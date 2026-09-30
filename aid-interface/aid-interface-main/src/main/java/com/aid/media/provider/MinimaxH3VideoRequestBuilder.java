package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.util.ModelCapabilityResolver;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 构造 MiniMax H3 V2 多模态 content 请求。结构化能力场景优先，兼容历史独立模型编码。 */
@Slf4j
public final class MinimaxH3VideoRequestBuilder {

    private static final String OPTION_REFERENCE_IMAGES = "referenceImages";
    private static final String OPTION_IMAGES = "images";
    private static final String OPTION_REFERENCE_VIDEOS = "referenceVideos";
    private static final String OPTION_REFERENCE_VIDEO_URL = "referenceVideoUrl";
    private static final String[] LAST_FRAME_KEYS = {"lastFrameImageUrl", "endImageUrl", "end_image_url"};

    private MinimaxH3VideoRequestBuilder() {
    }

    /** 必须在 Provider 构造上游请求体前调用，确保悬空素材引用被统一清洗。 */
    public static void sanitizePrompt(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        PromptLimits limits = resolvePromptLimits(config, request);
        ReferencePromptSanitizer.sanitizeInPlace(request, limits.imageCount(), limits.audioCount());
    }

    public static Map<String, Object> buildSubmissionBody(AiModelConfigVo config,
                                                           MediaVideoGenerateRequest request) {
        return buildSubmissionBody(config, request, request == null ? null : request.getPrompt(), false);
    }

    /** 使用清洗后的上游 prompt 完整预校验请求体，但不改写业务请求与审计快照。 */
    public static Map<String, Object> buildSubmissionBodyForValidation(AiModelConfigVo config,
                                                                        MediaVideoGenerateRequest request) {
        return buildSubmissionBodyForValidation(config, request, false);
    }

    /** 计划报价延后未知提示词的必填校验，保持场景与媒体参数校验。 */
    public static Map<String, Object> buildSubmissionBodyForValidation(AiModelConfigVo config,
                                                                        MediaVideoGenerateRequest request,
                                                                        boolean promptPending) {
        if (config == null || request == null) {
            return buildSubmissionBody(config, request);
        }
        return buildSubmissionBody(config, request, sanitizedPrompt(config, request), promptPending);
    }

    private static Map<String, Object> buildSubmissionBody(AiModelConfigVo config,
                                                            MediaVideoGenerateRequest request,
                                                            String submissionPrompt, boolean promptPending) {
        if (config == null || request == null) {
            throw rejected("missing model config or video request", "视频参数不能为空");
        }
        if (Boolean.TRUE.equals(request.getAudio()) || Boolean.TRUE.equals(option(request, "generate_audio"))
            || request.getBgm() != null
            || StrUtil.isNotBlank(request.getAudioType()) || StrUtil.isNotBlank(request.getVoiceId())) {
            throw rejected("unsupported video audio output control", "该模型不支持音画生成控制参数");
        }
        Scene scene = requireScene(config);
        String prompt = StrUtil.trim(submissionPrompt);
        if (!promptPending && StrUtil.isBlank(prompt)) {
            throw rejected("blank prompt", "视频提示词不能为空");
        }
        if (StrUtil.length(prompt) > 7000) {
            throw rejected("prompt exceeds 7000 characters, length=" + prompt.length(), "视频提示词过长");
        }

        List<Map<String, Object>> content = new ArrayList<>();
        if (StrUtil.isNotBlank(prompt)) {
            content.add(textItem(prompt));
        }
        switch (scene) {
            case TEXT -> validateTextScene(request);
            case FIRST_FRAME -> addFirstFrame(config, request, content);
            case LAST_FRAME -> addLastFrame(config, request, content);
            case FIRST_LAST_FRAME -> addFirstLastFrames(config, request, content);
            case REFERENCE -> addReferences(config, request, content);
        }

        String realModel = realModel(config);
        String resolution = resolution(request, realModel);
        int duration = duration(request, realModel);
        String ratio = ratio(request, scene);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", realModel);
        body.put("content", content);
        body.put("resolution", resolution);
        body.put("duration", duration);
        body.put("ratio", ratio);
        String callbackUrl = MinimaxH3CallbackSupport.resolveCallbackUrlForSubmission(config);
        if (StrUtil.isNotBlank(callbackUrl)) {
            body.put("callback_url", callbackUrl);
        }
        Object watermark = option(request, "aigc_watermark", "aigcWatermark");
        if (watermark != null) {
            if (!(watermark instanceof Boolean value)) {
                throw rejected("watermark must be boolean", "水印参数无效");
            }
            body.put("aigc_watermark", value);
        }
        Object expansion = option(request, "promptExpansionMode", "prompt_expansion_mode");
        if (expansion != null) {
            if (!MinimaxH3Constants.REAL_MODEL_CODE_MAX.equals(realModel)
                || !(expansion instanceof String mode)
                || !List.of("disabled", "balanced", "quality").contains(mode)) {
                throw rejected("unsupported prompt expansion mode", "扩写模式不支持");
            }
            body.put("extra", Map.of("prompt_expansion_mode", expansion));
        }
        return body;
    }

