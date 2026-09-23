package com.aid.model.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 按功能编码分组的可用AI模型展示VO
 * 用于 {@code POST /api/user/model/listByFunc} 一次传入多个功能编码时，
 * 按各自功能（场景）分组返回模型列表，前端可明确区分每个场景对应哪些模型，
 * 不再把多个场景的模型混成一个无法区分归属的扁平列表。
 *
 * @author 视觉AID
 */
@Data
public class AiModelFuncGroupVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 功能编码，对应 aid_ai_model_func_config.func_code（如 main_scene_image） */
    private String funcCode;

    /** 功能名称，对应 aid_ai_model_func_config.func_name；无配置时为 null */
    private String funcName;

    /** 模型大类：text/image/video/audio；无配置时为 null */
    private String modelType;

    /** 生成模式：如 image_edit/image_upscale/text_to_image；无配置时为 null */
    private String generateMode;

    /** 该业务池按配置顺序选出的默认可用模型编码；无可用模型时为 null。 */
    private String defaultModelCode;

    /** 该功能（场景）下可用的模型列表，按配置顺序；无可用模型时为空数组 */
    private List<AiModelVO> models = new ArrayList<>();
}
