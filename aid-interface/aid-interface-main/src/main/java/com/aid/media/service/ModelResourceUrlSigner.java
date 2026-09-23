package com.aid.media.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.aid.media.dto.ReferenceVideoInput;

import lombok.RequiredArgsConstructor;

/**
 * 外部大模型输入资源签名器。只在任务取得并发槽并即将调用上游时工作，
 * 不改任务存档，也不改变页面展示使用的资源访问地址。
 */
@Service
@RequiredArgsConstructor
public class ModelResourceUrlSigner
{
    private final OssTemplate ossTemplate;

    public void sign(MediaImageGenerateRequest request)
    {
        if (request == null)
        {
            return;
        }
        request.setReferenceImageUrl(signOne(request.getReferenceImageUrl()));
        request.setMaskImageUrl(signOne(request.getMaskImageUrl()));
        request.setOptions(signMap(request.getOptions()));
    }

    public void sign(MediaVideoGenerateRequest request)
    {
        if (request == null)
        {
            return;
        }
        Map<String, String> signedUrls = new LinkedHashMap<>();
        request.setImageUrl(signOne(request.getImageUrl(), signedUrls));
        if (request.getReferenceAudios() != null)
        {
            for (ReferenceAudioInput audio : request.getReferenceAudios())
            {
                if (audio != null)
                {
                    audio.setSampleUrl(signOne(audio.getSampleUrl(), signedUrls));
                }
            }
        }
        if (request.getResolvedReferenceVideos() != null)
        {
            List<ReferenceVideoInput> videos = new ArrayList<>();
            for (ReferenceVideoInput video : request.getResolvedReferenceVideos())
            {
                if (video == null) { videos.add(null); continue; }
                videos.add(new ReferenceVideoInput(video.getRecordId(), signOne(video.getVideoUrl(), signedUrls),
                        video.getDurationMs(), video.getFileSizeBytes(), video.getWidth(), video.getHeight(),
                        video.getFps(), video.getFormat()));
            }
            request.setResolvedReferenceVideos(videos);
        }
        request.setOptions(signMap(request.getOptions(), signedUrls));
    }

    /** 文本多模态只签名显式声明为 image 的内容块。 */
    public void sign(MediaTextGenerateRequest request)
    {
        if (request == null || request.getMessages() == null)
        {
            return;
        }
        Map<String, String> signedUrls = new LinkedHashMap<>();
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
                    part.setUrl(signOne(part.getUrl(), signedUrls));
                }
            }
        }
    }

    /** 语音模型的厂商扩展参数也可能承载参考音频 URL。 */
    public void sign(MediaAudioGenerateRequest request)
    {
        if (request != null)
        {
            request.setOptions(signMap(request.getOptions()));
        }
    }

    private String signOne(String value)
    {
        return ossTemplate.isManagedResourceUrl(value) ? ossTemplate.getModelSignedUrl(value) : value;
    }

    /** Provider 内部新生成的临时图片也必须经过与普通模型输入相同的存储签名。 */
    public String signImageUrl(String value)
    {
        return signOne(value);
    }

    private Map<String, Object> signMap(Map<String, Object> source)
    {
        return signMap(source, new LinkedHashMap<>());
    }

    private String signOne(String value, Map<String, String> signedUrls)
    {
        return value == null ? null : signedUrls.computeIfAbsent(value, this::signOne);
    }

    private Map<String, Object> signMap(Map<String, Object> source, Map<String, String> signedUrls)
    {
        if (source == null || source.isEmpty())
        {
            return source;
        }
        Map<String, Object> result = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> result.put(key, signValue(key, value, signedUrls)));
        return result;
    }

    private Object signValue(String fieldName, Object value, Map<String, String> signedUrls)
    {
        if (value instanceof String text)
        {
            return isResourceField(fieldName) ? signOne(text, signedUrls) : text;
        }
        if (value instanceof Map<?, ?> map)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String nestedName = String.valueOf(key);
                result.put(nestedName, signValue(nestedName, item, signedUrls));
            });
            return result;
        }
        if (value instanceof List<?> list)
        {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list)
            {
                result.add(signValue(fieldName, item, signedUrls));
            }
            return result;
        }
        return value;
    }

    /** 只处理明确承载媒体资源的扩展字段，避免误签名以 / 开头的普通协议参数。 */
    private boolean isResourceField(String fieldName)
    {
        if (fieldName == null)
        {
            return false;
        }
        String name = fieldName.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("callback") || name.contains("webhook") || name.contains("endpoint")
                || name.contains("baseurl") || name.contains("apiurl"))
        {
            return false;
        }
        return name.endsWith("url") || name.endsWith("urls")
                || name.contains("image") || name.contains("video") || name.contains("audio")
                || name.contains("reference") || name.contains("firstframe") || name.contains("lastframe");
    }
}
