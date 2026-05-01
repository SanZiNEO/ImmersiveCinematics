package com.immersivecinematics.immersive_cinematics.handler;

import com.immersivecinematics.immersive_cinematics.Immersive_cinematics;
import com.immersivecinematics.immersive_cinematics.overlay.CinematicOverlay;
import com.immersivecinematics.immersive_cinematics.script.ScriptProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;

import java.util.Set;

/**
 * 电影模式 HUD 控制器 — 白名单机制
 * <p>
 * 当 ScriptProperties 处于激活状态且 hideHud=true 时，默认取消所有 overlay 渲染，
 * 只放行白名单中的 overlay。这样既能隐藏原版 HUD，
 * 也能自动屏蔽其他模组注册的 HUD overlay。
 * <p>
 * 白名单中的 overlay 在电影模式下仍然正常渲染，
 * 后续如果需要在 HUD 层显示自定义文本，可以注册自己的 overlay 并加入白名单。
 */
public class HudOverlayHandler {

    /**
     * 电影模式下允许显示的 overlay 白名单
     * 使用 ResourceLocation 标识，兼容原版和模组 overlay
     * <p>
     * 当前白名单包含：
     * - CinematicOverlay：我们的电影覆盖层（黑边、文字、视频等）
     * <p>
     * 后续可以添加自定义 overlay 到白名单，例如：
     *   VanillaGuiOverlay.SUBTITLES.id()
     */
    private static final Set<ResourceLocation> ALLOWED_OVERLAYS = Set.of(
            // 我们的电影覆盖层 — 黑边/文字/视频等
            new ResourceLocation(Immersive_cinematics.MODID, CinematicOverlay.OVERLAY_ID)
    );

    /**
     * 处理 GUI overlay 渲染事件
     * <p>
     * 在每个 overlay 渲染前检查：如果脚本激活且 hideHud=true 且该 overlay 不在白名单中，则取消渲染。
     * 如果脚本激活但 hideHud=false，则不屏蔽任何 overlay。
     */
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        ScriptProperties props = ScriptProperties.getCurrent();
        if (props == null) {
            return;
        }

        // 只在 hideHud=true 时屏蔽非白名单 overlay
        if (props.isHideHud() && !ALLOWED_OVERLAYS.contains(event.getOverlay().id())) {
            event.setCanceled(true);
        }
    }
}
