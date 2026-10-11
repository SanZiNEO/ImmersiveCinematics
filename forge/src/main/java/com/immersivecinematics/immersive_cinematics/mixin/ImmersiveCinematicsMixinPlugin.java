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
 * 检测到 Sodium / Rubidium / Embeddium 时跳过 {@link LevelRendererMixin} 与 {@code LaneCullStateMixin}：
 * 这些模组会 {@code @Overwrite} 原版 {@code LevelRenderer.setupRender}，我们的 {@code @Redirect} /
 * {@code @ModifyVariable} 无法注入，vanilla 的剔除字段也成了死路径；但它们基于 Camera/Frustum 的渲染中心
 * 已经会跟随 {@code CameraMixin} 写入的虚拟相机，且 lane 渲染本身在检测到优化模组时已禁用，因此跳过不会丢失功能。
 *
 * 直接使用 Forge API，不使用 Java 反射。
 */
public final class ImmersiveCinematicsMixinPlugin implements IMixinConfigPlugin {

    /** 检测到渲染优化模组时跳过的 mixin（完整类名）。 */
    private static final Set<String> OPTIMIZER_SKIPPED_MIXINS = Set.of(
            "com.immersivecinematics.immersive_cinematics.mixin.LevelRendererMixin",
            "com.immersivecinematics.immersive_cinematics.mixin.LaneCullStateMixin"
    );

    private static final Set<String> RENDER_OPTIMIZER_MOD_IDS = Set.of(
            "sodium",
            "rubidium",
            "embeddium"
    );

    private static Boolean optimizerModPresent;

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
        return !(OPTIMIZER_SKIPPED_MIXINS.contains(mixinClassName) && isRenderOptimizerPresent());
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
        if (optimizerModPresent == null) {
            optimizerModPresent = detectRenderOptimizer();
        }
        return optimizerModPresent;
    }

    private static boolean detectRenderOptimizer() {
        // 早期阶段优先使用 LoadingModList
        var loadingModList = FMLLoader.getLoadingModList();
        if (loadingModList != null) {
            for (ModInfo mod : loadingModList.getMods()) {
                if (RENDER_OPTIMIZER_MOD_IDS.contains(mod.getModId())) {
                    return true;
                }
            }
        }

        // 兜底：ModList 已构建完成时再查一次
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
