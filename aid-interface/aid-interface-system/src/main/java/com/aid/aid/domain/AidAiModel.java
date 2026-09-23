package com.aid.aid.domain;

import java.math.BigDecimal;
import java.io.Serializable;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.aid.common.annotation.Excel;
import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import com.aid.common.core.domain.BaseEntity;

/**
 * AI底层模型配置与算力计费对象 aid_ai_model
 *
 * @author 视觉AID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@TableName(value = "aid_ai_model")
public class AidAiModel extends BaseEntity implements Serializable
{
    @TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.List<AidAiModelAlias> legacyAliases;
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属服务商ID (关联 aid_ai_provider.id) */
    @Excel(name = "所属服务商ID (关联 aid_ai_provider.id)")
    private Long providerId;

    /** 模型真实调用代码 (如: gpt-4o, seedance-v2)。前端展示/选择码，全表唯一（如 gpt5.4_a、gpt5.4_b） */
    @Excel(name = "模型真实调用代码 (如: gpt-4o, seedance-v2)")
    private String modelCode;

    /**
     * 映射后的真实上游模型名（发给厂商 API 请求体 model 字段的值）。
     * 可重复：多个展示码（如 gpt5.4_a、gpt5.4_b）可映射到同一真实模型（gpt5.4）。
     * 为空/空白时回退使用 {@link #modelCode}。
     */
    @Excel(name = "真实上游模型名 (如: gpt-4o, 为空则用模型代码)")
    private String realModelCode;

    /** 前端展示名称 */
    @Excel(name = "前端展示名称")
    private String modelName;

    /** 模型专属LOGO；为空时客户端回退所属服务商LOGO，存相对路径并由 @MediaUrl 转换。 */
    @Excel(name = "模型LOGO")
    @MediaUrl
    private String logoUrl;

    /** 模型分类 (image生图, video生视频, audio配音) */
    @Excel(name = "模型分类 (image生图, video生视频, audio配音)")
    private String modelType;

    /**
     * 生成模式细分（对 model_type 大类的进一步细分，不替换 model_type）。
     */
    @Excel(name = "生成模式 (text/text_to_image/image_to_image/image_edit/image_upscale/text_to_video/image_to_video/video_to_video/audio)")
    private String generateMode;

    /** 特定路由后缀 (可选) */
    @Excel(name = "特定路由后缀 (可选)")
    private String apiVersion;

    /** FIXED 模式官方原价（元），最终积分由基础倍率和单模型倍率换算 */
    @Excel(name = "官方原价（元）")
    private BigDecimal costCredits;

    /** 模型级计费倍率(1.00=不加价,1.10=加价10%,0.80=8折)，作用于本模型最终金额 */
    @Excel(name = "模型级计费倍率(1.00=不加价,1.10=加价10%,0.80=8折)")
    private BigDecimal billingMultiplier;

    /** 计费模式: FIXED=固定价, SKU=按SKU规则 */
    private String billingMode;

    /** SKU计费规则JSON(FIXED模式为空) */
    private String billingRuleJson;

    /** 计费规则版本号 */
    private Integer billingVersion;

    /** 模型管理配置的并发修改版本。 */
    private Long configVersion;

    /** 是否免费：0收费，1免费 */
    private Boolean isFree;

    /** 是否为该模型的上游图片输入启用代理 URL 模板。 */
    private Boolean imageUrlProxyEnabled;

    /** 图片代理 URL 模板；启用时必须且只能包含一个 {url} 占位符。 */
    private String imageUrlProxyTemplate;

    /** 模型接口路径，包含所需的API版本前缀 */
    @Excel(name = "模型接口路径")
    private String apiSuffix;

    /**
     * 协议标识，用于 Provider 路由。
     * 文本模型常用值：{@code openai-compatible-text} / {@code gemini-text}。
     * 与 {@code aid_media_task.protocol} 库表值一致。
     */
    private String protocol;

    /**
     * 模型优先级(值越大优先级越高)。
     */
    @Excel(name = "模型优先级(值越大优先级越高)")
    private Integer priority;

    /** 状态 (0正常 1停用) */
    @Excel(name = "状态 (0正常 1停用)")
    private String status;

    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

    /** 模型级调度策略JSON（覆写供应商默认） */
    private String scheduleStrategyJson;

    /** 图片模型能力类型（仅image类型有效）: 1=文生图, 2=图生图, 3=图片高清, 4=图片编辑 */
    private Integer imageRefine;
    /** 是否支持文本输入(prompt)：0否 1是 */
    private Boolean supportsTextInput;

    /**
     * 是否支持 system role 系统提示词分离（仅 text 模型有效）。
     * 1 = 支持 → 智能体 {@code prompt_content} 走 system 消息，动态入参（原文 / 已有资产等）走 user 消息（默认）<br/>
     * 0 = 不支持 → 系统提示词与动态入参合并成一条 user 消息，并用「【系统指令】/【输入数据】」边界标记区分。
     */
    private Boolean supportsSystemPrompt;

    /** 是否支持图片输入(单图)：0否 1是 */
    private Boolean supportsImageInput;

    /** 是否支持多图输入：0否 1是 */
    private Boolean supportsMultiImageInput;

    /** 最大输出数量；图片=最大可生成张数，视频通常为1 */
    private Integer maxOutputCount;

    /** 默认输出数量 */
    private Integer defaultOutputCount;

    /** 是否支持选择比例(aspectRatio)：0否 1是 */
    private Boolean supportsAspectRatio;

    /** 是否支持选择规格档位(size/resolution)：0否 1是 */
    private Boolean supportsSizePreset;

    /** 是否支持视频时长选择：0否 1是（仅 video 模型有效） */
    private Boolean supportsDuration;

    /** 是否支持首帧图输入：0否 1是（仅 video 模型有效；text/image 默认 false） */
    private Boolean supportsFirstFrame;

    /** 是否支持尾帧图输入：0否 1是（仅 video 模型有效；text/image 默认 false） */
    private Boolean supportsLastFrame;

    /** 默认规格档位，例如图片 1K/2K/4K，视频 720P/1080P */
    private String defaultSizeCode;

    /** 默认比例，例如 1:1、16:9 */
    private String defaultAspectRatio;

    /** 默认时长(秒)，仅视频模型有效 */
    private Integer defaultDurationSeconds;

    /** 模型能力配置JSON（sizeOptions/aspectRatioOptions/durationOptions/sceneRules 等，前端动态渲染） */
    private String capabilityJson;

    /** 统一参数到厂商参数的映射JSON（后端编排时使用） */
    private String paramMappingJson;

    /**
     * 模型级请求体附加参数（JSON 对象，覆盖厂商级 extra_body）。
     * 例如某模型不需要思考模式可在此显式设置 {"thinking":null} 覆盖厂商级配置。
     */
    private String extraBody;

    /** 模型官方定价页直链（为空回退所属服务商的 official_price_url），后台配价时一键核对官方价格 */
    private String officialPriceUrl;

    /**
     * 输入要求标签（非库表字段，查询时由 capability_json/sceneRules 等统一推导）：
     * text_only=纯文本 / image_optional=图片可选 / image_required=图片必传 / video_required=视频必传。
     * 后台管理列表展示与模型池选择器筛选使用；作为查询入参时按该标签过滤。
     */
    @TableField(exist = false)
    private String inputRequirement;

    /** 模型完整能力定义。 */
    @TableField(exist = false)
    private java.util.List<com.aid.aid.domain.model.ModelCapabilityDefinition> capabilities;

    /** 能力是否已落入结构化子表；false 时 capabilities 仅为旧配置的兼容投影。 */
    @TableField(exist = false)
    private Boolean structuredCapabilities;

    /** 业务功能对模型能力的绑定。 */
    @TableField(exist = false)
    private java.util.List<AidAiBusinessModelBinding> businessBindings;

    /** 业务视图选中的能力。 */
    @TableField(exist = false)
    private String selectedCapabilityCode;

}
