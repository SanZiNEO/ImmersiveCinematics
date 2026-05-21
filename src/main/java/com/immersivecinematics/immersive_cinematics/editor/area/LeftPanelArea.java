package com.immersivecinematics.immersive_cinematics.editor.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.trigger.TriggerPanel;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import net.minecraft.client.resources.language.I18n;
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
    private float totalDuration;
    private String selectedTrackType = "CAMERA";
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

    public void setTotalDuration(float d) { totalDuration = d; }
    public void setSelectedTrackType(String t) { selectedTrackType = t; }
    public void setScriptFileNames(List<String> names) { scriptFileNames = names; }
    public void setOnOpenScript(Consumer<String> r) { onOpenScript = r; }
    public void setOnDeleteScript(Consumer<String> r) { onDeleteScript = r; }
    public void setOnNewScript(Runnable r) { onNewScript = r; }
    public void setOnNameChanged(Consumer<String> r) { onNameChanged = r; }
    public void setOnAuthorChanged(Consumer<String> r) { onAuthorChanged = r; }
    public void setOnDescChanged(Consumer<String> r) { onDescChanged = r; }
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

        UIButton newBtn = new UIButton(x + 4, cy, w - 12, 20, I18n.get("editor.script.new_button"), b -> {
            if (onNewScript != null) onNewScript.run();
        });
        newBtn.color(0xFF333333, 0xFF444444).textColor(0xFFAAAAAA);
        children.add(newBtn);
    }

    private void buildScriptProperties() {
        if (script == null) return;
        fillMetaDefaults(script);

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.triggers"), lx, cy, 0); cy += 12;
        JsonArray triggers = script.has("triggers") ? script.getAsJsonArray("triggers") : new JsonArray();
        if (!script.has("triggers")) script.add("triggers", triggers);
        int tpHeight = Math.min(Math.max(0, (y + h) - cy), 160);
        TriggerPanel tp = new TriggerPanel(lx, cy, w - 12, tpHeight, triggers, onDirty);
        children.add(tp);
        cy += tp.h + 6;

        addSectionLabel(I18n.get("editor.section.script_info"), lx, cy, 0); cy += 16;
        cy = reflectObject(script, lx, cy, new String[]{"id", "name", "author", "version", "description", "dimension"});
        cy += 4;
        addSectionLabel(I18n.get("editor.section.runtime"), lx, cy, 0); cy += 16;
        cy = reflectObject(script, lx, cy, new String[]{
            "block_keyboard", "block_mouse", "block_mob_ai",
            "hide_hud", "hide_arm", "hide_chat", "hide_scoreboard",
            "hide_action_bar", "hide_title", "hide_subtitles",
            "hide_hotbar", "hide_crosshair",
            "suppress_bob", "render_player_model",
            "pause_when_game_paused", "skippable", "hold_at_end", "interruptible"
        });
        cy += 4;
        addSectionLabel(I18n.get("editor.section.duration"), lx, cy, 0); cy += 16;
        addSectionLabel(I18n.get("editor.field.total_duration") + ": " + fmtDuration(totalDuration), lx, cy, 0); cy += 14;
    }

    private void buildClipProperties() {
        if (selectedClip == null) return;
        fillClipDefaults(selectedClip, selectedTrackType);

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.clip_properties"), lx, cy, 0); cy += 16;
        String[] keys = selectedClip.keySet().stream()
                .filter(k -> !"keyframes".equals(k))
                .toArray(String[]::new);
        cy = reflectObject(selectedClip, lx, cy, keys);
    }

    private void buildKeyframeProperties() {
        if (selectedKeyframe == null) return;
        fillKeyframeDefaults(selectedKeyframe);

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

        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel(I18n.get("editor.section.keyframe_properties"), lx, cy, 0); cy += 16;
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

    private static void addDefault(JsonObject obj, String key, Object val) {
        if (!obj.has(key)) {
            if (val instanceof Boolean b) obj.addProperty(key, b);
            else if (val instanceof Integer i) obj.addProperty(key, i);
            else if (val instanceof Float f) obj.addProperty(key, f);
            else if (val instanceof Double d) obj.addProperty(key, d);
            else if (val instanceof String s) obj.addProperty(key, s);
            else if (val instanceof JsonObject jo) obj.add(key, jo.deepCopy());
        }
    }

    private static void fillMetaDefaults(JsonObject meta) {
        addDefault(meta, "description", "");
        addDefault(meta, "block_mob_ai", false);
        addDefault(meta, "render_player_model", true);
        addDefault(meta, "pause_when_game_paused", true);
    }

    private static void fillClipDefaults(JsonObject clip, String trackType) {
        if (trackType == null) trackType = "CAMERA";
        switch (trackType.toUpperCase()) {
            case "CAMERA" -> {
                addDefault(clip, "transition", "cut");
                addDefault(clip, "transition_duration", 0.5f);
                addDefault(clip, "interpolation", "linear");
                addDefault(clip, "position_mode", "relative");
                addDefault(clip, "loop", false);
                addDefault(clip, "loop_count", -1);
            }
            case "LETTERBOX" -> {
                addDefault(clip, "enabled", true);
                addDefault(clip, "aspect_ratio", 2.35f);
                addDefault(clip, "fade_in", 0.5f);
                addDefault(clip, "fade_out", 0.5f);
            }
            case "AUDIO" -> {
                addDefault(clip, "sound", "");
                addDefault(clip, "volume", 1.0f);
                addDefault(clip, "pitch", 1.0f);
                addDefault(clip, "loop", false);
                addDefault(clip, "fade_in", 0.0f);
                addDefault(clip, "fade_out", 0.0f);
            }
            case "EVENT" -> {
                addDefault(clip, "event_type", "command");
            }
            case "MOD_EVENT" -> {
                addDefault(clip, "event_type", "");
            }
        }
    }

    private static void fillKeyframeDefaults(JsonObject kf) {
        addDefault(kf, "yaw", 0f);
        addDefault(kf, "pitch", 0f);
        addDefault(kf, "roll", 0f);
        addDefault(kf, "fov", 70f);
        addDefault(kf, "zoom", 1.0f);
        addDefault(kf, "dof", 0f);
        if (!kf.has("position")) {
            JsonObject pos = new JsonObject();
            pos.addProperty("dx", 0f);
            pos.addProperty("dy", 0f);
            pos.addProperty("dz", 0f);
            kf.add("position", pos);
        }
    }

    private static final Set<String> TRISTATE_KEYS = Set.of(
        "hide_chat", "hide_scoreboard", "hide_action_bar",
        "hide_title", "hide_subtitles", "hide_hotbar", "hide_crosshair"
    );

    private static final Set<String> CLIP_ENUM_KEYS = Set.of(
        "transition", "interpolation", "position_mode"
    );

    private static String cycleClipEnum(String key, String current) {
        return switch (key) {
            case "transition" -> current.equals("cut") ? "morph" : "cut";
            case "interpolation" -> current.equals("linear") ? "smooth" : "linear";
            case "position_mode" -> current.equals("relative") ? "absolute" : "relative";
            default -> current;
        };
    }

    /** Auto-reflect a JsonObject's fields as editable widgets (entry point). */
    private int reflectObject(JsonObject obj, int lx, int cy, String[] orderedKeys) {
        return reflectObjectAll(obj, lx, cy, 0, orderedKeys, null);
    }

    private int reflectObjectAll(JsonObject obj, int lx, int cy, int depth,
                                  String[] orderedKeys, String parentKey) {
        if (orderedKeys != null) {
            for (String key : orderedKeys) {
                if (CLIP_ENUM_KEYS.contains(key)) {
                    cy = reflectClipEnum(key, lx, cy, depth, obj);
                } else if (TRISTATE_KEYS.contains(key)) {
                    cy = reflectTristate(key, lx, cy, depth, obj);
                } else if (obj.has(key)) {
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
            build();
        });

        if (!hasValue) {
            btn.color(0x00, 0x443A3A3A).textColor(0xFF777777);
        } else if (value) {
            btn.color(0x00, 0x44224422).textColor(0xFF44AA44);
        } else {
            btn.color(0x00, 0x44442222).textColor(0xFFAA4444);
        }
        children.add(btn);
        return cy + 18;
    }

    private static String displayTristate(String label, boolean hasValue, boolean value) {
        if (!hasValue) return label + ": " + I18n.get("editor.enum.tristate.null");
        return value ? label + ": " + I18n.get("editor.enum.tristate.true") : label + ": " + I18n.get("editor.enum.tristate.false");
    }

    private int reflectClipEnum(String key, int lx, int cy, int depth, JsonObject parentObj) {
        int ix = lx + depth * 10;
        int iw = w - 12 - depth * 10;
        String label = formatKey(key);
        String current = parentObj.has(key) ? parentObj.get(key).getAsString() : cycleClipEnum(key, "");
        String enumTKey = "editor.enum." + key + "." + current;
        String displayVal = I18n.exists(enumTKey) ? I18n.get(enumTKey) : current;
        UIButton btn = new UIButton(ix, cy, iw, 16, label + ": " + displayVal, b -> {
            String next = cycleClipEnum(key, current);
            parentObj.addProperty(key, next);
            if ("position_mode".equals(key)) {
                convertKeyframePositions(parentObj, next);
            }
            if (onDirty != null) onDirty.run();
            build();
        });
        btn.color(0x00, 0x44333A3A).textColor(0xFFAAAAAA);
        children.add(btn);
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

    private int reflectFloatField(String label, int lx, int cy, java.util.function.Supplier<Float> src, Consumer<Float> sink) {
        addFloatField(label, src, lx, cy, 0, 9999, 1, sink);
        return cy + 18;
    }

    private static String fmtDuration(float s) {
        int m = (int)(s / 60);
        int sec = (int)(s % 60);
        return String.format("%d:%02d (%.1fs)", m, sec, s);
    }

    private static String formatKey(String key) {
        String k = "editor.field." + key;
        if (I18n.exists(k)) return I18n.get(k);
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

    public UIComponent getFocusedInput() {
        return findFocusedInput(children);
    }

    private static UIComponent findFocusedInput(List<UIComponent> list) {
        for (UIComponent c : list) {
            if (c instanceof UITextInput ti && ti.isFocused()) return c;
            if (c instanceof UIFloatInput fi && fi.isFocused()) return c;
            List<UIComponent> sub = c.getChildren();
            if (sub != null) {
                UIComponent found = findFocusedInput(sub);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void clearTextFocus() {
        clearTextFocus(children);
    }

    private static void clearTextFocus(List<UIComponent> list) {
        for (UIComponent c : list) {
            if (c instanceof UITextInput ti) ti.clearFocus();
            if (c instanceof UIFloatInput fi) fi.clearFocus();
            List<UIComponent> sub = c.getChildren();
            if (sub != null) clearTextFocus(sub);
        }
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
