package com.aid.media.provider;

import com.aid.common.constant.HttpConstants;
import com.aid.tokendance.provider.text.TokenDanceToolMessages;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容 SSE（chat/completions stream）的 HTTP 拉取与增量解析，供多厂商文本 Provider 复用。
 */
@Slf4j
public final class OpenAiStyleChatStream {

    // 单次 HTTP 读流超时上限，避免无限挂起占用线程。
    private static final int HTTP_STREAM_TIMEOUT_MINUTES = 10;

    /**
     * SSE/同步响应体字节上限，防止上游异常返回超长内容导致 OOM；超过即强制终止读取。
     */
    private static final long MAX_STREAM_BODY_BYTES = 50L * 1024L * 1024L;
    /**
     * 错误响应体专用上限：64KB，足以读完任何 HTTP 错误 JSON/HTML，防止错误页面拖累内存。
     */
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Validate URL/auth/custom headers without opening a connection. */
    public static void validateRequestConfiguration(String url, String apiKey,
            String authHeader, String authPrefix, Map<String, String> extraHeaders) {
        buildJsonRequest(url, apiKey, authHeader, authPrefix, extraHeaders, "{}", false);
    }

    /**
     * 全局复用的线程安全 HttpClient，避免每次请求新建连接池/Selector 线程导致资源耗尽。
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(HTTP_STREAM_TIMEOUT_MINUTES))
            .build();

    private OpenAiStyleChatStream() {
    }

    /**
     * POST 非流式 Chat Completions（stream=false），一次拿到完整 JSON 响应，稳定解析 usage。
     *
     * @param url          完整请求 URL
     * @param apiKey       鉴权 token
     * @param authHeader   鉴权 header 名（null/blank → 默认 Authorization）
     * @param authPrefix   鉴权前缀（null → 默认 "Bearer "；空字符串 → 无前缀）
     * @param extraHeaders 自定义 header（null/empty 不附加）
     * @param jsonBody     请求体
     * @return ProviderSubmitResult 含 directText / rawResponse / usage
     */
    public static ProviderSubmitResult postJsonSync(String url, String apiKey,
                                                    String authHeader, String authPrefix,
                                                    Map<String, String> extraHeaders, String jsonBody) {
        return postJsonSync(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, false);
    }

    public static ProviderSubmitResult postJsonSync(String url, String apiKey,
            String authHeader, String authPrefix, Map<String, String> extraHeaders,
            String jsonBody, boolean allowTools) {
        return postJsonSync(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, allowTools, false);
    }

