package com.aid.media.util;

import cn.hutool.core.util.StrUtil;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.ReferenceAudioInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 从模型能力与真实媒体输入统一解析视频能力场景和计费场景。 */
public final class MediaGenerationSceneResolver {
    private static final String[] IMAGE_LIST_KEYS = {
            "referenceImages", "images", "keyImages", "key_images", "image_settings", "imageSettings"
    };
    private static final String[] VIDEO_SINGLE_KEYS = {
            "featureVideoUrl", "referenceVideoUrl", "baseVideoUrl", "inputVideoUrl", "videoUrl", "video_url"
    };
    private static final String[] VIDEO_LIST_KEYS = {"referenceVideos", "videos"};
    private static final String[] LAST_FRAME_KEYS = {"lastFrameImageUrl", "endImageUrl", "end_image_url"};

    private MediaGenerationSceneResolver() {
    }

    public static ResolvedVideoScene resolveVideo(AiModelConfigVo modelConfig,
                                                  MediaVideoGenerateRequest request) {
        if (modelConfig != null && modelConfig.getCapabilityCode() != null) {
            String scene = switch (modelConfig.getGenerateMode()) {
                case "text_to_video" -> "textToVideo";
                case "image_to_video", "last_frame_to_video" -> "imageToVideo";
                case "start_end_to_video" -> "startEndToVideo";
                case "reference_to_video" -> "referenceToVideo";
                case "video_edit", "video_extend", "feature_video", "video_to_video", "lip_sync" -> "videoToVideo";
                default -> null;
            };
            if (scene != null) return new ResolvedVideoScene(scene, billingGenerateMode(scene));
        }
        JsonNode capability = ModelCapabilityResolver.parseCapability(
                modelConfig == null ? null : modelConfig.getCapabilityJson());
        if (capability == null) capability = MissingNode.getInstance();
        InputFacts inputs = videoInputs(modelConfig, capability, request);
        String capabilityScene = capabilityScene(modelConfig, capability, request, inputs);
        return new ResolvedVideoScene(capabilityScene, billingGenerateMode(capabilityScene));
    }

    private static String capabilityScene(AiModelConfigVo modelConfig, JsonNode capability,
                                          MediaVideoGenerateRequest request, InputFacts inputs) {
        String configuredScene = scenarioToScene(ModelCapabilityResolver.readText(capability, "videoScenario"));
        if (StrUtil.isNotBlank(configuredScene)) return configuredScene;

        JsonNode scenes = capability.path("sceneRules");
        String mode = normalizeMode(modelConfig == null ? null : modelConfig.getGenerateMode());
        boolean hasMedia = inputs.hasImage() || inputs.hasVideo() || inputs.hasAudio();
        if (!hasMedia && declaresScene(capability, "textToVideo")) return "textToVideo";
        if (inputs.lastFrame() || "first_last_frame".equals(mode)
                || "start_end".equals(mode) || "start_end_to_video".equals(mode)) {
            return "startEndToVideo";
        }
        if (inputs.firstFrame()) return "imageToVideo";

        String requestedScene = requestedScene(request);
        if (isConsistentRequestedScene(requestedScene, inputs)
                && (!hasDeclaredScenes(capability) || declaresScene(capability, requestedScene))) {
            return requestedScene;
        }

        if ((inputs.referenceImageCount() > 0 || inputs.referenceVideoCount() > 0
                || inputs.referenceAudioCount() > 0)
                && declaresScene(capability, "referenceToVideo")
                && !declaresScene(capability, "videoToVideo")) {
            return "referenceToVideo";
        }
        if (("image_to_video".equals(mode) || "first_frame".equals(mode))
                && !isReferenceImageRole(capability)) return "imageToVideo";
        if ("reference_to_video".equals(mode) || "reference".equals(mode)) return "referenceToVideo";
        if ("video_to_video".equals(mode) || "video_edit".equals(mode)
                || "video_extend".equals(mode) || "edit".equals(mode)
                || "extend".equals(mode) || "lip_sync".equals(mode)) return "videoToVideo";
        // 没有官方场景、素材角色或模型模式的历史配置，保持原计费提取语义：
        // 有图走 IMAGE_TO_VIDEO，其余走 TEXT_TO_VIDEO；Kling 的真实视频输入例外走 VIDEO_TO_VIDEO。
        if (!hasDeclaredScenes(capability)
                && StrUtil.isBlank(ModelCapabilityResolver.readText(capability, "inputImageRole"))
                && StrUtil.isBlank(mode)) {
            if (inputs.hasImage()) return "imageToVideo";
            if (isKlingVideoModel(request) && inputs.hasVideo()) return "videoToVideo";
            return "textToVideo";
        }
        // 多参考场景仅允许图片而视频场景明确允许视频时，按实际素材选择视频场景。
        // 不能把可灵 Omni 的 feature/base video 误送进图片参考规则。
        if (inputs.hasVideo() && declaresScene(capability, "videoToVideo")
                && scenes.path("referenceToVideo").path("allowedInputs").isArray()) {
            boolean allowsVideo = false;
            for (JsonNode input : scenes.path("referenceToVideo").path("allowedInputs")) {
                if (Set.of("video", "referencevideo", "reference_video").contains(
                        input.asText().toLowerCase(Locale.ROOT))) allowsVideo = true;
            }
            if (!allowsVideo) return "videoToVideo";
        }
        if (inputs.referenceImageCount() > 0 || inputs.referenceVideoCount() > 0
                || inputs.referenceAudioCount() > 0) return "referenceToVideo";
        if (inputs.hasVideo()) return "videoToVideo";
        if (inputs.hasImage() && scenes.has("referenceToVideo") && !scenes.has("imageToVideo")) {
            return "referenceToVideo";
        }
        if (inputs.hasImage()) return "imageToVideo";
        return inputs.hasAudio() ? "referenceToVideo" : "textToVideo";
    }

