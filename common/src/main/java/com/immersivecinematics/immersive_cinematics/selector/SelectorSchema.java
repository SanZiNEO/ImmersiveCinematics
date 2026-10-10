package com.immersivecinematics.immersive_cinematics.selector;

import java.util.List;

/**
 * 选择器字段与取值的单一定义点：调用点名单、锚点取值集、择一取值集、调用点专属字段名模式
 * （{@code <字段>_<调用点>}）。
 *
 * schema 声明（{@code script/schema/TrackSchemas.camera()}）、脚本校验（{@code script/ScriptValidator}）
 * 与运行时服务（{@link EntitySelectorService}）共用本类，三方不可能不同步。名单与取值集的顺序 =
 * 字段登记顺序 = 校验报错文案顺序（{@code " / "} 连接），增删一律只改本类。
 */
public final class SelectorSchema {

    /**
     * 选择器调用点（角色）名单：每个调用点拥有自己的策略 / 锚点 / 择一字段，可单独配置，互不影响。
     * 通用字段只作默认回落——不写调用点专属字段时行为与只用通用字段一致。
     */
    public static final List<String> CALLPOINTS = List.of(
            "follow", "look_at", "look_at_target", "yaw_base",
            "yaw_base_from", "yaw_base_to", "facing_origin", "facing_target");

    /** 锚点取值：调用方传入的相机位置（缺省，旧行为）。 */
    public static final String ANCHOR_CAMERA = "camera";
    /** 锚点取值：玩家脚底（渲染帧插值）。 */
    public static final String ANCHOR_PLAYER = "player";
    /** 锚点取值：本调用点已解析锁的实体脚底，尚未解析到目标时回落 {@link #ANCHOR_CAMERA}。 */
    public static final String ANCHOR_TARGET = "target";
    /** 锚点取值：{@code position.relative_origin} 点源坐标，不可解析回落 {@link #ANCHOR_CAMERA}。 */
    public static final String ANCHOR_ORIGIN = "origin";

    /**
     * 锚点取值集（顺序 = 校验报错文案顺序）：就近排序（{@code sort=nearest}）的参考点，
     * 也是服务端解析请求的原点。坐标空间 = 世界空间方块坐标，脚底口径。
     */
    public static final List<String> ANCHORS = List.of(ANCHOR_CAMERA, ANCHOR_PLAYER, ANCHOR_TARGET, ANCHOR_ORIGIN);

    /** 择一取值：候选返回序首个可用（缺省，旧行为）。 */
    public static final String PICK_FIRST = "first";
    /** 择一取值：客户端可用候选内取距锚点最近者（平方距离比较，不开平方）。 */
    public static final String PICK_NEAREST = "nearest";
    /** 择一取值：候选返回序首个存活者，不比距离。 */
    public static final String PICK_ALIVE = "alive";

    /** 择一取值集（顺序 = 校验报错文案顺序）：从候选（本地扫描命中 / 服务端回传 UUID 列表）取定目标的规则。 */
    public static final List<String> PICKS = List.of(PICK_FIRST, PICK_NEAREST, PICK_ALIVE);

    /**
     * 调用点专属字段名 = {@code <字段>_<调用点>}（如 {@code selector_refresh_look_at}）。
     *
     * @param field     通用字段名
     * @param callpoint 调用点名；{@code null} = 该调用点无专属字段
     * @return 调用点专属字段名；{@code callpoint} 为 {@code null} 时返回 {@code null}
     */
    public static String callpointField(String field, String callpoint) {
        return callpoint == null ? null : field + "_" + callpoint;
    }

    /** {@code role} 是否在调用点名单内；不在名单内 = 只用通用字段。 */
    public static boolean isCallpoint(String role) {
        return role != null && CALLPOINTS.contains(role);
    }

    /** 取值是否属锚点取值集；未知值（未过校验的脚本）按缺省 {@link #ANCHOR_CAMERA} 处理。 */
    public static boolean isAnchor(String value) {
        return value != null && ANCHORS.contains(value);
    }

    /** 取值是否属择一取值集；未知值（未过校验的脚本）按缺省 {@link #PICK_FIRST} 处理。 */
    public static boolean isPick(String value) {
        return value != null && PICKS.contains(value);
    }

    private SelectorSchema() {}
}
