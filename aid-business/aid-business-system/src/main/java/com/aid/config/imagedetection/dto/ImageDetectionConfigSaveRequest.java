package com.aid.config.imagedetection.dto;

import lombok.Data;

@Data
public class ImageDetectionConfigSaveRequest {
    private Boolean enabled;
    private String credentialSource;
    private String region;
    private String bucketName;
    private String secretId;
    private String secretKey;
    private String cosImageAccessMode;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private Integer maxCallsPerUserMinute;
}