    private static InputFacts videoInputs(AiModelConfigVo modelConfig, JsonNode capability,
                                          MediaVideoGenerateRequest request) {
        if (request == null) return new InputFacts(false, false, 0, 0, 0);
        Map<String, Object> options = request.getOptions();
        Set<String> referenceImages = new LinkedHashSet<>();
        String lastFrame = firstText(options, LAST_FRAME_KEYS);
        boolean hasLastFrame = StrUtil.isNotBlank(lastFrame);
        for (String key : IMAGE_LIST_KEYS) addUrls(referenceImages, options == null ? null : options.get(key));

        Set<String> videos = new LinkedHashSet<>();
        for (String key : VIDEO_SINGLE_KEYS) addUrl(videos, options == null ? null : options.get(key));
        for (String key : VIDEO_LIST_KEYS) addUrls(videos, options == null ? null : options.get(key));

        Set<String> referenceAudios = new LinkedHashSet<>();
        List<ReferenceAudioInput> audios = request.getReferenceAudios();
        if (audios != null) {
            audios.stream().filter(Objects::nonNull)
                    .map(ReferenceAudioInput::getSampleUrl).filter(StrUtil::isNotBlank)
                    .forEach(referenceAudios::add);
        }
        addUrl(referenceAudios, request.getVoiceId());
        addUrls(referenceAudios, options == null ? null : options.get("referenceAudioVoiceIds"));
        int audioCount = referenceAudios.size();
        Object lipSyncAudio = options == null ? null : options.get("audio_url");
        if (lipSyncAudio != null && StrUtil.isNotBlank(String.valueOf(lipSyncAudio))) {
            audioCount = Math.max(audioCount, 1);
        }

        boolean hasOtherReferenceMedia = !referenceImages.isEmpty() || !videos.isEmpty() || audioCount > 0;
        boolean firstFrame = StrUtil.isNotBlank(request.getImageUrl())
                && isFirstFrameSemantic(modelConfig, capability, hasLastFrame)
                && (hasLastFrame || !hasOtherReferenceMedia);
        if (!firstFrame) addUrl(referenceImages, request.getImageUrl());
        return new InputFacts(firstFrame, hasLastFrame, referenceImages.size(), videos.size(), audioCount);
    }

    private static String requestedScene(MediaVideoGenerateRequest request) {
        String requested = firstText(request == null ? null : request.getOptions(), "generateMode");
        if (StrUtil.isBlank(requested)) return null;
        return switch (normalizeMode(requested).replace("-", "_")) {
            case "text_to_video", "texttovideo" -> "textToVideo";
            case "image_to_video", "imagetovideo" -> "imageToVideo";
            case "edge_to_video", "edgetovideo", "start_end_to_video", "startendtovideo" -> "startEndToVideo";
            case "multi_to_video", "multitovideo", "reference_to_video", "referencetovideo" -> "referenceToVideo";
            case "video_to_video", "videotovideo" -> "videoToVideo";
            default -> null;
        };
    }

    private static boolean isConsistentRequestedScene(String scene, InputFacts inputs) {
        if (scene == null) return false;
        return switch (scene) {
            case "textToVideo" -> !inputs.hasImage() && !inputs.hasVideo() && !inputs.hasAudio();
            case "imageToVideo" -> inputs.hasImage() && !inputs.lastFrame() && !inputs.hasVideo();
            case "startEndToVideo" -> inputs.firstFrame() && inputs.lastFrame();
            case "referenceToVideo" -> inputs.referenceImageCount() > 0
                    || inputs.referenceVideoCount() > 0 || inputs.referenceAudioCount() > 0;
            case "videoToVideo" -> inputs.hasVideo();
            default -> false;
        };
    }

    private static String billingGenerateMode(String scene) {
        return switch (StrUtil.blankToDefault(scene, "")) {
            case "textToVideo" -> "TEXT_TO_VIDEO";
            case "imageToVideo" -> "IMAGE_TO_VIDEO";
            case "startEndToVideo" -> "EDGE_TO_VIDEO";
            case "referenceToVideo" -> "MULTI_TO_VIDEO";
            case "videoToVideo" -> "VIDEO_TO_VIDEO";
            default -> null;
        };
    }

