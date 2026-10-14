# 点源时态开关（动态/静态）

> 状态：**已落地（2026-10-14，C7）**——见文末 §8 落地记录与 §9 补记。
> 由来：0.3.6 批次 B 收口期间用户补充分析——现有模型里「动/静」是点源**类型自带的隐含行为**（坐标天生恒定、选择器天生实时），人觉得直观但不统一；应改为由**点源上的一个值显式把控**。
> 关系文档：[coordinate-frame.md](../../complete/0.3.6/B-camera/coordinate-frame.md)（点源五形态与偏移，B5/B7）、[selector-model.md](../../complete/0.3.6/B-camera/selector-model.md)（选择器解析/锚点/择一，B10–B15）、[math-models.md](../../complete/0.3.6/B-camera/math-models.md)（定稿 4 生命周期与 seek=reset 拍板）。

---

## 1. 问题

同一个「点源」（玩家 / 坐标 / 选择器 / 方块 / 结构 + 偏移 + 表达空间），行为却由写法暗中决定：

- 写坐标 = 恒定不动；
- 写选择器 / 玩家 = 逐帧实时跟随。

于是「**跟/看某实体，但从这个镜头起参考点冻在当时位置**」这类镜头（定机位环绕、构图锁定、相对生物的定格取景）做不出来——只能硬写坐标，实体一动就穿帮。且动静规则散落在类型语义里，不统一。

## 2. 口径（定稿）

**来源 × 时态 二维**：点源 = 来源 + 偏移 + 表达空间 + **时态**（本篇新增的一维）。

| 来源形态 | 时态 = 实时（缺省） | 时态 = 快照 |
|---|---|---|
| 坐标 / 方块 / 结构 | 天生恒定，写不写无差 | 同左（无意义） |
| 玩家（WORLD 形态） | ~~跟着玩家走~~ **〔2026-10-14 修正：代码现状 = 玩家激活位置、整场不变（等价快照语义）；实时跟随 = 选择器 `@p`，见 §8〕** | 冻在取快照那一刻的玩家位置 |
| 实体选择器（SELECTOR） | 跟着实体走（= 跟随语义） | 冻在首次解析到的位置 |

- **缺省 = 现状**：玩家/选择器类缺省实时，坐标类天然恒定；不写这个值的脚本逐位零变化。
- **统一性**：follow / look_at 跟实体 = 「实时」档在特定字段上的表现，不再是独立语义。**理解上统一、字段不动**——follow/look_at 现有字段不迁移不改名（避免复杂化，见 §4）。
- 用户示例（拍板依据）：相对生物点源——开关关 = 第一次取到相对位置后固定；开 = 一直取、一直更新（即跟随）。

## 3. 边界规则（定稿，三条）

| 边界 | 规则 | 依据/类比 |
|---|---|---|
| 快照时刻 | **clip 开始取一次**；选择器异步解析的 = 首次解析成功那帧取 | 「clip = 片段」语义 |
| seek（编辑器拖播放头） | **重取快照** | B16 拍板「seek = reset」同口径（seek = 硬定位、状态归零） |
| loop 折返 / morph | loop 折返**不重取**（延续）；morph 两侧**各用各的快照** | 与运动模型 macro loop「状态延续」口径一致；morph = 两个片段各持状态 |

## 4. 范围限定（防复杂化）

1. 开关只管**点源**（follow 位置 / look_at 系 / yaw_base_from·to 端点 / facing_origin·facing_target / 位置基准 / selector_anchor 的 origin）；
2. **不扩到 motion 模型输入**（那是另一条线，混入即复杂化）；
3. 现有字段**不迁移不改名**——本开关是给点源对象加一维，不是重构既有字段；
4. 坐标/方块/结构类不需要写该值（写了也无效果，validator 可提示）。

## 5. 字段形态（已落地）

- 点源字段配套 `<字段>_time` 枚举：`facing_origin_time` / `facing_target_time` / `yaw_base_from_time` / `yaw_base_to_time` / `look_at_time`，取值 `"live" | "snapshot"`，缺省 `"live"`（未知值回落 live + validator 报错）；
- 仅对 SELECTOR 形态生效（§2 WORLD 行修正 + §4.4）；静态来源写时态 = 无效果 + validator 提示。

