package com.immersivecinematics.immersive_cinematics.selector;

/**
 * 选择器策略（调用点级）：扫描与切换两个指标相互独立——扫描决定重扫候选的频率，切换决定扫到新目标后
 * 是否真的换过去。
 *
 * 时间单位一律秒。缺省：扫描 1 秒、允许存活期切换、切换间隔 = 扫描间隔、无平滑（立即切换）；扫描间隔
 * 下限 0.05 秒，切换间隔与平滑不为负。字段定义与回落链见 {@code TrackSchemas.camera()}。
 *
 * @param scanSeconds      扫描间隔：多久重新扫一遍候选（影响准确性）
 * @param switchWhileAlive 目标存活时是否允许切换
 * @param switchSeconds    切换间隔：扫到不同目标后至少等这么久才真的换过去
 * @param switchSmooth     切换过渡时长（影响稳定性）；{@code 0} = 立即切换
 */
public record SelectorPolicy(
        float scanSeconds, boolean switchWhileAlive, float switchSeconds, float switchSmooth) {}
