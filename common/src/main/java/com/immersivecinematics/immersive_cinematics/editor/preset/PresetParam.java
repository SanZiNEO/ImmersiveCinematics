package com.immersivecinematics.immersive_cinematics.editor.preset;

/**
 * 预设参数定义（0.3.5 第5轮 5D）。
 */
public record PresetParam(String key, String label, String type, Object defaultValue, float min, float max) {
}
