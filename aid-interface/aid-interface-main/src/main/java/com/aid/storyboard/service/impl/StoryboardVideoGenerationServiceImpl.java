package com.aid.storyboard.service.impl;

import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.common.error.TaskErrorSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidGenRecord;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidGenRecordService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.exception.ServiceException;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.service.BillingPreHoldCalculationService;
import com.aid.billing.service.BillingQuoteAssembler;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.utils.DateUtils;
import com.aid.common.utils.image.ImageUrlValidator;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.enums.CreationModeEnum;
import com.aid.enums.GenTypeEnum;
import com.aid.media.constants.KlingConstants;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.PreparedMediaBillingInput;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.dto.ReferenceVideoInput;
import com.aid.media.provider.KlingVideoRequestBuilder;
import com.aid.media.provider.ReferenceImageLimiter;
import com.aid.media.resolver.ReferenceVideoRecordResolver;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaBillingQuotePreparer;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.media.util.ModelCapabilityValidator;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.notify.wechat.service.IWechatNotifyService;
import com.aid.rps.queue.MediaGenFanInSupport;
import com.aid.rps.queue.BatchTaskLogicalType;
import com.aid.rps.queue.BatchTaskSlotReservation;
import com.aid.rps.queue.BatchTaskSlotService;
import com.aid.rps.queue.BatchParentSubmissionGuard;
import com.aid.rps.queue.BatchTaskExecutionRejectedException;
import com.aid.rps.resolver.StoryboardPromptAudioReferenceResolver;
import com.aid.rps.resolver.StoryboardRoleAudioReferenceResolver;
import com.aid.rps.service.IRpsFormImageBusinessService.ReferenceEnablePlan;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.StoryboardVideoGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoFromImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoEdgeGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGridGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGenerateVO;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;
import com.aid.storyboard.support.StoryboardDurationResolver;
import com.aid.storyboard.support.StoryboardVideoAdvancedOptions;
import com.aid.storyboard.support.StoryboardDurationResolver.Resolution;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import com.aid.storyboard.video.VideoReferencePlanner;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 分镜图生视频服务实现（父任务异步）。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardVideoGenerationServiceImpl implements IStoryboardVideoGenerationService
{
    /** 模型池功能编码（aid_ai_model_func_config.func_code），独立于智能体 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO = "main_storyboard_video";

    /** 图生方向出片专用视频模型池：仅放支持图片输入的视频模型，从源头约束图生视频出片模型 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_IMAGE = "main_storyboard_video_image";

    /** 多参方向 pro（专业版）专属模型池：仅 creation_mode=pro 的多参出片走此池，其余创作模式仍走通用池 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO = "main_storyboard_video_multi_pro";

    /** 首尾帧出片专用视频模型池：仅放支持尾帧输入的视频模型，不按创作模式分流 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_EDGE = "main_storyboard_video_edge";

    /** 宫格出片专用视频模型池：仅 creation_mode=auto_grid 的宫格出片走此池 */
    private static final String FUNC_CODE_STORYBOARD_VIDEO_GRID = "main_storyboard_video_grid";

    /** 任务类型（必须与 AssetExtractServiceImpl 常量保持一致） */
    private static final String TASK_TYPE_STORYBOARD_VIDEO_GENERATE = "storyboard_video_generate";

    /** 业务任务类型（写入 aid_media_task.biz_task_type） */
    private static final String BIZ_TASK_TYPE = "storyboard_video_generate";

    /** 任务快照键：用户显式选择的配音记录 ID */
    private static final String SNAPSHOT_KEY_AUDIO_RECORD_IDS = "referenceAudioRecordIds";

    /** 任务快照键：用户显式选择的上传参考音频 ID */
    private static final String SNAPSHOT_KEY_REFERENCE_AUDIO_IDS = "referenceAudioIds";

    /** 任务快照键：用户显式选择的参考视频记录 ID */
    private static final String SNAPSHOT_KEY_VIDEO_RECORD_IDS = "referenceVideoRecordIds";

    /** 任务状态枚举（与 AidExtractTask 字符串字段对齐） */
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    /** 排队中（已入队等待并发名额）：与 PENDING/PROCESSING 同属"活跃"，重复出片检测必须纳入 */
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    /** 部分镜头成功 + 至少一镜头/条失败的终态（支持续生） */
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";

    /** 出片方向标识（写入 input_snapshot，续生时据此选 prepare 链路） */
    private static final String DIRECTION_MULTI = "multi";
    private static final String DIRECTION_IMAGE = "image";
    /** 首尾帧出片方向：首图 + 尾图生成视频 */
    private static final String DIRECTION_EDGE = "edge";
    /** 宫格出片方向：以分镜宫格图为参考的图生视频（仅 auto_grid） */
    private static final String DIRECTION_GRID = "grid";

    /** 期望模型类型 */
    private static final String MODEL_TYPE_VIDEO = "video";

    /** 删除标志：正常 */
    private static final String DEL_FLAG_NORMAL = "0";

    /** 生成记录选中状态 */
    private static final int SELECTED_NO = 0;
    private static final int SELECTED_YES = 1;

    /** gen_type 常量：图生视频 */
    private static final String GEN_TYPE_I2V = "i2v";

    /** gen_type 常量：首尾帧视频（与 GenTypeEnum.EDGE 对齐） */
    private static final String GEN_TYPE_EDGE = "edge";

    /**
     * 分镜原始视频（video 类）单选互斥范围：出片自动设为主视频时，同分镜其余原始视频记录的选中一并重置。
     * compose 类不参与原始视频之间的互斥；原始视频来源发生变化时，其旧合成派生结果会在更新主视频时统一失效。
     */
    private static final List<String> VIDEO_MUTEX_GEN_TYPES = List.of(
            GenTypeEnum.I2V.getValue(), GenTypeEnum.MULTI.getValue(), GenTypeEnum.EDGE.getValue(),
            GenTypeEnum.UPLOAD_VIDEO.getValue());

    /** 视频提示词业务侧最大长度。 */
    private static final int MAX_PROMPT_LENGTH = 20_000;

    /** 用户补充文本最大长度（超出拒绝） */
    private static final int MAX_USER_INPUT_LENGTH = 500;

    /** 出片数量上下限 */
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 4;

    /** 单次批量出片最大镜头数：限制批量规模，避免父任务顺序执行总时长过长超出执行租约 */
    private static final int MAX_BATCH_SHOTS = 20;

    /**
     * 运行批次号上限（独占编码位 [0,1000)）：bizTaskId 按父任务编码段 + 稳定镜头序号*1000 + 逻辑槽位确定性编码
     * （不含 runNo）。runNo 仅持久化于 input_snapshot 作「重试超限」守卫——续生到该上限直接拒绝，不 clamp。
     */
    private static final int MAX_RUN_NO = 999;

    /** 能力未声明时采用的参考图业务安全上限；模型明确配置优先。 */
    private static final int MAX_REFERENCE_IMAGES = 8;

    /** {@code @图片N[name]} 占位正则（与 {@link } 完全一致） */
    private static final Pattern REF_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]+)\\]");

    /** Redis 防重锁 Key 前缀（与 AssetExtractServiceImpl.FORM_LOCK_PREFIX 同命名空间） */
    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";

    /** 防重锁 TTL（秒）：90 分钟，覆盖 count 最大 4 条 × 1200s 单条视频轮询 + 余量 */
    private static final long FORM_LOCK_TTL_SECONDS = 90L * 60L;

    /**
     * 锁「在建宽限期」（毫秒）：SETNX 成功后到父任务入库前有一段加载/解析窗口，
     * 此期间锁值时间戳新于宽限期的，视为「并发在建」而非泄漏，不予抢占，避免删掉对方有效锁导致重复受理。
     */
    private static final long LOCK_INFLIGHT_GRACE_MS = 120_000L;

    /** 视频中间态白名单（用于异步首响应判活：非此白名单且非 SUCCEEDED 才判失败）。
     *  必须覆盖媒体调度的全部在飞态——含 WAIT_CALLBACK（callback-first 模型仅返回 providerTaskId 时进入），
     *  否则 callback-first 视频模型首响应会被误判为失败、过早记失败。轮询循环靠"非 SUCCEEDED/FAILED 即继续"驱动，不依赖本白名单。 */
    private static final Set<String> VIDEO_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    /** 批量出片 SSE 进度分段：排队 admitted 固定 5，提交阶段 5→30，扇入等待阶段 30→99，保证整体单调不回退 */
    private static final int PROGRESS_SUBMIT_BASE = 5;
    private static final int PROGRESS_SUBMIT_SPAN = 25;
    private static final int PROGRESS_FANIN_BASE = 30;
    private static final int PROGRESS_FANIN_SPAN = 69;
    private static final int PROGRESS_CAP = 99;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 镜头锁 compare-and-delete Lua 脚本：仅当锁当前值 == ARGV[1] 才删除，原子，杜绝误删他人/新锁。
     * 走 {@code redisCache.redisTemplate.execute(...)}，ARGV 与存值经同一序列化器，比较一致（与 EXTRACT 锁同套路）。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SHOT_LOCK_RELEASE_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /**
     * 镜头锁 compare-and-expire Lua 脚本：仅当锁当前值 == ARGV[1] 才续租。
     * TTL 直接写进脚本（整数字面量），不经 ARGV 传入——本类用项目默认 RedisTemplate（FastJson2 value 序列化器），
     * 若把 TTL 作为 ARGV 传入会被序列化成带引号字符串（如 {@code "5400"}），导致 {@code EXPIRE} 报
     * "value is not an integer"。ARGV[1] 的 token 比较不受影响（存值与 ARGV 经同一序列化器，比较一致）。
     * TTL 取 {@link #FORM_LOCK_TTL_SECONDS}（在本字段之前声明，拼接时已完成初始化），随常量自动同步。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> SHOT_LOCK_RENEW_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], "
                            + FORM_LOCK_TTL_SECONDS + ") else return 0 end",
                    Long.class);

    /** 镜头锁持有凭证：key + token，所有释放/续租必须基于 token 走原子 CAS，杜绝误删/误续他人锁。 */
    private static final class ShotLock
    {
        final String key;
        final String token;

        ShotLock(String key, String token)
        {
            this.key = key;
            this.token = token;
        }
    }

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private IAidStoryboardService aidStoryboardService;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    @Autowired
    private MediaBillingQuotePreparer mediaBillingQuotePreparer;

    @Autowired
    private BillingPreHoldCalculationService billingPreHoldCalculationService;

    @Autowired
    private BillingQuoteAssembler billingQuoteAssembler;

    /** 媒体任务终态载荷二阶段压缩：业务上下文消费成功后再清理。 */
    @Autowired
    private MediaTaskArchiveService mediaTaskArchiveService;

    /** 参考素材富化解析所需：form_image（@图片N 命中行）/ form（形态→主资产）/ 主资产（名称/类型）。 */
    @Autowired
    private IAidRolePropSceneFormImageService rolePropSceneFormImageService;

    @Autowired
    private IAidRolePropSceneFormService rolePropSceneFormService;

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    @Autowired
    private StoryboardPromptAudioReferenceResolver promptAudioReferenceResolver;

    /** 自动提示词角色音频占位兜底；历史数据出片时按台词临时自愈，不改写用户原文。 */
    @Autowired
    private StoryboardRoleAudioReferenceResolver roleAudioReferenceResolver;

    /** 分厂商 / 分模型参考装配策略路由器（多模态差异化处理 + 可扩展）。 */
    @Autowired
    private VideoReferencePlanner videoReferencePlanner;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    @Autowired
    private IAidGenRecordService aidGenRecordService;

    @Autowired
    private ReferenceVideoRecordResolver referenceVideoRecordResolver;

    /** aid_media_task 无独立 Service，沿用现有直读 Mapper 的统一做法。
     *  续生 durable 复用用：按确定性 bizTaskId 反查已成功媒体任务，不受媒体层 1 小时幂等窗限制。 */
    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    @Autowired
    private IAssetExtractService assetExtractService;

    @Autowired
    private BatchTaskSlotService batchTaskSlotService;

    @Autowired
    private BatchParentSubmissionGuard batchParentSubmissionGuard;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    @Autowired
    private AssetExtractSseManager sseManager;

    @Autowired
    private com.aid.common.aid.oss.util.MediaUrlResolver mediaUrlResolver;

    /** 引用即启用：生视频前把"已存在但未启用"的被引用资产自动设为 is_use=1 */
    @Autowired
    private com.aid.rps.service.IRpsFormImageBusinessService rpsFormImageBusinessService;

    /** 创作模式解析：剧集 creation_mode 优先，回退项目 default_creation_mode（多参 pro 分流用）。 */
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;

    @Autowired
    private IAidComicProjectService aidComicProjectService;

    @Autowired
    private IWechatNotifyService wechatNotifyService;

    /** 账户服务（建任务前的余额只读预检） */
    @Autowired
    private com.aid.billing.service.IAccountUpdateService accountUpdateService;

    @Override
    public StoryboardVideoGenerateVO generateVideo(StoryboardVideoGenerateRequest request, Long userId)
    {
        return generateVideo(request, userId, false);
    }

    @Override
    public StoryboardVideoGenerateVO generateVideo(StoryboardVideoGenerateRequest request, Long userId,
            boolean batchMode)
    {
        return generateVideo(request, userId, batchMode, null);
    }

    @Override
    public StoryboardVideoGenerateVO generateVideo(StoryboardVideoGenerateRequest request, Long userId,
            boolean batchMode, BatchTaskSlotReservation slotReservation)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestMulti(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜图生视频(多参方向)入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}, hasInputPrompt={}",
                userId, ids.size(), single, perShotCount, request.getModelName(),
                StrUtil.isNotBlank(request.getVideoPrompt()));

        String creationMode = resolveCreationModeForBatch(ids, userId);
        String funcCode = CreationModeEnum.PRO.getValue().equals(creationMode)
                ? FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO : FUNC_CODE_STORYBOARD_VIDEO; // pro 专属多参池 / 通用多参池
        AiModelConfigVo modelConfig = requireMultiVideoModel(request, funcCode);
        String modelCode = modelConfig.getModelCode();
        Long modelId = modelConfig.getId();
        Integer requestedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? request.getDurationSeconds() : null;
        String resolvedAspectRatio = resolveAspectRatioForBatch(
                request.getAspectRatio(), ids, userId, modelConfig);
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        boolean batchWorkflow = batchMode || !single;
        return submitBatch(userId, ids, single, perShotCount, batchWorkflow, batchWorkflow,
                modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, requestedDuration, request.getGenerateAudio(), request.getUserInputText(),
                funcCode, DIRECTION_MULTI,
                slotReservation,
                sb -> prepareMultiShot(sb, singleFinal, request, modelConfig, userId,
                        perShotCountFinal, null, null, true, true));
    }

    @Override
    public BillingQuoteVO quoteVideo(StoryboardVideoGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestMulti(request);
        boolean single = ids.size() == 1;
        String creationMode = resolveCreationModeForBatch(ids, userId);
        String funcCode = CreationModeEnum.PRO.getValue().equals(creationMode)
                ? FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO : FUNC_CODE_STORYBOARD_VIDEO;
        AiModelConfigVo modelConfig = requireMultiVideoModel(request, funcCode);
        int perShotCount = single ? clampCount(request.getCount()) : 1;
        return quotePreparedVideoBatch("STORYBOARD_VIDEO", userId, ids, single,
                single ? clampCount(request.getCount()) : 1, modelConfig,
                resolveAspectRatioForBatch(request.getAspectRatio(), ids, userId, modelConfig),
                resolveResolution(request.getResolution(), modelConfig),
                Boolean.TRUE.equals(modelConfig.getSupportsDuration()) ? request.getDurationSeconds() : null,
                request.getGenerateAudio(), DIRECTION_MULTI, false,
                storyboard -> prepareMultiShot(storyboard, single, request, modelConfig, userId,
                        perShotCount, null, null, false, false));
    }

    @Override
    public BillingQuoteVO quotePlannedVideo(StoryboardVideoGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestMulti(request);
        boolean single = ids.size() == 1;
        String creationMode = resolveCreationModeForBatch(ids, userId);
        String funcCode = CreationModeEnum.PRO.getValue().equals(creationMode)
                ? FUNC_CODE_STORYBOARD_VIDEO_MULTI_PRO : FUNC_CODE_STORYBOARD_VIDEO;
        AiModelConfigVo modelConfig = requireMultiVideoModel(request, funcCode);
        int perShotCount = single ? clampCount(request.getCount()) : 1;
        return quotePreparedVideoBatch("STORYBOARD_VIDEO", userId, ids, single,
                perShotCount, modelConfig,
                resolveAspectRatioForBatch(request.getAspectRatio(), ids, userId, modelConfig),
                resolveResolution(request.getResolution(), modelConfig),
                Boolean.TRUE.equals(modelConfig.getSupportsDuration()) ? request.getDurationSeconds() : null,
                request.getGenerateAudio(), DIRECTION_MULTI, true,
                storyboard -> preparePlannedMultiShot(
                        storyboard, single, request, modelConfig, userId, perShotCount));
    }

    @Override
    public BillingQuoteVO quoteVideoFromImage(StoryboardVideoFromImageGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestImage(request);
        boolean single = ids.size() == 1;
        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_IMAGE, "image_to_video");
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            throw new ServiceException("该模型不支持图片");
        }
        int perShotCount = single ? clampCount(request.getCount()) : 1;
        return quotePreparedVideoBatch("STORYBOARD_VIDEO_IMAGE", userId, ids, single,
                perShotCount, modelConfig,
                resolveAspectRatioForBatch(request.getAspectRatio(), ids, userId, modelConfig),
                resolveResolution(request.getResolution(), modelConfig),
                Boolean.TRUE.equals(modelConfig.getSupportsDuration()) ? request.getDurationSeconds() : null,
                request.getGenerateAudio(), DIRECTION_IMAGE, false,
                storyboard -> prepareImageShot(storyboard, single, request, modelConfig, userId,
                        perShotCount, null, false, false));
    }

    @Override
    public BillingQuoteVO quotePlannedVideoFromImage(
            StoryboardVideoFromImageGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestImage(request);
        boolean single = ids.size() == 1;
        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_IMAGE, "image_to_video");
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            throw new ServiceException("该模型不支持图片");
        }
        int perShotCount = single ? clampCount(request.getCount()) : 1;
        return quotePreparedVideoBatch("STORYBOARD_VIDEO_IMAGE", userId, ids, single,
                perShotCount, modelConfig,
                resolveAspectRatioForBatch(request.getAspectRatio(), ids, userId, modelConfig),
                resolveResolution(request.getResolution(), modelConfig),
                Boolean.TRUE.equals(modelConfig.getSupportsDuration()) ? request.getDurationSeconds() : null,
                request.getGenerateAudio(), DIRECTION_IMAGE, true,
                storyboard -> preparePlannedImageShot(
                        storyboard, single, request, modelConfig, userId, perShotCount));
    }

    @Override
    public BillingQuoteVO quoteVideoFromGrid(StoryboardVideoGridGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestGrid(request);
        boolean single = ids.size() == 1;
        String creationMode = resolveCreationModeForBatch(ids, userId);
        if (!CreationModeEnum.AUTO_GRID.getValue().equals(creationMode))
        {
            throw new ServiceException("仅宫格模式可用");
        }
        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_GRID, "image_to_video");
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            throw new ServiceException("该模型不支持图片");
        }
        int perShotCount = single ? clampCount(request.getCount()) : 1;
        StoryboardVideoFromImageGenerateRequest imageRequest = new StoryboardVideoFromImageGenerateRequest();
        imageRequest.setVideoPrompt(single ? request.getVideoPrompt() : null);
        imageRequest.setGenerateAudio(request.getGenerateAudio());
        imageRequest.setReferenceAudioRecordIds(single ? request.getReferenceAudioRecordIds() : null);
        imageRequest.setReferenceAudioIds(single ? request.getReferenceAudioIds() : null);
        imageRequest.setUserInputText(request.getUserInputText());
        return quotePreparedVideoBatch("STORYBOARD_VIDEO_GRID", userId, ids, single,
                perShotCount, modelConfig,
                resolveAspectRatioForBatch(request.getAspectRatio(), ids, userId, modelConfig),
                resolveResolution(request.getResolution(), modelConfig),
                Boolean.TRUE.equals(modelConfig.getSupportsDuration()) ? request.getDurationSeconds() : null,
                request.getGenerateAudio(), DIRECTION_GRID, false,
                storyboard -> prepareImageShot(storyboard, single, imageRequest, modelConfig, userId,
                        perShotCount, null, false, false));
    }

    @Override
    public BillingQuoteVO quotePlannedVideoFromGrid(
            StoryboardVideoGridGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestGrid(request);
        boolean single = ids.size() == 1;
        String creationMode = resolveCreationModeForBatch(ids, userId);
        if (!CreationModeEnum.AUTO_GRID.getValue().equals(creationMode))
        {
            throw new ServiceException("仅宫格模式可用");
        }
        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_GRID, "image_to_video");
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            throw new ServiceException("该模型不支持图片");
        }
        int perShotCount = single ? clampCount(request.getCount()) : 1;
        StoryboardVideoFromImageGenerateRequest imageRequest = new StoryboardVideoFromImageGenerateRequest();
        imageRequest.setGenerateAudio(request.getGenerateAudio());
        imageRequest.setReferenceAudioRecordIds(single ? request.getReferenceAudioRecordIds() : null);
        imageRequest.setReferenceAudioIds(single ? request.getReferenceAudioIds() : null);
        return quotePreparedVideoBatch("STORYBOARD_VIDEO_GRID", userId, ids, single,
                perShotCount, modelConfig,
                resolveAspectRatioForBatch(request.getAspectRatio(), ids, userId, modelConfig),
                resolveResolution(request.getResolution(), modelConfig),
                Boolean.TRUE.equals(modelConfig.getSupportsDuration()) ? request.getDurationSeconds() : null,
                request.getGenerateAudio(), DIRECTION_GRID, true,
                storyboard -> preparePlannedImageShot(
                        storyboard, single, imageRequest, modelConfig, userId, perShotCount));
    }

    @Override
    public BillingQuoteVO quoteVideoFromEdge(StoryboardVideoEdgeGenerateRequest request, Long userId)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestEdge(request);
        boolean single = ids.size() == 1;
        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_EDGE, "start_end_to_video");
        if (!Boolean.TRUE.equals(modelConfig.getSupportsLastFrame()))
        {
            throw new ServiceException("不支持尾帧");
        }
        Map<Long, EdgeResolved> edgeMap = buildEdgeResolvedMap(request);
        int perShotCount = single ? clampCount(request.getCount()) : 1;
        return quotePreparedVideoBatch("STORYBOARD_VIDEO_EDGE", userId, ids, single,
                perShotCount, modelConfig,
                resolveAspectRatioForBatch(request.getAspectRatio(), ids, userId, modelConfig),
                resolveResolution(request.getResolution(), modelConfig),
                Boolean.TRUE.equals(modelConfig.getSupportsDuration()) ? request.getDurationSeconds() : null,
                request.getGenerateAudio(), DIRECTION_EDGE, false,
                storyboard -> prepareEdgeShot(storyboard, single, request, edgeMap, modelConfig,
                        userId, perShotCount, false, false));
    }

    private AiModelConfigVo requireVideoModel(String requestedModelCode, String funcCode)
    {
        return requireVideoModel(requestedModelCode, funcCode, null);
    }

    private static String multiCapability(StoryboardVideoGenerateRequest request) {
        if (request != null && StrUtil.isNotBlank(request.getCapabilityCode()))
        {
            return StrUtil.trim(request.getCapabilityCode()).toLowerCase(java.util.Locale.ROOT);
        }
        String taskType = request == null ? null : request.getOmniReferenceTaskType();
        return "edit".equals(taskType) ? "video_edit" : "extend".equals(taskType) ? "video_extend" : null;
    }

    private AiModelConfigVo requireMultiVideoModel(StoryboardVideoGenerateRequest request, String funcCode)
    {
        String requested = multiCapability(request);
        AiModelConfigVo selected = requireVideoModel(request.getModelName(), funcCode, requested);
        boolean dmcH3 = "dmc-h3-video".equals(selected.getProtocol());
        boolean tokenDanceH3 = TokenDanceProtocols.isTokenDance(selected.getProviderCode())
                && TokenDanceProtocols.matches(TokenDanceProtocols.MINIMAX_VIDEO_GENERATION_V2,
                        selected.getProtocol());
        boolean implicitReference = dmcH3 && hasMultiReferenceSelection(request)
                || tokenDanceH3 && hasTokenDanceReferenceSelection(request);
        if (requested != null || !implicitReference)
        {
            return selected;
        }
        // 旧版多参请求只传模型编码；先选定参考能力，使报价、预校验与正式提交一致。
        return requireVideoModel(request.getModelName(), funcCode, "reference_to_video");
    }

    private static boolean hasMultiReferenceSelection(StoryboardVideoGenerateRequest request)
    {
        if (request == null) return false;
        if (request.getBaseImageRecordId() != null
                || request.getReferenceOverrides() != null && !request.getReferenceOverrides().isEmpty()
                || request.getReferenceAudioRecordIds() != null && !request.getReferenceAudioRecordIds().isEmpty()
                || request.getReferenceAudioIds() != null && !request.getReferenceAudioIds().isEmpty()
                || request.getReferenceVideoRecordIds() != null && !request.getReferenceVideoRecordIds().isEmpty())
        {
            return true;
        }
        String prompt = request.getVideoPrompt();
        return StrUtil.isNotBlank(prompt) && (prompt.contains("@图片") || prompt.contains("<Picture ")
                || prompt.contains("<Video ") || prompt.contains("<Audio "));
    }

    /** 单张垫图仍走首帧能力；TokenDance 只为显式参考素材请求补齐旧版缺失的能力编码。 */
    private static boolean hasTokenDanceReferenceSelection(StoryboardVideoGenerateRequest request)
    {
        if (request == null) return false;
        if (request.getReferenceOverrides() != null && !request.getReferenceOverrides().isEmpty()
                || request.getReferenceAudioRecordIds() != null && !request.getReferenceAudioRecordIds().isEmpty()
                || request.getReferenceAudioIds() != null && !request.getReferenceAudioIds().isEmpty()
                || request.getReferenceVideoRecordIds() != null && !request.getReferenceVideoRecordIds().isEmpty())
        {
            return true;
        }
        String prompt = request.getVideoPrompt();
        return StrUtil.isNotBlank(prompt) && (prompt.contains("@图片") || prompt.contains("<Picture ")
                || prompt.contains("<Video ") || prompt.contains("<Audio "));
    }

    private AiModelConfigVo requireVideoModel(String requestedModelCode, String funcCode, String capability)
    {
        String modelCode = resolveModelCode(requestedModelCode, funcCode);
        AiModelConfigVo modelConfig;
        try
        {
            modelConfig = aiModelConfigService.selectForBusiness(modelCode, funcCode, capability);
        }
        catch (ServiceException exception)
        {
            boolean compatibleVideoToVideoAlias = ("video_edit".equals(capability)
                    || "video_extend".equals(capability))
                    && "业务未绑定此能力".equals(exception.getMessage());
            if (!compatibleVideoToVideoAlias)
            {
                throw exception;
            }
            modelConfig = aiModelConfigService.selectForBusiness(modelCode, funcCode, "video_to_video");
        }
        if (modelConfig == null && ("video_edit".equals(capability) || "video_extend".equals(capability)))
        {
            modelConfig = aiModelConfigService.selectForBusiness(modelCode, funcCode, "video_to_video");
        }
        if (modelConfig == null)
        {
            throw new ServiceException("模型不存在");
        }
        return modelConfig;
    }

    /** 正式提交与直接报价共用 PreparedShot，再由同一请求构造器进入媒体计费。 */
    private BillingQuoteVO quotePreparedVideoBatch(String quoteType, Long userId, List<Long> ids,
            boolean single, int perShotCount, AiModelConfigVo modelConfig,
            String aspectRatio, String resolution, Integer requestedDurationSeconds,
            Boolean requestedGenerateAudio, String direction, boolean plannedPrompt,
            ShotPreparer preparer)
    {
        Boolean generateAudio = resolveGenerateAudioOrDefault(modelConfig, requestedGenerateAudio);
        assertGenerateAudioAllowed(modelConfig, generateAudio);
        List<BillingQuoteVO> items = new ArrayList<>();
        try
        {
            for (Long id : ids)
            {
                AidStoryboard storyboard = loadAndCheckStoryboard(id, userId);
                PreparedShot shot = preparer.prepare(storyboard);
                boolean useRecommended = usesStoryboardRecommendedDuration(direction, storyboard);
                Integer recommended = useRecommended
                        ? StoryboardDurationResolver.parseRecommendedDuration(storyboard.getScriptParams()) : null;
                Resolution duration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                        ? StoryboardDurationResolver.resolve(requestedDurationSeconds,
                                recommended, useRecommended, single, modelConfig)
                        : new Resolution(null, null);
                MediaVideoGenerateRequest media = buildVideoMediaRequest(
                        storyboard, shot, modelConfig, userId, aspectRatio, resolution,
                        duration.durationSeconds(), generateAudio, Map.of());
                PreparedMediaBillingInput prepared = plannedPrompt
                        ? mediaBillingQuotePreparer.preparePlannedVideoBilling(media)
                        : mediaBillingQuotePreparer.prepareVideoBilling(media);
                BillingCalcResult result = billingPreHoldCalculationService.calculate(
                        prepared.modelConfig(), prepared.billingInput());
                if (result == null || !result.isMatched())
                {
                    throw new ServiceException(result == null ? "计费规则缺失" : result.getErrorMessage());
                }
                BillingQuoteVO item = billingQuoteAssembler.single(
                        quoteType, prepared.modelConfig(), result, perShotCount);
                items.add(plannedPrompt ? billingQuoteAssembler.asEstimated(item) : item);
            }
        }
        catch (ReferenceAudioValidationException e)
        {
            throw new ServiceException(e.getMessage());
        }
        return billingQuoteAssembler.aggregate(quoteType, items);
    }

    /**
     * 解析创作模式（剧集 creation_mode 优先 → 项目 default_creation_mode 兜底 → 空串）。
     * 口径与 {@code ProjectGenConfigServiceImpl} 一致；仅 select 必要字段。
     */
    private String resolveCreationMode(Long projectId, Long episodeId)
    {
        if (Objects.nonNull(episodeId) && episodeId > 0)
        {
            AidComicEpisode episode = aidComicEpisodeService.getOne(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .select(AidComicEpisode::getId, AidComicEpisode::getCreationMode)
                            .eq(AidComicEpisode::getId, episodeId)
                            .last("limit 1"), false);
            if (Objects.nonNull(episode) && StrUtil.isNotBlank(episode.getCreationMode()))
            {
                return episode.getCreationMode().trim();
            }
        }
        if (Objects.nonNull(projectId) && projectId > 0)
        {
            AidComicProject project = aidComicProjectService.getOne(
                    Wrappers.<AidComicProject>lambdaQuery()
                            .select(AidComicProject::getId, AidComicProject::getDefaultCreationMode)
                            .eq(AidComicProject::getId, projectId)
                            .last("limit 1"), false);
            if (Objects.nonNull(project) && StrUtil.isNotBlank(project.getDefaultCreationMode()))
            {
                return project.getDefaultCreationMode().trim();
            }
        }
        return "";
    }

    /**
     * 批量出片解析创作模式：扫描 ids 取第一个可加载分镜的创作模式（逐镜头真正的归属校验在 submitBatch 内进行）。
     * 避免首个 id 非法/越权直接整批失败——同批分镜本属同一 project/episode，取首个可加载者即可代表；
     * 全部不可加载时返回空串（后续 submitBatch 会以「全部失败」终结）。
     */
    private String resolveCreationModeForBatch(List<Long> ids, Long userId)
    {
        for (Long id : ids)
        {
            try
            {
                AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                return resolveCreationMode(sb.getProjectId(), sb.getEpisodeId());
            }
            catch (RuntimeException ignore)
            {
                // 该镜头不可加载，尝试下一个
            }
        }
        return "";
    }

    /** 判断当前分镜和出片方向是否应消费视觉导演建议秒数。 */
    private boolean usesStoryboardRecommendedDuration(String direction, AidStoryboard storyboard)
    {
        if (!Objects.equals(DIRECTION_MULTI, direction) && !Objects.equals(DIRECTION_GRID, direction))
        {
            return false;
        }
        String creationMode = resolveCreationMode(storyboard.getProjectId(), storyboard.getEpisodeId());
        if (Objects.equals(DIRECTION_GRID, direction))
        {
            return Objects.equals(CreationModeEnum.AUTO_GRID.getValue(), creationMode);
        }
        return Objects.equals(CreationModeEnum.MULTI.getValue(), creationMode)
                || Objects.equals(CreationModeEnum.PRO.getValue(), creationMode);
    }

    /**
     * 按出片方向兜底解析 func_code（仅用于无 funcCode 字段的老快照续生）。
     * 多参 pro 分流的 func_code 已持久化到快照，老快照按 direction 回落到通用池：
     * image/grid → 各自池、edge → 首尾帧池、其余（multi）→ 通用多参池。
     */
    private String resolveFuncCodeByDirection(String direction)
    {
        if (DIRECTION_IMAGE.equals(direction)) { return FUNC_CODE_STORYBOARD_VIDEO_IMAGE; }
        if (DIRECTION_GRID.equals(direction)) { return FUNC_CODE_STORYBOARD_VIDEO_GRID; }
        if (DIRECTION_EDGE.equals(direction)) { return FUNC_CODE_STORYBOARD_VIDEO_EDGE; }
        return FUNC_CODE_STORYBOARD_VIDEO;
    }
    private void validateUserId(Long userId)
    {
        if (Objects.isNull(userId) || userId <= 0)
        {
            log.error("分镜图生视频登录态缺失: userId={}", userId);
            throw new ServiceException("请先登录");
        }
    }
    @Override
    public StoryboardVideoGenerateVO generateVideoFromImage(StoryboardVideoFromImageGenerateRequest request, Long userId)
    {
        return generateVideoFromImage(request, userId, false);
    }

    @Override
    public StoryboardVideoGenerateVO generateVideoFromImage(StoryboardVideoFromImageGenerateRequest request,
            Long userId, boolean batchMode)
    {
        return generateVideoFromImage(request, userId, batchMode, null);
    }

    @Override
    public StoryboardVideoGenerateVO generateVideoFromImage(StoryboardVideoFromImageGenerateRequest request,
            Long userId, boolean batchMode, BatchTaskSlotReservation slotReservation)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestImage(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜图生视频(图生方向)入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}, imageCount={}",
                userId, ids.size(), single, perShotCount, request.getModelName(),
                request.getImages() == null ? 0 : request.getImages().size());

        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_IMAGE, "image_to_video");
        String modelCode = modelConfig.getModelCode();
        // 图生视频出片硬要求：模型必须支持图片输入（池已收敛，此处再兜底一层）
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            log.info("图生视频出片模型不支持图片输入: modelCode={}", modelCode);
            throw new ServiceException("该模型不支持图片");
        }
        Long modelId = modelConfig.getId();
        Integer requestedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? request.getDurationSeconds() : null;
        String resolvedAspectRatio = resolveAspectRatioForBatch(
                request.getAspectRatio(), ids, userId, modelConfig);
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        boolean batchWorkflow = batchMode || !single;
        return submitBatch(userId, ids, single, perShotCount, batchWorkflow, batchWorkflow,
                modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, requestedDuration, request.getGenerateAudio(), request.getUserInputText(),
                FUNC_CODE_STORYBOARD_VIDEO_IMAGE, DIRECTION_IMAGE,
                slotReservation,
                sb -> prepareImageShot(sb, singleFinal, request, modelConfig, userId,
                        perShotCountFinal, null, true, true));
    }
    @Override
    public StoryboardVideoGenerateVO generateVideoFromGrid(StoryboardVideoGridGenerateRequest request, Long userId)
    {
        return generateVideoFromGrid(request, userId, false);
    }

    @Override
    public StoryboardVideoGenerateVO generateVideoFromGrid(StoryboardVideoGridGenerateRequest request,
            Long userId, boolean batchMode)
    {
        return generateVideoFromGrid(request, userId, batchMode, null);
    }

    @Override
    public StoryboardVideoGenerateVO generateVideoFromGrid(StoryboardVideoGridGenerateRequest request,
            Long userId, boolean batchMode, BatchTaskSlotReservation slotReservation)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestGrid(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜宫格生视频入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}",
                userId, ids.size(), single, perShotCount, request.getModelName());

        String creationMode = resolveCreationModeForBatch(ids, userId);
        if (!CreationModeEnum.AUTO_GRID.getValue().equals(creationMode))
        {
            log.info("宫格生视频拒绝：非 auto_grid 创作模式: creationMode={}", creationMode);
            throw new ServiceException("仅宫格模式可用");
        }

        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_GRID, "image_to_video");
        String modelCode = modelConfig.getModelCode();
        if (!Boolean.TRUE.equals(modelConfig.getSupportsImageInput()))
        {
            log.info("宫格生视频模型不支持图片输入: modelCode={}", modelCode);
            throw new ServiceException("该模型不支持图片");
        }
        Long modelId = modelConfig.getId();
        Integer requestedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? request.getDurationSeconds() : null;
        String resolvedAspectRatio = resolveAspectRatioForBatch(
                request.getAspectRatio(), ids, userId, modelConfig);
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        StoryboardVideoFromImageGenerateRequest imageReq = new StoryboardVideoFromImageGenerateRequest();
        imageReq.setVideoPrompt(single ? request.getVideoPrompt() : null);
        imageReq.setGenerateAudio(request.getGenerateAudio());
        imageReq.setReferenceAudioRecordIds(single ? request.getReferenceAudioRecordIds() : null);
        imageReq.setReferenceAudioIds(single ? request.getReferenceAudioIds() : null);
        imageReq.setUserInputText(request.getUserInputText());
        boolean batchWorkflow = batchMode || !single;
        return submitBatch(userId, ids, single, perShotCount, batchWorkflow, batchWorkflow,
                modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, requestedDuration, request.getGenerateAudio(), request.getUserInputText(),
                FUNC_CODE_STORYBOARD_VIDEO_GRID, DIRECTION_GRID,
                slotReservation,
                sb -> prepareImageShot(sb, singleFinal, imageReq, modelConfig, userId,
                        perShotCountFinal, null, true, true));
    }
    @Override
    public StoryboardVideoGenerateVO generateVideoFromEdge(StoryboardVideoEdgeGenerateRequest request, Long userId)
    {
        return generateVideoFromEdge(request, userId, false);
    }

    @Override
    public StoryboardVideoGenerateVO generateVideoFromEdge(StoryboardVideoEdgeGenerateRequest request,
            Long userId, boolean batchMode)
    {
        validateUserId(userId);
        List<Long> ids = validateBatchRequestEdge(request);
        boolean single = ids.size() == 1;
        int perShotCount = single ? clampCount(request.getCount()) : 1;

        log.info("分镜首尾帧生视频入口: userId={}, shotCount={}, single={}, perShotCount={}, modelName={}",
                userId, ids.size(), single, perShotCount, request.getModelName());

        AiModelConfigVo modelConfig = requireVideoModel(
                request.getModelName(), FUNC_CODE_STORYBOARD_VIDEO_EDGE, "start_end_to_video");
        String modelCode = modelConfig.getModelCode();
        if (!Boolean.TRUE.equals(modelConfig.getSupportsLastFrame()))
        {
            log.info("首尾帧生视频模型不支持尾帧输入: modelCode={}", modelCode);
            throw new ServiceException("不支持尾帧");
        }
        Long modelId = modelConfig.getId();
        Integer requestedDuration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? request.getDurationSeconds() : null;
        String resolvedAspectRatio = resolveAspectRatioForBatch(
                request.getAspectRatio(), ids, userId, modelConfig);
        String resolvedResolution = resolveResolution(request.getResolution(), modelConfig);

        Map<Long, EdgeResolved> edgeMap = buildEdgeResolvedMap(request);
        final boolean singleFinal = single;
        final int perShotCountFinal = perShotCount;
        boolean batchWorkflow = batchMode || !single;
        return submitBatch(userId, ids, single, perShotCount, batchWorkflow, batchWorkflow,
                modelCode, modelConfig, modelId,
                resolvedAspectRatio, resolvedResolution, requestedDuration, request.getGenerateAudio(), request.getUserInputText(),
                FUNC_CODE_STORYBOARD_VIDEO_EDGE, DIRECTION_EDGE,
                null,
                sb -> prepareEdgeShot(sb, singleFinal, request, edgeMap, modelConfig, userId,
                        perShotCountFinal, true, true));
    }

    /**
     * 图生方向批量出片入参校验：storyboardIds 非空去重 + count 规则（多镜头禁止 count&gt;1）+ 长度/时长基础校验。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestImage(StoryboardVideoFromImageGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜图生视频(图生方向)入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        long explicitImageCount = request.getImages() == null ? 0L : request.getImages().stream()
                .filter(StrUtil::isNotBlank)
                .map(StrUtil::trim)
                .distinct()
                .count();
        if (!single && explicitImageCount > 0)
        {
            log.info("分镜批量图生视频收到整批参考图: count={}", explicitImageCount);
            throw new ServiceException("批量分镜不能共用参考图");
        }
        if (explicitImageCount > 1)
        {
            log.info("分镜图生视频参考图数量超限: count={}", explicitImageCount);
            throw new ServiceException("图生方向最多传1张图");
        }
        if (StrUtil.isNotBlank(request.getVideoPrompt()) && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜图生视频(图生方向)提示词过长: len={}", request.getVideoPrompt().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        if (StrUtil.isNotBlank(request.getUserInputText()) && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.error("分镜图生视频用户补充内容过长: len={}", request.getUserInputText().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "用户补充内容过长，请精简");
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0)
        {
            log.error("分镜图生视频(图生方向) durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /** 将前端传入的图生提示词落库到 aid_storyboard.video_prompt_image（建任务前调用，见 generateVideoFromImage 设计说明）。 */
    private void persistVideoPromptImage(Long storyboardId, Long userId, String videoPrompt)
    {
        LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
        upd.eq(AidStoryboard::getId, storyboardId);
        upd.eq(AidStoryboard::getUserId, userId);
        upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        upd.set(AidStoryboard::getVideoPromptImage, videoPrompt);
        upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        aidStoryboardService.update(upd);
    }

    /** 将前端传入的（多参方向）提示词落库到 aid_storyboard.video_prompt（建任务前调用，与图生方向口径一致）。 */
    private void persistVideoPrompt(Long storyboardId, Long userId, String videoPrompt)
    {
        LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
        upd.eq(AidStoryboard::getId, storyboardId);
        upd.eq(AidStoryboard::getUserId, userId);
        upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        upd.set(AidStoryboard::getVideoPrompt, videoPrompt);
        upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        aidStoryboardService.update(upd);
    }

    /**
     * 多参方向批量出片入参校验：storyboardIds 非空去重 + count 规则 + 长度/时长基础校验。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestMulti(StoryboardVideoGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜图生视频入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        StoryboardVideoAdvancedOptions.normalize(request, single);
        validateCountRule(single, request.getCount());
        if (!single && CollectionUtil.isNotEmpty(request.getReferenceVideoRecordIds()))
        {
            log.info("分镜批量出片不接受整批参考视频: shotCount={}, videoCount={}",
                    ids.size(), request.getReferenceVideoRecordIds().size());
            throw new ServiceException("参考视频仅限单镜");
        }
        if (StrUtil.isNotBlank(request.getVideoPrompt())
                && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜图生视频提示词过长: len={}", request.getVideoPrompt().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        if (StrUtil.isNotBlank(request.getUserInputText())
                && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.error("分镜视频用户补充内容过长: len={}", request.getUserInputText().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "用户补充内容过长，请精简");
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0
                && request.getDurationSeconds() != -1)
        {
            log.error("分镜图生视频 durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /**
     * 宫格方向批量出片入参校验：storyboardIds 非空去重 + count 规则 + 长度/时长基础校验。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestGrid(StoryboardVideoGridGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜宫格生视频入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        if (StrUtil.isNotBlank(request.getVideoPrompt()) && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜宫格生视频提示词过长: len={}", request.getVideoPrompt().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        if (StrUtil.isNotBlank(request.getUserInputText()) && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.error("分镜宫格视频用户补充内容过长: len={}", request.getUserInputText().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "用户补充内容过长，请精简");
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0)
        {
            log.error("分镜宫格生视频 durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /**
     * 首尾帧方向批量出片入参校验：storyboardIds 非空去重 + count 规则 + 长度/时长基础校验。
     * 首尾图记录的存在性按镜头在 {@link #prepareEdgeShot} 中校验（多镜头单镜头失败仅跳过，不阻断整批）。
     *
     * @return 去重后的分镜 ID 列表
     */
    private List<Long> validateBatchRequestEdge(StoryboardVideoEdgeGenerateRequest request)
    {
        if (Objects.isNull(request))
        {
            log.error("分镜首尾帧生视频入参为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = distinctValidIds(request.getStoryboardIds());
        boolean single = ids.size() == 1;
        validateCountRule(single, request.getCount());
        if (StrUtil.isNotBlank(request.getVideoPrompt()) && request.getVideoPrompt().length() > MAX_PROMPT_LENGTH)
        {
            log.error("分镜首尾帧生视频提示词过长: len={}", request.getVideoPrompt().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        if (StrUtil.isNotBlank(request.getUserInputText()) && request.getUserInputText().length() > MAX_USER_INPUT_LENGTH)
        {
            log.error("分镜首尾帧视频用户补充内容过长: len={}", request.getUserInputText().length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "用户补充内容过长，请精简");
        }
        if (Objects.nonNull(request.getDurationSeconds()) && request.getDurationSeconds() <= 0)
        {
            log.error("分镜首尾帧生视频 durationSeconds 非法: val={}", request.getDurationSeconds());
            throw new ServiceException("时长有误");
        }
        return ids;
    }

    /** 组装首尾帧逐镜头解析项。 */
    private Map<Long, EdgeResolved> buildEdgeResolvedMap(StoryboardVideoEdgeGenerateRequest request)
    {
        Map<Long, EdgeResolved> map = new LinkedHashMap<>();
        if (Objects.isNull(request) || CollectionUtil.isEmpty(request.getItems()))
        {
            return map;
        }
        for (StoryboardVideoEdgeGenerateRequest.EdgeShotItem item : request.getItems())
        {
            if (Objects.isNull(item) || Objects.isNull(item.getStoryboardId())) { continue; }
            map.put(item.getStoryboardId(), new EdgeResolved(
                    StrUtil.trimToNull(item.getFirstImageUrl()), item.getFirstImageRecordId(),
                    StrUtil.trimToNull(item.getLastImageUrl()), item.getLastImageRecordId(),
                    new ExplicitAudioSelection(item.getReferenceAudioRecordIds(), item.getReferenceAudioIds())));
        }
        return map;
    }

    /** 把首尾帧来源与参考音频记录 ID 写入快照镜头条目，供续生重解析。 */
    private void putEdgeSnapshot(Map<String, Object> target, PreparedShot ps)
    {
        if (StrUtil.isNotBlank(ps.firstImageUrl)) { target.put("firstImageUrl", ps.firstImageUrl); }
        if (Objects.nonNull(ps.firstImageRecordId)) { target.put("firstImageRecordId", ps.firstImageRecordId); }
        if (StrUtil.isNotBlank(ps.lastImageUrl)) { target.put("lastImageUrl", ps.lastImageUrl); }
        if (Objects.nonNull(ps.lastImageRecordId)) { target.put("lastImageRecordId", ps.lastImageRecordId); }
        putAudioSelectionSnapshot(target, ps.referenceAudios);
        putVideoSelectionSnapshot(target, ps.referenceVideos);
        Map<String, Object> advanced = StoryboardVideoAdvancedOptions.snapshot(ps.providerExtraOptions);
        if (!advanced.isEmpty()) target.put("videoOptions", advanced);
    }

    /** 把逐分镜建议时长、实际时长及来源写入任务快照。 */
    private void putDurationSnapshot(Map<String, Object> target, PreparedShot ps)
    {
        target.put("recommendedDurationSeconds", ps.recommendedDurationSeconds);
        target.put("durationSeconds", ps.durationSeconds);
        target.put("durationSource", ps.durationSource);
    }

    /**
     * 快照只保存业务 ID，不保存可变化的解析 URL。隐式来源由提示词重新推导，不进快照。
     * 按 sourceType 而非 ID 字段是否有值来判定来源：角色绑定了上传参考音频时，
     * 隐式条目同样带 referenceAudioId（供回显与追溯），只看字段会把隐式来源误固化成显式选择，
     * 续生时降级语义反转为阻断出片。
     */
    private void putAudioSelectionSnapshot(Map<String, Object> target, List<ReferenceAudioInput> referenceAudios)
    {
        if (CollectionUtil.isEmpty(referenceAudios)) { return; }
        List<Long> recordIds = collectExplicitIds(referenceAudios, ReferenceAudioInput.SOURCE_AUDIO_RECORD,
                ReferenceAudioInput::getAudioRecordId);
        if (CollectionUtil.isNotEmpty(recordIds)) { target.put(SNAPSHOT_KEY_AUDIO_RECORD_IDS, recordIds); }
        List<Long> uploadIds = collectExplicitIds(referenceAudios, ReferenceAudioInput.SOURCE_UPLOAD,
                ReferenceAudioInput::getReferenceAudioId);
        if (CollectionUtil.isNotEmpty(uploadIds)) { target.put(SNAPSHOT_KEY_REFERENCE_AUDIO_IDS, uploadIds); }
    }

    /** 快照只保存参考视频来源记录 ID，URL 与元数据在续生时重新解析。 */
    private void putVideoSelectionSnapshot(Map<String, Object> target, List<ReferenceVideoInput> referenceVideos)
    {
        if (CollectionUtil.isEmpty(referenceVideos))
        {
            return;
        }
        List<Long> recordIds = referenceVideos.stream()
                .filter(Objects::nonNull)
                .map(ReferenceVideoInput::getRecordId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (CollectionUtil.isNotEmpty(recordIds))
        {
            target.put(SNAPSHOT_KEY_VIDEO_RECORD_IDS, recordIds);
        }
    }

    /**
     * 取指定显式来源的业务 ID 列表。
     *
     * @param referenceAudios 归一后的参考音频列表
     * @param sourceType      目标来源类型
     * @param idGetter        该来源对应的 ID 取值器
     * @return 去重保序的 ID 列表
     */
    private List<Long> collectExplicitIds(List<ReferenceAudioInput> referenceAudios, String sourceType,
            Function<ReferenceAudioInput, Long> idGetter)
    {
        return referenceAudios.stream()
                .filter(Objects::nonNull)
                .filter(audio -> sourceType.equals(audio.getSourceType()))
                .map(idGetter)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** 读取续生快照中用户显式选择的两类参考音频 ID。 */
    private ExplicitAudioSelection parseAudioSelectionSnapshot(JsonNode shotNode)
    {
        return new ExplicitAudioSelection(parseLongArraySnapshot(shotNode, SNAPSHOT_KEY_AUDIO_RECORD_IDS),
                parseLongArraySnapshot(shotNode, SNAPSHOT_KEY_REFERENCE_AUDIO_IDS));
    }

    /** 读取快照中的 Long 数组字段；缺失或非数组返回空列表。 */
    private List<Long> parseLongArraySnapshot(JsonNode shotNode, String field)
    {
        List<Long> ids = new ArrayList<>();
        JsonNode node = shotNode.path(field);
        if (!node.isArray()) { return ids; }
        for (JsonNode value : node)
        {
            if (value.canConvertToLong()) { ids.add(value.longValue()); }
        }
        return ids;
    }

    /** 读取快照中的正整数秒数；缺失、非整数或非正数返回 null。 */
    private Integer readPositiveSnapshotInt(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (Objects.isNull(value) || !value.isIntegralNumber() || !value.canConvertToInt())
        {
            return null;
        }
        int parsed = value.intValue();
        return parsed > 0 || parsed == -1 && "durationSeconds".equals(field) ? parsed : null;
    }

    /**
     * 解析首尾帧单帧来源 URL：上传图 URL 优先（本站图片校验），否则库内记录（须属本分镜）；两者皆无返回 null。
     *
     * @return 完整可下发的图片 URL；无来源返回 null（由调用方决定是否报错/单帧降级）
     */
    private String resolveEdgeFrameUrl(String uploadUrl, Long recordId, AidStoryboard sb, Long userId,
                                       boolean validateRemoteUrl)
    {
        if (StrUtil.isNotBlank(uploadUrl))
        {
            return validateUploadedImageUrl(uploadUrl, sb, validateRemoteUrl);
        }
        if (Objects.nonNull(recordId) && recordId > 0)
        {
            return resolveBaseImageUrl(recordId, sb, userId);
        }
        return null;
    }

    /** 校验前端上传的首尾帧图片 URL（仅本站资源 + 远程可达 + 图片格式），返回完整 URL；非法抛「图片无效」。 */
    private String validateUploadedImageUrl(String url, AidStoryboard sb, boolean validateRemoteUrl)
    {
        String u = StrUtil.trim(url);
        // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
        if (!mediaUrlResolver.isSiteImageUrl(u))
        {
            log.info("首尾帧上传图非本站资源: storyboardId={}, url={}", sb.getId(), u);
            throw new ServiceException("图片不可用");
        }
        // 相对路径拼完整URL，并据此做远程可达性 + Content-Type 校验
        String full = mediaUrlResolver.toFullUrl(u);
        if (validateRemoteUrl && !ImageUrlValidator.isValidRemoteImageUrl(full))
        {
            log.info("首尾帧上传图非法(格式/不可达/非图片): storyboardId={}, url={}", sb.getId(), full);
            throw new ServiceException("图片不可用");
        }
        return full;
    }

    /** 首尾帧逐镜头解析项。 */
    private static final class EdgeResolved
    {
        final String firstImageUrl;
        final Long firstImageRecordId;
        final String lastImageUrl;
        final Long lastImageRecordId;
        final ExplicitAudioSelection audioSelection;
        final List<Long> referenceVideoRecordIds;
        final Map<String, Object> videoOptions;

        EdgeResolved(String firstImageUrl, Long firstImageRecordId, String lastImageUrl, Long lastImageRecordId,
                      ExplicitAudioSelection audioSelection)
        {
            this(firstImageUrl, firstImageRecordId, lastImageUrl, lastImageRecordId, audioSelection, null);
        }

        EdgeResolved(String firstImageUrl, Long firstImageRecordId, String lastImageUrl, Long lastImageRecordId,
                     ExplicitAudioSelection audioSelection, List<Long> referenceVideoRecordIds)
        {
            this(firstImageUrl, firstImageRecordId, lastImageUrl, lastImageRecordId,
                    audioSelection, referenceVideoRecordIds, Map.of());
        }

        EdgeResolved(String firstImageUrl, Long firstImageRecordId, String lastImageUrl, Long lastImageRecordId,
                     ExplicitAudioSelection audioSelection, List<Long> referenceVideoRecordIds,
                     Map<String, Object> videoOptions)
        {
            this.firstImageUrl = firstImageUrl;
            this.firstImageRecordId = firstImageRecordId;
            this.lastImageUrl = lastImageUrl;
            this.lastImageRecordId = lastImageRecordId;
            this.audioSelection = (audioSelection == null) ? ExplicitAudioSelection.EMPTY : audioSelection;
            this.referenceVideoRecordIds = referenceVideoRecordIds == null
                    ? Collections.emptyList() : List.copyOf(referenceVideoRecordIds);
            this.videoOptions = Map.copyOf(videoOptions);
        }
    }

    /**
     * 用户显式选择的参考音频：配音记录 ID 与上传参考音频 ID 两类。
     * 二者的取值时机、快照固化与续生重解析口径完全一致，合成一个值对象随链路整体流转，
     * 避免每加一类来源就在每层方法签名上再挂一个 List 参数。
     */
    private static final class ExplicitAudioSelection
    {
        /** 空选择：批量镜头与无快照场景共用。 */
        static final ExplicitAudioSelection EMPTY = new ExplicitAudioSelection(null, null);

        /** 配音记录 ID（aid_audio_record.id）。 */
        final List<Long> audioRecordIds;

        /** 上传参考音频 ID（aid_reference_audio.id）。 */
        final List<Long> referenceAudioIds;

        ExplicitAudioSelection(List<Long> audioRecordIds, List<Long> referenceAudioIds)
        {
            this.audioRecordIds = (audioRecordIds == null) ? Collections.emptyList() : audioRecordIds;
            this.referenceAudioIds = (referenceAudioIds == null) ? Collections.emptyList() : referenceAudioIds;
        }

        boolean isEmpty()
        {
            return CollectionUtil.isEmpty(audioRecordIds) && CollectionUtil.isEmpty(referenceAudioIds);
        }
    }

    /** 分镜 ID 列表去重 + 基础非空校验。 */
    private List<Long> distinctValidIds(List<Long> rawIds)
    {
        if (CollectionUtil.isEmpty(rawIds))
        {
            log.error("分镜图生视频 storyboardIds 为空");
            throw new ServiceException("参数错误");
        }
        List<Long> ids = new ArrayList<>();
        for (Long id : rawIds)
        {
            if (Objects.nonNull(id) && id > 0 && !ids.contains(id))
            {
                ids.add(id);
            }
        }
        if (ids.isEmpty())
        {
            log.error("分镜图生视频 storyboardIds 全部非法: count={}", rawIds.size());
            throw new ServiceException("参数错误");
        }
        if (ids.size() > MAX_BATCH_SHOTS)
        {
            log.info("分镜批量出片镜头数超限: size={}, max={}", ids.size(), MAX_BATCH_SHOTS);
            throw new ServiceException("数量超限");
        }
        return ids;
    }

    /**
     * count 规则校验：
     *
     *   - 多镜头（single=false）：不允许 count&gt;1，违者抛"批量限1条"；
     *   - 单镜头（single=true）：count 必须在 [1,4]（为空按 1）。
     *
     */
    private void validateCountRule(boolean single, Integer count)
    {
        if (Objects.isNull(count))
        {
            return;
        }
        if (!single)
        {
            if (count > MIN_COUNT)
            {
                log.info("分镜图生视频多镜头批量禁止 count>1: count={}", count);
                throw new ServiceException("批量限1条");
            }
            return;
        }
        if (count < MIN_COUNT || count > MAX_COUNT)
        {
            log.error("分镜图生视频 count 越界: count={}", count);
            throw new ServiceException("参数错误");
        }
    }

    /** 单镜头出片条数：null→1，超出 [1,4] 直接拒绝。 */
    private int clampCount(Integer count)
    {
        if (Objects.isNull(count))
        {
            return MIN_COUNT;
        }
        if (count < MIN_COUNT || count > MAX_COUNT)
        {
            log.info("分镜视频输出数量超限: min={}, max={}, actual={}", MIN_COUNT, MAX_COUNT, count);
            throw new ServiceException("生成视频数量超限");
        }
        return count;
    }

    /** 把异常文案归一化为简短的镜头跳过原因（控制在 ~12 字内，供前端列表展示）。 */
    private String shortReason(String message)
    {
        String safe = StrUtil.blankToDefault(message, "生成失败");
        return StrUtil.sub(safe, 0, 12);
    }

    private AidStoryboard loadAndCheckStoryboard(Long storyboardId, Long userId)
    {
        AidStoryboard storyboard = aidStoryboardService.getOne(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId,
                                AidStoryboard::getProjectId,
                                AidStoryboard::getEpisodeId,
                                AidStoryboard::getUserId,
                                AidStoryboard::getVideoPrompt,
                                AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getImagePrompt,
                                AidStoryboard::getDialogueText,
                                AidStoryboard::getFinalImageId,
                                AidStoryboard::getScriptParams,
                                AidStoryboard::getDelFlag)
                        .eq(AidStoryboard::getId, storyboardId)
                        .last("limit 1"),
                false);
        if (Objects.isNull(storyboard) || !DEL_FLAG_NORMAL.equals(storyboard.getDelFlag()))
        {
            log.error("分镜不存在或已删除: storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("分镜不存在");
        }
        if (!Objects.equals(userId, storyboard.getUserId()))
        {
            log.error("分镜归属校验失败: storyboardId={}, ownerUserId={}, requestUserId={}",
                    storyboardId, storyboard.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        return storyboard;
    }

    private String resolveVideoPrompt(String inputPrompt, AidStoryboard storyboard)
    {
        if (StrUtil.isNotBlank(inputPrompt))
        {
            return inputPrompt;
        }
        String dbPrompt = storyboard.getVideoPrompt();
        if (StrUtil.isBlank(dbPrompt))
        {
            // 视频提示词为空说明还没生成视频提示词，引导用户先去生成提示词再来生成视频
            log.error("分镜图生视频提示词为空（视频提示词未生成）: storyboardId={}, userId={}",
                    storyboard.getId(), storyboard.getUserId());
            throw new ServiceException("请先生成提示词");
        }
        return dbPrompt;
    }

    /**
     * 模型解析：按指定 func_code 池校验。
     *
     *   - 用户传入 modelCode → 必须存在、{@code model_type=video} 且在池内
     *   - 用户未传 → 取池内第一个可用模型兜底
     *
     */
    private String resolveModelCode(String requestModelCode, String funcCode)
    {
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("分镜图生视频模型池未配置或全部失效: funcCode={}", funcCode);
            throw new ServiceException("功能未配置");
        }

        if (StrUtil.isNotBlank(requestModelCode))
        {
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(requestModelCode);
            if (Objects.isNull(modelConfig))
            {
                log.info("用户指定视频模型不可用: requestModelCode={}", requestModelCode);
                throw new ServiceException("模型不存在");
            }
            if (!Objects.equals(MODEL_TYPE_VIDEO, modelConfig.getModelType()))
            {
                log.info("用户指定模型类型不匹配: requestModelCode={}, actualType={}",
                        requestModelCode, modelConfig.getModelType());
                throw new ServiceException("模型不可用");
            }
            boolean inPool = pool.stream().anyMatch(m -> Objects.equals(modelConfig.getId(), m.getId()));
            if (!inPool)
            {
                log.error("分镜图生视频模型不在 {} 池内: modelCode={}, poolSize={}",
                        funcCode, requestModelCode, pool.size());
                throw new ServiceException("模型不可用");
            }
            return requestModelCode;
        }

        String fallback = pool.get(0).getModelCode();
        log.info("分镜图生视频未指定模型，使用池内默认: funcCode={}, modelCode={}", funcCode, fallback);
        return fallback;
    }
    /**
     * 解析本镜参考素材：从最终下发的 {@code video_prompt}（入参覆盖优先，否则库值）的
     * {@code @图片N[name]} 占位出发，解析出参考图 URL 并压缩重排编号。
     *
     * @param referenceOverrides 临时 name→URL 映射（单镜头前端直传，可空）；命中即优先于库内资产
     * @return 按 N 升序、去重、仅含命中且 URL 非空的参考素材；无引用返回空列表
     */
    private ReferenceResolution resolveReferences(String videoPrompt, AidStoryboard storyboard, Long userId,
            Map<String, String> referenceOverrides, boolean applyReferenceEnable,
            boolean validateRemoteUrls)
    {
        // 从传入的最终 video_prompt 解析 @图片N（与实际下发 prompt 一致）；入参覆盖时也能正确对齐参考图。
        Map<Integer, String> nameByN = parseAtReferences(videoPrompt);
        if (CollectionUtil.isEmpty(nameByN))
        {
            return new ReferenceResolution(videoPrompt, new ArrayList<>());
        }
        // @图片N 名称分隔符归一化（连字符 → 下划线，与 form_image.name 命名口径一致）：
        // 兼容历史/偶发把 `角色名_形态` 写成 `角色名-形态` 的引用，避免下游"引用即启用/缺失校验"误报缺失。
        // 仅作匹配用途，归一后的名称用于后续库内查找；资产库 name 统一以下划线为分隔符，故连字符→下划线安全。
        Map<Integer, String> normalizedByN = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : nameByN.entrySet())
        {
            normalizedByN.put(e.getKey(), normalizeAssetRefName(e.getValue()));
        }
        nameByN = normalizedByN;

        // 临时参考图覆盖（name→URL，单镜头前端直传）：命中且 URL 非空的 name 走临时外部图，优先于库内资产，
        // 这些 name 不参与库内"引用即启用 / 缺失校验"、也不查 form_image；其余 name 仍走库内解析。
        // key/value 统一 trim 归一化：prompt 里的 @图片N[name] 的 name 已 trim，前端 map key 若带空格须对齐，避免漏命中。
        Map<String, String> overrides = new java.util.HashMap<>();
        if (referenceOverrides != null)
        {
            for (Map.Entry<String, String> e : referenceOverrides.entrySet())
            {
                String k = normalizeAssetRefName(e.getKey());
                String v = StrUtil.trim(e.getValue());
                if (StrUtil.isNotBlank(k) && StrUtil.isNotBlank(v)) { overrides.put(k, v); }
            }
        }
        // 需走库内解析的 name（未被临时映射覆盖的）；与 overrides 互斥，保证临时图不被当作"库内缺失"误报
        List<String> libraryNames = new ArrayList<>();
        for (String nm : nameByN.values())
        {
            if (StrUtil.isBlank(overrides.get(nm))) { libraryNames.add(nm); }
        }

        // 库内解析所需：name → form_image、assetId → 主资产；仅当存在库内 name 时才查
        Map<String, AidRolePropSceneFormImage> byName = new LinkedHashMap<>();
        Map<Long, AidRolePropScene> assetById = new java.util.HashMap<>();
        List<Long> additionallyEnabledIds = List.of();
        if (CollectionUtil.isNotEmpty(libraryNames))
        {
            // 第一次调用只做有效性分组；该方法在存在缺失时不会启用任何图片，因此需要把有效引用
            // 单独再调用一次，保证历史上未启用但仍可用的图片可以正常参与本次生成。
            ReferenceEnablePlan enablePlan = rpsFormImageBusinessService.planReferenceEnable(
                    storyboard.getProjectId(), userId, libraryNames);
            java.util.List<String> missingNames = enablePlan.missingNames();
            if (CollectionUtil.isNotEmpty(missingNames))
            {
                log.warn("分镜图生视频存在失效引用，将降级为文字描述: storyboardId={}, missing={}",
                        storyboard.getId(), formatMissingRefs(nameByN, missingNames));
                java.util.Set<String> missingSet = missingNames.stream()
                        .map(StoryboardVideoGenerationServiceImpl::normalizeAssetRefName)
                        .collect(java.util.stream.Collectors.toSet());
                libraryNames = libraryNames.stream()
                        .filter(name -> !missingSet.contains(normalizeAssetRefName(name)))
                        .collect(java.util.stream.Collectors.toList());
                if (CollectionUtil.isNotEmpty(libraryNames))
                {
                    enablePlan = rpsFormImageBusinessService.planReferenceEnable(
                            storyboard.getProjectId(), userId, libraryNames);
                    java.util.List<String> unexpectedMissing = enablePlan.missingNames();
                    if (CollectionUtil.isNotEmpty(unexpectedMissing))
                    {
                        log.warn("分镜图生视频有效引用二次启用时状态变化，将继续文字降级: storyboardId={}, missing={}",
                                storyboard.getId(), unexpectedMissing);
                    }
                }
            }
            additionallyEnabledIds = enablePlan.imageIdsToEnable();
            if (applyReferenceEnable)
            {
                rpsFormImageBusinessService.applyReferenceEnablePlan(enablePlan, userId);
            }

            // 可引用域=项目+用户（不按集过滤），与 StoryboardImageReferenceResolver 出图解析口径一致：
            // 项目级角色图（episode_id=0）/ 跨集复用资产图按集过滤会漏配
            List<AidRolePropSceneFormImage> imgs = java.util.Collections.emptyList();
            if (CollectionUtil.isNotEmpty(libraryNames))
            {
                var query = Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId,
                                AidRolePropSceneFormImage::getName,
                                AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getAssetId,
                                AidRolePropSceneFormImage::getSourceType,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getProjectId, storyboard.getProjectId())
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, 0)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
                if (CollectionUtil.isEmpty(additionallyEnabledIds))
                {
                    query.eq(AidRolePropSceneFormImage::getIsUse, 1);
                }
                else
                {
                    List<Long> plannedEnableIds = additionallyEnabledIds;
                    query.and(w -> w.eq(AidRolePropSceneFormImage::getIsUse, 1)
                            .or().in(AidRolePropSceneFormImage::getId, plannedEnableIds));
                }
                query.orderByAsc(AidRolePropSceneFormImage::getSortOrder)
                        .orderByAsc(AidRolePropSceneFormImage::getId);
                imgs = rolePropSceneFormImageService.list(query);
            }

            // key 统一 trim：DB name 可能带首尾空格，而占位 name 已 trim，不归一化会漏命中
            for (AidRolePropSceneFormImage img : imgs)
            {
                if (StrUtil.isNotBlank(img.getImageUrl()))
                {
                    byName.putIfAbsent(normalizeAssetRefName(img.getName()), img);
                }
            }

            Set<Long> assetIds = new java.util.LinkedHashSet<>();
            for (AidRolePropSceneFormImage img : byName.values())
            {
                if (Objects.nonNull(img.getAssetId()))
                {
                    assetIds.add(img.getAssetId());
                }
            }
            if (CollectionUtil.isNotEmpty(assetIds))
            {
                List<AidRolePropScene> assets = rolePropSceneService.list(
                        Wrappers.<AidRolePropScene>lambdaQuery()
                                .select(AidRolePropScene::getId,
                                        AidRolePropScene::getName,
                                        AidRolePropScene::getAssetType)
                                .in(AidRolePropScene::getId, assetIds)
                                .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
                for (AidRolePropScene a : assets)
                {
                    assetById.put(a.getId(), a);
                }
            }
        }

        List<Integer> orderedNs = new ArrayList<>(nameByN.keySet());
        orderedNs.sort(Integer::compareTo);
        List<ResolvedReference> refs = new ArrayList<>();
        // 收集解析不到的库内占位名称：@图片N 引用了资产库里不存在/未启用/无图的素材时，
        // 不能静默跳过（会导致参考图变少、@图片N 与 referenceImages 错位、张冠李戴），必须硬报错。
        List<String> unresolvedNames = new ArrayList<>();
        for (Integer n : orderedNs)
        {
            String formName = nameByN.get(n);
            String ovUrl = StrUtil.trim(overrides.get(formName));
            // 临时映射优先：命中即用前端直传 URL（不入库），N 槽位对齐由占位顺序保证
            if (StrUtil.isNotBlank(ovUrl))
            {
                // 仅允许本站资源（相对路径或本站域名完整URL），拒绝站外外链
                if (!mediaUrlResolver.isSiteImageUrl(ovUrl))
                {
                    log.info("多参图生视频临时参考图非本站资源: storyboardId={}, name={}, url={}",
                            storyboard.getId(), formName, ovUrl);
                    throw new ServiceException("图片不可用");
                }
                // 相对路径拼完整URL后再做远程可达性 + Content-Type 校验
                String ovFull = mediaUrlResolver.toFullUrl(ovUrl);
                if (validateRemoteUrls && !ImageUrlValidator.isValidRemoteImageUrl(ovFull))
                {
                    log.info("多参图生视频临时参考图非法(格式/不可达/非图片): storyboardId={}, name={}, url={}",
                            storyboard.getId(), formName, ovFull);
                    throw new ServiceException("图片不可用");
                }
                // 临时图：无库内资产元数据（assetName/assetType 置空、非设定卡）；URL 拼成完整地址下发
                refs.add(new ResolvedReference(n, formName, formName, null, false, ovFull));
                continue;
            }
            AidRolePropSceneFormImage hit = null;
            for (String candidate : com.aid.rps.resolver.StoryboardImageReferenceResolver.candidateLookupKeys(formName))
            {
                hit = byName.get(candidate);
                if (Objects.nonNull(hit)) { break; }
            }
            if (Objects.isNull(hit) || StrUtil.isBlank(hit.getImageUrl()))
            {
                unresolvedNames.add(formName);
                continue;
            }
            AidRolePropScene asset = hit.getAssetId() == null ? null : assetById.get(hit.getAssetId());
            String assetName = asset == null ? null : asset.getName();
            String assetType = asset == null ? null : asset.getAssetType();
            boolean settingCard = isSettingCard(hit, formName);
            // DB 存相对路径，下游 provider 需完整 URL
            String fullUrl = mediaUrlResolver.toFullUrl(hit.getImageUrl());
            refs.add(new ResolvedReference(n, formName, assetName, assetType, settingCard, fullUrl));
        }
        // 资产删除/改名属于可恢复的历史数据变化：缺失占位改成普通文字，不再作为图片槽位下发。
        if (CollectionUtil.isNotEmpty(unresolvedNames))
        {
            log.warn("分镜图生视频引用文字降级: storyboardId={}, unresolved={}, formatted={}, refTotal={}",
                    storyboard.getId(), unresolvedNames, formatMissingRefs(nameByN, unresolvedNames), nameByN.size());
        }
        ReferenceResolution resolution = compactResolvedReferences(videoPrompt, refs);
        log.info("分镜图生视频参考解析完成: storyboardId={}, refTotal={}, resolved={}, degraded={}, tempOverride={}",
                storyboard.getId(), nameByN.size(), resolution.references.size(),
                nameByN.size() - resolution.references.size(), nameByN.size() - libraryNames.size());
        return resolution;
    }

    /**
     * 把有效引用压缩成连续的图片1..N；缺失引用剥掉私有占位壳，仅保留名称作为自然语言描述。
     */
    private ReferenceResolution compactResolvedReferences(String prompt, List<ResolvedReference> resolved)
    {
        List<ResolvedReference> ordered = new ArrayList<>(resolved);
        ordered.sort(java.util.Comparator.comparingInt(ResolvedReference::getOriginalN));
        Map<Integer, ResolvedReference> compactByOriginal = new LinkedHashMap<>();
        List<ResolvedReference> compactRefs = new ArrayList<>();
        for (ResolvedReference ref : ordered)
        {
            int compactN = compactRefs.size() + 1;
            ResolvedReference compact = new ResolvedReference(compactN, ref.getFormName(), ref.getAssetName(),
                    ref.getAssetType(), ref.isSettingCard(), ref.getUrl());
            compactByOriginal.put(ref.getOriginalN(), compact);
            compactRefs.add(compact);
        }
        Matcher matcher = REF_PATTERN.matcher(StrUtil.nullToEmpty(prompt));
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find())
        {
            int originalN;
            try { originalN = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException e) { matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group())); continue; }
            ResolvedReference compact = compactByOriginal.get(originalN);
            String rawName = StrUtil.trimToEmpty(matcher.group(2));
            String replacement = Objects.nonNull(compact)
                    ? "@图片" + compact.getOriginalN() + "[" + compact.getFormName() + "]"
                    : rawName;
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return new ReferenceResolution(rewritten.toString(), compactRefs);
    }

    /** 单镜头参考解析结果：实际下发提示词与图片数组必须作为同一份快照使用。 */
    private static final class ReferenceResolution
    {
        final String prompt;
        final List<ResolvedReference> references;

        ReferenceResolution(String prompt, List<ResolvedReference> references)
        {
            this.prompt = prompt;
            this.references = references;
        }
    }

    /** 判定 form_image 是否角色多方位设定卡：source_type=ai_builder 或 name 以 _角色设定 结尾。 */
    private boolean isSettingCard(AidRolePropSceneFormImage img, String formName)
    {
        if (img != null && "ai_builder".equalsIgnoreCase(StrUtil.trimToEmpty(img.getSourceType())))
        {
            return true;
        }
        return StrUtil.endWith(formName, "_角色设定");
    }

    /** 把缺失的参考占位格式化为"图片N[name]、图片M[name2]"（按 N 升序），用于建任务前同步硬失败的精确提示。 */
    private String formatMissingRefs(Map<Integer, String> nameByN, java.util.Collection<String> missingNames)
    {
        java.util.Set<String> missing = new java.util.HashSet<>(missingNames);
        List<Integer> ns = new ArrayList<>(nameByN.keySet());
        ns.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (Integer n : ns)
        {
            String name = nameByN.get(n);
            if (missing.contains(name))
            {
                if (sb.length() > 0) { sb.append('、'); }
                sb.append("图片").append(n).append('[').append(name).append(']');
            }
        }
        return sb.toString();
    }

    /**
     * 资产引用名归一化：把名称结构分隔符的"连字符 / 全角横线"统一成下划线后再比对。
     * 资产库 {@code aid_role_prop_scene_form_image.name} 以下划线 {@code _} 作为「角色名_形态」「场景名_方位」
     * 分隔符；上游 video_prompt 的 {@code @图片N[name]} 偶发/历史把 {@code _} 写成 {@code -}，精确匹配会被
     * 「引用即启用 / 缺失校验」误报缺失。本方法仅用于匹配比对，把常见横线变体统一为下划线消除该类误判。
     */
    private static String normalizeAssetRefName(String s)
    {
        if (s == null) { return ""; }
        String t = StrUtil.trim(s);
        return t.replace('-', '_').replace('－', '_').replace('‐', '_').replace('–', '_');
    }

    /** 解析 image_prompt 中 @图片N[name] 占位（按 N 升序去重）。 */
    private Map<Integer, String> parseAtReferences(String imagePrompt)
    {
        Map<Integer, String> nameByN = new LinkedHashMap<>();
        if (StrUtil.isBlank(imagePrompt))
        {
            return nameByN;
        }
        Matcher m = REF_PATTERN.matcher(imagePrompt);
        while (m.find())
        {
            int n;
            try
            {
                n = Integer.parseInt(m.group(1));
            }
            catch (NumberFormatException e)
            {
                continue;
            }
            String name = StrUtil.trimToEmpty(m.group(2));
            if (StrUtil.isBlank(name))
            {
                continue;
            }
            nameByN.putIfAbsent(n, name);
        }
        return nameByN;
    }

    /** 首帧垫图：baseImageRecordId 优先 → 分镜 final_image_id 兜底 → 都为空返回 null。 */
    private String resolveBaseImageUrl(Long baseImageRecordId, AidStoryboard storyboard, Long userId)
    {
        Long recordId = Objects.nonNull(baseImageRecordId) ? baseImageRecordId : storyboard.getFinalImageId();
        if (Objects.isNull(recordId))
        {
            return null;
        }
        AidGenRecord rec = aidGenRecordService.getOne(
                Wrappers.<AidGenRecord>lambdaQuery()
                        .select(AidGenRecord::getId, AidGenRecord::getUserId,
                                AidGenRecord::getStoryboardId, AidGenRecord::getFileUrl,
                                AidGenRecord::getDelFlag)
                        .eq(AidGenRecord::getId, recordId)
                        .last("limit 1"),
                false);
        if (Objects.isNull(rec) || !DEL_FLAG_NORMAL.equals(rec.getDelFlag()))
        {
            log.error("首帧底图记录不存在或已删除: recordId={}, storyboardId={}", recordId, storyboard.getId());
            throw new ServiceException("底图不可用");
        }
        if (!Objects.equals(userId, rec.getUserId()))
        {
            log.error("首帧底图归属校验失败: recordId={}, ownerUserId={}, requestUserId={}",
                    recordId, rec.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        if (!Objects.equals(storyboard.getId(), rec.getStoryboardId()))
        {
            log.error("首帧底图与分镜不匹配: recordId={}, recStoryboardId={}, requestStoryboardId={}",
                    recordId, rec.getStoryboardId(), storyboard.getId());
            throw new ServiceException("底图不可用");
        }
        if (StrUtil.isBlank(rec.getFileUrl()))
        {
            log.error("首帧底图 fileUrl 为空: recordId={}", recordId);
            throw new ServiceException("底图未就绪");
        }
        // DB 存相对路径，下游 provider 需完整 URL（与图生图链路 / 资产提取链路保持一致）
        return mediaUrlResolver.toFullUrl(rec.getFileUrl());
    }
    /** 单镜头准备结果（解析后的不可变快照，taskId 在建任务后再绑定）。 */
    private static final class PreparedShot
    {
        final AidStoryboard storyboard;
        final String finalPrompt;
        final String rawVideoPrompt;
        final List<String> referenceImages;
        final String baseImageUrl;
        final String userVideoPromptInput;
        final int takeCount;
        /** 首尾帧出片专用：尾帧图 URL（其余方向为 null） */
        final String lastFrameImageUrl;
        /** 首尾帧出片专用：首图记录 ID（走库内记录时落 aid_gen_record.first_image_id；上传图为 null） */
        final Long firstImageRecordId;
        /** 首尾帧出片专用：尾图记录 ID（走库内记录时落 aid_gen_record.last_image_id；上传图为 null） */
        final Long lastImageRecordId;
        /** 首尾帧出片专用：首图上传 URL 原始值（续生快照持久化用；走记录时为 null） */
        final String firstImageUrl;
        /** 首尾帧出片专用：尾图上传 URL 原始值（续生快照持久化用；走记录/无尾帧时为 null） */
        final String lastImageUrl;
        /** 已解析并完成模型能力校验的参考音频。 */
        final List<ReferenceAudioInput> referenceAudios;
        /** 已解析的服务端可信参考视频。 */
        final List<ReferenceVideoInput> referenceVideos;
        /** 厂商专属扩展参数（装配策略产出，如 Vidu 主体调用 subjects），提交时并入 options */
        final Map<String, Object> providerExtraOptions;
        /** 分镜脚本中的原始建议时长（秒） */
        final Integer recommendedDurationSeconds;
        /** 按所选模型档位归一化后的实际生成时长（秒） */
        final Integer durationSeconds;
        /** 实际生成时长来源 */
        final String durationSource;

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount)
        {
            this(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl, userVideoPromptInput,
                    takeCount, null, null, null, null, null, null, null, null, null, null);
        }

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount,
                     Map<String, Object> providerExtraOptions)
        {
            this(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl, userVideoPromptInput,
                    takeCount, null, null, null, null, null, null, providerExtraOptions, null, null, null);
        }

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount,
                     String lastFrameImageUrl, Long firstImageRecordId, Long lastImageRecordId,
                     String firstImageUrl, String lastImageUrl,
                     List<ReferenceAudioInput> referenceAudios)
        {
            this(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl, userVideoPromptInput,
                    takeCount, lastFrameImageUrl, firstImageRecordId, lastImageRecordId,
                    firstImageUrl, lastImageUrl, referenceAudios, null, null, null, null);
        }

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount,
                     String lastFrameImageUrl, Long firstImageRecordId, Long lastImageRecordId,
                     String firstImageUrl, String lastImageUrl,
                     List<ReferenceAudioInput> referenceAudios,
                     Map<String, Object> providerExtraOptions, Integer recommendedDurationSeconds,
                     Integer durationSeconds, String durationSource)
        {
            this(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl, userVideoPromptInput,
                    takeCount, lastFrameImageUrl, firstImageRecordId, lastImageRecordId,
                    firstImageUrl, lastImageUrl, referenceAudios, providerExtraOptions,
                    recommendedDurationSeconds, durationSeconds, durationSource, null);
        }

        PreparedShot(AidStoryboard storyboard, String finalPrompt, String rawVideoPrompt,
                     List<String> referenceImages, String baseImageUrl, String userVideoPromptInput, int takeCount,
                     String lastFrameImageUrl, Long firstImageRecordId, Long lastImageRecordId,
                     String firstImageUrl, String lastImageUrl,
                     List<ReferenceAudioInput> referenceAudios,
                     Map<String, Object> providerExtraOptions, Integer recommendedDurationSeconds,
                     Integer durationSeconds, String durationSource, List<ReferenceVideoInput> referenceVideos)
        {
            this.storyboard = storyboard;
            this.finalPrompt = finalPrompt;
            this.rawVideoPrompt = rawVideoPrompt;
            this.referenceImages = (referenceImages == null)
                    ? java.util.Collections.emptyList() : referenceImages;
            this.baseImageUrl = baseImageUrl;
            this.userVideoPromptInput = userVideoPromptInput;
            this.takeCount = takeCount;
            this.lastFrameImageUrl = lastFrameImageUrl;
            this.firstImageRecordId = firstImageRecordId;
            this.lastImageRecordId = lastImageRecordId;
            this.firstImageUrl = firstImageUrl;
            this.lastImageUrl = lastImageUrl;
            this.referenceAudios = (referenceAudios == null)
                    ? java.util.Collections.emptyList() : referenceAudios;
            this.referenceVideos = (referenceVideos == null)
                    ? java.util.Collections.emptyList() : List.copyOf(referenceVideos);
            this.providerExtraOptions = (providerExtraOptions == null)
                    ? java.util.Collections.emptyMap() : providerExtraOptions;
            this.recommendedDurationSeconds = recommendedDurationSeconds;
            this.durationSeconds = durationSeconds;
            this.durationSource = durationSource;
        }

        PreparedShot withDuration(Integer recommendedDurationSeconds, Integer durationSeconds, String durationSource)
        {
            return new PreparedShot(storyboard, finalPrompt, rawVideoPrompt, referenceImages, baseImageUrl,
                    userVideoPromptInput, takeCount, lastFrameImageUrl, firstImageRecordId, lastImageRecordId,
                    firstImageUrl, lastImageUrl, referenceAudios, providerExtraOptions, recommendedDurationSeconds,
                    durationSeconds, durationSource, referenceVideos);
        }
    }

    /** 解析并校验本镜实际提交的参考音频；不读取历史 script_params。 */
    private List<ReferenceAudioInput> resolveAndValidateReferenceAudios(String prompt, AidStoryboard storyboard,
            Long userId, ExplicitAudioSelection selection, AiModelConfigVo modelConfig, Boolean generateAudio)
    {
        ExplicitAudioSelection effective = Objects.isNull(selection) ? ExplicitAudioSelection.EMPTY : selection;
        StoryboardPromptAudioReferenceResolver.ResolveResult resolved = promptAudioReferenceResolver.resolve(
                prompt, storyboard.getProjectId(), storyboard.getEpisodeId(), userId,
                effective.audioRecordIds, effective.referenceAudioIds);
        // 提示词自身编号矛盾属于内容问题：按首次绑定取值继续，不阻断出片
        if (resolved.isInvalidAudioIndex())
        {
            log.warn("分镜参考音频编号冲突已降级: storyboardId={}", storyboard.getId());
        }
        // 用户显式选择的音频记录不可用必须报错，否则用户以为已生效
        if (CollectionUtil.isNotEmpty(resolved.getUnresolvedAudioRecordIds()))
        {
            log.info("分镜参考音频记录不可用: storyboardId={}, ids={}",
                    storyboard.getId(), resolved.getUnresolvedAudioRecordIds());
            throw new ReferenceAudioValidationException("参考音频不可用");
        }
        // 用户显式选择的上传参考音频同理，不可用必须报错
        if (CollectionUtil.isNotEmpty(resolved.getUnresolvedReferenceAudioIds()))
        {
            log.info("分镜上传参考音频不可用: storyboardId={}, ids={}",
                    storyboard.getId(), resolved.getUnresolvedReferenceAudioIds());
            throw new ReferenceAudioValidationException("参考音频不可用");
        }
        // 角色未绑音色属于提示词推导出的隐式引用，跳过该条继续出片
        if (CollectionUtil.isNotEmpty(resolved.getUnresolvedAudioNames()))
        {
            log.warn("分镜角色未绑音色已跳过参考音频: storyboardId={}, unresolved={}",
                    storyboard.getId(), resolved.getUnresolvedAudioNames());
        }
        if (CollectionUtil.isEmpty(resolved.getAudioReferences()))
        {
            return new ArrayList<>();
        }
        MediaVideoGenerateRequest validationRequest = new MediaVideoGenerateRequest();
        validationRequest.setAudio(generateAudio);
        validationRequest.setReferenceAudios(resolved.getAudioReferences());
        try
        {
            ModelCapabilityValidator.normalizeAndValidateVideoAudio(modelConfig, validationRequest);
            ModelCapabilityValidator.normalizeAndValidateReferenceAudios(modelConfig, validationRequest);
        }
        catch (ServiceException ex)
        {
            throw new ReferenceAudioValidationException(ex.getMessage());
        }
        // 归一后的列表已完成去重、严格上限校验与编号重排，实际下发以此为准
        return validationRequest.getReferenceAudios();
    }

    /** 按能力校验后的实际参考音频列表同步提示词占位。 */
    private String alignPromptWithResolvedRoleAudios(String prompt, AidStoryboard storyboard,
            List<ReferenceAudioInput> referenceAudios)
    {
        // 即使最终没有可下发音频，也必须清掉旧编号协议，防止无效占位残留或误绑后续显式音频。
        List<String> resolvedRoleNames = CollectionUtil.isEmpty(referenceAudios)
                ? Collections.emptyList()
                : referenceAudios.stream()
                .filter(Objects::nonNull)
                .filter(audio -> Objects.equals(ReferenceAudioInput.SOURCE_VOICE_SAMPLE, audio.getSourceType()))
                .map(ReferenceAudioInput::getName)
                .filter(StrUtil::isNotBlank)
                .toList();
        return roleAudioReferenceResolver.alignPromptToResolvedRoleReferences(
                prompt, storyboard.getDialogueText(), resolvedRoleNames);
    }

    /**
     * 取本镜实际生效的显式参考音频选择：续生快照优先，其次单镜头请求入参。
     * 批量出片的顶层入参只对单镜头有效，不能广播到每个镜头。
     *
     * @param single            是否单镜头请求
     * @param requestSelection  请求顶层的显式选择
     * @param snapshotSelection 续生快照固化的显式选择
     * @return 生效的显式选择；均无返回空选择
     */
    private ExplicitAudioSelection resolveShotAudioSelection(boolean single,
            ExplicitAudioSelection requestSelection, ExplicitAudioSelection snapshotSelection)
    {
        if (Objects.nonNull(snapshotSelection) && !snapshotSelection.isEmpty())
        {
            return snapshotSelection;
        }
        return (single && Objects.nonNull(requestSelection)) ? requestSelection : ExplicitAudioSelection.EMPTY;
    }

    /** 解析用户显式选择的参考视频；只接受服务端生成记录 ID。 */
    private List<ReferenceVideoInput> resolveReferenceVideos(AidStoryboard storyboard, Long userId,
            List<Long> referenceVideoRecordIds, boolean allowProbe)
    {
        if (CollectionUtil.isEmpty(referenceVideoRecordIds))
        {
            return Collections.emptyList();
        }
        return referenceVideoRecordResolver.resolve(
                referenceVideoRecordIds, userId, storyboard.getProjectId(), allowProbe);
    }

    /** 用户显式选择的参考音频不可用时抛出，供单镜头链路转换为 ServiceException。 */
    private static final class ReferenceAudioValidationException extends RuntimeException
    {
        ReferenceAudioValidationException(String message)
        {
            super(message);
        }
    }

    /** 单镜头准备回调（锁内执行；解析失败抛 ServiceException）。 */
    @FunctionalInterface
    private interface ShotPreparer
    {
        PreparedShot prepare(AidStoryboard storyboard);
    }

    /** 多参方向单镜头准备：提示词来源 + 参考图解析 + 厂商装配。 */
    private PreparedShot prepareMultiShot(AidStoryboard sb, boolean single,
            StoryboardVideoGenerateRequest request, AiModelConfigVo modelConfig, Long userId, int takeCount,
            ExplicitAudioSelection snapshotSelection, List<Long> snapshotVideoRecordIds,
            boolean applyMutations, boolean validateRemoteUrls)
    {
        StoryboardVideoAdvancedOptions.merge(Map.of(), request, modelConfig);
        String overridePrompt = single ? request.getVideoPrompt() : null;
        String videoPrompt = resolveVideoPrompt(overridePrompt, sb);
        if (StrUtil.isBlank(overridePrompt))
        {
            videoPrompt = roleAudioReferenceResolver.normalizeStoredVideoPrompt(
                    videoPrompt, sb.getDialogueText());
        }
        List<ReferenceAudioInput> referenceAudios = resolveAndValidateReferenceAudios(videoPrompt, sb, userId,
                resolveShotAudioSelection(single, new ExplicitAudioSelection(
                        request.getReferenceAudioRecordIds(), request.getReferenceAudioIds()), snapshotSelection),
                modelConfig, request.getGenerateAudio());
        List<Long> referenceVideoRecordIds = single
                ? request.getReferenceVideoRecordIds() : snapshotVideoRecordIds;
        List<ReferenceVideoInput> referenceVideos = resolveReferenceVideos(sb, userId,
                referenceVideoRecordIds, false);
        validateReferenceVideoCountBeforeSubmit(modelConfig, referenceVideos);
        // 分镜层只解析权属与服务端已存元数据；正式生成在媒体主链路完成
        // 唯一一次可信内容探测，避免同一个视频在预处理和预扣前重复下载。
        videoPrompt = alignPromptWithResolvedRoleAudios(videoPrompt, sb, referenceAudios);
        if (applyMutations && single && StrUtil.isNotBlank(overridePrompt))
        {
            persistVideoPrompt(sb.getId(), userId, videoPrompt);
        }
        ReferenceResolution resolution = resolveReferences(videoPrompt, sb, userId,
                single ? request.getReferenceOverrides() : null, applyMutations, validateRemoteUrls);
        boolean videoSourceOnly = "edit".equals(request.getOmniReferenceTaskType())
                || "extend".equals(request.getOmniReferenceTaskType());
        boolean forbidsImageInput = Boolean.FALSE.equals(modelConfig.getSupportsImageInput());
        String baseImageUrl = (videoSourceOnly || forbidsImageInput) && request.getBaseImageRecordId() == null
                ? null : resolveBaseImageUrl(single ? request.getBaseImageRecordId() : null, sb, userId);
        int maxRefImages = ReferenceImageLimiter.resolveMax(modelConfig, MAX_REFERENCE_IMAGES);
        int actualReferenceImages = countDistinctReferenceImages(resolution.references, baseImageUrl);
        if (maxRefImages == ReferenceImageLimiter.FORBID && actualReferenceImages > 0)
        {
            log.info("多参视频模型禁止参考图: storyboardId={}, actual={}",
                    sb.getId(), actualReferenceImages);
            throw new ServiceException("模型不支持图片");
        }
        else if (maxRefImages >= 0 && actualReferenceImages > maxRefImages)
        {
            log.info("多参视频参考图数量超限: storyboardId={}, max={}, actual={}",
                    sb.getId(), maxRefImages, actualReferenceImages);
            throw new ServiceException("参考图数量超限");
        }
        int minReferenceImages = ReferenceImageLimiter.readMinFromCapabilityJson(
                modelConfig.getCapabilityJson());
        boolean klingModel = KlingConstants.PROVIDER_CODE.equalsIgnoreCase(
                StrUtil.trim(modelConfig.getProviderCode()));
        if (actualReferenceImages < minReferenceImages)
        {
            log.info("多参视频有效参考图不足: storyboardId={}, required={}, actual={}",
                    sb.getId(), minReferenceImages, actualReferenceImages);
            throw new ServiceException("请选择参考图");
        }
        if (CollectionUtil.isEmpty(resolution.references)
                && StrUtil.isBlank(baseImageUrl)
                && CollectionUtil.isEmpty(referenceVideos)
                && Boolean.FALSE.equals(modelConfig.getSupportsTextInput()))
        {
            log.info("多参视频零引用且模型不支持纯文本: storyboardId={}, modelCode={}",
                    sb.getId(), modelConfig.getModelCode());
            throw new ServiceException("请选择参考图");
        }
        VideoReferenceContext ctx = new VideoReferenceContext(resolution.prompt, request.getUserInputText(),
                resolution.references, baseImageUrl, modelConfig, request.getGenerateAudio(), maxRefImages);
        VideoReferencePlan plan = videoReferencePlanner.plan(ctx);
        if (klingModel)
        {
            validateKlingPlannedReferenceInputs(modelConfig, plan, referenceVideos);
        }
        String finalPrompt = plan.getFinalPrompt();
        if (StrUtil.length(finalPrompt) > MAX_PROMPT_LENGTH)
        {
            log.info("多参图生视频最终提示词过长: storyboardId={}, len={}", sb.getId(), StrUtil.length(finalPrompt));
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        return new PreparedShot(sb, finalPrompt, videoPrompt, plan.getReferenceImageUrls(),
                plan.getFirstFrameImageUrl(), overridePrompt, takeCount, null, null, null, null, null,
                referenceAudios, StoryboardVideoAdvancedOptions.merge(plan.getExtraOptions(), request, modelConfig),
                null, null, null, referenceVideos);
    }

    /** 模型图片上下限按真实下发素材计数：显式首帧与提示词参考图合并去重。 */
    private int countDistinctReferenceImages(List<ResolvedReference> references, String baseImageUrl)
    {
        Set<String> urls = new TreeSet<>();
        if (StrUtil.isNotBlank(baseImageUrl))
        {
            urls.add(baseImageUrl.trim());
        }
        if (CollectionUtil.isNotEmpty(references))
        {
            references.stream()
                    .filter(Objects::nonNull)
                    .map(ResolvedReference::getUrl)
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .forEach(urls::add);
        }
        return urls.size();
    }

    /** 交给媒体主链路前先按模型全局/场景最大值拒绝异常数量。 */
    private void validateReferenceVideoCountBeforeSubmit(AiModelConfigVo modelConfig,
            List<ReferenceVideoInput> referenceVideos)
    {
        if (CollectionUtil.isEmpty(referenceVideos))
        {
            return;
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(
                modelConfig == null ? null : modelConfig.getCapabilityJson());
        if (capability == null)
        {
            return;
        }
        JsonNode supportsVideo = capability.get("supportsVideoInput");
        if (supportsVideo != null && supportsVideo.isBoolean() && !supportsVideo.asBoolean())
        {
            throw new ServiceException("模型不支持视频");
        }
        Integer maximum = nonNegativeInteger(capability.get("maxReferenceVideos"));
        if (maximum == null && capability.path("sceneRules").isObject())
        {
            int largestSceneMaximum = -1;
            java.util.Iterator<JsonNode> scenes = capability.path("sceneRules").elements();
            while (scenes.hasNext())
            {
                Integer sceneMaximum = nonNegativeInteger(scenes.next().get("maxReferenceVideos"));
                if (sceneMaximum != null)
                {
                    largestSceneMaximum = Math.max(largestSceneMaximum, sceneMaximum);
                }
            }
            maximum = largestSceneMaximum < 0 ? null : largestSceneMaximum;
        }
        if (maximum != null && referenceVideos.size() > maximum)
        {
            log.info("参考视频探测前数量超限: modelCode={}, max={}, actual={}",
                    modelConfig.getModelCode(), maximum, referenceVideos.size());
            throw new ServiceException("参考视频数量超限");
        }
    }

    private Integer nonNegativeInteger(JsonNode value)
    {
        return value != null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= 0 ? value.intValue() : null;
    }

    /** 前序提示词尚未生成时，只规划当前请求已确定的垫图与显式参考音频。 */
    private PreparedShot preparePlannedMultiShot(AidStoryboard storyboard, boolean single,
            StoryboardVideoGenerateRequest request, AiModelConfigVo modelConfig,
            Long userId, int takeCount)
    {
        StoryboardVideoAdvancedOptions.merge(Map.of(), request, modelConfig);
        List<ReferenceAudioInput> referenceAudios = resolveAndValidateReferenceAudios(
                "", storyboard, userId,
                resolveShotAudioSelection(single, new ExplicitAudioSelection(
                        request.getReferenceAudioRecordIds(), request.getReferenceAudioIds()), null),
                modelConfig, request.getGenerateAudio());
        List<ReferenceVideoInput> referenceVideos = resolveReferenceVideos(storyboard, userId,
                single ? request.getReferenceVideoRecordIds() : null, false);
        validateReferenceVideoCountBeforeSubmit(modelConfig, referenceVideos);
        boolean videoSourceOnly = "edit".equals(request.getOmniReferenceTaskType())
                || "extend".equals(request.getOmniReferenceTaskType());
        boolean forbidsImageInput = Boolean.FALSE.equals(modelConfig.getSupportsImageInput());
        String baseImageUrl = (videoSourceOnly || forbidsImageInput) && request.getBaseImageRecordId() == null
                ? null : resolveBaseImageUrl(single ? request.getBaseImageRecordId() : null, storyboard, userId);
        int maxReferences = ReferenceImageLimiter.resolveMax(modelConfig, MAX_REFERENCE_IMAGES);
        String plannedPrompt = single ? StrUtil.blankToDefault(request.getVideoPrompt(), "") : "";
        ReferenceResolution resolution = resolveReferences(plannedPrompt, storyboard, userId,
                single ? request.getReferenceOverrides() : null, false, false);
        int actualReferenceImages = countDistinctReferenceImages(resolution.references, baseImageUrl);
        if (maxReferences == ReferenceImageLimiter.FORBID && actualReferenceImages > 0)
        {
            throw new ServiceException("模型不支持图片");
        }
        if (maxReferences >= 0 && actualReferenceImages > maxReferences)
        {
            throw new ServiceException("参考图数量超限");
        }
        int minReferenceImages = ReferenceImageLimiter.readMinFromCapabilityJson(
                modelConfig.getCapabilityJson());
        if (actualReferenceImages < minReferenceImages)
        {
            throw new ServiceException("请选择参考图");
        }
        if (CollectionUtil.isEmpty(resolution.references)
                && StrUtil.isBlank(baseImageUrl)
                && CollectionUtil.isEmpty(referenceVideos)
                && Boolean.FALSE.equals(modelConfig.getSupportsTextInput()))
        {
            throw new ServiceException("请选择参考图");
        }
        VideoReferencePlan plan = videoReferencePlanner.plan(new VideoReferenceContext(
                resolution.prompt, null, resolution.references, baseImageUrl, modelConfig,
                request.getGenerateAudio(), maxReferences));
        if (KlingConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(modelConfig.getProviderCode())))
        {
            validateKlingPlannedReferenceInputs(modelConfig, plan, referenceVideos);
        }
        return new PreparedShot(storyboard, null, null, plan.getReferenceImageUrls(),
                plan.getFirstFrameImageUrl(), null, takeCount,
                null, null, null, null, null, referenceAudios,
                StoryboardVideoAdvancedOptions.merge(plan.getExtraOptions(), request, modelConfig),
                null, null, null, referenceVideos);
    }

    /** 按分镜策略规划后的真实可灵派发字段校验场景输入。 */
    static void validateKlingPlannedReferenceInputs(AiModelConfigVo modelConfig, VideoReferencePlan plan)
    {
        validateKlingPlannedReferenceInputs(modelConfig, plan, Collections.emptyList());
    }

    /** 按分镜策略规划后的真实可灵派发字段校验场景输入。 */
    static void validateKlingPlannedReferenceInputs(AiModelConfigVo modelConfig, VideoReferencePlan plan,
            List<ReferenceVideoInput> referenceVideos)
    {
        MediaVideoGenerateRequest plannedRequest = new MediaVideoGenerateRequest();
        plannedRequest.setImageUrl(plan.getFirstFrameImageUrl());
        Map<String, Object> options = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(plan.getReferenceImageUrls()))
        {
            options.put("referenceImages", new ArrayList<>(plan.getReferenceImageUrls()));
        }
        if (CollectionUtil.isNotEmpty(plan.getExtraOptions()))
        {
            // 与 submitSingleVideoMedia 保持相同覆盖顺序，校验看到的就是最终下发 options。
            options.putAll(plan.getExtraOptions());
        }
        if (CollectionUtil.isNotEmpty(referenceVideos))
        {
            options.put("referenceVideos", referenceVideos.stream()
                    .map(ReferenceVideoInput::getVideoUrl).toList());
        }
        plannedRequest.setOptions(options);
        KlingVideoRequestBuilder.validateRequestInputs(modelConfig, plannedRequest);
    }

    /** 图生方向单镜头准备：提示词回落 video_prompt_image + 单张参考图（前端直传或回落分镜主图）。 */
    private PreparedShot prepareImageShot(AidStoryboard sb, boolean single,
            StoryboardVideoFromImageGenerateRequest request, AiModelConfigVo modelConfig, Long userId, int takeCount,
            ExplicitAudioSelection snapshotSelection, boolean applyMutations, boolean validateRemoteUrls)
    {
        boolean useFrontPrompt = single && StrUtil.isNotBlank(request.getVideoPrompt());
        String videoPrompt = useFrontPrompt ? request.getVideoPrompt() : sb.getVideoPromptImage();
        if (StrUtil.isBlank(videoPrompt))
        {
            log.info("图生视频提示词为空(video_prompt_image 未生成且未传入): storyboardId={}", sb.getId());
            throw new ServiceException("请先生成提示词");
        }
        if (videoPrompt.length() > MAX_PROMPT_LENGTH)
        {
            log.info("图生视频提示词过长(含库回落): storyboardId={}, len={}", sb.getId(), videoPrompt.length());
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        if (!useFrontPrompt)
        {
            videoPrompt = roleAudioReferenceResolver.normalizeStoredVideoPrompt(
                    videoPrompt, sb.getDialogueText());
        }
        List<ReferenceAudioInput> referenceAudios = resolveAndValidateReferenceAudios(videoPrompt, sb, userId,
                resolveShotAudioSelection(single, new ExplicitAudioSelection(
                        request.getReferenceAudioRecordIds(), request.getReferenceAudioIds()), snapshotSelection),
                modelConfig, request.getGenerateAudio());
        videoPrompt = alignPromptWithResolvedRoleAudios(videoPrompt, sb, referenceAudios);
        int maxRef = ReferenceImageLimiter.resolveMax(modelConfig, MAX_REFERENCE_IMAGES);
        if (maxRef == ReferenceImageLimiter.FORBID)
        {
            log.info("图生视频模型禁用参考图: storyboardId={}", sb.getId());
            throw new ServiceException("该模型不支持图片");
        }
        String refImageUrl = resolveImageDirectionInput(
                sb, single, request, userId, validateRemoteUrls);
        if (StrUtil.isBlank(refImageUrl))
        {
            log.info("图生视频无参考图且无分镜主图: storyboardId={}", sb.getId());
            throw new ServiceException("请选择参考图");
        }
        if (applyMutations && useFrontPrompt)
        {
            persistVideoPromptImage(sb.getId(), userId, videoPrompt);
        }
        List<ResolvedReference> references = new ArrayList<>();
        references.add(new ResolvedReference(1, "参考图1", "参考图1", null, false, refImageUrl));
        String resolvedBaseImageUrl = (single && Objects.nonNull(request.getBaseImageRecordId()))
                ? resolveBaseImageUrl(request.getBaseImageRecordId(), sb, userId) : null;
        VideoReferenceContext ctx = new VideoReferenceContext(videoPrompt, request.getUserInputText(),
                references, resolvedBaseImageUrl, modelConfig, request.getGenerateAudio(), maxRef);
        VideoReferencePlan plan = videoReferencePlanner.plan(ctx);
        String finalPrompt = plan.getFinalPrompt();
        if (StrUtil.length(finalPrompt) > MAX_PROMPT_LENGTH)
        {
            log.info("图生视频最终提示词过长: storyboardId={}, len={}", sb.getId(), StrUtil.length(finalPrompt));
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        return new PreparedShot(sb, finalPrompt, videoPrompt, plan.getReferenceImageUrls(),
                plan.getFirstFrameImageUrl(), useFrontPrompt ? request.getVideoPrompt() : null, takeCount,
                null, null, null, null, null, referenceAudios, plan.getExtraOptions(), null, null, null);
    }

    private PreparedShot preparePlannedImageShot(AidStoryboard storyboard, boolean single,
            StoryboardVideoFromImageGenerateRequest request, AiModelConfigVo modelConfig,
            Long userId, int takeCount)
    {
        int maxReferences = ReferenceImageLimiter.resolveMax(modelConfig, MAX_REFERENCE_IMAGES);
        if (maxReferences == ReferenceImageLimiter.FORBID)
        {
            throw new ServiceException("该模型不支持图片");
        }
        String referenceImageUrl = resolveImageDirectionInput(
                storyboard, single, request, userId, false);
        if (StrUtil.isBlank(referenceImageUrl))
        {
            throw new ServiceException("请选择参考图");
        }
        List<ReferenceAudioInput> referenceAudios = resolveAndValidateReferenceAudios(
                "", storyboard, userId,
                resolveShotAudioSelection(single, new ExplicitAudioSelection(
                        request.getReferenceAudioRecordIds(), request.getReferenceAudioIds()), null),
                modelConfig, request.getGenerateAudio());
        List<ResolvedReference> references = List.of(
                new ResolvedReference(1, "参考图1", "参考图1", null, false, referenceImageUrl));
        String baseImageUrl = single && Objects.nonNull(request.getBaseImageRecordId())
                ? resolveBaseImageUrl(request.getBaseImageRecordId(), storyboard, userId) : null;
        VideoReferencePlan plan = videoReferencePlanner.plan(new VideoReferenceContext(
                "", null, references, baseImageUrl, modelConfig,
                request.getGenerateAudio(), maxReferences));
        return new PreparedShot(storyboard, null, null, plan.getReferenceImageUrls(),
                plan.getFirstFrameImageUrl(), null, takeCount,
                null, null, null, null, null, referenceAudios,
                plan.getExtraOptions(), null, null, null);
    }

    private String resolveImageDirectionInput(
            AidStoryboard storyboard, boolean single,
            StoryboardVideoFromImageGenerateRequest request, Long userId,
            boolean validateRemoteUrl)
    {
        String referenceImageUrl = null;
        boolean fromUpload = false;
        if (single && CollectionUtil.isNotEmpty(request.getImages()))
        {
            for (String candidate : request.getImages())
            {
                String url = StrUtil.trim(candidate);
                if (StrUtil.isNotBlank(url))
                {
                    referenceImageUrl = url;
                    fromUpload = true;
                    break;
                }
            }
        }
        if (!fromUpload)
        {
            return resolveBaseImageUrl(null, storyboard, userId);
        }
        if (!mediaUrlResolver.isSiteImageUrl(referenceImageUrl))
        {
            throw new ServiceException("图片不可用");
        }
        String fullUrl = mediaUrlResolver.toFullUrl(referenceImageUrl);
        if (validateRemoteUrl && !ImageUrlValidator.isValidRemoteImageUrl(fullUrl))
        {
            throw new ServiceException("图片不可用");
        }
        return fullUrl;
    }

    /**
     * 首尾帧方向单镜头准备：解析首图(首帧)+可选尾图(尾帧)+配音参考+提示词。
     * 首/尾帧来源二选一(上传 URL 优先于库内记录 ID)：上传 URL 走本站图片校验,记录 ID 走 {@code resolveBaseImageUrl}(须属本分镜)。
     * 首帧必填(两种都缺报「请选首帧」);尾帧可选——传则关键帧动画(首→尾),不传则仅首帧单帧图生视频。
     * 提示词:单镜头入参优先 → 库回落 {@code aid_storyboard.video_prompt} → 双空报「请先生成提示词」。
     * 参考音频按记录 ID 解析，并按所选模型能力统一校验。
     */
    private PreparedShot prepareEdgeShot(AidStoryboard sb, boolean single,
            StoryboardVideoEdgeGenerateRequest request, Map<Long, EdgeResolved> edgeMap,
            AiModelConfigVo modelConfig, Long userId, int takeCount,
            boolean applyMutations, boolean validateRemoteUrls)
    {
        EdgeResolved er = edgeMap.get(sb.getId());
        boolean firstFromUpload = er != null && StrUtil.isNotBlank(er.firstImageUrl);
        String firstUrl = (er == null) ? null : resolveEdgeFrameUrl(
                er.firstImageUrl, er.firstImageRecordId, sb, userId, validateRemoteUrls);
        if (StrUtil.isBlank(firstUrl))
        {
            log.info("首尾帧生视频缺少首帧来源(上传图/记录ID 均无): storyboardId={}", sb.getId());
            throw new ServiceException("请选择首帧");
        }
        // 上传图无对应 gen_record，记录 ID 仅在走库内记录时落库 first_image_id
        Long firstRecId = firstFromUpload ? null : (er != null ? er.firstImageRecordId : null);
        boolean lastFromUpload = er != null && StrUtil.isNotBlank(er.lastImageUrl);
        String lastUrl = (er == null) ? null : resolveEdgeFrameUrl(
                er.lastImageUrl, er.lastImageRecordId, sb, userId, validateRemoteUrls);
        Long lastRecId = lastFromUpload ? null : (er != null ? er.lastImageRecordId : null);
        String overridePrompt = single ? request.getVideoPrompt() : null;
        String videoPrompt = resolveVideoPrompt(overridePrompt, sb);
        if (StrUtil.isBlank(overridePrompt))
        {
            videoPrompt = roleAudioReferenceResolver.normalizeStoredVideoPrompt(
                    videoPrompt, sb.getDialogueText());
        }
        List<ReferenceAudioInput> referenceAudios = resolveAndValidateReferenceAudios(videoPrompt, sb, userId,
                er == null ? null : er.audioSelection, modelConfig, request.getGenerateAudio());
        videoPrompt = alignPromptWithResolvedRoleAudios(videoPrompt, sb, referenceAudios);
        if (applyMutations && single && StrUtil.isNotBlank(overridePrompt))
        {
            persistVideoPrompt(sb.getId(), userId, videoPrompt);
        }
        VideoReferenceContext ctx = new VideoReferenceContext(videoPrompt, request.getUserInputText(),
                List.of(), firstUrl, lastUrl, modelConfig, request.getGenerateAudio(), 0);
        VideoReferencePlan plan = videoReferencePlanner.plan(ctx);
        String finalPrompt = plan.getFinalPrompt();
        if (StrUtil.length(finalPrompt) > MAX_PROMPT_LENGTH)
        {
            log.info("首尾帧生视频最终提示词过长: storyboardId={}, len={}", sb.getId(), StrUtil.length(finalPrompt));
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_TOO_LONG, "提示词过长，请精简");
        }
        return new PreparedShot(sb, finalPrompt, videoPrompt, new ArrayList<>(),
                plan.getFirstFrameImageUrl(), overridePrompt, takeCount, lastUrl,
                firstRecId, lastRecId,
                firstFromUpload ? er.firstImageUrl : null, lastFromUpload ? er.lastImageUrl : null,
                referenceAudios);
    }

    /** 抢镜头级 Redis 防重锁（token + 原子 CAS + 在建宽限 + 重抢后二次查 DB）：成功返回持有的 token，失败返回 null。 */
    private String acquireShotLock(String lockKey, Long storyboardId)
    {
        // 锁值 = 时间戳:uuid，时间戳用于「在建宽限期」判定，uuid 保证全局唯一、CAS 只删/续自己的锁
        String token = newLockToken();
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(locked))
        {
            // 抢到锁后再查 DB：批量顺序执行可能让靠后镜头锁超 TTL 过期，避免对仍活跃父任务重复出片/扣费
            if (hasActiveTaskInDb(storyboardId))
            {
                releaseLockIfMine(lockKey, token);
                log.info("分镜图生视频抢锁后发现父任务仍活跃(锁曾过期)，拒绝重复出片: storyboardId={}", storyboardId);
                return null;
            }
            return token;
        }
        // SETNX 失败：DB 有活跃任务 → 真冲突，处理中
        if (hasActiveTaskInDb(storyboardId))
        {
            return null;
        }
        // DB 暂无活跃任务但锁存在：可能 (a) 并发请求刚 SETNX 成功、父任务尚未入库；(b) 进程崩溃残留泄漏锁。
        Object existing = redisCache.getCacheObject(lockKey);
        long lockTs = parseLeadingTs(existing);
        // (a) 锁仍在「在建宽限期」内 → 视为并发在建，按处理中对待，绝不抢占（避免删对方有效锁导致重复受理）
        if (lockTs > 0 && System.currentTimeMillis() - lockTs < LOCK_INFLIGHT_GRACE_MS)
        {
            log.info("分镜图生视频锁处于在建宽限期(疑似并发在建任务)，按处理中对待: storyboardId={}", storyboardId);
            return null;
        }
        // (b) 超宽限期 → 视为泄漏：原子 compare-and-delete（仅当锁仍是我读到的那个值才删，避免删掉期间被他人重抢的新锁）
        log.warn("分镜图生视频 Redis 锁疑似泄漏（DB 无活跃任务且超在建宽限期），CAS 清理重抢: lockKey={}", lockKey);
        releaseLockIfMine(lockKey, existing);
        String newToken = newLockToken();
        Boolean re = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, newToken, FORM_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(re))
        {
            // 清理后未抢到 → 期间被他人抢走，按处理中对待
            return null;
        }
        // 关键：重抢成功后再查一次 DB，闭合「A 刚插入父任务、B 已删锁重抢」的 TOCTOU 窗口
        if (hasActiveTaskInDb(storyboardId))
        {
            releaseLockIfMine(lockKey, newToken);
            log.info("分镜图生视频重抢后发现父任务已活跃，CAS 释放并拒绝: storyboardId={}", storyboardId);
            return null;
        }
        return newToken;
    }

    /** 锁值 token：毫秒时间戳:UUID。 */
    private String newLockToken()
    {
        return System.currentTimeMillis() + ":" + java.util.UUID.randomUUID();
    }

    /** 原子 compare-and-delete：仅当锁当前值 == 传入值时删除（只删自己/指定值的锁）。 */
    private void releaseLockIfMine(String lockKey, Object expectedValue)
    {
        if (lockKey == null || expectedValue == null)
        {
            return;
        }
        try
        {
            redisCache.redisTemplate.execute(SHOT_LOCK_RELEASE_SCRIPT,
                    java.util.Collections.singletonList(lockKey), expectedValue);
        }
        catch (Exception ignore)
        {
            // CAS 删除失败不阻断主流程，锁会随 TTL 自然过期
        }
    }

    /** 原子 compare-and-expire：仅当锁当前值 == token 时续租 TTL（避免续到他人锁）。 */
    private void renewLockIfMine(String lockKey, String token)
    {
        if (lockKey == null || token == null)
        {
            return;
        }
        try
        {
            // TTL 已写死在脚本中（避免 ARGV 被序列化成带引号字符串导致 EXPIRE 失败），此处只传 token 比较
            redisCache.redisTemplate.execute(SHOT_LOCK_RENEW_SCRIPT,
                    java.util.Collections.singletonList(lockKey), token);
        }
        catch (Exception ignore)
        {
            // 续租失败不阻断，acquireShotLock 已有 DB 兜底
        }
    }

    /** 解析锁值前导时间戳（token 形如 "时间戳:uuid"；旧值/非法返回 0）。 */
    private long parseLeadingTs(Object v)
    {
        if (v == null)
        {
            return 0L;
        }
        String s = String.valueOf(v).trim();
        int idx = s.indexOf(':');
        String head = idx >= 0 ? s.substring(0, idx) : s;
        try
        {
            return Long.parseLong(head);
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    /**
     * 逐镜头抢锁 + 解析 → 创建单一父任务并异步入队。
     * 单镜头（single=true）任一步失败直接抛错；多镜头时单镜头失败仅跳过并记 ShotResult，不阻断其余。
     */
    private StoryboardVideoGenerateVO submitBatch(Long userId, List<Long> ids, boolean single, int perShotCount,
            boolean batchWorkflow,
            boolean overwriteExistingFinal,
            String modelCode, AiModelConfigVo modelConfig, Long modelId, String aspectRatio, String resolution,
            Integer requestedDurationSeconds, Boolean generateAudio, String userInputText, String funcCode,
            String direction, BatchTaskSlotReservation inheritedReservation, ShotPreparer preparer)
    {
        // 支持音画同出且未传值：父任务快照与计费预检统一默认开启
        generateAudio = resolveGenerateAudioOrDefault(modelConfig, generateAudio);
        // 建父任务前拦截：不支持音画同出的模型禁止 generateAudio=true
        assertGenerateAudioAllowed(modelConfig, generateAudio);
        List<PreparedShot> prepared = new ArrayList<>();
        List<ShotLock> heldLocks = new ArrayList<>();
        List<StoryboardVideoGenerateVO.ShotResult> rejected = new ArrayList<>();
        Map<String, Boolean> recommendationPolicyByScope = new LinkedHashMap<>();
        BatchTaskSlotReservation slotReservation = null;
        boolean acquiredHere = false;
        Integer fallbackDurationSeconds = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                ? StoryboardDurationResolver.resolve(requestedDurationSeconds, null, false, single, modelConfig)
                        .durationSeconds()
                : null;
        try
        {
            for (Long id : ids)
            {
                String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + id;
                ShotLock heldThis = null;
                try
                {
                    AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                    String token = acquireShotLock(lockKey, id);
                    if (token == null)
                    {
                        if (single) { throw new ServiceException("任务处理中"); }
                        rejected.add(new StoryboardVideoGenerateVO.ShotResult(id, false, "任务处理中"));
                        continue;
                    }
                    heldThis = new ShotLock(lockKey, token);
                    heldLocks.add(heldThis);
                    String scopeKey = direction + ":" + sb.getProjectId() + ":" + sb.getEpisodeId();
                    boolean useRecommendedDuration = recommendationPolicyByScope.computeIfAbsent(scopeKey,
                            key -> usesStoryboardRecommendedDuration(direction, sb));
                    PreparedShot shot = preparer.prepare(sb);
                    Integer recommendedDurationSeconds = useRecommendedDuration
                            ? StoryboardDurationResolver.parseRecommendedDuration(sb.getScriptParams()) : null;
                    if (useRecommendedDuration && Objects.isNull(recommendedDurationSeconds))
                    {
                        String creationMode = resolveCreationMode(sb.getProjectId(), sb.getEpisodeId());
                        log.warn("分镜建议时长缺失，按后续优先级兜底: storyboardId={}, creationMode={}, reason={}",
                                sb.getId(), creationMode,
                                StoryboardDurationResolver.explainRecommendedDurationMiss(sb.getScriptParams()));
                    }
                    Resolution duration = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                            ? StoryboardDurationResolver.resolve(requestedDurationSeconds,
                                    recommendedDurationSeconds, useRecommendedDuration, single, modelConfig)
                            : new Resolution(null, null);
                    prepared.add(shot.withDuration(recommendedDurationSeconds,
                            duration.durationSeconds(), duration.source()));
                }
                catch (RuntimeException e)
                {
                    if (heldThis != null)
                    {
                        releaseLockIfMine(heldThis.key, heldThis.token);
                        heldLocks.remove(heldThis);
                    }
                    if (single) { throw e; }
                    log.info("分镜批量出片跳过镜头: storyboardId={}, reason={}", id, e.getMessage());
                    rejected.add(new StoryboardVideoGenerateVO.ShotResult(id, false, shortReason(e.getMessage())));
                }
            }
            if (prepared.isEmpty())
            {
                log.info("分镜批量出片无可受理镜头: ids={}, rejectedCount={}", ids, rejected.size());
                if (ids.stream().filter(Objects::nonNull).distinct().count() > 1
                        && CollectionUtil.isNotEmpty(rejected)
                        && rejected.stream().allMatch(item -> "任务处理中".equals(item.getReason())))
                {
                    throw new ServiceException("任务执行中，请先停止");
                }
                String reason = rejected.isEmpty() ? "没有可生成的分镜" : rejected.get(0).getReason();
                throw TaskErrorPresentation.toServiceException(reason, "没有可生成分镜");
            }
            slotReservation = resolveWorkflowSlot(prepared, inheritedReservation, batchWorkflow);
            acquiredHere = inheritedReservation == null && slotReservation != null;
            return createBatchTaskAndEnqueue(userId, modelCode, modelId, modelConfig, aspectRatio, resolution,
                    fallbackDurationSeconds, generateAudio, userInputText, perShotCount, funcCode, direction, prepared,
                    heldLocks, rejected, overwriteExistingFinal, slotReservation);
        }
        catch (RuntimeException e)
        {
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            if (acquiredHere)
            {
                batchTaskSlotService.release(slotReservation);
            }
            if (e instanceof ReferenceAudioValidationException)
            {
                throw new ServiceException(e.getMessage());
            }
            throw e;
        }
    }

    private BatchTaskSlotReservation resolveWorkflowSlot(List<PreparedShot> prepared,
                                                          BatchTaskSlotReservation inheritedReservation,
                                                          boolean batchWorkflow)
    {
        AidStoryboard first = prepared.get(0).storyboard;
        boolean sameScope = prepared.stream().allMatch(item ->
                Objects.equals(first.getProjectId(), item.storyboard.getProjectId())
                        && Objects.equals(first.getEpisodeId(), item.storyboard.getEpisodeId()));
        if (!sameScope)
        {
            throw new ServiceException("分镜归属不一致");
        }
        if (inheritedReservation != null)
        {
            if (!Objects.equals(first.getProjectId(), inheritedReservation.projectId())
                    || !Objects.equals(first.getEpisodeId(), inheritedReservation.episodeId())
                    || !inheritedReservation.logicalTypes().contains(
                            BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW))
            {
                throw new ServiceException("任务范围无效");
            }
            batchTaskSlotService.requireOwned(inheritedReservation);
            return inheritedReservation;
        }
        if (!batchWorkflow)
        {
            return null;
        }
        return batchTaskSlotService.acquireTaskSlots(first.getProjectId(), first.getEpisodeId(),
                List.of(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW));
    }

    /** 创建单一父任务（写 input_snapshot/totalCount）+ 构建每镜头 Job + 入队异步执行。 */
    private StoryboardVideoGenerateVO createBatchTaskAndEnqueue(Long userId, String modelCode, Long modelId,
            AiModelConfigVo modelConfig, String aspectRatio, String resolution, Integer durationSeconds,
            Boolean generateAudio, String userInputText, int perShotCount, String funcCode, String direction,
            List<PreparedShot> prepared, List<ShotLock> heldLocks,
            List<StoryboardVideoGenerateVO.ShotResult> rejected, boolean overwriteExistingFinal,
            BatchTaskSlotReservation slotReservation)
    {
        AidStoryboard firstSb = prepared.get(0).storyboard;
        Long projectId = firstSb.getProjectId();
        Long episodeId = firstSb.getEpisodeId();

        int newSubtasks = 0;
        List<Long> acceptedIds = new ArrayList<>();
        List<Map<String, Object>> shotsSnapshot = new ArrayList<>();
        List<Map<String, Object>> allShotsSnapshot = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i++)
        {
            PreparedShot ps = prepared.get(i);
            newSubtasks += ps.takeCount;
            acceptedIds.add(ps.storyboard.getId());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("storyboardId", ps.storyboard.getId());
            s.put("takeCount", ps.takeCount);
            // 持久化锁 token：外部（取消/队列收尾）释放锁时按 token 走 compare-and-delete，不裸删
            s.put("lockToken", heldLocks.get(i).token);
            // 首尾帧出片：持久化首/尾帧来源（上传 URL / 记录 ID）+ 配音项，续生时据此重解析（首尾帧/配音入参不可由分镜单图还原）
            putEdgeSnapshot(s, ps);
            putDurationSnapshot(s, ps);
            shotsSnapshot.add(s);
            // 稳定全集（不含 token，续生时原样保留）：用于续生时兜底"未开始 / 无成功快照"的镜头全集
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("storyboardId", ps.storyboard.getId());
            a.put("takeCount", ps.takeCount);
            putEdgeSnapshot(a, ps);
            putDurationSnapshot(a, ps);
            allShotsSnapshot.add(a);
        }

        AidExtractTask task = new AidExtractTask();
        task.setProjectId(projectId);
        task.setEpisodeId(episodeId);
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_STORYBOARD_VIDEO_GENERATE);
        task.setModelCode(modelCode);
        Map<String, Object> inputMap = new LinkedHashMap<>();
        inputMap.put("direction", direction);
        // 持久化解析定型的模型池编码：续生直接据此还原 func_code（多参 pro 分流仅凭 direction 无法还原）
        inputMap.put("funcCode", funcCode);
        inputMap.put("storyboardIds", acceptedIds);
        inputMap.put("modelCode", modelCode);
        inputMap.put("capabilityCode", modelConfig.getCapabilityCode());
        inputMap.put("aspectRatio", aspectRatio);
        inputMap.put("resolution", resolution);
        inputMap.put("durationSeconds", durationSeconds);
        inputMap.put("countPerShot", perShotCount);
        inputMap.put("generateAudio", generateAudio);
        inputMap.put("userInputText", userInputText);
        inputMap.put("overwriteExistingFinal", overwriteExistingFinal);
        inputMap.put("shots", shotsSnapshot);
        // 稳定全集：续生时据此兜底全部镜头（含未开始/无成功快照的），不依赖增量 resultData.shots
        inputMap.put("allShots", allShotsSnapshot);
        // 运行批次号：fresh 提交固定 0；续生在 resumeVideo 内 +1 并回写
        inputMap.put("runNo", 0);
        batchTaskSlotService.attachSnapshotMetadata(inputMap, slotReservation);
        try
        {
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
        }
        catch (Exception e)
        {
            // 逐分镜秒数和锁信息必须完整持久化，否则续生会改变计费及实际出片参数
            log.error("分镜批量出片 inputSnapshot 序列化失败: direction={}", direction, e);
            throw new ServiceException("提交失败");
        }
        task.setStatus(TASK_STATUS_PENDING);
        task.setTotalCount(newSubtasks);
        task.setDelFlag(DEL_FLAG_NORMAL);
        task.setCreateTime(DateUtils.getNowDate());
        task.setCreateBy(String.valueOf(userId));
        // 校验前置：余额只读预检提前到建任务之前（预估 = 单条视频预扣 × 子任务数），
        // 余额不足直接拦截，不再产生「先建任务再媒体冻结失败」的废任务；最终以媒体任务锁内冻结为准
        precheckVideoBatchBalance(userId, modelConfig, aspectRatio, resolution, generateAudio, prepared);
        extractTaskService.save(task);
        Long taskId = task.getId();

        boolean enqueued;
        try
        {
            // 稳定镜头序号（fresh：prepared 顺序 == allShots 顺序）+ 本轮全槽位 [0..takeCount-1]
            Map<Long, Integer> shotOrdinalById = new LinkedHashMap<>();
            Map<Long, List<Integer>> takeSlotsByShot = new LinkedHashMap<>();
            for (int i = 0; i < prepared.size(); i++)
            {
                PreparedShot ps = prepared.get(i);
                Long sid = ps.storyboard.getId();
                shotOrdinalById.put(sid, i);
                List<Integer> slots = new ArrayList<>();
                for (int s = 0; s < ps.takeCount; s++) { slots.add(s); }
                takeSlotsByShot.put(sid, slots);
            }
            enqueued = buildJobsAndEnqueue(taskId, projectId, episodeId, userId, modelCode, modelId, modelConfig,
                    aspectRatio, resolution, generateAudio, userInputText, perShotCount, direction,
                    prepared, heldLocks,
                    newSubtasks, prepared.size(), 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    java.util.Collections.emptyMap(), 0, false, overwriteExistingFinal,
                    shotOrdinalById, takeSlotsByShot);
        }
        catch (RuntimeException ex)
        {
            // 入队过程抛异常（非返回 false）：父任务已落库，须置 FAILED + 释放锁，避免遗留 PENDING 僵尸
            log.error("分镜图生视频批量入队异常: taskId={}", taskId, ex);
            updateTaskFailed(taskId, "提交失败");
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw new ServiceException("提交失败，请重试");
        }
        if (!enqueued)
        {
            log.error("分镜图生视频批量入队失败: taskId={}", taskId);
            updateTaskFailed(taskId, "提交失败");
            for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
            throw new ServiceException("提交失败，请重试");
        }

        StoryboardVideoGenerateVO vo = new StoryboardVideoGenerateVO();
        vo.setTaskId(taskId);
        vo.setStatus(TASK_STATUS_PENDING);
        vo.setModelName(modelCode);
        vo.setTotalShots(prepared.size());
        vo.setCountPerShot(perShotCount);
        vo.setTotalSubtasks(newSubtasks);
        List<StoryboardVideoGenerateVO.ShotResult> items = new ArrayList<>();
        for (PreparedShot ps : prepared)
        {
            items.add(new StoryboardVideoGenerateVO.ShotResult(ps.storyboard.getId(), true, null));
        }
        items.addAll(rejected);
        vo.setItems(items);
        return vo;
    }

    /**
     * 出片批量余额前置预检（只读，不冻结）：按完整媒体请求预估单条视频预扣额 × 总条数，
     * 余额不足直接抛「预扣余额不足」；预估不出（模型未配规则等）时放行，交由媒体任务统一冻结兜底。
     *
     * @param userId          用户ID
     * @param modelConfig     出片模型配置
     * @param aspectRatio     宽高比（分辨率缺省时用于推断档位）
     * @param resolution      已解析的清晰度档位（可空；与下发/冻结同源）
     * @param generateAudio   是否音画同出
     * @param prepared        已固化逐分镜时长的镜头列表
     */
    private void precheckVideoBatchBalance(Long userId, AiModelConfigVo modelConfig, String aspectRatio,
            String resolution, Boolean generateAudio, List<PreparedShot> prepared)
    {
        try
        {
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (PreparedShot shot : prepared)
            {
                MediaVideoGenerateRequest media = buildVideoMediaRequest(
                        shot.storyboard, shot, modelConfig, userId, aspectRatio, resolution,
                        shot.durationSeconds, generateAudio, Map.of());
                // 与报价和正式提交共用素材及计费维度，不能漏掉参考视频等 SKU 条件。
                PreparedMediaBillingInput billing = mediaBillingQuotePreparer.prepareVideoBilling(media);
                BillingCalcResult calc = billingPreHoldCalculationService.calculate(
                        billing.modelConfig(), billing.billingInput());
                if (Objects.isNull(calc) || !calc.isMatched() || Objects.isNull(calc.getAmount()))
                {
                    // 任一镜头无法准确预估时整批放行，由媒体任务统一冻结硬校验
                    return;
                }
                total = total.add(calc.getAmount()
                        .multiply(java.math.BigDecimal.valueOf(Math.max(shot.takeCount, 1))));
            }
            accountUpdateService.precheckBalance(userId, total);
        }
        catch (ServiceException e)
        {
            // 余额不足等业务异常原样上抛（建任务前拦截）
            throw e;
        }
        catch (Exception e)
        {
            log.warn("分镜出片余额预检异常(忽略, 交由统一冻结兜底): userId={}", userId, e);
        }
    }

    /** 构建每镜头 VideoGenJob（共享 taskId）+ 组装 VideoBatchJob 入队。返回是否入队成功。
     *  @param totalShots 原批次镜头总数（累计口径，写入 resultData.totalShots；续生时为原始全集大小，非本轮补跑数）
     *  @param shotOrdinalById 镜头稳定序号（allShots 位置，0 基）：用于 bizTaskId 基址，保证跨 fresh/续生同一镜头同一基址
     *  @param takeSlotsByShot 每镜头本轮要生成的逻辑 take 槽位（0 基）：fresh=[0..takeCount-1]，续生=缺失槽位 */
    private boolean buildJobsAndEnqueue(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode,
            Long modelId, AiModelConfigVo modelConfig, String aspectRatio, String resolution, Boolean generateAudio,
            String userInputText, int perShotCount, String direction, List<PreparedShot> prepared,
            List<ShotLock> heldLocks, int newSubtasks, int totalShots, int seedSuccessCount, List<Long> seedRecordIds,
            List<Map<String, Object>> seedItems, List<Map<String, Object>> seedShotResults,
            Map<Long, Map<String, Object>> seedShotPrior, int runNo, boolean forcePartial,
            boolean overwriteExistingFinal,
            Map<Long, Integer> shotOrdinalById, Map<Long, List<Integer>> takeSlotsByShot)
    {
        // 入队前清理扇入计数/收尾标记：每轮（首跑/续生）从干净状态开始，兜底上一轮被僵尸回收未 cleanup 的残留
        fanInSupport.cleanup(taskId);
        // 出片产物类型：首尾帧方向落 gen_type=edge，其余方向沿用 i2v
        String genType = DIRECTION_EDGE.equals(direction) ? GEN_TYPE_EDGE : GEN_TYPE_I2V;
        List<VideoGenJob> shotJobs = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i++)
        {
            PreparedShot ps = prepared.get(i);
            ShotLock lock = heldLocks.get(i);
            Long sid = ps.storyboard.getId();
            // 稳定镜头序号（allShots 位置）→ bizTaskId 基址；续生时同一镜头取同一 ordinal，保证同槽位同 bizTaskId
            int ordinal = (shotOrdinalById != null && shotOrdinalById.get(sid) != null) ? shotOrdinalById.get(sid) : i;
            long bizSeqBase;
            try
            {
                // bizSeqBase = 父任务编码段 + ordinal*1000；ordinal<20、槽位<count(≤4) → 不跨父任务编码空间
                bizSeqBase = Math.addExact(
                        Math.multiplyExact(taskId, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR),
                        (long) ordinal * 1000L);
            }
            catch (ArithmeticException overflow)
            {
                // taskId 异常大导致编码溢出：直接判提交失败，避免 bizTaskId 冲突误判幂等
                log.error("分镜图生视频 bizSeqBase 编码溢出: taskId={}, ordinal={}", taskId, ordinal, overflow);
                throw new ServiceException("提交失败，请重试");
            }
            List<Integer> takeSlots = (takeSlotsByShot != null) ? takeSlotsByShot.get(sid) : null;
            if (CollectionUtil.isEmpty(takeSlots))
            {
                // 兜底：无显式槽位（理论不出现）→ 按本镜头条数取前 N 槽位
                takeSlots = new ArrayList<>();
                for (int s = 0; s < ps.takeCount; s++) { takeSlots.add(s); }
            }
            Integer jobDurationSeconds = Boolean.TRUE.equals(modelConfig.getSupportsDuration())
                    ? ps.durationSeconds : null;
            shotJobs.add(new VideoGenJob(taskId, userId, ps.storyboard, modelCode, modelId, modelConfig,
                    ps.finalPrompt, ps.rawVideoPrompt, ps.referenceImages, ps.baseImageUrl, ps.takeCount,
                    aspectRatio, resolution, jobDurationSeconds, generateAudio, userInputText, ps.userVideoPromptInput,
                    bizSeqBase, takeSlots, lock.key, lock.token,
                    genType, ps.lastFrameImageUrl, ps.firstImageRecordId, ps.lastImageRecordId,
                    ps.referenceAudios, ps.referenceVideos, ps.providerExtraOptions, overwriteExistingFinal));
        }
        VideoBatchJob batchJob = new VideoBatchJob(taskId, userId, modelCode, perShotCount, totalShots,
                seedSuccessCount + newSubtasks, seedSuccessCount, runNo, shotJobs, new ArrayList<>(heldLocks),
                seedRecordIds, seedItems, seedShotResults, seedShotPrior, forcePartial);
        return taskQueueService.submitLocalTask(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_STORYBOARD_VIDEO_GENERATE, () -> runAsyncBatch(batchJob));
    }

    /**
     * 组装单镜头 shotResult，并合并续生携带的既有进度（保证 takeCount/successCount/recordIds 为累计口径）。
     */
    private Map<String, Object> buildShotResultEntry(VideoBatchJob batch, VideoGenJob shot,
            List<Long> shotRecordIds, int shotSuccess, List<Integer> failedTakes)
    {
        Long sid = shot.storyboard.getId();
        Map<String, Object> prior = batch.seedShotPrior.get(sid);
        int origTakeCount = shot.videoCount;
        int baseSuccess = 0;
        List<Long> mergedRecordIds = new ArrayList<>();
        if (prior != null)
        {
            if (prior.get("takeCount") instanceof Number n) { origTakeCount = n.intValue(); }
            if (prior.get("successCount") instanceof Number n) { baseSuccess = n.intValue(); }
            if (prior.get("recordIds") instanceof List<?> list)
            {
                for (Object o : list) { if (o instanceof Number n) { mergedRecordIds.add(n.longValue()); } }
            }
        }
        mergedRecordIds.addAll(shotRecordIds);
        Map<String, Object> sr = new LinkedHashMap<>();
        sr.put("storyboardId", sid);
        sr.put("takeCount", origTakeCount);
        sr.put("successCount", baseSuccess + shotSuccess);
        sr.put("recordIds", mergedRecordIds);
        if (!failedTakes.isEmpty()) { sr.put("failedTakes", failedTakes); }
        return sr;
    }

    /** 组装父任务 resultData 结构（终态 / 增量共用）。 */
    private Map<String, Object> buildBatchResultMap(VideoBatchJob batch, int total, List<Long> recordIds,
            List<Map<String, Object>> items, List<Map<String, Object>> shotResults, List<Map<String, Object>> failedItems)
    {
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("modelCode", batch.modelCode);
        resultMap.put("totalShots", batch.totalShots);
        resultMap.put("countPerShot", batch.countPerShot);
        resultMap.put("totalSubtasks", total);
        resultMap.put("successCount", recordIds.size());
        resultMap.put("failCount", failedItems.size());
        resultMap.put("recordIds", recordIds);
        resultMap.put("items", items);
        resultMap.put("shots", shotResults);
        if (!failedItems.isEmpty()) { resultMap.put("failedItems", failedItems); }
        return resultMap;
    }

    /** 组装单镜头单条 take 的 gen_params JSON 快照（落 aid_gen_record.gen_params NOT NULL 列）。
     *  含 parentTaskId：保留父任务快照，便于排查。
     *  含 takeIndex：该 take 的逻辑槽位（0 基），续生时据此精确算出"已成功槽位"，只补缺失槽位、不重复补已成功的；
     *  含 bizSeq：下发媒体层的确定性 bizTaskId（= bizSeqBase + takeIndex），也是父子任务收尾关联键。 */
    private String buildShotGenParamsJson(Long parentTaskId, Long storyboardId, String modelCode, String aspectRatio,
            String resolution, Integer durationSeconds, int takeCount, Boolean generateAudio, String userInputText,
            String videoPromptInput, int takeIndex, long bizSeq)
    {
        Map<String, Object> genParamsMap = new LinkedHashMap<>();
        genParamsMap.put("parentTaskId", parentTaskId);
        genParamsMap.put("storyboardId", storyboardId);
        genParamsMap.put("modelCode", modelCode);
        genParamsMap.put("aspectRatio", aspectRatio);
        genParamsMap.put("resolution", resolution);
        genParamsMap.put("durationSeconds", durationSeconds);
        genParamsMap.put("count", takeCount);
        // 逻辑 take 槽位（0 基）+ 确定性 bizTaskId：续生按槽位做集合差，精确补缺、媒体层幂等去重
        genParamsMap.put("takeIndex", takeIndex);
        genParamsMap.put("bizSeq", bizSeq);
        genParamsMap.put("generateAudio", generateAudio);
        genParamsMap.put("userInputText", userInputText);
        genParamsMap.put("videoPromptInput", videoPromptInput);
        try
        {
            return OBJECT_MAPPER.writeValueAsString(genParamsMap);
        }
        catch (Exception e)
        {
            log.warn("分镜图生视频 genParams 序列化失败, 降级最小快照: storyboardId={}", storyboardId, e);
            return "{\"parentTaskId\":" + parentTaskId + ",\"storyboardId\":" + storyboardId
                    + ",\"takeIndex\":" + takeIndex + ",\"bizSeq\":" + bizSeq + "}";
        }
    }

    /** 执行分镜视频批量生成。 */
    private void runAsyncBatch(VideoBatchJob batch)
    {
        Long taskId = batch.taskId;
        String dispatchToken = taskQueueService.currentLocalDispatchToken(taskId);
        // 同步提交阶段是否已登记心跳续租：仅 PROCESSING 抢占成功后才登记，finally 据此决定是否移出心跳集合。
        boolean heartbeatActivated = false;
        try
        {
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelled(taskId)) { sseManager.sendCancelled(taskId, "用户取消"); }
                releaseBatchLocksAndSlots(batch, dispatchToken);
                return;
            }
            if (!updateTaskStatus(taskId, TASK_STATUS_PROCESSING, null, TASK_STATUS_PENDING))
            {
                log.warn("分镜批量出片任务已被其他线程处理, 跳过: taskId={}", taskId);
                return;
            }
            // 同步提交阶段登记心跳常驻续租：同步直出条目可能耗时 > 租约 TTL(90s)，只在每镜起点续租一次时，
            // 长耗时同步生成期间租约会过期被僵尸回收误杀。转异步在途后于 finally 移出心跳，改由 media 轮询续租。
            assetExtractService.markTaskProcessing(taskId);
            heartbeatActivated = true;
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelled(taskId)) { sseManager.sendCancelled(taskId, "用户取消"); }
                releaseBatchLocksAndSlots(batch, dispatchToken);
                return;
            }

            // 提交进度分母 = 本轮实际要提交的 take 总数（fresh=镜头数×每镜条数，续生=缺失槽位数）
            int totalTakes = 0;
            for (VideoGenJob s : batch.shots) { totalTakes += s.takeSlots.size(); }
            int submittedTakes = 0;
            // 先推一条 0/N，替换掉排队阶段的「已获得执行名额」快照，让前端立刻看到进入提交阶段
            pushSubmitProgress(taskId, totalTakes, 0);

            int asyncPending = 0;
            boolean cancelledMid = false;
            outer:
            for (VideoGenJob shot : batch.shots)
            {
                renewLockIfMine(shot.lockKey, shot.lockToken);
                try { assetExtractService.touchTaskProcessing(taskId); }
                catch (Exception ignore) { /* 续租失败不阻断 */ }
                for (int idx = 0; idx < shot.takeSlots.size(); idx++)
                {
                    int slot = shot.takeSlots.get(idx);
                    if (assetExtractService.isTaskCancelled(taskId)) { cancelledMid = true; break outer; }
                    long bizSeq = Math.addExact(shot.bizSeqBase, slot);
                    String genParamsJson = buildShotGenParamsJson(shot.taskId, shot.storyboard.getId(),
                            shot.modelCode, shot.aspectRatio, shot.resolution, shot.durationSeconds, shot.videoCount,
                            shot.generateAudio, shot.userInputText, shot.userVideoPromptInput, slot, bizSeq);
                    try
                    {
                        String reused = reconcileExistingMediaUrl(bizSeq);
                        if (StrUtil.isNotBlank(reused))
                        {
                            batchParentSubmissionGuard.executeManagedBusinessCommit(taskId, dispatchToken, () -> {
                                persistGenRecordIdempotent(shot, bizSeq, reused, genParamsJson);
                                return null;
                            });
                            continue;
                        }
                        MediaTaskResponse resp = submitSingleVideoMedia(
                                shot, bizSeq, genParamsJson, dispatchToken);
                        String st = Objects.isNull(resp) ? null : resp.getStatus();
                        if (TASK_STATUS_SUCCEEDED.equals(st))
                        {
                            if (Objects.nonNull(resp) && StrUtil.isNotBlank(resp.getOssUrl()))
                            {
                                // DIRECT 同步直出：内联幂等落库，落库失败不计失败（事件监听幂等兜底，避免双计数）
                                try {
                                    batchParentSubmissionGuard.executeManagedBusinessCommit(taskId, dispatchToken, () -> {
                                        persistGenRecordIdempotent(shot, bizSeq, resp.getOssUrl(), genParamsJson);
                                        return null;
                                    });
                                }
                                catch (Exception pe) { log.error("分镜批量出片DIRECT内联落库失败,交由事件兜底: taskId={}, bizSeq={}", taskId, bizSeq, pe); }
                            }
                            else
                            {
                                // 成功但 OSS 未就绪 → 等 OSS 持久化事件扇入收尾，不可计失败（已计费）
                                asyncPending++;
                            }
                        }
                        else if (Objects.nonNull(st) && VIDEO_IN_PROGRESS_STATUSES.contains(st))
                        {
                            asyncPending++;
                        }
                        else
                        {
                            boolean contentRejected = Objects.nonNull(resp)
                                    && TaskErrorCode.UPSTREAM_CONTENT_FILTERED.name().equals(resp.getErrorCode());
                            if (contentRejected)
                            {
                                log.warn("分镜批量出片被内容安全策略拒绝: taskId={}, storyboardId={}, status={}",
                                        taskId, shot.storyboard.getId(), st);
                            }
                            else
                            {
                                log.error("分镜批量出片提交即终态非成功: taskId={}, storyboardId={}, status={}",
                                        taskId, shot.storyboard.getId(), st);
                            }
                            if (Objects.nonNull(resp) && Objects.nonNull(resp.getTaskId()))
                            {
                                // 统一走媒体终态消费入口，失败计数完成后同步清理已无用途的分镜上下文。
                                onMediaVideoTaskTerminal(resp.getTaskId(), false, null);
                            }
                            else
                            {
                                batchParentSubmissionGuard.executeManagedBusinessCommit(taskId, dispatchToken, () -> {
                                    fanInSupport.incrFail(taskId, "媒体任务提交失败");
                                    return null;
                                });
                            }
                        }
                    }
                    catch (ShotInFlightException inflight)
                    {
                        asyncPending++;
                        log.info("storyboard video batch take has inflight media task: taskId={}, storyboardId={}, take={}",
                                taskId, shot.storyboard.getId(), slot + 1);
                    }
                    catch (BatchTaskExecutionRejectedException rejected)
                    {
                        cancelledMid = true;
                        break outer;
                    }
                    catch (Exception perItemEx)
                    {
                        log.error("分镜批量出片单条提交失败: taskId={}, storyboardId={}, take={}, err={}",
                                taskId, shot.storyboard.getId(), slot + 1, perItemEx.getMessage());
                        int takeNumber = slot + 1;
                        batchParentSubmissionGuard.executeManagedBusinessCommit(taskId, dispatchToken, () -> {
                            fanInSupport.incrFail(taskId, shot.storyboard.getId(), takeNumber, dispatchToken, perItemEx);
                            return null;
                        });
                    }
                    finally
                    {
                        // 无论复用/成功/在飞/失败，该 take 均已处理完提交动作，推「已提交 X/N」进度
                        submittedTakes++;
                        pushSubmitProgress(taskId, totalTakes, submittedTakes);
                    }
                }
            }

            if (cancelledMid || assetExtractService.isTaskCancelled(taskId))
            {
                if (cancelVideoBatchAtCheckpointSerialized(taskId))
                {
                    releaseBatchLocksAndSlots(batch, dispatchToken);
                }
                return;
            }
            if (asyncPending > 0)
            {
                // 异步在途：保持 PROCESSING，锁/名额由事件扇入 finalizeVideoBatchIfDone 收尾；
                // 父任务租约由 media 调度中心轮询子任务时续租。转异步前再续一次租约，给轮询接管留完整 TTL 窗口。
                assetExtractService.touchTaskProcessing(taskId);
                log.info("分镜批量出片已提交,转异步等待扇入: taskId={}, asyncPending={}", taskId, asyncPending);
                return;
            }
            batchParentSubmissionGuard.executeManagedBusinessCommit(taskId, dispatchToken, () -> {
                finalizeVideoBatchIfDone(taskId);
                return null;
            });
        }
        catch (Exception e)
        {
            log.error("分镜批量出片提交阶段失败: taskId={}", taskId, e);
            TaskErrorResult errorResult = ErrorNormalizer.normalize(e);
            if (updateTaskFailed(taskId, errorResult)) { sseManager.sendError(taskId, errorResult); }
            releaseBatchLocksAndSlots(batch, dispatchToken);
        }
        finally
        {
            // 同步提交阶段结束：停止心跳续租（保留租约 key）。转异步→交 media 轮询续租；同步终态→租约已随收尾释放。
            if (heartbeatActivated)
            {
                assetExtractService.deactivateTaskProcessingHeartbeat(taskId);
            }
        }
    }

    /** 释放本批次镜头锁 + 取消标记 + 并发名额（取消/失败/提交异常路径用；正常异步路径由收尾释放）。 */
    private void releaseBatchLocksAndSlots(VideoBatchJob batch, String dispatchToken)
    {
        for (ShotLock l : batch.shotLocks) { releaseLockIfMine(l.key, l.token); }
        try { assetExtractService.clearCancelFlag(batch.taskId, dispatchToken); } catch (Exception ignore) { /* ignore */ }
        try { assetExtractService.releaseTaskSlots(batch.taskId, dispatchToken); } catch (Exception ignore) { /* ignore */ }
    }

    private boolean cancelVideoBatchAtCheckpoint(Long taskId)
    {
        try
        {
            AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.isNull(task) || !TASK_STATUS_PROCESSING.equals(task.getStatus()))
            {
                return false;
            }
            int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
            List<Long> storyboardIds = fanInSupport.parseBatchStoryboardIds(task.getInputSnapshot());
            if (total > 0 && CollectionUtil.isEmpty(storyboardIds))
            {
                log.error("分镜批量出片取消收尾缺少批次镜头范围: taskId={}", taskId);
                return false;
            }
            List<AidGenRecord> succ = loadSucceededRecordsByParentTask(taskId, storyboardIds);
            int successCount = succ.size();
            int failCount = fanInSupport.getFailCount(taskId);
            int pendingCount = Math.max(total - successCount - failCount, 0);
            Map<String, Object> resultMap = buildFinalizeResultMap(task, succ, total, successCount, failCount);
            resultMap.put("pendingCount", pendingCount);
            String resultJson = null;
            try { resultJson = OBJECT_MAPPER.writeValueAsString(resultMap); }
            catch (Exception e) { log.warn("分镜批量出片取消结果序列化失败: taskId={}", taskId, e); }
            boolean updated = updateTaskCancelledWithResult(taskId, resultJson);
            if (updated)
            {
                try { sseManager.sendCancelled(taskId, "用户取消"); }
                catch (Exception e) { log.warn("分镜批量出片取消SSE发送失败: taskId={}", taskId, e); }
                try { fanInSupport.cleanup(taskId); }
                catch (Exception e) { log.warn("分镜批量出片取消清理扇入状态失败: taskId={}", taskId, e); }
                log.info("分镜批量出片在安全检查点取消: taskId={}, total={}, success={}, fail={}, pending={}",
                        taskId, total, successCount, failCount, pendingCount);
            }
            return updated;
        }
        catch (Exception e)
        {
            log.error("分镜批量出片取消收尾失败，保留PROCESSING等待租约回收: taskId={}", taskId, e);
            return false;
        }
    }

    private boolean cancelVideoBatchAtCheckpointSerialized(Long taskId)
    {
        try
        {
            return batchParentSubmissionGuard.execute(taskId,
                    () -> cancelVideoBatchAtCheckpoint(taskId));
        }
        catch (Exception e)
        {
            log.error("分镜批量出片取消串行收口失败: taskId={}", taskId, e);
            return false;
        }
    }
    private record VideoResumeBillingPlan(AidExtractTask task, AiModelConfigVo modelConfig,
            String direction, String aspectRatio, String resolution, Boolean generateAudio,
            List<PreparedShot> shots)
    {
    }

    @Override
    public BillingQuoteVO quoteResumeVideo(Long taskId, Long userId)
    {
        VideoResumeBillingPlan plan = prepareVideoResumeBillingPlan(taskId, userId);
        List<BillingQuoteVO> items = new ArrayList<>(plan.shots().size());
        for (PreparedShot shot : plan.shots())
        {
            MediaVideoGenerateRequest request = buildVideoMediaRequest(
                    shot.storyboard, shot, plan.modelConfig(), userId,
                    plan.aspectRatio(), plan.resolution(), shot.durationSeconds,
                    plan.generateAudio(), Map.of());
            PreparedMediaBillingInput prepared = mediaBillingQuotePreparer.prepareVideoBilling(request);
            BillingCalcResult result = billingPreHoldCalculationService.calculate(
                    prepared.modelConfig(), prepared.billingInput());
            if (result == null || !result.isMatched())
            {
                throw new ServiceException(result == null
                        ? "计费规则缺失" : result.getErrorMessage());
            }
            items.add(billingQuoteAssembler.single(
                    "TASK_RESUME", prepared.modelConfig(), result, shot.takeCount));
        }
        return billingQuoteAssembler.aggregate("TASK_RESUME", items);
    }

    private VideoResumeBillingPlan prepareVideoResumeBillingPlan(Long taskId, Long userId)
    {
        validateUserId(userId);
        if (taskId == null || taskId <= 0)
        {
            throw new ServiceException("参数错误");
        }
        AidExtractTask task = extractTaskService.getById(taskId);
        if (task == null || !DEL_FLAG_NORMAL.equals(task.getDelFlag())
                || !Objects.equals(userId, task.getUserId()))
        {
            throw new ServiceException("任务不存在");
        }
        if (!TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(task.getTaskType()))
        {
            throw new ServiceException("类型不支持");
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            throw new ServiceException("状态不支持");
        }
        JsonNode input;
        try
        {
            input = OBJECT_MAPPER.readTree(StrUtil.blankToDefault(task.getInputSnapshot(), "{}"));
        }
        catch (Exception e)
        {
            throw new ServiceException("任务数据异常");
        }
        if (input.path("runNo").asInt(0) >= MAX_RUN_NO)
        {
            throw new ServiceException("重试次数超限");
        }
        String direction = input.path("direction").asText(DIRECTION_MULTI);
        String modelCode = input.path("modelCode").asText(null);
        String funcCode = input.hasNonNull("funcCode") ? input.get("funcCode").asText() : null;
        if (StrUtil.isBlank(funcCode))
        {
            funcCode = resolveFuncCodeByDirection(direction);
        }
        String resolvedModelCode = resolveModelCode(modelCode, funcCode);
        AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(resolvedModelCode, funcCode, input.path("capabilityCode").asText(null));
        if (modelConfig == null)
        {
            throw new ServiceException("模型不存在");
        }
        Boolean generateAudio = input.hasNonNull("generateAudio")
                ? input.get("generateAudio").asBoolean() : null;
        generateAudio = resolveGenerateAudioOrDefault(modelConfig, generateAudio);
        assertGenerateAudioAllowed(modelConfig, generateAudio);

        LinkedHashMap<Long, Integer> takeByShot = new LinkedHashMap<>();
        Map<Long, Integer> recommendedDurationByShot = new LinkedHashMap<>();
        Map<Long, Integer> durationByShot = new LinkedHashMap<>();
        Map<Long, String> durationSourceByShot = new LinkedHashMap<>();
        Map<Long, EdgeResolved> edgeByShot = new LinkedHashMap<>();
        Integer legacyDuration = input.hasNonNull("durationSeconds")
                ? input.get("durationSeconds").asInt() : null;
        JsonNode allShots = input.path("allShots");
        if (!allShots.isArray() || allShots.isEmpty())
        {
            allShots = input.path("shots");
        }
        if (allShots.isArray())
        {
            for (JsonNode node : allShots)
            {
                long storyboardId = node.path("storyboardId").asLong(0L);
                int takeCount = node.path("takeCount").asInt(0);
                if (storyboardId <= 0L || takeCount <= 0)
                {
                    continue;
                }
                takeByShot.put(storyboardId, takeCount);
                Integer recommended = readPositiveSnapshotInt(node, "recommendedDurationSeconds");
                Integer duration = readPositiveSnapshotInt(node, "durationSeconds");
                String durationSource = node.path("durationSource").asText(null);
                if (duration == null && legacyDuration != null && legacyDuration > 0)
                {
                    duration = legacyDuration;
                    durationSource = StoryboardDurationResolver.SOURCE_LEGACY_FALLBACK;
                }
                recommendedDurationByShot.put(storyboardId, recommended);
                durationByShot.put(storyboardId, duration);
                durationSourceByShot.put(storyboardId, StrUtil.trimToNull(durationSource));
                String firstUrl = node.path("firstImageUrl").asText(null);
                String lastUrl = node.path("lastImageUrl").asText(null);
                long firstId = node.path("firstImageRecordId").asLong(0L);
                long lastId = node.path("lastImageRecordId").asLong(0L);
                ExplicitAudioSelection audioSelection = parseAudioSelectionSnapshot(node);
                List<Long> referenceVideoRecordIds = parseLongArraySnapshot(
                        node, SNAPSHOT_KEY_VIDEO_RECORD_IDS);
                if (StrUtil.isNotBlank(firstUrl) || StrUtil.isNotBlank(lastUrl)
                        || firstId > 0L || lastId > 0L || !audioSelection.isEmpty()
                        || CollectionUtil.isNotEmpty(referenceVideoRecordIds) || !StoryboardVideoAdvancedOptions.read(node).isEmpty())
                {
                    edgeByShot.put(storyboardId, new EdgeResolved(
                            StrUtil.trimToNull(firstUrl), firstId > 0L ? firstId : null,
                            StrUtil.trimToNull(lastUrl), lastId > 0L ? lastId : null,
                            audioSelection, referenceVideoRecordIds, StoryboardVideoAdvancedOptions.read(node)));
                }
            }
        }
        if (takeByShot.isEmpty())
        {
            throw new ServiceException("任务数据异常");
        }
        Map<Long, TreeSet<Integer>> succeeded = new LinkedHashMap<>();
        for (AidGenRecord record : loadSucceededRecordsByParentTask(taskId, takeByShot.keySet()))
        {
            Long storyboardId = record.getStoryboardId();
            int takeCount = takeByShot.getOrDefault(storyboardId, 0);
            if (takeCount <= 0)
            {
                continue;
            }
            TreeSet<Integer> slots = succeeded.computeIfAbsent(storyboardId, key -> new TreeSet<>());
            Integer slot = parseTakeIndexFromGenParams(record.getGenParams());
            if (slot != null)
            {
                if (slot < 0 || slot >= takeCount || slots.contains(slot))
                {
                    continue;
                }
            }
            else
            {
                for (int index = 0; index < takeCount; index++)
                {
                    if (!slots.contains(index))
                    {
                        slot = index;
                        break;
                    }
                }
            }
            if (slot != null)
            {
                slots.add(slot);
            }
        }
        boolean imageLike = DIRECTION_IMAGE.equals(direction) || DIRECTION_GRID.equals(direction);
        boolean edge = DIRECTION_EDGE.equals(direction);
        String userInputText = input.hasNonNull("userInputText")
                ? input.get("userInputText").asText() : null;
        List<PreparedShot> shots = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : takeByShot.entrySet())
        {
            int remaining = entry.getValue()
                    - succeeded.getOrDefault(entry.getKey(), new TreeSet<>()).size();
            if (remaining <= 0)
            {
                continue;
            }
            AidStoryboard storyboard = loadAndCheckStoryboard(entry.getKey(), userId);
            PreparedShot shot;
            if (edge)
            {
                StoryboardVideoEdgeGenerateRequest request = new StoryboardVideoEdgeGenerateRequest();
                request.setGenerateAudio(generateAudio);
                request.setUserInputText(userInputText);
                shot = prepareEdgeShot(storyboard, false, request, edgeByShot,
                        modelConfig, userId, remaining, false, false);
            }
            else if (imageLike)
            {
                StoryboardVideoFromImageGenerateRequest request = new StoryboardVideoFromImageGenerateRequest();
                request.setGenerateAudio(generateAudio);
                request.setUserInputText(userInputText);
                EdgeResolved snapshot = edgeByShot.get(entry.getKey());
                shot = prepareImageShot(storyboard, false, request, modelConfig, userId,
                        remaining, snapshot == null ? null : snapshot.audioSelection,
                        false, false);
            }
            else
            {
                StoryboardVideoGenerateRequest request = new StoryboardVideoGenerateRequest();
                request.setGenerateAudio(generateAudio);
                request.setUserInputText(userInputText);
                EdgeResolved snapshot = edgeByShot.get(entry.getKey());
                StoryboardVideoAdvancedOptions.restore(request, snapshot == null ? null : snapshot.videoOptions);
                shot = prepareMultiShot(storyboard, false, request, modelConfig, userId,
                        remaining, snapshot == null ? null : snapshot.audioSelection,
                        snapshot == null ? null : snapshot.referenceVideoRecordIds, false, false);
            }
            shots.add(shot.withDuration(recommendedDurationByShot.get(entry.getKey()),
                    durationByShot.get(entry.getKey()), durationSourceByShot.get(entry.getKey())));
        }
        if (shots.isEmpty())
        {
            throw new ServiceException("无可续生项");
        }
        return new VideoResumeBillingPlan(task, modelConfig, direction,
                input.hasNonNull("aspectRatio") ? input.get("aspectRatio").asText() : null,
                input.hasNonNull("resolution") ? input.get("resolution").asText() : null,
                generateAudio, List.copyOf(shots));
    }

    @Override
    public StoryboardVideoGenerateVO resumeVideo(Long taskId, Long userId)
    {
        validateUserId(userId);
        if (Objects.isNull(taskId) || taskId <= 0)
        {
            log.error("分镜批量出片续生入参无效: taskId={}", taskId);
            throw new ServiceException("参数错误");
        }
        AidExtractTask task = extractTaskService.getById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag()))
        {
            throw new ServiceException("任务不存在");
        }
        if (!Objects.equals(userId, task.getUserId()))
        {
            log.error("分镜批量出片续生归属校验失败: taskId={}, owner={}, req={}", taskId, task.getUserId(), userId);
            throw new ServiceException("无权访问");
        }
        if (!TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(task.getTaskType()))
        {
            throw new ServiceException("类型不支持");
        }
        if (TASK_STATUS_PENDING.equals(task.getStatus())
                || TASK_STATUS_QUEUED.equals(task.getStatus())
                || TASK_STATUS_PROCESSING.equals(task.getStatus()))
        {
            StoryboardVideoGenerateVO vo = new StoryboardVideoGenerateVO();
            vo.setTaskId(taskId);
            vo.setStatus(task.getStatus());
            vo.setModelName(task.getModelCode());
            vo.setTotalSubtasks(task.getTotalCount());
            return vo;
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            // 允许从 PARTIAL_FAILED 续生；也允许从 FAILED 续生——崩溃 / 僵尸回收会把执行中的批量任务置 FAILED，
            // 其已成功产物已由增量 resultData 落库，续生据此只补未出满的镜头，不重复出片。
            log.info("分镜批量出片续生拒绝：状态不可续: taskId={}, status={}", taskId, task.getStatus());
            throw new ServiceException("状态不支持");
        }
        VideoResumeBillingPlan preparedResume = prepareVideoResumeBillingPlan(taskId, userId);
        // 续生失败需回滚的原值（状态/总数/快照），失败时原样还原
        String originalStatus = task.getStatus();
        String originalInputSnapshot = task.getInputSnapshot();

        JsonNode input;
        try
        {
            input = OBJECT_MAPPER.readTree(StrUtil.blankToDefault(task.getInputSnapshot(), "{}"));
        }
        catch (Exception e)
        {
            log.error("分镜批量出片续生 inputSnapshot 解析失败: taskId={}", taskId, e);
            throw new ServiceException("任务数据异常");
        }
        String direction = input.path("direction").asText(DIRECTION_MULTI);
        String modelCode = input.path("modelCode").asText(null);
        String aspectRatio = input.hasNonNull("aspectRatio") ? input.get("aspectRatio").asText() : null;
        String resolution = input.hasNonNull("resolution") ? input.get("resolution").asText() : null;
        Integer durationSeconds = input.hasNonNull("durationSeconds") ? input.get("durationSeconds").asInt() : null;
        Boolean generateAudio = input.hasNonNull("generateAudio") ? input.get("generateAudio").asBoolean() : null;
        String userInputText = input.hasNonNull("userInputText") ? input.get("userInputText").asText() : null;
        int perShotCount = input.path("countPerShot").asInt(1);
        // 老任务快照缺少该字段时保持原有自动覆盖行为，避免续生改变历史任务语义。
        boolean overwriteExistingFinal = !input.has("overwriteExistingFinal")
                || input.path("overwriteExistingFinal").asBoolean(true);
        // 运行批次号（单调，用于 bizTaskId 唯一）：超过编码上限直接拒绝，不 clamp（clamp 会让第 1000 次续生复用 runNo=999 的 bizTaskId）
        int priorRunNo = input.path("runNo").asInt(0);
        if (priorRunNo >= MAX_RUN_NO)
        {
            log.warn("分镜批量出片续生次数超限(编码位耗尽): taskId={}, priorRunNo={}", taskId, priorRunNo);
            throw new ServiceException("重试次数超限");
        }
        int newRunNo = priorRunNo + 1;
        // 续生失败回滚用：totalCount 回滚到原值（status/inputSnapshot 用上面已捕获的 originalStatus/originalInputSnapshot）
        Integer originalTotalCount = task.getTotalCount();

        // 既有成功携带（以落库 gen_record 为真源，保留已出片产物、避免重复扣费；
        // 即便"record 已提交、父快照未刷新就崩溃"也不会重复补跑）
        List<Long> seedRecordIds = new ArrayList<>();
        List<Map<String, Object>> seedItems = new ArrayList<>();
        List<Map<String, Object>> seedShotResults = new ArrayList<>();
        // 部分完成且本轮重跑的镜头的既有进度（storyboardId → {takeCount, successCount, recordIds}），供 shots 累计口径
        Map<Long, Map<String, Object>> seedShotPrior = new LinkedHashMap<>();
        // 全部待补镜头的完整既有进度（含 successCount=0 的），若本轮被跳过未受理则原样回填 shots，保持 PARTIAL_FAILED
        Map<Long, Map<String, Object>> priorShotFull = new LinkedHashMap<>();
        // storyboardId → 剩余待补条数
        Map<Long, Integer> remainByShot = new LinkedHashMap<>();

        // 续生以快照全集为准，避免遗漏未开始或崩溃前未落进度的镜头。
        java.util.LinkedHashMap<Long, Integer> origTakeByShot = new java.util.LinkedHashMap<>();
        Map<Long, Integer> recommendedDurationByShot = new LinkedHashMap<>();
        Map<Long, Integer> durationByShot = new LinkedHashMap<>();
        Map<Long, String> durationSourceByShot = new LinkedHashMap<>();
        // 首尾帧续生：storyboardId → {首图记录ID, 尾图记录ID, 配音URL列表}（从快照还原，首尾帧入参不可由分镜单图重建）
        Map<Long, EdgeResolved> edgeResolvedByShot = new LinkedHashMap<>();
        JsonNode allShotsNode = input.path("allShots");
        if (!allShotsNode.isArray() || allShotsNode.isEmpty())
        {
            allShotsNode = input.path("shots");
        }
        if (allShotsNode.isArray())
        {
            for (JsonNode an : allShotsNode)
            {
                long sid = an.path("storyboardId").asLong(0);
                int tc = an.path("takeCount").asInt(0);
                if (sid > 0 && tc > 0)
                {
                    origTakeByShot.put(sid, tc);
                    Integer recommended = readPositiveSnapshotInt(an, "recommendedDurationSeconds");
                    Integer actualDuration = readPositiveSnapshotInt(an, "durationSeconds");
                    String durationSource = an.path("durationSource").asText(null);
                    // 老快照没有逐镜头秒数时，仅回落原顶层秒数，禁止重读可变的分镜建议值
                    if (Objects.isNull(actualDuration) && Objects.nonNull(durationSeconds) && durationSeconds > 0)
                    {
                        actualDuration = durationSeconds;
                        durationSource = StoryboardDurationResolver.SOURCE_LEGACY_FALLBACK;
                    }
                    recommendedDurationByShot.put(sid, recommended);
                    durationByShot.put(sid, actualDuration);
                    durationSourceByShot.put(sid, StrUtil.trimToNull(durationSource));
                }
                // 首尾帧来源与参考音频记录 ID。
                String firstUrl = an.path("firstImageUrl").asText(null);
                String lastUrl = an.path("lastImageUrl").asText(null);
                long firstId = an.path("firstImageRecordId").asLong(0);
                long lastId = an.path("lastImageRecordId").asLong(0);
                ExplicitAudioSelection audioSelection = parseAudioSelectionSnapshot(an);
                List<Long> referenceVideoRecordIds = parseLongArraySnapshot(
                        an, SNAPSHOT_KEY_VIDEO_RECORD_IDS);
                if (sid > 0 && (StrUtil.isNotBlank(firstUrl) || StrUtil.isNotBlank(lastUrl)
                        || firstId > 0 || lastId > 0 || !audioSelection.isEmpty()
                        || CollectionUtil.isNotEmpty(referenceVideoRecordIds) || !StoryboardVideoAdvancedOptions.read(an).isEmpty()))
                {
                    edgeResolvedByShot.put(sid, new EdgeResolved(
                            StrUtil.trimToNull(firstUrl), firstId > 0 ? firstId : null,
                            StrUtil.trimToNull(lastUrl), lastId > 0 ? lastId : null,
                            audioSelection, referenceVideoRecordIds, StoryboardVideoAdvancedOptions.read(an)));
                }
            }
        }
        if (origTakeByShot.isEmpty())
        {
            log.error("分镜批量出片续生全集为空(快照缺 allShots/shots): taskId={}", taskId);
            throw new ServiceException("任务数据异常");
        }
        int originalTotalShots = origTakeByShot.size();

        // 已成功进度以落库记录为准，并按逻辑槽位精确补跑缺失项。
        Map<Long, List<Long>> recordsByShot = new LinkedHashMap<>();
        // storyboardId → 已成功逻辑槽位集合（升序、互异、落 [0,takeCount)）
        Map<Long, java.util.TreeSet<Integer>> succeededSlotsByShot = new LinkedHashMap<>();
        List<AidGenRecord> doneRecords = loadSucceededRecordsByParentTask(taskId, origTakeByShot.keySet());
        for (AidGenRecord rec : doneRecords)
        {
            Long sid = rec.getStoryboardId();
            if (Objects.isNull(sid)) { continue; }
            int takeCount = origTakeByShot.getOrDefault(sid, 0);
            recordsByShot.computeIfAbsent(sid, k -> new ArrayList<>()).add(rec.getId());
            seedRecordIds.add(rec.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("storyboardId", sid);
            m.put("recordId", rec.getId());
            if (StrUtil.isNotBlank(rec.getFileUrl())) { m.put("videoUrl", rec.getFileUrl()); }
            seedItems.add(m);
            // 解析槽位；缺失/越界/冲突 → 贪心取最低可用槽位，保证集合内槽位互异且落在 [0,takeCount)
            java.util.TreeSet<Integer> slots = succeededSlotsByShot.computeIfAbsent(sid, k -> new java.util.TreeSet<>());
            Integer slot = parseTakeIndexFromGenParams(rec.getGenParams());
            if (slot == null || slot < 0 || slot >= takeCount || slots.contains(slot))
            {
                slot = null;
                for (int j = 0; j < takeCount; j++) { if (!slots.contains(j)) { slot = j; break; } }
            }
            if (slot != null && slot >= 0 && slot < takeCount) { slots.add(slot); }
        }

        // 稳定镜头序号（按 allShots 顺序，0 基）：续生时同一镜头与 fresh 用同一 ordinal → 同槽位同 bizTaskId
        Map<Long, Integer> shotOrdinalById = new LinkedHashMap<>();
        int ordSeq = 0;
        for (Long sid : origTakeByShot.keySet()) { shotOrdinalById.put(sid, ordSeq++); }
        // storyboardId → 本轮要补的缺失槽位（0 基，升序）
        Map<Long, List<Integer>> missingSlotsByShot = new LinkedHashMap<>();

        // 按全集分类已完成镜头与待补镜头。
        for (Map.Entry<Long, Integer> en : origTakeByShot.entrySet())
        {
            Long sid = en.getKey();
            int takeCount = en.getValue();
            java.util.TreeSet<Integer> succeeded = succeededSlotsByShot.getOrDefault(sid, new java.util.TreeSet<>());
            List<Integer> missing = new ArrayList<>();
            for (int j = 0; j < takeCount; j++) { if (!succeeded.contains(j)) { missing.add(j); } }
            int successCount = takeCount - missing.size();
            List<Long> records = recordsByShot.getOrDefault(sid, java.util.Collections.emptyList());
            if (missing.isEmpty())
            {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("storyboardId", sid);
                m.put("takeCount", takeCount);
                m.put("successCount", successCount);
                m.put("recordIds", new ArrayList<>(records));
                seedShotResults.add(m);
                continue;
            }
            remainByShot.put(sid, missing.size());
            missingSlotsByShot.put(sid, missing);
            Map<String, Object> full = new LinkedHashMap<>();
            full.put("storyboardId", sid);
            full.put("takeCount", takeCount);
            full.put("successCount", successCount);
            full.put("recordIds", new ArrayList<>(records));
            priorShotFull.put(sid, full);
            if (successCount > 0)
            {
                Map<String, Object> prior = new LinkedHashMap<>();
                prior.put("takeCount", takeCount);
                prior.put("successCount", successCount);
                prior.put("recordIds", new ArrayList<>(records));
                seedShotPrior.put(sid, prior);
            }
        }
        int seedSuccessCount = seedRecordIds.size();

        if (remainByShot.isEmpty())
        {
            log.info("分镜批量出片续生无待补镜头: taskId={}", taskId);
            throw new ServiceException("无可续生项");
        }
        if (StrUtil.isBlank(modelCode))
        {
            throw new ServiceException("任务数据异常");
        }

        // 模型池编码优先取快照持久化值，缺失时按 direction 兜底（兼容无 funcCode 的老快照）
        String funcCode = input.hasNonNull("funcCode") ? input.get("funcCode").asText() : null;
        if (StrUtil.isBlank(funcCode))
        {
            funcCode = resolveFuncCodeByDirection(direction);
        }
        boolean imageLike = DIRECTION_IMAGE.equals(direction) || DIRECTION_GRID.equals(direction);
        boolean edge = DIRECTION_EDGE.equals(direction);
        String resolvedModelCode = resolveModelCode(modelCode, funcCode);
        AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(resolvedModelCode, funcCode, input.path("capabilityCode").asText(null));
        if (Objects.isNull(modelConfig)) { throw new ServiceException("模型不存在"); }
        if (imageLike && !Boolean.TRUE.equals(modelConfig.getSupportsImageInput())) { throw new ServiceException("该模型不支持图片"); }
        if (edge && !Boolean.TRUE.equals(modelConfig.getSupportsLastFrame())) { throw new ServiceException("不支持尾帧"); }
        // 续生快照未写 generateAudio 且模型支持音画同出：默认开启，与 fresh 提交一致
        generateAudio = resolveGenerateAudioOrDefault(modelConfig, generateAudio);
        assertGenerateAudioAllowed(modelConfig, generateAudio);
        Long modelId = modelConfig.getId();

        final Boolean genAudioF = generateAudio;
        final String userInputF = userInputText;
        Map<Long, PreparedShot> plannedShots = preparedResume.shots().stream()
                .collect(Collectors.toMap(shot -> shot.storyboard.getId(), shot -> shot,
                        (left, right) -> left, LinkedHashMap::new));
        List<PreparedShot> prepared = new ArrayList<>();
        List<ShotLock> heldLocks = new ArrayList<>();
        // 有待补镜头本轮被跳过（抢锁失败/解析失败）→ 终态须保持 PARTIAL_FAILED，且这些镜头原样回填 shots，不能丢失
        boolean forcePartial = false;
        Long projectId = null;
        Long episodeId = null;
        try
        {
            Map<Long, AidStoryboard> resumeStoryboards = new LinkedHashMap<>();
            try
            {
                // 先获取计划内全部镜头锁；任一冲突都在子媒体计费前整体退出。
                for (Long id : remainByShot.keySet())
                {
                    AidStoryboard sb = loadAndCheckStoryboard(id, userId);
                    resumeStoryboards.put(id, sb);
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + id;
                    String token = acquireShotLock(lockKey, id);
                    if (token == null)
                    {
                        throw new ServiceException("任务处理中");
                    }
                    heldLocks.add(new ShotLock(lockKey, token));
                    if (projectId == null)
                    {
                        projectId = sb.getProjectId();
                        episodeId = sb.getEpisodeId();
                    }
                }
                // 全部锁成功后才应用引用/提示词变更，并校验与只读报价计划未漂移。
                for (Map.Entry<Long, Integer> entry : remainByShot.entrySet())
                {
                    Long id = entry.getKey();
                    int remain = entry.getValue();
                    AidStoryboard sb = resumeStoryboards.get(id);
                    PreparedShot ps;
                    if (edge)
                    {
                        StoryboardVideoEdgeGenerateRequest r = new StoryboardVideoEdgeGenerateRequest();
                        r.setGenerateAudio(genAudioF);
                        r.setUserInputText(userInputF);
                        ps = prepareEdgeShot(sb, false, r, edgeResolvedByShot, modelConfig, userId,
                                remain, true, true);
                    }
                    else if (imageLike)
                    {
                        StoryboardVideoFromImageGenerateRequest r = new StoryboardVideoFromImageGenerateRequest();
                        r.setGenerateAudio(genAudioF);
                        r.setUserInputText(userInputF);
                        EdgeResolved snapshot = edgeResolvedByShot.get(id);
                        ps = prepareImageShot(sb, false, r, modelConfig, userId, remain,
                                Objects.isNull(snapshot) ? null : snapshot.audioSelection, true, true);
                    }
                    else
                    {
                        StoryboardVideoGenerateRequest r = new StoryboardVideoGenerateRequest();
                        r.setGenerateAudio(genAudioF);
                        r.setUserInputText(userInputF);
                        EdgeResolved snapshot = edgeResolvedByShot.get(id);
                        StoryboardVideoAdvancedOptions.restore(r, Objects.isNull(snapshot) ? null : snapshot.videoOptions);
                        ps = prepareMultiShot(sb, false, r, modelConfig, userId, remain,
                                Objects.isNull(snapshot) ? null : snapshot.audioSelection,
                                Objects.isNull(snapshot) ? null : snapshot.referenceVideoRecordIds, true, true);
                    }
                    ps = ps.withDuration(recommendedDurationByShot.get(id), durationByShot.get(id),
                            durationSourceByShot.get(id));
                    PreparedShot planned = plannedShots.get(id);
                    if (!samePreparedVideoBillingInputs(planned, ps) || planned.takeCount != remain)
                    {
                        throw new ServiceException("任务数据变化");
                    }
                    // 报价阶段允许缺少可信媒体元数据；正式续生已完成探测后必须使用
                    // 当前完整对象，不能继续把仅估算的 planned 快照下发到任务/计费链路。
                    prepared.add(ps);
                }
            }
            catch (RuntimeException e)
            {
                for (ShotLock held : heldLocks)
                {
                    releaseLockIfMine(held.key, held.token);
                }
                heldLocks.clear();
                throw e;
            }
            if (prepared.isEmpty())
            {
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                throw new ServiceException("无可续生项");
            }
            int newSubtasks = prepared.stream().mapToInt(p -> p.takeCount).sum();
            // 重写 inputSnapshot：保留 direction/model/比例/时长/音频/countPerShot，更新本轮受理镜头的 storyboardIds + shots(含新 lockToken) + runNo，
            // 使取消/队列收尾能按新 token 释放本轮持有的锁。
            Map<String, Object> newSnap = new LinkedHashMap<>();
            newSnap.put("direction", direction);
            // 续生重写快照须保留 func_code，否则下一轮续生丢失 pro/专属池信息
            newSnap.put("funcCode", funcCode);
            List<Long> resumeIds = new ArrayList<>();
            List<Map<String, Object>> resumeShots = new ArrayList<>();
            for (int i = 0; i < prepared.size(); i++)
            {
                PreparedShot ps = prepared.get(i);
                resumeIds.add(ps.storyboard.getId());
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("storyboardId", ps.storyboard.getId());
                s.put("takeCount", ps.takeCount);
                s.put("lockToken", heldLocks.get(i).token);
                // 首尾帧续生：保留本轮镜头的首/尾帧来源 + 配音项
                putEdgeSnapshot(s, ps);
                putDurationSnapshot(s, ps);
                resumeShots.add(s);
            }
            newSnap.put("storyboardIds", resumeIds);
            newSnap.put("modelCode", resolvedModelCode);
            newSnap.put("aspectRatio", aspectRatio);
            newSnap.put("resolution", resolution);
            newSnap.put("durationSeconds", durationSeconds);
            newSnap.put("countPerShot", perShotCount);
            newSnap.put("generateAudio", generateAudio);
            newSnap.put("userInputText", userInputText);
            newSnap.put("overwriteExistingFinal", overwriteExistingFinal);
            newSnap.put("shots", resumeShots);
            // 原批次全集原样保留（续生不改变全集），供后续轮次继续兜底未开始/无快照镜头
            List<Map<String, Object>> allShotsOut = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : origTakeByShot.entrySet())
            {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("storyboardId", e.getKey());
                a.put("takeCount", e.getValue());
                a.put("recommendedDurationSeconds", recommendedDurationByShot.get(e.getKey()));
                a.put("durationSeconds", durationByShot.get(e.getKey()));
                a.put("durationSource", durationSourceByShot.get(e.getKey()));
                // 首尾帧全集：保留首/尾帧来源（上传 URL / 记录 ID）+ 配音项，使后续轮次续生仍可重解析
                EdgeResolved er = edgeResolvedByShot.get(e.getKey());
                if (er != null)
                {
                    if (StrUtil.isNotBlank(er.firstImageUrl)) { a.put("firstImageUrl", er.firstImageUrl); }
                    if (Objects.nonNull(er.firstImageRecordId)) { a.put("firstImageRecordId", er.firstImageRecordId); }
                    if (StrUtil.isNotBlank(er.lastImageUrl)) { a.put("lastImageUrl", er.lastImageUrl); }
                    if (Objects.nonNull(er.lastImageRecordId)) { a.put("lastImageRecordId", er.lastImageRecordId); }
                    if (CollectionUtil.isNotEmpty(er.audioSelection.audioRecordIds))
                    {
                        a.put(SNAPSHOT_KEY_AUDIO_RECORD_IDS, er.audioSelection.audioRecordIds);
                    }
                    if (CollectionUtil.isNotEmpty(er.audioSelection.referenceAudioIds))
                    {
                        a.put(SNAPSHOT_KEY_REFERENCE_AUDIO_IDS, er.audioSelection.referenceAudioIds);
                    }
                    if (CollectionUtil.isNotEmpty(er.referenceVideoRecordIds))
                    {
                        a.put(SNAPSHOT_KEY_VIDEO_RECORD_IDS, er.referenceVideoRecordIds);
                    }
                    if (!er.videoOptions.isEmpty()) a.put("videoOptions", er.videoOptions);
                }
                allShotsOut.add(a);
            }
            newSnap.put("allShots", allShotsOut);
            newSnap.put("runNo", newRunNo);
            // 供队列层回滚恢复 totalCount（续生 CAS 会把 total 改成本轮 seed+new）。
            // 服务层回滚用内存中的 originalInputSnapshot 直接还原 inputSnapshot，无需存进快照。
            newSnap.put("priorTotalCount", originalTotalCount);
            batchTaskSlotService.attachSnapshotMetadata(
                    newSnap, batchTaskSlotService.reservationFromTask(task));
            String newSnapJson;
            try
            {
                newSnapJson = OBJECT_MAPPER.writeValueAsString(newSnap);
            }
            catch (Exception e)
            {
                // 简单类型序列化失败极罕见；一旦失败不可降级（会丢新 token/runNo，导致后续按旧值释放锁/判 runNo）→ 释放锁 + 失败
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.error("分镜批量出片续生快照序列化失败: taskId={}", taskId, e);
                throw new ServiceException("提交失败，请重试");
            }

            // 单次 CAS：PARTIAL_FAILED/FAILED → PENDING 同时回写 totalCount + 新 inputSnapshot + 清空旧 errorMessage，按行数判定推进权。
            LambdaUpdateWrapper<AidExtractTask> toPending = Wrappers.lambdaUpdate();
            toPending.eq(AidExtractTask::getId, taskId);
            toPending.in(AidExtractTask::getStatus,
                    TASK_STATUS_PARTIAL_FAILED, TASK_STATUS_FAILED, TASK_STATUS_CANCELLED);
            toPending.set(AidExtractTask::getStatus, TASK_STATUS_PENDING);
            toPending.set(AidExtractTask::getTotalCount, seedSuccessCount + newSubtasks);
            toPending.set(AidExtractTask::getInputSnapshot, newSnapJson);
            // 清空上一轮可能残留的 errorMessage（如队列回滚写入的"续生提交失败" / 崩溃回收的"超时未完成"），避免成功后详情仍带旧错误
            toPending.set(AidExtractTask::getErrorMessage, null)
                .set(AidExtractTask::getErrorDetailJson, null);
            toPending.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            int casRows;
            try
            {
                casRows = extractTaskService.getBaseMapper().update(null, toPending);
            }
            catch (RuntimeException ex)
            {
                // CAS update 本身异常：状态未推进（仍 PARTIAL_FAILED），仅释放本轮锁即可
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.error("分镜批量出片续生状态CAS异常: taskId={}", taskId, ex);
                throw new ServiceException("提交失败，请重试");
            }
            if (casRows == 0)
            {
                for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
                log.warn("分镜批量出片续生 CAS 失败（状态已变）: taskId={}", taskId);
                throw new ServiceException("状态不支持");
            }

            // 已切到 PENDING：此后任何入队失败/异常都必须回滚到原 PARTIAL_FAILED（恢复 totalCount + 释放锁），保留续生入口
            boolean enqueued;
            try
            {
                try { assetExtractService.clearCancelFlag(taskId, task.getBillingTraceId()); } catch (Exception ignore) { /* ignore */ }
                enqueued = buildJobsAndEnqueue(taskId, projectId, episodeId, userId, resolvedModelCode, modelId,
                        modelConfig, aspectRatio, resolution, genAudioF, userInputF, perShotCount,
                        direction, prepared, heldLocks,
                        newSubtasks, originalTotalShots, seedSuccessCount, seedRecordIds, seedItems, seedShotResults,
                        seedShotPrior, newRunNo, forcePartial, overwriteExistingFinal,
                        shotOrdinalById, missingSlotsByShot);
            }
            catch (RuntimeException ex)
            {
                log.error("分镜批量出片续生入队异常，回滚原状态: taskId={}", taskId, ex);
                releaseLocksAndRollbackResume(taskId, heldLocks, originalTotalCount, originalStatus, originalInputSnapshot);
                throw new ServiceException("提交失败，请重试");
            }
            if (!enqueued)
            {
                // 续生提交失败：回滚到原状态（保留续生入口与原 resultData/快照），仅向前端返回"提交失败"
                releaseLocksAndRollbackResume(taskId, heldLocks, originalTotalCount, originalStatus, originalInputSnapshot);
                throw new ServiceException("提交失败，请重试");
            }

            StoryboardVideoGenerateVO vo = new StoryboardVideoGenerateVO();
            vo.setTaskId(taskId);
            vo.setStatus(TASK_STATUS_PENDING);
            vo.setModelName(resolvedModelCode);
            vo.setTotalShots(prepared.size());
            vo.setCountPerShot(perShotCount);
            vo.setTotalSubtasks(newSubtasks);
            List<StoryboardVideoGenerateVO.ShotResult> voItems = new ArrayList<>();
            for (PreparedShot ps : prepared)
            {
                voItems.add(new StoryboardVideoGenerateVO.ShotResult(ps.storyboard.getId(), true, null));
            }
            vo.setItems(voItems);
            log.info("分镜批量出片续生提交: taskId={}, resumeShots={}, newSubtasks={}, seedSuccess={}",
                    taskId, prepared.size(), newSubtasks, seedSuccessCount);
            return vo;
        }
        catch (RuntimeException e)
        {
            log.error("分镜批量出片续生受理失败: taskId={}", taskId, e);
            throw e;
        }
    }

    /**
     * Durable 复用 / 阻断决策：按确定性 bizTaskId 反查本逻辑槽位的既有媒体任务（窗口无关，与媒体层 1 小时幂等窗解耦）。
     */
    private String reconcileExistingMediaUrl(long bizTaskId)
    {
        List<AidMediaTask> tasks;
        try
        {
            tasks = aidMediaTaskMapper.selectList(
                    Wrappers.<AidMediaTask>lambdaQuery()
                            .select(AidMediaTask::getId, AidMediaTask::getStatus, AidMediaTask::getOssUrl)
                            .eq(AidMediaTask::getBizTaskType, BIZ_TASK_TYPE)
                            .eq(AidMediaTask::getBizTaskId, bizTaskId));
        }
        catch (Exception e)
        {
            log.error("分镜批量出片 durable 复用查询失败(防重复扣费, fail-closed): bizTaskId={}", bizTaskId, e);
            throw new ServiceException("查询失败");
        }
        if (CollectionUtil.isEmpty(tasks))
        {
            return null; // 无既有媒体任务 → 允许生成
        }
        // 是否存在「不可重生」的旧任务：在飞 / 成功但 OSS 待补偿——这两类都不能新建同槽位任务，否则重复生成/扣费
        boolean blockRegenerate = false;
        for (AidMediaTask t : tasks)
        {
            String st = t.getStatus();
            if (TASK_STATUS_SUCCEEDED.equals(st))
            {
                // 成功且 OSS 就绪 → 直接复用（最高优先，立即返回）
                if (StrUtil.isNotBlank(t.getOssUrl())) { return t.getOssUrl(); }
                // 成功但 OSS 暂空（污染快照）→ 阻断，等补偿回填
                blockRegenerate = true;
            }
            else if (!TASK_STATUS_FAILED.equals(st))
            {
                // 非 SUCCEEDED、非 FAILED 即在飞（PENDING/QUEUED/PROCESSING/WAIT_*）→ 阻断
                blockRegenerate = true;
            }
            // FAILED：未成功扣费的终态，忽略（不阻断重生）
        }
        if (blockRegenerate)
        {
            log.info("storyboard video batch slot has inflight media task, block regenerate: bizTaskId={}", bizTaskId);
            throw new ShotInFlightException();
        }
        return null; // 仅 FAILED 终态 → 允许重新生成
    }

    /** 单条视频调用统一视频生成主链路（计费 / 排队 / 退款全部由媒体主链路处理）。 */
    private static final class ShotInFlightException extends RuntimeException
    {
        ShotInFlightException()
        {
            super("media task inflight");
        }
    }

    private MediaTaskResponse submitSingleVideoMedia(VideoGenJob job, long bizSeq,
                                                     String genParamsJson, String dispatchToken)
    {
        AidStoryboard storyboard = job.storyboard;
        // 扇入上下文：随 request_json 落库，事件回调时 listener 反读重建 gen_record（厂商 Provider 只取已知键）
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("storyboardId", storyboard.getId());
        ctx.put("modelId", job.modelId);
        ctx.put("genType", job.genType);
        ctx.put("finalPrompt", job.finalPrompt);
        ctx.put("userInputText", StrUtil.blankToDefault(job.userVideoPromptInput, job.rawVideoPrompt));
        ctx.put("durationSeconds", job.durationSeconds);
        ctx.put("firstImageRecordId", job.firstImageRecordId);
        ctx.put("lastImageRecordId", job.lastImageRecordId);
        ctx.put("genParams", genParamsJson);
        ctx.put("bizSeq", bizSeq);
        ctx.put("overwriteExistingFinal", job.overwriteExistingFinal);
        ctx.put("executionTraceId", dispatchToken);
        MediaVideoGenerateRequest videoRequest = buildVideoMediaRequest(
                storyboard, job.finalPrompt, job.referenceImages, job.baseImageUrl,
                job.lastFrameImageUrl, job.referenceAudios, job.referenceVideos, job.providerExtraOptions,
                job.modelConfig, job.userId, job.aspectRatio, job.resolution,
                job.durationSeconds, job.generateAudio, Map.of(OPT_KEY_CTX, ctx));
        videoRequest.setBizTaskType(BIZ_TASK_TYPE);
        videoRequest.setBizTaskId(bizSeq);
        videoRequest.setParentTaskId(job.taskId);

        return batchParentSubmissionGuard.executeManagedTask(job.taskId, dispatchToken,
                () -> mediaGenerationService.generateVideo(videoRequest));
    }

    private MediaVideoGenerateRequest buildVideoMediaRequest(
            AidStoryboard storyboard, PreparedShot shot, AiModelConfigVo modelConfig,
            Long userId, String aspectRatio, String resolution, Integer durationSeconds,
            Boolean generateAudio, Map<String, Object> extraOptions)
    {
        return buildVideoMediaRequest(storyboard, shot.finalPrompt, shot.referenceImages,
                shot.baseImageUrl, shot.lastFrameImageUrl, shot.referenceAudios,
                shot.referenceVideos, shot.providerExtraOptions, modelConfig, userId, aspectRatio, resolution,
                durationSeconds, generateAudio, extraOptions);
    }

    private boolean samePreparedVideoBillingInputs(PreparedShot planned, PreparedShot actual)
    {
        return planned != null && actual != null
                && Objects.equals(planned.finalPrompt, actual.finalPrompt)
                && Objects.equals(planned.referenceImages, actual.referenceImages)
                && Objects.equals(planned.baseImageUrl, actual.baseImageUrl)
                && Objects.equals(planned.lastFrameImageUrl, actual.lastFrameImageUrl)
                && Objects.equals(planned.referenceAudios, actual.referenceAudios)
                && sameReferenceVideoSelection(planned.referenceVideos, actual.referenceVideos)
                && Objects.equals(planned.providerExtraOptions, actual.providerExtraOptions)
                && Objects.equals(planned.durationSeconds, actual.durationSeconds);
    }

    /**
     * 续生报价与正式分镜准备均只读取已有服务端元数据，真实内容探测下沉媒体主链路。
     * 来源记录、URL 或已确定元数据发生变化仍视为漂移；历史快照缺字段则允许服务端补齐。
     */
    private boolean sameReferenceVideoSelection(List<ReferenceVideoInput> planned,
            List<ReferenceVideoInput> actual)
    {
        List<ReferenceVideoInput> quoted = planned == null ? Collections.emptyList() : planned;
        List<ReferenceVideoInput> verified = actual == null ? Collections.emptyList() : actual;
        if (quoted.size() != verified.size())
        {
            return false;
        }
        for (int index = 0; index < quoted.size(); index++)
        {
            ReferenceVideoInput left = quoted.get(index);
            ReferenceVideoInput right = verified.get(index);
            if (left == null || right == null
                    || !Objects.equals(left.getRecordId(), right.getRecordId())
                    || !Objects.equals(left.getVideoUrl(), right.getVideoUrl())
                    || !sameKnownValue(left.getDurationMs(), right.getDurationMs())
                    || !sameKnownValue(left.getFileSizeBytes(), right.getFileSizeBytes())
                    || !sameKnownValue(left.getWidth(), right.getWidth())
                    || !sameKnownValue(left.getHeight(), right.getHeight())
                    || !sameKnownValue(left.getFormat(), right.getFormat())
                    || !sameKnownDecimal(left.getFps(), right.getFps()))
            {
                return false;
            }
        }
        return true;
    }

    private boolean sameKnownValue(Object planned, Object actual)
    {
        return planned == null || Objects.equals(planned, actual);
    }

    private boolean sameKnownDecimal(BigDecimal planned, BigDecimal actual)
    {
        return planned == null || actual != null && planned.compareTo(actual) == 0;
    }

    /** 正式提交与只读报价共用的最终媒体请求构造器。 */
    private MediaVideoGenerateRequest buildVideoMediaRequest(
            AidStoryboard storyboard, String prompt, List<String> referenceImages,
            String baseImageUrl, String lastFrameImageUrl,
            List<ReferenceAudioInput> referenceAudios, List<ReferenceVideoInput> referenceVideos,
            Map<String, Object> providerExtraOptions,
            AiModelConfigVo modelConfig, Long userId, String aspectRatio, String resolution,
            Integer durationSeconds, Boolean generateAudio, Map<String, Object> extraOptions)
    {
        MediaVideoGenerateRequest request = new MediaVideoGenerateRequest();
        request.setPrompt(prompt);
        request.setModelName(modelConfig.getModelCode());
        request.setCapabilityCode(modelConfig.getCapabilityCode());
        request.setBusinessFuncCode(modelConfig.getBusinessFuncCode());
        request.setUserId(userId);
        request.setProjectId(storyboard.getProjectId());
        request.setEpisodeId(storyboard.getEpisodeId());
        request.setImageUrl(baseImageUrl);
        request.setAspectRatio(aspectRatio);
        request.setDurationSeconds(durationSeconds);
        Map<String, Object> options = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(referenceImages))
        {
            options.put("referenceImages", new ArrayList<>(referenceImages));
        }
        if (CollectionUtil.isNotEmpty(providerExtraOptions))
        {
            options.putAll(providerExtraOptions);
        }
        if (StrUtil.isNotBlank(resolution))
        {
            options.put("resolution", resolution);
        }
        if (Objects.nonNull(generateAudio))
        {
            request.setAudio(generateAudio);
            options.put("generate_audio", generateAudio);
        }
        if (StrUtil.isNotBlank(lastFrameImageUrl))
        {
            options.put("lastFrameImageUrl", lastFrameImageUrl);
            options.put("endImageUrl", lastFrameImageUrl);
            options.put("end_image_url", lastFrameImageUrl);
            options.put("start_end", Boolean.TRUE);
        }
        if (CollectionUtil.isNotEmpty(referenceAudios))
        {
            request.setReferenceAudios(new ArrayList<>(referenceAudios));
        }
        if (CollectionUtil.isNotEmpty(referenceVideos))
        {
            request.setReferenceVideoRecordIds(referenceVideos.stream()
                    .map(ReferenceVideoInput::getRecordId).toList());
            request.setResolvedReferenceVideos(new ArrayList<>(referenceVideos));
            options.put("referenceVideos", referenceVideos.stream()
                    .map(ReferenceVideoInput::getVideoUrl).toList());
            boolean durationsComplete = referenceVideos.stream().allMatch(value -> value != null
                    && value.getDurationMs() != null && value.getDurationMs() > 0);
            if (durationsComplete)
            {
                List<BigDecimal> durations = referenceVideos.stream()
                        .map(ReferenceVideoInput::getDurationMs)
                        .map(value -> BigDecimal.valueOf(value).movePointLeft(3).stripTrailingZeros())
                        .toList();
                options.put("referenceVideoDurations", durations);
                long totalMs = referenceVideos.stream()
                        .map(ReferenceVideoInput::getDurationMs)
                        .mapToLong(Long::longValue)
                        .sum();
                int totalSeconds = Math.toIntExact(Math.floorDiv(Math.addExact(totalMs, 999L), 1000L));
                options.put("referenceVideoSeconds", totalSeconds);
            }
        }
        if (extraOptions != null)
        {
            options.putAll(extraOptions);
        }
        request.setOptions(options);
        return request;
    }

    /** 异步执行所需的全部入参快照（不可变）。 */
    private static final class VideoGenJob
    {
        final Long taskId;
        final Long userId;
        final AidStoryboard storyboard;
        final String modelCode;
        final Long modelId;
        final AiModelConfigVo modelConfig;
        final String finalPrompt;
        final String rawVideoPrompt;
        final List<String> referenceImages; // unmodifiable copy
        final String baseImageUrl;
        final int videoCount;
        final String aspectRatio;
        /** 清晰度档位（已按模型 capability 规范化；可空=不下发走上游默认） */
        final String resolution;
        final Integer durationSeconds;
        final Boolean generateAudio;
        final String userInputText;
        final String userVideoPromptInput;
        /** 该镜头 bizTaskId 基址 = 父任务编码段 + 稳定镜头序号(allShots 位置)*1000（不含 take 槽位 / runNo） */
        final long bizSeqBase;
        /** 本轮要生成的逻辑 take 槽位（0 基）：fresh=[0..takeCount-1]，续生=缺失槽位。size 即本轮条数 */
        final List<Integer> takeSlots;
        final String lockKey;
        final String lockToken;
        /** 产物类型（i2v / edge）：落 aid_gen_record.gen_type */
        final String genType;
        /** 首尾帧出片：尾帧图 URL（其余方向为 null，generateSingleVideo 据此透传厂商尾帧键） */
        final String lastFrameImageUrl;
        /** 首尾帧出片：首图记录 ID（落 aid_gen_record.first_image_id） */
        final Long firstImageRecordId;
        /** 首尾帧出片：尾图记录 ID（落 aid_gen_record.last_image_id） */
        final Long lastImageRecordId;
        /** 已解析参考音频。 */
        final List<ReferenceAudioInput> referenceAudios;
        /** 已解析参考视频。 */
        final List<ReferenceVideoInput> referenceVideos;
        /** 厂商专属扩展参数（装配策略产出，如 Vidu 主体调用 subjects），提交时并入 options */
        final Map<String, Object> providerExtraOptions;
        /** true=批量生成保持自动覆盖；false=单个生成仅在尚无主视频时自动设置。 */
        final boolean overwriteExistingFinal;

        VideoGenJob(Long taskId, Long userId, AidStoryboard storyboard,
                    String modelCode, Long modelId, AiModelConfigVo modelConfig,
                    String finalPrompt, String rawVideoPrompt, List<String> referenceImages,
                    String baseImageUrl, int videoCount, String aspectRatio, String resolution,
                    Integer durationSeconds, Boolean generateAudio,
                    String userInputText, String userVideoPromptInput,
                    long bizSeqBase, List<Integer> takeSlots, String lockKey, String lockToken,
                     String genType, String lastFrameImageUrl, Long firstImageRecordId, Long lastImageRecordId,
                     List<ReferenceAudioInput> referenceAudios, List<ReferenceVideoInput> referenceVideos,
                     Map<String, Object> providerExtraOptions,
                    boolean overwriteExistingFinal)
        {
            this.taskId = taskId;
            this.userId = userId;
            this.storyboard = storyboard;
            this.modelCode = modelCode;
            this.modelId = modelId;
            this.modelConfig = modelConfig;
            this.finalPrompt = finalPrompt;
            this.rawVideoPrompt = rawVideoPrompt;
            this.referenceImages = (referenceImages == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(referenceImages));
            this.baseImageUrl = baseImageUrl;
            this.videoCount = videoCount;
            this.aspectRatio = aspectRatio;
            this.resolution = resolution;
            this.durationSeconds = durationSeconds;
            this.generateAudio = generateAudio;
            this.userInputText = userInputText;
            this.userVideoPromptInput = userVideoPromptInput;
            this.bizSeqBase = bizSeqBase;
            this.takeSlots = (takeSlots == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(takeSlots));
            this.lockKey = lockKey;
            this.lockToken = lockToken;
            this.genType = StrUtil.blankToDefault(genType, GEN_TYPE_I2V);
            this.lastFrameImageUrl = lastFrameImageUrl;
            this.firstImageRecordId = firstImageRecordId;
            this.lastImageRecordId = lastImageRecordId;
            this.referenceAudios = (referenceAudios == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(referenceAudios));
            this.referenceVideos = (referenceVideos == null)
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(referenceVideos));
            this.providerExtraOptions = (providerExtraOptions == null)
                    ? java.util.Collections.emptyMap()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(providerExtraOptions));
            this.overwriteExistingFinal = overwriteExistingFinal;
        }
    }

    /** 批量异步执行容器：一个父任务下的多镜头 Job 列表 + 续生携带的既有成功快照（不可变）。 */
    private static final class VideoBatchJob
    {
        final Long taskId;
        final Long userId;
        final String modelCode;
        final int countPerShot;
        /** 原批次镜头总数（累计口径，写入 resultData.totalShots；续生时为原批次全集大小，非本轮补跑数） */
        final int totalShots;
        /** 进度分母 = 既有成功条数 + 本批新子任务条数 */
        final int totalSubtasks;
        /** 续生时携带的既有成功条数（首次提交为 0） */
        final int seedSuccessCount;
        /** 运行批次号（持久化、单调递增，存 input_snapshot）：仅用于"重试超限"守卫与排查追溯；
         *  bizTaskId 按逻辑槽位确定性编码（不含 runNo），续生重跑同槽位得同一 bizTaskId、媒体层幂等去重 */
        final int runNo;
        final List<VideoGenJob> shots;
        final List<ShotLock> shotLocks;
        final List<Long> seedRecordIds;
        final List<Map<String, Object>> seedItems;
        final List<Map<String, Object>> seedShotResults;
        /** 续生时「部分完成且本轮重跑」镜头的既有进度（storyboardId → {takeCount, successCount, recordIds}），用于 shots 累计口径 */
        final Map<Long, Map<String, Object>> seedShotPrior;
        /** 强制部分失败终态：续生时有待补镜头被跳过（未受理）时为 true，避免错误置 SUCCEEDED 关闭续生入口 */
        final boolean forcePartial;

        VideoBatchJob(Long taskId, Long userId, String modelCode, int countPerShot, int totalShots,
                      int totalSubtasks, int seedSuccessCount, int runNo, List<VideoGenJob> shots,
                      List<ShotLock> shotLocks, List<Long> seedRecordIds, List<Map<String, Object>> seedItems,
                      List<Map<String, Object>> seedShotResults, Map<Long, Map<String, Object>> seedShotPrior,
                      boolean forcePartial)
        {
            this.taskId = taskId;
            this.userId = userId;
            this.modelCode = modelCode;
            this.countPerShot = countPerShot;
            this.totalShots = totalShots;
            this.totalSubtasks = totalSubtasks;
            this.seedSuccessCount = seedSuccessCount;
            this.runNo = runNo;
            this.shots = (shots == null) ? java.util.Collections.emptyList() : shots;
            this.shotLocks = (shotLocks == null) ? new ArrayList<>() : shotLocks;
            this.seedRecordIds = (seedRecordIds == null) ? new ArrayList<>() : seedRecordIds;
            this.seedItems = (seedItems == null) ? new ArrayList<>() : seedItems;
            this.seedShotResults = (seedShotResults == null) ? new ArrayList<>() : seedShotResults;
            this.seedShotPrior = (seedShotPrior == null) ? java.util.Collections.emptyMap() : seedShotPrior;
            this.forcePartial = forcePartial;
        }
    }
    private Long persistGenRecord(VideoGenJob job, long bizSeq, String videoUrl, String genParamsJson)
    {
        AidStoryboard storyboard = job.storyboard;
        AidGenRecord record = new AidGenRecord();
        record.setUserId(job.userId);
        record.setProjectId(storyboard.getProjectId());
        record.setEpisodeId(storyboard.getEpisodeId());
        record.setStoryboardId(storyboard.getId());
        record.setGenType(job.genType);
        record.setModelId(job.modelId);
        // 首尾帧出片：记录首/尾图记录 ID
        if (Objects.nonNull(job.firstImageRecordId)) { record.setFirstImageId(job.firstImageRecordId); }
        if (Objects.nonNull(job.lastImageRecordId)) { record.setLastImageId(job.lastImageRecordId); }
        record.setPromptText(job.finalPrompt);
        record.setUserInputText(StrUtil.blankToDefault(job.userVideoPromptInput, job.rawVideoPrompt));
        record.setFileUrl(videoUrl);
        if (Objects.nonNull(job.durationSeconds))
        {
            record.setVideoDuration(Long.valueOf(job.durationSeconds));
        }
        record.setGenParams(genParamsJson);
        record.setBizSeq(bizSeq); // 幂等唯一键：由唯一索引 uk_gen_record_biz_seq 兜底防重复落库
        record.setStatus(1); // 1=成功
        record.setIsSelected(job.overwriteExistingFinal ? SELECTED_YES : SELECTED_NO);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(DateUtils.getNowDate());
        record.setCreateBy(String.valueOf(job.userId));
        record.setCostCredits(resolveSettledVideoCost(bizSeq, job.userId, videoUrl));
        try
        {
            aidGenRecordService.save(record);
        }
        catch (DuplicateKeyException dup)
        {
            // DIRECT 内联与事件扇入并发竞态：唯一键冲突说明同一 take 已落库，幂等忽略
            log.info("分镜出片 gen_record 已存在(biz_seq 唯一键冲突,幂等忽略): bizSeq={}, storyboardId={}",
                    bizSeq, storyboard.getId());
            markExistingVideoRecordAsFinal(bizSeq, job.userId, job.overwriteExistingFinal);
            return null;
        }
        markStoryboardFinalVideo(storyboard.getId(), record.getId(), job.userId, job.overwriteExistingFinal);
        return record.getId();
    }
    /** 注入 aid_media_task.request_json.options 的单一命名空间上下文键。 */
    private static final String OPT_KEY_CTX = "sbzVideoGenCtx";

    /** 通用扇入支撑（失败计数 / 收尾CAS / bizSeq反解 / 快照解析 / 父任务续租），与出图共用。 */
    @Autowired
    private MediaGenFanInSupport fanInSupport;

    /** 幂等落 gen_record：按 bizSeq 查重，已存在则跳过（事件重投 / DIRECT 与事件竞态双保险）。 */
    private void persistGenRecordIdempotent(VideoGenJob shot, long bizSeq, String videoUrl, String genParamsJson)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.nonNull(existing))
        {
            syncSettledVideoCost(existing.getId(), bizSeq, shot.userId, videoUrl);
            Long recordUserId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : shot.userId;
            markStoryboardFinalVideo(existing.getStoryboardId(), existing.getId(), recordUserId,
                    shot.overwriteExistingFinal);
            return;
        }
        persistGenRecord(shot, bizSeq, videoUrl, genParamsJson);
    }

    /** 按 biz_seq 唯一键加载该 take 已落库记录（与唯一索引 uk_gen_record_biz_seq 语义一致）。 */
    private AidGenRecord loadGenRecordByBizSeq(long bizSeq)
    {
        try
        {
            return aidGenRecordService.getOne(
                    Wrappers.<AidGenRecord>lambdaQuery()
                            .select(AidGenRecord::getId, AidGenRecord::getStoryboardId, AidGenRecord::getUserId)
                            .eq(AidGenRecord::getBizSeq, bizSeq)
                            .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"), false);
        }
        catch (Exception e)
        {
            log.warn("分镜出片 bizSeq 查重异常(按不存在处理): bizSeq={}", bizSeq, e);
            return null;
        }
    }

    private void markExistingVideoRecordAsFinal(long bizSeq, Long fallbackUserId, boolean overwriteExistingFinal)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        if (Objects.isNull(existing))
        {
            return;
        }
        Long userId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : fallbackUserId;
        markStoryboardFinalVideo(existing.getStoryboardId(), existing.getId(), userId, overwriteExistingFinal);
    }

    /** 从同一成功媒体任务读取已结算积分，不重新报价或执行扣款。 */
    private BigDecimal resolveSettledVideoCost(long bizSeq, Long userId, String videoUrl) {
        AidMediaTask settled = aidMediaTaskMapper.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                .select(AidMediaTask::getActualCost)
                .eq(AidMediaTask::getBizTaskType, BIZ_TASK_TYPE)
                .eq(AidMediaTask::getBizTaskId, bizSeq)
                .eq(AidMediaTask::getUserId, userId)
                .eq(AidMediaTask::getStatus, TASK_STATUS_SUCCEEDED)
                .eq(AidMediaTask::getOssUrl, videoUrl)
                .isNotNull(AidMediaTask::getActualCost)
                .orderByDesc(AidMediaTask::getId).last("LIMIT 1"));
        return settled == null ? null : settled.getActualCost();
    }

    private void syncSettledVideoCost(Long recordId, long bizSeq, Long userId, String videoUrl) {
        BigDecimal cost = resolveSettledVideoCost(bizSeq, userId, videoUrl);
        if (cost == null || recordId == null) return;
        aidGenRecordService.update(Wrappers.<AidGenRecord>lambdaUpdate()
                .eq(AidGenRecord::getId, recordId).eq(AidGenRecord::getUserId, userId)
                .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                .and(query -> query.isNull(AidGenRecord::getCostCredits)
                        .or().ne(AidGenRecord::getCostCredits, cost))
                .set(AidGenRecord::getCostCredits, cost)
                .set(AidGenRecord::getUpdateTime, DateUtils.getNowDate())
                .set(AidGenRecord::getUpdateBy, String.valueOf(userId)));
    }

    /** 媒体子任务终态回调（由 StoryboardVideoGenEventListener 调用）：成功幂等落库、失败计数，随后尝试收尾。 */
    @Override
    public void onMediaVideoTaskTerminal(Long mediaTaskId, boolean success, String ossUrl)
    {
        AidMediaTask mt = aidMediaTaskMapper.selectById(mediaTaskId);
        if (Objects.isNull(mt) || Objects.isNull(mt.getBizTaskId())) { return; }
        long bizSeq = mt.getBizTaskId();
        Long parentTaskId = fanInSupport.decodeParentTaskId(bizSeq);
        Map<String, Object> ctx = extractCtxFromMediaTask(mt);
        String executionTraceId = ctx.get("executionTraceId") instanceof String trace ? trace : null;
        try
        {
            batchParentSubmissionGuard.executeManagedBusinessCommit(parentTaskId, executionTraceId, () -> {
                boolean contextConsumed = false;
                try
                {
                    if (success && StrUtil.isNotBlank(ossUrl))
                    {
                        contextConsumed = persistGenRecordFromCtx(mt, bizSeq, ossUrl);
                    }
                    else if (!success)
                    {
                        fanInSupport.incrFail(parentTaskId);
                        contextConsumed = true;
                    }
                }
                catch (Exception e)
                {
                    log.error("分镜出片事件落库异常: mediaTaskId={}, bizSeq={}", mediaTaskId, bizSeq, e);
                    fanInSupport.incrFail(parentTaskId, "任务数据处理异常: " + e.getMessage());
                }
                finalizeVideoBatchIfDone(parentTaskId);
                if (contextConsumed)
                {
                    mediaTaskArchiveService.removeConsumedFanInContext(mediaTaskId, OPT_KEY_CTX);
                }
                return null;
            });
        }
        catch (BatchTaskExecutionRejectedException rejected)
        {
            log.info("分镜出片事件执行周期已变化，跳过业务回写: mediaTaskId={}, parentTaskId={}",
                    mediaTaskId, parentTaskId);
        }
    }

    /** 从 request_json.options.sbzVideoGenCtx 反序列化上下文，幂等落 gen_record。 */
    private boolean persistGenRecordFromCtx(AidMediaTask mt, long bizSeq, String ossUrl)
    {
        AidGenRecord existing = loadGenRecordByBizSeq(bizSeq);
        Map<String, Object> ctx = extractCtxFromMediaTask(mt);
        if (Objects.nonNull(existing))
        {
            syncSettledVideoCost(existing.getId(), bizSeq, mt.getUserId(), ossUrl);
            // 上下文已压缩说明该成功事件此前已消费，不再重复改动用户后续选择。
            if (CollectionUtil.isNotEmpty(ctx))
            {
                Long recordUserId = Objects.nonNull(existing.getUserId()) ? existing.getUserId() : mt.getUserId();
                markStoryboardFinalVideo(existing.getStoryboardId(), existing.getId(), recordUserId,
                        resolveOverwriteExistingFinal(ctx));
            }
            return true; // 幂等：已落库时不再依赖 request_json 上下文，兼容上下文压缩后的重复事件
        }
        if (CollectionUtil.isEmpty(ctx))
        {
            log.error("分镜出片事件落库缺少上下文(跳过,计失败防卡死): mediaTaskId={}, bizSeq={}", mt.getId(), bizSeq);
            fanInSupport.incrFail(
                    fanInSupport.decodeParentTaskId(bizSeq), "任务数据处理异常: 媒体上下文缺失");
            return false;
        }
        Long storyboardId = ctx.get("storyboardId") instanceof Number n ? n.longValue() : null;
        if (Objects.isNull(storyboardId))
        {
            // 上下文缺 storyboardId 无法落库：计失败防卡死，与上方"缺上下文"分支保持一致。
            // 否则该 take 既不计成功也不计失败，扇入永远凑不齐 total → 父任务永卡 PROCESSING（仅能靠僵尸对账兜底）。
            log.error("分镜出片事件落库缺 storyboardId(跳过,计失败防卡死): mediaTaskId={}, bizSeq={}", mt.getId(), bizSeq);
            fanInSupport.incrFail(
                    fanInSupport.decodeParentTaskId(bizSeq), "任务数据处理异常: 分镜标识缺失");
            return false;
        }
        AidGenRecord record = new AidGenRecord();
        record.setUserId(mt.getUserId());
        record.setProjectId(mt.getProjectId());
        record.setEpisodeId(mt.getEpisodeId());
        record.setStoryboardId(storyboardId);
        record.setGenType((String) ctx.get("genType"));
        record.setModelId(ctx.get("modelId") instanceof Number n ? n.longValue() : null);
        if (ctx.get("firstImageRecordId") instanceof Number n) { record.setFirstImageId(n.longValue()); }
        if (ctx.get("lastImageRecordId") instanceof Number n) { record.setLastImageId(n.longValue()); }
        record.setPromptText((String) ctx.get("finalPrompt"));
        record.setUserInputText((String) ctx.get("userInputText"));
        record.setFileUrl(ossUrl);
        if (ctx.get("durationSeconds") instanceof Number n) { record.setVideoDuration(n.longValue()); }
        record.setGenParams((String) ctx.get("genParams"));
        record.setBizSeq(bizSeq); // 幂等唯一键：由唯一索引 uk_gen_record_biz_seq 兜底防重复落库
        record.setStatus(1);
        boolean overwriteExistingFinal = resolveOverwriteExistingFinal(ctx);
        record.setIsSelected(overwriteExistingFinal ? SELECTED_YES : SELECTED_NO);
        record.setDelFlag(DEL_FLAG_NORMAL);
        record.setCreateTime(DateUtils.getNowDate());
        record.setCreateBy(String.valueOf(mt.getUserId()));
        record.setCostCredits(resolveSettledVideoCost(bizSeq, mt.getUserId(), ossUrl));
        try
        {
            aidGenRecordService.save(record);
        }
        catch (DuplicateKeyException dup)
        {
            // DIRECT 内联与事件扇入并发竞态：唯一键冲突说明同一 take 已落库，幂等忽略
            log.info("分镜出片事件落库 gen_record 已存在(biz_seq 唯一键冲突,幂等忽略): bizSeq={}, storyboardId={}",
                    bizSeq, storyboardId);
            markExistingVideoRecordAsFinal(bizSeq, mt.getUserId(), overwriteExistingFinal);
            return true;
        }
        markStoryboardFinalVideo(storyboardId, record.getId(), mt.getUserId(), overwriteExistingFinal);
        return true;
    }

    /**
     * 自动生成成功后同步分镜主视频：批量生成允许覆盖，单个生成仅在尚无主视频时设置。
     * 真正切换主视频时维持 video 类内单选互斥，并失效基于旧原视频生成的 compose 主记录。
     */
    private void markStoryboardFinalVideo(Long storyboardId, Long recordId, Long userId,
            boolean overwriteExistingFinal)
    {
        if (Objects.isNull(storyboardId) || Objects.isNull(recordId) || Objects.isNull(userId))
        {
            return;
        }
        try
        {
            // 查询字段精简：派生 compose 是否失效只取当前 final_video_id。
            AidStoryboard currentStoryboard = aidStoryboardService.getOne(
                    Wrappers.<AidStoryboard>lambdaQuery()
                            .select(AidStoryboard::getId, AidStoryboard::getFinalVideoId)
                            .eq(AidStoryboard::getId, storyboardId)
                            .eq(AidStoryboard::getUserId, userId)
                            .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                            .last("LIMIT 1"));
            if (Objects.isNull(currentStoryboard))
            {
                log.warn("分镜视频自动设为主视频失败(分镜不存在或无权): storyboardId={}, recordId={}, userId={}",
                        storyboardId, recordId, userId);
                return;
            }
            if (!overwriteExistingFinal
                    && Objects.nonNull(currentStoryboard.getFinalVideoId())
                    && !Objects.equals(currentStoryboard.getFinalVideoId(), recordId))
            {
                log.info("单个分镜视频生成保留已有主视频: storyboardId={}, finalVideoId={}, recordId={}",
                        storyboardId, currentStoryboard.getFinalVideoId(), recordId);
                return;
            }

            // 单个生成仅允许抢占空指针；条件更新避免任务执行期间用户手动选片被异步结果覆盖。
            if (!overwriteExistingFinal && Objects.isNull(currentStoryboard.getFinalVideoId()))
            {
                LambdaUpdateWrapper<AidStoryboard> reserveFinal = Wrappers.lambdaUpdate();
                reserveFinal.eq(AidStoryboard::getId, storyboardId);
                reserveFinal.eq(AidStoryboard::getUserId, userId);
                reserveFinal.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
                reserveFinal.isNull(AidStoryboard::getFinalVideoId);
                reserveFinal.set(AidStoryboard::getFinalVideoId, recordId);
                reserveFinal.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
                reserveFinal.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
                if (!aidStoryboardService.update(reserveFinal))
                {
                    log.info("单个分镜视频生成保留并发选定的主视频: storyboardId={}, recordId={}, userId={}",
                            storyboardId, recordId, userId);
                    return;
                }
            }
            boolean originalSourceChanged = !Objects.equals(currentStoryboard.getFinalVideoId(), recordId);
            // video 类内单选：重置同分镜其余分镜视频记录的选中。
            LambdaUpdateWrapper<AidGenRecord> resetWrapper = Wrappers.lambdaUpdate();
            resetWrapper.eq(AidGenRecord::getStoryboardId, storyboardId);
            resetWrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
            resetWrapper.in(AidGenRecord::getGenType, VIDEO_MUTEX_GEN_TYPES);
            resetWrapper.ne(AidGenRecord::getId, recordId);
            resetWrapper.eq(AidGenRecord::getIsSelected, SELECTED_YES);
            resetWrapper.set(AidGenRecord::getIsSelected, SELECTED_NO);
            resetWrapper.set(AidGenRecord::getUpdateBy, String.valueOf(userId));
            resetWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
            aidGenRecordService.update(resetWrapper);
            // compose 成片依赖原视频；新原视频成为主视频后，旧配音成片不能继续被时间轴和导出使用。
            if (originalSourceChanged) {
                LambdaUpdateWrapper<AidGenRecord> invalidateComposeWrapper = Wrappers.lambdaUpdate();
                invalidateComposeWrapper.eq(AidGenRecord::getStoryboardId, storyboardId);
                invalidateComposeWrapper.eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL);
                invalidateComposeWrapper.eq(AidGenRecord::getGenType, GenTypeEnum.COMPOSE.getValue());
                invalidateComposeWrapper.eq(AidGenRecord::getIsSelected, SELECTED_YES);
                invalidateComposeWrapper.set(AidGenRecord::getIsSelected, SELECTED_NO);
                invalidateComposeWrapper.set(AidGenRecord::getUpdateBy, String.valueOf(userId));
                invalidateComposeWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
                aidGenRecordService.update(invalidateComposeWrapper);
            }
            // 本记录置选中（批量 insert 已带 1；单个空主视频或幂等重放路径在此补齐）。
            LambdaUpdateWrapper<AidGenRecord> selectWrapper = Wrappers.lambdaUpdate();
            selectWrapper.eq(AidGenRecord::getId, recordId);
            selectWrapper.set(AidGenRecord::getIsSelected, SELECTED_YES);
            selectWrapper.set(AidGenRecord::getUpdateBy, String.valueOf(userId));
            selectWrapper.set(AidGenRecord::getUpdateTime, DateUtils.getNowDate());
            aidGenRecordService.update(selectWrapper);

            if (overwriteExistingFinal)
            {
                LambdaUpdateWrapper<AidStoryboard> update = Wrappers.lambdaUpdate();
                update.eq(AidStoryboard::getId, storyboardId);
                update.eq(AidStoryboard::getUserId, userId);
                update.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
                update.set(AidStoryboard::getFinalVideoId, recordId);
                update.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
                update.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
                boolean updated = aidStoryboardService.update(update);
                if (!updated)
                {
                    log.warn("分镜视频自动设为主视频失败(分镜不存在或无权): storyboardId={}, recordId={}, userId={}",
                            storyboardId, recordId, userId);
                }
            }
        }
        catch (Exception e)
        {
            log.warn("分镜视频自动设为主视频异常(不阻断): storyboardId={}, recordId={}", storyboardId, recordId, e);
        }
    }

    /** 新任务显式写入；老任务上下文缺字段时保持历史自动覆盖语义。 */
    private boolean resolveOverwriteExistingFinal(Map<String, Object> ctx)
    {
        Object value = ctx.get("overwriteExistingFinal");
        return !(value instanceof Boolean flag) || flag;
    }

    /** 解析 request_json → options.sbzVideoGenCtx（Map）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractCtxFromMediaTask(AidMediaTask mt)
    {
        if (Objects.isNull(mt) || StrUtil.isBlank(mt.getRequestJson())) { return java.util.Collections.emptyMap(); }
        try
        {
            JsonNode opt = OBJECT_MAPPER.readTree(mt.getRequestJson()).path("options").path(OPT_KEY_CTX);
            if (opt.isMissingNode() || opt.isNull()) { return java.util.Collections.emptyMap(); }
            return OBJECT_MAPPER.convertValue(opt, Map.class);
        }
        catch (Exception e)
        {
            log.warn("分镜出片解析媒体任务上下文失败: mediaTaskId={}", mt.getId(), e);
            return java.util.Collections.emptyMap();
        }
    }

    /** 扇入收尾（幂等）：成功 gen_record 数 + 失败计数 >= 总数时 CAS 一次性置终态 + SSE + 释放锁/名额。 */
    @Override
    public void finalizeVideoBatchIfDone(Long taskId)
    {
        if (Objects.isNull(taskId)) { return; }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || !TASK_STATUS_PROCESSING.equals(task.getStatus())) { return; }
        int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
        List<Long> storyboardIds = fanInSupport.parseBatchStoryboardIds(task.getInputSnapshot());
        List<AidGenRecord> succ = loadSucceededRecordsByParentTask(taskId, storyboardIds);
        int successCount = succ.size();
        int failCount = fanInSupport.getFailCount(taskId);
        int processed = successCount + failCount;
        if (processed < total)
        {
            // 未凑齐终态：推一次「已处理 X/N」扇入进度，异步等待期前端不再长时间无事件
            pushBatchProgress(taskId, total, processed, successCount, failCount);
            return;
        }
        if (!fanInSupport.tryWinFinalize(taskId)) { return; }
        try
        {
            Map<String, Object> resultMap = buildFinalizeResultMap(task, succ, total, successCount, failCount);
            String resultJson;
            try { resultJson = OBJECT_MAPPER.writeValueAsString(resultMap); }
            catch (Exception e) { resultJson = null; }
            if (assetExtractService.isTaskCancelled(taskId))
            {
                if (updateTaskCancelledWithResult(taskId, resultJson))
                {
                    sseManager.sendCancelled(taskId, "用户取消");
                }
                log.info("分镜批量出片收尾检测到取消，结果已保留: taskId={}, total={}, success={}, fail={}",
                        taskId, total, successCount, failCount);
                return;
            }

            if (successCount == 0)
            {
                TaskErrorResult errorResult =
                        fanInSupport.resolveRepresentativeFailure(taskId, BIZ_TASK_TYPE, task.getModelCode());
                if (updateTaskFailed(taskId, errorResult, resultJson))
                {
                    sseManager.sendError(taskId, errorResult);
                }
            }
            else if (failCount > 0)
            {
                if (updateTaskPartialFailed(taskId, total, resultJson)) { sseManager.sendPartialFailed(taskId, resultMap, "部分完成"); }
            }
            else
            {
                if (updateTaskSuccess(taskId, total, resultJson)) { sseManager.sendComplete(taskId, resultMap); }
            }
            log.info("分镜批量出片收尾: taskId={}, total={}, success={}, fail={}", taskId, total, successCount, failCount);
        }
        finally
        {
            releaseShotLocksFromSnapshot(task);
            try { assetExtractService.clearCancelFlag(taskId, task.getBillingTraceId()); } catch (Exception ignore) { /* ignore */ }
            try { assetExtractService.releaseTaskSlots(taskId, task.getBillingTraceId()); } catch (Exception ignore) { /* ignore */ }
            fanInSupport.cleanup(taskId);
        }
    }

    /** 释放父任务 input_snapshot.shots[].lockToken 持有的镜头锁（复用通用快照解析）。 */
    private void releaseShotLocksFromSnapshot(AidExtractTask task)
    {
        if (Objects.isNull(task)) { return; }
        for (MediaGenFanInSupport.ShotLockRef ref : fanInSupport.parseShotLocks(task.getInputSnapshot()))
        {
            releaseLockIfMine(FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + ref.storyboardId, ref.lockToken);
        }
    }

    /** 收尾结果 resultData：按已成功 gen_record 聚合 items/shots（video）。 */
    private Map<String, Object> buildFinalizeResultMap(AidExtractTask task, List<AidGenRecord> succ,
            int total, int successCount, int failCount)
    {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Long> recordIds = new ArrayList<>();
        Map<Long, List<Long>> byShot = new LinkedHashMap<>();
        for (AidGenRecord r : succ)
        {
            recordIds.add(r.getId());
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("storyboardId", r.getStoryboardId());
            it.put("recordId", r.getId());
            it.put("videoUrl", r.getFileUrl());
            items.add(it);
            byShot.computeIfAbsent(r.getStoryboardId(), k -> new ArrayList<>()).add(r.getId());
        }
        List<Map<String, Object>> shots = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : byShot.entrySet())
        {
            Map<String, Object> sr = new LinkedHashMap<>();
            sr.put("storyboardId", e.getKey());
            sr.put("successCount", e.getValue().size());
            sr.put("recordIds", e.getValue());
            shots.add(sr);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("modelCode", task.getModelCode());
        map.put("totalSubtasks", total);
        map.put("successCount", successCount);
        map.put("failCount", failCount);
        map.put("failedItems", fanInSupport.resolveFailureItems(task, BIZ_TASK_TYPE));
        map.put("recordIds", recordIds);
        map.put("items", items);
        map.put("shots", shots);
        return map;
    }

    /**
     * 续生反查：按 bizSeq 父任务编码段 + storyboardId 取本父任务已成功落库的视频记录。
     */
    private List<AidGenRecord> loadSucceededRecordsByParentTask(Long taskId, java.util.Collection<Long> storyboardIds)
    {
        if (Objects.isNull(taskId) || CollectionUtil.isEmpty(storyboardIds))
        {
            return new ArrayList<>();
        }
        try
        {
            long lowerBizSeq = Math.multiplyExact(taskId, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
            long upperBizSeq = Math.addExact(lowerBizSeq, MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
            return aidGenRecordService.list(
                    Wrappers.<AidGenRecord>lambdaQuery()
                            .select(AidGenRecord::getId, AidGenRecord::getStoryboardId,
                                    AidGenRecord::getFileUrl, AidGenRecord::getGenParams, AidGenRecord::getCreateTime)
                            .in(AidGenRecord::getStoryboardId, storyboardIds)
                            .eq(AidGenRecord::getStatus, 1)
                            .eq(AidGenRecord::getDelFlag, DEL_FLAG_NORMAL)
                            .ge(AidGenRecord::getBizSeq, lowerBizSeq)
                            .lt(AidGenRecord::getBizSeq, upperBizSeq)
                            .orderByAsc(AidGenRecord::getBizSeq));
        }
        catch (Exception e)
        {
            // fail-closed：反查是防重复扣费真源，一次 SQL/连接异常若被当"无成功记录"会触发重复补跑/扣费，
            // 因此宁可本次续生失败也不返回空列表
            log.error("分镜批量出片续生反查 aid_gen_record 失败(fail-closed): taskId={}", taskId, e);
            throw new ServiceException("任务数据异常");
        }
    }

    /** 从 aid_gen_record.gen_params 解析逻辑 take 槽位（takeIndex）；无法解析返回 null（调用方按贪心兜底分配）。 */
    private Integer parseTakeIndexFromGenParams(String genParams)
    {
        if (StrUtil.isBlank(genParams)) { return null; }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(genParams);
            JsonNode ti = node.get("takeIndex");
            if (ti != null && ti.isInt()) { return ti.asInt(); }
            if (ti != null && ti.canConvertToInt()) { return ti.asInt(); }
            return null;
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片续生解析 takeIndex 失败: {}", e.getMessage());
            return null;
        }
    }
    /** 解析批量分镜出片宽高比。 */
    private String resolveAspectRatioForBatch(String requestValue, List<Long> storyboardIds,
            Long userId, AiModelConfigVo modelConfig)
    {
        if (StrUtil.isNotBlank(requestValue))
        {
            return resolveAspectRatio(requestValue, modelConfig);
        }
        String projectValue = resolveProjectAspectRatioForBatch(storyboardIds, userId);
        return ModelCapabilityResolver.resolveVideoAspectRatioForProjectDefault(modelConfig, projectValue);
    }

    /** 读取批量中首个可访问分镜所属项目的画面比例。 */
    private String resolveProjectAspectRatioForBatch(List<Long> storyboardIds, Long userId)
    {
        if (CollectionUtil.isEmpty(storyboardIds))
        {
            return null;
        }
        for (Long storyboardId : storyboardIds)
        {
            AidStoryboard storyboard;
            try
            {
                storyboard = loadAndCheckStoryboard(storyboardId, userId);
            }
            catch (ServiceException ignore)
            {
                // 与批量受理一致：不可访问的镜头不参与项目默认值解析，继续尝试下一项。
                continue;
            }
            Long projectId = storyboard.getProjectId();
            if (Objects.isNull(projectId) || projectId <= 0)
            {
                return null;
            }
            AidComicProject project = aidComicProjectService.getOne(
                    Wrappers.<AidComicProject>lambdaQuery()
                            .select(AidComicProject::getId, AidComicProject::getAspectRatio)
                            .eq(AidComicProject::getId, projectId)
                            .eq(AidComicProject::getUserId, userId)
                            .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"), false);
            return Objects.isNull(project) ? null : StrUtil.trimToNull(project.getAspectRatio());
        }
        return null;
    }

    /** 解析视频出片宽高比。 */
    private String resolveAspectRatio(String userValue, AiModelConfigVo modelConfig)
    {
        return ModelCapabilityResolver.resolveVideoAspectRatio(modelConfig, userValue);
    }

    /**
     * 解析最终下发的清晰度档位：与 {@link #resolveAspectRatio} 同语义，白名单来自
     * {@code capability_json.sizeOptions}，返回配置中的规范写法，保证计费 SKU 匹配与厂商下发同源。
     *
     * @param userValue   用户请求档位（可空）
     * @param modelConfig 出片模型配置
     * @return 规范化档位；模型未声明任何档位且用户未传时返回 null（不下发，走上游默认）
     */
    private String resolveResolution(String userValue, AiModelConfigVo modelConfig)
    {
        return ModelCapabilityResolver.resolveSize(modelConfig, userValue);
    }

    /**
     * 音画同出取值：显式传入优先；supportsAudio=true 且未声明 defaultAudio 时兼容默认开启，
     * 明确 defaultAudio=false 时默认无声。
     *
     * @param modelConfig   出片模型配置
     * @param generateAudio 请求音画开关（可空）
     * @return 归一后的开关值；不支持且未传时仍为 null
     */
    private Boolean resolveGenerateAudioOrDefault(AiModelConfigVo modelConfig, Boolean generateAudio)
    {
        if (Objects.nonNull(generateAudio))
        {
            return generateAudio;
        }
        JsonNode flag = readField(modelConfig == null ? null : modelConfig.getCapabilityJson(), "supportsAudio");
        if (flag != null && flag.asBoolean(false))
        {
            JsonNode configuredDefault = readField(
                    modelConfig == null ? null : modelConfig.getCapabilityJson(), "defaultAudio");
            return configuredDefault != null && configuredDefault.isBoolean()
                    ? configuredDefault.asBoolean() : Boolean.TRUE;
        }
        return null;
    }

    /**
     * 音画同出前置校验：仅当 capability.supportsAudio=true 时允许 generateAudio=true。
     *
     * @param modelConfig   出片模型配置
     * @param generateAudio 请求音画开关（可空=不下发）
     */
    private void assertGenerateAudioAllowed(AiModelConfigVo modelConfig, Boolean generateAudio)
    {
        if (!Boolean.TRUE.equals(generateAudio))
        {
            return;
        }
        JsonNode flag = readField(modelConfig == null ? null : modelConfig.getCapabilityJson(), "supportsAudio");
        boolean supports = flag != null && flag.asBoolean(false);
        if (!supports)
        {
            log.info("分镜出片模型不支持音画同出: modelCode={}, generateAudio={}",
                    modelConfig == null ? null : modelConfig.getModelCode(), generateAudio);
            throw new ServiceException("模型不支持音频");
        }
    }

    private JsonNode readField(String capabilityJson, String key)
    {
        if (StrUtil.isBlank(capabilityJson))
        {
            return null;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(capabilityJson);
            return root.get(key);
        }
        catch (Exception e)
        {
            log.warn("分镜图生视频 capability_json 解析失败, key={}, err={}", key, e.getMessage());
            return null;
        }
    }
    /** 扇入等待阶段 SSE 进度：已处理 X/N（进度映射 30→99 区间，接在提交阶段 5→30 之后单调递增）。 */
    private void pushBatchProgress(Long taskId, int total, int processed, int successCount, int failCount)
    {
        if (Objects.isNull(taskId) || total <= 0)
        {
            return;
        }
        int progress = PROGRESS_FANIN_BASE + (int) Math.floor(processed * (double) PROGRESS_FANIN_SPAN / total);
        if (progress > PROGRESS_CAP)
        {
            progress = PROGRESS_CAP;
        }
        String progressText = processed + "/" + total;
        String stepId = "video_gen_" + processed + "_of_" + total;
        String stepTitle = "已处理 " + progressText;

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("taskType", TASK_TYPE_STORYBOARD_VIDEO_GENERATE);
        extras.put("status", TASK_STATUS_PROCESSING);
        extras.put("processedCount", processed);
        extras.put("successCount", successCount);
        extras.put("failCount", failCount);
        extras.put("totalCount", total);
        extras.put("progressText", progressText);
        extras.put("currentCount", processed);

        try
        {
            sseManager.sendStepProgressWithData(taskId, "video_gen", progress,
                    stepId, stepTitle, processed, total, extras);
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片 progress 推送异常: taskId={}, {}/{}", taskId, processed, total);
        }
    }

    /** 提交阶段 SSE 进度：已提交 X/N（进度映射 5→30 区间，替换排队 admitted 快照，避免前端长时间卡在 5%）。 */
    private void pushSubmitProgress(Long taskId, int total, int submitted)
    {
        if (Objects.isNull(taskId) || total <= 0)
        {
            return;
        }
        int progress = PROGRESS_SUBMIT_BASE + (int) Math.floor(submitted * (double) PROGRESS_SUBMIT_SPAN / total);
        String progressText = submitted + "/" + total;
        String stepId = "video_submit_" + submitted + "_of_" + total;
        String stepTitle = "已提交 " + progressText;

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("taskType", TASK_TYPE_STORYBOARD_VIDEO_GENERATE);
        extras.put("status", TASK_STATUS_PROCESSING);
        extras.put("submittedCount", submitted);
        extras.put("totalCount", total);
        extras.put("progressText", progressText);

        try
        {
            sseManager.sendStepProgressWithData(taskId, "video_gen", progress,
                    stepId, stepTitle, submitted, total, extras);
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片提交进度推送异常: taskId={}, {}/{}", taskId, submitted, total);
        }
    }

    /**
     * DB 兜底：检查指定 storyboardId 是否真的有未结束（PENDING / PROCESSING）的
     * storyboard_video_generate 任务。只检查 input_snapshot.storyboardIds 数组，避免
     * referenceAudioIds 等其他数组中相同的数字误判为分镜正在处理。
     */
    private boolean hasActiveTaskInDb(Long storyboardId)
    {
        if (Objects.isNull(storyboardId))
        {
            return false;
        }
        LambdaQueryWrapper<AidExtractTask> w = Wrappers.lambdaQuery();
        w.eq(AidExtractTask::getTaskType, TASK_TYPE_STORYBOARD_VIDEO_GENERATE)
                .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                .apply("JSON_CONTAINS(IF(JSON_VALID(input_snapshot), input_snapshot, '{}'), "
                        + "CAST({0} AS CHAR), '$.storyboardIds') = 1", storyboardId);
        Long cnt = extractTaskService.getBaseMapper().selectCount(w);
        return Objects.nonNull(cnt) && cnt > 0;
    }
    private boolean updateTaskStatus(Long taskId, String newStatus, String errorMessage, String expectedStatus)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, expectedStatus);
        update.set(AidExtractTask::getStatus, newStatus);
        if (StrUtil.isNotBlank(errorMessage))
        {
            update.set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(errorMessage));
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        return rows > 0;
    }

    private boolean updateTaskSuccess(Long taskId, int totalCount, String resultJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_SUCCEEDED);
        update.set(AidExtractTask::getTotalCount, totalCount);
        // 成功终态清空 errorMessage，避免续生回滚等写入的旧错误残留
        update.set(AidExtractTask::getErrorMessage, null)
                .set(AidExtractTask::getErrorDetailJson, null);
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜图生视频成功CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
        releaseWorkflowSlot(taskId);
        return true;
    }

    /** PROCESSING → PARTIAL_FAILED（部分镜头/条成功 + 至少一条失败，支持续生）。 */
    private boolean updateTaskPartialFailed(Long taskId, int totalCount, String resultJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED);
        update.set(AidExtractTask::getTotalCount, totalCount);
        // 清空旧 errorMessage（失败明细在 resultData.failedItems），避免续生回滚写入的旧文案残留
        update.set(AidExtractTask::getErrorMessage, null)
                .set(AidExtractTask::getErrorDetailJson, null);
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜批量出片部分失败CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
        releaseWorkflowSlot(taskId);
        return true;
    }

    /** 续生提交失败回滚：PENDING→原状态 + 恢复 totalCount + 恢复 inputSnapshot + CAS 释放本轮锁；保留原 resultData 与续生入口。 */
    private void releaseLocksAndRollbackResume(Long taskId, List<ShotLock> heldLocks, Integer originalTotalCount,
            String originalStatus, String originalInputSnapshot)
    {
        try
        {
            LambdaUpdateWrapper<AidExtractTask> u = Wrappers.lambdaUpdate();
            u.eq(AidExtractTask::getId, taskId);
            u.eq(AidExtractTask::getStatus, TASK_STATUS_PENDING);
            u.set(AidExtractTask::getStatus, StrUtil.blankToDefault(originalStatus, TASK_STATUS_PARTIAL_FAILED));
            if (Objects.nonNull(originalTotalCount))
            {
                u.set(AidExtractTask::getTotalCount, originalTotalCount);
            }
            // 恢复续生前的 inputSnapshot（含旧 runNo / 旧 token），避免入队失败也白白消耗 runNo / 残留新 token
            u.set(AidExtractTask::getInputSnapshot, originalInputSnapshot);
            u.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.getBaseMapper().update(null, u);
        }
        catch (Exception e)
        {
            log.warn("分镜批量出片续生回滚原状态失败: taskId={}", taskId, e);
        }
        for (ShotLock l : heldLocks) { releaseLockIfMine(l.key, l.token); }
    }

    private boolean updateTaskFailed(Long taskId, String errorMessage)
    {
        return updateTaskFailed(taskId, ErrorNormalizer.normalizeByMessage(errorMessage));
    }

    private boolean updateTaskFailed(Long taskId, TaskErrorResult errorResult)
    {
        return updateTaskFailed(taskId, errorResult, null);
    }

    private boolean updateTaskFailed(Long taskId, TaskErrorResult errorResult, String resultJson)
    {
        String safeMsg = StrUtil.blankToDefault(errorResult.getRawMessage(), errorResult.getUserMessage());
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
        update.set(AidExtractTask::getErrorMessage, StrUtil.sub(MediaTaskPayloadSanitizer.sanitizeForStorage(safeMsg), 0, 255))
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.write(errorResult));
        if (StrUtil.isNotBlank(resultJson)) { update.set(AidExtractTask::getResultData, resultJson); }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜图生视频失败CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        wechatNotifyService.notifyTaskTerminal(taskId);
        releaseWorkflowSlot(taskId);
        return true;
    }

    private boolean updateTaskCancelled(Long taskId)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_CANCELLED);
        update.set(AidExtractTask::getErrorMessage, "用户取消")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("用户取消"));
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜图生视频取消CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        releaseWorkflowSlot(taskId);
        return true;
    }

    private boolean updateTaskCancelledWithResult(Long taskId, String resultJson)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(AidExtractTask::getStatus, TASK_STATUS_CANCELLED);
        update.set(AidExtractTask::getErrorMessage, "用户取消")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("用户取消"));
        if (StrUtil.isNotBlank(resultJson))
        {
            update.set(AidExtractTask::getResultData, resultJson);
        }
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("分镜图生视频取消(保留结果)CAS未命中, 终态已被其他分支接管: taskId={}", taskId);
            return false;
        }
        releaseWorkflowSlot(taskId);
        return true;
    }

    private void releaseWorkflowSlot(Long taskId)
    {
        batchTaskSlotService.releaseForTask(extractTaskService.selectAidExtractTaskById(taskId));
    }
}
