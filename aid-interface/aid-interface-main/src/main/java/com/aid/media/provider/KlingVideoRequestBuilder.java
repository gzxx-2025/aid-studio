package com.aid.media.provider;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按后台 {@code capability_json.klingScenario} 严格组装可灵 3.0 请求体。 */
@Slf4j
public final class KlingVideoRequestBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern IMAGE_TOKEN = Pattern.compile("图片(\\d+)(?:\\[[^\\]]*])?");
    private static final Pattern OFFICIAL_REFERENCE = Pattern.compile("@([A-Za-z][A-Za-z0-9_-]{0,63})");
    private static final Pattern STRAY_AT = Pattern.compile("@(?=\\S)");
    private static final Set<String> RESOLUTIONS = Set.of("720p", "1080p", "4k");
    private static final Set<String> ASPECT_RATIOS = Set.of("16:9", "9:16", "1:1");

    private KlingVideoRequestBuilder() {
    }

    /**
     * 使用与实际提交完全相同的清洗和构造流程生成请求体，不修改原始业务请求。
     */
    public static Map<String, Object> buildSubmissionBody(AiModelConfigVo config,
                                                           MediaVideoGenerateRequest request) {
        return buildSubmissionBody(config, request, false);
    }

    private static Map<String, Object> buildSubmissionBody(AiModelConfigVo config,
                                                           MediaVideoGenerateRequest request,
                                                           boolean promptPending) {
        int dispatchedImages = validateRequestInputs(config, request);
        MediaVideoGenerateRequest sanitized = new MediaVideoGenerateRequest();
        BeanUtil.copyProperties(request, sanitized);
        sanitized.setPrompt(ReferencePromptSanitizer.sanitizePreservingSubjectRefs(
            request.getPrompt(), dispatchedImages, 0));
        return build(config, sanitized, promptPending);
    }

    /** 在任务落库和冻结前执行完整可灵 Provider 请求契约校验。 */
    public static void validateFullRequest(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        validateFullRequest(config, request, false);
    }

    /** 计划报价不构造占位提示词；已知素材、设置和已有提示词仍完整校验。 */
    public static void validateFullRequest(AiModelConfigVo config, MediaVideoGenerateRequest request,
                                           boolean promptPending) {
        buildSubmissionBody(config, request, promptPending);
    }

    public static Map<String, Object> build(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        return build(config, request, false);
    }

    private static Map<String, Object> build(AiModelConfigVo config, MediaVideoGenerateRequest request,
                                             boolean promptPending) {
        if (config == null || request == null) {
            return fail("null model or request", "模型配置无效");
        }
        String scenario = resolveScenario(config);
        ResolvedInputs inputs = resolveAndValidateInputs(config, request, scenario);
        Map<String, Object> options = inputs.options();
        String firstFrame = inputs.firstFrame();
        String lastFrame = inputs.lastFrame();
        List<String> referenceImages = inputs.referenceImages();
        String featureVideo = inputs.featureVideo();
        String baseVideo = inputs.baseVideo();
        List<ElementInput> elements = inputs.elements();
        List<Map<String, Object>> contents = new ArrayList<>();
        List<String> placeholderImageIds = new ArrayList<>();
        Set<String> allowedReferenceIds = new LinkedHashSet<>();
        int imageIndex = 1;
        if (StrUtil.isNotBlank(firstFrame)) {
            String id = "image_" + imageIndex++;
            contents.add(urlContent("first_frame", firstFrame, isOmni(scenario) ? id : null));
            if (isOmni(scenario)) {
                allowedReferenceIds.add(id);
                if (!KlingConstants.SCENARIO_OMNI_REFERENCE.equals(scenario)) {
                    placeholderImageIds.add(id);
                }
            }
        }
        if (StrUtil.isNotBlank(lastFrame)) {
            String id = "image_" + imageIndex++;
            contents.add(urlContent("last_frame", lastFrame, isOmni(scenario) ? id : null));
            if (isOmni(scenario)) {
                allowedReferenceIds.add(id);
                if (KlingConstants.SCENARIO_OMNI_FIRST_LAST.equals(scenario)) {
                    placeholderImageIds.add(id);
                }
            }
        }
        if (KlingConstants.SCENARIO_OMNI_REFERENCE.equals(scenario)
            || KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario)
            || KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario)) {
            for (String url : referenceImages) {
                String id = "image_" + imageIndex++;
                contents.add(urlContent("refer_image", url, id));
                allowedReferenceIds.add(id);
                placeholderImageIds.add(id);
            }
        }
        if (KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario)) {
            contents.add(urlContent("feature_video", featureVideo, "video_1"));
            allowedReferenceIds.add("video_1");
        }
        if (KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario)) {
            contents.add(urlContent("base_video", baseVideo, "video_1"));
            allowedReferenceIds.add("video_1");
        }
        int elementIndex = 1;
        for (ElementInput element : elements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "element");
            item.put("element_id", element.elementId());
            item.put("id", StrUtil.isBlank(element.referenceId()) ? "element_" + elementIndex : element.referenceId());
            if (!allowedReferenceIds.add(String.valueOf(item.get("id")))) {
                fail("duplicate contents id=" + item.get("id"), "素材编号重复");
            }
            contents.add(item);
            elementIndex++;
        }

        String prompt = rehydratePrompt(StrUtil.trim(request.getPrompt()), placeholderImageIds);
        prompt = retainOnlyDispatchedReferences(prompt, allowedReferenceIds);
        int promptMax = isOmni(scenario) ? 3072 : 2500;
        if ((!promptPending && StrUtil.isBlank(prompt)) || StrUtil.length(prompt) > promptMax) {
            fail("prompt length=" + StrUtil.length(prompt), "提示词无效");
        }
        if (StrUtil.isNotBlank(prompt)) {
            Map<String, Object> promptContent = new LinkedHashMap<>();
            promptContent.put("type", "prompt");
            promptContent.put("text", prompt);
            contents.add(0, promptContent);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", contents);
        body.put("settings", buildSettings(scenario, request, options, firstFrame, featureVideo, baseVideo));
        Map<String, Object> commonOptions = buildCommonOptions(config, options);
        if (!commonOptions.isEmpty()) {
            body.put("options", commonOptions);
        }
        return body;
    }

    /**
     * 按请求构建器的真实场景、别名择一和去重口径校验输入，并返回实际会派发的图片数。
     * 此方法不修改请求，可在任务落库和冻结计费前安全调用。
     */
    public static int validateRequestInputs(AiModelConfigVo config, MediaVideoGenerateRequest request) {
        if (config == null || request == null) {
            return fail("null model or request", "模型配置无效");
        }
        String scenario = resolveScenario(config);
        return resolveAndValidateInputs(config, request, scenario).dispatchedImageCount();
    }

    public static String resolveScenario(AiModelConfigVo config) {
        if (config == null || StrUtil.isBlank(config.getCapabilityJson())) {
            return fail("missing kling capability", "模型配置无效");
        }
        try {
            JsonNode root = MAPPER.readTree(config.getCapabilityJson());
            String scenario = root.path("klingScenario").asText("").trim();
            if (!KlingConstants.SCENARIOS.contains(scenario)) {
                return fail("unknown kling scenario=" + scenario, "模型配置无效");
            }
            return scenario;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            return fail("invalid kling capability json", "模型配置无效");
        }
    }

    private static ResolvedInputs resolveAndValidateInputs(AiModelConfigVo config,
                                                            MediaVideoGenerateRequest request,
                                                            String scenario) {
        Map<String, Object> options = request.getOptions() == null ? Map.of() : request.getOptions();
        String firstFrame = StrUtil.trim(request.getImageUrl());
        String lastFrame = firstNonBlank(options, "lastFrameImageUrl", "endImageUrl", "end_image_url");
        List<String> referenceImages = resolveReferenceImages(options);
        String explicitFeatureVideo = firstNonBlank(options, "featureVideoUrl", "referenceVideoUrl");
        String explicitBaseVideo = firstNonBlank(options, "baseVideoUrl", "inputVideoUrl");
        String sharedVideo = firstNonBlank(options, "videoUrl", "video_url");
        List<String> referenceVideos = readStringList(options.get("referenceVideos"));
        if (referenceVideos.isEmpty()) {
            referenceVideos = readStringList(options.get("videos"));
        }
        if (referenceVideos.size() > 1) {
            fail("multiple reference videos=" + referenceVideos.size(), "参考视频数量超限");
        }
        if (!referenceVideos.isEmpty()) {
            String listVideo = referenceVideos.get(0);
            if ((StrUtil.isNotBlank(explicitFeatureVideo) && !explicitFeatureVideo.equals(listVideo))
                || (StrUtil.isNotBlank(explicitBaseVideo) && !explicitBaseVideo.equals(listVideo))
                || (StrUtil.isNotBlank(sharedVideo) && !sharedVideo.equals(listVideo))) {
                fail("conflicting reference video aliases", "参考视频参数互斥");
            }
        }
        if (StrUtil.isBlank(sharedVideo) && !referenceVideos.isEmpty()) {
            sharedVideo = referenceVideos.get(0);
        }
        String featureVideo = explicitFeatureVideo;
        String baseVideo = explicitBaseVideo;
        if (KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario) && StrUtil.isBlank(featureVideo)) {
            featureVideo = sharedVideo;
        } else if (KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario) && StrUtil.isBlank(baseVideo)) {
            baseVideo = sharedVideo;
        } else if (!KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario)
            && !KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario) && StrUtil.isNotBlank(sharedVideo)) {
            // 其它场景仍需把共享视频别名识别为不支持的视频输入，而不是静默忽略。
            featureVideo = sharedVideo;
        }
        List<ElementInput> elements = readElements(options.get("elements"));
        validateScenarioInputs(scenario, firstFrame, lastFrame, referenceImages, featureVideo, baseVideo, elements);

        int dispatchedImageCount = countDispatchedImages(
            scenario, firstFrame, lastFrame, referenceImages);
        int minReferenceImages = ReferenceImageLimiter.readMinFromCapabilityJson(config.getCapabilityJson());
        if (minReferenceImages > 0 && dispatchedImageCount < minReferenceImages) {
            fail("insufficient dispatched images=" + dispatchedImageCount + "/" + minReferenceImages,
                "至少传" + minReferenceImages + "张图");
        }
        return new ResolvedInputs(scenario, options, firstFrame, lastFrame, referenceImages,
            featureVideo, baseVideo, elements, dispatchedImageCount);
    }

    private static int countDispatchedImages(String scenario, String firstFrame, String lastFrame,
                                             List<String> referenceImages) {
        int count = StrUtil.isNotBlank(firstFrame) ? 1 : 0;
        if (StrUtil.isNotBlank(lastFrame)) {
            count++;
        }
        if (KlingConstants.SCENARIO_OMNI_REFERENCE.equals(scenario)
            || KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario)
            || KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario)) {
            count += referenceImages.size();
        }
        return count;
    }

    private static void validateScenarioInputs(String scenario, String first, String last, List<String> refs,
                                               String featureVideo, String baseVideo, List<ElementInput> elements) {
        boolean hasFirst = StrUtil.isNotBlank(first);
        boolean hasLast = StrUtil.isNotBlank(last);
        boolean hasRefs = !refs.isEmpty();
        boolean hasFeature = StrUtil.isNotBlank(featureVideo);
        boolean hasBase = StrUtil.isNotBlank(baseVideo);
        if (hasLast && !hasFirst) {
            fail("last frame without first frame", "尾帧参数无效");
        }
        switch (scenario) {
            case KlingConstants.SCENARIO_TURBO_I2V, KlingConstants.SCENARIO_STANDARD_I2V,
                 KlingConstants.SCENARIO_OMNI_I2V -> {
                require(hasFirst, "该模型必须提供首帧");
                reject(hasLast || hasRefs || hasFeature || hasBase || !elements.isEmpty(), "该模型仅支持首帧图生视频");
            }
            case KlingConstants.SCENARIO_STANDARD_MULTI -> {
                require(hasFirst, "该模型必须提供首帧");
                reject(hasRefs || hasFeature || hasBase, "标准 3.0 多参数场景不支持参考图或参考视频");
                reject(elements.size() > 3, "首尾帧场景最多支持 3 个主体");
            }
            case KlingConstants.SCENARIO_OMNI_T2V ->
                reject(hasFirst || hasLast || hasRefs || hasFeature || hasBase || !elements.isEmpty(), "纯文本模型不接受参考素材");
            case KlingConstants.SCENARIO_OMNI_FIRST_LAST -> {
                require(hasFirst && hasLast, "首尾帧模型必须同时提供首帧和尾帧");
                reject(hasRefs || hasFeature || hasBase, "首尾帧模型不支持参考图或参考视频");
                reject(elements.size() > 3, "首尾帧场景最多支持 3 个主体");
            }
            case KlingConstants.SCENARIO_OMNI_REFERENCE -> {
                require(hasRefs || !elements.isEmpty(), "多参考模型必须提供参考图或主体；仅首帧请使用首帧模型");
                reject(hasFeature || hasBase, "参考生成模型不接受视频输入");
                reject(hasFirst && elements.size() > 3, "首帧场景主体超过3个");
                int dispatchedImageCount = refs.size() + (hasFirst ? 1 : 0) + (hasLast ? 1 : 0);
                validateReferenceCombination(dispatchedImageCount, elements);
            }
            case KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO -> {
                require(hasFeature, "视频特征参考模型必须提供参考视频");
                reject(hasFirst || hasLast || hasBase, "视频特征场景不接受首尾帧或待编辑视频");
                validateFeatureVideoCombination(refs.size(), elements);
            }
            case KlingConstants.SCENARIO_OMNI_EDIT -> {
                require(hasBase, "视频编辑模型必须提供待编辑视频");
                reject(hasFirst || hasLast || hasFeature, "视频编辑场景不接受首尾帧或特征参考视频");
                validateFeatureVideoCombination(refs.size(), elements);
            }
            default -> fail("unknown scenario=" + scenario, "模型配置无效");
        }
    }

    /** Omni 无参考视频时的图片/主体组合上限。 */
    private static void validateReferenceCombination(int referenceImageCount, List<ElementInput> elements) {
        int videoCharacters = 0;
        int multiImageElements = 0;
        for (ElementInput element : elements) {
            require(StrUtil.isNotBlank(element.elementType()), "Omni 参考主体必须提供 elementType");
            if ("video_character_elements".equals(element.elementType())) {
                videoCharacters++;
            } else if ("multi_image_elements".equals(element.elementType())) {
                multiImageElements++;
            } else {
                fail("unknown elementType=" + element.elementType(), "主体类型无效");
            }
        }
        reject(videoCharacters > 3, "视频角色主体不能超过 3 个");
        if (videoCharacters > 0 && (multiImageElements > 0 || referenceImageCount > 0)) {
            reject(referenceImageCount + multiImageElements > 4,
                "混合主体场景中参考图片与多图主体合计不能超过 4 个");
        } else {
            reject(referenceImageCount + multiImageElements > 7,
                "参考图片与多图主体合计不能超过 7 个");
        }
    }

    /** Omni 有参考视频时的参考图/主体互斥与数量上限。 */
    private static void validateFeatureVideoCombination(int referenceImageCount, List<ElementInput> elements) {
        int videoCharacters = 0;
        int multiImageElements = 0;
        for (ElementInput element : elements) {
            require(StrUtil.isNotBlank(element.elementType()), "Omni 参考主体必须提供 elementType");
            if ("video_character_elements".equals(element.elementType())) {
                videoCharacters++;
            } else if ("multi_image_elements".equals(element.elementType())) {
                multiImageElements++;
            } else {
                fail("unknown elementType=" + element.elementType(), "主体类型无效");
            }
        }
        reject(videoCharacters > 0 && multiImageElements > 0,
            "有参考视频时不能混用视频角色主体和多图主体");
        reject(videoCharacters > 0 && referenceImageCount > 0,
            "有参考视频和视频角色主体时不能再添加参考图片");
        if (videoCharacters > 0) {
            reject(videoCharacters > 1, "有参考视频时视频角色主体不能超过 1 个");
        } else {
            reject(referenceImageCount + multiImageElements > 4,
                "有参考视频时参考图片与多图主体合计不能超过 4 个");
        }
    }

    private static Map<String, Object> buildSettings(String scenario, MediaVideoGenerateRequest request,
                                                     Map<String, Object> options, String first,
                                                     String featureVideo, String baseVideo) {
        Map<String, Object> settings = new LinkedHashMap<>();
        String resolution = StrUtil.blankToDefault(firstNonBlank(options, "resolution", "size"), "720p")
            .toLowerCase(Locale.ROOT);
        if (!RESOLUTIONS.contains(resolution) || (KlingConstants.SCENARIO_TURBO_I2V.equals(scenario) && "4k".equals(resolution))) {
            fail("unsupported resolution=" + resolution, "分辨率无效");
        }
        settings.put("resolution", resolution);
        int duration = request.getDurationSeconds() == null ? intOption(options, "duration", 5) : request.getDurationSeconds();
        if (duration < 3 || duration > 15) {
            fail("duration out of range=" + duration, "时长参数无效");
        }
        settings.put("duration", duration);
        if (KlingConstants.SCENARIO_TURBO_I2V.equals(scenario)) {
            reject(hasAny(options, "audioMode", "audio", "multiShot", "multi_shot", "aspect_ratio"),
                "Turbo 模型不支持音频、多镜头或比例参数");
            return settings;
        }

        String audio = resolveAudio(request, options);
        boolean scenarioDefaultMultiShot = KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario)
            || (isOmni(scenario) && !KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario));
        boolean multiShot = booleanOption(options, "multiShot",
            booleanOption(options, "multi_shot", scenarioDefaultMultiShot));
        if (scenario.startsWith("standard_")) {
            if (!Set.of("off", "native").contains(audio)) {
                fail("standard audio=" + audio, "音频模式无效");
            }
            settings.put("audio", audio);
            settings.put("multi_shot", multiShot);
            reject(hasAny(options, "aspect_ratio", "aspectRatio"), "标准图生视频不接受画幅比例参数");
            return settings;
        }

        if (KlingConstants.SCENARIO_OMNI_FEATURE_VIDEO.equals(scenario)) {
            reject(!"off".equals(audio) || !multiShot, "视频特征参考必须使用 multi_shot=true 且 audio=off");
        } else if (KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario)) {
            reject(multiShot || "native".equals(audio), "视频编辑必须使用 multi_shot=false，且 audio 不能为 native");
            reject(!Set.of("off", "original").contains(audio), "视频编辑音频模式仅支持 off/original");
        } else {
            reject("original".equals(audio), "无参考视频时不能使用 original 音频模式");
            reject(!Set.of("off", "native").contains(audio), "音频模式不符合模型要求");
        }
        settings.put("audio", audio);
        settings.put("multi_shot", multiShot);
        if (KlingConstants.SCENARIO_OMNI_EDIT.equals(scenario)
            && (hasAny(options, "aspect_ratio", "aspectRatio") || StrUtil.isNotBlank(request.getAspectRatio()))) {
            fail("reference video scenario received aspect ratio", "参考视频场景不接受画幅比例参数");
        }
        String aspect = firstNonBlank(options, "aspect_ratio", "aspectRatio");
        if (StrUtil.isBlank(aspect)) {
            aspect = request.getAspectRatio();
        }
        if (StrUtil.isBlank(first) && StrUtil.isBlank(baseVideo) && StrUtil.isBlank(aspect)) {
            aspect = "16:9";
        }
        if (StrUtil.isNotBlank(aspect)) {
            if (!ASPECT_RATIOS.contains(aspect.trim())) {
                fail("unsupported aspect=" + aspect, "画幅比例无效");
            }
            settings.put("aspect_ratio", aspect.trim());
        }
        return settings;
    }

    private static Map<String, Object> buildCommonOptions(AiModelConfigVo config, Map<String, Object> options) {
        Map<String, Object> result = new LinkedHashMap<>();
        String callbackUrl = KlingCallbackSupport.resolveCallbackUrlForSubmission(config);
        if (StrUtil.isNotBlank(callbackUrl)) {
            result.put("callback_url", callbackUrl);
        }
        String externalId = firstNonBlank(options, "external_task_id", "externalTaskId");
        if (StrUtil.isNotBlank(externalId)) {
            result.put("external_task_id", externalId);
        }
        boolean watermark = booleanOption(options, "watermark", false);
        Map<String, Object> watermarkInfo = new LinkedHashMap<>();
        watermarkInfo.put("enabled", watermark);
        result.put("watermark_info", watermarkInfo);
        return result;
    }

    private static String resolveAudio(MediaVideoGenerateRequest request, Map<String, Object> options) {
        String explicit = firstNonBlank(options, "audioMode");
        if (StrUtil.isNotBlank(explicit)) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        Object legacy = options.get("audio");
        if (legacy instanceof Boolean bool) {
            return bool ? "native" : "off";
        }
        if (legacy != null && StrUtil.isNotBlank(String.valueOf(legacy))) {
            String value = String.valueOf(legacy).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(value)) {
                return "native";
            }
            if ("false".equals(value)) {
                return "off";
            }
            return value;
        }
        return Boolean.TRUE.equals(request.getAudio()) ? "native" : "off";
    }

    private static String rehydratePrompt(String prompt, List<String> imageIds) {
        if (StrUtil.isBlank(prompt) || imageIds.isEmpty()) {
            return prompt;
        }
        Matcher matcher = IMAGE_TOKEN.matcher(prompt);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            int originalIndex = Integer.parseInt(matcher.group(1));
            String replacement = originalIndex > 0 && originalIndex <= imageIds.size()
                ? "@" + imageIds.get(originalIndex - 1) : matcher.group();
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /** 仅保留本次 contents 中真实存在的官方引用 ID；其它 @ 标记退化成裸文本。 */
    private static String retainOnlyDispatchedReferences(String prompt, Set<String> allowedIds) {
        if (StrUtil.isBlank(prompt)) {
            return prompt;
        }
        Matcher matcher = OFFICIAL_REFERENCE.matcher(prompt);
        StringBuffer protectedText = new StringBuffer();
        while (matcher.find()) {
            String id = matcher.group(1);
            String replacement = allowedIds.contains(id) ? "\u0001" + id : id;
            matcher.appendReplacement(protectedText, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(protectedText);
        String stripped = STRAY_AT.matcher(protectedText.toString()).replaceAll("");
        return stripped.replace('\u0001', '@');
    }

    private static Map<String, Object> urlContent(String type, String url, String id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("url", url);
        if (StrUtil.isNotBlank(id)) {
            result.put("id", id);
        }
        return result;
    }

    private static List<ElementInput> readElements(Object value) {
        List<ElementInput> result = new ArrayList<>();
        if (!(value instanceof Iterable<?> iterable)) {
            return result;
        }
        Set<String> referenceIds = new LinkedHashSet<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                String elementId = stringValue(map.get("element_id"));
                if (StrUtil.isBlank(elementId)) {
                    elementId = stringValue(map.get("elementId"));
                }
                require(StrUtil.isNotBlank(elementId), "主体缺少 element_id");
                String referenceId = stringValue(map.get("id"));
                if (StrUtil.isNotBlank(referenceId) && !referenceIds.add(referenceId)) {
                    fail("duplicate element id=" + referenceId, "素材编号重复");
                }
                String elementType = stringValue(map.get("element_type"));
                if (StrUtil.isBlank(elementType)) {
                    elementType = stringValue(map.get("elementType"));
                }
                result.add(new ElementInput(elementId, referenceId, elementType));
            } else if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                result.add(new ElementInput(String.valueOf(item).trim(), null, null));
            }
        }
        return result;
    }

    private static List<String> readStringList(Object value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = stringValue(item);
                if (StrUtil.isNotBlank(text)) {
                    result.add(text);
                }
            }
        } else if (value instanceof String text && StrUtil.isNotBlank(text)) {
            result.add(text.trim());
        }
        return new ArrayList<>(result);
    }

    /** 与请求体构造共用的参考图解析口径：去空、去重，首选列表为空时回退通用 images。 */
    public static List<String> resolveReferenceImages(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<String> references = readStringList(options.get("referenceImages"));
        return references.isEmpty() ? readStringList(options.get("images")) : references;
    }

    private static boolean isOmni(String scenario) {
        return scenario.startsWith("omni_");
    }

    private static boolean hasAny(Map<String, Object> options, String... keys) {
        for (String key : keys) {
            if (options.containsKey(key) && options.get(key) != null) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(Map<String, Object> options, String... keys) {
        for (String key : keys) {
            String value = stringValue(options.get(key));
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intOption(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fail("duration is not integer", "时长参数无效");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            fail(message, message);
        }
    }

    private static void reject(boolean condition, String message) {
        if (condition) {
            fail(message, message);
        }
    }

    private static <T> T fail(String reason, String clientMessage) {
        log.warn("Kling request rejected: {}", reason);
        throw new ServiceException(clientMessage);
    }

    private record ElementInput(String elementId, String referenceId, String elementType) {
    }

    private record ResolvedInputs(String scenario, Map<String, Object> options,
                                  String firstFrame, String lastFrame,
                                  List<String> referenceImages, String featureVideo,
                                  String baseVideo, List<ElementInput> elements,
                                  int dispatchedImageCount) {
    }
}