    public static ProviderSubmitResult postJsonSync(String url, String apiKey,
            String authHeader, String authPrefix, Map<String, String> extraHeaders,
            String jsonBody, boolean allowTools, boolean completions) {
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req;
        try {
            req = buildJsonRequest(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, false);
        } catch (IllegalArgumentException badConfig) {
            log.error("非流式文本请求构造失败（鉴权或 URL 非法）, url={}, err={}", url, badConfig.getMessage());
            return ProviderSubmitResult.builder()
                    .rawResponse("配置错误")
                    .errorDetailJson(TextFailureBillingPolicy.notSentSnapshot("配置错误"))
                    .build();
        }
        HttpResponse<String> resp;
        try {
            resp = com.aid.diagnostics.DiagnosticHttp.send(client, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("非流式文本请求被中断, url={}", url, e);
            return ProviderSubmitResult.builder().rawResponse("请求被中断").build();
        } catch (IOException e) {
            log.error("非流式文本请求IO异常, url={}", url, e);
            return ProviderSubmitResult.builder().rawResponse(e.getMessage()).build();
        }
        String body = resp.body() != null ? resp.body() : "";
        // 同步响应字节上限校验，防止上游异常返回超大 body 导致 OOM。
        if (body.length() > MAX_STREAM_BODY_BYTES) {
            log.error("非流式文本响应超过大小上限, url={}, bodyLen={}, limit={}", url, body.length(), MAX_STREAM_BODY_BYTES);
            return ProviderSubmitResult.builder()
                    .rawResponse("响应超限")
                    .build();
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.error("非流式文本上游HTTP失败, url={}, status={}, bodyLen={}", url, resp.statusCode(), body.length());
            String safeMessage = ProviderErrorSanitizer.fromHttp(resp.statusCode(), body);
            Map<String, Object> usage = parseUsageSafely(body);
            return ProviderSubmitResult.builder()
                    .rawResponse(safeMessage)
                    .errorDetailJson(TextFailureBillingPolicy.httpFailureSnapshot(
                            resp.statusCode(), safeMessage, usage))
                    .usage(usage)
                    .build();
        }
        // 解析非流式 JSON 响应：choices[0].message.content + usage
        Map<String, Object> observedUsage = null;
        try {
            JsonNode root = MAPPER.readTree(body);
            Map<String, Object> usage = parseUsageFromRoot(root);
            observedUsage = usage;
            // 提取文本
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                String finishReason = textOrNull(choice.get("finish_reason"));
                String finishError = TextFinishReasonSupport.openAiFailureMessage(finishReason);
                JsonNode message = choice.path("message");
                if (hasToolCall(message) && !allowTools) {
                    finishError = "生成方式不支持";
                }
                if (allowTools && "tool_calls".equals(finishReason)) finishError = null;
                if (finishError != null) {
                    log.info("非流式文本未完整终止: url={}, finishReason={}, usage={}",
                            url, finishReason, usage);
                    return ProviderSubmitResult.builder()
                            .rawResponse(finishError)
                            .usage(usage.isEmpty() ? null : usage)
                            .build();
                }
                String text = textOrNull(message.get("content"));
                if (completions && message.isMissingNode()) text = textOrNull(choice.get("text"));
                String reasoning = textOrNull(message.get("reasoning_content"));
                log.info("非流式文本响应解析: url={}, hasText={}, hasReasoning={}, usage={}", url,
                        StringUtils.isNotBlank(text), StringUtils.isNotBlank(reasoning), usage);
                if (StringUtils.isBlank(text) && !(allowTools && hasToolCall(message))) {
                    return ProviderSubmitResult.builder()
                            .rawResponse("响应内容为空")
                            .usage(usage.isEmpty() ? null : usage)
                            .build();
                }
                return ProviderSubmitResult.builder()
                        .directText(text)
                        .directReasoning(reasoning)
                        .toolMessage(allowTools ? chatToolContext(root, text, reasoning) : null)
                        .rawResponse(truncateRaw(ReasoningContentSanitizer.sanitizeJson(body)))
                        .usage(usage.isEmpty() ? null : usage)
                        .build();
            }
            log.info("非流式文本响应缺少 choices: url={}, usage={}", url, usage);
            return ProviderSubmitResult.builder()
                .rawResponse("上游终止异常")
                .usage(usage.isEmpty() ? null : usage)
                .build();
        } catch (Exception e) {
            log.error("非流式文本响应解析失败, url={}, bodyLen={}, errorType={}",
                    url, body.length(), e.getClass().getSimpleName());
            return ProviderSubmitResult.builder()
                .directText(null)
                .rawResponse("解析响应失败")
                .usage(observedUsage == null || observedUsage.isEmpty() ? null : observedUsage)
                .build();
        }
    }

