package com.immersivecinematics.immersive_cinematics.script;

/**
 * 事件片段 — 游戏内命令事件（服务端执行）
 * <p>
 * event 轨道统一为 "command" 类型，由服务端在脚本播放期间按时间戳执行命令。
 * 原 time_lock/weather/particle 等专用类型全部用命令替代：
 * <ul>
 *   <li>时间锁定 → /time set 6000</li>
 *   <li>天气覆盖 → /weather rain</li>
 *   <li>粒子效果 → /particle minecraft:explosion ...</li>
 * </ul>
 * 客户端不处理 event 轨道，命令执行结果通过原版同步包自动到达客户端。
 */
public class EventClip {

    /** 总时间轴上的开始时间（秒） */
    private final float startTime;

    /** 持续时长（秒），瞬发事件 = 0 */
    private final float duration;

    /** 事件类型，当前仅支持 "command" */
    private final String eventType;

    /** 要执行的游戏命令文本 */
    private final String command;

    public EventClip(float startTime, float duration, String eventType, String command) {
        this.startTime = startTime;
        this.duration = duration;
        this.eventType = eventType;
        this.command = command;
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public String getEventType() { return eventType; }
    public String getCommand() { return command; }

    /** 是否为瞬发事件（duration=0） */
    public boolean isInstant() { return duration == 0f; }

    @Override
    public String toString() {
        return String.format("EventClip{start=%.2f, dur=%.2f, type=%s, cmd=%s}",
                startTime, duration, eventType, command);
    }
}
