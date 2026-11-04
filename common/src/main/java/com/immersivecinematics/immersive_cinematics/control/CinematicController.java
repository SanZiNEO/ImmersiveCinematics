package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.api.HudLayerRegistry;
import com.immersivecinematics.immersive_cinematics.mixin.MouseHandlerAccessor;
import com.immersivecinematics.immersive_cinematics.script.ScreenLayer;
import com.immersivecinematics.immersive_cinematics.script.ScreenLayerMode;
import com.immersivecinematics.immersive_cinematics.script.ScriptMeta;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.lwjgl.glfw.GLFW;

public class CinematicController {

    public static final CinematicController INSTANCE = new CinematicController();

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
     * 画面层模式（脚本字段 {@code screen_layer_mode}）：normal = 逐层抑制；allowlist = 接管整帧后按白名单重画。
     * 旧字段 {@code hard_hide_hud} 在解析期归一到这里。
     */
    private ScreenLayerMode screenLayerMode = ScreenLayerMode.NORMAL;

    private Boolean hideArm = null;
    private Boolean suppressBob = null;
    private Boolean suppressDistortion = null;
    private boolean renderPlayerModel = true;
    private boolean blockMobAi = false;
    /** 画面层并集：键 = 规范名 / 原始动态键，值 = <b>隐藏</b>（与脚本 {@code screen_layers} 的显示极性相反）。 */
    private java.util.Map<String, Boolean> screenLayers = new java.util.LinkedHashMap<>();

    /** 播放期间临时修改 screenEffectScale 的保存/恢复状态 */
    private double savedScreenEffectScale = 1.0;
    private boolean distortionOverridden = false;

    /**
     * 行为并集的一项：一个活跃实例的运行时行为快照 + 该实例是否由编辑器驱动。
     *
     * <p>{@code editorDriven = true} 的实例参与<b>内容行为并集</b>（HUD / lane / 相机 / 音频 / 覆盖层），
     * 但不参与<b>游戏控制</b>（键鼠屏蔽）——见 {@link #computeUnion(java.util.List)}。</p>
     */
    public record BehaviorEntry(ScriptMeta.RuntimeBehavior behavior, boolean editorDriven) {}

    /**
     * 全部活跃实例的行为并集快照（纯数据，不触达 Minecraft）。
     *
     * <p>三态字段 {@code null} = 未声明（消费方回落各自默认值）；{@code screenLayers} 为键并集，
     * 键已归一到 {@link ScreenLayer#canonicalize(String)}，取值 = <b>隐藏</b>（全部实例按各自
     * {@code screen_layers} / 旧字段 / {@code hide_hud} 解析后的 OR）。</p>
     *
     * @param anyInstance 是否有实例参与；{@code false} = 无实例要求（其余字段为默认值）
     * @param blockKeyboard 键鼠屏蔽（只由非编辑器实例投票）
     * @param blockMouse    同上
     * @param hideHud       内容行为：全部实例投票
     * @param screenLayerMode 画面层模式并集（任一实例 allowlist → allowlist）
     * @param screenLayers  画面层键并集（值 = 隐藏）
     */
    public record Union(
            boolean anyInstance,
            boolean blockKeyboard, boolean blockMouse,
            boolean hideHud,
            Boolean hideChat, Boolean hideScoreboard, Boolean hideActionBar, Boolean hideTitle,
            Boolean hideSubtitles, Boolean hideHotbar, Boolean hideCrosshair, Boolean hideBossbar,
            Boolean hideSkipHud, ScreenLayerMode screenLayerMode, Boolean hideArm,
            Boolean suppressBob, Boolean suppressDistortion,
            boolean renderPlayerModel, boolean blockMobAi,
            java.util.Map<String, Boolean> screenLayers) {

        /** 无实例要求：与 resetBehaviorToggles() 的默认值一致。 */
        static Union none() {
            return new Union(false,
                    false, false,
                    true,
                    null, null, null, null,
                    null, null, null, null,
                    null, ScreenLayerMode.NORMAL, null,
                    null, null,
                    true, false,
                    java.util.Map.of());
        }
    }

