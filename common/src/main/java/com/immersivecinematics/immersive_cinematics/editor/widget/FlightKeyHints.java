package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.immersivecinematics.immersive_cinematics.control.CinematicKeyBindings;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞行取景按键提示（0.3.5 第5轮）。
 * <p>
 * 独立子组件，右下角纵向排布：鼠标图标 + 圆角键帽 PNG + 动态键位名。
 * 键帽文字来自玩家当前实际绑定的 {@link KeyMapping}，不写死默认键。
 */
public class FlightKeyHints extends UIComponent {

    private static final ResourceLocation KEYCAP = new ResourceLocation("immersive_cinematics", "textures/gui/flight/keycap.png");
    private static final ResourceLocation MOUSE = new ResourceLocation("immersive_cinematics", "textures/gui/flight/mouse.png");

    private static final int MARGIN = 8;
    private static final int ROW_H = 22;
    private static final int KEYCAP_HEIGHT = 16;
    private static final int KEYCAP_PAD_X = 4;
    private static final int KEYCAP_GAP = 2;
    private static final int MOUSE_SIZE = 28;
    private static final int ICON_TEXT_GAP = 6;
    private static final int PAD = 6;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int BG_COLOR = 0x66000000;

    private int frameX;
    private int frameY;
    private int frameW;
    private int frameH;

    private static class HintRow {
        final boolean mouse;
        final List<String> keys;
        final String action;

        HintRow(boolean mouse, List<String> keys, String action) {
            this.mouse = mouse;
            this.keys = keys;
            this.action = action;
        }
    }

    public FlightKeyHints() {
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

        List<HintRow> rows = buildRows(Minecraft.getInstance().options);
        if (rows.isEmpty()) {
            RenderSystem.enableDepthTest();
            return;
        }

        int maxIconW = 0;
        int maxActionW = 0;
        for (HintRow row : rows) {
            int iconW = row.mouse ? MOUSE_SIZE : rowIconWidth(ctx, row.keys);
            maxIconW = Math.max(maxIconW, iconW);
            maxActionW = Math.max(maxActionW, ctx.font.width(row.action));
        }

        int blockW = PAD * 2 + maxIconW + ICON_TEXT_GAP + maxActionW;
        int blockH = PAD * 2 + rows.size() * ROW_H;
        int x = frameX + frameW - MARGIN - blockW;
        int y = frameY + frameH - MARGIN - blockH;

        fillColor(x, y, x + blockW, y + blockH, BG_COLOR);

        GuiGraphics gui = ctx.graphics;
        for (int i = 0; i < rows.size(); i++) {
            HintRow row = rows.get(i);
            int ry = y + PAD + i * ROW_H;
            int iconX = x + PAD;
            if (row.mouse) {
                drawTexture(ctx, MOUSE, iconX, ry + (ROW_H - MOUSE_SIZE) / 2, MOUSE_SIZE, MOUSE_SIZE);
            } else {
                int kx = iconX;
                int ky = ry + (ROW_H - KEYCAP_HEIGHT) / 2;
                for (String key : row.keys) {
                    int kw = keycapWidth(ctx, key);
                    drawTexture(ctx, KEYCAP, kx, ky, kw, KEYCAP_HEIGHT);
                    drawKeyLabel(ctx, key, kx, ky, kw, KEYCAP_HEIGHT);
                    kx += kw + KEYCAP_GAP;
                }
            }
            int tx = x + PAD + maxIconW + ICON_TEXT_GAP;
            int ty = ry + (ROW_H - ctx.font.lineHeight) / 2;
            gui.drawString(ctx.font, row.action, tx, ty, TEXT_COLOR, false);
        }

        RenderSystem.enableDepthTest();
    }

    private List<HintRow> buildRows(Options opts) {
        List<HintRow> rows = new ArrayList<>();
        rows.add(new HintRow(true, List.of(), I18n.get("editor.flight.key.look")));
        rows.add(new HintRow(false, List.of(
                keyLabel(opts.keyUp),
                keyLabel(opts.keyLeft),
                keyLabel(opts.keyDown),
                keyLabel(opts.keyRight)), I18n.get("editor.flight.key.move")));
        rows.add(new HintRow(false, List.of(
                keyLabel(opts.keyJump),
                keyLabel(opts.keyShift)), I18n.get("editor.flight.key.up_down")));
        rows.add(new HintRow(false, List.of("Ctrl"), I18n.get("editor.flight.key.slow")));
        rows.add(new HintRow(false, List.of(
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_LEFT),
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ROLL_RIGHT)), I18n.get("editor.flight.key.roll")));
        rows.add(new HintRow(false, List.of(
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_FOV_OUT),
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_FOV_IN)), I18n.get("editor.flight.key.fov")));
        rows.add(new HintRow(false, List.of(
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_OUT),
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_ZOOM_IN)), I18n.get("editor.flight.key.zoom")));
        rows.add(new HintRow(false, List.of(
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_MODE)), I18n.get("editor.flight.key.save_mode")));
        rows.add(new HintRow(false, List.of(
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT_RESET_OPTICS)), I18n.get("editor.flight.key.reset_optics")));
        rows.add(new HintRow(false, List.of(
                keyLabel(CinematicKeyBindings.EDITOR_FLIGHT)), I18n.get("editor.flight.key.save_exit")));
        rows.add(new HintRow(false, List.of("Esc"), I18n.get("editor.flight.key.cancel")));
        return rows;
    }

    private static String keyLabel(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }

    private int rowIconWidth(UIContext ctx, List<String> keys) {
        int w = 0;
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) w += KEYCAP_GAP;
            w += keycapWidth(ctx, keys.get(i));
        }
        return w;
    }

    private static int keycapWidth(UIContext ctx, String label) {
        return ctx.font.width(label) + KEYCAP_PAD_X * 2;
    }

    private void drawKeyLabel(UIContext ctx, String label, int kx, int ky, int kw, int kh) {
        int tx = kx + (kw - ctx.font.width(label)) / 2;
        int ty = ky + (kh - ctx.font.lineHeight) / 2;
        ctx.graphics.drawString(ctx.font, label, tx, ty, 0xFFFFFFFF, false);
    }

    private void drawTexture(UIContext ctx, ResourceLocation loc, int x, int y, int w, int h) {
        RenderSystem.setShaderTexture(0, loc);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        var pose = ctx.graphics.pose();
        pose.pushPose();
        var builder = new BufferBuilder(256);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(x, y + h, 0).uv(0, 0).endVertex();
        builder.vertex(x + w, y + h, 0).uv(1, 0).endVertex();
        builder.vertex(x + w, y, 0).uv(1, 1).endVertex();
        builder.vertex(x, y, 0).uv(0, 1).endVertex();
        BufferUploader.drawWithShader(builder.end());
        pose.popPose();
        RenderSystem.setShaderTexture(0, 0);
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