    private static String realModel(AiModelConfigVo config) {
        String value = StrUtil.trim(config.getRealModelCode());
        if (StrUtil.isBlank(value) && MinimaxH3Constants.MODEL_CODES.contains(config.getModelCode())
            && !MinimaxH3Constants.MODEL_MAX_OFFICIAL.equals(config.getModelCode())) {
            value = MinimaxH3Constants.REAL_MODEL_CODE;
        }
        if (!MinimaxH3Constants.REAL_MODEL_CODE.equals(value)
            && !MinimaxH3Constants.REAL_MODEL_CODE_MAX.equals(value)) {
            throw rejected("unsupported upstream model", "上游模型不支持");
        }
        return value;
    }

    private static String sanitizedPrompt(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        PromptLimits limits = resolvePromptLimits(config, request);
        return ReferencePromptSanitizer.sanitize(request.getPrompt(), limits.imageCount(), limits.audioCount());
    }

    private static PromptLimits resolvePromptLimits(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        Scene scene = requireScene(config);
        int imageCount = countDispatchedImages(config, request, scene);
        int audioCount = scene == Scene.REFERENCE ? referenceAudios(config, request).size() : 0;
        return new PromptLimits(imageCount, audioCount);
    }

    private static void validateTextScene(MediaVideoGenerateRequest request) {
        if (StrUtil.isNotBlank(request.getImageUrl()) || hasReferenceInputs(request)
            || StrUtil.isNotBlank(optionText(request, LAST_FRAME_KEYS))) {
            throw rejected("text scene contains media input", "文生视频不支持素材");
        }
    }

    private static void addFirstFrame(AiModelConfigVo config, MediaVideoGenerateRequest request,
                                      List<Map<String, Object>> content) {
        rejectReferences(request);
        String first = StrUtil.trim(request.getImageUrl());
        if (StrUtil.isBlank(first)) {
            throw rejected("first-frame scene is missing first image", "请提供首帧图片");
        }
        if (StrUtil.isNotBlank(optionText(request, LAST_FRAME_KEYS))) {
            throw rejected("first-frame scene contains last-frame image", "首帧场景不支持尾帧");
        }
        ensureImagesAllowed(config, 1);
        content.add(mediaItem("image_url", first, "first_frame"));
    }

    private static void addLastFrame(AiModelConfigVo config, MediaVideoGenerateRequest request,
                                     List<Map<String, Object>> content) {
        rejectReferences(request);
        String last = optionText(request, LAST_FRAME_KEYS);
        if (StrUtil.isNotBlank(last) && StrUtil.isNotBlank(request.getImageUrl())
            && !last.equals(request.getImageUrl().trim())) {
            throw rejected("last-frame scene contains two distinct images", "尾帧场景仅限一图");
        }
        if (StrUtil.isBlank(last)) {
            last = StrUtil.trim(request.getImageUrl());
        }
        if (StrUtil.isBlank(last)) {
            throw rejected("last-frame scene is missing last image", "请提供尾帧图片");
        }
        ensureImagesAllowed(config, 1);
        content.add(mediaItem("image_url", last, "last_frame"));
    }

