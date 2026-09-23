package com.aid.rps.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.agent.IAidAgentService;
import com.aid.aid.domain.AidAgent;
import com.aid.aid.domain.AidComicEpisode;
import com.aid.aid.domain.AidComicScript;
import com.aid.aid.domain.AidRolePropScene;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.service.IAidComicEpisodeService;
import com.aid.aid.service.IAidComicScriptService;
import com.aid.aid.service.IAidMediaTaskService;
import com.aid.aid.service.IAidRolePropSceneService;
import com.aid.common.config.AidAppConfig;
import com.aid.common.utils.DateUtils;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.provider.TextOutputLimitResolver;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.media.service.IMediaGenerationService;
import com.aid.rps.model.ExistingAssetLib;
import com.aid.rps.service.IExtractBillingService;
import com.aid.rps.service.IExtractBillingService.TextCallBillingContext;
import com.aid.rps.service.impl.TextTaskExecutionRejectedException;
import com.aid.service.IAiModelConfigService;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import lombok.extern.slf4j.Slf4j;

/**
 * 资产提取可复用工具类，提供脚本加载、LLM调用、JSON解析、文本切片等通用方法。
 *
 * @author 视觉AID
 */
@Slf4j
@Component
public class AssetExtractHelper
{
    private static final String DEL_FLAG_NORMAL = "0";
    private static final String LLM_STATUS_SUCCEEDED = "SUCCEEDED";
    private static final int SCRIPT_STATUS_ACTIVE = 1;
    private static final int IS_EXTRACTED_YES = 1;

    /** 提示词缓存目录名（与 PromptLibBusinessServiceImpl 保持一致） */
    private static final String PROMPT_LIB_DIR = "lib/prompts";

    /** 智能体启用状态 */
    private static final int AGENT_STATUS_ENABLED = 1;

    /** 提示词缓存原子回写用的临时文件后缀 */
    private static final String PROMPT_CACHE_TEMP_SUFFIX = ".tmp";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String BIZ_TASK_TYPE_EXTRACT = "extract";
    private static final String CALL_INPUT_SHA_MARKER = ",inputSha=";
    private static final long REPLAY_CACHE_TTL_MILLIS = 5L * 60L * 1000L;
    private static final int REPLAY_CACHE_MAX_ENTRIES = 16;
    private static final long REPLAY_CACHE_MAX_TEXT_CHARS = 16L * 1024L * 1024L;
    private static final int WRITER_OUTPUT_TOKENS_PER_SOURCE_CHAR = 8;
    private static final int STANDARD_OUTPUT_TOKENS_PER_SOURCE_CHAR = 4;

    /** LLM 异步任务轮询最大等待时间（秒），默认 600s；由 aid.extract.llm-poll.timeout-seconds 配置 */
    @org.springframework.beans.factory.annotation.Value("${aid.extract.llm-poll.timeout-seconds:600}")
    private long llmPollTimeoutSeconds;

    /** LLM 异步任务轮询间隔（秒），默认 3s；由 aid.extract.llm-poll.interval-seconds 配置 */
    @org.springframework.beans.factory.annotation.Value("${aid.extract.llm-poll.interval-seconds:3}")
    private long llmPollIntervalSeconds;

    /** LLM 单次输出全局硬上限；具体结构化任务会在此上限内进一步收紧。 */
    @org.springframework.beans.factory.annotation.Value("${aid.extract.llm.max-tokens:65536}")
    private int llmMaxTokens;

    /** 普通结构化提取的单次输出上限；分镜脚本按本批正文长度单独计算。 */
    @org.springframework.beans.factory.annotation.Value("${aid.extract.llm.structured-max-tokens:8192}")
    private int llmStructuredMaxTokens;

    /** 分镜脚本动态输出上限的下限，避免短片段因固定字段较多而被截断。 */
    @org.springframework.beans.factory.annotation.Value("${aid.extract.llm.storyboard-script-min-tokens:16384}")
    private int storyboardScriptMinTokens;

    /** 分镜脚本动态输出上限的上限；同时受全局 max-tokens 限制。 */
    @org.springframework.beans.factory.annotation.Value("${aid.extract.llm.storyboard-script-max-tokens:49152}")
    private int storyboardScriptMaxTokens;

    /** 道具描述清洗：需要移除的 AI 常见后缀模式 */
    private static final Pattern PROP_SUFFIX_PATTERN = Pattern.compile(
            "[（(]参考[^）)]*[）)]|\\[注[^\\]]*\\]|，仅供参考|。仅供参考$|（[^）]*视觉[^）]*）",
            Pattern.CASE_INSENSITIVE
    );

    @Autowired
    private IAidComicScriptService scriptService;

    @Autowired
    private IAidComicEpisodeService episodeService;

    @Autowired
    private IAidRolePropSceneService rpsService;

    /** 智能体配置 Service：按 agent_code 反查 prompt_content */
    @Autowired
    private IAidAgentService aidAgentService;

    @Autowired
    private IMediaGenerationService mediaGenerationService;

    @Autowired
    private IAidMediaTaskService mediaTaskService;

    @Autowired
    private IExtractBillingService extractBillingService;

    /** AI 模型配置查询：读取 supports_system_prompt 决定系统提示词是否分离成 system role */
    @Autowired
    private IAiModelConfigService aiModelConfigService;

    @Autowired
    private com.aid.model.definition.ModelInvocationResolver modelInvocationResolver;

    /** supports_system_prompt 的本地缓存，避免每次 LLM 调用都聚合查三表 VO */
    private final ConcurrentHashMap<String, CachedFlag> supportsSystemPromptCache = new ConcurrentHashMap<>();
    private final Object successfulReplayCacheLock = new Object();
    private final LinkedHashMap<ReplayCacheKey, CachedReplayResults> successfulReplayCache =
            new LinkedHashMap<>(16, 0.75F, true);
    private long successfulReplayCacheTextChars;

    /** 缓存有效期：5 分钟 */
    private static final long SUPPORTS_SYSTEM_CACHE_TTL_MS = 5 * 60 * 1000L;

    /** 缓存 supports_system_prompt 的简单值对象 */
    private static final class CachedFlag
    {
        final boolean value;
        final long expireAt;

        CachedFlag(boolean value, long expireAt)
        {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
    /**
     * 加载单集剧本内容
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（电影模式传0）
     * @param userId    当前用户ID（防越权读取他人剧本，强制按用户隔离）
     * @return 剧本文本内容，不存在则返回null
     */
    public String loadScriptContent(Long projectId, Long episodeId, Long userId)
    {
        LambdaQueryWrapper<AidComicScript> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicScript::getProjectId, projectId);
        wrapper.eq(AidComicScript::getEpisodeId, episodeId);
        // 按当前用户隔离，防止越权读他人剧本
        wrapper.eq(AidComicScript::getUserId, userId);
        wrapper.eq(AidComicScript::getStatus, SCRIPT_STATUS_ACTIVE);
        wrapper.eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.select(AidComicScript::getOriginalText);
        // 若存在多条 active，始终以最后更新的一条作为当前剧本。
        wrapper.orderByDesc(AidComicScript::getUpdateTime);
        wrapper.orderByDesc(AidComicScript::getId);
        wrapper.last("LIMIT 1");
        AidComicScript script = scriptService.getOne(wrapper, false);
        if (Objects.isNull(script))
        {
            return null;
        }
        return script.getOriginalText();
    }

    /**
     * 加载项目全部剧本内容（按创建时间排序，用于剧集模式的全局分析）
     *
     * @param projectId 项目ID
     * @param userId    当前用户ID（防越权读取他人剧本）
     * @return 全部剧本合并文本，无内容则返回null
     */
    public String loadAllScriptsContent(Long projectId, Long userId)
    {
        LambdaQueryWrapper<AidComicScript> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicScript::getProjectId, projectId);
        // 强制按当前用户隔离
        wrapper.eq(AidComicScript::getUserId, userId);
        wrapper.eq(AidComicScript::getStatus, SCRIPT_STATUS_ACTIVE);
        wrapper.eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL);
        wrapper.select(AidComicScript::getOriginalText);
        wrapper.orderByAsc(AidComicScript::getCreateTime);
        List<AidComicScript> scripts = scriptService.list(wrapper);

        if (CollectionUtil.isEmpty(scripts))
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (AidComicScript script : scripts)
        {
            String text = script.getOriginalText();
            if (StrUtil.isNotBlank(text))
            {
                if (!sb.isEmpty())
                {
                    sb.append("\n\n---\n\n");
                }
                sb.append(text);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 按剧集分组加载剧本（用于剧集模式角色提取，按episodeNo排序后按组数分割）
     *
     * @param projectId  项目ID
     * @param groupSize  每组包含的剧集数（如10集/组）
     * @param userId     当前用户ID（防越权读取他人剧本）
     * @return 分组后的剧本内容列表，每组为一个合并后的文本
     */
    public List<String> loadGroupedScriptsContent(Long projectId, int groupSize, Long userId)
    {
        // 查询所有剧集，按 episodeNo 排序
        LambdaQueryWrapper<AidComicEpisode> episodeWrapper = Wrappers.lambdaQuery();
        episodeWrapper.eq(AidComicEpisode::getProjectId, projectId);
        episodeWrapper.eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL);
        episodeWrapper.orderByAsc(AidComicEpisode::getEpisodeNo);
        List<AidComicEpisode> episodes = episodeService.list(episodeWrapper);

        if (CollectionUtil.isEmpty(episodes))
        {
            return Collections.emptyList();
        }

        // 按组分割
        List<List<AidComicEpisode>> groups = new ArrayList<>();
        for (int i = 0; i < episodes.size(); i += groupSize)
        {
            int end = Math.min(i + groupSize, episodes.size());
            groups.add(episodes.subList(i, end));
        }

        // 加载每组剧集的剧本内容并合并
        List<String> groupedScripts = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++)
        {
            List<AidComicEpisode> group = groups.get(i);
            StringBuilder sb = new StringBuilder();
            for (AidComicEpisode episode : group)
            {
                // 同样带 userId 隔离，防越权读他人剧本
                String text = loadScriptContent(projectId, episode.getId(), userId);
                if (StrUtil.isNotBlank(text))
                {
                    if (!sb.isEmpty())
                    {
                        sb.append("\n\n---\n\n");
                    }
                    sb.append("【第").append(episode.getEpisodeNo()).append("集】\n\n");
                    sb.append(text);
                }
            }
            if (!sb.isEmpty())
            {
                groupedScripts.add(sb.toString());
            }
        }

        return groupedScripts;
    }

