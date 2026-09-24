package com.aid.common.image.detection;

/** 置信度使用腾讯云原始 0..100 整数刻度。 */
public record DetectedObject(String label, int confidence, PixelBox box) {}
