package com.immersivecinematics.immersive_cinematics.control;

import com.immersivecinematics.immersive_cinematics.Config;
import com.immersivecinematics.immersive_cinematics.ImmersiveCinematics;
import com.immersivecinematics.immersive_cinematics.camera.CameraManager;
import com.immersivecinematics.immersive_cinematics.camera.PlaybackInstance;
import com.immersivecinematics.immersive_cinematics.trigger.client.ClientScriptReceiver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 跳过提示 HUD：画跳过键图标与长按进度环，仅脚本模式、顶层实例可跳过且本人尚未投票时出现。
 *
 * 长按块（环形 + 键位图标 + 文案）与票数文本是两个独立元素，各自按「元素中心的屏幕百分比」
 * 定位（{@code Config.skipHudX/skipHudY} 与 {@code Config.voteHudX/voteHudY}），缺省落在同一行：
 * 长按块靠右、票数水平居中。票数只在真多人或单人存档已开 LAN 发布时显示——
 * 单人未发布没有第二个投票者，画出来只是噪声。
 *
 * <p>票数按实例分流：每个可呈现的实例一行，顶层实例恒占 {@code voteHudX/voteHudY} 那一行，
 * 其余实例按 {@code CameraManager.instances()} 顺序向下堆叠，因此多实例同时可跳过时不会互相覆盖。
 * 只有一个批次时行内不带脚本名，多批并存才逐行加名前缀。
 * 长按块仍只对顶层实例绘制，且已投票者不再看到它。
 */
public class SkipHudRenderer {

    private static final ResourceLocation SKIP_KEY_TEXTURE =
            new ResourceLocation(ImmersiveCinematics.MOD_ID, "textures/gui/skip_key.png");

    private static final int ICON_SIZE = 16;
    private static final int RING_RADIUS = 12;
    private static final int RING_WIDTH = 4;
    private static final int GAP_TEXT_RING = 4;
    private static final int RING_SEGMENTS = 80;

    /** 绘制跳过提示；不满足显示条件时直接返回，不做任何绘制。 */
    public static void render(GuiGraphics guiGraphics) {
        if (!Config.showSkipHud) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (!CameraManager.INSTANCE.isScriptMode()) return;
        // 整块开关：hud.skip_prompt 层判隐藏时，长按块与票数一起消失
        if (CinematicController.INSTANCE.isLayerHidden("hud.skip_prompt")) return;

        // 长按块只对顶层实例绘制；已投票者不再显示（服务端已记票，再按也不会重复生效）
        PlaybackInstance top = CameraManager.INSTANCE.activeInstance();
        if (top != null && top.isSkippable()
                && !ClientScriptReceiver.hasVoted(top.scriptId(), top.instanceId())) {
            renderPrompt(mc, guiGraphics, CinematicKeyBindings.getSkipHoldProgress());
        }
        renderVotes(mc, guiGraphics);
    }

