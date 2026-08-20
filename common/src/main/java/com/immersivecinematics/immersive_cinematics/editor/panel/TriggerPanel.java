package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.trigger.TriggerEditor;
import com.immersivecinematics.immersive_cinematics.editor.widget.UILabel;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIDropdown;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIFloatInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UITextInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 触发器面板（0.3.5 第5轮 5A）。
 * <p>
 * 从 editor/trigger/TriggerPanel 迁入 panel 包并改为 EditorPanel 子类。
 * 数据来自 PanelContext.script.triggers；高度由 EditorPanel.build() 模板回写。
 */
public class TriggerPanel extends EditorPanel {

    private JsonArray triggers;
    private int selectedIndex = -1;
    private TriggerEditor editor;
    private Runnable onDirty;
    private Runnable onTriggerChanged;

    public void setOnTriggerChanged(Runnable r) {
        onTriggerChanged = r;
    }

    private static final List<String> TYPE_LIST = List.of(
        "login", "location", "advancement", "biome", "entity_kill",
        "entity_interact", "block_interact", "item_on_interact",
        "dimension_change", "dimension", "item_craft", "item_use",
        "item_consume", "item_release", "item_instant_use", "item_use_interrupt",
        "item_pickup", "item_drop", "xp", "observation",
        "inventory", "structure", "gamestage"
    );

    @Override
    protected void buildContent() {
        if (ctx == null || ctx.script == null) return;

        JsonArray arr = ctx.script.has("triggers") ? ctx.script.getAsJsonArray("triggers") : new JsonArray();
        if (!ctx.script.has("triggers")) ctx.script.add("triggers", arr);
        this.triggers = arr;
        this.onDirty = ctx.onDirty;

        if (selectedIndex < 0 && triggers.size() > 0) {
            selectedIndex = 0;
            JsonObject t = triggers.get(0).getAsJsonObject();
            JsonObject cond = t.has("conditions") ? t.getAsJsonObject("conditions") : new JsonObject();
            t.add("conditions", cond);
            editor = TriggerEditor.create(t.get("type").getAsString());
            editor.setConditions(cond);
        }

        int lx = x;
        int cy = y;

        if (selectedIndex < 0 || selectedIndex >= triggers.size()) {
            UIDropdown sel = new UIDropdown(lx, cy, w, 16, triggerOptions(),
                () -> 0, this::selectTrigger);
            sel.setHighlightIndex(-1);
            sel.setOnRightClick(this::deleteTrigger);
            sel.setMaxListHeight(150);
            addChild(sel);
            return;
        }

        JsonObject trigger = triggers.get(selectedIndex).getAsJsonObject();

        cy = y + 36;

        UITextInput idInput = new UITextInput(lx, cy, w, 16, I18n.get("editor.field.trigger_id"),
            () -> trigger.get("id").getAsString(),
            v -> { trigger.addProperty("id", v); if (onDirty != null) onDirty.run(); });
        addChild(idInput);
        cy += 18;

        UIToggle repeatToggle = new UIToggle(lx, cy, w, 16, I18n.get("editor.field.repeatable"),
            () -> trigger.has("repeatable") && trigger.get("repeatable").getAsBoolean(),
            v -> { trigger.addProperty("repeatable", v); if (onDirty != null) onDirty.run(); });
        addChild(repeatToggle);
        cy += 18;

        UIToggle onEnterToggle = new UIToggle(lx, cy, w, 16, I18n.get("editor.field.on_enter"),
            () -> trigger.has("on_enter") && trigger.get("on_enter").getAsBoolean(),
            v -> { trigger.addProperty("on_enter", v); if (onDirty != null) onDirty.run(); });
        addChild(onEnterToggle);
        cy += 18;

        UIFloatInput exitBufferInput = new UIFloatInput(lx, cy, w, 16, I18n.get("editor.field.exit_buffer"),
            () -> trigger.has("exit_buffer") ? trigger.get("exit_buffer").getAsFloat() : 0,
            0, 9999, 1f,
            v -> { trigger.addProperty("exit_buffer", v); if (onDirty != null) onDirty.run(); });
        addChild(exitBufferInput);
        cy += 18;

        UIFloatInput delayInput = new UIFloatInput(lx, cy, w, 16, I18n.get("editor.field.delay"),
            () -> trigger.has("delay") ? trigger.get("delay").getAsFloat() : 0,
            0, 9999, 0.5f,
            v -> { trigger.addProperty("delay", v); if (onDirty != null) onDirty.run(); });
        addChild(delayInput);
        cy += 20;

        UILabel condLabel = new UILabel(lx, cy, I18n.get("editor.section.conditions"), EditorTheme.TEXT_DIM);
        addChild(condLabel);
        cy += 12;

        if (editor != null) {
            editor.build(getChildren(), lx, cy, w, onDirty != null ? onDirty : () -> {});
        }

        int contentBottom = y;
        for (UIComponent wc : getChildren()) {
            contentBottom = Math.max(contentBottom, wc.y + wc.h);
        }
        int effectiveH = Math.max(1, contentBottom - y + 4 + 16 + 16);

        int curTypeIdx = TYPE_LIST.indexOf(trigger.get("type").getAsString());
        int typeIdx = curTypeIdx < 0 ? 0 : curTypeIdx;

        List<String> typeNames = TYPE_LIST.stream().map(t -> I18n.get("editor.trigger.type." + t)).collect(Collectors.toList());
        UIDropdown typeDD = new UIDropdown(lx, y + 18, w, 16, typeNames,
            () -> typeIdx,
            i -> {
                String newType = TYPE_LIST.get(i);
                if (newType.equals(trigger.get("type").getAsString())) return;
                trigger.addProperty("type", newType);
                JsonObject newCond = new JsonObject();
                trigger.add("conditions", newCond);
                editor = TriggerEditor.create(newType);
                editor.setConditions(newCond);
                if (onDirty != null) onDirty.run();
                build();
                if (onTriggerChanged != null) onTriggerChanged.run();
            });
        typeDD.setHighlightIndex(typeIdx);
        typeDD.setMaxListHeight(Math.max(0, effectiveH - 34));
        addChild(typeDD);

        UIDropdown sel = new UIDropdown(lx, y, w, 16, triggerOptions(),
            () -> Math.min(selectedIndex, triggers.size()),
            i -> { selectTrigger(i); if (onTriggerChanged != null) onTriggerChanged.run(); });
        sel.setHighlightIndex(selectedIndex);
        sel.setOnRightClick(i -> { deleteTrigger(i); if (onTriggerChanged != null) onTriggerChanged.run(); });
        sel.setMaxListHeight(Math.max(0, effectiveH - 16));
        addChild(sel);
    }

