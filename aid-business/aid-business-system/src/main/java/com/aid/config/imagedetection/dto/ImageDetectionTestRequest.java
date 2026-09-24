package com.aid.config.imagedetection.dto;

import lombok.Data;

@Data
public class ImageDetectionTestRequest {
    /** COS_OBJECT 或 VERIFIED_URL。 */
    private String sourceType;
    private String objectKey;
    private String imageUrl;
}