    private static void addFirstLastFrames(AiModelConfigVo config, MediaVideoGenerateRequest request,
                                           List<Map<String, Object>> content) {
        rejectReferences(request);
        String first = StrUtil.trim(request.getImageUrl());
        String last = optionText(request, LAST_FRAME_KEYS);
        if (StrUtil.isBlank(first) || StrUtil.isBlank(last)) {
            throw rejected("first-last-frame scene is missing first or last image", "请提供首尾帧图片");
        }
        if (first.equals(last)) {
            throw rejected("first and last images are identical", "首尾帧图片不能相同");
        }
        ensureImagesAllowed(config, 2);
        content.add(mediaItem("image_url", first, "first_frame"));
        content.add(mediaItem("image_url", last, "last_frame"));
    }

    private static void addReferences(AiModelConfigVo config, MediaVideoGenerateRequest request,
                                      List<Map<String, Object>> content) {
        if (StrUtil.isNotBlank(optionText(request, LAST_FRAME_KEYS))) {
            throw rejected("reference scene contains frame input", "参考场景不支持首尾帧");
        }
        List<String> images = referenceImages(config, request);
        List<String> videos = referenceVideos(request);
        List<ReferenceAudioInput> audios = referenceAudios(config, request);
        if (images.isEmpty() && videos.isEmpty() && audios.isEmpty()) {
            throw rejected("reference scene contains no usable media", "请提供参考素材");
        }
        for (String url : images) {
            content.add(mediaItem("image_url", url, "reference_image"));
        }
        for (String url : videos) {
            content.add(mediaItem("video_url", url, "reference_video"));
        }
        for (ReferenceAudioInput audio : audios) {
            if (audio != null && StrUtil.isNotBlank(audio.getSampleUrl())) {
                content.add(mediaItem("audio_url", audio.getSampleUrl().trim(), "reference_audio"));
            }
        }
    }

    private static void rejectReferences(MediaVideoGenerateRequest request) {
        if (hasReferenceInputs(request)) {
            throw rejected("frame scene contains reference media", "帧场景不支持参考素材");
        }
    }

    private static boolean hasReferenceInputs(MediaVideoGenerateRequest request) {
        return !mediaUrls(option(request, OPTION_REFERENCE_IMAGES)).isEmpty()
            || !mediaUrls(option(request, OPTION_IMAGES)).isEmpty()
            || !referenceVideos(request).isEmpty()
            || (request.getReferenceAudios() != null && !request.getReferenceAudios().isEmpty());
    }

    private static List<String> referenceImages(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        List<String> images = new ArrayList<>();
        if (StrUtil.isNotBlank(request.getImageUrl())) {
            images.add(request.getImageUrl().trim());
        }
        images.addAll(mediaUrls(option(request, OPTION_REFERENCE_IMAGES)));
        images.addAll(mediaUrls(option(request, OPTION_IMAGES)));
        images = deduplicate(images);
        int maximum = Math.min(9, ReferenceImageLimiter.resolveMax(config, 9));
        if (images.size() > maximum) {
            throw rejected("reference image count exceeds " + maximum, "参考图片数量超限");
        }
        return images;
    }

    private static List<String> referenceVideos(MediaVideoGenerateRequest request) {
        List<String> videos = new ArrayList<>(mediaUrls(option(request, OPTION_REFERENCE_VIDEOS)));
        for (String key : List.of(OPTION_REFERENCE_VIDEO_URL, "featureVideoUrl", "baseVideoUrl",
                "inputVideoUrl", "videoUrl", "video_url", "videos")) {
            videos.addAll(mediaUrls(option(request, key)));
        }
        videos = deduplicate(videos);
        if (videos.size() > 3) {
            throw rejected("reference video count exceeds 3, count=" + videos.size(), "参考视频最多三个");
        }
        return videos;
    }

