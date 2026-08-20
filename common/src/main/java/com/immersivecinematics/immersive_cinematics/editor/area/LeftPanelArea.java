package com.immersivecinematics.immersive_cinematics.editor.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.script.SchemaLoader;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.editor.trigger.TriggerPanel;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import com.immersivecinematics.immersive_cinematics.editor.widget.IFocusable;
import net.minecraft.client.resources.language.I18n;
import java.util.*;
import java.util.function.Consumer;


public class LeftPanelArea extends UIComponent {

    public enum PanelMode { SCRIPT_LIST, SCRIPT_PROPERTIES, CLIP_PROPERTIES, KEYFRAME_PROPERTIES, TRACK_LIST, TRIGGER }
    private PanelMode mode = PanelMode.SCRIPT_PROPERTIES;

    private JsonObject script;
    private List<String> scriptFileNames = new ArrayList<>();
    private JsonObject selectedClip;
    private JsonObject selectedKeyframe;
    private float totalDuration;
    private String selectedTrackType = "CAMERA";
    private JsonArray tracks;
    private static final int TAB_HEIGHT = 20;
    private boolean dataDirty = true;
    private long lastBuildTime;

    private Consumer<String> onOpenScript;
    private Consumer<String> onDeleteScript;
    private Runnable onNewScript;
    private Consumer<String> onNameChanged;
    private Consumer<String> onAuthorChanged;
    private Consumer<Integer> onTrackSelected;
    private Consumer<String> onTrackAdd;
    private Consumer<Integer> onTrackDelete;

    
    private Consumer<String> onDescChanged;
    private Consumer<String> onBehaviorFlag;
    private Runnable onDirty;
    private Consumer<JsonObject> onToggleTrackVisible;