    private static void renderPrompt(Minecraft mc, GuiGraphics guiGraphics, float progress) {
        var font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        Component keyDisplay = CinematicKeyBindings.SKIP_KEY.getTranslatedKeyMessage();
        Component text = Component.translatable("hud.immersive_cinematics.skip_hold",
                keyDisplay.plainCopy().withStyle(style -> style.withColor(0xFFFFFF)));

        int textWidth = font.width(text);
        int blockW = RING_RADIUS * 2 + GAP_TEXT_RING + textWidth;
        int blockH = Math.max(RING_RADIUS * 2, font.lineHeight);

        // 坐标指元素中心：先换算屏幕像素，再按块尺寸回推左上角
        int cx = Math.round(sw * Config.skipHudX / 100f);
        int cy = Math.round(sh * Config.skipHudY / 100f);
        int baseX = cx - blockW / 2;
        int baseY = cy - blockH / 2;

        int ringCenterX = baseX + blockW - RING_RADIUS;
        int ringCenterY = baseY + blockH / 2;

        int textColor = 0xAAFFFFFF;

        int textX = baseX;
        int textY = baseY + (blockH - font.lineHeight) / 2;
        guiGraphics.drawString(font, text, textX, textY, textColor, true);

        int iconX = ringCenterX - ICON_SIZE / 2;
        int iconY = ringCenterY - ICON_SIZE / 2;
        guiGraphics.blit(SKIP_KEY_TEXTURE, iconX, iconY, ICON_SIZE, ICON_SIZE, 0, 0, 64, 64, 64, 64);

        String keyName = keyDisplay.getString();
        String keyShort = keyName.length() <= 3 ? keyName : keyName.substring(0, 2);
        int keyTextX = ringCenterX - font.width(keyShort) / 2;
        int keyTextY = ringCenterY - font.lineHeight / 2 + 1;
        guiGraphics.drawString(font, keyShort, keyTextX, keyTextY, textColor, true);

        if (progress > 0f) {
            drawRingArc(guiGraphics, ringCenterX, ringCenterY, RING_RADIUS, RING_WIDTH, progress, sw, sh);
        }
    }

    /**
     * 票数按实例分流：每个可呈现的实例一行，行中心由 {@code voteHudX/voteHudY} 与行号给出。
     * 顶层实例恒占第一行（像素基准不变），其余实例按 {@code CameraManager.instances()} 顺序
     * 向下堆叠，行距 {@code font.lineHeight + 2}；顶层实例没有票数时不占位，后续行从第一行起排。
     *
     * <p>只有一个批次在呈现时省掉名字（{@code skip_vote}，2 参）；多批并存才逐行带名字
     * （{@code skip_vote_named}，3 参），否则每行重复同一个脚本名只是噪声。
     */
    private static void renderVotes(Minecraft mc, GuiGraphics guiGraphics) {
        if (!shouldShowVotes(mc)) return;

        var font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int vcx = Math.round(sw * Config.voteHudX / 100f);
        int vcy = Math.round(sh * Config.voteHudY / 100f);
        int lineStep = font.lineHeight + 2;

        PlaybackInstance top = CameraManager.INSTANCE.activeInstance();
        List<VoteLine> lines = new ArrayList<>();
        ClientScriptReceiver.SkipVote topVote = top != null ? voteRow(top) : null;
        if (topVote != null) {
            lines.add(new VoteLine(top, topVote));
        }
        for (PlaybackInstance instance : CameraManager.INSTANCE.instances()) {
            if (instance == top) continue;
            ClientScriptReceiver.SkipVote vote = voteRow(instance);
            if (vote != null) {
                lines.add(new VoteLine(instance, vote));
            }
        }

        boolean named = lines.size() > 1;
        for (int row = 0; row < lines.size(); row++) {
            VoteLine line = lines.get(row);
            drawVoteRow(guiGraphics, line.instance(), line.vote(), vcx, vcy + row * lineStep, named);
        }
    }

    /** 一行票数：实例 + 该实例的票数快照。 */
    private record VoteLine(PlaybackInstance instance, ClientScriptReceiver.SkipVote vote) {}

    /**
     * 该实例的行票数快照；不该占行时返回 {@code null}。
     * 条件：非编辑器驱动、正在播、可跳过，且缓存里有它自己的记录（总数 &gt; 0）。
     */
    private static ClientScriptReceiver.SkipVote voteRow(PlaybackInstance instance) {
        if (instance.isEditorDriven() || !instance.player().isPlaying() || !instance.isSkippable()) return null;
        ClientScriptReceiver.SkipVote vote =
                ClientScriptReceiver.getSkipVote(instance.scriptId(), instance.instanceId());
        return vote != null && vote.total > 0 ? vote : null;
    }