    private static boolean isKlingVideoModel(MediaVideoGenerateRequest request) {
        String modelName = request == null ? null : request.getModelName();
        return StrUtil.isNotBlank(modelName)
                && modelName.trim().toLowerCase(Locale.ROOT).startsWith("kling-3.0-");
    }

    private static boolean isFirstFrameSemantic(AiModelConfigVo modelConfig, JsonNode capability,
                                                boolean hasLastFrame) {
        if (hasLastFrame) return true;
        String imageRole = normalizeMode(ModelCapabilityResolver.readText(capability, "inputImageRole"));
        if (StrUtil.isNotBlank(imageRole)) {
            return "first_frame".equals(imageRole) || "firstframe".equals(imageRole);
        }
        String scenario = scenarioToScene(ModelCapabilityResolver.readText(capability, "videoScenario"));
        if (StrUtil.isNotBlank(scenario)) {
            return "imageToVideo".equals(scenario) || "startEndToVideo".equals(scenario);
        }
        String mode = normalizeMode(modelConfig == null ? null : modelConfig.getGenerateMode());
        return "image_to_video".equals(mode) || "first_frame".equals(mode)
                || "first_last_frame".equals(mode) || "start_end".equals(mode)
                || "start_end_to_video".equals(mode);
    }

    private static boolean isReferenceImageRole(JsonNode capability) {
        String imageRole = normalizeMode(ModelCapabilityResolver.readText(capability, "inputImageRole"));
        return "reference".equals(imageRole) || "reference_image".equals(imageRole)
                || "referenceimage".equals(imageRole);
    }

    private static boolean hasDeclaredScenes(JsonNode capability) {
        JsonNode allowed = capability.get("allowedScenes");
        if (allowed != null && (allowed.isTextual() && StrUtil.isNotBlank(allowed.asText())
                || allowed.isArray() && !allowed.isEmpty())) return true;
        JsonNode rules = capability.path("sceneRules");
        return rules.isObject() && !rules.isEmpty();
    }

    private static boolean declaresScene(JsonNode capability, String scene) {
        JsonNode allowed = capability.get("allowedScenes");
        if (allowed != null) {
            if (allowed.isTextual() && sameScene(allowed.asText(), scene)) return true;
            if (allowed.isArray()) {
                for (JsonNode value : allowed) {
                    if (value.isTextual() && sameScene(value.asText(), scene)) return true;
                }
            }
        }
        JsonNode rules = capability.path("sceneRules");
        if (!rules.isObject()) return false;
        java.util.Iterator<String> fields = rules.fieldNames();
        while (fields.hasNext()) if (sameScene(fields.next(), scene)) return true;
        return false;
    }

    private static String scenarioToScene(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        return switch (normalizeMode(raw)) {
            case "text", "text_to_video", "texttovideo" -> "textToVideo";
            case "first_frame", "firstframe", "image_to_video", "imagetovideo" -> "imageToVideo";
            case "first_last_frame", "firstlastframe", "start_end_to_video", "startendtovideo" -> "startEndToVideo";
            case "reference", "reference_to_video", "referencetovideo" -> "referenceToVideo";
            case "edit", "extend", "video_to_video", "videotovideo" -> "videoToVideo";
            case "multi_frame", "multiframe" -> "multiFrame";
            default -> raw.trim();
        };
    }

    private static boolean sameScene(String first, String second) {
        return first != null && second != null
                && first.replace("_", "").replace("-", "").equalsIgnoreCase(
                second.replace("_", "").replace("-", ""));
    }

    private static String firstText(Map<String, Object> options, String... keys) {
        if (options == null) return null;
        for (String key : keys) {
            Object value = options.get(key);
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) return String.valueOf(value).trim();
        }
        return null;
    }

    private static void addUrls(Set<String> target, Object raw) {
        if (raw instanceof List<?> list) list.forEach(item -> addUrl(target, item));
        else addUrl(target, raw);
    }

    private static void addUrl(Set<String> target, Object raw) {
        if (raw == null) return;
        if (raw instanceof Map<?, ?> map) {
            for (String key : new String[]{"key_image", "keyImage", "image_url", "imageUrl", "url"}) {
                if (map.containsKey(key)) {
                    addUrl(target, map.get(key));
                    return;
                }
            }
            return;
        }
        String value = String.valueOf(raw).trim();
        if (StrUtil.isNotBlank(value)) target.add(value);
    }

    private static String normalizeMode(String value) {
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private record InputFacts(boolean firstFrame, boolean lastFrame, int referenceImageCount,
                              int referenceVideoCount, int referenceAudioCount) {
        boolean hasImage() {
            return firstFrame || lastFrame || referenceImageCount > 0;
        }

        boolean hasVideo() {
            return referenceVideoCount > 0;
        }

        boolean hasAudio() {
            return referenceAudioCount > 0;
        }
    }

    public record ResolvedVideoScene(String capabilityScene, String billingGenerateMode) {
    }
}
