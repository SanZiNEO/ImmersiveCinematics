package com.immersivecinematics.immersive_cinematics.editor.preset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 预设注册表（0.3.5 第5轮 5D）。
 * <p>
 * 内置预设在此注册；新增预设只需添加 Preset 实例。
 */
public final class PresetRegistry {

    private static final List<Preset> PRESETS = new ArrayList<>();

    static {
        register(OrbitCirclePreset.create());
    }

    private PresetRegistry() {}

    public static void register(Preset preset) {
        PRESETS.add(preset);
    }

    public static List<Preset> getAll() {
        return Collections.unmodifiableList(PRESETS);
    }

    public static Preset get(String id) {
        for (Preset preset : PRESETS) {
            if (preset.getId().equals(id)) return preset;
        }
        return null;
    }
}