    private static List<ReferenceAudioInput> referenceAudios(AiModelConfigVo config,
                                                              MediaVideoGenerateRequest request) {
        List<ReferenceAudioInput> audios = request.getReferenceAudios() == null
            ? List.of() : request.getReferenceAudios();
        int configuredMaximum = ReferenceAudioLimiter.readCapability(config).getMaxCount();
        int maximum = configuredMaximum == -1 ? 3 : Math.min(3, Math.max(0, configuredMaximum));
        List<ReferenceAudioInput> valid = new ArrayList<>();
        for (ReferenceAudioInput audio : audios) {
            if (audio == null) {
                throw rejected("reference audio is null", "参考音频无效");
            }
            if (StrUtil.isBlank(audio.getSampleUrl())) {
                if (audio.isExplicit()) {
                    throw rejected("explicit reference audio has no URL", "参考音频地址不能为空");
                }
                // 提示词自动推导的音色由统一音频校验器清理；不能阻断前置预估。
                continue;
            }
            if (valid.stream().noneMatch(item -> item.getSampleUrl().trim().equals(audio.getSampleUrl().trim()))) {
                valid.add(audio);
            }
        }
        if (valid.size() > maximum) {
            throw rejected("reference audio count exceeds " + maximum, "参考音频数量超限");
        }
        return valid;
    }

    private static int countDispatchedImages(AiModelConfigVo config, MediaVideoGenerateRequest request, Scene scene) {
        return switch (scene) {
            case TEXT -> 0;
            case FIRST_FRAME, LAST_FRAME -> 1;
            case FIRST_LAST_FRAME -> 2;
            case REFERENCE -> referenceImages(config, request).size();
        };
    }

    private static void ensureImagesAllowed(AiModelConfigVo config, int required) {
        int max = ReferenceImageLimiter.resolveMax(config, required);
        if (max < required) {
            throw rejected("reference image capability is below scene requirement, required=" + required
                + ", configuredMax=" + max, "参考图配置不匹配");
        }
    }

    private static String resolution(MediaVideoGenerateRequest request, String realModel) {
        String value = optionText(request, "resolution", "size");
        value = StrUtil.isBlank(value) ? "768P" : value.trim().toUpperCase(Locale.ROOT);
        boolean supported = MinimaxH3Constants.REAL_MODEL_CODE_MAX.equals(realModel)
            ? List.of("480P", "768P").contains(value) : MinimaxH3Constants.RESOLUTIONS.contains(value);
        if (!supported) {
            throw rejected("unsupported resolution=" + value, "分辨率不支持");
        }
        return value;
    }

    private static int duration(MediaVideoGenerateRequest request, String realModel) {
        Integer configured = request.getDurationSeconds();
        if (configured == null) {
            Object raw = option(request, "duration", "durationSeconds");
            if (raw != null) {
                try {
                    configured = Integer.valueOf(String.valueOf(raw));
                } catch (NumberFormatException ex) {
                    throw rejected("duration is not an integer", "视频时长须为整数");
                }
            }
        }
        int value = configured == null ? 5 : configured;
        int minimum = MinimaxH3Constants.REAL_MODEL_CODE_MAX.equals(realModel) ? 5 : 4;
        if (value < minimum || value > 15) {
            throw rejected("duration is outside integer range [4,15], value=" + value, "视频时长范围错误");
        }
        return value;
    }

    private static String ratio(MediaVideoGenerateRequest request, Scene scene) {
        String value = StrUtil.trim(request.getAspectRatio());
        if (scene == Scene.FIRST_FRAME || scene == Scene.LAST_FRAME || scene == Scene.FIRST_LAST_FRAME) {
            if (StrUtil.isNotBlank(value) && !"adaptive".equals(value)) {
                throw rejected("frame scene ignores explicit ratio=" + value, "首尾帧场景仅支持自适应比例");
            }
            return "adaptive";
        }
        if (StrUtil.isBlank(value)) {
            value = scene == Scene.TEXT ? "16:9" : "adaptive";
        }
        if (!MinimaxH3Constants.RATIOS.contains(value)
            || (scene == Scene.TEXT && "adaptive".equals(value))) {
            throw rejected("unsupported ratio=" + value + ", scene=" + scene, "视频比例不支持");
        }
        return value;
    }

