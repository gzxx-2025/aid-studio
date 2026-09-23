package com.aid.aid.domain;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;
/** 单次请求的完整诊断事件。 */
@Data
@TableName("aid_diagnostic_event")
public class AidDiagnosticEvent {
    @TableId private String eventId;
    private String requestId;
    private String correlationKey;
    private String requestTime;
    private Long userId;
    private String source;
    private String diagnosticJson;
    private String reportId;
    private Date createTime;
    private String createBy;
}
