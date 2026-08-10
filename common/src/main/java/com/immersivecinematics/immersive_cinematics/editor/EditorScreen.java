package com.immersivecinematics.immersive_cinematics.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.immersivecinematics.immersive_cinematics.editor.area.*;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.debug.RawInputLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
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
import java.util.Set;
import java.util.stream.Stream;

public class EditorScreen extends Screen {

    // REF_W/REF_H/sx/sy 已迁移到 Scale 类

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

    private final EditorUndoManager undoManager = new EditorUndoManager();

    private UIComponent rootComponent;
    private ContextMenu contextMenu;
    private List<JsonObject> clipboard;
    private long lastSpacePress;
    /** A6 轨道显隐（会话级，引用轨道 JSON 对象） */
    private final Set<JsonObject> hiddenTracks = new java.util.HashSet<>();
    /** B2 轨道锁定（会话级） */
    private final Set<JsonObject> lockedTracks = new java.util.HashSet<>();
    /** B2 轨道静音（会话级，仅影响预览推送） */
    private final Set<JsonObject> mutedTracks = new java.util.HashSet<>();
    /** 组 7：gizmo/滑块拖拽中标志 — 拖拽期走直控播放体系（零重载），松手一次性推送 */
    private boolean gizmoDragging = false;

    public EditorScreen(EditorBridge bridge, Path scriptsDir) {
        super(Component.literal("Cinematic Editor"));
        this.scriptsDir = scriptsDir;
        this.doc = new EditorDocument();
        this.sel = new EditorSelection();
        this.playback = new EditorPlayback();
        this.output = new EditorOutput(bridge);

        sel.setListener((clips, kf) -> {
            try {
                if (leftPanel == null) return;
                syncPanels();
                LeftPanelArea.PanelMode m;
                if (clips == null || clips.isEmpty()) m = LeftPanelArea.PanelMode.SCRIPT_PROPERTIES;
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
        Scale.update(width, height);
        int menuH = clamp((int)(24 * Scale.sy), 20, 28);
        int leftW = clamp((int)(260 * Scale.sx), 180, (int)(360 * Scale.sx));
        int timelineH = clamp((int)(220 * Scale.sy), 150, (int)(280 * Scale.sy));
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
        rootComponent.addChild(menuBar);
        rootComponent.addChild(leftPanel);
        rootComponent.addChild(preview);
        rootComponent.addChild(timeline);

        contextMenu = new ContextMenu();
        rootComponent.addChild(contextMenu);
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
            undoManager.push(doc.toJson());
            doc.markDirty();
            // B 模型：字段编辑后保证转场对齐不变量（transition_duration 变化时后续片段重叠修正）
            EditorOperations.applyTransitionAlignment(doc.getTracks());
            pushScriptUpdate();
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
    }

    // ══════════════════════════════════════════════════════════════
    //  CLIPBOARD HELPERS (used by keyboard shortcuts + context menus)
    // ══════════════════════════════════════════════════════════════

    private void copySelectedClips() {
        if (sel.getClips().isEmpty()) return;
        clipboard = new ArrayList<>();
        for (JsonObject clip : sel.getClips()) {
            int trackIdx = EditorOperations.findTrackIndex(doc.getTracks(), clip);
            String trackType = trackIdx >= 0 ? doc.getTracks().get(trackIdx).getAsJsonObject().get("type").getAsString() : "CAMERA";
            JsonObject copy = clip.deepCopy();
            copy.addProperty("_trackType", trackType);
            clipboard.add(copy);
        }
    }

    private void cutSelectedClips() {
        if (sel.getClip() == null) return;
        clipboard = new ArrayList<>();
        undoManager.push(doc.toJson());
        for (JsonObject c : sel.getClips()) {
            clipboard.add(c.deepCopy());
            EditorOperations.deleteClip(doc.getTracks(), c);
        }
        sel.clear(); doc.markDirty(); pushScriptUpdate();
    }

    private void pasteClips() {
        if (clipboard == null || clipboard.isEmpty()) return;
        undoManager.push(doc.toJson());
        List<JsonObject> pasted = new ArrayList<>();
        float offset = 0;
        for (JsonObject clip : clipboard) {
            JsonObject copy = clip.deepCopy();
            EditorOperations.moveClip(copy, EditorOperations.getStart(clip) + offset, 0);
            String clipTrackType = clip.has("_trackType") ? clip.get("_trackType").getAsString() : "CAMERA";
            JsonObject targetTrack = EditorOperations.getTrackByType(doc.getTracks(), clipTrackType);
            if (targetTrack == null) {
                targetTrack = EditorOperations.addTrack(doc.getTracks(), clipTrackType);
            }
            targetTrack.getAsJsonArray("clips").add(copy);
            pasted.add(copy);
            offset += 0.5f;
        }
        if (!pasted.isEmpty()) { sel.selectClips(pasted); doc.markDirty(); pushScriptUpdate(); }
    }

    private void selectAllClips() {
        List<JsonObject> allClips = new ArrayList<>();
        for (JsonElement te : doc.getTracks()) {
            for (JsonElement ce : te.getAsJsonObject().getAsJsonArray("clips")) {
                allClips.add(ce.getAsJsonObject());
            }
        }
        sel.selectClips(allClips);
    }

    private void deleteSelectedClips() {
        undoManager.push(doc.toJson());
        for (JsonObject clip : sel.getClips()) {
            EditorOperations.deleteClip(doc.getTracks(), clip);
        }
        sel.clear(); doc.markDirty(); pushScriptUpdate();
    }

    private void wireTimeline() {
        timeline.setOnClickAtTime(t -> {
            EditorLogger.playhead(EditorLogger.SCREEN, t, 0, "ruler_click");
            seekTo(t);
        });
        timeline.setOnClickClip(clip -> {
            float st = EditorOperations.getStart(clip);
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_CLIP", "startTime=" + st);
            sel.selectClip(clip);
        });
        timeline.setOnSelectClips(clips -> {
            sel.selectClips(clips);
        });
        timeline.setOnClickKeyframe((kf, clip) -> {
            float globalTime = EditorOperations.getStart(clip) + kf.get("time").getAsFloat();
            // Last keyframe at clip duration boundary needs epsilon to stay within active range [start, start+duration)
            float clipEnd = EditorOperations.getStart(clip) + EditorOperations.getDuration(clip);
            if (globalTime >= clipEnd) globalTime = clipEnd - 0.001f;
            EditorLogger.action(EditorLogger.TIMELINE, "SELECT_KEYFRAME", "time=" + kf.get("time").getAsFloat() + " global=" + globalTime);
            sel.selectKeyframe(kf, clip);
            // 组 3：关键帧点击 = 定位（暂停，不再继续走）——修复播放中点击关键帧播放头继续 tick 的抽搐
            seekTo(globalTime);
        });
        timeline.setOnMoveClips((clips, delta) -> {
            if (clips.isEmpty() || Math.abs(delta) < 0.001f) return;
            undoManager.push(doc.toJson());
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_CLIPS", "count=" + clips.size() + " delta=" + delta);
            EditorOperations.moveClips(doc.getTracks(), clips, delta);
            doc.markDirty();
            // B 模型：拖拽后保持转场重叠不变量
            EditorOperations.applyTransitionAlignment(doc.getTracks());
            pushScriptUpdate();
            syncPanels();
        });
        timeline.setOnToggleClip(clip -> sel.toggleClip(clip));
        // B2：轨道头按钮（👁 显隐 / 🔒 锁定 / 🔇 静音 — 全部会话级）
        timeline.setOnToggleTrackHidden(track -> {
            if (!hiddenTracks.remove(track)) hiddenTracks.add(track);
            timeline.setHiddenTracks(hiddenTracks);
        });
        timeline.setOnToggleTrackLocked(track -> {
            if (!lockedTracks.remove(track)) lockedTracks.add(track);
            timeline.setLockedTracks(lockedTracks);
        });
        timeline.setOnToggleTrackMuted(track -> {
            if (!mutedTracks.remove(track)) mutedTracks.add(track);
            timeline.setMutedTracks(mutedTracks);
            pushScriptUpdate();   // 静音改变预览音频，需显式推送
        });
        timeline.setOnResizeLeft((clip, ns) -> {
            undoManager.push(doc.toJson());
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_LEFT", "clipStart=" + EditorOperations.getStart(clip) + " newStart=" + ns);
            EditorOperations.resizeClipLeft(clip, ns, 0);
            doc.markDirty();
            EditorOperations.applyTransitionAlignment(doc.getTracks());
            pushScriptUpdate();
        });
        timeline.setOnResizeRight((clip, ne) -> {
            undoManager.push(doc.toJson());
            EditorLogger.action(EditorLogger.TIMELINE, "RESIZE_CLIP_RIGHT", "clipEnd=" + EditorOperations.getEnd(clip) + " newEnd=" + ne);
            EditorOperations.resizeClipRight(clip, ne, 0);
            doc.markDirty();
            EditorOperations.applyTransitionAlignment(doc.getTracks());
            pushScriptUpdate();
        });
        timeline.setOnMoveKeyframe((kf, clip, nt) -> {
            undoManager.push(doc.toJson());
            EditorLogger.action(EditorLogger.TIMELINE, "MOVE_KEYFRAME", "from=" + kf.get("time").getAsFloat() + " to=" + nt + " clipStart=" + EditorOperations.getStart(clip));
            EditorOperations.moveKeyframe(clip, kf, nt, 0);
            doc.markDirty();
            pushScriptUpdate();
        });
        timeline.setOnToolAddClip(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_CLIP", "");
            // 对象树：加到选中项（关键帧→片段→轨道）所属轨道；无选中兜底轨道 0
            int idx = selectedTrackIndex();
            String type = "CAMERA";
            if (idx >= 0 && idx < doc.getTracks().size()) {
                type = doc.getTracks().get(idx).getAsJsonObject().get("type").getAsString();
            }
            if ("LETTERBOX".equals(type)) type = "CAMERA";
            JsonObject clip = EditorOperations.addClip(doc.getTracks(), idx, doc.getTotalDuration(), 5, type);
            if (clip != null) {
                doc.markDirty();
                pushScriptUpdate();
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
                pushScriptUpdate();
            }
        });
        timeline.setOnToolAddKeyframe(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_KEYFRAME", "at=" + String.format("%.3f", playback.getTime()));
            JsonObject kf = EditorOperations.addKeyframeAt(sel.getClip(), playback.getTime());
            doc.markDirty();
            pushScriptUpdate();
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
                pushScriptUpdate();
            }
        });
        timeline.setOnToolSnap(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ARRANGE", "");
            EditorOperations.snapAllClips(doc.getTracks());
            doc.markDirty();
            pushScriptUpdate();
            syncPanels();
        });

