package com.immersivecinematics.immersive_cinematics.script;

public class ScriptProperties {

    private boolean blockKeyboard = true;
    private boolean blockMouse = true;
    private boolean blockMobAi = false;
    private boolean hideHud = true;
    private boolean hideArm = true;
    private boolean suppressBob = true;
    private Boolean hideChat = null;
    private Boolean hideScoreboard = null;
    private Boolean hideActionBar = null;
    private Boolean hideTitle = null;
    private Boolean hideSubtitles = null;
    private Boolean hideHotbar = null;
    private Boolean hideCrosshair = null;
    private boolean renderPlayerModel = true;
    private boolean pauseWhenGamePaused = true;
    private boolean interruptible = true;
    private boolean skippable = true;
    private boolean holdAtEnd = false;

    public void apply(ScriptMeta meta) {
        this.blockKeyboard = meta.isBlockKeyboard();
        this.blockMouse = meta.isBlockMouse();
        this.blockMobAi = meta.isBlockMobAi();
        this.hideHud = meta.isHideHud();
        this.hideArm = meta.isHideArm();
        this.suppressBob = meta.isSuppressBob();
        this.hideChat = meta.isHideChat();
        this.hideScoreboard = meta.isHideScoreboard();
        this.hideActionBar = meta.isHideActionBar();
        this.hideTitle = meta.isHideTitle();
        this.hideSubtitles = meta.isHideSubtitles();
        this.hideHotbar = meta.isHideHotbar();
        this.hideCrosshair = meta.isHideCrosshair();
        this.renderPlayerModel = meta.isRenderPlayerModel();
        this.pauseWhenGamePaused = meta.isPauseWhenGamePaused();
        this.interruptible = meta.isInterruptible();
        this.skippable = meta.isSkippable();
        this.holdAtEnd = meta.isHoldAtEnd();
    }

    public void revert() {
        ScriptMeta.RuntimeBehavior d = ScriptMeta.RuntimeBehavior.DEFAULT;
        this.blockKeyboard = d.blockKeyboard();
        this.blockMouse = d.blockMouse();
        this.blockMobAi = d.blockMobAi();
        this.hideHud = d.hideHud();
        this.hideArm = d.hideArm();
        this.suppressBob = d.suppressBob();
        this.hideChat = d.hideChat();
        this.hideScoreboard = d.hideScoreboard();
        this.hideActionBar = d.hideActionBar();
        this.hideTitle = d.hideTitle();
        this.hideSubtitles = d.hideSubtitles();
        this.hideHotbar = d.hideHotbar();
        this.hideCrosshair = d.hideCrosshair();
        this.renderPlayerModel = d.renderPlayerModel();
        this.pauseWhenGamePaused = d.pauseWhenGamePaused();
        this.interruptible = d.interruptible();
        this.skippable = d.skippable();
        this.holdAtEnd = d.holdAtEnd();
    }

    public boolean isBlockKeyboard() { return blockKeyboard; }
    public boolean isBlockMouse() { return blockMouse; }
    public boolean isBlockMobAi() { return blockMobAi; }
    public boolean isHideHud() { return hideHud; }
    public boolean isHideArm() { return hideArm; }
    public boolean isSuppressBob() { return suppressBob; }
    public Boolean isHideChat() { return hideChat; }
    public Boolean isHideScoreboard() { return hideScoreboard; }
    public Boolean isHideActionBar() { return hideActionBar; }
    public Boolean isHideTitle() { return hideTitle; }
    public Boolean isHideSubtitles() { return hideSubtitles; }
    public Boolean isHideHotbar() { return hideHotbar; }
    public Boolean isHideCrosshair() { return hideCrosshair; }
    public boolean isRenderPlayerModel() { return renderPlayerModel; }
    public boolean isPauseWhenGamePaused() { return pauseWhenGamePaused; }
    public boolean isInterruptible() { return interruptible; }
    public boolean isSkippable() { return skippable; }
    public boolean isHoldAtEnd() { return holdAtEnd; }
}
