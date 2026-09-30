package com.aid.config.test.tester;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aid.common.config.AidAppConfig;
import com.aid.common.config.test.ConfigConnectivityTester;
import com.aid.common.config.test.ConfigTestRequest;
import com.aid.common.config.test.ConfigTestResult;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.IdUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 对象存储配置读写连通性测试。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class OssConnectivityTester implements ConfigConnectivityTester {

    /** 探测文件大小。 */
    private static final int PROBE_FILE_SIZE = 1024;

    /** 探测对象目录。 */
    private static final String PROBE_DIRECTORY = "__aid_probe__";

    /** 本地文件统一公共访问路径。 */
    private static final String LOCAL_PROFILE_PATH = "/profile";

    /** 探测文件内容。 */
    private static final byte[] PROBE_BYTES = new byte[PROBE_FILE_SIZE];

    @Override
    public String testKey() {
        return "oss";
    }

    @Override
    public ConfigTestResult test(ConfigTestRequest request) {
        Map<String, Object> payload = request.getPayload();
        String uploadMode = TesterPayloads.str(payload, "uploadMode", "oss").toLowerCase();

        try {
            return switch (uploadMode) {
                case "cos" -> testCos(payload);
                case "qiniu" -> testQiniu(payload);
                case "local" -> testLocal(payload);
                case "oss" -> testOss(payload);
                default -> ConfigTestResult.fail("存储模式不支持");
            };
        } catch (Exception e) {
            log.error("对象存储测试异常: uploadMode={}, exception={}", uploadMode, e.getClass().getSimpleName());
            return failWithDetails("对象存储测试失败",
                    "uploadMode=" + uploadMode + "; " + e.getClass().getSimpleName());
        }
    }

    /** 阿里云 OSS 读写删除测试。 */
    private ConfigTestResult testOss(Map<String, Object> payload) {
        String endpoint = TesterPayloads.str(payload, "endpoint");
        String accessKeyId = TesterPayloads.str(payload, "accessKeyId");
        String accessKeySecret = TesterPayloads.str(payload, "accessKeySecret");
        String bucketName = TesterPayloads.str(payload, "bucketName");
        String prefix = TesterPayloads.str(payload, "prefix");
        String resourceDomain = resourceDomain(payload);

        if (StrUtil.hasBlank(endpoint, accessKeyId, accessKeySecret, bucketName, resourceDomain)) {
            return ConfigTestResult.fail("请填写完整OSS配置");
        }
        if (!isOfficialOssEndpoint(endpoint)) {
            return ConfigTestResult.fail("OSS Endpoint格式错误");
        }
        if (!isHttpDomain(resourceDomain)) {
            return ConfigTestResult.fail("访问域名格式错误");
        }

        String objectKey = buildProbeKey(prefix);
        String details = StrUtil.format("uploadMode=oss; endpoint={}; bucketName={}; resourceAccessDomain={}",
                endpoint, bucketName, resourceDomain);
        OSS client = null;
        boolean uploaded = false;
        try {
            client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
            if (!client.doesBucketExist(bucketName)) {
                return failWithDetails("Bucket不存在或无权限", details + "; bucket=false");
            }
            client.putObject(bucketName, objectKey, new ByteArrayInputStream(PROBE_BYTES));
            uploaded = true;
            if (!client.doesObjectExist(bucketName, objectKey)) {
                return failWithDetails("OSS写入校验失败", details + "; stat=false");
            }
            client.deleteObject(bucketName, objectKey);
            uploaded = false;
            return success("oss", details);
        } catch (Exception e) {
            log.error("OSS读写测试失败: bucketName={}, exception={}", bucketName, e.getClass().getSimpleName());
            return failWithDetails("OSS读写测试失败", details + "; " + e.getClass().getSimpleName());
        } finally {
            if (client != null) {
                if (uploaded) {
                    deleteOssProbe(client, bucketName, objectKey);
                }
                client.shutdown();
            }
        }
    }

    /** 腾讯云 COS 读写删除测试。 */
    private ConfigTestResult testCos(Map<String, Object> payload) {
        String region = TesterPayloads.str(payload, "cosRegion");
        String secretId = TesterPayloads.str(payload, "cosSecretId");
        String secretKey = TesterPayloads.str(payload, "cosSecretKey");
        String bucketName = TesterPayloads.str(payload, "cosBucketName");
        String prefix = TesterPayloads.str(payload, "cosPrefix");
        String resourceDomain = resourceDomain(payload);

        if (StrUtil.hasBlank(region, secretId, secretKey, bucketName, resourceDomain)) {
            return ConfigTestResult.fail("请填写完整COS配置");
        }
        if (!isHttpDomain(resourceDomain)) {
            return ConfigTestResult.fail("访问域名格式错误");
        }

        String objectKey = buildProbeKey(prefix);
        String details = StrUtil.format("uploadMode=cos; region={}; bucketName={}; resourceAccessDomain={}",
                region, bucketName, resourceDomain);
        COSClient client = null;
        boolean uploaded = false;
        try {
            COSCredentials credentials = new BasicCOSCredentials(secretId, secretKey);
            ClientConfig clientConfig = new ClientConfig(new Region(region));
            clientConfig.setHttpProtocol(HttpProtocol.https);
            client = new COSClient(credentials, clientConfig);
            client.getBucketLocation(bucketName);
            com.qcloud.cos.model.ObjectMetadata metadata = new com.qcloud.cos.model.ObjectMetadata();
            metadata.setContentLength(PROBE_BYTES.length);
            metadata.setContentType("application/octet-stream");
            client.putObject(bucketName, objectKey, new ByteArrayInputStream(PROBE_BYTES), metadata);
            uploaded = true;
            if (!client.doesObjectExist(bucketName, objectKey)) {
                return failWithDetails("COS写入校验失败", details + "; stat=false");
            }
            client.deleteObject(bucketName, objectKey);
            uploaded = false;
            return success("cos", details);
        } catch (Exception e) {
            log.error("COS读写测试失败: bucketName={}, exception={}", bucketName, e.getClass().getSimpleName());
            return failWithDetails("COS读写测试失败", details + "; " + e.getClass().getSimpleName());
        } finally {
            if (client != null) {
                if (uploaded) {
                    deleteCosProbe(client, bucketName, objectKey);
                }
                client.shutdown();
            }
        }
    }

    /** 七牛云 Kodo 读写删除测试。 */
    private ConfigTestResult testQiniu(Map<String, Object> payload) {
        String accessKey = TesterPayloads.str(payload, "qiniuAccessKey");
        String secretKey = TesterPayloads.str(payload, "qiniuSecretKey");
        String bucketName = TesterPayloads.str(payload, "qiniuBucketName");
        String prefix = TesterPayloads.str(payload, "qiniuPrefix");
        String resourceDomain = resourceDomain(payload);

        if (StrUtil.hasBlank(accessKey, secretKey, bucketName, resourceDomain)) {
            return ConfigTestResult.fail("请填写完整七牛配置");
        }
        if (!isHttpDomain(resourceDomain)) {
            return ConfigTestResult.fail("访问域名格式错误");
        }

        String objectKey = buildProbeKey(prefix);
        String details = StrUtil.format("uploadMode=qiniu; bucketName={}; resourceAccessDomain={}",
                bucketName, resourceDomain);
        Configuration configuration = Configuration.create();
        Auth auth = Auth.create(accessKey, secretKey);
        UploadManager uploadManager = new UploadManager(configuration);
        BucketManager bucketManager = new BucketManager(auth, configuration);
        boolean uploaded = false;
        Response uploadResponse = null;
        Response deleteResponse = null;
        try {
            uploadResponse = uploadManager.put(PROBE_BYTES, objectKey, auth.uploadToken(bucketName));
            if (!uploadResponse.isOK()) {
                return failWithDetails("七牛写入测试失败",
                        details + "; status=" + uploadResponse.statusCode);
            }
            uploaded = true;
            bucketManager.stat(bucketName, objectKey);
            deleteResponse = bucketManager.delete(bucketName, objectKey);
            if (!deleteResponse.isOK()) {
                return failWithDetails("七牛删除测试失败",
                        details + "; status=" + deleteResponse.statusCode);
            }
            uploaded = false;
            return success("qiniu", details);
        } catch (Exception e) {
            log.error("七牛云读写测试失败: bucketName={}, exception={}", bucketName, e.getClass().getSimpleName());
            return failWithDetails("七牛读写测试失败", details + "; " + e.getClass().getSimpleName());
        } finally {
            closeQiniuResponse(uploadResponse);
            closeQiniuResponse(deleteResponse);
            if (uploaded) {
                deleteQiniuProbe(bucketManager, bucketName, objectKey);
            }
        }
    }

    /** 本地目录读写删除测试。 */
    private ConfigTestResult testLocal(Map<String, Object> payload) {
        String resourceDomain = resourceDomain(payload);
        if (StrUtil.isBlank(resourceDomain)) {
            return ConfigTestResult.fail("请填写资源访问地址");
        }
        if (!isHttpDomain(resourceDomain)) {
            return ConfigTestResult.fail("访问域名格式错误");
        }
        if (StrUtil.isNotBlank(resourceDomain) && domainPathEndsWith(resourceDomain, LOCAL_PROFILE_PATH)) {
            return ConfigTestResult.fail("本地域名不要加/profile");
        }

        if (StrUtil.isBlank(AidAppConfig.getProfile())) {
            return ConfigTestResult.fail("本地目录未配置");
        }
        String uploadPath = AidAppConfig.getUploadPath();
        String publicPrefix = StrUtil.isBlank(resourceDomain)
                ? LOCAL_PROFILE_PATH
                : StrUtil.removeSuffix(resourceDomain.trim(), "/") + LOCAL_PROFILE_PATH;
        String details = "uploadMode=local; uploadPath=" + uploadPath
                + "; resourceAccessDomain=" + (StrUtil.isBlank(resourceDomain) ? "<relative>" : resourceDomain)
                + "; publicPrefix=" + publicPrefix;
        File probeFile = null;
        try {
            File folder = new File(uploadPath, PROBE_DIRECTORY);
            if (!folder.exists() && !folder.mkdirs()) {
                return failWithDetails("本地目录不可写", details + "; mkdirs=false");
            }
            probeFile = new File(folder, IdUtil.fastSimpleUUID() + ".tmp");
            try (FileOutputStream outputStream = new FileOutputStream(probeFile)) {
                outputStream.write(PROBE_BYTES);
            }
            if (Files.size(probeFile.toPath()) != PROBE_FILE_SIZE) {
                return failWithDetails("本地写入校验失败", details + "; size=false");
            }
            String probeUrl = publicPrefix + "/upload/" + PROBE_DIRECTORY + "/" + probeFile.getName();
            if (!canReadLocalProbe(probeUrl)) {
                return failWithDetails("资源访问地址无法读取上传文件，请检查协议、端口和转发配置",
                        details + "; publicRead=failed");
            }
            Files.delete(probeFile.toPath());
            probeFile = null;
            return success("local", details);
        } catch (Exception e) {
            log.error("本地存储测试失败: path={}, exception={}", uploadPath, e.getClass().getSimpleName());
            return failWithDetails("本地目录不可写", details + "; " + e.getClass().getSimpleName());
        } finally {
            deleteLocalProbe(probeFile);
        }
    }

    /** 用实际公开地址读取刚写入的随机文件，避免仅测试磁盘成功却保存不可用的域名。 */
    private boolean canReadLocalProbe(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(4000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            try (InputStream input = connection.getInputStream()) {
                return Arrays.equals(PROBE_BYTES, input.readNBytes(PROBE_FILE_SIZE + 1));
            }
        } catch (Exception e) {
            log.info("本地资源访问探测失败: exception={}", e.getClass().getSimpleName());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 构建不会与业务对象冲突的探测键。 */
    private String buildProbeKey(String prefix) {
        String normalizedPrefix = StrUtil.trimToEmpty(prefix).replace('\\', '/');
        normalizedPrefix = StrUtil.strip(normalizedPrefix, "/");
        String key = PROBE_DIRECTORY + "/" + System.currentTimeMillis() + "-" + IdUtil.fastSimpleUUID() + ".bin";
        return StrUtil.isBlank(normalizedPrefix) ? key : normalizedPrefix + "/" + key;
    }

    /** 新公共字段优先，兼容旧后台仍提交 cdnDomain/localDomain 的测试请求。 */
    private String resourceDomain(Map<String, Object> payload) {
        String value = TesterPayloads.str(payload, "resourceAccessDomain");
        if (StrUtil.isNotBlank(value)) {
            return value;
        }
        value = TesterPayloads.str(payload, "cdnDomain");
        return StrUtil.isNotBlank(value) ? value : TesterPayloads.str(payload, "localDomain");
    }

    /** 校验公共访问域名。 */
    private boolean isHttpDomain(String value) {
        try {
            URI uri = URI.create(value.trim());
            return (Objects.equals("http", uri.getScheme()) || Objects.equals("https", uri.getScheme()))
                    && StrUtil.isNotBlank(uri.getHost())
                    && Objects.isNull(uri.getUserInfo())
                    && (StrUtil.isBlank(uri.getPath()) || "/".equals(uri.getPath()))
                    && Objects.isNull(uri.getQuery())
                    && Objects.isNull(uri.getFragment());
        } catch (Exception e) {
            return false;
        }
    }

    /** 连接测试会携带访问密钥，只允许阿里云 OSS 官方 Endpoint，避免把密钥发送到任意主机。 */
    private boolean isOfficialOssEndpoint(String value) {
        try {
            URI uri = URI.create(value.matches("(?i)^https?://.*") ? value.trim() : "https://" + value.trim());
            String host = StrUtil.blankToDefault(uri.getHost(), "").toLowerCase();
            return host.matches("^oss-[a-z0-9-]+(?:-internal|-intranet)?\\.aliyuncs\\.com$")
                    && Objects.isNull(uri.getUserInfo()) && uri.getPort() == -1
                    && (StrUtil.isBlank(uri.getPath()) || "/".equals(uri.getPath()))
                    && Objects.isNull(uri.getQuery()) && Objects.isNull(uri.getFragment());
        } catch (Exception e) {
            return false;
        }
    }

    /** 判断访问域名路径是否以指定固定段结尾。 */
    private boolean domainPathEndsWith(String value, String expectedPath) {
        try {
            String path = StrUtil.removeSuffix(URI.create(value.trim()).getPath(), "/");
            return StrUtil.endWithIgnoreCase(path, expectedPath);
        } catch (Exception e) {
            return false;
        }
    }

    /** 构建成功结果。 */
    private ConfigTestResult success(String provider, String details) {
        ConfigTestResult result = ConfigTestResult.ok("读写删除测试通过", provider);
        result.setDetails(details + "; write=ok; stat=ok; delete=ok");
        return result;
    }

    private void deleteOssProbe(OSS client, String bucketName, String objectKey) {
        try {
            client.deleteObject(bucketName, objectKey);
        } catch (Exception e) {
            log.warn("OSS探测文件清理失败: bucketName={}, objectKey={}", bucketName, objectKey);
        }
    }

    private void deleteCosProbe(COSClient client, String bucketName, String objectKey) {
        try {
            client.deleteObject(bucketName, objectKey);
        } catch (Exception e) {
            log.warn("COS探测文件清理失败: bucketName={}, objectKey={}", bucketName, objectKey);
        }
    }

    private void deleteQiniuProbe(BucketManager bucketManager, String bucketName, String objectKey) {
        Response response = null;
        try {
            response = bucketManager.delete(bucketName, objectKey);
            // 清理失败只记录，不覆盖原测试结果。
        } catch (Exception e) {
            log.warn("七牛探测文件清理失败: bucketName={}, objectKey={}", bucketName, objectKey);
        } finally {
            closeQiniuResponse(response);
        }
    }

    private void closeQiniuResponse(Response response) {
        if (Objects.nonNull(response)) {
            response.close();
        }
    }

    private void deleteLocalProbe(File file) {
        if (Objects.isNull(file) || !file.exists()) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (Exception e) {
            log.warn("本地探测文件清理失败: path={}", file.getAbsolutePath());
        }
    }

    /** 构造带调试明细的失败结果。 */
    private ConfigTestResult failWithDetails(String message, String details) {
        ConfigTestResult result = ConfigTestResult.fail(message);
        result.setDetails(details);
        return result;
    }
}
