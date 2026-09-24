package com.aid.common.image.detection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.aid.common.aid.core.service.ConfigService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.region.Region;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;

/** 后台联通测试先读取并核实原图；不提供通用的任意 URL 代理。 */
@Component
@RequiredArgsConstructor
public class ImageDetectionSourceProbe {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final ConfigService configService;

    public DetectImageObjectsInput cosObject(String key, ImageDetectionConfig config, String traceId) {
        if (StrUtil.isBlank(key) || key.startsWith("/") || key.contains("..") || key.contains("\\")
                || key.length() > 1024) throw invalid();
        ClientConfig clientConfig = new ClientConfig(new Region(config.region()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionTimeout(config.connectTimeoutMs());
        clientConfig.setSocketTimeout(config.readTimeoutMs());
        COSClient client = new COSClient(new BasicCOSCredentials(config.secretId(), config.secretKey()), clientConfig);
        try {
            COSObject object = client.getObject(config.bucketName(), key);
            try (InputStream input = object.getObjectContent()) {
                ImageMeta meta = inspect(input);
                return new DetectImageObjectsInput(ImageSource.cosObject(config.bucketName(), config.region(), key),
                        meta.width, meta.height, meta.bytes, meta.format, traceId);
            }
        } catch (ImageDetectionException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "原图读取失败", 0);
        } finally {
            client.shutdown();
        }
    }

    public DetectImageObjectsInput verifiedUrl(String imageUrl, String traceId) {
        URI uri;
        try {
            if (imageUrl == null || imageUrl.length() > 4096) throw invalid();
            uri = URI.create(imageUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || StrUtil.isBlank(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                    || !allowedHost(uri.getHost()) || !publicAddress(uri.getHost())) throw invalid();
        } catch (RuntimeException e) {
            throw invalid();
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != 200) throw invalid();
            ImageMeta meta = inspect(connection.getInputStream());
            return new DetectImageObjectsInput(ImageSource.verifiedUrl(imageUrl), meta.width, meta.height,
                    meta.bytes, meta.format, traceId);
        } catch (ImageDetectionException e) {
            throw e;
        } catch (Exception e) {
            throw new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "原图读取失败", 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean allowedHost(String host) {
        try {
            Map<String, String> oss = configService.getConfigValues("oss");
            String domain = oss.get("resourceAccessDomain");
            if (StrUtil.isNotBlank(domain) && Objects.equals(host, URI.create(domain).getHost())) return true;
            String whitelist = oss.getOrDefault("imageUrlWhitelist", "");
            return Arrays.stream(whitelist.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .anyMatch(s -> {
                        try { return Objects.equals(host, URI.create(s).getHost()); }
                        catch (RuntimeException e) { return false; }
                    });
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean publicAddress(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                byte[] bytes = address.getAddress();
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ImageMeta inspect(InputStream stream) throws Exception {
        byte[] bytes;
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                if (output.size() + count > MAX_BYTES) throw invalid();
                output.write(chunk, 0, count);
            }
            bytes = output.toByteArray();
        }
        try (ImageInputStream image = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(image);
                return new ImageMeta(reader.getWidth(0), reader.getHeight(0), bytes.length,
                        reader.getFormatName().toLowerCase());
            } finally {
                reader.dispose();
            }
        }
    }

    private ImageDetectionException invalid() {
        return new ImageDetectionException(ImageDetectionException.Code.INVALID_IMAGE, "图片地址不可用", 0);
    }

    private record ImageMeta(int width, int height, int bytes, String format) {}
}
