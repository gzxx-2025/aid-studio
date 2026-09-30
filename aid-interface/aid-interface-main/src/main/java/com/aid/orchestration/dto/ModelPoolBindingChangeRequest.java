package com.aid.orchestration.dto;

import java.util.List;

import lombok.Data;

/**
 * 模型与模型池批量关系变更请求。
 */
@Data
public class ModelPoolBindingChangeRequest
{
    /** 待处理模型主键。 */
    private List<Long> modelIds;

    /** 待处理模型池主键。 */
    private List<Long> poolIds;

    /** 新增结构化模型关系时必须提交本次能力选择；无结构化能力的旧模型可省略。 */
    private List<ModelPoolCapabilitySelection> capabilitySelections;

    /** 移出文本模型时由管理员明确选择的池内替代模型，键为模型池 ID。 */
    private java.util.Map<Long, String> poolReplacementCodes;
}
