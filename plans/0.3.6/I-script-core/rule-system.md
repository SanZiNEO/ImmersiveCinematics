# 规则与路径（索引）

> 状态：已实现（I4-1 ～ I4-9 关闭）
> 能力清单：片段身份 + 脚本级独立片段表（I4-1）；脚本级规则集合 `rules` / `rule_bindings`（I4-2）；规则运行态与必选时间门（I4-3）；实例级挂起与多来源持有（I4-4）；事实接力与五种 `schedule` 释放（I4-5）；出口策略与分支接线（I4-6）；`emit_command` 服务端信任边界（I4-7）；脚本级路径集合 `paths`（I4-8）；EVENT seek 与「循环/多入口不重放」定性（I4-9）
> 范围：规则系统与路径集合的索引与跨篇口径；正文按“一篇一个可独立执行的目标”拆到下列分篇，本页不重复
> 关联：`rule-collection.md`、`rule-time-gate.md`、`rule-suspension.md`、`rule-facts-scheduling.md`、`rule-exits.md`、`path-collection.md`、`rule-implementation.md`；结构侧见 `object-model.md`、`schema-and-types.md`、`runtime-evaluation.md`、`track-camera.md`
> 口径：本篇自足承载规则方向；C 轮 `../C-trigger/condition-system.md` 只作已完成 v1 的实现证据与旧设计参考。执行者不需要从 C 正文拼接目标语义，C 正文也不因本篇被重新打开。

## 与触发器的关系（先读）

**两套东西，共用一层底层能力。** 触发器管「整个脚本播不播」，规则管「实例播到这段时做什么」；规则是新增的一套字段，不是触发器的扩展。

| | 触发器 | 规则 |
|---|---|---|
| 管什么 | 整个脚本的播放门控：命中后起播 / 排队 / 顶掉 | 本实例播放过程中的判定与动作 |
| 在哪 | 服务端；脚本 `meta.triggers` | 播放端；脚本级规则集合（定义 + 绑定） |
| 字段 | 27 种类型 + 条件表 + `delay` / `requires` 等 | **新增字段集**，不写进触发器条目，不继承触发器类型 |
| 共享 | 底层判定：几何 / id 匹配核、状态谓词、事件采集语义、`all_of`／`any` 组合语义 | 同左 |

- 规则不是触发器的新类型，也不在 `meta.triggers` 的条目上加字段，更不挂在片段上（旧 `clip.rules` 是 v1 载体）：触发器条目里不会出现规则字段，规则条目里也不写 `type` 去复用某个触发器类型。落点 = 脚本顶层 `rules`（定义表）+ `rule_bindings`（绑定表），与 `clips`、`paths` 并列；字段口径见 [`rule-collection.md`](rule-collection.md) §3.4 与 [`docs/SCRIPT_FORMAT.md`](../../../docs/SCRIPT_FORMAT.md) §5。
- 「复用底层判定」不等于「复用触发器定义」：共享的是几何 / id 匹配核与事件采集语义（`GeometryConditions` 一类），不是 27 种触发器类型本身。
- 两套字段各自演化：触发器加类型不影响规则集合形态，规则集合改结构也不动 `meta.triggers`。
- 触发器字段不因规则集合改动：规则集合按新字段落地，不复用、不覆盖现有触发器字段。

## 共同时间基准（一句话）

规则与路径都是**新增内容**，不改现有体系。两者共用同一条时间基准：**时间原点 = 该片段本次执行的起点（executionStart，由编排/引用边给出），时间轴 = 该次执行的 `[0, duration]`**；关键帧 `time`、关键帧查找与采样、规则时间门、路径 timing 全部用同一个 `executionLocalTime`。`start_time` 只是默认放置锚点，不是执行起点；片段引用是稳定身份，不按轨道下标/放置位置定位。目标片段可以内联在轨道，也可以是脚本级独立片段，不要求它在任何轨道里（见 `clip-identity.md`）。展开见 [`rule-time-gate.md`](rule-time-gate.md) §二。

## 分篇地图

| 文档 | 负责的目标 | 一轮可交付的边界 | 状态 |
|---|---|---|---|
| [`rule-collection.md`](rule-collection.md) | 脚本级规则集合：定义 / 绑定 / 激活 / 事实 / 调度五层，多条目多入口，归属与身份 | 集合落点、条目形态、多入口记账；不含时间门语义与动作阶段 | 已实现（I4-2 / I4-3） |
| [`rule-time-gate.md`](rule-time-gate.md) | 片段局部时间基准、时间门（`>=` 阈值）、门前采集与门后提交、条件树边界 | 时间门与采集/提交分层；不含挂起、事实与出口 | 已实现（I4-3） |
| [`rule-suspension.md`](rule-suspension.md) | 到门后挂起整个实例、持有来源记账、释放条件可达性 | 判定等待的持有模型；不含动作延后 | 已实现（I4-4） |
| [`rule-facts-scheduling.md`](rule-facts-scheduling.md) | 事实接力、判定成功与动作完成分离、延迟动作与承诺动作参与完成 | 事实与调度；不含出口策略 | 已实现（I4-5） |
| [`rule-exits.md`](rule-exits.md) | 正常完成（片段默认 + 入口覆盖）、失败 fallback、退出三族、与循环 / EVENT / 播放关系的边界 | 出口与边界；不含时间门与调度 | 已实现（I4-6） |
| [`rule-command-trust.md`](rule-command-trust.md) | `emit_command` 的服务端信任边界：客户端只发动作引用、服务端解析脚本命令、实例授权与权限上限 | 请求面与命令源；不改 EVENT 命令协议 | 已实现（I4-7） |
| [`path-collection.md`](path-collection.md) | 脚本级路径集合：目标片段与相邻端点对、`geometry`（`line`/`bezier`）、`timing`、多条目 | 集合契约；相机侧装配归 `track-camera.md` | 已实现（I4-8） |
| [`rule-implementation.md`](rule-implementation.md) | 现行实现速查、现行实现表、新旧规则系统差异报告 | 只记录事实与边界，不定义语义 | 记录文档 |

分篇原则：每篇只定一个可独立执行的目标，执行者能在一轮内把它拆成任务并做完；跨篇引用只指目标，不把语义散落到多篇。

## 非目标（全篇共用）

- 不做通用变量语言、任意表达式求值或跨脚本任意控制。
- 不做外部模组注册自定义判定源、动作或事实总线。
- 不做编辑器的规则可视化编辑（编辑器呈现归 `../Z-editor/object-model.md`）。
- 不做单条激活的取消动作；实例终止、脚本替换或停播回收全部激活、事实与待执行动作。
- 不新增全局段落／sequence 库、独立分支引擎或另一种分支内容格式。
- 不改变过渡采样、时钟精度、渲染或媒体功能；不改变 EVENT 的服务端命令协议。规则 `emit_command` 的客户端→服务端请求按 `rule-command-trust.md` 收紧（客户端只发动作引用，服务端解析脚本命令）。
