package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.DashscopeConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ReferencePromptSanitizer;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Wan3.0 多模态请求组装与任务落库前校验。 */
public final class Wan3VideoRequestBuilder {

    private static final Set<String> RESOLUTIONS = Set.of("480P", "720P", "1080P");
    private static final Set<String> RATIOS = Set.of("ADAPTIVE", "16:9", "4:3", "1:1", "3:4", "9:16");
    private static final Set<String> VIDEO_FORMATS = Set.of("mp4", "mov");
    private static final Set<String> AUDIO_FORMATS = Set.of("wav", "mp3");
    private static final String[] LAST_FRAME_KEYS = {"lastFrameImageUrl", "endImageUrl", "end_image_url"};
    private static final String[] IMAGE_LIST_KEYS = {
            "referenceImages", "images", "keyImages", "key_images", "image_settings", "imageSettings"
    };
    private static final String[] VIDEO_SINGLE_KEYS = {
            "referenceVideoUrl", "featureVideoUrl", "baseVideoUrl", "inputVideoUrl", "videoUrl", "video_url"
    };
    private static final String[] VIDEO_LIST_KEYS = {"referenceVideos", "videos"};

    private Wan3VideoRequestBuilder() {
    }

    public static boolean supportsModel(AiModelConfigVo modelConfig) {
        // TokenDance 的同名模型由独立协议门面校验，不能提前套用原厂请求及整数时长契约。
        if (modelConfig != null && "tokendance".equalsIgnoreCase(modelConfig.getProviderCode())) {
            return false;
        }
        String model = ModelCodeResolver.resolveUpstreamModel(modelConfig, null);
        return supportsModelName(model);
    }

    public static boolean supportsModelName(String modelName) {
        if (StrUtil.isBlank(modelName)) {
            return false;
        }
        String normalized = modelName.trim().toLowerCase(Locale.ROOT);
        return DashscopeConstants.MODEL_WAN3.equals(normalized)
                || DashscopeConstants.MODEL_WAN3_PRIME.equals(normalized);
    }

    /** 原始请求计数必须先于通用截断执行，超限直接拒绝而不是静默少发。 */
    public static void validateRawInputs(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        if (!supportsModel(modelConfig) || request == null) {
            return;
        }
        MediaInputs inputs = collect(request);
        require(inputs.imageCount() <= DashscopeConstants.WAN3_MAX_REFERENCE_IMAGES, "参考图超限");
        require(inputs.videos().size() <= DashscopeConstants.WAN3_MAX_REFERENCE_VIDEOS, "参考视频超限");
        require(inputs.audios().size() <= DashscopeConstants.WAN3_MAX_REFERENCE_AUDIOS, "参考音频超限");
        validateAudioMetadata(request, inputs.audios());
    }

