package com.immersivecinematics.immersive_cinematics.client.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * master 画面颜色调整的帧内状态 —— 数据层（{@code script.AdjustTrackPlayer}）与
 * 渲染层（{@link ColorAdjustPass}）之间的唯一交接点。
 *
 * <h2>发布 / 取走</h2>
 * <ul>
 *   <li><b>写</b>：{@code AdjustTrackPlayer.onRenderFrame} 每渲染帧把当前片段的插值结果按<b>实例</b>
 *       {@link #publish} 一次；没有活跃片段时<b>不发布</b>（{@link #clear} 只用于该实例播放停止）。</li>
 *   <li><b>读</b>：{@link ColorAdjustPass} 在合成输出上 {@link #consume} 一次，<b>取走即清空</b>——
 *       所以「本帧没人发布」自然等于「本帧不调色」，不依赖任何结束时的清理时序
 *       （发布每帧都发生，取走也每帧都发生，二者同帧配对）。</li>
 * </ul>
 *
 * <p>{@link #publish} 把恒等参数（47 个标量通道全 0、10 条曲线强度全 1、无 LUT 或 LUT 强度 0）
 * 规整为「该实例本帧无调整」（从集合里移除该实例的条目），因此<b>活跃但无效果</b>的 ADJUST 片段
 * 同样不产生任何 pass、任何状态改动。</p>
 *
 * <h2>多轨道 / 多实例（A13'：按层级叠加）</h2>
 * <b>实例内</b>：同帧用<b>同一个实例标识</b>的多次发布 = 后发布者覆盖先发布者
 * （轨道层级后面的在上，与 lane 合成顺序同一口径）= 该实例的最后一个 ADJUST 轨道；
 * 覆盖只换值、不改变该实例在集合里的位置。没活跃 clip 的轨道不发布，不会把别的轨道的发布抹掉。
 * <p><b>跨实例</b>：每个实例各占一层，{@link #consume} 返回<b>有序列表</b>——顺序 = 本帧的发布顺序
 * = {@code CameraManager.instances()} 的列表顺序 = 实例启动顺序（先启动的在前 = 下层，后启动的在后
 * = 上层；与 {@code ScriptLaneDriver} 的 lane 平铺同一口径）。渲染侧对列表逐套<b>顺序施加</b>：
 * 前一套的输出就是后一套的基画面（PS 调整层各自持有 + 总体叠加同语义）。</p>
 * <p>集合的生命周期 = <b>一帧</b>：帧首 {@link #beginFrame} 复位（丢帧间残留）、{@link #consume} 取走即清空，
 * 所以插入序就是本帧实例循环的顺序。</p>
 *
 * <h2>线程</h2>
 * 发布在渲染线程（{@code CameraManager.onRenderFrame} 的调用链里）、取走也在渲染线程
 * （{@code GameRenderer.renderLevel} 返回之后）——单线程配对；集合用 {@code synchronized} 保护
 * 只是防止编辑器预览等旁路从其它线程触发发布时的可见性 / 结构破坏问题（每帧只有几次操作，开销可忽略）。
 */
public final class MasterColorAdjust {

    /** 全局单例（渲染线程访问）。 */
    public static final MasterColorAdjust INSTANCE = new MasterColorAdjust();

    /**
     * 本帧待消费的调整参数，键 = 实例标识（按身份比较，同一实例每次传同一个对象）。
     * <p>插入序 = 本帧的发布顺序 = 实例启动顺序（先启动的在前 = 下层）；同一实例重复发布
     * 只覆盖值、不改变位置。</p>
     */
    private final Map<Object, ColorAdjustParams> pending = new LinkedHashMap<>();

    private MasterColorAdjust() {
    }

    /**
     * 发布<b>某个实例</b>本帧的调整参数；{@code null} 或恒等参数都视为「该实例本帧无调整」
     * （移除该实例的条目，不影响其它实例）。
     *
     * @param instanceKey 实例标识（同一实例每次传同一个对象；按身份比较）
     * @param params      该实例当前活跃 ADJUST 轨道的插值结果
     */
    public synchronized void publish(Object instanceKey, ColorAdjustParams params) {
        if (instanceKey == null) {
            return;
        }
        if (params == null || params.isIdentity()) {
            this.pending.remove(instanceKey);
        } else {
            this.pending.put(instanceKey, params);
        }
    }

    /**
     * 帧首复位（{@code GameRenderer.render} 的 HEAD、实例循环之前调用）：丢弃两帧之间产生的条目。
     *
     * <p>实例循环按 {@code CameraManager.instances()} 的列表顺序逐实例发布，插入序因此 = 实例启动顺序；
     * 但播放实例的<b>预执行首帧</b>（{@code ScriptPlayer.start}）等旁路发布可能落在两帧之间，先于循环插入
     * 会让顺序失真一帧——帧首清掉即可保证本帧的顺序只由本帧的循环决定（被清掉的实例在循环里会重新发布，
     * 因为循环覆盖全部在播实例）。</p>
     */
    public synchronized void beginFrame() {
        this.pending.clear();
    }

    /**
     * 取走本帧全部实例的调整参数并清空。
     *
     * @return 按实例顺序（先启动的在前 = 下层）排列的调整参数列表；无调整（没人发布 / 参数恒等）
     *         返回空列表
     */
    public synchronized List<ColorAdjustParams> consume() {
        if (this.pending.isEmpty()) {
            return List.of();
        }
        List<ColorAdjustParams> layers = new ArrayList<>(this.pending.values());
        this.pending.clear();
        return layers;
    }

    /**
     * 丢弃<b>指定实例</b>尚未取走的参数（该实例播放停止时调用，避免残留一帧；其它实例不受影响）。
     *
     * @param instanceKey 实例标识（与 {@link #publish} 同一个对象）
     */
    public synchronized void clear(Object instanceKey) {
        if (instanceKey == null) {
            return;
        }
        this.pending.remove(instanceKey);
    }
}
