package com.immersivecinematics.immersive_cinematics.media;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 坏素材错误纹理 —— 黑 / 洋红 16×9 棋盘格，全工程共享一张。
 *
 * <p>用途：视频层探测或解码失败时，用它替代真实画面（虚拟素材尺寸 {@link #WIDTH}×{@link #HEIGHT}），
 * 让作者一眼看出「这段素材坏了」，而不是黑屏猜原因。</p>
 *
 * <p><b>线程边界</b>：{@link #texture()} 首次调用会创建 {@link NativeImage} 并注册 {@link DynamicTexture}，
 * 必须在<b>渲染线程</b>调用；创建后只读缓存，之后任意线程调用都只返回同一个 {@link ResourceLocation}。</p>
 *
 * <p><b>生命周期</b>：懒创建一次，进程内常驻，<b>不随任何覆盖层关闭而释放</b>（多实例共享）。
 * 像素为 ABGR 打包（与 {@link NativeImage#setPixelRGBA} 的小端内存布局一致：字节序 R,G,B,A）。</p>
 */
public final class ErrorTexture {

    /** 虚拟素材宽（像素）：错误纹理按 1920×1080 参与几何解算。 */
    public static final int WIDTH = 1920;

    /** 虚拟素材高（像素）。 */
    public static final int HEIGHT = 1080;

    /** 棋盘格列数（16 列 × 9 行 = 16:9，单格 120×120 像素）。 */
    private static final int COLUMNS = 16;

    /** 棋盘格行数。 */
    private static final int ROWS = 9;

    /** 黑色格（A=255, B=0, G=0, R=0）。 */
    private static final int COLOR_BLACK = 0xFF000000;

    /** 洋红色格（A=255, B=255, G=0, R=255）。 */
    private static final int COLOR_MAGENTA = 0xFFFF00FF;

    /** 注册名（固定，进程内唯一）。 */
    private static final ResourceLocation LOCATION =
            new ResourceLocation("immersive_cinematics", "error_texture");

    private static final Object LOCK = new Object();

    /** 懒创建结果；{@code null} = 尚未创建。 */
    private static volatile ResourceLocation texture;

    private ErrorTexture() {
    }

    /**
     * 共享错误纹理的注册名（懒创建一次）。
     *
     * @return 可直接交给 {@code GuiGraphics.blit} 的纹理位置；同一进程内恒为同一实例
     */
    public static ResourceLocation texture() {
        ResourceLocation existing = texture;
        if (existing != null) {
            return existing;
        }
        synchronized (LOCK) {
            if (texture == null) {
                texture = create();
            }
            return texture;
        }
    }

    /** 生成棋盘格像素并注册动态纹理；只在渲染线程、只在首次调用时执行一次。 */
    private static ResourceLocation create() {
        NativeImage image = new NativeImage(WIDTH, HEIGHT, true);
        for (int row = 0; row < ROWS; row++) {
            int y0 = row * HEIGHT / ROWS;
            int y1 = (row + 1) * HEIGHT / ROWS;
            for (int column = 0; column < COLUMNS; column++) {
                int color = ((row + column) & 1) == 0 ? COLOR_BLACK : COLOR_MAGENTA;
                int x0 = column * WIDTH / COLUMNS;
                int x1 = (column + 1) * WIDTH / COLUMNS;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        image.setPixelRGBA(x, y, color);
                    }
                }
            }
        }
        DynamicTexture dynamic = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(LOCATION, dynamic);
        return LOCATION;
    }
}
