package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.control.FlightController;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 飞行取景参数 HUD（0.3.5 第5轮）。
 * <p>
 * 独立子组件，左上角 F3 风格：半透明底行 + 9px 行高，展示
 * 位置 / 朝向 / 滚转 / FOV / 缩放 / 保存模式。
 */
public class FlightHud extends UIComponent {

    private static final int MARGIN = 6;
    private static final int LINE_HEIGHT = 9;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int BACKGROUND_COLOR = 0x80000000;

    private int frameX;
    private int frameY;
    private int frameW;
    private int frameH;

    public FlightHud() {
        super(0, 0, 0, 0);
    }

    public void setFrame(int x, int y, int w, int h) {
        this.frameX = x;
        this.frameY = y;
        this.frameW = w;
        this.frameH = h;
    }

    @Override
    public void render(UIContext ctx) {
        // 只在 renderOverlay 阶段绘制
    }

    @Override
    public void renderOverlay(UIContext ctx) {
        if (!visible) return;

        RenderSystem.disableScissor();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();

        FlightController f = FlightController.INSTANCE;
        Vec3 pos = f.getPos();
        List<String> lines = new ArrayList<>();
        lines.add("Flight Camera" + (f.isSlowDown() ? "  [SLOW]" : ""));
        lines.add(String.format(Locale.ROOT, "XYZ: %.3f / %.3f / %.3f", pos.x, pos.y, pos.z));
        lines.add(String.format(Locale.ROOT, "Yaw: %.2f  Pitch: %.2f  Roll: %.2f",
                f.getYaw(), f.getPitch(), f.getRoll()));
        lines.add(String.format(Locale.ROOT, "FOV: %.2f  Zoom: %.2f", f.getFov(), f.getZoom()));
        lines.add("Mode: " + (f.isModeAbsolute() ? "ABSOLUTE" : "RELATIVE"));

        GuiGraphics gui = ctx.graphics;
        int x = frameX + MARGIN;
        int y = frameY + MARGIN;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int textW = ctx.font.width(line);
            fillColor(x, y + i * LINE_HEIGHT,
                    x + textW + 4, y + i * LINE_HEIGHT + LINE_HEIGHT, BACKGROUND_COLOR);
            gui.drawString(ctx.font, line, x + 2, y + i * LINE_HEIGHT + 1, TEXT_COLOR, false);
        }

        RenderSystem.enableDepthTest();
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