    /**
     * 统计剧本字数（不拼接字符串，直接查库累加length，仅供费用预估使用）
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（null表示统计项目下全部剧本）
     * @param userId    当前用户ID（防越权统计他人剧本）
     * @return 剧本总字数
     */
    public int countScriptCharacters(Long projectId, Long episodeId, Long userId)
    {
        LambdaQueryWrapper<AidComicScript> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicScript::getProjectId, projectId);
        if (Objects.nonNull(episodeId))
        {
            wrapper.eq(AidComicScript::getEpisodeId, episodeId);
        }
        // 强制按当前用户隔离
        wrapper.eq(AidComicScript::getUserId, userId);
        wrapper.eq(AidComicScript::getStatus, SCRIPT_STATUS_ACTIVE);
        wrapper.eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL);
        // 只查必要字段，避免加载大文本内容到内存
        wrapper.select(AidComicScript::getId, AidComicScript::getEpisodeId,
                AidComicScript::getOriginalText);
        List<AidComicScript> scripts = scriptService.list(wrapper);

        log.info("字数统计查询: projectId={}, episodeId={}, 查到{}条剧本记录",
                projectId, episodeId, scripts.size());

        int totalChars = 0;
        for (AidComicScript script : scripts)
        {
            String text = script.getOriginalText();
            int len = StrUtil.isNotBlank(text) ? text.length() : 0;
            if (len > 0)
            {
                totalChars += len;
                log.info("字数统计明细: scriptId={}, episodeId={}, 使用字段=originalText, 长度={}",
                        script.getId(), script.getEpisodeId(), len);
            }
            else
            {
                log.warn("字数统计跳过空剧本: scriptId={}, episodeId={}",
                        script.getId(), script.getEpisodeId());
            }
        }
        log.info("字数统计结果: projectId={}, episodeId={}, 总字数={}",
                projectId, episodeId, totalChars);
        return totalChars;
    }

    /**
     * 统计项目剧集数量
     *
     * @param projectId 项目ID
     * @return 剧集数量
     */
    public int countEpisodes(Long projectId)
    {
        LambdaQueryWrapper<AidComicEpisode> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidComicEpisode::getProjectId, projectId);
        wrapper.eq(AidComicEpisode::getDelFlag, DEL_FLAG_NORMAL);
        return (int) episodeService.count(wrapper);
    }

    /**
     * 加载角色生成提示词中的"已有资产库"文本（characters_lib_info），用于避免重复提取与支持角色更新。
     *
     * @param projectId 项目ID
     * @return 已有角色结构化文本（无数据时返回 "无"，可直接替换 {characters_lib_info}）
     */
    public String loadCharactersLibInfo(Long projectId)
    {
        if (Objects.isNull(projectId))
        {
            return "无";
        }
        // 组装角色生成提示词的 characters_lib_info：只查必要字段，introduction 用于模型判断是否同一人
        LambdaQueryWrapper<AidRolePropScene> wrapper = Wrappers.lambdaQuery();
        wrapper.select(AidRolePropScene::getName, AidRolePropScene::getAliasesName, AidRolePropScene::getIntroduction);
        wrapper.eq(AidRolePropScene::getProjectId, projectId);
        wrapper.eq(AidRolePropScene::getAssetType, "character");
        wrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        // 按 id 升序，保证多次提取顺序稳定
        wrapper.orderByAsc(AidRolePropScene::getId);
        List<AidRolePropScene> list = rpsService.list(wrapper);
        if (CollectionUtil.isEmpty(list))
        {
            return "无";
        }
        // 同名去重（忽略大小写），保留首次出现的介绍
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        for (AidRolePropScene asset : list)
        {
            String name = asset.getName();
            if (StrUtil.isBlank(name) || !seen.add(name))
            {
                continue;
            }
            sb.append("- ").append(name);
            // 别名：用 / 分隔，避免与外层 - 行起始符 / 介绍中的逗号混淆
            String aliasesRaw = StrUtil.blankToDefault(asset.getAliasesName(), "");
            if (StrUtil.isNotBlank(aliasesRaw))
            {
                String[] parts = aliasesRaw.split("[,，]");
                List<String> aliasList = new ArrayList<>();
                for (String p : parts)
                {
                    String t = p.trim();
                    if (StrUtil.isNotBlank(t)) { aliasList.add(t); }
                }
                if (!aliasList.isEmpty())
                {
                    sb.append("（别名：").append(String.join("/", aliasList)).append("）");
                }
            }
            String intro = StrUtil.blankToDefault(asset.getIntroduction(), "");
            if (StrUtil.isNotBlank(intro))
            {
                // 介绍单行化：避免破坏 "- 角色：介绍" 的逐行结构，且裁剪过长内容防止 token 浪费
                String oneLine = intro.replaceAll("\\s+", " ").trim();
                if (oneLine.length() > 200) { oneLine = oneLine.substring(0, 200) + "..."; }
                sb.append("：").append(oneLine);
            }
            sb.append("\n");
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "无" : text;
    }

    /**
     * 查询项目是否已有角色资产
     *
     * @param projectId 项目ID
     * @return 角色数量（0表示没有）
     */
    public int countExistingCharacters(Long projectId)
    {
        LambdaQueryWrapper<AidRolePropScene> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidRolePropScene::getProjectId, projectId);
        wrapper.eq(AidRolePropScene::getAssetType, "character");
        wrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        return (int) rpsService.count(wrapper);
    }
    /**
     * 加载已有资产库（用于提取时去重）
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID（null表示加载项目下全部资产）
     * @return 已有资产库快照
     */
    public ExistingAssetLib loadExistingAssets(Long projectId, Long episodeId)
    {
        ExistingAssetLib lib = new ExistingAssetLib();
        LambdaQueryWrapper<AidRolePropScene> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AidRolePropScene::getProjectId, projectId);
        if (Objects.nonNull(episodeId))
        {
            wrapper.eq(AidRolePropScene::getEpisodeId, episodeId);
        }
        wrapper.eq(AidRolePropScene::getDelFlag, DEL_FLAG_NORMAL);
        // 查询字段精简：资产去重只依赖主键、类型、名称和别名。
        wrapper.select(AidRolePropScene::getId,
                AidRolePropScene::getAssetType,
                AidRolePropScene::getName,
                AidRolePropScene::getAliasesName);
        List<AidRolePropScene> existingList = rpsService.list(wrapper);

        for (AidRolePropScene asset : existingList)
        {
            String name = asset.getName();
            if (StrUtil.isBlank(name))
            {
                continue;
            }
            List<String> aliases = parseAliases(name, asset.getAliasesName());
            switch (asset.getAssetType())
            {
                case "character" -> lib.addCharacter(name, aliases);
                // 场景按“地点_时间”权威名称去重，同一地点的不同时段分别建档。
                case "scene" -> lib.addScene(name, asset.getId());
                case "prop" -> lib.addProp(name, aliases);
                default -> { /* 忽略未知类型 */ }
            }
        }
        return lib;
    }

    private record ReplayCacheKey(Long parentTaskId, Long userId, String bizTaskType,
                                  String modelCode, String currentTraceId,
                                  String priorTraceId) { }

    private record CachedReplayResults(Map<String, List<MediaTaskResponse>> resultsByIdentity,
                                       Map<String, List<MediaTaskResponse>> resultsByStableSlot,
                                       long expireAt, long textChars) { }
    /**
     * 构造统一的 LLM options 参数（max_tokens 等），并显式关闭思考模式把预算全留给 JSON 正文。
     */
    private Map<String, Object> buildLlmOptions(boolean structuredOutput)
    {
        return buildLlmOptions(null, null, structuredOutput);
    }

    private Map<String, Object> buildLlmOptions(String callIdentity, Integer requestedOutputTokens,
                                                boolean structuredOutput)
    {
        Map<String, Object> options = new java.util.HashMap<>();
        int outputTokens = resolveOutputTokenCap(callIdentity, requestedOutputTokens);
        if (outputTokens > 0)
        {
            // 下发通用 max_tokens 字段名，异名厂商由各自 ProviderClient 映射
            options.put("max_tokens", outputTokens);
        }
        // 资产提取专用：显式禁用思考模式，把全部输出预算留给 JSON 正文
        options.put(TextReasoningOptionsResolver.ENABLED_KEY, Boolean.FALSE);
        // 只有调用方明确按 JSON 解析结果时才开启；自然语言正文与视觉描述保持关闭。
        options.put(com.aid.media.provider.StructuredOutputSupport.ENABLED_KEY, structuredOutput);
        return options;
    }

    /**
     * 分镜批次已经按最多 6000 字拆分，输出上限按本批正文规模增长，不再让每个批次都占用全局 64K 上限。
     */
    public int resolveStoryboardScriptOutputTokens(int sourceChars, boolean writerOutput)
    {
        long scaled = (long) Math.max(0, sourceChars)
                * (writerOutput ? WRITER_OUTPUT_TOKENS_PER_SOURCE_CHAR : STANDARD_OUTPUT_TOKENS_PER_SOURCE_CHAR);
        int minimum = positiveOrDefault(storyboardScriptMinTokens, 16384);
        int maximum = Math.min(resolveGlobalOutputCap(), positiveOrDefault(storyboardScriptMaxTokens, 49152));
        if (maximum < minimum)
        {
            minimum = maximum;
        }
        return (int) Math.max(minimum, Math.min(scaled, maximum));
    }

    /**
     * 结构化批量任务按输入条目规模增长。短调用保持 8K，较长角色切片或整批提示词
     * 最多增长到 48K，避免统一 8K 截断，也不再为每次调用都冻结全局 64K。
     */
    public int resolveStructuredOutputTokens(int sourceUnits, int tokensPerUnit)
    {
        long scaled = (long) Math.max(0, sourceUnits) * Math.max(1, tokensPerUnit);
        int minimum = Math.min(resolveGlobalOutputCap(), positiveOrDefault(llmStructuredMaxTokens, 8192));
        int maximum = Math.min(resolveGlobalOutputCap(), positiveOrDefault(storyboardScriptMaxTokens, 49152));
        return (int) Math.max(minimum, Math.min(scaled, maximum));
    }

    private int resolveOutputTokenCap(String callIdentity, Integer requestedOutputTokens)
    {
        int globalCap = resolveGlobalOutputCap();
        if (requestedOutputTokens != null && requestedOutputTokens > 0)
        {
            return Math.min(globalCap, requestedOutputTokens);
        }
        if (StrUtil.isBlank(callIdentity))
        {
            return globalCap;
        }
        if (callIdentity.startsWith("stage=script_direct")
                || callIdentity.startsWith("stage=script_scene"))
        {
            return Math.min(globalCap, positiveOrDefault(storyboardScriptMaxTokens, 49152));
        }
        return Math.min(globalCap, positiveOrDefault(llmStructuredMaxTokens, 8192));
    }

    private int resolveGlobalOutputCap()
    {
        int configured = positiveOrDefault(llmMaxTokens, TextOutputLimitResolver.FALLBACK_OUTPUT_TOKENS);
        return Math.min(configured, TextOutputLimitResolver.ABSOLUTE_OUTPUT_TOKENS);
    }

    private int positiveOrDefault(int value, int fallback)
    {
        return value > 0 ? value : fallback;
    }

    /** 为外层自计费调用写入与实际 LLM 请求一致的输出上限。 */
    public void applyLlmBillingLimits(Map<String, Object> billingParams, int expectedCallCount)
    {
        if (billingParams == null)
        {
            return;
        }
        int providerCap = llmMaxTokens > 0
                ? Math.min(llmMaxTokens, TextOutputLimitResolver.ABSOLUTE_OUTPUT_TOKENS)
                : TextOutputLimitResolver.FALLBACK_OUTPUT_TOKENS;
        billingParams.put("providerOutputTokenCap", providerCap);
        billingParams.put("billingOutputTokenCeiling", TextOutputLimitResolver.billingCeiling(providerCap));
        billingParams.put("expectedCallCount", Math.max(1, expectedCallCount));
    }

    /**
     * 调用 LLM 并返回解析后的 JSON。
     *
     * @param systemPrompt 系统提示词
     * @param modelCode    AI模型编码（必填，对应aid_ai_model.model_code）
     * @param bizTaskId    业务任务ID（用于关联到业务任务表）
     * @param userId       用户ID（MQ消费场景传入，用于写入aid_media_task）
     * @return 解析后的 JsonNode
     */
    public JsonNode callLlm(String systemPrompt, String modelCode, Long bizTaskId, Long userId)
    {
        return callLlm(systemPrompt, modelCode, bizTaskId, userId, null);
    }

    /**
     * 调用 LLM 并返回解析后的 JSON（带 aid_media_task.prompt 摘要的版本，避免模板正文重复占用存储）。
     *
     * @param systemPrompt     完整 system 提示词（送给 LLM）
     * @param modelCode        AI模型编码
     * @param bizTaskId        业务任务ID
     * @param userId           用户ID
     * @param taskPromptDigest 任务存档摘要（动态入参原文，最多几 KB）；为 null 时不做摘要替换
     * @return 解析后的 JsonNode
     */
    public JsonNode callLlm(String systemPrompt, String modelCode, Long bizTaskId, Long userId, String taskPromptDigest)
    {
        MediaTextGenerateRequest textRequest = new MediaTextGenerateRequest();
        textRequest.setModelName(modelCode);
        textRequest.setBizTaskId(bizTaskId);
        textRequest.setBizTaskType(BIZ_TASK_TYPE_EXTRACT);
        textRequest.setUserId(userId);
        textRequest.setPreferNonStream(true);
        textRequest.setStream(Boolean.FALSE);
        textRequest.setReasoningEnabled(Boolean.FALSE);
        textRequest.setIncludeReasoning(Boolean.FALSE);
        textRequest.setOptions(buildLlmOptions(true));
        textRequest.setTaskPromptDigest(taskPromptDigest);
        textRequest.setMessages(buildMessages(systemPrompt, null, modelCode));
        applyTextCallBilling(textRequest, bizTaskId, BIZ_TASK_TYPE_EXTRACT, modelCode, null, null);

        MediaTaskResponse taskResponse = mediaGenerationService.generateText(textRequest);
        MediaTaskResponse finalResponse = resolveLlmResponse(taskResponse);

        String textContent = finalResponse.getTextContent();
        if (StrUtil.isBlank(textContent))
        {
            log.error("LLM返回内容为空");
            throw new RuntimeException("AI提取失败");
        }
        return parseAiResponse(textContent);
    }

    /**
     * 调用 LLM 并返回解析后的 JSON（system + user 分离版，规则放 system、原文放 user）。
     *
     * @param systemPrompt     系统提示词（规则部分）
     * @param userContent      用户消息（原文/数据部分）
     * @param modelCode        AI模型编码
     * @param bizTaskId        业务任务ID
     * @param userId           用户ID
     * @param taskPromptDigest 任务存档摘要
     * @return 解析后的 JsonNode
     */
    public JsonNode callLlm(String systemPrompt, String userContent, String modelCode,
                             Long bizTaskId, Long userId, String taskPromptDigest)
    {
        MediaTextGenerateRequest textRequest = new MediaTextGenerateRequest();
        textRequest.setModelName(modelCode);
        textRequest.setBizTaskId(bizTaskId);
        textRequest.setBizTaskType(BIZ_TASK_TYPE_EXTRACT);
        textRequest.setUserId(userId);
        textRequest.setPreferNonStream(true);
        textRequest.setStream(Boolean.FALSE);
        textRequest.setReasoningEnabled(Boolean.FALSE);
        textRequest.setIncludeReasoning(Boolean.FALSE);
        textRequest.setOptions(buildLlmOptions(true));
        textRequest.setTaskPromptDigest(taskPromptDigest);
        textRequest.setMessages(buildMessages(systemPrompt, userContent, modelCode));
        applyTextCallBilling(textRequest, bizTaskId, BIZ_TASK_TYPE_EXTRACT, modelCode, null, null);

        MediaTaskResponse taskResponse = mediaGenerationService.generateText(textRequest);
        MediaTaskResponse finalResponse = resolveLlmResponse(taskResponse);

        String textContent = finalResponse.getTextContent();
        if (StrUtil.isBlank(textContent))
        {
            log.error("LLM返回内容为空");
            throw new RuntimeException("AI提取失败");
        }
        return parseAiResponse(textContent);
    }

    /**
     * 调用 LLM 返回原始文本（不解析JSON，用于视觉描述生成等返回自然语言的场景）。
     *
     * @param systemPrompt 系统提示词
     * @param userContent  用户消息内容
     * @param modelCode    AI模型编码（必填，对应aid_ai_model.model_code）
     * @param bizTaskId    业务任务ID（用于关联到业务任务表）
     * @param userId       用户ID（MQ消费场景传入，用于写入aid_media_task）
     * @return LLM 返回的原始文本
     */
    public String callLlmRaw(String systemPrompt, String userContent, String modelCode, Long bizTaskId, Long userId)
    {
        return callLlmRaw(systemPrompt, userContent, modelCode, bizTaskId, userId, null);
    }

    /**
     * 调用 LLM 返回原始文本（带 aid_media_task.prompt 摘要的版本）。
     */
    public String callLlmRaw(String systemPrompt, String userContent, String modelCode, Long bizTaskId, Long userId, String taskPromptDigest)
    {
        return callLlmRaw(systemPrompt, userContent, modelCode, bizTaskId, userId, taskPromptDigest, BIZ_TASK_TYPE_EXTRACT);
    }

    /**
     * 调用 LLM 返回原始文本（完整版：可自定义 bizTaskType，用于非资产提取场景）。
     *
     * @param bizTaskType 业务任务类型，例如 "extract" / "storyboard_script"
     */
    public String callLlmRaw(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest, String bizTaskType)
    {
        return callLlmRaw(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, null);
    }

    public String callLlmRaw(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity)
    {
        return callLlmRaw(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, StrUtil::isNotBlank);
    }

    public String callLlmRaw(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator)
    {
        return callLlmRaw(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, null);
    }

    public String callLlmRaw(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId)
    {
        return callLlmRaw(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId, null);
    }

    public String callLlmRaw(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId, Integer requestedOutputTokens)
    {
        return callLlmRaw(systemPrompt, userContent, modelCode, bizTaskId, userId, taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId, requestedOutputTokens, null);
    }

    public String callLlmRaw(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId, Integer requestedOutputTokens, String businessFuncCode)
    {
        MediaTaskResponse finalResponse = callLlmRawForResponse(systemPrompt, userContent, modelCode,
                bizTaskId, userId, taskPromptDigest, bizTaskType, callIdentity, replayValidator,
                expectedTraceId, requestedOutputTokens, businessFuncCode);
        return finalResponse == null ? null : finalResponse.getTextContent();
    }

    /** 明确按 JSON 契约交付原始字符串，供调用方执行自身的结构与业务校验。 */
    public String callLlmStructured(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId, Integer requestedOutputTokens, String businessFuncCode)
    {
        MediaTaskResponse finalResponse = callLlmForResponse(systemPrompt, userContent, modelCode,
                bizTaskId, userId, taskPromptDigest, bizTaskType, callIdentity, replayValidator,
                expectedTraceId, requestedOutputTokens, businessFuncCode, true);
        return finalResponse == null ? null : finalResponse.getTextContent();
    }

    /**
     * 调用 LLM 返回最终媒体任务响应（含 textContent 与平台 taskId），供结算按真实 token 回读时使用。
     *
     * @return 终态媒体任务响应（SUCCEEDED）；调用方据 {@link MediaTaskResponse#getTaskId()} 回读真实 token usage
     */
    public MediaTaskResponse callLlmRawForResponse(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest, String bizTaskType)
    {
        return callLlmRawForResponse(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, null);
    }

    public MediaTaskResponse callLlmRawForResponse(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity)
    {
        return callLlmRawForResponse(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, StrUtil::isNotBlank);
    }

    public MediaTaskResponse callLlmRawForResponse(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator)
    {
        return callLlmRawForResponse(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, null);
    }

    public MediaTaskResponse callLlmRawForResponse(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId)
    {
        return callLlmRawForResponse(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId, null);
    }

    public MediaTaskResponse callLlmRawForResponse(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId, Integer requestedOutputTokens)
    {
        return callLlmRawForResponse(systemPrompt, userContent, modelCode, bizTaskId, userId, taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId, requestedOutputTokens, null);
    }

    public MediaTaskResponse callLlmRawForResponse(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId, Integer requestedOutputTokens, String businessFuncCode)
    {
        return callLlmForResponse(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId,
                requestedOutputTokens, businessFuncCode, false);
    }

    /** 明确按 JSON 契约交付，同时保留最终媒体任务信息供真实用量结算。 */
    public MediaTaskResponse callLlmStructuredForResponse(String systemPrompt, String userContent,
                              String modelCode, Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId, Integer requestedOutputTokens, String businessFuncCode)
    {
        return callLlmForResponse(systemPrompt, userContent, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId,
                requestedOutputTokens, businessFuncCode, true);
    }

    private MediaTaskResponse callLlmForResponse(String systemPrompt, String userContent, String modelCode,
                              Long bizTaskId, Long userId, String taskPromptDigest,
                              String bizTaskType, String callIdentity,
                              java.util.function.Predicate<String> replayValidator,
                              String expectedTraceId, Integer requestedOutputTokens,
                              String businessFuncCode, boolean structuredOutput)
    {
        MediaTextGenerateRequest textRequest = new MediaTextGenerateRequest();
        textRequest.setModelName(modelCode);
        textRequest.setBizTaskId(bizTaskId);
        textRequest.setBizTaskType(StrUtil.isNotBlank(bizTaskType) ? bizTaskType : BIZ_TASK_TYPE_EXTRACT);
        textRequest.setUserId(userId);
        textRequest.setPreferNonStream(true);
        textRequest.setStream(Boolean.FALSE);
        textRequest.setReasoningEnabled(Boolean.FALSE);
        textRequest.setIncludeReasoning(Boolean.FALSE);
        textRequest.setOptions(buildLlmOptions(callIdentity, requestedOutputTokens, structuredOutput));
        textRequest.setTaskPromptDigest(taskPromptDigest);
        textRequest.setMessages(buildMessages(systemPrompt, userContent, modelCode));
        applyBusinessModelContext(textRequest, businessFuncCode);
        modelCode = textRequest.getModelName();
        TextCallBillingContext billingContext = applyTextCallBilling(textRequest, bizTaskId,
                textRequest.getBizTaskType(), modelCode, callIdentity, expectedTraceId);

        MediaTaskResponse replay = findSuccessfulLlmResponseForResume(bizTaskId, userId,
                textRequest.getBizTaskType(), modelCode, textRequest.getCallIdentity(), billingContext,
                replayValidator);
        if (Objects.nonNull(replay))
        {
            return replay;
        }

        MediaTaskResponse taskResponse = mediaGenerationService.generateText(textRequest);
        return resolveLlmResponse(taskResponse);
    }
    /**
     * 模板化调用 LLM（system 规则 + 结构化 user 输入），返回解析后的 JSON。
     *
     * @param promptTemplate   智能体提示词模板（含 {xxx} 占位符）
     * @param userInputs       动态入参映射：key=变量名（不含花括号），value=实际值
     * @param modelCode        AI模型编码
     * @param bizTaskId        业务任务ID
     * @param userId           用户ID
     * @param taskPromptDigest 任务存档摘要
     * @return 解析后的 JsonNode
     */
    public JsonNode callLlmWithInputs(String promptTemplate, Map<String, String> userInputs,
                                       String modelCode, Long bizTaskId, Long userId, String taskPromptDigest)
    {
        return callLlmWithInputs(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, null);
    }

    public JsonNode callLlmWithInputs(String promptTemplate, Map<String, String> userInputs,
                                       String modelCode, Long bizTaskId, Long userId,
                                       String taskPromptDigest, String callIdentity)
    {
        return callLlmWithInputs(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, callIdentity, node -> true);
    }

    public JsonNode callLlmWithInputs(String promptTemplate, Map<String, String> userInputs,
                                       String modelCode, Long bizTaskId, Long userId,
                                       String taskPromptDigest, String callIdentity,
                                       java.util.function.Predicate<JsonNode> replayValidator)
    {
        return callLlmWithInputs(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, callIdentity, replayValidator, null);
    }

    public JsonNode callLlmWithInputs(String promptTemplate, Map<String, String> userInputs,
                                       String modelCode, Long bizTaskId, Long userId,
                                       String taskPromptDigest, String callIdentity,
                                       java.util.function.Predicate<JsonNode> replayValidator,
                                       String expectedTraceId)
    {
        return callLlmWithInputs(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, callIdentity, replayValidator, expectedTraceId, null);
    }

    public JsonNode callLlmWithInputs(String promptTemplate, Map<String, String> userInputs,
                                       String modelCode, Long bizTaskId, Long userId,
                                       String taskPromptDigest, String callIdentity,
                                       java.util.function.Predicate<JsonNode> replayValidator,
                                       String expectedTraceId, Integer requestedOutputTokens)
    {
        return callLlmWithInputs(promptTemplate, userInputs, modelCode, bizTaskId, userId, taskPromptDigest, callIdentity, replayValidator, expectedTraceId, requestedOutputTokens, null);
    }

    public JsonNode callLlmWithInputs(String promptTemplate, Map<String, String> userInputs,
                                       String modelCode, Long bizTaskId, Long userId,
                                       String taskPromptDigest, String callIdentity,
                                       java.util.function.Predicate<JsonNode> replayValidator,
                                       String expectedTraceId, Integer requestedOutputTokens, String businessFuncCode)
    {
        String textContent = callLlmRawWithInputsInternal(promptTemplate, userInputs, modelCode,
                bizTaskId, userId, taskPromptDigest, BIZ_TASK_TYPE_EXTRACT, callIdentity,
                raw -> isReplayJsonValid(raw, replayValidator), expectedTraceId,
                requestedOutputTokens, businessFuncCode, true);
        if (StrUtil.isBlank(textContent))
        {
            log.error("LLM返回内容为空");
            throw new RuntimeException("AI提取失败");
        }
        return parseAiResponse(textContent);
    }

    /**
     * 生成不含提示词明文的媒体调用身份：stable slot + 实际 model/messages SHA。
     * 业务完成水位只使用 stable slot；媒体幂等与历史结果精确匹配使用本方法的完整身份。
     */
    public String buildLlmCallIdentity(String baseIdentity, String promptTemplate,
                                       Map<String, String> userInputs, String modelCode)
    {
        String systemPrompt = stripPlaceholders(promptTemplate, userInputs);
        String userContent = buildStructuredUserContent(userInputs);
        return appendInputSha(baseIdentity, buildMessages(systemPrompt, userContent, modelCode), modelCode, null);
    }

    private MediaTaskResponse findSuccessfulLlmResponseForResume(Long bizTaskId, Long userId,
                                                                 String bizTaskType, String modelCode,
                                                                 String callIdentity,
                                                                 TextCallBillingContext billingContext,
                                                                 java.util.function.Predicate<String> validator)
    {
        if (Objects.isNull(billingContext) || !billingContext.mediaTaskBilling()
                || !extractBillingService.hasActiveResumeBilling(bizTaskId,
                        billingContext.billingTraceId())
                || Objects.isNull(bizTaskId) || Objects.isNull(userId)
                || StrUtil.isBlank(bizTaskType) || StrUtil.isBlank(modelCode)
                || StrUtil.isBlank(billingContext.priorBillingTraceId())
                || StrUtil.isBlank(callIdentity))
        {
            return null;
        }
        ReplayCacheKey cacheKey = new ReplayCacheKey(bizTaskId, userId, bizTaskType,
                modelCode, billingContext.billingTraceId(), billingContext.priorBillingTraceId());
        long now = System.currentTimeMillis();
        CachedReplayResults cached = getCachedReplayResults(cacheKey, now);
        if (Objects.isNull(cached) || cached.expireAt() <= now)
        {
            cached = loadSuccessfulReplayResults(cacheKey, now);
            cacheReplayResults(cacheKey, cached, now);
        }
        // 精确 model/messages SHA 优先；动态资产库会随已完成 chunk 扩充，active resume 下再按
        // stable slot 回退到上一周期已付费成功结果。候选仍须通过调用方业务 validator。
        List<MediaTaskResponse> exactCandidates = cached.resultsByIdentity()
                .getOrDefault(callIdentity, List.of());
        // 精确身份存在时只校验其最新候选；只有完全没有精确候选才回退到 stable slot 最新候选。
        // 任一层的最新候选校验失败都直接发起新调用，不穿透到另一层或更老周期。
        List<MediaTaskResponse> replayCandidates = exactCandidates.isEmpty()
                ? cached.resultsByStableSlot().getOrDefault(stableCallSlot(callIdentity), List.of())
                : exactCandidates;
        for (MediaTaskResponse replay : replayCandidates)
        {
            try
            {
                if (validator != null && !validator.test(replay.getTextContent()))
                {
                    log.warn("历史文本结果未通过业务校验: parentTaskId={}, callIdentity={}, mediaTaskId={}",
                            bizTaskId, callIdentity, replay.getTaskId());
                    continue;
                }
                log.info("复用已成功文本调用结果: parentTaskId={}, callIdentity={}, mediaTaskId={}",
                        bizTaskId, callIdentity, replay.getTaskId());
                return replay;
            }
            catch (Exception validationEx)
            {
                log.warn("历史文本结果业务校验异常: parentTaskId={}, callIdentity={}, mediaTaskId={}",
                        bizTaskId, callIdentity, replay.getTaskId());
            }
        }
        return null;
    }

    private CachedReplayResults getCachedReplayResults(ReplayCacheKey cacheKey, long now)
    {
        synchronized (successfulReplayCacheLock)
        {
            removeExpiredReplayEntries(now);
            return successfulReplayCache.get(cacheKey);
        }
    }

    private void cacheReplayResults(ReplayCacheKey cacheKey, CachedReplayResults cached, long now)
    {
        if (cached.textChars() > REPLAY_CACHE_MAX_TEXT_CHARS)
        {
            return;
        }
        synchronized (successfulReplayCacheLock)
        {
            removeExpiredReplayEntries(now);
            CachedReplayResults replaced = successfulReplayCache.put(cacheKey, cached);
            if (Objects.nonNull(replaced))
            {
                successfulReplayCacheTextChars -= replaced.textChars();
            }
            successfulReplayCacheTextChars += cached.textChars();
            while (successfulReplayCache.size() > REPLAY_CACHE_MAX_ENTRIES
                    || successfulReplayCacheTextChars > REPLAY_CACHE_MAX_TEXT_CHARS)
            {
                Map.Entry<ReplayCacheKey, CachedReplayResults> eldest =
                        successfulReplayCache.entrySet().iterator().next();
                successfulReplayCacheTextChars -= eldest.getValue().textChars();
                successfulReplayCache.remove(eldest.getKey());
            }
        }
    }

    private void removeExpiredReplayEntries(long now)
    {
        var iterator = successfulReplayCache.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<ReplayCacheKey, CachedReplayResults> entry = iterator.next();
            boolean inactive = !extractBillingService.hasActiveResumeBilling(
                    entry.getKey().parentTaskId(), entry.getKey().currentTraceId());
            if (entry.getValue().expireAt() <= now || inactive)
            {
                successfulReplayCacheTextChars -= entry.getValue().textChars();
                iterator.remove();
            }
        }
    }

    private CachedReplayResults loadSuccessfulReplayResults(ReplayCacheKey cacheKey, long now)
    {
        List<AidMediaTask> candidates = mediaTaskService.list(
                Wrappers.<AidMediaTask>lambdaQuery()
                        .select(AidMediaTask::getId, AidMediaTask::getModelName,
                                AidMediaTask::getRequestJson, AidMediaTask::getResultText)
                        .eq(AidMediaTask::getBizTaskId, cacheKey.parentTaskId())
                        .eq(AidMediaTask::getBizTaskType, cacheKey.bizTaskType())
                        .eq(AidMediaTask::getMediaType, "TEXT")
                        .eq(AidMediaTask::getStatus, LLM_STATUS_SUCCEEDED)
                        .eq(AidMediaTask::getUserId, cacheKey.userId())
                        .eq(AidMediaTask::getModelName, cacheKey.modelCode())
                        .isNotNull(AidMediaTask::getResultText)
                        .orderByDesc(AidMediaTask::getId));
        Map<String, List<MediaTaskResponse>> results = new LinkedHashMap<>();
        Map<String, List<MediaTaskResponse>> slotResults = new LinkedHashMap<>();
        long textChars = 0L;
        for (AidMediaTask candidate : candidates)
        {
            try
            {
                JsonNode request = OBJECT_MAPPER.readTree(candidate.getRequestJson());
                String identity = request.path("callIdentity").asText();
                String attemptId = request.path("billingAttemptId").asText();
                if (StrUtil.isBlank(identity) || StrUtil.isBlank(candidate.getResultText())
                        || request.path("billingExempt").asBoolean(false)
                        || !Objects.equals(cacheKey.priorTraceId(), attemptId))
                {
                    continue;
                }
                MediaTaskResponse response = MediaTaskResponse.builder()
                        .taskId(candidate.getId())
                        .mediaType("TEXT")
                        .modelName(candidate.getModelName())
                        .status(LLM_STATUS_SUCCEEDED)
                        .textContent(candidate.getResultText())
                        .build();
                // 查询已按 id 倒序；每个 identity/slot 只保留最近一次已付费成功结果。
                // 若最近结果未通过当前业务 validator，必须发起新调用，不能继续回退到更老周期，
                // 否则可能在业务输入曾变更时复用古老但结构合法的输出。
                if (!results.containsKey(identity))
                {
                    results.put(identity, List.of(response));
                    // 容量只统计真正保留的“每个精确身份最新一条”。同身份更老的重复行不会进入缓存，
                    // 也不能把它们计入权重，否则会误判超限并导致续生每个调用都重新全量扫描。
                    textChars += candidate.getResultText().length();
                }
                slotResults.putIfAbsent(stableCallSlot(identity), List.of(response));
            }
            catch (Exception replayEx)
            {
                log.warn("历史文本结果索引跳过: parentTaskId={}, mediaTaskId={}, err={}",
                        cacheKey.parentTaskId(), candidate.getId(), replayEx.getMessage());
            }
        }
        Map<String, List<MediaTaskResponse>> immutableResults = new LinkedHashMap<>();
        results.forEach((identity, responses) -> immutableResults.put(identity, List.copyOf(responses)));
        Map<String, List<MediaTaskResponse>> immutableSlotResults = new LinkedHashMap<>();
        slotResults.forEach((slot, responses) -> immutableSlotResults.put(slot, List.copyOf(responses)));
        return new CachedReplayResults(Map.copyOf(immutableResults), Map.copyOf(immutableSlotResults),
                now + REPLAY_CACHE_TTL_MILLIS, textChars);
    }

    private String stableCallSlot(String callIdentity)
    {
        if (StrUtil.isBlank(callIdentity))
        {
            return callIdentity;
        }
        int marker = callIdentity.lastIndexOf(CALL_INPUT_SHA_MARKER);
        return marker >= 0 ? callIdentity.substring(0, marker) : callIdentity;
    }

    private boolean isReplayJsonValid(String replayText,
                                      java.util.function.Predicate<JsonNode> replayValidator)
    {
        try
        {
            JsonNode parsed = parseAiResponse(replayText);
            return replayValidator == null || replayValidator.test(parsed);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * 按最终下发的 messages 口径估算输入字符数。
     * 支持 system role 时累计 system + user；不支持时计入合并消息新增的标题与换行。
     */
    public int estimateLlmInputChars(String systemPrompt, String userContent, String modelCode)
    {
        long totalChars;
        if (resolveSupportsSystemPrompt(modelCode))
        {
            totalChars = (long) StrUtil.length(systemPrompt) + StrUtil.length(userContent);
        }
        else
        {
            totalChars = StrUtil.length("【系统指令】\n") + (long) StrUtil.length(systemPrompt);
            if (StrUtil.isNotBlank(userContent))
            {
                totalChars += StrUtil.length("\n\n【输入数据】\n") + (long) StrUtil.length(userContent);
            }
        }
        return (int) Math.min(totalChars, Integer.MAX_VALUE);
    }

    /**
     * 按模板化调用的真实拆分规则估算输入字符数：先清理 system 占位符，再组装结构化 user message。
     */
    public int estimateLlmInputCharsWithInputs(String promptTemplate, Map<String, String> userInputs,
                                                String modelCode)
    {
        String systemPrompt = stripPlaceholders(promptTemplate, userInputs);
        String userContent = buildStructuredUserContent(userInputs);
        return estimateLlmInputChars(systemPrompt, userContent, modelCode);
    }

    /**
     * 模板化调用 LLM 返回原始文本（system + 结构化 user 输入）。
     *
     * @param promptTemplate   智能体提示词模板（含 {xxx} 占位符）
     * @param userInputs       动态入参映射
     * @param modelCode        AI模型编码
     * @param bizTaskId        业务任务ID
     * @param userId           用户ID
     * @param taskPromptDigest 任务存档摘要
     * @param bizTaskType      业务任务类型
     * @return LLM 返回的原始文本
     */
    public String callLlmRawWithInputs(String promptTemplate, Map<String, String> userInputs,
                                        String modelCode, Long bizTaskId, Long userId,
                                        String taskPromptDigest, String bizTaskType)
    {
        return callLlmRawWithInputs(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, null);
    }

    public String callLlmRawWithInputs(String promptTemplate, Map<String, String> userInputs,
                                        String modelCode, Long bizTaskId, Long userId,
                                        String taskPromptDigest, String bizTaskType,
                                        String callIdentity)
    {
        return callLlmRawWithInputsInternal(promptTemplate, userInputs, modelCode, bizTaskId,
                userId, taskPromptDigest, bizTaskType, callIdentity, StrUtil::isNotBlank, null, null);
    }

    /**
     * 模板化原始文本调用的续生回放版本。validator 只校验历史成功 child 的输出是否仍可被
     * 当前业务消费；校验不通过时跳过该历史候选，并创建本周期的新 child 调用。
     */
    public String callLlmRawWithInputs(String promptTemplate, Map<String, String> userInputs,
                                        String modelCode, Long bizTaskId, Long userId,
                                        String taskPromptDigest, String bizTaskType,
                                        String callIdentity,
                                        java.util.function.Predicate<String> replayValidator)
    {
        return callLlmRawWithInputs(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, null);
    }

    public String callLlmRawWithInputs(String promptTemplate, Map<String, String> userInputs,
                                        String modelCode, Long bizTaskId, Long userId,
                                        String taskPromptDigest, String bizTaskType,
                                        String callIdentity,
                                        java.util.function.Predicate<String> replayValidator,
                                        String expectedTraceId)
    {
        return callLlmRawWithInputsInternal(promptTemplate, userInputs, modelCode, bizTaskId,
                userId, taskPromptDigest, bizTaskType, callIdentity, replayValidator,
                expectedTraceId, null);
    }

    public String callLlmRawWithInputs(String promptTemplate, Map<String, String> userInputs,
            String modelCode, Long bizTaskId, Long userId, String taskPromptDigest, String bizTaskType,
            String callIdentity, java.util.function.Predicate<String> replayValidator,
            String expectedTraceId, String businessFuncCode)
    {
        return callLlmRawWithInputsInternal(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId,
                null, businessFuncCode, true);
    }

    private String callLlmRawWithInputsInternal(String promptTemplate, Map<String, String> userInputs,
                                                 String modelCode, Long bizTaskId, Long userId,
                                                 String taskPromptDigest, String bizTaskType,
                                                 String callIdentity,
                                                 java.util.function.Predicate<String> replayValidator,
                                                 String expectedTraceId,
                                                 Integer requestedOutputTokens)
    {
        return callLlmRawWithInputsInternal(promptTemplate, userInputs, modelCode, bizTaskId, userId,
                taskPromptDigest, bizTaskType, callIdentity, replayValidator, expectedTraceId,
                requestedOutputTokens, null, true);
    }

    private String callLlmRawWithInputsInternal(String promptTemplate, Map<String, String> userInputs,
                                                 String modelCode, Long bizTaskId, Long userId,
                                                 String taskPromptDigest, String bizTaskType,
                                                 String callIdentity,
                                                 java.util.function.Predicate<String> replayValidator,
                                                 String expectedTraceId,
                                                 Integer requestedOutputTokens, String businessFuncCode,
                                                 boolean structuredOutput)
    {
        String systemPrompt = stripPlaceholders(promptTemplate, userInputs);
        String userContent = buildStructuredUserContent(userInputs);

        MediaTextGenerateRequest textRequest = new MediaTextGenerateRequest();
        textRequest.setModelName(modelCode);
        textRequest.setBizTaskId(bizTaskId);
        textRequest.setBizTaskType(StrUtil.isNotBlank(bizTaskType) ? bizTaskType : BIZ_TASK_TYPE_EXTRACT);
        textRequest.setUserId(userId);
        textRequest.setPreferNonStream(true);
        textRequest.setStream(Boolean.FALSE);
        textRequest.setReasoningEnabled(Boolean.FALSE);
        textRequest.setIncludeReasoning(Boolean.FALSE);
        textRequest.setOptions(buildLlmOptions(callIdentity, requestedOutputTokens, structuredOutput));
        textRequest.setTaskPromptDigest(taskPromptDigest);
        textRequest.setMessages(buildMessages(systemPrompt, userContent, modelCode));
        applyBusinessModelContext(textRequest, businessFuncCode);
        modelCode = textRequest.getModelName();
        TextCallBillingContext billingContext = applyTextCallBilling(textRequest, bizTaskId,
                textRequest.getBizTaskType(), modelCode, callIdentity, expectedTraceId);

        MediaTaskResponse replay = findSuccessfulLlmResponseForResume(bizTaskId, userId,
                textRequest.getBizTaskType(), modelCode, textRequest.getCallIdentity(), billingContext,
                replayValidator == null ? StrUtil::isNotBlank : replayValidator);
        if (Objects.nonNull(replay))
        {
            return replay.getTextContent();
        }

        MediaTaskResponse taskResponse = mediaGenerationService.generateText(textRequest);
        MediaTaskResponse finalResponse = resolveLlmResponse(taskResponse);
        return finalResponse.getTextContent();
    }

    public String businessFunctionForAgent(String agentCode) {
        AidAgent agent = aidAgentService.getByAgentCode(agentCode);
        if (agent == null || StrUtil.isBlank(agent.getBizCategoryCode())) {
            log.info("智能体未配置业务分类: agentCode={}", agentCode);
            throw new com.aid.common.exception.ServiceException("业务绑定缺失");
        }
        return agent.getBizCategoryCode();
    }

    private void applyBusinessModelContext(MediaTextGenerateRequest request, String functionCode) {
        if (StrUtil.isBlank(functionCode)) return;
        AiModelConfigVo config = aiModelConfigService.selectForBusiness(request.getModelName(), functionCode, null);
        if (config == null) throw new com.aid.common.exception.ServiceException("模型不可用");
        request.setBusinessFuncCode(functionCode);
        request.setCapabilityCode(config.getCapabilityCode());
        modelInvocationResolver.normalize(config, request);
    }

    private TextCallBillingContext applyTextCallBilling(MediaTextGenerateRequest request, Long parentTaskId,
                                                        String bizTaskType, String modelCode,
                                                        String callIdentity, String expectedTraceId)
    {
        TextCallBillingContext context = extractBillingService.resolveTextCallBillingContext(parentTaskId);
        if (request.getProjectId() == null)
        {
            request.setProjectId(context.projectId());
        }
        if (request.getEpisodeId() == null)
        {
            request.setEpisodeId(context.episodeId());
        }
        if (StrUtil.isBlank(expectedTraceId))
        {
            if (context.mediaTaskBilling())
            {
                throw new TextTaskExecutionRejectedException();
            }
        }
        else if (!Objects.equals(expectedTraceId, context.billingTraceId()))
        {
            throw new TextTaskExecutionRejectedException();
        }
        request.setBillingExempt(!context.mediaTaskBilling());
        if (StrUtil.isNotBlank(callIdentity))
        {
            // PARENT_TASK 也持久化稳定槽位和输入摘要，父任务才能把并行子调用精确映射到逐次估算。
            request.setCallIdentity(appendInputSha(callIdentity, request.getMessages(), modelCode, request.getInvocationIdentity()));
        }
        if (!context.mediaTaskBilling())
        {
            // 旧 FROZEN 周期及安全回退周期继续由父任务结算，严禁子任务重复扣费。
            return context;
        }
        if (StrUtil.isBlank(callIdentity))
        {
            throw new com.aid.common.exception.ServiceException("调用身份缺失");
        }
        String normalizedIdentity = request.getCallIdentity();
        String identityMaterial = String.join("|",
                StrUtil.blankToDefault(context.parentTaskType(), "unknown"),
                String.valueOf(parentTaskId),
                context.billingTraceId(),
                StrUtil.blankToDefault(bizTaskType, BIZ_TASK_TYPE_EXTRACT),
                normalizedIdentity,
                StrUtil.blankToDefault(modelCode, "unknown"));
        request.setBillingAttemptId(context.billingTraceId());
        request.setCallIdentity(normalizedIdentity);
        request.setCallId(SecureUtil.sha256(identityMaterial));
        return context;
    }

    private String appendInputSha(String callIdentity,
                                  List<MediaTextGenerateRequest.TextMessageItem> messages,
                                  String modelCode, String invocationIdentity)
    {
        if (StrUtil.isBlank(callIdentity))
        {
            return callIdentity;
        }
        String baseIdentity = callIdentity.trim();
        int existingMarker = baseIdentity.lastIndexOf(CALL_INPUT_SHA_MARKER);
        if (existingMarker >= 0)
        {
            baseIdentity = baseIdentity.substring(0, existingMarker);
        }
        try
        {
            String inputMaterial = StrUtil.blankToDefault(modelCode, "unknown") + "|"
                    + OBJECT_MAPPER.writeValueAsString(messages == null ? List.of() : messages)
                    + (invocationIdentity == null ? "" : "|" + invocationIdentity);
            return baseIdentity + CALL_INPUT_SHA_MARKER + SecureUtil.sha256(inputMaterial);
        }
        catch (Exception e)
        {
            log.error("文本调用身份摘要失败: callIdentity={}", baseIdentity, e);
            throw new com.aid.common.exception.ServiceException("调用身份异常");
        }
    }

    /**
     * 根据模型是否支持 system role 构建消息列表（支持则 system/user 分离，否则合并为单条 user）。
     */
    private List<MediaTextGenerateRequest.TextMessageItem> buildMessages(String systemPrompt, String userContent, String modelCode)
    {
        List<MediaTextGenerateRequest.TextMessageItem> messages = new ArrayList<>();
        boolean supportsSystem = resolveSupportsSystemPrompt(modelCode);

        if (supportsSystem)
        {
            MediaTextGenerateRequest.TextMessageItem systemMsg = new MediaTextGenerateRequest.TextMessageItem();
            systemMsg.setRole("system");
            systemMsg.setContent(systemPrompt);
            messages.add(systemMsg);

            if (StrUtil.isNotBlank(userContent))
            {
                MediaTextGenerateRequest.TextMessageItem userMsg = new MediaTextGenerateRequest.TextMessageItem();
                userMsg.setRole("user");
                userMsg.setContent(userContent);
                messages.add(userMsg);
            }
        }
        else
        {
            StringBuilder merged = new StringBuilder();
            merged.append("【系统指令】\n");
            merged.append(systemPrompt);
            if (StrUtil.isNotBlank(userContent))
            {
                merged.append("\n\n【输入数据】\n");
                merged.append(userContent);
            }
            MediaTextGenerateRequest.TextMessageItem userMsg = new MediaTextGenerateRequest.TextMessageItem();
            userMsg.setRole("user");
            userMsg.setContent(merged.toString());
            messages.add(userMsg);

            // 模型不支持 system role 时，额外打印合并后的最终消息
            log.info("\n"
                    + "╔══════════════════════════════════════════════════════════════════╗\n"
                    + "║   ⚙️  模型不支持 system role，已合并为单条 user 消息\n"
                    + "║   合并后 length={}\n"
                    + "╠══════════════════════════════════════════════════════════════════╣\n"
                    + "{}\n"
                    + "╚══════════════════════════════════════════════════════════════════╝",
                    merged.length(), merged);
        }
        return messages;
    }

    /**
     * 查询模型是否支持 system role 系统提示词分离（失败/为 null 默认 true，内置 5 分钟缓存）。
     */
    private boolean resolveSupportsSystemPrompt(String modelCode)
    {
        if (StrUtil.isBlank(modelCode))
        {
            return true;
        }
        long now = System.currentTimeMillis();
        CachedFlag cached = supportsSystemPromptCache.get(modelCode);
        if (cached != null && cached.expireAt > now)
        {
            return cached.value;
        }
        boolean supports = true;
        try
        {
            AiModelConfigVo config = aiModelConfigService.selectByModelCode(modelCode);
            if (config != null && config.getSupportsSystemPrompt() != null)
            {
                supports = config.getSupportsSystemPrompt();
            }
        }
        catch (Exception e)
        {
            log.warn("查询模型 supports_system_prompt 失败，默认按支持处理: modelCode={}, err={}",
                    modelCode, e.getMessage());
        }
        supportsSystemPromptCache.put(modelCode, new CachedFlag(supports, now + SUPPORTS_SYSTEM_CACHE_TTL_MS));
        return supports;
    }

    /**
     * 清理模板中的占位符：独占一行整行移除，嵌在文本中间替换为 {@code [见用户消息: xxx]} 保留语义。
     */
    private String stripPlaceholders(String template, Map<String, String> inputs)
    {
        if (StrUtil.isBlank(template) || inputs == null || inputs.isEmpty())
        {
            return template;
        }
        String result = template;
        for (String key : inputs.keySet())
        {
            if (StrUtil.isBlank(key))
            {
                continue;
            }
            String quotedKey = Pattern.quote(key);
            result = result.replaceAll("(?m)^\\s*\\{" + quotedKey + "\\}\\s*$\\r?\\n?", "");
            result = result.replace("{" + key + "}", "[见用户消息: " + key + "]");
        }
        return result;
    }

    /**
     * 将动态入参组装为结构化 user message 内容（每个变量以 [变量名] 标题行 + 值，变量间空行分隔）。
     */
    private String buildStructuredUserContent(Map<String, String> inputs)
    {
        if (inputs == null || inputs.isEmpty())
        {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : inputs.entrySet())
        {
            String value = StrUtil.blankToDefault(entry.getValue(), "无");
            if (sb.length() > 0)
            {
                sb.append("\n\n");
            }
            sb.append("[").append(entry.getKey()).append("]\n");
            sb.append(value);
        }
        return sb.toString();
    }
    /**
     * 处理 generateText() 返回的响应：SUCCEEDED 直接返回，非终态轮询到终态，FAILED/超时抛异常。
     */
    private MediaTaskResponse resolveLlmResponse(MediaTaskResponse taskResponse)
    {
        if (Objects.isNull(taskResponse))
        {
            log.error("LLM调用返回空响应");
            throw new RuntimeException("AI生成失败: 上游响应为空");
        }
        // 同步成功
        if (Objects.equals(LLM_STATUS_SUCCEEDED, taskResponse.getStatus()))
        {
            return taskResponse;
        }
        // 终态失败：保留真实错误信息，不再用笼统文案覆盖
        if ("FAILED".equals(taskResponse.getStatus()))
        {
            String errorMsg = taskResponse.getErrorMessage();
            log.error("LLM调用失败: error={}", errorMsg);
            throw new RuntimeException(StrUtil.blankToDefault(errorMsg, "AI生成失败"));
        }
        // QUEUED/PROCESSING：轮询远端直到终态
        Long mediaTaskId = taskResponse.getTaskId();
        if (mediaTaskId == null)
        {
            log.error("LLM非终态但无taskId, status={}", taskResponse.getStatus());
            throw new RuntimeException("AI生成失败: 任务ID缺失");
        }

        log.info("LLM任务异步中, 开始轮询: mediaTaskId={}, status={}, timeoutSeconds={}",
                mediaTaskId, taskResponse.getStatus(), llmPollTimeoutSeconds);
        long deadline = System.currentTimeMillis() + llmPollTimeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                Thread.sleep(llmPollIntervalSeconds * 1000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new RuntimeException("AI生成被中断");
            }

            // 远端刷新但不累加 retry_count，不会打满补偿上限
            MediaTaskResponse polled = mediaGenerationService.queryTaskRefresh(mediaTaskId);

            if (Objects.isNull(polled))
            {
                log.error("LLM轮询返回空: mediaTaskId={}", mediaTaskId);
                throw new RuntimeException("AI生成失败: 轮询返回空");
            }
            if (Objects.equals(LLM_STATUS_SUCCEEDED, polled.getStatus()))
            {
                log.info("LLM异步任务成功: mediaTaskId={}", mediaTaskId);
                return polled;
            }
            if ("FAILED".equals(polled.getStatus()))
            {
                String errorMsg = polled.getErrorMessage();
                log.error("LLM异步任务失败: mediaTaskId={}, error={}", mediaTaskId, errorMsg);
                throw new RuntimeException(StrUtil.blankToDefault(errorMsg, "AI生成失败"));
            }
            // 仍在 PROCESSING/QUEUED/PENDING，继续轮询
        }

        log.error("LLM异步任务超时: mediaTaskId={}, timeout={}s", mediaTaskId, llmPollTimeoutSeconds);
        throw new RuntimeException("AI生成超时");
    }
    /**
     * 清理 markdown 围栏并解析 AI 返回的 JSON
     *
     * @param textContent AI 返回的原始文本
     * @return 解析后的 JsonNode
     */
    public JsonNode parseAiResponse(String textContent)
    {
        String json = textContent.trim();
        if (json.startsWith("```json"))
        {
            json = json.substring(7);
        }
        else if (json.startsWith("```"))
        {
            json = json.substring(3);
        }
        if (json.endsWith("```"))
        {
            json = json.substring(0, json.length() - 3);
        }
        json = json.trim();
        try
        {
            return OBJECT_MAPPER.readTree(json);
        }
        catch (Exception e)
        {
            // 截取前100字符作为错误提示，避免超长内容写入日志和数据库
            String preview = json.length() > 100 ? json.substring(0, 100) + "..." : json;
            String errorMsg = "AI返回内容JSON解析失败: " + preview;
            log.error("JSON解析异常: content前100字符={}, 原因={}", preview, e.getMessage());
            throw new RuntimeException(errorMsg);
        }
    }

    /**
     * 从 JsonNode 获取文本字段
     */
    public String getJsonText(JsonNode node, String field)
    {
        JsonNode fieldNode = node.get(field);
        if (Objects.isNull(fieldNode) || fieldNode.isNull())
        {
            return "";
        }
        return fieldNode.asText("");
    }

    /**
     * 从 JsonNode 获取文本数组字段
     */
    public List<String> getJsonTextArray(JsonNode node, String field)
    {
        JsonNode arr = node.get(field);
        if (Objects.isNull(arr) || !arr.isArray())
        {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : arr)
        {
            String text = item.asText("").trim();
            if (StrUtil.isNotBlank(text))
            {
                result.add(text);
            }
        }
        return result;
    }

    /**
     * 加载提示词模板内容：本地缓存文件与 aid_agent 版本一致才命中，否则回源并回写缓存。
     *
     * @param promptName agent_code（迁移后与旧 remark 一致，如 aid_scene_extractor）
     * @return 中文提示词模板内容
     */
    public String loadPromptByName(String promptName)
    {
        return loadPromptByName(promptName, true);
    }

    /** 只读加载提示词；报价/计划阶段禁止创建或刷新本地缓存文件。 */
    public String loadPromptByNameReadOnly(String promptName)
    {
        return loadPromptByName(promptName, false);
    }

    private String loadPromptByName(String promptName, boolean writeCache)
    {
        com.aid.diagnostics.DiagnosticCapture.promptSource(promptName);
        if (StrUtil.isBlank(promptName))
        {
            log.error("提示词加载失败：agentCode 为空");
            throw new RuntimeException("系统配置异常");
        }

        // 存在性与启用状态一律以库为准：缓存文件不得让已删除 / 已停用的智能体继续生效
        AidAgent meta = aidAgentService.getPromptMetaByAgentCode(promptName);
        if (Objects.isNull(meta))
        {
            log.error("提示词加载失败：智能体不存在, agentCode={}", promptName);
            throw new RuntimeException("系统配置异常");
        }
        if (Objects.nonNull(meta.getStatus()) && !Objects.equals(AGENT_STATUS_ENABLED, meta.getStatus()))
        {
            log.error("提示词加载失败：智能体已停用, agentCode={}, status={}", promptName, meta.getStatus());
            throw new RuntimeException("智能体停用");
        }

        String basePath = AidAppConfig.getProfile() + "/" + PROMPT_LIB_DIR;
        File zhFile = new File(basePath + "/" + promptName + "_zh.txt");
        // 缓存文件的修改时间被显式盖成智能体版本时间，落后于库内版本即视为过期。
        // 缓存是节点本地文件，写入方无法跨节点清理，故用版本比对而非写时失效。
        long agentVersion = promptVersionMillis(meta);
        if (zhFile.exists() && zhFile.lastModified() >= agentVersion)
        {
            String cached = FileUtil.readString(zhFile, StandardCharsets.UTF_8);
            if (StrUtil.isNotBlank(cached))
            {
                return cached;
            }
            // 文件存在但内容为空：视为异常缓存，继续走数据库回源覆盖
            log.info("提示词缓存文件为空，回源 aid_agent: agentCode={}, path={}", promptName, zhFile.getAbsolutePath());
        }

        AidAgent agent = aidAgentService.getByAgentCode(promptName);
        if (Objects.isNull(agent) || StrUtil.isBlank(agent.getPromptContent()))
        {
            log.error("提示词加载失败: agentCode={}", promptName);
            throw new RuntimeException("系统配置异常");
        }

        if (!writeCache)
        {
            return agent.getPromptContent();
        }
        try
        {
            FileUtil.mkdir(basePath);
            writePromptCache(zhFile, agent.getPromptContent(), agentVersion, promptName);
            log.info("提示词缓存回写成功: agentCode={}, path={}", promptName, zhFile.getAbsolutePath());
        }
        catch (Exception e)
        {
            // 写文件失败不影响业务，只记录日志
            log.error("提示词缓存回写失败: agentCode={}, error={}", promptName, e.getMessage(), e);
        }
        return agent.getPromptContent();
    }

    /**
     * 原子回写提示词缓存：先写同目录临时文件并盖上版本时间戳，再整体替换目标文件，
     * 避免并发回源时读到只写了一半的提示词。
     *
     * @param target        缓存文件
     * @param content       提示词正文
     * @param versionMillis 智能体版本时间（毫秒），0 表示无版本约束、不盖戳
     * @param promptName    agent_code，仅用于日志定位
     */
    private void writePromptCache(File target, String content, long versionMillis, String promptName)
            throws IOException
    {
        File temp = File.createTempFile(target.getName(), PROMPT_CACHE_TEMP_SUFFIX, target.getParentFile());
        try
        {
            FileUtil.writeString(content, temp, StandardCharsets.UTF_8);
            // 版本戳同源于库时间，比对不受应用与数据库时钟差影响
            if (versionMillis > 0 && !temp.setLastModified(versionMillis))
            {
                log.warn("提示词缓存版本戳写入失败，后续每次都会回源: agentCode={}", promptName);
            }
            try
            {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                // 文件系统不支持原子移动时退回普通替换
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            // 移动成功后临时文件已不存在，删除仅用于清理失败残留
            FileUtil.del(temp);
        }
    }

    /** 智能体提示词版本时间：优先 update_time，从未更新过取 create_time，两者皆空返回 0（视为无版本约束）。 */
    private long promptVersionMillis(AidAgent agent)
    {
        Date version = Objects.nonNull(agent.getUpdateTime()) ? agent.getUpdateTime() : agent.getCreateTime();
        return Objects.isNull(version) ? 0L : version.getTime();
    }

    /**
     * 通用模板变量替换
     *
     * @param template  模板字符串
     * @param variables 变量映射 (key → value)
     * @return 替换后的字符串
     */
    public String substituteVariables(String template, Map<String, String> variables)
    {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet())
        {
            result = result.replace("{" + entry.getKey() + "}",
                    StrUtil.blankToDefault(entry.getValue(), ""));
        }
        return result;
    }
    /**
     * 将长文本按段落边界切片（优先 \n\n，其次 \n，再次句末标点，无边界则硬切）
     *
     * @param content   原始文本
     * @param chunkSize 切片大小（字符数）
     * @return 切片列表
     */
    public List<String> chunkContent(String content, int chunkSize)
    {
        if (StrUtil.isBlank(content))
        {
            return Collections.emptyList();
        }
        if (content.length() <= chunkSize)
        {
            return Collections.singletonList(content);
        }

        List<String> chunks = new ArrayList<>();
        int offset = 0;
        int length = content.length();

        while (offset < length)
        {
            if (offset + chunkSize >= length)
            {
                chunks.add(content.substring(offset).trim());
                break;
            }

            int end = offset + chunkSize;

            // 优先在 \n\n 处切割
            int boundary = findLastIndexOf(content, offset, end, "\n\n");
            if (boundary <= offset)
            {
                // 其次在 \n 处切割
                boundary = findLastIndexOf(content, offset, end, "\n");
            }
            if (boundary <= offset)
            {
                // 再次在句末标点处切割
                boundary = findLastSentenceEnd(content, offset, end);
            }
            if (boundary <= offset)
            {
                // 硬切
                boundary = end;
            }

            String chunk = content.substring(offset, boundary).trim();
            if (!chunk.isEmpty())
            {
                chunks.add(chunk);
            }
            offset = boundary;
            // 跳过连续空白
            while (offset < length && Character.isWhitespace(content.charAt(offset)))
            {
                offset++;
            }
        }

        return chunks;
    }

    private int findLastIndexOf(String content, int start, int end, String delimiter)
    {
        int idx = content.lastIndexOf(delimiter, end);
        if (idx > start)
        {
            return idx + delimiter.length();
        }
        return -1;
    }

    private int findLastSentenceEnd(String content, int start, int end)
    {
        for (int i = Math.min(end, content.length()) - 1; i > start; i--)
        {
            char c = content.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?')
            {
                return i + 1;
            }
        }
        return -1;
    }
    /**
     * 清洗并生成道具视觉描述（description → summary → name 依次回退，并清除 AI 后缀污染）
     *
     * @param name        道具名称
     * @param summary     道具摘要
     * @param description 道具描述
     * @return 清洗后的视觉描述
     */
    public String resolvePropVisualDescription(String name, String summary, String description)
    {
        String result = cleanPropText(description);
        if (StrUtil.isNotBlank(result))
        {
            return result;
        }

        result = cleanPropText(summary);
        if (StrUtil.isNotBlank(result))
        {
            return result;
        }

        return StrUtil.blankToDefault(name, "").trim();
    }

    private String cleanPropText(String text)
    {
        if (StrUtil.isBlank(text))
        {
            return "";
        }
        String cleaned = text.trim();
        // 移除 AI 常见后缀污染
        cleaned = PROP_SUFFIX_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }
    /**
     * 解析名称+别名字符串为别名列表
     *
     * @param name        主名称
     * @param aliasesName 别名字符串（逗号/斜杠分隔）
     * @return 名称+别名列表
     */
    public List<String> parseAliases(String name, String aliasesName)
    {
        List<String> all = new ArrayList<>();
        all.add(name);
        if (StrUtil.isNotBlank(aliasesName))
        {
            for (String alias : aliasesName.split("[,，/]"))
            {
                String trimmed = alias.trim();
                if (StrUtil.isNotBlank(trimmed))
                {
                    all.add(trimmed);
                }
            }
        }
        return all;
    }

    /**
     * 别名交叉匹配：检查 newName 或其 aliases 是否与已有资产名或别名重叠。
     *
     * @param newName          新名称
     * @param newAliases       新别名列表
     * @param existingNames    已有名称集合
     * @param existingAliasMap 已有别名映射
     * @return true 表示匹配到已有资产
     */
    public boolean matchesAnyAlias(String newName, List<String> newAliases,
                                   Set<String> existingNames, Map<String, List<String>> existingAliasMap)
    {
        // 新名称精确匹配已有名称
        if (existingNames.stream().anyMatch(n -> n.equalsIgnoreCase(newName)))
        {
            return true;
        }
        // 新别名匹配已有名称
        if (CollectionUtil.isNotEmpty(newAliases))
        {
            for (String alias : newAliases)
            {
                if (existingNames.stream().anyMatch(n -> n.equalsIgnoreCase(alias)))
                {
                    return true;
                }
            }
        }
        // 新名称匹配已有别名
        for (List<String> aliases : existingAliasMap.values())
        {
            for (String alias : aliases)
            {
                if (alias.equalsIgnoreCase(newName))
                {
                    return true;
                }
            }
        }
        return false;
    }
    /**
     * 标记剧本已提取（防止重复提取）
     *
     * @param projectId 项目ID
     * @param episodeId 剧集ID
     * @param userId    当前用户ID（防越权写他人剧本）
     */
    public void markScriptExtracted(Long projectId, Long episodeId, Long userId)
    {
        LambdaUpdateWrapper<AidComicScript> update = Wrappers.lambdaUpdate();
        update.eq(AidComicScript::getProjectId, projectId);
        if (Objects.nonNull(episodeId))
        {
            update.eq(AidComicScript::getEpisodeId, episodeId);
        }
        // 强制按当前用户隔离，防止越权改他人剧本状态
        update.eq(AidComicScript::getUserId, userId);
        update.eq(AidComicScript::getStatus, SCRIPT_STATUS_ACTIVE);
        update.eq(AidComicScript::getDelFlag, DEL_FLAG_NORMAL);
        update.set(AidComicScript::getIsExtracted, IS_EXTRACTED_YES);
        update.set(AidComicScript::getUpdateTime, DateUtils.getNowDate());
        update.set(AidComicScript::getUpdateBy, String.valueOf(userId));
        scriptService.update(update);
    }
    /**
     * 将 List 序列化为 JSON 字符串
     *
     * @param list 列表
     * @return JSON 字符串，失败返回 null
     */
    public String toJsonString(List<String> list)
    {
        try
        {
            return OBJECT_MAPPER.writeValueAsString(list);
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
