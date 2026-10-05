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
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;

/**
 * 四象限同屏原型（一次性，测完即删；默认关，关闭时零差异）。
 *
 * <p>挂在 {@code GameRenderer.renderLevel} 的 RETURN：原版单画面渲染完之后，对四个象限各做一遍
 * ——<b>整尺寸渲染到该象限自己的离屏缓冲，再按 50% 缩放贴进屏幕象限</b>：</p>
 *
 * <pre>
 * 1) 渲染（整尺寸，与正常一帧同一套流程）：
 *    mainRenderTarget 临时指向该象限 FBO → fbo.bindWrite(true)（视口 = FBO 全尺寸）
 *    → camera.setup(...)（走 CameraMixin 原型分支：模组相机状态接管）
 *    → 视图 PoseStack（XP=xRot、YP=yRot+180，复刻 GameRenderer.renderLevel）
 *    → roll（复刻 GameRendererMixin，本原型恒为 0）→ setInverseViewRotationMatrix
 *    → getFov（走 GameRendererMixin 原型分支）→ 投影矩阵
 *    → LevelRenderer.prepareCullFrustum → LevelRenderer.renderLevel
 *    → 恢复 mainRenderTarget
 * 2) 上屏：glBlitFramebuffer 把 FBO 线性缩放贴到象限矩形（右上 +x / 左上 −x / 左下 +z / 右下 −z）
 * </pre>
 *
 * <p>之所以整尺寸渲染再缩放（而不是直接把 viewport 设成象限）：原版 {@code renderLevel} 内部
 * （实体段 entityTarget.clear()、粒子/云/天气 target 切换）会把 GL viewport 重置成整窗尺寸——
 * 直接设象限 viewport 会被它冲掉，导致实体层按整屏尺寸绘制。渲染进 FBO 后这些重置只会落在
 * FBO 自己的尺寸上，各层天然一致。</p>
 *
 * <p>四遍画完后按 {@link QuadrantProto#shouldCapture()} 出图：游戏主图一张 + 每象限裁一张
 * （写 {@code <gameDir>/quadrant-captures/}）。</p>
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

    @Invoker("getProjectionMatrix")
    abstract Matrix4f ic$getProjectionMatrix(double d);

    @Invoker("getFov")
    abstract double ic$getFov(Camera camera, float partialTick, boolean useFOVSetting);

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void ic$quadrant(float partialTick, long nanoTime, PoseStack poseStack, CallbackInfo ci) {
        if (!QuadrantProto.isEnabled()) {
            return;
        }
        Minecraft mc = this.minecraft;
        if (mc.level == null || mc.player == null) {
            return;
        }

        // 原型期间：不画手、不画 HUD（出图要干净的四象限）
        this.renderHand = false;
        mc.options.hideGui = true;

        // 每帧刷新四个相机的模组相机状态（位置 + yaw/pitch/roll/fov/zoom）
        QuadrantProto.update(mc.player, partialTick);

        // 临时测试准备：创造模式 + 玩家周围召唤各种实体（一次性，随原型一起删）
        QuadrantProto.setupTestEntities(mc);

        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        int halfW = w / 2;
        int halfH = h / 2;

        for (int i = 0; i < 4; ++i) {
            QuadrantProto.ProtoCamera proto = QuadrantProto.camera(i);
            Camera camera = proto.camera();
            RenderTarget fbo = QuadrantProto.target(i, w, h);

            // 渲染期间让所有 getMainRenderTarget() 引用都指向本象限的离屏缓冲
            ((MinecraftAccessor) mc).ic$setMainRenderTarget(fbo);
            try {
                fbo.bindWrite(true);   // 绑定 FBO + 视口 = FBO 全尺寸（按正常整屏尺寸渲染）

                // 🎥 我们的相机接管：Camera.setup → CameraMixin 原型分支读取该实例的模组相机状态
                camera.setup(mc.level, mc.player,
                        !mc.options.getCameraType().isFirstPerson(),
                        mc.options.getCameraType().isMirrored(),
                        partialTick);

                // 视图矩阵：复刻 GameRenderer.renderLevel（poseStack.mulPose(XP, xRot) + mulPose(YP, yRot + 180)）
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

                // 描边（发光/Glowing）：renderLevel 里的后处理链已把描边合成进共享 entityTarget；
                // 此刻 FBO 还绑着 → 立刻把它贴进"本象限这张画面"（原版是等渲染完再整屏 1:1 贴，那样就跑到象限外面了）。
                QuadrantProto.setLaneRendering(true);
                try {
                    mc.levelRenderer.doEntityOutline();
                } finally {
                    QuadrantProto.setLaneRendering(false);
                    // doEntityOutline → blitToScreen 会把全局投影改成正交矩阵，必须还原：
                    // 否则下一个象限里走全局矩阵的绘制（实体/粒子/方块实体）会坏。
                    RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
                }
            } finally {
                ((MinecraftAccessor) mc).ic$setMainRenderTarget(main);
            }

            // 上屏：把整尺寸画面按 50% 缩放贴进象限
            // 象限布局（用户口径）：右上 = 第一象限(+x)，左上 = 第二象限(−x)，左下 = 第三象限(+z)，右下 = 第四象限(−z)
            int vx = (i % 2 == 0) ? halfW : 0;
            int vy = (i < 2) ? halfH : 0;
            int dstW = (i % 2 == 0) ? (w - halfW) : halfW;
            int dstH = (i < 2) ? (h - halfH) : halfH;
            ic$blitScaled(fbo, main, vx, vy, dstW, dstH);
        }

        RenderSystem.viewport(0, 0, w, h);

        if (QuadrantProto.shouldCapture()) {
            // 游戏主图（整屏四象限）
            Screenshot.grab(mc.gameDirectory, "quadrant-main.png", main, component -> {
            });
            // 每象限各裁一张（takeScreenshot 已 flipY：图像第 0 行 = 屏幕顶部）
            NativeImage full = Screenshot.takeScreenshot(main);
            Util.ioPool().execute(() -> {
                try {
                    File dir = new File(mc.gameDirectory, "quadrant-captures");
                    dir.mkdirs();
                    for (int i = 0; i < 4; ++i) {
                        int sx = (i % 2 == 0) ? halfW : 0;
                        int sy = (i < 2) ? 0 : halfH;
                        NativeImage sub = new NativeImage(halfW, halfH, false);
                        for (int y = 0; y < halfH; ++y) {
                            for (int x = 0; x < halfW; ++x) {
                                sub.setPixelRGBA(x, y, full.getPixelRGBA(sx + x, sy + y));
                            }
                        }
                        sub.writeToFile(new File(dir, "quadrant-" + QuadrantProto.NAMES[i] + ".png"));
                        sub.close();
                    }
                    QuadrantProto.logCaptured();
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
