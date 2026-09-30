package com.aid.billing.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.dto.BillingQuoteRequest;
import com.aid.billing.dto.ImagePricingQuotePayload;
import com.aid.billing.service.BillingPreHoldCalculationService;
import com.aid.billing.service.BillingQuoteService;
import com.aid.billing.service.BillingQuoteAssembler;
import com.aid.billing.service.BusinessBillingQuoteAdapter;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.PreparedMediaBillingInput;
import com.aid.media.service.MediaBillingQuotePreparer;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.util.ModelCapabilityValidator;
import com.aid.media.util.ModelInputCapabilityValidator;
import com.aid.billing.util.BillingInputExtractor;
import com.aid.project.service.IUserProjectBusinessService;
import com.aid.service.IAiModelConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

/** 直接媒体报价实现；仅做读取、前置校验与纯计费计算。 */
@Service
@RequiredArgsConstructor
public class BillingQuoteServiceImpl implements BillingQuoteService
{
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final String TYPE_IMAGE = "MEDIA_IMAGE";
    private static final String TYPE_IMAGE_PRICING = "MEDIA_IMAGE_PRICING";
    private static final String TYPE_VIDEO = "MEDIA_VIDEO";
    private static final String TYPE_TEXT = "MEDIA_TEXT";
    private static final String TYPE_AUDIO = "MEDIA_AUDIO";

    private final ObjectMapper objectMapper;
    private final MediaBillingQuotePreparer mediaBillingQuotePreparer;
    private final BillingPreHoldCalculationService billingPreHoldCalculationService;
    private final BillingQuoteAssembler billingQuoteAssembler;
    private final IAiModelConfigService aiModelConfigService;
    private final IUserProjectBusinessService userProjectBusinessService;
    private final List<BusinessBillingQuoteAdapter> businessQuoteAdapters;
    private final Validator validator;

    @Override
    public BillingQuoteVO quote(BillingQuoteRequest request, Long userId)
    {
        if (request == null || userId == null)
        {
            throw new ServiceException("请先登录");
        }
        JsonNode payload = request.getPayload();
        if (payload == null || payload.isNull() || !payload.isObject())
        {
            throw new ServiceException("报价参数无效");
        }
        if (payload.toString().getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES)
        {
            throw new ServiceException("报价参数过大");
        }
        String quoteType = Objects.toString(request.getQuoteType(), "")
                .trim().toUpperCase(Locale.ROOT);
        if (TYPE_IMAGE_PRICING.equals(quoteType))
        {
            if (request.getQuantity() != null && request.getQuantity() != 1)
            {
                throw new ServiceException("计价数量无效");
            }
            return quoteImagePricing(payload, userId);
        }
        if (!quoteType.startsWith("MEDIA_"))
        {
            if (request.getQuantity() != null && request.getQuantity() != 1)
            {
                throw new ServiceException("业务报价数量无效");
            }
            return businessQuoteAdapters.stream()
                    .filter(adapter -> adapter.supports(quoteType))
                    .findFirst()
                    .orElseThrow(() -> new ServiceException("报价类型不支持"))
                    .quote(quoteType, payload, userId);
        }
        rejectBatchPayload(payload);
        PreparedMediaBillingInput prepared = switch (quoteType)
        {
            case TYPE_IMAGE -> prepareImage(payload, userId);
            case TYPE_VIDEO -> prepareVideo(payload, userId);
            case TYPE_TEXT -> prepareText(payload, userId);
            case TYPE_AUDIO -> prepareAudio(payload, userId);
            default -> throw new ServiceException("报价类型不支持");
        };
        BillingCalcResult result = billingPreHoldCalculationService.calculate(
                prepared.modelConfig(), prepared.billingInput());
        if (result == null || !result.isMatched())
        {
            throw new ServiceException(result == null ? "计费规则缺失" : result.getErrorMessage());
        }
        int quantity = request.getQuantity() == null ? 1 : request.getQuantity();
        return billingQuoteAssembler.single(quoteType, prepared.modelConfig(), result, quantity);
    }

