package com.aid.billing.model;

import lombok.Data;
import java.util.List;

/**
 * 结算规则：主要给文本模型使用，采用只退不补（REFUND_ONLY）策略。
 */
@Data
public class SettleRule {

    /** 结算策略：DIRECT_SETTLE / REFUND_ONLY */
    private String settleMode;

    /** Usage来源：PROVIDER_USAGE（优先取上游） / ESTIMATE（内部估算） */
    private String usageSource;

    /** 字符转Token估算比例，默认2个字符算1个Token */
    private int charToTokenRatio;

    /** 是否允许退款 */
    private boolean allowRefund;

    /** 是否允许补扣 */
    private boolean allowExtraCharge;

    /** Token计费口径：AGGREGATE（父级总量）或 BUCKETED（互斥子桶）。 */
    private String usagePricingMode;

    /** Optional per-output-image pixel tiers; prices are frozen in billingRuleJson. */
    private List<ImageOutputPixelTier> imageOutputPixelTiers;
}
