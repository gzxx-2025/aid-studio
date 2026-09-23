package com.aid.aid.domain.media;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aid.common.aid.oss.annotation.MediaUrl;
import com.aid.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 媒体结果实体 aid_media_result
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aid_media_result")
public class AidMediaResult extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    // 关联主任务ID。
    private Long taskId;

    // 同一任务内的有序结果序号，从 0 开始。
    private Integer resultIndex;

    // 媒体类型：IMAGE / VIDEO。
    private String mediaType;

    // 上游原始URL。
    @MediaUrl
    private String originUrl;

    // OSS持久化URL（相对路径）。
    @MediaUrl
    private String ossUrl;

    // MIME 类型。
    private String mimeType;

    // 文件大小（Byte）。
    private Long fileSize;

    /** Structured output dimensions and layer placement; never stores the provider URL. */
    private String metadataJson;

    // 视频时长（秒），图片可为空。
    private Integer durationSeconds;
}
