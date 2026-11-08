package com.immersivecinematics.immersive_cinematics.fabric.mixin;

import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import com.immersivecinematics.immersive_cinematics.fabric.hud.FabricHudLayerRegistry;
import com.immersivecinematics.immersive_cinematics.fabric.hud.FabricHudTakeover;
import com.immersivecinematics.immersive_cinematics.handler.ClientEventHandler;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.gui.components.SubtitleOverlay;
import net.minecraft.client.gui.components.spectator.SpectatorGui;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric 的画面层整帧接管（唯一模式：接管即整帧取消并重画；未接管时原版照常）。
 *
 * <p>接管条件由 {@link FabricHudTakeover#isActive()} 单点判定（与 Forge 共用
 * {@code CinematicController#isScreenLayerTakeoverActive()}）。接管期间：</p>
 * <ul>
 *   <li>原版 HUD 不再走 {@code Gui.render} 的原始路径，改由 {@link #ic$renderHud} 按
 *       {@code GuiOverlayManager} 的原版层顺序逐层判定后重画；世界效果层（暗角、望远镜、南瓜 / 细雪遮罩、
 *       传送门、睡眠淡出、药水图标、F3 文本）不参与 {@code screen_layers} 判定，照常重画。</li>
 *   <li>{@code hideGui}（F1）按原版块处理：HUD 层不画，世界效果层照常。</li>
 *   <li>原生 {@code HudRenderCallback} 随整帧取消<strong>全灭</strong>——回调没有层 ID、也无法单独放行。
 *       第三方要参与白名单，必须在客户端初始化期调用 {@link FabricHudLayerRegistry#register}
 *       登记自己的层与渲染回调。</li>
 *   <li>末尾依次补画自定义层、本模组覆盖层（黑边 / 字幕 / 图片 / GIF / 跳过提示），再复位着色器颜色。</li>
 * </ul>
 *
 * <p>未接管时 {@code Gui.render} 正常执行，本 mixin 不做任何追加。</p>
 */
@Mixin(Gui.class)
public abstract class FabricGuiTakeoverMixin {

    /** 南瓜遮罩纹理（与 {@code Gui.PUMPKIN_BLUR_LOCATION} 同值，避免 shadow 静态常量带来额外的类初始化顺序约束）。 */
    @Unique private static final ResourceLocation ic$PUMPKIN_BLUR = new ResourceLocation("textures/misc/pumpkinblur.png");
    /** 细雪遮罩纹理（与 {@code Gui.POWDER_SNOW_OUTLINE_LOCATION} 同值）。 */
    @Unique private static final ResourceLocation ic$POWDER_SNOW_OUTLINE = new ResourceLocation("textures/misc/powder_snow_outline.png");

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ChatComponent chat;
    @Shadow @Final private PlayerTabOverlay tabList;
    @Shadow @Final private BossHealthOverlay bossOverlay;
    @Shadow @Final private SubtitleOverlay subtitleOverlay;
    @Shadow @Final private SpectatorGui spectatorGui;
    @Shadow @Final private DebugScreenOverlay debugScreen;
    @Shadow private Component overlayMessageString;
    @Shadow private int overlayMessageTime;
    @Shadow private boolean animateOverlayMessageColor;
    @Shadow private Component title;
    @Shadow private Component subtitle;
    @Shadow private int titleTime;
    @Shadow private int titleFadeInTime;
    @Shadow private int titleStayTime;
    @Shadow private int titleFadeOutTime;
    @Shadow private int screenWidth;
    @Shadow private int screenHeight;
    @Shadow private float scopeScale;
    @Shadow private int tickCount;

    @Shadow protected abstract void renderVignette(GuiGraphics guiGraphics, Entity entity);

    @Shadow protected abstract void renderSpyglassOverlay(GuiGraphics guiGraphics, float scale);

    @Shadow protected abstract void renderTextureOverlay(GuiGraphics guiGraphics, ResourceLocation texture, float alpha);

    @Shadow protected abstract void renderPortalOverlay(GuiGraphics guiGraphics, float alpha);

    @Shadow protected abstract void renderHotbar(float partialTick, GuiGraphics guiGraphics);

    @Shadow protected abstract void renderCrosshair(GuiGraphics guiGraphics);

    @Shadow protected abstract void renderPlayerHealth(GuiGraphics guiGraphics);

    @Shadow protected abstract void renderVehicleHealth(GuiGraphics guiGraphics);

    @Shadow public abstract void renderJumpMeter(PlayerRideableJumping vehicle, GuiGraphics guiGraphics, int x);

    @Shadow public abstract void renderExperienceBar(GuiGraphics guiGraphics, int x);

    @Shadow public abstract void renderSelectedItemName(GuiGraphics guiGraphics);

    @Shadow protected abstract void renderEffects(GuiGraphics guiGraphics);

    @Shadow protected abstract void displayScoreboardSidebar(GuiGraphics guiGraphics, Objective objective);

    @Shadow protected abstract void drawBackdrop(GuiGraphics guiGraphics, Font font, int y, int width, int color);

    @Shadow public abstract Font getFont();

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ic$takeOver(GuiGraphics guiGraphics, float partialTick, CallbackInfo ci) {
        if (!FabricHudTakeover.isActive()) return;
        ci.cancel();
        ic$renderHud(guiGraphics, partialTick);
    }

    /**
     * 按原版 {@code Gui.render} 的绘制顺序重画：世界效果层不判定，HUD 层逐层判定。
     * 内联段（动作栏 / 标题与副标题 / 睡眠淡出 / 聊天鼠标坐标 / 玩家列表条件 / 记分板 objective）
     * 从 1.20.1 {@code Gui.render} 复制，保留原版的 {@code RenderSystem} 状态序列与 pose 栈。
     */
    private void ic$renderHud(GuiGraphics guiGraphics, float partialTick) {
        Minecraft mc = this.minecraft;
        if (mc.player == null || mc.level == null) return;

        Window window = mc.getWindow();
        this.screenWidth = guiGraphics.guiWidth();
        this.screenHeight = guiGraphics.guiHeight();
        Font font = this.getFont();
        RenderSystem.enableBlend();

        // ===== 世界效果层：不参与层判定，总是重画 =====

        if (Minecraft.useFancyGraphics()) {
            this.renderVignette(guiGraphics, mc.getCameraEntity());
        } else {
            RenderSystem.enableDepthTest();
        }
        float deltaFrameTime = mc.getDeltaFrameTime();
        this.scopeScale = Mth.lerp(0.5F * deltaFrameTime, this.scopeScale, 1.125F);
        if (mc.options.getCameraType().isFirstPerson()) {
            if (mc.player.isScoping()) {
                this.renderSpyglassOverlay(guiGraphics, this.scopeScale);
            } else {
                this.scopeScale = 0.5F;
                ItemStack helmet = mc.player.getInventory().getArmor(3);
                if (helmet.is(Blocks.CARVED_PUMPKIN.asItem())) {
                    this.renderTextureOverlay(guiGraphics, ic$PUMPKIN_BLUR, 1.0F);
                }
            }
        }
        if (mc.player.getTicksFrozen() > 0) {
            this.renderTextureOverlay(guiGraphics, ic$POWDER_SNOW_OUTLINE, mc.player.getPercentFrozen());
        }
        float spinning = Mth.lerp(partialTick, mc.player.oSpinningEffectIntensity, mc.player.spinningEffectIntensity);
        if (spinning > 0.0F && !mc.player.hasEffect(MobEffects.CONFUSION)) {
            this.renderPortalOverlay(guiGraphics, spinning);
        }

        // ===== HUD 层：逐层判定 =====

        boolean hideGui = mc.options.hideGui;

        // 1. HOTBAR：旁观者快捷栏按原版在 hideGui 之外绘制
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            if (!CinematicController.INSTANCE.isLayerHidden("hud.hotbar")) {
                this.spectatorGui.renderHotbar(guiGraphics);
            }
        } else if (!hideGui && !CinematicController.INSTANCE.isLayerHidden("hud.hotbar")) {
            this.renderHotbar(partialTick, guiGraphics);
        }

        if (!hideGui) {
            RenderSystem.enableBlend();

            // 2. CROSSHAIR
            if (!CinematicController.INSTANCE.isLayerHidden("hud.crosshair")) {
                this.renderCrosshair(guiGraphics);
            }

            // 3. BOSS_EVENT_PROGRESS
            if (!CinematicController.INSTANCE.isLayerHidden("hud.bossbar")) {
                mc.getProfiler().push("bossHealth");
                this.bossOverlay.render(guiGraphics);
                mc.getProfiler().pop();
            }

            // 4. PLAYER_HEALTH / ARMOR_LEVEL / FOOD_LEVEL / AIR_LEVEL
            if (mc.gameMode.canHurtPlayer() && !CinematicController.INSTANCE.isLayerHidden("hud.health")) {
                this.renderPlayerHealth(guiGraphics);
            }

            // 5. MOUNT_HEALTH
            if (!CinematicController.INSTANCE.isLayerHidden("hud.health")) {
                this.renderVehicleHealth(guiGraphics);
            }

            RenderSystem.disableBlend();
            int hotbarX = this.screenWidth / 2 - 91;
            PlayerRideableJumping jumpable = mc.player.jumpableVehicle();
            if (jumpable != null) {
                // 6. JUMP_BAR：不在 15 个规范层内，走 status 键
                if (!CinematicController.INSTANCE.isLayerHidden("status")) {
                    this.renderJumpMeter(jumpable, guiGraphics, hotbarX);
                }
            } else if (mc.gameMode.hasExperience()) {
                // 7. EXPERIENCE_BAR
                if (!CinematicController.INSTANCE.isLayerHidden("hud.experience")) {
                    this.renderExperienceBar(guiGraphics, hotbarX);
                }
            }

            // 8. ITEM_NAME：不在 15 个规范层内，走 status 键；旁观者走 spectatorGui 的 tooltip
            if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
                if (!CinematicController.INSTANCE.isLayerHidden("status")) {
                    this.renderSelectedItemName(guiGraphics);
                }
            } else if (mc.player.isSpectator() && !CinematicController.INSTANCE.isLayerHidden("status")) {
                this.spectatorGui.renderTooltip(guiGraphics);
            }
        }

        // ===== 世界效果层：睡眠淡出 / 药水图标 / F3 文本 =====

        if (mc.player.getSleepTimer() > 0) {
            mc.getProfiler().push("sleep");
            float sleepTimer = mc.player.getSleepTimer();
            float fade = sleepTimer / 100.0F;
            if (fade > 1.0F) {
                fade = 1.0F - (sleepTimer - 100.0F) / 10.0F;
            }
            int color = (int) (220.0F * fade) << 24 | 0x101020;
            guiGraphics.fill(RenderType.guiOverlay(), 0, 0, this.screenWidth, this.screenHeight, color);
            mc.getProfiler().pop();
        }
        this.renderEffects(guiGraphics);
        if (mc.options.renderDebug) {
            this.debugScreen.render(guiGraphics);
        }

        if (!hideGui) {
            // 9. RECORD_OVERLAY（动作栏）
            if (!CinematicController.INSTANCE.isLayerHidden("hud.action_bar")) {
                this.ic$renderOverlayMessage(guiGraphics, font, partialTick);
            }

            // 10. TITLE_TEXT（标题 + 副标题内联段）
            if (!CinematicController.INSTANCE.isLayerHidden("hud.title")) {
                this.ic$renderTitle(guiGraphics, font, partialTick);
            }

            // 11. SUBTITLES
            if (!CinematicController.INSTANCE.isLayerHidden("hud.subtitle")) {
                this.subtitleOverlay.render(guiGraphics);
            }

            Scoreboard scoreboard = mc.level.getScoreboard();

            // 12. SCOREBOARD（侧栏 objective 计算 + displayScoreboardSidebar）
            if (!CinematicController.INSTANCE.isLayerHidden("hud.scoreboard")) {
                this.ic$renderScoreboardSidebar(guiGraphics, scoreboard);
            }

            RenderSystem.enableBlend();

            // 13. CHAT_PANEL（鼠标坐标计算 + chat.render）
            if (!CinematicController.INSTANCE.isLayerHidden("hud.chat")) {
                int mouseX = Mth.floor(mc.mouseHandler.xpos() * (double) window.getGuiScaledWidth() / (double) window.getScreenWidth());
                int mouseY = Mth.floor(mc.mouseHandler.ypos() * (double) window.getGuiScaledHeight() / (double) window.getScreenHeight());
                mc.getProfiler().push("chat");
                this.chat.render(guiGraphics, this.tickCount, mouseX, mouseY);
                mc.getProfiler().pop();
            }

            // 14. PLAYER_LIST：原版用记分板侧栏的同一条判定键
            Objective listObjective = scoreboard.getDisplayObjective(0);
            if (CinematicController.INSTANCE.isLayerHidden("hud.scoreboard")) {
                this.tabList.setVisible(false);
            } else if (mc.options.keyPlayerList.isDown()
                    && (!mc.isLocalServer() || mc.player.connection.getListedOnlinePlayers().size() > 1 || listObjective != null)) {
                this.tabList.setVisible(true);
                this.tabList.render(guiGraphics, this.screenWidth, scoreboard, listObjective);
            } else {
                this.tabList.setVisible(false);
            }
        }

        // ===== 第三方自定义层 → 本模组覆盖层 → 着色器复位 =====
        FabricHudLayerRegistry.renderCustom(guiGraphics, partialTick);
        ClientEventHandler.onRenderHud(guiGraphics);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 动作栏内联段：从 {@code Gui.render} 复制，含 {@code drawBackdrop} 与 hsv 变色。 */
    private void ic$renderOverlayMessage(GuiGraphics guiGraphics, Font font, float partialTick) {
        if (this.overlayMessageString == null || this.overlayMessageTime <= 0) return;
        Minecraft mc = this.minecraft;
        mc.getProfiler().push("overlayMessage");
        float remaining = (float) this.overlayMessageTime - partialTick;
        int alpha = (int) (remaining * 255.0F / 20.0F);
        if (alpha > 255) {
            alpha = 255;
        }
        if (alpha > 8) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(this.screenWidth / 2, this.screenHeight - 68, 0.0F);
            int color = 0xFFFFFF;
            if (this.animateOverlayMessageColor) {
                color = Mth.hsvToRgb(remaining / 50.0F, 0.7F, 0.6F) & 0xFFFFFF;
            }
            int background = alpha << 24 & 0xFF000000;
            int width = font.width(this.overlayMessageString);
            this.drawBackdrop(guiGraphics, font, -4, width, 0xFFFFFF | background);
            guiGraphics.drawString(font, this.overlayMessageString, -width / 2, -4, color | background);
            guiGraphics.pose().popPose();
        }
        mc.getProfiler().pop();
    }

    /** 标题 + 副标题内联段：从 {@code Gui.render} 复制，保留 4×/2× 缩放与淡入淡出。 */
    private void ic$renderTitle(GuiGraphics guiGraphics, Font font, float partialTick) {
        if (this.title == null || this.titleTime <= 0) return;
        Minecraft mc = this.minecraft;
        mc.getProfiler().push("titleAndSubtitle");
        float remaining = (float) this.titleTime - partialTick;
        int alpha = 255;
        if (this.titleTime > this.titleFadeOutTime + this.titleStayTime) {
            float elapsed = (float) (this.titleFadeInTime + this.titleStayTime + this.titleFadeOutTime) - remaining;
            alpha = (int) (elapsed * 255.0F / (float) this.titleFadeInTime);
        }
        if (this.titleTime <= this.titleFadeOutTime) {
            alpha = (int) (remaining * 255.0F / (float) this.titleFadeOutTime);
        }
        if ((alpha = Mth.clamp(alpha, 0, 255)) > 8) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(this.screenWidth / 2, this.screenHeight / 2, 0.0F);
            RenderSystem.enableBlend();
            guiGraphics.pose().pushPose();
            guiGraphics.pose().scale(4.0F, 4.0F, 4.0F);
            int background = alpha << 24 & 0xFF000000;
            int width = font.width(this.title);
            this.drawBackdrop(guiGraphics, font, -10, width, 0xFFFFFF | background);
            guiGraphics.drawString(font, this.title, -width / 2, -10, 0xFFFFFF | background);
            guiGraphics.pose().popPose();
            if (this.subtitle != null) {
                guiGraphics.pose().pushPose();
                guiGraphics.pose().scale(2.0F, 2.0F, 2.0F);
                int subtitleWidth = font.width(this.subtitle);
                this.drawBackdrop(guiGraphics, font, 5, subtitleWidth, 0xFFFFFF | background);
                guiGraphics.drawString(font, this.subtitle, -subtitleWidth / 2, 5, 0xFFFFFF | background);
                guiGraphics.pose().popPose();
            }
            RenderSystem.disableBlend();
            guiGraphics.pose().popPose();
        }
        mc.getProfiler().pop();
    }

    /** 记分板侧栏：objective 计算从 {@code Gui.render} 复制（队伍色槽位 3+color → 槽位 1）。 */
    private void ic$renderScoreboardSidebar(GuiGraphics guiGraphics, Scoreboard scoreboard) {
        Minecraft mc = this.minecraft;
        Objective objective = null;
        PlayerTeam team = scoreboard.getPlayersTeam(mc.player.getScoreboardName());
        if (team != null) {
            int colorSlot = team.getColor().getId();
            if (colorSlot >= 0) {
                objective = scoreboard.getDisplayObjective(3 + colorSlot);
            }
        }
        Objective sidebar = objective != null ? objective : scoreboard.getDisplayObjective(1);
        if (sidebar != null) {
            this.displayScoreboardSidebar(guiGraphics, sidebar);
        }
    }
}
