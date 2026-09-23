package com.aid.media.service.impl;

import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorPresentation;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.media.service.IMediaBillingService;
import com.aid.media.service.MediaTaskArchiveService;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingFacadeService;
import com.aid.billing.service.IAccountUpdateService;
import com.aid.billing.util.BillingInputExtractor;
import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.RefundStatusMapper;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskSuccessValidator;
import com.aid.common.constant.HttpConstants;
import com.aid.common.exception.ServiceException;
import com.aid.common.aid.redis.utils.RedisUtils;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.oss.entity.UploadResult;
import com.aid.common.oss.factory.OssFactory;
import com.aid.common.satoken.utils.LoginHelper;
import com.aid.common.utils.spring.SpringUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.constants.ConfigurableAsyncMediaConstants;
import com.aid.media.constants.DashscopeConstants;
import com.aid.media.constants.KlingConstants;
import com.aid.media.constants.MinimaxH3Constants;
import com.aid.media.constants.OpenAiCompatibleConstants;
import com.aid.media.constants.ViduConstants;
import com.aid.media.constants.VolcengineConstants;
import com.aid.media.service.MediaConcurrencyLimiter;
import com.aid.media.dto.MediaBatchGenerateRequest;
import com.aid.media.dto.MediaBatchGenerateResponse;
import com.aid.media.dto.MediaBatchProgressRequest;
import com.aid.media.dto.MediaBatchProgressResponse;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskListItem;
import com.aid.media.dto.MediaTaskListRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.PreparedMediaBillingInput;
import com.aid.media.enums.MediaBillingStatus;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.enums.MediaType;
import com.aid.media.eta.MediaEtaRecorder;
import com.aid.media.eta.MediaEtaService;
import com.aid.media.provider.ImageProviderClient;
import com.aid.media.provider.ImageOutputItem;
import com.aid.media.provider.KlingVideoRequestBuilder;
import com.aid.media.provider.MinimaxH3VideoRequestBuilder;
import com.aid.media.provider.DmcH3VideoRequestBuilder;
import com.aid.media.provider.impl.DmcH3VideoProviderClient.SubmissionOutcomeUnknownException;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderErrorSanitizer;
import com.aid.media.provider.ProviderUsageSupport;
import com.aid.media.provider.ReasoningContentSanitizer;
import com.aid.media.provider.ProviderTaskResult;
import com.aid.media.provider.TextProviderClient;
import com.aid.media.provider.TextFailureBillingPolicy;
import com.aid.media.provider.TextGenerationControl;
import com.aid.media.provider.TextModelCapabilityValidator;
import com.aid.media.provider.TextOutputLimitResolver;
import com.aid.media.provider.TextStreamCallbacks;
import com.aid.media.provider.VideoProviderClient;
import com.aid.media.provider.impl.ConfigurableAsyncVideoProviderClient;
import com.aid.media.provider.impl.MpsVideoProviderClient;
import com.aid.media.provider.impl.VolcengineVideoProviderClient;
import com.aid.media.provider.impl.Wan3VideoRequestBuilder;
import com.aid.media.provider.impl.AgnesVideo25RequestBuilder;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaBillingQuotePreparer;
import com.aid.media.service.MediaTextStreamSink;
import com.aid.media.service.TaskDispatchService;
import com.aid.media.service.TaskCompletionService;
import com.aid.media.service.VideoInputAspectRatioNormalizer;
import com.aid.media.event.MediaTaskCompletedEvent;
import com.aid.media.event.MediaTaskOssPersistedEvent;
import com.aid.media.util.MediaTaskPayloadSanitizer;
import com.aid.media.util.ModelCapabilityValidator;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.media.util.ModelInputCapabilityValidator;
import com.aid.media.util.ReferenceMediaRequestNormalizer;
import com.aid.media.util.VideoDurationProber;
import com.aid.model.definition.ModelParameterValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.impl.TextTaskExecutionRejectedException;
import com.aid.service.IAiModelConfigService;
import com.aid.service.IGenResultCallbackService;
import com.aid.tokendance.security.TokenDancePublicMediaDownloader;
import com.aid.tokendance.provider.common.TokenDanceResponseMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenerationServiceImpl implements IMediaGenerationService, MediaBillingQuotePreparer {

    private final Map<Long, TextStreamCancellationControl> activeTextStreams = new ConcurrentHashMap<>();

    // 默认图片模型名称：与首期默认协议一致，可在 aid_ai_model 覆盖。
    private static final String DEFAULT_IMAGE_MODEL = DashscopeConstants.PROTOCOL_IMAGE;
    // 默认视频模型名称：与首期默认协议一致。
    private static final String DEFAULT_VIDEO_MODEL = ViduConstants.PROTOCOL_VIDEO;
    // 默认文本模型名占位：需在 aid_ai_model 配置 model_type=text 的记录时可覆盖。
    private static final String DEFAULT_TEXT_MODEL = OpenAiCompatibleConstants.DEFAULT_TEXT_MODEL;
    // 默认文本协议：所有 OpenAI 兼容厂商（OpenAI/方舟/百炼/DeepSeek/Kimi/智谱/Grok 等）统一走该协议。
    private static final String DEFAULT_TEXT_PROTOCOL = OpenAiCompatibleConstants.PROTOCOL_TEXT;
    // 流式 SSE 审计快照写入 aid_media_task.response_json 时的最大字符数，防止超大字段。
    private static final int TEXT_STREAM_RAW_MAX_CHARS = 100_000;
    private static final int MEDIA_TASK_PROMPT_SUMMARY_CODE_POINTS = 4_000;
    private static final String TEXT_CANCEL_CHANNEL = "aid:media:text:cancel";
    private static final long TEXT_CANCEL_PERSISTENCE_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    // 补偿轮询最小扫描间隔（秒）：避免刚被前端轮询的任务立刻被补偿任务重复查询。
    private static final int COMPENSATION_MIN_SCAN_GAP_SECONDS = 5;
    // 补偿轮询默认批量：单次最多处理 50 条，避免长事务或瞬时压力峰值。
    private static final int DEFAULT_COMPENSATION_BATCH_SIZE = 50;
    // OSS 持久化补偿最小就绪间隔（秒）：任务成功后至少等待 60 秒再进补偿队列，
    // 避免同步成功路径刚把任务标成 SUCCEEDED、persistOssIfNeeded 还没跑完就被抢。
    private static final int OSS_COMPENSATION_READY_GAP_SECONDS = 60;
    // provider 远程调用慢调用 WARN 阈值（毫秒）：超过则单独 WARN，便于一眼定位 390s 这类慢上游。
    private static final long SLOW_SUBMIT_WARN_MS = 60_000L;
    private static final String STORYBOARD_VIDEO_GENERATE_BIZ_TYPE = "storyboard_video_generate";
    /** 上游产物下载最多允许的受控重定向次数。 */
    private static final int ORIGIN_DOWNLOAD_MAX_REDIRECTS = 3;
    // 秒→毫秒换算：产物时长在任务表按秒存储，解析结果需向上取整到秒。
    private static final int MILLIS_PER_SECOND = 1000;
    // 正式 TTS 任务允许的音频格式；业务入口和统一任务入口双重校验，禁止非法值进入预冻结。
    private static final Set<String> AUDIO_FORMATS = Set.of("mp3", "wav", "pcm", "ogg_opus", "opus", "flac");
    // 豆包与 MiniMax 当前支持采样率的并集；具体模型不支持时仍由 Provider 返回明确失败并统一退款。
    private static final Set<Integer> AUDIO_SAMPLE_RATES = Set.of(8000, 16000, 22050, 24000, 32000, 44100, 48000);
    // 业务含义：单次批量提交媒体任务条数上限，与接口约定一致，避免一次请求压垮上游或长事务。
    // 可通过 aid.chat.media.batch.max-size 配置，默认 20。
    @Value("${aid.chat.media.batch.max-size:20}")
    private int maxBatchMediaSize;

    /**
     * 旧补偿轮询的退避增长计数上限；达到后保持最大间隔继续查询，不参与任务判死。
     */
    @Value("${aid.chat.media.compensation.max-retry:120}")
    private int maxCompensationRetry;

    // 媒体任务表 Mapper：负责读写 aid_media_task。
    private final AidMediaTaskMapper aidMediaTaskMapper;
    // 媒体结果表 Mapper：负责读写 aid_media_result。
    private final AidMediaResultMapper aidMediaResultMapper;
    // 模型配置服务：从 aid_ai_model + aid_ai_provider + aid_user_ai_config 获取模型配置。
    private final IAiModelConfigService aiModelConfigService;
    private final com.aid.model.definition.ModelBusinessBindingService modelBusinessBindings;
    // 三阶段计费服务：预冻结 → 执行 → 结算/退回。
    private final IMediaBillingService mediaBillingService;
    // 计费门面服务：SKU规则解析+金额计算，委托mediaBillingService做账户操作。
    private final BillingFacadeService billingFacadeService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelInvocationResolver invocationResolver;
    // 统一账户变更执行器：流式场景事务回滚后任务行不存在时，直接退回账户。
    private final IAccountUpdateService accountUpdateService;
    // 图片 provider 列表：由 Spring 自动注入全部实现。
    private final List<ImageProviderClient> imageProviderClients;
    // 视频 provider 列表：由 Spring 自动注入全部实现。
    private final List<VideoProviderClient> videoProviderClients;
    // 文本 provider 列表：由 Spring 自动注入全部实现。
    private final List<TextProviderClient> textProviderClients;
    // 音频（TTS）provider 列表：由 Spring 自动注入全部实现。
    private final List<com.aid.media.provider.AudioProviderClient> audioProviderClients;
    // 通用线程池：承载文本 SSE 后台读流；项目内仅此一个 ThreadPoolTaskExecutor Bean，无需 @Qualifier。
    private final ThreadPoolTaskExecutor threadPoolTaskExecutor;
    // 编程式事务：流式场景短事务插入/扣费与结束落库分开提交。
    private final TransactionTemplate transactionTemplate;
    // REQUIRES_NEW 短事务模板（@PostConstruct 初始化）：建任务 + 预冻结独立提交，
    // 即使上层调用方持有事务，任务行也能立即提交、对外可见，submit 始终在该短事务提交之后执行。
    private TransactionTemplate requiresNewTxTemplate;
    // 生成结果回填：资产表 / 抽卡记录表（可选，仅当任务上带有 callback 字段且已成功拿到 URL）。
    private final IGenResultCallbackService genResultCallbackService;
    // 媒体并发限流服务：基于 Redis 原子计数实现用户级 + 系统级两级并发限制。
    private final MediaConcurrencyLimiter concurrencyLimiter;
    // 统一任务调度中心：异步任务提交后由调度中心按策略驱动轮询。
    private final TaskDispatchService taskDispatchService;
    // 统一终态 CAS：前端刷新与补偿轮询也必须经同一入口，避免重复结算和重复归档。
    private final TaskCompletionService taskCompletionService;
    // 事件发布器：OSS 持久化成功后发布 MediaTaskOssPersistedEvent，触发业务侧回填 OSS URL。
    private final ApplicationEventPublisher applicationEventPublisher;
    // 合成终态收口（COMPOSE 分支）：提交失败时走 ComposeBillingService 退款，不经 BillingFacadeService。
    private final com.aid.compose.service.ComposeCompletionService composeCompletionService;
    // 终态请求/响应归档与数据库载荷压缩；文件失败不影响媒体任务主链路。
    private final MediaTaskArchiveService mediaTaskArchiveService;
    // 模型健康采集：同步直出/提交被拒在本类收口（异步任务终态在 TaskCompletionService 收口），内部吞异常。
    private final com.aid.modelhealth.service.ModelHealthRecorder modelHealthRecorder;
    // 跟随输入图比例的视频模型统一在 Provider 提交前归一化图片，避免批量镜头输出比例漂移。
    private final VideoInputAspectRatioNormalizer videoInputAspectRatioNormalizer;
    // 可灵创建阶段拒绝样本：只记录脱敏后的业务码/message/request_id，不影响退款与终态收口。
    private final KlingSubmissionFailureRecorder klingSubmissionFailureRecorder;
    // 外部模型输入资源仅在真正提交时生成临时签名 URL，普通页面展示地址保持不变。
    private final com.aid.media.service.ModelOutboundResourcePreparer modelOutboundResourcePreparer;
    /** 平台后处理只需要读取本站原始资源；不得套用仅供上游访问的图片代理。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.media.service.ModelResourceUrlSigner modelResourceUrlSigner;
    // 滚动文本 child 创建门禁：与父周期回滚共用数据库行锁，阻断迟到 worker 跨周期建单。
    private final IExtractBillingService extractBillingService;

    /** ETA 计算与成功样本采集采用字段注入，避免扩大本类已有大型构造器的测试改造面。 */
    @org.springframework.beans.factory.annotation.Autowired
    private MediaEtaService mediaEtaService;

    @org.springframework.beans.factory.annotation.Autowired
    private MediaEtaRecorder mediaEtaRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.redisson.api.RedissonClient redissonClient;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.media.resolver.ReferenceVideoRecordResolver referenceVideoRecordResolver;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.tokendance.credential.TokenDanceCredentialStore tokenDanceCredentialStore;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.voice.service.VoiceReferenceSampleService voiceReferenceSampleService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.media.service.VerifiedMediaInputService verifiedMediaInputService;

    /** 父任务取消标记用于阻止其遗留 QUEUED 子任务在取消后再次被调度。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.rps.queue.TaskCancelFlagManager taskCancelFlagManager;

    @PostConstruct
    void clampMaxCompensationRetry() {
        if (maxCompensationRetry < 1) {
            log.warn("aid.chat.media.compensation.max-retry={} is invalid, using 120", maxCompensationRetry);
            maxCompensationRetry = 120;
        }
        // 初始化 REQUIRES_NEW 短事务模板：建任务/预冻结独立提交，不并入上层调用方事务。
        this.requiresNewTxTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        RedisUtils.subscribe(TEXT_CANCEL_CHANNEL, Long.class, this::cancelLocalTextStream);
    }

    @Override
    public PreparedMediaBillingInput prepareImageBilling(MediaImageGenerateRequest request)
    {
        return prepareImageBilling(request, false);
    }

    private PreparedMediaBillingInput prepareImageBilling(MediaImageGenerateRequest request, boolean verifyMetadata)
    {
        if (request == null)
        {
            throw new ServiceException("请求不能为空");
        }
        AiModelConfigVo modelConfig = invocationResolver.select(resolveModel(request.getModelName(), MediaType.IMAGE,
                request.getBusinessFuncCode(), request.getCapabilityCode()), request.getCapabilityCode());
        // 区域编辑的真实原图尺寸必须先于模型参数默认值写回，避免默认 1024x1024 覆盖非 1024 原图。
        validateAndNormalizeProtectedImageEdit(request, modelConfig);
        invocationResolver.normalize(modelConfig, request);
        validatePromptForModel(modelConfig, request.getPrompt());
        ModelCapabilityValidator.validatePrompt(modelConfig, request.getPrompt());
        ImageProviderClient imageClient = resolveImageClient(request.getModelName(), modelConfig);
        ModelInputCapabilityValidator.validateRawImageInputs(modelConfig, request);
        ReferenceMediaRequestNormalizer.normalize(modelConfig, request,
                imageClient.fallbackMaxReferenceImages(modelConfig));
        ModelInputCapabilityValidator.normalizeAndValidateImage(modelConfig, request);
        if (verifyMetadata) verifiedMediaInputService.image(modelConfig, request);
        validateMinReferenceImages(modelConfig, countImageRequestReferenceImages(request));
        ModelCapabilityValidator.normalizeImageAspectRatio(modelConfig, request);
        ModelCapabilityValidator.validateImage(modelConfig, request);
        imageClient.validateRequest(modelConfig, request);
        BillingInput billingInput = BillingInputExtractor.fromImageRequest(
                request, modelConfig.getModelCode(), modelConfig.getMaxOutputCount(), modelConfig);
        billingInput.setInputMetadataPending(!verifyMetadata && countImageRequestReferenceImages(request) > 0);
        return new PreparedMediaBillingInput(modelConfig, billingInput);
    }

    @Override
    public PreparedMediaBillingInput preparePlannedImageBilling(MediaImageGenerateRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("请求不能为空");
        }
        AiModelConfigVo modelConfig = invocationResolver.select(resolveModel(request.getModelName(), MediaType.IMAGE,
                request.getBusinessFuncCode(), request.getCapabilityCode()), request.getCapabilityCode());
        validateAndNormalizeProtectedImageEdit(request, modelConfig);
        invocationResolver.normalizePlannedPrompt(modelConfig, request);
        ImageProviderClient imageClient = resolveImageClient(request.getModelName(), modelConfig);
        ModelInputCapabilityValidator.validateRawImageInputs(modelConfig, request);
        ReferenceMediaRequestNormalizer.normalize(modelConfig, request,
                imageClient.fallbackMaxReferenceImages(modelConfig));
        ModelInputCapabilityValidator.normalizeAndValidatePlannedImage(modelConfig, request);
        ModelCapabilityValidator.normalizeImageAspectRatio(modelConfig, request);
        ModelCapabilityValidator.validateImage(modelConfig, request);
        imageClient.validateRequest(modelConfig, request, true);
        BillingInput billingInput = BillingInputExtractor.fromImageRequest(
                request, modelConfig.getModelCode(), modelConfig.getMaxOutputCount(), modelConfig);
        billingInput.setInputMetadataPending(countImageRequestReferenceImages(request) > 0);
        return new PreparedMediaBillingInput(modelConfig, billingInput);
    }

    @Override
    public PreparedMediaBillingInput prepareVideoBilling(MediaVideoGenerateRequest request)
    {
        return prepareVideoBilling(request, false);
    }

    private PreparedMediaBillingInput prepareVideoBilling(MediaVideoGenerateRequest request, boolean verifyMetadata)
    {
        if (request == null)
        {
            throw new ServiceException("请求不能为空");
        }
        AiModelConfigVo modelConfig = invocationResolver.select(resolveModel(request.getModelName(), MediaType.VIDEO), request.getCapabilityCode());
        invocationResolver.normalize(modelConfig, request);
        validatePromptForModel(modelConfig, request.getPrompt());
        ModelCapabilityValidator.validatePrompt(modelConfig, request.getPrompt());
        // 先解析权属与已有元数据；数量及模态通过后再统一探测，避免非法请求触发大量下载。
        resolveReferenceVideoRecords(request, verifyMetadata);
        validateVideoProviderContract(modelConfig, request);
        ModelInputCapabilityValidator.validateRawVideoInputs(modelConfig, request);
        Wan3VideoRequestBuilder.validateRawInputs(modelConfig, request);
        AgnesVideo25RequestBuilder.validateRawInputs(modelConfig, request);
        VideoProviderClient videoClient = resolveVideoClient(request.getModelName(), modelConfig);
        videoClient.normalizeRequest(modelConfig, request);
        ReferenceMediaRequestNormalizer.normalize(modelConfig, request,
                videoClient.fallbackMaxReferenceImages(modelConfig),
                videoClient.fallbackMaxReferenceVideos(modelConfig));
        validateVideoRequestReferenceInputs(modelConfig, request);
        if (ModelCapabilityResolver.isVideoAspectRatioFollowInput(modelConfig)
                && StrUtil.isBlank(request.getAspectRatio()))
        {
            request.setAspectRatio(ModelCapabilityResolver.resolveVideoAspectRatio(modelConfig, null));
        }
        ModelCapabilityValidator.normalizeVideoAspectRatio(modelConfig, request);
        if (!isLipSyncRequest(modelConfig, request))
        {
            ModelCapabilityValidator.validateVideo(modelConfig, request.getDurationSeconds(),
                    request.getAspectRatio(), request.getOptions());
        }
        ModelCapabilityValidator.normalizeAndValidateVideoAudio(modelConfig, request);
        ModelInputCapabilityValidator.validateVideoQuote(modelConfig, request, false);
        if (verifyMetadata) verifiedMediaInputService.video(modelConfig, request);
        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(modelConfig, request);
        if (verifyMetadata) ModelInputCapabilityValidator.validateVideo(modelConfig, request);
        else ModelInputCapabilityValidator.validateVideoQuote(modelConfig, request, false);
        if (verifyMetadata) invocationResolver.validateVerifiedMaterials(modelConfig, request);
        validateVideoProviderContract(modelConfig, request);
        videoClient.validateRequest(modelConfig, request);
        BillingInput input = BillingInputExtractor.fromVideoRequest(request, modelConfig);
        input.setInputMetadataPending(!verifyMetadata);
        return new PreparedMediaBillingInput(modelConfig, input);
    }

    @Override
    public PreparedMediaBillingInput preparePlannedVideoBilling(MediaVideoGenerateRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("请求不能为空");
        }
        AiModelConfigVo modelConfig = invocationResolver.select(resolveModel(request.getModelName(), MediaType.VIDEO), request.getCapabilityCode());
        invocationResolver.normalizePlannedPrompt(modelConfig, request);
        resolveReferenceVideoRecords(request, false);
        validateVideoProviderContract(modelConfig, request);
        ModelInputCapabilityValidator.validateRawVideoInputs(modelConfig, request);
        Wan3VideoRequestBuilder.validateRawInputs(modelConfig, request);
        AgnesVideo25RequestBuilder.validateRawInputs(modelConfig, request);
        VideoProviderClient videoClient = resolveVideoClient(request.getModelName(), modelConfig);
        videoClient.normalizeRequest(modelConfig, request);
        ReferenceMediaRequestNormalizer.normalize(modelConfig, request,
                videoClient.fallbackMaxReferenceImages(modelConfig),
                videoClient.fallbackMaxReferenceVideos(modelConfig));
        validateVideoRequestReferenceInputs(modelConfig, request);
        if (ModelCapabilityResolver.isVideoAspectRatioFollowInput(modelConfig)
                && StrUtil.isBlank(request.getAspectRatio()))
        {
            request.setAspectRatio(ModelCapabilityResolver.resolveVideoAspectRatio(modelConfig, null));
        }
        ModelCapabilityValidator.normalizeVideoAspectRatio(modelConfig, request);
        if (!isLipSyncRequest(modelConfig, request))
        {
            ModelCapabilityValidator.validateVideo(modelConfig, request.getDurationSeconds(),
                    request.getAspectRatio(), request.getOptions());
        }
        ModelCapabilityValidator.normalizeAndValidateVideoAudio(modelConfig, request);
        ModelCapabilityValidator.normalizeAndValidateReferenceAudios(modelConfig, request);
        ModelInputCapabilityValidator.validateVideoQuote(modelConfig, request, true);
        Wan3VideoRequestBuilder.validateFullRequest(modelConfig, request);
        AgnesVideo25RequestBuilder.validateFullRequest(modelConfig, request);
        videoClient.validateRequest(modelConfig, request, true);
        BillingInput input = BillingInputExtractor.fromVideoRequest(request, modelConfig);
        input.setInputMetadataPending(true);
        return new PreparedMediaBillingInput(modelConfig, input);
    }

    private void resolveReferenceVideoRecords(MediaVideoGenerateRequest request, boolean allowProbe)
    {
        if (CollectionUtil.isNotEmpty(request.getReferenceVideoRecordIds()))
        {
            Long userId = request.getUserId() != null ? request.getUserId() : getCurrentUserIdSafe();
            referenceVideoRecordResolver.resolveAndApply(request, userId, allowProbe);
        }
        else
        {
            request.setResolvedReferenceVideos(Collections.emptyList());
        }
    }

    @Override
    public PreparedMediaBillingInput prepareTextBilling(MediaTextGenerateRequest request)
    {
        return prepareTextBilling(request, false);
    }

    private PreparedMediaBillingInput prepareTextBilling(MediaTextGenerateRequest request, boolean verifyMetadata)
    {
        TextGenerationControl.normalize(request, false);
        validateTextRequest(request);
        AiModelConfigVo modelConfig = invocationResolver.select(resolveTextModel(request), request.getCapabilityCode());
        invocationResolver.normalize(modelConfig, request);
        if (verifyMetadata) verifiedMediaInputService.text(modelConfig, request);
        TextModelCapabilityValidator.normalizeAndValidate(modelConfig, request, verifyMetadata);
        TextOutputLimitResolver.normalize(request, modelConfig, verifyMetadata);
        resolveTextClient(request.getModelName(), modelConfig).validateRequest(modelConfig, request);
        BillingInput input = BillingInputExtractor.fromTextRequest(request);
        input.setInputMetadataPending(!verifyMetadata && request.getMessages() != null
                && request.getMessages().stream().anyMatch(message -> message != null && message.getParts() != null
                && message.getParts().stream().anyMatch(part -> part != null && !"text".equalsIgnoreCase(part.getType()))));
        return new PreparedMediaBillingInput(modelConfig, input);
    }

    @Override
    public PreparedMediaBillingInput prepareAudioBilling(MediaAudioGenerateRequest request)
    {
        return prepareAudioBilling(request, false);
    }

    private PreparedMediaBillingInput prepareAudioBilling(MediaAudioGenerateRequest request, boolean verifyMetadata)
    {
        if (Objects.isNull(request))
        {
            throw new ServiceException("配音文本不能为空");
        }
        AiModelConfigVo modelConfig = invocationResolver.select(resolveModel(request.getModelName(), MediaType.AUDIO), request.getCapabilityCode());
        invocationResolver.normalize(modelConfig, request);
        boolean optimizePreview = "tokendance".equalsIgnoreCase(modelConfig.getProviderCode())
                && "tokendance:openai:chat-completions".equals(modelConfig.getProtocol())
                && "mimo-v2.5-tts-voicedesign".equals(modelConfig.getRealModelCode())
                && request.getOptions() != null && Boolean.TRUE.equals(request.getOptions().get("optimizeTextPreview"));
        if (StringUtils.isBlank(request.getTtsText()) && !optimizePreview) throw new ServiceException("配音文本不能为空");
        if ("tokendance".equalsIgnoreCase(modelConfig.getProviderCode())
                && "tokendance:openai:chat-completions".equals(modelConfig.getProtocol())
                && "pcm16".equalsIgnoreCase(request.getAudioFormat())) request.setAudioFormat("pcm");
        if (StringUtils.isBlank(request.getVoiceCode())
                && com.aid.media.util.AudioModelCapabilityValidator.requiresVoice(modelConfig))
        {
            throw new ServiceException("音色不可用");
        }
        validateAudioParameters(request);
        ModelCapabilityValidator.validatePrompt(modelConfig, request.getTtsText());
        voiceReferenceSampleService.prepare(modelConfig, request, verifyMetadata);
        if (verifyMetadata) verifiedMediaInputService.configured(modelConfig, request);
        com.aid.media.util.AudioModelCapabilityValidator.validate(modelConfig, request);
        if (request.getExpectedModelConfigurationHash() != null
                && !request.getExpectedModelConfigurationHash().equals(com.aid.model.ModelConfigurationFingerprint.of(modelConfig))) {
            throw new ServiceException("配置已变请重报价");
        }
        com.aid.tokendance.provider.audio.TokenDanceAudioPayloads.validate(modelConfig, request);
        return new PreparedMediaBillingInput(modelConfig,
                BillingInputExtractor.fromAudioRequest(request));
    }

    @Override
    public MediaTaskResponse generateImage(MediaImageGenerateRequest request) {
        return createImageTask(request, false);
    }

    @Override
    public MediaTaskResponse submitImage(MediaImageGenerateRequest request) {
        return createImageTask(request, true);
    }

    private MediaTaskResponse createImageTask(MediaImageGenerateRequest request, boolean deferred) {
        //      SecurityContext 会丢失，仅靠 getCurrentUserIdSafe() 会取到 null，
        //      导致 task.userId 为空、预冻结/结算/退款全部被跳过造成漏扣费。
        //      业务调用方显式 setUserId 时优先采用，否则回退到登录上下文（保留同步接口行为）。
        Long effectiveUserId = request.getUserId() != null ? request.getUserId() : getCurrentUserIdSafe();
        PreparedMediaBillingInput preparedBilling = prepareImageBilling(request, true);
        AiModelConfigVo modelConfig = preparedBilling.modelConfig();
        // 文件内容只能通过对象存储 URL 传递，抢占并发与扣费前先阻止 Base64/data URI 落库。
        String requestJson = MediaTaskPayloadSanitizer.serializeRequest(request);
        String requestHash = buildRequestHash(MediaType.IMAGE.name(), request, effectiveUserId);
        AidMediaTask existing = findRecentTaskByHash(requestHash);
        if (existing != null) {
            // 幂等命中补偿：若该任务已成功但 ossUrl 为空，当场补一次持久化，
            // 避免返回 stale 记录导致调用方拿到上游过期 URL 或触发 "存储失败" 守卫。
            repairExistingIfOssMissing(existing);
            return toResponse(existing);
        }
        // 四维并发准入（全局/用户/模型/供应商）：用规范模型编码抢占，与任务落库的 model_name 一致。
        boolean canRun = !deferred && concurrencyLimiter.tryAcquire(effectiveUserId, modelConfig.getModelCode());

        AidMediaTask task = new AidMediaTask();
        // 记录发起用户，匿名请求时允许为 null。
        task.setUserId(effectiveUserId);
        // 关联项目/剧集，列表查询时按项目过滤。
        task.setProjectId(request.getProjectId());
        task.setEpisodeId(request.getEpisodeId());
        // 标记任务类型为图片，供后续查询/轮询分支判断。
        task.setMediaType(MediaType.IMAGE.name());
        // 按模型名动态选择图片 provider：支持 dashscope 与 vidu 图片能力并存。
        ImageProviderClient client = resolveImageClient(request.getModelName(), modelConfig);
        // 将本次动态选中的协议写入任务，保证后续 queryTask 能命中同一个 provider。
        task.setProtocol(client.protocol());
        // 记录规范模型编码（model_code）：doSubmitToProvider/轮询/补偿均按 task.modelName 反查 aid_ai_model，
        // 必须落 modelConfig.getModelCode()（而非可能是别名/上游真实名的 request.modelName），保证反查命中；
        // provider 路由仍按 requestJson 内的原始 modelName 走 resolveImageClient，不受影响（与 generateVideo 一致）。
        task.setModelName(modelConfig.getModelCode());
        captureProviderRoute(task, modelConfig);
        // 原始提示词留存，便于审计和问题排查。
        // 若业务方显式传入 taskPromptDigest（如智能体模板生图场景），优先存 digest 摘要，避免 TEXT 列截断。
        task.setPrompt(summarizeImagePromptForTask(request));
        // 保存幂等哈希，配合唯一索引保证同请求可复用。
        task.setRequestHash(requestHash);
        // 请求报文快照只保存业务参数，不允许保存图片原文。
        task.setRequestJson(requestJson);
        // 并发未超限为 PENDING，超限为 QUEUED 排队等待。
        task.setStatus(canRun ? MediaTaskStatus.PENDING.name() : MediaTaskStatus.QUEUED.name());
        // 初始计费状态为 INIT，表示尚未完成扣费闭环。
        task.setBillingStatus(MediaBillingStatus.INIT.name());
        // 初始轮询次数为 0。
        task.setRetryCount(0);
        // 可选：业务记录回填参数（异步轮询成功后仍可从本行读出）。
        task.setCallbackRecordId(request.getRecordId());
        task.setCallbackCategory(request.getCategory());
        // 业务任务关联：记录是哪个业务任务触发的本次媒体调用（同时参与 requestHash 破除错误幂等复用）
        task.setBizTaskId(request.getBizTaskId());
        task.setBizTaskType(request.getBizTaskType());
        // 入库 + 预冻结 + 回写 FROZEN：用 REQUIRES_NEW 短事务独立提交（即使上层在事务中也立即可见），
        // 提交后再在事务外提交上游。
        fillCreateInfo(task);
        try {
            requiresNewTxTemplate.executeWithoutResult(s -> {
                // 先落库，避免外部成功但本地无任务记录。
                aidMediaTaskMapper.insert(task);
                com.aid.diagnostics.DiagnosticCapture.rememberTask(task);
                // 预冻结计费（登录用户生效）：透传最终 modelCode 与 max_output_count，硬编码仅兜底。
                billingFacadeService.prepareBilling(task, modelConfig, preparedBilling.billingInput());
                // 预冻结成功后立即回写 FROZEN，确保 settleBilling/refundBilling 的 CAS 条件能命中 DB 状态。
                updateTaskWithPayloadArchive(task);
            });
        } catch (Exception freezeEx) {
            // 建任务/预冻结失败：释放并发名额；若冻结已通过 REQUIRES_NEW 独立提交则显式退款兜底。
            log.error("图片建任务/预冻结失败, taskId={}", task.getId(), freezeEx);
            if (canRun) {
                releaseConcurrencyAfterCompletion(task);
            }
            refundFrozenIfNeeded(task, "图片预冻结失败退回");
            throw freezeEx;
        }
        // 并发超限：QUEUED，提交交由现有排队拉起逻辑（drainQueue）。
        if (deferred) {
            dispatchDeferredTask(task.getId());
            return toResponse(task);
        }
        if (!canRun) {
            return toResponse(task);
        }
        // 短事务已提交（任务可见、连接已归还）。此处在事务外提交上游并回写终态。
        // doSubmitToProvider 内部：事务外 submit → handleSubmitResult 状态机 → transactionTemplate 回写
        //   → 终态 CAS + 失败退款 + 释放并发 + OSS 回写 + 事件发布，覆盖同步/异步全部 provider。
        // 同步 provider 此时 task 已为 SUCCEEDED+ossUrl，异步 provider 为 PROCESSING。
        doSubmitToProvider(task);
        return toResponse(task);
    }

    @Override
    public MediaTaskResponse generateVideo(MediaVideoGenerateRequest request) {
        return createVideoTask(request, false);
    }

    @Override
    public MediaTaskResponse submitVideo(MediaVideoGenerateRequest request) {
        return createVideoTask(request, true);
    }

    private MediaTaskResponse createVideoTask(MediaVideoGenerateRequest request, boolean deferred) {
        //      SecurityContext 会丢失，仅靠 getCurrentUserIdSafe() 会取到 null，
        //      导致 task.userId 为空、预冻结/结算/退款全部被跳过造成漏扣费。
        //      业务调用方显式 setUserId 时优先采用，否则回退到登录上下文（保留同步接口行为）。
        Long effectiveUserId = request.getUserId() != null ? request.getUserId() : getCurrentUserIdSafe();
        PreparedMediaBillingInput preparedBilling = prepareVideoBilling(request, true);
        AiModelConfigVo modelConfig = preparedBilling.modelConfig();
        // 文件内容只能通过对象存储 URL 传递，抢占并发与扣费前先阻止 Base64/data URI 落库。
        String requestJson = MediaTaskPayloadSanitizer.serializeRequest(request);
        String requestHash = buildRequestHash(MediaType.VIDEO.name(), request, effectiveUserId);
        AidMediaTask existing = findRecentTaskByHash(requestHash);
        if (existing != null) {
            // 幂等命中补偿：若该任务已成功但 ossUrl 为空，当场补一次持久化。
            repairExistingIfOssMissing(existing);
            return toResponse(existing);
        }
        // 四维并发准入（全局/用户/模型/供应商）：用规范模型编码抢占，与任务落库的 model_name 一致。
        boolean canRun = !deferred && concurrencyLimiter.tryAcquire(effectiveUserId, modelConfig.getModelCode());

        AidMediaTask task = new AidMediaTask();
        // 记录用户上下文（优先显式入参，兜底登录态）。
        task.setUserId(effectiveUserId);
        // 关联项目/剧集。
        task.setProjectId(request.getProjectId());
        task.setEpisodeId(request.getEpisodeId());
        // 标记任务类型。
        task.setMediaType(MediaType.VIDEO.name());
        // 按模型名动态选择视频 provider：为后续接入万相/可灵/爱诗等平台保留扩展位。
        VideoProviderClient client = resolveVideoClient(request.getModelName(), modelConfig);
        // 将本次动态选中的协议写入任务，保证后续 queryTask 能命中同一个 provider。
        task.setProtocol(client.protocol());
        // 记录模型名。
        task.setModelName(modelConfig.getModelCode());
        captureProviderRoute(task, modelConfig);
        // 记录提示词。
        // 若业务方显式传入 taskPromptDigest（如智能体模板视频场景），优先存 digest 摘要，避免 TEXT 列截断。
        task.setPrompt(summarizeVideoPromptForTask(request));
        // 记录幂等哈希。
        task.setRequestHash(requestHash);
        // 保存无内嵌文件的请求业务快照。
        task.setRequestJson(requestJson);
        // 并发未超限为 PENDING，超限为 QUEUED 排队等待。
        task.setStatus(canRun ? MediaTaskStatus.PENDING.name() : MediaTaskStatus.QUEUED.name());
        // 初始计费状态。
        task.setBillingStatus(MediaBillingStatus.INIT.name());
        // 初始重试次数。
        task.setRetryCount(0);
        task.setCallbackRecordId(request.getRecordId());
        task.setCallbackCategory(request.getCategory());
        // 业务任务关联：记录是哪个业务任务触发的本次媒体调用（同时参与 requestHash 破除错误幂等复用）。
        task.setBizTaskId(request.getBizTaskId());
        task.setBizTaskType(request.getBizTaskType());
        task.setParentTaskId(request.getParentTaskId());
        // 入库 + 预冻结 + 回写 FROZEN：用 REQUIRES_NEW 短事务独立提交，提交后再在事务外提交上游。
        fillCreateInfo(task);
        try {
            requiresNewTxTemplate.executeWithoutResult(s -> {
                aidMediaTaskMapper.insert(task);
                com.aid.diagnostics.DiagnosticCapture.rememberTask(task);
                billingFacadeService.prepareBilling(task, modelConfig, preparedBilling.billingInput());
                // 预冻结成功后回写 FROZEN，确保 settle/refund 的 CAS 条件能命中 DB 状态。
                updateTaskWithPayloadArchive(task);
            });
        } catch (Exception freezeEx) {
            log.error("视频建任务/预冻结失败, taskId={}", task.getId(), freezeEx);
            if (canRun) {
                releaseConcurrencyAfterCompletion(task);
            }
            refundFrozenIfNeeded(task, "视频预冻结失败退回");
            throw freezeEx;
        }
        // 并发超限：QUEUED，提交交由现有排队拉起逻辑。
        if (deferred) {
            dispatchDeferredTask(task.getId());
            return toResponse(task);
        }
        if (!canRun) {
            return toResponse(task);
        }
        // 短事务已提交（任务可见、连接已归还）。事务外提交上游并回写终态（含轮询调度/退款/OSS/事件）。
        doSubmitToProvider(task);
        return toResponse(task);
    }

    @Override
    public MediaTaskResponse generateAudio(com.aid.media.dto.MediaAudioGenerateRequest request) {
        return generateAudioTask(request, null);
    }

    @Override
    public MediaTaskResponse generateOperatorAudio(MediaAudioGenerateRequest request, Long adminId) {
        if (adminId == null || adminId <= 0 || request == null) throw new ServiceException("管理员身份无效");
        request.setUserId(-adminId);
        request.setProjectId(null);
        request.setEpisodeId(null);
        request.setRecordId(null);
        request.setCategory(null);
        request.setParentTaskId(null);
        request.setBizTaskId(null);
        request.setBizTaskType("operator_voice");
        request.setPreviewMode(false);
        return generateAudioTask(request, adminId);
    }

    private MediaTaskResponse generateAudioTask(MediaAudioGenerateRequest request, Long operatorAdminId) {
        PreparedMediaBillingInput preparedBilling = prepareAudioBilling(request, true);
        if (operatorAdminId == null && "tokendance:minimax:voice_clone".equals(preparedBilling.modelConfig().getProtocol())) {
            throw new ServiceException("请在后台创建音色");
        }

        Long effectiveUserId = request.getUserId() != null ? request.getUserId() : getCurrentUserIdSafe();
        AiModelConfigVo modelConfig = preparedBilling.modelConfig();
        // 保持既有请求哈希语义，随后将权威身份写入快照，供无登录上下文的排队恢复使用。
        String requestHash = buildRequestHash(MediaType.AUDIO.name(), request, effectiveUserId);
        request.setUserId(effectiveUserId);
        // 正式任务只接受文本与业务参数，禁止把音频 Base64 放入扩展字段后写库。
        String requestJson = MediaTaskPayloadSanitizer.serializeRequest(request);
        AidMediaTask existing = findRecentTaskByHash(requestHash);
        if (existing != null) {
            repairExistingIfOssMissing(existing);
            return toResponse(existing);
        }

        // 后台音色先返回持久化任务，后续沿用统一队列并发准入，不改变 C 端同步配音行为。
        boolean canRun = operatorAdminId == null && concurrencyLimiter.tryAcquire(effectiveUserId, modelConfig.getModelCode());

        AidMediaTask task = new AidMediaTask();
        task.setUserId(effectiveUserId);
        task.setProjectId(request.getProjectId());
        task.setEpisodeId(request.getEpisodeId());
        task.setMediaType(MediaType.AUDIO.name());
        com.aid.media.provider.AudioProviderClient client = resolveAudioClient(request.getModelName(), modelConfig);
        task.setProtocol(client.protocol());
        task.setModelName(modelConfig.getModelCode());
        captureProviderRoute(task, modelConfig);
        // prompt 复用存 ttsText，便于现有列表/日志通用展示
        task.setPrompt(limitTaskPromptSummary(request.getTtsText()));
        task.setRequestHash(requestHash);
        task.setRequestJson(requestJson);
        task.setStatus(canRun ? MediaTaskStatus.PENDING.name() : MediaTaskStatus.QUEUED.name());
        task.setBillingStatus(MediaBillingStatus.INIT.name());
        task.setRetryCount(0);
        task.setBizTaskId(request.getBizTaskId());
        task.setBizTaskType(request.getBizTaskType());
        task.setParentTaskId(request.getParentTaskId());
        task.setCallbackRecordId(request.getRecordId());
        task.setCallbackCategory(request.getCategory());
        // 入库 + 预冻结 + 回写 FROZEN：REQUIRES_NEW 短事务独立提交，提交后在事务外提交上游。
        fillCreateInfo(task);
        try {
            requiresNewTxTemplate.executeWithoutResult(s -> {
                aidMediaTaskMapper.insert(task);
                com.aid.diagnostics.DiagnosticCapture.rememberTask(task);
                if (operatorAdminId == null) billingFacadeService.prepareBilling(task, modelConfig, preparedBilling.billingInput());
                else billingFacadeService.prepareOperatorBilling(task, modelConfig, preparedBilling.billingInput(), operatorAdminId);
                updateTaskWithPayloadArchive(task);
            });
        } catch (Exception freezeEx) {
            log.error("音频建任务/预冻结失败, taskId={}", task.getId(), freezeEx);
            if (canRun) {
                releaseConcurrencyAfterCompletion(task);
            }
            refundFrozenIfNeeded(task, "音频预冻结失败退回");
            throw freezeEx;
        }
        // 并发超限：QUEUED，提交交由现有排队拉起逻辑。
        if (!canRun) {
            return toResponse(task);
        }
        // 短事务已提交。事务外提交上游并回写终态：豆包 TTS 异步→PROCESSING；
        // 同步成功→OSS 回写 + publishOssPersistedEvent，均由 doSubmitToProvider 统一处理。
        doSubmitToProvider(task);
        return toResponse(task);
    }

    /**
     * 正式配音任务公共参数校验。所有业务入口最终都会经过本方法，防止内部调用绕过 Controller 校验。
     */
    private void validateAudioParameters(com.aid.media.dto.MediaAudioGenerateRequest request) {
        if (Objects.nonNull(request.getEmotionScale())
                && (request.getEmotionScale() < 1 || request.getEmotionScale() > 5)) {
            log.info("音频任务情感强度非法: value={}", request.getEmotionScale());
            throw new ServiceException("情感强度无效");
        }
        if (Objects.nonNull(request.getSpeechRate())
                && (request.getSpeechRate() < -50 || request.getSpeechRate() > 100)) {
            log.info("音频任务语速非法: value={}", request.getSpeechRate());
            throw new ServiceException("语速无效");
        }
        if (Objects.nonNull(request.getLoudnessRate())
                && (request.getLoudnessRate() < -50 || request.getLoudnessRate() > 100)) {
            log.info("音频任务音量非法: value={}", request.getLoudnessRate());
            throw new ServiceException("音量无效");
        }
        if (Objects.nonNull(request.getPitch())
                && (request.getPitch() < -12 || request.getPitch() > 12)) {
            log.info("音频任务音调非法: value={}", request.getPitch());
            throw new ServiceException("音调无效");
        }
        if (StringUtils.isNotBlank(request.getAudioFormat())) {
            String format = request.getAudioFormat().trim().toLowerCase(Locale.ROOT);
            if (!AUDIO_FORMATS.contains(format)) {
                log.info("音频任务格式非法: value={}", request.getAudioFormat());
                throw new ServiceException("音频格式无效");
            }
            request.setAudioFormat(format);
        }
        if (Objects.nonNull(request.getSampleRate()) && !AUDIO_SAMPLE_RATES.contains(request.getSampleRate())) {
            log.info("音频任务采样率非法: value={}", request.getSampleRate());
            throw new ServiceException("采样率无效");
        }
    }

    /**
     * 按 providerCode / capability_json.provider / modelName / protocol 四级优先级解析音频 provider。
     */
    private com.aid.media.provider.AudioProviderClient resolveAudioClient(String requestModel, AiModelConfigVo modelConfig) {
        if (modelConfig != null && StringUtils.isNotBlank(modelConfig.getCapabilityCode())) return getAudioClientByProtocol(modelConfig.getProtocol());
        String providerCode = modelConfig == null ? null : modelConfig.getProviderCode();
        if (providerCode != null && !providerCode.isBlank()) {
            List<com.aid.media.provider.AudioProviderClient> byCode = audioProviderClients.stream()
                    .filter(it -> it.supportsProviderCode(providerCode))
                    .toList();
            if (byCode.size() == 1) {
                log.info("resolveAudioClient 命中 providerCode 强路由: providerCode={}", providerCode);
                return byCode.get(0);
            }
            if (byCode.size() > 1) {
                log.error("resolveAudioClient providerCode 命中多个 provider: providerCode={}, count={}",
                        providerCode, byCode.size());
                throw new ServiceException("系统繁忙");
            }
            // 0 个命中：providerCode 不被任何 client 识别，降级到下一级
            log.info("resolveAudioClient providerCode 未命中任何 client，降级: providerCode={}", providerCode);
        }

        String capabilityProvider = parseCapabilityJsonProvider(modelConfig);
        if (capabilityProvider != null && !capabilityProvider.isBlank()) {
            List<com.aid.media.provider.AudioProviderClient> byCapability = audioProviderClients.stream()
                    .filter(it -> it.supportsProviderCode(capabilityProvider))
                    .toList();
            if (byCapability.size() == 1) {
                log.info("resolveAudioClient 命中 capability_json.provider 路由: capabilityProvider={}",
                        capabilityProvider);
                return byCapability.get(0);
            }
            if (byCapability.size() > 1) {
                log.error("resolveAudioClient capability_json.provider 命中多个 provider: capabilityProvider={}, count={}",
                        capabilityProvider, byCapability.size());
                throw new ServiceException("系统繁忙");
            }
            log.info("resolveAudioClient capability_json.provider 未命中任何 client，降级: capabilityProvider={}",
                    capabilityProvider);
        }

        String effectiveModel = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig, requestModel);
        if (StringUtils.isNotBlank(effectiveModel)) {
            List<com.aid.media.provider.AudioProviderClient> byModel = audioProviderClients.stream()
                    .filter(it -> it.supportsModel(effectiveModel))
                    .toList();
            if (byModel.size() == 1) {
                log.info("resolveAudioClient 命中 modelName 路由: modelName={}", effectiveModel);
                return byModel.get(0);
            }
        }

        List<com.aid.media.provider.AudioProviderClient> byProtocol = audioProviderClients.stream()
                .filter(it -> it.supportsProtocol(com.aid.media.constants.VolcengineTtsConstants.PROTOCOL_TTS))
                .toList();
        if (byProtocol.size() == 1) {
            log.info("resolveAudioClient 命中协议兜底: protocol={}", com.aid.media.constants.VolcengineTtsConstants.PROTOCOL_TTS);
            return byProtocol.get(0);
        }
        throw new ServiceException("音频服务未配置");
    }

    /**
     * 从 {@code AiModelConfigVo.capabilityJson} 解析 {@code provider} 字段。
     * 与 {@code StoryboardWorkbenchServiceImpl.parseCapabilityProvider} 语义保持一致：
     * JSON 缺失 / 解析失败 / 字段不存在 → 返回 null（调用方视为"无声明"，降级到下一级路由）。
     */
    private String parseCapabilityJsonProvider(AiModelConfigVo modelConfig) {
        if (modelConfig == null || modelConfig.getCapabilityJson() == null
                || modelConfig.getCapabilityJson().isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(modelConfig.getCapabilityJson());
            com.fasterxml.jackson.databind.JsonNode node = root.get("provider");
            if (node != null && !node.isNull() && !node.asText().isBlank()) {
                return node.asText().trim();
            }
        } catch (Exception e) {
            log.warn("resolveAudioClient 解析 capability_json.provider 失败: modelCode={}, err={}",
                    modelConfig.getModelCode(), e.getMessage());
        }
        return null;
    }

    @Override
    public MediaTaskResponse generateText(MediaTextGenerateRequest request) {
        return createTextTask(request, false);
    }

    @Override
    public MediaTaskResponse submitText(MediaTextGenerateRequest request) {
        return createTextTask(request, true);
    }

    private MediaTaskResponse createTextTask(MediaTextGenerateRequest request, boolean deferred) {
        //      限流、幂等、入库、释放全部用同一个值，避免 anonymous 与真实 userId 错乱。
        Long effectiveUserId = request.getUserId() != null ? request.getUserId() : getCurrentUserIdSafe();
        PreparedMediaBillingInput preparedBilling = prepareTextBilling(request, true);
        AiModelConfigVo modelConfig = preparedBilling.modelConfig();
        String requestJson = MediaTaskPayloadSanitizer.serializeRequest(request);
        String requestHash = buildRequestHash(MediaType.TEXT.name(), request, effectiveUserId);
        AidMediaTask existing = StrUtil.isNotBlank(request.getCallId())
                ? findTaskByIdempotencyKey(request.getCallId()) : findRecentTaskByHash(requestHash);
        if (existing != null) {
            if (StrUtil.isNotBlank(request.getCallId())) {
                validateTextIdempotencyWinner(existing, request, effectiveUserId, modelConfig.getModelCode());
            }
            return toResponse(existing);
        }
        TextProviderClient client = resolveTextClient(request.getModelName(), modelConfig);
        client.validateProviderConfiguration(modelConfig, request);
        // 四维并发准入（全局/用户/模型/供应商）：用规范模型编码抢占，与任务落库的 model_name 一致。
        boolean canRun = !deferred && concurrencyLimiter.tryAcquire(effectiveUserId, modelConfig.getModelCode());
        if (!canRun && com.aid.tokendance.provider.text.TokenDanceToolMessages.isToolTurn(modelConfig, request)) {
            // 工具续轮含仅内存思考签名，不能降级为丢失上下文的磁盘排队请求。
            throw new ServiceException("并发已满请稍后重试");
        }
        AidMediaTask task = new AidMediaTask();
        task.setUserId(effectiveUserId);
        task.setProjectId(request.getProjectId());
        task.setEpisodeId(request.getEpisodeId());
        task.setMediaType(MediaType.TEXT.name());
        task.setProtocol(client.protocol());
        task.setModelName(modelConfig.getModelCode());
        captureProviderRoute(task, modelConfig);
        task.setPrompt(summarizeTextPromptForTask(request));
        task.setRequestHash(requestHash);
        task.setIdempotencyKey(StrUtil.blankToDefault(request.getCallId(), null));
        task.setRequestJson(requestJson);
        // 并发未超限为 PENDING，超限为 QUEUED 排队等待。
        task.setStatus(canRun ? MediaTaskStatus.PENDING.name() : MediaTaskStatus.QUEUED.name());
        // billingExempt 时跳过计费，由外层任务统一计费
        boolean exempt = request.getBillingExempt() != null && request.getBillingExempt();
        task.setBillingStatus(exempt ? null : MediaBillingStatus.INIT.name());
        task.setRetryCount(0);
        // 业务任务关联：记录是哪个业务任务触发的本次媒体调用
        task.setBizTaskId(request.getBizTaskId());
        task.setBizTaskType(request.getBizTaskType());
        fillCreateInfo(task);
        // 入库 + （非 exempt 时）预冻结 + 回写 FROZEN：REQUIRES_NEW 短事务独立提交，提交后在事务外提交上游。
        // billingExempt 时跳过预冻结，由外层任务统一计费。
        try {
            requiresNewTxTemplate.executeWithoutResult(s -> {
                if (!exempt && StrUtil.isNotBlank(request.getBillingAttemptId())) {
                    extractBillingService.assertRollingTextCallExecution(
                            request.getBizTaskId(), effectiveUserId, request.getBillingAttemptId());
                }
                aidMediaTaskMapper.insert(task);
                com.aid.diagnostics.DiagnosticCapture.rememberTask(task);
                if (!exempt) {
                    billingFacadeService.prepareBilling(task, modelConfig, preparedBilling.billingInput());
                }
                updateTaskWithPayloadArchive(task);
            });
        } catch (DuplicateKeyException duplicate) {
            if (canRun) {
                releaseConcurrencyAfterCompletion(task);
            }
            AidMediaTask winner = findTaskByIdempotencyKey(request.getCallId());
            if (winner != null) {
                validateTextIdempotencyWinner(winner, request, effectiveUserId, modelConfig.getModelCode());
                log.info("文本逻辑调用已由并发请求创建，复用原任务, callId={}, taskId={}",
                        request.getCallId(), winner.getId());
                return toResponse(winner);
            }
            throw new ServiceException("任务处理中");
        } catch (Exception freezeEx) {
            log.error("文本建任务/预冻结失败, taskId={}", task.getId(), freezeEx);
            if (canRun) {
                releaseConcurrencyAfterCompletion(task);
            }
            if (!exempt) {
                refundFrozenIfNeeded(task, "文本预冻结失败退回");
            }
            throw freezeEx;
        }
        // 并发超限：QUEUED，提交交由现有排队拉起逻辑。
        if (deferred) {
            dispatchDeferredTask(task.getId());
            return toResponse(task);
        }
        if (!canRun) {
            return toResponse(task);
        }
        // 短事务已提交。事务外提交上游并回写终态：doSubmitToProvider 已统一处理
        // preferNonStream→chatSync / 流式→submit、exempt 的 usage 快照、终态回写与退款。
        doSubmitToProvider(task, request);
        return toResponse(task);
    }

    @Override
    public void generateTextStream(MediaTextGenerateRequest request, MediaTextStreamSink sink) {
        TextGenerationControl.normalize(request, true);
        validateTextRequest(request);
        Long effectiveUserId = request.getUserId() != null ? request.getUserId() : getCurrentUserIdSafe();
        AiModelConfigVo modelConfig = invocationResolver.select(resolveTextModel(request), request.getCapabilityCode());
        invocationResolver.normalize(modelConfig, request);
        verifiedMediaInputService.text(modelConfig, request);
        TextModelCapabilityValidator.normalizeAndValidate(modelConfig, request);
        TextOutputLimitResolver.normalize(request, modelConfig);
        String requestJson = MediaTaskPayloadSanitizer.serializeRequest(request);
        String requestHash = buildRequestHash(MediaType.TEXT.name(), request, effectiveUserId);
        AidMediaTask existing = StrUtil.isNotBlank(request.getCallId())
                ? findTaskByIdempotencyKey(request.getCallId()) : null;
        if (existing != null) {
            validateTextIdempotencyWinner(existing, request, effectiveUserId, modelConfig.getModelCode());
            notifyExistingTextStream(sink, existing);
            return;
        }
        TextProviderClient client = resolveTextClient(request.getModelName(), modelConfig);
        client.validateProviderConfiguration(modelConfig, request);
        client.validateRequest(modelConfig, request);
        boolean toolTurn = com.aid.tokendance.provider.text.TokenDanceToolMessages.isToolTurn(modelConfig, request);
        if (toolTurn && !sink.supportsToolMessages()) throw new ServiceException("当前入口不支持工具");
        boolean canRun = concurrencyLimiter.tryAcquire(effectiveUserId, modelConfig.getModelCode());
        if (!canRun && toolTurn) throw new ServiceException("并发已满请稍后重试");
        boolean exempt = Boolean.TRUE.equals(request.getBillingExempt());

        AidMediaTask task = buildTextStreamTask(request, requestJson, modelConfig, client,
                canRun, effectiveUserId, requestHash);
        try {
            // 调用线程同步完成“任务落库 + 预冻结”，短事务提交后才向业务返回 taskId。
            requiresNewTxTemplate.executeWithoutResult(status -> {
                if (!exempt && StrUtil.isNotBlank(request.getBillingAttemptId())) {
                    extractBillingService.assertRollingTextCallExecution(
                            request.getBizTaskId(), effectiveUserId, request.getBillingAttemptId());
                }
                aidMediaTaskMapper.insert(task);
                com.aid.diagnostics.DiagnosticCapture.rememberTask(task);
                if (!exempt) {
                    BillingInput billingInput = BillingInputExtractor.fromTextRequest(request);
                    billingFacadeService.prepareBilling(task, modelConfig, billingInput);
                }
                updateTaskWithPayloadArchive(task);
            });
        } catch (DuplicateKeyException duplicate) {
            if (canRun) {
                releaseConcurrencyAfterCompletion(task);
            }
            AidMediaTask winner = findTaskByIdempotencyKey(request.getCallId());
            if (winner != null) {
                validateTextIdempotencyWinner(winner, request, effectiveUserId, modelConfig.getModelCode());
                log.info("文本流式逻辑调用已由并发请求创建，附着原任务, callId={}, taskId={}",
                        request.getCallId(), winner.getId());
                notifyExistingTextStream(sink, winner);
                return;
            }
            safeNotifyTextSink(task.getId(), "failed", () -> sink.onFailed("任务处理中"));
            return;
        } catch (RuntimeException prepareError) {
            log.error("文本流式任务准备失败, taskId={}, errorType={}", task.getId(),
                    prepareError.getClass().getSimpleName(), prepareError);
            if (!exempt) {
                refundFrozenIfNeeded(task, "流式文本准备失败退回");
            }
            if (canRun) {
                releaseConcurrencyAfterCompletion(task);
            }
            String userMessage = TaskErrorPresentation.fromThrowable(prepareError, "系统繁忙").getMessage();
            safeNotifyTextSink(task.getId(), "failed", () -> sink.onFailed(userMessage));
            return;
        }

        try {
            // 该回调负责业务侧持久化关联，不可按普通 SSE 断开吞掉；失败必须收口已冻结任务。
            sink.onTaskPrepared(task.getId());
        } catch (RuntimeException attachError) {
            log.error("文本流式任务关联失败, taskId={}, errorType={}", task.getId(),
                    attachError.getClass().getSimpleName(), attachError);
            boolean terminalWon = finishTextStreamFailure(task, "业务关联失败", null,
                    task.getStatus(), null);
            if (terminalWon) {
                if (canRun) {
                    releaseConcurrency(task);
                }
                safeNotifyTextSink(task.getId(), "failed", () -> sink.onFailed("系统繁忙"));
                publishTextTaskCompletedSafely(task);
            } else {
                // 任务已被队列或补偿推进；本线程不再抢终态，只结束当前流并转查询。
                safeNotifyTextSink(task.getId(), "detached", () -> sink.onDetached(task.getId()));
            }
            return;
        }

        // 排队任务由现有统一队列拉起；当前 SSE 只通知 detached，不占工作线程、不释放坑位。
        if (!canRun) {
            safeNotifyTextSink(task.getId(), "detached", () -> sink.onDetached(task.getId()));
            return;
        }

        try {
            TextStreamCancellationControl control = new TextStreamCancellationControl();
            activeTextStreams.put(task.getId(), control);
            boolean accepted = executeTextStreamAsync(control,
                    () -> runTextStreamWorker(request, modelConfig, client, sink, task, control));
            if (!accepted) {
                activeTextStreams.remove(task.getId(), control);
                rejectPreparedTextStreamTask(task, sink, null);
            }
        } catch (Exception rejectEx) {
            activeTextStreams.remove(task.getId());
            log.error("文本流式提交线程池被拒绝, taskId={}, errorType={}", task.getId(),
                    rejectEx.getClass().getSimpleName(), rejectEx);
            rejectPreparedTextStreamTask(task, sink, rejectEx);
        }
    }

    /**
     * 共享线程池使用 CallerRunsPolicy。文本长流禁止回落到请求线程，因此用线程身份守卫把
     * CallerRuns 转为显式拒绝；不修改共享池的既有拒绝策略，也不新建第二套队列。
     */
    private boolean executeTextStreamAsync(TextStreamCancellationControl control, Runnable worker) {
        if (threadPoolTaskExecutor.getThreadPoolExecutor().isShutdown()) {
            return false;
        }
        Thread submitThread = Thread.currentThread();
        AtomicBoolean callerRunsFallback = new AtomicBoolean(false);
        AtomicBoolean executeReturned = new AtomicBoolean(false);
        FutureTask<Void> future = new FutureTask<>(() -> {
            if (Thread.currentThread() == submitThread && !executeReturned.get()) {
                callerRunsFallback.set(true);
                return null;
            }
            worker.run();
            return null;
        });
        control.attach(future);
        threadPoolTaskExecutor.execute(future);
        executeReturned.set(true);
        return !callerRunsFallback.get();
    }

    private void rejectPreparedTextStreamTask(AidMediaTask task, MediaTextStreamSink sink,
                                              Throwable rejectError) {
        if (rejectError == null) {
            log.warn("文本流式任务异步执行被拒绝, taskId={}", task.getId());
        }
        boolean terminalWon = finishTextStreamFailure(task, "系统繁忙", null,
                MediaTaskStatus.PENDING.name(), null);
        if (terminalWon) {
            releaseConcurrency(task);
            safeNotifyTextSink(task.getId(), "rejected", () -> sink.onFailed("系统繁忙"));
            publishTextTaskCompletedSafely(task);
        }
    }

    private AidMediaTask buildTextStreamTask(MediaTextGenerateRequest request, String requestJson,
                                              AiModelConfigVo modelConfig, TextProviderClient client,
                                              boolean canRun, Long effectiveUserId, String requestHash) {
        AidMediaTask task = new AidMediaTask();
        task.setUserId(effectiveUserId);
        task.setProjectId(request.getProjectId());
        task.setEpisodeId(request.getEpisodeId());
        task.setMediaType(MediaType.TEXT.name());
        task.setProtocol(client.protocol());
        task.setModelName(modelConfig.getModelCode());
        captureProviderRoute(task, modelConfig);
        task.setPrompt(summarizeTextPromptForTask(request));
        task.setRequestHash(requestHash);
        task.setIdempotencyKey(StrUtil.blankToDefault(request.getCallId(), null));
        task.setRequestJson(requestJson);
        task.setStatus(canRun ? MediaTaskStatus.PENDING.name() : MediaTaskStatus.QUEUED.name());
        task.setBillingStatus(Boolean.TRUE.equals(request.getBillingExempt())
                ? null : MediaBillingStatus.INIT.name());
        task.setRetryCount(0);
        task.setBizTaskId(request.getBizTaskId());
        task.setBizTaskType(request.getBizTaskType());
        fillCreateInfo(task);
        return task;
    }

    /**
     * 流式幂等命中只附着既有任务。进行中任务交给 Run 事件/查询恢复，绝不再次提交 Provider。
     */
    private void notifyExistingTextStream(MediaTextStreamSink sink, AidMediaTask existing) {
        try {
            sink.onTaskPrepared(existing.getId());
        } catch (RuntimeException attachError) {
            log.info("文本流式幂等任务附着失败, taskId={}, errorType={}",
                    existing.getId(), attachError.getClass().getSimpleName());
            safeNotifyTextSink(existing.getId(), "failed", () -> sink.onFailed("请求失败"));
            return;
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(existing.getStatus())) {
            if (com.aid.media.provider.TextToolResultSupport.read(existing) != null) {
                safeNotifyTextSink(existing.getId(), "tool_context_unavailable", () -> sink.onFailed("工具上下文仅首次返回"));
                return;
            }
            safeNotifyTextSink(existing.getId(), "done",
                    () -> sink.onDone(StrUtil.blankToDefault(existing.getResultText(), ""), null));
            return;
        }
        if (MediaTaskStatus.FAILED.name().equals(existing.getStatus())) {
            safeNotifyTextSink(existing.getId(), "failed",
                    () -> sink.onFailed(StrUtil.blankToDefault(existing.getErrorMessage(), "生成失败")));
            return;
        }
        if (MediaTaskStatus.CANCELLED.name().equals(existing.getStatus())) {
            safeNotifyTextSink(existing.getId(), "failed", () -> sink.onFailed("任务已取消"));
            return;
        }
        safeNotifyTextSink(existing.getId(), "detached", () -> sink.onDetached(existing.getId()));
    }

    /**
     * 业务含义：后台线程只读取上游 SSE 并竞争终态；任务与冻结金额已在调用线程提交。
     */
    private void runTextStreamWorker(MediaTextGenerateRequest request, AiModelConfigVo modelConfig,
                                     TextProviderClient client, MediaTextStreamSink sink,
                                     AidMediaTask task, TextStreamCancellationControl control) {
        StringBuilder aggregated = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        AtomicBoolean streamFailed = new AtomicBoolean(false);
        AtomicReference<String> errRef = new AtomicReference<>();
        AtomicReference<Map<String, Object>> capturedUsage = new AtomicReference<>();
        AtomicReference<MediaTextGenerateRequest.TextMessageItem> toolMessage = new AtomicReference<>();
        try {
            modelOutboundResourcePreparer.prepare(modelConfig, request);
            markTextProviderCallStarted(task, request);
            // 业务含义：阻塞读 SSE，增量同时推 sink 与本地聚合，供落库全文。
            client.streamChat(modelConfig, request, new TextStreamCallbacks() {
                @Override
                public void onResponseBody(AutoCloseable responseBody) {
                    control.attachResponseBody(responseBody);
                }

                @Override
                public void onDelta(String textDelta) {
                    if (isTextStreamCancellationRequested(task.getId(), control)) {
                        throw new CancellationException("用户停止");
                    }
                    if (textDelta != null) {
                        aggregated.append(textDelta);
                        if (!control.updatePartialIfActive(aggregated.toString())) {
                            throw new CancellationException("用户停止");
                        }
                        safeNotifyTextSink(task.getId(), "delta", () -> sink.onDelta(textDelta));
                    }
                }

                @Override
                public void onReasoningDelta(String reasoningDelta) {
                    if (reasoningDelta != null && Boolean.TRUE.equals(request.getIncludeReasoning())) {
                        safeNotifyTextSink(task.getId(), "reasoning_delta",
                                () -> sink.onReasoningDelta(reasoningDelta));
                    }
                }

                @Override
                public void onToolMessage(MediaTextGenerateRequest.TextMessageItem message) {
                    if (!sink.supportsToolMessages()) throw new ServiceException("当前入口不支持工具");
                    toolMessage.set(message);
                }

                @Override
                public void onSseDataLine(String dataLine) {
                    if (raw.length() < TEXT_STREAM_RAW_MAX_CHARS && dataLine != null) {
                        int room = TEXT_STREAM_RAW_MAX_CHARS - raw.length();
                        if (room <= 0) {
                            return;
                        }
                        String part = dataLine.length() <= room ? dataLine : dataLine.substring(0, room);
                        raw.append(part).append('\n');
                    }
                }

                @Override
                public void onError(String message, Throwable cause) {
                    streamFailed.set(true);
                    // 已归一化的恢复动作不能在 SSE 回调转为短文案时丢失。
                    if (cause instanceof ServiceException serviceException
                            && TaskErrorSnapshot.read(serviceException.getTaskErrorJson()) != null) {
                        task.setErrorDetailJson(serviceException.getTaskErrorJson());
                    }
                    String safeMessage = ProviderErrorSanitizer.safeMessage(message, "上游生成失败");
                    errRef.set(safeMessage);
                    log.error("文本流式上游错误, taskId={}, errorType={}", task.getId(),
                            cause == null ? "upstream" : cause.getClass().getSimpleName());
                }

                @Override
                public void onComplete() {
                }

                @Override
                public void onUsage(Map<String, Object> usage) {
                    capturedUsage.updateAndGet(current -> ProviderUsageSupport.merge(current, usage));
                    Object inputTokens = usage == null ? null : usage.get("input_tokens");
                    Object outputTokens = usage == null ? null : usage.get("output_tokens");
                    Object totalTokens = usage == null ? null : usage.get("total_tokens");
                    Object promptTokens = usage == null ? null : usage.get("prompt_tokens");
                    Object completionTokens = usage == null ? null : usage.get("completion_tokens");
                    // 上游可能只返回 prompt/completion 或 input/output，total 缺失时按"输入+输出"自动兜底
                    Object effectiveTotal = totalTokens != null
                            ? totalTokens
                            : sumTokensSafely(promptTokens != null ? promptTokens : inputTokens,
                                              completionTokens != null ? completionTokens : outputTokens);
                    log.info("文本LLM调用完成usage: taskId={}, bizTaskId={}, bizTaskType={}, modelCode={}, modelName={}, prompt_tokens={}, completion_tokens={}, input_tokens={}, output_tokens={}, total_tokens={}, usageSource=PROVIDER_REAL_USAGE",
                            task.getId(), task.getBizTaskId(), task.getBizTaskType(),
                            modelConfig == null ? null : modelConfig.getModelCode(),
                            task.getModelName(),
                            promptTokens != null ? promptTokens : inputTokens,
                            completionTokens != null ? completionTokens : outputTokens,
                            inputTokens, outputTokens, effectiveTotal);
                }
            });

            String rawSnapshot = raw.length() >= TEXT_STREAM_RAW_MAX_CHARS
                ? raw + "\n...[truncated]"
                : raw.toString();

            if (streamFailed.get()) {
                if (StringUtils.isBlank(aggregated) && StringUtils.isNotBlank(errRef.get())
                        && isEmptyContentError(errRef.get())) {
                    markEmptyTextResult(task);
                }
                boolean terminalWon = finishTextStreamFailure(task, errRef.get(), null,
                        MediaTaskStatus.PENDING.name(), capturedUsage.get());
                if (terminalWon) {
                    releaseConcurrency(task);
                    safeNotifyTextSink(task.getId(), "failed",
                            () -> sink.onFailed(StringUtils.defaultIfBlank(errRef.get(), "生成失败")));
                    publishTextTaskCompletedSafely(task);
                }
                return;
            }

            // 业务含义：流正常结束，只写最终文本结果到 resultText，不存储原始SSE流到 responseJson。
            // 业务成功校验：流式返回空文本,视为 RESULT_INVALID
            String aggregatedText = aggregated.toString();
            TaskErrorResult textValidation = toolMessage.get() == null ? TaskSuccessValidator.validateText(aggregatedText) : null;
            if (textValidation != null) {
                log.error("文本流式任务状态为成功但正文为空，降级为 FAILED, taskId={}", task.getId());
                task.setErrorDetailJson(TaskErrorSnapshot.write(textValidation));
                String failureMessage = textValidation.getUserMessage();
                boolean terminalWon = finishTextStreamFailure(task, failureMessage, null,
                        MediaTaskStatus.PENDING.name(), capturedUsage.get());
                if (terminalWon) {
                    releaseConcurrency(task);
                    safeNotifyTextSink(task.getId(), "failed", () -> sink.onFailed(textValidation.getUserMessage()));
                    publishTextTaskCompletedSafely(task);
                }
                return;
            }
            // 上游未返回 usage 时显式标记，便于结算阶段排查 token 来源
            if (capturedUsage.get() == null) {
                log.info("文本LLM调用完成usage: taskId={}, bizTaskId={}, bizTaskType={}, modelCode={}, modelName={}, 未返回 usage, usageSource=PROVIDER_NO_USAGE",
                        task.getId(), task.getBizTaskId(), task.getBizTaskType(),
                        modelConfig == null ? null : modelConfig.getModelCode(),
                        task.getModelName());
            }
            if (toolMessage.get() != null) {
                toolMessage.get().setContent(aggregatedText);
                com.aid.media.provider.TextToolResultSupport.capture(task, toolMessage.get());
            }
            boolean terminalWon = finishTextStreamSuccess(task, aggregatedText, capturedUsage.get());
            if (terminalWon) {
                releaseConcurrency(task);
                if (toolMessage.get() != null) safeNotifyTextSink(task.getId(), "tool_message", () -> sink.onToolMessage(toolMessage.get()));
                safeNotifyTextSink(task.getId(), "done", () -> sink.onDone(aggregatedText, rawSnapshot));
                publishTextTaskCompletedSafely(task);
            }
        } catch (CancellationException ex) {
            log.info("文本流任务已停止, taskId={}", task.getId());
        } catch (IOException ex) {
            log.error("文本流式 IO 异常, taskId={}, errorType={}", task.getId(),
                    ex.getClass().getSimpleName());
            task.setErrorDetailJson(TaskErrorSnapshot.write(ErrorNormalizer.normalize(
                    String.valueOf(task.getId()), null, task.getModelName(), ex)));
            boolean terminalWon = finishTextStreamFailure(task,
                    ProviderErrorSanitizer.safeMessage(ex.getMessage(), "上游连接失败"), null,
                    MediaTaskStatus.PENDING.name(), capturedUsage.get());
            if (terminalWon) {
                releaseConcurrency(task);
                safeNotifyTextSink(task.getId(), "failed", () -> sink.onFailed(TaskErrorSnapshot.fromTask(task).getUserMessage()));
                publishTextTaskCompletedSafely(task);
            }
        } catch (Exception ex) {
            log.error("文本流式编排失败, taskId={}, errorType={}", task.getId(),
                    ex.getClass().getSimpleName());
            String evidenceSnapshot = TextFailureBillingPolicy.snapshotFrom(ex);
            if (evidenceSnapshot != null) {
                task.setErrorDetailJson(evidenceSnapshot);
            } else if (TaskErrorSnapshot.read(task.getErrorDetailJson()) == null) {
                task.setErrorDetailJson(TaskErrorSnapshot.write(ErrorNormalizer.normalize(
                        String.valueOf(task.getId()), null, task.getModelName(), ex)));
            }
            boolean terminalWon = finishTextStreamFailure(task,
                    ProviderErrorSanitizer.safeMessage(ex.getMessage(), "生成编排失败"), null,
                    MediaTaskStatus.PENDING.name(), capturedUsage.get());
            if (terminalWon) {
                releaseConcurrency(task);
                safeNotifyTextSink(task.getId(), "failed", () -> sink.onFailed(TaskErrorSnapshot.fromTask(task).getUserMessage()));
                publishTextTaskCompletedSafely(task);
            }
        } finally {
            activeTextStreams.remove(task.getId(), control);
        }
    }

    @Override
    public MediaTaskResponse cancelTextTask(Long taskId, Long userId) {
        if (taskId == null || taskId <= 0 || userId == null || userId <= 0) {
            throw new ServiceException("任务参数错误");
        }
        AidMediaTask task = aidMediaTaskMapper.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, taskId)
                .eq(AidMediaTask::getUserId, userId)
                .eq(AidMediaTask::getMediaType, MediaType.TEXT.name())
                .last("LIMIT 1"));
        if (task == null) {
            throw new ServiceException("任务不存在");
        }
        if (MediaTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            publishTextCancelSignalSafely(taskId);
            cancelLocalTextStream(taskId);
            return toResponse(aidMediaTaskMapper.selectById(taskId));
        }
        if (!MediaTaskStatus.PENDING.name().equals(task.getStatus())
                && !MediaTaskStatus.QUEUED.name().equals(task.getStatus())) {
            throw new ServiceException("任务已结束");
        }

        String expectedStatus = task.getStatus();
        TextStreamCancellationControl control = activeTextStreams.get(taskId);
        String partialText = control == null ? task.getResultText() : control.partialText();
        task.setStatus(MediaTaskStatus.CANCELLED.name());
        task.setResultText(StrUtil.isBlank(partialText) ? null : partialText);
        task.setResponseJson(null);
        task.setErrorMessage("用户停止");
        boolean terminalWon = finishTextStreamTerminal(task, expectedStatus, false, null);
        if (!terminalWon) {
            AidMediaTask current = aidMediaTaskMapper.selectById(taskId);
            if (current == null) {
                throw new ServiceException("任务不存在");
            }
            if (!MediaTaskStatus.CANCELLED.name().equals(current.getStatus())) {
                if (MediaTaskStatus.PENDING.name().equals(current.getStatus())
                        || MediaTaskStatus.QUEUED.name().equals(current.getStatus())) {
                    return cancelTextTask(taskId, userId);
                }
                throw new ServiceException("任务已结束");
            }
            return toResponse(current);
        }

        publishTextCancelSignalSafely(taskId);
        cancelLocalTextStream(taskId);
        if (MediaTaskStatus.PENDING.name().equals(expectedStatus)) {
            releaseConcurrency(task);
        } else {
            drainQueue();
        }
        publishTextTaskCompletedSafely(task);
        AidMediaTask current = aidMediaTaskMapper.selectById(taskId);
        return toResponse(current == null ? task : current);
    }

    private boolean isTextStreamCancellationRequested(Long taskId, TextStreamCancellationControl control) {
        if (control.isCancelled()) {
            return true;
        }
        if (!control.shouldCheckPersistentCancellation(TEXT_CANCEL_PERSISTENCE_CHECK_NANOS)) {
            return false;
        }
        AidMediaTask current = aidMediaTaskMapper.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                .select(AidMediaTask::getStatus)
                .eq(AidMediaTask::getId, taskId)
                .last("LIMIT 1"));
        if (current != null && MediaTaskStatus.CANCELLED.name().equals(current.getStatus())) {
            control.cancel();
            return true;
        }
        return false;
    }

    private void publishTextCancelSignalSafely(Long taskId) {
        try {
            RedisUtils.publish(TEXT_CANCEL_CHANNEL, taskId);
        } catch (RuntimeException publishError) {
            log.warn("文本任务取消信号发布失败，worker 将通过 DB 终态复核停止, taskId={}, errorType={}",
                    taskId, publishError.getClass().getSimpleName());
        }
    }

    private void cancelLocalTextStream(Long taskId) {
        TextStreamCancellationControl control = activeTextStreams.get(taskId);
        if (control == null) {
            return;
        }
        String partialText = control.cancelAndGetPartial();
        if (StrUtil.isNotBlank(partialText)) {
            int enriched = aidMediaTaskMapper.update(null, new LambdaUpdateWrapper<AidMediaTask>()
                    .eq(AidMediaTask::getId, taskId)
                    .eq(AidMediaTask::getStatus, MediaTaskStatus.CANCELLED.name())
                    .set(AidMediaTask::getResultText, partialText)
                    .set(AidMediaTask::getUpdateBy, "system")
                    .set(AidMediaTask::getUpdateTime, new Date()));
            if (enriched == 1) {
                AidMediaTask cancelled = aidMediaTaskMapper.selectById(taskId);
                if (cancelled != null) {
                    publishTextTaskCompletedSafely(cancelled);
                }
            }
        }
    }

    private boolean finishTextStreamSuccess(AidMediaTask task, String resultText,
                                            Map<String, Object> usage) {
        task.setStatus(MediaTaskStatus.SUCCEEDED.name());
        task.setResultText(resultText);
        if (task.getLiveTextTurn() == null) task.setResponseJson(null);
        task.setErrorMessage(null);
        return finishTextStreamTerminal(task, MediaTaskStatus.PENDING.name(), true, usage);
    }

    private boolean finishTextStreamFailure(AidMediaTask task, String errorMessage, String responseJson,
                                            String expectedStatus, Map<String, Object> usage) {
        task.setStatus(MediaTaskStatus.FAILED.name());
        if (TaskErrorSnapshot.read(task.getErrorDetailJson()) == null) {
            task.setErrorDetailJson(TaskErrorSnapshot.write(ErrorNormalizer.normalize(
                    String.valueOf(task.getId()), null, task.getModelName(), -1, errorMessage)));
        }
        task.setErrorMessage(ProviderErrorSanitizer.safeMessage(errorMessage, "生成失败"));
        task.setResponseJson(responseJson);
        task.setResultText(null);
        return finishTextStreamTerminal(task, expectedStatus, false, usage);
    }

    /**
     * PENDING/QUEUED 到终态的唯一竞争点。CAS、结退款和终态字段在同一独立事务提交；
     * 输家不释放槽、不通知观察者、不发布事件，避免补偿线程与长流双重终态。
     */
    private boolean finishTextStreamTerminal(AidMediaTask task, String expectedStatus,
                                             boolean businessSucceeded, Map<String, Object> usage) {
        task.setErrorDetailJson(TextFailureBillingPolicy.withObservedUsage(task.getErrorDetailJson(), usage));
        return Boolean.TRUE.equals(requiresNewTxTemplate.execute(status -> {
            LambdaUpdateWrapper<AidMediaTask> cas = new LambdaUpdateWrapper<>();
            cas.eq(AidMediaTask::getId, task.getId());
            cas.eq(AidMediaTask::getStatus, expectedStatus);
            cas.set(AidMediaTask::getStatus, task.getStatus());
            cas.set(AidMediaTask::getUpdateBy,
                    task.getUserId() == null ? "" : String.valueOf(task.getUserId()));
            cas.set(AidMediaTask::getUpdateTime, new Date());
            if (aidMediaTaskMapper.update(null, cas) != 1) {
                log.info("文本流终态CAS未获胜, taskId={}, expected={}, target={}",
                        task.getId(), expectedStatus, task.getStatus());
                return false;
            }

            boolean hasProviderUsage = ProviderUsageSupport.hasAnyProviderUsage(usage);
            boolean billingExempt = task.getBillingStatus() == null;
            boolean billingWon;
            if (billingExempt) {
                if (hasProviderUsage) {
                    persistUsageForExemptTask(task, usage);
                }
                billingWon = true;
            } else {
                boolean settleProviderCall = TextFailureBillingPolicy.shouldSettle(
                        businessSucceeded, task.getUpstreamAcceptTime() != null,
                        task.getProtocol(), task.getErrorDetailJson(), usage);
                billingWon = settleProviderCall
                        ? billingFacadeService.settleBilling(task, usage)
                        : billingFacadeService.refundBilling(task);
                if (!businessSucceeded && settleProviderCall) {
                    log.info("文本Provider调用后失败，按真实usage或预冻结上限保守结算: taskId={}", task.getId());
                }
            }
            if (!billingWon) {
                // 任务状态 CAS 已获胜时仍需保留本线程正文/错误；仅同步账务快照，避免覆盖业务结果。
                AidMediaTask persisted = aidMediaTaskMapper.selectById(task.getId());
                if (persisted != null) {
                    task.setBillingStatus(persisted.getBillingStatus());
                    task.setFrozenAmount(persisted.getFrozenAmount());
                }
            }
            updateTaskWithPayloadArchive(task);
            return true;
        }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaTaskResponse queryTask(Long taskId, boolean pollRemote) {
        AidMediaTask task = Optional.ofNullable(aidMediaTaskMapper.selectById(taskId))
            .orElseThrow(() -> new ServiceException("任务不存在"));
        Long currentUserId = getCurrentUserIdSafe();
        if (currentUserId != null && task.getUserId() != null
            && !currentUserId.equals(task.getUserId())) {
            log.info("queryTask 归属校验失败, currentUserId={}, taskUserId={}, taskId={}", currentUserId, task.getUserId(), taskId);
            throw new ServiceException("任务不存在");
        }
        // pollRemote 参数仅作兼容保留，不触发真实上游请求；
        // 上游查询统一由调度中心 TaskDispatchService 执行。
        return toResponse(task);
    }

    @Override
    public MediaTaskResponse queryTaskLocal(Long taskId) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        return task == null ? null : toResponse(task);
    }

    @Override
    public MediaTaskResponse queryTaskRefresh(Long taskId) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }
        // PROCESSING 且有 providerTaskId 时刷新远端，但不累加 retry_count
        if (MediaTaskStatus.PROCESSING.name().equals(task.getStatus())
            && StringUtils.isNotBlank(task.getProviderTaskId())) {
            refreshProcessingTask(task, true, false);
        }
        // SUCCEEDED 但 oss_url 为空时立即触发一次 OSS 持久化修复，
        // 不再让业务层等待 60s 定时补偿。常见于上游产物 URL 抖动 / persistOssIfNeeded 首次失败。
        if (MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
            && StringUtils.isBlank(task.getOssUrl())
            && StringUtils.isNotBlank(task.getOriginUrl())) {
            try {
                log.info("queryTaskRefresh 触发 OSS 持久化即时修复, taskId={}", taskId);
                persistOssIfNeeded(task);
                if (StringUtils.isNotBlank(task.getOssUrl())) {
                    updateTaskWithPayloadArchive(task);
                    // 发布 OSS 就绪事件，通知业务侧回填 oss_url（替换 originUrl）。
                    publishOssPersistedEventSafely(task);
                }
            } catch (Exception ex) {
                log.warn("queryTaskRefresh OSS 持久化即时修复失败, taskId={}, err={}", taskId, ex.getMessage());
            }
        }
        return toResponse(task);
    }

    /**
     * 批量提交图片/视频生成：同事务内完成全部任务落库与扣费，事务提交后再异步逐条调上游 submit。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaBatchGenerateResponse batchGenerate(MediaBatchGenerateRequest request) {
        if (!LoginHelper.isLogin()) {
            log.info("batch media generate rejected: not login");
            throw new ServiceException("请先登录");
        }
        if (request == null || CollectionUtil.isEmpty(request.getItems())) {
            throw new ServiceException("任务项不能为空");
        }
        if (request.getItems().size() > maxBatchMediaSize) {
            throw new ServiceException("单次最多" + maxBatchMediaSize + "个任务");
        }
        Long batchUserId = LoginHelper.getUserId();
        String batchId = IdUtil.fastSimpleUUID();
        Long userId = batchUserId;
        List<PreparedBatchUnit> prepared = new ArrayList<>(request.getItems().size());
        int ordinal = 0;
        for (MediaBatchGenerateRequest.BatchGenerateItem item : request.getItems()) {
            prepared.add(buildPreparedBatchUnit(item, batchId, userId, ordinal, request));
            ordinal++;
        }
        for (PreparedBatchUnit unit : prepared) {
            aidMediaTaskMapper.insert(unit.task());
            com.aid.diagnostics.DiagnosticCapture.rememberTask(unit.task());
            billingFacadeService.prepareBilling(unit.task(), unit.modelConfig(), unit.billingInput());
            // 预冻结修改了 billingStatus / billingTraceId / frozenAmount，必须回写才能被后续 settle/refund 读到。
            fillUpdateInfo(unit.task());
            aidMediaTaskMapper.updateById(unit.task());
        }
        List<Long> taskIds = prepared.stream().map(u -> u.task().getId()).toList();
        Runnable scheduleSubmits = () -> {
            for (Long taskId : taskIds) {
                //      线程池拒绝（队列满 / 应用关闭）时该条保持 QUEUED（未占槽，无需释放），
                //      用 try/catch 兜住以免中断 for 循环导致后续 taskId 全部漏调度；
                //      未拉起的 QUEUED 由后续任务完成触发的 drainQueue 或 watchdog 兜底。
                try {
                    threadPoolTaskExecutor.execute(() -> submitSingleTaskAsync(taskId));
                } catch (Exception rejectEx) {
                    log.warn("批量任务提交线程池被拒绝, 保留QUEUED 等待后续调度, taskId={}", taskId, rejectEx);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scheduleSubmits.run();
                }
            });
        } else {
            scheduleSubmits.run();
        }
        List<MediaTaskResponse> responses = prepared.stream().map(u -> toResponse(u.task())).toList();
        return MediaBatchGenerateResponse.builder()
            .batchId(batchId)
            .totalCount(responses.size())
            .tasks(responses)
            .build();
    }

    /**
     * 按 batchId 查询当前用户该批任务汇总；可选对 PROCESSING 任务联动上游刷新。
     */
    @Override
    public MediaBatchProgressResponse queryBatchProgress(MediaBatchProgressRequest request) {
        if (!LoginHelper.isLogin()) {
            log.info("batch media progress rejected: not login");
            throw new ServiceException("请先登录");
        }
        if (request == null || StringUtils.isBlank(request.getBatchId())) {
            throw new ServiceException("批次不能为空");
        }
        String batchId = request.getBatchId().trim();
        boolean pollRemote = request.getPollRemote() == null || Boolean.TRUE.equals(request.getPollRemote());
        Long userId = LoginHelper.getUserId();
        List<AidMediaTask> list = aidMediaTaskMapper.selectList(
            new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getBatchId, batchId)
                .eq(AidMediaTask::getUserId, userId)
                .orderByAsc(AidMediaTask::getId)
        );
        if (CollectionUtil.isEmpty(list)) {
            throw new ServiceException("批次不存在");
        }
        // 上游查询统一由调度中心 TaskDispatchService 执行；
        // pollRemote 参数仅作兼容保留，不触发真实上游请求。
        int total = list.size();
        int succeeded = 0;
        int failed = 0;
        for (AidMediaTask task : list) {
            if (MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())) {
                succeeded++;
            } else if (MediaTaskStatus.FAILED.name().equals(task.getStatus())) {
                failed++;
            }
        }
        int completed = succeeded + failed;
        int progressPercent = total > 0 ? completed * 100 / total : 0;
        boolean allDone = completed == total;
        List<MediaTaskResponse> responses = list.stream().map(this::toResponse).toList();
        return MediaBatchProgressResponse.builder()
            .batchId(batchId)
            .totalCount(total)
            .completedCount(completed)
            .succeededCount(succeeded)
            .failedCount(failed)
            .progressPercent(progressPercent)
            .allDone(allDone)
            .tasks(responses)
            .build();
    }

    /**
     * 定时补偿轮询入口（供 Scheduler 调用）
     * @param batchSize 本轮补偿最大处理条数，<=0 时使用默认值
     * @return 本轮实际发起上游状态对账的任务数
     */
    @Override
    public int compensateProcessingTasks(int batchSize) {
        int fetchLimit = batchSize > 0 ? batchSize : DEFAULT_COMPENSATION_BATCH_SIZE;
        Date now = new Date();
        Date updateBefore = new Date(now.getTime() - COMPENSATION_MIN_SCAN_GAP_SECONDS * 1000L);
        List<AidMediaTask> candidates = aidMediaTaskMapper.selectTasksForCompensation(
            MediaTaskStatus.PROCESSING.name(),
            updateBefore,
            maxCompensationRetry,
            fetchLimit
        );
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (AidMediaTask task : candidates) {
            if (!isCompensationDue(task, now)) {
                continue;
            }
            try {
                refreshProcessingTask(task, false);
                handled++;
            } catch (Exception ex) {
                log.warn("media compensation poll failed, taskId={}, error={}", task.getId(), ex.getMessage());
            }
        }
        return handled;
    }

    /**
     * 排队任务兜底拉起（供 Scheduler 调用）。
     * 正常路径由任务完成事件触发 drainQueue；但"服务重启后无在途任务 / 完成事件丢失"时 QUEUED 任务无人拉起，
     * 会一直滞留直到被 closeStaleUnsubmittedTasks 按僵尸失败退款。本方法定时扫描 QUEUED 任务，
     * 逐条尝试抢占并发坑位后走既有 drainQueueSubmit 链路拉起（CAS 防重，与事件驱动路径并存安全）。
     *
     * @param batchSize 单轮最多扫描的排队任务条数，&lt;=0 时使用默认值
     * @return 本轮实际拉起的任务条数
     */
    @Override
    public int drainQueuedCompensate(int batchSize) {
        int fetchLimit = batchSize > 0 ? batchSize : DEFAULT_COMPENSATION_BATCH_SIZE;
        // 特别标注：本查询只取拉起所需的最小字段（id + userId + modelName），新增依赖字段时须同步补充 select。
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getModelName);
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        wrapper.and(w -> w.isNull(AidMediaTask::getNextPollTime)
                .or().le(AidMediaTask::getNextPollTime, new Date()));
        // 与 drainQueue 相同的 FIFO 顺序，保证兜底拉起不打乱排队位次语义。
        wrapper.orderByAsc(AidMediaTask::getCreateTime, AidMediaTask::getId);
        wrapper.last("limit " + fetchLimit);
        List<AidMediaTask> candidates = aidMediaTaskMapper.selectList(wrapper);
        if (CollectionUtil.isEmpty(candidates)) {
            return 0;
        }
        int drained = 0;
        for (AidMediaTask candidate : candidates) {
            // 逐条抢占四维并发坑位；用户/模型/供应商任一维已满则跳过继续后面的候选（与 drainQueue 跳过语义一致）。
            boolean canRun = concurrencyLimiter.tryAcquire(candidate.getUserId(), candidate.getModelName());
            if (!canRun) {
                continue;
            }
            Long taskId = candidate.getId();
            Long userId = candidate.getUserId();
            String modelName = candidate.getModelName();
            try {
                // 复用既有拉起链路：drainQueueSubmit 内部 CAS QUEUED→PENDING，竞争失败会自行释放坑位。
                threadPoolTaskExecutor.execute(() -> drainQueueSubmit(taskId, userId, modelName));
                drained++;
            } catch (Exception rejectEx) {
                // 线程池拒绝：释放刚抢占的坑位并停止本轮，任务保持 QUEUED 等下一轮兜底。
                log.warn("drainQueuedCompensate 提交线程池被拒绝, 释放并发槽并保留 QUEUED, taskId={}", taskId, rejectEx);
                concurrencyLimiter.release(userId, modelName);
                break;
            }
        }
        if (drained > 0) {
            log.info("drainQueuedCompensate 兜底拉起排队任务 {} 条", drained);
        }
        return drained;
    }

    @Override
    public int cancelQueuedVideoTasksForParent(Long parentTaskId, Long userId) {
        if (Objects.isNull(parentTaskId) || parentTaskId <= 0
                || Objects.isNull(userId) || userId <= 0) {
            return 0;
        }
        long lowerBizTaskId;
        long upperBizTaskId;
        try {
            lowerBizTaskId = Math.multiplyExact(parentTaskId,
                    com.aid.rps.queue.MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
            upperBizTaskId = Math.addExact(lowerBizTaskId,
                    com.aid.rps.queue.MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR);
        } catch (ArithmeticException overflow) {
            log.error("分镜视频父任务关联ID溢出: parentTaskId={}", parentTaskId, overflow);
            return 0;
        }

        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(AidMediaTask::getId);
        wrapper.eq(AidMediaTask::getUserId, userId);
        wrapper.eq(AidMediaTask::getMediaType, MediaType.VIDEO.name());
        wrapper.eq(AidMediaTask::getBizTaskType, STORYBOARD_VIDEO_GENERATE_BIZ_TYPE);
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        wrapper.and(scope -> scope.eq(AidMediaTask::getParentTaskId, parentTaskId)
                .or(legacy -> legacy.isNull(AidMediaTask::getParentTaskId)
                        .ge(AidMediaTask::getBizTaskId, lowerBizTaskId)
                        .lt(AidMediaTask::getBizTaskId, upperBizTaskId)));
        wrapper.orderByAsc(AidMediaTask::getId);
        List<AidMediaTask> queuedTasks = aidMediaTaskMapper.selectList(wrapper);
        int cancelledCount = 0;
        for (AidMediaTask queuedTask : queuedTasks) {
            if (taskCompletionService.cancelQueuedTask(
                    queuedTask.getId(), userId, "用户取消，未提交")) {
                cancelledCount++;
            }
        }
        log.info("分镜视频排队子任务取消完成: parentTaskId={}, queuedClosed={}",
                parentTaskId, cancelledCount);
        return cancelledCount;
    }

    /**
     * OSS 持久化补偿：扫描成功但 ossUrl 为空的任务，重试下载+上传+业务回填。
     *
     * @param batchSize 本轮最大处理条数
     * @return 本轮实际重试成功的条数
     */
    @Override
    public int compensateOssPersistence(int batchSize) {
        int fetchLimit = batchSize > 0 ? batchSize : DEFAULT_COMPENSATION_BATCH_SIZE;
        Date readyBefore = new Date(System.currentTimeMillis() - OSS_COMPENSATION_READY_GAP_SECONDS * 1000L);
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        // 特别标注：只取 OSS 下载、结果登记、回填和事件所需字段，禁止重新查询任务表的大文本全列。
        wrapper.select(
            AidMediaTask::getId,
            AidMediaTask::getUserId,
            AidMediaTask::getMediaType,
            AidMediaTask::getStatus,
            AidMediaTask::getModelName,
            AidMediaTask::getProtocol,
            AidMediaTask::getProviderRouteSnapshotJson,
            AidMediaTask::getOriginUrl,
            AidMediaTask::getOssUrl,
            AidMediaTask::getOutputDurationSeconds,
            AidMediaTask::getRequestJson,
            AidMediaTask::getCallbackRecordId,
            AidMediaTask::getCallbackCategory
        )
            // oss_pending 是数据库虚拟生成列，配合 (oss_pending, update_time, id) 索引直接定位极少量候选。
            .eq(AidMediaTask::getOssPending, 1)
            .le(AidMediaTask::getUpdateTime, readyBefore)
            .orderByAsc(AidMediaTask::getUpdateTime, AidMediaTask::getId)
            .last("limit " + fetchLimit);
        List<AidMediaTask> candidates = aidMediaTaskMapper.selectList(wrapper);
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int handled = 0;
        for (AidMediaTask task : candidates) {
            try {
                persistOssIfNeeded(task);
                if (StringUtils.isBlank(task.getOssUrl())) {
                    // 失败也更新时间，保证至少等待 60 秒再重试，避免永久失败任务每轮都被立即扫中。
                    updateOssCompensationFailure(task);
                    log.warn("oss persistence compensation still blank, taskId={}", task.getId());
                    continue;
                }
                int updated = updateOssCompensationSuccess(task);
                if (updated == 0) {
                    log.info("oss persistence compensation CAS skipped, taskId={}", task.getId());
                    continue;
                }
                tryInvokeGenResultCallback(task);
                //    监听该事件后，才会把 audio_url 回填为 oss_url，避免回填上游临时签名。
                publishOssPersistedEventSafely(task);
                handled++;
                log.info("oss persistence compensation succeeded, taskId={}, ossUrl={}", task.getId(), task.getOssUrl());
            } catch (Exception ex) {
                // 单条异常不阻断批次。
                log.error("oss persistence compensation failed, taskId={}, error={}",
                    task.getId(), ex.getMessage(), ex);
            }
        }
        return handled;
    }

    /**
     * 定向回写 OSS 补偿成功字段，避免 updateById 把查询出的 request_json 等大字段再次写回。
     */
    private int updateOssCompensationSuccess(AidMediaTask task) {
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload = fillUpdateInfo(task);
        LambdaUpdateWrapper<AidMediaTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidMediaTask::getId, task.getId());
        updateWrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.SUCCEEDED.name());
        updateWrapper.isNull(AidMediaTask::getOssUrl);
        updateWrapper.set(AidMediaTask::getOssUrl, task.getOssUrl());
        // 补偿路径解析出的成片时长同步落库，缺失时不覆盖既有值
        if (Objects.nonNull(task.getOutputDurationSeconds())) {
            updateWrapper.set(AidMediaTask::getOutputDurationSeconds, task.getOutputDurationSeconds());
        }
        updateWrapper.set(AidMediaTask::getRequestJson, task.getRequestJson());
        updateWrapper.set(AidMediaTask::getResponseJson, task.getResponseJson());
        updateWrapper.set(AidMediaTask::getErrorMessage, null);
        updateWrapper.set(AidMediaTask::getUpdateBy, task.getUpdateBy());
        updateWrapper.set(AidMediaTask::getUpdateTime, task.getUpdateTime());
        int rows = aidMediaTaskMapper.update(null, updateWrapper);
        if (rows > 0) {
            mediaTaskArchiveService.archiveAfterCommit(preparedPayload);
        }
        return rows;
    }

    /**
     * 记录本轮 OSS 补偿失败并刷新重试时间，不改任务成功终态和计费状态。
     */
    private void updateOssCompensationFailure(AidMediaTask task) {
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload = fillUpdateInfo(task);
        String errorMessage = StringUtils.defaultIfBlank(task.getErrorMessage(), "OSS持久化失败");
        LambdaUpdateWrapper<AidMediaTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidMediaTask::getId, task.getId());
        updateWrapper.eq(AidMediaTask::getOssPending, 1);
        updateWrapper.set(AidMediaTask::getRequestJson, task.getRequestJson());
        updateWrapper.set(AidMediaTask::getResponseJson, task.getResponseJson());
        updateWrapper.set(AidMediaTask::getErrorMessage,
            MediaTaskPayloadSanitizer.sanitizeForStorage(errorMessage));
        updateWrapper.set(AidMediaTask::getUpdateBy, task.getUpdateBy());
        updateWrapper.set(AidMediaTask::getUpdateTime, task.getUpdateTime());
        int rows = aidMediaTaskMapper.update(null, updateWrapper);
        if (rows > 0) {
            mediaTaskArchiveService.archiveAfterCommit(preparedPayload);
        }
    }

    /**
     * 供 {@link TaskDispatchService} 轮询终态后调用的 OSS 持久化兜底入口。
     * 同步执行一次下载 → 上传 OSS → DB 回写，成功后发布 {@link MediaTaskOssPersistedEvent}，
     * 让 AudioTaskEventListener 等业务侧监听器用 oss_url 而非 origin_url 回填业务表。
     *
     * @param taskId aid_media_task.id
     * @return true 表示 oss_url 就绪；false 表示仍需等待下一轮补偿
     */
    @Override
    public boolean ensureOssPersisted(Long taskId) {
        if (Objects.isNull(taskId)) {
            return false;
        }
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("ensureOssPersisted 任务不存在, taskId={}", taskId);
            return false;
        }
        if (!MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())) {
            return false;
        }
        if (StringUtils.isNotBlank(task.getOssUrl())) {
            publishOssPersistedEventSafely(task);
            return true;
        }
        if (StringUtils.isBlank(task.getOriginUrl())) {
            log.warn("ensureOssPersisted originUrl 为空, 待补偿, taskId={}", taskId);
            return false;
        }
        try {
            persistOssIfNeeded(task);
            if (StringUtils.isBlank(task.getOssUrl())) {
                log.warn("ensureOssPersisted 持久化仍未得到 ossUrl, 待补偿, taskId={}", taskId);
                return false;
            }
            updateTaskWithPayloadArchive(task);
            tryInvokeGenResultCallback(task);
            publishOssPersistedEventSafely(task);
            log.info("ensureOssPersisted 持久化成功, taskId={}, ossUrl present", taskId);
            return true;
        } catch (Exception ex) {
            log.error("ensureOssPersisted 持久化异常, taskId={}, err={}", taskId, ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * 发布 OSS 持久化完成事件；内部异常不影响主流程。
     */
    private void publishOssPersistedEventSafely(AidMediaTask task) {
        try {
            applicationEventPublisher.publishEvent(
                new MediaTaskOssPersistedEvent(this, task.getId(), task.getUserId()));
        } catch (Exception ex) {
            log.warn("publishOssPersistedEventSafely 发布事件失败, taskId={}, err={}",
                task.getId(), ex.getMessage());
        }
    }

    /**
     * 处理提交结果
     * @param task
     * @param submitResult
     */
    /**
     * @return true = 本线程赢得终态处理权（或无需计费），调用方应继续执行 OSS 回写等副作用；
     *         false = CAS 失败，调用方应跳过副作用，仅做 updateById 落库。
     */
    private boolean handleSubmitResult(AidMediaTask task, ProviderSubmitResult submitResult) {
        if (submitResult == null) {
            task.setStatus(MediaTaskStatus.FAILED.name());
            task.setErrorMessage("Provider submit result empty");
            markEmptyTextResult(task);
            return closeFailedSubmitBilling(task, null);
        }
        task.setProviderTaskId(submitResult.getProviderTaskId());
        if (TaskErrorSnapshot.read(submitResult.getErrorDetailJson()) != null) {
            task.setErrorDetailJson(TextFailureBillingPolicy.withObservedUsage(
                    submitResult.getErrorDetailJson(), submitResult.getUsage()));
        }
        task.setResponseJson(submitResult.getRawResponse());
        // 3a) Base64 直出模式：provider 在内存中解码并上传 OSS，只写 ossUrl，Base64 绝不落库，
        //     后续 persistOssIfNeeded / compensateOssPersistence 均因 originUrl 为空自动跳过。
        if (StringUtils.isNotBlank(submitResult.getOssUrl())) {
            log.info("media submit succeeded (base64→OSS), taskId={}, protocol={}", task.getId(), task.getProtocol());
            // 模型健康采集：同步直出成功（异步任务在 TaskCompletionService 终态收口处采集）
            recordSubmitHealthSuccess(task);
            task.setStatus(MediaTaskStatus.SUCCEEDED.name());
            // base64 图片已在 provider 内部转 OSS，响应体不再入库，避免 aid_media_task.response_json 保留图片占位内容
            task.setResponseJson(null);
            task.setOssUrl(submitResult.getOssUrl());
            persistSubmitResultManifest(task, submitResult, true);
            task.setErrorMessage(null);
            // 同步 TTS 音频时长（毫秒→秒向上取整，宁高勿低）：留档到任务表，
            // 供业务侧回填 aid_audio_record.duration_ms、对口型时长校验与合成对齐消费。
            if (Objects.nonNull(submitResult.getAudioDurationMs()) && submitResult.getAudioDurationMs() > 0) {
                task.setOutputDurationSeconds(
                        (long) Math.ceil(submitResult.getAudioDurationMs() / 1000.0));
            }
            // 三阶段计费：图片任务同步成功时必须传入真实张数 usageData，避免按预扣封顶。
            Map<String, Object> usageData = buildImageSettleUsageForSubmit(task, submitResult);
            boolean billingWon = billingFacadeService.settleBilling(task, usageData);
            syncTerminalFieldsIfNeeded(task, billingWon);
            releaseConcurrency(task);
            return billingWon;
        }
        // 3b) URL 落库模式：上游直接返回产物 URL，写 originUrl，后续 persistOssIfNeeded 下载转存 OSS。
        if (StringUtils.isNotBlank(submitResult.getDirectUrl())) {
            log.info("media submit succeeded (URL), taskId={}, protocol={}", task.getId(), task.getProtocol());
            task.setOriginUrl(submitResult.getDirectUrl());
            persistSubmitResultManifest(task, submitResult, false);
            if (isLayerDecomposition(protectedImageEditRequest(task))) {
                try {
                    validateLayerManifestBeforeSettlement(task);
                } catch (LayerOutputInvalidException invalid) {
                    task.setStatus(MediaTaskStatus.FAILED.name());
                    task.setErrorMessage(invalid.getMessage());
                    task.setErrorDetailJson(TaskErrorSnapshot.write(
                            TaskErrorResult.of(TaskErrorCode.RESULT_INVALID, invalid.getMessage())));
                    aidMediaResultMapper.delete(new LambdaQueryWrapper<AidMediaResult>()
                            .eq(AidMediaResult::getTaskId, task.getId()));
                    return closeFailedSubmitBilling(task, null);
                }
            }
            // 模型健康采集：同步直出成功
            recordSubmitHealthSuccess(task);
            task.setStatus(MediaTaskStatus.SUCCEEDED.name());
            task.setErrorMessage(null);
            // 三阶段计费：图片任务同步成功时必须传入真实张数 usageData，避免按预扣封顶。
            Map<String, Object> usageData = buildImageSettleUsageForSubmit(task, submitResult);
            boolean billingWon = billingFacadeService.settleBilling(task, usageData);
            syncTerminalFieldsIfNeeded(task, billingWon);
            releaseConcurrency(task);
            return billingWon;
        }
        // 不存储 responseJson（原始SSE流），只存最终文本结果到 resultText。
        if (StringUtils.isNotBlank(submitResult.getDirectText()) || submitResult.getToolMessage() != null) {
            task.setResponseJson(null);
            if (submitResult.getToolMessage() != null) {
                try {
                    submitResult.getToolMessage().setContent(submitResult.getDirectText());
                    com.aid.media.provider.TextToolResultSupport.capture(task, submitResult.getToolMessage());
                } catch (ServiceException invalidToolResult) {
                    task.setStatus(MediaTaskStatus.FAILED.name());
                    task.setErrorMessage("工具结果无法保存");
                    return closeFailedSubmitBilling(task, submitResult.getUsage());
                }
            }
            log.info("media text submit succeeded directly, taskId={}, protocol={}", task.getId(), task.getProtocol());
            // 模型健康采集：文本同步返回成功
            recordSubmitHealthSuccess(task);
            task.setStatus(MediaTaskStatus.SUCCEEDED.name());
            task.setResultText(submitResult.getDirectText());
            task.setErrorMessage(null);
            // 三阶段计费：任务成功，结算冻结金额（透传 provider 实际 token usage，
            // 与 streamText 路径保持一致；usage 缺失时由统一文本链路兜底）。
            boolean billingWon = billingFacadeService.settleBilling(task, submitResult.getUsage());
            syncTerminalFieldsIfNeeded(task, billingWon);
            releaseConcurrency(task);
            return billingWon;
        }
        if (StringUtils.isNotBlank(submitResult.getProviderTaskId())) {
            log.info("media submit accepted, taskId={}, providerTaskId={}", task.getId(), submitResult.getProviderTaskId());
            // 初始化调度策略：冻结策略快照、设置调度模式（WAIT_POLL/WAIT_CALLBACK）。
            AiModelConfigVo modelConfigForDispatch = resolveTaskModelConfig(task);
            if (modelConfigForDispatch != null) {
                taskDispatchService.initDispatchSchedule(task, modelConfigForDispatch);
            } else {
                // 模型配置缺失时回退到旧 PROCESSING 状态，兼容历史任务。
                task.setStatus(MediaTaskStatus.PROCESSING.name());
            }
            return true;
        }
        task.setStatus(MediaTaskStatus.FAILED.name());
        if (isEmptyTextSubmitResult(task, submitResult)) {
            markEmptyTextResult(task);
        }
        // 如果submitResult.rawResponse有值，则尝试从submitResult.rawResponse中解析错误信息
        if (StringUtils.isNotBlank(submitResult.getRawResponse())) {
            TaskErrorResult existing = TaskErrorSnapshot.read(task.getErrorDetailJson());
            TaskErrorResult recovery = existing == null
                    ? TokenDanceResponseMapper.recoveryErrorFromAudit(
                            task.getProtocol(), submitResult.getRawResponse()) : null;
            if (recovery != null) {
                task.setErrorDetailJson(TokenDanceResponseMapper.recoverySnapshot(
                        task.getProtocol(), submitResult.getRawResponse()));
            }
            TaskErrorResult normalized = existing != null ? existing : recovery;
            if (normalized == null) {
                normalized = ErrorNormalizer.normalize(Objects.toString(task.getId(), null), null,
                        task.getModelName(), -1, submitResult.getRawResponse());
                task.setErrorDetailJson(TaskErrorSnapshot.write(normalized));
            }
            task.setErrorMessage(normalized.getUserMessage());
            // 模型健康采集：提交被上游拒绝（上游返回了错误响应体，属于"上游返回错误"口径）
            modelHealthRecorder.recordFailure(task.getModelName(), task.getMediaType(), task.getErrorMessage());
            return closeFailedSubmitBilling(task, submitResult.getUsage());
        }
        task.setErrorMessage("上游未返回任务标识、URL或文本结果");
        // 模型健康采集：上游返回 200 但无有效结果，同属上游侧异常
        modelHealthRecorder.recordFailure(task.getModelName(), task.getMediaType(), task.getErrorMessage());
        return closeFailedSubmitBilling(task, submitResult.getUsage());
    }

    /** 同步图片协议也必须保存全部有序结果，不能只依赖主任务上的第 0 项。 */
    private void persistSubmitResultManifest(AidMediaTask task, ProviderSubmitResult submitResult,
                                             boolean alreadyPersisted) {
        if (task == null || submitResult == null || !MediaType.IMAGE.name().equals(task.getMediaType())) {
            return;
        }
        if (submitResult.getImageOutputs() != null && !submitResult.getImageOutputs().isEmpty()) {
            int index = 0;
            for (ImageOutputItem output : submitResult.getImageOutputs()) {
                String url = output.getUrl();
                if (StringUtils.isBlank(url)) throw new ServiceException("图片结果地址缺失");
                aidMediaResultMapper.upsertTaskResult(task.getId(), index, task.getMediaType(), url,
                        task.getUserId() == null ? "" : String.valueOf(task.getUserId()));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("width", output.getWidth());
                metadata.put("height", output.getHeight());
                metadata.put("zIndex", output.getZIndex());
                metadata.put("name", output.getName());
                metadata.put("description", output.getDescription());
                metadata.put("boundingBox", output.getBoundingBox());
                metadata.put("outputFormat", output.getOutputFormat());
                aidMediaResultMapper.update(null, new LambdaUpdateWrapper<AidMediaResult>()
                        .eq(AidMediaResult::getTaskId, task.getId())
                        .eq(AidMediaResult::getResultIndex, index)
                        .set(AidMediaResult::getMetadataJson, com.alibaba.fastjson2.JSON.toJSONString(metadata)));
                index++;
            }
            return;
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(submitResult.getDirectUrl())) {
            ordered.add(submitResult.getDirectUrl().trim());
        }
        if (submitResult.getResultUrls() != null) {
            for (String url : submitResult.getResultUrls()) {
                if (StringUtils.isNotBlank(url)) ordered.add(url.trim());
            }
        }
        if (ordered.isEmpty() && StringUtils.isNotBlank(submitResult.getOssUrl())) {
            ordered.add(submitResult.getOssUrl().trim());
        }
        String operator = task.getUserId() == null ? "" : String.valueOf(task.getUserId());
        int index = 0;
        for (String url : ordered) {
            aidMediaResultMapper.upsertTaskResult(task.getId(), index, task.getMediaType(), url, operator);
            if (alreadyPersisted) {
                LambdaUpdateWrapper<AidMediaResult> update = new LambdaUpdateWrapper<>();
                update.eq(AidMediaResult::getTaskId, task.getId());
                update.eq(AidMediaResult::getResultIndex, index);
                update.set(AidMediaResult::getOssUrl, url);
                aidMediaResultMapper.update(null, update);
            }
            index++;
        }
    }

    /** 文本调用失败保守结算；未产出可用正文、未发出请求或明确拒绝时退款。 */
    private boolean closeFailedSubmitBilling(AidMediaTask task, Map<String, Object> usage) {
        task.setErrorDetailJson(TextFailureBillingPolicy.withObservedUsage(task.getErrorDetailJson(), usage));
        boolean textTask = MediaType.TEXT.name().equals(task.getMediaType());
        boolean hasProviderUsage = ProviderUsageSupport.hasAnyProviderUsage(usage);
        if (textTask && task.getBillingStatus() == null) {
            if (hasProviderUsage) {
                persistUsageForExemptTask(task, usage);
            }
            releaseConcurrency(task);
            return true;
        }
        boolean settleProviderCall = textTask && TextFailureBillingPolicy.shouldSettle(
                false, task.getUpstreamAcceptTime() != null,
                task.getProtocol(), task.getErrorDetailJson(), usage);
        boolean billingWon = settleProviderCall
                ? billingFacadeService.settleBilling(task, usage)
                : billingFacadeService.refundBilling(task);
        if (settleProviderCall) {
            log.info("文本同步Provider调用后失败，按真实usage或预冻结上限保守结算: taskId={}", task.getId());
        }
        syncTerminalFieldsIfNeeded(task, billingWon);
        releaseConcurrency(task);
        return billingWon;
    }

    private boolean isEmptyTextSubmitResult(AidMediaTask task, ProviderSubmitResult result) {
        return MediaType.TEXT.name().equals(task.getMediaType())
                && StringUtils.isBlank(result.getProviderTaskId())
                && StringUtils.isBlank(result.getDirectText())
                && result.getToolMessage() == null
                && (StringUtils.isBlank(result.getRawResponse())
                || isEmptyContentError(result.getRawResponse()));
    }

    private boolean isEmptyContentError(String message) {
        if (StringUtils.isBlank(message)) {
            return true;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("响应内容为空") || normalized.contains("正文为空")
                || normalized.contains("content is empty") || normalized.contains("empty content");
    }

    private void markEmptyTextResult(AidMediaTask task) {
        if (MediaType.TEXT.name().equals(task.getMediaType())) {
            task.setErrorDetailJson(TaskErrorSnapshot.write(TaskSuccessValidator.validateText(null)));
        }
    }

    /**
     * 模型健康采集：同步直出成功，耗时 = 任务创建到上游返回（即本次请求耗时）。
     * 采集器内部吞异常，不影响主流程。
     */
    private void recordSubmitHealthSuccess(AidMediaTask task) {
        if (task.getTerminalTime() == null) {
            task.setTerminalTime(new Date());
        }
        Long latencyMs = Objects.nonNull(task.getCreateTime())
                ? System.currentTimeMillis() - task.getCreateTime().getTime() : null;
        modelHealthRecorder.recordSuccess(task.getModelName(), task.getMediaType(), latencyMs);
    }

    /**
     * 平台公共校验：图片任务的 Provider 查询结果是否包含任何可用图片 URL。
     * 判定顺序：{@code resultUrl} 非空 &gt; {@code resultUrls} 非空 &gt; {@code resultCount &gt; 0}。
     * 任何一个命中即视为有效结果；三者皆无则需要把本次 SUCCEEDED 降级为 FAILED，以防脏任务进入结算。
     */
    private boolean hasAnyImageUrl(ProviderTaskResult taskResult) {
        if (taskResult == null) {
            return false;
        }
        if (StringUtils.isNotBlank(taskResult.getResultUrl())) {
            return true;
        }
        if (taskResult.getResultUrls() != null && !taskResult.getResultUrls().isEmpty()) {
            return true;
        }
        return taskResult.getResultCount() != null && taskResult.getResultCount() > 0;
    }

    /**
     * 同步直出结算：图片补充真实张数，音频保留上游真实用量。
     */
    private Map<String, Object> buildImageSettleUsageForSubmit(AidMediaTask task, ProviderSubmitResult submitResult) {
        if (Objects.equals(task.getMediaType(), MediaType.AUDIO.name())) {
            return submitResult == null || submitResult.getUsage() == null
                    ? null : new HashMap<>(submitResult.getUsage());
        }
        if (!Objects.equals(task.getMediaType(), MediaType.IMAGE.name())) {
            return null;
        }
        int actualCount = 0;
        if (submitResult != null) {
            if (submitResult.getResultCount() != null && submitResult.getResultCount() > 0) {
                actualCount = submitResult.getResultCount();
            } else if (submitResult.getResultUrls() != null && !submitResult.getResultUrls().isEmpty()) {
                actualCount = submitResult.getResultUrls().size();
            } else if (StringUtils.isNotBlank(submitResult.getDirectUrl())
                    || StringUtils.isNotBlank(submitResult.getOssUrl())) {
                actualCount = 1;
            }
        }
        if (actualCount <= 0) {
            actualCount = 1;
        }
        Map<String, Object> usage = new HashMap<>();
        usage.put("actualImageCount", actualCount);
        usage.put("resultCount", actualCount);
        if (submitResult != null && submitResult.getImageOutputs() != null
                && !submitResult.getImageOutputs().isEmpty()) {
            usage.put("outputImagePixels", submitResult.getImageOutputs().stream()
                    .map(item -> Math.multiplyExact((long) item.getWidth(), item.getHeight())).toList());
        }
        // 合并 provider 返回的 token usage（如 Gemini 图片模型会带 prompt_tokens/completion_tokens/total_tokens）
        if (submitResult != null && submitResult.getUsage() != null) {
            usage.putAll(submitResult.getUsage());
        }
        return usage;
    }

    /**
     * billingExempt 子任务：将 provider 返回的真实 token usage 写入 billingSnapshotJson。
     * 子任务不走独立计费（billingStatus=null），但外层 aggregateTokenUsage() 需要从子任务
     * 的 billingSnapshotJson 中读取 actualInputTokens/actualOutputTokens 来聚合主任务的结算 usage。
     * 本方法确保 provider 返回的 usage 不被丢弃。
     */
    private void persistUsageForExemptTask(AidMediaTask task, Map<String, Object> usage) {
        BillingSnapshot snapshot = new BillingSnapshot();
        Integer inputTokens = usageInteger(usage, "input_tokens", "prompt_tokens");
        Integer outputTokens = usageInteger(usage, "output_tokens", "completion_tokens");
        boolean inputComplete = usageBoolean(usage, "input_usage_complete", inputTokens != null);
        boolean outputComplete = usageBoolean(usage, "output_usage_complete", outputTokens != null);
        snapshot.setActualInputTokens(inputTokens);
        snapshot.setActualOutputTokens(outputTokens);
        snapshot.setActualUncachedInputTokens(usageInteger(usage, "uncached_input_tokens"));
        snapshot.setActualCachedInputTokens(usageInteger(
                usage, "cached_input_tokens", "cache_read_input_tokens"));
        snapshot.setActualCacheWriteInputTokens(usageInteger(usage, "cache_write_input_tokens"));
        snapshot.setActualVisibleOutputTokens(usageInteger(usage, "visible_output_tokens"));
        snapshot.setActualReasoningTokens(usageInteger(usage, "reasoning_tokens"));
        snapshot.setHasAnyProviderUsage(true);
        snapshot.setInputUsageComplete(inputComplete);
        snapshot.setOutputUsageComplete(outputComplete);
        snapshot.setProviderUsageCaptured(usageBoolean(
                usage, "provider_usage_captured", inputComplete && outputComplete));
        snapshot.setInputTokenBucketsComplete(usageBoolean(
                usage, "input_token_buckets_complete", false));
        snapshot.setOutputTokenBucketsComplete(usageBoolean(
                usage, "output_token_buckets_complete", false));
        task.setBillingSnapshotJson(JSONUtil.toJsonStr(snapshot));
        log.info("billingExempt子任务写入usage快照: taskId={}, bizTaskId={}, inputTokens={}, "
                        + "outputTokens={}, inputComplete={}, outputComplete={}",
                task.getId(), task.getBizTaskId(), inputTokens, outputTokens,
                inputComplete, outputComplete);
    }

    private static Integer usageInteger(Map<String, Object> usage, String... keys) {
        if (usage == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = usage.get(key);
            if (value == null) {
                continue;
            }
            try {
                long parsed = value instanceof Number number
                        ? number.longValue() : Long.parseLong(String.valueOf(value));
                return (int) Math.max(0L, Math.min(parsed, Integer.MAX_VALUE));
            } catch (Exception ignored) {
                // 非法 usage 字段按缺失处理，完整性标记会阻止低估结算。
            }
        }
        return null;
    }

    private static boolean usageBoolean(Map<String, Object> usage, String key, boolean defaultValue) {
        return usage != null && usage.containsKey(key)
                ? Boolean.parseBoolean(String.valueOf(usage.get(key))) : defaultValue;
    }

    /**
     * 刷新单个处理中任务（统一供'前端轮询接口'和'服务端定时补偿'复用）
     * @param task 待刷新任务
     * @param failOnModelMissing 模型配置缺失时是否抛异常（true：API查询场景；false：补偿场景）
     */
    private void refreshProcessingTask(AidMediaTask task, boolean failOnModelMissing) {
        refreshProcessingTask(task, failOnModelMissing, true);
    }

    /**
     * 刷新单个处理中任务
     * @param task 待刷新任务
     * @param failOnModelMissing 模型配置缺失时是否抛异常
     * @param incrementRetry 上游仍 PROCESSING 时是否累加重试计数（内部编排轮询传 false）
     */
    private void refreshProcessingTask(AidMediaTask task, boolean failOnModelMissing, boolean incrementRetry) {
        try (com.aid.diagnostics.DiagnosticCapture.TaskScope scope = com.aid.diagnostics.DiagnosticCapture.task(task)) {
            refreshProcessingTaskCaptured(task, failOnModelMissing, incrementRetry);
        }
    }

    private void refreshProcessingTaskCaptured(AidMediaTask task, boolean failOnModelMissing, boolean incrementRetry) {
        if (!MediaTaskStatus.PROCESSING.name().equals(task.getStatus())
            || StringUtils.isBlank(task.getProviderTaskId())) {
            return;
        }
        AiModelConfigVo modelConfig = resolveTaskModelConfig(task);
        if (modelConfig == null) {
            if (failOnModelMissing) {
                throw new ServiceException("模型配置不存在: " + task.getModelName());
            }
            // 已有 providerTaskId 说明上游可能仍在运行；本地配置缺失不等于厂商任务失败。
            log.error("媒体任务模型配置缺失，保留任务等待对账, taskId={}, modelName={}, providerTaskId={}",
                    task.getId(), task.getModelName(), task.getProviderTaskId());
            task.setErrorMessage("上游状态待确认:模型配置缺失");
            updateTaskWithPayloadArchive(task);
            return;
        }
        ProviderTaskResult taskResult;
        if (Objects.equals(task.getMediaType(), MediaType.IMAGE.name())) {
            taskResult = getImageClient(task.getProtocol()).query(modelConfig, task.getProviderTaskId());
        } else if (Objects.equals(task.getMediaType(), MediaType.VIDEO.name())) {
            taskResult = getVideoClient(task.getProtocol()).query(modelConfig, task.getProviderTaskId());
        } else if (Objects.equals(task.getMediaType(), MediaType.AUDIO.name())) {
            // AUDIO 分支：与调度中心 queryUpstream 保持一致的 protocol 路由。
            taskResult = getAudioClientByProtocol(task.getProtocol()).query(modelConfig, task.getProviderTaskId());
        } else {
            taskResult = getTextClient(task.getProtocol()).query(modelConfig, task.getProviderTaskId());
        }
        if (taskResult == null) {
            return;
        }
        if (!Boolean.TRUE.equals(taskResult.getQuerySuccessful())) {
            task.setStatus(MediaTaskStatus.PROCESSING.name());
            task.setErrorMessage(StringUtils.defaultIfBlank(taskResult.getErrorMessage(), "上游状态待确认"));
            task.setResponseJson(taskResult.getRawResponse());
            updateTaskWithPayloadArchive(task);
            return;
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())
            && Objects.equals(task.getMediaType(), MediaType.IMAGE.name())
            && !hasAnyImageUrl(taskResult)) {
            log.error("图片任务状态为 SUCCEEDED 但未解析到结果 URL，保留任务继续对账, taskId={}, protocol={}",
                task.getId(), task.getProtocol());
            taskResult.setStatus(MediaTaskStatus.PROCESSING.name());
            taskResult.setQuerySuccessful(Boolean.FALSE);
            taskResult.setTerminalConfirmed(Boolean.FALSE);
            taskResult.setErrorMessage("上游成功但结果未就绪");
        }
        if (MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())
            && Objects.equals(task.getMediaType(), MediaType.VIDEO.name())
            && StringUtils.isBlank(taskResult.getResultUrl())) {
            log.error("视频任务状态为 SUCCEEDED 但未解析到结果 URL，保留任务继续对账, taskId={}, protocol={}",
                task.getId(), task.getProtocol());
            taskResult.setStatus(MediaTaskStatus.PROCESSING.name());
            taskResult.setQuerySuccessful(Boolean.FALSE);
            taskResult.setTerminalConfirmed(Boolean.FALSE);
            taskResult.setErrorMessage("上游成功但结果未就绪");
        }
        boolean terminal = MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())
            || MediaTaskStatus.FAILED.name().equals(taskResult.getStatus());
        if (terminal) {
            if (!Boolean.TRUE.equals(taskResult.getTerminalConfirmed())) {
                task.setStatus(MediaTaskStatus.PROCESSING.name());
                task.setErrorMessage("上游终态未经文档状态确认");
                task.setResponseJson(taskResult.getRawResponse());
                updateTaskWithPayloadArchive(task);
                return;
            }
            taskCompletionService.completeTask(task.getId(), taskResult);
            if (MediaTaskStatus.SUCCEEDED.name().equals(taskResult.getStatus())) {
                // CAS 赢家或并发赢家均可能已完成终态；统一入口会自行校验数据库状态并幂等持久化。
                ensureOssPersisted(task.getId());
            }
            return;
        }
        task.setResponseJson(taskResult.getRawResponse());
        task.setStatus(MediaTaskStatus.PROCESSING.name());
        if (incrementRetry) {
            int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();
            task.setRetryCount(Math.min(currentRetry + 1, maxCompensationRetry));
        }
        updateTaskWithPayloadArchive(task);
    }

    /**
     * 判定任务是否到达补偿轮询窗口
     * @param task 任务实体
     * @param now 当前时间
     * @return true 表示可执行补偿查询
     */
    private boolean isCompensationDue(AidMediaTask task, Date now) {
        int backoffSeconds = resolveCompensationBackoffSeconds(task.getRetryCount());
        Date lastTouchTime = task.getUpdateTime() != null ? task.getUpdateTime() : task.getCreateTime();
        if (lastTouchTime == null) {
            return true;
        }
        long nextPollTimeMillis = lastTouchTime.getTime() + backoffSeconds * 1000L;
        return nextPollTimeMillis <= now.getTime();
    }

    /**
     * 计算补偿轮询退避秒数（5/10/20/30 秒）
     * @param retryCount 当前重试次数
     * @return 本次建议等待秒数
     */
    private int resolveCompensationBackoffSeconds(Integer retryCount) {
        int retries = retryCount == null ? 0 : retryCount;
        if (retries <= 0) {
            return 5;
        }
        if (retries == 1) {
            return 10;
        }
        if (retries == 2) {
            return 20;
        }
        return 30;
    }

    /**
     * 任务已成功且存在可访问 URL 时，将结果回填至业务表（可选，依赖任务上的 callback 字段）。
     */
    private void tryInvokeGenResultCallback(AidMediaTask task) {
        if (!MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())) {
            return;
        }
        if (task.getCallbackRecordId() == null || StringUtils.isBlank(task.getCallbackCategory())) {
            return;
        }
        // 强校验：业务表只能回填已持久化到本系统存储的 URL（aid_media_task.oss_url），
        // 绝对禁止回填上游厂商临时签名 URL（task.originUrl）—— 几小时后会 403 失效。
        // 如果本次 OSS/本地持久化失败，ossUrl 为空，此处直接跳过回填并打印 error，
        // 业务侧由定时补偿任务或人工重试修复，不会污染业务表。
        if (StringUtils.isBlank(task.getOssUrl())) {
            log.error("gen result callback skipped: ossUrl is blank, taskId={}, recordId={}, category={}, originUrl={}",
                task.getId(), task.getCallbackRecordId(), task.getCallbackCategory(), task.getOriginUrl());
            return;
        }
        try {
            genResultCallbackService.fillResultUrl(task.getCallbackRecordId(), task.getOssUrl(), task.getCallbackCategory());
        } catch (Exception ex) {
            log.error("gen result callback failed, taskId={}, recordId={}, error={}",
                task.getId(), task.getCallbackRecordId(), ex.getMessage(), ex);
        }
    }

    private void persistOssIfNeeded(AidMediaTask task) {
        if (!MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus()) || StringUtils.isBlank(task.getOriginUrl())) {
            return;
        }
        //    COMPOSE 成片在终态收口（completeTask）时已随 origin_url 一并把 oss_url 填成 COS 对象相对路径，
        //    因此这里会直接命中该 return，不会再走下载转存。
        if (StringUtils.isNotBlank(task.getOssUrl())) {
            return;
        }
        try {
            MediaImageGenerateRequest editRequest = protectedImageEditRequest(task);
            if (editRequest != null) {
                persistProtectedImageResults(task, editRequest);
                return;
            }
            //    单次失败直接放弃会让业务层 resolveImageUrl 抛"存储失败"；同步重试可消除大多数瞬时抖动。
            byte[] bytes = downloadOriginBytesWithRetry(task);
            String suffix = detectSuffix(task.getOriginUrl(), task);
            String contentType = resolveContentType(task, suffix);
            UploadResult uploadResult = OssFactory.instance().uploadSuffix(bytes, suffix, contentType);
            task.setOssUrl(uploadResult.getUrl());
            //    产物字节已在内存，顺带补齐厂商未回传的成片时长（对口型等），避免业务侧时长为空
            Integer durationSeconds = resolveOutputDurationSeconds(task, bytes);
            upsertResultRecord(task, contentType, bytes.length, durationSeconds);
        } catch (Exception ex) {
            //    但记录 error 级别日志供运维告警；ossUrl 保持为空，
            //    tryInvokeGenResultCallback 将跳过业务表回填，避免写入会过期的上游 URL。
            log.error("media persist upload failed, taskId={}, originUrl={}, error={}",
                task.getId(), task.getOriginUrl(), ex.getMessage(), ex);
            //     注意：保持 status=SUCCEEDED 不变（计费已结算、上游已产物），仅作为可观测信号，
            //     60s 后由定时补偿 ossCompensate 兜底重试。
            String reason = ex.getClass().getSimpleName() + ": " + StringUtils.defaultString(ex.getMessage(), "");
            if (reason.length() > 240) {
                reason = reason.substring(0, 240);
            }
            task.setErrorMessage("OSS 持久化失败：" + reason);
        }
    }

    /**
     * 带同步重试的上游产物下载。
     */
    private byte[] downloadOriginBytesWithRetry(AidMediaTask task) {
        return downloadOriginBytesWithRetry(task, task.getOriginUrl());
    }

    private byte[] downloadOriginBytesWithRetry(AidMediaTask task, String originUrl) {
        final int maxAttempts = 3;
        final int connTimeoutMs = 30_000;
        final int readTimeoutMs = 60_000;
        final long[] backoffMs = {0L, 1500L, 3000L};
        AiModelConfigVo modelConfig = resolveTaskModelConfig(task);
        Exception lastEx = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (backoffMs[attempt - 1] > 0L) {
                try {
                    Thread.sleep(backoffMs[attempt - 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("下载重试被中断", ie);
                }
            }
            try {
                byte[] bytes = isTokenDanceAudioTask(task, modelConfig)
                        ? TokenDancePublicMediaDownloader.download(
                                originUrl, connTimeoutMs, readTimeoutMs)
                        : downloadOriginBytesFollowingRedirects(
                                originUrl, modelConfig, task.getId(), task.getModelName(),
                                connTimeoutMs, readTimeoutMs);
                if (attempt > 1) {
                    log.info("origin 下载重试成功, taskId={}, attempt={}, size={}", task.getId(), attempt, bytes.length);
                }
                return bytes;
            } catch (Exception ex) {
                lastEx = ex;
                log.warn("origin 下载失败, taskId={}, attempt={}/{}, errorType={}",
                        task.getId(), attempt, maxAttempts, ex.getClass().getSimpleName());
            }
        }
        // 全部重试失败：抛出最后一次异常给上层 catch 处理
        throw new RuntimeException("origin 下载重试 " + maxAttempts + " 次均失败", lastEx);
    }

    private static boolean isTokenDanceAudioTask(AidMediaTask task, AiModelConfigVo modelConfig) {
        return task != null && Objects.equals(MediaType.AUDIO.name(), task.getMediaType())
                && modelConfig != null && "tokendance".equalsIgnoreCase(modelConfig.getProviderCode());
    }

    /**
     * 受控下载产物：最多跟随三次重定向；只有与模型网关同源的请求才携带模型凭证。
     */
    static byte[] downloadOriginBytesFollowingRedirects(String originUrl, AiModelConfigVo modelConfig,
                                                        Long taskId, String modelCode,
                                                        int connectionTimeoutMs, int readTimeoutMs) {
        OriginDownloadSource source = resolveOriginDownloadSource(originUrl);
        String currentUrl = source.url();
        Set<String> visited = new HashSet<>();
        int redirectCount = 0;
        boolean initialRequest = true;
        while (true) {
            if (!visited.add(currentUrl)) {
                throw new ServiceException("产物重定向循环");
            }
            HttpRequest request = source.managedStorage()
                    ? HttpRequest.get(currentUrl)
                    : buildOriginDownloadRequest(currentUrl, modelConfig, taskId, modelCode, initialRequest);
            try (HttpResponse response = request
                    .setConnectionTimeout(connectionTimeoutMs)
                    .setReadTimeout(readTimeoutMs)
                    .setFollowRedirects(false)
                    .execute()) {
                int status = response.getStatus();
                if (isRedirectStatus(status)) {
                    if (redirectCount >= ORIGIN_DOWNLOAD_MAX_REDIRECTS) {
                        throw new ServiceException("产物重定向过多");
                    }
                    String location = response.header("Location");
                    if (StringUtils.isBlank(location)) {
                        throw new ServiceException("产物重定向无地址");
                    }
                    currentUrl = resolveRedirectUrl(currentUrl, location);
                    redirectCount++;
                    initialRequest = false;
                    continue;
                }
                if (!response.isOk()) {
                    throw new RuntimeException("下载非 2xx 状态码: " + status);
                }
                byte[] bytes = response.bodyBytes();
                if (bytes == null || bytes.length == 0) {
                    throw new RuntimeException("下载字节为空");
                }
                return bytes;
            }
        }
    }

    private static HttpRequest buildOriginDownloadRequest(String originUrl, AiModelConfigVo modelConfig,
                                                           Long taskId, String modelCode,
                                                           boolean initialRequest) {
        String safeUrl = requireHttpUrl(originUrl);
        HttpRequest request = HttpRequest.get(safeUrl);
        if (!resultDownloadRequiresAuth(modelConfig)) {
            return request;
        }
        if (modelConfig == null || StringUtils.isBlank(modelConfig.getApiKey())) {
            log.error("鉴权产物下载缺少模型凭证, taskId={}, modelCode={}",
                    taskId, modelCode);
            throw new ServiceException("产物下载配置异常");
        }
        boolean sameGatewayOrigin = sameOrigin(safeUrl, modelConfig.getBaseUrl());
        if (initialRequest && !sameGatewayOrigin) {
            log.error("鉴权产物下载拒绝跨源携密, taskId={}, modelCode={}", taskId, modelCode);
            throw new ServiceException("产物地址不可信");
        }
        if (!sameGatewayOrigin) {
            return request;
        }
        String authHeader = StringUtils.defaultIfBlank(
                modelConfig.getAuthHeader(), HttpConstants.HEADER_AUTHORIZATION);
        String authPrefix = modelConfig.getAuthPrefix() == null
                ? HttpConstants.AUTH_BEARER_PREFIX : modelConfig.getAuthPrefix();
        return request.header(authHeader, authPrefix + modelConfig.getApiKey(), true);
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String resolveRedirectUrl(String currentUrl, String location) {
        try {
            URI resolved = URI.create(currentUrl).resolve(location.trim());
            return requireHttpUrl(resolved.toString());
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("产物重定向无效");
        }
    }

    private static String requireHttpUrl(String value) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("产物地址无效");
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || StringUtils.isBlank(uri.getHost())) {
                throw new ServiceException("产物地址无效");
            }
            return uri.toString();
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("产物地址无效");
        }
    }

    private static boolean resultDownloadRequiresAuth(AiModelConfigVo modelConfig) {
        if (modelConfig == null) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode capability =
                ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
        return capability != null && capability.path(
                ConfigurableAsyncMediaConstants.CAPABILITY_RESULT_DOWNLOAD_REQUIRES_AUTH).asBoolean(false);
    }

    private static boolean sameOrigin(String firstUrl, String secondUrl) {
        if (StringUtils.isAnyBlank(firstUrl, secondUrl)) {
            return false;
        }
        try {
            URI first = URI.create(firstUrl.trim());
            URI second = URI.create(secondUrl.trim());
            String firstScheme = first.getScheme();
            String secondScheme = second.getScheme();
            if (!("http".equalsIgnoreCase(firstScheme) || "https".equalsIgnoreCase(firstScheme))
                    || !("http".equalsIgnoreCase(secondScheme) || "https".equalsIgnoreCase(secondScheme))) {
                return false;
            }
            return StringUtils.equalsIgnoreCase(firstScheme, secondScheme)
                    && StringUtils.equalsIgnoreCase(first.getHost(), second.getHost())
                    && effectivePort(first) == effectivePort(second);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 解析视频任务的成片时长（秒，向上取整），并在任务表时长缺失时补齐。
     * 仅处理视频任务：厂商回传时长时以厂商为准，未回传时从产物字节的容器头解析。
     *
     * @param task  当前任务（时长缺失时就地补齐 outputDurationSeconds）
     * @param bytes 产物完整字节
     * @return 成片时长（秒）；非视频任务或解析不出返回 null
     */
    private Integer resolveOutputDurationSeconds(AidMediaTask task, byte[] bytes) {
        if (!Objects.equals(MediaType.VIDEO.name(), task.getMediaType())) {
            return null;
        }
        if (Objects.nonNull(task.getOutputDurationSeconds()) && task.getOutputDurationSeconds() > 0) {
            return task.getOutputDurationSeconds().intValue();
        }
        Integer durationMs = VideoDurationProber.probeDurationMs(bytes);
        if (Objects.isNull(durationMs) || durationMs <= 0) {
            log.warn("media 成片时长解析失败, taskId={}, size={}", task.getId(), bytes.length);
            return null;
        }
        int seconds = (int) Math.ceil(durationMs / (double) MILLIS_PER_SECOND);
        task.setOutputDurationSeconds((long) seconds);
        log.info("media 成片时长补齐, taskId={}, durationMs={}, seconds={}", task.getId(), durationMs, seconds);
        return seconds;
    }

    private void upsertResultRecord(AidMediaTask task, String mimeType, long fileSize, Integer durationSeconds) {
        LambdaQueryWrapper<AidMediaResult> wrapper = new LambdaQueryWrapper<AidMediaResult>()
            .eq(AidMediaResult::getTaskId, task.getId())
            .eq(AidMediaResult::getResultIndex, 0)
            .last("limit 1");
        AidMediaResult result = aidMediaResultMapper.selectOne(wrapper);
        if (result == null) {
            result = new AidMediaResult();
            result.setTaskId(task.getId());
            result.setResultIndex(0);
            result.setMediaType(task.getMediaType());
            result.setOriginUrl(task.getOriginUrl());
            result.setOssUrl(task.getOssUrl());
            result.setMimeType(mimeType);
            result.setFileSize(fileSize);
            result.setDurationSeconds(durationSeconds);
            aidMediaResultMapper.insert(result);
            return;
        }
        result.setOriginUrl(task.getOriginUrl());
        result.setOssUrl(task.getOssUrl());
        result.setMimeType(mimeType);
        result.setFileSize(fileSize);
        if (Objects.nonNull(durationSeconds)) {
            result.setDurationSeconds(durationSeconds);
        }
        aidMediaResultMapper.updateById(result);
    }

    /**
     * 从 OSS/上游 URL 中提取文件后缀；音频任务优先使用请求里声明的格式，不被误导性 URL 后缀污染。
     */
    private String detectSuffix(String url, AidMediaTask task) {
        String mediaType = task == null ? null : task.getMediaType();

        if (Objects.equals(mediaType, MediaType.AUDIO.name())) {
            String reqFormat = normalizeAudioFormat(extractAudioFormatFromRequest(task));
            if (StringUtils.isNotBlank(reqFormat)) {
                return "." + reqFormat;
            }
        }

        if (StringUtils.isNotBlank(url) && url.contains(".")) {
            int idx = url.lastIndexOf('.');
            if (idx > 0 && idx < url.length() - 1) {
                String suffix = url.substring(idx);
                // 清理查询参数，避免把 ?token 拼进后缀。
                int q = suffix.indexOf('?');
                if (q > 0) {
                    suffix = suffix.substring(0, q);
                }
                // AUDIO：先归一化（ogg_opus → opus），再按归一化后的值长度判断是否可用，
                //        避免 ".ogg_opus" 这类 9 字符的复合标记被 <=8 的守卫误判为非法后缀。
                if (Objects.equals(mediaType, MediaType.AUDIO.name())) {
                    String normalized = normalizeAudioFormat(suffix);
                    if (StringUtils.isNotBlank(normalized)
                            && normalized.length() > 0 && normalized.length() <= 8) {
                        return "." + normalized;
                    }
                    // 归一化后仍然不可用 → 落到第 3 步按 AUDIO 默认兜底
                } else if (Objects.equals(mediaType, MediaType.IMAGE.name())) {
                    // IMAGE：仅放行标准图片后缀，避免阿里 DashScope 临时 URL 形如 ".image" 的伪后缀
                    // 直接被写进 OSS 对象名（用户报告 ai_pet/2026/05/15/xxx.image 即此根因）。
                    String s = suffix.toLowerCase();
                    if (".png".equals(s) || ".jpg".equals(s) || ".jpeg".equals(s)
                            || ".webp".equals(s) || ".gif".equals(s) || ".bmp".equals(s)) {
                        return s;
                    }
                    // 未知后缀（含 .image / .bin / .tmp）→ 落第 3 步按 IMAGE 默认 .png 兜底
                } else if (Objects.equals(mediaType, MediaType.VIDEO.name())) {
                    // VIDEO：白名单收敛，未知后缀兜底 .mp4
                    String s = suffix.toLowerCase();
                    if (".mp4".equals(s) || ".mov".equals(s) || ".webm".equals(s)
                            || ".mkv".equals(s) || ".avi".equals(s) || ".m4v".equals(s)) {
                        return s;
                    }
                } else if (suffix.length() <= 8) {
                    return suffix;
                }
            }
        }

        if (Objects.equals(mediaType, MediaType.IMAGE.name())) {
            return ".png";
        }
        if (Objects.equals(mediaType, MediaType.AUDIO.name())) {
            return ".mp3";
        }
        return ".mp4";
    }

    /**
     * 按任务类型 + 实际后缀解析 OSS 对象的 Content-Type。
     * 后缀先经 {@link #normalizeAudioFormat} 归一化（例如 ogg_opus → opus），再映射到标准 MIME。
     * AUDIO 无法识别时兜底 {@code audio/mpeg}；IMAGE 兜底 {@code image/png}；VIDEO 兜底 {@code video/mp4}。
     */
    private String resolveContentType(AidMediaTask task, String suffix) {
        String mediaType = task == null ? null : task.getMediaType();
        if (Objects.equals(mediaType, MediaType.IMAGE.name())) {
            return "image/png";
        }
        if (Objects.equals(mediaType, MediaType.AUDIO.name())) {
            String normalized = normalizeAudioFormat(suffix);
            if (StringUtils.isBlank(normalized)) {
                return "audio/mpeg";
            }
            switch (normalized) {
                case "mp3":
                    return "audio/mpeg";
                case "wav":
                    return "audio/wav";
                case "pcm":
                    return "audio/x-pcm";
                case "ogg":
                case "opus":
                    return "audio/ogg";
                case "m4a":
                    return "audio/mp4";
                case "aac":
                    return "audio/aac";
                default:
                    return "audio/mpeg";
            }
        }
        return "video/mp4";
    }

    /**
     * 从 aid_media_task.request_json 中读取 audioFormat。解析失败或字段缺失时返回 null。
     */
    private String extractAudioFormatFromRequest(AidMediaTask task) {
        if (task == null || StringUtils.isBlank(task.getRequestJson())) {
            return null;
        }
        try {
            com.aid.media.dto.MediaAudioGenerateRequest req =
                cn.hutool.json.JSONUtil.toBean(task.getRequestJson(),
                    com.aid.media.dto.MediaAudioGenerateRequest.class);
            return req == null ? null : req.getAudioFormat();
        } catch (Exception ex) {
            log.info("extractAudioFormatFromRequest 解析失败, taskId={}, err={}",
                task.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 音频格式归一化：。
     */
    private String normalizeAudioFormat(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        s = s.toLowerCase();
        if ("ogg_opus".equals(s) || "oggopus".equals(s)) {
            return "opus";
        }
        return s;
    }

    private ImageProviderClient getImageClient(String protocol) {
        List<ImageProviderClient> candidates = imageProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        ImageProviderClient client = candidates.size() == 1 ? candidates.get(0) : null;
        if (client == null) {
            throw new ServiceException("不支持的图片协议: " + protocol);
        }
        return client;
    }

    private VideoProviderClient getVideoClient(String protocol) {
        List<VideoProviderClient> candidates = videoProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol)).toList();
        VideoProviderClient client = candidates.size() == 1 ? candidates.get(0) : null;
        if (client == null) {
            throw new ServiceException("不支持的视频协议: " + protocol);
        }
        return client;
    }

    private TextProviderClient getTextClient(String protocol) {
        List<TextProviderClient> candidates = textProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        TextProviderClient client = candidates.size() == 1 ? candidates.get(0) : null;
        if (client == null) {
            throw new ServiceException("不支持的文本协议: " + protocol);
        }
        return client;
    }

    /**
     * 按 protocol 解析音频 provider：供 refreshProcessingTask 轮询使用。
     */
    private com.aid.media.provider.AudioProviderClient getAudioClientByProtocol(String protocol) {
        if (StringUtils.isBlank(protocol)) {
            throw new ServiceException("音频格式不支持");
        }
        List<com.aid.media.provider.AudioProviderClient> candidates = audioProviderClients.stream()
            .filter(it -> it.supportsProtocol(protocol))
            .toList();
        com.aid.media.provider.AudioProviderClient client =
            candidates.size() == 1 ? candidates.get(0) : null;
        if (client == null) {
            throw new ServiceException("不支持的音频协议: " + protocol);
        }
        return client;
    }

    /**
     * 按 {@code provider_code} 在给定 client 列表中唯一命中一个实现。
     *
     * @param clients     某一媒体类型的全部 client
     * @param modelConfig 已解析模型配置（取 provider_code）
     * @param matcher     各 client 的 supportsProviderCode 方法引用
     * @param          client 类型
     * @return 唯一命中的 client；未命中返回 null
     */
    private <T> T resolveByProviderCode(List<T> clients, AiModelConfigVo modelConfig,
                                        java.util.function.BiPredicate<T, String> matcher) {
        String providerCode = modelConfig == null ? null : modelConfig.getProviderCode();
        if (StringUtils.isBlank(providerCode)) {
            return null;
        }
        List<T> hit = clients.stream()
                .filter(it -> matcher.test(it, providerCode))
                .toList();
        if (hit.size() == 1) {
            return hit.get(0);
        }
        if (hit.size() > 1) {
            log.error("provider_code 命中多个实现, providerCode={}, count={}", providerCode, hit.size());
            throw new ServiceException("配置异常");
        }
        return null;
    }

    /**
     * 解析视频 provider：按 {@code aid_ai_provider.provider_code} 强路由（唯一确定性依据）。
     *
     * @param requestModel 前端请求模型名（保留入参以兼容调用方，本方法不再据此猜测）
     * @param modelConfig  已解析的模型配置（含 provider_code / protocol）
     * @return 匹配到的 provider
     */
    private VideoProviderClient resolveVideoClient(String requestModel, AiModelConfigVo modelConfig) {
        if (modelConfig != null && StringUtils.isNotBlank(modelConfig.getCapabilityCode())) return getVideoClient(modelConfig.getProtocol());
        VideoProviderClient byProviderCode = resolveByProviderCode(
                videoProviderClients, modelConfig, VideoProviderClient::supportsProviderCode);
        if (byProviderCode != null) {
            return byProviderCode;
        }
        String protocol = modelConfig == null ? null : modelConfig.getProtocol();
        if (StringUtils.isNotBlank(protocol)) {
            List<VideoProviderClient> byProtocol = videoProviderClients.stream()
                .filter(it -> it.supportsProtocol(protocol))
                .toList();
            if (byProtocol.size() == 1) {
                return byProtocol.get(0);
            }
        }
        log.error("无法路由视频 provider, providerCode={}, protocol={}",
                modelConfig == null ? null : modelConfig.getProviderCode(), protocol);
        throw new ServiceException("模型未配置");
    }

    /**
     * 解析图片 provider：按 {@code aid_ai_provider.provider_code} 强路由（唯一确定性依据）。
     *
     * @param requestModel 前端请求模型名（保留入参以兼容调用方，本方法不再据此猜测）
     * @param modelConfig  已解析的模型配置（含 provider_code / protocol）
     * @return 匹配到的 provider
     */
    private ImageProviderClient resolveImageClient(String requestModel, AiModelConfigVo modelConfig) {
        if (modelConfig != null && StringUtils.isNotBlank(modelConfig.getCapabilityCode())) return getImageClient(modelConfig.getProtocol());
        ImageProviderClient byProviderCode = resolveByProviderCode(
                imageProviderClients, modelConfig, ImageProviderClient::supportsProviderCode);
        if (byProviderCode != null) {
            return byProviderCode;
        }
        String protocol = modelConfig == null ? null : modelConfig.getProtocol();
        if (StringUtils.isNotBlank(protocol)) {
            List<ImageProviderClient> byProtocol = imageProviderClients.stream()
                .filter(it -> it.supportsProtocol(protocol))
                .toList();
            if (byProtocol.size() == 1) {
                return byProtocol.get(0);
            }
        }
        log.error("无法路由图片 provider, providerCode={}, protocol={}",
                modelConfig == null ? null : modelConfig.getProviderCode(), protocol);
        throw new ServiceException("模型未配置");
    }

    /**
     * 解析文本 provider。
     *
     * @param requestModel 前端请求模型名
     * @param modelConfig  已解析的模型配置
     */
    private TextProviderClient resolveTextClient(String requestModel, AiModelConfigVo modelConfig) {
        if (modelConfig != null && StringUtils.isNotBlank(modelConfig.getCapabilityCode())) return getTextClient(modelConfig.getProtocol());
        String protocol = modelConfig == null ? null : modelConfig.getProtocol();
        if (StringUtils.isNotBlank(protocol)) {
            List<TextProviderClient> byProtocol = textProviderClients.stream()
                    .filter(it -> it.supportsProtocol(protocol))
                    .toList();
            if (byProtocol.size() == 1) {
                return byProtocol.get(0);
            }
        }

        //    展示码 model_code 与真实模型名 real_model_code 解耦，弱匹配须用真实模型名。
        String effectiveModel = com.aid.media.provider.ModelCodeResolver.resolveUpstreamModel(modelConfig, requestModel);
        if (StringUtils.isNotBlank(effectiveModel)) {
            List<TextProviderClient> byModel = textProviderClients.stream()
                    .filter(it -> it.supportsModel(effectiveModel))
                    .toList();
            if (byModel.size() == 1) {
                return byModel.get(0);
            }
        }

        return getTextClient(DEFAULT_TEXT_PROTOCOL);
    }

    private void validatePrompt(String prompt) {
        // 统一校验 prompt 非空，保证上下游处理逻辑一致。
        if (StringUtils.isBlank(prompt)) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_EMPTY, "请填写提示词");
        }
    }

    /** 未声明文本能力的历史模型保持提示词必填；显式禁用文本的素材处理模型允许空提示词。 */
    private void validatePromptForModel(AiModelConfigVo modelConfig, String prompt) {
        if (modelConfig != null && Boolean.FALSE.equals(modelConfig.getSupportsTextInput())) {
            if (StringUtils.isNotBlank(prompt)) {
                throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_INVALID, "模型不支持文本输入");
            }
            return;
        }
        if (modelConfig != null && StringUtils.isBlank(prompt)) {
            JsonNode capability = ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson());
            if (capability != null && capability.path("promptOptional").asBoolean(false)) return;
        }
        validatePrompt(prompt);
    }

    /**
     * 判断是否对口型提交：options 同时携带源视频与驱动音频契约键。
     * 对口型的时长/画幅由素材推导，不参与能力白名单校验。
     *
     * @param request 视频生成请求
     * @return true=对口型请求
     */
    private boolean isLipSyncRequest(AiModelConfigVo modelConfig, MediaVideoGenerateRequest request) {
        if (modelConfig != null && ("lip_sync".equalsIgnoreCase(modelConfig.getGenerateMode())
                || "lip_sync".equalsIgnoreCase(modelConfig.getCapabilityCode()))) {
            return true;
        }
        if (request != null && "lip_sync".equalsIgnoreCase(request.getCapabilityCode())) {
            return true;
        }
        if (request == null) {
            return false;
        }
        Map<String, Object> options = request.getOptions();
        if (options == null || options.isEmpty()) {
            return false;
        }
        boolean hasVideo = options.containsKey("video_url");
        boolean hasDriver = options.containsKey("audio_url") || StrUtil.isNotBlank(request.getPrompt());
        return hasVideo && hasDriver;
    }

    static void validateMinReferenceImages(AiModelConfigVo modelConfig, int actualCount) {
        if (modelConfig == null) {
            return;
        }
        int min = com.aid.media.provider.ReferenceImageLimiter
                .readMinFromCapabilityJson(modelConfig.getCapabilityJson());
        if (min > 0 && actualCount < min) {
            log.error("参考图数量不足被前置拦截: modelCode={}, 需要>={}张, 实际={}张",
                    modelConfig.getModelCode(), min, actualCount);
            throw new ServiceException("至少传" + min + "张图");
        }
    }

    /**
     * 统计图片请求携带的参考图张数：options.referenceImages 列表优先，其次单图 referenceImageUrl。
     */
    private static int countImageRequestReferenceImages(MediaImageGenerateRequest request) {
        if (request == null) {
            return 0;
        }
        int count = countUrlList(request.getOptions() == null ? null : request.getOptions().get("referenceImages"));
        if (count == 0 && StringUtils.isNotBlank(request.getReferenceImageUrl())) {
            count = 1;
        }
        return count;
    }

    /**
     * 统计视频请求携带的输入图张数：首帧 imageUrl + 首个非空尾帧别名 + 参考图列表 + 多帧关键帧列表。
     */
    static int countVideoRequestReferenceImages(MediaVideoGenerateRequest request) {
        if (request == null) {
            return 0;
        }
        int count = 0;
        if (StringUtils.isNotBlank(request.getImageUrl())) {
            count++;
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            // 尾帧（首尾帧场景）：与 Provider 一致，三个同义键首个非空仅计一张。
            for (String key : new String[]{"lastFrameImageUrl", "endImageUrl", "end_image_url"}) {
                Object value = options.get(key);
                if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                    count++;
                    break;
                }
            }
            // 参考图列表（参考生场景）与多帧关键帧列表
            count += countUrlList(options.get("referenceImages"));
            count += countUrlList(options.get("images"));
            count += countUrlList(options.get("keyImages"));
            count += countUrlList(options.get("key_images"));
            count += countUrlList(options.get("image_settings"));
            count += countUrlList(options.get("imageSettings"));
        }
        return count;
    }

    /** 按 Provider 实际派发语义校验视频参考输入，必须在任务落库与冻结计费前调用。 */
    static void validateVideoRequestReferenceInputs(AiModelConfigVo modelConfig,
                                                    MediaVideoGenerateRequest request) {
        if (modelConfig != null
            && KlingConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(modelConfig.getProviderCode()))) {
            KlingVideoRequestBuilder.validateRequestInputs(modelConfig, request);
            return;
        }
        validateMinReferenceImages(modelConfig, countVideoRequestReferenceImages(request));
    }

    /** 执行视频 Provider 契约校验。 */
    static void validateVideoProviderContract(AiModelConfigVo modelConfig,
                                               MediaVideoGenerateRequest request) {
        String internalAspectRatio = request == null ? null : request.getAspectRatio();
        boolean followInput = request != null
                && ModelCapabilityResolver.isVideoAspectRatioFollowInput(modelConfig);
        if (followInput) {
            request.setAspectRatio(ModelCapabilityResolver.resolveVideoProviderAspectRatio(modelConfig));
        }
        try {
            if (modelConfig != null
                && KlingConstants.PROVIDER_CODE.equalsIgnoreCase(StrUtil.trim(modelConfig.getProviderCode()))) {
                KlingVideoRequestBuilder.validateFullRequest(modelConfig, request);
                return;
            }
            if (modelConfig != null
                && MinimaxH3Constants.PROTOCOL_VIDEO.equalsIgnoreCase(StrUtil.trim(modelConfig.getProtocol()))) {
                MinimaxH3VideoRequestBuilder.buildSubmissionBodyForValidation(modelConfig, request);
                return;
            }
            if (modelConfig != null
                && DmcH3VideoRequestBuilder.PROTOCOL.equalsIgnoreCase(StrUtil.trim(modelConfig.getProtocol()))) {
                DmcH3VideoRequestBuilder.build(modelConfig, request, false);
                return;
            }
            if (modelConfig != null
                && VolcengineConstants.PROTOCOL_SEEDANCE_VIDEO.equalsIgnoreCase(StrUtil.trim(modelConfig.getProtocol()))) {
                VolcengineVideoProviderClient.validateFullRequest(modelConfig, request);
                return;
            }
            if (modelConfig != null
                && ConfigurableAsyncMediaConstants.PROTOCOL_VIDEO.equalsIgnoreCase(
                        StrUtil.trim(modelConfig.getProtocol()))) {
                ConfigurableAsyncVideoProviderClient.validateFullRequest(modelConfig, request);
                return;
            }
            if (Wan3VideoRequestBuilder.supportsModel(modelConfig)) {
                Wan3VideoRequestBuilder.validateFullRequest(modelConfig, request);
                return;
            }
            if (AgnesVideo25RequestBuilder.supportsModel(modelConfig)) {
                AgnesVideo25RequestBuilder.validateFullRequest(modelConfig, request);
            }
        } finally {
            if (followInput) {
                request.setAspectRatio(internalAspectRatio);
            }
        }
    }

    /** 统计列表型 option 中的有效条目数（非 List 或空返回 0）。 */
    private static int countUrlList(Object raw) {
        if (!(raw instanceof java.util.List<?> list)) {
            return 0;
        }
        int count = 0;
        for (Object item : list) {
            if (item != null && StringUtils.isNotBlank(String.valueOf(item))) {
                count++;
            }
        }
        return count;
    }

    private void validateTextRequest(MediaTextGenerateRequest request) {
        // 请求体本身不可为空。
        if (request == null) {
            throw new ServiceException("请求不能为空");
        }
        // 单轮 prompt 有字即视为有效用户输入。
        boolean hasPrompt = StringUtils.isNotBlank(request.getPrompt());
        // 多轮中正文或任一有效内容块都可构成模型输入。
        boolean hasMessageBody = false;
        if (CollectionUtil.isNotEmpty(request.getMessages())) {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages()) {
                if (item != null && (StringUtils.isNotBlank(item.getContent())
                        || CollectionUtil.isNotEmpty(item.getParts())
                        || "tool".equalsIgnoreCase(item.getRole()) && item.getToolCallId() != null && item.getContent() != null)) {
                    hasMessageBody = true;
                    break;
                }
            }
        }
        // 两者皆无则无法构造 user 侧语义，拒绝请求。
        if (!hasPrompt && !hasMessageBody) {
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_EMPTY, "请填写提示词");
        }
    }

    /**
     * 生成任务表 prompt 摘要：用于扣费展示、幂等辅助。
     */
    private String summarizeTextPromptForTask(MediaTextGenerateRequest request) {
        if (StringUtils.isNotBlank(request.getTaskPromptDigest())) {
            return limitTaskPromptSummary(request.getTaskPromptDigest());
        }
        if (StringUtils.isNotBlank(request.getPrompt())) {
            return limitTaskPromptSummary(request.getPrompt());
        }
        // (system 通常承载完整智能体模板,体积可能超过 TEXT 上限)
        StringBuilder sb = new StringBuilder();
        boolean hasNonSystemContent = false;
        if (CollectionUtil.isNotEmpty(request.getMessages())) {
            for (MediaTextGenerateRequest.TextMessageItem item : request.getMessages()) {
                if (item == null) {
                    continue;
                }
                if ("system".equalsIgnoreCase(item.getRole())) {
                    continue;
                }
                if (StringUtils.isNotBlank(item.getContent())) {
                    sb.append(item.getContent()).append(';');
                    hasNonSystemContent = true;
                }
                if (CollectionUtil.isNotEmpty(item.getParts())) {
                    long mediaCount = item.getParts().stream().filter(Objects::nonNull)
                            .filter(part -> !"text".equalsIgnoreCase(part.getType())).count();
                    if (mediaCount > 0) {
                        sb.append("[媒体:").append(mediaCount).append("];");
                        hasNonSystemContent = true;
                    }
                }
            }
            // 极端兜底:仅有 system 消息且业务方未传 digest 时,降级写一个占位标识,
            // 避免 prompt 列写入空字符串后续审计无法定位任务来源
            if (!hasNonSystemContent) {
                MediaTextGenerateRequest.TextMessageItem first = request.getMessages().get(0);
                String role = first == null ? "unknown" : StringUtils.defaultString(first.getRole(), "unknown");
                sb.append("[system-only:").append(role).append("]");
            }
        }
        return limitTaskPromptSummary(sb.toString());
    }

    /**
     * 图片任务存档 prompt:优先 taskPromptDigest,否则用完整 prompt。
     * 设计原因同 {@link #summarizeTextPromptForTask(MediaTextGenerateRequest)}:
     * 业务方拼装的 finalPrompt 可能含智能体模板正文,体积接近 TEXT 上限;
     * 显式传入 digest 可只把动态入参留档,与文本路径策略保持一致。
     */
    private String summarizeImagePromptForTask(MediaImageGenerateRequest request) {
        if (StringUtils.isNotBlank(request.getTaskPromptDigest())) {
            return limitTaskPromptSummary(request.getTaskPromptDigest());
        }
        return limitTaskPromptSummary(request.getPrompt());
    }

    /**
     * 视频任务存档 prompt:优先 taskPromptDigest,否则用完整 prompt。
     */
    private String summarizeVideoPromptForTask(MediaVideoGenerateRequest request) {
        if (StringUtils.isNotBlank(request.getTaskPromptDigest())) {
            return limitTaskPromptSummary(request.getTaskPromptDigest());
        }
        return limitTaskPromptSummary(request.getPrompt());
    }

    private String limitTaskPromptSummary(String prompt) {
        if (prompt == null) {
            return null;
        }
        int codePoints = prompt.codePointCount(0, prompt.length());
        if (codePoints <= MEDIA_TASK_PROMPT_SUMMARY_CODE_POINTS) {
            return prompt;
        }
        // prompt 列仅作任务摘要；按码点截取，避免中文或表情被拆断，完整输入仍在 request_json。
        return prompt.substring(0, prompt.offsetByCodePoints(0, MEDIA_TASK_PROMPT_SUMMARY_CODE_POINTS));
    }

    private AiModelConfigVo resolveTextModel(MediaTextGenerateRequest request) {
        if (!request.isStrictModelSelection()) {
            return resolveModel(request.getModelName(), MediaType.TEXT);
        }
        AiModelConfigVo model = StringUtils.isBlank(request.getModelName()) ? null
                : aiModelConfigService.selectByModelCode(request.getModelName());
        if (model == null || !"text".equalsIgnoreCase(model.getModelType())) {
            log.info("已锁定文本模型不可用: modelCode={}", request.getModelName());
            throw new ServiceException("所选模型不可用");
        }
        return model;
    }

    private AiModelConfigVo resolveModel(String requestedModel, MediaType mediaType) {
        AiModelConfigVo byName = null;
        if (StringUtils.isNotBlank(requestedModel)) {
            byName = aiModelConfigService.selectByModelCode(requestedModel);
            if (byName == null) {
                log.info("显式选择的媒体模型不可用: modelCode={}, mediaType={}", requestedModel, mediaType);
                throw new ServiceException("所选模型不可用");
            }
        }
        if (byName != null) {
            return requireModelType(byName, mediaType);
        }
        String defaultModel;
        if (mediaType == MediaType.IMAGE) {
            defaultModel = DEFAULT_IMAGE_MODEL;
        } else if (mediaType == MediaType.VIDEO) {
            defaultModel = DEFAULT_VIDEO_MODEL;
        } else if (mediaType == MediaType.AUDIO) {
            // AUDIO 没有系统级默认 modelCode（音色与 model 一对多绑定），要求调用方显式传 modelName。
            // 这里直接抛错，避免拿错 model 导致鉴权/路由失败。
            throw new ServiceException("请先配置音频模型");
        } else {
            defaultModel = DEFAULT_TEXT_MODEL;
        }
        byName = aiModelConfigService.selectByModelCode(defaultModel);
        if (byName != null) {
            return requireModelType(byName, mediaType);
        }
        String category;
        if (mediaType == MediaType.IMAGE) {
            category = "image";
        } else if (mediaType == MediaType.VIDEO) {
            category = "video";
        } else if (mediaType == MediaType.AUDIO) {
            category = "audio";
        } else {
            category = "text";
        }
        AiModelConfigVo byCategory = aiModelConfigService.selectByCategoryWithHighestPriority(category);
        if (byCategory != null) {
            return requireModelType(byCategory, mediaType);
        }
        throw new ServiceException("未找到" + mediaType.name() + "模型配置，请先配置 aid_ai_model");
    }

    /** 未指定模型时仅从当前业务池选择首个支持目标能力的可用模型。 */
    private AiModelConfigVo resolveModel(String requestedModel, MediaType mediaType,
                                         String businessFuncCode, String capabilityCode) {
        if (StringUtils.isNotBlank(requestedModel) || StringUtils.isBlank(businessFuncCode)) {
            return resolveModel(requestedModel, mediaType);
        }
        java.util.LinkedHashSet<Long> orderedModelIds = modelBusinessBindings.forFunction(businessFuncCode).stream()
                .filter(binding -> StringUtils.isBlank(capabilityCode)
                        ? Boolean.TRUE.equals(binding.getDefaultCapability())
                        : Objects.equals(capabilityCode, binding.getCapabilityCode()))
                .map(com.aid.aid.domain.AidAiBusinessModelBinding::getModelId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (Long modelId : orderedModelIds) {
            AiModelConfigVo candidate = aiModelConfigService.selectByModelId(modelId);
            if (candidate == null || !mediaType.name().equalsIgnoreCase(candidate.getModelType())) continue;
            return candidate;
        }
        log.info("业务模型池无可用模型, funcCode={}, capabilityCode={}", businessFuncCode, capabilityCode);
        throw new ServiceException("业务暂无可用模型");
    }

    /**
     * 任务态提交、调度与轮询必须按任务所属用户解析凭证；COMPOSE 保持独立平台配置契约。
     */
    private void captureProviderRoute(AidMediaTask task, AiModelConfigVo config) {
        task.setProviderRouteSnapshotJson("tokendance".equalsIgnoreCase(config.getProviderCode())
                ? com.aid.tokendance.credential.TokenDanceTaskRouteSnapshot.capture(config)
                : com.aid.model.definition.ModelTaskRouteSnapshot.capture(config));
        if (config.getCapabilityCode() != null || "tokendance".equalsIgnoreCase(config.getProviderCode())) {
            task.setProtocol(config.getProtocol());
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.ModelTaskConfigurationResolver taskConfigurationResolver;

    AiModelConfigVo resolveTaskModelConfig(AidMediaTask task) {
        return taskConfigurationResolver.resolve(task);
    }

    /** 按调用媒体类型校验模型大类，防止图片/视频模型进入音频路由和错误计费。 */
    private AiModelConfigVo requireModelType(AiModelConfigVo modelConfig, MediaType mediaType) {
        String expectedType = mediaType.name();
        if (StringUtils.isBlank(modelConfig.getModelType())
                || !expectedType.equalsIgnoreCase(modelConfig.getModelType())) {
            log.error("媒体模型类型不匹配, modelCode={}, expected={}, actual={}",
                    modelConfig.getModelCode(), expectedType, modelConfig.getModelType());
            throw new ServiceException("模型类型错误");
        }
        return modelConfig;
    }

    /**
     * 幂等时间窗口：仅在该时间窗内的任务才参与幂等复用。
     * 避免幂等复用 30 天前的过期 OSS URL（OSS 签名 URL、源站 URL 可能早已失效）。
     * 同时防止跨月/跨批次的脏数据命中，影响资源链路的新一次调用。
     */
    private static final long IDEMPOTENT_WINDOW_MILLIS = 60L * 60L * 1000L; // 1 小时

    private AidMediaTask findRecentTaskByHash(String requestHash) {
        Date windowStart = new Date(System.currentTimeMillis() - IDEMPOTENT_WINDOW_MILLIS);
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<AidMediaTask>()
            .eq(AidMediaTask::getRequestHash, requestHash)
            .ne(AidMediaTask::getStatus, MediaTaskStatus.FAILED.name())
            .ge(AidMediaTask::getCreateTime, windowStart)
            .orderByDesc(AidMediaTask::getCreateTime)
            .last("limit 1");
        return aidMediaTaskMapper.selectOne(wrapper);
    }

    private AidMediaTask findTaskByIdempotencyKey(String idempotencyKey) {
        if (StrUtil.isBlank(idempotencyKey)) {
            return null;
        }
        return aidMediaTaskMapper.selectOne(new LambdaQueryWrapper<AidMediaTask>()
                .eq(AidMediaTask::getIdempotencyKey, idempotencyKey)
                .last("limit 1"));
    }

    private void validateTextIdempotencyWinner(AidMediaTask winner,
                                               MediaTextGenerateRequest request,
                                               Long effectiveUserId,
                                               String modelCode) {
        String storedAttemptId = null;
        if (StrUtil.isNotBlank(winner.getRequestJson())) {
            try {
                storedAttemptId = JSONUtil.parseObj(winner.getRequestJson()).getStr("billingAttemptId");
            } catch (Exception parseEx) {
                log.error("文本幂等winner请求快照无法解析, taskId={}", winner.getId(), parseEx);
            }
        }
        boolean sameIdentity = Objects.equals(effectiveUserId, winner.getUserId())
                && Objects.equals(request.getBizTaskId(), winner.getBizTaskId())
                && Objects.equals(StrUtil.blankToDefault(request.getBizTaskType(), null),
                        StrUtil.blankToDefault(winner.getBizTaskType(), null))
                && Objects.equals(modelCode, winner.getModelName())
                && Objects.equals(request.getBillingAttemptId(), storedAttemptId)
                && Objects.equals(buildRequestHash(MediaType.TEXT.name(), request, effectiveUserId),
                        winner.getRequestHash());
        if (!sameIdentity) {
            log.error("文本调用幂等身份冲突，拒绝复用: callId={}, winnerTaskId={}, "
                            + "requestUser={}, winnerUser={}, requestBiz={}/{}, winnerBiz={}/{}, "
                            + "requestModel={}, winnerModel={}, requestAttempt={}, winnerAttempt={}",
                    request.getCallId(), winner.getId(), effectiveUserId, winner.getUserId(),
                    request.getBizTaskType(), request.getBizTaskId(), winner.getBizTaskType(),
                    winner.getBizTaskId(), modelCode, winner.getModelName(),
                    request.getBillingAttemptId(), storedAttemptId);
            throw new ServiceException("调用身份冲突");
        }
    }

    /**
     * 幂等命中时的存量记录补偿：
     * 若任务 status=SUCCEEDED 但 oss_url 为空（persistOssIfNeeded 失败留下的坏记录），
     * 当场重试一次下载+持久化，成功后回写 oss_url 并触发业务回填。
     * 失败时保持静默，由定时补偿任务 mediaTask.ossCompensate 兜底。
     */
    private void repairExistingIfOssMissing(AidMediaTask existing) {
        if (!MediaTaskStatus.SUCCEEDED.name().equals(existing.getStatus())) {
            return;
        }
        if (StringUtils.isNotBlank(existing.getOssUrl()) || StringUtils.isBlank(existing.getOriginUrl())) {
            return;
        }
        try {
            log.info("幂等命中污染快照，触发 OSS 持久化修复, taskId={}", existing.getId());
            persistOssIfNeeded(existing);
            if (StringUtils.isNotBlank(existing.getOssUrl())) {
                updateTaskWithPayloadArchive(existing);
                tryInvokeGenResultCallback(existing);
                // OSS 就绪后通知业务侧监听器回填（配音场景尤其关键）。
                publishOssPersistedEventSafely(existing);
            }
        } catch (Exception ex) {
            log.error("幂等污染快照修复失败, taskId={}, error={}", existing.getId(), ex.getMessage(), ex);
        }
    }

    private String buildRequestHash(String mediaType, Object request, Long userId) {
        //    - 相同语义 payload 的不同 Java 版本/不同 JSON 库会产生不同 JSON 顺序，造成哈希漂移。
        //    - 这里使用反射按字段名字典序拼接（只拼基础类型 + 数组/集合转字符串），保证跨进程稳定。
        String stableBody = serializeStable(request);
        String payload = mediaType + "|" + userId + "|" + stableBody;
        return SecureUtil.sha256(payload);
    }

    /**
     * 稳定序列化：按字段名字典序拼接 key=value 对，值只取基础类型 / 集合 / 数组 / toString。
     * 不使用 JSON 库，避免 fastjson2 / jackson 在不同版本下字段顺序差异。
     *
     * @param obj 请求对象
     * @return 稳定的字符串表示
     */
    private String serializeStable(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof CharSequence || obj instanceof Number || obj instanceof Boolean) {
            return String.valueOf(obj);
        }
        if (obj instanceof java.util.Map<?, ?> map) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            sorted.forEach((k, v) -> sb.append(k).append("=").append(serializeStable(v)).append(";"));
            return sb.append("}").toString();
        }
        if (obj instanceof java.util.Collection<?> coll) {
            StringBuilder sb = new StringBuilder("[");
            for (Object e : coll) {
                sb.append(serializeStable(e)).append(",");
            }
            return sb.append("]").toString();
        }
        if (obj.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int len = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < len; i++) {
                sb.append(serializeStable(java.lang.reflect.Array.get(obj, i))).append(",");
            }
            return sb.append("]").toString();
        }
        // 反射按字段名字典序拼接（含父类字段），跳过 static/transient 字段
        java.util.TreeMap<String, Object> fieldValues = new java.util.TreeMap<>();
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isTransient(mod)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    fieldValues.put(f.getName(), f.get(obj));
                } catch (IllegalAccessException ignore) {
                    // 反射失败时跳过该字段
                }
            }
            clazz = clazz.getSuperclass();
        }
        StringBuilder sb = new StringBuilder(obj.getClass().getSimpleName()).append("{");
        fieldValues.forEach((k, v) -> sb.append(k).append("=").append(serializeStable(v)).append(";"));
        return sb.append("}").toString();
    }

    private Long getCurrentUserIdSafe() {
        if (!LoginHelper.isLogin()) {
            return null;
        }
        return LoginHelper.getUserId();
    }

    /**
     * 填充创建信息：createBy、createTime、updateBy、updateTime
     * 优先使用 task.userId（MQ消费等场景由调用方传入），否则从登录上下文获取。
     */
    private void fillCreateInfo(AidMediaTask task) {
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";
        Date now = new Date();
        task.setCreateBy(userStr);
        task.setCreateTime(now);
        task.setUpdateBy(userStr);
        task.setUpdateTime(now);
    }

    /**
     * 填充更新信息：updateBy、updateTime
     */
    private MediaTaskArchiveService.PreparedTerminalPayload fillUpdateInfo(AidMediaTask task) {
        if (MediaTaskStatus.FAILED.name().equals(task.getStatus())) {
            if (TaskErrorSnapshot.read(task.getErrorDetailJson()) == null) {
                task.setErrorDetailJson(TaskErrorSnapshot.write(ErrorNormalizer.normalize(
                        String.valueOf(task.getId()), null, task.getModelName(), -1, task.getErrorMessage())));
            }
        } else {
            task.setErrorDetailJson(null);
        }
        if (MediaType.TEXT.name().equals(task.getMediaType())) {
            // Provider 适配器是第一道防线；统一写库/归档边界再次剔除明文思维链，
            // 防止未来新增 Provider 遗漏脱敏后污染 response_json、归档或错误字段。
            task.setResponseJson(ReasoningContentSanitizer.sanitizeJson(task.getResponseJson()));
            if (StringUtils.isNotBlank(task.getErrorMessage())) {
                task.setErrorMessage(ProviderErrorSanitizer.safeMessage(task.getErrorMessage(), "文本生成失败"));
            }
        }
        if ((MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || MediaTaskStatus.FAILED.name().equals(task.getStatus()))
                && task.getTerminalTime() == null) {
            task.setTerminalTime(new Date());
        }
        // 终态统一压缩 request_json、清空 response_json；开关开启时先保留不可变快照并异步归档。
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload =
            mediaTaskArchiveService.prepareTerminalPayload(task, task.getStatus(), task.getResponseJson());
        task.setRequestJson(preparedPayload.getRequestJson());
        task.setResponseJson(preparedPayload.getResponseJson());
        // 模型结果和错误信息统一过存储守卫，禁止任何文件型编码进入数据库。
        task.setResultText(MediaTaskPayloadSanitizer.sanitizeForStorage(task.getResultText()));
        task.setErrorMessage(MediaTaskPayloadSanitizer.sanitizeForStorage(task.getErrorMessage()));
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";
        task.setUpdateBy(userStr);
        task.setUpdateTime(new Date());
        return preparedPayload;
    }

    /**
     * 更新媒体任务并在数据库写入成功后登记可选归档，事务提交失败时不会产生孤儿文件。
     */
    private int updateTaskWithPayloadArchive(AidMediaTask task) {
        return updateTaskWithPayloadArchive(task, true);
    }

    /**
     * @param archiveAllowed 是否允许本路径生成归档；终态 CAS 失败者必须传 false 防止重复归档
     */
    private int updateTaskWithPayloadArchive(AidMediaTask task, boolean archiveAllowed) {
        MediaTaskArchiveService.PreparedTerminalPayload preparedPayload = fillUpdateInfo(task);
        int rows = aidMediaTaskMapper.updateById(task);
        if (rows > 0 && archiveAllowed) {
            mediaTaskArchiveService.archiveAfterCommit(preparedPayload);
        }
        return rows;
    }

    private void safeNotifyTextSink(Long taskId, String event, Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException observerError) {
            // 客户端断开或业务观察者异常不能反向改变媒体任务与账务终态。
            log.info("文本流观察者已断开, taskId={}, event={}, errorType={}", taskId, event,
                    observerError.getClass().getSimpleName());
        }
    }

    /**
     * 批量落库前的内存载体：任务行与当时解析出的模型配置，供 insert 后 prepareBilling 使用同一套计费参数。
     *
     * @param task        待插入的 aid_media_task 行（尚未提交上游）
     * @param modelConfig 与该任务一致的模型配置（含单价等）
     */
    private record PreparedBatchUnit(AidMediaTask task, AiModelConfigVo modelConfig, BillingInput billingInput) {
    }

    /**
     * 将单个批量项转为「任务 + 模型配置」：校验类型与入参，并与单条 generateImage/generateVideo 字段语义对齐。
     */
    private PreparedBatchUnit buildPreparedBatchUnit(MediaBatchGenerateRequest.BatchGenerateItem item,
                                                     String batchId, Long userId, int ordinal,
                                                     MediaBatchGenerateRequest batchRequest) {
        String mediaTypeNorm = normalizeBatchMediaType(item.getMediaType());
        if (mediaTypeNorm == null) {
            throw new ServiceException("媒体类型不支持");
        }
        if (Objects.equals(MediaType.IMAGE.name(), mediaTypeNorm)) {
            MediaImageGenerateRequest imgReq = item.getImageRequest();
            if (imgReq == null) {
                throw new ServiceException("图片参数不能为空");
            }
            AiModelConfigVo modelConfig = invocationResolver.select(resolveModel(imgReq.getModelName(), MediaType.IMAGE), imgReq.getCapabilityCode());
            validateAndNormalizeProtectedImageEdit(imgReq, modelConfig);
            invocationResolver.normalize(modelConfig, imgReq);
            validatePromptForModel(modelConfig, imgReq.getPrompt());
            ModelCapabilityValidator.validatePrompt(modelConfig, imgReq.getPrompt());
            ImageProviderClient imageClient = resolveImageClient(imgReq.getModelName(), modelConfig);
            ModelInputCapabilityValidator.validateRawImageInputs(modelConfig, imgReq);
            ReferenceMediaRequestNormalizer.normalize(modelConfig, imgReq,
                    imageClient.fallbackMaxReferenceImages(modelConfig));
            ModelInputCapabilityValidator.normalizeAndValidateImage(modelConfig, imgReq);
            verifiedMediaInputService.image(modelConfig, imgReq);
            validateMinReferenceImages(modelConfig, countImageRequestReferenceImages(imgReq));
            // 批量与单条图片生成共用同一能力归一化和场景校验，禁止两条入口出现不同结果。
            ModelCapabilityValidator.normalizeImageAspectRatio(modelConfig, imgReq);
            ModelCapabilityValidator.validateImage(modelConfig, imgReq);
            ImageProviderClient client = resolveImageClient(imgReq.getModelName(), modelConfig);
            client.validateRequest(modelConfig, imgReq);
            AidMediaTask task = new AidMediaTask();
            task.setUserId(userId);
            // 关联项目/剧集：子项优先，否则取批量顶层。
            task.setProjectId(imgReq.getProjectId() != null ? imgReq.getProjectId() : batchRequest.getProjectId());
            task.setEpisodeId(imgReq.getEpisodeId() != null ? imgReq.getEpisodeId() : batchRequest.getEpisodeId());
            task.setMediaType(MediaType.IMAGE.name());
            task.setProtocol(client.protocol());
            //    走 selectByModelCode 反查配置，必须是 model_code 而非别名/上游名；provider 路由仍用 requestJson 内原始 modelName。
            task.setModelName(modelConfig.getModelCode());
            captureProviderRoute(task, modelConfig);
            // 若业务方显式传入 taskPromptDigest（如智能体模板批量生图），优先存 digest 摘要，避免 TEXT 列截断。
            task.setPrompt(summarizeImagePromptForTask(imgReq));
            task.setRequestHash(buildRequestHash(MediaType.IMAGE.name() + "|" + batchId + "|" + ordinal, imgReq, userId));
            task.setRequestJson(MediaTaskPayloadSanitizer.serializeRequest(imgReq));
            //    统一「PENDING=已占槽」语义，避免 watchdog/启动重建把未占槽的任务误算为占槽。
            task.setStatus(MediaTaskStatus.QUEUED.name());
            task.setBillingStatus(MediaBillingStatus.INIT.name());
            task.setRetryCount(0);
            task.setCallbackRecordId(imgReq.getRecordId());
            task.setCallbackCategory(imgReq.getCategory());
            task.setBatchId(batchId);
            fillCreateInfo(task);
            // 批量场景也必须把最终 modelCode 传进计费提取器，避免前端不传 modelName 导致上限保护失效
            // 同步把 max_output_count 透传过去，让后台配置覆盖硬编码上限。
            return new PreparedBatchUnit(task, modelConfig,
                    BillingInputExtractor.fromImageRequest(
                        imgReq, task.getModelName(),
                        modelConfig == null ? null : modelConfig.getMaxOutputCount(), modelConfig));
        }
        if (Objects.equals(MediaType.VIDEO.name(), mediaTypeNorm)) {
            MediaVideoGenerateRequest vidReq = item.getVideoRequest();
            if (vidReq == null) {
                throw new ServiceException("视频参数不能为空");
            }
            if (CollectionUtil.isNotEmpty(vidReq.getReferenceVideoRecordIds())) {
                throw new ServiceException("批量暂不支持参考视频");
            }
            AiModelConfigVo modelConfig = invocationResolver.select(resolveModel(vidReq.getModelName(), MediaType.VIDEO), vidReq.getCapabilityCode());
            invocationResolver.normalize(modelConfig, vidReq);
            validatePromptForModel(modelConfig, vidReq.getPrompt());
            ModelCapabilityValidator.validatePrompt(modelConfig, vidReq.getPrompt());
            validateVideoProviderContract(modelConfig, vidReq);
            ModelInputCapabilityValidator.validateRawVideoInputs(modelConfig, vidReq);
            Wan3VideoRequestBuilder.validateRawInputs(modelConfig, vidReq);
            AgnesVideo25RequestBuilder.validateRawInputs(modelConfig, vidReq);
            VideoProviderClient videoClient = resolveVideoClient(vidReq.getModelName(), modelConfig);
            ReferenceMediaRequestNormalizer.normalize(modelConfig, vidReq,
                    videoClient.fallbackMaxReferenceImages(modelConfig),
                    videoClient.fallbackMaxReferenceVideos(modelConfig));
            validateVideoRequestReferenceInputs(modelConfig, vidReq);
            if (ModelCapabilityResolver.isVideoAspectRatioFollowInput(modelConfig)
                    && StrUtil.isBlank(vidReq.getAspectRatio())) {
                vidReq.setAspectRatio(ModelCapabilityResolver.resolveVideoAspectRatio(modelConfig, null));
            }
            // 批量视频与单条视频使用同一比例语义；跟随输入模型保留内部目标比例，提交前统一归一化输入图。
            ModelCapabilityValidator.normalizeVideoAspectRatio(modelConfig, vidReq);
            if (!isLipSyncRequest(modelConfig, vidReq)) {
                ModelCapabilityValidator.validateVideo(modelConfig, vidReq.getDurationSeconds(),
                        vidReq.getAspectRatio(), vidReq.getOptions());
            }
            ModelCapabilityValidator.normalizeAndValidateVideoAudio(modelConfig, vidReq);
            ModelInputCapabilityValidator.validateVideoQuote(modelConfig, vidReq, false);
            verifiedMediaInputService.video(modelConfig, vidReq);
            invocationResolver.validateVerifiedMaterials(modelConfig, vidReq);
            ModelCapabilityValidator.normalizeAndValidateReferenceAudios(modelConfig, vidReq);
            ModelInputCapabilityValidator.validateVideo(modelConfig, vidReq);
            validateVideoProviderContract(modelConfig, vidReq);
            VideoProviderClient client = resolveVideoClient(vidReq.getModelName(), modelConfig);
            client.validateRequest(modelConfig, vidReq);
            AidMediaTask task = new AidMediaTask();
            task.setUserId(userId);
            // 关联项目/剧集：子项优先，否则取批量顶层。
            task.setProjectId(vidReq.getProjectId() != null ? vidReq.getProjectId() : batchRequest.getProjectId());
            task.setEpisodeId(vidReq.getEpisodeId() != null ? vidReq.getEpisodeId() : batchRequest.getEpisodeId());
            task.setMediaType(MediaType.VIDEO.name());
            task.setProtocol(client.protocol());
            task.setModelName(modelConfig.getModelCode());
            captureProviderRoute(task, modelConfig);
            // 若业务方显式传入 taskPromptDigest（如智能体模板批量视频），优先存 digest 摘要，避免 TEXT 列截断。
            task.setPrompt(summarizeVideoPromptForTask(vidReq));
            task.setRequestHash(buildRequestHash(MediaType.VIDEO.name() + "|" + batchId + "|" + ordinal, vidReq, userId));
            task.setRequestJson(MediaTaskPayloadSanitizer.serializeRequest(vidReq));
            // 批量任务入库即 QUEUED（尚未占并发槽）：由 submitSingleTaskAsync 占槽成功后 CAS 改 PENDING。
            task.setStatus(MediaTaskStatus.QUEUED.name());
            task.setBillingStatus(MediaBillingStatus.INIT.name());
            task.setRetryCount(0);
            task.setCallbackRecordId(vidReq.getRecordId());
            task.setCallbackCategory(vidReq.getCategory());
            task.setBatchId(batchId);
            fillCreateInfo(task);
            return new PreparedBatchUnit(task, modelConfig, BillingInputExtractor.fromVideoRequest(vidReq, modelConfig));
        }
        throw new ServiceException("媒体类型不支持");
    }

    /**
     * 将前端传入的 mediaType 转为大写枚举名，仅接受 IMAGE / VIDEO。
     */
    private static String normalizeBatchMediaType(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (Objects.equals(MediaType.IMAGE.name(), u) || Objects.equals(MediaType.VIDEO.name(), u)) {
            return u;
        }
        return null;
    }

    @Override
    public void submitComposeTaskAsync(Long taskId) {
        // 复用 submitSingleTaskAsync 的「tryAcquire → CAS(QUEUED→PENDING) → doSubmitToProvider」机制：
        // CoreComposeService 已将任务落库为 QUEUED 并预冻结，这里只负责异步拉起提交，并发已满则保持 QUEUED 等 drainQueue。
        if (Objects.isNull(taskId)) {
            return;
        }
        try {
            threadPoolTaskExecutor.execute(() -> submitSingleTaskAsync(taskId));
        } catch (Exception rejectEx) {
            // 线程池拒绝：任务保持 QUEUED，由后续 drainQueue / watchdog 兜底拉起。
            log.warn("compose 提交线程池被拒绝, 保留QUEUED 等待后续调度, taskId={}", taskId, rejectEx);
        }
    }

    /**
     * 按任务快照中的 protocol 提交 COMPOSE 合成任务。云端成功后回填 providerTaskId 并接入
     * 回调优先、轮询补偿调度；本地 FFmpeg 同步校验输出后也走统一终态收口。
     * 明确失败标 FAILED 并走 ComposeBillingService 退款。调用前须已抢占并发坑位（PENDING）。
     *
     * @param task COMPOSE 任务
     */
    private void doSubmitComposeToProvider(AidMediaTask task) {
        long submitStartMs = System.currentTimeMillis();
        try {
            VideoProviderClient client = getVideoClient(task.getProtocol());
            if (Objects.isNull(client)) {
                log.error("compose submit 未命中 MPS Provider, taskId={}, protocol={}", task.getId(), task.getProtocol());
                throw new ServiceException("合成失败，请重试");
            }
            MediaVideoGenerateRequest req = buildComposeSubmitRequest(task);
            log.info("compose submit 开始, taskId={}, protocol={}", task.getId(), task.getProtocol());
            ProviderSubmitResult submitResult = client.submit(null, req);
            // 本地 FFmpeg 在 submit 内以退出码 + ffprobe 同步确认终态；仍先写入统一调度态，
            // 直接从 PENDING 走 TaskCompletionService 的同一 CAS 收口，不提前写入无法查询的本地任务号。
            // 若进程在上传后、终态提交前退出，任务仍保持“未提交”语义，可由看门狗重新排队。
            if (submitResult != null && StringUtils.isNotBlank(submitResult.getDirectUrl())) {
                long duration = submitResult.getAudioDurationMs() == null ? 0L
                        : (submitResult.getAudioDurationMs() + 999L) / 1000L;
                ProviderTaskResult terminal = ProviderTaskResult.builder()
                        .status(MediaTaskStatus.SUCCEEDED.name())
                        .resultUrl(submitResult.getDirectUrl())
                        .outputDurationSeconds(duration)
                        .rawResponse(submitResult.getRawResponse())
                        .providerStatus("Success")
                        .querySuccessful(Boolean.TRUE)
                        .terminalConfirmed(Boolean.TRUE)
                        .build();
                boolean completed = taskCompletionService.completeTask(task.getId(), terminal);
                log.info("compose local completed, taskId={}, elapsedMs={}",
                        task.getId(), System.currentTimeMillis() - submitStartMs);
                if (!completed) {
                    log.warn("compose local terminal CAS not won, taskId={}", task.getId());
                }
                return;
            }
            String providerTaskId = submitResult == null ? null : submitResult.getProviderTaskId();
            if (StringUtils.isBlank(providerTaskId)) {
                String err = submitResult == null ? "提交失败" : extractErrorMessage(submitResult.getRawResponse());
                log.error("compose submit 未返回 providerTaskId, taskId={}, err={}", task.getId(), err);
                failCompose(task, err);
                return;
            }
            // 提交成功：回填 providerTaskId + 接入合成调度（回调优先 + 轮询兜底），不在此结算（结算在终态收口）。
            task.setProviderTaskId(providerTaskId);
            task.setResponseJson(submitResult.getRawResponse());
            taskDispatchService.initComposeDispatchSchedule(task);
            requiresNewTxTemplate.executeWithoutResult(s -> updateTaskWithPayloadArchive(task));
            log.info("compose submit accepted, taskId={}, providerTaskId={}, elapsedMs={}",
                    task.getId(), providerTaskId, System.currentTimeMillis() - submitStartMs);
        } catch (com.aid.compose.exception.ComposeUpstreamUnavailableException ex) {
            if (MpsVideoProviderClient.PROTOCOL_MPS.equalsIgnoreCase(task.getProtocol())
                    || com.aid.compose.config.MpsConfigManager.MODE_ALIYUN_IMS.equals(task.getProtocol())) {
                // 云厂商已提供幂等键和回调上下文。首次提交结果不确定时保留 PENDING 与并发槽，
                // 优先等待回调认领；超过僵尸阈值后再由补偿任务重排并执行幂等恢复。
                log.warn("compose cloud submit result unknown, keep pending for callback, taskId={}, protocol={}, error={}",
                        task.getId(), task.getProtocol(), ex.getMessage());
            } else {
                boolean requeued = taskCompletionService.requeueUnsubmittedTask(task.getId());
                log.warn("compose submit result unknown, requeue taskId={}, requeued={}, error={}",
                        task.getId(), requeued, ex.getMessage());
            }
        } catch (Exception ex) {
            log.error("compose submit failed, taskId={}", task.getId(), ex);
            failCompose(task, StringUtils.defaultIfBlank(ex.getMessage(), "提交失败"));
        }
    }

    /**
     * COMPOSE 提交失败收口：标 FAILED（用户文案 ≤6 字）+ ComposeBillingService 退款 + 释放并发坑位。
     *
     * @param task         COMPOSE 任务
     * @param errorDetail  详细错误（落 episode_editor.error_msg / 日志，不直接给用户）
     */
    private void failCompose(AidMediaTask task, String errorDetail) {
        task.setStatus(MediaTaskStatus.FAILED.name());
        task.setErrorMessage("合成失败");
        try {
            requiresNewTxTemplate.executeWithoutResult(s -> {
                updateTaskWithPayloadArchive(task);
                ProviderTaskResult failResult = ProviderTaskResult.builder()
                        .status(MediaTaskStatus.FAILED.name())
                        .errorMessage(errorDetail)
                        .build();
                composeCompletionService.onFailed(task, failResult);
            });
        } catch (Exception ex) {
            log.error("compose 失败回写/退款异常, taskId={}", task.getId(), ex);
        }
        releaseConcurrencyAfterCompletion(task);
    }

    /**
     * 还原 COMPOSE 任务的 EditMedia 请求体并包装为 MediaVideoGenerateRequest（经 options 透传给 MpsVideoProviderClient）。
     *
     * @param task COMPOSE 任务
     * @return 提交请求
     */
    private MediaVideoGenerateRequest buildComposeSubmitRequest(AidMediaTask task) {
        MediaVideoGenerateRequest req = new MediaVideoGenerateRequest();
        req.setModelName(task.getModelName());
        req.setUserId(task.getUserId());
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        cn.hutool.json.JSONObject archived = JSONUtil.parseObj(task.getRequestJson());
        if (com.aid.compose.config.MpsConfigManager.MODE_TENCENT_MPS.equals(task.getProtocol())) {
            Object editMediaBody = archived.containsKey("editMediaRequest")
                    ? archived.get("editMediaRequest") : archived;
            options.put(com.aid.media.provider.impl.MpsVideoProviderClient.OPTION_EDIT_MEDIA_REQUEST, editMediaBody);
        }
        if (archived.containsKey("composePlan")) {
            options.put(com.aid.media.provider.impl.AliyunImsVideoProviderClient.OPTION_COMPOSE_PLAN,
                    archived.get("composePlan"));
        }
        req.setOptions(options);
        return req;
    }

    /** The durable QUEUED row is the fallback when the shared executor is saturated or shutting down. */
    private void dispatchDeferredTask(Long taskId) {
        Thread caller = Thread.currentThread();
        AtomicBoolean callerFallback = new AtomicBoolean();
        try {
            threadPoolTaskExecutor.execute(new FutureTask<Void>(() -> {
                // The shared executor uses CallerRunsPolicy; never submit to a provider on the HTTP thread.
                if (Thread.currentThread() != caller) {
                    submitSingleTaskAsync(taskId);
                } else {
                    callerFallback.set(true);
                }
                return null;
            }));
            if (callerFallback.get()) {
                log.warn("异步提交线程池已满，任务留在队列等待补偿调度, taskId={}", taskId);
            }
        } catch (RuntimeException rejected) {
            log.warn("异步提交暂不可用，任务留在队列等待补偿调度, taskId={}", taskId, rejected);
        }
    }

    /** 批量及非流式提交共用：任务已落库、预冻结后抢占并发槽，CAS 入执行态。 */
    private void submitSingleTaskAsync(Long taskId) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("batch submit skipped, task not found, taskId={}", taskId);
            return;
        }
        if (!MediaTaskStatus.QUEUED.name().equals(task.getStatus())) {
            log.info("batch submit 跳过, taskId={} 状态已变更为{}", taskId, task.getStatus());
            return;
        }
        // 四维并发准入：模型编码取任务落库的规范 model_name。
        boolean canRun = concurrencyLimiter.tryAcquire(task.getUserId(), task.getModelName());
        if (!canRun) {
            return;
        }
        //    竞争失败（已被取消/其它线程拉起）则释放刚抢占的坑位并继续调度，避免泄漏。
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";
        LambdaUpdateWrapper<AidMediaTask> casWrapper = new LambdaUpdateWrapper<>();
        casWrapper.eq(AidMediaTask::getId, taskId);
        casWrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        casWrapper.set(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name());
        casWrapper.set(AidMediaTask::getUpdateBy, userStr);
        casWrapper.set(AidMediaTask::getUpdateTime, new Date());
        int rows = aidMediaTaskMapper.update(null, casWrapper);
        if (rows == 0) {
            log.info("batch submit 竞争失败, taskId={} 已被处理，释放坑位", taskId);
            concurrencyLimiter.release(task.getUserId(), task.getModelName());
            drainQueue();
            return;
        }
        task.setStatus(MediaTaskStatus.PENDING.name());
        doSubmitToProvider(task);
    }

    /**
     * 任务到达终态时释放并发坑位，并触发排队任务消费（安全包装，异常不影响主流程）。
     */
    /**
     * 从上游响应中提取错误信息。
     */
    private String extractErrorMessage(String rawResponse) {
        if (StringUtils.isBlank(rawResponse)) {
            return "上游响应为空";
        }
        try {
            JSONObject json = JSONObject.parseObject(rawResponse);
            String msg = null;
            String code = null;
            // Gemini 特有：error.status（如 RESOURCE_EXHAUSTED / PERMISSION_DENIED / INVALID_ARGUMENT）
            String status = null;
            // Gemini 特有：error.details[].reason（细分错误原因）
            String reason = null;
            JSONObject errorObj = json.getJSONObject("error");
            if (errorObj != null) {
                msg = errorObj.getString("message");
                code = errorObj.getString("code");
                if (StringUtils.isBlank(code)) {
                    code = errorObj.getString("type");
                }
                // Gemini: error.status
                status = errorObj.getString("status");
                // Gemini: error.details 是数组，每个元素可能含 reason / @type / domain / metadata
                com.alibaba.fastjson2.JSONArray detailsArr = errorObj.getJSONArray("details");
                if (detailsArr != null && !detailsArr.isEmpty()) {
                    for (int i = 0; i < detailsArr.size(); i++) {
                        JSONObject detail = detailsArr.getJSONObject(i);
                        if (detail == null) {
                            continue;
                        }
                        String r = detail.getString("reason");
                        if (StringUtils.isNotBlank(r)) {
                            reason = r;
                            break;
                        }
                    }
                }
            }
            if (StringUtils.isBlank(msg)) {
                msg = json.getString("message");
            }
            if (StringUtils.isBlank(code)) {
                code = json.getString("code");
            }
            if (StringUtils.isBlank(msg)) {
                String fallback = json.getString("status_msg");
                if (StringUtils.isBlank(fallback)) {
                    fallback = json.getString("msg");
                }
                if (StringUtils.isBlank(fallback)) {
                    fallback = json.getString("error_msg");
                }
                msg = fallback;
            }
            if (StringUtils.isNotBlank(msg) || StringUtils.isNotBlank(code)
                    || StringUtils.isNotBlank(status) || StringUtils.isNotBlank(reason)) {
                StringBuilder sb = new StringBuilder();
                if (StringUtils.isNotBlank(code)) {
                    sb.append("code=").append(code);
                }
                if (StringUtils.isNotBlank(status)) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append("status=").append(status);
                }
                if (StringUtils.isNotBlank(reason)) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append("reason=").append(reason);
                }
                if (StringUtils.isNotBlank(msg)) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append("message=").append(msg);
                }
                return sb.toString();
            }
            // JSON 可解析但无已知错误字段，截断原始响应
            return rawResponse.length() > 200 ? rawResponse.substring(0, 200) : rawResponse;
        } catch (Exception e) {
            // 非 JSON，直接返回原文（截断防超长）
            return rawResponse.length() > 200 ? rawResponse.substring(0, 200) : rawResponse;
        }
    }

    /**
     * 释放并发坑位并触发排队消费。
     * 如果在事务中，挂到 afterCommit 执行，确保 DB 终态落库后才释放名额和拉起新任务；
     * 无事务上下文时直接执行（降级）。
     */
    private void releaseConcurrency(AidMediaTask task) {
        Long userId = task.getUserId();
        // 释放需带模型编码：四维限流按 全局/用户/模型/供应商 各自计数，缺一会导致模型/供应商维度泄漏。
        String modelName = task.getModelName();
        Runnable action = () -> {
            try {
                concurrencyLimiter.release(userId, modelName);
            } catch (Exception e) {
                log.warn("释放并发坑位异常, userId={}, modelName={}", userId, modelName, e);
            }
            drainQueue();
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 事务回滚后释放并发坑位并拉起排队任务（用于预冻结失败等回滚场景）。
     * 无论事务提交还是回滚，都会执行释放。
     */
    private void releaseConcurrencyAfterCompletion(AidMediaTask task) {
        Long userId = task.getUserId();
        // 释放需带模型编码：四维限流按 全局/用户/模型/供应商 各自计数，缺一会导致模型/供应商维度泄漏。
        String modelName = task.getModelName();
        Runnable action = () -> {
            try {
                concurrencyLimiter.release(userId, modelName);
            } catch (Exception e) {
                log.warn("释放并发坑位异常, userId={}, modelName={}", userId, modelName, e);
            }
            drainQueue();
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 计费 CAS 失败后，从 DB 重新读取赢家已落库的终态字段，。
     *
     * @param task       当前任务（内存对象）
     * @param billingWon settleBilling / refundBilling 的返回值
     */
    private void syncTerminalFieldsIfNeeded(AidMediaTask task, boolean billingWon) {
        if (!billingWon) {
            AidMediaTask dbTask = aidMediaTaskMapper.selectById(task.getId());
            if (dbTask != null) {
                task.setStatus(dbTask.getStatus());
                task.setBillingStatus(dbTask.getBillingStatus());
                task.setErrorMessage(dbTask.getErrorMessage());
                task.setErrorDetailJson(dbTask.getErrorDetailJson());
                task.setTerminalTime(dbTask.getTerminalTime());
                task.setOriginUrl(dbTask.getOriginUrl());
                task.setResultText(dbTask.getResultText());
                task.setOssUrl(dbTask.getOssUrl());
            }
        }
    }

    /**
     * 冻结金额退款兜底：优先走任务级 refundBilling，失败且 DB 已记 FROZEN 则直接退回账户。
     * 用于「建任务/预冻结短事务失败」时，撤销可能已通过 REQUIRES_NEW 独立提交的冻结。
     */
    private void refundFrozenIfNeeded(AidMediaTask task, String reason) {
        boolean refunded = false;
        try {
            refunded = billingFacadeService.refundBilling(task);
        } catch (Exception refundEx) {
            log.error("退款失败, 需人工介入, taskId={}", task.getId(), refundEx);
        }
        if (!refunded && MediaBillingStatus.FROZEN.name().equals(task.getBillingStatus())
                && task.getUserId() != null && task.getFrozenAmount() != null
                && task.getFrozenAmount().compareTo(BigDecimal.ZERO) > 0) {
            try {
                accountUpdateService.refund(task.getUserId(), task.getFrozenAmount(),
                        task.getBillingTraceId(), "refund", reason);
            } catch (Exception directEx) {
                log.error("直接账户退款失败, 需人工介入, traceId={}", task.getBillingTraceId(), directEx);
            }
        }
    }

    /**
     * 将已抢占坑位的任务提交到上游 provider（图片/视频/文本通用）。
     * 调用前须确保已成功抢占并发坑位。
     * 终态时由 handleSubmitResult / catch 分支调用 releaseConcurrency 释放坑位。
     */
    private void doSubmitToProvider(AidMediaTask task) {
        doSubmitToProvider(task, null);
    }

    private void doSubmitToProvider(AidMediaTask task, MediaTextGenerateRequest liveTextRequest) {
        try (com.aid.diagnostics.DiagnosticCapture.TaskScope scope = com.aid.diagnostics.DiagnosticCapture.task(task)) {
            doSubmitToProviderCaptured(task, liveTextRequest);
        }
    }

    private void doSubmitToProviderCaptured(AidMediaTask task, MediaTextGenerateRequest liveTextRequest) {
        // COMPOSE 合成任务走独立提交分支（MPS Provider + ComposeBillingService），
        // 不与图片/视频/TTS/文本共用 billingFacadeService 计费链路；非 COMPOSE 任务逻辑保持不变。
        if (com.aid.compose.ComposeConstants.MEDIA_TYPE_COMPOSE.equals(task.getMediaType())) {
            doSubmitComposeToProvider(task);
            return;
        }
        // submit 起止计时基准（声明在 try 外，保证 catch 也能算 elapsed）。
        long submitStartMs = System.currentTimeMillis();
        ProviderSubmitResult submitResult = null;
        try {
            AiModelConfigVo modelConfig = resolveTaskModelConfig(task);
            if (modelConfig == null) {
                throw new ServiceException("模型未配置");
            }
            // 事务边界守护：provider 远程调用绝不应在活跃 DB 事务内执行。若检测到活跃事务，说明上层调用方
            // （见 R6 审计：StoryboardWorkbench / MediaGenerationBiz 等 @Transactional 方法）仍把 submit 包在
            // 自身事务内，打 WARN 暴露，便于后续收口；此处不中断，避免影响既有调用方。
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                log.warn("provider 提交时线程仍处于活跃事务内, taskId={}, model={}",
                        task.getId(), task.getModelName());
            }
            // 起始日志 + 重置计时基准（排除配置查询耗时，只统计远程调用本身）。
            log.info("provider 提交开始, taskId={}, protocol={}, model={}",
                    task.getId(), task.getProtocol(), task.getModelName());
            submitStartMs = System.currentTimeMillis();
            if (Objects.equals(task.getMediaType(), MediaType.IMAGE.name())) {
                MediaImageGenerateRequest imgReq = JSONUtil.toBean(task.getRequestJson(), MediaImageGenerateRequest.class);
                modelOutboundResourcePreparer.prepare(modelConfig, imgReq);
                ImageProviderClient client = resolveImageClient(imgReq.getModelName(), modelConfig);
                submitResult = client.submit(modelConfig, imgReq);
            } else if (Objects.equals(task.getMediaType(), MediaType.VIDEO.name())) {
                MediaVideoGenerateRequest vidReq = JSONUtil.toBean(task.getRequestJson(), MediaVideoGenerateRequest.class);
                if (("tokendance".equalsIgnoreCase(modelConfig.getProviderCode())
                        || DmcH3VideoRequestBuilder.PROTOCOL.equalsIgnoreCase(modelConfig.getProtocol()))
                        && vidReq.getReferenceVideoRecordIds() != null && !vidReq.getReferenceVideoRecordIds().isEmpty()) {
                    // 内部可信 DTO 不写任务 JSON；队列拉起后按任务归属重建，不能把裸 URL 当已核验素材。
                    vidReq.setUserId(task.getUserId());
                    vidReq.setProjectId(task.getProjectId());
                    referenceVideoRecordResolver.resolveAndApply(vidReq, task.getUserId(), true);
                    ModelInputCapabilityValidator.validateVideo(modelConfig, vidReq);
                }
                if (videoInputAspectRatioNormalizer.normalize(modelConfig, vidReq)) {
                    task.setRequestJson(MediaTaskPayloadSanitizer.serializeRequest(vidReq));
                    // 归一化后的临时 URL 必须先持久化，应用重启后仍可继续提交并在终态清理。
                    requiresNewTxTemplate.executeWithoutResult(s -> updateTaskWithPayloadArchive(task));
                }
                modelOutboundResourcePreparer.prepare(modelConfig, vidReq);
                VideoProviderClient client = resolveVideoClient(vidReq.getModelName(), modelConfig);
                if ("dmc-h3-video".equalsIgnoreCase(modelConfig.getProtocol())) {
                    vidReq.setProviderIdempotencyKey("aid-dmc-" + task.getId());
                }
                submitResult = client.submit(modelConfig, vidReq);
            } else if (Objects.equals(task.getMediaType(), MediaType.AUDIO.name())) {
                com.aid.media.dto.MediaAudioGenerateRequest audioReq =
                        JSONUtil.toBean(task.getRequestJson(), com.aid.media.dto.MediaAudioGenerateRequest.class);
                audioReq.setUserId(task.getUserId());
                modelOutboundResourcePreparer.prepare(modelConfig, audioReq);
                com.aid.media.provider.AudioProviderClient audioClient =
                        resolveAudioClient(audioReq.getModelName(), modelConfig);
                submitResult = audioClient.submit(modelConfig, audioReq);
            } else if (Objects.equals(task.getMediaType(), MediaType.TEXT.name())) {
                MediaTextGenerateRequest textReq = liveTextRequest != null ? liveTextRequest
                        : JSONUtil.toBean(task.getRequestJson(), MediaTextGenerateRequest.class);
                modelOutboundResourcePreparer.prepare(modelConfig, textReq);
                TextProviderClient client = resolveTextClient(textReq.getModelName(), modelConfig);
                TextGenerationControl.normalize(textReq, false);
                client.validateProviderConfiguration(modelConfig, textReq);
                client.validateRequest(modelConfig, textReq);
                boolean useNonStream = !TextGenerationControl.isStreaming(textReq);
                log.info("排队拉起文本任务: taskId={}, mode={}", task.getId(),
                        useNonStream ? "NON_STREAM" : "STREAM");
                markTextProviderCallStarted(task, textReq);
                submitResult = useNonStream
                        ? client.chatSync(modelConfig, textReq)
                        : client.submit(modelConfig, textReq);
            } else {
                throw new ServiceException("不支持的媒体类型");
            }
            // submit 结束日志：正常返回 INFO（含 elapsed），慢调用额外 WARN。
            long submitElapsedMs = System.currentTimeMillis() - submitStartMs;
            log.info("provider 提交结束, taskId={}, model={}, elapsedMs={}, status=OK",
                    task.getId(), task.getModelName(), submitElapsedMs);
            if (submitElapsedMs > SLOW_SUBMIT_WARN_MS) {
                log.warn("provider 慢调用, taskId={}, model={}, elapsedMs={}（超过 {}ms 阈值）",
                        task.getId(), task.getModelName(), submitElapsedMs, SLOW_SUBMIT_WARN_MS);
            }
            //    settleBilling/refundBilling 为 REQUIRED 传播，若不收进本事务会 join 上层调用方事务并锁住
            //    aid_media_task 行，随后的 updateById 又用新事务更新同一行 → 行锁互等/死锁。
            //    合并到同一独立事务后：计费 CAS 与终态 updateById 同事务提交，且不受上层事务影响。
            final ProviderSubmitResult sr = submitResult;
            boolean terminalWon = Boolean.TRUE.equals(requiresNewTxTemplate.execute(s -> {
                boolean won = handleSubmitResult(task, sr);
                // billingExempt 文本子任务需写 usage 快照供外层聚合。
                if (Objects.equals(task.getMediaType(), MediaType.TEXT.name())
                        && task.getBillingStatus() == null
                        && sr != null && ProviderUsageSupport.hasAnyProviderUsage(sr.getUsage())) {
                    persistUsageForExemptTask(task, sr.getUsage());
                }
                updateTaskWithPayloadArchive(task, won);
                return won;
            }));
            //    仅本线程赢得终态 CAS 才执行 OSS 回写和业务回调。
            //    整段用 try/catch 兜住：OSS / 回调 / 事件均为成功后的副作用，其异常不得冒泡到下方失败 catch，
            //    否则会对已成功并已释放并发槽的任务再次走退款/释放，导致并发计数被重复扣减。
            if (terminalWon && MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())) {
                if (mediaEtaRecorder != null) {
                    mediaEtaRecorder.recordSuccess(task);
                }
                try {
                    persistOssIfNeeded(task);
                    tryInvokeGenResultCallback(task);
                    requiresNewTxTemplate.executeWithoutResult(s -> updateTaskWithPayloadArchive(task));
                    // OSS 就绪后发布事件，让业务侧（音频场景）用 oss_url 回填业务表。
                    if (StringUtils.isNotBlank(task.getOssUrl())) {
                        publishOssPersistedEventSafely(task);
                    }
                } catch (Exception ossEx) {
                    log.error("媒体成功后 OSS 回写/回调异常（不影响任务成功终态）, taskId={}", task.getId(), ossEx);
                }
            }
            if (terminalWon && (MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                    || MediaTaskStatus.FAILED.name().equals(task.getStatus()))) {
                cleanupNormalizedVideoInputs(task);
                publishTextTaskCompletedSafely(task);
            }
        } catch (SubmissionOutcomeUnknownException ex) {
            // POST 可能已被上游接受；冻结款和并发占用保持原状，等待人工核对。
            task.setErrorMessage("DMC 提交结果待核对");
            requiresNewTxTemplate.executeWithoutResult(s -> updateTaskWithPayloadArchive(task));
            log.error("DMC 提交结果未知，保留 PENDING, taskId={}", task.getId());
        } catch (Exception ex) {
            long submitElapsedMs = System.currentTimeMillis() - submitStartMs;
            boolean textTask = MediaType.TEXT.name().equals(task.getMediaType());
            boolean klingRejected = !textTask && klingSubmissionFailureRecorder.record(task, ex);
            if (textTask) {
                // 文本异常可能由响应解析器抛出且异常消息携带原始 token；日志只保留类型。
                log.error("text submit failed, taskId={}, model={}, elapsedMs={}, errorType={}",
                        task.getId(), task.getModelName(), submitElapsedMs, ex.getClass().getSimpleName());
            } else if (klingRejected) {
                log.warn("media submit rejected, taskId={}, model={}, elapsedMs={}, reason={}",
                    task.getId(), task.getModelName(), submitElapsedMs, ex.getMessage());
            } else {
                log.error("media submit failed, taskId={}, model={}, elapsedMs={}, status=FAIL",
                    task.getId(), task.getModelName(), submitElapsedMs, ex);
            }
            task.setStatus(MediaTaskStatus.FAILED.name());
            String evidenceSnapshot = textTask ? TextFailureBillingPolicy.snapshotFrom(ex) : null;
            if (evidenceSnapshot != null) {
                task.setErrorDetailJson(evidenceSnapshot);
            } else if (TaskErrorSnapshot.read(task.getErrorDetailJson()) == null) {
                task.setErrorDetailJson(TaskErrorSnapshot.write(ErrorNormalizer.normalize(
                        String.valueOf(task.getId()), null, task.getModelName(), ex)));
            }
            task.setErrorMessage(textTask
                    ? ProviderErrorSanitizer.safeMessage(ex.getMessage(), "文本提交失败")
                    : StringUtils.defaultIfBlank(ex.getMessage(), "提交失败"));
            Map<String, Object> failedUsage = submitResult == null ? null : submitResult.getUsage();
            task.setErrorDetailJson(TextFailureBillingPolicy.withObservedUsage(
                    task.getErrorDetailJson(), failedUsage));
            boolean hasProviderUsage = ProviderUsageSupport.hasAnyProviderUsage(failedUsage);
            boolean settleProviderCall = textTask && TextFailureBillingPolicy.shouldSettle(
                    false, task.getUpstreamAcceptTime() != null,
                    task.getProtocol(), task.getErrorDetailJson(), failedUsage);
            // 账务收口与失败回写放进同一个 REQUIRES_NEW，避免和上层事务形成行锁互等。
            try {
                requiresNewTxTemplate.executeWithoutResult(s -> {
                    boolean billingThrew = false;
                    boolean billingWon = false;
                    try {
                        if (textTask && task.getBillingStatus() == null) {
                            billingWon = true;
                        } else {
                            billingWon = settleProviderCall
                                    ? billingFacadeService.settleBilling(task, failedUsage)
                                    : billingFacadeService.refundBilling(task);
                        }
                    } catch (Exception billingEx) {
                        billingThrew = true;
                        log.error("媒体提交失败计费收口异常, 保留FAILED待补偿, taskId={}",
                                task.getId(), billingEx);
                    }
                    if (!billingThrew) {
                        // 计费 CAS 失败（已被其它路径处理）时从 DB 回读终态，避免覆盖赢家结果。
                        syncTerminalFieldsIfNeeded(task, billingWon);
                    } else {
                        // 计费异常：强制保持失败终态，冻结款留给补偿任务处理。
                        task.setStatus(MediaTaskStatus.FAILED.name());
                    }
                    if (textTask && task.getBillingStatus() == null
                            && ProviderUsageSupport.hasAnyProviderUsage(failedUsage)) {
                        persistUsageForExemptTask(task, failedUsage);
                    }
                    updateTaskWithPayloadArchive(task);
                });
            } catch (Exception updEx) {
                log.error("媒体失败状态回写失败, taskId={}", task.getId(), updEx);
            }
            cleanupNormalizedVideoInputs(task);
            // 失败释放并发槽：用 afterCompletion（commit 或 rollback 都触发），避免绑定到上层 afterCommit 后
            // 因外层事务回滚而漏释放并发槽。本 catch 仅在 submit/回写失败时进入（成功后的 OSS 副作用已被上面
            // 的 try 兜住，不会重复进入），故此处释放与成功路径释放互斥，不会双释放。
            releaseConcurrencyAfterCompletion(task);
            publishTextTaskCompletedSafely(task);
            // 父周期门禁拒绝不是普通 Provider 失败。child 已在上方按“未越过 Provider
            // 边界”完成退款和 FAILED 收口后，仍须把专用异常交还同步业务 worker，令其
            // 立即中止后续滚动调用；QUEUED 调度线程即使无人接收异常，也不会重试已终态 child。
            if (ex instanceof TextTaskExecutionRejectedException rejected) {
                throw rejected;
            }
        }
    }

    /**
     * 持久化“即将越过 Provider 边界”的证据。只有 CAS 成功后才允许远程调用；
     * 宕机补偿据此区分可退款的未发请求与必须保守结算的已发请求。
     */
    private void markTextProviderCallStarted(AidMediaTask task, MediaTextGenerateRequest request) {
        Date startedAt = new Date();
        int rows = Optional.ofNullable(requiresNewTxTemplate.execute(status -> {
            // 滚动 child 可能先冻结后因并发限额进入 QUEUED。真正拉起时必须再次在父行锁下
            // 校验其创建周期仍拥有执行权，避免父任务取消/续生后旧周期排队 child 越过 Provider
            // 边界。Skill 等普通文本任务没有 billingAttemptId，继续沿用自身单任务计费链路。
            if (request != null && !Boolean.TRUE.equals(request.getBillingExempt())
                    && StrUtil.isNotBlank(request.getBillingAttemptId())) {
                extractBillingService.assertRollingTextCallExecution(
                        request.getBizTaskId(), task.getUserId(), request.getBillingAttemptId());
            }
            LambdaUpdateWrapper<AidMediaTask> update = new LambdaUpdateWrapper<>();
            update.eq(AidMediaTask::getId, task.getId());
            update.eq(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name());
            update.isNull(AidMediaTask::getUpstreamAcceptTime);
            update.set(AidMediaTask::getUpstreamAcceptTime, startedAt);
            update.set(AidMediaTask::getUpdateTime, startedAt);
            return aidMediaTaskMapper.update(null, update);
        })).orElse(0);
        if (rows != 1) {
            throw new ServiceException("任务状态异常");
        }
        task.setUpstreamAcceptTime(startedAt);
    }

    private void publishTextTaskCompletedSafely(AidMediaTask task) {
        if (task == null || task.getId() == null || !MediaType.TEXT.name().equals(task.getMediaType())
                || !(MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || MediaTaskStatus.FAILED.name().equals(task.getStatus())
                || MediaTaskStatus.CANCELLED.name().equals(task.getStatus()))) {
            return;
        }
        try {
            applicationEventPublisher.publishEvent(
                    new MediaTaskCompletedEvent(this, task.getId(), task.getUserId()));
        } catch (Exception eventError) {
            log.warn("文本任务终态事件发布失败, taskId={}, errorType={}", task.getId(),
                    eventError.getClass().getSimpleName());
        }
    }

    /** 监听异步任务终态事件并触发排队消费与成功产物持久化。 */
    @EventListener
    public void onTaskCompleted(MediaTaskCompletedEvent event) {
        // 并发槽已释放，先拉起下一条排队任务；OSS 下载可能耗时，不能让队列空转等待持久化。
        drainQueue();
        try {
            AidMediaTask task = aidMediaTaskMapper.selectById(event.getTaskId());
            if (Objects.nonNull(task)) {
                cleanupNormalizedVideoInputs(task);
            }
            // COMPOSE 等任务已直接写入 oss_url；只处理成功但尚未完成持久化的异步产物。
            if (Objects.nonNull(task)
                    && MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                    && StringUtils.isBlank(task.getOssUrl())
                    && StringUtils.isNotBlank(task.getOriginUrl())) {
                ensureOssPersisted(task.getId());
            }
        } catch (Exception ex) {
            // OSS 失败由定时补偿继续处理。
            log.warn("任务终态 OSS 持久化触发失败, taskId={}, error={}", event.getTaskId(), ex.getMessage());
        }
    }

    /** 恢复视频任务原始输入图 URL 并清理比例归一化临时对象；清理失败不影响任务终态。 */
    private void cleanupNormalizedVideoInputs(AidMediaTask task) {
        if (Objects.isNull(task) || !MediaType.VIDEO.name().equals(task.getMediaType())
                || StrUtil.isBlank(task.getRequestJson())) {
            return;
        }
        try {
            MediaVideoGenerateRequest request = JSONUtil.toBean(task.getRequestJson(), MediaVideoGenerateRequest.class);
            if (!videoInputAspectRatioNormalizer.restoreAndCleanup(request)) {
                return;
            }
            task.setRequestJson(MediaTaskPayloadSanitizer.serializeRequest(request));
            requiresNewTxTemplate.executeWithoutResult(s -> updateTaskWithPayloadArchive(task));
        } catch (Exception ex) {
            log.warn("视频输入图临时对象清理异常, taskId={}, error={}", task.getId(), ex.getMessage(), ex);
        }
    }

    /** 单次排队拉起最多扫描的 QUEUED 候选条数：跳过语义下窗口过小会造成窗口外任务饥饿（避免魔法数字）。 */
    private static final int DRAIN_QUEUE_SCAN_LIMIT = 50;

    /**
     * 释放并发坑位后，尝试拉起下一个排队任务。
     * 取一批 QUEUED 候选（按 createTime/id 稳定排序），逐条 tryAcquire，
     * 跳过用户/模型/供应商限额已满的，避免队列头阻塞导致系统并发空闲却无法调度。
     */
    private void drainQueue() {
        try {
            LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
            // 特别标注：本查询只取拉起所需的最小字段（id + userId + modelName），新增依赖字段时须同步补充 select。
            wrapper.select(AidMediaTask::getId, AidMediaTask::getUserId, AidMediaTask::getModelName);
            wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
            wrapper.and(w -> w.isNull(AidMediaTask::getNextPollTime)
                    .or().le(AidMediaTask::getNextPollTime, new Date()));
            wrapper.orderByAsc(AidMediaTask::getCreateTime, AidMediaTask::getId);
            wrapper.last("limit " + DRAIN_QUEUE_SCAN_LIMIT);
            List<AidMediaTask> candidates = aidMediaTaskMapper.selectList(wrapper);
            if (CollectionUtil.isEmpty(candidates)) {
                return;
            }
            for (AidMediaTask candidate : candidates) {
                boolean canRun = concurrencyLimiter.tryAcquire(candidate.getUserId(), candidate.getModelName());
                if (canRun) {
                    Long taskId = candidate.getId();
                    Long userId = candidate.getUserId();
                    String modelName = candidate.getModelName();
                    log.info("drainQueue 拉起排队任务, taskId={}, userId={}, modelName={}", taskId, userId, modelName);
                    //    线程池拒绝（队列满 / 应用关闭）时必须立即释放刚抢占的槽，否则任务仍 QUEUED 但 Redis 计数偏高、
                    //    后续任务被长期挡住。释放后保留任务 QUEUED，等下一轮调度再拉起。
                    try {
                        threadPoolTaskExecutor.execute(() -> drainQueueSubmit(taskId, userId, modelName));
                    } catch (Exception rejectEx) {
                        log.warn("drainQueue 提交线程池被拒绝, 释放并发槽并保留 QUEUED, taskId={}, userId={}",
                                taskId, userId, rejectEx);
                        concurrencyLimiter.release(userId, modelName);
                    }
                    return;
                }
            }
            // 全部候选的用户/模型/供应商限额都满，等待后续释放再调度。
        } catch (Exception e) {
            log.warn("drainQueue 执行异常", e);
        }
    }

    /**
     * 排队任务拉起：乐观锁更新状态为 PENDING，成功后提交到上游 provider。
     * 所有早退分支都释放 drainQueue 中已抢占的坑位（四维，需带模型编码），避免名额泄漏。
     * 竞争失败时继续尝试下一条排队任务。
     */
    private void drainQueueSubmit(Long taskId, Long userId, String modelName) {
        AidMediaTask task = aidMediaTaskMapper.selectById(taskId);
        if (task == null) {
            // 任务已被删除（数据修复等场景），释放已抢占的坑位。
            log.warn("drainQueue submit skipped, task not found, taskId={}", taskId);
            concurrencyLimiter.release(userId, modelName);
            drainQueue();
            return;
        }
        if (!MediaTaskStatus.QUEUED.name().equals(task.getStatus())) {
            log.info("drainQueue 跳过, taskId={} 状态已变更为{}", taskId, task.getStatus());
            concurrencyLimiter.release(userId, modelName);
            drainQueue();
            return;
        }
        if (isCancelledStoryboardVideoParent(task)) {
            taskCompletionService.cancelQueuedTask(taskId, userId, "用户取消，未提交");
            concurrencyLimiter.release(userId, modelName);
            drainQueue();
            return;
        }
        String userStr = task.getUserId() != null ? String.valueOf(task.getUserId()) : "";
        LambdaUpdateWrapper<AidMediaTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(AidMediaTask::getId, taskId);
        updateWrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        updateWrapper.set(AidMediaTask::getStatus, MediaTaskStatus.PENDING.name());
        updateWrapper.set(AidMediaTask::getUpdateBy, userStr);
        updateWrapper.set(AidMediaTask::getUpdateTime, new Date());
        int rows = aidMediaTaskMapper.update(null, updateWrapper);
        if (rows == 0) {
            log.info("drainQueue 竞争失败, taskId={} 已被其他线程拉起，释放坑位", taskId);
            concurrencyLimiter.release(userId, modelName);
            drainQueue();
            return;
        }
        task.setStatus(MediaTaskStatus.PENDING.name());
        doSubmitToProvider(task);
    }

    private boolean isCancelledStoryboardVideoParent(AidMediaTask task) {
        if (Objects.isNull(taskCancelFlagManager) || Objects.isNull(task)
                || !STORYBOARD_VIDEO_GENERATE_BIZ_TYPE.equals(task.getBizTaskType())) {
            return false;
        }
        Long parentTaskId = task.getParentTaskId();
        if (Objects.isNull(parentTaskId) && Objects.nonNull(task.getBizTaskId())) {
            parentTaskId = task.getBizTaskId()
                    / com.aid.rps.queue.MediaGenFanInSupport.BIZ_SEQ_PARENT_FACTOR;
        }
        return Objects.nonNull(parentTaskId) && parentTaskId > 0
                && taskCancelFlagManager.isCancelled(parentTaskId);
    }

    private MediaTaskResponse toResponse(AidMediaTask task) {
        boolean protectedResultPending = isProtectedImageEditResultPending(task);
        String exposedStatus = protectedResultPending
                ? MediaTaskStatus.PROCESSING.name() : task.getStatus();
        // 将数据库任务实体统一转换为对外响应对象。
        // 排队任务额外返回排队位置，供前端展示等待排名。
        Integer queuePosition = null;
        if (MediaTaskStatus.QUEUED.name().equals(exposedStatus)) {
            queuePosition = concurrencyLimiter.getQueuePosition(task.getUserId(), task.getId());
        }
        // 从计费快照中提取计费模式
        String billingMode = null;
        if (task.getBillingSnapshotJson() != null && !task.getBillingSnapshotJson().isBlank()) {
            try {
                com.aid.billing.model.BillingSnapshot snapshot =
                        cn.hutool.json.JSONUtil.toBean(task.getBillingSnapshotJson(), com.aid.billing.model.BillingSnapshot.class);
                billingMode = snapshot.getBillingMode();
            } catch (Exception ignored) {
            }
        }
        // 终态错误优先读取持久化快照。
        String errorCode = null;
        String errorType = null;
        String errorSource = null;
        boolean needRecharge = false;
        String rechargeOwner = null;
        boolean retryable = false;
        String userMessage = protectedResultPending ? null : task.getErrorMessage();
        if (!protectedResultPending
                && (StringUtils.isNotBlank(task.getErrorMessage()) || StringUtils.isNotBlank(task.getErrorDetailJson()))) {
            TaskErrorResult normalized = TaskErrorSnapshot.fromTask(task);
            errorCode = normalized.getErrorCode();
            errorType = normalized.getErrorType();
            errorSource = normalized.getErrorSource();
            needRecharge = normalized.isNeedRecharge();
            rechargeOwner = normalized.getRechargeOwner();
            retryable = normalized.isRetryable();
            userMessage = normalized.getUserMessage();
        }
        MediaTextGenerateRequest.TextMessageItem toolResult = com.aid.media.provider.TextToolResultSupport.read(task);
        return MediaTaskResponse.builder()
            .taskId(task.getId())
            .mediaType(task.getMediaType())
            .protocol(task.getProtocol())
            .modelName(task.getModelName())
            .status(exposedStatus)
            .queuePosition(queuePosition)
            .eta(protectedResultPending || mediaEtaService == null
                    ? null : mediaEtaService.estimateMediaTask(task, queuePosition))
            .providerTaskId(task.getProviderTaskId())
            .originUrl(protectedResultPending ? null : task.getOriginUrl())
            .ossUrl(task.getOssUrl())
            .textContent(task.getResultText())
            .toolMessage(toolResult)
            .toolContextAvailable(toolResult == null ? null : task.getLiveTextTurn() != null)
            .errorMessage(userMessage)
            .errorCode(errorCode)
            .errorType(errorType)
            .errorSource(errorSource)
            .userMessage(userMessage)
            .needRecharge(needRecharge)
            .rechargeOwner(rechargeOwner)
            .retryable(retryable)
            .billingStatus(task.getBillingStatus())
            .refundStatus(RefundStatusMapper.resolveWithFrozen(
                    exposedStatus, task.getBillingStatus(),
                    task.getFrozenAmount() != null && task.getFrozenAmount().signum() > 0))
            .preHoldAmount(task.getFrozenAmount())
            .actualCost(task.getActualCost())
            .billingMode(billingMode)
            .outputDurationSeconds(task.getOutputDurationSeconds())
            .build();
    }

    /**
     * 上游已经成功、账务已经按原 CAS 收口，但像素保护和平台存储尚未完成时，对外仍表现为处理中。
     * 原始上游地址只作为 oss_pending 的可恢复检查点，不得在此阶段返回给业务方。
     */
    private static boolean isProtectedImageEditResultPending(AidMediaTask task) {
        if (task == null
                || !MediaType.IMAGE.name().equals(task.getMediaType())
                || !MediaTaskStatus.SUCCEEDED.name().equals(task.getStatus())
                || StringUtils.isNotBlank(task.getOssUrl())
                || StringUtils.isBlank(task.getRequestJson())) {
            return false;
        }
        try {
            MediaImageGenerateRequest request = JSONUtil.toBean(task.getRequestJson(),
                    MediaImageGenerateRequest.class);
            return com.aid.media.provider.ImageEditAssetSupport.requiresResultProtection(request)
                    || isLayerDecomposition(request);
        } catch (RuntimeException ex) {
            log.warn("图片编辑后处理状态解析失败, taskId={}, errorType={}", task.getId(),
                    ex.getClass().getSimpleName());
            return task.getRequestJson().contains("image_inpainting")
                    || task.getRequestJson().contains("image_outpainting")
                    || task.getRequestJson().contains("image_layer_decomposition");
        }
    }

    private record OriginDownloadSource(String url, boolean managedStorage) { }

    /**
     * Provider 已将 base64 结果先落入本站存储时，origin 会是可信相对路径。
     * 通过当前存储配置生成短期读取地址，读取时不携带模型网关凭证；保护完成后仍另存最终结果。
     */
    private static OriginDownloadSource resolveOriginDownloadSource(String originUrl) {
        String value = StringUtils.trimToNull(originUrl);
        if (value == null) throw new ServiceException("资源地址无效");
        URI parsed;
        try {
            parsed = URI.create(value);
            if (parsed.isAbsolute()) return new OriginDownloadSource(requireHttpUrl(value), false);
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("资源地址无效");
        }
        String decodedPath = parsed.getPath();
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("..")
                || value.contains("\\") || value.contains("?") || value.contains("#")
                || value.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(decodedPath) || !decodedPath.startsWith("/")
                || decodedPath.startsWith("//") || decodedPath.contains("..")
                || decodedPath.contains("\\")
                || decodedPath.chars().anyMatch(Character::isISOControl)) {
            throw new ServiceException("资源地址无效");
        }
        OssTemplate storage;
        try {
            storage = SpringUtils.getBean(OssTemplate.class);
        } catch (RuntimeException ex) {
            throw new ServiceException("存储资源读取配置异常");
        }
        if (storage == null || !storage.isManagedResourceUrl(value)) {
            throw new ServiceException("资源地址无效");
        }
        String readable = storage.getSignedUrl(value, 600);
        if (StringUtils.isBlank(readable) || readable.equals(value)) {
            readable = storage.toModelProxyUnsignedSourceUrl(value);
        }
        return new OriginDownloadSource(requireHttpUrl(readable), true);
    }

    /**
     * 在预冻结和 Provider 调用前校验蒙版/扩图几何，并把受保护结果尺寸规范为真实像素尺寸。
     * 这样不会出现已付费生成后才发现结果无法按像素回贴、随后永久补偿的任务。
     */
    private static void validateAndNormalizeProtectedImageEdit(MediaImageGenerateRequest request,
                                                                 AiModelConfigVo modelConfig) {
        if (!com.aid.media.provider.ImageEditAssetSupport.requiresResultProtection(request)) {
            return;
        }
        com.aid.media.provider.ImageEditAssetSupport.PreparedProtection protection =
                com.aid.media.provider.ImageEditAssetSupport.prepareResultProtection(request);
        int width;
        int height;
        if (com.aid.media.provider.ImageEditAssetSupport.isOutpainting(request)) {
            width = request.getTargetWidth();
            height = request.getTargetHeight();
        } else {
            width = protection.source().getWidth();
            height = protection.source().getHeight();
        }
        String expected = width + "x" + height;
        if (StringUtils.isNotBlank(request.getSize())
                && !expected.equals(normalizeImageDimensions(request.getSize()))) {
            throw new ServiceException("图片编辑输出尺寸必须与受保护画布一致");
        }
        Map<String, Object> options = request.getOptions();
        if (options != null) {
            Object optionSize = options.get("size");
            if (optionSize != null && !expected.equals(normalizeImageDimensions(String.valueOf(optionSize)))) {
                throw new ServiceException("图片编辑输出尺寸与模型参数冲突");
            }
            Object ratio = options.containsKey("aspectRatio")
                    ? options.get("aspectRatio") : options.get("aspect_ratio");
            if (ratio != null && !matchesImageRatio(width, height, String.valueOf(ratio))) {
                throw new ServiceException("图片编辑输出比例与受保护画布冲突");
            }
        }
        // 输出尺寸只写入模型明确声明的参数。Wan 等扩图/区域编辑协议通过独立几何参数
        // 表达目标画布，强行写入 size 会被其“无规格档位”能力正确拒绝。
        applyProtectedOutputSize(request, modelConfig, expected);
    }

    private static void applyProtectedOutputSize(MediaImageGenerateRequest request,
                                                   AiModelConfigVo modelConfig, String expected) {
        if (modelConfig != null && modelConfig.getResolvedDefinition() != null
                && ModelParameterValidator.parameterPaths(modelConfig.getResolvedDefinition()).contains("size")) {
            request.setSize(expected);
        }
    }

    private static String normalizeImageDimensions(String value) {
        if (StringUtils.isBlank(value)) return null;
        String[] parts = value.trim().split("[*xX×]");
        if (parts.length != 2) return null;
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            return width > 0 && height > 0 ? width + "x" + height : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean matchesImageRatio(int width, int height, String value) {
        String[] parts = StringUtils.defaultString(value).trim().split("[:/]");
        if (parts.length != 2) return false;
        try {
            double expected = Double.parseDouble(parts[0].trim()) / Double.parseDouble(parts[1].trim());
            return expected > 0 && Math.abs(width / (double) height - expected) < 0.000001d;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private MediaImageGenerateRequest protectedImageEditRequest(AidMediaTask task) {
        if (task == null || !MediaType.IMAGE.name().equals(task.getMediaType())
                || StringUtils.isBlank(task.getRequestJson())) {
            return null;
        }
        MediaImageGenerateRequest request = JSONUtil.toBean(task.getRequestJson(), MediaImageGenerateRequest.class);
        if (!com.aid.media.provider.ImageEditAssetSupport.requiresResultProtection(request)
                && !isLayerDecomposition(request)) {
            return null;
        }
        if (!isLayerDecomposition(request)) modelResourceUrlSigner.sign(request);
        return request;
    }

    private static boolean isLayerDecomposition(MediaImageGenerateRequest request) {
        return request != null && "image_layer_decomposition".equals(request.getCapabilityCode());
    }

    private static final class LayerOutputInvalidException extends RuntimeException {
        private LayerOutputInvalidException(String message) {
            super(message);
        }
    }

    /** Invalid layer bytes are permanent; reject them before the success ledger transition. */
    private void validateLayerManifestBeforeSettlement(AidMediaTask task) {
        List<AidMediaResult> results = selectOrderedTaskResults(task.getId());
        if (results.isEmpty()) throw new LayerOutputInvalidException("图层分离结果清单缺失");
        for (AidMediaResult result : results) {
            if (StringUtils.isBlank(result.getOriginUrl())) {
                throw new LayerOutputInvalidException("图层分离结果地址缺失");
            }
            byte[] bytes;
            try {
                bytes = downloadOriginBytesWithRetry(task, result.getOriginUrl());
            } catch (RuntimeException unavailable) {
                // Settlement must never succeed before every ordered output is readable and valid.
                // A later persistence retry cannot safely reverse an already completed billing CAS.
                log.warn("图层分离预校验下载失败，按失败退款收口, taskId={}, resultIndex={}",
                        task.getId(), result.getResultIndex(), unavailable);
                throw new LayerOutputInvalidException("图层分离结果暂时无法校验，请稍后重试");
            }
            validateLayerImageBytes(result, bytes);
        }
    }

    private void validateLayerImageBytes(AidMediaResult result, byte[] generated) {
        if (generated.length > 50L * 1024 * 1024) {
            throw new LayerOutputInvalidException("图层图片超过文件大小上限");
        }
        if (generated.length < 8 || generated[0] != (byte) 0x89 || generated[1] != 'P'
                || generated[2] != 'N' || generated[3] != 'G') {
            throw new LayerOutputInvalidException("图层图片必须是 PNG");
        }
        java.awt.image.BufferedImage image;
        try (javax.imageio.stream.ImageInputStream input = javax.imageio.ImageIO
                .createImageInputStream(new java.io.ByteArrayInputStream(generated))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new LayerOutputInvalidException("图层图片无法解码");
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 20_000_000L) {
                    throw new LayerOutputInvalidException("图层图片像素超过上限");
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (java.io.IOException invalid) {
            throw new LayerOutputInvalidException("图层图片无法解码");
        }
        if (image == null || (result.getResultIndex() != null && result.getResultIndex() > 0
                && !image.getColorModel().hasAlpha())) {
            throw new LayerOutputInvalidException("透明图层缺少 Alpha 通道");
        }
        com.alibaba.fastjson2.JSONObject metadata = result.getMetadataJson() == null
                ? null : com.alibaba.fastjson2.JSON.parseObject(result.getMetadataJson());
        if (metadata == null || image.getWidth() != metadata.getIntValue("width")
                || image.getHeight() != metadata.getIntValue("height")) {
            throw new LayerOutputInvalidException("图层图片尺寸与上游元数据不符");
        }
    }

    /**
     * 逐项保护有序结果。已经完成的结果保留，补偿只处理空项；全部完成后才开放主任务结果。
     */
    private void persistProtectedImageResults(AidMediaTask task, MediaImageGenerateRequest request) {
        org.redisson.api.RLock lock = redissonClient == null ? null
                : redissonClient.getLock("aid:media:image-edit:persist:" + task.getId());
        boolean locked = lock == null;
        try {
            if (lock != null) {
                locked = lock.tryLock();
                if (!locked) return;
            }
            persistProtectedImageResultsLocked(task, request);
        } finally {
            if (lock != null && locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void persistProtectedImageResultsLocked(AidMediaTask task, MediaImageGenerateRequest request) {
        List<AidMediaResult> results = selectOrderedTaskResults(task.getId());
        if (results.isEmpty()) {
            String operator = task.getUserId() == null ? "" : String.valueOf(task.getUserId());
            aidMediaResultMapper.upsertTaskResult(task.getId(), 0, task.getMediaType(), task.getOriginUrl(), operator);
            results = selectOrderedTaskResults(task.getId());
        }
        if (results.isEmpty()) throw new ServiceException("图片编辑结果清单缺失");

        boolean layerDecomposition = isLayerDecomposition(request);
        com.aid.media.provider.ImageEditAssetSupport.PreparedProtection protection = layerDecomposition
                ? null : com.aid.media.provider.ImageEditAssetSupport.prepareResultProtection(request);
        for (AidMediaResult result : results) {
            if (StringUtils.isNotBlank(result.getOssUrl())) continue;
            if (StringUtils.isBlank(result.getOriginUrl())) throw new ServiceException("图片编辑结果地址缺失");
            byte[] generated = downloadOriginBytesWithRetry(task, result.getOriginUrl());
            byte[] protectedBytes = layerDecomposition ? generated
                    : com.aid.media.provider.ImageEditAssetSupport.protectResult(protection, generated);
            if (layerDecomposition) {
                validateLayerImageBytes(result, generated);
            }
            UploadResult upload;
            try {
                upload = OssFactory.instance().uploadSuffix(protectedBytes, ".png", "image/png");
            } catch (java.io.IOException ex) {
                log.warn("图片编辑结果保存失败, taskId={}, resultIndex={}",
                        task.getId(), result.getResultIndex(), ex);
                throw new ServiceException("图片编辑结果保存失败");
            }
            LambdaUpdateWrapper<AidMediaResult> update = new LambdaUpdateWrapper<>();
            update.eq(AidMediaResult::getId, result.getId());
            update.isNull(AidMediaResult::getOssUrl);
            update.set(AidMediaResult::getOssUrl, upload.getUrl());
            update.set(AidMediaResult::getMimeType, "image/png");
            update.set(AidMediaResult::getFileSize, (long) protectedBytes.length);
            update.set(AidMediaResult::getUpdateBy,
                    task.getUserId() == null ? "" : String.valueOf(task.getUserId()));
            update.set(AidMediaResult::getUpdateTime, new Date());
            aidMediaResultMapper.update(null, update);
        }

        results = selectOrderedTaskResults(task.getId());
        if (results.isEmpty() || results.stream().anyMatch(value -> StringUtils.isBlank(value.getOssUrl()))) {
            throw new ServiceException("图片编辑结果仍在持久化");
        }
        task.setOssUrl(results.get(0).getOssUrl());
        task.setErrorMessage(null);
    }

    private List<AidMediaResult> selectOrderedTaskResults(Long taskId) {
        return aidMediaResultMapper.selectList(new LambdaQueryWrapper<AidMediaResult>()
                .eq(AidMediaResult::getTaskId, taskId)
                .orderByAsc(AidMediaResult::getResultIndex, AidMediaResult::getId));
    }
    @Override
    public List<MediaTaskListItem> listUserTasks(MediaTaskListRequest request, Long userId) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AidMediaTask::getUserId, userId);

        if (request != null && request.getProjectId() != null) {
            wrapper.eq(AidMediaTask::getProjectId, request.getProjectId());
        }
        if (request != null && request.getEpisodeId() != null) {
            wrapper.eq(AidMediaTask::getEpisodeId, request.getEpisodeId());
        }
        if (request != null && cn.hutool.core.util.StrUtil.isNotBlank(request.getMediaType())) {
            wrapper.eq(AidMediaTask::getMediaType, request.getMediaType());
        }
        if (request != null && cn.hutool.core.util.StrUtil.isNotBlank(request.getStatus())) {
            wrapper.eq(AidMediaTask::getStatus, request.getStatus());
        }

        wrapper.orderByDesc(AidMediaTask::getCreateTime);

        // 列表读取紧凑终态错误快照，不读取原始请求和响应。
        // frozenAmount 必须查出，供 RefundStatusMapper.resolveWithFrozen 精确区分"预冻结失败"与"已退款"。
        wrapper.select(AidMediaTask::getId,
            AidMediaTask::getProjectId,
            AidMediaTask::getEpisodeId,
            AidMediaTask::getMediaType,
            AidMediaTask::getProtocol,
            AidMediaTask::getModelName,
            AidMediaTask::getPrompt,
            AidMediaTask::getStatus,
            AidMediaTask::getOssUrl,
            AidMediaTask::getResultText,
            AidMediaTask::getErrorMessage,
            AidMediaTask::getErrorDetailJson,
            AidMediaTask::getBillingStatus,
            AidMediaTask::getFrozenAmount,
            AidMediaTask::getActualCost,
            AidMediaTask::getCreateTime,
            AidMediaTask::getUpdateTime);

        List<AidMediaTask> tasks = aidMediaTaskMapper.selectList(wrapper);

        return tasks.stream().map(this::toListItem).toList();
    }

    /**
     * 将实体转换为列表项（prompt 截取前200字符，时间格式化）。
     */
    private MediaTaskListItem toListItem(AidMediaTask task) {
        // prompt 截取前200字符，避免大字段传输。
        String promptSummary = task.getPrompt();
        if (promptSummary != null && promptSummary.length() > 200) {
            promptSummary = promptSummary.substring(0, 200);
        }
        // 终态错误优先读取持久化快照。
        String errorCode = null;
        String errorType = null;
        String errorSource = null;
        Boolean needRecharge = null;
        String rechargeOwner = null;
        Boolean retryable = null;
        String userMessage = task.getErrorMessage();
        if (StringUtils.isNotBlank(task.getErrorMessage()) || StringUtils.isNotBlank(task.getErrorDetailJson())) {
            TaskErrorResult normalized = TaskErrorSnapshot.fromTask(task);
            errorCode = normalized.getErrorCode();
            errorType = normalized.getErrorType();
            errorSource = normalized.getErrorSource();
            needRecharge = normalized.isNeedRecharge();
            rechargeOwner = normalized.getRechargeOwner();
            retryable = normalized.isRetryable();
            userMessage = normalized.getUserMessage();
        }
        // 时间格式化。
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return MediaTaskListItem.builder()
            .taskId(task.getId())
            .projectId(task.getProjectId())
            .episodeId(task.getEpisodeId())
            .mediaType(task.getMediaType())
            .protocol(task.getProtocol())
            .modelName(task.getModelName())
            .prompt(promptSummary)
            .status(task.getStatus())
            .ossUrl(task.getOssUrl())
            .textContent(task.getResultText())
            .errorMessage(userMessage)
            .errorCode(errorCode)
            .errorType(errorType)
            .errorSource(errorSource)
            .needRecharge(needRecharge)
            .rechargeOwner(rechargeOwner)
            .retryable(retryable)
            .billingStatus(task.getBillingStatus())
            .refundStatus(RefundStatusMapper.resolveWithFrozen(
                    task.getStatus(), task.getBillingStatus(),
                    task.getFrozenAmount() != null && task.getFrozenAmount().signum() > 0))
            .actualCost(task.getActualCost())
            .createTime(task.getCreateTime() != null ? sdf.format(task.getCreateTime()) : null)
            .updateTime(task.getUpdateTime() != null ? sdf.format(task.getUpdateTime()) : null)
            .build();
    }

    /**
     * total_tokens 兜底：上游只返回 prompt/completion 或 input/output 时，按"输入+输出"自动求和。
     * 任一侧无法转 long 即返回 null，避免日志打印异常字段。
     */
    private static Long sumTokensSafely(Object inputLike, Object outputLike) {
        Long a = toLongOrNull(inputLike);
        Long b = toLongOrNull(outputLike);
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0L : a) + (b == null ? 0L : b);
    }

    private static Long toLongOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
