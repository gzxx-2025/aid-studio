package com.aid.common.error;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Known provider error fields only: request IDs, echoed prompts and metadata are not error evidence.
 * Contracts: docs.volcengine.com/docs/ark/error-codes, docs.platform.vidu.com/7208256m0,
 * help.aliyun.com/zh/model-studio/manage-asynchronous-tasks, ai.google.dev/api/generate-content,
 * developers.openai.com/api/docs/guides/image-generation.
 */
final class ProviderErrorContract {
    private ProviderErrorContract() {}

    static TaskErrorResult classify(String raw) {
        for (String code : codes(raw)) {
            String fullCode = code.toLowerCase(Locale.ROOT);
            String key = fullCode.split("\\.", 2)[0];
            if (fullCode.equals("inputimagesensitivecontentdetected.privacyinformation")
                    || fullCode.equals("inputvideosensitivecontentdetected.privacyinformation")) {
                return TaskErrorResult.of(TaskErrorCode.REAL_PERSON_RESTRICTED, raw);
            }
            String message = switch (key) {
                case "inputtextsensitivecontentdetected", "inputtextriskdetection", "taskpromptpolicyviolation" -> "提示词未通过内容审核，请修改后重试";
                case "inputimagesensitivecontentdetected", "inputimageriskdetection", "photoauditnotpass" -> "参考图未通过内容审核，请更换后重试";
                case "inputvideosensitivecontentdetected" -> "参考视频未通过内容审核，请更换后重试";
                case "inputaudiosensitivecontentdetected" -> "参考音频未通过内容审核，请更换后重试";
                case "outputaudiosensitivecontentdetected", "outputtextsensitivecontentdetected", "outputimagesensitivecontentdetected", "outputvideosensitivecontentdetected", "outputtextriskdetection", "outputimageriskdetection", "creationpolicyviolation", "datainspectionfailed" -> "生成内容未通过审核，请调整提示词或参考素材后重试";
                case "auditsubmitillegal", "auditfailed", "content_policy_violation", "moderation_blocked", "safety", "blocklist", "prohibited_content", "spii", "image_safety", "image_prohibited_content" -> "内容未通过安全审核，请调整提示词或参考素材后重试";
                default -> null;
            };
            if (message != null) {
                TaskErrorResult result = TaskErrorResult.of(TaskErrorCode.UPSTREAM_CONTENT_FILTERED, raw);
                result.setUserMessage(message);
                return result;
            }
            TaskErrorCode type = switch (key) {
                case "creditinsufficient", "insufficient_quota" -> TaskErrorCode.PROVIDER_QUOTA_EXHAUSTED;
                case "arrearage" -> TaskErrorCode.MERCHANT_QUOTA_EXHAUSTED;
                case "unauthorized", "invalid_api_key", "authenticationerror" -> TaskErrorCode.UPSTREAM_AUTH_INVALID;
                case "quotaexceeded", "toomanyrequests", "systemthrottling", "rate_limit_exceeded" -> TaskErrorCode.UPSTREAM_RATE_LIMITED;
                case "fieldlacking", "fieldunwanted", "fieldinvalid", "fielditemcountoutofrange" -> TaskErrorCode.MODEL_PARAMETER_INCOMPATIBLE;
                case "imagedownloadfailure", "videodownloadfailure" -> TaskErrorCode.USER_FILE_DOWNLOAD_FAILED;
                case "imageformatinvalid", "videoformatinvalid" -> TaskErrorCode.USER_FILE_FORMAT_INVALID;
                case "imagesizeinvalid" -> TaskErrorCode.USER_IMAGE_RESOLUTION_INVALID;
                case "modelunavailable" -> TaskErrorCode.UPSTREAM_SERVICE_NOT_OPEN;
                default -> null;
            };
            if (type != null) return TaskErrorResult.of(type, raw);
        }
        return null;
    }

    static String errorText(String raw) {
        JSONObject root = object(raw);
        if (root == null) return raw;
        List<String> text = new ArrayList<>();
        messages(root, text);
        messages(child(root, "error"), text);
        add(root.get("error") instanceof String ? root.get("error") : null, text);
        messages(child(root, "output"), text);
        return String.join(" ", text);
    }

    private static List<String> codes(String raw) {
        JSONObject root = object(raw);
        List<String> codes = new ArrayList<>();
        if (root == null) return codes;
        codes(root, codes);
        codes(child(root, "error"), codes);
        codes(child(root, "output"), codes);
        JSONObject feedback = child(root, "promptFeedback");
        if (feedback != null) add(feedback.get("blockReason"), codes);
        JSONArray candidates = root.get("candidates") instanceof JSONArray array ? array : null;
        if (candidates != null) for (Object item : candidates) {
            if (item instanceof JSONObject candidate) add(candidate.get("finishReason"), codes);
        }
        return codes;
    }
    private static void codes(JSONObject object, List<String> codes) {
        if (object == null) return;
        add(object.get("code"), codes);
        add(object.get("type"), codes);
        add(object.get("reason"), codes);
        add(object.get("err_code"), codes);
    }
    private static void messages(JSONObject object, List<String> text) {
        if (object == null) return;
        add(object.get("message"), text);
        add(object.get("msg"), text);
    }
    private static void add(Object value, List<String> list) {
        if (value instanceof String string && !string.isBlank()) list.add(string);
    }
    private static JSONObject child(JSONObject root, String key) {
        return root.get(key) instanceof JSONObject child ? child : null;
    }
    private static JSONObject object(String raw) {
        try { return JSON.parseObject(raw); } catch (Exception ignored) { return null; }
    }
}
