package com.aid.storyboard.video;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.common.exception.ServiceException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;

/** 提示词未生成时的候选图片引用，仅用于只读报价；正式提交仍以最终提示词为准。 */
public final class StoryboardPlannedImageReferences {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SECTION = Pattern.compile("(场景|角色|道具|视频|音频)\\s*[：:]");
    private static final Pattern NAME = Pattern.compile("\\[([^\\]]+)\\]");
    private StoryboardPlannedImageReferences() { }

    public static Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> params = MAPPER.readValue(json, new TypeReference<>() { });
            return params == null ? new LinkedHashMap<>() : params;
        } catch (Exception e) {
            throw new ServiceException("分镜脚本格式异常");
        }
    }

    public static String buildPrompt(Map<String, Object> params) {
        String info = String.valueOf(params.getOrDefault("引用信息", ""));
        var sections = SECTION.matcher(info);
        var names = new LinkedHashSet<String>();
        String type = null;
        int start = 0;
        while (sections.find()) {
            collect(names, type, info.substring(start, sections.start()));
            type = sections.group(1);
            start = sections.end();
        }
        collect(names, type, info.substring(start));
        StringBuilder prompt = new StringBuilder();
        int index = 1;
        for (String name : names) prompt.append("@图片").append(index++).append('[').append(name).append("]\n");
        return prompt.toString();
    }

    private static void collect(LinkedHashSet<String> names, String type, String text) {
        if (!"场景".equals(type) && !"角色".equals(type) && !"道具".equals(type)) return;
        var matcher = NAME.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) names.add(name);
        }
    }
}
