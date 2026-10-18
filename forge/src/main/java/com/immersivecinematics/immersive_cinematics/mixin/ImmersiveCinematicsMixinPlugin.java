package com.immersivecinematics.immersive_cinematics.mixin;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Forge 专属 Mixin 配置插件。
 *
 * <p>检测到渲染优化模组（Sodium / Rubidium / Embeddium）时，跳过只针对原版
 * {@code LevelRenderer.setupRender} 的两个 mixin：</p>
 * <ul>
 *   <li>{@code LevelRendererVanillaMixin}（视图中心 + 遮挡开关）；</li>
 *   <li>{@code LaneCullStateMixin}（vanilla 剔除字段的 per-lane 交换）。</li>
 * </ul>
 *
 * <p>原因：这些模组会 {@code @Overwrite} 原版 {@code setupRender}，注入无法应用；但它们基于
 * {@code Camera} / {@code Frustum} 的渲染中心会跟随 {@code CameraMixin} 写入的虚拟相机，
 * 因此跳过等于<strong>改走它们的优化路径</strong>，不是禁用 lane。lane 渲染本身由
 * {@code LaneRenderer} 照常执行；缺少 {@code LaneCullBridge} 时只跳过剔除状态交换。</p>
 */
public final class ImmersiveCinematicsMixinPlugin implements IMixinConfigPlugin {

    private static final String PREFIX = "com.immersivecinematics.immersive_cinematics.mixin.";

    /** 只在 Oculus/Iris 存在时应用的兼容 mixin（只读注册；Iris* 全部按此前缀判定）。 */
    private static final String IRIS_COMPAT_PREFIX = PREFIX + "Iris";

    /** 只针对原版 setupRender 的 mixin：优化模组改写该方法时跳过（改用它们的路径）。 */
    private static final Set<String> VANILLA_SETUP_RENDER_MIXINS = Set.of(
            PREFIX + "LevelRendererVanillaMixin",
            PREFIX + "LaneCullStateMixin"
    );

    private static final Set<String> RENDER_OPTIMIZER_MOD_IDS = Set.of(
            "sodium",
            "rubidium",
            "embeddium"
    );

    private static Boolean optimizerPresent;

    @Override
    public void onLoad(String mixinPackage) {
        // no-op
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(IRIS_COMPAT_PREFIX)) {
            return isIrisPresent();
        }
        if (VANILLA_SETUP_RENDER_MIXINS.contains(mixinClassName)) {
            return !isRenderOptimizerPresent();
        }
        return true;
    }

    private static boolean isIrisPresent() {
        var loadingModList = FMLLoader.getLoadingModList();
        if (loadingModList != null) {
            for (ModInfo mod : loadingModList.getMods()) {
                String id = mod.getModId();
                if ("oculus".equals(id) || "iris".equals(id)) {
                    return true;
                }
            }
        }
        ModList modList = ModList.get();
        return modList != null && (modList.isLoaded("oculus") || modList.isLoaded("iris"));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    private static boolean isRenderOptimizerPresent() {
        if (optimizerPresent == null) {
            optimizerPresent = detectRenderOptimizer();
        }
        return optimizerPresent;
    }

    private static boolean detectRenderOptimizer() {
        // Mixin 可能在 ModList 构建完成前运行：优先用早期可用的 LoadingModList。
        var loadingModList = FMLLoader.getLoadingModList();
        if (loadingModList != null) {
            for (ModInfo mod : loadingModList.getMods()) {
                if (RENDER_OPTIMIZER_MOD_IDS.contains(mod.getModId())) {
                    return true;
                }
            }
        }
        ModList modList = ModList.get();
        if (modList != null) {
            for (String modId : RENDER_OPTIMIZER_MOD_IDS) {
                if (modList.isLoaded(modId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
