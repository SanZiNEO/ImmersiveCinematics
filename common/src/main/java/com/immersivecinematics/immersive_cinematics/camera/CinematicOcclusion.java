package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;

/**
 * 遮挡剔除的整帧统一决策（回落路径）：有活跃 lane 且 per-lane 剔除不可用时，整帧把 {@code smartCull} 读成
 * {@code false}——BFS 退化为「视距内全部区块、无遮挡剪枝」，各 pass 仍各自 {@code applyFrustum} 视锥过滤。
 *
 * 与 per-lane 路径互斥：per-lane 生效（开关 on + 有活跃 lane + 非优化模组 + 槽 mixin 在位）时本标志恒
 * {@code false}，判定交回逐 pass（{@code LevelRendererMixin} 读该 pass 的相机）；两制同时改写 {@code smartCull}
 * 会互相打架。两条路径共用的一项职责：活跃 ↔ 非活跃切换的那一帧强制一次可见集合重建
 * （{@link LevelRenderer#needsUpdate()}），否则集合与网格中心要等相机再移动 8 格才按新口径重建。
 *
 * 代价（仅回落路径）：没有遮挡剪枝，绘制与区块编译量上升。判定口径、取舍与删除时点见
 * plans/0.3.6/multi-camera-rendering.md §12.8 / §12.9.3。
 */
public final class CinematicOcclusion {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本帧是否把 {@code smartCull} 读成 false（回落路径；per-lane 生效时恒 false）。 */
    private static boolean occlusionOffThisFrame;

    /** 上一帧是否有活跃 lane（活跃 ↔ 非活跃切换时强制一次可见集合重建）。 */
    private static boolean lanesActiveLastFrame;

    private CinematicOcclusion() {
    }

    /** 每帧开始调用一次（挂在 {@code GameRenderer.render} 的 HEAD，lane 注册之后）。 */
    public static void beginFrame(Minecraft mc) {
        boolean hasLanes = mc.level != null && LaneRenderer.INSTANCE.hasActiveLanes();
        boolean perLane = hasLanes && LaneRenderer.isPerLaneCullActive(mc);
        if (hasLanes != lanesActiveLastFrame) {
            lanesActiveLastFrame = hasLanes;
            // 起 / 停播各一次：网格中心在「最上层 lane」与玩家之间跳变，集合与网格都是惰性重建的。
            LevelRenderer levelRenderer = mc.levelRenderer;
            if (levelRenderer != null) {
                levelRenderer.needsUpdate();
            }
            if (hasLanes) {
                LOGGER.info(perLane
                        ? "[lane] 有活跃 lane：per-lane 可见集合生效（逐 lane 独立候选集 / 可见集合 / 门闩，"
                                + "遮挡剪枝按各 pass 的相机判定）"
                        : "[lane] 有活跃 lane：整帧关闭遮挡剔除（smartCull=false）——可见集合退化为视距内全部区块"
                                + "（无遮挡剪枝），代价是绘制 / 区块编译量上升；停播后自动恢复原版判定");
            } else {
                LOGGER.info("[lane] 无活跃 lane：遮挡剔除交回原版判定（smartCull 恢复），"
                        + "可见集合 / 网格中心回玩家相机口径");
            }
        }
        occlusionOffThisFrame = hasLanes && !perLane;
    }

    /** 本帧是否把 {@code smartCull} 读成 false（回落路径）。 */
    public static boolean isOcclusionOffThisFrame() {
        return occlusionOffThisFrame;
    }
}
