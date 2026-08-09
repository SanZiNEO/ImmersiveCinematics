package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 二维球相机操控控件（A1 / 组 5 改版）。
 * <p>
 * 布局（固定，不随脚本朝向旋转）：
 * <ul>
 *   <li>外圈 = roll（直拖外环只转 roll）</li>
 *   <li>内圈固定十字：横线（蓝）= yaw（左右环绕），竖线（绿）= pitch（上下）</li>
 *   <li>球内其他区域 = yaw+pitch 轨迹球；Shift = 0.1 倍精细；双击中心 = 重置为 0</li>
 * </ul>
 * 无选中 CAMERA clip 时 {@link #enabled} 为 false（半透明、不响应）。
 */
public class OrbitGizmo extends UIComponent {

    public static final int RADIUS = 28;   // 参考像素

    // 拖拽模式（单轴锁定）
    private static final int MODE_NONE  = 0;
    private static final int MODE_ORBIT = 1;   // 球内轨迹球（yaw+pitch）
    private static final int MODE_YAW   = 2;   // 横线（只转 yaw）
    private static final int MODE_PITCH = 3;   // 竖线（只转 pitch）
    private static final int MODE_ROLL  = 4;   // 外环

    private static final int AXIS_PITCH_COLOR = 0xFF3A8A3A;  // 绿 — pitch（竖线）
    private static final int AXIS_YAW_COLOR   = 0xFF3A6DB5;  // 蓝 — yaw（横线）
    private static final int AXIS_ROLL_COLOR  = 0xFF8A3A3A;  // 红 — roll 指示（中心点 + 外环段）
    /** 十字线命中像素距离 */
    private static final int CROSS_HIT_PX = 3;

    private int dragMode = MODE_NONE;
    public boolean enabled = true;

    private long lastClickTime;
    private double lastClickDist;
    private int downX, downY;

    private Runnable onDragStart;
    private BiConsumer<Float, Float> onDelta;      // yawDelta, pitchDelta（轨迹球）
    private Consumer<Float> onYawOnly;             // 横线（只转 yaw）
    private Consumer<Float> onPitchOnly;           // 竖线（只转 pitch）
    private Consumer<Float> onRollDelta;           // rollDelta（外环）
    private Runnable onReset;
    private Runnable onDragEnd;                    // 组 7：松手回调

    public OrbitGizmo(int x, int y, int w, int h) {
        super(x, y, w, h);
    }

    public int radius() {
        return (int) (RADIUS * com.immersivecinematics.immersive_cinematics.editor.Scale.sx);
    }

    public void setOnDragStart(Runnable r) { onDragStart = r; }
    public void setOnDelta(BiConsumer<Float, Float> r) { onDelta = r; }
    public void setOnYawOnly(Consumer<Float> r) { onYawOnly = r; }
    public void setOnPitchOnly(Consumer<Float> r) { onPitchOnly = r; }
    public void setOnRollDelta(Consumer<Float> r) { onRollDelta = r; }
    public void setOnReset(Runnable r) { onReset = r; }
    public void setOnDragEnd(Runnable r) { onDragEnd = r; }

    /** 沿方向 (dx, dy) 以 1px 步进画线（fill 矩形无法画斜线，逐点填充） */
    private static void drawLine(UIContext ctx, int cx, int cy, int len, double dx, double dy, int color, int halfThick) {
        for (int t = -len; t <= len; t++) {
            int px = cx + (int) Math.round(dx * t);
            int py = cy + (int) Math.round(dy * t);
            ctx.graphics.fill(px - halfThick, py - halfThick, px + halfThick + 1, py + halfThick + 1, color);
        }
    }

    @Override
    public void renderContent(UIContext ctx) {
        int cx = x + w / 2;
        int cy = y + h / 2;
        int r = radius();

        // 球环：8 段短线拼"八角环"（MC fill 无圆）
        int segLen = dragging() ? 7 : 5;
        int segColor = enabled ? 0x88FFFFFF : 0x33FFFFFF;
        if (dragging()) segColor = EditorTheme.lighten(EditorTheme.ACCENT, 0.2f);
        for (int i = 0; i < 8; i++) {
            double a0 = Math.toRadians(i * 45);
            double a1 = Math.toRadians((i + 1) * 45);
            double mid = (a0 + a1) / 2;
            // 以中点为中心画一条 5/7px 短线（线段方向为环切线）
            double tx = -Math.sin(mid);
            double ty = Math.cos(mid);
            int hx = (int) (tx * segLen / 2);
            int hy = (int) (ty * segLen / 2);
            int mx = (int) (cx + Math.cos(mid) * r);
            int my = (int) (cy + Math.sin(mid) * r);
            ctx.graphics.fill(Math.min(mx - hx, mx + hx), Math.min(my - hy, my + hy),
                    Math.max(mx - hx, mx + hx) + 1, Math.max(my - hy, my + hy) + 1, segColor);
        }

        // 固定十字（不随脚本朝向旋转）：
        // 横线（蓝）= yaw 环绕；竖线（绿）= pitch 上下
        if (enabled) {
            boolean yawActive = dragMode == MODE_YAW;
            boolean pitchActive = dragMode == MODE_PITCH;
            drawLine(ctx, cx, cy, r, 1, 0,
                    yawActive ? EditorTheme.lighten(AXIS_YAW_COLOR, 0.3f) : AXIS_YAW_COLOR,
                    yawActive ? 1 : 0);
            drawLine(ctx, cx, cy, r, 0, 1,
                    pitchActive ? EditorTheme.lighten(AXIS_PITCH_COLOR, 0.3f) : AXIS_PITCH_COLOR,
                    pitchActive ? 1 : 0);
        } else {
            // 禁用态保留旧十字
            ctx.graphics.fill(cx - 1, cy - r, cx + 1, cy + r, 0x44FFFFFF);
            ctx.graphics.fill(cx - r, cy - 1, cx + r, cy + 1, 0x44FFFFFF);
        }

        // roll 指示 — 红中心点 + 45° 方向外环红段（视线轴恒朝屏幕外）
        int centerColor = dragMode == MODE_ROLL ? EditorTheme.lighten(AXIS_ROLL_COLOR, 0.3f) : AXIS_ROLL_COLOR;
        ctx.graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, centerColor);
        double rm = Math.toRadians(45);
        int mx = (int) (cx + Math.cos(rm) * r);
        int my = (int) (cy + Math.sin(rm) * r);
        double rtx = -Math.sin(rm);
        double rty = Math.cos(rm);
        for (int t = -3; t <= 3; t++) {
            ctx.graphics.fill((int) (mx + rtx * t), (int) (my + rty * t),
                    (int) (mx + rtx * t) + 1, (int) (my + rty * t) + 1, centerColor);
        }
    }

    private boolean dragging() {
        return dragMode != MODE_NONE;
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        if (!enabled || !isHovered(ctx)) return false;
        int cx = x + w / 2;
        int cy = y + h / 2;
        double dist = Math.hypot(ctx.mouseX - cx, ctx.mouseY - cy);
        if (dist > radius() + 4) return false;

        long now = System.currentTimeMillis();
        if (now - lastClickTime < 300 && dist <= 12 && lastClickDist <= 12) {
            // 双击中心 → 重置
            if (onReset != null) onReset.run();
            lastClickTime = 0;
            return true;
        }
        lastClickTime = now;
        lastClickDist = dist;

        // 单轴锁定：先十字线（像素命中），再外环，最后球内轨迹球
        if (Math.abs(ctx.mouseY - cy) <= CROSS_HIT_PX) {
            dragMode = MODE_YAW;      // 横线 → 左右环绕
        } else if (Math.abs(ctx.mouseX - cx) <= CROSS_HIT_PX) {
            dragMode = MODE_PITCH;    // 竖线 → 上下
        } else if (dist > radius() * 0.55) {
            dragMode = MODE_ROLL;     // 外环
        } else {
            dragMode = MODE_ORBIT;    // 球内轨迹球
        }

        downX = ctx.mouseX;
        downY = ctx.mouseY;
        if (onDragStart != null) onDragStart.run();
        return true;
    }

    @Override
    protected boolean onDragged(UIContext ctx) {
        if (!dragging()) return false;
        float sens = ctx.isShiftDown() ? 0.04f : 0.4f;   // 度/像素
        switch (dragMode) {
            case MODE_ORBIT -> {
                if (onDelta != null) onDelta.accept((float) ctx.mouseDX * sens, (float) -ctx.mouseDY * sens);
            }
            case MODE_YAW -> {
                if (onYawOnly != null) onYawOnly.accept((float) ctx.mouseDX * sens);
            }
            case MODE_PITCH -> {
                if (onPitchOnly != null) onPitchOnly.accept((float) -ctx.mouseDY * sens);
            }
            case MODE_ROLL -> {
                if (onRollDelta != null) onRollDelta.accept((float) ctx.mouseDX * sens);
            }
            default -> { }
        }
        downX = ctx.mouseX;
        downY = ctx.mouseY;
        return true;
    }

    @Override
    protected boolean onReleased(UIContext ctx) {
        if (dragging() && onDragEnd != null) onDragEnd.run();
        dragMode = MODE_NONE;
        return false;
    }
}
