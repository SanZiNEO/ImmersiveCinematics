package com.immersivecinematics.immersive_cinematics.editor.trigger;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import java.util.List;

public abstract class TriggerEditor {
    protected JsonObject conditions;

    public void setConditions(JsonObject c) { this.conditions = c; }

    public abstract int build(List<UIComponent> widgets, int x, int y, int w, Runnable onDirty);

    public static TriggerEditor create(String type) {
        return switch (type) {
            case "login", "command" -> new NoConditionEditor();
            case "advancement" -> new SingleIdEditor("advancement");
            case "biome" -> new SingleIdEditor("biome");
            case "dimension", "dimension_change" -> new SingleIdEditor("dimension");
            case "interact" -> new SingleIdEditor("target");
            case "item_craft" -> new SingleIdEditor("item");
            case "item_use" -> new SingleIdEditor("item");
            case "gamestage" -> new SingleIdEditor("stage");
            case "custom" -> new SingleIdEditor("event_id");
            case "structure" -> new StructureEditor();
            case "entity_kill" -> new EntityKillEditor();
            case "inventory" -> new InventoryEditor();
            case "location" -> new LocationEditor();
            default -> new NoConditionEditor();
        };
    }
}
