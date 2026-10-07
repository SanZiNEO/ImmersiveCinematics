package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.camera.CameraState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * {@link LaneRenderer} 的调试驱动（冒烟入口）——把 N 个方位相机注册成 N 条 lane，
 * 让 {@link LaneRenderer} 真正跑起来。
 *
 * <p>模式（{@code -Dicinematics.quadrant=...} / 环境变量 {@code ICINEMATICS_QUADRANT=...}）：</p>
 * <ul>
 *   <li>{@code 0} / 不设 / {@code false} —— 关闭：与不装本模组零差异（每帧一次静态判断）；</li>
 *   <li>{@code 1} —— 单 lane（整尺寸离屏渲染 1 遍，铺满屏幕）；</li>
 *   <li>{@code true} —— 同 {@code 4}；</li>
 *   <li>{@code ≥4} —— 取最近的<b>平方数</b>作为 lane 数（4 / 9 / 16 / 25 / 36 / 49 / 64…，不设上限），
 *       n×n 网格排布，每条 lane 一个方位相机。</li>
 * </ul>
 *
 * <p>相机方位口径见 {@code docs/AI_SCRIPTING_GUIDE.md} §1.4（0=南(+z)、90=西(−x)、180=北(−z)、−90=东(+x)）：
 * 每条 lane 的相机从各自方位朝玩家推进（12→36→12 格往返）、始终看向玩家。内容开关用
 * {@link LaneRenderer.LaneContent#FULL}——与原型一致，便于肉眼核对实体 / 天空 / 天气都进了画面。</p>
 *
 * <p><b>上屏</b>：本驱动通过 {@link LaneRenderer#setSink} 注册合成回调，把每条 lane 的离屏画面按
 * n×n 网格铺到屏幕（走 {@link LaneCompositor}：dest = 网格格、source = 全幅、opacity = 1），
 * 让冒烟测试能肉眼验证渲染 + 合成链路。网格是调试期的固定合成参数；
 * 脚本 lane 的合成参数（opacity/dest/source 关键帧）由 {@link ScriptLaneDriver} 提供，
 * 且脚本 lane 存在时本驱动整体让位（见该类注释的「与调试驱动共存」）。
 */
public final class LaneDebugDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MODE = parseMode();
    /** lane 数：{@code 1} = 单 lane；{@code ≥4} 已归一化为最近的平方数；{@code 0} = 关闭。 */
    private static final int VIEWS = MODE;
    private static final int GRID = VIEWS >= 4 ? (int) Math.round(Math.sqrt(VIEWS)) : 1;
    private static final boolean ENABLED = VIEWS > 0;

    private static final long PERIOD_MS = 12_000L;          // 推进/后退往返周期
    private static final double D_NEAR = 12.0;              // 最近距离（格）
    private static final double D_FAR = 36.0;               // 最远距离（格）
    private static final double HEIGHT_ABOVE_EYE = 2.0;     // 相机高于玩家眼高
    private static final float DEFAULT_FOV = 70.0F;         // 模组相机默认 FOV

    /** 各 lane 的相机方位 yaw。 */
    private static final float[] YAWS = buildYaws();

    private static boolean started;

    private LaneDebugDriver() {
    }

    /**
     * 每帧调用一次（{@code GameRenderer.render} 的 HEAD，本帧世界渲染与 lane 渲染之前）。未开启时零差异。
     * <p>
     * 本驱动只在<b>没有脚本 lane</b>时被调用（共存规则见 {@link ScriptLaneDriver}）：脚本 lane 存在时
     * 调用方直接跳过本方法，两者不会同时写 lane。合成回调每帧重装——脚本播放期间上屏被
     * {@link ScriptLaneDriver} 接管，脚本结束后本驱动要能重新拿回上屏（否则 lane 渲染了却贴不上屏）。
     */
    public static void tick(Minecraft mc, float partialTick) {
        if (!ENABLED) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            LaneRenderer.INSTANCE.clear();
            return;
        }
        if (!started) {
            started = true;
            LOGGER.info("[lane] 调试驱动启用：mode={} lanes={} grid={}x{}", MODE, VIEWS, GRID, GRID);
        }
        LaneRenderer.INSTANCE.setSink(LaneDebugDriver::peek);
        update(mc.player, partialTick);
    }

    /** 更新所有 lane 的相机状态（位置 + yaw/pitch/roll/fov/zoom）。 */
    private static void update(Player player, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        double d = currentDistance();
        float pitch = (float) Math.toDegrees(Math.atan2(HEIGHT_ABOVE_EYE, d));   // 正=向下看
        for (int i = 0; i < VIEWS; i++) {
            float yaw = YAWS[i];
            // 相机方位 = 朝向的反方向（相机在东 → yaw 90 朝西看）；MC 朝向向量：yaw 0 → +z，yaw 90 → −x
            double rad = Math.toRadians(yaw);
            Vec3 pos = new Vec3(eye.x + Math.sin(rad) * d, eye.y + HEIGHT_ABOVE_EYE, eye.z - Math.cos(rad) * d);
            LaneRenderer.INSTANCE.setLane(i, new CameraState(pos, yaw, pitch, 0.0F, DEFAULT_FOV, 1.0F),
                    LaneRenderer.LaneContent.FULL, null);   // 调试 lane 无 lane 级调色
        }
    }

    /** 当前推进距离 d(t)：12 → 36 → 12 往返。 */
    private static double currentDistance() {
        double phase = (double) (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
        return D_NEAR + (D_FAR - D_NEAR) * (0.5 - 0.5 * Math.cos(phase * Math.PI * 2.0));
    }

    private static float[] buildYaws() {
        if (VIEWS <= 4) {
            return new float[]{90.0F, -90.0F, 180.0F, 0.0F};
        }
        // 16 画面起：每 360/N 度一个方位，覆盖各种方向
        float[] yaws = new float[VIEWS];
        for (int i = 0; i < VIEWS; i++) {
            yaws[i] = -180.0F + i * (360.0F / VIEWS);
        }
        return yaws;
    }

    private static int parseMode() {
        String value = System.getProperty("icinematics.quadrant");
        if (value == null || value.isEmpty()) {
            value = System.getenv("ICINEMATICS_QUADRANT");
        }
        if (value == null || value.isEmpty() || "false".equalsIgnoreCase(value)) {
            return 0;
        }
        if ("true".equalsIgnoreCase(value)) {
            return 4;
        }
        try {
            int mode = Integer.parseInt(value.trim());
            if (mode <= 0) {
                return 0;
            }
            if (mode == 1) {
                return 1;   // 单 lane
            }
            // 不设上限：取最近的平方数（4/9/16/25/36/49/64…）
            int n = Math.max(2, (int) Math.round(Math.sqrt(mode)));
            return n * n;
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    // ===== 上屏（走合成层）=====

    /**
     * 把该 lane 的整尺寸画面合成进网格单元：dest = 网格格、source = 全幅、opacity = 1。
     * 网格第 0 行在顶部；叠放顺序 = 调用顺序（lane 序号递增）。
     */
    private static void peek(int index, RenderTarget lane) {
        LaneCompositor.compose(lane, LaneCompositor.Rect.FULL, cell(index), 1.0F);
    }

    /** 第 {@code index} 格的屏幕归一化矩形（左上角原点；余数归最后一列 / 首行）。 */
    private static LaneCompositor.Rect cell(int index) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int cellW = main.width / GRID;
        int cellH = main.height / GRID;
        int col = index % GRID;
        int row = index / GRID;
        int x = col * cellW;
        int y = row * cellH;
        int w = (col == GRID - 1) ? (main.width - x) : cellW;
        int h = (row == 0) ? (main.height - (GRID - 1) * cellH) : cellH;
        return new LaneCompositor.Rect(x / (float) main.width, y / (float) main.height,
                w / (float) main.width, h / (float) main.height);
    }
}
