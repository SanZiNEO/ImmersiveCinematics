package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.*;
import com.immersivecinematics.immersive_cinematics.script.object.ClipRuntimeCache;
import com.immersivecinematics.immersive_cinematics.util.MathUtil;
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
 * clip 持续时按关键帧插值驱动层属性；{@link #onStop()} 清理本实例的层。
 * 每个播放实例各自持有自己的层（归属 token = 本实例的播放器），多实例互不覆盖。
 *
 * 合成参数取自统一字段表（{@code TrackSchemas.overlay()}）：位置 x/y（窗口归一化，元素中心，缺省 0.5）、
 * 锚点 anchor_x/anchor_y（元素自身归一化，缺省 0.5）、缩放 scale_x/scale_y（相对素材原始像素尺寸的倍数，
 * 缺省 1）、取材 source（素材归一化，缺省全幅）、不透明度 opacity（缺省 1.0）、顺序 z_index
 * （clip 级，缺省 10）。位置 / 锚点 / 缩放对 fade 不生效（效果层铺满窗口）；层创建时把编辑基准分辨率
 * （k 的分母口径）送入层，取值来源归 meta 字段。
 */
public class OverlayTrackPlayer implements TrackPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverlayTrackPlayer.class);

    // fade 高级颜色遮罩通道缺省
    private static final float DEFAULT_MASK_POSITION = 0.5f;   // mask_x / mask_y（窗口比例，元素中心）
    private static final float DEFAULT_MASK_SIZE = 1.0f;       // mask_width / mask_height（窗口比例）
    private static final float DEFAULT_MASK_RADIUS = 0.5f;     // mask_radius（短边比例）
    private static final float DEFAULT_GRADIENT_ANGLE = 0f;    // 0 = 向右，90 = 向下
    private static final float DEFAULT_GRADIENT_CENTER = 0.5f; // gradient_center_x / _y（窗口比例）
    private static final float DEFAULT_GRADIENT_RADIUS = 0.5f; // gradient_radius（短边比例）

    private final ScriptPlayer scriptPlayer;
    private final TrackType type;
    private final int trackIndex;
    private final OverlayManager overlayManager;
    private OverlayLayer currentLayer = null;
    private Clip activeClip = null;
    /** 当前 fade 片段的高级颜色遮罩定义（建层时取一次；clip 切换 / reset / stop 时清空，不逐帧新建） */
    private ColorMaskSpec activeMaskSpec = null;
    /** 新格式采样装配层：按片段对象身份缓存采样器与复用缓冲（旧格式片段不进入）。 */
    private final ClipRuntimeCache runtimeCache = new ClipRuntimeCache(TrackType.OVERLAY);
    /** 每片段 value-only 解析结果（按 clip 对象身份；与 {@link #runtimeCache} 同生命周期）。 */
    private final Map<Clip, OverlayClipRuntime> clipRuntimes = new IdentityHashMap<>();

    public OverlayTrackPlayer(ScriptPlayer scriptPlayer, TrackType type, OverlayManager overlayManager, int trackIndex) {
        this.scriptPlayer = scriptPlayer;
        this.type = type;
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
                LOGGER.info("OVERLAY switch: global={} old={} new={} layer={}",
                        String.format("%.2f", globalTime),
                        activeClip != null ? activeClip.getString("layer_type", "?") : "null",
                        clip != null ? clip.getString("layer_type", "?") : "null",
                        clip != null ? clip.getString("layer_type", "?") : "null");
            }
            cleanupCurrentLayer();
            activeClip = null;

            if (clip != null) {
                currentLayer = clip.hasObjects() ? createObjectLayer(clip) : createLayer(clip);
                if (currentLayer != null) {
                    overlayManager.addLayer(currentLayer);
                }
                activeClip = clip;
            }
        }

        if (clip == null || currentLayer == null) return;

        double localTime = scriptPlayer.executionLocalTime(clip, globalTime);

        if (clip.hasObjects()) {
            // 新格式：一个 SampledClip 提供全部参数，value-only 结构已按片段解析一次
            OverlayClipRuntime.OverlayParams params =
                    OverlayClipRuntime.compute(clip, localTime, runtimeCache, clipRuntime(clip));
            applyObjectParams(params);
            if (currentLayer instanceof ImageLayer imageLayer) {
                // 视频 / GIF 时间跟片段局部时间（旧格式仍用全局时间）
                imageLayer.setTime(localTime);
            }
            return;
        }

        List<Keyframe> kfs = clip.getKeyframes();

        // 透明度完全由关键帧 opacity 控制（fade_in / fade_out 由关键帧表达，代码层不叠加）；
        // 缺省 1.0 = 不透明
        float opacity = interpolateFloat(kfs, localTime, "opacity", CanvasTransform.DEFAULT_OPACITY);

        updateLayer(opacity, kfs, localTime);
        if (currentLayer instanceof ImageLayer) {
            ((ImageLayer) currentLayer).setTime(globalTime);
        }
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
        activeMaskSpec = null;
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

    private OverlayLayer createLayer(Clip clip) {
        String layerType = clip.getString("layer_type", "fade");
        // z_index 是 clip 级字段（单值通道），缺省 10
        int zIndex = clip.getInt("z_index", CanvasTransform.DEFAULT_Z_INDEX);
        // 编辑基准分辨率（k 的分母）取自脚本 meta；无脚本 / 未声明 → 缺省 1920×1080
        ScriptMeta meta = scriptPlayer.getScript() != null ? scriptPlayer.getScript().getMeta() : null;
        int baseWidth = meta != null ? meta.getBaseWidth() : ScriptMeta.DEFAULT_BASE_WIDTH;
        int baseHeight = meta != null ? meta.getBaseHeight() : ScriptMeta.DEFAULT_BASE_HEIGHT;

        OverlayLayer layer;
        switch (layerType) {
            case "fade" -> {
                FadeLayer fl = new FadeLayer(scriptPlayer.owner());
                fl.setColor(clip.getString("color", "#000000"));
                // 高级颜色遮罩定义在解析期已缓存于 clip；建层时取一次并送入层（GPU 资源生命周期归 FadeLayer）
                ColorMaskSpec spec = clip.getColorMaskSpec();
                activeMaskSpec = spec;
                fl.setMaskSpec(spec);
                fl.setZIndex(zIndex);
                layer = fl;
            }
            case "image" -> {
                ImageLayer il = new ImageLayer(scriptPlayer.owner());
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
                il.setBaseResolution(baseWidth, baseHeight);
                layer = il;
            }
            case "subtitle" -> {
                SubtitleLayer sl = new SubtitleLayer(scriptPlayer.owner());
                // 文本资源：@lang:<key> → 客户端当前语言译文（回退 en_us → 原样串），非引用串原样透传；
                // 分行归 SubtitleLayer 渲染时（此处只解析引用）。
                sl.setText(com.immersivecinematics.immersive_cinematics.util.LangResources
                        .resolve(clip.getString("text", "")));
                sl.setZIndex(zIndex);
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

    /**
     * 新格式建层：层类型 / 路径 / 文本 / z_index 取自 {@link OverlayClipRuntime}（value-only 解析一次），
     * 不使用新格式 clip 的空壳 {@code data}。
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
                activeMaskSpec = spec;
                fl.setMaskSpec(spec);
                fl.setZIndex(runtime.zIndex());
                layer = fl;
            }
            case "image" -> {
                ImageLayer il = new ImageLayer(scriptPlayer.owner());
                String path = runtime.path();
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
            // 无渐变时不读渐变几何（层保持初始化缺省，与旧链路一致）
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
        } else if (currentLayer instanceof SubtitleLayer sl) {
            sl.setOpacity(params.opacity());
            sl.setPosition(params.x(), params.y());
            sl.setAnchor(params.anchorX(), params.anchorY());
            sl.setFontScale(params.fontScale());
            sl.setScale(params.scaleX(), params.scaleY());
        }
    }

    private void updateLayer(float opacity, List<Keyframe> kfs, double localTime) {
        if (currentLayer instanceof FadeLayer fl) {
            // 效果层铺满画布：位置 / 锚点 / 缩放不生效；opacity 先按原默认作用于合成结果
            fl.setOpacity(opacity);
            // 高级颜色遮罩：只按 spec 能力采样被消费的通道，颜色由 spec 按 localTime 缓存采样
            // （缺省 full / 无渐变时跳过对应几何采样，层保持初始化缺省 = 旧 fade 行为）
            ColorMaskSpec spec = activeMaskSpec;
            if (spec != null) {
                // 只采样 spec 实际消费的通道（新层已初始化各通道缺省，未读通道保持缺省）：
                // full 不读 mask 几何；rect 读 x/y/宽/高；circle 读 x/y/半径。
                String maskShape = spec.maskShape();
                if ("rect".equals(maskShape)) {
                    fl.setMaskGeometry(
                            interpolateFloat(kfs, localTime, "mask_x", DEFAULT_MASK_POSITION),
                            interpolateFloat(kfs, localTime, "mask_y", DEFAULT_MASK_POSITION),
                            interpolateFloat(kfs, localTime, "mask_width", DEFAULT_MASK_SIZE),
                            interpolateFloat(kfs, localTime, "mask_height", DEFAULT_MASK_SIZE),
                            DEFAULT_MASK_RADIUS
                    );
                } else if ("circle".equals(maskShape)) {
                    fl.setMaskGeometry(
                            interpolateFloat(kfs, localTime, "mask_x", DEFAULT_MASK_POSITION),
                            interpolateFloat(kfs, localTime, "mask_y", DEFAULT_MASK_POSITION),
                            DEFAULT_MASK_SIZE, DEFAULT_MASK_SIZE,
                            interpolateFloat(kfs, localTime, "mask_radius", DEFAULT_MASK_RADIUS)
                    );
                }
                // 渐变几何只在声明了渐变时读取：linear 用方向角 + 中心；radial 用中心 + 半径。
                if (spec.hasGradient()) {
                    if ("radial".equals(spec.gradientType())) {
                        fl.setGradientGeometry(
                                DEFAULT_GRADIENT_ANGLE,
                                interpolateFloat(kfs, localTime, "gradient_center_x", DEFAULT_GRADIENT_CENTER),
                                interpolateFloat(kfs, localTime, "gradient_center_y", DEFAULT_GRADIENT_CENTER),
                                interpolateFloat(kfs, localTime, "gradient_radius", DEFAULT_GRADIENT_RADIUS)
                        );
                    } else {
                        fl.setGradientGeometry(
                                interpolateFloat(kfs, localTime, "gradient_angle", DEFAULT_GRADIENT_ANGLE),
                                interpolateFloat(kfs, localTime, "gradient_center_x", DEFAULT_GRADIENT_CENTER),
                                interpolateFloat(kfs, localTime, "gradient_center_y", DEFAULT_GRADIENT_CENTER),
                                DEFAULT_GRADIENT_RADIUS
                        );
                    }
                }
                fl.setColorRgb(spec.sampleColor(localTime));
            }
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
     * 关键帧浮点通道插值：匀速线性——运动与脚本里写的关键帧完全一致，没有隐式数学；
     * 缓动由编辑器烘焙为显式关键帧写入脚本，运行时不求值。
     */
    private float interpolateFloat(List<Keyframe> kfs, double localTime, String key, float defaultValue) {
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
     * 取材 {@code source = {x,y,w,h}} 的分量插值：按分量整体线性。
     * 关键帧没写 {@code source} 时按 {@code defaultValue}（缺省 = 全幅）处理。
     */
    private float interpolateSourceComponent(List<Keyframe> kfs, double localTime, String component,
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
    private static int segmentIndex(List<Keyframe> kfs, double localTime) {
        for (int i = 0; i < kfs.size() - 1; i++) {
            if (localTime >= kfs.get(i).getTime() && localTime <= kfs.get(i + 1).getTime()) {
                return i;
            }
        }
        return -1;
    }

    /** 区间 [i, i+1] 内的插值系数（钳制到 0~1；零长区间取 0） */
    private static float segmentT(List<Keyframe> kfs, int i, double localTime) {
        double span = (double) kfs.get(i + 1).getTime() - kfs.get(i).getTime();
        double t = (span > 0.001) ? (localTime - kfs.get(i).getTime()) / span : 0.0;
        return (float) Math.max(0.0, Math.min(1.0, t));
    }

    // ========== Helpers ==========

    private Clip findActiveClip(double globalTime) {
        for (Clip clip : clips()) {
            if (scriptPlayer.isClipActive(clip, globalTime)) return clip;
        }
        return null;
    }
}
