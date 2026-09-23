package com.aid.billing.util;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.util.ImageBillingCapabilityHelper;
import com.aid.media.util.MediaGenerationSceneResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计费参数提取工具：从不同请求类型中提取统一的计费参数。
 * 提供静态方法，不持有状态，不依赖Spring容器。
 */
@Slf4j
public final class BillingInputExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * estimatedOutputChars/max_tokens/expectedImageCount/n/videoDuration 等参数统一做硬上限保护，
     * 防止前端构造巨大参数导致结算层金额溢出或冲垮上游配额。
     */
    /** 文本预估输出字符硬上限（= 2M 字符，对应约 1.6M token）。 */
    private static final int TEXT_ESTIMATED_OUTPUT_CHARS_HARD_CAP = 2_000_000;
    /** 文本预估输出 token 硬上限。 */
    private static final int TEXT_ESTIMATED_OUTPUT_TOKENS_HARD_CAP = 1_600_000;
    /** 图片 expectedImageCount 硬上限（在 capability 上限之上再加一层绝对兜底）。 */
    private static final int IMAGE_EXPECTED_COUNT_HARD_CAP = 16;
    /** 图层拆分的底图与透明图层共用一个请求，仍由所选能力的 maxOutputCount 再限制。 */
    private static final int IMAGE_LAYER_EXPECTED_COUNT_HARD_CAP = 64;
    /** 视频 durationSeconds 硬上限：300 秒足够覆盖任何合法视频生成。 */
    private static final int VIDEO_DURATION_HARD_CAP_SECONDS = 300;
    /** TTS text 字符硬上限（与 StoryboardWorkbenchServiceImpl.TTS_TEXT_MAX_LENGTH 对齐）。 */
    private static final int TTS_TEXT_HARD_CAP_CHARS = 50_000;

    private BillingInputExtractor() {
    }

    /**
     * 从图片生成请求提取计费参数（兼容老调用，使用 request.modelName 作为兜底 modelCode）。
     */
    public static BillingInput fromImageRequest(MediaImageGenerateRequest request) {
        return fromImageRequest(request, request == null ? null : request.getModelName(), null);
    }

    /**
     * 从图片生成请求提取计费参数（兼容老调用，未传配置化的 max_output_count，仅按硬编码兜底）。
     */
    public static BillingInput fromImageRequest(MediaImageGenerateRequest request, String effectiveModelCode) {
        return fromImageRequest(request, effectiveModelCode, null);
    }

    /**
     * 从图片生成请求提取计费参数。
     *
     * @param request                  原始请求
     * @param effectiveModelCode       已解析的最终模型 code（例如 request.modelName 为空时传 modelConfig.modelCode）
     * @param configuredMaxOutputCount aid_ai_model.max_output_count（可空，空则按硬编码兜底）
     */
    public static BillingInput fromImageRequest(MediaImageGenerateRequest request,
                                                String effectiveModelCode,
                                                Integer configuredMaxOutputCount) {
        return fromImageRequest(request, effectiveModelCode, configuredMaxOutputCount, null);
    }

    /** 使用完整模型能力解析模型专属的输出像素档位。 */
    public static BillingInput fromImageRequest(MediaImageGenerateRequest request,
                                                String effectiveModelCode,
                                                Integer configuredMaxOutputCount,
                                                AiModelConfigVo modelConfig) {
        Map<String, Object> params = new HashMap<>();
        // 分辨率三级取值，保证计费命中的 SKU 与真实出图档位一致：
        // 1) options.resolution 显式档位（Vidu 等厂商为 1080p/2K/4K 枚举，与下发上游的字段同源）
        // 2) options.size 或顶层 size 本身是档位串（业务层存在 setSize("2K") 的用法）
        // 3) options.size 或顶层 size 为宽高尺寸串时按像素推断（4K/2K/1K/SD）
        String resolution = null;
        String effectiveSize = extractFromOptions(request.getOptions(), "size");
        if (CharSequenceUtil.isBlank(effectiveSize)) {
            effectiveSize = request.getSize();
        }
        if (isGptImageModel(effectiveModelCode)) {
            // OpenAI 图片协议只使用 size；不能采信额外的 resolution，否则可伪造低档位绕过 4K 计费。
            resolution = ResolutionUtil.parseResolution(effectiveSize);
        } else {
            String explicitResolution = extractFromOptions(request.getOptions(), "resolution");
            if (CharSequenceUtil.isNotBlank(explicitResolution)) {
                String tier = ResolutionUtil.parseTier(explicitResolution);
                resolution = CharSequenceUtil.isNotBlank(tier) ? tier : explicitResolution.trim();
            }
        }
        if (CharSequenceUtil.isBlank(resolution)) {
            resolution = ResolutionUtil.parseTier(effectiveSize);
        }
        if (CharSequenceUtil.isBlank(resolution)) {
            resolution = ResolutionUtil.parseResolution(effectiveSize);
        }
        params.put("resolution", resolution);
        // 宽高比
        params.put("ratio", ResolutionUtil.parseRatio(request.getSize()));
        // 生成模式：ultra 固定为超分；其余按是否有参考图区分 编辑 / 文生图。必须用最终 modelCode 判定
        String generateMode = resolveGenerateMode(request, effectiveModelCode);
        params.put("generateMode", generateMode);
        // 预期输出张数：模型级上限保护，配置化优先、硬编码兜底
        int expectedImageCount = resolveExpectedImageCount(request);
        expectedImageCount = ImageBillingCapabilityHelper.normalizeExpectedCount(
                effectiveModelCode, expectedImageCount, configuredMaxOutputCount);
        params.put("expectedImageCount", expectedImageCount);
        // 兼容既有 SKU 规则：imageCount 与 expectedImageCount 同值
        params.put("imageCount", expectedImageCount);
        // 通用原始参数提取：输入字符数（TOKEN 模型的估算在 BillingEstimateResolver 中完成）
        int inputChars = request != null && CharSequenceUtil.isNotBlank(request.getPrompt())
                ? request.getPrompt().length() : 0;
        params.put("inputChars", inputChars);
        // 从 options 提取 imageSize（部分厂商使用此字段指定分辨率档位，TOKEN 模型估算时使用）
        if (request != null && request.getOptions() != null) {
            String imageSize = extractFromOptions(request.getOptions(), "imageSize");
            if (CharSequenceUtil.isBlank(imageSize)) {
                imageSize = extractFromOptions(request.getOptions(), "image_size");
            }
            if (CharSequenceUtil.isNotBlank(imageSize)) {
                params.put("imageSize", imageSize.trim().toUpperCase());
            }
            // 提取 aspectRatio（TOKEN 图片模型预估输出 token 时使用）
            String aspectRatio = extractFromOptions(request.getOptions(), "aspect_ratio");
            if (CharSequenceUtil.isBlank(aspectRatio)) {
                aspectRatio = extractFromOptions(request.getOptions(), "aspectRatio");
            }
            if (CharSequenceUtil.isNotBlank(aspectRatio)) {
                params.put("aspectRatio", aspectRatio.trim());
            }
            // 提取 quality（gpt-image 等 TOKEN 图片模型按 quality×size 预估输出 token 时使用）
            String quality = extractFromOptions(request.getOptions(), "quality");
            if (CharSequenceUtil.isNotBlank(quality)) {
                params.put("quality", quality.trim());
            }
        }
        // 参考图张数（gpt-image 等编辑模式按张数预估高保真输入 token 时使用）
        params.put("referenceImageCount", countReferenceImages(request));
        // 保留原始 size 值（TOKEN 模型估算分辨率档位的兜底来源）
        if (CharSequenceUtil.isNotBlank(effectiveSize)) {
            params.put("rawSize", effectiveSize.trim());
        }
        params.put("outputPixels", resolveOutputPixels(effectiveSize, modelConfig));
        return new BillingInput("IMAGE", params);
    }

    private static long resolveOutputPixels(String size, AiModelConfigVo modelConfig) {
        if (CharSequenceUtil.isBlank(size)) {
            return Long.MAX_VALUE;
        }
        String normalized = size.trim().replace('×', 'x').replace('*', 'x').toUpperCase();
        if (normalized.matches("\\d{1,5}X\\d{1,5}")) {
            String[] dimensions = normalized.split("X", 2);
            try {
                return Math.multiplyExact(Long.parseLong(dimensions[0]), Long.parseLong(dimensions[1]));
            } catch (NumberFormatException | ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }
        // 1K/2K 等是模型档位，不是全局固定宽高。只有模型核验模板明确给出用于
        // 成本阶梯选择的代表像素值时才使用；缺失时走最高价档，避免低估成本。
        if (modelConfig != null && CharSequenceUtil.isNotBlank(modelConfig.getCapabilityJson())) {
            try {
                JsonNode value = MAPPER.readTree(modelConfig.getCapabilityJson())
                        .path("outputPixelEstimateBySize").path(normalized);
                if (value.canConvertToLong() && value.longValue() > 0) return value.longValue();
            } catch (Exception ignored) {
                // 非法能力 JSON 由管理端保存校验负责；计费侧保守落最高档。
            }
        }
        return Long.MAX_VALUE;
    }

    private static boolean isGptImageModel(String modelCode) {
        return CharSequenceUtil.isNotBlank(modelCode)
            && modelCode.toLowerCase().contains("gpt-image");
    }

    /**
     * 统计请求中的参考图张数：顶层 referenceImageUrl + options.images + options.referenceImages。
     * 仅用于 TOKEN 图片模型编辑模式的输入 token 预估，多计为安全方向（预扣宁高勿低）。
     */
    private static int countReferenceImages(MediaImageGenerateRequest request) {
        if (request == null) {
            return 0;
        }
        Set<String> images = new LinkedHashSet<>();
        addMediaUrl(images, request.getReferenceImageUrl());
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            addMediaUrls(images, options.get("images"));
            addMediaUrls(images, options.get("referenceImages"));
        }
        return images.size();
    }

    /** 取 List 大小，非 List 返回 0。 */
    private static int sizeOfList(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    /**
     * 解析预期输出张数。
     */
    private static int resolveExpectedImageCount(MediaImageGenerateRequest request) {
        if (request == null) {
            return 1;
        }
        Map<String, Object> options = request.getOptions();
        int hardCap = "image_layer_decomposition".equals(request.getCapabilityCode())
                ? IMAGE_LAYER_EXPECTED_COUNT_HARD_CAP : IMAGE_EXPECTED_COUNT_HARD_CAP;
        if (options != null) {
            Object forceSingle = options.get("force_single");
            if (forceSingle != null && Boolean.parseBoolean(String.valueOf(forceSingle))) {
                return 1;
            }
        }
        Integer explicit = request.getExpectedImageCount();
        if (explicit != null && explicit > 0) {
            if (explicit > hardCap) {
                log.info("图片生成数量超过系统安全上限: actual={}, max={}",
                        explicit, hardCap);
                throw new ServiceException("图片数量超限");
            }
            return explicit;
        }
        if (options != null) {
            Object n = options.get("n");
            if (n != null) {
                int parsed = toInt(n);
                if (parsed > 0) {
                    if (parsed > hardCap) {
                        log.info("图片生成数量超过系统安全上限: actual={}, max={}",
                                parsed, hardCap);
                        throw new ServiceException("图片数量超限");
                    }
                    return parsed;
                }
            }
        }
        return 1;
    }

    /**
     * 解析图片生成模式。
     * jimeng-image-ultra 固定 UPSCALE（按真实 modelCode 判定）；其余按三处来源判断是否存在参考图。
     */
    private static String resolveGenerateMode(MediaImageGenerateRequest request, String effectiveModelCode) {
        if (request == null) {
            return "TEXT_TO_IMAGE";
        }
        String modelCode = CharSequenceUtil.isNotBlank(effectiveModelCode)
                ? effectiveModelCode : request.getModelName();
        if (CharSequenceUtil.isNotBlank(modelCode) && "jimeng-image-ultra".equalsIgnoreCase(modelCode)) {
            return "UPSCALE";
        }
        // 参考图来源：顶层 referenceImageUrl、options.images、options.referenceImages
        if (CharSequenceUtil.isNotBlank(request.getReferenceImageUrl())) {
            return "IMAGE_EDIT";
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            if (hasNonEmptyList(options.get("images")) || hasNonEmptyList(options.get("referenceImages"))) {
                return "IMAGE_EDIT";
            }
        }
        return "TEXT_TO_IMAGE";
    }

    private static boolean hasNonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    /**
     * 从音频（TTS）生成请求提取计费参数。
     * 豆包 TTS 单价由 aid_ai_model.cost_credits（FIXED）或 billing_rule_json（SKU）决定；
     * 此处只为 SKU 模式预留文本长度字段 textLength / chars / ttsChars，便于未来按字数定价；
     * FIXED 模式不会读这些字段。
     */
    public static BillingInput fromAudioRequest(MediaAudioGenerateRequest request) {
        Map<String, Object> params = new HashMap<>();
        int textLen = request != null && CharSequenceUtil.isNotBlank(request.getTtsText())
                ? request.getTtsText().length()
                : 0;
        // TTS 文本字符硬上限
        if (textLen > TTS_TEXT_HARD_CAP_CHARS) {
            textLen = TTS_TEXT_HARD_CAP_CHARS;
        }
        params.put("textLength", textLen);
        params.put("chars", textLen);
        params.put("ttsChars", textLen);
        if (request != null && CharSequenceUtil.isNotBlank(request.getVoiceCode())) {
            params.put("voiceCode", request.getVoiceCode());
        }
        if (request != null && CharSequenceUtil.isNotBlank(request.getLanguage())) {
            params.put("language", request.getLanguage());
        }
        return new BillingInput("AUDIO", params);
    }

    /**
     * 从视频生成请求提取计费参数。
     */
    public static BillingInput fromVideoRequest(MediaVideoGenerateRequest request) {
        return fromVideoRequest(request, null);
    }

    /** 从已解析模型与真实输入提取视频计费参数。 */
    public static BillingInput fromVideoRequest(MediaVideoGenerateRequest request,
                                                AiModelConfigVo modelConfig) {
        Map<String, Object> params = new HashMap<>();
        // 时长（秒）：加硬上限避免前端塞超大 duration 打爆结算
        boolean autoDuration = request.getDurationSeconds() == null
                || request.getDurationSeconds() == -1;
        int rawDuration = autoDuration
                ? positiveIntOption(request.getOptions(), "billingDurationSeconds",
                    positiveIntOption(request.getOptions(), "duration", 5))
                : request.getDurationSeconds();
        if (rawDuration < 1) {
            rawDuration = 5;
        }
        // 多帧视频（Vidu multiframe 等）：durationSeconds 是"每段"时长，真实出片长度 = 段数 × 每段时长，
        // 计费必须按总时长，否则 9 段视频只按 1 段扣费
        int segments = countMultiFrameSegments(request.getOptions());
        if (segments > 1) {
            rawDuration = rawDuration * segments;
        }
        if (rawDuration > VIDEO_DURATION_HARD_CAP_SECONDS) {
            rawDuration = VIDEO_DURATION_HARD_CAP_SECONDS;
        }
        params.put("duration", rawDuration);
        params.put("autoDuration", autoDuration);
        // 分辨率：显式传入优先，并做档位规范化（1080P→1080p），保证与 SKU match 值一致；
        // 未传时从宽高比推断（默认 720P，SKU 匹配已忽略大小写）
        String resolution = extractFromOptions(request.getOptions(), "resolution");
        if (CharSequenceUtil.isBlank(resolution)) {
            resolution = extractFromOptions(request.getOptions(), "size");
        }
        if (CharSequenceUtil.isNotBlank(resolution)) {
            String tier = ResolutionUtil.parseTier(resolution);
            params.put("resolution", CharSequenceUtil.isNotBlank(tier) ? tier : resolution.trim());
        } else {
            params.put("resolution", ResolutionUtil.inferVideoResolution(request.getAspectRatio()));
        }
        if (CharSequenceUtil.isNotBlank(request.getAspectRatio())) {
            params.put("aspectRatio", request.getAspectRatio().trim());
        }
        // 音画同出标记（Vidu 视频 SKU 维度之一）：优先取 DTO.audio，其次 options.audio；缺省 false。
        // 音画同出档位通常更贵，预扣方向宁高勿低，仅在显式开启时记 true。
        Boolean audio = request.getAudio();
        if (audio == null) {
            String optAudio = extractFromOptions(request.getOptions(), "audio");
            if (CharSequenceUtil.isNotBlank(optAudio)) {
                audio = Boolean.parseBoolean(optAudio.trim());
            }
        }
        params.put("audio", Boolean.TRUE.equals(audio));
        String audioMode = extractFromOptions(request.getOptions(), "audioMode");
        if (CharSequenceUtil.isBlank(audioMode)) {
            audioMode = extractFromOptions(request.getOptions(), "audio");
        }
        if (CharSequenceUtil.isBlank(audioMode)) {
            audioMode = Boolean.TRUE.equals(audio) ? "native" : "off";
        } else if ("true".equalsIgnoreCase(audioMode)) {
            audioMode = "native";
        } else if ("false".equalsIgnoreCase(audioMode)) {
            audioMode = "off";
        }
        params.put("audioMode", audioMode.trim().toLowerCase());
        // 输入媒体计费参数：参考图张数 + 输入视频段数/秒数（供 inputPricing 附加费计算，未配置价的模型不受影响）
        params.put("referenceImageCount", countVideoInputImages(request));
        int inputVideoCount = countInputVideos(request.getOptions());
        params.put("inputVideoCount", inputVideoCount);
        params.put("referenceVideoCount", inputVideoCount);
        params.put("hasVideoInput", inputVideoCount > 0);
        int referenceAudioCount = request.getReferenceAudios() == null ? 0
                : (int) request.getReferenceAudios().stream().filter(java.util.Objects::nonNull)
                .map(com.aid.media.dto.ReferenceAudioInput::getSampleUrl).filter(CharSequenceUtil::isNotBlank).distinct().count();
        params.put("referenceAudioCount", referenceAudioCount);
        int inputVideoSeconds = extractInputVideoSeconds(request.getOptions());
        // MiniMax H3 按上游实际输入视频秒数结算。预冻结阶段不采信客户端自报时长，
        // 有参考视频时留 0 交给 inputPricing.video.maxSeconds 按官方 15 秒上限冻结。
        if (isMinimaxH3VideoModel(request.getModelName()) && inputVideoCount > 0) {
            inputVideoSeconds = 0;
        }
        if (request.getResolvedReferenceVideos() != null && !request.getResolvedReferenceVideos().isEmpty()) {
            long durationMs = request.getResolvedReferenceVideos().stream()
                    .map(com.aid.media.dto.ReferenceVideoInput::getDurationMs).filter(java.util.Objects::nonNull)
                    .reduce(0L, Math::addExact);
            inputVideoSeconds = Math.toIntExact(Math.floorDiv(Math.addExact(durationMs, 999L), 1000L));
        }
        params.put("inputVideoSeconds", inputVideoSeconds);
        String generateMode = null;
        if (modelConfig != null) {
            generateMode = MediaGenerationSceneResolver.resolveVideo(modelConfig, request).billingGenerateMode();
        }
        if (CharSequenceUtil.isBlank(generateMode)) {
            // 兼容尚未传入模型配置的旧调用；正式报价与提交均走上方可信场景解析。
            generateMode = countVideoInputImages(request) > 0 ? "IMAGE_TO_VIDEO" : "TEXT_TO_VIDEO";
            if (isKlingVideoModel(request.getModelName())) {
                generateMode = inputVideoCount > 0 ? "VIDEO_TO_VIDEO" : generateMode;
            } else {
                String requestedMode = extractFromOptions(request.getOptions(), "generateMode");
                if (isLegacyVideoGenerateMode(requestedMode)) {
                    generateMode = requestedMode.trim().toUpperCase();
                }
            }
        }
        params.put("generateMode", generateMode);
        return new BillingInput("VIDEO", params);
    }

    private static boolean isKlingVideoModel(String modelName) {
        return CharSequenceUtil.isNotBlank(modelName)
                && modelName.trim().toLowerCase().startsWith("kling-3.0-");
    }

    private static boolean isMinimaxH3VideoModel(String modelName) {
        return CharSequenceUtil.isNotBlank(modelName)
                && modelName.trim().toLowerCase().startsWith("minimax-h3-");
    }

    private static boolean isLegacyVideoGenerateMode(String mode) {
        return CharSequenceUtil.isNotBlank(mode)
                && ("TEXT_TO_VIDEO".equalsIgnoreCase(mode)
                || "IMAGE_TO_VIDEO".equalsIgnoreCase(mode)
                || "EDGE_TO_VIDEO".equalsIgnoreCase(mode)
                || "MULTI_TO_VIDEO".equalsIgnoreCase(mode));
    }

    /**
     * 统计视频请求中的输入图片张数：首帧 imageUrl + 尾帧 lastFrameImageUrl + options 参考图列表。
     * 仅用于 inputPricing 图片附加费计算，多计为安全方向（预扣宁高勿低，失败全额退）。
     */
    private static int countVideoInputImages(MediaVideoGenerateRequest request) {
        if (request == null) {
            return 0;
        }
        int semanticSlots = 0;
        Set<String> semanticUrls = new LinkedHashSet<>();
        if (CharSequenceUtil.isNotBlank(request.getImageUrl())) {
            semanticSlots++;
            addMediaUrl(semanticUrls, request.getImageUrl());
        }
        Set<String> images = new LinkedHashSet<>();
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            for (String key : new String[]{"lastFrameImageUrl", "endImageUrl", "end_image_url"}) {
                Object value = options.get(key);
                if (value != null && CharSequenceUtil.isNotBlank(String.valueOf(value))) {
                    semanticSlots++;
                    addMediaUrl(semanticUrls, value);
                    break;
                }
            }
            addMediaUrls(images, options.get("referenceImages"));
            addMediaUrls(images, options.get("images"));
            for (String key : new String[]{"keyImages", "key_images", "image_settings", "imageSettings"}) {
                if (options.get(key) instanceof List<?> list && !list.isEmpty()) {
                    list.forEach(item -> addMediaUrl(images, item));
                    break;
                }
            }
        }
        images.removeAll(semanticUrls);
        return semanticSlots + images.size();
    }

    /**
     * 统计实际 Provider 会读取的输入视频：任一单视频字段只计一段，另计显式列表。
     */
    private static int countInputVideos(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        Set<String> videos = new LinkedHashSet<>();
        for (String key : new String[]{"featureVideoUrl", "referenceVideoUrl", "baseVideoUrl",
                "inputVideoUrl", "videoUrl", "video_url"}) {
            Object v = options.get(key);
            if (v != null && CharSequenceUtil.isNotBlank(String.valueOf(v))) {
                addMediaUrl(videos, v);
            }
        }
        addMediaUrls(videos, options.get("referenceVideos"));
        addMediaUrls(videos, options.get("videos"));
        return videos.size();
    }

    private static void addMediaUrls(Set<String> target, Object value) {
        if (value instanceof List<?> list) {
            list.forEach(item -> addMediaUrl(target, item));
        } else {
            addMediaUrl(target, value);
        }
    }

    private static void addMediaUrl(Set<String> target, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : new String[]{"key_image", "keyImage", "image_url", "imageUrl", "url"}) {
                if (map.containsKey(key)) {
                    addMediaUrl(target, map.get(key));
                    return;
                }
            }
            return;
        }
        if (value != null && CharSequenceUtil.isNotBlank(String.valueOf(value))) {
            target.add(String.valueOf(value).trim());
        }
    }

    private static int positiveIntOption(Map<String, Object> options, String key, int fallback) {
        if (options == null || options.get(key) == null) {
            return fallback;
        }
        int value = toInt(options.get(key));
        return value > 0 ? value : fallback;
    }

    /**
     * 提取输入视频总秒数（业务层显式传入 options.inputVideoSeconds / referenceVideoSeconds）。
     * 未传返回 0；有输入视频但秒数未知时，计费层按 inputPricing.video.maxSeconds 预扣兜底。
     */
    private static int extractInputVideoSeconds(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        for (String key : new String[]{"inputVideoSeconds", "referenceVideoSeconds", "videoSeconds"}) {
            Object v = options.get(key);
            if (v != null) {
                int parsed = toInt(v);
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return 0;
    }

    /**
     * 统计多帧视频段数：options 携带 imageSettings / keyImages 等关键帧列表时，
     * 列表长度即段数（每个关键帧对应一段视频）；非多帧请求返回 1。
     */
    private static int countMultiFrameSegments(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return 1;
        }
        // 与 ViduVideoProviderClient 组装多帧请求体读取的 options 键保持一致
        String[] keys = {"image_settings", "imageSettings", "key_images", "keyImages"};
        for (String key : keys) {
            Object raw = options.get(key);
            if (raw instanceof List<?> list && !list.isEmpty()) {
                return list.size();
            }
        }
        return 1;
    }

    /**
     * 从文本生成请求提取计费参数。
     */
    public static BillingInput fromTextRequest(MediaTextGenerateRequest request) {
        Map<String, Object> params = new HashMap<>();
        int inputChars = TextTokenEstimator.saturatedCharacterCount(request);
        int conservativeInputTokens = TextTokenEstimator.estimateRequestConservative(request);
        int balancedInputTokens = TextTokenEstimator.estimateRequestBalanced(request);
        params.put("inputChars", inputChars);
        params.put("inputTokens", conservativeInputTokens);
        params.put("conservativeInputTokens", conservativeInputTokens);
        params.put("balancedInputTokens", balancedInputTokens);

        int estimatedOutputChars = BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS;
        Object requestedOutputChars = extractFromOptionsObj(request.getOptions(), "estimatedOutputChars");
        if (requestedOutputChars != null) {
            estimatedOutputChars = Math.max(0,
                    Math.min(toInt(requestedOutputChars), TEXT_ESTIMATED_OUTPUT_CHARS_HARD_CAP));
        }
        int providerOutputTokens = optionalPositiveTokenLimit(extractFromOptionsObj(request.getOptions(),
                com.aid.media.provider.TextOutputLimitResolver.PROVIDER_OUTPUT_TOKENS_KEY));
        int billingOutputTokens = optionalPositiveTokenLimit(extractFromOptionsObj(request.getOptions(),
                com.aid.media.provider.TextOutputLimitResolver.BILLING_OUTPUT_TOKENS_KEY));
        if (providerOutputTokens <= 0) {
            providerOutputTokens = optionalPositiveTokenLimit(extractFromOptionsObj(request.getOptions(),
                    com.aid.media.provider.TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY));
        }
        if (providerOutputTokens <= 0) {
            providerOutputTokens = com.aid.media.provider.TextOutputLimitResolver.FALLBACK_OUTPUT_TOKENS;
        }
        if (billingOutputTokens <= 0) {
            billingOutputTokens = com.aid.media.provider.TextOutputLimitResolver.billingCeiling(providerOutputTokens);
        }
        int normalizedReasoningBudget = request.getReasoningBudgetTokens() == null
                ? 0 : positiveTokenBudget(request.getReasoningBudgetTokens());
        boolean reasoningEnabled = Boolean.TRUE.equals(request.getReasoningEnabled());
        params.put("outputTokens", billingOutputTokens);
        params.put("providerOutputTokenCap", providerOutputTokens);
        params.put("billingOutputTokenCeiling", billingOutputTokens);
        params.put("reasoningEnabled", reasoningEnabled);
        params.put("reasoningOverridePresent", request.getReasoningEnabled() != null);
        params.put("reasoningBudgetTokens", normalizedReasoningBudget);
        params.put("reasoningBudgetOverridePresent", normalizedReasoningBudget > 0);
        params.put("outputLimitPresent", true);
        params.put("estimatedOutputChars", estimatedOutputChars);
        params.put("totalChars", (int) Math.min((long) inputChars + estimatedOutputChars, Integer.MAX_VALUE));

        Map<String, Integer> modalityCounts = new HashMap<>();
        if (request.getMessages() != null) {
            for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages()) {
                if (message == null || message.getParts() == null) {
                    continue;
                }
                for (MediaTextGenerateRequest.TextContentPart part : message.getParts()) {
                    if (part != null && part.getType() != null && !"text".equalsIgnoreCase(part.getType())) {
                        modalityCounts.merge(part.getType().trim().toLowerCase(), 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Object> textOptions = request.getOptions();
        int legacyImages = textOptions == null ? 0
                : sizeOfList(textOptions.get("images")) + sizeOfList(textOptions.get("referenceImages"));
        modalityCounts.merge("image", legacyImages, Integer::sum);
        modalityCounts.forEach((type, count) -> {
            if (count > 0) {
                params.put(type + "InputCount", count);
            }
        });
        return new BillingInput("TEXT", params);
    }

    /**
     * 从options Map中提取字符串值
     */
    private static String extractFromOptions(Map<String, Object> options, String key) {
        if (options == null) {
            return null;
        }
        Object val = options.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    /**
     * 从options Map中提取Object值
     */
    private static Object extractFromOptionsObj(Map<String, Object> options, String key) {
        if (options == null) {
            return null;
        }
        return options.get(key);
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS;
        }
    }

    private static int optionalPositiveTokenLimit(Object value) {
        if (value == null) {
            return 0;
        }
        long parsed;
        try {
            parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(parsed, TEXT_ESTIMATED_OUTPUT_TOKENS_HARD_CAP));
    }

    /** 思考预算允许 0 表示不覆盖厂商默认；非法值归 0，正数与输出上限使用同一硬上限。 */
    private static int positiveTokenBudget(Object value) {
        long parsed;
        try {
            parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(parsed, TEXT_ESTIMATED_OUTPUT_TOKENS_HARD_CAP));
    }

    /**
     * 向上取整整数除法：ceil(a / b)。
     * 预扣阶段必须向上取整，避免系统性少扣。
     */
    private static int ceilDiv(int a, int b) {
        if (b <= 0) {
            return a;
        }
        return (a + b - 1) / b;
    }
}
