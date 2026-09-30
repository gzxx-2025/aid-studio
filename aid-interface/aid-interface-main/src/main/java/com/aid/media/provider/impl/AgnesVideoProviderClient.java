package com.aid.media.provider.impl;


import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.common.constant.HttpConstants;
import com.aid.common.utils.ProviderEndpointUtils;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.AgnesConstants;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.provider.ModelCodeResolver;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.provider.ReferencePromptSanitizer;
import com.aid.media.provider.VideoProviderClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Agnes 视频异步生成与状态查询实现。 */
@Slf4j
@Component
public class AgnesVideoProviderClient implements VideoProviderClient {

    /** 提交超时（毫秒）：建任务通常较快，留足余量 */
    private static final int SUBMIT_TIMEOUT_MS = 120_000;

    /**
     * 业务层最低视频时长（秒）。
     * Agnes 帧数硬约束 ≥ 81 帧（{@code 8n+1}，n≥10），@24fps 约 3.4 秒；
     * 系统计费按用户传入的 {@code durationSeconds} 预冻结/结算（PER_SECOND × DIRECT_SETTLE × REQUEST_PARAM），
     * 若用户传入 1-3 秒会出现"上游实际跑 3.4 秒、平台只按 1 秒计费"的少扣费缺口。
     * 这里在 Provider 层统一拒绝低于 4 秒（4×24=96 帧 → 89 帧 8n+1，可与上游对齐），
     * 与 SQL 中 {@code durationOptions=[4,5,8,10]} 联动，前端选项已不会下传低值。
     */
    private static final int MIN_DURATION_SECONDS = 4;

    /**
     * 状态查询最小间隔（毫秒）：Agnes 对 video status query 有秒级短窗限流，
     * 调度中心批量轮询会把 N 个任务的查询挤在几秒内连发，超出配额即 429。
     * 本闸门保证同一进程内相邻两次状态查询至少间隔该值，从源头削平突发。
     */
    private static final long QUERY_MIN_GAP_MS = 2_000L;

    /** 闸门单次最长等待（毫秒）：防调度线程长阻塞，超时直接放行，残余 429 由可重试轮询兜底 */
    private static final long QUERY_GATE_MAX_WAIT_MS = 15_000L;

    /** 闸门等待轮询步长（毫秒） */
    private static final long QUERY_GATE_SLEEP_STEP_MS = 200L;

    /** 上一次状态查询的时间戳（毫秒），进程内全局共享（Agnes 限流按 API Key 维度，本系统单 Key） */
    private static final AtomicLong LAST_QUERY_AT_MS = new AtomicLong(0L);
    @Override
    public String protocol() {
        return AgnesConstants.PROTOCOL_VIDEO;
    }

