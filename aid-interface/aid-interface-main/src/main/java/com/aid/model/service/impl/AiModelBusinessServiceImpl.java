package com.aid.model.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.service.IAidAiModelFuncConfigService;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.support.ModelInputRequirementResolver;
import com.aid.billing.service.IBillingDetailQueryService;
import com.aid.enums.CreationModeEnum;
import com.aid.enums.ProjectTypeEnum;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelFuncGroupVO;
import com.aid.model.vo.AiModelVO;
import com.aid.model.vo.CapabilityVO;
import com.aid.model.util.BuiltInBrandIcons;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.util.ModelCapabilityResolver;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aid.model.dto.AiModelListRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * C端AI模型业务Service实现
 * 查询可用模型列表，聚合模型信息与供应商名称。
 * 仅返回模型状态正常、供应商状态正常的记录。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AiModelBusinessServiceImpl implements IAiModelBusinessService
{
    /** 状态正常 */
    private static final String STATUS_NORMAL = "0";
    /** 删除标志正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 多参出片通用池 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO = "main_storyboard_video";
    /** 多参出片专业版专属池 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO = "main_storyboard_video_multi_pro";

    /** 模型类型常量 */
    private static final String MODEL_TYPE_TEXT = "text";
    private static final String MODEL_TYPE_IMAGE = "image";
    private static final String MODEL_TYPE_VIDEO = "video";

    private final IAidAiModelService aiModelService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelDefinitionService modelDefinitions;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelBusinessBindingService modelBusinessBindings;
    private final IAidAiProviderService aiProviderService;
    /** 按功能编码查模型时需要读功能配置 */
    private final IAidAiModelFuncConfigService aiModelFuncConfigService;
    /** 组装模型计费明细（SKU 档位 / 价格折算口径与计费详情接口一致） */
    private final IBillingDetailQueryService billingDetailQueryService;
    private final IAidComicProjectService aidComicProjectService;
    private final IAidComicEpisodeService aidComicEpisodeService;

    public AiModelBusinessServiceImpl(IAidAiModelService aiModelService,
                                      IAidAiProviderService aiProviderService,
                                      IAidAiModelFuncConfigService aiModelFuncConfigService,
                                      IBillingDetailQueryService billingDetailQueryService,
                                      IAidComicProjectService aidComicProjectService,
                                      IAidComicEpisodeService aidComicEpisodeService)
    {
        this.aiModelService = aiModelService;
        this.aiProviderService = aiProviderService;
        this.aiModelFuncConfigService = aiModelFuncConfigService;
        this.billingDetailQueryService = billingDetailQueryService;
        this.aidComicProjectService = aidComicProjectService;
        this.aidComicEpisodeService = aidComicEpisodeService;
    }

    @Override
    public List<AiModelVO> listAvailableModels(String modelType)
    {
        // 字符串入参重载：只按 modelType 大类过滤
        AiModelListRequest req = new AiModelListRequest();
        req.setModelType(modelType);
        return listAvailableModels(req);
    }

    @Override
    public List<AiModelVO> listAvailableModels(AiModelListRequest request)
    {
        String modelType = Objects.isNull(request) ? null : request.getModelType();
        String generateMode = Objects.isNull(request) ? null : request.getGenerateMode();
        // 注意：校验性查询，只查必要字段
        LambdaQueryWrapper<AidAiProvider> providerWrapper = Wrappers.lambdaQuery();
        providerWrapper.select(AidAiProvider::getId, AidAiProvider::getProviderCode, AidAiProvider::getProviderName, AidAiProvider::getLogoUrl);
        providerWrapper.eq(AidAiProvider::getStatus, STATUS_NORMAL);
        providerWrapper.eq(AidAiProvider::getDelFlag, DEL_FLAG_NORMAL);
        List<AidAiProvider> providers = aiProviderService.list(providerWrapper);

        // 可用供应商ID集合
        Set<Long> availableProviderIds = providers.stream()
                .map(AidAiProvider::getId)
                .collect(Collectors.toSet());
        // 供应商ID→名称映射
        Map<Long, String> providerNameMap = providers.stream()
                .collect(Collectors.toMap(AidAiProvider::getId, AidAiProvider::getProviderName));
        // 供应商ID→LOGO映射（logo 可能为空，过滤后再收集，避免 toMap 空值 NPE）
        Map<Long, String> providerLogoMap = providers.stream()
                .filter(p -> BuiltInBrandIcons.resolve(p.getProviderCode(), p.getLogoUrl()) != null)
                .collect(Collectors.toMap(AidAiProvider::getId,
                        p -> BuiltInBrandIcons.resolve(p.getProviderCode(), p.getLogoUrl())));

        if (CollectionUtil.isEmpty(availableProviderIds))
        {
            log.info("无可用供应商");
            return new ArrayList<>();
        }

        // 注意：校验性查询，只查必要字段
        LambdaQueryWrapper<AidAiModel> modelWrapper = Wrappers.lambdaQuery();
        // 注意：新增字段时务必同步追加到 select，否则前端拿不到能力配置
        modelWrapper.select(AidAiModel::getId, AidAiModel::getModelCode,
                AidAiModel::getModelName, AidAiModel::getLogoUrl, AidAiModel::getModelType,
                AidAiModel::getGenerateMode,
                AidAiModel::getCostCredits, AidAiModel::getPriority,
                AidAiModel::getProviderId, AidAiModel::getImageRefine,
                // 模型能力扩展字段（图片/视频统一，前端动态渲染参数面板使用）
                AidAiModel::getSupportsTextInput, AidAiModel::getSupportsImageInput,
                AidAiModel::getSupportsMultiImageInput, AidAiModel::getMaxOutputCount,
                AidAiModel::getDefaultOutputCount, AidAiModel::getSupportsAspectRatio,
                AidAiModel::getSupportsSizePreset, AidAiModel::getSupportsDuration,
                AidAiModel::getSupportsFirstFrame, AidAiModel::getSupportsLastFrame,
                AidAiModel::getDefaultSizeCode, AidAiModel::getDefaultAspectRatio,
                AidAiModel::getDefaultDurationSeconds, AidAiModel::getCapabilityJson,
                // 计费明细组装所需字段（billing 出参：SKU 档位 / 倍率 / 备注）
                AidAiModel::getBillingMode, AidAiModel::getBillingRuleJson,
                AidAiModel::getBillingMultiplier, AidAiModel::getIsFree, AidAiModel::getRemark);
        modelWrapper.eq(AidAiModel::getStatus, STATUS_NORMAL);
        modelWrapper.eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL);
        // 按模型类型筛选
        if (StrUtil.isNotBlank(modelType))
        {
            modelWrapper.eq(AidAiModel::getModelType, modelType);
        }
        // 按生成模式细分筛选（对 modelType 大类做进一步过滤）
        modelWrapper.in(AidAiModel::getProviderId, availableProviderIds);
        // 按优先级降序排列
        modelWrapper.orderByDesc(AidAiModel::getPriority);
        List<AidAiModel> models = aiModelService.list(modelWrapper);
        modelDefinitions.preload(models);
        models = models.stream().map(model -> modelDefinitions.project(model, generateMode)).filter(Objects::nonNull).toList();

        if (CollectionUtil.isEmpty(models))
        {
            log.info("无可用模型, modelType={}, generateMode={}", modelType, generateMode);
            return new ArrayList<>();
        }

        // 全局价格折算系数：一次请求只查一次配置，循环内复用
        BigDecimal globalFactor = billingDetailQueryService.readGlobalPriceFactor();
        List<AiModelVO> result = new ArrayList<>();
        for (AidAiModel model : models)
        {
            result.add(buildModelVo(model,
                    providerNameMap.get(model.getProviderId()),
                    providerLogoMap.get(model.getProviderId()),
                    globalFactor));
        }

        log.info("查询可用模型列表: modelType={}, generateMode={}, 返回{}条", modelType, generateMode, result.size());
        return result;
    }

    @Override
    public List<AiModelVO> listAvailableModelsByFuncCode(String funcCode)
    {
        if (StrUtil.isBlank(funcCode))
        {
            log.info("按功能编码查模型失败：funcCode 为空");
            return new ArrayList<>();
        }

        AidAiModelFuncConfig funcConfig = queryEnabledFuncConfig(funcCode);
        if (Objects.isNull(funcConfig))
        {
            log.info("未找到启用的功能配置: funcCode={}", funcCode);
            return new ArrayList<>();
        }

        List<AiModelVO> result = resolveModelsByFuncConfig(funcConfig);
        log.info("按功能编码查询可用模型: funcCode={}, 返回={}条", funcCode, result.size());
        return result;
    }

    /**
     * 按 funcCode 查启用的功能配置（状态正常 + 未删除），查全部分组展示所需字段。
     *
     * @param funcCode 功能编码
     * @return 功能配置；不存在 / 未启用 / 已删除时返回 null
     */
    private AidAiModelFuncConfig queryEnabledFuncConfig(String funcCode)
    {
        // 只查必要字段：分组元数据 + modelIds + 状态/删除标记
        LambdaQueryWrapper<AidAiModelFuncConfig> funcWrapper = Wrappers.lambdaQuery();
        funcWrapper.select(AidAiModelFuncConfig::getId, AidAiModelFuncConfig::getFuncCode,
                AidAiModelFuncConfig::getFuncName, AidAiModelFuncConfig::getModelType,
                AidAiModelFuncConfig::getGenerateMode, AidAiModelFuncConfig::getModelIds,
                AidAiModelFuncConfig::getStatus, AidAiModelFuncConfig::getDelFlag);
        funcWrapper.eq(AidAiModelFuncConfig::getFuncCode, funcCode);
        funcWrapper.eq(AidAiModelFuncConfig::getStatus, STATUS_NORMAL);
        funcWrapper.eq(AidAiModelFuncConfig::getDelFlag, DEL_FLAG_NORMAL);
        funcWrapper.last("limit 1");
        return aiModelFuncConfigService.getOne(funcWrapper, false);
    }

    /**
     * 根据功能配置解析其 modelIds 并组装可用模型 VO 列表。
     * 过滤掉：已删除 / 已停用 / 供应商不可用 / 配置中存在的无效模型 ID；
     * 结果按 modelIds 的原始配置顺序返回。
     *
     * @param funcConfig 已启用的功能配置（非空）
     * @return 可用模型列表，无可用模型时返回空数组
     */
    private List<AiModelVO> resolveModelsByFuncConfig(AidAiModelFuncConfig funcConfig)
    {
        String funcCode = funcConfig.getFuncCode();

        List<Long> orderedModelIds = parseModelIdsJson(funcConfig.getModelIds(), funcCode);
        if (CollectionUtil.isEmpty(orderedModelIds))
        {
            log.info("功能配置的 modelIds 为空: funcCode={}", funcCode);
            return new ArrayList<>();
        }

        //    注意：这里不按优先级排序，因为最终要按 modelIds 配置顺序返回
        LambdaQueryWrapper<AidAiModel> modelWrapper = Wrappers.lambdaQuery();
        modelWrapper.select(AidAiModel::getId, AidAiModel::getModelCode,
                AidAiModel::getModelName, AidAiModel::getLogoUrl, AidAiModel::getModelType,
                AidAiModel::getGenerateMode,
                AidAiModel::getCostCredits, AidAiModel::getPriority,
                AidAiModel::getProviderId, AidAiModel::getImageRefine,
                AidAiModel::getSupportsTextInput, AidAiModel::getSupportsImageInput,
                AidAiModel::getSupportsMultiImageInput, AidAiModel::getMaxOutputCount,
                AidAiModel::getDefaultOutputCount, AidAiModel::getSupportsAspectRatio,
                AidAiModel::getSupportsSizePreset, AidAiModel::getSupportsDuration,
                AidAiModel::getSupportsFirstFrame, AidAiModel::getSupportsLastFrame,
                AidAiModel::getDefaultSizeCode, AidAiModel::getDefaultAspectRatio,
                AidAiModel::getDefaultDurationSeconds, AidAiModel::getCapabilityJson,
                // 计费明细组装所需字段（billing 出参：SKU 档位 / 倍率 / 备注）
                AidAiModel::getBillingMode, AidAiModel::getBillingRuleJson,
                AidAiModel::getBillingMultiplier, AidAiModel::getIsFree, AidAiModel::getRemark);
        modelWrapper.in(AidAiModel::getId, orderedModelIds);
        modelWrapper.eq(AidAiModel::getStatus, STATUS_NORMAL);
        modelWrapper.eq(AidAiModel::getDelFlag, DEL_FLAG_NORMAL);
        List<AidAiModel> models = aiModelService.list(modelWrapper);
        modelDefinitions.preload(models);
        if (CollectionUtil.isEmpty(models))
        {
            log.info("功能配置匹配到的模型全部无效: funcCode={}, modelIds={}", funcCode, orderedModelIds);
            return new ArrayList<>();
        }

        LambdaQueryWrapper<AidAiProvider> providerWrapper = Wrappers.lambdaQuery();
        providerWrapper.select(AidAiProvider::getId, AidAiProvider::getProviderCode, AidAiProvider::getProviderName, AidAiProvider::getLogoUrl);
        providerWrapper.eq(AidAiProvider::getStatus, STATUS_NORMAL);
        providerWrapper.eq(AidAiProvider::getDelFlag, DEL_FLAG_NORMAL);
        List<AidAiProvider> providers = aiProviderService.list(providerWrapper);
        Set<Long> availableProviderIds = providers.stream()
                .map(AidAiProvider::getId)
                .collect(Collectors.toSet());
        Map<Long, String> providerNameMap = providers.stream()
                .collect(Collectors.toMap(AidAiProvider::getId, AidAiProvider::getProviderName));
        // 供应商ID→LOGO映射（logo 可能为空，过滤后再收集，避免 toMap 空值 NPE）
        Map<Long, String> providerLogoMap = providers.stream()
                .filter(p -> BuiltInBrandIcons.resolve(p.getProviderCode(), p.getLogoUrl()) != null)
                .collect(Collectors.toMap(AidAiProvider::getId,
                        p -> BuiltInBrandIcons.resolve(p.getProviderCode(), p.getLogoUrl())));

        Map<Long, AidAiModel> modelById = new LinkedHashMap<>();
        for (AidAiModel m : models)
        {
            modelById.put(m.getId(), m);
        }
        // 全局价格折算系数：一次请求只查一次配置，循环内复用
        BigDecimal globalFactor = billingDetailQueryService.readGlobalPriceFactor();
        List<AiModelVO> result = new ArrayList<>();
        var businessBindings = modelBusinessBindings.forFunction(funcCode);
        // preload 会把旧 capability_json 转为内存能力。只有已落入能力子表的模型，
        // 才必须具备显式业务能力绑定；否则历史模型会被误判成“模型池全部失效”。
        Set<Long> structuredModelIds = modelDefinitions.structuredModelIds(orderedModelIds);
        for (Long id : orderedModelIds)
        {
            AidAiModel model = modelById.get(id);
            if (Objects.isNull(model))
            {
                // 无效 ID（已删除 / 已停用 / 从未存在），跳过但不报错
                continue;
            }
            if (!availableProviderIds.contains(model.getProviderId()))
            {
                // 模型对应的供应商已停用 / 已删除，视为该模型不可用
                continue;
            }
            var defaults = businessBindings.stream()
                    .filter(binding -> Objects.equals(binding.getModelId(), model.getId()) && Boolean.TRUE.equals(binding.getDefaultCapability()))
                    .toList();
            if (structuredModelIds.contains(model.getId()) && defaults.size() != 1) continue;
            String selectedCapability = defaults.isEmpty()
                    ? com.aid.model.definition.LegacyModelDefinitionConverter.businessCode(
                            model, funcCode, funcConfig.getGenerateMode())
                    : defaults.get(0).getCapabilityCode();
            AidAiModel projected = model.getCapabilities().isEmpty()
                    ? model : modelDefinitions.project(model, selectedCapability,
                            defaults.isEmpty() ? null : defaults.get(0).getDefaultsJson());
            if (projected == null) continue;
            // The multi-parameter picker needs the reference-material limits of a
            // bound route, even when the text route is the invocation default.
            // DMC H3 shares one price/parameter family across these routes.
            if (Objects.equals(FUNC_CODE_STORYBOARD_VIDEO, funcCode)
                    && Objects.equals("dmc-h3-video", projected.getProtocol())
                    && Objects.equals("text_to_video", selectedCapability))
            {
                var referenceBinding = businessBindings.stream()
                        .filter(binding -> Objects.equals(binding.getModelId(), model.getId())
                                && Objects.equals("reference_to_video", binding.getCapabilityCode()))
                        .findFirst();
                if (referenceBinding.isPresent())
                {
                    AidAiModel referenceView = modelDefinitions.project(model, "reference_to_video",
                            referenceBinding.get().getDefaultsJson());
                    if (referenceView != null) projected = referenceView;
                }
            }
            AiModelVO item = buildModelVo(projected,
                    providerNameMap.get(model.getProviderId()),
                    providerLogoMap.get(model.getProviderId()),
                    globalFactor);
            Set<String> boundCapabilityCodes = businessBindings.stream()
                    .filter(binding -> Objects.equals(binding.getModelId(), model.getId()))
                    .map(com.aid.aid.domain.AidAiBusinessModelBinding::getCapabilityCode)
                    .filter(StrUtil::isNotBlank)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (boundCapabilityCodes.isEmpty() && StrUtil.isNotBlank(selectedCapability))
            {
                // 旧业务配置没有标准化 binding 时只暴露已投影的当前能力，不能把模型全部能力误开放给该业务。
                boundCapabilityCodes.add(selectedCapability);
            }
            if (model.getCapabilities() != null)
            {
                List<com.aid.model.vo.ModelCapabilityOptionVO> capabilityOptions = new ArrayList<>();
                for (var capability : model.getCapabilities())
                {
                    if (!Boolean.TRUE.equals(capability.getEnabled())
                            || !boundCapabilityCodes.contains(capability.getCode()))
                    {
                        continue;
                    }
                    var binding = businessBindings.stream()
                            .filter(value -> Objects.equals(value.getModelId(), model.getId())
                                    && Objects.equals(value.getCapabilityCode(), capability.getCode()))
                            .findFirst();
                    AidAiModel capabilityProjection = modelDefinitions.project(model, capability.getCode(),
                            binding.map(com.aid.aid.domain.AidAiBusinessModelBinding::getDefaultsJson).orElse(null));
                    if (capabilityProjection == null)
                    {
                        continue;
                    }
                    com.aid.model.vo.ModelCapabilityOptionVO option = new com.aid.model.vo.ModelCapabilityOptionVO();
                    option.setCode(capability.getCode());
                    option.setLabel(capability.getLabel());
                    option.setGenerateMode(capability.getGenerateMode());
                    var effectiveCapability = capabilityProjection.getCapabilities().stream()
                            .filter(value -> Objects.equals(value.getCode(), capability.getCode()))
                            .findFirst().orElse(capability);
                    option.setParameterSchema(effectiveCapability.getParameters());
                    option.setParameterRules(effectiveCapability.getRules());
                    option.setPresentation(clientCapabilityPresentation(effectiveCapability.getPresentation()));
                    option.setBilling(billingDetailQueryService.buildModelBillingDetail(capabilityProjection,
                            providerNameMap.get(model.getProviderId()), providerLogoMap.get(model.getProviderId()),
                            globalFactor));
                    capabilityOptions.add(option);
                }
                item.setAvailableCapabilities(capabilityOptions);
            }
            result.add(item);
        }
        return result;
    }

    /** 仅返回 C 端渲染所需字段，供应商协议及运营路由元数据不得透出。 */
    private Map<String, Object> clientCapabilityPresentation(Map<String, Object> source)
    {
        if (source == null || source.isEmpty()) return null;
        Set<String> allowed = Set.of("description", "icon", "sort", "tags", "inputRequirement",
                "supportsImageInput", "supportsMultiImageInput", "maxReferenceImages", "minReferenceImages");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (allowed.contains(key)) result.put(key, value);
        });
        return result.isEmpty() ? null : result;
    }

    /**
     * 组装单个模型 VO：基础信息 + 能力字段 + capability 展示兜底 + 计费明细。
     * 两个模型池查询入口共用，新增返回字段时只需改这里（select 字段仍需各自补齐）。
     *
     * @param model        模型实体（需含能力与计费相关字段）
     * @param providerName 供应商名称
     * @param providerLogo 供应商LOGO图标URL
     * @param globalFactor 全局价格折算系数（一次请求只查一次，循环内复用）
     * @return 组装完成的模型 VO
     */
    private AiModelVO buildModelVo(AidAiModel model, String providerName, String providerLogo, BigDecimal globalFactor)
    {
        AiModelVO vo = new AiModelVO();
        vo.setId(model.getId());
        vo.setModelCode(model.getModelCode());
        var legacy = model.getLegacyAliases() == null ? modelDefinitions.aliasesForModel(model.getId()) : model.getLegacyAliases();
        vo.setLegacyModelCodes(legacy.stream().map(com.aid.aid.domain.AidAiModelAlias::getLegacyModelCode).toList());
        vo.setLegacyModelIds(legacy.stream().map(com.aid.aid.domain.AidAiModelAlias::getLegacyModelId).toList());
        vo.setCapabilityCode(model.getSelectedCapabilityCode());
        if (model.getCapabilities() != null) model.getCapabilities().stream()
                .filter(d -> Objects.equals(d.getCode(), model.getSelectedCapabilityCode())).findFirst().ifPresent(d -> {
                    vo.setParameterSchema(d.getParameters());
                    vo.setParameterRules(d.getRules());
                });
        vo.setModelName(model.getModelName());
        vo.setModelType(model.getModelType());
        vo.setGenerateMode(model.getGenerateMode());
        // 展示单价 = 原价 × 模型级倍率 × 全局折算系数（禁止直出库表原价）
        vo.setCostCredits(billingDetailQueryService.displayCostCredits(model, globalFactor));
        vo.setIsFree(Boolean.TRUE.equals(model.getIsFree()));
        vo.setPriority(model.getPriority());
        vo.setImageRefine(model.getImageRefine());
        vo.setProviderName(providerName);
        vo.setModelLogo(model.getLogoUrl());
        vo.setProviderLogo(StrUtil.isNotBlank(model.getLogoUrl()) ? model.getLogoUrl() : providerLogo);
        // 模型能力扩展字段（图片/视频统一，供前端动态渲染参数面板）
        vo.setSupportsTextInput(model.getSupportsTextInput());
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
        // capability：DB 中的 capability_json 字符串反序列化为结构化对象（前端无需再 JSON.parse）；
        // 解析失败或为空时按 modelType 兜底为推荐结构
        vo.setCapability(parseOrDefaultCapability(model.getModelType(), model.getCapabilityJson()));
        vo.setMaxPromptCharacters(vo.getCapability().getMaxPromptCharacters());
        vo.setMaxPromptCharactersCjk(vo.getCapability().getMaxPromptCharactersCjk());
        // 兜底：旧库未跑能力迁移脚本时 13 个顶层能力字段在 DB 全为 NULL，
        // Jackson 全局 default-property-inclusion=non_null 会丢弃 null，前端会拿不到字段。
        // 在此按 modelType 注入合理默认值，保证模型池接口返回的 VO 始终是完整渲染契约。
        applyCapabilityDefaults(vo);
        // 单次最多参考图张数（999=无限，0=禁止/文本模型，N=上限）
        vo.setMaxReferenceImages(ReferenceImageLimiter.resolveDisplayMax(model.getModelType(), model.getCapabilityJson()));
        // 单次最少参考图张数（0=不要求带图，N>=1=必须至少带 N 张图）
        vo.setMinReferenceImages(ReferenceImageLimiter.readMinFromCapabilityJson(model.getCapabilityJson()));
        // 输入要求标签（text_only/image_optional/image_required/video_required），前端据此控制上传入口
        vo.setInputRequirement(ModelInputRequirementResolver.resolve(
                model.getModelType(), model.getGenerateMode(),
                model.getCapabilityJson(), model.getSupportsImageInput()));
        // capability 展示契约兜底：分辨率/比例/时长/音画同出等关键键必返，前端无需判 undefined
        normalizeCapabilityForDisplay(vo);
        // 计费明细（口径与 /api/public/billing/detail 一致）：SKU 档位价格 + 表头列定义，
        // 视频模型据此展示「秒数/分辨率对应的价格档位」
        vo.setBilling(billingDetailQueryService.buildModelBillingDetail(model, providerName, providerLogo, globalFactor));
        return vo;
    }

    /**
     * capability 展示契约兜底（仅 image/video）：
     * 关键键缺失时按顶层字段/空集合补齐，保证序列化后键必然存在——
     * 分辨率/比例枚举 → 空数组；默认规格/比例/时长 → 顶层默认值；
     * 参考图上下限 → 顶层归一化值；视频音画同出（supportsAudio/supportsBgm/supportsVoiceId）→ false，
     * 音频类型枚举 → 空数组；视频时长枚举缺失 → 用默认时长补单档。
     */
    private void normalizeCapabilityForDisplay(AiModelVO vo)
    {
        CapabilityVO cap = vo.getCapability();
        if (Objects.isNull(cap))
        {
            return;
        }
        String type = vo.getModelType();
        boolean isImage = Objects.equals(MODEL_TYPE_IMAGE, type);
        boolean isVideo = Objects.equals(MODEL_TYPE_VIDEO, type);
        if (!isImage && !isVideo)
        {
            return;
        }
        // 规格/比例枚举兜底为空数组，避免 non_null 序列化丢键
        if (Objects.isNull(cap.getSizeOptions()))
        {
            cap.setSizeOptions(new ArrayList<>());
        }
        if (Objects.isNull(cap.getAspectRatioOptions()))
        {
            cap.setAspectRatioOptions(new ArrayList<>());
        }
        if (CollectionUtil.isEmpty(cap.getSizeOptions()))
        {
            if (StrUtil.isBlank(cap.getDefaultSize()))
            {
                cap.setDefaultSize(vo.getDefaultSizeCode());
            }
        }
        else
        {
            String defaultSize = resolveSupportedOption(cap.getSizeOptions(),
                    cap.getDefaultSize(), vo.getDefaultSizeCode());
            cap.setDefaultSize(defaultSize);
            vo.setDefaultSizeCode(defaultSize);
        }
        if (CollectionUtil.isEmpty(cap.getAspectRatioOptions()))
        {
            if (StrUtil.isBlank(cap.getDefaultAspectRatio()))
            {
                cap.setDefaultAspectRatio(vo.getDefaultAspectRatio());
            }
        }
        else
        {
            String defaultAspectRatio = resolveSupportedOption(cap.getAspectRatioOptions(),
                    cap.getDefaultAspectRatio(), vo.getDefaultAspectRatio());
            cap.setDefaultAspectRatio(defaultAspectRatio);
            vo.setDefaultAspectRatio(defaultAspectRatio);
        }
        // 参考图上下限与顶层口径对齐（顶层为归一化后的权威值）
        if (Objects.isNull(cap.getMaxReferenceImages()))
        {
            cap.setMaxReferenceImages(vo.getMaxReferenceImages());
        }
        if (Objects.isNull(cap.getMinReferenceImages()))
        {
            cap.setMinReferenceImages(vo.getMinReferenceImages());
        }
        if (isVideo)
        {
            // 时长枚举缺失 → 用默认时长补单档，防止前端时长下拉为空
            if (CollectionUtil.isEmpty(cap.getDurationOptions()))
            {
                List<Integer> single = new ArrayList<>();
                Integer defaultDuration = Objects.nonNull(cap.getDefaultDurationSeconds())
                        ? cap.getDefaultDurationSeconds() : vo.getDefaultDurationSeconds();
                if (Objects.nonNull(defaultDuration))
                {
                    single.add(defaultDuration);
                }
                cap.setDurationOptions(single);
                cap.setDefaultDurationSeconds(defaultDuration);
                vo.setDefaultDurationSeconds(defaultDuration);
            }
            else
            {
                Integer defaultDuration = resolveSupportedDuration(cap.getDurationOptions(),
                        cap.getDefaultDurationSeconds(), vo.getDefaultDurationSeconds());
                cap.setDefaultDurationSeconds(defaultDuration);
                vo.setDefaultDurationSeconds(defaultDuration);
            }
            // 音画同出能力键必返：未配置一律视为不支持；支持时默认开启
            if (Objects.isNull(cap.getSupportsAudio()))
            {
                cap.setSupportsAudio(Boolean.FALSE);
            }
            if (Objects.isNull(cap.getSupportsReferenceAudio()))
            {
                cap.setSupportsReferenceAudio(Boolean.FALSE);
            }
            if (Objects.isNull(cap.getMaxReferenceAudios()))
            {
                cap.setMaxReferenceAudios(0);
            }
            if (Objects.isNull(cap.getReferenceAudioMinDurationSeconds()))
            {
                cap.setReferenceAudioMinDurationSeconds(java.math.BigDecimal.ZERO);
            }
            if (Objects.isNull(cap.getReferenceAudioMaxDurationSeconds()))
            {
                cap.setReferenceAudioMaxDurationSeconds(java.math.BigDecimal.ZERO);
            }
            if (Objects.isNull(cap.getReferenceAudioMaxTotalDurationSeconds()))
            {
                cap.setReferenceAudioMaxTotalDurationSeconds(java.math.BigDecimal.ZERO);
            }
            if (Objects.isNull(cap.getReferenceAudioFormats()))
            {
                cap.setReferenceAudioFormats(new ArrayList<>());
            }
            // 未声明 defaultAudio 的历史模型保持“支持即默认开启”；显式 false 的模型默认无声。
            boolean defaultAudio = Boolean.TRUE.equals(cap.getSupportsAudio())
                    && !Boolean.FALSE.equals(cap.getDefaultAudio());
            cap.setDefaultAudio(defaultAudio);
            cap.setDefaultGenerateAudio(defaultAudio);
            if (Objects.isNull(cap.getSupportsBgm()))
            {
                cap.setSupportsBgm(Boolean.FALSE);
            }
            if (Objects.isNull(cap.getSupportsVoiceId()))
            {
                cap.setSupportsVoiceId(Boolean.FALSE);
            }
            if (Objects.isNull(cap.getAudioTypes()))
            {
                cap.setAudioTypes(new ArrayList<>());
            }
        }
    }

    /** 选择当前业务能力实际支持的规范选项，能力默认优先，其次使用模型默认。 */
    private String resolveSupportedOption(List<String> options, String capabilityDefault, String modelDefault)
    {
        String matched = ModelCapabilityResolver.matchOption(options, capabilityDefault);
        if (StrUtil.isNotBlank(matched))
        {
            return matched;
        }
        matched = ModelCapabilityResolver.matchOption(options, modelDefault);
        if (StrUtil.isNotBlank(matched))
        {
            return matched;
        }
        return options.stream().filter(StrUtil::isNotBlank).map(String::trim).findFirst().orElse(null);
    }

    /** 选择当前业务能力实际支持的时长默认值。 */
    private Integer resolveSupportedDuration(List<Integer> options, Integer capabilityDefault, Integer modelDefault)
    {
        if (Objects.nonNull(capabilityDefault) && options.contains(capabilityDefault))
        {
            return capabilityDefault;
        }
        if (Objects.nonNull(modelDefault) && options.contains(modelDefault))
        {
            return modelDefault;
        }
        return options.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    @Override
    public List<AiModelVO> listAvailableModelsByFuncCodes(List<String> funcCodes)
    {
        if (CollectionUtil.isEmpty(funcCodes))
        {
            log.info("按功能编码查模型失败：funcCodes 为空");
            return new ArrayList<>();
        }

        // 按入参顺序逐个查询并合并，按模型 ID 去重（首次出现优先，保持顺序）
        Map<Long, AiModelVO> mergedById = new LinkedHashMap<>();
        for (String funcCode : funcCodes)
        {
            if (StrUtil.isBlank(funcCode))
            {
                continue;
            }
            List<AiModelVO> pool = listAvailableModelsByFuncCode(funcCode);
            for (AiModelVO vo : pool)
            {
                if (Objects.nonNull(vo) && Objects.nonNull(vo.getId()))
                {
                    mergedById.putIfAbsent(vo.getId(), vo);
                }
            }
        }

        List<AiModelVO> result = new ArrayList<>(mergedById.values());
        log.info("按多功能编码查询可用模型: funcCodes={}, 去重后返回={}条", funcCodes, result.size());
        return result;
    }

    @Override
    public List<AiModelFuncGroupVO> listAvailableModelsGroupedByFuncCodes(List<String> funcCodes)
    {
        return listAvailableModelsGroupedByFuncCodes(funcCodes, null, null, null);
    }

    @Override
    public List<AiModelFuncGroupVO> listAvailableModelsGroupedByFuncCodes(List<String> funcCodes,
            Long projectId, Long episodeId, Long userId)
    {
        if (CollectionUtil.isEmpty(funcCodes))
        {
            log.info("按功能编码分组查模型失败：funcCodes 为空");
            return new ArrayList<>();
        }

        // 传了项目则按创作模式重映射出片池，保证列表与 /generate/video 校验同源
        List<String> resolvedCodes = remapFuncCodesByCreationMode(funcCodes, projectId, episodeId, userId);

        // 按入参顺序逐个查询，每个功能编码独立成组（不跨组去重，保留各自归属）
        List<AiModelFuncGroupVO> groups = new ArrayList<>();
        for (String funcCode : resolvedCodes)
        {
            if (StrUtil.isBlank(funcCode))
            {
                continue;
            }
            AiModelFuncGroupVO group = new AiModelFuncGroupVO();
            group.setFuncCode(funcCode);

            // 查启用的功能配置：用于带出功能名称/大类/生成模式，并解析模型池
            AidAiModelFuncConfig funcConfig = queryEnabledFuncConfig(funcCode);
            if (Objects.isNull(funcConfig))
            {
                // 无配置 / 未启用：分组仍保留，models 置空，前端可据此提示该场景暂无可用模型
                log.info("分组查模型：未找到启用的功能配置, funcCode={}", funcCode);
                groups.add(group);
                continue;
            }
            group.setFuncName(funcConfig.getFuncName());
            group.setModelType(funcConfig.getModelType());
            group.setGenerateMode(funcConfig.getGenerateMode());
            List<AiModelVO> models = resolveModelsByFuncConfig(funcConfig);
            group.setModels(models);
            if (!models.isEmpty())
            {
                group.setDefaultModelCode(models.get(0).getModelCode());
            }
            groups.add(group);
        }

        log.info("按功能编码分组查询可用模型: funcCodes={}, resolved={}, 返回分组={}个",
                funcCodes, resolvedCodes, groups.size());
        return groups;
    }

    /**
     * 按项目创作模式重映射出片功能编码：专业版多参出片走 {@code main_storyboard_video_multi_pro}。
     * 无项目 / 解析不到创作模式时原样返回。
     */
    private List<String> remapFuncCodesByCreationMode(List<String> funcCodes, Long projectId,
            Long episodeId, Long userId)
    {
        if (CollectionUtil.isEmpty(funcCodes) || Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return funcCodes;
        }
        String creationMode = resolveProjectCreationMode(projectId, episodeId, userId);
        if (StrUtil.isBlank(creationMode))
        {
            return funcCodes;
        }
        boolean pro = CreationModeEnum.PRO.getValue().equals(creationMode);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String code : funcCodes)
        {
            if (StrUtil.isBlank(code))
            {
                continue;
            }
            if (pro && Objects.equals(FUNC_CODE_STORYBOARD_VIDEO, code))
            {
                ordered.add(FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO);
            }
            else
            {
                ordered.add(code);
            }
        }
        return new ArrayList<>(ordered);
    }

    /**
     * 解析项目创作模式：电影取 default_creation_mode；剧集优先取剧集 creation_mode。
     */
    private String resolveProjectCreationMode(Long projectId, Long episodeId, Long userId)
    {
        AidComicProject project = aidComicProjectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getProjectType,
                                AidComicProject::getDefaultCreationMode)
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getUserId, userId)
                        .last("limit 1"));
        if (Objects.isNull(project))
        {
            return "";
        }
        if (!Objects.equals(ProjectTypeEnum.MOVIE.getValue(), project.getProjectType())
                && Objects.nonNull(episodeId))
        {
            AidComicEpisode episode = aidComicEpisodeService.getOne(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .select(AidComicEpisode::getId, AidComicEpisode::getCreationMode)
                            .eq(AidComicEpisode::getId, episodeId)
                            .eq(AidComicEpisode::getProjectId, projectId)
                            .eq(AidComicEpisode::getUserId, userId)
                            .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"));
            if (Objects.nonNull(episode) && StrUtil.isNotBlank(episode.getCreationMode()))
            {
                return episode.getCreationMode().trim();
            }
        }
        return StrUtil.trimToEmpty(project.getDefaultCreationMode());
    }

    /**
     * 宽松解析 {@code aid_ai_model_func_config.model_ids}（JSON 数组字符串）。
     *
     * @param modelIdsJson DB 中存储的 JSON 字符串，例如 {@code "[5,2,9]"}
     * @param funcCode     当前功能编码，仅用于日志
     * @return 去重后的有序 ID 列表；非法数据返回空列表
     */
    private List<Long> parseModelIdsJson(String modelIdsJson, String funcCode)
    {
        List<Long> ordered = new ArrayList<>();
        if (StrUtil.isBlank(modelIdsJson))
        {
            return ordered;
        }
        try
        {
            List<?> raw = JSONUtil.parseArray(modelIdsJson).toList(Object.class);
            Set<Long> seen = new LinkedHashSet<>();
            for (Object item : raw)
            {
                if (Objects.isNull(item))
                {
                    continue;
                }
                Long id = null;
                if (item instanceof Number)
                {
                    id = ((Number) item).longValue();
                }
                else
                {
                    String s = item.toString().trim();
                    if (StrUtil.isBlank(s))
                    {
                        continue;
                    }
                    try
                    {
                        id = Long.parseLong(s);
                    }
                    catch (NumberFormatException ignore)
                    {
                        // 非数字元素跳过，不阻断后续有效元素
                    }
                }
                if (Objects.nonNull(id) && id > 0L && seen.add(id))
                {
                    ordered.add(id);
                }
            }
        }
        catch (Exception e)
        {
            log.error("解析功能配置 modelIds 失败, funcCode={}, jsonLen={}, err={}",
                    funcCode, StrUtil.length(modelIdsJson), e.getMessage());
        }
        return ordered;
    }

    /**
     * 兜底填充 VO 中为 null 的能力字段（按 modelType 给出合理默认值），
     * 保证 /api/user/model/list 在 DB 字段未回填时也能返回完整的能力契约。
     * 仅在字段为 null 时填充，运营在后台已配置的非 null 值不会被覆盖。
     *
     * @param vo 已完成基本字段赋值的 AiModelVO
     */
    private void applyCapabilityDefaults(AiModelVO vo)
    {
        String type = vo.getModelType();
        boolean isText = Objects.equals(MODEL_TYPE_TEXT, type);
        boolean isImage = Objects.equals(MODEL_TYPE_IMAGE, type);
        boolean isVideo = Objects.equals(MODEL_TYPE_VIDEO, type);

        // 输入能力：默认所有模型都支持文本输入；图片/视频默认支持图片输入；多图默认关闭
        if (Objects.isNull(vo.getSupportsTextInput())) vo.setSupportsTextInput(Boolean.TRUE);
        if (Objects.isNull(vo.getSupportsImageInput())) vo.setSupportsImageInput(isImage || isVideo);
        if (Objects.isNull(vo.getSupportsMultiImageInput())) vo.setSupportsMultiImageInput(Boolean.FALSE);

        // 输出数量：图片默认最多 4 张，文本/视频均为 1
        if (Objects.isNull(vo.getMaxOutputCount())) vo.setMaxOutputCount(isImage ? 4 : 1);
        if (Objects.isNull(vo.getDefaultOutputCount())) vo.setDefaultOutputCount(1);

        // 比例 / 规格 / 时长开关：图片支持比例+规格，视频在此基础上加时长
        if (Objects.isNull(vo.getSupportsAspectRatio())) vo.setSupportsAspectRatio(isImage || isVideo);
        if (Objects.isNull(vo.getSupportsSizePreset())) vo.setSupportsSizePreset(isImage || isVideo);
        if (Objects.isNull(vo.getSupportsDuration())) vo.setSupportsDuration(isVideo);
        // 首帧/尾帧：仅 video 模型可能支持，由运营在后台逐个开启；text/image 默认 false 保持契约稳定
        if (Objects.isNull(vo.getSupportsFirstFrame())) vo.setSupportsFirstFrame(Boolean.FALSE);
        if (Objects.isNull(vo.getSupportsLastFrame())) vo.setSupportsLastFrame(Boolean.FALSE);

        // 默认规格 / 比例 / 时长（仅对应类型注入；不适用类型保持 null，符合语义）
        if (isImage)
        {
            if (Boolean.TRUE.equals(vo.getSupportsSizePreset()) && StrUtil.isBlank(vo.getDefaultSizeCode())) vo.setDefaultSizeCode("2K");
            if (Boolean.TRUE.equals(vo.getSupportsAspectRatio()) && StrUtil.isBlank(vo.getDefaultAspectRatio())) vo.setDefaultAspectRatio("1:1");
        }
        else if (isVideo)
        {
            if (StrUtil.isBlank(vo.getDefaultSizeCode())) vo.setDefaultSizeCode("1080P");
            if (StrUtil.isBlank(vo.getDefaultAspectRatio())) vo.setDefaultAspectRatio("16:9");
            if (Objects.isNull(vo.getDefaultDurationSeconds())) vo.setDefaultDurationSeconds(5);
        }

        // capability 已在调用前由 parseOrDefaultCapability 兜底，此处无需再处理
    }

    /**
     * 解析 DB 中的 capability_json 字符串为 CapabilityVO；为空或解析失败时按 modelType 给出推荐结构。
     *
     * @param modelType 模型分类
     * @param json      DB 中存储的 capability_json 原文
     * @return 永不为 null 的 CapabilityVO
     */
    private CapabilityVO parseOrDefaultCapability(String modelType, String json)
    {
        if (StrUtil.isNotBlank(json))
        {
            try
            {
                CapabilityVO parsed = JSONUtil.toBean(json, CapabilityVO.class);
                if (Objects.nonNull(parsed))
                {
                    if (Objects.equals(MODEL_TYPE_TEXT, modelType))
                    {
                        normalizeTextCapability(parsed);
                    }
                    return parsed;
                }
            }
            catch (Exception e)
            {
                // 不阻断主流程，仅打日志便于排查脏数据
                log.error("解析 capability_json 失败, modelType={}, jsonLen={}",
                        modelType, StrUtil.length(json), e);
            }
        }
        return defaultCapability(modelType);
    }

    /**
     * 按 modelType 构造推荐 CapabilityVO（与 sql/alter_20260430_aid_ai_model_capability.sql 推荐结构对齐）。
     */
    private CapabilityVO defaultCapability(String modelType)
    {
        CapabilityVO cap = new CapabilityVO();
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        if (Objects.equals(MODEL_TYPE_IMAGE, modelType))
        {
            cap.setSizeOptions(Arrays.asList("1K", "2K", "4K"));
            cap.setDefaultSize("2K");
            cap.setAspectRatioOptions(Arrays.asList("1:1", "2:3", "3:2", "3:4", "4:3", "7:9", "9:7", "9:16", "9:21", "16:9", "21:9"));
            cap.setDefaultAspectRatio("1:1");
            cap.setAllowCustomWH(Boolean.FALSE);
            // 文生图：支持比例+规格
            rules.put("textToImage", buildSceneRule(true, true, null, null));
            // 图生图：支持比例+规格 + 比例跟随输入
            rules.put("imageToImage", buildSceneRule(true, true, null, true));
        }
        else if (Objects.equals(MODEL_TYPE_VIDEO, modelType))
        {
            cap.setSizeOptions(Arrays.asList("720P", "1080P"));
            cap.setDefaultSize("1080P");
            cap.setAspectRatioOptions(Arrays.asList("16:9", "9:16", "1:1", "4:3", "3:4"));
            cap.setDefaultAspectRatio("16:9");
            cap.setDurationOptions(Arrays.asList(5, 10, 15, 30));
            cap.setDefaultDurationSeconds(5);
            // 文生视频：支持比例+规格+时长
            rules.put("textToVideo", buildSceneRule(true, true, true, null));
            // 图生视频：上同 + 比例跟随输入
            rules.put("imageToVideo", buildSceneRule(true, true, true, true));
        }
        else
        {
            cap.setInputModalities(List.of("TEXT"));
            cap.setOutputModalities(List.of("TEXT"));
            cap.setSupportsImageInput(Boolean.FALSE);
            cap.setSupportsVideoInput(Boolean.FALSE);
            cap.setSupportsAudioInput(Boolean.FALSE);
            cap.setSupportsDocumentInput(Boolean.FALSE);
            cap.setMaxInputImages(0);
            cap.setMaxInputVideos(0);
            cap.setMaxInputAudios(0);
            cap.setMaxInputDocuments(0);
            cap.setSupportsReasoning(Boolean.FALSE);
            cap.setSupportsReasoningDisable(Boolean.FALSE);
            cap.setSupportsReasoningContent(Boolean.FALSE);
            cap.setReturnsReasoningContent(Boolean.FALSE);
            cap.setSupportsReasoningBudget(Boolean.FALSE);
            cap.setDefaultReasoningEnabled(Boolean.FALSE);
            cap.setAllowedReasoningLevels(List.of());
            rules.put("textOnly", buildSceneRule(false, false, false, null));
        }
        cap.setSceneRules(rules);
        return cap;
    }

    /** 文本模型能力保持稳定非 null 契约；仅在官方未公布数量时使用 10 的约定值。 */
    private void normalizeTextCapability(CapabilityVO cap)
    {
        LinkedHashSet<String> inputModalities = new LinkedHashSet<>();
        inputModalities.add("TEXT");
        if (cap.getInputModalities() != null)
        {
            cap.getInputModalities().stream().filter(StrUtil::isNotBlank)
                    .map(value -> value.trim().toUpperCase()).forEach(inputModalities::add);
        }
        cap.setInputModalities(new ArrayList<>(inputModalities));
        if (cap.getOutputModalities() == null || cap.getOutputModalities().isEmpty())
        {
            cap.setOutputModalities(List.of("TEXT"));
        }
        cap.setSupportsImageInput(Boolean.TRUE.equals(cap.getSupportsImageInput()) || inputModalities.contains("IMAGE"));
        cap.setSupportsVideoInput(Boolean.TRUE.equals(cap.getSupportsVideoInput()) || inputModalities.contains("VIDEO"));
        cap.setSupportsAudioInput(Boolean.TRUE.equals(cap.getSupportsAudioInput()) || inputModalities.contains("AUDIO"));
        cap.setSupportsDocumentInput(Boolean.TRUE.equals(cap.getSupportsDocumentInput()) || inputModalities.contains("DOCUMENT"));
        cap.setMaxInputImages(normalizeTextInputCount(cap.getSupportsImageInput(), cap.getMaxInputImages()));
        cap.setMaxInputVideos(normalizeTextInputCount(cap.getSupportsVideoInput(), cap.getMaxInputVideos()));
        cap.setMaxInputAudios(normalizeTextInputCount(cap.getSupportsAudioInput(), cap.getMaxInputAudios()));
        cap.setMaxInputDocuments(normalizeTextInputCount(cap.getSupportsDocumentInput(), cap.getMaxInputDocuments()));
        cap.setSupportsReasoning(Boolean.TRUE.equals(cap.getSupportsReasoning()));
        cap.setSupportsReasoningDisable(Boolean.TRUE.equals(cap.getSupportsReasoningDisable()));
        cap.setSupportsReasoningContent(Boolean.TRUE.equals(cap.getSupportsReasoningContent())
                || Boolean.TRUE.equals(cap.getReturnsReasoningContent()));
        cap.setReturnsReasoningContent(cap.getSupportsReasoningContent());
        cap.setSupportsReasoningBudget(Boolean.TRUE.equals(cap.getSupportsReasoningBudget()));
        cap.setDefaultReasoningEnabled(Boolean.TRUE.equals(cap.getDefaultReasoningEnabled()));
        if (cap.getAllowedReasoningLevels() == null)
        {
            cap.setAllowedReasoningLevels(List.of());
        }
    }

    private Integer normalizeTextInputCount(Boolean supported, Integer value)
    {
        if (!Boolean.TRUE.equals(supported))
        {
            return 0;
        }
        return value == null || value == 0 ? 10 : Math.max(-1, value);
    }

    /**
     * 构造单个场景规则 Map（仅放入非 null 的开关，避免 Jackson NON_NULL 序列化时混入冗余 false）。
     * 注：当传入 false 时仍会被序列化（boolean false 不算 null）；仅在传入 null 时跳过。
     */
    private Map<String, Object> buildSceneRule(Boolean supportsAspectRatio,
                                               Boolean supportsSizePreset,
                                               Boolean supportsDuration,
                                               Boolean aspectRatioFollowInput)
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        if (Objects.nonNull(supportsAspectRatio)) rule.put("supportsAspectRatio", supportsAspectRatio);
        if (Objects.nonNull(supportsSizePreset)) rule.put("supportsSizePreset", supportsSizePreset);
        if (Objects.nonNull(supportsDuration)) rule.put("supportsDuration", supportsDuration);
        if (Objects.nonNull(aspectRatioFollowInput)) rule.put("aspectRatioFollowInput", aspectRatioFollowInput);
        return rule;
    }
}
