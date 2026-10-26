package com.immersivecinematics.immersive_cinematics.client.lane;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 视角作用域：主视角和每条 lane 各自进入 / 退出一次，统一驱动 previous/tracker、颜色历史、shadowcolor 的隔离。
 *
 * 挂点：lane 由 {@link LaneRenderer} 在渲染每条 lane 前 {@link #enter(String)}、合成完成后 {@link #exit(String)}；
 * main 用原版 {@code LevelRenderer.renderLevel} HEAD / RETURN——不依赖 Iris 的 beginLevelRendering /
 * finalizeLevelRendering，也不假设 main 与 lane 的先后；renderLevel 正是主视角真正的 level 边界
 * （它的 begin 实际发生在 lane 之后，不能提前到帧首）。{@link #onLevelHead()} / {@link #onLevelReturn()}
 * 在 lane pass 内不生效。
 * {@link #enter(String)} 与 {@link #exit(String)} 必须按同一 view 名配对：exit 只弹栈顶同名的一层，
 * 未配对调用无副作用。
 *
 * 光影 pipeline 切换时 {@link ShaderCompat#refresh()} 返回 true，栈被清空、旧视角作用域不再生效；
 * 光影模组不存在时适配层探测为空，这里只做 no-op。
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
        // lane 循环内不刷新 pipeline：刷新会清空视角栈，把本帧已 enter 的 lane 作用域打散（exit 配不上）。
        // pipeline 切换只可能在帧与帧之间发生，帧首第一次 enter 仍按原逻辑刷新。
        if (!LaneRenderer.isLaneLoopActive() && ShaderCompat.refresh()) {
            // pipeline 切换后旧视角作用域已失效；第一个新视角重新建立。
            STACK.clear();
        }
        ShadowColorClear.clearIfNeeded();
        if (!ViewStateCapture.hasView(view)) {
            // 新租约首帧没有自己的历史：回到 main 基线，避免继承上一条 lane 的 tracker / previous
            ViewStateCapture.restoreView("main");
        } else {
            ViewStateCapture.restoreView(view);
        }
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
