package com.immersivecinematics.immersive_cinematics.spi;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 第三方轨道播放回调的帧内视图：当前片段 + 执行事实 + 按对象路径取采样值。
 *
 * <p>取值语义：</p>
 * <ul>
 *   <li>{@link #clip()} 只保证<b>片段级只读字段</b>（{@code start_time} / {@code duration} / {@code data} /
 *       对象声明等）；不要借它读逐帧状态——逐帧状态一律走本接口的 {@code f/i/b/s/struct}。</li>
 *   <li>{@link #present(String)} = 该路径本帧是否有值（对 {@code nullable_float} 即「是否锁住」）。</li>
 *   <li>未在轨道 schema 里声明的 {@code path} 返回 {@code 0f} / {@code 0} / {@code false} / {@code null} /
 *       {@code null}，<b>不抛异常</b>；已声明但作者没写、也没有 schema 缺省的路径同样返回这些缺省值
 *       （{@link #present(String)} 为 {@code false}）。</li>
 *   <li>{@link #owner()} = 播放实例 token（与 {@code OverlayApi} 等按实例过滤的层系统同一个 token）。</li>
 * </ul>
 *
 * <p>路径 → 槽位在<b>轨道注册期</b>编译（{@code TrackRegistry.pathSlots}），运行期只做数组下标访问，
 * 因此这里不接受动态路径集合。</p>
 *
 * <p>本对象是帧内临时视图：只在该帧的回调调用期内有效，不得跨帧持有。</p>
 */
public interface TrackContext {

    /**
     * 当前驱动的片段。
     *
     * @return 片段（非 {@code null}）
     */
    Clip clip();

    /**
     * 当前执行内的片段局部时间（秒，已由执行层归一化到执行窗口内）。
     *
     * @return 执行局部时间
     */
    double executionLocalTime();

    /**
     * 当前是该片段的第几次执行（从 0 起；无限循环片段每圈 +1）。
     *
     * @return 执行序号
     */
    int executionIndex();

    /**
     * 播放实例归属 token（用于按实例过滤的层系统，如 {@code OverlayApi}）。
     *
     * @return 实例 token；未接线时为 {@code null}
     */
    Object owner();

    /**
     * 读浮点采样值。
     *
     * @param path 对象路径
     * @return 本帧值；未声明 / 本帧无值时为 {@code 0f}
     */
    float f(String path);

    /**
     * 该路径本帧是否有值。
     *
     * @param path 对象路径
     * @return 有值返回 {@code true}；未声明或无值返回 {@code false}
     */
    boolean present(String path);

    /**
     * 读整数采样值。
     *
     * @param path 对象路径
     * @return 本帧值；未声明 / 本帧无值时为 {@code 0}
     */
    int i(String path);

    /**
     * 读布尔采样值。
     *
     * @param path 对象路径
     * @return 本帧值；未声明 / 本帧无值时为 {@code false}
     */
    boolean b(String path);

    /**
     * 读字符串采样值（{@code string} / {@code enum} / {@code ref}）。
     *
     * @param path 对象路径
     * @return 本帧值；未声明 / 本帧无值时为 {@code null}
     */
    String s(String path);

    /**
     * 读 value-only 结构值（{@code struct}）：不可变 {@code Map}/{@code List}/{@code String}/{@code Boolean}/
     * {@code Float}/{@code null}。
     *
     * @param path 对象路径
     * @return 结构值；未声明或不是 {@code struct} 时为 {@code null}
     */
    Object struct(String path);

    /**
     * 当前脚本 id。
     *
     * @return 脚本 id；无脚本时为 {@code "<none>"}
     */
    String scriptId();

    /**
     * 当前播放实例 id。
     *
     * @return 播放实例 id；本地来源（非服务端请求）为空串
     */
    String instanceId();
}