    /**
     * 从 OpenAI 兼容响应根节点提取 usage：统一映射 prompt_tokens→input_tokens，completion_tokens→output_tokens。
     */
    static Map<String, Object> parseUsageFromRoot(JsonNode root) {
        Map<String, Object> usage = new HashMap<>();
        JsonNode usageNode = root.path("usage");
        if (!usageNode.isObject()) {
            return usage;
        }
        Integer inputTokens = firstNonNegativeInt(usageNode, "prompt_tokens", "input_tokens");
        Integer outputTokens = firstNonNegativeInt(usageNode, "completion_tokens", "output_tokens");
        Integer totalTokens = firstNonNegativeInt(usageNode, "total_tokens");
        if (inputTokens != null) {
            usage.put("prompt_tokens", inputTokens);
            usage.put("input_tokens", inputTokens);
        }
        if (outputTokens != null) {
            usage.put("completion_tokens", outputTokens);
            usage.put("output_tokens", outputTokens);
        }
        if (totalTokens != null) {
            usage.put("total_tokens", totalTokens);
        } else if (inputTokens != null && outputTokens != null) {
            usage.put("total_tokens", saturatedAdd(inputTokens, outputTokens));
        }
        JsonNode promptDetails = usageNode.get("prompt_tokens_details");
        boolean promptDetailsObject = promptDetails != null && promptDetails.isObject();
        ParsedTokenField detailCached = parseTokenField(promptDetails,
                "cached_tokens", "cache_read_tokens");
        ParsedTokenField rootCacheHit = parseTokenField(usageNode, "prompt_cache_hit_tokens");
        Integer cachedTokens = rootCacheHit.value() != null
                ? rootCacheHit.value() : detailCached.value();
        boolean cachedConflict = rootCacheHit.value() != null && detailCached.value() != null
                && !Objects.equals(rootCacheHit.value(), detailCached.value());
        ParsedTokenField directCacheWrite = parseTokenField(
                promptDetails, "cache_write_tokens", "cached_tokens_written");
        JsonNode cacheCreation = promptDetailsObject ? promptDetails.get("cache_creation") : null;
        ParsedTokenField cacheCreationTokens = parseTokenField(cacheCreation,
                "cache_creation_input_tokens", "ephemeral_5m_input_tokens");
        boolean cacheCreationInvalid = cacheCreation != null
                && (!cacheCreation.isObject() || !cacheCreationTokens.present()
                || cacheCreationTokens.invalid());
        Integer cacheWriteTokens = directCacheWrite.value();
        if (cacheCreationTokens.value() != null) {
            cacheWriteTokens = cacheWriteTokens == null
                    ? cacheCreationTokens.value() : Math.max(cacheWriteTokens, cacheCreationTokens.value());
        }
        // OpenAI 明细对象内仅返回 cached_tokens 时，缺省写缓存量按协议为零。
        if (!directCacheWrite.present() && cacheCreation == null
                && promptDetailsObject && detailCached.value() != null && !detailCached.invalid()) {
            cacheWriteTokens = 0;
        }
        ParsedTokenField rootCacheMiss = parseTokenField(usageNode, "prompt_cache_miss_tokens");
        Integer uncachedTokens = rootCacheMiss.value();
        // DeepSeek 使用根级命中/未命中二分桶，没有独立写缓存收费桶。
        boolean rootBuckets = rootCacheHit.present() && rootCacheMiss.present()
                && !rootCacheHit.invalid() && !rootCacheMiss.invalid() && !cachedConflict
                && inputTokens != null && cachedTokens != null && uncachedTokens != null
                && (long) cachedTokens + uncachedTokens == inputTokens;
        if (rootBuckets && !directCacheWrite.present() && cacheCreation == null) {
            cacheWriteTokens = 0;
            usage.put("cache_write_input_tokens", 0);
        }
        JsonNode completionDetails = usageNode.path("completion_tokens_details");
        Integer reasoningTokens = firstNonNegativeInt(completionDetails, "reasoning_tokens");
        if (cachedTokens != null) {
            usage.put("cached_input_tokens", cachedTokens);
            usage.put("cache_read_input_tokens", cachedTokens);
        }
        if (cacheWriteTokens != null) {
            usage.put("cache_write_input_tokens", cacheWriteTokens);
        }
        boolean inputBucketFieldsValid = (promptDetailsObject || rootBuckets)
                && !detailCached.invalid() && !rootCacheHit.invalid() && !rootCacheMiss.invalid()
                && !directCacheWrite.invalid() && !cacheCreationInvalid && !cachedConflict;
        boolean inputBucketsConsistent = inputBucketFieldsValid
                && inputTokens != null && cachedTokens != null
                && cacheWriteTokens != null
                && (long) cachedTokens + cacheWriteTokens <= inputTokens;
        if (uncachedTokens != null) {
            usage.put("uncached_input_tokens", uncachedTokens);
            inputBucketsConsistent = inputBucketsConsistent
                    && (long) uncachedTokens + cachedTokens + cacheWriteTokens == inputTokens;
        } else if (inputBucketsConsistent) {
            usage.put("uncached_input_tokens", inputTokens - cachedTokens - cacheWriteTokens);
        }
        boolean outputBucketsConsistent = outputTokens != null && reasoningTokens != null
                && reasoningTokens <= outputTokens;
        if (reasoningTokens != null) {
            usage.put("reasoning_tokens", reasoningTokens);
        }
        if (outputBucketsConsistent) {
            usage.put("visible_output_tokens", outputTokens - reasoningTokens);
        }
        if (usage.isEmpty()) {
            return usage;
        }
        usage.put("provider_usage_captured", inputTokens != null && outputTokens != null);
        usage.put("input_usage_complete", inputTokens != null);
        usage.put("output_usage_complete", outputTokens != null);
        usage.put("input_token_buckets_complete", inputBucketsConsistent);
        usage.put("output_token_buckets_complete", outputBucketsConsistent);
        return usage;
    }

