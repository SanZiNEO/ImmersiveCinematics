package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.EditorCore;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.model.*;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TimelineArea extends UIComponent {
    private final List<UIComponent> children = new ArrayList<>();
    private EditorCore core;
    private float playheadTime;
    private float pixelsPerSecond = 60f;
    private float scrollOffset;

    private EditorClip draggingClip;
    private EditorKeyframe draggingKeyframe;
    private EditorClip keyframeClip;
    private int dragOffsetX;
    private int mouseDownX;
    private int mouseDownY;
    private int mouseDownButton;
    private boolean draggingResizeLeft;
    private boolean draggingResizeRight;
    private long dragStartTime;
    private long lastDragLogTime;
    private int dragLogCounter;

    private Consumer<Float> onClickAtTime;
    private Consumer<EditorClip> onClickClip;
    private BiConsumer<EditorKeyframe, EditorClip> onClickKeyframe;
    private BiConsumer<EditorClip, Float> onMoveClip;
    private BiConsumer<EditorClip, Float> onResizeLeft;
    private BiConsumer<EditorClip, Float> onResizeRight;
    private MoveKeyframeCallback onMoveKeyframe;
    private Runnable onToolAddClip;
    private Runnable onToolDeleteClip;
    private Runnable onToolAddKeyframe;
    private Runnable onToolDeleteKeyframe;
    private Runnable onToolSnap;

    private static final int TOOLBAR_W = 22;
    private static final int LABEL_W = 58;
    private static final int LEFT_W = TOOLBAR_W + LABEL_W;
    private static final int HEADER_H = 20;
    private static final int TRACK_H = 28;
    private static final int RESIZE_MARGIN = 4;
    private static final int BTN = 16;
    private static final int BTN_GAP = 2;

    public TimelineArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.TIMELINE, "full_area", x, y, w, h);
    }

    public void setCore(EditorCore c) { core = c; }
    public void setPlayheadTime(float t) { playheadTime = t; }
    public float getPlayheadTime() { return playheadTime; }

    public void setOnClickAtTime(Consumer<Float> r) { onClickAtTime = r; }
    public void setOnClickClip(Consumer<EditorClip> r) { onClickClip = r; }
    public void setOnClickKeyframe(BiConsumer<EditorKeyframe, EditorClip> r) { onClickKeyframe = r; }
    public void setOnMoveClip(BiConsumer<EditorClip, Float> r) { onMoveClip = r; }
    public void setOnResizeLeft(BiConsumer<EditorClip, Float> r) { onResizeLeft = r; }
    public void setOnResizeRight(BiConsumer<EditorClip, Float> r) { onResizeRight = r; }
    public void setOnMoveKeyframe(MoveKeyframeCallback r) { onMoveKeyframe = r; }
    public void setOnToolAddClip(Runnable r) { onToolAddClip = r; }
    public void setOnToolDeleteClip(Runnable r) { onToolDeleteClip = r; }
    public void setOnToolAddKeyframe(Runnable r) { onToolAddKeyframe = r; }
    public void setOnToolDeleteKeyframe(Runnable r) { onToolDeleteKeyframe = r; }
    public void setOnToolSnap(Runnable r) { onToolSnap = r; }

    public int canvasX() { return x + LEFT_W; }
    public int canvasY() { return y + HEADER_H; }
    public int canvasW() { return w - LEFT_W; }
    public float timeToX(float t) { return canvasX() + (t * pixelsPerSecond) + scrollOffset; }
    public float xToTime(float px) { return (px - canvasX() - scrollOffset) / pixelsPerSecond; }

    @Override
    public void render(UIContext ctx) {
        int cx = canvasX();
        int cy = canvasY();
        int cw = canvasW();
        EditorScript script = core != null ? core.getScript() : null;

        ctx.graphics.fill(x, y, x + w, y + h, 0xFF171717);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
        ctx.graphics.fill(x, y, x + w, y + HEADER_H, 0xFF1F1F1F);

        drawRuler(ctx, cx, y, cw, script);
        drawToolbar(ctx);
        if (script != null) drawTracks(ctx, script, cx, cy);
        drawPlayhead(ctx, cx, cy, cw);

        for (UIComponent c : children) c.render(ctx);
    }

    private void drawRuler(UIContext ctx, int cx, int top, int cw, EditorScript script) {
        float total = script != null ? script.totalDuration : 0;
        float interval = (total * pixelsPerSecond + Math.abs(scrollOffset)) < 200 ? 10
                : (total * pixelsPerSecond + Math.abs(scrollOffset)) < 400 ? 5 : 1;
        for (float t = 0; t <= total + interval; t += interval) {
            float sx = cx + t * pixelsPerSecond + scrollOffset;
            if (sx < cx - 20 || sx > cx + cw) continue;
            ctx.graphics.fill((int) sx, top, (int) sx + 1, top + HEADER_H, 0xFF3A3A3A);
            ctx.graphics.drawString(ctx.font, fmt(t), (int) sx + 2, top + 2, 0xFF777777);
        }
    }

    private void drawToolbar(UIContext ctx) {
        int bx = x + 3;
        int by = y + HEADER_H + 4;
        EditorClip selClip = core != null ? core.getSelectedClip() : null;
        EditorKeyframe selKf = core != null ? core.getSelectedKeyframe() : null;
        boolean canAddKf = core != null && core.canAddKeyframeAt(playheadTime);
        boolean snapOn = core != null && core.isAutoSnap();

        drawBtn(ctx, bx, by, "+C", 0xFF338833, 0xFF44AA44, true); by += BTN + BTN_GAP;
        drawBtn(ctx, bx, by, "-C", 0xFF883333, 0xFFAA4444, selClip != null); by += BTN + BTN_GAP + 4;
        drawBtn(ctx, bx, by, "+K", 0xFF333388, 0xFF4444AA, canAddKf); by += BTN + BTN_GAP;
        drawBtn(ctx, bx, by, "-K", 0xFF883366, 0xFFAA4488, selKf != null); by += BTN + BTN_GAP + 4;
        drawSnapBtn(ctx, bx, by, snapOn);
    }

    private void drawSnapBtn(UIContext ctx, int bx, int by, boolean active) {
        boolean hover = ctx.isMouseIn(bx, by, BTN, BTN);
        int bg = active ? (hover ? 0xFF448844 : 0xFF337733) : (hover ? 0xFF444444 : 0xFF222222);
        ctx.graphics.fill(bx, by, bx + BTN, by + BTN, bg);
        ctx.graphics.renderOutline(bx, by, BTN, BTN, active ? 0xFF66AA66 : 0xFF555555);
        int tw = ctx.font.width("\u00AB\u00BB");
        ctx.graphics.drawString(ctx.font, "\u00AB\u00BB", bx + (BTN - tw) / 2, by + (BTN - 8) / 2, active ? 0xFFFFFFFF : 0xFF888888);
    }

    private void drawBtn(UIContext ctx, int bx, int by, String label, int c, int hc, boolean active) {
        boolean hover = ctx.isMouseIn(bx, by, BTN, BTN);
        int bg = !active ? 0xFF222222 : hover ? hc : c;
        ctx.graphics.fill(bx, by, bx + BTN, by + BTN, bg);
        ctx.graphics.renderOutline(bx, by, BTN, BTN, active ? 0xFF666666 : 0xFF333333);
        int tw = ctx.font.width(label);
        ctx.graphics.drawString(ctx.font, label, bx + (BTN - tw) / 2, by + (BTN - 8) / 2, active ? 0xFFFFFFFF : 0xFF555555);
    }

    private void drawTracks(UIContext ctx, EditorScript script, int cx, int cy) {
        EditorClip selClip = core.getSelectedClip();
        EditorKeyframe selKf = core.getSelectedKeyframe();
        for (int ti = 0; ti < script.tracks.size(); ti++) {
            EditorTrack track = script.tracks.get(ti);
            int ty = cy + ti * TRACK_H;
            ctx.graphics.fill(x + TOOLBAR_W, ty, cx, ty + TRACK_H, 0xFF222222);
            ctx.graphics.drawString(ctx.font, track.type.toUpperCase(), x + TOOLBAR_W + 4, ty + (TRACK_H - 8) / 2, 0xFF888888);
            for (EditorClip clip : track.clips) {
                drawClip(ctx, clip, selClip, selKf, ty);
            }
        }
    }

    private void drawClip(UIContext ctx, EditorClip clip, EditorClip selClip, EditorKeyframe selKf, int ty) {
        float sx = timeToX(clip.startTime);
        float ex = timeToX(clip.endTime());
        int cw = canvasW();
        int cx = canvasX();
        if (ex < cx || sx > cx + cw) return;
        int clipX = Math.max(cx, (int) sx);
        int clipW = Math.min(cx + cw, (int) ex) - clipX;
        if (clipW < 2) clipW = 2;

        boolean isSel = (clip == selClip);
        int fill = isSel ? 0xFF4A4F5A : 0xFF3A3F4A;
        if (ctx.isMouseIn(clipX, ty, clipW, TRACK_H)) fill = isSel ? 0xFF5A5F6A : 0xFF4A4F5A;
        ctx.graphics.fill(clipX, ty + 2, clipX + clipW, ty + TRACK_H - 2, fill);
        ctx.graphics.renderOutline(clipX, ty + 2, clipW, TRACK_H - 4, isSel ? 0xFF707580 : 0xFF505560);

        if (ctx.isMouseIn(clipX, ty + 2, RESIZE_MARGIN, TRACK_H - 4))
            ctx.graphics.fill(clipX, ty + 2, clipX + RESIZE_MARGIN, ty + TRACK_H - 2, 0x55FFFFFF);
        if (ctx.isMouseIn(clipX + clipW - RESIZE_MARGIN, ty + 2, RESIZE_MARGIN, TRACK_H - 4))
            ctx.graphics.fill(clipX + clipW - RESIZE_MARGIN, ty + 2, clipX + clipW, ty + TRACK_H - 2, 0x55FFFFFF);

        if (clipW > 30) {
            String label = fmt(clip.startTime) + "-" + fmt(clip.endTime());
            int lw = ctx.font.width(label);
            if (lw + 8 < clipW) ctx.graphics.drawString(ctx.font, label, clipX + 4, ty + (TRACK_H - 8) / 2, 0xFFCCCCCC);
        }
        for (EditorKeyframe kf : clip.keyframes) {
            float kx = timeToX(clip.startTime + kf.time);
            if (kx >= clipX + 2 && kx <= clipX + clipW - 2) {
                int kc = (kf == selKf && clip == selClip) ? 0xFFFFFF88 : 0xFFAAAAAA;
                ctx.graphics.fill((int) kx - 3, ty + TRACK_H / 2 - 3, (int) kx + 3, ty + TRACK_H / 2 + 3, kc);
            }
        }
    }

    private void drawPlayhead(UIContext ctx, int cx, int cy, int cw) {
        float px = timeToX(playheadTime);
        if (px >= cx && px <= cx + cw)
            ctx.graphics.fill((int) px, cy, (int) px + 2, y + h, 0xFFFF3333);
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        if (!ctx.isMouseIn(x, y, w, h)) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "full_area", ctx.mouseX, ctx.mouseY, false);
            return false;
        }
        EditorLogger.areaHit(EditorLogger.TIMELINE, "full_area", ctx.mouseX, ctx.mouseY, true);
        mouseDownX = ctx.mouseX;
        mouseDownY = ctx.mouseY;
        mouseDownButton = 0;

        if (ctx.mouseX < x + TOOLBAR_W && ctx.mouseY >= y + HEADER_H) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "toolbar", ctx.mouseX, ctx.mouseY, true);
            return clickToolbar(ctx);
        }

        if (ctx.mouseY < canvasY() && ctx.mouseX >= x + TOOLBAR_W) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "ruler", ctx.mouseX, ctx.mouseY, true);
            float t = xToTime(ctx.mouseX);
            if (t >= 0 && onClickAtTime != null) {
                float snapped = Math.max(0, t);
                EditorLogger.playhead(EditorLogger.TIMELINE, snapped, ctx.mouseX, "ruler_click");
                onClickAtTime.accept(snapped);
            }
            return true;
        }

        EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas", ctx.mouseX, ctx.mouseY, true);
        return clickCanvas(ctx);
    }

    private boolean clickToolbar(UIContext ctx) {
        int bx = x + 3;
        int by = y + HEADER_H + 4;

        if (ctx.isMouseIn(bx, by, BTN, BTN)) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "+C");
            if (onToolAddClip != null) onToolAddClip.run(); return true;
        }
        by += BTN + BTN_GAP;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) {
            boolean hasSel = core != null && core.getSelectedClip() != null;
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "-C hasSelection=" + hasSel);
            if (hasSel && onToolDeleteClip != null) onToolDeleteClip.run(); return true;
        }
        by += BTN + BTN_GAP + 4;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) {
            boolean canAdd = core != null && core.canAddKeyframeAt(playheadTime);
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "+K canAdd=" + canAdd + " playhead=" + String.format("%.3f", playheadTime));
            if (canAdd && onToolAddKeyframe != null) onToolAddKeyframe.run(); return true;
        }
        by += BTN + BTN_GAP;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) {
            boolean hasKf = core != null && core.getSelectedKeyframe() != null;
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "-K hasSelection=" + hasKf);
            if (hasKf && onToolDeleteKeyframe != null) onToolDeleteKeyframe.run(); return true;
        }
        by += BTN + BTN_GAP + 4;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "snap");
            if (onToolSnap != null) onToolSnap.run(); return true;
        }
        return false;
    }

    private boolean clickCanvas(UIContext ctx) {
        EditorScript script = core != null ? core.getScript() : null;
        if (script == null || ctx.mouseX < canvasX()) return false;

        int trackIdx = (ctx.mouseY - canvasY()) / TRACK_H;
        if (trackIdx < 0 || trackIdx >= script.tracks.size()) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas_empty", ctx.mouseX, ctx.mouseY, false);
            return false;
        }
        EditorTrack track = script.tracks.get(trackIdx);

        for (int i = track.clips.size() - 1; i >= 0; i--) {
            EditorClip clip = track.clips.get(i);
            float sx = timeToX(clip.startTime);
            float ex = timeToX(clip.endTime());
            if (ctx.mouseX < sx || ctx.mouseX > ex) continue;

            if (ctx.mouseX <= sx + RESIZE_MARGIN) {
                draggingResizeLeft = true; draggingClip = clip;
                dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "resizeLeft clip=" + clip.startTime);
                return true;
            }
            if (ctx.mouseX >= ex - RESIZE_MARGIN) {
                draggingResizeRight = true; draggingClip = clip;
                dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "resizeRight clip=" + clip.endTime());
                return true;
            }

            for (int j = clip.keyframes.size() - 1; j >= 0; j--) {
                EditorKeyframe kf = clip.keyframes.get(j);
                float kx = timeToX(clip.startTime + kf.time);
                if (Math.abs(ctx.mouseX - kx) <= 5) {
                    keyframeClip = clip;
                    draggingKeyframe = kf;
                    dragOffsetX = (int) (ctx.mouseX - kx);
                    dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "keyframe time=" + kf.time + " clipStart=" + clip.startTime);
                    if (onClickKeyframe != null) onClickKeyframe.accept(kf, clip);
                    return true;
                }
            }

            draggingClip = clip;
            dragOffsetX = (int) (ctx.mouseX - sx);
            dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
            EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "moveClip start=" + clip.startTime);
            if (onClickClip != null) onClickClip.accept(clip);
            return true;
        }
        EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas_miss", ctx.mouseX, ctx.mouseY, false);
        return false;
    }

    @Override
    public boolean mouseReleased(UIContext ctx) {
        boolean moved = Math.abs(ctx.mouseX - mouseDownX) > 2 || Math.abs(ctx.mouseY - mouseDownY) > 2;
        long dragDuration = System.currentTimeMillis() - dragStartTime;

        if (draggingClip != null) {
            if (draggingResizeLeft) {
                if (moved) {
                    float newT = xToTime(ctx.mouseX);
                    EditorLogger.scrubSession(EditorLogger.TIMELINE, newT, ctx.mouseX, "end_resizeLeft");
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "resizeLeft clip=" + draggingClip.startTime + " to=" + newT + " moved=" + moved + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
                    if (onResizeLeft != null) onResizeLeft.accept(draggingClip, newT);
                } else if (!draggingClip.keyframes.isEmpty()) {
                    EditorKeyframe firstKf = draggingClip.keyframes.get(0);
                    EditorLogger.action(EditorLogger.TIMELINE, "CLICK_NO_DRAG", "resizeLeft_selectFirstKf time=" + firstKf.time);
                    if (onClickKeyframe != null) onClickKeyframe.accept(firstKf, draggingClip);
                }
            } else if (draggingResizeRight) {
                if (moved) {
                    float newT = xToTime(ctx.mouseX);
                    EditorLogger.scrubSession(EditorLogger.TIMELINE, newT, ctx.mouseX, "end_resizeRight");
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "resizeRight clip=" + draggingClip.endTime() + " to=" + newT + " moved=" + moved + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
                    if (onResizeRight != null) onResizeRight.accept(draggingClip, newT);
                } else if (!draggingClip.keyframes.isEmpty()) {
                    EditorKeyframe lastKf = draggingClip.keyframes.get(draggingClip.keyframes.size() - 1);
                    EditorLogger.action(EditorLogger.TIMELINE, "CLICK_NO_DRAG", "resizeRight_selectLastKf time=" + lastKf.time);
                    if (onClickKeyframe != null) onClickKeyframe.accept(lastKf, draggingClip);
                }
            } else if (moved && onMoveClip != null) {
                float newT = xToTime(ctx.mouseX - dragOffsetX);
                EditorLogger.scrubSession(EditorLogger.TIMELINE, newT, ctx.mouseX, "end_moveClip");
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "moveClip clip=" + draggingClip.startTime + " to=" + newT + " moved=" + moved + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
                onMoveClip.accept(draggingClip, newT);
            } else {
                EditorLogger.action(EditorLogger.TIMELINE, "CLICK_NO_DRAG", "clip select=" + draggingClip.startTime + " moved=" + moved);
            }
        }
        if (draggingKeyframe != null && onMoveKeyframe != null && moved) {
            float newLocal = xToTime(ctx.mouseX - dragOffsetX) - keyframeClip.startTime;
            EditorLogger.scrubSession(EditorLogger.TIMELINE, newLocal, ctx.mouseX, "end_moveKeyframe");
            EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "moveKeyframe kf=" + draggingKeyframe.time + " to=" + newLocal + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
            onMoveKeyframe.accept(draggingKeyframe, keyframeClip, newLocal);
        }
        draggingClip = null;
        draggingKeyframe = null;
        keyframeClip = null;
        draggingResizeLeft = false;
        draggingResizeRight = false;
        dragLogCounter = 0;
        return false;
    }

    @Override
    public boolean mouseDragged(UIContext ctx) {
        if (draggingClip == null && draggingKeyframe == null) return false;
        long now = System.currentTimeMillis();
        dragLogCounter++;
        if (now - lastDragLogTime >= 250) {
            EditorLogger.mouseDrag(EditorLogger.TIMELINE, ctx.mouseX, ctx.mouseY,
                    draggingKeyframe != null ? "keyframe" : "clip",
                    xToTime(ctx.mouseX));
            lastDragLogTime = now;
        }
        String state = "dragging=" + (draggingKeyframe != null ? "kf" : draggingResizeLeft ? "resizeL" : draggingResizeRight ? "resizeR" : "clip");
        EditorLogger.mouseMove(EditorLogger.TIMELINE, ctx.mouseX, ctx.mouseY, "canvas", true, "dragCounter=" + dragLogCounter + " " + state);
        return true;
    }

    @Override
    public boolean mouseScrolled(UIContext ctx, double scroll) {
        if (!ctx.isMouseIn(x, y, w, h)) return false;
        float old = pixelsPerSecond;
        pixelsPerSecond = Math.max(10, pixelsPerSecond + (float) scroll * 10);
        scrollOffset = scrollOffset * (pixelsPerSecond / old);
        EditorLogger.mouseScroll(EditorLogger.TIMELINE, scroll, ctx.mouseX, ctx.mouseY, "canvas");
        EditorLogger.state(EditorLogger.TIMELINE, "pixelsPerSecond", old, pixelsPerSecond);
        return true;
    }

    @Override
    protected List<UIComponent> getChildren() { return children; }

    private static String fmt(float s) { int m = (int)(s / 60); return String.format("%d:%02d", m, (int)(s % 60)); }

    @FunctionalInterface
    public interface MoveKeyframeCallback {
        void accept(EditorKeyframe kf, EditorClip clip, float newLocalTime);
    }
}
