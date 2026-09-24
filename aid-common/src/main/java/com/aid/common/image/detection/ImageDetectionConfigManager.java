package com.aid.common.image.detection;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aid.common.aid.core.service.ConfigService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/** 每次读取生效配置；COS_STORAGE 不依赖当前上传模式或启动时的 COSClient。 */
@Component
@RequiredArgsConstructor
public class ImageDetectionConfigManager {
    public static final String CATEGORY = "image_object_detection";
    private final ConfigService configService;

    public Map<String, String> publicConfig() {
        Map<String, String> values = new HashMap<>(category(CATEGORY));
        values.putIfAbsent("enabled", "false");
        values.putIfAbsent("provider", "tencent_ci");
        values.putIfAbsent("credentialSource", "COS_STORAGE");
        values.putIfAbsent("cosImageAccessMode", "COS_OBJECT");
        values.putIfAbsent("connectTimeoutMs", "3000");
        values.putIfAbsent("readTimeoutMs", "15000");
        values.putIfAbsent("maxCallsPerUserMinute", "10");
        String key = values.get("secretKey");
        values.put("secretKey", StrUtil.isBlank(key) ? "" : "****");
        String id = values.get("secretId");
        values.put("secretId", StrUtil.isBlank(id) ? "" : "****");
        return values;
    }

    public ImageDetectionConfig current() {
        Map<String, String> values = category(CATEGORY);
        boolean enabled = Boolean.parseBoolean(values.getOrDefault("enabled", "false"));
        String credentialSource = values.getOrDefault("credentialSource", "COS_STORAGE");
        Map<String, String> credentials = "COS_STORAGE".equals(credentialSource)
                ? (enabled ? category("oss") : optionalOss()) : values;
        String region = "COS_STORAGE".equals(credentialSource) ? credentials.get("cosRegion") : values.get("region");
        String bucket = "COS_STORAGE".equals(credentialSource) ? credentials.get("cosBucketName") : values.get("bucketName");
        String secretId = "COS_STORAGE".equals(credentialSource) ? credentials.get("cosSecretId") : values.get("secretId");
        String secretKey = "COS_STORAGE".equals(credentialSource) ? credentials.get("cosSecretKey") : values.get("secretKey");
        ImageDetectionConfig config = new ImageDetectionConfig(enabled,
                values.getOrDefault("provider", "tencent_ci"), region, bucket, secretId, secretKey,
                credentialSource, values.getOrDefault("cosImageAccessMode", "COS_OBJECT"),
                number(values, "connectTimeoutMs", 3000), number(values, "readTimeoutMs", 15000),
                number(values, "maxCallsPerUserMinute", 10));
        if (enabled && (!"tencent_ci".equals(config.provider())
                || !("COS_STORAGE".equals(credentialSource) || "DEDICATED".equals(credentialSource))
                || !("COS_OBJECT".equals(config.cosImageAccessMode()) || "PUBLIC_URL".equals(config.cosImageAccessMode()))
                || StrUtil.isBlank(region) || StrUtil.isBlank(bucket) || StrUtil.isBlank(secretId) || StrUtil.isBlank(secretKey)
                || !region.matches("[a-z0-9-]{3,64}") || !bucket.matches("[a-zA-Z0-9-]+-[0-9]+")
                || config.connectTimeoutMs() < 500 || config.connectTimeoutMs() > 30000
                || config.readTimeoutMs() < 1000 || config.readTimeoutMs() > 120000
                || config.maxCallsPerUserMinute() < 1 || config.maxCallsPerUserMinute() > 120)) {
            throw new ImageDetectionException(ImageDetectionException.Code.UNCONFIGURED, "图像检测未配置", 0);
        }
        return config;
    }

    private int number(Map<String, String> values, String key, int fallback) {
        try { return Integer.parseInt(values.getOrDefault(key, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return -1; }
    }

    private Map<String, String> category(String name) {
        try {
            Map<String, String> values = configService.getConfigValues(name);
            return values == null ? Map.of() : values;
        } catch (RuntimeException e) {
            if (CATEGORY.equals(name)) return Map.of();
            throw new ImageDetectionException(ImageDetectionException.Code.UNCONFIGURED, "图像检测未配置", 0);
        }
    }

    private Map<String, String> optionalOss() {
        try { return configService.getConfigValues("oss"); }
        catch (RuntimeException e) { return Map.of(); }
    }
}
