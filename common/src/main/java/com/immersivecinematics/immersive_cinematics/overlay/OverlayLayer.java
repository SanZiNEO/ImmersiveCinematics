package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 覆盖层接口 —— 按 zIndex 顺序由 {@link OverlayManager} 统一调度。
 *
 * 渲染顺序：zIndex 越小越先绘制（越在底层），越大越后绘制（越在顶层）。
 * 生命周期：{@link #render} 由管理器在 {@link #isVisible()} 为 true 时调用（实现类无需再查可见性）；
 * {@link #reset()} 回到默认状态（不释放资源）；{@link #close()} 在层移出列表后释放资源。
 * 归属 token（{@link #owner()}）是管理器的过滤键：渐出 / 动画判定 / 清理都只碰同归属的层。
 */
public interface OverlayLayer {

    /**
     * 渲染此覆盖层。由 {@link OverlayManager#render} 在 {@link #isVisible()} 为 true 时调用，
     * 实现类无需再次检查可见性。
     *
     * @param guiGraphics Minecraft GUI 绘图上下文
     * @param screenWidth  窗口宽度（像素）
     * @param screenHeight 窗口高度（像素）
     */
    void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight);

    /**
     * 此覆盖层是否可见。不可见的层不会被渲染，但仍保留在层列表中。
     */
    boolean isVisible();

    /**
     * 层级索引（z-index）：数值越小越在底层（先绘制），越大越在顶层（后绘制）。
     *
     * 建议值：0 = 画幅层（{@link LetterboxLayer}，内置常量、不在脚本口径内）；
     * 10 = 脚本层统一默认值（{@link CanvasTransform#DEFAULT_Z_INDEX}，不写 {@code z_index} 的
     * fade / image / subtitle 都在这一层）；10+ = 需要压住其他覆盖层时显式写更大的值
     * （如字幕 30、转场遮罩 200）。
     */
    int getZIndex();

    /**
     * 层归属 token（不透明）——{@code null} = 无主层。
     *
     * 由建层方构造注入（值 = 播放实例引用；{@code Object} 类型以免 overlay → camera 包依赖）。
     * {@link OverlayManager#startFadeOut(Object)} / {@link OverlayManager#isAnimating(Object)} /
     * {@link OverlayManager#removeLayersOf(Object)} 按此过滤：A 实例的渐出不碰 B 实例的层。
     */
    Object owner();

    /**
     * 重置此覆盖层到默认状态。由 {@link OverlayManager#reset()} 在相机停用时遍历调用；
     * 每层自行定义重置内容（如 {@link LetterboxLayer} 将 aspectRatio 归零），不释放资源。
     */
    void reset();

    /**
     * 每渲染帧更新（动画驱动）。由 {@link OverlayManager#update(float)} 遍历调用，
     * 实现类可重写以驱动过渡动画（如 {@link LetterboxLayer} 的渐出）。
     *
     * @param deltaTime 距上一帧的秒数
     */
    default void tick(float deltaTime) {}

    /**
     * 触发退出动画。实现类可重写以启动渐出，默认空操作
     * （无动画的层无需关心——下一帧由帧驱动硬停）。
     */
    default void startFadeOut() {}

    /**
     * 是否正在执行过渡动画。由 {@link OverlayManager#isAnimating(Object)} 按归属遍历调用，
     * 用于判断该实例的层是否都已完成退场。默认 {@code false}（无动画的层退场时下一帧硬停）。
     */
    default boolean isAnimating() { return false; }

    /**
     * 释放此覆盖层持有的 GPU / 原生资源（默认无资源 = 空操作）。
     * 由 {@link OverlayManager#removeLayer(OverlayLayer)} /
     * {@link OverlayManager#removeLayersOf(Object)} 在层移出列表后调用；
     * 每个层只会被关闭一次（实现方仍需对重复调用保持幂等）。
     * {@link #reset()} 不关闭资源——层仍在列表中，只是回到默认状态。
     */
    default void close() {}
}