    private PreparedMediaBillingInput prepareImage(JsonNode payload, Long userId)
    {
        MediaImageGenerateRequest media = convertAndValidate(payload, MediaImageGenerateRequest.class);
        media.setUserId(userId);
        assertProjectOwnership(media.getProjectId(), userId);
        return mediaBillingQuotePreparer.prepareImageBilling(media);
    }

    private BillingQuoteVO quoteImagePricing(JsonNode payload, Long userId)
    {
        ImagePricingQuotePayload pricing = convertAndValidate(payload, ImagePricingQuotePayload.class);
        String mode = pricing.getGenerateMode().trim().toUpperCase(Locale.ROOT);
        String capabilityCode = "TEXT_TO_IMAGE".equals(mode) ? "text_to_image" : "image_to_image";
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCodeForUser(
                pricing.getModelCode(), userId, capabilityCode);
        if (modelConfig == null || !"image".equalsIgnoreCase(modelConfig.getModelType()))
        {
            throw new ServiceException("图片模型不可用");
        }
        MediaImageGenerateRequest media = buildPricingImageRequest(pricing, modelConfig);
        ModelInputCapabilityValidator.validateRawImageInputs(modelConfig, media);
        ModelInputCapabilityValidator.normalizeAndValidateImage(modelConfig, media);
        ModelCapabilityValidator.normalizeImageAspectRatio(modelConfig, media);
        ModelCapabilityValidator.validateImage(modelConfig, media);
        BillingInput input = BillingInputExtractor.fromImageRequest(
                media, modelConfig.getModelCode(), modelConfig.getMaxOutputCount(), modelConfig);
        BillingCalcResult result = billingPreHoldCalculationService.calculate(modelConfig, input);
        if (result == null || !result.isMatched())
        {
            throw new ServiceException(result == null ? "计费规则缺失" : result.getErrorMessage());
        }
        String meterType = result.getSnapshot() == null
                ? null : result.getSnapshot().getMeterType();
        if ("TOKEN".equalsIgnoreCase(meterType)
                && (result.getAmount() == null || result.getAmount().compareTo(BigDecimal.ZERO) <= 0))
        {
            throw new ServiceException("暂无法预估");
        }
        return billingQuoteAssembler.single(TYPE_IMAGE_PRICING, modelConfig, result, 1);
    }

    private MediaImageGenerateRequest buildPricingImageRequest(ImagePricingQuotePayload pricing,
                                                                AiModelConfigVo modelConfig)
    {
        Integer requestedCount = pricing.getExpectedImageCount();
        if (requestedCount == null)
        {
            requestedCount = pricing.getImageCount();
        }
        if (pricing.getExpectedImageCount() != null && pricing.getImageCount() != null
                && !Objects.equals(pricing.getExpectedImageCount(), pricing.getImageCount()))
        {
            throw new ServiceException("生成数量不一致");
        }
        int outputCount = requestedCount == null ? 1 : requestedCount;
        String mode = pricing.getGenerateMode().trim().toUpperCase(Locale.ROOT);
        boolean imageEdit = "IMAGE_EDIT".equals(mode) || "IMAGE_TO_IMAGE".equals(mode);
        int referenceCount = pricing.getReferenceImageCount() == null
                ? 0 : pricing.getReferenceImageCount();
        if (!imageEdit && referenceCount > 0)
        {
            throw new ServiceException("生成模式不匹配");
        }
        if (imageEdit)
        {
            int minimum = Math.max(1,
                    ReferenceImageLimiter.readMinFromCapabilityJson(modelConfig.getCapabilityJson()));
            referenceCount = Math.max(referenceCount, minimum);
        }
        Integer maxReferenceImages = ReferenceImageLimiter.readMaxFromCapability(modelConfig);
        if (referenceCount > 0 && (Boolean.FALSE.equals(modelConfig.getSupportsImageInput())
                || Integer.valueOf(0).equals(maxReferenceImages)))
        {
            throw new ServiceException("模型不支持图片");
        }
        if (maxReferenceImages != null && maxReferenceImages > 0
                && referenceCount > maxReferenceImages)
        {
            throw new ServiceException("参考图数量超限");
        }

        Map<String, Object> options = new LinkedHashMap<>();
        if (pricing.getResolution() != null && !pricing.getResolution().isBlank())
        {
            options.put("resolution", pricing.getResolution().trim());
        }
        if (pricing.getAspectRatio() != null && !pricing.getAspectRatio().isBlank())
        {
            options.put("aspectRatio", pricing.getAspectRatio().trim());
        }
        options.put("n", outputCount);
        if (referenceCount > 0)
        {
            List<String> references = new ArrayList<>(referenceCount);
            for (int index = 0; index < referenceCount; index++)
            {
                references.add("quote-reference-" + index);
            }
            options.put("referenceImages", references);
        }
        MediaImageGenerateRequest media = new MediaImageGenerateRequest();
        media.setModelName(modelConfig.getModelCode());
        media.setPrompt(pricing.getPrompt() == null || pricing.getPrompt().isBlank()
                ? "图片报价" : pricing.getPrompt().trim());
        String effectiveSize = pricing.getSize();
        if ((effectiveSize == null || effectiveSize.isBlank())
                && pricing.getResolution() != null && !pricing.getResolution().isBlank())
        {
            effectiveSize = pricing.getResolution().trim();
        }
        media.setSize(effectiveSize);
        media.setExpectedImageCount(outputCount);
        media.setOptions(options);
        return media;
    }

