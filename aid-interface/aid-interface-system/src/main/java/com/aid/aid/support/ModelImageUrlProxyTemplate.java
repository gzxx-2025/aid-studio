package com.aid.aid.support;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import com.aid.common.exception.ServiceException;

/**
 * 模型图片代理模板的统一校验与拼接规则。
 */
public final class ModelImageUrlProxyTemplate
{
    public static final String PLACEHOLDER = "{url}";
    public static final int MAX_LENGTH = 1000;

    private ModelImageUrlProxyTemplate()
    {
    }

    /** 规范化模板；开启时同时执行完整校验。 */
    public static String normalizeAndValidate(Boolean enabled, String template)
    {
        String normalized = template == null ? null : template.trim();
        if (normalized != null && normalized.length() > MAX_LENGTH)
        {
            throw new ServiceException("图片代理 URL 模板不能超过 1000 个字符");
        }
        if (!Boolean.TRUE.equals(enabled))
        {
            return normalized;
        }
        if (normalized == null || normalized.isEmpty())
        {
            throw new ServiceException("启用图片代理拼接时必须填写 URL 模板");
        }
        validateEnabledTemplate(normalized);
        return normalized;
    }

    /** 将完整源地址按 UTF-8 百分号编码后代入模板。 */
    public static String apply(String template, String sourceUrl)
    {
        String normalized = normalizeAndValidate(Boolean.TRUE, template);
        if (!isHttpUrl(sourceUrl))
        {
            return sourceUrl;
        }
        return normalized.replace(PLACEHOLDER, percentEncode(sourceUrl));
    }

    private static void validateEnabledTemplate(String template)
    {
        int first = template.indexOf(PLACEHOLDER);
        if (first < 0 || first != template.lastIndexOf(PLACEHOLDER))
        {
            throw new ServiceException("图片代理 URL 模板必须且只能包含一个 {url} 占位符");
        }

        int schemeEnd = template.indexOf("://");
        if (schemeEnd < 0 || !"https".equalsIgnoreCase(template.substring(0, schemeEnd)))
        {
            throw new ServiceException("图片代理 URL 模板必须使用绝对 HTTPS 地址");
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = template.length();
        for (char delimiter : new char[] {'/', '?', '#'})
        {
            int index = template.indexOf(delimiter, authorityStart);
            if (index >= 0 && index < authorityEnd)
            {
                authorityEnd = index;
            }
        }
        if (first < authorityEnd)
        {
            throw new ServiceException("{url} 占位符只能放在代理地址的路径或查询参数中");
        }

        try
        {
            URI uri = new URI(template.replace(PLACEHOLDER, "encoded-source-url"));
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
            {
                throw new ServiceException("图片代理 URL 模板必须使用绝对 HTTPS 地址");
            }
            if (uri.getRawUserInfo() != null)
            {
                throw new ServiceException("图片代理 URL 模板不能包含账号或密码");
            }
            if (uri.getRawFragment() != null)
            {
                throw new ServiceException("图片代理 URL 模板不能包含 Fragment");
            }
        }
        catch (URISyntaxException ex)
        {
            throw new ServiceException("图片代理 URL 模板格式无效");
        }
    }

    private static boolean isHttpUrl(String value)
    {
        if (value == null)
        {
            return false;
        }
        try
        {
            URI uri = new URI(value);
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        }
        catch (URISyntaxException ex)
        {
            return false;
        }
    }

    /** RFC 3986 百分号编码：只保留 unreserved 字符。 */
    private static String percentEncode(String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 3);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte item : bytes)
        {
            int unsigned = item & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-'
                    || unsigned == '.' || unsigned == '_' || unsigned == '~')
            {
                encoded.append((char) unsigned);
            }
            else
            {
                encoded.append('%').append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }
}
