package com.aid.rps.service.impl;

import com.aid.common.error.TaskErrorSnapshot;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.AidRolePropSceneForm;
import com.aid.aid.domain.AidRolePropSceneFormImage;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiModelFuncConfig;
import com.aid.aid.service.IAidRolePropSceneFormImageService;
import com.aid.aid.service.IAidRolePropSceneFormService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiModelFuncConfigService;
import com.aid.aid.domain.AidComicProject;
import com.aid.aid.domain.AidExtractTask;
import com.aid.aid.service.IAidComicProjectService;
import com.aid.aid.service.IAidExtractTaskService;
import com.aid.common.aid.oss.config.OssConfigManager;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.properties.OssProperties;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.config.AidAppConfig;
import com.aid.common.constant.Constants;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.error.TaskErrorPresentation;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.MediaBillingQuoteService;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.media.util.ModelCapabilityResolver;
import com.aid.rps.dto.AssetExtractTaskVO;
import com.aid.rps.dto.RpsFormImageCreateRequest;
import com.aid.rps.dto.RpsFormImageListRequest;
import com.aid.rps.dto.RpsFormImageUpdateRequest;
import com.aid.rps.dto.RpsFormImageUpscaleRequest;
import com.aid.rps.dto.RpsSceneFormImageSplitRequest;
import com.aid.rps.dto.ExtractTaskMessage;
import com.aid.rps.resolver.StoryboardImageReferenceResolver;
import com.aid.rps.service.IRpsFormImageBusinessService;
import com.aid.rps.service.IFormImageSelectionService;
import com.aid.common.aid.rocketmq.config.RocketMqConfigManager;
import com.aid.common.aid.rocketmq.core.MqTemplateFactory;
import com.aid.common.aid.rocketmq.entity.MqResult;
import com.aid.rps.vo.RpsAssetVO;
import com.aid.rps.vo.RpsFormImageDetailVO;
import com.aid.rps.vo.RpsSceneFormImageBatchSplitVO;
import com.aid.rps.vo.RpsSceneFormImageSplitVO;
import com.aid.common.vo.BatchOperationResultVO;
import com.aid.service.IAiModelConfigService;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 形态图片实例业务 Service 实现。
 * 处理"上传 / 官方导入"图片入库、图片删除 / 编辑 / 高清、场景四宫格拆分等业务规则。
 * 与 {@code IRpsBusinessService} 解耦：本 Service 不参与 form 形态定义。
 *
 * @author 视觉AID
 */
