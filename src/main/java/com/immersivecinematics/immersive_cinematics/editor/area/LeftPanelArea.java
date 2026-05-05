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
    private Runnable onScriptChanged;

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
    public void setOnScriptChanged(Runnable r) { onScriptChanged = r; }

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
        UILabel header = new UILabel(x + 6, cy, "Scripts", 0xFFAAAAAA);
        children.add(header);
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

        cy = addField("Name", script.name, lx, cy, v -> { script.name = v; changed(); });
        cy = addField("Author", script.author, lx, cy, v -> { script.author = v; changed(); });
        cy = addField("Description", script.description, lx, cy, v -> { script.description = v; changed(); });

        cy += 4;
        addSectionLabel("Runtime", lx, cy); cy += 16;

        cy = addToggle("Block Keys", script.blockKeyboard, lx, cy, v -> { script.blockKeyboard = v; changed(); });
        cy = addToggle("Block Mouse", script.blockMouse, lx, cy, v -> { script.blockMouse = v; changed(); });
        cy = addToggle("Hide HUD", script.hideHud, lx, cy, v -> { script.hideHud = v; changed(); });
        cy = addToggle("Hide Arm", script.hideArm, lx, cy, v -> { script.hideArm = v; changed(); });
        cy = addToggle("Suppress Bob", script.suppressBob, lx, cy, v -> { script.suppressBob = v; changed(); });
        cy = addToggle("Skippable", script.skippable, lx, cy, v -> { script.skippable = v; changed(); });
        cy = addToggle("Hold at End", script.holdAtEnd, lx, cy, v -> { script.holdAtEnd = v; changed(); });
        cy = addToggle("Interruptible", script.interruptible, lx, cy, v -> { script.interruptible = v; changed(); });

        cy += 4;
        addSectionLabel("Duration", lx, cy); cy += 16;
        addFloatField("Total", script.totalDuration, lx, cy, 0, 9999, 1, v -> { script.totalDuration = v; changed(); });
    }

    private void buildClipProperties() {
        if (selectedClip == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Clip", lx, cy); cy += 16;
        cy = addFloatField("Start", selectedClip.startTime, lx, cy, 0, 9999, 0.5f, v -> { selectedClip.startTime = v; changed(); });
        cy = addFloatField("Duration", selectedClip.duration, lx, cy, 0.1f, 9999, 0.5f, v -> { selectedClip.duration = v; changed(); });

        cy += 4;
        addSectionLabel("Settings", lx, cy); cy += 16;
        cy = addDropdown("Transition", new String[]{"cut", "morph"}, "cut".equals(selectedClip.transition) ? 0 : 1,
                lx, cy, i -> { selectedClip.transition = i == 0 ? "cut" : "morph"; changed(); });
        cy = addDropdown("Interpolation", new String[]{"linear", "smooth"}, "linear".equals(selectedClip.interpolation) ? 0 : 1,
                lx, cy, i -> { selectedClip.interpolation = i == 0 ? "linear" : "smooth"; changed(); });
        cy = addDropdown("Position Mode", new String[]{"relative", "absolute"}, "relative".equals(selectedClip.positionMode) ? 0 : 1,
                lx, cy, i -> { selectedClip.positionMode = i == 0 ? "relative" : "absolute"; changed(); });
        cy = addToggle("Loop", selectedClip.loop, lx, cy, v -> { selectedClip.loop = v; changed(); });
    }

    private void buildKeyframeProperties() {
        if (selectedKeyframe == null) return;
        int cy = y + 6;
        int lx = x + 6;

        addSectionLabel("Keyframe", lx, cy); cy += 16;
        cy = addFloatField("Time", selectedKeyframe.time, lx, cy, 0, 9999, 0.1f, v -> { selectedKeyframe.time = v; changed(); });

        cy += 4;
        addSectionLabel("Position", lx, cy); cy += 16;
        cy = addFloatField("X", selectedKeyframe.position.x, lx, cy, -999, 999, 0.5f, v -> { selectedKeyframe.position.x = v; changed(); });
        cy = addFloatField("Y", selectedKeyframe.position.y, lx, cy, -999, 999, 0.5f, v -> { selectedKeyframe.position.y = v; changed(); });
        cy = addFloatField("Z", selectedKeyframe.position.z, lx, cy, -999, 999, 0.5f, v -> { selectedKeyframe.position.z = v; changed(); });

        cy += 4;
        addSectionLabel("Rotation", lx, cy); cy += 16;
        cy = addFloatField("Yaw", selectedKeyframe.yaw, lx, cy, -180, 180, 1, v -> { selectedKeyframe.yaw = v; changed(); });
        cy = addFloatField("Pitch", selectedKeyframe.pitch, lx, cy, -90, 90, 1, v -> { selectedKeyframe.pitch = v; changed(); });
        cy = addFloatField("Roll", selectedKeyframe.roll, lx, cy, -180, 180, 1, v -> { selectedKeyframe.roll = v; changed(); });

        cy += 4;
        addSectionLabel("Camera", lx, cy); cy += 16;
        cy = addFloatField("FOV", selectedKeyframe.fov, lx, cy, 1, 179, 1, v -> { selectedKeyframe.fov = v; changed(); });
        cy = addFloatField("Zoom", selectedKeyframe.zoom, lx, cy, 0.1f, 10, 0.1f, v -> { selectedKeyframe.zoom = v; changed(); });
    }

    private void addSectionLabel(String text, int lx, int cy) {
        children.add(new UILabel(lx, cy, text, 0xFF777777));
    }

    private int addField(String label, String value, int lx, int cy, Consumer<String> onChange) {
        // Simple inline text display for now
        UILabel lbl = new UILabel(lx, cy, label + ": " + value, 0xFFCCCCCC);
        children.add(lbl);
        return cy + 12;
    }

    private int addFloatField(String label, float value, int lx, int cy, float min, float max, float step,
                              Consumer<Float> onChange) {
        UIFloatInput fi = new UIFloatInput(lx, cy, w - 12, 16, label, value, min, max, step, onChange);
        children.add(fi);
        return cy + 18;
    }

    private int addToggle(String label, boolean value, int lx, int cy, Consumer<Boolean> onChange) {
        UIToggle tgl = new UIToggle(lx, cy, w - 12, 16, label, value, onChange);
        children.add(tgl);
        return cy + 18;
    }

    private int addDropdown(String label, String[] options, int initial, int lx, int cy, Consumer<Integer> onChange) {
        UIDropdown dd = new UIDropdown(lx, cy, w - 12, 16, Arrays.asList(options), initial, onChange);
        children.add(dd);
        return cy + 18;
    }

    private void changed() {
        if (onScriptChanged != null) onScriptChanged.run();
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