    @Override
    public boolean supportsProviderCode(String providerCode) {
        // Agnes 视频：按 provider_code 精确归属
        return providerCode != null
                && AgnesConstants.PROVIDER_CODE.equalsIgnoreCase(providerCode.trim());
    }
    @Override
    public ProviderSubmitResult submit(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        com.aid.media.provider.VideoProviderConfigurationValidator.validate(modelConfig);
        String apiKey = modelConfig != null ? modelConfig.getApiKey() : null;
        if (StringUtils.isBlank(apiKey)) {
            log.error("Agnes 视频提交失败: apiKey 为空, modelCode={}",
                    modelConfig == null ? null : modelConfig.getModelCode());
            return ProviderSubmitResult.builder().rawResponse(AgnesConstants.ERROR_API_KEY_EMPTY).build();
        }

        String model = resolveEffectiveModel(modelConfig, request);
        String submitUrl = buildApiUrl(modelConfig.getBaseUrl(), modelConfig.getApiSuffix());
        Map<String, Object> body;
        if (AgnesVideo25RequestBuilder.supportsModelName(model)) {
            body = AgnesVideo25RequestBuilder.buildSubmissionBody(model, modelConfig, request);
        } else {
            // 2.0 不下发参考音频，悬空音频占位必须降级为普通文字。
            ReferencePromptSanitizer.sanitizeInPlace(request,
                    ReferenceImageLimiter.resolveMax(modelConfig, 0), 0);
            Integer reqDuration = request == null ? null : request.getDurationSeconds();
            if (reqDuration != null && reqDuration > 0 && reqDuration < MIN_DURATION_SECONDS) {
                log.error("Agnes 视频时长不足: requestedSeconds={}, minSeconds={}, modelCode={}",
                        reqDuration, MIN_DURATION_SECONDS,
                        modelConfig == null ? null : modelConfig.getModelCode());
                throw new ServiceException("时长过短");
            }
            body = buildSubmitBody(model, request);
        }
        String json = JSONUtil.toJsonStr(com.aid.model.definition.ModelConfiguredRequestBody.apply(modelConfig, body, request));
        log.info("Agnes 视频提交, url={}, model={}, mode={}, seconds={}", submitUrl, model,
                body.get(AgnesConstants.JSON_MODE), body.get("seconds"));

        String raw = doPost(submitUrl, apiKey, modelConfig.getAuthHeader(), modelConfig.getAuthPrefix(), json);
        JsonNode root = ProviderResponseHelper.readTree(raw);
        if (root == null) {
            log.error("Agnes 视频提交响应解析失败, model={}, responseLen={}", model, StringUtils.length(raw));
            return ProviderSubmitResult.builder().rawResponse(raw).build();
        }

        JsonNode errorNode = root.path("error");
        if (errorNode.isObject() && !errorNode.isNull()) {
            String errMsg = errorNode.path("message").asText("未知错误");
            log.error("Agnes 视频提交上游错误, model={}, error={}, responseLen={}",
                    model, errMsg, StringUtils.length(raw));
            return ProviderSubmitResult.builder()
                    .rawResponse(StringUtils.abbreviate(raw, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX))
                    .build();
        }

        //    Agnes 旧版 /v1/videos/{task_id} 查询端点存在「上游已完成、查询仍恒返回 queued」的缺陷，
        //    官方推荐改用 video_id 走 /agnesapi?video_id= 查询（task_query_suffix=/agnesapi?video_id=%s）。
        //    故这里把 video_id 作为 providerTaskId 落库，确保轮询走可正确反映终态的端点。
        String taskId = ProviderResponseHelper.readText(root,
                "video_id", "data.video_id",
                "task_id", "id", "data.task_id", "data.id");
        String directUrl = ProviderResponseHelper.readText(root,
                "video_url", "data.video_url", "metadata.url");
        if (StringUtils.isBlank(taskId) && StringUtils.isBlank(directUrl)) {
            // 既无任务 ID 也无直出 URL：提交失败，透传错误
            String error = ProviderResponseHelper.readText(root, "error.message", "message", "error");
            log.error("Agnes 视频提交未返回任务ID, model={}, error={}, responseLen={}",
                    model, error, StringUtils.length(raw));
            return ProviderSubmitResult.builder().rawResponse(StringUtils.defaultIfBlank(error, raw)).build();
        }
        return ProviderSubmitResult.builder()
                .providerTaskId(taskId)
                .directUrl(directUrl)
                .rawResponse(StringUtils.abbreviate(raw, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX))
                .build();
    }
    @Override
    public ProviderTaskResult query(AiModelConfigVo modelConfig, String providerTaskId) {
        // 状态查询限速闸门：调度中心批量轮询时把相邻查询强制拉开间隔，避免触发上游短窗限流(429)
        acquireQuerySlot(providerTaskId);
        String queryUrl = buildTaskUrl(modelConfig.getBaseUrl(), modelConfig.getTaskQuerySuffix(), providerTaskId);
        queryUrl = AgnesVideo25RequestBuilder.appendModelName(queryUrl,
                ModelCodeResolver.resolveUpstreamModel(modelConfig, null));
        String raw = doGet(queryUrl, modelConfig.getApiKey(), modelConfig.getAuthHeader(), modelConfig.getAuthPrefix());
        JsonNode root = ProviderResponseHelper.readTree(raw);
        if (root == null) {
            return queryAnomaly(raw, "上游响应非JSON", null);
        }
        String taskStatus = ProviderResponseHelper.readText(root, "status", "data.status", "task_status");
        JsonNode errorNode = root.path("error");
        String errMsg = null;
        if (errorNode.isObject() && !errorNode.isNull()) {
            errMsg = errorNode.path("message").asText("查询失败");
        }
        if (!isKnownStatus(taskStatus)) {
            if (StringUtils.isNotBlank(errMsg)) {
                return queryAnomaly(raw, errMsg, taskStatus);
            }
            return queryAnomaly(raw, StringUtils.isBlank(taskStatus)
                    ? "上游响应缺少状态" : "上游返回未知状态:" + taskStatus, taskStatus);
        }
        if (errorNode.isObject() && !errorNode.isNull()
                && !AgnesConstants.STATUS_FAILED.equalsIgnoreCase(taskStatus)) {
            int errCode = errorNode.path("code").asInt(0);
            log.warn("Agnes 视频查询返回错误但未给出 failed 终态, taskId={}, code={}, error={}",
                    providerTaskId, errCode, errMsg);
            return queryAnomaly(raw, errMsg, taskStatus);
        }
        String normalizedStatus = normalizeStatus(taskStatus);
        // 结果 URL：官方字段为顶层 url，其余为兼容路径；
        // 不读 remixed_from_video_id 之类 ID 字段，防止把任务 ID 误当结果 URL。
        String videoUrl = ProviderResponseHelper.readText(root,
                "video_url",
                "data.video_url",
                "output.video_url",
                "metadata.url",
                "data.url",
                "url");
        if (StringUtils.isBlank(videoUrl)) {
            videoUrl = ProviderResponseHelper.findFirstUrl(root);
        }
        String errorMessage = ProviderResponseHelper.readText(root, "error.message", "error", "message");
        Integer videoDuration = parseSeconds(root);

        //    防止空 URL 进入 SUCCEEDED 触发空内容计费。
        if (AgnesConstants.TASK_STATUS_SUCCEEDED.equals(normalizedStatus) && StringUtils.isBlank(videoUrl)) {
            log.error("Agnes 视频查询完成但缺少结果URL, taskId={}, status={}, responseLen={}",
                    providerTaskId, taskStatus, StringUtils.length(raw));
            return queryAnomaly(raw, StringUtils.defaultIfBlank(errorMessage, "上游成功但结果链接未就绪"), taskStatus);
        }

        return ProviderTaskResult.builder()
                .status(normalizedStatus)
                .resultUrl(videoUrl)
                .errorMessage(errorMessage)
                .videoDurationSeconds(videoDuration)
                .rawResponse(StringUtils.abbreviate(raw, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX))
                .querySuccessful(Boolean.TRUE)
                .providerStatus(taskStatus)
                .terminalConfirmed(isTerminalStatus(taskStatus))
                .build();
    }

