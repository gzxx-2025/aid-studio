package com.aid.media.constants;

/**
 * OpenAI GPT Image（gpt-image-2 等）图片生成相关常量，输出固定 base64 由 Provider 落 OSS。
 *
 * @author 视觉AID
 */
public final class OpenAiImageConstants {

    private OpenAiImageConstants() {
    }
    /** aid_ai_provider.provider_code 约定 */
    public static final String PROVIDER_CODE = "openai";

    /** 图片协议标识：与 aid_ai_model.protocol、OpenAiImageProviderClient.protocol() 一致 */
    public static final String PROTOCOL_IMAGE = "openai-image";

    /** 兜底默认模型（生产应在 aid_ai_model 配置 real_model_code） */
    public static final String DEFAULT_IMAGE_MODEL = "gpt-image-2";
    public static final String JSON_MODEL = "model";
    public static final String JSON_PROMPT = "prompt";
    public static final String JSON_N = "n";
    public static final String JSON_SIZE = "size";
    public static final String JSON_QUALITY = "quality";
    public static final String JSON_BACKGROUND = "background";
    public static final String JSON_OUTPUT_FORMAT = "output_format";
    public static final String JSON_OUTPUT_COMPRESSION = "output_compression";
    public static final String JSON_MODERATION = "moderation";
    /** edits 入参：参考图引用数组，元素 {@code {"image_url": "..."}} */
    public static final String JSON_IMAGES = "images";
    public static final String JSON_IMAGE_URL = "image_url";
    public static final String JSON_DATA = "data";
    public static final String JSON_B64 = "b64_json";
    public static final String JSON_URL = "url";
    public static final String JSON_ERROR = "error";
    public static final String JSON_MESSAGE = "message";
    /** GPT image 官方参考图上限 16 张（运营可在 capability_json.maxReferenceImages 覆盖） */
    public static final int MAX_REFERENCE_IMAGES = 16;

    /** 默认尺寸 */
    public static final String DEFAULT_IMAGE_SIZE = "1024x1024";

    /** 一次最多出图张数（官方 n 上限 10） */
    public static final int MAX_IMAGE_COUNT = 10;

    /** 图片生成超时（毫秒）：高质量 + 多图可能较久，取 300s */
    public static final int IMAGE_TIMEOUT_MS = 300_000;
    public static final String IMAGE_SUFFIX_PNG = ".png";
    public static final String IMAGE_CONTENT_TYPE_PNG = "image/png";
    public static final String IMAGE_SUFFIX_JPG = ".jpg";
    public static final String IMAGE_CONTENT_TYPE_JPEG = "image/jpeg";
    public static final String IMAGE_SUFFIX_WEBP = ".webp";
    public static final String IMAGE_CONTENT_TYPE_WEBP = "image/webp";
    /** 允许的 output_format 取值：png（默认）/ jpeg（落库后缀 .jpg）；其余格式一律按 png 处理 */
    public static final String OUTPUT_FORMAT_PNG = "png";
    public static final String OUTPUT_FORMAT_JPEG = "jpeg";
    public static final String OUTPUT_FORMAT_JPG = "jpg";
    public static final String OUTPUT_FORMAT_WEBP = "webp";
    public static final int LOG_RESPONSE_SNIPPET_MAX = 500;
    public static final String ERROR_API_KEY_EMPTY = "密钥未配置";
    public static final String ERROR_BASE_URL_EMPTY = "网关未配置";
    public static final String ERROR_NO_IMAGE = "出图失败";
    public static final String ERROR_SERIALIZE = "序列化失败";

    /** 归一化任务状态：同步直出 */
    public static final String TASK_STATUS_SUCCEEDED = "SUCCEEDED";
}
