package com.aid.media.provider;

import com.aid.common.exception.ServiceException;
import com.aid.common.oss.factory.OssFactory;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.util.MediaBytesFetcher;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;

/**
 * 图片编辑协议共用的素材转换器。所有输入 URL 必须已经经过业务权限校验，
 * 并由出站准备器签名或代理；本类不接受客户端资源标识，也不执行权限判断。
 */
@Slf4j
public final class ImageEditAssetSupport {
    public static final int MAX_INPUT_BYTES = 50 * 1024 * 1024;
    /** 解码保护上限，模型自身的更小尺寸限制仍由能力 Schema/Provider 单独校验。 */
    public static final long MAX_PIXELS = 20_000_000L;

    private ImageEditAssetSupport() { }

    public record BinaryImage(byte[] bytes, BufferedImage image) { }

    public record Dimensions(int width, int height) { }

    public record OpenAiExpandPayload(byte[] image, byte[] mask) { }

    /** 多结果后处理复用同一份已校验原图/蒙版，避免每张结果重复下载和解码。 */
    public record PreparedProtection(MediaImageGenerateRequest request, BufferedImage source,
                                     BufferedImage mask, boolean alphaEncoding) { }

    public static boolean isInpainting(MediaImageGenerateRequest request) {
        return request != null && "image_inpainting".equalsIgnoreCase(request.getCapabilityCode());
    }

    public static boolean isOutpainting(MediaImageGenerateRequest request) {
        return request != null && "image_outpainting".equalsIgnoreCase(request.getCapabilityCode());
    }

    /**
     * 区域重绘和扩图必须在平台侧完成像素保护后才能向业务暴露结果。
     * 整图编辑（含重新打光）没有区域外像素不变的契约，不进入该后处理门禁。
     */
    public static boolean requiresResultProtection(MediaImageGenerateRequest request) {
        return isInpainting(request) || isOutpainting(request);
    }

