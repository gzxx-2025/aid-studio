package com.aid.skill.service;

import cn.hutool.core.util.StrUtil;
import com.aid.skill.domain.AidSkillVersion;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Skill 不可变版本中的默认模型与有序候选模型契约。 */
public record SkillModelConfiguration(String defaultModelCode, List<String> selectableModelCodes) {
    public static final int MAX_MODELS = 20;

    public SkillModelConfiguration {
        selectableModelCodes = List.copyOf(selectableModelCodes == null ? List.of() : selectableModelCodes);
    }

    public static SkillModelConfiguration from(AidSkillVersion version) {
        if (version == null) {
            return new SkillModelConfiguration(null, List.of());
        }
        String fallback = canonicalCode(version.getModelCode());
        if (StrUtil.isBlank(version.getModelConfigJson())) {
            return legacy(fallback);
        }
        try {
            JSONObject value = JSON.parseObject(version.getModelConfigJson());
            String defaultCode = StrUtil.trim(value.getString("defaultModelCode"));
            JSONArray candidates = value.getJSONArray("selectableModelCodes");
            List<String> codes = candidates == null ? List.of()
                    : candidates.toJavaList(String.class);
            return normalized(StrUtil.blankToDefault(defaultCode, fallback), codes, true);
        } catch (RuntimeException ignored) {
            return legacy(fallback);
        }
    }

    public static SkillModelConfiguration normalized(String defaultCode, List<String> candidates,
                                                     boolean legacyFallback) {
        String normalizedDefault = canonicalCode(defaultCode);
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                String code = canonicalCode(candidate);
                if (StrUtil.isNotBlank(code)) {
                    ordered.add(code);
                }
            }
        }
        if (ordered.isEmpty() && legacyFallback && StrUtil.isNotBlank(normalizedDefault)) {
            ordered.add(normalizedDefault);
        }
        List<String> codes = new ArrayList<>(ordered);
        if (codes.size() > MAX_MODELS) {
            codes = new ArrayList<>(codes.subList(0, MAX_MODELS));
        }
        return new SkillModelConfiguration(normalizedDefault, codes);
    }

    public boolean isValid() {
        return StrUtil.isNotBlank(defaultModelCode) && !selectableModelCodes.isEmpty()
                && selectableModelCodes.size() <= MAX_MODELS
                && selectableModelCodes.contains(defaultModelCode);
    }

    public String toJson() {
        JSONObject value = new JSONObject();
        value.put("defaultModelCode", defaultModelCode);
        value.put("selectableModelCodes", selectableModelCodes);
        return value.toJSONString();
    }

    private static SkillModelConfiguration legacy(String fallback) {
        return StrUtil.isBlank(fallback)
                ? new SkillModelConfiguration(null, List.of())
                : new SkillModelConfiguration(fallback, List.of(fallback));
    }

    private static String canonicalCode(String code) {
        String normalized = StrUtil.trim(code);
        // Published Skill packages are immutable and their digest includes the saved model list.
        // Resolve the retired upstream identity without rewriting signed package snapshots.
        return "deepseek-v4-flash".equals(normalized) ? "deepseek-flash" : normalized;
    }
}