@Slf4j
@Service
public class RpsFormImageBusinessServiceImpl implements IRpsFormImageBusinessService
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String DEL_FLAG_DELETED = "2";
    /** 渠道 / 模型启用状态：正常 */
    private static final String STATUS_NORMAL = "0";
    /** 模型类型：图片 */
    private static final String MODEL_TYPE_IMAGE = "image";
    /** 功能编码：与 /api/user/model/listByFunc?funcCode=image_upscale 及功能池配置一致 */
    private static final String FUNC_CODE_IMAGE_UPSCALE = "image_upscale";
    /** 允许的来源类型：上传 / 官方 */
    private static final List<String> UPLOAD_OFFICIAL = List.of("upload", "official");
    /** 主资产类型：角色 */
    private static final String ASSET_TYPE_CHARACTER = "character";
    /** 主资产类型：仅 scene 可被拆分（业务硬约束） */
    private static final String ASSET_TYPE_SCENE = "scene";
    /** 主资产类型：道具 */
    private static final String ASSET_TYPE_PROP = "prop";
    /**
     * 拆分子图位置标签，顺序固定：主视 / 反打 / 左立面 / 右立面。
     */
    private static final List<String> SPLIT_POSITION_LABELS = List.of("主视", "反打", "左立面", "右立面");
    /** 拆分子图来源类型：复用现有的 upload 通道 */
    private static final String SPLIT_CHILD_SOURCE_TYPE = "upload";
    /** 一键提取自动生成图片来源 */
    private static final String FORM_IMAGE_SOURCE_TYPE_AI_AUTO = "ai_auto";
    /** 编辑作图 / 对话作图图片来源 */
    private static final String FORM_IMAGE_SOURCE_TYPE_EDIT_CHAT = "ai_edit_chat";
    /** 编辑作图 / 对话作图任务类型 */
    private static final String TASK_TYPE_FORM_EDIT_CHAT = "form_edit_chat";

    /** 源图被拆后的聚合命名后缀：源图原名 + 该后缀（如 龙泉镇泥瓶巷_主视,反打,左立面,右立面） */
    private static final String SPLIT_SOURCE_NAME_SUFFIX = "_" + String.join(",", SPLIT_POSITION_LABELS);

    /** 子图统一输出格式（PNG 无损，避免二次压缩失真） */
    private static final String SPLIT_CHILD_IMAGE_FORMAT = "png";

    /** 源图下载大小上限：10MB，超过则拒绝拆分（防止大图占用过高内存） */
    private static final long MAX_SOURCE_IMAGE_BYTES = 10L * 1024 * 1024;

    /** 源图下载连接 / 读取超时（毫秒） */
    private static final int DOWNLOAD_TIMEOUT_MS = 15000;
    /** reference_images JSON 列序列化复用 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 分辨率档位排序表（归一化写法，从低到高）：用于从 capability_json.sizeOptions 中取最高档 */
    private static final List<String> RESOLUTION_RANK = List.of("1k", "2k", "3k", "4k", "8k");

    /** 异步中间态白名单（轮询用）：含 WAIT_CALLBACK（回调优先模式首响应态），漏掉会把在途任务误判失败 */
    private static final Set<String> IMAGE_IN_PROGRESS_STATUSES = Set.of(
            "INIT", "PENDING", "QUEUED", "PROCESSING", "WAIT_POLL", "WAIT_CALLBACK");
    /** 任务状态常量 */
    private static final String TASK_STATUS_PENDING = "PENDING";
    private static final String TASK_STATUS_PROCESSING = "PROCESSING";
    private static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String TASK_STATUS_FAILED = "FAILED";
    /** 任务类型：图片高清 */
    private static final String TASK_TYPE_IMAGE_UPSCALE = "image_upscale";
    /**
     * 图片轮询最大等待时间（秒）。
     * 即梦 8K 超清等高清模型实测出图常需 5~9 分钟（含上游排队 / 限流退避），
     * 旧值 180s 远小于真实耗时，会在上游尚未出图时就抛「生成超时」误判失败；故放宽到 600s 覆盖真实耗时。
     */
    private static final long IMAGE_POLL_TIMEOUT_SECONDS = 600L;
    /** 图片轮询间隔（秒） */
    private static final long IMAGE_POLL_INTERVAL_SECONDS = 5L;
    /** 保底 prompt：高清模型自身不依赖 prompt，但统一媒体主链路 validatePrompt 要求非空 */
    private static final String UPSCALE_FALLBACK_PROMPT = "高清增强，保持原图内容不变";
    /** 防重锁 Redis Key 前缀 */
    private static final String UPSCALE_LOCK_PREFIX = "asset:form:lock:image_upscale:";
    /**
     * 防重锁 TTL（秒）。
     * 必须 ≥ 单次最坏出图时长，否则锁会在生成过程中提前过期：用户重试会重复下单、
     * 进一步加剧上游并发超限（50430）。高清出图最坏约 9 分钟，这里给 900s（15 分钟）兜底，
     * 正常情况下由消费端 finally 主动释放，TTL 仅作进程崩溃时的安全网。
     */
    private static final long UPSCALE_LOCK_TTL_SECONDS = 900L;
    /** MQ Topic / Tag：复用资产提取主题，高清任务使用独立 tag */
    private static final String MQ_TOPIC = "ASSET_EXTRACT_TOPIC";
    private static final String MQ_TAG_UPSCALE = "image_upscale";

    @Autowired
    private IAidRolePropSceneFormImageService rpsFormImageService;

    /** 形态图片使用状态统一规则服务。 */
    @Autowired
    private IFormImageSelectionService formImageSelectionService;
    /** OSS 文件清理服务：硬删形态图前先删其 OSS 文件 */
    @Autowired
    private com.aid.media.cleanup.IMediaOssCleanupService mediaOssCleanupService;

    @Autowired
    private IAidRolePropSceneFormService rpsFormService;

    @Autowired
    private IAidRolePropSceneService rpsService;

    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aid.model.definition.BusinessModelResolver businessModelResolver;

    /** 模型主表 Service：高清接口校验模型存在 / 类型 / 池成员 */
    @Autowired
    private IAidAiModelService aidAiModelService;

    /** 模型功能池配置 Service：高清模型必须在 func_code=image_upscale 的池内 */
    @Autowired
    private IAidAiModelFuncConfigService aidAiModelFuncConfigService;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    @Autowired
    private MediaBillingQuoteService mediaBillingQuoteService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 媒体URL拼接器：DB相对路径 → 完整可下载URL，用于接口出参及参考图传给上游 provider */
    @Autowired
    private MediaUrlResolver mediaUrlResolver;

    /**
     * OSS 配置管理器：在拆分入参 {@code List<String>} 不能用 @MediaUrl 注解的情况下，
     * 业务层手工读取 cdnDomain / localDomain 把完整 URL 剥离成相对路径入库。
     */
    @Autowired
    private OssConfigManager ossConfigManager;

    /** OSS 上传模板：后端切图后把 4 张子图字节上传 OSS / 本地，返回相对路径 */
    @Autowired
    private OssTemplate ossTemplate;

    /** 项目主表 Service：拆分接口需校验项目归属当前用户 */
    @Autowired
    private IAidComicProjectService projectService;

    /** Redis缓存：防重锁 */
    @Autowired
    private RedisCache redisCache;

    /** 提取任务 Service：复用现有 aid_extract_task 表 */
    @Autowired
    private IAidExtractTaskService extractTaskService;

    /** MQ 配置管理器 */
    @Autowired
    private RocketMqConfigManager rocketMqConfigManager;

    /** MQ 消息发送工厂 */
    @Autowired
    private MqTemplateFactory mqTemplateFactory;

    /** 任务排队 / 多维并发调度服务 */
    @Autowired
    private com.aid.rps.queue.TaskQueueService taskQueueService;

    /** SSE 推送（本地模式终态编排用） */
    @Autowired
    private com.aid.rps.sse.AssetExtractSseManager sseManager;

    /** 取消标记 / 名额释放（本地模式终态编排用） */
    @Autowired
    private com.aid.rps.service.IAssetExtractService assetExtractService;

    /** 双模式派发路由器（MQ/本地切换唯一收口） */
    @Autowired
    private com.aid.rps.queue.DualModeTaskDispatcher dualModeTaskDispatcher;

    @Override
    @Transactional
    public RpsAssetVO createImage(RpsFormImageCreateRequest request, Long userId)
    {
        String sourceType = request.getSourceType();
        if (StrUtil.isBlank(sourceType) || !UPLOAD_OFFICIAL.contains(sourceType))
        {
            log.info("图片创建失败，来源类型错误: sourceType={}", sourceType);
            throw new RuntimeException("来源类型错误");
        }

        LambdaQueryWrapper<AidRolePropSceneForm> formQuery = Wrappers.lambdaQuery();
        formQuery.eq(AidRolePropSceneForm::getId, request.getFormId());
        formQuery.eq(AidRolePropSceneForm::getUserId, userId);
        formQuery.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
        AidRolePropSceneForm form = rpsFormService.getOne(formQuery);
        if (Objects.isNull(form))
        {
            log.info("图片创建失败，形态不存在: formId={}", request.getFormId());
            throw new RuntimeException("形态不存在");
        }

        LambdaQueryWrapper<AidRolePropSceneFormImage> existsQuery = Wrappers.lambdaQuery();
        existsQuery.eq(AidRolePropSceneFormImage::getFormId, form.getId());
        existsQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        existsQuery.select(AidRolePropSceneFormImage::getId);
        long existingCount = rpsFormImageService.count(existsQuery);

        // 默认名直接取 form 名，不额外拼接 changeReason 后缀，避免出现"林深_初始形象_初始形象"这种复读名
        AidRolePropScene asset = rpsService.getById(form.getAssetId());
        String defaultName = StrUtil.isNotBlank(form.getName()) ? form.getName() : "形态";

        Date now = DateUtils.getNowDate();
        AidRolePropSceneFormImage img = new AidRolePropSceneFormImage();
        img.setFormId(form.getId());
        img.setAssetId(form.getAssetId());
        img.setProjectId(form.getProjectId());
        img.setEpisodeId(form.getEpisodeId());
        img.setUserId(userId);
        img.setName(StrUtil.blankToDefault(request.getName(), defaultName));
        img.setImageUrl(request.getImageUrl());
        img.setSourceType(sourceType);
        img.setSortOrder((int) existingCount);
        img.setIsUse(0);
        img.setImageStatus("completed");
        img.setDelFlag(DEL_FLAG_NORMAL);
        img.setCreateTime(now);
        img.setCreateBy(String.valueOf(userId));
        img.setUpdateTime(now);
        img.setUpdateBy(String.valueOf(userId));
        rpsFormImageService.save(img);

        // 前端拿到 imgId 后可直接调 /form/use、/form/unuse，无需二次查库。
        return RpsAssetVO.builder()
                .id(form.getAssetId())
                .assetType(Objects.nonNull(asset) ? asset.getAssetType() : null)
                .assetName(Objects.nonNull(asset) ? asset.getName() : null)
                // imgId 取 MyBatis-Plus save 后自动回填的主键，不重复查库
                .imgId(img.getId())
                .build();
    }

    @Override
    @Transactional
    public void deleteImage(Long imageId, Long userId)
    {
        LambdaQueryWrapper<AidRolePropSceneFormImage> q = Wrappers.lambdaQuery();
        // 查询字段精简：归属校验只需主键，不加载内部提示词快照。
        q.select(AidRolePropSceneFormImage::getId);
        q.eq(AidRolePropSceneFormImage::getId, imageId);
        q.eq(AidRolePropSceneFormImage::getUserId, userId);
        q.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        AidRolePropSceneFormImage img = rpsFormImageService.getOne(q);
        if (Objects.isNull(img))
        {
            log.info("图片删除失败，不存在或不属于当前用户: imageId={}", imageId);
            throw new RuntimeException("图片不存在");
        }

        rpsFormImageService.getBaseMapper().delete(Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .eq(AidRolePropSceneFormImage::getId, imageId)
                .eq(AidRolePropSceneFormImage::getUserId, userId));

        mediaOssCleanupService.cleanupFiles(java.util.Collections.singletonList(img.getImageUrl()));
    }

    @Override
    @Transactional
    public java.util.List<String> enableReferencesAndCollectMissing(Long projectId, Long userId,
                                                                    java.util.Collection<String> names)
    {
        ReferenceEnablePlan plan = planReferenceEnable(projectId, userId, names);
        applyReferenceEnablePlan(plan, userId);
        return plan.missingNames();
    }

    @Override
    public ReferenceEnablePlan planReferenceEnable(Long projectId, Long userId,
                                                   java.util.Collection<String> names)
    {
        List<String> missing = new ArrayList<>();
        if (CollectionUtil.isEmpty(names))
        {
            return new ReferenceEnablePlan(projectId, missing, List.of());
        }
        java.util.Set<String> nameSet = names.stream()
                .filter(StrUtil::isNotBlank)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (nameSet.isEmpty())
        {
            return new ReferenceEnablePlan(projectId, missing, List.of());
        }
        // 作用域不完整 → 无法按归属解析，全部计入真实缺失，
        // 杜绝"作用域为空 → resolver 返回空 → 绕过参考缺失校验 → 仍建任务走自行发挥"
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            return new ReferenceEnablePlan(projectId, new ArrayList<>(nameSet), List.of());
        }
        // 候选图：整作用域加载（不按 name IN 过滤），便于对占位名做归一化 + 方位后缀回退匹配；
        // 可引用域=项目+用户（不按集过滤），与 StoryboardImageReferenceResolver 出图解析口径一致：
        // 项目级角色图（episode_id=0）/ 跨集复用资产图按集过滤会被误判"缺失"；
        // 单项目 form_image 行数很小（<百级），全量加载成本可忽略
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getName,
                                AidRolePropSceneFormImage::getIsUse, AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getSortOrder)
                        .eq(AidRolePropSceneFormImage::getProjectId, projectId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getIsSplitSource, 0)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));
        // 按归一化 name 分组，只保留"可用图"（image_url 非空）；匹配口径与出图解析器完全一致
        Map<String, List<AidRolePropSceneFormImage>> usableByName = new LinkedHashMap<>();
        if (CollectionUtil.isNotEmpty(imgs))
        {
            for (AidRolePropSceneFormImage img : imgs)
            {
                if (StrUtil.isBlank(img.getImageUrl()))
                {
                    continue;
                }
                usableByName.computeIfAbsent(
                        StoryboardImageReferenceResolver.normalizeAssetRefName(img.getName()),
                        k -> new ArrayList<>()).add(img);
            }
        }
        List<Long> toEnable = new ArrayList<>();
        for (String name : nameSet)
        {
            // 候选键顺序：精确名 → 方位后缀回退（X_反打→X / X→X_主视…），与 StoryboardImageReferenceResolver 同源
            List<AidRolePropSceneFormImage> group = null;
            for (String key : StoryboardImageReferenceResolver.candidateLookupKeys(name))
            {
                group = usableByName.get(key);
                if (CollectionUtil.isNotEmpty(group))
                {
                    break;
                }
            }
            if (CollectionUtil.isEmpty(group))
            {
                missing.add(name);            // 完全无可用图 → 真实缺失
                continue;
            }
            boolean hasActiveUsable = group.stream()
                    .anyMatch(i -> Objects.nonNull(i.getIsUse()) && i.getIsUse() == 1);
            if (hasActiveUsable)
            {
                continue;                     // 已有可用且在用图 → 可解析
            }
            // "已存在未启用"：记录最新一张待启用（sortOrder 大优先，再 id 大）
            AidRolePropSceneFormImage latest = group.stream()
                    .max(java.util.Comparator
                            .comparing((AidRolePropSceneFormImage i) -> Objects.isNull(i.getSortOrder()) ? Integer.MIN_VALUE : i.getSortOrder())
                            .thenComparing(AidRolePropSceneFormImage::getId))
                    .orElse(null);
            if (Objects.nonNull(latest))
            {
                toEnable.add(latest.getId());
            }
        }
        // 原子计划：存在真实缺失时，本轮不包含任何待启用图。
        if (CollectionUtil.isNotEmpty(missing))
        {
            return new ReferenceEnablePlan(projectId, missing, List.of());
        }
        return new ReferenceEnablePlan(projectId, missing, toEnable);
    }

    @Override
    @Transactional
    public void applyReferenceEnablePlan(ReferenceEnablePlan plan, Long userId)
    {
        if (plan == null || CollectionUtil.isEmpty(plan.imageIdsToEnable())
                || CollectionUtil.isNotEmpty(plan.missingNames()) || Objects.isNull(userId))
        {
            return;
        }
        List<Long> toEnable = plan.imageIdsToEnable();
        if (CollectionUtil.isNotEmpty(toEnable))
        {
            List<Long> orderedIds = toEnable.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            orderedIds.forEach(imageId -> formImageSelectionService.selectImage(imageId, userId));
            log.info("引用即启用: projectId={}, userId={}, 启用图数={}, ids={}",
                    plan.projectId(), userId, orderedIds.size(), orderedIds);
        }
    }

    @Override
    @Transactional
    public RpsFormImageDetailVO updateImage(RpsFormImageUpdateRequest request, Long userId)
    {
        LambdaQueryWrapper<AidRolePropSceneFormImage> q = Wrappers.lambdaQuery();
        // 归属校验只读取主键，禁止加载内部提示词快照等大字段。
        q.select(AidRolePropSceneFormImage::getId);
        q.eq(AidRolePropSceneFormImage::getId, request.getImageId());
        q.eq(AidRolePropSceneFormImage::getUserId, userId);
        q.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        AidRolePropSceneFormImage img = rpsFormImageService.getOne(q);
        if (Objects.isNull(img))
        {
            log.info("图片编辑失败，不存在或不属于当前用户: imageId={}", request.getImageId());
            throw new RuntimeException("图片不存在");
        }

        Date now = DateUtils.getNowDate();
        LambdaUpdateWrapper<AidRolePropSceneFormImage> upd = Wrappers.lambdaUpdate();
        upd.eq(AidRolePropSceneFormImage::getId, request.getImageId());
        upd.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        boolean anyField = false;
        if (StrUtil.isNotBlank(request.getName()))
        {
            upd.set(AidRolePropSceneFormImage::getName, request.getName());
            anyField = true;
        }
        if (StrUtil.isNotBlank(request.getImageUrl()))
        {
            upd.set(AidRolePropSceneFormImage::getImageUrl, request.getImageUrl());
            anyField = true;
        }
        if (Objects.nonNull(request.getDescriptionIndex()))
        {
            upd.set(AidRolePropSceneFormImage::getDescriptionIndex, request.getDescriptionIndex());
            anyField = true;
        }
        if (Objects.nonNull(request.getReferenceImages()))
        {
            // 序列化为 JSON 字符串落 reference_images 列；空列表 → null（与生图链路保持一致）
            String json = null;
            if (CollectionUtil.isNotEmpty(request.getReferenceImages()))
            {
                try
                {
                    json = OBJECT_MAPPER.writeValueAsString(request.getReferenceImages());
                }
                catch (Exception e)
                {
                    log.error("图片编辑失败，参考图序列化失败: imageId={}, err={}",
                            request.getImageId(), e.getMessage());
                    throw new RuntimeException("参考图异常");
                }
            }
            upd.set(AidRolePropSceneFormImage::getReferenceImages, json);
            anyField = true;
        }
        if (!anyField)
        {
            log.info("图片编辑失败，未提供任何可更新字段: imageId={}", request.getImageId());
            throw new RuntimeException("更新内容不存在");
        }
        upd.set(AidRolePropSceneFormImage::getUpdateTime, now);
        upd.set(AidRolePropSceneFormImage::getUpdateBy, String.valueOf(userId));
        rpsFormImageService.update(upd);

        // 查询字段精简：C端更新结果不加载内部提示词快照。
        AidRolePropSceneFormImage updated = rpsFormImageService.getOne(
                detailImageSelectWrapper().eq(AidRolePropSceneFormImage::getId, request.getImageId()));
        AidRolePropSceneForm form = rpsFormService.getById(updated.getFormId());
        AidRolePropScene asset = Objects.nonNull(form) ? rpsService.getById(form.getAssetId()) : null;
        return buildDetailVO(updated, form, asset);
    }

    /**
     * 查询形态图片列表（assetType 等条件下推 SQL）。
     */
    @Override
    public List<RpsFormImageDetailVO> queryImageList(RpsFormImageListRequest request, Long userId)
    {
        if (Objects.isNull(request))
        {
            log.info("图片列表查询失败，参数为空: userId={}", userId);
            throw new RuntimeException("参数缺失");
        }
        if (Objects.isNull(request.getFormId()) && StrUtil.isBlank(request.getAssetType()))
        {
            log.info("图片列表查询失败，formId 与 assetType 必须至少传一个: userId={}", userId);
            throw new RuntimeException("参数缺失");
        }

        Set<Long> scopedAssetIds = null;
        if (Objects.isNull(request.getFormId()))
        {
            LambdaQueryWrapper<AidRolePropScene> scopeQ = Wrappers.lambdaQuery();
            // 仅取主键，最小列返回
            scopeQ.select(AidRolePropScene::getId);
            scopeQ.eq(AidRolePropScene::getUserId, userId);
            scopeQ.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
            // assetType 必传：本路径核心收敛维度
            scopeQ.eq(AidRolePropScene::getAssetType, request.getAssetType());
            if (Objects.nonNull(request.getAssetId()))
            {
                scopeQ.eq(AidRolePropScene::getId, request.getAssetId());
            }
            if (Objects.nonNull(request.getProjectId()))
            {
                // projectId 仅做精确匹配，不支持"全局资产"特殊取值
                scopeQ.eq(AidRolePropScene::getProjectId, request.getProjectId());
            }
            if (Objects.nonNull(request.getEpisodeId()) && request.getEpisodeId() > 0L)
            {
                // 按集浏览时项目级资产（剧集角色/复用资产，episodeId=0）恒可见，与 /list、/form/list 口径一致
                scopeQ.in(AidRolePropScene::getEpisodeId, 0L, request.getEpisodeId());
            }
            else if (Objects.nonNull(request.getEpisodeId()))
            {
                scopeQ.eq(AidRolePropScene::getEpisodeId, request.getEpisodeId());
            }
            scopedAssetIds = rpsService.list(scopeQ).stream()
                    .map(AidRolePropScene::getId)
                    .collect(Collectors.toSet());
            // 收敛集合为空：直接短路返回空，避免后续无意义查询
            if (CollectionUtil.isEmpty(scopedAssetIds))
            {
                return new ArrayList<>();
            }
        }

        LambdaQueryWrapper<AidRolePropSceneFormImage> imgQuery = Wrappers.lambdaQuery();
        // 查询字段精简：C端列表明确排除 prompt_snapshot。
        imgQuery.select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getFormId,
                AidRolePropSceneFormImage::getAssetId, AidRolePropSceneFormImage::getProjectId,
                AidRolePropSceneFormImage::getEpisodeId, AidRolePropSceneFormImage::getName,
                AidRolePropSceneFormImage::getImageUrl, AidRolePropSceneFormImage::getSourceType,
                AidRolePropSceneFormImage::getDescriptionIndex, AidRolePropSceneFormImage::getReferenceImages,
                AidRolePropSceneFormImage::getBatchNo,
                AidRolePropSceneFormImage::getSortOrder, AidRolePropSceneFormImage::getIsUse,
                AidRolePropSceneFormImage::getImageStatus, AidRolePropSceneFormImage::getFailReason,
                AidRolePropSceneFormImage::getIsSplitSource, AidRolePropSceneFormImage::getIsSplitChild);
        imgQuery.eq(AidRolePropSceneFormImage::getUserId, userId);
        imgQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        if (Objects.nonNull(request.getFormId()))
        {
            // formId 路径：仅查该 form 下，其它过滤忽略
            imgQuery.eq(AidRolePropSceneFormImage::getFormId, request.getFormId());
        }
        else
        {
            // 收敛路径：assetType / projectId / episodeId / assetId 已在主资产层下推，
            // 这里仅按 asset_id in 收敛集合做主查询，命中精确范围
            imgQuery.in(AidRolePropSceneFormImage::getAssetId, scopedAssetIds);
        }
        if (Objects.nonNull(request.getIsUse()))
        {
            // isUse 直接在 form_image 层 SQL 过滤
            imgQuery.eq(AidRolePropSceneFormImage::getIsUse, request.getIsUse());
        }
        imgQuery.orderByAsc(AidRolePropSceneFormImage::getSortOrder);
        imgQuery.orderByAsc(AidRolePropSceneFormImage::getCreateTime);
        List<AidRolePropSceneFormImage> imgs = rpsFormImageService.list(imgQuery);
        if (CollectionUtil.isEmpty(imgs))
        {
            return new ArrayList<>();
        }
        boolean hasAiAutoImages = imgs.stream()
                .anyMatch(i -> Objects.equals(FORM_IMAGE_SOURCE_TYPE_AI_AUTO, i.getSourceType()));

        Set<Long> formIds = imgs.stream()
                .map(AidRolePropSceneFormImage::getFormId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AidRolePropSceneForm> formMap = new java.util.HashMap<>();
        if (CollectionUtil.isNotEmpty(formIds))
        {
            LambdaQueryWrapper<AidRolePropSceneForm> formQuery = Wrappers.lambdaQuery();
            if (hasAiAutoImages)
            {
                // prompt_text 是展示提示词正文来源，仅列表中存在 ai_auto 时加载该长文本列。
                formQuery.select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getName,
                        AidRolePropSceneForm::getAssetId, AidRolePropSceneForm::getPromptText);
            }
            else
            {
                formQuery.select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getName,
                        AidRolePropSceneForm::getAssetId);
            }
            formQuery.in(AidRolePropSceneForm::getId, formIds);
            formQuery.eq(AidRolePropSceneForm::getDelFlag, DEL_FLAG_NORMAL);
            formMap = rpsFormService.list(formQuery).stream()
                    .collect(Collectors.toMap(AidRolePropSceneForm::getId, f -> f, (a, b) -> a));
        }

        Set<Long> assetIds = imgs.stream()
                .map(AidRolePropSceneFormImage::getAssetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AidRolePropScene> assetMap = new java.util.HashMap<>();
        if (CollectionUtil.isNotEmpty(assetIds))
        {
            LambdaQueryWrapper<AidRolePropScene> assetQuery = Wrappers.lambdaQuery();
            assetQuery.select(AidRolePropScene::getId, AidRolePropScene::getName,
                    AidRolePropScene::getAssetType, AidRolePropScene::getProjectId,
                    AidRolePropScene::getEpisodeId);
            assetQuery.in(AidRolePropScene::getId, assetIds);
            assetQuery.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
            assetMap = rpsService.list(assetQuery).stream()
                    .collect(Collectors.toMap(AidRolePropScene::getId, a -> a, (a, b) -> a));
        }

        Map<Long, String> projectStyleMap = loadProjectPublicStyles(imgs, assetMap, userId);
        Map<Long, String> editRawPromptMap = loadEditChatRawPrompts(imgs, userId);

        // 主资产 ID 收敛阶段下推到 SQL，此处不再做 assetType 内存裁剪。
        Map<Long, AidRolePropSceneForm> finalFormMap = formMap;
        Map<Long, AidRolePropScene> finalAssetMap = assetMap;
        return imgs.stream()
                .map(i -> {
                    AidRolePropSceneForm form = finalFormMap.get(i.getFormId());
                    AidRolePropScene asset = finalAssetMap.get(i.getAssetId());
                    String promptText = resolveDisplayPromptText(
                            i, form, asset, projectStyleMap, editRawPromptMap);
                    return buildDetailVO(i, form, asset, promptText);
                })
                .collect(Collectors.toList());
    }

    /**
     * 批量加载自动生图所需的项目公开风格描述。
     */
    private Map<Long, String> loadProjectPublicStyles(List<AidRolePropSceneFormImage> imgs,
                                                      Map<Long, AidRolePropScene> assetMap,
                                                      Long userId)
    {
        Set<Long> projectIds = imgs.stream()
                .filter(i -> Objects.equals(FORM_IMAGE_SOURCE_TYPE_AI_AUTO, i.getSourceType()))
                .map(i -> {
                    if (Objects.nonNull(i.getProjectId()))
                    {
                        return i.getProjectId();
                    }
                    AidRolePropScene asset = assetMap.get(i.getAssetId());
                    return Objects.nonNull(asset) ? asset.getProjectId() : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (CollectionUtil.isEmpty(projectIds))
        {
            return new HashMap<>();
        }
        // C端只显示公开风格值，禁止加载 hidden_style_prompt_json。
        List<AidComicProject> projects = projectService.list(Wrappers.<AidComicProject>lambdaQuery()
                .select(AidComicProject::getId, AidComicProject::getVideoStyleValue)
                .in(AidComicProject::getId, projectIds)
                .eq(AidComicProject::getUserId, userId)
                .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, String> styleMap = new HashMap<>();
        for (AidComicProject project : projects)
        {
            if (Objects.nonNull(project.getId()))
            {
                // HashMap 允许历史项目的公开风格值为 null；下游按无风格安全处理。
                styleMap.put(project.getId(), project.getVideoStyleValue());
            }
        }
        return styleMap;
    }

    /**
     * 批量加载编辑作图任务中的用户原始提示词。
     */
    private Map<Long, String> loadEditChatRawPrompts(List<AidRolePropSceneFormImage> imgs, Long userId)
    {
        Set<Long> taskIds = imgs.stream()
                .filter(i -> Objects.equals(FORM_IMAGE_SOURCE_TYPE_EDIT_CHAT, i.getSourceType()))
                .map(AidRolePropSceneFormImage::getBatchNo)
                .map(this::parseTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (CollectionUtil.isEmpty(taskIds))
        {
            return new HashMap<>();
        }
        // 仅查询 rawPrompt 所在的 input_snapshot；不查询任何最终 prompt 快照。
        List<AidExtractTask> tasks = extractTaskService.list(Wrappers.<AidExtractTask>lambdaQuery()
                .select(AidExtractTask::getId, AidExtractTask::getInputSnapshot)
                .in(AidExtractTask::getId, taskIds)
                .eq(AidExtractTask::getUserId, userId)
                .eq(AidExtractTask::getTaskType, TASK_TYPE_FORM_EDIT_CHAT)
                .eq(AidExtractTask::getDelFlag, DEL_FLAG_NORMAL));
        Map<Long, String> rawPromptMap = new HashMap<>();
        for (AidExtractTask task : tasks)
        {
            String rawPrompt = extractRawPrompt(task.getInputSnapshot());
            if (StrUtil.isNotBlank(rawPrompt))
            {
                // 原始输入按库存原文返回，不做 trim 或二次拼接。
                rawPromptMap.put(task.getId(), rawPrompt);
            }
        }
        return rawPromptMap;
    }

    /**
     * 按图片来源解析 C 端展示提示词。
     */
    private String resolveDisplayPromptText(AidRolePropSceneFormImage img,
                                            AidRolePropSceneForm form,
                                            AidRolePropScene asset,
                                            Map<Long, String> projectStyleMap,
                                            Map<Long, String> editRawPromptMap)
    {
        if (Objects.equals(FORM_IMAGE_SOURCE_TYPE_AI_AUTO, img.getSourceType()))
        {
            Long projectId = Objects.nonNull(img.getProjectId())
                    ? img.getProjectId() : Objects.nonNull(asset) ? asset.getProjectId() : null;
            String styleValue = Objects.nonNull(projectId) ? projectStyleMap.get(projectId) : null;
            return resolveAiAutoPromptText(form, asset, img.getDescriptionIndex(), styleValue);
        }
        if (Objects.equals(FORM_IMAGE_SOURCE_TYPE_EDIT_CHAT, img.getSourceType()))
        {
            Long taskId = parseTaskId(img.getBatchNo());
            return Objects.nonNull(taskId) ? editRawPromptMap.get(taskId) : null;
        }
        return null;
    }

    /**
     * 解析自动生图的业务正文并追加项目公开风格。
     */
    private String resolveAiAutoPromptText(AidRolePropSceneForm form,
                                           AidRolePropScene asset,
                                           Integer descriptionIndex,
                                           String videoStyleValue)
    {
        if (Objects.isNull(form) || Objects.isNull(asset))
        {
            return null;
        }
        String businessPrompt;
        if (Objects.equals(ASSET_TYPE_CHARACTER, asset.getAssetType()))
        {
            businessPrompt = extractCharacterBusinessPrompt(form.getPromptText(), descriptionIndex);
        }
        else if (Objects.equals(ASSET_TYPE_SCENE, asset.getAssetType())
                || Objects.equals(ASSET_TYPE_PROP, asset.getAssetType()))
        {
            businessPrompt = extractScenePropBusinessPrompt(form.getPromptText());
        }
        else
        {
            return null;
        }
        if (StrUtil.isBlank(businessPrompt))
        {
            return null;
        }
        String normalizedPrompt = businessPrompt.trim();
        if (StrUtil.isBlank(videoStyleValue))
        {
            return normalizedPrompt;
        }
        return normalizedPrompt + "\n\n使用风格：" + videoStyleValue.trim();
    }

    /**
     * 解析角色形态的完整人物描述。
     */
    private String extractCharacterBusinessPrompt(String promptText, Integer descriptionIndex)
    {
        if (StrUtil.isBlank(promptText))
        {
            return null;
        }
        String cleaned = stripJsonFence(promptText);
        if (!looksLikeJson(cleaned))
        {
            return cleaned;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(cleaned);
            if (Objects.isNull(root) || !root.isObject())
            {
                return null;
            }
            JsonNode descriptions = firstPresentNode(root,
                    "descriptions", "description", "人物特征");
            if (Objects.isNull(descriptions))
            {
                return null;
            }
            if (descriptions.isTextual())
            {
                return StrUtil.isBlank(descriptions.asText()) ? null : descriptions.asText();
            }
            if (descriptions.isArray() && !descriptions.isEmpty())
            {
                int index = Objects.nonNull(descriptionIndex) && descriptionIndex >= 0
                        && descriptionIndex < descriptions.size() ? descriptionIndex : 0;
                JsonNode selected = descriptions.get(index);
                return Objects.nonNull(selected) && selected.isTextual()
                        && StrUtil.isNotBlank(selected.asText()) ? selected.asText() : null;
            }
        }
        catch (Exception ignored)
        {
            // 历史异常 JSON 安全返回 null，禁止把结构化原文直接透传给 C 端。
        }
        return null;
    }

    /**
     * 从场景或道具形态结构中提取面向用户的 prompt 正文。
     */
    private String extractScenePropBusinessPrompt(String promptText)
    {
        if (StrUtil.isBlank(promptText))
        {
            return null;
        }
        String cleaned = stripJsonFence(promptText);
        if (!looksLikeJson(cleaned))
        {
            return cleaned;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(cleaned);
            JsonNode target = root;
            if (Objects.nonNull(root) && root.isArray())
            {
                target = root.isEmpty() ? null : root.get(0);
            }
            if (Objects.isNull(target) || !target.isObject())
            {
                return null;
            }
            JsonNode prompt = target.get("prompt");
            return Objects.nonNull(prompt) && prompt.isTextual() && StrUtil.isNotBlank(prompt.asText())
                    ? prompt.asText() : null;
        }
        catch (Exception ignored)
        {
            // 历史异常 JSON 安全返回 null，禁止把原始 JSON 直接透传给 C 端。
            return null;
        }
    }

    /**
     * 从任务输入快照读取用户原始提示词。
     */
    private String extractRawPrompt(String inputSnapshot)
    {
        if (StrUtil.isBlank(inputSnapshot))
        {
            return null;
        }
        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(inputSnapshot);
            JsonNode rawPrompt = Objects.nonNull(root) && root.isObject() ? root.get("rawPrompt") : null;
            return Objects.nonNull(rawPrompt) && rawPrompt.isTextual() && StrUtil.isNotBlank(rawPrompt.asText())
                    ? rawPrompt.asText() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * 解析 form_image.batch_no 中的编辑任务 ID。
     */
    private Long parseTaskId(String batchNo)
    {
        if (StrUtil.isBlank(batchNo))
        {
            return null;
        }
        try
        {
            long taskId = Long.parseLong(batchNo.trim());
            return taskId > 0L ? taskId : null;
        }
        catch (NumberFormatException ignored)
        {
            return null;
        }
    }

    /**
     * 获取第一个存在的业务文本节点。
     */
    private JsonNode firstPresentNode(JsonNode root, String... fieldNames)
    {
        for (String fieldName : fieldNames)
        {
            JsonNode node = root.get(fieldName);
            if (Objects.nonNull(node) && !node.isNull())
            {
                return node;
            }
        }
        return null;
    }

    /**
     * 去除常见 Markdown JSON 围栏。
     */
    private String stripJsonFence(String raw)
    {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```"))
        {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd)
        {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, lastFence).trim();
    }

    /**
     * 判断文本是否应按 JSON 解析。
     */
    private boolean looksLikeJson(String text)
    {
        return StrUtil.isNotBlank(text) && (text.startsWith("{") || text.startsWith("["));
    }

    /**
     * 组装图片详情 VO（含归属信息）。
     *
     */
    private RpsFormImageDetailVO buildDetailVO(AidRolePropSceneFormImage img,
                                               AidRolePropSceneForm form,
                                               AidRolePropScene asset)
    {
        return buildDetailVO(img, form, asset, null);
    }

    /**
     * 组装图片详情 VO（含 C 端展示提示词）。
     */
    private RpsFormImageDetailVO buildDetailVO(AidRolePropSceneFormImage img,
                                               AidRolePropSceneForm form,
                                               AidRolePropScene asset,
                                               String promptText)
    {
        // canSplit：仅 scene 类型 + 未被拆过(is_split_source=0) + 非拆分产物(is_split_child=0) 才可拆
        String assetType = Objects.nonNull(asset) ? asset.getAssetType() : null;
        boolean canSplit = ASSET_TYPE_SCENE.equals(assetType)
                && Objects.equals(0, img.getIsSplitSource())
                && Objects.equals(0, img.getIsSplitChild());
        List<String> referenceImages = mediaUrlResolver.toFullUrls(
                deserializeReferenceImages(img.getReferenceImages()));
        return RpsFormImageDetailVO.builder()
                .id(img.getId())
                .formId(img.getFormId())
                .formName(Objects.nonNull(form) ? form.getName() : null)
                .assetId(img.getAssetId())
                .assetName(Objects.nonNull(asset) ? asset.getName() : null)
                .assetType(assetType)
                .projectId(img.getProjectId())
                .episodeId(img.getEpisodeId())
                .name(img.getName())
                .imageUrl(img.getImageUrl())
                .sourceType(img.getSourceType())
                .promptText(promptText)
                .descriptionIndex(img.getDescriptionIndex())
                .isUse(img.getIsUse())
                .imageStatus(img.getImageStatus())
                .failReason(img.getFailReason())
                .referenceImages(referenceImages)
                .sortOrder(img.getSortOrder())
                .canSplit(canSplit)                                 // 是否可拆分四宫格
                .build();
    }

    /** C端单图详情查询字段，明确排除内部 prompt_snapshot。 */
    private LambdaQueryWrapper<AidRolePropSceneFormImage> detailImageSelectWrapper()
    {
        return Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getFormId,
                        AidRolePropSceneFormImage::getAssetId, AidRolePropSceneFormImage::getProjectId,
                        AidRolePropSceneFormImage::getEpisodeId, AidRolePropSceneFormImage::getName,
                        AidRolePropSceneFormImage::getImageUrl, AidRolePropSceneFormImage::getSourceType,
                        AidRolePropSceneFormImage::getDescriptionIndex,
                        AidRolePropSceneFormImage::getReferenceImages,
                        AidRolePropSceneFormImage::getSortOrder, AidRolePropSceneFormImage::getIsUse,
                        AidRolePropSceneFormImage::getImageStatus, AidRolePropSceneFormImage::getFailReason,
                        AidRolePropSceneFormImage::getIsSplitSource,
                        AidRolePropSceneFormImage::getIsSplitChild);
    }

    /**
     * 反序列化 form_image.reference_images 为 List。失败返 null + warn。
     */
    private List<String> deserializeReferenceImages(String json)
    {
        if (StrUtil.isBlank(json))
        {
            return null;
        }
        try
        {
            return OBJECT_MAPPER.readValue(json, OBJECT_MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        }
        catch (Exception e)
        {
            log.warn("reference_images 解析失败: jsonLen={}, err={}", StrUtil.length(json), e.getMessage());
            return null;
        }
    }
    @Override
    public AssetExtractTaskVO upscaleImage(RpsFormImageUpscaleRequest request, Long userId)
    {
        UpscaleBillingPlan plan = prepareUpscaleBillingPlan(request, userId, true);
        AidRolePropSceneFormImage img = plan.image();
        String effectiveModelCode = plan.modelCode();
        String effectiveResolution = plan.resolution();
        String fullReferenceUrl = plan.referenceUrl();

        String lockKey = buildUpscaleLockKey(request.getImageId());
        Boolean locked = redisCache.redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", UPSCALE_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (locked == null || !locked)
        {
            throw new RuntimeException("任务处理中");
        }

        Long taskId = null;
        try
        {
            AidExtractTask task = new AidExtractTask();
            task.setProjectId(img.getProjectId());
            task.setEpisodeId(img.getEpisodeId());
            task.setUserId(userId);
            task.setTaskType(TASK_TYPE_IMAGE_UPSCALE);
            task.setModelCode(effectiveModelCode);
            // inputSnapshot 保存高清任务上下文参数
            try
            {
                Map<String, Object> inputMap = new LinkedHashMap<>();
                inputMap.put("imageId", request.getImageId());
                inputMap.put("modelCode", effectiveModelCode);
                inputMap.put("resolution", effectiveResolution);
                inputMap.put("referenceUrl", fullReferenceUrl);
                inputMap.put("rawImageUrl", img.getImageUrl());
                task.setInputSnapshot(OBJECT_MAPPER.writeValueAsString(inputMap));
            }
            catch (Exception e)
            {
                task.setInputSnapshot("{\"imageId\":" + request.getImageId() + "}");
            }
            task.setStatus(TASK_STATUS_PENDING);
            task.setTotalCount(0);
            task.setDelFlag(DEL_FLAG_NORMAL);
            task.setCreateTime(DateUtils.getNowDate());
            task.setCreateBy(String.valueOf(userId));
            extractTaskService.save(task);

            taskId = task.getId();
            sendUpscaleMqMessage(taskId, img.getProjectId(), img.getEpisodeId(), userId, effectiveModelCode);

            return AssetExtractTaskVO.builder()
                    .taskId(taskId)
                    .status(TASK_STATUS_PENDING)
                    .build();
        }
        catch (RuntimeException e)
        {
            // MQ发送失败或同步校验抵达此处，将任务标记为 FAILED，避免僵尸 PENDING
            markTaskFailedQuietly(taskId, e.getMessage());
            redisCache.deleteObject(lockKey);
            throw e;
        }
    }

    @Override
    public BillingQuoteVO quoteUpscaleImage(RpsFormImageUpscaleRequest request, Long userId)
    {
        UpscaleBillingPlan plan = prepareUpscaleBillingPlan(request, userId, false);
        MediaImageGenerateRequest mediaRequest = buildUpscaleMediaRequest(
                plan.image().getProjectId(), plan.image().getEpisodeId(), userId,
                plan.modelCode(), plan.resolution(), plan.referenceUrl(), null);
        return mediaBillingQuoteService.quoteImage("FORM_IMAGE_UPSCALE", mediaRequest, 1);
    }

    private UpscaleBillingPlan prepareUpscaleBillingPlan(
            RpsFormImageUpscaleRequest request, Long userId, boolean validateRemoteImage)
    {
        LambdaQueryWrapper<AidRolePropSceneFormImage> imgQuery = Wrappers.lambdaQuery();
        imgQuery.eq(AidRolePropSceneFormImage::getId, request.getImageId());
        imgQuery.eq(AidRolePropSceneFormImage::getUserId, userId);
        imgQuery.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
        AidRolePropSceneFormImage img = rpsFormImageService.getOne(imgQuery);
        if (Objects.isNull(img))
        {
            log.info("高清任务提交失败，图片不存在或不属于当前用户: imageId={}", request.getImageId());
            throw new RuntimeException("图片不存在");
        }
        // 校验当前图片有 URL 可作参考图
        if (StrUtil.isBlank(img.getImageUrl()))
        {
            log.info("高清任务提交失败，图片URL为空: imageId={}", request.getImageId());
            throw new RuntimeException("图片无内容");
        }

        String effectiveModelCode = StrUtil.trimToNull(request.getModelCode());
        if (StrUtil.isBlank(effectiveModelCode))
        {
            log.info("高清任务提交失败，未传模型: imageId={}", request.getImageId());
            throw new RuntimeException("请选择模型");
        }
        // 校验：模型存在 / 启用 / model_type=image / 在 func_code=image_upscale 的功能池内
        // 高清"能力"由功能池统一治理（运营把能做高清的模型加入池即可），无需额外卡 imageRefine 能力位。
        validateModelInUpscalePool(effectiveModelCode);
        AiModelConfigVo modelConfig = aiModelConfigService.selectForBusiness(effectiveModelCode, FUNC_CODE_IMAGE_UPSCALE, null);
        if (Objects.isNull(modelConfig))
        {
            log.error("高清任务提交失败，模型不存在: modelCode={}", effectiveModelCode);
            throw new RuntimeException("模型无效");
        }

        String effectiveResolution = resolveUpscaleResolution(request.getResolution(), modelConfig, effectiveModelCode);

        String fullReferenceUrl = validateRemoteImage
                ? resolveAccessibleImageUrl(img.getImageUrl(), request.getImageId())
                : mediaUrlResolver.toFullUrl(img.getImageUrl());
        return new UpscaleBillingPlan(
                img, effectiveModelCode, effectiveResolution, fullReferenceUrl);
    }

    private record UpscaleBillingPlan(AidRolePropSceneFormImage image,
            String modelCode, String resolution, String referenceUrl)
    {
    }

    /**
     * 尝试将任务标记为 FAILED（仅当 task 已入库时）。
     * 不抛异常，避免干扰外层 catch 逻辑。
     */
    private void markTaskFailedQuietly(Long taskId, String errorMessage)
    {
        if (Objects.isNull(taskId))
        {
            return;
        }
        try
        {
            String safeMsg = StrUtil.isNotBlank(errorMessage) ? errorMessage : "提交失败";
            LambdaUpdateWrapper<AidExtractTask> update = Wrappers.lambdaUpdate();
            update.eq(AidExtractTask::getId, taskId);
            update.in(AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
            update.set(AidExtractTask::getStatus, TASK_STATUS_FAILED);
            update.set(AidExtractTask::getErrorMessage, safeMsg)
                .set(AidExtractTask::getErrorDetailJson, TaskErrorSnapshot.fromMessage(safeMsg));
            update.set(AidExtractTask::getUpdateTime, DateUtils.getNowDate());
            extractTaskService.getBaseMapper().update(null, update);
        }
        catch (Exception ex)
        {
            log.error("标记任务FAILED失败: taskId={}", taskId, ex);
        }
    }
    /**
     * 构建高清防重锁 Redis Key（请求侧 / 消费侧必须使用同一个 key）。
     *
     * @param imageId 图片ID
     * @return 锁 key，格式：asset:form:lock:image_upscale:{imageId}
     */
    public static String buildUpscaleLockKey(Long imageId)
    {
        return UPSCALE_LOCK_PREFIX + imageId;
    }

    /**
     * 归一化高清异步阶段的错误信息，只返回 ≤6 字的短文案。
     * 原始 provider 错误由调用方 log.error 打印，此方法仅负责用户可见文案。
     *
     * @param e 异常
     * @return 短错误文案
     */
    public static String normalizeUpscaleAsyncError(Throwable e)
    {
        String msg = (e != null) ? e.getMessage() : null;
        if (StrUtil.isBlank(msg))
        {
            return "生成失败";
        }
        if (msg.contains("图片无效")) return "图片无效";
        if (msg.contains("档位不支持")) return "档位不支持";
        if (msg.contains("模型无效")) return "模型无效";
        if (msg.contains("模型不符")) return "模型不符";
        if (msg.contains("生成超时")) return "生成超时";
        if (msg.contains("生成中断")) return "生成中断";
        if (msg.contains("存储失败")) return "存储失败";
        if (msg.contains("参数异常")) return "参数异常";
        if (msg.contains("任务不存在")) return "任务不存在";
        return "生成失败";
    }
    /**
     * 发送高清任务 MQ 消息。
     * 复用 ASSET_EXTRACT_TOPIC 主题，使用 image_upscale tag 区分。
     */
    private void sendUpscaleMqMessage(Long taskId, Long projectId, Long episodeId,
                                       Long userId, String modelCode)
    {
        // 双模式派发统一收口：MQ 开发 MQ（Consumer 消费）；MQ 关走本地线程（同一执行体 doUpscaleImage）
        boolean enqueued = dualModeTaskDispatcher.dispatch(taskId, projectId, episodeId, userId, modelCode,
                TASK_TYPE_IMAGE_UPSCALE, () -> runUpscaleLocally(taskId, userId));
        if (!enqueued)
        {
            log.error("高清任务入队失败(可能已取消/推进): taskId={}", taskId);
            throw new RuntimeException("任务提交失败");
        }
    }

    /**
     * 本地模式执行体：与 {@code AssetExtractConsumer#handleImageUpscale} 完全同义的单图高清终态编排，
     * 区别仅在于触发来源为本地线程池而非 MQ Consumer。
     */
    private void runUpscaleLocally(Long taskId, Long userId)
    {
        String dispatchToken = taskQueueService.currentLocalDispatchToken(taskId);
        Long imageId = resolveImageIdFromTask(taskId);
        String lockKey = buildUpscaleLockKey(imageId);

        updateUpscaleStatus(taskId, "PROCESSING", null, null);
        // 登记执行租约（重启自愈据租约判活）：本地派发只在放行时写过一次 90s 租约，
        // 若不登记心跳常驻集合，超过 90s 的高清任务租约会自然过期，被运行期僵尸回收误判为"进程已死"
        // → FAILED + 退款，而本地线程仍在跑并最终写回 SUCCEEDED，造成"已退款又出图"的免费生成。
        // 与 MQ 消费侧 onMessage 及其它本地执行体（分镜高清/编辑图等）保持一致，进入 PROCESSING 即登记续租。
        assetExtractService.markTaskProcessing(taskId);

        // 执行前取消检查
        if (assetExtractService.isTaskCancelled(taskId))
        {
            updateUpscaleStatus(taskId, "CANCELLED", "用户取消", null);
            sseManager.sendCancelled(taskId, "用户取消");
            assetExtractService.clearCancelFlag(taskId, dispatchToken);
            try { redisCache.deleteObject(lockKey); } catch (Exception ignore) { /* ignore */ }
            log.info("本地高清任务执行前检测到取消: taskId={}", taskId);
            return;
        }

        sseManager.sendStepProgress(taskId, "image_upscale", 20,
                "image_gen", "正在进行图片高清处理...", 1, 1);
        try
        {
            Map<String, Object> result = doUpscaleImage(taskId, userId);

            // 执行后取消检查：图已覆盖，保留结果快照，状态标 CANCELLED
            if (assetExtractService.isTaskCancelled(taskId))
            {
                updateUpscaleStatus(taskId, "CANCELLED", "用户取消", OBJECT_MAPPER.writeValueAsString(result));
                sseManager.sendCancelled(taskId, "用户取消");
                assetExtractService.clearCancelFlag(taskId, dispatchToken);
                log.info("本地高清任务执行后检测到取消(resultData已保留): taskId={}", taskId);
                return;
            }

            updateUpscaleStatus(taskId, "SUCCEEDED", null, OBJECT_MAPPER.writeValueAsString(result));
            sseManager.sendComplete(taskId, result);
            log.info("本地高清完成: taskId={}, imageId={}", taskId, result.get("imageId"));
        }
        catch (Exception e)
        {
            log.error("本地高清失败: taskId={}, userId={}", taskId, userId, e);
            com.aid.common.error.TaskErrorResult error = com.aid.common.error.ErrorNormalizer.normalize(e);
            String msg = StrUtil.sub(StrUtil.blankToDefault(error.getUserMessage(), "生成失败"), 0, 80);
            updateUpscaleStatus(taskId, "FAILED", msg, null);
            sseManager.sendError(taskId, error);
        }
        finally
        {
            try { redisCache.deleteObject(lockKey); }
            catch (Exception ex) { log.warn("本地高清释放防重锁失败: taskId={}, lockKey={}", taskId, lockKey, ex); }
            try { assetExtractService.releaseTaskSlots(taskId, dispatchToken); }
            catch (Exception ex) { log.warn("本地高清释放名额异常(不影响业务): taskId={}", taskId, ex); }
        }
    }

    /** 本地高清：状态更新（可选 errorMessage / resultData），独立事务。 */
    private void updateUpscaleStatus(Long taskId, String status, String errorMessage, String resultJson)
    {
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.aid.aid.domain.AidExtractTask> update =
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate();
        update.eq(com.aid.aid.domain.AidExtractTask::getId, taskId);
        // 终态即最终：仅允许从 PENDING/PROCESSING 推进，杜绝把已被回收/取消判定的终态（FAILED/CANCELLED）
        // 又覆盖回 SUCCEEDED，造成"已退款又出图"的免费生成
        update.in(com.aid.aid.domain.AidExtractTask::getStatus, TASK_STATUS_PENDING, TASK_STATUS_PROCESSING);
        update.set(com.aid.aid.domain.AidExtractTask::getStatus, status);
        if (Objects.nonNull(errorMessage))
        {
            update.set(com.aid.aid.domain.AidExtractTask::getErrorMessage, errorMessage);
        }
        if (Objects.nonNull(resultJson))
        {
            update.set(com.aid.aid.domain.AidExtractTask::getResultData, resultJson);
        }
        update.set(com.aid.aid.domain.AidExtractTask::getUpdateTime, com.aid.common.utils.DateUtils.getNowDate());
        extractTaskService.update(update);
    }

    /** 从 aid_extract_task.input_snapshot 读取 imageId（构建锁 key 用）；解析失败返回 null。 */
    private Long resolveImageIdFromTask(Long taskId)
    {
        try
        {
            com.aid.aid.domain.AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
            if (Objects.isNull(task) || StrUtil.isBlank(task.getInputSnapshot()))
            {
                return null;
            }
            Map<String, Object> snapshot = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
            Object idVal = snapshot.get("imageId");
            return Objects.isNull(idVal) ? null : Long.valueOf(String.valueOf(idVal));
        }
        catch (Exception e)
        {
            log.warn("本地高清解析 imageId 失败: taskId={}", taskId, e);
            return null;
        }
    }

    /** 读取 MQ 全局开关；读失败按未启用（本地模式）降级。 */
    private boolean isMqEnabled()
    {
        try
        {
            return rocketMqConfigManager.isEnabled();
        }
        catch (Exception e)
        {
            log.warn("读取MQ配置失败，默认走本地模式: {}", e.getMessage());
            return false;
        }
    }
    /**
     * 执行高清生成（由 Consumer 调用，不要在 Controller 中直接调用）。
     *
     * @param taskId 高清任务ID
     * @param userId 用户ID
     * @return 结果 Map（imageId / imageUrl / modelCode / resolution）
     */
    @Override
    public Map<String, Object> doUpscaleImage(Long taskId, Long userId)
    {
        AidExtractTask task = extractTaskService.selectAidExtractTaskById(taskId);
        if (Objects.isNull(task))
        {
            throw new RuntimeException("任务不存在");
        }

        Map<String, Object> snapshot;
        try
        {
            snapshot = OBJECT_MAPPER.readValue(task.getInputSnapshot(), Map.class);
        }
        catch (Exception e)
        {
            log.error("解析高清任务inputSnapshot失败: taskId={}", taskId, e);
            throw new RuntimeException("参数异常");
        }

        // 从 snapshot 提取业务参数
        Long imageId = Long.valueOf(String.valueOf(snapshot.get("imageId")));
        String modelCode = String.valueOf(snapshot.get("modelCode"));
        String resolution = snapshot.get("resolution") != null ? String.valueOf(snapshot.get("resolution")) : null;
        String referenceUrl = String.valueOf(snapshot.get("referenceUrl"));

        log.info("高清MQ消费开始: taskId={}, imageId={}, modelCode={}, resolution={}, refUrl={}",
                taskId, imageId, modelCode, resolution, referenceUrl);

        MediaImageGenerateRequest imageRequest = buildUpscaleMediaRequest(
                task.getProjectId(), task.getEpisodeId(), userId, modelCode,
                resolution, referenceUrl, taskId);

        MediaTaskResponse imageResponse = mediaGenerationService.generateImage(imageRequest);

        String newImageUrl = resolveUpscaleImageUrl(imageResponse);
        if (StrUtil.isBlank(newImageUrl))
        {
            log.error("高清生成失败，结果URL为空: taskId={}, imageId={}", taskId, imageId);
            throw new RuntimeException("生成失败");
        }

        //    注意：DB 里仍然写相对路径（与其它 form_image 记录格式一致，由 @MediaUrl 在出参时统一拼域名）
        updateUpscaledImageInTx(imageId, newImageUrl, userId);
        log.info("高清生成完成，已覆盖原图: taskId={}, imageId={}, newUrl={}", taskId, imageId, newImageUrl);

        //    注意：result_data 是原始 JSON 字符串透传（不经 @MediaUrl 序列化），
        //    因此这里手动调用 mediaUrlResolver.toFullUrl 把相对路径补齐成完整 URL（本地域名 / OSS CDN），
        //    保证任务详情 / SSE complete 事件里返回的 imageUrl 与 /form-image/list 返回值语义一致。
        String fullImageUrl = mediaUrlResolver.toFullUrl(newImageUrl);
        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("imageId", imageId);
        resultMap.put("imageUrl", fullImageUrl);
        resultMap.put("modelCode", modelCode);
        resultMap.put("resolution", resolution);
        return resultMap;
    }

    private MediaImageGenerateRequest buildUpscaleMediaRequest(Long projectId, Long episodeId,
            Long userId, String modelCode, String resolution, String referenceUrl,
            Long bizTaskId)
    {
        MediaImageGenerateRequest imageRequest = new MediaImageGenerateRequest();
        imageRequest.setModelName(modelCode);
        imageRequest.setBusinessFuncCode(FUNC_CODE_IMAGE_UPSCALE);
        imageRequest.setUserId(userId);
        imageRequest.setPrompt(UPSCALE_FALLBACK_PROMPT);
        imageRequest.setReferenceImageUrl(referenceUrl);
        imageRequest.setExpectedImageCount(1);
        imageRequest.setProjectId(projectId);
        imageRequest.setEpisodeId(episodeId);
        Map<String, Object> options = new HashMap<>();
        if (StrUtil.isNotBlank(resolution))
        {
            options.put("resolution", resolution);
        }
        options.put("force_single", true);
        imageRequest.setOptions(options);
        // 注入业务任务ID，破除幂等复用
        imageRequest.setBizTaskId(bizTaskId);
        imageRequest.setBizTaskType(TASK_TYPE_IMAGE_UPSCALE);
        return imageRequest;
    }

    /**
     * 高清完成后覆盖写库（用 TransactionTemplate 缩小事务边界，不包裹远程调用和轮询）。
     */
    private void updateUpscaledImageInTx(Long imageId, String newImageUrl, Long userId)
    {
        transactionTemplate.executeWithoutResult(status -> {
            Date now = DateUtils.getNowDate();
            LambdaUpdateWrapper<AidRolePropSceneFormImage> upd = Wrappers.lambdaUpdate();
            upd.eq(AidRolePropSceneFormImage::getId, imageId);
            upd.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
            upd.set(AidRolePropSceneFormImage::getImageUrl, newImageUrl);
            upd.set(AidRolePropSceneFormImage::getUpdateTime, now);
            upd.set(AidRolePropSceneFormImage::getUpdateBy, String.valueOf(userId));
            rpsFormImageService.update(upd);
        });
    }

    /**
     * 校验模型在 {@code func_code=image_upscale} 的功能池内（高清模型必须前端传入且在池内）。
     * 校验链：功能池存在/启用 → 池非空 → 模型存在/启用/{@code model_type=image} → 模型 ID 在池内。
     * 任意不满足抛出模型相关短文案。
     */
    private void validateModelInUpscalePool(String modelCode) {
        businessModelResolver.resolve(FUNC_CODE_IMAGE_UPSCALE, modelCode, MODEL_TYPE_IMAGE);
    }

    /**
     * 将 DB 存储的图片地址解析为上游 provider 可直接下载的完整 URL。
     *
     * @param rawImageUrl DB 中存储的原始图片地址
     * @param imageId     图片记录 ID，日志用
     * @return 完整可下载 URL（以 http/https 开头）
     */
    private String resolveAccessibleImageUrl(String rawImageUrl, Long imageId)
    {
        if (StrUtil.isBlank(rawImageUrl))
        {
            log.error("参考图URL为空，无法解析: imageId={}", imageId);
            throw new RuntimeException("图片无效");
        }
        // 复用项目统一媒体URL拼接器：DB相对路径 → 完整URL
        String fullUrl = mediaUrlResolver.toFullUrl(rawImageUrl);
        // 校验解析后是否为完整可访问 URL
        if (StrUtil.isBlank(fullUrl) || (!fullUrl.toLowerCase().startsWith("http://")
                && !fullUrl.toLowerCase().startsWith("https://")))
        {
            log.error("参考图URL解析后仍非完整URL，可能域名未配置: imageId={}, rawUrl={}, resolved={}",
                    imageId, rawImageUrl, fullUrl);
            throw new RuntimeException("图片无效");
        }
        return fullUrl;
    }

    /**
     * 解析高清档位：白名单一律取自模型 {@code capability_json.sizeOptions}，返回配置中的规范写法，
     * 保证计费 SKU 匹配与厂商下发同源。
     * 前端传值未命中白名单直接抛「档位不支持」；未传值取白名单内的最高档（高清场景语义）；
     * 模型未声明档位时返回 null，由模型按自身默认能力执行。
     */
    private String resolveUpscaleResolution(String requestedResolution,
                                            AiModelConfigVo modelConfig, String modelCode)
    {
        List<String> supportedOptions = ModelCapabilityResolver.readOptions(
                ModelCapabilityResolver.parseCapability(modelConfig.getCapabilityJson()),
                ModelCapabilityResolver.KEY_SIZE_OPTIONS);
        boolean supportsResolution = CollectionUtil.isNotEmpty(supportedOptions);

        if (StrUtil.isNotBlank(requestedResolution))
        {
            if (!supportsResolution)
            {
                // 无已知支持范围，无法校验前端传入值，直接拒绝
                log.info("模型无已知分辨率支持范围，无法校验前端传值，拒绝: modelCode={}, resolution={}",
                        modelCode, requestedResolution);
                throw new RuntimeException("档位不支持");
            }
            String matched = ModelCapabilityResolver.matchOption(supportedOptions, requestedResolution);
            if (Objects.isNull(matched))
            {
                log.info("前端传入 resolution 不在模型支持范围: modelCode={}, requested={}, supported={}",
                        modelCode, requestedResolution, supportedOptions);
                throw new RuntimeException("档位不支持");
            }
            return matched;
        }

        // 前端未传 resolution：模型未声明档位，由模型按自身默认最高能力执行
        if (!supportsResolution)
        {
            log.info("模型不支持分辨率参数，由模型按自身默认最高能力执行: modelCode={}", modelCode);
            return null;
        }

        // 高清场景语义：未指定时取白名单内的最高档
        String maxResolution = pickHighestResolution(supportedOptions);
        log.info("前端未传 resolution，自动取模型最高档位: modelCode={}, resolution={}, supported={}",
                modelCode, maxResolution, supportedOptions);
        return maxResolution;
    }

    /**
     * 从白名单中按 RESOLUTION_RANK 排序取最高档，返回白名单内的规范写法。
     * 全部不在 RESOLUTION_RANK 中（自定义档位名）时取白名单首项兜底。
     */
    private String pickHighestResolution(List<String> supportedOptions)
    {
        String highest = null;
        int highestIdx = -1;
        for (String opt : supportedOptions)
        {
            int idx = RESOLUTION_RANK.indexOf(ModelCapabilityResolver.normalize(opt));
            if (idx > highestIdx)
            {
                highestIdx = idx;
                highest = opt;
            }
        }
        if (Objects.isNull(highest) && CollectionUtil.isNotEmpty(supportedOptions))
        {
            highest = supportedOptions.get(0);
        }
        return highest;
    }

    /**
     * 从图片生成响应中获取最终高清图 URL。
     * 支持同步模型（直接 SUCCEEDED）和异步模型（PROCESSING → 轮询远端等待完成）。
     * 上游 provider 返回的原始错误只写日志，不透传给前端；前端统一收到 ≤6 字短异常。
     */
    private String resolveUpscaleImageUrl(MediaTaskResponse imageResponse)
    {
        if (Objects.isNull(imageResponse))
        {
            throw new RuntimeException("生成失败");
        }

        if (Objects.equals(TASK_STATUS_SUCCEEDED, imageResponse.getStatus()))
        {
            if (StrUtil.isBlank(imageResponse.getOssUrl()))
            {
                log.error("高清同步成功但 ossUrl 为空: mediaTaskId={}, originUrl={}",
                        imageResponse.getTaskId(), imageResponse.getOriginUrl());
                throw new RuntimeException("存储失败");
            }
            return imageResponse.getOssUrl();
        }

        if (!IMAGE_IN_PROGRESS_STATUSES.contains(imageResponse.getStatus()))
        {
            log.error("高清生成终态失败: mediaTaskId={}, status={}, error={}",
                    imageResponse.getTaskId(), imageResponse.getStatus(), imageResponse.getErrorMessage());
            throw new RuntimeException("生成失败");
        }

        Long mediaTaskId = imageResponse.getTaskId();
        if (Objects.isNull(mediaTaskId))
        {
            log.error("异步高清任务缺少 taskId, 无法轮询");
            throw new RuntimeException("生成失败");
        }

        log.info("高清图异步生成中, 开始轮询: mediaTaskId={}", mediaTaskId);
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
                throw new RuntimeException("生成中断");
            }

            // 查远端 + 刷本地 + SUCCEEDED 但 ossUrl 空时即时重试持久化
            MediaTaskResponse polled = mediaGenerationService.queryTaskRefresh(mediaTaskId);
            if (Objects.isNull(polled))
            {
                log.error("轮询高清任务返回空: mediaTaskId={}", mediaTaskId);
                throw new RuntimeException("生成失败");
            }

            if (Objects.equals(TASK_STATUS_SUCCEEDED, polled.getStatus()))
            {
                if (StrUtil.isBlank(polled.getOssUrl()))
                {
                    // 下一轮轮询再试，仅当超时才落到外层超时报错
                    log.warn("异步高清成功但 ossUrl 暂空，等待下一轮持久化: mediaTaskId={}, originUrl={}",
                            mediaTaskId, polled.getOriginUrl());
                    continue;
                }
                log.info("高清图异步生成完成: mediaTaskId={}, url={}", mediaTaskId, polled.getOssUrl());
                return polled.getOssUrl();
            }
            if ("FAILED".equals(polled.getStatus()))
            {
                // 日志保留完整上游错误，抛出短异常
                log.error("异步高清生成失败: mediaTaskId={}, error={}", mediaTaskId, polled.getErrorMessage());
                throw new RuntimeException("生成失败");
            }
            // 仍在 PROCESSING/QUEUED/PENDING，继续轮询
        }

        // 超时
        log.error("异步高清生成超时: mediaTaskId={}, timeout={}s", mediaTaskId, IMAGE_POLL_TIMEOUT_SECONDS);
        throw new RuntimeException("生成超时");
    }
    @Override
    public RpsSceneFormImageBatchSplitVO splitSceneImageBatch(Long projectId, List<Long> sourceImageIds, Long userId)
    {
        // 单个 / 批量同接口：controller 已解析出去重有序的 sourceImageIds。
        // 内存友好关键：按源图逐张顺序串行处理——上一张大图字节（源图字节 + 4 张子图 PNG 字节）
        // 是 splitSceneImage 方法内的局部变量，方法返回后即不可达、等待 GC 回收，
        // "同一时刻内存中只驻留 1 张当前在处理的源图字节"。真正保障来自"顺序处理 + 不预先一次性 load"，不是 GC 时机。
        // 注意：results.add(vo) 仅累积 URL 元数据（不含字节），但仍随条目数线性增长——已在 controller 端限定上限。
        BatchOperationResultVO summary = new BatchOperationResultVO();
        List<RpsSceneFormImageSplitVO> results = new ArrayList<>();
        if (CollectionUtil.isEmpty(sourceImageIds))
        {
            log.info("场景批量拆分：入参为空, userId={}", userId);
            return RpsSceneFormImageBatchSplitVO.builder()
                    .summary(summary.summarize())
                    .results(results)
                    .build();
        }
        // 项目范围闸门：批量入口一次性校验项目归属，失败整体抛错
        validateProjectOwnership(projectId, userId);
        for (Long sourceImageId : sourceImageIds)
        {
            try
            {
                // 项目边界校验：先轻量探测 source 是否归属本用户且 project_id == 入参 projectId，
                // 越权条目按"项目不匹配"单条失败，避免无意义地走重型下载/切图/上传
                AidRolePropSceneFormImage probe = rpsFormImageService.getOne(
                        Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                                .select(AidRolePropSceneFormImage::getId, AidRolePropSceneFormImage::getProjectId)
                                .eq(AidRolePropSceneFormImage::getId, sourceImageId)
                                .eq(AidRolePropSceneFormImage::getUserId, userId)
                                .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                                .last("LIMIT 1"),
                        false);
                if (Objects.isNull(probe))
                {
                    summary.addFailure(sourceImageId, "源图不存在");
                    continue;
                }
                if (!Objects.equals(probe.getProjectId(), projectId))
                {
                    log.info("场景批量拆分-项目不匹配: sourceImageId={}, imgProjectId={}, reqProjectId={}",
                            sourceImageId, probe.getProjectId(), projectId);
                    summary.addFailure(sourceImageId, "项目不匹配");
                    continue;
                }
                // 复用单张拆分逻辑（其内部用 transactionTemplate 短事务入库，单张独立提交）
                RpsSceneFormImageSplitRequest one = new RpsSceneFormImageSplitRequest();
                one.setProjectId(projectId);
                one.setSourceImageId(sourceImageId);
                RpsSceneFormImageSplitVO vo = splitSceneImage(one, userId);
                results.add(vo);
                summary.addSuccess(sourceImageId);
            }
            catch (RuntimeException e)
            {
                // 单张失败：记录原因并继续处理后续源图，不影响已成功的拆分
                log.error("场景批量拆分-单张失败: sourceImageId={}, userId={}, err={}",
                        sourceImageId, userId, e.getMessage(), e);
                summary.addFailure(sourceImageId,
                        TaskErrorPresentation.toUserMessage(e.getMessage(), "拆分失败"));
            }
        }
        log.info("场景批量拆分完成: userId={}, projectId={}, total={}, success={}, fail={}",
                userId, projectId, sourceImageIds.size(), summary.getSuccessIds().size(), summary.getFailures().size());
        return RpsSceneFormImageBatchSplitVO.builder()
                .summary(summary.summarize())
                .results(results)
                .build();
    }

    /**
     * 项目归属一次性校验（项目存在 / 未删除 / 归属当前用户）。
     * 批量入口处先校验一次，失败直接整体抛错（不进入条目循环）。
     */
    private void validateProjectOwnership(Long projectId, Long userId)
    {
        if (Objects.isNull(projectId) || Objects.isNull(userId))
        {
            log.info("项目范围校验失败：参数缺失, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("参数缺失");
        }
        AidComicProject project = projectService.getOne(
                Wrappers.<AidComicProject>lambdaQuery()
                        .select(AidComicProject::getId)
                        .eq(AidComicProject::getId, projectId)
                        .eq(AidComicProject::getUserId, userId)
                        .eq(AidComicProject::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"));
        if (Objects.isNull(project))
        {
            log.info("项目范围校验失败：项目不存在或不属于当前用户, projectId={}, userId={}", projectId, userId);
            throw new ServiceException("项目不存在或无权限操作");
        }
    }

    @Override
    public RpsSceneFormImageSplitVO splitSceneImage(RpsSceneFormImageSplitRequest request, Long userId)
    {
        if (Objects.isNull(request) || Objects.isNull(userId) || Objects.isNull(request.getSourceImageId()))
        {
            log.info("场景拆分失败：参数缺失, hasReq={}, userId={}", Objects.nonNull(request), userId);
            throw new ServiceException("参数缺失");
        }
        Long sourceImageId = request.getSourceImageId();

        AidRolePropSceneFormImage sourceImage = rpsFormImageService.getOne(
                Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                        .select(AidRolePropSceneFormImage::getId,
                                AidRolePropSceneFormImage::getFormId,
                                AidRolePropSceneFormImage::getAssetId,
                                AidRolePropSceneFormImage::getProjectId,
                                AidRolePropSceneFormImage::getEpisodeId,
                                AidRolePropSceneFormImage::getUserId,
                                AidRolePropSceneFormImage::getName,
                                AidRolePropSceneFormImage::getImageUrl,
                                AidRolePropSceneFormImage::getDelFlag,
                                AidRolePropSceneFormImage::getIsSplitSource,
                                AidRolePropSceneFormImage::getIsSplitChild)
                        .eq(AidRolePropSceneFormImage::getId, sourceImageId)
                        .eq(AidRolePropSceneFormImage::getUserId, userId)
                        .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL)
                        .last("LIMIT 1"),
                false);
        if (Objects.isNull(sourceImage))
        {
            log.info("场景拆分失败：源图不存在或不属于当前用户, sourceImageId={}, userId={}",
                    sourceImageId, userId);
            throw new ServiceException("源图不存在");
        }
        // 后端从源图反查项目 / 剧集（入参不再携带，避免前端越权或传错归属）
        Long projectId = sourceImage.getProjectId();
        Long episodeId = sourceImage.getEpisodeId();
        // 已是拆分产物 → 不允许再拆（产物不可再拆）
        if (Objects.nonNull(sourceImage.getIsSplitChild()) && sourceImage.getIsSplitChild() == 1)
        {
            log.info("场景拆分失败：源图本身是拆分产物，不可再拆, sourceImageId={}", sourceImageId);
            throw new ServiceException("子图不可拆分");
        }
        // 已被拆过 → 不允许重复拆
        if (Objects.nonNull(sourceImage.getIsSplitSource()) && sourceImage.getIsSplitSource() == 1)
        {
            log.info("场景拆分失败：源图已被拆分, sourceImageId={}", sourceImageId);
            throw new ServiceException("内容已拆分");
        }
        if (StrUtil.isBlank(sourceImage.getImageUrl()))
        {
            log.info("场景拆分失败：源图URL为空, sourceImageId={}", sourceImageId);
            throw new ServiceException("源图无内容");
        }

        // 防漏字段：仅查 assetType / userId / delFlag 用作类型与权属判定
        AidRolePropScene asset = rpsService.getOne(
                Wrappers.<AidRolePropScene>lambdaQuery()
                        .select(AidRolePropScene::getId, AidRolePropScene::getUserId,
                                AidRolePropScene::getAssetType, AidRolePropScene::getDelFlag,
                                AidRolePropScene::getName)
                        .eq(AidRolePropScene::getId, sourceImage.getAssetId())
                        .last("LIMIT 1"),
                false);
        if (Objects.isNull(asset)
                || !DEL_FLAG_NORMAL.equals(asset.getDelFlag())
                || !Objects.equals(userId, asset.getUserId()))
        {
            log.info("场景拆分失败：主资产不存在或不属于当前用户, assetId={}, userId={}",
                    sourceImage.getAssetId(), userId);
            throw new ServiceException("资产不存在");
        }
        if (!ASSET_TYPE_SCENE.equals(asset.getAssetType()))
        {
            log.info("场景拆分失败：仅场景可拆分, assetId={}, assetType={}",
                    asset.getId(), asset.getAssetType());
            throw new ServiceException("仅场景可拆");
        }

        // 防漏字段：仅查归属与命名所需字段
        AidRolePropSceneForm form = rpsFormService.getOne(
                Wrappers.<AidRolePropSceneForm>lambdaQuery()
                        .select(AidRolePropSceneForm::getId, AidRolePropSceneForm::getAssetId,
                                AidRolePropSceneForm::getUserId, AidRolePropSceneForm::getDelFlag,
                                AidRolePropSceneForm::getProjectId, AidRolePropSceneForm::getEpisodeId,
                                AidRolePropSceneForm::getName)
                        .eq(AidRolePropSceneForm::getId, sourceImage.getFormId())
                        .last("LIMIT 1"),
                false);
        if (Objects.isNull(form)
                || !DEL_FLAG_NORMAL.equals(form.getDelFlag())
                || !Objects.equals(userId, form.getUserId()))
        {
            log.info("场景拆分失败：形态不存在或不属于当前用户, formId={}, userId={}",
                    sourceImage.getFormId(), userId);
            throw new ServiceException("形态不存在");
        }
        // 显式断言：form 与请求的 projectId / episodeId 必须严格一致
        // （sourceImage 校验已通过，form 与 sourceImage 同源；这里加一道防御性兜底，避免历史脏数据导致跨项目越权）
        if (!Objects.equals(projectId, form.getProjectId())
                || !Objects.equals(episodeId, form.getEpisodeId()))
        {
            log.info("场景拆分失败：形态项目/剧集不匹配, formId={}, reqProjectId={}, reqEpisodeId={}, " +
                            "formProjectId={}, formEpisodeId={}",
                    form.getId(), projectId, episodeId, form.getProjectId(), form.getEpisodeId());
            throw new ServiceException("形态数据异常");
        }
        // 显式断言：form 的 assetId 必须等于 sourceImage 的 assetId（防 form 被改主资产后 sourceImage 仍指向旧 form 的脏数据）
        if (!Objects.equals(form.getAssetId(), sourceImage.getAssetId()))
        {
            log.info("场景拆分失败：形态主资产与源图主资产不匹配, formAssetId={}, imageAssetId={}",
                    form.getAssetId(), sourceImage.getAssetId());
            throw new ServiceException("数据异常");
        }

        OssProperties ossProps = Objects.nonNull(ossConfigManager) ? ossConfigManager.getOssProperties() : null;
        // 本地上传的 /profile 路径直接读取受管理的文件，避免依赖可选的资源访问域名。
        Path localSource = resolveLocalSceneSourceFile(sourceImage.getImageUrl());
        byte[] sourceBytes = localSource != null
                ? readLocalImageBytes(localSource, MAX_SOURCE_IMAGE_BYTES, sourceImageId)
                : downloadImageBytes(mediaUrlResolver.toFullUrl(sourceImage.getImageUrl()),
                        MAX_SOURCE_IMAGE_BYTES, sourceImageId);
        // 解码 → 切 4 宫格 → 各自编码为 PNG 字节
        List<byte[]> quadrantBytes = cropQuadrants(sourceBytes, sourceImageId);
        // 逐张上传，返回剥离配置域名后的相对路径（顺序与 SPLIT_POSITION_LABELS 一致）
        List<String> normalizedImageUrls = new ArrayList<>(quadrantBytes.size());
        for (int i = 0; i < quadrantBytes.size(); i++)
        {
            String uploadedUrl;
            try
            {
                // fileName 仅用于推断扩展名，真实存储名由 OssTemplate 内部生成
                uploadedUrl = ossTemplate.uploadBytes(quadrantBytes.get(i), "split." + SPLIT_CHILD_IMAGE_FORMAT, null);
            }
            catch (Exception e)
            {
                log.error("场景拆分失败：第{}张子图上传失败, sourceImageId={}, err={}", i + 1, sourceImageId, e.getMessage(), e);
                throw new ServiceException("上传失败，请重试");
            }
            // OSS 模式 uploadBytes 返回完整 URL，统一剥域名存相对路径（local 模式已是相对路径，幂等）
            normalizedImageUrls.add(stripConfiguredDomain(uploadedUrl, ossProps));
        }

        String baseName = StrUtil.isNotBlank(sourceImage.getName())
                ? sourceImage.getName()
                : buildFallbackName(asset, form);
        Date now = DateUtils.getNowDate();
        // 上传后的相对路径列表（effectively final，供事务内 lambda 引用）
        List<String> childImageUrls = normalizedImageUrls;

        List<AidRolePropSceneFormImage> children = transactionTemplate.execute(status ->
        {
            // 子图排序接在当前形态已有图片之后。
            // 防漏字段：仅 count 不取列；form 已校验 userId，这里同时按 userId 过滤双保险
            long existingCount = rpsFormImageService.count(
                    Wrappers.<AidRolePropSceneFormImage>lambdaQuery()
                            .eq(AidRolePropSceneFormImage::getFormId, form.getId())
                            .eq(AidRolePropSceneFormImage::getUserId, userId)
                            .eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL));

            // 按固定方位顺序构造四张子图。
            List<AidRolePropSceneFormImage> kids = new ArrayList<>(SPLIT_POSITION_LABELS.size());
            for (int i = 0; i < SPLIT_POSITION_LABELS.size(); i++)
            {
                String label = SPLIT_POSITION_LABELS.get(i);
                AidRolePropSceneFormImage child = new AidRolePropSceneFormImage();
                child.setFormId(form.getId());
                child.setAssetId(form.getAssetId());
                child.setProjectId(form.getProjectId());
                child.setEpisodeId(form.getEpisodeId());
                child.setUserId(userId);
                child.setName(baseName + "_" + label);              // 命名 源图name_主视/反打/左立面/右立面
                child.setImageUrl(childImageUrls.get(i));           // 已剥离域名的相对路径
                child.setSourceType(SPLIT_CHILD_SOURCE_TYPE);
                child.setSortOrder((int) (existingCount + i));
                child.setIsUse(1);
                child.setImageStatus("completed");
                child.setIsSplitSource(0);                          // 子图自己也是"未被拆过"
                child.setIsSplitChild(1);                           // 标记本图为拆分产物，不允许再作为源图
                child.setSplitParentImageId(sourceImage.getId());   // 排查用：指向源图
                child.setDelFlag(DEL_FLAG_NORMAL);
                child.setCreateTime(now);
                child.setCreateBy(String.valueOf(userId));
                child.setUpdateTime(now);
                child.setUpdateBy(String.valueOf(userId));
                kids.add(child);
            }
            if (!rpsFormImageService.saveBatch(kids))
            {
                log.error("场景拆分入库失败：saveBatch 返回 false, sourceImageId={}, formId={}",
                        sourceImageId, form.getId());
                throw new ServiceException("保存失败，请重试");
            }

            // 仅在源图尚未拆分时标记，防止并发重复产出子图。
            // 拆分成功后源图关闭启用，四张子图启用，后续引用只命中可用子图。
            LambdaUpdateWrapper<AidRolePropSceneFormImage> markSource = Wrappers.lambdaUpdate();
            markSource.eq(AidRolePropSceneFormImage::getId, sourceImageId);
            markSource.eq(AidRolePropSceneFormImage::getDelFlag, DEL_FLAG_NORMAL);
            markSource.eq(AidRolePropSceneFormImage::getUserId, userId);
            markSource.eq(AidRolePropSceneFormImage::getIsSplitSource, 0);
            markSource.set(AidRolePropSceneFormImage::getIsSplitSource, 1);
            markSource.set(AidRolePropSceneFormImage::getIsUse, 0);
            // 源图改名为聚合命名：源图原名_主视,反打,左立面,右立面（仅展示用，源图已被分镜匹配排除）
            markSource.set(AidRolePropSceneFormImage::getName, baseName + SPLIT_SOURCE_NAME_SUFFIX);
            markSource.set(AidRolePropSceneFormImage::getUpdateTime, now);
            markSource.set(AidRolePropSceneFormImage::getUpdateBy, String.valueOf(userId));
            if (!rpsFormImageService.update(markSource))
            {
                // 并发场景：另一线程刚拆过并已 CAS 标记；本线程整批回滚避免重复产出 4 张子图（上传的 OSS 文件成为孤儿，可接受）
                log.warn("场景拆分标记源图失败（可能被并发已标记）: sourceImageId={}", sourceImageId);
                throw new ServiceException("内容已拆分");
            }
            return kids;
        });

        List<RpsFormImageDetailVO> childVos = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++)
        {
            AidRolePropSceneFormImage c = children.get(i);
            childVos.add(RpsFormImageDetailVO.builder()
                    .id(c.getId())
                    .formId(c.getFormId())
                    .formName(form.getName())
                    .assetId(c.getAssetId())
                    .assetName(asset.getName())
                    .assetType(asset.getAssetType())
                    .projectId(c.getProjectId())
                    .episodeId(c.getEpisodeId())
                    .name(c.getName())
                    .imageUrl(c.getImageUrl())
                    .sourceType(c.getSourceType())
                    .isUse(c.getIsUse())
                    .imageStatus(c.getImageStatus())
                    .sortOrder(c.getSortOrder())
                    .build());
        }
        log.info("场景拆分完成: sourceImageId={}, formId={}, 新增子图={}, baseName={}",
                sourceImageId, form.getId(), children.size(), baseName);
        return RpsSceneFormImageSplitVO.builder()
                .sourceImageId(sourceImage.getId())
                .assetId(asset.getId())
                .formId(form.getId())
                .children(childVos)
                .build();
    }

    /**
     * 当源图 form_image.name 为空时的兜底命名。
     * 直接使用 form.name（已含"资产名_变更原因"完整语义），避免 assetName 与 formName 重复拼接复读。
     */
    private String buildFallbackName(AidRolePropScene asset, AidRolePropSceneForm form)
    {
        if (Objects.nonNull(form) && StrUtil.isNotBlank(form.getName()))
        {
            return form.getName();
        }
        // form.name 也缺失时，按资产名兜底
        return Objects.nonNull(asset) && StrUtil.isNotBlank(asset.getName()) ? asset.getName() : "场景";
    }

    /**
     * 读取已通过托管路径校验的本地源图，带大小上限保护。
     *
     * @param file          已解析的本地文件
     * @param maxBytes      最大允许字节数
     * @param sourceImageId 源图 ID（仅日志用）
     * @return 源图字节数组
     */
    private Path resolveLocalSceneSourceFile(String storedUrl)
    {
        if (!ossTemplate.isLocalFile(storedUrl))
        {
            return null;
        }
        try
        {
            URI uri = URI.create(storedUrl);
            String path = uri.getPath();
            String prefix = Constants.RESOURCE_PREFIX + "/upload/";
            if (uri.getRawQuery() != null || uri.getRawFragment() != null
                    || path == null || !path.startsWith(prefix)
                    || path.contains("..") || path.contains("\\"))
            {
                throw new ServiceException("源图路径无效");
            }
            Path base = Paths.get(AidAppConfig.getProfile()).toRealPath();
            Path file = base.resolve(path.substring(Constants.RESOURCE_PREFIX.length() + 1))
                    .normalize().toRealPath();
            if (!file.startsWith(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            {
                throw new ServiceException("源图路径无效");
            }
            return file;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (IOException | IllegalArgumentException e)
        {
            throw new ServiceException("源图读取失败");
        }
    }

    private byte[] readLocalImageBytes(Path file, long maxBytes, Long sourceImageId)
    {
        try
        {
            if (Files.size(file) > maxBytes)
            {
                throw new ServiceException("源图过大");
            }
            try (InputStream in = Files.newInputStream(file);
                 ByteArrayOutputStream out = new ByteArrayOutputStream())
            {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) != -1)
                {
                    total += count;
                    if (total > maxBytes)
                    {
                        throw new ServiceException("源图过大");
                    }
                    out.write(buffer, 0, count);
                }
                if (total == 0)
                {
                    throw new ServiceException("源图无内容");
                }
                return out.toByteArray();
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            log.error("场景拆分失败：读取本地源图异常, sourceImageId={}, err={}", sourceImageId, e.getMessage(), e);
            throw new ServiceException("源图读取失败");
        }
    }

    /** 下载远程源图字节，带大小上限保护。 */
    private byte[] downloadImageBytes(String fullUrl, long maxBytes, Long sourceImageId)
    {
        if (StrUtil.isBlank(fullUrl))
        {
            log.error("场景拆分失败：源图 URL 解析为空, sourceImageId={}", sourceImageId);
            throw new ServiceException("源图无内容");
        }
        HttpURLConnection conn = null;
        try
        {
            URL url = new URL(fullUrl);                                  // 源图地址
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);                 // 连接超时
            conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);                    // 读取超时
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);                       // 跟随重定向（CDN/OSS 可能 302）
            conn.setRequestProperty("User-Agent", "aid-server");         // 部分 CDN 对空 UA 返回 403，显式带 UA
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
            {
                log.error("场景拆分失败：源图下载响应异常, sourceImageId={}, httpCode={}", sourceImageId, code);
                throw new ServiceException("源图下载失败");
            }
            // Content-Length 已知且超限 → 提前拒绝
            long declaredLen = conn.getContentLengthLong();
            if (declaredLen > 0 && declaredLen > maxBytes)
            {
                log.error("场景拆分失败：源图超过大小上限, sourceImageId={}, len={}B, max={}B", sourceImageId, declaredLen, maxBytes);
                throw new ServiceException("源图过大");
            }
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream())
            {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1)
                {
                    total += n;
                    if (total > maxBytes)                                // 边读边校验上限
                    {
                        log.error("场景拆分失败：源图字节超过上限, sourceImageId={}, max={}B", sourceImageId, maxBytes);
                        throw new ServiceException("源图过大");
                    }
                    out.write(buf, 0, n);
                }
                byte[] bytes = out.toByteArray();
                if (bytes.length == 0)
                {
                    log.error("场景拆分失败：源图下载为空, sourceImageId={}", sourceImageId);
                    throw new ServiceException("源图无内容");
                }
                return bytes;
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("场景拆分失败：源图下载异常, sourceImageId={}, url={}, err={}", sourceImageId, fullUrl, e.getMessage(), e);
            throw new ServiceException("源图下载失败");
        }
        finally
        {
            if (Objects.nonNull(conn))
            {
                conn.disconnect();
            }
        }
    }

    /**
     * 把源图字节解码后沿宽高中线裁成 2×2 共 4 张子图，按 [左上, 右上, 左下, 右下] 顺序返回各自 PNG 字节。
     * 中线坐标按尺寸整除向下取整（容忍 1 像素误差），不对宽高比做额外校验；解码失败抛错触发回滚。
     *
     * @param sourceBytes   源图字节
     * @param sourceImageId 源图 ID（仅日志用）
     * @return 4 张子图 PNG 字节列表（顺序：左上→右上→左下→右下）
     */
    private List<byte[]> cropQuadrants(byte[] sourceBytes, Long sourceImageId)
    {
        BufferedImage src;
        try
        {
            src = ImageIO.read(new ByteArrayInputStream(sourceBytes));   // 解码
        }
        catch (Exception e)
        {
            log.error("场景拆分失败：源图解码异常, sourceImageId={}, err={}", sourceImageId, e.getMessage(), e);
            throw new ServiceException("源图解析失败");
        }
        if (Objects.isNull(src))
        {
            log.error("场景拆分失败：源图无法解码为图片, sourceImageId={}", sourceImageId);
            throw new ServiceException("源图格式有误");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 2 || h < 2)
        {
            log.error("场景拆分失败：源图尺寸过小无法四等分, sourceImageId={}, w={}, h={}", sourceImageId, w, h);
            throw new ServiceException("源图过小");
        }
        int halfW = w / 2;                                              // 宽中线（向下取整）
        int halfH = h / 2;                                              // 高中线（向下取整）
        // 四宫格区域，顺序严格 [左上, 右上, 左下, 右下]，与 SPLIT_POSITION_LABELS 一一对应
        int[][] regions = {
                {0, 0, halfW, halfH},                                   // 左上 = 主视
                {halfW, 0, w - halfW, halfH},                           // 右上 = 反打
                {0, halfH, halfW, h - halfH},                           // 左下 = 左立面
                {halfW, halfH, w - halfW, h - halfH}                    // 右下 = 右立面
        };
        List<byte[]> result = new ArrayList<>(regions.length);
        try
        {
            for (int[] r : regions)
            {
                // 拷贝到独立 BufferedImage（避免 getSubimage 共享 raster 导致编码异常；ARGB 保留可能的透明通道）
                BufferedImage sub = new BufferedImage(r[2], r[3], BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = sub.createGraphics();
                g.drawImage(src.getSubimage(r[0], r[1], r[2], r[3]), 0, 0, null);
                g.dispose();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                if (!ImageIO.write(sub, SPLIT_CHILD_IMAGE_FORMAT, baos))
                {
                    log.error("场景拆分失败：子图编码无可用 writer, sourceImageId={}, format={}", sourceImageId, SPLIT_CHILD_IMAGE_FORMAT);
                    throw new ServiceException("切图失败，请重试");
                }
                result.add(baos.toByteArray());
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("场景拆分失败：切图异常, sourceImageId={}, err={}", sourceImageId, e.getMessage(), e);
            throw new ServiceException("切图失败，请重试");
        }
        return result;
    }

    /**
     * 把前端传入的完整 URL 剥离配置的 cdnDomain / localDomain，得到与 @MediaUrl 字段语义一致的"相对路径"。
     */
    private String stripConfiguredDomain(String url, OssProperties properties)
    {
        if (StrUtil.isBlank(url))
        {
            return url;
        }
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
        {
            // 已是相对路径
            return url;
        }
        if (Objects.isNull(properties))
        {
            return url;
        }
        String cdnDomain = stripTrailingSlash(properties.getCdnDomain());
        if (StrUtil.isNotBlank(cdnDomain) && url.startsWith(cdnDomain))
        {
            String rest = url.substring(cdnDomain.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        String localDomain = stripTrailingSlash(properties.getLocalDomain());
        if (StrUtil.isNotBlank(localDomain) && url.startsWith(localDomain))
        {
            String rest = url.substring(localDomain.length());
            return rest.startsWith("/") ? rest : "/" + rest;
        }
        // 域名不在配置中（如外链），保留原样
        return url;
    }

    private String stripTrailingSlash(String domain)
    {
        if (StrUtil.isBlank(domain))
        {
            return domain;
        }
        return domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
    }
}