    private static Map<String, Object> textItem(String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "text");
        item.put("text", text);
        return item;
    }

    private static Map<String, Object> mediaItem(String type, String url, String role) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("url", url);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put(type, target);
        item.put("role", role);
        return item;
    }

    private static List<String> mediaUrls(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                addMediaUrl(result, value);
            }
        } else {
            addMediaUrl(result, raw);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void addMediaUrl(List<String> result, Object value) {
        if (value instanceof String text) {
            if (StrUtil.isBlank(text)) {
                throw rejected("blank reference media URL", "参考素材地址不能为空");
            }
            result.add(text.trim());
            return;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw rejected("invalid reference media item", "参考素材格式无效");
        }
        Object url = map.get("url");
        if (url == null) {
            for (String key : List.of("image_url", "video_url", "audio_url")) {
                Object nested = map.get(key);
                if (nested instanceof Map<?, ?> nestedMap && nestedMap.get("url") != null) {
                    url = nestedMap.get("url");
                    break;
                }
            }
        }
        if (!(url instanceof String text) || StrUtil.isBlank(text)) {
            throw rejected("reference media item has no URL", "参考素材地址不能为空");
        }
        result.add(text.trim());
    }

    private static List<String> deduplicate(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (StrUtil.isNotBlank(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static Object option(MediaVideoGenerateRequest request, String... keys) {
        Map<String, Object> options = request.getOptions();
        if (options == null) {
            return null;
        }
        for (String key : keys) {
            if (options.containsKey(key) && options.get(key) != null) {
                return options.get(key);
            }
        }
        return null;
    }

    private static String optionText(MediaVideoGenerateRequest request, String... keys) {
        Object value = option(request, keys);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Scene requireScene(AiModelConfigVo config) {
        String configured = ModelCapabilityResolver.readText(
            ModelCapabilityResolver.parseCapability(config == null ? null : config.getCapabilityJson()),
            "videoScenario");
        if (StrUtil.isNotBlank(configured)) {
            return switch (configured.trim().toLowerCase(Locale.ROOT)) {
                case "text_to_video", "text" -> Scene.TEXT;
                case "first_frame", "image_to_video" -> Scene.FIRST_FRAME;
                case "last_frame", "last_frame_to_video" -> Scene.LAST_FRAME;
                case "first_last_frame", "start_end_to_video" -> Scene.FIRST_LAST_FRAME;
                case "multimodal_reference", "reference_to_video", "reference" -> Scene.REFERENCE;
                default -> throw rejected("unknown configured videoScenario=" + configured,
                    "模型场景配置无效");
            };
        }
        String code = config == null ? null : StrUtil.trim(config.getModelCode());
        return switch (StrUtil.blankToDefault(code, "")) {
            case MinimaxH3Constants.MODEL_T2V -> Scene.TEXT;
            case MinimaxH3Constants.MODEL_I2V_FIRST -> Scene.FIRST_FRAME;
            case MinimaxH3Constants.MODEL_I2V_LAST -> Scene.LAST_FRAME;
            case MinimaxH3Constants.MODEL_I2V_FIRST_LAST -> Scene.FIRST_LAST_FRAME;
            case MinimaxH3Constants.MODEL_REFERENCE -> Scene.REFERENCE;
            default -> throw rejected("unknown platform model code=" + code, "模型场景配置无效");
        };
    }

    private static ServiceException rejected(String reason, String clientMessage) {
        log.warn("MiniMax H3 request rejected: {}", reason);
        return new ServiceException(clientMessage);
    }

    private record PromptLimits(int imageCount, int audioCount) {
    }

    private enum Scene {
        TEXT, FIRST_FRAME, LAST_FRAME, FIRST_LAST_FRAME, REFERENCE
    }
}
