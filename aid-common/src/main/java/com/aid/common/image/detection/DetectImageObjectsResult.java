package com.aid.common.image.detection;

import java.util.List;

public record DetectImageObjectsResult(String provider, boolean detected,
                                       List<DetectedObject> objects, String providerRequestId) {}
