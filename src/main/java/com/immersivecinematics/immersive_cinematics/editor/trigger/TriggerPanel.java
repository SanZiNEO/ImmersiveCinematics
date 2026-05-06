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

    private void rebuild() {
        widgets.clear();
        if (triggers == null) return;

        int lx = x;
        int cy = y;

        UIDropdown sel = new UIDropdown(lx, cy, w, 16, triggerOptions(),
            () -> Math.min(selectedIndex, triggers.size()),
            this::selectTrigger);
        widgets.add(sel);

        if (selectedIndex < 0 || selectedIndex >= triggers.size()) return;
        cy += 20;

        JsonObject trigger = triggers.get(selectedIndex).getAsJsonObject();

        UITextInput idInput = new UITextInput(lx, cy, w, 16, "id",
            () -> trigger.get("id").getAsString(),
            v -> { trigger.addProperty("id", v); if (onDirty != null) onDirty.run(); });
        widgets.add(idInput);
        cy += 18;

        UIDropdown typeDD = new UIDropdown(lx, cy, w, 16, TYPE_LIST,
            () -> {
                int idx = TYPE_LIST.indexOf(trigger.get("type").getAsString());
                return idx >= 0 ? idx : 0;
            },
            i -> {
                String newType = TYPE_LIST.get(i);
                String oldType = trigger.get("type").getAsString();
                if (newType.equals(oldType)) return;
                trigger.addProperty("type", newType);
                JsonObject newCond = new JsonObject();
                trigger.add("conditions", newCond);
                editor = TriggerEditor.create(newType);
                editor.setConditions(newCond);
                if (onDirty != null) onDirty.run();
                rebuild();
            });
        widgets.add(typeDD);
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
    }

    @Override
    protected List<UIComponent> getChildren() { return widgets; }

    @Override
    public void render(UIContext ctx) {
        for (UIComponent w : widgets) w.render(ctx);
    }
}
