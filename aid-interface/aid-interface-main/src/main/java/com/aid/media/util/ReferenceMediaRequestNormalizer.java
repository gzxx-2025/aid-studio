package com.aid.media.util;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 任务落库前统一去重、校验并回写参考图片与参考视频。 */
@Slf4j
public final class ReferenceMediaRequestNormalizer {

    private static final String KEY_MAX_REFERENCE_IMAGES = "maxReferenceImages";
    private static final String KEY_MAX_REFERENCE_VIDEOS = "maxReferenceVideos";
    private static final String[] LAST_IMAGE_KEYS = {"lastFrameImageUrl", "endImageUrl", "end_image_url"};
    private static final String[] IMAGE_SETTING_KEYS = {"image_settings", "imageSettings"};
    private static final String[] KEY_FRAME_IMAGE_KEYS = {"key_images", "keyImages"};
    private static final String[] STRUCTURED_IMAGE_KEYS = {
            "image_settings", "imageSettings", "key_images", "keyImages"
    };
    private static final String[] STRUCTURED_IMAGE_URL_KEYS = {
            "key_image", "keyImage", "image_url", "imageUrl", "url"
    };
    private static final String[] FEATURE_VIDEO_KEYS = {"featureVideoUrl", "referenceVideoUrl"};
    private static final String[] BASE_VIDEO_KEYS = {"baseVideoUrl", "inputVideoUrl"};
    private static final String[] SHARED_VIDEO_KEYS = {"videoUrl", "video_url"};

    private ReferenceMediaRequestNormalizer() {
    }

    public static void normalize(AiModelConfigVo modelConfig, MediaImageGenerateRequest request,
                                 Integer providerFallbackMaxImages) {
        if (request == null) {
            return;
        }
        Map<String, Object> options = mutableOptions(request.getOptions());
        LinkedHashSet<String> images = new LinkedHashSet<>();
        addUrl(images, request.getReferenceImageUrl());
        addUrls(images, options.get("referenceImages"));
        addUrls(images, options.get("images"));
        List<String> normalized = applyConfiguredLimit(new ArrayList<>(images),
                configuredLimit(modelConfig, KEY_MAX_REFERENCE_IMAGES, providerFallbackMaxImages),
                modelConfig, "参考图");

        // 图片编辑的第一张图具有“被编辑原图”的固定语义，必须独立保留，不能在归一化时
        // 混入普通参考图列表。这样蒙版、扩图几何校验及异步结果保护始终指向同一原图。
        boolean imageEdit = Set.of("image_edit", "image_inpainting", "image_outpainting")
                .contains(StrUtil.blankToDefault(request.getCapabilityCode(), "").trim().toLowerCase());
        String source = StrUtil.trim(request.getReferenceImageUrl());
        if (imageEdit && StrUtil.isNotBlank(source)) {
            request.setReferenceImageUrl(source);
            normalized.removeIf(source::equals);
        } else {
            request.setReferenceImageUrl(null);
        }
        options.remove("images");
        putListOrRemove(options, "referenceImages", normalized);
        request.setOptions(options.isEmpty() ? null : options);
    }

    public static void normalize(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
                                 Integer providerFallbackMaxImages,
                                 Integer providerFallbackMaxVideos) {
        if (request == null) {
            return;
        }
        Map<String, Object> options = mutableOptions(request.getOptions());
        // 对口型的 video_url 是源视频契约键，不属于参考视频别名，必须原样保留。
        if (options.containsKey("video_url") && options.containsKey("audio_url")) {
            request.setOptions(options);
            return;
        }
        normalizeVideoImages(modelConfig, request, options, providerFallbackMaxImages);
        normalizeVideoReferences(modelConfig, options, providerFallbackMaxVideos);
        request.setOptions(options.isEmpty() ? null : options);
    }

    private static void normalizeVideoImages(AiModelConfigVo modelConfig,
                                             MediaVideoGenerateRequest request,
                                             Map<String, Object> options,
                                             Integer providerFallbackMaxImages) {
        List<ImageSlot> simple = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addSemanticImageSlot(simple, seen, ImageSlotType.FIRST, request.getImageUrl());
        addSemanticImageSlot(simple, seen, ImageSlotType.LAST, firstText(options, LAST_IMAGE_KEYS));
        addImageUrls(simple, seen, options.get("referenceImages"));
        addImageUrls(simple, seen, options.get("images"));

        StructuredImages structuredImages = normalizeStructuredImages(modelConfig, options, seen);
        String structuredKey = structuredImages.key();
        List<Object> structured = structuredImages.values();
        int before = simple.size() + structured.size();
        Integer max = configuredLimit(modelConfig, KEY_MAX_REFERENCE_IMAGES, providerFallbackMaxImages);
        if (max != null && max >= 0 && before > max) {
            rejectExcess(modelConfig, "参考图", max, before);
        }

        request.setImageUrl(simple.stream()
                .filter(slot -> slot.type() == ImageSlotType.FIRST).map(ImageSlot::url).findFirst().orElse(null));
        for (String key : LAST_IMAGE_KEYS) {
            options.remove(key);
        }
        simple.stream().filter(slot -> slot.type() == ImageSlotType.LAST).map(ImageSlot::url).findFirst()
                .ifPresent(url -> options.put(LAST_IMAGE_KEYS[0], url));
        options.remove("images");
        putListOrRemove(options, "referenceImages", simple.stream()
                .filter(slot -> slot.type() == ImageSlotType.REFERENCE).map(ImageSlot::url).toList());
        for (String key : STRUCTURED_IMAGE_KEYS) {
            options.remove(key);
        }
        if (structuredKey != null && !structured.isEmpty()) {
            options.put(structuredKey, structured);
        }
    }

