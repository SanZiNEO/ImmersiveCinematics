package com.immersivecinematics.immersive_cinematics.editor.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.*;
import java.util.function.Consumer;

public class LeftPanelArea extends UIComponent {
    private final List<UIComponent> children = new ArrayList<>();

    public enum PanelMode { SCRIPT_LIST, SCRIPT_PROPERTIES, CLIP_PROPERTIES, KEYFRAME_PROPERTIES }
    private PanelMode mode = PanelMode.SCRIPT_PROPERTIES;

    private JsonObject script;
    private List<String> scriptFileNames = new ArrayList<>();
    private JsonObject selectedClip;
    private JsonObject selectedKeyframe;

    private Consumer<String> onOpenScript;
    private Consumer<String> onDeleteScript;
    private Runnable onNewScript;
    private Consumer<String> onNameChanged;
    private Consumer<String> onAuthorChanged;
    private Consumer<String> onDescChanged;
    private Consumer<Float> onDurationChanged;
    private Consumer<String> onBehaviorFlag;
    private Runnable onDirty;

    public LeftPanelArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.LEFT, "full_area", x, y, w, h);
    }

    public void setMode(PanelMode m) {
        if (this.mode != m) {
            EditorLogger.areaMode(EditorLogger.LEFT, "mode", this.mode.name(), m.name());
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
    }
    public void setScriptFileNames(List<String> names) { scriptFileNames = names; }
    public void setOnOpenScript(Consumer<String> r) { onOpenScript = r; }
    public void setOnDeleteScript(Consumer<String> r) { onDeleteScript = r; }
    public void setOnNewScript(Runnable r) { onNewScript = r; }
    public void setOnNameChanged(Consumer<String> r) { onNameChanged = r; }
    public void setOnAuthorChanged(Consumer<String> r) { onAuthorChanged = r; }
    public void setOnDescChanged(Consumer<String> r) { onDescChanged = r; }
    public void setOnDurationChanged(Consumer<Float> r) { onDurationChanged = r; }
    public void setOnBehaviorFlag(Consumer<String> r) { onBehaviorFlag = r; }

    public void build() {
        EditorLogger.action(EditorLogger.LEFT, "BUILD", "mode=" + mode);
        children.clear();
        switch (mode) {
            case SCRIPT_LIST -> buildScriptList();
            case SCRIPT_PROPERTIES -> buildScriptProperties();
            case CLIP_PROPERTIES -> buildClipProperties();
            case KEYFRAME_PROPERTIES -> buildKeyframeProperties();
        }
    }

    private void buildScriptList() {
        int cy = y + 6;
        children.add(new UILabel(x + 6, cy, "Scripts", 0xFFAAAAAA));
        cy += 16;

        for (String name : scriptFileNames) {
            UIButton itemBtn = new UIButton(x + 4, cy, w - 8, 18, name, b -> {
                if (onOpenScript != null) onOpenScript.accept(name);
            });
            itemBtn.color(0x00, 0x443A3A3A).textColor(0xFFAAAAAA);
            children.add(itemBtn);
            cy += 20;
        }

        UIButton newBtn = new UIButton(x + 4, cy, w - 8, 20, "+ New Script", b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(0xFF333333, 0xFF444444).textColor(0xFFAAAAAA);
        children.add(newBtn);
    }

    private void buildScriptProperties() {
        if (script == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Script Info", lx, cy); cy += 16;
        cy = reflectObject(script, lx, cy, new String[]{"id", "name", "author", "version", "description", "dimension", "triggers"});
        cy += 4;
        addSectionLabel("Runtime", lx, cy); cy += 16;
        cy = reflectObject(script, lx, cy, new String[]{"block_keyboard", "block_mouse", "hide_hud", "hide_arm", "suppress_bob", "skippable", "hold_at_end", "interruptible"});
        cy += 4;
        addSectionLabel("Duration", lx, cy); cy += 16;
        cy = reflectFloatField("total_duration", lx, cy, () -> script.has("total_duration") ? script.get("total_duration").getAsFloat() : 0, v -> { script.addProperty("total_duration", v); if (onDirty != null) onDirty.run(); });
    }

    private void buildClipProperties() {
        if (selectedClip == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Clip Properties", lx, cy); cy += 16;
        cy = reflectObject(selectedClip, lx, cy, null);
    }

    private void buildKeyframeProperties() {
        if (selectedKeyframe == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Keyframe Properties", lx, cy); cy += 16;
        cy = reflectObject(selectedKeyframe, lx, cy, null);
    }

    /** Auto-reflect a JsonObject's fields as editable widgets. */
    private int reflectObject(JsonObject obj, int lx, int cy, String[] orderedKeys) {
        if (orderedKeys != null) {
            for (String key : orderedKeys) {
                if (obj.has(key)) {
                    cy = reflectField(key, obj.get(key), lx, cy);
                }
            }
            return cy;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            cy = reflectField(entry.getKey(), entry.getValue(), lx, cy);
        }
        return cy;
    }

    private int reflectField(String key, JsonElement val, int lx, int cy) {
        if (!val.isJsonPrimitive()) return cy;
        JsonPrimitive prim = val.getAsJsonPrimitive();
        String label = formatKey(key);

        if (prim.isBoolean()) {
            boolean current = prim.getAsBoolean();
            addToggle(label, () -> {
                JsonElement e = findRef(selectedClip != null ? selectedClip : (selectedKeyframe != null ? selectedKeyframe : script), key);
                return e != null && e.getAsBoolean();
            }, lx, cy, v -> {
                findParent(key).addProperty(key, v);
                if (onDirty != null) onDirty.run();
            });
            return cy + 18;
        }

        if (prim.isNumber()) {
            float current = prim.getAsFloat();
            boolean isInt = current == Math.floor(current) && !Float.isInfinite(current) && key.equals("version");
            if (isInt) {
                return cy;
            }
            addFloatField(label, () -> {
                JsonElement e = findRef(findTarget(), key);
                return e != null ? e.getAsFloat() : 0;
            }, lx, cy, -9999, 9999, 0.5f, v -> {
                findParent(key).addProperty(key, v);
                if (onDirty != null) onDirty.run();
            });
            return cy + 18;
        }

        if (prim.isString()) {
            UITextInput ti = new UITextInput(lx, cy, w - 12, 16, label,
                    () -> {
                        JsonElement e = findRef(findTarget(), key);
                        return e != null ? e.getAsString() : "";
                    },
                    v -> {
                        findParent(key).addProperty(key, v);
                        if (onDirty != null) onDirty.run();
                    });
            children.add(ti);
            return cy + 18;
        }

        return cy;
    }

    private int reflectFloatField(String label, int lx, int cy, java.util.function.Supplier<Float> src, Consumer<Float> sink) {
        addFloatField(label, src, lx, cy, 0, 9999, 1, sink);
        return cy + 18;
    }

    private JsonObject findTarget() {
        return selectedKeyframe != null ? selectedKeyframe : (selectedClip != null ? selectedClip : script);
    }

    private JsonObject findParent(String key) {
        if (selectedKeyframe != null && selectedKeyframe.has(key)) return selectedKeyframe;
        if (selectedClip != null && selectedClip.has(key)) return selectedClip;
        return script;
    }

    private static JsonElement findRef(JsonObject obj, String key) {
        return obj != null && obj.has(key) ? obj.get(key) : null;
    }

    private static String formatKey(String key) {
        return key.replace("_", " ");
    }

    private void addSectionLabel(String text, int lx, int cy) {
        children.add(new UILabel(lx, cy, text, 0xFF777777));
    }

    private int addField(String label, java.util.function.Supplier<String> source, int lx, int cy, Consumer<String> sink) {
        UITextInput ti = new UITextInput(lx, cy, w - 12, 16, label, source, sink);
        children.add(ti);
        return cy + 18;
    }

    private int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                              float min, float max, float step, Consumer<Float> sink) {
        UIFloatInput fi = new UIFloatInput(lx, cy, w - 12, 16, label, source, min, max, step, sink);
        children.add(fi);
        return cy + 18;
    }

    private int addToggle(String label, java.util.function.Supplier<Boolean> source, int lx, int cy, Consumer<Boolean> sink) {
        UIToggle tgl = new UIToggle(lx, cy, w - 12, 16, label, source, sink);
        children.add(tgl);
        return cy + 18;
    }

    public UITextInput getFocusedInput() {
        for (UIComponent c : children) {
            if (c instanceof UITextInput ti && ti.isFocused()) return ti;
        }
        return null;
    }

    @Override
    public void render(UIContext ctx) {
        ctx.graphics.fill(x, y, x + w, y + h, 0xFF1A1A1A);
        ctx.graphics.fill(x + w - 1, y, x + w, y + h, 0xFF2A2A2A);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
        for (UIComponent c : children) {
            c.render(ctx);
        }
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        EditorLogger.areaHit(EditorLogger.LEFT, "full_area", ctx.mouseX, ctx.mouseY, true);
        EditorLogger.areaHit(EditorLogger.LEFT, "mode_" + mode.name(), ctx.mouseX, ctx.mouseY, true);
        for (int i = children.size() - 1; i >= 0; i--) {
            UIComponent c = children.get(i);
            if (c.isHovered(ctx)) {
                if (c instanceof UIButton btn) {
                    EditorLogger.action(EditorLogger.LEFT, "BUTTON_CLICK", "label=" + btn.getLabel() + " mode=" + mode);
                } else if (c instanceof UIToggle tgl) {
                    EditorLogger.action(EditorLogger.LEFT, "TOGGLE_CLICK", "label=" + mode + " value=" + !tgl.isOn());
                }
            }
            if (c.mouseClicked(ctx)) return true;
        }
        return false;
    }

    @Override
    protected List<UIComponent> getChildren() {
        return children;
    }
}
