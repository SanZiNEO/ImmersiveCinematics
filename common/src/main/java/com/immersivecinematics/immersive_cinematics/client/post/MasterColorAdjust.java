package com.immersivecinematics.immersive_cinematics.client.post;

/**
 * master 画面颜色调整的帧内状态 —— 数据层（{@code script.AdjustTrackPlayer}）与
 * 渲染层（{@link ColorAdjustPass}）之间的唯一交接点。
 *
 * <h2>发布 / 取走</h2>
 * <ul>
 *   <li><b>写</b>：{@code AdjustTrackPlayer.onRenderFrame} 每渲染帧把当前片段的插值结果
 *       {@link #publish} 一次；没有活跃片段时<b>不发布</b>（{@link #clear} 只用于播放停止）。</li>
 *   <li><b>读</b>：{@link ColorAdjustPass} 在合成输出上 {@link #consume} 一次，<b>取走即清空</b>——
 *       所以「本帧没人发布」自然等于「本帧不调色」，不依赖任何结束时的清理时序
 *       （发布每帧都发生，取走也每帧都发生，二者同帧配对）。</li>
 * </ul>
 *
 * <p>{@link #publish} 把恒等参数（35 个标量通道全 0、10 条曲线强度全 1、无 LUT 或 LUT 强度 0）
 * 规整为「无调整」，因此<b>活跃但无效果</b>的 ADJUST 片段同样不产生任何 pass、任何状态改动。</p>
 *
 * <h2>多轨道 / 多实例</h2>
 * 同帧多个 ADJUST 轨道：<b>后发布者覆盖先发布者</b>（轨道层级后面的在上，与 lane 合成顺序同一口径）。
 * 多个播放实例各写一份 master 的合并语义仍是开放问题（见 plans/0.3.6/screen-color-adjust.md §5），
 * 本版本至多 1 个活跃实例，行为 = 该实例的最后一个 ADJUST 轨道。
 *
 * <h2>线程</h2>
 * 发布在渲染线程（{@code CameraManager.onRenderFrame} 的调用链里）、取走也在渲染线程
 * （{@code GameRenderer.renderLevel} 返回之后）——单线程配对；字段用 {@code volatile} 只是
 * 防止编辑器预览等旁路从其它线程触发发布时的可见性问题，不加锁。
 */
public final class MasterColorAdjust {

    /** 全局单例（渲染线程访问）。 */
    public static final MasterColorAdjust INSTANCE = new MasterColorAdjust();

    /** 本帧待消费的调整参数；{@code null} = 本帧无调整。 */
    private volatile ColorAdjustParams pending;

    private MasterColorAdjust() {
    }

    /** 发布本帧的调整参数；{@code null} 或恒等参数都视为「本帧无调整」。 */
    public void publish(ColorAdjustParams params) {
        this.pending = (params == null || params.isIdentity()) ? null : params;
    }

    /**
     * 取走本帧的调整参数并清空。
     *
     * @return 本帧的调整参数；无调整（没人发布 / 参数恒等）返回 {@code null}
     */
    public ColorAdjustParams consume() {
        ColorAdjustParams params = this.pending;
        this.pending = null;
        return params;
    }

    /** 丢弃尚未取走的参数（播放停止时调用，避免残留一帧）。 */
    public void clear() {
        this.pending = null;
    }
}
