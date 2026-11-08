package com.immersivecinematics.immersive_cinematics.script;

import java.util.List;

/**
 * 画面层规范名（15 层）：{@code screen_layers} 的键域，也是全部规范层名的唯一来源。
 *
 * <p>每层只携带两样东西：规范名（{@link #id()}）与所属平台空间（{@link #space()}）。
 * 层的显隐只由脚本 {@code screen_layers} 的声明决定，没有旧字段别名，也没有回落链。</p>
 *
 * <p>{@code health} / {@code status} / 注册分类名 / 未注册 overlay 的资源 ID 都是 {@code screen_layers}
 * 的动态键，按原始字符串参与判定，<strong>不是</strong>本枚举的别名。</p>
 */
public enum ScreenLayer {
    HUD_HOTBAR("hud.hotbar", Space.HUD),
    HUD_CROSSHAIR("hud.crosshair", Space.HUD),
    HUD_HEALTH("hud.health", Space.HUD),
    HUD_EXPERIENCE("hud.experience", Space.HUD),
    HUD_BOSSBAR("hud.bossbar", Space.HUD),
    HUD_CHAT("hud.chat", Space.HUD),
    HUD_SCOREBOARD("hud.scoreboard", Space.HUD),
    HUD_ACTION_BAR("hud.action_bar", Space.HUD),
    HUD_TITLE("hud.title", Space.HUD),
    HUD_SUBTITLE("hud.subtitle", Space.HUD),
    HUD_SKIP_PROMPT("hud.skip_prompt", Space.HUD),
    VIEWMODEL("viewmodel", Space.WORLD),
    VIEW_BOBBING("view_bobbing", Space.VIEW),
    SCREEN_EFFECTS("screen_effects", Space.POST),
    PLAYER_MODEL("player_model", Space.WORLD);

    /** 层的所属空间：HUD 界面 / 世界渲染 / 视角 / 后处理。 */
    public enum Space { HUD, WORLD, VIEW, POST }

    private static final ScreenLayer[] VALUES = values();
    private static final List<ScreenLayer> ALL = List.of(VALUES);

    private final String id;
    private final Space space;

    ScreenLayer(String id, Space space) {
        this.id = id;
        this.space = space;
    }

    /** 规范名（{@code screen_layers} 的键）。 */
    public String id() { return id; }

    /** 所属空间。 */
    public Space space() { return space; }

    /** 全部层，顺序与枚举声明一致（不可变）。 */
    public static List<ScreenLayer> all() { return ALL; }
}
