package com.aid.config.storage.controller;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.aid.service.IAidConfigService;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.config.storage.dto.StorageConfigSaveRequest;
import com.aid.config.test.tester.OssConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.aid.compose.ComposeConstants;
import com.aid.media.enums.MediaTaskStatus;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 文件存储整组保存与媒体处理归属校验。 */
@Slf4j
@RestController
@RequestMapping("/aidconfig/storage")
@RequiredArgsConstructor
public class StorageConfigController extends BaseController
{
    private static final String CATEGORY = "oss";
    private static final String MASK_FLAG = "****";
    private static final Set<String> MODES = Set.of("local", "oss", "cos", "qiniu");

    private final ConfigService configService;
    private final IAidConfigService aidConfigService;
    private final OssConfigManager ossConfigManager;
    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final OssConnectivityTester ossConnectivityTester;

    /** 读取文件存储配置；所有访问密钥仅返回脱敏值。 */
    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @GetMapping("/config")
    public AjaxResult getConfig()
    {
        Map<String, String> values = new java.util.HashMap<>();
        Map<String, String> stored = configService.getConfigValues(CATEGORY);
        if (stored != null)
        {
            values.putAll(stored);
        }
        mask(values, "accessKeyId");
        mask(values, "accessKeySecret");
        mask(values, "cosSecretId");
        mask(values, "cosSecretKey");
        mask(values, "qiniuAccessKey");
        mask(values, "qiniuSecretKey");
        values.putIfAbsent("uploadMode", "local");
        values.putIfAbsent("modelSignedUrlExpireHours", "72");
        return AjaxResult.success(values);
    }

    @PreAuthorize("@ss.hasPermi('aidconfig:aidconfig:edit')")
    @Log(title = "文件存储配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config")
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult saveConfig(@RequestBody StorageConfigSaveRequest request)
    {
        if (request == null)
        {
            return AjaxResult.error("参数不能为空");
        }
        try
        {
            validate(request);
            saveValue("enabled", request.getEnabled());
            saveValue("uploadMode", request.getUploadMode());
            saveValue("endpoint", request.getEndpoint());
            saveSecret("accessKeyId", request.getAccessKeyId());
            saveSecret("accessKeySecret", request.getAccessKeySecret());
            saveValue("bucketName", request.getBucketName());
            saveValue("prefix", request.getPrefix());
            saveValue("cosRegion", request.getCosRegion());
            saveSecret("cosSecretId", request.getCosSecretId());
            saveSecret("cosSecretKey", request.getCosSecretKey());
            saveValue("cosBucketName", request.getCosBucketName());
            saveValue("cosPrefix", request.getCosPrefix());
            saveValue("cosCdnDomain", request.getCosCdnDomain());
            saveSecret("qiniuAccessKey", request.getQiniuAccessKey());
            saveSecret("qiniuSecretKey", request.getQiniuSecretKey());
            saveValue("qiniuBucketName", request.getQiniuBucketName());
            saveValue("qiniuPrefix", request.getQiniuPrefix());
            saveValue("resourceAccessDomain", normalizeDomain(request.getResourceAccessDomain()));
            saveValue("modelSignedUrlExpireHours", request.getModelSignedUrlExpireHours());
            saveValue("imageUrlWhitelist", request.getImageUrlWhitelist());
            saveValue("maxFileSize", request.getMaxFileSize());
            saveValue("allowedExtensions", request.getAllowedExtensions());
            saveValue("maxBatchCount", request.getMaxBatchCount());
            saveValue("uploadTypeLimits", request.getUploadTypeLimits());
            refreshAfterCommit();
            return AjaxResult.success("保存成功");
        }
        catch (IllegalArgumentException e)
        {
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return AjaxResult.error(e.getMessage());
        }
        catch (Exception e)
        {
            log.error("保存文件存储配置失败", e);
            org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return AjaxResult.error("保存失败");
        }
    }

