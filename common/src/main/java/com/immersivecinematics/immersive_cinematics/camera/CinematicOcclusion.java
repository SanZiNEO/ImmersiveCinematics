package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.client.lane.LaneRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;

/**
 * "相机在实心方块里"时的遮挡剔除决定 —— 原版旁观者语义，但**按帧统一决定**。
 *
 * <p>原版 {@code LevelRenderer.setupRender} 里有一条：
 * <pre>
 * boolean bl3 = this.minecraft.smartCull;
 * if (player.isSpectator() && level.getBlockState(camera.getBlockPosition()).isSolidRender(...)) bl3 = false;
 * </pre>
 * 我们的相机自由穿墙，等价于旁观者，所以套用同一条件。但有一个坑：可见区块集合
 * （{@code renderChunkStorage}）是 <b>LevelRenderer 上的共享状态</b>，而一帧里可能渲染多个 pass
 * （主 pass + N 个 lane）。如果各 pass 用不同的 {@code smartCull} 值，它们会互相把共享集合
 * 重建/扩展成不同结果 → 画面在"塌缩 / 完整"之间来回闪。</p>
 *
 * <p>所以这里**每帧只决定一次**（{@code GameRenderer.render} 开头），整帧所有 pass 共用同一个值
 * ——判定输入是"本帧的任一相机（任一 lane 相机，或主画面模组相机）在实心方块里"；
 * 状态切换（进/出实心方块）时强制一次可见集合重建，避免"相机已进墙、画面还是旧集合"的滞后。</p>
 *
 * <p>长期方向（见 plans/0.3.6/quadrant-prototype-results.md §3.5）：每 lane 独立维护自己的可见集合 /
 * 遮挡剔除状态，那时各 lane 才允许有自己的遮挡行为；只要可见集合还是共享单份，就必须整帧统一。</p>
 */
public final class CinematicOcclusion {

    private static boolean occlusionOffThisFrame;

    private CinematicOcclusion() {
    }

    /** 每帧开始调用一次（挂在 {@code GameRenderer.render} 的 HEAD）。 */
    public static void beginFrame(Minecraft mc) {
        boolean inside = false;
        if (mc.level != null) {
            LaneRenderer renderer = LaneRenderer.INSTANCE;
            if (renderer.hasActiveLanes()) {
                // 多 lane：任一 lane 相机在实心方块里 → 本帧整体关掉遮挡剔除（各 pass 共用同一个值）
                inside = renderer.isAnyCameraInsideSolidBlock(mc);
            }
            // 主画面（模组相机）与 lane 共用同一份可见区块集合 → 整帧必须同一个值
            if (!inside && CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip()) {
                inside = isInsideSolidBlock(mc, mc.gameRenderer.getMainCamera());
            }
        }
        if (inside != occlusionOffThisFrame) {
            occlusionOffThisFrame = inside;
            // 进/出实心方块：强制一次可见集合重建（否则要等相机再移动 8 格才会重建）
            LevelRenderer levelRenderer = mc.levelRenderer;
            if (levelRenderer != null) {
                levelRenderer.needsUpdate();
            }
        }
    }

    /** 本帧是否把 {@code smartCull} 读成 false（= 原版"旁观者在实心方块里"的效果）。 */
    public static boolean isOcclusionOffThisFrame() {
        return occlusionOffThisFrame;
    }

    private static boolean isInsideSolidBlock(Minecraft mc, Camera camera) {
        if (camera == null) {
            return false;
        }
        BlockPos pos = camera.getBlockPosition();
        return mc.level.getBlockState(pos).isSolidRender(mc.level, pos);
    }
}
