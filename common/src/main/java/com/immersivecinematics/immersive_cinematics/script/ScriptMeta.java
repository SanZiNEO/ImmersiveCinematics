package com.immersivecinematics.immersive_cinematics.script;
import java.util.Collections;
import java.util.List;
public class ScriptMeta {

    private final String id;
    private final String name;
    private final String author;
    private final int version;
    private final String description;
    private final RuntimeBehavior behavior;
    private final int priority;
    private final String dimension;
    private final List<TriggerDefinition> triggers;
    /** 跳过投票比例（百分比，10~100）；null = 未指定，运行时回落到全局配置 Config.skipVoteRatio */
    private final Integer skipVoteRatio;
    /** 脚本级相机区域刷怪开关（0.3.5 第5.5轮，仅脚本 meta，不进全局 Config） */
    private final boolean cameraMobSpawn;
    /** 脚本级相机刷怪半径（区块） */
    private final int cameraMobRadius;
    /** 脚本级相机区实体 AI 开关（true=实体 tick，false=静态布景） */
    private final boolean cameraMobAi;

    public ScriptMeta(String id, String name, String author, int version, String description,
                      RuntimeBehavior behavior, int priority, String dimension,
                      List<TriggerDefinition> triggers, Integer skipVoteRatio,
                      boolean cameraMobSpawn, int cameraMobRadius, boolean cameraMobAi) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.description = description;
        this.behavior = behavior;
        this.priority = priority;
        this.dimension = dimension;
        this.triggers = triggers != null ? triggers : Collections.emptyList();
        this.skipVoteRatio = skipVoteRatio;
        this.cameraMobSpawn = cameraMobSpawn;
        this.cameraMobRadius = cameraMobRadius;
        this.cameraMobAi = cameraMobAi;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAuthor() { return author; }
    public int getVersion() { return version; }
    public String getDescription() { return description; }
    public RuntimeBehavior getBehavior() { return behavior; }
    /** 播放优先级：数值越大优先级越高，仅用于队列内排序（不可打断脚本永不被打断） */
    public int getPriority() { return priority; }
    public String getDimension() { return dimension; }
    public List<TriggerDefinition> getTriggers() { return triggers; }
    /** 跳过投票比例（10~100）；null = 未指定，运行时使用全局配置 Config.skipVoteRatio */
    public Integer getSkipVoteRatio() { return skipVoteRatio; }
    /** 脚本级：是否在相机区域刷怪 */
    public boolean isCameraMobSpawn() { return cameraMobSpawn; }
    /** 脚本级：相机刷怪半径（区块） */
    public int getCameraMobRadius() { return cameraMobRadius; }
    /** 脚本级：是否让相机区实体正常 AI */
    public boolean isCameraMobAi() { return cameraMobAi; }

