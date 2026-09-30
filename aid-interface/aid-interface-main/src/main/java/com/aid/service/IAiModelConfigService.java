package com.aid.service;

import com.aid.domain.vo.AiModelConfigVo;

/**
 * AI模型配置聚合Service接口。
 */
public interface IAiModelConfigService {

    /**
     * 按模型代码查询
     *
     * @param modelCode 模型真实调用代码 (如: qwen-image-max)
     * @return 已组装的模型配置（含服务商信息和用户覆盖），不存在返回 null
     */
    AiModelConfigVo selectByModelCode(String modelCode);

    /** 按业务明确的能力查询模型调用配置。 */
    default AiModelConfigVo selectByModelCode(String modelCode, String capabilityCode) {
        return selectByModelCode(modelCode);
    }

    /** 按业务绑定解析模型能力与默认参数。 */
    default AiModelConfigVo selectForBusiness(String modelCode, String funcCode, String capabilityCode) {
        return selectByModelCode(modelCode, capabilityCode);
    }

    /**
     * 按模型代码和任务用户查询配置。
     *
     * @param modelCode 模型代码
     * @param userId 任务所属用户ID
     * @return 已组装的模型配置，不存在返回 null
     */
    AiModelConfigVo selectByModelCodeForUser(String modelCode, Long userId);

    /** Resolve the capability used by a user's read-only quote without dropping user-level configuration. */
    default AiModelConfigVo selectByModelCodeForUser(String modelCode, Long userId, String capabilityCode) {
        return selectByModelCodeForUser(modelCode, userId);
    }

    /** 已建任务按原模型记录解析凭证，模型停用不取消原任务的查询。 */
    default AiModelConfigVo selectTaskCredentials(Long modelId, String modelCode, Long userId) {
        return selectByModelCodeForUser(modelCode, userId);
    }

    /**
     * 按模型分类查询优先级最高的模型。
     *
     * @param category 模型分类 (image/video/audio)
     * @return 已组装的模型配置，不存在返回 null
     */
    AiModelConfigVo selectByCategoryWithHighestPriority(String category);

    /**
     * 按分类查询低于指定优先级的最高优先级模型。
     *
     * @param category         模型分类
     * @param currentPriority  当前模型优先级
     * @return 已组装的模型配置，不存在返回 null
     */
    AiModelConfigVo selectFallbackByCategoryAndLessPriority(String category, Integer currentPriority);

    /**
     * 按模型ID查询
     *
     * @param modelId 模型主键
     * @return 已组装的模型配置，不存在返回 null
     */
    AiModelConfigVo selectByModelId(Long modelId);

    /** 按模型主键和业务明确的能力读取调用配置。 */
    default AiModelConfigVo selectByModelId(Long modelId, String capabilityCode) {
        return selectByModelId(modelId);
    }
}
