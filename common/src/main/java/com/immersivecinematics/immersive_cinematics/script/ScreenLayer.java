package com.immersivecinematics.immersive_cinematics.script;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 画面层规范名（15 层）：脚本 {@code screen_layers} 的键域。
 *
 * <p>每层携带三样东西：规范名（{@link #id()}）、所属平台空间（{@link #space()}）、以及旧字段别名
 * （{@link #legacyAlias()}，{@code null} = 无旧别名，只能经 {@code hide_hud} 回落）。旧字段的解析规则
 * 集中在 {@link #legacyHidden(ScreenLayer, LegacyFlags)} 的单一 switch 里，控制器只做适配，
 * 不复制映射表。</p>
 *
 * <p>{@code health} / {@code status} <strong>不是</strong>别名：它们是 Forge 分类名，必须作为动态键原样
 * 保留（{@link #canonicalize(String)} 对它们返回原样）。</p>
 */
public enum ScreenLayer {
    HUD_HOTBAR("hud.hotbar", Space.HUD, "hotbar"),
    HUD_CROSSHAIR("hud.crosshair", Space.HUD, "crosshair"),
    HUD_HEALTH("hud.health", Space.HUD, null),
    HUD_EXPERIENCE("hud.experience", Space.HUD, null),
    HUD_BOSSBAR("hud.bossbar", Space.HUD, "bossbar"),
    HUD_CHAT("hud.chat", Space.HUD, "chat"),
    HUD_SCOREBOARD("hud.scoreboard", Space.HUD, "scoreboard"),
    HUD_ACTION_BAR("hud.action_bar", Space.HUD, "action_bar"),
    HUD_TITLE("hud.title", Space.HUD, "title"),
    HUD_SUBTITLE("hud.subtitle", Space.HUD, "subtitles"),
    HUD_SKIP_PROMPT("hud.skip_prompt", Space.HUD, "skip_hud"),
    VIEWMODEL("viewmodel", Space.WORLD, "arm"),
    VIEW_BOBBING("view_bobbing", Space.VIEW, "bob"),
    SCREEN_EFFECTS("screen_effects", Space.POST, null),
    PLAYER_MODEL("player_model", Space.WORLD, null);

    /** 层的所属空间：HUD 界面 / 世界渲染 / 视角 / 后处理。 */
    public enum Space { HUD, WORLD, VIEW, POST }

    /**
     * 旧脚本字段的同形读取面：控制器实现它读自己的三态字段，映射表（switch）只写一次。
     *
     * <p>三态字段 {@code null} = 未声明；{@link #hideHud()} 与 {@link #renderPlayerModel()} 是基础值
     * （{@code hide_hud} 缺省 {@code true}，{@code render_player_model} 缺省 {@code true}）。</p>
     */
    public interface LegacyFlags {
        boolean hideHud();
        Boolean hideHotbar();
        Boolean hideCrosshair();
        Boolean hideBossbar();
        Boolean hideChat();
        Boolean hideScoreboard();
        Boolean hideActionBar();
        Boolean hideTitle();
        Boolean hideSubtitles();
        Boolean hideSkipHud();
        Boolean hideArm();
        Boolean suppressBob();
        Boolean suppressDistortion();
        boolean renderPlayerModel();
    }

    private static final ScreenLayer[] VALUES = values();
    private static final List<ScreenLayer> ALL = List.of(VALUES);
    private static final Map<String, ScreenLayer> BY_ID;

    static {
        Map<String, ScreenLayer> map = new HashMap<>();
        for (ScreenLayer layer : VALUES) {
            map.put(layer.id, layer);
            if (layer.legacyAlias != null) map.put(layer.legacyAlias, layer);
        }
        BY_ID = Map.copyOf(map);
    }

    private final String id;
    private final Space space;
    private final String legacyAlias;

    ScreenLayer(String id, Space space, String legacyAlias) {
        this.id = id;
        this.space = space;
        this.legacyAlias = legacyAlias;
    }

    /** 规范名（{@code screen_layers} 的键）。 */
    public String id() { return id; }

    /** 所属空间。 */
    public Space space() { return space; }

    /** 旧字段别名；{@code null} = 无别名（只经 {@code hide_hud} 回落）。 */
    public String legacyAlias() { return legacyAlias; }

    /** 规范名或别名 → 层；未命中 {@code null}。 */
    public static ScreenLayer byId(String value) {
        return value == null ? null : BY_ID.get(value);
    }

    /** 规范名或别名 → 规范名；未命中原样返回（{@code null} 仍是 {@code null}）。 */
    public static String canonicalize(String value) {
        ScreenLayer layer = byId(value);
        return layer == null ? value : layer.id;
    }

    /** 全部层，顺序与枚举声明一致（不可变）。 */
    public static List<ScreenLayer> all() { return ALL; }

    /**
     * 旧字段语义下的「是否隐藏」：单一 switch 表，所有旧回落规则都在这里。
     *
     * @param layer 目标层；{@code null} → {@code false}
     * @param flags 旧字段读取面；{@code null} → {@code false}
     */
    public static boolean legacyHidden(ScreenLayer layer, LegacyFlags flags) {
        if (layer == null || flags == null) return false;
        switch (layer) {
            case HUD_HOTBAR: return resolve(flags.hideHotbar(), flags.hideHud());
            case HUD_CROSSHAIR: return resolve(flags.hideCrosshair(), flags.hideHud());
            case HUD_HEALTH: return flags.hideHud();
            case HUD_EXPERIENCE: return flags.hideHud();
            case HUD_BOSSBAR: return resolve(flags.hideBossbar(), flags.hideHud());
            case HUD_CHAT: return resolve(flags.hideChat(), flags.hideHud());
            case HUD_SCOREBOARD: return resolve(flags.hideScoreboard(), flags.hideHud());
            case HUD_ACTION_BAR: return resolve(flags.hideActionBar(), flags.hideHud());
            case HUD_TITLE: return resolve(flags.hideTitle(), flags.hideHud());
            case HUD_SUBTITLE: return resolve(flags.hideSubtitles(), flags.hideHud());
            case HUD_SKIP_PROMPT: return Boolean.TRUE.equals(flags.hideSkipHud());
            case VIEWMODEL: return resolve(flags.hideArm(), flags.hideHud());
            case VIEW_BOBBING: return resolve(flags.suppressBob(), flags.hideHud());
            case SCREEN_EFFECTS:
                return flags.suppressDistortion() != null
                        ? flags.suppressDistortion()
                        : resolve(flags.suppressBob(), flags.hideHud());
            case PLAYER_MODEL: return !flags.renderPlayerModel();
            default: return false;
        }
    }

    /** 旧字段语义下的「是否隐藏」：{@link ScriptMeta.RuntimeBehavior} 适配后转调同一张表。 */
    public static boolean legacyHidden(ScreenLayer layer, ScriptMeta.RuntimeBehavior behavior) {
        if (behavior == null) return false;
        return legacyHidden(layer, new RuntimeBehaviorFlags(behavior));
    }

    private static boolean resolve(Boolean value, boolean hideHud) {
        return value != null ? value : hideHud;
    }

    /** {@link ScriptMeta.RuntimeBehavior} 的只读适配（每条目一次，仅并集计算期使用）。 */
    private record RuntimeBehaviorFlags(ScriptMeta.RuntimeBehavior b) implements LegacyFlags {
        @Override public boolean hideHud() { return b.hideHud(); }
        @Override public Boolean hideHotbar() { return b.hideHotbar(); }
        @Override public Boolean hideCrosshair() { return b.hideCrosshair(); }
        @Override public Boolean hideBossbar() { return b.hideBossbar(); }
        @Override public Boolean hideChat() { return b.hideChat(); }
        @Override public Boolean hideScoreboard() { return b.hideScoreboard(); }
        @Override public Boolean hideActionBar() { return b.hideActionBar(); }
        @Override public Boolean hideTitle() { return b.hideTitle(); }
        @Override public Boolean hideSubtitles() { return b.hideSubtitles(); }
        @Override public Boolean hideSkipHud() { return b.hideSkipHud(); }
        @Override public Boolean hideArm() { return b.hideArm(); }
        @Override public Boolean suppressBob() { return b.suppressBob(); }
        @Override public Boolean suppressDistortion() { return b.suppressDistortion(); }
        @Override public boolean renderPlayerModel() { return b.renderPlayerModel(); }
    }
}
