package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.*;
import com.immersivecinematics.immersive_cinematics.util.MathUtil;
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
    private final int trackIndex;
    private final OverlayManager overlayManager;
    private OverlayLayer currentLayer = null;
    private Clip activeClip = null;

    public OverlayTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, OverlayManager overlayManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
        this.trackIndex = trackIndex;
        this.overlayManager = overlayManager;
    }

    /** 组 A：动态数据源（replaceScript 后自动用新数据，零重建；按轨道索引定位，支持同类型多轨道） */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
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
            // 诊断：层切换（排查脚本结束后字幕残留）
            if (activeClip != null || clip != null) {
                LOGGER.info("OVERLAY switch: global={} old={} new={} layer={}",
                        String.format("%.2f", globalTime),
                        activeClip != null ? activeClip.getString("layer_type", "?") : "null",
                        clip != null ? clip.getString("layer_type", "?") : "null",
                        clip != null ? clip.getString("layer_type", "?") : "null");
            }
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

        // 透明度完全由关键帧 opacity 控制（fade_in/fade_out 由关键帧表达，代码层不叠加）
        float opacity = interpolateFloat(kfs, localTime, "opacity", 0f);

        updateLayer(opacity, kfs, localTime);
        if (currentLayer instanceof ImageLayer) {
            ((ImageLayer) currentLayer).setTime(globalTime);
        }
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
                        il.setTexture(path, tex);
                    } else {
                        // debug 级：资源缺失不影响脚本播放，作者排查时开调试日志可见
                        LOGGER.debug("Image not found in resource/: {}", path);
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
            il.setScale(first.getFloat("scale_x", 1f), first.getFloat("scale_y", 1f));
        } else if (currentLayer instanceof SubtitleLayer sl) {
            sl.setPosition(first.getFloat("x", 0f), first.getFloat("y", 0f));
            sl.setFontScale(first.getFloat("font_scale", 1f));
            sl.setScale(first.getFloat("scale_x", 1f), first.getFloat("scale_y", 1f));
        } else if (currentLayer instanceof PipLayer pl) {
            pl.setPosition(first.getFloat("x", 0f), first.getFloat("y", 0f));
            pl.setSize(first.getFloat("width", 0f), first.getFloat("height", 0f));
            pl.setAnchor(first.getFloat("anchor_x", 0.5f), first.getFloat("anchor_y", 0.5f));
        }
    }

    private void updateLayer(float opacity, List<Keyframe> kfs, float localTime) {
        if (currentLayer instanceof FadeLayer fl) {
            fl.setOpacity(opacity);
        } else if (currentLayer instanceof ImageLayer il) {
            il.setOpacity(opacity);
            // 屏幕百分比位置 + 原图百分比乘数（scale_x/scale_y，默认 1 = 原尺寸）
            il.setPosition(
                    interpolateFloat(kfs, localTime, "x", 0f),
                    interpolateFloat(kfs, localTime, "y", 0f)
            );
            il.setScale(
                    interpolateFloat(kfs, localTime, "scale_x", 1f),
                    interpolateFloat(kfs, localTime, "scale_y", 1f)
            );
        } else if (currentLayer instanceof SubtitleLayer sl) {
            sl.setOpacity(opacity);
            sl.setPosition(
                    interpolateFloat(kfs, localTime, "x", 0f),
                    interpolateFloat(kfs, localTime, "y", 0f)
            );
            // 两级缩放：font_scale（原版 title 同款矩阵缩放）+ scale_x/y（图片同款百分比缩放）
            sl.setFontScale(interpolateFloat(kfs, localTime, "font_scale", 1f));
            sl.setScale(
                    interpolateFloat(kfs, localTime, "scale_x", 1f),
                    interpolateFloat(kfs, localTime, "scale_y", 1f)
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

    // ========== 关键帧插值（匀速线性）==========

    /**
     * 关键帧浮点通道插值：匀速线性。
     * <p>
     * 0.3.6 起运行时统一线性（旧 clip 级 {@code interpolation} / Catmull-Rom {@code smooth} 已退役）：
     * 运动与脚本里写的关键帧完全一致，没有隐式数学；缓动由<b>编辑器烘焙</b>为显式关键帧写入脚本，
     * 运行时不求值（见 plans/0.3.6/script-model.md）。
     */
    private float interpolateFloat(List<Keyframe> kfs, float localTime, String key, float defaultValue) {
        if (kfs == null || kfs.isEmpty()) return defaultValue;
        if (kfs.size() < 2) return kfs.get(0).getFloat(key, defaultValue);

        Keyframe from = kfs.get(0);
        Keyframe to = kfs.get(kfs.size() - 1);
        boolean found = false;

        for (int i = 0; i < kfs.size() - 1; i++) {
            if (localTime >= kfs.get(i).getTime() && localTime <= kfs.get(i + 1).getTime()) {
                from = kfs.get(i);
                to = kfs.get(i + 1);
                found = true;
                break;
            }
        }

        if (!found) {
            // 范围外：返回边界关键帧的值
            return localTime < kfs.get(0).getTime()
                    ? kfs.get(0).getFloat(key, defaultValue)
                    : kfs.get(kfs.size() - 1).getFloat(key, defaultValue);
        }

        float t = (to.getTime() - from.getTime() > 0.001f)
                ? (localTime - from.getTime()) / (to.getTime() - from.getTime()) : 0f;
        t = Math.max(0f, Math.min(1f, t));

        return MathUtil.lerp(from.getFloat(key, defaultValue), to.getFloat(key, defaultValue), t);
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