    /**
     * 计算全部活跃实例的行为并集（纯函数：不读不写 Minecraft 状态）。
     *
     * <p>分组：</p>
     * <ul>
     *   <li><b>游戏控制</b>（{@code block_keyboard} / {@code block_mouse}）：只对 {@code !editorDriven}
     *       的条目求 OR——编辑器播放不屏蔽键鼠，也不解除游戏实例的屏蔽。</li>
     *   <li><b>内容行为</b>（{@code hide_hud}、各 {@code hide_*}、{@code screen_layer_mode}、{@code hide_arm}、
     *       {@code suppress_bob}、{@code suppress_distortion}、{@code render_player_model}、
     *       {@code block_mob_ai}、{@code screen_layers}）：全部条目求 OR——编辑器播放与普通播放画面一致。</li>
     * </ul>
     *
     * <p>三态开关先按各条目自己的 {@code hide_hud} 解析成有效值再 OR（单实例结果与直接读该实例一致）。
     * {@code screen_layer_mode} 取 OR 的语义：任一实例要求 {@code allowlist} 即 {@code allowlist}；
     * {@code screen_layers} 的键归一后按「实例声明 → 旧字段 → hide_hud」逐实例解析成隐藏极性再 OR。</p>
     *
     * @param entries 活跃实例的行为条目（顺序无关；null 条目与 null behavior 忽略；
     *                null / 空 = 无实例要求 → {@link Union#none()}）
     */
    public static Union computeUnion(java.util.List<BehaviorEntry> entries) {
        java.util.List<BehaviorEntry> active = new java.util.ArrayList<>();
        if (entries != null) {
            for (BehaviorEntry entry : entries) {
                if (entry != null && entry.behavior() != null) active.add(entry);
            }
        }
        if (active.isEmpty()) return Union.none();

        boolean uBlockKeyboard = false;
        boolean uBlockMouse = false;
        boolean uHideHud = false;
        boolean uRenderPlayerModel = false;
        boolean uBlockMobAi = false;
        boolean anyAllowlist = false;
        for (BehaviorEntry entry : active) {
            ScriptMeta.RuntimeBehavior b = entry.behavior();
            if (!entry.editorDriven()) {
                uBlockKeyboard |= b.blockKeyboard();
                uBlockMouse |= b.blockMouse();
            }
            uHideHud |= b.hideHud();
            uRenderPlayerModel |= b.renderPlayerModel();
            uBlockMobAi |= b.blockMobAi();
            anyAllowlist |= b.screenLayerMode() == ScreenLayerMode.ALLOWLIST;
        }

        // screen_layers：键并集必须在全部实例上求值——未声明该键的实例按自身的旧字段 / hide_hud 参与
        // （否则该键会失去「其他实例 hide_hud=true」这一票），与消费方「未声明则回落」一致。
        // 键统一归一到规范名；未命中（health / status / 未注册 ID）保留原始键。
        java.util.Set<String> layerKeys = new java.util.LinkedHashSet<>();
        for (BehaviorEntry entry : active) {
            for (String key : entry.behavior().screenLayers().keySet()) {
                layerKeys.add(ScreenLayer.canonicalize(key));
            }
        }
        java.util.Map<String, Boolean> uLayers = new java.util.LinkedHashMap<>();
        for (String key : layerKeys) {
            boolean hidden = false;
            for (BehaviorEntry entry : active) {
                hidden |= perInstanceHidden(entry.behavior(), key);
            }
            uLayers.put(key, hidden);
        }

        return new Union(true,
                uBlockKeyboard, uBlockMouse,
                uHideHud,
                union(active, e -> effective(e.behavior().hideChat(), e.behavior())),
                union(active, e -> effective(e.behavior().hideScoreboard(), e.behavior())),
                union(active, e -> effective(e.behavior().hideActionBar(), e.behavior())),
                union(active, e -> effective(e.behavior().hideTitle(), e.behavior())),
                union(active, e -> effective(e.behavior().hideSubtitles(), e.behavior())),
                union(active, e -> effective(e.behavior().hideHotbar(), e.behavior())),
                union(active, e -> effective(e.behavior().hideCrosshair(), e.behavior())),
                union(active, e -> effective(e.behavior().hideBossbar(), e.behavior())),
                union(active, e -> effective(e.behavior().hideSkipHud(), e.behavior())),
                anyAllowlist ? ScreenLayerMode.ALLOWLIST : ScreenLayerMode.NORMAL,
                union(active, e -> effective(e.behavior().hideArm(), e.behavior())),
                union(active, e -> effective(e.behavior().suppressBob(), e.behavior())),
                union(active, e -> effectiveDistortion(e.behavior())),
                uRenderPlayerModel, uBlockMobAi,
                uLayers);
    }

