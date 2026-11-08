package com.immersivecinematics.immersive_cinematics.script.object;

import com.immersivecinematics.immersive_cinematics.script.Clip;
import com.immersivecinematics.immersive_cinematics.spi.PropertyMode;
import com.immersivecinematics.immersive_cinematics.spi.PropertyType;

import java.util.Objects;

/**
 * 单个片段的 value-only 字段读取视图：包装片段的 {@link ClipObjects}，只回答「作者写了什么」。
 *
 * <p>只读 {@link PropertyMode#VALUE} 属性；遇到 {@code animated}/{@code event} 属性时按「未提供」处理，
 * 回落调用方给的 {@code fallback}（{@code struct} 返回 {@code null}）——value 视图不在运行时采样
 * 动画属性，动画量走 {@link ClipSampler}/{@link SampledClip}。</p>
 *
 * <p>{@link #declared(String)} 只看作者是否显式写了该路径（{@link ClipObjects#contains(String)}），
 * 不把 schema 缺省值算作声明；因此「未声明」与「声明为缺省值」可区分。构造期不预解析属性：
 * 空 {@code objects}（程序化构造）不产生任何无意义工作，方法内的 {@code Map} 查找就是全部开销。</p>
 *
 * <p>非线程安全；只在客户端主线程使用（与 {@link ClipRuntimeCache} 同一约束）。</p>
 */
public final class ClipValueView {

    private final ClipObjects objects;

    /**
     * @param clip 片段；{@code null} 不接受
     */
    public ClipValueView(Clip clip) {
        this.objects = Objects.requireNonNull(clip, "clip").getObjects();
    }

    /** 作者是否显式声明了该路径（不看 schema 缺省值）。 */
    public boolean declared(String path) {
        return objects.contains(path);
    }

    /**
     * 读取 value-only 浮点字段。
     *
     * <p>{@link Number} 统一取 {@code floatValue()}；非有限结果（{@code NaN}/{@code ±Infinity}）回落
     * {@code fallback}，与 {@link Clip#getFloat(String, float)} 同口径。路径未声明、属性非
     * {@code value} 形态或值不是数字时同样回落。</p>
     */
    public float f(String path, float fallback) {
        SampledProperty property = valueProperty(path);
        if (property == null) {
            return fallback;
        }
        Object value = property.value();
        if (!(value instanceof Number number)) {
            return fallback;
        }
        float result = number.floatValue();
        return Float.isFinite(result) ? result : fallback;
    }

    /**
     * 读取 value-only 整数字段。
     *
     * <p>{@link Number} 统一取 {@code intValue()}；非有限结果回落 {@code fallback}。路径未声明、属性非
     * {@code value} 形态或值不是数字时同样回落。</p>
     */
    public int i(String path, int fallback) {
        SampledProperty property = valueProperty(path);
        if (property == null) {
            return fallback;
        }
        Object value = property.value();
        if (!(value instanceof Number number)) {
            return fallback;
        }
        double result = number.doubleValue();
        return Double.isFinite(result) ? number.intValue() : fallback;
    }

    /** 读取 value-only 布尔字段；只接受 {@link Boolean}，其余回落 {@code fallback}。 */
    public boolean b(String path, boolean fallback) {
        SampledProperty property = valueProperty(path);
        if (property == null) {
            return fallback;
        }
        Object value = property.value();
        return value instanceof Boolean bool ? bool : fallback;
    }

    /** 读取 value-only 字符串字段；只接受 {@link String}，其余回落 {@code fallback}。 */
    public String s(String path, String fallback) {
        SampledProperty property = valueProperty(path);
        if (property == null) {
            return fallback;
        }
        Object value = property.value();
        return value instanceof String string ? string : fallback;
    }

    /**
     * 读取任意 value-only 属性的静态值（不限类型）。
     *
     * <p>路径未声明或属性非 {@code value} 形态返回 {@code null}；与 {@link #struct(String)} 的区别是
     * 不限定 {@link PropertyType#STRUCT}。</p>
     */
    public Object o(String path) {
        SampledProperty property = valueProperty(path);
        return property != null ? property.value() : null;
    }

    /**
     * 读取 value-only {@link PropertyType#STRUCT} 的原始值。
     *
     * <p>返回 parser 已冻结的结构：不可变 {@code Map}/{@code List}/{@code String}/{@code Boolean}/
     * {@code Float}/{@code null}。路径未声明、属性非 {@code value} 形态或类型不是 {@code struct} 时返回
     * {@code null}。</p>
     */
    public Object struct(String path) {
        SampledProperty property = valueProperty(path);
        if (property == null || property.type() != PropertyType.STRUCT) {
            return null;
        }
        return property.value();
    }

    /** 被包装的对象集合（只读）。 */
    public ClipObjects objects() {
        return objects;
    }

    /** 该路径的 value-only 属性；未声明或非 {@code value} 形态返回 {@code null}。 */
    private SampledProperty valueProperty(String path) {
        SampledProperty property = objects.get(path);
        return property != null && property.mode() == PropertyMode.VALUE ? property : null;
    }
}