    /** 完整校验只读取本地参数，不下载素材、不请求上游。 */
    public static void validateFullRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        validateFullRequest(modelConfig, request, false);
    }

    /** 计划报价允许提示词尚未生成，素材组合、时长与规格继续完整校验。 */
    public static void validateFullRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request,
                                           boolean promptPending) {
        if (!supportsModel(modelConfig) || request == null) {
            return;
        }
        MediaInputs inputs = collect(request);
        require(inputs.imageCount() <= DashscopeConstants.WAN3_MAX_REFERENCE_IMAGES, "参考图超限");
        require(inputs.videos().size() <= DashscopeConstants.WAN3_MAX_REFERENCE_VIDEOS, "参考视频超限");
        require(inputs.audios().size() <= DashscopeConstants.WAN3_MAX_REFERENCE_AUDIOS, "参考音频超限");
        validateAudioMetadata(request, inputs.audios());

        boolean hasLastFrame = StrUtil.isNotBlank(inputs.lastFrame());
        boolean hasReferenceMedia = !inputs.referenceImages().isEmpty()
                || !inputs.videos().isEmpty() || !inputs.audios().isEmpty();
        if (hasLastFrame) {
            require(StrUtil.isNotBlank(request.getImageUrl()), "缺少首帧");
            require(!hasReferenceMedia, "素材组合错误");
        }
        require(promptPending || StrUtil.isNotBlank(request.getPrompt()) || inputs.hasAnyMedia(), "缺少生成内容");

        String resolution = resolution(request);
        require(RESOLUTIONS.contains(resolution), "分辨率不支持");
        String ratio = ratio(request);
        require(RATIOS.contains(ratio.toUpperCase(Locale.ROOT)), "比例不支持");

        int requestedDuration = request.getDurationSeconds() == null ? 5 : request.getDurationSeconds();
        require(requestedDuration == -1
                || requestedDuration >= DashscopeConstants.WAN3_MIN_OUTPUT_SECONDS
                && requestedDuration <= DashscopeConstants.WAN3_MAX_OUTPUT_SECONDS, "时长不支持");

        int inputVideoSeconds = validateInputVideoDurations(request, inputs.videos());
        if (!inputs.videos().isEmpty() && requestedDuration > 0) {
            require(inputVideoSeconds + requestedDuration <= DashscopeConstants.WAN3_MAX_OUTPUT_SECONDS,
                    "总时长超限");
        }
        int safeOutputSeconds = requestedDuration == -1
                ? Math.max(DashscopeConstants.WAN3_MIN_OUTPUT_SECONDS,
                    DashscopeConstants.WAN3_MAX_OUTPUT_SECONDS - inputVideoSeconds)
                : requestedDuration;
        Map<String, Object> options = request.getOptions() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getOptions());
        options.put("billingDurationSeconds", safeOutputSeconds);
        if (inputVideoSeconds > 0) {
            options.put("inputVideoSeconds", inputVideoSeconds);
        }
        request.setOptions(options);
    }

    public static Map<String, Object> buildSubmissionBody(String modelName,
                                                           AiModelConfigVo modelConfig,
                                                           MediaVideoGenerateRequest request) {
        validateFullRequest(modelConfig, request);
        MediaInputs inputs = collect(request);
        List<Map<String, Object>> media = new ArrayList<>();
        boolean hasLastFrame = StrUtil.isNotBlank(inputs.lastFrame());
        boolean referenceMode = !hasLastFrame && (!inputs.referenceImages().isEmpty()
                || !inputs.videos().isEmpty() || !inputs.audios().isEmpty());
        int referenceImageCount = 0;
        if (hasLastFrame) {
            media.add(media("first_frame", request.getImageUrl()));
            media.add(media("last_frame", inputs.lastFrame()));
        } else if (referenceMode) {
            if (StrUtil.isNotBlank(request.getImageUrl())) {
                media.add(media("reference_image", request.getImageUrl()));
                referenceImageCount++;
            }
            for (String url : inputs.referenceImages()) {
                media.add(media("reference_image", url));
                referenceImageCount++;
            }
            inputs.videos().forEach(url -> media.add(media("reference_video", url)));
            inputs.audios().forEach(audio -> media.add(media("reference_audio", audio.getSampleUrl())));
        } else if (StrUtil.isNotBlank(request.getImageUrl())) {
            media.add(media("first_frame", request.getImageUrl()));
        }

        Map<String, Object> input = new LinkedHashMap<>();
        String prompt = ReferencePromptSanitizer.sanitizeForWan3(request.getPrompt(),
                referenceImageCount, inputs.videos().size(), inputs.audios().size());
        if (StrUtil.isNotBlank(prompt)) {
            input.put("prompt", prompt);
        }
        if (!media.isEmpty()) {
            input.put("media", media);
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("resolution", resolution(request));
        parameters.put("ratio", ratio(request));
        parameters.put("duration", request.getDurationSeconds() == null ? 5 : request.getDurationSeconds());
        parameters.put("audio", request.getAudio() == null || Boolean.TRUE.equals(request.getAudio()));
        copyBooleanOption(request, parameters, "prompt_extend");
        copyBooleanOption(request, parameters, "watermark");
        copyIntegerOption(request, parameters, "seed");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", input);
        body.put("parameters", parameters);
        return body;
    }

    private static int validateInputVideoDurations(MediaVideoGenerateRequest request, List<String> videos) {
        if (videos.isEmpty()) {
            return 0;
        }
        Map<String, Object> options = request.getOptions();
        List<Integer> durations = integerList(firstOption(options,
                "referenceVideoDurations", "videoDurations", "inputVideoDurations"));
        int declaredTotal = positiveInteger(firstOption(options,
                "inputVideoSeconds", "referenceVideoSeconds", "videoSeconds"));
        if (!durations.isEmpty()) {
            require(durations.size() == videos.size(), "视频时长不完整");
            int sum = 0;
            for (Integer seconds : durations) {
                require(seconds != null
                        && seconds >= DashscopeConstants.WAN3_MIN_MEDIA_SECONDS
                        && seconds <= DashscopeConstants.WAN3_MAX_INPUT_VIDEO_SECONDS, "视频时长不符");
                sum += seconds;
            }
            require(declaredTotal <= 0 || declaredTotal == sum, "视频时长不一致");
            declaredTotal = sum;
        }
        require(declaredTotal > 0, "缺少视频时长");
        require(declaredTotal >= videos.size()
                && declaredTotal <= DashscopeConstants.WAN3_MAX_INPUT_VIDEO_SECONDS, "输入视频超时");
        return declaredTotal;
    }

    private static void validateAudioMetadata(MediaVideoGenerateRequest request,
                                              List<ReferenceAudioInput> audios) {
        if (audios.isEmpty()) {
            return;
        }
        require(!Boolean.FALSE.equals(request.getAudio()), "请开启视频声音");
        long totalDurationMs = 0L;
        for (ReferenceAudioInput audio : audios) {
            String format = StrUtil.blankToDefault(audio.getFormat(), "").trim().toLowerCase(Locale.ROOT);
            require(AUDIO_FORMATS.contains(format), "参考音频格式不符");
            Integer durationMs = audio.getDurationMs();
            require(durationMs != null
                    && durationMs >= DashscopeConstants.WAN3_MIN_MEDIA_SECONDS * 1000
                    && durationMs <= DashscopeConstants.WAN3_MAX_INPUT_AUDIO_SECONDS * 1000,
                    "参考音频时长不符");
            totalDurationMs += durationMs;
        }
        require(totalDurationMs <= DashscopeConstants.WAN3_MAX_INPUT_AUDIO_SECONDS * 1000L,
                "参考音频时长不符");
    }

    private static MediaInputs collect(MediaVideoGenerateRequest request) {
        Map<String, Object> options = request.getOptions();
        String lastFrame = firstText(options, LAST_FRAME_KEYS);
        LinkedHashSet<String> referenceImages = new LinkedHashSet<>();
        for (String key : IMAGE_LIST_KEYS) {
            addUrls(referenceImages, options == null ? null : options.get(key));
        }
        LinkedHashSet<String> videos = new LinkedHashSet<>();
        for (String key : VIDEO_SINGLE_KEYS) {
            addUrl(videos, options == null ? null : options.get(key));
        }
        for (String key : VIDEO_LIST_KEYS) {
            addUrls(videos, options == null ? null : options.get(key));
        }
        validateVideoFormats(options, new ArrayList<>(videos));
        LinkedHashMap<String, ReferenceAudioInput> audios = new LinkedHashMap<>();
        if (request.getReferenceAudios() != null) {
            for (ReferenceAudioInput audio : request.getReferenceAudios()) {
                if (audio != null && StrUtil.isNotBlank(audio.getSampleUrl())) {
                    audios.putIfAbsent(audio.getSampleUrl().trim(), audio);
                }
            }
        }
        int semanticImages = StrUtil.isNotBlank(request.getImageUrl()) ? 1 : 0;
        if (StrUtil.isNotBlank(lastFrame)) {
            semanticImages++;
        }
        referenceImages.remove(StrUtil.trim(request.getImageUrl()));
        referenceImages.remove(lastFrame);
        return new MediaInputs(lastFrame, new ArrayList<>(referenceImages),
                new ArrayList<>(videos), new ArrayList<>(audios.values()),
                semanticImages + referenceImages.size());
    }

    private static void validateVideoFormats(Map<String, Object> options, List<String> videos) {
        List<String> declared = stringList(firstOption(options, "referenceVideoFormats", "videoFormats"));
        if (!declared.isEmpty()) {
            require(declared.size() == videos.size(), "视频格式不完整");
            declared.forEach(format -> require(VIDEO_FORMATS.contains(format.toLowerCase(Locale.ROOT)),
                    "视频格式不支持"));
            return;
        }
        for (String url : videos) {
            String extension = extension(url);
            if (StrUtil.isNotBlank(extension)) {
                require(VIDEO_FORMATS.contains(extension), "视频格式不支持");
            }
        }
    }

    private static String extension(String rawUrl) {
        try {
            String path = URI.create(rawUrl).getPath();
            int slash = path == null ? -1 : path.lastIndexOf('/');
            int dot = path == null ? -1 : path.lastIndexOf('.');
            if (dot <= slash || dot == path.length() - 1) {
                return null;
            }
            return path.substring(dot + 1).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolution(MediaVideoGenerateRequest request) {
        String value = textOption(request.getOptions(), "resolution", "size");
        return StrUtil.isBlank(value) ? "720P" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String ratio(MediaVideoGenerateRequest request) {
        return StrUtil.isBlank(request.getAspectRatio()) ? "adaptive" : request.getAspectRatio().trim();
    }

    private static Map<String, Object> media(String type, String url) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("url", url);
        return item;
    }

    private static void copyBooleanOption(MediaVideoGenerateRequest request,
                                          Map<String, Object> target, String key) {
        Object value = optionIncludingParameters(request.getOptions(), key);
        if (value instanceof Boolean bool) {
            target.put(key, bool);
        } else if (value != null && ("true".equalsIgnoreCase(String.valueOf(value))
                || "false".equalsIgnoreCase(String.valueOf(value)))) {
            target.put(key, Boolean.parseBoolean(String.valueOf(value)));
        }
    }

    private static void copyIntegerOption(MediaVideoGenerateRequest request,
                                          Map<String, Object> target, String key) {
        Object value = optionIncludingParameters(request.getOptions(), key);
        if (value != null) {
            try {
                target.put(key, Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                throw new ServiceException("随机种子无效");
            }
        }
    }

    private static Object optionIncludingParameters(Map<String, Object> options, String key) {
        if (options == null) {
            return null;
        }
        if (options.containsKey(key)) {
            return options.get(key);
        }
        Object parameters = options.get("parameters");
        return parameters instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static Object firstOption(Map<String, Object> options, String... keys) {
        if (options == null) return null;
        for (String key : keys) {
            if (options.get(key) != null) return options.get(key);
        }
        return null;
    }

    private static String firstText(Map<String, Object> options, String... keys) {
        Object value = firstOption(options, keys);
        return value == null ? null : StrUtil.trimToNull(String.valueOf(value));
    }

    private static String textOption(Map<String, Object> options, String... keys) {
        return firstText(options, keys);
    }

    private static List<Integer> integerList(Object value) {
        List<Integer> result = new ArrayList<>();
        if (!(value instanceof List<?> list)) return result;
        for (Object item : list) {
            try {
                result.add(Integer.parseInt(String.valueOf(item)));
            } catch (Exception ignored) {
                throw new ServiceException("视频时长不符");
            }
        }
        return result;
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (!(value instanceof List<?> list)) return result;
        for (Object item : list) {
            if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                result.add(String.valueOf(item).trim());
            }
        }
        return result;
    }

    private static int positiveInteger(Object value) {
        if (value == null) return 0;
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException ignored) {
            throw new ServiceException("视频时长不符");
        }
    }

    private static void addUrls(Set<String> target, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addUrl(target, item));
        } else {
            addUrl(target, value);
        }
    }

    private static void addUrl(Set<String> target, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : new String[]{"key_image", "keyImage", "image_url", "imageUrl", "url"}) {
                if (map.containsKey(key)) {
                    addUrl(target, map.get(key));
                    return;
                }
            }
            return;
        }
        if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
            target.add(String.valueOf(value).trim());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ServiceException(message);
        }
    }

    private record MediaInputs(String lastFrame, List<String> referenceImages,
                               List<String> videos, List<ReferenceAudioInput> audios,
                               int imageCount) {
        boolean hasAnyMedia() {
            return imageCount > 0 || !videos.isEmpty() || !audios.isEmpty();
        }
    }
}
