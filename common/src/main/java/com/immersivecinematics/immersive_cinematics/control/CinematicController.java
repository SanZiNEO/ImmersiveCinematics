package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.mixin.MouseHandlerAccessor;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class CinematicController {

    public static final CinematicController INSTANCE = new CinematicController();

    private boolean skippable = true;
    private boolean interruptible = true;
    private boolean holdAtEnd = false;

    private boolean blockKeyboard = true;
    private boolean blockMouse = true;

    private boolean hideHud = true;
    private Boolean hideChat = null;
    private Boolean hideScoreboard = null;
    private Boolean hideActionBar = null;
    private Boolean hideTitle = null;
    private Boolean hideSubtitles = null;
    private Boolean hideHotbar = null;
    private Boolean hideCrosshair = null;
    private Boolean hideBossbar = null;
    private Boolean hideSkipHud = null;

    private Boolean hideArm = null;
    private Boolean suppressBob = null;
    private Boolean suppressDistortion = null;
    private boolean renderPlayerModel = true;
    private boolean blockMobAi = false;

    /** 播放期间临时修改 screenEffectScale 的保存/恢复状态 */
    private double savedScreenEffectScale = 1.0;
    private boolean distortionOverridden = false;

    private boolean pauseWhenGamePaused = true;

    public void apply(ScriptMeta.RuntimeBehavior behavior) {
        this.skippable = behavior.skippable();
        this.interruptible = behavior.interruptible();
        this.holdAtEnd = behavior.holdAtEnd();
        this.blockKeyboard = behavior.blockKeyboard();
        this.blockMouse = behavior.blockMouse();
        this.hideHud = behavior.hideHud();
        this.hideChat = behavior.hideChat();
        this.hideScoreboard = behavior.hideScoreboard();
        this.hideActionBar = behavior.hideActionBar();
        this.hideTitle = behavior.hideTitle();
        this.hideSubtitles = behavior.hideSubtitles();
        this.hideHotbar = behavior.hideHotbar();
        this.hideCrosshair = behavior.hideCrosshair();
        this.hideBossbar = behavior.hideBossbar();
        this.hideSkipHud = behavior.hideSkipHud();
        this.hideArm = behavior.hideArm();
        this.suppressBob = behavior.suppressBob();
        this.suppressDistortion = behavior.suppressDistortion();
        this.renderPlayerModel = behavior.renderPlayerModel();
        this.blockMobAi = behavior.blockMobAi();
        this.pauseWhenGamePaused = behavior.pauseWhenGamePaused();
        updateScreenEffectScale();
    }

    public void revert() {
        this.skippable = true;
        this.interruptible = true;
        this.holdAtEnd = false;
        this.blockKeyboard = false;
        this.blockMouse = false;
        this.hideHud = true;
        this.hideChat = null;
        this.hideScoreboard = null;
        this.hideActionBar = null;
        this.hideTitle = null;
        this.hideSubtitles = null;
        this.hideHotbar = null;
        this.hideCrosshair = null;
        this.hideBossbar = null;
        this.hideSkipHud = null;
        this.hideArm = null;
        this.suppressBob = null;
        this.suppressDistortion = null;
        this.renderPlayerModel = true;
        this.blockMobAi = false;
        this.pauseWhenGamePaused = true;
        restoreScreenEffectScale();
    }

    public boolean isSkippable() { return skippable; }
    public boolean isInterruptible() { return interruptible; }
    public boolean isHoldAtEnd() { return holdAtEnd; }
    public boolean isBlockKeyboard() { return blockKeyboard; }
    public boolean isBlockMouse() { return blockMouse; }

    public void setBlockKeyboard(boolean v) { this.blockKeyboard = v; }
    public void setBlockMouse(boolean v) { this.blockMouse = v; }

    public void releaseAllKeys() {
        KeyMapping.releaseAll();
    }

    /**
     * 播放退出后的输入状态重同步（优雅交接）——播放开始用 {@link #releaseAllKeys()} 清旧状态，
     * 退出改用本方法按实际物理按键状态重建，避免玩家持续按住 W 时退出导致"按键被强制松开，
     * 直到松开重按才恢复"的卡键现象。
     * <ol>
     *   <li>键盘：{@code KeyMapping.setAll()} 把全部 KEYSYM 绑定按当前物理按键状态 setDown；</li>
     *   <li>鼠标按钮：{@code KeyMapping.setAll()} 只处理键盘，鼠标按键单独按 GLFW 物理状态同步；</li>
     *   <li>鼠标视角累积量：清空 accumulatedDX/DY，避免退出后第一次 turnPlayer 消费播放期间积压位移。</li>
     * </ol>
     */
    public void syncInputStateAfterExit() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // 1) 键盘状态重同步（替代 releaseAll 的"全量释放"）
        KeyMapping.setAll();

        // 2) 鼠标按键状态重同步（KeyMapping.setAll() 只处理键盘；set() 内部按 Key 的类型/值匹配所有绑定该键的映射）
        long window = mc.getWindow().getWindow();
        for (int button = 0; button < 8; button++) { // 常用鼠标按钮 0..7（左/右/中/侧键等）
            boolean down = GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), down);
        }

        // 3) 鼠标视角累积量清理（Accessor 接口——mixin 类不可直接引用，会抛 IllegalClassLoadError）
        if (mc.mouseHandler != null) {
            MouseHandlerAccessor accessor = (MouseHandlerAccessor) mc.mouseHandler;
            accessor.setAccumulatedDX(0.0D);
            accessor.setAccumulatedDY(0.0D);
        }
    }

    public boolean isHideHud() { return hideHud; }
    public Boolean isHideChat() { return hideChat; }
    public Boolean isHideScoreboard() { return hideScoreboard; }
    public Boolean isHideActionBar() { return hideActionBar; }
    public Boolean isHideTitle() { return hideTitle; }
    public Boolean isHideSubtitles() { return hideSubtitles; }
    public Boolean isHideHotbar() { return hideHotbar; }
    public Boolean isHideCrosshair() { return hideCrosshair; }
    public Boolean isHideBossbar() { return hideBossbar; }
    public Boolean isHideSkipHud() { return hideSkipHud; }
    public Boolean isHideArm() { return hideArm; }
    public Boolean isSuppressBob() { return suppressBob; }
    public Boolean isSuppressDistortion() { return suppressDistortion; }
    public boolean isRenderPlayerModel() { return renderPlayerModel; }
    public boolean isBlockMobAi() { return blockMobAi; }
    public boolean isPauseWhenGamePaused() { return pauseWhenGamePaused; }

    /**
     * 是否屏蔽屏幕扭曲（反胃/传送门旋转）。
     * <ul>
     *   <li>脚本显式写了 {@code suppress_distortion} 时以脚本为准；</li>
     *   <li>未写时兼容旧行为：跟随 {@code suppress_bob}，再回落到 {@code hide_hud}。</li>
     * </ul>
     */
    private boolean shouldSuppressDistortion() {
        if (suppressDistortion != null) return suppressDistortion;
        if (suppressBob != null) return suppressBob;
        return hideHud;
    }

    /**
     * 根据当前脚本设置临时修改原版 {@code screenEffectScale}。
     * 需要屏蔽时设为 0，不需要时恢复原值。
     */
    private void updateScreenEffectScale() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        if (shouldSuppressDistortion()) {
            if (!distortionOverridden) {
                savedScreenEffectScale = mc.options.screenEffectScale().get();
                distortionOverridden = true;
            }
            mc.options.screenEffectScale().set(0.0);
        } else {
            restoreScreenEffectScale();
        }
    }

    /** 恢复播放前保存的 {@code screenEffectScale}。 */
    private void restoreScreenEffectScale() {
        if (!distortionOverridden) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            mc.options.screenEffectScale().set(savedScreenEffectScale);
        }
        distortionOverridden = false;
    }
}
