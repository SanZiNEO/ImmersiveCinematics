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
    private static final int TAB_GAP = 2;
    private boolean dataDirty = true;
    private long lastBuildTime;

    private int scrollY;
    /** E7：滚轮滚动目标值（render 逐帧向它插值，实现平滑滚动） */
    private int targetScrollY;
    private int maxScroll;
    private int contentHeight;
    private boolean scrollbarGrabbed;
    private int scrollbarGrabOffset;

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


    public void build() {
        EditorLogger.action(EditorLogger.LEFT, "BUILD", "mode=" + mode);
        clearChildren();
        buildTabBar();
        switch (mode) {
            case SCRIPT_LIST -> buildScriptList();
            case SCRIPT_PROPERTIES -> buildScriptProperties();
            case CLIP_PROPERTIES -> buildClipProperties();
            case KEYFRAME_PROPERTIES -> buildKeyframeProperties();
            case TRACK_LIST -> buildTrackList();
            case TRIGGER -> buildTriggerPanel();
        }
        computeContentHeightAndClampScroll();
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
        addChild(new UILabel(x + 6, cy, "Scripts", EditorTheme.TEXT_SECONDARY));
        cy += (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);

        for (String name : scriptFileNames) {
            int btnH = (int)(20 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
            UIButton itemBtn = new UIButton(x + 4, cy, w - 12, btnH, name, b -> {
                if (onOpenScript != null) onOpenScript.accept(name);
            });
            itemBtn.color(0x00, 0x443A3A3A).textColor(EditorTheme.TEXT_SECONDARY);
            addChild(itemBtn);
            cy += btnH + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        }

        UIButton newBtn = new UIButton(x + 4, cy, w - 12, (int)(20 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy), I18n.get("editor.script.new_button"), b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_SECONDARY);
        addChild(newBtn);
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
        addChild(tp);
    }

    /** C4：按 schema 的 "meta" 段渲染指定 section 的字段（tristate 走三态按钮，其余按类型反射） */
    private int buildMetaFields(int lx, int cy, String section) {
        for (Map.Entry<String, SchemaLoader.FieldDef> e : SchemaLoader.getMetaFields().entrySet()) {
            if (!section.equals(e.getValue().section())) continue;
            String key = e.getKey();
            if ("tristate".equals(e.getValue().type())) {
                cy = reflectTristate(key, lx, cy, 0, script);
            } else if (script.has(key)) {
                cy = reflectField(key, script.get(key), lx, cy, 0, script, null, false);
            }
        }
        return cy;
    }

    private void buildClipProperties() {
        if (selectedClip == null) return;
        com.immersivecinematics.immersive_cinematics.editor.EditorDefaults.fillClipDefaults(selectedClip, selectedTrackType);

        int cy = contentY() + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.clip_properties"), lx, cy, 0); cy += (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        // C4：字段顺序 = 通用字段 + schema clip 字段（白名单语义，schema 外字段不显示）
        String trackType = selectedTrackType != null ? selectedTrackType : "CAMERA";
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.add("start_time"); keys.add("duration");
        for (Map.Entry<String, SchemaLoader.FieldDef> e : SchemaLoader.getClipFields(TrackType.valueOf(trackType.toUpperCase())).entrySet()) {
            if (!"start_time".equals(e.getKey()) && !"duration".equals(e.getKey()) && !"keyframes".equals(e.getKey())) keys.add(e.getKey());
        }
        cy = reflectObject(selectedClip, lx, cy, keys.toArray(new String[0]), false);
    }

    private void buildKeyframeProperties() {
        if (selectedKeyframe == null) return;
        com.immersivecinematics.immersive_cinematics.editor.EditorDefaults.fillKeyframeDefaults(selectedKeyframe, selectedTrackType);

        if (selectedClip != null && selectedKeyframe.has("position")) {
            String mode = selectedClip.has("position_mode") ? selectedClip.get("position_mode").getAsString() : "relative";
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
        cy = reflectObject(selectedKeyframe, lx, cy, kfKeys.toArray(new String[0]), true);
    }

    private void computeContentHeightAndClampScroll() {
        int bottom = y;
        for (UIComponent c : getChildren()) {
            bottom = Math.max(bottom, getComponentBottom(c));
        }
        contentHeight = Math.max(0, bottom - y);
        contentHeight = Math.max(0, bottom - y);

        boolean shouldScroll = contentHeight > h * 0.8f;
        if (!shouldScroll) {
            scrollY = 0;
            maxScroll = 0;
            return;
        }
        maxScroll = Math.max(0, contentHeight - h);
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
    }

    private static int getComponentBottom(UIComponent comp) {
        int b = comp.y + comp.h;
        List<UIComponent> sub = comp.getChildren();
        if (sub != null) {
            for (UIComponent s : sub) {
                b = Math.max(b, getComponentBottom(s));
            }
        }
        return b;
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
        addChild(btn);
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
        UIButton btn = new UIButton(ix, cy, iw, 16, label + ": " + displayVal, b -> {
            // C4：按 schema values 顺序循环（indexOf 找不到 → 回到第一个）
            String next = values.isEmpty() ? current : values.get((values.indexOf(current) + 1) % values.size());
            parentObj.addProperty(key, next);
            if ("position_mode".equals(key)) {
                convertKeyframePositions(parentObj, next);
            }
            if (onDirty != null) onDirty.run();
            scheduleBuild();
        });
        btn.color(0x00, 0x44333A3A).textColor(EditorTheme.TEXT_SECONDARY);
        addChild(btn);
        return cy + 18;
    }

    private static void convertKeyframePositions(JsonObject clip, String mode) {
        JsonArray kfs = clip.has("keyframes") ? clip.getAsJsonArray("keyframes") : null;
        if (kfs == null) return;
        for (JsonElement ke : kfs) {
            JsonObject kf = ke.getAsJsonObject();
            if (!kf.has("position")) continue;
            JsonObject pos = kf.getAsJsonObject("position");
            if ("absolute".equals(mode)) {
                if (pos.has("dx") && !pos.has("x")) {
                    pos.addProperty("x", pos.get("dx").getAsFloat());
                    pos.addProperty("y", pos.get("dy").getAsFloat());
                    pos.addProperty("z", pos.get("dz").getAsFloat());
                    pos.remove("dx"); pos.remove("dy"); pos.remove("dz");
                }
            } else {
                if (pos.has("x") && !pos.has("dx")) {
                    pos.addProperty("dx", pos.get("x").getAsFloat());
                    pos.addProperty("dy", pos.get("y").getAsFloat());
                    pos.addProperty("dz", pos.get("z").getAsFloat());
                    pos.remove("x"); pos.remove("y"); pos.remove("z");
                }
            }
        }
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
            } catch (Exception ignored) { }
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
            addChild(ti);
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
        addChild(new UILabel(lx + depth * 10, cy, text, EditorTheme.TEXT_DIM));
    }

    private int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                              float min, float max, float step, Consumer<Float> sink) {
        return addFloatField(label, source, lx, cy, min, max, step, sink, w - 12);
    }

    private int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                              float min, float max, float step, Consumer<Float> sink, int width) {
        int fh = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        UIFloatInput fi = new UIFloatInput(lx, cy, width, fh, label, source, min, max, step, sink);
        addChild(fi);
        return cy + fh + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    }

    private int addToggle(String label, java.util.function.Supplier<Boolean> source, int lx, int cy, Consumer<Boolean> sink) {
        int fh = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        UIToggle tgl = new UIToggle(lx, cy, w - 12, fh, label, source, sink);
        addChild(tgl);
        return cy + fh + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    }

    public UIComponent getFocusedInput() {
        return findFocusedInput(getChildren());
    }

    private static UIComponent findFocusedInput(List<UIComponent> list) {
        for (UIComponent c : list) {
            if (c instanceof IFocusable f && f.isFocused()) return c;
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
            if (c instanceof IFocusable f) f.clearFocus();
            List<UIComponent> sub = c.getChildren();
            if (sub != null) clearTextFocus(sub);
        }
    }
    /**
     * Override render() to control the full rendering pipeline:
     * 1. Fill background
     * 2. pushViewport (scissor with scroll offset for visual clipping)
     * 3. pushScroll (translate children positions for scroll visual)
     * 4. Render children
     * 5. popScroll (restore coordinates — NEVER leak to siblings)
     * 6. popViewport (restore scissor)
     * 7. Draw scrollbar + outline (not affected by scroll/ scissor)
     */
    @Override
    public void render(UIContext ctx) {
        if (!visible) return;

        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_TRACK);
        ctx.graphics.fill(x + w - 1, y, x + w, y + h, EditorTheme.BORDER_DARK);

        // E7：向目标滚动值平滑逼近（滚动条拖动走即时路径不受影响）
        if (scrollY != targetScrollY) {
            scrollY += (int)((targetScrollY - scrollY) * 0.25f);
            if (Math.abs(targetScrollY - scrollY) < 1) scrollY = targetScrollY;
        }

        ctx.pushViewport(x, y, w, h);
        ctx.pushScroll(scrollY);

        for (UIComponent child : getChildren()) {
            if (child.visible) child.render(ctx);
        }

        ctx.popScroll(scrollY);
        ctx.popViewport();

        if (maxScroll > 0) {
            int sbX = x + w - 4;
            int sbH = h;
            ctx.graphics.fill(sbX, y, sbX + 4, y + sbH, EditorTheme.SCROLLBAR_BG);
            float thumbRatio = (float)h / contentHeight;
            int thumbH = Math.max(8, (int)(sbH * thumbRatio));
            int thumbY = y + (int)((float)scrollY / maxScroll * (sbH - thumbH));
            ctx.graphics.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, EditorTheme.SCROLLBAR_THUMB);
            ctx.graphics.renderOutline(sbX, thumbY, 4, thumbH, EditorTheme.BORDER_LIGHT);
        }

        ctx.graphics.renderOutline(x, y, w, h, EditorTheme.BORDER);
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        ctx.pushScroll(scrollY);
        for (UIComponent c : getChildren()) {
            c.renderOverlay(ctx);
        }
        ctx.popScroll(scrollY);
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!ctx.isMouseIn(x, y, w, h)) return false;

        if (maxScroll > 0) {
            int sbX = x + w - 4;
            if (ctx.mouseX >= sbX) {
                float thumbRatio = (float)h / contentHeight;
                int thumbH = Math.max(8, (int)(h * thumbRatio));
                int thumbY = y + (int)((float)scrollY / maxScroll * (h - thumbH));
                if (ctx.mouseY >= thumbY && ctx.mouseY < thumbY + thumbH) {
                    scrollbarGrabbed = true;
                    scrollbarGrabOffset = ctx.mouseY - thumbY;
                } else {
                    scrollY = targetScrollY = (int)((float)(ctx.mouseY - y) / h * maxScroll);
                    clampScrollY();
                }
                return true;
            }
        }

        EditorLogger.areaHit(EditorLogger.LEFT, "full_area", ctx.mouseX, ctx.mouseY, true);
        EditorLogger.areaHit(EditorLogger.LEFT, "mode_" + mode.name(), ctx.mouseX, ctx.mouseY, true);

        ctx.pushScroll(scrollY);
        List<UIComponent> ch = getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            UIComponent c = ch.get(i);
            if (c.isHovered(ctx) && c instanceof com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle tgl) {
                EditorLogger.action(EditorLogger.LEFT, "TOGGLE_CLICK", "label=" + mode + " value=" + !tgl.isOn());
            }
            if (c.mouseClicked(ctx)) { ctx.popScroll(scrollY); return true; }
        }
        ctx.popScroll(scrollY);
        return false;
    }

    @Override
    protected boolean onDragged(UIContext ctx) {
        if (scrollbarGrabbed && maxScroll > 0) {
            float thumbRatio = (float)h / contentHeight;
            int thumbH = Math.max(8, (int)(h * thumbRatio));
            int trackSpace = h - thumbH;
            if (trackSpace > 0) {
                scrollY = targetScrollY = (int)((float)(ctx.mouseY - y - scrollbarGrabOffset) / trackSpace * maxScroll);
                clampScrollY();
            }
            return true;
        }
        ctx.pushScroll(scrollY);
        boolean result = false;
        List<UIComponent> ch = getChildren();
        if (ch != null) {
            for (int i = ch.size() - 1; i >= 0; i--) {
                if (ch.get(i).mouseDragged(ctx)) { result = true; }
            }
        }
        ctx.popScroll(scrollY);
        return result;
    }

    @Override
    protected boolean onReleased(UIContext ctx) {
        scrollbarGrabbed = false;
        return false;
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (!visible || !ctx.isMouseIn(x, y, w, h)) return false;
        ctx.pushScroll(scrollY);
        for (int i = getChildren().size() - 1; i >= 0; i--) {
            if (getChildren().get(i).mouseScrolled(ctx, scroll)) { ctx.popScroll(scrollY); return true; }
        }
        ctx.popScroll(scrollY);
        if (maxScroll > 0) {
            // E7：滚轮改 targetScrollY，视觉由 render 渐变驱动（shiftViewport 已删除）
            targetScrollY -= (int)(scroll * 20);
            clampScrollY();
            return true;
        }
        return false;
    }
    private void buildTabBar() {
        int tabX = x + 2;
        int tabY = y;
        int tabH = TAB_HEIGHT;
        int n = PanelMode.values().length;
        int tabW = (w - 4 - (n - 1) * TAB_GAP) / n;

        for (PanelMode m : PanelMode.values()) {
            String label = getTabLabel(m);
            UIButton tab = new UIButton(tabX, tabY, tabW, tabH, label, btn -> {
                setMode(m);
            });

            if (m == mode) {
                tab.color(0xFF333344, 0xFF444455).textColor(0xFFFFFFFF);
            } else {
                tab.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(0xFF888888);
            }

            addChild(tab);
            tabX += tabW + TAB_GAP;
        }
    }
    
    private String getTabLabel(PanelMode m) {
        return switch (m) {
            case SCRIPT_LIST -> I18n.get("editor.tab.list");
            case SCRIPT_PROPERTIES -> I18n.get("editor.tab.properties");
            case CLIP_PROPERTIES -> I18n.get("editor.tab.clip");
            case KEYFRAME_PROPERTIES -> I18n.get("editor.tab.keyframe");
            case TRACK_LIST -> I18n.get("editor.tab.tracks");
            case TRIGGER -> I18n.get("editor.tab.triggers");
        };
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
            addChild(row);

            // 显隐开关 👁（点击切换，不切换选中轨道）
            UIButton visBtn = new UIButton(lx + 4 + (w - 12 - 26) + 2, cy, 24, rowH,
                    I18n.get("editor.tab.track_visible"), btn -> {
                        if (onToggleTrackVisible != null) onToggleTrackVisible.accept(track);
                    });
            visBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_SECONDARY);
            addChild(visBtn);

            cy += rowH + 2;
        }
    }


    public void setTracks(JsonArray t) { this.tracks = t; dataDirty = true; }

    private void clampScrollY() {
        targetScrollY = Math.max(0, Math.min(targetScrollY, maxScroll));
    }

    private int tabBarY() { return y; }
    private int contentY() { return y + TAB_HEIGHT + 4; }
    private int contentH() { return h - TAB_HEIGHT - 4; }
    public void setOnTrackSelected(Consumer<Integer> r) { onTrackSelected = r; }
    public void setOnToggleTrackVisible(Consumer<JsonObject> r) { onToggleTrackVisible = r; }
}
    
