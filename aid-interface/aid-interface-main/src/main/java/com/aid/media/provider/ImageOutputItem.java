package com.aid.media.provider;

import lombok.Data;

import java.util.Map;

/** One ordered image output and its provider-supplied placement metadata. */
@Data
public class ImageOutputItem {
    private String url;
    private Integer width;
    private Integer height;
    private Integer zIndex;
    private String name;
    private String description;
    private String outputFormat;
    private Map<String, Object> boundingBox;
}
