package com.aid.common.image.detection;

import cn.hutool.core.util.StrUtil;

/** 已由业务层完成授权并解析的原图来源。不可直接用请求参数构造。 */
public record ImageSource(Kind kind, String bucket, String region, String key, String url) {
    public enum Kind { COS_OBJECT, VERIFIED_URL }

    public ImageSource {
        if (kind == Kind.COS_OBJECT) {
            if (StrUtil.isBlank(bucket) || StrUtil.isBlank(region) || StrUtil.isBlank(key)) {
                throw new IllegalArgumentException("图片来源错误");
            }
        } else if (kind == Kind.VERIFIED_URL) {
            if (StrUtil.isBlank(url) || StrUtil.isNotBlank(bucket) || StrUtil.isNotBlank(region) || StrUtil.isNotBlank(key)) {
                throw new IllegalArgumentException("图片来源错误");
            }
        } else {
            throw new IllegalArgumentException("图片来源错误");
        }
    }

    public static ImageSource cosObject(String bucket, String region, String key) {
        return new ImageSource(Kind.COS_OBJECT, bucket, region, key, null);
    }

    /** COS 原图在 PUBLIC_URL 路由时还需要已授权的可读取地址。 */
    public static ImageSource cosObject(String bucket, String region, String key, String verifiedUrl) {
        return new ImageSource(Kind.COS_OBJECT, bucket, region, key, verifiedUrl);
    }

    public static ImageSource verifiedUrl(String url) {
        return new ImageSource(Kind.VERIFIED_URL, null, null, null, url);
    }
}
