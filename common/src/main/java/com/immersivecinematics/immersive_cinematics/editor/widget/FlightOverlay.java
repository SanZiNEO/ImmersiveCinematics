package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.editor.PreviewCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;

/**
 * 飞行取景覆盖层组件（0.3.5 第5轮）。
 * <p>
 * 作为 UI 树的顶层子组件，只在 renderOverlay 阶段绘制。
 * 覆盖层本体用 RenderSystem + BufferBuilder 直接绘制，不经过 GuiGraphics 的 scissor 栈，
 * 避免被前序区域残留 scissor 裁剪导致穿透。
 */
public class FlightOverlay extends UIComponent {

    public FlightOverlay() {
        super(0, 0, 0, 0);
    }

    @Override
    public void render(UIContext ctx) {
        // 普通阶段不绘制，只在 renderOverlay 覆盖层阶段绘制
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        if (!visible) return;

        GuiGraphics guiGraphics = ctx.graphics;
        int screenW = ctx.screenWidth;
        int screenH = ctx.screenHeight;

        // 清掉可能残留的硬件 scissor / 深度，确保覆盖层独立画在最上层
        RenderSystem.disableScissor();
        RenderSystem.disableDepthTest();

        int margin = Math.max(24, (int)(Math.min(screenW, screenH) * 0.08));
        int ox = margin;
        int oy = margin;
        int ow = screenW - margin * 2;
        int oh = screenH - margin * 2;

        // 全屏不透明背景
        fillColor(0, 0, screenW, screenH, 0xFF101015);
        // 外框阴影
        fillColor(ox - 4, oy - 4, ox + ow + 4, oy + oh + 4, 0xFF000000);
        // 覆盖层内容底
        fillColor(ox, oy, ox + ow, oy + oh, 0xFF101016);
        // 边框
        fillColor(ox, oy, ox + ow, oy + 1, 0xFF33333D);
        fillColor(ox, oy + oh - 1, ox + ow, oy + oh, 0xFF33333D);
        fillColor(ox, oy + 1, ox + 1, oy + oh - 1, 0xFF33333D);
        fillColor(ox + ow - 1, oy + 1, ox + ow, oy + oh - 1, 0xFF33333D);

        int texId = PreviewCapture.getTextureId();
        if (texId >= 0) {
            int capW = PreviewCapture.getWidth();
            int capH = PreviewCapture.getHeight();
            float srcAspect = (float) capW / capH;
            float dstAspect = (float) ow / oh;
            int rx, ry, rw, rh;
            if (srcAspect > dstAspect) {
                rw = ow;
                rh = (int) (ow / srcAspect);
                rx = ox;
                ry = oy + (oh - rh) / 2;
            } else {
                rh = oh;
                rw = (int) (oh * srcAspect);
                rx = ox + (ow - rw) / 2;
                ry = oy;
            }

            RenderSystem.setShaderTexture(0, texId);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            var pose = guiGraphics.pose();
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
        }

        RenderSystem.enableDepthTest();

        String hint = I18n.get("editor.flight.hint");
        guiGraphics.drawString(ctx.font, hint, ox + 8, oy + 6, 0xFFA0A0B0);
    }

    private void fillColor(int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var builder = new BufferBuilder(256);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        builder.vertex(x1, y2, 0).color(r, g, b, a).endVertex();
        builder.vertex(x2, y2, 0).color(r, g, b, a).endVertex();
        builder.vertex(x2, y1, 0).color(r, g, b, a).endVertex();
        builder.vertex(x1, y1, 0).color(r, g, b, a).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }
}
