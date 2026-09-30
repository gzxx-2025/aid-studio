package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.AgnesConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ReferencePromptSanitizer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Agnes Video 2.5 多模态请求组装与任务落库前校验。 */
public final class AgnesVideo25RequestBuilder {

    private static final Set<String> STANDARD_SIZES = Set.of("720P", "960P", "2K");
    private static final Set<String> FLASH_SIZES = Set.of("720P");
    private static final Set<String> RATIOS = Set.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16");
    private static final Set<String> MODES = Set.of("text", "keyframe", "reference");
    private static final String[] LAST_FRAME_KEYS = {"lastFrameImageUrl", "endImageUrl", "end_image_url"};
    private static final String[] IMAGE_LIST_KEYS = {"referenceImages", "images"};
    private static final String[] VIDEO_SINGLE_KEYS = {
            "referenceVideoUrl", "featureVideoUrl", "baseVideoUrl", "inputVideoUrl", "videoUrl", "video_url"
    };
    private static final String[] VIDEO_LIST_KEYS = {"referenceVideos", "videos"};

    private AgnesVideo25RequestBuilder() {
    }

    public static boolean supportsModel(AiModelConfigVo modelConfig) {
        return supportsModelName(ModelCodeResolver.resolveUpstreamModel(modelConfig, null));
    }

    public static boolean supportsModelName(String modelName) {
        if (StrUtil.isBlank(modelName)) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase(Locale.ROOT);
        return AgnesConstants.VIDEO_MODEL_25.equals(normalized)
                || AgnesConstants.VIDEO_MODEL_25_FLASH.equals(normalized);
    }

    /** 原始请求必须先于通用素材截断校验，避免 Flash 超限输入被静默少发。 */
    public static void validateRawInputs(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        if (!supportsModel(modelConfig) || request == null) {
            return;
        }
        Inputs inputs = collect(request);
        validateN(request);
        if (isFlash(modelConfig)) {
            String mode = resolveMode(request, inputs);
            if ("reference".equals(mode)) {
                require(referenceImages(request, inputs).size() <= AgnesConstants.VIDEO_25_FLASH_MAX_IMAGES,
                        "参考图超限");
            }
            require(inputs.videos().isEmpty(), "模型不支持视频");
        }
    }

