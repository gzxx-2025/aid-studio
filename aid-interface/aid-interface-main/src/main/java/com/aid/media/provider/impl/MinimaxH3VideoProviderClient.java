package com.aid.media.provider.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.aid.common.exception.ServiceException;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.MinimaxH3StatusMapper;
import com.aid.media.provider.MinimaxH3VideoRequestBuilder;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.VideoProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** MiniMax H3 视频 V2 官方 API 客户端。 */
@Slf4j
@Component
public class MinimaxH3VideoProviderClient implements VideoProviderClient {

    @Override
    public String protocol() {
        return MinimaxH3Constants.PROTOCOL_VIDEO;
    }

    @Override
    public Integer fallbackMaxReferenceImages(AiModelConfigVo modelConfig) {
        return 9;
    }

    @Override
    public Integer fallbackMaxReferenceVideos(AiModelConfigVo modelConfig) {
        return 3;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        return MinimaxH3Constants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode))
            || MinimaxH3Constants.LEGACY_PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(providerCode));
    }

    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        MinimaxH3VideoRequestBuilder.sanitizePrompt(modelConfig, request);
        validateBaseUrl(modelConfig);
        requireApiKey(modelConfig.getApiKey());
        Map<String, Object> body = MinimaxH3VideoRequestBuilder.buildSubmissionBody(modelConfig, request);
        HttpResult response;
        try {
            response = doPost(buildSubmitUrl(modelConfig), modelConfig.getApiKey(), JSONUtil.toJsonStr(com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, body, request)));
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("MiniMax H3 submit outcome unknown, modelCode={}, error={}",
                modelConfig.getModelCode(), ex.getClass().getSimpleName());
            throw new ServiceException("上游提交失败");
        }
        return parseSubmitResponse(response.statusCode(), response.body());
    }

    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        validateBaseUrl(modelConfig);
        requireApiKey(modelConfig.getApiKey());
        if (StrUtil.isBlank(providerTaskId)) {
            log.warn("MiniMax H3 query rejected: blank provider task id");
            return anomaly(null, "任务编号为空", null);
        }
        HttpResult response;
        try {
            response = doGet(buildQueryUrl(modelConfig, providerTaskId), modelConfig.getApiKey());
        } catch (Exception ex) {
            log.warn("MiniMax H3 query unavailable, taskId={}, error={}",
                providerTaskId, ex.getClass().getSimpleName());
            return anomaly(null, "上游查询暂不可用", null);
        }
        return parseQueryResponse(response.statusCode(), response.body(), providerTaskId,
            StrUtil.blankToDefault(modelConfig.getCapabilityCode(), modelConfig.getModelCode()),
            modelConfig.getRealModelCode());
    }

    static ProviderSubmitResult parseSubmitResponse(int httpStatus, String raw) {
        if (httpStatus < 200 || httpStatus >= 300 || !JSONUtil.isTypeJSON(raw)) {
            log.warn("MiniMax H3 submit rejected, httpStatus={}, responseLength={}",
                httpStatus, StrUtil.length(raw));
            return ProviderSubmitResult.builder()
                    .rawResponse(ProviderErrorSanitizer.fromHttp(httpStatus, raw)).build();
        }
        JsonNode root = ProviderResponseHelper.readTree(raw);
        String taskId = ProviderResponseHelper.readText(root, "task_id");
        if (StrUtil.isBlank(taskId)) {
            log.warn("MiniMax H3 submit response missing task_id, responseLength={}", StrUtil.length(raw));
            return ProviderSubmitResult.builder()
                    .rawResponse(ProviderErrorSanitizer.safeMessage(raw, "上游响应缺少任务标识")).build();
        }
        return ProviderSubmitResult.builder().providerTaskId(taskId).rawResponse(raw).build();
    }

    static ProviderTaskResult parseQueryResponse(int httpStatus, String raw, String providerTaskId,
                                                  String platformModelCode) {
        return parseQueryResponse(httpStatus, raw, providerTaskId, platformModelCode,
            MinimaxH3Constants.REAL_MODEL_CODE);
    }

    static ProviderTaskResult parseQueryResponse(int httpStatus, String raw, String providerTaskId,
                                                  String platformModelCode, String realModelCode) {
        if (httpStatus == 404) {
            log.warn("MiniMax H3 query endpoint returned 404, taskId={}", providerTaskId);
            return ProviderTaskResult.builder()
                .status(MinimaxH3Constants.STATUS_FAILURE)
                .errorMessage("上游查询接口返回 404，任务无法继续查询")
                .taskError(TaskErrorResult.of(TaskErrorCode.UPSTREAM_QUERY_NOT_FOUND))
                .rawResponse(ProviderErrorSanitizer.fromHttp(httpStatus, raw))
                .querySuccessful(Boolean.TRUE)
                .providerStatus("HTTP_404")
                .terminalConfirmed(Boolean.TRUE)
                .build();
        }
        if (httpStatus < 200 || httpStatus >= 300 || !JSONUtil.isTypeJSON(raw)) {
            log.warn("MiniMax H3 query HTTP/document anomaly, taskId={}, httpStatus={}, responseLength={}",
                providerTaskId, httpStatus, StrUtil.length(raw));
            return anomaly(raw, "上游查询暂不可用", null);
        }
        JsonNode root = ProviderResponseHelper.readTree(raw);
        JsonNode task = root == null ? null : root.path("task");
        if (task == null || !task.isObject()) {
            log.warn("MiniMax H3 query response missing task object, taskId={}", providerTaskId);
            return anomaly(raw, "上游响应缺少任务数据", null);
        }
        if (!StrUtil.equals(StrUtil.trim(realModelCode), text(task, "model"))
            || !"generation".equals(text(task, "task_type"))
            || !"video".equals(text(task, "modality"))) {
            log.warn("MiniMax H3 query task contract mismatch, taskId={}", providerTaskId);
            return anomaly(raw, "上游返回非视频生成任务", text(task, "status"));
        }
        String providerStatus = text(task, "status");
        if (!MinimaxH3StatusMapper.isKnown(providerStatus)) {
            log.warn("MiniMax H3 query unknown status, taskId={}, status={}", providerTaskId, providerStatus);
            return anomaly(raw, "上游返回未知任务状态", providerStatus);
        }
        String normalized = MinimaxH3StatusMapper.normalize(providerStatus);
        String resultUrl = text(task.path("content"), "url");
        if (MinimaxH3Constants.STATUS_SUCCESS.equals(normalized) && StrUtil.isBlank(resultUrl)) {
            log.warn("MiniMax H3 succeeded task has no output URL yet, taskId={}", providerTaskId);
            return anomaly(raw, "上游成功产物尚未就绪", providerStatus);
        }
        JsonNode usage = task.path("usage");
        Integer outputSeconds = nonNegativeInteger(usage.get("output_seconds"));
        Integer inputSeconds = nonNegativeInteger(usage.get("input_seconds"));
        Integer inputImageCount = nonNegativeInteger(usage.get("input_image_count"));
        if (MinimaxH3Constants.STATUS_SUCCESS.equals(normalized) && (outputSeconds == null || outputSeconds <= 0)) {
            // OpenAPI 未将 usage 字段声明为 required；缺 output_seconds 时只回退同一官方任务对象中
            // 明确定义为“任务产物时长”的 duration，绝不使用客户端请求时长。
            outputSeconds = positiveInteger(task.get("duration"));
            if (outputSeconds == null) {
                log.warn("MiniMax H3 succeeded task usage is incomplete, taskId={}", providerTaskId);
                return anomaly(raw, "上游成功任务用量尚未就绪", providerStatus);
            }
            log.warn("MiniMax H3 output_seconds omitted; using official task duration, taskId={}", providerTaskId);
        }
        if (MinimaxH3Constants.STATUS_SUCCESS.equals(normalized)) {
            InputUsage resolvedInput = resolveSuccessfulInputUsage(
                platformModelCode, inputSeconds, inputImageCount);
            if (resolvedInput == null) {
                log.warn("MiniMax H3 succeeded task input usage is unavailable or inconsistent, taskId={}, modelCode={}",
                    providerTaskId, platformModelCode);
                return anomaly(raw, "输入用量尚未就绪", providerStatus);
            }
            inputSeconds = resolvedInput.videoSeconds();
            inputImageCount = resolvedInput.imageCount();
        }
        String error = null;
        if (MinimaxH3Constants.STATUS_FAILURE.equals(normalized)) {
            error = MinimaxH3Constants.STATUS_CANCELLED.equalsIgnoreCase(providerStatus)
                ? "上游任务已取消" : safeFailure(task);
        }
        return ProviderTaskResult.builder()
            .status(normalized)
            .resultUrl(resultUrl)
            .videoDurationSeconds(outputSeconds)
            .inputVideoSeconds(inputSeconds)
            .inputImageCount(inputImageCount)
            .errorMessage(error)
            .rawResponse(raw)
            .querySuccessful(Boolean.TRUE)
            .providerStatus(providerStatus)
            .terminalConfirmed(MinimaxH3StatusMapper.isTerminal(providerStatus))
            .build();
    }

    private static String safeFailure(JsonNode task) {
        String message = text(task.path("error"), "message");
        String normalized = StrUtil.trimToEmpty(message).toLowerCase();
        if (normalized.contains("sensitive") || normalized.contains("safety")
            || normalized.contains("policy") || normalized.contains("审核")) {
            return "生成内容未通过安全校验";
        }
        return "上游任务执行失败";
    }

    private static Integer nonNegativeInteger(JsonNode node) {
        if (node == null || node.isNull() || (!node.isNumber() && !node.isTextual())) {
            return null;
        }
        try {
            int value = new BigDecimal(node.asText()).setScale(0, RoundingMode.CEILING).intValueExact();
            return value >= 0 ? value : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer positiveInteger(JsonNode node) {
        Integer value = nonNegativeInteger(node);
        return value != null && value > 0 ? value : null;
    }

    /**
     * OpenAPI 未将零值 usage 声明为必返。只有平台场景能确定真实派发输入时才补零/张数；
     * 多模态参考场景的组合不固定，缺任一实际用量都必须继续查询，不能按预扣上限收费。
     */
    private static InputUsage resolveSuccessfulInputUsage(String modelCode, Integer videoSeconds,
                                                           Integer imageCount) {
        if (MinimaxH3Constants.MODEL_T2V.equals(modelCode)
            || MinimaxH3Constants.MODEL_OFFICIAL.equals(modelCode)
            || MinimaxH3Constants.MODEL_MAX_OFFICIAL.equals(modelCode)
            || "text_to_video".equals(modelCode)) {
            return knownSceneInputUsage(videoSeconds, imageCount, 0);
        }
        if (MinimaxH3Constants.MODEL_I2V_FIRST.equals(modelCode)
            || MinimaxH3Constants.MODEL_I2V_LAST.equals(modelCode)
            || "image_to_video".equals(modelCode) || "last_frame_to_video".equals(modelCode)) {
            return knownSceneInputUsage(videoSeconds, imageCount, 1);
        }
        if (MinimaxH3Constants.MODEL_I2V_FIRST_LAST.equals(modelCode)
            || "start_end_to_video".equals(modelCode)) {
            return knownSceneInputUsage(videoSeconds, imageCount, 2);
        }
        if (MinimaxH3Constants.MODEL_REFERENCE.equals(modelCode)
            || "reference_to_video".equals(modelCode)) {
            return videoSeconds != null && imageCount != null
                ? new InputUsage(videoSeconds, imageCount) : null;
        }
        return null;
    }

    private static InputUsage knownSceneInputUsage(Integer videoSeconds, Integer imageCount, int expectedImages) {
        if ((videoSeconds != null && videoSeconds != 0)
            || (imageCount != null && imageCount > expectedImages)) {
            return null;
        }
        return new InputUsage(0, imageCount == null ? expectedImages : imageCount);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String value = node.path(field).asText(null);
        return StrUtil.trimToNull(value);
    }

    private static ProviderTaskResult anomaly(String raw, String message, String providerStatus) {
        return ProviderTaskResult.builder()
            .status(MinimaxH3Constants.STATUS_PROCESSING)
            .errorMessage(message)
            .rawResponse(raw)
            .querySuccessful(Boolean.FALSE)
            .providerStatus(providerStatus)
            .terminalConfirmed(Boolean.FALSE)
            .build();
    }

    private HttpResult doPost(String url, String apiKey, String body) {
        try (HttpResponse response = HttpRequest.post(url)
            .header("Authorization", MinimaxH3Constants.AUTH_PREFIX + apiKey.trim())
            .header("Content-Type", "application/json")
            .body(body)
            .timeout(MinimaxH3Constants.HTTP_TIMEOUT_MS)
            .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        }
    }

    private HttpResult doGet(String url, String apiKey) {
        try (HttpResponse response = HttpRequest.get(url)
            .header("Authorization", MinimaxH3Constants.AUTH_PREFIX + apiKey.trim())
            .header("Content-Type", "application/json")
            .timeout(MinimaxH3Constants.HTTP_TIMEOUT_MS)
            .execute()) {
            return new HttpResult(response.getStatus(), response.body());
        }
    }

    static String buildSubmitUrl(AiModelConfigVo config) {
        try {
            return ProviderEndpointUtils.buildSubmitUrl(config.getBaseUrl(), config.getApiSuffix());
        } catch (IllegalArgumentException ex) {
            log.warn("MiniMax H3 submit path rejected, modelCode={}, reason={}",
                config.getModelCode(), ex.getMessage());
            throw new ServiceException("模型路径无效");
        }
    }

    static String buildQueryUrl(AiModelConfigVo config, String providerTaskId) {
        try {
            return ProviderEndpointUtils.buildTaskQueryUrl(
                config.getBaseUrl(), config.getTaskQuerySuffix(), providerTaskId);
        } catch (IllegalArgumentException ex) {
            log.warn("MiniMax H3 query path rejected, modelCode={}, reason={}",
                config.getModelCode(), ex.getMessage());
            throw new ServiceException("查询路径无效");
        }
    }

    public static void validateBaseUrl(AiModelConfigVo config) {
        if (config == null || StrUtil.isBlank(config.getBaseUrl())) {
            log.warn("MiniMax H3 base URL is missing, modelCode={}",
                config == null ? null : config.getModelCode());
            throw new ServiceException("MiniMax地址未配置");
        }
        try {
            ProviderEndpointUtils.buildSubmitUrl(config.getBaseUrl(), config.getApiSuffix());
            ProviderEndpointUtils.normalizeTaskQueryTemplate(config.getTaskQuerySuffix());
        } catch (IllegalArgumentException ex) {
            log.warn("MiniMax H3 base URL cannot be parsed, modelCode={}, error={}",
                config.getModelCode(), ex.getClass().getSimpleName());
            throw new ServiceException("MiniMax地址无效");
        }
    }

    private static void requireApiKey(String apiKey) {
        if (StrUtil.isBlank(apiKey)) {
            log.warn("MiniMax H3 API key is missing");
            throw new ServiceException("MiniMax密钥未配置");
        }
    }

    private record HttpResult(int statusCode, String body) {
    }

    private record InputUsage(int videoSeconds, int imageCount) {
    }
}