    private void validate(StorageConfigSaveRequest request)
    {
        String mode = StrUtil.blankToDefault(request.getUploadMode(), "local").trim().toLowerCase();
        if (!MODES.contains(mode))
        {
            log.error("不支持的文件存储方式, mode={}", mode);
            throw new IllegalArgumentException("方式错误");
        }
        request.setUploadMode(mode);
        normalizeDomain(request.getResourceAccessDomain());
        if ("local".equals(mode))
        {
            ConfigTestRequest probe = new ConfigTestRequest();
            probe.setPayload(Map.of("uploadMode", mode,
                    "resourceAccessDomain", StrUtil.trimToEmpty(request.getResourceAccessDomain())));
            ConfigTestResult result = ossConnectivityTester.test(probe);
            if (!result.isSuccess())
            {
                throw new IllegalArgumentException(result.getMessage());
            }
        }
        Integer hours = request.getModelSignedUrlExpireHours();
        if (hours == null || hours < 1 || hours > 168)
        {
            log.error("模型临时链接有效期非法, hours={}", hours);
            throw new IllegalArgumentException("有效期错误");
        }
        if ("oss".equals(mode))
        {
            require(request.getEndpoint(), "OSS地址");
            request.setEndpoint(normalizeOssEndpoint(request.getEndpoint()));
            require(request.getBucketName(), "OSS桶");
            requireSecret(request.getAccessKeyId(), "accessKeyId");
            requireSecret(request.getAccessKeySecret(), "accessKeySecret");
        }
        else if ("cos".equals(mode))
        {
            require(request.getCosRegion(), "COS地域");
            String cosRegion = request.getCosRegion().trim().toLowerCase();
            if (!cosRegion.matches("[a-z0-9-]{3,64}"))
            {
                log.error("COS地域格式非法, region={}", request.getCosRegion());
                throw new IllegalArgumentException("地域格式错误");
            }
            request.setCosRegion(cosRegion);
            require(request.getCosBucketName(), "COS桶");
            requireSecret(request.getCosSecretId(), "cosSecretId");
            requireSecret(request.getCosSecretKey(), "cosSecretKey");
        }
        else if ("qiniu".equals(mode))
        {
            require(request.getQiniuBucketName(), "七牛空间");
            requireSecret(request.getQiniuAccessKey(), "qiniuAccessKey");
            requireSecret(request.getQiniuSecretKey(), "qiniuSecretKey");
        }
        if (storageOwnershipChanged(request, mode) && hasActiveComposeTask())
        {
            log.error("存在未完成合成任务时禁止切换存储归属, targetMode={}", mode);
            throw new IllegalArgumentException("有合成任务处理中");
        }
        validateMediaProcessOwnership(request, mode);
    }

    private boolean storageOwnershipChanged(StorageConfigSaveRequest request, String mode)
    {
        Map<String, String> current = configService.getConfigValues(CATEGORY);
        if (current == null)
        {
            current = Map.of();
        }
        String currentMode = StrUtil.blankToDefault(current.get("uploadMode"), "local").toLowerCase();
        if (!mode.equals(currentMode))
        {
            return true;
        }
        if ("cos".equals(mode))
        {
            return !StrUtil.equals(request.getCosBucketName(), current.get("cosBucketName"))
                    || !StrUtil.equalsIgnoreCase(request.getCosRegion(), current.get("cosRegion"));
        }
        if ("oss".equals(mode))
        {
            String currentEndpoint = current.get("endpoint");
            return !StrUtil.equals(request.getBucketName(), current.get("bucketName"))
                    || StrUtil.isBlank(currentEndpoint)
                    || !StrUtil.equalsIgnoreCase(normalizeOssEndpoint(request.getEndpoint()),
                    normalizeOssEndpoint(currentEndpoint));
        }
        if ("qiniu".equals(mode))
        {
            return !StrUtil.equals(request.getQiniuBucketName(), current.get("qiniuBucketName"));
        }
        return false;
    }

