package com.aid.billing.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.enums.MeterType;
import com.aid.billing.enums.TextSettleStatus;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.model.BillingRule;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingPreHoldCalculationService;
import com.aid.billing.service.BillingFacadeService;
import com.aid.billing.service.BillingRuleResolver;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.billing.util.TextReasoningBillingResolver;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.service.IMediaBillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 计费门面服务实现：规则解析+金额计算 → 委托账户执行层操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingFacadeServiceImpl implements BillingFacadeService {

    private final BillingAmountCalculator billingAmountCalculator;
    private final BillingPreHoldCalculationService billingPreHoldCalculationService;
    private final BillingRuleResolver billingRuleResolver;
    private final IMediaBillingService mediaBillingService;
    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final IAccountUpdateService accountUpdateService;

    private static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_FORMAT);

    /**
     * {@code task.resultText} 长度硬上限（字符数）。
     * LONGTEXT 列物理上支持 4GB，但业务侧按 `length() * 4 / 5` 估算 token 时超大字符串会直接把扣费打爆；
     * 100 万字符 ≈ 50 万 token，已覆盖任何合法 LLM 输出，超过部分一律视为异常/污染数据。
     */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareBilling(AidMediaTask task, AiModelConfigVo modelConfig, BillingInput billingInput) {
        if (billingInput.isInputMetadataPending()) throw new ServiceException("素材尚未完成核验");
        BillingCalcResult calcResult = billingPreHoldCalculationService.calculate(modelConfig, billingInput);
        if (!calcResult.isMatched()) {
            log.error("预扣计费失败, modelCode={}, error={}", modelConfig.getModelCode(), calcResult.getErrorMessage());
            throw new ServiceException("计费规则缺失");
        }

        // 计算器已完成：官方原价 × 模型基础倍率 × 单模型倍率。
        BigDecimal adjustedAmount = calcResult.getAmount();
        String meterType = calcResult.getSnapshot() != null ? calcResult.getSnapshot().getMeterType() : "UNKNOWN";
        log.info("预扣计费, model={}, meterType={}, calculated={}, adjusted={}",
                modelConfig.getModelCode(), meterType, calcResult.getAmount(), adjustedAmount);
        if ("TOKEN".equals(meterType)) {
            log.info("[预冻结-TOKEN] finalPreHold={}", adjustedAmount);
        }

        // 写入计费参数和快照到任务
        if (billingInput != null && billingInput.getParams() != null) {
            task.setBillingParamJson(JSONUtil.toJsonStr(billingInput.getParams()));
        }
        if (calcResult.getSnapshot() != null) {
            task.setBillingSnapshotJson(JSONUtil.toJsonStr(calcResult.getSnapshot()));
        }

        // 用计算出的金额覆盖modelConfig的costCredits
        AiModelConfigVo overrideConfig = copyWithAmount(modelConfig, adjustedAmount);

        // 委托给账户执行层做冻结
        mediaBillingService.prepareBilling(task, overrideConfig);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean settleBilling(AidMediaTask task, Map<String, Object> usageData) {
        // 解析快照，按 meterType 分发结算逻辑
        BillingSnapshot snapshot = parseSnapshot(task.getBillingSnapshotJson());
        if (snapshot != null && "PROVIDER".equals(snapshot.getPayerType())) {
            return settleOperatorBilling(task, snapshot, usageData);
        }
        MeterType meterType = snapshot != null ? MeterType.of(snapshot.getMeterType()) : null;
        log.info("结算分发: taskId={}, meterType={}", task.getId(), meterType);

        if (meterType == MeterType.PER_IMAGE) {
            // PER_IMAGE：按实际张数 × 单张价结算，多退不补
            return settleImageBilling(task, snapshot, usageData);
        }

        if (meterType == MeterType.TOKEN || meterType == MeterType.PER_SECOND) {
            // TOKEN / PER_SECOND：走差额结算（实际用量重算，只退不补）
            // 先完成账户层结算，再走差额退款
        } else {
            // SKU_PACKAGE / null：直接按预扣金额结算
            boolean result = mediaBillingService.settleBilling(task);
            if (result) {
                task.setActualCost(task.getFrozenAmount());
                updateTaskSnapshotActualCost(task, task.getFrozenAmount());
            }
            return result;
        }

        // === 文本SKU结算：三步CAS ===

        // 第一步：账户层结算（CAS billing_status FROZEN→SUCCESS）
        boolean settleResult = mediaBillingService.settleBilling(task);
        if (!settleResult) {
            return false;
        }

        // 第二步：text_settle_status CAS（INIT→PROCESSING），只有一个线程能抢到
        boolean casSuccess = casTextSettleStatus(task.getId(), TextSettleStatus.INIT, TextSettleStatus.PROCESSING);
        if (!casSuccess) {
            // 已经被其他线程处理，从DB回读最新状态同步到内存对象，防止外层updateById覆盖
            syncTextSettleStatusFromDb(task);
            log.info("文本结算CAS未抢到, 跳过, taskId={}, currentStatus={}", task.getId(), task.getTextSettleStatus());
            return true;
        }
        // 同步内存对象，防止外层updateById把DB里的PROCESSING覆盖回INIT
        task.setTextSettleStatus(TextSettleStatus.PROCESSING);

        try {
            // 思考请求缺少供应商完整 usage 时不得按可见正文退款：思考 token 不可见。
            // 保留原始部分 usage 交给计算器写入已捕获侧和完整性审计；计算器会按预冻结上限保守结算。
            Map<String, Object> effectiveUsage = usageData;
            if (meterType == MeterType.TOKEN && !hasCompleteTokenUsage(effectiveUsage)) {
                log.error("文本请求未返回完整父级usage，按预冻结结算, taskId={}, model={}",
                        task.getId(), task.getModelName());
            }

            // 重新计算实际金额
            BigDecimal preHoldAmount = task.getFrozenAmount();
            BillingCalcResult settleCalc = billingAmountCalculator.calculateSettleAmount(
                    preHoldAmount, task.getBillingSnapshotJson(), effectiveUsage);

            BigDecimal actualAmount = BillingConstants.normalizeAccountAmount(settleCalc.getAmount());
            logTextSettleSummary(task, snapshot, settleCalc, preHoldAmount, actualAmount, effectiveUsage);

            // 持久化快照（含textSettleDone审计标记）— 实际金额/退款金额用倍率后值，保证审计口径一致
            if (settleCalc.getSnapshot() != null) {
                settleCalc.getSnapshot().setSettleTime(LocalDateTime.now().format(DATETIME_FORMATTER));
            }

            // 是否存在部分补扣（用于决定终态：DONE / PARTIAL_DONE）
            boolean partialExtraCharge = false;

            if (actualAmount != null && actualAmount.compareTo(preHoldAmount) < 0) {
                // 执行差额退款
                BigDecimal refundAmount = preHoldAmount.subtract(actualAmount);
                String refundDesc = meterType == MeterType.PER_SECOND ? "按秒差额退款" : "文本生成差额退款";
                refundDifference(task.getUserId(), refundAmount, task.getBillingTraceId(), refundDesc);
                task.setActualCost(actualAmount);
                // 快照写入倍率后的金额
                if (settleCalc.getSnapshot() != null) {
                    settleCalc.getSnapshot().setActualAmount(actualAmount);
                    settleCalc.getSnapshot().setRefundAmount(refundAmount);
                }
                log.info("差额退款, meterType={}, userId={}, taskId={}, preHold={}, actual={}, refund={}",
                        meterType, task.getUserId(), task.getId(), preHoldAmount, actualAmount, refundAmount);
                logTextRefundSummary(task, snapshot, preHoldAmount, actualAmount, refundAmount);
            } else if (meterType == MeterType.TOKEN && actualAmount != null
                    && actualAmount.compareTo(preHoldAmount) > 0) {
                BigDecimal extraRequired = actualAmount.subtract(preHoldAmount);
                if (!allowsExtraCharge(snapshot)) {
                    task.setActualCost(preHoldAmount);
                    if (settleCalc.getSnapshot() != null) {
                        settleCalc.getSnapshot().setActualAmount(preHoldAmount);
                        settleCalc.getSnapshot().setRefundAmount(BigDecimal.ZERO);
                        settleCalc.getSnapshot().setExtraChargeRequired(extraRequired);
                        settleCalc.getSnapshot().setExtraChargeActual(BigDecimal.ZERO);
                        settleCalc.getSnapshot().setPartialExtraCharge(Boolean.FALSE);
                    }
                    log.info("TOKEN超预扣但规则禁止补扣，按预冻结封顶, taskId={}, preHold={}, calculated={}",
                            task.getId(), preHoldAmount, actualAmount);
                } else {
                // TOKEN 补扣：仅规则明确允许时从可用余额补扣差额。
                BigDecimal actualExtraCharged = accountUpdateService.settleExtraCharge(
                        task.getUserId(), extraRequired, task.getBillingTraceId(),
                        "settle_extra", "TOKEN超预扣补扣");
                boolean partial = actualExtraCharged.compareTo(extraRequired) < 0;
                partialExtraCharge = partial;
                BigDecimal finalSettled = preHoldAmount.add(actualExtraCharged);
                task.setActualCost(finalSettled);
                // 快照回填补扣审计字段
                if (settleCalc.getSnapshot() != null) {
                    settleCalc.getSnapshot().setActualAmount(finalSettled);
                    settleCalc.getSnapshot().setRefundAmount(BigDecimal.ZERO);
                    settleCalc.getSnapshot().setExtraChargeRequired(extraRequired);
                    settleCalc.getSnapshot().setExtraChargeActual(actualExtraCharged);
                    settleCalc.getSnapshot().setPartialExtraCharge(partial);
                }
                // 补扣明细日志
                logTokenExtraCharge(task, snapshot, meterType, preHoldAmount,
                        actualAmount, extraRequired, actualExtraCharged, partial, finalSettled);
                }
            } else {
                // actual == preHold 或非 TOKEN 的 actual >= preHold，按预扣金额结算
                task.setActualCost(preHoldAmount);
                if (settleCalc.getSnapshot() != null) {
                    settleCalc.getSnapshot().setActualAmount(preHoldAmount);
                    settleCalc.getSnapshot().setRefundAmount(BigDecimal.ZERO);
                }
            }

            // 序列化快照（必须在金额回填之后）
            if (settleCalc.getSnapshot() != null) {
                task.setBillingSnapshotJson(JSONUtil.toJsonStr(settleCalc.getSnapshot()));
            }

            // 第三步：CAS text_settle_status PROCESSING→终态
            // 部分补扣 → PARTIAL_DONE（后续定时任务追补），全额 → DONE
            String targetStatus = partialExtraCharge ? TextSettleStatus.PARTIAL_DONE : TextSettleStatus.DONE;
            boolean doneCas = casTextSettleStatus(task.getId(), TextSettleStatus.PROCESSING, targetStatus);
            if (!doneCas) {
                log.warn("文本结算PROCESSING→{}的CAS失败, 强制兜底, taskId={}", targetStatus, task.getId());
                forceSetTextSettleStatus(task.getId(), targetStatus);
            }
            // 同步内存对象，防止外层updateById覆盖
            task.setTextSettleStatus(targetStatus);
            if (partialExtraCharge) {
                log.info("部分补扣，任务进入PARTIAL_DONE待追补, taskId={}, userId={}", task.getId(), task.getUserId());
            }
        } catch (Exception e) {
            // 异常时回滚PROCESSING→INIT，允许下次重试
            try {
                casTextSettleStatus(task.getId(), TextSettleStatus.PROCESSING, TextSettleStatus.INIT);
                task.setTextSettleStatus(TextSettleStatus.INIT);
            } catch (Exception ex) {
                log.error("文本结算异常回滚text_settle_status失败, taskId={}", task.getId(), ex);
            }
            throw e;
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareOperatorBilling(AidMediaTask task, AiModelConfigVo modelConfig, BillingInput billingInput, Long adminId) {
        if (billingInput.isInputMetadataPending()) throw new ServiceException("素材尚未完成核验");
        if (adminId == null || adminId <= 0 || !Objects.equals(task.getUserId(), -adminId)) {
            throw new ServiceException("计费身份无效");
        }
        BillingCalcResult calculation = billingPreHoldCalculationService.calculate(modelConfig, billingInput);
        if (!calculation.isMatched() || calculation.getSnapshot() == null || calculation.getSnapshot().getBaseAmount() == null) {
            throw new ServiceException("计费规则缺失");
        }
        BillingSnapshot snapshot = calculation.getSnapshot();
        snapshot.setPayerType("PROVIDER");
        snapshot.setPayerAdminId(adminId);
        snapshot.setProviderEstimatedCostCny(snapshot.getBaseAmount());
        snapshot.setPreHoldAmount(BigDecimal.ZERO);
        task.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        task.setBillingParamJson(JSONUtil.toJsonStr(billingInput.getParams()));
        mediaBillingService.prepareBilling(task, copyWithAmount(modelConfig, BigDecimal.ZERO));
    }

    private boolean settleOperatorBilling(AidMediaTask task, BillingSnapshot snapshot, Map<String, Object> usageData) {
        if (snapshot.getPayerAdminId() == null || !Objects.equals(task.getUserId(), -snapshot.getPayerAdminId())
                || task.getFrozenAmount() == null || task.getFrozenAmount().signum() != 0) {
            throw new ServiceException("计费身份无效");
        }
        if (!mediaBillingService.settleBilling(task)) return false;
        // 成本与用户积分分开：复用冻结价格及 Token 用量解析，但不进行用户补扣或退款。
        if ("TOKEN".equals(snapshot.getMeterType())) {
            BillingSnapshot costSnapshot = JSONUtil.toBean(JSONUtil.toJsonStr(snapshot), BillingSnapshot.class);
            costSnapshot.setIsFree(false);
            BillingCalcResult calculation = billingAmountCalculator.calculateSettleAmount(BigDecimal.ZERO,
                    JSONUtil.toJsonStr(costSnapshot), usageData);
            if (calculation.getSnapshot() != null) {
                Boolean userFree = snapshot.getIsFree();
                BeanUtil.copyProperties(calculation.getSnapshot(), snapshot);
                snapshot.setIsFree(userFree);
                snapshot.setPreHoldAmount(BigDecimal.ZERO);
            }
        } else if ("SKU_PACKAGE".equals(snapshot.getMeterType())) {
            snapshot.setProviderActualCostCny(snapshot.getProviderEstimatedCostCny());
        } else if ("PER_CHAR".equals(snapshot.getMeterType()) && usageData != null && usageData.get("usage_characters") != null) {
            try {
                BigDecimal chars = new BigDecimal(String.valueOf(usageData.get("usage_characters")));
                BillingRule rule = billingRuleResolver.parseRule(copySnapshotConfig(snapshot));
                if (chars.signum() >= 0 && chars.scale() <= 0 && rule != null && rule.getSkus() != null) {
                    snapshot.setProviderActualCharacters(chars.longValueExact());
                    rule.getSkus().stream().filter(sku -> Objects.equals(sku.getSkuCode(), snapshot.getSkuCode()))
                            .filter(sku -> sku.getPricePerChar() != null && sku.getPricePerChar().signum() >= 0).findFirst()
                            .ifPresent(sku -> snapshot.setProviderActualCostCny(chars.multiply(sku.getPricePerChar())
                                    .add(snapshot.getFixedSurcharge() == null ? BigDecimal.ZERO : snapshot.getFixedSurcharge())));
                }
            } catch (RuntimeException ignored) {
                log.info("站长音频实际用量未确认, taskId={}", task.getId());
            }
        }
        snapshot.setActualAmount(BigDecimal.ZERO);
        snapshot.setRefundAmount(BigDecimal.ZERO);
        snapshot.setTextSettleDone(true);
        snapshot.setSettleTime(LocalDateTime.now().format(DATETIME_FORMATTER));
        task.setActualCost(BigDecimal.ZERO);
        task.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        return true;
    }

    private AiModelConfigVo copySnapshotConfig(BillingSnapshot snapshot) {
        AiModelConfigVo config = new AiModelConfigVo();
        config.setModelCode(snapshot.getModelName());
        config.setBillingMode(snapshot.getBillingMode());
        config.setBillingRuleJson(snapshot.getBillingRuleJson());
        return config;
    }

    private boolean allowsExtraCharge(BillingSnapshot snapshot) {
        if (snapshot == null || CharSequenceUtil.isBlank(snapshot.getBillingRuleJson())) {
            return false;
        }
        try {
            AiModelConfigVo config = new AiModelConfigVo();
            config.setBillingMode(BillingConstants.MODE_SKU);
            config.setBillingRuleJson(snapshot.getBillingRuleJson());
            BillingRule rule = billingRuleResolver.parseRule(config);
            return rule != null && rule.getSettleRule() != null
                    && rule.getSettleRule().isAllowExtraCharge();
        } catch (Exception e) {
            log.error("补扣规则解析失败，按禁止补扣处理, model={}", snapshot.getModelName(), e);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean refundBilling(AidMediaTask task) {
        BillingSnapshot snapshotForLog = task == null ? null : parseSnapshot(task.getBillingSnapshotJson());
        MeterType meterType = snapshotForLog != null ? MeterType.of(snapshotForLog.getMeterType()) : null;
        BigDecimal preHoldAmount = task != null && task.getFrozenAmount() != null
                ? task.getFrozenAmount() : BigDecimal.ZERO;
        boolean result = mediaBillingService.refundBilling(task);
        if (result) {
            task.setActualCost(BigDecimal.ZERO);
            // 统一退款日志：按 meterType 打印
            String refundReason = task != null && CharSequenceUtil.isNotBlank(task.getErrorMessage())
                    ? task.getErrorMessage() : "task_failed_full_refund";
            log.info("[退款] taskId={}, meterType={}, preHold={}, actual=0, refund={}, reason={}",
                    task != null ? task.getId() : null, meterType, preHoldAmount, preHoldAmount, refundReason);
            if (meterType == MeterType.PER_IMAGE && snapshotForLog != null) {
                BigDecimal unitPrice = snapshotForLog.getUnitPrice() != null ? snapshotForLog.getUnitPrice() : BigDecimal.ZERO;
                int expectedCount = snapshotForLog.getExpectedImageCount() != null ? snapshotForLog.getExpectedImageCount() : 0;
                BigDecimal finalMultiplier = resolveFinalMultiplierFromSnapshot(snapshotForLog);
                logImageRefundSummary(task, snapshotForLog, unitPrice, expectedCount, finalMultiplier,
                        preHoldAmount, BigDecimal.ZERO, preHoldAmount, refundReason);
            }
        }
        return result;
    }

    /**
     * 从快照中读取最终综合倍率（modelMultiplier × globalMultiplier，预扣时已冻结）。
     * 快照中无此字段时降级为 1.0（兼容老任务，不影响历史账务）。
     */
    private BigDecimal resolveFinalMultiplierFromSnapshot(BillingSnapshot snapshot) {
        if (snapshot != null && snapshot.getFinalBillingMultiplier() != null
                && snapshot.getFinalBillingMultiplier().compareTo(BigDecimal.ZERO) > 0) {
            return snapshot.getFinalBillingMultiplier();
        }
        return BigDecimal.ONE;
    }

    /**
     * CAS更新text_settle_status：只有当前值等于expected时才更新为target。
     * 返回true表示抢到（受影响行数=1），false表示未抢到。
     */
    private boolean casTextSettleStatus(Long taskId, String expected, String target) {
        int rows = aidMediaTaskMapper.update(null,
                Wrappers.<AidMediaTask>lambdaUpdate()
                        .eq(AidMediaTask::getId, taskId)
                        .eq(AidMediaTask::getTextSettleStatus, expected)
                        .set(AidMediaTask::getTextSettleStatus, target));
        return rows > 0;
    }

    /**
     * 强制设置text_settle_status（无CAS条件），用于兜底场景。
     */
    private void forceSetTextSettleStatus(Long taskId, String target) {
        aidMediaTaskMapper.update(null,
                Wrappers.<AidMediaTask>lambdaUpdate()
                        .eq(AidMediaTask::getId, taskId)
                        .set(AidMediaTask::getTextSettleStatus, target));
    }

    /**
     * 从DB回读text_settle_status同步到内存task对象，防止外层updateById覆盖DB值。
     */
    private void syncTextSettleStatusFromDb(AidMediaTask task) {
        AidMediaTask fresh = aidMediaTaskMapper.selectOne(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .eq(AidMediaTask::getId, task.getId())
                        .select(AidMediaTask::getId, AidMediaTask::getTextSettleStatus));
        if (fresh != null) {
            task.setTextSettleStatus(fresh.getTextSettleStatus());
        }
    }

    private boolean hasCompleteTokenUsage(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return false;
        }
        if (usage.containsKey("provider_usage_captured")
                && !Boolean.parseBoolean(String.valueOf(usage.get("provider_usage_captured")))) {
            return false;
        }
        if (usage.containsKey("input_usage_complete")
                && !Boolean.parseBoolean(String.valueOf(usage.get("input_usage_complete")))) {
            return false;
        }
        if (usage.containsKey("output_usage_complete")
                && !Boolean.parseBoolean(String.valueOf(usage.get("output_usage_complete")))) {
            return false;
        }
        boolean hasInput = usage.containsKey("input_tokens") || usage.containsKey("prompt_tokens");
        boolean hasOutput = usage.containsKey("output_tokens") || usage.containsKey("completion_tokens");
        return hasInput && hasOutput;
    }

    private int safeToInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("inputChars转整数失败, value={}", value);
            return 0;
        }
    }

    /**
     * 从任务的 billingSnapshotJson → billingRuleJson → settleRule 解析 charToTokenRatio。
     */
    private int resolveCharToTokenRatioFromTask(AidMediaTask task) {
        BillingSnapshot snapshot = parseSnapshot(task.getBillingSnapshotJson());
        if (snapshot != null && snapshot.getBillingRuleJson() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.aid.billing.model.BillingRule rule = om.readValue(snapshot.getBillingRuleJson(), com.aid.billing.model.BillingRule.class);
                if (rule.getSettleRule() != null && rule.getSettleRule().getCharToTokenRatio() > 0) {
                    return rule.getSettleRule().getCharToTokenRatio();
                }
            } catch (Exception e) {
                log.warn("从快照解析charToTokenRatio失败，使用默认值", e);
            }
        }
        return BillingConstants.DEFAULT_CHAR_TO_TOKEN_RATIO;
    }

    /**
     * 向上取整整数除法：ceil(a / b)。
     * 预扣和降级结算都必须向上取整，避免系统性少扣。
     */
    private static int ceilDiv(int a, int b) {
        if (b <= 0) {
            return a;
        }
        return (a + b - 1) / b;
    }

    /**
     * 图片二次结算：按 provider 实际返回张数 × 单张价 计算实际金额，采用"只退不补"策略。
     */
    private boolean settleImageBilling(AidMediaTask task, BillingSnapshot snapshot, Map<String, Object> usageData) {
        boolean settled = mediaBillingService.settleBilling(task);
        if (!settled) {
            return false;
        }

        BigDecimal preHoldAmount = task.getFrozenAmount() == null ? BigDecimal.ZERO : task.getFrozenAmount();
        int actualImageCount = resolveActualImageCount(snapshot, usageData);
        BigDecimal unitPrice = snapshot != null && snapshot.getUnitPrice() != null
                ? snapshot.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal finalMultiplier = resolveFinalMultiplierFromSnapshot(snapshot);
        // 输入媒体附加费（参考图按张等）：输入在提交时已消耗，结算按快照原样叠加，不随产出张数退差
        BigDecimal inputMediaBase = snapshot != null && snapshot.getInputMediaAmount() != null
                && snapshot.getInputMediaAmount().compareTo(BigDecimal.ZERO) > 0
                ? snapshot.getInputMediaAmount() : BigDecimal.ZERO;

        BigDecimal imageBase = imageOutputPixelBase(snapshot, usageData, actualImageCount, unitPrice);
        BigDecimal actualAmount = imageBase
                .add(inputMediaBase)
                .multiply(finalMultiplier);
        actualAmount = BillingConstants.normalizeAccountAmount(actualAmount);
        if (actualAmount.compareTo(preHoldAmount) > 0) {
            log.info("图片实际金额超过预扣, 按预扣封顶, taskId={}, preHold={}, calc={}",
                    task.getId(), preHoldAmount, actualAmount);
            actualAmount = preHoldAmount;
        }
        BigDecimal refundAmount = preHoldAmount.subtract(actualAmount);
        // 图片完成扣费明细日志：先打总账（与文本 LLM 同风格），再走差额退款（如有）
        logImageSettleSummary(task, snapshot, unitPrice, actualImageCount, finalMultiplier,
                preHoldAmount, actualAmount, refundAmount.max(BigDecimal.ZERO), usageData);
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            refundDifference(task.getUserId(), refundAmount, task.getBillingTraceId(), "图片生成差额退款");
            log.info("图片差额退款, userId={}, taskId={}, preHold={}, actual={}, refund={}, actualImageCount={}",
                    task.getUserId(), task.getId(), preHoldAmount, actualAmount, refundAmount, actualImageCount);
            // 图片退款详情：单独一条结构化日志
            logImageRefundSummary(task, snapshot, unitPrice, actualImageCount, finalMultiplier,
                    preHoldAmount, actualAmount, refundAmount, "image_settle_refund_diff");
        }

        task.setActualCost(actualAmount);
        if (snapshot != null) {
            snapshot.setActualImageCount(actualImageCount);
            snapshot.setSettledImageCount(actualImageCount);
            snapshot.setActualAmount(actualAmount);
            snapshot.setRefundAmount(refundAmount);
            snapshot.setSettleTime(LocalDateTime.now().format(DATETIME_FORMATTER));
            task.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        } else {
            updateTaskSnapshotActualCost(task, actualAmount);
        }
        return true;
    }

    /** Missing or inconsistent measured pixels settle at the frozen maximum unit price. */
    private BigDecimal imageOutputPixelBase(BillingSnapshot snapshot, Map<String, Object> usageData,
                                            int actualCount, BigDecimal maximumUnitPrice) {
        if (snapshot == null || snapshot.getBillingRuleJson() == null) {
            return maximumUnitPrice.multiply(BigDecimal.valueOf(actualCount));
        }
        com.aid.billing.model.BillingRule rule;
        try {
            rule = com.alibaba.fastjson2.JSON.parseObject(snapshot.getBillingRuleJson(),
                    com.aid.billing.model.BillingRule.class);
        } catch (RuntimeException invalid) {
            return maximumUnitPrice.multiply(BigDecimal.valueOf(actualCount));
        }
        var tiers = rule == null || rule.getSettleRule() == null
                ? null : rule.getSettleRule().getImageOutputPixelTiers();
        if (tiers == null || tiers.isEmpty()) {
            return maximumUnitPrice.multiply(BigDecimal.valueOf(actualCount));
        }
        Object rawPixels = usageData == null ? null : usageData.get("outputImagePixels");
        if (!(rawPixels instanceof java.util.List<?> pixels) || pixels.size() != actualCount) {
            return maximumUnitPrice.multiply(BigDecimal.valueOf(actualCount));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Object raw : pixels) {
            if (!(raw instanceof Number number) || number.longValue() <= 0) {
                return maximumUnitPrice.multiply(BigDecimal.valueOf(actualCount));
            }
            long pixelCount = number.longValue();
            BigDecimal price = null;
            for (var tier : tiers) {
                if (tier == null || tier.getPrice() == null || tier.getPrice().signum() < 0) continue;
                if (tier.getMaxPixels() == null || pixelCount <= tier.getMaxPixels()) {
                    price = tier.getPrice();
                    break;
                }
            }
            if (price == null) return maximumUnitPrice.multiply(BigDecimal.valueOf(actualCount));
            total = total.add(price.min(maximumUnitPrice));
        }
        return total;
    }

    /**
     * 从 usageData / snapshot 解析实际产出张数：。
     */
    private int resolveActualImageCount(BillingSnapshot snapshot, Map<String, Object> usageData) {
        if (usageData != null) {
            Object a = usageData.get("actualImageCount");
            if (a != null) {
                int v = safeToInt(a);
                if (v > 0) {
                    return v;
                }
            }
            Object b = usageData.get("resultCount");
            if (b != null) {
                int v = safeToInt(b);
                if (v > 0) {
                    return v;
                }
            }
        }
        if (snapshot != null && snapshot.getActualImageCount() != null && snapshot.getActualImageCount() > 0) {
            return snapshot.getActualImageCount();
        }
        return 1;
    }

    /**
     * 差额退款：委托统一账户执行器，保证同用户串行 + 独立事务 + 统一流水
     */
    private void refundDifference(Long userId, BigDecimal refundAmount, String traceId,String tip) {
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        accountUpdateService.settleRefund(userId, refundAmount, traceId, "settle_refund", tip);
    }

    private AiModelConfigVo copyWithAmount(AiModelConfigVo original, BigDecimal amount) {
        AiModelConfigVo copy = new AiModelConfigVo();
        copy.setId(original.getId());
        copy.setProviderId(original.getProviderId());
        copy.setModelCode(original.getModelCode());
        copy.setModelName(original.getModelName());
        copy.setModelType(original.getModelType());
        copy.setCostCredits(amount);
        copy.setApiVersion(original.getApiVersion());
        copy.setApiSuffix(original.getApiSuffix());
        copy.setPriority(original.getPriority());
        copy.setBillingMode(original.getBillingMode());
        copy.setBillingRuleJson(original.getBillingRuleJson());
        copy.setBillingVersion(original.getBillingVersion());
        copy.setIsFree(original.getIsFree());
        copy.setBaseUrl(original.getBaseUrl());
        copy.setApiKey(original.getApiKey());
        copy.setApiSecret(original.getApiSecret());
        copy.setProviderCode(original.getProviderCode());
        copy.setProviderName(original.getProviderName());
        return copy;
    }

    private BillingSnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSONUtil.toBean(json, BillingSnapshot.class);
        } catch (Exception e) {
            log.error("计费快照解析失败", e);
            return null;
        }
    }

    private void updateTaskSnapshotActualCost(AidMediaTask task, BigDecimal actualCost) {
        BillingSnapshot snapshot = parseSnapshot(task.getBillingSnapshotJson());
        if (snapshot != null) {
            snapshot.setActualAmount(actualCost);
            snapshot.setSettleTime(LocalDateTime.now().format(DATETIME_FORMATTER));
            task.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        }
    }

    /**
     * 从快照的 requestParams 里取尺寸（仅日志展示）。
     */
    private Object resolveSnapshotSize(BillingSnapshot snapshot) {
        if (snapshot == null || snapshot.getRequestParams() == null) {
            return null;
        }
        Object size = snapshot.getRequestParams().get("size");
        return size != null ? size : snapshot.getRequestParams().get("resolution");
    }

    /**
     * 图片完成扣费汇总日志：与 logTextSettleSummary 同风格。
     * 包含 actualImageCount / unitPrice / 倍率 / preHold / actual / refund 全量字段，
     * 公式：finalActual = unitPrice * actualImageCount * finalMultiplier（封顶 preHold）。
     */
    private void logImageSettleSummary(AidMediaTask task, BillingSnapshot snapshot, BigDecimal unitPrice,
                                       int actualImageCount, BigDecimal finalMultiplier,
                                       BigDecimal preHoldAmount, BigDecimal actualAmount, BigDecimal refundAmount,
                                       Map<String, Object> usageData) {
        BigDecimal modelMultiplier = snapshot != null && snapshot.getModelBillingMultiplier() != null ? snapshot.getModelBillingMultiplier() : BigDecimal.ONE;
        BigDecimal globalMultiplier = snapshot != null && snapshot.getGlobalBillingMultiplier() != null ? snapshot.getGlobalBillingMultiplier() : BigDecimal.ONE;
        BigDecimal baseAmount = unitPrice.multiply(BigDecimal.valueOf(actualImageCount));
        BigDecimal rawActualBeforeCap = baseAmount.multiply(finalMultiplier);
        Integer expectedImageCount = snapshot == null ? null : snapshot.getExpectedImageCount();
        log.info("图片完成扣费: taskId={}, userId={}, modelName={}, billingMode={}, skuCode={}, skuName={}, generateMode={}, size={}",
                task.getId(), task.getUserId(),
                snapshot == null ? task.getModelName() : snapshot.getModelName(),
                snapshot == null ? null : snapshot.getBillingMode(),
                snapshot == null ? null : snapshot.getSkuCode(),
                snapshot == null ? null : snapshot.getSkuName(),
                snapshot == null ? null : snapshot.getGenerateMode(),
                resolveSnapshotSize(snapshot));
        log.info("图片完成扣费: expectedImageCount={}, actualImageCount={}, unitPrice={}, baseAmount={} (= {} * {})",
                expectedImageCount, actualImageCount, unitPrice, baseAmount, unitPrice, actualImageCount);
        log.info("图片完成扣费: modelMultiplier={}, globalMultiplier={}, finalMultiplier={}",
                modelMultiplier, globalMultiplier, finalMultiplier);
        log.info("图片完成扣费公式: rawActual={} = baseAmount({}) * finalMultiplier({}), preHold={}, finalActual={} (按 preHold 封顶), refund={}",
                rawActualBeforeCap, baseAmount, finalMultiplier, preHoldAmount, actualAmount, refundAmount);
        // 补充 token 用量日志（仅 Gemini 图片等模型会携带，其它图片模型 usageData 中无 token 字段时不打印）
        if (usageData != null && usageData.containsKey("total_tokens")) {
            log.info("图片完成扣费token: taskId={}, prompt_tokens={}, completion_tokens={}, input_tokens={}, output_tokens={}, total_tokens={}",
                    task.getId(),
                    usageData.get("prompt_tokens"), usageData.get("completion_tokens"),
                    usageData.get("input_tokens"), usageData.get("output_tokens"),
                    usageData.get("total_tokens"));
        }
    }

    /**
     * 图片退款汇总日志：差额退款 / 任务失败全额退款 共用。
     * 包含 taskId / modelName / preHold / actual / refund / 退款原因 / 张数 / 单价 / 倍率。
     */
    private void logImageRefundSummary(AidMediaTask task, BillingSnapshot snapshot, BigDecimal unitPrice,
                                       int imageCount, BigDecimal finalMultiplier,
                                       BigDecimal preHoldAmount, BigDecimal actualAmount,
                                       BigDecimal refundAmount, String reason) {
        BigDecimal modelMultiplier = snapshot != null && snapshot.getModelBillingMultiplier() != null ? snapshot.getModelBillingMultiplier() : BigDecimal.ONE;
        BigDecimal globalMultiplier = snapshot != null && snapshot.getGlobalBillingMultiplier() != null ? snapshot.getGlobalBillingMultiplier() : BigDecimal.ONE;
        log.info("图片退款明细: taskId={}, userId={}, modelName={}, billingMode={}, skuCode={}, skuName={}, reason={}",
                task.getId(), task.getUserId(),
                snapshot == null ? task.getModelName() : snapshot.getModelName(),
                snapshot == null ? null : snapshot.getBillingMode(),
                snapshot == null ? null : snapshot.getSkuCode(),
                snapshot == null ? null : snapshot.getSkuName(),
                reason);
        log.info("图片退款明细: imageCount={}, unitPrice={}, modelMultiplier={}, globalMultiplier={}, finalMultiplier={}",
                imageCount, unitPrice, modelMultiplier, globalMultiplier, finalMultiplier);
        log.info("图片退款明细: preHold={}, actual={}, refund={} (= preHold - actual)",
                preHoldAmount, actualAmount, refundAmount);
    }

    private void logTextSettleSummary(AidMediaTask task, BillingSnapshot snapshot, BillingCalcResult settleCalc,
                                      BigDecimal preHoldAmount, BigDecimal actualAmount,
                                      Map<String, Object> usageData) {
        BillingSnapshot settleSnapshot = settleCalc.getSnapshot() == null ? snapshot : settleCalc.getSnapshot();
        BigDecimal refundAmount = preHoldAmount.subtract(actualAmount);
        log.info("文本LLM完成扣费: taskId={}, userId={}, modelName={}, billingMode={}, skuCode={}, skuName={}, usageSource={}",
                task.getId(), task.getUserId(),
                settleSnapshot == null ? null : settleSnapshot.getModelName(),
                settleSnapshot == null ? null : settleSnapshot.getBillingMode(),
                settleSnapshot == null ? null : settleSnapshot.getSkuCode(),
                settleSnapshot == null ? null : settleSnapshot.getSkuName(),
                resolveUsageSource(usageData));
        log.info("文本LLM完成扣费: actualInputTokens={}, actualOutputTokens={}, modelMultiplier={}, globalMultiplier={}, finalMultiplier={}",
                settleSnapshot == null ? null : settleSnapshot.getActualInputTokens(),
                settleSnapshot == null ? null : settleSnapshot.getActualOutputTokens(),
                settleSnapshot == null ? null : settleSnapshot.getModelBillingMultiplier(),
                settleSnapshot == null ? null : settleSnapshot.getGlobalBillingMultiplier(),
                settleSnapshot == null ? null : settleSnapshot.getFinalBillingMultiplier());
        log.info("文本LLM完成扣费公式: finalActual={}, preHold={}, refund={}",
                actualAmount, preHoldAmount, refundAmount.max(BigDecimal.ZERO));
    }

    private void logTextRefundSummary(AidMediaTask task, BillingSnapshot snapshot,
                                      BigDecimal preHoldAmount, BigDecimal actualAmount, BigDecimal refundAmount) {
        log.info("文本LLM退款说明: taskId={}, userId={}, modelName={}, skuCode={}, preHold={}, actual={}, refund={} = {} - {}",
                task.getId(), task.getUserId(),
                snapshot == null ? null : snapshot.getModelName(),
                snapshot == null ? null : snapshot.getSkuCode(),
                preHoldAmount, actualAmount, refundAmount, preHoldAmount, actualAmount);
    }

    /**
     * TOKEN 超预冻结补扣明细日志：包含完整审计字段。
     */
    private void logTokenExtraCharge(AidMediaTask task, BillingSnapshot snapshot, MeterType meterType,
                                      BigDecimal preHoldAmount, BigDecimal actualAmount,
                                      BigDecimal extraRequired, BigDecimal actualExtraCharged,
                                      boolean partial, BigDecimal finalSettled) {
        log.info("[TOKEN补扣] taskId={}, userId={}, meterType={}, modelName={}, preHold={}, actual={}, extra={}, "
                        + "currentBalanceAfterSettle=查看流水, expectedExtraCharge={}, actualExtraCharged={}, "
                        + "partialCharge={}, finalSettledAmount={}",
                task.getId(), task.getUserId(), meterType,
                snapshot == null ? task.getModelName() : snapshot.getModelName(),
                preHoldAmount, actualAmount, extraRequired,
                extraRequired, actualExtraCharged, partial, finalSettled);
        if (partial) {
            log.info("[TOKEN补扣] 余额不足，已按最大余额部分补扣, taskId={}, userId={}, 应补={}, 实补={}",
                    task.getId(), task.getUserId(), extraRequired, actualExtraCharged);
        }
    }

    /**
     * 追补扫描：扫描 text_settle_status=PARTIAL_DONE 的媒体任务，从可用余额追补剩余差额。
     * 全额补完 → DONE，仍不足 → 保持 PARTIAL_DONE（下次扫描继续）。
     */
    @Override
    public int retryPartialExtraCharges(int batchSize) {
        int safeBatch = Math.max(1, Math.min(batchSize <= 0 ? 50 : batchSize, 500));
        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .eq(AidMediaTask::getTextSettleStatus, TextSettleStatus.PARTIAL_DONE)
                        .select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getBillingTraceId,
                                AidMediaTask::getBillingSnapshotJson, AidMediaTask::getFrozenAmount, AidMediaTask::getActualCost)
                        .last("LIMIT " + safeBatch)
        );
        int processed = 0;
        for (AidMediaTask task : tasks) {
            try {
                boolean ok = retryPartialForMediaTask(task);
                if (ok) {
                    processed++;
                }
            } catch (Exception e) {
                log.error("媒体任务追补失败, taskId={}", task.getId(), e);
            }
        }
        if (processed > 0) {
            log.info("媒体任务追补扫描完成, total={}, processed={}", tasks.size(), processed);
        }
        return processed;
    }

    /**
     * 单个媒体任务追补：从快照读取 extraChargeRequired，调用 settleExtraCharge 追补剩余差额。
     * settleExtraCharge 内部按 traceId 累计已补扣金额，不会重复扣。
     */
    private boolean retryPartialForMediaTask(AidMediaTask task) {
        // 从快照读取补扣信息
        BillingSnapshot snapshot = parseSnapshot(task.getBillingSnapshotJson());
        if (snapshot == null || snapshot.getExtraChargeRequired() == null
                || snapshot.getExtraChargeRequired().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("追补跳过（快照缺少补扣信息）, taskId={}", task.getId());
            return false;
        }
        BigDecimal extraRequired = snapshot.getExtraChargeRequired();

        // 调用 settleExtraCharge（内部按 traceId 累计，返回累计总额）
        BigDecimal totalCharged = accountUpdateService.settleExtraCharge(
                task.getUserId(), extraRequired, task.getBillingTraceId(),
                "settle_extra", "TOKEN追补");
        boolean fullyCharged = totalCharged.compareTo(extraRequired) >= 0;
        BigDecimal preHold = task.getFrozenAmount() != null ? task.getFrozenAmount() : BigDecimal.ZERO;
        BigDecimal finalSettled = preHold.add(totalCharged);

        // 更新快照审计字段
        snapshot.setExtraChargeActual(totalCharged);
        snapshot.setPartialExtraCharge(!fullyCharged);
        snapshot.setActualAmount(finalSettled);
        String updatedSnapshotJson = JSONUtil.toJsonStr(snapshot);

        // CAS PARTIAL_DONE → DONE 或保持 PARTIAL_DONE（更新金额+快照）
        String targetStatus = fullyCharged ? TextSettleStatus.DONE : TextSettleStatus.PARTIAL_DONE;
        int rows = aidMediaTaskMapper.update(null,
                Wrappers.<AidMediaTask>lambdaUpdate()
                        .eq(AidMediaTask::getId, task.getId())
                        .eq(AidMediaTask::getTextSettleStatus, TextSettleStatus.PARTIAL_DONE)
                        .set(AidMediaTask::getTextSettleStatus, targetStatus)
                        .set(AidMediaTask::getActualCost, finalSettled)
                        .set(AidMediaTask::getBillingSnapshotJson, updatedSnapshotJson));
        if (rows > 0) {
            log.info("[媒体TOKEN追补] taskId={}, userId={}, extraRequired={}, totalCharged={}, fullyCharged={}, finalSettled={}",
                    task.getId(), task.getUserId(), extraRequired, totalCharged, fullyCharged, finalSettled);
        }
        return rows > 0;
    }

    private String resolveUsageSource(Map<String, Object> usageData) {
        if (usageData == null || usageData.isEmpty()) {
            return "EMPTY";
        }
        if (usageData.get("input_tokens") != null || usageData.get("output_tokens") != null
                || usageData.get("prompt_tokens") != null || usageData.get("completion_tokens") != null) {
            return "PROVIDER_REAL_USAGE";
        }
        if (usageData.get("input_tokens_estimate") != null || usageData.get("output_tokens_estimate") != null) {
            return "TOKEN_ESTIMATE";
        }
        if (usageData.get("total_chars_estimate") != null || usageData.get("output_chars_estimate") != null) {
            return "CHAR_ESTIMATE";
        }
        return "UNKNOWN";
    }
}