    public LeftPanelArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.LEFT, "full_area", x, y, w, h);
    }

    public void setMode(PanelMode m) {
        if (this.mode != m || dataDirty) {
            dataDirty = false;
            if (this.mode != m) {
                EditorLogger.areaMode(EditorLogger.LEFT, "mode", this.mode.name(), m.name());
                // 切模式回到面板顶部：scrollY 残留会把新面板内容/固定 tab 滚出可视区（面板"全白"根因）
                if (content != null) content.resetScroll();
            }
            this.mode = m;
            build();
        }
    }
    public PanelMode getMode() { return mode; }

    public void setDirtyCallback(Runnable r) { onDirty = r; }

    public void setData(JsonObject meta, JsonObject clip, JsonObject kf) {
        this.script = meta;
        this.selectedClip = clip;
        this.selectedKeyframe = kf;
        this.dataDirty = true;
    }

    public void setTotalDuration(float d) { totalDuration = d; }
    public void setSelectedTrackType(String t) { selectedTrackType = t; }
    public void setScriptFileNames(List<String> names) { scriptFileNames = names; }
    public void setOnOpenScript(Consumer<String> r) { onOpenScript = r; }
    public void setOnTrackAdd(Consumer<String> r) { onTrackAdd = r; }
    public void setOnTrackDelete(Consumer<Integer> r) { onTrackDelete = r; }

    public void setOnDeleteScript(Consumer<String> r) { onDeleteScript = r; }
    public void setOnNewScript(Runnable r) { onNewScript = r; }
    public void setOnNameChanged(Consumer<String> r) { onNameChanged = r; }
    public void setOnAuthorChanged(Consumer<String> r) { onAuthorChanged = r; }
    public void setOnDescChanged(Consumer<String> r) { onDescChanged = r; }
    public void setOnBehaviorFlag(Consumer<String> r) { onBehaviorFlag = r; }


    /** tab 栏独立组件（固定在面板顶部，不随内容滚动；渲染与命中均豁免滚动偏移） */
    private PanelTabBar tabBar;
    /** 可滚动内容容器（位于 Tab 栏下方，承载各模式 build 出的子组件） */
    private ScrollablePanel content;

    public void build() {
        EditorLogger.action(EditorLogger.LEFT, "BUILD", "mode=" + mode);
        clearChildren();
        tabBar = new PanelTabBar(x, y, w, TAB_HEIGHT, mode, this::setMode);
        tabBar.fixedToParent = true;
        addChild(tabBar);
        content = new ScrollablePanel(x, y + TAB_HEIGHT, w, h - TAB_HEIGHT);
        addChild(content);
        switch (mode) {
            case SCRIPT_LIST -> buildScriptList();
            case SCRIPT_PROPERTIES -> buildScriptProperties();
            case CLIP_PROPERTIES -> buildClipProperties();
            case KEYFRAME_PROPERTIES -> buildKeyframeProperties();
            case TRACK_LIST -> buildTrackList();
            case TRIGGER -> buildTriggerPanel();
        }
        content.recompute();
    }
    // Debounce: skip builds within 150ms to avoid flash on rapid property edits
    private void scheduleBuild() {
        long now = System.currentTimeMillis();
        if (now - lastBuildTime < 150) return;
        lastBuildTime = now;
        build();
    }

    private void buildScriptList() {
        System.out.println("[KILO-DEBUG] LeftPanelArea.buildScriptList: scriptFileNames=" + scriptFileNames);
        int cy = contentY() + 6;
        content.addChild(new UILabel(x + 6, cy, "Scripts", EditorTheme.TEXT_SECONDARY));
        cy += (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);

        for (String name : scriptFileNames) {
            int btnH = (int)(20 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
            UIButton itemBtn = new UIButton(x + 4, cy, w - 12, btnH, name, b -> {
                if (onOpenScript != null) onOpenScript.accept(name);
            });
            itemBtn.color(0x00, 0x443A3A3A).textColor(EditorTheme.TEXT_SECONDARY);
            content.addChild(itemBtn);
            cy += btnH + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        }

        UIButton newBtn = new UIButton(x + 4, cy, w - 12, (int)(20 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy), I18n.get("editor.script.new_button"), b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_SECONDARY);
        content.addChild(newBtn);
    }

    private void buildScriptProperties() {
        if (script == null) return;
        com.immersivecinematics.immersive_cinematics.editor.EditorDefaults.fillMetaDefaults(script);

        int cy = contentY() + 6;
        int lx = x + 6;

        // 触发器已独立到 TRIGGER tab（TriggerPanel 高度不固定，不再与 meta 叠放）
        int sectionGap = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        int smallGap = (int)(4 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        addSectionLabel(I18n.get("editor.section.script_info"), lx, cy, 0); cy += sectionGap;
        cy = buildMetaFields(lx, cy, "info");
        cy += smallGap;
        addSectionLabel(I18n.get("editor.section.runtime"), lx, cy, 0); cy += sectionGap;
        cy = buildMetaFields(lx, cy, "runtime");
        cy += 4;
        addSectionLabel(I18n.get("editor.section.duration"), lx, cy, 0); cy += (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        addSectionLabel(I18n.get("editor.field.total_duration") + ": " + fmtDuration(totalDuration), lx, cy, 0); cy += (int)(14 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    }

    /** TRIGGER tab：触发器独立面板（高度自适应，不与其他数据叠放） */
    private void buildTriggerPanel() {
        if (script == null) return;
        int cy = contentY() + 6;
        int lx = x + 6;
        addSectionLabel(I18n.get("editor.section.triggers"), lx, cy, 0); cy += (int)(12 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        JsonArray triggers = script.has("triggers") ? script.getAsJsonArray("triggers") : new JsonArray();
        if (!script.has("triggers")) script.add("triggers", triggers);
        TriggerPanel tp = new TriggerPanel(lx, cy, w - 12, 1, triggers, onDirty);
        tp.setOnTriggerChanged(() -> { build(); });
        content.addChild(tp);
    }

    /** C4：按 schema 的 "meta" 段渲染指定 section 的字段（tristate 走三态按钮，其余按类型反射） */
    private int buildMetaFields(int lx, int cy, String section) {
        for (Map.Entry<String, SchemaLoader.FieldDef> e : SchemaLoader.getMetaFields().entrySet()) {
            if (!section.equals(e.getValue().section())) continue;
            String key = e.getKey();
            if ("tristate".equals(e.getValue().type())) {
                cy = reflectTristate(key, lx, cy, 0, script);
            } else if ("int".equals(e.getValue().type()) && e.getValue().defaultValue() == null) {
                // 可选 int 字段（schema 声明但无默认值）：未写入 JSON 时也渲染，带"未设置（跟随全局配置）"态
                cy = reflectOptionalInt(key, lx, cy, 0, script);
            } else if (script.has(key)) {
                cy = reflectField(key, script.get(key), lx, cy, 0, script, null, false);
            }
        }
        return cy;
    }

    /**
     * 可选 int 字段下拉（schema 无默认值的 int meta 字段）：始终渲染。
     * 未设置 = 不写入 JSON（运行时回落到全局配置）；当前用于 skip_vote_ratio（10~100 每 10 一档）。
     */
    private int reflectOptionalInt(String key, int lx, int cy, int depth, JsonObject parentObj) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        List<String> options = new java.util.ArrayList<>();
        options.add(I18n.get("editor.field." + key + ".unset"));
        for (int v = 100; v >= 10; v -= 10) options.add(String.valueOf(v));

        UIDropdown dd = new UIDropdown(ix, cy, iw, 16, options,
                () -> {
                    if (!parentObj.has(key) || parentObj.get(key).isJsonNull()) return 0;
                    int cur = parentObj.get(key).getAsInt();
                    for (int i = 1; i < options.size(); i++) {
                        if (Integer.parseInt(options.get(i)) == cur) return i;
                    }
                    return -1; // 非预设值（如手写 55）→ 显示空白，仍可下拉改选
                },
                i -> {
                    if (i <= 0) parentObj.remove(key);
                    else parentObj.addProperty(key, Integer.parseInt(options.get(i)));
                    if (onDirty != null) onDirty.run();
                    scheduleBuild();
                });
        dd.setLabel(label + ":");
        content.addChild(dd);
        return cy + 18;
    }

    private void buildClipProperties() {
        if (selectedClip == null) return;
        com.immersivecinematics.immersive_cinematics.editor.EditorDefaults.fillClipDefaults(selectedClip, selectedTrackType);

        int cy = contentY() + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.clip_properties"), lx, cy, 0); cy += (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        // C4：字段顺序 = 通用字段 + schema clip 字段（白名单语义，schema 外字段不显示）。
        // position_mode/follow/look_at 均已迁移到关键帧级，clip 面板不再有互斥过滤
        String trackType = selectedTrackType != null ? selectedTrackType : "CAMERA";
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.add("start_time"); keys.add("duration");
        for (Map.Entry<String, SchemaLoader.FieldDef> e : SchemaLoader.getClipFields(TrackType.valueOf(trackType.toUpperCase())).entrySet()) {
            if (!"start_time".equals(e.getKey()) && !"duration".equals(e.getKey()) && !"keyframes".equals(e.getKey())) keys.add(e.getKey());
        }
        // 呼吸扰动 v2：trauma 专属参数（cam_breath_trauma/decay）仅当 cam_breath_type=trauma 时显示
        if (selectedClip != null && selectedClip.has("cam_breath_type")) {
            if (!"trauma".equals(selectedClip.get("cam_breath_type").getAsString())) {
                keys.remove("cam_breath_trauma");
                keys.remove("cam_breath_decay");
            }
        }
        cy = reflectObject(selectedClip, lx, cy, keys.toArray(new String[0]), false);
    }

    private void buildKeyframeProperties() {
        if (selectedKeyframe == null) return;
        com.immersivecinematics.immersive_cinematics.editor.EditorDefaults.fillKeyframeDefaults(selectedKeyframe, selectedTrackType);

        // 关键帧级 position_mode：切换时把该帧 position 在 dx/dy/dz ↔ x/y/z 间转换
        if (selectedKeyframe.has("position")) {
            String mode = selectedKeyframe.has("position_mode") ? selectedKeyframe.get("position_mode").getAsString() : "relative";
            JsonObject pos = selectedKeyframe.getAsJsonObject("position");
            if ("absolute".equals(mode) && pos.has("dx") && !pos.has("x")) {
                pos.addProperty("x", pos.get("dx").getAsFloat());
                pos.addProperty("y", pos.get("dy").getAsFloat());
                pos.addProperty("z", pos.get("dz").getAsFloat());
                pos.remove("dx"); pos.remove("dy"); pos.remove("dz");
            } else if ("relative".equals(mode) && pos.has("x") && !pos.has("dx")) {
                pos.addProperty("dx", pos.get("x").getAsFloat());
                pos.addProperty("dy", pos.get("y").getAsFloat());
                pos.addProperty("dz", pos.get("z").getAsFloat());
                pos.remove("x"); pos.remove("y"); pos.remove("z");
            }
        }

        int cy = contentY() + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.keyframe_properties"), lx, cy, 0); cy += (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        // C4：字段顺序 = time + schema keyframe 字段
        String kfTrackType = selectedTrackType != null ? selectedTrackType : "CAMERA";
        java.util.LinkedHashSet<String> kfKeys = new java.util.LinkedHashSet<>();
        kfKeys.add("time");
        for (Map.Entry<String, SchemaLoader.FieldDef> e : SchemaLoader.getKeyframeFields(TrackType.valueOf(kfTrackType.toUpperCase())).entrySet()) {
            if (!"time".equals(e.getKey())) kfKeys.add(e.getKey());
        }
        // 对立字段互斥（关键帧级）：look_at=entity 显示 look_at_selector、coordinate 显示 look_at_target_xyz/structure，
        // follow=entity 显示 follow_selector；look_at 非 none 时 yaw/pitch 被目标点覆盖，隐藏编辑
        if ("CAMERA".equals(kfTrackType) && selectedKeyframe != null) {
            String lookAt = selectedKeyframe.has("look_at") ? selectedKeyframe.get("look_at").getAsString() : "none";
            String follow = selectedKeyframe.has("follow") ? selectedKeyframe.get("follow").getAsString() : "none";
            if (!"entity".equals(lookAt)) kfKeys.remove("look_at_selector");
            if (!"coordinate".equals(lookAt)) {
                kfKeys.remove("look_at_target_x");
                kfKeys.remove("look_at_target_y");
                kfKeys.remove("look_at_target_z");
                kfKeys.remove("look_at_target_structure");
            } else {
                // 互斥：已指定结构时坐标输入隐藏（下拉选"（空）"移除结构字段后坐标自动恢复显示）
                String structureId = selectedKeyframe.has("look_at_target_structure")
                        ? selectedKeyframe.get("look_at_target_structure").getAsString() : "";
                if (!structureId.isEmpty()) {
                    kfKeys.remove("look_at_target_x");
                    kfKeys.remove("look_at_target_y");
                    kfKeys.remove("look_at_target_z");
                }
            }
            if (!"none".equals(lookAt)) {
                kfKeys.remove("yaw");
                kfKeys.remove("pitch");
            }
            if (!"entity".equals(follow)) kfKeys.remove("follow_selector");
        }
        cy = reflectObject(selectedKeyframe, lx, cy, kfKeys.toArray(new String[0]), true);
    }

    /**
     * 结构目标下拉（look_at_target_structure）：列出注册表（原版+模组）所有结构 id，自动补全。
     * 选项含"（空）"= 不使用结构目标（结构/坐标互斥，选空后坐标输入恢复显示）。未进世界时用文本输入代替下拉。
     */
    private int reflectStructureDropdown(String key, int lx, int cy, int depth, JsonObject parentObj) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        String current = parentObj.has(key) ? parentObj.get(key).getAsString() : "";

        java.util.List<String> structureIds = new java.util.ArrayList<>();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) {
            try {
                for (net.minecraft.resources.ResourceLocation id :
                        mc.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).keySet()) {
                    structureIds.add(id.toString());
                }
                structureIds.sort(String::compareTo);
            } catch (Exception ignored) {
                // 编辑器防御：结构注册表读取失败 → 结构列表为空，回退文本输入（可选探测，缺失即无下拉候选）
            }
        }
        if (structureIds.isEmpty()) {
            return reflectField(key, parentObj.get(key), lx, cy, depth, parentObj, null, true);
        }

        java.util.List<String> display = new java.util.ArrayList<>();
        display.add("(" + I18n.get("editor.enum.none") + ")");
        for (String id : structureIds) {
            display.add(id.equals(current) ? id : id);
        }
        UIDropdown dd = new UIDropdown(ix, cy, iw, 16, display,
                () -> {
                    int idx = structureIds.indexOf(parentObj.has(key) ? parentObj.get(key).getAsString() : "");
                    return idx < 0 ? 0 : idx + 1;
                },
                idx -> {
                    if (idx <= 0) parentObj.remove(key);
                    else if (idx - 1 < structureIds.size()) parentObj.addProperty(key, structureIds.get(idx - 1));
                    if (onDirty != null) onDirty.run();
                    scheduleBuild();
                });
        dd.setLabel(label);
        content.addChild(dd);
        return cy + 18;
    }

    /** Auto-reflect a JsonObject's fields as editable widgets (entry point). */
    private int reflectObject(JsonObject obj, int lx, int cy, String[] orderedKeys, boolean isKeyframe) {
        return reflectObjectAll(obj, lx, cy, 0, orderedKeys, null, isKeyframe);
    }

    private int reflectObjectAll(JsonObject obj, int lx, int cy, int depth,
                                  String[] orderedKeys, String parentKey, boolean isKeyframe) {
        if (orderedKeys != null) {
            for (String key : orderedKeys) {
                // C4：枚举字段由 schema enum values 驱动（原 CLIP_ENUM_KEYS 硬编码集合已删除）
                List<String> enumVals = SchemaLoader.getEnumValues(
                        TrackType.valueOf((selectedTrackType != null ? selectedTrackType : "CAMERA").toUpperCase()),
                        isKeyframe, key);
                if (!enumVals.isEmpty()) {
                    cy = reflectClipEnum(key, lx, cy, depth, obj, isKeyframe, enumVals);
                } else if ("look_at_target_structure".equals(key) && obj.has(key)) {
                    cy = reflectStructureDropdown(key, lx, cy, depth, obj);
                } else if (obj.has(key)) {
                    cy = reflectField(key, obj.get(key), lx, cy, depth, obj, parentKey, isKeyframe);
                }
            }
            return cy;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            cy = reflectField(entry.getKey(), entry.getValue(), lx, cy, depth, obj, parentKey, isKeyframe);
        }
        return cy;
    }

    private int reflectTristate(String key, int lx, int cy, int depth, JsonObject parentObj) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        boolean hasValue = parentObj.has(key) && !parentObj.get(key).isJsonNull();
        boolean value = hasValue && parentObj.get(key).getAsBoolean();

        UIButton btn = new UIButton(ix, cy, iw, 16, displayTristate(label, hasValue, value), b -> {
            if (!parentObj.has(key) || parentObj.get(key).isJsonNull()) {
                parentObj.addProperty(key, true);
            } else if (parentObj.get(key).getAsBoolean()) {
                parentObj.addProperty(key, false);
            } else {
                parentObj.remove(key);
            }
            if (onDirty != null) onDirty.run();
            scheduleBuild();
        });

        if (!hasValue) {
            btn.color(0x00, 0x443A3A3A).textColor(EditorTheme.TEXT_DIM);
        } else if (value) {
            btn.color(0x00, 0x44224422).textColor(0xFF44AA44);
        } else {
            btn.color(0x00, 0x44442222).textColor(0xFFAA4444);
        }
        content.addChild(btn);
        return cy + 18;
    }

    private static String displayTristate(String label, boolean hasValue, boolean value) {
        if (!hasValue) return label + ": " + I18n.get("editor.enum.tristate.null");
        return value ? label + ": " + I18n.get("editor.enum.tristate.true") : label + ": " + I18n.get("editor.enum.tristate.false");
    }

    private int reflectClipEnum(String key, int lx, int cy, int depth, JsonObject parentObj,
                                boolean isKeyframe, List<String> values) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        String current = parentObj.has(key) ? parentObj.get(key).getAsString() : (values.isEmpty() ? "" : values.get(0));
        String enumTKey = "editor.enum." + key + "." + current;
        String displayVal = I18n.exists(enumTKey) ? I18n.get(enumTKey) : current;

        // 模式切换字段（关键帧级 follow / look_at / position_mode）用下拉菜单：
        // 切换模式后对立字段组（selector vs target_xyz、dx/dy/dz vs x/y/z）单独显示（buildKeyframeProperties 按模式过滤）
        if ("cam_tracking_look_at".equals(key) || "follow".equals(key) || "look_at".equals(key) || "position_mode".equals(key)) {
            List<String> display = new ArrayList<>();
            for (String v : values) {
                String tk = "editor.enum." + key + "." + v;
                display.add(I18n.exists(tk) ? I18n.get(tk) : v);
            }
            UIDropdown dd = new UIDropdown(ix, cy, iw, 16, display,
                    () -> Math.max(0, values.indexOf(parentObj.has(key) ? parentObj.get(key).getAsString() : (values.isEmpty() ? "" : values.get(0)))),
                    idx -> {
                        if (idx < 0 || idx >= values.size()) return;
                        parentObj.addProperty(key, values.get(idx));
                        if (onDirty != null) onDirty.run();
                        scheduleBuild();
                    });
            dd.setLabel(label);
            content.addChild(dd);
            return cy + 18;
        }

        UIButton btn = new UIButton(ix, cy, iw, 16, label + ": " + displayVal, b -> {
            // C4：按 schema values 顺序循环（indexOf 找不到 → 回到第一个）
            String next = values.isEmpty() ? current : values.get((values.indexOf(current) + 1) % values.size());
            parentObj.addProperty(key, next);
            if (onDirty != null) onDirty.run();
            scheduleBuild();
        });
        btn.color(0x00, 0x44333A3A).textColor(EditorTheme.TEXT_SECONDARY);
        content.addChild(btn);
        return cy + 18;
    }

    private int reflectField(String key, JsonElement val, int lx, int cy,
                              int depth, JsonObject parentObj, String parentKey, boolean isKeyframe) {
        if (val.isJsonObject()) {
            addSectionLabel(formatKey(key) + ":", lx, cy, depth);
            cy += 12;
            cy = reflectObjectAll(val.getAsJsonObject(), lx, cy, depth + 1, null, key, isKeyframe);
            cy += 2;
            return cy;
        }

        if (val.isJsonArray()) {
            addSectionLabel(formatKey(key), lx, cy, depth);
            cy += 14;
            JsonArray arr = val.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (el.isJsonObject()) {
                    addSectionLabel("[" + i + "]", lx, cy, depth + 1);
                    cy += 12;
                    cy = reflectObjectAll(el.getAsJsonObject(), lx, cy, depth + 2, null, key, isKeyframe);
                }
            }
            return cy;
        }

        if (!val.isJsonPrimitive()) return cy;
        return reflectPrimitive(key, val, lx, cy, depth, parentObj, isKeyframe);
    }

    private int reflectPrimitive(String key, JsonElement val, int lx, int cy,
                                  int depth, JsonObject parentObj, boolean isKeyframe) {
        JsonPrimitive prim = val.getAsJsonPrimitive();
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;

        String label = formatKey(key);

        if (prim.isBoolean()) {
            addToggle(label, () -> {
                return parentObj.has(key) && parentObj.get(key).getAsBoolean();
            }, ix, cy, v -> {
                parentObj.addProperty(key, v);
                if (onDirty != null) onDirty.run();
            });
            return cy + 18;
        }

        if (prim.isNumber()) {
            float current = prim.getAsFloat();
            boolean isInt = current == Math.floor(current) && !Float.isInfinite(current) && key.equals("version");
            if (isInt) return cy;
            // C4：schema int 类型字段 → 整数步进 1，提交时取整
            boolean isIntField = false;
            try {
                SchemaLoader.FieldDef def = isKeyframe
                        ? SchemaLoader.getKeyframeFields(TrackType.valueOf((selectedTrackType != null ? selectedTrackType : "CAMERA").toUpperCase())).get(key)
                        : SchemaLoader.getClipFields(TrackType.valueOf((selectedTrackType != null ? selectedTrackType : "CAMERA").toUpperCase())).get(key);
                isIntField = def != null && "int".equals(def.type());
            } catch (Exception ignored) {
                // 编辑器防御：schema 字段查询失败则按 float 渲染该字段（视觉略退化，不影响数据）；
                // 若某类字段反复走这里说明 schema 与轨道类型不匹配，属应修复的配置问题，非运行时错误
            }
            if (isIntField) {
                addFloatField(label, () -> {
                    return parentObj.has(key) ? parentObj.get(key).getAsFloat() : 0;
                }, ix, cy, -9999, 9999, 1f, v -> {
                    parentObj.addProperty(key, Math.round(v));
                    if (onDirty != null) onDirty.run();
                }, iw);
                return cy + 18;
            }
            addFloatField(label, () -> {
                return parentObj.has(key) ? parentObj.get(key).getAsFloat() : 0;
            }, ix, cy, -9999, 9999, 0.5f, v -> {
                parentObj.addProperty(key, v);
                if (onDirty != null) onDirty.run();
            }, iw);
            return cy + 18;
        }

        if (prim.isString()) {
            UITextInput ti = new UITextInput(ix, cy, iw, 16, label,
                    () -> parentObj.has(key) ? parentObj.get(key).getAsString() : "",
                    v -> {
                        parentObj.addProperty(key, v);
                        if (onDirty != null) onDirty.run();
                    });
            content.addChild(ti);
            return cy + 18;
        }
        return cy;
    }

    private static String fmtDuration(float s) {
        int totalSec = Math.round(s);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int sec = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d (%.1fs)", h, m, sec, s);
        return String.format("%d:%02d (%.1fs)", m, sec, s);
    }

    private static String formatKey(String key) {
        String k = "editor.field." + key;
        if (I18n.exists(k)) return I18n.get(k);
        return key.replace("_", " ");
    }

    private void addSectionLabel(String text, int lx, int cy, int depth) {
        content.addChild(new UILabel(lx + depth * 10, cy, text, EditorTheme.TEXT_DIM));
    }

    private int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                              float min, float max, float step, Consumer<Float> sink) {
        return addFloatField(label, source, lx, cy, min, max, step, sink, w - 12);
    }

    private int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                              float min, float max, float step, Consumer<Float> sink, int width) {
        int fh = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        UIFloatInput fi = new UIFloatInput(lx, cy, width, fh, label, source, min, max, step, sink);
        content.addChild(fi);
        return cy + fh + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    }

    private int addToggle(String label, java.util.function.Supplier<Boolean> source, int lx, int cy, Consumer<Boolean> sink) {
        int fh = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        UIToggle tgl = new UIToggle(lx, cy, w - 12, fh, label, source, sink);
        content.addChild(tgl);
        return cy + fh + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    }

    public UIComponent getFocusedInput() {
        return findFocusedInput(getChildren());
    }

    private static UIComponent findFocusedInput(List<UIComponent> list) {
        for (UIComponent c : list) {
            if (c instanceof IFocusable) {
                IFocusable f = (IFocusable) c;
                if (f.isFocused()) return c;
            }
            List<UIComponent> sub = c.getChildren();
            if (sub != null) {
                UIComponent found = findFocusedInput(sub);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void clearTextFocus() {
        clearTextFocus(getChildren());
    }

    private static void clearTextFocus(List<UIComponent> list) {
        for (UIComponent c : list) {
            if (c instanceof IFocusable) ((IFocusable) c).clearFocus();
            List<UIComponent> sub = c.getChildren();
            if (sub != null) clearTextFocus(sub);
        }
    }
    /**
     * 渲染管线：背景 + Tab 栏 + ScrollablePanel 内容 + 外框。
     * 滚动、视口裁剪、滚动条全部由 ScrollablePanel 自管。
     */
    @Override
    public void render(UIContext ctx) {
        if (!visible) return;

        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_TRACK);
        ctx.graphics.fill(x + w - 1, y, x + w, y + h, EditorTheme.BORDER_DARK);

        // Tab 栏：不透明背景（内容滚动不会穿透）+ 固定按钮（不随内容滚动）
        if (tabBar != null) tabBar.render(ctx);
        if (content != null) content.render(ctx);
        // Tab 栏最后再盖一层（防止滚动内容在视口边缘残留）并重绘按钮
        if (tabBar != null) tabBar.render(ctx);

        ctx.graphics.renderOutline(x, y, w, h, EditorTheme.BORDER);
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        if (tabBar != null) tabBar.renderOverlay(ctx);
        if (content != null) content.renderOverlay(ctx);
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!ctx.isMouseIn(hitX(), hitY(), w, h)) return false;

        EditorLogger.areaHit(EditorLogger.LEFT, "full_area", ctx.mouseX, ctx.mouseY, true);
        EditorLogger.areaHit(EditorLogger.LEFT, "mode_" + mode.name(), ctx.mouseX, ctx.mouseY, true);

        // 子组件命中已由 UIComponent.mouseClicked 模板统一分发（绝对坐标 + 滚动修正 + 容器裁剪），
        // 这里只保留 toggle 点击日志特判，不再重复分发。
        for (UIComponent c : getChildren()) {
            if (c.isHovered(ctx) && c instanceof com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle) {
                com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle tgl =
                        (com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle) c;
                EditorLogger.action(EditorLogger.LEFT, "TOGGLE_CLICK", "label=" + mode + " value=" + !tgl.isOn());
            }
        }
        return false;
    }

    private void buildTrackList() {
        if (tracks == null) return;

        int cy = contentY() + 4;
        int lx = x + 6;
        int rowH = 20;

        addSectionLabel(I18n.get("editor.section.track_list"), lx, cy, 0);
        cy += 16;

        for (int ti = 0; ti < tracks.size(); ti++) {
            JsonObject track = tracks.get(ti).getAsJsonObject();
            String type = track.has("type") ? track.get("type").getAsString() : "TRACK";
            int clipCount = track.has("clips") ? track.getAsJsonArray("clips").size() : 0;

            int finalTi = ti;
            // 显示 id（多轨道管理：同类型多条轨道通过 id 区分）
            String trackId = track.has("id") ? track.get("id").getAsString() : type;
            String label = trackId + "  (" + I18n.get("editor.label.clip_count", String.valueOf(clipCount)) + ")";
            UIButton row = new UIButton(lx + 4, cy, w - 12 - 26, rowH, label, btn -> {
                if (onTrackSelected != null) onTrackSelected.accept(finalTi);
            });
            row.color(0x00, 0x443A3A3A).textColor(EditorTheme.TEXT_SECONDARY);
            content.addChild(row);

            // 显隐开关 👁（点击切换，不切换选中轨道）
            UIButton visBtn = new UIButton(lx + 4 + (w - 12 - 26) + 2, cy, 24, rowH,
                    I18n.get("editor.tab.track_visible"), btn -> {
                        if (onToggleTrackVisible != null) onToggleTrackVisible.accept(track);
                    });
            visBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_SECONDARY);
            content.addChild(visBtn);

            cy += rowH + 2;
        }
    }


    public void setTracks(JsonArray t) { this.tracks = t; dataDirty = true; }

    private int contentY() { return content != null ? content.y + 4 : y + TAB_HEIGHT + 4; }
    public void setOnTrackSelected(Consumer<Integer> r) { onTrackSelected = r; }
    public void setOnToggleTrackVisible(Consumer<JsonObject> r) { onToggleTrackVisible = r; }
}
    
