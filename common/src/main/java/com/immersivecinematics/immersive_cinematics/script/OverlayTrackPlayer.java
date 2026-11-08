package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.media.video.VideoFiles;
import com.immersivecinematics.immersive_cinematics.media.video.VideoLayer;
import com.immersivecinematics.immersive_cinematics.overlay.*;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.track.TrackDriver;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * OVERLAY 轨道播放器 — 驱动 fade / image / subtitle 覆盖层。
 *
 * 每渲染帧检测活跃 clip：clip 切换时创建并挂上对应的 OverlayLayer（旧层移除），
 * clip 持续时按采样参数驱动层属性；{@link #onStop()} 清理本实例的层。
 * 每个播放实例各自持有自己的层（归属 token = 本实例的播放器），多实例互不覆盖。
 *
 * 合成参数取自统一字段表（{@code TrackSchemas.overlay()}）：位置 x/y（窗口归一化，元素中心，缺省 0.5）、
 * 锚点 anchor_x/anchor_y（元素自身归一化，缺省 0.5）、缩放 scale_x/scale_y（相对素材原始像素尺寸的倍数，
 * 缺省 1）、取材 source（素材归一化，缺省全幅）、不透明度 opacity（缺省 1.0）、顺序 z_index
 * （clip 级，缺省 10）。位置 / 锚点 / 缩放对 fade 不生效（效果层铺满窗口）；层创建时把编辑基准分辨率
 * （k 的分母口径）送入层，取值来源归 meta 字段。
 */
public class OverlayTrackPlayer implements TrackDriver {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverlayTrackPlayer.class);

    private final ScriptPlayer scriptPlayer;
    private final int trackIndex;
    private final OverlayManager overlayManager;
    private OverlayLayer currentLayer = null;
    private Clip activeClip = null;
    /** 采样装配层：按片段对象身份缓存采样器与复用缓冲。 */
    private final ClipRuntimeCache runtimeCache = new ClipRuntimeCache(TrackTypeId.OVERLAY);
    /** 每片段 value-only 解析结果（按 clip 对象身份；与 {@link #runtimeCache} 同生命周期）。 */
    private final Map<Clip, OverlayClipRuntime> clipRuntimes = new IdentityHashMap<>();

    public OverlayTrackPlayer(ScriptPlayer scriptPlayer, OverlayManager overlayManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.trackIndex = trackIndex;
        this.overlayManager = overlayManager;
    }

    /** 动态数据源：replaceScript 后自动用新数据，零重建；按轨道索引定位，支持同类型多轨道。 */
    private List<Clip> clips() {
        return scriptPlayer.clipsForTrack(trackIndex);
    }

    @Override
    public boolean isActiveAt(double globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(double globalTime) {
        Clip clip = findActiveClip(globalTime);

        if (clip != activeClip) {
            // 诊断：层切换（排查脚本结束后字幕残留）
            if (activeClip != null || clip != null) {
                String oldLayer = activeClip != null
                        ? new ClipValueView(activeClip).s("layer_type", "?") : "null";
                String newLayer = clip != null ? new ClipValueView(clip).s("layer_type", "?") : "null";
                LOGGER.info("OVERLAY switch: global={} old={} new={} layer={}",
                        String.format("%.2f", globalTime), oldLayer, newLayer, newLayer);
            }
            cleanupCurrentLayer();
            activeClip = null;

            if (clip != null) {
                currentLayer = createObjectLayer(clip);
                if (currentLayer != null) {
                    overlayManager.addLayer(currentLayer);
                }
                activeClip = clip;
            }
        }

        if (clip == null || currentLayer == null) return;

        double localTime = scriptPlayer.executionLocalTime(clip, globalTime);
        // 一个 SampledClip 提供全部参数，value-only 结构已按片段解析一次
        OverlayClipRuntime.OverlayParams params =
                OverlayClipRuntime.compute(clip, localTime, runtimeCache, clipRuntime(clip));
        applyObjectParams(params);
        // 时间跟片段局部时间：GIF / 视频层重写，其余层 default 空操作
        currentLayer.setTime(localTime);
    }

    @Override
    public void onStop() {
        cleanupCurrentLayer();
        clearRuntimeCache();
    }

    /** 数据替换后 clip 对象身份变化，onRenderFrame 的 activeClip 比较会自动重建 layer；这里仅清理当前层。 */
    @Override
    public void onScriptReplaced() {
        cleanupCurrentLayer();
        clearRuntimeCache();
    }

    // ========== Layer management ==========

    private void cleanupCurrentLayer() {
        if (currentLayer != null) {
            overlayManager.removeLayer(currentLayer);
            currentLayer = null;
        }
        activeClip = null;
    }

    /** 脚本替换 / 停播：丢弃全部片段的采样与解析缓存（下次访问按新片段重建）。 */
    private void clearRuntimeCache() {
        runtimeCache.clear();
        clipRuntimes.clear();
    }

    /** 该片段的 value-only 解析结果；按对象身份缓存，每片段只解析一次。 */
    private OverlayClipRuntime clipRuntime(Clip clip) {
        OverlayClipRuntime runtime = clipRuntimes.get(clip);
        if (runtime == null) {
            runtime = OverlayClipRuntime.of(clip, runtimeCache.values(clip));
            clipRuntimes.put(clip, runtime);
        }
        return runtime;
    }

    /**
     * 建层：层类型 / 路径 / 文本 / z_index 取自 {@link OverlayClipRuntime}（value-only 解析一次），
     * 不读片段 {@code data}。
     */
    private OverlayLayer createObjectLayer(Clip clip) {
        OverlayClipRuntime runtime = clipRuntime(clip);
        // 编辑基准分辨率（k 的分母）取自脚本 meta；无脚本 / 未声明 → 缺省 1920×1080
        ScriptMeta meta = scriptPlayer.getScript() != null ? scriptPlayer.getScript().getMeta() : null;
        int baseWidth = meta != null ? meta.getBaseWidth() : ScriptMeta.DEFAULT_BASE_WIDTH;
        int baseHeight = meta != null ? meta.getBaseHeight() : ScriptMeta.DEFAULT_BASE_HEIGHT;

        OverlayLayer layer;
        switch (runtime.layerType()) {
            case "fade" -> {
                FadeLayer fl = new FadeLayer(scriptPlayer.owner());
                // 颜色逐帧由采样值合成（setColorRgb）；这里只送入 value-only 结构
                ColorMaskSpec spec = runtime.maskSpec();
                fl.setMaskSpec(spec);
                fl.setZIndex(runtime.zIndex());
                layer = fl;
            }
            case "image" -> {
                String path = runtime.path();
                if (VideoFiles.isVideo(path)) {
                    // 视频容器：几何 / 取材 / 时间轴与图片层同一套，解码与音轨由视频层自管
                    VideoLayer vl = new VideoLayer(scriptPlayer.owner(), path);
                    vl.setZIndex(runtime.zIndex());
                    vl.setBaseResolution(baseWidth, baseHeight);
                    layer = vl;
                } else {
                    ImageLayer il = new ImageLayer(scriptPlayer.owner());
                    if (!path.isEmpty()) {
                        ResourceLocation tex = com.immersivecinematics.immersive_cinematics.util.TextureLoader.loadTexture(path);
                        if (tex != null) {
                            il.setTexture(path, tex);
                        } else {
                            LOGGER.debug("Image not found in resource/: {}", path);
                        }
                    }
                    il.setZIndex(runtime.zIndex());
                    il.setBaseResolution(baseWidth, baseHeight);
                    layer = il;
                }
            }
            case "subtitle" -> {
                SubtitleLayer sl = new SubtitleLayer(scriptPlayer.owner());
                sl.setText(com.immersivecinematics.immersive_cinematics.util.LangResources.resolve(runtime.text()));
                sl.setZIndex(runtime.zIndex());
                sl.setBaseResolution(baseWidth, baseHeight);
                layer = sl;
            }
            default -> {
                LOGGER.warn("未知 OVERLAY layer_type: {}", runtime.layerType());
                return null;
            }
        }
        return layer;
    }

    /** 把一帧装配结果写入当前层（只调 setter，不改层内数学）。 */
    private void applyObjectParams(OverlayClipRuntime.OverlayParams params) {
        if (currentLayer instanceof FadeLayer fl) {
            fl.setOpacity(params.opacity());
            fl.setColorRgb(params.colorRgb());
            // full 不读遮罩几何（层保持初始化缺省）；rect / circle 由装配层按形状填参数
            if (!ColorMaskSpec.DEFAULT_MASK_SHAPE.equals(params.maskShape())) {
                fl.setMaskGeometry(params.maskX(), params.maskY(),
                        params.maskWidth(), params.maskHeight(), params.maskRadius());
            }
            // 无渐变时不读渐变几何（层保持初始化缺省）
            if (params.hasGradient()) {
                fl.setGradientGeometry(params.gradientAngle(), params.gradientCenterX(),
                        params.gradientCenterY(), params.gradientRadius());
            }
        } else if (currentLayer instanceof ImageLayer il) {
            il.setOpacity(params.opacity());
            il.setPosition(params.x(), params.y());
            il.setAnchor(params.anchorX(), params.anchorY());
            il.setScale(params.scaleX(), params.scaleY());
            il.setSource(params.sourceX(), params.sourceY(), params.sourceW(), params.sourceH());
        } else if (currentLayer instanceof VideoLayer vl) {
            // 与图片层同一套装配参数（视频层不是 ImageLayer 子类，单独走一条）
            vl.setOpacity(params.opacity());
            vl.setPosition(params.x(), params.y());
            vl.setAnchor(params.anchorX(), params.anchorY());
            vl.setScale(params.scaleX(), params.scaleY());
            vl.setSource(params.sourceX(), params.sourceY(), params.sourceW(), params.sourceH());
        } else if (currentLayer instanceof SubtitleLayer sl) {
            sl.setOpacity(params.opacity());
            sl.setPosition(params.x(), params.y());
            sl.setAnchor(params.anchorX(), params.anchorY());
            sl.setFontScale(params.fontScale());
            sl.setScale(params.scaleX(), params.scaleY());
        }
    }

    // ========== Helpers ==========

    private Clip findActiveClip(double globalTime) {
        for (Clip clip : clips()) {
            if (scriptPlayer.isClipActive(clip, globalTime)) return clip;
        }
        return null;
    }
}