    private PreparedMediaBillingInput prepareVideo(JsonNode payload, Long userId)
    {
        MediaVideoGenerateRequest media = convertAndValidate(payload, MediaVideoGenerateRequest.class);
        media.setUserId(userId);
        media.setParentTaskId(null);
        assertProjectOwnership(media.getProjectId(), userId);
        return mediaBillingQuotePreparer.prepareVideoBilling(media);
    }

    private PreparedMediaBillingInput prepareText(JsonNode payload, Long userId)
    {
        MediaTextGenerateRequest media = convertAndValidate(payload, MediaTextGenerateRequest.class);
        media.setUserId(userId);
        media.setBillingExempt(Boolean.FALSE);
        media.setCallId(null);
        media.setBillingAttemptId(null);
        media.setCallIdentity(null);
        assertProjectOwnership(media.getProjectId(), userId);
        return mediaBillingQuotePreparer.prepareTextBilling(media);
    }

    private PreparedMediaBillingInput prepareAudio(JsonNode payload, Long userId)
    {
        MediaAudioGenerateRequest media = convertAndValidate(payload, MediaAudioGenerateRequest.class);
        media.setUserId(userId);
        media.setParentTaskId(null);
        media.setPreviewMode(false);
        assertProjectOwnership(media.getProjectId(), userId);
        return mediaBillingQuotePreparer.prepareAudioBilling(media);
    }

    private <T> T convertAndValidate(JsonNode payload, Class<T> type)
    {
        try
        {
            T value = objectMapper.treeToValue(payload, type);
            ConstraintViolation<T> violation = validator.validate(value).stream()
                    .sorted(java.util.Comparator.comparing(item -> item.getPropertyPath().toString()))
                    .findFirst().orElse(null);
            if (violation != null)
            {
                throw new ServiceException(violation.getMessage());
            }
            return value;
        }
        catch (JsonProcessingException e)
        {
            throw new ServiceException("报价参数无效");
        }
    }

    /** quantity 仅表示同一单请求的重复调用次数；批量数组由业务报价适配器处理。 */
    private void rejectBatchPayload(JsonNode payload)
    {
        if (payload.has("items") || payload.has("requests") || payload.has("tasks")
                || payload.has("batchItems"))
        {
            throw new ServiceException("请使用批量报价");
        }
    }

    private void assertProjectOwnership(Long projectId, Long userId)
    {
        if (projectId != null && userProjectBusinessService.selectUserProjectById(projectId, userId) == null)
        {
            throw new ServiceException("项目不存在");
        }
    }

}