    private boolean isKnownStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return false;
        }
        String lower = status.trim().toLowerCase();
        return AgnesConstants.STATUS_QUEUED.equals(lower)
                || AgnesConstants.STATUS_IN_PROGRESS.equals(lower)
                || AgnesConstants.STATUS_COMPLETED.equals(lower)
                || AgnesConstants.STATUS_FAILED.equals(lower);
    }

    private boolean isTerminalStatus(String status) {
        String lower = StringUtils.defaultString(status).trim().toLowerCase();
        return AgnesConstants.STATUS_COMPLETED.equals(lower) || AgnesConstants.STATUS_FAILED.equals(lower);
    }

    private ProviderTaskResult queryAnomaly(String raw, String message, String providerStatus) {
        return ProviderTaskResult.builder()
                .status(AgnesConstants.TASK_STATUS_PROCESSING)
                .errorMessage(StringUtils.defaultIfBlank(message, "上游状态待确认"))
                .rawResponse(StringUtils.abbreviate(raw, AgnesConstants.LOG_RESPONSE_SNIPPET_MAX))
                .querySuccessful(Boolean.FALSE)
                .providerStatus(providerStatus)
                .terminalConfirmed(Boolean.FALSE)
                .build();
    }

    /**
     * 领取一个状态查询槽位：CAS 抢占「上次查询时间」，保证相邻两次上游查询间隔 ≥ {@link #QUERY_MIN_GAP_MS}。
     * 未到间隔时短睡等待；等待累计超过 {@link #QUERY_GATE_MAX_WAIT_MS} 直接放行（防调度线程长阻塞）。
     * 查询接口的 HTTP/鉴权/限流异常统一作为查询异常保持挂起，不是生成失败。
     */
    private void acquireQuerySlot(String providerTaskId) {
        long deadline = System.currentTimeMillis() + QUERY_GATE_MAX_WAIT_MS;
        while (true) {
            long last = LAST_QUERY_AT_MS.get();
            long now = System.currentTimeMillis();
            long readyAt = last + QUERY_MIN_GAP_MS;
            if (now >= readyAt) {
                if (LAST_QUERY_AT_MS.compareAndSet(last, now)) {
                    return;
                }
                // CAS 失败说明并发线程刚领走槽位，重新读时间再算
                continue;
            }
            if (now >= deadline) {
                log.warn("Agnes 视频查询限速闸门等待超时直接放行, taskId={}", providerTaskId);
                return;
            }
            try {
                Thread.sleep(Math.min(readyAt - now, QUERY_GATE_SLEEP_STEP_MS));
            } catch (InterruptedException ie) {
                // 中断时恢复标记并放行，由上层调度自行收口
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 组装 Agnes /v1/videos 请求体：。
     */
    private Map<String, Object> buildSubmitBody(String model, MediaVideoGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(AgnesConstants.JSON_MODEL, model);                                       // 模型名
        body.put(AgnesConstants.JSON_PROMPT, request == null ? "" : request.getPrompt()); // 提示词

        Map<String, Object> options = request == null ? null : request.getOptions();

        // 帧率：options.frame_rate / fps，默认 24
        int frameRate = resolveFrameRate(options);
        body.put(AgnesConstants.JSON_FRAME_RATE, frameRate);
        // 帧数：由时长 × 帧率推导，并对齐到合法 8n+1（受 [MIN,MAX] 约束）
        int numFrames = resolveNumFrames(request, frameRate, options);
        body.put(AgnesConstants.JSON_NUM_FRAMES, numFrames);
        // 宽高：options.width/height → 比例推断 → 默认
        int[] wh = resolveWidthHeight(request, options);
        body.put(AgnesConstants.JSON_WIDTH, wh[0]);
        body.put(AgnesConstants.JSON_HEIGHT, wh[1]);

        // 负向提示词（仅 options 透传）
        String negativePrompt = getStringOption(options, "negative_prompt");
        if (StringUtils.isNotBlank(negativePrompt)) {
            body.put(AgnesConstants.JSON_NEGATIVE_PROMPT, negativePrompt);
        }

        // 输入图：
        //  - 首尾帧（关键帧动画）优先：options 带尾帧 URL 时，extra_body.image=[首帧,尾帧] + mode=keyframes，
        //    在两个关键帧之间生成平滑过渡（Agnes 官方「关键帧动画」工作流）；
        //  - 否则多图 / 显式 mode 走 extra_body.image；单图走顶层 image。
        List<String> refImages = resolveReferenceImages(request);
        String mode = getStringOption(options, "mode");
        String lastFrameUrl = resolveLastFrameUrl(options);
        if (StringUtils.isNotBlank(lastFrameUrl)) {
            // 首帧：参考图首张优先，其次顶层 imageUrl
            String firstFrame = !refImages.isEmpty() ? refImages.get(0)
                    : (request != null ? request.getImageUrl() : null);
            List<String> keyframes = new ArrayList<>();
            if (StringUtils.isNotBlank(firstFrame)) {
                keyframes.add(firstFrame);
            }
            keyframes.add(lastFrameUrl);
            Map<String, Object> extraBody = new LinkedHashMap<>();
            extraBody.put(AgnesConstants.JSON_IMAGE, keyframes);
            // 显式 mode 优先，否则用关键帧模式（首尾帧）
            extraBody.put(AgnesConstants.JSON_MODE,
                    StringUtils.isNotBlank(mode) ? mode : AgnesConstants.MODE_KEYFRAMES);
            body.put(AgnesConstants.JSON_EXTRA_BODY, extraBody);
        } else if (StringUtils.isNotBlank(mode)) {
            // 显式 mode（如 keyframes）：多图走 extra_body.image + mode 透传
            Map<String, Object> extraBody = new LinkedHashMap<>();
            if (!refImages.isEmpty()) {
                extraBody.put(AgnesConstants.JSON_IMAGE, refImages);
            } else if (StringUtils.isNotBlank(request != null ? request.getImageUrl() : null)) {
                extraBody.put(AgnesConstants.JSON_IMAGE, List.of(request.getImageUrl()));
            }
            extraBody.put(AgnesConstants.JSON_MODE, mode);
            body.put(AgnesConstants.JSON_EXTRA_BODY, extraBody);
        } else {
            // 单图图生视频：顶层 image。
            // Agnes 多图仅支持 mode=keyframes（首尾帧过渡），与「多参考图锁人设」语义不同：
            // 无显式 mode 时多图下发会被上游整单拒绝（multiple images, but mode was omitted），
            // 因此必须明确拒绝，不能静默只保留首张素材。
            String single = !refImages.isEmpty() ? refImages.get(0)
                    : (request != null ? request.getImageUrl() : null);
            if (refImages.size() > 1) {
                log.info("Agnes 视频不支持非关键帧模式的多参考图: total={}", refImages.size());
                throw new ServiceException("此场景仅支持单图");
            }
            if (StringUtils.isNotBlank(single)) {
                body.put(AgnesConstants.JSON_IMAGE, single);
            }
        }
        return body;
    }

    /**
     * 解析尾帧图 URL（首尾帧/关键帧动画用）：兼容业务层下发的多种命名键
     * （{@code lastFrameImageUrl} / {@code end_image_url} / {@code endImageUrl}），任一非空即取。
     */
    private String resolveLastFrameUrl(Map<String, Object> options) {
        String url = getStringOption(options, "lastFrameImageUrl");
        if (StringUtils.isBlank(url)) {
            url = getStringOption(options, "end_image_url");
        }
        if (StringUtils.isBlank(url)) {
            url = getStringOption(options, "endImageUrl");
        }
        return url;
    }

    /**
     * 解析帧率：options.frame_rate / options.fps，范围 1-60，默认 24。
     */
    private int resolveFrameRate(Map<String, Object> options) {
        Integer fr = getIntOption(options, "frame_rate");
        boolean explicitlyConfigured = options != null && options.containsKey("frame_rate");
        if (fr == null && !explicitlyConfigured) {
            fr = getIntOption(options, "fps");
            explicitlyConfigured = options != null && options.containsKey("fps");
        }
        if (fr == null && !explicitlyConfigured) {
            return AgnesConstants.DEFAULT_FRAME_RATE;
        }
        if (fr == null || fr < 1 || fr > 60) {
            log.info("Agnes 视频帧率不符合限制: frameRate={}", fr);
            throw new ServiceException("视频帧率不支持");
        }
        return fr;
    }

    /**
     * 解析帧数：优先 options.num_frames；否则 durationSeconds × frameRate；对齐到合法 8n+1。
     */
    private int resolveNumFrames(MediaVideoGenerateRequest request, int frameRate, Map<String, Object> options) {
        Integer explicit = getIntOption(options, "num_frames");
        if (options != null && options.containsKey("num_frames")) {
            if (explicit == null || explicit < AgnesConstants.MIN_NUM_FRAMES
                    || explicit > AgnesConstants.MAX_NUM_FRAMES || (explicit - 1) % 8 != 0) {
                log.info("Agnes 视频帧数不符合限制: numFrames={}", explicit);
                throw new ServiceException("视频帧数不支持");
            }
            return explicit;
        }
        Integer duration = request == null ? null : request.getDurationSeconds();
        if (duration == null || duration <= 0) {
            return AgnesConstants.DEFAULT_NUM_FRAMES;
        }
        return alignDerivedFrames(Math.multiplyExact((long) duration, frameRate));
    }

    /**
     * 将秒数换算结果向上对齐到 Agnes 合法帧数，不能把用户要求的输出时长静默缩短。
     */
    private int alignDerivedFrames(long desired) {
        long frames = Math.max(AgnesConstants.MIN_NUM_FRAMES,
                Math.addExact(Math.floorDiv(Math.max(desired - 1, 0) + 7, 8) * 8, 1));
        if (frames > AgnesConstants.MAX_NUM_FRAMES) {
            log.info("Agnes 视频时长换算后超过帧数上限: desired={}, aligned={}", desired, frames);
            throw new ServiceException("视频时长超限");
        }
        return Math.toIntExact(frames);
    }

    /**
     * 解析宽高，优先级：。
     */
    private int[] resolveWidthHeight(MediaVideoGenerateRequest request, Map<String, Object> options) {
        Integer w = getIntOption(options, "width");
        Integer h = getIntOption(options, "height");
        boolean hasWidth = options != null && options.containsKey("width");
        boolean hasHeight = options != null && options.containsKey("height");
        if (hasWidth || hasHeight) {
            if (!hasWidth || !hasHeight || w == null || h == null || w <= 0 || h <= 0) {
                log.info("Agnes 视频宽高参数不完整或无效: width={}, height={}", w, h);
                throw new ServiceException("视频尺寸无效");
            }
        }
        if (w != null && h != null && w > 0 && h > 0) {
            return new int[]{w, h};
        }

        // 比例：顶层 aspectRatio 优先，其次 options.aspect_ratio
        String ratio = request == null ? null : request.getAspectRatio();
        if (StringUtils.isBlank(ratio)) {
            ratio = getStringOption(options, "aspect_ratio");
        }

        // 规格档来源：options.resolution（业务层统一档位键，与计费同源）优先，其次历史 options.size
        String sizePreset = getStringOption(options, "resolution");
        if (StringUtils.isBlank(sizePreset)) {
            sizePreset = getStringOption(options, "size");
        }
        int[] byPreset = resolveDimsBySizePreset(sizePreset, ratio);
        if (byPreset != null) {
            return byPreset;
        }
        if (StringUtils.isNotBlank(sizePreset)) {
            log.info("Agnes 视频清晰度不受支持: size={}", sizePreset);
            throw new ServiceException("视频清晰度不支持");
        }

        if (StringUtils.isNotBlank(ratio)) {
            switch (ratio.trim()) {
                case "16:9":
                    return new int[]{1280, 720};
                case "9:16":
                    return new int[]{720, 1280};
                case "1:1":
                    return new int[]{960, 960};
                case "4:3":
                    return new int[]{1024, 768};
                case "3:4":
                    return new int[]{768, 1024};
                default:
                    log.info("Agnes 视频比例不受支持: aspectRatio={}", ratio);
                    throw new ServiceException("视频比例不支持");
            }
        }
        return new int[]{AgnesConstants.DEFAULT_VIDEO_WIDTH, AgnesConstants.DEFAULT_VIDEO_HEIGHT};
    }

    /**
     * 规格档（480P / 720P / 1080P，与官方支持档位一致）+ 比例 → 真实宽高。
     * 规格档决定短边（480 / 720 / 1080），比例决定长短边分配；比例缺省时按默认 16:9 处理。
     * 所有返回值均为 8 的倍数，避免上游对分辨率取整约束报错。识别不到规格档时返回 {@code null} 交由上层降级。
     */
    private int[] resolveDimsBySizePreset(String sizePreset, String ratio) {
        if (StringUtils.isBlank(sizePreset)) {
            return null;
        }
        int shortSide;
        switch (sizePreset.trim().toUpperCase()) {
            case "1080P":
                shortSide = 1080;
                break;
            case "720P":
                shortSide = 720;
                break;
            case "480P":
                shortSide = 480;
                break;
            default:
                // 非已知规格档：交回上层按比例 / 默认处理
                return null;
        }
        // 长边 = 短边 × 比例系数（向上对齐到 8 的倍数）
        String r = StringUtils.isNotBlank(ratio) ? ratio.trim() : "16:9";
        switch (r) {
            case "16:9":
                return new int[]{alignTo8(shortSide * 16 / 9), shortSide};
            case "9:16":
                return new int[]{shortSide, alignTo8(shortSide * 16 / 9)};
            case "1:1":
                return new int[]{shortSide, shortSide};
            case "4:3":
                return new int[]{alignTo8(shortSide * 4 / 3), shortSide};
            case "3:4":
                return new int[]{shortSide, alignTo8(shortSide * 4 / 3)};
            default:
                log.info("Agnes 视频比例不受支持: aspectRatio={}", ratio);
                throw new ServiceException("视频比例不支持");
        }
    }

    /** 向上对齐到 8 的倍数（视频分辨率常见约束）。 */
    private int alignTo8(int value) {
        int remainder = value % 8;
        return remainder == 0 ? value : value + (8 - remainder);
    }

    /**
     * 合并输入图 URL：imageUrl + options.referenceImages + options.images。
     */
    private List<String> resolveReferenceImages(MediaVideoGenerateRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getImageUrl())) {
            result.add(request.getImageUrl());
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            addUrls(result, options.get("referenceImages"));
            addUrls(result, options.get("images"));
        }
        return result;
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
    private String normalizeStatus(String status) {
        if (StringUtils.isBlank(status)) {
            return AgnesConstants.TASK_STATUS_PROCESSING;
        }
        String lower = status.trim().toLowerCase();
        if (AgnesConstants.STATUS_COMPLETED.equals(lower)) {
            return AgnesConstants.TASK_STATUS_SUCCEEDED;
        }
        if (AgnesConstants.STATUS_FAILED.equals(lower)) {
            return AgnesConstants.TASK_STATUS_FAILED;
        }
        // queued / in_progress / 其它 → 处理中
        return AgnesConstants.TASK_STATUS_PROCESSING;
    }

    /**
     * 解析 Agnes seconds（字符串如 "10.0"），向上取整为秒数；无则返回 null。
     */
    private Integer parseSeconds(JsonNode root) {
        String seconds = ProviderResponseHelper.readText(root,
                "seconds", "data.seconds", "metadata.seconds");
        if (StringUtils.isBlank(seconds)) {
            return null;
        }
        try {
            return (int) Math.ceil(Double.parseDouble(seconds.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    private String doPost(String url, String apiKey, String authHeader, String authPrefix, String json) {
        String headerName = StringUtils.isNotBlank(authHeader) ? authHeader : HttpConstants.HEADER_AUTHORIZATION;
        String prefix = authPrefix != null ? authPrefix : HttpConstants.AUTH_BEARER_PREFIX;
        try (HttpResponse response = HttpRequest.post(url)
                .header(headerName, prefix + apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .body(json)
                .timeout(SUBMIT_TIMEOUT_MS)
                .execute()) {
            return response.body();
        }
    }

    private String doGet(String url, String apiKey, String authHeader, String authPrefix) {
        String headerName = StringUtils.isNotBlank(authHeader) ? authHeader : HttpConstants.HEADER_AUTHORIZATION;
        String prefix = authPrefix != null ? authPrefix : HttpConstants.AUTH_BEARER_PREFIX;
        try (HttpResponse response = HttpRequest.get(url)
                .header(headerName, prefix + apiKey)
                .header(HttpConstants.HEADER_CONTENT_TYPE, HttpConstants.CONTENT_TYPE_JSON)
                .timeout(HttpConstants.DEFAULT_TIMEOUT_MS)
                .execute()) {
            return response.body();
        }
    }
    private String resolveEffectiveModel(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        String resolved = ModelCodeResolver.resolveUpstreamModel(modelConfig,
                request == null ? null : request.getModelName());
        return StringUtils.isNotBlank(resolved) ? resolved : AgnesConstants.DEFAULT_VIDEO_MODEL;
    }

    static String buildApiUrl(String baseUrl, String apiSuffix) {
        return ProviderEndpointUtils.buildSubmitUrl(baseUrl, apiSuffix);
    }

    static String buildTaskUrl(String baseUrl, String taskQuerySuffix, String providerTaskId) {
        return ProviderEndpointUtils.buildTaskQueryUrl(baseUrl, taskQuerySuffix, providerTaskId);
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

    private Integer getIntOption(Map<String, Object> options, String key) {
        if (options == null || key == null) {
            return null;
        }
        Object val = options.get(key);
        if (val instanceof Number) {
            try {
                return new BigDecimal(val.toString()).intValueExact();
            } catch (ArithmeticException | NumberFormatException ignore) {
                return null;
            }
        }
        if (val instanceof String && StringUtils.isNotBlank((String) val)) {
            try {
                return new BigDecimal(((String) val).trim()).intValueExact();
            } catch (ArithmeticException | NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
