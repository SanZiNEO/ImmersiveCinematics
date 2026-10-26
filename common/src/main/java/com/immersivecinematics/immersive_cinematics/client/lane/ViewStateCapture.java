package com.immersivecinematics.immersive_cinematics.client.lane;

import com.immersivecinematics.immersive_cinematics.Config;
import com.mojang.logging.LogUtils;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 每视角 previous 矩阵与 CameraPositionTracker 的保存 / 恢复。
 *
 * 实例由两个只读构造器 mixin 注册：{@code CameraUniforms.CameraPositionTracker.<init>} 与
 * {@code MatrixUniforms.Previous.<init>}。mixin 只记录实例、不改光影模组行为，字段读写只发生在
 * {@link ViewScope} 的视角边界上。
 *
 * pipeline 切换时 {@link #onPipelineChanged()} 丢掉旧 pipeline 的实例与保存状态（旧 previous/tracker 不能套到
 * 新 pipeline 上）。释放由 {@link CameraLeasePool} 在租约归还时确定性触发（{@link #releaseView}）：
 * 片段结束即清，不再按「连续 2 帧不活跃」启发式回收；main 永不释放。
 */
public final class ViewStateCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Object tracker;
    private static final List<Object> previousInstances = new ArrayList<>();
    private static final Map<String, SavedView> saved = new HashMap<>();
    /** 上一次 pipeline 变化时已有的 Previous 实例数；切换后新实例追加在它后面。 */
    private static int lastPipelineInstanceCount;

    private ViewStateCapture() {
    }

    public static void registerTracker(Object instance) {
        if (instance != null) {
            tracker = instance;
        }
    }

    public static void registerPrevious(Object instance) {
        if (instance == null) {
            return;
        }
        for (Object existing : previousInstances) {
            if (existing == instance) {
                return;
            }
        }
        previousInstances.add(instance);
    }

    /** 首次拿到 pipeline：记录当前实例数，清掉可能残留的旧状态。 */
    public static void onPipelineReady() {
        lastPipelineInstanceCount = previousInstances.size();
        saved.clear();
    }

    /** pipeline 切换：丢掉旧 pipeline 注册的 Previous 实例，保留新实例；清空每视角保存状态。 */
    public static void onPipelineChanged() {
        int old = Math.min(lastPipelineInstanceCount, previousInstances.size());
        if (old > 0) {
            previousInstances.subList(0, old).clear();
        }
        lastPipelineInstanceCount = previousInstances.size();
        saved.clear();
        if (Config.debugLogging) {
            LOGGER.info("[view-capture] pipeline changed; previousInstances={}", previousInstances.size());
        }
    }

    /** 该视角是否已有保存状态（新租约首帧 = false，由 {@link ViewScope} 回落到 main 基线）。 */
    public static boolean hasView(String view) {
        return view != null && saved.containsKey(view);
    }

    public static void restoreView(String view) {
        if (view == null) {
            return;
        }
        SavedView state = saved.get(view);
        if (state == null) {
            return;
        }
        restoreTracker(state);
        restorePrevious(state);
        if (Config.debugLogging) {
            LOGGER.info("[view-capture] restore view={}", view);
        }
    }

    public static void saveView(String view) {
        if (view == null) {
            return;
        }
        SavedView state = capture();
        if (state != null) {
            saved.put(view, state);
            if (Config.debugLogging) {
                LOGGER.info("[view-capture] save view={}", view);
            }
        }
    }

    public static void releaseView(String view) {
        if (view == null) {
            return;
        }
        saved.remove(view);
        ColorHistoryCapture.release(view);
    }

    private static SavedView capture() {
        Vector3d current = copyVec(field(tracker, "currentCameraPosition"));
        Vector3d previous = copyVec(field(tracker, "previousCameraPosition"));
        Vector3d currentUnshifted = copyVec(field(tracker, "currentCameraPositionUnshifted"));
        Vector3d previousUnshifted = copyVec(field(tracker, "previousCameraPositionUnshifted"));
        Vector3d shift = copyVec(field(tracker, "shift"));

        List<Matrix4f> matrices = new ArrayList<>(previousInstances.size());
        for (Object instance : previousInstances) {
            Object value = field(instance, "previous");
            matrices.add(value instanceof Matrix4f matrix ? new Matrix4f(matrix) : null);
        }
        return new SavedView(current, previous, currentUnshifted, previousUnshifted, shift, matrices);
    }

    private static void restoreTracker(SavedView state) {
        setField(tracker, "currentCameraPosition", copyVec(state.current));
        setField(tracker, "previousCameraPosition", copyVec(state.previous));
        setField(tracker, "currentCameraPositionUnshifted", copyVec(state.currentUnshifted));
        setField(tracker, "previousCameraPositionUnshifted", copyVec(state.previousUnshifted));
        setField(tracker, "shift", copyVec(state.shift));
    }

    private static void restorePrevious(SavedView state) {
        int count = Math.min(previousInstances.size(), state.previousMatrices.size());
        for (int i = 0; i < count; i++) {
            Matrix4f matrix = state.previousMatrices.get(i);
            if (matrix != null) {
                setField(previousInstances.get(i), "previous", new Matrix4f(matrix));
            }
        }
    }

    private static Vector3d copyVec(Object value) {
        return value instanceof Vector3d vec ? new Vector3d(vec) : null;
    }

    private static Object field(Object owner, String name) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                // continue
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void setField(Object owner, String name, Object value) {
        if (owner == null || value == null) {
            return;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(owner, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // continue
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private record SavedView(Vector3d current, Vector3d previous, Vector3d currentUnshifted,
                             Vector3d previousUnshifted, Vector3d shift,
                             List<Matrix4f> previousMatrices) {
    }
}
