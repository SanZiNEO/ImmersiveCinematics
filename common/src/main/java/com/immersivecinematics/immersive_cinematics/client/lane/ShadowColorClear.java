package com.immersivecinematics.immersive_cinematics.client.lane;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.util.Collection;

/**
 * 兼容补丁：光影包没有把 shadowcolor 放进 Iris 的清除列表时，在每个视角开始前强制清一次
 * shadowcolor main/alt。MakeUp 的 {@code buffersToBeCleared=[]} 会走到这里；Complementary 的
 * {@code [0,0,1,1]} 由 Iris 自己清，这里直接跳过。
 *
 * <p>只清颜色、不动深度；使用与 Iris shadow clear 相同的白色 clear color。目标通过
 * {@link ShaderCompat} 反射获取，不编译依赖 Iris/Oculus。</p>
 */
public final class ShadowColorClear {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean logged;

    private ShadowColorClear() {
    }

    public static void clearIfNeeded() {
        Object shadow = ShaderCompat.shadowTargets();
        if (shadow == null) {
            return;
        }
        Object clear = ShaderCompat.invoke(shadow, "getBuffersToBeCleared", new Class<?>[]{});
        if (containsColor(clear)) {
            return;
        }
        int colors = intValue(ShaderCompat.invoke(shadow, "getNumColorTextures", new Class<?>[]{}));
        if (colors <= 0) {
            return;
        }
        int[] buffers = new int[colors];
        for (int i = 0; i < colors; i++) {
            buffers[i] = i;
        }
        int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            clearSide(shadow, "createFramebufferWritingToMain", buffers);
            clearSide(shadow, "createFramebufferWritingToAlt", buffers);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[shadow-clear] forced shadowcolor clear colors={} res={}",
                    colors, intValue(ShaderCompat.invoke(shadow, "getResolution", new Class<?>[]{})));
        }
    }

    private static void clearSide(Object shadow, String factory, int[] buffers) {
        Object framebuffer = ShaderCompat.invoke(shadow, factory, new Class<?>[]{int[].class}, (Object) buffers);
        if (framebuffer == null) {
            return;
        }
        int fbo = intValue(ShaderCompat.invoke(framebuffer, "getId", new Class<?>[]{}));
        if (fbo <= 0) {
            return;
        }
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GlStateManager._clearColor(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, false);
        } finally {
            // Iris 1.7.6 / Oculus 1.8.0 只有 protected destroyInternal()；旧版可能是 destroy()。
            if (!ShaderCompat.invokeVoid(framebuffer, "destroyInternal")) {
                ShaderCompat.invokeVoid(framebuffer, "destroy");
            }
        }
    }

    private static boolean containsColor(Object clear) {
        if (clear == null) {
            return false;
        }
        if (clear instanceof Collection<?> collection) {
            return collection.contains(0) || collection.contains(1);
        }
        String text = String.valueOf(clear);
        return text.contains("0") || text.contains("1");
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
