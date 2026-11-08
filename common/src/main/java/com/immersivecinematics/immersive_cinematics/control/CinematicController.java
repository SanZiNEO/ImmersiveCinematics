package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.mixin.MouseHandlerAccessor;
import com.immersivecinematics.immersive_cinematics.script.ScreenLayer;
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
    private boolean blockMobAi = false;

    /**
     * 画面层接管唯一模式：任一隐藏层 → 整帧清除 + 白名单重画。
     * 并集表 {@code screenLayers} 键 = 规范层名 / 原始动态键，值 = <b>隐藏</b>（与脚本 {@code screen_layers} 的显示极性相反）。
     */
    private java.util.Map<String, Boolean> screenLayers = new java.util.LinkedHashMap<>();

    /**
     * 派生值：15 个 HUD 规范层是否全部显式判为隐藏（见 {@link #computeHudAllHidden()}）。
     * {@code screen_layers} 未声明的键回落此值。
     */
    private boolean hudAllHidden = false;

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
     * <p>{@code screenLayers} 为键并集，只含各实例<b>显式声明</b>的键，取值 = <b>隐藏</b>
     * （实例声明值取反后 OR）。</p>
     *
     * @param anyInstance 是否有实例参与；{@code false} = 无实例要求（其余字段为默认值）
     * @param blockKeyboard 键鼠屏蔽（只由非编辑器实例投票）
     * @param blockMouse    同上
     * @param blockMobAi    生物 AI 屏蔽（全部实例投票）
     * @param screenLayers  画面层键并集（值 = 隐藏）
     */
    public record Union(
            boolean anyInstance,
            boolean blockKeyboard, boolean blockMouse,
            boolean blockMobAi,
            java.util.Map<String, Boolean> screenLayers) {

        /** 无实例要求：与 resetBehaviorToggles() 的默认值一致。 */
        static Union none() {
            return new Union(false,
                    false, false,
                    false,
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
     *   <li><b>内容行为</b>（{@code block_mob_ai}、{@code screen_layers}）：
     *       全部条目求 OR——编辑器播放与普通播放画面一致。</li>
     * </ul>
     *
     * <p>{@code screen_layers} 的键并集只取各实例显式声明的键，值取显示极性的反面再 OR——
     * 未声明该键的实例不投票（消费方对未声明键统一回落派生值）。画面层接管是唯一模式：
     * 任一隐藏层 → 整帧清除 + 白名单重画，没有模式字段参与并集。</p>
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
        boolean uBlockMobAi = false;
        for (BehaviorEntry entry : active) {
            ScriptMeta.RuntimeBehavior b = entry.behavior();
            if (!entry.editorDriven()) {
                uBlockKeyboard |= b.blockKeyboard();
                uBlockMouse |= b.blockMouse();
            }
            uBlockMobAi |= b.blockMobAi();
        }

        // screen_layers：键并集只含显式声明的键，值 = 显示 → 隐藏极性取反后 OR。
        java.util.Set<String> layerKeys = new java.util.LinkedHashSet<>();
        for (BehaviorEntry entry : active) {
            for (String key : entry.behavior().screenLayers().keySet()) {
                if (key != null) layerKeys.add(key);
            }
        }
        java.util.Map<String, Boolean> uLayers = new java.util.LinkedHashMap<>();
        for (String key : layerKeys) {
            boolean hidden = false;
            for (BehaviorEntry entry : active) {
                Boolean declared = entry.behavior().screenLayers().get(key);
                if (declared != null) hidden |= !declared;
            }
            uLayers.put(key, hidden);
        }

        return new Union(true,
                uBlockKeyboard, uBlockMouse,
                uBlockMobAi,
                uLayers);
    }

    /**
     * 写入一份并集快照：无实例要求时回默认值并恢复 {@code screenEffectScale}
     * （无实例 = 无层判隐藏，直接恢复播放前保存的值，不重新判定）。
     *
     * @param union 并集快照；{@code null} = 无实例要求
     */
    public void applyUnion(Union union) {
        if (union == null || !union.anyInstance()) {
            resetBehaviorToggles();
            restoreScreenEffectScale();
            return;
        }

        this.blockKeyboard = union.blockKeyboard();
        this.blockMouse = union.blockMouse();
        this.blockMobAi = union.blockMobAi();
        this.screenLayers = new java.util.LinkedHashMap<>(union.screenLayers());
        this.hudAllHidden = computeHudAllHidden();
        updateScreenEffectScale();
    }

    /**
     * 运行时控制取并集：行为开关在所有活跃实例间逐位 OR——任一实例要求屏蔽即生效，
     * 实例增删后由 {@code CameraManager} 重算。
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

    /**
     * 并集表的派生值：15 个 HUD 规范层在 {@link #screenLayers} 中是否全部显式判为隐藏。
     * 任一层缺失或为显示 → {@code false}。
     */
    private boolean computeHudAllHidden() {
        for (ScreenLayer layer : ScreenLayer.all()) {
            if (layer.space() != ScreenLayer.Space.HUD) continue;
            if (!Boolean.TRUE.equals(screenLayers.get(layer.id()))) return false;
        }
        return true;
    }

    /** 行为开关恢复默认值（= {@link #revert()} 中与行为开关有关的部分）。 */
    private void resetBehaviorToggles() {
        this.blockKeyboard = false;
        this.blockMouse = false;
        this.blockMobAi = false;
        this.screenLayers.clear();
        this.hudAllHidden = computeHudAllHidden();
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

    /**
     * 判断某个画面层是否应该隐藏。
     *
     * <p>查找顺序：并集表层名 → 并集表分类键（{@code categoryKey}，与层名不同时）→
     * 未声明键回落派生值 {@link #hudAllHidden}（15 个 HUD 规范层是否全部显式隐藏）。</p>
     *
     * @param layerName   层名：15 个规范层名或动态键（分类名 / 未注册 overlay 资源 ID）
     * @param categoryKey 平台分类键（可为 {@code null}）；与层名不同时作为第二优先键查询
     */
    public boolean isLayerHidden(String layerName, String categoryKey) {
        Boolean v = screenLayers.get(layerName);
        if (v != null) return v;
        if (categoryKey != null && !categoryKey.equals(layerName)) {
            v = screenLayers.get(categoryKey);
            if (v != null) return v;
        }
        return hudAllHidden;
    }

    /** 判断某个画面层是否应该隐藏（分类键 = 层名）。 */
    public boolean isLayerHidden(String layer) {
        return isLayerHidden(layer, layer);
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

    /** 画面层接管是否生效：有活跃实例 + 任一层判隐藏（唯一模式；Forge / Fabric 共用）。 */
    public boolean isScreenLayerTakeoverActive() {
        return CameraManager.INSTANCE.isActive() && anyScreenLayerHidden();
    }

    public boolean isBlockMobAi() { return blockMobAi; }

    /**
     * 是否屏蔽屏幕扭曲（反胃/传送门旋转）：统一走 {@code screen_effects} 层的判定
     * （{@code screen_layers} 声明，未声明回落派生值 {@link #hudAllHidden}）。
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
