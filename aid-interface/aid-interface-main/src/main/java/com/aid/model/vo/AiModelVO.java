package com.aid.model.vo;

import java.io.Serializable;
import java.math.BigDecimal;

import com.aid.billing.vo.ModelBillingDetailVO;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

/**
 * AI模型列表展示VO
 * 供C端用户选择模型时展示，包含模型基本信息和所属供应商名称。
 *
 * @author 视觉AID
 */
@Data
public class AiModelVO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 模型ID */
    private Long id;

    /** 模型真实调用代码 (如: qwen-image-max, seedance-v2) */
    private String modelCode;

    /** 仅用于恢复历史选择，不产生额外模型选项。 */
    private java.util.List<String> legacyModelCodes;
    private java.util.List<Long> legacyModelIds;

    /** 当前业务使用的模型能力。 */
    private String capabilityCode;

    /** 当前能力的表单参数定义。 */
    private java.util.List<com.aid.aid.domain.model.ModelParameter> parameterSchema;

    /** 当前能力的参数联动规则。 */
    private java.util.List<com.aid.aid.domain.model.ModelParameterRule> parameterRules;

    /**
     * 当前业务为该模型开放的全部能力。capabilityCode 仍表示默认能力，
     * 调用方切换操作时必须从本列表选择并原样提交能力编码。
     */
    private java.util.List<ModelCapabilityOptionVO> availableCapabilities = new java.util.ArrayList<>();

    /** 前端展示名称 */
    private String modelName;

    /** 模型专属LOGO；为空表示未单独配置。 */
    @com.aid.common.aid.oss.annotation.MediaUrl
    private String modelLogo;

    /** 模型分类 (text-文本, image-生图, video-生视频, audio-配音) */
    private String modelType;

    /**
     * 生成模式（对 {@code modelType} 大类做进一步细分）：
     * {@code text} / {@code text_to_image} / {@code image_to_image} / {@code image_edit} /
     * {@code image_upscale} / {@code text_to_video} / {@code image_to_video} /
     * {@code video_to_video} / {@code audio}
     */
    private String generateMode;

    /** 单次调用扣除积分 */
    private BigDecimal costCredits;

    /** 是否免费 */
    private Boolean isFree;

    /**
     * 模型优先级(值越大优先级越高)。
     */
    private Integer priority;

    /** 服务商展示名称 (如: 字节火山引擎, OpenAI) */
    private String providerName;

    /** 兼容展示LOGO：模型LOGO优先，为空时回退服务商LOGO。 */
    @com.aid.common.aid.oss.annotation.MediaUrl
    private String providerLogo;

    /** 图片模型能力类型（仅image类型有效）: 1=文生图, 2=图生图, 3=图片高清, 4=图片编辑 */
    private Integer imageRefine;
    /** 是否支持文本输入(prompt) */
    private Boolean supportsTextInput;

    /** 是否支持图片输入(单图) */
    private Boolean supportsImageInput;

    /** 是否支持多图输入 */
    private Boolean supportsMultiImageInput;

    /** 最大输出数量；图片=最大可生成张数，视频通常为1 */
    private Integer maxOutputCount;

    /** 默认输出数量 */
    private Integer defaultOutputCount;

    /** 是否支持选择比例(aspectRatio) */
    private Boolean supportsAspectRatio;

    /** 是否支持选择规格档位(size/resolution) */
    private Boolean supportsSizePreset;

    /** 是否支持视频时长选择（仅 video 模型有效） */
    private Boolean supportsDuration;

    /** 是否支持首帧图输入（仅 video 模型有效；text/image 默认 false） */
    private Boolean supportsFirstFrame;

    /** 是否支持尾帧图输入（仅 video 模型有效；text/image 默认 false） */
    private Boolean supportsLastFrame;

    /** 默认规格档位（图片 1K/2K/4K，视频 720P/1080P） */
    private String defaultSizeCode;

    /** 默认比例（如 1:1、16:9） */
    private String defaultAspectRatio;

    /** 默认时长（秒），仅视频模型有效 */
    private Integer defaultDurationSeconds;

    /** 当前业务能力的通用提示词最大字符数；null 表示未配置。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Integer maxPromptCharacters;

    /** 含中日韩文字时的最大字符数；null 表示沿用通用上限。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Integer maxPromptCharactersCjk;

    /**
     * 单次最多可上传参考图张数（仅 image/video 有意义）。
     */
    private Integer maxReferenceImages;

    /**
     * 单次最少参考图张数（仅 image/video 有意义；0=不要求带图，N&gt;=1=必须至少带 N 张图）。
     * 前端据此在触发任务前做前置校验与界面提示。
     */
    private Integer minReferenceImages;

    /**
     * 输入要求标签（由 capability/sceneRules 等统一推导，前端据此控制上传入口）：
     * text_only=纯文本即可 / image_optional=图片可选 / image_required=图片必传 / video_required=视频必传。
     */
    private String inputRequirement;

    /**
     * 模型能力配置（结构化对象，前端动态渲染参数面板使用）。
     * 前端无需自行 JSON.parse；
     * 字段集按 modelType 略有差异，详见 {@link CapabilityVO}。
     */
    private CapabilityVO capability;

    /**
     * 模型计费明细（结构化对象，与 /api/public/billing/detail 的单模型结构完全一致）。
     * 含计费口径（meterType）、口径中文名、表头列定义（columns）与各档位 SKU 明细（rules，
     * 价格已按「模型级倍率 × 全局倍率 × 扣费系数」折算）。
     * 视频模型据此展示「秒数 / 分辨率对应的价格档位」，文本模型展示输入/输出百万 Token 单价。
     */
    private ModelBillingDetailVO billing;
}
