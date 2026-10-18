package com.immersivecinematics.immersive_cinematics.client.lane;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 视角作用域：主视角和每条 lane 各自进入/退出一次，统一驱动
 * previous/tracker、颜色历史、shadowcolor 的隔离。
 *
 * <p>挂点：</p>
 * <ul>
 *   <li>lane：{@link LaneRenderer} 在渲染每条 lane 前 {@link #enter(String)}，合成完成后
 *       {@link #exit(String)}；</li>
 *   <li>main：原版 {@code LevelRenderer.renderLevel} HEAD/RETURN。主视角的 begin 实际发生在
 *       lane 之后，所以不能在帧首进入；{@code renderLevel} 正好是主视角真正的 level 边界。</li>
 * </ul>
 *
 * <p>不依赖 Iris 的 beginLevelRendering / finalizeLevelRendering，也不假设 main 与 lane 的先后；
 * 光影模组不存在时适配层探测为空，这里只做 no-op。</p>
 *
 * <p>pipeline 切换时 {@link ShaderCompat#refresh()} 返回 true，栈会被清空；旧视角作用域不再生效。</p>
 */
public final class ViewScope {

    private static final Deque<String> STACK = new ArrayDeque<>();

    private ViewScope() {
    }

    public static void enter(String view) {
        if (view == null) {
            return;
        }
        if (!STACK.isEmpty() && STACK.peek().equals(view)) {
            return;
        }
        if (ShaderCompat.refresh()) {
            // pipeline 切换后旧视角作用域已失效；第一个新视角重新建立。
            STACK.clear();
        }
        ShadowColorClear.clearIfNeeded();
        ViewStateCapture.restoreView(view);
        ColorHistoryCapture.restore(view);
        STACK.push(view);
    }

    public static void exit(String view) {
        if (view == null || STACK.isEmpty() || !STACK.peek().equals(view)) {
            return;
        }
        ViewStateCapture.saveView(view);
        ColorHistoryCapture.save(view);
        STACK.pop();
    }

    /** 原版 LevelRenderer.renderLevel HEAD：lane 自己的 renderLevel 不进入 main。 */
    public static void onLevelHead() {
        if (!LaneRenderer.isRenderingLane()) {
            enter("main");
        }
    }

    /** 原版 LevelRenderer.renderLevel RETURN：主视角 composite 已结束。 */
    public static void onLevelReturn() {
        if (!LaneRenderer.isRenderingLane()) {
            exit("main");
        }
    }

    public static String current() {
        return STACK.peek();
    }
}
