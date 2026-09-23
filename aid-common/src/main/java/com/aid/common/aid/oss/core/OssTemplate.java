package com.aid.common.aid.oss.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.qcloud.cos.COSClient;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.exception.OssException;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.vo.OssUploadLimitsVO;
import com.aid.common.config.AidAppConfig;
import com.aid.common.constant.Constants;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.uuid.IdUtils;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;

/**
 * OSS核心操作类。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssTemplate
{
    /**
     * 默认签名过期时间（秒）：1小时
     */
    private static final int DEFAULT_EXPIRE_SECONDS = 3600;

    /**
     * 默认文件名最大长度
     */
    private static final int DEFAULT_FILE_NAME_LENGTH = 100;

    /**
     * 本地上传的子目录
     */
    private static final String LOCAL_UPLOAD_DIR = "upload";

    /**
     * OSS客户端（可选注入，配置不完整时为null）
     */
    @Autowired(required = false)
    private OSS ossClient;

    /**
     * 腾讯云COS客户端（可选注入，配置不完整时为null）
     */
    @Autowired(required = false)
    private COSClient cosClient;

    /**
     * OSS配置管理器
     */
    private final OssConfigManager ossConfigManager;

    /**
     * 七牛云上传客户端，区域由 SDK 根据 AccessKey 与 Bucket 自动识别。
     */
    private final Configuration qiniuConfiguration = Configuration.create();

    /**
     * 七牛云上传管理器，可安全复用其连接池。
     */
    private final UploadManager qiniuUploadManager = new UploadManager(qiniuConfiguration);
    /**
     * 上传文件到本地
     *
     * @param file 上传的文件
     * @param customDir 自定义目录（可为空）
     * @return 文件访问URL
     */
    public String uploadToLocal(MultipartFile file, String customDir)
    {
        return uploadToLocal(file, customDir, true);
    }

    private String uploadToLocal(MultipartFile file, String customDir, boolean validateUpload)
    {
        // 校验文件
        if (validateUpload)
        {
            validateFile(file);
        }

        // customDir 白名单 + 规范化 + 前缀断言
        String safeCustomDir = sanitizeCustomDir(customDir);

        try
        {
            // 获取文件扩展名并生成新文件名
            String extension = FilenameUtils.getExtension(file.getOriginalFilename());
            String fileName = generateFileName(extension);

            // 构建本地存储路径
            Path baseDir = Paths.get(AidAppConfig.getProfile(), LOCAL_UPLOAD_DIR).toAbsolutePath().normalize();
            Path dir = baseDir;
            if (StrUtil.isNotBlank(safeCustomDir))
            {
                dir = dir.resolve(safeCustomDir);
            }
            // 添加日期子目录
            dir = dir.resolve(DateUtils.datePath()).normalize();
            // 防路径穿越：最终目录必须在 baseDir 之内
            if (!dir.startsWith(baseDir))
            {
                log.error("本地上传路径非法，疑似路径穿越: customDir={}, resolved={}", customDir, dir);
                throw new OssException("路径非法");
            }
            Files.createDirectories(dir);

            // 写入文件
            Path filePath = dir.resolve(fileName).normalize();
            if (!filePath.startsWith(baseDir))
            {
                log.error("本地上传文件路径非法: filePath={}", filePath);
                throw new OssException("路径非法");
            }
            file.transferTo(filePath.toFile());

            // 构建相对访问路径（不拼域名，由读取层统一拼接 localDomain）
            String relativePath = Constants.RESOURCE_PREFIX + "/" + LOCAL_UPLOAD_DIR + "/";
            if (StrUtil.isNotBlank(safeCustomDir))
            {
                relativePath += safeCustomDir + "/";
            }
            relativePath += DateUtils.datePath() + "/" + fileName;

            return relativePath;
        }
        catch (OssException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            log.error("本地文件写入失败：{}", e.getMessage(), e);
            throw new OssException("文件写入失败");
        }
    }

    /**
     * 对 customDir 做严格白名单（字母/数字/下划线/短横/斜杠），并拒绝 `..`。
     * 为空返回 null；非法抛 OssException。
     */
    private String sanitizeCustomDir(String customDir)
    {
        if (StrUtil.isBlank(customDir))
        {
            return null;
        }
        String trimmed = customDir.trim();
        // 禁止 . . 与反斜杠与盘符
        if (trimmed.contains("..") || trimmed.contains("\\"))
        {
            throw new OssException("目录非法");
        }
        // 首尾的 / 移除，便于拼接
        while (trimmed.startsWith("/"))
        {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty())
        {
            return null;
        }
        // 允许：字母/数字/下划线/短横/点 + 斜杠作为分隔
        if (!trimmed.matches("[A-Za-z0-9_\\-./]+"))
        {
            throw new OssException("目录非法");
        }
        return trimmed;
    }

    /**
     * 上传字节数组到本地
     *
     * @param bytes 字节数组
     * @param fileName 文件名
     * @return 文件访问URL
     */
    public String uploadBytesToLocal(byte[] bytes, String fileName)
    {
        if (Objects.isNull(bytes) || bytes.length == 0)
        {
            log.error("本地上传字节数组失败：字节数组为空");
            throw new OssException("上传失败");
        }

        try
        {
            // 构建本地存储路径
            Path dir = Paths.get(AidAppConfig.getProfile(), LOCAL_UPLOAD_DIR, DateUtils.datePath());
            Files.createDirectories(dir);

            // 写入文件
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, bytes);

            // 构建相对访问路径（不拼域名）
            return Constants.RESOURCE_PREFIX + "/" + LOCAL_UPLOAD_DIR + "/" + DateUtils.datePath() + "/" + fileName;
        }
        catch (IOException e)
        {
            log.error("本地字节数组写入失败：{}", e.getMessage(), e);
            throw new OssException("文件写入失败");
        }
    }

    /**
     * 标准化本地文件URL：若 URL 以配置的 localDomain 开头，则裁掉域名，
     * 得到形如 `/profile/upload/...` 的相对路径；否则原样返回。
     * 用于 {@link #deleteFromLocal(String)} 等需要从 URL 推回本地物理路径的场景。
     *
     * @param fileUrl 可能包含 localDomain 前缀的完整URL或相对路径
     * @return 以 `/profile` 开头的相对路径
     */
    private String normalizeLocalFileUrl(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return fileUrl;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        String localDomain = Objects.isNull(properties) ? null : properties.getLocalDomain();
        if (StrUtil.isBlank(localDomain))
        {
            return fileUrl;
        }
        // 去除 localDomain 末尾可能的 `/` 后做前缀匹配
        String domain = localDomain.endsWith("/") ? localDomain.substring(0, localDomain.length() - 1) : localDomain;
        // 必须以 domain 开头，且紧跟的下一个字符是 '/'（路径边界），避免 https://a.com 误匹配 https://a.com.evil.com
        if (fileUrl.length() > domain.length()
                && fileUrl.startsWith(domain)
                && fileUrl.charAt(domain.length()) == '/')
        {
            return fileUrl.substring(domain.length());
        }
        // 恰好等于 domain 本身（没有路径）不视为本地文件URL
        return fileUrl;
    }
    /**
     * 上传文件到阿里云OSS
     *
     * @param file 上传的文件
     * @param customDir 自定义目录（可为空）
     * @return 文件访问URL
     */
    public String uploadToOss(MultipartFile file, String customDir)
    {
        return uploadToOss(file, customDir, true);
    }

    private String uploadToOss(MultipartFile file, String customDir, boolean validateUpload)
    {
        // 校验文件
        if (validateUpload)
        {
            validateFile(file);
        }

        OssProperties properties = ossConfigManager.getOssProperties();
        long startMs = System.currentTimeMillis();
        String objectKey = null;

        try
        {
            // 校验OSS客户端
            if (Objects.isNull(ossClient))
            {
                log.error("[OSS-UPLOAD] 失败：OSS客户端未初始化（请检查配置是否已保存并点击【同步配置】）, originalFilename={}, size={}B",
                        file.getOriginalFilename(), file.getSize());
                throw new OssException("OSS未配置");
            }

            // 获取文件扩展名并生成新文件名
            String extension = FilenameUtils.getExtension(file.getOriginalFilename());
            String fileName = generateFileName(extension);
            objectKey = buildObjectKey(customDir, fileName, properties);

            // 上传前日志
            log.info("[OSS-UPLOAD] 开始上传(MultipartFile), endpoint={}, bucket={}, objectKey={}, size={}B, contentType={}",
                    properties.getEndpoint(), properties.getBucketName(), objectKey, file.getSize(), file.getContentType());

            // 获取输入流
            InputStream inputStream = file.getInputStream();
            // 创建元数据
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(file.getContentType());

            // 创建上传请求
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    properties.getBucketName(),
                    objectKey,
                    inputStream,
                    metadata
            );

            // 执行上传
            ossClient.putObject(putObjectRequest);

            // 返回文件URL
            String url = getFileUrl(objectKey, properties);
            log.info("[OSS-UPLOAD] 上传成功(MultipartFile), objectKey={}, url={}, elapsedMs={}",
                    objectKey, url, System.currentTimeMillis() - startMs);
            return url;
        }
        catch (IOException e)
        {
            log.error("[OSS-UPLOAD] 文件读取失败, originalFilename={}, error={}",
                    file.getOriginalFilename(), e.getMessage(), e);
            throw new OssException("文件读取失败");
        }
        catch (OssException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[OSS-UPLOAD] 上传异常(MultipartFile), endpoint={}, bucket={}, objectKey={}, size={}B, elapsedMs={}, error={}",
                    properties.getEndpoint(), properties.getBucketName(), objectKey, file.getSize(),
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            throw new OssException("上传失败");
        }
    }

    /**
     * 上传字节数组到阿里云OSS
     *
     * @param bytes 字节数组
     * @param fileName 文件名
     * @param customDir 自定义目录（可为空）
     * @return 文件访问URL
     */
    public String uploadBytesToOss(byte[] bytes, String fileName, String customDir)
    {
        if (Objects.isNull(bytes) || bytes.length == 0)
        {
            log.error("[OSS-UPLOAD] 失败：字节数组为空, fileName={}", fileName);
            throw new OssException("上传失败");
        }

        OssProperties properties = ossConfigManager.getOssProperties();

        // 校验OSS客户端
        if (Objects.isNull(ossClient))
        {
            log.error("[OSS-UPLOAD] 失败：OSS客户端未初始化（请检查配置是否已保存并点击【同步配置】）, fileName={}, size={}B",
                    fileName, bytes.length);
            throw new OssException("OSS未配置");
        }

        // 提取扩展名并生成新文件名
        String extension = FilenameUtils.getExtension(fileName);
        String newFileName = generateFileName(extension);
        String objectKey = buildObjectKey(customDir, newFileName, properties);

        // 上传前日志：输出目标 bucket / objectKey / endpoint / 字节大小，便于排查问题
        log.info("[OSS-UPLOAD] 开始上传, endpoint={}, bucket={}, objectKey={}, size={}B, contentType={}",
                properties.getEndpoint(), properties.getBucketName(), objectKey, bytes.length, getContentType(extension));

        long startMs = System.currentTimeMillis();
        try
        {
            // 创建输入流
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            // 创建元数据
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType(getContentType(extension));

            // 上传到OSS
            ossClient.putObject(properties.getBucketName(), objectKey, inputStream, metadata);

            // 返回文件URL
            String url = getFileUrl(objectKey, properties);
            log.info("[OSS-UPLOAD] 上传成功, objectKey={}, url={}, elapsedMs={}",
                    objectKey, url, System.currentTimeMillis() - startMs);
            return url;
        }
        catch (Exception e)
        {
            // 输出完整堆栈 + 关键参数，便于定位是 AK/SK 错、bucket 不存在、网络不通还是签名问题
            log.error("[OSS-UPLOAD] 上传异常, endpoint={}, bucket={}, objectKey={}, size={}B, elapsedMs={}, error={}",
                    properties.getEndpoint(), properties.getBucketName(), objectKey, bytes.length,
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            throw new OssException("上传失败");
        }
    }
    /**
     * 上传文件到腾讯云COS
     *
     * @param file 上传的文件
     * @param customDir 自定义目录（可为空）
     * @return 文件访问相对路径（带前导 /）
     */
    public String uploadToCos(MultipartFile file, String customDir)
    {
        return uploadToCos(file, customDir, true);
    }

    private String uploadToCos(MultipartFile file, String customDir, boolean validateUpload)
    {
        // 校验文件
        if (validateUpload)
        {
            validateFile(file);
        }

        OssProperties properties = ossConfigManager.getOssProperties();
        long startMs = System.currentTimeMillis();
        String objectKey = null;

        try
        {
            // 校验COS客户端
            if (Objects.isNull(cosClient))
            {
                log.error("[COS-UPLOAD] 失败：COS客户端未初始化（请检查配置是否已保存并点击【同步配置】）, originalFilename={}, size={}B",
                        file.getOriginalFilename(), file.getSize());
                throw new OssException("COS未配置");
            }

            // 获取文件扩展名并生成新文件名
            String extension = FilenameUtils.getExtension(file.getOriginalFilename());
            String fileName = generateFileName(extension);
            objectKey = buildObjectKey(customDir, fileName, properties.getCosPrefix());

            log.info("[COS-UPLOAD] 开始上传(MultipartFile), region={}, bucket={}, objectKey={}, size={}B, contentType={}",
                    properties.getCosRegion(), properties.getCosBucketName(), objectKey, file.getSize(), file.getContentType());

            // 创建元数据
            com.qcloud.cos.model.ObjectMetadata metadata = new com.qcloud.cos.model.ObjectMetadata();
            metadata.setContentLength(file.getSize());
            if (StrUtil.isNotBlank(file.getContentType()))
            {
                metadata.setContentType(file.getContentType());
            }

            // 创建上传请求并执行
            com.qcloud.cos.model.PutObjectRequest putObjectRequest = new com.qcloud.cos.model.PutObjectRequest(
                    properties.getCosBucketName(),
                    objectKey,
                    file.getInputStream(),
                    metadata);
            cosClient.putObject(putObjectRequest);

            String url = getFileUrl(objectKey, properties);
            log.info("[COS-UPLOAD] 上传成功(MultipartFile), objectKey={}, url={}, elapsedMs={}",
                    objectKey, url, System.currentTimeMillis() - startMs);
            return url;
        }
        catch (IOException e)
        {
            log.error("[COS-UPLOAD] 文件读取失败, originalFilename={}, error={}",
                    file.getOriginalFilename(), e.getMessage(), e);
            throw new OssException("文件读取失败");
        }
        catch (OssException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[COS-UPLOAD] 上传异常(MultipartFile), region={}, bucket={}, objectKey={}, size={}B, elapsedMs={}, error={}",
                    properties.getCosRegion(), properties.getCosBucketName(), objectKey, file.getSize(),
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            throw new OssException("上传失败");
        }
    }

    /**
     * 上传字节数组到腾讯云COS
     *
     * @param bytes 字节数组
     * @param fileName 文件名（用于取扩展名）
     * @param customDir 自定义目录（可为空）
     * @return 文件访问相对路径（带前导 /）
     */
    public String uploadBytesToCos(byte[] bytes, String fileName, String customDir)
    {
        if (Objects.isNull(bytes) || bytes.length == 0)
        {
            log.error("[COS-UPLOAD] 失败：字节数组为空, fileName={}", fileName);
            throw new OssException("上传失败");
        }

        OssProperties properties = ossConfigManager.getOssProperties();

        // 校验COS客户端
        if (Objects.isNull(cosClient))
        {
            log.error("[COS-UPLOAD] 失败：COS客户端未初始化（请检查配置是否已保存并点击【同步配置】）, fileName={}, size={}B",
                    fileName, bytes.length);
            throw new OssException("COS未配置");
        }

        // 提取扩展名并生成新文件名
        String extension = FilenameUtils.getExtension(fileName);
        String newFileName = generateFileName(extension);
        String objectKey = buildObjectKey(customDir, newFileName, properties.getCosPrefix());

        log.info("[COS-UPLOAD] 开始上传, region={}, bucket={}, objectKey={}, size={}B, contentType={}",
                properties.getCosRegion(), properties.getCosBucketName(), objectKey, bytes.length, getContentType(extension));

        long startMs = System.currentTimeMillis();
        try
        {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            com.qcloud.cos.model.ObjectMetadata metadata = new com.qcloud.cos.model.ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType(getContentType(extension));

            com.qcloud.cos.model.PutObjectRequest putObjectRequest = new com.qcloud.cos.model.PutObjectRequest(
                    properties.getCosBucketName(),
                    objectKey,
                    inputStream,
                    metadata);
            cosClient.putObject(putObjectRequest);

            String url = getFileUrl(objectKey, properties);
            log.info("[COS-UPLOAD] 上传成功, objectKey={}, url={}, elapsedMs={}",
                    objectKey, url, System.currentTimeMillis() - startMs);
            return url;
        }
        catch (Exception e)
        {
            log.error("[COS-UPLOAD] 上传异常, region={}, bucket={}, objectKey={}, size={}B, elapsedMs={}, error={}",
                    properties.getCosRegion(), properties.getCosBucketName(), objectKey, bytes.length,
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            throw new OssException("上传失败");
        }
    }

    /**
     * 上传文件到七牛云 Kodo。
     *
     * @param file 上传文件
     * @param customDir 自定义目录
     * @return 文件访问相对路径
     */
    public String uploadToQiniu(MultipartFile file, String customDir)
    {
        return uploadToQiniu(file, customDir, true);
    }

    private String uploadToQiniu(MultipartFile file, String customDir, boolean validateUpload)
    {
        if (validateUpload)
        {
            validateFile(file);
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        validateQiniuConfig(properties);

        String extension = FilenameUtils.getExtension(file.getOriginalFilename());
        String objectKey = buildObjectKey(customDir, generateFileName(extension), properties.getQiniuPrefix());
        String contentType = StrUtil.blankToDefault(file.getContentType(), getContentType(extension));
        Auth auth = Auth.create(properties.getQiniuAccessKey(), properties.getQiniuSecretKey());
        String uploadToken = auth.uploadToken(properties.getQiniuBucketName());
        long startMs = System.currentTimeMillis();

        log.info("[QINIU-UPLOAD] 开始上传, bucket={}, objectKey={}, size={}B, contentType={}",
                properties.getQiniuBucketName(), objectKey, file.getSize(), contentType);
        Response response = null;
        try (InputStream inputStream = file.getInputStream())
        {
            response = qiniuUploadManager.put(
                    inputStream, file.getSize(), objectKey, uploadToken, null, contentType, true);
            if (!response.isOK())
            {
                log.error("[QINIU-UPLOAD] 上传失败, bucket={}, objectKey={}, status={}",
                        properties.getQiniuBucketName(), objectKey, response.statusCode);
                throw new OssException("上传失败");
            }
            String url = getFileUrl(objectKey, properties);
            log.info("[QINIU-UPLOAD] 上传成功, objectKey={}, url={}, elapsedMs={}",
                    objectKey, url, System.currentTimeMillis() - startMs);
            return url;
        }
        catch (OssException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[QINIU-UPLOAD] 上传异常, bucket={}, objectKey={}, elapsedMs={}, error={}",
                    properties.getQiniuBucketName(), objectKey,
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            throw new OssException("上传失败");
        }
        finally
        {
            closeQiniuResponse(response);
        }
    }

    /**
     * 上传字节数组到七牛云 Kodo。
     *
     * @param bytes 文件内容
     * @param fileName 原文件名
     * @param customDir 自定义目录
     * @return 文件访问相对路径
     */
    public String uploadBytesToQiniu(byte[] bytes, String fileName, String customDir)
    {
        if (Objects.isNull(bytes) || bytes.length == 0)
        {
            log.error("[QINIU-UPLOAD] 失败：字节数组为空, fileName={}", fileName);
            throw new OssException("上传失败");
        }

        OssProperties properties = ossConfigManager.getOssProperties();
        validateQiniuConfig(properties);
        String extension = FilenameUtils.getExtension(fileName);
        String objectKey = buildObjectKey(customDir, generateFileName(extension), properties.getQiniuPrefix());
        Auth auth = Auth.create(properties.getQiniuAccessKey(), properties.getQiniuSecretKey());
        String uploadToken = auth.uploadToken(properties.getQiniuBucketName());
        long startMs = System.currentTimeMillis();

        Response response = null;
        try
        {
            response = qiniuUploadManager.put(
                    bytes, objectKey, uploadToken, null, getContentType(extension), true);
            if (!response.isOK())
            {
                log.error("[QINIU-UPLOAD] 上传失败, bucket={}, objectKey={}, status={}",
                        properties.getQiniuBucketName(), objectKey, response.statusCode);
                throw new OssException("上传失败");
            }
            String url = getFileUrl(objectKey, properties);
            log.info("[QINIU-UPLOAD] 上传成功, objectKey={}, url={}, elapsedMs={}",
                    objectKey, url, System.currentTimeMillis() - startMs);
            return url;
        }
        catch (OssException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[QINIU-UPLOAD] 上传异常, bucket={}, objectKey={}, elapsedMs={}, error={}",
                    properties.getQiniuBucketName(), objectKey,
                    System.currentTimeMillis() - startMs, e.getMessage(), e);
            throw new OssException("上传失败");
        }
        finally
        {
            closeQiniuResponse(response);
        }
    }

    /** 校验七牛云上传所需配置。 */
    private void validateQiniuConfig(OssProperties properties)
    {
        if (Objects.isNull(properties) || StrUtil.hasBlank(
                properties.getQiniuAccessKey(),
                properties.getQiniuSecretKey(),
                properties.getQiniuBucketName()))
        {
            log.error("[QINIU-UPLOAD] 七牛云配置不完整");
            throw new OssException("七牛云未配置");
        }
    }

    /**
     * 从七牛云删除文件。
     *
     * @param fileUrl 文件URL或相对路径
     * @return true=删除成功
     */
    public boolean deleteFromQiniu(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return false;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        try
        {
            validateQiniuConfig(properties);
            String objectKey = getObjectKeyFromUrl(fileUrl, properties);
            if (StrUtil.isBlank(objectKey))
            {
                return false;
            }
            Auth auth = Auth.create(properties.getQiniuAccessKey(), properties.getQiniuSecretKey());
            BucketManager bucketManager = new BucketManager(auth, qiniuConfiguration);
            Response response = bucketManager.delete(properties.getQiniuBucketName(), objectKey);
            try
            {
                return response.isOK();
            }
            finally
            {
                closeQiniuResponse(response);
            }
        }
        catch (QiniuException e)
        {
            // 七牛 Kodo 明确以 612 表示目标资源不存在；删除语义按幂等成功处理。
            if (e.code() == 612)
            {
                log.info("七牛云文件已不存在，按删除成功处理: fileUrl={}", fileUrl);
                return true;
            }
            log.error("七牛云删除文件失败: code={}, error={}", e.code(), e.error(), e);
            return false;
        }
        catch (Exception e)
        {
            log.error("七牛云删除文件失败：{}", e.getMessage(), e);
            return false;
        }
    }

    /** 关闭七牛云响应体，释放底层 HTTP 连接。 */
    private void closeQiniuResponse(Response response)
    {
        if (Objects.nonNull(response))
        {
            response.close();
        }
    }
    /**
     * 从腾讯云COS删除文件
     *
     * @param fileUrl 文件URL或相对路径
     * @return true=删除成功，false=删除失败
     */
    public boolean deleteFromCos(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl) || Objects.isNull(cosClient))
        {
            return false;
        }

        OssProperties properties = ossConfigManager.getOssProperties();

        try
        {
            String objectKey = getObjectKeyFromUrl(fileUrl, properties);
            if (StrUtil.isNotBlank(objectKey))
            {
                cosClient.deleteObject(properties.getCosBucketName(), objectKey);
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            log.error("COS删除文件失败：{}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从OSS删除文件
     *
     * @param fileUrl 文件URL
     * @return true=删除成功，false=删除失败
     */
    public boolean deleteFromOss(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl) || Objects.isNull(ossClient))
        {
            return false;
        }

        OssProperties properties = ossConfigManager.getOssProperties();

        try
        {
            String objectKey = getObjectKeyFromUrl(fileUrl, properties);
            if (StrUtil.isNotBlank(objectKey))
            {
                ossClient.deleteObject(properties.getBucketName(), objectKey);
                return true;
            }
            return false;
        }
        catch (Exception e)
        {
            log.error("OSS删除文件失败：{}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从本地删除文件
     *
     * @param fileUrl 文件URL
     * @return true=删除成功，false=删除失败
     */
    public boolean deleteFromLocal(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return false;
        }

        try
        {
            // 先去掉可能的 localDomain 前缀，得到以 /profile 开头的相对路径
            String normalizedUrl = normalizeLocalFileUrl(fileUrl);
            // 只允许删除 profile 目录下的文件
            if (!normalizedUrl.startsWith(Constants.RESOURCE_PREFIX))
            {
                log.warn("拒绝删除非 profile 路径的文件: {}", fileUrl);
                return false;
            }
            // 拒绝 ..
            if (normalizedUrl.contains(".."))
            {
                log.warn("拒绝删除包含 .. 的路径: {}", fileUrl);
                return false;
            }
            // 从URL提取本地路径
            Path baseDir = Paths.get(AidAppConfig.getProfile()).toAbsolutePath().normalize();
            String localPath = AidAppConfig.getProfile() + normalizedUrl.replace(Constants.RESOURCE_PREFIX, "");
            Path filePath = Paths.get(localPath).toAbsolutePath().normalize();
            // 双重断言：物理路径必须在 profile 之内
            if (!filePath.startsWith(baseDir))
            {
                log.warn("拒绝删除 profile 目录外文件: fileUrl={}, resolved={}", fileUrl, filePath);
                return false;
            }
            if (Files.exists(filePath))
            {
                Files.delete(filePath);
                return true;
            }
            // 文件本就不存在视为删除成功（幂等），避免重复删除或脏数据永久阻断 DB 删除
            return true;
        }
        catch (Exception e)
        {
            log.error("本地删除文件失败：{}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 判断给定 URL 是否指向本地存储文件：去除 localDomain 前缀后以 /profile 开头，
     * 或本身就是 /profile 相对路径。用于区分"本地（必须同步删成功）"与"远程（异步best-effort）"。
     *
     * @param fileUrl 文件 URL 或相对路径
     * @return true=本地文件
     */
    public boolean isLocalFile(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return false;
        }
        String normalizedLocal = normalizeLocalFileUrl(fileUrl);
        return StrUtil.isNotBlank(normalizedLocal) && normalizedLocal.startsWith(Constants.RESOURCE_PREFIX);
    }

    /**
     * 按文件 URL 自动路由删除：依据 URL 归属选择对应存储实现。
     *
     * @param fileUrl 文件 URL 或相对路径
     * @return true=删除成功或本就不存在（幂等）；false=删除失败（调用方应据此阻断后续 DB 删除）
     */
    public boolean deleteByUrl(String fileUrl)
    {
        // 空 URL 视为无需删除，返回 true 不阻断业务
        if (StrUtil.isBlank(fileUrl))
        {
            return true;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        String lower = fileUrl.toLowerCase();

        if (isLocalFile(fileUrl))
        {
            return deleteFromLocal(fileUrl);
        }

        if (lower.contains(".aliyuncs.com"))
        {
            return deleteFromOss(fileUrl);
        }

        if (lower.contains(".myqcloud.com"))
        {
            return deleteFromCos(fileUrl);
        }

        if (lower.contains(".clouddn.com") || lower.contains(".qiniucdn.com")
                || lower.contains(".qiniuio.com"))
        {
            return deleteFromQiniu(fileUrl);
        }

        String cdn = Objects.isNull(properties) ? null : properties.getCdnDomain();
        if (StrUtil.isNotBlank(cdn) && fileUrl.startsWith(stripDomainTrailingSlash(cdn)))
        {
            String mode = properties.getUploadMode();
            if ("cos".equalsIgnoreCase(mode))
            {
                return deleteFromCos(fileUrl);
            }
            if ("qiniu".equalsIgnoreCase(mode))
            {
                return deleteFromQiniu(fileUrl);
            }
            return deleteFromOss(fileUrl);
        }

        return delete(fileUrl);
    }
    /**
     * 统一删除入口：按当前 uploadMode 分发到本地或对应云存储。
     *
     * @param fileUrl 文件URL或相对路径
     * @return true=删除成功，false=删除失败
     */
    public boolean delete(String fileUrl)
    {
        OssProperties properties = ossConfigManager.getOssProperties();
        String mode = Objects.isNull(properties) ? "oss" : properties.getUploadMode();
        if ("local".equalsIgnoreCase(mode))
        {
            return deleteFromLocal(fileUrl);
        }
        if ("cos".equalsIgnoreCase(mode))
        {
            return deleteFromCos(fileUrl);
        }
        if ("qiniu".equalsIgnoreCase(mode))
        {
            return deleteFromQiniu(fileUrl);
        }
        return deleteFromOss(fileUrl);
    }

    /**
     * 获取文件临时访问URL。
     *
     * @param fileUrl 文件URL
     * @param expireSeconds 过期时间（秒）
     * @return 带签名的URL
     */
    public String getSignedUrl(String fileUrl, int expireSeconds)
    {
        return generateSignedUrl(fileUrl, expireSeconds, 24 * 3600, false);
    }

    /** 按调用场景生成签名，避免模型链接的较长期限改变其它临时链接的既有上限。 */
    private String generateSignedUrl(String fileUrl, int expireSeconds, int maxExpireSeconds,
                                     boolean publicOssEndpoint)
    {
        OssProperties properties = ossConfigManager.getOssProperties();
        String mode = Objects.isNull(properties) ? "oss" : properties.getUploadMode();

        // <= 0 使用默认 1 小时；上限由具体调用场景决定。
        int clampedExpire;
        if (expireSeconds <= 0)
        {
            clampedExpire = DEFAULT_EXPIRE_SECONDS;
        }
        else if (expireSeconds > maxExpireSeconds)
        {
            log.warn("临时签名过期时间超过场景上限，强制 clamp: requested={}s, max={}s",
                    expireSeconds, maxExpireSeconds);
            clampedExpire = maxExpireSeconds;
        }
        else
        {
            clampedExpire = expireSeconds;
        }

        // 七牛云私有空间分支
        if ("qiniu".equalsIgnoreCase(mode))
        {
            try
            {
                validateQiniuConfig(properties);
                String objectKey = getObjectKeyFromUrl(fileUrl, properties);
                String resourceDomain = properties.getEffectiveResourceAccessDomain();
                if (StrUtil.isBlank(objectKey) || StrUtil.isBlank(resourceDomain))
                {
                    return fileUrl;
                }
                String baseUrl = stripDomainTrailingSlash(resourceDomain) + "/" + objectKey;
                Auth auth = Auth.create(properties.getQiniuAccessKey(), properties.getQiniuSecretKey());
                return auth.privateDownloadUrl(baseUrl, clampedExpire);
            }
            catch (Exception e)
            {
                log.error("获取七牛云签名URL失败：{}", e.getMessage(), e);
                return fileUrl;
            }
        }

        // 腾讯云COS 分支
        if ("cos".equalsIgnoreCase(mode))
        {
            if (Objects.isNull(cosClient))
            {
                return fileUrl;
            }
            try
            {
                String objectKey = getObjectKeyFromUrl(fileUrl, properties);
                if (StrUtil.isNotBlank(objectKey))
                {
                    Date expiration = new Date(System.currentTimeMillis() + clampedExpire * 1000L);
                    URL url = cosClient.generatePresignedUrl(properties.getCosBucketName(), objectKey, expiration);
                    return url.toString();
                }
                return fileUrl;
            }
            catch (Exception e)
            {
                log.error("获取COS签名URL失败：{}", e.getMessage(), e);
                return fileUrl;
            }
        }

        // 阿里云OSS 分支
        if (Objects.isNull(ossClient))
        {
            return fileUrl;
        }

        try
        {
            String objectKey = getObjectKeyFromUrl(fileUrl, properties);
            if (StrUtil.isNotBlank(objectKey))
            {
                // 计算过期时间
                Date expiration = new Date(System.currentTimeMillis() + clampedExpire * 1000L);
                // 普通服务端读取继续复用配置的 Endpoint（可为内网）；
                // 只有提交外部大模型时才改用对应公网 Endpoint 计算签名。
                URL url = publicOssEndpoint
                        ? generatePublicOssPresignedUrl(properties, objectKey, expiration)
                        : ossClient.generatePresignedUrl(properties.getBucketName(), objectKey, expiration);
                return url.toString();
            }
            return fileUrl;
        }
        catch (Exception e)
        {
            log.error("获取签名URL失败：{}", e.getMessage(), e);
            return fileUrl;
        }
    }

    /**
     * 仅在向外部大模型实际提交时生成临时访问地址。普通页面展示仍使用资源访问地址，
     * 不会调用本方法。外部第三方 URL 原样返回；系统自有云存储资源签名失败则明确报错，
     * 避免把受防盗链保护的地址继续提交给模型。
     *
     * @param fileUrl 相对路径或系统资源完整地址
     * @return 可供外部模型短期读取的地址
     */
    public String getModelSignedUrl(String fileUrl)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return fileUrl;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        if (!isManagedResourceUrl(fileUrl, properties))
        {
            return fileUrl;
        }
        String mode = StrUtil.blankToDefault(properties.getUploadMode(), "local");
        if ("local".equalsIgnoreCase(mode))
        {
            // 本地文件没有对象存储签名协议；保持现有资源访问地址语义。
            return toResourceAccessUrl(fileUrl, properties);
        }
        int hours = Objects.isNull(properties.getModelSignedUrlExpireHours())
                ? 72 : Math.max(1, Math.min(168, properties.getModelSignedUrlExpireHours()));
        String signed = generateSignedUrl(fileUrl, hours * 3600, 168 * 3600, true);
        if (StrUtil.isBlank(signed) || Objects.equals(signed, fileUrl))
        {
            log.error("模型资源临时签名失败, mode={}, resource={}", mode, fileUrl);
            throw new OssException("签名失败");
        }
        return signed;
    }

    /** 判断地址是否属于当前系统配置的存储。 */
    public boolean isManagedResourceUrl(String fileUrl)
    {
        return isManagedResourceUrl(fileUrl, ossConfigManager.getOssProperties());
    }

    /**
     * 为模型级第三方图片代理准备无签名的站点资源地址。系统自有资源统一换成站长配置的
     * 资源访问域名，并移除原地址的查询参数和 Fragment；外部第三方 URL 原样返回。
     *
     * <p>该方法只由已显式开启图片代理的模型在签名前调用。普通模型仍走现有 COS/OSS
     * 临时签名链路，不受图片代理配置影响。</p>
     */
    public String toModelProxyUnsignedSourceUrl(String sourceUrl)
    {
        if (StrUtil.isBlank(sourceUrl))
        {
            return sourceUrl;
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        if (Objects.isNull(properties))
        {
            return sourceUrl;
        }
        if (!isManagedResourceUrl(sourceUrl, properties))
        {
            return sourceUrl;
        }
        String resourceDomain = stripDomainTrailingSlash(properties.getEffectiveResourceAccessDomain());
        if (StrUtil.isBlank(resourceDomain))
        {
            throw new OssException("资源访问域名未配置");
        }
        if (sourceUrl.startsWith("/"))
        {
            return toResourceAccessUrl(sourceUrl, properties);
        }
        try
        {
            URI source = URI.create(sourceUrl);
            URI access = URI.create(resourceDomain);
            if (!source.isAbsolute() || source.getHost() == null || access.getHost() == null
                    || !("http".equalsIgnoreCase(access.getScheme())
                    || "https".equalsIgnoreCase(access.getScheme())))
            {
                throw new OssException("资源访问域名配置无效");
            }
            String path = StrUtil.blankToDefault(source.getRawPath(), "/");
            if (access.getHost().equalsIgnoreCase(source.getHost()))
            {
                return access.getScheme() + "://" + access.getRawAuthority() + path;
            }
            return resourceDomain + path;
        }
        catch (Exception exception)
        {
            if (exception instanceof OssException ossException)
            {
                throw ossException;
            }
            throw new OssException("资源访问地址转换失败");
        }
    }

    private boolean isManagedResourceUrl(String fileUrl, OssProperties properties)
    {
        if (StrUtil.isBlank(fileUrl) || Objects.isNull(properties))
        {
            return false;
        }
        if (fileUrl.startsWith("/"))
        {
            return !fileUrl.startsWith("//") && !fileUrl.contains("..") && !fileUrl.contains("\\")
                    && !fileUrl.contains("?") && !fileUrl.contains("#")
                    && fileUrl.chars().noneMatch(Character::isISOControl);
        }
        String resourceDomain = stripDomainTrailingSlash(properties.getEffectiveResourceAccessDomain());
        if (StrUtil.isNotBlank(resourceDomain) && fileUrl.startsWith(resourceDomain + "/"))
        {
            return true;
        }
        String mode = properties.getUploadMode();
        if ("cos".equalsIgnoreCase(mode))
        {
            return urlHostEquals(fileUrl, properties.getCosBucketName() + ".cos."
                    + properties.getCosRegion() + ".myqcloud.com");
        }
        if ("oss".equalsIgnoreCase(mode))
        {
            String endpoint = normalizeOssEndpoint(properties.getEndpoint());
            return urlHostEquals(fileUrl, properties.getBucketName() + "." + endpoint)
                    || urlHostEquals(fileUrl, properties.getBucketName() + "." + toPublicOssEndpoint(endpoint));
        }
        return false;
    }

    private boolean urlHostEquals(String fileUrl, String expectedHost)
    {
        try
        {
            URI uri = URI.create(fileUrl);
            return StrUtil.equalsIgnoreCase(uri.getHost(), expectedHost)
                    && StrUtil.isNotBlank(uri.getPath()) && !"/".equals(uri.getPath());
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private String toResourceAccessUrl(String fileUrl, OssProperties properties)
    {
        if (!fileUrl.startsWith("/"))
        {
            return fileUrl;
        }
        String domain = stripDomainTrailingSlash(properties.getEffectiveResourceAccessDomain());
        if (StrUtil.isBlank(domain))
        {
            return fileUrl;
        }
        // 本地模式的兼容访问前缀会自动带 /profile，避免与相对资源路径重复拼接。
        if (domain.endsWith("/profile") && fileUrl.startsWith("/profile/"))
        {
            return domain + fileUrl.substring("/profile".length());
        }
        return domain + fileUrl;
    }

    private URL generatePublicOssPresignedUrl(OssProperties properties, String objectKey, Date expiration)
    {
        String configuredEndpoint = normalizeOssEndpoint(properties.getEndpoint());
        String publicEndpoint = toPublicOssEndpoint(configuredEndpoint);
        if (StrUtil.equalsIgnoreCase(configuredEndpoint, publicEndpoint))
        {
            return ossClient.generatePresignedUrl(properties.getBucketName(), objectKey, expiration);
        }
        OSS publicClient = new OSSClientBuilder().build(publicEndpoint,
                properties.getAccessKeyId(), properties.getAccessKeySecret());
        try
        {
            return publicClient.generatePresignedUrl(properties.getBucketName(), objectKey, expiration);
        }
        finally
        {
            publicClient.shutdown();
        }
    }

    private String normalizeOssEndpoint(String endpoint)
    {
        return StrUtil.removeSuffix(StrUtil.blankToDefault(endpoint, "")
                .trim().replaceFirst("(?i)^https?://", ""), "/")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private String toPublicOssEndpoint(String endpoint)
    {
        String normalized = normalizeOssEndpoint(endpoint);
        int firstDot = normalized.indexOf('.');
        if (firstDot < 0)
        {
            return normalized.replace("-internal", "").replace("-intranet", "");
        }
        String regionHost = normalized.substring(0, firstDot)
                .replace("-internal", "").replace("-intranet", "");
        return regionHost + normalized.substring(firstDot);
    }

    /**
     * 刷新配置
     */
    public void refresh()
    {
        ossConfigManager.refresh();
        log.info("OSS配置已刷新");
    }

    /**
     * 获取当前配置信息（供前端展示）
     *
     * @return 脱敏后的配置Map
     */
    public Map<String, String> getCurrentConfig()
    {
        return ossConfigManager.getCurrentConfig();
    }

    /**
     * 获取当前生效的上传大小限制（供前端上传前按类型做大小预校验，遵循后台 aid_config 配置）。
     * 返回分类型限制列表 + 全局兜底上限，前端据文件后缀命中类型后取对应上限。
     *
     * @return 上传大小限制视图
     */
    public OssUploadLimitsVO getUploadLimits()
    {
        OssUploadLimitsVO vo = new OssUploadLimitsVO();
        OssProperties properties = ossConfigManager.getOssProperties();
        if (Objects.isNull(properties))
        {
            return vo;
        }
        // 全局兜底：单文件上限（字节转MB）与允许扩展名
        Long maxFileSize = properties.getMaxFileSize();
        vo.setGlobalMaxSizeMb(Objects.isNull(maxFileSize) ? 0L : maxFileSize / 1024 / 1024);
        vo.setGlobalAllowedExtensions(properties.getAllowedExtensions());
        // 分类型限制：转换为前端可直接消费的结构（extensions 转为有序列表）
        List<OssProperties.UploadTypeLimit> limits = properties.getUploadTypeLimits();
        if (limits != null && !limits.isEmpty())
        {
            List<OssUploadLimitsVO.TypeLimit> typeLimits = new ArrayList<>(limits.size());
            for (OssProperties.UploadTypeLimit limit : limits)
            {
                OssUploadLimitsVO.TypeLimit item = new OssUploadLimitsVO.TypeLimit();
                item.setName(limit.getName());
                item.setMaxSizeMb(limit.getMaxSizeMb());
                item.setExtensions(new ArrayList<>(limit.getExtensions()));
                typeLimits.add(item);
            }
            vo.setTypeLimits(typeLimits);
        }
        return vo;
    }

    /**
     * 获取OSS配置属性
     *
     * @return OSS配置属性
     */
    public OssProperties getProperties()
    {
        return ossConfigManager.getOssProperties();
    }

    /**
     * 校验文件扩展名是否允许
     *
     * @param extension 文件扩展名
     * @return true=允许，false=不允许
     */
    public boolean isAllowedExtension(String extension)
    {
        if (StrUtil.isBlank(extension))
        {
            return false;
        }

        OssProperties properties = ossConfigManager.getOssProperties();

        // 优先用「分类型上传限制」作为扩展名白名单：命中任意类型即允许
        List<OssProperties.UploadTypeLimit> limits =
                Objects.isNull(properties) ? null : properties.getUploadTypeLimits();
        if (limits != null && !limits.isEmpty())
        {
            String extLower = extension.toLowerCase();
            for (OssProperties.UploadTypeLimit limit : limits)
            {
                if (limit.getExtensions().contains(extLower))
                {
                    return true;
                }
            }
            return false;
        }

        // 回退旧逻辑：全局 allowedExtensions 白名单
        String allowed = properties.getAllowedExtensions();
        if (StrUtil.isBlank(allowed))
        {
            return true;
        }

        // 将允许的扩展名转为小写列表进行比较
        List<String> allowedList = Arrays.asList(allowed.toLowerCase().split(","));
        return allowedList.contains(extension.toLowerCase());
    }
    /**
     * 统一上传入口：按配置的 uploadMode 分发到本地或对应云存储。
     * 返回统一格式的相对路径（带前导 /），DB 只存这个相对路径。
     *
     * @param file MultipartFile
     * @param customDir 自定义子目录（可空）
     * @return 相对访问路径
     */
    public String upload(MultipartFile file, String customDir)
    {
        OssProperties properties = ossConfigManager.getOssProperties();
        String mode = Objects.isNull(properties) ? "oss" : properties.getUploadMode();
        if ("local".equalsIgnoreCase(mode))
        {
            return uploadToLocal(file, customDir);
        }
        if ("cos".equalsIgnoreCase(mode))
        {
            return uploadToCos(file, customDir);
        }
        if ("qiniu".equalsIgnoreCase(mode))
        {
            return uploadToQiniu(file, customDir);
        }
        return uploadToOss(file, customDir);
    }

    /**
     * 上传系统内部生成的文件。跳过面向用户上传入口的扩展名与大小限制，仍执行目录白名单、
     * 存储凭证和 SDK 上传校验；用于成片等已由本系统生成并验证的受信文件。
     */
    public String uploadSystemGeneratedFile(MultipartFile file, String customDir)
    {
        if (Objects.isNull(file) || file.isEmpty())
        {
            log.error("系统生成文件为空, fileName={}", Objects.isNull(file) ? null : file.getOriginalFilename());
            throw new OssException("上传失败");
        }
        OssProperties properties = ossConfigManager.getOssProperties();
        String mode = Objects.isNull(properties) ? "local" : properties.getUploadMode();
        if ("local".equalsIgnoreCase(mode))
        {
            return uploadToLocal(file, customDir, false);
        }
        if ("cos".equalsIgnoreCase(mode))
        {
            return uploadToCos(file, customDir, false);
        }
        if ("qiniu".equalsIgnoreCase(mode))
        {
            return uploadToQiniu(file, customDir, false);
        }
        return uploadToOss(file, customDir, false);
    }

    /**
     * 统一字节流上传入口：供媒体流水线等系统内部场景使用。
     *
     * @param bytes 字节数组
     * @param fileName 文件名（用于取扩展名）
     * @param customDir 自定义子目录（可空）
     * @return 相对访问路径
     */
    public String uploadBytes(byte[] bytes, String fileName, String customDir)
    {
        OssProperties properties = ossConfigManager.getOssProperties();
        String mode = Objects.isNull(properties) ? "oss" : properties.getUploadMode();
        if ("local".equalsIgnoreCase(mode))
        {
            // local 模式：fileName 可能只有后缀，先规范为 UUID.后缀
            String ext = FilenameUtils.getExtension(fileName);
            String safeName = StrUtil.isNotBlank(ext)
                    ? generateFileName(ext)
                    : IdUtils.fastSimpleUUID() + ".bin";
            return uploadBytesToLocal(bytes, safeName);
        }
        if ("cos".equalsIgnoreCase(mode))
        {
            return uploadBytesToCos(bytes, fileName, customDir);
        }
        if ("qiniu".equalsIgnoreCase(mode))
        {
            return uploadBytesToQiniu(bytes, fileName, customDir);
        }
        return uploadBytesToOss(bytes, fileName, customDir);
    }
    /**
     * 对外暴露的文件预校验入口：用于批量上传在真正上传前先做完整校验，
     * 避免出现“前几个文件已上传、后一个文件校验失败”导致的部分成功脏副作用。
     *
     * @param file 上传的文件
     */
    public void validate(MultipartFile file)
    {
        validateFile(file);
    }

    /**
     * 校验文件基本信息
     *
     * @param file 上传的文件
     */
    private void validateFile(MultipartFile file)
    {
        if (Objects.isNull(file) || file.isEmpty())
        {
            log.error("上传失败：文件为空");
            throw new OssException("文件为空");
        }

        // 校验文件名长度
        String originalFilename = file.getOriginalFilename();
        if (StrUtil.isNotBlank(originalFilename) && originalFilename.length() > DEFAULT_FILE_NAME_LENGTH)
        {
            log.error("上传失败：文件名过长，length={}", originalFilename.length());
            throw new OssException("文件名过长");
        }

        // 校验文件扩展名
        String extension = FilenameUtils.getExtension(originalFilename);
        if (!isAllowedExtension(extension))
        {
            log.error("上传失败：不支持的文件类型，extension={}", extension);
            throw new OssException("文件类型错误");
        }

        // 校验文件大小
        OssProperties properties = ossConfigManager.getOssProperties();
        long fileSize = file.getSize();

        // 优先按「分类型上传限制」校验：命中类型用该类型上限，提示中带类型名与上限
        List<OssProperties.UploadTypeLimit> limits =
                Objects.isNull(properties) ? null : properties.getUploadTypeLimits();
        if (limits != null && !limits.isEmpty())
        {
            String extLower = StrUtil.isBlank(extension) ? "" : extension.toLowerCase();
            for (OssProperties.UploadTypeLimit limit : limits)
            {
                if (limit.getExtensions().contains(extLower))
                {
                    if (fileSize > limit.getMaxBytes())
                    {
                        log.error("上传失败：{}文件大小超出限制，size={}, maxBytes={}",
                                limit.getName(), fileSize, limit.getMaxBytes());
                        throw new OssException(limit.getName() + "大小不能超过 " + limit.getMaxSizeMb() + "MB");
                    }
                    return;
                }
            }
            // isAllowedExtension 已确保扩展名命中某一类型，理论不会走到这里；兜底拦截
            log.error("上传失败：不支持的文件类型，extension={}", extension);
            throw new OssException("文件类型错误");
        }

        // 回退旧逻辑：全局 maxFileSize
        if (fileSize > properties.getMaxFileSize())
        {
            log.error("上传失败：文件大小超出限制，size={}, maxSize={}", fileSize, properties.getMaxFileSize());
            throw new OssException("文件过大");
        }
    }
    /**
     * 生成UUID格式的文件名
     *
     * @param extension 文件扩展名
     * @return UUID文件名
     */
    private String generateFileName(String extension)
    {
        return IdUtils.fastSimpleUUID() + "." + extension;
    }

    /**
     * 构建OSS对象键（包含路径），使用配置的默认前缀。
     *
     * @param customDir 自定义目录
     * @param fileName 文件名
     * @param properties OSS配置
     * @return OSS对象键
     */
    private String buildObjectKey(String customDir, String fileName, OssProperties properties)
    {
        return buildObjectKey(customDir, fileName, properties.getPrefix());
    }

    /**
     * 构建对象键（包含路径），可指定前缀。
     * customDir 统一走白名单清洗（与 local 模式同口径），防 `..`/特殊字符把对象写出配置前缀命名空间。
     *
     * @param customDir 自定义目录
     * @param fileName 文件名
     * @param prefix 路径前缀（可空）
     * @return 对象键
     */
    private String buildObjectKey(String customDir, String fileName, String prefix)
    {
        String safeCustomDir = sanitizeCustomDir(customDir);
        StringBuilder keyBuilder = new StringBuilder();

        // 添加配置的前缀
        if (StrUtil.isNotBlank(prefix))
        {
            keyBuilder.append(prefix);
            if (!prefix.endsWith("/"))
            {
                keyBuilder.append("/");
            }
        }

        // 添加自定义目录（已清洗：无 ..、无反斜杠、无首尾斜杠）
        if (StrUtil.isNotBlank(safeCustomDir))
        {
            keyBuilder.append(safeCustomDir).append("/");
        }

        // 添加日期目录（格式：yyyy/MM/dd）
        keyBuilder.append(DateUtils.datePath()).append("/");

        // 添加文件名
        keyBuilder.append(fileName);

        return keyBuilder.toString();
    }

    /**
     * 返回云存储上传后的相对访问路径（带前导 /，不拼域名）。
     *
     * @param objectKey 对象键
     * @param properties OSS配置（预留）
     * @return 相对路径，如 /2026/04/23/xxx.png
     */
    private String getFileUrl(String objectKey, OssProperties properties)
    {
        return objectKey.startsWith("/") ? objectKey : "/" + objectKey;
    }

    /**
     * 去除域名末尾多余的 `/`，便于做前缀匹配。
     *
     * @param domain 域名（可带末尾 /）
     * @return 去尾斜杠后的域名
     */
    private String stripDomainTrailingSlash(String domain)
    {
        if (StrUtil.isBlank(domain))
        {
            return domain;
        }
        String d = domain.trim();
        while (d.endsWith("/"))
        {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    /**
     * 从URL中提取对象键
     *
     * @param fileUrl 文件URL
     * @param properties OSS配置
     * @return OSS对象键
     */
    private String getObjectKeyFromUrl(String fileUrl, OssProperties properties)
    {
        if (StrUtil.isBlank(fileUrl))
        {
            return null;
        }

        try
        {
            // 兼容旧数据：带 CDN 域名的完整 URL（按当前模式取生效的 CDN 域名）
            String cdnDomain = properties.getEffectiveCdnDomain();
            if (StrUtil.isNotBlank(cdnDomain) && fileUrl.startsWith(cdnDomain))
            {
                String rest = fileUrl.substring(cdnDomain.length());
                return rest.startsWith("/") ? rest.substring(1) : rest;
            }

            // 兼容旧数据：带默认云存储域名的完整 URL
            String defaultDomain;
            if ("cos".equalsIgnoreCase(properties.getUploadMode()))
            {
                // 腾讯云COS 默认域名：https://{bucket}.cos.{region}.myqcloud.com
                defaultDomain = "https://" + properties.getCosBucketName()
                        + ".cos." + properties.getCosRegion() + ".myqcloud.com";
            }
            else if ("oss".equalsIgnoreCase(properties.getUploadMode()))
            {
                // 阿里云OSS 默认域名：https://{bucket}.{endpoint}
                defaultDomain = "https://" + properties.getBucketName() + "." + properties.getEndpoint();
            }
            else
            {
                // 七牛云下载域名由空间绑定，统一通过 cdnDomain 匹配。
                defaultDomain = null;
            }
            if (StrUtil.isNotBlank(defaultDomain) && fileUrl.startsWith(defaultDomain))
            {
                return fileUrl.substring(defaultDomain.length() + 1);
            }

            // 新数据：以 / 开头的相对路径即 objectKey（去掉前导 /）
            if (fileUrl.startsWith("/"))
            {
                return fileUrl.substring(1);
            }

            // 兜底：任意完整 http(s) URL（如跨云数据迁移留下的历史完整地址）。
            // 归属（阿里云/腾讯云）已由 deleteByUrl 按 host 判定，这里不再依赖当前 uploadMode 的域名匹配，
            // 直接用 URI 的 path 去掉前导 / 作为 objectKey，确保跨云完整 URL 也能正确解析删除。
            String lower = fileUrl.toLowerCase();
            if (lower.startsWith("http://") || lower.startsWith("https://"))
            {
                String path = java.net.URI.create(fileUrl).getPath();
                if (StrUtil.isNotBlank(path))
                {
                    return path.startsWith("/") ? path.substring(1) : path;
                }
            }

            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 根据扩展名获取Content-Type
     *
     * @param extension 文件扩展名
     * @return Content-Type
     */
    private String getContentType(String extension)
    {
        if (StrUtil.isBlank(extension))
        {
            return "application/octet-stream";
        }

        String ext = extension.toLowerCase();
        if (Objects.equals(ext, "png"))
        {
            return "image/png";
        }
        if (Objects.equals(ext, "jpg") || Objects.equals(ext, "jpeg"))
        {
            return "image/jpeg";
        }
        if (Objects.equals(ext, "gif"))
        {
            return "image/gif";
        }
        if (Objects.equals(ext, "bmp"))
        {
            return "image/bmp";
        }
        if (Objects.equals(ext, "webp"))
        {
            return "image/webp";
        }
        if (Objects.equals(ext, "pdf"))
        {
            return "application/pdf";
        }
        // 默认返回二进制流类型
        return "application/octet-stream";
    }
}
