package com.immersivecinematics.immersive_cinematics.script;
import java.util.Collections;
import java.util.List;
/**
 * 脚本元数据：标识 / 作者 + 运行时行为（{@link RuntimeBehavior}）+ 触发定义 + 分辨率基准。
 */
public class ScriptMeta {

    private final String id;
    private final String name;
    private final String author;
    private final String description;
    private final RuntimeBehavior behavior;
    private final int priority;
    /** 跨脚本播放关系（{@code meta.play_mode}，三态，缺省 {@link PlayMode#PARALLEL}） */
    private final PlayMode playMode;
    private final String dimension;
    private final List<TriggerDefinition> triggers;
    /** 跳过投票比例（百分比，10~100）；null = 未指定，运行时回落到全局配置 Config.skipVoteRatio */
    private final Integer skipVoteRatio;
    /** 脚本级轮询间隔（tick，1~600）：该脚本全部轮询类触发器的缺省覆盖；null = 未指定，回落到类型级默认 */
    private final Integer triggerPollInterval;
    /** 编辑基准分辨率宽（像素）：分辨率转义系数 k 的分母，脚本未声明 meta.base_resolution 时为缺省值 */
    private final int baseWidth;
    /** 编辑基准分辨率高（像素）：分辨率转义系数 k 的分母，脚本未声明 meta.base_resolution 时为缺省值 */
    private final int baseHeight;

    /** 编辑基准分辨率缺省宽（像素）：脚本未声明 {@code meta.base_resolution} 时的 W基。 */
    public static final int DEFAULT_BASE_WIDTH = 1920;
    /** 编辑基准分辨率缺省高（像素）：脚本未声明 {@code meta.base_resolution} 时的 H基。 */
    public static final int DEFAULT_BASE_HEIGHT = 1080;

    public ScriptMeta(String id, String name, String author, String description,
                      RuntimeBehavior behavior, int priority, PlayMode playMode, String dimension,
                      List<TriggerDefinition> triggers, Integer skipVoteRatio,
                      Integer triggerPollInterval, int baseWidth, int baseHeight) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.description = description;
        this.behavior = behavior;
        this.priority = priority;
        this.playMode = playMode != null ? playMode : PlayMode.PARALLEL;
        this.dimension = dimension;
        this.triggers = triggers != null ? triggers : Collections.emptyList();
        this.skipVoteRatio = skipVoteRatio;
        this.triggerPollInterval = triggerPollInterval;
        this.baseWidth = baseWidth;
        this.baseHeight = baseHeight;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public RuntimeBehavior getBehavior() { return behavior; }
    /** 播放优先级：数值越大优先级越高，仅用于队列内排序（不可打断脚本永不被打断） */
    public int getPriority() { return priority; }
    /** 跨脚本播放关系（{@code meta.play_mode}，缺省 {@link PlayMode#PARALLEL}）；与同脚本再触发的 interruptible 分工不同 */
    public PlayMode getPlayMode() { return playMode; }
    public String getDimension() { return dimension; }
    public List<TriggerDefinition> getTriggers() { return triggers; }
    /** 跳过投票比例（10~100）；null = 未指定，运行时使用全局配置 Config.skipVoteRatio */
    public Integer getSkipVoteRatio() { return skipVoteRatio; }
    /** 脚本级轮询间隔（tick）；null = 未指定，运行时回落到类型级默认（Config 注册值） */
    public Integer getTriggerPollInterval() { return triggerPollInterval; }
    /** 编辑基准分辨率宽（像素）：分辨率转义系数 k 的分母（缺省 {@link #DEFAULT_BASE_WIDTH}） */
    public int getBaseWidth() { return baseWidth; }
    /** 编辑基准分辨率高（像素）：分辨率转义系数 k 的分母（缺省 {@link #DEFAULT_BASE_HEIGHT}） */
    public int getBaseHeight() { return baseHeight; }
    public boolean isBlockKeyboard() { return behavior.blockKeyboard(); }
    public boolean isBlockMouse() { return behavior.blockMouse(); }
    public boolean isHoldAtEnd() { return behavior.holdAtEnd(); }
    /** 宏观循环开关：整条时间轴（或 loop_start / loop_end 区间）从 a 到 b 重复执行。 */
    public boolean isMacroLoop() { return behavior.macroLoop(); }
    /** 宏观循环次数：-1 = 无限重复；正整数 N = 重复 N 圈后自然结束 */
    public int getMacroLoopCount() { return behavior.macroLoopCount(); }

