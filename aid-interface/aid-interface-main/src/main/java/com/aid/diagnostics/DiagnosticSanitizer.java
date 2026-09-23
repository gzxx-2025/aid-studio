package com.aid.diagnostics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.mapper.AidAgentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** 保留诊断原文结构并去除秘密字段、智能体正文和媒体内联内容。 */
@Component
@RequiredArgsConstructor
public class DiagnosticSanitizer {
    private final AidAgentMapper agents;
    private volatile List<AidAgent> promptSources=List.of();
    private volatile long loadedAt;
    private static final Pattern SECRET=Pattern.compile("(?i).*(password|passwd|authorization|cookie|apikey|api_key|secret|privatekey|private_key|accesstoken|access_token|refreshtoken|refresh_token|credential).*");
    private static final String LOOSE_VALUE="(?:\"(?:\\\\.|[^\"\\\\])*+\"?|'(?:\\\\.|[^'\\\\])*+'?|[^\\r\\n,;}]+)";

    public Object clean(Object raw) {
        loadPrompts();
        return visit(raw);
    }
    private synchronized void loadPrompts() {
        if(System.currentTimeMillis()-loadedAt<60000) return;
        // 来源匹配需要正文，但仅在内存使用，不将智能体正文写入诊断。
        promptSources=agents.selectList(Wrappers.<AidAgent>lambdaQuery().select(AidAgent::getAgentCode,AidAgent::getName,AidAgent::getPromptContent));
        loadedAt=System.currentTimeMillis();
    }
    private Object visit(Object value) {
        return visit(value,new IdentityHashMap<>(),0);
    }
    private Object visit(Object value,IdentityHashMap<Object,Boolean> path,int depth) {
        if(depth>64)return Map.of("capture","嵌套过深，该分支未采集");
        boolean reference=value!=null && !(value instanceof String || value instanceof Number || value instanceof Boolean);
        if(reference && path.put(value,Boolean.TRUE)!=null)return Map.of("capture","循环引用，该分支未采集");
        try{return visitValue(value,path,depth);}
        finally{if(reference)path.remove(value);}
    }
    private Object visitValue(Object value,IdentityHashMap<Object,Boolean> path,int depth) {
        if(value instanceof byte[] bytes)return Map.of("capture","媒体二进制未采集","originalLength",bytes.length);
        if(value instanceof org.springframework.web.multipart.MultipartFile file)return Map.of("capture","文件内容未采集","originalLength",file.getSize());
        if(value instanceof Map<?,?> map) {
            Map<String,Object> result=new LinkedHashMap<>();
            map.forEach((key,item)->{if(key instanceof String name && secretField(name))DiagnosticCapture.modelSecret(item);});
            String role=map.get("role") instanceof String text?text:"";
            boolean system="system".equals(role)||"developer".equals(role);
            for(var item:map.entrySet()) {
                String key=item.getKey() instanceof String text?text:item.getKey() instanceof Enum<?> name?name.name():item.getKey() instanceof Number number?number.toString():"[非文本字段]";
                String normalized=key.replace("-","").replace("_","").toLowerCase(Locale.ROOT);
                if(secretField(normalized)) result.put(key,"[已过滤秘密字段]");
                else if(Set.of("promptcontent","systemprompt","systeminstruction","developerprompt","agentprompt","instructions").contains(normalized) || (system && (normalized.equals("content") || normalized.equals("parts")))) result.put(key,promptMarker(item.getValue()));
                else if(Set.of("reasoningcontent","directreasoning","thinking").contains(normalized))result.put(key,"[推理上下文未采集]");
                else if(normalized.equals("b64json") || normalized.equals("inlinedata") || normalized.endsWith("base64") || (normalized.equals("data") && (map.containsKey("mimeType") || map.containsKey("mime_type")))) result.put(key,Map.of("capture","媒体二进制未采集","originalLength",item.getValue() instanceof CharSequence text?text.length():-1));
                else result.put(key,visit(item.getValue(),path,depth+1));
            }
            return result;
        }
        if(value instanceof Collection<?> list) return list.stream().map(item->visit(item,path,depth+1)).toList();
        if(value!=null && value.getClass().isArray()) {
            List<Object> result=new ArrayList<>();for(int i=0;i<java.lang.reflect.Array.getLength(value);i++)result.add(visit(java.lang.reflect.Array.get(value,i),path,depth+1));return result;
        }
        if(value instanceof String text) {
            text=DiagnosticCapture.redactKnownSecrets(text);
            String trimmed=text.trim();
            if(trimmed.startsWith("data:")) {
                String data=trimmed.substring(5).trim();
                if(data.startsWith("{") || data.startsWith("[")) {Object cleaned=visit(data,path,depth+1);return Objects.equals(data,cleaned)?text:"data: "+cleaned;}
            }
            if(trimmed.startsWith("{")||trimmed.startsWith("[")) {
                try { Object parsed=JSON.parse(text);Object cleaned=visit(parsed,path,depth+1);return JSON.toJSONString(parsed).equals(JSON.toJSONString(cleaned))?text:JSON.toJSONString(cleaned); } catch(Exception ignored) { }
            }
            if(text.startsWith("data:") && text.contains(";base64,")) return "[媒体内联内容未采集，原长度="+text.length()+"]";
            for(AidAgent agent:promptSources) if(agent.getPromptContent()!=null && !agent.getPromptContent().isBlank()) {
                text=text.replace(agent.getPromptContent(),marker(agent));
                String escaped=JSON.toJSONString(agent.getPromptContent());text=text.replace(escaped.substring(1,escaped.length()-1),marker(agent));
            }
            if((text.startsWith("https://") || text.startsWith("http://") || text.startsWith("/")) && text.contains("%")) {
                String decoded=text;
                try {for(int i=0;i<3 && decoded.contains("%");i++)decoded=java.net.URLDecoder.decode(decoded,java.nio.charset.StandardCharsets.UTF_8);
                    String cleaned=redactText(decoded);if(!cleaned.equals(decoded))return cleaned;
                }catch(IllegalArgumentException ignored){ }
            }
            return redactText(text);
        }
        if(value==null || value instanceof Number || value instanceof Boolean)return value;
        // DTO 可嵌套在参数列表或响应 Map 中，不能等最终入库序列化时才展开。
        try {return visit(JSON.parse(JSON.toJSONString(value,(com.alibaba.fastjson2.filter.ValueFilter)(object,name,field)->{
            if(secretField(name)){DiagnosticCapture.modelSecret(field);return "[已过滤秘密字段]";}
            return visit(field,path,depth+1);
        },com.alibaba.fastjson2.JSONWriter.Feature.ReferenceDetection)),path,depth+1);}
        catch(RuntimeException | StackOverflowError unavailable){return Map.of("capture","对象无法安全转换，该分支未采集","type",value.getClass().getSimpleName());}
    }
    private boolean secretField(String name) {
        String key=name.replace("-","").replace("_","").toLowerCase(Locale.ROOT);
        return SECRET.matcher(key).matches() || Set.of("token","accesskey","xapitoken","xencryptkey","xencryptiv","xencryptts","captchatoken").contains(key);
    }
    private String redactText(String text) {
        // 畸形 JSON 和日志内嵌片段也不能保留系统角色正文。
        if(Pattern.compile("(?i)[\"']?role[\"']?\\s*:\\s*[\"']?(?:system|developer)(?:[\"']|\\s|[,}])").matcher(text).find()) {
            text=text.replaceAll("(?is)([\"']?(?:content|parts)[\"']?\\s*:\\s*)[\\[{].*","$1[已省略无法解析的系统提示词片段]");
            text=text.replaceAll("(?i)([\"']?(?:content|parts)[\"']?\\s*:\\s*)"+LOOSE_VALUE,"$1[已省略系统提示词]");
        }
        return text.replaceAll("(?i)([?&](?:key|api_key|token|signature|sign|x-amz-[^=\\s&]+|q-signature|access_token|authorization|credential|secret)=)[^&\\s]+","$1[已过滤]")
            .replaceAll("(?i)((?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/=-]+","$1[已过滤]")
            .replaceAll("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@","$1[已过滤]@")
            .replaceAll("(?im)(\\b(?:Cookie|Set-Cookie)\\s*:\\s*)[^\\r\\n]*","$1[已过滤]")
            .replaceAll("(?i)((?:[\"']?(?:password|passwd|api[_-]?key|secret|authorization|cookie|token|access[_-]?key|xencrypt(?:key|iv|ts)|access[_-]?token|refresh[_-]?token|credential|system[_-]?prompt|prompt[_-]?content)[\"']?)\\s*[:=]\\s*)"+LOOSE_VALUE,"$1[已过滤]");
    }
    private Object promptMarker(Object value) {
        String text=value instanceof CharSequence sequence?sequence.toString():"";
        for(AidAgent agent:promptSources) if(agent.getPromptContent()!=null && !agent.getPromptContent().isBlank() && text.contains(agent.getPromptContent())) return Map.of("content","[已省略智能体提示词]","agentCode",Objects.toString(agent.getAgentCode(),""),"agentName",Objects.toString(agent.getName(),""));
        Set<String> sourceCodes=DiagnosticCapture.promptSources();
        if(!sourceCodes.isEmpty())return Map.of("content","[已省略智能体提示词]","agents",promptSources.stream().filter(agent->sourceCodes.contains(agent.getAgentCode())).map(agent->Map.of("agentCode",Objects.toString(agent.getAgentCode(),""),"agentName",Objects.toString(agent.getName(),""))).toList());
        return Map.of("content","[已省略系统提示词]","agentCode","未提供来源编码");
    }
    private String marker(AidAgent agent) { return "[已省略智能体提示词："+agent.getName()+"（"+agent.getAgentCode()+"）]"; }
}
