package com.aid.media.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aid.billing.util.ResolutionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ViduConstants;
import com.aid.media.enums.DispatchMode;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.model.ScheduleStrategy;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.ViduCallbackSupport;
import com.aid.media.provider.KlingCallbackSupport;
import com.aid.media.constants.KlingConstants;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.provider.MinimaxH3CallbackSupport;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.TaskDispatchService;
import com.aid.media.util.TaskLivenessDecider;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.service.IAiModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 统一任务调度中心实现：按供应商/模型策略驱动异步任务的轮询节奏。
 * 支持 CALLBACK_FIRST（回调优先）和 POLL_ONLY（纯轮询）两种模式。
 */
@Slf4j
@Service
public class TaskDispatchServiceImpl implements TaskDispatchService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelTaskConfigurationResolver taskConfigurationResolver;

    /** 独立滚动文件日志：只记录已提交上游但无法确认状态的任务。 */
    private static final Logger UPSTREAM_ANOMALY_LOG = LoggerFactory.getLogger("media-upstream-anomaly");

    /** 存活复核最小间隔，避免 10 秒调度拍重复轰炸异常网关。 */
    private static final int LIVENESS_RECONCILE_MIN_INTERVAL_SECONDS = 60;

    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final IAiModelConfigService aiModelConfigService;
    private final TaskCompletionService taskCompletionService;
    private final List<ImageProviderClient> imageProviderClients;
    private final List<VideoProviderClient> videoProviderClients;
    private final List<TextProviderClient> textProviderClients;
    private final List<com.aid.media.provider.AudioProviderClient> audioProviderClients;

    /** 扇入支撑：用于给「非阻塞出图/出视频」父任务在轮询子任务时续租，防止僵尸回收误判退款。 */
    private final com.aid.rps.queue.MediaGenFanInSupport mediaGenFanInSupport;

    /** 合成分支收口服务：COMPOSE 任务轮询到"处理中"时回写真实进度到 aid_episode_editor */
    private final com.aid.compose.service.ComposeCompletionService composeCompletionService;

    public TaskDispatchServiceImpl(AidMediaTaskMapper aidMediaTaskMapper,
                                   IAiModelConfigService aiModelConfigService,
                                   TaskCompletionService taskCompletionService,
                                   List<ImageProviderClient> imageProviderClients,
                                   List<VideoProviderClient> videoProviderClients,
                                   List<TextProviderClient> textProviderClients,
                                   List<com.aid.media.provider.AudioProviderClient> audioProviderClients,
                                   com.aid.rps.queue.MediaGenFanInSupport mediaGenFanInSupport,
                                   com.aid.compose.service.ComposeCompletionService composeCompletionService) {
        this.aidMediaTaskMapper = aidMediaTaskMapper;
        this.aiModelConfigService = aiModelConfigService;
        this.taskCompletionService = taskCompletionService;
        this.imageProviderClients = imageProviderClients;
        this.videoProviderClients = videoProviderClients;
        this.textProviderClients = textProviderClients;
        this.audioProviderClients = audioProviderClients;
        this.mediaGenFanInSupport = mediaGenFanInSupport;
        this.composeCompletionService = composeCompletionService;
    }

    @Override
    public void initDispatchSchedule(AidMediaTask task, AiModelConfigVo modelConfig) {
        ScheduleStrategy strategy = resolveStrategy(task, modelConfig);
        // 先补齐存活双时钟再做规模抬高：顺序颠倒会让抬高后的生命周阈值成为无进展对账的兜底值，异常任务很晚才进入告警。
        applyLivenessDefaults(strategy, task);
        // 天花板按本单真实规模（时长 × 分辨率）抬高后再冻结，避免用一个常量卡死所有量级的作业
        applyVideoScaledMaxLife(strategy, task);

        String dispatchMode;
        if (Objects.equals(task.getMediaType(), MediaType.TEXT.name())) {
            // 文本同步/流式不走调度中心。
            dispatchMode = DispatchMode.DIRECT.name();
        } else if (Boolean.TRUE.equals(strategy.getSupportsCallback())
            && hasUsableCallbackConfiguration(modelConfig)
            && DispatchMode.CALLBACK_FIRST.name().equals(strategy.getDispatchMode())) {
            dispatchMode = DispatchMode.CALLBACK_FIRST.name();
        } else {
            dispatchMode = DispatchMode.POLL_ONLY.name();
        }

        String snapshotJson = JSONUtil.toJsonStr(strategy);
        task.setDispatchMode(dispatchMode);
        task.setScheduleSnapshotJson(snapshotJson);

        Date now = new Date();
        // 本方法在「上游已受理并回填 providerTaskId」后调用，此刻即存活判定的两个起算点。
        task.setUpstreamAcceptTime(now);
        task.setLastProgressTime(now);
        if (DispatchMode.CALLBACK_FIRST.name().equals(dispatchMode)) {
            // 回调优先模式：设置回调等待截止时间。
            long deadlineMs = now.getTime() + (long) strategy.getFirstPollDelaySeconds() * 1000L;
            task.setCallbackDeadline(new Date(deadlineMs));
            task.setStatus(MediaTaskStatus.WAIT_CALLBACK.name());
            // 同时设一个兜底 nextPollTime（回调截止后开始轮询）。
            task.setNextPollTime(new Date(deadlineMs));
        } else if (DispatchMode.POLL_ONLY.name().equals(dispatchMode)) {
            // 纯轮询模式：设置首次轮询时间。
            long firstPollMs = now.getTime() + (long) strategy.getFirstPollDelaySeconds() * 1000L;
            task.setNextPollTime(new Date(firstPollMs));
            task.setStatus(MediaTaskStatus.WAIT_POLL.name());
        }
        // DIRECT 模式不设调度时间，不进入调度中心。
    }

    /**
     * 补齐存活判定所需的字段：供应商级/模型级 JSON 只配了 {@code maxLifeSeconds} 时，
     * 无进展超时会退化成与生命周期阈值同值，会让失联任务很晚才进入强制对账；
     * 同理漏配 {@code lifeSecondsPerVideoSecond} 会让规模抬高整条失效。
     * 故两者缺失时按媒体类型默认补齐，已配置的一律不覆盖。
     *
     * @param strategy 已解析的调度策略（原地补齐）
     * @param task     媒体任务
     */
    private void applyLivenessDefaults(ScheduleStrategy strategy, AidMediaTask task) {
        ScheduleStrategy defaults = defaultStrategyOf(task.getMediaType());
        if (strategy.getMaxLifeSeconds() <= 0) {
            strategy.setMaxLifeSeconds(defaults.getMaxLifeSeconds());
        }
        if (strategy.getProgressTimeoutSeconds() <= 0) {
            strategy.setProgressTimeoutSeconds(defaults.getProgressTimeoutSeconds());
        }
        if (Objects.isNull(strategy.getLifeSecondsPerVideoSecond())) {
            strategy.setLifeSecondsPerVideoSecond(defaults.getLifeSecondsPerVideoSecond());
        }
    }

    /**
     * 按媒体类型取默认策略。
     *
     * @param mediaType 媒体类型
     * @return 该媒体类型的默认调度策略
     */
    private ScheduleStrategy defaultStrategyOf(String mediaType) {
        if (Objects.equals(mediaType, MediaType.VIDEO.name())) {
            return ScheduleStrategy.defaultVideo();
        }
        if (Objects.equals(mediaType, MediaType.AUDIO.name())) {
            return ScheduleStrategy.defaultAudio();
        }
        return ScheduleStrategy.defaultImage();
    }

    /**
     * 按本单实际规模抬高视频任务的存活天花板（仅抬高，绝不压低）。
     *
     * 规模取自任务已落库的 {@code request_json}，与真正下发给上游的参数同源：
     * 时长来自 {@code durationSeconds}，清晰度档位来自 {@code options.resolution}。
     * 解析不出规模（非视频 / 老快照 / 脏数据）时原样返回，退化为常量口径。
     *
     * @param strategy 已解析的调度策略（原地改写 maxLifeSeconds）
     * @param task     已回填 request_json 的媒体任务
     */
    private void applyVideoScaledMaxLife(ScheduleStrategy strategy, AidMediaTask task) {
        if (Objects.isNull(strategy) || Objects.isNull(task)
            || !Objects.equals(task.getMediaType(), MediaType.VIDEO.name())) {
            return;
        }
        Double perSecond = strategy.getLifeSecondsPerVideoSecond();
        if (Objects.isNull(perSecond) || perSecond <= 0 || StrUtil.isBlank(task.getRequestJson())) {
            return;
        }
        int durationSeconds;
        String resolution;
        try {
            JSONObject request = JSONUtil.parseObj(task.getRequestJson());
            durationSeconds = request.getInt("durationSeconds", 0);
            JSONObject options = request.getJSONObject("options");
            resolution = Objects.isNull(options) ? null : options.getStr("resolution");
        } catch (Exception ex) {
            log.debug("applyVideoScaledMaxLife 解析 request_json 失败，沿用常量天花板, taskId={}", task.getId());
            return;
        }
        if (durationSeconds <= 0) {
            return;
        }
        long scaled = Math.round(durationSeconds * perSecond * resolutionLifeFactor(resolution));
        int baseline = strategy.effectiveMaxLifeSeconds();
        if (scaled <= baseline) {
            return;
        }
        int scaledSeconds = (int) Math.min(scaled, Integer.MAX_VALUE);
        strategy.setMaxLifeSeconds(scaledSeconds);
        log.info("applyVideoScaledMaxLife 按作业规模抬高存活天花板, taskId={}, duration={}s, resolution={}, {}s -> {}s",
            task.getId(), durationSeconds, resolution, baseline, scaledSeconds);
    }

    /**
     * 清晰度档位对出片耗时的放大系数：档位越高，同样时长的出片耗时越长。
     * 档位解析复用计费侧的统一口径，保证「按什么档扣费」与「按什么档给存活预算」同源。
     *
     * @param resolution 清晰度原值（可空）
     * @return 放大系数；无法识别时按 1.0 不放大
     */
    private double resolutionLifeFactor(String resolution) {
        String tier = StrUtil.trimToEmpty(ResolutionUtil.parseTier(resolution)).toLowerCase(Locale.ROOT);
        return switch (tier) {
            case "4k", "2160p" -> 3.0d;
            case "2k", "1440p" -> 2.4d;
            case "1080p" -> 1.8d;
            case "720p" -> 1.3d;
            default -> 1.0d;
        };
    }

    /**
     * Vidu 只有实际下发 callback_url 才能等待回调；未配置地址时直接走轮询。
     * 其他供应商沿用自身已有的回调配置方式，不改变其调度行为。
     */
    private boolean hasUsableCallbackConfiguration(AiModelConfigVo modelConfig) {
        if (Objects.isNull(modelConfig)) {
            return true;
        }
        String providerCode = StrUtil.trimToEmpty(modelConfig.getProviderCode()).toLowerCase(Locale.ROOT);
        if (MinimaxH3Constants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(modelConfig.getProtocol()))) {
            boolean configured = StrUtil.isNotBlank(
                MinimaxH3CallbackSupport.resolveCallbackUrlForSubmission(modelConfig));
            if (!configured) {
                log.info("MiniMax H3 未配置可用回调地址，降级为轮询, modelCode={}", modelConfig.getModelCode());
            }
            return configured;
        }
        if (Objects.equals(KlingConstants.PROVIDER_CODE, providerCode)) {
            boolean configured = StrUtil.isNotBlank(KlingCallbackSupport.resolveCallbackUrlForSubmission(modelConfig));
            if (!configured) {
                log.info("Kling 未配置可用回调地址，降级为轮询, modelCode={}", modelConfig.getModelCode());
            }
            return configured;
        }
        if (!Objects.equals(ViduConstants.PROVIDER_CODE, providerCode)) {
            return true;
        }
        boolean configured = StrUtil.isNotBlank(ViduCallbackSupport.resolveCallbackBaseUrl(modelConfig));
        if (!configured) {
            log.info("Vidu 未配置回调地址，降级为轮询, modelCode={}", modelConfig.getModelCode());
        }
        return configured;
    }

    @Override
    public void initComposeDispatchSchedule(AidMediaTask task) {
        // 合成任务统一回调优先 + 轮询兜底：MPS 支持任务通知回调，回调到达即收口；回调超时转轮询。
        ScheduleStrategy strategy = new ScheduleStrategy();
        strategy.setDispatchMode(DispatchMode.CALLBACK_FIRST.name());
        strategy.setSupportsCallback(Boolean.TRUE);
        // 合成耗时较长（最长 60 分钟成片），首次轮询延迟与节奏放宽，对账阈值留足余量。
        strategy.setFirstPollDelaySeconds(60);
        strategy.setBaseIntervalSeconds(30);
        strategy.setMaxIntervalSeconds(120);
        strategy.setBackoffFactor(1.5);
        strategy.setMaxRetryCount(120);
        strategy.setMaxLifeSeconds(5400);
        // MPS 合成期间每次轮询都会回报真实进度，15 分钟拿不到任何推进即升级对账告警。
        strategy.setProgressTimeoutSeconds(900);

        task.setDispatchMode(DispatchMode.CALLBACK_FIRST.name());
        task.setScheduleSnapshotJson(JSONUtil.toJsonStr(strategy));
        Date acceptedAt = new Date();
        task.setUpstreamAcceptTime(acceptedAt);
        task.setLastProgressTime(acceptedAt);
        long deadlineMs = acceptedAt.getTime() + (long) strategy.getFirstPollDelaySeconds() * 1000L;
        task.setCallbackDeadline(new Date(deadlineMs));
        task.setNextPollTime(new Date(deadlineMs));
        task.setStatus(MediaTaskStatus.WAIT_CALLBACK.name());
    }

    @Override
    public int dispatchDueTasks(int batchSize) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.PROCESSING.name());
        wrapper.le(AidMediaTask::getNextPollTime, new Date());
        wrapper.isNotNull(AidMediaTask::getProviderTaskId);
        wrapper.ne(AidMediaTask::getProviderTaskId, "");
        wrapper.orderByAsc(AidMediaTask::getNextPollTime);
        wrapper.last("LIMIT " + batchSize);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        int dispatched = 0;
        for (AidMediaTask task : tasks) {
            try {
                dispatched += dispatchSingleTask(task) ? 1 : 0;
            } catch (Exception ex) {
                log.warn("dispatchDueTasks 单任务调度异常, taskId={}, error={}", task.getId(), ex.getMessage());
            }
        }
        return dispatched;
    }

    @Override
    public int transitionExpiredCallbacks(int batchSize) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.WAIT_CALLBACK.name());
        wrapper.le(AidMediaTask::getCallbackDeadline, new Date());
        wrapper.orderByAsc(AidMediaTask::getCallbackDeadline);
        wrapper.last("LIMIT " + batchSize);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        int transitioned = 0;
        for (AidMediaTask task : tasks) {
            try {
                // 扇入型任务：回调等待期同样给父任务续租，覆盖 callback-first 且回调截止 > 父任务租约 TTL 的场景
                mediaGenFanInSupport.renewParentLeaseIfFanIn(
                        task.getBizTaskType(), task.getBizTaskId(), task.getParentTaskId());
                transitioned += transitionToPoll(task) ? 1 : 0;
            } catch (Exception ex) {
                log.warn("transitionExpiredCallbacks 异常, taskId={}, error={}", task.getId(), ex.getMessage());
            }
        }
        return transitioned;
    }

    @Override
    public int closeTimeoutTasks(int batchSize) {
        List<AidMediaTask> tasks = loadLivenessCandidates(batchSize);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        Date now = new Date();
        int reconciled = 0;
        for (AidMediaTask task : tasks) {
            ScheduleStrategy strategy = parseStrategySnapshot(task);
            if (Objects.isNull(strategy)) {
                continue;
            }
            // 受理时刻缺失（升级前的存量任务）回落创建时刻；进展时刻缺失回落受理时刻，
            // 叠加快照缺 progressTimeoutSeconds 时回落 maxLifeSeconds，存量任务判定与升级前完全一致。
            Date acceptedAt = Objects.nonNull(task.getUpstreamAcceptTime())
                ? task.getUpstreamAcceptTime() : task.getCreateTime();
            if (Objects.isNull(acceptedAt)) {
                continue;
            }
            Date lastProgressAt = Objects.nonNull(task.getLastProgressTime())
                ? task.getLastProgressTime() : acceptedAt;
            TaskLivenessDecider.Verdict verdict = TaskLivenessDecider.decide(
                now.getTime(), acceptedAt.getTime(), lastProgressAt.getTime(),
                strategy.effectiveMaxLifeSeconds(), strategy.effectiveProgressTimeoutSeconds());
            if (TaskLivenessDecider.Verdict.ALIVE.equals(verdict)) {
                continue;
            }
            reconciled += reconcileAfterLivenessThreshold(task, verdict) ? 1 : 0;
        }
        return reconciled;
    }

    /**
     * 分别按两条存活时钟加载最早候选，并兼容升级前时钟为空的任务。
     *
     * @param batchSize 每条时钟最多扫描数量
     * @return 按任务主键去重后的候选
     */
    private List<AidMediaTask> loadLivenessCandidates(int batchSize) {
        int limit = Math.max(batchSize, 1);
        Map<Long, AidMediaTask> candidates = new LinkedHashMap<>();

        LambdaQueryWrapper<AidMediaTask> acceptedQuery = newLivenessQuery();
        acceptedQuery.isNotNull(AidMediaTask::getUpstreamAcceptTime);
        acceptedQuery.orderByAsc(AidMediaTask::getUpstreamAcceptTime, AidMediaTask::getId);
        acceptedQuery.last("LIMIT " + limit);
        appendCandidates(candidates, aidMediaTaskMapper.selectList(acceptedQuery));

        LambdaQueryWrapper<AidMediaTask> progressQuery = newLivenessQuery();
        progressQuery.isNotNull(AidMediaTask::getLastProgressTime);
        progressQuery.orderByAsc(AidMediaTask::getLastProgressTime, AidMediaTask::getId);
        progressQuery.last("LIMIT " + limit);
        appendCandidates(candidates, aidMediaTaskMapper.selectList(progressQuery));

        LambdaQueryWrapper<AidMediaTask> legacyQuery = newLivenessQuery();
        legacyQuery.and(wrapper -> wrapper.isNull(AidMediaTask::getUpstreamAcceptTime)
                .or().isNull(AidMediaTask::getLastProgressTime));
        legacyQuery.orderByAsc(AidMediaTask::getCreateTime, AidMediaTask::getId);
        legacyQuery.last("LIMIT " + limit);
        appendCandidates(candidates, aidMediaTaskMapper.selectList(legacyQuery));
        return new ArrayList<>(candidates.values());
    }

    private LambdaQueryWrapper<AidMediaTask> newLivenessQuery() {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        // 定时扫描只取存活判定与上游复核所需字段，禁止把请求/响应等大字段带入扫描内存。
        wrapper.select(AidMediaTask::getId,
                AidMediaTask::getUserId,
                AidMediaTask::getProjectId,
                AidMediaTask::getModelName,
                AidMediaTask::getMediaType,
                AidMediaTask::getProtocol,
                AidMediaTask::getProviderTaskId,
                AidMediaTask::getStatus,
                AidMediaTask::getBatchId,
                AidMediaTask::getBizTaskId,
                AidMediaTask::getBizTaskType,
                AidMediaTask::getParentTaskId,
                AidMediaTask::getBillingTraceId,
                AidMediaTask::getFrozenAmount,
                AidMediaTask::getScheduleSnapshotJson,
                AidMediaTask::getUpstreamAcceptTime,
                AidMediaTask::getLastProgressTime,
                AidMediaTask::getCreateTime,
                AidMediaTask::getUpdateTime);
        wrapper.in(AidMediaTask::getStatus,
                MediaTaskStatus.WAIT_POLL.name(),
                MediaTaskStatus.WAIT_CALLBACK.name(),
                MediaTaskStatus.PROCESSING.name());
        wrapper.isNotNull(AidMediaTask::getProviderTaskId);
        wrapper.ne(AidMediaTask::getProviderTaskId, "");
        wrapper.and(item -> item.isNull(AidMediaTask::getUpdateTime)
                .or().le(AidMediaTask::getUpdateTime,
                        new Date(System.currentTimeMillis()
                                - LIVENESS_RECONCILE_MIN_INTERVAL_SECONDS * 1000L)));
        return wrapper;
    }

    private void appendCandidates(Map<Long, AidMediaTask> target, List<AidMediaTask> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (AidMediaTask task : source) {
            if (Objects.nonNull(task) && Objects.nonNull(task.getId())) {
                target.putIfAbsent(task.getId(), task);
            }
        }
    }

    /**
     * 在本地超时后向上游复核终态，并决定续等或收口。
     *
     * @param task    待收口任务
     * @param verdict 本地存活判定结论
     * @return 是否真正收口
     */
    private boolean reconcileAfterLivenessThreshold(AidMediaTask task, TaskLivenessDecider.Verdict verdict) {
        mediaGenFanInSupport.renewParentLeaseIfFanIn(
                task.getBizTaskType(), task.getBizTaskId(), task.getParentTaskId());
        ProviderTaskResult upstream = queryUpstream(task);
        if (Objects.isNull(upstream) || StrUtil.isBlank(upstream.getStatus())) {
            recordUpstreamAnomaly(task, verdict, "上游查询无响应", null);
            return false;
        }
        if (!Boolean.TRUE.equals(upstream.getQuerySuccessful())) {
            if (shouldRefundExpiredMinimaxTerminalAnomaly(task, verdict, upstream)) {
                ProviderTaskResult refundResult = ProviderTaskResult.builder()
                    .status(MediaTaskStatus.FAILED.name())
                    .errorMessage("结算结果超时")
                    .rawResponse(upstream.getRawResponse())
                    .querySuccessful(Boolean.TRUE)
                    .providerStatus(upstream.getProviderStatus())
                    .terminalConfirmed(Boolean.TRUE)
                    .build();
                boolean won = taskCompletionService.completeTask(task.getId(), refundResult);
                log.warn("MiniMax H3 成功终态长期缺少可结算结果，按最大生命周期失败收口并退款, "
                        + "taskId={}, providerTaskId={}, completeTask={}",
                    task.getId(), task.getProviderTaskId(), won);
                return won;
            }
            recordUpstreamAnomaly(task, verdict,
                    StrUtil.blankToDefault(upstream.getErrorMessage(), "上游状态待确认"), upstream);
            return false;
        }
        String upstreamStatus = upstream.getStatus();
        if (MediaTaskStatus.PROCESSING.name().equals(upstreamStatus)) {
            // 官方排队/处理中状态是权威活跃证据，无论本地已等待多久都续约，不自动失败。
            markUpstreamProgress(task);
            log.info("reconcileAfterLivenessThreshold 上游仍在生成，任务续约, taskId={}, providerTaskId={}, verdict={}",
                task.getId(), task.getProviderTaskId(), verdict);
            return false;
        }
        if (!Boolean.TRUE.equals(upstream.getTerminalConfirmed())) {
            recordUpstreamAnomaly(task, verdict, "上游终态未经文档状态确认", upstream);
            return false;
        }
        // 上游已终态：无论成功失败，都用上游结果收口，不再套用超时文案
        boolean won = taskCompletionService.completeTask(task.getId(), upstream);
        log.info("reconcileAfterLivenessThreshold 对账确认上游终态, taskId={}, upstreamStatus={}, verdict={}, completeTask={}",
            task.getId(), upstreamStatus, verdict, won);
        // 成功终态提交后由 MediaTaskCompletedEvent 单向驱动 OSS 持久化，调度服务不反向依赖媒体生成服务。
        return true;
    }

    static boolean shouldRefundExpiredMinimaxTerminalAnomaly(AidMediaTask task,
                                                               TaskLivenessDecider.Verdict verdict,
                                                               ProviderTaskResult upstream) {
        return task != null && upstream != null
            && TaskLivenessDecider.Verdict.EXPIRED.equals(verdict)
            && MinimaxH3Constants.PROTOCOL_VIDEO.equals(task.getProtocol())
            && MinimaxH3Constants.STATUS_SUCCEEDED.equalsIgnoreCase(upstream.getProviderStatus())
            && !Boolean.TRUE.equals(upstream.getQuerySuccessful());
    }

    @Override
    public void markUpstreamProgress(AidMediaTask task) {
        if (Objects.isNull(task) || Objects.isNull(task.getId())) {
            return;
        }
        Date now = new Date();
        LambdaUpdateWrapper<AidMediaTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AidMediaTask::getId, task.getId());
        // 仅前移在途任务：终态任务不得被回写，否则会污染已收口的时间线。
        wrapper.in(AidMediaTask::getStatus,
            MediaTaskStatus.WAIT_POLL.name(),
            MediaTaskStatus.WAIT_CALLBACK.name(),
            MediaTaskStatus.PROCESSING.name());
        wrapper.set(AidMediaTask::getLastProgressTime, now);
        wrapper.set(AidMediaTask::getErrorMessage, null);
        wrapper.set(AidMediaTask::getUpdateBy,
            Objects.nonNull(task.getUserId()) ? String.valueOf(task.getUserId()) : "");
        wrapper.set(AidMediaTask::getUpdateTime, now);
        try {
            aidMediaTaskMapper.update(null, wrapper);
        } catch (Exception ex) {
            // 进展登记失败只会让任务早一轮被判无进展，不阻断调度主流程。
            log.warn("markUpstreamProgress 登记失败, taskId={}, error={}", task.getId(), ex.getMessage());
        }
    }

    @Override
    public void scheduleImmediatePoll(AidMediaTask task) {
        if (Objects.isNull(task) || Objects.isNull(task.getId())) {
            return;
        }
        Date now = new Date();
        LambdaUpdateWrapper<AidMediaTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AidMediaTask::getId, task.getId());
        wrapper.in(AidMediaTask::getStatus,
                MediaTaskStatus.WAIT_POLL.name(),
                MediaTaskStatus.WAIT_CALLBACK.name(),
                MediaTaskStatus.PROCESSING.name());
        wrapper.set(AidMediaTask::getStatus, MediaTaskStatus.WAIT_POLL.name());
        wrapper.set(AidMediaTask::getNextPollTime, now);
        wrapper.set(AidMediaTask::getUpdateBy,
                Objects.nonNull(task.getUserId()) ? String.valueOf(task.getUserId()) : "");
        wrapper.set(AidMediaTask::getUpdateTime, now);
        aidMediaTaskMapper.update(null, wrapper);
    }

    @Override
    public boolean scheduleImmediatePollIfWaitingCallback(AidMediaTask task) {
        if (Objects.isNull(task) || Objects.isNull(task.getId())) {
            return false;
        }
        Date now = new Date();
        LambdaUpdateWrapper<AidMediaTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AidMediaTask::getId, task.getId());
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.WAIT_CALLBACK.name());
        wrapper.set(AidMediaTask::getStatus, MediaTaskStatus.WAIT_POLL.name());
        wrapper.set(AidMediaTask::getNextPollTime, now);
        wrapper.set(AidMediaTask::getUpdateBy,
                Objects.nonNull(task.getUserId()) ? String.valueOf(task.getUserId()) : "");
        wrapper.set(AidMediaTask::getUpdateTime, now);
        return aidMediaTaskMapper.update(null, wrapper) > 0;
    }

    /**
     * 记录“已有上游任务ID但本次无法确认状态”的特殊标记与独立日志。
     * 只更新错误提示和审计时间，不前移 last_progress_time、不改变状态、不退款、不释放并发。
     */
    private void recordUpstreamAnomaly(AidMediaTask task, TaskLivenessDecider.Verdict verdict,
                                       String reason, ProviderTaskResult upstream) {
        String safeReason = MediaTaskPayloadSanitizer.sanitizeForStorage(
                "上游状态待确认:" + StrUtil.blankToDefault(reason, "查询异常"));
        if (safeReason.length() > 240) {
            safeReason = safeReason.substring(0, 240);
        }
        LambdaUpdateWrapper<AidMediaTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AidMediaTask::getId, task.getId());
        wrapper.in(AidMediaTask::getStatus,
                MediaTaskStatus.WAIT_POLL.name(),
                MediaTaskStatus.WAIT_CALLBACK.name(),
                MediaTaskStatus.PROCESSING.name());
        wrapper.set(AidMediaTask::getErrorMessage, safeReason);
        wrapper.set(AidMediaTask::getUpdateBy,
                Objects.nonNull(task.getUserId()) ? String.valueOf(task.getUserId()) : "");
        wrapper.set(AidMediaTask::getUpdateTime, new Date());
        aidMediaTaskMapper.update(null, wrapper);

        UPSTREAM_ANOMALY_LOG.warn(
                "taskId={} providerTaskId={} projectId={} userId={} bizTaskType={} bizTaskId={} batchId={} "
                        + "model={} protocol={} status={} providerStatus={} verdict={} acceptTime={} lastProgressTime={} "
                        + "billingTraceId={} frozenAmount={} reason={}",
                task.getId(), task.getProviderTaskId(), task.getProjectId(), task.getUserId(),
                task.getBizTaskType(), task.getBizTaskId(), task.getBatchId(), task.getModelName(), task.getProtocol(),
                task.getStatus(), Objects.isNull(upstream) ? null : upstream.getProviderStatus(), verdict,
                task.getUpstreamAcceptTime(), task.getLastProgressTime(), task.getBillingTraceId(),
                task.getFrozenAmount(), safeReason);
    }

    @Override
    public int closeStaleUnsubmittedTasks(int batchSize) {
        // 未提交上游的僵尸任务无调度快照（schedule_snapshot_json 为空），用固定兜底时限判定。
        // 取 2 小时：必须显著大于单次 HTTP 提交超时上限（SubmitTimeoutResolver 最大 3600s），
        // 否则可能把「提交仍在合法等待中」的任务误判为僵尸并退款（与 R11 超时分层一致：deadline > submitTimeout）。
        final long unsubmittedMaxLifeMs = 7200L * 1000L;
        final long composeUnsubmittedMaxLifeMs = 600L * 1000L;
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        // QUEUED 只表示等待并发槽，50 个分镜在低并发下合法等待可能超过数小时，不能当僵尸关闭。
        // 这里只处理已占槽但提交进程异常中断的 PENDING。
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name());
        // 仅清「未提交上游」的：providerTaskId 为空（NULL 或空串）。
        wrapper.and(w -> w.isNull(AidMediaTask::getProviderTaskId)
            .or().eq(AidMediaTask::getProviderTaskId, ""));
        wrapper.and(w -> w.isNull(AidMediaTask::getProtocol)
            .or().ne(AidMediaTask::getProtocol, "dmc-h3-video"));
        // 本地 FFmpeg 会定期刷新 updateTime；按最近活动时间排序，避免长任务长期占据扫描窗口，
        // 让真正失联的 PENDING 任务优先进入补偿。
        wrapper.orderByAsc(AidMediaTask::getUpdateTime, AidMediaTask::getId);
        wrapper.last("LIMIT " + batchSize);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        Date now = new Date();
        int closed = 0;
        for (AidMediaTask task : tasks) {
            // DMC 的首次 POST 可能已受理却未返回 task_id；没有上游证据时不能自动失败退款。
            if ("dmc-h3-video".equalsIgnoreCase(task.getProtocol())) {
                continue;
            }
            Date pendingSince = Objects.nonNull(task.getUpdateTime()) ? task.getUpdateTime() : task.getCreateTime();
            if (Objects.isNull(pendingSince)) {
                continue;
            }
            boolean compose = com.aid.compose.ComposeConstants.MEDIA_TYPE_COMPOSE.equals(task.getMediaType());
            long maxLifeMs = compose ? composeUnsubmittedMaxLifeMs : unsubmittedMaxLifeMs;
            if (now.getTime() - pendingSince.getTime() > maxLifeMs) {
                boolean won = compose
                        ? taskCompletionService.requeueUnsubmittedTask(task.getId())
                        : taskCompletionService.closeUnsubmittedTask(task.getId(), "任务超时: 提交未完成");
                log.info("closeStaleUnsubmittedTasks 处理未提交僵尸任务, taskId={}, requeued={}, won={}",
                        task.getId(), compose, won);
                if (won) {
                    closed++;
                }
            }
        }
        return closed;
    }
    /**
     * 调度单个任务：查询上游并推进状态。
     */
    private boolean dispatchSingleTask(AidMediaTask task) {
        ScheduleStrategy strategy = parseStrategySnapshot(task);
        if (Objects.isNull(strategy)) {
            return false;
        }

        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        // 重试计数只控制退避增长，不再参与终态判定；生成终态必须由上游官方状态确认。
        // 达到上限后保持最大轮询间隔继续查询，避免长尾任务仅因轮询次数用尽被提前失败。
        int retryBackoffCeiling = Math.max(strategy.getMaxRetryCount(), 1);
        int backoffAttempt = Math.min(retryCount, retryBackoffCeiling - 1);
        int nextRetryCount = retryCount >= retryBackoffCeiling ? retryCount : retryCount + 1;
        int nextInterval = calculateNextInterval(strategy, backoffAttempt);
        Date nextPollTime = new Date(System.currentTimeMillis() + (long) nextInterval * 1000L);
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";

        LambdaUpdateWrapper<AidMediaTask> casWrapper = new LambdaUpdateWrapper<>();
        casWrapper.eq(AidMediaTask::getId, task.getId());
        casWrapper.eq(AidMediaTask::getNextPollTime, task.getNextPollTime());
        casWrapper.set(AidMediaTask::getNextPollTime, nextPollTime);
        casWrapper.set(AidMediaTask::getRetryCount, nextRetryCount);
        casWrapper.set(AidMediaTask::getUpdateBy, userStr);
        casWrapper.set(AidMediaTask::getUpdateTime, new Date());

        int rows = aidMediaTaskMapper.update(null, casWrapper);
        if (rows == 0) {
            // CAS 失败：其他实例/线程已抢占此任务。
            return false;
        }

        // 扇入型任务（非阻塞出图/出视频）：轮询到该子任务即视为父任务"仍在途"，给父任务续租，
        // 防止「非阻塞提交后无线程续租 → 父任务租约过期被僵尸回收误判失败 + 退款」。
        mediaGenFanInSupport.renewParentLeaseIfFanIn(
                task.getBizTaskType(), task.getBizTaskId(), task.getParentTaskId());

        ProviderTaskResult taskResult = queryUpstream(task);
        if (Objects.isNull(taskResult)) {
            // 上游无响应，保持当前状态等待下次调度。
            recordUpstreamAnomaly(task, TaskLivenessDecider.Verdict.ALIVE, "上游查询无响应", null);
            return false;
        }

        if (!Boolean.TRUE.equals(taskResult.getQuerySuccessful())) {
            recordUpstreamAnomaly(task, TaskLivenessDecider.Verdict.ALIVE,
                    StrUtil.blankToDefault(taskResult.getErrorMessage(), "上游状态待确认"), taskResult);
            return false;
        }

        if (MediaTaskStatus.PROCESSING.name().equals(taskResult.getStatus())) {
            // 上游明确回报仍在推进：登记一次进展，把无进展时钟推回去。
            // 缺了这一步，last_progress_time 会永远停在受理时刻，出片正常的长任务照样在
            // progressTimeoutSeconds 到点时被判「上游无进展」杀掉并退款——即本次要根治的误杀。
            markUpstreamProgress(task);
            // COMPOSE 任务仍处理中：把 MPS 真实进度回写 aid_episode_editor.export_progress（展示增强，失败不阻断）
            if (Objects.equals(task.getMediaType(), com.aid.compose.ComposeConstants.MEDIA_TYPE_COMPOSE)) {
                composeCompletionService.onProgress(task, taskResult.getProgress());
            }
            return false;
        }

        if (!Boolean.TRUE.equals(taskResult.getTerminalConfirmed())) {
            recordUpstreamAnomaly(task, TaskLivenessDecider.Verdict.ALIVE,
                    "上游终态未经文档状态确认", taskResult);
            return false;
        }

        taskCompletionService.completeTask(task.getId(), taskResult);
        // CAS 赢家发布终态事件，OSS 持久化由事件监听器统一处理。
        return true;
    }

    /**
     * 将回调超时任务转为轮询模式。
     */
    private boolean transitionToPoll(AidMediaTask task) {
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";

        // CAS 更新状态：WAIT_CALLBACK → WAIT_POLL。
        LambdaUpdateWrapper<AidMediaTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AidMediaTask::getId, task.getId());
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.WAIT_CALLBACK.name());
        wrapper.set(AidMediaTask::getStatus, MediaTaskStatus.WAIT_POLL.name());
        // 设置 nextPollTime 为当前时间，立即开始轮询。
        wrapper.set(AidMediaTask::getNextPollTime, new Date());
        wrapper.set(AidMediaTask::getUpdateBy, userStr);
        wrapper.set(AidMediaTask::getUpdateTime, new Date());

        int rows = aidMediaTaskMapper.update(null, wrapper);
        if (rows > 0) {
            log.info("transitionToPoll 回调超时转轮询, taskId={}", task.getId());
        }
        return rows > 0;
    }

    /**
     * 查询上游任务状态。
     */
    private ProviderTaskResult queryUpstream(AidMediaTask task) {
        try (com.aid.diagnostics.DiagnosticCapture.TaskScope scope = com.aid.diagnostics.DiagnosticCapture.task(task)) {
            return queryUpstreamCaptured(task);
        }
    }

    private ProviderTaskResult queryUpstreamCaptured(AidMediaTask task) {
        try {
            //    MPS 不在 aid_ai_model，故必须在 selectByModelCode 之前短路，避免因模型缺失误判为「无法查询」。
            if (Objects.equals(task.getMediaType(), com.aid.compose.ComposeConstants.MEDIA_TYPE_COMPOSE)) {
                VideoProviderClient composeClient = resolveVideoClient(task.getProtocol());
                if (Objects.isNull(composeClient)) {
                    log.warn("queryUpstream COMPOSE 未命中 MPS Provider, taskId={}, protocol={}",
                        task.getId(), task.getProtocol());
                    return null;
                }
                return composeClient.query(null, task.getProviderTaskId());
            }
            AiModelConfigVo modelConfig = taskConfigurationResolver.resolve(task);
            if (Objects.isNull(modelConfig)) {
                log.warn("queryUpstream 模型配置缺失, taskId={}, modelName={}", task.getId(), task.getModelName());
                return null;
            }

            if (Objects.equals(task.getMediaType(), MediaType.IMAGE.name())) {
                ImageProviderClient client = resolveImageClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            } else if (Objects.equals(task.getMediaType(), MediaType.VIDEO.name())) {
                VideoProviderClient client = resolveVideoClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            } else if (Objects.equals(task.getMediaType(), MediaType.AUDIO.name())) {
                com.aid.media.provider.AudioProviderClient client = resolveAudioClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            } else {
                TextProviderClient client = resolveTextClient(task.getProtocol());
                if (Objects.isNull(client)) {
                    return null;
                }
                return client.query(modelConfig, task.getProviderTaskId());
            }
        } catch (Exception ex) {
            log.warn("queryUpstream 查询上游异常, taskId={}, error={}", task.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 计算下次轮询间隔（指数退避 + 上限封顶）。
     */
    private int calculateNextInterval(ScheduleStrategy strategy, int retryCount) {
        double base = strategy.getBaseIntervalSeconds();
        double factor = strategy.getBackoffFactor();
        int max = strategy.getMaxIntervalSeconds();

        double interval = base * Math.pow(factor, retryCount);
        return (int) Math.min(interval, max);
    }

    /**
     * 解析调度策略：模型级 > 供应商级 > 媒体类型默认。
     * 模型级策略覆写轮询节奏参数，但 supportsCallback 默认继承供应商级。
     * 容错：{@code schedule_strategy_json} 列若被脏值污染（如误写成 UUID / 空白 / 非 JSON 文本），
     * 视为"未配置"静默跳过，不打 WARN 噪音；只有「以 {@code {} 开头但格式破坏的真 JSON」才告警，
     * 用以区分"运营没配"与"配置坏了"两种语义。
     */
    private ScheduleStrategy resolveStrategy(AidMediaTask task, AiModelConfigVo modelConfig) {
        if (modelConfig != null && looksLikeJsonObject(modelConfig.getScheduleStrategyJson())) {
            try {
                ScheduleStrategy strategy = JSONUtil.toBean(modelConfig.getScheduleStrategyJson(), ScheduleStrategy.class);
                if (Objects.nonNull(strategy) && StrUtil.isNotBlank(strategy.getDispatchMode())) {
                    // 模型级 JSON 未显式设置 supportsCallback 时，继承供应商级配置。
                    mergeProviderCallbackCapability(strategy, modelConfig);
                    log.debug("resolveStrategy 使用模型级策略, modelCode={}", modelConfig.getModelCode());
                    return strategy;
                }
            } catch (Exception ex) {
                log.warn("resolveStrategy 解析模型级策略异常, modelCode={}", modelConfig.getModelCode(), ex);
            }
        }

        if (modelConfig != null && looksLikeJsonObject(modelConfig.getProviderScheduleStrategyJson())) {
            try {
                ScheduleStrategy strategy = JSONUtil.toBean(modelConfig.getProviderScheduleStrategyJson(), ScheduleStrategy.class);
                if (Objects.nonNull(strategy) && StrUtil.isNotBlank(strategy.getDispatchMode())) {
                    // 供应商表总开关关闭时强制禁用回调。
                    mergeProviderCallbackCapability(strategy, modelConfig);
                    log.debug("resolveStrategy 使用供应商级策略, providerCode={}", modelConfig.getProviderCode());
                    return strategy;
                }
            } catch (Exception ex) {
                log.warn("resolveStrategy 解析供应商策略异常, providerCode={}", modelConfig.getProviderCode(), ex);
            }
        }

        ScheduleStrategy fallback = defaultStrategyOf(task.getMediaType());
        mergeProviderCallbackCapability(fallback, modelConfig);
        return fallback;
    }

    /**
     * 轻量启发式：判断 schedule_strategy_json 列值是否"看起来像 JSON 对象"。
     * 仅做形态校验（首字符是否 {@code {}），不做 JSON 完整性校验——后者交给 {@code JSONUtil.toBean}。
     * 用于跳过运营误填的 UUID / 普通字符串 / 空白等脏值，避免把"配置缺失"刷成 WARN。
     */
    private static boolean looksLikeJsonObject(String raw) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String trimmed = raw.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }


    /**
     * 将供应商级 supportsCallback 能力合并到策略对象。
     * 供应商总开关拥有最高否决权；供应商开启后，模型可进一步关闭回调。
     */
    private void mergeProviderCallbackCapability(ScheduleStrategy strategy, AiModelConfigVo modelConfig) {
        if (modelConfig == null) {
            return;
        }
        if (!ViduCallbackSupport.isCallbackEnabled(modelConfig)) {
            strategy.setSupportsCallback(Boolean.FALSE);
            return;
        }
        if (strategy.getSupportsCallback() == null) {
            strategy.setSupportsCallback(Boolean.TRUE);
        }
    }

    /**
     * 从任务快照解析调度策略。
     */
    private ScheduleStrategy parseStrategySnapshot(AidMediaTask task) {
        if (StrUtil.isBlank(task.getScheduleSnapshotJson())) {
            return null;
        }
        try {
            return JSONUtil.toBean(task.getScheduleSnapshotJson(), ScheduleStrategy.class);
        } catch (Exception ex) {
            log.warn("parseStrategySnapshot 解析失败, taskId={}", task.getId(), ex);
            return null;
        }
    }

    /**
     * 按 protocol 查找图片 provider。
     */
    private ImageProviderClient resolveImageClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<ImageProviderClient> candidates = imageProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 按 protocol 查找视频 provider。
     */
    private VideoProviderClient resolveVideoClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<VideoProviderClient> candidates = videoProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 按 protocol 查找文本 provider。
     */
    private TextProviderClient resolveTextClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<TextProviderClient> candidates = textProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 按 protocol 查找音频 provider。
     */
    private com.aid.media.provider.AudioProviderClient resolveAudioClient(String protocol) {
        if (StrUtil.isBlank(protocol)) {
            return null;
        }
        List<com.aid.media.provider.AudioProviderClient> candidates = audioProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