    private static void normalizeVideoReferences(AiModelConfigVo modelConfig, Map<String, Object> options,
                                                 Integer providerFallbackMaxVideos) {
        List<VideoSlot> videos = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addVideoAliases(videos, seen, options, "referenceVideoUrl", FEATURE_VIDEO_KEYS);
        addVideoAliases(videos, seen, options, "baseVideoUrl", BASE_VIDEO_KEYS);
        addVideoAliases(videos, seen, options, "videoUrl", SHARED_VIDEO_KEYS);
        addVideoUrls(videos, seen, options.get("referenceVideos"));
        addVideoUrls(videos, seen, options.get("videos"));
        Integer max = configuredLimit(modelConfig, KEY_MAX_REFERENCE_VIDEOS, providerFallbackMaxVideos);
        if (max != null && max >= 0 && videos.size() > max) {
            rejectExcess(modelConfig, "参考视频", max, videos.size());
        }
        for (String key : FEATURE_VIDEO_KEYS) options.remove(key);
        for (String key : BASE_VIDEO_KEYS) options.remove(key);
        for (String key : SHARED_VIDEO_KEYS) options.remove(key);
        options.remove("referenceVideos");
        options.remove("videos");
        List<String> listValues = new ArrayList<>();
        for (VideoSlot slot : videos) {
            if (slot.key() == null) listValues.add(slot.url());
            else options.put(slot.key(), slot.url());
        }
        putListOrRemove(options, "referenceVideos", listValues);
    }

    private static <T> List<T> applyConfiguredLimit(List<T> values, Integer max,
                                                     AiModelConfigVo modelConfig, String type) {
        if (max == null || max < 0 || values.size() <= max) {
            return values;
        }
        rejectExcess(modelConfig, type, max, values.size());
        return values;
    }

    private static Integer configuredLimit(AiModelConfigVo config, String key, Integer providerFallback) {
        JsonNode capability = ModelCapabilityResolver.parseCapability(config == null ? null : config.getCapabilityJson());
        JsonNode node = capability == null ? null : capability.get(key);
        if (node != null && node.isNumber()) {
            int configured = node.intValue();
            if (KEY_MAX_REFERENCE_IMAGES.equals(key) && config != null
                    && Boolean.FALSE.equals(config.getSupportsMultiImageInput())) {
                int semanticLimit = nonMultiImageSlotLimit(config);
                return configured < 0 ? semanticLimit : Math.min(semanticLimit, configured);
            }
            return configured;
        }
        if (KEY_MAX_REFERENCE_IMAGES.equals(key) && config != null
                && Boolean.FALSE.equals(config.getSupportsMultiImageInput())) {
            return nonMultiImageSlotLimit(config);
        }
        return providerFallback;
    }

    /**
     * 非多图模型仍可能同时具有首帧和尾帧两个语义槽位。它们不是任意多图输入，
     * 但归一化阶段必须允许这两个明确槽位同时存在，并继续拒绝第三张额外参考图。
     */
    private static int nonMultiImageSlotLimit(AiModelConfigVo config) {
        return Boolean.TRUE.equals(config.getSupportsFirstFrame())
                && Boolean.TRUE.equals(config.getSupportsLastFrame()) ? 2 : 1;
    }

    private static void rejectExcess(AiModelConfigVo config, String type, int max, int actual) {
        log.info("{}超过模型能力上限: modelCode={}, max={}, actual={}",
                type, config == null ? null : config.getModelCode(), max, actual);
        throw new ServiceException(type + "数量超限");
    }

