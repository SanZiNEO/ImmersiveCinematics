package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.model.*;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.*;
import java.util.function.Consumer;

public class LeftPanelArea extends UIComponent {
    private final List<UIComponent> children = new ArrayList<>();

    public enum PanelMode { SCRIPT_LIST, SCRIPT_PROPERTIES, CLIP_PROPERTIES, KEYFRAME_PROPERTIES }
    private PanelMode mode = PanelMode.SCRIPT_PROPERTIES;

    private EditorScript script;
    private List<String> scriptFileNames = new ArrayList<>();
    private EditorClip selectedClip;
    private EditorKeyframe selectedKeyframe;

    private Consumer<String> onOpenScript;
    private Consumer<String> onDeleteScript;
    private Runnable onNewScript;
    private Consumer<String> onNameChanged;
    private Consumer<String> onAuthorChanged;
    private Consumer<String> onDescChanged;
    private Consumer<Float> onDurationChanged;
    private Consumer<String> onBehaviorFlag;

    public LeftPanelArea(int x, int y, int w, int h) {
        super(x, y, w, h);
    }

    public void setMode(PanelMode m) { mode = m; build(); }
    public PanelMode getMode() { return mode; }
    public void setScript(EditorScript s) { script = s; }
    public void setSelectedClip(EditorClip c) { selectedClip = c; }
    public void setSelectedKeyframe(EditorKeyframe k) { selectedKeyframe = k; }
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

        cy = addField("Name", script.name, lx, cy, onNameChanged);
        cy = addField("Author", script.author, lx, cy, onAuthorChanged);
        cy = addField("Description", script.description, lx, cy, onDescChanged);

        cy += 4;
        addSectionLabel("Runtime", lx, cy); cy += 16;

        cy = addToggle("Block Keys", script.blockKeyboard, lx, cy, "blockKeyboard");
        cy = addToggle("Block Mouse", script.blockMouse, lx, cy, "blockMouse");
        cy = addToggle("Hide HUD", script.hideHud, lx, cy, "hideHud");
        cy = addToggle("Hide Arm", script.hideArm, lx, cy, "hideArm");
        cy = addToggle("Suppress Bob", script.suppressBob, lx, cy, "suppressBob");
        cy = addToggle("Skippable", script.skippable, lx, cy, "skippable");
        cy = addToggle("Hold at End", script.holdAtEnd, lx, cy, "holdAtEnd");
        cy = addToggle("Interruptible", script.interruptible, lx, cy, "interruptible");

        cy += 4;
        addSectionLabel("Duration", lx, cy); cy += 16;
        addFloatField("Total", script.totalDuration, lx, cy, 0, 9999, 1, onDurationChanged);
    }

    private void buildClipProperties() {
        if (selectedClip == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Clip", lx, cy); cy += 16;
        cy = addFloatField("Start", selectedClip.startTime, lx, cy, 0, 9999, 0.5f, null);
        cy = addFloatField("Duration", selectedClip.duration, lx, cy, 0.1f, 9999, 0.5f, null);

        cy += 4;
        addSectionLabel("Settings", lx, cy); cy += 16;
        cy = addDropdown("Transition", new String[]{"cut", "morph"}, "cut".equals(selectedClip.transition) ? 0 : 1,
                lx, cy, i -> { selectedClip.transition = i == 0 ? "cut" : "morph"; });
        cy = addDropdown("Interpolation", new String[]{"linear", "smooth"}, "linear".equals(selectedClip.interpolation) ? 0 : 1,
                lx, cy, i -> { selectedClip.interpolation = i == 0 ? "linear" : "smooth"; });
        cy = addDropdown("Position Mode", new String[]{"relative", "absolute"}, "relative".equals(selectedClip.positionMode) ? 0 : 1,
                lx, cy, i -> { selectedClip.positionMode = i == 0 ? "relative" : "absolute"; });
        cy = addToggle("Loop", selectedClip.loop, lx, cy, "loop");
    }

    private void buildKeyframeProperties() {
        if (selectedKeyframe == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Keyframe", lx, cy); cy += 16;
        cy = addFloatField("Time", selectedKeyframe.time, lx, cy, 0, 9999, 0.1f, null);

        cy += 4;
        addSectionLabel("Position", lx, cy); cy += 16;
        cy = addFloatField("X", selectedKeyframe.position.x, lx, cy, -999, 999, 0.5f, null);
        cy = addFloatField("Y", selectedKeyframe.position.y, lx, cy, -999, 999, 0.5f, null);
        cy = addFloatField("Z", selectedKeyframe.position.z, lx, cy, -999, 999, 0.5f, null);

        cy += 4;
        addSectionLabel("Rotation", lx, cy); cy += 16;
        cy = addFloatField("Yaw", selectedKeyframe.yaw, lx, cy, -180, 180, 1, null);
        cy = addFloatField("Pitch", selectedKeyframe.pitch, lx, cy, -90, 90, 1, null);
        cy = addFloatField("Roll", selectedKeyframe.roll, lx, cy, -180, 180, 1, null);

        cy += 4;
        addSectionLabel("Camera", lx, cy); cy += 16;
        cy = addFloatField("FOV", selectedKeyframe.fov, lx, cy, 1, 179, 1, null);
        cy = addFloatField("Zoom", selectedKeyframe.zoom, lx, cy, 0.1f, 10, 0.1f, null);
    }

    private void addSectionLabel(String text, int lx, int cy) {
        children.add(new UILabel(lx, cy, text, 0xFF777777));
    }

    private int addField(String label, String value, int lx, int cy, Consumer<String> onChange) {
        UITextInput ti = new UITextInput(lx, cy, w - 12, 16, label, value, onChange);
        children.add(ti);
        return cy + 18;
    }

    private int addFloatField(String label, float value, int lx, int cy, float min, float max, float step,
                              Consumer<Float> onChange) {
        UIFloatInput fi = new UIFloatInput(lx, cy, w - 12, 16, label, value, min, max, step, onChange);
        children.add(fi);
        return cy + 18;
    }

    private int addToggle(String label, boolean value, int lx, int cy, String flag) {
        UIToggle tgl = new UIToggle(lx, cy, w - 12, 16, label, value,
                v -> { if (flag.equals("loop") && selectedClip != null) selectedClip.loop = v; else if (onBehaviorFlag != null) onBehaviorFlag.accept(flag + "=" + v); });
        children.add(tgl);
        return cy + 18;
    }

    private int addDropdown(String label, String[] options, int initial, int lx, int cy, Consumer<Integer> onChange) {
        UIDropdown dd = new UIDropdown(lx, cy, w - 12, 16, Arrays.asList(options), initial, onChange);
        children.add(dd);
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
    protected List<UIComponent> getChildren() {
        return children;
    }
}
