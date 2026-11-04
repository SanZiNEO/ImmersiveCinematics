package com.immersivecinematics.immersive_cinematics.script.event;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.script.Keyframe;
import com.immersivecinematics.immersive_cinematics.script.object.ClipValueView;
import com.immersivecinematics.immersive_cinematics.script.object.SampledProperty;
import com.immersivecinematics.immersive_cinematics.spi.TrackTypeId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 单片段事件编译结果：离散记录 + 稳定身份 + 边沿游标。
 *
 * <p>事件不是采样属性：{@link #compile(Clip, Object)} 在加载/实例创建时把
 * {@code objects.event}（EVENT）或 {@code objects.mod_event}（MOD_EVENT）的记录读成数组，
 * 旧格式片段则直接读 {@code keyframes}（一个旧关键帧 = 一条事件，事件下标 = keyframe 下标）。
 * 超界事件（{@code time > duration}）在编译期丢弃。</p>
 *
 * <p>游标语义：{@link #advance(double)} / {@link #poll(double, Consumer)} 只触发
 * {@code time ∈ (上次推进到的局部时间, toLocalTime]} 的记录，首次从 {@code -inf} 开始，
 * 因此 {@code time=0} 的记录会在第一次推进时触发；每条记录最多触发一次。
 * {@link #reset(double)} 是 seek 入口：游标移到目标时间之后的下一条，目标时间及更早的事件不补触发。</p>
 *
 * <p>本类不做数值采样、不进入 {@link com.immersivecinematics.immersive_cinematics.script.object.SampledClip}；
 * 记录只读，游标/已触发集合可变，按播放实例每片段一份。</p>
 */
public final class EventClipRuntime {

    private static final String EVENT_PATH = "event";
    private static final String MOD_EVENT_PATH = "mod_event";
    private static final String COMMAND = "command";

    private final EventRecord[] records;
    private final EventKey[] keys;
    private final boolean[] fired;
    private int cursor;
    /** 上次推进到的片段局部时间；{@code -inf} = 尚未推进。 */
    private double scannedThrough = Double.NEGATIVE_INFINITY;

    private EventClipRuntime(EventRecord[] records, EventKey[] keys) {
        this.records = records;
        this.keys = keys;
        this.fired = new boolean[records.length];
    }

    /**
     * 编译片段的事件列表。
     *
     * <p>新格式从对象模型读（{@code event} / {@code mod_event}，按片段轨道类型选择）；
     * 旧格式从 {@code keyframes} 读，事件下标 = keyframe 下标。片段没有事件对象时返回空运行时。</p>
     *
     * @param clip          片段
     * @param instanceToken 播放实例令牌；{@code null} 时退回片段身份（仅探针/无实例场景）
     */
    public static EventClipRuntime compile(Clip clip, Object instanceToken) {
        Objects.requireNonNull(clip, "clip");
        Object token = instanceToken != null ? instanceToken : clip;
        boolean modEvent = TrackTypeId.MOD_EVENT.equals(clip.getTrackType());
        String path = modEvent ? MOD_EVENT_PATH : EVENT_PATH;

        List<EventRecord> records = new ArrayList<>();
        List<EventKey> keys = new ArrayList<>();

        if (clip.hasObjects()) {
            SampledProperty property = clip.getObjects().get(path);
            if (property != null) {
                ClipValueView values = new ClipValueView(clip);
                String defaultType = values.s("event_type", modEvent ? MOD_EVENT_PATH : COMMAND);
                Object defaultData = modEvent ? values.struct("data") : null;
                SampledProperty.Compiled compiled = property.compile(clip.getDuration(), clip.isInfinite());
                int count = compiled.eventCount();
                for (int i = 0; i < count; i++) {
                    EventRecord record = newRecord(compiled.timeAt(i), compiled.eventValue(i),
                            defaultType, defaultData);
                    if (record == null) continue;
                    records.add(record);
                    keys.add(new EventKey(token, clip, path, i));
                }
            }
        } else {
            List<Keyframe> keyframes = clip.getKeyframes();
            for (int i = 0; i < keyframes.size(); i++) {
                Keyframe keyframe = keyframes.get(i);
                float time = keyframe.getTime();
                if (!inRange(time, clip)) continue;
                EventRecord record = modEvent ? oldModEventRecord(keyframe) : oldEventRecord(keyframe);
                if (record == null) continue;
                records.add(record);
                keys.add(new EventKey(token, clip, path, i));
            }
        }

        return new EventClipRuntime(records.toArray(new EventRecord[0]), keys.toArray(new EventKey[0]));
    }

    /** 事件数量（已过滤超界记录）。 */
    public int size() {
        return records.length;
    }

    /** 是否没有事件。 */
    public boolean isEmpty() {
        return records.length == 0;
    }

    /** 第 {@code index} 条记录；下标与 {@link #key(int)} 一一对应。 */
    public EventRecord record(int index) {
        return records[index];
    }

    /** 第 {@code index} 条记录的身份。 */
    public EventKey key(int index) {
        return keys[index];
    }

    /** 记录数组（只读约定；调用方不得修改）。 */
    public EventRecord[] records() {
        return records;
    }

    /** 身份数组（只读约定；调用方不得修改）。 */
    public EventKey[] keys() {
        return keys;
    }

    /** 下一条待推进记录的下标；等于 {@link #size()} 表示已到末尾。 */
    public int cursor() {
        return cursor;
    }

    /** 上次推进到的片段局部时间；{@code -inf} = 尚未推进。 */
    public double scannedThrough() {
        return scannedThrough;
    }

    /** 第 {@code index} 条记录是否已触发。 */
    public boolean fired(int index) {
        return index >= 0 && index < fired.length && fired[index];
    }

    /**
     * 边沿推进：触发 {@code time ∈ (上次推进时间, toLocalTime]} 的记录。
     *
     * @param toLocalTime 目标片段局部时间
     * @return 推进后 {@link #cursor()}（区间终点的下标，不含）
     */
    public int advance(double toLocalTime) {
        if (!(toLocalTime > scannedThrough)) {
            return cursor;
        }
        while (cursor < records.length && records[cursor].time() <= toLocalTime) {
            fired[cursor] = true;
            cursor++;
        }
        scannedThrough = toLocalTime;
        return cursor;
    }

    /**
     * 边沿推进并逐条交给 {@code sink}（无列表分配）。
     *
     * @return 本次触发的记录数
     */
    public int poll(double toLocalTime, Consumer<EventRecord> sink) {
        int start = cursor;
        int end = advance(toLocalTime);
        if (sink != null) {
            for (int i = start; i < end; i++) {
                sink.accept(records[i]);
            }
        }
        return end - start;
    }

    /**
     * seek：把游标重置到 {@code localTime} 之后的下一条记录，清空已触发标记。
     * {@code time <= localTime} 的记录不会在后续推进中触发。
     */
    public void reset(double localTime) {
        double target = Double.isFinite(localTime) ? localTime : Double.NEGATIVE_INFINITY;
        Arrays.fill(fired, false);
        cursor = 0;
        while (cursor < records.length && records[cursor].time() <= target) {
            cursor++;
        }
        scannedThrough = target;
    }

    /** 超界过滤：有限片段只保留 {@code 0 <= time <= duration}，无限片段只要求 {@code time >= 0}。 */
    private static boolean inRange(float time, Clip clip) {
        if (!(time >= 0f) || !Float.isFinite(time)) {
            return false;
        }
        return clip.isInfinite() || time <= clip.getDuration();
    }

    /** 新格式事件记录：{@code command}/{@code event_type}/{@code position}/{@code data}。 */
    private static EventRecord newRecord(float time, Object raw, String defaultType, Object defaultData) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String eventType = map.get("event_type") instanceof String type && !type.isEmpty()
                ? type : defaultType;
        String command = map.get("command") instanceof String text ? text : null;
        EventRecord.Position position = positionOf(map.get("position"));
        Object data = map.containsKey("data") ? map.get("data") : defaultData;
        return new EventRecord(time, eventType, command, position, data);
    }

    /** 旧格式 EVENT 关键帧：{@code command}/{@code event_type}/{@code position}。 */
    private static EventRecord oldEventRecord(Keyframe keyframe) {
        String eventType = keyframe.getString("event_type", COMMAND);
        String command = keyframe.getString("command", null);
        EventRecord.Position position = positionOf(keyframe.getObject("position"));
        return new EventRecord(keyframe.getTime(), eventType, command, position, null);
    }

    /** 旧格式 MOD_EVENT 关键帧：{@code event_type}/{@code data}。 */
    private static EventRecord oldModEventRecord(Keyframe keyframe) {
        String eventType = keyframe.getString("event_type", MOD_EVENT_PATH);
        Object data = keyframe.getObject("data");
        return new EventRecord(keyframe.getTime(), eventType, null, null, data);
    }

    /** {@code {x,z}} = 绝对，{@code {dx,dz}} = 相对；不成对返回 {@code null}。 */
    private static EventRecord.Position positionOf(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Number x = number(map.get("x"));
        Number z = number(map.get("z"));
        if (x != null && z != null) {
            return new EventRecord.Position(x.doubleValue(), z.doubleValue(), false);
        }
        Number dx = number(map.get("dx"));
        Number dz = number(map.get("dz"));
        if (dx != null && dz != null) {
            return new EventRecord.Position(dx.doubleValue(), dz.doubleValue(), true);
        }
        return null;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }
}