        // A9：拖 AUDIO clip/关键帧时实时推送预览（100ms 节流，由 TimelineArea 触发）
        timeline.setOnDragLivePreview(() -> output.markDirty(doc.toJson(), playback.getTime()));

        timeline.setOnToolAddTrack(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_TRACK", "");
            // 弹出轨道类型选择上下文菜单
            contextMenu.clearEntries();
            for (String t : new String[]{"CAMERA", "AUDIO", "EVENT", "MOD_EVENT", "OVERLAY"}) {
                contextMenu.addEntry(I18n.get("editor.contextmenu.add_track", t), 0xFFAAAAAA, () -> {
                    undoManager.push(doc.toJson());
                    EditorOperations.addTrack(doc.getTracks(), t);
                    doc.markDirty(); pushScriptUpdate(); syncPanels();
                });
            }
            contextMenu.show(timeline.x + timeline.toolbarW() + 4,
                    timeline.y + timeline.headerH() + 4);
        });

        timeline.setOnToolDeleteTrack(() -> {
            // 对象树：删选中项（关键帧→片段→轨道）所属轨道——不再按顺序删第 0 条（相机轨道）
            int idx = selectedTrackIndex();
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_DELETE_TRACK", "index=" + idx);
            if (idx < 0 || idx >= doc.getTracks().size()) return;
            undoManager.push(doc.toJson());
            hiddenTracks.remove(doc.getTracks().get(idx).getAsJsonObject());
            lockedTracks.remove(doc.getTracks().get(idx).getAsJsonObject());
            mutedTracks.remove(doc.getTracks().get(idx).getAsJsonObject());
            EditorOperations.removeTrack(doc.getTracks(), idx);
            timeline.setSelectedTrackIndex(Math.max(0, idx - 1));
            doc.markDirty(); pushScriptUpdate(); syncPanels();
        });
        // ============= Context menu wiring =============

        // Clip right-click
        timeline.setOnShowClipContext((mx, my) -> {
            contextMenu.clearEntries();
            contextMenu.addEntry(I18n.get("editor.contextmenu.copy"), 0xFFCCCCCC, () -> copySelectedClips());
            contextMenu.addEntry(I18n.get("editor.contextmenu.cut"), 0xFFCCCCCC, () -> cutSelectedClips());
            contextMenu.addEntry(I18n.get("editor.contextmenu.delete_clip"), 0xFFFF6666, () -> deleteSelectedClips());
            contextMenu.addEntry(I18n.get("editor.contextmenu.duplicate"), 0xFFCCCCCC, () -> {
                JsonObject clip = sel.getClip();
                if (clip == null) return;
                undoManager.push(doc.toJson());
                JsonObject copy = clip.deepCopy();
                copy.addProperty("start_time", EditorOperations.getEnd(clip));
                int trackIdx = timeline.getSelectedTrackIndex();
                if (trackIdx >= 0 && trackIdx < doc.getTracks().size()) {
                    doc.getTracks().get(trackIdx).getAsJsonObject().getAsJsonArray("clips").add(copy);
                    EditorOperations.sortTrackClips(doc.getTracks());
                    doc.markDirty(); pushScriptUpdate(); sel.selectClip(copy);
                }
            });
            contextMenu.addSeparator();
            contextMenu.addEntry(I18n.get("editor.contextmenu.split"), 0xFFCCCCCC, () -> {
                JsonObject clip = sel.getClip();
                if (clip != null) {
                    undoManager.push(doc.toJson());
                    JsonObject right = EditorOperations.splitClip(doc.getTracks(), clip, playback.getTime());
                    if (right != null) {
                        // B 模型：拆分后保持转场重叠不变量（左片段尾部转场指向右片段）
                        EditorOperations.applyTransitionAlignment(doc.getTracks());
                        doc.markDirty(); pushScriptUpdate(); sel.selectClip(right);
                    }
                }
            });
            contextMenu.addEntry(I18n.get("editor.contextmenu.add_keyframe"), 0xFFCCCCCC, () -> {
                JsonObject kf = EditorOperations.addKeyframeAt(sel.getClip(), playback.getTime());
                if (kf != null) { doc.markDirty(); pushScriptUpdate(); sel.selectKeyframe(kf, sel.getClip()); }
            });
            contextMenu.show(mx, my);
        });

        // Track label right-click
        timeline.setOnShowTrackLabelContext((mx, my) -> {
            contextMenu.clearEntries();
            int idx = timeline.getSelectedTrackIndex();
            String type = idx >= 0 && idx < doc.getTracks().size()
                    ? doc.getTracks().get(idx).getAsJsonObject().get("type").getAsString() : "";
            contextMenu.addEntry(I18n.get("editor.contextmenu.track_header", type, idx), 0xFF888888, null);
            contextMenu.addSeparator();
            contextMenu.addEntry(I18n.get("editor.contextmenu.add_clip_here"), 0xFFCCCCCC, () -> {
                if (idx < 0) return;
                undoManager.push(doc.toJson());
                JsonObject clip = EditorOperations.addClip(doc.getTracks(), idx, playback.getTime(), 5, type);
                if (clip != null) { doc.markDirty(); pushScriptUpdate(); sel.selectClip(clip); }
            });
            contextMenu.addEntry(I18n.get("editor.contextmenu.delete_track"), 0xFFFF6666, () -> {
                if (idx < 0 || idx >= doc.getTracks().size()) return;
                undoManager.push(doc.toJson());
                hiddenTracks.remove(doc.getTracks().get(idx).getAsJsonObject());
                lockedTracks.remove(doc.getTracks().get(idx).getAsJsonObject());
                mutedTracks.remove(doc.getTracks().get(idx).getAsJsonObject());
                EditorOperations.removeTrack(doc.getTracks(), idx);
                timeline.setSelectedTrackIndex(Math.max(0, idx - 1));
                doc.markDirty(); pushScriptUpdate(); syncPanels();
            });
            // B3：Delete All Empty Tracks（Olive 借鉴，重建菜单为确认态）
            contextMenu.addEntry(I18n.get("editor.contextmenu.delete_empty_tracks"), 0xFFFF6666, () -> {
                contextMenu.clearEntries();
                contextMenu.addEntry(I18n.get("editor.contextmenu.confirm_delete_empty"), 0xFFFF6666, () -> {
                    undoManager.push(doc.toJson());
                    JsonArray tracks = doc.getTracks();
                    for (int i = tracks.size() - 1; i >= 0; i--) {
                        JsonObject t = tracks.get(i).getAsJsonObject();
                        if (t.has("clips") && t.getAsJsonArray("clips").size() == 0) {
                            hiddenTracks.remove(t); lockedTracks.remove(t); mutedTracks.remove(t);
                            EditorOperations.removeTrack(tracks, i);
                        }
                    }
                    doc.markDirty(); pushScriptUpdate(); syncPanels(); contextMenu.hide();
                });
                contextMenu.addEntry(I18n.get("gui.cancel"), 0xFFAAAAAA, () -> contextMenu.hide());
            });
            for (String t : new String[]{"CAMERA", "AUDIO", "EVENT", "MOD_EVENT", "OVERLAY"}) {
                contextMenu.addEntry(I18n.get("editor.contextmenu.add_track", t), 0xFFAAAAAA, () -> {
                    EditorOperations.addTrack(doc.getTracks(), t);
                    doc.markDirty(); pushScriptUpdate(); syncPanels();
                });
            }
            contextMenu.show(mx, my);
        });

        // Timeline empty area right-click
        timeline.setOnShowTimelineContext((mx, my) -> {
            contextMenu.clearEntries();
            int trackIdx = (my - timeline.canvasY()) / (int)(28 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);
            int finalTrackIdx = Math.max(0, Math.min(trackIdx, doc.getTracks().size() - 1));
            for (JsonElement te : doc.getTracks()) {
                String t = te.getAsJsonObject().get("type").getAsString();
                if ("LETTERBOX".equals(t)) continue;
                contextMenu.addEntry(I18n.get("editor.contextmenu.add_clip", t), 0xFFCCCCCC, () -> {
                    undoManager.push(doc.toJson());
                    JsonObject clip = EditorOperations.addClip(doc.getTracks(), finalTrackIdx, doc.getTotalDuration(), 5, t);
                    if (clip != null) { doc.markDirty(); pushScriptUpdate(); sel.selectClip(clip); }
                });
            }
            contextMenu.addEntry(I18n.get("editor.contextmenu.add_keyframe"), 0xFFCCCCCC, () -> {
                JsonObject clip = sel.getClip();
                if (clip != null) {
                    JsonObject kf = EditorOperations.addKeyframeAt(clip, playback.getTime());
                    if (kf != null) { doc.markDirty(); pushScriptUpdate(); }
                }
            });
            contextMenu.addEntry(I18n.get("editor.contextmenu.select_all"), 0xFFCCCCCC, () -> selectAllClips());
            contextMenu.addEntry(I18n.get("editor.contextmenu.paste"), 0xFFCCCCCC, () -> { pasteClips(); pushScriptUpdate(); syncPanels(); });
            contextMenu.addSeparator();
            for (String t : new String[]{"CAMERA", "AUDIO", "EVENT", "MOD_EVENT", "OVERLAY"}) {
                contextMenu.addEntry(I18n.get("editor.contextmenu.add_track", t), 0xFFAAAAAA, () -> {
                    EditorOperations.addTrack(doc.getTracks(), t);
                    doc.markDirty(); pushScriptUpdate(); syncPanels();
                });
            }
            contextMenu.addSeparator();
            contextMenu.addEntry(I18n.get("editor.contextmenu.snap", "\u00AB\u00BB"), 0xFFCCCCCC, () -> {
                EditorOperations.snapAllClips(doc.getTracks()); doc.markDirty(); pushScriptUpdate(); syncPanels();
            });
            contextMenu.show(mx, my);
        });

        // Ruler right-click
        timeline.setOnShowRulerContext((mx, my) -> {
            contextMenu.clearEntries();
            float t = timeline.xToTime(mx);
            contextMenu.addEntry(I18n.get("editor.contextmenu.jump_playhead"), 0xFFCCCCCC, () -> {
                seekTo(t);
            });
            contextMenu.addEntry(I18n.get("editor.contextmenu.zoom_to_fit"), 0xFFCCCCCC, () -> {
                float totalDur = doc.getTotalDuration();
                if (totalDur > 0) {
                    timeline.setPixelsPerSecond(Math.min(timeline.canvasW() / totalDur, 5000));
                    timeline.setScrollOffset(0);
                    syncPanels();
                }
            });
            contextMenu.addSeparator();
            // C2：A-B 循环点
            contextMenu.addEntry(I18n.get("editor.contextmenu.set_in"), 0xFFCCCCCC, () -> setLoopPoint(true, Math.max(0, t)));
            contextMenu.addEntry(I18n.get("editor.contextmenu.set_out"), 0xFFCCCCCC, () -> setLoopPoint(false, Math.max(0, t)));
            contextMenu.addEntry(I18n.get("editor.contextmenu.clear_loop"), 0xFFCCCCCC, () -> clearLoop());
            contextMenu.show(mx, my);
        });

        // C1：marker 右键菜单
        timeline.setOnShowMarkerContext((mt, xy) -> {
            contextMenu.clearEntries();
            contextMenu.addEntry(I18n.get("editor.contextmenu.delete_marker"), 0xFFFF6666, () -> removeMarker(mt));
            contextMenu.show(xy[0], xy[1]);
        });
        timeline.setOnToolAddClip(() -> {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOL_ADD_CLIP", "");
            JsonObject selC = sel.getClip();
            int trackIdx = 0;
            String trackType = "CAMERA";
            if (selC != null) {
                int ti = EditorOperations.findTrackIndex(doc.getTracks(), selC);
                if (ti >= 0) {
                    trackIdx = ti;
                    trackType = doc.getTracks().get(ti).getAsJsonObject().get("type").getAsString();
                }
            }
            if ("LETTERBOX".equals(trackType)) { trackType = "CAMERA"; }
            JsonObject clip = EditorOperations.addClip(doc.getTracks(), trackIdx, doc.getTotalDuration(), 5, trackType);
            if (clip != null) { doc.markDirty(); sel.selectClip(clip); }
        });
    }

    private void wirePreview() {
        // 播放/暂停合并 toggle:播放中点击=暂停(停在当前),非播放点击=播放/继续(从当前位置)
        preview.setOnPlay(() -> {
            if (playback.isPlaying()) {
                EditorLogger.action(EditorLogger.PREVIEW, "PAUSE", "btn");
                playback.pause();
                output.pause();
                preview.setPlayingState(false, true);
                menuBar.setStatus(I18n.get("editor.status.paused"), 0xFFBBBB44);
                menuBar.setAction(I18n.get("editor.action.playback_paused"));
            } else {
                EditorLogger.action(EditorLogger.PREVIEW, "PLAY", "btn");
                // 组 7：播放前退出直控态（否则 CameraTrackPlayer 被跳过，播放不写相机）
                CameraManager.INSTANCE.setPreviewDirectControl(false);
                gizmoDragging = false;
                playback.play();
                output.play();
                preview.setPlayingState(true, false);
                menuBar.setStatus(I18n.get("editor.status.playing"), 0xFF44AA44);
                menuBar.setAction(I18n.get("editor.action.playback_started"));
            }
        });
        preview.setOnStop(() -> {
            // 终止 = 重置播放头到脚本第一帧并保持预览激活(相机回第一帧视角,而非玩家视角;
            // 玩家视角由脚本时间空隙自然产生)
            EditorLogger.action(EditorLogger.PREVIEW, "STOP", "btn");
            playback.stop();
            output.stop();
            preview.setPlayingState(false, false);
            syncPanels();
            menuBar.setStatus(I18n.get("editor.status.editing"), 0xFFAAAAAA);
            menuBar.setAction(I18n.get("editor.action.playback_stopped"));
        });

        // A2/A3：相机快速控制 — 三维球 + 滑块（一次拖拽 = 一个 undo 快照）
        preview.setOnSliderDragStart(() -> { undoManager.push(doc.toJson()); gizmoDragging = true; });
        preview.setOnSliderChanged((key, value) -> applyCameraParam(key, value));
        preview.setOnGizmoDragStart(() -> { undoManager.push(doc.toJson()); gizmoDragging = true; });
        preview.setOnGizmoDelta((dyaw, dpitch) -> {
            applyCameraParamDelta("yaw", dyaw);
            applyCameraParamDelta("pitch", dpitch);
        });
        // 组 5：单轴锁定回调（拖绿线只转 pitch、拖蓝线只转 yaw）
        preview.setOnGizmoYawOnly(v -> applyCameraParamDelta("yaw", v));
        preview.setOnGizmoPitchOnly(v -> applyCameraParamDelta("pitch", v));
        preview.setOnGizmoRollDelta(droll -> applyCameraParamDelta("roll", droll));
        preview.setOnGizmoReset(() -> {
            undoManager.push(doc.toJson());
            applyCameraParam("yaw", 0f);
            applyCameraParam("pitch", 0f);
            applyCameraParam("roll", 0f);
        });
        // 组 7：拖拽松手 — 退出直控、一次性推送文档（播放器下次启动用新数据）
        preview.setOnGizmoDragEnd(() -> finishGizmoDrag());
        preview.setOnSliderDragEnd(() -> finishGizmoDrag());
    }

    // ══════════════════════════════════════════════════════════════
    //  A3：相机参数写入（选中 CAMERA clip 播放头处关键帧）
    // ══════════════════════════════════════════════════════════════

    private void applyCameraParam(String key, float value) {
        JsonObject clip = sel.getClip();
        if (clip == null || !"CAMERA".equals(findSelectedTrackType())) return;
        float globalTime = playback.getTime();
        float localTime = globalTime - EditorOperations.getStart(clip);
        if (localTime < 0 || localTime > EditorOperations.getDuration(clip)) return;
        JsonObject kf = findKeyframeAt(clip, localTime);
        if (kf == null) kf = EditorOperations.addKeyframeAt(clip, globalTime);
        if (kf == null) return;
        if ("pitch".equals(key)) value = Math.max(-89f, Math.min(89f, value));
        if ("fov".equals(key)) value = Math.max(30f, Math.min(110f, value));
        if ("zoom".equals(key)) value = Math.max(0.1f, Math.min(5f, value));
        kf.addProperty(key, value);
        doc.markDirty();
        if (gizmoDragging) {
            // 组 7：拖拽直控播放体系（bbs 式"编辑即生效"）— 零解析零重启，相机由编辑器直驱
            CameraManager.INSTANCE.setPreviewDirectControl(true);
            float[] cur = cameraValuesFrom(kf);
            CameraManager.INSTANCE.previewSetCamera(cur[0], cur[1], cur[2], cur[3], cur[4]);
        } else {
            pushScriptUpdate();
        }
    }

    /** 组 7：读取关键帧相机值（缺失补默认 0/0/0/70/1）；kf 为 null 时返回默认值 */
    private static float[] cameraValuesFrom(JsonObject kf) {
        float yaw = kf != null && kf.has("yaw") ? kf.get("yaw").getAsFloat() : 0f;
        float pitch = kf != null && kf.has("pitch") ? kf.get("pitch").getAsFloat() : 0f;
        float roll = kf != null && kf.has("roll") ? kf.get("roll").getAsFloat() : 0f;
        float fov = kf != null && kf.has("fov") ? kf.get("fov").getAsFloat() : 70f;
        float zoom = kf != null && kf.has("zoom") ? kf.get("zoom").getAsFloat() : 1f;
        return new float[]{yaw, pitch, roll, fov, zoom};
    }

    /** 组 7/组 A：拖拽松手收尾 — 退出直控、直接同步接管播放体系（零节流零重启：replaceScript 增量替换） */
    private void finishGizmoDrag() {
        gizmoDragging = false;
        CameraManager.INSTANCE.setPreviewDirectControl(false);
        CameraManager.INSTANCE.pushScript(filterPreviewJson());
        syncPanels();
    }

    private void applyCameraParamDelta(String key, float delta) {
        JsonObject clip = sel.getClip();
        if (clip == null || !"CAMERA".equals(findSelectedTrackType())) return;
        float globalTime = playback.getTime();
        float localTime = globalTime - EditorOperations.getStart(clip);
        if (localTime < 0 || localTime > EditorOperations.getDuration(clip)) return;
        JsonObject kf = findKeyframeAt(clip, localTime);
        if (kf == null) kf = EditorOperations.addKeyframeAt(clip, globalTime);
        if (kf == null) return;
        float cur = kf.has(key) ? kf.get(key).getAsFloat() : 0f;
        applyCameraParam(key, cur + delta);
    }

    private static JsonObject findKeyframeAt(JsonObject clip, float localTime) {
        JsonArray kfs = EditorOperations.keyframes(clip);
        if (kfs == null) return null;
        for (JsonElement ke : kfs) {
            JsonObject k = ke.getAsJsonObject();
            if (Math.abs(k.get("time").getAsFloat() - localTime) < 0.001f) return k;
        }
        return null;
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
        leftPanel.setOnTrackSelected(trackIdx -> {
            timeline.setSelectedTrackIndex(trackIdx);
            // Clear clip selection when selecting a track
            sel.clear();
            syncPanels();
        });

        leftPanel.setOnToggleTrackVisible(track -> {
            if (!hiddenTracks.remove(track)) hiddenTracks.add(track);
            timeline.setHiddenTracks(hiddenTracks);
            leftPanel.setTracks(doc.getTracks());
            leftPanel.build();
        });

        leftPanel.setOnTrackAdd(type -> {
            // 弹出轨道类型选择
            contextMenu.clearEntries();
            for (String t : new String[]{"CAMERA", "AUDIO", "EVENT", "MOD_EVENT", "OVERLAY"}) {
                contextMenu.addEntry(I18n.get("editor.contextmenu.add_track", t), 0xFFAAAAAA, () -> {
                    undoManager.push(doc.toJson());
                    EditorOperations.addTrack(doc.getTracks(), t);
                    doc.markDirty();
                    pushScriptUpdate();
                    syncPanels();
                });
            }
            contextMenu.show(leftPanel.x + 10, leftPanel.y + 60);
        });

        leftPanel.setOnTrackDelete(trackIdx -> {
            if (trackIdx < 0 || trackIdx >= doc.getTracks().size()) return;
            undoManager.push(doc.toJson());
            EditorOperations.removeTrack(doc.getTracks(), trackIdx);
            timeline.setSelectedTrackIndex(Math.max(0, trackIdx - 1));
            doc.markDirty();
            pushScriptUpdate();
            syncPanels();
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
                    kf.addProperty("fov", 70f); kf.addProperty("zoom", 1f);
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
        JsonObject letterboxTrack = findTrackByType(doc.getTracks(), "LETTERBOX");
        if (letterboxTrack != null) {
            letterboxTrack.getAsJsonArray("clips").add(lbClip);
        }

        if (clip != null) sel.selectClip(clip);
        // N2a：文档加载 = 内容变化，显式推送预览（否则播放仍用旧脚本）
        pushScriptUpdate();
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
        leftPanel.setTracks(doc.getTracks());

        float time = playback.getTime();
        timeline.setSelectedClips(sel.getClips());
        boolean canAddKf = EditorOperations.canAddKeyframeAt(sel.getClip(), time);
        timeline.setData(doc.getTimeline(), sel.getClip(), sel.getKeyframe(), canAddKf);
        timeline.setPlayheadTime(time);
        // 组 3：播放中插值平滑，暂停/定位直接显示精确值（抽搐根因消除）
        timeline.setPlayheadInterpolate(playback.isPlaying());
        preview.setCurrentTime(time);

        // A3：预览相机控件状态同步（需选中 CAMERA clip 才可用——无选中时禁用，避免"显示可用但操作无效"）
        preview.setCameraControlEnabled(sel.getClip() != null && "CAMERA".equals(trackType));
        JsonObject ckf = (sel.getClip() != null && "CAMERA".equals(trackType))
                ? findKeyframeAt(sel.getClip(), playback.getTime() - EditorOperations.getStart(sel.getClip()))
                : null;
        float[] cv = cameraValuesFrom(ckf);
        preview.setCameraValues(cv[0], cv[1], cv[2], cv[3], cv[4]);

        // N2a：不再隐式推送脚本（播放头移动/选中变化是纯 UI 状态）；
        // 编辑动作由各回调显式调用 pushScriptUpdate()
        EditorLogger.sync(EditorLogger.SCREEN, "panels",
                "playbackTime=" + String.format("%.3f", time)
                        + " dirty=" + doc.isDirty()
                        + " selectedClip=" + (sel.getClip() != null ? EditorOperations.getStart(sel.getClip()) : "null")
                        + " selectedKf=" + (sel.getKeyframe() != null ? sel.getKeyframe().get("time").getAsFloat() : "null"));
    }

    /** N2a：显式编辑推送（替代 syncPanels 的隐式 markDirty；200ms 节流由 EditorOutput 处理） */
    private void pushScriptUpdate() {
        output.markDirty(filterPreviewJson(), playback.getTime());
    }

    /**
     * 组 3：统一播放头定位 = 暂停 + 定位 + 同步（剪映/PR 惯例：任何非播放按钮的移动都停在这一刻；音频不触发）。
     * 播放按钮/Space/Enter 是唯一不经过此处的播放状态切换入口。
     */
    private void seekTo(float t) {
        playback.pause();
        playback.setTime(Math.max(0f, t));
        output.pause();
        output.setTime(Math.max(0f, t));
        syncPanels();
    }

    /** B2：静音预览 — 推送副本剔除静音 AUDIO 轨道 clips（保存文件不受影响） */
    private String filterPreviewJson() {
        if (mutedTracks.isEmpty()) return doc.toJson();
        JsonObject copy = com.google.gson.JsonParser.parseString(doc.toJson()).getAsJsonObject();
        JsonArray tracks = copy.getAsJsonObject("timeline").getAsJsonArray("tracks");
        for (int i = 0; i < tracks.size(); i++) {
            JsonObject t = tracks.get(i).getAsJsonObject();
            if ("AUDIO".equals(t.get("type").getAsString())
                    && i < doc.getTracks().size()
                    && mutedTracks.contains(doc.getTracks().get(i).getAsJsonObject())) {
                t.add("clips", new JsonArray());
            }
        }
        return copy.toString();
    }

    // ══════════════════════════════════════════════════════════════
    //  C1：Marker 标记
    // ══════════════════════════════════════════════════════════════

    private void removeMarker(float time) {
        JsonObject timeline = doc.getTimeline();
        JsonArray markers = timeline.has("markers") ? timeline.getAsJsonArray("markers") : null;
        if (markers == null) return;
        undoManager.push(doc.toJson());
        for (int i = markers.size() - 1; i >= 0; i--) {
            if (Math.abs(markers.get(i).getAsJsonObject().get("time").getAsFloat() - time) < 0.001f) {
                markers.remove(i);
            }
        }
        doc.markDirty();
        pushScriptUpdate();
        syncPanels();
    }

    // ══════════════════════════════════════════════════════════════
    //  C2：A-B 循环区间
    // ══════════════════════════════════════════════════════════════

    private void setLoopPoint(boolean isStart, float t) {
        undoManager.push(doc.toJson());
        if (isStart) {
            doc.getTimeline().addProperty("loop_start", t);
            float end = doc.getTimeline().has("loop_end") ? doc.getTimeline().get("loop_end").getAsFloat() : -1;
            if (end >= 0 && end <= t) doc.getTimeline().remove("loop_end");
        } else {
            float start = doc.getTimeline().has("loop_start") ? doc.getTimeline().get("loop_start").getAsFloat() : -1;
            if (start < 0 || t <= start) {
                doc.getTimeline().remove("loop_start");
                doc.getTimeline().remove("loop_end");
                doc.markDirty(); pushScriptUpdate(); syncPanels();
                return;
            }
            doc.getTimeline().addProperty("loop_end", t);
        }
        doc.markDirty();
        pushScriptUpdate();
        syncPanels();
    }

    private void clearLoop() {
        undoManager.push(doc.toJson());
        doc.getTimeline().remove("loop_start");
        doc.getTimeline().remove("loop_end");
        doc.markDirty();
        pushScriptUpdate();
        syncPanels();
    }

    // ══════════════════════════════════════════════════════════════
    //  FILE I/O
    // ══════════════════════════════════════════════════════════════

    private void saveScript() {
        // Validate before save
        List<String> errors = EditorOperations.validateScript(doc.getRoot());
        if (!errors.isEmpty()) {
            String msg = I18n.get("editor.validation.failed", errors.size());
            for (String e : errors) msg += "  - " + e + "\n";
            EditorLogger.error(EditorLogger.SCREEN, "SAVE_VALIDATION_FAILED", new RuntimeException(msg));
        }
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
            // N2b：轻量通知服务端脚本已保存（只带文件名；服务端指纹对比 + 广播，失败不影响保存）
            try {
                new com.immersivecinematics.immersive_cinematics.trigger.network.C2SScriptSavedPacket(dest.getFileName().toString()).sendToServer();
            } catch (Exception notifyEx) {
                EditorLogger.action(EditorLogger.SCREEN, "SAVE_SCRIPT", "scriptSavedNotify failed " + notifyEx.getMessage());
            }
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
            // B 模型：旧脚本迁移（next.start == prevEnd → 修正为 end−t/2 重叠转场）
            EditorOperations.applyTransitionAlignment(doc.getTracks());
            playback.setTime(0);
            sel.clear();
            // 打开脚本后自动选中第一个 CAMERA clip——否则相机控件（滑块/三维球）显示可用但 applyCameraParam 因无选中直接 return
            for (JsonElement te : doc.getTracks()) {
                JsonObject t = te.getAsJsonObject();
                if ("CAMERA".equals(t.get("type").getAsString())) {
                    JsonArray clips = t.getAsJsonArray("clips");
                    if (clips.size() > 0) {
                        sel.selectClip(clips.get(0).getAsJsonObject());
                        break;
                    }
                }
            }
            leftPanel.setMode(LeftPanelArea.PanelMode.SCRIPT_PROPERTIES);
            syncPanels();
            pushScriptUpdate();   // N2a：文档加载 = 内容变化
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
        // 覆盖层(letterbox/图片/文字)在 gui.render 阶段通过 GuiGraphics 排队到 GUI 顶点缓冲、帧末才统一提交;
        // 必须先 flush 再捕获,预览纹理才能包含完整播放画面(世界+覆盖层)。
        // 编辑器 UI 在 capture 之后才绘制,不会入镜(时序错开的既有特性保持不变)。
        minecraft.renderBuffers().bufferSource().endBatch();
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
                // C2：A-B 循环 — 播放到 out 点回到 in 点
                float loopStart = doc.getTimeline().has("loop_start") ? doc.getTimeline().get("loop_start").getAsFloat() : -1;
                float loopEnd = doc.getTimeline().has("loop_end") ? doc.getTimeline().get("loop_end").getAsFloat() : -1;
                if (loopStart >= 0 && loopEnd > loopStart && playback.isPlaying() && t >= loopEnd) {
                    playback.setTime(loopStart);
                    output.setTime(loopStart);
                    t = loopStart;
                }
                timeline.setPlayheadTime(t);
                preview.setCurrentTime(t);
                if (playback.isPlaying()) {
                    timeline.ensurePlayheadVisible();
                } else {
                    output.pause();
                    EditorLogger.action(EditorLogger.SCREEN, "PLAYBACK", "ended at=" + String.format("%.3f", t));
                    preview.setPlayingState(false, false);
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
                try {
                    rootComponent.renderOverlay(ctx);
                } finally {
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                }
            }
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "RENDER_CRASH phase=ui_tree " + cycleStr, e); return;
        }

        renderPhase = "done";
    }

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

            // B1：轨道标签重命名键盘路由（回车提交 / Esc 取消 / Backspace 删除，优先于一切分发）
            if (timeline.isRenaming()) {
                if (keyCode == 257) { timeline.commitRename(); doc.markDirty(); pushScriptUpdate(); syncPanels(); }
                else if (keyCode == 256) { timeline.cancelRename(); }
                else if (keyCode == 259) { timeline.backspaceRename(); }
                return true;
            }

            // 1) Dispatch to component tree (focus-based + children traversal)
            if (rootComponent != null && rootComponent.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }

            boolean escPressed = keyCode == 256;
            boolean editorKeyPressed = CinematicKeyBindings.EDITOR_KEY != null && CinematicKeyBindings.EDITOR_KEY.matches(keyCode, scanCode);
            if (escPressed || editorKeyPressed) {
                if (contextMenu.isVisible()) { contextMenu.hide(); return true; }
                if (timeline.isRenaming()) { timeline.cancelRename(); return true; }
                // D3：Esc 先清选择，再按才关编辑器（逐级退出）
                if (!sel.getClips().isEmpty() || sel.getKeyframe() != null) {
                    sel.clear();
                    syncPanels();
                    return true;
                }
                EditorLogger.action(EditorLogger.SCREEN, "CLOSE", "ESC"); CinematicKeyBindings.notifyEditorClosed(); onClose(); return true;
            }

            // 2) Legacy IFocusable dispatch (for left panel text inputs)
            UIComponent focused = leftPanel.getFocusedInput();
            if (focused instanceof IFocusable f && f.keyPressed(keyCode, scanCode, modifiers)) return true;

            // 3) Editor-level shortcuts

            // Ctrl+A — select all
            if (keyCode == 65 && hasControlDown()) {
                selectAllClips();
                return true;
            }

            // Ctrl+C — copy selected
            if (keyCode == 67 && hasControlDown()) {
                copySelectedClips();
                return true;
            }

            // Ctrl+V — paste
            if (keyCode == 86 && hasControlDown()) {
                pasteClips();
                syncPanels();
                return true;
            }
            // Ctrl+X — cut (copy + delete)
            if (keyCode == 88 && hasControlDown()) {
                cutSelectedClips();
                return true;
            }

            // Space — play/pause（300ms 防重复，文本输入聚焦时不触发）
            if (CinematicKeyBindings.EDITOR_PLAY_PAUSE.matches(keyCode, scanCode) && !(focused instanceof IFocusable)) {
                long now = System.currentTimeMillis();
                if (now - lastSpacePress > 300) {
                    lastSpacePress = now;
                    if (playback.isPlaying()) {
                        playback.pause(); output.pause();
                        preview.setPlayingState(false, true);
                        menuBar.setStatus(I18n.get("editor.status.paused"), 0xFFBBBB44);
                        menuBar.setAction(I18n.get("editor.action.playback_paused"));
                    } else {
                        // 组 7：播放前退出直控态
                        CameraManager.INSTANCE.setPreviewDirectControl(false);
                        gizmoDragging = false;
                        playback.play(); output.play();
                        preview.setPlayingState(true, false);
                        menuBar.setStatus(I18n.get("editor.status.playing"), 0xFF44AA44);
                        menuBar.setAction(I18n.get("editor.action.playback_started"));
                    }
                }
                return true;
            }

            // M — 添加 marker（文本输入聚焦时不触发）
            if (CinematicKeyBindings.EDITOR_ADD_MARKER.matches(keyCode, scanCode) && !(focused instanceof IFocusable)) {
                undoManager.push(doc.toJson());
                JsonArray markers = doc.getTimeline().has("markers")
                        ? doc.getTimeline().getAsJsonArray("markers") : new JsonArray();
                if (!doc.getTimeline().has("markers")) doc.getTimeline().add("markers", markers);
                float t = playback.getTime();
                boolean dup = false;
                for (JsonElement me : markers) {
                    if (Math.abs(me.getAsJsonObject().get("time").getAsFloat() - t) < 0.001f) { dup = true; break; }
                }
                if (!dup) {
                    JsonObject mk = new JsonObject();
                    mk.addProperty("time", t);
                    markers.add(mk);
                }
                doc.markDirty();
                pushScriptUpdate();
                syncPanels();
                return true;
            }

            // C2：I / O — 设置 A-B 循环点；Shift+I / Shift+O — 清除循环
            if (CinematicKeyBindings.EDITOR_SET_LOOP_IN.matches(keyCode, scanCode) && !hasShiftDown() && !(focused instanceof IFocusable)) { setLoopPoint(true, playback.getTime()); return true; }    // I
            if (CinematicKeyBindings.EDITOR_SET_LOOP_OUT.matches(keyCode, scanCode) && !hasShiftDown() && !(focused instanceof IFocusable)) { setLoopPoint(false, playback.getTime()); return true; }   // O
            if ((CinematicKeyBindings.EDITOR_SET_LOOP_IN.matches(keyCode, scanCode) || CinematicKeyBindings.EDITOR_SET_LOOP_OUT.matches(keyCode, scanCode))
                    && hasShiftDown() && !(focused instanceof IFocusable)) { clearLoop(); return true; }             // Shift+I / Shift+O

            // Arrows — move playhead (when no clip/kf selected and no text focus)
            if (!(focused instanceof IFocusable) && sel.getClips().isEmpty() && sel.getKeyframe() == null) {
                float step = hasShiftDown() ? 5f : 0.5f;
                if (CinematicKeyBindings.EDITOR_PLAYHEAD_LEFT.matches(keyCode, scanCode)) { seekTo(playback.getTime() - step); return true; }
                if (CinematicKeyBindings.EDITOR_PLAYHEAD_RIGHT.matches(keyCode, scanCode)) { seekTo(Math.min(doc.getTotalDuration(), playback.getTime() + step)); return true; }
            }

            // Ctrl+←/→ — jump to prev/next clip
            if (hasControlDown() && !sel.getClips().isEmpty()) {
                JsonObject current = sel.getClip();
                if (keyCode == 262) { // Ctrl+→ next clip
                    // Find next clip after current in any track
                    float currentStart = EditorOperations.getStart(current);
                    JsonObject next = null;
                    float nextStart = Float.MAX_VALUE;
                    for (JsonElement te : doc.getTracks()) {
                        for (JsonElement ce : te.getAsJsonObject().getAsJsonArray("clips")) {
                            JsonObject c = ce.getAsJsonObject();
                            float s = EditorOperations.getStart(c);
                            if (s > currentStart + 0.01f && s < nextStart) { next = c; nextStart = s; }
                        }
                    }
                    if (next != null) sel.selectClip(next);
                    return true;
                }
                if (keyCode == 263) { // Ctrl+← prev clip
                    float currentStart = EditorOperations.getStart(current);
                    JsonObject prev = null;
                    float prevStart = -1;
                    for (JsonElement te : doc.getTracks()) {
                        for (JsonElement ce : te.getAsJsonObject().getAsJsonArray("clips")) {
                            JsonObject c = ce.getAsJsonObject();
                            float s = EditorOperations.getStart(c);
                            if (s < currentStart - 0.01f && s > prevStart) { prev = c; prevStart = s; }
                        }
                    }
                    if (prev != null) sel.selectClip(prev);
                    return true;
                }
            }

            // Ctrl+Shift+←/→ — jump to timeline start/end
            if (hasControlDown() && hasShiftDown()) {
                if (keyCode == 263) { seekTo(0); return true; }
                if (keyCode == 262) { seekTo(doc.getTotalDuration()); return true; }
            }

            // Ctrl+↑/↓ — move clip to prev/next track
            if (hasControlDown() && !sel.getClips().isEmpty()) {
                JsonObject clip = sel.getClip();
                if (keyCode == 265) { // Ctrl+↑
                    int targetIdx = EditorOperations.findTrackIndex(doc.getTracks(), clip) - 1;
                    // B2：目标轨道锁定则拒绝
                    if (targetIdx >= 0 && !lockedTracks.contains(doc.getTracks().get(targetIdx).getAsJsonObject())) {
                        EditorOperations.moveClipToTrack(doc.getTracks(), clip, targetIdx); doc.markDirty(); pushScriptUpdate(); syncPanels();
                    }
                    return true;
                }
                if (keyCode == 264) { // Ctrl+↓
                    int targetIdx = EditorOperations.findTrackIndex(doc.getTracks(), clip) + 1;
                    if (targetIdx < doc.getTracks().size() && !lockedTracks.contains(doc.getTracks().get(targetIdx).getAsJsonObject())) {
                        EditorOperations.moveClipToTrack(doc.getTracks(), clip, targetIdx); doc.markDirty(); pushScriptUpdate(); syncPanels();
                    }
                    return true;
                }
            }

            // Home/End — timeline start/end
            if (CinematicKeyBindings.EDITOR_HOME.matches(keyCode, scanCode)) { seekTo(0); return true; } // Home
            if (CinematicKeyBindings.EDITOR_END.matches(keyCode, scanCode)) { seekTo(doc.getTotalDuration()); return true; } // End

            // PageUp/PageDown — prev/next track
            if (CinematicKeyBindings.EDITOR_PAGE_UP.matches(keyCode, scanCode) && sel.getClip() != null) { // PageUp
                int ti = EditorOperations.findTrackIndex(doc.getTracks(), sel.getClip());
                if (ti > 0) { JsonArray clips = doc.getTracks().get(ti - 1).getAsJsonObject().getAsJsonArray("clips"); if (clips.size() > 0) sel.selectClip(clips.get(0).getAsJsonObject()); }
                return true;
            }
            if (CinematicKeyBindings.EDITOR_PAGE_DOWN.matches(keyCode, scanCode) && sel.getClip() != null) { // PageDown
                int ti = EditorOperations.findTrackIndex(doc.getTracks(), sel.getClip());
                if (ti < doc.getTracks().size() - 1) { JsonArray clips = doc.getTracks().get(ti + 1).getAsJsonObject().getAsJsonArray("clips"); if (clips.size() > 0) sel.selectClip(clips.get(0).getAsJsonObject()); }
                return true;
            }

            // [ / ] — jump to clip start/end (cycle markers placeholder)
            if (CinematicKeyBindings.EDITOR_CLIP_START.matches(keyCode, scanCode) && sel.getClip() != null) { // [
                seekTo(EditorOperations.getStart(sel.getClip())); return true;
            }
            if (CinematicKeyBindings.EDITOR_CLIP_END.matches(keyCode, scanCode) && sel.getClip() != null) { // ]
                seekTo(EditorOperations.getEnd(sel.getClip())); return true;
            }

            // Delete — delete selected clips
            if ((CinematicKeyBindings.EDITOR_DELETE.matches(keyCode, scanCode) || keyCode == 127) && !sel.getClips().isEmpty()) {
                deleteSelectedClips();
                return true;
            }

            // Ctrl+ shortcuts group
            if (hasControlDown()) {
                if (keyCode == 83) { saveScript(); return true; }
                if (keyCode == 68 && !sel.getClips().isEmpty()) { /* Ctrl+D duplicate */
                    undoManager.push(doc.toJson());
                    List<JsonObject> copies = new ArrayList<>();
                    for (JsonObject clip : sel.getClips()) {
                        JsonObject copy = clip.deepCopy();
                        EditorOperations.moveClip(copy, EditorOperations.getStart(clip) + 0.5f, 0);
                        for (JsonElement te : doc.getTracks()) {
                            JsonArray clips = te.getAsJsonObject().getAsJsonArray("clips");
                            if (clips.contains(clip)) { clips.add(copy); copies.add(copy); break; }
                        }
                    }
                    sel.selectClips(copies); doc.markDirty(); pushScriptUpdate(); return true;
                }
                // Undo
                if (keyCode == 90 && !hasShiftDown()) {
                    String prev = undoManager.undo();
                    if (prev != null) { doc.loadFromJson(prev); sel.clear(); syncPanels(); }
                    return true;
                }
                // Redo: Ctrl+Y or Ctrl+Shift+Z
                if (keyCode == 89 || (keyCode == 90 && hasShiftDown())) {
                    String next = undoManager.redo();
                    if (next != null) { doc.loadFromJson(next); sel.clear(); syncPanels(); }
                    return true;
                }
                if (keyCode == 48) { timeline.resetZoom(); return true; }
            }

            // Enter — play selected clip
            if (CinematicKeyBindings.EDITOR_PLAY_CLIP.matches(keyCode, scanCode)) {
                JsonObject clip = sel.getClip();
                if (clip != null) {
                    float start = EditorOperations.getStart(clip);
                    // 组 7：播放前退出直控态
                    CameraManager.INSTANCE.setPreviewDirectControl(false);
                    gizmoDragging = false;
                    playback.setTime(start); output.setTime(start);
                    playback.play(); output.play();
                    preview.setPlayingState(true, false);
                    menuBar.setStatus(I18n.get("editor.status.playing_clip"), 0xFF44AA44);
                }
                return true;
            }

            // F — frame all
            if (CinematicKeyBindings.EDITOR_FRAME_ALL.matches(keyCode, scanCode)) {
                float totalDur = doc.getTotalDuration();
                if (totalDur > 0) { timeline.setPixelsPerSecond(Math.min(timeline.canvasW() / totalDur, 5000)); timeline.setScrollOffset(0); }
                return true;
            }

            // Legacy keyframe/clip nudge
            if (sel.getKeyframe() != null && handleKeyframeKey(keyCode, scanCode)) return true;
            if (sel.getClip() != null && handleClipKey(keyCode, scanCode)) return true;
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "keyPressed crashed keyCode=" + keyCode, e);
        }
        return false;
    }
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        try {
            // B1：轨道重命名字符输入（优先）
            if (timeline.isRenaming()) {
                timeline.typeRenameChar(codePoint);
                return true;
            }

            // Dispatch to component tree (focus-based)
            if (rootComponent != null && rootComponent.charTyped(codePoint, modifiers)) {
                return true;
            }

            // Legacy IFocusable dispatch
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

    private boolean handleKeyframeKey(int keyCode, int scanCode) {
        try {
            JsonObject kf = sel.getKeyframe();
            float step = hasShiftDown() ? 5 : 0.5f;
            String dir;
            if (CinematicKeyBindings.EDITOR_NUDGE_UP.matches(keyCode, scanCode)) { undoManager.push(doc.toJson()); addTo(kf, "yaw", step); dir = "yaw+"; }
            else if (CinematicKeyBindings.EDITOR_NUDGE_DOWN.matches(keyCode, scanCode)) { undoManager.push(doc.toJson()); addTo(kf, "yaw", -step); dir = "yaw-"; }
            else if (CinematicKeyBindings.EDITOR_PLAYHEAD_LEFT.matches(keyCode, scanCode)) { undoManager.push(doc.toJson()); addTo(kf, "time", -step); dir = "time-"; }
            else if (CinematicKeyBindings.EDITOR_PLAYHEAD_RIGHT.matches(keyCode, scanCode)) { undoManager.push(doc.toJson()); addTo(kf, "time", step); dir = "time+"; }
            else return false;
            EditorLogger.action(EditorLogger.SCREEN, "KEYFRAME_NUDGE", dir + " step=" + step);
            doc.markDirty();
            pushScriptUpdate();
            return true;
        } catch (Exception e) {
            EditorLogger.error(EditorLogger.SCREEN, "handleKeyframeKey crashed", e);
            return false;
        }
    }

    private boolean handleClipKey(int keyCode, int scanCode) {
        try {
            JsonObject clip = sel.getClip();
            float step = hasShiftDown() ? 1 : 0.1f;
            if (CinematicKeyBindings.EDITOR_PLAYHEAD_LEFT.matches(keyCode, scanCode)) { undoManager.push(doc.toJson()); EditorOperations.moveClip(clip, EditorOperations.getStart(clip) - step, 0); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "left step=" + step); doc.markDirty(); pushScriptUpdate(); return true; }
            else if (CinematicKeyBindings.EDITOR_PLAYHEAD_RIGHT.matches(keyCode, scanCode)) { undoManager.push(doc.toJson()); EditorOperations.moveClip(clip, EditorOperations.getStart(clip) + step, 0); EditorLogger.action(EditorLogger.SCREEN, "CLIP_NUDGE", "right step=" + step); doc.markDirty(); pushScriptUpdate(); return true; }
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
        // 关闭编辑器 = 完全退出预览播放(回到玩家视角);与终止按钮(归零保持)语义不同
        CameraManager.INSTANCE.exitPreview();
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

    /**
     * 对象树追溯：选中项（关键帧→片段→轨道）所属的轨道索引。
     * 选中关键帧/片段时从选中 clip 向上找父轨道；无选中时回落时间轴标签选中索引。
     */
    private int selectedTrackIndex() {
        JsonObject clip = sel.getClip();
        if (clip != null) {
            for (int i = 0; i < doc.getTracks().size(); i++) {
                JsonArray clips = doc.getTracks().get(i).getAsJsonObject().getAsJsonArray("clips");
                for (JsonElement ce : clips) {
                    if (ce.getAsJsonObject() == clip) return i;
                }
            }
        }
        return timeline.getSelectedTrackIndex();
    }
    
    private static JsonObject findTrackByType(JsonArray tracks, String type) {
        for (JsonElement te : tracks) {
            JsonObject track = te.getAsJsonObject();
            if (type.equals(track.get("type").getAsString())) {
                return track;
            }
        }
        return null;
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
