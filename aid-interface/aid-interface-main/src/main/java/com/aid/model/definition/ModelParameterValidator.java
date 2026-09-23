package com.aid.model.definition;

import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.aid.aid.domain.model.ModelParameterRule;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** 校验可视化参数定义并执行声明式条件约束。 */
@Slf4j
public final class ModelParameterValidator {
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array");
    private static final Set<String> CONDITIONS = Set.of("present", "absent", "eq", "neq", "in", "gt", "gte", "lt", "lte", "count_gt", "count_gte", "count_lt", "count_lte");
    private static final Set<String> ACTIONS = Set.of("required", "forbidden", "fixed", "minimum", "maximum", "choices", "maximum_sum");
    private static final Set<String> RESERVED = Set.of("__proto__", "prototype", "constructor", "apiKey", "apiSecret", "authorization", "userId", "operatorAdminId",
            "modelName", "capabilityCode", "projectId", "episodeId", "recordId", "category", "bizTaskId", "bizTaskType",
            "parentTaskId", "callId", "taskPromptDigest", "resolvedReferenceVideos");

    private ModelParameterValidator() { }

    public static void validateDefinition(ModelCapabilityDefinition definition) {
        if (definition == null) fail("能力不能为空");
        Set<String> paths = new HashSet<>();
        validateFields(definition.getParameters(), "", paths, 0);
        paths.addAll(ModelMaterialStatistics.FIELDS);
        if (definition.getRules() == null) {
            validateBusinessDefaults(definition, Map.of());
            return;
        }
        if (definition.getRules().size() > 100) fail("条件规则过多");
        for (ModelParameterRule rule : definition.getRules()) {
            if (rule == null || rule.getMatch() == null || !Set.of("all", "any").contains(rule.getMatch())
                    || rule.getConditions() == null || rule.getConditions().isEmpty()
                    || rule.getActions() == null || rule.getActions().isEmpty()) fail("条件规则不完整");
            for (ModelParameterRule.Condition condition : rule.getConditions()) {
                validateCondition(condition, paths, 0);
                validateConditionType(definition, condition);
            }
            for (ModelParameterRule.Action action : rule.getActions()) {
                if (action == null || !paths.contains(action.getField())
                        || action.getOperator() == null || !ACTIONS.contains(action.getOperator())) fail("约束字段无效");
                if (Set.of("minimum", "maximum").contains(action.getOperator())) decimal(action.getValue());
                if (ModelMaterialStatistics.isField(action.getField()) && !Set.of("minimum", "maximum").contains(action.getOperator())) fail("素材统计仅支持范围约束");
                if ("maximum_sum".equals(action.getOperator())) {
                    decimal(action.getValue());
                    if (!ModelMaterialStatistics.isField(action.getValueField())) fail("请选择合计统计项");
                    ModelParameter field = fieldAt(definition.getParameters(), action.getField());
                    if (field == null || !Set.of("number", "integer").contains(field.getType())) fail("合计约束必须作用于数字参数");
                }
                if ("choices".equals(action.getOperator()) && !(action.getValue() instanceof List<?>)) fail("选项必须为列表");
                if (ModelMaterialStatistics.usesStatistics(rule) && "fixed".equals(action.getOperator())) fail("素材统计规则只校验参数，不能改写已核验的请求");
                validateActionValue(definition, action);
            }
            validateActionConflicts(rule.getActions());
        }
        Map<String, List<ModelParameterRule.Action>> matchingRules = new LinkedHashMap<>();
        for (var rule : definition.getRules()) {
            String key = rule.getMatch() + JSON.toJSONString(rule.getConditions());
            matchingRules.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(rule.getActions());
        }
        matchingRules.values().forEach(ModelParameterValidator::validateActionConflicts);
        validateBusinessDefaults(definition, Map.of());
    }

    private static void validateActionValue(ModelCapabilityDefinition definition, ModelParameterRule.Action action) {
        ModelParameter field = fieldAt(definition.getParameters(), action.getField());
        if (field == null) return;
        if (Set.of("minimum", "maximum").contains(action.getOperator()) && Set.of("object", "boolean").contains(field.getType()))
            failField(action.getField(), "此类型不支持范围约束");
        if ("fixed".equals(action.getOperator())) {
            if (action.getValue() == null) fail("固定值不能为空");
            checkValue(field, action.getValue(), action.getField());
        }
        if ("choices".equals(action.getOperator())) {
            List<?> choices = (List<?>) action.getValue();
            if (choices.isEmpty()) fail("允许的选项不能为空");
            ModelParameter item = "array".equals(field.getType()) ? field.getItems() : field;
            for (Object choice : choices) checkValue(item, choice, action.getField());
        }
    }

