package com.immersivecinematics.immersive_cinematics.editor.area;

import com.immersivecinematics.immersive_cinematics.editor.EditorTheme;
import com.immersivecinematics.immersive_cinematics.editor.PreviewCapture;
import com.immersivecinematics.immersive_cinematics.editor.debug.EditorLogger;
import com.immersivecinematics.immersive_cinematics.editor.widget.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.renderer.GameRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class PreviewArea extends UIComponent {
    private final UIButton playBtn;
    private final UIButton pauseBtn;
    private final UIButton stopBtn;
    private final UILabel timeLabel;

    private float currentTime;

    // A2：相机快速控制 — 三维球 + 迷你参数滑块
    private final OrbitGizmo orbitGizmo;
    private static final String[] SLIDER_KEYS = {"yaw", "pitch", "roll", "fov", "zoom"};
    private final float[] sliderValues = new float[5];
    private int sliderDragIndex = -1;
    private int sliderDragOffsetX;
    /** 按下时当前值的比例（拖拽基准：从当前值相对增减，不再从 0 起算 → 修复往左拖瞬移归零） */
    private float sliderDragStartRatio;
    private boolean slidersEnabled;
    private int sliderHoverIndex = -1;
    private Runnable onSliderDragStart;
    private Runnable onSliderDragEnd;
    private BiConsumer<String, Float> onSliderChanged;

    public PreviewArea(int x, int y, int w, int h) {
        super(x, y, w, h);
        EditorLogger.areaRegister(EditorLogger.PREVIEW, "full_area", x, y, w, h);

        float sx = com.immersivecinematics.immersive_cinematics.editor.Scale.sx;
        float sy = com.immersivecinematics.immersive_cinematics.editor.Scale.sy;
        int barW = (int)(160 * sx);
        int barH = (int)(24 * sy);
        int barX = x + (w - barW) / 2;
        int barY = y + h - barH - (int)(8 * sy);

        int btnW = (int)(36 * sx);
        int btnGap = (int)(40 * sx);
        playBtn = new UIButton(barX, barY, btnW, barH, "\u25B6", b -> {});
        playBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
        pauseBtn = new UIButton(barX + btnGap, barY, btnW, barH, "\u23F8", b -> {});
        pauseBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
        stopBtn = new UIButton(barX + btnGap * 2, barY, btnW, barH, "\u25A0", b -> {});
        stopBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
        timeLabel = new UILabel(barX + btnGap * 3 + (int)(8 * sx), barY + (int)(7 * sy), "00:00.000", EditorTheme.TEXT_MUTED);

        addChild(playBtn);
        addChild(pauseBtn);
        addChild(stopBtn);
        addChild(timeLabel);

        // A2：三维球（位置由 renderContent 每帧 setBounds）
        orbitGizmo = new OrbitGizmo(0, 0, 0, 0);
        orbitGizmo.visible = false;
        addChild(orbitGizmo);
    }

    public void setCurrentTime(float t) {
        this.currentTime = t;
        if (timeLabel != null) timeLabel.setText(formatTimecode(t));
    }

    /** MM:SS.mmm（超过 1 小时分钟位自然累加，如 "125:30.000"） */
    private static String formatTimecode(float s) {
        int m = (int)(s / 60);
        float sec = s % 60;
        return String.format("%02d:%06.3f", m, sec);
    }

    public void setOnPlay(Runnable r) { playBtn.setOnClick(b -> r.run()); }
    public void setOnPause(Runnable r) { pauseBtn.setOnClick(b -> r.run()); }
    public void setOnStop(Runnable r) { stopBtn.setOnClick(b -> r.run()); }

    /** E5：播放状态按钮高亮（播放→绿色 playBtn，暂停→黄色 pauseBtn，停止→全部复位） */
    public void setPlayingState(boolean playing, boolean paused) {
        if (playing) {
            playBtn.color(0xFF2A5A2A, 0xFF3A7A3A);
            pauseBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
        } else if (paused) {
            playBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
            pauseBtn.color(0xFF5A5A2A, 0xFF7A7A3A);
        } else {
            playBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
            pauseBtn.color(EditorTheme.BG_WIDGET, EditorTheme.BG_HOVER);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  A2：相机快速控制（三维球 + 迷你参数滑块）
    // ══════════════════════════════════════════════════════════════

    public void setCameraControlEnabled(boolean enabled) {
        slidersEnabled = enabled;
        orbitGizmo.enabled = enabled;
    }

    public void setCameraValues(float yaw, float pitch, float roll, float fov, float zoom) {
        sliderValues[0] = yaw;
        sliderValues[1] = pitch;
        sliderValues[2] = roll;
        sliderValues[3] = fov;
        sliderValues[4] = zoom;
    }

    public void setOnSliderDragStart(Runnable r) { onSliderDragStart = r; }
    public void setOnSliderDragEnd(Runnable r) { onSliderDragEnd = r; }
    public void setOnSliderChanged(BiConsumer<String, Float> r) { onSliderChanged = r; }
    public void setOnGizmoDragStart(Runnable r) { orbitGizmo.setOnDragStart(r); }
    public void setOnGizmoDragEnd(Runnable r) { orbitGizmo.setOnDragEnd(r); }
    public void setOnGizmoDelta(BiConsumer<Float, Float> r) { orbitGizmo.setOnDelta(r); }
    public void setOnGizmoYawOnly(Consumer<Float> r) { orbitGizmo.setOnYawOnly(r); }
    public void setOnGizmoPitchOnly(Consumer<Float> r) { orbitGizmo.setOnPitchOnly(r); }
    public void setOnGizmoRollDelta(Consumer<Float> r) { orbitGizmo.setOnRollDelta(r); }
    public void setOnGizmoReset(Runnable r) { orbitGizmo.setOnReset(r); }

    /** 预览矩形计算（renderContent 与鼠标事件共用） */
    private int[] previewRect() {
        int previewH = h - 40;
        int previewW = (int) (previewH * 16f / 9f);
        if (previewW > w - 16) {
            previewW = w - 16;
            previewH = (int) (previewW * 9f / 16f);
        }
        int px = x + (w - previewW) / 2;
        int py = y + 8;
        return new int[]{px, py, previewW, previewH};
    }

    private static float sliderStepFor(int i) {
        return switch (i) {
            case 3 -> 1f;      // fov
            case 4 -> 0.05f;   // zoom
            default -> 1f;     // yaw/pitch/roll（度）
        };
    }

    /** 滑块位置 → 参数值 */
    private static float sliderValueFor(int i, float ratio) {
        return switch (i) {
            case 0 -> -180f + ratio * 360f;   // yaw
            case 1 -> -90f + ratio * 180f;    // pitch
            case 2 -> -180f + ratio * 360f;   // roll
            case 3 -> 30f + ratio * 80f;      // fov
            default -> 0.1f + ratio * 4.9f;   // zoom
        };
    }

    /** 参数值 → 滑块比例（clamp 0..1） */
    private static float sliderRatioFor(int i, float value) {
        float ratio = switch (i) {
            case 0 -> (value + 180f) / 360f;   // yaw
            case 1 -> (value + 90f) / 180f;    // pitch
            case 2 -> (value + 180f) / 360f;   // roll
            case 3 -> (value - 30f) / 80f;     // fov
            default -> (value - 0.1f) / 4.9f;  // zoom
        };
        return Math.max(0f, Math.min(1f, ratio));
    }

    private static float clampSliderValue(int i, float v) {
        return switch (i) {
            case 0, 2 -> Math.max(-180f, Math.min(180f, v));
            case 1 -> Math.max(-90f, Math.min(90f, v));
            case 3 -> Math.max(30f, Math.min(110f, v));
            default -> Math.max(0.1f, Math.min(5f, v));
        };
    }

    private void drawCameraControls(UIContext ctx, int px, int py, int previewW, int previewH) {
        if (previewW < 360) {
            orbitGizmo.visible = false;
            sliderHoverIndex = -1;
            return;
        }

        // 滑块行
        int sy = py + previewH - 18;
        int sx0 = px + (previewW - 344) / 2;
        for (int i = 0; i < SLIDER_KEYS.length; i++) {
            int sx = sx0 + i * 74;
            float ratio = sliderRatioFor(i, sliderValues[i]);
            boolean active = slidersEnabled;
            boolean hover = active && ctx.isMouseIn(sx, sy, 64, 10);
            ctx.graphics.fill(sx, sy + 4, sx + 64, sy + 6, 0x33FFFFFF);
            int fillColor = hover || sliderDragIndex == i ? 0xAAFFFFFF : 0x66FFFFFF;
            if (active) ctx.graphics.fill(sx, sy + 4, sx + (int) (64 * ratio), sy + 6, fillColor);
            // 原版式把手（竖块）：位于值比例处，x + ratio*(width-8)，8px 宽（对齐 AbstractSliderButton 视觉）
            if (active) {
                int handleX = sx + (int) (ratio * (64 - 8));
                ctx.graphics.fill(handleX, sy, handleX + 8, sy + 10,
                        hover || sliderDragIndex == i ? 0xFFFFFFFF : 0xCCFFFFFF);
            }
            ctx.graphics.drawString(ctx.font, SLIDER_KEYS[i].substring(0, 1), sx + 1, sy - 7,
                    active ? 0x66FFFFFF : 0x33FFFFFF);
        }

        // 三维球（预览区右下角）
        int r = orbitGizmo.radius();
        orbitGizmo.setBounds(px + previewW - r - 10, py + previewH - r - 30, r * 2, r * 2);
        orbitGizmo.visible = true;

        // hover 状态更新（拖拽中固定为拖拽滑块）
        if (sliderDragIndex >= 0) {
            sliderHoverIndex = sliderDragIndex;
        } else {
            sliderHoverIndex = -1;
            for (int i = 0; i < SLIDER_KEYS.length; i++) {
                int sx = sx0 + i * 74;
                if (slidersEnabled && ctx.isMouseIn(sx, sy, 64, 10)) {
                    sliderHoverIndex = i;
                    break;
                }
            }
        }
    }

    @Override
    public void renderContent(UIContext ctx) {
        ctx.graphics.fill(x, y, x + w, y + h, EditorTheme.BG_PREVIEW);
        ctx.graphics.renderOutline(x, y, w, h, EditorTheme.BORDER);

        // 预览区域（16:9 保持宽高比，居中显示）
        int[] pr = previewRect();
        int px = pr[0];
        int py = pr[1];
        int previewW = pr[2];
        int previewH = pr[3];

        ctx.graphics.fill(px, py, px + previewW, py + previewH, EditorTheme.BG_MAIN);
        ctx.graphics.renderOutline(px, py, previewW, previewH, EditorTheme.BORDER_LIGHT);

        int texId = PreviewCapture.getTextureId();
        if (texId >= 0) {
            // 有捕获纹理：绘制带 UV 映射的四边形
            int capW = PreviewCapture.getWidth();
            int capH = PreviewCapture.getHeight();
            float srcAspect = (float) capW / capH;
            float dstAspect = (float) previewW / previewH;
            int rx, ry, rw, rh;
            if (srcAspect > dstAspect) {
                rw = previewW;
                rh = (int) (previewW / srcAspect);
                rx = px;
                ry = py + (previewH - rh) / 2;
            } else {
                rh = previewH;
                rw = (int) (previewH * srcAspect);
                rx = px + (previewW - rw) / 2;
                ry = py;
            }

            RenderSystem.setShaderTexture(0, texId);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            var pose = ctx.graphics.pose();
            pose.pushPose();
            var builder = new BufferBuilder(256);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.vertex(rx, ry + rh, 0).uv(0, 0).endVertex();
            builder.vertex(rx + rw, ry + rh, 0).uv(1, 0).endVertex();
            builder.vertex(rx + rw, ry, 0).uv(1, 1).endVertex();
            builder.vertex(rx, ry, 0).uv(0, 1).endVertex();
            BufferUploader.drawWithShader(builder.end());
            pose.popPose();
            RenderSystem.setShaderTexture(0, 0);
        } else {
            // 无捕获纹理：显示占位文字
            String msg = I18n.get("editor.menu.no_preview");
            int tw = ctx.font.width(msg);
            ctx.graphics.drawString(ctx.font, msg, px + (previewW - tw) / 2, py + previewH / 2 - 4, EditorTheme.TEXT_DISABLED);
        }

        // A2：相机快速控制（滑块行 + 三维球）
        drawCameraControls(ctx, px, py, previewW, previewH);
    }

    @Override
    protected boolean onClicked(UIContext ctx) {
        // 滑块命中（先于 super 子控件分发；滑块区不在任何子控件 bounds 内）
        // 交互：按下不改值（保持当前值），拖动从当前值相对增减（修复旧版"按下点=0 → 往左拖归零"）。
        if (slidersEnabled) {
            int[] pr = previewRect();
            if (pr[2] >= 360) {
                int sy = pr[1] + pr[3] - 18;
                int sx0 = pr[0] + (pr[2] - 344) / 2;
                for (int i = 0; i < SLIDER_KEYS.length; i++) {
                    int sx = sx0 + i * 74;
                    if (ctx.isMouseIn(sx, sy, 64, 10)) {
                        sliderDragIndex = i;
                        sliderDragOffsetX = ctx.mouseX - sx;
                        sliderDragStartRatio = sliderRatioFor(i, sliderValues[i]);
                        if (onSliderDragStart != null) onSliderDragStart.run();
                        return true;
                    }
                }
            }
        }
        return super.onClicked(ctx);
    }

    @Override
    protected boolean onDragged(UIContext ctx) {
        if (sliderDragIndex >= 0) {
            int[] pr = previewRect();
            if (pr[2] >= 360) {
                int sx0 = pr[0] + (pr[2] - 344) / 2;
                int sx = sx0 + sliderDragIndex * 74;
                // 细腻拖动：角度类 0.4°/px（与三维球一致），zoom 保持原比例——不再 64px=全范围（yaw 曾达 5.6°/px）
                float sens = switch (sliderDragIndex) {
                    case 0, 2 -> 0.4f / 360f;   // yaw/roll
                    case 1 -> 0.4f / 180f;      // pitch
                    case 3 -> 0.4f / 80f;       // fov
                    default -> 1f / 64f;        // zoom
                };
                float ratio = sliderDragStartRatio + (ctx.mouseX - sx - sliderDragOffsetX) * sens;
                ratio = Math.max(0f, Math.min(1f, ratio));
                if (onSliderChanged != null) {
                    onSliderChanged.accept(SLIDER_KEYS[sliderDragIndex], sliderValueFor(sliderDragIndex, ratio));
                }
            }
            return true;
        }
        return super.onDragged(ctx);
    }

    @Override
    protected boolean onScrolled(UIContext ctx, double scroll) {
        if (slidersEnabled && sliderHoverIndex >= 0) {
            float step = sliderStepFor(sliderHoverIndex);
            if (ctx.isShiftDown()) step *= 5;
            float cur = sliderValues[sliderHoverIndex];
            float val = clampSliderValue(sliderHoverIndex, cur + (scroll > 0 ? step : -step));
            if (onSliderChanged != null) onSliderChanged.accept(SLIDER_KEYS[sliderHoverIndex], val);
            return true;
        }
        return super.onScrolled(ctx, scroll);
    }

    @Override
    protected boolean onReleased(UIContext ctx) {
        if (sliderDragIndex >= 0 && onSliderDragEnd != null) onSliderDragEnd.run();
        sliderDragIndex = -1;
        return super.onReleased(ctx);
    }
}
