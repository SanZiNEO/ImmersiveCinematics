package com.immersivecinematics.immersive_cinematics.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第三方轨道注册用的对象声明表：路径 → {@link ObjectDef}，保持声明顺序。
 *
 * <p>只描述新对象模型（{@code objects}），不含旧链路的 clip / keyframe / playControl 字段表；
 * 内置 7 轨的字段表仍由 {@code TrackSchemas} 提供，本类不参与。</p>
 *
 * <p>{@link Builder#build()} 做硬校验（不满足抛 {@link IllegalArgumentException}）：</p>
 * <ol>
 *   <li>至少一个对象声明；</li>
 *   <li>路径不重复；</li>
 *   <li>至少一个数值对象（{@code type} 为 {@link PropertyType#FLOAT} / {@link PropertyType#INT} 且
 *       {@code mode} 为 {@link PropertyMode#VALUE} / {@link PropertyMode#ANIMATED}）——
 *       轨道至少要有一个可采样的数值量，否则运行时没有任何可驱动的内容。</li>
 * </ol>
 */
public final class TrackSchema {

    private final Map<String, ObjectDef> objects;

    private TrackSchema(Map<String, ObjectDef> objects) {
        this.objects = objects;
    }

    /** 开始构造一条轨道声明。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 路径 → 声明（不可变，迭代顺序 = 声明顺序 = 编辑器渲染顺序）。
     *
     * @return 只读映射
     */
    public Map<String, ObjectDef> objects() {
        return objects;
    }

    /** 声明构造器；{@link #build()} 之后实例不可变。 */
    public static final class Builder {

        private final List<ObjectDef> defs = new ArrayList<>();

        private Builder() {}

        /**
         * 声明一个可关键帧的连续量（{@code mode=animated}）。
         *
         * @param path         点分路径
         * @param type         值类型
         * @param interp       插值方式；{@code null} = 按 {@link Interp#infer} 推导（{@code float} → linear /
         *                     angle / geometric，{@code int} 等 → step）
         * @param defaultValue 缺省值；{@code null} = 无缺省
         * @return 本构造器
         */
        public Builder animatable(String path, PropertyType type, Interp interp, Object defaultValue) {
            return object(ObjectDef.builder(path, PropertyMode.ANIMATED, type)
                    .interp(interp != null ? interp : Interp.infer(type, path, null))
                    .def(defaultValue)
                    .build());
        }

        /**
         * 声明一个只有静态值的字段（{@code mode=value}）。
         *
         * @param path         点分路径
         * @param type         值类型
         * @param defaultValue 缺省值；{@code null} = 无缺省
         * @return 本构造器
         */
        public Builder value(String path, PropertyType type, Object defaultValue) {
            return object(ObjectDef.builder(path, PropertyMode.VALUE, type)
                    .def(defaultValue)
                    .build());
        }

        /**
         * 声明一个离散事件记录字段（{@code mode=event}，{@code type=event_record}）。
         *
         * @param path 点分路径
         * @return 本构造器
         */
        public Builder event(String path) {
            return object(ObjectDef.builder(path, PropertyMode.EVENT, PropertyType.EVENT_RECORD).build());
        }

        /**
         * 直接声明一条完整的对象定义（需要 requires / 范围 / 分组等额外属性时用）。
         *
         * @param def 对象声明
         * @return 本构造器
         */
        public Builder object(ObjectDef def) {
            if (def == null) {
                throw new IllegalArgumentException("对象声明不能为 null");
            }
            defs.add(def);
            return this;
        }

        /**
         * 冻结并校验声明表。
         *
         * @return 不可变声明表
         * @throws IllegalArgumentException 无对象 / 路径重复 / 没有数值对象
         */
        public TrackSchema build() {
            if (defs.isEmpty()) {
                throw new IllegalArgumentException("轨道 schema 至少需要一个对象声明");
            }
            Map<String, ObjectDef> objects = new LinkedHashMap<>();
            for (ObjectDef def : defs) {
                ObjectDef previous = objects.putIfAbsent(def.path(), def);
                if (previous != null) {
                    throw new IllegalArgumentException("轨道 schema 对象路径重复: " + def.path());
                }
            }
            boolean numeric = false;
            for (ObjectDef def : objects.values()) {
                boolean numericType = def.type() == PropertyType.FLOAT || def.type() == PropertyType.INT;
                boolean valueMode = def.mode() == PropertyMode.VALUE || def.mode() == PropertyMode.ANIMATED;
                if (numericType && valueMode) {
                    numeric = true;
                    break;
                }
            }
            if (!numeric) {
                throw new IllegalArgumentException("轨道 schema 至少需要一个数值对象（type=float/int 且 mode=value/animated）");
            }
            return new TrackSchema(Collections.unmodifiableMap(objects));
        }
    }
}
