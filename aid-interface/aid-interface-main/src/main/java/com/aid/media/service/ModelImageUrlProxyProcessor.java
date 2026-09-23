package com.aid.media.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.aid.aid.support.ModelImageUrlProxyTemplate;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

import lombok.RequiredArgsConstructor;

/**
 * 只改写模型输入中的图片 URL。其他媒体、回调及输出地址保持原值。
 */
@Service
@RequiredArgsConstructor
public class ModelImageUrlProxyProcessor
{
    private final OssTemplate ossTemplate;

    public void process(AiModelConfigVo config, MediaImageGenerateRequest request)
    {
        if (!enabled(config) || request == null)
        {
            return;
        }
        validateConfiguration(config);
        Map<String, String> cache = new LinkedHashMap<>();
        request.setReferenceImageUrl(proxy(config, request.getReferenceImageUrl(), cache));
        request.setMaskImageUrl(proxy(config, request.getMaskImageUrl(), cache));
        request.setOptions(processMap(config, request.getOptions(), false, cache));
    }

    public void process(AiModelConfigVo config, MediaVideoGenerateRequest request)
    {
        if (!enabled(config) || request == null)
        {
            return;
        }
        validateConfiguration(config);
        Map<String, String> cache = new LinkedHashMap<>();
        request.setImageUrl(proxy(config, request.getImageUrl(), cache));
        request.setOptions(processMap(config, request.getOptions(), false, cache));
    }

    public void process(AiModelConfigVo config, MediaTextGenerateRequest request)
    {
        if (!enabled(config) || request == null)
        {
            return;
        }
        validateConfiguration(config);
        if (request.getMessages() == null)
        {
            return;
        }
        Map<String, String> cache = new LinkedHashMap<>();
        for (MediaTextGenerateRequest.TextMessageItem message : request.getMessages())
        {
            if (message == null || message.getParts() == null)
            {
                continue;
            }
            for (MediaTextGenerateRequest.TextContentPart part : message.getParts())
            {
                if (part != null && "image".equalsIgnoreCase(part.getType()))
                {
                    part.setUrl(proxy(config, part.getUrl(), cache));
                }
            }
        }
    }

    private boolean enabled(AiModelConfigVo config)
    {
        return config != null && Boolean.TRUE.equals(config.getImageUrlProxyEnabled());
    }

    private void validateConfiguration(AiModelConfigVo config)
    {
        ModelImageUrlProxyTemplate.normalizeAndValidate(Boolean.TRUE, config.getImageUrlProxyTemplate());
    }

    private String proxy(AiModelConfigVo config, String source, Map<String, String> cache)
    {
        if (source == null)
        {
            return null;
        }
        return cache.computeIfAbsent(source,
                value -> ModelImageUrlProxyTemplate.apply(config.getImageUrlProxyTemplate(),
                        ossTemplate.toModelProxyUnsignedSourceUrl(value)));
    }

    /** Provider 内部派生图片（如标准化蒙版）复用模型图片代理规则。 */
    public String processImageUrl(AiModelConfigVo config, String source)
    {
        if (!enabled(config) || source == null)
        {
            return source;
        }
        validateConfiguration(config);
        return proxy(config, source, new LinkedHashMap<>());
    }

    private Map<String, Object> processMap(AiModelConfigVo config, Map<String, Object> source,
            boolean inheritedImageContext, Map<String, String> cache)
    {
        if (source == null || source.isEmpty())
        {
            return source;
        }
        boolean declaredImage = declaresImage(source);
        boolean activeImageContext = inheritedImageContext && !declaresNonImageMedia(source);
        Map<String, Object> result = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> {
            boolean excluded = isExcludedField(key);
            boolean imageContext = !excluded
                    && (activeImageContext || declaredImage || isImageField(key));
            result.put(key, processValue(config, key, value, imageContext, cache));
        });
        return result;
    }

    private Object processValue(AiModelConfigVo config, String fieldName, Object value,
            boolean imageContext, Map<String, String> cache)
    {
        if (value instanceof String text)
        {
            return imageContext ? proxy(config, text, cache) : text;
        }
        if (value instanceof Map<?, ?> map)
        {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), item));
            return processMap(config, nested, imageContext, cache);
        }
        if (value instanceof List<?> list)
        {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list)
            {
                result.add(processValue(config, fieldName, item, imageContext, cache));
            }
            return result;
        }
        return value;
    }

    private boolean declaresImage(Map<String, Object> source)
    {
        Object type = valueIgnoreCase(source, "type");
        if (type != null && "image".equalsIgnoreCase(String.valueOf(type)))
        {
            return true;
        }
        Object mimeType = valueIgnoreCase(source, "mimeType");
        if (mimeType == null)
        {
            mimeType = valueIgnoreCase(source, "mime_type");
        }
        return mimeType != null && String.valueOf(mimeType).toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private boolean declaresNonImageMedia(Map<String, Object> source)
    {
        Object type = valueIgnoreCase(source, "type");
        if (type != null)
        {
            String normalizedType = String.valueOf(type).toLowerCase(Locale.ROOT);
            if (normalizedType.equals("video") || normalizedType.equals("audio")
                    || normalizedType.equals("document"))
            {
                return true;
            }
        }
        Object mimeType = valueIgnoreCase(source, "mimeType");
        if (mimeType == null)
        {
            mimeType = valueIgnoreCase(source, "mime_type");
        }
        if (mimeType == null)
        {
            return false;
        }
        String normalizedMime = String.valueOf(mimeType).toLowerCase(Locale.ROOT);
        return normalizedMime.startsWith("video/") || normalizedMime.startsWith("audio/")
                || normalizedMime.startsWith("application/") || normalizedMime.startsWith("text/");
    }

    private Object valueIgnoreCase(Map<String, Object> source, String wanted)
    {
        for (Map.Entry<String, Object> entry : source.entrySet())
        {
            if (wanted.equalsIgnoreCase(entry.getKey()))
            {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isImageField(String fieldName)
    {
        String name = normalized(fieldName);
        return !isExcludedField(fieldName) && (name.contains("image")
                || name.equals("inputreference") || name.contains("firstframe")
                || name.contains("lastframe") || name.contains("endframe")
                || name.contains("keyframe"));
    }

    private boolean isExcludedField(String fieldName)
    {
        String name = normalized(fieldName);
        boolean videoResource = name.contains("video") && !name.contains("image")
                && !name.contains("firstframe") && !name.contains("lastframe")
                && !name.contains("endframe") && !name.contains("keyframe");
        return videoResource || name.contains("audio") || name.contains("document")
                || name.contains("callback") || name.contains("webhook") || name.contains("endpoint")
                || name.contains("baseurl") || name.contains("apiurl") || name.contains("output")
                || name.contains("result");
    }

    private String normalized(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    public void validate(AiModelConfigVo config)
    {
        if (enabled(config))
        {
            validateConfiguration(config);
        }
    }
}
