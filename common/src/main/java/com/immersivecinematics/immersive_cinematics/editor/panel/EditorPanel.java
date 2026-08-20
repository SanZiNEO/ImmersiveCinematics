package com.immersivecinematics.immersive_cinematics.editor.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.fields.FieldGroup;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIButton;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIComponent;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIDropdown;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIFloatInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UILabel;
import com.immersivecinematics.immersive_cinematics.editor.widget.UITextInput;
import com.immersivecinematics.immersive_cinematics.editor.widget.UIToggle;
import com.immersivecinematics.immersive_cinematics.script.SchemaLoader;
import com.immersivecinematics.immersive_cinematics.script.TrackType;
import com.immersivecinematics.immersive_cinematics.script.schema.FieldDef;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 编辑器内容面板基类（0.3.5 第5轮 5A）。
 * <p>
 * 每个 LeftPanelArea.PanelMode 对应一个 EditorPanel 子类。
 * 面板使用屏幕绝对坐标创建子组件（x/y 由 LeftPanelArea 在 add 前设置），
 * build() 模板负责 clearChildren → buildContent → 按子组件底部回写 this.h，
 * 使 ScrollablePanel 能正确计算滚动范围并让面板命中区覆盖全部内容。
 */
public abstract class EditorPanel extends UIComponent {

    protected PanelContext ctx;
    /** 折叠组展开状态（titleKey → expanded），跨 build 保留 */
    private final Map<String, Boolean> groupStates = new HashMap<>();

    public EditorPanel() {
        super(0, 0, 0, 0);
    }

    public void setContext(PanelContext ctx) {
        this.ctx = ctx;
    }

    public PanelContext getContext() {
        return ctx;
    }

    /** 模板：清空 → 子类构建内容 → 回写实际高度 */
    public final void build() {
        clearChildren();
        buildContent();
        finishBuild();
    }

    protected abstract void buildContent();

    protected void markDirty() {
        if (ctx != null && ctx.onDirty != null) ctx.onDirty.run();
    }

    protected void requestRebuild() {
        if (ctx != null && ctx.onRebuild != null) ctx.onRebuild.run();
    }

    protected String selectedTrackType() {
        return ctx != null && ctx.selectedTrackType != null ? ctx.selectedTrackType : "CAMERA";
    }

    protected void finishBuild() {
        int bottom = y;
        for (UIComponent c : getChildren()) {
            if (!c.visible) continue;
            bottom = Math.max(bottom, getComponentBottom(c));
        }
        this.h = Math.max(1, bottom - y + 4);
    }

    private static int getComponentBottom(UIComponent comp) {
        if (!comp.visible) return comp.y;
        int b = comp.y + comp.h;
        List<UIComponent> sub = comp.getChildren();
        if (sub != null) {
            for (UIComponent s : sub) {
                if (!s.visible) continue;
                b = Math.max(b, getComponentBottom(s));
            }
        }
        return b;
    }

    // ── 折叠分组 + 字段全覆盖 ──

    protected boolean isGroupExpanded(String titleKey) {
        Boolean state = groupStates.get(titleKey);
        if (state == null) {
            for (FieldGroup group : currentGroups()) {
                if (group.getTitleKey().equals(titleKey)) {
                    groupStates.put(titleKey, group.isDefaultExpanded());
                    return group.isDefaultExpanded();
                }
            }
            return true;
        }
        return state;
    }

    protected void toggleGroup(String titleKey) {
        groupStates.put(titleKey, !isGroupExpanded(titleKey));
        requestRebuild();
    }

    /** 子类可覆盖，用于解析折叠组默认状态；未覆盖时按默认展开 */
    protected List<FieldGroup> currentGroups() {
        return java.util.Collections.emptyList();
    }

