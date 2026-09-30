package com.aid.aid.controller.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.service.support.ModelBillingRuleValidator;
import com.aid.common.exception.ServiceException;
import com.aid.media.constants.ConfigurableAsyncMediaConstants;
import com.aid.media.constants.AgnesConstants;
import com.aid.media.constants.DashscopeConstants;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.constants.ViduConstants;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.provider.ReferenceAudioLimiter;
import com.aid.media.provider.ViduCallbackSupport;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 模型 / 服务商管理表单的 JSON 列入参校验器。
 * 把所有 JSON 列的轻量校验集中到写入侧：非空时必须以 {@code {} 包裹且能成功 JSON parse，
 * 避免运营粘贴非法字符串（如 UUID）落库，污染 billing_rule_json / capability_json 等关键字段
 * 导致计费 / 调度失败。任意校验失败抛 {@link ServiceException}（≤ 6 字文案，符合编码规范）。
 *
 * @author 视觉AID
 */
@Slf4j
public final class AiConfigJsonValidator
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 已实现参考音频下发的服务商白名单。
     * Provider 路由以 provider_code 为最高优先级（见 MediaGenerationServiceImpl 的四级解析），
     * protocol 仅是兜底信号且视频模型普遍留空，故能力位归属必须以 provider_code 判定。
     * 白名单外的服务商开启该位后音频会被静默丢弃却照常扣费，因此直接拒绝保存。
     * 新厂商实现参考音频下发后必须在此登记。
     */
    private static final Set<String> REFERENCE_AUDIO_PROVIDER_CODES =
            Set.of(VolcengineConstants.PROVIDER_CODE);

    /**
     * 已实现参考音频下发的协议白名单。
     * 与 {@link #REFERENCE_AUDIO_PROVIDER_CODES} 取并集：protocol 显式填了受支持协议时同样放行，
     * 避免只按服务商判定而漏掉按协议路由的配置方式。
     */
    private static final Set<String> REFERENCE_AUDIO_PROTOCOLS =
            Set.of(VolcengineConstants.PROTOCOL_SEEDANCE_VIDEO,
                    MinimaxH3Constants.PROTOCOL_VIDEO,
                    ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO,
                    "tencent-ci-async-media",
                    "dmc-h3-video");

    /** 可配置异步视频协议允许下发的音频开关字段；none 表示上游隐式处理。 */
    private static final Set<String> CONFIGURABLE_VIDEO_AUDIO_FIELDS =
            Set.of("audio", "generate_audio", ConfigurableAsyncMediaConstants.UPSTREAM_AUDIO_FIELD_NONE);

    /** 文案统一 ≤6 字（编码规范），具体损坏内容由 log.error 打印给开发排查。 */
    private static final String ERR_INVALID_JSON = "JSON格式错";

    /** 回调配置错误文案。 */
    private static final String ERR_INVALID_CALLBACK = "回调地址错误";

    /** 参考音频能力配置错误文案。 */
    private static final String ERR_INVALID_REFERENCE_AUDIO = "音频配置错误";

    /** 上游音频字段配置错误文案。 */
    private static final String ERR_INVALID_AUDIO_FIELD = "音频字段错误";

    private AiConfigJsonValidator()
    {
    }
    /**
     * 校验 {@link AidAiProvider} 上的全部 JSON 列。
     * 涉及字段：{@code schedule_strategy_json / extra_headers / extra_body / extra_query}
     */
    public static void validate(AidAiProvider provider)
    {
        if (provider == null)
        {
            return;
        }
        provider.setExtraBody(sanitizeRuntimeTextOptions(provider.getExtraBody()));
        validateJsonObjectIfPresent("schedule_strategy_json", provider.getScheduleStrategyJson());
        validateJsonObjectIfPresent("extra_headers", provider.getExtraHeaders());
        validateJsonObjectIfPresent("extra_body", provider.getExtraBody());
        validateJsonObjectIfPresent("extra_query", provider.getExtraQuery());
        validateViduCallback(provider);
    }

    /**
     * Vidu 开启回调时必须提供完整 HTTP/HTTPS 地址，避免配置表显示已开启但运行时只能降级轮询。
     */
    private static void validateViduCallback(AidAiProvider provider)
    {
        if (!"vidu".equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()))
                || !Boolean.TRUE.equals(provider.getSupportsCallback()))
        {
            return;
        }
        String callbackUrl = null;
        try
        {
            JsonNode strategy = OBJECT_MAPPER.readTree(provider.getScheduleStrategyJson());
            callbackUrl = strategy == null ? null : strategy.path("callbackBaseUrl").asText(null);
            if (!ViduCallbackSupport.isValidCallbackBaseUrl(callbackUrl))
            {
                throw new IllegalArgumentException("地址不完整");
            }
        }
        catch (Exception e)
        {
            log.error("Vidu 回调地址非法: callbackUrl={}, reason={}", callbackUrl, e.getMessage());
            throw new ServiceException(ERR_INVALID_CALLBACK);
        }
    }
    /**
     * 校验 {@link AidAiModel} 上的全部 JSON 列。
     * 涉及字段：{@code billing_rule_json / schedule_strategy_json / capability_json /
     * param_mapping_json / extra_body}
     *
     * @param model        待校验模型
     * @param providerCode 模型所属服务商编码（{@code aid_ai_provider.provider_code}），
     *                     参考音频能力位按它判定归属；取不到时传 null，此时仅按 protocol 判定
     */
    public static void validate(AidAiModel model, String providerCode)
    {
        validate(model, providerCode, false);
    }

    public static void validate(AidAiModel model, String providerCode, boolean adapterSupportsReferenceAudio)
    {
        if (model == null)
        {
            return;
        }
        model.setExtraBody(sanitizeRuntimeTextOptions(model.getExtraBody()));
        model.setCapabilityJson(com.aid.aid.service.support.MediaCapabilityNormalizer.normalizeJson(model.getCapabilityJson()));
        validateJsonObjectIfPresent("billing_rule_json", model.getBillingRuleJson());
        validateJsonObjectIfPresent("schedule_strategy_json", model.getScheduleStrategyJson());
        validateJsonObjectIfPresent("capability_json", model.getCapabilityJson());
        validateJsonObjectIfPresent("param_mapping_json", model.getParamMappingJson());
        validateJsonObjectIfPresent("extra_body", model.getExtraBody());
        ModelBillingRuleValidator.validate(model);
        validateMediaCapability(model.getCapabilityJson());
        validateTextCapability(model);
        validateViduModelCallback(model);
        validateConfigurableVideoAudioField(model);
        validateConfigurableVideoResolutionMapping(model);
        validateReferenceAudioCapability(model, providerCode, adapterSupportsReferenceAudio);
    }

    /** 校验可配置异步视频协议的上游音频开关字段。 */
    private static void validateConfigurableVideoAudioField(AidAiModel model)
    {
        if (!Objects.equals(ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO, model.getProtocol())
                || StrUtil.isBlank(model.getCapabilityJson()))
        {
            return;
        }
        try
        {
            JsonNode capability = OBJECT_MAPPER.readTree(model.getCapabilityJson());
            JsonNode fieldNode = capability == null
                    ? null : capability.get(ConfigurableAsyncMediaConstants.CAPABILITY_UPSTREAM_AUDIO_FIELD);
            if (fieldNode == null || fieldNode.isNull())
            {
                return;
            }
            String field = fieldNode.isTextual() ? StrUtil.trim(fieldNode.asText()) : null;
            boolean invalid = !CONFIGURABLE_VIDEO_AUDIO_FIELDS.contains(field)
                    || (Objects.equals(ConfigurableAsyncMediaConstants.UPSTREAM_AUDIO_FIELD_NONE, field)
                    && capability.path(ConfigurableAsyncMediaConstants.CAPABILITY_FORCE_GENERATE_AUDIO)
                            .asBoolean(false));
            if (invalid)
            {
                log.error("可配置异步视频音频字段非法: modelCode={}, field={}, forceGenerateAudio={}",
                        model.getModelCode(), field,
                        capability.path(ConfigurableAsyncMediaConstants.CAPABILITY_FORCE_GENERATE_AUDIO)
                                .asBoolean(false));
                throw new ServiceException(ERR_INVALID_AUDIO_FIELD);
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("可配置异步视频音频字段解析失败: modelCode={}, reason={}",
                    model.getModelCode(), e.getMessage());
            throw new ServiceException(ERR_INVALID_AUDIO_FIELD);
        }
    }

    /** 校验可配置异步视频协议的上游分辨率映射。 */
    private static void validateConfigurableVideoResolutionMapping(AidAiModel model)
    {
        if (!Objects.equals(ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO, model.getProtocol())
                || StrUtil.isBlank(model.getCapabilityJson()))
        {
            return;
        }
        try
        {
            JsonNode capability = OBJECT_MAPPER.readTree(model.getCapabilityJson());
            JsonNode fixed = capability == null
                    ? null : capability.get(ConfigurableAsyncMediaConstants.CAPABILITY_UPSTREAM_RESOLUTION);
            JsonNode mapping = capability == null
                    ? null : capability.get(ConfigurableAsyncMediaConstants.CAPABILITY_UPSTREAM_RESOLUTION_MAP);
            if (mapping == null || mapping.isNull())
            {
                return;
            }
            if (!mapping.isObject()
                    || (fixed != null && !fixed.isNull() && mapping.size() > 0))
            {
                throw new IllegalArgumentException("映射结构错误");
            }
            JsonNode sizeOptions = capability.get("sizeOptions");
            var fields = mapping.fields();
            while (fields.hasNext())
            {
                Map.Entry<String, JsonNode> entry = fields.next();
                String source = StrUtil.trim(entry.getKey());
                String target = entry.getValue().isTextual()
                        ? StrUtil.trim(entry.getValue().asText()) : null;
                if (StrUtil.isBlank(source) || StrUtil.isBlank(target)
                        || !containsIgnoreCase(sizeOptions, source))
                {
                    throw new IllegalArgumentException("映射内容错误");
                }
            }
        }
        catch (Exception e)
        {
            log.error("可配置异步视频分辨率映射非法: modelCode={}, reason={}",
                    model.getModelCode(), e.getMessage());
            throw new ServiceException("分辨率错误");
        }
    }

    private static boolean containsIgnoreCase(JsonNode values, String expected)
    {
        if (values == null || !values.isArray())
        {
            return false;
        }
        for (JsonNode value : values)
        {
            if (value.isTextual() && expected.equalsIgnoreCase(StrUtil.trim(value.asText())))
            {
                return true;
            }
        }
        return false;
    }

    /** 校验 Vidu 模型级回调地址。 */
    private static void validateViduModelCallback(AidAiModel model)
    {
        boolean viduProtocol = Objects.equals(ViduConstants.PROTOCOL_IMAGE, model.getProtocol())
                || Objects.equals(ViduConstants.PROTOCOL_VIDEO, model.getProtocol());
        if (!viduProtocol || StrUtil.isBlank(model.getScheduleStrategyJson()))
        {
            return;
        }
        String callbackUrl = null;
        try
        {
            JsonNode strategy = OBJECT_MAPPER.readTree(model.getScheduleStrategyJson());
            callbackUrl = strategy == null ? null : strategy.path("callbackBaseUrl").asText(null);
            if (StrUtil.isNotBlank(callbackUrl)
                    && !ViduCallbackSupport.isValidCallbackBaseUrl(callbackUrl))
            {
                throw new IllegalArgumentException("地址不完整");
            }
        }
        catch (Exception e)
        {
            log.error("Vidu 模型回调地址非法: modelCode={}, callbackUrl={}, reason={}",
                            model.getModelCode(), callbackUrl, e.getMessage());
            throw new ServiceException(ERR_INVALID_CALLBACK);
        }
    }

    /** 视频参考音频能力开启时，服务商、数量、时长、格式及可选的音画同出依赖必须可执行。 */
    private static void validateReferenceAudioCapability(AidAiModel model, String providerCode, boolean adapterSupportsReferenceAudio)
    {
        if (StrUtil.isBlank(model.getCapabilityJson()))
        {
            return;
        }
        JsonNode capability;
        try
        {
            capability = OBJECT_MAPPER.readTree(model.getCapabilityJson());
        }
        catch (Exception e)
        {
            // JSON 语法错误与配置项不完整是两类问题，日志必须区分，否则排查方向被带偏
            log.error("capability_json 解析失败: modelCode={}, reason={}", model.getModelCode(), e.getMessage());
            throw new ServiceException(ERR_INVALID_JSON);
        }
        if (Objects.isNull(capability) || !capability.path("supportsReferenceAudio").asBoolean(false))
        {
            return;
        }
        String reason = resolveReferenceAudioConfigError(model, providerCode, capability, adapterSupportsReferenceAudio);
        if (StrUtil.isNotBlank(reason))
        {
            log.error("视频参考音频能力配置非法: modelCode={}, reason={}", model.getModelCode(), reason);
            throw new ServiceException(ERR_INVALID_REFERENCE_AUDIO);
        }
    }

    /**
     * 逐项判定参考音频能力配置，返回可定位的具体原因。
     *
     * @param model        模型
     * @param providerCode 模型所属服务商编码，可为空
     * @param capability   已解析的能力 JSON
     * @return 不合法原因；配置合法返回 null
     */
    private static String resolveReferenceAudioConfigError(AidAiModel model, String providerCode, JsonNode capability,
                                                           boolean adapterSupportsReferenceAudio)
    {
        if (!Objects.equals("video", StrUtil.trim(model.getModelType())))
        {
            return "仅视频模型可开启参考音频";
        }
        // Provider 侧一律按 equalsIgnoreCase 匹配，此处统一转小写后比对，避免大小写差异误判为未实现
        boolean deliverable = adapterSupportsReferenceAudio
                || REFERENCE_AUDIO_PROVIDER_CODES.contains(normalizeCode(providerCode))
                || REFERENCE_AUDIO_PROTOCOLS.contains(normalizeCode(model.getProtocol()))
                || isAgnes25Model(model, providerCode)
                || isWan3DashscopeModel(model)
                || ("tokendance".equals(normalizeCode(providerCode)) && Set.of("tokendance:seedance:generations",
                    "tokendance:wan3:video-synthesis", "tokendance:minimax:video_generation_v2").contains(normalizeCode(model.getProtocol())));
        if (!deliverable)
        {
            return "服务商未实现参考音频下发: providerCode=" + providerCode + ", protocol=" + model.getProtocol();
        }
        boolean generatedAudioRequired = !capability.has(ReferenceAudioLimiter.KEY_REQUIRES_GENERATED_AUDIO)
                || capability.path(ReferenceAudioLimiter.KEY_REQUIRES_GENERATED_AUDIO).asBoolean(true);
        if (generatedAudioRequired && !capability.path("supportsAudio").asBoolean(false))
        {
            return "参考音频依赖音画同出,需先开启 supportsAudio";
        }
        int maxReferenceAudios = capability.path("maxReferenceAudios").asInt(0);
        if (maxReferenceAudios == 0 || maxReferenceAudios < -1)
        {
            return "maxReferenceAudios 必须为-1或正整数";
        }
        BigDecimal minSeconds = referenceAudioSeconds(capability, "referenceAudioMinDurationSeconds");
        BigDecimal maxSeconds = referenceAudioSeconds(capability, "referenceAudioMaxDurationSeconds");
        BigDecimal maxTotalSeconds = referenceAudioSeconds(capability, "referenceAudioMaxTotalDurationSeconds");
        if (minSeconds.signum() < 0 || maxSeconds.signum() < 0 || maxTotalSeconds.signum() < 0
                || (minSeconds.signum() > 0 && maxSeconds.signum() > 0 && maxSeconds.compareTo(minSeconds) < 0)
                || (minSeconds.signum() > 0 && maxTotalSeconds.signum() > 0 && maxTotalSeconds.compareTo(minSeconds) < 0))
        {
            return "时长区间非法: min=" + minSeconds + ", max=" + maxSeconds + ", total=" + maxTotalSeconds;
        }
        List<String> formats = new ArrayList<>();
        JsonNode formatNode = capability.path("referenceAudioFormats");
        if (formatNode.isArray())
        {
            formatNode.forEach(item -> {
                if (item.isTextual() && StrUtil.isNotBlank(item.asText()))
                {
                    formats.add(item.asText());
                }
            });
        }
        if (formats.isEmpty())
        {
            return "referenceAudioFormats 不能为空";
        }
        if (formats.contains("*") && formats.size() > 1)
        {
            return "通配格式必须单独配置";
        }
        // COS 原生媒体处理使用 ffprobe 校验已登记输入，可解析 FLAC/AMR；
        // 通用参考音频上传仍使用 ReferenceAudioLimiter 的较窄格式集合。
        boolean tencentCiMedia = "tencent_ci_media".equals(normalizeCode(providerCode))
                && "tencent-ci-async-media".equals(normalizeCode(model.getProtocol()));
        List<String> unsupported = formats.stream()
                .filter(format -> !"*".equals(format))
                .filter(format -> !ReferenceAudioLimiter.isProbeableFormat(format)
                        && !(tencentCiMedia && Set.of("flac", "amr").contains(normalizeCode(format))))
                .toList();
        if (!unsupported.isEmpty())
        {
            return "格式无法解析时长: " + unsupported + ", 可选=" + ReferenceAudioLimiter.probeableFormats();
        }
        return null;
    }

    private static BigDecimal referenceAudioSeconds(JsonNode capability, String key) {
        JsonNode value = capability.get(key);
        if (value == null || value.isNull()) return BigDecimal.ZERO;
        if (!value.isNumber()) throw new ServiceException("音频时长配置无效");
        return value.decimalValue();
    }

    /** Agnes 仅 Video 2.5 两款请求构造器实现结构化参考音频，不能按整个供应商放行。 */
    private static boolean isAgnes25Model(AidAiModel model, String providerCode)
    {
        boolean routedToAgnes = AgnesConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode))
                || AgnesConstants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(model.getProtocol()));
        if (!routedToAgnes)
        {
            return false;
        }
        String upstream = StrUtil.blankToDefault(model.getRealModelCode(), model.getModelCode());
        String normalized = normalizeCode(upstream);
        return "agnes-video-2.5".equals(normalized) || "agnes-video-2.5-flash".equals(normalized);
    }

    private static boolean isWan3DashscopeModel(AidAiModel model)
    {
        if (model == null || !DashscopeConstants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(model.getProtocol())))
        {
            return false;
        }
        String upstream = StrUtil.blankToDefault(model.getRealModelCode(), model.getModelCode());
        String normalized = normalizeCode(upstream);
        return DashscopeConstants.MODEL_WAN3.equals(normalized)
                || DashscopeConstants.MODEL_WAN3_PRIME.equals(normalized);
    }

    /**
     * 归一化服务商编码 / 协议标识，便于与白名单做大小写无关比对。
     *
     * @param code 原始值，可为空
     * @return 去空白并转小写后的值；空值返回空串
     */
    private static String normalizeCode(String code)
    {
        return StrUtil.trimToEmpty(code).toLowerCase(Locale.ROOT);
    }

    /**
     * 单字段校验：空值放行；非空必须以 {@code {} 包裹（顶层强制 JSON 对象，杜绝裸 UUID / 数组 /
     * 字符串误填），并能成功 parse。
     */
    private static void validateJsonObjectIfPresent(String fieldName, String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return;
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}"))
        {
            throw structuredError(fieldName, "顶层非 JSON 对象", "length=" + trimmed.length());
        }
        try
        {
            OBJECT_MAPPER.readTree(trimmed);
        }
        catch (Exception e)
        {
            throw structuredError(fieldName, e.getClass().getSimpleName(), "length=" + trimmed.length());
        }
    }

    /** 校验已定义的媒体硬约束，同时保留未知扩展字段。 */
    private static void validateMediaCapability(String raw) {
        if (StrUtil.isBlank(raw)) return;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            if (!root.isObject()) throw new IllegalArgumentException("根节点必须是对象");
            validateMediaCapabilityObject(root, "capabilityJson");
            JsonNode scenes = root.path("sceneRules");
            if (!scenes.isMissingNode() && !scenes.isNull() && !scenes.isObject()) throw new IllegalArgumentException("sceneRules 必须是对象");
            if (scenes.isObject()) {
                var fields = scenes.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    JsonNode scene = field.getValue();
                    String path = "sceneRules." + field.getKey();
                    if (scene.isNull()) continue;
                    if (!scene.isObject()) throw new IllegalArgumentException(path + " 必须是对象");
                    validateMediaCapabilityObject(scene, path);
                }
            }
        } catch (IllegalArgumentException ex) {
            log.info("媒体能力结构无效: {}", ex.getMessage());
            throw new ServiceException("媒体能力配置无效：" + ex.getMessage());
        } catch (Exception ex) {
            log.info("媒体能力结构无效: {}", ex.getClass().getSimpleName());
            throw new ServiceException("媒体能力配置无效：JSON格式错误");
        }
    }

    private static void validateMediaCapabilityObject(JsonNode capability, String path) {
        for (String prefix : List.of("referenceImage", "referenceVideo", "referenceAudio")) {
            for (String suffix : List.of("MinDurationSeconds", "MaxDurationSeconds", "MaxTotalDurationSeconds", "MaxFileSizeMb",
                    "MinDimensionPixels", "MaxDimensionPixels", "MinPixels", "MaxPixels", "MinWidth", "MaxWidth",
                    "MinHeight", "MaxHeight", "MinAspectRatio", "MaxAspectRatio", "MinFps", "MaxFps")) {
                String field = prefix + suffix;
                JsonNode value = capability.get(field);
                // null 是配置合并协议的删除标记，编辑器留空/关闭输入能力时会显式发送。
                if (value != null && !value.isNull() && (!value.isNumber() || value.decimalValue().signum() < 0)) {
                    throw new IllegalArgumentException(path + "." + field + " 必须是非负数字");
                }
            }
            for (String suffix : List.of("DurationSeconds", "DimensionPixels", "Pixels", "Width", "Height", "AspectRatio", "Fps")) {
                JsonNode min = capability.get(prefix + "Min" + suffix);
                JsonNode max = capability.get(prefix + "Max" + suffix);
                if (min != null && !min.isNull() && max != null && !max.isNull() && max.decimalValue().signum() > 0
                        && min.decimalValue().compareTo(max.decimalValue()) > 0)
                    throw new IllegalArgumentException(path + "." + prefix + "Min" + suffix + " 不能大于最大值");
            }
            JsonNode formats = capability.get(prefix + "Formats");
            if (formats != null && !formats.isNull()) {
                if (!formats.isArray()) throw new IllegalArgumentException(path + "." + prefix + "Formats 必须是数组");
                for (JsonNode format : formats) {
                    if (!format.isTextual() || format.asText().isBlank()
                            || "*".equals(format.asText()) && formats.size() != 1)
                        throw new IllegalArgumentException(path + "." + prefix + "Formats 包含无效格式");
                }
            }
        }
        for (String field : List.of("maxInputMediaTotalFileSizeMb", "maxInputOutputVideoDurationSeconds")) {
            JsonNode value = capability.get(field);
            if (value != null && !value.isNull() && (!value.isNumber() || value.decimalValue().signum() < 0))
                throw new IllegalArgumentException(path + "." + field + " 必须是非负数字");
        }
    }

    /** 校验文本多模态和统一思考能力字段。 */
    private static void validateTextCapability(AidAiModel model)
    {
        if (!Objects.equals("text", StrUtil.trim(model.getModelType()))
                || StrUtil.isBlank(model.getCapabilityJson()))
        {
            return;
        }
        try
        {
            JsonNode capability = OBJECT_MAPPER.readTree(model.getCapabilityJson());
            Set<String> supportedModalities = Set.of("TEXT", "IMAGE", "VIDEO", "AUDIO", "DOCUMENT");
            JsonNode modalities = capability.path("inputModalities");
            if (modalities.isArray())
            {
                for (JsonNode modality : modalities)
                {
                    if (!modality.isTextual()
                            || !supportedModalities.contains(modality.asText().trim().toUpperCase(Locale.ROOT)))
                    {
                        throw new IllegalArgumentException("输入模态非法");
                    }
                }
            }
            JsonNode outputModalities = capability.path("outputModalities");
            if (outputModalities.isArray())
            {
                for (JsonNode modality : outputModalities)
                {
                    if (!modality.isTextual()
                            || !supportedModalities.contains(modality.asText().trim().toUpperCase(Locale.ROOT)))
                    {
                        throw new IllegalArgumentException("输出模态非法");
                    }
                }
            }
            for (String field : List.of("maxInputImages", "maxInputVideos",
                    "maxInputAudios", "maxInputDocuments"))
            {
                JsonNode value = capability.get(field);
                if (value != null && (!value.isIntegralNumber() || value.asInt() < -1))
                {
                    throw new IllegalArgumentException(field + "非法");
                }
            }
            validateTextModalityCount(capability, "IMAGE", "maxInputImages");
            validateTextModalityCount(capability, "VIDEO", "maxInputVideos");
            validateTextModalityCount(capability, "AUDIO", "maxInputAudios");
            validateTextModalityCount(capability, "DOCUMENT", "maxInputDocuments");
            for (String field : List.of("maxInputImageFileSizeMb", "maxInputVideoFileSizeMb",
                    "maxInputAudioFileSizeMb", "maxInputDocumentFileSizeMb",
                    "maxInputVideoDurationSeconds", "maxInputAudioDurationSeconds",
                    "maxInputVideoTotalDurationSeconds", "maxInputAudioTotalDurationSeconds",
                    "maxInputMediaTotalFileSizeMb"))
            {
                JsonNode value = capability.get(field);
                if (value != null && (!value.isNumber() || value.decimalValue().signum() < 0))
                {
                    throw new IllegalArgumentException(field + "非法");
                }
            }
            for (String field : List.of("maxInputDocumentPages", "contextWindowTokens", "maxOutputTokens",
                    "defaultReasoningBudgetTokens", "maxReasoningBudgetTokens"))
            {
                JsonNode value = capability.get(field);
                if (value != null && (!value.isIntegralNumber() || value.asLong() < 0L))
                {
                    throw new IllegalArgumentException(field + "非法");
                }
            }
            Set<String> levels = new java.util.LinkedHashSet<>();
            Set<String> supportedLevels = Objects.equals("deepseek:chat-completions", model.getProtocol())
                    ? Set.of("minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                    : Set.of("minimal", "low", "medium", "high", "xhigh", "max");
            JsonNode allowed = capability.path("allowedReasoningLevels");
            if (allowed.isArray())
            {
                allowed.forEach(value -> {
                    if (value.isTextual() && StrUtil.isNotBlank(value.asText()))
                    {
                        String level = value.asText().trim().toLowerCase(Locale.ROOT);
                        if (!supportedLevels.contains(level))
                        {
                            throw new IllegalArgumentException("思考档位非法");
                        }
                        levels.add(level);
                    }
                });
            }
            String defaultLevel = capability.path("defaultReasoningLevel").asText(null);
            if (StrUtil.isNotBlank(defaultLevel)
                    && !levels.isEmpty() && !levels.contains(defaultLevel.trim().toLowerCase(Locale.ROOT)))
            {
                throw new IllegalArgumentException("默认档位非法");
            }
            boolean supportsReasoning = capability.path("supportsReasoning").asBoolean(false);
            if (!supportsReasoning && (capability.path("supportsReasoningBudget").asBoolean(false)
                    || capability.path("supportsReasoningContent").asBoolean(false)
                    || capability.path("returnsReasoningContent").asBoolean(false)
                    || StrUtil.isNotBlank(defaultLevel) || !levels.isEmpty()))
            {
                throw new IllegalArgumentException("思考能力冲突");
            }
            int defaultBudget = capability.path("defaultReasoningBudgetTokens").asInt(0);
            int maxBudget = capability.path("maxReasoningBudgetTokens").asInt(0);
            if ((!capability.path("supportsReasoningBudget").asBoolean(false) && defaultBudget > 0)
                    || (maxBudget > 0 && defaultBudget > maxBudget))
            {
                throw new IllegalArgumentException("思考预算冲突");
            }
        }
        catch (Exception e)
        {
            log.error("文本模型能力配置非法: modelCode={}, reason={}", model.getModelCode(), e.getMessage());
            throw new ServiceException("能力配置错误");
        }
    }

    private static void validateTextModalityCount(JsonNode capability, String modality, String countField)
    {
        boolean declared = containsIgnoreCase(capability.path("inputModalities"), modality)
                || capability.path("supports" + modality.substring(0, 1)
                        + modality.substring(1).toLowerCase(Locale.ROOT) + "Input").asBoolean(false);
        JsonNode value = capability.get(countField);
        // 未声明上限与显式禁止是不同状态；官方未公开数量限制的目录不能伪造为零。
        // 非法类型已由上面的字段校验拒绝，显式零值仍与已开启的输入模态冲突。
        if (value == null)
        {
            return;
        }
        int count = value.asInt();
        if (declared && count == 0)
        {
            throw new IllegalArgumentException(countField + "未配置");
        }
        if (!declared && count != 0)
        {
            throw new IllegalArgumentException(countField + "能力冲突");
        }
    }

    /** 删除只能由单次调用方决定的文本运行参数，避免后台再次把策略写死到模型配置。 */
    private static String sanitizeRuntimeTextOptions(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return raw;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(raw);
            if (root == null || !root.isObject())
            {
                return raw;
            }
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) root;
            for (String key : List.of("stream", "stream_options", "enable_thinking", "thinking",
                    "thinking_budget", "reasoning_effort", "thinking_level", "thinkingConfig"))
            {
                object.remove(key);
            }
            JsonNode template = object.get("chat_template_kwargs");
            if (template != null && template.isObject())
            {
                ((com.fasterxml.jackson.databind.node.ObjectNode) template).remove("enable_thinking");
                if (template.isEmpty())
                {
                    object.remove("chat_template_kwargs");
                }
            }
            return object.isEmpty() ? null : OBJECT_MAPPER.writeValueAsString(object);
        }
        catch (Exception ignored)
        {
            return raw;
        }
    }

    private static ServiceException structuredError(String fieldName, String reason, String preview)
    {
        // 控制台留详细原因供开发排查；用户侧只看到 6 字内文案
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("field", fieldName);
        ctx.put("reason", reason);
        ctx.put("preview", preview);
        log.error("AI 配置 JSON 字段非法: {}", ctx);
        return new ServiceException(ERR_INVALID_JSON);
    }
}
