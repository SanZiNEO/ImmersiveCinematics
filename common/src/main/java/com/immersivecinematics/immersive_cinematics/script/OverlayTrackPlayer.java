package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.*;
import com.immersivecinematics.immersive_cinematics.util.MathUtil;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * OVERLAY 轨道播放器 — 驱动 fade/image/subtitle 覆盖层
 * <p>
 * 生命周期：
 * <ol>
 *   <li>onRenderFrame 检测当前活跃的 clip</li>
 *   <li>Clip 切换时创建/移除对应的 OverlayLayer</li>
 *   <li>Clip 持续时通过关键帧插值驱动 layer 属性</li>
 *   <li>onStop 时清理所有层</li>
 * </ol>
 * 字段口径 = 统一参数字段表（{@code plans/0.3.6/variable-frame.md} §4.2）：
 * 位置 x/y（<b>窗口</b>归一化，元素中心，缺省 0.5）、锚点 anchor_x/anchor_y（元素自身归一化，缺省 0.5）、
 * 缩放 scale_x/scale_y（相对<b>素材原始像素尺寸</b>的倍数，缺省 1）、取材 source（素材归一化，缺省全幅）、
 * 不透明度 opacity（缺省 1.0）、顺序 z_index（clip 级，缺省 10）。
 * 位置 / 锚点 / 缩放对 fade 不生效（效果层铺满窗口）。
 * 层创建时把编辑基准分辨率（W基/H基，k 的分母口径）送入层；取值来源归 meta 字段。
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
                }
                activeClip = clip;
            }
        }

        if (clip == null || currentLayer == null) return;

        // Interpolate keyframe values
        float localTime = clipTime(clip, globalTime);
        List<Keyframe> kfs = clip.getKeyframes();

        // 透明度完全由关键帧 opacity 控制（fade_in/fade_out 由关键帧表达，代码层不叠加）；
        // 缺省 1.0 = 不透明（§3.1 字段表）
        float opacity = interpolateFloat(kfs, localTime, "opacity", CanvasTransform.DEFAULT_OPACITY);

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
        // z_index 是 clip 级字段（= 只有一个值的步进通道，§3.1 字段表）；默认统一 10
        int zIndex = clip.getInt("z_index", CanvasTransform.DEFAULT_Z_INDEX);
        // 编辑基准分辨率（k 的分母）取自脚本 meta；无脚本 / 未声明 → 缺省 1920×1080
        ScriptMeta meta = scriptPlayer.getScript() != null ? scriptPlayer.getScript().getMeta() : null;
        int baseWidth = meta != null ? meta.getBaseWidth() : ScriptMeta.DEFAULT_BASE_WIDTH;
        int baseHeight = meta != null ? meta.getBaseHeight() : ScriptMeta.DEFAULT_BASE_HEIGHT;

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
                // 编辑基准分辨率送层（k 的分母）
                il.setBaseResolution(baseWidth, baseHeight);
                layer = il;
            }
            case "subtitle" -> {
                SubtitleLayer sl = new SubtitleLayer();
                // 文本资源：@lang:<key> → 客户端当前语言译文（回退 en_us → 原样串，§3.2）；
                // 非引用串原样透传。解析在分行之前（SubtitleLayer 渲染时才 split("\n")，§3.5）。
                sl.setText(com.immersivecinematics.immersive_cinematics.util.LangResources
                        .resolve(clip.getString("text", "")));
                sl.setZIndex(zIndex);
                // 编辑基准分辨率送层（k 的分母）
                sl.setBaseResolution(baseWidth, baseHeight);
                layer = sl;
            }
            default -> {
                LOGGER.warn("未知 OVERLAY layer_type: {}", layerType);
                return null;
            }
        }
        return layer;
    }

    private void updateLayer(float opacity, List<Keyframe> kfs, float localTime) {
        if (currentLayer instanceof FadeLayer fl) {
            // 效果层铺满画布：位置 / 锚点 / 缩放不生效，只有 opacity
            fl.setOpacity(opacity);
        } else if (currentLayer instanceof ImageLayer il) {
            il.setOpacity(opacity);
            il.setPosition(
                    interpolateFloat(kfs, localTime, "x", CanvasTransform.DEFAULT_POSITION),
                    interpolateFloat(kfs, localTime, "y", CanvasTransform.DEFAULT_POSITION)
            );
            il.setAnchor(
                    interpolateFloat(kfs, localTime, "anchor_x", CanvasTransform.DEFAULT_ANCHOR),
                    interpolateFloat(kfs, localTime, "anchor_y", CanvasTransform.DEFAULT_ANCHOR)
            );
            il.setScale(
                    interpolateFloat(kfs, localTime, "scale_x", CanvasTransform.DEFAULT_SCALE),
                    interpolateFloat(kfs, localTime, "scale_y", CanvasTransform.DEFAULT_SCALE)
            );
            // 取材：source = {x,y,w,h}，按分量整体线性（缺省全幅 {0,0,1,1}）
            il.setSource(
                    interpolateSourceComponent(kfs, localTime, "x", 0f),
                    interpolateSourceComponent(kfs, localTime, "y", 0f),
                    interpolateSourceComponent(kfs, localTime, "w", 1f),
                    interpolateSourceComponent(kfs, localTime, "h", 1f)
            );
        } else if (currentLayer instanceof SubtitleLayer sl) {
            sl.setOpacity(opacity);
            sl.setPosition(
                    interpolateFloat(kfs, localTime, "x", CanvasTransform.DEFAULT_POSITION),
                    interpolateFloat(kfs, localTime, "y", CanvasTransform.DEFAULT_POSITION)
            );
            sl.setAnchor(
                    interpolateFloat(kfs, localTime, "anchor_x", CanvasTransform.DEFAULT_ANCHOR),
                    interpolateFloat(kfs, localTime, "anchor_y", CanvasTransform.DEFAULT_ANCHOR)
            );
            // 两级缩放：font_scale（原版 title 同款矩阵缩放，改变文字块基准尺寸）+ scale_x/y（相对素材像素的倍数）
            sl.setFontScale(interpolateFloat(kfs, localTime, "font_scale", 1f));
            sl.setScale(
                    interpolateFloat(kfs, localTime, "scale_x", CanvasTransform.DEFAULT_SCALE),
                    interpolateFloat(kfs, localTime, "scale_y", CanvasTransform.DEFAULT_SCALE)
            );
        }
    }

    // ========== 关键帧插值（匀速线性 / 步进）==========

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

        int i = segmentIndex(kfs, localTime);
        if (i < 0) {
            // 范围外：返回边界关键帧的值
            return localTime < kfs.get(0).getTime()
                    ? kfs.get(0).getFloat(key, defaultValue)
                    : kfs.get(kfs.size() - 1).getFloat(key, defaultValue);
        }
        return MathUtil.lerp(kfs.get(i).getFloat(key, defaultValue),
                kfs.get(i + 1).getFloat(key, defaultValue), segmentT(kfs, i, localTime));
    }

    /**
     * 取材 {@code source = {x,y,w,h}} 的分量插值：按分量整体线性（§3.1 字段表）。
     * 关键帧没写 {@code source} 时按 {@code defaultValue}（缺省 = 全幅）处理。
     */
    private float interpolateSourceComponent(List<Keyframe> kfs, float localTime, String component,
                                             float defaultValue) {
        if (kfs == null || kfs.isEmpty()) return defaultValue;
        if (kfs.size() < 2) return sourceComponent(kfs.get(0), component, defaultValue);

        int i = segmentIndex(kfs, localTime);
        if (i < 0) {
            return localTime < kfs.get(0).getTime()
                    ? sourceComponent(kfs.get(0), component, defaultValue)
                    : sourceComponent(kfs.get(kfs.size() - 1), component, defaultValue);
        }
        return MathUtil.lerp(sourceComponent(kfs.get(i), component, defaultValue),
                sourceComponent(kfs.get(i + 1), component, defaultValue), segmentT(kfs, i, localTime));
    }

    /** 读一个关键帧的 {@code source} 分量；缺字段 → {@code defaultValue} */
    private static float sourceComponent(Keyframe kf, String component, float defaultValue) {
        if (kf.getData().get("source") instanceof Map<?, ?> source) {
            Object value = source.get(component);
            if (value instanceof Number number) return number.floatValue();
        }
        return defaultValue;
    }

    /** 定位 localTime 落在哪个关键帧区间：返回左端点索引；范围外返回 -1 */
    private static int segmentIndex(List<Keyframe> kfs, float localTime) {
        for (int i = 0; i < kfs.size() - 1; i++) {
            if (localTime >= kfs.get(i).getTime() && localTime <= kfs.get(i + 1).getTime()) {
                return i;
            }
        }
        return -1;
    }

    /** 区间 [i, i+1] 内的插值系数（钳制到 0~1；零长区间取 0） */
    private static float segmentT(List<Keyframe> kfs, int i, float localTime) {
        float span = kfs.get(i + 1).getTime() - kfs.get(i).getTime();
        float t = (span > 0.001f) ? (localTime - kfs.get(i).getTime()) / span : 0f;
        return Math.max(0f, Math.min(1f, t));
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
