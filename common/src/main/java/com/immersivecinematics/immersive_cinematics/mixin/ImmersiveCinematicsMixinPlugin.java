package com.immersivecinematics.immersive_cinematics.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * 跨平台 Mixin 配置插件。
 *
 * <p>当检测到渲染优化模组（Sodium / Rubidium / Embeddium）时，跳过
 * {@link LevelRendererMixin}：
 * <ul>
 *   <li>这些模组会 {@code @Overwrite} 原版 {@code LevelRenderer.setupRender}，
 *       导致我们的 {@code @Redirect} 无法注入；</li>
 *   <li>但它们基于 {@code Camera} / {@code Frustum} 的渲染中心已经会跟随
 *       {@code CameraMixin} 写入的虚拟相机，因此跳过不会丢失功能。</li>
 * </ul>
 *
 * <p>使用反射检测平台加载器，避免 common 模块依赖 Forge / Fabric API。
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
            optimizerModPresent = detectRenderOptimizer();
        }
        return optimizerModPresent;
    }

    private static boolean detectRenderOptimizer() {
        for (String modId : RENDER_OPTIMIZER_MOD_IDS) {
            if (isForgeModLoaded(modId) || isFabricModLoaded(modId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForgeModLoaded(String modId) {
        try {
            // Mixin 可能在 Forge 的 ModList 构建完成前运行，所以优先用早期可用的 LoadingModList。
            Class<?> fmlLoaderClass = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
            Object loadingModList = fmlLoaderClass.getMethod("getLoadingModList").invoke(null);
            if (loadingModList != null) {
                Object mods = loadingModList.getClass().getMethod("getMods").invoke(loadingModList);
                if (mods instanceof Iterable<?> iterable) {
                    for (Object mod : iterable) {
                        Object id = mod.getClass().getMethod("getModId").invoke(mod);
                        if (modId.equals(id)) {
                            return true;
                        }
                    }
                }
            }

            // 兜底：如果 ModList 已经可用，再查一次。
            Class<?> modListClass = Class.forName("net.minecraftforge.fml.ModList");
            Object modList = modListClass.getMethod("get").invoke(null);
            if (modList != null) {
                Method isLoaded = modListClass.getMethod("isLoaded", String.class);
                return Boolean.TRUE.equals(isLoaded.invoke(modList, modId));
            }
        } catch (Exception | LinkageError ignored) {
            // 检测失败时不跳过 mixin，保持原行为。
        }
        return false;
    }

    private static boolean isFabricModLoaded(String modId) {
        try {
            Class<?> fabricLoaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = fabricLoaderClass.getMethod("getInstance").invoke(null);
            Method isModLoaded = fabricLoaderClass.getMethod("isModLoaded", String.class);
            return Boolean.TRUE.equals(isModLoaded.invoke(loader, modId));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }
}
