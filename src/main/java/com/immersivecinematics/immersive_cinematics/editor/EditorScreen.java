package com.immersivecinematics.immersive_cinematics.editor;

import com.immersivecinematics.immersive_cinematics.editor.area.*;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.debug.RawInputLogger;
import com.immersivecinematics.immersive_cinematics.editor.model.*;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class EditorScreen extends Screen {

    private final EditorCore core;
    private final Path scriptsDir;
    private final List<String> scriptFileNames = new ArrayList<>();

    private MenuBarArea menuBar;
    private LeftPanelArea leftPanel;
    private PreviewArea preview;
    private TimelineArea timeline;

    private String scriptFilePath;
    private boolean playing;
    private float playbackTime;
    private long lastPlayTick;
    private boolean firstInit = true;
    private boolean scriptDirty;
    private String activeArea = "none";
    private int mouseDownX, mouseDownY;

    private final EditorOutput editorOutput;

    // ── Loop monitoring ─────────────────────────────────────────
    private int renderCycle;
    private String renderPhase = "idle";
    private long lastRenderLog;

    public EditorScreen(EditorBridge bridge, Path scriptsDir) {
        super(Component.literal("Cinematic Editor"));
        this.scriptsDir = scriptsDir;
        this.core = new EditorCore();
        this.editorOutput = new EditorOutput(bridge);
        core.setOnChanged(() -> { scriptDirty = true; });
        core.setOnSelectionChanged((clip, kf) -> {
            try {
                if (leftPanel == null) return;
                if (clip == null) leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
                else if (kf == null) leftPanel.setMode(LeftPanelArea.PanelMode.CLIP_PROPERTIES);
                else leftPanel.setMode(LeftPanelArea.PanelMode.KEYFRAME_PROPERTIES);
                syncPanels();
            } catch (Exception e) {
                EditorLogger.error(EditorLogger.SCREEN, "onSelectionChanged crashed", e);
            }
        });
    }

    @Override
    protected void init() {
        int menuH = 24;
        int leftW = (int) (width * 0.2f);
        int timelineH = (int) (height * 0.22f);
        int previewH = height - menuH - timelineH;

        menuBar = new MenuBarArea(0, 0, width, menuH);
        leftPanel = new LeftPanelArea(0, menuH, leftW, previewH);
        preview = new PreviewArea(leftW, menuH, width - leftW, previewH);
        timeline = new TimelineArea(0, menuH + previewH, width, timelineH);

        RawInputLogger.enable();

        EditorLogger.areaBoundaries(EditorLogger.SCREEN,
                "MenuBar=(0,0," + width + "," + menuH + ")"
                        + " LeftPanel=(0," + menuH + "," + leftW + "," + previewH + ")"
                        + " Preview=(" + leftW + "," + menuH + "," + (width - leftW) + "," + previewH + ")"
                        + " Timeline=(0," + (menuH + previewH) + "," + width + "," + timelineH + ")");

        wireMenu();
        wireTimeline();
        wirePreview();
        wireLeftPanel();

        leftPanel.setDirtyCallback(() -> scriptDirty = true);

        if (firstInit) {
            firstInit = false;
            refreshScriptList();
            core.newScript();
            syncPanels();
            leftPanel.build();
        } else {
            syncPanels();
            leftPanel.build();
        }
    }

    private void wireMenu() {
        menuBar.setOnNewScript(() -> {
            EditorLogger.action(EditorLogger.SCREEN, "NEW_SCRIPT", "from menu");
            core.newScript(); syncPanels();
        });
        menuBar.setOnSaveScript(() -> {
            EditorLogger.action(EditorLogger.SCREEN, "SAVE", "from menu");
            saveScript();
        });
        menuBar.setOnToggleList(this::toggleScriptList);
    }

    private void wireTimeline() {
        timeline.setCore(core);
        timeline.setOnClickAtTime(t -> {
            EditorLogger.playhead(EditorLogger.SCREEN, t, 0, "ruler_click");
            float prev = playbackTime;
            playbackTime = t;
            editorOutput.setTime(t);
            EditorLogger.dataSync(EditorLogger.SCREEN, "playhead", t, prev);
            syncPanels();
        });
        timeline.setOnClickClip(clip -> {
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_CLIP", "startTime=" + clip.startTime);
            core.selectClip(clip);
        });
        timeline.setOnClickKeyframe((kf, clip) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_KEYFRAME", "time=" + kf.time + " clipStart=" + clip.startTime);
            core.selectKeyframe(kf);
        });
        timeline.setOnMoveClip((clip, ns) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_CLIP", "from=" + clip.startTime + " to=" + ns);
            core.moveClip(clip, ns);
        });
        timeline.setOnResizeLeft((clip, ns) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_LEFT", "clipStart=" + clip.startTime + " newStart=" + ns);
            core.resizeClipLeft(clip, ns);
        });
        timeline.setOnResizeRight((clip, ne) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_RIGHT", "clipEnd=" + clip.endTime() + " newEnd=" + ne);
            core.resizeClipRight(clip, ne);
        });
        timeline.setOnMoveKeyframe((kf, clip, nt) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_KEYFRAME", "from=" + kf.time + " to=" + nt + " clipStart=" + clip.startTime);
            core.moveKeyframe(kf, clip, nt);
        });
        timeline.setOnToolAddClip(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_CLIP", "");
            core.addClip();
        });
        timeline.setOnToolDeleteClip(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_DELETE_CLIP",
                    "selected=" + (core.getSelectedClip() != null ? core.getSelectedClip().startTime : "null"));
            core.deleteSelectedClip();
        });
        timeline.setOnToolAddKeyframe(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_KEYFRAME", "at=" + String.format("%.3f", playbackTime));
            core.addKeyframeAt(playbackTime);
        });
        timeline.setOnToolDeleteKeyframe(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_DELETE_KEYFRAME",
                    "selected=" + (core.getSelectedKeyframe() != null ? core.getSelectedKeyframe().time : "null"));
            core.deleteSelectedKeyframe();
        });
        timeline.setOnToolSnap(() -> {
            boolean before = core.isAutoSnap();
            core.toggleAutoSnap();
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_SNAP", "before=" + before + " after=" + core.isAutoSnap());
        });
    }

    private void wirePreview() {
        preview.setOnPlay(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "PLAY", "btn");
            play();
        });
        preview.setOnPause(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "PAUSE", "btn");
            pause();
        });
        preview.setOnStop(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "STOP", "btn");
            stop();
        });
    }

    private void wireLeftPanel() {
        leftPanel.setOnOpenScript(fileName -> {
            EditorLogger.action(EditorLogger.LEFT, "OPEN_SCRIPT", "file=" + fileName);
            openScript(fileName);
        });
        leftPanel.setOnDeleteScript(fileName -> {
            EditorLogger.action(EditorLogger.LEFT, "DELETE_SCRIPT", "file=" + fileName);
            deleteScript(fileName);
        });
        leftPanel.setOnNewScript(() -> {
            EditorLogger.action(EditorLogger.LEFT, "NEW_SCRIPT", "from left panel");
            core.newScript(); syncPanels();
        });
        leftPanel.setOnNameChanged(v -> {
            EditorLogger.state(EditorLogger.LEFT, "scriptName", core.getScript().name, v);
            core.setScriptName(v);
        });
        leftPanel.setOnAuthorChanged(v -> {
            EditorLogger.state(EditorLogger.LEFT, "scriptAuthor", core.getScript().author, v);
            core.setScriptAuthor(v);
        });
        leftPanel.setOnDescChanged(v -> core.setScriptDescription(v));
        leftPanel.setOnDurationChanged(v -> {
            EditorLogger.state(EditorLogger.LEFT, "totalDuration", core.getScript().totalDuration, v);
            core.setTotalDuration(v);
        });
        leftPanel.setOnBehaviorFlag(s -> {
            String[] parts = s.split("=");
            if (parts.length == 2) {
                EditorLogger.action(EditorLogger.LEFT, "BEHAVIOR_FLAG", parts[0] + "=" + parts[1]);
                core.setBehaviorFlag(parts[0], Boolean.parseBoolean(parts[1]));
            }
        });
    }

    private void flush() {
        syncPanels();
    }

    private void syncPanels() {
        menuBar.setScriptName(core.getFileName());
        leftPanel.setScript(core.getScript());
        leftPanel.setSelectedClip(core.getSelectedClip());
        leftPanel.setSelectedKeyframe(core.getSelectedKeyframe());
        timeline.setPlayheadTime(playbackTime);
        preview.setCurrentTime(playbackTime);

        EditorLogger.sync(EditorLogger.SCREEN, "panels",
                "playbackTime=" + String.format("%.3f", playbackTime)
                        + " scriptDirty=" + scriptDirty
                        + " selectedClip=" + (core.getSelectedClip() != null ? core.getSelectedClip().startTime : "null")
                        + " selectedKf=" + (core.getSelectedKeyframe() != null ? core.getSelectedKeyframe().time : "null"));
    }

    private void toggleScriptList() {
        LeftPanelArea.PanelMode prev = leftPanel.getMode();
        if (prev == LeftPanelArea.PanelMode.SCRIPT_LIST)
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
        else leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_LIST);
        EditorLogger.action(EditorLogger.SCREEN, "TOGGLE_LIST", "from=" + prev + " to=" + leftPanel.getMode());
        syncPanels();
    }

    private void saveScript() {
        try {
            Files.createDirectories(scriptsDir);
            Path dest = scriptFilePath != null ? Paths.get(scriptFilePath)
                    : scriptsDir.resolve(core.getFileName() + ".json");
            Files.writeString(dest, core.toJson());
            String savedPath = dest.toString();
            if (scriptFilePath == null) scriptFilePath = savedPath;
            refreshScriptList();
            editorOutput.pushScript(core.toJson());
            EditorLogger.action(EditorLogger.SCREEN, "SAVE_SCRIPT", "path=" + savedPath + " success=true");
        } catch (IOException e) {
            EditorLogger.action(EditorLogger.SCREEN, "SAVE_SCRIPT", "path=" + scriptFilePath + " success=false error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void openScript(String fileName) {
        try {
            String json = Files.readString(scriptsDir.resolve(fileName));
            core.loadFromJson(json);
            core.setFileName(fileName.replace(".json", ""));
            scriptFilePath = scriptsDir.resolve(fileName).toString();
            playbackTime = 0;
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
            syncPanels();
            EditorLogger.action(EditorLogger.SCREEN, "OPEN_SCRIPT", "file=" + fileName + " success=true");
        } catch (IOException e) {
            EditorLogger.action(EditorLogger.SCREEN, "OPEN_SCRIPT", "file=" + fileName + " success=false error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void deleteScript(String fileName) {
        try {
            Files.deleteIfExists(scriptsDir.resolve(fileName));
            refreshScriptList();
            EditorLogger.action(EditorLogger.SCREEN, "DELETE_SCRIPT", "file=" + fileName + " success=true");
        } catch (IOException e) {
            EditorLogger.action(EditorLogger.SCREEN, "DELETE_SCRIPT", "file=" + fileName + " success=false error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void refreshScriptList() {
        scriptFileNames.clear();
        if (Files.exists(scriptsDir)) {
            try (Stream<Path> files = Files.list(scriptsDir)) {
                files.filter(f -> f.toString().endsWith(".json"))
                        .map(f -> f.getFileName().toString()).sorted().forEach(scriptFileNames::add);
            } catch (IOException ignored) {}
        }
        leftPanel.setScriptFileNames(scriptFileNames);
        if (leftPanel.getMode() == LeftPanelArea.PanelMode.SCRIPT_LIST) leftPanel.build();
    }

    private void play() {
        playing = true; lastPlayTick = System.currentTimeMillis();
        editorOutput.play();
        EditorLogger.marker(EditorLogger.SCREEN, "PLAY START time=" + String.format("%.3f", playbackTime));
    }
    private void pause() {
        playing = false;
        editorOutput.pause();
        EditorLogger.marker(EditorLogger.SCREEN, "PAUSE time=" + String.format("%.3f", playbackTime));
    }
    private void stop() {
        playing = false; playbackTime = 0;
        editorOutput.stop();
        syncPanels();
        EditorLogger.marker(EditorLogger.SCREEN, "STOP");
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderCycle++;
        String cycleStr = "cycle=" + renderCycle;
        EditorLogger.outRaw(EditorLogger.SCREEN, "LOOP", "RENDER_START " + cycleStr);

        long t0 = System.nanoTime();

        // ── Phase 1: context + playback ─────────────────────────
        renderPhase = "ctx_init";
        UIContext ctx;
        try {
            ctx = new UIContext(guiGraphics, font, width, height, partialTick, mouseX, mouseY);
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=ctx_init " + cycleStr, e);
            return;
        }

        renderPhase = "playback";
        try {
            if (playing) {
                long now = System.currentTimeMillis();
                playbackTime += (now - lastPlayTick) / 1000f;
                lastPlayTick = now;
                if (playbackTime >= core.getScript().totalDuration) {
                    playbackTime = core.getScript().totalDuration;
                    playing = false;
                    editorOutput.pause();
                    EditorLogger.action(EditorLogger.SCREEN, "PLAYBACK", "ended at=" + String.format("%.3f", playbackTime));
                }
                timeline.setPlayheadTime(playbackTime);
                preview.setCurrentTime(playbackTime);
                EditorLogger.dataSync(EditorLogger.SCREEN, "playbackTime", playbackTime, timeline.getPlayheadTime());
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=playback " + cycleStr, e);
            return;
        }

        // ── Phase 2-5: render areas ─────────────────────────────
        renderPhase = "menuBar";
        try { menuBar.render(ctx); } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=menuBar " + cycleStr, e); return;
        }

        renderPhase = "leftPanel";
        try { leftPanel.render(ctx); } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=leftPanel " + cycleStr, e); return;
        }

        renderPhase = "preview";
        try { preview.render(ctx); } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=preview " + cycleStr, e); return;
        }

        renderPhase = "timeline";
        try { timeline.render(ctx); } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=timeline " + cycleStr, e); return;
        }

        // ── Done ─────────────────────────────────────────────────
        renderPhase = "done";
        long elapsedNs = System.nanoTime() - t0;
        long now = System.currentTimeMillis();

        if (now - lastRenderLog >= 1000) {
            lastRenderLog = now;
            EditorLogger.outRaw(EditorLogger.SCREEN, "LOOP",
                    "RENDER_TICK cycle=" + renderCycle + " elapsed=" + (elapsedNs / 1_000_000) + "ms"
                            + " playing=" + playing + " time=" + String.format("%.3f", playbackTime)
                            + " scriptDirty=" + scriptDirty);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        UIContext ctx = makeCtx(mx, my);
        int imx = (int) mx, imy = (int) my;
        mouseDownX = imx; mouseDownY = imy;
        EditorLogger.mousePressed(EditorLogger.SCREEN, button, imx, imy, activeArea);

        try {
            if (menuBar.mouseClicked(ctx)) { activeArea = "MenuBar"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "MenuBar", true); flush(); return true; }
            if (leftPanel.mouseClicked(ctx)) { activeArea = "LeftPanel"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "LeftPanel", true); flush(); return true; }
            if (preview.mouseClicked(ctx)) { activeArea = "Preview"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "Preview", true); flush(); return true; }
            if (timeline.mouseClicked(ctx)) { activeArea = "Timeline"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "Timeline", true); flush(); return true; }
            EditorLogger.areaNoHit(EditorLogger.SCREEN, imx, imy);
            flush();
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "mouseClicked crashed button=" + button, e);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        try {
            UIContext ctx = makeCtx(mx, my);
            int imx = (int) mx, imy = (int) my;
            boolean timelineDrag = timeline.mouseDragged(ctx);
            if (timelineDrag) {
                EditorLogger.mouseDrag(EditorLogger.SCREEN, imx, imy, "Timeline", timeline.xToTime(imx));
            } else {
                if (!EditorLogger.throttle("mouseDragged", 1000)) {
                    EditorLogger.mouseMove(EditorLogger.SCREEN, imx, imy, activeArea, true);
                }
            }
            return timelineDrag;
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "mouseDragged crashed", e);
            return false;
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        try {
            UIContext ctx = makeCtx(mx, my);
            int imx = (int) mx, imy = (int) my;
            EditorLogger.mouseReleased(EditorLogger.SCREEN, button, imx, imy, mouseDownX, mouseDownY, activeArea);
            menuBar.mouseReleased(ctx); leftPanel.mouseReleased(ctx);
            preview.mouseReleased(ctx); timeline.mouseReleased(ctx);
            flush();
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "mouseReleased crashed", e);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scroll) {
        try {
            UIContext ctx = makeCtx(mx, my);
            int imx = (int) mx, imy = (int) my;
            if (timeline.mouseScrolled(ctx, scroll)) {
                EditorLogger.mouseScroll(EditorLogger.SCREEN, scroll, imx, imy, "Timeline");
                return true;
            }
            if (leftPanel.mouseScrolled(ctx, scroll)) {
                EditorLogger.mouseScroll(EditorLogger.SCREEN, scroll, imx, imy, "LeftPanel");
                return true;
            }
            EditorLogger.mouseScroll(EditorLogger.SCREEN, scroll, imx, imy, "NO_HIT");
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "mouseScrolled crashed", e);
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            EditorLogger.keyPress(EditorLogger.SCREEN, "keyPressed", keyCode,
                    "mods=" + modifiers + " shift=" + hasShiftDown() + " ctrl=" + hasControlDown());
            if (keyCode == 256) { EditorLogger.action(EditorLogger.SCREEN, "CLOSE", "ESC"); onClose(); return true; }
            if (keyCode == 66) { EditorLogger.action(EditorLogger.SCREEN, "CLOSE", "B"); onClose(); return true; }
            UITextInput focused = leftPanel.getFocusedInput();
            if (focused != null && focused.keyPressed(keyCode, scanCode, modifiers)) { flush(); return true; }
            if (core.getSelectedKeyframe() != null) { handleKeyframeKey(keyCode); flush(); return true; }
            if (core.getSelectedClip() != null) { handleClipKey(keyCode); flush(); return true; }
            if (keyCode == 83 && hasControlDown()) {
                EditorLogger.action(EditorLogger.SCREEN, "SAVE", "Ctrl+S");
                saveScript();
                return true;
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "keyPressed crashed keyCode=" + keyCode, e);
        }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        try {
            UITextInput focused = leftPanel.getFocusedInput();
            if (focused != null) {
                EditorLogger.keyPress(EditorLogger.SCREEN, "charTyped", (int) codePoint,
                        "char=" + (codePoint > 32 ? String.valueOf(codePoint) : "CTRL"));
                focused.charTyped(codePoint); flush(); return true;
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "charTyped crashed codePoint=" + (int) codePoint, e);
        }
        return false;
    }

    private void handleKeyframeKey(int keyCode) {
        try {
            EditorKeyframe kf = core.getSelectedKeyframe();
            float step = hasShiftDown() ? 5 : 0.5f;
            String dir;
            if (keyCode == 265) { kf.position.y += step; dir = "y+"; }
            else if (keyCode == 264) { kf.position.y -= step; dir = "y-"; }
            else if (keyCode == 263) { kf.position.x -= step; dir = "x-"; }
            else if (keyCode == 262) { kf.position.x += step; dir = "x+"; }
            else return;
            EditorLogger.action(EditorLogger.SCREEN, "KEYFRAME_NUDGE", dir + " step=" + step);
            core.moveKeyframe(kf, core.getSelectedClip(), kf.time);
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "handleKeyframeKey crashed", e);
        }
    }

    private void handleClipKey(int keyCode) {
        try {
            EditorClip clip = core.getSelectedClip();
            float step = hasShiftDown() ? 1 : 0.1f;
            if (keyCode == 263) { core.moveClip(clip, clip.startTime - step); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "left step=" + step); }
            else if (keyCode == 262) { core.moveClip(clip, clip.startTime + step); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "right step=" + step); }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "handleClipKey crashed", e);
        }
    }

    public EditorOutput getEditorOutput() { return editorOutput; }

    @Override
    public void onClose() {
        try { stop(); } catch (Exception e) { EditorLogger.error(EditorLogger.SCREEN, "onClose-stop failed", e); }
        RawInputLogger.disable();
        EditorLogger.close();
        if (minecraft != null) minecraft.setScreen(null);
    }
    @Override public boolean isPauseScreen() { return false; }

    private UIContext makeCtx(double mx, double my) {
        return new UIContext(null, font, width, height, 0, (int) mx, (int) my);
    }
}
