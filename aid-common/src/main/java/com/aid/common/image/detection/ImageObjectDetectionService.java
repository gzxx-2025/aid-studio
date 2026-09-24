package com.aid.common.image.detection;

public interface ImageObjectDetectionService {
    /** 每次调用均向供应商发起一次识别；callerKey 用于单调用方限流。 */
    DetectImageObjectsResult detect(DetectImageObjectsInput input, String callerKey);
}
