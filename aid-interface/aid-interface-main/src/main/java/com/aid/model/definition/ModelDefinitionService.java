package com.aid.model.definition;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelAlias;
import com.aid.aid.domain.AidAiModelCapability;
import com.aid.aid.domain.AidAiModelProtocolBinding;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.aid.aid.mapper.AidAiModelAliasMapper;
import com.aid.aid.mapper.AidAiModelCapabilityMapper;
import com.aid.aid.mapper.AidAiModelProtocolBindingMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.support.ModelBillingRuleValidator;
import com.aid.aid.service.support.MediaCapabilityNormalizer;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.ProviderEndpointUtils;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理真实模型的能力和协议绑定并解析历史标识。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDefinitionService {
    private final IAidAiModelService models;
    private final AidAiModelCapabilityMapper capabilities;
    private final AidAiModelProtocolBindingMapper bindings;
    private final AidAiModelAliasMapper aliases;
    private final ModelProtocolRegistry protocols;
    private final ModelBusinessBindingService businessBindings;
    private final IAidAiProviderService providers;

    /** 列表一次批量读取定义和旧标识，避免每个模型分别查询数据库。 */
    public void preload(List<AidAiModel> models) {
        if (models.isEmpty()) return;
        var ids = models.stream().map(AidAiModel::getId).toList();
        var rows = capabilities.selectList(Wrappers.<AidAiModelCapability>lambdaQuery().in(AidAiModelCapability::getModelId, ids).orderByAsc(AidAiModelCapability::getSortOrder));
        var routes = bindings.selectList(Wrappers.<AidAiModelProtocolBinding>lambdaQuery().in(AidAiModelProtocolBinding::getModelId, ids).orderByAsc(AidAiModelProtocolBinding::getSortOrder));
        var old = aliases.selectList(Wrappers.<AidAiModelAlias>lambdaQuery().in(AidAiModelAlias::getModelId, ids));
        for (var model : models) {
            List<ModelCapabilityDefinition> configured = rows.stream().filter(row -> Objects.equals(row.getModelId(), model.getId())).map(row -> {
                var definition = JSON.parseObject(row.getDefinitionJson(), ModelCapabilityDefinition.class);
                definition.setBindings(routes.stream().filter(route -> Objects.equals(route.getModelId(), model.getId()) && Objects.equals(route.getCapabilityCode(), row.getCapabilityCode()))
                        .map(route -> parseBinding(route.getDefinitionJson())).toList());
                return definition;
            }).toList();
            model.setStructuredCapabilities(!configured.isEmpty());
            model.setCapabilities(configured.isEmpty() ? LegacyModelDefinitionConverter.convert(model) : configured);
            if (!configured.isEmpty()) ModelSupportOverview.apply(model, configured);
            model.setLegacyAliases(old.stream().filter(alias -> Objects.equals(alias.getModelId(), model.getId())).toList());
        }
    }

    public List<ModelCapabilityDefinition> definitions(Long modelId) {
        if (modelId == null) return List.of();
        List<AidAiModelCapability> rows = capabilities.selectList(Wrappers.<AidAiModelCapability>lambdaQuery()
                .eq(AidAiModelCapability::getModelId, modelId).orderByAsc(AidAiModelCapability::getSortOrder));
        List<AidAiModelProtocolBinding> routes = bindings.selectList(Wrappers.<AidAiModelProtocolBinding>lambdaQuery()
                .eq(AidAiModelProtocolBinding::getModelId, modelId).orderByAsc(AidAiModelProtocolBinding::getSortOrder));
        List<ModelCapabilityDefinition> result = new ArrayList<>();
        for (AidAiModelCapability row : rows) {
            ModelCapabilityDefinition definition = JSON.parseObject(row.getDefinitionJson(), ModelCapabilityDefinition.class);
            definition.setBindings(routes.stream().filter(r -> Objects.equals(r.getCapabilityCode(), row.getCapabilityCode()))
                    .map(r -> parseBinding(r.getDefinitionJson())).toList());
            result.add(definition);
        }
        return result;
    }

    static ModelProtocolBinding parseBinding(String json) {
        ModelProtocolBinding binding = JSON.parseObject(json, ModelProtocolBinding.class);
        binding.setCapability(MediaCapabilityNormalizer.normalize(binding.getCapability()));
        binding.setPresentation(MediaCapabilityNormalizer.normalize(binding.getPresentation()));
        return binding;
    }

    /**
     * 返回确实已经落入结构化能力子表的模型 ID。
     *
     * <p>{@link #preload(List)} 会把旧版 capability_json 转换成只存在于内存中的能力定义，
     * 因此调用方不能再用 {@code model.getCapabilities().isEmpty()} 判断模型是否已经迁移。
     * 业务模型池只有对真实结构化模型才要求显式的能力绑定；旧模型仍按兼容规则投影。</p>
     */
    public Set<Long> structuredModelIds(List<Long> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) return Set.of();
        return capabilities.selectList(Wrappers.<AidAiModelCapability>lambdaQuery()
                        .select(AidAiModelCapability::getModelId)
                        .in(AidAiModelCapability::getModelId, modelIds))
                .stream().map(AidAiModelCapability::getModelId).collect(java.util.stream.Collectors.toSet());
    }

    public AidAiModelAlias alias(String code) {
        if (code == null || code.isBlank()) return null;
        AidAiModelAlias alias = aliases.selectOne(Wrappers.<AidAiModelAlias>lambdaQuery()
                .eq(AidAiModelAlias::getLegacyModelCode, code));
        if (alias == null) return null;
        // 同编码重新建模后，旧别名不能继续把新请求路由到历史替代模型。
        AidAiModel active = models.getOne(Wrappers.<AidAiModel>lambdaQuery()
                .select(AidAiModel::getId)
                .eq(AidAiModel::getModelCode, code)
                .eq(AidAiModel::getDelFlag, "0")
                .last("limit 1"), false);
        return active != null && !Objects.equals(active.getId(), alias.getModelId()) ? null : alias;
    }

    public AidAiModelAlias alias(Long id) {
        if (id == null) return null;
        return aliases.selectOne(Wrappers.<AidAiModelAlias>lambdaQuery().eq(AidAiModelAlias::getLegacyModelId, id));
    }

    public List<AidAiModelAlias> aliasesForModel(Long id) {
        if (id == null) return List.of();
        return aliases.selectList(Wrappers.<AidAiModelAlias>lambdaQuery().eq(AidAiModelAlias::getModelId, id));
    }

    public AidAiModel detail(Long id) {
        AidAiModelAlias old = alias(id);
        if (old != null) id = old.getModelId();
        AidAiModel model = models.selectAidAiModelById(id);
        if (model != null) {
            List<ModelCapabilityDefinition> configured = definitions(id);
            model.setStructuredCapabilities(!configured.isEmpty());
            model.setCapabilities(configured.isEmpty() ? LegacyModelDefinitionConverter.convert(model) : configured);
            if (!configured.isEmpty()) ModelSupportOverview.apply(model, configured);
            model.setBusinessBindings(businessBindings.forModel(id));
            if (model.getBusinessBindings().isEmpty() && configured.isEmpty()) model.setBusinessBindings(businessBindings.legacyBindings(model));
        }
        return model;
    }

    public AidAiModel project(AidAiModel model, String capabilityCode) {
        return project(model, capabilityCode, null);
    }

    public AidAiModel project(AidAiModel model, String capabilityCode, String businessDefaultsJson) {
        List<ModelCapabilityDefinition> configured = model.getCapabilities() == null ? definitions(model.getId()) : model.getCapabilities();
        if (configured.isEmpty()) configured = LegacyModelDefinitionConverter.convert(model);
        List<ModelCapabilityDefinition> choices = ModelCapabilitySelection.candidates(configured, capabilityCode);
        if (choices.size() != 1) return null;
        ModelCapabilityDefinition definition = ModelSchemaPresentation.withBusinessDefaults(choices.get(0), businessDefaultsJson);
        List<ModelProtocolBinding> routes = definition.getBindings().stream()
                .filter(r -> Boolean.TRUE.equals(r.getEnabled()) && Boolean.TRUE.equals(r.getDefaultBinding())).toList();
        if (routes.size() != 1) return null;
        var route = routes.get(0);
        AidAiModel view = new AidAiModel();
        BeanUtil.copyProperties(model, view);
        view.setSelectedCapabilityCode(definition.getCode());
        view.setProtocol(route.getProtocol());
        view.setApiSuffix(route.getApiSuffix());
        view.setApiVersion(route.getApiVersion());
        view.setGenerateMode(definition.getGenerateMode());
        view.setCapabilityJson(JSON.toJSONString(route.getCapability()));
        view.setCapabilities(configured.stream().map(item -> Objects.equals(item.getCode(), definition.getCode()) ? definition : item).toList());
        ModelInvocationResolver.applyResolvedPresentation(view, definition, route);
        if (route.getBillingMode() != null) view.setBillingMode(route.getBillingMode());
        if (route.getBillingRule() != null) view.setBillingRuleJson(JSON.toJSONString(route.getBillingRule()));
        if (route.getCostCredits() != null) view.setCostCredits(route.getCostCredits());
        return view;
    }

    public Map<String, Object> preview(ModelCapabilityDefinition definition, Map<String, Object> parameters) {
        ModelParameterValidator.validateDefinition(definition);
        Map<String, Object> normalized = parameters == null ? new LinkedHashMap<>() : parameters;
        ModelParameterValidator.normalize(definition, normalized);
        return normalized;
    }

    @Transactional(rollbackFor = Exception.class)
    public int save(AidAiModel model, boolean create) {
        model.setCapabilityJson(MediaCapabilityNormalizer.normalizeJson(model.getCapabilityJson()));
        AidAiModel current = create ? null : models.getById(model.getId());
        if (!create && (current == null || !"0".equals(current.getDelFlag()))) fail("模型不存在，请重新打开统一模型配置");
        if (!create && (model.getProviderId() != null && !Objects.equals(model.getProviderId(), current.getProviderId())
                || model.getModelCode() != null && !Objects.equals(model.getModelCode(), current.getModelCode()))) fail("模型稳定标识不可修改");
        if (current != null && model.getCapabilities() != null && model.getBusinessBindings() == null && definitions(model.getId()).isEmpty())
            model.setBusinessBindings(businessBindings.legacyBindings(current));
        Long providerId = model.getProviderId() != null ? model.getProviderId() : current == null ? null : current.getProviderId();
        String identity = model.getRealModelCode() != null ? model.getRealModelCode().trim() : current == null ? null : current.getRealModelCode();
        if (model.getRealModelCode() != null) model.setRealModelCode(identity);
        if (current != null && model.getRealModelCode() != null
                && !Objects.equals(current.getRealModelCode(), identity)) {
            List<ModelCapabilityDefinition> configured = model.getCapabilities() == null
                    ? definitions(model.getId()) : model.getCapabilities();
            for (var capability : configured) for (var route : capability.getBindings()) {
                if (route.getUpstreamModel() == null || Objects.equals(route.getUpstreamModel(), current.getRealModelCode()))
                    route.setUpstreamModel(identity);
            }
            if (model.getCapabilities() == null && !configured.isEmpty()) model.setCapabilities(configured);
        }
        if ((create || model.getRealModelCode() != null) && (identity == null || identity.isBlank())) fail("请填写真实模型标识");
        if (create || current != null && (!Objects.equals(current.getProviderId(), providerId) || !Objects.equals(current.getRealModelCode(), identity))) {
            if (providers.getOne(Wrappers.<AidAiProvider>lambdaQuery().eq(AidAiProvider::getId, providerId).last("FOR UPDATE")) == null) fail("供应商不存在");
            if (models.count(Wrappers.<AidAiModel>lambdaQuery().eq(AidAiModel::getProviderId, providerId)
                    .eq(AidAiModel::getRealModelCode, identity).eq(AidAiModel::getDelFlag, "0")
                    .ne(model.getId() != null, AidAiModel::getId, model.getId())) > 0) fail("真实模型已存在，请为原模型添加能力或协议");
        }
        if (current != null && model.getRealModelCode() != null) {
            List<ModelCapabilityDefinition> stored = definitions(current.getId());
            List<ModelCapabilityDefinition> requested = model.getCapabilities() == null ? stored : model.getCapabilities();
            if (synchronizeInheritedUpstreamModels(current, identity, stored, requested)
                    && model.getCapabilities() == null) model.setCapabilities(requested);
        }
        if (model.getCapabilities() != null) {
            // Imported routes may leave billingMode unset and inherit the model's mode.
            // Preserve that contract when the administrator saves an unchanged model.
            String inheritedBillingMode = model.getBillingMode() != null
                    ? model.getBillingMode() : current == null ? null : current.getBillingMode();
            for (var capability : model.getCapabilities()) {
                if (capability.getBindings() == null) continue;
                for (var route : capability.getBindings()) {
                    if (route != null) {
                        route.setCapability(MediaCapabilityNormalizer.normalize(route.getCapability()));
                        route.setPresentation(MediaCapabilityNormalizer.normalize(route.getPresentation()));
                    }
                    if (route != null && route.getBillingMode() == null)
                        route.setBillingMode(inheritedBillingMode);
                }
            }
            validate(model.getCapabilities());
            for (var alias : aliasesForModel(model.getId())) {
                boolean retained = model.getCapabilities().stream().anyMatch(cap -> Objects.equals(cap.getCode(), alias.getCapabilityCode())
                        && cap.getBindings().stream().anyMatch(route -> Objects.equals(route.getCode(), alias.getBindingCode())));
                if (!retained) fail("历史引用仍使用此能力或协议，不能删除");
            }
            if (current != null && model.getBusinessBindings() == null) {
                for (var business : businessBindings.forModel(model.getId())) {
                    if (model.getCapabilities().stream().noneMatch(cap -> Objects.equals(cap.getCode(), business.getCapabilityCode())
                            && Boolean.TRUE.equals(cap.getEnabled()))) fail("请先调整此能力的业务绑定");
                }
            }
            protocols.validate(model);
            for (var capability : model.getCapabilities()) for (var route : capability.getBindings()) {
                AidAiModel priced = new AidAiModel();
                if (current != null) BeanUtil.copyProperties(current, priced);
                BeanUtil.copyProperties(model, priced, CopyOptions.create().setIgnoreNullValue(true));
                // A route without its own price inherits the model-level rule.  Serializing a
                // missing route rule as JSON null made an unchanged legacy model impossible to save.
                if (route.getBillingMode() != null) priced.setBillingMode(route.getBillingMode());
                if (route.getBillingRule() != null) priced.setBillingRuleJson(JSON.toJSONString(route.getBillingRule()));
                if (route.getCostCredits() != null) priced.setCostCredits(route.getCostCredits());
                if (!Boolean.TRUE.equals(route.getEnabled()) || !Boolean.TRUE.equals(capability.getEnabled())) priced.setStatus("1");
                ModelBillingRuleValidator.validate(priced);
                if (Boolean.TRUE.equals(route.getEnabled()) && Boolean.TRUE.equals(capability.getEnabled())
                        && "0".equals(priced.getStatus()) && !Boolean.TRUE.equals(priced.getIsFree())
                        && !"SKU".equals(priced.getBillingMode()) && (priced.getCostCredits() == null || priced.getCostCredits().signum() < 0)) fail("请配置能力价格");
            }
        }
        List<ModelCapabilityDefinition> overviewDefinitions = model.getCapabilities() != null
                ? model.getCapabilities() : current == null ? List.of() : definitions(current.getId());
        if (!overviewDefinitions.isEmpty()) ModelSupportOverview.apply(model, overviewDefinitions);
        int changed = create ? models.insertAidAiModel(model) : models.updateAidAiModel(model);
        if (changed > 0 && model.getCapabilities() != null) replace(model.getId(), model.getCapabilities(),
                create ? model.getCreateBy() : model.getUpdateBy());
        if (changed > 0 && model.getBusinessBindings() != null) businessBindings.replaceForModel(model.getId(),
                model.getBusinessBindings(), create ? model.getCreateBy() : model.getUpdateBy());
        return changed;
    }

    /** 仅同步仍跟随旧模型标识的协议；调用方显式修改的上游端点保持不变。 */
    static boolean synchronizeInheritedUpstreamModels(AidAiModel current, String newIdentity,
                                                        List<ModelCapabilityDefinition> stored,
                                                        List<ModelCapabilityDefinition> requested) {
        String oldIdentity = current.getRealModelCode() != null && !current.getRealModelCode().isBlank()
                ? current.getRealModelCode().trim()
                : current.getModelCode();
        boolean changed = false;
        for (ModelCapabilityDefinition capability : requested) {
            if (capability.getBindings() == null) continue;
            ModelCapabilityDefinition previous = stored.stream()
                    .filter(item -> Objects.equals(item.getCode(), capability.getCode())).findFirst().orElse(null);
            for (ModelProtocolBinding route : capability.getBindings()) {
                ModelProtocolBinding priorRoute = previous == null || previous.getBindings() == null ? null
                        : previous.getBindings().stream()
                                .filter(item -> Objects.equals(item.getCode(), route.getCode())).findFirst().orElse(null);
                String upstream = route.getUpstreamModel() == null ? null : route.getUpstreamModel().trim();
                if (priorRoute != null && !Objects.equals(upstream,
                        priorRoute.getUpstreamModel() == null ? null : priorRoute.getUpstreamModel().trim())) continue;
                if ((Objects.equals(upstream, oldIdentity) || Objects.equals(upstream, current.getModelCode()))
                        && !Objects.equals(upstream, newIdentity)) {
                    route.setUpstreamModel(newIdentity);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public void validate(List<ModelCapabilityDefinition> definitions) {
        if (definitions == null || definitions.isEmpty() || definitions.size() > 100) fail("请配置模型能力");
        Set<String> codes = new HashSet<>();
        long defaults = 0;
        for (ModelCapabilityDefinition definition : definitions) {
            ModelParameterValidator.validateDefinition(definition);
            Set<String> parameterPaths = ModelParameterValidator.parameterPaths(definition);
            checkCode(definition.getCode());
            if (!codes.add(definition.getCode())) fail("能力编码重复");
            if (definition.getGenerateMode() == null || definition.getGenerateMode().isBlank()) fail("请选择能力类型");
            if (Boolean.TRUE.equals(definition.getDefaultCapability()) && Boolean.TRUE.equals(definition.getEnabled())) defaults++;
            Set<String> routeCodes = new HashSet<>();
            int defaultRoutes = 0;
            if (definition.getBindings() == null) fail("请配置调用协议");
            for (ModelProtocolBinding binding : definition.getBindings()) {
                if (binding == null) fail("调用协议无效");
                checkCode(binding.getCode());
                if (!routeCodes.add(binding.getCode())) fail("协议编码重复");
                if (binding.getProtocol() == null || binding.getProtocol().isBlank()) fail("请选择调用协议");
                if (!Set.of("FIXED", "SKU").contains(binding.getBillingMode() == null ? "" : binding.getBillingMode())) fail("请选择计费方式");
                if (binding.getApiSuffix() != null && !binding.getApiSuffix().isBlank()) {
                    try { binding.setApiSuffix(ProviderEndpointUtils.normalizeSubmitPath(binding.getApiSuffix())); }
                    catch (IllegalArgumentException ex) { fail("接口路径无效"); }
                }
                if (binding.getTaskQuerySuffix() != null && !binding.getTaskQuerySuffix().isBlank()) {
                    try { binding.setTaskQuerySuffix(ProviderEndpointUtils.normalizeTaskQueryTemplate(binding.getTaskQuerySuffix())); }
                    catch (IllegalArgumentException ex) { fail("查询路径无效"); }
                }
                if (Boolean.TRUE.equals(binding.getDefaultBinding()) && Boolean.TRUE.equals(binding.getEnabled())) defaultRoutes++;
                Set<String> targets = new HashSet<>();
                if (binding.getMappings() != null) for (ModelProtocolBinding.FieldMapping mapping : binding.getMappings()) {
                    if (mapping == null || !parameterPaths.contains(mapping.getSource()) || !validPath(mapping.getSource())
                            || !validPath(mapping.getTarget()) || !targets.add(mapping.getTarget())) fail("参数映射无效");
                    if (targets.stream().anyMatch(target -> !target.equals(mapping.getTarget())
                            && (target.startsWith(mapping.getTarget() + ".") || mapping.getTarget().startsWith(target + "."))))
                        fail("映射目标的父字段与子字段不能重复配置");
                    if (mapping.getType() != null && !Set.of("preserve", "string", "number", "integer", "boolean", "object", "array").contains(mapping.getType())) fail("映射类型不支持");
                }
            }
            if (Boolean.TRUE.equals(definition.getEnabled()) && defaultRoutes != 1) fail("请选择默认协议");
        }
        if (defaults != 1) fail("请选择默认能力");
    }

    @Transactional(rollbackFor = Exception.class)
    public void replace(Long modelId, List<ModelCapabilityDefinition> definitions, String actor) {
        validate(definitions);
        bindings.delete(Wrappers.<AidAiModelProtocolBinding>lambdaQuery().eq(AidAiModelProtocolBinding::getModelId, modelId));
        capabilities.delete(Wrappers.<AidAiModelCapability>lambdaQuery().eq(AidAiModelCapability::getModelId, modelId));
        int index = 0;
        for (ModelCapabilityDefinition definition : definitions) {
            ModelCapabilityDefinition stored = JSON.parseObject(JSON.toJSONString(definition), ModelCapabilityDefinition.class);
            stored.setBindings(null);
            AidAiModelCapability row = new AidAiModelCapability();
            row.setModelId(modelId);
            row.setCapabilityCode(definition.getCode());
            row.setGenerateMode(definition.getGenerateMode());
            row.setSortOrder(index++);
            row.setDefinitionJson(JSON.toJSONString(stored));
            row.setCreateBy(actor);
            row.setCreateTime(DateUtils.getNowDate());
            capabilities.insert(row);
            int routeIndex = 0;
            for (ModelProtocolBinding route : definition.getBindings()) {
                AidAiModelProtocolBinding item = new AidAiModelProtocolBinding();
                item.setModelId(modelId);
                item.setCapabilityCode(definition.getCode());
                item.setBindingCode(route.getCode());
                item.setProtocol(route.getProtocol());
                item.setDefinitionJson(JSON.toJSONString(route));
                item.setSortOrder(routeIndex++);
                item.setCreateBy(actor);
                item.setCreateTime(DateUtils.getNowDate());
                bindings.insert(item);
            }
        }
    }

    private static boolean validPath(String path) {
        return path != null && path.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*")
                && !path.contains("__proto__") && !path.contains("constructor") && !path.contains("prototype");
    }
    private static void checkCode(String code) { if (code == null || !code.matches("[a-z][a-z0-9_-]{0,95}")) fail("配置编码无效"); }
    private static void fail(String message) { log.info("模型能力配置无效: {}", message); throw new ServiceException(message); }
}
