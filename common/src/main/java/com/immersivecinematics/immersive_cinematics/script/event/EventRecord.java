package com.immersivecinematics.immersive_cinematics.script.event;

/**
 * 一条离散事件记录（编译后只读）。
 *
 * <p>EVENT 记录使用 {@link #eventType} / {@link #command} / {@link #position}；
 * MOD_EVENT 记录使用 {@link #eventType} / {@link #data}。两类记录共用同一容器与游标。</p>
 *
 * @param time      片段局部时间（秒）
 * @param eventType 事件类型；EVENT 只接受 {@code command}，MOD_EVENT 必填（缺省取 value-only 默认值）
 * @param command   命令；{@code null} 或空串 = 占位（标记已触发但不执行）
 * @param position  玩家移动目标；{@code null} = 该记录不参与移动
 * @param data      MOD_EVENT 的任意数据；EVENT 为 {@code null}
 */
public record EventRecord(double time, String eventType, String command, Position position, Object data) {

    /**
     * 玩家移动目标：{@code relative=false} 时 {@code x/z} 是世界绝对坐标；
     * {@code relative=true} 时是相对激活位置（触发点）的 {@code dx/dz}。
     */
    public record Position(double x, double z, boolean relative) {
    }

    /** 是否有可执行命令（空串是占位，不算）。 */
    public boolean hasCommand() {
        return command != null && !command.isEmpty();
    }

    /** 是否携带玩家移动目标。 */
    public boolean hasPosition() {
        return position != null;
    }
}
