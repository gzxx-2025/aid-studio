package com.aid.rps.service.impl;

import com.aid.common.error.ErrorNormalizer;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.common.error.TaskErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.domain.AidPromptLib;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidRoleVoiceBinding;
import com.aid.aid.domain.AidScenePlot;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.aid.service.IAidPromptLibService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidRoleVoiceBindingService;
import com.aid.aid.service.IAidScenePlotService;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;
import com.aid.common.aid.rocketmq.core.MqTemplateFactory;
import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.common.utils.DateUtils;
import com.aid.common.core.redis.RedisCache;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaBillingQuoteService;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.rps.dto.AssetExtractRequest;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.BatchFormGenerateResult;
import com.aid.rps.dto.BatchFormImageResult;
import com.aid.rps.dto.CancelBatchResult;
import com.aid.rps.dto.ExtractTaskMessage;
import com.aid.rps.dto.FormImageGenerateRequest;
import com.aid.rps.dto.FormCardImageGenerateRequest;
import com.aid.rps.dto.FormGenerateRequest;
import com.aid.rps.helper.AssetExtractHelper;
import com.aid.rps.helper.SceneExtractionNormalizer;
import com.aid.rps.helper.SceneExtractionNormalizer.NormalizedScene;
import com.aid.rps.model.ExistingAssetLib;
import com.aid.rps.exception.PartialExtractionException;
import com.aid.rps.queue.BatchParentResourceCleaner;
import com.aid.rps.queue.BatchParentSubmissionGuard;
import com.aid.rps.queue.BatchTaskExecutionRejectedException;
import com.aid.rps.queue.BatchTaskLogicalType;
import com.aid.rps.queue.BatchTaskSlotReservation;
import com.aid.rps.queue.BatchTaskSlotService;
import com.aid.projectgenconfig.enums.ProjectGenConfigScene;
import com.aid.project.service.ProjectStyleSnapshotService;
import com.aid.agent.AgentDefaultParamsApplier;
import com.aid.agent.AgentModelDefault;
import com.aid.agent.IAidAgentService;
import com.aid.aid.domain.AidAgent;
import com.aid.rps.service.IAssetExtractService;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IFormImageSelectionService;
import com.aid.rps.service.IExtractBillingService.ResumeBillingContext;
import com.aid.rps.service.IExtractBillingService.ResumeTaskMutation;
import com.aid.rps.service.IExtractBillingService.TextCallBillingContext;
import com.aid.rps.sse.AssetExtractSseManager;
import com.aid.rps.vo.RpsAssetVO;
import com.aid.rps.vo.RpsFormVO;
import com.aid.service.IAiModelConfigService;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.billing.service.BillingAmountCalculator;
import com.aid.billing.service.BillingQuoteAssembler;
import com.aid.billing.service.BillingPriceMultiplierService;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.enums.BillingConstants;
import com.aid.billing.error.BillingBalanceErrors;
import com.aid.billing.enums.BillingMode;
import com.aid.billing.enums.MeterType;
import com.aid.billing.model.BillingSnapshot;
import com.aid.billing.vo.BillingQuoteVO;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.extern.slf4j.Slf4j;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.exception.ServiceException;

/**
 * 两阶段流水线AI资产提取Service实现。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class AssetExtractServiceImpl implements IAssetExtractService, com.aid.rps.queue.QueueTaskFinalizer
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String DEL_FLAG_DELETED = "2";
    private static final String DELETE_REASON_AUTO_OVERWRITE = "auto_overwrite";

    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_FINALIZING = "FINALIZING";
    private static final String TASK_STATUS_RECOVERING = "RECOVERING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    private static final String TASK_STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    /** 用户主动取消（区分于 FAILED） */
    private static final String TASK_STATUS_CANCELLED = "CANCELLED";
    /** 排队中（已预冻结，等待并发名额） */
    private static final String TASK_STATUS_QUEUED = "QUEUED";
    /** 多模型资产提取计费快照类型。 */
    private static final String BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT = "asset_extract_multi_model";
    /**
     * 取消信号 Redis Key 前缀。
     * TTL 6 小时，覆盖剧集模式下可能跑几十分钟的任务；
     * 任务终态时会主动 delete，不会长期滞留。
     */
    private static final String CANCEL_FLAG_PREFIX = "asset:extract:cancel:";
    private static final long CANCEL_FLAG_TTL_SECONDS = 6L * 60L * 60L;

    // 图片生成的"进行中"状态白名单：
    //   与媒体主链路对齐——INIT / PENDING / QUEUED / PROCESSING / WAIT_POLL / WAIT_CALLBACK 均为合法的
    //   异步中间态，不应被当作失败处理（阿里万相首次返回 WAIT_POLL、回调优先模型首次返回 WAIT_CALLBACK，
    //   早期逻辑均会误判为失败）。
    private static final Set<String> IMAGE_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");

    private static final String TASK_TYPE_ASSET_EXTRACT = "asset_extract";
    private static final String TASK_TYPE_FORM_GENERATE = "form_generate";
    private static final String TASK_TYPE_FORM_IMAGE = "form_image";
    private static final String TASK_TYPE_IMAGE_UPSCALE = "image_upscale";
    /** 批量形态生成父任务 */
    private static final String TASK_TYPE_FORM_GENERATE_BATCH = "form_generate_batch";
    /** 批量形态图生成父任务 */
    private static final String TASK_TYPE_FORM_IMAGE_BATCH = "form_image_batch";
    /** 多机位形态生图任务（单条任务，沿用形态生图链路，由 FormMultiViewImageServiceImpl 发起） */
    private static final String TASK_TYPE_FORM_MULTI_VIEW = "form_multi_view";
    /** 编辑弹窗生图 / 对话作图任务（单条任务，沿用形态生图链路，由 FormEditChatImageServiceImpl 发起） */
    private static final String TASK_TYPE_FORM_EDIT_CHAT = "form_edit_chat";
    /** 设定卡任务类型标识，与 generateFormImage 的 form_image 区分 */
    private static final String TASK_TYPE_FORM_CARD_IMAGE = "form_card_image";
    /** 批量角色设定卡生成父任务（角色源图 → 设定卡） */
    private static final String TASK_TYPE_FORM_CARD_IMAGE_BATCH = "form_card_image_batch";
    /** 批量分镜脚本生成父任务 */
    private static final String TASK_TYPE_STORYBOARD_SCRIPT_BATCH = "storyboard_script_batch";
    /** 批量分镜图脚本（图生图 prompt）生成父任务 */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH = "storyboard_image_prompt_batch";
    /** 批量分镜视频提示词生成父任务（视觉导演） */
    private static final String TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH = "storyboard_video_prompt_batch";
    /** 分镜编辑图任务（单条任务，结果落 aid_gen_record，由 StoryboardEditImageServiceImpl 发起） */
    private static final String TASK_TYPE_STORYBOARD_EDIT_IMAGE = "storyboard_edit_image";
    /** 分镜图高清任务（单条任务，结果落 aid_gen_record，由 StoryboardImageUpscaleServiceImpl 发起） */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_UPSCALE = "storyboard_image_upscale";
    /** 分镜单机位机位生图任务（归属分镜，结果落 aid_gen_record/gen_type=image，由 StoryboardMultiViewGridImageServiceImpl 发起） */
    private static final String TASK_TYPE_STORYBOARD_MULTI_VIEW_IMAGE = "storyboard_multi_view_image";
    /** 分镜九宫格机位生图任务（归属分镜，结果落 aid_gen_record/gen_type=grid，由 StoryboardMultiViewGridImageServiceImpl 发起） */
    private static final String TASK_TYPE_STORYBOARD_MULTI_GRID_IMAGE = "storyboard_multi_grid_image";
    /** 分镜图生成父任务（count 子任务循环单图，结果每张落 aid_gen_record，由 StoryboardImageGenerationServiceImpl 发起） */
    private static final String TASK_TYPE_STORYBOARD_IMAGE_GENERATE = "storyboard_image_generate";
    /** 分镜图生视频父任务（count 子任务循环单视频，结果每条落 aid_gen_record(gen_type=i2v)，由 StoryboardVideoGenerationServiceImpl 发起） */
    private static final String TASK_TYPE_STORYBOARD_VIDEO_GENERATE = "storyboard_video_generate";
    /** 分镜批量配音父任务。 */
    private static final String TASK_TYPE_STORYBOARD_AUDIO_GENERATE = "storyboard_audio_generate";
    /** 分镜批量对口型父任务。 */
    private static final String TASK_TYPE_STORYBOARD_LIP_SYNC_GENERATE = "storyboard_lip_sync_generate";
    /** 分镜脚本提取智能体编码 */
    private static final String PROMPT_NAME_STORYBOARD_SCRIPT = "aid_storyboard_script_extractor";
    /** 分镜脚本业务分类编码 */
    private static final String BIZ_CATEGORY_MAIN_STORYBOARD_SCRIPT = "main_storyboard_script";

    /** 剧集角色提取：每组集数 */
    private static final int SERIES_EPISODE_GROUP_SIZE = 10;
    private static final String ASSET_TYPE_CHARACTER = "character";
    private static final String ASSET_TYPE_SCENE = "scene";
    private static final String ASSET_TYPE_PROP = "prop";

    /** 角色设定卡允许使用的源图来源。 */
    private static final Set<String> CARD_IMAGE_SOURCE_TYPES = Set.of("ai_auto", "upload", "ai_edit_chat");

    private static final String EXTRACT_TYPE_CHARACTER = "character";
    private static final String EXTRACT_TYPE_SCENE = "scene";
    private static final String EXTRACT_TYPE_PROP = "prop";

    /** MEDIA_TASK 周期逐调用业务落库水位，值为该调用成功提交的资产主键列表。 */
    private static final String ROLLING_TEXT_CALL_RESULTS_KEY = "rollingTextCallResults";

    /** 提取范围：剧集增量（只分析当前集，剧集默认） */
    private static final String EXTRACT_SCOPE_EPISODE_INCREMENTAL = "EPISODE_INCREMENTAL";

    /** 提取范围：全项目扫描（仅角色，首次初始化/主动全量） */
    private static final String EXTRACT_SCOPE_PROJECT_FULL = "PROJECT_FULL";

    /** 角色主资产的项目级归属集（剧集角色跨集唯一，统一落 episodeId=0） */
    private static final Long CHARACTER_GLOBAL_EPISODE_ID = 0L;

    private static final String PROJECT_TYPE_MOVIE = "movie";
    private static final String PROJECT_TYPE_SERIES = "series";
    private static final String MODEL_TYPE_TEXT = "text";
    private static final String MODEL_TYPE_IMAGE = "image";

    private static final String MQ_TOPIC = "ASSET_EXTRACT_TOPIC";
    private static final String MQ_TAG = "extract";

    private static final String PROMPT_NAME_CASTING = "aid_casting_director";
    private static final String PROMPT_NAME_SCENE = "aid_scene_extractor";
    private static final String PROMPT_NAME_PROP = "aid_prop_extractor";
    private static final String PROMPT_NAME_VISUAL_STYLIST = "aid_visual_stylist";
    // 业务分类编码（biz_category_code）常量：
    //   - 强约束：智能体的 biz_category_code 必须等于下面对应的常量
    //   - 该常量同时也是 aid_ai_model_func_config.func_code，
    //     业务接口会用它去拉对应可选模型池
    /** 公用并行提取（角色） */
    private static final String BIZ_CATEGORY_MAIN_CHARACTER_EXTRACT = "main_character_extract";
    /** 公用并行提取（场景） */
    private static final String BIZ_CATEGORY_MAIN_SCENE_EXTRACT = "main_scene_extract";
    /** 公用并行提取（道具） */
    private static final String BIZ_CATEGORY_MAIN_PROP_EXTRACT = "main_prop_extract";

    /**
     * form/generate 角色形态生成业务分类（character / scene / prop 三类均调 LLM）。
     * scene/prop 对应分类常量为字面量 "main_scene_form" / "main_prop_form"。
     */
    private static final String BIZ_CATEGORY_MAIN_CHARACTER_FORM = "main_character_form";

    /** form/generate-image 角色形态图 */
    private static final String BIZ_CATEGORY_MAIN_CHARACTER_IMAGE = "main_character_image";
    /** form/generate-image 场景形态图 */
    private static final String BIZ_CATEGORY_MAIN_SCENE_IMAGE = "main_scene_image";
    /** form/generate-image 道具形态图 */
    private static final String BIZ_CATEGORY_MAIN_PROP_IMAGE = "main_prop_image";

    /** form/generate-card-image 角色设定卡 */
    private static final String BIZ_CATEGORY_MAIN_CHARACTER_CARD_IMAGE = "main_character_card_image";

    /** 形态图模板 remark：角色 / 场景 / 道具 */
    // 人物蓝图模板：当前 character 分支未使用，保留常量以对应词库中仍存在的模板
    private static final String PROMPT_NAME_CHARACTER_IMAGE = "aid_persona_blueprint";
    // 人物形态图"参考图 + 提示词"模板：供传图接口基于已有角色图生成设定卡，不可删除。
    private static final String PROMPT_NAME_CHARACTER_FORM_IMAGE_BUILDER = "aid_character_form_image_builder";
    /**
     * 人物形态图 txt2img 默认智能体。编码为历史稳定标识，模板内容由智能体配置维护。
     */
    private static final String PROMPT_NAME_CHARACTER_FORM_IMAGE = "aid_character_form_image_background_white";
    private static final String PROMPT_NAME_SCENE_IMAGE = "aid_scene_image_builder";
    private static final String PROMPT_NAME_PROP_IMAGE = "aid_prop_image_builder";
    private static final String CHARACTER_FORM_IMAGE_NAME_SUFFIX = "_角色设定";

    /*
     * 资产生图的尺寸（resolution）/ 比例（aspectRatio）由项目级配置决定
     * （aid_project_gen_config 覆盖 + aid_gen_agent_pool 矩阵默认），缺省值由模型
     * capability_json 经 ModelCapabilityResolver 兜底，本类不放硬编码默认值；
     * 调整默认值走后台「智能体矩阵配置」的 main_*_image / main_character_card_image 行。
     * 档位/比例到厂商像素尺寸的翻译由各 ImageProviderClient 负责，业务层禁止写厂商分支。
     */

    /**
     * 形态图生成后缀（代码常量，不入库）。
     * 只承载构图与画面纪律，禁止写入媒介 / 画风词（写实、真人、卡通、3D、摄影等），
     * 画面媒介统一由项目画风在外层注入，避免后缀覆盖用户所选风格。
     */
    private static final String CHARACTER_PROMPT_SUFFIX = "，角色设定图，白色纯色背景，全身正面站姿，清晰可辨的服装与配饰细节，无文字水印";
    private static final String LOCATION_SPATIAL_SUFFIX = "，场景设定图，保留可落位位置的空间布局，透视关系准确，空间比例合理，镜头稍微俯视，无人物，无文字水印";
    private static final String PROP_PROMPT_SUFFIX = "，道具设定图，白色纯色背景，单品居中展示，主体完整不裁切，细节清晰，无文字水印";
    private static final String CHARACTER_FORM_IMAGE_LAYOUT_CONSTRAINT =
            "\n角色形态图布局要求：\n"
            + "必须在同一白色背景画布中展示同一角色的正面、侧面和背面等完整视图。\n"
            + "各视图的人物身份、服装、配饰、颜色和材质必须一致，主体完整且互不遮挡。\n"
            + "智能体模板或人物描述中的单视图、禁止多视角等历史要求不适用于本次生成。";

    private static final String VISUAL_DESC_STATUS_PENDING = "pending";
    private static final String VISUAL_DESC_STATUS_COMPLETED = "completed";
    // 角色变体形态自动继承「初始形象」基准图相关常量：
    // 同 assetId 下 change_reason=初始形象 视为基准形态，其余形态默认继承其基准图，
    // 保证同一角色不同形态（如换装）的外观一致。
    /** 基准形态固定变更原因：同 assetId 下该形态视为「初始形象」基准 */
    private static final String INITIAL_FORM_CHANGE_REASON = "初始形象";
    /** form_image.is_use=1：用户主动设为引用（优先作为基准参考图） */
    private static final int IS_USE_YES = 1;
    /** form_image.is_split_source=0：非四宫格拆分源图，可作为参考图 */
    private static final int IS_SPLIT_SOURCE_NO = 0;
    /** form_image.image_status=completed：已完成图片 */
    private static final String IMAGE_STATUS_COMPLETED = "completed";
    /**
     * 角色变体形态「参考图锁人设」一致性约束（运行时拼接，不入库）。
     * 仅在「非初始形态 + 已解析到继承基准图」时追加到 finalPrompt 末尾：
     * 参考图负责锁定「这个人是谁」，当前形态描述负责说明「这次只改什么」。
     */
    private static final String CHARACTER_CONSISTENCY_CONSTRAINT =
            "\n角色一致性约束：\n"
            + "必须与参考图中的人物保持为同一角色。\n"
            + "保持五官、脸型、发型、发色、胡须、体型、年龄感、身份气质一致。\n"
            + "未明确要求修改的部分，一律以参考图为准。\n"
            + "仅允许按上方「人物提示词」改变服装、配饰、姿态、表情、局部状态。";

    /** 角色提取切片大小：保留更大上下文 */
    private static final int CHUNK_SIZE_CHARACTER = 30000;
    /** 场景/道具提取切片大小 */
    private static final int CHUNK_SIZE_SCENE_PROP = 3000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final class RollingExtractProgress
    {
        private final boolean enabled;
        private final String billingTraceId;
        private final Map<String, List<Long>> assetIdsByCall;

        private RollingExtractProgress(boolean enabled, String billingTraceId,
                                       Map<String, List<Long>> assetIdsByCall)
        {
            this.enabled = enabled;
            this.billingTraceId = billingTraceId;
            this.assetIdsByCall = assetIdsByCall;
        }

        private boolean isCompleted(String stableSlot)
        {
            return enabled && assetIdsByCall.containsKey(stableSlot);
        }

        private void recordCompleted(String stableSlot, List<Long> assetIds)
        {
            assetIdsByCall.put(stableSlot, assetIds);
        }
    }

    /**
     * 资产提取工作线程池配置可配化，替代 newFixedThreadPool(3)。
     */
    @org.springframework.beans.factory.annotation.Value("${aid.extract.executor.core-size:6}")
    private int extractExecutorCoreSize;
    @org.springframework.beans.factory.annotation.Value("${aid.extract.executor.max-size:12}")
    private int extractExecutorMaxSize;
    @org.springframework.beans.factory.annotation.Value("${aid.extract.executor.queue-capacity:50}")
    private int extractExecutorQueueCapacity;

    /**
     * 资产提取工作线程池：用 lazy init，在 @PostConstruct 中按配置值创建。
     */
    private volatile ExecutorService extractExecutor;

    private ExecutorService getExtractExecutor() {
        ExecutorService exec = extractExecutor;
        if (exec != null) {
            return exec;
        }
        synchronized (this) {
            if (extractExecutor == null) {
                int core = extractExecutorCoreSize > 0 ? extractExecutorCoreSize : 6;
                int max = Math.max(extractExecutorMaxSize, core);
                int queueCap = extractExecutorQueueCapacity > 0 ? extractExecutorQueueCapacity : 50;
                extractExecutor = new java.util.concurrent.ThreadPoolExecutor(
                        core, max, 60L, TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(queueCap),
                        r -> {
                            Thread t = new Thread(r, "asset-extract-worker");
                            t.setDaemon(true);
                            return t;
                        },
                        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
                log.info("资产提取线程池初始化: core={}, max={}, queueCap={}", core, max, queueCap);
            }
            return extractExecutor;
        }
    }

    /** 提交防重锁 Redis Key 前缀 */
    private static final String EXTRACT_LOCK_PREFIX = "asset:extract:lock:";

    /**
     * Lua 脚本：仅当 GET key == ARGV[1] 才 DEL，防止自动过期后被他人复用又被本请求误删。
     * 用于"僵尸锁"清理时 compare-and-delete。
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> EXTRACT_LOCK_RELEASE_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IAidExtractTaskService extractTaskService;

    @Autowired
    private BatchTaskSlotService batchTaskSlotService;

    @Autowired
    private BatchParentSubmissionGuard batchParentSubmissionGuard;

    @Autowired
    private BatchParentResourceCleaner batchParentResourceCleaner;

    @Autowired
    private IAidComicProjectService projectService;

    /** 提示词库：画风 official 类型需要根据 promptName 反查真实提示词 */
    @Autowired
    private IAidPromptLibService promptLibService;

    @Autowired
    private IAidRolePropSceneService rpsService;

    @Autowired
    private IAidRolePropSceneFormService rpsFormService;

    /** 场次兼容数据 Service。 */
    @Autowired
    private IAidScenePlotService scenePlotService;

    /** 角色音色绑定 Service。 */
    @Autowired
    private IAidRoleVoiceBindingService roleVoiceBindingService;

    /**
     * 形态图片实例 Service：generateFormImage 不回写 form.image_url，
     * 改为向 aid_role_prop_scene_form_image 插入 source_type=ai_auto 的图片实例行；
     * form 表 image_url / extra_images / is_use 字段已下线，全部走 form_image。
     */
    @Autowired
    private com.aid.aid.service.IAidRolePropSceneFormImageService rpsFormImageService;

    /** 生成图片默认使用状态统一规则。 */
    @Autowired
    private IFormImageSelectionService formImageSelectionService;

    @Autowired
    private RocketMqConfigManager rocketMqConfigManager;

    /** 本地派发终态编排器（与 MQ Consumer 共用同一套终态语义；form_generate/form_image 失败仍记成功，故 partialOnFailed=false） */
    @Autowired
    private com.aid.rps.queue.BatchTaskLocalOrchestrator batchTaskLocalOrchestrator;

    /** 双模式派发路由器（MQ/本地切换唯一收口） */
    @Autowired
    private com.aid.rps.queue.DualModeTaskDispatcher dualModeTaskDispatcher;

    /** 扇入支撑：僵尸回收守卫用（判断非阻塞出图/出片父任务是否仍有 media 子任务在途）。 */
    @Autowired
    private com.aid.rps.queue.MediaGenFanInSupport mediaGenFanInSupport;

    /** 分镜批量任务「重启自愈」回收策略集合（脚本/图提示词/视频提示词各一个独立组件 C，A→List 无循环依赖）。 */
    @Autowired
    private java.util.List<com.aid.rps.queue.BatchTaskRestartRecovery> batchTaskRestartRecoveries;

    /** 取消标记低层管理器（单一来源，与编排器共用，避免前缀重复与循环依赖） */
    @Autowired
    private com.aid.rps.queue.TaskCancelFlagManager taskCancelFlagManager;

    /** 本地编排规格：批量形态生成（有失败项仍记 SUCCEEDED，与 Consumer 同口径） */
    private static final com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec LOCAL_SPEC_FORM_GENERATE =
            new com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec(
                    "form_generate", "初始化批量形态生成...",
                    "剩余任务已取消", "部分生成失败，可续生",
                    TASK_TYPE_FORM_GENERATE_BATCH, false);

    /** 本地编排规格：批量形态图生成（全成功/部分失败/全部失败分别收口） */
    private static final com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec LOCAL_SPEC_FORM_IMAGE =
            new com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec(
                    "form_image_gen", "初始化批量形态图生成...",
                    "剩余任务已取消", "部分生成失败，可续生",
                    TASK_TYPE_FORM_IMAGE_BATCH, true, true);

    /** 本地编排规格：批量设定卡生成（有失败项 → PARTIAL_FAILED，与 Consumer 同口径） */
    private static final com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec LOCAL_SPEC_FORM_CARD =
            new com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec(
                    "form_card_image_gen", "初始化批量角色设定卡生成...",
                    "剩余任务已取消", "部分设定卡生成失败，可续生",
                    TASK_TYPE_FORM_CARD_IMAGE_BATCH, true);

    @Autowired
    private MqTemplateFactory mqTemplateFactory;

    @Autowired
    private AssetExtractSseManager sseManager;

    @Autowired
    private AssetExtractHelper helper;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    /** 业务分类（funcCode）池校验需要复用此服务 */
    @Autowired
    private IAiModelBusinessService aiModelBusinessService;

    /** 项目级配置解析器。 */
    @Autowired
    private com.aid.projectgenconfig.service.IProjectGenConfigResolver projectGenConfigResolver;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    @Autowired
    private MediaBillingQuoteService mediaBillingQuoteService;

    @Autowired
    private IExtractBillingService extractBillingService;

    @Autowired
    private AgentDefaultParamsApplier agentDefaultParamsApplier;

    /**
     * 智能体配置 Service：公用并行提取按 (extractType, agentCode) 校验业务分类，
     * 单一用途接口（角色图 / 道具图 / 场景图）按 agentCode 直接断言 biz_category_code。
     */
    @Autowired
    private IAidAgentService aidAgentService;

    /** SKU 统一计费引擎 */
    @Autowired
    private BillingAmountCalculator billingAmountCalculator;
    @Autowired
    private BillingQuoteAssembler billingQuoteAssembler;

    @Autowired
    private BillingPriceMultiplierService billingPriceMultiplierService;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    /** 首提交业务数据事务；账户冻结必须在该事务提交后执行。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 角色隐藏风格快照解析，仅用于角色形态图与角色设定卡。 */
    @Autowired
    private ProjectStyleSnapshotService projectStyleSnapshotService;

    /** 媒体URL拼接器：DB相对路径 → 完整URL，用于参考图传给媒体服务 */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    /** 角色音色自动匹配绑定：角色提取成功后为自动提取角色按性别/年龄自动绑定音色 */
    @Autowired
    private com.aid.rps.voice.service.IRoleVoiceAutoBindService roleVoiceAutoBindService;
    @PreDestroy
    public void shutdown()
    {
        log.info("关闭资产提取线程池...");
        ExecutorService exec = extractExecutor;
        if (exec == null) {
            return;
        }
        exec.shutdown();
        try
        {
            if (!exec.awaitTermination(30, TimeUnit.SECONDS))
            {
                exec.shutdownNow();
                log.warn("资产提取线程池强制关闭");
            }
        }
        catch (InterruptedException e)
        {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 构建资产提取只读提交计划，正式任务与quote共用。 */
    private ExtractSubmissionPlan prepareExtractSubmissionPlan(AssetExtractRequest request, Long userId)
    {
        if (request == null || userId == null)
        {
            throw new ServiceException("参数错误");
        }
        AidComicProject project = validateProject(request.getProjectId(), userId);
        requireProjectStyle(project);
        List<String> requestedTypes = request.getExtractTypes();
        if (CollectionUtil.isEmpty(requestedTypes))
        {
            throw new ServiceException("提取类型不能为空");
        }
        if (requestedTypes.size() > 3)
        {
            throw new ServiceException("提取类型过多");
        }
        validateAgentCodesAgainstExtractTypes(requestedTypes, request.getAgentCodes());
        Map<String, String> modelCodesByType = resolveExtractModelCodesByType(
                request.getProjectId(), userId, requestedTypes,
                request.getAgentCodes(), request.getModelCodes());
        String primaryModelCode = pickPrimaryModelCode(modelCodesByType);
        List<String> extractTypes = resolveExtractTypesFromRequest(requestedTypes);
        String extractScope = resolveExtractScope(
                request.getExtractScope(), project.getProjectType(), extractTypes);
        Long episodeId = resolveEpisodeId(
                request.getEpisodeId(), project, extractTypes, extractScope);
        if (Objects.equals(EXTRACT_SCOPE_PROJECT_FULL, extractScope))
        {
            if (StrUtil.isBlank(helper.loadAllScriptsContent(request.getProjectId(), userId)))
            {
                throw new ServiceException("剧本内容不能为空");
            }
        }
        else if (StrUtil.isBlank(helper.loadScriptContent(request.getProjectId(), episodeId, userId)))
        {
            throw new ServiceException("剧本内容不能为空");
        }
        ExtractPlanningContext context = new ExtractPlanningContext(
                request.getProjectId(), episodeId, userId, extractScope,
                Map.copyOf(request.getAgentCodes()));
        ExtractBillingPlan billingPlan = buildExtractBillingPlan(
                context, List.copyOf(extractTypes), Map.copyOf(modelCodesByType));
        return new ExtractSubmissionPlan(project, List.copyOf(extractTypes),
                Map.copyOf(modelCodesByType), primaryModelCode, extractScope,
                episodeId, context, billingPlan);
    }

    private record ExtractSubmissionPlan(AidComicProject project, List<String> extractTypes,
            Map<String, String> modelCodesByType, String primaryModelCode,
            String extractScope, Long episodeId, ExtractPlanningContext planningContext,
            ExtractBillingPlan billingPlan)
    {
    }

    @Override
    public BillingQuoteVO quoteExtractAssets(AssetExtractRequest request, Long userId)
    {
        ExtractSubmissionPlan submission = prepareExtractSubmissionPlan(request, userId);
        List<BillingQuoteVO> items = new ArrayList<>();
        for (ExtractModelBillingPlan model : submission.billingPlan().models())
        {
            for (ExtractCallBillingEstimate call : model.calls())
            {
                items.add(billingQuoteAssembler.single(
                        "ASSET_EXTRACT", call.modelConfig(), call.result(), 1));
            }
        }
        return billingQuoteAssembler.aggregate("ASSET_EXTRACT", items);
    }

    @Override
    public AssetExtractTaskVO extractAssets(AssetExtractRequest request, Long userId)
    {
        ExtractSubmissionPlan submission = prepareExtractSubmissionPlan(request, userId);
        List<String> extractTypes = submission.extractTypes();
        Map<String, String> modelCodesByType = submission.modelCodesByType();
        String primaryModelCode = submission.primaryModelCode();
        String extractScope = submission.extractScope();
        Long episodeId = submission.episodeId();
        ExtractBillingPlan billingPlan = submission.billingPlan();

        BatchTaskSlotReservation slotReservation = batchTaskSlotService.acquireTaskSlots(
                request.getProjectId(), episodeId, logicalExtractTypes(extractTypes));

        // task 和 billingPrepared 需在 try 外声明，供 catch 引用
        AidExtractTask task = null;
        boolean billingPrepared = false;
        try
        {
            // 先仅持久化任务，保证账户独立冻结后始终能按 task + trace 补偿。
            List<String> committedExtractTypes = extractTypes;
            task = transactionTemplate.execute(status -> {
                // 与项目风格切换共用项目主行锁；锁内复核风格并插入任务，事务提交后才释放。
                AidComicProject lockedProject = lockProjectForExtract(request.getProjectId(), userId);
                requireProjectStyle(lockedProject);
                return createTaskRecord(request.getProjectId(), episodeId, userId, committedExtractTypes,
                        request.getAgentCodes(), modelCodesByType, primaryModelCode, extractScope,
                        slotReservation, Boolean.TRUE.equals(request.getOverwrite()));
            });
            if (task == null)
            {
                throw new ServiceException("任务创建失败");
            }

            // 任务级预冻结计费：按实际参与的模型分别估价再汇总，避免混合模型按主模型误计费。
            long usageStartMediaTaskId = extractBillingService.findLatestExtractMediaTaskId(task.getId());
            ExtractBillingEstimate billingEstimate = serializeExtractBillingPlan(
                    billingPlan, usageStartMediaTaskId);
            extractBillingService.prepareBilling(task.getId(), userId,
                    billingEstimate.amount(), billingEstimate.snapshotJson());
            billingPrepared = true;

            // task、冻结意图和计费快照均已独立持久化，账户冻结成功后才允许入队。
            // 这样即使进程在任一步退出，补偿任务也能从 task + trace 找回并安全退款。
            boolean enqueued = enqueueExtractAfterCommit(task.getId(), request.getProjectId(), episodeId,
                    userId, primaryModelCode, isMqEnabled());
            if (!enqueued)
            {
                AidExtractTask current = extractTaskService.selectAidExtractTaskById(task.getId());
                return AssetExtractTaskVO.builder()
                        .taskId(task.getId())
                        .status(current == null ? TASK_STATUS_FAILED : current.getStatus())
                        .build();
            }

            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .build();
        }
        catch (RuntimeException e)
        {
            if (task != null)
            {
                // task 已先于账户资金动作持久化；同步异常必须收口，不留下无消费者的 PENDING。
                log.error("提取任务提交异常, taskId={}", task.getId(), e);
                AidExtractTask current = extractTaskService.selectAidExtractTaskById(task.getId());
                String expectedTraceId = current == null ? null : current.getBillingTraceId();
                if (StrUtil.isNotBlank(expectedTraceId))
                {
                    updateTaskFailed(task.getId(), expectedTraceId, e.getMessage());
                }
                else
                {
                    updateTaskFailed(task.getId(), null, e.getMessage());
                }
                batchTaskSlotService.releaseForTask(
                        extractTaskService.selectAidExtractTaskById(task.getId()));
                if (billingPrepared)
                {
                    try
                    {
                        extractBillingService.refundBilling(task.getId(), userId, expectedTraceId);
                    }
                    catch (Exception refundEx)
                    {
                        log.error("退款失败，等待补偿, taskId={}", task.getId(), refundEx);
                    }
                    return AssetExtractTaskVO.builder()
                            .taskId(task.getId())
                            .status(TASK_STATUS_FAILED)
                            .build();
                }
            }
            else
            {
                batchTaskSlotService.release(slotReservation);
            }
            throw e;
        }
    }

    private List<String> logicalExtractTypes(List<String> extractTypes)
    {
        List<String> logicalTypes = new ArrayList<>();
        for (String extractType : extractTypes)
        {
            if (Objects.equals(EXTRACT_TYPE_CHARACTER, extractType))
            {
                logicalTypes.add(BatchTaskLogicalType.ASSET_EXTRACT_CHARACTER);
            }
            else if (Objects.equals(EXTRACT_TYPE_SCENE, extractType))
            {
                logicalTypes.add(BatchTaskLogicalType.ASSET_EXTRACT_SCENE);
            }
            else if (Objects.equals(EXTRACT_TYPE_PROP, extractType))
            {
                logicalTypes.add(BatchTaskLogicalType.ASSET_EXTRACT_PROP);
            }
        }
        return logicalTypes;
    }

    /**
     * 两阶段流水线提取核心方法
     * 阶段一(15-55%)：角色提取 — 大切片、顺序处理、累积去重、生成视觉描述
     * 阶段二(55-85%)：场景+道具提取 — 小切片、片内并行、累积去重、道具描述清洗
     */
    @Override
    public List<RpsAssetVO> doExtract(Long taskId, Long projectId, Long episodeId, Long userId)
    {
        return doExtract(taskId, projectId, episodeId, userId, null);
    }

    @Override
    public List<RpsAssetVO> doExtract(Long taskId, Long projectId, Long episodeId, Long userId,
                                      String dispatchToken)
    {
        List<String> extractTypes = resolveExtractTypes(taskId);
        // 覆盖删除由已领取的 worker 执行，并和 inputSnapshot 的幂等标记处于同一事务。
        // 入队失败不会再提前删除旧素材，续跑也不会重复删除上一轮已生成结果。
        applyOverwriteOnce(taskId, projectId, episodeId, extractTypes, dispatchToken);
        boolean needCharacter = extractTypes.contains(EXTRACT_TYPE_CHARACTER);
        boolean needScene = extractTypes.contains(EXTRACT_TYPE_SCENE);
        boolean needProp = extractTypes.contains(EXTRACT_TYPE_PROP);

        //    - task.modelCode 字段保留主模型，仅作为兜底；真实 LLM 调用按 type 取 modelCodesByType 中的对应值
        String fallbackModelCode = resolveModelCode(taskId);
        Map<String, String> modelCodesByType = resolveExtractModelCodesByTypeFromSnapshot(taskId, fallbackModelCode);
        String characterModelCode = modelCodesByType.getOrDefault(EXTRACT_TYPE_CHARACTER, fallbackModelCode);
        String sceneModelCode = modelCodesByType.getOrDefault(EXTRACT_TYPE_SCENE, fallbackModelCode);
        String propModelCode = modelCodesByType.getOrDefault(EXTRACT_TYPE_PROP, fallbackModelCode);

        // 按资产类型解析智能体编码。
        Map<String, String> agentCodes = resolveAgentCodes(taskId);

        sseManager.sendStepProgress(taskId, "init", 5, "init", "初始化提取任务", 1, 3);

        AidComicProject project = projectService.selectAidComicProjectById(projectId);

        sseManager.sendStepProgress(taskId, "loading_assets", 10, "load_assets", "加载已有资产库", 1, 1);
        // 去重库按项目级加载：场景以“地点_时间”作为资产身份，跨集命中同名资产时只新增剧情引用。
        ExistingAssetLib lib = helper.loadExistingAssets(projectId, null);

        RollingExtractProgress rollingProgress = loadRollingExtractProgress(taskId, dispatchToken);
        // MEDIA_TASK 重投/续生必须返回累计结果；资产主键来自与业务落库同事务提交的调用水位，
        // 再从主表重建 VO，避免仅依赖上一轮内存或可能尚未写入的 resultData。
        List<RpsAssetVO> allAssets = rollingProgress.enabled
                ? loadRollingCompletedAssets(rollingProgress, projectId, userId)
                : new ArrayList<>();

        if (needCharacter && !isTaskCancelled(taskId))
        {
            String characterAgentCode = resolveAgentCodeForType(agentCodes, EXTRACT_TYPE_CHARACTER);
            if (Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType()))
            {
                // 剧集角色主资产恒落项目级（episodeId=0，跨集唯一）；范围决定分析的剧本区间
                String extractScope = resolveExtractScopeFromTask(taskId);
                if (Objects.equals(EXTRACT_SCOPE_PROJECT_FULL, extractScope))
                {
                    // 全量初始化：按10集/组分批扫描全项目剧本（先总后分）
                    extractSeriesCharacters(taskId, projectId, CHARACTER_GLOBAL_EPISODE_ID, userId,
                            allAssets, characterModelCode, lib, characterAgentCode, rollingProgress);
                }
                else
                {
                    // 日常增量：只分析当前集剧本，参考全项目角色目录做去重/合并/形态增量
                    extractEpisodeIncrementalCharacters(taskId, projectId, episodeId, userId,
                            allAssets, characterModelCode, lib, characterAgentCode, rollingProgress);
                }
            }
            else
            {
                // 电影模式：单集剧本直接提取
                sseManager.sendStepProgress(taskId, "loading_script_char", 12, "load_script_char", "加载角色剧本", 1, 1);
                String characterScript = resolveCharacterScript(project, userId);
                if (StrUtil.isBlank(characterScript))
                {
                    throw new ServiceException("剧本内容不能为空");
                }
                extractCharacters(taskId, characterScript, lib, projectId, episodeId, userId,
                        allAssets, characterModelCode, characterAgentCode, rollingProgress);
            }
        }

        // ★ 角色提取完成后：为本剧集「自动提取角色」按性别/年龄自动匹配并绑定音色。
        //   失败仅记录日志，绝不影响提取主流程（音色绑定是增值动作，非提取硬依赖）。
        if (needCharacter && !isTaskCancelled(taskId))
        {
            try
            {
                roleVoiceAutoBindService.autoBindForEpisode(projectId, episodeId, userId);
            }
            catch (Exception autoBindEx)
            {
                log.error("角色音色自动绑定失败（不影响提取）: projectId={}, episodeId={}, userId={}, err={}",
                        projectId, episodeId, userId, autoBindEx.getMessage(), autoBindEx);
            }
        }

        // ★ 取消检查点：角色阶段完成后，检查是否需要跳过场景+道具阶段
        if (isTaskCancelled(taskId))
        {
            log.info("提取任务被用户取消（角色阶段后），已提取{}个资产: taskId={}", allAssets.size(), taskId);
            return allAssets;
        }

        boolean extractScenesAndPropsSuccess = true;   // 默认全部成功
        if (needScene || needProp)
        {
            sseManager.sendStepProgress(taskId, "loading_script_sp", 53, "load_script_sp", "加载场景道具剧本", 1, 1);
            String scenePropScript = helper.loadScriptContent(projectId, episodeId, userId);
            if (StrUtil.isBlank(scenePropScript))
            {
                throw new ServiceException("剧本内容不能为空");
            }
            String sceneAgentCode = needScene ? resolveAgentCodeForType(agentCodes, EXTRACT_TYPE_SCENE) : null;
            String propAgentCode = needProp ? resolveAgentCodeForType(agentCodes, EXTRACT_TYPE_PROP) : null;
            extractScenesAndPropsSuccess = extractScenesAndProps(taskId, scenePropScript, lib, projectId, episodeId, userId, allAssets,
                    needScene, needProp, sceneModelCode, propModelCode, sceneAgentCode, propAgentCode,
                    rollingProgress);
        }

        // ★ 取消检查点：场景+道具阶段完成后
        if (isTaskCancelled(taskId))
        {
            log.info("提取任务被用户取消（场景道具阶段后），已提取{}个资产: taskId={}", allAssets.size(), taskId);
            return allAssets;
        }

        // 场景道具部分失败时，不标记剧本已提取（允许续跑），抛 PartialExtractionException 让 Consumer 标记 PARTIAL_FAILED
        if (!extractScenesAndPropsSuccess)
        {
            log.warn("资产提取部分完成（场景道具阶段有 chunk 失败）: projectId={}, 已提取{}个资产, 类型={}",
                    projectId, allAssets.size(), extractTypes);
            sseManager.sendProgress(taskId, "partial", 85, "提取部分完成，可继续生成");
            throw new PartialExtractionException("提取部分完成，可继续生成", allAssets);
        }

        helper.markScriptExtracted(projectId, episodeId, userId);

        sseManager.sendProgress(taskId, "done", 98, "提取完成");
        log.info("资产提取完成: projectId={}, 共提取{}个资产, 类型={}", projectId, allAssets.size(), extractTypes);
        return allAssets;
    }
    /**
     * 解析提取范围：电影恒为整部剧本（返回增量口径占位）；剧集默认增量，
     * PROJECT_FULL 仅支持角色（场景/道具资产按集归属，全量扫描无归属集）。
     *
     * @param requestScope 请求范围（可空）
     * @param projectType  项目类型
     * @param extractTypes 提取类型
     * @return 生效范围（EPISODE_INCREMENTAL / PROJECT_FULL）
     */
    private String resolveExtractScope(String requestScope, String projectType, List<String> extractTypes)
    {
        if (!Objects.equals(PROJECT_TYPE_SERIES, projectType))
        {
            // 电影无范围概念：恒按整部电影剧本（episodeId=0 单集语义）
            return EXTRACT_SCOPE_EPISODE_INCREMENTAL;
        }
        String scope = StrUtil.blankToDefault(requestScope, EXTRACT_SCOPE_EPISODE_INCREMENTAL)
                .trim().toUpperCase();
        if (!Objects.equals(EXTRACT_SCOPE_EPISODE_INCREMENTAL, scope)
                && !Objects.equals(EXTRACT_SCOPE_PROJECT_FULL, scope))
        {
            log.info("提取范围非法: scope={}", requestScope);
            throw new ServiceException("提取范围有误");
        }
        boolean hasSceneOrProp = extractTypes.contains(EXTRACT_TYPE_SCENE)
                || extractTypes.contains(EXTRACT_TYPE_PROP);
        if (Objects.equals(EXTRACT_SCOPE_PROJECT_FULL, scope) && hasSceneOrProp)
        {
            // 场景/道具资产按集归属，全量扫描无法归集，必须走增量
            log.info("全量扫描仅支持角色: extractTypes={}", extractTypes);
            throw new ServiceException("全量仅支持角色");
        }
        return scope;
    }

    /**
     * 校验智能体编码：。
     */
    private void validateAgentCodesAgainstExtractTypes(List<String> extractTypes,
                                                       Map<String, String> agentCodes)
    {
        if (agentCodes == null || agentCodes.isEmpty())
        {
            log.error("公用并行提取参数校验失败: agentCodes 为空");
            throw new ServiceException("智能体编码不能为空");
        }
        // key 集合必须严格一致
        Set<String> typeSet = new TreeSet<>(extractTypes);
        Set<String> codeKeySet = new TreeSet<>(agentCodes.keySet());
        if (!Objects.equals(typeSet, codeKeySet))
        {
            log.error("公用并行提取参数校验失败: extractTypes 与 agentCodes 不匹配, types={}, codes={}",
                    typeSet, codeKeySet);
            throw new ServiceException("智能体与类型不匹配");
        }
        // 每个 (type, agentCode) 业务分类断言
        for (String type : extractTypes)
        {
            String agentCode = agentCodes.get(type);
            if (StrUtil.isBlank(agentCode))
            {
                log.error("公用并行提取参数校验失败: type={} 对应 agentCode 为空", type);
                throw new ServiceException("智能体编码不能为空");
            }
            String expectedCategory = mapExtractTypeToBizCategory(type);
            if (StrUtil.isBlank(expectedCategory))
            {
                log.error("公用并行提取参数校验失败: 未知 extractType={}", type);
                throw new ServiceException("提取类型无效: " + type);
            }
            AidAgent agent = aidAgentService.getByAgentCode(agentCode);
            if (Objects.isNull(agent) || !Objects.equals(1, agent.getStatus())
                    || !Objects.equals(expectedCategory, agent.getBizCategoryCode()))
            {
                log.error("公用并行提取参数校验失败: 智能体业务分类不匹配, type={}, expected={}, agentCode={}, actualCategory={}",
                        type, expectedCategory, agentCode,
                        agent == null ? null : agent.getBizCategoryCode());
                throw new ServiceException("智能体业务分类不匹配: " + type + " -> " + agentCode);
            }
        }
    }

    /**
     * 提取类型 → 业务分类编码 强映射。
     */
    private static String mapExtractTypeToBizCategory(String extractType)
    {
        if (EXTRACT_TYPE_CHARACTER.equals(extractType))
        {
            return BIZ_CATEGORY_MAIN_CHARACTER_EXTRACT;
        }
        if (EXTRACT_TYPE_SCENE.equals(extractType))
        {
            return BIZ_CATEGORY_MAIN_SCENE_EXTRACT;
        }
        if (EXTRACT_TYPE_PROP.equals(extractType))
        {
            return BIZ_CATEGORY_MAIN_PROP_EXTRACT;
        }
        return null;
    }

    /**
     * 校验模型是否存在可用（必须存在于数据库，且模型类型为text）
     */
    private void validateModel(String modelCode)
    {
        if (StrUtil.isBlank(modelCode))
        {
            log.info("模型编码为空");
            throw new ServiceException("模型不能为空");
        }
        // 校验性查询，只查必要字段
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.info("模型不存在或不可用: modelCode={}", modelCode);
            throw new ServiceException("模型不存在");
        }
        // 校验模型类型必须为text（本接口为LLM文本生成）
        if (!Objects.equals(MODEL_TYPE_TEXT, modelConfig.getModelType()))
        {
            log.info("模型类型不匹配: modelCode={}, modelType={}", modelCode, modelConfig.getModelType());
            throw new ServiceException("模型不可用");
        }
    }

    /**
     * 提取接口专用模型解析：。
     *
     * @param requestModelCode 用户传入的 modelCode（可空）
     * @param agentCodes       前端传入的 agentCodes 映射（key=extractType, value=agentCode）
     * @return 最终生效的 modelCode
     */
    /**
     * 3 类各自独立按 sceneCode 走 3 级链路解析模型编码。
     *
     * @param projectId       项目ID
     * @param userId          用户ID
     * @param extractTypes    本次提取类型列表
     * @param agentCodes      用户传入的智能体编码 Map（key=extractType）
     * @param requestedModels 用户传入的模型编码 Map（key=extractType，可空）
     * @return 解析后的 Map
     */
    private Map<String, String> resolveExtractModelCodesByType(Long projectId, Long userId,
                                                               List<String> extractTypes,
                                                               Map<String, String> agentCodes,
                                                               Map<String, String> requestedModels)
    {
        Map<String, String> result = new LinkedHashMap<>();
        for (String type : extractTypes)
        {
            ProjectGenConfigScene scene = mapExtractTypeToScene(type);
            if (scene == null)
            {
                log.error("提取模型解析失败: 未知 extractType={}", type);
                throw new ServiceException("提取类型不支持");
            }
            String reqAgent = agentCodes != null ? StrUtil.trim(agentCodes.get(type)) : null;
            String reqModel = requestedModels != null ? StrUtil.trim(requestedModels.get(type)) : null;
            com.aid.projectgenconfig.service.ResolvedSceneConfig resolved =
                    projectGenConfigResolver.resolve(projectId, userId, scene,
                            reqAgent, reqModel, null, null);
            result.put(type, resolved.getModelCode());
        }
        return result;
    }

    /** 映射提取类型 → ProjectGenConfigScene 枚举（main_*_extract）。 */
    private static ProjectGenConfigScene mapExtractTypeToScene(String extractType)
    {
        if (EXTRACT_TYPE_CHARACTER.equals(extractType))
        {
            return ProjectGenConfigScene.CHARACTER_EXTRACT;
        }
        if (EXTRACT_TYPE_SCENE.equals(extractType))
        {
            return ProjectGenConfigScene.SCENE_EXTRACT;
        }
        if (EXTRACT_TYPE_PROP.equals(extractType))
        {
            return ProjectGenConfigScene.PROP_EXTRACT;
        }
        return null;
    }

    /**
     * 取主模型编码：character 优先，否则 scene，否则 prop，否则空字符串。
     * 用于父任务 task.modelCode 字段（路由展示 / 旧任务兜底），不影响实际 LLM 调用按 type 选模型。
     */
    private String pickPrimaryModelCode(Map<String, String> modelCodesByType)
    {
        if (modelCodesByType == null || modelCodesByType.isEmpty())
        {
            return "";
        }
        String c = modelCodesByType.get(EXTRACT_TYPE_CHARACTER);
        if (StrUtil.isNotBlank(c))
        {
            return c;
        }
        String s = modelCodesByType.get(EXTRACT_TYPE_SCENE);
        if (StrUtil.isNotBlank(s))
        {
            return s;
        }
        String p = modelCodesByType.get(EXTRACT_TYPE_PROP);
        if (StrUtil.isNotBlank(p))
        {
            return p;
        }
        return "";
    }

    /**
     * @deprecated /parallel 的模型解析由 {@link #resolveExtractModelCodesByType(Long, Long, List, Map, Map)}
     * 按 3 类各自独立走 3 级兜底链路完成，本方法仅保留。
     */
    @Deprecated
    private String resolveExtractModelCode(String requestModelCode, Map<String, String> agentCodes)
    {
        // 取第一个 agentCode 用于解析：
        //   - 默认模型来源
        //   - 业务分类编码（即 funcCode），用于校验用户指定模型是否在功能可选池内
        AidAgent firstAgent = null;
        String firstAgentCode = null;
        if (agentCodes != null && !agentCodes.isEmpty())
        {
            firstAgentCode = agentCodes.values().iterator().next();
            firstAgent = aidAgentService.getByAgentCode(firstAgentCode);
        }

        // 优先级 1：用户显式传入
        if (StrUtil.isNotBlank(requestModelCode))
        {
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(requestModelCode);
            if (Objects.isNull(modelConfig))
            {
                log.info("用户指定模型不可用: requestModelCode={}", requestModelCode);
                throw new ServiceException("模型不存在");
            }
            if (!Objects.equals(MODEL_TYPE_TEXT, modelConfig.getModelType()))
            {
                log.info("用户指定模型类型不匹配: requestModelCode={}, actualType={}", requestModelCode, modelConfig.getModelType());
                throw new ServiceException("模型不可用");
            }
            // 业务分类池校验：智能体若配置了 bizCategoryCode（= funcCode），
            // 用户传入的模型必须落在该功能配置的可选池里。
            assertModelInBizCategoryPool(requestModelCode, modelConfig.getId(), firstAgent, firstAgentCode);
            log.info("提取模型解析: 用户指定模型生效, modelCode={}", requestModelCode);
            return requestModelCode;
        }

        // 优先级 2：从 agentCodes 中取第一个 agent 的 model_code
        if (firstAgent != null && StrUtil.isNotBlank(firstAgent.getModelCode()))
        {
            String agentModelCode = firstAgent.getModelCode();
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(agentModelCode);
            if (Objects.isNull(modelConfig))
            {
                log.error("智能体配置的模型不可用: agentCode={}, modelCode={}", firstAgentCode, agentModelCode);
                throw new ServiceException("模型不存在");
            }
            if (!Objects.equals(MODEL_TYPE_TEXT, modelConfig.getModelType()))
            {
                log.error("智能体配置的模型类型不匹配: agentCode={}, modelCode={}, actualType={}",
                        firstAgentCode, agentModelCode, modelConfig.getModelType());
                throw new ServiceException("模型不可用");
            }
            // 业务分类池校验
            assertModelInBizCategoryPool(agentModelCode, modelConfig.getId(), firstAgent, firstAgentCode);
            log.info("提取模型解析: 智能体默认模型生效, agentCode={}, modelCode={}", firstAgentCode, agentModelCode);
            return agentModelCode;
        }

        // 都没有 → 报错
        log.error("提取模型解析失败: 用户未传 modelCode 且智能体未配置默认模型, agentCodes={}", agentCodes);
        throw new ServiceException("模型未配置");
    }

    /**
     * 业务分类池校验：智能体的 {@code biz_category_code} 直接等于 {@code aid_ai_model_func_config.func_code}，。
     */
    private void assertModelInBizCategoryPool(String modelCode, Long modelId, AidAgent agent, String agentCode)
    {
        if (agent == null || StrUtil.isBlank(agent.getBizCategoryCode()))
        {
            // 老数据兼容：bizCategoryCode 为空时跳过池校验，仅依赖前面的"模型存在 + 类型匹配"校验
            return;
        }
        String funcCode = agent.getBizCategoryCode();
        List<AiModelVO> pool = aiModelBusinessService.listAvailableModelsByFuncCode(funcCode);
        if (CollectionUtil.isEmpty(pool))
        {
            // 区分两种情况，文案不同
            // listAvailableModelsByFuncCode 在以下场景都返回空：funcCode 不存在 / 已停用 / modelIds 为空 / 池内模型全部失效
            // 用日志记录细节，对外用一个统一短文案
            log.error("提取模型解析失败: 业务分类对应的功能配置不存在或可选模型为空, agentCode={}, bizCategoryCode={}, modelCode={}",
                    agentCode, funcCode, modelCode);
            throw new ServiceException("模型未配置");
        }
        boolean inPool = pool.stream().anyMatch(m -> Objects.equals(modelId, m.getId()));
        if (!inPool)
        {
            log.error("提取模型解析失败: 模型不在业务分类可选池内, agentCode={}, bizCategoryCode={}, modelCode={}, poolSize={}",
                    agentCode, funcCode, modelCode, pool.size());
            throw new ServiceException("模型不可用");
        }
    }

    /**
     * 图片场景模型解析（用于 form/generate-image / form/generate-card-image / form/generate 角色形态）。
     *
     * @param requestModelCode 用户传入的 modelCode（可空）
     * @param agent            智能体（必须非空，调用方先做过 assertBizCategory 断言）
     * @param expectedType     期望模型类型（{@code text} / {@code image}）
     * @return 最终生效的 modelCode
     */
    private String resolveAgentScopedModel(String requestModelCode, AidAgent agent, String expectedType)
    {
        if (Objects.isNull(agent))
        {
            // 防御：理论上 caller 已断言过 agent 非空
            log.error("解析智能体模型失败: agent 为空");
            throw new ServiceException("智能体不存在");
        }

        // 优先级 1：用户显式传入
        if (StrUtil.isNotBlank(requestModelCode))
        {
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(requestModelCode);
            if (Objects.isNull(modelConfig))
            {
                log.info("用户指定模型不可用: agentCode={}, requestModelCode={}", agent.getAgentCode(), requestModelCode);
                throw new ServiceException("模型不存在");
            }
            if (!Objects.equals(expectedType, modelConfig.getModelType()))
            {
                log.info("用户指定模型类型不匹配: agentCode={}, requestModelCode={}, actualType={}, expected={}",
                        agent.getAgentCode(), requestModelCode, modelConfig.getModelType(), expectedType);
                throw new ServiceException("模型不可用");
            }
            assertModelInBizCategoryPool(requestModelCode, modelConfig.getId(), agent, agent.getAgentCode());
            log.info("智能体模型解析: 用户指定模型生效, agentCode={}, modelCode={}",
                    agent.getAgentCode(), requestModelCode);
            return requestModelCode;
        }

        // 优先级 2：智能体默认模型
        String agentModelCode = agent.getModelCode();
        if (StrUtil.isBlank(agentModelCode))
        {
            log.error("智能体未配置默认模型: agentCode={}", agent.getAgentCode());
            throw new ServiceException("模型未配置");
        }
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(agentModelCode);
        if (Objects.isNull(modelConfig))
        {
            log.error("智能体配置的模型不可用: agentCode={}, modelCode={}", agent.getAgentCode(), agentModelCode);
            throw new ServiceException("模型不存在");
        }
        if (!Objects.equals(expectedType, modelConfig.getModelType()))
        {
            log.error("智能体配置的模型类型不匹配: agentCode={}, modelCode={}, actualType={}, expected={}",
                    agent.getAgentCode(), agentModelCode, modelConfig.getModelType(), expectedType);
            throw new ServiceException("模型不可用");
        }
        assertModelInBizCategoryPool(agentModelCode, modelConfig.getId(), agent, agent.getAgentCode());
        log.info("智能体模型解析: 智能体默认模型生效, agentCode={}, modelCode={}",
                agent.getAgentCode(), agentModelCode);
        return agentModelCode;
    }

    /**
     * 校验图片模型存在且类型为 image
     */
    private void validateImageModel(String modelCode)
    {
        if (StrUtil.isBlank(modelCode))
        {
            log.info("图片模型编码为空");
            throw new ServiceException("模型不能为空");
        }
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            log.info("图片模型不存在或不可用: modelCode={}", modelCode);
            throw new ServiceException("模型不存在");
        }
        if (!Objects.equals(MODEL_TYPE_IMAGE, modelConfig.getModelType()))
        {
            log.info("图片模型类型不匹配: modelCode={}, modelType={}", modelCode, modelConfig.getModelType());
            throw new ServiceException("模型不可用");
        }
    }

    /**
     * 按预计的每一次 LLM 调用分别匹配 SKU 后汇总预冻结金额。
     * 单次调用区间必须保持独立，禁止把多个切片的 token/字符合并后命中某一个档位。
     */
    private ExtractModelBillingEstimate estimateExtractCost(Long taskId, String modelCode,
                                                             List<String> extractTypes)
    {
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            return new ExtractModelBillingEstimate(BigDecimal.ZERO, List.of(
                    new ExtractCallBillingEstimate("unknown", null,
                            BillingCalcResult.fixed(BigDecimal.ZERO, null), modelConfig)));
        }

        ExtractInputEstimate inputEstimate = estimateExtractInput(taskId, extractTypes, modelCode);
        return estimateExtractCostFromInput("taskId=" + taskId, modelConfig, inputEstimate);
    }

    private ExtractModelBillingEstimate estimateExtractCost(ExtractPlanningContext context,
            String modelCode, List<String> extractTypes)
    {
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (Objects.isNull(modelConfig))
        {
            throw new ServiceException("模型不存在");
        }
        ExtractInputEstimate inputEstimate = estimateExtractInput(context, extractTypes, modelCode);
        return estimateExtractCostFromInput("quote", modelConfig, inputEstimate);
    }

    private ExtractModelBillingEstimate estimateExtractCostFromInput(String trace,
            AiModelConfigVo modelConfig, ExtractInputEstimate inputEstimate)
    {
        List<ExtractCallBillingEstimate> callEstimates = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (ExtractCallInputEstimate callInput : inputEstimate.calls())
        {
            AiModelConfigVo callModel = aiModelConfigService.selectForBusiness(modelConfig.getModelCode(),
                    mapExtractTypeToBizCategory(callInput.extractType()), null);
            if (callModel == null) {
                log.info("提取报价的业务模型不可用: modelCode={}, extractType={}", modelConfig.getModelCode(), callInput.extractType());
                throw new ServiceException("业务模型不可用");
            }
            BillingCalcResult result = estimateExtractCallCost(trace, callModel, callInput.inputChars());
            callEstimates.add(new ExtractCallBillingEstimate(
                    callInput.extractType(), callInput.callSlot(), result, callModel));
            totalAmount = totalAmount.add(defaultAmount(result.getAmount()));
        }
        log.info("逐调用预估提取费用完成: trace={}, modelCode={}, calls={}, amount={}",
                trace, modelConfig.getModelCode(), callEstimates.size(), totalAmount);
        return new ExtractModelBillingEstimate(totalAmount, callEstimates);
    }

    /** 单次调用使用统一计费引擎估价，未命中时只对本次调用按固定价兜底。 */
    private BillingCalcResult estimateExtractCallCost(String trace, AiModelConfigVo modelConfig, int inputChars)
    {
        int estimatedOutputChars = BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS;
        Map<String, Object> params = new HashMap<>();
        params.put("inputChars", inputChars);
        params.put("inputTokens", BillingConstants.charsToTokens(inputChars));
        params.put("outputTokens", BillingConstants.charsToTokens(estimatedOutputChars));
        params.put("estimatedOutputChars", estimatedOutputChars);
        params.put("totalChars", inputChars + estimatedOutputChars);
        helper.applyLlmBillingLimits(params, 1);
        BillingCalcResult result = billingAmountCalculator.calculatePreHoldAmount(
                modelConfig, new BillingInput("TEXT", params));
        if (result.isMatched())
        {
            MeterType meterType = Objects.isNull(result.getSnapshot())
                    ? null : MeterType.of(result.getSnapshot().getMeterType());
            boolean fixedBilling = BillingMode.of(modelConfig.getBillingMode()) == BillingMode.FIXED;
            if (!fixedBilling && Objects.nonNull(meterType)
                    && meterType != MeterType.TOKEN && meterType != MeterType.SKU_PACKAGE)
            {
                log.error("资产提取计费口径错误: trace={}, modelCode={}, meterType={}",
                        trace, modelConfig.getModelCode(), meterType);
                throw new ServiceException("计费配置错误");
            }
            return result;
        }

        BigDecimal rawUnitPrice = Objects.nonNull(modelConfig.getCostCredits())
                ? modelConfig.getCostCredits() : BigDecimal.ZERO;
        BigDecimal unitPrice = billingPriceMultiplierService.apply(
                rawUnitPrice, modelConfig.getBillingMultiplier());
        if (Boolean.TRUE.equals(modelConfig.getIsFree()))
        {
            return buildFreeFixedFallback(modelConfig, params, rawUnitPrice, unitPrice, MeterType.TOKEN);
        }
        log.warn("提取单次调用SKU未命中，按固定价兜底: trace={}, modelCode={}, inputChars={}, amount={}",
                trace, modelConfig.getModelCode(), inputChars, unitPrice);
        return BillingCalcResult.fixed(unitPrice, null);
    }

    private record ExtractCallBillingEstimate(String extractType, String callSlot,
                                              BillingCalcResult result, AiModelConfigVo modelConfig)
    {
    }

    private record ExtractModelBillingEstimate(BigDecimal amount,
                                               List<ExtractCallBillingEstimate> callEstimates)
    {
    }

    /** 构建资产提取的只读逐模型、逐调用计费计划。 */
    private ExtractBillingPlan buildExtractBillingPlan(ExtractPlanningContext context,
            List<String> extractTypes, Map<String, String> modelCodesByType)
    {
        Map<String, List<String>> extractTypesByModel = new LinkedHashMap<>();
        for (String extractType : extractTypes)
        {
            String modelCode = modelCodesByType.get(extractType);
            if (StrUtil.isBlank(modelCode))
            {
                throw new ServiceException("模型不能为空");
            }
            extractTypesByModel.computeIfAbsent(modelCode, key -> new ArrayList<>()).add(extractType);
        }
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalCalls = 0;
        List<ExtractModelBillingPlan> models = new ArrayList<>(extractTypesByModel.size());
        for (Map.Entry<String, List<String>> entry : extractTypesByModel.entrySet())
        {
            AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(entry.getKey());
            if (modelConfig == null)
            {
                throw new ServiceException("模型不存在");
            }
            ExtractModelBillingEstimate estimate = estimateExtractCost(
                    context, entry.getKey(), entry.getValue());
            totalCalls += estimate.callEstimates().size();
            if (totalCalls > 100)
            {
                throw new ServiceException("批量过多");
            }
            BigDecimal amount = defaultAmount(estimate.amount());
            totalAmount = totalAmount.add(amount);
            models.add(new ExtractModelBillingPlan(entry.getKey(), List.copyOf(entry.getValue()),
                    modelConfig, List.copyOf(estimate.callEstimates()), amount));
        }
        return new ExtractBillingPlan(List.copyOf(models), totalAmount);
    }

    /** 给已创建的正式任务补充usage水位并序列化同一只读计划。 */
    private ExtractBillingEstimate serializeExtractBillingPlan(ExtractBillingPlan plan,
            long usageStartMediaTaskId)
    {
        List<Map<String, Object>> items = new ArrayList<>(plan.models().size());
        for (ExtractModelBillingPlan model : plan.models())
        {
            int expectedCallCount = Math.max(1, model.calls().size());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelCode", model.modelCode());
            item.put("extractTypes", model.extractTypes());
            item.put("billingGranularity", "PER_CALL");
            item.put("preHoldAmount", model.preHoldAmount());
            item.put("expectedCallCount", expectedCallCount);
            item.put("unitPreHoldAmount", model.preHoldAmount().divide(
                    BigDecimal.valueOf(expectedCallCount), 12, java.math.RoundingMode.HALF_UP));
            List<Map<String, Object>> calls = new ArrayList<>(model.calls().size());
            BillingSnapshot pricingSnapshot = null;
            int callIndex = 0;
            for (ExtractCallBillingEstimate call : model.calls())
            {
                BillingCalcResult result = call.result();
                Map<String, Object> callItem = new LinkedHashMap<>();
                callItem.put("callIndex", callIndex++);
                callItem.put("extractType", call.extractType());
                if (StrUtil.isNotBlank(call.callSlot()))
                {
                    callItem.put("callSlot", call.callSlot());
                }
                callItem.put("preHoldAmount", defaultAmount(result.getAmount()));
                if (StrUtil.isNotBlank(result.getSkuCode()))
                {
                    callItem.put("skuCode", result.getSkuCode());
                    callItem.put("skuName", result.getSkuName());
                }
                BillingSnapshot snapshot = result.getSnapshot();
                if (snapshot != null)
                {
                    callItem.put("isFree", Boolean.TRUE.equals(snapshot.getIsFree()));
                    callItem.put("meterType", snapshot.getMeterType());
                    callItem.put("estimatedInputTokens", snapshot.getEstimatedInputTokens());
                    callItem.put("estimatedOutputTokens", snapshot.getEstimatedOutputTokens());
                    if (pricingSnapshot == null)
                    {
                        pricingSnapshot = snapshot;
                    }
                }
                calls.add(callItem);
            }
            item.put("callEstimates", calls);
            if (pricingSnapshot != null)
            {
                item.put("isFree", Boolean.TRUE.equals(pricingSnapshot.getIsFree()));
                item.put("snapshot", pricingSnapshot);
            }
            items.add(item);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("batchType", BILLING_BATCH_TYPE_MULTI_MODEL_EXTRACT);
        snapshot.put("billingGranularity", "PER_CALL");
        snapshot.put("itemCount", items.size());
        snapshot.put("preHoldAmount", plan.preHoldAmount());
        snapshot.put("usageStartMediaTaskId", Math.max(0L, usageStartMediaTaskId));
        snapshot.put("items", items);
        return new ExtractBillingEstimate(plan.preHoldAmount(), JSONUtil.toJsonStr(snapshot));
    }

    private record ExtractModelBillingPlan(String modelCode, List<String> extractTypes,
            AiModelConfigVo modelConfig, List<ExtractCallBillingEstimate> calls,
            BigDecimal preHoldAmount)
    {
    }

    private record ExtractBillingPlan(List<ExtractModelBillingPlan> models,
            BigDecimal preHoldAmount)
    {
    }

    /** 资产提取续跑的不可变只读计费计划。 */
    private record ExtractResumePlan(AidExtractTask task, ExtractBillingPlan billingPlan,
            ExtractBillingEstimate billingEstimate)
    {
    }

    private ExtractResumePlan prepareExtractResumePlan(Long taskId, Long userId)
    {
        AidExtractTask task = requireExtractResumeTask(taskId, userId);
        List<String> extractTypes = resolveExtractTypes(taskId);
        Map<String, String> modelCodesByType = resolveExtractModelCodesByTypeFromSnapshot(
                taskId, task.getModelCode());
        ExtractPlanningContext context = new ExtractPlanningContext(task.getProjectId(),
                task.getEpisodeId(), task.getUserId(), resolveExtractScopeFromTask(taskId),
                Map.copyOf(resolveAgentCodes(taskId)));
        ExtractBillingPlan fullPlan = buildExtractBillingPlan(
                context, List.copyOf(extractTypes), Map.copyOf(modelCodesByType));
        Set<String> completedSlots = readCompletedExtractCallSlots(task);
        List<ExtractModelBillingPlan> remainingModels = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ExtractModelBillingPlan model : fullPlan.models())
        {
            List<ExtractCallBillingEstimate> remainingCalls = model.calls().stream()
                    .filter(call -> StrUtil.isBlank(call.callSlot())
                            || !completedSlots.contains(call.callSlot()))
                    .toList();
            if (remainingCalls.isEmpty())
            {
                continue;
            }
            BigDecimal amount = remainingCalls.stream()
                    .map(ExtractCallBillingEstimate::result)
                    .map(BillingCalcResult::getAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            remainingModels.add(new ExtractModelBillingPlan(model.modelCode(),
                    model.extractTypes(), model.modelConfig(), List.copyOf(remainingCalls), amount));
            total = total.add(amount);
        }
        ExtractBillingPlan remainingPlan = new ExtractBillingPlan(
                List.copyOf(remainingModels), total);
        long watermark = extractBillingService.findLatestExtractMediaTaskId(taskId);
        return new ExtractResumePlan(task, remainingPlan,
                serializeExtractBillingPlan(remainingPlan, watermark));
    }

    private AidExtractTask requireExtractResumeTask(Long taskId, Long userId)
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
        if (!TASK_TYPE_ASSET_EXTRACT.equals(task.getTaskType()))
        {
            throw new ServiceException("任务类型不支持续生");
        }
        if (!TASK_STATUS_PARTIAL_FAILED.equals(task.getStatus())
                && !TASK_STATUS_CANCELLED.equals(task.getStatus()))
        {
            throw new ServiceException("任务状态不支持续生");
        }
        if (task.getCreateTime() != null
                && System.currentTimeMillis() - task.getCreateTime().getTime() > 24L * 3600_000L)
        {
            throw new ServiceException("任务已过期，请重新发起");
        }
        return task;
    }

    private Set<String> readCompletedExtractCallSlots(AidExtractTask task)
    {
        if (StrUtil.isBlank(task.getInputSnapshot())
                || !task.getInputSnapshot().trim().startsWith("{"))
        {
            return Set.of();
        }
        try
        {
            JsonNode completed = OBJECT_MAPPER.readTree(task.getInputSnapshot())
                    .get(ROLLING_TEXT_CALL_RESULTS_KEY);
            if (completed == null || !completed.isObject())
            {
                return Set.of();
            }
            LinkedHashSet<String> slots = new LinkedHashSet<>();
            completed.fieldNames().forEachRemaining(slots::add);
            return Set.copyOf(slots);
        }
        catch (Exception e)
        {
            log.error("资产提取续跑水位解析失败: taskId={}", task.getId(), e);
            throw new ServiceException("任务数据异常");
        }
    }

    @Override
    public BillingQuoteVO quoteResumeExtract(Long taskId, Long userId)
    {
        ExtractResumePlan plan = prepareExtractResumePlan(taskId, userId);
        if (plan.billingPlan().models().isEmpty())
        {
            return billingQuoteAssembler.zero("TASK_RESUME", "本轮无模型费用");
        }
        List<BillingQuoteVO> items = new ArrayList<>();
        for (ExtractModelBillingPlan model : plan.billingPlan().models())
        {
            for (ExtractCallBillingEstimate call : model.calls())
            {
                items.add(billingQuoteAssembler.single(
                        "TASK_RESUME", call.modelConfig(), call.result(), 1));
            }
        }
        return billingQuoteAssembler.aggregate("TASK_RESUME", items);
    }

    private BigDecimal defaultAmount(BigDecimal amount)
    {
        return Objects.nonNull(amount) ? amount : BigDecimal.ZERO;
    }

    private record ExtractBillingEstimate(BigDecimal amount, String snapshotJson)
    {
    }

    private static String formBusinessFunction(String assetType)
    {
        if (ASSET_TYPE_CHARACTER.equals(assetType)) return BIZ_CATEGORY_MAIN_CHARACTER_FORM;
        if (ASSET_TYPE_SCENE.equals(assetType)) return "main_scene_form";
        if (ASSET_TYPE_PROP.equals(assetType)) return "main_prop_form";
        throw new ServiceException("资产类型不支持");
    }

    private BillingCalcResult estimateFormGenerateCost(Long taskId, AidRolePropScene asset, String modelCode)
    {
        AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(modelCode,
                formBusinessFunction(asset.getAssetType()), null);
        if (modelConfig == null)
        {
            log.info("形态报价的业务模型不可用: modelCode={}, assetType={}", modelCode, asset.getAssetType());
            throw new ServiceException("业务模型不可用");
        }

        // 按各资产类型最终下发的 system + user messages 预估。
        int totalInputChars = estimateFormGenerateInputChars(asset, modelCode);
        if (totalInputChars <= 0)
        {
            log.info("形态生成费用预估: 资产数据不足, taskId={}, assetId={}, assetType={}",
                    taskId, asset.getId(), asset.getAssetType());
            return BillingCalcResult.fixed(BigDecimal.ZERO, null);
        }

        int expectedVariants = estimateFormAppearanceCount(asset);
        int estimatedOutputChars = Math.max(1, expectedVariants) * BillingConstants.DEFAULT_ESTIMATED_OUTPUT_CHARS;

        Map<String, Object> params = new HashMap<>();
        params.put("inputChars", totalInputChars);
        params.put("inputTokens", BillingConstants.charsToTokens(totalInputChars));
        params.put("outputTokens", BillingConstants.charsToTokens(estimatedOutputChars));
        params.put("estimatedOutputChars", estimatedOutputChars);
        params.put("totalChars", totalInputChars + estimatedOutputChars);
        helper.applyLlmBillingLimits(params, 1);
        BillingInput billingInput = new BillingInput("TEXT", params);

        BillingCalcResult result = billingAmountCalculator.calculatePreHoldAmount(modelConfig, billingInput);
        if (result.isMatched())
        {
            log.info("形态生成SKU预估费用: taskId={}, assetId={}, assetType={}, skuCode={}, amount={}, inputChars={}, expectedVariants={}",
                    taskId, asset.getId(), asset.getAssetType(), result.getSkuCode(), result.getAmount(), totalInputChars, expectedVariants);
            return result;
        }

        BigDecimal rawUnitPrice = Objects.nonNull(modelConfig.getCostCredits())
                ? modelConfig.getCostCredits() : BigDecimal.ZERO;
        BigDecimal unitPrice = billingPriceMultiplierService.apply(
                rawUnitPrice, modelConfig.getBillingMultiplier());
        if (Boolean.TRUE.equals(modelConfig.getIsFree()))
        {
            MeterType meterType = Objects.equals(MODEL_TYPE_IMAGE, modelConfig.getModelType())
                    ? MeterType.PER_IMAGE : MeterType.TOKEN;
            return buildFreeFixedFallback(modelConfig, params, rawUnitPrice, unitPrice, meterType);
        }
        log.info("形态生成FIXED预估费用: taskId={}, assetId={}, assetType={}, unitPrice={}, inputChars={}, expectedVariants={}",
                taskId, asset.getId(), asset.getAssetType(), unitPrice, totalInputChars, expectedVariants);
        return BillingCalcResult.fixed(unitPrice, null);
    }

    /** 为规则异常的免费模型保留原价审计快照，同时禁止固定价兜底反向收费。 */
    private BillingCalcResult buildFreeFixedFallback(AiModelConfigVo modelConfig,
                                                      Map<String, Object> params,
                                                      BigDecimal rawUnitPrice,
                                                      BigDecimal displayUnitPrice,
                                                      MeterType meterType)
    {
        BillingSnapshot snapshot = new BillingSnapshot();
        snapshot.setModelId(modelConfig.getId());
        snapshot.setModelName(modelConfig.getModelCode());
        snapshot.setModelType(StrUtil.blankToDefault(modelConfig.getModelType(), MODEL_TYPE_TEXT).toUpperCase());
        snapshot.setMeterType(meterType.name());
        snapshot.setBillingMode(BillingMode.FIXED.name());
        snapshot.setBillingVersion(modelConfig.getBillingVersion());
        snapshot.setIsFree(Boolean.TRUE);
        snapshot.setRequestParams(params);
        snapshot.setUnitPrice(displayUnitPrice);
        snapshot.setBaseAmount(rawUnitPrice);
        snapshot.setModelBillingMultiplier(billingPriceMultiplierService.resolveModelMultiplier(
                modelConfig.getBillingMultiplier()));
        snapshot.setGlobalBillingMultiplier(billingPriceMultiplierService.getGlobalMultiplier());
        snapshot.setFinalBillingMultiplier(snapshot.getModelBillingMultiplier()
                .multiply(snapshot.getGlobalBillingMultiplier()));
        snapshot.setPreHoldAmount(BigDecimal.ZERO);
        return BillingCalcResult.fixed(BigDecimal.ZERO, snapshot);
    }

    private int estimateFormAppearanceCount(AidRolePropScene asset)
    {
        String expectedAppearances = asset.getExpectedAppearances();
        if (StrUtil.isBlank(expectedAppearances))
        {
            return 1;
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(expectedAppearances);
            if (parsed != null && parsed.isArray() && !parsed.isEmpty())
            {
                return parsed.size();
            }
        }
        catch (Exception e)
        {
            log.warn("形态生成预估appearance数量解析失败, assetId={}", asset.getId(), e);
        }
        return 1;
    }

    private int estimateFormGenerateInputChars(AidRolePropScene asset, String modelCode)
    {
        if (Objects.isNull(asset))
        {
            return 0;
        }
        if (Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType()))
        {
            if (StrUtil.isBlank(asset.getProfileData()))
            {
                return 0;
            }
            Map<String, String> inputs = buildVisualStylistInputs(asset);
            return helper.estimateLlmInputCharsWithInputs(
                    helper.loadPromptByNameReadOnly(PROMPT_NAME_VISUAL_STYLIST), inputs, modelCode);
        }
        if (Objects.equals(ASSET_TYPE_SCENE, asset.getAssetType()))
        {
            if (StrUtil.isNotBlank(asset.getIntroduction()))
            {
                return helper.estimateLlmInputCharsWithInputs(
                        helper.loadPromptByNameReadOnly("aid_scene_stylist"), collectSceneStylistInputs(asset), modelCode);
            }
        }
        if (Objects.equals(ASSET_TYPE_PROP, asset.getAssetType()))
        {
            if (StrUtil.isNotBlank(asset.getIntroduction()))
            {
                return helper.estimateLlmInputCharsWithInputs(
                        helper.loadPromptByNameReadOnly("aid_prop_stylist"), collectPropStylistVariables(asset), modelCode);
            }
        }
        if (StrUtil.isBlank(asset.getSummary()))
        {
            return 0;
        }
        return helper.estimateLlmInputChars(buildScenePropSystemPrompt(asset.getAssetType()),
                buildScenePropUserContent(asset), modelCode);
    }

    /**
     * 场景/道具形态生成 LLM 系统提示词
     */
    private String buildScenePropSystemPrompt(String assetType)
    {
        String typeLabel = Objects.equals(ASSET_TYPE_SCENE, assetType) ? "场景" : "道具";
        String focusHint = Objects.equals(ASSET_TYPE_SCENE, assetType)
                ? "空间布局、光线氛围、色调风格、关键物件摆放"
                : "外观特征、材质质感、颜色光泽、尺寸比例";
        return "你是一个专业的视觉描述生成器。请根据以下" + typeLabel + "信息，"
                + "生成一段适合用于AI绘图的详细视觉描述。"
                + "描述应包含" + typeLabel + "的" + focusHint + "等画面要素。"
                + "请直接输出描述文本，不要包含JSON格式。";
    }

    /**
     * 场景/道具形态生成 LLM 用户内容
     */
    private String buildScenePropUserContent(AidRolePropScene asset)
    {
        String typeLabel = Objects.equals(ASSET_TYPE_SCENE, asset.getAssetType()) ? "场景" : "道具";
        StringBuilder sb = new StringBuilder();
        sb.append(typeLabel).append("名称: ").append(asset.getName()).append("\n");
        // summary = 简要说明，introduction = 详细描述 / 视觉描述
        if (StrUtil.isNotBlank(asset.getSummary()))
        {
            sb.append(typeLabel).append("简要说明: ").append(asset.getSummary()).append("\n");
        }
        if (StrUtil.isNotBlank(asset.getIntroduction()))
        {
            sb.append(typeLabel).append("详细描述:\n").append(asset.getIntroduction()).append("\n");
        }
        return sb.toString();
    }


    /**
     * 按执行阶段的真实切片和消息装配预估资产提取输入。
     * 已有资产库按提交时快照计入；后续切片中新提取资产造成的库增长由真实 usage 结算兜底。
     */
    private ExtractInputEstimate estimateExtractInput(Long taskId, List<String> extractTypes, String modelCode)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task))
        {
            return new ExtractInputEstimate(List.of(new ExtractCallInputEstimate("unknown", null, 0)));
        }
        AidComicProject project = projectService.selectAidComicProjectById(task.getProjectId());
        if (Objects.isNull(project))
        {
            return new ExtractInputEstimate(List.of(new ExtractCallInputEstimate("unknown", null, 0)));
        }
        ExtractPlanningContext context = new ExtractPlanningContext(task.getProjectId(),
                task.getEpisodeId(), task.getUserId(), resolveExtractScopeFromTask(taskId),
                Map.copyOf(resolveAgentCodes(taskId)));
        return estimateExtractInput(context, extractTypes, modelCode);
    }

    private ExtractInputEstimate estimateExtractInput(ExtractPlanningContext context,
            List<String> extractTypes, String modelCode)
    {
        AidComicProject project = projectService.selectAidComicProjectById(context.projectId());
        if (Objects.isNull(project))
        {
            return new ExtractInputEstimate(List.of(new ExtractCallInputEstimate("unknown", null, 0)));
        }
        Map<String, String> agentCodes = context.agentCodes();
        // 去重库与执行阶段同口径：项目级加载（跨集去重/复用）
        ExistingAssetLib lib = helper.loadExistingAssets(context.projectId(), null);
        List<ExtractCallInputEstimate> calls = new ArrayList<>();

        if (extractTypes.contains(EXTRACT_TYPE_CHARACTER))
        {
            String agentCode = resolveAgentCodeForType(agentCodes, EXTRACT_TYPE_CHARACTER);
            String promptTemplate = helper.loadPromptByNameReadOnly(
                    StrUtil.blankToDefault(agentCode, PROMPT_NAME_CASTING));
            String charactersLibInfo = helper.loadCharactersLibInfo(context.projectId());
            String charactersLibName = lib.getCharacterNamesJoined();
            // 与执行阶段同口径：剧集全量=全项目分组剧本；剧集增量=当前集剧本；电影=整部剧本
            boolean seriesFullScan = Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType())
                    && Objects.equals(EXTRACT_SCOPE_PROJECT_FULL, context.extractScope());
            String characterStage = seriesFullScan ? "character_series"
                    : Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType())
                    ? "character_incremental" : "character";
            List<String> characterScripts;
            if (seriesFullScan)
            {
                characterScripts = helper.loadGroupedScriptsContent(
                        context.projectId(), SERIES_EPISODE_GROUP_SIZE, context.userId());
            }
            else if (Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType()))
            {
                characterScripts = List.of(StrUtil.nullToEmpty(
                        helper.loadScriptContent(context.projectId(), context.episodeId(), context.userId())));
            }
            else
            {
                characterScripts = List.of(StrUtil.nullToEmpty(resolveCharacterScript(project, context.userId())));
            }
            Map<String, Integer> sourceOccurrences = new HashMap<>();
            for (String script : characterScripts)
            {
                for (String chunk : helper.chunkContent(StrUtil.nullToEmpty(script), CHUNK_SIZE_CHARACTER))
                {
                    Map<String, String> inputs = new LinkedHashMap<>();
                    inputs.put("input", chunk);
                    inputs.put("characters_lib_info", charactersLibInfo);
                    inputs.put("characters_lib_name", charactersLibName);
                    int inputChars = helper.estimateLlmInputCharsWithInputs(promptTemplate, inputs, modelCode);
                    String callSlot = buildStableAssetChunkSlot(characterStage, chunk, sourceOccurrences);
                    calls.add(new ExtractCallInputEstimate(EXTRACT_TYPE_CHARACTER, callSlot, inputChars));
                }
            }
        }

        if (extractTypes.contains(EXTRACT_TYPE_SCENE) || extractTypes.contains(EXTRACT_TYPE_PROP))
        {
            String script = helper.loadScriptContent(context.projectId(), context.episodeId(), context.userId());
            List<String> chunks = helper.chunkContent(StrUtil.nullToEmpty(script), CHUNK_SIZE_SCENE_PROP);
            String sceneTemplate = extractTypes.contains(EXTRACT_TYPE_SCENE)
                    ? helper.loadPromptByNameReadOnly(StrUtil.blankToDefault(
                            resolveAgentCodeForType(agentCodes, EXTRACT_TYPE_SCENE), PROMPT_NAME_SCENE)) : null;
            String propTemplate = extractTypes.contains(EXTRACT_TYPE_PROP)
                    ? helper.loadPromptByNameReadOnly(StrUtil.blankToDefault(
                            resolveAgentCodeForType(agentCodes, EXTRACT_TYPE_PROP), PROMPT_NAME_PROP)) : null;
            Map<String, Integer> sourceOccurrences = new HashMap<>();
            for (String chunk : chunks)
            {
                String sourceSlot = buildStableAssetChunkSlot("source", chunk, sourceOccurrences);
                String sourceIdentity = sourceSlot.substring("stage=source,".length());
                if (StrUtil.isNotBlank(sceneTemplate))
                {
                    Map<String, String> inputs = new LinkedHashMap<>();
                    inputs.put("input", chunk);
                    inputs.put("locations_lib_name", lib.getSceneNamesJoined());
                    int inputChars = helper.estimateLlmInputCharsWithInputs(sceneTemplate, inputs, modelCode);
                    calls.add(new ExtractCallInputEstimate(EXTRACT_TYPE_SCENE,
                            "stage=scene," + sourceIdentity, inputChars));
                }
                if (StrUtil.isNotBlank(propTemplate))
                {
                    Map<String, String> inputs = new LinkedHashMap<>();
                    inputs.put("input", chunk);
                    inputs.put("props_lib_name", lib.getPropNamesJoined());
                    int inputChars = helper.estimateLlmInputCharsWithInputs(propTemplate, inputs, modelCode);
                    calls.add(new ExtractCallInputEstimate(EXTRACT_TYPE_PROP,
                            "stage=prop," + sourceIdentity, inputChars));
                }
            }
        }
        if (calls.isEmpty())
        {
            calls.add(new ExtractCallInputEstimate("unknown", null, 0));
        }
        return new ExtractInputEstimate(List.copyOf(calls));
    }

    private record ExtractCallInputEstimate(String extractType, String callSlot, int inputChars) { }

    private record ExtractInputEstimate(List<ExtractCallInputEstimate> calls) { }

    private record ExtractPlanningContext(Long projectId, Long episodeId, Long userId,
            String extractScope, Map<String, String> agentCodes)
    {
    }

    private AidComicProject validateProject(Long projectId, Long userId)
    {
        // 使用MyBatis-Plus同时校验项目存在性和归属权
        // 注意：select 中新增 videoStyleType/videoStyleValue，用于 requireProjectStyle 校验，不能再收窄
        AidComicProject project = projectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId, AidComicProject::getProjectType,
                                AidComicProject::getVideoStyleType, AidComicProject::getVideoStyleValue)
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getUserId, userId)
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL));
        if (Objects.isNull(project))
        {
            log.info("项目不存在或无权操作, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        return project;
    }

    /**
     * 资产提取父任务建档事务内锁定项目行，与风格切换使用同一互斥点。
     */
    private AidComicProject lockProjectForExtract(Long projectId, Long userId)
    {
        // 查询字段精简：锁内只复核归属与项目公开风格。
        AidComicProject project = projectService.getOne(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getId, AidComicProject::getProjectType,
                        AidComicProject::getVideoStyleType, AidComicProject::getVideoStyleValue)
                .eq(AidComicProject::getId, projectId)
                .eq(AidComicProject::getUserId, userId)
                .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                .last("FOR UPDATE"));
        if (Objects.isNull(project))
        {
            log.info("锁定提取项目失败，项目不存在: projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在");
        }
        return project;
    }

    /**
     * 校验项目已设置视频风格。
     * 规则：extract/generateForm/generateFormImage 等所有大模型调用前必须有设置
     * videoStyleType 和 videoStyleValue。此检查必须在任务创建 / 计费冻结之前执行，
     * 避免下游因缺风风格拼装出风格不一致的资产。
     */
    private void requireProjectStyle(AidComicProject project)
    {
        if (Objects.isNull(project))
        {
            throw new ServiceException("项目不存在");
        }
        if (StrUtil.isBlank(project.getVideoStyleType()) || StrUtil.isBlank(project.getVideoStyleValue()))
        {
            log.info("项目未设置视频风格: projectId={}, styleType={}, styleValue={}",
                    project.getId(), project.getVideoStyleType(), project.getVideoStyleValue());
            throw new ServiceException("请选择风格");
        }
    }

    /**
     * 解析任务归属剧集ID。
     * 电影恒为 0；剧集全量扫描（仅角色）归属 0（项目级）；剧集增量提取必填=当前集。
     * 注意：任务归属集 ≠ 角色主资产落库集——剧集角色主资产恒落 {@link #CHARACTER_GLOBAL_EPISODE_ID}（项目级唯一）。
     *
     * @param requestEpisodeId 请求剧集ID
     * @param project          项目
     * @param extractTypes     提取类型
     * @param extractScope     提取范围
     * @return 任务归属剧集ID
     */
    private Long resolveEpisodeId(Long requestEpisodeId, AidComicProject project,
                                  List<String> extractTypes, String extractScope)
    {
        if (Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType()))
        {
            return 0L;
        }
        // 剧集全量扫描（仅角色）：任务归属项目级
        if (Objects.equals(EXTRACT_SCOPE_PROJECT_FULL, extractScope))
        {
            return 0L;
        }
        // 剧集增量提取（角色/场景/道具均分析当前集）：episodeId 必填
        if (Objects.isNull(requestEpisodeId) || requestEpisodeId <= 0L)
        {
            log.info("剧集增量提取缺少剧集ID: projectId={}, extractTypes={}", project.getId(), extractTypes);
            throw new ServiceException("剧集不能为空");
        }
        return requestEpisodeId;
    }
    /**
     * 角色提取的剧本加载策略
     *
     *   - 电影：加载单集剧本（episodeId=0）
     *   - 剧集：合并所有剧集内容（按创建时间排序），跨集统一分析角色
     *
     */
    private String resolveCharacterScript(AidComicProject project, Long userId)
    {
        if (Objects.equals(PROJECT_TYPE_MOVIE, project.getProjectType()))
        {
            return helper.loadScriptContent(project.getId(), 0L, userId);
        }
        return helper.loadAllScriptsContent(project.getId(), userId);
    }
    /**
     * 从任务记录获取模型编码
     */
    private String resolveModelCode(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getModelCode()))
        {
            log.info("任务模型编码为空: taskId={}", taskId);
            throw new ServiceException("模型不能为空");
        }
        return task.getModelCode();
    }

    /**
     * 从请求参数解析提取类型列表，为空则默认提取全部
     */
    private List<String> resolveExtractTypesFromRequest(List<String> extractTypes)
    {
        // 未传值则默认提取全部
        if (CollectionUtil.isEmpty(extractTypes))
        {
            return Arrays.asList(EXTRACT_TYPE_CHARACTER, EXTRACT_TYPE_SCENE, EXTRACT_TYPE_PROP);
        }
        // 过滤合法值
        List<String> validTypes = new ArrayList<>();
        for (String type : extractTypes)
        {
            if (type != null && !type.isBlank()
                    && (EXTRACT_TYPE_CHARACTER.equals(type)
                    || EXTRACT_TYPE_SCENE.equals(type)
                    || EXTRACT_TYPE_PROP.equals(type)))
            {
                validTypes.add(type);
            }
        }
        // 用户显式传了值但全部非法 → 报错，而非静默退化为全提取
        if (validTypes.isEmpty())
        {
            throw new ServiceException("提取类型不支持");
        }
        return validTypes;
    }

    /**
     * 从任务记录的 inputSnapshot 解析提取范围；缺失（CSV 快照等）回退增量口径。
     *
     * @param taskId 任务ID
     * @return 提取范围（EPISODE_INCREMENTAL / PROJECT_FULL）
     */
    @SuppressWarnings("unchecked")
    private String resolveExtractScopeFromTask(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            return EXTRACT_SCOPE_EPISODE_INCREMENTAL;
        }
        String snapshot = task.getInputSnapshot().trim();
        if (!snapshot.startsWith("{"))
        {
            return EXTRACT_SCOPE_EPISODE_INCREMENTAL;
        }
        try
        {
            Map<String, Object> map = OBJECT_MAPPER.readValue(snapshot, Map.class);
            Object scope = map.get("extractScope");
            if (Objects.nonNull(scope) && EXTRACT_SCOPE_PROJECT_FULL.equals(String.valueOf(scope).trim()))
            {
                return EXTRACT_SCOPE_PROJECT_FULL;
            }
        }
        catch (Exception e)
        {
            log.warn("解析 inputSnapshot.extractScope 失败, 回退增量: taskId={}, err={}", taskId, e.getMessage());
        }
        return EXTRACT_SCOPE_EPISODE_INCREMENTAL;
    }

    /**
     * 从任务记录的 inputSnapshot 解析提取类型列表。
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveExtractTypes(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            return Arrays.asList(EXTRACT_TYPE_CHARACTER, EXTRACT_TYPE_SCENE, EXTRACT_TYPE_PROP);
        }
        String snapshot = task.getInputSnapshot().trim();
        if (snapshot.startsWith("{"))
        {
            try
            {
                Map<String, Object> map = OBJECT_MAPPER.readValue(snapshot, Map.class);
                Object types = map.get("extractTypes");
                if (types instanceof List)
                {
                    List<String> result = new ArrayList<>();
                    for (Object o : (List<Object>) types)
                    {
                        if (o != null)
                        {
                            String trimmed = String.valueOf(o).trim();
                            if (StrUtil.isNotBlank(trimmed))
                            {
                                result.add(trimmed);
                            }
                        }
                    }
                    if (!result.isEmpty())
                    {
                        return result;
                    }
                }
            }
            catch (Exception e)
            {
                // 解析失败：记录后回退 CSV 解析路径兜底
                log.error("inputSnapshot JSON 解析失败，回退 CSV 路径: taskId={}, snapshotLen={}, error={}",
                        taskId, StrUtil.length(snapshot), e.getMessage());
            }
        }
        List<String> types = new ArrayList<>();
        for (String type : snapshot.split(","))
        {
            String trimmed = type.trim();
            if (StrUtil.isNotBlank(trimmed))
            {
                types.add(trimmed);
            }
        }
        return types.isEmpty()
                ? Arrays.asList(EXTRACT_TYPE_CHARACTER, EXTRACT_TYPE_SCENE, EXTRACT_TYPE_PROP)
                : types;
    }

    /**
     * 从任务的 inputSnapshot 中解析 agentCodes 映射。
     * 解析失败或 inputSnapshot 为 CSV 格式（不含 agentCodes）时返回空 Map，
     * 上层调用点会用默认智能体常量（{@code aid_casting_director} 等）兜底。
     *
     * @param taskId 任务ID
     * @return agentCodes 映射，key=extractType（character/scene/prop），value=agentCode；不存在返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveAgentCodes(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            return new HashMap<>();
        }
        String snapshot = task.getInputSnapshot().trim();
        if (!snapshot.startsWith("{"))
        {
            // 旧 CSV 格式 inputSnapshot 不含 agentCodes
            return new HashMap<>();
        }
        try
        {
            Map<String, Object> map = OBJECT_MAPPER.readValue(snapshot, Map.class);
            Object codes = map.get("agentCodes");
            if (codes instanceof Map)
            {
                Map<String, String> result = new HashMap<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) codes).entrySet())
                {
                    if (e.getValue() != null)
                    {
                        String v = String.valueOf(e.getValue()).trim();
                        if (StrUtil.isNotBlank(v))
                        {
                            result.put(e.getKey(), v);
                        }
                    }
                }
                return result;
            }
        }
        catch (Exception e)
        {
            log.error("inputSnapshot agentCodes 解析失败: taskId={}, snapshotLen={}, error={}",
                    taskId, StrUtil.length(snapshot), e.getMessage());
        }
        return new HashMap<>();
    }

    /**
     * 从任务的 inputSnapshot 中解析 modelCodes 映射。
     *
     * @param taskId            任务ID
     * @param fallbackModelCode 兜底模型编码（task.modelCode），用于补全 Map 中缺失 type 的兜底值
     * @return modelCodes 映射，key=extractType（character/scene/prop），value=modelCode；不存在返回空 Map
     */
    @SuppressWarnings("unchecked")
    Map<String, String> resolveExtractModelCodesByTypeFromSnapshot(Long taskId, String fallbackModelCode)
    {
        Map<String, String> result = new HashMap<>();
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.nonNull(task) && StrUtil.isNotBlank(task.getInputSnapshot()))
        {
            String snapshot = task.getInputSnapshot().trim();
            if (snapshot.startsWith("{"))
            {
                try
                {
                    Map<String, Object> map = OBJECT_MAPPER.readValue(snapshot, Map.class);
                    Object codes = map.get("modelCodes");
                    if (codes instanceof Map)
                    {
                        for (Map.Entry<String, Object> entry : ((Map<String, Object>) codes).entrySet())
                        {
                            if (entry.getValue() == null)
                            {
                                continue;
                            }
                            String modelCode = String.valueOf(entry.getValue()).trim();
                            if (StrUtil.isNotBlank(modelCode))
                            {
                                result.put(entry.getKey(), modelCode);
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    log.error("inputSnapshot modelCodes 解析失败: taskId={}, snapshotLen={}, error={}",
                            taskId, StrUtil.length(snapshot), e.getMessage());
                }
            }
        }

        // 兼容旧 CSV、旧 JSON 以及 modelCodes 缺项：只补缺失类型，绝不覆盖现代多模型快照。
        if (StrUtil.isNotBlank(fallbackModelCode))
        {
            for (String extractType : resolveExtractTypes(taskId))
            {
                result.putIfAbsent(extractType, fallbackModelCode);
            }
        }
        return result;
    }

    /**
     * 取指定类型对应的 agentCode；snapshot 没有时回退到对应的旧常量。
     * 旧任务（早期 CSV inputSnapshot）没有 agentCodes，会自动回退到
     * {@code PROMPT_NAME_CASTING / PROMPT_NAME_SCENE / PROMPT_NAME_PROP} 三个固定常量，
     * 保证存量任务能继续被消费。
     */
    private String resolveAgentCodeForType(Map<String, String> agentCodes, String extractType)
    {
        if (agentCodes != null)
        {
            String code = agentCodes.get(extractType);
            if (StrUtil.isNotBlank(code))
            {
                return code;
            }
        }
        // 兜底：早期旧任务没有 agentCodes
        if (EXTRACT_TYPE_CHARACTER.equals(extractType))
        {
            return PROMPT_NAME_CASTING;
        }
        if (EXTRACT_TYPE_SCENE.equals(extractType))
        {
            return PROMPT_NAME_SCENE;
        }
        if (EXTRACT_TYPE_PROP.equals(extractType))
        {
            return PROMPT_NAME_PROP;
        }
        return null;
    }
    private RollingExtractProgress loadRollingExtractProgress(Long taskId, String dispatchToken)
    {
        TextCallBillingContext billingContext = extractBillingService.resolveTextCallBillingContext(taskId);
        if (billingContext.mediaTaskBilling() && StrUtil.isBlank(dispatchToken))
        {
            log.error("滚动提取缺少派发周期令牌: taskId={}", taskId);
            throw new TextTaskExecutionRejectedException();
        }
        if (StrUtil.isNotBlank(dispatchToken)
                && !Objects.equals(dispatchToken, billingContext.billingTraceId()))
        {
            log.warn("提取 worker 周期已变更: taskId={}, expectedTrace={}, currentTrace={}",
                    taskId, dispatchToken, billingContext.billingTraceId());
            throw new TextTaskExecutionRejectedException();
        }
        if (!billingContext.mediaTaskBilling())
        {
            return new RollingExtractProgress(false, billingContext.billingTraceId(), new LinkedHashMap<>());
        }
        AidExtractTask task = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getBillingTraceId,
                                AidExtractTask::getInputSnapshot)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        if (Objects.isNull(task)
                || !Objects.equals(billingContext.billingTraceId(), task.getBillingTraceId())
                || StrUtil.isBlank(task.getInputSnapshot())
                || !task.getInputSnapshot().trim().startsWith("{"))
        {
            log.error("滚动提取水位加载失败: taskId={}", taskId);
            throw new ServiceException("任务数据异常");
        }
        Map<String, List<Long>> completed = new LinkedHashMap<>();
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(task.getInputSnapshot());
            JsonNode callResults = root.get(ROLLING_TEXT_CALL_RESULTS_KEY);
            if (Objects.nonNull(callResults) && callResults.isObject())
            {
                callResults.fields().forEachRemaining(entry -> {
                    List<Long> ids = new ArrayList<>();
                    if (entry.getValue().isArray())
                    {
                        entry.getValue().forEach(idNode -> {
                            if (idNode.canConvertToLong() && idNode.asLong() > 0L)
                            {
                                ids.add(idNode.asLong());
                            }
                        });
                    }
                    completed.put(entry.getKey(), List.copyOf(new LinkedHashSet<>(ids)));
                });
            }
        }
        catch (Exception e)
        {
            log.error("滚动提取水位解析失败: taskId={}", taskId, e);
            throw new ServiceException("任务数据异常");
        }
        return new RollingExtractProgress(true, billingContext.billingTraceId(), completed);
    }

    private List<RpsAssetVO> loadRollingCompletedAssets(RollingExtractProgress progress,
                                                        Long projectId, Long userId)
    {
        LinkedHashSet<Long> orderedIds = progress.assetIdsByCall.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orderedIds.isEmpty())
        {
            return new ArrayList<>();
        }
        List<AidRolePropScene> rows = rpsService.list(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .in(AidRolePropScene::getId, orderedIds)
                        .eq(AidRolePropScene::getProjectId, projectId)
                        .eq(AidRolePropScene::getUserId, userId)
                        .eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, AidRolePropScene> rowsById = rows.stream()
                .collect(Collectors.toMap(AidRolePropScene::getId, row -> row, (left, right) -> left));
        // 与正常提取结果的 VO 契约保持一致：一次性回读已完成主资产的形态，避免续生累计结果
        // 丢失角色形态，同时避免按资产逐条查询造成 N+1。水位仍只决定返回哪些主资产，
        // updated_character 仅新增形态而未产生主资产水位时不会凭空扩展结果集合。
        List<AidRolePropSceneForm> forms = rpsFormService.list(
                Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .select(AidRolePropSceneForm::getId,
                                AidRolePropSceneForm::getAssetId,
                                AidRolePropSceneForm::getName,
                                AidRolePropSceneForm::getChangeReason,
                                AidRolePropSceneForm::getCreateSource,
                                AidRolePropSceneForm::getVisualDescStatus,
                                AidRolePropSceneForm::getPromptText)
                        .in(AidRolePropSceneForm::getAssetId, orderedIds)
                        .eq(AidRolePropSceneForm::getProjectId, projectId)
                        .eq(AidRolePropSceneForm::getUserId, userId)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AidRolePropSceneForm::getId));
        Map<Long, List<AidRolePropSceneForm>> formsByAssetId = forms.stream()
                .collect(Collectors.groupingBy(AidRolePropSceneForm::getAssetId,
                        LinkedHashMap::new, Collectors.toList()));
        List<RpsAssetVO> result = new ArrayList<>();
        for (Long assetId : orderedIds)
        {
            AidRolePropScene row = rowsById.get(assetId);
            if (Objects.nonNull(row))
            {
                result.add(buildAssetVO(row,
                        formsByAssetId.getOrDefault(assetId, List.of())));
            }
        }
        return result;
    }

    private JsonNode callOrReplayExtractLlm(String promptTemplate, Map<String, String> userInputs,
                                            String modelCode, Long taskId, Long userId,
                                            String taskPromptDigest, String callIdentity,
                                            RollingExtractProgress progress, String businessFuncCode)
    {
        java.util.function.Predicate<JsonNode> replayValidator;
        if (callIdentity.startsWith("stage=scene,"))
        {
            replayValidator = this::isValidSceneExtractResult;
        }
        else if (callIdentity.startsWith("stage=prop,"))
        {
            replayValidator = this::isValidPropExtractResult;
        }
        else
        {
            replayValidator = this::isValidCharacterExtractResult;
        }
        int outputTokenCap = helper.resolveStructuredOutputTokens(
                StrUtil.length(userInputs.get("input")), 1);
        return batchParentSubmissionGuard.executeManagedTask(taskId, progress.billingTraceId,
                () -> helper.callLlmWithInputs(promptTemplate, userInputs, modelCode,
                        taskId, userId, taskPromptDigest, callIdentity, replayValidator,
                        progress.billingTraceId, outputTokenCap, businessFuncCode),
                TextTaskExecutionRejectedException::new);
    }

    private boolean isValidCharacterExtractResult(JsonNode result)
    {
        if (Objects.isNull(result) || !result.isObject())
        {
            return false;
        }
        JsonNode newCharacters = result.get("new_characters");
        JsonNode updatedCharacters = result.get("updated_characters");
        boolean hasNewArray = Objects.nonNull(newCharacters) && newCharacters.isArray();
        boolean hasUpdatedArray = Objects.nonNull(updatedCharacters) && updatedCharacters.isArray();
        if (!hasNewArray && !hasUpdatedArray)
        {
            return false;
        }
        if (hasNewArray)
        {
            for (JsonNode item : newCharacters)
            {
                if (!item.isObject() || StrUtil.isBlank(helper.getJsonText(item, "name")))
                {
                    return false;
                }
            }
        }
        if (hasUpdatedArray)
        {
            for (JsonNode item : updatedCharacters)
            {
                if (!item.isObject() || StrUtil.isBlank(helper.getJsonText(item, "name")))
                {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isValidSceneExtractResult(JsonNode result)
    {
        if (Objects.isNull(result) || !result.isObject())
        {
            return false;
        }
        JsonNode locations = result.get("locations");
        if (Objects.isNull(locations) || !locations.isArray() || locations.isEmpty())
        {
            return false;
        }
        try
        {
            for (JsonNode item : locations)
            {
                SceneExtractionNormalizer.normalize(item);
            }
            return true;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private boolean isValidPropExtractResult(JsonNode result)
    {
        if (Objects.isNull(result) || !result.isObject())
        {
            return false;
        }
        JsonNode props = result.get("props");
        if (Objects.isNull(props) || !props.isArray())
        {
            return false;
        }
        for (JsonNode item : props)
        {
            if (!item.isObject()
                    || StrUtil.isBlank(helper.getJsonText(item, "name"))
                    || StrUtil.isBlank(helper.getJsonText(item, "summary"))
                    || StrUtil.isBlank(helper.getJsonText(item, "description")))
            {
                return false;
            }
        }
        return true;
    }

    private void persistExtractCallResult(Long taskId, Long userId, String stableSlot, JsonNode result,
                                          List<RpsAssetVO> allAssets,
                                          RollingExtractProgress progress,
                                          java.util.function.Consumer<JsonNode> parser)
    {
        if (!progress.enabled)
        {
            parser.accept(result);
            return;
        }
        int initialSize = allAssets.size();
        try
        {
            List<Long> committedIds = transactionTemplate.execute(status -> {
                extractBillingService.assertTextTaskBusinessCommit(
                        taskId, userId, progress.billingTraceId);
                parser.accept(result);
                List<Long> newAssetIds = allAssets.subList(initialSize, allAssets.size()).stream()
                        .map(RpsAssetVO::getId)
                        .filter(Objects::nonNull)
                        .toList();
                return mergeRollingCallResultLocked(taskId, progress.billingTraceId,
                        stableSlot, newAssetIds);
            });
            if (Objects.isNull(committedIds))
            {
                throw new ServiceException("结果保存失败");
            }
            progress.recordCompleted(stableSlot, committedIds);
        }
        catch (RuntimeException e)
        {
            while (allAssets.size() > initialSize)
            {
                allAssets.remove(allAssets.size() - 1);
            }
            throw e;
        }
    }

    private List<Long> mergeRollingCallResultLocked(Long taskId, String expectedTraceId,
                                                    String stableSlot, List<Long> newAssetIds)
    {
        AidExtractTask locked = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getBillingTraceId, AidExtractTask::getInputSnapshot)
                        .eq(AidExtractTask::getId, taskId)
                        .last("FOR UPDATE"), false);
        if (Objects.isNull(locked)
                || !Objects.equals(TASK_STATUS_PROCESSING, locked.getStatus())
                || !Objects.equals(expectedTraceId, locked.getBillingTraceId())
                || StrUtil.isBlank(locked.getInputSnapshot())
                || !locked.getInputSnapshot().trim().startsWith("{"))
        {
            log.info("滚动提取水位写入跳过，任务周期已变化: taskId={}, callIdentity={}",
                    taskId, stableSlot);
            throw new TextTaskExecutionRejectedException();
        }
        try
        {
            ObjectNode root = (ObjectNode) OBJECT_MAPPER.readTree(locked.getInputSnapshot());
            JsonNode existingNode = root.get(ROLLING_TEXT_CALL_RESULTS_KEY);
            ObjectNode callResults;
            if (Objects.nonNull(existingNode) && existingNode.isObject())
            {
                callResults = (ObjectNode) existingNode;
            }
            else
            {
                callResults = OBJECT_MAPPER.createObjectNode();
                root.set(ROLLING_TEXT_CALL_RESULTS_KEY, callResults);
            }
            LinkedHashSet<Long> mergedIds = new LinkedHashSet<>();
            JsonNode storedIds = callResults.get(stableSlot);
            if (Objects.nonNull(storedIds) && storedIds.isArray())
            {
                storedIds.forEach(idNode -> {
                    if (idNode.canConvertToLong() && idNode.asLong() > 0L)
                    {
                        mergedIds.add(idNode.asLong());
                    }
                });
            }
            if (CollectionUtil.isNotEmpty(newAssetIds))
            {
                mergedIds.addAll(newAssetIds);
            }
            ArrayNode persistedIds = callResults.putArray(stableSlot);
            mergedIds.forEach(persistedIds::add);

            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
            update.eq(AidExtractTask::getBillingTraceId, expectedTraceId);
            update.set(AidExtractTask::getInputSnapshot, OBJECT_MAPPER.writeValueAsString(root));
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            if (extractTaskService.getBaseMapper().update(null, update) == 0)
            {
                throw new TextTaskExecutionRejectedException();
            }
            return List.copyOf(mergedIds);
        }
        catch (TextTaskExecutionRejectedException e)
        {
            throw e;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("滚动提取水位写入失败: taskId={}, stableSlot={}", taskId, stableSlot, e);
            throw new ServiceException("结果保存失败");
        }
    }

    /**
     * Build the durable business slot from the immutable source chunk only.
     * Runtime library/prompt/model differences belong to the media identity SHA, while a global
     * loop index is unsafe because resume/input edits can move the same chunk to another position.
     * The occurrence suffix disambiguates equal chunks without depending on unrelated chunks.
     */
    private String buildStableAssetChunkSlot(String stage, String sourceChunk,
                                             Map<String, Integer> sourceOccurrences)
    {
        String sourceSha = SecureUtil.sha256(StrUtil.nullToEmpty(sourceChunk));
        int occurrence = sourceOccurrences.merge(sourceSha, 1, Integer::sum) - 1;
        return "stage=" + stage + ",item=" + sourceSha + ",occurrence=" + occurrence;
    }

    private void extractCharacters(Long taskId, String scriptContent, ExistingAssetLib lib,
                                   Long projectId, Long episodeId, Long userId,
                                   List<RpsAssetVO> allAssets, String modelCode, String agentCode,
                                   RollingExtractProgress rollingProgress)
    {
        String promptTemplate = helper.loadPromptByName(StrUtil.blankToDefault(agentCode, PROMPT_NAME_CASTING));
        List<String> chunks = helper.chunkContent(scriptContent, CHUNK_SIZE_CHARACTER);
        int totalChunks = chunks.size();

        log.info("角色提取: 分为{}个切片, 总长度={}", totalChunks, scriptContent.length());

        // 记录本轮之前已有的角色数，用于计算本轮新增
        int prevAssetCount = allAssets.size();
        Map<String, Integer> sourceOccurrences = new HashMap<>();

        for (int i = 0; i < totalChunks; i++)
        {
            // ★ 取消检查点：每个 chunk 开始前检查
            if (isTaskCancelled(taskId))
            {
                log.info("角色提取被用户取消，已完成{}/{}个切片: taskId={}", i, totalChunks, taskId);
                break;
            }

            int progress = calculateProgress(15, 30, i, totalChunks);
            String stepTitle = totalChunks == 1 ? "正在分析角色..." : "正在分析角色 " + (i + 1) + "/" + totalChunks + "...";
            sseManager.sendStepProgress(taskId, "extracting_characters", progress,
                    "char_chunk_" + i, stepTitle, i + 1, totalChunks);

            try
            {
                String chunk = chunks.get(i);
                // 角色生成阶段：实时从 aid_role_prop_scene 查询本项目已有 character 资产，组装 characters_lib_info（含介绍）
                String charactersLibInfo = helper.loadCharactersLibInfo(projectId);
                // characters_lib_name 仅承载"已有角色名字"语义，与 lib_info 不混用
                String charactersLibName = lib.getCharacterNamesJoined();
                // 模板化调用 → prompt_content 走 system，动态入参走结构化 user message
                Map<String, String> userInputs = new LinkedHashMap<>();
                userInputs.put("input", chunk);
                userInputs.put("characters_lib_info", charactersLibInfo);
                userInputs.put("characters_lib_name", charactersLibName);
                // aid_media_task.prompt 列只存动态入参（chunk + 已有角色库），不存模板正文
                String digest = buildCharacterTaskDigest(chunk, charactersLibInfo, charactersLibName);
                String stableSlot = buildStableAssetChunkSlot(
                        "character", chunk, sourceOccurrences);
                String callIdentity = helper.buildLlmCallIdentity(stableSlot,
                        promptTemplate, userInputs, modelCode);
                if (rollingProgress.isCompleted(stableSlot))
                {
                    log.info("角色提取跳过已完成调用: taskId={}, stableSlot={}", taskId, stableSlot);
                    continue;
                }
                JsonNode result = callOrReplayExtractLlm(promptTemplate, userInputs, modelCode,
                        taskId, userId, digest, callIdentity, rollingProgress, BIZ_CATEGORY_MAIN_CHARACTER_EXTRACT);

                persistExtractCallResult(taskId, userId, stableSlot, result, allAssets, rollingProgress,
                        node -> parseAndPersistCharacters(node, lib, projectId, episodeId, userId,
                                allAssets, rollingProgress.enabled));

                // 推送本轮提取到的角色名
                int newCount = allAssets.size() - prevAssetCount;
                prevAssetCount = allAssets.size();
                if (newCount > 0)
                {
                    // 收集本轮新增的角色名
                    List<String> newNames = allAssets.subList(allAssets.size() - newCount, allAssets.size())
                            .stream().map(RpsAssetVO::getAssetName).toList();
                    String namesStr = String.join("、", newNames);
                    int doneProgress = calculateProgress(15, 30, i + 1, totalChunks) - 1;
                    sseManager.sendStepProgress(taskId, "extracting_characters", doneProgress,
                            "char_chunk_" + i + "_done",
                            "提取到角色: " + namesStr, i + 1, totalChunks);
                }
                else
                {
                    int doneProgress = calculateProgress(15, 30, i + 1, totalChunks) - 1;
                    sseManager.sendStepProgress(taskId, "extracting_characters", doneProgress,
                            "char_chunk_" + i + "_done",
                            "本切片无新增角色", i + 1, totalChunks);
                }
            }
            catch (TextTaskExecutionRejectedException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                log.error("角色提取切片{}失败: taskId={}", i, taskId, e);
                throw new RuntimeException("角色提取切片" + i + "失败: " + e.getMessage(), e);
            }
        }

        log.info("角色切片提取完成: 切片={}", totalChunks);
    }
    /**
     * 剧集模式角色提取：按10集/组分批提取，每组独立切片+LLM调用，组间累积去重
     * 60集 = 6组 × 10集，每组内合并文本后按30000字切片提取角色。
     */
    private void extractSeriesCharacters(Long taskId, Long projectId, Long episodeId,
                                         Long userId, List<RpsAssetVO> allAssets,
                                         String modelCode, ExistingAssetLib lib, String agentCode,
                                         RollingExtractProgress rollingProgress)
    {
        sseManager.sendStepProgress(taskId, "loading_script_char", 12,
                "load_script_char", "加载剧集剧本", 1, 1);

        // 按 episodeNo 分组加载剧本
        List<String> groupedScripts = helper.loadGroupedScriptsContent(projectId, SERIES_EPISODE_GROUP_SIZE, userId);
        if (CollectionUtil.isEmpty(groupedScripts))
        {
            throw new ServiceException("剧本内容不能为空");
        }

        String promptTemplate = helper.loadPromptByName(StrUtil.blankToDefault(agentCode, PROMPT_NAME_CASTING));
        int totalGroups = groupedScripts.size();

        log.info("剧集角色提取: 共{}组, 每组最多{}集", totalGroups, SERIES_EPISODE_GROUP_SIZE);

        int prevAssetCount = allAssets.size();
        Map<String, Integer> sourceOccurrences = new HashMap<>();

        for (int g = 0; g < totalGroups; g++)
        {
            // ★ 取消检查点：每组开始前检查
            if (isTaskCancelled(taskId))
            {
                log.info("剧集角色提取被用户取消，已完成{}/{}组: taskId={}", g, totalGroups, taskId);
                break;
            }

            String groupScript = groupedScripts.get(g);
            List<String> chunks = helper.chunkContent(groupScript, CHUNK_SIZE_CHARACTER);
            int totalChunks = chunks.size();

            for (int i = 0; i < totalChunks; i++)
            {
                // ★ 取消检查点：每个 chunk 开始前检查
                if (isTaskCancelled(taskId))
                {
                    log.info("剧集角色提取被用户取消，第{}组已完成{}/{}个切片: taskId={}", g + 1, i, totalChunks, taskId);
                    break;
                }

                // 进度：组间15-45，组内按切片细分
                int baseProgress = 15 + (30 * g / totalGroups);
                int rangePerGroup = 30 / totalGroups;
                int progress = calculateProgress(baseProgress, rangePerGroup, i, totalChunks);
                String stepTitle = totalGroups == 1
                        ? "正在分析角色 " + (i + 1) + "/" + totalChunks + "..."
                        : "正在分析角色 第" + (g + 1) + "组 " + (i + 1) + "/" + totalChunks + "...";
                int globalStepIndex = g * totalChunks + i + 1;
                int globalStepTotal = totalGroups * totalChunks;
                sseManager.sendStepProgress(taskId, "extracting_characters", progress,
                        "char_group_" + g + "_chunk_" + i, stepTitle, globalStepIndex, globalStepTotal);

                try
                {
                    String chunk = chunks.get(i);
                    // 角色生成阶段：实时从 aid_role_prop_scene 查询本项目已有 character 资产，组装 characters_lib_info（含介绍）
                    String charactersLibInfo = helper.loadCharactersLibInfo(projectId);
                    // characters_lib_name 仅承载"已有角色名字"语义，与 lib_info 不混用
                    String charactersLibName = lib.getCharacterNamesJoined();
                    // 模板化调用 → prompt_content 走 system，动态入参走结构化 user message
                    Map<String, String> userInputs = new LinkedHashMap<>();
                    userInputs.put("input", chunk);
                    userInputs.put("characters_lib_info", charactersLibInfo);
                    userInputs.put("characters_lib_name", charactersLibName);
                    // aid_media_task.prompt 列只存动态入参（chunk + 已有角色库），不存模板正文
                    String digest = buildCharacterTaskDigest(chunk, charactersLibInfo, charactersLibName);
                    String stableSlot = buildStableAssetChunkSlot(
                            "character_series", chunk, sourceOccurrences);
                    String callIdentity = helper.buildLlmCallIdentity(
                            stableSlot,
                            promptTemplate, userInputs, modelCode);
                    if (rollingProgress.isCompleted(stableSlot))
                    {
                        log.info("剧集角色提取跳过已完成调用: taskId={}, stableSlot={}",
                                taskId, stableSlot);
                        continue;
                    }
                    JsonNode result = callOrReplayExtractLlm(promptTemplate, userInputs, modelCode,
                            taskId, userId, digest, callIdentity, rollingProgress, BIZ_CATEGORY_MAIN_CHARACTER_EXTRACT);
                    persistExtractCallResult(taskId, userId, stableSlot, result, allAssets, rollingProgress,
                            node -> parseAndPersistCharacters(node, lib, projectId, episodeId, userId,
                                    allAssets, rollingProgress.enabled));

                    // 推送本轮提取到的角色名
                    int newCount = allAssets.size() - prevAssetCount;
                    prevAssetCount = allAssets.size();
                    if (newCount > 0)
                    {
                        List<String> newNames = allAssets.subList(allAssets.size() - newCount, allAssets.size())
                                .stream().map(RpsAssetVO::getAssetName).toList();
                        String namesStr = String.join("、", newNames);
                        int doneProgress = calculateProgress(baseProgress, rangePerGroup, i + 1, totalChunks) - 1;
                        sseManager.sendStepProgress(taskId, "extracting_characters", doneProgress,
                                "char_group_" + g + "_chunk_" + i + "_done",
                                "提取到角色: " + namesStr, globalStepIndex, globalStepTotal);
                    }
                }
                catch (TextTaskExecutionRejectedException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    log.error("剧集角色提取失败: group={}, chunk={}, taskId={}", g, i, taskId, e);
                    throw new RuntimeException("角色提取失败: " + e.getMessage(), e);
                }
            }

            log.info("剧集角色提取: 第{}/{}组完成", g + 1, totalGroups);
        }

        log.info("剧集角色提取全部完成: 共{}组, 提取{}个角色", totalGroups, allAssets.size());
    }

    /**
     * 剧集角色增量提取：只分析当前集剧本，参考「全项目角色目录」完成去重、
     * 已有角色信息合并与形态增量；新角色主资产落项目级（episodeId=0）。
     *
     * @param taskId    任务ID
     * @param projectId 项目ID
     * @param episodeId 当前集ID（任务归属，用于日志/形态首次出现集）
     * @param userId    用户ID
     * @param allAssets 结果收集
     * @param modelCode LLM 模型编码
     * @param lib       项目级已有资产去重库
     * @param agentCode 角色提取智能体编码
     */
    private void extractEpisodeIncrementalCharacters(Long taskId, Long projectId, Long episodeId,
                                                     Long userId, List<RpsAssetVO> allAssets,
                                                     String modelCode, ExistingAssetLib lib, String agentCode,
                                                     RollingExtractProgress rollingProgress)
    {
        sseManager.sendStepProgress(taskId, "loading_script_char", 12,
                "load_script_char", "加载本集剧本", 1, 1);
        String episodeScript = helper.loadScriptContent(projectId, episodeId, userId);
        if (StrUtil.isBlank(episodeScript))
        {
            throw new ServiceException("剧本内容不能为空");
        }
        String promptTemplate = helper.loadPromptByName(StrUtil.blankToDefault(agentCode, PROMPT_NAME_CASTING));
        List<String> chunks = helper.chunkContent(episodeScript, CHUNK_SIZE_CHARACTER);
        int totalChunks = chunks.size();
        log.info("剧集角色增量提取: projectId={}, episodeId={}, chunks={}", projectId, episodeId, totalChunks);

        int prevAssetCount = allAssets.size();
        Map<String, Integer> sourceOccurrences = new HashMap<>();
        for (int i = 0; i < totalChunks; i++)
        {
            if (isTaskCancelled(taskId))
            {
                log.info("剧集角色增量提取被用户取消，已完成{}/{}个切片: taskId={}", i, totalChunks, taskId);
                break;
            }
            int progress = calculateProgress(15, 30, i, totalChunks);
            sseManager.sendStepProgress(taskId, "extracting_characters", progress,
                    "char_inc_chunk_" + i, "正在分析本集角色 " + (i + 1) + "/" + totalChunks + "...",
                    i + 1, totalChunks);
            try
            {
                String chunk = chunks.get(i);
                // 全项目角色紧凑目录（名称+别名+介绍）：模型据此判断"新角色 or 已有角色更新/新形态"
                String charactersLibInfo = helper.loadCharactersLibInfo(projectId);
                String charactersLibName = lib.getCharacterNamesJoined();
                Map<String, String> userInputs = new LinkedHashMap<>();
                userInputs.put("input", chunk);
                userInputs.put("characters_lib_info", charactersLibInfo);
                userInputs.put("characters_lib_name", charactersLibName);
                String digest = buildCharacterTaskDigest(chunk, charactersLibInfo, charactersLibName);
                String stableSlot = buildStableAssetChunkSlot(
                        "character_incremental", chunk, sourceOccurrences);
                String callIdentity = helper.buildLlmCallIdentity(
                        stableSlot,
                        promptTemplate, userInputs, modelCode);
                if (rollingProgress.isCompleted(stableSlot))
                {
                    log.info("剧集角色增量提取跳过已完成调用: taskId={}, stableSlot={}",
                            taskId, stableSlot);
                    continue;
                }
                JsonNode result = callOrReplayExtractLlm(promptTemplate, userInputs, modelCode,
                        taskId, userId, digest, callIdentity, rollingProgress, BIZ_CATEGORY_MAIN_CHARACTER_EXTRACT);
                // 新角色主资产恒落项目级（episodeId=0），跨集唯一；已有角色走信息合并+形态增量
                persistExtractCallResult(taskId, userId, stableSlot, result, allAssets, rollingProgress,
                        node -> parseAndPersistCharacters(node, lib, projectId,
                                CHARACTER_GLOBAL_EPISODE_ID, userId, allAssets, rollingProgress.enabled));

                int newCount = allAssets.size() - prevAssetCount;
                prevAssetCount = allAssets.size();
                if (newCount > 0)
                {
                    List<String> newNames = allAssets.subList(allAssets.size() - newCount, allAssets.size())
                            .stream().map(RpsAssetVO::getAssetName).toList();
                    sseManager.sendStepProgress(taskId, "extracting_characters",
                            calculateProgress(15, 30, i + 1, totalChunks) - 1,
                            "char_inc_chunk_" + i + "_done",
                            "提取到角色: " + String.join("、", newNames), i + 1, totalChunks);
                }
            }
            catch (TextTaskExecutionRejectedException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                log.error("剧集角色增量提取失败: chunk={}, taskId={}", i, taskId, e);
                throw new RuntimeException("角色提取失败: " + e.getMessage(), e);
            }
        }
        log.info("剧集角色增量提取完成: projectId={}, episodeId={}, 新增{}个角色",
                projectId, episodeId, allAssets.size());
    }
    @Override
    public Map<String, Object> estimateCost(AssetExtractRequest request, Long userId)
    {
        // 校验项目
        AidComicProject project = validateProject(request.getProjectId(), userId);

        List<String> extractTypes = resolveExtractTypesFromRequest(request.getExtractTypes());
        // 提取范围解析（与提取接口同口径：剧集默认增量、全量仅支持角色）
        String extractScope = resolveExtractScope(request.getExtractScope(), project.getProjectType(), extractTypes);
        Long episodeId = resolveEpisodeId(request.getEpisodeId(), project, extractTypes, extractScope);

        boolean needCharacter = extractTypes.contains(EXTRACT_TYPE_CHARACTER);
        boolean needScene = extractTypes.contains(EXTRACT_TYPE_SCENE);
        boolean needProp = extractTypes.contains(EXTRACT_TYPE_PROP);
        boolean isSeries = Objects.equals(PROJECT_TYPE_SERIES, project.getProjectType());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectType", project.getProjectType());
        result.put("extractTypes", extractTypes);
        result.put("extractScope", isSeries ? extractScope : null);

        // 角色提取预估
        if (needCharacter)
        {
            if (isSeries && Objects.equals(EXTRACT_SCOPE_PROJECT_FULL, extractScope))
            {
                // 剧集全量初始化：统计全项目剧本字数和组数
                int totalChars = helper.countScriptCharacters(request.getProjectId(), null, userId);
                int episodeCount = helper.countEpisodes(request.getProjectId());
                int groupCount = (int) Math.ceil((double) episodeCount / SERIES_EPISODE_GROUP_SIZE);
                result.put("characterTotalChars", totalChars);
                result.put("episodeCount", episodeCount);
                result.put("characterGroupCount", groupCount);
                result.put("characterGroupName", "每组最多" + SERIES_EPISODE_GROUP_SIZE + "集");
                result.put("characterEstimatedCallCount",
                        estimateChunkCallCount(totalChars, CHUNK_SIZE_CHARACTER));
            }
            else
            {
                // 剧集增量（当前集）/ 电影（episodeId=0）：按该集剧本字数预估
                int totalChars = helper.countScriptCharacters(request.getProjectId(), episodeId, userId);
                result.put("characterTotalChars", totalChars);
                result.put("characterEstimatedCallCount",
                        estimateChunkCallCount(totalChars, CHUNK_SIZE_CHARACTER));
            }
            // 已有角色数 = 全项目参考资产目录规模（提示前端复用/覆盖）
            result.put("existingCharacterCount", helper.countExistingCharacters(request.getProjectId()));
        }

        // 场景+道具提取预估
        if (needScene || needProp)
        {
            int scenePropChars = helper.countScriptCharacters(request.getProjectId(), episodeId, userId);
            result.put("scenePropTotalChars", scenePropChars);
            result.put("scenePropTypes", extractTypes.stream()
                    .filter(t -> "scene".equals(t) || "prop".equals(t))
                    .toList());
            // 场景与道具各跑一轮切片调用
            int perTypeCalls = estimateChunkCallCount(scenePropChars, CHUNK_SIZE_SCENE_PROP);
            result.put("scenePropEstimatedCallCount",
                    perTypeCalls * (int) extractTypes.stream()
                            .filter(t -> EXTRACT_TYPE_SCENE.equals(t) || EXTRACT_TYPE_PROP.equals(t)).count());
        }

        return result;
    }

    /**
     * 按切片大小估算 LLM 调用次数（向上取整，至少 1 次；无内容为 0）。
     *
     * @param totalChars 剧本字数
     * @param chunkSize  切片大小
     * @return 预估调用次数
     */
    private int estimateChunkCallCount(int totalChars, int chunkSize)
    {
        if (totalChars <= 0)
        {
            return 0;
        }
        return (int) Math.ceil((double) totalChars / chunkSize);
    }
    private boolean extractScenesAndProps(Long taskId, String scriptContent, ExistingAssetLib lib,
                                          Long projectId, Long episodeId, Long userId,
                                          List<RpsAssetVO> allAssets,
                                          boolean needScene, boolean needProp,
                                          String sceneModelCode, String propModelCode,
                                          String sceneAgentCode, String propAgentCode,
                                          RollingExtractProgress rollingProgress)
    {
        String scenePromptTemplate = needScene
                ? helper.loadPromptByName(StrUtil.blankToDefault(sceneAgentCode, PROMPT_NAME_SCENE))
                : null;
        String propPromptTemplate = needProp
                ? helper.loadPromptByName(StrUtil.blankToDefault(propAgentCode, PROMPT_NAME_PROP))
                : null;

        List<String> chunks = helper.chunkContent(scriptContent, CHUNK_SIZE_SCENE_PROP);
        int totalChunks = chunks.size();
        Map<String, Integer> sourceOccurrences = new HashMap<>();

        log.info("场景道具提取: 分为{}个切片", totalChunks);

        int prevAssetCount = allAssets.size();

        // chunk 级失败记录（不再抛异常回滚整个事务）
        int extractionFailedChunk = -1;       // -1 表示全部成功
        String extractionFailedReason = null;

        for (int i = 0; i < totalChunks; i++)
        {
            // ★ 取消检查点：每个 chunk 开始前检查
            if (isTaskCancelled(taskId))
            {
                log.info("场景道具提取被用户取消，已完成{}/{}个切片: taskId={}", i, totalChunks, taskId);
                break;
            }

            int progress = calculateProgress(55, 30, i, totalChunks);
            String stepTitle = totalChunks == 1 ? "正在分析场景道具..." : "正在分析场景道具 " + (i + 1) + "/" + totalChunks + "...";
            sseManager.sendStepProgress(taskId, "extracting_scenes_props", progress,
                    "scene_prop_chunk_" + i, stepTitle, i + 1, totalChunks);

            try
            {
                String chunk = chunks.get(i);
                // aid_media_task.prompt 列只存动态入参（chunk + 已有资产库），不存模板正文
                final String spDigest = buildScenePropTaskDigest(chunk, lib);
                final Map<String, String> sceneInputs = new LinkedHashMap<>();
                sceneInputs.put("input", chunk);
                sceneInputs.put("locations_lib_name", lib.getSceneNamesJoined());
                final Map<String, String> propInputs = new LinkedHashMap<>();
                propInputs.put("input", chunk);
                propInputs.put("props_lib_name", lib.getPropNamesJoined());
                final String sourceSlot = buildStableAssetChunkSlot(
                        "source", chunk, sourceOccurrences);
                final String sourceIdentity = sourceSlot.substring("stage=source,".length());
                final String sceneStableSlot = "stage=scene," + sourceIdentity;
                final String propStableSlot = "stage=prop," + sourceIdentity;
                final String sceneCallIdentity = needScene
                        ? helper.buildLlmCallIdentity(sceneStableSlot,
                                scenePromptTemplate, sceneInputs, sceneModelCode) : null;
                final String propCallIdentity = needProp
                        ? helper.buildLlmCallIdentity(propStableSlot,
                                propPromptTemplate, propInputs, propModelCode) : null;
                boolean runScene = needScene && !rollingProgress.isCompleted(sceneStableSlot);
                boolean runProp = needProp && !rollingProgress.isCompleted(propStableSlot);

                if (!runScene && !runProp)
                {
                    log.info("场景道具提取跳过已完成切片: taskId={}, chunk={}", taskId, i);
                    continue;
                }
                if (runScene && runProp)
                {
                    // 片内并行：同一切片同时提取场景和道具
                    // 模板化调用 → prompt_content 走 system；原文+已有库走 user message
                    final String _sceneTpl = scenePromptTemplate;
                    final String _propTpl = propPromptTemplate;
                    CompletableFuture<JsonNode> sceneFuture = CompletableFuture.supplyAsync(
                            () -> callOrReplayExtractLlm(_sceneTpl, sceneInputs, sceneModelCode,
                                    taskId, userId, spDigest, sceneCallIdentity, rollingProgress, BIZ_CATEGORY_MAIN_SCENE_EXTRACT), getExtractExecutor());
                    CompletableFuture<JsonNode> propFuture = CompletableFuture.supplyAsync(
                            () -> callOrReplayExtractLlm(_propTpl, propInputs, propModelCode,
                                    taskId, userId, spDigest, propCallIdentity, rollingProgress, BIZ_CATEGORY_MAIN_PROP_EXTRACT), getExtractExecutor());

                    // 当前窗口两个 Provider 调用已经并发启动，必须都等待并消费成功一边；余额不足只阻断
                    // 后续 chunk，不能提前返回而丢掉另一边已经付费完成的结果。
                    AtomicBoolean balanceInsufficient = new AtomicBoolean(false);
                    AtomicReference<TextTaskExecutionRejectedException> executionRejected =
                            new AtomicReference<>();
                    JsonNode sceneResult = safeGetFuture(
                            sceneFuture, "场景提取", balanceInsufficient, executionRejected);
                    JsonNode propResult = safeGetFuture(
                            propFuture, "道具提取", balanceInsufficient, executionRejected);
                    if (executionRejected.get() != null)
                    {
                        throw executionRejected.get();
                    }

                    Exception sceneError = null;
                    Exception propError = null;
                    if (Objects.isNull(sceneResult))
                    {
                        sceneError = new ServiceException("场景提取失败");
                    }
                    else
                    {
                        try
                        {
                            persistExtractCallResult(taskId, userId, sceneStableSlot, sceneResult,
                                    allAssets, rollingProgress,
                                    node -> parseAndPersistScenes(node, lib, projectId, episodeId,
                                            userId, allAssets));
                        }
                        catch (Exception e)
                        {
                            sceneError = e;
                        }
                    }
                    if (Objects.isNull(propResult))
                    {
                        propError = new ServiceException("道具提取失败");
                    }
                    else
                    {
                        try
                        {
                            persistExtractCallResult(taskId, userId, propStableSlot, propResult,
                                    allAssets, rollingProgress,
                                    node -> parseAndPersistProps(node, lib, projectId, episodeId,
                                            userId, allAssets, rollingProgress.enabled));
                        }
                        catch (Exception e)
                        {
                            propError = e;
                        }
                    }

                    // 两个子调用独立消费：一类失败时仍保留另一类成功结果，再把父任务标为部分失败。
                    if (Objects.nonNull(sceneError) || Objects.nonNull(propError))
                    {
                        TextTaskExecutionRejectedException rejected =
                                TextTaskExecutionRejectedException.find(sceneError);
                        if (rejected == null)
                        {
                            rejected = TextTaskExecutionRejectedException.find(propError);
                        }
                        if (rejected != null)
                        {
                            throw rejected;
                        }
                        log.error("场景道具并行提取存在失败: taskId={}, sceneError={}, propError={}",
                                taskId,
                                Objects.nonNull(sceneError) ? sceneError.getMessage() : "无",
                                Objects.nonNull(propError) ? propError.getMessage() : "无");
                        if (balanceInsufficient.get())
                        {
                            throw BillingBalanceErrors.preholdNotEnough();
                        }
                        throw new ServiceException("提取结果异常");
                    }
                }
                else if (runScene)
                {
                    JsonNode sceneResult = callOrReplayExtractLlm(scenePromptTemplate, sceneInputs,
                            sceneModelCode, taskId, userId, spDigest, sceneCallIdentity,
                            rollingProgress, BIZ_CATEGORY_MAIN_SCENE_EXTRACT);
                    persistExtractCallResult(taskId, userId, sceneStableSlot, sceneResult,
                            allAssets, rollingProgress,
                            node -> parseAndPersistScenes(node, lib, projectId, episodeId,
                                    userId, allAssets));
                }
                else
                {
                    JsonNode propResult = callOrReplayExtractLlm(propPromptTemplate, propInputs,
                            propModelCode, taskId, userId, spDigest, propCallIdentity,
                            rollingProgress, BIZ_CATEGORY_MAIN_PROP_EXTRACT);
                    persistExtractCallResult(taskId, userId, propStableSlot, propResult,
                            allAssets, rollingProgress,
                            node -> parseAndPersistProps(node, lib, projectId, episodeId,
                                    userId, allAssets, rollingProgress.enabled));
                }

                // 推送本轮提取到的场景/道具名
                int newCount = allAssets.size() - prevAssetCount;
                prevAssetCount = allAssets.size();
                if (newCount > 0)
                {
                    List<String> newNames = allAssets.subList(allAssets.size() - newCount, allAssets.size())
                            .stream().map(RpsAssetVO::getAssetName).toList();
                    String namesStr = String.join("、", newNames);
                    int doneProgress = calculateProgress(55, 30, i + 1, totalChunks) - 1;
                    sseManager.sendStepProgress(taskId, "extracting_scenes_props", doneProgress,
                            "scene_prop_chunk_" + i + "_done",
                            "提取到: " + namesStr, i + 1, totalChunks);
                }
            }
            catch (TextTaskExecutionRejectedException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                log.error("场景道具提取切片{}失败: taskId={}", i, taskId, e);
                // 不再抛异常（避免回滚已成功 chunk）。记录失败信息，break 中断后续 chunk。
                //   已成功 chunk 的资产已通过独立 save 落库（doExtract 已去掉 @Transactional），不会被回滚。
                //   Consumer 根据 extractionFailedChunk 判断是否标记 PARTIAL_FAILED。
                extractionFailedChunk = i;
                extractionFailedReason = StrUtil.sub(e.getMessage(), 0, 200);
                break;
            }
        }

        // 记录 chunk 级进度到 result_data，供续跑时定位起始位置
        recordChunkProgress(taskId, totalChunks, extractionFailedChunk, extractionFailedReason,
                rollingProgress);

        if (extractionFailedChunk >= 0)
        {
            log.warn("场景道具提取部分完成: taskId={}, completedChunks={}/{}, failedChunk={}",
                    taskId, extractionFailedChunk, totalChunks, extractionFailedChunk);
            return false;   // 部分失败
        }
        else
        {
            log.info("场景道具切片提取完成: 切片={}", totalChunks);
            return true;    // 全部成功
        }
    }

    /**
     * 记录 chunk 级进度到 aid_extract_task.result_data（JSON 追加）。
     * 供续跑时读取 failedChunk 定位起始位置。
     */
    private void recordChunkProgress(Long taskId, int totalChunks, int failedChunk, String failedReason,
                                     RollingExtractProgress rollingProgress)
    {
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode progress = OBJECT_MAPPER.createObjectNode();
            progress.put("totalChunks", totalChunks);
            if (failedChunk >= 0)
            {
                progress.put("failedChunk", failedChunk);
                progress.put("failedReason", StrUtil.blankToDefault(failedReason, ""));
                // completedChunks = [0, 1, ..., failedChunk-1]
                com.fasterxml.jackson.databind.node.ArrayNode completed = progress.putArray("completedChunks");
                for (int c = 0; c < failedChunk; c++)
                {
                    completed.add(c);
                }
            }
            else
            {
                // 全部成功
                com.fasterxml.jackson.databind.node.ArrayNode completed = progress.putArray("completedChunks");
                for (int c = 0; c < totalChunks; c++)
                {
                    completed.add(c);
                }
            }
            // 追加到 result_data（不覆盖已有内容）
            if (Objects.isNull(rollingProgress)
                    || StrUtil.isBlank(rollingProgress.billingTraceId))
            {
                throw new TextTaskExecutionRejectedException();
            }
            LambdaUpdateWrapper<AidExtractTask> upd = Wrappers.lambdaUpdate();
            upd.eq(AidExtractTask::getId, taskId);
            upd.eq(AidExtractTask::getBillingTraceId, rollingProgress.billingTraceId);
            upd.set(AidExtractTask::getRemark, progress.toString());
            upd.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            boolean updated = extractTaskService.update(upd);
            if (!updated)
            {
                throw new TextTaskExecutionRejectedException();
            }
        }
        catch (TextTaskExecutionRejectedException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.warn("recordChunkProgress 失败: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    @Override
    public AssetExtractTaskVO resumeExtract(Long taskId, Long userId)
    {
        ExtractResumePlan resumePlan = prepareExtractResumePlan(taskId, userId);
        AidExtractTask task = resumePlan.task();
        String originalStatus = task.getStatus();
        ExtractBillingEstimate billingEstimate = resumePlan.billingEstimate();

        boolean mqEnabled = isMqEnabled();
        String dispatchMode = mqEnabled ? "MQ" : "LOCAL";

        try
        {
            taskQueueService.executeWithTaskDispatchLock(taskId, () -> {
                // 清理上一轮遗留取消标记；锁内新产生的取消请求只在本轮入队失败时清理。
                taskQueueService.clearCancelRequested(taskId, task.getBillingTraceId());
                clearCancelFlag(taskId, task.getBillingTraceId());

                ResumeBillingContext billingContext = null;
                try
                {
                    billingContext = extractBillingService.rearmBillingForResume(taskId, userId,
                            billingEstimate.amount(), billingEstimate.snapshotJson(), originalStatus,
                            new ResumeTaskMutation(false, null, false, null, false, null),
                            dispatchMode);
                    taskQueueService.assertTaskDispatchLockHeld(taskId);
                    billingContext = extractBillingService.markResumeBillingDispatchIntent(
                            taskId, billingContext);
                    taskQueueService.assertTaskDispatchLockHeld(taskId);

                    boolean enqueued;
                    if (mqEnabled)
                    {
                        enqueued = taskQueueService.submitMqTaskNow(taskId, task.getProjectId(),
                                task.getEpisodeId(), userId, task.getModelCode(), TASK_TYPE_ASSET_EXTRACT,
                                billingContext.resumeTraceId());
                    }
                    else
                    {
                        Long projectId = task.getProjectId();
                        Long episodeId = task.getEpisodeId();
                        Runnable job = () -> runExtractLocally(taskId, projectId, episodeId, userId);
                        enqueued = taskQueueService.submitLocalTaskNow(taskId, projectId, episodeId,
                                userId, task.getModelCode(), TASK_TYPE_ASSET_EXTRACT, job,
                                billingContext.resumeTraceId());
                    }
                    if (!enqueued)
                    {
                        throw new ServiceException("任务提交失败");
                    }
                    return Boolean.TRUE;
                }
                catch (RuntimeException submitEx)
                {
                    if (billingContext != null)
                    {
                        rollbackAssetExtractResumeSubmission(taskId, userId, billingContext);
                    }
                    throw submitEx;
                }
            });
        }
        catch (RuntimeException submitEx)
        {
            log.error("资产提取续跑提交失败: taskId={}", taskId, submitEx);
            if (submitEx instanceof ServiceException serviceException)
            {
                throw serviceException;
            }
            throw new ServiceException("任务提交失败");
        }

        log.info("资产提取续跑提交: taskId={}", taskId);
        return AssetExtractTaskVO.builder()
                .taskId(taskId)
                .status(TASK_STATUS_PENDING)
                .build();
    }

    private void rollbackAssetExtractResumeSubmission(Long taskId, Long userId,
                                                       ResumeBillingContext billingContext)
    {
        // 必须先在派发锁内原子恢复业务终态并落 ROLLBACK_REQUIRED，抢回失败时禁止退款/释放名额。
        if (!extractBillingService.requestResumeBillingRollback(taskId, userId, billingContext))
        {
            log.error("资产提取续跑未能抢回任务，跳过退款: taskId={}", taskId);
            return;
        }
        releaseTaskSlots(taskId, billingContext.resumeTraceId());
        try
        {
            extractBillingService.rollbackResumeBilling(taskId, userId, billingContext);
        }
        catch (Exception rollbackEx)
        {
            log.error("资产提取续跑计费回滚失败: taskId={}", taskId, rollbackEx);
        }
        // 本次并未成功入队，清除锁内并发取消写入的标记，避免污染下一次续跑。
        taskQueueService.clearCancelRequested(taskId, billingContext.resumeTraceId());
        clearCancelFlag(taskId, billingContext.resumeTraceId());
    }

    @Override
    public void releaseTaskSlots(Long taskId, String dispatchToken)
    {
        try
        {
            taskQueueService.releaseSlots(taskId, dispatchToken);
        }
        catch (Exception e)
        {
            log.warn("释放任务周期并发名额异常, taskId={}", taskId, e);
        }
    }

    @Override
    public void markTaskProcessing(Long taskId)
    {
        try
        {
            taskQueueService.markProcessing(taskId);
        }
        catch (Exception e)
        {
            log.warn("登记任务执行租约异常, taskId={}", taskId, e);
        }
    }

    @Override
    public void touchTaskProcessing(Long taskId)
    {
        try
        {
            taskQueueService.touchProcessing(taskId);
        }
        catch (Exception e)
        {
            log.warn("续租扇入任务执行租约异常, taskId={}", taskId, e);
        }
    }

    @Override
    public void deactivateTaskProcessingHeartbeat(Long taskId)
    {
        try
        {
            taskQueueService.deactivateProcessingHeartbeat(taskId);
        }
        catch (Exception e)
        {
            log.warn("停止扇入任务心跳续租异常, taskId={}", taskId, e);
        }
    }

    @Override
    public Integer getTaskQueuePosition(Long taskId)
    {
        try
        {
            return taskQueueService.getQueuePosition(taskId);
        }
        catch (Exception e)
        {
            log.warn("查询任务排队位次异常, taskId={}", taskId, e);
            return null;
        }
    }

    @Override
    public int resetLeaselessProcessingTasks(int batchSize, boolean clearLeasesFirst)
    {
        int limit = batchSize <= 0 ? 200 : batchSize;
        List<AidExtractTask> processing = extractTaskService.list(
                Wrappers.<AidExtractTask>lambdaQuery()
                        // 启动扫描只读取回收判定所需字段，任务详情在派发锁内二次查询。
                        .select(AidExtractTask::getId, AidExtractTask::getBillingTraceId)
                        .in(AidExtractTask::getStatus,
                                TASK_STATUS_PROCESSING, TASK_STATUS_FINALIZING, TASK_STATUS_RECOVERING)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .last("ORDER BY COALESCE(update_time, create_time) ASC LIMIT " + limit));
        if (CollectionUtil.isEmpty(processing))
        {
            return 0;
        }

        if (clearLeasesFirst)
        {
            for (AidExtractTask task : processing)
            {
                try { taskQueueService.clearLease(task.getId(), task.getBillingTraceId()); }
                catch (Exception clearEx)
                {
                    log.warn("启动自愈清理执行租约异常: taskId={}", task.getId(), clearEx);
                }
            }
            log.warn("启动清空 {} 条本实例遗留租约", processing.size());
        }

        int reset = 0;
        for (AidExtractTask task : processing)
        {
            try
            {
                Boolean handled = taskQueueService.executeWithTaskDispatchLock(task.getId(),
                        () -> recoverInactiveCycle(task.getId(),
                                Set.of(TASK_STATUS_PROCESSING, TASK_STATUS_FINALIZING,
                                        TASK_STATUS_RECOVERING), "启动回收"));
                if (Boolean.TRUE.equals(handled))
                {
                    reset++;
                }
            }
            catch (Exception e)
            {
                log.error("启动回收PROCESSING任务异常, taskId={}", task.getId(), e);
            }
        }
        return reset;
    }
    @Override
    public int resetStartupPendingQueuedTasks(int batchSize)
    {
        int limit = batchSize <= 0 ? 200 : batchSize;
        Date safetyThreshold = new Date(System.currentTimeMillis() - 120_000L);
        List<AidExtractTask> pendingQueued = extractTaskService.list(
                Wrappers.<AidExtractTask>lambdaQuery()
                        // 启动扫描只读取ID；状态、trace和收据在派发锁内重新核对。
                        .select(AidExtractTask::getId)
                        .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .apply("COALESCE(update_time, create_time) < {0}", safetyThreshold)
                        .last("ORDER BY COALESCE(update_time, create_time) ASC LIMIT " + limit));
        if (CollectionUtil.isEmpty(pendingQueued))
        {
            return 0;
        }

        int reset = 0;
        Set<String> expectedStatuses = Set.of(TASK_STATUS_PENDING, TASK_STATUS_QUEUED);
        for (AidExtractTask task : pendingQueued)
        {
            try
            {
                Boolean handled = taskQueueService.executeWithTaskDispatchLock(task.getId(),
                        () -> recoverInactiveCycle(task.getId(), expectedStatuses, "启动回收"));
                if (Boolean.TRUE.equals(handled))
                {
                    reset++;
                }
            }
            catch (Exception e)
            {
                log.error("启动回收PENDING/QUEUED任务异常, taskId={}", task.getId(), e);
            }
        }
        return reset;
    }

    /**
     * 在任务派发锁内二次读取并回收确认失活的当前周期。
     * 返回 true 表示已推进终态，false 表示仍存活、状态已变化或等待下次补偿。
     */
    private boolean recoverInactiveCycle(Long taskId, Set<String> expectedStatuses, String logTag)
    {
        AidExtractTask current = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getUserId,
                                AidExtractTask::getProjectId, AidExtractTask::getEpisodeId,
                                AidExtractTask::getStatus, AidExtractTask::getTaskType,
                                AidExtractTask::getBillingTraceId, AidExtractTask::getInputSnapshot,
                                AidExtractTask::getDelFlag)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        if (current == null || !DEL_FLAG_NORMAL.equals(current.getDelFlag())
                || !expectedStatuses.contains(current.getStatus()))
        {
            return false;
        }
        String dispatchToken = current.getBillingTraceId();
        if (taskQueueService.isCurrentCycleLive(taskId, current.getStatus(), dispatchToken))
        {
            log.info("{} 当前周期仍存活，跳过: taskId={}, status={}", logTag, taskId, current.getStatus());
            return false;
        }

        // 已领取但失活的续跑必须恢复旧业务终态并退本轮冻结，不能降级成普通 FAILED。
        if (extractBillingService.hasActiveResumeBilling(taskId, dispatchToken))
        {
            boolean restored = extractBillingService.rollbackDeadResumeExecution(
                    taskId, current.getUserId(), dispatchToken);
            if (!restored)
            {
                restored = extractBillingService.recoverResumeBillingIfNeeded(
                        taskId, current.getUserId());
                AidExtractTask afterResume = extractTaskService.selectAidExtractTaskById(taskId);
                restored = restored && afterResume != null
                        && !expectedStatuses.contains(afterResume.getStatus());
            }
            if (!restored)
            {
                log.warn("{} 续跑周期尚未完成回滚: taskId={}", logTag, taskId);
                return false;
            }
            finishInactiveRecovery(current, dispatchToken);
            return true;
        }

        if (mediaGenFanInSupport.isFanInTaskType(current.getTaskType()))
        {
            if (mediaGenFanInSupport.hasInflightMedia(taskId))
            {
                mediaGenFanInSupport.renewParentLease(taskId);
                return false;
            }
            try
            {
                mediaGenFanInSupport.reconcileFanInParent(taskId, current.getTaskType());
            }
            catch (Exception e)
            {
                log.warn("{} 扇入任务对账失败: taskId={}", logTag, taskId, e);
                return false;
            }
            AidExtractTask afterFanIn = extractTaskService.selectAidExtractTaskById(taskId);
            if (afterFanIn == null || !expectedStatuses.contains(afterFanIn.getStatus()))
            {
                return true;
            }
            if (mediaGenFanInSupport.isDurableWorkflowTaskType(current.getTaskType()))
            {
                // 持久化编排对账未收口时等待下一轮事件/补偿，禁止降级到通用“服务中断”失败。
                mediaGenFanInSupport.renewParentLease(taskId);
                return false;
            }
            current = afterFanIn;
        }

        if (tryRecoverBatchTaskOnRestart(current, logTag))
        {
            finishInactiveRecovery(current, dispatchToken);
            return true;
        }

        boolean partialOutput = false;
        Map<String, Object> usageData = null;
        if (TASK_TYPE_ASSET_EXTRACT.equals(current.getTaskType()))
        {
            try
            {
                usageData = extractBillingService.aggregateTokenUsage(taskId);
            }
            catch (Exception e)
            {
                log.warn("{} 聚合用量失败，等待下次回收: taskId={}", logTag, taskId, e);
                return false;
            }
            if (!isExtractUsageAggregationComplete(usageData))
            {
                log.warn("{} 子任务用量尚未收敛: taskId={}", logTag, taskId);
                return false;
            }
            partialOutput = hasBillableExtractUsage(usageData);
        }

        String targetStatus = partialOutput ? TASK_STATUS_PARTIAL_FAILED : TASK_STATUS_FAILED;
        String errorMessage = partialOutput ? "服务中断，部分已完成" : "服务中断，已退回";
        if (!updateInactiveTaskStatus(current, targetStatus, errorMessage))
        {
            return false;
        }
        try
        {
            if (partialOutput)
            {
                extractBillingService.settleBilling(
                        taskId, current.getUserId(), usageData, current.getBillingTraceId());
            }
            else
            {
                extractBillingService.refundBilling(
                        taskId, current.getUserId(), current.getBillingTraceId());
            }
        }
        catch (Exception billingEx)
        {
            log.error("{} 计费收口失败，等待补偿: taskId={}", logTag, taskId, billingEx);
        }
        finishInactiveRecovery(current, dispatchToken);
        try { sseManager.sendError(taskId, errorMessage); } catch (Exception ignore) { }
        return true;
    }

    private boolean updateInactiveTaskStatus(AidExtractTask task, String targetStatus, String errorMessage)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, task.getId());
        update.eq(AidExtractTask::getStatus, task.getStatus());
        if (StrUtil.isBlank(task.getBillingTraceId()))
        {
            update.isNull(AidExtractTask::getBillingTraceId);
        }
        else
        {
            update.eq(AidExtractTask::getBillingTraceId, task.getBillingTraceId());
        }
        update.set(AidExtractTask::getStatus, targetStatus);
        update.set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(errorMessage));
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        return extractTaskService.getBaseMapper().update(null, update) > 0;
    }

    private void finishInactiveRecovery(AidExtractTask task, String dispatchToken)
    {
        try { releasePendingTaskSpecificLock(task, dispatchToken); } catch (Exception ignore) { }
        try { clearCancelFlag(task.getId(), dispatchToken); } catch (Exception ignore) { }
        try { taskQueueService.releaseSlots(task.getId(), dispatchToken); } catch (Exception ignore) { }
    }

    private boolean isExtractUsageAggregationComplete(Map<String, Object> usageData)
    {
        return usageData != null
                && Objects.equals(Boolean.TRUE, usageData.get("aggregation_complete"))
                && usageData.get("model_usages") instanceof Map<?, ?>;
    }

    /** 读取当前任务计费周期；LOCAL 上下文存在时必须与数据库一致。 */
    /** MQ/LOCAL worker 必须使用领取时捕获的周期令牌，不允许重读“当前 trace”代替旧 worker 续命。 */
    private String requireCurrentBillingTrace(Long taskId, String dispatchToken)
    {
        if (StrUtil.isBlank(dispatchToken))
        {
            throw new TextTaskExecutionRejectedException();
        }
        AidExtractTask current = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        String currentTraceId = current == null ? null : current.getBillingTraceId();
        if (!Objects.equals(dispatchToken, currentTraceId))
        {
            log.warn("任务周期令牌不匹配: taskId={}, expectedTrace={}, currentTrace={}",
                    taskId, dispatchToken, currentTraceId);
            throw new TextTaskExecutionRejectedException();
        }
        return dispatchToken;
    }

    /** 形态生成成功后只按关联子任务的真实 provider usage 结算。 */
    private boolean settleFormGenerateUsage(Long taskId, Long userId, String billingTraceId)
    {
        Map<String, Object> usageData = extractBillingService.aggregateTokenUsage(taskId);
        if (!isExtractUsageAggregationComplete(usageData) || !hasBillableExtractUsage(usageData))
        {
            return false;
        }
        return extractBillingService.settleBilling(taskId, userId, usageData, billingTraceId);
    }

    private boolean hasBillableExtractUsage(Map<String, Object> usageData)
    {
        return resolveUsageCount(usageData.get("billable_call_count")) > 0
                || resolveUsageCount(usageData.get("successful_call_count")) > 0
                || resolveUsageCount(usageData.get("usage_call_count")) > 0;
    }

    private int resolveUsageCount(Object value)
    {
        if (value instanceof Number number)
        {
            return Math.max(0, number.intValue());
        }
        try
        {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    @Override
    public boolean claimTaskForExecution(Long taskId, String dispatchToken)
    {
        // 兼容旧调用点，统一委托队列服务在派发锁内完成状态 CAS 与租约登记。
        return taskQueueService.claimTaskForExecution(taskId, dispatchToken);
    }

    /**
     * 重启自愈：分镜批量任务（脚本 / 图提示词 / 视频提示词）走各自专属回收策略。
     *
     * @param task   待回收任务
     * @param logTag 日志前缀（区分 RESTART / STARTUP 两条回收路径）
     * @return true=已由专属策略回收（调用方应 {@code continue}，不再走通用 token 聚合分支）
     */
    private boolean tryRecoverBatchTaskOnRestart(AidExtractTask task, String logTag)
    {
        if (Objects.isNull(task) || Objects.isNull(task.getId()))
        {
            return false;
        }
        Long taskId = task.getId();
        for (com.aid.rps.queue.BatchTaskRestartRecovery recovery : batchTaskRestartRecoveries)
        {
            if (!recovery.supports(task.getTaskType()))
            {
                continue;
            }
            boolean handled = false;
            try
            {
                handled = recovery.recover(taskId);
            }
            catch (Exception ex)
            {
                log.error("{} 分镜批量任务专属回收异常, taskId={}, taskType={}", logTag, taskId, task.getTaskType(), ex);
            }
            if (handled)
            {
                try { releasePendingTaskSpecificLock(task, task.getBillingTraceId()); } catch (Exception ignore) { }
                try { clearCancelFlag(taskId, task.getBillingTraceId()); } catch (Exception ignore) { }
                try { taskQueueService.releaseSlots(taskId, task.getBillingTraceId()); } catch (Exception ignore) { }
                try { sseManager.sendError(taskId, "服务重启中断"); } catch (Exception ignore) { }
            }
            // supports 命中即认定本类型由专属策略负责；recover 返回 false（任务不存在等）才交回通用回收
            return handled;
        }
        return false;
    }

    /**
     * 重启自愈专用 CAS 状态更新：仅 PROCESSING → 目标终态。
     *
     * @return true=CAS 成功
     */
    /**
     * 启动期遗留死单专用 CAS：仅 PENDING/QUEUED → 目标终态。
     */
    /**
     * worker 领取任务后再执行覆盖删除。删除和 overwriteApplied 标记共用一个事务：
     * 删除成功但标记失败会整体回滚，进程重启后可以安全重试；续跑看到标记后不会重复删除。
     */
    @SuppressWarnings("unchecked")
    private void applyOverwriteOnce(Long taskId, Long projectId, Long episodeId,
                                    List<String> extractTypes, String dispatchToken)
    {
        transactionTemplate.executeWithoutResult(status -> {
            AidExtractTask current = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getProjectId,
                                    AidExtractTask::getEpisodeId, AidExtractTask::getStatus,
                                    AidExtractTask::getBillingTraceId,
                                    AidExtractTask::getInputSnapshot)
                            .eq(AidExtractTask::getId, taskId)
                            .last("FOR UPDATE"), false);
            if (current == null
                    || !Objects.equals(TASK_STATUS_PROCESSING, current.getStatus())
                    || !Objects.equals(projectId, current.getProjectId())
                    || !Objects.equals(episodeId, current.getEpisodeId())
                    || StrUtil.isBlank(dispatchToken)
                    || !Objects.equals(dispatchToken, current.getBillingTraceId()))
            {
                log.info("覆盖删除跳过，任务周期已变化: taskId={}", taskId);
                throw new TextTaskExecutionRejectedException();
            }
            String snapshotJson = current.getInputSnapshot();
            if (StrUtil.isBlank(snapshotJson) || !snapshotJson.trim().startsWith("{"))
            {
                return;
            }
            Map<String, Object> snapshot;
            try
            {
                snapshot = OBJECT_MAPPER.readValue(snapshotJson, Map.class);
            }
            catch (Exception e)
            {
                log.error("解析覆盖快照失败: taskId={}", taskId, e);
                throw new ServiceException("任务数据异常");
            }
            if (!Boolean.TRUE.equals(snapshot.get("overwrite"))
                    || Boolean.TRUE.equals(snapshot.get("overwriteApplied")))
            {
                return;
            }

            softDeleteAutoExtractedAssets(projectId, episodeId, extractTypes, taskId);
            snapshot.put("overwriteApplied", true);
            String updatedSnapshot;
            try
            {
                updatedSnapshot = OBJECT_MAPPER.writeValueAsString(snapshot);
            }
            catch (Exception e)
            {
                log.error("更新覆盖快照失败: taskId={}", taskId, e);
                throw new ServiceException("任务数据异常");
            }
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
            if (StrUtil.isBlank(current.getBillingTraceId()))
            {
                update.isNull(AidExtractTask::getBillingTraceId);
            }
            else
            {
                update.eq(AidExtractTask::getBillingTraceId, current.getBillingTraceId());
            }
            update.set(AidExtractTask::getInputSnapshot, updatedSnapshot);
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            if (extractTaskService.getBaseMapper().update(null, update) == 0)
            {
                log.info("覆盖删除标记竞争失败: taskId={}", taskId);
                throw new TextTaskExecutionRejectedException();
            }
        });
    }

    /**
     * 重新生成时软删已有自动提取资产（create_source='auto'）。
     */
    private void softDeleteAutoExtractedAssets(Long projectId, Long episodeId, List<String> extractTypes,
                                               Long taskId)
    {
        LambdaQueryWrapper<AidRolePropScene> mainQuery = Wrappers.lambdaQuery();
        mainQuery.eq(AidRolePropScene::getProjectId, projectId);
        mainQuery.eq(AidRolePropScene::getEpisodeId, episodeId);
        mainQuery.eq(AidRolePropScene::getCreateSource, "auto");
        mainQuery.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        if (CollectionUtil.isNotEmpty(extractTypes))
        {
            mainQuery.in(AidRolePropScene::getAssetType, extractTypes);
        }
        mainQuery.select(AidRolePropScene::getId, AidRolePropScene::getAssetType);
        List<AidRolePropScene> toDelete = rpsService.list(mainQuery);
        if (CollectionUtil.isEmpty(toDelete))
        {
            log.info("重新生成：无需删除的自动提取资产: projectId={}, episodeId={}, types={}",
                    projectId, episodeId, extractTypes);
            return;
        }
        List<Long> mainIds = toDelete.stream().map(AidRolePropScene::getId).collect(java.util.stream.Collectors.toList());
        List<Long> sceneIds = toDelete.stream()
                .filter(asset -> Objects.equals(ASSET_TYPE_SCENE, asset.getAssetType()))
                .map(AidRolePropScene::getId)
                .collect(java.util.stream.Collectors.toList());
        Date softDeleteTime = DateUtils.getNowDate();
        log.info("重新生成：准备软删自动提取资产: projectId={}, episodeId={}, types={}, count={}",
                projectId, episodeId, extractTypes, mainIds.size());

        LambdaUpdateWrapper<AidRolePropSceneFormImage> imgDel = Wrappers.lambdaUpdate();
        imgDel.in(AidRolePropSceneFormImage::getAssetId, mainIds);
        imgDel.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        imgDel.set(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_DELETED);
        imgDel.set(AidRolePropSceneFormImage::getUpdateTime, softDeleteTime);
        rpsFormImageService.update(imgDel);

        LambdaUpdateWrapper<AidRolePropSceneForm> formDel = Wrappers.lambdaUpdate();
        formDel.in(AidRolePropSceneForm::getAssetId, mainIds);
        formDel.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        formDel.set(AidRolePropSceneForm::getDelFlag, DEL_FLAG_DELETED);
        formDel.set(AidRolePropSceneForm::getUpdateTime, softDeleteTime);
        rpsFormService.update(formDel);

        LambdaUpdateWrapper<AidRoleVoiceBinding> voiceDel = Wrappers.lambdaUpdate();
        voiceDel.in(AidRoleVoiceBinding::getAssetId, mainIds);
        voiceDel.eq(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_NORMAL);
        voiceDel.set(AidRoleVoiceBinding::getDelFlag, DEL_FLAG_DELETED);
        voiceDel.set(AidRoleVoiceBinding::getUpdateTime, softDeleteTime);
        roleVoiceBindingService.update(voiceDel);

        LambdaUpdateWrapper<AidRolePropScene> mainDel = Wrappers.lambdaUpdate();
        mainDel.in(AidRolePropScene::getId, mainIds);
        mainDel.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        mainDel.set(AidRolePropScene::getDelFlag, DEL_FLAG_DELETED);
        mainDel.set(AidRolePropScene::getDeleteReason, DELETE_REASON_AUTO_OVERWRITE);
        mainDel.set(AidRolePropScene::getDeletedAt, softDeleteTime);
        mainDel.set(AidRolePropScene::getDeleteTaskId, taskId);
        mainDel.set(AidRolePropScene::getUpdateTime, softDeleteTime);
        rpsService.update(mainDel);

        //    plot 行通过 scene_id 外键挂在 mainIds 上；character/prop 不需要级联
        if (CollectionUtil.isNotEmpty(sceneIds))
        {
            LambdaUpdateWrapper<AidScenePlot> plotDel = Wrappers.lambdaUpdate();
            plotDel.in(AidScenePlot::getSceneId, sceneIds);
            plotDel.eq(AidScenePlot::getCreateSource, "auto");
            plotDel.eq(AidScenePlot::getDelFlag, DEL_FLAG_NORMAL);
            plotDel.set(AidScenePlot::getDelFlag, DEL_FLAG_DELETED);
            plotDel.set(AidScenePlot::getUpdateTime, softDeleteTime);
            plotDel.set(AidScenePlot::getUpdateBy, "system");
            scenePlotService.update(plotDel);
        }

        log.info("重新生成：软删完成: projectId={}, episodeId={}, deletedMainCount={}", projectId, episodeId, mainIds.size());
    }
    private void parseAndPersistCharacters(JsonNode result, ExistingAssetLib lib,
                                           Long projectId, Long episodeId, Long userId,
                                           List<RpsAssetVO> allAssets, boolean strictPersistence)
    {
        if (Objects.isNull(result))
        {
            if (strictPersistence)
            {
                throw new ServiceException("角色结果异常");
            }
            return;
        }

        // 字段语义约定：character 提取阶段不再向 aid_role_prop_scene.remark 写任何结构化 JSON。
        // 角色结构化字段全部走主表独立列（name / aliases_name / introduction / gender / age_range /
        // role_level / profile_data / expected_appearances 等）；
        // updated_characters 命中已有角色后，仅按主表列做"introduction 追加 + aliases_name 并集"

        JsonNode newChars = result.get("new_characters");
        JsonNode updatedChars = result.get("updated_characters");
        if (strictPersistence
                && !((Objects.nonNull(newChars) && newChars.isArray())
                || (Objects.nonNull(updatedChars) && updatedChars.isArray())))
        {
            throw new ServiceException("角色结果异常");
        }
        if (Objects.nonNull(newChars) && newChars.isArray())
        {
            for (JsonNode item : newChars)
            {
                try
                {
                    if (strictPersistence
                            && (!item.isObject() || StrUtil.isBlank(helper.getJsonText(item, "name"))))
                    {
                        throw new ServiceException("角色结果异常");
                    }
                    persistSingleCharacter(item, lib, projectId, episodeId, userId, allAssets);
                }
                catch (Exception e)
                {
                    String name = helper.getJsonText(item, "name");
                    log.error("持久化单条角色失败: name={}", name, e);
                    if (strictPersistence)
                    {
                        throw new ServiceException("角色保存失败");
                    }
                }
            }
        }

        // 处理 updated_characters
        if (Objects.nonNull(updatedChars) && updatedChars.isArray())
        {
            for (JsonNode item : updatedChars)
            {
                try
                {
                    String name = helper.getJsonText(item, "name");
                    if (StrUtil.isBlank(name))
                    {
                        if (strictPersistence)
                        {
                            throw new ServiceException("角色结果异常");
                        }
                        continue;
                    }
                    String updatedIntroduction = helper.getJsonText(item, "updated_introduction");
                    List<String> updatedAliases = helper.getJsonTextArray(item, "updated_aliases");
                    // updated_characters 必须真实落库到主表已有角色行：按 name 项目级匹配，
                    // 合并 introduction（追加）+ aliases_name（并集去重）；不写 remark
                    Long matchedAssetId = updateCharacterInfo(name, updatedIntroduction, updatedAliases,
                            projectId, userId, strictPersistence);
                    // 形态增量（协议可选字段 new_appearances）：已有角色出现持久性外观变化
                    // （长大/衰老/换装/伤疤等）时新增 pending 形态壳；模型未输出该字段则不处理
                    if (Objects.nonNull(matchedAssetId))
                    {
                        mergeCharacterAppearances(matchedAssetId, item, projectId, userId,
                                strictPersistence);
                    }
                }
                catch (Exception e)
                {
                    log.error("更新角色信息失败", e);
                    if (strictPersistence)
                    {
                        throw new ServiceException("角色保存失败");
                    }
                }
            }
        }
    }

    /**
     * 合并角色形态增量：消费模型输出的 {@code new_appearances} 数组
     * （元素：name / change_reason / visual_delta），为已有角色新增 pending 形态壳。
     * 形态按「资产ID + 形态全名」去重：已存在只记录日志不重复创建（幂等，重复执行/续跑安全）；
     * 形态ID 由服务端生成（数据库自增主键），不使用模型返回的任何下标。
     *
     * @param assetId   已有角色主资产ID
     * @param item      updated_characters 单条节点
     * @param projectId 项目ID
     * @param userId    用户ID
     */
    private void mergeCharacterAppearances(Long assetId, JsonNode item, Long projectId, Long userId,
                                           boolean strictPersistence)
    {
        JsonNode appearances = item.get("new_appearances");
        if (Objects.isNull(appearances) || !appearances.isArray() || appearances.isEmpty())
        {
            return;
        }
        // 查询字段精简：形态去重只需 id/name（新增使用字段时此处必须同步补充）
        List<AidRolePropSceneForm> existingForms = rpsFormService.list(
                Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getName)
                        .eq(AidRolePropSceneForm::getAssetId, assetId)
                        .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL));
        Set<String> existingNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (AidRolePropSceneForm form : existingForms)
        {
            if (StrUtil.isNotBlank(form.getName()))
            {
                existingNames.add(form.getName().trim());
            }
        }
        // 主资产归属：形态与主资产同维度落库（剧集角色为项目级 episodeId=0）
        AidRolePropScene asset = rpsService.getById(assetId);
        if (Objects.isNull(asset))
        {
            return;
        }
        for (JsonNode appearance : appearances)
        {
            try
            {
                String appearanceName = helper.getJsonText(appearance, "name");
                if (StrUtil.isBlank(appearanceName))
                {
                    continue;
                }
                // 形态全名规则与全量提取一致：资产名_形态名
                String composedName = asset.getName() + "_" + appearanceName.trim();
                if (existingNames.contains(composedName))
                {
                    log.info("形态增量命中已有形态,跳过创建: assetId={}, formName={}", assetId, composedName);
                    continue;
                }
                String changeReason = helper.getJsonText(appearance, "change_reason");
                // visual_delta：紧凑外观变化描述（JSON 对象转文本存 promptText，供后续形态视觉描述/生图参考）
                JsonNode visualDelta = appearance.get("visual_delta");
                String promptText = Objects.nonNull(visualDelta) && !visualDelta.isNull()
                        ? visualDelta.toString() : null;

                AidRolePropSceneForm form = new AidRolePropSceneForm();
                form.setAssetId(assetId);
                form.setProjectId(projectId);
                form.setEpisodeId(asset.getEpisodeId());
                form.setUserId(userId);
                form.setName(composedName);
                form.setChangeReason(StrUtil.blankToDefault(changeReason, appearanceName.trim()));
                form.setPromptText(promptText);
                // pending 形态壳：只建定义不自动生图/生视觉描述，费用由用户后续显式触发的统一批量任务承担
                form.setVisualDescStatus(StrUtil.isNotBlank(promptText)
                        ? VISUAL_DESC_STATUS_COMPLETED : VISUAL_DESC_STATUS_PENDING);
                form.setCreateSource("auto");
                form.setDelFlag(DEL_FLAG_NORMAL);
                form.setCreateTime(DateUtils.getNowDate());
                form.setCreateBy(String.valueOf(userId));
                if (!rpsFormService.save(form))
                {
                    throw new ServiceException("形态保存失败");
                }
                existingNames.add(composedName);
                log.info("形态增量新增: assetId={}, formId={}, formName={}, changeReason={}",
                        assetId, form.getId(), composedName, changeReason);
            }
            catch (Exception e)
            {
                // 单条形态失败不阻断其余形态与整批提取
                log.error("形态增量单条创建失败: assetId={}", assetId, e);
                if (strictPersistence)
                {
                    throw new ServiceException("形态保存失败");
                }
            }
        }
    }

    private void persistSingleCharacter(JsonNode item, ExistingAssetLib lib,
                                        Long projectId, Long episodeId, Long userId,
                                        List<RpsAssetVO> allAssets)
    {
        String name = helper.getJsonText(item, "name");
        if (StrUtil.isBlank(name))
        {
            return;
        }

        List<String> newAliases = helper.parseAliases(name, helper.getJsonText(item, "aliases"));
        if (helper.matchesAnyAlias(name, newAliases, lib.getCharacterNames(), lib.getCharacterAliasMap()))
        {
            return;
        }

        String introduction = helper.getJsonText(item, "introduction");
        String gender = helper.getJsonText(item, "gender");
        String ageRange = helper.getJsonText(item, "age_range");
        String roleLevel = helper.getJsonText(item, "role_level");
        String expectedAppearances = buildExpectedAppearances(item);
        List<String> aliasesList = helper.getJsonTextArray(item, "aliases");
        String aliasesName = aliasesList.isEmpty() ? null : String.join(",", aliasesList);
        // 角色 profileData：保留原有抽取字段（archetype 等）+ 叠加主表冗余快照字段
        String profileData = buildCharacterProfileData(item, name, aliasesName, introduction, gender, ageRange, roleLevel);

        // 主表
        AidRolePropScene mainEntity = new AidRolePropScene();
        mainEntity.setProjectId(projectId);
        mainEntity.setEpisodeId(episodeId);
        mainEntity.setUserId(userId);
        mainEntity.setName(name);
        mainEntity.setAliasesName(aliasesName);
        mainEntity.setIntroduction(introduction);
        mainEntity.setGender(gender);
        mainEntity.setAgeRange(ageRange);
        mainEntity.setRoleLevel(roleLevel);
        mainEntity.setProfileData(profileData);
        mainEntity.setExpectedAppearances(expectedAppearances);
        mainEntity.setAssetType(ASSET_TYPE_CHARACTER);
        mainEntity.setDelFlag(DEL_FLAG_NORMAL);
        mainEntity.setCreateTime(DateUtils.getNowDate());
        // 创建者审计：与 update_by 对称，使用当前提取任务的 userId 写入 create_by
        mainEntity.setCreateBy(String.valueOf(userId));
        // 自动提取链路标记
        mainEntity.setCreateSource("auto");
        // 注意：character 提取阶段不再向 remark 写入结构化 JSON，
        // 角色结构信息全部走主表独立列（name / aliases_name / introduction / gender / age_range /
        // role_level / profile_data / expected_appearances 等），remark 留作业务运营备注用途
        if (!rpsService.save(mainEntity) || Objects.isNull(mainEntity.getId()))
        {
            throw new ServiceException("角色保存失败");
        }

        // 批量提取只保存主表，形态由用户单独触发生成
        allAssets.add(buildAssetVO(mainEntity, List.of()));
        lib.addCharacter(name, newAliases);
    }
    /**
     * 场景持久化总入口：统一名称和时间后，仅保存可复用场景资产。
     */
    private void parseAndPersistScenes(JsonNode result, ExistingAssetLib lib,
                                       Long projectId, Long episodeId, Long userId,
                                       List<RpsAssetVO> allAssets)
    {
        if (Objects.isNull(result))
        {
            log.error("场景提取结果为空: projectId={}, episodeId={}", projectId, episodeId);
            throw new ServiceException("场景结果异常");
        }

        JsonNode locations = result.get("locations");
        if (Objects.isNull(locations) || !locations.isArray() || locations.isEmpty())
        {
            log.error("场景提取缺少有效 locations: projectId={}, episodeId={}", projectId, episodeId);
            throw new ServiceException("场景结果异常");
        }

        int successCount = 0;
        int failedCount = 0;

        for (JsonNode item : locations)
        {
            try
            {
                NormalizedScene normalizedScene = SceneExtractionNormalizer.normalize(item);
                if (normalizedScene.defaulted())
                {
                    log.warn("场景时间缺失，使用统一默认时间: projectId={}, episodeId={}, sceneName={}",
                            projectId, episodeId, normalizedScene.canonicalName());
                }
                persistSingleScene(normalizedScene, lib, projectId, episodeId, userId, allAssets);
                successCount++;
            }
            catch (Exception e)
            {
                String name = helper.getJsonText(item, "name");
                log.error("持久化单条场景失败: name={}", name, e);
                failedCount++;
            }
        }

        if (failedCount > 0)
        {
            log.error("场景提取结果不完整: projectId={}, episodeId={}, total={}, success={}, failed={}",
                    projectId, episodeId, locations.size(), successCount, failedCount);
            throw new ServiceException("场景结果异常");
        }
    }

    /**
     * 单条 location 落库。
     *
     * 以“地点_时间”权威名称作为场景去重键，复用已有场景资产。
     */
    private void persistSingleScene(NormalizedScene normalizedScene, ExistingAssetLib lib,
                                    Long projectId, Long episodeId, Long userId,
                                    List<RpsAssetVO> allAssets)
    {
        JsonNode item = normalizedScene.item();
        String sceneName = normalizedScene.canonicalName();

        Long sceneId = lib.findSceneIdByName(sceneName);
        AidRolePropScene mainEntity = null;
        if (Objects.nonNull(sceneId))
        {
            log.info("场景资产复用: sceneName={}, sceneId={}", sceneName, sceneId);
        }
        else
        {
            String summary = helper.getJsonText(item, "summary");
            boolean hasCrowd = item.has("has_crowd") && item.get("has_crowd").asBoolean(false);
            String crowdDescription = helper.getJsonText(item, "crowd_description");
            List<String> availableSlots = helper.getJsonTextArray(item, "available_slots");
            String availableSlotsJson = availableSlots.isEmpty() ? null : helper.toJsonString(availableSlots);
            String introduction = helper.getJsonText(item, "introduction");

            mainEntity = new AidRolePropScene();
            mainEntity.setProjectId(projectId);
            mainEntity.setEpisodeId(episodeId);
            mainEntity.setUserId(userId);
            mainEntity.setName(sceneName);
            mainEntity.setIntroduction(StrUtil.isNotBlank(introduction) ? introduction : null);
            mainEntity.setSummary(summary);
            mainEntity.setAvailableSlots(availableSlotsJson);
            mainEntity.setHasCrowd(hasCrowd ? 1 : 0);
            mainEntity.setCrowdDescription(crowdDescription);

            // 场景提取只负责稳定的空间视觉信息，场次编号由分镜生成阶段分配。
            mainEntity.setProfileData(buildSceneProfileData(item, sceneName, introduction, summary,
                    availableSlotsJson, hasCrowd, crowdDescription));
            mainEntity.setAssetType(ASSET_TYPE_SCENE);
            mainEntity.setDelFlag(DEL_FLAG_NORMAL);
            mainEntity.setCreateTime(DateUtils.getNowDate());
            mainEntity.setCreateBy(String.valueOf(userId));
            mainEntity.setUpdateTime(DateUtils.getNowDate());
            mainEntity.setUpdateBy(String.valueOf(userId));
            mainEntity.setCreateSource("auto");
            if (!rpsService.save(mainEntity) || Objects.isNull(mainEntity.getId()))
            {
                log.error("场景主资产保存失败: projectId={}, episodeId={}, sceneName={}",
                        projectId, episodeId, sceneName);
                throw new ServiceException("场景保存失败");
            }
            sceneId = mainEntity.getId();
            lib.addScene(sceneName, sceneId);
            allAssets.add(buildAssetVO(mainEntity, List.of()));
        }
    }

    private void parseAndPersistProps(JsonNode result, ExistingAssetLib lib,
                                      Long projectId, Long episodeId, Long userId,
                                      List<RpsAssetVO> allAssets, boolean strictPersistence)
    {
        if (Objects.isNull(result))
        {
            if (strictPersistence)
            {
                throw new ServiceException("道具结果异常");
            }
            return;
        }

        JsonNode props = result.get("props");
        if (Objects.isNull(props) || !props.isArray())
        {
            if (strictPersistence)
            {
                throw new ServiceException("道具结果异常");
            }
            return;
        }

        for (JsonNode item : props)
        {
            try
            {
                if (strictPersistence
                        && (!item.isObject()
                        || StrUtil.isBlank(helper.getJsonText(item, "name"))
                        || StrUtil.isBlank(helper.getJsonText(item, "summary"))
                        || StrUtil.isBlank(helper.getJsonText(item, "description"))))
                {
                    throw new ServiceException("道具结果异常");
                }
                persistSingleProp(item, lib, projectId, episodeId, userId, allAssets);
            }
            catch (Exception e)
            {
                String name = helper.getJsonText(item, "name");
                log.error("持久化单条道具失败: name={}", name, e);
                if (strictPersistence)
                {
                    throw new ServiceException("道具保存失败");
                }
            }
        }
    }

    private void persistSingleProp(JsonNode item, ExistingAssetLib lib,
                                   Long projectId, Long episodeId, Long userId,
                                   List<RpsAssetVO> allAssets)
    {
        String name = helper.getJsonText(item, "name");
        String summary = helper.getJsonText(item, "summary");
        String description = helper.getJsonText(item, "description");
        if (StrUtil.isBlank(name) || StrUtil.isBlank(summary) || StrUtil.isBlank(description))
        {
            return;
        }

        List<String> newAliases = helper.parseAliases(name, null);
        if (helper.matchesAnyAlias(name, newAliases, lib.getPropNames(), lib.getPropAliasMap()))
        {
            return;
        }

        // 清洗道具视觉描述（移除AI后缀污染）
        String cleanDescription = helper.resolvePropVisualDescription(name, summary, description);

        // 主表
        AidRolePropScene mainEntity = new AidRolePropScene();
        mainEntity.setProjectId(projectId);
        mainEntity.setEpisodeId(episodeId);
        mainEntity.setUserId(userId);
        mainEntity.setName(name);
        mainEntity.setSummary(summary);
        // 清洗后的视觉描述同步到主表 introduction 列
        mainEntity.setIntroduction(cleanDescription);
        // 道具 profileData：收拢 summary + introduction（清洗后的视觉描述）+ 融合版扩展字段
        mainEntity.setProfileData(buildPropProfileData(item, name, summary, cleanDescription));
        mainEntity.setAssetType(ASSET_TYPE_PROP);
        mainEntity.setDelFlag(DEL_FLAG_NORMAL);
        mainEntity.setCreateTime(DateUtils.getNowDate());
        // 自动提取链路标记
        mainEntity.setCreateSource("auto");
        if (!rpsService.save(mainEntity) || Objects.isNull(mainEntity.getId()))
        {
            throw new ServiceException("道具保存失败");
        }

        // 批量提取只保存主表，形态由用户单独触发生成
        allAssets.add(buildAssetVO(mainEntity, List.of()));
        lib.addProp(name, newAliases);
    }
    /**
     * 处理 updated_characters：根据 name 命中"当前项目"已有角色主资产并合并信息。
     *
     * @param name                 已有角色名（必须与资产库 name 完全一致）
     * @param updatedIntroduction  补充的介绍（可能为空，空则不更新 introduction 列）
     * @param updatedAliases       新发现的别名（可能为空，空则不更新 aliases_name 列）
     * @param projectId            当前项目ID（用于项目级匹配）
     * @param userId               当前提取任务的执行者，用于审计列 update_by；不参与查询过滤
     * @return 命中的角色主资产ID（供形态增量使用）；未命中返回 null
     */
    private Long updateCharacterInfo(String name, String updatedIntroduction,
                                     List<String> updatedAliases,
                                     Long projectId, Long userId, boolean strictPersistence)
    {
        try
        {
            // 命中"当前项目"已有角色：项目级查找，与 loadCharactersLibInfo 的查询范围严格对齐，
            // 避免出现"提示词里看得到、更新时改不到"的假更新；仅查 id / introduction / aliases_name 三列
            LambdaQueryWrapper<AidRolePropScene> query = Wrappers.lambdaQuery();
            query.select(AidRolePropScene::getId,
                    AidRolePropScene::getIntroduction,
                    AidRolePropScene::getAliasesName);
            query.eq(AidRolePropScene::getProjectId, projectId);
            query.eq(AidRolePropScene::getUserId, userId);
            query.eq(AidRolePropScene::getName, name);
            query.eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER);
            query.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
            // 同名稳定取最早一条，避免历史脏数据导致更新随机命中
            query.orderByAsc(AidRolePropScene::getId);
            query.last("LIMIT 1");
            AidRolePropScene existing = rpsService.getOne(query, false);

            if (Objects.isNull(existing))
            {
                // 未命中：常见于模型把新角色误归入 updated_characters，记录日志便于排查，不抛异常
                log.info("updated_characters 未命中已有角色: projectId={}, name={}", projectId, name);
                return null;
            }

            // 无任何有效更新内容则不写库，避免空更新（仍返回命中ID，形态增量不依赖信息更新）
            boolean hasIntroductionUpdate = StrUtil.isNotBlank(updatedIntroduction);
            boolean hasAliasUpdate = CollectionUtil.isNotEmpty(updatedAliases);
            if (!hasIntroductionUpdate && !hasAliasUpdate)
            {
                return existing.getId();
            }

            LambdaUpdateWrapper<AidRolePropScene> update = Wrappers.lambdaUpdate();
            update.eq(AidRolePropScene::getId, existing.getId());
            update.eq(AidRolePropScene::getProjectId, projectId);
            update.eq(AidRolePropScene::getUserId, userId);
            update.eq(AidRolePropScene::getAssetType, ASSET_TYPE_CHARACTER);
            update.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
            update.set(AidRolePropScene::getUpdateTime, DateUtils.getNowDate());
            // 更新者：使用当前提取任务的 userId，保持创建者 / 更新者审计一致
            update.set(AidRolePropScene::getUpdateBy, String.valueOf(userId));

            // 介绍合并：保留原介绍 + 追加新增介绍；
            // 防重复：若新介绍（trim 后）已是原介绍的子串，则跳过追加，
            // 避免同一角色被多个切片反复命中后 introduction 越拼越长且内容重复
            if (hasIntroductionUpdate)
            {
                String existingIntro = StrUtil.blankToDefault(existing.getIntroduction(), "");
                String addText = updatedIntroduction.trim();
                if (existingIntro.isEmpty())
                {
                    update.set(AidRolePropScene::getIntroduction, addText);
                }
                else if (!existingIntro.contains(addText))
                {
                    update.set(AidRolePropScene::getIntroduction, existingIntro + " " + addText);
                }
                // else: 子串已存在，跳过更新（不改 introduction 列）
            }

            // 别名合并：原别名 ∪ 新发现别名，忽略大小写去重，逗号拼接
            if (hasAliasUpdate)
            {
                String existingAliases = StrUtil.blankToDefault(existing.getAliasesName(), "");
                Set<String> allAliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (String a : existingAliases.split("[,，]"))
                {
                    String t = a.trim();
                    if (StrUtil.isNotBlank(t)) { allAliases.add(t); }
                }
                for (String a : updatedAliases)
                {
                    if (StrUtil.isNotBlank(a)) { allAliases.add(a.trim()); }
                }
                update.set(AidRolePropScene::getAliasesName, String.join(",", allAliases));
            }

            if (!rpsService.update(update))
            {
                throw new ServiceException("角色保存失败");
            }
            log.info("updated_characters 命中并更新: projectId={}, name={}, intro={}, aliases={}",
                    projectId, name, hasIntroductionUpdate, hasAliasUpdate);
            return existing.getId();
        }
        catch (Exception e)
        {
            // 旧父任务保留单条失败继续；滚动子调用必须让本次业务落库事务整体失败。
            log.error("更新角色信息失败: name={}", name, e);
            if (strictPersistence)
            {
                throw e instanceof RuntimeException runtimeException
                        ? runtimeException : new ServiceException("角色保存失败");
            }
            return null;
        }
    }
    private boolean isMqEnabled()
    {
        try
        {
            return rocketMqConfigManager.isEnabled();
        }
        catch (Exception e)
        {
            log.warn("读取MQ配置失败，默认不启用MQ: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 构建表单批量任务的本地执行 job：交由统一终态编排器执行同一套自洽执行体（doFormXxxBatch），
     * 与 MQ Consumer 的 handleFormXxxBatch 终态语义一致。
     */
    private Runnable buildFormBatchLocalJob(Long taskId, Long userId,
                                            com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec spec,
                                            java.util.function.Supplier<String> body)
    {
        return () -> batchTaskLocalOrchestrator.run(taskId, userId, spec, body, () -> {
            String dispatchToken = taskQueueService.currentLocalDispatchToken(taskId);
            if (Objects.equals(TASK_TYPE_FORM_GENERATE_BATCH, spec.taskType))
            {
                try
                {
                    boolean billingClosed = extractBillingService.settleOrRefundAfterExecutionFailure(
                            taskId, userId, dispatchToken);
                    if (!billingClosed)
                    {
                        log.warn("本地批量形态生成异常计费尚未收敛，保留冻结等待补偿: taskId={}", taskId);
                    }
                }
                catch (Exception billingEx)
                {
                    log.error("本地批量形态生成异常计费收口失败: taskId={}", taskId, billingEx);
                }
            }
            else
            {
                settleFormBatchDispatchCycle(taskId, userId, spec.taskType, dispatchToken);
            }
            releaseBatchFormLocks(taskId, spec.taskType, dispatchToken);
        });
    }

    /** 图片批量父任务仅收口零金额派发周期，子图片继续由媒体主链路独立计费。 */
    private void settleFormBatchDispatchCycle(Long taskId, Long userId, String taskType,
                                              String dispatchToken)
    {
        if (Objects.equals(TASK_TYPE_FORM_GENERATE_BATCH, taskType)
                || StrUtil.isBlank(dispatchToken))
        {
            return;
        }
        try
        {
            boolean closed = extractBillingService.settleBilling(taskId, userId,
                    Map.of("aggregation_complete", true), dispatchToken);
            if (!closed)
            {
                log.warn("图片批量父周期尚未收口: taskId={}, taskType={}", taskId, taskType);
            }
        }
        catch (Exception billingEx)
        {
            log.error("图片批量父周期收口失败: taskId={}, taskType={}", taskId, taskType, billingEx);
        }
    }
    /**
     * 事务提交后入队（asset_extract）。
     * MQ 开启 → 走 MQ 派发模式；MQ 关闭 → 走本地派发模式（注册本地执行 job）。
     * 入队失败（CAS 未命中或异常）兜底：标记 FAILED + 退款 + 释放锁 + SSE 通知。
     */
    private boolean enqueueExtractAfterCommit(Long taskId, Long projectId, Long episodeId,
                                              Long userId, String modelCode, boolean mqEnabled)
    {
        try
        {
            boolean enqueued;
            if (mqEnabled)
            {
                // 本方法运行在事务 afterCommit 回调内，必须用 *Now 强制立即入队，
                // 避免 enqueue() 再次判定事务活跃而二次注册 afterCommit（新回调不再执行 → doEnqueue 丢失 → 任务永久 PENDING）。
                enqueued = taskQueueService.submitMqTaskNow(taskId, projectId, episodeId, userId,
                        modelCode, TASK_TYPE_ASSET_EXTRACT);
            }
            else
            {
                // 本地模式：把原 executeAsync 的执行体封装成 job，由调度放行时执行
                Runnable job = () -> runExtractLocally(taskId, projectId, episodeId, userId);
                enqueued = taskQueueService.submitLocalTaskNow(taskId, projectId, episodeId, userId,
                        modelCode, TASK_TYPE_ASSET_EXTRACT, job);
            }
            if (!enqueued)
            {
                // 入队未成立：可能是 doEnqueue 关键步骤失败回滚成 PENDING（需兜底失败+退款），
                // 也可能是任务已被取消/推进（updateTaskFailed CAS 不命中则自动跳过，不会误退款）
                log.warn("提取任务入队失败(已回滚PENDING/被取消推进)，走失败兜底: taskId={}", taskId);
                failExtractEnqueue(taskId, projectId, episodeId, userId);
                return false;
            }
            return true;
        }
        catch (Exception ex)
        {
            log.error("提取任务入队异常, 显式退款 + 标记FAILED, taskId={}", taskId, ex);
            failExtractEnqueue(taskId, projectId, episodeId, userId);
            return false;
        }
    }

    /**
     * 提取任务入队失败兜底：CAS 标记 FAILED；仅当本调用赢得状态机推进（任务原本 PENDING/QUEUED/PROCESSING）
     * 才执行退款 + 释放锁 + 释放名额 + SSE，避免对已取消/已终态任务重复退款。退款本身亦幂等（仅退 FROZEN）。
     */
    private void failExtractEnqueue(Long taskId, Long projectId, Long episodeId, Long userId)
    {
        AidExtractTask current = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        String expectedTraceId = current == null ? null : current.getBillingTraceId();
        boolean failed;
        try { failed = updateTaskFailedBeforeExecution(taskId, expectedTraceId, "任务提交失败"); }
        catch (Exception e) { log.error("提取任务入队失败兜底标记FAILED异常, taskId={}", taskId, e); failed = false; }
        if (!failed)
        {
            log.info("提取任务入队失败兜底跳过(任务已非PENDING/QUEUED/PROCESSING): taskId={}", taskId);
            return;
        }
        try { extractBillingService.refundBilling(taskId, userId, expectedTraceId); }
        catch (Exception refundEx)
        {
            log.error("提取任务入队失败退款异常，等待补偿收口: taskId={}, traceId={}",
                    taskId, expectedTraceId, refundEx);
        }
        try { releaseExtractLockForTask(taskId, expectedTraceId); } catch (Exception ignore) { }
        try { taskQueueService.releaseSlots(taskId, expectedTraceId); } catch (Exception ignore) { }
        try { sseManager.sendError(taskId, "任务提交失败"); } catch (Exception ignore) { }
    }

    /** 入队失败只能回收尚未被 worker 领取的周期，禁止覆盖已经 PROCESSING 的任务。 */
    private boolean updateTaskFailedBeforeExecution(Long taskId, String expectedTraceId, String errorMessage)
    {
        if (StrUtil.isBlank(expectedTraceId))
        {
            return false;
        }
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED);
        update.eq(AidExtractTask::getBillingTraceId, expectedTraceId);
        update.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
        update.set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(errorMessage));
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        return extractTaskService.getBaseMapper().update(null, update) > 0;
    }

    private void executeAsync(Long taskId, Long projectId, Long episodeId, Long userId, String modelCode)
    {
        CompletableFuture.runAsync(() -> runExtractLocally(taskId, projectId, episodeId, userId),
                getExtractExecutor());
    }

    /**
     * 本地执行提取核心体（由本地派发执行器 / 续跑 executeAsync 调用）。
     * 原 executeAsync 内联逻辑抽出，便于排队放行时复用同一执行体。
     */
    private void runExtractLocally(Long taskId, Long projectId, Long episodeId, Long userId)
    {
        String dispatchToken = taskQueueService.currentLocalDispatchToken(taskId);
        try
        {
            // 统一队列会先按 token 领取 LOCAL 任务；无队列上下文时保留原入口 CAS。
            if (StrUtil.isBlank(dispatchToken))
            {
                throw new TextTaskExecutionRejectedException();
            }
            boolean claimed = isCurrentProcessingCycle(taskId, dispatchToken);
            if (!claimed)
            {
                log.info("本地提取任务周期已变化，跳过执行: taskId={}", taskId);
                return;
            }
            // 登记执行租约（重启自愈据租约判活）
            markTaskProcessing(taskId);
            // 与 MQ 路径保持一致：每个成功切片独立落库，部分失败时不得回滚已完成资产。
            List<RpsAssetVO> results = doExtract(taskId, projectId, episodeId, userId,
                    dispatchToken);

            // ★ 用户主动取消：doExtract 提前 break 返回部分结果
            if (isTaskCancelled(taskId))
            {
                if (!updateTaskCancelled(taskId, dispatchToken, results != null ? results.size() : 0))
                {
                    log.info("本地提取消收口跳过，周期已变化: taskId={}", taskId);
                    return;
                }
                sseManager.sendCancelled(taskId, "用户取消");
                // 已开始的 LLM 调用按实际用量结算
                try
                {
                    Map<String, Object> usageData = extractBillingService.aggregateTokenUsage(taskId);
                    log.info("本地提取被取消，按实际用量结算: taskId={}, usageData={}", taskId, usageData);
                    extractBillingService.settleBilling(taskId, userId, usageData, dispatchToken);
                }
                catch (Exception billingEx)
                {
                    log.error("本地取消结算失败（不影响业务结果）, taskId={}", taskId, billingEx);
                }
                releaseExtractLockForTask(taskId, dispatchToken);
                clearCancelFlag(taskId, dispatchToken);
                // 释放并发名额
                releaseTaskSlots(taskId, dispatchToken);
                log.info("本地线程池提取被取消: taskId={}, partialCount={}", taskId,
                        results != null ? results.size() : 0);
                return;
            }

            String resultJson = OBJECT_MAPPER.writeValueAsString(results);
            if (!updateTaskSuccess(taskId, dispatchToken, results.size(), resultJson))
            {
                log.info("本地提取成功收口跳过，周期已变化: taskId={}", taskId);
                return;
            }
            // 任务级差额结算：聚合实际 token usage，SKU 定价差额退回（独立 try-catch）
            try
            {
                Map<String, Object> usageData = extractBillingService.aggregateTokenUsage(taskId);
                log.info("提取主任务结算前usageData: taskId={}, usageData={}", taskId, usageData);
                extractBillingService.settleBilling(taskId, userId, usageData, dispatchToken);
            }
            catch (Exception billingEx)
            {
                log.error("本地线程池提取结算失败（不影响业务结果）, taskId={}", taskId, billingEx);
            }
            releaseExtractLockForTask(taskId, dispatchToken);
            // 释放并发名额
            releaseTaskSlots(taskId, dispatchToken);
            sseManager.sendComplete(taskId, results);
            log.info("本地线程池提取完成: taskId={}, count={}", taskId, results.size());
        }
        catch (BatchTaskExecutionRejectedException rejected)
        {
            log.info("本地提取在提交周期内取消: taskId={}", taskId);
            cancelManagedBatchTask(taskId, userId);
        }
        catch (PartialExtractionException partialEx)
        {
            List<RpsAssetVO> partialAssets = partialEx.getPartialAssets() == null
                    ? List.of() : partialEx.getPartialAssets();
            log.warn("本地线程池提取部分完成: taskId={}, partialCount={}", taskId, partialAssets.size());
            boolean terminalOwned = false;
            try
            {
                String partialResultJson = OBJECT_MAPPER.writeValueAsString(partialAssets);
                boolean updated = updateTaskPartialFailed(taskId, dispatchToken, partialAssets.size(),
                        partialResultJson, partialEx.getMessage());
                if (updated)
                {
                    terminalOwned = true;
                    try
                    {
                        Map<String, Object> usageData = extractBillingService.aggregateTokenUsage(taskId);
                        log.info("本地提取部分完成结算: taskId={}, usageData={}", taskId, usageData);
                        extractBillingService.settleBilling(taskId, userId, usageData, dispatchToken);
                    }
                    catch (Exception billingEx)
                    {
                        log.error("本地提取部分完成结算失败: taskId={}", taskId, billingEx);
                    }
                    try
                    {
                        sseManager.sendPartialFailed(taskId, partialAssets,
                                "提取部分完成，可继续生成（已提取" + partialAssets.size() + "个资产）");
                    }
                    catch (Exception sseEx)
                    {
                        log.warn("本地提取部分完成事件发送失败: taskId={}", taskId, sseEx);
                    }
                }
            }
            catch (Exception updateEx)
            {
                log.error("本地提取部分完成状态更新失败: taskId={}", taskId, updateEx);
            }
            finally
            {
                if (terminalOwned)
                {
                    releaseExtractLockForTask(taskId, dispatchToken);
                    releaseTaskSlots(taskId, dispatchToken);
                }
            }
        }
        catch (Exception e)
        {
            log.error("本地线程池提取失败: taskId={}", taskId, e);
            // 使用 ErrorNormalizer 归一化错误，保留真实原因
            com.aid.common.error.TaskErrorResult errorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
            if (!updateTaskFailed(taskId, dispatchToken, errorResult))
            {
                log.info("本地提取失败收口跳过，周期已变化: taskId={}", taskId);
                return;
            }
            try
            {
                boolean billingClosed = extractBillingService.settleOrRefundAfterExecutionFailure(
                        taskId, userId, dispatchToken);
                if (!billingClosed)
                {
                    log.warn("本地提取失败计费尚未收口，保留冻结等待补偿: taskId={}", taskId);
                }
            }
            catch (Exception billingEx)
            {
                log.error("本地提取失败计费收口异常，保留冻结等待补偿: taskId={}",
                        taskId, billingEx);
            }
            releaseExtractLockForTask(taskId, dispatchToken);
            // 释放并发名额
            releaseTaskSlots(taskId, dispatchToken);
            sseManager.sendError(taskId, errorResult);
        }
    }

    /**
     * CAS 更新任务状态：仅当 currentStatus 匹配时才更新。
     * @return true=更新成功，false=CAS 失败（已被其他线程处理）
     */
    private boolean updateTaskStatus(Long taskId, String targetStatus, String errorMessage, String currentStatus)
    {
        return updateTaskStatus(taskId, targetStatus, errorMessage, currentStatus, null);
    }

    private boolean updateTaskStatus(Long taskId, String targetStatus, String errorMessage,
                                     String currentStatus, String dispatchToken)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, currentStatus);
        if (StrUtil.isNotBlank(dispatchToken))
        {
            update.eq(AidExtractTask::getBillingTraceId, dispatchToken);
        }
        update.set(AidExtractTask::getStatus, targetStatus);
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        if (errorMessage != null)
        {
            update.set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(errorMessage));
        }
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("任务状态CAS失败, taskId={}, {}→{}", taskId, currentStatus, targetStatus);
        }
        return rows > 0;
    }

    /**
     * CAS 标记任务成功：仅 PROCESSING → SUCCEEDED。
     */
    private boolean updateTaskSuccess(Long taskId, String dispatchToken,
                                      int totalCount, String resultData)
    {
        if (StrUtil.isBlank(dispatchToken))
        {
            throw new TextTaskExecutionRejectedException();
        }
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.eq(AidExtractTask::getBillingTraceId, dispatchToken);
        update.set(AidExtractTask::getStatus, TASK_STATUS_SUCCEEDED);
        update.set(AidExtractTask::getTotalCount, totalCount);
        update.set(AidExtractTask::getResultData, resultData);
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("任务成功CAS失败, taskId={}", taskId);
        }
        return rows > 0;
    }

    /** 校验统一队列已经领取的 LOCAL 执行周期。 */
    private boolean isCurrentProcessingCycle(Long taskId, String dispatchToken)
    {
        AidExtractTask current = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getStatus,
                                AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId)
                        .last("LIMIT 1"), false);
        return Objects.nonNull(current)
                && Objects.equals(TASK_STATUS_PROCESSING, current.getStatus())
                && Objects.equals(dispatchToken, current.getBillingTraceId());
    }

    /**
     * CAS 标记部分完成：仅 PROCESSING → PARTIAL_FAILED，并保留已完成资产。
     */
    private boolean updateTaskPartialFailed(Long taskId, String dispatchToken, int totalCount,
                                            String resultData, String errorMessage)
    {
        if (StrUtil.isBlank(dispatchToken))
        {
            throw new TextTaskExecutionRejectedException();
        }
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.eq(AidExtractTask::getBillingTraceId, dispatchToken);
        update.set(AidExtractTask::getStatus, TASK_STATUS_PARTIAL_FAILED);
        update.set(AidExtractTask::getTotalCount, totalCount);
        update.set(AidExtractTask::getResultData, resultData);
        update.set(AidExtractTask::getErrorMessage, errorMessage)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(errorMessage));
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        int rows = extractTaskService.getBaseMapper().update(null, update);
        if (rows == 0)
        {
            log.warn("任务部分完成CAS失败, taskId={}", taskId);
        }
        return rows > 0;
    }

    /**
     * CAS 标记任务失败：PENDING/PROCESSING → FAILED。
     *
     * @return true = CAS 实际更新了一行（即任务原本仍为 PENDING/PROCESSING，本调用赢得了状态机推进）；
     *         false = 0 行被更新，任务可能已经被其他线程推进到终态（SUCCEEDED / FAILED / CANCELLED）。
     *         调用方据此判断是否还要执行后续退款 / 释放锁等动作。
     */
    private boolean updateTaskFailed(Long taskId, String expectedTraceId, String errorMessage)
    {
        return updateTaskFailed(taskId, expectedTraceId, ErrorNormalizer.normalizeByMessage(errorMessage));
    }

    /**
     * CAS 标记任务失败（结构化版本兼容入口）：保留此重载避免调用点大改,。
     *
     * @return true 表示 CAS 实际成功
     */
    private boolean updateTaskFailed(Long taskId, String expectedTraceId, TaskErrorResult errorResult)
    {
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING);
        if (StrUtil.isNotBlank(expectedTraceId))
        {
            update.eq(AidExtractTask::getBillingTraceId, expectedTraceId);
        }
        else
        {
            // 预计费/入队前失败同样把“尚未建立周期”作为 CAS 条件，禁止迟到异常污染随后创建的周期。
            update.isNull(AidExtractTask::getBillingTraceId);
        }
        update.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
        update.set(AidExtractTask::getErrorMessage, StrUtil.sub(StrUtil.blankToDefault(errorResult.getRawMessage(), errorResult.getUserMessage()), 0, 500))
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.write(errorResult));
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        Integer rows = extractTaskService.getBaseMapper().update(null, update);
        return rows != null && rows > 0;
    }

    /**
     * 僵尸 extract 锁清理用 CAS：仅当锁值仍等于"刚才读到的 existingToken"时才 DEL，
     * 防止在 GET → DEL 之间锁自然过期被他人重抢导致裸删误杀。
     *
     * @return true = 成功删除（确认是当初读到的同一把脏锁）；
     *         false = 锁值已变（被持有者主动释放、过期被他人重抢、或本身已不存在），不删
     */
    @SuppressWarnings("unchecked")
    private boolean casDeleteExtractLockIfMatch(String key, String existingToken)
    {
        if (StrUtil.isBlank(existingToken))
        {
            return false;
        }
        try
        {
            Object ret = redisCache.redisTemplate.execute(
                    EXTRACT_LOCK_RELEASE_SCRIPT,
                    java.util.Collections.singletonList(key),
                    existingToken);
            return Objects.nonNull(ret) && ((Number) ret).longValue() > 0L;
        }
        catch (Exception e)
        {
            log.warn("提取僵尸锁 CAS 清理失败: key={}, msg={}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public void releaseExtractLockForTask(Long taskId, String expectedTraceId)
    {
        try
        {
            AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
            if (task == null)
            {
                return;
            }
            if (!Objects.equals(expectedTraceId, task.getBillingTraceId()))
            {
                log.warn("提取锁释放跳过，任务周期已变化: taskId={}, expectedTraceId={}, currentTraceId={}",
                        taskId, expectedTraceId, task.getBillingTraceId());
                return;
            }
            batchTaskSlotService.releaseForTask(task);
            String lockToken = resolveExtractLockToken(task.getInputSnapshot());
            if (StrUtil.isBlank(lockToken))
            {
                return;
            }
            releaseExtractLockIfMatch(task.getProjectId(), task.getEpisodeId(), lockToken);
        }
        catch (Exception e)
        {
            log.warn("释放提取锁异常, taskId={}", taskId, e);
        }
    }

    private void releaseExtractLockIfMatch(Long projectId, Long episodeId, String lockToken)
    {
        if (Objects.isNull(projectId) || Objects.isNull(episodeId) || StrUtil.isBlank(lockToken))
        {
            return;
        }
        casDeleteExtractLockIfMatch(EXTRACT_LOCK_PREFIX + projectId + ":" + episodeId, lockToken);
    }

    private String resolveExtractLockToken(String inputSnapshot)
    {
        if (StrUtil.isBlank(inputSnapshot) || !inputSnapshot.trim().startsWith("{"))
        {
            return null;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(inputSnapshot);
            JsonNode tokenNode = root.get("extractLockToken");
            return tokenNode == null || tokenNode.isNull() ? null : StrUtil.trim(tokenNode.asText());
        }
        catch (Exception e)
        {
            log.warn("解析提取锁令牌失败: {}", e.getMessage());
            return null;
        }
    }
    @Override
    public void cancelTask(Long taskId, Long userId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task))
        {
            log.info("取消失败，任务不存在: taskId={}", taskId);
            throw new ServiceException("任务不可用");
        }
        if (!Objects.equals(userId, task.getUserId()))
        {
            log.info("取消失败，任务不属于当前用户: taskId={}, userId={}, taskUserId={}", taskId, userId, task.getUserId());
            throw new ServiceException("无权操作");
        }

        // 仅允许取消支持异步中断的任务类型。
        String taskType = task.getTaskType();
        boolean cancelSupported = TASK_TYPE_ASSET_EXTRACT.equals(taskType)
                || TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)
                || TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)
                || TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType)
                || TASK_TYPE_FORM_MULTI_VIEW.equals(taskType)
                || TASK_TYPE_STORYBOARD_MULTI_VIEW_IMAGE.equals(taskType)
                || TASK_TYPE_STORYBOARD_MULTI_GRID_IMAGE.equals(taskType)
                || TASK_TYPE_FORM_EDIT_CHAT.equals(taskType)
                || TASK_TYPE_IMAGE_UPSCALE.equals(taskType)
                || TASK_TYPE_STORYBOARD_EDIT_IMAGE.equals(taskType)
                || TASK_TYPE_STORYBOARD_IMAGE_UPSCALE.equals(taskType)
                || TASK_TYPE_STORYBOARD_IMAGE_GENERATE.equals(taskType)
                || TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(taskType)
                || TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(taskType)
                || TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(taskType)
                || TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(taskType)
                || TASK_TYPE_STORYBOARD_AUDIO_GENERATE.equals(taskType)
                || TASK_TYPE_STORYBOARD_LIP_SYNC_GENERATE.equals(taskType);
        if (!cancelSupported)
        {
            log.info("取消失败，该接口不支持此任务类型: taskId={}, taskType={}", taskId, taskType);
            throw new ServiceException("任务不匹配");
        }

        String currentStatus = task.getStatus();
        boolean activeStatus = TASK_STATUS_QUEUED.equals(currentStatus)
                || TASK_STATUS_PENDING.equals(currentStatus)
                || TASK_STATUS_PROCESSING.equals(currentStatus)
                || TASK_STATUS_FINALIZING.equals(currentStatus)
                || TASK_STATUS_RECOVERING.equals(currentStatus);
        boolean managedBatchTask = batchTaskSlotService.isManagedBatchTask(task);
        boolean chainRootTask = isCombinedChainRoot(task);
        // 先落取消标记，再等待 submission guard；等待期间新提交者拿锁后会复核标记并停止。
        if ((activeStatus && managedBatchTask) || chainRootTask)
        {
            setCancelFlag(taskId);
        }

        int stoppedChainChildren = cancelActiveOwnedChainChildren(task, userId);

        if (!activeStatus)
        {
            if (chainRootTask || stoppedChainChildren > 0)
            {
                return;
            }
            log.info("取消失败，任务状态不可取消: taskId={}, status={}", taskId, currentStatus);
            throw new ServiceException("任务已停止");
        }

        if (managedBatchTask)
        {
            cancelManagedBatchTask(taskId, userId);
            if (chainRootTask)
            {
                batchParentSubmissionGuard.execute(taskId, () -> {
                    persistChainCancelled(taskId);
                    return null;
                });
            }
            return;
        }

        //    与 drain/admit/requeue 共享同一把派发锁，杜绝"取消成功并退款后调度器仍 dispatch 执行"的竞态；
        //    抢不到锁(正在放行)或 CAS 未命中(已 PROCESSING/终态) → 走 cancel flag 兜底，由执行 worker 在检查点停止。
        if (TASK_STATUS_QUEUED.equals(currentStatus) || TASK_STATUS_PENDING.equals(currentStatus))
        {
            boolean cancelled = taskQueueService.tryCancelQueuedOrPending(taskId);
            if (cancelled)
            {
                // 退回冻结金额（幂等，避免资金挂账）。名额/队列已由 tryCancelQueuedOrPending 在锁内释放。
                try
                {
                    extractBillingService.refundBilling(taskId, userId, task.getBillingTraceId());
                }
                catch (Exception refundEx)
                {
                    log.error("取消退款失败, 需人工介入: taskId={}", taskId, refundEx);
                }
                // 释放提交防重锁（按 taskType，与 cancelBatchTasks / 僵尸回收 / 启动自愈一致）
                releasePendingTaskSpecificLock(task, task.getBillingTraceId());
                sseManager.sendCancelled(taskId, "用户取消");
                log.info("排队/待派发任务取消成功: taskId={}, fromStatus={}", taskId, currentStatus);
            }
            else
            {
                // 抢锁失败(正在放行) 或 已进入 PROCESSING → 写 cancel flag，由处理循环检查点停止后续 chunk
                log.info("QUEUED/PENDING原子取消未命中(可能正在放行/已执行)，写cancel flag兜底: taskId={}", taskId);
                setCancelFlag(taskId);
            }
            return;
        }

        setCancelFlag(taskId);
        log.info("PROCESSING任务已写入cancel flag: taskId={}", taskId);
    }

    /** 在 submission guard 内把逻辑批量父任务可靠收口为取消终态。 */
    private void cancelManagedBatchTask(Long taskId, Long userId)
    {
        boolean changed = batchParentSubmissionGuard.execute(taskId, () ->
        {
            AidExtractTask current = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.isNull(current) || !Objects.equals(userId, current.getUserId()))
            {
                throw new ServiceException("任务不可用");
            }
            if (TASK_STATUS_CANCELLED.equals(current.getStatus()))
            {
                return false;
            }
            if (!isCancelableActiveStatus(current.getStatus()))
            {
                throw new ServiceException("任务已停止");
            }
            String expectedTraceId = current.getBillingTraceId();
            if ((TASK_STATUS_PENDING.equals(current.getStatus())
                    || TASK_STATUS_QUEUED.equals(current.getStatus()))
                    && taskQueueService.tryCancelQueuedOrPending(taskId))
            {
                refundCancelledPendingTask(taskId, userId, expectedTraceId);
                AidExtractTask cancelled = extractTaskService.selectAidExtractTaskById(taskId);
                releasePendingTaskSpecificLock(cancelled, expectedTraceId);
                batchParentResourceCleaner.releaseCancelledResources(cancelled);
                return true;
            }

            current = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.isNull(current) || !Objects.equals(userId, current.getUserId()))
            {
                throw new ServiceException("任务不可用");
            }
            if (TASK_STATUS_CANCELLED.equals(current.getStatus()))
            {
                return false;
            }
            if (!isCancelableActiveStatus(current.getStatus()))
            {
                throw new ServiceException("任务已停止");
            }
            String statusBeforeCancel = current.getStatus();
            boolean startedVideo = TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(current.getTaskType())
                    && isStartedVideoStatus(statusBeforeCancel);
            if (startedVideo)
            {
                int queuedClosed = mediaGenerationService.cancelQueuedVideoTasksForParent(taskId, userId);
                log.info("分镜视频停止前已关闭未提交子任务，已提交任务继续结算: taskId={}, queuedClosed={}",
                        taskId, queuedClosed);
            }
            expectedTraceId = current.getBillingTraceId();
            boolean updated = extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                    .eq(AidExtractTask::getId, taskId)
                    .eq(AidExtractTask::getUserId, userId)
                    .eq(AidExtractTask::getBillingTraceId, expectedTraceId)
                    .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED,
                            TASK_STATUS_PROCESSING, TASK_STATUS_FINALIZING, TASK_STATUS_RECOVERING)
                    .set(AidExtractTask::getStatus, TASK_STATUS_CANCELLED)
                    .set(AidExtractTask::getErrorMessage, "用户取消")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("用户取消"))
                    .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate()));
            if (!updated)
            {
                AidExtractTask latest = extractTaskService.selectAidExtractTaskById(taskId);
                if (Objects.isNull(latest) || !TASK_STATUS_CANCELLED.equals(latest.getStatus()))
                {
                    throw new ServiceException("任务已停止");
                }
                return false;
            }
            if (TASK_STATUS_PENDING.equals(statusBeforeCancel)
                    || TASK_STATUS_QUEUED.equals(statusBeforeCancel))
            {
                refundCancelledPendingTask(taskId, userId, expectedTraceId);
            }
            else if (!startedVideo)
            {
                try
                {
                    boolean billingClosed = extractBillingService.settleOrRefundAfterExecutionFailure(
                            taskId, userId, expectedTraceId);
                    if (!billingClosed)
                    {
                        log.warn("取消任务计费尚未收敛，保留补偿: taskId={}, taskType={}",
                                taskId, current.getTaskType());
                    }
                }
                catch (Exception billingEx)
                {
                    log.error("取消任务计费收口异常: taskId={}, taskType={}",
                            taskId, current.getTaskType(), billingEx);
                }
            }
            else
            {
                // 分镜视频费用由 aid_media_task 子任务持有：已提交供应商的子任务继续真实结算，
                // 父任务取消只阻止后续业务回写，不得伪造退款或打印退款成功语义。
                log.info("分镜视频父任务停止不执行账户动作: taskId={}", taskId);
            }
            AidExtractTask cancelled = extractTaskService.selectAidExtractTaskById(taskId);
            releasePendingTaskSpecificLock(cancelled, expectedTraceId);
            batchParentResourceCleaner.releaseCancelledResources(cancelled);
            releaseTaskSlots(taskId, expectedTraceId);
            return true;
        });
        AidExtractTask cancelledTask = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(cancelledTask) || !TASK_STATUS_CANCELLED.equals(cancelledTask.getStatus()))
        {
            throw new ServiceException("任务已停止");
        }
        if (changed)
        {
            sseManager.sendCancelled(taskId, "用户取消");
        }
    }

    private boolean isCancelableActiveStatus(String status)
    {
        return TASK_STATUS_PENDING.equals(status) || TASK_STATUS_QUEUED.equals(status)
                || TASK_STATUS_PROCESSING.equals(status) || TASK_STATUS_FINALIZING.equals(status)
                || TASK_STATUS_RECOVERING.equals(status);
    }

    private boolean isStartedVideoStatus(String status)
    {
        return TASK_STATUS_PROCESSING.equals(status)
                || TASK_STATUS_FINALIZING.equals(status)
                || TASK_STATUS_RECOVERING.equals(status);
    }

    private void refundCancelledPendingTask(Long taskId, Long userId, String expectedTraceId)
    {
        try
        {
            extractBillingService.refundBilling(taskId, userId, expectedTraceId);
        }
        catch (Exception refundEx)
        {
            log.error("取消退款失败, 需人工介入: taskId={}", taskId, refundEx);
        }
    }

    /** 组合链根任务已终态时，仍停止其同 owner 的活跃出图/出片子任务。 */
    private int cancelActiveOwnedChainChildren(AidExtractTask task, Long userId)
    {
        if (!isCombinedChainRoot(task))
        {
            return 0;
        }
        BatchTaskSlotReservation parentReservation = batchTaskSlotService.reservationFromTask(task);
        if (parentReservation == null)
        {
            return 0;
        }
        return batchParentSubmissionGuard.execute(task.getId(), () ->
                cancelActiveOwnedChainChildrenLocked(task, userId, parentReservation));
    }

    private int cancelActiveOwnedChainChildrenLocked(AidExtractTask task, Long userId,
                                                      BatchTaskSlotReservation parentReservation)
    {
        persistChainCancelled(task.getId());
        Set<Long> candidateIds = new LinkedHashSet<>();
        try
        {
            if (StrUtil.isNotBlank(task.getResultData()))
            {
                JsonNode childIds = OBJECT_MAPPER.readTree(task.getResultData()).path("chainChildTaskIds");
                if (childIds.isArray())
                {
                    for (JsonNode childIdNode : childIds)
                    {
                        if (childIdNode.canConvertToLong())
                        {
                            candidateIds.add(childIdNode.asLong());
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            log.warn("停止组合链读取结果子任务失败: taskId={}, err={}", task.getId(), ex.getMessage());
        }
        try
        {
            List<AidExtractTask> scopedChildren = extractTaskService.list(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId)
                            .eq(AidExtractTask::getUserId, userId)
                            .eq(AidExtractTask::getProjectId, parentReservation.projectId())
                            .eq(AidExtractTask::getEpisodeId, parentReservation.episodeId())
                            .in(AidExtractTask::getTaskType,
                                    TASK_TYPE_STORYBOARD_IMAGE_GENERATE,
                                    TASK_TYPE_STORYBOARD_VIDEO_GENERATE)
                            .in(AidExtractTask::getStatus,
                                    TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING,
                                    TASK_STATUS_FINALIZING, TASK_STATUS_RECOVERING)
                            .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL));
            for (AidExtractTask child : scopedChildren)
            {
                candidateIds.add(child.getId());
            }
        }
        catch (Exception ex)
        {
            log.error("停止组合链扫描同owner子任务失败: taskId={}", task.getId(), ex);
            throw new ServiceException("任务繁忙，请稍后");
        }

        int stopped = 0;
        int failed = 0;
        for (Long childId : candidateIds)
        {
            try
            {
                if (Objects.equals(task.getId(), childId))
                {
                    continue;
                }
                AidExtractTask child = extractTaskService.selectAidExtractTaskById(childId);
                BatchTaskSlotReservation childReservation = batchTaskSlotService.reservationFromTask(child);
                if (child == null || childReservation == null
                        || !Objects.equals(userId, child.getUserId())
                        || !Objects.equals(parentReservation.ownerToken(), childReservation.ownerToken())
                        || !Objects.equals(parentReservation.projectId(), childReservation.projectId())
                        || !Objects.equals(parentReservation.episodeId(), childReservation.episodeId())
                        || !Set.copyOf(parentReservation.logicalTypes())
                                .equals(Set.copyOf(childReservation.logicalTypes()))
                        || !isCancelableActiveStatus(child.getStatus()))
                {
                    continue;
                }
                cancelTask(childId, userId);
                stopped++;
            }
            catch (Exception childEx)
            {
                if (isChainChildClosedOrDetached(childId, parentReservation))
                {
                    stopped++;
                    log.info("停止组合链子任务已收口: rootTaskId={}, childTaskId={}, err={}",
                            task.getId(), childId, childEx.getMessage());
                }
                else
                {
                    failed++;
                    log.warn("停止组合链子任务失败: rootTaskId={}, childTaskId={}, err={}",
                            task.getId(), childId, childEx.getMessage());
                }
            }
        }
        if (failed > 0)
        {
            throw new ServiceException("任务繁忙，请稍后");
        }
        return stopped;
    }

    private boolean isChainChildClosedOrDetached(Long childId, BatchTaskSlotReservation parentReservation)
    {
        try
        {
            AidExtractTask child = extractTaskService.selectAidExtractTaskById(childId);
            if (child == null || !isCancelableActiveStatus(child.getStatus()))
            {
                return true;
            }
            BatchTaskSlotReservation childReservation = batchTaskSlotService.reservationFromTask(child);
            return childReservation != null
                    && !Objects.equals(parentReservation.ownerToken(), childReservation.ownerToken());
        }
        catch (Exception recheckEx)
        {
            log.warn("复核组合链子任务状态失败: childTaskId={}, err={}", childId, recheckEx.getMessage());
            return false;
        }
    }

    private void persistChainCancelled(Long taskId)
    {
        AidExtractTask current = extractTaskService.selectAidExtractTaskById(taskId);
        if (current == null)
        {
            throw new ServiceException("任务不可用");
        }
        try
        {
            JSONObject result = StrUtil.isBlank(current.getResultData())
                    ? new JSONObject() : JSON.parseObject(current.getResultData());
            if (Boolean.TRUE.equals(result.getBoolean("chainCancelled")))
            {
                return;
            }
            result.put("chainCancelled", true);
            boolean updated = extractTaskService.update(Wrappers.<AidExtractTask>lambdaUpdate()
                    .eq(AidExtractTask::getId, taskId)
                    .eq(AidExtractTask::getUserId, current.getUserId())
                    .set(AidExtractTask::getResultData, result.toJSONString())
                    .set(AidExtractTask::getUpdateTime, DateUtils.getNowDate()));
            if (!updated)
            {
                throw new ServiceException("任务状态异常");
            }
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            log.error("持久化组合链停止标记失败: taskId={}", taskId, ex);
            throw new ServiceException("任务状态异常");
        }
    }

    private boolean isCombinedChainRoot(AidExtractTask task)
    {
        if (task == null || StrUtil.isBlank(task.getInputSnapshot())
                || batchTaskSlotService.reservationFromTask(task) == null)
        {
            return false;
        }
        try
        {
            JsonNode chainNext = OBJECT_MAPPER.readTree(task.getInputSnapshot()).path("chainNext");
            return chainNext.isObject() && chainNext.size() > 0;
        }
        catch (Exception ex)
        {
            log.warn("识别组合链根任务失败: taskId={}, err={}", task.getId(), ex.getMessage());
            return false;
        }
    }

    @Override
    public CancelBatchResult cancelBatchTasks(List<Long> taskIds, Long userId)
    {
        if (CollectionUtil.isEmpty(taskIds))
        {
            return CancelBatchResult.builder().cancelCount(0).build();
        }

        // 先批量查出任务记录，用于 CAS 后释放对应业务锁
        // 纳入 QUEUED（排队中）任务，与 PENDING 一并支持批量取消
        List<AidExtractTask> tasks = extractTaskService.getBaseMapper().selectList(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .in(AidExtractTask::getId, taskIds)
                        .eq(AidExtractTask::getUserId, userId)
                        .in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED,
                                TASK_STATUS_PROCESSING, TASK_STATUS_FINALIZING, TASK_STATUS_RECOVERING)
                        .select(AidExtractTask::getId, AidExtractTask::getProjectId,
                                AidExtractTask::getEpisodeId, AidExtractTask::getTaskType,
                                AidExtractTask::getInputSnapshot, AidExtractTask::getStatus,
                                AidExtractTask::getBillingTraceId));
        // 按 id 索引，CAS 成功后快速取到任务记录
        Map<Long, AidExtractTask> taskMap = new HashMap<>();
        for (AidExtractTask t : tasks)
        {
            taskMap.put(t.getId(), t);
        }

        int cancelCount = 0;

        // 逐个 CAS 更新 PENDING/QUEUED → CANCELLED（只处理当前用户 + 可取消状态）
        for (Long taskId : taskIds)
        {
            try
            {
                // 通过队列服务原子取消（持 taskId 派发锁，与 drain/admit/requeue 互斥），杜绝"取消并退款后仍被 dispatch"。
                // taskMap 已是"本用户 + SELECT 时为 PENDING/QUEUED"的任务，据此做归属/可取消性门控（替代原 CAS 里的 user_id 条件）。
                AidExtractTask taskRecord = taskMap.get(taskId);
                if (Objects.isNull(taskRecord))
                {
                    // 非本用户 / SELECT 时已非 PENDING|QUEUED → 跳过
                    continue;
                }
                if (batchTaskSlotService.isManagedBatchTask(taskRecord))
                {
                    cancelTask(taskId, userId);
                    cancelCount++;
                    continue;
                }
                if (TASK_STATUS_PROCESSING.equals(taskRecord.getStatus()))
                {
                    setCancelFlag(taskId);
                    cancelCount++;
                    log.info("批量取消已写入PROCESSING任务cancel flag: taskId={}", taskId);
                    continue;
                }
                boolean cancelled = taskQueueService.tryCancelQueuedOrPending(taskId);
                if (cancelled)
                {
                    cancelCount++;
                    // 推送 SSE 取消事件
                    sseManager.sendCancelled(taskId, "用户取消");
                    // 退回冻结金额（如果有预冻结）
                    try
                    {
                        extractBillingService.refundBilling(
                                taskId, userId, taskRecord.getBillingTraceId());
                    }
                    catch (Exception refundEx)
                    {
                        log.error("批量取消退款失败: taskId={}", taskId, refundEx);
                    }
                    // 释放该任务对应的业务防重锁（按 taskType 分发）。并发名额/出队已由 tryCancelQueuedOrPending 在锁内完成。
                    releasePendingTaskSpecificLock(taskRecord, taskRecord.getBillingTraceId());
                }
                else
                {
                    // 抢锁失败(正在放行) 或 已进入 PROCESSING → 写 cancel flag，由执行 worker 在检查点停止
                    log.info("批量取消原子取消未命中(可能正在放行/已执行)，写cancel flag兜底: taskId={}", taskId);
                    setCancelFlag(taskId);
                    cancelCount++;
                }
            }
            catch (Exception e)
            {
                log.error("批量取消单条任务异常: taskId={}", taskId, e);
            }
        }

        log.info("批量取消完成: userId={}, 请求数={}, 受理停止数={}", userId, taskIds.size(), cancelCount);
        return CancelBatchResult.builder().cancelCount(cancelCount).build();
    }

    @Override
    public boolean isTaskCancelled(Long taskId)
    {
        return taskCancelFlagManager.isCancelled(taskId);
    }

    /**
     * 写入 Redis 取消标记
     */
    @Override
    public void setCancelFlag(Long taskId)
    {
        AidExtractTask task = extractTaskService.getOne(
                Wrappers.<AidExtractTask>lambdaQuery()
                        .select(AidExtractTask::getId, AidExtractTask::getBillingTraceId)
                        .eq(AidExtractTask::getId, taskId).last("LIMIT 1"), false);
        if (task != null && StrUtil.isNotBlank(task.getBillingTraceId()))
        {
            taskCancelFlagManager.setCancelled(taskId, task.getBillingTraceId());
        }
        else
        {
            taskCancelFlagManager.setCancelled(taskId);
        }
    }

    /**
     * 清除 Redis 取消标记
     */
    @Override
    public void clearCancelFlag(Long taskId, String dispatchToken)
    {
        taskCancelFlagManager.clearCancelled(taskId, dispatchToken);
    }

    /**
     * 队列层取消/失败任务后的业务收尾回调（{@link com.aid.rps.queue.QueueTaskFinalizer}）。
     * 当任务在队列层被置为终态（drain 命中 cancelreq 直接 CANCELLED；failTaskAndRefund 派发失败/owner 回收置 FAILED；
     * failEnqueue 延迟入队失败置 FAILED）时，队列已完成名额释放/退款/SSE，但无法释放 taskType 业务防重锁、清 worker
     * cancel flag——本回调补齐这两件业务收尾，让用户取消/失败后可立即重新提交。幂等、不抛出。
     */
    @Override
    public void onQueueTaskTerminated(Long taskId, String dispatchToken)
    {
        if (Objects.isNull(taskId))
        {
            return;
        }
        try
        {
            // 按 taskType 释放业务防重锁（与单条/批量取消同一套），让用户可立即重新提交，不必等锁 TTL
            AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.nonNull(task))
            {
                releasePendingTaskSpecificLock(task, dispatchToken);
            }
            // 顺手清 worker cancel flag（终态已落定，flag 无需再保留）
            clearCancelFlag(taskId, dispatchToken);
            log.info("队列层终态业务收尾完成: taskId={}", taskId);
        }
        catch (Exception e)
        {
            // 收尾失败不影响终态本身（DB 已终态、已退款）；业务锁最差等 TTL 过期
            log.warn("队列层终态业务收尾异常(忽略): taskId={}", taskId, e);
        }
    }

    /**
     * 各任务类型的"僵尸"判定阈值（分钟）。
     * 阈值需 ≥ 该任务类型的最长正常耗时上限，避免误杀进行中的长任务。
     * 调整本表后，如果 {@link } 入参 staleMinutes 比某类型
     * 阈值大，会按入参取 max；这样运维侧可以拉长扫描窗口而不会反向放宽某类型。
     */
    private static final Map<String, Integer> ZOMBIE_STALE_MINUTES_BY_TYPE = Map.ofEntries(
            Map.entry(TASK_TYPE_ASSET_EXTRACT, 20),         // LLM 长链路（角色 + 场景/道具），保守 20 分钟
            Map.entry(TASK_TYPE_FORM_GENERATE, 10),         // 单资产形态生成（文本）
            Map.entry(TASK_TYPE_FORM_GENERATE_BATCH, 30),   // 批量形态生成父任务
            Map.entry(TASK_TYPE_FORM_IMAGE, 15),            // 单 form 图
            Map.entry(TASK_TYPE_FORM_IMAGE_BATCH, 30),      // 批量形态图父任务
            Map.entry(TASK_TYPE_FORM_CARD_IMAGE, 15),       // 角色设定卡
            Map.entry(TASK_TYPE_FORM_CARD_IMAGE_BATCH, 30), // 批量角色设定卡父任务
            Map.entry(TASK_TYPE_FORM_MULTI_VIEW, 15),       // 多机位（形态侧）
            Map.entry(TASK_TYPE_STORYBOARD_MULTI_VIEW_IMAGE, 15), // 分镜单机位机位生图
            Map.entry(TASK_TYPE_STORYBOARD_MULTI_GRID_IMAGE, 15), // 分镜九宫格机位生图
            Map.entry(TASK_TYPE_FORM_EDIT_CHAT, 15),        // 编辑弹窗 / 对话作图
            Map.entry(TASK_TYPE_IMAGE_UPSCALE, 15),         // 高清放大
            Map.entry(TASK_TYPE_STORYBOARD_EDIT_IMAGE, 15), // 分镜编辑图
            Map.entry(TASK_TYPE_STORYBOARD_IMAGE_UPSCALE, 15), // 分镜图高清
            // 注意：storyboard_image_generate / storyboard_video_generate 已改为「非阻塞事件驱动扇入」，
            // 其回收由三重机制兜底：①media 层 closeTimeoutTasks 按 maxLifeSeconds 关停卡死子任务 → 事件扇入收尾；
            // ②租约失活回收（reapLeaselessProcessing，已加「仍有 media 子任务在途则续租跳过」守卫）；
            // ③子任务全终态后扇入 finalize 推进终态。因此不再设固定时长硬上限——否则高并发下 media 排队期间
            // 合法长批量会被硬上限误判失败 → 用户重提新任务（新 bizSeq）→ 重复扣费。
            Map.entry(TASK_TYPE_STORYBOARD_SCRIPT_BATCH, 60), // 分镜脚本批量父任务，最新版剧本切片后可能产生较多批次
            Map.entry(TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH, 10), // 分镜图脚本批量父任务，逐镜调 LLM，10 分钟兜底
            Map.entry(TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH, 10) // 视频提示词批量父任务，逐镜调 LLM，10 分钟兜底
    );

    /**
     * 僵尸任务兜底：扫描提取任务的"僵尸态"并自愈。
     *
     * @param staleMinutes 调用方传入的最低判定时长（分钟），实际阈值取 max(staleMinutes, 类型阈值)
     * @param batchSize    单次扫描批量上限
     */
    @Override
    public int reclaimZombieExtractTasks(int staleMinutes, int batchSize)
    {
        if (batchSize <= 0)
        {
            return 0;
        }
        int callerStale = Math.max(staleMinutes, 0);
        long nowMillis = System.currentTimeMillis();

        StringBuilder condition = new StringBuilder("(");
        List<Object> sqlParams = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : ZOMBIE_STALE_MINUTES_BY_TYPE.entrySet())
        {
            int effectiveStale = Math.max(callerStale, entry.getValue());
            Date threshold = new Date(nowMillis - effectiveStale * 60_000L);
            if (!first)
            {
                condition.append(" OR ");
            }
            condition.append("(task_type = {").append(sqlParams.size())
                    .append("} AND COALESCE(update_time, create_time) < {")
                    .append(sqlParams.size() + 1).append("})");
            sqlParams.add(entry.getKey());
            sqlParams.add(threshold);
            first = false;
        }
        condition.append(")");

        List<AidExtractTask> candidates = extractTaskService.list(
                Wrappers.<AidExtractTask>lambdaQuery()
                        // 粗筛仅取ID，防止扫描 input_snapshot 等无关字段。
                        .select(AidExtractTask::getId)
                        .in(AidExtractTask::getStatus,
                                TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING,
                                TASK_STATUS_FINALIZING, TASK_STATUS_RECOVERING)
                        .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL)
                        .apply(condition.toString(), sqlParams.toArray())
                        .last("ORDER BY COALESCE(update_time, create_time) ASC LIMIT " + batchSize));
        if (CollectionUtil.isEmpty(candidates))
        {
            return 0;
        }

        int reclaimed = 0;
        Set<String> activeStatuses = Set.of(
                TASK_STATUS_PENDING, TASK_STATUS_QUEUED, TASK_STATUS_PROCESSING,
                TASK_STATUS_FINALIZING, TASK_STATUS_RECOVERING);
        for (AidExtractTask candidate : candidates)
        {
            try
            {
                Boolean handled = taskQueueService.executeWithTaskDispatchLock(candidate.getId(),
                        () -> recoverInactiveCycle(candidate.getId(), activeStatuses, "僵尸回收"));
                if (Boolean.TRUE.equals(handled))
                {
                    reclaimed++;
                }
            }
            catch (Exception e)
            {
                log.error("僵尸任务回收异常, taskId={}", candidate.getId(), e);
            }
        }
        return reclaimed;
    }

    /**
     * PENDING 提取任务取消成功后，根据 task 记录释放提取防重锁。
     * 仅在 cancelTask 的 PENDING 取消成功分支调用。
     */
    private void releasePendingExtractTaskLock(AidExtractTask task, String expectedTraceId)
    {
        try
        {
            AidExtractTask current = task == null
                    ? null : extractTaskService.selectAidExtractTaskById(task.getId());
            if (current == null || !Objects.equals(expectedTraceId, current.getBillingTraceId()))
            {
                log.warn("PENDING任务锁释放跳过，周期已变化: taskId={}, expectedTraceId={}, currentTraceId={}",
                        task == null ? null : task.getId(), expectedTraceId,
                        current == null ? null : current.getBillingTraceId());
                return;
            }
            task = current;
            String taskType = task.getTaskType();
            if (TASK_TYPE_IMAGE_UPSCALE.equals(taskType))
            {
                // 从 inputSnapshot 解析 imageId，释放 image_upscale 防重锁
                Long imageId = resolveImageIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(imageId))
                {
                    String lockKey = RpsFormImageBusinessServiceImpl.buildUpscaleLockKey(imageId);
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING高清任务取消后释放upscale锁: taskId={}, imageId={}", task.getId(), imageId);
                }
            }
            else if (TASK_TYPE_ASSET_EXTRACT.equals(taskType))
            {
                releaseExtractLockForTask(task.getId(), expectedTraceId);
                log.info("PENDING提取任务取消后释放extract锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
            else if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType) || TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)
                    || TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType))
            {
                // 批量父任务取消：释放所有子项锁
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING批量父任务取消后释放所有子项锁: taskId={}, taskType={}", task.getId(), taskType);
            }
            else if (TASK_TYPE_FORM_IMAGE.equals(taskType))
            {
                // 单条形态图任务 PENDING 取消：释放 form_image 防重锁，避免用户等 TTL 过期
                Long formId = resolveFormIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(formId))
                {
                    String lockKey = buildFormImageLockKey(formId);
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING形态图任务取消后释放form_image锁: taskId={}, formId={}", task.getId(), formId);
                }
            }
            else if (TASK_TYPE_FORM_MULTI_VIEW.equals(taskType))
            {
                Long formId = resolveFormIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(formId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_FORM_MULTI_VIEW + ":" + formId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING多机位任务取消后释放form_multi_view锁: taskId={}, formId={}", task.getId(), formId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_MULTI_VIEW_IMAGE.equals(taskType)
                    || TASK_TYPE_STORYBOARD_MULTI_GRID_IMAGE.equals(taskType))
            {
                // 分镜机位生图（单机位 / 九宫格）：锁 key = asset:form:lock:{taskType}:{storyboardId}
                Long storyboardId = resolveStoryboardIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(storyboardId))
                {
                    String lockKey = FORM_LOCK_PREFIX + taskType + ":" + storyboardId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING分镜机位生图任务取消后释放{}锁: taskId={}, storyboardId={}",
                            taskType, task.getId(), storyboardId);
                }
            }
            else if (TASK_TYPE_FORM_EDIT_CHAT.equals(taskType))
            {
                Long formId = resolveFormIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(formId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_FORM_EDIT_CHAT + ":" + formId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING编辑弹窗生图任务取消后释放form_edit_chat锁: taskId={}, formId={}", task.getId(), formId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_EDIT_IMAGE.equals(taskType))
            {
                Long storyboardId = resolveStoryboardIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(storyboardId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_EDIT_IMAGE + ":" + storyboardId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING分镜编辑图任务取消后释放storyboard_edit_image锁: taskId={}, storyboardId={}",
                            task.getId(), storyboardId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_UPSCALE.equals(taskType))
            {
                Long recordId = resolveGenRecordIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(recordId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_UPSCALE + ":" + recordId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING分镜图高清任务取消后释放storyboard_image_upscale锁: taskId={}, recordId={}",
                            task.getId(), recordId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_GENERATE.equals(taskType))
            {
                // 批量重构：父任务 input_snapshot.shots[{storyboardId, lockToken}]，按 token CAS 释放每个镜头锁
                Map<Long, String> shotTokens = resolveShotLockTokensFromSnapshot(task.getInputSnapshot());
                for (Map.Entry<Long, String> en : shotTokens.entrySet())
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_GENERATE + ":" + en.getKey();
                    casReleaseVideoShotLock(lockKey, en.getValue());
                    log.info("PENDING分镜图生成任务取消后CAS释放storyboard_image_generate锁: taskId={}, storyboardId={}",
                            task.getId(), en.getKey());
                }
            }
            else if (TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(taskType))
            {
                Map<Long, String> shotTokens = resolveShotLockTokensFromSnapshot(task.getInputSnapshot());
                for (Map.Entry<Long, String> en : shotTokens.entrySet())
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + en.getKey();
                    casReleaseVideoShotLock(lockKey, en.getValue());
                    log.info("PENDING分镜图生视频任务取消后CAS释放storyboard_video_generate锁: taskId={}, storyboardId={}",
                            task.getId(), en.getKey());
                }
            }
            else if (TASK_TYPE_FORM_CARD_IMAGE.equals(taskType))
            {
                Long imageId = resolveImageIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(imageId))
                {
                    String lockKey = buildFormCardImageLockKey(imageId);
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING设定卡任务取消后释放form_card_image锁: taskId={}, imageId={}", task.getId(), imageId);
                }
            }
            else if (TASK_TYPE_FORM_GENERATE.equals(taskType))
            {
                Long assetId = resolveAssetIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(assetId))
                {
                    String lockKey = buildFormGenerateLockKey(assetId);
                    releaseTaskOwnedItemLock(task, lockKey);
                    log.info("PENDING形态生成任务取消后CAS释放form_generate锁: taskId={}, assetId={}", task.getId(), assetId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(taskType))
            {
                // 分镜脚本批量任务使用项目级锁（key=storyboard:script:lock:{projectId}:{episodeId}），
                // 复用 releaseBatchFormLocks 的统一释放分支
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING分镜脚本批量任务取消后释放项目级锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(taskType))
            {
                // 分镜图脚本批量任务使用项目级锁（key=storyboard:image_prompt:lock:{projectId}:{episodeId}）
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING分镜图脚本批量任务取消后释放项目级锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
            else if (TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(taskType))
            {
                // 视频提示词批量任务使用项目级锁（key=storyboard:video_prompt:lock:{projectId}:{episodeId}）
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING视频提示词批量任务取消后释放项目级锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
            // 新逻辑槽对无元数据旧任务为 no-op；owner CAS 释放，可与各分支专用释放安全重复调用。
            batchTaskSlotService.releaseForTask(task);
        }
        catch (Exception e)
        {
            log.warn("释放PENDING任务锁异常: taskId={}", task.getId(), e);
        }
    }

    /**
     * 批量取消 PENDING 任务后，按 taskType 释放对应业务锁。
     */
    private void releasePendingTaskSpecificLock(AidExtractTask task, String expectedTraceId)
    {
        Long taskId = task == null ? null : task.getId();
        try
        {
            AidExtractTask current = taskId == null
                    ? null : extractTaskService.selectAidExtractTaskById(taskId);
            if (current == null || !Objects.equals(expectedTraceId, current.getBillingTraceId()))
            {
                log.warn("任务业务锁释放跳过，周期已变化: taskId={}, expectedTraceId={}, currentTraceId={}",
                        taskId, expectedTraceId,
                        current == null ? null : current.getBillingTraceId());
                return;
            }
            task = current;
            String taskType = task.getTaskType();
            if (TASK_TYPE_IMAGE_UPSCALE.equals(taskType))
            {
                // 从 inputSnapshot 解析 imageId，复用 RpsFormImageBusinessServiceImpl.buildUpscaleLockKey
                Long imageId = resolveImageIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(imageId))
                {
                    String lockKey = RpsFormImageBusinessServiceImpl.buildUpscaleLockKey(imageId);
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING高清任务取消后释放upscale锁: taskId={}, imageId={}", task.getId(), imageId);
                }
            }
            else if (TASK_TYPE_ASSET_EXTRACT.equals(taskType))
            {
                releaseExtractLockForTask(task.getId(), expectedTraceId);
                log.info("PENDING提取任务批量取消后释放extract锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
            else if (TASK_TYPE_FORM_IMAGE.equals(taskType))
            {
                // 从 inputSnapshot 解析 formId，释放 form_image 防重锁
                Long formId = resolveFormIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(formId))
                {
                    String lockKey = buildFormImageLockKey(formId);
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING形态图任务取消后释放form_image锁: taskId={}, formId={}", task.getId(), formId);
                }
            }
            else if (TASK_TYPE_FORM_MULTI_VIEW.equals(taskType))
            {
                // 从 inputSnapshot 解析 formId，释放多机位任务防重锁（与 form_image 共用 key 规则，按 formId 粒度）
                Long formId = resolveFormIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(formId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_FORM_MULTI_VIEW + ":" + formId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING多机位任务取消后释放form_multi_view锁: taskId={}, formId={}", task.getId(), formId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_MULTI_VIEW_IMAGE.equals(taskType)
                    || TASK_TYPE_STORYBOARD_MULTI_GRID_IMAGE.equals(taskType))
            {
                // 分镜机位生图（单机位 / 九宫格）：锁 key = asset:form:lock:{taskType}:{storyboardId}
                Long storyboardId = resolveStoryboardIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(storyboardId))
                {
                    String lockKey = FORM_LOCK_PREFIX + taskType + ":" + storyboardId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING分镜机位生图任务批量取消后释放{}锁: taskId={}, storyboardId={}",
                            taskType, task.getId(), storyboardId);
                }
            }
            else if (TASK_TYPE_FORM_EDIT_CHAT.equals(taskType))
            {
                // 从 inputSnapshot 解析 formId，释放编辑弹窗生图任务防重锁
                Long formId = resolveFormIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(formId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_FORM_EDIT_CHAT + ":" + formId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING编辑弹窗生图任务取消后释放form_edit_chat锁: taskId={}, formId={}", task.getId(), formId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_EDIT_IMAGE.equals(taskType))
            {
                Long storyboardId = resolveStoryboardIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(storyboardId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_EDIT_IMAGE + ":" + storyboardId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING分镜编辑图任务批量取消后释放storyboard_edit_image锁: taskId={}, storyboardId={}",
                            task.getId(), storyboardId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_UPSCALE.equals(taskType))
            {
                Long recordId = resolveGenRecordIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(recordId))
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_UPSCALE + ":" + recordId;
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING分镜图高清任务批量取消后释放storyboard_image_upscale锁: taskId={}, recordId={}",
                            task.getId(), recordId);
                }
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_GENERATE.equals(taskType))
            {
                // 批量重构：父任务 input_snapshot.shots[{storyboardId, lockToken}]，按 token CAS 释放每个镜头锁
                Map<Long, String> shotTokens = resolveShotLockTokensFromSnapshot(task.getInputSnapshot());
                for (Map.Entry<Long, String> en : shotTokens.entrySet())
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_IMAGE_GENERATE + ":" + en.getKey();
                    casReleaseVideoShotLock(lockKey, en.getValue());
                    log.info("PENDING分镜图生成任务批量取消后CAS释放storyboard_image_generate锁: taskId={}, storyboardId={}",
                            task.getId(), en.getKey());
                }
            }
            else if (TASK_TYPE_STORYBOARD_VIDEO_GENERATE.equals(taskType))
            {
                Map<Long, String> shotTokens = resolveShotLockTokensFromSnapshot(task.getInputSnapshot());
                for (Map.Entry<Long, String> en : shotTokens.entrySet())
                {
                    String lockKey = FORM_LOCK_PREFIX + TASK_TYPE_STORYBOARD_VIDEO_GENERATE + ":" + en.getKey();
                    casReleaseVideoShotLock(lockKey, en.getValue());
                    log.info("PENDING分镜图生视频任务批量取消后CAS释放storyboard_video_generate锁: taskId={}, storyboardId={}",
                            task.getId(), en.getKey());
                }
            }
            else if (TASK_TYPE_FORM_CARD_IMAGE.equals(taskType))
            {
                // 从 inputSnapshot 解析 imageId，释放 form_card_image 防重锁
                Long imageId = resolveImageIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(imageId))
                {
                    String lockKey = buildFormCardImageLockKey(imageId);
                    redisCache.deleteObject(lockKey);
                    log.info("PENDING设定卡任务取消后释放form_card_image锁: taskId={}, imageId={}", task.getId(), imageId);
                }
            }
            else if (TASK_TYPE_FORM_GENERATE.equals(taskType))
            {
                // 从 inputSnapshot 解析 assetId，释放 form_generate 防重锁
                Long assetId = resolveAssetIdFromSnapshot(task.getInputSnapshot());
                if (Objects.nonNull(assetId))
                {
                    String lockKey = buildFormGenerateLockKey(assetId);
                    releaseTaskOwnedItemLock(task, lockKey);
                    log.info("PENDING形态生成任务取消后CAS释放form_generate锁: taskId={}, assetId={}", task.getId(), assetId);
                }
            }
            else if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType) || TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)
                    || TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType))
            {
                // 批量父任务：释放所有子项锁
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING批量父任务批量取消后释放子项锁: taskId={}, taskType={}", task.getId(), taskType);
            }
            else if (TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(taskType))
            {
                // 分镜脚本批量任务使用项目级锁（key=storyboard:script:lock:{projectId}:{episodeId}），
                // 委托 releaseBatchFormLocks 走统一释放分支，避免与 cancel 流程实现漂移
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING/PROCESSING分镜脚本批量任务取消/僵尸自愈后释放项目级锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(taskType))
            {
                // 分镜图脚本批量任务使用项目级锁（key=storyboard:image_prompt:lock:{projectId}:{episodeId}）
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING/PROCESSING分镜图脚本批量任务取消/僵尸自愈后释放项目级锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
            else if (TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(taskType))
            {
                // 视频提示词批量任务使用项目级锁（key=storyboard:video_prompt:lock:{projectId}:{episodeId}）
                releaseBatchFormLocks(task.getId(), taskType, expectedTraceId);
                log.info("PENDING/PROCESSING视频提示词批量任务取消/僵尸自愈后释放项目级锁: taskId={}, projectId={}, episodeId={}",
                        task.getId(), task.getProjectId(), task.getEpisodeId());
            }
        }
        catch (Exception e)
        {
            log.warn("释放PENDING任务锁(按taskType)异常: taskId={}", taskId, e);
        }
        finally
        {
            try
            {
                AidExtractTask current = taskId == null
                        ? null : extractTaskService.selectAidExtractTaskById(taskId);
                if (current != null && Objects.equals(expectedTraceId, current.getBillingTraceId()))
                {
                    batchTaskSlotService.releaseForTask(current);
                }
                else if (taskId != null)
                {
                    log.warn("任务逻辑槽释放跳过，周期已变化: taskId={}, expectedTraceId={}, currentTraceId={}",
                            taskId, expectedTraceId,
                            current == null ? null : current.getBillingTraceId());
                }
            }
            catch (Exception releaseEx)
            {
                log.warn("任务逻辑槽释放异常: taskId={}", taskId, releaseEx);
            }
        }
    }

    /**
     * 从 inputSnapshot JSON 解析 imageId（高清任务用）。
     * 解析失败返回 null，不中断主流程。
     */
    private Long resolveImageIdFromSnapshot(String inputSnapshot)
    {
        try
        {
            if (StrUtil.isBlank(inputSnapshot))
            {
                return null;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(inputSnapshot, Map.class);
            Object idVal = snapshot.get("imageId");
            return idVal != null ? Long.valueOf(String.valueOf(idVal)) : null;
        }
        catch (Exception e)
        {
            log.warn("解析inputSnapshot中imageId失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 inputSnapshot JSON 解析 formId（form_image 任务用）。
     * 解析失败返回 null，不中断主流程。
     */
    private Long resolveFormIdFromSnapshot(String inputSnapshot)
    {
        try
        {
            if (StrUtil.isBlank(inputSnapshot))
            {
                return null;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(inputSnapshot, Map.class);
            Object idVal = snapshot.get("formId");
            return idVal != null ? Long.valueOf(String.valueOf(idVal)) : null;
        }
        catch (Exception e)
        {
            log.warn("解析inputSnapshot中formId失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 inputSnapshot JSON 解析 assetId（form_generate 任务用）。
     * 解析失败返回 null，不中断主流程。
     */
    private Long resolveAssetIdFromSnapshot(String inputSnapshot)
    {
        try
        {
            if (StrUtil.isBlank(inputSnapshot))
            {
                return null;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(inputSnapshot, Map.class);
            Object idVal = snapshot.get("assetId");
            return idVal != null ? Long.valueOf(String.valueOf(idVal)) : null;
        }
        catch (Exception e)
        {
            log.warn("解析inputSnapshot中assetId失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 inputSnapshot JSON 解析 storyboardId（分镜编辑图任务用）。
     * 解析失败返回 null，不中断主流程。
     */
    private Long resolveStoryboardIdFromSnapshot(String inputSnapshot)
    {
        try
        {
            if (StrUtil.isBlank(inputSnapshot))
            {
                return null;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(inputSnapshot, Map.class);
            Object idVal = snapshot.get("storyboardId");
            return idVal != null ? Long.valueOf(String.valueOf(idVal)) : null;
        }
        catch (Exception e)
        {
            log.warn("解析inputSnapshot中storyboardId失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 inputSnapshot JSON 解析每个镜头的锁 token（分镜批量出片父任务用）。
     * 读取 {@code shots:[{storyboardId, lockToken}]}，返回 storyboardId → lockToken 映射。
     * 取消 / 队列收尾据此对每个镜头锁做 compare-and-delete（只删本任务持有的 token，不裸删 key，
     * 避免删掉新请求刚抢到的同名锁）。解析失败 / 无 token 返回空 Map，不中断主流程。
     */
    @SuppressWarnings("unchecked")
    private Map<Long, String> resolveShotLockTokensFromSnapshot(String inputSnapshot)
    {
        Map<Long, String> tokens = new LinkedHashMap<>();
        try
        {
            if (StrUtil.isBlank(inputSnapshot))
            {
                return tokens;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(inputSnapshot, Map.class);
            Object shots = snapshot.get("shots");
            if (shots instanceof List<?> list)
            {
                for (Object o : list)
                {
                    if (o instanceof Map<?, ?> mp)
                    {
                        Object sid = mp.get("storyboardId");
                        Object tk = mp.get("lockToken");
                        if (sid != null && tk != null)
                        {
                            try { tokens.put(Long.valueOf(String.valueOf(sid)), String.valueOf(tk)); }
                            catch (NumberFormatException ignore) { /* skip */ }
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.warn("解析inputSnapshot中shots锁token失败: {}", e.getMessage());
        }
        return tokens;
    }

    /** 视频镜头锁 compare-and-delete：仅当锁当前值 == 传入 token 才删（复用 EXTRACT_LOCK_RELEASE_SCRIPT 同序列化器）。 */
    private void casReleaseVideoShotLock(String lockKey, String token)
    {
        if (StrUtil.isBlank(lockKey) || StrUtil.isBlank(token))
        {
            return;
        }
        try
        {
            redisCache.redisTemplate.execute(EXTRACT_LOCK_RELEASE_SCRIPT,
                    java.util.Collections.singletonList(lockKey), token);
        }
        catch (Exception e)
        {
            log.warn("CAS释放视频镜头锁失败(将随TTL过期): lockKey={}, err={}", lockKey, e.getMessage());
        }
    }

    /**
     * 从 inputSnapshot JSON 解析 genRecordId（分镜图高清任务用）。
     * 解析失败返回 null，不中断主流程。
     */
    private Long resolveGenRecordIdFromSnapshot(String inputSnapshot)
    {
        try
        {
            if (StrUtil.isBlank(inputSnapshot))
            {
                return null;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(inputSnapshot, Map.class);
            Object idVal = snapshot.get("genRecordId");
            return idVal != null ? Long.valueOf(String.valueOf(idVal)) : null;
        }
        catch (Exception e)
        {
            log.warn("解析inputSnapshot中genRecordId失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建 form_image 防重锁 key。
     * 格式：asset:form:lock:form_image:{formId}
     */
    private String buildFormImageLockKey(Long formId)
    {
        return FORM_LOCK_PREFIX + "form_image:" + formId;
    }

    /**
     * 构建 form_card_image 防重锁 key。
     * 格式：asset:form:lock:form_card_image:{imageId}
     */
    private String buildFormCardImageLockKey(Long imageId)
    {
        return FORM_LOCK_PREFIX + "form_card_image:" + imageId;
    }

    /**
     * CAS 标记任务为 CANCELLED：PROCESSING → CANCELLED。
     * 仅由循环检查点发现取消后调用。
     */
    private boolean updateTaskCancelled(Long taskId, String dispatchToken, int completedCount)
    {
        if (StrUtil.isBlank(dispatchToken))
        {
            throw new TextTaskExecutionRejectedException();
        }
        LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
        update.eq(AidExtractTask::getId, taskId);
        update.eq(AidExtractTask::getStatus, TASK_STATUS_PROCESSING);
        update.eq(AidExtractTask::getBillingTraceId, dispatchToken);
        update.set(AidExtractTask::getStatus, TASK_STATUS_CANCELLED);
        update.set(AidExtractTask::getErrorMessage, "用户取消")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("用户取消"));
        update.set(AidExtractTask::getTotalCount, completedCount);
        update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
        return extractTaskService.getBaseMapper().update(null, update) > 0;
    }

    /**
     * 创建提取任务记录。
     * inputSnapshot 中除 extractTypes / agentCodes 外，还含 modelCodes（Map&lt;extractType, modelCode&gt;），
     * 供 Consumer 端按 type 选用对应模型；task.modelCode 字段保留主模型（character 优先），用于路由展示和旧任务兜底。
     */
    private AidExtractTask createTaskRecord(Long projectId, Long episodeId, Long userId,
                                            List<String> extractTypes,
                                            Map<String, String> agentCodes,
                                            Map<String, String> modelCodesByType,
                                            String primaryModelCode,
                                            String extractScope,
                                            BatchTaskSlotReservation slotReservation,
                                            boolean overwrite)
    {
        AidExtractTask task = new AidExtractTask();
        task.setProjectId(projectId);
        task.setEpisodeId(episodeId);
        task.setUserId(userId);
        task.setTaskType(TASK_TYPE_ASSET_EXTRACT);
        // 将 extractTypes + agentCodes + modelCodes + extractScope 一起写入 inputSnapshot（JSON），便于审计 / Consumer 取用
        task.setInputSnapshot(buildExtractInputSnapshot(
                extractTypes, agentCodes, modelCodesByType, extractScope, slotReservation,
                overwrite));
        task.setModelCode(primaryModelCode);
        task.setStatus(TASK_STATUS_PENDING);
        task.setTotalCount(0);
        task.setDelFlag(DEL_FLAG_NORMAL);
        // 创建时同时初始化 update_time：僵尸扫描器按 update_time < threshold 判定，
        // 避免 update_time 为 NULL 时漏扫"刚 INSERT 后 MQ 投递失败"的任务。
        java.util.Date now = DateUtils.getNowDate();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setCreateBy(String.valueOf(userId));
        extractTaskService.save(task);
        return task;
    }

    /**
     * 组装 extract 任务的 inputSnapshot JSON：包含 extractTypes / agentCodes / modelCodes。
     * 序列化失败兜底为旧 CSV 格式，保证业务可用。
     */
    private String buildExtractInputSnapshot(List<String> extractTypes,
                                             Map<String, String> agentCodes,
                                             Map<String, String> modelCodes,
                                             String extractScope,
                                             BatchTaskSlotReservation slotReservation,
                                             boolean overwrite)
    {
        if (CollectionUtil.isEmpty(extractTypes))
        {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("extractTypes", extractTypes);
        if (StrUtil.isNotBlank(extractScope))
        {
            snapshot.put("extractScope", extractScope);
        }
        batchTaskSlotService.attachSnapshotMetadata(snapshot, slotReservation);
        snapshot.put("overwrite", overwrite);
        snapshot.put("overwriteApplied", false);
        if (agentCodes != null && !agentCodes.isEmpty())
        {
            snapshot.put("agentCodes", agentCodes);
        }
        if (modelCodes != null && !modelCodes.isEmpty())
        {
            snapshot.put("modelCodes", modelCodes);
        }
        try
        {
            return OBJECT_MAPPER.writeValueAsString(snapshot);
        }
        catch (Exception e)
        {
            log.error("inputSnapshot序列化失败: extractTypes={}, error={}",
                    extractTypes, e.getMessage(), e);
            throw new ServiceException("任务创建失败");
        }
    }

    /**
     * 在外层事务提交之后调用 sendMqMessage。
     */
    private void sendExtractMqAfterCommit(Long taskId, Long projectId, Long episodeId,
                                          Long userId, String modelCode)
    {
        try
        {
            sendMqMessage(taskId, projectId, episodeId, userId, modelCode);
        }
        catch (Exception mqEx)
        {
            log.error("MQ发送失败(afterCommit), 显式退款 + 标记FAILED, taskId={}", taskId, mqEx);
            AidExtractTask current = extractTaskService.selectAidExtractTaskById(taskId);
            String expectedTraceId = current == null ? null : current.getBillingTraceId();
            try
            {
                if (StrUtil.isNotBlank(expectedTraceId))
                {
                    updateTaskFailed(taskId, expectedTraceId, "任务提交失败");
                }
                else
                {
                    updateTaskFailed(taskId, null, "任务提交失败");
                }
            }
            catch (Exception updEx)
            {
                log.error("MQ失败后标记任务FAILED异常, taskId={}", taskId, updEx);
            }
            try
            {
                extractBillingService.refundBilling(taskId, userId, expectedTraceId);
            }
            catch (Exception refundEx)
            {
                log.error("MQ失败后退款也失败, 需人工介入, taskId={}", taskId, refundEx);
            }
            try
            {
                releaseExtractLockForTask(taskId, expectedTraceId);
            }
            catch (Exception lockEx)
            {
                log.warn("MQ失败后释放extract锁异常, taskId={}", taskId, lockEx);
            }
            try
            {
                sseManager.sendError(taskId, "任务提交失败");
            }
            catch (Exception sseEx)
            {
                log.warn("MQ失败后SSE通知异常(不影响业务), taskId={}", taskId, sseEx);
            }
        }
    }

    private void sendMqMessage(Long taskId, Long projectId, Long episodeId, Long userId,
                               String modelCode)
    {
        ExtractTaskMessage message = ExtractTaskMessage.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .build();
        try
        {
            String body = OBJECT_MAPPER.writeValueAsString(message);
            // 同步发送，失败直接抛 MqException
            MqResult result = mqTemplateFactory.send(MQ_TOPIC, MQ_TAG, String.valueOf(taskId), body);
            log.info("提取任务MQ消息已发送: taskId={}, projectId={}, msgId={}", taskId, projectId, result.getMessageId());
        }
        catch (Exception e)
        {
            log.error("发送提取任务MQ消息失败: taskId={}, error={}", taskId, e.getMessage(), e);
            throw new ServiceException("任务提交失败，请重试");
        }
    }

    /**
     * 发送批量父任务 MQ 消息（form_generate_batch / form_image_batch），带 taskType 用于 Consumer 分发。
     */
    private void sendBatchMqMessage(Long taskId, Long projectId, Long episodeId,
                                    Long userId, String modelCode, String taskType)
    {
        ExtractTaskMessage message = ExtractTaskMessage.builder()
                .taskId(taskId)
                .projectId(projectId)
                .episodeId(episodeId)
                .userId(userId)
                .modelCode(modelCode)
                .taskType(taskType)
                .build();
        try
        {
            String body = OBJECT_MAPPER.writeValueAsString(message);
            MqResult result = mqTemplateFactory.send(MQ_TOPIC, MQ_TAG, String.valueOf(taskId), body);
            log.info("批量父任务MQ消息已发送: taskId={}, taskType={}, msgId={}", taskId, taskType, result.getMessageId());
        }
        catch (Exception e)
        {
            log.error("发送批量父任务MQ消息失败: taskId={}, taskType={}", taskId, taskType, e);
            throw new ServiceException("任务提交失败，请重试");
        }
    }
    private JsonNode safeGetFuture(CompletableFuture<JsonNode> future, String label,
                                   AtomicBoolean balanceInsufficient,
                                   AtomicReference<TextTaskExecutionRejectedException> executionRejected)
    {
        try
        {
            return future.join();
        }
        catch (Exception e)
        {
            log.error("{}异常: {}", label, e.getMessage(), e);
            TextTaskExecutionRejectedException rejected =
                    TextTaskExecutionRejectedException.find(e);
            if (rejected != null)
            {
                executionRejected.compareAndSet(null, rejected);
            }
            if (BillingBalanceErrors.isPreholdNotEnough(e))
            {
                balanceInsufficient.set(true);
            }
            return null;
        }
    }

    /**
     * 构建任务存档摘要（aid_media_task.prompt 列内容）。
     * 资产提取等场景的智能体模板正文体积常超过 50KB，叠加切片原文后会超 MySQL TEXT
     * 列 64KB 上限触发数据截断异常。本方法只把"动态入参"拼成几 KB 的紧凑摘要，
     * 仅用于审计与排查，不参与 LLM 调用。
     */
    private String buildScenePropTaskDigest(String scriptContent, ExistingAssetLib lib)
    {
        return new StringBuilder()
                .append("[input]\n").append(StrUtil.blankToDefault(scriptContent, ""))
                .append("\n[characters_lib_name]\n").append(StrUtil.blankToDefault(lib.getCharacterNamesJoined(), "无"))
                .append("\n[locations_lib_name]\n").append(StrUtil.blankToDefault(lib.getSceneNamesJoined(), "无"))
                .append("\n[props_lib_name]\n").append(StrUtil.blankToDefault(lib.getPropNamesJoined(), "无"))
                .toString();
    }

    /**
     * 构建任务存档摘要（角色提取专用）。
     */
    private String buildCharacterTaskDigest(String scriptContent, String charactersLibInfo, String charactersLibName)
    {
        return new StringBuilder()
                .append("[input]\n").append(StrUtil.blankToDefault(scriptContent, ""))
                .append("\n[characters_lib_info]\n").append(StrUtil.blankToDefault(charactersLibInfo, "无"))
                .append("\n[characters_lib_name]\n").append(StrUtil.blankToDefault(charactersLibName, "无"))
                .toString();
    }

    /**
     * 进度百分比计算。
     *
     * @param base    起始百分比
     * @param range   百分比范围
     * @param current 当前步骤（0-based）
     * @param total   总步骤数
     * @return 计算后的进度百分比
     */
    private int calculateProgress(int base, int range, int current, int total)
    {
        if (total <= 1)
        {
            return base;
        }
        return base + (range * current / (total - 1));
    }

    /**
     * 角色 profileData：原有抽取字段（archetype / personality_tags / era_period / social_class /
     * occupation / costume_tier / suggested_colors / primary_identifier / visual_keywords）+
     * 主表权威冗余快照（asset_type / name / aliases / introduction / gender / age_range / role_level）。
     * 注：buildVisualStylistInputV2 在 mergeProfileData 里会跳过已存在于 root 的 key，
     * 所以这些冗余快照字段不会反覆盖主表受保护列。
     */
    private String buildCharacterProfileData(JsonNode item, String name, String aliasesName,
                                             String introduction, String gender, String ageRange, String roleLevel)
    {
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode profileNode = OBJECT_MAPPER.createObjectNode();
            String[] profileFields = {"archetype", "personality_tags", "era_period", "social_class",
                    "occupation", "costume_tier", "suggested_colors", "primary_identifier", "visual_keywords"};
            for (String field : profileFields)
            {
                JsonNode fieldValue = item.get(field);
                if (Objects.nonNull(fieldValue) && !fieldValue.isNull())
                {
                    profileNode.set(field, fieldValue);
                }
            }
            profileNode.put("asset_type", ASSET_TYPE_CHARACTER);
            profileNode.put("name", StrUtil.blankToDefault(name, ""));
            profileNode.put("aliases", StrUtil.blankToDefault(aliasesName, ""));
            profileNode.put("introduction", StrUtil.blankToDefault(introduction, ""));
            profileNode.put("gender", StrUtil.blankToDefault(gender, ""));
            profileNode.put("age_range", StrUtil.blankToDefault(ageRange, ""));
            profileNode.put("role_level", StrUtil.blankToDefault(roleLevel, ""));
            return profileNode.toString();
        }
        catch (Exception e)
        {
            log.warn("构建角色profileData失败", e);
            return null;
        }
    }

    /**
     * 场景 profileData：原生字段 + 空间结构扩展字段。
     */
    private String buildSceneProfileData(JsonNode item, String name, String introduction, String summary,
                                         String availableSlotsJson, boolean hasCrowd, String crowdDescription)
    {
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode node = OBJECT_MAPPER.createObjectNode();
            // ---------- 原生字段（字段名与既有列保持一致） ----------
            node.put("asset_type", ASSET_TYPE_SCENE);
            node.put("name", StrUtil.blankToDefault(name, ""));
            node.put("introduction", StrUtil.blankToDefault(introduction, ""));
            node.put("summary", StrUtil.blankToDefault(summary, ""));
            node.put("available_slots", StrUtil.blankToDefault(availableSlotsJson, ""));
            node.put("has_crowd", hasCrowd ? 1 : 0);
            node.put("crowd_description", StrUtil.blankToDefault(crowdDescription, ""));

            // ---------- 空间结构字段（白名单透传） ----------
            if (Objects.nonNull(item))
            {
                copySceneExtField(item, node, "spatialPosition",          "spatial_position");
                copySceneExtField(item, node, "spatialZoneDivision",      "spatial_zone_division");
                copySceneExtField(item, node, "sceneFeatureDescription",  "scene_feature_description");
                copySceneExtField(item, node, "specificLocation",         "specific_location");
                copySceneExtField(item, node, "timeOfDay",                "time_of_day");
                copySceneExtField(item, node, "eraCoordinate",            "era_coordinate");
                copySceneExtField(item, node, "dateCoordinate",           "date_coordinate");
                copySceneExtField(item, node, "weather",                  "weather");
                copySceneExtField(item, node, "regionCoordinate",         "region_coordinate");
                copySceneExtField(item, node, "environmentType",          "environment_type");
            }

            return node.toString();
        }
        catch (Exception e)
        {
            log.warn("构建场景profileData失败", e);
            return null;
        }
    }

    /**
     * 复制单个字符串字段:支持 LLM 同时返回 camelCase 和 snake_case (下游 LLM 偶尔会偏离 schema)。
     * 字段空时不写入,保证 profile_data 紧凑。
     */
    private void copySceneExtField(JsonNode item, com.fasterxml.jackson.databind.node.ObjectNode node,
                                   String camelKey, String snakeKey)
    {
        JsonNode v = item.get(camelKey);
        if (Objects.isNull(v) || v.isNull())
        {
            v = item.get(snakeKey);
        }
        if (Objects.nonNull(v) && !v.isNull())
        {
            String text = v.asText("");
            if (StrUtil.isNotBlank(text))
            {
                node.put(snakeKey, text);
            }
        }
    }

    /**
     * 复制数组字段,保留为 JSON 数组(每个元素均为字符串)。
     * 字段为空数组时不写入。
     */
    private void copySceneExtArray(JsonNode item, com.fasterxml.jackson.databind.node.ObjectNode node,
                                   String camelKey, String snakeKey)
    {
        JsonNode v = item.get(camelKey);
        if (Objects.isNull(v) || !v.isArray() || v.isEmpty())
        {
            v = item.get(snakeKey);
        }
        if (Objects.nonNull(v) && v.isArray() && !v.isEmpty())
        {
            com.fasterxml.jackson.databind.node.ArrayNode arr = node.putArray(snakeKey);
            for (JsonNode elem : v)
            {
                String text = elem.asText("").trim();
                if (StrUtil.isNotBlank(text))
                {
                    arr.add(text);
                }
            }
            // 数组内全部是空串时回退:把空数组节点移除
            if (arr.isEmpty())
            {
                node.remove(snakeKey);
            }
        }
    }

    /**
     * 道具 profileData：asset_type / name / summary / introduction + 融合版扩展字段。
     * introduction 取清洗后的视觉描述（{@code resolvePropVisualDescription}）。
     * 扩展字段（propType / material / color / dimensions / specialDesign / eraCoordinate /
     * narrativeFunction / stateChange / stateName）从 LLM 返回的 JsonNode 透传，缺失字段不写入。
     */
    private String buildPropProfileData(JsonNode item, String name, String summary, String introduction)
    {
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("asset_type", ASSET_TYPE_PROP);
            node.put("name", StrUtil.blankToDefault(name, ""));
            node.put("summary", StrUtil.blankToDefault(summary, ""));
            node.put("introduction", StrUtil.blankToDefault(introduction, ""));

            // 融合版扩展字段（白名单透传，缺字段时不写入）
            if (Objects.nonNull(item))
            {
                copySceneExtField(item, node, "propType",             "prop_type");
                copySceneExtField(item, node, "material",             "material");
                copySceneExtField(item, node, "color",                "color");
                copySceneExtField(item, node, "dimensions",           "dimensions");
                copySceneExtField(item, node, "specialDesign",        "special_design");
                copySceneExtField(item, node, "eraCoordinate",        "era_coordinate");
                copySceneExtField(item, node, "narrativeFunction",    "narrative_function");
                copySceneExtField(item, node, "stateChange",          "state_change");
                copySceneExtField(item, node, "stateName",            "state_name");
            }

            return node.toString();
        }
        catch (Exception e)
        {
            log.warn("构建道具profileData失败", e);
            return null;
        }
    }

    /**
     * 从 LLM 提取结果构建 expected_appearances JSON 字符串。
     * LLM 返回的元素可能只有 {id, change_reason}，缺少 name 字段。
     * 此方法会为每个元素补充 name：优先取 change_reason 中冒号前的部分作为形态名，
     * 找不到则回退为 "形态N"。
     */
    private String buildExpectedAppearances(JsonNode item)
    {
        JsonNode appearances = item.get("expected_appearances");
        if (Objects.isNull(appearances) || !appearances.isArray() || appearances.isEmpty())
        {
            return null;
        }
        // 逐元素补充 name 字段
        com.fasterxml.jackson.databind.node.ArrayNode enriched = OBJECT_MAPPER.createArrayNode();
        for (int i = 0; i < appearances.size(); i++)
        {
            JsonNode elem = appearances.get(i);
            com.fasterxml.jackson.databind.node.ObjectNode node = OBJECT_MAPPER.createObjectNode();
            // id
            node.put("id", elem.has("id") ? elem.get("id").asInt(i) : i);
            // name：优先取已有 name，否则从 change_reason 冒号前截取
            String existingName = elem.has("name") ? elem.get("name").asText("") : "";
            String changeReason = elem.has("change_reason") ? elem.get("change_reason").asText("") : "";
            if (StrUtil.isBlank(existingName))
            {
                existingName = deriveAppearanceName(changeReason, i);
            }
            // 最上游清洗：剥离选角导演误拼接的外貌描述脏值（如「初始形象，白衣佩剑的女剑修」），
            // 从源头保证 expected_appearances 落库即为干净短标签
            node.put("name", sanitizeFormLabel(existingName));
            node.put("change_reason", sanitizeFormLabel(changeReason));
            enriched.add(node);
        }
        return enriched.toString();
    }

    /**
     * 从 change_reason 中提取形态名：取中文冒号 '：' 或英文冒号 ':' 前的部分。
     * 找不到冒号时回退为 "形态{index}"。
     */
    private String deriveAppearanceName(String changeReason, int index)
    {
        if (StrUtil.isBlank(changeReason))
        {
            return "形态" + index;
        }
        // 优先中文冒号
        int pos = changeReason.indexOf('：');
        if (pos <= 0)
        {
            pos = changeReason.indexOf(':');
        }
        if (pos > 0)
        {
            return changeReason.substring(0, pos).trim();
        }
        // 无冒号，使用完整 changeReason 作为 name
        return changeReason.trim();
    }

    /**
     * 形态标签（name / change_reason）脏值清洗。
     *
     * @param raw 原始标签
     * @return 清洗后的短标签；入参为空白时原样返回
     */
    private String sanitizeFormLabel(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return raw;
        }
        String s = raw.trim();
        int cut = -1;
        // 在首个描述性分隔符处截断
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '，' || c == ',' || c == '、' || c == '；' || c == ';' || c == '\n' || c == '\r')
            {
                cut = i;
                break;
            }
        }
        if (cut >= 0)
        {
            s = s.substring(0, cut).trim();
        }
        // 极端情况：标签以分隔符开头导致截断后为空，回退原始 trim 值，避免产出空标签
        return StrUtil.isBlank(s) ? raw.trim() : s;
    }

    private RpsAssetVO buildAssetVO(AidRolePropScene mainEntity, List<AidRolePropSceneForm> formEntities)
    {
        String assetType = mainEntity.getAssetType();
        List<RpsFormVO> formVOs = new ArrayList<>();
        for (AidRolePropSceneForm f : formEntities)
        {
            // form 表不再承载图片字段；buildAssetVO 用于批量提取场景，未带 form_image 上下文，
            // 主图由上层后续调 convertToFormVO(form, imgs) 重新填充，此处留空。
            RpsFormVO.RpsFormVOBuilder formBuilder = RpsFormVO.builder()
                    .id(f.getId())
                    .assetType(assetType)
                    .name(f.getName())
                    .changeReason(f.getChangeReason())
                    .createSource(f.getCreateSource())
                    .visualDescStatus(f.getVisualDescStatus());
            // 按 assetType 解析 promptText 为结构化字段
            enrichFormPromptFieldsFromJson(formBuilder, f.getPromptText(), assetType);
            formVOs.add(formBuilder.build());
        }

        // 按 assetType 分类填充结构化字段（不再返回原始 JSON 字符串）
        RpsAssetVO.RpsAssetVOBuilder builder = RpsAssetVO.builder()
                .id(mainEntity.getId())
                .assetType(assetType)
                .createSource(mainEntity.getCreateSource())
                .assetName(mainEntity.getName())
                .introduction(mainEntity.getIntroduction())
                .forms(formVOs);

        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            builder.aliasesName(mainEntity.getAliasesName())
                    .gender(mainEntity.getGender())
                    .ageRange(mainEntity.getAgeRange())
                    .roleLevel(mainEntity.getRoleLevel());
            // 解析 profileData 中的角色扩展字段
            enrichCharacterProfileFieldsFromJson(builder, mainEntity.getProfileData());
            // 解析 expectedAppearances JSON 数组
            builder.expectedAppearances(parseJsonArraySafe(mainEntity.getExpectedAppearances()));
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            builder.summary(mainEntity.getSummary())
                    .hasCrowd(mainEntity.getHasCrowd())
                    .crowdDescription(mainEntity.getCrowdDescription());
            builder.availableSlots(parseStringArraySafe(mainEntity.getAvailableSlots()));
        }
        else if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            builder.summary(mainEntity.getSummary());
        }

        return builder.build();
    }

    /**
     * 从 promptText JSON 中提取结构化字段填充到 RpsFormVO builder。
     * 解析失败静默降级，不阻塞正常返回。
     */
    private void enrichFormPromptFieldsFromJson(RpsFormVO.RpsFormVOBuilder builder, String promptText, String assetType)
    {
        if (StrUtil.isBlank(promptText))
        {
            return;
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(promptText);
            JsonNode root = Objects.nonNull(parsed) && parsed.isArray() && !parsed.isEmpty()
                    ? parsed.get(0) : parsed;
            if (Objects.isNull(root) || !root.isObject())
            {
                return;
            }
            if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
            {
                JsonNode desc = root.get("descriptions");
                if (Objects.nonNull(desc) && !desc.isNull()) builder.descriptions(desc.asText());
                JsonNode appId = root.has("appearanceId") ? root.get("appearanceId") : root.get("appearance_id");
                if (Objects.nonNull(appId) && appId.isNumber()) builder.appearanceId(appId.asInt());
            }
            else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
            {
                JsonNode prompt = root.get("prompt");
                if (Objects.nonNull(prompt) && !prompt.isNull()) builder.prompt(prompt.asText());
                JsonNode summary = root.get("summary");
                if (Objects.nonNull(summary) && !summary.isNull()) builder.summary(summary.asText());
                JsonNode intro = root.get("introduction");
                if (Objects.nonNull(intro) && !intro.isNull()) builder.introduction(intro.asText());
                JsonNode hc = root.has("hasCrowd") ? root.get("hasCrowd") : root.get("has_crowd");
                if (Objects.nonNull(hc) && hc.isNumber()) builder.hasCrowd(hc.asInt());
                JsonNode cd = root.has("crowdDescription") ? root.get("crowdDescription") : root.get("crowd_description");
                if (Objects.nonNull(cd) && !cd.isNull()) builder.crowdDescription(cd.asText());
                JsonNode slots = root.has("availableSlots") ? root.get("availableSlots") : root.get("available_slots");
                builder.availableSlots(extractStringArray(slots));
            }
            else if (Objects.equals(ASSET_TYPE_PROP, assetType))
            {
                JsonNode prompt = root.get("prompt");
                if (Objects.nonNull(prompt) && !prompt.isNull()) builder.prompt(prompt.asText());
                JsonNode summary = root.get("summary");
                if (Objects.nonNull(summary) && !summary.isNull()) builder.summary(summary.asText());
                JsonNode intro = root.get("introduction");
                if (Objects.nonNull(intro) && !intro.isNull()) builder.introduction(intro.asText());
            }
        }
        catch (Exception e)
        {
            log.warn("promptText 解析失败，降级跳过: assetType={}, err={}", assetType, e.getMessage());
        }
    }

    /**
     * 从 profileData JSON 中提取角色扩展字段填充到 VO builder。
     * 解析失败静默降级，不阻塞正常返回。
     */
    private void enrichCharacterProfileFieldsFromJson(RpsAssetVO.RpsAssetVOBuilder builder, String profileData)
    {
        if (StrUtil.isBlank(profileData))
        {
            return;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(profileData);
            if (Objects.isNull(root) || !root.isObject())
            {
                return;
            }
            // 文本字段
            if (root.has("archetype") && !root.get("archetype").isNull())
                builder.archetype(root.get("archetype").asText());
            if (root.has("era_period") && !root.get("era_period").isNull())
                builder.eraPeriod(root.get("era_period").asText());
            if (root.has("occupation") && !root.get("occupation").isNull())
                builder.occupation(root.get("occupation").asText());
            if (root.has("social_class") && !root.get("social_class").isNull())
                builder.socialClass(root.get("social_class").asText());
            if (root.has("primary_identifier") && !root.get("primary_identifier").isNull())
                builder.primaryIdentifier(root.get("primary_identifier").asText());
            // 数值字段
            if (root.has("costume_tier") && root.get("costume_tier").isNumber())
                builder.costumeTier(root.get("costume_tier").asInt());
            // 数组字段
            builder.visualKeywords(extractStringArray(root.get("visual_keywords")));
            builder.personalityTags(extractStringArray(root.get("personality_tags")));
            builder.suggestedColors(extractStringArray(root.get("suggested_colors")));
        }
        catch (Exception e)
        {
            log.warn("profileData 解析失败，降级跳过: err={}", e.getMessage());
        }
    }

    /**
     * 解析 JSON 数组字符串为 List（expectedAppearances 等），解析失败返回 null。
     */
    @SuppressWarnings("unchecked")
    private List<Object> parseJsonArraySafe(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(json);
            if (Objects.isNull(arr) || !arr.isArray())
            {
                return null;
            }
            return OBJECT_MAPPER.convertValue(arr, List.class);
        }
        catch (Exception e)
        {
            log.warn("JSON 数组解析失败: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 JSON 数组字符串为 List&lt;String&gt;（availableSlots 等），解析失败返回 null。
     */
    private List<String> parseStringArraySafe(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            JsonNode arr = OBJECT_MAPPER.readTree(json);
            return extractStringArray(arr);
        }
        catch (Exception e)
        {
            log.warn("字符串数组解析失败: err={}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 JsonNode（应为数组）提取 List&lt;String&gt;。
     *
     *   - null / 非数组 → 返回 null（表示缺失/异常）
     *   - 合法空数组 [] → 返回空 List（表示明确无值，是有效业务状态）
     *
     */
    private List<String> extractStringArray(JsonNode arrNode)
    {
        if (Objects.isNull(arrNode) || !arrNode.isArray())
        {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode n : arrNode)
        {
            String s = n.asText("");
            if (StrUtil.isNotBlank(s))
            {
                result.add(s);
            }
        }
        // 合法空数组是有效业务值，不应被吞为 null
        return result;
    }
    private static final String FORM_LOCK_PREFIX = "asset:form:lock:";

    @Override
    public AssetExtractTaskVO batchGenerateForm(List<Long> assetIds, Long userId,
                                                String agentCode, String requestedModelCode)
    {
        FormGenerateBillingPlan plan = prepareFormGenerateBillingPlan(
                assetIds, userId, agentCode, requestedModelCode);
        List<Long> uniqueAssetIds = plan.assetIds();
        List<FormGenerateValidated> validatedList = plan.validatedList();
        String modelCode = plan.modelCode();

        FormGenerateValidated first = validatedList.get(0);
        for (FormGenerateValidated validated : validatedList)
        {
            if (!Objects.equals(first.asset.getProjectId(), validated.asset.getProjectId())
                    || !Objects.equals(first.asset.getEpisodeId(), validated.asset.getEpisodeId()))
            {
                log.info("批量形态生成禁止跨项目剧集: userId={}, assetIds={}", userId, uniqueAssetIds);
                throw new ServiceException("素材归属不一致");
            }
        }
        BatchTaskSlotReservation slotReservation = batchTaskSlotService.acquireTaskSlots(
                first.asset.getProjectId(), first.asset.getEpisodeId(),
                List.of(mapAssetTypeToFormGenerateLogicalType(first.asset.getAssetType())));

        List<String> acquiredLockKeys = new ArrayList<>();
        String itemLockToken = IdUtil.fastSimpleUUID();
        try
        {
            for (Long assetId : uniqueAssetIds)
            {
                String formLockKey = buildFormGenerateLockKey(assetId);
                Boolean locked = redisCache.redisTemplate.opsForValue()
                        .setIfAbsent(formLockKey, itemLockToken, 900, TimeUnit.SECONDS);
                if (locked == null || !locked)
                {
                    log.info("批量形态生成失败，资产已在处理中: assetId={}", assetId);
                    throw new ServiceException("任务处理中");
                }
                acquiredLockKeys.add(formLockKey);
            }
        }
        catch (RuntimeException e)
        {
            // 回滚已获取的锁
            for (String key : acquiredLockKeys)
            {
                casDeleteExtractLockIfMatch(key, itemLockToken);
            }
            batchTaskSlotService.release(slotReservation);
            throw e;
        }

        AidExtractTask task = null;
        try
        {
            task = new AidExtractTask();
            task.setProjectId(first.asset.getProjectId());
            task.setEpisodeId(first.asset.getEpisodeId());
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_FORM_GENERATE_BATCH);
            task.setModelCode(modelCode);
            // inputSnapshot 存整批参数（JSON）
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("assetIds", uniqueAssetIds);
            inputMap.put("modelCode", modelCode);
            inputMap.put("itemLockToken", itemLockToken);
            inputMap.put("assetType", first.asset.getAssetType());
            batchTaskSlotService.attachSnapshotMetadata(inputMap, slotReservation);
            // agentCode 已按 character / scene / prop 对应业务分类完成校验。
            if (StrUtil.isNotBlank(agentCode))
            {
                inputMap.put("agentCode", agentCode);
            }
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
            task.setStatus(TASK_STATUS_PENDING);
            task.setTotalCount(uniqueAssetIds.size());
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            extractTaskService.save(task);

            // 整批预冻结：对每个 assetId 估算费用并累加，一次性冻结到父任务上
            BigDecimal totalFrozen = BigDecimal.ZERO;
            List<Map<String, Object>> itemSnapshots = new ArrayList<>();
            for (FormGenerateValidated v : validatedList)
            {
                BillingCalcResult itemCalc = estimateFormGenerateCost(task.getId(), v.asset, modelCode);
                if (itemCalc != null && itemCalc.getAmount() != null)
                {
                    totalFrozen = totalFrozen.add(itemCalc.getAmount());
                }
                if (itemCalc != null && itemCalc.getSnapshot() != null)
                {
                    Map<String, Object> snap = new LinkedHashMap<>();
                    snap.put("assetId", v.assetId);
                    snap.put("snapshot", itemCalc.getSnapshot());
                    itemSnapshots.add(snap);
                }
            }
            String batchSnapshotJson = null;
            if (!itemSnapshots.isEmpty())
            {
                Map<String, Object> batchSnap = new LinkedHashMap<>();
                batchSnap.put("batchType", TASK_TYPE_FORM_GENERATE_BATCH);
                batchSnap.put("items", itemSnapshots);
                batchSnapshotJson = JSONUtil.toJsonStr(batchSnap);
            }
            extractBillingService.prepareBilling(task.getId(), userId, totalFrozen, batchSnapshotJson);
            log.info("批量形态生成预冻结成功: taskId={}, userId={}, assetCount={}, totalFrozen={}",
                    task.getId(), userId, uniqueAssetIds.size(), totalFrozen);

            // 双模式派发统一收口（排队 + 多维并发调度）：MQ 开发 MQ；MQ 关走本地线程
            final Long taskId = task.getId();
            boolean enqueued = dualModeTaskDispatcher.dispatch(taskId,
                    first.asset.getProjectId(), first.asset.getEpisodeId(),
                    userId, modelCode, TASK_TYPE_FORM_GENERATE_BATCH,
                    buildFormBatchLocalJob(taskId, userId, LOCAL_SPEC_FORM_GENERATE,
                            () -> doFormGenerateBatch(taskId, userId,
                                    taskQueueService.currentLocalDispatchToken(taskId))));
            if (!enqueued)
            {
                log.warn("批量形态生成入队失败(可能已取消): taskId={}", task.getId());
                throw new ServiceException("任务提交失败");
            }

            log.info("批量形态生成父任务创建成功: taskId={}, userId={}, assetIds={}", task.getId(), userId, uniqueAssetIds);
            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .build();
        }
        catch (Exception e)
        {
            log.error("批量形态生成父任务创建失败，回滚所有锁: userId={}", userId, e);
            boolean canRelease = Objects.isNull(task) || Objects.isNull(task.getId())
                    || closeFormBatchSubmissionFailure(task, userId, true);
            if (canRelease)
            {
                for (String key : acquiredLockKeys)
                {
                    casDeleteExtractLockIfMatch(key, itemLockToken);
                }
            }
            if (Objects.isNull(task) || Objects.isNull(task.getId()))
            {
                batchTaskSlotService.release(slotReservation);
            }
            throw new ServiceException("提交失败，请重试");
        }
    }

    @Override
    public BillingQuoteVO quoteFormGenerate(FormGenerateRequest request, Long userId)
    {
        FormGenerateBillingPlan plan = prepareFormGenerateBillingPlan(
                request.getAssetIds(), userId, request.getAgentCode(), request.getModelCode());
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(plan.modelCode());
        if (modelConfig == null)
        {
            throw new ServiceException("模型不存在");
        }
        List<BillingQuoteVO> items = new ArrayList<>();
        for (FormGenerateValidated validated : plan.validatedList())
        {
            BillingCalcResult result = estimateFormGenerateCost(
                    null, validated.asset, plan.modelCode());
            if (result == null || !result.isMatched())
            {
                throw new ServiceException("计费规则缺失");
            }
            items.add(billingQuoteAssembler.single(
                    "FORM_GENERATE", modelConfig, result, 1));
        }
        return billingQuoteAssembler.aggregate("FORM_GENERATE", items);
    }

    private FormGenerateBillingPlan prepareFormGenerateBillingPlan(List<Long> assetIds,
            Long userId, String agentCode, String requestedModelCode)
    {
        if (CollectionUtil.isEmpty(assetIds))
        {
            throw new ServiceException("参数错误");
        }
        if (assetIds.size() > 100)
        {
            throw new ServiceException("批量过多");
        }
        List<Long> uniqueAssetIds = assetIds.stream().distinct().collect(Collectors.toList());
        List<FormGenerateValidated> validatedList = new ArrayList<>();
        for (Long assetId : uniqueAssetIds)
        {
            validatedList.add(validateSingleFormGenerate(assetId, userId));
        }
        assertAgentForFormBatch(agentCode, validatedList);
        FormGenerateValidated first = validatedList.get(0);
        ProjectGenConfigScene scene = mapAssetTypeToFormScene(first.asset.getAssetType());
        com.aid.projectgenconfig.service.ResolvedSceneConfig resolved =
                projectGenConfigResolver.resolve(first.asset.getProjectId(), userId, scene,
                        agentCode, requestedModelCode, null, null);
        for (FormGenerateValidated item : validatedList)
        {
            item.modelCode = resolved.getModelCode();
        }
        return new FormGenerateBillingPlan(List.copyOf(uniqueAssetIds),
                List.copyOf(validatedList), resolved.getModelCode());
    }

    private record FormGenerateBillingPlan(List<Long> assetIds,
            List<FormGenerateValidated> validatedList, String modelCode)
    {
    }
    /**
     * 批量形态生成核心逻辑（由 Consumer 调用）。
     * 逐个执行 assetIds 中的形态生成，每完成一项推 stepProgress + 释放锁。
     * 单项失败不影响整批。
     * 计费：父任务预冻结后聚合关联媒体子任务的真实 provider usage；
     * 业务失败也按实际调用收口，只有权威确认零调用时退款。
     */
    @Override
    @SuppressWarnings("unchecked")
    public String doFormGenerateBatch(Long taskId, Long userId, String dispatchToken)
    {
        String billingTraceId = requireCurrentBillingTrace(taskId, dispatchToken);
        Map<String, Object> input = parseBatchInput(taskId);
        List<Long> assetIds = parseIdList(input, "assetIds");
        String itemLockToken = resolveItemLockToken(input);
        String modelCode = String.valueOf(input.getOrDefault("modelCode", ""));
        int runTotal = assetIds.size();
        int total = resolveResumeOriginalTotal(input, runTotal);

        List<Map<String, Object>> successItems = parseResumeSeedSuccessItems(input);
        List<Map<String, Object>> failedItems = new ArrayList<>();
        int successCount = successItems.size();
        int currentSuccessCount = 0;
        int failCount = 0;

        for (int i = 0; i < runTotal; i++)
        {
            Long assetId = assetIds.get(i);
            String lockKey = buildFormGenerateLockKey(assetId);

            // ★ 取消检查点
            if (isTaskCancelled(taskId))
            {
                log.info("批量形态生成被取消: taskId={}, done={}/{}", taskId, i, runTotal);
                for (int j = i; j < runTotal; j++)
                {
                    releaseBatchItemLock(taskId, buildFormGenerateLockKey(assetIds.get(j)), itemLockToken);
                }
                break;
            }

            // 推送步骤进度（补充 successCount / failCount / currentId）
            int progress = 10 + (int) ((i * 80.0) / runTotal);
            sseManager.sendStepProgress(taskId, "form_generate", progress,
                    "asset_" + assetId, "正在生成设定 " + (i + 1) + "/" + runTotal, i + 1, runTotal);

            try
            {
                AidRolePropScene asset = rpsService.getById(assetId);
                if (Objects.isNull(asset) || !Objects.equals(DEL_FLAG_NORMAL, asset.getDelFlag())
                        || !Objects.equals(userId, asset.getUserId()))
                {
                    throw new ServiceException("资产不存在");
                }
                String assetType = asset.getAssetType();
                List<AidRolePropSceneForm> newForms;
                if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
                {
                    newForms = generateCharacterForms(
                            asset, modelCode, userId, taskId, billingTraceId);
                }
                else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
                {
                    newForms = List.of(generateSceneForm(
                            asset, modelCode, userId, taskId, billingTraceId));
                }
                else if (Objects.equals(ASSET_TYPE_PROP, assetType))
                {
                    newForms = List.of(generatePropForm(
                            asset, modelCode, userId, taskId, billingTraceId));
                }
                else
                {
                    throw new ServiceException("类型不支持");
                }

                Long firstFormId = CollectionUtil.isNotEmpty(newForms) ? newForms.get(0).getId() : null;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("assetId", assetId);
                item.put("formId", firstFormId);
                item.put("formCount", newForms.size());
                successItems.add(item);
                successCount++;
                currentSuccessCount++;
                log.info("批量形态生成单项完成: taskId={}, assetId={}, formCount={}", taskId, assetId, newForms.size());
            }
            catch (TextTaskExecutionRejectedException e)
            {
                for (int j = i + 1; j < runTotal; j++)
                {
                    releaseBatchItemLock(taskId, buildFormGenerateLockKey(assetIds.get(j)), itemLockToken);
                }
                throw e;
            }
            catch (Exception e)
            {
                log.error("批量形态生成单项失败: taskId={}, assetId={}", taskId, assetId, e);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("assetId", assetId);
                item.put("message", TaskErrorPresentation.toUserMessage(
                        modelCode, e.getMessage(), "图片生成失败"));
                failedItems.add(item);
                failCount++;
                if (BillingBalanceErrors.isPreholdNotEnough(e))
                {
                    // 当前 child 未发 Provider；保留此前已完成结果并立即停止后续滚动调用。
                    for (int j = i + 1; j < runTotal; j++)
                    {
                        releaseBatchItemLock(taskId, buildFormGenerateLockKey(assetIds.get(j)), itemLockToken);
                    }
                    break;
                }
            }
            finally
            {
                // 每处理完一项释放该项锁
                releaseBatchItemLock(taskId, lockKey, itemLockToken);
            }
        }

        // 整批结算只读取关联子任务的 provider usage；失败调用返回 usage 时同样计费。
        try
        {
            if (currentSuccessCount > 0)
            {
                boolean billingClosed = settleFormGenerateUsage(taskId, userId, billingTraceId);
                if (billingClosed)
                {
                    log.info("批量形态生成真实用量结算成功: taskId={}, success={}, fail={}",
                            taskId, successCount, failCount);
                }
                else
                {
                    log.warn("批量形态生成用量尚未收敛，保留冻结等待补偿: taskId={}", taskId);
                }
            }
            else
            {
                boolean billingClosed = extractBillingService.settleOrRefundAfterExecutionFailure(
                        taskId, userId, billingTraceId);
                if (!billingClosed)
                {
                    log.warn("批量形态生成全部失败计费尚未收敛，保留冻结等待补偿: taskId={}", taskId);
                }
            }
        }
        catch (Exception billingEx)
        {
            log.error("批量形态生成计费结算异常（不影响业务结果）: taskId={}", taskId, billingEx);
        }

        return buildBatchResultJson(total, successCount, failCount, successItems, failedItems);
    }

    /**
     * 批量形态图生成核心逻辑（由 Consumer 调用）。
     * 逐个执行 formIds 中的图片生成。计费由图片生成主链路内部处理。
     */
    @Override
    @SuppressWarnings("unchecked")
    public String doFormImageBatch(Long taskId, Long userId, String dispatchToken)
    {
        String billingTraceId = requireCurrentBillingTrace(taskId, dispatchToken);
        Map<String, Object> input = parseBatchInput(taskId);
        List<Long> formIds = parseIdList(input, "formIds");
        String itemLockToken = resolveItemLockToken(input);
        // formIds 已在提交阶段（batchGenerateFormImage）按「同角色初始形象优先于其变体」排好序并存入 inputSnapshot，
        // 消费端直接顺序消费即可，无需再查库重排。
        // ★★ 强约束：本批量循环必须保持单线程顺序执行，禁止改造成并发 / 线程池。
        //    变体形态依赖同角色「初始形象」先出图并落库完成（image_status=completed）才能反查到基准图，
        //    一旦并行化，变体可能在初始形象 completed 之前执行，初始形象优先的顺序保证会静默失效。
        String modelCode = String.valueOf(input.getOrDefault("modelCode", ""));
        String agentCode = Objects.toString(input.get("agentCode"), null);
        // resolution / aspectRatio 从 input_snapshot 取，缺失走 null 兜底
        String resolution = StrUtil.trim(String.valueOf(input.getOrDefault("resolution", "")));
        String aspectRatio = StrUtil.trim(String.valueOf(input.getOrDefault("aspectRatio", "")));
        int runTotal = formIds.size();
        int total = resolveResumeOriginalTotal(input, runTotal);

        List<Map<String, Object>> successItems = parseResumeSeedSuccessItems(input);
        List<Map<String, Object>> failedItems = new ArrayList<>();
        int successCount = successItems.size();
        int failCount = 0;
        // 记录本批内「初始形象基准形态」生成失败的角色 assetId：
        // 后续同角色变体形态执行到基准图反查时，可据此给出「初始形象生成失败」的清晰文案，
        // 而非笼统的「请先生成初始形象」（用户明明已把初始形象一起提交，只是它生成失败了）。
        Set<Long> failedInitialAssetIds = new HashSet<>();

        for (int i = 0; i < runTotal; i++)
        {
            // 注意：此处必须串行处理（见上方强约束），不可并行化
            Long formId = formIds.get(i);
            String lockKey = buildFormImageLockKey(formId);

            // ★ 取消检查点
            if (isTaskCancelled(taskId))
            {
                log.info("批量形态图生成被取消: taskId={}, done={}/{}", taskId, i, runTotal);
                for (int j = i; j < runTotal; j++)
                {
                    releaseBatchItemLock(taskId, buildFormImageLockKey(formIds.get(j)), itemLockToken);
                }
                break;
            }

            int progress = 10 + (int) ((i * 80.0) / runTotal);
            sseManager.sendStepProgress(taskId, "form_image_gen", progress,
                    "form_" + formId, "正在生成形态图 " + (i + 1) + "/" + runTotal, i + 1, runTotal);

            try
            {
                // 执行单项形态图生成（复用已有逻辑）
                Map<String, Object> resultItem = executeSingleFormImageInternal(
                        taskId, formId, agentCode, modelCode, resolution, aspectRatio, userId,
                        failedInitialAssetIds, billingTraceId);

                successItems.add(resultItem);
                successCount++;
                log.info("批量形态图单项完成: taskId={}, formId={}, imageUrl={}", taskId, formId, resultItem.get("imageUrl"));
            }
            catch (BatchTaskExecutionRejectedException rejected)
            {
                for (int j = i + 1; j < runTotal; j++)
                {
                    releaseBatchItemLock(taskId, buildFormImageLockKey(formIds.get(j)), itemLockToken);
                }
                throw rejected;
            }
            catch (Exception e)
            {
                log.error("批量形态图单项失败: taskId={}, formId={}", taskId, formId, e);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("formId", formId);
                item.put("message", TaskErrorPresentation.toUserMessage(
                        modelCode, e.getMessage(), "图片生成失败"));
                failedItems.add(item);
                failCount++;
            }
            finally
            {
                releaseBatchItemLock(taskId, lockKey, itemLockToken);
            }
        }

        settleFormBatchDispatchCycle(taskId, userId, TASK_TYPE_FORM_IMAGE_BATCH, dispatchToken);
        return buildBatchResultJson(total, successCount, failCount, successItems, failedItems);
    }

    /**
     * 单项形态图生成内部逻辑（复用 submitSingleFormImageTask 中的核心步骤，不创建子任务）。
     *
     * @param failedInitialAssetIds 本批内「初始形象基准形态」已生成失败的角色 assetId 集合（消费循环维护，可空）：
     */
    private Map<String, Object> executeSingleFormImageInternal(Long parentTaskId, Long formId,
                                                               String agentCode,
                                                               String resolvedModelCode,
                                                               String resolvedResolution,
                                                               String resolvedAspectRatio,
                                                               Long userId,
                                                               Set<Long> failedInitialAssetIds,
                                                               String expectedTraceId)
    {
        AidRolePropSceneForm form = rpsFormService.getById(formId);
        if (Objects.isNull(form) || !Objects.equals(DEL_FLAG_NORMAL, form.getDelFlag())
                || !Objects.equals(userId, form.getUserId()))
        {
            throw new ServiceException("形态不存在");
        }
        AidRolePropScene asset = rpsService.getById(form.getAssetId());
        if (Objects.isNull(asset))
        {
            throw new ServiceException("资产不存在");
        }
        // 当前 form 是否为「角色初始形象」基准形态：决定失败时是否登记到 failedInitialAssetIds
        boolean characterInitialForm = Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType())
                && !isCharacterVariantForm(asset, form);
        // 变体形态短路：同批内其「初始形象」已生成失败 → 直接给清晰文案，不再尝试反查必然缺失的基准图
        if (isCharacterVariantForm(asset, form)
                && CollectionUtil.isNotEmpty(failedInitialAssetIds)
                && failedInitialAssetIds.contains(asset.getId()))
        {
            log.info("变体形态跳过：同批内初始形象生成失败, formId={}, assetId={}", formId, asset.getId());
            throw new ServiceException("初始形象生成失败");
        }
        try
        {
            return doExecuteSingleFormImage(parentTaskId, formId, form, asset,
                    agentCode, resolvedModelCode, resolvedResolution, resolvedAspectRatio, userId,
                    expectedTraceId);
        }
        catch (Exception e)
        {
            // 初始形象基准形态本次生成失败 → 登记 assetId，供同批后续同角色变体形态给出清晰文案。
            // 放宽到 Exception（含潜在受检异常）登记后再原样上抛，避免登记遗漏导致变体退回笼统文案。
            if (characterInitialForm && Objects.nonNull(failedInitialAssetIds) && Objects.nonNull(asset.getId()))
            {
                failedInitialAssetIds.add(asset.getId());
            }
            throw e;
        }
    }

    /**
     * 单项形态图生成核心步骤（form / asset 已由调用方加载校验）：解析继承基准图 → 拼 prompt → 调媒体主链路出图 → 落库。
     */
    private Map<String, Object> doExecuteSingleFormImage(Long parentTaskId, Long formId,
                                                         AidRolePropSceneForm form, AidRolePropScene asset,
                                                         String agentCode,
                                                         String resolvedModelCode,
                                                         String resolvedResolution,
                                                         String resolvedAspectRatio,
                                                         Long userId,
                                                         String expectedTraceId)
    {
        AidComicProject project = projectService.selectAidComicProjectById(asset.getProjectId());
        if (Objects.isNull(project))
        {
            throw new ServiceException("项目不存在");
        }
        FormImageMediaBillingPlan mediaPlan = buildFormImageMediaBillingPlan(
                parentTaskId, form, asset, project, agentCode, resolvedModelCode,
                resolvedResolution, resolvedAspectRatio, userId, false);
        MediaImageGenerateRequest imageRequest = mediaPlan.request();
        String finalPrompt = mediaPlan.finalPrompt();
        String inheritedRefImageUrl = mediaPlan.referenceImageUrl();
        String modelCode = resolvedModelCode;

        MediaTaskResponse imageResponse = batchParentSubmissionGuard.executeManagedTask(
                parentTaskId, expectedTraceId,
                () -> mediaGenerationService.generateImage(imageRequest));
        return batchParentSubmissionGuard.executeManagedBusinessCommit(parentTaskId, expectedTraceId, () -> {
            String imageUrl = resolveImageUrl(imageResponse, modelCode);
            if (StrUtil.isBlank(imageUrl))
            {
                throw new ServiceException("图片生成失败");
            }
            // 回溯链路：带参考图时落库 reference_images，纯文生图时落空列表（与原行为一致）
            List<String> effectiveRefImages = StrUtil.isNotBlank(inheritedRefImageUrl)
                    ? List.of(inheritedRefImageUrl)
                    : java.util.Collections.emptyList();
            Long imageId = persistAutoGeneratedFormImage(
                    form, asset, imageUrl, finalPrompt, effectiveRefImages, parentTaskId, userId);
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("formId", formId);
            resultMap.put("imageId", imageId);
            resultMap.put("imageUrl", toClientMediaUrl(imageUrl));
            return resultMap;
        });
    }

    private FormImageMediaBillingPlan buildFormImageMediaBillingPlan(Long bizTaskId,
            AidRolePropSceneForm form, AidRolePropScene asset, AidComicProject project,
            String agentCode, String modelCode, String resolution, String aspectRatio, Long userId,
            boolean allowDeferredReference)
    {
        String inheritedRefImageUrl;
        boolean estimated = false;
        try
        {
            inheritedRefImageUrl = resolveInheritedBaseImageUrl(asset, form, userId);
        }
        catch (ServiceException e)
        {
            if (!allowDeferredReference || !isCharacterVariantForm(asset, form))
            {
                throw e;
            }
            inheritedRefImageUrl = null;
            estimated = true;
        }

        String capabilityCode = resolveFormImageCapability(inheritedRefImageUrl, estimated);
        AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(modelCode,
                helper.businessFunctionForAgent(agentCode), capabilityCode);
        ModelCapabilityResolver.ImageSizeSpec sizeSpec = ModelCapabilityResolver.resolveImageSpec(
                modelConfig, resolution, aspectRatio);
        String finalPrompt = buildFormImagePrompt(
                project, asset, form, agentCode, sizeSpec.aspectRatio());
        if (StrUtil.isBlank(finalPrompt))
        {
            throw new ServiceException("模板异常");
        }
        if (StrUtil.isNotBlank(inheritedRefImageUrl))
        {
            finalPrompt = finalPrompt + CHARACTER_CONSISTENCY_CONSTRAINT;
        }

        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setModelName(modelConfig.getModelCode());
        imageRequest.setBusinessFuncCode(helper.businessFunctionForAgent(agentCode));
        imageRequest.setCapabilityCode(modelConfig.getCapabilityCode());
        imageRequest.setUserId(userId);
        imageRequest.setPrompt(finalPrompt);
        imageRequest.setTaskPromptDigest(buildFormImagePromptDigest(asset, form));
        if (StrUtil.isNotBlank(inheritedRefImageUrl))
        {
            imageRequest.setReferenceImageUrl(inheritedRefImageUrl);
        }
        Map<String, Object> options = new HashMap<>();
        options.put("force_single", true);
        if (StrUtil.isNotBlank(sizeSpec.aspectRatio()))
        {
            options.put("aspect_ratio", sizeSpec.aspectRatio());
        }
        imageRequest.setSize(sizeSpec.size());
        imageRequest.setOptions(options);
        imageRequest.setExpectedImageCount(1);
        imageRequest.setBizTaskId(bizTaskId);
        imageRequest.setBizTaskType(TASK_TYPE_FORM_IMAGE_BATCH);
        agentDefaultParamsApplier.applyToImage(
                new AgentModelDefault(modelCode), imageRequest, modelConfig);
        return new FormImageMediaBillingPlan(
                imageRequest, finalPrompt, inheritedRefImageUrl, estimated);
    }

    private record FormImageMediaBillingPlan(MediaImageGenerateRequest request,
            String finalPrompt, String referenceImageUrl, boolean estimated)
    {
    }

    /**
     * 角色、场景和道具形态图共用同一业务入口，但请求既可能是纯文生图，也可能继承角色基准图。
     * 必须根据本次真实素材显式选择能力，不能依赖业务池默认能力。
     */
    private String resolveFormImageCapability(String referenceImageUrl, boolean deferredReference)
    {
        return StrUtil.isNotBlank(referenceImageUrl) || deferredReference
                ? "image_to_image" : "text_to_image";
    }

    /**
     * 解析角色变体形态应继承的「初始形象」基准图 URL。
     *
     * @param asset  主表资产
     * @param form   当前要生图的形态
     * @param userId 当前用户（防越权）
     * @return 拼好完整域名的基准参考图 URL；初始形象 / 非角色类型返回 null
     */
    private String resolveInheritedBaseImageUrl(AidRolePropScene asset, AidRolePropSceneForm form, Long userId)
    {
        if (Objects.isNull(asset) || !Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType()))
        {
            return null;
        }
        //    用清洗后的 change_reason 比对，历史脏值（如「初始形象，白衣佩剑的女剑修」）也能正确识别为锚点
        if (Objects.equals(INITIAL_FORM_CHANGE_REASON, sanitizeFormLabel(StrUtil.trimToEmpty(form.getChangeReason()))))
        {
            return null;
        }

        Map<String, AidRolePropSceneForm> formsByReason = loadExistingFormsByChangeReason(asset.getId());
        AidRolePropSceneForm initialForm = formsByReason.get(INITIAL_FORM_CHANGE_REASON);
        if (Objects.isNull(initialForm))
        {
            log.info("变体形态生图缺少基准形态: assetId={}, formId={}, changeReason={}",
                    asset.getId(), form.getId(), form.getChangeReason());
            throw new ServiceException("请先生成初始形象");
        }

        String relativeUrl = resolveBaseFormImageRelativeUrl(initialForm.getId(), userId);
        if (StrUtil.isBlank(relativeUrl))
        {
            log.info("变体形态生图缺少基准图: assetId={}, formId={}, initialFormId={}",
                    asset.getId(), form.getId(), initialForm.getId());
            throw new ServiceException("请先生成初始形象");
        }

        return mediaUrlResolver.toFullUrl(relativeUrl);
    }

    /**
     * 取指定「初始形象」form 的基准图相对路径。
     *
     * @param initialFormId 初始形象形态 ID
     * @param userId        当前用户（防越权）
     * @return 基准图相对路径；无可用图返回 null
     */
    private String resolveBaseFormImageRelativeUrl(Long initialFormId, Long userId)
    {
        LambdaQueryWrapper<AidRolePropSceneFormImage> inUseQuery = Wrappers.lambdaQuery();
        inUseQuery.select(AidRolePropSceneFormImage::getId,
                        AidRolePropSceneFormImage::getImageUrl,
                        AidRolePropSceneFormImage::getSortOrder)
                .eq(AidRolePropSceneFormImage::getFormId, initialFormId)
                .eq(AidRolePropSceneFormImage::getUserId, userId)
                .eq(AidRolePropSceneFormImage::getIsUse, IS_USE_YES)
                .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_SOURCE_NO)
                .eq(AidRolePropSceneFormImage::getImageStatus, IMAGE_STATUS_COMPLETED)
                .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                .orderByAsc(AidRolePropSceneFormImage::getSortOrder)
                .last("limit 1");
        AidRolePropSceneFormImage inUse = rpsFormImageService.getOne(inUseQuery, false);
        if (Objects.nonNull(inUse) && StrUtil.isNotBlank(inUse.getImageUrl()))
        {
            return inUse.getImageUrl();
        }

        LambdaQueryWrapper<AidRolePropSceneFormImage> latestQuery = Wrappers.lambdaQuery();
        latestQuery.select(AidRolePropSceneFormImage::getId,
                        AidRolePropSceneFormImage::getImageUrl,
                        AidRolePropSceneFormImage::getCreateTime)
                .eq(AidRolePropSceneFormImage::getFormId, initialFormId)
                .eq(AidRolePropSceneFormImage::getUserId, userId)
                .eq(AidRolePropSceneFormImage::getIsSplitSource, IS_SPLIT_SOURCE_NO)
                .eq(AidRolePropSceneFormImage::getImageStatus, IMAGE_STATUS_COMPLETED)
                .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                .orderByDesc(AidRolePropSceneFormImage::getCreateTime)
                .last("limit 1");
        AidRolePropSceneFormImage latest = rpsFormImageService.getOne(latestQuery, false);
        if (Objects.nonNull(latest) && StrUtil.isNotBlank(latest.getImageUrl()))
        {
            return latest.getImageUrl();
        }
        return null;
    }

    /** 解析批量父任务的 inputSnapshot */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBatchInput(Long taskId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
        {
            throw new ServiceException("任务数据异常");
        }
        try
        {
            return OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
        }
        catch (Exception e)
        {
            throw new ServiceException("解析失败");
        }
    }

    /** 从 inputSnapshot map 中解析 id 列表 */
    @SuppressWarnings("unchecked")
    private List<Long> parseIdList(Map<String, Object> input, String key)
    {
        List<Number> rawIds = (List<Number>) input.get(key);
        if (CollectionUtil.isEmpty(rawIds))
        {
            throw new ServiceException("参数错误");
        }
        return rawIds.stream().map(Number::longValue).collect(Collectors.toList());
    }

    /** 续生时保留整批原始总数；首跑没有该字段时按本轮数量统计。 */
    private int resolveResumeOriginalTotal(Map<String, Object> input, int currentTotal)
    {
        Object raw = input.get("resumeOriginalTotalCount");
        if (Objects.isNull(raw))
        {
            return currentTotal;
        }
        try
        {
            int val = Integer.parseInt(String.valueOf(raw));
            return val > 0 ? val : currentTotal;
        }
        catch (Exception e)
        {
            return currentTotal;
        }
    }

    /** 续生时携带上一轮已成功项，最终 resultData 使用累计口径。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseResumeSeedSuccessItems(Map<String, Object> input)
    {
        Object raw = input.get("resumeSeedSuccessItems");
        if (!(raw instanceof List<?> list) || list.isEmpty())
        {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list)
        {
            if (item instanceof Map<?, ?> map)
            {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    /** 从历史 resultData 中读取已成功项。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseHistorySuccessItems(String resultData)
    {
        if (StrUtil.isBlank(resultData))
        {
            return new ArrayList<>();
        }
        try
        {
            Map<String, Object> result = OBJECT_MAPPER.readValue(resultData, Map.class);
            Object raw = result.get("successItems");
            if (!(raw instanceof List<?> list) || list.isEmpty())
            {
                return new ArrayList<>();
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object item : list)
            {
                if (item instanceof Map<?, ?> map)
                {
                    items.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
            return items;
        }
        catch (Exception e)
        {
            log.warn("解析批量任务历史成功项失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 从 successItems 中提取已成功的业务 ID，用于续生过滤。 */
    private Set<Long> collectSuccessIds(List<Map<String, Object>> successItems, String idKey)
    {
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> item : successItems)
        {
            Object raw = item.get(idKey);
            if (Objects.isNull(raw) && "sourceImageId".equals(idKey))
            {
                raw = item.get("imageId");
            }
            if (Objects.isNull(raw) && "imageId".equals(idKey))
            {
                raw = item.get("sourceImageId");
            }
            if (Objects.nonNull(raw))
            {
                try { ids.add(Long.valueOf(String.valueOf(raw))); }
                catch (NumberFormatException ignore) { /* skip */ }
            }
        }
        return ids;
    }

    /**
     * 续生前从带当前 task 归属标记的图片落库数据补齐成功项，避免父任务异常时 resultData 未写入。
     * form_generate 的 form 行没有 taskId，不能把任务前既有 form 猜成本轮成功；其文本 child 崩溃窗
     * 由历史 SUCCEEDED raw 回放后幂等 upsert 收口。
     */
    private List<Map<String, Object>> mergeDurableFormBatchSuccessItems(String taskType, Long taskId, Long userId,
                                                                        List<Long> originalIds,
                                                                        List<Map<String, Object>> historyItems,
                                                                        String itemIdKey)
    {
        List<Map<String, Object>> merged = new ArrayList<>(historyItems);
        Set<Long> successIds = collectSuccessIds(merged, itemIdKey);
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType))
        {
            appendDurableFormImageSuccessItems(taskId, userId, originalIds, merged, successIds);
        }
        else if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType))
        {
            appendDurableFormCardImageSuccessItems(taskId, userId, originalIds, merged, successIds);
        }
        return merged;
    }

    private void appendDurableFormImageSuccessItems(Long taskId, Long userId, List<Long> formIds,
                                                    List<Map<String, Object>> merged,
                                                    Set<Long> successIds)
    {
        if (CollectionUtil.isEmpty(formIds))
        {
            return;
        }
        LambdaQueryWrapper<AidRolePropSceneFormImage> query = Wrappers.lambdaQuery();
        query.select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getFormId,
                AidRolePropSceneFormImage::getImageUrl);
        query.in(AidRolePropSceneFormImage::getFormId, formIds);
        query.eq(AidRolePropSceneFormImage::getUserId, userId);
        query.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        query.eq(AidRolePropSceneFormImage::getSourceType, "ai_auto");
        query.eq(AidRolePropSceneFormImage::getImageStatus, "completed");
        query.eq(AidRolePropSceneFormImage::getBatchNo, String.valueOf(taskId));
        query.isNotNull(AidRolePropSceneFormImage::getImageUrl);
        query.ne(AidRolePropSceneFormImage::getImageUrl, "");
        query.orderByAsc(AidRolePropSceneFormImage::getId);
        List<AidRolePropSceneFormImage> images = rpsFormImageService.list(query);
        if (CollectionUtil.isEmpty(images))
        {
            return;
        }
        Map<Long, AidRolePropSceneFormImage> firstImageByFormId = new LinkedHashMap<>();
        for (AidRolePropSceneFormImage image : images)
        {
            if (Objects.nonNull(image.getFormId()) && !successIds.contains(image.getFormId()))
            {
                firstImageByFormId.putIfAbsent(image.getFormId(), image);
            }
        }
        for (Long formId : formIds)
        {
            AidRolePropSceneFormImage image = firstImageByFormId.get(formId);
            if (Objects.isNull(image) || successIds.contains(formId))
            {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("formId", formId);
            item.put("imageId", image.getId());
            item.put("imageUrl", toClientMediaUrl(image.getImageUrl()));
            merged.add(item);
            successIds.add(formId);
        }
    }

    private void appendDurableFormCardImageSuccessItems(Long taskId, Long userId, List<Long> sourceImageIds,
                                                        List<Map<String, Object>> merged,
                                                        Set<Long> successIds)
    {
        if (CollectionUtil.isEmpty(sourceImageIds))
        {
            return;
        }
        List<AidRolePropSceneFormImage> sourceImages = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getFormId,
                                AidRolePropSceneFormImage::getImageUrl)
                        .in(AidRolePropSceneFormImage::getId, sourceImageIds)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));
        if (CollectionUtil.isEmpty(sourceImages))
        {
            return;
        }

        Map<String, Long> sourceIdByUrl = new HashMap<>();
        Map<Long, List<Long>> sourceIdsByFormId = new HashMap<>();
        for (AidRolePropSceneFormImage sourceImage : sourceImages)
        {
            sourceIdsByFormId.computeIfAbsent(sourceImage.getFormId(), k -> new ArrayList<>()).add(sourceImage.getId());
            addSourceImageUrlKeys(sourceIdByUrl, sourceImage.getImageUrl(), sourceImage.getId());
        }

        List<AidRolePropSceneFormImage> cardImages = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getFormId,
                                AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getReferenceImages)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .eq(AidRolePropSceneFormImage::getSourceType, "ai_builder")
                        .eq(AidRolePropSceneFormImage::getImageStatus, "completed")
                        .eq(AidRolePropSceneFormImage::getBatchNo, String.valueOf(taskId))
                        .isNotNull(AidRolePropSceneFormImage::getImageUrl)
                        .ne(AidRolePropSceneFormImage::getImageUrl, "")
                        .orderByAsc(AidRolePropSceneFormImage::getId));
        if (CollectionUtil.isEmpty(cardImages))
        {
            return;
        }

        for (AidRolePropSceneFormImage cardImage : cardImages)
        {
            Long sourceImageId = resolveCardSourceImageId(cardImage, sourceIdByUrl, sourceIdsByFormId);
            if (Objects.isNull(sourceImageId) || successIds.contains(sourceImageId))
            {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceImageId", sourceImageId);
            item.put("imageId", sourceImageId);
            item.put("cardImageId", cardImage.getId());
            item.put("cardImageUrl", toClientMediaUrl(cardImage.getImageUrl()));
            item.put("formId", cardImage.getFormId());
            merged.add(item);
            successIds.add(sourceImageId);
        }
    }

    private void addSourceImageUrlKeys(Map<String, Long> sourceIdByUrl, String imageUrl, Long sourceImageId)
    {
        if (StrUtil.isBlank(imageUrl) || Objects.isNull(sourceImageId))
        {
            return;
        }
        String raw = normalizeMediaUrlKey(imageUrl);
        if (StrUtil.isNotBlank(raw))
        {
            sourceIdByUrl.putIfAbsent(raw, sourceImageId);
        }
        String full = normalizeMediaUrlKey(mediaUrlResolver.toFullUrl(imageUrl));
        if (StrUtil.isNotBlank(full))
        {
            sourceIdByUrl.putIfAbsent(full, sourceImageId);
        }
    }

    private Long resolveCardSourceImageId(AidRolePropSceneFormImage cardImage,
                                          Map<String, Long> sourceIdByUrl,
                                          Map<Long, List<Long>> sourceIdsByFormId)
    {
        List<String> referenceImages = parseReferenceImages(cardImage.getReferenceImages());
        for (String url : referenceImages)
        {
            Long sourceImageId = sourceIdByUrl.get(normalizeMediaUrlKey(url));
            if (Objects.nonNull(sourceImageId))
            {
                return sourceImageId;
            }
        }
        List<Long> sameFormSources = sourceIdsByFormId.get(cardImage.getFormId());
        if (CollectionUtil.isNotEmpty(sameFormSources) && sameFormSources.size() == 1)
        {
            return sameFormSources.get(0);
        }
        return null;
    }

    private List<String> parseReferenceImages(String referenceImagesJson)
    {
        if (StrUtil.isBlank(referenceImagesJson))
        {
            return java.util.Collections.emptyList();
        }
        try
        {
            JsonNode node = OBJECT_MAPPER.readTree(referenceImagesJson);
            if (!node.isArray())
            {
                return java.util.Collections.emptyList();
            }
            List<String> urls = new ArrayList<>();
            for (JsonNode item : node)
            {
                if (item.isTextual() && StrUtil.isNotBlank(item.asText()))
                {
                    urls.add(item.asText());
                }
            }
            return urls;
        }
        catch (Exception e)
        {
            log.warn("解析设定卡参考图失败: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private String normalizeMediaUrlKey(String url)
    {
        return StrUtil.isBlank(url) ? "" : url.trim();
    }

    private String toClientMediaUrl(String url)
    {
        return StrUtil.isBlank(url) ? url : mediaUrlResolver.toFullUrl(url);
    }

    /** 构建批量任务的 resultData JSON */
    private String buildBatchResultJson(int total, int successCount, int failCount,
                                        List<Map<String, Object>> successItems,
                                        List<Map<String, Object>> failedItems)
    {
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("totalCount", total);
        resultData.put("successCount", successCount);
        resultData.put("failCount", failCount);
        resultData.put("successItems", successItems);
        resultData.put("failedItems", failedItems);
        try
        {
            return OBJECT_MAPPER.writeValueAsString(resultData);
        }
        catch (Exception e)
        {
            return "{}";
        }
    }

    /**
     * 释放批量父任务的所有项目锁（供 Consumer 取消 / 异常补释放使用）。
     * 从 inputSnapshot 解析 id 列表，逐个释放锁。
     */
    @Override
    @SuppressWarnings("unchecked")
    public void releaseBatchFormLocks(Long taskId, String taskType, String dispatchToken)
    {
        try
        {
            if (StrUtil.isBlank(dispatchToken))
            {
                log.warn("批量锁释放缺少派发周期令牌，已拒绝: taskId={}, taskType={}", taskId, taskType);
                return;
            }
            AidExtractTask task = extractTaskService.getOne(
                    Wrappers.<AidExtractTask>lambdaQuery()
                            .select(AidExtractTask::getId, AidExtractTask::getProjectId,
                                    AidExtractTask::getEpisodeId, AidExtractTask::getBillingTraceId,
                                    AidExtractTask::getStatus, AidExtractTask::getInputSnapshot)
                            .eq(AidExtractTask::getId, taskId)
                            .last("LIMIT 1"), false);
            if (task == null)
            {
                return;
            }
            if (!Objects.equals(dispatchToken, task.getBillingTraceId()))
            {
                log.info("批量锁释放跳过，任务周期已变化: taskId={}", taskId);
                return;
            }
            Map<String, Object> input = StrUtil.isBlank(task.getInputSnapshot())
                    ? new LinkedHashMap<>()
                    : OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
            batchTaskSlotService.releaseForTask(task);
            if (TASK_TYPE_FORM_GENERATE.equals(taskType))
            {
                Long assetId = input.get("assetId") == null
                        ? null : Long.valueOf(String.valueOf(input.get("assetId")));
                if (assetId != null)
                {
                    releaseBatchItemLock(taskId, buildFormGenerateLockKey(assetId), resolveItemLockToken(input));
                }
            }
            else if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType))
            {
                List<Long> ids = parseIdList(input, "assetIds");
                String itemLockToken = resolveItemLockToken(input);
                for (Long id : ids)
                {
                    releaseBatchItemLock(taskId, buildFormGenerateLockKey(id), itemLockToken);
                }
                log.info("批量形态生成锁全部释放: taskId={}, count={}", taskId, ids.size());
            }
            else if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType))
            {
                List<Long> ids = parseIdList(input, "formIds");
                String itemLockToken = resolveItemLockToken(input);
                for (Long id : ids)
                {
                    releaseBatchItemLock(taskId, buildFormImageLockKey(id), itemLockToken);
                }
                log.info("批量形态图锁全部释放: taskId={}, count={}", taskId, ids.size());
            }
            else if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType))
            {
                List<Long> ids = parseIdList(input, "imageIds");
                String itemLockToken = resolveItemLockToken(input);
                for (Long id : ids)
                {
                    releaseBatchItemLock(taskId, buildFormCardImageLockKey(id), itemLockToken);
                }
                log.info("批量设定卡锁全部释放: taskId={}, count={}", taskId, ids.size());
            }
            else if (TASK_TYPE_STORYBOARD_SCRIPT_BATCH.equals(taskType))
            {
                // 分镜脚本批量任务使用项目级锁，直接释放
                Long projectId = input.get("projectId") != null
                        ? Long.valueOf(String.valueOf(input.get("projectId"))) : null;
                Long episodeId = input.get("episodeId") != null
                        ? Long.valueOf(String.valueOf(input.get("episodeId"))) : null;
                if (projectId != null && episodeId != null)
                {
                    String lockKey = "storyboard:script:lock:" + projectId + ":" + episodeId;
                    releaseProjectBatchLock(taskId, lockKey, input);
                }
            }
            else if (TASK_TYPE_STORYBOARD_IMAGE_PROMPT_BATCH.equals(taskType))
            {
                // 分镜图脚本批量任务使用项目级锁（key=storyboard:image_prompt:lock:{projectId}:{episodeId}）
                Long projectId = input.get("projectId") != null
                        ? Long.valueOf(String.valueOf(input.get("projectId"))) : null;
                Long episodeId = input.get("episodeId") != null
                        ? Long.valueOf(String.valueOf(input.get("episodeId"))) : null;
                if (projectId != null && episodeId != null)
                {
                    String lockKey = "storyboard:image_prompt:lock:" + projectId + ":" + episodeId;
                    releaseProjectBatchLock(taskId, lockKey, input);
                }
            }
            else if (TASK_TYPE_STORYBOARD_VIDEO_PROMPT_BATCH.equals(taskType))
            {
                // 视频提示词批量任务使用项目级锁（key=storyboard:video_prompt:lock:{projectId}:{episodeId}）
                Long projectId = input.get("projectId") != null
                        ? Long.valueOf(String.valueOf(input.get("projectId"))) : null;
                Long episodeId = input.get("episodeId") != null
                        ? Long.valueOf(String.valueOf(input.get("episodeId"))) : null;
                if (projectId != null && episodeId != null)
                {
                    String lockKey = "storyboard:video_prompt:lock:" + projectId + ":" + episodeId;
                    releaseProjectBatchLock(taskId, lockKey, input);
                }
            }
        }
        catch (Exception e)
        {
            log.warn("释放批量锁异常: taskId={}, taskType={}", taskId, taskType, e);
        }
    }

    /** 项目级锁必须按持锁 token 做 compare-and-delete，缺少 token 时宁可等待 TTL。 */
    private void releaseProjectBatchLock(Long taskId, String lockKey, Map<String, Object> input)
    {
        Object tokenValue = input.get("projectLockToken");
        String projectLockToken = tokenValue == null ? null : String.valueOf(tokenValue);
        if (StrUtil.isBlank(projectLockToken))
        {
            log.warn("项目锁缺少持锁令牌，跳过释放: taskId={}, lockKey={}", taskId, lockKey);
            return;
        }
        casDeleteExtractLockIfMatch(lockKey, projectLockToken);
        log.info("项目批量锁已按令牌释放: taskId={}, lockKey={}", taskId, lockKey);
    }

    private record FormBatchResumePlan(AidExtractTask task, Map<String, Object> input,
            String idKey, String itemIdKey, int originalTotalCount,
            List<Map<String, Object>> seedSuccessItems, List<Long> remainingIds,
            ExtractBillingEstimate parentBilling,
            List<FormGenerateResumeBillingItem> formGenerateBillingItems)
    {
    }

    private record FormGenerateResumeBillingItem(AiModelConfigVo modelConfig,
            BillingCalcResult result)
    {
    }

    @Override
    public BillingQuoteVO quoteResumeFormBatchTask(Long taskId, Long userId)
    {
        FormBatchResumePlan plan = prepareFormBatchResumePlan(taskId, userId);
        String taskType = plan.task().getTaskType();
        Map<String, Object> input = plan.input();
        String agentCode = Objects.toString(input.get("agentCode"), null);
        String modelCode = StrUtil.blankToDefault(
                Objects.toString(input.get("modelCode"), null), plan.task().getModelCode());
        String resolution = Objects.toString(input.get("resolution"), null);
        String aspectRatio = Objects.toString(input.get("aspectRatio"), null);
        List<BillingQuoteVO> items = new ArrayList<>();
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType))
        {
            for (FormGenerateResumeBillingItem item : plan.formGenerateBillingItems())
            {
                items.add(billingQuoteAssembler.single(
                        "TASK_RESUME", item.modelConfig(), item.result(), 1));
            }
        }
        else if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType))
        {
            FormImageBatchBillingPlan billingPlan = prepareFormImageBatchBillingPlan(
                    plan.remainingIds(), userId, agentCode, modelCode, resolution, aspectRatio);
            Map<Long, FormImageValidated> byId = billingPlan.validatedList().stream()
                    .collect(Collectors.toMap(item -> item.formId, item -> item));
            for (Long formId : billingPlan.formIds())
            {
                FormImageValidated validated = byId.get(formId);
                boolean deferred = billingPlan.initialAssetIds().contains(validated.asset.getId());
                FormImageMediaBillingPlan mediaPlan = buildFormImageMediaBillingPlan(null,
                        validated.form, validated.asset, validated.project, agentCode, billingPlan.modelCode(),
                        billingPlan.resolution(), billingPlan.aspectRatio(), userId, deferred);
                items.add(mediaPlan.estimated()
                        ? mediaBillingQuoteService.quotePlannedImage(
                                "TASK_RESUME", mediaPlan.request(), 1)
                        : mediaBillingQuoteService.quoteImage(
                                "TASK_RESUME", mediaPlan.request(), 1));
            }
        }
        else
        {
            for (Long imageId : plan.remainingIds())
            {
                CardImageValidated validated = validateSingleCardImage(imageId, userId);
                CardImageMediaBillingPlan mediaPlan = buildCardImageMediaBillingPlan(null,
                        validated, agentCode, modelCode, resolution, aspectRatio, userId);
                items.add(mediaBillingQuoteService.quoteImage(
                        "TASK_RESUME", mediaPlan.request(), 1));
            }
        }
        return billingQuoteAssembler.aggregate("TASK_RESUME", items);
    }

    private FormBatchResumePlan prepareFormBatchResumePlan(Long taskId, Long userId)
    {
        if (taskId == null || taskId <= 0)
        {
            throw new ServiceException("参数错误");
        }
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (task == null || !DEL_FLAG_NORMAL.equals(task.getDelFlag())
                || !Objects.equals(userId, task.getUserId()))
        {
            throw new ServiceException("任务不存在");
        }
        String taskType = task.getTaskType();
        if (!isFormBatchTaskType(taskType))
        {
            throw new ServiceException("类型不支持");
        }
        if (!isFormBatchResumeStatus(task.getStatus()))
        {
            throw new ServiceException("状态不支持");
        }
        Map<String, Object> input = parseBatchInput(taskId);
        String idKey = resolveFormBatchIdKey(taskType);
        String itemIdKey = resolveFormBatchItemIdKey(taskType);
        List<Long> originalIds = parseIdList(input, idKey);
        int originalTotalCount = resolveResumeOriginalTotal(input, originalIds.size());
        List<Map<String, Object>> seedSuccessItems = parseHistorySuccessItems(task.getResultData());
        seedSuccessItems = mergeDurableFormBatchSuccessItems(taskType, taskId, userId,
                originalIds, seedSuccessItems, itemIdKey);
        Set<Long> successIds = collectSuccessIds(seedSuccessItems, itemIdKey);
        List<Long> remainingIds = originalIds.stream()
                .filter(id -> !successIds.contains(id)).toList();
        if (remainingIds.isEmpty())
        {
            throw new ServiceException("无可续生项");
        }
        List<FormGenerateResumeBillingItem> formGenerateBillingItems = List.of();
        ExtractBillingEstimate parentBilling = new ExtractBillingEstimate(BigDecimal.ZERO, null);
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType))
        {
            FormGenerateResumeBillingPlan billingPlan = prepareFormGenerateResumeBilling(
                    task, userId, remainingIds,
                    extractBillingService.findLatestExtractMediaTaskId(taskId));
            formGenerateBillingItems = billingPlan.items();
            parentBilling = billingPlan.estimate();
        }
        String agentCode = Objects.toString(input.get("agentCode"), null);
        String modelCode = StrUtil.blankToDefault(
                Objects.toString(input.get("modelCode"), null), task.getModelCode());
        String resolution = Objects.toString(input.get("resolution"), null);
        String aspectRatio = Objects.toString(input.get("aspectRatio"), null);
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType))
        {
            FormImageBatchBillingPlan plan = prepareFormImageBatchBillingPlan(
                    remainingIds, userId, agentCode, modelCode, resolution, aspectRatio);
            Map<Long, FormImageValidated> byId = plan.validatedList().stream()
                    .collect(Collectors.toMap(item -> item.formId, item -> item));
            for (Long formId : plan.formIds())
            {
                FormImageValidated validated = byId.get(formId);
                buildFormImageMediaBillingPlan(null, validated.form, validated.asset,
                        validated.project, agentCode, plan.modelCode(), plan.resolution(), plan.aspectRatio(),
                        userId, plan.initialAssetIds().contains(validated.asset.getId()));
            }
        }
        else if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType))
        {
            for (Long imageId : remainingIds)
            {
                CardImageValidated validated = validateSingleCardImage(imageId, userId);
                buildCardImageMediaBillingPlan(
                        null, validated, agentCode, modelCode, resolution, aspectRatio, userId);
            }
        }
        return new FormBatchResumePlan(task,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input)), idKey, itemIdKey,
                originalTotalCount, List.copyOf(seedSuccessItems),
                List.copyOf(remainingIds), parentBilling,
                List.copyOf(formGenerateBillingItems));
    }

    @Override
    public AssetExtractTaskVO resumeFormBatchTask(Long taskId, Long userId)
    {
        FormBatchResumePlan preparedResume = prepareFormBatchResumePlan(taskId, userId);
        AidExtractTask task = preparedResume.task();
        String taskType = task.getTaskType();
        if (!isFormBatchTaskType(taskType))
        {
            throw new ServiceException("类型不支持");
        }
        if (!isFormBatchResumeStatus(task.getStatus()))
        {
            throw new ServiceException("状态不支持");
        }

        List<String> acquiredLocks = new ArrayList<>();
        String resumeItemLockToken = IdUtil.fastSimpleUUID();
        String originalStatus = task.getStatus();
        try
        {
            Map<String, Object> input = preparedResume.input();
            String idKey = preparedResume.idKey();
            int resumeOriginalTotalCount = preparedResume.originalTotalCount();
            List<Map<String, Object>> seedSuccessItems = preparedResume.seedSuccessItems();
            List<Long> remainingIds = preparedResume.remainingIds();

            for (Long id : remainingIds)
            {
                String lockKey = buildFormBatchItemLockKey(taskType, id);
                Boolean locked = redisCache.redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, resumeItemLockToken,
                                resolveFormBatchLockTtlSeconds(taskType), TimeUnit.SECONDS);
                if (locked == null || !locked)
                {
                    throw new ServiceException("任务处理中");
                }
                acquiredLocks.add(lockKey);
            }

            Map<String, Object> resumeInput = new LinkedHashMap<>(input);
            resumeInput.put(idKey, remainingIds);
            resumeInput.put("resumeOriginalTotalCount", resumeOriginalTotalCount);
            resumeInput.put("resumeSeedSuccessItems", seedSuccessItems);
            resumeInput.put("itemLockToken", resumeItemLockToken);
            String resumeInputJson;
            try
            {
                resumeInputJson = OBJECT_MAPPER.writeValueAsString(resumeInput);
            }
            catch (Exception e)
            {
                throw new ServiceException("提交失败，请重试");
            }

            String dispatchMode = dualModeTaskDispatcher.resolveDispatchMode();
            try
            {
                taskQueueService.executeWithTaskDispatchLock(taskId, () -> {
                    taskQueueService.clearCancelRequested(taskId, task.getBillingTraceId());
                    clearCancelFlag(taskId, task.getBillingTraceId());

                    ResumeBillingContext billingContext = null;
                    try
                    {
                        ExtractBillingEstimate billingEstimate = preparedResume.parentBilling();
                        billingContext = extractBillingService.rearmBillingForResume(
                                taskId, userId, billingEstimate.amount(), billingEstimate.snapshotJson(),
                                originalStatus, new ResumeTaskMutation(false, null,
                                        true, resumeInputJson, true, resumeOriginalTotalCount), dispatchMode);
                        taskQueueService.assertTaskDispatchLockHeld(taskId);
                        billingContext = extractBillingService.markResumeBillingDispatchIntent(
                                taskId, billingContext);
                        taskQueueService.assertTaskDispatchLockHeld(taskId);
                        boolean enqueued = dualModeTaskDispatcher.dispatchNow(taskId,
                                task.getProjectId(), task.getEpisodeId(), userId, task.getModelCode(), taskType,
                                buildFormBatchLocalJob(taskId, userId, resolveFormBatchLocalSpec(taskType),
                                        buildFormBatchResumeBody(taskId, userId, taskType)),
                                dispatchMode, billingContext.resumeTraceId());
                        if (!enqueued)
                        {
                            throw new ServiceException("任务提交失败");
                        }
                        return Boolean.TRUE;
                    }
                    catch (RuntimeException submitEx)
                    {
                        if (billingContext != null)
                        {
                            rollbackAssetExtractResumeSubmission(taskId, userId, billingContext);
                        }
                        throw submitEx;
                    }
                });
            }
            catch (RuntimeException submitEx)
            {
                log.error("素材批量任务续生提交失败: taskId={}, taskType={}", taskId, taskType, submitEx);
                throw submitEx instanceof ServiceException
                        ? submitEx : new ServiceException("提交失败，请重试");
            }

            log.info("素材批量任务续生提交: taskId={}, taskType={}, remaining={}, seedSuccess={}",
                    taskId, taskType, remainingIds.size(), seedSuccessItems.size());
            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(TASK_STATUS_PENDING)
                    .totalCount(resumeOriginalTotalCount)
                    .build();
        }
        catch (RuntimeException e)
        {
            releaseLockKeys(acquiredLocks, resumeItemLockToken);
            throw e;
        }
    }

    private boolean isFormBatchTaskType(String taskType)
    {
        return TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)
                || TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)
                || TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType);
    }

    private boolean isFormBatchResumeStatus(String status)
    {
        return TASK_STATUS_CANCELLED.equals(status)
                || TASK_STATUS_PARTIAL_FAILED.equals(status)
                || TASK_STATUS_FAILED.equals(status);
    }

    private String resolveFormBatchIdKey(String taskType)
    {
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)) { return "assetIds"; }
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)) { return "formIds"; }
        if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType)) { return "imageIds"; }
        throw new ServiceException("类型不支持");
    }

    private String resolveFormBatchItemIdKey(String taskType)
    {
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)) { return "assetId"; }
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)) { return "formId"; }
        if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType)) { return "sourceImageId"; }
        throw new ServiceException("类型不支持");
    }

    private String buildFormBatchItemLockKey(String taskType, Long id)
    {
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)) { return buildFormGenerateLockKey(id); }
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)) { return buildFormImageLockKey(id); }
        if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType)) { return buildFormCardImageLockKey(id); }
        throw new ServiceException("类型不支持");
    }

    private long resolveFormBatchLockTtlSeconds(String taskType)
    {
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)) { return 900L; }
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)) { return 300L; }
        if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType)) { return 1800L; }
        return 300L;
    }

    private com.aid.rps.queue.BatchTaskLocalOrchestrator.Spec resolveFormBatchLocalSpec(String taskType)
    {
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType)) { return LOCAL_SPEC_FORM_GENERATE; }
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)) { return LOCAL_SPEC_FORM_IMAGE; }
        if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType)) { return LOCAL_SPEC_FORM_CARD; }
        throw new ServiceException("类型不支持");
    }

    private java.util.function.Supplier<String> buildFormBatchResumeBody(Long taskId, Long userId, String taskType)
    {
        if (TASK_TYPE_FORM_GENERATE_BATCH.equals(taskType))
        {
            return () -> doFormGenerateBatch(
                    taskId, userId, taskQueueService.currentLocalDispatchToken(taskId));
        }
        if (TASK_TYPE_FORM_IMAGE_BATCH.equals(taskType)) {
            return () -> doFormImageBatch(taskId, userId,
                    taskQueueService.currentLocalDispatchToken(taskId));
        }
        if (TASK_TYPE_FORM_CARD_IMAGE_BATCH.equals(taskType)) {
            return () -> doFormCardImageBatch(taskId, userId,
                    taskQueueService.currentLocalDispatchToken(taskId));
        }
        throw new ServiceException("类型不支持");
    }

    private String resolveItemLockToken(Map<String, Object> input)
    {
        Object token = input == null ? null : input.get("itemLockToken");
        return token == null ? null : String.valueOf(token);
    }

    @SuppressWarnings("unchecked")
    private void releaseTaskOwnedItemLock(AidExtractTask task, String lockKey)
    {
        String itemLockToken = null;
        try
        {
            if (task != null && StrUtil.isNotBlank(task.getInputSnapshot()))
            {
                Map<String, Object> input = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
                itemLockToken = resolveItemLockToken(input);
            }
        }
        catch (Exception parseEx)
        {
            log.warn("解析任务持锁令牌失败，等待TTL自然过期: taskId={}, lockKey={}",
                    task == null ? null : task.getId(), lockKey, parseEx);
            return;
        }
        releaseBatchItemLock(task == null ? null : task.getId(), lockKey, itemLockToken);
    }

    private void releaseBatchItemLock(Long taskId, String lockKey, String itemLockToken)
    {
        if (StrUtil.isBlank(itemLockToken))
        {
            log.warn("批量项锁缺少持锁令牌，等待TTL自然过期: taskId={}, lockKey={}", taskId, lockKey);
            return;
        }
        casDeleteExtractLockIfMatch(lockKey, itemLockToken);
    }

    private void releaseLockKeys(List<String> lockKeys, String itemLockToken)
    {
        for (String key : lockKeys)
        {
            try { casDeleteExtractLockIfMatch(key, itemLockToken); }
            catch (Exception releaseEx)
            {
                log.warn("续生回滚项锁释放异常: lockKey={}", key, releaseEx);
            }
        }
    }

    private record FormGenerateResumeBillingPlan(ExtractBillingEstimate estimate,
            List<FormGenerateResumeBillingItem> items)
    {
    }

    private FormGenerateResumeBillingPlan prepareFormGenerateResumeBilling(
            AidExtractTask task, Long userId, List<Long> remainingAssetIds,
            long usageStartMediaTaskId)
    {
        BigDecimal totalFrozen = BigDecimal.ZERO;
        List<Map<String, Object>> itemSnapshots = new ArrayList<>();
        List<FormGenerateResumeBillingItem> items = new ArrayList<>();
        String modelCode = task.getModelCode();
        AiModelConfigVo modelConfig = aiModelConfigService.selectByModelCode(modelCode);
        if (modelConfig == null)
        {
            throw new ServiceException("模型不存在");
        }
        for (Long assetId : remainingAssetIds)
        {
            FormGenerateValidated validated = validateSingleFormGenerate(assetId, userId);
            BillingCalcResult itemCalc = estimateFormGenerateCost(task.getId(), validated.asset, modelCode);
            items.add(new FormGenerateResumeBillingItem(modelConfig, itemCalc));
            if (Objects.nonNull(itemCalc) && Objects.nonNull(itemCalc.getAmount()))
            {
                totalFrozen = totalFrozen.add(itemCalc.getAmount());
            }
            if (Objects.nonNull(itemCalc) && Objects.nonNull(itemCalc.getSnapshot()))
            {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("assetId", assetId);
                snap.put("snapshot", itemCalc.getSnapshot());
                itemSnapshots.add(snap);
            }
        }
        Map<String, Object> batchSnap = new LinkedHashMap<>();
        batchSnap.put("batchType", TASK_TYPE_FORM_GENERATE_BATCH);
        batchSnap.put("items", itemSnapshots);
        batchSnap.put("usageStartMediaTaskId", Math.max(0L, usageStartMediaTaskId));
        return new FormGenerateResumeBillingPlan(
                new ExtractBillingEstimate(totalFrozen, JSONUtil.toJsonStr(batchSnap)),
                List.copyOf(items));
    }

    /**
     * 单个资产形态生成的前置校验结果（校验通过后的数据快照）。
     * 仅用于 generateForm / batchGenerateForm 内部传递。
     */
    private static class FormGenerateValidated
    {
        Long assetId;
        AidRolePropScene asset;
        String modelCode;
    }

    /**
     * 批量形态生成时按 agentCode 断言业务分类。
     *
     * @return 断言通过的智能体（非空）
     */
    private AidAgent assertAgentForFormBatch(String agentCode, List<FormGenerateValidated> validatedList)
    {
        // 提取批内 assetType（必须一致）
        String batchType = null;
        for (FormGenerateValidated v : validatedList)
        {
            String type = v.asset.getAssetType();
            if (StrUtil.isBlank(type))
            {
                log.error("批量形态生成失败：资产类型为空, assetId={}", v.assetId);
                throw new ServiceException("资产类型不能为空");
            }
            if (batchType == null)
            {
                batchType = type;
            }
            else if (!Objects.equals(batchType, type))
            {
                log.error("批量形态生成失败：批内资产类型不一致, expected={}, got={}, assetId={}",
                        batchType, type, v.assetId);
                throw new ServiceException("类型不一致");
            }
        }
        // character / scene / prop 形态生成均需要智能体（调 LLM）
        if (StrUtil.isBlank(agentCode))
        {
            log.error("form/generate 形态生成参数校验失败：agentCode 为空, assetType={}", batchType);
            throw new ServiceException("智能体编码不能为空");
        }
        // 断言 agentCode 业务分类：character → main_character_form，scene → main_scene_form，prop → main_prop_form
        String expectedBizCategory;
        if (Objects.equals(ASSET_TYPE_CHARACTER, batchType))
        {
            expectedBizCategory = BIZ_CATEGORY_MAIN_CHARACTER_FORM;
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, batchType))
        {
            expectedBizCategory = "main_scene_form";
        }
        else if (Objects.equals(ASSET_TYPE_PROP, batchType))
        {
            expectedBizCategory = "main_prop_form";
        }
        else
        {
            log.error("批量形态生成失败：未知资产类型 {}", batchType);
            throw new ServiceException("类型不支持");
        }
        return aidAgentService.getByAgentCodeAndAssertBizCategory(agentCode, expectedBizCategory);
    }

    /**
     * 校验单个 assetId：资产存在、归属、项目、风格。
     * modelCode 解析挪到外层 batch 流程，依赖 agent 完成；本方法只做归属和风格校验。
     * 校验不通过直接抛异常（短文案）。
     */
    private FormGenerateValidated validateSingleFormGenerate(Long assetId, Long userId)
    {
        AidRolePropScene asset = rpsService.getById(assetId);
        if (Objects.isNull(asset) || !Objects.equals(DEL_FLAG_NORMAL, asset.getDelFlag()))
        {
            log.info("形态生成失败，资产不存在: assetId={}", assetId);
            throw new ServiceException("资产不存在");
        }
        if (!Objects.equals(userId, asset.getUserId()))
        {
            log.info("形态生成失败，资产不属于当前用户: assetId={}, userId={}", assetId, userId);
            throw new ServiceException("资产不存在");
        }

        requireProjectStyle(validateProject(asset.getProjectId(), userId));

        // 打包校验结果（modelCode 由外层批处理统一解析后回填）
        FormGenerateValidated v = new FormGenerateValidated();
        v.assetId = assetId;
        v.asset = asset;
        v.modelCode = null;
        return v;
    }

    /**
     * 构建 form_generate 防重锁 key。
     */
    private String buildFormGenerateLockKey(Long assetId)
    {
        return FORM_LOCK_PREFIX + TASK_TYPE_FORM_GENERATE + ":" + assetId;
    }

    /**
     * 为单个资产创建形态生成任务 + 计费预冻结 + 异步执行。
     * 锁已由调用方获取，异步 finally 释放。任务创建失败由调用方负责释放锁。
     */
    private AssetExtractTaskVO submitSingleFormGenerateTask(FormGenerateValidated v, Long userId,
                                                             String formLockKey, String itemLockToken)
    {
        Long assetId = v.assetId;
        AidRolePropScene asset = v.asset;
        String modelCode = v.modelCode;

        AidExtractTask task = null;
        boolean billingPrepared = false;
        try
        {
            // 创建任务记录
            task = new AidExtractTask();
            task.setProjectId(asset.getProjectId());
            task.setEpisodeId(asset.getEpisodeId());
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_FORM_GENERATE);
            task.setModelCode(modelCode);
            try
            {
                task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(
                        Map.of("assetId", assetId, "assetType", asset.getAssetType(),
                                "itemLockToken", itemLockToken)));
            }
            catch (Exception e)
            {
                task.setInputSnapshot("{\"assetId\":" + assetId
                        + ",\"itemLockToken\":\"" + itemLockToken + "\"}");
            }
            task.setStatus(TASK_STATUS_PENDING);
            task.setTotalCount(0);
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            extractTaskService.save(task);

            BillingCalcResult calcResult = estimateFormGenerateCost(task.getId(), asset, modelCode);
            String snapshotJson = null;
            if (calcResult.getSnapshot() != null)
            {
                snapshotJson = JSONUtil.toJsonStr(calcResult.getSnapshot());
            }
            extractBillingService.prepareBilling(task.getId(), userId, calcResult.getAmount(), snapshotJson);
            billingPrepared = true;
            log.info("形态生成任务预冻结成功: taskId={}, userId={}, assetId={}, amount={}",
                    task.getId(), userId, assetId, calcResult.getAmount());

            // 异步执行
            Long taskId = task.getId();
            Runnable formGenJob = () ->
            {
                String dispatchToken = taskQueueService.currentLocalDispatchToken(taskId);
                if (StrUtil.isBlank(dispatchToken))
                {
                    log.error("形态生成LOCAL任务缺少派发周期令牌，拒绝执行: taskId={}", taskId);
                    return;
                }
                try
                {
                    String billingTraceId = requireCurrentBillingTrace(taskId, dispatchToken);
                    if (!Objects.equals(dispatchToken, billingTraceId)
                            || !updateTaskStatus(taskId, TASK_STATUS_PROCESSING, null,
                            TASK_STATUS_PENDING, dispatchToken))
                    {
                        log.warn("形态生成任务周期已变化, 跳过: taskId={}", taskId);
                        return;
                    }
                    // 登记执行租约（重启自愈据租约判活）
                    markTaskProcessing(taskId);
                    sseManager.sendStepProgress(taskId, "form_generate", 10,
                            "form_gen", "正在生成形态描述...", 1, 1);

                    // 实际执行：重新加载资产并校验状态一致性
                    // 防止提交时校验通过但执行前资产被软删除/归属变更
                    AidRolePropScene reloadedAsset = rpsService.getById(assetId);
                    if (Objects.isNull(reloadedAsset) || !Objects.equals(DEL_FLAG_NORMAL, reloadedAsset.getDelFlag()))
                    {
                        log.info("形态生成失败，资产已不存在: assetId={}", assetId);
                        throw new ServiceException("资产不存在");
                    }
                    if (!Objects.equals(userId, reloadedAsset.getUserId()))
                    {
                        log.info("形态生成失败，资产归属已变更: assetId={}, expectedUser={}, actualUser={}",
                                assetId, userId, reloadedAsset.getUserId());
                        throw new ServiceException("资产不存在");
                    }
                    String assetType = reloadedAsset.getAssetType();
                    String assetName = reloadedAsset.getName();
                    List<AidRolePropSceneForm> newForms;
                    if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
                    {
                        newForms = generateCharacterForms(
                                reloadedAsset, modelCode, userId, taskId, dispatchToken);
                    }
                    else if (Objects.equals(ASSET_TYPE_SCENE, assetType))
                    {
                        AidRolePropSceneForm form = generateSceneForm(
                                reloadedAsset, modelCode, userId, taskId, dispatchToken);
                        newForms = List.of(form);
                    }
                    else if (Objects.equals(ASSET_TYPE_PROP, assetType))
                    {
                        AidRolePropSceneForm form = generatePropForm(
                                reloadedAsset, modelCode, userId, taskId, dispatchToken);
                        newForms = List.of(form);
                    }
                    else
                    {
                        log.info("形态生成失败，不支持的资产类型: assetType={}", assetType);
                        throw new ServiceException("类型不支持");
                    }

                    RpsAssetVO resultVo = buildAssetVO(reloadedAsset, newForms);
                    String resultJson = OBJECT_MAPPER.writeValueAsString(resultVo);
                    if (!updateTaskSuccess(taskId, dispatchToken, newForms.size(), resultJson))
                    {
                        log.info("形态生成成功收口跳过，周期已变化: taskId={}", taskId);
                        return;
                    }

                    try
                    {
                        boolean billingClosed = settleFormGenerateUsage(
                                taskId, userId, dispatchToken);
                        if (billingClosed)
                        {
                            log.info("形态生成真实用量结算成功: taskId={}, userId={}, assetId={}",
                                    taskId, userId, assetId);
                        }
                        else
                        {
                            log.warn("形态生成用量尚未收敛，保留冻结等待补偿: taskId={}", taskId);
                        }
                    }
                    catch (Exception billingEx)
                    {
                        log.error("形态生成任务结算失败（不影响业务结果）, taskId={}", taskId, billingEx);
                    }

                    // 推送形态生成结果描述
                    String formDesc = newForms.stream()
                            .map(f -> StrUtil.isNotBlank(f.getChangeReason())
                                    ? f.getChangeReason() : "形态")
                            .collect(Collectors.joining("、"));
                    sseManager.sendStepProgress(taskId, "form_generate", 95,
                            "form_gen_done", assetName + " 生成" + newForms.size() + "个形态: " + formDesc, 1, 1);

                    sseManager.sendComplete(taskId, resultVo);

                    log.info("形态生成任务完成: taskId={}, assetId={}, assetType={}", taskId, assetId, assetType);
                }
                catch (TextTaskExecutionRejectedException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    log.error("形态生成任务失败: taskId={}, assetId={}", taskId, assetId, e);
                    com.aid.common.error.TaskErrorResult formErrorResult = com.aid.common.error.ErrorNormalizer.normalize(e);
                    if (!updateTaskFailed(taskId, dispatchToken, formErrorResult))
                    {
                        log.info("形态生成失败收口跳过，周期已变化: taskId={}", taskId);
                        return;
                    }
                    try
                    {
                        boolean billingClosed = extractBillingService.settleOrRefundAfterExecutionFailure(
                                taskId, userId, dispatchToken);
                        if (!billingClosed)
                        {
                            log.warn("形态生成失败计费尚未收敛，保留冻结等待补偿: taskId={}", taskId);
                        }
                    }
                    catch (Exception billingEx)
                    {
                        log.error("形态生成失败计费收口异常，保留冻结等待补偿: taskId={}",
                                taskId, billingEx);
                    }
                    sseManager.sendError(taskId, formErrorResult);
                }
                finally
                {
                    casDeleteExtractLockIfMatch(formLockKey, itemLockToken);
                    // 释放多维并发名额 + 执行租约（幂等）
                    try { releaseTaskSlots(taskId, dispatchToken); } catch (Exception ignore) { }
                }
            };
            // 入队 + 多维并发调度（LOCAL 派发）
            boolean formGenEnqueued = taskQueueService.submitLocalTask(taskId, asset.getProjectId(),
                    asset.getEpisodeId(), userId, modelCode, TASK_TYPE_FORM_GENERATE, formGenJob);
            if (!formGenEnqueued)
            {
                log.error("形态生成任务入队失败: taskId={}, assetId={}", taskId, assetId);
                AidExtractTask current = extractTaskService.selectAidExtractTaskById(taskId);
                String expectedTraceId = current == null ? null : current.getBillingTraceId();
                if (StrUtil.isNotBlank(expectedTraceId))
                {
                    updateTaskFailed(taskId, expectedTraceId, "提交失败");
                }
                else
                {
                    updateTaskFailed(taskId, null, "提交失败");
                }
                try { extractBillingService.refundBilling(taskId, userId, expectedTraceId); }
                catch (Exception refundEx)
                {
                    log.error("形态生成任务入队失败退款异常，等待补偿收口: taskId={}, traceId={}",
                            taskId, expectedTraceId, refundEx);
                }
                casDeleteExtractLockIfMatch(formLockKey, itemLockToken);
                throw new ServiceException("提交失败，请重试");
            }

            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(TASK_STATUS_PENDING)
                    .build();
        }
        catch (RuntimeException e)
        {
            log.error("形态生成任务创建阶段异常: assetId={}, userId={}", assetId, userId, e);
            // 只要 task 已落库，就必须标记 FAILED，避免僵尸 PENDING
            if (task != null && task.getId() != null)
            {
                AidExtractTask current = extractTaskService.selectAidExtractTaskById(task.getId());
                String expectedTraceId = current == null ? null : current.getBillingTraceId();
                if (StrUtil.isNotBlank(expectedTraceId))
                {
                    updateTaskFailed(task.getId(), expectedTraceId, "提交失败");
                }
                else
                {
                    updateTaskFailed(task.getId(), null, "提交失败");
                }
                // billingPrepared 只决定是否需要退款
                if (billingPrepared)
                {
                    try
                    {
                        extractBillingService.refundBilling(task.getId(), userId, expectedTraceId);
                        log.info("形态生成任务异常退款成功: taskId={}, userId={}, assetId={}", task.getId(), userId, assetId);
                    }
                    catch (Exception refundEx)
                    {
                        log.error("形态生成任务异常退款失败, taskId={}", task.getId(), refundEx);
                    }
                }
            }
            // 向上抛异常，让 batchGenerateForm 将该资产归入 failedAssets
            throw new ServiceException("提交失败，请重试");
        }
    }

    /**
     * 角色形态生成：调用 aid_visual_stylist 将 profileData + expectedAppearances 转为视觉描述
     * 支持多形态：如果主表有 expectedAppearances，LLM 会为每个形态生成描述，
     * 方法会为每个形态创建一条 form 记录。
     */
    private List<AidRolePropSceneForm> generateCharacterForms(AidRolePropScene asset, String modelCode,
                                                               Long userId, Long parentTaskId,
                                                               String expectedTraceId)
    {
        String profileData = asset.getProfileData();
        if (StrUtil.isBlank(profileData))
        {
            log.info("角色形态生成失败，档案数据为空: assetId={}", asset.getId());
            throw new ServiceException("档案数据为空");
        }

        String visualPromptTemplate = helper.loadPromptByName(PROMPT_NAME_VISUAL_STYLIST);
        // 模板化调用 → prompt_content 走 system，动态入参走结构化 user message
        Map<String, String> userInputs = buildVisualStylistInputs(asset);
        // 隐藏角色画风仅进入实际 LLM user message；任务摘要只存角色档案，避免隐藏提示词落库。
        String characterInput = userInputs.get("character_profiles");
        String visualDescription = batchParentSubmissionGuard.executeManagedTask(parentTaskId, expectedTraceId,
                () -> helper.callLlmRawWithInputs(visualPromptTemplate, userInputs,
                        modelCode, parentTaskId, userId, characterInput, "extract",
                        "stage=form_character,item=" + asset.getId(),
                        raw -> isValidCharacterFormOutput(raw, asset),
                        expectedTraceId, BIZ_CATEGORY_MAIN_CHARACTER_FORM), TextTaskExecutionRejectedException::new);

        if (!isValidCharacterFormOutput(visualDescription, asset))
        {
            throw new ServiceException("AI生成失败");
        }

        return transactionTemplate.execute(status -> {
            extractBillingService.assertTextTaskBusinessCommit(
                    parentTaskId, userId, expectedTraceId);
            // 尝试解析JSON多形态响应；解析、upsert 与父周期门禁同一事务。
            List<AidRolePropSceneForm> forms = parseVisualDescriptions(
                    visualDescription, asset, userId);
            if (CollectionUtil.isNotEmpty(forms))
            {
                return forms;
            }
            throw new ServiceException("AI生成失败");
        });
    }

    /**
     * 角色形态 raw 的无副作用业务校验。历史已结算结果与本轮新输出共用同一契约：
     * characters[0].appearances 必须完整覆盖当前输入的 expected_appearances，id 不重复，
     * 且每个形态都带可消费的 descriptions。校验失败的历史候选不得阻止新 Provider 调用。
     */
    private boolean isValidCharacterFormOutput(String raw, AidRolePropScene asset)
    {
        if (StrUtil.isBlank(raw) || Objects.isNull(asset))
        {
            return false;
        }
        try
        {
            JsonNode root = helper.parseAiResponse(raw);
            JsonNode characters = root.get("characters");
            if (Objects.isNull(characters) || !characters.isArray() || characters.isEmpty())
            {
                return false;
            }
            JsonNode appearances = characters.get(0).get("appearances");
            if (Objects.isNull(appearances) || !appearances.isArray() || appearances.isEmpty())
            {
                return false;
            }

            Set<Integer> expectedIds = new LinkedHashSet<>();
            buildExpectedAppearancesNode(asset).forEach(node -> expectedIds.add(node.path("id").asInt()));
            Set<Integer> actualIds = new LinkedHashSet<>();
            for (JsonNode appearance : appearances)
            {
                if (!appearance.isObject())
                {
                    return false;
                }
                JsonNode idNode = appearance.get("id");
                if (Objects.isNull(idNode) || !idNode.canConvertToInt()
                        || !actualIds.add(idNode.asInt()))
                {
                    return false;
                }
                JsonNode descriptions = appearance.get("descriptions");
                boolean validDescription = Objects.nonNull(descriptions)
                        && ((descriptions.isTextual() && StrUtil.isNotBlank(descriptions.asText()))
                        || (descriptions.isArray() && !descriptions.isEmpty()
                        && StrUtil.isNotBlank(descriptions.get(0).asText())));
                if (!validDescription)
                {
                    return false;
                }
            }
            return actualIds.equals(expectedIds);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /**
     * 构建角色视觉设计师输入，角色档案与项目角色画风保持独立。
     */
    private Map<String, String> buildVisualStylistInputs(AidRolePropScene asset)
    {
        AidComicProject project = projectService.selectAidComicProjectById(asset.getProjectId());
        if (Objects.isNull(project))
        {
            log.info("角色形态生成失败，项目不存在: assetId={}, projectId={}", asset.getId(), asset.getProjectId());
            throw new ServiceException("项目不存在");
        }

        Map<String, String> userInputs = new LinkedHashMap<>();
        userInputs.put("character_profiles", buildVisualStylistInputV2(asset));
        userInputs.put("art_style_prompt", projectStyleSnapshotService.resolveCharacterPrompt(project));
        return userInputs;
    }

    /**
     * 构建视觉设计师（aid_visual_stylist）的标准化输入 character_profiles JSON。
     */
    private String buildVisualStylistInputV2(AidRolePropScene asset)
    {
        com.fasterxml.jackson.databind.node.ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("name", asset.getName());
        appendTextField(root, "introduction", asset.getIntroduction());
        appendTextField(root, "gender", asset.getGender());
        // age_range 现由角色提取输出为纯数字岁数（如 "25"）。喂给视觉师前转成自然语言「N岁左右」；
        // 0/空/非人（无年龄）跳过不写；历史中文文本（如「约二十五岁」）原样透传，保证老数据兼容。
        appendTextField(root, "age_range", formatAgeRangeForStylist(asset.getAgeRange()));
        appendTextField(root, "role_level", asset.getRoleLevel());

        List<String> aliases = parseAliasesName(asset.getAliasesName());
        if (CollectionUtil.isNotEmpty(aliases))
        {
            root.set("aliases", OBJECT_MAPPER.valueToTree(aliases));
        }

        mergeProfileData(root, asset);

        // 与下游 parseVisualDescriptions 的解析约定保持一致
        root.set("expected_appearances", buildExpectedAppearancesNode(asset));

        return root.toString();
    }

    /**
     * 把 profileData JSON 对象中的扩展字段平铺到顶层 root 节点。
     */
    private void mergeProfileData(com.fasterxml.jackson.databind.node.ObjectNode root, AidRolePropScene asset)
    {
        String profileData = asset.getProfileData();
        if (StrUtil.isBlank(profileData))
        {
            return;
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(profileData);
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode profileObj))
            {
                log.warn("profileData 非对象结构，跳过扩展字段合并: assetId={}", asset.getId());
                return;
            }
            // 仅平铺主表字段未占用的 key，避免 profileData 旧值覆盖主表权威列
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = profileObj.fields();
            while (it.hasNext())
            {
                java.util.Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                if (root.has(key))
                {
                    continue;
                }
                JsonNode value = entry.getValue();
                if (Objects.isNull(value) || value.isNull())
                {
                    continue;
                }
                // 跳过空字符串扩展字段，与 appendTextField 风格保持一致
                if (value.isTextual() && StrUtil.isBlank(value.asText("")))
                {
                    continue;
                }
                root.set(key, value);
            }
        }
        catch (Exception e)
        {
            // 解析失败仅打 warn，绝不影响 root 已写入的主表字段输出
            log.warn("profileData 解析失败，仅输出主表字段: assetId={}, err={}", asset.getId(), e.getMessage());
        }
    }

    /**
     * 构建 expected_appearances 数组节点（id 规范化到 0-based）。
     */
    private com.fasterxml.jackson.databind.node.ArrayNode buildExpectedAppearancesNode(AidRolePropScene asset)
    {
        String expectedAppearances = asset.getExpectedAppearances();
        if (StrUtil.isBlank(expectedAppearances))
        {
            // 兜底主形象
            com.fasterxml.jackson.databind.node.ArrayNode arr = OBJECT_MAPPER.createArrayNode();
            arr.add(buildAppearanceNode(0, "初始形象", "初始形象"));
            return arr;
        }
        try
        {
            JsonNode parsed = OBJECT_MAPPER.readTree(expectedAppearances);
            if (!parsed.isArray() || parsed.isEmpty())
            {
                com.fasterxml.jackson.databind.node.ArrayNode arr = OBJECT_MAPPER.createArrayNode();
                arr.add(buildAppearanceNode(0, "初始形象", "初始形象"));
                return arr;
            }

            // 探测 id 起始值：判断是否需要统一减 1
            boolean hasZero = false;
            boolean hasOne = false;
            for (JsonNode it : parsed)
            {
                if (Objects.isNull(it) || !it.isObject()) { continue; }
                JsonNode idNode = it.get("id");
                if (Objects.nonNull(idNode) && idNode.canConvertToInt())
                {
                    int v = idNode.asInt();
                    if (v == 0) { hasZero = true; }
                    if (v == 1) { hasOne = true; }
                }
            }
            // 仅在"无 0 但有 1"时才认为是 1-based，需要统一减 1；否则保持原 id
            boolean shiftDown = !hasZero && hasOne;

            com.fasterxml.jackson.databind.node.ArrayNode out = OBJECT_MAPPER.createArrayNode();
            for (JsonNode it : parsed)
            {
                // 非对象元素直接丢弃（保持数组结构纯净）
                if (Objects.isNull(it) || !it.isObject()) { continue; }
                JsonNode idNode = it.get("id");
                // 缺 id 字段或 id 不可转 int 的元素直接跳过：
                // 否则会被强制归 0，与已有 id=0 主形象冲突，向下游 LLM 同时发出多个"主形象"产生语义歧义
                if (Objects.isNull(idNode) || !idNode.canConvertToInt()) { continue; }
                int rawId = idNode.asInt();
                int normalizedId = shiftDown ? Math.max(rawId - 1, 0) : rawId;
                String name = sanitizeFormLabel(it.hasNonNull("name") ? it.get("name").asText("") : "");
                String changeReason = sanitizeFormLabel(it.hasNonNull("change_reason") ? it.get("change_reason").asText("") : "");
                if (normalizedId == 0)
                {
                    // 主形象锚点：change_reason 强制规范为「初始形象」，与基准图继承匹配口径一致
                    changeReason = "初始形象";
                }
                else if (StrUtil.isBlank(changeReason))
                {
                    changeReason = "形象变化";
                }
                if (StrUtil.isBlank(name))
                {
                    name = changeReason;
                }
                out.add(buildAppearanceNode(normalizedId, name, changeReason));
            }
            // 全部被跳过 / 数组本就为空 → 兜底主形象，与文档规则一致
            if (out.isEmpty())
            {
                out.add(buildAppearanceNode(0, "初始形象", "初始形象"));
            }
            return out;
        }
        catch (Exception e)
        {
            // 解析异常不抛出，返回兜底主形象，避免阻塞整批形态生成
            log.warn("expected_appearances 解析失败，使用兜底主形象: assetId={}, err={}", asset.getId(), e.getMessage());
            com.fasterxml.jackson.databind.node.ArrayNode arr = OBJECT_MAPPER.createArrayNode();
            arr.add(buildAppearanceNode(0, "初始形象", "初始形象"));
            return arr;
        }
    }

    /**
     * 构造单条 expected_appearances 元素：{id, name, change_reason}
     */
    private com.fasterxml.jackson.databind.node.ObjectNode buildAppearanceNode(int id, String name, String changeReason)
    {
        com.fasterxml.jackson.databind.node.ObjectNode node = OBJECT_MAPPER.createObjectNode();
        node.put("id", id);
        node.put("name", StrUtil.blankToDefault(name, ""));
        node.put("change_reason", StrUtil.blankToDefault(changeReason, "初始形象"));
        return node;
    }

    /**
     * 向 root 写入字符串字段；空字符串字段不写入，避免向下游模型传送冗余空字段。
     */
    private void appendTextField(com.fasterxml.jackson.databind.node.ObjectNode root, String key, String value)
    {
        if (StrUtil.isBlank(value))
        {
            return;
        }
        root.put(key, value);
    }

    /**
     * 把角色 age_range 格式化为喂给视觉师的自然语言年龄。
     */
    private String formatAgeRangeForStylist(String ageRange)
    {
        if (StrUtil.isBlank(ageRange))
        {
            return null;
        }
        String trimmed = ageRange.trim();
        // 纯整数（提取输出的标准格式）：0 视为无年龄跳过，正数拼「N岁左右」
        if (trimmed.matches("\\d+"))
        {
            int age = Integer.parseInt(trimmed);
            return age > 0 ? age + "岁左右" : null;
        }
        // 非纯数字的中文描述原样透传
        return trimmed;
    }

    /**
     * 解析主表 aliases_name 列：按英文 / 中文逗号拆数组、去空、忽略大小写去重，保持原始大小写第一次出现的形式。
     */
    private List<String> parseAliasesName(String aliasesName)
    {
        if (StrUtil.isBlank(aliasesName))
        {
            return java.util.Collections.emptyList();
        }
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<String> out = new java.util.ArrayList<>();
        for (String token : aliasesName.split("[,，]"))
        {
            String t = token.trim();
            if (StrUtil.isNotBlank(t) && seen.add(t))
            {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 解析视觉描述JSON响应，为每个appearance创建form记录
     * 预期JSON格式（新协议）：
     * {"characters":[{"name":"xx","appearances":[{"id":0,"change_reason":"初始形象",
     *   "descriptions":"完整外貌描述"}]}]}
     */
    private List<AidRolePropSceneForm> parseVisualDescriptions(String rawText,
                                                               AidRolePropScene asset, Long userId)
    {
        try
        {
            JsonNode root = helper.parseAiResponse(rawText);
            JsonNode characters = root.get("characters");
            if (Objects.isNull(characters) || !characters.isArray() || characters.isEmpty())
            {
                return null;
            }

            // 取第一个角色（单资产生成场景只有一个角色）
            JsonNode character = characters.get(0);
            JsonNode appearances = character.get("appearances");
            if (Objects.isNull(appearances) || !appearances.isArray() || appearances.isEmpty())
            {
                return null;
            }

            // 对 (assetId, changeReason) 做 upsert，避免 createAsset 自动创建的占位 form 与 AI 生成 form 冲突。
            // 预先加载本资产已有的全部 form（del_flag='0'），按 changeReason 建索引便于命中更新。
            Map<String, AidRolePropSceneForm> existingByReason = loadExistingFormsByChangeReason(asset.getId());

            List<AidRolePropSceneForm> resultForms = new ArrayList<>();
            int appearanceIdx = 0;
            for (JsonNode appearance : appearances)
            {
                JsonNode appearanceIdNode = appearance.get("id");
                int appearanceId = (Objects.nonNull(appearanceIdNode) && appearanceIdNode.canConvertToInt())
                        ? appearanceIdNode.asInt() : appearanceIdx;

                //    （如「初始形象，白衣佩剑的女剑修」会污染形态名，并使 change_reason 不再精确等于「初始形象」，
                //      导致变体形态反查不到基准图、整链路报「请先生成初始形象」无法生图）
                String appearanceName = sanitizeFormLabel(helper.getJsonText(appearance, "name"));
                String changeReason = sanitizeFormLabel(helper.getJsonText(appearance, "change_reason"));
                if (appearanceId == 0)
                {
                    // 主形态锚点：强制规范为「初始形象」，保证变体形态基准图继承匹配恒成立
                    changeReason = INITIAL_FORM_CHANGE_REASON;
                }
                else if (StrUtil.isBlank(changeReason))
                {
                    changeReason = "变体形象";
                }
                // 形态名缺失时回退为 changeReason
                if (StrUtil.isBlank(appearanceName))
                {
                    appearanceName = changeReason;
                }

                JsonNode descriptionsNode = appearance.get("descriptions");
                String descriptions = "";
                if (Objects.nonNull(descriptionsNode) && descriptionsNode.isTextual())
                {
                    // 新协议：单字符串
                    descriptions = descriptionsNode.asText("").trim();
                }
                else if (Objects.nonNull(descriptionsNode) && descriptionsNode.isArray() && !descriptionsNode.isEmpty())
                {
                    // 兼容极端情况：若仍为数组则取首条
                    descriptions = descriptionsNode.get(0).asText("").trim();
                }
                if (StrUtil.isBlank(descriptions))
                {
                    // descriptions 为空则保留原兜底：整个 appearance 的文本作为单条 description
                    descriptions = appearance.toString();
                }
                if (StrUtil.isBlank(descriptions))
                {
                    continue;
                }

                String structuredPromptText = buildStructuredCharacterPromptText(
                        asset, changeReason, appearanceId, descriptions);

                AidRolePropSceneForm hit = existingByReason.get(changeReason);
                if (Objects.nonNull(hit))
                {
                    hit.setPromptText(structuredPromptText);
                    hit.setVisualDescStatus(VISUAL_DESC_STATUS_COMPLETED);
                    // 自愈：change_reason 是系统标签，统一刷成规范值（清掉历史脏值如「初始形象，白衣佩剑的女剑修」）
                    hit.setChangeReason(changeReason);
                    // 仅当原 name 为系统自动生成（create_source=auto）时才重算为「资产名_规范形态名」，
                    // 避免覆盖用户手动改过的形态名
                    if (Objects.equals("auto", hit.getCreateSource()))
                    {
                        String healedName = StrUtil.isNotBlank(appearanceName)
                                ? asset.getName() + "_" + appearanceName : asset.getName();
                        hit.setName(healedName);
                    }
                    hit.setUpdateTime(DateUtils.getNowDate());
                    hit.setUpdateBy(String.valueOf(userId));
                    updateOwnedPromptForm(hit, asset, userId);
                    resultForms.add(hit);
                }
                else
                {
                    // 角色形态名称规则：资产名_形态名
                    AidRolePropSceneForm fresh = buildFormRecord(asset, appearanceName, changeReason, structuredPromptText, userId);
                    if (!rpsFormService.save(fresh) || Objects.isNull(fresh.getId()))
                    {
                        throw new ServiceException("形态保存失败");
                    }
                    resultForms.add(fresh);
                }
                appearanceIdx++;
            }

            return resultForms.isEmpty() ? null : resultForms;
        }
        catch (Exception e)
        {
            if (e instanceof ServiceException serviceException)
            {
                throw serviceException;
            }
            log.warn("视觉描述JSON解析异常: assetId={}", asset.getId(), e);
            return null;
        }
    }

    /**
     * 构建form记录（角色形态名称规则：资产名_形态名）。
     *
     * @param asset          主表资产
     * @param appearanceName 形态名（对应 expected_appearances[*].name）
     * @param changeReason   变更原因
     * @param promptText     规范化 promptText JSON
     * @param userId         用户ID
     */
    private AidRolePropSceneForm buildFormRecord(AidRolePropScene asset, String appearanceName,
                                                 String changeReason, String promptText, Long userId)
    {
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setAssetId(asset.getId());
        form.setProjectId(asset.getProjectId());
        form.setEpisodeId(asset.getEpisodeId());
        form.setUserId(userId);
        // 角色形态名称规则：资产名_形态名
        String composedName = StrUtil.isNotBlank(appearanceName)
                ? asset.getName() + "_" + appearanceName : asset.getName();
        form.setName(composedName);
        form.setChangeReason(changeReason);
        form.setPromptText(promptText);
        // 自动提取链路标记
        form.setCreateSource("auto");
        // form.is_use 字段已删除，使用状态走 form_image
        form.setVisualDescStatus(VISUAL_DESC_STATUS_COMPLETED);
        form.setDelFlag(DEL_FLAG_NORMAL);
        form.setCreateTime(DateUtils.getNowDate());
        form.setCreateBy(String.valueOf(userId));
        return form;
    }

    /**
     * 加载某主资产下"未删除 form"按 changeReason 索引，供 generateForm 链路 upsert 使用。
     * 同 changeReason 出现多条时取 createTime 最早一条（稳定优先），其它视为冗余历史不动。
     */
    private Map<String, AidRolePropSceneForm> loadExistingFormsByChangeReason(Long assetId)
    {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AidRolePropSceneForm> q = Wrappers.lambdaQuery();
        q.eq(AidRolePropSceneForm::getAssetId, assetId);
        q.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        q.orderByAsc(AidRolePropSceneForm::getCreateTime);
        List<AidRolePropSceneForm> existing = rpsFormService.list(q);
        Map<String, AidRolePropSceneForm> map = new java.util.HashMap<>();
        if (CollectionUtil.isNotEmpty(existing))
        {
            for (AidRolePropSceneForm f : existing)
            {
                // 按清洗后的 change_reason 建索引：历史脏值（如「初始形象，白衣佩剑的女剑修」）
                // 归一到「初始形象」，使变体形态仍能反查到既有基准形态，无需先迁移存量数据
                String key = sanitizeFormLabel(StrUtil.blankToDefault(f.getChangeReason(), ""));
                if (StrUtil.isNotBlank(key))
                {
                    // putIfAbsent → 同 changeReason 出现多条时保留 createTime 最早一条（已按升序）
                    map.putIfAbsent(key, f);
                }
            }
        }
        return map;
    }

    /**
     * 构建角色规范化 prompt_text JSON：。
     */
    private String buildStructuredCharacterPromptText(AidRolePropScene asset,
                                                      String changeReason,
                                                      int appearanceId,
                                                      String descriptions)
    {
        try
        {
            com.fasterxml.jackson.databind.node.ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("assetType", ASSET_TYPE_CHARACTER);
            root.put("promptVersion", "v2");
            if (Objects.nonNull(asset))
            {
                root.put("name", StrUtil.blankToDefault(asset.getName(), ""));
            }
            root.put("appearanceId", appearanceId);
            root.put("changeReason", StrUtil.blankToDefault(changeReason, ""));
            root.put("descriptions", StrUtil.blankToDefault(descriptions, ""));
            return OBJECT_MAPPER.writeValueAsString(root);
        }
        catch (Exception e)
        {
            log.warn("规范化角色 prompt_text 序列化失败，回退为纯文本: assetId={}, err={}",
                    Objects.nonNull(asset) ? asset.getId() : null, e.getMessage());
            return StrUtil.blankToDefault(descriptions, "");
        }
    }

    /**
     * 将主资产的 available_slots JSON 字符串解析为 ArrayNode。
     * 非法或空值时降级为空数组，不抛异常。
     */
    private com.fasterxml.jackson.databind.node.ArrayNode parseAvailableSlotsToArray(String availableSlotsJson)
    {
        if (StrUtil.isNotBlank(availableSlotsJson))
        {
            try
            {
                com.fasterxml.jackson.databind.JsonNode parsed = OBJECT_MAPPER.readTree(availableSlotsJson.trim());
                if (parsed.isArray())
                {
                    return (com.fasterxml.jackson.databind.node.ArrayNode) parsed;
                }
            }
            catch (Exception e)
            {
                log.warn("解析 available_slots 为数组失败，降级为空数组: err={}", e.getMessage());
            }
        }
        return OBJECT_MAPPER.createArrayNode();
    }

    /**
     * 场景形态生成：调用 aid_scene_stylist 智能体生成四视图 JSON，存入 form.prompt_text。
     * 场景不再直接复制 introduction，改为调 LLM 生成四视图结构化 prompt。
     * 入参从 aid_role_prop_scene 主表取（name / introduction / summary / available_slots / profile_data）。
     * 输出 JSON 存入 aid_role_prop_scene_form.prompt_text。
     * introduction 为空时 fallback 到旧 LLM 单文本生成逻辑。
     */
    private AidRolePropSceneForm generateSceneForm(AidRolePropScene asset, String modelCode,
                                                    Long userId, Long parentTaskId,
                                                    String expectedTraceId)
    {
        String introduction = asset.getIntroduction();
        if (StrUtil.isBlank(introduction))
        {
            // fallback：introduction 为空，走旧 LLM 单文本生成
            log.info("场景 fallback LLM: assetId={}，introduction 为空", asset.getId());
            return generateScenePropForm(
                    asset, modelCode, userId, parentTaskId, expectedTraceId);
        }

        // 构建场景四视图 LLM 提示词：模板化分离 → 模板正文走 system，动态入参走 user
        log.info("场景四视图生成: assetId={}, introduction长度={}", asset.getId(), introduction.length());
        String sceneStyleTemplate = helper.loadPromptByName("aid_scene_stylist");
        Map<String, String> sceneStyleInputs = collectSceneStylistInputs(asset);
        // aid_media_task.prompt 列只存动态入参摘要，不存模板正文
        String digest = buildSceneStylistTaskDigest(asset);
        // 调 LLM（aid_scene_stylist 智能体）
        String llmOutput = batchParentSubmissionGuard.executeManagedTask(parentTaskId, expectedTraceId,
                () -> helper.callLlmRawWithInputs(sceneStyleTemplate, sceneStyleInputs,
                        modelCode, parentTaskId, userId, digest, "extract",
                        "stage=form_scene,item=" + asset.getId(), this::isValidStylistReplay,
                        expectedTraceId, "main_scene_form"), TextTaskExecutionRejectedException::new);

        if (StrUtil.isBlank(llmOutput))
        {
            throw new ServiceException("AI生成失败");
        }
        // 与续生 replay validator 使用完全相同的业务契约；新输出无效时本轮失败，
        // 续生会跳过这条已结算但不可消费的历史 child，允许重新调用 Provider。
        extractPromptFromStylistJson(llmOutput);

        // LLM 输出的四视图 JSON 与父周期门禁同一事务提交。
        return transactionTemplate.execute(status -> {
            extractBillingService.assertTextTaskBusinessCommit(
                    parentTaskId, userId, expectedTraceId);
            return upsertSinglePromptForm(asset, null, "初始形象", llmOutput, userId);
        });
    }

    /**
     * 构建场景四视图智能体（aid_scene_stylist）的完整提示词。
     * 从 aid_role_prop_scene 主表取字段，替换提示词模板中的 8 个占位符。
     * 项目级字段（art_style / art_style_prompt / aspect_ratio）从关联项目取。
     */
    /**
     * 构建 aid_scene_stylist 智能体的动态入参映射（不含模板正文）。
     * 与 {@link #buildSceneStylistPrompt} 共享变量收集逻辑，但只返回入参 Map，
     * 供 {@link AssetExtractHelper#callLlmRawWithInputs} 把入参作为结构化 user message 发送。
     */
    private Map<String, String> collectSceneStylistInputs(AidRolePropScene asset)
    {
        // 从主表取基础字段
        String assetName = StrUtil.blankToDefault(asset.getName(), "");
        String summary = StrUtil.blankToDefault(asset.getSummary(), "");
        String introduction = StrUtil.blankToDefault(asset.getIntroduction(), "");
        String availableSlots = StrUtil.blankToDefault(asset.getAvailableSlots(), "[]");

        // 从主表 profile_data 拼装 scene_context（时空背景一句话）
        String sceneContext = buildSceneContextFromProfile(asset);

        // 从项目取画风和比例
        AidComicProject project = projectService.selectAidComicProjectById(asset.getProjectId());
        // 风格名称同样走脏值过滤（custom/纯数字/过短串 → 空串），避免把内部枚举当风格名下发
        String artStyle = resolveProjectArtStyleName(project);
        // 画风提示词统一走 resolveProjectArtStylePrompt 解析，
        // 兼容 official（反查提示词库）/ custom（图片 URL 或无意义脏值 → 空串）/ 新项目（原样返回），
        // 避免把 custom 的图片 URL 或脏值（如 "1"）原样拼进 prompt 末尾"图片风格"字段污染出图。
        String artStylePrompt = resolveProjectArtStylePrompt(project);
        String aspectRatio = resolveSceneAspectRatio(project);

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("asset_name", assetName);
        variables.put("form_prompt_simple", summary);
        variables.put("form_prompt_text", introduction);
        variables.put("available_slots", availableSlots);
        variables.put("scene_context", sceneContext);
        variables.put("aspect_ratio", aspectRatio);
        variables.put("art_style", artStyle);
        variables.put("art_style_prompt", artStylePrompt);
        return variables;
    }

    /**
     * 场景四视图任务存档摘要（aid_media_task.prompt 列内容）。
     * 仅打包动态入参,跳过 aid_scene_stylist 模板正文,避免 TEXT 列截断。
     */
    private String buildSceneStylistTaskDigest(AidRolePropScene asset)
    {
        AidComicProject project = projectService.selectAidComicProjectById(asset.getProjectId());
        return new StringBuilder()
                .append("[asset_name]\n").append(StrUtil.blankToDefault(asset.getName(), ""))
                .append("\n[form_prompt_simple]\n").append(StrUtil.blankToDefault(asset.getSummary(), ""))
                .append("\n[form_prompt_text]\n").append(StrUtil.blankToDefault(asset.getIntroduction(), ""))
                .append("\n[available_slots]\n").append(StrUtil.blankToDefault(asset.getAvailableSlots(), "[]"))
                .append("\n[scene_context]\n").append(StrUtil.blankToDefault(buildSceneContextFromProfile(asset), ""))
                .append("\n[aspect_ratio]\n").append(StrUtil.blankToDefault(resolveSceneAspectRatio(project), ""))
                .append("\n[art_style]\n").append(Objects.nonNull(project) ? StrUtil.blankToDefault(project.getVideoStyleType(), "") : "")
                .append("\n[art_style_prompt]\n").append(Objects.nonNull(project) ? StrUtil.blankToDefault(project.getVideoStyleValue(), "") : "")
                .toString();
    }

    /**
     * 道具形态生成：调用 aid_prop_stylist 智能体生成道具视觉增强提示词 JSON，存入 form.prompt_text。
     * 道具不再直接复制 introduction，改为调 LLM 生成工程级生图提示词。
     * 入参从 aid_role_prop_scene 主表取（name / introduction / profile_data 中的扩展字段）。
     * 输出 JSON 存入 aid_role_prop_scene_form.prompt_text。
     * introduction 为空时 fallback 到旧 LLM 单文本生成逻辑。
     */
    private AidRolePropSceneForm generatePropForm(AidRolePropScene asset, String modelCode,
                                                   Long userId, Long parentTaskId,
                                                   String expectedTraceId)
    {
        String introduction = asset.getIntroduction();
        if (StrUtil.isBlank(introduction))
        {
            // fallback：introduction 为空，走旧 LLM 单文本生成
            log.info("道具 fallback LLM: assetId={}，introduction 为空", asset.getId());
            return generateScenePropForm(
                    asset, modelCode, userId, parentTaskId, expectedTraceId);
        }

        // 构建道具视觉增强 LLM 提示词：模板化分离 → 模板正文走 system，动态入参走 user
        log.info("道具视觉增强生成: assetId={}, introduction长度={}", asset.getId(), introduction.length());
        String propStyleTemplate = helper.loadPromptByName("aid_prop_stylist");
        Map<String, String> propStyleInputs = collectPropStylistVariables(asset);
        // aid_media_task.prompt 列只存动态入参摘要，不存模板正文
        String digest = buildPropStylistTaskDigest(asset);
        // 调 LLM（aid_prop_stylist 智能体）
        String llmOutput = batchParentSubmissionGuard.executeManagedTask(parentTaskId, expectedTraceId,
                () -> helper.callLlmRawWithInputs(propStyleTemplate, propStyleInputs,
                        modelCode, parentTaskId, userId, digest, "extract",
                        "stage=form_prop,item=" + asset.getId(), this::isValidStylistReplay,
                        expectedTraceId, "main_prop_form"), TextTaskExecutionRejectedException::new);

        if (StrUtil.isBlank(llmOutput))
        {
            throw new ServiceException("AI生成失败");
        }
        // 与续生 replay validator 使用完全相同的业务契约。
        extractPromptFromStylistJson(llmOutput);

        // LLM 输出的道具视觉增强 JSON 与父周期门禁同一事务提交。
        return transactionTemplate.execute(status -> {
            extractBillingService.assertTextTaskBusinessCommit(
                    parentTaskId, userId, expectedTraceId);
            return upsertSinglePromptForm(asset, null, "初始形象", llmOutput, userId);
        });
    }

    /**
     * 道具视觉增强任务存档摘要（aid_media_task.prompt 列内容）。
     * 仅打包动态入参,跳过 aid_prop_stylist 模板正文,避免 TEXT 列截断。
     */
    private String buildPropStylistTaskDigest(AidRolePropScene asset)
    {
        Map<String, String> vars = collectPropStylistVariables(asset);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : vars.entrySet())
        {
            sb.append('[').append(entry.getKey()).append("]\n")
                    .append(StrUtil.blankToDefault(entry.getValue(), "")).append('\n');
        }
        return sb.toString();
    }

    /**
     * 收集 aid_prop_stylist 模板需要的所有动态入参（从主表 + profile_data + 项目读取）。
     */
    private Map<String, String> collectPropStylistVariables(AidRolePropScene asset)
    {
        // 从主表取基础字段
        String assetName = StrUtil.blankToDefault(asset.getName(), "");
        String propDescription = StrUtil.blankToDefault(asset.getIntroduction(), "");

        // 从 profile_data 取扩展字段
        String propType = "";
        String propMaterial = "";
        String propDimensions = "";
        String propStateName = "";
        if (StrUtil.isNotBlank(asset.getProfileData()))
        {
            try
            {
                com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(asset.getProfileData());
                if (Objects.nonNull(root) && root.isObject())
                {
                    propType = getProfileField(root, "prop_type");
                    propMaterial = getProfileField(root, "material");
                    propDimensions = getProfileField(root, "dimensions");
                    propStateName = getProfileField(root, "state_name");
                }
            }
            catch (Exception e)
            {
                log.warn("解析道具 profile_data 失败，降级为空: assetId={}, err={}",
                        asset.getId(), e.getMessage());
            }
        }
        // state_name 兜底：道具名-完好
        if (StrUtil.isBlank(propStateName))
        {
            propStateName = assetName + "-完好";
        }

        // 从项目取画风
        AidComicProject project = projectService.selectAidComicProjectById(asset.getProjectId());
        // 道具与场景继续使用公开风格名称及画风提示词，不受角色隐藏画风链路影响
        String artStyle = resolveProjectArtStyleName(project);
        String artStylePrompt = resolveProjectArtStylePrompt(project);

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("asset_name", assetName);
        variables.put("prop_description", propDescription);
        variables.put("prop_type", propType);
        variables.put("prop_material", propMaterial);
        variables.put("prop_dimensions", propDimensions);
        variables.put("prop_state_name", propStateName);
        variables.put("art_style", artStyle);
        variables.put("art_style_prompt", artStylePrompt);
        return variables;
    }

    /**
     * 从 profile_data JsonNode 安全取字符串字段，空值返回空串。
     */
    private String getProfileField(com.fasterxml.jackson.databind.JsonNode root, String key)
    {
        com.fasterxml.jackson.databind.JsonNode node = root.get(key);
        if (Objects.nonNull(node) && node.isTextual())
        {
            return StrUtil.blankToDefault(node.asText(""), "");
        }
        return "";
    }

    /**
     * 从 aid_scene_stylist / aid_prop_stylist 输出的 JSON 中提取 prompt 字段。
     */
    /**
     * 从 aid_scene_stylist / aid_prop_stylist 输出的 JSON 中提取 prompt 字段（严格版）。
     *
     * @param json LLM 原始输出（可能含 markdown fence）
     * @return prompt 字段值（必非空）
     * @throws RuntimeException 入参为空 / JSON 解析失败 / 不是对象或数组 / 缺少 prompt 字段 / prompt 字段为空
     */
    private String extractPromptFromStylistJson(String json)
    {
        if (StrUtil.isBlank(json))
        {
            throw new ServiceException("请先生成形态描述");
        }
        // 剥离 markdown fence + 前后空白
        String cleaned = stripMarkdownJsonFence(json);
        if (StrUtil.isBlank(cleaned))
        {
            throw new ServiceException("视觉描述格式有误");
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try
        {
            root = OBJECT_MAPPER.readTree(cleaned);
        }
        catch (Exception e)
        {
            log.warn("extractPromptFromStylistJson JSON 解析失败: err={}, contentLen={}",
                    e.getMessage(), cleaned.length());
            throw TaskErrorPresentation.toServiceException(e.getMessage(), "视觉描述格式有误");
        }
        com.fasterxml.jackson.databind.JsonNode target = null;
        if (root.isObject())
        {
            target = root;
        }
        else if (root.isArray() && !root.isEmpty())
        {
            target = root.get(0);
        }
        if (Objects.isNull(target))
        {
            log.warn("extractPromptFromStylistJson 顶层结构非对象/非数组: contentLen={}", cleaned.length());
            throw new ServiceException("视觉描述格式有误");
        }
        if (!target.has("prompt"))
        {
            // 收集实际字段名，便于排查 LLM 输出 schema 偏离
            StringBuilder actualFields = new StringBuilder();
            java.util.Iterator<String> it = target.fieldNames();
            while (it.hasNext())
            {
                if (actualFields.length() > 0) { actualFields.append(','); }
                actualFields.append(it.next());
            }
            log.warn("extractPromptFromStylistJson 缺少 prompt 字段: 实际字段=[{}], contentLen={}",
                    actualFields, cleaned.length());
            throw new ServiceException("视觉描述不能为空");
        }
        com.fasterxml.jackson.databind.JsonNode promptNode = target.get("prompt");
        if (Objects.isNull(promptNode) || !promptNode.isTextual())
        {
            throw new ServiceException("视觉描述格式有误");
        }
        String prompt = promptNode.asText("");
        if (StrUtil.isBlank(prompt))
        {
            throw new ServiceException("视觉描述不能为空");
        }
        return prompt;
    }

    /**
     * form_generate 单任务和批任务共用同一资产级消费逻辑。场景/道具 stylist 历史输出只有在
     * 当前严格 schema（对象或首个数组元素包含非空 prompt）可消费时才允许回放；否则续生会
     * 跳过旧 SUCCEEDED child 并重新调用 Provider，避免无效 raw 被永久命中。
     */
    private boolean isValidStylistReplay(String raw)
    {
        try
        {
            return StrUtil.isNotBlank(extractPromptFromStylistJson(raw));
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * extractPromptFromStylistJson 的容错版本 —— 仅用于 aid_media_task.prompt 列存档摘要。
     * digest 仅用于审计排查，不参与 LLM 调用，因此异常情况下用空串兜底，不影响主流程。
     */
    private String extractPromptFromStylistJsonForDigest(String json)
    {
        try
        {
            return extractPromptFromStylistJson(json);
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * 剥离 LLM 输出常见的 markdown 代码块包裹，提取出纯 JSON 文本。
     */
    private String stripMarkdownJsonFence(String raw)
    {
        if (StrUtil.isBlank(raw))
        {
            return raw;
        }
        String trimmed = raw.trim();
        // 找出 JSON 主体起止：起点为第一个 { 或 [，终点为最后一个 } 或 ]
        int objStart = trimmed.indexOf('{');
        int arrStart = trimmed.indexOf('[');
        int start;
        if (objStart < 0 && arrStart < 0)
        {
            return trimmed;
        }
        if (objStart < 0)
        {
            start = arrStart;
        }
        else if (arrStart < 0)
        {
            start = objStart;
        }
        else
        {
            start = Math.min(objStart, arrStart);
        }
        int objEnd = trimmed.lastIndexOf('}');
        int arrEnd = trimmed.lastIndexOf(']');
        int end = Math.max(objEnd, arrEnd);
        if (end < start)
        {
            return trimmed;
        }
        return trimmed.substring(start, end + 1);
    }
    /**
     * 场景/道具通用形态生成：调用 LLM 生成视觉描述，结果 upsert by (assetId, '初始形象')。
     */
    private AidRolePropSceneForm generateScenePropForm(AidRolePropScene asset, String modelCode,
                                                        Long userId, Long parentTaskId,
                                                        String expectedTraceId)
    {
        // 场景/道具形态生成至少需要 summary 或 introduction 其一非空
        if (StrUtil.isBlank(asset.getSummary()) && StrUtil.isBlank(asset.getIntroduction()))
        {
            log.info("形态生成失败，描述为空: assetId={}, assetType={}", asset.getId(), asset.getAssetType());
            throw new ServiceException("描述不能为空");
        }

        // 构建 LLM 提示词并调用
        String systemPrompt = buildScenePropSystemPrompt(asset.getAssetType());
        String userContent = buildScenePropUserContent(asset);
        // userContent 本身就是动态入参（不含模板），直接作为 aid_media_task.prompt 摘要
        String visualDescription = batchParentSubmissionGuard.executeManagedTask(parentTaskId, expectedTraceId,
                () -> helper.callLlmRaw(
                        systemPrompt, userContent, modelCode, parentTaskId, userId, userContent, "extract",
                        "stage=form_" + asset.getAssetType() + ",item=" + asset.getId(),
                        StrUtil::isNotBlank, expectedTraceId, null,
                        formBusinessFunction(asset.getAssetType())),
                TextTaskExecutionRejectedException::new);

        if (StrUtil.isBlank(visualDescription))
        {
            throw new ServiceException("AI生成失败");
        }

        return transactionTemplate.execute(status -> {
            extractBillingService.assertTextTaskBusinessCommit(
                    parentTaskId, userId, expectedTraceId);
            return upsertSinglePromptForm(
                    asset, null, "初始形象", visualDescription, userId);
        });
    }

    /**
     * 通用 upsert：命中 (assetId, changeReason) 则更新 prompt_text + visual_desc_status；否则新建。
     *
     * @param formName 新建时的表单名称；null 则默认使用 asset.getName()
     */
    private AidRolePropSceneForm upsertSinglePromptForm(AidRolePropScene asset, String formName,
                                                        String changeReason,
                                                        String promptText, Long userId)
    {
        Map<String, AidRolePropSceneForm> existingByReason = loadExistingFormsByChangeReason(asset.getId());
        AidRolePropSceneForm hit = existingByReason.get(changeReason);
        if (Objects.nonNull(hit))
        {
            hit.setPromptText(promptText);
            hit.setVisualDescStatus(VISUAL_DESC_STATUS_COMPLETED);
            hit.setUpdateTime(DateUtils.getNowDate());
            hit.setUpdateBy(String.valueOf(userId));
            updateOwnedPromptForm(hit, asset, userId);
            return hit;
        }
        AidRolePropSceneForm form = new AidRolePropSceneForm();
        form.setAssetId(asset.getId());
        form.setProjectId(asset.getProjectId());
        form.setEpisodeId(asset.getEpisodeId());
        form.setUserId(userId);
        form.setName(StrUtil.isNotBlank(formName) ? formName : asset.getName());
        form.setChangeReason(changeReason);
        form.setPromptText(promptText);
        form.setVisualDescStatus(VISUAL_DESC_STATUS_COMPLETED);
        // 自动提取链路标记
        form.setCreateSource("auto");
        // form.is_use 字段已删除，使用状态走 form_image
        form.setDelFlag(DEL_FLAG_NORMAL);
        form.setCreateTime(DateUtils.getNowDate());
        form.setCreateBy(String.valueOf(userId));
        if (!rpsFormService.save(form) || Objects.isNull(form.getId()))
        {
            throw new ServiceException("形态保存失败");
        }
        return form;
    }

    private void updateOwnedPromptForm(AidRolePropSceneForm form,
                                       AidRolePropScene asset, Long userId)
    {
        LambdaUpdateWrapper<AidRolePropSceneForm> update = Wrappers.lambdaUpdate();
        update.eq(AidRolePropSceneForm::getId, form.getId());
        update.eq(AidRolePropSceneForm::getAssetId, asset.getId());
        update.eq(AidRolePropSceneForm::getProjectId, asset.getProjectId());
        if (Objects.isNull(asset.getEpisodeId()))
        {
            update.isNull(AidRolePropSceneForm::getEpisodeId);
        }
        else
        {
            update.eq(AidRolePropSceneForm::getEpisodeId, asset.getEpisodeId());
        }
        update.eq(AidRolePropSceneForm::getUserId, userId);
        update.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        update.set(AidRolePropSceneForm::getName, form.getName());
        update.set(AidRolePropSceneForm::getChangeReason, form.getChangeReason());
        update.set(AidRolePropSceneForm::getPromptText, form.getPromptText());
        update.set(AidRolePropSceneForm::getVisualDescStatus, form.getVisualDescStatus());
        update.set(AidRolePropSceneForm::getUpdateTime, form.getUpdateTime());
        update.set(AidRolePropSceneForm::getUpdateBy, form.getUpdateBy());
        if (!rpsFormService.update(update))
        {
            throw new ServiceException("形态保存失败");
        }
    }
    @Override
    public AssetExtractTaskVO batchGenerateFormImage(List<Long> formIds, Long userId,
                                                     String agentCode, String requestedModelCode,
                                                     String requestedResolution, String requestedAspectRatio)
    {
        FormImageBatchBillingPlan plan = prepareFormImageBatchBillingPlan(formIds, userId,
                agentCode, requestedModelCode, requestedResolution, requestedAspectRatio);
        List<Long> uniqueFormIds = plan.formIds();
        List<FormImageValidated> validatedList = plan.validatedList();
        String modelCode = plan.modelCode();
        String resolution = plan.resolution();
        String aspectRatio = plan.aspectRatio();

        FormImageValidated first = validatedList.get(0);
        for (FormImageValidated validated : validatedList)
        {
            if (!Objects.equals(first.asset.getProjectId(), validated.asset.getProjectId())
                    || !Objects.equals(first.form.getEpisodeId(), validated.form.getEpisodeId()))
            {
                log.info("批量形态图禁止跨项目剧集: userId={}, formIds={}", userId, uniqueFormIds);
                throw new ServiceException("素材归属不一致");
            }
        }
        BatchTaskSlotReservation slotReservation = batchTaskSlotService.acquireTaskSlots(
                first.asset.getProjectId(), first.form.getEpisodeId(),
                List.of(mapAssetTypeToFormImageLogicalType(first.asset.getAssetType())));

        List<String> acquiredLockKeys = new ArrayList<>();
        String itemLockToken = IdUtil.fastSimpleUUID();
        try
        {
            for (Long formId : uniqueFormIds)
            {
                String imageLockKey = buildFormImageLockKey(formId);
                Boolean imageLocked = redisCache.redisTemplate.opsForValue()
                        .setIfAbsent(imageLockKey, itemLockToken, 300, TimeUnit.SECONDS);
                if (imageLocked == null || !imageLocked)
                {
                    log.info("批量形态图生成失败，form已在处理中: formId={}", formId);
                    throw new ServiceException("任务处理中");
                }
                acquiredLockKeys.add(imageLockKey);
            }
        }
        catch (RuntimeException e)
        {
            // 回滚已获取的锁
            for (String key : acquiredLockKeys)
            {
                casDeleteExtractLockIfMatch(key, itemLockToken);
            }
            batchTaskSlotService.release(slotReservation);
            throw e;
        }

        AidExtractTask task = null;
        try
        {
            task = new AidExtractTask();
            task.setProjectId(first.asset.getProjectId());
            task.setEpisodeId(first.form.getEpisodeId());
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_FORM_IMAGE_BATCH);
            task.setModelCode(modelCode);
            // inputSnapshot 存整批参数（JSON）
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("formIds", uniqueFormIds);
            inputMap.put("modelCode", modelCode);
            inputMap.put("itemLockToken", itemLockToken);
            inputMap.put("assetType", first.asset.getAssetType());
            batchTaskSlotService.attachSnapshotMetadata(inputMap, slotReservation);
            // 保存 agentCode 供 consumer 加载对应模板
            if (StrUtil.isNotBlank(agentCode))
            {
                inputMap.put("agentCode", agentCode);
            }
            // 保存 resolution / aspectRatio，consumer 端按此下发图片请求
            if (StrUtil.isNotBlank(resolution))
            {
                inputMap.put("resolution", resolution);
            }
            if (StrUtil.isNotBlank(aspectRatio))
            {
                inputMap.put("aspectRatio", aspectRatio);
            }
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
            task.setStatus(TASK_STATUS_PENDING);
            task.setTotalCount(uniqueFormIds.size());
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            extractTaskService.save(task);

            // 双模式派发统一收口：MQ 开发 MQ；MQ 关走本地线程
            final Long taskId = task.getId();
            boolean enqueued = dualModeTaskDispatcher.dispatch(taskId,
                    first.asset.getProjectId(), first.form.getEpisodeId(),
                    userId, modelCode, TASK_TYPE_FORM_IMAGE_BATCH,
                    buildFormBatchLocalJob(taskId, userId, LOCAL_SPEC_FORM_IMAGE,
                            () -> doFormImageBatch(taskId, userId,
                                    taskQueueService.currentLocalDispatchToken(taskId))));
            if (!enqueued)
            {
                log.warn("批量形态图生成入队失败(可能已取消): taskId={}", task.getId());
                throw new ServiceException("任务提交失败");
            }

            log.info("批量形态图生成父任务创建成功: taskId={}, userId={}, formIds={}", task.getId(), userId, uniqueFormIds);
            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .build();
        }
        catch (Exception e)
        {
            log.error("批量形态图生成父任务创建失败，回滚所有锁: userId={}", userId, e);
            boolean canRelease = Objects.isNull(task) || Objects.isNull(task.getId())
                    || closeFormBatchSubmissionFailure(task, userId, false);
            if (canRelease)
            {
                for (String key : acquiredLockKeys)
                {
                    casDeleteExtractLockIfMatch(key, itemLockToken);
                }
            }
            if (Objects.isNull(task) || Objects.isNull(task.getId()))
            {
                batchTaskSlotService.release(slotReservation);
            }
            throw new ServiceException("提交失败，请重试");
        }
    }

    @Override
    public BillingQuoteVO quoteFormImage(FormImageGenerateRequest request, Long userId)
    {
        FormImageBatchBillingPlan plan = prepareFormImageBatchBillingPlan(
                request.getFormIds(), userId, request.getAgentCode(), request.getModelCode(),
                request.getResolution(), request.getAspectRatio());
        Map<Long, FormImageValidated> byId = plan.validatedList().stream()
                .collect(Collectors.toMap(item -> item.formId, item -> item));
        List<BillingQuoteVO> items = new ArrayList<>();
        for (Long formId : plan.formIds())
        {
            FormImageValidated validated = byId.get(formId);
            boolean deferredReference = plan.initialAssetIds().contains(validated.asset.getId());
            FormImageMediaBillingPlan mediaPlan = buildFormImageMediaBillingPlan(null,
                    validated.form, validated.asset, validated.project, request.getAgentCode(), plan.modelCode(),
                    plan.resolution(), plan.aspectRatio(), userId, deferredReference);
            BillingQuoteVO item = mediaPlan.estimated()
                    ? mediaBillingQuoteService.quotePlannedImage(
                            "FORM_IMAGE", mediaPlan.request(), 1)
                    : mediaBillingQuoteService.quoteImage(
                            "FORM_IMAGE", mediaPlan.request(), 1);
            items.add(item);
        }
        return billingQuoteAssembler.aggregate("FORM_IMAGE", items);
    }

    private FormImageBatchBillingPlan prepareFormImageBatchBillingPlan(List<Long> formIds,
            Long userId, String agentCode, String requestedModelCode,
            String requestedResolution, String requestedAspectRatio)
    {
        if (CollectionUtil.isEmpty(formIds))
        {
            throw new ServiceException("参数错误");
        }
        if (formIds.size() > 100)
        {
            throw new ServiceException("批量过多");
        }
        List<Long> uniqueFormIds = formIds.stream().distinct().collect(Collectors.toList());
        Map<Long, AidRolePropSceneForm> batchFormMap = loadBatchFormsById(uniqueFormIds, userId);
        Set<Long> initialAssetIds = collectInitialFormAssetIds(batchFormMap);
        Map<Long, AidRolePropScene> assetCache = new HashMap<>();
        Map<Long, AidComicProject> projectCache = new HashMap<>();
        List<FormImageValidated> validatedList = new ArrayList<>();
        for (Long formId : uniqueFormIds)
        {
            validatedList.add(validateSingleFormImage(formId, batchFormMap.get(formId), userId,
                    initialAssetIds, assetCache, projectCache));
        }
        assertAgentForFormImageBatch(agentCode, validatedList);
        FormImageValidated first = validatedList.get(0);
        ProjectGenConfigScene scene = mapAssetTypeToImageScene(first.asset.getAssetType());
        com.aid.projectgenconfig.service.ResolvedSceneConfig resolved =
                projectGenConfigResolver.resolve(first.asset.getProjectId(), userId, scene,
                        agentCode, requestedModelCode, requestedResolution, requestedAspectRatio);
        String modelCode = resolved.getModelCode();
        String resolution = resolved.getResolution();
        String aspectRatio = resolved.getAspectRatio();
        AgentModelDefault agentModel = new AgentModelDefault(modelCode);
        for (FormImageValidated item : validatedList)
        {
            String finalPrompt = buildFormImagePrompt(
                    item.project, item.asset, item.form, agentCode, aspectRatio);
            if (StrUtil.isBlank(finalPrompt))
            {
                log.error("形态图生成失败，最终提示词为空: formId={}, agentCode={}",
                        item.formId, agentCode);
                throw new ServiceException("模板异常");
            }
            item.modelCode = modelCode;
            item.agentModel = agentModel;
            item.finalPrompt = finalPrompt;
            item.aspectRatio = aspectRatio;
            item.resolution = resolution;
        }
        List<Long> ordered = orderFormIdsInitialFirst(uniqueFormIds, batchFormMap, assetCache);
        return new FormImageBatchBillingPlan(List.copyOf(ordered),
                List.copyOf(validatedList), Set.copyOf(initialAssetIds),
                modelCode, resolution, aspectRatio);
    }

    private record FormImageBatchBillingPlan(List<Long> formIds,
            List<FormImageValidated> validatedList, Set<Long> initialAssetIds,
            String modelCode, String resolution, String aspectRatio)
    {
    }

    /**
     * 单个 form 的前置校验结果（校验通过后的数据快照）。
     * 仅用于 generateFormImage / batchGenerateFormImage 内部传递。
     */
    private static class FormImageValidated
    {
        Long formId;
        AidRolePropSceneForm form;
        AidRolePropScene asset;
        AidComicProject project;
        AgentModelDefault agentModel;
        String modelCode;
        String finalPrompt;
        String aspectRatio;
        /** 清晰度档位（如 1K / 2K / 4K），由项目级配置解析器解析后回填，下发到 imageRequest.size */
        String resolution;
    }

    /**
     * 资产类型 → 形态描述场景枚举映射。
     */
    private static ProjectGenConfigScene mapAssetTypeToFormScene(String assetType)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return ProjectGenConfigScene.CHARACTER_FORM;
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return ProjectGenConfigScene.SCENE_FORM;
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return ProjectGenConfigScene.PROP_FORM;
        }
        return null;
    }

    /**
     * 资产类型 → 形态图场景枚举映射。
     */
    private static ProjectGenConfigScene mapAssetTypeToImageScene(String assetType)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return ProjectGenConfigScene.CHARACTER_IMAGE;
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return ProjectGenConfigScene.SCENE_IMAGE;
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return ProjectGenConfigScene.PROP_IMAGE;
        }
        return null;
    }

    /**
     * 资产类型 → 形态图智能体业务分类编码映射。
     */
    private static String mapAssetTypeToImageBizCategory(String assetType)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return BIZ_CATEGORY_MAIN_CHARACTER_IMAGE;
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return BIZ_CATEGORY_MAIN_SCENE_IMAGE;
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return BIZ_CATEGORY_MAIN_PROP_IMAGE;
        }
        return null;
    }

    /**
     * 批量形态图生成时按 agentCode 断言业务分类。
     * 批内所有 form 关联资产的 assetType 必须一致；agentCode 的 biz_category_code 必须为对应映射值。
     *
     * @return 校验通过后的智能体（caller 用其做模型解析）
     */
    private AidAgent assertAgentForFormImageBatch(String agentCode, List<FormImageValidated> validatedList)
    {
        String batchAssetType = null;
        for (FormImageValidated v : validatedList)
        {
            String type = v.asset.getAssetType();
            if (StrUtil.isBlank(type))
            {
                log.error("批量形态图生成失败：资产类型为空, formId={}", v.formId);
                throw new ServiceException("资产类型不能为空");
            }
            if (batchAssetType == null)
            {
                batchAssetType = type;
            }
            else if (!Objects.equals(batchAssetType, type))
            {
                log.error("批量形态图生成失败：批内资产类型不一致, expected={}, got={}, formId={}",
                        batchAssetType, type, v.formId);
                throw new ServiceException("类型不一致");
            }
        }
        String expectedCategory = mapAssetTypeToImageBizCategory(batchAssetType);
        if (StrUtil.isBlank(expectedCategory))
        {
            log.error("批量形态图生成失败：未知资产类型 {}", batchAssetType);
            throw new ServiceException("类型不支持");
        }
        AidAgent agent = aidAgentService.getByAgentCodeAndAssertBizCategory(agentCode, expectedCategory);
        if (StrUtil.isBlank(agent.getPromptContent()))
        {
            log.error("批量形态图生成失败：智能体提示词为空, agentCode={}", agentCode);
            throw new ServiceException("模板异常");
        }
        return agent;
    }

    /**
     * 校验单个 form（批量场景）：形态存在、归属、资产、项目、风格、提示词，并按需做变体形态基准图前置校验。
     *
     * @param preloadedForm      已批量预加载的 form（命中复用；为 null 时回退 getById）
     * @param deferrableAssetIds 本批内"初始形象基准形态在场"的角色 assetId 集合（命中则变体放行，可空）
     * @param assetCache         asset 复用缓存（可空，为 null 时直接查库）
     * @param projectCache       project 复用缓存（可空，为 null 时直接查库）
     */
    private FormImageValidated validateSingleFormImage(Long formId, AidRolePropSceneForm preloadedForm, Long userId,
                                                       Set<Long> deferrableAssetIds,
                                                       Map<Long, AidRolePropScene> assetCache,
                                                       Map<Long, AidComicProject> projectCache)
    {
        AidRolePropSceneForm form = Objects.nonNull(preloadedForm) ? preloadedForm : rpsFormService.getById(formId);
        if (Objects.isNull(form) || !Objects.equals(DEL_FLAG_NORMAL, form.getDelFlag()))
        {
            log.info("形态图生成失败，形态不存在: formId={}", formId);
            throw new ServiceException("形态不存在");
        }
        if (!Objects.equals(userId, form.getUserId()))
        {
            log.info("形态图生成失败，形态不属于当前用户: formId={}, userId={}", formId, userId);
            throw new ServiceException("形态不存在");
        }

        AidRolePropScene asset = Objects.nonNull(assetCache)
                ? assetCache.computeIfAbsent(form.getAssetId(), aid -> rpsService.getById(aid))
                : rpsService.getById(form.getAssetId());
        if (Objects.isNull(asset))
        {
            log.info("形态图生成失败，主表资产不存在: assetId={}", form.getAssetId());
            throw new ServiceException("资产不存在");
        }

        // 同项目多形态复用项目缓存。
        AidComicProject project = Objects.nonNull(projectCache)
                ? projectCache.computeIfAbsent(asset.getProjectId(), pid -> projectService.selectAidComicProjectById(pid))
                : projectService.selectAidComicProjectById(asset.getProjectId());
        if (Objects.isNull(project))
        {
            log.info("形态图生成失败，项目不存在: projectId={}", asset.getProjectId());
            throw new ServiceException("项目不存在");
        }

        requireProjectStyle(project);

        String promptText = form.getPromptText();
        if (StrUtil.isBlank(promptText))
        {
            log.info("形态图生成失败，提示词为空: formId={}", formId);
            throw TaskErrorPresentation.fromCode(TaskErrorCode.USER_INPUT_EMPTY, "请填写提示词");
        }

        // 角色变体形态必须具备初始形象基准图。
        if (isCharacterVariantForm(asset, form)
                && CollectionUtil.isNotEmpty(deferrableAssetIds)
                && deferrableAssetIds.contains(asset.getId()))
        {
            // 同批内含「初始形象」，基准图将在消费阶段优先生成，跳过同步硬校验
            log.info("批量形态图变体形态基准图延迟生成（初始形象在同批内）: formId={}, assetId={}", formId, asset.getId());
        }
        else
        {
            resolveInheritedBaseImageUrl(asset, form, userId);
        }

        // 打包校验结果（modelCode / agentModel 由外层批处理统一解析后回填）
        FormImageValidated v = new FormImageValidated();
        v.formId = formId;
        v.form = form;
        v.asset = asset;
        v.project = project;
        v.agentModel = null;
        v.modelCode = null;
        v.finalPrompt = null;
        // 默认值仅作占位，实际 aspectRatio / resolution 由项目级配置解析器在 batchGenerateFormImage 阶段回填
        v.aspectRatio = null;
        v.resolution = null;
        return v;
    }

    /**
     * 判断该形态是否为「角色变体形态」（需要继承初始形象基准图做图生图锁人设）。
     * 仅 character 类型、且 change_reason 清洗后不等于「初始形象」才算变体；
     * 场景 / 道具 / 初始形象本身均返回 false（走纯文生图，不需要基准图）。
     */
    private boolean isCharacterVariantForm(AidRolePropScene asset, AidRolePropSceneForm form)
    {
        if (Objects.isNull(asset) || !Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType()))
        {
            return false;
        }
        // 用清洗后的 change_reason 比对，历史脏值（如「初始形象，白衣佩剑」）也能正确识别为初始形象锚点
        return !Objects.equals(INITIAL_FORM_CHANGE_REASON,
                sanitizeFormLabel(StrUtil.trimToEmpty(form.getChangeReason())));
    }

    /**
     * 一次性加载本批全部 form 并按 id 建索引。
     * 按 {@code userId} + {@code del_flag} 过滤，命中即代表存在且归属当前用户；未命中（map 无此 id）
     * 由后续校验判定为「形态不存在」。供整批校验 / 排序 / 初始形象在场判定复用，避免重复查库。
     */
    private Map<Long, AidRolePropSceneForm> loadBatchFormsById(List<Long> formIds, Long userId)
    {
        Map<Long, AidRolePropSceneForm> map = new HashMap<>();
        if (CollectionUtil.isEmpty(formIds))
        {
            return map;
        }
        LambdaQueryWrapper<AidRolePropSceneForm> q = Wrappers.lambdaQuery();
        q.in(AidRolePropSceneForm::getId, formIds)
                .eq(AidRolePropSceneForm::getUserId, userId)
                .eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        List<AidRolePropSceneForm> forms = rpsFormService.list(q);
        if (CollectionUtil.isNotEmpty(forms))
        {
            for (AidRolePropSceneForm f : forms)
            {
                map.put(f.getId(), f);
            }
        }
        return map;
    }

    /**
     * 从已加载的 form 集合内存计算「初始形象基准形态在场」的 assetId 集合（不额外查库）。
     */
    private Set<Long> collectInitialFormAssetIds(Map<Long, AidRolePropSceneForm> formMap)
    {
        Set<Long> result = new HashSet<>();
        if (Objects.isNull(formMap) || formMap.isEmpty())
        {
            return result;
        }
        for (AidRolePropSceneForm f : formMap.values())
        {
            if (Objects.isNull(f) || Objects.isNull(f.getAssetId()))
            {
                continue;
            }
            // change_reason 清洗后等于「初始形象」即视为基准形态在场
            if (Objects.equals(INITIAL_FORM_CHANGE_REASON,
                    sanitizeFormLabel(StrUtil.trimToEmpty(f.getChangeReason()))))
            {
                result.add(f.getAssetId());
            }
        }
        return result;
    }

    /**
     * 批量形态图初始形象优先排序：同角色「初始形象」基准形态排到其变体形态之前。
     */
    private List<Long> orderFormIdsInitialFirst(List<Long> formIds, Map<Long, AidRolePropSceneForm> formMap,
                                                Map<Long, AidRolePropScene> assetCache)
    {
        if (CollectionUtil.isEmpty(formIds) || formIds.size() == 1)
        {
            return formIds;
        }
        List<Long> ordered = new ArrayList<>(formIds);
        // 稳定排序：assetId 聚合在前，组内 character 初始形象(0)先于其余(1)；缺失数据兜底排到末尾、保持原相对顺序
        ordered.sort(Comparator
                .comparing((Long id) -> {
                    AidRolePropSceneForm f = formMap.get(id);
                    return Objects.nonNull(f) && Objects.nonNull(f.getAssetId()) ? f.getAssetId() : Long.MAX_VALUE;
                })
                .thenComparingInt(id -> isCharacterInitialForm(formMap.get(id), assetCache) ? 0 : 1));
        return ordered;
    }

    /**
     * 判断 form 是否为「角色初始形象」基准形态：资产类型为 character 且 change_reason 清洗后等于「初始形象」。
     * 资产类型经 {@code assetCache} 查得（命中复用，未命中再查库）；form / asset 缺失均返回 false。
     */
    private boolean isCharacterInitialForm(AidRolePropSceneForm form, Map<Long, AidRolePropScene> assetCache)
    {
        if (Objects.isNull(form) || Objects.isNull(form.getAssetId()))
        {
            return false;
        }
        AidRolePropScene asset = Objects.nonNull(assetCache)
                ? assetCache.computeIfAbsent(form.getAssetId(), aid -> rpsService.getById(aid))
                : rpsService.getById(form.getAssetId());
        if (Objects.isNull(asset) || !Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType()))
        {
            return false;
        }
        return Objects.equals(INITIAL_FORM_CHANGE_REASON,
                sanitizeFormLabel(StrUtil.trimToEmpty(form.getChangeReason())));
    }
    @Override
    public AssetExtractTaskVO batchGenerateCardImage(List<Long> imageIds, Long userId,
                                                     String agentCode, String requestedModelCode,
                                                     String requestedResolution, String requestedAspectRatio)
    {
        CardImageBatchBillingPlan plan = prepareCardImageBatchBillingPlan(imageIds, userId,
                agentCode, requestedModelCode, requestedResolution, requestedAspectRatio);
        List<Long> uniqueImageIds = plan.imageIds();
        List<CardImageValidated> validatedList = plan.validatedList();
        CardImageValidated first = validatedList.get(0);
        String modelCode = plan.modelCode();
        String resolution = plan.resolution();
        String aspectRatio = plan.aspectRatio();

        for (CardImageValidated validated : validatedList)
        {
            if (!Objects.equals(first.asset.getProjectId(), validated.asset.getProjectId())
                    || !Objects.equals(first.asset.getEpisodeId(), validated.asset.getEpisodeId()))
            {
                log.info("批量设定卡禁止跨项目剧集: userId={}, imageIds={}", userId, uniqueImageIds);
                throw new ServiceException("素材归属不一致");
            }
        }
        BatchTaskSlotReservation slotReservation = batchTaskSlotService.acquireTaskSlots(
                first.asset.getProjectId(), first.asset.getEpisodeId(),
                List.of(BatchTaskLogicalType.FORM_CARD_IMAGE_BATCH));

        List<String> acquiredLockKeys = new ArrayList<>();
        String itemLockToken = IdUtil.fastSimpleUUID();
        try
        {
            for (Long imageId : uniqueImageIds)
            {
                String cardLockKey = buildFormCardImageLockKey(imageId);
                // TTL 对齐僵尸阈值（form_card_image_batch=30min）：批量串行出图整体耗时可能远超数分钟，
                // 排队等待的 imageId 锁若中途过期会留下重复提交窗口，故锁需覆盖整批最长可运行时长。
                Boolean cardLocked = redisCache.redisTemplate.opsForValue()
                        .setIfAbsent(cardLockKey, itemLockToken, 1800, TimeUnit.SECONDS);
                if (cardLocked == null || !cardLocked)
                {
                    log.info("批量设定卡生成失败，角色源图已在处理中: imageId={}", imageId);
                    throw new ServiceException("任务处理中");
                }
                acquiredLockKeys.add(cardLockKey);
            }
        }
        catch (RuntimeException e)
        {
            // 回滚已获取的锁
            for (String key : acquiredLockKeys)
            {
                casDeleteExtractLockIfMatch(key, itemLockToken);
            }
            batchTaskSlotService.release(slotReservation);
            throw e;
        }

        AidExtractTask task = null;
        try
        {
            task = new AidExtractTask();
            task.setProjectId(first.asset.getProjectId());
            task.setEpisodeId(first.asset.getEpisodeId());
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_FORM_CARD_IMAGE_BATCH);
            task.setModelCode(modelCode);
            // inputSnapshot 存整批参数（JSON）
            Map<String, Object> inputMap = new LinkedHashMap<>();
            inputMap.put("imageIds", uniqueImageIds);
            inputMap.put("modelCode", modelCode);
            inputMap.put("agentCode", agentCode);
            inputMap.put("itemLockToken", itemLockToken);
            batchTaskSlotService.attachSnapshotMetadata(inputMap, slotReservation);
            // 保存 resolution / aspectRatio，consumer 端按此下发图片请求
            if (StrUtil.isNotBlank(resolution))
            {
                inputMap.put("resolution", resolution);
            }
            if (StrUtil.isNotBlank(aspectRatio))
            {
                inputMap.put("aspectRatio", aspectRatio);
            }
            task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
            task.setStatus(TASK_STATUS_PENDING);
            task.setTotalCount(uniqueImageIds.size());
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            extractTaskService.save(task);

            // 双模式派发统一收口：MQ 开发 MQ；MQ 关走本地线程
            final Long taskId = task.getId();
            boolean enqueued = dualModeTaskDispatcher.dispatch(taskId,
                    first.asset.getProjectId(), first.asset.getEpisodeId(),
                    userId, modelCode, TASK_TYPE_FORM_CARD_IMAGE_BATCH,
                    buildFormBatchLocalJob(taskId, userId, LOCAL_SPEC_FORM_CARD,
                            () -> doFormCardImageBatch(taskId, userId,
                                    taskQueueService.currentLocalDispatchToken(taskId))));
            if (!enqueued)
            {
                log.warn("批量设定卡生成入队失败(可能已取消): taskId={}", task.getId());
                throw new ServiceException("任务提交失败");
            }

            log.info("批量设定卡生成父任务创建成功: taskId={}, userId={}, imageIds={}", task.getId(), userId, uniqueImageIds);
            return AssetExtractTaskVO.builder()
                    .taskId(task.getId())
                    .status(TASK_STATUS_PENDING)
                    .build();
        }
        catch (Exception e)
        {
            log.error("批量设定卡生成父任务创建失败，回滚所有锁: userId={}", userId, e);
            boolean canRelease = Objects.isNull(task) || Objects.isNull(task.getId())
                    || closeFormBatchSubmissionFailure(task, userId, false);
            if (canRelease)
            {
                for (String key : acquiredLockKeys)
                {
                    casDeleteExtractLockIfMatch(key, itemLockToken);
                }
            }
            if (Objects.isNull(task) || Objects.isNull(task.getId()))
            {
                batchTaskSlotService.release(slotReservation);
            }
            throw new ServiceException("提交失败，请重试");
        }
    }

    @Override
    public BillingQuoteVO quoteCardImage(FormCardImageGenerateRequest request, Long userId)
    {
        CardImageBatchBillingPlan plan = prepareCardImageBatchBillingPlan(
                request.getImageIds(), userId, request.getAgentCode(), request.getModelCode(),
                request.getResolution(), request.getAspectRatio());
        List<BillingQuoteVO> items = new ArrayList<>();
        for (CardImageValidated validated : plan.validatedList())
        {
            CardImageMediaBillingPlan mediaPlan = buildCardImageMediaBillingPlan(null,
                    validated, request.getAgentCode(), plan.modelCode(), plan.resolution(), plan.aspectRatio(), userId);
            items.add(mediaBillingQuoteService.quoteImage(
                    "FORM_CARD_IMAGE", mediaPlan.request(), 1));
        }
        return billingQuoteAssembler.aggregate("FORM_CARD_IMAGE", items);
    }

    /** 父任务落库后的同步提交失败必须先持久化终态，再释放批量槽和项目锁。 */
    private boolean closeFormBatchSubmissionFailure(AidExtractTask task, Long userId,
                                                     boolean refundPreparedBilling)
    {
        AidExtractTask current = extractTaskService.selectAidExtractTaskById(task.getId());
        if (Objects.isNull(current))
        {
            batchTaskSlotService.releaseForTask(task);
            return true;
        }
        if (TASK_STATUS_PENDING.equals(current.getStatus()) || TASK_STATUS_QUEUED.equals(current.getStatus()))
        {
            LambdaUpdateWrapper<AidExtractTask> failed = Wrappers.lambdaUpdate();
            failed.eq(AidExtractTask::getId, current.getId());
            failed.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_QUEUED);
            if (StrUtil.isBlank(current.getBillingTraceId()))
            {
                failed.isNull(AidExtractTask::getBillingTraceId);
            }
            else
            {
                failed.eq(AidExtractTask::getBillingTraceId, current.getBillingTraceId());
            }
            failed.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
            failed.set(AidExtractTask::getErrorMessage, "任务提交失败")
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage("任务提交失败"));
            failed.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.getBaseMapper().update(null, failed);
            current = extractTaskService.selectAidExtractTaskById(task.getId());
        }
        if (Objects.isNull(current))
        {
            batchTaskSlotService.releaseForTask(task);
            return true;
        }
        boolean terminal = TASK_STATUS_SUCCEEDED.equals(current.getStatus())
                || TASK_STATUS_FAILED.equals(current.getStatus())
                || TASK_STATUS_PARTIAL_FAILED.equals(current.getStatus())
                || TASK_STATUS_CANCELLED.equals(current.getStatus());
        if (!terminal)
        {
            log.warn("批量形态任务提交失败补偿跳过活跃任务: taskId={}, status={}",
                    current.getId(), current.getStatus());
            return false;
        }
        if (refundPreparedBilling && StrUtil.isNotBlank(current.getBillingTraceId()))
        {
            try
            {
                extractBillingService.refundBilling(current.getId(), userId, current.getBillingTraceId());
            }
            catch (Exception refundEx)
            {
                log.error("批量形态任务提交失败退款异常，等待补偿: taskId={}", current.getId(), refundEx);
            }
        }
        batchTaskSlotService.releaseForTask(current);
        return true;
    }

    private CardImageBatchBillingPlan prepareCardImageBatchBillingPlan(List<Long> imageIds,
            Long userId, String agentCode, String requestedModelCode,
            String requestedResolution, String requestedAspectRatio)
    {
        if (CollectionUtil.isEmpty(imageIds))
        {
            throw new ServiceException("参数错误");
        }
        if (imageIds.size() > 100)
        {
            throw new ServiceException("批量过多");
        }
        AidAgent agent = aidAgentService.getByAgentCodeAndAssertBizCategory(
                agentCode, BIZ_CATEGORY_MAIN_CHARACTER_CARD_IMAGE);
        if (StrUtil.isBlank(agent.getPromptContent()))
        {
            log.error("批量设定卡生成失败：智能体提示词为空, agentCode={}", agentCode);
            throw new ServiceException("模板异常");
        }
        List<Long> uniqueImageIds = imageIds.stream().distinct().collect(Collectors.toList());
        List<CardImageValidated> validatedList = new ArrayList<>();
        for (Long imageId : uniqueImageIds)
        {
            validatedList.add(validateSingleCardImage(imageId, userId));
        }
        CardImageValidated first = validatedList.get(0);
        Long projectId = first.project.getId();
        for (CardImageValidated item : validatedList)
        {
            if (!Objects.equals(projectId, item.project.getId()))
            {
                throw new ServiceException("需同一项目");
            }
        }
        com.aid.projectgenconfig.service.ResolvedSceneConfig resolved =
                projectGenConfigResolver.resolve(projectId, userId,
                        ProjectGenConfigScene.CHARACTER_CARD_IMAGE,
                        agentCode, requestedModelCode, requestedResolution, requestedAspectRatio);
        return new CardImageBatchBillingPlan(List.copyOf(uniqueImageIds),
                List.copyOf(validatedList), resolved.getModelCode(),
                resolved.getResolution(), resolved.getAspectRatio());
    }

    private record CardImageBatchBillingPlan(List<Long> imageIds,
            List<CardImageValidated> validatedList, String modelCode,
            String resolution, String aspectRatio)
    {
    }

    /**
     * 单张角色源图设定卡生成的前置校验（提交阶段整批 fail-fast + 消费阶段重载共用）。
     * 校验链：源图存在/归属/来源(ai_auto、upload 或 ai_edit_chat)/URL → 资产存在/归属/角色类型 → 形态存在 →
     * 三者关系一致 → 项目存在/归属/有画风。校验不通过直接抛美化异常。
     */
    private CardImageValidated validateSingleCardImage(Long imageId, Long userId)
    {
        AidRolePropSceneFormImage sourceImage = rpsFormImageService.getById(imageId);
        if (Objects.isNull(sourceImage) || !Objects.equals(DEL_FLAG_NORMAL, sourceImage.getDelFlag()))
        {
            log.info("设定卡生成失败，图片不存在: imageId={}", imageId);
            throw new ServiceException("图片不存在");
        }
        if (!Objects.equals(userId, sourceImage.getUserId()))
        {
            log.info("设定卡生成失败，图片不属于当前用户: imageId={}, userId={}", imageId, userId);
            throw new ServiceException("图片不存在");
        }
        if (!supportsCardImageSource(sourceImage.getSourceType()))
        {
            log.info("设定卡生成失败，输入图片来源不支持: imageId={}, sourceType={}", imageId, sourceImage.getSourceType());
            throw new ServiceException("图片来源不支持");
        }
        if (StrUtil.isBlank(sourceImage.getImageUrl()))
        {
            log.info("设定卡生成失败，角色源图URL为空: imageId={}", imageId);
            throw new ServiceException("图片不可用");
        }
        AidRolePropScene asset = rpsService.getById(sourceImage.getAssetId());
        if (Objects.isNull(asset) || !Objects.equals(DEL_FLAG_NORMAL, asset.getDelFlag()))
        {
            log.info("设定卡生成失败，主资产不存在: assetId={}", sourceImage.getAssetId());
            throw new ServiceException("资产不存在");
        }
        if (!Objects.equals(userId, asset.getUserId()))
        {
            log.info("设定卡生成失败，资产不属于当前用户: assetId={}, userId={}", asset.getId(), userId);
            throw new ServiceException("资产不存在");
        }
        if (!Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType()))
        {
            log.info("设定卡生成失败，仅支持角色类型: assetType={}, assetId={}", asset.getAssetType(), asset.getId());
            throw new ServiceException("仅支持角色");
        }
        AidRolePropSceneForm form = rpsFormService.getById(sourceImage.getFormId());
        if (Objects.isNull(form) || !Objects.equals(DEL_FLAG_NORMAL, form.getDelFlag()))
        {
            log.info("设定卡生成失败，形态不存在: formId={}", sourceImage.getFormId());
            throw new ServiceException("形态不存在");
        }
        // 校验图片、形态、主资产关系一致性。
        if (!Objects.equals(sourceImage.getFormId(), form.getId())
                || !Objects.equals(sourceImage.getAssetId(), asset.getId())
                || !Objects.equals(form.getAssetId(), asset.getId()))
        {
            log.error("设定卡生成失败，数据关系不一致: imageId={}, image.formId={}, form.id={}, image.assetId={}, form.assetId={}, asset.id={}",
                    imageId, sourceImage.getFormId(), form.getId(), sourceImage.getAssetId(), form.getAssetId(), asset.getId());
            throw new ServiceException("数据异常");
        }
        AidComicProject project = projectService.selectAidComicProjectById(asset.getProjectId());
        if (Objects.isNull(project) || !Objects.equals(DEL_FLAG_NORMAL, project.getDelFlag()))
        {
            log.info("设定卡生成失败，项目不存在: projectId={}", asset.getProjectId());
            throw new ServiceException("项目不存在");
        }
        if (!Objects.equals(userId, project.getUserId()))
        {
            log.info("设定卡生成失败，项目不属于当前用户: projectId={}, userId={}", project.getId(), userId);
            throw new ServiceException("项目不存在");
        }
        requireProjectStyle(project);

        CardImageValidated v = new CardImageValidated();
        v.imageId = imageId;
        v.sourceImage = sourceImage;
        v.form = form;
        v.asset = asset;
        v.project = project;
        return v;
    }

    static boolean supportsCardImageSource(String sourceType)
    {
        return Objects.nonNull(sourceType) && CARD_IMAGE_SOURCE_TYPES.contains(sourceType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String doFormCardImageBatch(Long taskId, Long userId, String dispatchToken)
    {
        String billingTraceId = requireCurrentBillingTrace(taskId, dispatchToken);
        Map<String, Object> input = parseBatchInput(taskId);
        List<Long> imageIds = parseIdList(input, "imageIds");
        String itemLockToken = resolveItemLockToken(input);
        // 父任务已存好解析后的 modelCode / resolution / aspectRatio，consumer 直接用
        String modelCode = String.valueOf(input.getOrDefault("modelCode", ""));
        String agentCode = Objects.toString(input.get("agentCode"), null);
        String resolution = StrUtil.trim(String.valueOf(input.getOrDefault("resolution", "")));
        String aspectRatio = StrUtil.trim(String.valueOf(input.getOrDefault("aspectRatio", "")));
        int runTotal = imageIds.size();
        int total = resolveResumeOriginalTotal(input, runTotal);

        List<Map<String, Object>> successItems = parseResumeSeedSuccessItems(input);
        List<Map<String, Object>> failedItems = new ArrayList<>();
        int successCount = successItems.size();
        int failCount = 0;

        for (int i = 0; i < runTotal; i++)
        {
            Long imageId = imageIds.get(i);
            String lockKey = buildFormCardImageLockKey(imageId);

            // ★ 取消检查点：剩余项的锁全部释放后跳出
            if (isTaskCancelled(taskId))
            {
                log.info("批量设定卡生成被取消: taskId={}, done={}/{}", taskId, i, runTotal);
                for (int j = i; j < runTotal; j++)
                {
                    releaseBatchItemLock(taskId, buildFormCardImageLockKey(imageIds.get(j)), itemLockToken);
                }
                break;
            }

            int progress = 10 + (int) ((i * 80.0) / runTotal);
            sseManager.sendStepProgress(taskId, "form_card_image_gen", progress,
                    "card_" + imageId, "正在生成角色设定卡 " + (i + 1) + "/" + runTotal, i + 1, runTotal);

            try
            {
                // 执行单项设定卡生成（角色源图 → 设定卡）
                Map<String, Object> resultItem = executeSingleCardImageInternal(
                        taskId, imageId, agentCode, modelCode, resolution, aspectRatio, userId,
                        billingTraceId);
                successItems.add(resultItem);
                successCount++;
                log.info("批量设定卡单项完成: taskId={}, imageId={}, cardImageUrl={}", taskId, imageId, resultItem.get("cardImageUrl"));
            }
            catch (BatchTaskExecutionRejectedException rejected)
            {
                for (int j = i + 1; j < runTotal; j++)
                {
                    releaseBatchItemLock(taskId, buildFormCardImageLockKey(imageIds.get(j)), itemLockToken);
                }
                throw rejected;
            }
            catch (Exception e)
            {
                log.error("批量设定卡单项失败: taskId={}, imageId={}", taskId, imageId, e);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("imageId", imageId);
                item.put("message", TaskErrorPresentation.toUserMessage(
                        modelCode, e.getMessage(), "图片生成失败"));
                failedItems.add(item);
                failCount++;
            }
            finally
            {
                releaseBatchItemLock(taskId, lockKey, itemLockToken);
            }
        }

        settleFormBatchDispatchCycle(taskId, userId, TASK_TYPE_FORM_CARD_IMAGE_BATCH, dispatchToken);
        return buildBatchResultJson(total, successCount, failCount, successItems, failedItems);
    }

    /**
     * 单项设定卡生成内部逻辑（由 doFormCardImageBatch 调用，不创建子任务）。
     * 从 imageId 重新加载并校验角色源图 / 资产 / 形态 / 项目（与提交阶段同口径），
     * 父任务已解析好 modelCode + resolution + aspectRatio，此处直接使用。
     * 图片计费由媒体主链路按 userId 内部预冻结 / 结算。
     */
    private Map<String, Object> executeSingleCardImageInternal(Long parentTaskId, Long imageId,
                                                               String agentCode,
                                                               String resolvedModelCode,
                                                               String resolvedResolution,
                                                               String resolvedAspectRatio,
                                                               Long userId,
                                                               String expectedTraceId)
    {
        CardImageValidated v = validateSingleCardImage(imageId, userId);
        AidRolePropSceneFormImage sourceImage = v.sourceImage;
        AidRolePropSceneForm form = v.form;
        AidRolePropScene asset = v.asset;
        String modelCode = resolvedModelCode;
        CardImageMediaBillingPlan mediaPlan = buildCardImageMediaBillingPlan(parentTaskId,
                v, agentCode, modelCode, resolvedResolution, resolvedAspectRatio, userId);
        MediaImageGenerateRequest imageRequest = mediaPlan.request();
        String finalPrompt = mediaPlan.finalPrompt();
        List<String> refImageList = mediaPlan.referenceImages();

        // 调用图片生成
        MediaTaskResponse imageResponse = batchParentSubmissionGuard.executeManagedTask(
                parentTaskId, expectedTraceId,
                () -> mediaGenerationService.generateImage(imageRequest));
        return batchParentSubmissionGuard.executeManagedBusinessCommit(parentTaskId, expectedTraceId, () -> {
            String cardImageUrl = resolveImageUrl(imageResponse, modelCode);
            if (StrUtil.isBlank(cardImageUrl))
            {
                log.error("设定卡生成失败，图片URL为空: imageId={}", imageId);
                throw new ServiceException("图片生成失败");
            }
            // 写入 aid_role_prop_scene_form_image：sourceType = ai_builder
            Long cardImageId = persistCardFormImage(
                    form, asset, cardImageUrl, finalPrompt, refImageList,
                    sourceImage.getSourceType(), parentTaskId, userId);
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("sourceImageId", imageId);
            resultMap.put("imageId", imageId);
            resultMap.put("cardImageId", cardImageId);
            resultMap.put("cardImageUrl", toClientMediaUrl(cardImageUrl));
            resultMap.put("formId", form.getId());
            return resultMap;
        });
    }

    private CardImageMediaBillingPlan buildCardImageMediaBillingPlan(Long bizTaskId,
            CardImageValidated validated, String agentCode, String modelCode, String resolution,
            String aspectRatio, Long userId)
    {
        AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(modelCode,
                helper.businessFunctionForAgent(agentCode), "image_to_image");
        ModelCapabilityResolver.ImageSizeSpec sizeSpec = ModelCapabilityResolver.resolveImageSpec(
                modelConfig, resolution, aspectRatio);
        String finalPrompt = buildCardImagePrompt(
                validated.project, sizeSpec.aspectRatio(), agentCode);
        if (StrUtil.isBlank(finalPrompt))
        {
            throw new ServiceException("模板异常");
        }
        String sourceUrl = mediaUrlResolver.toFullUrl(validated.sourceImage.getImageUrl());
        List<String> referenceImages = sanitizeReferenceImages(
                List.of(sourceUrl), validated.form.getId());

        MediaImageGenerateRequest request = new MediaImageGenerateRequest();
        request.setModelName(modelConfig.getModelCode());
        request.setBusinessFuncCode(helper.businessFunctionForAgent(agentCode));
        request.setCapabilityCode(modelConfig.getCapabilityCode());
        request.setUserId(userId);
        request.setPrompt(finalPrompt);
        request.setTaskPromptDigest(buildCardImagePromptDigest(
                validated.project, sizeSpec.aspectRatio()));
        Map<String, Object> options = new HashMap<>();
        if (referenceImages.size() == 1)
        {
            request.setReferenceImageUrl(referenceImages.get(0));
        }
        else if (referenceImages.size() > 1)
        {
            options.put("referenceImages", referenceImages);
        }
        options.put("force_single", true);
        if (StrUtil.isNotBlank(sizeSpec.aspectRatio()))
        {
            options.put("aspect_ratio", sizeSpec.aspectRatio());
        }
        request.setOptions(options);
        request.setSize(sizeSpec.size());
        request.setExpectedImageCount(1);
        request.setBizTaskId(bizTaskId);
        request.setBizTaskType(TASK_TYPE_FORM_CARD_IMAGE_BATCH);
        agentDefaultParamsApplier.applyToImage(
                new AgentModelDefault(modelCode), request, modelConfig);
        return new CardImageMediaBillingPlan(
                request, finalPrompt, List.copyOf(referenceImages));
    }

    private record CardImageMediaBillingPlan(MediaImageGenerateRequest request,
            String finalPrompt, List<String> referenceImages)
    {
    }

    /**
     * 单张角色源图设定卡校验快照（提交 fail-fast 与消费阶段共用）。
     */
    private static class CardImageValidated
    {
        Long imageId;
        AidRolePropSceneFormImage sourceImage;
        AidRolePropSceneForm form;
        AidRolePropScene asset;
        AidComicProject project;
    }

    /**
     * 基于角色源图和所选智能体组装设定卡 prompt。
     * 【参考图驱动】人物身份完全依赖角色源图参考图锚定，不拼接 form.promptText。
     * 仅保留：所选智能体模板正文 + 完整角色画风提示词。
     */
    private String buildCardImagePrompt(AidComicProject project, String aspectRatio)
    {
        return buildCardImagePrompt(project, aspectRatio, null);
    }

    private String buildCardImagePrompt(AidComicProject project, String aspectRatio, String agentCode)
    {
        String effectiveAspectRatio = StrUtil.blankToDefault(aspectRatio, "16:9");
        String promptName = StrUtil.blankToDefault(
                agentCode, PROMPT_NAME_CHARACTER_FORM_IMAGE_BUILDER);
        String template = StrUtil.blankToDefault(
                helper.loadPromptByNameReadOnly(promptName), "")
                .replace("{aspect_ratio}", effectiveAspectRatio)
                .replace("21:9", effectiveAspectRatio)
                .replace("21：9", effectiveAspectRatio);

        String artStylePrompt = projectStyleSnapshotService.resolveCharacterPrompt(project);

        StringBuilder sb = new StringBuilder(template);
        if (sb.length() > 0 && !sb.toString().endsWith("\n"))
        {
            sb.append("\n");
        }
        sb.append("画布比例：").append(effectiveAspectRatio)
                .append("（以本次生成配置为唯一标准，模板中的其他比例描述无效）\n")
                .append("[art_style_prompt]\n").append(artStylePrompt);

        return sb.toString();
    }

    /**
     * 设定卡任务存档摘要（aid_media_task.prompt 列内容）。
     * 仅打包动态入参,跳过 aid_character_form_image_builder 模板正文。
     */
    private String buildCardImagePromptDigest(AidComicProject project, String aspectRatio)
    {
        String artStylePrompt = Objects.nonNull(project)
                ? StrUtil.blankToDefault(project.getVideoStyleValue(), "") : "";
        return new StringBuilder()
                .append("[aspect_ratio]\n").append(StrUtil.blankToDefault(aspectRatio, "16:9"))
                .append("\n[art_style_prompt]\n").append(artStylePrompt)
                .toString();
    }

    /**
     * 形态图任务存档摘要(aid_media_task.prompt 列内容)。
     */
    private String buildFormImagePromptDigest(AidRolePropScene asset, AidRolePropSceneForm form)
    {
        String assetType = asset == null ? "" : StrUtil.blankToDefault(asset.getAssetType(), "");
        String assetName = asset == null ? "" : StrUtil.blankToDefault(asset.getName(), "");
        String promptText = form == null ? "" : StrUtil.blankToDefault(form.getPromptText(), "");

        StringBuilder sb = new StringBuilder();
        sb.append("[asset_type]\n").append(assetType);
        sb.append("\n[asset_name]\n").append(assetName);
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            // 角色形态图:动态入参 = 公开画风描述 + form.prompt_text 中的 descriptions
            String descriptions = resolveCharacterDescriptions(promptText);
            AidComicProject project = asset == null ? null : projectService.selectAidComicProjectById(asset.getProjectId());
            String artStylePrompt = Objects.nonNull(project)
                    ? StrUtil.blankToDefault(project.getVideoStyleValue(), "") : "";
            sb.append("\n[art_style_prompt]\n").append(artStylePrompt);
            sb.append("\n[form_prompt_text]\n").append(StrUtil.blankToDefault(descriptions, ""));
        }
        else
        {
            // 场景/道具形态图:动态入参 = stylist 输出 JSON 中提取的 prompt 字段
            // digest 仅用于审计存档，不影响主流程；用容错版本，解析失败时填空字符串
            String stylistPrompt = extractPromptFromStylistJsonForDigest(promptText);
            sb.append("\n[form_prompt_text]\n").append(StrUtil.blankToDefault(stylistPrompt, ""));
        }
        return sb.toString();
    }

    /**
     * 设定卡生成成功后落地一条 aid_role_prop_scene_form_image 实例。
     *
     * @return 新插入图片实例 id
     */
    private Long persistCardFormImage(AidRolePropSceneForm form,
                                      AidRolePropScene asset,
                                       String cardImageUrl,
                                       String finalPrompt,
                                       List<String> effectiveRefImages,
                                       String sourceImageType,
                                       Long taskId,
                                       Long userId)
    {
        LambdaQueryWrapper<AidRolePropSceneFormImage> existsQuery = Wrappers.lambdaQuery();
        existsQuery.eq(AidRolePropSceneFormImage::getFormId, form.getId());
        existsQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        existsQuery.select(AidRolePropSceneFormImage::getId);
        long existingCount = rpsFormImageService.count(existsQuery);

        String referenceImagesJson = null;
        if (CollectionUtil.isNotEmpty(effectiveRefImages))
        {
            try
            {
                referenceImagesJson = OBJECT_MAPPER.writeValueAsString(effectiveRefImages);
            }
            catch (Exception e)
            {
                log.warn("设定卡参考图序列化失败: formId={}, taskId={}, err={}",
                        form.getId(), taskId, e.getMessage());
            }
        }

        AidRolePropSceneFormImage img = new AidRolePropSceneFormImage();
        img.setFormId(form.getId());
        img.setAssetId(form.getAssetId());
        img.setProjectId(form.getProjectId());
        img.setEpisodeId(form.getEpisodeId());
        img.setUserId(userId);
        // name 命名：资产名_角色设定（仅 character 类型走设定卡接口）。
        // 命名约定：以 "_角色设定" 结尾的 form_image 在分镜编剧字典里会被特殊标注为
        // "多方位设定卡，可作为角色多角度参考图"，并在引用优先级上排在普通形态图前。
        String assetName = Objects.nonNull(asset) && StrUtil.isNotBlank(asset.getName()) ? asset.getName() : "资产";
        img.setName(assetName + CHARACTER_FORM_IMAGE_NAME_SUFFIX);
        img.setImageUrl(cardImageUrl);
        // sourceType = ai_builder：区分角色源图（ai_auto/upload/ai_edit_chat）与角色设定卡
        img.setSourceType("ai_builder");
        img.setDescriptionIndex(0);
        img.setPromptSnapshot(finalPrompt);
        img.setReferenceImages(referenceImagesJson);
        img.setBatchNo(Objects.nonNull(taskId) ? String.valueOf(taskId) : null);
        img.setSortOrder((int) existingCount);
        // 保留来源初始值用于兼容既有校验；统一规则会让新设定卡接管角色主图。
        img.setIsUse(resolveCardImageInitialIsUse(sourceImageType));
        img.setImageStatus("completed");
        img.setDelFlag(DEL_FLAG_NORMAL);
        img.setCreateTime(DateUtils.getNowDate());
        img.setCreateBy(String.valueOf(userId));
        formImageSelectionService.saveGeneratedImage(img, userId, true);

        return img.getId();
    }

    static int resolveCardImageInitialIsUse(String sourceImageType)
    {
        // 兼容来源初始值校验；最终状态由 IFormImageSelectionService 统一设为角色主图。
        return Objects.equals("upload", sourceImageType) ? 0 : 1;
    }

    /**
     * 自动生图成功后落地一条 aid_role_prop_scene_form_image 实例（替代回写 form.image_url，。
     *
     * @return 新插入图片实例 id
     */
    private Long persistAutoGeneratedFormImage(AidRolePropSceneForm form,
                                               AidRolePropScene asset,
                                               String imageUrl,
                                               String finalPrompt,
                                               List<String> effectiveRefImages,
                                               Long taskId,
                                               Long userId)
    {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.aid.aid.domain.AidRolePropSceneFormImage> existsQuery = Wrappers.lambdaQuery();
        existsQuery.eq(com.aid.aid.domain.AidRolePropSceneFormImage::getFormId, form.getId());
        existsQuery.eq(com.aid.aid.domain.AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        // 仅查必要字段（id）做 count 用；新增字段时如需统计请同步扩展
        existsQuery.select(com.aid.aid.domain.AidRolePropSceneFormImage::getId);
        long existingCount = rpsFormImageService.count(existsQuery);

        String referenceImagesJson = null;
        if (CollectionUtil.isNotEmpty(effectiveRefImages))
        {
            try
            {
                referenceImagesJson = OBJECT_MAPPER.writeValueAsString(effectiveRefImages);
            }
            catch (Exception e)
            {
                // 序列化失败仅 warn，不阻塞主流程
                log.warn("形态图参考图序列化失败: formId={}, taskId={}, err={}",
                        form.getId(), taskId, e.getMessage());
            }
        }

        com.aid.aid.domain.AidRolePropSceneFormImage img = new com.aid.aid.domain.AidRolePropSceneFormImage();
        img.setFormId(form.getId());
        img.setAssetId(form.getAssetId());
        img.setProjectId(form.getProjectId());
        img.setEpisodeId(form.getEpisodeId());
        img.setUserId(userId);
        // name 命名：直接复用 form.name（已经包含"资产名_变更原因"完整语义），
        // 不再拼接 change_reason，避免出现"林深_初始形象（X）_初始形象（X）"这种复读名字。
        // 同一 form 下多张图不在 name 上区分，由 sort_order / batch_no 区分；
        // 角色自动形态图使用 _角色设定 后缀，供下游识别为多方位参考图；
        // 场景/道具保持原名，拆分子图仍由独立分支追加方位后缀。
        String formName = StrUtil.isNotBlank(form.getName()) ? form.getName() : "形态";
        if (Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType())
                && !StrUtil.endWith(formName, CHARACTER_FORM_IMAGE_NAME_SUFFIX))
        {
            formName += CHARACTER_FORM_IMAGE_NAME_SUFFIX;
        }
        img.setName(formName);
        img.setImageUrl(imageUrl);
        img.setSourceType("ai_auto");
        img.setDescriptionIndex(0);
        img.setPromptSnapshot(finalPrompt);
        img.setReferenceImages(referenceImagesJson);
        img.setBatchNo(Objects.nonNull(taskId) ? String.valueOf(taskId) : null);
        img.setSortOrder((int) existingCount);
        img.setImageStatus("completed");
        img.setDelFlag(DEL_FLAG_NORMAL);
        // 审计字段：创建者 / 时间统一以当前 userId 为操作人
        img.setCreateTime(DateUtils.getNowDate());
        img.setCreateBy(String.valueOf(userId));
        formImageSelectionService.saveGeneratedImage(img, userId, true);

        return img.getId();
    }

    /**
     * 过滤参考图 URL：剔除空串、非 http/https 地址，保证传给下游的都是公网可访问链接。
     * 命中非法地址时只记录日志并跳过，不中断整体流程（可能还有其他合法图可用）。
     */
    private List<String> sanitizeReferenceImages(List<String> rawImages, Long formId)
    {
        if (CollectionUtil.isEmpty(rawImages))
        {
            return null;
        }
        List<String> result = new ArrayList<>(rawImages.size());
        for (String url : rawImages)
        {
            if (StrUtil.isBlank(url))
            {
                log.warn("剔除空参考图 URL: formId={}", formId);
                continue;
            }
            String trimmed = url.trim();
            String lower = trimmed.toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://"))
            {
                log.warn("剔除非法参考图 URL（非 http/https）: formId={}, url={}", formId, trimmed);
                continue;
            }
            result.add(trimmed);
        }
        return result.isEmpty() ? null : result;
    }

    /** 图片轮询最大等待时间（秒） */
    private static final long IMAGE_POLL_TIMEOUT_SECONDS = 180L;
    /** 图片轮询间隔（秒） */
    private static final long IMAGE_POLL_INTERVAL_SECONDS = 5L;

    /**
     * 从图片生成响应中获取最终 imageUrl。
     * 支持同步模型（直接 SUCCEEDED）和异步模型（PROCESSING → 通过媒体服务轮询远端）。
     */
    private String resolveImageUrl(MediaTaskResponse imageResponse, String modelCode)
    {
        if (Objects.isNull(imageResponse))
        {
            throw new ServiceException("图片生成失败");
        }

        if (Objects.equals(TASK_STATUS_SUCCEEDED, imageResponse.getStatus()))
        {
            if (StrUtil.isBlank(imageResponse.getOssUrl()))
            {
                log.error("图片同步成功但 ossUrl 为空: mediaTaskId={}, originUrl={}",
                        imageResponse.getTaskId(), imageResponse.getOriginUrl());
                throw new ServiceException("保存失败，请重试");
            }
            return imageResponse.getOssUrl();
        }

        //    注意：WAIT_POLL / INIT / PENDING / QUEUED / PROCESSING 都是合法的异步中间态，继续进入轮询。
        if (!IMAGE_IN_PROGRESS_STATUSES.contains(imageResponse.getStatus()))
        {
            String errorMsg = imageResponse.getErrorMessage();
            log.error("形态图生成失败: mediaTaskId={}, status={}, error={}",
                    imageResponse.getTaskId(), imageResponse.getStatus(), errorMsg);
            throw new ServiceException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
        }

        //    注意：queryTask(.., true) 当前实现已退化为「只读本地快照」，无法触发远端刷新与 OSS 修复，
        //    必须使用 queryTaskRefresh 走 refreshProcessingTask + persistOssIfNeeded 真实链路。
        Long mediaTaskId = imageResponse.getTaskId();
        if (mediaTaskId == null)
        {
            log.error("异步图片任务缺少 taskId, 无法轮询");
            throw new ServiceException("图片生成失败");
        }

        log.info("图片异步生成中, 开始轮询远端: mediaTaskId={}", mediaTaskId);
        long deadline = System.currentTimeMillis() + IMAGE_POLL_TIMEOUT_SECONDS * 1000L;

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(IMAGE_POLL_INTERVAL_SECONDS * 1000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new ServiceException("图片生成被中断");
            }

            // 通过媒体服务查询：查远端 + 刷本地 + SUCCEEDED 但 ossUrl 空时即时重试持久化
            MediaTaskResponse polled = mediaGenerationService.queryTaskRefresh(mediaTaskId);
            if (Objects.isNull(polled))
            {
                log.error("轮询图片任务返回空: mediaTaskId={}", mediaTaskId);
                throw new ServiceException("图片生成失败");
            }

            if (Objects.equals(TASK_STATUS_SUCCEEDED, polled.getStatus()))
            {
                // 业务表只允许写入本系统已持久化的 ossUrl，禁止回落上游临时 URL（会过期）。
                if (StrUtil.isBlank(polled.getOssUrl()))
                {
                    // 不立即报错：queryTaskRefresh 已对 SUCCEEDED+ossUrl 空触发即时重试持久化，
                    // 下一轮轮询若仍空再重试一次，仅当超时窗口耗尽才落到外层 "图片生成超时"。
                    log.warn("异步图片成功但 ossUrl 暂空，等待下一轮持久化: mediaTaskId={}, originUrl={}",
                            mediaTaskId, polled.getOriginUrl());
                    continue;
                }
                log.info("异步图片生成成功: mediaTaskId={}, url={}", mediaTaskId, polled.getOssUrl());
                return polled.getOssUrl();
            }
            if ("FAILED".equals(polled.getStatus()))
            {
                String errorMsg = polled.getErrorMessage();
                log.error("异步图片生成失败: mediaTaskId={}, error={}", mediaTaskId, errorMsg);
                throw new ServiceException(StrUtil.isNotBlank(errorMsg) ? errorMsg : "图片生成失败");
            }
            // 仍在 PROCESSING/QUEUED/PENDING，继续轮询
        }

        // 超时
        log.error("异步图片生成超时: mediaTaskId={}, timeout={}s", mediaTaskId, IMAGE_POLL_TIMEOUT_SECONDS);
        throw new ServiceException("图片生成超时");
    }
    /**
     * 形态图最终 prompt 组装入口：按资产类型分派到各自的构造方法
     *
     * @param project 项目
     * @param asset   主表资产
     * @param form    从表形态
     * @return 最终生图 prompt
     */
    private String buildFormImagePrompt(AidComicProject project, AidRolePropScene asset, AidRolePropSceneForm form)
    {
        return buildFormImagePrompt(project, asset, form, null, null);
    }

    private String buildFormImagePrompt(AidComicProject project, AidRolePropScene asset,
                                        AidRolePropSceneForm form, String agentCode,
                                        String aspectRatio)
    {
        String assetType = asset.getAssetType();
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return buildCharacterFormImagePrompt(project, asset, form, agentCode, aspectRatio);
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return buildSceneFormImagePrompt(project, asset, form);
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return buildPropFormImagePrompt(project, asset, form);
        }
        log.info("形态图生成失败，资产类型不支持: assetType={}, assetId={}", assetType, asset.getId());
        throw new ServiceException("类型不支持");
    }

    private static String mapAssetTypeToFormGenerateLogicalType(String assetType)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return BatchTaskLogicalType.FORM_GENERATE_CHARACTER_BATCH;
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return BatchTaskLogicalType.FORM_GENERATE_SCENE_BATCH;
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return BatchTaskLogicalType.FORM_GENERATE_PROP_BATCH;
        }
        log.error("形态生成逻辑槽映射失败: assetType={}", assetType);
        throw new ServiceException("类型不支持");
    }

    private static String mapAssetTypeToFormImageLogicalType(String assetType)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return BatchTaskLogicalType.FORM_IMAGE_CHARACTER_BATCH;
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return BatchTaskLogicalType.FORM_IMAGE_SCENE_BATCH;
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return BatchTaskLogicalType.FORM_IMAGE_PROP_BATCH;
        }
        log.error("形态图逻辑槽映射失败: assetType={}", assetType);
        throw new ServiceException("类型不支持");
    }

    /**
     * 人物形态图 txt2img prompt 组装。
     */
    private String buildCharacterFormImagePrompt(AidComicProject project, AidRolePropScene asset, AidRolePropSceneForm form)
    {
        return buildCharacterFormImagePrompt(project, asset, form, null, null);
    }

    private String buildCharacterFormImagePrompt(AidComicProject project, AidRolePropScene asset,
                                                 AidRolePropSceneForm form, String agentCode,
                                                 String aspectRatio)
    {
        String promptName = StrUtil.blankToDefault(agentCode, PROMPT_NAME_CHARACTER_FORM_IMAGE);
        String effectiveAspectRatio = StrUtil.trimToEmpty(aspectRatio);
        String template = StrUtil.blankToDefault(helper.loadPromptByNameReadOnly(promptName), "")
                .replace("{aspect_ratio}", effectiveAspectRatio);
        if (StrUtil.isNotBlank(effectiveAspectRatio))
        {
            template = template.replace("21:9", effectiveAspectRatio)
                    .replace("21：9", effectiveAspectRatio);
        }

        // 角色图片只使用项目角色隐藏画风，不注入 video_style_type 风格名称。
        String artStylePrompt = projectStyleSnapshotService.resolveCharacterPrompt(project);
        // descriptions 现在是单条字符串，直接从 prompt_text JSON 取 descriptions 字段；
        // 纯文本 / 解析失败则原文兜底
        String formPromptText = resolveCharacterDescriptions(form.getPromptText());

        StringBuilder sb = new StringBuilder(StrUtil.blankToDefault(template, ""));
        if (sb.length() > 0 && !sb.toString().endsWith("\n"))
        {
            sb.append("\n");
        }
        sb.append("人物提示词：").append("\n")
                .append(formPromptText)
                .append(CHARACTER_FORM_IMAGE_LAYOUT_CONSTRAINT);
        if (StrUtil.isNotBlank(effectiveAspectRatio))
        {
            sb.append("\n画布比例：").append(effectiveAspectRatio)
                    .append("（以本次生成配置为准）");
        }
        // 人物描述可能携带旧图片风格，项目隐藏风格必须最后追加并拥有最终约束权。
        if (sb.length() > 0 && !sb.toString().endsWith("\n"))
        {
            sb.append("\n");
        }
        sb.append("[art_style_prompt]\n").append(artStylePrompt);

        return sb.toString();
    }

    /**
     * 解析项目画风「风格名称」。
     * 取 {@code video_style_type}；为空、或属于旧协议占位（official / custom / ai_gen）、
     * 或为无意义脏值（纯数字 / 过短串）时一律返回空串，避免把"custom"这类内部枚举当风格名输出。
     */
    private String resolveProjectArtStyleName(AidComicProject project)
    {
        if (Objects.isNull(project))
        {
            return "";
        }
        String styleType = project.getVideoStyleType();
        if (StrUtil.isBlank(styleType))
        {
            return "";
        }
        String trimmed = styleType.trim();
        // 旧协议占位枚举：不是真正的风格名
        if (Objects.equals("official", trimmed)
                || Objects.equals("custom", trimmed)
                || Objects.equals("ai_gen", trimmed))
        {
            return "";
        }
        // 无意义脏值：纯数字 / 过短串
        if (trimmed.length() < 2 || trimmed.matches("\\d+"))
        {
            return "";
        }
        return trimmed;
    }

    /**
     * 从角色 form.prompt_text 中解析单个 descriptions 文本。
     * 结构化 JSON 取 descriptions 字段（字符串）；纯文本 / 解析失败原文兜底。
     */
    private String resolveCharacterDescriptions(String promptText)
    {
        if (StrUtil.isBlank(promptText))
        {
            return "";
        }
        String trimmed = promptText.trim();
        if (!trimmed.startsWith("{"))
        {
            return promptText;
        }
        try
        {
            com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            com.fasterxml.jackson.databind.JsonNode descs = root.path("descriptions");
            // 新协议：单字符串
            if (descs.isTextual() && StrUtil.isNotBlank(descs.asText()))
            {
                return descs.asText();
            }
            // 兼容：若仍为数组则取首条
            if (descs.isArray() && descs.size() > 0)
            {
                String first = descs.get(0).asText("");
                return StrUtil.isNotBlank(first) ? first : promptText;
            }
        }
        catch (Exception e)
        {
            log.warn("解析角色 prompt_text.descriptions 失败，按原文兜底: err={}", e.getMessage());
        }
        return promptText;
    }

    /**
     * 从场景 form.prompt_text 解析完整上下文，返回 Map：
     * name / summary / introduction / has_crowd / crowd_description / available_slots / scene_context。
     * 结构化 JSON 逐字段读取；纯文本/解析失败时 introduction 兜底为原文，其余字段为空。
     * 主资产仅作为各字段的二级 fallback——form 层有值优先用 form 层。
     */
    private Map<String, String> parseScenePromptContext(String promptText, AidRolePropScene asset)
    {
        Map<String, String> ctx = new LinkedHashMap<>();
        // 初始化为主资产兜底值
        ctx.put("name", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getName() : null, ""));
        ctx.put("summary", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getSummary() : null, ""));
        ctx.put("introduction", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getIntroduction() : null, ""));
        int fallbackHasCrowd = Objects.nonNull(asset) && Objects.nonNull(asset.getHasCrowd()) ? asset.getHasCrowd() : 0;
        ctx.put("has_crowd", String.valueOf(fallbackHasCrowd));
        ctx.put("crowd_description", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getCrowdDescription() : null, ""));
        ctx.put("available_slots", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getAvailableSlots() : null, ""));
        // scene_context 默认空串，由 form.prompt_text JSON 内的 scene_context 字段覆盖
        ctx.put("scene_context", "");
        // 尝试从结构化 JSON 中覆盖
        if (StrUtil.isNotBlank(promptText) && promptText.trim().startsWith("{"))
        {
            try
            {
                com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(promptText.trim());
                overrideIfPresent(ctx, root, "name");
                overrideIfPresent(ctx, root, "summary");
                overrideIfPresent(ctx, root, "introduction");
                overrideIfPresent(ctx, root, "crowd_description");
                overrideIfPresent(ctx, root, "scene_context");
                // has_crowd 可能是数值节点
                com.fasterxml.jackson.databind.JsonNode hcNode = root.get("has_crowd");
                if (Objects.nonNull(hcNode) && hcNode.isNumber())
                {
                    ctx.put("has_crowd", String.valueOf(hcNode.intValue()));
                }
                // available_slots 可能是数组节点，转为 JSON 字符串
                com.fasterxml.jackson.databind.JsonNode slotsNode = root.get("available_slots");
                if (Objects.nonNull(slotsNode) && slotsNode.isArray() && !slotsNode.isEmpty())
                {
                    ctx.put("available_slots", slotsNode.toString());
                }
            }
            catch (Exception e)
            {
                log.warn("解析场景 prompt_text 上下文失败，使用主资产兜底: err={}", e.getMessage());
            }
        }
        else if (StrUtil.isNotBlank(promptText))
        {
            // 纯文本 promptText 视为 introduction
            ctx.put("introduction", promptText);
        }
        return ctx;
    }

    /**
     * 从道具 form.prompt_text 解析上下文，返回 Map：name / summary / introduction。
     * 结构化 JSON 逐字段读取；纯文本/解析失败时 introduction 兜底为原文，其余字段为空。
     * 主资产仅作为各字段的二级 fallback——form 层有值优先用 form 层。
     */
    private Map<String, String> parsePropPromptContext(String promptText, AidRolePropScene asset)
    {
        Map<String, String> ctx = new LinkedHashMap<>();
        // 初始化为主资产兜底值
        ctx.put("name", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getName() : null, ""));
        ctx.put("summary", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getSummary() : null, ""));
        ctx.put("introduction", StrUtil.blankToDefault(Objects.nonNull(asset) ? asset.getIntroduction() : null, ""));
        // 尝试从结构化 JSON 中覆盖
        if (StrUtil.isNotBlank(promptText) && promptText.trim().startsWith("{"))
        {
            try
            {
                com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(promptText.trim());
                overrideIfPresent(ctx, root, "name");
                overrideIfPresent(ctx, root, "summary");
                overrideIfPresent(ctx, root, "introduction");
            }
            catch (Exception e)
            {
                log.warn("解析道具 prompt_text 上下文失败，使用主资产兜底: err={}", e.getMessage());
            }
        }
        else if (StrUtil.isNotBlank(promptText))
        {
            // 纯文本 promptText 视为 introduction
            ctx.put("introduction", promptText);
        }
        return ctx;
    }

    /**errorType
     * 从 JSON 节点覆盖 ctx 中指定 key，仅当节点为非空文本时覆盖。
     */
    private void overrideIfPresent(Map<String, String> ctx, com.fasterxml.jackson.databind.JsonNode root, String key)
    {
        com.fasterxml.jackson.databind.JsonNode node = root.get(key);
        if (Objects.nonNull(node) && node.isTextual() && StrUtil.isNotBlank(node.asText()))
        {
            ctx.put(key, node.asText());
        }
    }

    /**
     * 场景形态图 prompt 组装。
     * 加载 aid_scene_image_builder 智能体提示词模板，把 form.prompt_text 中 stylist LLM 输出的
     * `prompt` 字段作为 `{form_prompt_text}` 占位符值，资产名作为 `{asset_name}` 占位符值。
     * stylist prompt 提取失败时直接抛错，不再静默降级（避免空提示词浪费图模型积分）。
     */
    private String buildSceneFormImagePrompt(AidComicProject project, AidRolePropScene asset, AidRolePropSceneForm form)
    {
        String template = helper.loadPromptByNameReadOnly(PROMPT_NAME_SCENE_IMAGE);
        String rawPromptText = Objects.nonNull(form) ? form.getPromptText() : null;
        String assetName = Objects.nonNull(asset) ? StrUtil.blankToDefault(asset.getName(), "") : "";

        // 失败抛错，由调用方回滚扣费；不再容忍空 prompt 继续往下走
        String stylistPrompt;
        try
        {
            stylistPrompt = extractPromptFromStylistJson(rawPromptText);
        }
        catch (Exception e)
        {
            log.error("场景出图 stylist prompt 提取失败: formId={}, assetId={}, assetName={}, err={}, promptLen={}",
                    Objects.nonNull(form) ? form.getId() : null,
                    Objects.nonNull(asset) ? asset.getId() : null,
                    assetName,
                    e.getMessage(),
                    StrUtil.length(rawPromptText));
            throw TaskErrorPresentation.toServiceException(e.getMessage(), "场景描述异常");
        }

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("asset_name", assetName);
        variables.put("form_prompt_text", stylistPrompt);
        return helper.substituteVariables(template, variables);
    }

    /**
     * 从主资产 profile_data JSON 拼装时空背景一句话，格式：
     * <pre>
     * {era_coordinate},{time_of_day},{date_coordinate},{weather},{environment_type}
     * </pre>
     * 例如："古代-大景王朝-中国,上午,春季-普通工作日,温和-阴-药香浮尘,城市"。
     * 缺失字段自适应跳过，不留空逗号；解析失败返回空串，由出图智能体走"无时空背景"分支。
     */
    private String buildSceneContextFromProfile(AidRolePropScene asset)
    {
        if (Objects.isNull(asset) || StrUtil.isBlank(asset.getProfileData()))
        {
            return "";
        }
        try
        {
            com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(asset.getProfileData());
            if (Objects.isNull(root) || !root.isObject())
            {
                return "";
            }
            // 拼装顺序：年代 → 时间 → 日期 → 气候 → 环境类型；缺失字段跳过不补占位
            String[] keys = new String[]{
                    "era_coordinate",
                    "time_of_day",
                    "date_coordinate",
                    "weather",
                    "environment_type"
            };
            List<String> parts = new ArrayList<>(keys.length);
            for (String key : keys)
            {
                com.fasterxml.jackson.databind.JsonNode node = root.get(key);
                if (Objects.nonNull(node) && node.isTextual())
                {
                    String val = node.asText("").trim();
                    if (StrUtil.isNotBlank(val))
                    {
                        parts.add(val);
                    }
                }
            }
            return parts.isEmpty() ? "" : String.join(",", parts);
        }
        catch (Exception e)
        {
            log.warn("拼装 scene_context 失败，降级为空串: assetId={}, err={}",
                    Objects.nonNull(asset) ? asset.getId() : null, e.getMessage());
            return "";
        }
    }

    /**
     * 解析画布比例：从项目配置 aid_comic_project.aspect_ratio 读取，缺省或非法值时降级为 16:9。
     * 仅允许六档：9:16 / 3:4 / 4:3 / 1:1 / 16:9 / 21:9，与场景出图智能体规则对齐。
     */
    private String resolveSceneAspectRatio(AidComicProject project)
    {
        final String defaultRatio = "16:9";
        final Set<String> allowed = Set.of("9:16", "3:4", "4:3", "1:1", "16:9", "21:9");
        if (Objects.isNull(project) || StrUtil.isBlank(project.getAspectRatio()))
        {
            return defaultRatio;
        }
        String val = project.getAspectRatio().trim();
        return allowed.contains(val) ? val : defaultRatio;
    }

    /**
     * 道具形态图 prompt 组装。
     * 加载 aid_prop_image_builder 智能体提示词模板，把 form.prompt_text 中 stylist LLM 输出的
     * `prompt` 字段作为 `{form_prompt_text}` 占位符值，资产名作为 `{asset_name}` 占位符值。
     * stylist prompt 提取失败时直接抛错，不再静默降级（避免空提示词浪费图模型积分）。
     */
    private String buildPropFormImagePrompt(AidComicProject project, AidRolePropScene asset, AidRolePropSceneForm form)
    {
        String template = helper.loadPromptByNameReadOnly(PROMPT_NAME_PROP_IMAGE);
        String rawPromptText = Objects.nonNull(form) ? form.getPromptText() : null;
        String assetName = Objects.nonNull(asset) ? StrUtil.blankToDefault(asset.getName(), "") : "";

        String stylistPrompt;
        try
        {
            stylistPrompt = extractPromptFromStylistJson(rawPromptText);
        }
        catch (Exception e)
        {
            log.error("道具出图 stylist prompt 提取失败: formId={}, assetId={}, assetName={}, err={}, promptLen={}",
                    Objects.nonNull(form) ? form.getId() : null,
                    Objects.nonNull(asset) ? asset.getId() : null,
                    assetName,
                    e.getMessage(),
                    StrUtil.length(rawPromptText));
            throw TaskErrorPresentation.toServiceException(e.getMessage(), "道具描述异常");
        }

        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("asset_name", assetName);
        variables.put("form_prompt_text", stylistPrompt);
        return helper.substituteVariables(template, variables);
    }

    /**
     * 资产类型 → aid_prompt_lib remark
     */
    private String resolvePromptRemarkByAssetType(String assetType)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return PROMPT_NAME_CHARACTER_FORM_IMAGE;
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return PROMPT_NAME_SCENE_IMAGE;
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return PROMPT_NAME_PROP_IMAGE;
        }
        return null;
    }

    /**
     * 资产类型 → 后缀提示词
     */
    private String resolvePromptSuffixByAssetType(String assetType)
    {
        if (Objects.equals(ASSET_TYPE_CHARACTER, assetType))
        {
            return CHARACTER_PROMPT_SUFFIX;
        }
        if (Objects.equals(ASSET_TYPE_SCENE, assetType))
        {
            return LOCATION_SPATIAL_SUFFIX;
        }
        if (Objects.equals(ASSET_TYPE_PROP, assetType))
        {
            return PROP_PROMPT_SUFFIX;
        }
        return "";
    }

    /**
     * 解析项目画风提示词。
     */
    private String resolveProjectArtStylePrompt(AidComicProject project)
    {
        if (Objects.isNull(project))
        {
            return "";
        }
        String styleValue = project.getVideoStyleValue();
        if (StrUtil.isBlank(styleValue))
        {
            return "";
        }
        String styleType = project.getVideoStyleType();

        // -------- 历史数据兼容 --------
        // custom：旧项目中 videoStyleValue 语义为"自定义参考图 URL"，不适合拼入文本 prompt。
        //   除图片 URL 外，再拦截无意义脏值（如 "1"、纯数字、过短串），统一返回空串，
        //   避免污染场景出图 prompt 末尾的"图片风格"字段（曾出现"图片风格:1"）。
        if (Objects.equals("custom", styleType))
        {
            String trimmed = styleValue.trim();
            String lower = trimmed.toLowerCase();
            // 图片 URL：不可作为文本提示词
            if (lower.startsWith("http://") || lower.startsWith("https://"))
            {
                return "";
            }
            // 无意义脏值：纯数字 / 过短串（长度 < 2）视为非有效画风提示词
            if (trimmed.length() < 2 || trimmed.matches("\\d+"))
            {
                log.info("custom 画风值非有效提示词，按空串处理: projectId={}, styleValueLen={}",
                        project.getId(), StrUtil.length(styleValue));
                return "";
            }
        }
        // official：旧项目中 videoStyleValue 为提示词库名称，需反查真实提示词
        if (Objects.equals("official", styleType))
        {
            // 校验性查询：只查 prompt_content 必要字段，减少数据返回
            LambdaQueryWrapper<AidPromptLib> wrapper = Wrappers.lambdaQuery();
            wrapper.select(AidPromptLib::getPromptContent);
            wrapper.eq(AidPromptLib::getPromptName, styleValue);
            wrapper.eq(AidPromptLib::getDelFlag, "0");
            wrapper.eq(AidPromptLib::getStatus, "0");
            wrapper.last("LIMIT 1");
            AidPromptLib promptLib = promptLibService.getOne(wrapper, false);
            if (Objects.nonNull(promptLib) && StrUtil.isNotBlank(promptLib.getPromptContent()))
            {
                return promptLib.getPromptContent();
            }
            // 未命中：降级返回原始值
            log.warn("历史项目官方画风提示词未命中 aid_prompt_lib: promptNameLen={}",
                    StrUtil.length(styleValue));
            return styleValue;
        }

        // -------- 新项目 & 其他情况 --------
        // videoStyleValue 即为风格提示词字符串，前端传什么用什么
        return styleValue;
    }

    /**
     * 将画风与后缀追加到已替换的模板结果尾部（避免模板未声明变量时信息丢失）
     */
    private String appendArtStyleAndSuffix(String body, String artStyle, String suffix)
    {
        StringBuilder sb = new StringBuilder(StrUtil.blankToDefault(body, ""));
        if (StrUtil.isNotBlank(artStyle) && !sb.toString().contains(artStyle))
        {
            sb.append("，画风：").append(artStyle);
        }
        if (StrUtil.isNotBlank(suffix) && !sb.toString().contains(suffix))
        {
            sb.append(suffix);
        }
        return sb.toString();
    }
}
