package com.immersivecinematics.immersive_cinematics.client.lane;

/**
 * lane 上屏前的守门判定（纯判定：无 MC / 无 GL 依赖，便于离线复算，GL 冒烟与生产共用同一份）。
 *
 * {@link LaneCompositor#compose} 在画之前调本类：任何一条判定不过就整个跳过这次绘制，
 * 主画面保持已有内容（基画面 / 更下层 lane）= 失败直通，绝不用未写 / 不可采样的画面覆盖它。
 * 判定 → 跳过语义：
 * {@code !written} → 该画面本帧没被写满（渲染失败 / FBO 不完整 / 绘制报错），内容未定义。
 * {@code colorTextureId <= 0} → 缓冲没有颜色纹理：采样只会得到黑，且 GL 不报错。
 * {@code opacity <= 0} → 视为不可见（叠化到 0 是正常路径，静默跳过）。
 * 矩形分量非有限 / 退化 → NaN / ±Inf 顶点坐标 GL 画飞或不写；宽 / 高 ≤ 0 画不出东西。
 */
public final class LaneComposeGate {

    /** 判定结果：{@link #NONE} = 可以上屏；其余 = 跳过（{@link #reason()} 是告警文案）。 */
    public enum Skip {

        NONE(""),
        /** 该画面本帧未写 / 渲染失败（生产者已限频告警，见 {@code LaneRenderer.renderLane}）。 */
        UNWRITTEN("该 lane 本帧未写（渲染失败）"),
        /** 缓冲没有颜色纹理（纹理名为 0）：采样只会得到黑，且 GL 不报错。 */
        NO_TEXTURE("lane 缓冲没有颜色纹理（纹理名 = 0，采样只会得到黑）"),
        /** {@code opacity ≤ 0}（含 NaN）：视为不可见——正常语义，静默跳过，不告警。 */
        INVISIBLE("opacity ≤ 0（不可见）"),
        /** 矩形分量非有限（NaN / ±Inf）或退化（宽 / 高 ≤ 0）：GL 会画飞 / 不写，或画不出有意义的画面。 */
        BAD_RECT("矩形分量非有限（NaN / ±Inf）或退化（宽 / 高 ≤ 0）");

        private final String reason;

        Skip(String reason) {
            this.reason = reason;
        }

        /** 是否跳过这次绘制。 */
        public boolean skip() {
            return this != NONE;
        }

        /** 跳过文案（{@link #NONE} = 空串）。 */
        public String reason() {
            return reason;
        }
    }

    private LaneComposeGate() {
    }

    /**
     * 画面层判定：本帧是否写过、有没有颜色纹理、是否可见。
     * {@code written = false} = 渲染失败 / 未写，内容未定义；{@code colorTextureId <= 0} = 无颜色纹理。
     * {@code opacity <= 0} 与 NaN 都视为不可见（NaN 的任何比较都是 false）。
     */
    public static Skip checkBuffer(boolean written, int colorTextureId, float opacity) {
        if (!written) {
            return Skip.UNWRITTEN;
        }
        if (colorTextureId <= 0) {
            return Skip.NO_TEXTURE;
        }
        if (!(opacity > 0.0F)) {   // 含 NaN：NaN 的任何比较都是 false
            return Skip.INVISIBLE;
        }
        return Skip.NONE;
    }

    /**
     * 矩形层判定（{@code source} / {@code dest} 同口径，归一化或像素都适用）：分量必须有限、宽高必须为正。
     * NaN / ±Inf 顶点坐标在 GL 里是未定义行为（画飞 / 整片不写）；宽 / 高 ≤ 0 时目标矩形画不出东西，
     * 取材矩形则塌成一条线——两种都跳过，画面保持原样。
     */
    public static Skip checkRect(float x, float y, float w, float h) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(w) || !Float.isFinite(h)
                || !(w > 0.0F) || !(h > 0.0F)) {
            return Skip.BAD_RECT;
        }
        return Skip.NONE;
    }
}
