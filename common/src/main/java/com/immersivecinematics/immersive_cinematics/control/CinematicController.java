package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.mixin.MouseHandlerAccessor;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
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
    /**
     * 强硬隐藏模式（脚本字段 {@code hard_hide_hud}）：三态，null = 未声明 → normal。
     * <p>见 {@code plans/0.3.6/hud-hard-hide.md}：hard 模式在 Forge 侧接管 {@code RenderGuiEvent.Pre}，
     * 连"不走 overlay 注册表、直接在 Pre 里自绘"的第三方 HUD 一起隐藏，再按白名单重画。
     */
    private Boolean hardHideHud = null;

    private Boolean hideArm = null;
    private Boolean suppressBob = null;
    private Boolean suppressDistortion = null;
    private boolean renderPlayerModel = true;
    private boolean blockMobAi = false;
    private java.util.Map<String, Boolean> hudLayers = new java.util.LinkedHashMap<>();

    /** 播放期间临时修改 screenEffectScale 的保存/恢复状态 */
    private double savedScreenEffectScale = 1.0;
    private boolean distortionOverridden = false;

    private boolean pauseWhenGamePaused = true;

    /**
     * 生命周期开关：按活跃实例写入（{@code skippable} / {@code interruptible} / {@code hold_at_end} /
     * {@code pause_when_game_paused}）。
     *
     * <p>这四个开关**不**参与运行时控制并集（{@code plans/0.3.6/parallel-playback.md} §3.1/§3.2）：
     * 跳过 / 打断 / 末尾保持 / 暂停联动的播放门控一律读实例本身（{@code PlaybackInstance}），
     * 这里的全局字段只服务 HUD 与输入路由的即时查询。
     *
     * @param behavior 活跃实例的行为快照；null = 无行为快照，回落默认值（与 {@link #revert()} 一致）
     */
    public void applyLifecycle(ScriptMeta.RuntimeBehavior behavior) {
        this.skippable = behavior == null || behavior.skippable();
        this.interruptible = behavior == null || behavior.interruptible();
        this.holdAtEnd = behavior != null && behavior.holdAtEnd();
        this.pauseWhenGamePaused = behavior == null || behavior.pauseWhenGamePaused();
    }

    /**
     * 运行时控制取并集（{@code plans/0.3.6/parallel-playback.md} §3.2）：行为开关在**所有活跃实例**之间
     * 逐位 OR —— 任一实例要求隐藏 / 屏蔽即生效；实例增删后由 {@code CameraManager} 重算，
     * 消费方（HUD 白名单 / 输入路由 / 各 Mixin）读到的仍是这里的全局开关，只是其值 = 并集。
     *
     * <p>三态开关（{@code hide_chat} 等，null = 未声明）先按**各实例自己的** {@code hide_hud} 解析成有效值再 OR，
     * 与消费方「null 则回落 {@code hide_hud}」的既有语义一致；因此**单实例**的并集结果与直接读该实例的行为
     * 逐位相同（零回归），多实例时取并集。
     *
     * <p>生命周期开关不参与并集（见 {@link #applyLifecycle}）。
     *
     * @param behaviors 活跃实例的行为快照（顺序无关；null 元素忽略；null / 空 = 无实例要求 → 恢复默认值）
     */
    public void recomputeUnion(java.util.List<ScriptMeta.RuntimeBehavior> behaviors) {
        java.util.List<ScriptMeta.RuntimeBehavior> active = new java.util.ArrayList<>();
        if (behaviors != null) {
            for (ScriptMeta.RuntimeBehavior b : behaviors) {
                if (b != null) active.add(b);
            }
        }
        if (active.isEmpty()) {
            // 无实例要求 → 行为开关回默认值；注意此处必须**恢复** screenEffectScale 而不是按默认值重新求值
            // （默认值 hide_hud=true 会让 shouldSuppressDistortion() 为真 → 反而全局压制扭曲）
            resetBehaviorToggles();
            restoreScreenEffectScale();
            return;
        }

        boolean uBlockKeyboard = false;
        boolean uBlockMouse = false;
        boolean uHideHud = false;
        boolean uRenderPlayerModel = false;
        boolean uBlockMobAi = false;
        for (ScriptMeta.RuntimeBehavior b : active) {
            uBlockKeyboard |= b.blockKeyboard();
            uBlockMouse |= b.blockMouse();
            uHideHud |= b.hideHud();
            uRenderPlayerModel |= b.renderPlayerModel();
            uBlockMobAi |= b.blockMobAi();
        }

        // hud_layers：并集必须在**全部实例**上求值——未声明该键的实例按自身的 hide_hud 参与
        // （否则该键会失去「其他实例 hide_hud=true」这一票），与消费方「未声明则回落 hide_hud」一致。
        java.util.Set<String> layerKeys = new java.util.LinkedHashSet<>();
        for (ScriptMeta.RuntimeBehavior b : active) {
            layerKeys.addAll(b.hudLayers().keySet());
        }
        java.util.Map<String, Boolean> uLayers = new java.util.LinkedHashMap<>();
        for (String key : layerKeys) {
            boolean hidden = false;
            for (ScriptMeta.RuntimeBehavior b : active) {
                hidden |= effective(b.hudLayers().get(key), b);
            }
            uLayers.put(key, hidden);
        }

        this.blockKeyboard = uBlockKeyboard;
        this.blockMouse = uBlockMouse;
        this.hideHud = uHideHud;
        this.hideChat = union(active, b -> effective(b.hideChat(), b));
        this.hideScoreboard = union(active, b -> effective(b.hideScoreboard(), b));
        this.hideActionBar = union(active, b -> effective(b.hideActionBar(), b));
        this.hideTitle = union(active, b -> effective(b.hideTitle(), b));
        this.hideSubtitles = union(active, b -> effective(b.hideSubtitles(), b));
        this.hideHotbar = union(active, b -> effective(b.hideHotbar(), b));
        this.hideCrosshair = union(active, b -> effective(b.hideCrosshair(), b));
        this.hideBossbar = union(active, b -> effective(b.hideBossbar(), b));
        this.hideSkipHud = union(active, b -> effective(b.hideSkipHud(), b));
        this.hardHideHud = union(active, CinematicController::effectiveHardHide);
        this.hideArm = union(active, b -> effective(b.hideArm(), b));
        this.suppressBob = union(active, b -> effective(b.suppressBob(), b));
        this.suppressDistortion = union(active, CinematicController::effectiveDistortion);
        this.renderPlayerModel = uRenderPlayerModel;
        this.blockMobAi = uBlockMobAi;
        this.hudLayers = uLayers;
        updateScreenEffectScale();
    }

    /** 三态开关在**单个实例**上的有效值：显式声明以声明为准，null = 未声明 → 回落该实例自己的 {@code hide_hud}。 */
    private static boolean effective(Boolean setting, ScriptMeta.RuntimeBehavior b) {
        return setting != null ? setting : b.hideHud();
    }

    /** {@code suppress_distortion} 在单个实例上的有效值：显式声明 → 回落 {@code suppress_bob} → 回落 {@code hide_hud}。 */
    private static boolean effectiveDistortion(ScriptMeta.RuntimeBehavior b) {
        if (b.suppressDistortion() != null) return b.suppressDistortion();
        return effective(b.suppressBob(), b);
    }

    /**
     * {@code hard_hide_hud} 在单个实例上的有效值：显式 {@code true} 才开启强硬模式。
     * <p>与 {@code hide_*} 的区别：null **不**回落 {@code hide_hud}——该开关是"模式"而非"显隐"，
     * 缺省即 normal（全局默认），否则默认脚本会凭空获得强硬行为。
     */
    private static boolean effectiveHardHide(ScriptMeta.RuntimeBehavior b) {
        return Boolean.TRUE.equals(b.hardHideHud());
    }

    /** 单实例开关取值函数（局部函数式接口，避免引入额外依赖）。 */
    private interface FlagOf {
        boolean get(ScriptMeta.RuntimeBehavior behavior);
    }

    /** 三态开关的并集：逐实例解析成有效值后 OR（{@code behaviors} 已剔除 null 且非空）。 */
    private static boolean union(java.util.List<ScriptMeta.RuntimeBehavior> behaviors, FlagOf effectiveValue) {
        boolean any = false;
        for (ScriptMeta.RuntimeBehavior b : behaviors) {
            any |= effectiveValue.get(b);
        }
        return any;
    }

    /** 行为开关恢复默认值（= {@link #revert()} 中与行为开关有关的部分）。 */
    private void resetBehaviorToggles() {
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
        this.hardHideHud = null;
        this.hideArm = null;
        this.suppressBob = null;
        this.suppressDistortion = null;
        this.renderPlayerModel = true;
        this.blockMobAi = false;
        this.hudLayers.clear();
    }

    public void revert() {
        this.skippable = true;
        this.interruptible = true;
        this.holdAtEnd = false;
        this.pauseWhenGamePaused = true;
        resetBehaviorToggles();
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
     *   <li>鼠标视角累积量：清空 {@code accumulatedDX/DY}，避免退出后第一次 {@code turnPlayer} 消费播放期间
     *       积压位移；</li>
     *   <li>键盘：{@code KeyMapping.setAll()} 把全部 KEYSYM 绑定按当前物理按键状态 setDown；</li>
     *   <li>鼠标按钮：{@code KeyMapping.setAll()} 只处理键盘，鼠标按键单独按 GLFW 物理状态同步。</li>
     * </ol>
     */
    public void syncInputStateAfterExit() {
        Minecraft mc = Minecraft.getInstance();

        // 1) 鼠标视角累积量清零——放在 level 判空之前，保证任何退出路径（含退出世界回标题界面）都清零。
        //    屏蔽期 MouseHandlerMixin.onMove 未被拦截，vanilla 仍在累积 accumulatedDX/DY；而本模组在
        //    turnPlayer HEAD ci.cancel()，vanilla 的“消费即清零”被整段跳过，累积量只增不减。不清零则退出后
        //    第一次 turnPlayer 会按积压位移转动视角（首帧跳变）。直写字段用 Accessor，不使用反射。
        MouseHandler mouseHandler = mc.mouseHandler;
        if (mouseHandler != null) {
            MouseHandlerAccessor accessor = (MouseHandlerAccessor) mouseHandler;
            accessor.ic$setAccumulatedDX(0.0D);
            accessor.ic$setAccumulatedDY(0.0D);
        }

        if (mc.level == null) return;

        // 2) 键盘状态重同步（替代 releaseAll 的"全量释放"）
        KeyMapping.setAll();

        // 3) 鼠标按键状态重同步（KeyMapping.setAll() 只处理键盘；set() 内部按 Key 的类型/值匹配所有绑定该键的映射）
        long window = mc.getWindow().getWindow();
        for (int button = 0; button < 8; button++) { // 常用鼠标按钮 0..7（左/右/中/侧键等）
            boolean down = GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), down);
        }
    }

    /**
     * 判断某个 HUD 层是否应该隐藏。固定分类优先使用对应单项开关，否则看 {@code hud_layers} 覆盖。
     */
    public boolean isLayerHidden(String layer) {
        switch (layer) {
            case "hotbar": return resolveLayer(hideHotbar);
            case "crosshair": return resolveLayer(hideCrosshair);
            case "chat": return resolveLayer(hideChat);
            case "scoreboard": return resolveLayer(hideScoreboard);
            case "bossbar": return resolveLayer(hideBossbar);
            case "subtitles": return resolveLayer(hideSubtitles);
            case "action_bar": return resolveLayer(hideActionBar);
            case "title": return resolveLayer(hideTitle);
            case "arm": return resolveLayer(hideArm);
            case "skip_hud": return resolveLayer(hideSkipHud);
            case "bob": return resolveLayer(suppressBob);
            default: {
                Boolean v = hudLayers.get(layer);
                return v != null ? v : hideHud;
            }
        }
    }

    private boolean resolveLayer(Boolean setting) {
        if (setting == null) return hideHud;
        return setting;
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

    /**
     * 强硬隐藏模式是否生效（脚本字段 {@code hard_hide_hud}，任一活跃实例显式声明 {@code true} 即生效）。
     * <p>该开关**不**参与「三态回落 {@code hide_hud}」：null = 未声明 = normal（全局默认值，本版本无配置键）。
     * 接管条件还需 {@code CameraManager.isActive()} 与 {@link #isHideHud()} 同时为真——{@code hide_hud=false}
     * 时无物可藏，不接管（见 {@code plans/0.3.6/hud-hard-hide.md} §3/§5）。
     */
    public boolean isHardHideHud() { return Boolean.TRUE.equals(hardHideHud); }

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
