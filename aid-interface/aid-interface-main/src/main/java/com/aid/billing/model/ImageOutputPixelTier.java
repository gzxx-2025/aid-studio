package com.aid.billing.model;

import lombok.Data;

import java.math.BigDecimal;

/** Inclusive upper pixel boundary and official price for one output image. */
@Data
public class ImageOutputPixelTier {
    /** Null means the final unbounded tier. */
    private Long maxPixels;
    private BigDecimal price;
}
