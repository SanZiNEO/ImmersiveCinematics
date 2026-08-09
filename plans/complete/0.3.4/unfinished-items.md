# 0.3.4 未完成功能清单（计划 vs 代码对照）

**创建日期**: 2026-08-06
**对照范围**: `plans/` 全部计划，含 `plans/complete/` 下已归档的旧版本计划（0.3.0~0.3.3、trigger/control/repair 系列、speed_driven、architectury 迁移系列等）
**依据**: 当前 0.3.4 代码（`common/src/main/java`）逐项核实，代码与计划冲突时以代码为准

> 用途：记录所有"计划过但到 0.3.4 仍未实现 / 未接线 / 只做了一半"的功能，供后续版本排期参考。已确认完成或明确放弃的计划不在此清单（见文末附录）。

---

## A. 明确延期或不做（计划自述，符合预期）

| # | 功能 | 计划出处 | 代码现状 |
|---|------|---------|---------|
| A1 | PAUSE_POINT 轨道 | 0.3.4 README 不做清单 / 0.4.0 | 无此轨道类型 |
| A2 | 区块预加载 | 0.4.0（计划 0.3.5） | 无 |
| A3 | ~~跟踪系统非玩家实体/结构目标~~ | 0.3.4 camera-tracking-system.md | ✅ **已实现（2026-08-09）**：`@e`/`@e[type=..,name=..]`/`uuid:..` 选择器 + `look_at_target_structure` 服务端 findNearestMapStructure 定位推送，不依赖区块预加载；实现为关键帧级字段 |
| A4 | Bezier 曲线编辑器（控制点可视化） | 0.4.0 | 编辑器无曲线编辑 |
| A5 | 预设片段库 | 0.4.0 | 无 |
| A6 | dissolve crossfade 过渡 | script_system.md / 0.3.0 排除项 | `TransitionType` 只有 CUT/MORPH |
| A7 | CustomTriggerAPI（第三方模组自定义触发器） | trigger_system_plan_v4 §11 | 未实现（原计划标注"后续可加"） |
| A8 | 音频倍速变调 | 0.3.4 README 不做清单 | 无（注：代码超计划实现了 clip 级 `pitch` 音高字段） |

## B. 注册了但未接线（最严重 — 功能存在但永远不触发/不可达）

| # | 功能 | 计划出处 | 代码现状 |
|---|------|---------|---------|
| B1 | `advancement` 触发器 | trigger_system_plan_v4 §4.2（走 AdvancementEvent） | 已注册，但全工程无 `onGameEvent("advancement")` 调用 → 永不触发 |
| B2 | `item_consume` 触发器 | trigger_system_plan_v4 §4 | 已注册，无事件源（代码注释：需 Mixin `LivingEntity.completeUsingItem()`）→ 永不触发 |
| B3 | `item_on_interact` 触发器 | 0.3.2 item-on-interact-trigger.md | 求值器与物品追踪（`InteractTracker.recordInteractionItem`）已实现，但迁移 Architectury 后事件分发机制丢失，无 `onGameEvent("item_on_interact")` → 永不触发 |
| B4 | `custom` 触发器 | trigger_system_plan_v4 §11 / 0.3.3 README（P11 计划删除） | 既未删除也未接线，`CustomEventTracker.fire()` 无调用方 → 永不触发 |
| B5 | 触发器状态同步链路（S2CTriggerStateSyncPacket） | trigger_system_plan_v4 §2.4 网络协议核心包 | 包与客户端缓存类（`ClientTriggerStateCache`）均存在，但服务端从不发送、客户端无人读取 → 整条链路休眠 |

> 统计：16 个注册触发器类型中 4 个（B1-B4）无事件源；连同 B5 共 5 条未接线链路。

## C. 只做了一半

