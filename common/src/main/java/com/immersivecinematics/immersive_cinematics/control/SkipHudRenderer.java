package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 跳过提示 HUD：画跳过键图标与长按进度环，仅脚本模式且顶层实例可跳过时出现。
 *
 * 长按块（环形 + 键位图标 + 文案）与票数文本是两个独立元素，各自按「元素中心的屏幕百分比」
 * 定位（{@code Config.skipHudX/skipHudY} 与 {@code Config.voteHudX/voteHudY}），缺省落在同一行：
 * 长按块靠右、票数水平居中。票数只在真多人或单人存档已开 LAN 发布时显示——
 * 单人未发布没有第二个投票者，画出来只是噪声。
 */
public class SkipHudRenderer {

    private static final ResourceLocation SKIP_KEY_TEXTURE =
            new ResourceLocation(ImmersiveCinematics.MOD_ID, "textures/gui/skip_key.png");

    private static final int ICON_SIZE = 16;
    private static final int RING_RADIUS = 12;
    private static final int RING_WIDTH = 4;
    private static final int GAP_TEXT_RING = 4;
    private static final int RING_SEGMENTS = 80;

    /** 绘制跳过提示；不满足显示条件时直接返回，不做任何绘制。 */
    public static void render(GuiGraphics guiGraphics) {
        if (!Config.showSkipHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (!CameraManager.INSTANCE.isScriptMode()) return;
        if (!CameraManager.INSTANCE.isTopInstanceSkippable()) return;
        // hide_skip_hud 三态：仅显式 true 隐藏；未设置（null）= 显示（不回落 hide_hud）
        if (Boolean.TRUE.equals(CinematicController.INSTANCE.isHideSkipHud())) return;

        float progress = CinematicKeyBindings.getSkipHoldProgress();
        renderInternal(mc, guiGraphics, progress);
    }

    private static void renderInternal(Minecraft mc, GuiGraphics guiGraphics, float progress) {
        var font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        Component keyDisplay = CinematicKeyBindings.SKIP_KEY.getTranslatedKeyMessage();
        Component text = Component.translatable("hud.immersive_cinematics.skip_hold",
                keyDisplay.plainCopy().withStyle(style -> style.withColor(0xFFFFFF)));

        int textWidth = font.width(text);
        int blockW = RING_RADIUS * 2 + GAP_TEXT_RING + textWidth;
        int blockH = Math.max(RING_RADIUS * 2, font.lineHeight);

        // 坐标指元素中心：先换算屏幕像素，再按块尺寸回推左上角
        int cx = Math.round(sw * Config.skipHudX / 100f);
        int cy = Math.round(sh * Config.skipHudY / 100f);
        int baseX = cx - blockW / 2;
        int baseY = cy - blockH / 2;

        int ringCenterX = baseX + blockW - RING_RADIUS;
        int ringCenterY = baseY + blockH / 2;

        int textColor = 0xAAFFFFFF;

        int textX = baseX;
        int textY = baseY + (blockH - font.lineHeight) / 2;
        guiGraphics.drawString(font, text, textX, textY, textColor, true);

        int iconX = ringCenterX - ICON_SIZE / 2;
        int iconY = ringCenterY - ICON_SIZE / 2;
        guiGraphics.blit(SKIP_KEY_TEXTURE, iconX, iconY, ICON_SIZE, ICON_SIZE, 0, 0, 64, 64, 64, 64);

        String keyName = keyDisplay.getString();
        String keyShort = keyName.length() <= 3 ? keyName : keyName.substring(0, 2);
        int keyTextX = ringCenterX - font.width(keyShort) / 2;
        int keyTextY = ringCenterY - font.lineHeight / 2 + 1;
        guiGraphics.drawString(font, keyShort, keyTextX, keyTextY, textColor, true);

        if (progress > 0f) {
            drawRingArc(guiGraphics, ringCenterX, ringCenterY, RING_RADIUS, RING_WIDTH, progress, sw, sh);
        }

        // 票数是独立元素：中心点由 voteHudX/voteHudY 单独给出，不受长按块宽度影响
        if (shouldShowVotes(mc)) {
            int voteCount = ClientScriptReceiver.getSkipVoterCount();
            int voteTotal = ClientScriptReceiver.getSkipTotalViewers();
            if (voteTotal > 0) {
                Component voteText = Component.translatable("hud.immersive_cinematics.skip_vote", voteCount, voteTotal);
                int voteW = font.width(voteText);
                int vcx = Math.round(sw * Config.voteHudX / 100f);
                int vcy = Math.round(sh * Config.voteHudY / 100f);
                guiGraphics.drawString(font, voteText, vcx - voteW / 2, vcy - font.lineHeight / 2, textColor, true);
            }
        }
    }

    /**
     * 票数只在对「别人也能投」的场合有意义：真多人，或单人存档已开 LAN 发布。
     * 与 {@code ServerLevel.announceSleepStatus} 的 {@code !isSingleplayer() || isPublished()} 同口径。
     */
    private static boolean shouldShowVotes(Minecraft mc) {
        if (!mc.isLocalServer()) return true;
        var server = mc.getSingleplayerServer();
        return server != null && server.isPublished();
    }

    private static void drawRingArc(GuiGraphics gg, int cx, int cy, int r, int w, float p, int sw, int sh) {
        int segments = Math.max(1, (int) (RING_SEGMENTS * p));
        float angleStep = (float) (2 * Math.PI / RING_SEGMENTS);
        float startAngle = (float) (-Math.PI / 2);

        for (int i = 0; i < segments; i++) {
            float a1 = startAngle + i * angleStep;
            float a2 = startAngle + (i + 1) * angleStep;

            int x1o = cx + (int) (r * Math.cos(a1));
            int y1o = cy + (int) (r * Math.sin(a1));
            int x2o = cx + (int) (r * Math.cos(a2));
            int y2o = cy + (int) (r * Math.sin(a2));
            int x1i = cx + (int) ((r - w) * Math.cos(a1));
            int y1i = cy + (int) ((r - w) * Math.sin(a1));
            int x2i = cx + (int) ((r - w) * Math.cos(a2));
            int y2i = cy + (int) ((r - w) * Math.sin(a2));

            fillTriangle(gg, x1o, y1o, x2o, y2o, x1i, y1i, 0xAAFFFFFF, sw, sh);
            fillTriangle(gg, x2o, y2o, x2i, y2i, x1i, y1i, 0xAAFFFFFF, sw, sh);
        }
    }

    private static void fillTriangle(GuiGraphics gg, int x1, int y1, int x2, int y2, int x3, int y3, int color, int sw, int sh) {
        int minX = Math.max(0, Math.min(x1, Math.min(x2, x3)));
        int maxX = Math.min(sw, Math.max(x1, Math.max(x2, x3)));
        int minY = Math.max(0, Math.min(y1, Math.min(y2, y3)));
        int maxY = Math.min(sh, Math.max(y1, Math.max(y2, y3)));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (pointInTriangle(x, y, x1, y1, x2, y2, x3, y3)) {
                    gg.fill(x, y, x + 1, y + 1, color);
                }
            }
        }
    }

    private static boolean pointInTriangle(int px, int py, int x1, int y1, int x2, int y2, int x3, int y3) {
        float d1 = sign(px, py, x1, y1, x2, y2);
        float d2 = sign(px, py, x2, y2, x3, y3);
        float d3 = sign(px, py, x3, y3, x1, y1);
        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(hasNeg && hasPos);
    }

    private static float sign(int px, int py, int x1, int y1, int x2, int y2) {
        return (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2);
    }
}
