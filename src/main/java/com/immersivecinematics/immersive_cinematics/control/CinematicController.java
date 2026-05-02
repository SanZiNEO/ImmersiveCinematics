package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.script.ScriptProperties;

public class CinematicController {

    public static final CinematicController INSTANCE = new CinematicController();

    private boolean skippable = true;
    private boolean interruptible = true;
    private boolean holdAtEnd = false;

    private boolean blockKeyboard = true;
    private boolean blockMouse = true;

    private boolean hideHud = true;
    private boolean blockChat = false;
    private boolean blockScoreboard = false;
    private boolean blockActionBar = false;

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
        this.blockChat = props.isBlockChat();
        this.blockScoreboard = props.isBlockScoreboard();
        this.blockActionBar = props.isBlockActionBar();
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
        this.blockChat = false;
        this.blockScoreboard = false;
        this.blockActionBar = false;
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
    public boolean isHideHud() { return hideHud; }
    public boolean isBlockChat() { return blockChat; }
    public boolean isBlockScoreboard() { return blockScoreboard; }
    public boolean isBlockActionBar() { return blockActionBar; }
    public boolean isHideArm() { return hideArm; }
    public boolean isSuppressBob() { return suppressBob; }
    public boolean isRenderPlayerModel() { return renderPlayerModel; }
    public boolean isBlockMobAi() { return blockMobAi; }
    public boolean isPauseWhenGamePaused() { return pauseWhenGamePaused; }
}
