package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.*;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * OVERLAY 轨道播放器 — 驱动 fade/image/subtitle/pip 覆盖层
 * <p>
 * 生命周期：
 * <ol>
 *   <li>onRenderFrame 检测当前活跃的 clip</li>
 *   <li>Clip 切换时创建/移除对应的 OverlayLayer</li>
 *   <li>Clip 持续时通过关键帧插值驱动 layer 属性</li>
 *   <li>onStop 时清理所有层</li>
 * </ol>
 */
public class OverlayTrackPlayer implements TrackPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverlayTrackPlayer.class);

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final OverlayManager overlayManager;
    private OverlayLayer currentLayer = null;
    private Clip activeClip = null;

    public OverlayTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, OverlayManager overlayManager) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.overlayManager = overlayManager;
    }

    /** 组 A：动态数据源（replaceScript 后自动用新数据，零重建） */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(type);
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        Clip clip = findActiveClip(globalTime);

        // Transition: clip changed or no longer active
        if (clip != activeClip) {
            cleanupCurrentLayer();
            activeClip = null;

            if (clip != null) {
                currentLayer = createLayer(clip);
                if (currentLayer != null) {
                    overlayManager.addLayer(currentLayer);
                    applyInitialClipValues(clip);
                }
                activeClip = clip;
            }
        }

        if (clip == null || currentLayer == null) return;

        // Interpolate keyframe values
        float localTime = clipTime(clip, globalTime);
        List<Keyframe> kfs = clip.getKeyframes();

        float opacity = interpolateFloat(kfs, localTime, "opacity", 0f);
        float fadeFactor = computeFadeFactor(clip, localTime);

        // Apply opacity with fade
        updateLayer(clip, opacity * fadeFactor, kfs, localTime);
    }

    @Override
    public void onStop() {
        cleanupCurrentLayer();
    }

    /** 组 A：数据替换后 clip 对象身份变化，onRenderFrame 的 activeClip 比较会自动重建 layer；这里仅清理当前层 */
    @Override
    public void onScriptReplaced() {
        cleanupCurrentLayer();
    }

    // ========== Layer management ==========

    private void cleanupCurrentLayer() {
        if (currentLayer != null) {
            overlayManager.removeLayer(currentLayer);
            currentLayer = null;
        }
        activeClip = null;
    }

    private OverlayLayer createLayer(Clip clip) {
        String layerType = clip.getString("layer_type", "fade");
        int zIndex = clip.getInt("z_index", 10);

        OverlayLayer layer;
        switch (layerType) {
            case "fade" -> {
                FadeLayer fl = new FadeLayer();
                fl.setColor(clip.getString("color", "#000000"));
                fl.setZIndex(zIndex);
                layer = fl;
            }
            case "image" -> {
                ImageLayer il = new ImageLayer();
                String path = clip.getString("path", "");
                if (!path.isEmpty()) {
                    ResourceLocation tex = com.immersivecinematics.immersive_cinematics.util.TextureLoader.loadTexture(path);
                    if (tex != null) {
                        il.setTexture(tex);
                    } else {
                        LOGGER.warn("Image not found in resource/: {}", path);
                    }
                }
                il.setZIndex(zIndex);
                layer = il;
            }
            case "subtitle" -> {
                SubtitleLayer sl = new SubtitleLayer();
                sl.setText(clip.getString("text", ""));
                sl.setZIndex(zIndex);
                layer = sl;
            }
            case "pip" -> {
                PipLayer pl = new PipLayer();
                pl.setZIndex(zIndex);
                layer = pl;
            }
            default -> {
                LOGGER.warn("未知 OVERLAY layer_type: {}", layerType);
                return null;
            }
        }
        return layer;
    }

    private void applyInitialClipValues(Clip clip) {
        // On first activation, set initial position/size from first keyframe if available
        List<Keyframe> kfs = clip.getKeyframes();
        if (kfs == null || kfs.isEmpty()) return;

        Keyframe first = kfs.get(0);
        if (currentLayer instanceof ImageLayer il) {
            il.setPosition(first.getFloat("x", 0f), first.getFloat("y", 0f));
            il.setSize(first.getFloat("width", 0f), first.getFloat("height", 0f));
            il.setAnchor(first.getFloat("anchor_x", 0.5f), first.getFloat("anchor_y", 0.5f));
        } else if (currentLayer instanceof SubtitleLayer sl) {
            sl.setPosition(first.getFloat("x", 0f), first.getFloat("y", 0f));
            sl.setAnchor(first.getFloat("anchor_x", 0.5f), first.getFloat("anchor_y", 0.5f));
        } else if (currentLayer instanceof PipLayer pl) {
            pl.setPosition(first.getFloat("x", 0f), first.getFloat("y", 0f));
            pl.setSize(first.getFloat("width", 0f), first.getFloat("height", 0f));
            pl.setAnchor(first.getFloat("anchor_x", 0.5f), first.getFloat("anchor_y", 0.5f));
        }
    }

    private void updateLayer(Clip clip, float opacity, List<Keyframe> kfs, float localTime) {
        if (currentLayer instanceof FadeLayer fl) {
            fl.setOpacity(opacity);
        } else if (currentLayer instanceof ImageLayer il) {
            il.setOpacity(opacity);
            il.setPosition(
                    interpolateFloat(kfs, localTime, "x", 0f),
                    interpolateFloat(kfs, localTime, "y", 0f)
            );
            il.setSize(
                    interpolateFloat(kfs, localTime, "width", 0f),
                    interpolateFloat(kfs, localTime, "height", 0f)
            );
            il.setAnchor(
                    interpolateFloat(kfs, localTime, "anchor_x", 0.5f),
                    interpolateFloat(kfs, localTime, "anchor_y", 0.5f)
            );
        } else if (currentLayer instanceof SubtitleLayer sl) {
            sl.setOpacity(opacity);
            sl.setPosition(
                    interpolateFloat(kfs, localTime, "x", 0f),
                    interpolateFloat(kfs, localTime, "y", 0f)
            );
            sl.setAnchor(
                    interpolateFloat(kfs, localTime, "anchor_x", 0.5f),
                    interpolateFloat(kfs, localTime, "anchor_y", 0.5f)
            );
        } else if (currentLayer instanceof PipLayer pl) {
            pl.setOpacity(opacity);
            pl.setPosition(
                    interpolateFloat(kfs, localTime, "x", 0f),
                    interpolateFloat(kfs, localTime, "y", 0f)
            );
            pl.setSize(
                    interpolateFloat(kfs, localTime, "width", 0f),
                    interpolateFloat(kfs, localTime, "height", 0f)
            );
            pl.setAnchor(
                    interpolateFloat(kfs, localTime, "anchor_x", 0.5f),
                    interpolateFloat(kfs, localTime, "anchor_y", 0.5f)
            );
        }
    }

    // ========== Interpolation (same pattern as LetterboxTrackPlayer) ==========

    /**
     * 线性插值两个关键帧之间的 float 字段值
     */
    private float interpolateFloat(List<Keyframe> kfs, float localTime, String key, float defaultValue) {
        if (kfs == null || kfs.isEmpty()) return defaultValue;
        if (kfs.size() < 2) return kfs.get(0).getFloat(key, defaultValue);

        Keyframe from = kfs.get(0);
        Keyframe to = kfs.get(kfs.size() - 1);

        for (int i = 0; i < kfs.size() - 1; i++) {
            if (localTime >= kfs.get(i).getTime() && localTime <= kfs.get(i + 1).getTime()) {
                from = kfs.get(i);
                to = kfs.get(i + 1);
                break;
            }
        }

        float t = (to.getTime() - from.getTime() > 0.001f)
                ? (localTime - from.getTime()) / (to.getTime() - from.getTime()) : 0f;
        t = Math.max(0f, Math.min(1f, t));

        float vFrom = from.getFloat(key, defaultValue);
        float vTo = to.getFloat(key, defaultValue);
        return vFrom + (vTo - vFrom) * t;
    }

    // ========== Fade ==========

    /**
     * 计算 fade_in / fade_out 因子
     * <p>
     * 在淡入阶段：0 → 1，淡出阶段：1 → 0，中间阶段：1
     */
    private float computeFadeFactor(Clip clip, float localTime) {
        float fadeIn = clip.getFadeIn();
        float fadeOut = clip.getFadeOut();
        float duration = clip.getDuration();

        if (fadeIn > 0f && localTime < fadeIn) {
            return localTime / fadeIn;
        }

        if (fadeOut > 0f && duration > 0f) {
            float remaining = duration - localTime;
            if (remaining < fadeOut && remaining > 0f) {
                return remaining / fadeOut;
            }
        }

        return 1f;
    }

    // ========== Helpers ==========

    private float clipTime(Clip clip, float globalTime) {
        return Math.max(0f, Math.min(clip.getDuration(), globalTime - clip.getStartTime()));
    }

    private Clip findActiveClip(float globalTime) {
        for (Clip clip : clips()) {
            boolean isActive;
            if (clip.getDuration() < 0f) {
                isActive = globalTime >= clip.getStartTime();
            } else {
                float clipEnd = clip.getStartTime() + clip.getDuration();
                isActive = globalTime >= clip.getStartTime() && globalTime < clipEnd;
            }
            if (isActive) return clip;
        }
        return null;
    }
}
