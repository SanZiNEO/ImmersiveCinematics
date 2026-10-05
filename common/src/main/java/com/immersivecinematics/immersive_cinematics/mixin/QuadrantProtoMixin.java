package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.proto.QuadrantProto;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;

/**
 * 多画面压力测试原型（一次性，测完即删；默认关，关闭时零差异）。
 *
 * <p>挂在 {@code GameRenderer.renderLevel}：</p>
 * <ul>
 *   <li>HEAD：记本帧开始时间（用于"主画面耗时"与帧间隔）；</li>
 *   <li>RETURN：主画面渲染完之后，对每个画面各做一遍——<b>整尺寸渲染到共用离屏缓冲，再缩放贴进网格单元</b>：
 *     <pre>
 *     mainRenderTarget 临时指向离屏缓冲 → bindWrite(true)（视口 = 缓冲全尺寸）
 *       → camera.setup(...)（走 CameraMixin 原型分支）
 *       → 视图 PoseStack（XP=xRot、YP=yRot+180）→ roll → setInverseViewRotationMatrix
 *       → getFov（GameRendererMixin 原型分支）→ 投影
 *       → prepareCullFrustum → renderLevel → doEntityOutline（lane 内描边）→ 还原投影
 *       → 恢复 mainRenderTarget → glBlitFramebuffer 缩放到网格单元
 *     </pre>
 *   </li>
 * </ul>
 *
 * <p>模式（{@code -Dicinematics.quadrant} / {@code ICINEMATICS_QUADRANT}）：{@code 1}=只采样（原版画面基线）、
 * {@code 4}=2×2 四画面、{@code 16}=4×4 十六画面；不设=关闭。每次测试跑 30 秒后打印统计并出图，
 * 原始数据表写到 {@code <gameDir>/quadrant-perf/}。</p>
 */