    private static Integer firstNonNegativeInt(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isIntegralNumber() && value.canConvertToLong()) {
                long tokens = value.asLong();
                if (tokens >= 0L) {
                    return tokens > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tokens;
                }
            }
        }
        return null;
    }

    private static ParsedTokenField parseTokenField(JsonNode node, String... names) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new ParsedTokenField(null, false, false);
        }
        Integer parsed = null;
        boolean present = false;
        boolean invalid = false;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null) {
                continue;
            }
            present = true;
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                invalid = true;
                continue;
            }
            long tokens = value.asLong();
            if (tokens < 0L || tokens > Integer.MAX_VALUE) {
                invalid = true;
                continue;
            }
            int normalized = (int) tokens;
            if (parsed != null && !Objects.equals(parsed, normalized)) {
                invalid = true;
            } else {
                parsed = normalized;
            }
        }
        return new ParsedTokenField(parsed, present, invalid);
    }

    private record ParsedTokenField(Integer value, boolean present, boolean invalid) {
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static String truncateRaw(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() > 100_000 ? raw.substring(0, 100_000) + "\n...[truncated]" : raw;
    }

    /**
     * POST 流式 Chat Completions，按行解析 SSE，将正文与思考链增量回调给编排层。
     *
     * @param url          完整请求 URL
     * @param apiKey       鉴权 token
     * @param authHeader   鉴权 header 名（null/blank → 默认 Authorization）
     * @param authPrefix   鉴权前缀（null → 默认 "Bearer "；空字符串 → 无前缀）
     * @param extraHeaders 自定义 header（null/empty 不附加）
     * @param jsonBody     请求体
     * @param callbacks    流式回调
     */
    public static void postSseStream(String url, String apiKey,
                                     String authHeader, String authPrefix,
                                     Map<String, String> extraHeaders,
                                     String jsonBody, TextStreamCallbacks callbacks) throws IOException {
        postSseStream(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, callbacks, false);
    }

    public static void postSseStream(String url, String apiKey, String authHeader, String authPrefix,
            Map<String, String> extraHeaders, String jsonBody, TextStreamCallbacks callbacks,
            boolean allowTools) throws IOException {
        postSseStream(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, callbacks, allowTools, false);
    }

    public static void postSseStream(String url, String apiKey, String authHeader, String authPrefix,
            Map<String, String> extraHeaders, String jsonBody, TextStreamCallbacks callbacks,
            boolean allowTools, boolean completions) throws IOException {
        TokenDanceToolMessages.Accumulator tools = allowTools
                ? new TokenDanceToolMessages.Accumulator(TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS,
                        TokenDanceToolMessages.DEEPSEEK_TURN_CHARS) : null;
        HttpClient client = SHARED_HTTP_CLIENT;
        HttpRequest req;
        try {
            req = buildJsonRequest(url, apiKey, authHeader, authPrefix, extraHeaders, jsonBody, true);
        } catch (IllegalArgumentException badConfig) {
            log.error("文本流式请求构造失败（鉴权或 URL 非法）, url={}, err={}", url, badConfig.getMessage());
            callbacks.onError("配置错误", TextFailureBillingPolicy.notSent("配置错误"));
            return;
        }
        HttpResponse<InputStream> resp;
        try {
            resp = com.aid.diagnostics.DiagnosticHttp.send(client, req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callbacks.onError("请求被中断", e);
            return;
        }
        callbacks.onResponseBody(resp.body());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            ErrorBodyRead errorBody = readAllAndClose(resp.body());
            String errBody = errorBody.body();
            log.error("文本流式上游 HTTP 失败, url={}, status={}, bodyLen={}",
                    url, resp.statusCode(), StringUtils.length(errBody));
            if (!errorBody.complete()) {
                callbacks.onError("上游错误响应过大", null);
                return;
            }
            String safeMessage = ProviderErrorSanitizer.fromHttp(resp.statusCode(), errBody);
            Map<String, Object> usage = parseUsageSafely(errBody);
            if (ProviderUsageSupport.hasAnyProviderUsage(usage)) {
                callbacks.onUsage(usage);
            }
            callbacks.onError(safeMessage,
                    TextFailureBillingPolicy.httpFailure(resp.statusCode(), safeMessage, usage));
            return;
        }
        AtomicBoolean sawDone = new AtomicBoolean(false);
        AtomicBoolean sawNormalFinish = new AtomicBoolean(false);
        AtomicReference<String> terminalFailure = new AtomicReference<>();
        AtomicBoolean fatal = new AtomicBoolean(false);
        // 累计字节数，超过上限立即终止并抛错
        long totalBytes = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalBytes += (long) line.length() + 1L; // +1 换行符
                if (totalBytes > MAX_STREAM_BODY_BYTES) {
                    log.error("文本流式响应超过大小上限, url={}, totalBytes={}, limit={}",
                            url, totalBytes, MAX_STREAM_BODY_BYTES);
                    callbacks.onError("响应超限", null);
                    fatal.set(true);
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                callbacks.onSseDataLine(ReasoningContentSanitizer.sanitizeJson(data));
                if ("[DONE]".equals(data)) {
                    sawDone.set(true);
                    break;
                }
                if (!emitDeltasFromChunk(data, callbacks, sawNormalFinish, terminalFailure, tools, completions)) {
                    fatal.set(true);
                    break;
                }
            }
        }
        if (!fatal.get() && terminalFailure.get() != null) {
            callbacks.onError(terminalFailure.get(), null);
            fatal.set(true);
        }
        if (!fatal.get() && !sawNormalFinish.get()) {
            log.warn("文本流式上游缺少正常终止原因, url={}, sawDone={}", url, sawDone.get());
            callbacks.onError("上游终止异常", null);
            fatal.set(true);
        }
        if (!fatal.get()) {
            if (!sawDone.get()) {
                log.info("文本流式上游未显式返回[DONE]，按连接结束处理");
            }
            if (tools != null) {
                var toolMessage = tools.finishChatTurn();
                if (!TokenDanceToolMessages.hasCalls(toolMessage) && StringUtils.isBlank(toolMessage.getContent())) {
                    callbacks.onError("响应内容为空", null);
                    return;
                }
                if (toolMessage != null) callbacks.onToolMessage(toolMessage);
            }
            callbacks.onComplete();
        }
    }

    private static Map<String, Object> parseUsageSafely(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        try {
            Map<String, Object> usage = parseUsageFromRoot(MAPPER.readTree(body));
            return usage == null || usage.isEmpty() ? null : usage;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * @return false 表示解析致命错误，应终止流且不再 onComplete。
     */
    private static boolean emitDeltasFromChunk(String dataJson, TextStreamCallbacks callbacks,
                                               AtomicBoolean sawNormalFinish,
                                               AtomicReference<String> terminalFailure,
                                               TokenDanceToolMessages.Accumulator tools, boolean completions) {
        if (StringUtils.isBlank(dataJson)) {
            return true;
        }
        try {
            JsonNode root = MAPPER.readTree(dataJson);
            // 先提取 usage（Qwen 等模型的最终 usage chunk 中 choices 为空，
            // 必须在工具、终止原因和正文校验之前解析，否则异常路径会丢失真实用量）。
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                Map<String, Object> usage = parseUsageFromRoot(root);
                if (!usage.isEmpty()) {
                    callbacks.onUsage(usage);
                }
            }
            if (tools != null) tools.accept(root);

            // 再提取 delta 文本（choices 为空时跳过 delta，但 usage 已处理）。
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode choice = choices.get(0);
                JsonNode finishNode = choice.get("finish_reason");
                JsonNode delta = choice.path("delta");
                if (hasToolCall(delta) && tools == null) {
                    terminalFailure.compareAndSet(null, "生成方式不支持");
                }
                if (finishNode != null && !finishNode.isNull()) {
                    String finishReason = finishNode.isTextual() ? finishNode.asText() : finishNode.toString();
                    if (!"null".equalsIgnoreCase(StringUtils.trim(finishReason))) {
                        String finishError = TextFinishReasonSupport.openAiFailureMessage(finishReason);
                        if (tools != null && "tool_calls".equals(finishReason)) finishError = null;
                        if (finishError != null) {
                            log.info("文本流式上游未完整终止: finishReason={}", finishReason);
                            terminalFailure.compareAndSet(null, finishError);
                        } else {
                            sawNormalFinish.set(true);
                        }
                    }
                }
                if (terminalFailure.get() != null) {
                    return true;
                }
                String content = textOrNull(delta.get("content"));
                if (completions && delta.isMissingNode()) content = textOrNull(choice.get("text"));
                if (StringUtils.isNotEmpty(content)) {
                    callbacks.onDelta(content);
                }
                String reasoning = textOrNull(delta.get("reasoning_content"));
                if (StringUtils.isNotEmpty(reasoning)) {
                    callbacks.onReasoningDelta(reasoning);
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("解析 SSE data 片段失败, dataLen={}, errorType={}",
                    dataJson.length(), e.getClass().getSimpleName());
            callbacks.onError("解析流数据失败", null);
            return false;
        }
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        return n.toString();
    }

    private static com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem chatToolContext(
            JsonNode root, String content, String reasoning) {
        var message = TokenDanceToolMessages.sync(root, TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS);
        if (message == null) {
            message = new com.aid.media.dto.MediaTextGenerateRequest.TextMessageItem();
            message.setRole("assistant");
            message.setContent(content);
            message.setReasoningContent(reasoning == null ? "" : reasoning);
        }
        return message;
    }

    private static boolean hasToolCall(JsonNode messageOrDelta) {
        if (messageOrDelta == null || messageOrDelta.isMissingNode() || messageOrDelta.isNull()) {
            return false;
        }
        JsonNode toolCalls = messageOrDelta.get("tool_calls");
        JsonNode functionCall = messageOrDelta.get("function_call");
        return toolCalls != null && !toolCalls.isNull() && !toolCalls.isEmpty()
                || functionCall != null && !functionCall.isNull() && !functionCall.isEmpty();
    }

    private static ErrorBodyRead readAllAndClose(InputStream in) throws IOException {
        if (in == null) {
            return new ErrorBodyRead("", true);
        }
        // 错误响应读取上限 64KB，防止上游返回大 HTML 错误页拖累内存。
        try (InputStream closeable = in;
             BufferedReader br = new BufferedReader(new InputStreamReader(closeable, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            int totalLen = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (totalLen + line.length() + 1 > MAX_ERROR_BODY_BYTES) {
                    sb.append(line, 0, Math.min(line.length(), MAX_ERROR_BODY_BYTES - totalLen));
                    sb.append("\n...[error body truncated]");
                    return new ErrorBodyRead(sb.toString(), false);
                }
                sb.append(line).append('\n');
                totalLen += line.length() + 1;
            }
            return new ErrorBodyRead(sb.toString(), true);
        }
    }

    private record ErrorBodyRead(String body, boolean complete) {
    }

    /**
     * 构建 OpenAI 兼容 Chat Completions HTTP 请求，支持自定义鉴权与额外 header。
     *
     * @param url          完整 URL
     * @param apiKey       鉴权 token
     * @param authHeader   鉴权 header 名（null/blank → Authorization）
     * @param authPrefix   鉴权前缀（null → "Bearer "；空字符串 → 无前缀）
     * @param extraHeaders 自定义 header（null/empty 忽略）
     * @param jsonBody     请求体
     * @param accessSse    true 时附加 Accept: text/event-stream
     */
    private static HttpRequest buildJsonRequest(String url, String apiKey,
                                                String authHeader, String authPrefix,
                                                Map<String, String> extraHeaders,
                                                String jsonBody, boolean accessSse) {
        // 鉴权 header 名兜底：默认 Authorization
        String effectiveAuthHeader = StringUtils.isNotBlank(authHeader)
                ? authHeader.trim() : HttpConstants.HEADER_AUTHORIZATION;
        // 鉴权前缀兜底：null 表示用默认 Bearer，空字符串表示显式无前缀
        String effectivePrefix = (authPrefix == null) ? HttpConstants.AUTH_BEARER_PREFIX : authPrefix;
        String authValue = effectivePrefix + StringUtils.defaultString(apiKey);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(HTTP_STREAM_TIMEOUT_MINUTES))
                .header(effectiveAuthHeader, authValue)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON);
        if (accessSse) {
            builder.header(HttpConstants.HEADER_ACCEPT, HttpConstants.ACCEPT_TEXT_EVENT_STREAM);
        }
        // 附加自定义 header（如 Azure 的 api-version）
        if (extraHeaders != null && !extraHeaders.isEmpty()) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (StringUtils.isBlank(name) || value == null) {
                    continue;
                }
                // 跳过与鉴权/必备 header 同名的项，防止运营误配置覆盖
                if (effectiveAuthHeader.equalsIgnoreCase(name)
                        || HttpConstants.HEADER_CONTENT_TYPE.equalsIgnoreCase(name)
                        || HttpConstants.HEADER_ACCEPT.equalsIgnoreCase(name)) {
                    log.warn("OpenAiStyleChatStream: 忽略与必备 header 冲突的 extra header: {}", name);
                    continue;
                }
                builder.header(name, value);
            }
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(com.aid.diagnostics.DiagnosticCapture.outboundBody(jsonBody), StandardCharsets.UTF_8)).build();
    }
}
