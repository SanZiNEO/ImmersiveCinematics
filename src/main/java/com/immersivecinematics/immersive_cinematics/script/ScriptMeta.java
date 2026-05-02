package com.immersivecinematics.immersive_cinematics.script;

public class ScriptMeta {

    private final String id;
    private final String name;
    private final String author;
    private final int version;
    private final String description;
    private final RuntimeBehavior behavior;

    public ScriptMeta(String id, String name, String author, int version, String description,
                      RuntimeBehavior behavior) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.description = description;
        this.behavior = behavior;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAuthor() { return author; }
    public int getVersion() { return version; }
    public String getDescription() { return description; }
    public RuntimeBehavior getBehavior() { return behavior; }

    public boolean isBlockKeyboard() { return behavior.blockKeyboard(); }
    public boolean isBlockMouse() { return behavior.blockMouse(); }
    @Deprecated
    public boolean isBlockMobAi() { return behavior.blockMobAi(); }
    public boolean isHideHud() { return behavior.hideHud(); }
    public boolean isHideArm() { return behavior.hideArm(); }
    public boolean isSuppressBob() { return behavior.suppressBob(); }
    public Boolean isHideChat() { return behavior.hideChat(); }
    public Boolean isHideScoreboard() { return behavior.hideScoreboard(); }
    public Boolean isHideActionBar() { return behavior.hideActionBar(); }
    public Boolean isHideTitle() { return behavior.hideTitle(); }
    public Boolean isHideSubtitles() { return behavior.hideSubtitles(); }
    public Boolean isHideHotbar() { return behavior.hideHotbar(); }
    public Boolean isHideCrosshair() { return behavior.hideCrosshair(); }
    public boolean isRenderPlayerModel() { return behavior.renderPlayerModel(); }
    public boolean isPauseWhenGamePaused() { return behavior.pauseWhenGamePaused(); }
    public boolean isInterruptible() { return behavior.interruptible(); }
    public boolean isSkippable() { return behavior.skippable(); }
    public boolean isHoldAtEnd() { return behavior.holdAtEnd(); }

    @Override
    public String toString() {
        return String.format("ScriptMeta{id=%s, name=%s, author=%s, v%d}", id, name, author, version);
    }

    public record RuntimeBehavior(
            boolean blockKeyboard,
            boolean blockMouse,
            boolean blockMobAi,
            boolean hideHud,
            boolean hideArm,
            boolean suppressBob,
            Boolean hideChat,
            Boolean hideScoreboard,
            Boolean hideActionBar,
            Boolean hideTitle,
            Boolean hideSubtitles,
            Boolean hideHotbar,
            Boolean hideCrosshair,
            boolean renderPlayerModel,
            boolean pauseWhenGamePaused,
            boolean interruptible,
            boolean skippable,
            boolean holdAtEnd
    ) {
        public static final RuntimeBehavior DEFAULT = new RuntimeBehavior(
                true, true, false, true, true, true,
                null, null, null, null, null, null, null, true,
                true, true, true, false
        );

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private boolean blockKeyboard = DEFAULT.blockKeyboard();
            private boolean blockMouse = DEFAULT.blockMouse();
            private boolean blockMobAi = DEFAULT.blockMobAi();
            private boolean hideHud = DEFAULT.hideHud();
            private boolean hideArm = DEFAULT.hideArm();
            private boolean suppressBob = DEFAULT.suppressBob();
            private Boolean hideChat = null;
            private Boolean hideScoreboard = null;
            private Boolean hideActionBar = null;
            private Boolean hideTitle = null;
            private Boolean hideSubtitles = null;
            private Boolean hideHotbar = null;
            private Boolean hideCrosshair = null;
            private boolean renderPlayerModel = DEFAULT.renderPlayerModel();
            private boolean pauseWhenGamePaused = DEFAULT.pauseWhenGamePaused();
            private boolean interruptible = DEFAULT.interruptible();
            private boolean skippable = DEFAULT.skippable();
            private boolean holdAtEnd = DEFAULT.holdAtEnd();

            public Builder blockKeyboard(boolean v) { this.blockKeyboard = v; return this; }
            public Builder blockMouse(boolean v) { this.blockMouse = v; return this; }
            public Builder blockMobAi(boolean v) { this.blockMobAi = v; return this; }
            public Builder hideHud(boolean v) { this.hideHud = v; return this; }
            public Builder hideArm(boolean v) { this.hideArm = v; return this; }
            public Builder suppressBob(boolean v) { this.suppressBob = v; return this; }
            public Builder hideChat(Boolean v) { this.hideChat = v; return this; }
            public Builder hideScoreboard(Boolean v) { this.hideScoreboard = v; return this; }
            public Builder hideActionBar(Boolean v) { this.hideActionBar = v; return this; }
            public Builder hideTitle(Boolean v) { this.hideTitle = v; return this; }
            public Builder hideSubtitles(Boolean v) { this.hideSubtitles = v; return this; }
            public Builder hideHotbar(Boolean v) { this.hideHotbar = v; return this; }
            public Builder hideCrosshair(Boolean v) { this.hideCrosshair = v; return this; }
            public Builder renderPlayerModel(boolean v) { this.renderPlayerModel = v; return this; }
            public Builder pauseWhenGamePaused(boolean v) { this.pauseWhenGamePaused = v; return this; }
            public Builder interruptible(boolean v) { this.interruptible = v; return this; }
            public Builder skippable(boolean v) { this.skippable = v; return this; }
            public Builder holdAtEnd(boolean v) { this.holdAtEnd = v; return this; }

            public RuntimeBehavior build() {
                return new RuntimeBehavior(
                        blockKeyboard, blockMouse, blockMobAi,
                        hideHud, hideArm, suppressBob,
                        hideChat, hideScoreboard, hideActionBar,
                        hideTitle, hideSubtitles, hideHotbar, hideCrosshair,
                        renderPlayerModel,
                        pauseWhenGamePaused, interruptible, skippable, holdAtEnd
                );
            }
        }
    }
}
