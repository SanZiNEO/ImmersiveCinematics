package com.immersivecinematics.immersive_cinematics.script.execution;

import com.immersivecinematics.immersive_cinematics.script.Clip;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 显式执行依赖的静态诊断。
 *
 * <p>只对调用方声明的依赖边做判定，不推断时间轴顺序：同轨后续片段是否可达属于编排选择，
 * 不在此处判错。{@link DependencyKind#WAIT_START} 只要求前置片段开始，无限前置天然可达；
 * {@link DependencyKind#WAIT_COMPLETION} 要求前置片段自然完成，无限片段没有自然完成点。</p>
 */
public final class ExecutionDiagnostics {

    private ExecutionDiagnostics() {
    }

    /**
     * 检查依赖边。
     *
     * @param deps  显式依赖；{@code null} / 空列表返回空结果
     * @param label 片段显示名（{@code null} 时回落到 {@link Clip#getClipId()}）
     * @return 诊断文案；无问题时为空列表
     */
    public static List<String> check(List<ExecutionDependency> deps, Function<Clip, String> label) {
        if (deps == null || deps.isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        for (ExecutionDependency dep : deps) {
            if (dep == null) {
                issues.add("依赖项为空（缺少 dependent / prerequisite）");
                continue;
            }
            Clip dependent = dep.dependent();
            Clip prerequisite = dep.prerequisite();
            if (dependent == null) {
                issues.add("依赖项缺少 dependent 片段");
            }
            if (prerequisite == null) {
                issues.add("依赖项缺少 prerequisite 片段");
            }
            if (dependent == null || prerequisite == null) {
                continue;
            }
            if (dep.kind() != DependencyKind.WAIT_COMPLETION) {
                continue;
            }
            String dependentLabel = label(dependent, label);
            if (dependent == prerequisite) {
                issues.add("自依赖：" + dependentLabel + " 等待自身完成（无自然完成点）");
            } else if (prerequisite.isEffectivelyInfinite()) {
                issues.add("不可达依赖：" + dependentLabel + " 等待无限片段 "
                        + label(prerequisite, label) + " 完成（无自然完成点）");
            }
        }
        return issues;
    }

    private static String label(Clip clip, Function<Clip, String> label) {
        String text = label == null ? null : label.apply(clip);
        return text == null || text.isEmpty() ? clip.getClipId() : text;
    }
}
