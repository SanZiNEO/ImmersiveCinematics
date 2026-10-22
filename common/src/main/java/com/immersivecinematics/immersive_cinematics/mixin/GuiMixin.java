package com.immersivecinematics.immersive_cinematics.mixin;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 电影模式下按白名单隐藏 HUD。
 *
 * 挂点是每个 {@code Gui} 私有渲染方法的 HEAD + cancel，取代 Forge 的
 * {@code RenderGuiOverlayEvent.Pre}：该事件只在 Forge 存在，逐方法注入才能让 Fabric / Forge 共用同一套
 * 挂点。非 {@code Gui} 私有方法的组件（{@link ChatComponentMixin}、{@link BossHealthOverlayMixin}、
 * {@link PlayerTabOverlayMixin}、{@link SubtitleOverlayMixin}）各自单独处理。
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

    // ===== Action Bar / Title：在 Gui.render 内联渲染、没有独立方法可注入，帧内把计时清零再于 RETURN 还原 =====

    @Shadow private int overlayMessageTime;
    @Shadow private int titleTime;
    @Unique private int savedOverlayMessageTime;
    @Unique private int savedTitleTime;
    @Unique private boolean suppressOverlayMessage;
    @Unique private boolean suppressTitle;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderHead(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
        if (isActive() && shouldHide(CinematicController.INSTANCE.isHideActionBar()) && overlayMessageTime > 0) {
            savedOverlayMessageTime = overlayMessageTime;
            overlayMessageTime = 0;
            suppressOverlayMessage = true;
        } else {
            suppressOverlayMessage = false;
        }

        if (isActive() && shouldHide(CinematicController.INSTANCE.isHideTitle()) && titleTime > 0) {
            savedTitleTime = titleTime;
            titleTime = 0;
            suppressTitle = true;
        } else {
            suppressTitle = false;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderReturn(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
        if (suppressOverlayMessage) overlayMessageTime = savedOverlayMessageTime;
        if (suppressTitle) titleTime = savedTitleTime;
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

    // ===== 保存提示（F1 隐藏块内） =====

    @Inject(method = "renderSavingIndicator", at = @At("HEAD"), cancellable = true)
    private void onRenderSavingIndicator(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (isActive() && shouldHide(null)) ci.cancel();
    }
}
