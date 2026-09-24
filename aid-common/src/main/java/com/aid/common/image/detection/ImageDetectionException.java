package com.aid.common.image.detection;

public class ImageDetectionException extends RuntimeException {
    public enum Code { DISABLED, UNCONFIGURED, INVALID_IMAGE, RATE_LIMITED, AUTH_ERROR, PROVIDER_ERROR, TIMEOUT, PROTOCOL_ERROR }
    private final Code code;
    private final int providerCalls;
    private final String providerRequestId;

    public ImageDetectionException(Code code, String message, int providerCalls) {
        this(code, message, providerCalls, null);
    }

    public ImageDetectionException(Code code, String message, int providerCalls, String providerRequestId) {
        super(message);
        this.code = code;
        this.providerCalls = providerCalls;
        this.providerRequestId = providerRequestId;
    }

    public Code getCode() { return code; }
    public int getProviderCalls() { return providerCalls; }
    public String getProviderRequestId() { return providerRequestId; }
}
