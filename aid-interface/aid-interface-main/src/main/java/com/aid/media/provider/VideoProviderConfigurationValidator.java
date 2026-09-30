package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;

/** 原生视频协议的凭证检查；使用已经解析了网关和用户覆盖的最终凭证。 */
public final class VideoProviderConfigurationValidator {
    private VideoProviderConfigurationValidator() { }

    public static void validate(AiModelConfigVo config) {
        if (config == null) return;
        String provider = StrUtil.trimToEmpty(config.getProviderCode());
        // 这些原生协议仅用 API key 鉴权；不影响自定义请求头、签名或免鉴权协议。
        if (("vidu".equalsIgnoreCase(provider) || "agnes".equalsIgnoreCase(provider))
                && StrUtil.isBlank(config.getApiKey())) {
            throw new ServiceException("视频服务未配置密钥，请联系管理员配置服务商");
        }
    }
}