    /**
     * 写入一份并集快照：无实例要求时回默认值并直接恢复 {@code screenEffectScale}
     * （默认 {@code hide_hud=true} 会让 {@link #shouldSuppressDistortion()} 为真，
     * 不能走 {@link #updateScreenEffectScale()}，否则反而全局压制扭曲）；有实例时写全部字段后按并集重算。
     *
     * @param union 并集快照；{@code null} = 无实例要求
     */
    public void applyUnion(Union union) {
        if (union == null || !union.anyInstance()) {
            // 无实例要求 → 行为开关回默认值；必须直接恢复 screenEffectScale（见上）
            resetBehaviorToggles();
            restoreScreenEffectScale();
            return;
        }

        this.blockKeyboard = union.blockKeyboard();
        this.blockMouse = union.blockMouse();
        this.hideHud = union.hideHud();
        this.hideChat = union.hideChat();
        this.hideScoreboard = union.hideScoreboard();
        this.hideActionBar = union.hideActionBar();
        this.hideTitle = union.hideTitle();
        this.hideSubtitles = union.hideSubtitles();
        this.hideHotbar = union.hideHotbar();
        this.hideCrosshair = union.hideCrosshair();
        this.hideBossbar = union.hideBossbar();
        this.hideSkipHud = union.hideSkipHud();
        this.screenLayerMode = union.screenLayerMode() != null ? union.screenLayerMode() : ScreenLayerMode.NORMAL;
        this.hideArm = union.hideArm();
        this.suppressBob = union.suppressBob();
        this.suppressDistortion = union.suppressDistortion();
        this.renderPlayerModel = union.renderPlayerModel();
        this.blockMobAi = union.blockMobAi();
        this.screenLayers = new java.util.LinkedHashMap<>(union.screenLayers());
        updateScreenEffectScale();
    }

    /**
     * 运行时控制取并集：行为开关在所有活跃实例间逐位 OR——任一实例要求隐藏 / 屏蔽即生效，
     * 实例增删后由 {@code CameraManager} 重算；三态开关先按各实例的 {@code hide_hud} 解析成有效值再 OR
     * （单实例结果与直接读该实例一致）。
     * 键鼠屏蔽只由非编辑器实例投票（编辑器播放不参与游戏控制），其余内容行为全部实例参与。
     * 生命周期开关（{@code skippable} / {@code interruptible} / {@code hold_at_end} /
     * {@code pause_when_game_paused}）不参与并集：
     * 播放门控读活跃实例本身，HUD / 输入路由的即时查询走 {@code CameraManager} 并集或顶层实例接口。
     *
     * @param entries 活跃实例的行为条目（顺序无关；null 元素忽略；null / 空 = 无实例要求 → 恢复默认值）
     */
    public void recomputeUnion(java.util.List<BehaviorEntry> entries) {
        applyUnion(computeUnion(entries));
    }

