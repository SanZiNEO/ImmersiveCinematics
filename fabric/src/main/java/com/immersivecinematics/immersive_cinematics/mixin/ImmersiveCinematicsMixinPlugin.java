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
 * <p>检测到 Sodium（Rubidium / Embeddium 为 Forge 侧，不会在 Fabric 出现）时跳过
 * {@link LevelRendererMixin}：Sodium 会 {@code @Overwrite} 原版
 * {@code LevelRenderer.setupRender}，导致我们的注入无法应用；
 * 但它基于 Camera/Frustum 的渲染中心已经会跟随 {@code CameraMixin} 写入的虚拟相机，
 * 因此跳过不会丢失功能。
 *
 * <p>直接使用 Fabric Loader API，不使用 Java 反射。
 */
public final class ImmersiveCinematicsMixinPlugin implements IMixinConfigPlugin {

    private static final String LEVEL_RENDERER_MIXIN = "com.immersivecinematics.immersive_cinematics.mixin.LevelRendererMixin";

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
        if (mixinClassName.equals(LEVEL_RENDERER_MIXIN)) {
            return !isRenderOptimizerPresent();
        }
        return true;
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
