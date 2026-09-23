package com.aid.media.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

/** 将上游失败响应压缩为可审计但不含思维链和原始响应体的短错误。 */
public final class ProviderErrorSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ERROR_LENGTH = 300;

    private ProviderErrorSanitizer() {
    }

    public static String fromHttp(int statusCode, String responseBody) {
        responseBody = com.aid.diagnostics.DiagnosticCapture.redactKnownSecrets(responseBody);
        String sanitized = ReasoningContentSanitizer.sanitizeJson(responseBody);
        String detail = extractMessage(sanitized);
        String prefix = "HTTP " + statusCode;
        return truncate(StringUtils.isBlank(detail) ? prefix : prefix + ": " + detail);
    }

    public static String safeMessage(String value, String fallback) {
        value = com.aid.diagnostics.DiagnosticCapture.redactKnownSecrets(value);
        if (StringUtils.isBlank(value)) {
            return truncate(fallback);
        }
        String trimmed = value.trim();
        String sanitized = (trimmed.startsWith("{") || trimmed.startsWith("["))
                ? ReasoningContentSanitizer.sanitizeJson(trimmed) : trimmed;
        String detail = extractMessage(sanitized);
        if (StringUtils.isBlank(detail) || "[reasoning response omitted]".equals(detail)) {
            detail = fallback;
        }
        return truncate(detail);
    }

    private static String extractMessage(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(value);
            JsonNode error = root.path("error");
            String message = text(error.path("message"));
            if (message == null) {
                message = text(root.path("message"));
            }
            String code = text(error.path("code"));
            if (code == null) {
                code = text(error.path("status"));
            }
            if (code == null) {
                code = text(root.path("code"));
            }
            if (message == null) {
                message = text(root.path("msg"));
            }
            if (message == null && error.isTextual()) {
                message = error.asText();
            }
            if (code != null && message != null) {
                return code + ": " + message;
            }
            return message != null ? message : code;
        } catch (Exception ignored) {
            // 非 JSON 只允许短文本；HTML、堆栈和疑似结构体不会继续透传。
            String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
            if (compact.startsWith("<") || compact.contains("{\"") || compact.length() > MAX_ERROR_LENGTH) {
                return null;
            }
            return compact;
        }
    }

    private static String text(JsonNode value) {
        return value != null && value.isValueNode() && !value.isNull()
                && StringUtils.isNotBlank(value.asText()) ? value.asText().trim() : null;
    }

    private static String truncate(String value) {
        String safe = com.aid.diagnostics.DiagnosticCapture.redactKnownSecrets(StringUtils.defaultIfBlank(value, "上游请求失败"));
        return safe.length() <= MAX_ERROR_LENGTH ? safe : safe.substring(0, MAX_ERROR_LENGTH);
    }
}
