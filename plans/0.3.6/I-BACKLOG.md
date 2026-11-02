# I 轮移交与清零清单（I-2 → I-3 / I-4 / I-5）

> 建立：2026-11-02。来源：I-2 验收遗留 + I-2 复核新发现。
> 原则：**I 轮（I-5 收口）结束时本表必须清零**；本轮做不了的必须有明确归属轮次与关闭条件，不能写成“后续再说”，也不能只留在轮次总结里。
> 纯契约 / 设计边界单列在 D 表，不算遗留；但必须在对应专篇有明确文字。

## 轮次归属假设

按 `I-script-core/README.md` §六 建议实现顺序 + I-2 任务书的 I-1~I-5 过渡策略：

- **I-3**：各轨道装配（CAMERA → OVERLAY / LETTERBOX → AUDIO / ADJUST / EVENT）。
- **I-4**：脚本级规则 / 路径集合、时间门 / 激活 / 挂起 / 事实 / 延迟动作、分支接线、片段身份 / 引用。
- **I-5**：格式清理（旧字段拒绝、`file_loop` 改名、`version` / `total_duration` 删除）、迁移器、文档与最终验收。

若 `I-ROUNDS.md` 的编号不同，以它为准；本表按“能力归属”排，不按编号硬绑。

## A. I-3 轨道装配

| ID | 事项 | 关闭条件 | 依赖 / 备注 |
|---|---|---|---|
| I2-B1 | 旧循环夹具实机像素回归 | Fabric / Forge 各跑 `test_camera_loop_repeat` / `test_camera_loop_pingpong` / `test_exit_loop_count` / `test_exit_infinite`；记录执行人、截图/日志；同帧无结构差异 | 旧格式现在就能跑；I-3 接轨道后复跑；I-5 最终全量 |

## B. I-4 规则 / 路径 / 分支接线

| ID | 事项 | 关闭条件 | 依赖 / 备注 |
|---|---|---|---|
| I2-B2 | 分支控制器接 `ScriptPlayer` / 规则引擎 | 规则绑定可启动分支；正常/失败走同一 `ClipBranchController`；探针 + 实机 | 依赖 rule-collection / rule-time-gate |
| I2-B3 | 稳定片段身份 / ClipRef / 使用边 + 多入口/多放置 | `clip-identity.md` 结构评审定稿；同一片段多入口各自执行身份/运行态隔离；重排/插入/删除显式重映射；探针 | 规则/路径集合的前置；不能退回轨道下标 |
| I2-B4 | `END_EXECUTION` 与 `CONTINUE` 语义 | 按 `rule-exits.md`：有隐式序列/调用栈则分离；确认无栈则文档明确等价并关闭 | 设计不要求调用栈；不能悬空 |
| I2-B5 | 分支 + Modifier 活跃窗 | `ClipModifierGate` 按分支入口 `executionStart` / `windowStart` 判活；探针 + 实机 | 依赖 I2-B2 |
| I2-B6 | 跨帧跳过多次执行的中间执行身份/规则激活 | 明确“跳过的执行是否建立激活/事实”；账本提供边界事件或规则按最终执行补齐；探针 | 与 I2-B2 同轮；设计 §3.2 执行边界事件 |
| I2-B7 | 分支 launch 时间不变量 | `ClipBranchController.launch` 校验 `host.now() >= ledger.lastHead()`（或用 `lastHead()` 作 launch 时间）；非法返回 false；探针 | 与 I2-B2 同轮 |
| I2-B8 | 分支失败副作用边界 | `rule-exits.md` 写明“只恢复执行事实，不重放内容；音频/覆盖层按各自生命周期”；如需回滚单列设计 | 设计边界；与 D 表 I2-C2 对应 |
| I2-B9 | 分支出口脚本声明/序列化（片段默认 + 入口覆盖） | schema / parser / validator / 编辑器契约落地；入口覆盖 > 片段默认；旧格式迁移报告 | 与规则集合同轮或 I-5 |

## C. I-5 格式清理 / 迁移 / 最终验收

| ID | 事项 | 关闭条件 | 依赖 / 备注 |
|---|---|---|---|
| I2-B10 | `docs/SCRIPT_FORMAT.md` 重写 | 新口径（objects / loop / branch / 旧字段移除）；grep 无旧循环、旧字段残留 | 与格式清理同轮 |
| I2-B11 | 宏观循环 L6（执行次数/完成事实） | 二选一并写入 `acceptance.md`：① 实现执行边界计数；② 明确“宏观语义不变、时间圈仅定位”，改写 L6 表述 | 设计 §2.3 说非目标；不能悬空 |
| I2-B12 | AUDIO `file_loop` 改名 + 旧 `loop` 双语义切换 | 旧格式 AUDIO `loop` 迁移为 `file_loop`；新格式 `loop` 只作执行循环；旧字段出现即错误（I-5） | 依赖迁移器 |
| I2-B13 | 旧循环脚本迁移器/差异报告 + 旧字段拒绝 | 迁移器按 `migration.md` 处理旧 `keyframes` / `curve` / `rules` / `loop`；旧字段错误；报告/回滚 | 与 I2-B12 同轮 |
| I2-B14 | 最终全量实机/性能验收 | 4 支循环夹具 + 新格式轨道脚本 + 多实例；记录执行人/结果 | I-5 收口 |

## D. 契约 / 设计边界（不排期，但必须在对应专篇定性）

| ID | 事项 | 定性 | 处理 |
|---|---|---|---|
| I2-C1 | `finiteWindowEnd` / `getWindowEnd` / `contentEnd` 无限调用抛异常 | 契约：调用方先判 `hasFiniteWindowEnd()` / `hasFiniteContentEnd()` | 已写入 `ClipTimeSource`；不修 |
| I2-C2 | 分支快照只恢复账本/播放头，不回滚音频/覆盖层副作用 | 设计边界：失败 fallback 按执行事实恢复，不重放内容 | 由 I2-B8 写入 `rule-exits.md`；不承诺回滚 |

## 清零规则

- **I-3 收口**：A 表至少完成旧循环实机基线；I2-B1 的最终全量留 I-5。
- **I-4 收口**：B 表全部关闭（含文档定性）；I2-B9 的序列化若拆到 I-5，必须在 I-5 表有对应项。
- **I-5 收口**：C 表全部关闭；D 表在对应专篇有明确文字；本表无“未排期”项。
- 任何新增遗留必须补进本表并指定轮次/关闭条件；不允许只写在轮次总结里。
