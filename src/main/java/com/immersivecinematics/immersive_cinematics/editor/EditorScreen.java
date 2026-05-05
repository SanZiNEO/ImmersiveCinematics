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

    public EditorScreen(EditorBridge bridge, Path scriptsDir) {
        super(Component.literal("Cinematic Editor"));
        this.bridge = bridge;
        this.scriptsDir = scriptsDir;
        this.core = new EditorCore();
        core.setOnChanged(this::onCoreChanged);
        core.setOnSelectionChanged((clip, kf) -> {
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

        if (firstInit) {
            firstInit = false;
            refreshScriptList();
            core.newScript();
            syncPanels();
            pushScript();
        } else {
            syncPanels();
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
        timeline.setOnMoveClip((clip, ns) -> { core.moveClip(clip, ns); syncPanels(); });
        timeline.setOnResizeLeft((clip, ns) -> { core.resizeClipLeft(clip, ns); syncPanels(); });
        timeline.setOnResizeRight((clip, ne) -> { core.resizeClipRight(clip, ne); syncPanels(); });
        timeline.setOnMoveKeyframe((kf, clip, nt) -> { core.moveKeyframe(kf, clip, nt); syncPanels(); });
        timeline.setOnToolAddClip(() -> { core.addClip(); syncPanels(); pushScript(); });
        timeline.setOnToolDeleteClip(() -> { core.deleteSelectedClip(); syncPanels(); pushScript(); });
        timeline.setOnToolAddKeyframe(() -> { core.addKeyframeAt(playbackTime); syncPanels(); pushScript(); });
        timeline.setOnToolDeleteKeyframe(() -> { core.deleteSelectedKeyframe(); syncPanels(); pushScript(); });
    }

    private void wirePreview() {
        preview.setOnPlay(this::play);
        preview.setOnPause(this::pause);
        preview.setOnStop(this::stop);
    }

    private void onCoreChanged() {
        pushScript();
    }

    private void syncPanels() {
        menuBar.setScriptName(core.getScript().name);
        leftPanel.setScript(core.getScript());
        leftPanel.setSelectedClip(core.getSelectedClip());
        leftPanel.setSelectedKeyframe(core.getSelectedKeyframe());
        leftPanel.build();
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
                String name = core.getScript().name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (name.isEmpty()) name = "script";
                dest = scriptsDir.resolve(name + ".json");
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
            if (playbackTime >= core.getScript().totalDuration) { playbackTime = core.getScript().totalDuration; playing = false; }
            if (bridge != null) bridge.setTime(playbackTime);
            timeline.setPlayheadTime(playbackTime);
            preview.setCurrentTime(playbackTime);
        }

        timeline.updatePlayheadDrag(ctx);
        menuBar.render(ctx);
        leftPanel.render(ctx);
        preview.render(ctx);
        timeline.render(ctx);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        UIContext ctx = makeCtx(mx, my);
        return menuBar.mouseClicked(ctx) || leftPanel.mouseClicked(ctx) || preview.mouseClicked(ctx) || timeline.mouseClicked(ctx);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        UIContext ctx = makeCtx(mx, my);
        menuBar.mouseReleased(ctx); leftPanel.mouseReleased(ctx); preview.mouseReleased(ctx); timeline.mouseReleased(ctx);
        syncPanels();
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scroll) {
        UIContext ctx = makeCtx(mx, my);
        return timeline.mouseScrolled(ctx, scroll) || leftPanel.mouseScrolled(ctx, scroll);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (core.getSelectedKeyframe() != null) return handleKeyframeKey(keyCode);
        if (core.getSelectedClip() != null) return handleClipKey(keyCode);
        if (keyCode == 83 && hasControlDown()) { saveScript(); return true; }
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
