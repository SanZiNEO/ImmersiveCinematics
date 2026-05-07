package com.immersivecinematics.immersive_cinematics.editor.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.trigger.TriggerPanel;
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
    private boolean dataDirty = true;

    private int scrollY;
    private int maxScroll;
    private int contentHeight;
    private boolean scrollbarGrabbed;
    private int scrollbarGrabOffset;

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
        computeContentHeightAndClampScroll();
    }

    private void buildScriptList() {
        int cy = y + 6;
        children.add(new UILabel(x + 6, cy, "Scripts", 0xFFAAAAAA));
        cy += 16;

        for (String name : scriptFileNames) {
            UIButton itemBtn = new UIButton(x + 4, cy, w - 12, 18, name, b -> {
                if (onOpenScript != null) onOpenScript.accept(name);
            });
            itemBtn.color(0x00, 0x443A3A3A).textColor(0xFFAAAAAA);
            children.add(itemBtn);
            cy += 20;
        }

        UIButton newBtn = new UIButton(x + 4, cy, w - 12, 20, "+ New Script", b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(0xFF333333, 0xFF444444).textColor(0xFFAAAAAA);
        children.add(newBtn);
    }

    private void buildScriptProperties() {
        if (script == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Triggers", lx, cy, 0); cy += 12;
        JsonArray triggers = script.has("triggers") ? script.getAsJsonArray("triggers") : new JsonArray();
        if (!script.has("triggers")) script.add("triggers", triggers);
        int tpHeight = Math.min(Math.max(0, (y + h) - cy), 160);
        TriggerPanel tp = new TriggerPanel(lx, cy, w - 12, tpHeight, triggers, onDirty);
        children.add(tp);
        cy += tp.h + 6;

        addSectionLabel("Script Info", lx, cy, 0); cy += 16;
        cy = reflectObject(script, lx, cy, new String[]{"id", "name", "author", "version", "description", "dimension"});
        cy += 4;
        addSectionLabel("Runtime", lx, cy, 0); cy += 16;
        cy = reflectObject(script, lx, cy, new String[]{"block_keyboard", "block_mouse", "hide_hud", "hide_arm", "suppress_bob", "skippable", "hold_at_end", "interruptible"});
        cy += 4;
        addSectionLabel("Duration", lx, cy, 0); cy += 16;
        cy = reflectFloatField("total_duration", lx, cy, () -> script.has("total_duration") ? script.get("total_duration").getAsFloat() : 0, v -> { script.addProperty("total_duration", v); if (onDirty != null) onDirty.run(); });
    }

    private void buildClipProperties() {
        if (selectedClip == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Clip Properties", lx, cy, 0); cy += 16;
        String[] keys = selectedClip.keySet().stream()
                .filter(k -> !"keyframes".equals(k))
                .toArray(String[]::new);
        cy = reflectObject(selectedClip, lx, cy, keys);
    }

    private void buildKeyframeProperties() {
        if (selectedKeyframe == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Keyframe Properties", lx, cy, 0); cy += 16;
        cy = reflectObject(selectedKeyframe, lx, cy, null);
    }

    private void computeContentHeightAndClampScroll() {
        int bottom = y;
        for (UIComponent c : children) {
            bottom = Math.max(bottom, getComponentBottom(c));
        }
        contentHeight = Math.max(0, bottom - y);

        boolean shouldScroll = contentHeight > h * 0.8f;
        if (!shouldScroll) {
            scrollY = 0;
            maxScroll = 0;
            return;
        }
        maxScroll = Math.min(contentHeight - h, (int)(h * 0.4f));
        maxScroll = Math.max(0, maxScroll);
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
    private int reflectObject(JsonObject obj, int lx, int cy, String[] orderedKeys) {
        return reflectObjectAll(obj, lx, cy, 0, orderedKeys, null);
    }

    private int reflectObjectAll(JsonObject obj, int lx, int cy, int depth,
                                  String[] orderedKeys, String parentKey) {
        if (orderedKeys != null) {
            for (String key : orderedKeys) {
                if (obj.has(key)) {
                    cy = reflectField(key, obj.get(key), lx, cy, depth, obj, parentKey);
                }
            }
            return cy;
        }
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            cy = reflectField(entry.getKey(), entry.getValue(), lx, cy, depth, obj, parentKey);
        }
        return cy;
    }

    private int reflectField(String key, JsonElement val, int lx, int cy,
                              int depth, JsonObject parentObj, String parentKey) {
        if (val.isJsonObject()) {
            addSectionLabel(formatKey(key) + ":", lx, cy, depth);
            cy += 12;
            cy = reflectObjectAll(val.getAsJsonObject(), lx, cy, depth + 1, null, key);
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
                    cy = reflectObjectAll(el.getAsJsonObject(), lx, cy, depth + 2, null, key);
                }
            }
            return cy;
        }

        if (!val.isJsonPrimitive()) return cy;
        return reflectPrimitive(key, val, lx, cy, depth, parentObj);
    }

    private int reflectPrimitive(String key, JsonElement val, int lx, int cy,
                                  int depth, JsonObject parentObj) {
        JsonPrimitive prim = val.getAsJsonPrimitive();
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;

        String label = formatKey(key);
        if (parentObj == selectedKeyframe && "position".equals(findKeyOf(parentObj, selectedKeyframe))) {
            label = remapPositionLabel(key, label);
        }

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
            children.add(ti);
            return cy + 18;
        }
        return cy;
    }

    private String findKeyOf(JsonObject child, JsonObject parent) {
        for (Map.Entry<String, JsonElement> e : parent.entrySet()) {
            if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject() == child)
                return e.getKey();
        }
        return null;
    }

    private String remapPositionLabel(String key, String defaultLabel) {
        if (selectedClip != null && selectedClip.has("position_mode")) {
            String mode = selectedClip.get("position_mode").getAsString();
            if ("absolute".equals(mode)) {
                if ("dx".equals(key)) return "x";
                if ("dy".equals(key)) return "y";
                if ("dz".equals(key)) return "z";
            }
        }
        return defaultLabel;
    }

    private int reflectFloatField(String label, int lx, int cy, java.util.function.Supplier<Float> src, Consumer<Float> sink) {
        addFloatField(label, src, lx, cy, 0, 9999, 1, sink);
        return cy + 18;
    }

    private static String formatKey(String key) {
        return key.replace("_", " ");
    }

    private void addSectionLabel(String text, int lx, int cy, int depth) {
        children.add(new UILabel(lx + depth * 10, cy, text, 0xFF777777));
    }

    private int addField(String label, java.util.function.Supplier<String> source, int lx, int cy, Consumer<String> sink) {
        UITextInput ti = new UITextInput(lx, cy, w - 12, 16, label, source, sink);
        children.add(ti);
        return cy + 18;
    }

    private int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                              float min, float max, float step, Consumer<Float> sink) {
        return addFloatField(label, source, lx, cy, min, max, step, sink, w - 12);
    }

    private int addFloatField(String label, java.util.function.Supplier<Float> source, int lx, int cy,
                              float min, float max, float step, Consumer<Float> sink, int width) {
        UIFloatInput fi = new UIFloatInput(lx, cy, width, 16, label, source, min, max, step, sink);
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

        ctx.graphics.enableScissor(x, y, x + w, y + h);
        var pose = ctx.graphics.pose();
        pose.pushPose();
        pose.translate(0, -scrollY, 0);
        for (UIComponent c : children) {
            c.render(ctx);
        }
        pose.popPose();
        ctx.graphics.disableScissor();

        if (maxScroll > 0) {
            int sbX = x + w - 4;
            int sbH = h;
            ctx.graphics.fill(sbX, y, sbX + 4, y + sbH, 0xFF222222);
            float thumbRatio = (float)h / contentHeight;
            int thumbH = Math.max(8, (int)(sbH * thumbRatio));
            int thumbY = y + (int)((float)scrollY / maxScroll * (sbH - thumbH));
            ctx.graphics.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, 0xFF777777);
            ctx.graphics.renderOutline(sbX, thumbY, 4, thumbH, 0xFF555555);
        }

        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
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
                    scrollY = (int)((float)(ctx.mouseY - y) / h * maxScroll);
                    clampScrollY();
                }
                return true;
            }
        }

        EditorLogger.areaHit(EditorLogger.LEFT, "full_area", ctx.mouseX, ctx.mouseY, true);
        EditorLogger.areaHit(EditorLogger.LEFT, "mode_" + mode.name(), ctx.mouseX, ctx.mouseY, true);

        int origY = ctx.mouseY;
        ctx.mouseY += scrollY;
        for (int i = children.size() - 1; i >= 0; i--) {
            UIComponent c = children.get(i);
            if (c.isHovered(ctx)) {
                if (c instanceof UIButton btn) {
                    EditorLogger.action(EditorLogger.LEFT, "BUTTON_CLICK", "label=" + btn.getLabel() + " mode=" + mode);
                } else if (c instanceof UIToggle tgl) {
                    EditorLogger.action(EditorLogger.LEFT, "TOGGLE_CLICK", "label=" + mode + " value=" + !tgl.isOn());
                }
            }
            if (c.mouseClicked(ctx)) { ctx.mouseY = origY; return true; }
        }
        ctx.mouseY = origY;
        return false;
    }

    @Override
    public boolean mouseDragged(UIContext ctx) {
        if (scrollbarGrabbed && maxScroll > 0) {
            float thumbRatio = (float)h / contentHeight;
            int thumbH = Math.max(8, (int)(h * thumbRatio));
            int trackSpace = h - thumbH;
            if (trackSpace > 0) {
                scrollY = (int)((float)(ctx.mouseY - y - scrollbarGrabOffset) / trackSpace * maxScroll);
                clampScrollY();
            }
            return true;
        }
        int origY = ctx.mouseY;
        ctx.mouseY += scrollY;
        boolean result = false;
        List<UIComponent> ch = getChildren();
        if (ch != null) {
            for (int i = ch.size() - 1; i >= 0; i--) {
                if (ch.get(i).mouseDragged(ctx)) { result = true; break; }
            }
        }
        ctx.mouseY = origY;
        return result;
    }

    @Override
    public boolean mouseReleased(UIContext ctx) {
        scrollbarGrabbed = false;
        return super.mouseReleased(ctx);
    }

    @Override
    public boolean mouseScrolled(UIContext ctx, double scroll) {
        if (!visible || !ctx.isMouseIn(x, y, w, h)) return false;
        if (maxScroll > 0) {
            scrollY -= (int)(scroll * 20);
            clampScrollY();
            return true;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).mouseScrolled(ctx, scroll)) return true;
        }
        return false;
    }

    private void clampScrollY() {
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
    }

    @Override
    public List<UIComponent> getChildren() {
        return children;
    }
}
