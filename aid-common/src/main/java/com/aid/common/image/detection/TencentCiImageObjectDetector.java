package com.aid.common.image.detection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.http.client.utils.URIBuilder;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSSigner;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.region.Region;

import lombok.extern.slf4j.Slf4j;

/** 腾讯云数据万象 AIObjectDetect；不复用 IMS 的 TC3 签名。 */
@Slf4j
@Component
public class TencentCiImageObjectDetector implements ImageObjectDetector {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    @Override public String provider() { return "tencent_ci"; }

    @Override
    public DetectImageObjectsResult detect(DetectImageObjectsInput input, ImageDetectionConfig config) {
        SignedRequest signed = sign(input, config);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) signed.url().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(config.connectTimeoutMs());
            connection.setReadTimeout(config.readTimeoutMs());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/xml");
            if (signed.authorization() != null) connection.setRequestProperty("Authorization", signed.authorization());
            int status = connection.getResponseCode();
            String requestId = connection.getHeaderField("x-cos-request-id");
            if (status < 200 || status >= 300) {
                log.warn("CI主体检测失败: http={}, requestId={}", status, requestId);
                ImageDetectionException.Code code = status == 429
                        ? ImageDetectionException.Code.RATE_LIMITED
                        : status == 401 || status == 403
                        ? ImageDetectionException.Code.AUTH_ERROR : ImageDetectionException.Code.PROVIDER_ERROR;
                String message = status == 429 ? "识别请求过频"
                        : status == 401 || status == 403 ? "识别授权失败"
                        : sourceDownloadFailed(connection) ? "腾讯云无法读取原图" : "识别服务异常";
                throw new ImageDetectionException(code, message, 1, requestId);
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase().contains("xml")) {
                throw new ImageDetectionException(ImageDetectionException.Code.PROTOCOL_ERROR, "识别响应异常", 1, requestId);
            }
            byte[] body = readBounded(connection.getInputStream());
            return parse(body, input.imageWidth(), input.imageHeight(), requestId);
        } catch (ImageDetectionException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            log.warn("CI主体检测超时");
            throw new ImageDetectionException(ImageDetectionException.Code.TIMEOUT, "识别服务超时", 1);
        } catch (IOException e) {
            log.warn("CI主体检测网络异常: {}", e.getClass().getSimpleName());
            throw new ImageDetectionException(ImageDetectionException.Code.PROVIDER_ERROR, "识别网络异常", 1);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private SignedRequest sign(DetectImageObjectsInput input, ImageDetectionConfig config) {
        ImageSource source = input.source();
        boolean direct = source.kind() == ImageSource.Kind.COS_OBJECT
                && "COS_OBJECT".equals(config.cosImageAccessMode());
        if (!direct) return signExternal(source, config);
        ClientConfig clientConfig = new ClientConfig(new Region(config.region()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        COSClient client = new COSClient(new BasicCOSCredentials(config.secretId(), config.secretKey()), clientConfig);
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(config.bucketName(),
                    source.key(), HttpMethodName.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + 60_000));
            request.addRequestParameter("ci-process", "AIObjectDetect");
            return new SignedRequest(client.generatePresignedUrl(request), null);
        } catch (ImageDetectionException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("生成CI请求签名失败: {}", e.getClass().getSimpleName());
            throw new ImageDetectionException(ImageDetectionException.Code.UNCONFIGURED, "图像检测未配置", 0);
        } finally {
            client.shutdown();
        }
    }

    /** 此 SDK 的预签名请求拒绝空对象键；根路径改用同一 SDK 的 COSSigner 授权头。 */
    private SignedRequest signExternal(ImageSource source, ImageDetectionConfig config) {
        try {
            String host = config.bucketName() + ".cos." + config.region() + ".myqcloud.com";
            Map<String, String> params = Map.of("ci-process", "AIObjectDetect", "detect-url", source.url());
            URL url = new URIBuilder().setScheme("https").setHost(host).setPath("/")
                    .addParameter("ci-process", "AIObjectDetect").addParameter("detect-url", source.url())
                    .build().toURL();
            String authorization = new COSSigner().buildAuthorizationStr(HttpMethodName.GET, "/",
                    Map.of("Host", host), params,
                    new BasicCOSCredentials(config.secretId(), config.secretKey()),
                    new Date(System.currentTimeMillis() + 60_000));
            return new SignedRequest(url, authorization);
        } catch (Exception e) {
            log.error("生成CI请求签名失败: {}", e.getClass().getSimpleName());
            throw new ImageDetectionException(ImageDetectionException.Code.UNCONFIGURED, "图像检测未配置", 0);
        }
    }

    private record SignedRequest(URL url, String authorization) {}

    private boolean sourceDownloadFailed(HttpURLConnection connection) {
        try {
            InputStream error = connection.getErrorStream();
            return error != null && new String(readBounded(error), StandardCharsets.UTF_8).contains("DownloadError");
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private byte[] readBounded(InputStream stream) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw new ImageDetectionException(ImageDetectionException.Code.PROTOCOL_ERROR, "识别响应过大", 1);
                }
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    DetectImageObjectsResult parse(byte[] xml, int imageWidth, int imageHeight, String requestId) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            Element root = document.getDocumentElement();
            if (root != null && "Error".equals(root.getTagName())) {
                String code = childText(root, "Code");
                if (code.contains("AccessDenied") || code.contains("Signature")) {
                    throw new ImageDetectionException(ImageDetectionException.Code.AUTH_ERROR, "识别授权失败", 1, requestId);
                }
                throw new ImageDetectionException(ImageDetectionException.Code.PROVIDER_ERROR, "识别服务异常", 1, requestId);
            }
            if (root == null || !"RecognitionResult".equals(root.getTagName())) {
                throw new IllegalArgumentException("root");
            }
            int status = Integer.parseInt(childText(root, "Status"));
            if (status != 0 && status != 1) throw new IllegalArgumentException("status");
            List<DetectedObject> objects = new ArrayList<>();
            int rawObjects = 0;
            NodeList nodes = root.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element item) || !"DetectMultiObj".equals(item.getTagName())) continue;
                rawObjects++;
                try {
                    String name = childText(item, "Name");
                    int confidence = Integer.parseInt(childText(item, "Confidence"));
                    Element location = child(item, "Location");
                    int x = Integer.parseInt(childText(location, "X"));
                    int y = Integer.parseInt(childText(location, "Y"));
                    int width = Integer.parseInt(childText(location, "Width"));
                    int height = Integer.parseInt(childText(location, "Height"));
                    if (name.isBlank() || confidence < 0 || confidence > 100 || x < 0 || y < 0
                            || width <= 0 || height <= 0 || (long) x + width > imageWidth
                            || (long) y + height > imageHeight) {
                        log.warn("CI主体检测包含无效矩形, requestId={}", requestId);
                        continue;
                    }
                    objects.add(new DetectedObject(name, confidence, new PixelBox(x, y, width, height)));
                } catch (RuntimeException e) {
                    log.warn("CI主体检测包含无效项目, requestId={}", requestId);
                }
            }
            if ((status == 0 && rawObjects != 0) || (status == 1 && objects.isEmpty())) {
                throw new IllegalArgumentException("status mismatch");
            }
            return new DetectImageObjectsResult("TENCENT_CI", status == 1, List.copyOf(objects), requestId);
        } catch (ImageDetectionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("CI主体检测协议异常, requestId={}", requestId);
            throw new ImageDetectionException(ImageDetectionException.Code.PROTOCOL_ERROR, "识别响应异常", 1, requestId);
        }
    }

    private Element child(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        }
        throw new IllegalArgumentException(name);
    }

    private String childText(Element parent, String name) { return child(parent, name).getTextContent().trim(); }
}
