package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.*;

public class TriggerPanel extends UIComponent {
    private final List<UIComponent> widgets = new ArrayList<>();
    private JsonArray triggers;
    private int selectedIndex = -1;
    private TriggerEditor editor;
    private Runnable onDirty;

    private static final List<String> TYPE_LIST = List.of(
        "login", "location", "advancement", "biome", "entity_kill",
        "interact", "dimension", "item_craft", "item_use", "inventory",
        "custom", "command", "structure", "gamestage"
    );

    public TriggerPanel(int x, int y, int w, int h, JsonArray triggers, Runnable onDirty) {
        super(x, y, w, h);
        this.triggers = triggers;
        this.onDirty = onDirty;
        if (triggers != null && triggers.size() > 0) {
            selectTrigger(0);
        } else {
            rebuild();
        }
    }

    private List<String> triggerOptions() {
        List<String> opts = new ArrayList<>();
        if (triggers != null) {
            for (int i = 0; i < triggers.size(); i++) {
                JsonObject t = triggers.get(i).getAsJsonObject();
                opts.add(t.get("id").getAsString() + " (" + t.get("type").getAsString() + ")");
            }
        }
        opts.add("[+ Add]");
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
        rebuild();
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
        rebuild();
    }

    private void rebuild() {
        widgets.clear();
        if (triggers == null) return;

        int lx = x;
        int cy = y;

        if (selectedIndex < 0 || selectedIndex >= triggers.size()) {
            UIDropdown sel = new UIDropdown(lx, cy, w, 16, triggerOptions(),
                () -> 0, this::selectTrigger);
            sel.setHighlightIndex(-1);
            sel.setOnRightClick(this::deleteTrigger);
            sel.setMaxListHeight(h - 16);
            widgets.add(sel);
            return;
        }

        JsonObject trigger = triggers.get(selectedIndex).getAsJsonObject();

        // Non-dropdown widgets start below both dropdowns
        cy = y + 36;

        UITextInput idInput = new UITextInput(lx, cy, w, 16, "id",
            () -> trigger.get("id").getAsString(),
            v -> { trigger.addProperty("id", v); if (onDirty != null) onDirty.run(); });
        widgets.add(idInput);
        cy += 18;

        UIToggle repeatToggle = new UIToggle(lx, cy, w, 16, "repeatable",
            () -> trigger.has("repeatable") && trigger.get("repeatable").getAsBoolean(),
            v -> { trigger.addProperty("repeatable", v); if (onDirty != null) onDirty.run(); });
        widgets.add(repeatToggle);
        cy += 18;

        UIFloatInput delayInput = new UIFloatInput(lx, cy, w, 16, "delay",
            () -> trigger.has("delay") ? trigger.get("delay").getAsFloat() : 0,
            0, 9999, 0.5f,
            v -> { trigger.addProperty("delay", v); if (onDirty != null) onDirty.run(); });
        widgets.add(delayInput);
        cy += 20;

        UILabel condLabel = new UILabel(lx, cy, "Conditions", 0xFF777777);
        widgets.add(condLabel);
        cy += 12;

        if (editor != null) {
            editor.build(widgets, lx, cy, w, onDirty != null ? onDirty : () -> {});
        }

        // Dropdowns last (higher z-order – rendered/mouse-tested on top)
        int curTypeIdx = TYPE_LIST.indexOf(trigger.get("type").getAsString());
        int typeIdx = curTypeIdx < 0 ? 0 : curTypeIdx;

        UIDropdown typeDD = new UIDropdown(lx, y + 18, w, 16, TYPE_LIST,
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
                rebuild();
            });
        typeDD.setHighlightIndex(typeIdx);
        typeDD.setMaxListHeight(Math.max(0, h - 34));
        widgets.add(typeDD);

        UIDropdown sel = new UIDropdown(lx, y, w, 16, triggerOptions(),
            () -> Math.min(selectedIndex, triggers.size()),
            this::selectTrigger);
        sel.setHighlightIndex(selectedIndex);
        sel.setOnRightClick(this::deleteTrigger);
        sel.setMaxListHeight(Math.max(0, h - 16));
        widgets.add(sel);
    }

    @Override
    public List<UIComponent> getChildren() { return widgets; }

    @Override
    public void render(UIContext ctx) {
        for (UIComponent w : widgets) w.render(ctx);
    }
}
