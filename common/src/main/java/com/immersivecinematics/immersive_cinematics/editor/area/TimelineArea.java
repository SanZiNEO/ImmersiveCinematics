package com.immersivecinematics.immersive_cinematics.editor.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorOperations;
import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import net.minecraft.client.resources.language.I18n;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TimelineArea extends UIComponent {
    private JsonObject script;
    private JsonObject selectedClip;
    private JsonObject selectedKeyframe;
    private boolean canAddKf;
    private float playheadTime;
    /** E3：上一帧播放头时间（partialTick 插值用） */
    private float prevPlayheadTime;
    /** 组 3：播放头插值仅播放中启用（暂停/定位直接显示精确值，消除非播放态 prev/cur 翻转振荡） */
    private boolean playheadInterpolate = true;
    private float pixelsPerSecond = 60f;
    private float scrollOffset;
    private JsonObject draggingClip;
    private JsonObject draggingKeyframe;
    private JsonObject keyframeClip;
    private int dragOffsetX;
    private int mouseDownX;
    private int mouseDownY;
    private boolean draggingResizeLeft;
    private boolean draggingResizeRight;
    private long dragStartTime;
    private long lastDragLogTime;
    private int dragLogCounter;
    private List<JsonObject> selectedClips = new ArrayList<>();
    private int selectedTrackIndex = 0;
    private Consumer<JsonObject> onToggleClip;
    
    // Ghost drag
    private float dragTargetTime;
    private boolean isDragging;
    private float ghostStart;
    private float ghostEnd;
    private int ghostTrackY;

    // D1 多选集拖拽整体移动
    private boolean draggingSelection;
    private float selectionDragStartTime;

    // D2 clip 悬停 tooltip
    private String hoveredClipTooltip;

    // E1 AUDIO 波形缓存（会话内不淘汰；失败 blacklist 不重试）
    private final Map<String, float[]> waveformCache = new HashMap<>();
    private final Set<String> waveformFailed = new HashSet<>();

    // A9 音频拖拽实时预览（仅 AUDIO 轨道，100ms 节流）
    private Runnable onDragLivePreview;
    private long lastLivePreviewMs;
    
    // Snap indicator
    private float snapIndicatorTime = -1;
    private int snapIndicatorTimer;
    
    // Box select
    private boolean boxSelecting;
    private int boxStartX, boxStartY;
    private float boxStartTime, boxEndTime;
    private int boxStartTrack, boxEndTrack;

    // Ctrl+中键 zoom-to-selection（Olive Zoom Tool）
    private boolean zoomSelecting;
    private float zoomBoxStartTime, zoomBoxEndTime;
    
    private static final float SNAP_THRESHOLD_PX = 8f;

    private Consumer<Float> onClickAtTime;
    private Consumer<JsonObject> onClickClip;
    private Consumer<List<JsonObject>> onSelectClips;
    private BiConsumer<JsonObject, JsonObject> onClickKeyframe;
    private BiConsumer<List<JsonObject>, Float> onMoveClips;
    private BiConsumer<JsonObject, Float> onResizeLeft;
    private BiConsumer<JsonObject, Float> onResizeRight;
    private MoveKeyframeCallback onMoveKeyframe;
    private Runnable onToolAddClip;
    private Runnable onToolDeleteClip;
    private Runnable onToolAddKeyframe;
    private Runnable onToolDeleteKeyframe;
    private Runnable onToolAddTrack;
    private Runnable onToolDeleteTrack;
    private Runnable onToolSnap;

    public TimelineArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.TIMELINE, "full_area", x, y, w, h);
    }
    private BiConsumer<Integer, Integer> onShowClipContext;
    private BiConsumer<Integer, Integer> onShowTimelineContext;
    private BiConsumer<Integer, Integer> onShowRulerContext;
    private BiConsumer<Integer, Integer> onShowTrackLabelContext;
    private BiConsumer<Float, int[]> onShowMarkerContext;


    public void setData(JsonObject script, JsonObject selClip, JsonObject selKf,
                        boolean canAddKf) {
        this.script = script;
        this.selectedClip = selClip;
        this.selectedKeyframe = selKf;
        this.canAddKf = canAddKf;
    }
    public void setPlayheadTime(float t) { prevPlayheadTime = playheadTime; playheadTime = t; }
    public float getPlayheadTime() { return playheadTime; }

    /** 组 3：播放中启用帧间插值（平滑）；暂停/定位时关闭（直接显示精确值） */
    public void setPlayheadInterpolate(boolean b) { playheadInterpolate = b; }

    /** E3：渲染用播放头时间（帧间插值，播放更平滑） */
    private float renderPlayheadTime(float partialTick) {
        return prevPlayheadTime + (playheadTime - prevPlayheadTime) * partialTick;
    }

    
    public void setSelectedClips(List<JsonObject> clips) { this.selectedClips = clips; }
    public void setOnToggleClip(Consumer<JsonObject> r) { onToggleClip = r; }
    public void setOnClickAtTime(Consumer<Float> r) { onClickAtTime = r; }
    public void setOnClickClip(Consumer<JsonObject> r) { onClickClip = r; }
    public void setOnClickKeyframe(BiConsumer<JsonObject, JsonObject> r) { onClickKeyframe = r; }
    public void setOnMoveClips(BiConsumer<List<JsonObject>, Float> r) { onMoveClips = r; }
    public void setOnResizeLeft(BiConsumer<JsonObject, Float> r) { onResizeLeft = r; }
    public void setOnResizeRight(BiConsumer<JsonObject, Float> r) { onResizeRight = r; }
    public void setOnMoveKeyframe(MoveKeyframeCallback r) { onMoveKeyframe = r; }
    public void setOnToolAddClip(Runnable r) { onToolAddClip = r; }
    public void setOnShowClipContext(BiConsumer<Integer, Integer> r) { onShowClipContext = r; }
    public void setOnShowTimelineContext(BiConsumer<Integer, Integer> r) { onShowTimelineContext = r; }

    public void setOnToolDeleteClip(Runnable r) { onToolDeleteClip = r; }
    public void setOnShowRulerContext(BiConsumer<Integer, Integer> r) { onShowRulerContext = r; }
    public void setOnShowTrackLabelContext(BiConsumer<Integer, Integer> r) { onShowTrackLabelContext = r; }
    public void setOnShowMarkerContext(BiConsumer<Float, int[]> r) { onShowMarkerContext = r; }
    public void setOnToolAddKeyframe(Runnable r) { onToolAddKeyframe = r; }
    public void setOnSelectClips(Consumer<List<JsonObject>> r) { onSelectClips = r; }
    public void setOnToolDeleteKeyframe(Runnable r) { onToolDeleteKeyframe = r; }
    public void setSelectedTrackIndex(int idx) { this.selectedTrackIndex = idx; }
    public void setOnToolAddTrack(Runnable r) { onToolAddTrack = r; }
    public void setOnToolDeleteTrack(Runnable r) { onToolDeleteTrack = r; }

    public int getSelectedTrackIndex() { return selectedTrackIndex; }
    private int scrollTrackOffset = 0;
    public int getScrollTrackOffset() { return scrollTrackOffset; }
    public void setScrollTrackOffset(int offset) {
        int totalH = totalTrackHeight();
        int ch = canvasH();
        int maxOffset = Math.max(0, totalH - ch);
        scrollTrackOffset = Math.max(0, Math.min(offset, maxOffset));
    }
    private int canvasH() { return h - headerH(); }
    private int totalTrackHeight() {
        return visibleTracks().size() * trackH();
    }

    /** 可见轨道列表（过滤 hiddenTracks） */
    private List<JsonObject> visibleTracks() {
        List<JsonObject> out = new ArrayList<>();
        JsonArray arr = tracks();
        if (arr != null) {
            for (JsonElement te : arr) {
                JsonObject t = te.getAsJsonObject();
                if (!hiddenTracks.contains(t)) out.add(t);
            }
        }
        return out;
    }

    /** 可见序号 → 原始索引；越界返回 -1 */
    private int visibleIndexToTrackIndex(int vi) {
        int seen = 0;
        JsonArray arr = tracks();
        if (arr == null) return -1;
        for (int ti = 0; ti < arr.size(); ti++) {
            JsonObject t = arr.get(ti).getAsJsonObject();
            if (hiddenTracks.contains(t)) continue;
            if (seen == vi) return ti;
            seen++;
        }
        return -1;
    }
    public void setOnToolSnap(Runnable r) { onToolSnap = r; }
    public void setOnDragLivePreview(Runnable r) { onDragLivePreview = r; }
    public int toolbarW() { return (int)(22 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx); }
    public void resetZoom() { pixelsPerSecond = 60f; scrollOffset = 0; }

    /** E4：播放中确保播放头在可视区内（超出边缘 20px 时自动水平滚动） */
    public void ensurePlayheadVisible() {
        float px = timeToX(playheadTime);
        float margin = (int)(20 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        if (px < canvasX() + margin) scrollOffset += (canvasX() + margin - px);
        else if (px > canvasX() + canvasW() - margin) scrollOffset -= (px - (canvasX() + canvasW() - margin));
        clampScrollOffset();
    }
    public void setPixelsPerSecond(float pps) { this.pixelsPerSecond = Math.max(10, Math.min(5000, pps)); clampScrollOffset(); }

    public int headerH()  { return (int)(20 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy); }
    public void setScrollOffset(float offset) { this.scrollOffset = offset; clampScrollOffset(); }

    private int labelW()   { return (int)(88 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx); }
    /** 轨道参考高度（无 Scale），默认 28，范围 14-72；A5 可拖拽分隔线调整 */
    private int trackHRef = 28;
    private boolean trackResizing;
    private int trackH()   { return Math.max(14, Math.round(trackHRef * com.immersivecinematics.immersive_cinematics.editor.Scale.sy)); }
    public void setTrackHRef(int ref) { trackHRef = Math.max(14, Math.min(72, ref)); }

    // A6 轨道显隐（编辑器会话级，引用轨道 JSON 对象）
    private Set<JsonObject> hiddenTracks = Collections.emptySet();
    public void setHiddenTracks(Set<JsonObject> h) { hiddenTracks = h != null ? h : Collections.emptySet(); }

    // B2 轨道锁定（会话级）
    private Set<JsonObject> lockedTracks = Collections.emptySet();
    public void setLockedTracks(Set<JsonObject> l) { lockedTracks = l != null ? l : Collections.emptySet(); }
    private boolean isTrackLocked(JsonObject t) { return lockedTracks.contains(t); }

    // B2 轨道静音（会话级，仅影响预览）
    private Set<JsonObject> mutedTracks = Collections.emptySet();
    public void setMutedTracks(Set<JsonObject> m) { mutedTracks = m != null ? m : Collections.emptySet(); }

    // B1 轨道标签重命名（就地编辑）
    private JsonObject renamingTrack;
    private String renameBuffer;
    private long lastLabelClick;
    private JsonObject lastLabelClickTrack;

    // B2 轨道头按钮回调
    private Consumer<JsonObject> onToggleTrackHidden;
    private Consumer<JsonObject> onToggleTrackLocked;
    private Consumer<JsonObject> onToggleTrackMuted;

    public void setOnToggleTrackHidden(Consumer<JsonObject> r) { onToggleTrackHidden = r; }
    public void setOnToggleTrackLocked(Consumer<JsonObject> r) { onToggleTrackLocked = r; }
    public void setOnToggleTrackMuted(Consumer<JsonObject> r) { onToggleTrackMuted = r; }

    // ===== B1 重命名公开接口（EditorScreen 键盘路由） =====

    public boolean isRenaming() { return renamingTrack != null; }

    public void commitRename() {
        if (renamingTrack != null) {
            String n = renameBuffer != null ? renameBuffer.trim() : "";
            if (n.isEmpty()) renamingTrack.remove("name");
            else renamingTrack.addProperty("name", n);
        }
        renamingTrack = null;
        renameBuffer = null;
    }

    public void cancelRename() {
        renamingTrack = null;
        renameBuffer = null;
    }

    public void typeRenameChar(char c) {
        if (renamingTrack == null) return;
        if (Character.isISOControl(c)) return;
        if (renameBuffer == null) renameBuffer = "";
        if (renameBuffer.length() >= 24) return;
        renameBuffer += c;
    }

    public void backspaceRename() {
        if (renamingTrack == null || renameBuffer == null || renameBuffer.isEmpty()) return;
        renameBuffer = renameBuffer.substring(0, renameBuffer.length() - 1);
    }

    private int btn()      { return (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy); }
    private int btnGap()   { return (int)(2 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy); }
    private int resizeMargin() { return (int)(4 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx); }

    public int canvasX() { return x + toolbarW() + labelW(); }
    public int canvasY() { return y + headerH(); }
    public int canvasW() { return w - toolbarW() - labelW(); }
    public float timeToX(float t) { return canvasX() + (t * pixelsPerSecond) + scrollOffset; }
    public float xToTime(float px) { return (px - canvasX() - scrollOffset) / pixelsPerSecond; }

    private void clampScrollOffset() {
        float maxScroll = 0;
        float contentWidth = (totalDuration() + 30) * pixelsPerSecond;
        float minScroll = Math.min(0, canvasW() - contentWidth);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < minScroll) scrollOffset = minScroll;
    }

    private JsonArray tracks() {
        return script != null ? script.getAsJsonArray("tracks") : null;
    }

    private float totalDuration() {
        return script != null && script.has("total_duration") ? script.get("total_duration").getAsFloat() : 0;
    }

    @Override
    public void renderContent(UIContext ctx) {
        int cx = canvasX();
        int cy = canvasY();
        int cw = canvasW();

        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_MAIN);
        ctx.graphics.renderOutline(x, y, w, h, EditorTheme.BORDER);
        ctx.graphics.fill(x, y, x + w, y + headerH(), EditorTheme.BG_PANEL);

        drawRuler(ctx, cx, y, cw);
        drawToolbar(ctx);

        // 裁剪轨道绘制区域，防止滚动内容溢出到标尺/工具栏
        ctx.graphics.enableScissor(cx, cy, cx + cw, cy + canvasH());
        drawTracks(ctx, cx, cy);
        drawPlayhead(ctx, cx, cy, cw);
        ctx.graphics.disableScissor();

        // D2：clip 悬停 tooltip（每帧重置无残留）
        if (hoveredClipTooltip != null) {
            ctx.graphics.renderTooltip(ctx.font, net.minecraft.network.chat.Component.literal(hoveredClipTooltip), ctx.mouseX, ctx.mouseY);
            hoveredClipTooltip = null;
        }

        // 全局 overlay（无需裁剪：拖拽幽灵、吸附指示器、框选）
        drawGhostOverlays(ctx, cx, cy, cw);
    }

    /** 绘制全局 overlay 元素（不在 canvas scissor 范围内） */
    private void drawGhostOverlays(UIContext ctx, int cx, int cy, int cw) {
        // Ghost drag preview
        if (isDragging && draggingClip != null) {
            int gx = (int) timeToX(ghostStart);
            int gw = Math.max(2, (int)(timeToX(ghostEnd) - gx));
            ctx.graphics.fill(gx, ghostTrackY + 2, gx + gw, ghostTrackY + trackH() - 2, EditorTheme.GHOST_FILL);
            ctx.graphics.renderOutline(gx, ghostTrackY + 2, gw, trackH() - 4, EditorTheme.GHOST_BORDER);
        }

        // D1：多选组拖拽 — 其余选中 clip 的偏移 ghost
        if (draggingSelection && isDragging && draggingClip != null) {
            float offset = dragTargetTime - selectionDragStartTime;
            if (Math.abs(offset) > 0.001f) {
                for (JsonObject c : selectedClips) {
                    if (c == draggingClip) continue;
                    int gx = (int) timeToX(EditorOperations.getStart(c) + offset);
                    int gw = Math.max(2, (int) (timeToX(EditorOperations.getEnd(c) + offset) - gx));
                    int gy = computeTrackY(c);
                    ctx.graphics.fill(gx, gy + 2, gx + gw, gy + trackH() - 2, 0x603A6DB5);
                    ctx.graphics.renderOutline(gx, gy + 2, gw, trackH() - 4, EditorTheme.GHOST_BORDER);
                }
            }
        }

        // Snap indicator
        if (snapIndicatorTimer > 0 && snapIndicatorTime >= 0) {
            int sx = (int) timeToX(snapIndicatorTime);
            if (snapIndicatorTimer % 3 < 2) {
                ctx.graphics.fill(sx, y + headerH(), sx + 2, y + h, EditorTheme.SNAP_INDICATOR);
            }
            snapIndicatorTimer--;
            if (snapIndicatorTimer <= 0) snapIndicatorTime = -1;
        }

        // Box select rect
        if (boxSelecting) {
            int bx = (int) Math.min(timeToX(boxStartTime), timeToX(boxEndTime));
            int bw = (int) Math.abs(timeToX(boxEndTime) - timeToX(boxStartTime));
            int by = Math.min(boxStartY, ctx.mouseY);
            int bh = Math.abs(ctx.mouseY - boxStartY);
            ctx.graphics.fill(bx, by, bx + bw, by + bh, EditorTheme.MARQUEE_FILL);
            ctx.graphics.renderOutline(bx, by, bw, bh, EditorTheme.MARQUEE_BORDER);
        }

        // Zoom select rect（Ctrl+中键，仅时间范围）
        if (zoomSelecting) {
            int zx = (int) Math.min(timeToX(zoomBoxStartTime), timeToX(zoomBoxEndTime));
            int zw = (int) Math.abs(timeToX(zoomBoxEndTime) - timeToX(zoomBoxStartTime));
            int zy = canvasY();
            int zh = canvasH();
            ctx.graphics.fill(zx, zy, zx + zw, zy + zh, EditorTheme.MARQUEE_FILL);
            ctx.graphics.renderOutline(zx, zy, zw, zh, EditorTheme.MARQUEE_BORDER);
        }
    }

    /** E2：主刻度间隔（按可视宽度自适应） */
    private float majorIntervalFor(float visibleWidth) {
        if (visibleWidth <= 2) return 0.5f;
        if (visibleWidth <= 5) return 1;
        if (visibleWidth <= 15) return 5;
        if (visibleWidth <= 60) return 10;
        return (float) Math.pow(10, Math.ceil(Math.log10(visibleWidth)) - 1);
    }

    private void drawRuler(UIContext ctx, int cx, int top, int cw) {
        float visibleWidth = cw / pixelsPerSecond;
        float visibleStart = Math.max(0, -scrollOffset / pixelsPerSecond);
        float visibleEnd = visibleStart + visibleWidth;

        float majorInterval = majorIntervalFor(visibleWidth);
        float minorInterval = majorInterval / 2;

        ctx.graphics.fill(cx, top, cx + cw, top + headerH(), EditorTheme.BG_PANEL);

        float startTick = (float) Math.floor(visibleStart / majorInterval) * majorInterval;
        for (float t = startTick; t <= visibleEnd + majorInterval; t += majorInterval) {
            float sx = cx + t * pixelsPerSecond + scrollOffset;
            if (sx < cx - 20 || sx > cx + cw) continue;
            int sxInt = (int) sx;
            ctx.graphics.fill(sxInt, top, sxInt + 2, top + headerH(), EditorTheme.BORDER_LIGHT);
            ctx.graphics.drawString(ctx.font, fmt(t), sxInt + 3, top + 2, EditorTheme.TEXT_DIM);
        }

        float startMinor = (float) Math.floor(visibleStart / minorInterval) * minorInterval;
        for (float t = startMinor; t <= visibleEnd + minorInterval; t += minorInterval) {
            float sx = cx + t * pixelsPerSecond + scrollOffset;
            if (sx < cx - 20 || sx > cx + cw) continue;
            boolean isMajor = Math.abs(t % majorInterval) < 0.001f;
            if (!isMajor) {
                ctx.graphics.fill((int) sx, top + headerH() - 6, (int) sx + 1, top + headerH(), EditorTheme.BORDER_DARK);
            }
        }

        // C1：marker pin（圆点 + 竖线，金褐 0xFFD7A800 区别于播放头红/吸附金）
        JsonArray markers = script != null && script.has("markers") ? script.getAsJsonArray("markers") : null;
        if (markers != null) {
            for (JsonElement me : markers) {
                float mt = me.getAsJsonObject().get("time").getAsFloat();
                float mx = timeToX(mt);
                if (mx < cx - 10 || mx > cx + cw + 10) continue;
                int mxi = (int) mx;
                ctx.graphics.fill(mxi - 1, top + headerH() - 6, mxi + 1, top + headerH(), 0xFFD7A800);
                ctx.graphics.fill(mxi - 3, top + headerH() - 10, mxi + 3, top + headerH() - 6, 0xFFD7A800);
            }
        }

        // C2：A-B 循环区间高亮（标尺底部金色条）
        float ls = -1, le = -1;
        if (script != null) {
            if (script.has("loop_start")) ls = script.get("loop_start").getAsFloat();
            if (script.has("loop_end")) le = script.get("loop_end").getAsFloat();
        }
        if (ls >= 0 && le > ls) {
            int ax = (int) timeToX(ls);
            int aw = (int) (timeToX(le) - ax);
            if (ax < cx + cw && ax + aw > cx) {
                ctx.graphics.fill(ax, top + headerH() - 3, ax + aw, top + headerH(), 0x33FFD700);
            }
        }
    }

    private void drawToolbar(UIContext ctx) {
        int bx = x + (int)(3 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        int by = y + headerH() + (int)(4 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);

        drawBtn(ctx, bx, by, I18n.get("editor.toolbar.add_clip"), 0xFF338833, 0xFF44AA44, true); by += btn() + btnGap();
        drawBtn(ctx, bx, by, I18n.get("editor.toolbar.delete_clip"), 0xFF883333, 0xFFAA4444, selectedClip != null); by += btn() + btnGap() + 4;
        drawBtn(ctx, bx, by, I18n.get("editor.toolbar.add_keyframe"), 0xFF333388, 0xFF4444AA, canAddKf); by += btn() + btnGap();
        drawBtn(ctx, bx, by, I18n.get("editor.toolbar.delete_keyframe"), 0xFF883366, 0xFFAA4488, selectedKeyframe != null); by += btn() + btnGap() + 4;
        drawBtn(ctx, bx, by, I18n.get("editor.toolbar.add_track"), 0xFF338866, 0xFF44AA88, true); by += btn() + btnGap();
        drawBtn(ctx, bx, by, I18n.get("editor.toolbar.delete_track"), 0xFF883355, 0xFFAA4477, selectedTrackIndex >= 0); by += btn() + btnGap() + 4;
        drawBtn(ctx, bx, by, I18n.get("editor.toolbar.snap", "\u00AB\u00BB"), 0xFF336688, 0xFF4488AA, true);
    }

    private void drawBtn(UIContext ctx, int bx, int by, String label, int c, int hc, boolean active) {
        boolean hover = ctx.isMouseIn(bx, by, btn(), btn());
        int bg = !active ? EditorTheme.BG_WIDGET : hover ? hc : c;
        ctx.graphics.fill(bx, by, bx + btn(), by + btn(), bg);
        ctx.graphics.renderOutline(bx, by, btn(), btn(), active ? EditorTheme.BORDER_LIGHT : EditorTheme.BORDER);
        int tw = ctx.font.width(label);
        ctx.graphics.drawString(ctx.font, label, bx + (btn() - tw) / 2, by + (btn() - 8) / 2, active ? 0xFFFFFFFF : EditorTheme.TEXT_DISABLED);
    }

    /** B2：轨道头小按钮（👁/🔒/🔇）— 自绘，不建 widget */
    private void drawTrackHeadBtn(UIContext ctx, int bx, int ty, int bw, String glyph,
                                  boolean active, int activeColor, int activeBg, boolean hover) {
        int bg = active ? (activeColor & 0x00FFFFFF) | 0x33000000
                : hover ? EditorTheme.BG_HOVER : 0;
        if (bg != 0) ctx.graphics.fill(bx, ty, bx + bw, ty + trackH(), bg);
        int color = active ? activeColor : EditorTheme.TEXT_SECONDARY;
        if (hover && !active) color = EditorTheme.lighten(color, 0.2f);
        int tw = ctx.font.width(glyph);
        ctx.graphics.drawString(ctx.font, glyph, bx + (bw - tw) / 2, ty + (trackH() - 8) / 2, color);
    }

    private void drawTracks(UIContext ctx, int cx, int cy) {
        JsonArray arr = tracks();
        if (arr == null) return;
        
        int labelAreaX = x + toolbarW();
        int labelAreaW = labelW();
        List<JsonObject> visible = visibleTracks();
        
        for (int vi = 0; vi < visible.size(); vi++) {
            JsonObject track = visible.get(vi);
            int ti = visibleIndexToTrackIndex(vi);
            int ty = cy + vi * trackH() - scrollTrackOffset;
            String type = track.has("type") ? track.get("type").getAsString() : "TRACK";
            
            ctx.graphics.fill(labelAreaX, ty, labelAreaX + labelAreaW, ty + trackH(), EditorTheme.BG_TRACK);
            ctx.graphics.renderOutline(labelAreaX, ty, labelAreaW, trackH(), EditorTheme.BORDER);

            // B1：重命名输入框（不画色块）
            if (renamingTrack == track) {
                String buf = renameBuffer != null ? renameBuffer : "";
                int boxW = (int)(labelAreaW - 50 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
                ctx.graphics.fill(labelAreaX + 4, ty + 3, labelAreaX + 4 + boxW, ty + trackH() - 3, EditorTheme.BG_WIDGET);
                ctx.graphics.renderOutline(labelAreaX + 4, ty + 3, boxW, trackH() - 6, EditorTheme.BORDER_LIGHT);
                ctx.graphics.drawString(ctx.font, buf, labelAreaX + 8, ty + (trackH() - 8) / 2, EditorTheme.TEXT_PRIMARY);
                if (System.currentTimeMillis() % 1000 < 500) {
                    int bx = labelAreaX + 8 + ctx.font.width(buf);
                    ctx.graphics.fill(bx, ty + (trackH() - 8) / 2, bx + 1, ty + (trackH() - 8) / 2 + 8, EditorTheme.TEXT_PRIMARY);
                }
            } else {
                int colorMark = EditorTheme.trackTypeColor(type);
                ctx.graphics.fill(labelAreaX + 4, ty + 4, labelAreaX + 8, ty + trackH() - 4, colorMark);
                // 显示名（B1：name 优先）；截断避免与右侧按钮重叠
                String label = track.has("name") ? track.get("name").getAsString() : type;
                int maxW = (int)(labelAreaW - 52 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
                while (ctx.font.width(label) > maxW && label.length() > 1) {
                    label = label.substring(0, label.length() - 1);
                }
                if (ctx.font.width(label) > maxW) label = label.substring(0, Math.max(1, label.length() - 1)) + "\u2026";
                ctx.graphics.drawString(ctx.font, label, labelAreaX + 12, ty + (trackH() - 8) / 2, EditorTheme.TEXT_SECONDARY);

                // B2：轨道头按钮区（👁 显隐 / 🔒 锁定 / 🔇 静音-AUDIO）
                int btnW = (int)(16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
                int bxEye = (int)(labelAreaX + labelAreaW - 48 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
                int bxLock = (int)(labelAreaX + labelAreaW - 32 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
                int bxMute = (int)(labelAreaX + labelAreaW - 16 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
                drawTrackHeadBtn(ctx, bxEye, ty, btnW,
                        "\uD83D\uDC41", hiddenTracks.contains(track), EditorTheme.SELECTED, 0x33FFFFFF,
                        ctx.isMouseIn(bxEye, ty, btnW, trackH()));
                drawTrackHeadBtn(ctx, bxLock, ty, btnW,
                        "\uD83D\uDD12", lockedTracks.contains(track), EditorTheme.DANGER, 0x33FFFFFF,
                        ctx.isMouseIn(bxLock, ty, btnW, trackH()));
                if ("AUDIO".equals(type)) {
                    drawTrackHeadBtn(ctx, bxMute, ty, btnW,
                            "\uD83D\uDD07", mutedTracks.contains(track), EditorTheme.SUCCESS, 0x33FFFFFF,
                            ctx.isMouseIn(bxMute, ty, btnW, trackH()));
                }
            }
            
            int bgColor = (ti == selectedTrackIndex) ? EditorTheme.lighten(EditorTheme.trackBgColor(type), 0.15f) : EditorTheme.trackBgColor(type);
            // B4：轨道行 hover 高亮（在选中高亮之后叠加）
            if (ctx.isMouseIn(cx, ty, canvasW(), trackH())) bgColor = EditorTheme.lighten(bgColor, 0.08f);
            ctx.graphics.fill(cx, ty, cx + canvasW(), ty + trackH(), bgColor);

            // C2：A-B 循环区间淡金覆盖（clip 之下）
            float ls = -1, le = -1;
            if (script != null) {
                if (script.has("loop_start")) ls = script.get("loop_start").getAsFloat();
                if (script.has("loop_end")) le = script.get("loop_end").getAsFloat();
            }
            if (ls >= 0 && le > ls) {
                int ax = (int) timeToX(ls);
                int aw = (int) (timeToX(le) - ax);
                if (ax < cx + canvasW() && ax + aw > cx) {
                    ctx.graphics.fill(ax, ty, ax + aw, ty + trackH(), 0x0DFFD700);
                }
            }

            // E2：垂直时间网格线（主刻度处，clip 之下）
            float visW = canvasW() / pixelsPerSecond;
            float visStart = Math.max(0, -scrollOffset / pixelsPerSecond);
            float interval = majorIntervalFor(visW);
            float startT = (float) Math.floor(visStart / interval) * interval;
            for (float t = startT; t <= visStart + visW + interval; t += interval) {
                int gx = (int) timeToX(t);
                if (gx < cx || gx > cx + canvasW()) continue;
                ctx.graphics.fill(gx, ty, gx + 1, ty + trackH(), 0x0DFFFFFF);
            }

            JsonArray clips = track.getAsJsonArray("clips");
            if (clips == null || clips.size() == 0) {
                String emptyHint = I18n.get("editor.timeline.empty_track");
            } else {
                for (JsonElement ce : clips) {
                    drawClip(ctx, ce.getAsJsonObject(), ty, type);
                }
            }
            if (vi < visible.size() - 1) {
                int sepY = ty + trackH() - 1;
                boolean sepHover = ctx.isMouseIn(cx, sepY, canvasW(), 1);
                ctx.graphics.fill(cx, sepY, cx + canvasW(), sepY + 1, sepHover ? EditorTheme.TEXT_SECONDARY : EditorTheme.BORDER);
            }
        }
    }
    
    /** E1：clip 渲染路由（EVENT 菱形 → 波形叠加 → 普通 clip 体） */
    private void drawClip(UIContext ctx, JsonObject clip, int ty, String trackType) {
        if ("EVENT".equals(trackType)) { drawEventClip(ctx, clip, ty); return; }
        drawClipBody(ctx, clip, ty, trackType);
        if ("AUDIO".equals(trackType)) drawWaveform(ctx, clip, ty);
    }

    private void drawClipBody(UIContext ctx, JsonObject clip, int ty, String trackType) {
        float sx = timeToX(EditorOperations.getStart(clip));
        float ex = timeToX(EditorOperations.getEnd(clip));
        int cw = canvasW();
        int cx = canvasX();
        if (ex < cx || sx > cx + cw) return;
        int clipX = Math.max(cx, (int) sx);
        int clipW = Math.min(cx + cw, (int) ex) - clipX;
        if (clipW < 2) clipW = 2;

        boolean isSel = selectedClips != null && selectedClips.contains(clip);
        boolean hovered = ctx.isMouseIn(clipX, ty, clipW, trackH());
        // D2：悬停 tooltip（In/Out/Dur）
        if (hovered) {
            float st = EditorOperations.getStart(clip), en = EditorOperations.getEnd(clip);
            hoveredClipTooltip = "In " + fmt(st) + "  Out " + fmt(en) + "  Dur " + fmt(en - st);
        }
        // B4：存在选中集时非选中 clip 变暗；E8：拖拽中的原 clip 也变暗（抬起质感）
        boolean hasSelection = selectedClips != null && !selectedClips.isEmpty();
        boolean dimmed = (hasSelection && !isSel) || (draggingClip == clip);
        int fill = EditorTheme.clipFillColor(trackType, isSel, hovered, dimmed);
        ctx.graphics.fill(clipX, ty + 2, clipX + clipW, ty + trackH() - 2, fill);

        if (isSel) {
            ctx.graphics.fill(clipX, ty + 2, clipX + 3, ty + trackH() - 2, EditorTheme.SELECTED);
            // E9：选中 clip 整框金色描边
            ctx.graphics.renderOutline(clipX, ty + 2, clipW, trackH() - 4, EditorTheme.SELECTED);
        }

        int bright = EditorTheme.lighten(EditorTheme.clipFillColor(trackType, false, false, false), 0.4f);
        ctx.graphics.fill(clipX, ty + 2, clipX + clipW, ty + 3, bright);
        ctx.graphics.fill(clipX, ty + 2, clipX + 1, ty + trackH() - 2, bright);
        int dark = EditorTheme.BORDER_DARK;
        ctx.graphics.fill(clipX, ty + trackH() - 3, clipX + clipW, ty + trackH() - 2, dark);
        ctx.graphics.fill(clipX + clipW - 1, ty + 2, clipX + clipW, ty + trackH() - 2, dark);

        float transDur = EditorOperations.getTransitionDuration(clip);
        if (transDur > 0f) {
            float tx = timeToX(EditorOperations.getEnd(clip));
            float tex = timeToX(EditorOperations.getTotalEnd(clip));
            int transW = Math.max(2, (int)(tex - tx));
            String tLabel = I18n.get("editor.timeline.morph_label", fmt(transDur));
            int tlw = ctx.font.width(tLabel);
            if (tlw + 4 < transW)
                ctx.graphics.drawString(ctx.font, tLabel, (int)tx + 2, ty + (trackH() - 8) / 2, 0xFFCCCCFF);
        }

        if (ctx.isMouseIn(clipX, ty + 2, resizeMargin(), trackH() - 4))
            ctx.graphics.fill(clipX, ty + 2, clipX + resizeMargin(), ty + trackH() - 2, 0x55FFFFFF);
        if (ctx.isMouseIn(clipX + clipW - resizeMargin(), ty + 2, resizeMargin(), trackH() - 4))
            ctx.graphics.fill(clipX + clipW - resizeMargin(), ty + 2, clipX + clipW, ty + trackH() - 2, 0x55FFFFFF);

        if (clipW > 60) {
            String label;
            if (clip.has("name")) {
                label = clip.get("name").getAsString();
            } else {
                label = fmt(EditorOperations.getStart(clip)) + "  " + trackType;
            }
            int lw = ctx.font.width(label);
            if (lw + 8 < clipW) ctx.graphics.drawString(ctx.font, label, clipX + 4, ty + (trackH() - 8) / 2, EditorTheme.TEXT_PRIMARY);
        }

        JsonArray kfs = EditorOperations.keyframes(clip);
        if (kfs != null) {
            for (JsonElement ke : kfs) {
                JsonObject kf = ke.getAsJsonObject();
                float kx = timeToX(EditorOperations.getStart(clip) + kf.get("time").getAsFloat());
                if (kx >= clipX + 2 && kx <= clipX + clipW - 2) {
                    int kc = (kf == selectedKeyframe && clip == selectedClip) ? 0xFFFFFF88 : EditorTheme.TEXT_SECONDARY;
                    ctx.graphics.fill((int) kx - 3, ty + trackH() / 2 - 3, (int) kx + 3, ty + trackH() / 2 + 3, kc);
                }
            }
        }
    }
    
    /** E1：AUDIO 波形列（半透明白，clip 填充色之上） */
    private void drawWaveform(UIContext ctx, JsonObject clip, int ty) {
        String sound = clip.has("sound") ? clip.get("sound").getAsString() : "";
        String source = clip.has("source") ? clip.get("source").getAsString() : "file";
        if (sound.isEmpty()) return;
        String key = source + "|" + sound;
        float[] peaks = waveformCache.get(key);
        if (peaks == null && !waveformFailed.contains(key)) {
            peaks = com.immersivecinematics.immersive_cinematics.script.CinematicAudioInstance.decodePeaks(sound, source, 256);
            if (peaks == null) waveformFailed.add(key);
            else waveformCache.put(key, peaks);
        }
        if (peaks == null) return;

        float sx = timeToX(EditorOperations.getStart(clip));
        float ex = timeToX(EditorOperations.getEnd(clip));
        int cx = canvasX(), cw = canvasW();
        if (ex < cx || sx > cx + cw) return;
        int clipX = Math.max(cx, (int) sx), clipW = Math.min(cx + cw, (int) ex) - clipX;
        if (clipW < 2) clipW = 2;

        float dur = Math.max(0.001f, EditorOperations.getDuration(clip));
        int midY = ty + trackH() / 2;
        for (int px = clipX; px < clipX + clipW; px++) {
            float local = (xToTime(px) - EditorOperations.getStart(clip)) / dur;
            int idx = (int) (local * peaks.length);
            if (idx < 0 || idx >= peaks.length) continue;
            int h = Math.max(1, (int) (peaks[idx] * (trackH() - 6)));
            ctx.graphics.fill(px, midY - h / 2, px + 1, midY + h / 2, 0x99FFFFFF);
        }
    }

    /** EVENT 轨道专属渲染：活动区间细线 + 关键帧菱形标记点（不画 clip 矩形） */
    private void drawEventClip(UIContext ctx, JsonObject clip, int ty) {
        float sx = timeToX(EditorOperations.getStart(clip));
        float ex = timeToX(EditorOperations.getEnd(clip));
        int cx = canvasX(), cw = canvasW();
        if (ex < cx || sx > cx + cw) return;
        int clipX = Math.max(cx, (int) sx);
        int clipW = Math.min(cx + cw, (int) ex) - clipX;
        // 活动区间细线（半透明红，轨道中线）
        ctx.graphics.fill(clipX, ty + trackH() / 2, clipX + clipW, ty + trackH() / 2 + 1, 0x668A3A3A);
        // D2：悬停 tooltip（In/Out/Dur）
        if (ctx.isMouseIn(clipX, ty, clipW, trackH())) {
            float st = EditorOperations.getStart(clip), en = EditorOperations.getEnd(clip);
            hoveredClipTooltip = "In " + fmt(st) + "  Out " + fmt(en) + "  Dur " + fmt(en - st);
        }
        // 关键帧菱形标记
        JsonArray kfs = EditorOperations.keyframes(clip);
        if (kfs == null) return;
        for (JsonElement ke : kfs) {
            JsonObject kf = ke.getAsJsonObject();
            float kx = timeToX(EditorOperations.getStart(clip) + kf.get("time").getAsFloat());
            if (kx < clipX - 8 || kx > clipX + clipW + 8) continue;
            boolean sel = (kf == selectedKeyframe && clip == selectedClip);
            int c = sel ? 0xFFFFFF88 : 0xFF8A3A3A;
            int kx0 = (int) kx;
            int midY = ty + trackH() / 2;
            ctx.graphics.fill(kx0 - 3, midY - 3, kx0 + 3, midY, c);
            ctx.graphics.fill(kx0 - 3, midY, kx0 + 3, midY + 3, c);
        }
    }

    private void drawPlayhead(UIContext ctx, int cx, int cy, int cw) {
        // 组 3：非播放态（定位/暂停）直接显示精确值，消除插值振荡（抽搐根因双保险之一）
        float px = timeToX(playheadInterpolate ? renderPlayheadTime(ctx.partialTick) : playheadTime);
        if (px >= cx && px <= cx + cw) {
            int pxInt = (int) px;
            ctx.graphics.fill(pxInt, y, pxInt + 2, y + h, EditorTheme.PLAYHEAD);
            ctx.graphics.fill(pxInt - 3, y, pxInt + 3, y + 4, EditorTheme.PLAYHEAD_HEAD);
            ctx.graphics.fill(pxInt - 1, y + 4, pxInt + 1, y + 6, EditorTheme.PLAYHEAD_HEAD);
        }
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!ctx.isMouseIn(x, y, w, h)) return false;
        mouseDownX = ctx.mouseX;
        mouseDownY = ctx.mouseY;
        boolean rightClick = ctx.mouseButton == 1;

        if (ctx.mouseX < x + toolbarW() && ctx.mouseY >= y + headerH()) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "toolbar", ctx.mouseX, ctx.mouseY, true);
            return clickToolbar(ctx);
        }

        // Label area click — select track (left) or context menu (right)
        if (ctx.mouseX >= x + toolbarW() && ctx.mouseX < canvasX() && ctx.mouseY >= canvasY()) {
            int ti = visibleIndexToTrackIndex((ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH());
            if (ti >= 0 && ti < tracks().size()) {
                JsonObject track = tracks().get(ti).getAsJsonObject();
                String type = track.has("type") ? track.get("type").getAsString() : "";
                float sx = com.immersivecinematics.immersive_cinematics.editor.Scale.sx;
                if (!rightClick) {
                    int lx = canvasX();
                    // B2：轨道头按钮区（先于选轨/重命名）
                    if (ctx.mouseX >= lx - 48 * sx && ctx.mouseX < lx - 32 * sx) {
                        if (onToggleTrackHidden != null) onToggleTrackHidden.accept(track);
                        return true;
                    }
                    if (ctx.mouseX >= lx - 32 * sx && ctx.mouseX < lx - 16 * sx) {
                        if (onToggleTrackLocked != null) onToggleTrackLocked.accept(track);
                        return true;
                    }
                    if ("AUDIO".equals(type) && ctx.mouseX >= lx - 16 * sx && ctx.mouseX < lx) {
                        if (onToggleTrackMuted != null) onToggleTrackMuted.accept(track);
                        return true;
                    }
                    // B1：双击轨道标签 → 就地重命名
                    long now = System.currentTimeMillis();
                    if (now - lastLabelClick < 300 && track == lastLabelClickTrack) {
                        renamingTrack = track;
                        renameBuffer = track.has("name") ? track.get("name").getAsString() : type;
                        lastLabelClick = 0;
                        return true;
                    }
                    lastLabelClick = now;
                    lastLabelClickTrack = track;
                }
                selectedTrackIndex = ti;
                EditorLogger.action(EditorLogger.TIMELINE, "LABEL_CLICK", "track=" + ti);
                if (rightClick && onShowTrackLabelContext != null) {
                    onShowTrackLabelContext.accept(ctx.mouseX, ctx.mouseY);
                }
            }
            return true;
        }
        if (ctx.mouseY < canvasY() && ctx.mouseX >= x + toolbarW()) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "ruler", ctx.mouseX, ctx.mouseY, true);
            // C1：marker pin 命中（先于跳转/右键菜单）
            JsonArray markers = script != null && script.has("markers") ? script.getAsJsonArray("markers") : null;
            if (markers != null) {
                for (JsonElement me : markers) {
                    JsonObject mk = me.getAsJsonObject();
                    float mt = mk.get("time").getAsFloat();
                    int mxi = (int) timeToX(mt);
                    if (Math.abs(ctx.mouseX - mxi) <= 5) {
                        if (rightClick) {
                            if (onShowMarkerContext != null) onShowMarkerContext.accept(mt, new int[]{ctx.mouseX, ctx.mouseY});
                        } else if (onClickAtTime != null) {
                            EditorLogger.playhead(EditorLogger.TIMELINE, mt, ctx.mouseX, "marker_click");
                            onClickAtTime.accept(mt);
                        }
                        return true;
                    }
                }
            }
            if (rightClick) {
                if (onShowRulerContext != null) onShowRulerContext.accept(ctx.mouseX, ctx.mouseY);
                return true;
            }
            float t = xToTime(ctx.mouseX);
            if (t >= 0 && onClickAtTime != null) {
                EditorLogger.playhead(EditorLogger.TIMELINE, Math.max(0, t), ctx.mouseX, "ruler_click");
                onClickAtTime.accept(Math.max(0, t));
            }
            return true;
        }

        EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas", ctx.mouseX, ctx.mouseY, true);
        if (ctx.mouseButton == 2) {
            // Ctrl+中键：缩放到选区；普通中键：无操作（一律消费）
            if (ctx.isCtrlDown() && ctx.mouseX >= canvasX() && ctx.mouseY >= canvasY()) {
                zoomSelecting = true;
                zoomBoxStartTime = zoomBoxEndTime = xToTime(ctx.mouseX);
            }
            return true;
        }
        // 轨道间分隔线拖拽调整高度（左键）
        if (ctx.mouseButton == 0 && ctx.mouseX >= canvasX() && ctx.mouseY >= canvasY()) {
            JsonArray arr = tracks();
            if (arr != null) {
                for (int ti = 0; ti < arr.size(); ti++) {
                    int ty = canvasY() + ti * trackH() - scrollTrackOffset;
                    if (Math.abs(ctx.mouseY - (ty + trackH() - 1)) <= 2) {
                        trackResizing = true;
                        return true;
                    }
                }
            }
        }
        if (rightClick) {
            return clickCanvasRight(ctx);
        }
        return clickCanvas(ctx);
    }
    
    private boolean clickCanvasRight(UIContext ctx) {
        JsonArray arr = tracks();
        if (arr == null || ctx.mouseX < canvasX()) return false;

        int trackIdx = visibleIndexToTrackIndex((ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH());
        if (trackIdx < 0 || trackIdx >= arr.size()) return false;
        JsonObject trackObj = arr.get(trackIdx).getAsJsonObject();
        // B2：锁定轨道右键也拒绝（不显示 clip 菜单）
        if (isTrackLocked(trackObj)) return true;
        JsonArray clips = trackObj.getAsJsonArray("clips");

        for (int i = clips.size() - 1; i >= 0; i--) {
            JsonObject clip = clips.get(i).getAsJsonObject();
            float sx = timeToX(EditorOperations.getStart(clip));
            float ex = timeToX(EditorOperations.getTotalEnd(clip));
            if (ctx.mouseX < sx || ctx.mouseX > ex) continue;
            // Right-click on clip
            if (onShowClipContext != null) onShowClipContext.accept(ctx.mouseX, ctx.mouseY);
            return true;
        }
        // Right-click on blank track area
        if (onShowTimelineContext != null) onShowTimelineContext.accept(ctx.mouseX, ctx.mouseY);
        return true;
    }

    private boolean clickToolbar(UIContext ctx) {
        int bx = x + (int)(3 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
        int by = y + headerH() + (int)(4 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy);

        if (ctx.isMouseIn(bx, by, btn(), btn())) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "+C");
            if (onToolAddClip != null) onToolAddClip.run(); return true;
        }
        by += btn() + btnGap();
        if (ctx.isMouseIn(bx, by, btn(), btn())) {
            boolean hasSel = selectedClip != null;
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "-C hasSelection=" + hasSel);
            if (hasSel && onToolDeleteClip != null) onToolDeleteClip.run(); return true;
        }
        by += btn() + btnGap() + 4;
        if (ctx.isMouseIn(bx, by, btn(), btn())) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "+K canAdd=" + canAddKf + " playhead=" + String.format("%.3f", playheadTime));
            if (canAddKf && onToolAddKeyframe != null) onToolAddKeyframe.run(); return true;
        }
        by += btn() + btnGap();
        if (ctx.isMouseIn(bx, by, btn(), btn())) {
            boolean hasKf = selectedKeyframe != null;
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "-K hasSelection=" + hasKf);
            if (hasKf && onToolDeleteKeyframe != null) onToolDeleteKeyframe.run(); return true;
        }
        by += btn() + btnGap() + 4;
        if (ctx.isMouseIn(bx, by, btn(), btn())) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "+T");
            if (onToolAddTrack != null) onToolAddTrack.run(); return true;
        }
        by += btn() + btnGap();
        if (ctx.isMouseIn(bx, by, btn(), btn())) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "-T trackIdx=" + selectedTrackIndex);
            if (selectedTrackIndex >= 0 && onToolDeleteTrack != null) onToolDeleteTrack.run(); return true;
        }
        by += btn() + btnGap() + 4;
        if (ctx.isMouseIn(bx, by, btn(), btn())) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "snap");
            if (onToolSnap != null) onToolSnap.run(); return true;
        }
        return false;
    }

    private boolean clickCanvas(UIContext ctx) {
        JsonArray arr = tracks();
        if (arr == null || ctx.mouseX < canvasX()) return false;

        int trackIdx = visibleIndexToTrackIndex((ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH());
        if (trackIdx < 0 || trackIdx >= arr.size()) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas_empty", ctx.mouseX, ctx.mouseY, false);
            return false;
        }
        JsonObject trackObj = arr.get(trackIdx).getAsJsonObject();
        // B2：锁定轨道不可选/拖/编辑（轨道头按钮仍可操作，用于解锁）
        if (isTrackLocked(trackObj)) return true;
        boolean isEventTrack = "EVENT".equals(trackObj.has("type") ? trackObj.get("type").getAsString() : "");
        JsonArray clips = trackObj.getAsJsonArray("clips");

        for (int i = clips.size() - 1; i >= 0; i--) {
            JsonObject clip = clips.get(i).getAsJsonObject();
            float sx = timeToX(EditorOperations.getStart(clip));
            float ex = timeToX(EditorOperations.getTotalEnd(clip));
            if (ctx.mouseX < sx || ctx.mouseX > ex) continue;

            if (isEventTrack) {
                // EVENT：菱形命中 → 关键帧；活动区间线命中 → 选中/拖拽 clip；无 trim
                JsonArray kfs = EditorOperations.keyframes(clip);
                if (kfs != null) {
                    for (int j = kfs.size() - 1; j >= 0; j--) {
                        JsonObject kf = kfs.get(j).getAsJsonObject();
                        float kx = timeToX(EditorOperations.getStart(clip) + kf.get("time").getAsFloat());
                        if (Math.abs(ctx.mouseX - kx) <= 5) {
                            keyframeClip = clip; draggingKeyframe = kf;
                            dragOffsetX = (int) (ctx.mouseX - kx);
                            dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                            EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "keyframe time=" + kf.get("time").getAsFloat());
                            if (onClickKeyframe != null) onClickKeyframe.accept(kf, clip);
                            return true;
                        }
                    }
                }
                if (Math.abs(ctx.mouseY - (computeTrackY(clip) + trackH() / 2)) <= 2) {
                    draggingClip = clip;
                    dragOffsetX = (int) (ctx.mouseX - sx);
                    dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "moveClip start=" + EditorOperations.getStart(clip));
                    if (ctx.isCtrlDown() || ctx.isShiftDown()) {
                        if (onToggleClip != null) onToggleClip.accept(clip);
                    } else if (!selectedClips.contains(clip)) {
                        if (onClickClip != null) onClickClip.accept(clip);
                    }
                    // D1：多选组拖拽
                    draggingSelection = selectedClips.size() > 1 && selectedClips.contains(clip);
                    selectionDragStartTime = EditorOperations.getStart(clip);
                    return true;
                }
                continue;
            }

            if (ctx.mouseX <= sx + resizeMargin()) {
                draggingResizeLeft = true; draggingClip = clip;
                dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "resizeLeft clip=" + EditorOperations.getStart(clip));
                return true;
            }
            if (ctx.mouseX >= ex - resizeMargin()) {
                draggingResizeRight = true; draggingClip = clip;
                dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "resizeRight clip=" + EditorOperations.getEnd(clip));
                return true;
            }

            JsonArray kfs = EditorOperations.keyframes(clip);
            if (kfs != null) {
                for (int j = kfs.size() - 1; j >= 0; j--) {
                    JsonObject kf = kfs.get(j).getAsJsonObject();
                    float kx = timeToX(EditorOperations.getStart(clip) + kf.get("time").getAsFloat());
                    if (Math.abs(ctx.mouseX - kx) <= 5) {
                        keyframeClip = clip; draggingKeyframe = kf;
                        dragOffsetX = (int) (ctx.mouseX - kx);
                        dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                        EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "keyframe time=" + kf.get("time").getAsFloat());
                        if (onClickKeyframe != null) onClickKeyframe.accept(kf, clip);
                        return true;
                    }
                }
            }

            draggingClip = clip;
            dragOffsetX = (int) (ctx.mouseX - sx);
            dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
            EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "moveClip start=" + EditorOperations.getStart(clip));

            if (ctx.isCtrlDown() || ctx.isShiftDown()) {
                if (onToggleClip != null) onToggleClip.accept(clip);
            } else if (!selectedClips.contains(clip)) {
                if (onClickClip != null) onClickClip.accept(clip);
            }
            // D1：多选组拖拽
            draggingSelection = selectedClips.size() > 1 && selectedClips.contains(clip);
            selectionDragStartTime = EditorOperations.getStart(clip);
            return true;
        }

        // Miss — start box select or jump playhead
        boxSelecting = true;
        boxStartX = ctx.mouseX;
        boxStartY = ctx.mouseY;
        boxStartTime = xToTime(ctx.mouseX);
        boxEndTime = boxStartTime;
        boxStartTrack = (ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH();
        return true;
    }

    @Override
    protected boolean onReleased(UIContext ctx) {
        // Track-height resize release
        if (trackResizing) {
            trackResizing = false;
            return true;
        }
        // Zoom-select release：缩放到选区（≥0.1s 才生效）
        if (zoomSelecting) {
            zoomSelecting = false;
            float a = Math.min(zoomBoxStartTime, zoomBoxEndTime);
            float b = Math.max(zoomBoxStartTime, zoomBoxEndTime);
            if (b - a >= 0.1f) {
                pixelsPerSecond = Math.max(10, Math.min(5000, canvasW() / (b - a)));
                scrollOffset = -a * pixelsPerSecond;
                clampScrollOffset();
            }
            return true;
        }
        boolean moved = Math.abs(ctx.mouseX - mouseDownX) > 2 || Math.abs(ctx.mouseY - mouseDownY) > 2;
        long dragDuration = System.currentTimeMillis() - dragStartTime;

        // Box select
        if (boxSelecting) {
            boxSelecting = false;
            float minTime = Math.min(boxStartTime, boxEndTime);
            float maxTime = Math.max(boxStartTime, boxEndTime);
            int minTrack = Math.max(0, Math.min(boxStartTrack, boxEndTrack));
            int maxTrack = Math.min(visibleTracks().size() - 1, Math.max(boxStartTrack, boxEndTrack));
            java.util.List<JsonObject> hit = new java.util.ArrayList<>();
            List<JsonObject> visTracks = visibleTracks();
            for (int vi = minTrack; vi <= maxTrack; vi++) {
                // B2：锁定轨道不参与框选
                if (isTrackLocked(visTracks.get(vi))) continue;
                JsonArray clips = visTracks.get(vi).getAsJsonArray("clips");
                for (JsonElement ce : clips) {
                    JsonObject c = ce.getAsJsonObject();
                    float cs = EditorOperations.getStart(c);
                    float ce2 = EditorOperations.getEnd(c);
                    if (cs < maxTime && ce2 > minTime) hit.add(c);
                }
            }
            if (!hit.isEmpty()) {
                if (onSelectClips != null) {
                    // D3：Shift 框选 → 合并到现有选择
                    if (ctx.isShiftDown()) {
                        List<JsonObject> merged = new ArrayList<>(selectedClips);
                        for (JsonObject c : hit) if (!merged.contains(c)) merged.add(c);
                        onSelectClips.accept(merged);
                    } else {
                        onSelectClips.accept(hit);
                    }
                }
                if (onClickClip != null) onClickClip.accept(hit.get(0));
            } else if (!moved) {
                // 纯点击（无拖拽）且框选无命中 → 播放头跳到点击时刻
                if (onClickAtTime != null) onClickAtTime.accept(Math.max(0, xToTime(boxStartTime)));
            }
            return true;
        }

        if (draggingClip != null) {
            if (draggingResizeLeft) {
                if (moved) {
                    float newT = snapToPlayhead(xToTime(ctx.mouseX));
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "resizeLeft to=" + newT);
                    if (onResizeLeft != null) onResizeLeft.accept(draggingClip, newT);
                } else {
                    JsonArray kfs = EditorOperations.keyframes(draggingClip);
                    if (kfs != null && kfs.size() > 0) {
                        if (onClickKeyframe != null) onClickKeyframe.accept(kfs.get(0).getAsJsonObject(), draggingClip);
                    }
                }
            } else if (draggingResizeRight) {
                if (moved) {
                    float newT = snapToPlayhead(xToTime(ctx.mouseX));
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "resizeRight to=" + newT);
                    if (onResizeRight != null) onResizeRight.accept(draggingClip, newT);
                } else {
                    JsonArray kfs = EditorOperations.keyframes(draggingClip);
                    if (kfs != null && kfs.size() > 0) {
                        JsonObject lastKf = kfs.get(kfs.size() - 1).getAsJsonObject();
                        if (onClickKeyframe != null) onClickKeyframe.accept(lastKf, draggingClip);
                    }
                }
            } else if (moved && onMoveClips != null) {
                float finalTime = dragTargetTime;
                if (draggingSelection) {
                    // D1：多选组整体平移
                    float delta = finalTime - selectionDragStartTime;
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "moveSelection delta=" + delta);
                    onMoveClips.accept(selectedClips, delta);
                } else {
                    float delta = finalTime - EditorOperations.getStart(draggingClip);
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "moveClip to=" + finalTime);
                    onMoveClips.accept(java.util.List.of(draggingClip), delta);
                }
            }
        }
        if (draggingKeyframe != null && onMoveKeyframe != null && moved) {
            float newLocal = xToTime(ctx.mouseX - dragOffsetX) - EditorOperations.getStart(keyframeClip);
            onMoveKeyframe.accept(draggingKeyframe, keyframeClip, newLocal);
        }
        draggingClip = null; draggingKeyframe = null; keyframeClip = null;
        draggingResizeLeft = false; draggingResizeRight = false;
        draggingSelection = false;
        dragLogCounter = 0; isDragging = false;
        snapIndicatorTimer = 0; snapIndicatorTime = -1;
        return false;
    }

    @Override
    protected boolean onDragged(UIContext ctx) {
        // 组 3：标尺按住拖动 = 持续 seek（Olive 标尺拖拽；EditorScreen 的 onClickAtTime 统一走 seekTo = 暂停+定位）
        // 必须带区域命中检查：onDragged 无命中测试且时间轴在 children 逆序中先于 PreviewArea——
        // 不加 isMouseIn 会把预览区（三维球/滑块）的拖拽事件全部抢走（播放头被 seek、关键帧上下文丢失）。
        if (ctx.isMouseIn(x, y, w, h) && draggingClip == null && draggingKeyframe == null && !boxSelecting && !zoomSelecting && !trackResizing
                && ctx.mouseY < canvasY() && ctx.mouseX >= x + toolbarW()) {
            float t = xToTime(ctx.mouseX);
            if (t >= 0 && onClickAtTime != null) {
                onClickAtTime.accept(Math.max(0, t));
            }
            return true;
        }
        // Track-height resize drag
        if (trackResizing) {
            int n = tracks() != null ? tracks().size() : 1;
            if (n > 0) {
                setTrackHRef(Math.round((ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / (float) n
                        / com.immersivecinematics.immersive_cinematics.editor.Scale.sy));
            }
            return true;
        }
        // Zoom-select drag (Ctrl+中键)
        if (zoomSelecting) {
            zoomBoxEndTime = xToTime(ctx.mouseX);
            return true;
        }
        // Box select drag
        if (boxSelecting) {
            boxEndTime = xToTime(ctx.mouseX);
            boxEndTrack = (ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH();
            return true;
        }

        if (draggingClip == null && draggingKeyframe == null) return false;

        // Update ghost
        if (draggingClip != null && !draggingResizeLeft && !draggingResizeRight) {
            float newStart = xToTime(ctx.mouseX - dragOffsetX);
            float snappedStart = snapToPlayheadAndClips(newStart, draggingClip);
            ghostStart = snappedStart;
            ghostEnd = snappedStart + EditorOperations.getDuration(draggingClip);
            ghostTrackY = computeTrackY(draggingClip);
            isDragging = true;
            dragTargetTime = snappedStart;
        }
        if (draggingResizeLeft) {
            float newStart = xToTime(ctx.mouseX);
            float snapped = snapToPlayhead(newStart);
            ghostStart = snapped;
            ghostEnd = EditorOperations.getTotalEnd(draggingClip);
            ghostTrackY = computeTrackY(draggingClip);
            isDragging = true;
        }
        if (draggingResizeRight) {
            float newEnd = xToTime(ctx.mouseX);
            float snapped = snapToPlayhead(newEnd);
            ghostStart = EditorOperations.getStart(draggingClip);
            ghostEnd = snapped;
            ghostTrackY = computeTrackY(draggingClip);
            isDragging = true;
        }

        long now = System.currentTimeMillis();
        dragLogCounter++;
        if (now - lastDragLogTime >= 250) {
            EditorLogger.mouseDrag(EditorLogger.TIMELINE, ctx.mouseX, ctx.mouseY,
                    draggingKeyframe != null ? "keyframe" : "clip", xToTime(ctx.mouseX));
            lastDragLogTime = now;
        }

        // A9：拖 AUDIO 轨道的 clip/关键帧时，100ms 节流推送实时预览
        JsonObject dragTarget = draggingClip != null ? draggingClip : (draggingKeyframe != null ? keyframeClip : null);
        if (dragTarget != null && "AUDIO".equals(trackTypeOf(dragTarget)) && onDragLivePreview != null) {
            if (now - lastLivePreviewMs >= 100) {
                lastLivePreviewMs = now;
                onDragLivePreview.run();
            }
        }
        return true;
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (!ctx.isMouseIn(x, y, w, h)) return false;

        boolean ctrl = ctx.isCtrlDown();
        boolean shift = ctx.isShiftDown();

        if (!ctrl && !shift) {
            // Horizontal scroll (no modifiers) — 行业惯例：滚轮=水平滚动
            float old = scrollOffset;
            scrollOffset += (float) scroll * 30;
            clampScrollOffset();
            EditorLogger.state(EditorLogger.TIMELINE, "scrollOffset", old, scrollOffset);
            EditorLogger.mouseScroll(EditorLogger.TIMELINE, scroll, ctx.mouseX, ctx.mouseY, "h_scroll");
            return true;
        }

        if (ctrl) {
            // Zoom (Ctrl+scroll)
            float oldPps = pixelsPerSecond;
            float scaleFactor = (scroll > 0) ? 1.25f : 0.8f;
            float newPps = Math.max(10, Math.min(5000, oldPps * scaleFactor));
            float mouseTime = xToTime(ctx.mouseX);
            pixelsPerSecond = newPps;
            scrollOffset = scrollOffset + mouseTime * (oldPps - newPps);
            clampScrollOffset();
            EditorLogger.state(EditorLogger.TIMELINE, "pixelsPerSecond", oldPps, pixelsPerSecond);
        } else if (shift) {
            // Vertical scroll (Shift+scroll) — 行业惯例：Shift+滚轮=垂直滚动
            setScrollTrackOffset(scrollTrackOffset - (int)(scroll * trackH() * 2));
            EditorLogger.state(EditorLogger.TIMELINE, "scrollTrackOffset", scrollTrackOffset, scrollTrackOffset);
        }

        EditorLogger.mouseScroll(EditorLogger.TIMELINE, scroll, ctx.mouseX, ctx.mouseY,
                ctrl ? "zoom" : shift ? "v_scroll" : "h_scroll");
        return true;
    }
    private float snapToPlayheadAndClips(float time, JsonObject selfClip) {
        float snapped = snapToPlayhead(time);
        if (snapped != time) return snapped;
        JsonArray arr = tracks();
        if (arr != null) {
            for (JsonElement te : arr) {
                for (JsonElement ce : te.getAsJsonObject().getAsJsonArray("clips")) {
                    JsonObject clip = ce.getAsJsonObject();
                    if (clip == selfClip) continue;
                    float otherStart = EditorOperations.getStart(clip);
                    float otherEnd = EditorOperations.getTotalEnd(clip);
                    if (Math.abs(timeToX(time) - timeToX(otherStart)) <= SNAP_THRESHOLD_PX) {
                        snapIndicatorTime = otherStart; snapIndicatorTimer = 10; return otherStart;
                    }
                    if (Math.abs(timeToX(time) - timeToX(otherEnd)) <= SNAP_THRESHOLD_PX) {
                        snapIndicatorTime = otherEnd; snapIndicatorTimer = 10; return otherEnd;
                    }
                }
            }
        }
        return time;
    }
    
    private float snapToPlayhead(float time) {
        float px = Math.abs(timeToX(time) - timeToX(playheadTime));
        if (px <= SNAP_THRESHOLD_PX) {
            snapIndicatorTime = playheadTime; snapIndicatorTimer = 10; return playheadTime;
        }
        return time;
    }
    
    /** 返回 clip 所在轨道的 type；找不到返回 null（A9 用） */
    private String trackTypeOf(JsonObject clip) {
        JsonArray arr = tracks();
        if (arr == null) return null;
        for (JsonElement te : arr) {
            JsonObject track = te.getAsJsonObject();
            JsonArray clips = track.getAsJsonArray("clips");
            if (clips != null) {
                for (JsonElement ce : clips) {
                    if (ce.getAsJsonObject() == clip) {
                        return track.has("type") ? track.get("type").getAsString() : null;
                    }
                }
            }
        }
        return null;
    }

    private int computeTrackY(JsonObject clip) {
        JsonArray arr = tracks();
        if (arr == null) return canvasY();
        List<JsonObject> visible = visibleTracks();
        for (int vi = 0; vi < visible.size(); vi++) {
            JsonArray clips = visible.get(vi).getAsJsonArray("clips");
            if (clips != null) {
                for (JsonElement ce : clips) {
                    if (ce.getAsJsonObject() == clip) return canvasY() + vi * trackH() - scrollTrackOffset;
                }
            }
        }
        return canvasY();
    }
    
    

    private static String fmt(float s) {
        int totalSec = Math.round(s);
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int sec = totalSec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%d:%02d", m, sec);
    }

    @FunctionalInterface
    public interface MoveKeyframeCallback {
        void accept(JsonObject kf, JsonObject clip, float newLocalTime);
    }
}
