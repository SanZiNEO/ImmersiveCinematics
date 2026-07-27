package com.immersivecinematics.immersive_cinematics.editor.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.editor.EditorOperations;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TimelineArea extends UIComponent {
    private JsonObject script;
    private JsonObject selectedClip;
    private JsonObject selectedKeyframe;
    private boolean canAddKf;
    private float playheadTime;
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
    
    // Snap indicator
    private float snapIndicatorTime = -1;
    private int snapIndicatorTimer;
    
    // Box select
    private boolean boxSelecting;
    private int boxStartX, boxStartY;
    private float boxStartTime, boxEndTime;
    private int boxStartTrack, boxEndTrack;
    
    private static final float SNAP_THRESHOLD_PX = 8f;

    private Consumer<Float> onClickAtTime;
    private Consumer<JsonObject> onClickClip;
    private Consumer<List<JsonObject>> onSelectClips;
    private BiConsumer<JsonObject, JsonObject> onClickKeyframe;
    private BiConsumer<JsonObject, Float> onMoveClip;
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


    public void setData(JsonObject script, JsonObject selClip, JsonObject selKf,
                        boolean canAddKf) {
        this.script = script;
        this.selectedClip = selClip;
        this.selectedKeyframe = selKf;
        this.canAddKf = canAddKf;
    }
    public void setPlayheadTime(float t) { playheadTime = t; }
    public float getPlayheadTime() { return playheadTime; }

    
    public void setSelectedClips(List<JsonObject> clips) { this.selectedClips = clips; }
    public void setOnToggleClip(Consumer<JsonObject> r) { onToggleClip = r; }
    public void setOnClickAtTime(Consumer<Float> r) { onClickAtTime = r; }
    public void setOnClickClip(Consumer<JsonObject> r) { onClickClip = r; }
    public void setOnClickKeyframe(BiConsumer<JsonObject, JsonObject> r) { onClickKeyframe = r; }
    public void setOnMoveClip(BiConsumer<JsonObject, Float> r) { onMoveClip = r; }
    public void setOnResizeLeft(BiConsumer<JsonObject, Float> r) { onResizeLeft = r; }
    public void setOnResizeRight(BiConsumer<JsonObject, Float> r) { onResizeRight = r; }
    public void setOnMoveKeyframe(MoveKeyframeCallback r) { onMoveKeyframe = r; }
    public void setOnToolAddClip(Runnable r) { onToolAddClip = r; }
    public void setOnShowClipContext(BiConsumer<Integer, Integer> r) { onShowClipContext = r; }
    public void setOnShowTimelineContext(BiConsumer<Integer, Integer> r) { onShowTimelineContext = r; }

    public void setOnToolDeleteClip(Runnable r) { onToolDeleteClip = r; }
    public void setOnShowRulerContext(BiConsumer<Integer, Integer> r) { onShowRulerContext = r; }
    public void setOnShowTrackLabelContext(BiConsumer<Integer, Integer> r) { onShowTrackLabelContext = r; }
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
        JsonArray arr = tracks();
        return arr != null ? arr.size() * trackH() : 0;
    }
    public void setOnToolSnap(Runnable r) { onToolSnap = r; }
    public int toolbarW() { return (int)(22 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx); }
    public void resetZoom() { pixelsPerSecond = 60f; scrollOffset = 0; }
    public void setPixelsPerSecond(float pps) { this.pixelsPerSecond = Math.max(10, Math.min(5000, pps)); clampScrollOffset(); }

    public int headerH()  { return (int)(20 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy); }
    public void setScrollOffset(float offset) { this.scrollOffset = offset; clampScrollOffset(); }

    private int labelW()   { return (int)(58 * com.immersivecinematics.immersive_cinematics.editor.Scale.sx); }
    private int trackH()   { return (int)(28 * com.immersivecinematics.immersive_cinematics.editor.Scale.sy); }
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

        ctx.graphics.fill(x, y, x + w, y + h, 0xFF171717);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
        ctx.graphics.fill(x, y, x + w, y + headerH(), 0xFF1F1F1F);

        drawRuler(ctx, cx, y, cw);
        drawToolbar(ctx);
        drawTracks(ctx, cx, cy);
        drawPlayhead(ctx, cx, cy, cw);

        // Ghost drag preview
        if (isDragging && draggingClip != null) {
            int gx = (int) timeToX(ghostStart);
            int gw = Math.max(2, (int)(timeToX(ghostEnd) - gx));
            ctx.graphics.fill(gx, ghostTrackY + 2, gx + gw, ghostTrackY + trackH() - 2, 0x803A6DB5);
            ctx.graphics.renderOutline(gx, ghostTrackY + 2, gw, trackH() - 4, 0x805A8DD5);
        }

        // Snap indicator
        if (snapIndicatorTimer > 0 && snapIndicatorTime >= 0) {
            int sx = (int) timeToX(snapIndicatorTime);
            if (snapIndicatorTimer % 3 < 2) {
                ctx.graphics.fill(sx, y + headerH(), sx + 2, y + h, 0xFFFFD700);
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
            ctx.graphics.fill(bx, by, bx + bw, by + bh, 0x223A6DB5);
            ctx.graphics.renderOutline(bx, by, bw, bh, 0xFF3A6DB5);
        }
    }

    private void drawRuler(UIContext ctx, int cx, int top, int cw) {
        float visibleWidth = cw / pixelsPerSecond;
        float visibleStart = Math.max(0, -scrollOffset / pixelsPerSecond);
        float visibleEnd = visibleStart + visibleWidth;

        float majorInterval;
        if (visibleWidth <= 2) majorInterval = 0.5f;
        else if (visibleWidth <= 5) majorInterval = 1;
        else if (visibleWidth <= 15) majorInterval = 5;
        else if (visibleWidth <= 60) majorInterval = 10;
        else majorInterval = (float) Math.pow(10, Math.ceil(Math.log10(visibleWidth)) - 1);
        float minorInterval = majorInterval / 2;

        ctx.graphics.fill(cx, top, cx + cw, top + headerH(), 0xFF1F1F1F);

        float startTick = (float) Math.floor(visibleStart / majorInterval) * majorInterval;
        for (float t = startTick; t <= visibleEnd + majorInterval; t += majorInterval) {
            float sx = cx + t * pixelsPerSecond + scrollOffset;
            if (sx < cx - 20 || sx > cx + cw) continue;
            int sxInt = (int) sx;
            ctx.graphics.fill(sxInt, top, sxInt + 2, top + headerH(), 0xFF3A3A3A);
            ctx.graphics.drawString(ctx.font, fmt(t), sxInt + 3, top + 2, 0xFF777777);
        }

        float startMinor = (float) Math.floor(visibleStart / minorInterval) * minorInterval;
        for (float t = startMinor; t <= visibleEnd + minorInterval; t += minorInterval) {
            float sx = cx + t * pixelsPerSecond + scrollOffset;
            if (sx < cx - 20 || sx > cx + cw) continue;
            boolean isMajor = Math.abs(t % majorInterval) < 0.001f;
            if (!isMajor) {
                ctx.graphics.fill((int) sx, top + headerH() - 6, (int) sx + 1, top + headerH(), 0xFF2A2A2A);
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
        int bg = !active ? 0xFF222222 : hover ? hc : c;
        ctx.graphics.fill(bx, by, bx + btn(), by + btn(), bg);
        ctx.graphics.renderOutline(bx, by, btn(), btn(), active ? 0xFF666666 : 0xFF333333);
        int tw = ctx.font.width(label);
        ctx.graphics.drawString(ctx.font, label, bx + (btn() - tw) / 2, by + (btn() - 8) / 2, active ? 0xFFFFFFFF : 0xFF555555);
    }

    private void drawTracks(UIContext ctx, int cx, int cy) {
        JsonArray arr = tracks();
        if (arr == null) return;
        
        int labelAreaX = x + toolbarW();
        int labelAreaW = labelW();
        
        for (int ti = 0; ti < arr.size(); ti++) {
            JsonObject track = arr.get(ti).getAsJsonObject();
            int ty = cy + ti * trackH() - scrollTrackOffset;
            String type = track.has("type") ? track.get("type").getAsString() : "TRACK";
            
            ctx.graphics.fill(labelAreaX, ty, labelAreaX + labelAreaW, ty + trackH(), 0xFF1A1A1A);
            ctx.graphics.renderOutline(labelAreaX, ty, labelAreaW, trackH(), 0xFF333333);
            int colorMark = trackTypeColor(type);
            ctx.graphics.fill(labelAreaX + 4, ty + 4, labelAreaX + 8, ty + trackH() - 4, colorMark);
            ctx.graphics.drawString(ctx.font, type, labelAreaX + 12, ty + (trackH() - 8) / 2, 0xFFAAAAAA);
            
            int bgColor = (ti == selectedTrackIndex) ? lighten(trackBgColor(type), 0.15f) : trackBgColor(type);
            ctx.graphics.fill(cx, ty, cx + canvasW(), ty + trackH(), bgColor);
            
            JsonArray clips = track.getAsJsonArray("clips");
            if (clips == null || clips.size() == 0) {
                String emptyHint = I18n.get("editor.timeline.empty_track");
            } else {
                for (JsonElement ce : clips) {
                    drawClip(ctx, ce.getAsJsonObject(), ty, type);
                }
            }
            if (ti < arr.size() - 1) {
                ctx.graphics.fill(cx, ty + trackH() - 1, cx + canvasW(), ty + trackH(), 0xFF333333);
            }
        }
    }
    
    private int trackTypeColor(String type) {
        return switch (type.toUpperCase()) {
            case "CAMERA" -> 0xFF3A6DB5;
            case "LETTERBOX" -> 0xFF3A8A3A;
            case "AUDIO" -> 0xFF8A8A3A;
            case "EVENT" -> 0xFF8A3A3A;
            case "MOD_EVENT" -> 0xFF8A3A8A;
            case "OVERLAY" -> 0xFF6A3A8A;
            default -> 0xFF666666;
        };
    }
    
    private int trackBgColor(String type) {
        return switch (type.toUpperCase()) {
            case "CAMERA" -> 0xFF1a2744;
            case "LETTERBOX" -> 0xFF1a2e1a;
            case "AUDIO" -> 0xFF2e2e1a;
            case "EVENT" -> 0xFF2e1a1a;
            case "MOD_EVENT" -> 0xFF2e1a2e;
            case "OVERLAY" -> 0xFF2e1a2e;
            default -> 0xFF1A1A2E;
        };
    }
    
    private void drawClip(UIContext ctx, JsonObject clip, int ty, String trackType) {
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
        int fill = clipFillColor(trackType, isSel, hovered);
        ctx.graphics.fill(clipX, ty + 2, clipX + clipW, ty + trackH() - 2, fill);

        if (isSel) {
            ctx.graphics.fill(clipX, ty + 2, clipX + 3, ty + trackH() - 2, 0xFFFFDD44);
        }

        int bright = lighten(clipFillColor(trackType, false, false), 0.4f);
        ctx.graphics.fill(clipX, ty + 2, clipX + clipW, ty + 3, bright);
        ctx.graphics.fill(clipX, ty + 2, clipX + 1, ty + trackH() - 2, bright);
        int dark = 0xFF222222;
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
            if (lw + 8 < clipW) ctx.graphics.drawString(ctx.font, label, clipX + 4, ty + (trackH() - 8) / 2, 0xFFCCCCCC);
        }

        JsonArray kfs = EditorOperations.keyframes(clip);
        if (kfs != null) {
            for (JsonElement ke : kfs) {
                JsonObject kf = ke.getAsJsonObject();
                float kx = timeToX(EditorOperations.getStart(clip) + kf.get("time").getAsFloat());
                if (kx >= clipX + 2 && kx <= clipX + clipW - 2) {
                    int kc = (kf == selectedKeyframe && clip == selectedClip) ? 0xFFFFFF88 : 0xFFAAAAAA;
                    ctx.graphics.fill((int) kx - 3, ty + trackH() / 2 - 3, (int) kx + 3, ty + trackH() / 2 + 3, kc);
                }
            }
        }
    }
    
    private static int clipFillColor(String trackType, boolean selected, boolean hovered) {
        int base = switch (trackType.toUpperCase()) {
            case "CAMERA" -> 0xFF3A6DB5;
            case "LETTERBOX" -> 0xFF3A8A3A;
            case "AUDIO" -> 0xFF8A8A3A;
            case "EVENT" -> 0xFF8A3A3A;
            case "MOD_EVENT" -> 0xFF8A3A8A;
            case "OVERLAY" -> 0xFF6A3A8A;
            default -> 0xFF3A3F4A;
        };
        if (selected) base = lighten(base, 0.3f);
        if (hovered) base = lighten(base, 0.15f);
        return base;
    }
    
    private static int lighten(int argb, float amount) {
        int r = Math.min(255, (int)(((argb >> 16) & 0xFF) * (1 + amount)));
        int g = Math.min(255, (int)(((argb >> 8) & 0xFF) * (1 + amount)));
        int b = Math.min(255, (int)((argb & 0xFF) * (1 + amount)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void drawPlayhead(UIContext ctx, int cx, int cy, int cw) {
        float px = timeToX(playheadTime);
        if (px >= cx && px <= cx + cw) {
            int pxInt = (int) px;
            ctx.graphics.fill(pxInt, y, pxInt + 2, y + h, 0xFFFF3333);
            ctx.graphics.fill(pxInt - 3, y, pxInt + 3, y + 4, 0xFFFF5555);
            ctx.graphics.fill(pxInt - 1, y + 4, pxInt + 1, y + 6, 0xFFFF5555);
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
            int ti = (ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH();
            if (ti >= 0 && ti < tracks().size()) {
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
        if (rightClick) {
            return clickCanvasRight(ctx);
        }
        return clickCanvas(ctx);
    }
    
    private boolean clickCanvasRight(UIContext ctx) {
        JsonArray arr = tracks();
        if (arr == null || ctx.mouseX < canvasX()) return false;

        int trackIdx = (ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH();
        if (trackIdx < 0 || trackIdx >= arr.size()) return false;
        JsonArray clips = arr.get(trackIdx).getAsJsonObject().getAsJsonArray("clips");

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

        int trackIdx = (ctx.getAdjustedMouseY() - canvasY() + scrollTrackOffset) / trackH();
        if (trackIdx < 0 || trackIdx >= arr.size()) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas_empty", ctx.mouseX, ctx.mouseY, false);
            return false;
        }
        JsonArray clips = arr.get(trackIdx).getAsJsonObject().getAsJsonArray("clips");

        for (int i = clips.size() - 1; i >= 0; i--) {
            JsonObject clip = clips.get(i).getAsJsonObject();
            float sx = timeToX(EditorOperations.getStart(clip));
            float ex = timeToX(EditorOperations.getTotalEnd(clip));
            if (ctx.mouseX < sx || ctx.mouseX > ex) continue;

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

            if (ctx.isCtrlDown()) {
                if (onToggleClip != null) onToggleClip.accept(clip);
            } else if (!selectedClips.contains(clip)) {
                if (onClickClip != null) onClickClip.accept(clip);
            }
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
        boolean moved = Math.abs(ctx.mouseX - mouseDownX) > 2 || Math.abs(ctx.mouseY - mouseDownY) > 2;
        long dragDuration = System.currentTimeMillis() - dragStartTime;

        // Box select
        if (boxSelecting) {
            boxSelecting = false;
            float minTime = Math.min(boxStartTime, boxEndTime);
            float maxTime = Math.max(boxStartTime, boxEndTime);
            int minTrack = Math.max(0, Math.min(boxStartTrack, boxEndTrack));
            int maxTrack = Math.min(tracks().size() - 1, Math.max(boxStartTrack, boxEndTrack));
            java.util.List<JsonObject> hit = new java.util.ArrayList<>();
            for (int ti = minTrack; ti <= maxTrack; ti++) {
                JsonArray clips = tracks().get(ti).getAsJsonObject().getAsJsonArray("clips");
                for (JsonElement ce : clips) {
                    JsonObject c = ce.getAsJsonObject();
                    float cs = EditorOperations.getStart(c);
                    float ce2 = EditorOperations.getEnd(c);
                    if (cs < maxTime && ce2 > minTime) hit.add(c);
                }
            }
            if (!hit.isEmpty()) {
                if (onSelectClips != null) onSelectClips.accept(hit);
                if (onClickClip != null && !hit.isEmpty()) onClickClip.accept(hit.get(0));
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
            } else if (moved && onMoveClip != null) {
                float finalTime = dragTargetTime;
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "moveClip to=" + finalTime);
                onMoveClip.accept(draggingClip, finalTime);
            }
        }
        if (draggingKeyframe != null && onMoveKeyframe != null && moved) {
            float newLocal = xToTime(ctx.mouseX - dragOffsetX) - EditorOperations.getStart(keyframeClip);
            onMoveKeyframe.accept(draggingKeyframe, keyframeClip, newLocal);
        }
        draggingClip = null; draggingKeyframe = null; keyframeClip = null;
        draggingResizeLeft = false; draggingResizeRight = false;
        dragLogCounter = 0; isDragging = false;
        snapIndicatorTimer = 0; snapIndicatorTime = -1;
        return false;
    }

    @Override
    protected boolean onDragged(UIContext ctx) {
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
        return true;
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (!ctx.isMouseIn(x, y, w, h)) return false;

        boolean ctrl = ctx.isCtrlDown();
        boolean shift = ctx.isShiftDown();

        if (!ctrl && !shift) {
            // Vertical scroll (no modifiers)
            setScrollTrackOffset(scrollTrackOffset - (int)(scroll * trackH() * 2));
            EditorLogger.state(EditorLogger.TIMELINE, "scrollTrackOffset", scrollTrackOffset, scrollTrackOffset);
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
            // Horizontal scroll (Shift+scroll)
            float old = scrollOffset;
            scrollOffset += (float) scroll * 30;
            clampScrollOffset();
            EditorLogger.state(EditorLogger.TIMELINE, "scrollOffset", old, scrollOffset);
        }

        EditorLogger.mouseScroll(EditorLogger.TIMELINE, scroll, ctx.mouseX, ctx.mouseY,
                ctrl ? "zoom" : shift ? "h_scroll" : "v_scroll");
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
    
    private int computeTrackY(JsonObject clip) {
        JsonArray arr = tracks();
        if (arr == null) return canvasY();
        for (int ti = 0; ti < arr.size(); ti++) {
            for (JsonElement ce : arr.get(ti).getAsJsonObject().getAsJsonArray("clips")) {
                if (ce.getAsJsonObject() == clip) return canvasY() + ti * trackH() - scrollTrackOffset;
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
