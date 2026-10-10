package com.immersivecinematics.immersive_cinematics.selector;

/**
 * 选择器策略（调用点级，时间单位秒）：扫描与切换两个指标相互独立——扫描决定"多久重扫一遍候选"，
 * 切换决定"扫到新目标后是否真的换过去"。
 *
 * @param scanSeconds      扫描间隔：多久重新扫一遍候选（影响准确性）
 * @param switchWhileAlive 目标存活时是否允许切换
 * @param switchSeconds    切换间隔：扫到新目标后，也要等这么久才真的换过去（影响稳定性）
 * @param switchSmooth     切换平滑：真的换过去时，用多少秒过渡
 */
public record SelectorPolicy(
        float scanSeconds, boolean switchWhileAlive, float switchSeconds, float switchSmooth) {}