    private List<String> triggerOptions() {
        List<String> opts = new ArrayList<>();
        if (triggers != null) {
            for (int i = 0; i < triggers.size(); i++) {
                JsonObject t = triggers.get(i).getAsJsonObject();
                opts.add(t.get("id").getAsString() + " (" + t.get("type").getAsString() + ")");
            }
        }
        opts.add(I18n.get("editor.label.trigger_add"));
        return opts;
    }

    private void selectTrigger(int index) {
        if (index >= (triggers != null ? triggers.size() : 0)) {
            JsonObject t = new JsonObject();
            t.addProperty("id", "trigger_" + (triggers.size() + 1));
            t.addProperty("type", "login");
            t.addProperty("repeatable", false);
            t.addProperty("delay", 0);
            t.add("conditions", new JsonObject());
            triggers.add(t);
            selectedIndex = triggers.size() - 1;
        } else {
            selectedIndex = index;
        }

        JsonObject t = triggers.get(selectedIndex).getAsJsonObject();
        JsonObject cond = t.has("conditions") ? t.getAsJsonObject("conditions") : new JsonObject();
        t.add("conditions", cond);
        editor = TriggerEditor.create(t.get("type").getAsString());
        editor.setConditions(cond);
        build();
        if (onDirty != null) onDirty.run();
    }

    private void deleteTrigger(int index) {
        if (index < 0 || index >= triggers.size()) return;
        triggers.remove(index);
        if (selectedIndex >= triggers.size()) selectedIndex = triggers.size() - 1;
        if (selectedIndex >= 0) {
            JsonObject t = triggers.get(selectedIndex).getAsJsonObject();
            JsonObject cond = t.has("conditions") ? t.getAsJsonObject("conditions") : new JsonObject();
            t.add("conditions", cond);
            editor = TriggerEditor.create(t.get("type").getAsString());
            editor.setConditions(cond);
        } else {
            editor = null;
        }
        if (onDirty != null) onDirty.run();
        build();
    }
}