    /** 一行票数：文本按自身宽度水平居中于 {@code voteHudX}，行中心为 {@code vcy}。 */
    private static void drawVoteRow(GuiGraphics guiGraphics, PlaybackInstance instance,
                                    ClientScriptReceiver.SkipVote vote, int vcx, int vcy, boolean named) {
        var font = Minecraft.getInstance().font;
        Component voteText = named
                ? Component.translatable("hud.immersive_cinematics.skip_vote_named",
                        voteName(instance), vote.voters, vote.total)
                : Component.translatable("hud.immersive_cinematics.skip_vote",
                        vote.voters, vote.total);
        int voteW = font.width(voteText);
        guiGraphics.drawString(font, voteText, vcx - voteW / 2, vcy - font.lineHeight / 2, 0xAAFFFFFF, true);
    }

    /** 行身份标签：脚本 meta 名称；未命名（null / 空白）回落脚本 id，保证每行都能区分实例。 */
    private static String voteName(PlaybackInstance instance) {
        var script = instance.script();
        var meta = script != null ? script.getMeta() : null;
        String name = meta != null ? meta.getName() : null;
        return name == null || name.isBlank() ? instance.scriptId() : name;
    }

    /**
     * 票数只在对「别人也能投」的场合有意义：真多人，或单人存档已开 LAN 发布。
     * 与 {@code ServerLevel.announceSleepStatus} 的 {@code !isSingleplayer() || isPublished()} 同口径。
     */
    private static boolean shouldShowVotes(Minecraft mc) {
        if (!mc.isLocalServer()) return true;
        var server = mc.getSingleplayerServer();
        return server != null && server.isPublished();
    }

    private static void drawRingArc(GuiGraphics gg, int cx, int cy, int r, int w, float p, int sw, int sh) {
        int segments = Math.max(1, (int) (RING_SEGMENTS * p));
        float angleStep = (float) (2 * Math.PI / RING_SEGMENTS);
        float startAngle = (float) (-Math.PI / 2);

        for (int i = 0; i < segments; i++) {
            float a1 = startAngle + i * angleStep;
            float a2 = startAngle + (i + 1) * angleStep;

            int x1o = cx + (int) (r * Math.cos(a1));
            int y1o = cy + (int) (r * Math.sin(a1));
            int x2o = cx + (int) (r * Math.cos(a2));
            int y2o = cy + (int) (r * Math.sin(a2));
            int x1i = cx + (int) ((r - w) * Math.cos(a1));
            int y1i = cy + (int) ((r - w) * Math.sin(a1));
            int x2i = cx + (int) ((r - w) * Math.cos(a2));
            int y2i = cy + (int) ((r - w) * Math.sin(a2));

            fillTriangle(gg, x1o, y1o, x2o, y2o, x1i, y1i, 0xAAFFFFFF, sw, sh);
            fillTriangle(gg, x2o, y2o, x2i, y2i, x1i, y1i, 0xAAFFFFFF, sw, sh);
        }
    }

    private static void fillTriangle(GuiGraphics gg, int x1, int y1, int x2, int y2, int x3, int y3, int color, int sw, int sh) {
        int minX = Math.max(0, Math.min(x1, Math.min(x2, x3)));
        int maxX = Math.min(sw, Math.max(x1, Math.max(x2, x3)));
        int minY = Math.max(0, Math.min(y1, Math.min(y2, y3)));
        int maxY = Math.min(sh, Math.max(y1, Math.max(y2, y3)));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (pointInTriangle(x, y, x1, y1, x2, y2, x3, y3)) {
                    gg.fill(x, y, x + 1, y + 1, color);
                }
            }
        }
    }

    private static boolean pointInTriangle(int px, int py, int x1, int y1, int x2, int y2, int x3, int y3) {
        float d1 = sign(px, py, x1, y1, x2, y2);
        float d2 = sign(px, py, x2, y2, x3, y3);
        float d3 = sign(px, py, x3, y3, x1, y1);
        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(hasNeg && hasPos);
    }

    private static float sign(int px, int py, int x1, int y1, int x2, int y2) {
        return (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2);
    }
}