    /**
     * 跨脚本播放关系三态（{@code meta.play_mode}）：本脚本在播期间与其它脚本的关系。
     * parallel 不阻塞新来者也不被顶掉；exclusive 让新来者排队；interruptible 可被新来者顶掉。
     * 与同脚本再触发的 {@code meta.interruptible} 分工不同：后者管同脚本替换。
     * 不进 {@link RuntimeBehavior}：behavior 参与 {@code recomputeUnion} 按位并集，会污染并集语义。
     */
    public enum PlayMode {
        PARALLEL("parallel"), EXCLUSIVE("exclusive"), INTERRUPTIBLE("interruptible");

        private static final PlayMode[] VALUES = values();

        /** JSON 取值字面量（脚本里写的 {@code parallel} / {@code exclusive} / {@code interruptible}）。 */
        private final String id;

        PlayMode(String id) { this.id = id; }

        /** JSON 取值字面量。 */
        public String id() { return id; }

        /** 取值域内 → 对应枚举；缺省 / 域外 → {@link #PARALLEL}（解析宽容：未知取值不让脚本失效）。 */
        public static PlayMode fromString(String value) {
            for (PlayMode mode : VALUES) {
                if (mode.id.equals(value)) return mode;
            }
            return PARALLEL;
        }

        /** {@code value} 是否在取值域内（解析期告警 / 校验器用）。 */
        public static boolean isKnown(String value) {
            for (PlayMode mode : VALUES) {
                if (mode.id.equals(value)) return true;
            }
            return false;
        }
    }

    @Override
    public String toString() {
        return String.format("ScriptMeta{id=%s, name=%s, author=%s}", id, name, author);
    }

    public record RuntimeBehavior(
            boolean blockKeyboard,
            boolean blockMouse,
            boolean blockMobAi,
            boolean pauseWhenGamePaused,
            boolean interruptible,
            boolean skippable,
            boolean holdAtEnd,
            boolean macroLoop,
            String macroLoopMode,
            int macroLoopCount,
            java.util.Map<String, Boolean> screenLayers
    ) {
        public static final RuntimeBehavior DEFAULT = new RuntimeBehavior(
                true, true, false,
                true, true, true, false,
                false, "repeat", -1,
                java.util.Collections.emptyMap()
        );

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private boolean blockKeyboard = DEFAULT.blockKeyboard();
            private boolean blockMouse = DEFAULT.blockMouse();
            private boolean blockMobAi = DEFAULT.blockMobAi();
            private boolean pauseWhenGamePaused = DEFAULT.pauseWhenGamePaused();
            private boolean interruptible = DEFAULT.interruptible();
            private boolean skippable = DEFAULT.skippable();
            private boolean holdAtEnd = DEFAULT.holdAtEnd();
            private boolean macroLoop = DEFAULT.macroLoop();
            private String macroLoopMode = DEFAULT.macroLoopMode();
            private int macroLoopCount = DEFAULT.macroLoopCount();
            private java.util.Map<String, Boolean> screenLayers = new java.util.LinkedHashMap<>();

            public Builder blockKeyboard(boolean v) { this.blockKeyboard = v; return this; }
            public Builder blockMouse(boolean v) { this.blockMouse = v; return this; }
            public Builder blockMobAi(boolean v) { this.blockMobAi = v; return this; }
            public Builder pauseWhenGamePaused(boolean v) { this.pauseWhenGamePaused = v; return this; }
            public Builder interruptible(boolean v) { this.interruptible = v; return this; }
            public Builder skippable(boolean v) { this.skippable = v; return this; }
            public Builder holdAtEnd(boolean v) { this.holdAtEnd = v; return this; }
            public Builder macroLoop(boolean v) { this.macroLoop = v; return this; }
            public Builder macroLoopMode(String v) { this.macroLoopMode = v; return this; }
            public Builder macroLoopCount(int v) { this.macroLoopCount = v; return this; }
            public Builder screenLayers(java.util.Map<String, Boolean> v) { this.screenLayers = v; return this; }

            public RuntimeBehavior build() {
                return new RuntimeBehavior(
                        blockKeyboard, blockMouse, blockMobAi,
                        pauseWhenGamePaused, interruptible, skippable, holdAtEnd,
                        macroLoop, macroLoopMode, macroLoopCount,
                        screenLayers
                );
            }
        }
    }
}
