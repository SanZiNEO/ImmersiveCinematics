package com.immersivecinematics.immersive_cinematics.camera;

import com.immersivecinematics.immersive_cinematics.proto.QuadrantProto;
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
 * （原型：主 pass + 4 个 lane）。如果各 pass 用不同的 {@code smartCull} 值，它们会互相把共享集合
 * 重建/扩展成不同结果 → 画面在"塌缩 / 完整"之间来回闪。</p>
 *
 * <p>所以这里**每帧只决定一次**（{@code GameRenderer.render} 开头），整帧所有 pass 共用同一个值；
 * 状态切换（进/出实心方块）时强制一次可见集合重建，避免"相机已进墙、画面还是旧集合"的滞后。</p>
 */
public final class CinematicOcclusion {

    private static boolean occlusionOffThisFrame;

    private CinematicOcclusion() {
    }

    /** 每帧开始调用一次（挂在 {@code GameRenderer.render} 的 HEAD）。 */
    public static void beginFrame(Minecraft mc) {
        boolean inside = false;
        if (mc.level != null) {
            if (QuadrantProto.isEnabled()) {
                // 原型：任一 lane 相机在实心方块里 → 本帧整体关掉遮挡剔除（各 pass 共用同一个值）
                for (int i = 0; i < 4 && !inside; i++) {
                    inside = isInsideSolidBlock(mc, QuadrantProto.camera(i).camera());
                }
            } else if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip()) {
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
