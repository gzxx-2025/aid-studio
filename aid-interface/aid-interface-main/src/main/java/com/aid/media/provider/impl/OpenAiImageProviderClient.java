package com.aid.media.provider.impl;


import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.constant.HttpConstants;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.OpenAiImageConstants;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ImageEditAssetSupport;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.SubmitTimeoutResolver;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * OpenAI GPT Image 图片生成 Provider（gpt-image-2 等），严格遵循官方 Images API。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class OpenAiImageProviderClient implements ImageProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPERATION_PLACEHOLDER = "{operation}";
    private static final String OPERATION_GENERATIONS = "generations";
    private static final String OPERATION_EDITS = "edits";
    private static final int MAX_MULTIPART_BYTES = 110 * 1024 * 1024;

    @Override
    public String protocol() {
        return OpenAiImageConstants.PROTOCOL_IMAGE;
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) {
        return OpenAiImageConstants.MAX_REFERENCE_IMAGES;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return providerCode != null
                && OpenAiImageConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }

    @Override
    public void validateRequest(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        String model = resolveEffectiveModel(modelConfig, request);
        validateGpt25Size(model, resolveSize(model, request));
        validateGpt25Quality(model,
                getStringOption(request == null ? null : request.getOptions(), "quality"));
        resolveImageCount(request, modelConfig);
    }
    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            log.error("OpenAI 图片提交失败: apiKey 为空, modelCode={}",
                    modelConfig == null ? null : modelConfig.getModelCode());
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_API_KEY_EMPTY).build();
        }
        String model = resolveEffectiveModel(modelConfig, request);
        List<String> images = resolveReferenceImages(request, modelConfig);
        // 清洗排在参考图定型之后：正文的「图片N」编号必须与真正下发的张数一一对应，
        // 数量已通过能力校验，这里只负责规范化占位编号，不丢弃素材。
        ReferencePromptSanitizer.sanitizeInPlace(request, images.size());
        boolean edit = !images.isEmpty();

        Map<String, Object> body = buildRequestBody(model, request, images, edit, modelConfig);
        body = com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, body, request);
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            log.error("OpenAI 图片请求体序列化失败, model={}", model, e);
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_SERIALIZE).build();
        }

        String url;
        try {
            url = buildApiUrl(modelConfig, edit);
        } catch (IllegalArgumentException e) {
            log.error("OpenAI 图片路径无效, modelCode={}, reason={}", modelConfig.getModelCode(), e.getMessage());
            return ProviderSubmitResult.builder().rawResponse("模型路径无效").build();
        }
        log.info("OpenAI 图片提交, url={}, model={}, edit={}, refImageCount={}, n={}, size={}, quality={}, promptLen={}",
                url, model, edit, images.size(), body.get(OpenAiImageConstants.JSON_N),
                body.get(OpenAiImageConstants.JSON_SIZE), body.get(OpenAiImageConstants.JSON_QUALITY),
                StringUtils.length(request == null ? null : request.getPrompt()));

        String respBody;
        try {
            // 单次 HTTP 超时按模型 capability_json.submitTimeoutSeconds 取值，缺省回退官方 300s 常量。
            int timeoutMs = SubmitTimeoutResolver.resolveMs(modelConfig, OpenAiImageConstants.IMAGE_TIMEOUT_MS);
            respBody = edit && useMultipartEdits(modelConfig, model)
                    ? doMultipart(url, apiKey, modelConfig.getAuthHeader(), modelConfig.getAuthPrefix(),
                            body, images, request, timeoutMs)
                    : doPost(url, apiKey, modelConfig.getAuthHeader(), modelConfig.getAuthPrefix(), json, timeoutMs);
        } catch (Exception e) {
            log.error("OpenAI 图片提交网络异常, model={}, error={}", model, e.getMessage(), e);
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }

        try {
            // 按请求的 output_format 推导 OSS 落库后缀/MIME（缺省 png），保证文件后缀与实际编码一致
            String outputFormat = getStringOption(request == null ? null : request.getOptions(), "output_format");
            return parseImageResponse(respBody, model, outputFormat, request);
        } catch (Exception e) {
            log.error("OpenAI 图片响应解析失败, model={}", model, e);
            return ProviderSubmitResult.builder()
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }
    }

    /**
     * OpenAI 图片生成为同步接口，不存在可供轮询的官方任务状态。
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
    /**
     * 组装官方 JSON 请求体。
     * <pre>
     * 文生图： { "model":"gpt-image-2", "prompt":"...", "n":1, "size":"1024x1024", "quality":"high" }
     * 图生图： { ...同上..., "images":[ {"image_url":"https://.../a.png"}, ... ] }
     * </pre>
     * 不传 seed（官方无此入参）。size/quality 仅在解析出有效值时下发，否则交由上游默认。
     */
    private Map<String, Object> buildRequestBody(String model, MediaImageGenerateRequest request,
                                                 List<String> images, boolean edit, AiModelConfigVo modelConfig) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(OpenAiImageConstants.JSON_MODEL, model);
        body.put(OpenAiImageConstants.JSON_PROMPT, request == null ? "" : StringUtils.defaultString(request.getPrompt()));
        body.put(OpenAiImageConstants.JSON_N, resolveImageCount(request, modelConfig));

        String size = resolveSize(model, request);
        validateGpt25Size(model, size);
        if (StringUtils.isNotBlank(size)) {
            body.put(OpenAiImageConstants.JSON_SIZE, size);
        }
        String quality = getStringOption(request == null ? null : request.getOptions(), "quality");
        if (StringUtils.isNotBlank(quality)) {
            validateGpt25Quality(model, quality);
            body.put(OpenAiImageConstants.JSON_QUALITY, quality.trim());
        }
        // 可选：背景 / 输出格式（仅在业务显式指定时下发）
        String background = getStringOption(request == null ? null : request.getOptions(), "background");
        if (StringUtils.isNotBlank(background)) {
            String normalizedBackground = background.trim().toLowerCase();
            if (!java.util.Set.of("auto", "opaque", "transparent").contains(normalizedBackground)) {
                throw new ServiceException("图片背景参数无效");
            }
            body.put(OpenAiImageConstants.JSON_BACKGROUND, normalizedBackground);
            background = normalizedBackground;
        }
        // 输出格式允许 png / jpeg / webp（jpg 归一为 jpeg），并与落库后缀、MIME 保持一致。
        String outputFormat = getStringOption(request == null ? null : request.getOptions(), "output_format");
        if (StringUtils.isNotBlank(outputFormat)) {
            String fmt = outputFormat.trim().toLowerCase();
            if (OpenAiImageConstants.OUTPUT_FORMAT_JPEG.equals(fmt) || OpenAiImageConstants.OUTPUT_FORMAT_JPG.equals(fmt)) {
                body.put(OpenAiImageConstants.JSON_OUTPUT_FORMAT, OpenAiImageConstants.OUTPUT_FORMAT_JPEG);
            } else if (OpenAiImageConstants.OUTPUT_FORMAT_PNG.equals(fmt)
                    || OpenAiImageConstants.OUTPUT_FORMAT_WEBP.equals(fmt)) {
                body.put(OpenAiImageConstants.JSON_OUTPUT_FORMAT, fmt);
            } else {
                throw new ServiceException("图片输出格式无效");
            }
        }
        Integer outputCompression = getIntegerOption(request == null ? null : request.getOptions(),
                OpenAiImageConstants.JSON_OUTPUT_COMPRESSION);
        if (outputCompression != null) {
            if (outputCompression < 0 || outputCompression > 100) {
                throw new ServiceException("输出压缩参数无效");
            }
            body.put(OpenAiImageConstants.JSON_OUTPUT_COMPRESSION, outputCompression);
        }
        String moderation = getStringOption(request == null ? null : request.getOptions(),
                OpenAiImageConstants.JSON_MODERATION);
        if (StringUtils.isNotBlank(moderation)) {
            String normalizedModeration = moderation.trim().toLowerCase();
            if (!java.util.Set.of("auto", "low").contains(normalizedModeration)) {
                throw new ServiceException("内容审核参数无效");
            }
            body.put(OpenAiImageConstants.JSON_MODERATION, normalizedModeration);
        }
        if (ImageEditAssetSupport.isInpainting(request) || ImageEditAssetSupport.isOutpainting(request)) {
            body.put(OpenAiImageConstants.JSON_OUTPUT_FORMAT, OpenAiImageConstants.OUTPUT_FORMAT_PNG);
            body.remove(OpenAiImageConstants.JSON_OUTPUT_COMPRESSION);
        }
        Object effectiveFormat = body.get(OpenAiImageConstants.JSON_OUTPUT_FORMAT);
        if (body.containsKey(OpenAiImageConstants.JSON_OUTPUT_COMPRESSION)
                && (effectiveFormat == null || OpenAiImageConstants.OUTPUT_FORMAT_PNG.equals(effectiveFormat))) {
            throw new ServiceException("PNG 不支持输出压缩参数");
        }
        if ("transparent".equalsIgnoreCase(background)
                && OpenAiImageConstants.OUTPUT_FORMAT_JPEG.equals(effectiveFormat)) {
            throw new ServiceException("透明背景不支持JPEG");
        }

        // 图生图：参考图 images[].image_url，官方支持「完整 URL 或 base64 data URL」。
        // 默认走远程 URL（体积小、由上游回源）；当模型 capability 开启 base64 传图时（网关无法回源业务 CDN 的场景），
        // 下载转 data URI 内联下发，规避上游"下载图片 404"。
        if (edit) {
            List<String> effectiveImages = images;
            if (com.aid.media.provider.ReferenceImageBase64Support.isBase64Enabled(modelConfig)) {
                effectiveImages = com.aid.media.provider.ReferenceImageBase64Support.toDataUris(images);
                log.info("gpt-image 参考图按 base64 内联下发, model={}, count={}", model, effectiveImages.size());
            }
            List<Map<String, Object>> imageRefs = new ArrayList<>();
            for (String imgUrl : effectiveImages) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put(OpenAiImageConstants.JSON_IMAGE_URL, imgUrl);
                imageRefs.add(ref);
            }
            body.put(OpenAiImageConstants.JSON_IMAGES, imageRefs);
        }
        return body;
    }

    /**
     * 合并参考图 URL：referenceImageUrl + options.referenceImages + options.images，
     * 统一读 capability_json.maxReferenceImages 校验（缺省回退官方 16 张）。
     */
    private List<String> resolveReferenceImages(MediaImageGenerateRequest request, AiModelConfigVo modelConfig) {
        if (request == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getReferenceImageUrl())) {
            result.add(request.getReferenceImageUrl());
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            addUrls(result, options.get("referenceImages"));
            addUrls(result, options.get("images"));
        }
        return ReferenceImageLimiter.limit(result, modelConfig, OpenAiImageConstants.MAX_REFERENCE_IMAGES, "OpenAI");
    }

    @SuppressWarnings("unchecked")
    private void addUrls(List<String> target, Object value) {
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item instanceof String && StringUtils.isNotBlank((String) item) && !target.contains(item)) {
                    target.add((String) item);
                }
            }
        }
    }

    /**
     * 解析下发上游的出图张数 n。
     */
    private int resolveImageCount(MediaImageGenerateRequest request, AiModelConfigVo modelConfig) {
        Integer n = request == null ? null : request.getExpectedImageCount();
        int count = (n == null || n < 1) ? 1 : n;
        // 模型配置上限优先（max_output_count>0 时生效）
        Integer configuredMax = modelConfig == null ? null : modelConfig.getMaxOutputCount();
        if (configuredMax != null && configuredMax > 0) {
            if (count > configuredMax) {
                log.info("OpenAI 图片输出数量超过模型配置: max={}, actual={}", configuredMax, count);
                throw new ServiceException("生成图片数量超限");
            }
        }
        // 官方硬上限兜底
        if (count > OpenAiImageConstants.MAX_IMAGE_COUNT) {
            log.info("OpenAI 图片输出数量超过协议上限: max={}, actual={}",
                    OpenAiImageConstants.MAX_IMAGE_COUNT, count);
            throw new ServiceException("生成图片数量超限");
        }
        return count;
    }

    /**
     * 解析输出尺寸为官方 "宽x高" 字符串或 "auto"。
     */
    private String resolveSize(String model, MediaImageGenerateRequest request) {
        if (request == null) {
            return OpenAiImageConstants.DEFAULT_IMAGE_SIZE;
        }
        Map<String, Object> options = request.getOptions();
        String optSize = getStringOption(options, "size");
        String ratio = getStringOption(options, "aspect_ratio");
        if (StringUtils.isBlank(ratio)) {
            ratio = getStringOption(options, "aspectRatio");
        }
        if (ImageEditAssetSupport.isOutpainting(request)) {
            String target = request.getTargetWidth() + "x" + request.getTargetHeight();
            String explicit = normalizeSize(optSize);
            if (StringUtils.isNotBlank(optSize) && explicit == null) {
                throw new ServiceException("图片尺寸无效");
            }
            if (explicit == null) {
                explicit = normalizeSize(request.getSize());
                if (StringUtils.isNotBlank(request.getSize()) && explicit == null) {
                    throw new ServiceException("图片尺寸无效");
                }
            }
            if (explicit != null && !target.equals(explicit)) {
                throw new ServiceException("扩图目标尺寸与输出尺寸冲突");
            }
            if (StringUtils.isNotBlank(ratio) && !matchesAspectRatio(target, ratio)) {
                throw new ServiceException("扩图目标尺寸与画幅比例冲突");
            }
            return target;
        }
        String byRatio = sizeFromAspectRatio(ratio);
        String normalized = normalizeSize(optSize);
        if (isGpt25(model) && StringUtils.isNotBlank(optSize)
                && !"auto".equalsIgnoreCase(optSize.trim()) && normalized == null) {
            throw new ServiceException("图片尺寸无效");
        }
        if (normalized == null) {
            normalized = normalizeSize(request.getSize());
            if (isGpt25(model) && StringUtils.isNotBlank(request.getSize())
                    && !"auto".equalsIgnoreCase(request.getSize().trim()) && normalized == null) {
                throw new ServiceException("图片尺寸无效");
            }
        }
        if (byRatio != null) {
            // GPT Image 协议没有独立比例字段。显式尺寸与比例一致时保留用户尺寸（例如 3840x2160 + 16:9）；
            // 二者冲突时以比例意图为准映射官方合法尺寸，避免比例被默认/旧尺寸静默覆盖。
            return normalized != null && matchesAspectRatio(normalized, ratio) ? normalized : byRatio;
        }
        if ((StringUtils.isNotBlank(optSize) && "auto".equalsIgnoreCase(optSize.trim()))
                || (StringUtils.isBlank(optSize) && StringUtils.isNotBlank(request.getSize())
                && "auto".equalsIgnoreCase(request.getSize().trim()))) {
            return "auto";
        }
        if (normalized != null) {
            return normalized;
        }
        return OpenAiImageConstants.DEFAULT_IMAGE_SIZE;
    }

    /** 判断显式宽高是否与目标比例一致，容忍标准显示分辨率的舍入误差。 */
    private boolean matchesAspectRatio(String size, String ratio) {
        if (StringUtils.isBlank(size) || StringUtils.isBlank(ratio)) {
            return false;
        }
        String[] dims = size.split("x");
        String[] ratioParts = ratio.trim().split(":");
        if (dims.length != 2 || ratioParts.length != 2) {
            return false;
        }
        try {
            double actual = Double.parseDouble(dims[0]) / Double.parseDouble(dims[1]);
            double expected = Double.parseDouble(ratioParts[0]) / Double.parseDouble(ratioParts[1]);
            return Math.abs(actual - expected) <= 0.01D;
        } catch (NumberFormatException | ArithmeticException ex) {
            return false;
        }
    }

    /** 把 "1024*1024"/"1024x1024"/"1024×1024" 规范化为 "1024x1024"；非法或档位返回 null。 */
    private String normalizeSize(String size) {
        if (StringUtils.isBlank(size)) {
            return null;
        }
        String[] dims = size.trim().split("[*xX×]");
        if (dims.length != 2) {
            return null;
        }
        try {
            int w = Integer.parseInt(dims[0].trim());
            int h = Integer.parseInt(dims[1].trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            return w + "x" + h;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 由常见比例推断官方合法尺寸（宽高均被 16 整除、比例合规、总像素在限制内），未知返回 null。
     * gpt-image-2 接受任意满足约束的分辨率：1:1→1024x1024、3:2→1536x1024、2:3→1024x1536、
     * 16:9→1536x864、9:16→864x1536。
     */
    private String sizeFromAspectRatio(String ratio) {
        if (StringUtils.isBlank(ratio)) {
            return null;
        }
        switch (ratio.trim()) {
            case "1:1":
                return "1024x1024";
            case "3:2":
                return "1536x1024";
            case "2:3":
                return "1024x1536";
            case "16:9":
                return "1536x864";
            case "9:16":
                return "864x1536";
            default:
                return null;
        }
    }
    /**
     * 解析官方 Images 响应：{@code { "data": [ { "b64_json": "...", "url": "..." } ] }}。
     * b64_json 优先（解码落 OSS）；GPT image 恒回 b64，url 仅作网关变体兜底。
     */
    private ProviderSubmitResult parseImageResponse(String respBody, String model, String outputFormat,
                                                    MediaImageGenerateRequest request) throws Exception {
        if (StringUtils.isBlank(respBody)) {
            log.error("OpenAI 图片响应为空, model={}", model);
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_NO_IMAGE).build();
        }
        JsonNode root = MAPPER.readTree(respBody);

        // 上游错误透传（OpenAI 风格 error.message）
        JsonNode errorNode = root.path(OpenAiImageConstants.JSON_ERROR);
        if (errorNode.isObject() && !errorNode.isNull()) {
            String errMsg = errorNode.path(OpenAiImageConstants.JSON_MESSAGE).asText("未知错误");
            log.error("OpenAI 图片上游错误, model={}, error={}", model, errMsg);
            return ProviderSubmitResult.builder()
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX)).build();
        }

        JsonNode data = root.path(OpenAiImageConstants.JSON_DATA);
        if (!data.isArray() || data.isEmpty()) {
            log.error("OpenAI 图片未返回 data, model={}, responseLen={}", model, respBody.length());
            return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_NO_IMAGE).build();
        }

        List<String> ossUrls = new ArrayList<>();    // b64 落库模式
        List<String> directUrls = new ArrayList<>(); // url 兜底模式
        boolean requiresPostProcess = ImageEditAssetSupport.isInpainting(request)
                || ImageEditAssetSupport.isOutpainting(request);
        String persistedFormat = requiresPostProcess ? OpenAiImageConstants.OUTPUT_FORMAT_PNG : outputFormat;
        for (JsonNode item : data) {
            String b64 = item.path(OpenAiImageConstants.JSON_B64).asText(null);
            if (StringUtils.isNotBlank(b64)) {
                String ossUrl = uploadBase64ToOss(b64, persistedFormat, request);
                if (StringUtils.isNotBlank(ossUrl)) {
                    if (requiresPostProcess) directUrls.add(ossUrl);
                    else ossUrls.add(ossUrl);
                }
                continue;
            }
            String directUrl = item.path(OpenAiImageConstants.JSON_URL).asText(null);
            if (StringUtils.isNotBlank(directUrl)) {
                directUrls.add(directUrl);
            }
        }

        // 解析 usage（gpt-image 为 TOKEN 计费族，按 provider 真实 token 结算）
        Map<String, Object> usage = parseUsage(root);

        if (!ossUrls.isEmpty()) {
            log.info("OpenAI 图片生成成功(b64→OSS), model={}, imageCount={}, input_tokens={}, output_tokens={}",
                    model, ossUrls.size(), usage.get("input_tokens"), usage.get("output_tokens"));
            return ProviderSubmitResult.builder()
                    .ossUrl(ossUrls.get(0))
                    .resultUrls(ossUrls)
                    .resultCount(ossUrls.size())
                    .usage(usage)
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }
        if (!directUrls.isEmpty()) {
            log.info("OpenAI 图片生成成功(url兜底), model={}, imageCount={}, input_tokens={}, output_tokens={}",
                    model, directUrls.size(), usage.get("input_tokens"), usage.get("output_tokens"));
            return ProviderSubmitResult.builder()
                    .directUrl(directUrls.get(0))
                    .resultUrls(directUrls)
                    .resultCount(directUrls.size())
                    .usage(usage)
                    .rawResponse(StringUtils.abbreviate(respBody, OpenAiImageConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }

        log.error("OpenAI 图片生成未返回图片, model={}, responseLen={}", model, respBody.length());
        return ProviderSubmitResult.builder().rawResponse(OpenAiImageConstants.ERROR_NO_IMAGE).build();
    }

    /**
     * 解析官方 usage 对象 → 统一口径 token usage（input_tokens/output_tokens/total_tokens）。
     * OpenAI Images 响应：{@code usage:{input_tokens, output_tokens, total_tokens, input_tokens_details,...}}。
     * gpt-image 为 TOKEN 计费族，结算按此真实 token 重算（缺失返回空 Map，退化为按预扣结算）。
     */
    private Map<String, Object> parseUsage(JsonNode root) {
        Map<String, Object> usage = new LinkedHashMap<>();
        JsonNode meta = root.path("usage");
        if (meta.isMissingNode() || meta.isNull()) {
            return usage;
        }
        int inputTokens = meta.path("input_tokens").asInt(0);
        int outputTokens = meta.path("output_tokens").asInt(0);
        int totalTokens = meta.path("total_tokens").asInt(0);
        if (totalTokens <= 0) {
            totalTokens = inputTokens + outputTokens;
        }
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("total_tokens", totalTokens);
        // 兼容文本 LLM 口径
        usage.put("prompt_tokens", inputTokens);
        usage.put("completion_tokens", outputTokens);
        return usage;
    }

    /** base64 解码后上传 OSS，返回可访问 URL。按 output_format 推导后缀/MIME（缺省 png）。 */
    private String uploadBase64ToOss(String base64Data, String outputFormat,
                                      MediaImageGenerateRequest request) {
        try {
            String pure = base64Data;
            int comma = base64Data.indexOf(',');
            if (base64Data.startsWith("data:") && comma > 0) {
                pure = base64Data.substring(comma + 1);
            }
            byte[] imageBytes = Base64.getDecoder().decode(pure);
            if (imageBytes.length == 0) {
                return null;
            }
            // 后缀/MIME 仅允许 png 或 jpg：jpeg/jpg → .jpg，其余（含 webp/png/空）一律 png
            String suffix = OpenAiImageConstants.IMAGE_SUFFIX_PNG;
            String contentType = OpenAiImageConstants.IMAGE_CONTENT_TYPE_PNG;
            if (StringUtils.isNotBlank(outputFormat)) {
                String fmt = outputFormat.trim().toLowerCase();
                if (OpenAiImageConstants.OUTPUT_FORMAT_JPEG.equals(fmt)
                        || OpenAiImageConstants.OUTPUT_FORMAT_JPG.equals(fmt)) {
                    suffix = OpenAiImageConstants.IMAGE_SUFFIX_JPG;
                    contentType = OpenAiImageConstants.IMAGE_CONTENT_TYPE_JPEG;
                } else if (OpenAiImageConstants.OUTPUT_FORMAT_WEBP.equals(fmt)) {
                    suffix = OpenAiImageConstants.IMAGE_SUFFIX_WEBP;
                    contentType = OpenAiImageConstants.IMAGE_CONTENT_TYPE_WEBP;
                }
            }
            UploadResult uploadResult = OssFactory.instance()
                    .uploadSuffix(imageBytes, suffix, contentType);
            return uploadResult.getUrl();
        } catch (Exception e) {
            log.error("OpenAI 图片 OSS 上传失败, error={}", e.getMessage(), e);
            return null;
        }
    }
    private String doPost(String url, String apiKey, String authHeader, String authPrefix, String json, int timeoutMs) {
        String headerName = StringUtils.isNotBlank(authHeader) ? authHeader : HttpConstants.HEADER_AUTHORIZATION;
        String prefix = authPrefix != null ? authPrefix : HttpConstants.AUTH_BEARER_PREFIX;
        try (HttpResponse response = HttpRequest.post(url)
                .header(headerName, prefix + apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .body(json)
                .timeout(timeoutMs)
                .execute()) {
            return response.body();
        }
    }

    private void validateGpt25Size(String model, String size) {
        if (!isGpt25(model) || StringUtils.isBlank(size) || "auto".equalsIgnoreCase(size)) return;
        String[] parts = size.split("x");
        if (parts.length != 2) throw new ServiceException("图片尺寸无效");
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            long pixels = (long) width * height;
            double ratio = (double) width / height;
            if (width <= 0 || height <= 0 || width % 16 != 0 || height % 16 != 0
                    || width > 3840 || height > 3840
                    || pixels < 655_360L || pixels > 8_294_400L || ratio < 1D / 3D || ratio > 3D) {
                throw new ServiceException("图片尺寸无效");
            }
        } catch (NumberFormatException ex) {
            throw new ServiceException("图片尺寸无效");
        }
    }

    private void validateGpt25Quality(String model, String quality) {
        if (!isGpt25(model) || StringUtils.isBlank(quality)) return;
        String normalized = quality.trim().toLowerCase();
        if (!java.util.Set.of("auto", "low", "medium", "high", "xhigh", "max").contains(normalized)) {
            throw new ServiceException("图片质量无效");
        }
    }

    private boolean isGpt25(String model) {
        return model != null && model.toLowerCase().contains("gpt-image-2.5");
    }

    /** 新 2.5 官方契约使用 multipart；旧兼容网关保持既有 JSON edit 路径，可由配置显式升级。 */
    private boolean useMultipartEdits(AiModelConfigVo modelConfig, String model) {
        if (isGpt25(model)) return true;
        if (modelConfig == null || StringUtils.isBlank(modelConfig.getCapabilityJson())) return false;
        try {
            return MAPPER.readTree(modelConfig.getCapabilityJson()).path("openAiMultipartEdits").asBoolean(false);
        } catch (Exception ex) {
            throw new ServiceException("模型能力配置无效");
        }
    }

    private String doMultipart(String url, String apiKey, String authHeader, String authPrefix,
                               Map<String, Object> body, List<String> images,
                               MediaImageGenerateRequest request, int timeoutMs) throws Exception {
        String boundary = "----aid-image-" + UUID.randomUUID().toString().replace("-", "");
        Path multipartFile = Files.createTempFile("aid-openai-image-", ".multipart");
        try {
            try (OutputStream fileOutput = new BufferedOutputStream(Files.newOutputStream(multipartFile,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
                 LimitedOutputStream output = new LimitedOutputStream(fileOutput, MAX_MULTIPART_BYTES)) {
                for (Map.Entry<String, Object> entry : body.entrySet()) {
                    if (OpenAiImageConstants.JSON_IMAGES.equals(entry.getKey()) || entry.getValue() == null
                            || entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) continue;
                    writePart(output, boundary, entry.getKey(), String.valueOf(entry.getValue()));
                }
                if (ImageEditAssetSupport.isOutpainting(request)) {
                    ImageEditAssetSupport.OpenAiExpandPayload payload =
                            ImageEditAssetSupport.prepareOpenAiExpand(request);
                    writeFile(output, boundary, "image[]", "image.png", "image/png",
                            payload.image());
                    writeFile(output, boundary, "mask", "mask.png", "image/png",
                            payload.mask());
                } else {
                    int index = 0;
                    java.awt.image.BufferedImage primaryImage = null;
                    for (String image : images) {
                        ImageEditAssetSupport.BinaryImage binary = ImageEditAssetSupport.read(image, "参考图");
                        if (primaryImage == null) primaryImage = binary.image();
                        writeImageFile(output, boundary, "image[]", "image-" + (++index) + ".png",
                                binary.image());
                    }
                    if (ImageEditAssetSupport.isInpainting(request)) {
                        if (primaryImage == null) throw new ServiceException("蒙版编辑缺少原图");
                        writeFile(output, boundary, "mask", "mask.png", "image/png",
                                ImageEditAssetSupport.toOpenAiMask(request, primaryImage));
                    }
                }
                output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            String headerName = StringUtils.isNotBlank(authHeader) ? authHeader : HttpConstants.HEADER_AUTHORIZATION;
            String prefix = authPrefix != null ? authPrefix : HttpConstants.AUTH_BEARER_PREFIX;
            try (HttpResponse response = HttpRequest.post(url)
                    .header(headerName, prefix + apiKey)
                    .header(HttpConstants.HEADER_CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                    .body(new cn.hutool.core.io.resource.FileResource(multipartFile))
                    .timeout(timeoutMs)
                    .execute()) {
                return response.body();
            }
        } finally {
            try {
                Files.deleteIfExists(multipartFile);
            } catch (Exception cleanupFailure) {
                log.warn("OpenAI 图片临时请求体清理失败, file={}", multipartFile.getFileName());
            }
        }
    }

    private void writePart(OutputStream output, String boundary, String name, String value)
            throws Exception {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void writeFile(OutputStream output, String boundary, String name, String filename,
                           String contentType, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > ImageEditAssetSupport.MAX_INPUT_BYTES) {
            throw new ServiceException("单张图片素材大小超限");
        }
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeImageFile(OutputStream output, String boundary, String name, String filename,
                                java.awt.image.BufferedImage image) throws Exception {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + filename + "\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        LimitedOutputStream imageOutput = new LimitedOutputStream(output, ImageEditAssetSupport.MAX_INPUT_BYTES);
        if (!javax.imageio.ImageIO.write(image, "png", imageOutput)) throw new ServiceException("图片编码失败");
        imageOutput.flush();
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /** 以磁盘流式构造 multipart，并在写入阶段执行总量上限，避免多参考图在堆内形成双份完整请求体。 */
    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        private LimitedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws java.io.IOException {
            ensureCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
            ensureCapacity(length);
            out.write(bytes, offset, length);
            written += length;
        }

        private void ensureCapacity(int length) {
            if (length < 0 || written + length > limit) {
                throw new ServiceException("图片素材总大小超限");
            }
        }
    }

    private Integer getIntegerOption(Map<String, Object> options, String key) {
        if (options == null || options.get(key) == null) return null;
        Object value = options.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw new ServiceException("图片参数无效");
        }
    }

    /** 解析下发上游的模型名：经 {@link ModelCodeResolver} 解析，兜底默认模型。 */
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaImageGenerateRequest request) {
        String resolved = ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        return StringUtils.isNotBlank(resolved) ? resolved : OpenAiImageConstants.DEFAULT_IMAGE_MODEL;
    }

    /** 按受控操作名构建 OpenAI 图片提交 URL。 */
    static String buildApiUrl(AiModelConfigVo modelConfig, boolean edit) {
        if (modelConfig == null) {
            log.warn("OpenAI 图片端点校验失败, reason=modelConfig");
            throw new IllegalArgumentException("模型配置无效");
        }
        String suffix = ProviderEndpointUtils.normalizeSubmitPath(modelConfig.getApiSuffix());
        String operation = edit ? OPERATION_EDITS : OPERATION_GENERATIONS;
        int placeholderIndex = suffix.indexOf(OPERATION_PLACEHOLDER);
        if (placeholderIndex >= 0) {
            if (placeholderIndex != suffix.lastIndexOf(OPERATION_PLACEHOLDER)) {
                log.warn("OpenAI 图片端点校验失败, reason=operationTemplate");
                throw new IllegalArgumentException("模型路径模板无效");
            }
            suffix = suffix.replace(OPERATION_PLACEHOLDER, operation);
        } else if (edit && suffix.endsWith('/' + OPERATION_GENERATIONS)) {
            suffix = suffix.substring(0, suffix.length() - OPERATION_GENERATIONS.length()) + OPERATION_EDITS;
        }
        return ProviderEndpointUtils.buildSubmitUrl(modelConfig.getBaseUrl(), suffix);
    }

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
}
