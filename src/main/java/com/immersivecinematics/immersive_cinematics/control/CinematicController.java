package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.script.ScriptProperties;
import net.minecraft.client.KeyMapping;

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

    private boolean hideArm = true;
    private boolean suppressBob = true;
    private boolean renderPlayerModel = true;
    private boolean blockMobAi = false;

    private boolean pauseWhenGamePaused = true;

    public void apply(ScriptProperties props) {
        this.skippable = props.isSkippable();
        this.interruptible = props.isInterruptible();
        this.holdAtEnd = props.isHoldAtEnd();
        this.blockKeyboard = props.isBlockKeyboard();
        this.blockMouse = props.isBlockMouse();
        this.hideHud = props.isHideHud();
        this.hideChat = props.isHideChat();
        this.hideScoreboard = props.isHideScoreboard();
        this.hideActionBar = props.isHideActionBar();
        this.hideTitle = props.isHideTitle();
        this.hideSubtitles = props.isHideSubtitles();
        this.hideHotbar = props.isHideHotbar();
        this.hideCrosshair = props.isHideCrosshair();
        this.hideArm = props.isHideArm();
        this.suppressBob = props.isSuppressBob();
        this.renderPlayerModel = props.isRenderPlayerModel();
        this.blockMobAi = props.isBlockMobAi();
        this.pauseWhenGamePaused = props.isPauseWhenGamePaused();
    }

    public void revert() {
        this.skippable = true;
        this.interruptible = true;
        this.holdAtEnd = false;
        this.blockKeyboard = true;
        this.blockMouse = true;
        this.hideHud = true;
        this.hideChat = null;
        this.hideScoreboard = null;
        this.hideActionBar = null;
        this.hideTitle = null;
        this.hideSubtitles = null;
        this.hideHotbar = null;
        this.hideCrosshair = null;
        this.hideArm = true;
        this.suppressBob = true;
        this.renderPlayerModel = true;
        this.blockMobAi = false;
        this.pauseWhenGamePaused = true;
    }

    public boolean isSkippable() { return skippable; }
    public boolean isInterruptible() { return interruptible; }
    public boolean isHoldAtEnd() { return holdAtEnd; }
    public boolean isBlockKeyboard() { return blockKeyboard; }
    public boolean isBlockMouse() { return blockMouse; }

    /** 释放所有按键状态（键盘+鼠标），在脚本启动/结束时调用 */
    public void releaseAllKeys() {
        KeyMapping.releaseAll();
    }
    public boolean isHideHud() { return hideHud; }
    public Boolean isHideChat() { return hideChat; }
    public Boolean isHideScoreboard() { return hideScoreboard; }
    public Boolean isHideActionBar() { return hideActionBar; }
    public Boolean isHideTitle() { return hideTitle; }
    public Boolean isHideSubtitles() { return hideSubtitles; }
    public Boolean isHideHotbar() { return hideHotbar; }
    public Boolean isHideCrosshair() { return hideCrosshair; }
    public boolean isHideArm() { return hideArm; }
    public boolean isSuppressBob() { return suppressBob; }
    public boolean isRenderPlayerModel() { return renderPlayerModel; }
    public boolean isBlockMobAi() { return blockMobAi; }
    public boolean isPauseWhenGamePaused() { return pauseWhenGamePaused; }
}
