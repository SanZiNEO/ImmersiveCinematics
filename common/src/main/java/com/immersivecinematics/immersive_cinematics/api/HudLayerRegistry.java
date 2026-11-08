package com.immersivecinematics.immersive_cinematics.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HUD 层注册门面（对外稳定面）：第三方模组把自己的 overlay 归入一个脚本可写的分类。
 *
 * <p>脚本侧可写三类层名：15 个规范层名（{@code hud.hotbar} … {@code player_model}）、
 * {@link #register} 注册的分类名、以及未注册 overlay 的完整资源 ID。三类键都由
 * {@code screen_layers} 直接声明；未声明的键一律隐藏——画面层接管是唯一模式：任一隐藏层 →
 * 平台清除整帧 HUD，只重画白名单内的层。</p>
 *
 * <p>唯一注册表：第三方注册只落在这里。平台实现（Forge / Fabric）只持有自己的内置表，并按
 * 「内置表 → 本表 → 平台回落」的顺序解析分类；不得再建第二个第三方注册表。</p>
 *
 * <p>覆盖边界：本门面只对「平台能按 ID 判定」的层有效——Forge 的 overlay 注册表条目、
 * 在 {@code RenderGuiEvent.Pre} 里自绘的第三方 HUD，以及 Fabric 侧主动调用
 * {@code FabricHudVisibility.isHidden} 的层。直接 mixin 进 {@code Gui.render} 或绕过这些入口
 * 绘制的 HUD 没有稳定挂点，注册了也不会被脚本控制。</p>
 *
 * <p>稳定性：本包（{@code api}）内的类型与方法默认只增不删；破坏性变更需显式裁决后执行
 * （例如画面层收敛为唯一模式时删除平台能力位）。</p>
 *
 * <p>线程契约：注册应在客户端初始化期完成（{@link #install} 与首次 {@link #register} 都属该窗口），
 * 读取发生在渲染线程。表本身并发安全，但分类语义假定注册早于任何一帧判定——播放中途注册的层
 * 从下一帧起才可能被命中。</p>
 */
public final class HudLayerRegistry {

    /**
     * 平台挂接点：注册写表之后的回调。
     *
     * <p>Forge 的消费链（整帧接管重画）直接读表，回调留空即可；Fabric 借它打印一次平台边界说明。
     * 实现<strong>不得抛异常</strong>——异常会外溢到第三方模组的注册调用里，破坏「注册表无异常路径」的契约。</p>
     */
    public interface Platform {
        void onRegistered(ResourceLocation overlayId, String category);
    }

    private static final Map<ResourceLocation, String> LAYER_CATEGORIES = new ConcurrentHashMap<>();
    private static volatile Platform platform;

    private HudLayerRegistry() {}

    /**
     * 安装平台回调。只应在客户端初始化期调用一次；后装者覆盖先装者（平台实现都是幂等赋值）。
     * 未安装时 {@link #register} 照常写表，只是不触发回调——注册本身不依赖平台存在。
     */
    public static void install(Platform platform) {
        HudLayerRegistry.platform = platform;
    }

    /**
     * 注册一个 overlay 到指定分类：脚本用 {@code screen_layers} 的键控制它——分类名、15 个规范层名
     * 或未注册 overlay 的资源 ID 都行。
     *
     * <p>{@code overlayId} 或 {@code category} 为 {@code null} 时忽略整次调用——null 表示调用方没给出
     * 有效注册信息，静默丢弃比写入一个不可判定的分类更安全。{@code category} 为空串按「未指定分类」
     * 处理，等价于 {@link #register(ResourceLocation)}。写入后回调 {@link Platform#onRegistered}
     * （未安装平台时跳过）。</p>
     */
    public static void register(ResourceLocation overlayId, String category) {
        if (overlayId == null || category == null) return;
        String resolved = category.isEmpty() ? overlayId.toString() : category;
        LAYER_CATEGORIES.put(overlayId, resolved);
        Platform installed = platform;
        if (installed != null) {
            installed.onRegistered(overlayId, resolved);
        }
    }

    /**
     * 注册一个 overlay，分类 = 资源 ID 字符串：脚本里写
     * {@code screen_layers: {"modid:overlay": false}} 即可隐藏它（{@code true} = 显示）。
     * 未注册的 Forge overlay 本来也按 ID 匹配，本重载只是把那个隐式口子写成显式契约。
     */
    public static void register(ResourceLocation overlayId) {
        if (overlayId == null) return;
        register(overlayId, overlayId.toString());
    }

    /**
     * 查询注册分类；<strong>未注册返回 {@code null}</strong>。
     *
     * <p>返回 null 而不是在这里直接回落，是为了让平台能区分「第三方注册过」与「没注册」：
     * 调用方拿到 null 后自行回落自己的默认——Forge 用 {@code overlayId.toString()}，
     * Fabric 用原始层 ID 字符串。</p>
     */
    public static String categoryOf(ResourceLocation overlayId) {
        if (overlayId == null) return null;
        return LAYER_CATEGORIES.get(overlayId);
    }
}
