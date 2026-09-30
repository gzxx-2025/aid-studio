package com.aid.model.definition;

/** 分镜多参和首尾帧使用独立接口，不能把首尾帧能力作为多参池的业务能力。 */
final class StoryboardVideoPoolCapabilityPolicy {
    private StoryboardVideoPoolCapabilityPolicy() { }

    static boolean rejects(String funcCode, String generateMode) {
        return ("main_storyboard_video".equals(funcCode)
                || "main_storyboard_video_multi_pro".equals(funcCode))
                && "start_end_to_video".equals(generateMode);
    }
}
