package com.immersivecinematics.immersive_cinematics.overlay;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 覆盖层管理器 — 单例模式
 * <p>
 * 类似剪辑软件的轨道系统，管理所有覆盖层的生命周期和渲染顺序。
 * 层按 zIndex 升序排列：zIndex 越小越先绘制（底层），越大越后绘制（顶层）。
 * <p>
 * 支持多实例层（如多个文字层），通过 {@link #getLayers(Class)} 按类型查找所有匹配层。
 * <p>
 * 当前内置层：
 * <ul>
 *   <li>{@link LetterboxLayer} (zIndex=0) — 画幅比黑边</li>
 * </ul>
 * <p>
 * 后续可扩展：
 * <ul>
 *   <li>TextLayer (zIndex=100+) — 多个文字字幕层</li>
 *   <li>VideoLayer (zIndex=200+) — 视频播放</li>
 * </ul>
 */
public class OverlayManager {

    public static final OverlayManager INSTANCE = new OverlayManager();

    /** 所有注册的覆盖层（按 zIndex 升序排列） */
    private final List<OverlayLayer> layers = new ArrayList<>();

    /** 内置层引用（方便快速访问） */
    private final LetterboxLayer letterboxLayer = new LetterboxLayer();

    private OverlayManager() {
        addLayer(letterboxLayer);
    }

    // ========== 渲染 ==========

    /**
     * 渲染所有可见的覆盖层
     * <p>
     * 按 zIndex 升序顺序渲染，确保底层先绘制、顶层后绘制。
     * 不可见的层（isVisible() == false）自动跳过。
     *
     * @param guiGraphics  Minecraft GUI 绘图上下文
     * @param screenWidth  窗口宽度（像素）
     * @param screenHeight 窗口高度（像素）
     */
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        for (OverlayLayer layer : layers) {
            if (layer.isVisible()) {
                layer.render(guiGraphics, screenWidth, screenHeight);
            }
        }
    }

    // ========== 层管理 ==========

    /**
     * 添加覆盖层
     * <p>
     * 添加后自动按 zIndex 重新排序
     *
     * @param layer 要添加的覆盖层
     */
    public void addLayer(OverlayLayer layer) {
        layers.add(layer);
        layers.sort(Comparator.comparingInt(OverlayLayer::getZIndex));
    }

    /**
     * 移除覆盖层
     *
     * @param layer 要移除的覆盖层
     * @return 是否成功移除
     */
    public boolean removeLayer(OverlayLayer layer) {
        return layers.remove(layer);
    }

    /**
     * 按类型查找所有匹配的覆盖层（支持多实例）
     * <p>
     * 适用于同一类型有多个实例的场景，如多个 TextLayer。
     *
     * @param clazz 层的 Class 对象
     * @param <T>   层类型
     * @return 所有匹配的层列表（按 zIndex 排序），不会返回 null
     */
    public <T extends OverlayLayer> List<T> getLayers(Class<T> clazz) {
        return layers.stream()
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .collect(Collectors.toList());
    }

    // ========== 内置层便捷访问 ==========

    /**
     * 获取黑边层
     */
    public LetterboxLayer getLetterboxLayer() {
        return letterboxLayer;
    }

    // ========== 生命周期 ==========

    /**
     * 重置所有层到默认状态
     * <p>
     * 遍历所有注册的层调用各自的 reset()，
     * 每层自行负责自己的重置逻辑，无需在此硬编码。
     * <p>
     * 在相机停用时调用，确保黑边等效果消失
     */
    public void reset() {
        for (OverlayLayer layer : layers) {
            layer.reset();
        }
    }
}
