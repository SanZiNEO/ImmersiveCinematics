# 点源时态开关（动态/静态）

> 状态：**设计已定（2026-10-11 用户口径）**，实现批次 = **C 批小件**（排 C 轮开头，量级 ≈ B12）。边做边讨论，实现时如遇边界再议、就地销项。
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
| 玩家（WORLD 形态，脚本写 `"player"`） | 玩家**激活位置**（脚本激活时玩家所在位置，整场不变 = **等价快照语义**；实时跟随玩家 = 选择器 `@p`） | 同左（无效果） |
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

## 5. 字段形态（草案，实现时定）

- 点源对象内加可选枚举（如 `time: "live" | "snapshot"`；命名实现时定，与 SCRIPT_FORMAT 体例一致）；
- 点源关键字简写形态（如直接写 `"@p"` / `uuid:`）不受影响，缺省 = 实时；
- schema/校验随点源对象走（B7 的点源解析单源 PointSource 是落点）。

## 6. 验收（供 C 批任务书引用）

1. 时态 = 快照：首帧后参考点不变（实测取点冻结，实体移动画面不动）；
2. 时态 = 实时：逐帧跟随（与现状逐位一致）；
3. 缺省 = 现状：全库既有脚本逐位零变化（对拍口径）；
4. §3 三条边界各一探针（clip 开始取点 / seek 重取 / loop 延续 + morph 两侧独立）；
5. 坐标类写该值 = 无效果 + validator 提示；
6. 文档回写：SCRIPT_FORMAT 点源节 + 本文档落地记录。

## 7. 批次归属

**C 批新增小件**（暂记 C7「点源时态开关」，implementation-progress.md 批次 C 表登记随 T20 收口后补）。排 C 轮开头做掉，不挡触发器主线；若与 C6 判定规则的「冻结值 vs 实时值」语义有耦合，实现前先在此文档对齐一次再动手。

---

## 8. 落地记录（2026-10-14 实现）

### 8.1 字段形态（落地值）

字段名 = **点源字段名 + `_time`**，取值 `"live"`（缺省）/ `"snapshot"`，共 5 个：
`facing_origin_time` / `facing_target_time` / `yaw_base_from_time` / `yaw_base_to_time` / `look_at_time`。
未知取值运行期回落 `live`，校验器报错（脚本仍可播，与其余枚举校验同口径）。

### 8.2 实现口径

- **形态适用范围**：时态只对 **SELECTOR 形态**（`@…` / `uuid:…`）生效；静态来源（坐标 / 方块 / 结构 / 玩家激活位置）
  写了 = 无效果（validator 提示级）。原因：WORLD（脚本写 `"player"`）= 玩家**激活位置、整场不变**，代码现状
  **已是快照语义**（`PointSource.world()` / `EntityTargetResolver.originPos` 唯一赋值点 = 脚本激活位置）。
- **capture = lazy**：不做 clip 激活检测钩子。快照在「首次求值且解析非 null」那帧捕获（`lookup → miss → 现算 →
  非 null 才存`），null 不入表（目标搜索态期间不钉死「无点源」）。对快照点源写在片段首段的常规用法与
  「clip 开始取一次」逐位等价。
- **冻结的是展开偏移后的最终坐标**：快照 = 整个点源结果（来源取点 + 偏移表达空间展开），命中即整份读回
  ——这也是「稳态命中路径零分配」的前提（命中不重算偏移）。
- **分桶 = 片段身份 + 槽位**：槽位与点源字段一一对应（facing_origin / facing_target / yaw_base_from /
  yaw_base_to / look_at / position-anchor），故**同一片段内该字段的所有关键帧共用一份快照**；
  morph 两侧各持各的（两个片段两个桶）；`selector_anchor=origin` 的位置侧锚点取值单列一桶
  （它固定按锚点 `camera` 解析，与位置基准点按配置锚点解析的结果可以不同）。
- **生命周期**：`onSeek` / `onStop` / `onScriptReplaced` 清空（seek = 硬定位 → 重取）；`onLoopRestart` **不清**
  （折返 = 状态延续）。seek 链（`PreviewChannel.setTime` → `ScriptPlayer.onSeek` → 轨道 `onSeek`）是唯一重取入口。
- **当前片段注入**：求值器在位置 / 注视点 / 朝向段 / 可用性探测（含 morph 两侧）各入口注入片段身份，
  前置探测（`isClipUsable`）与求值读同一（片段，槽位）桶，判据不分叉。

### 8.3 WORLD 行修正说明

本文件 §2 原表「玩家（WORLD 形态）：实时 = 跟着玩家走」与代码不符，已按代码现状修正为
「WORLD = 玩家激活位置（恒定，等价快照语义）；实时跟随玩家 = 选择器 `@p`」。§4.1 的「follow 位置 / yaw_base=entity
直连路径不动」不受影响。

### 8.4 落地清单

- `camera/source/PointSource.java`：record 加 `Time` 分量 + `Time` 枚举 + `parseTime` + `withTime`；`of(pd, point, time)`。
- `camera/source/PointSourceSnapshots.java`（新）：按（片段引用，槽位）分桶的取点表，线性扫描、容量按需增长。
- `camera/source/EntityTargetResolver.java`：`resolvePointSource` 加快照门（`attachSnapshots` / `attachClip`）。
- `camera/source/CameraKeyframeEvaluator.java`：各求值入口注入当前片段；`look_at` / `facing_origin` 装配时态。
- `script/CameraTrackPlayer.java`：持有快照表并在 seek / 停止 / 替换时清空。
- `script/schema/TrackSchemas.java`：5 条 `_time` 字段元数据（自动导出 WebUI）。
- `script/ScriptValidator.java`：`_time` 枚举校验 + 静态来源无效果提示。
- `cinematics/tests/camera/test_camera_point_source_snapshot.json`、`..._snapshot_seg2.json`（新 fixture）。
- `docs/SCRIPT_FORMAT.md`：§「点源」+ §「点源时态」（含三条边界表）+ Keyframe 字段表 5 行。

### 8.5 验收口径

未改路径逐位对拍（HEAD 版类 vs 新版类，git HEAD 版 camera fixture 语料 52 个脚本 / 6247 采样帧，raw-bits
逐字节一致）+ 敏感度对照（带 `_time` 的合成脚本必须两侧有差异）+ 三条边界探针 + 稳态快照命中 0 分配。
探针脚手架：`E:/tmp/c7/probe/run.sh`。
