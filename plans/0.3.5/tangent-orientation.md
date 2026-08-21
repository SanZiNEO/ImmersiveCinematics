# 0.3.5 切线朝向（Tangent Orientation）方案

**状态**: ✅ 已实现（待测试与编辑器微调）
**最后更新**: 2026-08-21

> 目标：让相机可以“自动朝路径前进方向看”，并支持水平/竖直偏移角。
> 这是 0.2.0 “一直朝飞行方向 + 水平控制角度”的现代重做，但放在当前解耦的
> “路径 + 朝向”模型上，实现成本低。

---

## 1. 现状

当前朝向只有两种：

| 模式 | 说明 |
|---|---|
| 手写 | 关键帧直接写 `yaw / pitch / roll` |
| look_at | 关键帧写 `look_at`，相机看向目标点/实体 |

路径只负责“相机在哪”，不参与“相机看哪”。

---

## 2. 目标

新增第三种朝向模式：

```json
"orient": "tangent"
```

- 相机自动沿路径切线方向看
- 支持水平偏移：`yaw_offset`
- 支持竖直偏移：`pitch_offset`
- `roll` 仍然手写，不受影响

效果：
- 飞越、过山车、沿路巡游时，相机自然顺着路径方向
- 可以偏左/偏右看，也可以抬头/低头看

---

## 3. 数学基础

路径 `P(u)`，`u∈[0,1]`：

切线方向：
\[
T(u) = \frac{P'(u)}{\lVert P'(u) \rVert}
\]

- 直线：`P'(u) = P_1 - P_0`
- 三次贝塞尔：
  \[
  B'(u) = 3(1-u)^2(P_1-P_0) + 6(1-u)u(P_2-P_1) + 3u^2(P_3-P_2)
  \]

我们已有 `ArcLengthLUT`，能由弧长进度 `s` 反解出 `u`，再代入导数即可。

最终朝向：

```text
yaw   = 切线水平角 + yaw_offset
pitch = 切线俯仰角 + pitch_offset
roll  = 关键帧手写 roll
```

其中：
```text
切线水平角 = atan2(dx, dz)
切线俯仰角 = atan2(dy, sqrt(dx*dx + dz*dz))
```

---

## 4. 脚本参数（已定：片段级）

```json
{
  "type": "CAMERA",
  "clips": [{
    "start_time": 0,
    "duration": 5,
    "orient": "tangent",
    "yaw_offset": 30,
    "pitch_offset": -10,
    "keyframes": [...]
  }]
}
```

- `orient`：片段级，`manual` / `tangent`
- `yaw_offset / pitch_offset`：片段级，仅 `tangent` 生效
- `look_at`：关键帧级，保持现状

---

## 5. 优先级规则（已定）

```text
1. 段内有 look_at（任一端写了） → 用 look_at
2. 否则片段 orient = tangent   → 沿路径切线 + offset
3. 否则                         → 手写 yaw/pitch 插值
```

- `orient` 只保留 `manual` / `tangent`
- `look_at` 由关键帧控制，优先于 tangent
- `tangent` 只对非 follow 的普通路径有效；follow 动态实体时回退手写或 look_at

---

## 6. 实现点

1. `PathStrategy` 增加方法：
   ```java
   Vec3 tangent(Vec3 from, Vec3 to, float s, BezierCurve curve);
   ```
   - `LinearPathStrategy`：`to - from` 归一化
   - `BezierPathStrategy`：用 `ArcLengthLUT.lookupT(s)` 得到 `u`，再求 `B'(u)` 归一化
2. `CameraTrackPlayer`：
   - 根据 `orient` 选择朝向来源
   - `tangent` 模式下调用 `tangent()`，再转成 yaw/pitch，叠加 offset
   - roll 仍走现有 `interpolateRoll`
3. `ScriptParser` / Schema：
   - 新字段 `orient`（enum：manual/look_at/tangent）
   - 新字段 `yaw_offset` / `pitch_offset`（float，默认 0）
4. 校验：
   - `orient` 非法值报错
   - `tangent` 与 `look_at` 同时出现时，以 `orient` 为准（或校验冲突）

---

## 7. 已定/待确认

已定：
- 粒度：`orient` / offset 为片段级；`look_at` 为关键帧级
- 优先级：look_at > tangent > manual
- `roll` 在 tangent 下不自动压弯，仍手写

待确认/后续：
1. morph 过渡：前一段 look_at、后一段 tangent，过渡时朝向怎么混合？
   - 简单方案：过渡期间按现有角度插值混合
   - 复杂方案：每帧分别算两种朝向再 blend
2. 编辑器 UI：目前字段已接入，后续统一优化为切换按钮/面板

---

## 8. 范围建议

0.3.5 如果做，建议最小范围：

- 只支持 CAMERA 轨道
- 只支持普通路径（非 follow）
- 只加 `orient` / `yaw_offset` / `pitch_offset` 三个字段
- 不做编辑器 UI
- 不做自动 roll/bank

---

## 9. 待办

- [ ] 定粒度（clip / keyframe / 默认+覆盖）
- [ ] PathStrategy 加 tangent
- [ ] CameraTrackPlayer 接入
- [ ] Schema / Parser / Validator 更新
- [ ] 文档更新（SCRIPT_FORMAT / AI_SCRIPTING_GUIDE）
- [ ] 测试脚本：直线 tangent、贝塞尔 tangent、offset 组合、morph
