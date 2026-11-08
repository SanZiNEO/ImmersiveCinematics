package com.immersivecinematics.immersive_cinematics.script.identity;

import com.immersivecinematics.immersive_cinematics.script.Clip;

/**
 * 按片段 id 解析片段：规则绑定 / 路径端点 / 分支目标 / 出口目标的统一引用入口。
 *
 * <p>实现方是 {@code CinematicScript}（{@code script::findClip}）：内联片段与独立片段共用同一个
 * id 命名空间。{@link #require(String, String)} 把「未知引用」统一成带宿主路径的加载期错误文案。</p>
 */
public interface ClipResolver {

    /** 解析片段 id；未知返回 {@code null}。 */
    Clip resolve(String id);

    /**
     * 解析片段 id，未知即抛异常（文案固定：{@code 未知片段引用: <id>（宿主 <hostPath>）}）。
     *
     * @param id       片段 id
     * @param hostPath 引用发生的 JSON / 结构路径（用于定位报错）
     * @return 命中的片段（永不为 {@code null}）
     * @throws IllegalArgumentException id 无法解析
     */
    default Clip require(String id, String hostPath) {
        Clip clip = resolve(id);
        if (clip == null) {
            throw new IllegalArgumentException("未知片段引用: " + id + "（宿主 " + hostPath + "）");
        }
        return clip;
    }
}
