package com.immersivecinematics.immersive_cinematics.script;

import javax.annotation.Nullable;

/**
 * 脚本属性 — 脚本的身份信息和运行时行为配置
 * <p>
 * 重构后（O1）：
 * <ul>
 *   <li>运行时行为抽取为 {@link RuntimeBehavior} 值对象</li>
 *   <li>构造器参数从 20 个减少到 7 个</li>
 *   <li>ScriptParser 使用 Builder 模式构建 RuntimeBehavior</li>
 * </ul>
 * <p>
 * 插值控制（F2 修复后支持两种曲线组合模式）：
 * <ul>
 *   <li>脚本级 interpolation — 片段级未指定时的默认值</li>
 *   <li>片段级 interpolation — 覆盖脚本级默认值</li>
 *   <li>关键帧级 interpolation — 覆盖片段级（仅 SEGMENT 模式）</li>
 *   <li>curveCompositionMode — OVERRIDE（默认）或 COMPOSED（数学组合/双重平滑）</li>
 * </ul>
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

    // ========== 插值控制 ==========

    @Nullable
    private final InterpolationType interpolation;

    /** 曲线组合模式：OVERRIDE（默认）或 COMPOSED（数学组合/双重平滑） */
    @Nullable
    private final CurveCompositionMode curveCompositionMode;

    /**
     * 简化构造器 — 使用 RuntimeBehavior 值对象
     */
    public ScriptMeta(String id, String name, String author, int version, String description,
                      RuntimeBehavior behavior, @Nullable InterpolationType interpolation,
                      @Nullable CurveCompositionMode curveCompositionMode) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.description = description;
        this.behavior = behavior;
        this.interpolation = interpolation;
        this.curveCompositionMode = curveCompositionMode;
    }

    /**
     * 兼容旧构造器 — 从 20 个参数构建（内部转换为 RuntimeBehavior）
     */
    public ScriptMeta(String id, String name, String author, int version, String description,
                      boolean blockKeyboard, boolean blockMouse, boolean blockMobAi,
                      boolean hideHud, boolean hideArm, boolean suppressBob,
                      boolean blockChat, boolean blockScoreboard, boolean blockActionBar,
                      boolean blockParticles, boolean renderPlayerModel,
                      boolean pauseWhenGamePaused, boolean interruptible, boolean holdAtEnd,
                      @Nullable InterpolationType interpolation) {
        this(id, name, author, version, description,
                new RuntimeBehavior(blockKeyboard, blockMouse, blockMobAi,
                        hideHud, hideArm, suppressBob,
                        blockChat, blockScoreboard, blockActionBar,
                        blockParticles, renderPlayerModel,
                        pauseWhenGamePaused, interruptible, holdAtEnd),
                interpolation, null);
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
    public boolean isBlockMobAi() { return behavior.blockMobAi(); }
    public boolean isHideHud() { return behavior.hideHud(); }
    public boolean isHideArm() { return behavior.hideArm(); }
    public boolean isSuppressBob() { return behavior.suppressBob(); }
    public boolean isBlockChat() { return behavior.blockChat(); }
    public boolean isBlockScoreboard() { return behavior.blockScoreboard(); }
    public boolean isBlockActionBar() { return behavior.blockActionBar(); }
    public boolean isBlockParticles() { return behavior.blockParticles(); }
    public boolean isRenderPlayerModel() { return behavior.renderPlayerModel(); }
    public boolean isPauseWhenGamePaused() { return behavior.pauseWhenGamePaused(); }
    public boolean isInterruptible() { return behavior.interruptible(); }
    public boolean isHoldAtEnd() { return behavior.holdAtEnd(); }

    // ========== 插值控制 Getter ==========

    @Nullable
    public InterpolationType getInterpolation() { return interpolation; }

    @Nullable
    public CurveCompositionMode getCurveCompositionMode() { return curveCompositionMode; }

    public InterpolationType getEffectiveInterpolation() {
        return interpolation != null ? interpolation : InterpolationType.LINEAR;
    }

    @Override
    public String toString() {
        return String.format("ScriptMeta{id=%s, name=%s, author=%s, v%d, interp=%s}",
                id, name, author, version, interpolation);
    }

    /**
     * 运行时行为值对象 — 14 个布尔标志位
     * <p>
     * 使用 Java record 实现，不可变。
     * 默认值通过 {@link #DEFAULT} 常量提供。
     * Builder 模式通过 {@link #builder()} 获取。
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
            boolean blockParticles,
            boolean renderPlayerModel,
            boolean pauseWhenGamePaused,
            boolean interruptible,
            boolean holdAtEnd
    ) {
        /** 默认运行时行为 */
        public static final RuntimeBehavior DEFAULT = new RuntimeBehavior(
                true, true, false, true, true, true,
                false, false, false, false, true,
                true, true, false
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
            private boolean blockParticles = DEFAULT.blockParticles();
            private boolean renderPlayerModel = DEFAULT.renderPlayerModel();
            private boolean pauseWhenGamePaused = DEFAULT.pauseWhenGamePaused();
            private boolean interruptible = DEFAULT.interruptible();
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
            public Builder blockParticles(boolean v) { this.blockParticles = v; return this; }
            public Builder renderPlayerModel(boolean v) { this.renderPlayerModel = v; return this; }
            public Builder pauseWhenGamePaused(boolean v) { this.pauseWhenGamePaused = v; return this; }
            public Builder interruptible(boolean v) { this.interruptible = v; return this; }
            public Builder holdAtEnd(boolean v) { this.holdAtEnd = v; return this; }

            public RuntimeBehavior build() {
                return new RuntimeBehavior(
                        blockKeyboard, blockMouse, blockMobAi,
                        hideHud, hideArm, suppressBob,
                        blockChat, blockScoreboard, blockActionBar,
                        blockParticles, renderPlayerModel,
                        pauseWhenGamePaused, interruptible, holdAtEnd
                );
            }
        }
    }
}
