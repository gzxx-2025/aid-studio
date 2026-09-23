package com.aid.model.definition;

import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 为模型管理提供保留现有配置的官方图片能力补齐预览。 */
@Service
@RequiredArgsConstructor
public class OfficialImageCapabilityTemplateRegistry {
    private static final String WAN_DOC = "https://help.aliyun.com/zh/model-studio/wanx-image-edit-api-reference";
    private static final String GPT_DOC = "https://developers.openai.com/api/docs/guides/image-generation";
    private final ModelDefinitionService definitions;

    public Preview preview(Long modelId) {
        AidAiModel model = definitions.detail(modelId);
        if (model == null || !"0".equals(model.getDelFlag())) throw new ServiceException("模型不存在");
        String upstream = model.getRealModelCode();
        if (upstream == null || upstream.isBlank()) upstream = model.getModelCode();
        boolean wan = "wanx2.1-imageedit".equals(upstream);
        boolean gpt = Set.of("gpt-image-2.5-flare", "gpt-image-2.5-sunburst").contains(upstream);
        List<ModelCapabilityDefinition> current = JSON.parseArray(JSON.toJSONString(model.getCapabilities()), ModelCapabilityDefinition.class);
        if (current == null) current = new ArrayList<>();
        if (!wan && !gpt) return new Preview(false, modelId, current, List.of(), List.of());
        if (current.isEmpty()) current = LegacyModelDefinitionConverter.convert(model);
        ModelCapabilityDefinition base = current.stream().filter(c -> Boolean.TRUE.equals(c.getDefaultCapability()))
                .findFirst().orElse(current.get(0));
        List<String> added = new ArrayList<>();
        Map<String, String> catalog = new LinkedHashMap<>();
        if (gpt) { catalog.put("text_to_image", "文生图"); catalog.put("image_to_image", "图生图"); }
        catalog.put("image_edit", "图片编辑");
        catalog.put("image_inpainting", "区域编辑");
        catalog.put("image_outpainting", "扩图");
        if (wan) {
            catalog.put("image_upscale", "图片超分");
            catalog.put("image_stylization", "全局风格化");
            catalog.put("image_local_stylization", "局部风格化");
            catalog.put("image_text_removal", "去文字水印");
            catalog.put("image_colorization", "图像上色");
            catalog.put("image_doodle", "线稿生图");
            catalog.put("image_cartoon_reference", "参考卡通形象");
        }
        String source = wan ? WAN_DOC : GPT_DOC;
        for (var entry : catalog.entrySet()) {
            String code = entry.getKey();
            ModelCapabilityDefinition existing = current.stream().filter(c -> code.equals(c.getCode())).findFirst().orElse(null);
            List<ModelParameter> fields = parameters(code, wan);
            if (existing != null) {
                existing.setParameters(mergeMissing(existing.getParameters(), fields));
                continue;
            }
            ModelCapabilityDefinition item = JSON.parseObject(JSON.toJSONString(base), ModelCapabilityDefinition.class);
            item.setCode(code); item.setLabel(entry.getValue());
            item.setGenerateMode(Set.of("text_to_image", "image_to_image", "image_upscale").contains(code) ? code : "image_edit");
            item.setDefaultCapability(false); item.setEnabled(false);
            item.setEvidenceStatus("PENDING_VALIDATION"); item.setSourceUrls(List.of(source));
            item.setParameters(fields); item.setRules(new ArrayList<>());
            Map<String, Object> presentation = item.getPresentation() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item.getPresentation());
            presentation.put("supportsImageInput", !"text_to_image".equals(code));
            presentation.put("supportsMultiImageInput", gpt && !"text_to_image".equals(code));
            presentation.put("supportsTextInput", true);
            item.setPresentation(presentation);
            for (ModelProtocolBinding route : item.getBindings()) {
                Map<String, Object> capability = route.getCapability() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(route.getCapability());
                capability.remove("sceneRules");
                capability.put("requiredInputs", "text_to_image".equals(code) ? List.of("text") : List.of("text", "image"));
                capability.put("allowedInputs", "text_to_image".equals(code) ? List.of("text") : List.of("text", "image"));
                capability.put("minReferenceImages", "text_to_image".equals(code) ? 0 : 1);
                capability.put("maxReferenceImages", "text_to_image".equals(code) ? 0 : gpt ? 16 : 1);
                if (wan) capability.put("wanxFunction", wanFunction(code));
                route.setCapability(capability);
                route.setPresentation(presentation);
            }
            current.add(item); added.add(code);
        }
        return new Preview(true, modelId, current, added, List.of(source));
    }

    private static List<ModelParameter> parameters(String code, boolean wan) {
        List<ModelParameter> result = new ArrayList<>();
        ModelParameter prompt = field("prompt", "提示词", "string", null);
        prompt.setMaximum(BigDecimal.valueOf(wan ? 800 : 32000));
        result.add(prompt);
        if (!"text_to_image".equals(code)) result.add(material("referenceImageUrl", "原图", "reference_image", wan));
        result.add(number("expectedImageCount", "生成数量", 1, wan ? 4 : 10, 1));
        if ("image_inpainting".equals(code)) {
            result.add(material("maskImageUrl", "蒙版", "mask", wan));
            result.add(choice("maskEncoding", "蒙版选区语义", List.of("BLACK_WHITE", "ALPHA"), "BLACK_WHITE"));
        }
        if ("image_outpainting".equals(code)) {
            result.add(number("targetWidth", "目标宽度", 1, wan ? 12288 : 3840, null));
            result.add(number("targetHeight", "目标高度", 1, wan ? 12288 : 3840, null));
            result.add(number("sourceX", "原图左上角X", 0, wan ? 8192 : 3840, 0));
            result.add(number("sourceY", "原图左上角Y", 0, wan ? 8192 : 3840, 0));
        }
        if ("image_edit".equals(code)) {
            result.add(number("brightness", "灯光亮度", -100, 100, null));
            result.add(number("colorTemperature", "色温", 1000, 20000, null));
            result.add(number("keyLightAzimuth", "主光方位角", 0, 359, null));
            result.add(number("keyLightElevation", "主光仰角", -90, 90, null));
            result.add(field("rimLightEnabled", "轮廓光", "boolean", null));
            result.add(number("rimLightAzimuth", "轮廓光方位角", 0, 359, null));
            result.add(number("rimLightElevation", "轮廓光仰角", -90, 90, null));
        }
        List<ModelParameter> options = new ArrayList<>();
        if (wan) {
            List<ModelParameter> params = new ArrayList<>();
            params.add(number("seed", "随机种子", 0, Integer.MAX_VALUE, null));
            params.add(field("watermark", "输出水印", "boolean", false));
            if (Set.of("image_edit", "image_stylization").contains(code)) {
                ModelParameter strength = field("strength", "修改幅度", "number", new BigDecimal("0.5"));
                strength.setMinimum(BigDecimal.ZERO); strength.setMaximum(BigDecimal.ONE); params.add(strength);
            }
            if ("image_upscale".equals(code)) params.add(number("upscale_factor", "超分倍数", 1, 4, 1));
            if ("image_doodle".equals(code)) params.add(field("is_sketch", "输入已是线稿", "boolean", false));
            options.add(object("parameters", "万相参数", params));
        } else {
            if (!"text_to_image".equals(code)) {
                ModelParameter references = field("referenceImages", "参考图片", "array", null);
                references.setMaximum(BigDecimal.valueOf(16));
                references.setItems(material("image", "参考图", "reference_image", false));
                options.add(references);
            }
            ModelParameter size = field("size", "输出像素宽高", "string", "1024x1024");
            size.setDescription("WIDTHxHEIGHT：16倍数，边长不超过3840，比例1:3至3:1，总像素655360至8294400；扩图由目标尺寸决定");
            result.add(size);
            options.add(choice("quality", "质量", List.of("auto", "low", "medium", "high", "xhigh", "max"), "auto"));
            options.add(choice("output_format", "输出格式", List.of("png", "jpeg", "webp"), "png"));
            options.add(choice("background", "背景", List.of("auto", "opaque", "transparent"), "auto"));
            options.add(number("output_compression", "压缩质量", 0, 100, null));
            options.add(choice("moderation", "审核强度", List.of("auto", "low"), "auto"));
        }
        result.add(object("options", "模型参数", options));
        return result;
    }

    private static List<ModelParameter> mergeMissing(List<ModelParameter> current, List<ModelParameter> incoming) {
        List<ModelParameter> result = current == null ? new ArrayList<>() : new ArrayList<>(current);
        for (ModelParameter field : incoming) {
            ModelParameter old = result.stream().filter(f -> field.getName().equals(f.getName())).findFirst().orElse(null);
            if (old == null) { field.setDefaultValue(null); result.add(field); }
            else if ("object".equals(old.getType()) && "object".equals(field.getType())) old.setProperties(mergeMissing(old.getProperties(), field.getProperties()));
        }
        return result;
    }

    private static String wanFunction(String code) {
        return switch (code) {
            case "image_inpainting" -> "description_edit_with_mask";
            case "image_outpainting" -> "expand";
            case "image_upscale" -> "super_resolution";
            case "image_stylization" -> "stylization_all";
            case "image_local_stylization" -> "stylization_local";
            case "image_text_removal" -> "remove_watermark";
            case "image_colorization" -> "colorization";
            case "image_doodle" -> "doodle";
            case "image_cartoon_reference" -> "control_cartoon_feature";
            default -> "description_edit";
        };
    }

    private static ModelParameter material(String name, String label, String role, boolean wan) {
        ModelParameter p = field(name, label, "string", null); p.setMaterialRole(role);
        p.setFormats(List.of("png", "jpeg", "jpg", "webp")); p.setMaxFileSizeBytes((wan ? 10L : 50L) * 1024 * 1024);
        return p;
    }
    private static ModelParameter field(String name, String label, String type, Object value) {
        ModelParameter p = new ModelParameter(); p.setName(name); p.setLabel(label); p.setType(type); p.setDefaultValue(value); return p;
    }
    private static ModelParameter number(String name, String label, long min, long max, Object value) {
        ModelParameter p = field(name, label, "integer", value); p.setMinimum(BigDecimal.valueOf(min)); p.setMaximum(BigDecimal.valueOf(max)); return p;
    }
    private static ModelParameter choice(String name, String label, List<?> values, Object value) {
        ModelParameter p = field(name, label, "string", value); p.setChoices(new ArrayList<>(values)); return p;
    }
    private static ModelParameter object(String name, String label, List<ModelParameter> fields) {
        ModelParameter p = field(name, label, "object", null); p.setProperties(fields); return p;
    }
    public record Preview(boolean supported, Long modelId, List<ModelCapabilityDefinition> capabilities,
                          List<String> addedCapabilityCodes, List<String> sourceUrls) { }
}