    /** 三态开关在单个实例上的有效值：显式声明以声明为准，null = 未声明 → 回落该实例自己的 {@code hide_hud}。 */
    private static boolean effective(Boolean setting, ScriptMeta.RuntimeBehavior b) {
        return setting != null ? setting : b.hideHud();
    }

    /**
     * {@code suppress_distortion} 在单个实例上的有效值：显式声明 → 回落 {@code suppress_bob} →
     * 回落 {@code hide_hud}。
     */
    private static boolean effectiveDistortion(ScriptMeta.RuntimeBehavior b) {
        if (b.suppressDistortion() != null) return b.suppressDistortion();
        return effective(b.suppressBob(), b);
    }

    /**
     * {@code screen_layers} 键在单个实例上的有效值（<b>隐藏</b>极性）：
     * 实例显式声明该键（值 = 显示）→ 取反；否则若键命中规范层 → 走旧字段表；
     * 否则（{@code health} / {@code status} / 未注册 ID 等动态键）→ 回落该实例的 {@code hide_hud}。
     */
    private static boolean perInstanceHidden(ScriptMeta.RuntimeBehavior b, String key) {
        Boolean declared = b.screenLayers().get(key);
        if (declared != null) return !declared;
        ScreenLayer layer = ScreenLayer.byId(key);
        if (layer != null) return ScreenLayer.legacyHidden(layer, b);
        return b.hideHud();
    }

    /** 单条目开关取值函数（局部函数式接口，避免引入额外依赖）。 */
    private interface FlagOf {
        boolean get(BehaviorEntry entry);
    }