## 6. 验收（供 C 批任务书引用）

1. 时态 = 快照：首帧后参考点不变（实测取点冻结，实体移动画面不动）；
2. 时态 = 实时：逐帧跟随（与现状逐位一致）；
3. 缺省 = 现状：全库既有脚本逐位零变化（对拍口径）；
4. §3 三条边界各一探针（clip 开始取点 / seek 重取 / loop 延续 + morph 两侧独立）；
5. 坐标类写该值 = 无效果 + validator 提示；
6. 文档回写：SCRIPT_FORMAT 点源节 + 本文档落地记录。

## 7. 批次归属

**C 批新增小件**（C7「点源时态开关」）。已排 C 轮开头完成（2026-10-14）。

## 8. 落地记录（2026-10-14，C7）

- **实现**：`PointSource` 加 `Time{LIVE,SNAPSHOT}` 分量 + `parseTime(<字段>_time)`；新 `camera/source/PointSourceSnapshots`（按「片段引用 + 槽位 6」分桶取点表，null 不入表，分配 ∝ 片段数）；`EntityTargetResolver.resolvePointSource` 快照门（SNAPSHOT×SELECTOR×已注入时：命中读回 / 未命中现算非 null 才存）；`attachClip` 仿 `attachFrame` 范式注入当前片段（morph 两侧各注入 `renderMorph:250/255`）；生命周期 = onSeek/onStop/onScriptReplaced 清表、onLoopRestart 不动（延续）。
- **capture 语义 = lazy**（首次求值且解析非 null 那帧捕获；对「快照点源写在片段首段」的常规用法与「clip 开始取一次」逐位等价；fixture 把快照点源放首段）。
- **WORLD 行修正**：设计稿 §2 表「玩家（WORLD）实时 = 跟着玩家走」与代码现状不符——`"player"` 形态取的是 `originPos`（`ScriptPlayer.start` 取一次玩家位置，此后永不变），已是快照语义；实时跟随玩家走的是选择器 `@p` 路径。按「缺省=现状、逐位零变化」铁律，时态字段仅对 SELECTOR 形态生效，WORLD/坐标/方块/结构写时态 = 无效果 + validator 提示；本文 §2 表已就地修正。
- **验收证据**（详见 commit）：未改路径对拍 53 fixture 6247 帧六参数 raw-bits 逐字节一致；快照语义探针 32/32（冻结/seek 重取/loop 延续/morph 两侧/槽位隔离/静态无效果/null 不入表）；零分配稳态 2e5×4 轮 = 0 字节；icv 151 脚本基线 2 条不变 + 新 fixture ×2 [OK]；compileJava 全绿。
- **fixture**：`cinematics/tests/camera/test_camera_point_source_snapshot.json`（单 clip look_at 快照 + 实时对照）、`_seg2.json`（两片段第 2 段快照冻结）。

## 9. 补记：点源形态注册可扩展性（2026-10-14 用户口径）

**点源形态要方便后续注册新增**。未来可能新增点源形态——例如「扫群系、读群系地表、出一套坐标组，根据坐标组自动运镜」这类来源；现在不做，但 PointSource 的形态定义/解析/判据/schema 写法必须让后面新增形态时**注册成本低**（不要每加一个形态就到处改）。

落地口径：

1. 求值分发在 `EntityTargetResolver.resolvePointSource` 的 switch——新增形态难免动这里（求值逻辑所在），但**解析/判据/schema 侧**（`PointSource.parse` / `PositionData.originKindOf` / `PointSource.kindOf` / TrackSchemas 字段）应尽量收敛在少数注册点，加形态 = 加一处 case + schema 行，不动消费方。
2. C7 快照门是显式 `kind() == SELECTOR` 单点判定（v1 裁定范围）；槽位表/快照存储本身形态无关（按 clip+slot 分桶）。未来新形态若是「解析一次恒定」类型天然不需要快照；若是「实时扫描」类型想冻结，放开对应 Kind 即可——扩展点保持开放。

此为**记录口径**，不改已提交代码（当前实现已满足：快照门单点、槽位表形态无关、解析集中在 PointSource.parse 一处）。真正实现「群系地表坐标组」点源时另立任务书。