    public static BinaryImage read(String source, String label) {
        if (source == null || source.isBlank()) {
            throw new ServiceException(label + "不能为空");
        }
        byte[] bytes = dataUri(source);
        if (bytes == null) {
            MediaBytesFetcher.Content content = MediaBytesFetcher.fetch(source, MAX_INPUT_BYTES);
            if (content.isEmpty() || content.truncated()) {
                log.warn("图片编辑素材读取失败, label={}, truncated={}", label, content.truncated());
                throw new ServiceException(label + "读取失败");
            }
            bytes = content.bytes();
        }
        try {
            validateEncodedDimensions(bytes, label);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new ServiceException(label + "格式不支持");
            }
            image = normalizeOrientation(bytes, image, label);
            validatePixels(image, label);
            return new BinaryImage(bytes, image);
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("图片编辑素材解码失败, label={}, error={}", label, ex.getMessage());
            throw new ServiceException(label + "解析失败");
        }
    }

    /** 从真实素材读取尺寸，不能依赖调用方提交或旧资源可能缺失的元数据。 */
    public static Dimensions dimensions(String source, String label) {
        BufferedImage image = read(source, label).image();
        return new Dimensions(image.getWidth(), image.getHeight());
    }

    /** OpenAI mask：透明区域为编辑区，非透明区域为保留区。 */
    public static byte[] toOpenAiMask(MediaImageGenerateRequest request) {
        BinaryImage source = read(request.getReferenceImageUrl(), "原图");
        return toOpenAiMask(request, source.image());
    }

    /** 已读取原图时复用解码结果，避免构造 multipart 时重复下载、重复解码。 */
    public static byte[] toOpenAiMask(MediaImageGenerateRequest request, BufferedImage source) {
        BinaryImage mask = read(request.getMaskImageUrl(), "蒙版");
        requireSameSize(source, mask.image());
        BufferedImage output = new BufferedImage(mask.image().getWidth(), mask.image().getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        boolean alphaEncoding = isAlpha(request.getMaskEncoding());
        validateAlphaEncoding(mask.image(), alphaEncoding);
        validateNonEmptySelection(mask.image(), alphaEncoding);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                int argb = mask.image().getRGB(x, y);
                int edit = editAmount(argb, alphaEncoding);
                int nativeAlpha = 255 - edit;
                output.setRGB(x, y, (nativeAlpha << 24) | 0x00ffffff);
            }
        }
        return png(output);
    }

    /** OpenAI 扩图输入：透明目标画布中按权威坐标放置原图。 */
    public static byte[] toOpenAiExpandInput(MediaImageGenerateRequest request) {
        return prepareOpenAiExpand(request).image();
    }

    /** OpenAI 扩图蒙版：原图矩形不透明保留，其余透明生成。 */
    public static byte[] toOpenAiExpandMask(MediaImageGenerateRequest request) {
        return prepareOpenAiExpand(request).mask();
    }

    /** 一次读取原图，同时构造 OpenAI 扩图输入与蒙版。 */
    public static OpenAiExpandPayload prepareOpenAiExpand(MediaImageGenerateRequest request) {
        BinaryImage source = read(request.getReferenceImageUrl(), "原图");
        validateExpand(request, source.image(), null);
        BufferedImage canvas = new BufferedImage(request.getTargetWidth(), request.getTargetHeight(),
                BufferedImage.TYPE_INT_ARGB);
        copyRows(source.image(), canvas, request.getSourceX(), request.getSourceY());
        BufferedImage mask = new BufferedImage(request.getTargetWidth(), request.getTargetHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mask.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(255, 255, 255, 255));
            graphics.fillRect(request.getSourceX(), request.getSourceY(), source.image().getWidth(),
                    source.image().getHeight());
        } finally {
            graphics.dispose();
        }
        return new OpenAiExpandPayload(png(canvas), png(mask));
    }

    /** 万相 mask：白色为编辑区，黑色为保留区。 */
    public static String toWanMaskUrl(MediaImageGenerateRequest request) {
        BinaryImage source = read(request.getReferenceImageUrl(), "原图");
        BinaryImage mask = read(request.getMaskImageUrl(), "蒙版");
        requireSameSize(source.image(), mask.image());
        BufferedImage output = new BufferedImage(mask.image().getWidth(), mask.image().getHeight(),
                BufferedImage.TYPE_INT_RGB);
        boolean alphaEncoding = isAlpha(request.getMaskEncoding());
        validateAlphaEncoding(mask.image(), alphaEncoding);
        validateNonEmptySelection(mask.image(), alphaEncoding);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                int argb = mask.image().getRGB(x, y);
                int edit = editAmount(argb, alphaEncoding);
                output.setRGB(x, y, (edit << 16) | (edit << 8) | edit);
            }
        }
        try {
            return OssFactory.instance().uploadSuffix(png(output), ".png", "image/png").getUrl();
        } catch (Exception ex) {
            log.error("万相蒙版暂存失败, error={}", ex.getMessage());
            throw new ServiceException("蒙版转换失败");
        }
    }

    /** 对编辑结果执行确定性像素保护；有保护时统一返回 PNG。 */
    public static byte[] protectResult(MediaImageGenerateRequest request, byte[] generatedBytes) {
        if (!requiresResultProtection(request)) {
            return generatedBytes;
        }
        return protectResult(prepareResultProtection(request), generatedBytes);
    }

    public static PreparedProtection prepareResultProtection(MediaImageGenerateRequest request) {
        if (!requiresResultProtection(request)) {
            throw new ServiceException("当前能力不需要像素保护");
        }
        BinaryImage source = read(request.getReferenceImageUrl(), "原图");
        if (isOutpainting(request)) {
            validateExpand(request, source.image(), null);
            return new PreparedProtection(request, source.image(), null, false);
        }
        BinaryImage mask = read(request.getMaskImageUrl(), "蒙版");
        requireSameSize(source.image(), mask.image());
        boolean alphaEncoding = isAlpha(request.getMaskEncoding());
        validateAlphaEncoding(mask.image(), alphaEncoding);
        validateNonEmptySelection(mask.image(), alphaEncoding);
        return new PreparedProtection(request, source.image(), mask.image(), alphaEncoding);
    }

    public static byte[] protectResult(PreparedProtection protection, byte[] generatedBytes) {
        if (protection == null) throw new ServiceException("图片保护上下文不能为空");
        MediaImageGenerateRequest request = protection.request();
        BufferedImage source = protection.source();
        BufferedImage generated = decode(generatedBytes, "生成结果");
        if (isOutpainting(request)) {
            validateExpand(request, source, generated);
            BufferedImage output = new BufferedImage(generated.getWidth(), generated.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            copyRows(generated, output, 0, 0);
            copyRows(source, output, request.getSourceX(), request.getSourceY());
            return png(output);
        }
        BufferedImage mask = protection.mask();
        requireSameSize(source, generated);
        BufferedImage output = new BufferedImage(generated.getWidth(), generated.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                int maskArgb = mask.getRGB(x, y);
                int edit = editAmount(maskArgb, protection.alphaEncoding());
                output.setRGB(x, y, blend(source.getRGB(x, y), generated.getRGB(x, y), edit));
            }
        }
        return png(output);
    }

    public static void validateExpand(MediaImageGenerateRequest request) {
        BinaryImage source = read(request.getReferenceImageUrl(), "原图");
        validateExpand(request, source.image(), null);
    }

    /** 已读取真实原图尺寸时执行扩图几何校验。 */
    public static void validateExpand(MediaImageGenerateRequest request, Dimensions dimensions) {
        if (dimensions == null || dimensions.width() <= 0 || dimensions.height() <= 0) {
            throw new ServiceException("原图尺寸无效");
        }
        validateExpandGeometry(request, dimensions.width(), dimensions.height(), null);
    }

    private static void validateExpand(MediaImageGenerateRequest request, BufferedImage source,
                                       BufferedImage generated) {
        validateExpandGeometry(request, source.getWidth(), source.getHeight(), generated);
    }

    private static void validateExpandGeometry(MediaImageGenerateRequest request, int sourceWidth,
                                               int sourceHeight, BufferedImage generated) {
        Integer targetWidth = request.getTargetWidth();
        Integer targetHeight = request.getTargetHeight();
        Integer sourceX = request.getSourceX();
        Integer sourceY = request.getSourceY();
        if (targetWidth == null || targetHeight == null || sourceX == null || sourceY == null
                || targetWidth <= 0 || targetHeight <= 0 || sourceX < 0 || sourceY < 0
                || (long) sourceX + sourceWidth > targetWidth
                || (long) sourceY + sourceHeight > targetHeight) {
            throw new ServiceException("扩图区域无效");
        }
        if ((long) targetWidth * targetHeight > MAX_PIXELS) {
            throw new ServiceException("扩图尺寸过大");
        }
        if (targetWidth == sourceWidth && targetHeight == sourceHeight
                && sourceX == 0 && sourceY == 0) {
            throw new ServiceException("请至少扩展一侧");
        }
        if (generated != null && (generated.getWidth() != targetWidth || generated.getHeight() != targetHeight)) {
            throw new ServiceException("扩图结果尺寸异常");
        }
    }

    private static BufferedImage decode(byte[] bytes, String label) {
        try {
            validateEncodedDimensions(bytes, label);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new ServiceException(label + "格式不支持");
            validatePixels(image, label);
            return image;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException(label + "解析失败");
        }
    }

    private static void validatePixels(BufferedImage image, String label) {
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            throw new ServiceException(label + "尺寸过大");
        }
    }

    private static void validateEncodedDimensions(byte[] bytes, String label) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new ServiceException(label + "格式不支持");
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new ServiceException(label + "格式不支持");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                String normalizedFormat = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
                if (!java.util.Set.of("png", "jpeg", "jpg", "webp").contains(normalizedFormat)) {
                    throw new ServiceException(label + "仅支持 PNG、JPEG 或 WebP");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new ServiceException(label + "尺寸过大");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static void validateAlphaEncoding(BufferedImage mask, boolean alphaEncoding) {
        if (!alphaEncoding) return;
        if (!mask.getColorModel().hasAlpha()) throw new ServiceException("蒙版缺少透明通道");
    }

    private static void validateNonEmptySelection(BufferedImage mask, boolean alphaEncoding) {
        boolean hasEditArea = false;
        for (int y = 0; y < mask.getHeight() && !hasEditArea; y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                int argb = mask.getRGB(x, y);
                if (editAmount(argb, alphaEncoding) == 255) {
                    hasEditArea = true;
                    break;
                }
            }
        }
        if (!hasEditArea) throw new ServiceException("蒙版编辑区为空");
    }

    private static void requireSameSize(BufferedImage left, BufferedImage right) {
        if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) {
            throw new ServiceException("蒙版尺寸不一致");
        }
    }

    private static boolean isAlpha(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"ALPHA".equals(normalized) && !"BLACK_WHITE".equals(normalized)) {
            throw new ServiceException("蒙版语义无效");
        }
        return "ALPHA".equals(normalized);
    }

    private static int luminance(int argb) {
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        return (red * 299 + green * 587 + blue * 114 + 500) / 1000;
    }

    private static int binaryEdit(int argb) {
        return luminance(argb) >= 128 ? 255 : 0;
    }

    private static int editAmount(int argb, boolean alphaEncoding) {
        return alphaEncoding ? (((argb >>> 24) & 0xff) < 128 ? 255 : 0) : binaryEdit(argb);
    }

    /** 按扫描行精确复制 ARGB，避免大图再申请 width*height 的整图 int[]。 */
    private static void copyRows(BufferedImage source, BufferedImage target, int targetX, int targetY) {
        int[] row = new int[source.getWidth()];
        for (int y = 0; y < source.getHeight(); y++) {
            source.getRGB(0, y, source.getWidth(), 1, row, 0, source.getWidth());
            target.setRGB(targetX, targetY + y, source.getWidth(), 1, row, 0, source.getWidth());
        }
    }

    /** 按 EXIF Orientation 规范化像素矩阵，后续所有蒙版坐标都基于规范方向。 */
    private static BufferedImage normalizeOrientation(byte[] bytes, BufferedImage source, String label) {
        int orientation = 1;
        try {
            var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ex) {
            log.debug("图片方向元数据读取失败，按标准方向处理, label={}, error={}", label, ex.getMessage());
        }
        if (orientation <= 1) return source;
        if (orientation > 8) throw new ServiceException(label + "方向信息无效");
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage output = new BufferedImage(swap ? sourceHeight : sourceWidth,
                swap ? sourceWidth : sourceHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                int dx;
                int dy;
                switch (orientation) {
                    case 2 -> { dx = sourceWidth - 1 - x; dy = y; }
                    case 3 -> { dx = sourceWidth - 1 - x; dy = sourceHeight - 1 - y; }
                    case 4 -> { dx = x; dy = sourceHeight - 1 - y; }
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = sourceHeight - 1 - y; dy = x; }
                    case 7 -> { dx = sourceHeight - 1 - y; dy = sourceWidth - 1 - x; }
                    case 8 -> { dx = y; dy = sourceWidth - 1 - x; }
                    default -> { dx = x; dy = y; }
                }
                output.setRGB(dx, dy, source.getRGB(x, y));
            }
        }
        return output;
    }

    private static int blend(int source, int generated, int edit) {
        int inverse = 255 - edit;
        int a = (((source >>> 24) & 0xff) * inverse + ((generated >>> 24) & 0xff) * edit + 127) / 255;
        int r = (((source >>> 16) & 0xff) * inverse + ((generated >>> 16) & 0xff) * edit + 127) / 255;
        int g = (((source >>> 8) & 0xff) * inverse + ((generated >>> 8) & 0xff) * edit + 127) / 255;
        int b = ((source & 0xff) * inverse + (generated & 0xff) * edit + 127) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new ServiceException("图片编码失败");
            return output.toByteArray();
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException("图片编码失败");
        }
    }

    private static byte[] dataUri(String source) {
        if (source == null || !source.regionMatches(true, 0, "data:", 0, 5)) return null;
        int comma = source.indexOf(',');
        if (comma < 0 || !source.substring(0, comma).toLowerCase(Locale.ROOT).contains(";base64")) {
            throw new ServiceException("图片地址无效");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(source.substring(comma + 1));
        } catch (IllegalArgumentException ex) {
            throw new ServiceException("图片地址无效");
        }
        if (bytes.length == 0 || bytes.length > MAX_INPUT_BYTES) throw new ServiceException("图片文件过大");
        return bytes;
    }
}
