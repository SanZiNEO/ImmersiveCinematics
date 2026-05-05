package com.immersivecinematics.immersive_cinematics.editor;

import com.immersivecinematics.immersive_cinematics.editor.area.*;
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

    private final EditorBridge bridge;
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
    private boolean playheadScrubbed;

    public EditorScreen(EditorBridge bridge, Path scriptsDir) {
        super(Component.literal("Cinematic Editor"));
        this.bridge = bridge;
        this.scriptsDir = scriptsDir;
        this.core = new EditorCore();
        core.setOnChanged(() -> { scriptDirty = true; });
        core.setOnSelectionChanged((clip, kf) -> {
            if (leftPanel == null) return;
            if (clip == null) leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
            else if (kf == null) leftPanel.setMode(LeftPanelArea.PanelMode.CLIP_PROPERTIES);
            else leftPanel.setMode(LeftPanelArea.PanelMode.KEYFRAME_PROPERTIES);
            syncPanels();
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

        wireMenu();
        wireTimeline();
        wirePreview();
        wireLeftPanel();

        leftPanel.setDirtyCallback(() -> { scriptDirty = true; });

        if (firstInit) {
            firstInit = false;
            refreshScriptList();
            core.newScript();
            syncPanels();
            leftPanel.build();
            pushScript();
        } else {
            syncPanels();
            leftPanel.build();
        }
    }

    private void wireMenu() {
        menuBar.setOnNewScript(() -> { core.newScript(); syncPanels(); pushScript(); });
        menuBar.setOnSaveScript(this::saveScript);
        menuBar.setOnToggleList(this::toggleScriptList);
    }

    private void wireTimeline() {
        timeline.setCore(core);
        timeline.setOnClickAtTime(t -> { playbackTime = t; if (bridge != null) bridge.setTime(t); });
        timeline.setOnClickClip(core::selectClip);
        timeline.setOnClickKeyframe((kf, clip) -> core.selectKeyframe(kf));
        timeline.setOnMoveClip((clip, ns) -> core.moveClip(clip, ns));
        timeline.setOnResizeLeft((clip, ns) -> core.resizeClipLeft(clip, ns));
        timeline.setOnResizeRight((clip, ne) -> core.resizeClipRight(clip, ne));
        timeline.setOnMoveKeyframe((kf, clip, nt) -> core.moveKeyframe(kf, clip, nt));
        timeline.setOnToolAddClip(() -> core.addClip());
        timeline.setOnToolDeleteClip(() -> core.deleteSelectedClip());
        timeline.setOnToolAddKeyframe(() -> core.addKeyframeAt(playbackTime));
        timeline.setOnToolDeleteKeyframe(() -> core.deleteSelectedKeyframe());
        timeline.setOnToolSnap(() -> core.toggleAutoSnap());
    }

    private void wirePreview() {
        preview.setOnPlay(this::play);
        preview.setOnPause(this::pause);
        preview.setOnStop(this::stop);
    }

    private void wireLeftPanel() {
        leftPanel.setOnOpenScript(this::openScript);
        leftPanel.setOnDeleteScript(this::deleteScript);
        leftPanel.setOnNewScript(() -> { core.newScript(); syncPanels(); pushScript(); });
        leftPanel.setOnNameChanged(v -> core.setScriptName(v));
        leftPanel.setOnAuthorChanged(v -> core.setScriptAuthor(v));
        leftPanel.setOnDescChanged(v -> core.setScriptDescription(v));
        leftPanel.setOnDurationChanged(v -> core.setTotalDuration(v));
        leftPanel.setOnBehaviorFlag(s -> {
            String[] parts = s.split("=");
            if (parts.length == 2) {
                core.setBehaviorFlag(parts[0], Boolean.parseBoolean(parts[1]));
            }
        });
    }

    private void flush() {
        syncPanels();
        if (scriptDirty) {
            pushScript();
            scriptDirty = false;
        }
    }

    private void syncPanels() {
        menuBar.setScriptName(core.getFileName());
        leftPanel.setScript(core.getScript());
        leftPanel.setSelectedClip(core.getSelectedClip());
        leftPanel.setSelectedKeyframe(core.getSelectedKeyframe());
        timeline.setPlayheadTime(playbackTime);
        preview.setCurrentTime(playbackTime);
    }

    private void pushScript() {
        if (bridge != null) bridge.pushScript(core.toJson());
    }

    private void toggleScriptList() {
        if (leftPanel.getMode() == LeftPanelArea.PanelMode.SCRIPT_LIST)
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
        else leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_LIST);
        syncPanels();
    }

    private void saveScript() {
        try {
            Files.createDirectories(scriptsDir);
            Path dest;
            if (scriptFilePath != null) dest = Paths.get(scriptFilePath);
            else {
                dest = scriptsDir.resolve(core.getFileName() + ".json");
                scriptFilePath = dest.toString();
            }
            Files.writeString(dest, core.toJson());
            refreshScriptList();
        } catch (IOException e) { e.printStackTrace(); }
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
            pushScript();
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void deleteScript(String fileName) {
        try { Files.deleteIfExists(scriptsDir.resolve(fileName)); refreshScriptList(); }
        catch (IOException e) { e.printStackTrace(); }
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
        if (leftPanel.getMode() == LeftPanelArea.PanelMode.SCRIPT_LIST) {
            leftPanel.build();
        }
    }

    private void play() { playing = true; lastPlayTick = System.currentTimeMillis(); if (bridge != null) bridge.play(); }
    private void pause() { playing = false; if (bridge != null) bridge.pause(); }
    private void stop() { playing = false; playbackTime = 0; if (bridge != null) bridge.stop(); syncPanels(); }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        UIContext ctx = new UIContext(guiGraphics, font, width, height, partialTick, mouseX, mouseY);

        if (playing) {
            long now = System.currentTimeMillis();
            playbackTime += (now - lastPlayTick) / 1000f;
            lastPlayTick = now;
            if (playbackTime >= core.getScript().totalDuration) {
                playbackTime = core.getScript().totalDuration;
                playing = false;
                if (bridge != null) bridge.pause();
            }
            timeline.setPlayheadTime(playbackTime);
            preview.setCurrentTime(playbackTime);
        }

        float scrubDisplay = timeline.getScrubDisplay(ctx);
        if (scrubDisplay >= 0) {
            timeline.setPlayheadTime(scrubDisplay);
            preview.setCurrentTime(scrubDisplay);
            playheadScrubbed = true;
        }

        menuBar.render(ctx);
        leftPanel.render(ctx);
        preview.render(ctx);
        timeline.render(ctx);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        timeline.stopDrag();
        UIContext ctx = makeCtx(mx, my);
        boolean result = menuBar.mouseClicked(ctx) || leftPanel.mouseClicked(ctx) || preview.mouseClicked(ctx) || timeline.mouseClicked(ctx);
        flush();
        return result;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        UIContext ctx = makeCtx(mx, my);

        if (playheadScrubbed) {
            playbackTime = timeline.getScrubDisplay(ctx);
            if (bridge != null) bridge.setTime(playbackTime);
            playheadScrubbed = false;
        }

        menuBar.mouseReleased(ctx);
        leftPanel.mouseReleased(ctx);
        preview.mouseReleased(ctx);
        timeline.mouseReleased(ctx);

        flush();
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scroll) {
        UIContext ctx = makeCtx(mx, my);
        boolean result = timeline.mouseScrolled(ctx, scroll) || leftPanel.mouseScrolled(ctx, scroll);
        flush();
        return result;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        UITextInput focused = leftPanel.getFocusedInput();
        if (focused != null && focused.keyPressed(keyCode, scanCode, modifiers)) { flush(); return true; }
        if (core.getSelectedKeyframe() != null) { boolean r = handleKeyframeKey(keyCode); flush(); return r; }
        if (core.getSelectedClip() != null) { boolean r = handleClipKey(keyCode); flush(); return r; }
        if (keyCode == 83 && hasControlDown()) { saveScript(); flush(); return true; }
        return false;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        UITextInput focused = leftPanel.getFocusedInput();
        if (focused != null) { boolean r = focused.charTyped(codePoint); flush(); return r; }
        return false;
    }

    private boolean handleKeyframeKey(int keyCode) {
        EditorKeyframe kf = core.getSelectedKeyframe();
        float step = hasShiftDown() ? 5 : 0.5f;
        switch (keyCode) {
            case 265: kf.position.y += step; break;
            case 264: kf.position.y -= step; break;
            case 263: kf.position.x -= step; break;
            case 262: kf.position.x += step; break;
            default: return false;
        }
        core.moveKeyframe(kf, core.getSelectedClip(), kf.time);
        syncPanels();
        return true;
    }

    private boolean handleClipKey(int keyCode) {
        EditorClip clip = core.getSelectedClip();
        float step = hasShiftDown() ? 1 : 0.1f;
        switch (keyCode) {
            case 263: core.moveClip(clip, clip.startTime - step); break;
            case 262: core.moveClip(clip, clip.startTime + step); break;
            default: return false;
        }
        syncPanels();
        return true;
    }

    @Override
    public void onClose() { stop(); if (minecraft != null) minecraft.setScreen(null); }

    @Override
    public boolean isPauseScreen() { return false; }

    private UIContext makeCtx(double mx, double my) {
        return new UIContext(null, font, width, height, 0, (int) mx, (int) my);
    }
}
