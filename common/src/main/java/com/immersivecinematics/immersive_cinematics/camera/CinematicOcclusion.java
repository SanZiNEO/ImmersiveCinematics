package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;

/**
 * 遮挡剔除的整帧统一决策 —— <b>lane 活跃期间整帧强制 {@code smartCull=false}</b>。
 *
 * <h2>为什么</h2>
 * 可见区块集合（{@code LevelRenderer.renderChunkStorage} + {@code renderChunksInFrustum}）是
 * <b>单份共享状态</b>，且由「某个 pass」的相机经 BFS（{@code updateRenderChunks}）整体重建替换：
 * {@code smartCull=true} 时 BFS 带遮挡剪枝（沿 6 个方向只走「面与面能互相看见」的邻居），剪枝结果
 * 只对<b>播种相机</b>成立——一帧里主 pass + N 个 lane pass 的机位各不相同，被剪掉的 16 格区块在所有
 * 其它机位就整体缺失，表现为副画面里出现<b>倾斜的裁切矩形</b>（缺块边界沿区块网格斜切，且随
 * 「哪个 pass 当播种相机」间歇出现）。
 *
 * <h2>做法</h2>
 * 有活跃 lane 的帧整帧 {@code smartCull=false}（{@code LevelRendererMixin} 在 {@code setupRender}
 * 的 HEAD / RETURN 包夹）——BFS 退化为「视距内全部区块、无遮挡剪枝」，各 pass 仍各自 {@code applyFrustum}
 * 视锥过滤（lane pass 还会绕开原版朝向门闩强制重刷，见 {@code LaneRenderer.renderLane}）。
 * <p>判定口径 = <b>有活跃 lane 相机</b>（{@link LaneRenderer#hasActiveLanes()}）：每帧在
 * {@code GameRenderer.render} 的 HEAD 决定一次（{@link #beginFrame(Minecraft)}，排在 lane 注册之后，
 * 读的就是本帧 lane 表）。状态切换（起播 / 停播）时强制一次可见集合重建
 * （{@link LevelRenderer#needsUpdate()}），否则要等相机再移动 8 格才会用新的 {@code smartCull} 重建。</p>
 *
 * <p><b>代价</b>：播放期间（仅 lane 活跃的帧）绘制与区块编译量上升——没有遮挡剪枝，视距内全部区块都进
 * 可见集合；停播后立即恢复原版行为（下一帧起 {@code smartCull} 交回原版判定：原版对玩家相机自己的
 * 「旁观者在实心方块里 → 关掉遮挡剔除」判定不受本类影响）。缓解口径与成本见
 * {@code plans/0.3.6/multi-camera-rendering.md} §12.8。</p>
 *
 * <p><b>为什么不做逐 lane</b>：逐 lane 独立可见集合（每 lane 自己的 {@code renderChunkStorage} + BFS +
 * frustum）是长期方向；只要可见集合还是共享单份，一帧内就必须整帧统一（各 pass 用不同值会互相重建
 * → 画面在「塌缩 / 完整」之间来回闪，见 {@code quadrant-prototype-results.md} §3.5）。</p>
 */
public final class CinematicOcclusion {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本帧是否强制把 {@code smartCull} 读成 false（= lane 活跃期间关闭遮挡剪枝）。 */
    private static boolean occlusionOffThisFrame;

    private CinematicOcclusion() {
    }

    /** 每帧开始调用一次（挂在 {@code GameRenderer.render} 的 HEAD，lane 注册之后）。 */
    public static void beginFrame(Minecraft mc) {
        boolean off = mc.level != null && LaneRenderer.INSTANCE.hasActiveLanes();
        if (off != occlusionOffThisFrame) {
            occlusionOffThisFrame = off;
            // 起 / 停播各一条：可用它确认本帧是否按预期关掉了遮挡剔除（F3 的 "C: x/y (s)" 里 (s) 同理）。
            LOGGER.info(off
                    ? "[lane] 有活跃 lane：整帧关闭遮挡剔除（smartCull=false）——可见集合退化为视距内全部区块"
                            + "（无遮挡剪枝），代价是绘制 / 区块编译量上升；停播后自动恢复原版判定"
                    : "[lane] 无活跃 lane：遮挡剔除交回原版判定（smartCull 恢复）");
            // 进 / 出该状态：强制一次可见集合重建（否则要等相机再移动 8 格才会用新的 smartCull 重建）
            LevelRenderer levelRenderer = mc.levelRenderer;
            if (levelRenderer != null) {
                levelRenderer.needsUpdate();
            }
        }
    }

    /** 本帧是否把 {@code smartCull} 读成 false（= lane 活跃期间整帧关闭遮挡剪枝）。 */
    public static boolean isOcclusionOffThisFrame() {
        return occlusionOffThisFrame;
    }
}
