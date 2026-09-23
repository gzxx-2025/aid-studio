package com.aid.media.dto;

import lombok.Data;

import java.util.Map;

@Data
public class MediaImageGenerateRequest {

    // 指定模型名称（可选）：为空时走后端默认模型路由策略。
    private String modelName;

    /** 本次业务调用的能力；专用业务接口由 Service 填写。 */
    private String capabilityCode;

    /** 业务 Service 指定的功能池，不接受客户端覆盖。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String businessFuncCode;

    /** 归一化后的调用配置标识，参与请求幂等。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String invocationIdentity;

    // 项目ID（可选）：用于关联任务到具体项目，列表查询时按项目过滤。
    private Long projectId;

    // 剧集ID（可选）：电影模式为0，剧集模式为真实剧集ID。
    private Long episodeId;

    // 正向提示词（必填）：驱动图片生成语义。
    private String prompt;

    /** 任务存档摘要（可选）：prompt 过大突破 MySQL TEXT 64KB 时，设为仅含动态入参的紧凑版本入库；为空则写完整 prompt */
    private String taskPromptDigest;

    // 图片尺寸（可选）：如 1024*1024。
    private String size;

    // 负向提示词（可选）：用于约束不希望出现的元素。
    private String negativePrompt;

    // 参考图 URL（可选）：单图参考时可直接填此字段；多图场景建议同时使用 options.referenceImages 列表（分镜/业务默认写法）。
    private String referenceImageUrl;

    /**
     * 区域编辑蒙版 URL。该字段只接收业务层完成权限校验后解析出的可信地址，
     * 不接受 C 端直接传入任意 URL。
     */
    private String maskImageUrl;

    /** 蒙版语义：BLACK_WHITE（白色编辑）或 ALPHA（透明区域编辑）。 */
    private String maskEncoding;

    /** 扩图目标尺寸及原图在目标画布中的位置。 */
    private Integer targetWidth;
    private Integer targetHeight;
    private Integer sourceX;
    private Integer sourceY;

    /** 灯光编辑参数。是否支持及取值范围由当前模型能力定义校验。 */
    private Integer brightness;
    private Integer colorTemperature;
    private Integer keyLightAzimuth;
    private Integer keyLightElevation;
    private Boolean rimLightEnabled;
    private Integer rimLightAzimuth;
    private Integer rimLightElevation;

    /**
     * 扩展参数（可选）：厂商差异字段与业务透传。
     */
    private Map<String, Object> options;

    /**
     * 业务记录主键（可选）：生成成功后通过 {@link com.aid.service.IGenResultCallbackService#fillResultUrl} 回填。
     * 与 {@link #category} 同时有值时才会触发回填。
     */
    private Long recordId;

    /**
     * 回填目标（可选）：{@code asset} 写入资产表，{@code gen_record} 写入抽卡记录表；与 fillResultUrl 的 category 一致。
     */
    private String category;

    /**
     * 目标输出张数（可选，仅用于预扣与展示）。
     * 最终扣费以 provider 实际返回图片张数为准，不作为结算依据。
     * 为空时按 1；&lt;=0 或超过模型上限时直接拒绝。
     */
    private Integer expectedImageCount;

    /** 业务任务ID（可选）：用于关联触发本媒体任务的业务任务，如 aid_extract_task.id，参与 requestHash 计算以破除错误幂等复用 */
    private Long bizTaskId;

    /** 业务任务类型（可选）：如 form_image，与 bizTaskId 配合定位具体业务表 */
    private String bizTaskType;

    /**
     * 计费用户ID（可选）：异步线程内 SecurityContext 会丢失，业务调用方在外层确定 userId 后
     * 必须显式透传，否则媒体任务 userId 为空会导致预冻结/结算/退款全部跳过。为空时服务层回退到登录上下文。
     */
    private Long userId;
}
