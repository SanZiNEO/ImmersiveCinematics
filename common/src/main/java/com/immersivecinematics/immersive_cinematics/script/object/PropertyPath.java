package com.immersivecinematics.immersive_cinematics.script.object;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 采样属性的点分路径（如 {@code position.x}、{@code yaw}、{@code event}）。
 *
 * 段规则：{@code .} 分隔，每段匹配 {@code [a-z][a-z0-9_]*}；无空段、无首尾点、无空格、无数组下标。
 * 路径是稳定标识：schema、脚本、迁移器、编辑器使用同一字符串；运行时编译成整数槽位，不逐帧查表。
 * 路径拼写错误一律在加载期暴露，不做别名、不做模糊匹配。
 *
 * @param name 完整点分路径
 * @param segments 已拆分且逐段校验的路径段；不可变
 */
public record PropertyPath(String name, List<String> segments) {

    public PropertyPath {
        Objects.requireNonNull(name, "name");
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("属性路径不能为空: " + name);
        }
        for (String segment : segments) {
            if (!isSegment(segment)) {
                throw new IllegalArgumentException("非法属性路径: " + name);
            }
        }
        segments = List.copyOf(segments);
        if (!name.equals(String.join(".", segments))) {
            throw new IllegalArgumentException("属性路径与段不一致: " + name);
        }
    }

    /**
     * 解析点分路径；非法输入抛 {@link IllegalArgumentException}。
     *
     * @param text 待解析路径
     * @return 解析后的路径
     */
    public static PropertyPath parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("非法属性路径: " + text);
        }
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (true) {
            int dot = text.indexOf('.', start);
            int end = dot < 0 ? text.length() : dot;
            if (!isSegment(text, start, end)) {
                throw new IllegalArgumentException("非法属性路径: " + text);
            }
            segments.add(text.substring(start, end));
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        return new PropertyPath(text, segments);
    }

    /**
     * 轻量合法性判断；不抛异常、不分配（成功路径唯一分配为返回的 boolean）。
     *
     * @param text 待检查路径
     * @return 是否为合法点分路径
     */
    public static boolean isValid(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int start = 0;
        while (true) {
            int dot = text.indexOf('.', start);
            int end = dot < 0 ? text.length() : dot;
            if (!isSegment(text, start, end)) {
                return false;
            }
            if (dot < 0) {
                return true;
            }
            start = dot + 1;
        }
    }

    private static boolean isSegment(String text, int start, int end) {
        if (start >= end) {
            return false;
        }
        char first = text.charAt(start);
        if (first < 'a' || first > 'z') {
            return false;
        }
        for (int i = start + 1; i < end; i++) {
            char c = text.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isSegment(String segment) {
        return segment != null && isSegment(segment, 0, segment.length());
    }
}
