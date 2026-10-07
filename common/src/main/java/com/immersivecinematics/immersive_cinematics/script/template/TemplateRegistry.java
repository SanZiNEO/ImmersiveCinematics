package com.immersivecinematics.immersive_cinematics.script.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 片段模板注册表（内置库）。
 *
 * <p>命名空间 = 片段级模板 id（轨道级 / 脚本级模板注册表见 templates.md 步骤 3 / 4，暂不共用 id 空间）。
 * 初版只做内置模板：生成逻辑是 Java 代码，注册在静态初始化里。</p>
 */
public final class TemplateRegistry {

    private static final Map<String, ClipTemplate> TEMPLATES = new LinkedHashMap<>();

    static {
        register(new FadeClipTemplate());
        register(new StaticBreathClipTemplate());
        register(new DollyClipTemplate());
        register(new OrbitArcClipTemplate());
    }

    private TemplateRegistry() {}

    /** 注册一个模板；id 重复直接报错（内置库不该出现静默覆盖）。 */
    public static void register(ClipTemplate template) {
        if (template == null) throw new IllegalArgumentException("模板不能为空");
        ClipTemplate previous = TEMPLATES.putIfAbsent(template.id(), template);
        if (previous != null) {
            throw new IllegalStateException("模板 id 重复: " + template.id());
        }
    }

    /** 按 id 取模板；不存在返回 null。 */
    public static ClipTemplate get(String id) {
        return TEMPLATES.get(id);
    }

    /** 全部模板（注册顺序）。 */
    public static List<ClipTemplate> all() {
        return Collections.unmodifiableList(new ArrayList<>(TEMPLATES.values()));
    }

    /** 全部模板 id（注册顺序），用于命令 / 表单补全。 */
    public static List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(TEMPLATES.keySet()));
    }

    /** 该 key 是否为模板已声明的参数（命令用来把保留 key 与模板参数区分开）。 */
    public static boolean isDeclaredParam(ClipTemplate template, String key) {
        for (TemplateParam p : template.params()) {
            if (p.key().equals(key)) return true;
        }
        return false;
    }
}
