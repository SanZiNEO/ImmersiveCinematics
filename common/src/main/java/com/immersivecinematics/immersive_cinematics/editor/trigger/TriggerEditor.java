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
            case "entity_interact", "block_interact" -> new SingleIdEditor("target");
            case "item_on_interact" -> new ItemOnInteractEditor();
            case "item_craft" -> new SingleIdEditor("item");
            case "item_use" -> new SingleIdEditor("item");
            case "item_consume", "item_release", "item_instant_use",
                 "item_use_interrupt", "item_pickup", "item_drop" -> new SingleIdEditor("item");
            case "xp" -> new XpEditor();
            case "observation" -> new ObservationEditor();
            case "gamestage" -> new SingleIdEditor("stage");
            case "structure" -> new StructureEditor();
            case "entity_kill" -> new EntityKillEditor();
            case "inventory" -> new InventoryEditor();
            case "location" -> new LocationEditor();
            default -> new NoConditionEditor();
        };
    }
}
