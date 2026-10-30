# 规则与曲线：实现基线与差距

> 状态：记录文档（不定义语义）
> 范围：v1 实现基线、当前实现速查、与目标的差距、结构评审项汇总
> 关联：`rule-system.md`、`rule-collection.md`、`rule-time-gate.md`、`rule-suspension.md`、`rule-facts-scheduling.md`、`rule-exits.md`、`curve-collection.md`、`../C-trigger/condition-system.md`

## 一、v1 实现基线

C 轮 v1 只作旧实现证据，不是本方向已实现：

- 规则是 `clip.rules[{if|until, do}]` 片段元数据，按片段活跃窗建槽；`once`／`until` 由 timeline 回退重置。
- 规则生命周期截在 `[startTime, windowEnd)`；`until` 超时与 `if` 驻留／冷却使用不同时钟；`pause` 出口按同片段判定。
- 动作工件按锚点窗口回收；内容消费仍含 `getWindowEnd()`；`total_duration` 仍为必填统计位。
- EVENT 去重与命令协议保持一致。

## 二、当前实现速查（2026-10-30 对照源码）

- **载体**：片段级 `rules[]`（`TrackSchemas.putCommonClipFields`），每条 `{if | until, do}` 二选一互斥；修饰 `once`（缺省 `true`）、`cooldown`（缺省 0）、`mode`（`enter` 边沿 / `dwell` 驻留）、`dwell`、`timeout`（缺省 -1 = 无限）、`on_timeout`。它在片段上，不是 `meta` 字段，也不是触发器条目。
- **判定源**：`ClientConditions`，白名单八项 `time` / `location` / `facing` / `biome` / `dimension` / `camera_pos` / `camera_facing` / `camera_fov`；组合器 `all_of` / `any`，嵌套上限与触发器侧同一常量；事件类、tracker 类、前置条件类判定源不支持（校验期报 issue，运行期按不满足处理）。
- **引擎**：`PlaybackInstance.ruleEngine`，每实例一份；`FrameDriver` 在轨道分发之后逐帧 `evaluate(player)`；规则槽随脚本重建（`start` / `replaceScript`），停播清空。
- **动作**：`camera_set` / `overlay_set` / `adjust_set` / `goto_clip` / `pause` / `resume` / `exit` / `play_sound` / `emit_command`；参数类动作产出工件，片段活跃窗末 / 引擎重建 / 停播时回收，同类至多一件（再触发即覆盖）。
- **挂起**：`until` 与 `pause` 动作共用实例级「停住」状态但分开记账；同片段内已无释放者时自动解除并告警。
- **曲线**：`Clip.curve` 为 CAMERA clip 级一份 3D 控制点（恰好两个），隐式套到每个 from→to 段；`objects.curve` 从未实现。

## 三、差距表

| 目标 | 当前实现 | 偏离 | 归属 |
|---|---|---|---|
| 脚本级规则集合、定义／绑定／激活分层、多入口独立运行态 | `clip.rules` 每规则一槽，多入口会合并或互相覆盖 | 集合与绑定结构未落地 | `rule-collection.md` |
| 片段局部时间基准（起点 = 原点，`duration` = 轴） | 关键帧已按片段局部时间采样；时间门不存在 | 时间门与采集／提交分层未实现 | `rule-time-gate.md` |
| 局部时间阈值门、门前事实采集、门后持续判定 | 规则在片段活跃窗起点激活，窗口外不判定 | 时间门未实现 | `rule-time-gate.md` |
| 实例级挂起、多来源持有记账、可达性诊断 | 只有 `until` 阻塞等待与 `pause`，同片段内可释放 | 判定持有与诊断未实现 | `rule-suspension.md` |
| 规则／片段事实接力；成功≠动作完成；延迟动作 | 规则结果不能作为条件；动作在条件成立时直接提交 | 事实与调度未实现 | `rule-facts-scheduling.md` |
| 正常完成片段默认 + 入口覆盖；失败 fallback 独立 | 现行动作集有 `goto_clip`，两者未作为分开契约落地 | 出口结构未实现 | `rule-exits.md` |
| 循环按执行次数、规则状态按执行隔离 | 循环仍用 `%`／镜像折叠与 `getAnimPeriod()`，规则仍按时间回退重置 | 执行状态未落地 | `rule-exits.md`、`loop-execution.md` |
| 脚本级曲线集合、目标片段 + 相邻端点对、多条曲线 | `Clip.curve` 一份控制点隐式套所有段 | 集合结构未落地 | `curve-collection.md` |
| EVENT 去重与命令协议不变 | 一致：EVENT 仍按（实例, clip 索引, 对象路径, 事件索引）只触发一次 | — | `rule-exits.md` |
| 规则命令的服务端信任边界：客户端只发动作引用，服务端解析脚本命令；观看者由服务端签发 | 客户端原文命令 + viewer 检查 + 权限 4；播放回执可自登记 viewer | 请求面仍是任意命令，观看者身份可伪造 | `rule-command-trust.md` |

## 四、结构评审项汇总（非用户待确认）

- 稳定片段引用与绑定身份；片段重排、删除、插入后的校验与显式重建。
- 规则集合五层条目中哪些进 schema、哪些只在运行态；字段名与序列化。
- 事实记录的开始、有效期、消费与跨执行匹配方式；原条件未定义时如何补齐。
- 持有来源与释放条件的 schema 表达；可达性诊断的报告形态。
- 延迟动作释放条件如何与片段开始／完成、局部时间、事实和延迟统一表达。
- 曲线集合的字段名、序列化、端点引用与迁移形态。

用户已确认的方向（时间门 `>=`、门前事实门后确认、实例级挂起、事实接力、成功≠动作完成、延迟动作、正常后继片段默认 + 入口覆盖、失败 fallback）不在本节重复。
