package com.aid.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI模型配置聚合 VO：聚合 aid_ai_model + aid_ai_provider + aid_user_ai_config，
 * 其中 baseUrl/apiKey 已处理用户自定义覆盖逻辑，调用方直接使用即可。
 */
@Data
public class AiModelConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    /** 模型ID */
    private Long id;

    /** 本次调用选中的模型能力。 */
    private String capabilityCode;

    /** 本次调用选中的协议绑定。 */
    private String bindingCode;
    private java.util.List<com.aid.aid.domain.model.ModelProtocolBinding.FieldMapping> requestMappings;

    /** 模型配置版本。 */
    private Long configVersion;

    /** 本次解析出的能力定义。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private com.aid.aid.domain.model.ModelCapabilityDefinition resolvedDefinition;

    /** 本次解析持有的完整能力配置，避免同一次报价中混入后续编辑结果。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private java.util.List<com.aid.aid.domain.model.ModelCapabilityDefinition> resolvedCapabilities;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private java.util.Map<String, Object> invocationBaseline;

    /** 业务绑定的参数默认值。 */
    private String businessDefaultsJson;
    private String businessFuncCode;

    /** 所属服务商ID */
    private Long providerId;

    /** 服务端凭证版本引用，不包含密钥。 */
    private Integer credentialVersion;

    /** 模型展示/选择码 (如: gpt5.4_a, qwen-image-max)，全表唯一，前端按此选择 */
    private String modelCode;

    /**
     * 已解析的真实上游模型名（来自 aid_ai_model.real_model_code，为空时回退 modelCode）。
     * Provider 构造上游请求体 model 字段时使用本值。
     */
    private String realModelCode;

    /** 前端展示名称 */
    private String modelName;

    /** 模型分类 (image/video/audio) */
    private String modelType;

    /**
     * 生成模式（对 {@code modelType} 大类做进一步细分）：
     * {@code text} / {@code text_to_image} / {@code image_to_image} / {@code image_edit} /
     * {@code image_upscale} / {@code text_to_video} / {@code image_to_video} /
     * {@code video_to_video} / {@code audio}
     */
    private String generateMode;

    /** FIXED 模式官方原价（元）；最终积分由统一倍率服务换算 */
    private BigDecimal costCredits;

    /** 模型级计费倍率(1.00=不加价,1.10=加价10%,0.80=8折)，作用于本模型最终金额 */
    private BigDecimal billingMultiplier;

    /** 计费模式: FIXED=固定价, SKU=按SKU规则 */
    private String billingMode;

    /** SKU计费规则JSON */
    private String billingRuleJson;

    /** 计费规则版本号 */
    private Integer billingVersion;

    /** 是否免费 */
    private Boolean isFree;

    /** 模型上游图片输入是否使用代理 URL 模板；仅供服务端出站准备链路使用。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean imageUrlProxyEnabled;

    /** 图片代理 URL 模板；仅供服务端出站准备链路使用。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    private String imageUrlProxyTemplate;

    /** 特定路由后缀 (可选) */
    private String apiVersion;

    /** 网关后缀 */
    private String apiSuffix;

    /**
     * 协议标识（来自 aid_ai_model.protocol），文本模型常用 openai-compatible-text / gemini-text。
     * Provider 路由的权威依据。
     */
    private String protocol;

    /**
     * 模型优先级(值越大优先级越高)。
     */
    private Integer priority;

    /** 图片模型能力类型（仅image类型有效）: 1=文生图, 2=图生图, 3=图片高清, 4=图片编辑 */
    private Integer imageRefine;
    /** 服务商网关地址 */
    private String baseUrl;

    /** API密钥（如用户有自定义配置则返回用户密钥） */
    @lombok.ToString.Exclude
    private String apiKey;

    /** 扩展密钥 */
    @lombok.ToString.Exclude
    private String apiSecret;

    /** 任务查询路径模板（%s 为 taskId 占位符），来自 aid_ai_provider.task_query_suffix */
    private String taskQuerySuffix;

    /** 服务商唯一编码 (系统内路由标识) */
    private String providerCode;

    /** 服务商展示名称 */
    private String providerName;
    /** 模型级调度策略JSON（覆写供应商默认，来自 aid_ai_model.schedule_strategy_json） */
    private String scheduleStrategyJson;

    /** 供应商是否支持回调通知（来自 aid_ai_provider.supports_callback） */
    private Boolean supportsCallback;

    /** 供应商级调度策略JSON（来自 aid_ai_provider.schedule_strategy_json） */
    private String providerScheduleStrategyJson;
    /** 是否支持文本输入(prompt) */
    private Boolean supportsTextInput;

    /**
     * 是否支持 system role 系统提示词分离（仅 text 模型有效）。
     * 1 = 支持 → 智能体 {@code prompt_content} 走 system 消息，动态入参走 user 消息（默认）<br/>
     * 0 = 不支持 → 合并为一条 user 消息（带「【系统指令】/【输入数据】」分隔标记）。
     */
    private Boolean supportsSystemPrompt;

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

    /** 模型能力配置JSON（sizeOptions/aspectRatioOptions/durationOptions/sceneRules 等） */
    private String capabilityJson;

    /** 统一参数到厂商参数映射JSON（后端编排时使用） */
    private String paramMappingJson;
    /**
     * 鉴权 header 名（来自 aid_ai_provider.auth_header），默认 Authorization。
     * 例如 Azure OpenAI 用 api-key。
     */
    private String authHeader;

    /**
     * 鉴权前缀（来自 aid_ai_provider.auth_prefix），默认 "Bearer "。
     * 部分厂商无前缀（直接放裸 token）。
     */
    private String authPrefix;

    /**
     * 自定义 header（来自 aid_ai_provider.extra_headers，JSON 对象字符串）。
     * 例如 Azure 需要 {"api-version":"2024-02-01"}。
     */
    private String extraHeadersJson;

    /**
     * 厂商级请求体附加参数（来自 aid_ai_provider.extra_body，JSON 对象字符串）。
     * 例如方舟 {"thinking":{"type":"disabled"}}、阿里 {"enable_thinking":false}。
     */
    private String extraBodyJson;

    /**
     * 自定义 query string（来自 aid_ai_provider.extra_query，JSON 对象字符串）。
     * 部分厂商需要带固定 query 参数。
     */
    private String extraQueryJson;

    /**
     * 模型级请求体附加参数（来自 aid_ai_model.extra_body，JSON 对象字符串）。
     * 与厂商级 extra_body 合并时，模型级覆盖厂商级。
     */
    private String modelExtraBodyJson;
}
