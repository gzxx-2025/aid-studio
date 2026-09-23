package com.aid.media.provider;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ProviderSubmitResult {

    // 厂商任务ID（异步模式返回）。
    private String providerTaskId;

    // 直出URL（同步模式返回）。
    private String directUrl;

    // 直出文本（同步 Chat 类接口返回的助手正文，无 URL 时使用）。
    private String directText;

    /** 上游公开的思考内容；仅在内存回调链路短暂存在，禁止写入任务或业务表。 */
    @ToString.Exclude
    private String directReasoning;

    /** 本轮助手函数调用及续轮上下文；思考字段不得持久化。 */
    @ToString.Exclude
    private com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem toolMessage;

    // 厂商原始响应内容。
    private String rawResponse;

    /** 已归一化的任务错误，不以短文案替代恢复动作和来源信息。 */
    @ToString.Exclude
    private String errorDetailJson;

    /**
     * 同步直出图片 URL 列表（图片 provider 填充，兼容老 directUrl）。
     * 非空时 directUrl 应取首项，用于统一多图结算。
     */
    private List<String> resultUrls;

    /** Ordered structured image outputs; URL list remains for legacy consumers. */
    private List<ImageOutputItem> imageOutputs;

    /**
     * 同步直出实际图片张数（图片 provider 填充）。
     * 图片计费按实际张数结算，以该字段为准；为空时按 directUrl 有无兜底为 1/0。
     */
    private Integer resultCount;

    /**
     * 同步直出 token 用量（文本 provider 填充）：键名与 onUsage 一致，
     * 形如 input_tokens / output_tokens / total_tokens；
     * 由 {@code TextProviderClient} 默认 submit 聚合从 onUsage 捕获，供
     * {@code MediaGenerationServiceImpl.handleSubmitResult} 文本同步成功分支
     * 透传给 settleBilling，避免按预扣封顶结算。
     */
    private Map<String, Object> usage;

    /**
     * Base64 直出模式：provider 仅在内存中解码并上传 OSS，此字段保存系统 OSS URL，Base64 不落库。
     */
    private String ossUrl;

    /**
     * 试听模式音频 base64（不落库）：仅 {@code MediaAudioGenerateRequest.previewMode=true} 时由音频 provider 填充，
     * 内容为完整音频的 base64 编码。该字段不进任务主链路（handleSubmitResult 不处理），
     * 仅供同步试听 Service 直接读取返回前端，避免试听音频占用对象存储。
     */
    private String audioBase64;

    /**
     * 同步合成音频时长（毫秒）：TTS provider 从厂商响应解析填充
     * （MiniMax {@code extra_info.audio_length}）。厂商未返回时为 null。
     * handleSubmitResult 据此回写 {@code aid_media_task.output_duration_seconds}（向上取整秒，宁高勿低），
     * 供业务侧回填 {@code aid_audio_record.duration_ms}、对口型时长校验与合成对齐消费。
     */
    private Long audioDurationMs;
}
