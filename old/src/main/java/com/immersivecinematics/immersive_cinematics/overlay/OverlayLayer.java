package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 覆盖层接口 — 类似剪辑软件的轨道层
 * <p>
 * 每个覆盖层实现此接口，按 zIndex 顺序由 OverlayManager 统一调度渲染。
 * 层级从低到高：黑边(Letterbox) → 文字(Text) → 视频(Video) → ...
 * <p>
 * 渲染顺序：zIndex 越小越先绘制，越在底层；
 * zIndex 越大越后绘制，越在顶层（覆盖下层内容）。
 * <p>
 * 生命周期：OverlayManager.reset() 会遍历所有层调用 reset()，
 * 每层自行负责自己的重置逻辑，无需在 OverlayManager 中硬编码。
 */
public interface OverlayLayer {

    /**
     * 渲染此覆盖层
     * <p>
     * 由 OverlayManager.render() 在 isVisible() 为 true 时调用，
     * 实现类无需再次检查可见性。
     *
     * @param guiGraphics Minecraft GUI 绘图上下文
     * @param screenWidth  窗口宽度（像素）
     * @param screenHeight 窗口高度（像素）
     */
    void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight);

    /**
     * 此覆盖层是否可见
     * <p>
     * 不可见的层不会被渲染，但仍保留在层列表中
     */
    boolean isVisible();

    /**
     * 获取层级索引（z-index）
     * <p>
     * 数值越小越在底层（先绘制），数值越大越在顶层（后绘制）。
     * 建议值：
     * <ul>
     *   <li>0 — 黑边 (Letterbox)</li>
     *   <li>100+ — 文字字幕 (Text)，支持多个文字层：100, 101, 102...</li>
     *   <li>200+ — 视频播放 (Video)</li>
     * </ul>
     * 间隔 100 方便后续插入新层
     */
    int getZIndex();

    /**
     * 重置此覆盖层到默认状态
     * <p>
     * 由 OverlayManager.reset() 在相机停用时遍历调用。
     * 每层自行定义重置逻辑（如 LetterboxLayer 将 aspectRatio 归零），
     * 无需在 OverlayManager 中为每层硬编码 reset 调用。
     */
    void reset();

    /**
     * 每渲染帧更新（动画驱动）
     * <p>
     * 由 OverlayManager.update(deltaTime) 遍历调用，
     * 实现类可重写以驱动过渡动画（如 LetterboxLayer 的 fade-in/fade-out）。
     *
     * @param deltaTime 距上一帧的秒数
     */
    default void tick(float deltaTime) {}

    /**
     * 触发退出动画
     * <p>
     * 实现类可重写以启动 fade-out 过渡，
     * 默认实现为空操作（无动画的层无需关心）。
     */
    default void startFadeOut() {}

    /**
     * 是否正在执行过渡动画
     * <p>
     * 由 OverlayManager.isAnimating() 遍历调用，
     * 用于判断所有层是否都已完成动画。
     * 默认实现返回 false（无动画的层始终视为非动画状态）。
     */
    default boolean isAnimating() { return false; }
}
