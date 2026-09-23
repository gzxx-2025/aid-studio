package com.aid.model.definition;

import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.util.ModelCapabilityResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 公共媒体请求的可配置字段；身份、计费权限和业务关联不属于模型参数。 */
public final class ModelRequestParameters {
    private static final Map<String, Set<String>> ROOTS = Map.of(
            "text", Set.of("prompt", "messages", "options", "reasoningEnabled", "reasoningLevel", "reasoningBudgetTokens", "includeReasoning"),
            "image", Set.of("prompt", "size", "negativePrompt", "referenceImageUrl", "maskImageUrl", "maskEncoding",
                    "targetWidth", "targetHeight", "sourceX", "sourceY",
                    "brightness", "colorTemperature", "keyLightAzimuth", "keyLightElevation", "rimLightEnabled",
                    "rimLightAzimuth", "rimLightElevation", "options", "expectedImageCount"),
            "video", Set.of("prompt", "imageUrl", "durationSeconds", "aspectRatio", "options", "audio", "bgm", "audioType", "voiceId", "referenceAudios", "referenceVideoRecordIds"),
            "audio", Set.of("ttsText", "voiceCode", "language", "emotion", "emotionScale", "speechRate", "loudnessRate", "pitch", "audioFormat", "sampleRate", "enableTimestamp", "options"));

    private ModelRequestParameters() { }

    /** 既有公共接口的规格别名在 Schema 默认值之前统一，沿用原规格等价规则。 */
    public static void normalizeAliases(String modelType, ModelCapabilityDefinition definition, Map<String, Object> parameters) {
        Map<String, ModelParameter> fields = new LinkedHashMap<>();
        collect(definition.getParameters(), "", fields);
        if ("image".equals(modelType)) {
            alias(fields, parameters, "options.aspectRatio", "options.aspect_ratio");
            alias(fields, parameters, "size", "options.size");
        }
        if ("video".equals(modelType)) alias(fields, parameters, "options.resolution", "options.size");
        for (String path : Set.of("size", "aspectRatio", "options.aspectRatio", "options.aspect_ratio", "options.size", "options.resolution")) {
            var field = fields.get(path);
            Object value = ModelParameterValidator.read(parameters, path);
            if (field == null || !(value instanceof String text) || field.getChoices() == null) continue;
            var choices = field.getChoices().stream().filter(String.class::isInstance).map(String.class::cast).toList();
            String matched = ModelCapabilityResolver.matchOption(choices, text);
            if (matched != null) ModelParameterValidator.write(parameters, path, matched);
        }
    }

    private static void alias(Map<String, ModelParameter> fields, Map<String, Object> parameters, String target, String source) {
        if (!fields.containsKey(target)) return;
        Object value = ModelParameterValidator.read(parameters, source);
        Object existing = ModelParameterValidator.read(parameters, target);
        if (value == null) return;
        if (existing != null && !Objects.equals(existing, value)
                && !(existing instanceof String left && value instanceof String right
                && ModelCapabilityResolver.normalize(left).equals(ModelCapabilityResolver.normalize(right))))
            throw new ServiceException("参数别名冲突：" + target);
        if (existing == null) ModelParameterValidator.write(parameters, target, value);
    }

    private static void collect(List<ModelParameter> parameters, String prefix,
            Map<String, ModelParameter> fields) {
        if (parameters == null) return;
        for (var field : parameters) {
            fields.put(prefix + field.getName(), field);
            collect(field.getProperties(), prefix + field.getName() + ".", fields);
        }
    }

    public static void validate(String modelType, ModelCapabilityDefinition definition) {
        Set<String> allowed = ROOTS.getOrDefault(modelType, Set.of());
        if (definition.getParameters() == null) return;
        for (var parameter : definition.getParameters()) {
            if (!allowed.contains(parameter.getName())) throw new ServiceException("参数来源不支持：" + parameter.getName() + "，扩展参数请放入 options 分组");
            Class<?> requestType = switch (modelType) {
                case "text" -> MediaTextGenerateRequest.class;
                case "image" -> MediaImageGenerateRequest.class;
                case "video" -> MediaVideoGenerateRequest.class;
                default -> MediaAudioGenerateRequest.class;
            };
            try {
                Class<?> type = requestType.getDeclaredField(parameter.getName()).getType();
                String expected = Map.class.isAssignableFrom(type) ? "object"
                        : List.class.isAssignableFrom(type) ? "array"
                        : type == Boolean.class || type == boolean.class ? "boolean"
                        : type == Integer.class || type == Long.class ? "integer" : "string";
                if (!expected.equals(parameter.getType())) throw new ServiceException("参数类型与业务接口不符：" + parameter.getName());
            } catch (NoSuchFieldException ex) { throw new ServiceException("参数来源未实现"); }
        }
    }
}
