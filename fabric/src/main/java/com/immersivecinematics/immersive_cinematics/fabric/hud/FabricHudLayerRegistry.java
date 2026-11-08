package com.immersivecinematics.immersive_cinematics.fabric.hud;

import com.immersivecinematics.immersive_cinematics.api.HudLayerRegistry;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.control.CinematicController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fabric 平台专属的「自定义层 → 渲染回调」表：注册表负责判定，回调只负责画。
 *
 * <p>跨平台的「overlay ID → 分类」注册仍走 {@link HudLayerRegistry} 门面；本类只补上 Fabric
 * 缺失的那一半——原生 {@code HudRenderCallback} 没有层 ID、也不可取消，第三方要参与白名单
 * 只能在这里登记自己的层与渲染回调。{@link #categoryOf} 的解析顺序是
 * 本类自有表 → 门面表 → 原始层 ID。</p>
 *
 * <p>调用时机：只在接管重画的末尾调用——整帧被 {@code FabricGuiTakeoverMixin} 取消后，
 * 本表在重画末尾执行，于是「白名单判为显示」的层被放行、其余连同原生回调一起消失。</p>
 */
public final class FabricHudLayerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmersiveCinematics/FabricHud");

    private static final Map<String, String> LAYER_CATEGORIES = new ConcurrentHashMap<>();

    /** 自定义层，按注册顺序追加在原版层之后（第一版不支持插入原版层之间）。 */
    private static final List<CustomOverlay> CUSTOM_OVERLAYS = new CopyOnWriteArrayList<>();

    /** 自定义层的渲染回调：只负责绘制，显隐判定由注册表做。 */
    public interface Renderer {
        void render(GuiGraphics graphics, float partialTick);
    }

    /** 一条注册记录：层 ID（也是脚本 {@code screen_layers} 的键）+ 判定分类 + 渲染回调。 */
    public record CustomOverlay(String layerId, String category, Renderer renderer) {}

    private FabricHudLayerRegistry() {}

    /**
     * 内部实现；只写分类表，不注册渲染回调。
     * 本表只放 Fabric 侧自有层，第三方注册一律落门面表或 {@link #register(String, String, Renderer)}。
     */
    public static void register(String layerId, String category) {
        LAYER_CATEGORIES.put(layerId, category);
    }

    /**
     * 注册一个自定义层：脚本用同一个 {@code layerId} 作为 {@code screen_layers} 的键控制它。
     *
     * <p>{@code layerId} 或 {@code renderer} 为 {@code null} 时忽略整次调用。{@code category} 为空 / {@code null}
     * 时按 {@link #categoryOf} 解析（自有表 → 门面表 → 原始层 ID），并把解析结果写回分类表，
     * 让后续 {@link #categoryOf} 与判定保持一致。同一层重复注册按注册顺序追加，不覆盖先前的回调。</p>
     */
    public static void register(String layerId, String category, Renderer renderer) {
        if (layerId == null || renderer == null) return;
        String resolved = (category == null || category.isEmpty()) ? categoryOf(layerId) : category;
        LAYER_CATEGORIES.put(layerId, resolved);
        CUSTOM_OVERLAYS.add(new CustomOverlay(layerId, resolved, renderer));
    }

    /**
     * 遍历自定义层并调用渲染回调：只在接管重画的末尾调用。
     * 无活跃实例时整体跳过；每层用 {@code isLayerHidden(layerId, category)} 判定，判隐藏则跳过。
     * 单个回调抛异常只记日志并继续下一个，不拖垮整段 HUD。
     */
    public static void renderCustom(GuiGraphics graphics, float partialTick) {
        if (!CameraManager.INSTANCE.isActive()) return;
        for (CustomOverlay overlay : CUSTOM_OVERLAYS) {
            if (CinematicController.INSTANCE.isLayerHidden(overlay.layerId(), overlay.category())) continue;
            try {
                overlay.renderer().render(graphics, partialTick);
            } catch (Exception e) {
                LOGGER.error("自定义 HUD 层 '{}' 渲染失败", overlay.layerId(), e);
            }
        }
    }

    /**
     * 查询分类：本类自有表 → {@link HudLayerRegistry}（第三方，按层 ID 解析成资源 ID 后查）→ 原始层 ID。
     *
     * <p>中间一档是跨加载器契约：第三方用门面注册时给的是 {@link ResourceLocation}，Fabric 侧只有字符串，
     * 因此这里按资源 ID 语法解析；不是合法资源 ID 的层名（纯字符串）直接落到最后一档。</p>
     */
    public static String categoryOf(String layerId) {
        String category = LAYER_CATEGORIES.get(layerId);
        if (category != null) return category;
        ResourceLocation overlayId = ResourceLocation.tryParse(layerId);
        if (overlayId != null) {
            String external = HudLayerRegistry.categoryOf(overlayId);
            if (external != null) return external;
        }
        return layerId;
    }
}
