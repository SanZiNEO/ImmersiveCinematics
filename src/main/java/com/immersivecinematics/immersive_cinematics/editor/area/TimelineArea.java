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
    private final List<UIComponent> children = new ArrayList<>();
    private JsonObject script;
    private JsonObject selectedClip;
    private JsonObject selectedKeyframe;
    private boolean canAddKf;
    private float playheadTime;
    private float pixelsPerSecond = 60f;
    private float scrollOffset;
    private int verticalScroll;

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

    private Consumer<Float> onClickAtTime;
    private Consumer<JsonObject> onClickClip;
    private BiConsumer<JsonObject, JsonObject> onClickKeyframe;
    private Runnable onClickEmpty;
    private BiConsumer<JsonObject, Float> onMoveClip;
    private BiConsumer<JsonObject, Float> onResizeLeft;
    private BiConsumer<JsonObject, Float> onResizeRight;
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

    public void setData(JsonObject script, JsonObject selClip, JsonObject selKf,
                        boolean canAddKf) {
        this.script = script;
        this.selectedClip = selClip;
        this.selectedKeyframe = selKf;
        this.canAddKf = canAddKf;
    }
    public void setPlayheadTime(float t) { playheadTime = t; }
    public float getPlayheadTime() { return playheadTime; }

    public void setOnClickAtTime(Consumer<Float> r) { onClickAtTime = r; }
    public void setOnClickClip(Consumer<JsonObject> r) { onClickClip = r; }
    public void setOnClickKeyframe(BiConsumer<JsonObject, JsonObject> r) { onClickKeyframe = r; }
    public void setOnClickEmpty(Runnable r) { onClickEmpty = r; }
    public void setOnMoveClip(BiConsumer<JsonObject, Float> r) { onMoveClip = r; }
    public void setOnResizeLeft(BiConsumer<JsonObject, Float> r) { onResizeLeft = r; }
    public void setOnResizeRight(BiConsumer<JsonObject, Float> r) { onResizeRight = r; }
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

    private void clampScrollOffset() {
        float maxScroll = 0;
        float minScroll = Math.min(0, canvasW() - (totalDuration() + 30) * pixelsPerSecond);
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
    public void render(UIContext ctx) {
        int cx = canvasX();
        int cy = canvasY();
        int cw = canvasW();

        ctx.graphics.fill(x, y, x + w, y + h, 0xFF171717);
        ctx.graphics.renderOutline(x, y, w, h, 0xFF333333);
        ctx.graphics.fill(x, y, x + w, y + HEADER_H, 0xFF1F1F1F);

        drawRuler(ctx, cx, y, cw);
        drawToolbar(ctx);
        drawTracks(ctx, cx, cy);
        drawPlayhead(ctx, cx, cy, cw);

        for (UIComponent c : children) c.render(ctx);
    }

    private void drawRuler(UIContext ctx, int cx, int top, int cw) {
        float total = totalDuration();
        float visibleWidth = cw / pixelsPerSecond;
        float visibleStart = Math.max(0, -scrollOffset / pixelsPerSecond);
        float visibleEnd = visibleStart + visibleWidth;

        float interval;
        if (visibleWidth <= 5) interval = 10;
        else if (visibleWidth <= 15) interval = 5;
        else if (visibleWidth <= 60) interval = 1;
        else interval = (float) Math.pow(10, Math.ceil(Math.log10(visibleWidth)) - 1);

        float startTick = (float) Math.floor(visibleStart / interval) * interval;
        for (float t = startTick; t <= visibleEnd + interval; t += interval) {
            float sx = cx + t * pixelsPerSecond + scrollOffset;
            if (sx < cx - 20 || sx > cx + cw) continue;
            ctx.graphics.fill((int) sx, top, (int) sx + 1, top + HEADER_H, 0xFF3A3A3A);
            ctx.graphics.drawString(ctx.font, fmt(t), (int) sx + 2, top + 2, 0xFF777777);
        }
    }

    private void drawToolbar(UIContext ctx) {
        int bx = x + 3;
        int by = y + HEADER_H + 4;

        drawBtn(ctx, bx, by, "+C", 0xFF338833, 0xFF44AA44, true); by += BTN + BTN_GAP;
        drawBtn(ctx, bx, by, "-C", 0xFF883333, 0xFFAA4444, selectedClip != null); by += BTN + BTN_GAP + 4;
        drawBtn(ctx, bx, by, "+K", 0xFF333388, 0xFF4444AA, canAddKf); by += BTN + BTN_GAP;
        drawBtn(ctx, bx, by, "-K", 0xFF883366, 0xFFAA4488, selectedKeyframe != null); by += BTN + BTN_GAP + 4;
        drawBtn(ctx, bx, by, "\u00AB\u00BB", 0xFF336688, 0xFF4488AA, true);
    }

    private void drawBtn(UIContext ctx, int bx, int by, String label, int c, int hc, boolean active) {
        boolean hover = ctx.isMouseIn(bx, by, BTN, BTN);
        int bg = !active ? 0xFF222222 : hover ? hc : c;
        ctx.graphics.fill(bx, by, bx + BTN, by + BTN, bg);
        ctx.graphics.renderOutline(bx, by, BTN, BTN, active ? 0xFF666666 : 0xFF333333);
        int tw = ctx.font.width(label);
        ctx.graphics.drawString(ctx.font, label, bx + (BTN - tw) / 2, by + (BTN - 8) / 2, active ? 0xFFFFFFFF : 0xFF555555);
    }

    private void drawTracks(UIContext ctx, int cx, int cy) {
        JsonArray arr = tracks();
        if (arr == null) return;
        for (int ti = 0; ti < arr.size(); ti++) {
            JsonObject track = arr.get(ti).getAsJsonObject();
            int ty = cy + ti * TRACK_H;
            ctx.graphics.fill(x + TOOLBAR_W, ty, cx, ty + TRACK_H, 0xFF222222);
            String type = track.has("type") ? track.get("type").getAsString() : "TRACK";
            ctx.graphics.drawString(ctx.font, type.toUpperCase(), x + TOOLBAR_W + 4, ty + (TRACK_H - 8) / 2, 0xFF888888);
            JsonArray clips = track.getAsJsonArray("clips");
            for (JsonElement ce : clips) {
                drawClip(ctx, ce.getAsJsonObject(), ty);
            }
        }
    }

    private void drawClip(UIContext ctx, JsonObject clip, int ty) {
        float sx = timeToX(EditorOperations.getStart(clip));
        float ex = timeToX(EditorOperations.getEnd(clip));
        int cw = canvasW();
        int cx = canvasX();
        if (ex < cx || sx > cx + cw) return;
        int clipX = Math.max(cx, (int) sx);
        int clipW = Math.min(cx + cw, (int) ex) - clipX;
        if (clipW < 2) clipW = 2;

        boolean isSel = (clip == selectedClip);
        int fill = isSel ? 0xFF4A4F5A : 0xFF3A3F4A;
        if (ctx.isMouseIn(clipX, ty, clipW, TRACK_H)) fill = isSel ? 0xFF5A5F6A : 0xFF4A4F5A;
        ctx.graphics.fill(clipX, ty + 2, clipX + clipW, ty + TRACK_H - 2, fill);
        ctx.graphics.renderOutline(clipX, ty + 2, clipW, TRACK_H - 4, isSel ? 0xFF707580 : 0xFF505560);

        if (ctx.isMouseIn(clipX, ty + 2, RESIZE_MARGIN, TRACK_H - 4))
            ctx.graphics.fill(clipX, ty + 2, clipX + RESIZE_MARGIN, ty + TRACK_H - 2, 0x55FFFFFF);
        if (ctx.isMouseIn(clipX + clipW - RESIZE_MARGIN, ty + 2, RESIZE_MARGIN, TRACK_H - 4))
            ctx.graphics.fill(clipX + clipW - RESIZE_MARGIN, ty + 2, clipX + clipW, ty + TRACK_H - 2, 0x55FFFFFF);

        if (clipW > 30) {
            String label = fmt(EditorOperations.getStart(clip)) + "-" + fmt(EditorOperations.getEnd(clip));
            int lw = ctx.font.width(label);
            if (lw + 8 < clipW) ctx.graphics.drawString(ctx.font, label, clipX + 4, ty + (TRACK_H - 8) / 2, 0xFFCCCCCC);
        }

        JsonArray kfs = EditorOperations.keyframes(clip);
        if (kfs != null) {
            for (JsonElement ke : kfs) {
                JsonObject kf = ke.getAsJsonObject();
                float kx = timeToX(EditorOperations.getStart(clip) + kf.get("time").getAsFloat());
                if (kx >= clipX + 2 && kx <= clipX + clipW - 2) {
                    int kc = (kf == selectedKeyframe && clip == selectedClip) ? 0xFFFFFF88 : 0xFFAAAAAA;
                    ctx.graphics.fill((int) kx - 3, ty + TRACK_H / 2 - 3, (int) kx + 3, ty + TRACK_H / 2 + 3, kc);
                }
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
            boolean hasSel = selectedClip != null;
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "-C hasSelection=" + hasSel);
            if (hasSel && onToolDeleteClip != null) onToolDeleteClip.run(); return true;
        }
        by += BTN + BTN_GAP + 4;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) {
            EditorLogger.action(EditorLogger.TIMELINE, "TOOLBAR", "+K canAdd=" + canAddKf + " playhead=" + String.format("%.3f", playheadTime));
            if (canAddKf && onToolAddKeyframe != null) onToolAddKeyframe.run(); return true;
        }
        by += BTN + BTN_GAP;
        if (ctx.isMouseIn(bx, by, BTN, BTN)) {
            boolean hasKf = selectedKeyframe != null;
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
        JsonArray arr = tracks();
        if (arr == null || ctx.mouseX < canvasX()) return false;

        int trackIdx = (ctx.mouseY - canvasY()) / TRACK_H;
        if (trackIdx < 0 || trackIdx >= arr.size()) {
            EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas_empty", ctx.mouseX, ctx.mouseY, false);
            return false;
        }
        JsonArray clips = arr.get(trackIdx).getAsJsonObject().getAsJsonArray("clips");

        for (int i = clips.size() - 1; i >= 0; i--) {
            JsonObject clip = clips.get(i).getAsJsonObject();
            float sx = timeToX(EditorOperations.getStart(clip));
            float ex = timeToX(EditorOperations.getEnd(clip));
            if (ctx.mouseX < sx || ctx.mouseX > ex) continue;

            if (ctx.mouseX <= sx + RESIZE_MARGIN) {
                draggingResizeLeft = true; draggingClip = clip;
                dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "resizeLeft clip=" + EditorOperations.getStart(clip));
                return true;
            }
            if (ctx.mouseX >= ex - RESIZE_MARGIN) {
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
                        keyframeClip = clip;
                        draggingKeyframe = kf;
                        dragOffsetX = (int) (ctx.mouseX - kx);
                        dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
                        EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "keyframe time=" + kf.get("time").getAsFloat() + " clipStart=" + EditorOperations.getStart(clip));
                        if (onClickKeyframe != null) onClickKeyframe.accept(kf, clip);
                        return true;
                    }
                }
            }

            draggingClip = clip;
            dragOffsetX = (int) (ctx.mouseX - sx);
            dragStartTime = System.currentTimeMillis(); lastDragLogTime = dragStartTime; dragLogCounter = 0;
            EditorLogger.action(EditorLogger.TIMELINE, "DRAG_START", "moveClip start=" + EditorOperations.getStart(clip));
            if (onClickClip != null) onClickClip.accept(clip);
            return true;
        }
        EditorLogger.areaHit(EditorLogger.TIMELINE, "canvas_miss", ctx.mouseX, ctx.mouseY, false);
        if (onClickEmpty != null) onClickEmpty.run();
        return true;
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
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "resizeLeft clip=" + EditorOperations.getStart(draggingClip) + " to=" + newT + " moved=" + moved + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
                    if (onResizeLeft != null) onResizeLeft.accept(draggingClip, newT);
                } else {
                    JsonArray kfs = EditorOperations.keyframes(draggingClip);
                    if (kfs != null && kfs.size() > 0) {
                        JsonObject firstKf = kfs.get(0).getAsJsonObject();
                        EditorLogger.action(EditorLogger.TIMELINE, "CLICK_NO_DRAG", "resizeLeft_selectFirstKf time=" + firstKf.get("time").getAsFloat());
                        if (onClickKeyframe != null) onClickKeyframe.accept(firstKf, draggingClip);
                    }
                }
            } else if (draggingResizeRight) {
                if (moved) {
                    float newT = xToTime(ctx.mouseX);
                    EditorLogger.scrubSession(EditorLogger.TIMELINE, newT, ctx.mouseX, "end_resizeRight");
                    EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "resizeRight clip=" + EditorOperations.getEnd(draggingClip) + " to=" + newT + " moved=" + moved + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
                    if (onResizeRight != null) onResizeRight.accept(draggingClip, newT);
                } else {
                    JsonArray kfs = EditorOperations.keyframes(draggingClip);
                    if (kfs != null && kfs.size() > 0) {
                        JsonObject lastKf = kfs.get(kfs.size() - 1).getAsJsonObject();
                        EditorLogger.action(EditorLogger.TIMELINE, "CLICK_NO_DRAG", "resizeRight_selectLastKf time=" + lastKf.get("time").getAsFloat());
                        if (onClickKeyframe != null) onClickKeyframe.accept(lastKf, draggingClip);
                    }
                }
            } else if (moved && onMoveClip != null) {
                float newT = xToTime(ctx.mouseX - dragOffsetX);
                EditorLogger.scrubSession(EditorLogger.TIMELINE, newT, ctx.mouseX, "end_moveClip");
                EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "moveClip clip=" + EditorOperations.getStart(draggingClip) + " to=" + newT + " moved=" + moved + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
                onMoveClip.accept(draggingClip, newT);
            } else {
                EditorLogger.action(EditorLogger.TIMELINE, "CLICK_NO_DRAG", "clip select=" + EditorOperations.getStart(draggingClip) + " moved=" + moved);
            }
        }
        if (draggingKeyframe != null && onMoveKeyframe != null && moved) {
            float newLocal = xToTime(ctx.mouseX - dragOffsetX) - EditorOperations.getStart(keyframeClip);
            EditorLogger.scrubSession(EditorLogger.TIMELINE, newLocal, ctx.mouseX, "end_moveKeyframe");
            EditorLogger.action(EditorLogger.TIMELINE, "DRAG_END", "moveKeyframe kf=" + draggingKeyframe.get("time").getAsFloat() + " to=" + newLocal + " duration=" + dragDuration + "ms dragCalls=" + dragLogCounter);
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

        boolean ctrl = ctx.isCtrlDown();
        boolean shift = ctx.isShiftDown();

        if (ctrl) {
            float old = pixelsPerSecond;
            pixelsPerSecond = Math.max(10, pixelsPerSecond + (float) scroll * 10);
            float ratio = pixelsPerSecond / old;
            scrollOffset = scrollOffset * ratio;
            clampScrollOffset();
            EditorLogger.state(EditorLogger.TIMELINE, "pixelsPerSecond", old, pixelsPerSecond);
        } else if (shift) {
            float old = scrollOffset;
            scrollOffset += (float) scroll * 30;
            clampScrollOffset();
            EditorLogger.state(EditorLogger.TIMELINE, "scrollOffset", old, scrollOffset);
        } else {
            verticalScroll += (int) scroll * 20;
        }

        EditorLogger.mouseScroll(EditorLogger.TIMELINE, scroll, ctx.mouseX, ctx.mouseY,
                ctrl ? "zoom" : shift ? "h_scroll" : "v_scroll");
        return true;
    }

    @Override
    protected List<UIComponent> getChildren() { return children; }

    private static String fmt(float s) {
        int m = (int)(s / 60);
        return String.format("%d:%02d", m, (int)(s % 60));
    }

    @FunctionalInterface
    public interface MoveKeyframeCallback {
        void accept(JsonObject kf, JsonObject clip, float newLocalTime);
    }
}
