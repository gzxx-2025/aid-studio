package com.aid.diagnostics;

import cn.hutool.http.GlobalInterceptor;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.domain.AidDiagnosticTrace;
import com.aid.common.utils.SecurityUtils;
import cn.hutool.crypto.SecureUtil;

/** 在错误翻译前收集原始请求、响应和异常。 */
@Service
@RequiredArgsConstructor
public class DiagnosticCapture {
    private final DiagnosticSettings settings;
    private final DiagnosticSanitizer sanitizer;
    private final DiagnosticRepository mapper;
    private static volatile DiagnosticCapture instance;
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    public final AtomicLong captureFailures=new AtomicLong();

    public static final class Context {
        public final String requestId;
        public final String startedAt=Instant.now().toString();
        public final JSONObject data=new JSONObject();
        public final List<JSONObject> calls=new ArrayList<>();
        public final Context parent;
        public boolean failed;
        public boolean submitted;
        public Long userId;
        private final Set<String> secrets;
        private boolean modelCredentials;
        Context(String id,Context parent) { requestId=id;this.parent=parent;secrets=parent==null?new LinkedHashSet<>():parent.secrets; }
    }
    @PostConstruct void initialize() {
        instance=this;
        GlobalInterceptor.INSTANCE.addRequestInterceptor(request->{
            if(CURRENT.get()==null) return;
            try {
                registerHeaders(request.headers());
                String contentType=Objects.toString(request.header("Content-Type"),"");
                Object body=contentType.startsWith("multipart/") ? "[文件上传内容未采集]" : new String(request.bodyBytes()==null?new byte[0]:request.bodyBytes(),StandardCharsets.UTF_8);
                transportRequest(request.getUrl(),String.valueOf(request.getMethod()),body);
            } catch(Exception ignored) { captureFailures.incrementAndGet(); }
        });
        GlobalInterceptor.INSTANCE.addResponseInterceptor(response->{
            if(CURRENT.get()==null) return;
            String contentType=Objects.toString(response.header("Content-Type"),"");
            if(contentType.contains("text/event-stream") || contentType.startsWith("image/") || contentType.startsWith("audio/") || contentType.startsWith("video/") || contentType.contains("octet-stream")) return;
            try { transportResponse(response.getStatus(),response.body(),response.header("x-request-id")); }
            catch(Exception ignored) { captureFailures.incrementAndGet(); }
        });
    }
    @org.springframework.context.event.EventListener
    public void taskCompleted(com.aid.media.event.MediaTaskCompletedEvent event) {
        try {mapper.deleteTrace(SecureUtil.sha256("task:"+event.getTaskId()));}
        catch(Exception ignored) {captureFailures.incrementAndGet();}
    }
    public Context begin(String requestId,Object request) {
        return begin(requestId,request,false);
    }
    public Context beginProvider(String requestId,Object request) {
        return begin(requestId,request,true);
    }
    private Context begin(String requestId,Object request,boolean modelCredentials) {
        if(!settings.enabled()) return null;
        Context parent=CURRENT.get();
        try {
            Context context=new Context(validId(requestId),parent);context.modelCredentials=modelCredentials;
            try {context.userId=SecurityUtils.getUserId();}catch(Exception ignored) {if(context.parent!=null)context.userId=context.parent.userId;}
            CURRENT.set(context);
            context.data.put("request",sanitizer.clean(request));
            return context;
        } catch(Exception ignored) {if(parent==null)CURRENT.remove();else CURRENT.set(parent);captureFailures.incrementAndGet();return null; }
    }
    public static String validId(String value) {
        try { return UUID.fromString(value).toString(); } catch(Exception ignored) {return UUID.randomUUID().toString();}
    }
    public static Context current() { return CURRENT.get(); }
    /** 仅收集当前模型调用参数中的凭据，集合绝不写入诊断正文或任务快照。 */
    public static void modelSecret(Object value) {
        Context context=CURRENT.get();if(context!=null && context.modelCredentials)registerSecret(context,value);
    }
    private static void registerSecret(Context context,Object value) {
        if(value instanceof CharSequence sequence) {
            String secret=sequence.toString();
            if(!secret.isBlank() && !secret.startsWith("[已过滤") && !secret.startsWith("[已省略") && secret.length()<=16384 && context.secrets.size()<128)context.secrets.add(secret);
        }else if(value instanceof Collection<?> values)for(Object item:values)if(item instanceof CharSequence)registerSecret(context,item);
    }
    public static void registerHeaders(Map<String,List<String>> headers) {
        Context context=CURRENT.get();if(context==null)return;
        try {headers.forEach((name,values)->{
            String key=name.replace("-","").replace("_","").toLowerCase(Locale.ROOT);
            if(key.contains("authorization") || key.contains("apikey") || key.contains("cookie") || key.endsWith("token"))for(String value:values) {
                registerSecret(context,value);
                if(value.regionMatches(true,0,"Bearer ",0,7))registerSecret(context,value.substring(7).trim());
                if(value.regionMatches(true,0,"Basic ",0,6))registerSecret(context,value.substring(6).trim());
                if(key.contains("cookie"))for(String part:value.split(";")){int equal=part.indexOf('=');if(equal>=0)registerSecret(context,part.substring(equal+1).trim());}
            }
        });}catch(Exception ignored){if(instance!=null)instance.captureFailures.incrementAndGet();}
    }
    public static String redactKnownSecrets(String text) {
        if(text==null)return null;
        Context context=CURRENT.get();if(context==null)return text;
        for(String secret:context.secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            text=text.replace(secret,"[已过滤已知凭据]");
            String escaped=JSON.toJSONString(secret);text=text.replace(escaped.substring(1,escaped.length()-1),"[已过滤已知凭据]");
        }
        return text;
    }
    public static Object publicFailureValue(Object value) {
        return publicFailureValue(value,new IdentityHashMap<>(),0);
    }
    private static Object publicFailureValue(Object value,IdentityHashMap<Object,Boolean> path,int depth) {
        if(value instanceof String text)return redactKnownSecrets(text);
        if(!(value instanceof Map<?,?> || value instanceof Collection<?>))return value;
        if(depth>64 || path.put(value,Boolean.TRUE)!=null)return "[循环或过深的错误详情已省略]";
        try {
            if(value instanceof Map<?,?> map){Map<Object,Object> result=new LinkedHashMap<>();map.forEach((key,item)->result.put(key,publicFailureValue(item,path,depth+1)));return result;}
            return ((Collection<?>)value).stream().map(item->publicFailureValue(item,path,depth+1)).toList();
        }finally{path.remove(value);}
    }
    public static String publicFailureJson(String value) {
        if(value==null)return null;
        try {Object parsed=JSON.parse(value);Object safe=publicFailureValue(parsed);return JSON.toJSONString(parsed).equals(JSON.toJSONString(safe))?value:JSON.toJSONString(safe);}
        catch(Exception ignored){return redactKnownSecrets(value);}
    }
    public static void promptSource(String agentCode) {
        Context context=CURRENT.get();
        if(context==null || agentCode==null)return;
        Set<String> sources=(Set<String>)context.data.computeIfAbsent("agentSources",key->new LinkedHashSet<String>());
        sources.add(agentCode);
    }
    public static Set<String> promptSources() {
        Set<String> sources=new LinkedHashSet<>();
        for(Context context=CURRENT.get();context!=null;context=context.parent) {
            Object values=context.data.get("agentSources");
            if(values instanceof Collection<?> collection)collection.forEach(value->sources.add(String.valueOf(value)));
            JSONObject original=context.data.getJSONObject("originalRequest");
            if(original!=null && original.get("agentSources") instanceof Collection<?> collection)collection.forEach(value->sources.add(String.valueOf(value)));
        }
        return sources;
    }
    public void finish(Context context,Object response,Throwable error,boolean failed,String source) {
        if(context==null) return;
        try {
            if(error!=null) {
                StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));
                context.data.put("exception",sanitizer.clean(Map.of("type",error.getClass().getName(),"stack",stack.toString())));
            }
            if(response!=null) context.data.put("response",sanitizer.clean(response));
            context.data.put("modelCalls",context.calls);
            if(context.parent!=null) {
                context.parent.calls.addAll(context.calls);
                if(context.calls.isEmpty() && context.data.containsKey("response")) {
                    JSONObject boundary=new JSONObject();
                    boundary.put("providerRequest",context.data.get("request"));
                    boundary.put("providerResponse",context.data.get("response"));
                    context.parent.calls.add(boundary);
                }
                context.parent.failed |= failed || error!=null || context.failed;
                context.parent.submitted |= context.submitted;
                if(error!=null)context.parent.data.put("providerException",context.data.get("exception"));
            } else if(failed || error!=null || context.failed) {
                context.data.put("capture",Map.of("source",source,"textTruncated",false));
                persist("server".equals(source)?context.requestId:UUID.randomUUID().toString(),context.requestId,context.startedAt,source,context.data);
            }
        } catch(Exception ignored) { captureFailures.incrementAndGet(); }
        finally { if(context.parent==null) {context.secrets.clear();CURRENT.remove();}else CURRENT.set(context.parent); }
    }
    public void persist(String id,String requestId,String time,String source,Object diagnostic) {
        if(!settings.enabled()) return;
        try {
            // 容量保护拒绝整条写入并计数，不能把截断正文伪装成完整错误。
            if(mapper.storedBytes()>1024L*1024*1024) { captureFailures.incrementAndGet();return; }
            Context context=CURRENT.get();
            mapper.insert(id,requestId,time,source,JSON.toJSONString(sanitizer.clean(diagnostic)),context==null?null:context.userId);
        } catch(Exception ignored) { captureFailures.incrementAndGet(); }
    }
    public static void rememberTask(AidMediaTask task) {
        Context context=CURRENT.get();if(instance==null || context==null || task==null || task.getId()==null)return;
        try {instance.mapper.saveTrace(SecureUtil.sha256("task:"+task.getId()),context.requestId,JSON.toJSONString(instance.sanitizer.clean(context.data)));}
        catch(Exception ignored){instance.captureFailures.incrementAndGet();}
    }
    public static TaskScope task(AidMediaTask task) {
        if(instance==null || task==null || !instance.settings.enabled())return new TaskScope(null,task);
        Context context=null;
        try {
            AidDiagnosticTrace trace=instance.mapper.trace(SecureUtil.sha256("task:"+task.getId()));
            context=instance.begin(trace==null?null:trace.getRequestId(),Map.of("taskId",task.getId(),"businessRequest",Objects.toString(task.getRequestJson(),"")));
            if(context!=null) {context.userId=task.getUserId();if(trace!=null)context.data.put("originalRequest",JSON.parse(trace.getRequestJson()));}
        } catch(Exception ignored){instance.captureFailures.incrementAndGet();}
        return new TaskScope(context,task);
    }
    public static final class TaskScope implements AutoCloseable {
        private final Context context;private final AidMediaTask task;
        TaskScope(Context context,AidMediaTask task){this.context=context;this.task=task;}
        public void close(){
            if(context==null)return;
            try {
                if(context.submitted) {context.data.put("modelCalls",context.calls);instance.mapper.saveTrace(SecureUtil.sha256("task:"+task.getId()),context.requestId,JSON.toJSONString(instance.sanitizer.clean(context.data)));}
                if("SUCCEEDED".equals(task.getStatus()) || "FAILED".equals(task.getStatus()))instance.mapper.deleteTrace(SecureUtil.sha256("task:"+task.getId()));
            }catch(Exception ignored){instance.captureFailures.incrementAndGet();}
            instance.finish(context,Map.of("taskId",task.getId(),"status",Objects.toString(task.getStatus(),""),"providerTaskId",Objects.toString(task.getProviderTaskId(),"")),null,"FAILED".equals(task.getStatus()),"task");
        }
    }
    public static void transportRequest(String url,String method,Object body) {
        Context context=CURRENT.get();
        if(context==null || instance==null) return;
        try {
            JSONObject call=new JSONObject();call.put("url",instance.sanitizer.clean(url));call.put("method",method);
            call.put("requestTime",Instant.now().toString());call.put("requestBody",instance.sanitizer.clean(body));
            context.calls.add(call);
        } catch(Exception ignored) { instance.captureFailures.incrementAndGet(); }
    }
    public static String outboundBody(String body) {
        transportRequest("待发送","POST",body);
        return body;
    }
    public static void transportMetadata(String url,String method) {
        Context context=CURRENT.get();
        if(context==null || instance==null)return;
        try {
        if(context.calls.isEmpty())transportRequest(url,method,"请求正文未取得");
        else {
            JSONObject call=context.calls.get(context.calls.size()-1);
            call.put("url",instance.sanitizer.clean(url));call.put("method",method);
        }
        }catch(Exception ignored){instance.captureFailures.incrementAndGet();}
    }
    public static void transportComplete(boolean complete) {
        Context context=CURRENT.get();
        if(context!=null && !context.calls.isEmpty())context.calls.get(context.calls.size()-1).put("responseComplete",complete);
    }
    public static void transportFailure(Throwable error) {
        Context context=CURRENT.get();
        if(context==null || instance==null)return;
        context.failed=true;
        try {StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));context.data.put("transportException",instance.sanitizer.clean(Map.of("type",error.getClass().getName(),"stack",stack.toString())));transportComplete(false);}
        catch(Exception ignored){instance.captureFailures.incrementAndGet();}
    }
    public static void transportResponse(int status,Object body,String upstreamRequestId) {
        Context context=CURRENT.get();
        if(context==null || instance==null) return;
        try {
            if(context.calls.isEmpty()) context.calls.add(new JSONObject());
            JSONObject call=context.calls.get(context.calls.size()-1);
            call.put("status",status);call.put("responseBody",instance.sanitizer.clean(body));call.put("upstreamRequestId",upstreamRequestId);
            if(status>=400) context.failed=true;
        } catch(Exception ignored) { instance.captureFailures.incrementAndGet(); }
    }
    public static String streamLine(String line) {
        Context context=CURRENT.get();
        if(context!=null && instance!=null) try {
            if(context.calls.isEmpty())context.calls.add(new JSONObject());
            JSONObject call=context.calls.get(context.calls.size()-1);
            List<Object> lines=(List<Object>)call.computeIfAbsent("receivedLines",key->new ArrayList<>());
            lines.add(instance.sanitizer.clean(line));
            if(line.contains("\"error\"") || line.startsWith("event: error"))context.failed=true;
        } catch(Exception ignored) { instance.captureFailures.incrementAndGet(); }
        return line;
    }
}
