package com.immersivecinematics.immersive_cinematics.script.template;

import com.google.gson.JsonObject;
import com.immersivecinematics.immersive_cinematics.script.TrackType;

import java.util.List;

/**
 * 片段级模板：一段镜头（一个 clip 的关键帧 / 轨迹）的参数化生成器。
 *
 * <p>三层模板模型的最底层（{@code plans/0.3.6/templates.md} §2）：输入参数 → 输出**标准 clip JSON**
 * （与手写 clip 同构，落在 {@link #trackType()} 对应的轨道上），产物走 {@code ScriptParser} /
 * {@code ScriptValidator} 校验，与手写完全等价、可继续编辑。</p>
 *
 * <p>展开规则是 Java 函数（{@link #expand(TemplateArgs)}），数学（贝塞尔拼弧、三角函数）写在实现里；
 * 参数声明复用 {@code script/schema} 的 {@link com.immersivecinematics.immersive_cinematics.script.schema.FieldDef}。</p>
 *
 * <p>轨道级 / 脚本级模板（引用多个片段模板拼装）不在这里——见 templates.md 步骤 3 / 4。</p>
 */
public interface ClipTemplate {

    /** 注册 id（命令 / WebUI 用；命名空间 = 片段级，暂不与轨道级 / 脚本级共享）。 */
    String id();

    /** 显示名。 */
    String name();

    /** 一句话说明（命令 {@code /icinematics template list} 与 WebUI 提示用）。 */
    String description();

    /** 产物 clip 应落在哪条轨道（接入点据此插入）。 */
    TrackType trackType();

    /** 参数声明；顺序即表单 / 帮助文本顺序。 */
    List<TemplateParam> params();

    /**
     * 展开为 clip JSON（含 {@code start_time} / {@code duration} / {@code keyframes}，以及 clip 级字段）。
     *
     * <p>{@code start_time} 取 {@link TemplateArgs#startTime()}；关键帧 {@code time} 相对本 clip 起点。
     * 参数值已在 {@link TemplateArgs} 里归一并兜过默认值，实现可直接读取。</p>
     */
    JsonObject expand(TemplateArgs args);
}
