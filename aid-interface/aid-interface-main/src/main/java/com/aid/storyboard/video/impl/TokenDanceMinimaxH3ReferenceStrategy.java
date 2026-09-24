package com.aid.storyboard.video.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import com.aid.tokendance.provider.common.TokenDanceProtocols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 装配 TokenDance MiniMax H3 多参考视频的分镜图片。 */
@Slf4j
@Component
public class TokenDanceMinimaxH3ReferenceStrategy extends AbstractVideoReferenceStrategy
{
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("@图片(\\d+)\\[([^\\]]*)\\]");

    @Override
    public boolean supportsModelConfig(AiModelConfigVo model)
    {
        return model != null && TokenDanceProtocols.isTokenDance(model.getProviderCode())
                && TokenDanceProtocols.matches(TokenDanceProtocols.MINIMAX_VIDEO_GENERATION_V2,
                        model.getProtocol())
                && "reference_to_video".equalsIgnoreCase(StrUtil.trim(model.getCapabilityCode()));
    }

    @Override
    public VideoReferencePlan assemble(VideoReferenceContext context)
    {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        String base = StrUtil.trimToNull(context.getBaseImageUrl());
        if (base != null)
        {
            indexes.put(base, 1);
        }
        Map<Integer, Integer> referenceIndexes = new LinkedHashMap<>();
        for (ResolvedReference reference : takeRefs(context.getReferences(), -1))
        {
            String url = reference.getUrl().trim();
            Integer position = indexes.computeIfAbsent(url, ignored -> indexes.size() + 1);
            referenceIndexes.put(reference.getOriginalN(), position);
        }
        int actual = indexes.size();
        int max = context.getMaxReferenceImages();
        if (max >= 0 && actual > max)
        {
            log.info("TokenDance H3 分镜参考图超限: max={}, actual={}", max, actual);
            throw new ServiceException("参考图片数量超限");
        }
        if (actual > 1 && !supportsMultiImage(context.getModelConfig()))
        {
            log.info("TokenDance H3 分镜模型不支持多图: actual={}", actual);
            throw new ServiceException("模型不支持多图");
        }
        String prompt = remapPrompt(context.getVideoPrompt(), referenceIndexes);
        return VideoReferencePlan.of(composePrompt(prompt, null, context.getUserInputText()),
                new ArrayList<>(indexes.keySet()), null);
    }

    private String remapPrompt(String prompt, Map<Integer, Integer> referenceIndexes)
    {
        Matcher matcher = IMAGE_REFERENCE.matcher(StrUtil.nullToEmpty(prompt));
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find())
        {
            int original;
            try
            {
                original = Integer.parseInt(matcher.group(1));
            }
            catch (NumberFormatException exception)
            {
                throw new ServiceException("参考图编号无效");
            }
            Integer position = referenceIndexes.get(original);
            if (position == null)
            {
                throw new ServiceException("参考图编号无效");
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(
                    "@图片" + position + "[" + matcher.group(2) + "]"));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }
}
