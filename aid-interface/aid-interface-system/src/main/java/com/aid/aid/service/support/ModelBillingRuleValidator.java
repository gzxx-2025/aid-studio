package com.aid.aid.service.support;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.AidAiModel;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Set;

/** SKU 计费规则写入与启用共用的结构、口径和主价格校验。 */
@Slf4j
public final class ModelBillingRuleValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> METER_TYPES = Set.of(
            "TOKEN", "PER_IMAGE", "PER_SECOND", "SKU_PACKAGE", "PER_CHAR");
    private static final String CLIENT_MESSAGE = "计费规则无效";

    private ModelBillingRuleValidator() {
    }

    /**
     * SKU 模式存在规则时逐个校验启用档位；模型启用时还必须至少存在一个可计费档位。
     * SKU 缺少 meterType 时继承顶层口径；显式口径必须合法且不能用其它单位的价格字段兜底。
     */
    public static void validate(AidAiModel model) {
        if (model == null || !"SKU".equalsIgnoreCase(StrUtil.trim(model.getBillingMode()))) {
            return;
        }
        if (StrUtil.isBlank(model.getBillingRuleJson())) {
            if ("0".equals(model.getStatus())) {
                reject(model, null, "规则缺失");
            }
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(model.getBillingRuleJson());
            String fallbackMeterType = normalizeMeterType(root.path("meterType").asText(null));
            if (fallbackMeterType == null) {
                reject(model, null, "顶层meterType非法");
            }
            JsonNode skus = root.path("skus");
            if (!skus.isArray()) {
                reject(model, null, "skus不是数组");
            }
            int enabledCount = 0;
            BigDecimal minimumEnabledImagePrice = null;
            for (JsonNode sku : skus) {
                if (!sku.path("enabled").asBoolean(false)) {
                    continue;
                }
                enabledCount++;
                String skuCode = sku.path("skuCode").asText(null);
                String configuredMeterType = StrUtil.trim(sku.path("meterType").asText(null));
                boolean explicitMeterType = StrUtil.isNotBlank(configuredMeterType);
                String meterType = explicitMeterType
                        ? normalizeMeterType(configuredMeterType) : fallbackMeterType;
                if (meterType == null) {
                    reject(model, skuCode, "SKU meterType非法");
                }
                if (!hasValidMainPrice(sku, meterType, explicitMeterType)) {
                    reject(model, skuCode, meterType + "主价格缺失");
                }
                if ("PER_IMAGE".equals(meterType)) {
                    BigDecimal configuredPrice = sku.path("price").decimalValue();
                    minimumEnabledImagePrice = minimumEnabledImagePrice == null ? configuredPrice
                            : minimumEnabledImagePrice.min(configuredPrice);
                }
                if (sku.hasNonNull("outputPixelsPerUnit") && (!"PER_IMAGE".equals(meterType)
                        || !positive(sku.get("outputPixelsPerUnit"))
                        || !sku.get("outputPixelsPerUnit").isIntegralNumber()
                        || !sku.get("outputPixelsPerUnit").canConvertToLong())) {
                    reject(model, skuCode, "输出像素计费单位无效");
                }
                if (sku.hasNonNull("fixedSurcharge") && (!nonNegative(sku.get("fixedSurcharge"))
                        || !("PER_CHAR".equals(meterType) || "SKU_PACKAGE".equals(meterType)))) {
                    reject(model, skuCode, "固定附加费口径无效");
                }
                for (String price : Set.of("inputPricePerMillion", "outputPricePerMillion",
                        "cachedInputPricePerMillion", "cacheWritePricePerMillion", "reasoningPricePerMillion")) {
                    if (sku.hasNonNull(price) && !nonNegative(sku.get(price))) reject(model, skuCode, "Token价格无效");
                }
            }
            if (enabledCount == 0 && "0".equals(model.getStatus())) {
                reject(model, null, "无启用SKU");
            }
            JsonNode tiers = root.path("settleRule").path("imageOutputPixelTiers");
            if (!tiers.isMissingNode() && !tiers.isNull()) {
                if (!"PER_IMAGE".equals(fallbackMeterType) || !tiers.isArray() || tiers.isEmpty()
                        || minimumEnabledImagePrice == null) {
                    reject(model, null, "输出像素档位口径无效");
                }
                long previousLimit = 0;
                for (int index = 0; index < tiers.size(); index++) {
                    JsonNode tier = tiers.get(index);
                    JsonNode limit = tier.path("maxPixels");
                    JsonNode price = tier.path("price");
                    boolean last = index == tiers.size() - 1;
                    if (!tier.isObject() || !nonNegative(price)
                            || price.decimalValue().compareTo(minimumEnabledImagePrice) > 0
                            || (last ? !limit.isNull()
                            : !limit.isIntegralNumber() || !limit.canConvertToLong()
                            || limit.longValue() <= previousLimit)) {
                        reject(model, null, "输出像素档位需递增，末档无上限且价格不高于预冻结价");
                    }
                    if (!last) previousLimit = limit.longValue();
                }
            }
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            reject(model, null, "JSON解析失败: " + ex.getMessage());
        }
    }

    private static boolean hasValidMainPrice(JsonNode sku, String meterType, boolean explicitMeterType) {
        return switch (meterType) {
            case "TOKEN" -> nonNegative(sku.get("inputPricePerMillion")) && nonNegative(sku.get("outputPricePerMillion"));
            case "PER_IMAGE", "SKU_PACKAGE" -> nonNegative(sku.get("price"));
            case "PER_SECOND" -> nonNegative(sku.get("pricePerSecond"))
                    || (!explicitMeterType && positive(sku.get("price"))
                    && positive(sku.path("match").get("durationMax")));
            case "PER_CHAR" -> nonNegative(sku.get("pricePerChar"))
                    || (!explicitMeterType && positive(sku.get("price")));
            default -> false;
        };
    }

    private static String normalizeMeterType(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        return METER_TYPES.contains(normalized) ? normalized : null;
    }

    private static boolean positive(JsonNode node) {
        try {
            if (node == null) {
                return false;
            }
            BigDecimal value = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
            return value.compareTo(BigDecimal.ZERO) > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean nonNegative(JsonNode node) {
        try {
            if (node == null || node.isNull()) return false;
            BigDecimal value = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
            return value.signum() >= 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void reject(AidAiModel model, String skuCode, String reason) {
        log.warn("模型SKU计费规则拒绝: modelCode={}, skuCode={}, reason={}",
                model == null ? null : model.getModelCode(), skuCode, reason);
        throw new ServiceException(CLIENT_MESSAGE);
    }
}