    public boolean isBlockKeyboard() { return behavior.blockKeyboard(); }
    public boolean isBlockMouse() { return behavior.blockMouse(); }
    @Deprecated
    public boolean isBlockMobAi() { return behavior.blockMobAi(); }
    public boolean isHideHud() { return behavior.hideHud(); }
    public Boolean isHideArm() { return behavior.hideArm(); }
    public Boolean isSuppressBob() { return behavior.suppressBob(); }
    public Boolean isSuppressDistortion() { return behavior.suppressDistortion(); }
    public Boolean isHideChat() { return behavior.hideChat(); }
    public Boolean isHideScoreboard() { return behavior.hideScoreboard(); }
    public Boolean isHideActionBar() { return behavior.hideActionBar(); }
    public Boolean isHideTitle() { return behavior.hideTitle(); }
    public Boolean isHideSubtitles() { return behavior.hideSubtitles(); }
    public Boolean isHideHotbar() { return behavior.hideHotbar(); }
    public Boolean isHideCrosshair() { return behavior.hideCrosshair(); }
    public Boolean isHideBossbar() { return behavior.hideBossbar(); }
    public Boolean isHideSkipHud() { return behavior.hideSkipHud(); }
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
            Boolean hideArm,
            Boolean suppressBob,
            Boolean suppressDistortion,
            Boolean hideChat,
            Boolean hideScoreboard,
            Boolean hideActionBar,
            Boolean hideTitle,
            Boolean hideSubtitles,
            Boolean hideHotbar,
            Boolean hideCrosshair,
            Boolean hideBossbar,
            Boolean hideSkipHud,
            boolean renderPlayerModel,
            boolean pauseWhenGamePaused,
            boolean interruptible,
            boolean skippable,
            boolean holdAtEnd
    ) {
        public static final RuntimeBehavior DEFAULT = new RuntimeBehavior(
                true, true, false, true,
                null, null, null,
                null, null, null, null, null, null, null, null, null,
                true,
                true, true, true, false
        );

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private boolean blockKeyboard = DEFAULT.blockKeyboard();
            private boolean blockMouse = DEFAULT.blockMouse();
            private boolean blockMobAi = DEFAULT.blockMobAi();
            private boolean hideHud = DEFAULT.hideHud();
            private Boolean hideArm = DEFAULT.hideArm();
            private Boolean suppressBob = DEFAULT.suppressBob();
            private Boolean suppressDistortion = DEFAULT.suppressDistortion();
            private Boolean hideChat = null;
            private Boolean hideScoreboard = null;
            private Boolean hideActionBar = null;
            private Boolean hideTitle = null;
            private Boolean hideSubtitles = null;
            private Boolean hideHotbar = null;
            private Boolean hideCrosshair = null;
            private Boolean hideBossbar = null;
            private Boolean hideSkipHud = null;
            private boolean renderPlayerModel = DEFAULT.renderPlayerModel();
            private boolean pauseWhenGamePaused = DEFAULT.pauseWhenGamePaused();
            private boolean interruptible = DEFAULT.interruptible();
            private boolean skippable = DEFAULT.skippable();
            private boolean holdAtEnd = DEFAULT.holdAtEnd();

            public Builder blockKeyboard(boolean v) { this.blockKeyboard = v; return this; }
            public Builder blockMouse(boolean v) { this.blockMouse = v; return this; }
            public Builder blockMobAi(boolean v) { this.blockMobAi = v; return this; }
            public Builder hideHud(boolean v) { this.hideHud = v; return this; }
            public Builder hideArm(Boolean v) { this.hideArm = v; return this; }
            public Builder suppressBob(Boolean v) { this.suppressBob = v; return this; }
            public Builder suppressDistortion(Boolean v) { this.suppressDistortion = v; return this; }
            public Builder hideChat(Boolean v) { this.hideChat = v; return this; }
            public Builder hideScoreboard(Boolean v) { this.hideScoreboard = v; return this; }
            public Builder hideActionBar(Boolean v) { this.hideActionBar = v; return this; }
            public Builder hideTitle(Boolean v) { this.hideTitle = v; return this; }
            public Builder hideSubtitles(Boolean v) { this.hideSubtitles = v; return this; }
            public Builder hideHotbar(Boolean v) { this.hideHotbar = v; return this; }
            public Builder hideCrosshair(Boolean v) { this.hideCrosshair = v; return this; }
            public Builder hideBossbar(Boolean v) { this.hideBossbar = v; return this; }
            public Builder hideSkipHud(Boolean v) { this.hideSkipHud = v; return this; }
            public Builder renderPlayerModel(boolean v) { this.renderPlayerModel = v; return this; }
            public Builder pauseWhenGamePaused(boolean v) { this.pauseWhenGamePaused = v; return this; }
            public Builder interruptible(boolean v) { this.interruptible = v; return this; }
            public Builder skippable(boolean v) { this.skippable = v; return this; }
            public Builder holdAtEnd(boolean v) { this.holdAtEnd = v; return this; }

            public RuntimeBehavior build() {
                return new RuntimeBehavior(
                        blockKeyboard, blockMouse, blockMobAi,
                        hideHud, hideArm, suppressBob, suppressDistortion,
                        hideChat, hideScoreboard, hideActionBar,
                        hideTitle, hideSubtitles, hideHotbar, hideCrosshair,
                        hideBossbar, hideSkipHud,
                        renderPlayerModel,
                        pauseWhenGamePaused, interruptible, skippable, holdAtEnd
                );
            }
        }
    }
}
