package com.aid.model.definition;

import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.aid.aid.domain.model.ModelParameterRule;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 素材统计仅从已完成权属和元数据校验的请求生成，客户端不能写入此命名空间。 */
public final class ModelMaterialStatistics {
    public static final Set<String> FIELDS = Set.of("materials.imageCount", "materials.videoCount", "materials.audioCount",
            "materials.totalCount", "materials.videoDurationSeconds", "materials.audioDurationSeconds");
    private ModelMaterialStatistics() { }
    public static boolean isField(String path) { return path != null && FIELDS.contains(path); }

    public static boolean usesStatistics(ModelParameterRule rule) {
        return rule.getConditions().stream().anyMatch(ModelMaterialStatistics::usesStatistics)
                || rule.getActions().stream().anyMatch(action -> isField(action.getField()) || isField(action.getValueField()));
    }

    private static boolean usesStatistics(ModelParameterRule.Condition condition) {
        return isField(condition.getField()) || condition.getConditions() != null
                && condition.getConditions().stream().anyMatch(ModelMaterialStatistics::usesStatistics);
    }

    public static boolean required(ModelCapabilityDefinition definition) {
        return definition != null && definition.getRules() != null && definition.getRules().stream().anyMatch(ModelMaterialStatistics::usesStatistics);
    }

    public static Map<String, Object> fromDeclaredRequest(ModelCapabilityDefinition definition, Object request,
            VerifiedMediaMetadataService metadata) {
        Map<String, Set<String>> urls = new LinkedHashMap<>();
        for (String type : List.of("image", "video", "audio")) urls.put(type, new LinkedHashSet<>());
        collectDeclared(definition.getParameters(), JSON.parseObject(JSON.toJSONString(request)), urls);
        if (request instanceof MediaImageGenerateRequest image) {
            collect(urls.get("image"), image.getReferenceImageUrl());
            if (image.getOptions() != null) for (String key : List.of("referenceImages", "images", "key_images", "image_settings"))
                collect(urls.get("image"), image.getOptions().get(key));
        }
        if (request instanceof MediaTextGenerateRequest text) collectMessageMedia(JSON.parse(JSON.toJSONString(text.getMessages())), urls);
        Map<String, Object> result = new LinkedHashMap<>();
        int total = 0;
        for (var entry : urls.entrySet()) {
            result.put(entry.getKey() + "Count", entry.getValue().size());
            total += entry.getValue().size();
            if ("image".equals(entry.getKey())) continue;
            BigDecimal seconds = BigDecimal.ZERO;
            for (String url : entry.getValue()) {
                BigDecimal duration = metadata.inspect(url, entry.getKey()).durationSeconds();
                if (duration == null) throw new ServiceException("素材缺少可信时长");
                seconds = seconds.add(duration);
            }
            result.put(entry.getKey() + "DurationSeconds", seconds);
        }
        result.put("totalCount", total);
        return result;
    }

    private static void collectDeclared(List<ModelParameter> fields, Map<?, ?> values, Map<String, Set<String>> urls) {
        if (fields == null || values == null) return;
        for (ModelParameter field : fields) {
            Object value = values.get(field.getName());
            if (value == null) continue;
            // 蒙版只定义编辑区域，不属于计费/能力规则中的参考素材数量。
            if ("mask".equals(field.getMaterialRole())) continue;
            if (field.getMaterialRole() != null) {
                String type = "reference_video".equals(field.getMaterialRole()) ? "video"
                        : "reference_audio".equals(field.getMaterialRole()) ? "audio" : "image";
                ModelMaterialValidator.collect(urls.get(type), value);
            }
            if (value instanceof Map<?, ?> nested) collectDeclared(field.getProperties(), nested, urls);
            if (value instanceof List<?> list && field.getItems() != null) for (Object item : list)
                if (item != null) collectDeclared(List.of(field.getItems()), Map.of(field.getItems().getName(), item), urls);
        }
    }

    private static void collectMessageMedia(Object value, Map<String, Set<String>> urls) {
        if (value instanceof List<?> list) list.forEach(item -> collectMessageMedia(item, urls));
        else if (value instanceof Map<?, ?> map) {
            String type = String.valueOf(map.get("type"));
            for (String media : urls.keySet()) if ((media + "_url").equals(type))
                ModelMaterialValidator.collect(urls.get(media), map.get(type));
            if (map.containsKey("content")) collectMessageMedia(map.get("content"), urls);
        }
    }

    public static Map<String, Object> fromVerifiedVideo(MediaVideoGenerateRequest request) {
        Set<String> images = new LinkedHashSet<>();
        collect(images, request.getImageUrl());
        if (request.getOptions() != null) for (String key : List.of("referenceImages", "images", "key_images", "image_settings", "lastFrameImageUrl")) collect(images, request.getOptions().get(key));
        Map<String, BigDecimal> videos = new LinkedHashMap<>();
        if (request.getResolvedReferenceVideos() != null) for (var video : request.getResolvedReferenceVideos()) {
            if (video.getDurationMs() == null) throw new ServiceException("参考视频缺少可信时长");
            videos.put(video.getVideoUrl(), BigDecimal.valueOf(video.getDurationMs()).movePointLeft(3));
        }
        Map<String, BigDecimal> audios = new LinkedHashMap<>();
        if (request.getReferenceAudios() != null) for (var audio : request.getReferenceAudios()) {
            if (audio.getDurationMs() == null) throw new ServiceException("参考音频缺少可信时长");
            audios.put(audio.getSampleUrl(), BigDecimal.valueOf(audio.getDurationMs()).movePointLeft(3));
        }
        return Map.of("imageCount", images.size(), "videoCount", videos.size(), "audioCount", audios.size(),
                "totalCount", images.size() + videos.size() + audios.size(),
                "videoDurationSeconds", videos.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                "audioDurationSeconds", audios.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static void collect(Set<String> urls, Object input) {
        if (input instanceof String text && !text.isBlank()) urls.add(text);
        else if (input instanceof List<?> list) list.forEach(item -> collect(urls, item));
        else if (input instanceof Map<?, ?> map) {
            for (String key : List.of("url", "image_url", "imageUrl", "key_image", "keyImage"))
                if (map.get(key) != null) { collect(urls, map.get(key)); break; }
        }
    }
}
