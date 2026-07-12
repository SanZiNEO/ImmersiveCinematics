package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.immersivecinematics.immersive_cinematics.editor.area.*;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.debug.RawInputLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import com.immersivecinematics.immersive_cinematics.editor.widget.IFocusable;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.resources.language.I18n;
import org.lwjgl.opengl.GL11;
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

    public static final float REF_W = 960f;
    public static final float REF_H = 540f;
    public static float sx = 1f;
    public static float sy = 1f;

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
    private boolean firstInit = true;
    private String activeArea = "none";
    private int mouseDownX, mouseDownY;

    private int renderCycle;
    private String renderPhase = "idle";
    private long lastRenderLog;

    private final EditorBridge bridge;

    private UIComponent rootComponent;
    private UIComponent overlayComponent;

    public EditorScreen(EditorBridge bridge, Path scriptsDir) {
        super(Component.literal("Cinematic Editor"));
        this.scriptsDir = scriptsDir;
        this.bridge = bridge;
        this.doc = new EditorDocument();
        this.sel = new EditorSelection();
        this.playback = new EditorPlayback();
        this.output = new EditorOutput(bridge);

        sel.setListener((clip, kf) -> {
            try {
                if (leftPanel == null) return;
                syncPanels();
                LeftPanelArea.PanelMode m;
                if (clip == null) m = LeftPanelArea.PanelMode.SCRIPT_PROPERTIES;
                else if (kf == null) m = LeftPanelArea.PanelMode.CLIP_PROPERTIES;
                else m = LeftPanelArea.PanelMode.KEYFRAME_PROPERTIES;
                leftPanel.setMode(m);
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
        sx = (float)width / REF_W;
        sy = (float)height / REF_H;
        int menuH = clamp((int)(24 * sy), 20, 28);
        int leftW = clamp((int)(260 * sx), 180, (int)(360 * sx));
        int timelineH = clamp((int)(220 * sy), 150, (int)(280 * sy));
        int previewH = height - menuH - timelineH;

        menuBar = new MenuBarArea(0, 0, width, menuH);
        leftPanel = new LeftPanelArea(0, menuH, leftW, previewH);
        preview = new PreviewArea(leftW, menuH, width - leftW, previewH);
        timeline = new TimelineArea(0, menuH + previewH, width, timelineH);

        // ===== 构建 UI 树 =====
        rootComponent = new UIComponent(0, 0, width, height) {
            @Override public void render(UIContext ctx) {
                for (UIComponent child : getChildren()) {
                    if (child.visible) child.render(ctx);
                }
            }
        };
        menuBar.setParent(rootComponent);
        leftPanel.setParent(rootComponent);
        preview.setParent(rootComponent);
        timeline.setParent(rootComponent);
        rootComponent.children = new java.util.ArrayList<>();
        rootComponent.children.add(menuBar);
        rootComponent.children.add(leftPanel);
        rootComponent.children.add(preview);
        rootComponent.children.add(timeline);

        overlayComponent = new UIComponent(0, 0, width, height) {
            @Override public void render(UIContext ctx) {
                for (UIComponent child : getChildren()) {
                    if (child.visible) child.render(ctx);
                }
            }
        };
        overlayComponent.children = new java.util.ArrayList<>();
        overlayComponent.setParent(rootComponent);
        rootComponent.children.add(overlayComponent);

        RawInputLogger.enable();
        EditorLogger.areaBoundaries(EditorLogger.SCREEN,
                "MenuBar=(0,0," + width + "," + menuH + ")"
                        + " LeftPanel=(0," + menuH + "," + leftW + "," + previewH + ")"
                        + " Preview=(" + leftW + "," + menuH + "," + (width - leftW) + "," + previewH + ")"
                        + " Timeline=(0," + (menuH + previewH) + "," + width + "," + timelineH + ")");

        try {
            Files.createDirectories(scriptsDir);
            Files.createDirectories(scriptsDir.getParent().resolve("temp"));
        } catch (IOException e) {
            EditorLogger.error(EditorLogger.SCREEN, "init", e);
        }

        wireMenu();
        wireTimeline();
        wirePreview();
        wireLeftPanel();

        leftPanel.setDirtyCallback(() -> {
            doc.markDirty();
            doc.setFileName(doc.getMeta().get("id").getAsString());
            menuBar.setScriptName(doc.getFileName());
        });

        if (firstInit) {
            firstInit = false;
            System.out.println("[KILO-DEBUG] EditorScreen.init() firstInit, scriptsDir=" + scriptsDir.toAbsolutePath() + " exists=" + Files.exists(scriptsDir));
            refreshScriptList();
            System.out.println("[KILO-DEBUG] After refreshScriptList, scriptFileNames=" + scriptFileNames);

            bootstrapNewScript();

            menuBar.setAction(I18n.get("editor.action.new_script"));
            menuBar.setStatus(I18n.get("editor.status.editing"), 0xFFAAAAAA);
        } else {
            leftPanel.setScriptFileNames(scriptFileNames);
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
            scriptFilePath = null;
            bootstrapNewScript();
            menuBar.setAction(I18n.get("editor.action.new_script"));
            menuBar.setStatus(I18n.get("editor.status.editing"), 0xFFAAAAAA);
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
            playback.pause();
            playback.setTime(t);
            output.pause();
            output.setTime(t);
            syncPanels();
        });
        timeline.setOnClickClip(clip -> {
            float st = EditorOperations.getStart(clip);
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_CLIP", "startTime=" + st);
            sel.selectClip(clip);
        });
        timeline.setOnClickEmpty(() -> {
            sel.clear();
        });
        timeline.setOnClickKeyframe((kf, clip) -> {
            float globalTime = EditorOperations.getStart(clip) + kf.get("time").getAsFloat();
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_KEYFRAME", "time=" + kf.get("time").getAsFloat() + " global=" + globalTime);
            sel.selectKeyframe(kf, clip);
            playback.setTime(globalTime);
            output.setTime(globalTime);
        });
        timeline.setOnMoveClip((clip, ns) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_CLIP", "from=" + EditorOperations.getStart(clip) + " to=" + ns);
            EditorOperations.moveClip(clip, ns, 0);
            doc.markDirty();
        });
        timeline.setOnResizeLeft((clip, ns) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_LEFT", "clipStart=" + EditorOperations.getStart(clip) + " newStart=" + ns);
            EditorOperations.resizeClipLeft(clip, ns, 0);
            doc.markDirty();
        });
        timeline.setOnResizeRight((clip, ne) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_RIGHT", "clipEnd=" + EditorOperations.getEnd(clip) + " newEnd=" + ne);
            EditorOperations.resizeClipRight(clip, ne, 0);
            doc.markDirty();
        });
        timeline.setOnMoveKeyframe((kf, clip, nt) -> {
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_KEYFRAME", "from=" + kf.get("time").getAsFloat() + " to=" + nt + " clipStart=" + EditorOperations.getStart(clip));
            EditorOperations.moveKeyframe(clip, kf, nt, 0);
            doc.markDirty();
        });
        timeline.setOnToolAddClip(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_CLIP", "");
            JsonObject clip = EditorOperations.addClip(doc.getTracks(), 0, doc.getTotalDuration(), 5, "CAMERA");
            if (clip != null) {
                clip.addProperty("transition", "cut");
                clip.addProperty("interpolation", "linear");
                clip.addProperty("position_mode", "relative");
                clip.addProperty("loop", false);
                JsonArray kfs = clip.getAsJsonArray("keyframes");
                if (kfs != null) {
                    for (JsonElement ke : kfs) {
                        JsonObject kf = ke.getAsJsonObject();
                        if (!kf.has("position")) {
                            JsonObject pos = new JsonObject();
                            pos.addProperty("dx", 0f); pos.addProperty("dy", 0f); pos.addProperty("dz", 0f);
                            kf.add("position", pos);
                        }
                        if (!kf.has("yaw")) kf.addProperty("yaw", 0f);
                        if (!kf.has("pitch")) kf.addProperty("pitch", 0f);
                        if (!kf.has("roll")) kf.addProperty("roll", 0f);
                        if (!kf.has("fov")) kf.addProperty("fov", 70f);
                        if (!kf.has("zoom")) kf.addProperty("zoom", 1f);
                        if (!kf.has("dof")) kf.addProperty("dof", 0f);
                    }
                }
                doc.markDirty();
                sel.selectClip(clip);
            }
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
            if (kf != null) sel.selectKeyframe(kf, sel.getClip());
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
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ARRANGE", "");
            EditorOperations.snapAllClips(doc.getTracks());
            doc.markDirty();
            syncPanels();
        });
    }

    private void wirePreview() {
        preview.setOnPlay(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "PLAY", "btn");
            playback.play();
            output.play();
            menuBar.setStatus(I18n.get("editor.status.playing"), 0xFF44AA44);
            menuBar.setAction(I18n.get("editor.action.playback_started"));

        });
        preview.setOnPause(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "PAUSE", "btn");
            playback.pause();
            output.pause();
            menuBar.setStatus(I18n.get("editor.status.paused"), 0xFFBBBB44);
            menuBar.setAction(I18n.get("editor.action.playback_paused"));

        });
        preview.setOnStop(() -> {
            EditorLogger.action(EditorLogger.PREVIEW, "STOP", "btn");
            playback.stop();
            output.setTime(0);
            syncPanels();
            menuBar.setStatus(I18n.get("editor.status.editing"), 0xFFAAAAAA);
            menuBar.setAction(I18n.get("editor.action.playback_stopped"));
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
            scriptFilePath = null;
            bootstrapNewScript();
            menuBar.setAction(I18n.get("editor.action.new_script"));
            menuBar.setStatus(I18n.get("editor.status.editing"), 0xFFAAAAAA);
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
        leftPanel.setOnBehaviorFlag(s -> {
            String[] parts = s.split("=");
            if (parts.length == 2) {
                EditorLogger.action(EditorLogger.LEFT, "BEHAVIOR_FLAG", parts[0] + "=" + parts[1]);
                doc.getMeta().addProperty(parts[0], Boolean.parseBoolean(parts[1]));
                doc.markDirty();
            }
        });
    }

    // ========== 新建脚本引导（抽取公共块） ==========

    private void bootstrapNewScript() {
        doc.reset();

        // CAMERA track — first clip with default fields
        JsonObject clip = EditorOperations.addClip(doc.getTracks(), 0, 0, 10, "CAMERA");
        if (clip != null) {
            clip.addProperty("transition", "cut");
            clip.addProperty("interpolation", "linear");
            clip.addProperty("position_mode", "relative");
            clip.addProperty("loop", false);
            JsonArray kfs = clip.getAsJsonArray("keyframes");
            if (kfs != null) {
                for (JsonElement ke : kfs) {
                    JsonObject kf = ke.getAsJsonObject();
                    JsonObject pos = new JsonObject();
                    pos.addProperty("dx", 0f); pos.addProperty("dy", 0f); pos.addProperty("dz", 0f);
                    kf.add("position", pos);
                    kf.addProperty("yaw", 0f); kf.addProperty("pitch", 0f); kf.addProperty("roll", 0f);
                    kf.addProperty("fov", 70f); kf.addProperty("zoom", 1f); kf.addProperty("dof", 0f);
                }
            }
        }

        // LETTERBOX track — full-duration clip
        JsonObject lbClip = new JsonObject();
        lbClip.addProperty("start_time", 0f);
        lbClip.addProperty("duration", 10f);
        JsonArray lbs = new JsonArray();
        JsonObject lf0 = new JsonObject(); lf0.addProperty("time", 0f); lf0.addProperty("aspect_ratio", 2.35f);
        JsonObject lf1 = new JsonObject(); lf1.addProperty("time", 10f); lf1.addProperty("aspect_ratio", 2.35f);
        lbs.add(lf0); lbs.add(lf1);
        lbClip.add("keyframes", lbs);
        doc.getTracks().get(1).getAsJsonObject().getAsJsonArray("clips").add(lbClip);

        if (clip != null) sel.selectClip(clip);
    }

    // ══════════════════════════════════════════════════════════════
    //  SYNC
    // ══════════════════════════════════════════════════════════════

    private void syncPanels() {
        float dur = EditorOperations.recalcDuration(doc.getTracks());
        doc.setTotalDuration(dur);

        menuBar.setScriptName(doc.getFileName());
        String trackType = findSelectedTrackType();
        leftPanel.setData(doc.getMeta(), sel.getClip(), sel.getKeyframe());
        leftPanel.setTotalDuration(dur);
        leftPanel.setSelectedTrackType(trackType);

        float time = playback.getTime();
        boolean canAddKf = EditorOperations.canAddKeyframeAt(sel.getClip(), time);
        timeline.setData(doc.getTimeline(), sel.getClip(), sel.getKeyframe(), canAddKf);
        timeline.setPlayheadTime(time);
        preview.setCurrentTime(time);

        output.markDirty(doc.toJson(), time);
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
        else {
            refreshScriptList();
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_LIST);
        }
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
            menuBar.setAction(I18n.get("editor.action.saved", dest.getFileName().toString()));
            EditorLogger.action(EditorLogger.SCREEN, "SAVE_SCRIPT", "path=" + savedPath + " success=true");
        } catch (IOException e) {
            EditorLogger.action(EditorLogger.SCREEN, "SAVE_SCRIPT", "path=" + scriptFilePath + " success=false error=" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void openScript(String fileName) {
        try {
            Path src = scriptsDir.resolve(fileName);
            Path tempDir = scriptsDir.getParent().resolve("temp");
            Files.createDirectories(tempDir);
            Path dst = tempDir.resolve(fileName);
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String json = Files.readString(dst);
            doc.loadFromJson(json);
            String loadedId = doc.getMeta().has("id") ? doc.getMeta().get("id").getAsString() : "";
            doc.setFileName(loadedId.isEmpty() ? fileName.replace(".json", "") : loadedId);
            scriptFilePath = src.toString();
            playback.setTime(0);
            sel.clear();
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
            syncPanels();
            menuBar.setAction(I18n.get("editor.action.opened", fileName));
            menuBar.setStatus(I18n.get("editor.status.editing"), 0xFFAAAAAA);
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
        System.out.println("[KILO-DEBUG] refreshScriptList: dir=" + scriptsDir.toAbsolutePath() + " exists=" + Files.exists(scriptsDir));
        if (Files.exists(scriptsDir)) {
            try (Stream<Path> files = Files.list(scriptsDir)) {
                List<Path> allFiles = files.collect(java.util.stream.Collectors.toList());
                System.out.println("[KILO-DEBUG]   files in dir: " + allFiles.stream().map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toList()));
                allFiles.stream()
                        .filter(f -> f.toString().endsWith(".json"))
                        .map(f -> f.getFileName().toString()).sorted().forEach(scriptFileNames::add);
            } catch (IOException e) {
                System.out.println("[KILO-DEBUG]   IOException: " + e.getMessage());
            }
        }
        System.out.println("[KILO-DEBUG]   matched .json files: " + scriptFileNames);
        leftPanel.setScriptFileNames(scriptFileNames);
        if (leftPanel.getMode() == LeftPanelArea.PanelMode.SCRIPT_LIST) leftPanel.build();
    }

    // ══════════════════════════════════════════════════════════════
    //  RENDER
    // ══════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        PreviewCapture.capture(minecraft);
        renderCycle++;
        String cycleStr = "cycle=" + renderCycle;
        // per-frame RENDER_START log suppressed — too noisy for debugging

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
            output.tick();
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

        renderPhase = "ui_tree";
        try {
            if (rootComponent != null) {
                rootComponent.render(ctx);
                RenderSystem.depthFunc(GL11.GL_ALWAYS);
                rootComponent.renderOverlay(ctx);
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=ui_tree " + cycleStr, e); return;
        }

        renderPhase = "done";
        // per-frame RENDER_TICK log suppressed — too noisy for debugging
    }

    // ══════════════════════════════════════════════════════════════
    //  INPUT
    // ══════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (rootComponent == null) return false;
        leftPanel.clearTextFocus();
        UIContext ctx = makeCtx(mx, my, button);
        mouseDownX = (int) mx; mouseDownY = (int) my;
        EditorLogger.mousePressed(EditorLogger.SCREEN, button, (int) mx, (int) my, activeArea);
        try {
            if (rootComponent.mouseClicked(ctx)) { syncPanels(); return true; }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "mouseClicked crashed button=" + button, e);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (rootComponent == null) return false;
        try {
            UIContext ctx = makeCtx(mx, my, button);
            ctx.mouseDX = dx; ctx.mouseDY = dy;
            return rootComponent.mouseDragged(ctx);
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "mouseDragged crashed", e);
            return false;
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (rootComponent == null) return false;
        try {
            UIContext ctx = makeCtx(mx, my, button);
            return rootComponent.mouseReleased(ctx);
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "mouseReleased crashed", e);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scroll) {
        if (rootComponent == null) return false;
        try {
            UIContext ctx = makeCtx(mx, my, 0);
            return rootComponent.mouseScrolled(ctx, scroll);
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
            int editorKeyCode = CinematicKeyBindings.EDITOR_KEY != null ? CinematicKeyBindings.EDITOR_KEY.getKey().getValue() : -1;
            if (keyCode == 256 || (editorKeyCode > 0 && keyCode == editorKeyCode)) { EditorLogger.action(EditorLogger.SCREEN, "CLOSE", "ESC"); CinematicKeyBindings.notifyEditorClosed(); onClose(); return true; }

            UIComponent focused = leftPanel.getFocusedInput();
            if (focused instanceof IFocusable f && f.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (sel.getKeyframe() != null && handleKeyframeKey(keyCode)) { return true; }
            if (sel.getClip() != null && handleClipKey(keyCode)) { return true; }
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
            UIComponent focusedInput = leftPanel.getFocusedInput();
            if (focusedInput instanceof IFocusable f) {
                EditorLogger.keyPress(EditorLogger.SCREEN, "charTyped", (int) codePoint,
                        "char=" + (codePoint > 32 ? String.valueOf(codePoint) : "CTRL"));
                f.charTyped(codePoint);
                return true;
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "charTyped crashed codePoint=" + (int) codePoint, e);
        }
        return false;
    }

    private boolean handleKeyframeKey(int keyCode) {
        try {
            JsonObject kf = sel.getKeyframe();
            float step = hasShiftDown() ? 5 : 0.5f;
            String dir;
            if (keyCode == 265) { addTo(kf, "yaw", step); dir = "yaw+"; }
            else if (keyCode == 264) { addTo(kf, "yaw", -step); dir = "yaw-"; }
            else if (keyCode == 263) { addTo(kf, "time", -step); dir = "time-"; }
            else if (keyCode == 262) { addTo(kf, "time", step); dir = "time+"; }
            else return false;
            EditorLogger.action(EditorLogger.SCREEN, "KEYFRAME_NUDGE", dir + " step=" + step);
            doc.markDirty();
            return true;
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "handleKeyframeKey crashed", e);
            return false;
        }
    }

    private boolean handleClipKey(int keyCode) {
        try {
            JsonObject clip = sel.getClip();
            float step = hasShiftDown() ? 1 : 0.1f;
            if (keyCode == 263) { EditorOperations.moveClip(clip, EditorOperations.getStart(clip) - step, 0); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "left step=" + step); doc.markDirty(); return true; }
            else if (keyCode == 262) { EditorOperations.moveClip(clip, EditorOperations.getStart(clip) + step, 0); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "right step=" + step); doc.markDirty(); return true; }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "handleClipKey crashed", e);
        }
        return false;
    }

    private static void addTo(JsonObject obj, String key, float delta) {
        if (obj.has(key)) obj.addProperty(key, obj.get(key).getAsFloat() + delta);
    }

    @Override
    public void onClose() {
        playback.stop();
        bridge.stop();
        PreviewCapture.destroy();
        RawInputLogger.disable();
        EditorLogger.close();
        if (minecraft != null) minecraft.setScreen(null);
    }

    private String findSelectedTrackType() {
        JsonObject clip = sel.getClip();
        if (clip == null) return "CAMERA";
        for (JsonElement te : doc.getTracks()) {
            for (JsonElement ce : te.getAsJsonObject().getAsJsonArray("clips")) {
                if (ce.getAsJsonObject() == clip) {
                    String t = te.getAsJsonObject().get("type").getAsString();
                    return t == null ? "CAMERA" : t.toUpperCase();
                }
            }
        }
        return "CAMERA";
    }

    @Override public boolean isPauseScreen() { return false; }

    private UIContext makeCtx(double mx, double my, int button) {
        UIContext ctx = new UIContext(null, font, width, height, 0, (int) mx, (int) my);
        ctx.mouseButton = button;
        ctx.ctrlDown = hasControlDown();
        ctx.shiftDown = hasShiftDown();
        return ctx;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
