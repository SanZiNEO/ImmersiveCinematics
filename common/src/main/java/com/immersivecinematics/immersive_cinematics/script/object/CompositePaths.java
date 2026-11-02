package com.immersivecinematics.immersive_cinematics.script.object;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧复合 map → 叶子路径段表。
 *
 * <p>只用于 schema 完整性探针与后续迁移，不参与运行时：运行时只认扁平叶子路径。
 * 表是精确契约，顺序稳定，不可修改。{@code space} 是显式的 step 叶子（{@code world}/{@code base}）。</p>
 */
public final class CompositePaths {

    /** 复合名 → 叶子段（不含复合前缀）；不可变、保持声明顺序。 */
    public static final Map<String, List<String>> ALL;

    static {
        Map<String, List<String>> table = new LinkedHashMap<>();
        table.put("position", List.of("x", "y", "z"));
        table.put("dest", List.of("x", "y", "w", "h"));
        table.put("source", List.of("x", "y", "w", "h"));
        table.put("color", List.of("r", "g", "b"));
        table.put("look_at_part", List.of("x", "y", "z"));
        table.put("look_at_offset", List.of("x", "y", "z", "space"));
        table.put("facing_target_offset", List.of("x", "y", "z", "space"));
        table.put("yaw_base_from_offset", List.of("x", "y", "z", "space"));
        table.put("yaw_base_to_offset", List.of("x", "y", "z", "space"));
        ALL = Collections.unmodifiableMap(table);
    }

    private CompositePaths() {
    }

    /**
     * 取复合名的叶子段。
     *
     * @param composite 旧复合名（{@code position} / {@code dest} / …）
     * @return 叶子段列表；未知复合名返回 {@code null}（由调用方决定报错方式）
     */
    public static List<String> leavesOf(String composite) {
        return composite == null ? null : ALL.get(composite);
    }
}
