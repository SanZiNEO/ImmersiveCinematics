package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ImmersiveCinematics.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class SkipHudRenderer {

    private static final ResourceLocation SKIP_KEY_TEXTURE =
            new ResourceLocation(ImmersiveCinematics.MODID, "textures/gui/skip_key.png");

    private static final int MARGIN_RIGHT = 2;
    private static final int MARGIN_BOTTOM = 4;
    private static final int ICON_SIZE = 16;
    private static final int RING_RADIUS = 12;
    private static final int RING_WIDTH = 4;
    private static final int GAP_TEXT_RING = 4;
    private static final int RING_SEGMENTS = 80;

    @SubscribeEvent
    public static void onGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (!CameraManager.INSTANCE.isScriptMode()) return;
        if (!CinematicController.INSTANCE.isSkippable()) return;

        float progress = CinematicKeyBindings.getSkipHoldProgress();
        render(mc, event, progress);
    }

    private static void render(Minecraft mc, RenderGuiOverlayEvent.Post event, float progress) {
        var guiGraphics = event.getGuiGraphics();
        var font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        String keyName = CinematicKeyBindings.SKIP_KEY.getTranslatedKeyMessage().getString();
        Component text = Component.literal("长按 ").append(
                Component.literal(keyName).withStyle(style -> style.withColor(0xFFFFFF)))
                .append(Component.literal(" 跳过动画"));

        int textWidth = font.width(text);
        int totalWidth = RING_RADIUS * 2 + GAP_TEXT_RING + textWidth;
        int totalHeight = Math.max(RING_RADIUS * 2, font.lineHeight);

        int baseX = sw - MARGIN_RIGHT - totalWidth;
        int baseY = sh - MARGIN_BOTTOM - totalHeight;

        int ringCenterX = baseX + totalWidth - RING_RADIUS;
        int ringCenterY = baseY + totalHeight / 2;

        int textColor = 0xAAFFFFFF;

        // 文字
        int textX = baseX;
        int textY = baseY + (totalHeight - font.lineHeight) / 2;
        guiGraphics.drawString(font, text, textX, textY, textColor, true);

        // 按键底图
        int iconX = ringCenterX - ICON_SIZE / 2;
        int iconY = ringCenterY - ICON_SIZE / 2;
        guiGraphics.blit(SKIP_KEY_TEXTURE, iconX, iconY, ICON_SIZE, ICON_SIZE, 0, 0, 64, 64, 64, 64);

        // 按键文字
        String keySingle = keyName.length() <= 3 ? keyName : keyName.substring(0, 2);
        int keyTextX = ringCenterX - font.width(keySingle) / 2;
        int keyTextY = ringCenterY - font.lineHeight / 2 + 1;
        guiGraphics.drawString(font, keySingle, keyTextX, keyTextY, textColor, true);

        if (progress > 0f) {
            drawRingArc(guiGraphics, ringCenterX, ringCenterY, RING_RADIUS, RING_WIDTH, progress);
        }
    }

    private static void drawRingArc(net.minecraft.client.gui.GuiGraphics gg, int cx, int cy, int r, int w, float p) {
        int color = 0xFFFFFFFF;

        for (int i = 0; i < RING_SEGMENTS; i++) {
            float t = (float) i / RING_SEGMENTS;
            if (t > p) break;

            float a1 = (float) (-Math.PI / 2 + Math.PI * 2 * t);
            float a2 = (float) (-Math.PI / 2 + Math.PI * 2 * Math.min((i + 1f) / RING_SEGMENTS, p));

            int x1 = cx + (int) (Math.cos(a1) * r);
            int y1 = cy + (int) (Math.sin(a1) * r);
            int x2 = cx + (int) (Math.cos(a2) * r);
            int y2 = cy + (int) (Math.sin(a2) * r);
            int x3 = cx + (int) (Math.cos(a1) * (r - w));
            int y3 = cy + (int) (Math.sin(a1) * (r - w));
            int x4 = cx + (int) (Math.cos(a2) * (r - w));
            int y4 = cy + (int) (Math.sin(a2) * (r - w));

            gg.fill(
                Math.min(Math.min(x1, x2), Math.min(x3, x4)),
                Math.min(Math.min(y1, y2), Math.min(y3, y4)),
                Math.max(Math.max(x1, x2), Math.max(x3, x4)) + 1,
                Math.max(Math.max(y1, y2), Math.max(y3, y4)) + 1,
                color);
        }
    }
}
