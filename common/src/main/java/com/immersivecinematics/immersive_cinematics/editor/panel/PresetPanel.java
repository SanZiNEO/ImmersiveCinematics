package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.preset.Preset;
import com.immersivecinematics.immersive_cinematics.editor.preset.PresetParam;
import com.immersivecinematics.immersive_cinematics.editor.preset.PresetRegistry;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIButton;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIDropdown;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIFloatInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UILabel;
import com.immersivecinematics.immersive_cinematics.editor.widget.UITextInput;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 预设面板（0.3.5 第5轮 5D）。
 * <p>
 * 选择预设 → 填参数 → 生成完整脚本 JSON（可继续在编辑器中编辑）。
 */
public class PresetPanel extends EditorPanel {

    private Preset selectedPreset;
    private JsonObject paramValues;

    @Override
    protected void buildContent() {
        List<Preset> presets = PresetRegistry.getAll();
        if (presets.isEmpty()) return;
        if (selectedPreset == null) {
            selectedPreset = presets.get(0);
            initParams(selectedPreset);
        }

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.presets"), lx, cy, 0);
        cy += 16;

        List<String> presetNames = new ArrayList<>();
        for (Preset preset : presets) presetNames.add(preset.getName());
        UIDropdown presetDD = new UIDropdown(lx, cy, w - 12, 16, presetNames,
                () -> presets.indexOf(selectedPreset),
                idx -> {
                    if (idx >= 0 && idx < presets.size()) {
                        selectedPreset = presets.get(idx);
                        initParams(selectedPreset);
                        requestRebuild();
                    }
                });
        presetDD.setLabel(I18n.get("editor.preset.select"));
        addChild(presetDD);
        cy += 18;

        if (selectedPreset.getDescription() != null && !selectedPreset.getDescription().isEmpty()) {
            addSectionLabel(selectedPreset.getDescription(), lx, cy, 0);
            cy += 14;
        }

        for (PresetParam param : selectedPreset.getParams()) {
            if ("number".equals(param.type())) {
                cy = addFloatField(param.label(), () -> {
                    return paramValues.has(param.key()) ? paramValues.get(param.key()).getAsFloat() : 0f;
                }, lx, cy, param.min(), param.max(), 0.5f, v -> {
                    paramValues.addProperty(param.key(), v);
                    markDirty();
                }, w - 12);
            } else if ("text".equals(param.type())) {
                UITextInput ti = new UITextInput(lx, cy, w - 12, 16, param.label(),
                        () -> paramValues.has(param.key()) ? paramValues.get(param.key()).getAsString() : "",
                        v -> {
                            paramValues.addProperty(param.key(), v);
                            markDirty();
                        });
                addChild(ti);
                cy += 18;
            }
        }

        cy += 8;
        UIButton generate = new UIButton(lx, cy, w - 12, 20, I18n.get("editor.preset.generate"), b -> {
            if (ctx != null && ctx.onPresetGenerated != null && selectedPreset != null) {
                ctx.onPresetGenerated.accept(selectedPreset.generate(paramValues));
            }
        });
        generate.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_SECONDARY);
        addChild(generate);
    }

    private void initParams(Preset preset) {
        paramValues = new JsonObject();
        for (PresetParam param : preset.getParams()) {
            if ("number".equals(param.type())) {
                Number num = param.defaultValue() instanceof Number ? (Number) param.defaultValue() : 0f;
                paramValues.addProperty(param.key(), num.floatValue());
            } else {
                paramValues.addProperty(param.key(), String.valueOf(param.defaultValue()));
            }
        }
    }
}