    private static Map<String, Object> mutableOptions(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static void putListOrRemove(Map<String, Object> options, String key, List<?> values) {
        if (values == null || values.isEmpty()) options.remove(key);
        else options.put(key, values);
    }

    private static String firstText(Map<String, Object> options, String... keys) {
        for (String key : keys) {
            Object value = options.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static void addUrl(Set<String> target, Object value) {
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) target.add(String.valueOf(value).trim());
    }

    private static void addUrls(Set<String> target, Object value) {
        if (value instanceof List<?> list) list.forEach(item -> addUrl(target, item));
        else addUrl(target, value);
    }

    private static void addImageSlot(List<ImageSlot> target, Set<String> seen,
                                     ImageSlotType type, String value) {
        if (StrUtil.isNotBlank(value) && seen.add(value.trim())) target.add(new ImageSlot(type, value.trim()));
    }

    private static void addSemanticImageSlot(List<ImageSlot> target, Set<String> seen,
                                             ImageSlotType type, String value) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        String normalized = value.trim();
        target.add(new ImageSlot(type, normalized));
        // 首帧与尾帧即使 URL 相同也必须保留两个协议槽位；普通参考图仍与两者按 URL 去重。
        seen.add(normalized);
    }

    private static void addImageUrls(List<ImageSlot> target, Set<String> seen, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addImageSlot(target, seen, ImageSlotType.REFERENCE,
                    item == null ? null : String.valueOf(item)));
        } else if (value != null) {
            addImageSlot(target, seen, ImageSlotType.REFERENCE, String.valueOf(value));
        }
    }

    private static StructuredImages normalizeStructuredImages(AiModelConfigVo modelConfig,
                                                               Map<String, Object> options,
                                                               Set<String> seen) {
        List<Object> settings = mergeListOptions(options, IMAGE_SETTING_KEYS);
        List<Object> keyFrames = mergeListOptions(options, KEY_FRAME_IMAGE_KEYS);
        if (!settings.isEmpty()) {
            if (!keyFrames.isEmpty()) {
                log.info("结构化参考图同时存在互斥输入: modelCode={}",
                        modelConfig == null ? null : modelConfig.getModelCode());
                throw new ServiceException("参考图片配置冲突");
            }
            List<Object> normalized = new ArrayList<>();
            for (Object value : settings) {
                Map<String, Object> item = normalizeImageSetting(value);
                String url = structuredImageUrl(item);
                if (seen.add(url)) {
                    normalized.add(item);
                }
            }
            return new StructuredImages("image_settings", normalized);
        }
        if (keyFrames.isEmpty()) {
            return new StructuredImages(null, new ArrayList<>());
        }
        List<Object> normalized = new ArrayList<>();
        for (Object value : keyFrames) {
            String url = structuredImageUrl(value);
            if (StrUtil.isBlank(url)) {
                throw new ServiceException("参考图无效");
            }
            if (seen.add(url)) {
                normalized.add(url);
            }
        }
        return new StructuredImages("key_images", normalized);
    }

    private static List<Object> mergeListOptions(Map<String, Object> options, String... keys) {
        List<Object> values = new ArrayList<>();
        for (String key : keys) {
            Object raw = options.get(key);
            if (raw instanceof List<?> list) {
                values.addAll(list);
            }
        }
        return values;
    }

    private static Map<String, Object> normalizeImageSetting(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ServiceException("参考图无效");
        }
        String url = structuredImageUrl(raw);
        if (StrUtil.isBlank(url)) {
            throw new ServiceException("参考图无效");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) {
                normalized.put(String.valueOf(key), item);
            }
        });
        for (String key : STRUCTURED_IMAGE_URL_KEYS) {
            normalized.remove(key);
        }
        normalized.put("key_image", url);
        return normalized;
    }

    private static String structuredImageUrl(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : STRUCTURED_IMAGE_URL_KEYS) {
                Object url = map.get(key);
                if (url != null && StrUtil.isNotBlank(String.valueOf(url))) {
                    return String.valueOf(url).trim();
                }
            }
            return null;
        }
        return value == null ? null : StrUtil.trimToNull(String.valueOf(value));
    }

    private static void addVideoSlot(List<VideoSlot> target, Set<String> seen, String key, String value) {
        if (StrUtil.isNotBlank(value) && seen.add(value.trim())) target.add(new VideoSlot(key, value.trim()));
    }

    private static void addVideoAliases(List<VideoSlot> target, Set<String> seen,
                                        Map<String, Object> options, String canonicalKey,
                                        String... aliases) {
        boolean canonicalAssigned = false;
        for (String alias : aliases) {
            Object value = options.get(alias);
            if (value == null || StrUtil.isBlank(String.valueOf(value))) {
                continue;
            }
            String normalized = String.valueOf(value).trim();
            if (seen.add(normalized)) {
                target.add(new VideoSlot(canonicalAssigned ? null : canonicalKey, normalized));
                canonicalAssigned = true;
            }
        }
    }

    private static void addVideoUrls(List<VideoSlot> target, Set<String> seen, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addVideoSlot(target, seen, null, item == null ? null : String.valueOf(item)));
        } else if (value != null) {
            addVideoSlot(target, seen, null, String.valueOf(value));
        }
    }

    private enum ImageSlotType { FIRST, LAST, REFERENCE }
    private record ImageSlot(ImageSlotType type, String url) { }
    private record StructuredImages(String key, List<Object> values) { }
    private record VideoSlot(String key, String url) { }
}
