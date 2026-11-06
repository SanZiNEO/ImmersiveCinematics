# F5 光影每视角（per-pipeline）隔离

> 状态：未开始（K 轮）
> 来源：F 轮 **WP5**——`F-playback/cross-dimension-camera.md` §6.2（WP5 行）、§八（实现状态）；`F-playback/iris-oculus-compat.md` §7 / §4.5；`F-playback/multi-camera-rendering.md` §12.10。
> 归属：K 轮（用户 2026-11-06 决定：放到优化轮，编辑器前收尾）。

## 一、现状（已有什么）

- **WP1–WP4 已实现**：相机采样带维度；每个 lane 用自己的 `DimensionView`（level / renderer / lightmap / 剔除）；服务端按 `(player, viewId)` 供区块、实体与刷怪中心。
- **F3 已实机验证**（2026-10-18）：Iris 1.7.6 三套光影包 A/B、热切换、双视图；per-view TAA 历史（自动探测历史目标）、`shadowcolor` 按视角清除、深度重绑、切换光影包时重置缓存。
- **F8 已实机验证**（2026-10-26）：三画面、光影开启、双视图 active，无 `route-fail` / `oversize`。
- 因此**主路径已验证**；WP5 是「更极端组合下每个 view 的 pipeline 状态是否仍然完全隔离」的技术收口项。

## 二、要做什么

1. **per-view pipeline 状态清单**：把 Iris/Oculus 每个 view 会共享的可变状态列全（历史缓冲 / TAA / 阴影缓冲与 `shadowcolor` / 深度纹理绑定 / 投影矩阵缓存 / pipeline 选择键），逐项标注「已隔离 / 共享 / 未知」。
2. **未知项收口**：对「共享 / 未知」项给出隔离方案（按 view key 分桶，或每 view 重建），并说明代价（显存 / 重建次数）。
3. **组合矩阵**：同维度多 lane、跨维度双视图、三画面、光影包热切换、`FABULOUS`、Oculus / Embeddium 各跑一遍。
4. **性能对照**：沿用 F5 诊断矩阵口径（per lane tier 600 帧），给出隔离前后的 CPU 提交区间与帧时；不退化才算完成。

## 三、验收

- Iris / Oculus 开启时，两个维度各自画面正确，**无历史串色 / 无阴影串色 / 无深度错绑**。
- 三套光影包 A/B 一致；热切换后不需要重启客户端。
- 组合矩阵逐格有记录（通过 / 已知限制）。
- 性能：F5 矩阵同口径不退化（给出数字，不接受「感觉没变慢」）。

## 四、非目标

- 不改脚本字段、不改相机/过渡语义、不改 lane 合成顺序。
- 不为光影单独引入第二套渲染路径：仍走「每 lane 独立相机 → 共用离屏 FBO → 上屏」。
- 不承诺所有第三方光影包：只对已验证的三套 + 已知限制做记录。
