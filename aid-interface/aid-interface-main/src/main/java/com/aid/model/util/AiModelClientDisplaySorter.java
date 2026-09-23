package com.aid.model.util;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.aid.model.vo.AiModelFuncGroupVO;
import com.aid.model.vo.AiModelVO;

/** C 端模型展示排序工具；始终返回副本，不改变功能池的路由顺序。 */
public final class AiModelClientDisplaySorter
{
    private AiModelClientDisplaySorter()
    {
    }

    public static List<AiModelVO> sortedModels(List<AiModelVO> models)
    {
        List<AiModelVO> sorted = models == null ? new ArrayList<>() : new ArrayList<>(models);
        // Collator 非线程安全；每次排序独占一个实例，不放入静态共享 Comparator。
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        collator.setStrength(Collator.PRIMARY);
        Comparator<AiModelVO> comparator = (left, right) ->
        {
            int byLocalizedName = collator.compare(normalizeName(left), normalizeName(right));
            if (byLocalizedName != 0)
            {
                return byLocalizedName;
            }
            int byExactName = normalizeName(left).compareTo(normalizeName(right));
            if (byExactName != 0)
            {
                return byExactName;
            }
            int byCode = normalizeCode(left).compareTo(normalizeCode(right));
            if (byCode != 0)
            {
                return byCode;
            }
            return Comparator.nullsLast(Long::compareTo).compare(
                    left == null ? null : left.getId(), right == null ? null : right.getId());
        };
        sorted.sort(comparator);
        return sorted;
    }

    public static List<AiModelFuncGroupVO> sortedGroups(List<AiModelFuncGroupVO> groups)
    {
        if (groups == null)
        {
            return new ArrayList<>();
        }
        List<AiModelFuncGroupVO> result = new ArrayList<>(groups.size());
        for (AiModelFuncGroupVO source : groups)
        {
            if (source == null)
            {
                continue;
            }
            AiModelFuncGroupVO copy = new AiModelFuncGroupVO();
            copy.setFuncCode(source.getFuncCode());
            copy.setFuncName(source.getFuncName());
            copy.setModelType(source.getModelType());
            copy.setGenerateMode(source.getGenerateMode());
            copy.setDefaultModelCode(source.getDefaultModelCode());
            copy.setModels(sortedModels(source.getModels()));
            result.add(copy);
        }
        return result;
    }

    private static String normalizeName(AiModelVO model)
    {
        return model == null || model.getModelName() == null ? "\uffff" : model.getModelName().trim();
    }

    private static String normalizeCode(AiModelVO model)
    {
        return model == null || model.getModelCode() == null
                ? "\uffff" : model.getModelCode().trim().toLowerCase(Locale.ROOT);
    }
}