    /** 完整校验只读取本地参数，不下载素材、不请求上游。 */
    public static void validateFullRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        validateFullRequest(modelConfig, request, false);
    }

    /** 计划报价仅延后尚未生成的提示词必填检查，仍校验所有已知输入与规格。 */
    public static void validateFullRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
                                           boolean promptPending) {
        if (!supportsModel(modelConfig) || request == null) {
            return;
        }
        Inputs inputs = collect(request);
        require(promptPending || StrUtil.isNotBlank(request.getPrompt()), "提示词不能为空");
        validateN(request);

        int seconds = duration(request);
        require(seconds >= AgnesConstants.VIDEO_25_MIN_SECONDS
                && seconds <= AgnesConstants.VIDEO_25_MAX_SECONDS, "时长不支持");
        Set<String> supportedSizes = isFlash(modelConfig) ? FLASH_SIZES : STANDARD_SIZES;
        require(supportedSizes.contains(size(request)), "分辨率不支持");
        require(RATIOS.contains(ratio(request)), "比例不支持");

        String mode = resolveMode(request, inputs);
        require(MODES.contains(mode), "生成模式不支持");
        boolean hasFrames = StrUtil.isNotBlank(request.getImageUrl()) || StrUtil.isNotBlank(inputs.lastFrame());
        boolean hasReferences = !referenceImages(request, inputs).isEmpty()
                || !inputs.videos().isEmpty() || !inputs.audios().isEmpty();
        if ("text".equals(mode)) {
            require(!hasFrames && !hasReferences, "素材组合错误");
        } else if ("keyframe".equals(mode)) {
            require(hasFrames, "缺少关键帧");
            require(inputs.referenceImages().isEmpty()
                    && inputs.videos().isEmpty() && inputs.audios().isEmpty(), "素材组合错误");
        } else {
            require(hasReferences, "缺少参考素材");
            require(StrUtil.isBlank(inputs.lastFrame()), "素材组合错误");
        }
        if (isFlash(modelConfig)) {
            require(referenceImages(request, inputs).size() <= AgnesConstants.VIDEO_25_FLASH_MAX_IMAGES,
                    "参考图超限");
            require(inputs.videos().isEmpty(), "模型不支持视频");
        }
        validateVideoMetadata(request, inputs.videos().size());
    }

    public static Map<String, Object> buildSubmissionBody(String modelName,
                                                           AiModelConfigVo modelConfig,
                                                           MediaVideoGenerateRequest request) {
        validateFullRequest(modelConfig, request);
        Inputs inputs = collect(request);
        String mode = resolveMode(request, inputs);
        List<String> images = "reference".equals(mode)
                ? referenceImages(request, inputs) : new ArrayList<>();
        List<Map<String, Object>> videos = "reference".equals(mode)
                ? buildVideos(request, inputs.videos()) : new ArrayList<>();
        List<String> audios = "reference".equals(mode)
                ? inputs.audios().stream().map(ReferenceAudioInput::getSampleUrl).toList()
                : new ArrayList<>();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("prompt", ReferencePromptSanitizer.sanitizeForAgnes25(
                request.getPrompt(), images.size(), videos.size(), audios.size()));
        body.put("seconds", String.valueOf(duration(request)));
        body.put("mode", mode);
        body.put("size", size(request));
        body.put("aspect_ratio", ratio(request));
        body.put("n", 1);
        Integer seed = integerOption(request.getOptions(), "seed");
        if (seed != null) {
            body.put("seed", seed);
        }
        if ("keyframe".equals(mode)) {
            if (StrUtil.isNotBlank(request.getImageUrl())) {
                body.put("first_frame", request.getImageUrl());
            }
            if (StrUtil.isNotBlank(inputs.lastFrame())) {
                body.put("last_frame", inputs.lastFrame());
            }
        } else if ("reference".equals(mode)) {
            if (!images.isEmpty()) {
                body.put("images", images);
            }
            if (!audios.isEmpty()) {
                body.put("audios", audios);
            }
            if (!videos.isEmpty()) {
                body.put("videos", videos);
            }
        }
        return body;
    }

    public static String appendModelName(String queryUrl, String modelName) {
        if (StrUtil.isBlank(queryUrl) || !supportsModelName(modelName)
                || queryUrl.toLowerCase(Locale.ROOT).contains("model_name=")) {
            return queryUrl;
        }
        return queryUrl + (queryUrl.contains("?") ? "&" : "?") + "model_name=" + modelName;
    }

    private static List<Map<String, Object>> buildVideos(MediaVideoGenerateRequest request,
                                                          List<String> urls) {
        List<BigDecimal> starts = decimalList(option(request.getOptions(), "referenceVideoStartSeconds"));
        List<Boolean> requireAudios = booleanList(option(request.getOptions(), "referenceVideoRequireAudio"));
        List<Map<String, Object>> videos = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", urls.get(i));
            if (!starts.isEmpty()) {
                item.put("start_seconds", starts.get(i));
            }
            if (!requireAudios.isEmpty()) {
                item.put("require_audio", requireAudios.get(i));
            }
            videos.add(item);
        }
        return videos;
    }

    private static void validateVideoMetadata(MediaVideoGenerateRequest request, int videoCount) {
        List<BigDecimal> starts = decimalList(option(request.getOptions(), "referenceVideoStartSeconds"));
        if (!starts.isEmpty()) {
            require(starts.size() == videoCount, "视频参数不完整");
            starts.forEach(value -> require(value != null && value.signum() >= 0, "视频参数无效"));
        }
        List<Boolean> requireAudios = booleanList(option(request.getOptions(), "referenceVideoRequireAudio"));
        if (!requireAudios.isEmpty()) {
            require(requireAudios.size() == videoCount, "视频参数不完整");
        }
    }

    private static Inputs collect(MediaVideoGenerateRequest request) {
        Map<String, Object> options = request.getOptions();
        String lastFrame = firstText(options, LAST_FRAME_KEYS);
        LinkedHashSet<String> referenceImages = new LinkedHashSet<>();
        for (String key : IMAGE_LIST_KEYS) {
            addUrls(referenceImages, option(options, key));
        }
        referenceImages.remove(StrUtil.trim(request.getImageUrl()));
        referenceImages.remove(lastFrame);

        LinkedHashSet<String> videos = new LinkedHashSet<>();
        for (String key : VIDEO_SINGLE_KEYS) {
            addUrl(videos, option(options, key));
        }
        for (String key : VIDEO_LIST_KEYS) {
            addUrls(videos, option(options, key));
        }

        LinkedHashMap<String, ReferenceAudioInput> audioByUrl = new LinkedHashMap<>();
        if (request.getReferenceAudios() != null) {
            request.getReferenceAudios().stream()
                    .filter(audio -> audio != null && StrUtil.isNotBlank(audio.getSampleUrl()))
                    .forEach(audio -> audioByUrl.putIfAbsent(audio.getSampleUrl().trim(), audio));
        }
        List<ReferenceAudioInput> audios = new ArrayList<>(audioByUrl.values());
        return new Inputs(lastFrame, new ArrayList<>(referenceImages),
                new ArrayList<>(videos), audios);
    }

    private static List<String> referenceImages(MediaVideoGenerateRequest request, Inputs inputs) {
        LinkedHashSet<String> images = new LinkedHashSet<>();
        addUrl(images, request.getImageUrl());
        inputs.referenceImages().forEach(images::add);
        return new ArrayList<>(images);
    }

    private static String resolveMode(MediaVideoGenerateRequest request, Inputs inputs) {
        String configured = firstText(request.getOptions(), "mode");
        if ("keyframes".equalsIgnoreCase(configured)) {
            configured = "keyframe";
        }
        if (StrUtil.isNotBlank(configured)) {
            return configured.trim().toLowerCase(Locale.ROOT);
        }
        if (StrUtil.isNotBlank(inputs.lastFrame())) {
            return "keyframe";
        }
        if (!inputs.referenceImages().isEmpty() || !inputs.videos().isEmpty() || !inputs.audios().isEmpty()) {
            return "reference";
        }
        return StrUtil.isNotBlank(request.getImageUrl()) ? "keyframe" : "text";
    }

    private static int duration(MediaVideoGenerateRequest request) {
        return request.getDurationSeconds() == null ? AgnesConstants.VIDEO_25_DEFAULT_SECONDS
                : request.getDurationSeconds();
    }

    private static String size(MediaVideoGenerateRequest request) {
        String value = firstText(request.getOptions(), "resolution", "size");
        return StrUtil.isBlank(value) ? "720P" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String ratio(MediaVideoGenerateRequest request) {
        return StrUtil.isBlank(request.getAspectRatio()) ? "16:9" : request.getAspectRatio().trim();
    }

    private static boolean isFlash(AiModelConfigVo modelConfig) {
        String model = ModelCodeResolver.resolveUpstreamModel(modelConfig, null);
        return AgnesConstants.VIDEO_MODEL_25_FLASH.equalsIgnoreCase(StrUtil.trim(model));
    }

    private static void validateN(MediaVideoGenerateRequest request) {
        Integer n = integerOption(request.getOptions(), "n");
        require(n == null || n == 1, "生成数量仅支持1");
    }

    private static Integer integerOption(Map<String, Object> options, String key) {
        Object value = option(options, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new ServiceException("参数格式错误");
        }
    }

    private static List<BigDecimal> decimalList(Object value) {
        List<BigDecimal> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        require(value instanceof List<?>, "视频参数格式错");
        for (Object item : (List<?>) value) {
            try {
                result.add(new BigDecimal(String.valueOf(item)));
            } catch (NumberFormatException ex) {
                throw new ServiceException("视频参数无效");
            }
        }
        return result;
    }

    private static List<Boolean> booleanList(Object value) {
        List<Boolean> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        require(value instanceof List<?>, "视频参数格式错");
        for (Object item : (List<?>) value) {
            String text = String.valueOf(item);
            require("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text), "视频参数无效");
            result.add(Boolean.valueOf(text));
        }
        return result;
    }

    private static String firstText(Map<String, Object> options, String... keys) {
        if (options == null) {
            return null;
        }
        for (String key : keys) {
            Object value = options.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static Object option(Map<String, Object> options, String key) {
        return options == null ? null : options.get(key);
    }

    private static void addUrls(Set<String> target, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addUrl(target, item));
        } else {
            addUrl(target, value);
        }
    }

    private static void addUrl(Set<String> target, Object value) {
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            target.add(String.valueOf(value).trim());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ServiceException(message);
        }
    }

    private record Inputs(String lastFrame, List<String> referenceImages,
                          List<String> videos, List<ReferenceAudioInput> audios) {
    }
}