| # | 功能 | 计划出处 | 代码现状 |
|---|------|---------|---------|
| C1 | 播放队列完整方案 | repair_plan_E E2 / 0.3.4（2026-08-09 用户确认规则：priority 仅队列排序、移除 queueable、不可打断一律排队、可打断立即替换、无优先级抢占） | ✅ **已实施（2026-08-09）**：`ScriptQueue` 容量 8（priority 降序 + FIFO）+ `playScript` 决策树（可打断→立即替换；不可打断→一律入队满则拒）+ `deactivateNow` 自动接播 + schema/meta `priority` 字段；客户端 `/icinematics queue` 查询命令按用户要求移除（未实施） |
| C2 | EVENT 轨道编辑器可视化 | 0.3.4 event-track-rework.md（"EVENT 不渲染 clip 段矩形，keyframe 显示为标记点"） | ✅ **按确认方案完成（2026-08-09）**：复用现有 clip 块 + 块内关键帧调控 EVENT（用户确认现成方案即可，不做专属标记点）；运行时 keyframe 驱动多点触发已实现 |
| C3 | 编辑器音频联动 | 0.4.0（依赖 0.3.4 AUDIO） | 播放头 `setTime()` 跳转时 `repositionAudio` 已实现；拖拽 audio clip/关键帧过程中的实时跟随未做（待实施） |
| C4 | 维度字段消费 | script_design_v3 §meta / 0.4.0（计划 0.3.5） | `meta.dimension` 仅解析存储，无任何运行时校验/限制 |
| C5 | Schema 双向驱动（编辑器字段白名单） | 0.3.3 complete/02-post-migration.md ③ | ✅ **实际已完成（2026-08-09 复核）**：编辑器字段 UI 由 `SchemaLoader.getClipFields/getKeyframeFields` 白名单驱动（LeftPanelArea），默认值补齐走 `EditorDefaults`（schema 驱动）——原清单标注过时 |
| C6 | Timeline Widget 化 | 0.3.3 complete/02-post-migration.md ② | `TimelineArea.drawClip()` 仍是巨石方法，未拆分为 UIClipWidget / UIKeyframeDiamond / UITransitionZone |

## D. 编辑器交互计划（0.3.3）未做的小项

| # | 功能 | 出处 |
|---|------|------|
| D1 | Ctrl+中键框选 → 缩放到选区（Olive Zoom Tool） | editor-interaction-plan §一 |
| D2 | 轨道高度可调（拖拽轨道间分隔线） | editor-interaction-plan §四 |
| D3 | ~~TRACK_LIST 每行轨道显隐开关 👁~~ | editor-interaction-plan §八 — ✅ 已实现（2026-08-09 复核）：TimelineArea 轨道头 👁 显隐按钮（含锁定/静音），原清单标注过时 |
| D4 | 时间码 `MM:SS.mmm` 格式 | editor-interaction-plan §六（当前为 `h:mm:ss`） |
| D5 | 滚轮映射与行业惯例：计划"滚轮=水平滚动、Shift+滚轮=垂直" | editor-interaction-plan §一/§五；CHANGELOG 0.3.3 声称"滚轮=水平滚动"——代码实际为"无修饰=垂直滚动轨道、Shift+滚轮=水平滚动、Ctrl+滚轮=缩放"，计划与 CHANGELOG 均与代码不符 |

## E. 遗留小项（计划标注低优先级或属清理类）

| # | 功能 | 出处 |
|---|------|------|
| E1 | `MenuBarArea` 硬编码数值提取（去魔法数） | 0.3.3 complete/01-fixes-pending.md D2 |
| E2 | `UITextInput` 光标移动/位置插入/Ctrl+C/V | 0.3.3 complete/01-fixes-pending.md（原标注"优先级低，可不做"） |
| E3 | letterbox 动画 ease-in-out 平滑 | phase_2_repair_plan.md P2-3（当前关键帧线性插值） |
| E4 | `MathUtil` Hermite 基函数 h00/h10/h01/h11 残留 | speed_driven_interpolation_refactor.md（引擎已删除，基函数注释仍写"速度曲线引擎用"，属死代码） |
| E5 | 0.3.2 测试清单未勾选项（ESC 重开保持、letterbox 旧格式兼容回归等） | 0.3.2/0.3.2_test_checklist.md（功能已实现，缺回归验证） |

---

## 附录：已实现但与计划存在设计差异（非缺失，供追溯）

| 项 | 计划 | 实际实现 |
|----|------|---------|
| 呼吸字段名 | `breath_enabled` / `breath_intensity` / `breath_seed` | `cam_breath_enabled` / `cam_breath_intensity` / `cam_breath_seed` |
| 跟踪数据结构 | 嵌套 `tracking` 对象块 | 扁平 `cam_tracking_*` 字段（功能等价） |
| 音频资源目录 | `run/immersive_cinematics/video/` | `immersive_cinematics/resource/`（ResourcePath） |
| `minecraft:` 音源 | "走原版 SoundEvent" | 从资源包解码 OGG 后仍走 OpenAL 直播 |
| OVERLAY zIndex | 固定层级 0/10/20/30/40 | 新增 clip 级 `z_index` 字段（超计划实现） |
| fade 层命名 | ScreenFadeLayer | FadeLayer |
| blockChat/blockScoreboard/blockActionBar（control_system 旧命名） | boolean 字段 | 实现为三态 `hide_chat`/`hide_scoreboard`/`hide_action_bar`（能力更强） |
| 跳过键 | control_system §3.1"长按 Esc" | 默认 C 键（KeyMapping，可自定义） |
| trigger catalog 目录同步（v4 §8） | S2CCatalogSyncPacket | 未用，替代为编辑器本地 `BuiltInRegistries` 候选列表 |
