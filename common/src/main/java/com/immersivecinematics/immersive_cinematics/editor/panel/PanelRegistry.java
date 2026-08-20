package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.immersivecinematics.immersive_cinematics.editor.area.LeftPanelArea;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 面板注册表（0.3.5 第5轮 5A）。
 * <p>
 * LeftPanelArea.PanelMode → EditorPanel 工厂。新增模式/面板只需在此注册。
 */
public final class PanelRegistry {

    private static final Map<LeftPanelArea.PanelMode, Supplier<EditorPanel>> FACTORIES =
            new EnumMap<>(LeftPanelArea.PanelMode.class);

    static {
        register(LeftPanelArea.PanelMode.SCRIPT_LIST, ScriptListPanel::new);
        register(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES, ScriptPropertiesPanel::new);
        register(LeftPanelArea.PanelMode.CLIP_PROPERTIES, ClipPropertiesPanel::new);
        register(LeftPanelArea.PanelMode.KEYFRAME_PROPERTIES, KeyframePropertiesPanel::new);
        register(LeftPanelArea.PanelMode.TRACK_LIST, TrackListPanel::new);
        register(LeftPanelArea.PanelMode.TRIGGER, TriggerPanel::new);
        register(LeftPanelArea.PanelMode.PRESET, PresetPanel::new);
    }

    private PanelRegistry() {}

    public static void register(LeftPanelArea.PanelMode mode, Supplier<EditorPanel> factory) {
        FACTORIES.put(mode, factory);
    }

    public static EditorPanel create(LeftPanelArea.PanelMode mode) {
        Supplier<EditorPanel> factory = FACTORIES.get(mode);
        if (factory == null) {
            throw new IllegalArgumentException("No EditorPanel registered for mode " + mode);
        }
        return factory.get();
    }
}
