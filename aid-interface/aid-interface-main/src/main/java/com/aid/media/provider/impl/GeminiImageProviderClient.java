package com.aid.media.provider.impl;


import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.util.StrUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.constant.HttpConstants;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.media.constants.GeminiConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.SubmitTimeoutResolver;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Google Gemini 图片生成 Provider：支持 gemini-3.1-flash-image-preview（Nano Banana 2）。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class GeminiImageProviderClient implements ImageProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 参考图下载字节上限，防止异常大文件导致 OOM。
     * 业务参考图一般 <10MB，这里设 20MB 上限兼顾安全与灵活性。
     */
    private static final long MAX_REFERENCE_IMAGE_BYTES = 20L * 1024L * 1024L;
    /**
     * 下载超时（毫秒）：连接 5s + 读取 30s，覆盖一般 CDN 场景。
     */
    private static final int IMAGE_DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000;
    private static final int IMAGE_DOWNLOAD_READ_TIMEOUT_MS = 30_000;

    /**
     * 共享 HttpClient，避免每次请求都创建新的连接池/Selector 线程。
     * Java 11 HttpClient 是线程安全的，官方建议单例复用。
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(GeminiConstants.IMAGE_HTTP_TIMEOUT_MINUTES))
            .build();
    /**
     * 专供参考图下载使用的共享 HttpClient：连接超时 5s，用独立实例避免干扰上游 API 调用。
     */
    private static final HttpClient SHARED_DOWNLOAD_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(IMAGE_DOWNLOAD_CONNECT_TIMEOUT_MS))
            .build();

    /**
     * Gemini 图片模型输出收口指令：
     * 业务侧部分模板仍带“生成提示词/单段纯文本”语义，这会把 Gemini 引导成返回文本而非图片。
     * 这里在 provider 层统一追加“直接出图、不要返回提示词文本”的强约束，避免改动整套业务模板。
     */
    private static final String DIRECT_IMAGE_OUTPUT_INSTRUCTION =
            "\n\n请直接生成最终图片，不要输出提示词文本、解释、说明、标题或任何纯文本回复。"
                    + "直接返回图片结果。";
    @Override
    public String protocol() {
        return GeminiConstants.PROTOCOL_IMAGE;
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) {
        return GEMINI_IMAGE_REFERENCE_MAX;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // Gemini 图片：按 provider_code 精确归属
        return providerCode != null
                && GeminiConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }
    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        ReferencePromptSanitizer.sanitizeInPlace(request,
                com.aid.media.provider.ReferenceImageLimiter.resolveMax(modelConfig, GEMINI_IMAGE_REFERENCE_MAX));
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            log.error("Gemini 图片提交失败: apiKey 为空");
            return ProviderSubmitResult.builder()
                    .rawResponse(GeminiConstants.ERROR_API_KEY_EMPTY)
                    .build();
        }

        String model = resolveEffectiveModel(modelConfig, request);
        String url = buildGenerateContentUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix(), model);

        Map<String, Object> body;
        try {
            body = buildRequestBody(modelConfig, request);
        } catch (Exception e) {
            log.error("Gemini 图片请求体构建失败, model={}, error={}", model, e.getMessage(), e);
            return ProviderSubmitResult.builder()
                    .rawResponse("请求构建失败: " + e.getMessage())
                    .build();
        }

        String json;
        try {
            json = MAPPER.writeValueAsString(com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, body, request));
        } catch (Exception e) {
            log.error("Gemini 图片请求体序列化失败", e);
            return ProviderSubmitResult.builder()
                    .rawResponse("序列化失败")
                    .build();
        }

        log.info("Gemini 图片提交, url={}, model={}, requestBodyLen={}", url, model, json.length());

        String respBody;
        try {
            // 单次 HTTP 超时按模型 capability_json.submitTimeoutSeconds 取值，缺省回退官方 5 分钟常量。
            int timeoutMs = SubmitTimeoutResolver.resolveMs(modelConfig,
                    GeminiConstants.IMAGE_HTTP_TIMEOUT_MINUTES * 60 * 1000);
            respBody = doPost(url, apiKey, json, timeoutMs);
        } catch (Exception e) {
            log.error("Gemini 图片提交网络异常, model={}", model, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(e.getMessage())
                    .build();
        }

        try {
            return parseImageResponse(respBody, model);
        } catch (Exception e) {
            String sanitized = sanitizeResponseForLog(respBody);
            log.error("Gemini 图片响应解析失败, model={}, fullResponse={}", model, sanitized, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(sanitized)
                    .build();
        }
    }

    /**
     * Gemini 图片生成是同步接口，不需要异步轮询。
     * 正常不会走到此方法；同步接口无官方任务状态。
     */
    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        return ProviderTaskResult.builder()
                .status("PROCESSING")
                .errorMessage("同步模型无上游查询状态")
                .querySuccessful(Boolean.FALSE)
                .terminalConfirmed(Boolean.FALSE)
                .build();
    }
    /** Gemini 图片参考图厂商默认上限（capability_json.maxReferenceImages 未配置时兜底） */
    private static final int GEMINI_IMAGE_REFERENCE_MAX = 9;

    /**
     * 组装 Gemini generateContent 请求体（图片生成）。
     */
    private Map<String, Object> buildRequestBody(AiModelConfigVo modelConfig,
                                                 MediaImageGenerateRequest request) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();

        // --- contents ---
        List<Map<String, Object>> parts = new ArrayList<>();
        // 文本 prompt
        String prompt = normalizePromptForImageOutput(request != null ? request.getPrompt() : null);
        if (StringUtils.isNotBlank(prompt)) {
            parts.add(Map.of("text", prompt));
        }
        // 参考图：优先 referenceImageUrl，再看 options.referenceImages；
        // 统一上限：读 capability_json.maxReferenceImages，缺省回退 9 张，超限直接拒绝。
        List<String> refImages = com.aid.media.provider.ReferenceImageLimiter.limit(
                resolveReferenceImages(request), modelConfig, GEMINI_IMAGE_REFERENCE_MAX, "Gemini图片");
        boolean hasRefImage = !refImages.isEmpty();
        for (String imgUrl : refImages) {
            Map<String, Object> inlinePart = buildInlineDataPart(imgUrl);
            if (inlinePart != null) {
                parts.add(inlinePart);
            }
        }
        log.info("Gemini 图片请求场景: {}, 参考图数量={}", hasRefImage ? "图生图" : "纯文生图", refImages.size());
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("请填提示词");
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", parts);
        body.put("contents", List.of(content));

        // --- generationConfig ---
        Map<String, Object> genConfig = new LinkedHashMap<>();
        // 官方文档统一使用 ["Text","Image"]，模型需要 Text 输出来完成思考/推理后再产出图片
        genConfig.put("responseModalities", List.of("Text", "Image"));
        // imageConfig：aspectRatio + imageSize
        Map<String, Object> imageConfig = buildImageConfig(request);
        if (!imageConfig.isEmpty()) {
            genConfig.put("imageConfig", imageConfig);
        }
        body.put("generationConfig", genConfig);

        return body;
    }

    /**
     * 构建 imageConfig 节点：aspectRatio + imageSize。
     */
    private Map<String, Object> buildImageConfig(MediaImageGenerateRequest request) {
        Map<String, Object> imageConfig = new LinkedHashMap<>();
        if (request == null) {
            return imageConfig;
        }
        Map<String, Object> options = request.getOptions();

        // aspectRatio：优先 options.aspect_ratio
        String aspectRatio = getStringOption(options, "aspect_ratio");
        if (StringUtils.isBlank(aspectRatio)) {
            aspectRatio = getStringOption(options, "aspectRatio");
        }
        // 从 size 推断 aspectRatio（如 "1024*1024" → "1:1"）
        if (StringUtils.isBlank(aspectRatio) && StringUtils.isNotBlank(request.getSize())) {
            aspectRatio = inferAspectRatioFromSize(request.getSize());
        }
        if (StringUtils.isNotBlank(aspectRatio)) {
            imageConfig.put("aspectRatio", aspectRatio);
        }

        // imageSize：优先 options.imageSize / options.image_size
        String imageSize = getStringOption(options, "imageSize");
        if (StringUtils.isBlank(imageSize)) {
            imageSize = getStringOption(options, "image_size");
        }
        // 从 size 字段推断（"1K" / "2K" / "4K" 直传）
        if (StringUtils.isBlank(imageSize) && StringUtils.isNotBlank(request.getSize())) {
            imageSize = inferImageSizeFromSize(request.getSize());
        }
        if (StringUtils.isNotBlank(imageSize)) {
            imageConfig.put("imageSize", imageSize);
        }

        return imageConfig;
    }

    /**
     * 将业务 prompt 收口为“直接出图”语义，避免 Gemini 把请求理解成“帮我生成提示词文本”。
     */
    private String normalizePromptForImageOutput(String prompt) {
        if (StringUtils.isBlank(prompt)) {
            return prompt;
        }
        if (prompt.contains(DIRECT_IMAGE_OUTPUT_INSTRUCTION.trim())) {
            return prompt;
        }
        return prompt + DIRECT_IMAGE_OUTPUT_INSTRUCTION;
    }

    /**
     * 合并所有参考图 URL：referenceImageUrl + options.referenceImages + options.images。
     */
    private List<String> resolveReferenceImages(MediaImageGenerateRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        // 单图字段
        if (StringUtils.isNotBlank(request.getReferenceImageUrl())) {
            result.add(request.getReferenceImageUrl());
        }
        // options 多图字段
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            addUrlsFromOption(result, options.get("referenceImages"));
            addUrlsFromOption(result, options.get("images"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void addUrlsFromOption(List<String> target, Object value) {
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item instanceof String && StringUtils.isNotBlank((String) item)) {
                    target.add((String) item);
                }
            }
        }
    }

    /**
     * 将图片 URL 下载并转换为 Gemini inlineData part。
     * <pre>
     * { "inlineData": { "mimeType": "image/jpeg", "data": "base64..." } }
     * </pre>
     */
    private Map<String, Object> buildInlineDataPart(String imageUrl) {
        try {
            // 带字节上限的下载，替代无限制的 HttpUtil.downloadBytes
            byte[] bytes = downloadBytesWithLimit(imageUrl, MAX_REFERENCE_IMAGE_BYTES);
            if (bytes == null || bytes.length == 0) {
                log.warn("Gemini 参考图下载为空, url={}", imageUrl);
                return null;
            }
            // 优先用文件魔数字节识别 MIME，不再仅靠 URL 后缀猜测
            String mimeType = inferMimeTypeByMagicBytes(bytes);
            if (mimeType == null) {
                // 字节无法识别时回退到 URL 后缀推断
                mimeType = inferMimeType(imageUrl);
            }
            log.info("Gemini 参考图下载完成, url={}, bytes={}, mimeType={}", imageUrl, bytes.length, mimeType);
            String base64Data = Base64.getEncoder().encodeToString(bytes);
            Map<String, Object> inlineData = new LinkedHashMap<>();
            inlineData.put("mimeType", mimeType);
            inlineData.put("data", base64Data);
            return Map.of("inlineData", inlineData);
        } catch (Exception e) {
            log.error("Gemini 参考图下载失败, url={}, error={}", imageUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 带字节上限的图片下载。
     * 使用 java.net.http.HttpClient 以流式方式读取，累计字节数超过 maxBytes 立即终止，
     * 避免下载无限大文件导致 OOM。
     */
    private byte[] downloadBytesWithLimit(String imageUrl, long maxBytes) throws IOException, InterruptedException {
        // 复用共享 HttpClient
        HttpClient client = SHARED_DOWNLOAD_CLIENT;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofMillis(IMAGE_DOWNLOAD_READ_TIMEOUT_MS))
                .GET()
                .build();
        HttpResponse<InputStream> resp = com.aid.diagnostics.DiagnosticHttp.send(client, req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        // 若响应带 Content-Length 且明确超限，直接拒绝
        resp.headers().firstValue("Content-Length").ifPresent(cl -> {
            try {
                long len = Long.parseLong(cl);
                if (len > maxBytes) {
                    throw new RuntimeException("参考图过大: " + len + " bytes, limit=" + maxBytes);
                }
            } catch (NumberFormatException ignore) {
                // 忽略非法 Content-Length
            }
        });
        try (InputStream in = resp.body();
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("参考图超过大小上限 " + maxBytes + " bytes");
                }
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    /**
     * 根据文件头魔数字节识别图片 MIME 类型。
     * 返回 null 表示无法识别，调用方应回退到 URL 后缀推断。
     */
    private String inferMimeTypeByMagicBytes(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 (\x89PNG)
        if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "image/png";
        }
        // GIF: 47 49 46 (GIF)
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46) {
            return "image/gif";
        }
        // WebP: RIFF....WEBP
        if (data.length >= 12
                && data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46
                && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) {
            return "image/webp";
        }
        // BMP: 42 4D
        if (data[0] == 0x42 && data[1] == 0x4D) {
            return "image/bmp";
        }
        return null;
    }
    /**
     * 解析 Gemini generateContent 响应：
     * - candidates[0].content.parts[*].text → 日志记录模型推理文本
     * - candidates[0].content.parts[*].inlineData → base64 图片 → 逐张解码上传 OSS → 返回 ossUrl + resultUrls
     */
    private ProviderSubmitResult parseImageResponse(String respBody, String model) throws Exception {
        JsonNode root = MAPPER.readTree(respBody);
        // 响应体瘦身：保留完整 JSON 结构，但把 inlineData.data 大段 base64 替换为占位符。
        // 同时用于日志和 rawResponse（写库），避免超大 base64 导致 MySQL PacketTooBigException。
        String logResponse = sanitizeResponseForLog(root);

        // 检查错误
        JsonNode errorNode = root.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String errMsg = errorNode.path("message").asText("未知错误");
            log.error("Gemini 图片生成上游错误, model={}, error={}, fullResponse={}", model, errMsg, logResponse);
            return ProviderSubmitResult.builder()
                    .rawResponse(logResponse)
                    .build();
        }

        // 检查 finishReason=NO_IMAGE（上游未生成图片，通常为请求字段问题）
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            String finishReason = candidates.get(0).path("finishReason").asText("");
            if ("NO_IMAGE".equalsIgnoreCase(finishReason)) {
                // 打印全量诊断信息：响应、请求体关键字段、usageMetadata
                JsonNode usageMeta = root.path("usageMetadata");
                log.error("Gemini 图片生成返回 NO_IMAGE, model={}, finishReason={}, "
                                + "usageMetadata={}, fullResponse={}",
                        model, finishReason,
                        usageMeta.isMissingNode() ? "N/A" : usageMeta.toString(),
                        logResponse);
                return ProviderSubmitResult.builder()
                        .rawResponse(logResponse)
                        .build();
            }
        }

        // 遍历 parts：Text 部分记录日志，inlineData 部分提取图片
        List<String> ossUrls = new ArrayList<>();
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    // 记录模型返回的文本推理内容（responseModalities 包含 Text 时会有）
                    JsonNode textNode = part.path("text");
                    if (!textNode.isMissingNode() && StringUtils.isNotBlank(textNode.asText())) {
                        log.info("Gemini 图片模型返回文本, textLen={}", textNode.asText().length());
                    }
                    JsonNode inlineData = part.path("inlineData");
                    if (inlineData.isMissingNode() || inlineData.isNull()) {
                        continue;
                    }
                    String mimeType = inlineData.path("mimeType").asText("image/png");
                    String base64Data = inlineData.path("data").asText(null);
                    if (StringUtils.isBlank(base64Data)) {
                        continue;
                    }
                    // 解码并上传 OSS
                    String ossUrl = uploadBase64ToOss(base64Data, mimeType);
                    if (StringUtils.isNotBlank(ossUrl)) {
                        ossUrls.add(ossUrl);
                    }
                }
            }
        }

        if (ossUrls.isEmpty()) {
            log.error("Gemini 图片生成未返回图片, model={}, fullResponse={}",
                    model, logResponse);
            return ProviderSubmitResult.builder()
                    .rawResponse(logResponse)
                    .build();
        }

        // 解析 usageMetadata（Gemini 图片 token 用量）
        Map<String, Object> usage = parseUsageMetadata(root);
        log.info("Gemini 图片生成成功, model={}, imageCount={}, prompt_tokens={}, completion_tokens={}, "
                        + "input_tokens={}, output_tokens={}, total_tokens={}, usageSource=PROVIDER_REAL_USAGE",
                model, ossUrls.size(),
                usage.get("prompt_tokens"), usage.get("completion_tokens"),
                usage.get("input_tokens"), usage.get("output_tokens"),
                usage.get("total_tokens"));
        // Base64 只在内存中解码上传，任务仅保存系统 OSS URL，不设 directUrl（无上游可下载 URL）。
        return ProviderSubmitResult.builder()
                .ossUrl(ossUrls.get(0))
                .resultUrls(ossUrls)
                .resultCount(ossUrls.size())
                .rawResponse(logResponse)
                .usage(usage)
                .build();
    }

    /**
     * 将 base64 图片数据解码后上传到 OSS，返回可访问 URL。
     */
    private String uploadBase64ToOss(String base64Data, String mimeType) {
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            if (imageBytes.length == 0) {
                return null;
            }
            // 根据 mimeType 决定后缀
            String suffix = mimeTypeToSuffix(mimeType);
            UploadResult uploadResult = OssFactory.instance().uploadSuffix(imageBytes, suffix, mimeType);
            return uploadResult.getUrl();
        } catch (Exception e) {
            log.error("Gemini 图片 OSS 上传失败, mimeType={}, error={}", mimeType, e.getMessage(), e);
            return null;
        }
    }
    /**
     * 发起 Gemini REST POST 请求，返回响应正文。
     */
    private String doPost(String url, String apiKey, String jsonBody, int timeoutMs) throws Exception {
        // 复用共享 HttpClient
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header(GeminiConstants.HEADER_API_KEY, apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(com.aid.diagnostics.DiagnosticCapture.outboundBody(jsonBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp;
        try {
            resp = com.aid.diagnostics.DiagnosticHttp.send(client, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("请求被中断", ie);
        }
        String body = resp.body() != null ? resp.body() : "";
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.error("Gemini 图片 HTTP 失败, url={}, status={}, bodyLen={}", url, resp.statusCode(), body.length());
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return body;
    }
    /** 构建包含受控 {model} 占位符的 Gemini 提交 URL。 */
    static String buildGenerateContentUrl(String baseUrl, String apiSuffix, String model) {
        if (StringUtils.isBlank(baseUrl)) {
            log.error("Gemini 图片 baseUrl 为空，请在 aid_ai_provider 表配置 base_url");
            throw new IllegalArgumentException("配置缺失");
        }
        if (StringUtils.isBlank(apiSuffix)) {
            log.error("Gemini 图片 apiSuffix 为空，请在 aid_ai_model 表配置 api_suffix");
            throw new IllegalArgumentException("配置缺失");
        }
        String suffix = apiSuffix.trim();
        if (!suffix.contains("{model}")) {
            if (suffix.endsWith("/")) {
                suffix = suffix + "{model}" + GeminiConstants.OPERATION_GENERATE_CONTENT;
            } else {
                return ProviderEndpointUtils.buildSubmitUrl(baseUrl, suffix);
            }
        }
        return ProviderEndpointUtils.buildModelSubmitUrl(baseUrl, suffix, model);
    }

    /**
     * 解析最终模型名：经 ModelCodeResolver 解析（real_model_code 解耦展示码），最后兜底常量。
     */
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        // 解析真实上游模型名：展示码 model_code 与真实模型名 real_model_code 解耦
        String resolved = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        if (StringUtils.isNotBlank(resolved)) {
            return resolved;
        }
        return GeminiConstants.MODEL_FLASH_IMAGE;
    }

    /**
     * 从 size 字段推断 Gemini imageSize。
     * 平台 size 可能是 "1K" / "2K" / "4K"（直传）或 "1024*1024" 等（忽略）。
     */
    private String inferImageSizeFromSize(String size) {
        if (StringUtils.isBlank(size)) {
            return null;
        }
        String s = size.trim().toUpperCase();
        // 官方 imageSize 合法档位为 512（无 K 后缀）/ 1K / 2K / 4K，512 仅 3.1 Flash Image 支持
        if ("512".equals(s) || "1K".equals(s) || "2K".equals(s) || "4K".equals(s)) {
            return s;
        }
        return null;
    }

    /**
     * 从 size 字段推断 aspectRatio（如 "1024*1024" → "1:1"，"1920*1080" → "16:9"）。
     * 若 size 是 "1K"/"2K"/"4K" 档位则不推断 ratio。
     */
    private String inferAspectRatioFromSize(String size) {
        if (StringUtils.isBlank(size)) {
            return null;
        }
        // 跳过 1K/2K/4K 档位
        String s = size.trim().toUpperCase();
        if ("1K".equals(s) || "2K".equals(s) || "4K".equals(s)) {
            return null;
        }
        // 尝试解析 WxH 格式
        String[] dims = size.split("[*xX×]");
        if (dims.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(dims[0].trim());
            int h = Integer.parseInt(dims[1].trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            int gcd = gcd(w, h);
            return (w / gcd) + ":" + (h / gcd);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    /**
     * 从 URL 推断 MIME 类型。
     */
    private String inferMimeType(String url) {
        if (StringUtils.isBlank(url)) {
            return "image/png";
        }
        String lower = url.toLowerCase();
        if (lower.contains(".jpg") || lower.contains(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.contains(".webp")) {
            return "image/webp";
        }
        if (lower.contains(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    /**
     * 根据 MIME 类型返回文件后缀。
     */
    private String mimeTypeToSuffix(String mimeType) {
        if (StringUtils.isBlank(mimeType)) {
            return GeminiConstants.IMAGE_SUFFIX_PNG;
        }
        String lower = mimeType.toLowerCase();
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return ".jpg";
        }
        if (lower.contains("webp")) {
            return ".webp";
        }
        if (lower.contains("gif")) {
            return ".gif";
        }
        return GeminiConstants.IMAGE_SUFFIX_PNG;
    }

    /**
     * 安全读取 options 中的 String 值。
     */
    private String getStringOption(Map<String, Object> options, String key) {
        if (options == null || key == null) {
            return null;
        }
        Object val = options.get(key);
        if (val instanceof String) {
            return (String) val;
        }
        return val != null ? String.valueOf(val) : null;
    }
    /**
     * 从 Gemini 响应根节点解析 usageMetadata，返回统一口径 usage Map。
     * 字段映射：promptTokenCount→prompt_tokens/input_tokens，
     *          candidatesTokenCount→completion_tokens/output_tokens，
     *          totalTokenCount→total_tokens。
     */
    private Map<String, Object> parseUsageMetadata(JsonNode root) {
        Map<String, Object> usage = new LinkedHashMap<>();
        JsonNode meta = root.path("usageMetadata");
        if (meta.isMissingNode() || meta.isNull()) {
            return usage;
        }
        int promptTokens = meta.path("promptTokenCount").asInt(0);
        int candidatesTokens = meta.path("candidatesTokenCount").asInt(0);
        int totalTokens = meta.path("totalTokenCount").asInt(0);
        // 兜底：上游未返回 totalTokenCount 时按输入+输出累加
        if (totalTokens <= 0 && (promptTokens > 0 || candidatesTokens > 0)) {
            totalTokens = promptTokens + candidatesTokens;
        }
        // Gemini 原始语义
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", candidatesTokens);
        // 统一口径（与文本 LLM onUsage 保持一致）
        usage.put("input_tokens", promptTokens);
        usage.put("output_tokens", candidatesTokens);
        usage.put("total_tokens", totalTokens);
        return usage;
    }
    /**
     * 日志专用响应体：deep-copy JSON 后将 inlineData.data 替换为长度占位符，
     * 保留完整 JSON 结构和普通字段，避免大段 base64 冲爆日志。
     */
    private String sanitizeResponseForLog(JsonNode root) {
        try {
            JsonNode copy = root.deepCopy();
            JsonNode candidates = copy.path("candidates");
            if (candidates.isArray()) {
                for (JsonNode candidate : candidates) {
                    JsonNode parts = candidate.path("content").path("parts");
                    if (parts.isArray()) {
                        for (JsonNode part : parts) {
                            JsonNode inlineData = part.path("inlineData");
                            if (inlineData.isMissingNode() || inlineData.isNull()) {
                                continue;
                            }
                            if (inlineData.isObject() && inlineData.has("data")) {
                                String dataVal = inlineData.path("data").asText("");
                                ((com.fasterxml.jackson.databind.node.ObjectNode) inlineData)
                                        .put("data", "<base64 omitted, length=" + dataVal.length() + ">");
                            }
                        }
                    }
                }
            }
            return MAPPER.writeValueAsString(copy);
        } catch (Exception e) {
            // 脱敏失败则返回原始文本长度提示，不再尝试打印原文
            return "<sanitize failed, rawLength=" + (root != null ? root.toString().length() : 0) + ">";
        }
    }

    /**
     * 从原始响应字符串构建日志专用响应体（解析异常等场景，root 不可用时使用）。
     */
    private String sanitizeResponseForLog(String respBody) {
        if (StringUtils.isBlank(respBody)) {
            return respBody;
        }
        try {
            JsonNode root = MAPPER.readTree(respBody);
            return sanitizeResponseForLog(root);
        } catch (Exception e) {
            // JSON 解析失败说明不是合法 JSON，直接返回原文
            return respBody;
        }
    }
}
