package com.immersivecinematics.immersive_cinematics.api;

import com.immersivecinematics.immersive_cinematics.overlay.OverlayLayer;
import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 覆盖层对外出口 —— 第三方轨道「出画面」的唯一正式通道：开层 / 改 z 序 / 关层。
 *
 * <p>本类不新建任何图层容器：所有层都进既有的 {@link OverlayManager#INSTANCE}，
 * 因此 z 序口径与释放语义和内置 overlay 完全一致——数值越小越先绘制（越底层），
 * {@code 0} = 画幅层，{@code 10} = 脚本层默认（{@code CanvasTransform.DEFAULT_Z_INDEX}），
 * {@code 10+} = 显式压层（字幕 30、转场遮罩 200）。
 * 同一 {@code (owner, key)} 重复开层只改 z 序与渲染器，不重建层，返回同一个 {@link Handle}。
 *
 * <p>归属（{@code owner}）必须传播放实例 token：第三方轨道取 {@code TrackContext.owner()}。
 * 播放实例停止时 {@code PlaybackInstance.stop} 会调用 {@code OverlayManager.removeLayersOf(this)}，
 * 只清理 owner 相同的层——传别的对象（合成的新对象、字符串 id）会让层活过实例退场，成为残层。
 *
 * <p>本类<b>不提供</b> FBO / 渲染目标 / 合成上下文：第三方只能画在既有 GUI 画布上，
 * 拿不到离屏目标，也没有独立的合成阶段控制。
 * 也<b>不保证</b>第三方绘制的正确性——回调抛出的异常由 {@link OverlayManager} 逐层隔离并限频记错，
 * 画面效果由实现方自负。
 *
 * <p>线程契约：仅客户端主线程（渲染帧）；跨线程调用方须自行 marshal 到主线程。
 * 稳定性：{@code api} 包只增不删，语义不变；破坏性变更只允许发生在 {@code spi} 包与内部实现。
 */
public final class OverlayApi {

    /** 绘制回调：由我们的合成序列每帧调用；实现不得抛异常（内部已兜底，但仍要求）。 */
    @FunctionalInterface
    public interface LayerRenderer {
        void render(GuiGraphics graphics, int screenWidth, int screenHeight);
    }

    /** 开放图层的句柄：可改 z 序、可主动关。 */
    public static final class Handle {

        private final HandleImpl impl;

        private Handle(HandleImpl impl) {
            this.impl = impl;
        }

        /** 当前 z 序（数值越小越先绘制）。 */
        public int zIndex() {
            return impl.currentZIndex();
        }

        /**
         * 改 z 序：立刻在 {@link OverlayManager} 中重新排序（下一帧生效）。
         * 已关闭的句柄上是空操作。
         */
        public void setZIndex(int zIndex) {
            impl.setZIndex(zIndex);
        }

        /** 层是否还开着（主动关层 / 所属播放实例停止都会让它变 {@code false}）。 */
        public boolean isOpen() {
            return impl.isOpen();
        }

        /** 主动关层：幂等，已关过再关是空操作；同时把层从 {@link OverlayManager} 摘出。 */
        public void close() {
            impl.close();
        }
    }

    /**
     * 开层或更新已有层：同一 {@code (owner, key)} 重复调用时只改 z 序与渲染器，不重建层。
     *
     * @param owner    归属 token，必须传播放实例 token（第三方轨道用 {@code TrackContext.owner()}）；身份比较
     * @param key      该轨道内部稳定的层标识（非空白），与 owner 一起构成唯一键
     * @param zIndex   层序：越小越先绘制（0 = 画幅层，10 = 脚本层默认，10+ = 显式压层）
     * @param renderer 每帧绘制回调
     * @return 该 {@code (owner, key)} 对应的同一个句柄实例，可长期留存
     * @throws IllegalArgumentException owner / key 为 {@code null} 或 key 空白、renderer 为 {@code null}（编程错误，不静默丢弃）
     */
    public static Handle openLayer(Object owner, String key, int zIndex, LayerRenderer renderer) {
        requireOwner(owner);
        requireKey(key);
        if (renderer == null) {
            throw new IllegalArgumentException("renderer 不能为 null：层需要绘制回调");
        }
        Key id = new Key(owner, key);
        HandleImpl existing = LAYERS.get(id);
        if (existing != null && existing.isOpen()) {
            existing.retarget(zIndex, renderer);
            return existing.handle();
        }
        HandleImpl created = new HandleImpl(id, owner, zIndex, renderer);
        // 先进管理器再登记：静态表里不会出现“还没进合成序列”的层。
        OverlayManager.INSTANCE.addLayer(created);
        LAYERS.put(id, created);
        return created.handle();
    }

    /** 关层：不存在返回 false；已关过再关幂等返回 false。 */
    public static boolean closeLayer(Object owner, String key) {
        if (owner == null || key == null || key.isBlank()) return false;
        Key id = new Key(owner, key);
        HandleImpl impl = LAYERS.get(id);
        if (impl == null) return false;
        if (!impl.isOpen()) {
            LAYERS.remove(id, impl);   // 僵尸条目兜底（正常路径上 close() 已摘表）
            return false;
        }
        // removeLayer 命中即摘出并回调 close()（close 里再摘一次表，幂等）。
        if (!OverlayManager.INSTANCE.removeLayer(impl)) impl.close();
        return true;
    }

    /**
     * 关掉该 owner 的全部层；返回关闭数量。
     *
     * <p>只关通过本 API 开的层：内置 overlay（画幅 / 字幕 / 转场遮罩）由播放实例退场时的
     * {@code OverlayManager.removeLayersOf(instance)} 统一收拾，避免第三方误调把内置层拆掉。
     * 先拷贝键集再逐个关（每个 close() 都会改写静态表）。
     */
    public static int closeLayers(Object owner) {
        if (owner == null) return 0;
        List<Key> keys = new ArrayList<>();
        for (Key id : LAYERS.keySet()) {
            if (id.owner() == owner) keys.add(id);
        }
        int closed = 0;
        for (Key id : keys) {
            HandleImpl impl = LAYERS.get(id);
            if (impl == null) continue;
            boolean inManager = OverlayManager.INSTANCE.removeLayer(impl);
            if (!inManager) impl.close();   // 管理器侧已无此层：兜底关层摘表
            if (!impl.isOpen()) closed++;
        }
        return closed;
    }

    /** 查询是否已开（表里有且句柄仍开着）。 */
    public static boolean isOpen(Object owner, String key) {
        if (owner == null || key == null || key.isBlank()) return false;
        HandleImpl impl = LAYERS.get(new Key(owner, key));
        return impl != null && impl.isOpen();
    }

    private OverlayApi() {}

    // ========== 内部：唯一登记表 ==========

    /** (owner, key) → 层实现。owner 按对象身份比较（播放实例没有可靠的 equals）。 */
    private static final Map<Key, HandleImpl> LAYERS = new ConcurrentHashMap<>();

    /**
     * 静态表的键：{@code owner} 按身份（{@code ==}）比较，{@code key} 按字符串相等。
     * 等价于 {@code IdentityHashMap} 语义，但键是复合的——两端各用一半：owner 走
     * {@link System#identityHashCode(Object)}，key 走字符串哈希。
     */
    private record Key(Object owner, String key) {

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key that)) return false;
            return owner == that.owner && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(owner) * 31 + key.hashCode();
        }
    }

    private static void requireOwner(Object owner) {
        if (owner == null) {
            throw new IllegalArgumentException(
                    "owner 不能为 null：第三方轨道必须传播放实例 token（TrackContext.owner()），实例停止时据此连带释放");
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为 null 或空白：它用于在同一 owner 内区分多个层");
        }
    }

    /** 层实现：注册进 {@link OverlayManager} 的 {@link OverlayLayer}，句柄只是它的对外门面。 */
    private static final class HandleImpl implements OverlayLayer {

        private final Key id;
        private final Object owner;
        private final Handle handle = new Handle(this);

        private volatile LayerRenderer renderer;
        private volatile int zIndex;
        private volatile boolean open = true;

        /** 重挂窗口：此期间 {@link #close()} 只摘表不关层（主线程独占写，无需同步）。 */
        private boolean reattaching;

        HandleImpl(Key id, Object owner, int zIndex, LayerRenderer renderer) {
            this.id = id;
            this.owner = owner;
            this.zIndex = zIndex;
            this.renderer = renderer;
        }

        Handle handle() {
            return handle;
        }

        int currentZIndex() {
            return zIndex;
        }

        boolean isOpen() {
            return open;
        }

        // ========== 句柄动作 ==========

        void setZIndex(int newZIndex) {
            retarget(newZIndex, renderer);
        }

        /** 开层方更新：只换 z 序与渲染器，绝不 close（close 会释放旧层）。 */
        void retarget(int newZIndex, LayerRenderer newRenderer) {
            this.renderer = newRenderer;
            if (this.zIndex == newZIndex) return;
            this.zIndex = newZIndex;
            resort();
        }

        /**
         * 让管理器重新排序：{@link OverlayManager} 只在 {@code addLayer} 时排序、没有就地重排入口，
         * 所以「摘出 + 加回」。摘出走 {@code removeLayer} 会回调本层 {@link #close()}——
         * 用 {@code reattaching} 把这次回调降级为只摘表，层本体保持存活。
         */
        private void resort() {
            reattaching = true;
            try {
                OverlayManager.INSTANCE.removeLayer(this);
            } finally {
                reattaching = false;
            }
            if (open) OverlayManager.INSTANCE.addLayer(this);
        }

        // ========== OverlayLayer ==========

        /**
         * 转发给第三方回调。异常隔离与限频由 {@link OverlayManager#render} 逐层完成
         * （每层单独隔离 + 致命错误照常上抛），本层不重复兜底。
         */
        @Override
        public void render(GuiGraphics graphics, int screenWidth, int screenHeight) {
            LayerRenderer target = renderer;
            if (target == null) return;
            target.render(graphics, screenWidth, screenHeight);
        }

        @Override
        public boolean isVisible() {
            return open;
        }

        @Override
        public int getZIndex() {
            return zIndex;
        }

        @Override
        public Object owner() {
            return owner;
        }

        @Override
        public void reset() {
            // 无内部状态可重置：层的内容全由 renderer 每帧决定。
        }

        @Override
        public void close() {
            if (reattaching) return;                 // 重挂窗口：保留层本体
            if (!open) {
                LAYERS.remove(id, this);
                return;
            }
            open = false;
            LAYERS.remove(id, this);
            // 句柄被直接关闭时管理器列表里可能还留着本层：摘掉，避免死层常驻。
            // 管理器侧回调（removeLayer / removeLayersOf）走到这里时是 no-op。
            OverlayManager.INSTANCE.removeLayer(this);
        }
    }
}
