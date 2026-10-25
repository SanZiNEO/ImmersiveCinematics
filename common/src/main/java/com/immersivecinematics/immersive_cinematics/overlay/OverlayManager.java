package com.immersivecinematics.immersive_cinematics.overlay;

import com.immersivecinematics.immersive_cinematics.util.ErrorLog;
import com.immersivecinematics.immersive_cinematics.util.FatalErrors;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 覆盖层管理器 —— 层的注册表与统一调度（渲染 / 动画驱动 / 按归属清理）。
 *
 * 层按 zIndex 升序排列：越小越先绘制（底层），越大越后绘制（顶层）。
 * 层由建层方（{@code OverlayTrackPlayer} / {@code LetterboxTrackPlayer}）运行时加入，
 * 每层带一个不透明的归属 token（{@link OverlayLayer#owner()}，值 = 播放实例引用）；
 * 渐出触发 / 动画判定 / 清理都按归属过滤 —— 一个实例退场不碰其他实例的层。
 * 全停（{@code forceDeactivate} / {@code emergencyStop}）不经渐出：逐实例 {@code deactivateNow} 直接清层。
 *
 * 支持多实例层（如多个文字层 / 多实例黑边层）：{@link #getLayers(Class)} 按类型返回全部匹配层。
 */
public class OverlayManager {

    public static final OverlayManager INSTANCE = new OverlayManager();

    /** 所有注册的覆盖层（按 zIndex 升序排列） */
    private final List<OverlayLayer> layers = new ArrayList<>();

    private OverlayManager() {
    }

    // ========== 渲染 ==========

    /**
     * 渲染所有可见的覆盖层：按 zIndex 升序绘制（底层先、顶层后），不可见的层跳过。
     *
     * @param guiGraphics  Minecraft GUI 绘图上下文
     * @param screenWidth  窗口宽度（像素）
     * @param screenHeight 窗口高度（像素）
     */
    public void render(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        for (OverlayLayer layer : layers) {
            try {
                if (layer.isVisible()) {
                    layer.render(guiGraphics, screenWidth, screenHeight);
                }
            } catch (Exception e) {
                FatalErrors.rethrowIfFatal(e);
                ErrorLog.logRateLimited("Render", "overlay:" + layer.getClass().getName(), 5000L,
                        "覆盖层渲染失败（层=" + layer.getClass().getSimpleName()
                                + ", owner=" + layer.owner() + ", z=" + layer.getZIndex()
                                + "）：本帧跳过，下一帧重试", e);
            }
        }
    }

    // ========== 层管理 ==========

    /**
     * 添加覆盖层，并按 zIndex 重新排序。
     *
     * @param layer 要添加的覆盖层
     */
    public void addLayer(OverlayLayer layer) {
        layers.add(layer);
        layers.sort(Comparator.comparingInt(OverlayLayer::getZIndex));
    }

    /**
     * 移除覆盖层并释放其 GPU 资源（{@link OverlayLayer#close()}）。
     *
     * @param layer 要移除的覆盖层
     * @return 是否成功移除（{@code false} = 不在列表中，不调用 close，避免误关他处仍持有的层）
     */
    public boolean removeLayer(OverlayLayer layer) {
        boolean removed = layers.remove(layer);
        if (removed) layer.close();
        return removed;
    }

    /**
     * 按类型查找所有匹配的覆盖层（多实例）：同一类型可有多个实例，如多个 TextLayer。
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

    // ========== 动画驱动 ==========

    /**
     * 每渲染帧更新所有层（驱动动画）：在 render() 之前调用，逐层 tick(deltaTime)。
     *
     * @param deltaTime 距上一帧的秒数
     */
    public void update(float deltaTime) {
        for (OverlayLayer layer : layers) {
            layer.tick(deltaTime);
        }
    }

    /**
     * 指定归属是否有层正在执行过渡动画（帧驱动据此判定该实例的退场是否完成：
     * A 实例的渐出不决定 B 实例何时停）。
     * 逐层读 {@link OverlayLayer#isAnimating()}，零分配。
     *
     * @param owner 层归属 token（{@code null} = 只匹配无主层）
     */
    public boolean isAnimating(Object owner) {
        for (int i = 0; i < layers.size(); i++) {
            OverlayLayer layer = layers.get(i);
            if (Objects.equals(layer.owner(), owner) && layer.isAnimating()) return true;
        }
        return false;
    }

    /**
     * 任一归属是否有层正在动画 —— 渲染入口门控用（相机已停但层还在渐出时仍需渲染一帧）。
     */
    public boolean isAnyAnimating() {
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).isAnimating()) return true;
        }
        return false;
    }

    /**
     * 触发指定归属层的退出动画（只对 {@code owner} 匹配的层调 {@link OverlayLayer#startFadeOut()}）。
     *
     * @param owner 层归属 token（{@code null} = 只匹配无主层）
     */
    public void startFadeOut(Object owner) {
        for (int i = 0; i < layers.size(); i++) {
            OverlayLayer layer = layers.get(i);
            if (Objects.equals(layer.owner(), owner)) layer.startFadeOut();
        }
    }

    /**
     * 移除指定归属的全部层并释放其 GPU 资源（{@link OverlayLayer#close()}）。
     * 实例退场兜底：TrackPlayer 清理抛异常时也不留残层。
     *
     * @param owner 层归属 token（{@code null} = 只匹配无主层）
     * @return 实际移除的层数
     */
    public int removeLayersOf(Object owner) {
        int removed = 0;
        for (int i = layers.size() - 1; i >= 0; i--) {
            OverlayLayer layer = layers.get(i);
            if (Objects.equals(layer.owner(), owner)) {
                layers.remove(i);
                layer.close();
                removed++;
            }
        }
        return removed;
    }

    // ========== 生命周期 ==========

    /**
     * 重置所有层到默认状态：遍历所有层调用各自的 reset()，每层自行负责重置逻辑。
     * 在相机停用时调用，确保黑边等效果消失。
     */
    public void reset() {
        for (OverlayLayer layer : layers) {
            layer.reset();
        }
    }
}
