package com.immersivecinematics.immersive_cinematics.script.object.position;

import com.immersivecinematics.immersive_cinematics.camera.source.PointSource;
import net.minecraft.world.phys.Vec3;

/**
 * 求值层需要的世界查询接口（resolver 注入）：点源取点、实体朝向、方块 / 结构定位。
 *
 * <p>未解析统一用空值表达，不抛异常、不引入替代值：点 = {@code null}，角度 = {@link Double#NaN}
 * （与旧链路"找不到就找不到"同口径）。求值层负责把空值转成片段不可用 / 基准 0。</p>
 *
 * <p>坐标系与单位：世界空间方块坐标；方块与结构取中心；实体取包围盒内百分比点。</p>
 */
public interface WorldContext {

    /** 玩家激活位置（脚本激活时玩家所在位置，整场不变）；不可用 = {@code null}。 */
    Vec3 playerActivation();

    /** 实体包围盒内取点（每轴百分比见 {@link PointSource.EntityPoint}）；实体不存在 = {@code null}。 */
    Vec3 entityPoint(String selector, PointSource.EntityPoint point);

    /** 实体身体水平朝向（度，{@code yBodyRot}）；实体不存在 = {@link Double#NaN}。 */
    double entityBodyYaw(String selector);

    /** 实体视线俯仰（度，{@code xRot}）；实体不存在 = {@link Double#NaN}。 */
    double entityLookPitch(String selector);

    /** 就近搜索的方块中心；附近没有该方块 = {@code null}。 */
    Vec3 blockCenter(String blockId, int radius);

    /** 结构 bounding box 中心；结构未定位 = {@code null}。 */
    Vec3 structureCenter(String structureId);
}
