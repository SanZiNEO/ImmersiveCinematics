package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class SkipHudRenderer {

    private static final ResourceLocation SKIP_KEY_TEXTURE =
            new ResourceLocation(ImmersiveCinematics.MOD_ID, "textures/gui/skip_key.png");

    private static final int MARGIN_RIGHT = 2;
    private static final int MARGIN_BOTTOM = 4;
    private static final int ICON_SIZE = 16;
    private static final int RING_RADIUS = 12;
    private static final int RING_WIDTH = 4;
    private static final int GAP_TEXT_RING = 4;
    private static final int RING_SEGMENTS = 80;

    public static void render(GuiGraphics guiGraphics) {
        if (!Config.showSkipHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (!CameraManager.INSTANCE.isScriptMode()) return;
        if (!CinematicController.INSTANCE.isSkippable()) return;

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
        int totalWidth = RING_RADIUS * 2 + GAP_TEXT_RING + textWidth;
        int totalHeight = Math.max(RING_RADIUS * 2, font.lineHeight);

        int baseX = sw - MARGIN_RIGHT - totalWidth;
        int baseY = sh - MARGIN_BOTTOM - totalHeight;

        int ringCenterX = baseX + totalWidth - RING_RADIUS;
        int ringCenterY = baseY + totalHeight / 2;

        int textColor = 0xAAFFFFFF;

        int textX = baseX;
        int textY = baseY + (totalHeight - font.lineHeight) / 2;
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

        if (!mc.isLocalServer()) {
            int voteCount = ClientScriptReceiver.getSkipVoterCount();
            int voteTotal = ClientScriptReceiver.getSkipTotalViewers();
            if (voteTotal > 0) {
                Component voteText = Component.translatable("hud.immersive_cinematics.skip_vote",
                        voteCount, voteTotal);
                int voteW = font.width(voteText);
                guiGraphics.drawString(font, voteText, (sw - voteW) / 2, sh - 30, textColor, true);
            }
        }
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
