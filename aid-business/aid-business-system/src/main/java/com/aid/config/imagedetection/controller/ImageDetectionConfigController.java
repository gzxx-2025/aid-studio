package com.aid.config.imagedetection.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.image.detection.DetectImageObjectsInput;
import com.aid.common.image.detection.DetectImageObjectsResult;
import com.aid.common.image.detection.ImageDetectionConfig;
import com.aid.common.image.detection.ImageDetectionConfigManager;
import com.aid.common.image.detection.ImageDetectionException;
import com.aid.common.image.detection.ImageDetectionSourceProbe;
import com.aid.common.image.detection.ImageObjectDetectionService;
import com.aid.common.utils.SecurityUtils;
import com.aid.config.imagedetection.dto.ImageDetectionConfigSaveRequest;
import com.aid.config.imagedetection.dto.ImageDetectionTestRequest;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 后台视觉服务配置与真实联通测试。 */
@Slf4j
@RestController
@RequestMapping("/aidconfig/image-detection")
@RequiredArgsConstructor
public class ImageDetectionConfigController extends BaseController {
    private static final String CATEGORY = ImageDetectionConfigManager.CATEGORY;
    private final ImageDetectionConfigManager configManager;
    private final ConfigService configService;
    private final IAidConfigService aidConfigService;
    private final ImageObjectDetectionService detectionService;
    private final ImageDetectionSourceProbe sourceProbe;
    private final RegisteredDetectionImage registeredImage;

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/config")
    public AjaxResult getConfig() {
        Map<String, String> result = configManager.publicConfig();
        try {
            Map<String, String> oss = configService.getConfigValues("oss");
            result.put("uploadMode", oss.getOrDefault("uploadMode", "local"));
            if ("COS_STORAGE".equals(result.get("credentialSource"))) {
                result.put("region", oss.getOrDefault("cosRegion", ""));
                result.put("bucketName", oss.getOrDefault("cosBucketName", ""));
            }
        } catch (RuntimeException e) {
            result.put("uploadMode", "local");
        }
        return AjaxResult.success(result);
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "图像主体检测配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult saveConfig(@RequestBody ImageDetectionConfigSaveRequest request) {
        try {
            validate(request);
            save("enabled", request.getEnabled());
            save("provider", "tencent_ci");
            save("credentialSource", request.getCredentialSource());
            if ("DEDICATED".equals(request.getCredentialSource())) {
                save("region", request.getRegion());
                save("bucketName", request.getBucketName());
                saveSecret("secretId", request.getSecretId());
                saveSecret("secretKey", request.getSecretKey());
            }
            Map<String, String> oss = configService.getConfigValues("oss");
            if ("cos".equalsIgnoreCase(oss.get("uploadMode"))) {
                save("cosImageAccessMode", request.getCosImageAccessMode());
            }
            save("connectTimeoutMs", request.getConnectTimeoutMs());
            save("readTimeoutMs", request.getReadTimeoutMs());
            save("maxCallsPerUserMinute", request.getMaxCallsPerUserMinute());
            configManager.current();
            return AjaxResult.success("保存成功");
        } catch (Exception e) {
            log.error("保存图像主体检测配置失败: {}", e.getClass().getSimpleName());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return AjaxResult.error(e instanceof ImageDetectionException ? e.getMessage() : "配置保存失败");
        }
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @PostMapping("/test")
    public AjaxResult test(@RequestBody ImageDetectionTestRequest request) {
        long started = System.nanoTime();
        int calls = 0;
        try {
            if (request == null || !Set.of("COS_OBJECT", "VERIFIED_URL").contains(request.getSourceType())) {
                throw new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "测试图片未选择", 0);
            }
            ImageDetectionConfig config = configManager.current();
            if (!config.enabled()) throw new ImageDetectionException(ImageDetectionException.Code.DISABLED, "图像检测未启用", 0);
            DetectImageObjectsInput input;
            String traceId = UUID.randomUUID().toString();
            if ("COS_OBJECT".equals(request.getSourceType())) {
                Map<String, String> oss = configService.getConfigValues("oss");
                if (!"cos".equalsIgnoreCase(oss.get("uploadMode"))
                        || !"COS_OBJECT".equals(config.cosImageAccessMode()) || StrUtil.isNotBlank(request.getImageUrl())) {
                    throw new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "图片来源错误", 0);
                }
                if (!registeredImage.hasCosKey(request.getObjectKey())) {
                    throw new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "图片未登记", 0);
                }
                input = sourceProbe.cosObject(request.getObjectKey(), config, traceId);
            } else {
                if (StrUtil.isBlank(request.getImageUrl()) || StrUtil.isNotBlank(request.getObjectKey())) {
                    throw new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "图片地址不可用", 0);
                }
                if (!registeredImage.hasUrl(request.getImageUrl())) {
                    throw new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "图片未登记", 0);
                }
                input = sourceProbe.verifiedUrl(request.getImageUrl(), traceId);
            }
            DetectImageObjectsResult result = detectionService.detect(input, "admin:" + SecurityUtils.getUserId());
            calls = 1;
            return AjaxResult.success(testData("SUCCESS", calls, result.objects().size(), elapsed(started),
                    null, result.providerRequestId(), result));
        } catch (ImageDetectionException e) {
            calls = e.getProviderCalls();
            return AjaxResult.success(testData(e.getCode().name(), calls, 0, elapsed(started), e.getMessage(), e.getProviderRequestId(), null));
        } catch (Exception e) {
            log.error("图像主体检测测试失败: {}", e.getClass().getSimpleName());
            return AjaxResult.success(testData("ERROR", calls, 0, elapsed(started), "测试失败", null, null));
        }
    }

    private Map<String, Object> testData(String status, int calls, int count, long elapsedMs,
                                         String error, String requestId, DetectImageObjectsResult result) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        data.put("providerCalls", calls);
        data.put("objectCount", count);
        data.put("elapsedMs", elapsedMs);
        data.put("error", error);
        data.put("providerRequestId", requestId);
        data.put("result", result);
        return data;
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private void validate(ImageDetectionConfigSaveRequest request) {
        if (request == null || !Set.of("COS_STORAGE", "DEDICATED").contains(request.getCredentialSource())
                || request.getEnabled() == null || request.getConnectTimeoutMs() == null
                || request.getReadTimeoutMs() == null || request.getMaxCallsPerUserMinute() == null
                || request.getConnectTimeoutMs() < 500 || request.getConnectTimeoutMs() > 30000
                || request.getReadTimeoutMs() < 1000 || request.getReadTimeoutMs() > 120000
                || request.getMaxCallsPerUserMinute() < 1 || request.getMaxCallsPerUserMinute() > 120) {
            throw new IllegalArgumentException("配置参数错误");
        }
        if ("DEDICATED".equals(request.getCredentialSource())
                && (StrUtil.isBlank(request.getRegion()) || !request.getRegion().matches("[a-z0-9-]{3,64}")
                || StrUtil.isBlank(request.getBucketName()) || !request.getBucketName().matches("[a-zA-Z0-9-]+-[0-9]+"))) {
            throw new IllegalArgumentException("地域或桶错误");
        }
        if (request.getCosImageAccessMode() != null && !Set.of("COS_OBJECT", "PUBLIC_URL").contains(request.getCosImageAccessMode())) {
            throw new IllegalArgumentException("取图方式错误");
        }
    }

    private void save(String key, Object value) {
        if (Objects.nonNull(value)) aidConfigService.upsertConfigValue(CATEGORY, key, String.valueOf(value).trim());
    }

    private void saveSecret(String key, String value) {
        if (StrUtil.isNotBlank(value) && !value.contains("****")) save(key, value);
    }
}
