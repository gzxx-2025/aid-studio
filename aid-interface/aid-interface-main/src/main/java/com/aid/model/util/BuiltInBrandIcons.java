package com.aid.model.util;

import java.util.Locale;

import cn.hutool.core.util.StrUtil;

/** Packaged public provider icons used only when no custom logo is configured. */
public final class BuiltInBrandIcons
{
    private BuiltInBrandIcons() {}

    public static String resolve(String providerCode, String configuredUrl)
    {
        if (StrUtil.isNotBlank(configuredUrl)) return configuredUrl;
        if (providerCode == null) return null;
        return switch (providerCode.trim().toLowerCase(Locale.ROOT)) {
            case "dashscope", "volcengine", "jimeng", "volcengine_tts", "vidu", "deepseek" ->
                    "/brand-icons/" + providerCode.trim().toLowerCase(Locale.ROOT) + ".jpg";
            case "gemini", "openai", "minimax", "agnes", "kling", "tokendance" ->
                    "/brand-icons/" + providerCode.trim().toLowerCase(Locale.ROOT) + ".png";
            case "topaz" -> "/brand-icons/topaz.ico";
            case "wavespeed" -> "/brand-icons/wavespeed.ico";
            case "tencent_ci_media", "tencent_mps" -> "/brand-icons/tencent-cloud.ico";
            default -> null;
        };
    }
}
