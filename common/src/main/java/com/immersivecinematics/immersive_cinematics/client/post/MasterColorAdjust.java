package com.immersivecinematics.immersive_cinematics.client.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * master 画面颜色调整的帧内状态：数据层（{@code script.AdjustTrackPlayer}）与渲染层（{@link ColorAdjustPass}）
 * 之间的唯一交接点。
 *
 * {@link #publish} 每渲染帧按实例发布一次，{@link #consume} 在合成输出上取走一次并清空——两者同帧配对，
 * 「本帧没人发布」自然等于「本帧不调色」。恒等参数在 {@link #publish} 归一化为「该实例本帧无调整」（移除条目）。
 * 实例内：同帧同一实例的多次发布 = 后发布者覆盖先发布者（覆盖只换值、不改集合位置）。
 * 跨实例：{@link #consume} 返回有序列表，顺序 = 本帧发布顺序 = 实例启动顺序（先启动的在前 = 下层），
 * 渲染侧逐套顺序施加（前一套输出 = 后一套的基画面）。
 * 规则调整层（{@code adjust_set}）跨帧存活、片段结束 / 停播清除，{@link #consume} 时紧随其所属实例的轨道层之后。
 * 线程：发布与取走都在渲染线程（单线程配对）；{@code synchronized} 只兜编辑器预览等旁路发布。
 */
public final class MasterColorAdjust {

    /** 全局单例（渲染线程访问）。 */
    public static final MasterColorAdjust INSTANCE = new MasterColorAdjust();

    /**
     * 本帧待消费的调整参数，键 = 实例标识（按身份比较）；插入序 = 本帧发布顺序（同一实例重复发布只覆盖值）。
     */
    private final Map<Object, ColorAdjustParams> pending = new LinkedHashMap<>();

    /**
     * 实例级规则调整层（{@code adjust_set}，一实例至多一条）：触发即覆盖值、跨帧存活，与一帧生命周期的
     * {@link #pending} 分开记账；{@link #consume} 时紧随其所属实例的轨道层之后输出。
     */
    private final Map<Object, ColorAdjustParams> ruleLayers = new LinkedHashMap<>();

    private MasterColorAdjust() {
    }

    /**
     * 发布某个实例本帧的调整参数；{@code null} 或恒等参数视为「该实例本帧无调整」（移除条目，不影响其它实例）。
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
     * 发布某个实例的规则调整层（{@code adjust_set} 触发时；一实例至多一条 = 触发即覆盖值）；
     * 恒等参数视为「无规则层」（移除条目）。
     *
     * @param instanceKey 实例标识（与 {@link #publish} 同一个对象：本实例的播放器）
     * @param params      规则层参数（未写通道取缺省 = 不改变画面）
     */
    public synchronized void publishRuleLayer(Object instanceKey, ColorAdjustParams params) {
        if (instanceKey == null) {
            return;
        }
        if (params == null || params.isIdentity()) {
            this.ruleLayers.remove(instanceKey);
        } else {
            this.ruleLayers.put(instanceKey, params);
        }
    }

    /**
     * 清除某个实例的规则调整层（片段结束 / 引擎重建 / 停播；其它实例不受影响）。
     *
     * @param instanceKey 实例标识（与 {@link #publishRuleLayer} 同一个对象）
     */
    public synchronized void clearRuleLayer(Object instanceKey) {
        if (instanceKey != null) {
            this.ruleLayers.remove(instanceKey);
        }
    }

    /**
     * 帧首复位（实例循环之前）：丢弃两帧之间产生的条目——预执行首帧等旁路发布先于循环插入，会让顺序失真一帧；
     * 规则调整层不在这里清（跨帧存活）。
     */
    public synchronized void beginFrame() {
        this.pending.clear();
    }

    /**
     * 取走本帧全部实例的调整参数并清空（与 {@link #publish} 同帧配对）。
     *
     * @return 按实例顺序（先启动的在前 = 下层）排列的参数列表；无调整返回空列表。规则调整层紧随
     *         所属实例的轨道层之后，无轨道层的实例单独成层
     */
    public synchronized List<ColorAdjustParams> consume() {
        if (this.pending.isEmpty() && this.ruleLayers.isEmpty()) {
            return List.of();
        }
        List<ColorAdjustParams> layers = new ArrayList<>(this.pending.size() + this.ruleLayers.size());
        for (Map.Entry<Object, ColorAdjustParams> entry : this.pending.entrySet()) {
            layers.add(entry.getValue());
            ColorAdjustParams rule = this.ruleLayers.get(entry.getKey());
            if (rule != null) {
                layers.add(rule);
            }
        }
        for (Map.Entry<Object, ColorAdjustParams> entry : this.ruleLayers.entrySet()) {
            if (!this.pending.containsKey(entry.getKey())) {
                layers.add(entry.getValue());
            }
        }
        this.pending.clear();
        return layers;
    }

    /**
     * 丢弃指定实例尚未取走的参数（该实例播放停止时调用，避免残留一帧；其它实例不受影响）。
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
