package com.aid.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidUserAiConfig;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidUserAiConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.common.satoken.utils.LoginHelper;
import com.aid.common.utils.StringUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.KlingConstants;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.provider.KlingCallbackSignatureUtil;
import com.aid.service.IAiModelConfigService;
import com.aid.upgrade.gateway.OfficialGatewayConfig;
import com.aid.upgrade.gateway.OfficialGatewayConfigProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI模型配置聚合Service实现。
 */
@Slf4j
@RequiredArgsConstructor
@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class AiModelConfigServiceImpl implements IAiModelConfigService {

    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;
    private final IAidUserAiConfigService aidUserAiConfigService;
    private final OfficialGatewayConfigProvider officialGatewayConfigProvider;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.tokendance.credential.TokenDanceCredentialStore tokenDanceCredentialStore;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelDefinitionService modelDefinitions;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelInvocationResolver invocationResolver;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelBusinessBindingService modelBusinessBindings;

    private static final String STATUS_NORMAL = "0";
    private static final String DEL_FLAG_NORMAL = "0";

    @Override
    public AiModelConfigVo selectByModelCode(String modelCode) {
        com.aid.aid.domain.AidAiModelAlias alias = modelDefinitions.alias(modelCode);
        if (alias != null) return resolveAlias(alias, getCurrentUserIdSafe());
        AidAiModel model = aidAiModelService.getOne(
            Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelCode, modelCode)
                .eq(AidAiModel::getStatus, STATUS_NORMAL)
                .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                .last("limit 1"),
            false
        );
        return buildConfigVo(model);
    }

    @Override
    public AiModelConfigVo selectByModelCodeForUser(String modelCode, Long userId) {
        com.aid.aid.domain.AidAiModelAlias alias = modelDefinitions.alias(modelCode);
        if (alias != null) return resolveAlias(alias, userId);
        AidAiModel model = aidAiModelService.getOne(
            Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelCode, modelCode)
                .eq(AidAiModel::getStatus, STATUS_NORMAL)
                .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                .last("limit 1"),
            false
        );
        return buildConfigVo(model, userId);
    }

    @Override
    public AiModelConfigVo selectByCategoryWithHighestPriority(String category) {
        AidAiModel model = aidAiModelService.getOne(
            Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelType, category)
                .eq(AidAiModel::getStatus, STATUS_NORMAL)
                .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                .orderByDesc(AidAiModel::getPriority)
                .last("limit 1"),
            false
        );
        return buildConfigVo(model);
    }

    @Override
    public AiModelConfigVo selectFallbackByCategoryAndLessPriority(String category, Integer currentPriority) {
        AidAiModel model = aidAiModelService.getOne(
            Wrappers.<AidAiModel>lambdaQuery()
                .eq(AidAiModel::getModelType, category)
                .eq(AidAiModel::getStatus, STATUS_NORMAL)
                .eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL)
                .lt(AidAiModel::getPriority, currentPriority)
                .orderByDesc(AidAiModel::getPriority)
                .last("limit 1"),
            false
        );
        return buildConfigVo(model);
    }

    @Override
    public AiModelConfigVo selectByModelId(Long modelId) {
        com.aid.aid.domain.AidAiModelAlias alias = modelDefinitions.alias(modelId);
        if (alias != null) return resolveAlias(alias, getCurrentUserIdSafe());
        AidAiModel model = aidAiModelService.getById(modelId);
        if (model == null || !STATUS_NORMAL.equals(model.getStatus()) || !DEL_FLAG_NORMAL.equals(model.getDelFlag())) {
            return null;
        }
        return buildConfigVo(model);
    }

    /**
     * 核心组装逻辑：模型 + 服务商 + 用户覆盖 → 最终生效配置
     */
    private AiModelConfigVo buildConfigVo(AidAiModel model) {
        return buildConfigVo(model, getCurrentUserIdSafe());
    }

    /** 按指定用户组装模型、供应商及用户覆盖配置。 */
    private AiModelConfigVo buildConfigVo(AidAiModel model, Long userId) {
        return buildConfigVo(model, userId, false);
    }

    @Override
    public AiModelConfigVo selectTaskCredentials(Long modelId, String modelCode, Long userId) {
        AidAiModel model = aidAiModelService.getById(modelId);
        if (model == null || !java.util.Objects.equals(model.getModelCode(), modelCode)) return null;
        return buildConfigVo(model, userId, true);
    }

    private AiModelConfigVo buildConfigVo(AidAiModel model, Long userId, boolean existingTask) {
        if (model == null) {
            return null;
        }

        AidAiProvider provider = aidAiProviderService.getById(model.getProviderId());
        if (provider == null) {
            log.error("模型对应的服务商不存在, modelId={}, providerId={}", model.getId(), model.getProviderId());
            throw new ServiceException("模型配置异常");
        }
        if (!existingTask && !STATUS_NORMAL.equals(provider.getStatus())) {
            log.error("模型对应的服务商已停用, providerId={}", provider.getId());
            throw new ServiceException("模型已停用");
        }

        String effectiveBaseUrl = provider.getBaseUrl();
        String effectiveApiKey = provider.getApiKey();
        String effectiveApiSecret = provider.getApiSecret();
        boolean tokenDance = "tokendance".equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()));
        Integer credentialVersion = null;
        if (tokenDance) {
            com.aid.tokendance.credential.ResolvedTokenDanceCredential credential =
                    tokenDanceCredentialStore.requireActive(provider.getId());
            effectiveApiKey = credential.getApiKey();
            credentialVersion = credential.getCredentialVersion();
            effectiveApiSecret = null;
        }

        // 官方统一网关开启后，全局厂商出站改走官方地址与官方密钥（协议仍遵循原厂商）；
        // 例外模型或例外厂商（官方网关暂不支持的）仍走自有厂商网关
        OfficialGatewayConfig officialGateway = officialGatewayConfigProvider.getConfig();
        if (officialGateway.isEnabled() && StrUtil.isNotBlank(officialGateway.getBaseUrl())
                && !officialGateway.isExcluded(model.getId(), provider.getId())
                && supportsOfficialGateway(model, provider)) {
            effectiveBaseUrl = officialGateway.resolveBaseUrl(provider.getProviderCode());
            if (StrUtil.isNotBlank(officialGateway.getApiKey())) {
                effectiveApiKey = officialGateway.getApiKey();
            }
        }

        if (userId != null && !tokenDance) {
            AidUserAiConfig userConfig = aidUserAiConfigService.getOne(
                Wrappers.<AidUserAiConfig>lambdaQuery()
                    .eq(AidUserAiConfig::getUserId, userId)
                    .eq(AidUserAiConfig::getProviderId, provider.getId())
                    .eq(AidUserAiConfig::getDelFlag, DEL_FLAG_NORMAL)
                    .last("limit 1"),
                false
            );
            if (userConfig != null && STATUS_NORMAL.equals(userConfig.getIsEnable())) {
                if (StringUtils.isNotEmpty(userConfig.getCustomBaseUrl())) {
                    effectiveBaseUrl = userConfig.getCustomBaseUrl();
                }
                boolean customApiKeyEnabled = StrUtil.isNotBlank(userConfig.getCustomApiKey());
                if (customApiKeyEnabled) {
                    effectiveApiKey = userConfig.getCustomApiKey();
                }
                if (isKlingProvider(provider) && customApiKeyEnabled) {
                    // Kling Webhook Secret 归属于 API Key 对应账号，BYOK 不得继承平台账号 Secret。
                    effectiveApiSecret = KlingCallbackSignatureUtil.hasValidSecret(userConfig.getCustomApiSecret())
                        ? userConfig.getCustomApiSecret() : null;
                } else if (!isKlingProvider(provider)
                    && StringUtils.isNotEmpty(userConfig.getCustomApiSecret())) {
                    effectiveApiSecret = userConfig.getCustomApiSecret();
                }
            }
        }

        AiModelConfigVo vo = new AiModelConfigVo();
        // 模型字段
        vo.setId(model.getId());
        vo.setConfigVersion(model.getConfigVersion());
        vo.setProviderId(model.getProviderId());
        vo.setCredentialVersion(credentialVersion);
        vo.setModelCode(model.getModelCode());
        // 真实上游模型名：优先 real_model_code，为空回退 model_code
        String effectiveRealModelCode = StrUtil.isNotBlank(model.getRealModelCode())
                ? model.getRealModelCode() : model.getModelCode();
        vo.setRealModelCode(effectiveRealModelCode);
        vo.setModelName(model.getModelName());
        vo.setModelType(model.getModelType());
        vo.setGenerateMode(model.getGenerateMode());
        vo.setCostCredits(model.getCostCredits());
        // 模型级计费倍率（默认 1.00，避免老数据 NULL 导致下游 NPE）
        vo.setBillingMultiplier(model.getBillingMultiplier());
        vo.setApiVersion(model.getApiVersion());
        vo.setApiSuffix(model.getApiSuffix());
        vo.setProtocol(model.getProtocol());
        vo.setPriority(model.getPriority());
        vo.setImageRefine(model.getImageRefine());
        // 计费扩展字段
        vo.setBillingMode(model.getBillingMode());
        vo.setBillingRuleJson(model.getBillingRuleJson());
        vo.setBillingVersion(model.getBillingVersion());
        vo.setIsFree(Boolean.TRUE.equals(model.getIsFree()));
        vo.setImageUrlProxyEnabled(Boolean.TRUE.equals(model.getImageUrlProxyEnabled()));
        vo.setImageUrlProxyTemplate(model.getImageUrlProxyTemplate());
        // 服务商字段（已处理用户覆盖）
        vo.setBaseUrl(effectiveBaseUrl);
        vo.setApiKey(effectiveApiKey);
        vo.setApiSecret(effectiveApiSecret);
        vo.setTaskQuerySuffix(provider.getTaskQuerySuffix());
        vo.setProviderCode(provider.getProviderCode());
        vo.setProviderName(provider.getProviderName());
        // 调度策略字段
        vo.setScheduleStrategyJson(model.getScheduleStrategyJson());
        vo.setSupportsCallback(provider.getSupportsCallback());
        vo.setProviderScheduleStrategyJson(provider.getScheduleStrategyJson());
        // 模型能力扩展字段（图片/视频统一，供应给 provider/编排层）
        vo.setSupportsTextInput(model.getSupportsTextInput());
        vo.setSupportsSystemPrompt(model.getSupportsSystemPrompt());
        vo.setSupportsImageInput(model.getSupportsImageInput());
        vo.setSupportsMultiImageInput(model.getSupportsMultiImageInput());
        vo.setMaxOutputCount(model.getMaxOutputCount());
        vo.setDefaultOutputCount(model.getDefaultOutputCount());
        vo.setSupportsAspectRatio(model.getSupportsAspectRatio());
        vo.setSupportsSizePreset(model.getSupportsSizePreset());
        vo.setSupportsDuration(model.getSupportsDuration());
        vo.setSupportsFirstFrame(model.getSupportsFirstFrame());
        vo.setSupportsLastFrame(model.getSupportsLastFrame());
        vo.setDefaultSizeCode(model.getDefaultSizeCode());
        vo.setDefaultAspectRatio(model.getDefaultAspectRatio());
        vo.setDefaultDurationSeconds(model.getDefaultDurationSeconds());
        vo.setCapabilityJson(model.getCapabilityJson());
        vo.setParamMappingJson(model.getParamMappingJson());

        // LLM Provider 鉴权与请求扩展配置（厂商级）
        vo.setAuthHeader(provider.getAuthHeader());
        vo.setAuthPrefix(provider.getAuthPrefix());
        vo.setExtraHeadersJson(provider.getExtraHeaders());
        vo.setExtraBodyJson(provider.getExtraBody());
        vo.setExtraQueryJson(provider.getExtraQuery());
        // 模型级 extra_body（合并时覆盖厂商级同名 key）
        vo.setModelExtraBodyJson(model.getExtraBody());

        return existingTask ? vo : invocationResolver.select(vo, null);
    }

    @Override
    public AiModelConfigVo selectByModelCode(String modelCode, String capabilityCode) {
        return invocationResolver.select(selectByModelCode(modelCode), capabilityCode);
    }

    @Override
    public AiModelConfigVo selectByModelId(Long modelId, String capabilityCode) {
        return invocationResolver.select(selectByModelId(modelId), capabilityCode);
    }

    @Override
    public AiModelConfigVo selectForBusiness(String modelCode, String funcCode, String capabilityCode) {
        AiModelConfigVo config = selectByModelCode(modelCode);
        if (config == null) return null;
        String selected = modelBusinessBindings.capability(config.getId(), funcCode, capabilityCode);
        config.setBusinessFuncCode(funcCode);
        config.setBindingCode(null);
        invocationResolver.select(config, selected);
        config.setBusinessDefaultsJson(null);
        modelBusinessBindings.forFunction(funcCode).stream()
                .filter(b -> java.util.Objects.equals(b.getModelId(), config.getId())
                        && java.util.Objects.equals(b.getCapabilityCode(), config.getCapabilityCode()))
                .findFirst().ifPresent(b -> config.setBusinessDefaultsJson(b.getDefaultsJson()));
        if (config.getResolvedDefinition() != null) {
            var definition = com.aid.model.definition.ModelSchemaPresentation.withBusinessDefaults(
                    config.getResolvedDefinition(), config.getBusinessDefaultsJson());
            var route = definition.getBindings().stream()
                    .filter(binding -> java.util.Objects.equals(binding.getCode(), config.getBindingCode()))
                    .findFirst().orElse(null);
            com.aid.model.definition.ModelInvocationResolver.applyResolvedPresentation(config, definition, route);
        }
        return config;
    }

    private AiModelConfigVo resolveAlias(com.aid.aid.domain.AidAiModelAlias alias, Long userId) {
        AidAiModel model = aidAiModelService.getById(alias.getModelId());
        if (model == null || !STATUS_NORMAL.equals(model.getStatus()) || !DEL_FLAG_NORMAL.equals(model.getDelFlag())) return null;
        return invocationResolver.select(buildConfigVo(model, userId), alias.getCapabilityCode(), alias.getBindingCode());
    }

    /** 可灵使用运营方或用户显式配置的上游地址，统一网关未声明兼容前不得透明改写。 */
    private boolean supportsOfficialGateway(AidAiModel model, AidAiProvider provider) {
        return !isKlingProvider(provider)
            && !"tokendance".equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()))
            && !MinimaxH3Constants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(model.getProtocol()));
    }

    private boolean isKlingProvider(AidAiProvider provider) {
        return KlingConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(provider.getProviderCode()));
    }

    private Long getCurrentUserIdSafe() {
        if (!LoginHelper.isLogin()) {
            return null;
        }
        return LoginHelper.getUserId();
    }
}