    /** 三态开关的并集：逐条目解析成有效值后 OR（{@code entries} 已剔除 null 且非空）。 */
    private static boolean union(java.util.List<BehaviorEntry> entries, FlagOf effectiveValue) {
        boolean any = false;
        for (BehaviorEntry entry : entries) {
            any |= effectiveValue.get(entry);
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
        this.screenLayerMode = ScreenLayerMode.NORMAL;
        this.hideArm = null;
        this.suppressBob = null;
        this.suppressDistortion = null;
        this.renderPlayerModel = true;
        this.blockMobAi = false;
        this.screenLayers.clear();
    }

    public void revert() {
        resetBehaviorToggles();
        restoreScreenEffectScale();
    }

    public boolean isBlockKeyboard() { return blockKeyboard; }
    public boolean isBlockMouse() { return blockMouse; }

    public void releaseAllKeys() {
        KeyMapping.releaseAll();
    }

    /**
     * 播放退出后的输入状态重同步（优雅交接）：播放开始用 {@link #releaseAllKeys()} 清旧状态，退出改用本方法
     * 按实际物理按键状态重建，避免玩家持续按住 W 时退出卡键（要松开重按才恢复）。三步：
     * 1. 鼠标视角累积量：清空 {@code accumulatedDX/DY}，避免退出后第一次 {@code turnPlayer} 消费播放期积压位移；
     * 2. 键盘：{@code KeyMapping.setAll()} 把全部 KEYSYM 绑定按当前物理按键状态 setDown；
     * 3. 鼠标按钮：{@code KeyMapping.setAll()} 只处理键盘，鼠标按键单独按 GLFW 物理状态同步。
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

    /** 旧字段读取面：控制器字段的只读适配（实例复用——每帧判定不分配）。 */
    private final ScreenLayer.LegacyFlags legacyFlags = new ScreenLayer.LegacyFlags() {
        @Override public boolean hideHud() { return CinematicController.this.hideHud; }
        @Override public Boolean hideHotbar() { return CinematicController.this.hideHotbar; }
        @Override public Boolean hideCrosshair() { return CinematicController.this.hideCrosshair; }
        @Override public Boolean hideBossbar() { return CinematicController.this.hideBossbar; }
        @Override public Boolean hideChat() { return CinematicController.this.hideChat; }
        @Override public Boolean hideScoreboard() { return CinematicController.this.hideScoreboard; }
        @Override public Boolean hideActionBar() { return CinematicController.this.hideActionBar; }
        @Override public Boolean hideTitle() { return CinematicController.this.hideTitle; }
        @Override public Boolean hideSubtitles() { return CinematicController.this.hideSubtitles; }
        @Override public Boolean hideSkipHud() { return CinematicController.this.hideSkipHud; }
        @Override public Boolean hideArm() { return CinematicController.this.hideArm; }
        @Override public Boolean suppressBob() { return CinematicController.this.suppressBob; }
        @Override public Boolean suppressDistortion() { return CinematicController.this.suppressDistortion; }
        @Override public boolean renderPlayerModel() { return CinematicController.this.renderPlayerModel; }
    };

    private ScreenLayer.LegacyFlags legacyFlags() { return legacyFlags; }

    /**
     * 判断某个画面层是否应该隐藏。
     *
     * <p>查找顺序：并集表规范名 → 并集表原始键（{@code categoryKey}，Forge 分类名）→ 旧字段表
     * （{@link ScreenLayer#legacyHidden(ScreenLayer, ScreenLayer.LegacyFlags)}）→ {@code hide_hud}。</p>
     *
     * @param layerName   层名（规范名或别名均可，内部归一）
     * @param categoryKey 平台分类键（可为 {@code null}）；与规范名不同时作为第二优先键查询
     */
    public boolean isLayerHidden(String layerName, String categoryKey) {
        String canonical = ScreenLayer.canonicalize(layerName);
        Boolean v = screenLayers.get(canonical);
        if (v != null) return v;
        if (categoryKey != null && !categoryKey.equals(canonical)) {
            v = screenLayers.get(categoryKey);
            if (v != null) return v;
        }
        ScreenLayer layer = ScreenLayer.byId(canonical);
        if (layer != null) return ScreenLayer.legacyHidden(layer, legacyFlags());
        return hideHud;
    }

    /** 判断某个画面层是否应该隐藏（分类键 = 层名）。 */
    public boolean isLayerHidden(String layer) {
        return isLayerHidden(layer, layer);
    }

    /** 画面层模式并集（非 null）。 */
    public ScreenLayerMode screenLayerMode() {
        return screenLayerMode != null ? screenLayerMode : ScreenLayerMode.NORMAL;
    }

    /** 生效的 allowlist 模式：脚本要求 allowlist 且平台支持整帧接管（Forge 支持，Fabric 降级）。 */
    public boolean isAllowlistMode() {
        return screenLayerMode == ScreenLayerMode.ALLOWLIST && HudLayerRegistry.supportsScreenLayerTakeover();
    }

    /** 15 个规范层或并集表任一动态键要求隐藏。 */
    public boolean anyScreenLayerHidden() {
        for (ScreenLayer layer : ScreenLayer.all()) {
            if (isLayerHidden(layer.id())) return true;
        }
        for (String key : screenLayers.keySet()) {
            if (isLayerHidden(key, key)) return true;
        }
        return false;
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

    public Boolean isHideArm() { return hideArm; }
    public Boolean isSuppressBob() { return suppressBob; }
    public Boolean isSuppressDistortion() { return suppressDistortion; }
    public boolean isRenderPlayerModel() { return renderPlayerModel; }
    public boolean isBlockMobAi() { return blockMobAi; }

    /**
     * 是否屏蔽屏幕扭曲（反胃/传送门旋转）：统一走 {@code screen_effects} 层的判定链
     * （{@code suppress_distortion} → {@code suppress_bob} → {@code hide_hud}）。
     */
    private boolean shouldSuppressDistortion() {
        return isLayerHidden(ScreenLayer.SCREEN_EFFECTS.id());
    }

    /**
     * 根据当前脚本设置临时修改原版 {@code screenEffectScale}。
     * 需要屏蔽时设为 0，不需要时恢复原值。
     */
    private void updateScreenEffectScale() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
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
