package com.aid.common.image.detection;

public record ImageDetectionConfig(boolean enabled, String provider, String region, String bucketName,
                                   String secretId, String secretKey, String credentialSource,
                                   String cosImageAccessMode, int connectTimeoutMs, int readTimeoutMs,
                                   int maxCallsPerUserMinute) {}