    /** 按折叠组渲染字段；组内未设置字段也会显示“点击添加”入口 */
    protected int reflectGroupedFields(JsonObject obj, int lx, int cy, List<FieldGroup> groups, boolean isKeyframe) {
        for (FieldGroup group : groups) {
            boolean expanded = isGroupExpanded(group.getTitleKey());
            String label = I18n.get(group.getTitleKey());
            UIButton title = new UIButton(lx, cy, w - 12, 16, (expanded ? "▾ " : "▸ ") + label, b -> {
                toggleGroup(group.getTitleKey());
            });
            title.leftAlign();
            title.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER).textColor(EditorTheme.TEXT_PRIMARY);
            addChild(title);
            cy += 18;

            int fieldStart = cy;
            int start = getChildren().size();
            for (String key : group.getKeys()) {
                cy = reflectFieldOrAdd(obj, key, lx, cy, isKeyframe);
            }
            int end = getChildren().size();
            if (!expanded) {
                for (int i = start; i < end; i++) {
                    getChildren().get(i).visible = false;
                }
                cy = fieldStart;
            }
            cy += 4;
        }
        return cy;
    }

    /** 字段全覆盖：已有字段正常渲染；缺失字段显示“未设置/点击添加” */
    protected int reflectFieldOrAdd(JsonObject obj, String key, int lx, int cy, boolean isKeyframe) {
        if (obj.has(key)) {
            return reflectField(key, obj.get(key), lx, cy, 0, obj, null, isKeyframe);
        }

        FieldDef def = findFieldDef(key, isKeyframe);
        String type = def != null ? def.type() : "string";
        if ("tristate".equals(type)) {
            return reflectTristate(key, lx, cy, 0, obj);
        }
        if ("int".equals(type) && (def == null || def.defaultValue() == null)) {
            return reflectOptionalInt(key, lx, cy, 0, obj);
        }

        String label = formatKey(key) + ": " + I18n.get("editor.field.unset");
        UIButton add = new UIButton(lx, cy, w - 12, 16, label, b -> {
            addMissingField(obj, key, type, def);
            markDirty();
            requestRebuild();
        });
        add.color(0x00, 0x442A2A2A).textColor(EditorTheme.TEXT_DIM);
        addChild(add);
        return cy + 18;
    }

    private void addMissingField(JsonObject obj, String key, String type, FieldDef def) {
        Object defVal = def != null ? def.defaultValue() : null;
        if (defVal instanceof Boolean) {
            obj.addProperty(key, (Boolean) defVal);
        } else if (defVal instanceof Number) {
            obj.addProperty(key, ((Number) defVal).floatValue());
        } else if (defVal instanceof String) {
            obj.addProperty(key, (String) defVal);
        } else if ("map".equals(type) || "position".equals(type) || "object".equals(type)) {
            obj.add(key, new JsonObject());
        } else if ("array".equals(type) || "bezier_curve".equals(type)) {
            obj.add(key, new JsonArray());
        } else {
            obj.addProperty(key, "");
        }
    }

    private FieldDef findFieldDef(String key, boolean isKeyframe) {
        if (ctx != null) {
            TrackType tt = TrackType.valueOf(selectedTrackType().toUpperCase());
            Map<String, FieldDef> fields = isKeyframe
                    ? SchemaLoader.getKeyframeFields(tt)
                    : SchemaLoader.getClipFields(tt);
            FieldDef def = fields.get(key);
            if (def != null) return def;
        }
        return SchemaLoader.getMetaFields().get(key);
    }

    // ── 字段反射工具（后续可整体迁到 fields/ 包） ──

    protected int reflectObject(JsonObject obj, int lx, int cy, String[] orderedKeys, boolean isKeyframe) {
        return reflectObjectAll(obj, lx, cy, 0, orderedKeys, null, isKeyframe);
    }

    private int reflectObjectAll(JsonObject obj, int lx, int cy, int depth,
                                 String[] orderedKeys, String parentKey, boolean isKeyframe) {
        if (orderedKeys != null) {
            for (String key : orderedKeys) {
                List<String> enumVals = SchemaLoader.getEnumValues(
                        TrackType.valueOf(selectedTrackType().toUpperCase()),
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

    protected int reflectTristate(String key, int lx, int cy, int depth, JsonObject parentObj) {
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
            markDirty();
            requestRebuild();
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

    protected int reflectOptionalInt(String key, int lx, int cy, int depth, JsonObject parentObj) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        List<String> options = new ArrayList<>();
        options.add(I18n.get("editor.field." + key + ".unset"));
        for (int v = 100; v >= 10; v -= 10) options.add(String.valueOf(v));

        UIDropdown dd = new UIDropdown(ix, cy, iw, 16, options,
                () -> {
                    if (!parentObj.has(key) || parentObj.get(key).isJsonNull()) return 0;
                    int cur = parentObj.get(key).getAsInt();
                    for (int i = 1; i < options.size(); i++) {
                        if (Integer.parseInt(options.get(i)) == cur) return i;
                    }
                    return -1;
                },
                i -> {
                    if (i <= 0) parentObj.remove(key);
                    else parentObj.addProperty(key, Integer.parseInt(options.get(i)));
                    markDirty();
                    requestRebuild();
                });
        dd.setLabel(label + ":");
        addChild(dd);
        return cy + 18;
    }

    protected int reflectStructureDropdown(String key, int lx, int cy, int depth, JsonObject parentObj) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        String current = parentObj.has(key) ? parentObj.get(key).getAsString() : "";

        List<String> structureIds = new ArrayList<>();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) {
            for (net.minecraft.resources.ResourceLocation id :
                    mc.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).keySet()) {
                structureIds.add(id.toString());
            }
            structureIds.sort(String::compareTo);
        }
        if (structureIds.isEmpty()) {
            return reflectField(key, parentObj.get(key), lx, cy, depth, parentObj, null, true);
        }

        List<String> display = new ArrayList<>();
        display.add("(" + I18n.get("editor.enum.none") + ")");
        for (String id : structureIds) {
            display.add(id);
        }
        UIDropdown dd = new UIDropdown(ix, cy, iw, 16, display,
                () -> {
                    int idx = structureIds.indexOf(parentObj.has(key) ? parentObj.get(key).getAsString() : "");
                    return idx < 0 ? 0 : idx + 1;
                },
                idx -> {
                    if (idx <= 0) parentObj.remove(key);
                    else if (idx - 1 < structureIds.size()) parentObj.addProperty(key, structureIds.get(idx - 1));
                    markDirty();
                    requestRebuild();
                });
        dd.setLabel(label);
        addChild(dd);
        return cy + 18;
    }

    protected int reflectClipEnum(String key, int lx, int cy, int depth, JsonObject parentObj,
                                  boolean isKeyframe, List<String> values) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        String current = parentObj.has(key) ? parentObj.get(key).getAsString() : (values.isEmpty() ? "" : values.get(0));
        String enumTKey = "editor.enum." + key + "." + current;
        String displayVal = I18n.exists(enumTKey) ? I18n.get(enumTKey) : current;

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
                        markDirty();
                        requestRebuild();
                    });
            dd.setLabel(label);
            addChild(dd);
            return cy + 18;
        }

        UIButton btn = new UIButton(ix, cy, iw, 16, label + ": " + displayVal, b -> {
            String next = values.isEmpty() ? current : values.get((values.indexOf(current) + 1) % values.size());
            parentObj.addProperty(key, next);
            markDirty();
            requestRebuild();
        });
        btn.color(0x00, 0x44333A3A).textColor(EditorTheme.TEXT_SECONDARY);
        addChild(btn);
        return cy + 18;
    }

    protected int reflectField(String key, JsonElement val, int lx, int cy,
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
                markDirty();
            });
            return cy + 18;
        }

        if (prim.isNumber()) {
            float current = prim.getAsFloat();
            boolean isInt = current == Math.floor(current) && !Float.isInfinite(current) && key.equals("version");
            if (isInt) return cy;
            FieldDef def = isKeyframe
                    ? SchemaLoader.getKeyframeFields(TrackType.valueOf(selectedTrackType().toUpperCase())).get(key)
                    : SchemaLoader.getClipFields(TrackType.valueOf(selectedTrackType().toUpperCase())).get(key);
            boolean isIntField = def != null && "int".equals(def.type());
            if (isIntField) {
                addFloatField(label, () -> {
                    return parentObj.has(key) ? parentObj.get(key).getAsFloat() : 0;
                }, ix, cy, -9999, 9999, 1f, v -> {
                    parentObj.addProperty(key, Math.round(v));
                    markDirty();
                }, iw);
                return cy + 18;
            }
            addFloatField(label, () -> {
                return parentObj.has(key) ? parentObj.get(key).getAsFloat() : 0;
            }, ix, cy, -9999, 9999, 0.5f, v -> {
                parentObj.addProperty(key, v);
                markDirty();
            }, iw);
            return cy + 18;
        }

        if (prim.isString()) {
            UITextInput ti = new UITextInput(ix, cy, iw, 16, label,
                    () -> parentObj.has(key) ? parentObj.get(key).getAsString() : "",
                    v -> {
                        parentObj.addProperty(key, v);
                        markDirty();
                    });
            addChild(ti);
            return cy + 18;
        }
        return cy;
    }

    protected static String fmtDuration(float s) {
        int totalSec = Math.round(s);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int sec = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d (%.1fs)", h, m, sec, s);
        return String.format("%d:%02d (%.1fs)", m, sec, s);
    }

    protected static String formatKey(String key) {
        String k = "editor.field." + key;
        if (I18n.exists(k)) return I18n.get(k);
        return key.replace("_", " ");
    }

    protected void addSectionLabel(String text, int lx, int cy, int depth) {
        addChild(new UILabel(lx + depth * 10, cy, text, EditorTheme.TEXT_DIM));
    }

    protected int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                                float min, float max, float step, Consumer<Float> sink) {
        return addFloatField(label, source, lx, cy, min, max, step, sink, w - 12);
    }

    protected int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                                float min, float max, float step, Consumer<Float> sink, int width) {
        int fh = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        UIFloatInput fi = new UIFloatInput(lx, cy, width, fh, label, source, min, max, step, sink);
        addChild(fi);
        return cy + fh + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    }

    protected int addToggle(String label, java.util.function.Supplier<Boolean> source, int lx, int cy, Consumer<Boolean> sink) {
        int fh = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
        UIToggle tgl = new UIToggle(lx, cy, w - 12, fh, label, source, sink);
        addChild(tgl);
        return cy + fh + (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
    }
}
