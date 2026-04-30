package com.immersivecinematics.immersive_cinematics.script;

import javax.annotation.Nullable;

/**
 * 脚本属性 — 脚本的身份信息和运行时行为配置
 * <p>
 * 元信息：id/name/author/version/description
 * 运行时行为：14个布尔标志，控制电影模式下的各种屏蔽/显示行为
 * 插值控制：脚本级插值（全局默认风格），与片段级插值数学组合
 * <p>
 * 三层插值体系（数学组合模式）：
 * <ul>
 *   <li>脚本级 interpolation — 全局默认曲线风格，先应用</li>
 *   <li>片段级 interpolation — 单片段曲线，后应用，与脚本级组合</li>
 *   <li>关键帧级 interpolation — 逐段覆盖片段级（仅 SEGMENT 模式），仍与脚本级组合</li>
 * </ul>
 * 组合公式：adjustedT = applyCurve(applyCurve(t, scriptInterp), clipOrKeyframeInterp)
 * <p>
 * linear 是恒等函数 linear(t)=t，脚本级设 linear = 不施加全局曲线，片段级单独生效。
 */
public class ScriptMeta {

    // ========== 元信息 ==========

    /** 脚本唯一ID，字母+数字+下划线，最长32字符 */
    private final String id;

    /** 编辑器和脚本管理器中显示的名称，最长50字符 */
    private final String name;

    /** 作者信息，最长30字符 */
    private final String author;

    /** 格式版本号，当前=3 */
    private final int version;

    /** 脚本描述，最长200字符 */
    private final String description;

    // ========== 运行时行为 ==========

    /** 屏蔽键盘输入（WASD移动等），默认 true */
    private final boolean blockKeyboard;

    /** 屏蔽鼠标输入（视角转动、点击），默认 true */
    private final boolean blockMouse;

    /** 屏蔽生物对玩家的AI（防止攻击/跟踪干扰运镜），默认 false */
    private final boolean blockMobAi;

    /** 隐藏HUD（使用白名单机制保留必要overlay），默认 true */
    private final boolean hideHud;

    /** 隐藏手臂渲染，默认 true */
    private final boolean hideArm;

    /** 屏蔽受伤摇晃、行走摇晃、反胃/下界传送门扭曲效果，默认 true */
    private final boolean suppressBob;

    /** 屏蔽聊天消息显示，默认 false */
    private final boolean blockChat;

    /** 屏蔽计分板侧栏，默认 false */
    private final boolean blockScoreboard;

    /** 屏蔽动作栏文字，默认 false */
    private final boolean blockActionBar;

    /** 屏蔽他人粒子效果（多人时防止穿帮），默认 false */
    private final boolean blockParticles;

    /** 是否渲染玩家自身模型（第一人称叙事可设false），默认 true */
    private final boolean renderPlayerModel;

    /** 暂停菜单时是否冻结脚本（false=暂停时脚本继续播放），默认 true */
    private final boolean pauseWhenGamePaused;

    /** 是否允许被其他脚本打断，默认 true */
    private final boolean interruptible;

    /** 脚本播完后保持最后一帧，不自动退出电影模式，默认 false */
    private final boolean holdAtEnd;

    // ========== 插值控制 ==========

    /**
     * 脚本级插值 — 全局默认曲线风格
     * <p>
     * 与片段级插值数学组合：先应用脚本级，再应用片段级。
     * null = LINEAR（恒等函数，不施加全局曲线，片段级单独生效）。
     * <p>
     * 示例：脚本级 smooth + 片段级 ease_in =
     * adjustedT = ease_in(smooth(t))，整体缓入缓出 + 局部缓入。
     */
    @Nullable
    private final InterpolationType interpolation;

    public ScriptMeta(String id, String name, String author, int version, String description,
                      boolean blockKeyboard, boolean blockMouse, boolean blockMobAi,
                      boolean hideHud, boolean hideArm, boolean suppressBob,
                      boolean blockChat, boolean blockScoreboard, boolean blockActionBar,
                      boolean blockParticles, boolean renderPlayerModel,
                      boolean pauseWhenGamePaused, boolean interruptible, boolean holdAtEnd,
                      @Nullable InterpolationType interpolation) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.description = description;
        this.blockKeyboard = blockKeyboard;
        this.blockMouse = blockMouse;
        this.blockMobAi = blockMobAi;
        this.hideHud = hideHud;
        this.hideArm = hideArm;
        this.suppressBob = suppressBob;
        this.blockChat = blockChat;
        this.blockScoreboard = blockScoreboard;
        this.blockActionBar = blockActionBar;
        this.blockParticles = blockParticles;
        this.renderPlayerModel = renderPlayerModel;
        this.pauseWhenGamePaused = pauseWhenGamePaused;
        this.interruptible = interruptible;
        this.holdAtEnd = holdAtEnd;
        this.interpolation = interpolation;
    }

    /**
     * 兼容旧构造器 — interpolation 默认 null（LINEAR）
     */
    public ScriptMeta(String id, String name, String author, int version, String description,
                      boolean blockKeyboard, boolean blockMouse, boolean blockMobAi,
                      boolean hideHud, boolean hideArm, boolean suppressBob,
                      boolean blockChat, boolean blockScoreboard, boolean blockActionBar,
                      boolean blockParticles, boolean renderPlayerModel,
                      boolean pauseWhenGamePaused, boolean interruptible, boolean holdAtEnd) {
        this(id, name, author, version, description,
                blockKeyboard, blockMouse, blockMobAi,
                hideHud, hideArm, suppressBob,
                blockChat, blockScoreboard, blockActionBar,
                blockParticles, renderPlayerModel,
                pauseWhenGamePaused, interruptible, holdAtEnd,
                null);
    }

    // ========== 元信息 Getter ==========

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAuthor() { return author; }
    public int getVersion() { return version; }
    public String getDescription() { return description; }

    // ========== 运行时行为 Getter ==========

    public boolean isBlockKeyboard() { return blockKeyboard; }
    public boolean isBlockMouse() { return blockMouse; }
    public boolean isBlockMobAi() { return blockMobAi; }
    public boolean isHideHud() { return hideHud; }
    public boolean isHideArm() { return hideArm; }
    public boolean isSuppressBob() { return suppressBob; }
    public boolean isBlockChat() { return blockChat; }
    public boolean isBlockScoreboard() { return blockScoreboard; }
    public boolean isBlockActionBar() { return blockActionBar; }
    public boolean isBlockParticles() { return blockParticles; }
    public boolean isRenderPlayerModel() { return renderPlayerModel; }
    public boolean isPauseWhenGamePaused() { return pauseWhenGamePaused; }
    public boolean isInterruptible() { return interruptible; }
    public boolean isHoldAtEnd() { return holdAtEnd; }

    // ========== 插值控制 Getter ==========

    /**
     * 获取脚本级插值类型
     * @return 插值类型，null 表示 LINEAR（不施加全局曲线）
     */
    @Nullable
    public InterpolationType getInterpolation() { return interpolation; }

    /**
     * 获取有效的脚本级插值类型（null → LINEAR）
     */
    public InterpolationType getEffectiveInterpolation() {
        return interpolation != null ? interpolation : InterpolationType.LINEAR;
    }

    @Override
    public String toString() {
        return String.format("ScriptMeta{id=%s, name=%s, author=%s, v%d, interp=%s}",
                id, name, author, version, interpolation);
    }
}
