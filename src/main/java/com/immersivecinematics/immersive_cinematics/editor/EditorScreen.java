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

    private EditorBridge bridge;
    private EditorScript script;
    private EditorClip selectedClip;
    private EditorKeyframe selectedKeyframe;

    private MenuBarArea menuBar;
    private LeftPanelArea leftPanel;
    private PreviewArea preview;
    private TimelineArea timeline;

    private String scriptFilePath;
    private Path scriptsDir;
    private List<String> scriptFileNames = new ArrayList<>();

    private boolean playing;
    private float playbackTime;
    private long lastPlayTick;
    private boolean firstInit = true;

    public EditorScreen(EditorBridge bridge, Path scriptsDir) {
        super(Component.literal("Cinematic Editor"));
        this.bridge = bridge;
        this.scriptsDir = scriptsDir;
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

        wireCallbacks();

        if (firstInit) {
            firstInit = false;
            refreshScriptList();
            if (script == null) {
                newScript();
            }
        }

        if (script != null) {
            syncAll();
        }
    }

    private void wireCallbacks() {
        menuBar.setOnNewScript(this::newScript);
        menuBar.setOnSaveScript(this::saveScript);
        menuBar.setOnToggleList(this::toggleScriptList);

        leftPanel.setOnOpenScript(this::openScript);
        leftPanel.setOnDeleteScript(this::deleteScript);
        leftPanel.setOnNewScript(this::newScript);
        leftPanel.setOnScriptChanged(this::onScriptChanged);

        timeline.setOnClipSelected(this::selectClip);
        timeline.setOnKeyframeSelected(this::selectKeyframe);
        timeline.setOnPlayheadChanged(this::setPlaybackTime);
        timeline.setOnClipChanged(this::onScriptChanged);

        preview.setOnPlay(this::play);
        preview.setOnPause(this::pause);
        preview.setOnStop(this::stop);
    }

    private void newScript() {
        script = new EditorScript();
        script.name = "Untitled";
        script.author = "Author";
        selectedClip = null;
        selectedKeyframe = null;
        scriptFilePath = null;
        leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
        syncAll();
        pushScript();
    }

    private void saveScript() {
        if (script == null) return;
        try {
            Files.createDirectories(scriptsDir);
            Path dest;
            if (scriptFilePath != null) {
                dest = Paths.get(scriptFilePath);
            } else {
                String name = script.name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                if (name.isEmpty()) name = "script";
                dest = scriptsDir.resolve(name + ".json");
                scriptFilePath = dest.toString();
            }
            String json = EditorSerializer.serialize(script);
            Files.writeString(dest, json);
            refreshScriptList();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void openScript(String fileName) {
        try {
            Path path = scriptsDir.resolve(fileName);
            String json = Files.readString(path);
            script = EditorSerializer.deserialize(json);
            scriptFilePath = path.toString();
            selectedClip = null;
            selectedKeyframe = null;
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
            syncAll();
            pushScript();
            leftPanel.setScriptFileNames(scriptFileNames);
            leftPanel.build();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteScript(String fileName) {
        try {
            Files.deleteIfExists(scriptsDir.resolve(fileName));
            refreshScriptList();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void toggleScriptList() {
        if (leftPanel.getMode() == LeftPanelArea.PanelMode.SCRIPT_LIST) {
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
        } else {
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_LIST);
        }
        syncAll();
    }

    private void refreshScriptList() {
        scriptFileNames.clear();
        if (Files.exists(scriptsDir)) {
            try (Stream<Path> files = Files.list(scriptsDir)) {
                files.filter(f -> f.toString().endsWith(".json"))
                        .map(f -> f.getFileName().toString())
                        .sorted()
                        .forEach(scriptFileNames::add);
            } catch (IOException ignored) {}
        }
        leftPanel.setScriptFileNames(scriptFileNames);
        leftPanel.build();
    }

    private void selectClip(EditorClip clip) {
        selectedClip = clip;
        selectedKeyframe = null;
        leftPanel.setMode(LeftPanelArea.PanelMode.CLIP_PROPERTIES);
        syncAll();
    }

    private void selectKeyframe(EditorKeyframe kf) {
        selectedKeyframe = kf;
        leftPanel.setMode(LeftPanelArea.PanelMode.KEYFRAME_PROPERTIES);
        syncAll();
    }

    private void setPlaybackTime(float t) {
        playbackTime = Math.max(0, Math.min(t, script != null ? script.totalDuration : 0));
        if (bridge != null) bridge.setTime(playbackTime);
        syncTimeDisplay();
    }

    private void play() {
        playing = true;
        lastPlayTick = System.currentTimeMillis();
        if (bridge != null) bridge.play();
    }

    private void pause() {
        playing = false;
        if (bridge != null) bridge.pause();
    }

    private void stop() {
        playing = false;
        playbackTime = 0;
        if (bridge != null) bridge.stop();
        syncTimeDisplay();
    }

    private void onScriptChanged() {
        pushScript();
    }

    private void pushScript() {
        if (script != null && bridge != null) {
            bridge.pushScript(EditorSerializer.serialize(script));
        }
    }

    private void syncAll() {
        if (script == null) return;
        menuBar.setScriptName(script.name);
        leftPanel.setScript(script);
        leftPanel.setSelectedClip(selectedClip);
        leftPanel.setSelectedKeyframe(selectedKeyframe);
        leftPanel.build();
        timeline.setScript(script);
        syncTimeDisplay();
    }

    private void syncTimeDisplay() {
        preview.setCurrentTime(playbackTime);
        timeline.setPlayheadTime(playbackTime);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        UIContext ctx = new UIContext(guiGraphics, font, width, height, partialTick, mouseX, mouseY);

        if (playing) {
            long now = System.currentTimeMillis();
            float dt = (now - lastPlayTick) / 1000f;
            lastPlayTick = now;
            playbackTime += dt;
            if (script != null && playbackTime >= script.totalDuration) {
                playbackTime = script.totalDuration;
                playing = false;
            }
            if (bridge != null) bridge.setTime(playbackTime);
            syncTimeDisplay();
        }

        timeline.updateDrag(ctx);

        menuBar.render(ctx);
        leftPanel.render(ctx);
        preview.render(ctx);
        timeline.render(ctx);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        UIContext ctx = makeContext(mx, my, button);

        if (menuBar.mouseClicked(ctx)) return true;
        if (leftPanel.mouseClicked(ctx)) return true;
        if (preview.mouseClicked(ctx)) return true;
        if (timeline.mouseClicked(ctx)) return true;
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        UIContext ctx = makeContext(mx, my, button);

        menuBar.mouseReleased(ctx);
        leftPanel.mouseReleased(ctx);
        preview.mouseReleased(ctx);
        timeline.mouseReleased(ctx);
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scroll) {
        UIContext ctx = makeContext(mx, my, 0);
        ctx.mouseX = (int) mx;
        ctx.mouseY = (int) my;

        if (timeline.mouseScrolled(ctx, scroll)) return true;
        if (leftPanel.mouseScrolled(ctx, scroll)) return true;
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (script == null) return false;
        if (selectedKeyframe != null) {
            return handleKeyframeKey(keyCode);
        }
        if (selectedClip != null) {
            return handleClipKey(keyCode);
        }
        if (keyCode == 83 && hasControlDown()) {
            saveScript();
            return true;
        }
        return false;
    }

    private boolean handleKeyframeKey(int keyCode) {
        float step = hasShiftDown() ? 5 : 0.5f;
        switch (keyCode) {
            case 265: selectedKeyframe.position.y += step; break;
            case 264: selectedKeyframe.position.y -= step; break;
            case 263: selectedKeyframe.position.x -= step; break;
            case 262: selectedKeyframe.position.x += step; break;
            default: return false;
        }
        onScriptChanged();
        syncAll();
        return true;
    }

    private boolean handleClipKey(int keyCode) {
        float step = hasShiftDown() ? 1 : 0.1f;
        switch (keyCode) {
            case 263: selectedClip.startTime = Math.max(0, selectedClip.startTime - step); break;
            case 262: selectedClip.startTime += step; break;
            default: return false;
        }
        onScriptChanged();
        syncAll();
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    private UIContext makeContext(double mx, double my, int button) {
        UIContext ctx = new UIContext(null, font, width, height, 0, (int) mx, (int) my);
        ctx.mouseButton = button;
        return ctx;
    }

    @Override
    public void onClose() {
        stop();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
