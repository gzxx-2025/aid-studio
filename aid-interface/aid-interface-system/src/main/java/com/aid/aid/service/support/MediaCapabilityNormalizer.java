package com.aid.aid.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 兼容历史媒体能力中的数字字符串，保留扩展值和显式清除标记。 */
public final class MediaCapabilityNormalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)");
    private static final Set<String> NUMERIC_FIELDS = numericFields();
    private static final Set<String> BOOLEAN_FIELDS = Set.of("supportsTextInput", "supportsSystemPrompt",
            "supportsImageInput", "supportsMultiImageInput", "supportsVideoInput", "supportsReferenceAudio",
            "supportsFirstFrame", "supportsLastFrame");

    private MediaCapabilityNormalizer() { }

    public static String normalizeJson(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            Map<String, Object> capability = MAPPER.readValue(raw, new TypeReference<>() { });
            return MAPPER.writeValueAsString(normalize(capability));
        } catch (Exception ignored) {
            // 非法结构仍交给配置校验器拒绝。
            return raw;
        }
    }

    public static Map<String, Object> normalize(Map<String, Object> capability) {
        if (capability == null) return null;
        Map<String, Object> result = normalizeFields(capability);
        if (capability.get("sceneRules") instanceof Map<?, ?> scenes) {
            Map<String, Object> normalizedScenes = new LinkedHashMap<>();
            scenes.forEach((name, value) -> normalizedScenes.put(String.valueOf(name),
                    value instanceof Map<?, ?> scene ? normalizeFields(scene) : value));
            result.put("sceneRules", normalizedScenes);
        }
        return result;
    }

    private static Map<String, Object> normalizeFields(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((name, originalValue) -> {
            Object value = originalValue;
            String key = String.valueOf(name);
            if (NUMERIC_FIELDS.contains(key) && value instanceof String text
                    && DECIMAL.matcher(text.trim()).matches()) {
                value = new BigDecimal(text.trim());
            }
            if (BOOLEAN_FIELDS.contains(key)) {
                if (value instanceof Number number) {
                    BigDecimal flag = new BigDecimal(number.toString());
                    if (flag.compareTo(BigDecimal.ZERO) == 0) value = false;
                    else if (flag.compareTo(BigDecimal.ONE) == 0) value = true;
                } else if (value instanceof String text) {
                    if (Set.of("true", "1").contains(text.trim())) value = true;
                    else if (Set.of("false", "0").contains(text.trim())) value = false;
                }
            }
            result.put(key, value);
        });
        return result;
    }

    private static Set<String> numericFields() {
        Set<String> fields = new HashSet<>(List.of("minReferenceImages", "maxReferenceImages",
                "minReferenceVideos", "maxReferenceVideos", "minReferenceAudios", "maxReferenceAudios",
                "maxReferenceMaterials", "maxInputMediaTotalFileSizeMb", "maxInputOutputVideoDurationSeconds"));
        for (String prefix : List.of("referenceImage", "referenceVideo", "referenceAudio")) {
            for (String suffix : List.of("MinDurationSeconds", "MaxDurationSeconds", "MaxTotalDurationSeconds",
                    "MaxFileSizeMb", "MinDimensionPixels", "MaxDimensionPixels", "MinPixels", "MaxPixels",
                    "MinWidth", "MaxWidth", "MinHeight", "MaxHeight", "MinAspectRatio", "MaxAspectRatio",
                    "MinFps", "MaxFps")) fields.add(prefix + suffix);
        }
        return Set.copyOf(fields);
    }
}