    private static void validateConditionType(ModelCapabilityDefinition definition, ModelParameterRule.Condition condition) {
        if (condition.getMatch() != null) {
            condition.getConditions().forEach(child -> validateConditionType(definition, child));
            return;
        }
        ModelParameter field = fieldAt(definition.getParameters(), condition.getField());
        if (field == null) return;
        String operator = condition.getOperator();
        if (Set.of("gt", "gte", "lt", "lte").contains(operator) && !Set.of("integer", "number").contains(field.getType()))
            failField(condition.getField(), "此字段应使用数量条件或相等条件");
        if (operator.startsWith("count_") && !Set.of("array", "string").contains(field.getType()))
            failField(condition.getField(), "数量条件仅适用于列表或文本");
    }

    private static void validateActionConflicts(List<ModelParameterRule.Action> actions) {
        Map<String, List<ModelParameterRule.Action>> fields = new LinkedHashMap<>();
        actions.forEach(action -> fields.computeIfAbsent(action.getField(), ignored -> new ArrayList<>()).add(action));
        fields.forEach((field, constraints) -> {
            boolean required = constraints.stream().anyMatch(action -> "required".equals(action.getOperator()));
            boolean forbidden = constraints.stream().anyMatch(action -> "forbidden".equals(action.getOperator()));
            List<Object> fixed = constraints.stream().filter(action -> "fixed".equals(action.getOperator())).map(ModelParameterRule.Action::getValue).toList();
            if (forbidden && (required || fixed.stream().anyMatch(value -> !empty(value)))) failField(field, "必填、固定与禁用规则冲突");
            if (!fixed.isEmpty() && fixed.stream().anyMatch(value -> !same(value, fixed.get(0)))) failField(field, "固定参数规则冲突");
            BigDecimal minimum = constraints.stream().filter(action -> "minimum".equals(action.getOperator())).map(action -> decimal(action.getValue())).max(BigDecimal::compareTo).orElse(null);
            BigDecimal maximum = constraints.stream().filter(action -> "maximum".equals(action.getOperator())).map(action -> decimal(action.getValue())).min(BigDecimal::compareTo).orElse(null);
            if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) failField(field, "条件范围冲突");
            var choiceSets = constraints.stream().filter(action -> "choices".equals(action.getOperator())).map(action -> (List<?>) action.getValue()).toList();
            if (!choiceSets.isEmpty() && choiceSets.get(0).stream().noneMatch(value -> choiceSets.stream().allMatch(choices -> allowed(value, choices)))) failField(field, "条件选项没有交集");
            for (Object value : fixed) {
                if (minimum != null && magnitude(value).compareTo(minimum) < 0 || maximum != null && magnitude(value).compareTo(maximum) > 0
                        || choiceSets.stream().anyMatch(choices -> !allowed(value, choices))) failField(field, "固定值不符合条件约束");
            }
        });
    }

    private static void validateCondition(ModelParameterRule.Condition condition, Set<String> paths, int depth) {
        if (condition == null || depth > 8) fail("条件组层级无效");
        if (condition.getMatch() != null) {
            if (!Set.of("all", "any").contains(condition.getMatch()) || condition.getConditions() == null
                    || condition.getConditions().isEmpty() || condition.getConditions().size() > 100) fail("条件组不完整");
            for (var child : condition.getConditions()) validateCondition(child, paths, depth + 1);
            return;
        }
        if (!paths.contains(condition.getField()) || condition.getOperator() == null || !CONDITIONS.contains(condition.getOperator())) fail("条件字段无效");
        String operator = condition.getOperator();
        if (operator.startsWith("count_") || Set.of("gt", "gte", "lt", "lte").contains(operator)) decimal(condition.getValue());
        if ("in".equals(operator) && !(condition.getValue() instanceof List<?>)) fail("条件选项必须为列表");
    }

    public static Set<String> parameterPaths(ModelCapabilityDefinition definition) {
        Set<String> paths = new HashSet<>();
        validateFields(definition.getParameters(), "", paths, 0);
        return paths;
    }

    /** 默认参数是声明字段的部分值；必填条件留到实际请求合并后检查。 */
    public static void validateBusinessDefaults(ModelCapabilityDefinition definition, Map<String, Object> values) {
        if (values == null) return;
        ModelCapabilityDefinition partial = JSON.parseObject(JSON.toJSONString(definition), ModelCapabilityDefinition.class);
        optionalFields(partial.getParameters());
        partial.setRules(safe(partial.getRules()).stream().map(rule -> {
            rule.setActions(rule.getActions().stream().filter(action -> !"required".equals(action.getOperator())).toList());
            return rule;
        }).filter(rule -> !rule.getActions().isEmpty()).toList());
        declaredValues(partial.getParameters(), values, "");
        normalize(partial, JSON.parseObject(JSON.toJSONString(values)), true);
    }

    private static void optionalFields(List<ModelParameter> fields) {
        for (var field : safe(fields)) {
            field.setRequired(false); optionalFields(field.getProperties());
            if (field.getItems() != null) optionalFields(List.of(field.getItems()));
        }
    }

    private static ModelParameter fieldAt(List<ModelParameter> fields, String path) {
        ModelParameter result = null;
        for (String part : path.split("\\.")) {
            result = safe(fields).stream().filter(field -> Objects.equals(field.getName(), part)).findFirst().orElse(null);
            if (result == null) return null;
            fields = result.getProperties();
        }
        return result;
    }

    private static void declaredValues(List<ModelParameter> fields, Map<?, ?> values, String prefix) {
        for (var entry : values.entrySet()) {
            ModelParameter field = safe(fields).stream().filter(item -> Objects.equals(item.getName(), entry.getKey())).findFirst().orElse(null);
            if (field == null) failField(prefix + entry.getKey(), "默认参数未在能力中声明");
            if (entry.getValue() instanceof Map<?, ?> nested && "object".equals(field.getType())) declaredValues(field.getProperties(), nested, prefix + field.getName() + ".");
        }
    }

    private static void validateFields(List<ModelParameter> fields, String prefix, Set<String> paths, int depth) {
        if (fields == null) return;
        if (depth > 16 || fields.size() > 100) fail("参数层级过多");
        for (ModelParameter field : fields) {
            if (field == null || field.getName() == null || !field.getName().matches("[a-zA-Z][a-zA-Z0-9_]*")
                    || RESERVED.contains(field.getName()) || field.getType() == null
                    || !TYPES.contains(field.getType())) fail("参数定义无效");
            String path = prefix + field.getName();
            if (!paths.add(path)) fail("参数名称重复");
            if (field.getMinimum() != null && field.getMaximum() != null
                    && field.getMinimum().compareTo(field.getMaximum()) > 0) fail("参数范围无效");
            if (field.getStep() != null && field.getStep().signum() <= 0) fail("参数步长无效");
            if (field.getMaterialRole() != null && !Set.of("first_frame", "last_frame", "reference_image", "reference_video", "reference_audio", "mask").contains(field.getMaterialRole())) fail("素材角色无效");
            if (field.getMaterialRole() != null && !Set.of("string", "object", "array").contains(field.getType())) fail("素材参数类型无效");
            if (field.getMaterialRole() != null && !Set.of("reference_video", "reference_audio").contains(field.getMaterialRole())
                    && (field.getMinDurationSeconds() != null || field.getMaxDurationSeconds() != null || field.getMaxTotalDurationSeconds() != null)) fail("图片不支持时长限制");
            for (BigDecimal bound : new BigDecimal[] { field.getMinDurationSeconds(), field.getMaxDurationSeconds(), field.getMaxTotalDurationSeconds(), field.getMaxFileSizeMb() })
                if (bound != null && (field.getMaterialRole() == null || bound.signum() < 0)) fail("请为素材配置合法限制");
            if (field.getMinDurationSeconds() != null && field.getMaxDurationSeconds() != null && field.getMinDurationSeconds().compareTo(field.getMaxDurationSeconds()) > 0) fail("素材时长范围无效");
            if (field.getMaxFileSizeBytes() != null && (field.getMaterialRole() == null || field.getMaxFileSizeBytes() <= 0)) fail("素材字节上限无效");
            if (field.getClipDurationSeconds() != null && (!Set.of("reference_video", "reference_audio").contains(field.getMaterialRole())
                    || field.getClipDurationSeconds().signum() <= 0)) fail("素材裁切时长无效");
            if (field.getFormats() != null && !field.getFormats().isEmpty()) {
                if (field.getMaterialRole() == null || field.getFormats().stream().anyMatch(format -> format == null || !format.matches("[A-Za-z0-9]+"))) fail("素材格式无效");
            }
            if ((field.getMinimum() != null || field.getMaximum() != null)
                    && Set.of("boolean", "object").contains(field.getType())) fail("此类型不支持范围");
            if (field.getStep() != null && !Set.of("number", "integer").contains(field.getType())) fail("此类型不支持步长");
            if (Set.of("integer", "string", "array").contains(field.getType())) {
                for (BigDecimal bound : new BigDecimal[] { field.getMinimum(), field.getMaximum(), field.getStep() })
                    if (bound != null && bound.stripTrailingZeros().scale() > 0) fail("整数或数量范围必须是整数");
            }
            if (Set.of("string", "array").contains(field.getType()) && field.getMinimum() != null && field.getMinimum().signum() < 0) fail("长度和数量不能为负数");
            if ("object".equals(field.getType())) validateFields(field.getProperties(), path + ".", paths, depth + 1);
            if ("array".equals(field.getType())) {
                if (field.getItems() == null) fail("请配置列表项");
                // 列表子项使用独立作用域，不能配置运行时无法寻址的 foo.item 条件。
                validateFields(List.of(field.getItems()), "", new HashSet<>(), depth + 1);
            }
            if (field.getDefaultValue() != null) checkValue(field, field.getDefaultValue(), path);
        }
    }

    public static void normalize(ModelCapabilityDefinition definition, Map<String, Object> parameters) {
        normalize(definition, parameters, false);
    }

    public static ModelCapabilityDefinition withDeferredPrompt(ModelCapabilityDefinition definition) {
        ModelCapabilityDefinition partial = JSON.parseObject(JSON.toJSONString(definition), ModelCapabilityDefinition.class);
        partial.setParameters(safe(partial.getParameters()).stream().filter(field -> !"prompt".equals(field.getName())).toList());
        partial.setRules(safe(partial.getRules()).stream()
                .filter(rule -> rule.getConditions().stream().noneMatch(ModelParameterValidator::usesPrompt))
                .peek(rule -> rule.setActions(rule.getActions().stream().filter(action -> !"prompt".equals(action.getField())).toList()))
                .filter(rule -> !rule.getActions().isEmpty()).toList());
        return partial;
    }

    private static boolean usesPrompt(ModelParameterRule.Condition condition) {
        return "prompt".equals(condition.getField()) || safe(condition.getConditions()).stream().anyMatch(ModelParameterValidator::usesPrompt);
    }

    public static void normalize(ModelCapabilityDefinition definition, Map<String, Object> parameters, boolean metadataPending) {
        if (definition == null) return;
        Map<String, Object> requestedValues = JSON.parseObject(JSON.toJSONString(parameters));
        applyDefaults(definition.getParameters(), parameters);
        Map<String, Object> fixed = new LinkedHashMap<>();
        List<ModelParameterRule.Action> actions = new ArrayList<>();
        for (ModelParameterRule rule : safe(definition.getRules())) {
            if (metadataPending && ModelMaterialStatistics.usesStatistics(rule)) continue;
            boolean applies = "any".equals(rule.getMatch())
                    ? rule.getConditions().stream().anyMatch(c -> matches(c, parameters))
                    : rule.getConditions().stream().allMatch(c -> matches(c, parameters));
            if (applies) actions.addAll(rule.getActions());
        }
        for (ModelParameterRule.Action action : actions) {
            if (!"fixed".equals(action.getOperator())) continue;
            if (fixed.containsKey(action.getField()) && !same(fixed.get(action.getField()), action.getValue())) fail("固定参数规则冲突");
            fixed.put(action.getField(), action.getValue());
            Object requested = read(requestedValues, action.getField());
            if (requested != null && !same(requested, action.getValue())) failField(action.getField(), "参数不符合约束");
            write(parameters, action.getField(), action.getValue());
        }
        for (ModelParameterRule.Action action : actions) {
            Object value = read(parameters, action.getField());
            switch (action.getOperator()) {
                case "required" -> { if (empty(value)) failField(action.getField(), "缺少必填参数"); }
                case "forbidden" -> { if (!empty(value)) failField(action.getField(), "参数组合不支持"); }
                case "minimum" -> { if (value != null && magnitude(value).compareTo(decimal(action.getValue())) < 0) failField(action.getField(), minimumMessage(action.getValue())); }
                case "maximum" -> { if (value != null && magnitude(value).compareTo(decimal(action.getValue())) > 0) failField(action.getField(), maximumMessage(action.getValue())); }
                case "maximum_sum" -> {
                    Object other = read(parameters, action.getValueField());
                    if (other == null) fail("缺少素材统计，请先完成元数据校验");
                    if (value != null && decimal(value).signum() >= 0 && decimal(value).add(decimal(other)).compareTo(decimal(action.getValue())) > 0)
                        failField(action.getField(), "输入与输出合计超过允许上限，最大合计为 " + displayConstraint(action.getValue()));
                }
                case "choices" -> { if (value != null && !allowed(value, (List<?>) action.getValue())) failField(action.getField(), choicesMessage((List<?>) action.getValue())); }
                default -> { }
            }
        }
        checkFields(definition.getParameters(), parameters, "");
    }

    private static boolean matches(ModelParameterRule.Condition c, Map<String, Object> parameters) {
        if (c.getMatch() != null) return "any".equals(c.getMatch())
                ? c.getConditions().stream().anyMatch(child -> matches(child, parameters))
                : c.getConditions().stream().allMatch(child -> matches(child, parameters));
        Object value = read(parameters, c.getField());
        String op = c.getOperator();
        return switch (op) {
            case "present" -> !empty(value);
            case "absent" -> empty(value);
            case "eq" -> same(value, c.getValue());
            case "neq" -> !same(value, c.getValue());
            case "in" -> c.getValue() instanceof List<?> list && list.stream().anyMatch(v -> same(value, v));
            default -> {
                if (value == null) yield false;
                int comparison = (op.startsWith("count_") ? magnitude(value) : decimal(value)).compareTo(decimal(c.getValue()));
                yield switch (op.replace("count_", "")) {
                    case "gt" -> comparison > 0;
                    case "gte" -> comparison >= 0;
                    case "lt" -> comparison < 0;
                    case "lte" -> comparison <= 0;
                    default -> false;
                };
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static void applyDefaults(List<ModelParameter> fields, Map<String, Object> data) {
        for (ModelParameter field : safe(fields)) {
            if (data.get(field.getName()) == null && field.getDefaultValue() != null)
                data.put(field.getName(), JSON.parse(JSON.toJSONString(field.getDefaultValue())));
            Object value = data.get(field.getName());
            if (value instanceof Map<?, ?> map && "object".equals(field.getType())) applyDefaults(field.getProperties(), (Map<String, Object>) map);
            if (value instanceof List<?> list && "array".equals(field.getType()) && field.getItems() != null)
                for (Object item : list) applyItemDefaults(field.getItems(), item);
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyItemDefaults(ModelParameter schema, Object value) {
        if (value instanceof Map<?, ?> map && "object".equals(schema.getType())) applyDefaults(schema.getProperties(), (Map<String, Object>) map);
        if (value instanceof List<?> list && "array".equals(schema.getType()) && schema.getItems() != null)
            for (Object item : list) applyItemDefaults(schema.getItems(), item);
    }

    private static void checkFields(List<ModelParameter> fields, Map<?, ?> data, String prefix) {
        for (ModelParameter field : safe(fields)) {
            Object value = data.get(field.getName());
            String path = prefix + field.getName();
            if (Boolean.TRUE.equals(field.getRequired()) && empty(value)) failField(path, "缺少必填参数");
            if (value != null) checkValue(field, value, path);
        }
    }

    private static void checkValue(ModelParameter field, Object value, String path) {
        boolean valid = switch (field.getType()) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number && decimal(value).stripTrailingZeros().scale() <= 0;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> false;
        };
        if (!valid) failField(path, "参数类型错误，应为" + typeLabel(field.getType()));
        if (field.getChoices() != null && !field.getChoices().isEmpty()
                && !allowed(value, field.getChoices())) failField(path, choicesMessage(field.getChoices()));
        if (field.getMinimum() != null && magnitude(value).compareTo(field.getMinimum()) < 0) failField(path, minimumMessage(field.getMinimum()));
        if (field.getMaximum() != null && magnitude(value).compareTo(field.getMaximum()) > 0) failField(path, maximumMessage(field.getMaximum()));
        if (field.getStep() != null && value instanceof Number
                && decimal(value).subtract(field.getMinimum() == null ? BigDecimal.ZERO : field.getMinimum()).remainder(field.getStep()).signum() != 0)
            failField(path, "参数步长无效，应按 " + displayConstraint(field.getStep()) + " 递增");
        if (value instanceof Map<?, ?> map && "object".equals(field.getType())) checkFields(field.getProperties(), map, path + ".");
        if (value instanceof List<?> list && field.getItems() != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == null) failField(path, "列表项不能为空");
                checkValue(field.getItems(), list.get(i), path + "[" + i + "]");
            }
        }
    }

    public static Object read(Map<String, Object> data, String path) {
        Object current = data;
        for (String part : path.split("\\.")) current = current instanceof Map<?, ?> map ? map.get(part) : null;
        return current;
    }

    @SuppressWarnings("unchecked")
    public static void write(Map<String, Object> data, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = data;
        for (int i = 0; i < parts.length - 1; i++) {
            Object nested = current.get(parts[i]);
            if (nested == null) { nested = new LinkedHashMap<String, Object>(); current.put(parts[i], nested); }
            if (!(nested instanceof Map<?, ?>)) fail("参数路径冲突");
            current = (Map<String, Object>) nested;
        }
        current.put(parts[parts.length - 1], value);
    }

    private static boolean empty(Object value) { return value == null || value instanceof String s && s.isBlank() || value instanceof List<?> l && l.isEmpty(); }
    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return decimal(a).compareTo(decimal(b)) == 0;
        if (a instanceof Map<?, ?> left && b instanceof Map<?, ?> right)
            return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> same(left.get(key), right.get(key)));
        if (a instanceof List<?> left && b instanceof List<?> right) {
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) if (!same(left.get(i), right.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }
    private static boolean allowed(Object value, List<?> choices) {
        return value instanceof List<?> list ? list.stream().allMatch(item -> choices.stream().anyMatch(choice -> same(item, choice)))
                : choices.stream().anyMatch(choice -> same(value, choice));
    }
    private static BigDecimal magnitude(Object value) { return value instanceof List<?> list ? BigDecimal.valueOf(list.size()) : value instanceof String s ? BigDecimal.valueOf(s.length()) : decimal(value); }
    private static BigDecimal decimal(Object value) {
        try { return new BigDecimal(String.valueOf(value)); }
        catch (RuntimeException ex) { fail("数字参数无效"); return BigDecimal.ZERO; }
    }
    private static String minimumMessage(Object minimum) { return "参数低于允许下限，最小值为 " + displayConstraint(minimum); }
    private static String maximumMessage(Object maximum) { return "参数超过允许上限，最大值为 " + displayConstraint(maximum); }
    private static String displayConstraint(Object value) {
        if (value instanceof BigDecimal number) return number.stripTrailingZeros().toPlainString();
        if (value instanceof Number) return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }
    private static String choicesMessage(List<?> choices) {
        List<String> values = safe(choices).stream().limit(20).map(ModelParameterValidator::displayChoice).filter(Objects::nonNull).toList();
        if (values.size() != choices.size()) return "参数值无效，请从模型能力允许值中选择";
        String joined = String.join("、", values);
        return joined.length() <= 300 ? "参数值无效，允许值为：" + joined : "参数值无效，请从模型能力允许值中选择";
    }
    private static String displayChoice(Object value) {
        if (value instanceof Number || value instanceof Boolean) return displayConstraint(value);
        if (value instanceof String text && text.matches("[\\p{L}\\p{N}_.:+/\\-]{1,64}")) return text;
        return null;
    }
    private static String typeLabel(String type) {
        return switch (type) {
            case "string" -> "文本";
            case "number" -> "数字";
            case "integer" -> "整数";
            case "boolean" -> "布尔值";
            case "object" -> "对象";
            case "array" -> "列表";
            default -> "已声明类型";
        };
    }
    private static <T> List<T> safe(List<T> list) { return list == null ? List.of() : list; }
    private static void failField(String field, String message) { log.info("模型参数校验失败: field={}, reason={}", field, message); throw new ServiceException(field + "：" + message); }
    private static void fail(String message) { log.info("模型参数定义无效: {}", message); throw new ServiceException(message); }
}
