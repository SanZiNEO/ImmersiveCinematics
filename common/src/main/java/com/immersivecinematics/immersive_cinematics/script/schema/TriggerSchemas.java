package com.immersivecinematics.immersive_cinematics.script.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 触发器 conditions 字段的 Java 元数据（0.3.5 WebUI Editor）。
 * <p>
 * 与 TrackSchemas / MetaSchemas 同构：每种触发器类型对应一个 conditions 字段 map，
 * 由 SchemaExporter 导出给 WebUI 动态生成表单。Java 侧是唯一权威。
 * <p>
 * 字段定义参考 docs/TRIGGER_TYPES.md。
 */
public final class TriggerSchemas {

    private TriggerSchemas() {}

    /** 所有触发器类型 id 列表（顺序即编辑器下拉顺序） */
    public static List<String> typeList() {
        return List.of(
                "login",
                "location",
                "advancement",
                "biome",
                "entity_kill",
                "entity_interact",
                "block_interact",
                "item_on_interact",
                "dimension_change",
                "item_craft",
                "item_use",
                "item_consume",
                "item_release",
                "item_instant_use",
                "item_use_interrupt",
                "item_pickup",
                "item_drop",
                "xp",
                "dimension",
                "observation",
                "inventory",
                "structure",
                "gamestage"
        );
    }

    /** 所有触发器的 conditions schema */
    public static Map<String, Map<String, FieldDef>> all() {
        Map<String, Map<String, FieldDef>> map = new LinkedHashMap<>();
        map.put("login", login());
        map.put("location", location());
        map.put("advancement", advancement());
        map.put("biome", biome());
        map.put("entity_kill", entityKill());
        map.put("entity_interact", entityInteract());
        map.put("block_interact", blockInteract());
        map.put("item_on_interact", itemOnInteract());
        map.put("dimension_change", dimensionChange());
        map.put("item_craft", itemCraft());
        map.put("item_use", itemUse());
        map.put("item_consume", itemConsume());
        map.put("item_release", itemRelease());
        map.put("item_instant_use", itemInstantUse());
        map.put("item_use_interrupt", itemUseInterrupt());
        map.put("item_pickup", itemPickup());
        map.put("item_drop", itemDrop());
        map.put("xp", xp());
        map.put("dimension", dimension());
        map.put("observation", observation());
        map.put("inventory", inventory());
        map.put("structure", structure());
        map.put("gamestage", gamestage());
        return map;
    }

    // ── 各触发器 conditions 定义 ─────────────────────────────────

    private static Map<String, FieldDef> login() {
        return new LinkedHashMap<>();
    }

    private static Map<String, FieldDef> location() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("dimension", new FieldDef("string", ""));
        m.put("position", new FieldDef("map", null));
        m.put("radius", new FieldDef("float", 0f));
        m.put("corner1", new FieldDef("map", null));
        m.put("corner2", new FieldDef("map", null));
        return m;
    }

    private static Map<String, FieldDef> advancement() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("advancement", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> biome() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("biome", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> entityKill() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("entity", new FieldDef("string", null, true));
        m.put("mode", new FieldDef("enum", "or", false, List.of("or", "and")));
        m.put("dimension", new FieldDef("string", ""));
        m.put("biome", new FieldDef("string", ""));
        m.put("position", new FieldDef("map", null));
        m.put("radius", new FieldDef("float", 0f));
        m.put("corner1", new FieldDef("map", null));
        m.put("corner2", new FieldDef("map", null));
        return m;
    }

    private static Map<String, FieldDef> entityInteract() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("target", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> blockInteract() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("target", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemOnInteract() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        m.put("target", new FieldDef("string", null, true));
        m.put("target_type", new FieldDef("enum", "", false, List.of("", "block", "entity")));
        return m;
    }

    private static Map<String, FieldDef> dimensionChange() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("dimension", new FieldDef("string", null, true));
        m.put("from_dimension", new FieldDef("string", ""));
        return m;
    }

    private static Map<String, FieldDef> itemCraft() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemUse() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemConsume() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemRelease() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemInstantUse() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemUseInterrupt() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemPickup() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> itemDrop() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("item", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> xp() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("level", new FieldDef("int", null));
        m.put("total", new FieldDef("int", null));
        return m;
    }

    private static Map<String, FieldDef> dimension() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("dimension", new FieldDef("string", null, true));
        return m;
    }

    private static Map<String, FieldDef> observation() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("target", new FieldDef("string", null, true));
        m.put("target_type", new FieldDef("enum", "", false, List.of("", "block", "entity")));
        m.put("reach", new FieldDef("float", 4.5f));
        return m;
    }

    private static Map<String, FieldDef> inventory() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("items", new FieldDef("map", null, true));
        m.put("mode", new FieldDef("enum", "and", false, List.of("and", "or")));
        m.put("change", new FieldDef("enum", "", false, List.of("", "increase", "decrease")));
        return m;
    }

    private static Map<String, FieldDef> structure() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("structure", new FieldDef("string", null, true));
        m.put("radius", new FieldDef("int", 0));
        return m;
    }

    private static Map<String, FieldDef> gamestage() {
        Map<String, FieldDef> m = new LinkedHashMap<>();
        m.put("stage", new FieldDef("string", null, true));
        return m;
    }
}
