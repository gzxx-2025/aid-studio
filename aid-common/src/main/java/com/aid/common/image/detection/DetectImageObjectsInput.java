package com.aid.common.image.detection;

/** 原图尺寸与文件元数据必须来自服务端可信资源记录。 */
public record DetectImageObjectsInput(ImageSource source, int imageWidth, int imageHeight,
                                      long imageBytes, String imageFormat, String requestTraceId) {}
