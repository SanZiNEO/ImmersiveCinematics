package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 电影模式下 HUD 白名单控制。
 * <p>
 * 在每个 Gui 私有方法的渲染入口注入可取消检查，替代旧 Forge {@code RenderGuiOverlayEvent.Pre}。
 * 双向兼容 Fabric 和 Forge。
 * <p>
 * 非 Gui 私有方法的组件（ChatComponent、BossHealthOverlay、PlayerTabOverlay、SubtitleOverlay）在独立的 Mixin 类中处理。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    private static boolean isActive() {
        return CameraManager.INSTANCE.isActive();
    }

    private static boolean shouldHide(Boolean setting) {
        CinematicController ctrl = CinematicController.INSTANCE;
        if (setting == null) return ctrl.isHideHud();
        return setting;
    }

    // ===== 快捷栏 =====

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void onRenderHotbar(float partialTick, GuiGraphics guiGraphics, CallbackInfo ci) {
        if (isActive() && shouldHide(CinematicController.INSTANCE.isHideHotbar())) ci.cancel();
    }

    // ===== 准心 =====

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void onRenderCrosshair(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (isActive() && shouldHide(CinematicController.INSTANCE.isHideCrosshair())) ci.cancel();
    }

    // ===== 血量/饥饿/护甲 =====

    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void onRenderPlayerHealth(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (isActive() && shouldHide(null)) ci.cancel();
    }

    // ===== 载具血量 =====

    @Inject(method = "renderVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void onRenderVehicleHealth(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (isActive() && shouldHide(null)) ci.cancel();
    }

    // ===== 经验栏 =====

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void onRenderExperienceBar(GuiGraphics guiGraphics, int i, CallbackInfo ci) {
        if (isActive() && shouldHide(null)) ci.cancel();
    }

    // ===== 跳跃蓄力条 =====

    @Inject(method = "renderJumpMeter", at = @At("HEAD"), cancellable = true)
    private void onRenderJumpMeter(PlayerRideableJumping vehicle, GuiGraphics guiGraphics, int i, CallbackInfo ci) {
        if (isActive() && shouldHide(null)) ci.cancel();
    }


    // ===== 选中物品名 =====

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void onRenderSelectedItemName(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (isActive() && shouldHide(null)) ci.cancel();
    }

    // ===== 计分板侧栏 =====

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void onDisplayScoreboardSidebar(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
        if (isActive() && shouldHide(CinematicController.INSTANCE.isHideScoreboard())) ci.cancel();
    }
}
