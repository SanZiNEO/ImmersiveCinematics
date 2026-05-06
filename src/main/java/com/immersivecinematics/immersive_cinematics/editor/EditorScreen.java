package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.editor.area.*;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.debug.RawInputLogger;
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

    private final EditorDocument doc;
    private final EditorSelection sel;
    private final EditorPlayback playback;
    private final EditorOutput output;
    private final Path scriptsDir;

    private MenuBarArea menuBar;
    private LeftPanelArea leftPanel;
    private PreviewArea preview;
    private TimelineArea timeline;

    private final List<String> scriptFileNames = new ArrayList<>();
    private String scriptFilePath;
    private boolean autoSnap;
    private boolean firstInit = true;
    private String activeArea = "none";
    private int mouseDownX, mouseDownY;

    private int renderCycle;
    private String renderPhase = "idle";
    private long lastRenderLog;

    public EditorScreen(EditorBridge bridge, Path scriptsDir) {
        super(Component.literal("Cinematic Editor"));
        this.scriptsDir = scriptsDir;
        this.doc = new EditorDocument();
        this.sel = new EditorSelection();
        this.playback = new EditorPlayback();
        this.output = new EditorOutput(bridge);

        sel.setListener((clip, kf) -> {
            try {
                if (leftPanel == null) return;
                LeftPanelArea.PanelMode m;
                if (clip == null) m = LeftPanelArea.PanelMode.SCRIPT_PROPERTIES;
                else if (kf == null) m = LeftPanelArea.PanelMode.CLIP_PROPERTIES;
                else m = LeftPanelArea.PanelMode.KEYFRAME_PROPERTIES;
                leftPanel.setMode(m);
                syncPanels();
            } catch (Exception e) {
                EditorLogger.error(EditorLogger.SCREEN, "onSelectionChanged crashed", e);
            }
        });
    }

    public EditorOutput getEditorOutput() { return output; }

    // ══════════════════════════════════════════════════════════════
    //  INIT
    // ══════════════════════════════════════════════════════════════

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

        leftPanel.setDirtyCallback(doc::markDirty);

        if (firstInit) {
            firstInit = false;
            refreshScriptList();
            doc.reset();
            JsonObject clip = EditorOperations.addClip(doc.getTracks(), 0, 0, 10);
            syncPanels();
            leftPanel.build();
            if (clip != null) sel.selectClip(clip);
        } else {
            syncPanels();
            leftPanel.build();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  WIRING
    // ══════════════════════════════════════════════════════════════

    private void wireMenu() {
        menuBar.setOnNewScript(() -> {
            EditorLogger.action(EditorLogger.SCREEN, "NEW_SCRIPT", "from menu");
            doc.reset();
            sel.clear();
            JsonObject clip = EditorOperations.addClip(doc.getTracks(), 0, 0, 10);
            syncPanels();
            leftPanel.build();
            if (clip != null) sel.selectClip(clip);
        });
        menuBar.setOnSaveScript(() -> {
            EditorLogger.action(EditorLogger.SCREEN, "SAVE", "from menu");
            saveScript();
        });
        menuBar.setOnToggleList(this::toggleScriptList);
    }

    private void wireTimeline() {
        timeline.setOnClickAtTime(t -> {
            EditorLogger.playhead(EditorLogger.SCREEN, t, 0, "ruler_click");
            playback.setTime(t);
            output.setTime(t);
            syncPanels();
        });
        timeline.setOnClickClip(clip -> {
            float st = EditorOperations.getStart(clip);
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_CLIP", "startTime=" + st);
            sel.selectClip(clip);
        });
        timeline.setOnClickKeyframe((kf, clip) -> {
            float globalTime = EditorOperations.getStart(clip) + kf.get("time").getAsFloat();
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_KEYFRAME", "time=" + kf.get("time").getAsFloat() + " global=" + globalTime);
            sel.selectKeyframe(kf);
            playback.setTime(globalTime);
            output.setTime(globalTime);
        });
        timeline.setOnMoveClip((clip, ns) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_CLIP", "from=" + EditorOperations.getStart(clip) + " to=" + ns);
            EditorOperations.moveClip(clip, ns, autoSnap ? 0.5f : 0);
            doc.markDirty();
        });
        timeline.setOnResizeLeft((clip, ns) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_LEFT", "clipStart=" + EditorOperations.getStart(clip) + " newStart=" + ns);
            EditorOperations.resizeClipLeft(clip, ns, autoSnap ? 0.5f : 0);
            doc.markDirty();
        });
        timeline.setOnResizeRight((clip, ne) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_RIGHT", "clipEnd=" + EditorOperations.getEnd(clip) + " newEnd=" + ne);
            EditorOperations.resizeClipRight(clip, ne, autoSnap ? 0.5f : 0);
            doc.markDirty();
        });
        timeline.setOnMoveKeyframe((kf, clip, nt) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_KEYFRAME", "from=" + kf.get("time").getAsFloat() + " to=" + nt + " clipStart=" + EditorOperations.getStart(clip));
            EditorOperations.moveKeyframe(clip, kf, nt, autoSnap ? 0.5f : 0);
            doc.markDirty();
        });
        timeline.setOnToolAddClip(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_CLIP", "");
            JsonObject clip = EditorOperations.addClip(doc.getTracks(), 0, doc.getTotalDuration(), 5);
            doc.markDirty();
            if (clip != null) sel.selectClip(clip);
        });
        timeline.setOnToolDeleteClip(() -> {
            JsonObject clip = sel.getClip();
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_DELETE_CLIP",
                    "selected=" + (clip != null ? EditorOperations.getStart(clip) : "null"));
            if (clip != null) {
                EditorOperations.deleteClip(doc.getTracks(), clip);
                sel.clear();
                doc.markDirty();
            }
        });
        timeline.setOnToolAddKeyframe(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_KEYFRAME", "at=" + String.format("%.3f", playback.getTime()));
            JsonObject kf = EditorOperations.addKeyframeAt(sel.getClip(), playback.getTime());
            doc.markDirty();
            if (kf != null) sel.selectKeyframe(kf);
        });
        timeline.setOnToolDeleteKeyframe(() -> {
            JsonObject kf = sel.getKeyframe();
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_DELETE_KEYFRAME",
                    "selected=" + (kf != null ? kf.get("time").getAsFloat() : "null"));
            if (kf != null) {
                EditorOperations.deleteKeyframe(sel.getClip(), kf);
                sel.clear();
                doc.markDirty();
            }
        });
        timeline.setOnToolSnap(() -> {
            autoSnap = !autoSnap;
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_SNAP", "autoSnap=" + autoSnap);
            doc.markDirty();
        });
    }

    private void wirePreview() {
        preview.setOnPlay(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "PLAY", "btn");
            playback.play();
            output.play();
        });
        preview.setOnPause(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "PAUSE", "btn");
            playback.pause();
            output.pause();
        });
        preview.setOnStop(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "STOP", "btn");
            playback.stop();
            output.stop();
            syncPanels();
        });
    }

    private void wireLeftPanel() {
        leftPanel.setOnOpenScript(name -> {
            EditorLogger.action(EditorLogger.LEFT, "OPEN_SCRIPT", "file=" + name);
            openScript(name);
        });
        leftPanel.setOnDeleteScript(name -> {
            EditorLogger.action(EditorLogger.LEFT, "DELETE_SCRIPT", "file=" + name);
            deleteScript(name);
        });
        leftPanel.setOnNewScript(() -> {
            EditorLogger.action(EditorLogger.LEFT, "NEW_SCRIPT", "from left panel");
            doc.reset();
            sel.clear();
            JsonObject clip = EditorOperations.addClip(doc.getTracks(), 0, 0, 10);
            syncPanels();
            leftPanel.build();
            if (clip != null) sel.selectClip(clip);
        });
        leftPanel.setOnNameChanged(v -> {
            doc.getMeta().addProperty("name", v);
            doc.markDirty();
        });
        leftPanel.setOnAuthorChanged(v -> {
            doc.getMeta().addProperty("author", v);
            doc.markDirty();
        });
        leftPanel.setOnDescChanged(v -> {
            doc.getMeta().addProperty("description", v);
            doc.markDirty();
        });
        leftPanel.setOnDurationChanged(v -> {
            doc.setTotalDuration(v);
            doc.markDirty();
        });
        leftPanel.setOnBehaviorFlag(s -> {
            String[] parts = s.split("=");
            if (parts.length == 2) {
                EditorLogger.action(EditorLogger.LEFT, "BEHAVIOR_FLAG", parts[0] + "=" + parts[1]);
                doc.getMeta().addProperty(parts[0], Boolean.parseBoolean(parts[1]));
                doc.markDirty();
            }
        });
    }

    // ══════════════════════════════════════════════════════════════
    //  SYNC
    // ══════════════════════════════════════════════════════════════

    private void syncPanels() {
        float dur = EditorOperations.recalcDuration(doc.getTracks());
        doc.setTotalDuration(dur);

        menuBar.setScriptName(doc.getFileName());
        leftPanel.setData(doc.getMeta(), sel.getClip(), sel.getKeyframe());

        float time = playback.getTime();
        timeline.setData(doc.getTimeline(), sel.getClip(), sel.getKeyframe(),
                autoSnap, EditorOperations.canAddKeyframeAt(sel.getClip(), time));
        timeline.setPlayheadTime(time);
        preview.setCurrentTime(time);

        EditorLogger.sync(EditorLogger.SCREEN, "panels",
                "playbackTime=" + String.format("%.3f", time)
                        + " dirty=" + doc.isDirty()
                        + " selectedClip=" + (sel.getClip() != null ? EditorOperations.getStart(sel.getClip()) : "null")
                        + " selectedKf=" + (sel.getKeyframe() != null ? sel.getKeyframe().get("time").getAsFloat() : "null"));
    }

    // ══════════════════════════════════════════════════════════════
    //  FILE I/O
    // ══════════════════════════════════════════════════════════════

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
                    : scriptsDir.resolve(doc.getFileName() + ".json");
            float dur = EditorOperations.recalcDuration(doc.getTracks());
            doc.setTotalDuration(dur);
            Files.writeString(dest, doc.toJson());
            String savedPath = dest.toString();
            if (scriptFilePath == null) scriptFilePath = savedPath;
            doc.clearDirty();
            refreshScriptList();
            output.pushScript(doc.toJson());
            EditorLogger.action(EditorLogger.SCREEN, "SAVE_SCRIPT", "path=" + savedPath + " success=true");
        } catch (IOException e) {
            EditorLogger.action(EditorLogger.SCREEN, "SAVE_SCRIPT", "path=" + scriptFilePath + " success=false error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void openScript(String fileName) {
        try {
            String json = Files.readString(scriptsDir.resolve(fileName));
            doc.loadFromJson(json);
            doc.setFileName(fileName.replace(".json", ""));
            scriptFilePath = scriptsDir.resolve(fileName).toString();
            playback.setTime(0);
            sel.clear();
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

    // ══════════════════════════════════════════════════════════════
    //  RENDER
    // ══════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderCycle++;
        String cycleStr = "cycle=" + renderCycle;
        EditorLogger.outRaw(EditorLogger.SCREEN, "LOOP", "RENDER_START " + cycleStr);

        long t0 = System.nanoTime();

        renderPhase = "ctx_init";
        UIContext ctx;
        try {
            ctx = new UIContext(guiGraphics, font, width, height, partialTick, mouseX, mouseY);
            ctx.ctrlDown = hasControlDown();
            ctx.shiftDown = hasShiftDown();
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=ctx_init " + cycleStr, e);
            return;
        }

        renderPhase = "playback";
        try {
            if (playback.tick(doc.getTotalDuration())) {
                float t = playback.getTime();
                timeline.setPlayheadTime(t);
                preview.setCurrentTime(t);
                if (!playback.isPlaying()) {
                    output.pause();
                    EditorLogger.action(EditorLogger.SCREEN, "PLAYBACK", "ended at=" + String.format("%.3f", t));
                }
                EditorLogger.dataSync(EditorLogger.SCREEN, "playbackTime", t, timeline.getPlayheadTime());
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=playback " + cycleStr, e);
            return;
        }

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

        renderPhase = "done";
        long elapsedNs = System.nanoTime() - t0;
        long now = System.currentTimeMillis();

        if (now - lastRenderLog >= 1000) {
            lastRenderLog = now;
            EditorLogger.outRaw(EditorLogger.SCREEN, "LOOP",
                    "RENDER_TICK cycle=" + renderCycle + " elapsed=" + (elapsedNs / 1_000_000) + "ms"
                            + " playing=" + playback.isPlaying() + " time=" + String.format("%.3f", playback.getTime())
                            + " dirty=" + doc.isDirty());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  INPUT
    // ══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        UIContext ctx = makeCtx(mx, my);
        int imx = (int) mx, imy = (int) my;
        mouseDownX = imx; mouseDownY = imy;
        EditorLogger.mousePressed(EditorLogger.SCREEN, button, imx, imy, activeArea);

        try {
            if (menuBar.mouseClicked(ctx)) { activeArea = "MenuBar"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "MenuBar", true); syncPanels(); return true; }
            if (leftPanel.mouseClicked(ctx)) { activeArea = "LeftPanel"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "LeftPanel", true); syncPanels(); return true; }
            if (preview.mouseClicked(ctx)) { activeArea = "Preview"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "Preview", true); syncPanels(); return true; }
            if (timeline.mouseClicked(ctx)) { activeArea = "Timeline"; EditorLogger.mouseConsumedBy(EditorLogger.SCREEN, "Timeline", true); syncPanels(); return true; }
            EditorLogger.areaNoHit(EditorLogger.SCREEN, imx, imy);
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
            if (focused != null && focused.keyPressed(keyCode, scanCode, modifiers)) { return true; }
            if (sel.getKeyframe() != null) { handleKeyframeKey(keyCode); return true; }
            if (sel.getClip() != null) { handleClipKey(keyCode); return true; }
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
                focused.charTyped(codePoint); return true;
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "charTyped crashed codePoint=" + (int) codePoint, e);
        }
        return false;
    }

    private void handleKeyframeKey(int keyCode) {
        try {
            JsonObject kf = sel.getKeyframe();
            float step = hasShiftDown() ? 5 : 0.5f;
            String dir;
            if (keyCode == 265) { addTo(kf, "yaw", step); dir = "yaw+"; }
            else if (keyCode == 264) { addTo(kf, "yaw", -step); dir = "yaw-"; }
            else if (keyCode == 263) { addTo(kf, "time", -step); dir = "time-"; }
            else if (keyCode == 262) { addTo(kf, "time", step); dir = "time+"; }
            else return;
            EditorLogger.action(EditorLogger.SCREEN, "KEYFRAME_NUDGE", dir + " step=" + step);
            doc.markDirty();
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "handleKeyframeKey crashed", e);
        }
    }

    private void handleClipKey(int keyCode) {
        try {
            JsonObject clip = sel.getClip();
            float step = hasShiftDown() ? 1 : 0.1f;
            if (keyCode == 263) { EditorOperations.moveClip(clip, EditorOperations.getStart(clip) - step, autoSnap ? 0.5f : 0); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "left step=" + step); doc.markDirty(); }
            else if (keyCode == 262) { EditorOperations.moveClip(clip, EditorOperations.getStart(clip) + step, autoSnap ? 0.5f : 0); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "right step=" + step); doc.markDirty(); }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "handleClipKey crashed", e);
        }
    }

    private static void addTo(JsonObject obj, String key, float delta) {
        if (obj.has(key)) obj.addProperty(key, obj.get(key).getAsFloat() + delta);
    }

    @Override
    public void onClose() {
        CinematicKeyBindings.notifyEditorClosed();
        playback.stop();
        output.stop();
        RawInputLogger.disable();
        EditorLogger.close();
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override public boolean isPauseScreen() { return false; }

    private UIContext makeCtx(double mx, double my) {
        UIContext ctx = new UIContext(null, font, width, height, 0, (int) mx, (int) my);
        ctx.ctrlDown = hasControlDown();
        ctx.shiftDown = hasShiftDown();
        return ctx;
    }
}
