package com.immersivecinematics.immersive_cinematics.editor.fields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 字段折叠组（0.3.5 第5轮 5A/UI）。
 * <p>
 * 一个折叠组 = 标题 + 默认展开状态 + 字段列表。
 * 属性面板按组渲染，避免把所有字段一次性平铺。
 */
public class FieldGroup {

    private final String titleKey;
    private final boolean defaultExpanded;
    private final List<String> keys;

    public FieldGroup(String titleKey, boolean defaultExpanded, List<String> keys) {
        this.titleKey = titleKey;
        this.defaultExpanded = defaultExpanded;
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
    }

    public String getTitleKey() {
        return titleKey;
    }

    public boolean isDefaultExpanded() {
        return defaultExpanded;
    }

    public List<String> getKeys() {
        return keys;
    }
}
