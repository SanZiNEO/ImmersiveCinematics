package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 一条 CAMERA 片段的相机租约：不可变句柄，一次片段调用 = 一个租约。
 *
 * <p>租约由 {@link CameraLeasePool} 在片段首次出现时创建，片段结束 / 实例停止 / 世界卸载时归还。
 * {@code slotId + generation} 是视角状态的唯一身份（{@link #viewKey()}），{@link #cull()} 是该租约独占的
 * 剔除槽；{@code dimension} 非空时视角键追加 {@code @维度}，不同维度不共用历史 / 剔除状态。</p>
 *
 * <p>句柄只读：字段在构造后不变。归还后的旧句柄不会命中池里的新租约（{@link CameraLeasePool#release}
 * 按对象身份查找登记表），代际在每次重新分配槽位时自增，旧视角键永久失效。</p>
 *
 * @param owner     持有租约的播放实例（身份比较）
 * @param clip      产出该租约的 CAMERA 片段（身份比较）
 * @param dimension 该片段的渲染维度；空串 = 玩家当前维度
 * @param slotId    池槽编号（视角身份的一部分，不参与合成排序）
 * @param generation 槽位代际（每次重新分配自增）
 * @param viewKey   视角状态键 = {@code "lease" + slotId + "g" + generation}，维度非空时追加 {@code "@" + dimension}
 * @param cull      该租约独占的剔除槽
 */
public record CameraLease(PlaybackInstance owner, Clip clip, String dimension, int slotId, long generation,
                          String viewKey, LaneCullSlot cull) {
}