    private boolean hasActiveComposeTask()
    {
        Long count = aidMediaTaskMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AidMediaTask>()
                        .eq(AidMediaTask::getMediaType, ComposeConstants.MEDIA_TYPE_COMPOSE)
                        .in(AidMediaTask::getStatus,
                                MediaTaskStatus.QUEUED.name(), MediaTaskStatus.PENDING.name(),
                                MediaTaskStatus.WAIT_CALLBACK.name(), MediaTaskStatus.WAIT_POLL.name(),
                                MediaTaskStatus.PROCESSING.name()));
        return count != null && count > 0;
    }

    private void validateMediaProcessOwnership(StorageConfigSaveRequest request, String storageMode)
    {
        Map<String, String> media = configService.getConfigValues("mps");
        if (media == null || media.isEmpty())
        {
            return;
        }
        if (!Boolean.parseBoolean(media.getOrDefault("enabled", "false")))
        {
            return;
        }
        String processMode = media.getOrDefault("processMode", "tencent-mps");
        if ("tencent-mps".equals(processMode))
        {
            String mediaRegion = firstNotBlank(media.get("tencentRegion"), media.get("region"));
            if (!"cos".equals(storageMode) || !StrUtil.equalsIgnoreCase(mediaRegion, request.getCosRegion()))
            {
                log.error("文件存储与腾讯MPS配置不匹配, storageMode={}, cosRegion={}, mpsRegion={}",
                        storageMode, request.getCosRegion(), mediaRegion);
                throw new IllegalArgumentException("处理方式不匹配");
            }
        }
        else if ("aliyun-ims".equals(processMode))
        {
            String ossRegion = parseOssRegion(request.getEndpoint());
            String imsRegion = media.get("aliyunRegion");
            if (!"oss".equals(storageMode) || !StrUtil.equalsIgnoreCase(ossRegion, imsRegion))
            {
                log.error("文件存储与阿里IMS配置不匹配, storageMode={}, ossRegion={}, imsRegion={}",
                        storageMode, ossRegion, imsRegion);
                throw new IllegalArgumentException("处理方式不匹配");
            }
        }
    }

    private String normalizeDomain(String domain)
    {
        require(domain, "资源地址");
        try
        {
            URI uri = URI.create(domain.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || StrUtil.isBlank(uri.getHost())
                    || StrUtil.isNotBlank(uri.getUserInfo())
                    || (StrUtil.isNotBlank(uri.getPath()) && !"/".equals(uri.getPath()))
                    || StrUtil.isNotBlank(uri.getQuery()) || StrUtil.isNotBlank(uri.getFragment()))
            {
                throw new IllegalArgumentException("地址错误");
            }
            String value = domain.trim();
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
        catch (Exception e)
        {
            log.error("资源访问地址格式非法, domain={}", domain);
            throw new IllegalArgumentException("地址错误");
        }
    }

    private String parseOssRegion(String endpoint)
    {
        String value = StrUtil.blankToDefault(endpoint, "").trim().toLowerCase().replaceFirst("^https?://", "");
        int dot = value.indexOf('.');
        String host = dot >= 0 ? value.substring(0, dot) : value;
        host = host.replace("-internal", "").replace("-intranet", "");
        return host.startsWith("oss-") ? host.substring(4) : "";
    }

    private String normalizeOssEndpoint(String endpoint)
    {
        String value = StrUtil.blankToDefault(endpoint, "").trim();
        try
        {
            URI uri = URI.create(value.matches("(?i)^https?://.*") ? value : "https://" + value);
            String host = uri.getHost();
            if (StrUtil.isBlank(host)
                    || !host.toLowerCase().matches("^oss-[a-z0-9-]+(?:-internal|-intranet)?\\.aliyuncs\\.com$")
                    || StrUtil.isNotBlank(uri.getUserInfo()) || uri.getPort() != -1
                    || (StrUtil.isNotBlank(uri.getPath()) && !"/".equals(uri.getPath()))
                    || StrUtil.isNotBlank(uri.getQuery()) || StrUtil.isNotBlank(uri.getFragment()))
            {
                throw new IllegalArgumentException("地址错误");
            }
            return host.toLowerCase();
        }
        catch (Exception e)
        {
            log.error("OSS Endpoint格式非法, endpoint={}", endpoint);
            throw new IllegalArgumentException("地址错误");
        }
    }

    private void require(String value, String label)
    {
        if (StrUtil.isBlank(value))
        {
            log.error("文件存储必填项为空, label={}", label);
            throw new IllegalArgumentException("配置未填写");
        }
    }

    private void requireSecret(String submitted, String key)
    {
        if ((StrUtil.isBlank(submitted) || submitted.contains(MASK_FLAG))
                && StrUtil.isBlank(configService.getConfigValue(CATEGORY, key)))
        {
            log.error("文件存储密钥未配置, key={}", key);
            throw new IllegalArgumentException("密钥未配置");
        }
    }

    private String firstNotBlank(String first, String second)
    {
        return StrUtil.isNotBlank(first) ? first : second;
    }

    private void saveSecret(String key, String value)
    {
        if (StrUtil.isBlank(value) || value.contains(MASK_FLAG))
        {
            return;
        }
        aidConfigService.upsertConfigValue(CATEGORY, key, value.trim());
    }

    private void mask(Map<String, String> values, String key)
    {
        String value = values.get(key);
        if (StrUtil.isBlank(value))
        {
            return;
        }
        values.put(key, value.length() > 8
                ? value.substring(0, 4) + MASK_FLAG + value.substring(value.length() - 4)
                : MASK_FLAG);
    }

    private void saveValue(String key, Object value)
    {
        if (Objects.nonNull(value))
        {
            String text = value instanceof String ? ((String) value).trim() : String.valueOf(value);
            aidConfigService.upsertConfigValue(CATEGORY, key, text);
        }
    }

    /** 配置缓存只能在数据库事务提交成功后刷新。 */
    private void refreshAfterCommit()
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
        {
            ossConfigManager.refresh();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                ossConfigManager.refresh();
            }
        });
    }
}
