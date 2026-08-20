package com.immersivecinematics.immersive_cinematics.editor.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 编辑器极简图标工具（0.3.5 第5轮）。
 * <p>
 * 从 {@code assets/immersive_cinematics/textures/gui/editor/} 读取 64×64 PNG，
 * 通过 GuiGraphics.blit 绘制，保持与 UI 队列一致的裁剪和绘制顺序。
 * 参考 BBS 的 UIIcon/Batcher2D.icon 用法：支持用 ARGB 颜色对图标整体染色。
 */
public final class EditorIcons {

    private static final String ICON_PATH = "textures/gui/editor/";
    private static final int TEXTURE_SIZE = 64;

    private EditorIcons() {}

    public static void render(UIContext ctx, String name, int x, int y, int size) {
        render(ctx.graphics, name, x, y, size, 0xFFFFFFFF);
    }

    public static void render(UIContext ctx, String name, int x, int y, int size, int color) {
        render(ctx.graphics, name, x, y, size, color);
    }

    public static void render(GuiGraphics gui, String name, int x, int y, int size) {
        render(gui, name, x, y, size, 0xFFFFFFFF);
    }

    public static void render(GuiGraphics gui, String name, int x, int y, int size, int color) {
        if (gui == null || name == null || name.isEmpty()) return;
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        RenderSystem.setShaderColor(r, g, b, a);
        ResourceLocation loc = new ResourceLocation("immersive_cinematics", ICON_PATH + name + ".png");
        gui.blit(loc, x, y, size, size, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }
}
