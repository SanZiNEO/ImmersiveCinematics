package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.EditorCore;
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

    private boolean draggingPlayhead;
    private EditorClip draggingClip;
    private EditorKeyframe draggingKeyframe;
    private EditorClip keyframeClip;
    private int dragOffsetX;
    private int mouseDownX;
    private int mouseDownY;
    private boolean draggingResizeLeft;
    private boolean draggingResizeRight;

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
    }

    public void setCore(EditorCore c) { core = c; }
    public void setPlayheadTime(float t) { playheadTime = t; }

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

        drawBtn(ctx, bx, by, "+C", 0xFF338833, 0xFF44AA44, true); by += BTN + BTN_GAP;
        drawBtn(ctx, bx, by, "-C", 0xFF883333, 0xFFAA4444, selClip != null); by += BTN + BTN_GAP + 4;
        drawBtn(ctx, bx, by, "+K", 0xFF333388, 0xFF4444AA, canAddKf); by += BTN + BTN_GAP;
        drawBtn(ctx, bx, by, "-K", 0xFF883366, 0xFFAA4488, selKf != null);
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
        if (!ctx.isMouseIn(x, y, w, h)) return false;
        mouseDownX = ctx.mouseX;
        mouseDownY = ctx.mouseY;

        if (ctx.mouseX < x + TOOLBAR_W && ctx.mouseY >= y + HEADER_H) return clickToolbar(ctx);

        if (ctx.mouseY < canvasY() && ctx.mouseX >= x + TOOLBAR_W) {
            float t = xToTime(ctx.mouseX);
            if (t >= 0 && onClickAtTime != null) onClickAtTime.accept(Math.max(0, t));
            draggingPlayhead = true;
            return true;
        }

        return clickCanvas(ctx);
    }

    private boolean clickToolbar(UIContext ctx) {
        int bx = x + 3;
        int by = y + HEADER_H + 4;

        if (ctx.isMouseIn(bx, by, BTN, BTN)) { if (onToolAddClip != null) onToolAddClip.run(); return true; }
        by += BTN + BTN_GAP;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) { if (core != null && core.getSelectedClip() != null && onToolDeleteClip != null) onToolDeleteClip.run(); return true; }
        by += BTN + BTN_GAP + 4;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) { if (core != null && core.canAddKeyframeAt(playheadTime) && onToolAddKeyframe != null) onToolAddKeyframe.run(); return true; }
        by += BTN + BTN_GAP;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) { if (core != null && core.getSelectedKeyframe() != null && onToolDeleteKeyframe != null) onToolDeleteKeyframe.run(); return true; }
        return false;
    }

    private boolean clickCanvas(UIContext ctx) {
        EditorScript script = core != null ? core.getScript() : null;
        if (script == null || ctx.mouseX < canvasX()) return false;

        int trackIdx = (ctx.mouseY - canvasY()) / TRACK_H;
        if (trackIdx < 0 || trackIdx >= script.tracks.size()) return false;
        EditorTrack track = script.tracks.get(trackIdx);

        for (int i = track.clips.size() - 1; i >= 0; i--) {
            EditorClip clip = track.clips.get(i);
            float sx = timeToX(clip.startTime);
            float ex = timeToX(clip.endTime());
            if (ctx.mouseX < sx || ctx.mouseX > ex) continue;

            if (ctx.mouseX <= sx + RESIZE_MARGIN) {
                draggingResizeLeft = true; draggingClip = clip; return true;
            }
            if (ctx.mouseX >= ex - RESIZE_MARGIN) {
                draggingResizeRight = true; draggingClip = clip; return true;
            }

            for (int j = clip.keyframes.size() - 1; j >= 0; j--) {
                EditorKeyframe kf = clip.keyframes.get(j);
                float kx = timeToX(clip.startTime + kf.time);
                if (Math.abs(ctx.mouseX - kx) <= 5) {
                    keyframeClip = clip;
                    draggingKeyframe = kf;
                    dragOffsetX = (int) (ctx.mouseX - kx);
                    if (onClickKeyframe != null) onClickKeyframe.accept(kf, clip);
                    return true;
                }
            }

            draggingClip = clip;
            dragOffsetX = (int) (ctx.mouseX - sx);
            if (onClickClip != null) onClickClip.accept(clip);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(UIContext ctx) {
        boolean moved = Math.abs(ctx.mouseX - mouseDownX) > 2 || Math.abs(ctx.mouseY - mouseDownY) > 2;

        if (draggingClip != null) {
            if (draggingResizeLeft) {
                if (moved) {
                    if (onResizeLeft != null) onResizeLeft.accept(draggingClip, xToTime(ctx.mouseX));
                } else if (!draggingClip.keyframes.isEmpty()) {
                    EditorKeyframe firstKf = draggingClip.keyframes.get(0);
                    if (onClickKeyframe != null) onClickKeyframe.accept(firstKf, draggingClip);
                }
            } else if (draggingResizeRight) {
                if (moved) {
                    if (onResizeRight != null) onResizeRight.accept(draggingClip, xToTime(ctx.mouseX));
                } else if (!draggingClip.keyframes.isEmpty()) {
                    EditorKeyframe lastKf = draggingClip.keyframes.get(draggingClip.keyframes.size() - 1);
                    if (onClickKeyframe != null) onClickKeyframe.accept(lastKf, draggingClip);
                }
            } else if (moved && onMoveClip != null) {
                onMoveClip.accept(draggingClip, xToTime(ctx.mouseX - dragOffsetX));
            }
        }
        if (draggingKeyframe != null && onMoveKeyframe != null && moved) {
            float newLocal = xToTime(ctx.mouseX - dragOffsetX) - keyframeClip.startTime;
            onMoveKeyframe.accept(draggingKeyframe, keyframeClip, newLocal);
        }
        draggingClip = null;
        draggingKeyframe = null;
        keyframeClip = null;
        draggingPlayhead = false;
        draggingResizeLeft = false;
        draggingResizeRight = false;
        return false;
    }

    @Override
    public boolean mouseScrolled(UIContext ctx, double scroll) {
        if (!ctx.isMouseIn(x, y, w, h)) return false;
        float old = pixelsPerSecond;
        pixelsPerSecond = Math.max(10, pixelsPerSecond + (float) scroll * 10);
        scrollOffset = scrollOffset * (pixelsPerSecond / old);
        return true;
    }

    public void updatePlayheadDrag(UIContext ctx) {
        if (draggingPlayhead && onClickAtTime != null)
            onClickAtTime.accept(Math.max(0, xToTime(ctx.mouseX)));
    }

    @Override
    protected List<UIComponent> getChildren() { return children; }

    private static String fmt(float s) { int m = (int)(s / 60); return String.format("%d:%02d", m, (int)(s % 60)); }

    @FunctionalInterface
    public interface MoveKeyframeCallback {
        void accept(EditorKeyframe kf, EditorClip clip, float newLocalTime);
    }
}
