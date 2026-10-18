package com.immersivecinematics.immersive_cinematics.client.lane;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 每视角颜色历史隔离：把光影包用于时域历史的 colortex 按视角保存/恢复。
 *
 * <p>历史目标不再写死：由 {@link ShaderCompat} 从 Iris/Oculus 的 flipped 集合自动发现
 * （MakeUp 是 colortex3，Complementary 是 colortex2；BSL 或其他包用标准翻转缓冲也能覆盖）。
 * 探测不到时不隔离，由日志说明。</p>
 *
 * <p>实现：每个视角、每个目标各一对快照纹理（main/alt），格式/尺寸跟随 live；视角开始前
 * 快照 → live，结束后 live → 快照；首帧没有快照时清空 live。GPU blit，临时 FBO 持久复用。</p>
 *
 * <p>光影 pipeline 切换时必须 {@link #reset()}：旧快照属于旧 pipeline，不能把旧历史 blit 回新 live。</p>
 */
public final class ColorHistoryCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, Map<Integer, Snapshot>> snapshots = new HashMap<>();

    private static int readFbo;
    private static int drawFbo;
    private static boolean logged;

    private ColorHistoryCapture() {
    }

    /** 视角开始前恢复该视角的颜色历史；首帧清空 live。 */
    public static void restore(String view) {
        if (view == null) {
            return;
        }
        for (int targetIndex : ShaderCompat.historyTargets()) {
            Object target = ShaderCompat.target(targetIndex);
            if (target == null) {
                continue;
            }
            int width = ShaderCompat.width(target);
            int height = ShaderCompat.height(target);
            int format = ShaderCompat.format(target);
            if (width <= 0 || height <= 0 || format == 0) {
                continue;
            }
            Map<Integer, Snapshot> perView = snapshots.get(view);
            boolean first = perView == null || !perView.containsKey(targetIndex);
            Snapshot snapshot = snapshot(view, targetIndex, width, height, format);
            if (snapshot == null) {
                continue;
            }
            int liveMain = ShaderCompat.mainTexture(target);
            int liveAlt = ShaderCompat.altTexture(target);
            if (liveMain <= 0 || liveAlt <= 0) {
                continue;
            }
            if (first) {
                clear(liveMain, width, height);
                clear(liveAlt, width, height);
                if (!logged) {
                    logged = true;
                    LOGGER.info("[color-history] first frame view={} target={} size={}x{}",
                            view, targetIndex, width, height);
                }
            } else {
                copy(snapshot.main, liveMain, width, height);
                copy(snapshot.alt, liveAlt, width, height);
            }
        }
    }

    /** 视角结束后保存该视角的颜色历史。 */
    public static void save(String view) {
        if (view == null) {
            return;
        }
        for (int targetIndex : ShaderCompat.historyTargets()) {
            Object target = ShaderCompat.target(targetIndex);
            if (target == null) {
                continue;
            }
            int width = ShaderCompat.width(target);
            int height = ShaderCompat.height(target);
            int format = ShaderCompat.format(target);
            if (width <= 0 || height <= 0 || format == 0) {
                continue;
            }
            Snapshot snapshot = snapshot(view, targetIndex, width, height, format);
            if (snapshot == null) {
                continue;
            }
            int liveMain = ShaderCompat.mainTexture(target);
            int liveAlt = ShaderCompat.altTexture(target);
            if (liveMain <= 0 || liveAlt <= 0) {
                continue;
            }
            copy(liveMain, snapshot.main, width, height);
            copy(liveAlt, snapshot.alt, width, height);
        }
    }

    /** 光影 pipeline 切换：旧快照属于旧 pipeline，全部删除，首帧重新清空 live。 */
    public static void reset() {
        int count = 0;
        for (Map<Integer, Snapshot> perView : snapshots.values()) {
            for (Snapshot snapshot : perView.values()) {
                GL11.glDeleteTextures(new int[]{snapshot.main, snapshot.alt});
                count++;
            }
        }
        snapshots.clear();
        logged = false;
        LOGGER.info("[color-history] reset snapshots={}", count);
    }

    /** 视角结束时释放快照纹理。 */
    public static void release(String view) {
        Map<Integer, Snapshot> perView = snapshots.remove(view);
        if (perView != null) {
            for (Snapshot snapshot : perView.values()) {
                GL11.glDeleteTextures(new int[]{snapshot.main, snapshot.alt});
            }
        }
    }

    private static Snapshot snapshot(String view, int target, int width, int height, int format) {
        Map<Integer, Snapshot> perView = snapshots.computeIfAbsent(view, key -> new HashMap<>());
        Snapshot snapshot = perView.get(target);
        if (snapshot != null
                && (snapshot.width != width || snapshot.height != height || snapshot.format != format)) {
            GL11.glDeleteTextures(new int[]{snapshot.main, snapshot.alt});
            perView.remove(target);
            snapshot = null;
        }
        if (snapshot == null) {
            snapshot = new Snapshot(width, height, format,
                    createTexture(width, height, format), createTexture(width, height, format));
            perView.put(target, snapshot);
        }
        return snapshot;
    }

    private static int createTexture(int width, int height, int format) {
        int texture = GL11.glGenTextures();
        GlStateManager._bindTexture(texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, format, width, height, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GlStateManager._bindTexture(0);
        return texture;
    }

    private static void copy(int source, int destination, int width, int height) {
        if (source <= 0 || destination <= 0 || source == destination) {
            return;
        }
        ensureFramebuffers();
        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, source, 0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, destination, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        }
    }

    private static void clear(int texture, int width, int height) {
        if (texture <= 0) {
            return;
        }
        ensureFramebuffers();
        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        float[] previousColor = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, previousColor);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, false);
        } finally {
            GlStateManager._clearColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        }
    }

    private static void ensureFramebuffers() {
        if (readFbo == 0) {
            readFbo = GL30.glGenFramebuffers();
        }
        if (drawFbo == 0) {
            drawFbo = GL30.glGenFramebuffers();
        }
    }

    private record Snapshot(int width, int height, int format, int main, int alt) {
    }
}
