package com.aid.common.error.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidProviderErrorRule;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 错误规则匹配引擎。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class ErrorRuleEngine {

    public static final String MATCH_HTTP_STATUS = "HTTP_STATUS";
    public static final String MATCH_CODE = "CODE";
    public static final String MATCH_KEYWORD = "KEYWORD";
    public static final String MATCH_REGEX = "REGEX";
    public static final String MATCH_JSON_PATH = "JSON_PATH";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 判定一条规则是否命中。
     *
     * @param rule       规则
     * @param httpStatus HTTP 状态码（< 0 表示无 HTTP 状态码上下文）
     * @param rawMessage 上游原始错误体
     * @return 命中 true
     */
    public boolean matches(AidProviderErrorRule rule, int httpStatus, String rawMessage) {
        if (rule == null) {
            return false;
        }
        Integer enabled = rule.getEnabled();
        if (enabled == null || enabled == 0) {
            return false;
        }
        String matchType = rule.getMatchType();
        if (StringUtils.isBlank(matchType)) {
            return false;
        }
        try {
            switch (matchType.toUpperCase()) {
                case MATCH_HTTP_STATUS:
                    return matchHttpStatus(rule.getMatchPattern(), httpStatus);
                case MATCH_CODE:
                    return matchCode(rule.getMatchPattern(), rawMessage);
                case MATCH_KEYWORD:
                    return matchKeyword(rule.getMatchPattern(), rawMessage, isCaseSensitive(rule));
                case MATCH_REGEX:
                    return matchRegex(rule.getMatchPattern(), rawMessage, isCaseSensitive(rule));
                case MATCH_JSON_PATH:
                    return matchJsonPath(rule.getMatchField(), rule.getMatchPattern(), rawMessage,
                            isCaseSensitive(rule));
                default:
                    log.warn("未知 match_type={}, ruleId={}", matchType, rule.getId());
                    return false;
            }
        } catch (Exception e) {
            log.warn("规则匹配异常, ruleId={}, type={}, err={}", rule.getId(), matchType, e.getMessage());
            return false;
        }
    }

    private boolean isCaseSensitive(AidProviderErrorRule rule) {
        return rule.getCaseSensitive() != null && rule.getCaseSensitive() == 1;
    }

    /** HTTP_STATUS：支持 {@code 401,500-504} 这种语法 */
    private boolean matchHttpStatus(String pattern, int httpStatus) {
        if (httpStatus <= 0 || StringUtils.isBlank(pattern)) {
            return false;
        }
        for (String token : pattern.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            int dash = t.indexOf('-');
            if (dash > 0 && dash < t.length() - 1) {
                try {
                    int lo = Integer.parseInt(t.substring(0, dash).trim());
                    int hi = Integer.parseInt(t.substring(dash + 1).trim());
                    if (httpStatus >= lo && httpStatus <= hi) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            try {
                if (Integer.parseInt(t) == httpStatus) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    /** CODE：逗号分隔，rawMessage 中需出现完整 code（前后非数字） */
    private boolean matchCode(String pattern, String rawMessage) {
        if (StringUtils.isBlank(rawMessage) || StringUtils.isBlank(pattern)) {
            return false;
        }
        String lower = rawMessage.toLowerCase();
        for (String token : pattern.split(",")) {
            String code = token.trim();
            if (code.isEmpty()) {
                continue;
            }
            if (code.matches("[0-9]+") ? matchNumericCode(code, rawMessage) : hasCode(lower, code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测完整 code，不能命中请求 ID 或其他标识符中的片段。
     */
    private boolean hasCode(String lower, String code) {
        if (lower == null || lower.isEmpty() || code == null || code.isEmpty()) {
            return false;
        }
        int from = 0;
        while (from < lower.length()) {
            int idx = lower.indexOf(code, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
            int end = idx + code.length();
            boolean rightOk = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
        return false;
    }

    /** 数字规则只检查错误码或错误文案，不能扫描 JSON 中的 request_id 等元数据。 */
    private boolean matchNumericCode(String code, String rawMessage) {
        try {
            JsonNode root = MAPPER.readTree(rawMessage);
            if (root == null) return false;
            if (root.isValueNode()) return hasCode(root.asText(), code);
            for (String path : new String[]{"/code", "/error/code", "/status_code", "/error/status_code",
                    "/statusCode", "/error/statusCode", "/http_status", "/httpStatus"}) {
                JsonNode value = root.at(path);
                if (value.isValueNode() && code.equals(value.asText())) return true;
            }
            for (String path : new String[]{"/message", "/msg", "/error/message", "/error/msg", "/error"}) {
                JsonNode value = root.at(path);
                if (value.isTextual() && hasCode(value.asText(), code)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return hasCode(rawMessage, code);
        }
    }

    /** KEYWORD：逗号分隔，任一关键字命中即匹配 */
    private boolean matchKeyword(String pattern, String rawMessage, boolean caseSensitive) {
        if (StringUtils.isBlank(rawMessage) || StringUtils.isBlank(pattern)) {
            return false;
        }
        String haystack = caseSensitive ? rawMessage : rawMessage.toLowerCase();
        for (String token : pattern.split(",")) {
            String kw = caseSensitive ? token.trim() : token.trim().toLowerCase();
            if (kw.isEmpty()) {
                continue;
            }
            if (kw.matches("[0-9]+") ? matchNumericCode(kw, rawMessage) : haystack.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** REGEX：Java 正则匹配 */
    private boolean matchRegex(String pattern, String rawMessage, boolean caseSensitive) {
        if (StringUtils.isBlank(rawMessage) || StringUtils.isBlank(pattern)) {
            return false;
        }
        int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern p = Pattern.compile(pattern, flags);
        return p.matcher(rawMessage).find();
    }

    /**
     * JSON_PATH：从原始 JSON 中取 match_field 指定路径的值，再用 match_pattern（关键字）匹配。
     * 简化版 JSON 路径解析，仅支持 $.a.b.c 这种点分形式，不支持数组下标和过滤表达式。
     * 命中条件：取出的值（toString）包含 match_pattern 中的任一关键字。
     */
    private boolean matchJsonPath(String fieldPath, String pattern, String rawMessage, boolean caseSensitive) {
        if (StringUtils.isBlank(fieldPath) || StringUtils.isBlank(pattern) || StringUtils.isBlank(rawMessage)) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(rawMessage);
            String path = fieldPath.startsWith("$.") ? fieldPath.substring(2) : fieldPath;
            JsonNode current = root;
            for (String seg : path.split("\\.")) {
                if (current == null || current.isMissingNode()) {
                    return false;
                }
                current = current.get(seg);
            }
            if (current == null || current.isMissingNode() || current.isNull()) {
                return false;
            }
            String value = current.isTextual() ? current.asText() : current.toString();
            return matchKeyword(pattern, value, caseSensitive);
        } catch (Exception e) {
            // 非 JSON 体或路径不存在，视为不命中
            return false;
        }
    }
}
