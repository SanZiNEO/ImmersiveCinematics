package com.immersivecinematics.immersive_cinematics.spi;

/**
 * 第三方轨道的播放回调：由注册方实现，{@code TrackRegistry} 在片段活跃期内逐帧驱动。
 *
 * <p>调用线程 = <b>客户端渲染帧主线程</b>。实现里不得做阻塞 IO / 长耗时计算；
 * 每帧读取 {@link TrackContext} 的采样值并写入自己的渲染目标。</p>
 *
 * <p>{@link TrackContext} 是<b>帧内临时视图</b>：只在该回调的调用期内有效，实现<b>不得</b>把它存进字段
 * 跨帧使用（同一轨道实例每帧拿到的是同一个对象，但内容只在下一帧 {@code tick} 前被刷新）。</p>
 *
 * <p>生命周期：片段进入活跃窗 → {@link #start}；之后每渲染帧 {@link #tick}；片段离开活跃窗 / 停播 /
 * seek / 循环折返 / 脚本替换 → {@link #stop}（随后下一段活跃片段会重新 {@link #start}）。
 * 三个方法都可能因回调抛异常被平台记录并跳过，因此实现应自行保证资源释放。</p>
 */
public interface TrackPlayer {

    /**
     * 片段开始驱动：初始化本片段的资源。每个活跃期只调用一次。
     *
     * @param ctx 本帧上下文（只在本调用期内有效）
     */
    void start(TrackContext ctx);

    /**
     * 每渲染帧驱动：读 {@link TrackContext} 的采样值并写入渲染目标。
     *
     * @param ctx 本帧上下文（已按当前执行局部时间刷新）
     */
    void tick(TrackContext ctx);

    /**
     * 片段结束驱动：释放 {@link #start} 申请的资源。重复调用不得抛异常。
     *
     * @param ctx 停止时的上下文（与最后一次 {@code tick} 同一对象）
     */
    void stop(TrackContext ctx);
}
