package com.immersivecinematics.immersive_cinematics.camera.source;

import com.immersivecinematics.immersive_cinematics.selector.EntitySelectorService;
import net.minecraft.world.phys.Vec3;

/**
 * 相机侧选择器适配：把选择器解析 / 目标锁与切换 / 择一 / 锚点取位 / 平滑 / 捕获留底接到通用
 * {@link EntitySelectorService}；策略 / 锚点 / 择一字段的读取与点源求值归新格式求值层
 * （{@code ObjectWorldContext} / {@code PositionEvaluator}），本类只持有共享服务实例、世界上下文接线
 * 与包围盒取点数学。
 *
 * 线程与分配：仅客户端主线程，包围盒取点位于每帧求值路径；不新建集合、不装箱。
 * 状态所有权：目标锁与平滑状态由内部服务实例持有，生命周期 = 所属轨道播放器（停止 / 替换经 {@link #clear()}）。
 */
public final class EntityTargetResolver {

    private final EntitySelectorService service = new EntitySelectorService();

    /** 点源定位器（结构 / 方块）与玩家激活位置：与求值器共用同一实例，见 {@link #attachWorldContext}。 */
    private WorldPointLocator pointLocator;
    private Vec3 originPos;

    /**
     * 注入世界上下文（点源定位器 + 玩家激活位置）：由 {@code CameraKeyframeEvaluator} 构造时传入同一实例，
     * 两者共用定位器缓存；一次性接线，重复注入以最后一次为准。
     */
    public void attachWorldContext(WorldPointLocator locator, Vec3 originPos) {
        this.pointLocator = locator;
        this.originPos = originPos;
    }

    /** 通用选择器服务：同包的世界上下文（{@code ObjectWorldContext}）复用同一实例，锁 / 平滑 / 留底状态同源。 */
    EntitySelectorService service() {
        return service;
    }

    /** 生命周期清理：清空目标锁与客户端选择器缓存（停止播放 / 脚本替换时调用）。 */
    public void clear() {
        service.clear();
    }

    /**
     * 包围盒取点：{@code pos} = 实体渲染帧插值位置（= 包围盒底面中心），宽 / 高 = {@code getBbWidth} /
     * {@code getBbHeight}；横向 / 前后以底面中心为 50% 基准（±宽 / 2），纵向以底面为 0% 基准（高）。
     * 0 / 50 / 100 与包围盒最小侧 / 中心 / 最大侧逐位对应，故 {@code FOOT} / {@code CENTER} 与既有算式
     * 逐位等值（不另设分支）。
     *
     * @return 世界空间方块坐标
     */
    static Vec3 entityPoint(Vec3 pos, double width, double height, PointSource.EntityPoint point) {
        return new Vec3(
                pos.x + width * ((point.xPct() - 50.0) / 100.0),
                pos.y + height * (point.yPct() / 100.0),
                pos.z + width * ((point.zPct() - 50.0) / 100.0));
    }

    /** 捕获求值隔离：把当前锁状态深拷贝留底（同一时刻只保留最近一次留底）。 */
    public void snapshotLockState() {
        service.snapshotLockState();
    }

    /** 捕获求值隔离：把锁状态整体恢复为最近一次 {@link #snapshotLockState()} 的留底（深拷贝回写）。 */
    public void restoreLockState() {
        service.restoreLockState();
    }
}