@Mixin(GameRenderer.class)
public abstract class QuadrantProtoMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LightTexture lightTexture;

    @Shadow
    private boolean renderHand;

    @Unique
    private long ic$mainPassStartNs;

    @Invoker("getProjectionMatrix")
    abstract Matrix4f ic$getProjectionMatrix(double d);

    @Invoker("getFov")
    abstract double ic$getFov(Camera camera, float partialTick, boolean useFOVSetting);

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void ic$quadrantFrameStart(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        if (!QuadrantProto.isEnabled()) {
            return;
        }
        this.ic$mainPassStartNs = System.nanoTime();
        QuadrantProto.onFrameStart();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ic$quadrant(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        if (!QuadrantProto.isEnabled()) {
            return;
        }
        Minecraft mc = this.minecraft;
        if (mc.level == null || mc.player == null) {
            return;
        }

        // 原型期间：不画手、不画 HUD（出图要干净）
        this.renderHand = false;
        mc.options.hideGui = true;

        QuadrantProto.onMainPass(System.nanoTime() - this.ic$mainPassStartNs);
        QuadrantProto.update(mc.player, partialTick);

        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        int views = QuadrantProto.views();
        int grid = QuadrantProto.grid();
        int cellW = views > 0 ? w / grid : w;
        int cellH = views > 0 ? h / grid : h;

        for (int i = 0; i < views; ++i) {
            QuadrantProto.ProtoCamera proto = QuadrantProto.camera(i);
            Camera camera = proto.camera();
            RenderTarget fbo = QuadrantProto.target(w, h);

            long laneStartNs = System.nanoTime();

            // 渲染期间让所有 getMainRenderTarget() 引用都指向离屏缓冲
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(fbo);
            // 整个 lane 期间置位：① setupRender 的"相机在实心方块里"判定要用；② 描边上屏分流要用
            QuadrantProto.setLaneRendering(true);
            try {
                fbo.bindWrite(true);   // 绑定 FBO + 视口 = FBO 全尺寸（按正常整屏尺寸渲染）

                // 🎥 我们的相机接管：Camera.setup → CameraMixin 原型分支读取该实例的模组相机状态
                camera.setup(mc.level, mc.player,
                        !mc.options.getCameraType().isFirstPerson(),
                        mc.options.getCameraType().isMirrored(),
                        partialTick);

                // 视图矩阵：复刻 GameRenderer.renderLevel
                PoseStack ps = new PoseStack();
                ps.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
                ps.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180.0F));

                // roll：复刻 GameRendererMixin（本原型 roll 恒为 0，保留链路）
                float roll = proto.props().getRoll();
                if (roll != 0.0F) {
                    ps.mulPose(Axis.of(camera.getLookVector()).rotationDegrees(-roll));
                }
                RenderSystem.setInverseViewRotationMatrix(new Matrix3f(ps.last().normal()).invert());

                // fov：走 GameRendererMixin 原型分支（模组相机自己的 fov/zoom）
                double fov = this.ic$getFov(camera, partialTick, true);
                Matrix4f projection = this.ic$getProjectionMatrix(fov);
                Matrix4f cullProjection = this.ic$getProjectionMatrix(Math.max(fov, mc.options.fov().get()));
                mc.levelRenderer.prepareCullFrustum(ps, camera.getPosition(), cullProjection);
                mc.levelRenderer.renderLevel(ps, partialTick, nanoTime, false, camera, mc.gameRenderer,
                        this.lightTexture, projection);

                // 描边（发光）：renderLevel 里的后处理链已把描边合成进共享 entityTarget；
                // 此刻 FBO 还绑着 → 立刻贴进"本画面"（原版是等渲染完再整屏 1:1 贴，那样会跑到画面外面）。
                mc.levelRenderer.doEntityOutline();
                // doEntityOutline → blitToScreen 会把全局投影改成正交矩阵，必须还原
                RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            } finally {
                QuadrantProto.setLaneRendering(false);
                ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
            }

            // 上屏：把整尺寸画面缩放贴进网格单元（第 0 行在顶部；GL 原点在左下）
            int col = i % grid;
            int row = i / grid;
            int vx = col * cellW;
            int vy = (grid - 1 - row) * cellH;
            int dstW = (col == grid - 1) ? (w - vx) : cellW;
            int dstH = (row == 0) ? (h - (grid - 1) * cellH) : cellH;
            ic$blitScaled(fbo, main, vx, vy, dstW, dstH);

            QuadrantProto.onLaneRendered(i, System.nanoTime() - laneStartNs);
        }

        RenderSystem.viewport(0, 0, w, h);

        if (QuadrantProto.runFinished()) {
            QuadrantProto.logSummary();
            // 出图：整屏一张 + 每画面裁一张
            Screenshot.grab(mc.gameDirectory, "quadrant-main.png", main, component -> {
            });
            NativeImage full = Screenshot.takeScreenshot(main);
            Util.ioPool().execute(() -> {
                try {
                    File dir = new File(mc.gameDirectory, "quadrant-captures");
                    dir.mkdirs();
                    for (int i = 0; i < views; ++i) {
                        int sx = (i % grid) * cellW;
                        int sy = (i / grid) * cellH;
                        NativeImage sub = new NativeImage(cellW, cellH, false);
                        for (int y = 0; y < cellH; ++y) {
                            for (int x = 0; x < cellW; ++x) {
                                sub.setPixelRGBA(x, y, full.getPixelRGBA(sx + x, sy + y));
                            }
                        }
                        sub.writeToFile(new File(dir, "quadrant-" + QuadrantProto.name(i) + ".png"));
                        sub.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    full.close();
                }
            });
        }
    }

    /** 把 {@code src} 整幅线性缩放贴到 {@code dst} 的 (x, y, w, h) 矩形（GL 原点在左下）。 */
    private static void ic$blitScaled(RenderTarget src, RenderTarget dst, int x, int y, int w, int h) {
        GlStateManager._glBindFramebuffer(36008, src.frameBufferId);   // GL_READ_FRAMEBUFFER
        GlStateManager._glBindFramebuffer(36009, dst.frameBufferId);   // GL_DRAW_FRAMEBUFFER
        GlStateManager._glBlitFrameBuffer(0, 0, src.width, src.height,
                x, y, x + w, y + h, 16384 /* GL_COLOR_BUFFER_BIT */, 9729 /* GL_LINEAR */);
        GlStateManager._glBindFramebuffer(36160, dst.frameBufferId);   // GL_FRAMEBUFFER
    }
}
