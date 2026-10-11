package com.immersivecinematics.immersive_cinematics.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Fabric 专属 Mixin 配置插件。
 *
 * 检测到 Sodium（Rubidium / Embeddium 为 Forge 侧，不会在 Fabric 出现）时跳过 {@link LevelRendererMixin}
 * 与 {@code LaneCullStateMixin}：Sodium 会 {@code @Overwrite} 原版 {@code LevelRenderer.setupRender}，
 * 我们的注入无法应用，vanilla 的剔除字段也成了死路径；但它基于 Camera/Frustum 的渲染中心已经会跟随
 * {@code CameraMixin} 写入的虚拟相机，且 lane 渲染本身在检测到优化模组时已禁用，因此跳过不会丢失功能。
 *
 * 直接使用 Fabric Loader API，不使用 Java 反射。
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
            optimizerModPresent = RENDER_OPTIMIZER_MOD_IDS.stream()
                    .anyMatch(FabricLoader.getInstance()::isModLoaded);
        }
        return optimizerModPresent;
    }
}
