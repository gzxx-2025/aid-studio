package com.aid.billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 图片模型选型阶段的只读计价参数。 */
@Data
public class ImagePricingQuotePayload
{
    @NotBlank(message = "模型不能为空")
    private String modelCode;

    @NotBlank(message = "生成模式不能为空")
    @Pattern(regexp = "(?i)TEXT_TO_IMAGE|IMAGE_TO_IMAGE|IMAGE_EDIT",
            message = "生成模式无效")
    private String generateMode;

    /** Optional draft text; legacy model-selection quotes use a neutral placeholder. */
    private String prompt;

    private String size;

    private String resolution;

    private String aspectRatio;

    @Min(value = 1, message = "生成数量无效")
    @Max(value = 16, message = "生成数量过大")
    private Integer imageCount;

    @Min(value = 1, message = "生成数量无效")
    @Max(value = 16, message = "生成数量过大")
    private Integer expectedImageCount;

    @Min(value = 0, message = "参考图数量无效")
    @Max(value = 100, message = "参考图数量过大")
    private Integer referenceImageCount;
}
