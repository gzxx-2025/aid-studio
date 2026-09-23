package com.aid.model.definition;

import com.aid.aid.domain.AidAiBusinessModelBinding;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelCapability;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.mapper.AidAiBusinessModelBindingMapper;
import com.aid.aid.mapper.AidAiModelCapabilityMapper;
import com.aid.aid.service.IAidAiModelFuncConfigService;
import com.aid.aid.service.IAidAiModelService;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.orchestration.IAiOrchestrationService;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 维护业务功能对模型能力的绑定。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelBusinessBindingService {
    private static final Set<String> INPUT_ADAPTIVE_IMAGE_FUNCTIONS = Set.of(
            "main_character_image", "main_scene_image", "main_prop_image",
            "main_storyboard_image", "image_edit");

    private final AidAiBusinessModelBindingMapper bindings;
    private final AidAiModelCapabilityMapper capabilities;
    private final IAidAiModelService models;
    private final IAidAiModelFuncConfigService functions;
    private final IAiOrchestrationService orchestration;
    private final ModelFunctionBindingReconciler reconciler;

    public List<AidAiBusinessModelBinding> forModel(Long modelId) {
        return bindings.selectList(Wrappers.<AidAiBusinessModelBinding>lambdaQuery()
                .eq(AidAiBusinessModelBinding::getModelId, modelId).orderByAsc(AidAiBusinessModelBinding::getSortOrder));
    }

    public List<AidAiBusinessModelBinding> forFunction(String funcCode) {
        return bindings.selectList(Wrappers.<AidAiBusinessModelBinding>lambdaQuery()
                .eq(AidAiBusinessModelBinding::getFuncCode, funcCode).orderByAsc(AidAiBusinessModelBinding::getSortOrder));
    }

    @Transactional(rollbackFor = Exception.class)
    public int saveFunction(AidAiModelFuncConfig function, boolean creating, String actor) {
        if (!creating) {
            var current = functions.getOne(Wrappers.<AidAiModelFuncConfig>lambdaQuery()
                    .eq(AidAiModelFuncConfig::getId, function.getId()).eq(AidAiModelFuncConfig::getDelFlag, "0").last("FOR UPDATE"));
            if (current == null) fail("业务功能不存在");
            if (!Objects.equals(current.getFuncCode(), function.getFuncCode())) fail("业务功能编码不可修改");
        }
        orchestration.prepareFunctionUpdate(function, actor);
        reconciler.reconcile(function, actor);
        function.setUpdateBy(actor);
        return creating ? functions.insertAidAiModelFuncConfig(function) : functions.updateAidAiModelFuncConfig(function);
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteFunctions(Long[] ids) {
        if (ids == null || ids.length == 0) return 0;
        var selected = functions.list(Wrappers.<AidAiModelFuncConfig>lambdaQuery()
                .in(AidAiModelFuncConfig::getId, List.of(ids)).eq(AidAiModelFuncConfig::getDelFlag, "0")
                .orderByAsc(AidAiModelFuncConfig::getId).last("FOR UPDATE"));
        orchestration.validateFunctionConfigsRemovable(ids);
        int result = functions.deleteAidAiModelFuncConfigByIds(ids);
        if (result > 0 && !selected.isEmpty()) bindings.delete(Wrappers.<AidAiBusinessModelBinding>lambdaQuery()
                .in(AidAiBusinessModelBinding::getFuncCode, selected.stream().map(AidAiModelFuncConfig::getFuncCode).toList()));
        return result;
    }

    public List<AidAiBusinessModelBinding> legacyBindings(AidAiModel model) {
        List<AidAiBusinessModelBinding> result = new ArrayList<>();
        Set<String> availableCapabilities = LegacyModelDefinitionConverter.convert(model).stream()
                .filter(definition -> Boolean.TRUE.equals(definition.getEnabled()))
                .map(ModelCapabilityDefinition::getCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (AidAiModelFuncConfig function : functions.list(Wrappers.<AidAiModelFuncConfig>lambdaQuery().eq(AidAiModelFuncConfig::getDelFlag, "0"))) {
            if (function.getModelIds() == null || !JSON.parseArray(function.getModelIds(), Long.class).contains(model.getId())) continue;
            String defaultCode = LegacyModelDefinitionConverter.businessCode(
                    model, function.getFuncCode(), function.getGenerateMode());
            if (INPUT_ADAPTIVE_IMAGE_FUNCTIONS.contains(function.getFuncCode())
                    && availableCapabilities.contains("text_to_image")
                    && availableCapabilities.contains("image_to_image")) {
                String adaptiveDefault = Set.of("text_to_image", "image_to_image").contains(defaultCode)
                        ? defaultCode : "image_edit".equals(function.getFuncCode())
                                ? "image_to_image" : "text_to_image";
                result.add(legacyBinding(model.getId(), function.getFuncCode(), "text_to_image",
                        "text_to_image".equals(adaptiveDefault)));
                result.add(legacyBinding(model.getId(), function.getFuncCode(), "image_to_image",
                        "image_to_image".equals(adaptiveDefault)));
            } else {
                result.add(legacyBinding(model.getId(), function.getFuncCode(), defaultCode, true));
            }
        }
        return result;
    }

    private AidAiBusinessModelBinding legacyBinding(Long modelId, String funcCode,
            String capabilityCode, boolean defaultCapability) {
        AidAiBusinessModelBinding row = new AidAiBusinessModelBinding();
        row.setModelId(modelId);
        row.setFuncCode(funcCode);
        row.setCapabilityCode(capabilityCode);
        row.setDefaultCapability(defaultCapability);
        return row;
    }

    public String capability(Long modelId, String funcCode, String requested) {
        AidAiModelFuncConfig function = functions.getOne(Wrappers.<AidAiModelFuncConfig>lambdaQuery()
                .eq(AidAiModelFuncConfig::getFuncCode, funcCode).eq(AidAiModelFuncConfig::getDelFlag, "0").eq(AidAiModelFuncConfig::getStatus, "0"));
        if (function == null || function.getModelIds() == null || !JSON.parseArray(function.getModelIds(), Long.class).contains(modelId)) fail("模型不在业务池中");
        List<AidAiBusinessModelBinding> permitted = forFunction(funcCode).stream()
                .filter(b -> Objects.equals(b.getModelId(), modelId)).toList();
        if (permitted.isEmpty()) {
            if (capabilities.selectCount(Wrappers.<AidAiModelCapability>lambdaQuery().eq(AidAiModelCapability::getModelId, modelId)) > 0)
                fail("请先为业务绑定模型能力");
            if (requested != null && !requested.isBlank()) return requested;
            AidAiModel model = models.getById(modelId);
            if (model == null) fail("模型不存在");
            return LegacyModelDefinitionConverter.businessCode(
                    model, function.getFuncCode(), function.getGenerateMode());
        }
        if (requested != null && !requested.isBlank()) {
            if (permitted.stream().noneMatch(b -> requested.equals(b.getCapabilityCode()))) fail("业务未绑定此能力");
            return requested;
        }
        List<AidAiBusinessModelBinding> defaults = permitted.stream().filter(b -> Boolean.TRUE.equals(b.getDefaultCapability())).toList();
        if (defaults.size() != 1) fail("请明确调用能力");
        return defaults.get(0).getCapabilityCode();
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceForModel(Long modelId, List<AidAiBusinessModelBinding> requested, String actor) {
        if (requested == null) return;
        AidAiModel model = models.getById(modelId);
        List<AidAiBusinessModelBinding> existing = forModel(modelId);
        Set<String> functionCodes = new LinkedHashSet<>();
        existing.forEach(b -> functionCodes.add(b.getFuncCode()));
        legacyBindings(model).forEach(b -> functionCodes.add(b.getFuncCode()));
        Set<String> unique = new LinkedHashSet<>();
        List<AidAiModelCapability> rows = capabilities.selectList(Wrappers.<AidAiModelCapability>lambdaQuery().eq(AidAiModelCapability::getModelId, modelId));
        Set<String> allowed = new LinkedHashSet<>();
        for (AidAiModelCapability row : rows) {
            ModelCapabilityDefinition d = JSON.parseObject(row.getDefinitionJson(), ModelCapabilityDefinition.class);
            if (Boolean.TRUE.equals(d.getEnabled())) allowed.add(d.getCode());
        }
        for (AidAiBusinessModelBinding binding : requested) {
            if (binding == null || binding.getFuncCode() == null || !allowed.contains(binding.getCapabilityCode())) fail("业务能力绑定无效");
            if (!unique.add(binding.getFuncCode() + "/" + binding.getCapabilityCode())) fail("业务能力绑定重复");
            functionCodes.add(binding.getFuncCode());
            if (binding.getDefaultsJson() != null && !binding.getDefaultsJson().isBlank()) {
                var definition = rows.stream().filter(row -> Objects.equals(row.getCapabilityCode(), binding.getCapabilityCode()))
                        .map(row -> JSON.parseObject(row.getDefinitionJson(), ModelCapabilityDefinition.class)).findFirst().orElseThrow();
                ModelParameterValidator.validateBusinessDefaults(definition, JSON.parseObject(binding.getDefaultsJson()));
            }
        }
        for (String code : functionCodes.stream().sorted().toList()) {
            AidAiModelFuncConfig function = functions.getOne(Wrappers.<AidAiModelFuncConfig>lambdaQuery()
                    .eq(AidAiModelFuncConfig::getFuncCode, code).eq(AidAiModelFuncConfig::getDelFlag, "0").last("FOR UPDATE"));
            if (function == null || !Objects.equals(function.getModelType(), model.getModelType())) fail("业务模型类型不符");
            List<Long> ids = function.getModelIds() == null ? new ArrayList<>() : new ArrayList<>(JSON.parseArray(function.getModelIds(), Long.class));
            boolean selected = requested.stream().anyMatch(b -> code.equals(b.getFuncCode()));
            if (selected && requested.stream().filter(b -> code.equals(b.getFuncCode()) && Boolean.TRUE.equals(b.getDefaultCapability())).count() != 1) fail("请选择业务默认能力");
            boolean membershipChanged = selected ? !ids.contains(modelId) : ids.contains(modelId);
            if (selected && membershipChanged) ids.add(modelId);
            if (!selected && membershipChanged) ids.remove(modelId);
            if ("0".equals(function.getStatus()) && ids.isEmpty()) fail("业务至少绑定一模型");
            // 只修改同一模型在池内的能力选择时，模型池成员并未变化。历史池可能仍带有待治理的
            // 失效模型 ID，不应因为本次能力保存而重验、重写整个池；真正新增或移出成员时仍走
            // 完整的编排校验和引用迁移，不能借此绕过池约束。
            if (!membershipChanged) continue;
            function.setModelIds(JSON.toJSONString(ids));
            orchestration.prepareFunctionUpdate(function, actor);
            function.setUpdateBy(actor);
            function.setUpdateTime(DateUtils.getNowDate());
            functions.updateById(function);
        }
        bindings.delete(Wrappers.<AidAiBusinessModelBinding>lambdaQuery().eq(AidAiBusinessModelBinding::getModelId, modelId));
        int index = 0;
        for (AidAiBusinessModelBinding binding : requested) {
            AidAiBusinessModelBinding row = new AidAiBusinessModelBinding();
            row.setModelId(modelId); row.setFuncCode(binding.getFuncCode()); row.setCapabilityCode(binding.getCapabilityCode());
            row.setDefaultCapability(binding.getDefaultCapability());
            row.setDefaultsJson(binding.getDefaultsJson()); row.setSortOrder(index++);
            row.setCreateBy(actor); row.setCreateTime(DateUtils.getNowDate()); bindings.insert(row);
        }
    }

    private static void fail(String message) { log.info("业务模型绑定失败: {}", message); throw new ServiceException(message); }
}
