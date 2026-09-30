package com.aid.rps.service.impl;

import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.error.TaskErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidStoryboard;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidStoryboardService;
import com.aid.agent.IAidAgentService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingQuoteAssembler;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.StoryboardVideoPromptBatchRequest;
import com.aid.rps.dto.StoryboardVideoPromptImageBatchRequest;
import com.aid.rps.dto.StoryboardVideoWithPromptRequest;
import com.aid.enums.CreationModeEnum;
import com.aid.rps.assembler.StoryboardSceneContextAssembler;
import com.aid.rps.assembler.StoryboardVideoPromptContinuityAssembler;
import com.aid.rps.assembler.StoryboardVideoPromptContinuityAssembler.ContinuityContext;
import com.aid.rps.enums.VideoPromptContinuityMode;
import com.aid.rps.helper.AssetExtractHelper;
import com.aid.rps.model.StoryboardSceneContext;
import com.aid.rps.helper.ProjectGenerateLockGuard;
import com.aid.rps.queue.TaskQueueService;
import com.aid.rps.queue.BatchTaskLocalOrchestrator;
import com.aid.rps.queue.BatchTaskLogicalType;
import com.aid.rps.queue.BatchTaskSlotReservation;
import com.aid.rps.queue.BatchTaskSlotService;
import com.aid.rps.queue.BatchParentSubmissionGuard;
import com.aid.rps.resolver.ReferenceAssetSanitizer;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.rps.resolver.StoryboardPromptBatchAligner;
import com.aid.rps.resolver.StoryboardRoleAudioReferenceResolver;
import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IExtractBillingService.ResumeBillingContext;
import com.aid.rps.service.IExtractBillingService.ResumeTaskMutation;
import com.aid.rps.service.IStoryboardVideoPromptService;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.service.IAiModelConfigService;
import com.aid.storyboard.dto.ChainTriggerResult;
import com.aid.storyboard.dto.StoryboardVideoFromImageGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGenerateRequest;
import com.aid.storyboard.dto.StoryboardVideoGridGenerateRequest;
import com.aid.storyboard.service.impl.StoryboardStepChainService;
import com.aid.storyboard.service.IStoryboardVideoGenerationService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 批量生成"分镜视频提示词"实现（视觉导演融合版）。
 * 复用 {@link StoryboardImagePromptServiceImpl} 的任务体系骨架：
 * aid_extract_task 父任务 + 项目级 Redis 锁 + 计费 + MQ + SSE + 僵尸自愈。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class StoryboardVideoPromptServiceImpl implements IStoryboardVideoPromptService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_FINALIZING = "FINALIZING";
    private static final String TASK_STATUS_RECOVERING = "RECOVERING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";

    /**
     * 可「断线重连」的任务状态集合：全部已生成、本次没有新目标镜头时，
     * 若最近一条任务处于活跃(PENDING/QUEUED/PROCESSING)或有产物的终态(SUCCEEDED/PARTIAL_FAILED)，
     * 则回传该任务供前端重订阅 SSE，而非直接抛「已生成」死路。
     * 纯失败/取消(FAILED/CANCELLED)无产物，不纳入重连。
     */
    private static final Set<String> RECONNECTABLE_STATUS = Set.of(
            "PENDING", "QUEUED", "PROCESSING", "SUCCEEDED", "PARTIAL_FAILED");
    private static final Set<String> ACTIVE_TASK_STATUS = Set.of(
            TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING,
            TASK_STATUS_FINALIZING, TASK_STATUS_RECOVERING);

    /** 计费状态：已完整结算（= ExtractBillingStatus.SUCCESS，续生前置校验用） */
    private static final String BILLING_STATUS_FROZEN = "FROZEN";
    private static final String BILLING_STATUS_SUCCESS = "SUCCESS";
    /** 计费状态：已退款（ExtractBillingStatus.FAILED），排队期停止后允许重新预冻结续生 */
    private static final String BILLING_STATUS_REFUNDED = "FAILED";

    /** 任务类型常量 */
    public static final String TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH = "storyboard_video_prompt_batch";

    /** 缺失分镜提示最多展示数量，避免异常文案过长。 */
    private static final int MISSING_STORYBOARD_LABEL_LIMIT = 10;

    /** 默认智能体编码（多参方向） */
    private static final String DEFAULT_AGENT_CODE = "aid_visual_director";

    /** 智能体业务分类（多参方向，必须严格匹配） */
    private static final String BIZ_CATEGORY = "main_storyboard_video_prompt";

    /** 默认智能体编码（图生方向，漫剧版） */
    private static final String DEFAULT_AGENT_CODE_IMAGE = "aid_visual_director_image";

    /** 智能体业务分类（图生方向，必须严格匹配） */
    private static final String BIZ_CATEGORY_IMAGE = "main_storyboard_video_prompt_image";

    /** 默认智能体编码（宫格方向，auto_grid 专业版）。 */
    private static final String DEFAULT_AGENT_CODE_GRID = "aid_visual_director_grid";

    /** 多参方向-专业版智能体编码（pro 创作模式命中）：消费「中文镜头组」script_params，输出 SD2.0 扁平提示词。 */
    private static final String AGENT_CODE_MULTIREF = "aid_visual_director_multiref";

    /** 输出元素回传的业务编号字段名，与入参分段内的同名字段对照，构成归位的第二把校验钥匙。 */
    private static final String FIELD_BIZ_NO_SHOT = "镜号";
    private static final String FIELD_BIZ_NO_GROUP = "镜头组";

    /** 输出元素的业务载荷字段：提示词正文与建议视频时长（秒）。 */
    private static final String FIELD_PROMPT = "prompt";
    private static final String FIELD_DURATION = "duration";

    /** 模型把契约数组包进外壳对象（键名自选，如 result / data / shots）时，向内探测元素数组的层数上限。 */
    private static final int OUTPUT_ENVELOPE_MAX_DEPTH = 3;

    /** 整批视频提示词为每个镜头预留的输出 token；短批次仍由统一 8K 下限兜底。 */
    private static final int OUTPUT_TOKENS_PER_STORYBOARD = 2048;

    /**
     * 失败原因文案。任务级原因会交由 ErrorNormalizer 归一化成错误码，取值必须落在其可识别的业务失败词表内，
     * 否则会被兜底成通用异常码，前端拿不到「模型格式异常」这类精确提示。
     */
    private static final String FAILURE_BATCH_CONTRACT = "模型返回异常";
    private static final String FAILURE_OUTPUT_FORMAT = "输出格式异常";
    private static final String FAILURE_IDENTITY_CONFLICT = "镜头编号冲突";
    private static final String FAILURE_SCENE_CONFLICT = "场景约束冲突";
    private static final String FAILURE_PROVIDER_REJECTED = "模型拒绝输出";

    private static final Pattern VIDEO_SHOT_BEAT_PATTERN = Pattern.compile(
            "(?:^|\\s)(\\d+(?:\\.\\d+)?\\s*[-~～至]\\s*\\d+(?:\\.\\d+)?\\s*秒[：:])");
    private static final Pattern VIDEO_SHOT_FACT_PATTERN = Pattern.compile(
            "(?:^|[；;])\\s*(景别|动作|台词|画面文字)[：:]\\s*([^；;]*)");

    /** 逐镜失败明细：缺少建议时长属输出契约缺字段，任务级仍按输出格式异常上报。 */
    private static final String FAILURE_DURATION_MISSING = "缺少视频时长";

    /** 批量入参的条目单位，与各拼装方法的分段标题保持一致。 */
    private static final String UNIT_LABEL_SHOT = "镜头";
    private static final String UNIT_LABEL_GROUP = "镜头组";

    /** 方向标识：多参（回填 video_prompt）/ 图生（回填 video_prompt_image）/ 宫格（auto_grid，回填 video_prompt_image，复用图生列不新增列）。
     *  存入 inputSnapshot.direction；旧任务无该键时默认多参，保持向后兼容。 */
    private static final String DIRECTION_MULTI = "multi";
    private static final String DIRECTION_IMAGE = "image";
    private static final String DIRECTION_GRID = "grid";

    /** 与链式正式提交的拆批上限保持一致。 */
    private static final int CHAIN_CHILD_BATCH_SIZE = 20;

    /** 模型类型必须为 text */
    private static final String MODEL_TYPE_TEXT = "text";

    /** RocketMQ topic / tag */
    private static final String MQ_TOPIC = "ASSET_EXTRACT_TOPIC";
    private static final String MQ_TAG = "extract";

    /** 业务任务类型（写入 aid_media_task.biz_task_type） */
    private static final String BIZ_TASK_TYPE = "storyboard_video_prompt";

    /** 续生窗口：24 小时 */
    private static final long RESUME_WINDOW_HOURS = 24L;

    /** LLM 输出 prompt 平均字符数（用于预估输出 token） */
    private static final int ESTIMATED_OUTPUT_CHARS = 600;

    /** aid_extract_task.input_snapshot 为 MySQL TEXT，按 UTF-8 字节数守住存储上限。 */
    private static final int INPUT_SNAPSHOT_MAX_BYTES = 65_535;

    /**
     * 视频提示词格式校验必须命中的标签——多参方向（agent=aid_visual_director）。
     * 对齐该智能体输出契约（其 prompt_content 第七/九节）：每条 prompt 为固定三段式
     * {@code # 主题 / # 运镜 / # 风格}（C 级轻量镜头同样保留三段）。
     */
    private static final String[] VIDEO_PROMPT_REQUIRED_TOKENS = {"# 主题", "# 运镜", "# 风格"};

    /**
     * 视频提示词格式校验必须命中的标签——多参专业版（agent=aid_visual_director_multiref）。
     * 专业版采用 SD2.0 扁平结构；角色、道具和分段镜头均可能因当前镜头内容而省略，
     * 因此只校验每条合法输出都必须存在的场景声明与全局风格收尾。
     */
    private static final String[] VIDEO_PROMPT_MULTIREF_REQUIRED_TOKENS = {"场景：", "全局风格："};

    /**
     * 视频提示词格式校验必须命中的标签——图生方向（漫剧版）（agent=aid_visual_director_image）。
     * 该智能体输出公式为 {@code 镜头运动：[...]\n画面描述：[...]}，与多参方向的三段式不同，
     * 故两方向用各自的校验标签（共用一套会误杀其中一个方向）。
     */
    private static final String[] VIDEO_PROMPT_IMAGE_REQUIRED_TOKENS = {"镜头运动：", "画面描述："};

    /**
     * 视频提示词格式校验必须命中的标签——宫格方向（auto_grid 专业版）（agent=aid_visual_director_grid）。
     * 宫格视觉导演输出结构与多参三段式、图生「镜头运动/画面描述」均不同（核心是「切换镜头 + 参考分镜图」逐宫格描述），
     * 故单独一套校验标签，避免被另两套误杀。
     */
    private static final String[] VIDEO_PROMPT_GRID_REQUIRED_TOKENS = {"切换镜头", "参考分镜图"};

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record VideoPromptWrite(AidStoryboard storyboard, String prompt,
                                    String mergedScriptParams, int index) { }

    /** 视频提示词中的业务私有图片占位，输出落库前按当前资产白名单做文字降级。 */
    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile("@图片(\\d+)\\[([^\\]]+)\\]");
    @Autowired
    private IAidStoryboardService storyboardService;
    @Autowired
    private IAidComicProjectService projectService;
    @Autowired
    private IAidComicEpisodeService aidComicEpisodeService;
    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private BatchTaskSlotService batchTaskSlotService;

    @Autowired
    private BatchParentSubmissionGuard batchParentSubmissionGuard;
    @Autowired
    private IAidAgentService aidAgentService;
    @Autowired
    private StoryboardVideoPromptContinuityAssembler continuityAssembler;
    @Autowired
    private IAiModelConfigService aiModelConfigService;

    /** 项目级配置统一解析器（3 级兜底：用户传 → 项目配置 → aid_config 默认） */
    @Autowired
    private com.aid.projectgenconfig.service.IProjectGenConfigResolver projectGenConfigResolver;
    /** 业务功能模型池查询（图生方向强校验模型是否在 main_storyboard_video_prompt_image 池内） */
    @Autowired
    private IAiModelBusinessService aiModelBusinessService;
    @Autowired
    private IExtractBillingService extractBillingService;
    @Autowired
    private BillingAmountCalculator billingAmountCalculator;
    @Autowired
    private BillingQuoteAssembler billingQuoteAssembler;
    /** 账户服务（建任务前的余额只读预检，冻结仍走统一 prepareBilling） */
    @Autowired
    private com.aid.billing.service.IAccountUpdateService accountUpdateService;
    @Autowired
    private IAssetExtractService assetExtractService;
    @Autowired
    private AssetExtractHelper helper;
    @Autowired
    private RedisCache redisCache;

    /** 项目/剧集级锁的安全获取器（含僵尸锁自愈，与 AssetExtractServiceImpl 提取锁同一机制） */
    @Autowired
    private ProjectGenerateLockGuard projectLockGuard;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private TaskQueueService taskQueueService;

    /** 双模式派发路由器（MQ/本地切换唯一收口） */
    @Autowired
    private com.aid.rps.queue.DualModeTaskDispatcher dualModeTaskDispatcher;

    /** 本地派发终态编排器（与 MQ Consumer 共用同一套终态语义） */
    @Autowired
    private BatchTaskLocalOrchestrator batchTaskLocalOrchestrator;

    @Autowired
    private StoryboardStepChainService storyboardStepChainService;

    @Autowired
    private IStoryboardVideoGenerationService storyboardVideoGenerationService;

    /** 本地编排规格：分镜视频提示词 */
    private static final BatchTaskLocalOrchestrator.Spec LOCAL_SPEC =
            new BatchTaskLocalOrchestrator.Spec(
                    "storyboard_video_prompt", "初始化视频提示词批量生成...",
                    "剩余镜头已取消", "部分镜头生成失败，可续生",
                    TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH);
    @Autowired
    private AssetExtractSseManager sseManager;

    @Autowired
    private IAidRolePropSceneService rolePropSceneService;

    @Autowired
    private IAidRolePropSceneFormService rolePropSceneFormService;

    @Autowired
    private IAidRolePropSceneFormImageService rolePropSceneFormImageService;

    /** 角色级音频引用统一解析器：按台词补齐占位并兼容历史形态级名称。 */
    @Autowired
    private StoryboardRoleAudioReferenceResolver roleAudioReferenceResolver;

    @Autowired
    private StoryboardSceneContextAssembler storyboardSceneContextAssembler;

    /** aid_media_task 直读：结算阶段聚合 LLM 真实 token（provider usage 落在 billing_snapshot_json）。
     *  aid_media_task 无独立 Service，沿用现有直读 Mapper 的统一做法（参考 AssetExtractServiceImpl）。 */
    @Autowired
    private AidMediaTaskMapper aidMediaTaskMapper;

    /** Provider 整批成功后的分镜写回必须原子提交，消除 child 已结算但只落库部分镜头的崩溃窗。 */
    @Autowired
    private TransactionTemplate transactionTemplate;
    /**
     * 按方向与智能体校验视频提示词格式。
     * 标准多参输出三段式 {@code # 主题/# 运镜/# 风格}；专业版多参输出 SD2.0 扁平结构；
     * 图生方向输出 {@code 镜头运动：/画面描述：} 公式。各智能体使用独立标签，避免互相误杀。
     *
     * @param videoPrompt 模型输出的单条 prompt
     * @param direction   方向：{@code multi}=多参 / {@code image}=镜头运动+画面描述 / {@code grid}=切换镜头+参考分镜图
     * @param agentCode   本次生成使用的智能体编码
     */
    private boolean isVideoPromptFormatValid(String videoPrompt, String direction, String agentCode)
    {
        String[] tokens;
        if (DIRECTION_GRID.equals(direction))
        {
            tokens = VIDEO_PROMPT_GRID_REQUIRED_TOKENS;
        }
        else if (DIRECTION_IMAGE.equals(direction))
        {
            tokens = VIDEO_PROMPT_IMAGE_REQUIRED_TOKENS;
        }
        else if (Objects.equals(AGENT_CODE_MULTIREF, agentCode))
        {
            tokens = VIDEO_PROMPT_MULTIREF_REQUIRED_TOKENS;
        }
        else
        {
            // 标准多参方向保持原三段式校验。
            tokens = VIDEO_PROMPT_REQUIRED_TOKENS;
        }
        return matchesAllTokens(videoPrompt, tokens);
    }

    /** 文本是否命中给定的全部必需标签（任一缺失即不合法；空文本不合法）。 */
    private boolean matchesAllTokens(String text, String[] tokens)
    {
        if (StrUtil.isBlank(text))
        {
            return false;
        }
        for (String token : tokens)
        {
            if (!text.contains(token))
            {
                return false;
            }
        }
        return true;
    }
    @Override
    public void saveManualVideoPrompt(Long storyboardId, String videoPrompt, Long userId)
    {
        if (Objects.isNull(storyboardId) || Objects.isNull(userId))
        {
            throw new ServiceException("参数错误");
        }
        if (StrUtil.isBlank(videoPrompt))
        {
            log.info("手动视频提示词为空: storyboardId={}, userId={}", storyboardId, userId);
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_EMPTY, "请填写提示词");
        }

        // 精简查询字段：格式校验只需要分镜归属及其创作模式定位信息。
        AidStoryboard storyboard = storyboardService.getOne(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId)
                        .eq(AidStoryboard::getId, storyboardId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .last("limit 1"), false);
        if (Objects.isNull(storyboard))
        {
            log.info("手动视频提示词分镜不存在: storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("分镜不存在");
        }

        CreationModeEnum creationMode = CreationModeEnum.getByValue(
                resolveCreationMode(storyboard.getProjectId(), storyboard.getEpisodeId(), userId));
        String formatAgentCode = Objects.equals(CreationModeEnum.PRO, creationMode)
                ? AGENT_CODE_MULTIREF : DEFAULT_AGENT_CODE;
        if (!isVideoPromptFormatValid(videoPrompt, DIRECTION_MULTI, formatAgentCode))
        {
            log.info("手动视频提示词格式不规范: storyboardId={}, userId={}, creationMode={}",
                    storyboardId, userId, creationMode);
            throw new ServiceException("提示词不规范");
        }

        // 落库时再次带上归属条件，防止校验与更新之间的数据变化。
        LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
        upd.eq(AidStoryboard::getId, storyboardId);
        upd.eq(AidStoryboard::getUserId, userId);
        upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
        upd.set(AidStoryboard::getVideoPrompt, videoPrompt);
        upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
        upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
        boolean updated = storyboardService.update(upd);
        if (!updated)
        {
            log.info("手动视频提示词落库失败（分镜不存在或无权）: storyboardId={}, userId={}", storyboardId, userId);
            throw new ServiceException("分镜不存在");
        }
        log.info("手动视频提示词保存成功: storyboardId={}, userId={}", storyboardId, userId);
    }
    @Override
    public AssetExtractTaskVO batchGenerateVideoPrompt(Long projectId, Long episodeId, Long userId,
                                                       List<Long> storyboardIds,
                                                       String agentCode, String modelCode,
                                                       Boolean overwrite, String continuityMode,
                                                       Long previousStoryboardId,
                                                       Map<String, Object> chainNext)
    {
        // 多参方向：回填 aid_storyboard.video_prompt
        return batchGenerateInternal(DIRECTION_MULTI, projectId, episodeId, userId,
                storyboardIds, agentCode, modelCode, overwrite,
                continuityMode, previousStoryboardId, chainNext);
    }

    @Override
    public AssetExtractTaskVO batchGenerateVideoPromptImage(Long projectId, Long episodeId, Long userId,
                                                            List<Long> storyboardIds,
                                                            String agentCode, String modelCode,
                                                            Boolean overwrite, String continuityMode,
                                                            Long previousStoryboardId)
    {
        // 图生方向（漫剧版）：回填 aid_storyboard.video_prompt_image，与多参物理隔离
        return batchGenerateInternal(DIRECTION_IMAGE, projectId, episodeId, userId,
                storyboardIds, agentCode, modelCode, overwrite,
                continuityMode, previousStoryboardId, null);
    }

    @Override
    public AssetExtractTaskVO batchGenerateVideoPromptGrid(Long projectId, Long episodeId, Long userId,
                                                           List<Long> storyboardIds,
                                                           String agentCode, String modelCode,
                                                           Boolean overwrite, String continuityMode,
                                                           Long previousStoryboardId)
    {
        // 宫格方向（auto_grid 专业版）：biz=main_storyboard_video_prompt_grid，agent=aid_visual_director_grid，
        // 复用图生列 video_prompt_image 回填（不新增列），与图生/多参共用任务/计费/锁/队列骨架。
        return batchGenerateInternal(DIRECTION_GRID, projectId, episodeId, userId,
                storyboardIds, agentCode, modelCode, overwrite,
                continuityMode, previousStoryboardId, null);
    }

    @Override
    public BillingQuoteVO quoteVideoPrompt(StoryboardVideoPromptBatchRequest request, Long userId)
    {
        return quoteVideoPromptPlan("STORYBOARD_VIDEO_PROMPT", DIRECTION_MULTI,
                request.getProjectId(), request.getEpisodeId(), userId, request.getStoryboardIds(),
                request.getAgentCode(), request.getModelCode(), request.getOverwrite(),
                request.getContinuityMode(), request.getPreviousStoryboardId());
    }

    @Override
    public BillingQuoteVO quoteVideoPromptImage(StoryboardVideoPromptImageBatchRequest request, Long userId)
    {
        return quoteVideoPromptPlan("STORYBOARD_VIDEO_PROMPT_IMAGE", DIRECTION_IMAGE,
                request.getProjectId(), request.getEpisodeId(), userId, request.getStoryboardIds(),
                request.getAgentCode(), request.getModelCode(), request.getOverwrite(),
                request.getContinuityMode(), request.getPreviousStoryboardId());
    }

    @Override
    public BillingQuoteVO quoteVideoPromptGrid(StoryboardVideoPromptImageBatchRequest request, Long userId)
    {
        return quoteVideoPromptPlan("STORYBOARD_VIDEO_PROMPT_GRID", DIRECTION_GRID,
                request.getProjectId(), request.getEpisodeId(), userId, request.getStoryboardIds(),
                request.getAgentCode(), request.getModelCode(), request.getOverwrite(),
                request.getContinuityMode(), request.getPreviousStoryboardId());
    }

    @Override
    public BillingQuoteVO quoteVideoWithPrompt(StoryboardVideoWithPromptRequest request, Long userId)
    {
        if (request == null || request.getProjectId() == null || request.getEpisodeId() == null)
        {
            throw new ServiceException("参数错误");
        }
        AutoVideoRoute route = resolveAutoVideoRoute(
                resolveCreationMode(request.getProjectId(), request.getEpisodeId(), userId));
        VideoPromptBillingPlan plan = prepareVideoPromptBillingPlan(route.direction(),
                request.getProjectId(), request.getEpisodeId(), userId, request.getStoryboardIds(),
                request.getAgentCode(), request.getModelCode(), request.getOverwrite(),
                request.getContinuityMode(), request.getPreviousStoryboardId());
        BillingQuoteVO promptQuote = assembleVideoPromptQuote(
                quoteTypeForDirection(route.direction()), plan);
        BillingQuoteVO mediaQuote = quotePlannedVideoBatches(route, request,
                plan.targetList().stream().map(AidStoryboard::getId).toList(), userId);
        return billingQuoteAssembler.aggregate(
                "STORYBOARD_VIDEO_WITH_PROMPT", List.of(promptQuote, mediaQuote));
    }

    private BillingQuoteVO quoteVideoPromptPlan(String quoteType, String direction,
            Long projectId, Long episodeId, Long userId, List<Long> storyboardIds,
            String agentCode, String modelCode, Boolean overwrite,
            String continuityMode, Long previousStoryboardId)
    {
        VideoPromptBillingPlan plan = prepareVideoPromptBillingPlan(direction, projectId, episodeId,
                userId, storyboardIds, agentCode, modelCode, overwrite,
                continuityMode, previousStoryboardId);
        return assembleVideoPromptQuote(quoteType, plan);
    }

    private BillingQuoteVO assembleVideoPromptQuote(
            String quoteType, VideoPromptBillingPlan plan)
    {
        if (plan.noTargets())
        {
            throw new ServiceException("视频提示词已生成");
        }
        if (plan.billingResult() == null || !plan.billingResult().isMatched())
        {
            throw new ServiceException("计费规则缺失");
        }
        return billingQuoteAssembler.single(quoteType, plan.modelConfig(), plan.billingResult(), 1);
    }

    private BillingQuoteVO quotePlannedVideoBatches(AutoVideoRoute route,
            StoryboardVideoWithPromptRequest source, List<Long> targetIds, Long userId)
    {
        List<BillingQuoteVO> quotes = new ArrayList<>();
        for (int start = 0; start < targetIds.size(); start += CHAIN_CHILD_BATCH_SIZE)
        {
            List<Long> ids = new ArrayList<>(targetIds.subList(
                    start, Math.min(start + CHAIN_CHILD_BATCH_SIZE, targetIds.size())));
            if (DIRECTION_IMAGE.equals(route.direction()))
            {
                StoryboardVideoFromImageGenerateRequest request = new StoryboardVideoFromImageGenerateRequest();
                copyPlannedVideoOptions(source, request, ids);
                quotes.add(storyboardVideoGenerationService.quotePlannedVideoFromImage(request, userId));
            }
            else if (DIRECTION_GRID.equals(route.direction()))
            {
                StoryboardVideoGridGenerateRequest request = new StoryboardVideoGridGenerateRequest();
                copyPlannedVideoOptions(source, request, ids);
                quotes.add(storyboardVideoGenerationService.quotePlannedVideoFromGrid(request, userId));
            }
            else
            {
                StoryboardVideoGenerateRequest request = new StoryboardVideoGenerateRequest();
                request.setStoryboardIds(ids);
                request.setModelName(source.getGenModelName());
                request.setAspectRatio(source.getGenAspectRatio());
                request.setResolution(source.getGenResolution());
                request.setDurationSeconds(source.getGenDurationSeconds());
                request.setGenerateAudio(source.getGenGenerateAudio());
                quotes.add(storyboardVideoGenerationService.quotePlannedVideo(request, userId));
            }
        }
        return billingQuoteAssembler.aggregate(quoteTypeForMediaRoute(route), quotes);
    }

    /** 与前端合并报价共用规格和素材规划，直接调用合并生成也不能绕过检查。 */
    void preflightVideoChain(String direction, Map<String, Object> chainNext,
            List<Long> targetIds, Long userId)
    {
        if (chainNext == null || chainNext.isEmpty()) return;
        StoryboardVideoWithPromptRequest source = new StoryboardVideoWithPromptRequest();
        source.setGenModelName(Convert.toStr(chainNext.get("modelName"), null));
        source.setGenAspectRatio(Convert.toStr(chainNext.get("aspectRatio"), null));
        source.setGenResolution(Convert.toStr(chainNext.get("resolution"), null));
        if (chainNext.get("durationSeconds") instanceof Number n) source.setGenDurationSeconds(n.intValue());
        if (chainNext.get("generateAudio") instanceof Boolean b) source.setGenGenerateAudio(b);
        quotePlannedVideoBatches(new AutoVideoRoute(direction, Convert.toStr(chainNext.get("type"), null)),
                source, targetIds, userId);
    }

    private void copyPlannedVideoOptions(StoryboardVideoWithPromptRequest source,
            StoryboardVideoFromImageGenerateRequest target, List<Long> ids)
    {
        target.setStoryboardIds(ids);
        target.setModelName(source.getGenModelName());
        target.setAspectRatio(source.getGenAspectRatio());
        target.setResolution(source.getGenResolution());
        target.setDurationSeconds(source.getGenDurationSeconds());
        target.setGenerateAudio(source.getGenGenerateAudio());
    }

    private void copyPlannedVideoOptions(StoryboardVideoWithPromptRequest source,
            StoryboardVideoGridGenerateRequest target, List<Long> ids)
    {
        target.setStoryboardIds(ids);
        target.setModelName(source.getGenModelName());
        target.setAspectRatio(source.getGenAspectRatio());
        target.setResolution(source.getGenResolution());
        target.setDurationSeconds(source.getGenDurationSeconds());
        target.setGenerateAudio(source.getGenGenerateAudio());
    }

    private String quoteTypeForDirection(String direction)
    {
        if (DIRECTION_IMAGE.equals(direction)) return "STORYBOARD_VIDEO_PROMPT_IMAGE";
        if (DIRECTION_GRID.equals(direction)) return "STORYBOARD_VIDEO_PROMPT_GRID";
        return "STORYBOARD_VIDEO_PROMPT";
    }

    private String quoteTypeForMediaRoute(AutoVideoRoute route)
    {
        if (CHAIN_TYPE_VIDEO_IMAGE.equals(route.chainType())) return "STORYBOARD_VIDEO_IMAGE";
        if (CHAIN_TYPE_VIDEO_GRID.equals(route.chainType())) return "STORYBOARD_VIDEO_GRID";
        return "STORYBOARD_VIDEO";
    }

    /** chainNext 链路类型常量（与 StoryboardStepChainService 协议一致）。 */
    private static final String CHAIN_TYPE_VIDEO = "video";
    private static final String CHAIN_TYPE_VIDEO_IMAGE = "video_image";
    private static final String CHAIN_TYPE_VIDEO_GRID = "video_grid";

    /**
     * 【统一视频合一】批量生成分镜视频提示词 + 自动出片，按创作模式自动路由。
     * i2v→图生方向(video_image)、multi/pro→多参方向(video，pro 池在出片侧自动切)、auto_grid→宫格方向(video_grid)。
     * 提示词阶段智能体由 resolver 依据 creation_mode 自动解析（agentCode 透传，通常为空）。
     */
    @Override
    public AssetExtractTaskVO batchGenerateVideoWithPromptAuto(StoryboardVideoWithPromptRequest request, Long userId)
    {
        // 入参兜底（详细字段校验交给内部实现）
        if (Objects.isNull(request) || Objects.isNull(request.getProjectId()) || Objects.isNull(request.getEpisodeId()))
        {
            log.error("统一视频合一入参无效: userId={}", userId);
            throw new ServiceException("参数错误");
        }
        String creationMode = resolveCreationMode(request.getProjectId(), request.getEpisodeId(), userId);
        AutoVideoRoute route = resolveAutoVideoRoute(creationMode);
        String direction = route.direction();
        String chainType = route.chainType();
        Map<String, Object> chainNext = new LinkedHashMap<>();
        chainNext.put("type", chainType);                                  // 出片链路类型
        chainNext.put("modelName", request.getGenModelName());             // 出片模型（可选）
        chainNext.put("aspectRatio", request.getGenAspectRatio());         // 出片宽高比（可选）
        chainNext.put("resolution", request.getGenResolution());           // 出片清晰度档位（可选）
        chainNext.put("durationSeconds", request.getGenDurationSeconds()); // 出片时长秒（可选）
        chainNext.put("generateAudio", request.getGenGenerateAudio());     // 是否出音频（可选）
        log.info("统一视频合一路由: userId={}, projectId={}, episodeId={}, creationMode={}, direction={}, chainType={}",
                userId, request.getProjectId(), request.getEpisodeId(), creationMode, direction, chainType);
        return batchGenerateInternal(direction, request.getProjectId(), request.getEpisodeId(), userId,
                request.getStoryboardIds(), request.getAgentCode(), request.getModelCode(),
                request.getOverwrite(), request.getContinuityMode(),
                request.getPreviousStoryboardId(), chainNext);
    }

    private AutoVideoRoute resolveAutoVideoRoute(String creationMode)
    {
        CreationModeEnum mode = CreationModeEnum.getByValue(creationMode);
        if (CreationModeEnum.I2V.equals(mode))
        {
            return new AutoVideoRoute(DIRECTION_IMAGE, CHAIN_TYPE_VIDEO_IMAGE);
        }
        if (CreationModeEnum.AUTO_GRID.equals(mode))
        {
            return new AutoVideoRoute(DIRECTION_GRID, CHAIN_TYPE_VIDEO_GRID);
        }
        return new AutoVideoRoute(DIRECTION_MULTI, CHAIN_TYPE_VIDEO);
    }

    private record AutoVideoRoute(String direction, String chainType)
    {
    }

    /**
     * 解析创作模式（剧集 creation_mode 优先 → 项目 default_creation_mode 兜底 → 空串）。
     * 口径与 {@code StoryboardVideoGenerationServiceImpl.resolveCreationMode} 一致，保证提示词侧路由与出片侧分流同源。
     */
    private String resolveCreationMode(Long projectId, Long episodeId, Long userId)
    {
        if (Objects.nonNull(episodeId) && episodeId > 0)
        {
            AidComicEpisode episode = aidComicEpisodeService.getOne(
                    Wrappers.<AidComicEpisode>lambdaQuery()
                            .select(AidComicEpisode::getId, AidComicEpisode::getCreationMode)
                            .eq(AidComicEpisode::getId, episodeId)
                            .eq(AidComicEpisode::getProjectId, projectId)
                            .eq(AidComicEpisode::getUserId, userId)
                            .eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL)
                            .last("limit 1"), false);
            if (Objects.nonNull(episode) && StrUtil.isNotBlank(episode.getCreationMode()))
            {
                return episode.getCreationMode().trim();
            }
        }
        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        return Objects.isNull(project) ? "" : StrUtil.trimToEmpty(project.getDefaultCreationMode());
    }

    /**
     * 批量生成视频提示词内部实现：多参 / 图生 / 宫格三方向共用同一套任务/计费/锁/队列骨架，
     * 仅以 {@code direction} 区分 业务分类 / 默认智能体 / 回填列 / 输入装配 / "已生成"判定列。
     */
    private AssetExtractTaskVO batchGenerateInternal(String direction, Long projectId, Long episodeId, Long userId,
                                                     List<Long> storyboardIds,
                                                     String agentCode, String modelCode,
                                                     Boolean overwrite, String continuityMode,
                                                     Long previousStoryboardId,
                                                     Map<String, Object> chainNext)
    {
        VideoPromptBillingPlan plan = prepareVideoPromptBillingPlan(direction, projectId, episodeId, userId,
                storyboardIds, agentCode, modelCode, overwrite, continuityMode, previousStoryboardId);
        if (plan.noTargets())
        {
            AssetExtractTaskVO reconnect = findReconnectableTask(projectId, episodeId, userId);
            if (reconnect != null)
            {
                return reconnect;
            }
            throw new ServiceException("视频提示词已生成");
        }
        // 已有任务重连走上面的只读分支；新链路必须在余额预检、占位、冻结和付费文本调用前检查出片。
        preflightVideoChain(direction, chainNext,
                plan.targetList().stream().map(AidStoryboard::getId).toList(), userId);
        agentCode = plan.agentCode();
        String resolvedModelCode = plan.modelCode();
        List<AidStoryboard> targetList = plan.targetList();
        boolean overwriteFlag = plan.overwrite();
        boolean singleSelection = plan.singleSelection();
        ContinuityContext continuityContext = plan.continuityContext();
        BillingCalcResult calc = plan.billingResult();
        BigDecimal totalFrozen = plan.preHoldAmount();
        // 余额前置预检（只读）：不足直接抛「预扣余额不足」，最终仍以 prepareBilling 锁内冻结为准
        accountUpdateService.precheckBalance(userId, totalFrozen);

        // 抢锁失败时走僵尸锁自愈：DB 复核 + 锁年龄检查 + CAS 清理 + 重抢，
        // 避免「DB 无活跃任务但 Redis 锁泄漏」时让用户卡 30 分钟才能重新提交
        long lockTtlSeconds = Math.max(1800L, (long) targetList.size() * 30L);
        String lockKey = buildLockKey(projectId, episodeId);
        ProjectGenerateLockGuard.AcquireResult lockResult = projectLockGuard.tryAcquireWithStaleClean(
                lockKey, lockTtlSeconds, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH, projectId, episodeId);
        if (!lockResult.isAcquired())
        {
            throw new ServiceException("任务执行中，请先停止");
        }
        BatchTaskSlotReservation slotReservation;
        try
        {
            slotReservation = batchTaskSlotService.acquireTaskSlots(projectId, episodeId,
                    List.of(BatchTaskLogicalType.STORYBOARD_VIDEO_WORKFLOW));
        }
        catch (RuntimeException ex)
        {
            projectLockGuard.releaseIfMatch(lockKey, lockResult.getToken());
            throw ex;
        }

        AidExtractTask task = null;
        try
        {
            task = new AidExtractTask();
            task.setProjectId(projectId);
            task.setEpisodeId(episodeId);
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH);
            task.setStatus(TASK_STATUS_PENDING);
            task.setModelCode(resolvedModelCode);
            task.setTotalCount(targetList.size());
            // inputSnapshot
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("projectId", projectId);
            inputMap.put("episodeId", episodeId);
            inputMap.put("direction", direction);
            inputMap.put("agentCode", agentCode);
            inputMap.put("modelCode", resolvedModelCode);
            inputMap.put("overwrite", overwriteFlag);
            inputMap.put("projectLockToken", lockResult.getToken());
            inputMap.put("storyboardIds", targetList.stream().map(AidStoryboard::getId).collect(Collectors.toList()));
            if (continuityContext.enabled())
            {
                inputMap.put("continuityMode", VideoPromptContinuityMode.PREVIOUS_PROMPT.getValue());
                inputMap.put("continuitySingleSelection", singleSelection);
                inputMap.put("continuityContext", continuityContext);
                if (singleSelection)
                {
                    inputMap.put("previousStoryboardId", previousStoryboardId);
                }
            }
            // 合并接口：链式触发下一步（出视频）规格，提示词终态后由 StoryboardStepChainService 自动发起
            if (chainNext != null && !chainNext.isEmpty())
            {
                inputMap.put("chainNext", chainNext);
            }
            batchTaskSlotService.attachSnapshotMetadata(inputMap, slotReservation);
            try
            {
                String inputSnapshot = OBJECT_MAPPER.writeValueAsString(inputMap);
                assertContinuitySnapshotCapacity(inputSnapshot, continuityContext.enabled(),
                        projectId, episodeId);
                task.setInputSnapshot(inputSnapshot);
            }
            catch (ServiceException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                log.error("视频提示词 inputSnapshot 序列化失败: projectId={}, episodeId={}", projectId, episodeId, e);
                throw new ServiceException("提交失败，请重试");
            }
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            task.setUpdateTime(DateUtils.getNowDate());
            task.setUpdateBy(String.valueOf(userId));
            extractTaskService.save(task);

            // 保存计费快照：结算时 settleBilling 据此 + 实际 usage(token) 重算，否则会按预冻结额全扣
            String billingSnapshotJson = null;
            if (calc != null && calc.getSnapshot() != null)
            {
                attachUsageWatermark(calc.getSnapshot(),
                        extractBillingService.findLatestBillingMediaTaskId(task.getId()));
                try { billingSnapshotJson = JSONUtil.toJsonStr(calc.getSnapshot()); }
                catch (Exception ignore) { /* 快照序列化失败不阻断，退化为按预冻结额结算 */ }
            }
            extractBillingService.prepareBilling(task.getId(), userId, totalFrozen, billingSnapshotJson);
            log.info("视频提示词批量预冻结: taskId={}, shotCount={}, frozen={}", task.getId(), targetList.size(), totalFrozen);

            sendMqMessage(task.getId(), projectId, episodeId, userId, resolvedModelCode);

            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .totalShots(targetList.size())
                    .build();
        }
        catch (Exception e)
        {
            // 半态清理（CAS 释放，避免极端尾部场景误删被自愈机制重抢的新锁）
            projectLockGuard.releaseIfMatch(lockKey, lockResult.getToken());
            if (task != null && task.getId() != null)
            {
                AidExtractTask current = extractTaskService.selectAidExtractTaskById(task.getId());
                String expectedTraceId = current == null ? null : current.getBillingTraceId();
                int failedRows = 0;
                try
                {
                    LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
                    upd.eq(AidExtractTask::getId, task.getId());
                    upd.eq(AidExtractTask::getUserId, userId);
                    upd.eq(AidExtractTask::getStatus, TASK_STATUS_PENDING);
                    if (expectedTraceId == null)
                    {
                        upd.isNull(AidExtractTask::getBillingTraceId);
                    }
                    else
                    {
                        upd.eq(AidExtractTask::getBillingTraceId, expectedTraceId);
                    }
                    upd.set(AidExtractTask::getStatus, "FAILED");
                    upd.set(AidExtractTask::getErrorMessage, "提交失败")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("提交失败"));
                    upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
                    failedRows = extractTaskService.getBaseMapper().update(null, upd);
                }
                catch (Exception updateEx)
                {
                    log.error("视频提示词任务提交失败状态收口异常: taskId={}, traceId={}",
                            task.getId(), expectedTraceId, updateEx);
                }
                if (failedRows > 0)
                {
                    try { extractBillingService.refundBilling(task.getId(), userId, expectedTraceId); }
                    catch (Exception refundEx)
                    {
                        log.error("视频提示词任务提交失败退款异常，等待补偿收口: taskId={}, traceId={}",
                                task.getId(), expectedTraceId, refundEx);
                    }
                }
            }
            if (task != null && task.getId() != null)
            {
                batchTaskSlotService.releaseForTask(task);
            }
            else
            {
                batchTaskSlotService.release(slotReservation);
            }
            if (e instanceof ServiceException) { throw (ServiceException) e; }
            throw new ServiceException("提交失败，请重试");
        }
    }

    /** 构建只读视频提示词提交计划；正式提交与报价共同消费该不可变结果。 */
    private VideoPromptBillingPlan prepareVideoPromptBillingPlan(String direction,
            Long projectId, Long episodeId, Long userId, List<Long> storyboardIds,
            String requestedAgentCode, String requestedModelCode, Boolean overwrite,
            String continuityMode, Long previousStoryboardId)
    {
        boolean imageDir = DIRECTION_IMAGE.equals(direction);
        boolean gridDir = DIRECTION_GRID.equals(direction);
        boolean imageColumn = imageDir || gridDir;
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || Objects.isNull(userId))
        {
            throw new ServiceException("参数错误");
        }
        if (storyboardIds != null && storyboardIds.size() > 100)
        {
            throw new ServiceException("批量过多");
        }
        List<Long> uniqueIds = CollectionUtil.isEmpty(storyboardIds) ? new ArrayList<>()
                : storyboardIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        boolean explicitShots = CollectionUtil.isNotEmpty(uniqueIds);
        boolean overwriteFlag = explicitShots || Boolean.TRUE.equals(overwrite);

        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        if (Objects.isNull(project) || !Objects.equals(userId, project.getUserId())
                || !DEL_FLAG_NORMAL.equals(project.getDelFlag()))
        {
            throw new ServiceException("项目不存在");
        }
        com.aid.projectgenconfig.enums.ProjectGenConfigScene scene = gridDir
                ? com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_VIDEO_PROMPT_GRID
                : (imageDir
                    ? com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_VIDEO_PROMPT_IMAGE
                    : com.aid.projectgenconfig.enums.ProjectGenConfigScene.STORYBOARD_VIDEO_PROMPT);
        com.aid.projectgenconfig.service.ResolvedSceneConfig resolved = projectGenConfigResolver.resolve(
                projectId, episodeId, userId, scene, requestedAgentCode, requestedModelCode, null, null);
        String agentCode = resolved.getAgentCode();
        String modelCode = resolved.getModelCode();
        AidAgent agent = aidAgentService.getByAgentCode(agentCode);
        if (Objects.isNull(agent) || StrUtil.isBlank(agent.getPromptContent()))
        {
            throw new ServiceException("智能体不可用");
        }
        AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(modelCode, helper.businessFunctionForAgent(agentCode), null);
        if (Objects.isNull(modelConfig))
        {
            throw new ServiceException("模型不可用");
        }

        List<AidStoryboard> storyboardList = explicitShots
                ? loadTargetStoryboards(projectId, episodeId, userId, uniqueIds)
                : loadEpisodeStoryboards(projectId, episodeId, userId);
        if (CollectionUtil.isEmpty(storyboardList))
        {
            throw new ServiceException("分镜不能为空");
        }
        List<AidStoryboard> targetList = overwriteFlag ? storyboardList : storyboardList.stream()
                .filter(item -> StrUtil.isBlank(imageColumn
                        ? item.getVideoPromptImage() : item.getVideoPrompt()))
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(targetList))
        {
            return new VideoPromptBillingPlan(agentCode, modelCode, modelConfig, List.of(),
                    overwriteFlag, false, null, null, BigDecimal.ZERO, true);
        }
        if (targetList.size() > 100)
        {
            throw new ServiceException("批量过多");
        }

        boolean groupStructure = gridDir || AGENT_CODE_MULTIREF.equals(agentCode);
        if (imageColumn)
        {
            assertImagePromptReadyForVideoPrompt(targetList);
        }
        for (AidStoryboard storyboard : targetList)
        {
            validateShotForVideoPrompt(storyboard, imageColumn, groupStructure);
        }
        boolean singleSelection = explicitShots && uniqueIds.size() == 1;
        ContinuityContext continuityContext = continuityAssembler.resolve(
                projectId, episodeId, userId, targetList, continuityMode,
                previousStoryboardId, singleSelection, imageColumn);
        int batchInputChars = estimateBatchInputChars(direction, agentCode, modelCode,
                StrUtil.nullToEmpty(project.getVideoStyleType()),
                StrUtil.nullToEmpty(project.getVideoStyleValue()), targetList,
                projectId, episodeId, userId, continuityContext);
        int batchOutputChars = ESTIMATED_OUTPUT_CHARS * targetList.size();
        Map<String, Object> billingParams = new HashMap<>();
        billingParams.put("inputChars", batchInputChars);
        billingParams.put("inputTokens", BillingConstants.charsToTokens(batchInputChars));
        billingParams.put("outputTokens", BillingConstants.charsToTokens(batchOutputChars));
        billingParams.put("totalChars", batchInputChars + batchOutputChars);
        helper.applyLlmBillingLimits(billingParams, 1);
        BillingCalcResult result = billingAmountCalculator.calculatePreHoldAmount(
                modelConfig, new BillingInput("TEXT", billingParams));
        BigDecimal preHoldAmount = result != null && result.isMatched() && result.getAmount() != null
                ? result.getAmount() : BigDecimal.ZERO;
        return new VideoPromptBillingPlan(agentCode, modelCode, modelConfig,
                List.copyOf(targetList), overwriteFlag, singleSelection, continuityContext,
                result, preHoldAmount, false);
    }

    private record VideoPromptBillingPlan(String agentCode, String modelCode,
            AiModelConfigVo modelConfig, List<AidStoryboard> targetList, boolean overwrite,
            boolean singleSelection, ContinuityContext continuityContext,
            BillingCalcResult billingResult, BigDecimal preHoldAmount,
            boolean noTargets)
    {
    }

    private record VideoPromptResumePlan(AidExtractTask task, Map<String, Object> input,
            String direction, VideoPromptBillingPlan billingPlan, String billingSnapshotJson)
    {
    }

    @Override
    public BillingQuoteVO quoteResumeVideoPrompt(Long taskId, Long userId)
    {
        VideoPromptResumePlan plan = prepareVideoPromptResumePlan(taskId, userId);
        return assembleVideoPromptQuote("TASK_RESUME", plan.billingPlan());
    }

    @SuppressWarnings("unchecked")
    private VideoPromptResumePlan prepareVideoPromptResumePlan(Long taskId, Long userId)
    {
        AidExtractTask task = requireVideoPromptResumeTask(taskId, userId);
        Map<String, Object> input;
        try
        {
            input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
        }
        catch (Exception e)
        {
            throw new ServiceException("解析失败");
        }
        String direction = String.valueOf(input.getOrDefault("direction", DIRECTION_MULTI));
        boolean imageColumn = DIRECTION_IMAGE.equals(direction) || DIRECTION_GRID.equals(direction);
        Object rawStoryboardIds = input.get("storyboardIds");
        List<Long> originalIds = rawStoryboardIds instanceof List<?> rawIds
                ? rawIds.stream().map(Convert::toLong).filter(Objects::nonNull)
                        .distinct().toList()
                : List.of();
        if (originalIds.isEmpty())
        {
            throw new ServiceException("分镜不能为空");
        }
        List<AidStoryboard> stored = storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getVideoPrompt,
                                AidStoryboard::getVideoPromptImage)
                        .in(AidStoryboard::getId, originalIds)
                        .eq(AidStoryboard::getProjectId, task.getProjectId())
                        .eq(AidStoryboard::getEpisodeId, task.getEpisodeId())
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        List<Long> remainingIds = restoreSnapshotOrder(stored, originalIds).stream()
                .filter(storyboard -> StrUtil.isBlank(imageColumn
                        ? storyboard.getVideoPromptImage() : storyboard.getVideoPrompt()))
                .map(AidStoryboard::getId)
                .toList();
        if (remainingIds.isEmpty())
        {
            throw new ServiceException("无可续生镜头");
        }
        boolean gridDirection = DIRECTION_GRID.equals(direction);
        boolean imageDirection = DIRECTION_IMAGE.equals(direction);
        String defaultAgent = gridDirection ? DEFAULT_AGENT_CODE_GRID
                : (imageDirection ? DEFAULT_AGENT_CODE_IMAGE : DEFAULT_AGENT_CODE);
        String agentCode = StrUtil.blankToDefault(
                String.valueOf(input.getOrDefault("agentCode", defaultAgent)), defaultAgent);
        String modelCode = StrUtil.blankToDefault(
                String.valueOf(input.getOrDefault("modelCode", task.getModelCode())),
                task.getModelCode());
        VideoPromptBillingPlan billingPlan = prepareVideoPromptBillingPlan(direction,
                task.getProjectId(), task.getEpisodeId(), userId, remainingIds,
                agentCode, modelCode, Boolean.TRUE,
                Convert.toStr(input.get("continuityMode"), null),
                Convert.toLong(input.get("previousStoryboardId")));
        BillingCalcResult result = billingPlan.billingResult();
        String billingSnapshotJson = null;
        if (result != null && result.getSnapshot() != null)
        {
            attachUsageWatermark(result.getSnapshot(),
                    extractBillingService.findLatestBillingMediaTaskId(taskId));
            billingSnapshotJson = JSONUtil.toJsonStr(result.getSnapshot());
        }
        return new VideoPromptResumePlan(task, new LinkedHashMap<>(input), direction,
                billingPlan, billingSnapshotJson);
    }

    private AidExtractTask requireVideoPromptResumeTask(Long taskId, Long userId)
    {
        if (taskId == null)
        {
            throw new ServiceException("任务不能为空");
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null || !DEL_FLAG_NORMAL.equals(task.getDelFlag())
                || !Objects.equals(userId, task.getUserId()))
        {
            throw new ServiceException("任务不存在");
        }
        if (!TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(task.getTaskType()))
        {
            throw new ServiceException("类型不支持");
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            throw new ServiceException("状态不支持");
        }
        boolean refundedTerminal = BILLING_STATUS_REFUNDED.equals(task.getBillingStatus());
        if (!BILLING_STATUS_SUCCESS.equals(task.getBillingStatus()) && !refundedTerminal)
        {
            throw new ServiceException("结算未完成");
        }
        if (task.getCreateTime() != null
                && System.currentTimeMillis() - task.getCreateTime().getTime()
                        > RESUME_WINDOW_HOURS * 3600_000L)
        {
            throw new ServiceException("任务已过期");
        }
        return task;
    }

    /**
     * 断线重连兜底查询：当「全部已生成 + 未 overwrite」导致本次没有新目标镜头时，。
     *
     * @return 可重连任务 VO；无可重连任务时返回 {@code null}
     */
    private AssetExtractTaskVO findReconnectableTask(Long projectId, Long episodeId, Long userId)
    {
        // 防漏字段：除重连所需的 id/status/totalCount 外，补取 inputSnapshot/resultData，
        // 用于修复历史合并任务“提示词已成功但链式子任务未创建”的终态快照。
        AidExtractTask latest = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus, AidExtractTask::getTotalCount,
                                AidExtractTask::getInputSnapshot, AidExtractTask::getResultData)
                        .eq(AidExtractTask::getProjectId, projectId)
                        .eq(AidExtractTask::getEpisodeId, episodeId)
                        .eq(AidExtractTask::getUserId, userId)
                        .eq(AidExtractTask::getTaskType, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByDesc(AidExtractTask::getId)
                        .last("limit 1"), false);
        // 无历史任务：交回调用方抛「已生成」（可能是手动保存等非任务链路填充）
        if (Objects.isNull(latest) || StrUtil.isBlank(latest.getStatus()))
        {
            return null;
        }
        String status = latest.getStatus();
        if (ACTIVE_TASK_STATUS.contains(status))
        {
            throw new ServiceException("任务执行中，请先停止");
        }
        // 纯失败/取消无产物，不重连
        if (!RECONNECTABLE_STATUS.contains(status))
        {
            return null;
        }
        boolean chainTask = hasChainNext(latest.getInputSnapshot());
        if (!chainTask)
        {
            return null;
        }
        if ((TASK_STATUS_SUCCEEDED.equals(status) || TASK_STATUS_PARTIAL_FAILED.equals(status))
                && (!hasChainChildTaskId(latest.getResultData()) || isChainFailedResult(latest.getResultData())))
        {
            AssetExtractTaskVO chainRetry = retryChainAfterPromptsReady(latest);
            if (Objects.nonNull(chainRetry))
            {
                return chainRetry;
            }
        }
        log.info("视频提示词全部已生成，回传最近任务供前端断线重连: projectId={}, episodeId={}, taskId={}, status={}",
                projectId, episodeId, latest.getId(), status);
        return AssetExtractTaskVO.builder()
                .taskId(latest.getId())
                .status(status)
                .totalShots(latest.getTotalCount())
                .build();
    }
    @Override
    public String doStoryboardVideoPromptBatch(Long taskId, Long userId, String dispatchToken)
    {
        AidExtractTask cycleTask = extractTaskService.selectAidExtractTaskById(taskId);
        String executionTraceId = cycleTask == null ? null : cycleTask.getBillingTraceId();
        if (StrUtil.isBlank(dispatchToken) || StrUtil.isBlank(executionTraceId)
                || !Objects.equals(dispatchToken, executionTraceId))
        {
            throw new TextTaskExecutionRejectedException();
        }
        try
        {
            return doStoryboardVideoPromptBatchInternal(taskId, userId, executionTraceId);
        }
        catch (RuntimeException e)
        {
            // 运行期早失败兜底退款（快照解析 / 智能体 / 模型 / LLM 前置异常等在结算块之前抛出时，冻结款本会等补偿才退）。
            // refundBilling 幂等：正常路径已结算(billing=SUCCESS)或已退款则 no-op，仅 FROZEN 时真正退回；不阻断异常上抛
            try { extractBillingService.refundBilling(taskId, userId, executionTraceId); }
            catch (Exception refundEx)
            {
                log.error("视频提示词任务运行期早失败退款异常，等待补偿收口: taskId={}, traceId={}",
                        taskId, executionTraceId, refundEx);
            }
            throw e;
        }
    }

    private String doStoryboardVideoPromptBatchInternal(Long taskId, Long userId,
                                                        String executionTraceId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            throw new ServiceException("任务不存在");
        }
        Map<String, Object> input;
        try { input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class); }
        catch (Exception e) { throw new ServiceException("解析失败"); }

        Long projectId = Convert.toLong(input.get("projectId"));
        Long episodeId = Convert.toLong(input.get("episodeId"));
        String direction = String.valueOf(input.getOrDefault("direction", DIRECTION_MULTI));
        boolean imageDir = DIRECTION_IMAGE.equals(direction);
        boolean gridDir = DIRECTION_GRID.equals(direction);
        boolean imageColumn = imageDir || gridDir; // 图生 + 宫格都回填 video_prompt_image
        String defaultAgent = gridDir ? DEFAULT_AGENT_CODE_GRID : (imageDir ? DEFAULT_AGENT_CODE_IMAGE : DEFAULT_AGENT_CODE);
        String agentCode = StrUtil.blankToDefault(String.valueOf(input.getOrDefault("agentCode", defaultAgent)), defaultAgent);
        String modelCode = String.valueOf(input.getOrDefault("modelCode", task.getModelCode()));
        boolean overwriteFlag = Boolean.TRUE.equals(input.get("overwrite"));
        String continuityMode = Convert.toStr(input.get("continuityMode"), null);
        String projectLockToken = StrUtil.trim(String.valueOf(
                input.getOrDefault("projectLockToken", "")));

        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) input.get("storyboardIds");
        List<Long> storyboardIds = CollectionUtil.isEmpty(rawIds) ? new ArrayList<>()
                : rawIds.stream().map(Convert::toLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (CollectionUtil.isEmpty(storyboardIds)) { throw new ServiceException("分镜不能为空"); }

        // 加载智能体提示词模板：复用统一的「文件优先 → 回源 aid_agent → 回写文件」机制，与角色/场景/道具提取一致
        String systemPrompt = helper.loadPromptByName(agentCode);

        // 加载分镜
        List<AidStoryboard> storyboardList = storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getSourceSceneId, AidStoryboard::getUserId,
                                AidStoryboard::getSortOrder,
                                AidStoryboard::getScriptParams, AidStoryboard::getImagePrompt,
                                AidStoryboard::getVideoPrompt, AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getDelFlag)
                        .in(AidStoryboard::getId, storyboardIds)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
        storyboardList = restoreSnapshotOrder(storyboardList, storyboardIds);
        if (CollectionUtil.isEmpty(storyboardList)) { throw new ServiceException("镜头列表为空"); }

        // 加载项目（用于全局风格拼接：风格名称 / 风格内容）
        AidComicProject project = projectService.selectAidComicProjectById(projectId);
        String styleType = project == null ? "" : StrUtil.nullToEmpty(project.getVideoStyleType());
        String styleValue = project == null ? "" : StrUtil.nullToEmpty(project.getVideoStyleValue());

        int total = storyboardList.size();
        int successCount = 0;
        int failCount = 0;
        int skipCount = 0;
        long totalInputChars = 0;
        long totalOutputChars = 0;
        List<Map<String, Object>> successItems = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();
        String firstFailureMessage = null;
        // 本次 LLM 调用的真实 token usage（结算用，从 aid_media_task.billing_snapshot_json 聚合）；空则降级字符估算
        Map<String, Object> realUsage = null;

        sseManager.sendStepProgress(taskId, "storyboard_video_prompt", 10, "init", "初始化视频提示词生成...", 0, total);

        // 继续生成（overwrite=false）：已生成视频提示词的镜头跳过并保留，仅对未生成镜头批量生成（B1：只喂未生成）；
        // 重新生成（overwrite=true）：全部覆盖。
        List<AidStoryboard> targetList = new ArrayList<>();
        for (AidStoryboard sb : storyboardList)
        {
            if (!overwriteFlag && StrUtil.isNotBlank(imageColumn ? sb.getVideoPromptImage() : sb.getVideoPrompt()))
            {
                skipCount++;
                successItems.add(Map.of("storyboardId", sb.getId(), "reason", "已有视频提示词，跳过"));
                continue;
            }
            targetList.add(sb);
        }

        if (CollectionUtil.isNotEmpty(targetList) && !assetExtractService.isTaskCancelled(taskId))
        {
            ContinuityContext continuityContext = continuityAssembler.restore(
                    continuityMode, input.get("continuityContext"), targetList);
            List<String> referenceAssetNames = loadReferenceableAssetNames(projectId, userId);
            List<String> referenceAudioNames = roleAudioReferenceResolver.loadAvailableReferenceNames(
                    projectId, episodeId, userId);
            Map<Long, StoryboardSceneContext> sceneContexts = storyboardSceneContextAssembler.assemble(
                    targetList, projectId, userId);
            // 整段批量：一次调用产出带 shotKey、业务编号、prompt、duration 的 JSON 数组。
            // 入参分叉：
            //   · 宫格方向（grid）：吃「中文镜头组」script_params + 宫格图提示词（image_prompt），走 buildBatchLlmInputGroup(含图)
            //   · 多参方向 + 专业版 multiref：吃「中文镜头组」script_params（pro 不出图，直接据镜头组引用资产做多参），走 buildBatchLlmInputGroup(不含图)
            //   · 图生方向（漫剧版）：额外拼【分镜画面生图提示词】+【角色设定】，走 buildBatchLlmInputImage
            //   · 标准多参方向：18 字段单镜表，走 buildBatchLlmInput
            String userContent;
            // 条目单位随分支给出，必须与所选拼装方法的分段标题一致，避免二次推导与实际入参不符
            String unitLabel;
            if (gridDir)
            {
                userContent = buildBatchLlmInputGroup(styleType, styleValue, targetList, true,
                        referenceAssetNames, referenceAudioNames, sceneContexts);
                unitLabel = UNIT_LABEL_GROUP;
            }
            else if (imageDir)
            {
                userContent = buildBatchLlmInputImage(styleType, styleValue, targetList,
                        projectId, episodeId, userId, referenceAssetNames, referenceAudioNames,
                        sceneContexts);
                unitLabel = UNIT_LABEL_SHOT;
            }
            else if (AGENT_CODE_MULTIREF.equals(agentCode))
            {
                userContent = buildBatchLlmInputGroup(styleType, styleValue, targetList, false,
                        referenceAssetNames, referenceAudioNames, sceneContexts);
                unitLabel = UNIT_LABEL_GROUP;
            }
            else
            {
                userContent = buildBatchLlmInput(styleType, styleValue, targetList,
                        referenceAssetNames, referenceAudioNames, sceneContexts);
                unitLabel = UNIT_LABEL_SHOT;
            }
            userContent = continuityAssembler.appendContext(userContent, continuityContext);
            // 整段批量的输出与镜头按「相关键」绑定，相关键即入参每段标题里的不透明标识。
            // 输出契约在此统一收口追加，四个方向共用，避免新增方向漏加。
            userContent = appendBatchOutputContract(userContent, targetList, unitLabel);
            String finalUserContent = userContent;
            String finalUnitLabel = unitLabel;
            // provider 无真实 usage 时也按最终 messages 兜底，不能漏掉 system 或合并消息标题。
            totalInputChars = helper.estimateLlmInputChars(systemPrompt, userContent, modelCode);

            String llmRaw = null;
            Long llmMediaTaskId = null;
            try
            {
                if (!isCurrentExecutionCycle(taskId, executionTraceId))
                {
                    throw new TextTaskExecutionRejectedException();
                }
                // 取终态媒体响应：据本次实际返回的 mediaTaskId 回读真实 token，
                // 即使命中媒体层幂等复用（不产生新行）也能定位被复用的那条媒体任务，避免漏算
                int outputTokenCap = helper.resolveStructuredOutputTokens(
                        targetList.size(), OUTPUT_TOKENS_PER_STORYBOARD);
                MediaTaskResponse llmResp = batchParentSubmissionGuard.executeManagedTask(
                        taskId, executionTraceId,
                        () -> helper.callLlmStructuredForResponse(
                                systemPrompt, finalUserContent, modelCode, taskId, userId, null, BIZ_TASK_TYPE,
                                // 整批是一个原子业务交付槽位。续生会把 storyboardIds 收缩为 remaining，
                                // 因此 stable slot 禁止携带本轮 count/index；实际目标集合由 messages SHA 区分。
                                "stage=video_prompt,direction=" + direction + ",item=batch",
                                raw -> isReplayVideoOutputValid(raw, targetList, finalUnitLabel,
                                        direction, agentCode, styleType, styleValue), executionTraceId, outputTokenCap,
                                helper.businessFunctionForAgent(agentCode)),
                        TextTaskExecutionRejectedException::new);
                if (llmResp != null)
                {
                    llmRaw = llmResp.getTextContent();
                    llmMediaTaskId = llmResp.getTaskId();
                }
            }
            catch (TextTaskExecutionRejectedException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                log.error("视频提示词批量调用失败: taskId={}, err={}", taskId, e.getMessage(), e);
                firstFailureMessage = StrUtil.sub(
                        StrUtil.blankToDefault(e.getMessage(), "模型服务异常"), 0, 500);
            }
            totalOutputChars = StrUtil.length(StrUtil.nullToEmpty(llmRaw));

            // 按本次实际媒体任务 id 回读 provider 真实 token（结算按实际 token，charsToTokens 仅作降级兜底）
            realUsage = aggregateActualTokenUsage(llmMediaTaskId);

            // 解析 JSON 数组后按分镜身份键做完整双射校验；任何契约违约都整批拒绝，禁止部分写回。
            String providerRejectionMessage = providerRejectionMessage(llmRaw);
            List<JsonNode> elems = parseLlmOutputArray(llmRaw);
            StoryboardPromptBatchAligner.AlignmentResult alignment =
                    alignLlmElementsToShots(elems, targetList, unitLabel);
            List<JsonNode> aligned = alignment.valid() ? alignment.elements()
                    : new ArrayList<>(java.util.Collections.nCopies(targetList.size(), null));
            if (!alignment.valid())
            {
                log.error("视频提示词批量身份对齐失败，整批不落库: taskId={}, reason={}, expected={}, actual={}, outputLen={}",
                        taskId, alignment.reason(), targetList.size(), elems.size(), StrUtil.length(llmRaw));
                if (StrUtil.isBlank(firstFailureMessage))
                {
                    firstFailureMessage = StrUtil.blankToDefault(providerRejectionMessage, FAILURE_BATCH_CONTRACT);
                }
            }
            List<VideoPromptWrite> promptWrites = new ArrayList<>();
            for (int i = 0; i < targetList.size(); i++)
            {
                AidStoryboard sb = targetList.get(i);
                JsonNode elem = aligned.get(i);
                String prompt = Objects.isNull(elem) ? "" : elem.path(FIELD_PROMPT).asText("");
                prompt = normalizeMultirefGlobalStyle(prompt, direction, agentCode, styleType, styleValue);
                if (StrUtil.isBlank(prompt) || !isVideoPromptFormatValid(prompt, direction, agentCode))
                {
                    failCount++;
                    if (StrUtil.isBlank(firstFailureMessage))
                    {
                        firstFailureMessage = FAILURE_OUTPUT_FORMAT;
                    }
                    failedItems.add(Map.of("storyboardId", sb.getId(), "errorMessage",
                            StrUtil.blankToDefault(providerRejectionMessage, FAILURE_OUTPUT_FORMAT)));
                    continue;
                }
                prompt = sanitizeVideoPromptReferences(prompt, referenceAssetNames, taskId, sb.getId());
                Map<String, Object> shotParams = parseScriptParams(sb.getScriptParams());
                String dialogueText = Convert.toStr(shotParams.get("台词"), "");
                // 服务端校验音频编号与角色绑定；合法输出原样保留，异常占位按实际发言顺序原位修复。
                prompt = roleAudioReferenceResolver.normalizeGeneratedVideoPrompt(
                        prompt, dialogueText, referenceAudioNames);
                // 回填：多参方向写 video_prompt(+raw)；图生 / 宫格方向写 video_prompt_image（宫格复用图生列）
                int suggestDuration = elem.path(FIELD_DURATION).asInt(0);
                // 多参数、专业版、宫格版统一要求视觉导演返回建议秒数；
                // 图生方向暂不强制，缺失时仍由出片链路按用户选择或模型默认时长处理。
                boolean durationRequired = Objects.equals(DIRECTION_MULTI, direction)
                        || Objects.equals(DIRECTION_GRID, direction);
                if (durationRequired && suggestDuration <= 0)
                {
                    failCount++;
                    if (StrUtil.isBlank(firstFailureMessage))
                    {
                        firstFailureMessage = FAILURE_OUTPUT_FORMAT;
                    }
                    failedItems.add(Map.of("storyboardId", sb.getId(), "errorMessage", FAILURE_DURATION_MISSING));
                    continue;
                }
                if (!isCurrentExecutionCycle(taskId, executionTraceId))
                {
                    throw new TextTaskExecutionRejectedException();
                }
                // 保存视觉导演建议的视频时长（秒）到 script_params，留给后续「按建议时长出片」消费。
                // 多参数、专业版、宫格版已在上方强制要求 duration；图生方向返回有效值时同样保存。
                String mergedParams = null;
                if (suggestDuration > 0)
                {
                    mergedParams = mergeVideoDurationIntoScriptParams(
                            sb.getScriptParams(), suggestDuration);
                }
                promptWrites.add(new VideoPromptWrite(sb, prompt, mergedParams, i));
            }

            // 同一条已结算 child 的整批业务交付必须原子落库：崩溃只能发生在
            // 全部提交之前或之后，不能留下部分镜头导致续生的 stable slot 改变。
            if (!promptWrites.isEmpty())
            {
                transactionTemplate.executeWithoutResult(status -> {
                    extractBillingService.assertTextTaskBusinessCommit(
                            taskId, userId, executionTraceId);
                    for (VideoPromptWrite write : promptWrites)
                    {
                        LambdaUpdateWrapper<AidStoryboard> upd = Wrappers.lambdaUpdate();
                        upd.eq(AidStoryboard::getId, write.storyboard().getId());
                        upd.eq(AidStoryboard::getProjectId, projectId);
                        upd.eq(AidStoryboard::getEpisodeId, episodeId);
                        upd.eq(AidStoryboard::getUserId, userId);
                        upd.eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL);
                        if (imageColumn)
                        {
                            upd.set(AidStoryboard::getVideoPromptImage, write.prompt());
                        }
                        else
                        {
                            upd.set(AidStoryboard::getVideoPrompt, write.prompt());
                        }
                        if (StrUtil.isNotBlank(write.mergedScriptParams()))
                        {
                            upd.set(AidStoryboard::getScriptParams, write.mergedScriptParams());
                        }
                        upd.set(AidStoryboard::getUpdateTime, DateUtils.getNowDate());
                        upd.set(AidStoryboard::getUpdateBy, String.valueOf(userId));
                        if (!storyboardService.update(upd))
                        {
                            throw new ServiceException("写入失败");
                        }
                    }
                });
            }
            for (VideoPromptWrite write : promptWrites)
            {
                log.info("视频提示词归位落库: taskId={}, storyboardId={}, 序位={}, shotKey={}, promptLen={}",
                        taskId, write.storyboard().getId(), write.index() + 1,
                        StoryboardPromptBatchAligner.buildShotKey(write.storyboard().getId()),
                        StrUtil.length(write.prompt()));
                successCount++;
                successItems.add(Map.of("storyboardId", write.storyboard().getId(),
                        "promptLength", StrUtil.length(write.prompt())));
            }
            sseManager.sendStepProgress(taskId, "generating", 98, "batch",
                    "批量生成完成", skipCount + successCount + failCount, total);
        }

        // 计费结算：首跑与续生统一走 settleBilling（按本次真实 token 多退少补）/ refundBilling（全失败退回）。
        // 续生在 resumeVideoPrompt 已通过 rearmBillingForResume 重置为新一轮 FROZEN（新 traceId/金额/快照），
        // 故此处无需区分首跑 / 续生，彻底统一两条计费路径。
        try
        {
            Map<String, Object> usageData = extractBillingService.aggregateTokenUsage(taskId);
            if (!Objects.equals(Boolean.TRUE, usageData.get("aggregation_complete")))
            {
                log.warn("视频提示词用量尚未收敛，交补偿任务结算: taskId={}", taskId);
            }
            else if (hasBillableUsage(usageData))
            {
                extractBillingService.settleBilling(taskId, userId, usageData, executionTraceId);
                log.info("视频提示词批量结算: taskId={}, success={}, skip={}, fail={}",
                        taskId, successCount, skipCount, failCount);
            }
            else
            {
                extractBillingService.refundBilling(taskId, userId, executionTraceId);
                log.info("视频提示词本轮无模型调用，已退款: taskId={}", taskId);
            }
        }
        catch (Exception e) { log.error("视频提示词计费结算异常（不阻断任务终态）: taskId={}", taskId, e); }

        // 释放项目级锁
        try { projectLockGuard.releaseIfMatch(
                buildLockKey(task.getProjectId(), task.getEpisodeId()), projectLockToken); }
        catch (Exception ignore) { }

        // 全部失败抛异常让 Consumer 标 FAILED
        if (failCount == total && total > 0)
        {
            log.error("视频提示词全部生成失败: taskId={}, error={}", taskId, firstFailureMessage);
            if (FAILURE_IDENTITY_CONFLICT.equals(firstFailureMessage)
                    || FAILURE_SCENE_CONFLICT.equals(firstFailureMessage)
                    || FAILURE_PROVIDER_REJECTED.equals(firstFailureMessage))
            {
                throw TaskErrorPresentation.fromCode(TaskErrorCode.RESULT_FORMAT_INVALID, firstFailureMessage);
            }
            throw TaskErrorPresentation.toServiceException(firstFailureMessage, "视频提示词失败");
        }

        // 返回结果 JSON
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCount", total);
        result.put("successCount", successCount);
        result.put("skipCount", skipCount);
        result.put("failCount", failCount);
        result.put("successItems", successItems);
        result.put("failedItems", failedItems);
        try { return OBJECT_MAPPER.writeValueAsString(result); }
        catch (Exception e) { return "{}"; }
    }
    @Override
    public AssetExtractTaskVO resumeVideoPrompt(Long taskId, Long userId)
    {
        if (Objects.isNull(taskId)) { throw new ServiceException("任务不能为空"); }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || !DEL_FLAG_NORMAL.equals(task.getDelFlag())) { throw new ServiceException("任务不存在"); }
        if (!Objects.equals(userId, task.getUserId())) { throw new ServiceException("任务不存在"); }
        if (!TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(task.getTaskType())) { throw new ServiceException("类型不支持"); }
        if (isActiveTaskStatus(task.getStatus()))
        {
            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(task.getStatus())
                    .totalShots(task.getTotalCount())
                    .build();
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus())) { throw new ServiceException("状态不支持"); }
        VideoPromptResumePlan preparedResume = prepareVideoPromptResumePlan(taskId, userId);
        String originalStatus = task.getStatus();
        // 旧计费周期必须已完整结算（billing_status=SUCCESS）才允许续生：
        // 若仍是 PARTIAL_SUCCESS（首跑欠扣待追补），重置会覆盖旧周期追补快照导致欠款无法追扣，
        // 待 retryPartialExtraCharges 把旧周期追平为 SUCCESS 后再续生。
        boolean refundedTerminal = (TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                || TASK_STATUS_CANCELLED.equals(task.getStatus()) || TASK_STATUS_FAILED.equals(task.getStatus()))
                && BILLING_STATUS_REFUNDED.equals(task.getBillingStatus());
        if (!BILLING_STATUS_SUCCESS.equals(task.getBillingStatus()) && !refundedTerminal)
        {
            log.info("视频提示词续生拒绝：旧计费周期未完整结算, taskId={}, billingStatus={}", taskId, task.getBillingStatus());
            throw new ServiceException("结算未完成");
        }
        if (Objects.nonNull(task.getCreateTime()))
        {
            long ageHours = (System.currentTimeMillis() - task.getCreateTime().getTime()) / 3600_000L;
            if (ageHours > RESUME_WINDOW_HOURS) { throw new ServiceException("任务已过期"); }
        }

        // 续生防重锁
        String resumeLockKey = "storyboard:video_prompt:resume:lock:" + taskId;
        String resumeLockToken = IdUtil.fastSimpleUUID();
        Boolean resumeLocked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(resumeLockKey, resumeLockToken, 30L * 60L, TimeUnit.SECONDS);
        if (resumeLocked == null || !resumeLocked) { throw new ServiceException("任务处理中"); }

        try
        {
            // 项目级锁（续生场景同样走僵尸锁自愈，避免项目锁泄漏卡死续生入口）
            String projectLockKey = buildLockKey(task.getProjectId(), task.getEpisodeId());
            ProjectGenerateLockGuard.AcquireResult resumeProjectLockResult = projectLockGuard.tryAcquireWithStaleClean(
                    projectLockKey, 30L * 60L, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH,
                    task.getProjectId(), task.getEpisodeId());
            if (!resumeProjectLockResult.isAcquired()) { throw new ServiceException("项目处理中"); }

            // 解析 inputSnapshot
            Map<String, Object> input;
            try { input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class); }
            catch (Exception e) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("解析失败"); }

            String direction = String.valueOf(input.getOrDefault("direction", DIRECTION_MULTI));
            boolean imageDir = DIRECTION_IMAGE.equals(direction);
            boolean gridDir = DIRECTION_GRID.equals(direction);
            boolean imageColumn = imageDir || gridDir; // 图生 + 宫格都用 video_prompt_image 列
            String resumeAgentCode = String.valueOf(input.getOrDefault("agentCode", gridDir ? DEFAULT_AGENT_CODE_GRID : (imageDir ? DEFAULT_AGENT_CODE_IMAGE : DEFAULT_AGENT_CODE)));
            boolean groupStructure = gridDir || AGENT_CODE_MULTIREF.equals(resumeAgentCode);
            String modelCode = StrUtil.blankToDefault(String.valueOf(input.getOrDefault("modelCode", task.getModelCode())), task.getModelCode());
            String continuityMode = Convert.toStr(input.get("continuityMode"), null);
            Long previousStoryboardId = Convert.toLong(input.get("previousStoryboardId"));
            boolean continuitySingleSelection = Boolean.TRUE.equals(input.get("continuitySingleSelection"));
            @SuppressWarnings("unchecked")
            List<Object> rawIds = (List<Object>) input.get("storyboardIds");
            List<Long> originalIds = CollectionUtil.isEmpty(rawIds) ? new ArrayList<>()
                    : rawIds.stream().map(Convert::toLong).filter(Objects::nonNull).distinct().collect(Collectors.toList());
            if (CollectionUtil.isEmpty(originalIds)) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("分镜不能为空"); }

            // 加载剩余未生成的镜头
            List<AidStoryboard> loadedStoryboards = storyboardService.list(
                    Wrappers.<AidStoryboard>lambdaQuery()
                            .select(AidStoryboard::getId, AidStoryboard::getScriptParams,
                                    AidStoryboard::getImagePrompt, AidStoryboard::getVideoPrompt,
                                    AidStoryboard::getVideoPromptImage, AidStoryboard::getDelFlag)
                            .in(AidStoryboard::getId, originalIds)
                            .eq(AidStoryboard::getProjectId, task.getProjectId())
                            .eq(AidStoryboard::getEpisodeId, task.getEpisodeId())
                            .eq(AidStoryboard::getUserId, userId)
                            .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL));
            List<AidStoryboard> remaining = restoreSnapshotOrder(loadedStoryboards, originalIds).stream()
                    .filter(s -> StrUtil.isBlank(imageColumn ? s.getVideoPromptImage() : s.getVideoPrompt()))
                    .collect(Collectors.toList());
            if (CollectionUtil.isEmpty(remaining))
            {
                try
                {
                    AssetExtractTaskVO chainRetry = retryChainAfterPromptsReady(task);
                    if (Objects.nonNull(chainRetry)) { return chainRetry; }
                    throw new ServiceException("无可续生镜头");
                }
                finally
                {
                    projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                }
            }

            // 缺字段校验（与首跑一致，必须在冻结之前）：避免用户在部分失败后编辑过分镜 / 旧数据不完整时先冻结再失败
            try
            {
                for (AidStoryboard sb : remaining) { validateShotForVideoPrompt(sb, imageColumn, groupStructure); }
            }
            catch (ServiceException ve)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw ve;
            }

            ContinuityContext continuityContext;
            try
            {
                continuityContext = continuityAssembler.resolve(
                        task.getProjectId(), task.getEpisodeId(), userId, remaining,
                        continuityMode, previousStoryboardId,
                        continuitySingleSelection, imageColumn);
            }
            catch (ServiceException ve)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw ve;
            }

            // 预冻结：整批一次按估算 token 计算（charsToTokens 仅预冻结）+ 保存可结算快照；
            // 续生与首跑走同一计费链路：rearmBillingForResume 把已完整结算（仅 SUCCESS）任务重置为新一轮 FROZEN
            // → 消费端 settleBilling 按真实 token 多退少补。
            AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(modelCode, helper.businessFunctionForAgent(resumeAgentCode), null);
            if (Objects.isNull(modelConfig)) { projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken()); throw new ServiceException("模型不存在"); }
            AidComicProject project = projectService.selectAidComicProjectById(task.getProjectId());
            String styleType = Objects.isNull(project) ? "" : StrUtil.nullToEmpty(project.getVideoStyleType());
            String styleValue = Objects.isNull(project) ? "" : StrUtil.nullToEmpty(project.getVideoStyleValue());
            int batchInputChars = estimateBatchInputChars(direction, resumeAgentCode, modelCode,
                    styleType, styleValue, remaining, task.getProjectId(), task.getEpisodeId(), userId,
                    continuityContext);
            int batchOutputChars = ESTIMATED_OUTPUT_CHARS * remaining.size();
            Map<String, Object> billingParams = new HashMap<>();
            billingParams.put("inputChars", batchInputChars);
            billingParams.put("inputTokens", BillingConstants.charsToTokens(batchInputChars));
            billingParams.put("outputTokens", BillingConstants.charsToTokens(batchOutputChars));
            billingParams.put("totalChars", batchInputChars + batchOutputChars);
            helper.applyLlmBillingLimits(billingParams, 1);
            BillingInput billingInput = new BillingInput("TEXT", billingParams);
            BillingCalcResult calc = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
            BigDecimal totalRetryFrozen = (calc != null && calc.isMatched() && calc.getAmount() != null)
                    ? calc.getAmount() : BigDecimal.ZERO;
            String resumeSnapshotJson = null;
            if (calc != null && calc.getSnapshot() != null)
            {
                attachUsageWatermark(calc.getSnapshot(),
                        extractBillingService.findLatestBillingMediaTaskId(taskId));
                try { resumeSnapshotJson = JSONUtil.toJsonStr(calc.getSnapshot()); }
                catch (Exception ignore) { /* 快照序列化失败则退化为按预冻结额结算 */ }
            }

            // 续生改写 inputSnapshot：仅处理剩余镜头 + overwrite=true，保证消费端精确处理 remaining，
            // 与本次冻结 / 真实 token 口径一致（不误把已生成镜头重算重扣）
            List<Long> remainingIds = remaining.stream().map(AidStoryboard::getId).collect(Collectors.toList());
            Map<String, Object> resumeInput = new LinkedHashMap<>(input);
            resumeInput.put("storyboardIds", remainingIds);
            resumeInput.put("overwrite", Boolean.TRUE);
            resumeInput.put("modelCode", modelCode);
            resumeInput.put("projectLockToken", resumeProjectLockResult.getToken());
            if (continuityContext.enabled())
            {
                resumeInput.put("continuityContext", continuityContext);
            }
            String resumeInputJson;
            try
            {
                resumeInputJson = OBJECT_MAPPER.writeValueAsString(resumeInput);
                assertContinuitySnapshotCapacity(resumeInputJson, continuityContext.enabled(),
                        task.getProjectId(), task.getEpisodeId());
            }
            catch (ServiceException e)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw e;
            }
            catch (Exception e)
            {
                log.error("视频提示词续生快照序列化失败: taskId={}", taskId, e);
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                throw new ServiceException("续生失败，请重试");
            }

            BigDecimal retryFrozenAmount = preparedResume.billingPlan().preHoldAmount();
            String retryBillingSnapshotJson = preparedResume.billingSnapshotJson();
            String dispatchMode = dualModeTaskDispatcher.resolveDispatchMode();
            try
            {
                taskQueueService.executeWithTaskDispatchLock(taskId, () -> {
                    taskQueueService.clearCancelRequested(taskId, task.getBillingTraceId());
                    assetExtractService.clearCancelFlag(taskId, task.getBillingTraceId());

                    ResumeBillingContext billingContext = null;
                    try
                    {
                        billingContext = extractBillingService.rearmBillingForResume(taskId, userId,
                                retryFrozenAmount, retryBillingSnapshotJson, originalStatus,
                                new ResumeTaskMutation(true, null, true, resumeInputJson,
                                        true, remaining.size()), dispatchMode);
                        taskQueueService.assertTaskDispatchLockHeld(taskId);
                        billingContext = extractBillingService.markResumeBillingDispatchIntent(
                                taskId, billingContext);
                        taskQueueService.assertTaskDispatchLockHeld(taskId);
                        sendMqMessageNow(taskId, task.getProjectId(), task.getEpisodeId(),
                                userId, modelCode, dispatchMode, billingContext.resumeTraceId());
                        return Boolean.TRUE;
                    }
                    catch (RuntimeException submitEx)
                    {
                        if (billingContext != null)
                        {
                            rollbackVideoPromptResumeSubmission(
                                    taskId, userId, billingContext);
                        }
                        throw submitEx;
                    }
                });
            }
            catch (RuntimeException submitEx)
            {
                projectLockGuard.releaseIfMatch(projectLockKey, resumeProjectLockResult.getToken());
                log.error("视频提示词续生提交失败: taskId={}", taskId, submitEx);
                throw (submitEx instanceof ServiceException)
                        ? submitEx : new ServiceException("续生失败，请重试");
            }

            return AssetExtractTaskVO.builder().taskId(taskId).status(TASK_STATUS_PENDING).totalShots(remaining.size()).build();
        }
        catch (RuntimeException e)
        {
            if (e instanceof ServiceException) { throw e; }
            throw new ServiceException("续生失败，请重试");
        }
        finally { projectLockGuard.releaseIfMatch(resumeLockKey, resumeLockToken); }
    }

    private void attachUsageWatermark(BillingSnapshot snapshot, long usageStartMediaTaskId)
    {
        if (snapshot == null)
        {
            return;
        }
        Map<String, Object> requestParams = snapshot.getRequestParams() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(snapshot.getRequestParams());
        requestParams.put("usageStartMediaTaskId", Math.max(0L, usageStartMediaTaskId));
        snapshot.setRequestParams(requestParams);
    }

    private boolean hasBillableUsage(Map<String, Object> usageData)
    {
        return usageCount(usageData.get("billable_call_count")) > 0
                || usageCount(usageData.get("successful_call_count")) > 0
                || usageCount(usageData.get("usage_call_count")) > 0;
    }

    private int usageCount(Object value)
    {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private boolean isCurrentExecutionCycle(Long taskId, String executionTraceId)
    {
        if (Objects.isNull(taskId) || StrUtil.isBlank(executionTraceId))
        {
            return false;
        }
        return Objects.nonNull(extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId)
                        .eq(AidExtractTask::getId, taskId)
                        .in(AidExtractTask::getStatus,
                                TASK_STATUS_PROCESSING, TASK_STATUS_FINALIZING)
                        .eq(AidExtractTask::getBillingStatus, BILLING_STATUS_FROZEN)
                        .eq(AidExtractTask::getBillingTraceId, executionTraceId)
                        .last("LIMIT 1"), false));
    }

    private boolean isActiveTaskStatus(String status)
    {
        return TASK_STATUS_PENDING.equals(status)
                || TASK_STATUS_QUEUED.equals(status)
                || TASK_STATUS_PROCESSING.equals(status);
    }

    private AssetExtractTaskVO retryChainAfterPromptsReady(AidExtractTask task)
    {
        if (!hasChainNext(task.getInputSnapshot())
                || (hasChainChildTaskId(task.getResultData()) && !isChainFailedResult(task.getResultData())))
        {
            return null;
        }
        ChainTriggerResult chain = storyboardStepChainService.onPromptBatchTerminal(
                task.getId(), TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH, TASK_STATUS_SUCCEEDED);
        if (Objects.isNull(chain) || chain.isChainFailed() || CollectionUtil.isEmpty(chain.getChildTaskIds()))
        {
            ChainTriggerResult failed = Objects.isNull(chain)
                    ? ChainTriggerResult.failed("提交失败") : chain;
            String resultJson = appendChainFailure(task.getResultData(), failed);
            List<Long> chainChildTaskIds = chainChildTaskIdsFromResult(resultJson);
            updatePromptChainTerminal(task.getId(), task.getBillingTraceId(),
                    TASK_STATUS_PARTIAL_FAILED, resultJson, chainMessage(failed, "提交失败"));
            sendPartialFailedSafely(task.getId(), resultJson, chainMessage(failed, "提交失败"),
                    chainChildTaskIds, failed.getChildTaskType());
            throw new ServiceException(chainMessage(failed, "提交失败"));
        }

        String resultJson = appendChainSuccess(task.getResultData(), chain);
        List<Long> chainChildTaskIds = chainChildTaskIdsFromResult(resultJson);
        String nextStatus = hasPromptFailedItems(resultJson) ? TASK_STATUS_PARTIAL_FAILED : TASK_STATUS_SUCCEEDED;
        updatePromptChainTerminal(task.getId(), task.getBillingTraceId(),
                nextStatus, resultJson, null);
        if (TASK_STATUS_SUCCEEDED.equals(nextStatus))
        {
            sendCompleteSafely(task.getId(), resultJson, chainChildTaskIds, chain.getChildTaskType());
        }
        else
        {
            sendPartialFailedSafely(task.getId(), resultJson, "部分镜头生成失败，可续生",
                    chainChildTaskIds, chain.getChildTaskType());
        }
        log.info("视频提示词续生重试链式出片成功: taskId={}, childTaskId={}, childTaskType={}",
                task.getId(), chain.getChildTaskId(), chain.getChildTaskType());
        return AssetExtractTaskVO.builder()
                .taskId(task.getId())
                .status(nextStatus)
                .totalShots(task.getTotalCount())
                .resultData(resultJson)
                .chainChildTaskId(firstChainChildTaskId(chainChildTaskIds))
                .chainChildTaskIds(chainChildTaskIds)
                .chainChildTaskType(chain.getChildTaskType())
                .build();
    }

    private boolean hasChainNext(String inputSnapshot)
    {
        if (StrUtil.isBlank(inputSnapshot)) { return false; }
        try
        {
            JsonNode chainNode = OBJECT_MAPPER.readTree(inputSnapshot).path("chainNext");
            return !chainNode.isMissingNode() && !chainNode.isNull() && !chainNode.isEmpty();
        }
        catch (Exception e)
        {
            log.warn("视频提示词续生解析chainNext失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean hasChainChildTaskId(String resultData)
    {
        Map<String, Object> result = parseResultMap(resultData);
        Object listVal = result.get("chainChildTaskIds");
        if (listVal instanceof List<?> list)
        {
            return CollectionUtil.isNotEmpty(list);
        }
        Object val = result.get("chainChildTaskId");
        if (val instanceof Number n)
        {
            return n.longValue() > 0L;
        }
        if (val != null)
        {
            try { return Long.parseLong(String.valueOf(val)) > 0L; }
            catch (Exception ignore) { return false; }
        }
        return false;
    }

    private boolean isChainFailedResult(String resultData)
    {
        Map<String, Object> result = parseResultMap(resultData);
        Object val = result.get("chainFailed");
        return Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val));
    }

    private boolean hasPromptFailedItems(String resultData)
    {
        Map<String, Object> result = parseResultMap(resultData);
        int total = numberValue(result.get("totalCount"));
        int success = numberValue(result.get("successCount"));
        int skip = numberValue(result.get("skipCount"));
        return total > 0 && success + skip < total;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResultMap(String resultData)
    {
        if (StrUtil.isBlank(resultData)) { return new LinkedHashMap<>(); }
        try
        {
            return OBJECT_MAPPER.readValue(resultData, Map.class);
        }
        catch (Exception e)
        {
            log.warn("视频提示词续生解析resultData失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private int numberValue(Object value)
    {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private String appendChainSuccess(String resultData, ChainTriggerResult chain)
    {
        Map<String, Object> result = parseResultMap(resultData);
        List<Long> chainChildTaskIds = mergeChainChildTaskIds(result, chain);
        result.remove("chainFailed");
        result.remove("chainMessage");
        if (CollectionUtil.isNotEmpty(chainChildTaskIds))
        {
            result.put("chainChildTaskId", chainChildTaskIds.get(0));
            result.put("chainChildTaskIds", chainChildTaskIds);
        }
        result.put("chainChildTaskType", chain.getChildTaskType());
        try { return OBJECT_MAPPER.writeValueAsString(result); }
        catch (Exception e) { return resultData; }
    }

    private String appendChainFailure(String resultData, ChainTriggerResult chain)
    {
        Map<String, Object> result = parseResultMap(resultData);
        result.put("chainFailed", true);
        result.put("chainMessage", chainMessage(chain, "提交失败"));
        if (StrUtil.isNotBlank(chain.getChildTaskType()))
        {
            result.put("chainChildTaskType", chain.getChildTaskType());
        }
        List<Long> chainChildTaskIds = mergeChainChildTaskIds(result, chain);
        if (CollectionUtil.isNotEmpty(chainChildTaskIds))
        {
            result.put("chainChildTaskId", chainChildTaskIds.get(0));
            result.put("chainChildTaskIds", chainChildTaskIds);
        }
        try { return OBJECT_MAPPER.writeValueAsString(result); }
        catch (Exception e) { return resultData; }
    }

    private List<Long> mergeChainChildTaskIds(Map<String, Object> result, ChainTriggerResult chain)
    {
        List<Long> merged = new ArrayList<>();
        addChainChildTaskIds(merged, result.get("chainChildTaskIds"));
        addChainChildTaskId(merged, result.get("chainChildTaskId"));
        if (Objects.nonNull(chain))
        {
            addChainChildTaskIds(merged, chain.getChildTaskIds());
            addChainChildTaskId(merged, chain.getChildTaskId());
        }
        return merged;
    }

    private List<Long> chainChildTaskIdsFromResult(String resultData)
    {
        return mergeChainChildTaskIds(parseResultMap(resultData), null);
    }

    private Long firstChainChildTaskId(List<Long> taskIds)
    {
        return CollectionUtil.isEmpty(taskIds) ? null : taskIds.get(0);
    }

    private void addChainChildTaskIds(List<Long> target, Object value)
    {
        if (value instanceof List<?> values)
        {
            for (Object item : values)
            {
                addChainChildTaskId(target, item);
            }
        }
    }

    private void addChainChildTaskId(List<Long> target, Object value)
    {
        if (Objects.isNull(value))
        {
            return;
        }
        Long taskId = null;
        if (value instanceof Number n)
        {
            taskId = n.longValue();
        }
        else if (StrUtil.isNotBlank(String.valueOf(value)))
        {
            try { taskId = Long.parseLong(String.valueOf(value)); }
            catch (Exception ignore) { return; }
        }
        if (Objects.nonNull(taskId) && taskId > 0L && !target.contains(taskId))
        {
            target.add(taskId);
        }
    }

    private String chainMessage(ChainTriggerResult chain, String fallback)
    {
        return Objects.nonNull(chain) ? StrUtil.blankToDefault(chain.getMessage(), fallback) : fallback;
    }

    private void updatePromptChainTerminal(Long taskId, String expectedTraceId,
                                           String status, String resultJson, String errorMessage)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getBillingTraceId, expectedTraceId);
        update.set(AidExtractTask::getStatus, status);
        update.set(AidExtractTask::getResultData, resultJson);
        update.set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(errorMessage));
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        if (!extractTaskService.update(update))
        {
            throw new TextTaskExecutionRejectedException();
        }
    }

    private void sendCompleteSafely(Long taskId, String resultJson, List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try { sseManager.sendComplete(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), chainChildTaskIds, chainChildTaskType); }
        catch (Exception e) { sseManager.sendComplete(taskId, resultJson, chainChildTaskIds, chainChildTaskType); }
    }

    private void sendPartialFailedSafely(Long taskId, String resultJson, String message,
                                         List<Long> chainChildTaskIds, String chainChildTaskType)
    {
        try { sseManager.sendPartialFailed(taskId, OBJECT_MAPPER.readValue(resultJson, Map.class), message, chainChildTaskIds, chainChildTaskType); }
        catch (Exception e) { sseManager.sendPartialFailed(taskId, resultJson, message, chainChildTaskIds, chainChildTaskType); }
    }

    private void rollbackVideoPromptResumeSubmission(Long taskId, Long userId,
                                                      ResumeBillingContext billingContext)
    {
        if (!extractBillingService.requestResumeBillingRollback(taskId, userId, billingContext))
        {
            log.error("视频提示词续生未能抢回任务，跳过退款: taskId={}", taskId);
            return;
        }
        taskQueueService.releaseSlots(taskId, billingContext.resumeTraceId());
        try
        {
            extractBillingService.rollbackResumeBilling(taskId, userId, billingContext);
        }
        catch (Exception rollbackEx)
        {
            log.error("视频提示词续生计费回滚失败: taskId={}", taskId, rollbackEx);
        }
        taskQueueService.clearCancelRequested(taskId, billingContext.resumeTraceId());
        assetExtractService.clearCancelFlag(taskId, billingContext.resumeTraceId());
    }

    private String buildLockKey(Long projectId, Long episodeId)
    {
        return "storyboard:video_prompt:lock:" + projectId + ":" + episodeId;
    }

    /**
     * 校验模型是否落在指定功能编码（func_code）的可选模型池内。
     * 与 {@code StoryboardImageGenerationServiceImpl.assertModelInBizCategoryPool} 口径一致：
     * 池未配置 / 模型不在池内一律拒绝，保证运营后台 model_ids 配置具备约束力。
     */
    private void assertModelInFuncPool(String modelCode, Long modelId, String funcCode)
    {
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("图生视频提示词模型池未配置或全部失效: funcCode={}, modelCode={}", funcCode, modelCode);
            throw new ServiceException("模型未配置");
        }
        boolean inPool = pool.stream().anyMatch(m -> Objects.equals(modelId, m.getId()));
        if (!inPool)
        {
            log.error("图生视频提示词模型不在业务池内: funcCode={}, modelCode={}, poolSize={}", funcCode, modelCode, pool.size());
            throw new ServiceException("模型不可用");
        }
    }

    /**
     * 取功能池内第一个可用模型编码（图生方向"只配池就能跑"的默认兜底）：池为空抛"功能可选模型未配置"。
     */
    private String firstModelInPool(String funcCode)
    {
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            log.error("图生视频提示词模型池未配置或全部失效（取默认模型）: funcCode={}", funcCode);
            throw new ServiceException("模型未配置");
        }
        return pool.get(0).getModelCode();
    }

    /**
     * 按本次调用返回的媒体任务 id 回读 provider 真实 token usage（input/output）。
     *
     * @param mediaTaskId 本次 LLM 调用实际返回的媒体任务 id（{@code MediaTaskResponse.taskId}）
     * @return {@code {input_tokens, output_tokens}}；无真实 token 时返回空 Map
     */
    private Map<String, Object> aggregateActualTokenUsage(Long mediaTaskId)
    {
        if (Objects.isNull(mediaTaskId))
        {
            return new HashMap<>();
        }
        AidMediaTask mt = aidMediaTaskMapper.selectOne(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getBillingSnapshotJson)
                        .eq(AidMediaTask::getId, mediaTaskId)
                        .last("limit 1"));
        Map<String, Object> usage = new HashMap<>();
        if (Objects.isNull(mt) || StrUtil.isBlank(mt.getBillingSnapshotJson()))
        {
            return usage;
        }
        try
        {
            BillingSnapshot snapshot = JSONUtil.toBean(mt.getBillingSnapshotJson(), BillingSnapshot.class);
            int inputTokens = snapshot.getActualInputTokens() == null ? 0 : snapshot.getActualInputTokens();
            int outputTokens = snapshot.getActualOutputTokens() == null ? 0 : snapshot.getActualOutputTokens();
            if (inputTokens > 0 || outputTokens > 0)
            {
                usage.put("input_tokens", inputTokens);
                usage.put("output_tokens", outputTokens);
            }
        }
        catch (Exception e)
        {
            log.warn("视频提示词回读真实token解析快照失败: mediaTaskId={}", mediaTaskId, e);
        }
        return usage;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseScriptParams(String scriptParams)
    {
        if (StrUtil.isBlank(scriptParams)) { return new LinkedHashMap<>(); }
        try { return OBJECT_MAPPER.readValue(scriptParams, Map.class); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    /**
     * 把视觉导演建议的视频时长（秒）合并进 {@code script_params}（key=「视频时长建议秒」），留给后续「按建议时长出片」消费。
     * 读-改-写：保留原有全部 key，仅追加/覆盖该时长 key。解析或序列化失败返回 {@code null}（调用方降级为不写，
     * 不影响 video_prompt 回填本身）。
     */
    private String mergeVideoDurationIntoScriptParams(String scriptParamsJson, int durationSeconds)
    {
        try
        {
            Map<String, Object> params = parseScriptParams(scriptParamsJson);
            params.put("视频时长建议秒", durationSeconds);
            return OBJECT_MAPPER.writeValueAsString(params);
        }
        catch (Exception e)
        {
            log.warn("合并视频时长建议到 script_params 失败(降级不写): err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 加载目标分镜并严格做归属校验。
     */
    private List<AidStoryboard> loadTargetStoryboards(Long projectId, Long episodeId, Long userId, List<Long> ids)
    {
        if (CollectionUtil.isEmpty(ids)) { return new ArrayList<>(); }
        List<AidStoryboard> matched = storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getSourceSceneId, AidStoryboard::getUserId,
                                AidStoryboard::getSortOrder,
                                AidStoryboard::getScriptParams, AidStoryboard::getImagePrompt,
                                AidStoryboard::getVideoPrompt, AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getDelFlag)
                        .in(AidStoryboard::getId, ids)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder)
                        .orderByAsc(AidStoryboard::getId));
        if (matched.size() != ids.size())
        {
            throw new ServiceException("镜头存在不可用项");
        }
        return matched;
    }

    /**
     * 加载本剧集全部分镜（未指定 storyboardIds 时用），严格归属校验 + 按 sort_order。
     */
    private List<AidStoryboard> loadEpisodeStoryboards(Long projectId, Long episodeId, Long userId)
    {
        return storyboardService.list(
                Wrappers.<AidStoryboard>lambdaQuery()
                        .select(AidStoryboard::getId, AidStoryboard::getProjectId, AidStoryboard::getEpisodeId,
                                AidStoryboard::getSourceSceneId, AidStoryboard::getUserId,
                                AidStoryboard::getSortOrder,
                                AidStoryboard::getScriptParams, AidStoryboard::getImagePrompt,
                                AidStoryboard::getVideoPrompt, AidStoryboard::getVideoPromptImage,
                                AidStoryboard::getDelFlag)
                        .eq(AidStoryboard::getProjectId, projectId)
                        .eq(AidStoryboard::getEpisodeId, episodeId)
                        .eq(AidStoryboard::getUserId, userId)
                        .eq(AidStoryboard::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidStoryboard::getSortOrder)
                        .orderByAsc(AidStoryboard::getId));
    }

    /**
     * 按任务快照恢复分镜顺序，并拒绝执行期间发生的集合漂移。
     *
     * @param loaded      数据库查询结果
     * @param snapshotIds 任务创建时冻结的分镜主键顺序
     * @return 与快照顺序完全一致的分镜列表
     */
    private List<AidStoryboard> restoreSnapshotOrder(List<AidStoryboard> loaded, List<Long> snapshotIds)
    {
        if (CollectionUtil.isEmpty(loaded) || CollectionUtil.isEmpty(snapshotIds)
                || loaded.size() != snapshotIds.size())
        {
            log.error("视频提示词任务分镜集合已变化: expected={}, actual={}",
                    CollectionUtil.isEmpty(snapshotIds) ? 0 : snapshotIds.size(),
                    CollectionUtil.isEmpty(loaded) ? 0 : loaded.size());
            throw new ServiceException("分镜已变更");
        }
        Map<Long, AidStoryboard> byId = loaded.stream().collect(Collectors.toMap(
                AidStoryboard::getId, storyboard -> storyboard, (first, duplicate) -> first, LinkedHashMap::new));
        List<AidStoryboard> ordered = new ArrayList<>(snapshotIds.size());
        for (Long storyboardId : snapshotIds)
        {
            AidStoryboard storyboard = byId.get(storyboardId);
            if (Objects.isNull(storyboard))
            {
                log.error("视频提示词任务分镜已变化: storyboardId={}", storyboardId);
                throw new ServiceException("分镜已变更");
            }
            ordered.add(storyboard);
        }
        return ordered;
    }

    /** 校验启用连续性后的任务快照不会超过数据库 TEXT 字段容量。 */
    private void assertContinuitySnapshotCapacity(String inputSnapshot, boolean continuityEnabled,
                                                  Long projectId, Long episodeId)
    {
        if (!continuityEnabled)
        {
            return;
        }
        int snapshotBytes = StrUtil.nullToEmpty(inputSnapshot).getBytes(StandardCharsets.UTF_8).length;
        if (snapshotBytes > INPUT_SNAPSHOT_MAX_BYTES)
        {
            log.error("视频提示词连续性快照过长: projectId={}, episodeId={}, bytes={}, maxBytes={}",
                    projectId, episodeId, snapshotBytes, INPUT_SNAPSHOT_MAX_BYTES);
            throw new ServiceException("连续上下文过长");
        }
    }

    /**
     * 缺字段校验（在创建任务 / 计费冻结之前调用）：视觉导演入参取自 {@code script_params}。
     *
     * @param needImagePrompt 是否要求已有分镜生图提示词（image_prompt）：图生方向(i2v) + 宫格方向(auto_grid) 为 true
     * @param groupStructure  是否「中文镜头组」结构（pro 多参 multiref + auto_grid 宫格）：
     *                        true → 校验镜头组 key {@code 画面说明}；false → 漫剧单镜 key {@code 画面描述}
     */
    private void validateShotForVideoPrompt(AidStoryboard sb, boolean needImagePrompt, boolean groupStructure)
    {
        Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
        if (shot.isEmpty())
        {
            log.info("视频提示词拒绝：分镜脚本未生成, storyboardId={}", sb.getId());
            throw new ServiceException(storyboardLabel(sb) + "缺分镜");
        }
        // 镜头组结构（pro/auto_grid）落库 key 是「画面说明」，漫剧单镜结构是「画面描述」
        String pictureKey = groupStructure ? "画面说明" : "画面描述";
        if (StrUtil.isBlank(Convert.toStr(shot.get(pictureKey))))
        {
            log.info("视频提示词拒绝：缺{}, storyboardId={}", pictureKey, sb.getId());
            throw new ServiceException(storyboardLabel(sb) + "缺画面描述");
        }
        // 引用信息允许为空：用户删除资产后，该镜应按画面文字继续生成，不能被历史引用永久锁死。
        // 图生方向（漫剧版）/ 宫格方向均强依赖分镜生图提示词（漫剧版提取形态名身份绑定；宫格版对齐参考分镜图），未生成则拒绝
        if (needImagePrompt && StrUtil.isBlank(sb.getImagePrompt()))
        {
            log.info("视频提示词拒绝：缺分镜生图提示词, storyboardId={}", sb.getId());
            throw new ServiceException(storyboardLabel(sb) + "图脚本为空");
        }
    }

    /**
     * 图生视频 / 宫格视频必须先具备分镜图脚本；一次性列出缺失镜头，方便用户定位。
     */
    private void assertImagePromptReadyForVideoPrompt(List<AidStoryboard> storyboards)
    {
        List<String> missingLabels = storyboards.stream()
                .filter(storyboard -> StrUtil.isBlank(storyboard.getImagePrompt()))
                .limit(MISSING_STORYBOARD_LABEL_LIMIT + 1L)
                .map(this::storyboardLabel)
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(missingLabels))
        {
            return;
        }
        boolean truncated = missingLabels.size() > MISSING_STORYBOARD_LABEL_LIMIT;
        List<String> shownLabels = truncated
                ? missingLabels.subList(0, MISSING_STORYBOARD_LABEL_LIMIT)
                : missingLabels;
        String message = String.join("、", shownLabels) + (truncated ? "等" : "") + "图脚本为空";
        log.info("视频提示词拒绝：存在未生成分镜图脚本的镜头, message={}", message);
        throw new ServiceException(message);
    }

    /**
     * 构建用户可读的分镜定位文案，优先使用排序镜号，兜底使用分镜主键。
     */
    private String storyboardLabel(AidStoryboard storyboard)
    {
        if (Objects.nonNull(storyboard) && Objects.nonNull(storyboard.getSortOrder()))
        {
            return "第" + storyboard.getSortOrder() + "镜";
        }
        if (Objects.nonNull(storyboard) && Objects.nonNull(storyboard.getId()))
        {
            return "分镜" + storyboard.getId();
        }
        return "当前分镜";
    }

    /** 按执行阶段的真实分支组装 system + user，用于首跑和续生的整批预冻结。 */
    private int estimateBatchInputChars(String direction, String agentCode, String modelCode,
                                        String styleType, String styleValue, List<AidStoryboard> shots,
                                        Long projectId, Long episodeId, Long userId,
                                        ContinuityContext continuityContext)
    {
        boolean imageDir = DIRECTION_IMAGE.equals(direction);
        boolean gridDir = DIRECTION_GRID.equals(direction);
        List<String> referenceAssetNames = loadReferenceableAssetNames(projectId, userId);
        List<String> referenceAudioNames = roleAudioReferenceResolver.loadAvailableReferenceNames(
                projectId, episodeId, userId);
        Map<Long, StoryboardSceneContext> sceneContexts = storyboardSceneContextAssembler.assemble(
                shots, projectId, userId);
        String userContent;
        String unitLabel;
        if (gridDir)
        {
            userContent = buildBatchLlmInputGroup(styleType, styleValue, shots, true,
                    referenceAssetNames, referenceAudioNames, sceneContexts);
            unitLabel = UNIT_LABEL_GROUP;
        }
        else if (imageDir)
        {
            userContent = buildBatchLlmInputImage(styleType, styleValue, shots, projectId, episodeId, userId,
                    referenceAssetNames, referenceAudioNames, sceneContexts);
            unitLabel = UNIT_LABEL_SHOT;
        }
        else if (AGENT_CODE_MULTIREF.equals(agentCode))
        {
            userContent = buildBatchLlmInputGroup(styleType, styleValue, shots, false,
                    referenceAssetNames, referenceAudioNames, sceneContexts);
            unitLabel = UNIT_LABEL_GROUP;
        }
        else
        {
            userContent = buildBatchLlmInput(styleType, styleValue, shots,
                    referenceAssetNames, referenceAudioNames, sceneContexts);
            unitLabel = UNIT_LABEL_SHOT;
        }
        userContent = continuityAssembler.appendContext(userContent, continuityContext);
        // 预冻结必须与实际下发消息同源，身份契约的字符数不能漏算。
        userContent = appendBatchOutputContract(userContent, shots, unitLabel);
        String systemPrompt = helper.loadPromptByNameReadOnly(agentCode);
        return helper.estimateLlmInputChars(systemPrompt, userContent, modelCode);
    }

    /**
     * 整段批量入参拼装：【全局风格(风格名称/风格内容)】+【整段分镜脚本表(每镜 18 字段，源自 script_params 中文 key)】。
     * 镜头顺序沿用任务快照顺序，模型输出按分镜身份键归位，不依赖数组下标。
     */
    private String buildBatchLlmInput(String styleType, String styleValue, List<AidStoryboard> shots,
                                      List<String> referenceAssetNames,
                                      List<String> referenceAudioNames,
                                      Map<Long, StoryboardSceneContext> sceneContexts)
    {
        StringBuilder out = new StringBuilder(16384);
        // 全局风格（固定格式）
        out.append("【全局风格】\n");
        out.append("风格名称：").append(StrUtil.nullToEmpty(styleType)).append('\n');
        out.append("风格内容：").append(StrUtil.nullToEmpty(styleValue)).append('\n').append('\n');
        // 分镜脚本表
        out.append("【分镜脚本表】\n");
            for (AidStoryboard sb : shots)
            {
                Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
                StoryboardSceneContext sceneContext = sceneContexts.get(sb.getId());
                storyboardSceneContextAssembler.applyReferenceInfo(shot, sceneContext);
                sanitizeReferenceInfoInPlace(shot, referenceAssetNames, referenceAudioNames, sb.getId());
                out.append("--- ").append(UNIT_LABEL_SHOT).append(' ')
                        .append(StoryboardPromptBatchAligner.buildShotKey(sb.getId())).append(" ---\n");
                appendSceneContext(out, sceneContext);
            appendShotField(out, shot, FIELD_BIZ_NO_SHOT);
            appendShotField(out, shot, "剧本内容");
            appendShotField(out, shot, "画面描述");
            appendShotField(out, shot, "台词");
            appendShotField(out, shot, "动作状态");
            appendShotField(out, shot, "叙事功能");
            appendShotField(out, shot, "时间坐标");
            appendShotField(out, shot, "年代坐标");
            appendShotField(out, shot, "日期坐标");
            appendShotField(out, shot, "气候天象");
            appendShotField(out, shot, "引用信息");
            appendShotField(out, shot, "景别");
            appendShotField(out, shot, "拍摄角度");
            appendShotField(out, shot, "镜头焦距");
            appendShotField(out, shot, "镜头运动");
            appendShotField(out, shot, "构图");
            appendShotField(out, shot, "画面氛围");
            appendShotField(out, shot, "音效");
            out.append('\n');
        }
        return out.toString();
    }

    /** 输出一行"中文key：值"（值为空也输出空值行，保证字段位置稳定，便于 LLM 对齐 18 字段）。 */
    private void appendShotField(StringBuilder out, Map<String, Object> shot, String key)
    {
        String v = Convert.toStr(shot.get(key), "");
        out.append(key).append('：').append(StrUtil.nullToEmpty(v)).append('\n');
    }

    /** 加载当前仍可作为图片引用的名称白名单，整批一次查询，避免逐镜访问数据库。
     *  可引用域=项目+用户（不按集过滤），与出图解析器口径一致：
     *  项目级角色图（episode_id=0）/ 跨集复用资产图按集过滤会漏出白名单。 */
    private List<String> loadReferenceableAssetNames(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return new ArrayList<>();
        }
        List<AidRolePropSceneFormImage> images = rolePropSceneFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getName, AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsUse, 1)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, 0)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneFormImage::getSortOrder));
        if (CollectionUtil.isEmpty(images))
        {
            return new ArrayList<>();
        }
        return images.stream()
                .filter(image -> StrUtil.isNotBlank(image.getName()) && StrUtil.isNotBlank(image.getImageUrl()))
                .map(image -> StrUtil.trim(image.getName()))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 视觉导演入参治理：先按台词补齐真实角色音频，再剔除失效/杜撰引用。
     * 图片白名单来自可用 form_image，音频白名单来自启用角色绑定，二者都不信任 LLM 原文。
     */
    private void sanitizeReferenceInfoInPlace(Map<String, Object> shot, List<String> imageWhitelist,
            List<String> audioWhitelist, Long storyboardId)
    {
        if (Objects.isNull(shot))
        {
            return;
        }
        String refText = Convert.toStr(shot.get("引用信息"), "");
        String dialogueText = Convert.toStr(shot.get("台词"), "");
        refText = roleAudioReferenceResolver.ensureReferenceInfo(refText, dialogueText, audioWhitelist);
        if (StrUtil.isBlank(refText))
        {
            shot.remove("引用信息");
            return;
        }
        List<String> effectiveWhitelist = buildReferenceWhitelist(refText, imageWhitelist, audioWhitelist);
        if (CollectionUtil.isEmpty(effectiveWhitelist))
        {
            log.warn("视频提示词输入引用清洗：当前无可用媒体，引用信息全部文字降级, storyboardId={}", storyboardId);
            shot.remove("引用信息");
            return;
        }
        ReferenceAssetSanitizer.Result result = ReferenceAssetSanitizer.sanitize(refText, effectiveWhitelist);
        if (result.hasRemoval())
        {
            log.warn("视频提示词输入引用清洗: storyboardId={}, removed={}", storyboardId, result.getRemoved());
        }
        if (StrUtil.isBlank(result.getText()))
        {
            shot.remove("引用信息");
        }
        else
        {
            shot.put("引用信息", result.getText());
        }
    }

    /**
     * 构建引用白名单。音频只接受数据库启用绑定；视频暂沿用引用信息内的已有名称，避免图片清洗误删。
     */
    private List<String> buildReferenceWhitelist(String refText, List<String> imageWhitelist,
            List<String> audioWhitelist)
    {
        List<String> result = new ArrayList<>();
        if (CollectionUtil.isNotEmpty(imageWhitelist))
        {
            result.addAll(imageWhitelist);
        }
        if (CollectionUtil.isNotEmpty(audioWhitelist))
        {
            result.addAll(audioWhitelist);
        }
        appendBracketNamesInSection(refText, result, "视频");
        return result;
    }

    /** 提取引用信息指定分区内的方括号名称。 */
    private void appendBracketNamesInSection(String text, List<String> target, String sectionName)
    {
        if (StrUtil.isBlank(text) || Objects.isNull(target) || StrUtil.isBlank(sectionName))
        {
            return;
        }
        int start = text.indexOf(sectionName + "：");
        if (start < 0) { start = text.indexOf(sectionName + ":"); }
        if (start < 0) { return; }
        int sectionStart = start + sectionName.length() + 1;
        int sectionEnd = text.length();
        for (String label : List.of("场景", "角色", "道具", "视频", "音频"))
        {
            if (Objects.equals(label, sectionName)) { continue; }
            int full = text.indexOf(label + "：", sectionStart);
            int half = text.indexOf(label + ":", sectionStart);
            int next = full >= 0 && half >= 0 ? Math.min(full, half) : Math.max(full, half);
            if (next >= 0) { sectionEnd = Math.min(sectionEnd, next); }
        }
        Matcher matcher = Pattern.compile("\\[([^\\]]+)\\]").matcher(text.substring(sectionStart, sectionEnd));
        while (matcher.find())
        {
            String name = StrUtil.trimToEmpty(matcher.group(1));
            if (StrUtil.isNotBlank(name)) { target.add(name); }
        }
    }

    /** 视觉导演仍杜撰或沿用失效图片名时，落库前剥掉占位壳，仅保留名称语义。 */
    private String sanitizeVideoPromptReferences(String prompt, List<String> whitelist,
                                                  Long taskId, Long storyboardId)
    {
        Set<String> normalizedWhitelist = CollectionUtil.isEmpty(whitelist) ? java.util.Collections.emptySet()
                : whitelist.stream().filter(StrUtil::isNotBlank)
                        .map(StoryboardImageReferenceResolver::normalizeAssetRefName)
                        .collect(Collectors.toSet());
        Matcher matcher = IMAGE_REFERENCE_PATTERN.matcher(prompt);
        StringBuffer rewritten = new StringBuffer();
        List<String> degraded = new ArrayList<>();
        while (matcher.find())
        {
            String name = StrUtil.trimToEmpty(matcher.group(2));
            boolean valid = false;
            for (String candidate : StoryboardImageReferenceResolver.candidateLookupKeys(name))
            {
                if (normalizedWhitelist.contains(candidate))
                {
                    valid = true;
                    break;
                }
            }
            String replacement = valid ? matcher.group() : name;
            if (!valid) { degraded.add(name); }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        if (CollectionUtil.isNotEmpty(degraded))
        {
            log.warn("视频提示词输出引用文字降级: taskId={}, storyboardId={}, degraded={}",
                    taskId, storyboardId, degraded);
        }
        return rewritten.toString();
    }

    /**
     * 专业版「中文镜头组」整段批量入参拼装：供 多参方向-专业版（multiref）与 宫格方向（grid）共用。
     * 上游脚本由专业版编剧产出镜头组结构，{@code script_params} 的 key 与漫剧单镜版不同，
     * 此处按镜头组 10 字段装配：镜头组/剧本内容/画面说明/台词/时空环境/引用信息/镜头模式/运镜等级/时长估算/镜头脚本。
     *
     * @param includeImagePrompt 宫格方向=true：额外拼每个镜头组的「宫格图提示词」(image_prompt，由宫格画师产出)，
     *                           供宫格视觉导演按多宫格分镜图逐格出片；多参方向(pro)=false（pro 不出图）。
     */
    private String buildBatchLlmInputGroup(String styleType, String styleValue, List<AidStoryboard> shots,
                                           boolean includeImagePrompt, List<String> referenceAssetNames,
                                           List<String> referenceAudioNames,
                                           Map<Long, StoryboardSceneContext> sceneContexts)
    {
        StringBuilder out = new StringBuilder(16384);
        out.append("【全局风格】\n");
        out.append("风格名称：").append(StrUtil.nullToEmpty(styleType)).append('\n');
        out.append("风格内容：").append(StrUtil.nullToEmpty(styleValue)).append('\n').append('\n');
        out.append("【分镜脚本表（镜头组）】\n");
        for (AidStoryboard sb : shots)
        {
            Map<String, Object> group = parseScriptParams(sb.getScriptParams());
            StoryboardSceneContext sceneContext = sceneContexts.get(sb.getId());
            storyboardSceneContextAssembler.applyReferenceInfo(group, sceneContext);
            sanitizeReferenceInfoInPlace(group, referenceAssetNames, referenceAudioNames, sb.getId());
            out.append("--- ").append(UNIT_LABEL_GROUP).append(' ')
                    .append(StoryboardPromptBatchAligner.buildShotKey(sb.getId())).append(" ---\n");
            if (includeImagePrompt)
            {
                appendSceneContext(out, sceneContext);
            }
            else
            {
                appendVideoSceneContext(out, sceneContext);
            }
            appendShotField(out, group, FIELD_BIZ_NO_GROUP);
            appendShotField(out, group, "剧本内容");
            appendShotField(out, group, "画面说明");
            appendShotField(out, group, "台词");
            appendShotField(out, group, "时空环境");
            appendShotField(out, group, "引用信息");
            appendShotField(out, group, "镜头模式");
            appendShotField(out, group, "运镜等级");
            appendShotField(out, group, "时长估算");
            if (includeImagePrompt)
            {
                appendShotField(out, group, "镜头脚本");
            }
            else
            {
                // 多参视频只需要动作顺序；上游分镜图的机位、方位、焦距和距离是生图指令。
                out.append("镜头脚本：").append(summarizeVideoShotScript(
                        Convert.toStr(group.get("镜头脚本"), ""))).append('\n');
            }
            if (includeImagePrompt)
            {
                // 宫格图提示词（宫格画师产出的多宫格图 prompt），宫格视觉导演据此对齐"参考分镜图N"
                out.append("宫格图提示词：").append(StrUtil.nullToEmpty(sb.getImagePrompt())).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    static String summarizeVideoShotScript(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return "";
        }
        Matcher beatMatcher = VIDEO_SHOT_BEAT_PATTERN.matcher(raw);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (beatMatcher.find())
        {
            starts.add(beatMatcher.start());
            ends.add(beatMatcher.end());
        }
        StringBuilder facts = new StringBuilder();
        int beatCount = starts.isEmpty() ? 1 : starts.size();
        for (int i = 0; i < beatCount; i++)
        {
            String beat = starts.isEmpty() ? raw : raw.substring(ends.get(i),
                    i + 1 < beatCount ? starts.get(i + 1) : raw.length());
            Matcher factMatcher = VIDEO_SHOT_FACT_PATTERN.matcher(beat);
            StringBuilder selected = new StringBuilder();
            while (factMatcher.find())
            {
                if (!selected.isEmpty())
                {
                    selected.append('；');
                }
                selected.append(factMatcher.group(1)).append('：').append(factMatcher.group(2).trim());
            }
            if (!selected.isEmpty())
            {
                if (!facts.isEmpty())
                {
                    facts.append(" | ");
                }
                facts.append("第").append(i + 1).append("段 ").append(selected);
            }
        }
        return facts.toString();
    }

    /** 按条目单位取业务编号字段名：镜头单位取镜号，镜头组单位取镜头组。 */
    private static String bizNoFieldOf(String unitLabel)
    {
        return UNIT_LABEL_GROUP.equals(unitLabel) ? FIELD_BIZ_NO_GROUP : FIELD_BIZ_NO_SHOT;
    }

    /**
     * 追加整段批量的输出契约：要求模型原样回抄分段标题里的相关键与该条目的业务编号，并返回与入参等长的 JSON 数组。
     *
     * @param userContent 已拼装的批量入参
     * @param shotCount   本次送入的条目数量
     * @param unitLabel   条目单位（镜头 / 镜头组），与入参分段标题保持一致
     * @return 追加输出契约后的入参
     */
    private String appendBatchOutputContract(String userContent, List<AidStoryboard> shots, String unitLabel)
    {
        int shotCount = CollectionUtil.isEmpty(shots) ? 0 : shots.size();
        String bizNoField = bizNoFieldOf(unitLabel);
        String exampleKey = shotCount == 0 ? "SB-分镜ID"
                : StoryboardPromptBatchAligner.buildShotKey(shots.get(0).getId());
        return StrUtil.nullToEmpty(userContent)
                + "\n【输出对齐要求】\n"
                + "上文共 " + shotCount + " 个" + unitLabel + "，每段标题形如「--- " + unitLabel + " "
                + exampleKey + " ---」，其中 " + exampleKey + " 是该" + unitLabel + "的唯一身份标识。\n"
                + "必须返回长度为 " + shotCount + " 的 JSON 数组，每个元素额外包含两个字符串字段：\n"
                + StoryboardPromptBatchAligner.FIELD_SHOT_KEY + "：原样照抄该元素所对应" + unitLabel
                + "标题里的身份标识，不得改写、不得自行编号；\n"
                + bizNoField + "：原样照抄该" + unitLabel + "「" + bizNoField + "」字段的值。\n"
                + "shotKey 是系统内部身份，" + bizNoField + " 是业务编号；两者的字符串允许不同，例如 SB-3 与 001。"
                + "只需分别照抄同一输入段中的两个值，不得互相推算或要求数值相等。\n"
                + "禁止合并、省略、截断或调整顺序，内容相似也必须逐个输出。\n";
    }

    /**
     * 按分镜身份键和业务编号校验模型输出与目标列表的完整双射。
     *
     * @param elems     模型输出的数组元素
     * @param shots     本次送入的镜头列表，顺序即入参顺序
     * @param unitLabel 条目单位（镜头 / 镜头组）
     * @return 完整对齐结果；任何契约违约均不返回部分元素
     */
    private StoryboardPromptBatchAligner.AlignmentResult alignLlmElementsToShots(
            List<JsonNode> elems, List<AidStoryboard> shots, String unitLabel)
    {
        String bizNoField = bizNoFieldOf(unitLabel);
        List<StoryboardPromptBatchAligner.Target> targets = shots.stream()
                .map(shot -> new StoryboardPromptBatchAligner.Target(
                        shot.getId(), Convert.toStr(parseScriptParams(shot.getScriptParams()).get(bizNoField), "")))
                .collect(Collectors.toList());
        return StoryboardPromptBatchAligner.align(elems, targets, bizNoField);
    }

    /**
     * 图生方向（漫剧版）整段批量入参拼装：
     * 【全局风格】+【角色设定(本剧集角色形态：形态名/性别/形态专属特征)】+
     * 【分镜脚本表(每镜：画面描述/台词/动作状态/镜头运动/引用信息 + 分镜画面生图提示词)】。
     * 漫剧版需据「分镜画面生图提示词」提取形态名，再到「角色设定」匹配性别/服装/配饰做身份绑定，
     * 故图生方向比多参方向额外拼入 image_prompt 与角色形态设定。镜头顺序沿用任务快照顺序。
     */
    private String buildBatchLlmInputImage(String styleType, String styleValue, List<AidStoryboard> shots,
                                           Long projectId, Long episodeId, Long userId,
                                           List<String> referenceAssetNames,
                                           List<String> referenceAudioNames,
                                           Map<Long, StoryboardSceneContext> sceneContexts)
    {
        StringBuilder out = new StringBuilder(16384);
        // 全局风格
        out.append("【全局风格】\n");
        out.append("风格名称：").append(StrUtil.nullToEmpty(styleType)).append('\n');
        out.append("风格内容：").append(StrUtil.nullToEmpty(styleValue)).append('\n').append('\n');
        // 角色设定（形态名 + 性别 + 形态专属特征），供漫剧版身份绑定
        out.append(buildRoleSettingsSection(projectId, episodeId, userId));
        // 分镜脚本表 + 分镜画面生图提示词
        out.append("【分镜脚本表】\n");
        for (AidStoryboard sb : shots)
        {
            Map<String, Object> shot = parseScriptParams(sb.getScriptParams());
            StoryboardSceneContext sceneContext = sceneContexts.get(sb.getId());
            storyboardSceneContextAssembler.applyReferenceInfo(shot, sceneContext);
            sanitizeReferenceInfoInPlace(shot, referenceAssetNames, referenceAudioNames, sb.getId());
            out.append("--- ").append(UNIT_LABEL_SHOT).append(' ')
                    .append(StoryboardPromptBatchAligner.buildShotKey(sb.getId())).append(" ---\n");
            appendSceneContext(out, sceneContext);
            appendShotField(out, shot, FIELD_BIZ_NO_SHOT);
            appendShotField(out, shot, "画面描述");
            appendShotField(out, shot, "台词");
            appendShotField(out, shot, "动作状态");
            appendShotField(out, shot, "镜头运动");
            appendShotField(out, shot, "引用信息");
            // 分镜画面生图提示词（漫剧版据此提取形态名）
            out.append("分镜画面生图提示词：").append(StrUtil.nullToEmpty(sb.getImagePrompt())).append('\n');
            out.append('\n');
        }
        return out.toString();
    }

    private void appendSceneContext(StringBuilder out, StoryboardSceneContext context)
    {
        String text = storyboardSceneContextAssembler.formatPromptContext(context);
        if (StrUtil.isNotBlank(text))
        {
            out.append("【确定性场景上下文】\n").append(text);
        }
    }

    private void appendVideoSceneContext(StringBuilder out, StoryboardSceneContext context)
    {
        String text = storyboardSceneContextAssembler.formatPromptContext(context);
        if (StrUtil.isBlank(text))
        {
            return;
        }
        String description = context.sceneDescription();
        if (StrUtil.isNotBlank(description))
        {
            String videoFacts = videoSceneFacts(description);
            text = text.replace("场景描述：" + description + '\n',
                    StrUtil.isBlank(videoFacts) ? "" : "场景描述：" + videoFacts + '\n');
        }
        out.append("【确定性场景上下文】\n").append(text);
    }

    static String videoSceneFacts(String description)
    {
        String source = StrUtil.trimToEmpty(description);
        if (source.isEmpty())
        {
            return "";
        }
        String featureLabel = source.contains("场景特征描述：") ? "场景特征描述：" : "场景特征描述:";
        int featureStart = source.indexOf(featureLabel);
        if (featureStart >= 0)
        {
            String facts = source.substring(featureStart + featureLabel.length());
            for (String boundary : List.of("；四个视角", ";四个视角", "；图片风格", "；图像风格",
                    "；画面风格", "；镜头视角", "；左上角"))
            {
                int end = facts.indexOf(boundary);
                if (end >= 0)
                {
                    facts = facts.substring(0, end);
                }
            }
            return facts.trim();
        }
        for (String imageRule : List.of("宫格", "分割线", "焦距", "相机高度", "输出一张", "生成一张",
                "画幅比例", "图片风格", "图像风格"))
        {
            if (source.contains(imageRule))
            {
                return "";
            }
        }
        return source;
    }

    /**
     * 组装【角色设定】段：项目级 character 资产的每个形态（形态名 / 性别 / 变更原因 / 形态专属特征 promptText）。
     * 剧集角色主资产项目内唯一（episodeId=0），按项目维度查询覆盖全局角色与历史按集角色。
     * 仅读必要列；无角色资产时返回提示行（不阻断，LLM 退化为无身份绑定）。
     */
    private String buildRoleSettingsSection(Long projectId, Long episodeId, Long userId)
    {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("【角色设定】\n");
        List<AidRolePropScene> roles = rolePropSceneService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getName, AidRolePropScene::getGender,
                                AidRolePropScene::getAssetType, AidRolePropScene::getDelFlag)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getAssetType, "character")
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(roles))
        {
            sb.append("（暂无角色设定）\n\n");
            return sb.toString();
        }
        for (AidRolePropScene role : roles)
        {
            String gender = StrUtil.blankToDefault(role.getGender(), "未知");
            sb.append("- 角色：").append(StrUtil.nullToEmpty(role.getName()))
              .append("（性别：").append(gender).append("）\n");
            // 该角色全部形态
            List<AidRolePropSceneForm> forms = rolePropSceneFormService.list(
                    Wrappers.<AidRolePropSceneForm>lambdaQuery()
                            .select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getName,
                                    AidRolePropSceneForm::getChangeReason, AidRolePropSceneForm::getPromptText,
                                    AidRolePropSceneForm::getDelFlag)
                            .eq(AidRolePropSceneForm::getAssetId, role.getId())
                            .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
            if (CollectionUtil.isEmpty(forms))
            {
                continue;
            }
            for (AidRolePropSceneForm form : forms)
            {
                sb.append("  · 形态：").append(StrUtil.nullToEmpty(form.getName()));
                if (StrUtil.isNotBlank(form.getChangeReason()))
                {
                    sb.append("（").append(form.getChangeReason()).append("）");
                }
                sb.append("｜特征：").append(StrUtil.nullToEmpty(form.getPromptText())).append('\n');
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 解析视觉导演 LLM 输出的 JSON 数组（[{"prompt":..,"duration":..}, ...]），按顺序返回元素节点。
     * 先剥 markdown 代码块包装，再按输出内容定位元素列表，模型把数组套在外壳对象里同样可解析；
     * 取不到元素返回空列表（调用方按整批契约违约处理）。
     */
    private List<JsonNode> parseLlmOutputArray(String llmRaw)
    {
        List<JsonNode> result = new ArrayList<>();
        String text = StrUtil.trimToEmpty(llmRaw);
        if (text.startsWith("```"))
        {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl)
            {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        if (StrUtil.isBlank(text)) { return result; }
        try
        {
            collectOutputElements(OBJECT_MAPPER.readTree(text), OUTPUT_ENVELOPE_MAX_DEPTH, result);
        }
        catch (Exception e)
        {
            log.error("视频提示词 LLM 输出 JSON 数组解析失败: outputLen={}, err={}", text.length(), e.getMessage());
        }
        return result;
    }

    private String providerRejectionMessage(String llmRaw)
    {
        if (StrUtil.isBlank(llmRaw))
        {
            return "";
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(llmRaw.trim());
            if (Objects.isNull(root) || !root.isObject() || !root.hasNonNull("error"))
            {
                return "";
            }
            JsonNode error = root.path("error");
            String reason = error.isTextual() ? error.asText("")
                    : error.path("message").asText(error.path("reason").asText(""));
            if (StrUtil.isBlank(reason))
            {
                return "";
            }
            String code;
            String message;
            if (reason.contains("shotKey") || reason.contains("镜头组") || reason.contains("业务编号"))
            {
                code = "PROVIDER_OUTPUT_REJECTED_IDENTITY_CONFLICT";
                message = FAILURE_IDENTITY_CONFLICT;
            }
            else if (reason.contains("方向") || reason.contains("焦距")
                    || reason.contains("距离") || reason.contains("量化"))
            {
                code = "PROVIDER_OUTPUT_REJECTED_SCENE_CONFLICT";
                message = FAILURE_SCENE_CONFLICT;
            }
            else
            {
                code = "PROVIDER_OUTPUT_REJECTED";
                message = FAILURE_PROVIDER_REJECTED;
            }
            log.warn("视频提示词模型明确拒绝业务输出: reasonCode={}", code);
            return message;
        }
        catch (Exception ignored)
        {
            return "";
        }
    }

    private String normalizeMultirefGlobalStyle(String prompt, String direction, String agentCode,
                                                String styleType, String styleValue)
    {
        if (!DIRECTION_MULTI.equals(direction) || !AGENT_CODE_MULTIREF.equals(agentCode)
                || StrUtil.isBlank(prompt) || !prompt.contains("场景：") || prompt.contains("全局风格："))
        {
            return prompt;
        }
        String style = StrUtil.blankToDefault(styleValue, styleType);
        return StrUtil.isBlank(style) ? prompt : prompt.stripTrailing() + "\n全局风格：" + style.trim();
    }

    private boolean isReplayVideoOutputValid(String llmRaw, List<AidStoryboard> targetList,
                                             String unitLabel, String direction, String agentCode,
                                             String styleType, String styleValue)
    {
        List<JsonNode> elements = parseLlmOutputArray(llmRaw);
        StoryboardPromptBatchAligner.AlignmentResult alignment =
                alignLlmElementsToShots(elements, targetList, unitLabel);
        if (!alignment.valid())
        {
            return false;
        }
        boolean durationRequired = Objects.equals(DIRECTION_MULTI, direction)
                || Objects.equals(DIRECTION_GRID, direction);
        for (JsonNode element : alignment.elements())
        {
            String prompt = Objects.isNull(element) ? "" : element.path(FIELD_PROMPT).asText("");
            prompt = normalizeMultirefGlobalStyle(prompt, direction, agentCode, styleType, styleValue);
            if (StrUtil.isBlank(prompt) || !isVideoPromptFormatValid(prompt, direction, agentCode)
                    || (durationRequired && element.path(FIELD_DURATION).asInt(0) <= 0))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 从模型输出节点中取出契约元素列表。
     * 数组节点即元素列表原样展开（缺字段留给逐镜校验，保证「元素数＝镜头数」的契约判定口径不变）；
     * 自带 prompt 正文的对象视为单元素；其余对象一律按外壳逐层向内探测，且只接受确实含 prompt 正文的那一组，
     * 因此不依赖任何固定外壳键名，也不会把外壳里并列的提示信息数组误当成输出。
     *
     * @param node      待判定节点
     * @param depthLeft 剩余向内探测层数
     * @param out       元素收集结果
     */
    private void collectOutputElements(JsonNode node, int depthLeft, List<JsonNode> out)
    {
        if (Objects.isNull(node) || node.isNull())
        {
            return;
        }
        if (node.isArray())
        {
            for (JsonNode elem : node)
            {
                out.add(elem);
            }
            return;
        }
        if (!node.isObject())
        {
            return;
        }
        if (isOutputElement(node))
        {
            out.add(node);
            return;
        }
        if (depthLeft <= 0)
        {
            return;
        }
        for (JsonNode child : node)
        {
            List<JsonNode> nested = new ArrayList<>();
            collectOutputElements(child, depthLeft - 1, nested);
            if (nested.stream().anyMatch(this::isOutputElement))
            {
                out.addAll(nested);
                return;
            }
        }
    }

    /** 判定节点是否为契约元素：对象且带非空 prompt 正文。 */
    private boolean isOutputElement(JsonNode node)
    {
        return Objects.nonNull(node) && node.isObject()
                && StrUtil.isNotBlank(node.path(FIELD_PROMPT).asText(""));
    }

    private void sendMqMessage(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode)
    {
        // 双模式派发统一收口：MQ 开走 MQ；MQ 关走本地线程（共用同一执行体 + 终态编排）
        boolean enqueued = dualModeTaskDispatcher.dispatch(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH,
                () -> batchTaskLocalOrchestrator.run(taskId, userId, LOCAL_SPEC,
                        () -> doStoryboardVideoPromptBatch(taskId, userId,
                                taskQueueService.currentLocalDispatchToken(taskId)),
                        () -> assetExtractService.releaseBatchFormLocks(
                                taskId, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH,
                                taskQueueService.currentLocalDispatchToken(taskId))));
        if (!enqueued)
        {
            // 入队失败必须上抛：由提交 / 续生链路统一回滚冻结款与任务状态，杜绝冻结款 / 任务状态悬挂
            log.error("视频提示词批量入队失败: taskId={}", taskId);
            throw new ServiceException("提交失败，请重试");
        }
    }

    private void sendMqMessageNow(Long taskId, Long projectId, Long episodeId,
                                  Long userId, String modelCode, String dispatchMode,
                                  String dispatchToken)
    {
        boolean enqueued = dualModeTaskDispatcher.dispatchNow(taskId, projectId, episodeId, userId,
                modelCode, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH,
                () -> batchTaskLocalOrchestrator.run(taskId, userId, LOCAL_SPEC,
                        () -> doStoryboardVideoPromptBatch(taskId, userId,
                                taskQueueService.currentLocalDispatchToken(taskId)),
                        () -> assetExtractService.releaseBatchFormLocks(
                                taskId, TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH,
                                taskQueueService.currentLocalDispatchToken(taskId))),
                dispatchMode, dispatchToken);
        if (!enqueued)
        {
            log.error("视频提示词续生立即入队失败: taskId={}", taskId);
            throw new ServiceException("提交失败，请重试");
        }
    }
}
