package com.immersivecinematics.immersive_cinematics.script;

/**
 * 一条 CAMERA lane 的合成参数快照：不透明度 / 目标矩形 / 素材矩形 / 遮罩几何与形状。
 *
 * <p>新格式由 {@code ObjectCameraEvaluator} 从同一 {@code SampledClip} 一次算出（与相机六参数同一
 * {@code executionLocalTime}），随 {@link CameraLane#composite()} 交给渲染侧；旧格式片段该字段为
 * {@code null}，仍由 {@code ScriptLaneDriver} 按旧关键帧插值 + {@code LaneMask} 采样，像素不变。</p>
 *
 * <p>字段口径与旧链路一致：矩形为窗口 / 素材归一化比例（{@code x}/{@code y} 左上角，{@code w}/{@code h}
 * 宽高），{@code maskShape} 取 {@code full} / {@code rect} / {@code circle}（与 {@code LaneMask.SHAPE_*}
 * 一一对应），遮罩中心 / 宽高为窗口比例、半径为窗口短边比例。{@link #DEFAULT} = 全屏、不透明、full mask。</p>
 *
 * <p>不可变；构造后只读，可跨帧复用（同一片段同一 {@code t} 的取值确定）。</p>
 */
public record CameraLaneComposite(
        float opacity,
        float destX, float destY, float destW, float destH,
        float sourceX, float sourceY, float sourceW, float sourceH,
        String maskShape,
        float maskX, float maskY, float maskWidth, float maskHeight, float maskRadius) {

    /** 旧链路缺省：全屏 dest / source、不透明、full mask（几何取 {@code LaneMask} 缺省）。 */
    public static final CameraLaneComposite DEFAULT = new CameraLaneComposite(
            1.0F,
            0.0F, 0.0F, 1.0F, 1.0F,
            0.0F, 0.0F, 1.0F, 1.0F,
            "full",
            0.5F, 0.5F, 1.0F, 1.0F, 0.5F);
}
