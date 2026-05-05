package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.model.*;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TimelineArea extends UIComponent {
    private final List<UIComponent> children = new ArrayList<>();
    private EditorScript script;
    private float playheadTime;
    private float pixelsPerSecond = 60f;
    private float scrollOffset;

    private EditorClip draggingClip;
    private int dragOffsetX;
    private boolean draggingPlayhead;

    private Consumer<EditorClip> onClipSelected;
    private Consumer<EditorKeyframe> onKeyframeSelected;
    private Consumer<Float> onPlayheadChanged;
    private Runnable onClipChanged;

    private static final int TRACK_LABEL_WIDTH = 80;
    private static final int HEADER_HEIGHT = 20;
    private static final int TRACK_HEIGHT = 28;
    private static final int PLAYHEAD_WIDTH = 2;

    public TimelineArea(int x, int y, int w, int h) {
        super(x, y, w, h);
    }

    public void setScript(EditorScript s) { script = s; }
    public void setPlayheadTime(float t) { playheadTime = t; }
    public void setPixelsPerSecond(float pps) { pixelsPerSecond = Math.max(10, pps); }
    public void setScrollOffset(float s) { scrollOffset = Math.min(0, s); }

    public void setOnClipSelected(Consumer<EditorClip> r) { onClipSelected = r; }
    public void setOnKeyframeSelected(Consumer<EditorKeyframe> r) { onKeyframeSelected = r; }
    public void setOnPlayheadChanged(Consumer<Float> r) { onPlayheadChanged = r; }
    public void setOnClipChanged(Runnable r) { onClipChanged = r; }

    public int canvasX() { return x + TRACK_LABEL_WIDTH; }
    public int canvasY() { return y + HEADER_HEIGHT; }
    public int canvasW() { return w - TRACK_LABEL_WIDTH; }
    public float timeToX(float t) { return canvasX() + (t * pixelsPerSecond) + scrollOffset; }
    public float xToTime(float px) { return (px - canvasX() - scrollOffset) / pixelsPerSecond; }

    @Override
    public void render(UIContext ctx) {
        int cx = canvasX();
        int cy = canvasY();
        int cw = canvasW();

        ctx.graphics.fill(x, y, x + w, y + h, 0xFF171717);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
        ctx.graphics.fill(x, y, x + w, y + HEADER_HEIGHT, 0xFF1F1F1F);

        drawRuler(ctx, cx, y, cw);

        if (script != null) {
            for (int ti = 0; ti < script.tracks.size(); ti++) {
                EditorTrack track = script.tracks.get(ti);
                int ty = cy + ti * TRACK_HEIGHT;

                ctx.graphics.fill(x, ty, cx, ty + TRACK_HEIGHT, 0xFF222222);
                ctx.graphics.drawString(ctx.font, track.type.toUpperCase(), x + 4, ty + (TRACK_HEIGHT - 8) / 2, 0xFF888888);

                for (EditorClip clip : track.clips) {
                    drawClip(ctx, clip, ti, ty);
                }
            }
        }

        float px = timeToX(playheadTime);
        if (px >= cx && px <= cx + cw) {
            ctx.graphics.fill((int) px, cy, (int) px + PLAYHEAD_WIDTH, y + h, 0xFFFF3333);
        }

        for (UIComponent c : children) {
            c.render(ctx);
        }
    }

    private void drawRuler(UIContext ctx, int cx, int top, int cw) {
        int majorInterval = pixelsPerSecond < 20 ? 10 : pixelsPerSecond < 40 ? 5 : 1;
        for (float t = 0; t * pixelsPerSecond + scrollOffset < cw + 200; t += majorInterval) {
            float sx = cx + t * pixelsPerSecond + scrollOffset;
            if (sx < cx - 20) continue;
            if (sx > cx + cw) break;

            ctx.graphics.fill((int) sx, top, (int) sx + 1, top + HEADER_HEIGHT, 0xFF3A3A3A);
            ctx.graphics.drawString(ctx.font, formatTime(t), (int) sx + 2, top + 2, 0xFF777777);
        }
    }

    private void drawClip(UIContext ctx, EditorClip clip, int trackIndex, int ty) {
        float sx = timeToX(clip.startTime);
        float ex = timeToX(clip.endTime());
        int cw = canvasW();
        int cx = canvasX();

        if (ex < cx || sx > cx + cw) return;

        int clipX = Math.max(cx, (int) sx);
        int clipW = Math.min(cx + cw, (int) ex) - clipX;
        if (clipW < 2) clipW = 2;

        int clipColor = 0xFF3A3F4A;
        if (ctx.isMouseIn(clipX, ty, clipW, TRACK_HEIGHT)) {
            clipColor = 0xFF4A4F5A;
        }

        ctx.graphics.fill(clipX, ty + 2, clipX + clipW, ty + TRACK_HEIGHT - 2, clipColor);
        ctx.graphics.renderOutline(clipX, ty + 2, clipW, TRACK_HEIGHT - 4, 0xFF505560);

        if (clipW > 40) {
            String label = String.format("%.1f-%.1f", clip.startTime, clip.endTime());
            ctx.graphics.drawString(ctx.font, label, clipX + 4, ty + (TRACK_HEIGHT - 8) / 2, 0xFFDDDDDD);
        }

        for (EditorKeyframe kf : clip.keyframes) {
            float kx = timeToX(clip.startTime + kf.time);
            if (kx >= clipX + 2 && kx <= clipX + clipW - 2) {
                ctx.graphics.fill((int) kx - 3, ty + TRACK_HEIGHT / 2 - 3, (int) kx + 3, ty + TRACK_HEIGHT / 2 + 3, 0xFFAAAAAA);
            }
        }
    }

    @Override
    public boolean mouseClicked(UIContext ctx) {
        if (!ctx.isMouseIn(x, y, w, h)) return false;

        int cyMouse = ctx.mouseY - canvasY();
        if (cyMouse < 0) {
            float t = xToTime(ctx.mouseX);
            if (t >= 0 && onPlayheadChanged != null) {
                onPlayheadChanged.accept(Math.max(0, t));
            }
            draggingPlayhead = true;
            return true;
        }

        int trackIndex = cyMouse / TRACK_HEIGHT;
        if (script != null && trackIndex < script.tracks.size()) {
            EditorTrack track = script.tracks.get(trackIndex);

            for (int i = track.clips.size() - 1; i >= 0; i--) {
                EditorClip clip = track.clips.get(i);
                float sx = timeToX(clip.startTime);
                float ex = timeToX(clip.endTime());
                if (ctx.mouseX >= sx && ctx.mouseX <= ex) {
                    for (int j = clip.keyframes.size() - 1; j >= 0; j--) {
                        EditorKeyframe kf = clip.keyframes.get(j);
                        float kx = timeToX(clip.startTime + kf.time);
                        if (Math.abs(ctx.mouseX - kx) <= 5) {
                            if (onKeyframeSelected != null) onKeyframeSelected.accept(kf);
                            return true;
                        }
                    }

                    if (onClipSelected != null) onClipSelected.accept(clip);
                    draggingClip = clip;
                    dragOffsetX = (int) (ctx.mouseX - sx);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(UIContext ctx) {
        draggingClip = null;
        draggingPlayhead = false;
        return false;
    }

    @Override
    public boolean mouseScrolled(UIContext ctx, double scroll) {
        if (ctx.isMouseIn(x, y, w, h)) {
            float oldPPS = pixelsPerSecond;
            pixelsPerSecond = Math.max(10, pixelsPerSecond + (float) scroll * 10);
            scrollOffset = scrollOffset * (pixelsPerSecond / oldPPS);
            return true;
        }
        return false;
    }

    public void updateDrag(UIContext ctx) {
        if (draggingClip != null) {
            float newStart = xToTime(ctx.mouseX - dragOffsetX);
            newStart = Math.max(0, Math.round(newStart * 2) / 2f);
            if (Math.abs(newStart - draggingClip.startTime) > 0.01f) {
                draggingClip.startTime = newStart;
                if (onClipChanged != null) onClipChanged.run();
            }
        }
    }

    @Override
    protected List<UIComponent> getChildren() {
        return children;
    }

    private static String formatTime(float seconds) {
        int m = (int) (seconds / 60);
        int s = (int) (seconds % 60);
        return String.format("%d:%02d", m, s);
    }
}
