package com.aid.aid.domain;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;
/** 已接受的诊断发送任务。 */
@Data
@TableName("aid_diagnostic_delivery")
public class AidDiagnosticDelivery {
    @TableId private String reportId;
    private String eventId;
    private String protectedOptions;
    private String envelopeJson;
    private String protectedReceipt;
    private String status;
    private String resultMessage;
    private Date createTime;
    private String createBy;
    private Date updateTime;
    private String updateBy;
}
