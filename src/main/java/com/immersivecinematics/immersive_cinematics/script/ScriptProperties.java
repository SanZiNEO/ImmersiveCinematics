package com.immersivecinematics.immersive_cinematics.script;

/**
 * 脚本运行时属性单例 — 消费 ScriptMeta 中的14个标志位
 * <p>
 * 设计目标：
 * <ul>
 *   <li>ScriptPlayer.start() 时调用 apply(meta) 将脚本属性写入当前实例</li>
 *   <li>ScriptPlayer.stop() 时调用 revert() 重置为默认值</li>
 *   <li>Mixin 层和 Handler 通过 getCurrent() 读取当前脚本的行为配置</li>
 *   <li>不再直接检查 CameraManager.INSTANCE.isActive()，而是检查具体标志位</li>
 * </ul>
 * <p>
 * 这使得不同脚本可以有不同的运行时行为（如 hide_hud: false 的脚本播放时 HUD 可见）。
 */
public class ScriptProperties {

    // === 标志位（默认值） ===

    private boolean blockKeyboard = true;
    private boolean blockMouse = true;
    private boolean blockMobAi = false;
    private boolean hideHud = true;
    private boolean hideArm = true;
    private boolean suppressBob = true;
    private boolean blockChat = false;
    private boolean blockScoreboard = false;
    private boolean blockActionBar = false;
    private boolean blockParticles = false;
    private boolean renderPlayerModel = true;
    private boolean pauseWhenGamePaused = true;

    // === 单例访问 ===

    private static ScriptProperties current;

    /**
     * 获取当前活跃的脚本属性
     *
     * @return 当前脚本属性，无脚本播放时返回 null
     */
    public static ScriptProperties getCurrent() {
        return current;
    }

    /**
     * 应用脚本元信息到当前实例
     * <p>
     * 由 ScriptPlayer.start() 调用
     *
     * @param meta 脚本元信息
     */
    public void apply(ScriptMeta meta) {
        current = this;
        this.blockKeyboard = meta.isBlockKeyboard();
        this.blockMouse = meta.isBlockMouse();
        this.blockMobAi = meta.isBlockMobAi();
        this.hideHud = meta.isHideHud();
        this.hideArm = meta.isHideArm();
        this.suppressBob = meta.isSuppressBob();
        this.blockChat = meta.isBlockChat();
        this.blockScoreboard = meta.isBlockScoreboard();
        this.blockActionBar = meta.isBlockActionBar();
        this.blockParticles = meta.isBlockParticles();
        this.renderPlayerModel = meta.isRenderPlayerModel();
        this.pauseWhenGamePaused = meta.isPauseWhenGamePaused();
    }

    /**
     * 还原所有标志位为默认值
     * <p>
     * 由 ScriptPlayer.stop() 调用
     */
    public void revert() {
        current = null;
        this.blockKeyboard = true;
        this.blockMouse = true;
        this.blockMobAi = false;
        this.hideHud = true;
        this.hideArm = true;
        this.suppressBob = true;
        this.blockChat = false;
        this.blockScoreboard = false;
        this.blockActionBar = false;
        this.blockParticles = false;
        this.renderPlayerModel = true;
        this.pauseWhenGamePaused = true;
    }

    // === Getters ===

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
}
