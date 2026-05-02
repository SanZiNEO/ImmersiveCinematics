package com.immersivecinematics.immersive_cinematics.script;

/**
 * 脚本属性 — 脚本的身份信息和运行时行为配置
 * <p>
 * 速度驱动模型下，脚本级不再控制插值类型——
 * 插值控制下放到片段级（{@link CameraClip#getInterpolation()}）
 * 和属性级（{@link PropertyOverride}）。
 */
public class ScriptMeta {

    // ========== 元信息 ==========

    private final String id;
    private final String name;
    private final String author;
    private final int version;
    private final String description;

    // ========== 运行时行为 ==========

    private final RuntimeBehavior behavior;

    /**
     * 简化构造器 — 使用 RuntimeBehavior 值对象
     */
    public ScriptMeta(String id, String name, String author, int version, String description,
                      RuntimeBehavior behavior) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.description = description;
        this.behavior = behavior;
    }

    // ========== 元信息 Getter ==========

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAuthor() { return author; }
    public int getVersion() { return version; }
    public String getDescription() { return description; }

    // ========== 运行时行为 Getter（委托到 RuntimeBehavior）==========

    public RuntimeBehavior getBehavior() { return behavior; }

    public boolean isBlockKeyboard() { return behavior.blockKeyboard(); }
    public boolean isBlockMouse() { return behavior.blockMouse(); }
    /** @deprecated 需要 C/S 架构，当前纯客户端无法实现 */
    @Deprecated
    public boolean isBlockMobAi() { return behavior.blockMobAi(); }
    public boolean isHideHud() { return behavior.hideHud(); }
    public boolean isHideArm() { return behavior.hideArm(); }
    public boolean isSuppressBob() { return behavior.suppressBob(); }
    public boolean isBlockChat() { return behavior.blockChat(); }
    public boolean isBlockScoreboard() { return behavior.blockScoreboard(); }
    public boolean isBlockActionBar() { return behavior.blockActionBar(); }
    public boolean isRenderPlayerModel() { return behavior.renderPlayerModel(); }
    public boolean isPauseWhenGamePaused() { return behavior.pauseWhenGamePaused(); }
    /** 脚本间抢占控制：是否允许被其他脚本打断（与用户退出无关） */
    public boolean isInterruptible() { return behavior.interruptible(); }
    /** 用户退出控制：是否允许用户长按退出键提前结束播放 */
    public boolean isSkippable() { return behavior.skippable(); }
    /** 播完保持控制：播完后是否保持最后一帧，而非自动退出 */
    public boolean isHoldAtEnd() { return behavior.holdAtEnd(); }

    @Override
    public String toString() {
        return String.format("ScriptMeta{id=%s, name=%s, author=%s, v%d}",
                id, name, author, version);
    }

    /**
     * 运行时行为值对象 — 14 个布尔标志位 + 1 个已弃用 (blockMobAi)
     * <p>
     * 使用 Java record 实现，不可变。
     * 默认值通过 {@link #DEFAULT} 常量提供。
     * Builder 模式通过 {@link #builder()} 获取。
     * <p>
     * 退出控制三属性（设计意图）：
     * <ul>
     *   <li>{@code interruptible} — 脚本间抢占控制：当前脚本是否允许被<b>其他脚本</b>打断。
     *       例如：区域固定视角脚本（生化危机0式）可设为 false，防止其他脚本抢占。
     *       这与用户退出无关，仅控制脚本间的优先级调度。</li>
     *   <li>{@code skippable} — 用户退出控制：用户是否可以通过长按退出键提前结束脚本播放。
     *       false 时用户无法通过按键退出，适用于强制观看的过场动画或固定视角玩法区域。</li>
     *   <li>{@code holdAtEnd} — 播完保持控制：脚本自然播完后是否保持最后一帧相机状态，
     *       而非自动退出。适用于固定视角区域，玩家在该区域内持续处于脚本相机控制下，
     *       直到离开区域触发新脚本或用户按键退出（需 skippable=true）。</li>
     * </ul>
     */
    public record RuntimeBehavior(
            boolean blockKeyboard,
            boolean blockMouse,
            boolean blockMobAi,
            boolean hideHud,
            boolean hideArm,
            boolean suppressBob,
            boolean blockChat,
            boolean blockScoreboard,
            boolean blockActionBar,
            boolean renderPlayerModel,
            boolean pauseWhenGamePaused,
            /** 脚本间抢占控制：是否允许被其他脚本打断（与用户退出无关） */
            boolean interruptible,
            /** 用户退出控制：是否允许用户长按退出键提前结束播放 */
            boolean skippable,
            /** 播完保持控制：播完后是否保持最后一帧，而非自动退出 */
            boolean holdAtEnd
    ) {
        /** 默认运行时行为 */
        public static final RuntimeBehavior DEFAULT = new RuntimeBehavior(
                true, true, false, true, true, true,
                false, false, false, true,
                true, true, true, false
        );

        /**
         * 创建 Builder（从默认值开始）
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder 模式 — 只需覆盖需要修改的字段
         */
        public static class Builder {
            private boolean blockKeyboard = DEFAULT.blockKeyboard();
            private boolean blockMouse = DEFAULT.blockMouse();
            private boolean blockMobAi = DEFAULT.blockMobAi();
            private boolean hideHud = DEFAULT.hideHud();
            private boolean hideArm = DEFAULT.hideArm();
            private boolean suppressBob = DEFAULT.suppressBob();
            private boolean blockChat = DEFAULT.blockChat();
            private boolean blockScoreboard = DEFAULT.blockScoreboard();
            private boolean blockActionBar = DEFAULT.blockActionBar();
            private boolean renderPlayerModel = DEFAULT.renderPlayerModel();
            private boolean pauseWhenGamePaused = DEFAULT.pauseWhenGamePaused();
            /** 脚本间抢占控制：是否允许被其他脚本打断 */
            private boolean interruptible = DEFAULT.interruptible();
            /** 用户退出控制：是否允许用户长按退出键提前结束播放 */
            private boolean skippable = DEFAULT.skippable();
            /** 播完保持控制：播完后是否保持最后一帧 */
            private boolean holdAtEnd = DEFAULT.holdAtEnd();

            public Builder blockKeyboard(boolean v) { this.blockKeyboard = v; return this; }
            public Builder blockMouse(boolean v) { this.blockMouse = v; return this; }
            public Builder blockMobAi(boolean v) { this.blockMobAi = v; return this; }
            public Builder hideHud(boolean v) { this.hideHud = v; return this; }
            public Builder hideArm(boolean v) { this.hideArm = v; return this; }
            public Builder suppressBob(boolean v) { this.suppressBob = v; return this; }
            public Builder blockChat(boolean v) { this.blockChat = v; return this; }
            public Builder blockScoreboard(boolean v) { this.blockScoreboard = v; return this; }
            public Builder blockActionBar(boolean v) { this.blockActionBar = v; return this; }
            public Builder renderPlayerModel(boolean v) { this.renderPlayerModel = v; return this; }
            public Builder pauseWhenGamePaused(boolean v) { this.pauseWhenGamePaused = v; return this; }
            /** 脚本间抢占控制：是否允许被其他脚本打断 */
            public Builder interruptible(boolean v) { this.interruptible = v; return this; }
            /** 用户退出控制：是否允许用户长按退出键提前结束播放 */
            public Builder skippable(boolean v) { this.skippable = v; return this; }
            /** 播完保持控制：播完后是否保持最后一帧 */
            public Builder holdAtEnd(boolean v) { this.holdAtEnd = v; return this; }

            public RuntimeBehavior build() {
                return new RuntimeBehavior(
                        blockKeyboard, blockMouse, blockMobAi,
                        hideHud, hideArm, suppressBob,
                        blockChat, blockScoreboard, blockActionBar,
                        renderPlayerModel,
                        pauseWhenGamePaused, interruptible, skippable, holdAtEnd
                );
            }
        }
    }
}
