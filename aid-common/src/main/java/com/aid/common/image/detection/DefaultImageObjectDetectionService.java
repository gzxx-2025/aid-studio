package com.aid.common.image.detection;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/** 通用检测入口：配置、尺寸、来源与成本控制集中于此。 */
@Slf4j
@Service
public class DefaultImageObjectDetectionService implements ImageObjectDetectionService {
    private static final int MIN_WIDTH = 32, MIN_HEIGHT = 32, MAX_WIDTH = 7680, MAX_HEIGHT = 4320;
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final ImageDetectionConfigManager configManager;
    private final List<ImageObjectDetector> detectors;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public DefaultImageObjectDetectionService(ImageDetectionConfigManager configManager,
                                              List<ImageObjectDetector> detectors) {
        this.configManager = configManager;
        this.detectors = detectors;
    }

    @Override
    public DetectImageObjectsResult detect(DetectImageObjectsInput input, String callerKey) {
        ImageDetectionConfig config = configManager.current();
        if (!config.enabled()) throw error(ImageDetectionException.Code.DISABLED, "图像检测未启用");
        validate(input, config);
        if (StrUtil.isBlank(callerKey)) throw error(ImageDetectionException.Code.INVALID_IMAGE, "调用身份错误");
        long minute = System.currentTimeMillis() / 60_000;
        AtomicBoolean allowed = new AtomicBoolean();
        windows.compute(callerKey, (key, old) -> {
            Window next = old == null || old.minute != minute ? new Window(minute) : old;
            if (next.calls < config.maxCallsPerUserMinute()) {
                next.calls++;
                allowed.set(true);
            }
            return next;
        });
        if (!allowed.get()) throw error(ImageDetectionException.Code.RATE_LIMITED, "识别请求过频");
        // 周期清理仅维护限流状态，不保存识别结果。
        if (windows.size() > 10000) windows.entrySet().removeIf(e -> e.getValue().minute < minute - 1);
        ImageObjectDetector detector = detectors.stream().filter(d -> Objects.equals(d.provider(), config.provider()))
                .findFirst().orElseThrow(() -> error(ImageDetectionException.Code.UNCONFIGURED, "图像检测未配置"));
        long start = System.nanoTime();
        try {
            DetectImageObjectsResult result = detector.detect(input, config);
            log.info("图像主体检测完成: provider={}, count={}, elapsedMs={}, requestId={}, traceId={}",
                    result.provider(), result.objects().size(), (System.nanoTime() - start) / 1_000_000,
                    result.providerRequestId(), input.requestTraceId());
            return result;
        } catch (ImageDetectionException e) {
            log.warn("图像主体检测失败: code={}, calls={}, elapsedMs={}, traceId={}", e.getCode(),
                    e.getProviderCalls(), (System.nanoTime() - start) / 1_000_000, input.requestTraceId());
            throw e;
        }
    }

    private void validate(DetectImageObjectsInput input, ImageDetectionConfig config) {
        if (input == null || input.source() == null || input.imageWidth() < MIN_WIDTH || input.imageHeight() < MIN_HEIGHT
                || input.imageWidth() > MAX_WIDTH || input.imageHeight() > MAX_HEIGHT
                || input.imageBytes() <= 0 || input.imageBytes() > MAX_BYTES
                || !("png".equalsIgnoreCase(input.imageFormat()) || "jpg".equalsIgnoreCase(input.imageFormat())
                || "jpeg".equalsIgnoreCase(input.imageFormat()))) {
            throw error(ImageDetectionException.Code.INVALID_IMAGE, "图片规格错误");
        }
        ImageSource source = input.source();
        if (source.kind() == ImageSource.Kind.COS_OBJECT
                && (!config.bucketName().equals(source.bucket()) || !config.region().equals(source.region()))) {
            throw error(ImageDetectionException.Code.INVALID_IMAGE, "图片来源错误");
        }
        if (source.kind() == ImageSource.Kind.VERIFIED_URL || "PUBLIC_URL".equals(config.cosImageAccessMode())) {
            try {
                URI uri = URI.create(source.url());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || StrUtil.isBlank(uri.getHost())
                        || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                throw error(ImageDetectionException.Code.INVALID_IMAGE, "图片地址不可用");
            }
        }
    }

    private ImageDetectionException error(ImageDetectionException.Code code, String message) {
        return new ImageDetectionException(code, message, 0);
    }

    private static final class Window {
        private final long minute;
        private int calls;
        private Window(long minute) { this.minute = minute; }
    }
}
