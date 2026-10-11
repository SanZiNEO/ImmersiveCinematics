# 0.3.6 过渡：六参数状态过渡（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、曲线细节、JSON、打断规则等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [数学函数模型](./math-models.md)
> - [时间插值](./temporal-interpolation.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位（已确认）

**过渡（Transition）**：

> 相机从状态 A 到状态 B 的变化过程。

- 完全围绕 6 个底层参数：position、yaw、pitch、roll、fov、zoom
- 属于**应用层**
- 用于非瞬时切镜头
- 目的是丰富相机运动、让转场更自然

直接切镜头 = 一种特殊的过渡（duration = 0）。

---

## 2. 与时间插值的区别

见[时间插值](./temporal-interpolation.md) §2 的对比表。

---

## 3. 过渡来源（方向）

- 关键帧 / 片段之间
- look_at / follow 目标切换
- Base Provider 切换（脚本 / 编辑器 / 飞行 / 外部）
- 外部 Override 的 push / release
- 编辑器 seek
- 脚本打断 / 排队接播
- 未来其他相机模式切换

---

## 4. 过渡类型（方向）

- **Cut**：硬切，无中间过程
- **Blend**：6 个参数同时过渡
- **分参数过渡**：每个参数独立选择切 / 过渡 / 保持
- **曲线过渡**：用缓动 / 贝塞尔 / 自定义曲线控制节奏
- **分层 / 链式过渡**：A→中间→B，或 A→B→C
- **双状态混合**：A / B 按权重混合

> 具体类型清单和优先级执行时再定。

---

## 5. 六参数语义（方向）

- `position`：世界坐标或相对基准；可线性 / 贝塞尔 / 样条
- `yaw` / `pitch`：角度环绕、最短路径、多圈语义
- `roll`：可独立切 / 过渡 / 保持
- `fov`：注意安全范围
- `zoom`：建议对数 / 几何域
- 每个参数可以有独立的时长、曲线、模式

---

## 6. 与覆盖链的关系（方向）

过渡可能放在：

- Base Provider 层：Base 切换时做过渡
- Modifier 层：作为临时修改
- 独立过渡层：专门管理 A→B 状态变化

需要明确它与 Base / Modifier 的优先级、打断和叠加关系。

**实施接线（2026-10-11，B18 落地回写）**：第一版取「独立过渡层」，落点 = `camera/transition/`（层本体 + 声明 + 载体 + 混合公式），由 `CameraKeyframeEvaluator` 每轨持有一份；**合成位置 = 主相机状态写入点（覆盖链的 Base 写入侧）**，即实际次序为「过渡层 → 覆盖链（Base 选择 + Modifier 合成）→ 渲染读取」，而非「覆盖链 → 过渡层」。原因两条：① 过渡的两侧（A 侧 = 前一段实时状态、B 侧 = 后一段实时状态）只存在于 Base 写入点，放到链输出之后拿不到 A 侧，legacy morph 无法逐帧复现（B18 验收硬项）；② 渲染读取的主相机 lane 目前只在链上有 Modifier 生效时取链输出（`client/lane/ScriptLaneDriver.applyModifiers`），放链输出之后需先改该条件，否则过渡窗口内主画面不生效。③ 既有 morph×Modifier 的次序是「先混合再合成」，本实现保持该次序才满足「未改路径行为基线对拍」。
**将来迁移门槛**（满足后即可把层下移到链输出之后，A/B 口径不变）：主 lane 无条件取链输出（`ScriptLaneDriver` 条件改为「有 Modifier 或过渡进行中」）+ 层应用点移到 `CameraManager.refreshCameraState`。

---

## 7. 可能的问题

- 过渡放在哪一层
  → 已定（2026-10-11，B18）：独立过渡层，落点 `camera/transition/`；合成位置 = 主相机状态写入点（覆盖链的 Base 写入侧），次序 = 过渡层 → 覆盖链 → 渲染读取。详见 §6「实施接线」。
- 打断规则（snap / queue / blend / inherit）
  → 已定（2026-10-11，B18）：第一版支持 **snap + blend**，缺省 **blend**（用户拍板）；`queue` / `inherit` 不入第一版（未实现、无字段）。
- 分参数过渡的默认行为
  → 已定（2026-10-11，B18）：每通道 mode 缺省 `blend` + 片段级 `transition_duration` + 恒等曲线（= 线性斜坡）；仅显式声明 `transition_params` 的通道才分化。`TransitionSpec.build` 按通道补齐。
- 曲线与数值域规则
  → 已定（2026-10-11，B18）：曲线走 math 模型层（`MathModel` / `MathModelInstance`，不重造数学），缺省恒等 = 现状线性；角度 / 几何域混合公式单源 `TransitionBlend`（yaw / roll 最短路径、zoom 几何域）。
- 状态 A / B 的采样时机
  → 已定（2026-10-11，B18）：A 侧 = 前一段实时状态、B 侧 = 后一段实时状态，各自按自身时间采样；两侧都在 Base 写入点取，窗口以交界时刻为中心、宽 = 该通道时长。
- 与关键帧插值的边界
  → 划界（2026-10-11，B18）：过渡只作用于「状态 A→B 变化」，不改关键帧段内插值（段内匀速线性，缓动由编辑器烘焙成显式关键帧，方案 E）；现状不变。
- 与 look_at 目标切换的边界
  → 划界（2026-10-11，B18）：目标切换平滑属既有 `selector_switch_smooth`（`EntityTargetResolver.smoothTargetPoint`），不在 B18 重做。
- 与外部 Override 的交互
  → 划界（2026-10-11，B18）：外部 Override 无公开入口（B4-P5 留位），过渡层不与外部 API 交互。
- 过渡链 / 中断恢复
  → 已定（2026-10-11，B18）：链式 A→B→C 已落地——后开始的过渡优先（`CameraTrackPlayer.transitionPairAt`）；打断 blend 时以当前混合中间态为新 A 侧、时长重新起算。
- 性能与确定性
  → 已定（2026-10-11，B18）：逐帧零分配（`CameraTransitionLayer.blendInto` 不 new / 不装箱 / 不建集合，曲线实例仅过渡开始时分配一次）+ 确定性（基线对拍逐字节）。
- 编辑器如何配置与预览
  → 归编辑器阶段（2026-10-11，B18）：§9 步骤 7 不做。
- 外部 API 开放时机
  → 留位不开放（2026-10-11，B18）：0.3.6 只做内部接口，外部 API 留位（§9 步骤 8）。

---

## 8. 待定

- 过渡类型第一版范围
  → 已定（2026-10-11，B18）：cut / blend / 分参数（blend·hold·cut）/ 曲线 / 打断（snap·blend）/ 链式（A→B→C）；双状态混合 = morph 现状（`CameraTrackPlayer.renderMorph`）。
- 默认时长 / 默认曲线
  → 已定（2026-10-11，B18）：时长 = 片段 `transition_duration`（缺省 0.5）；曲线缺省 = 恒等（= 现状线性斜坡）。
- 分参数默认值
  → 已定（2026-10-11，B18）：每通道 mode 缺省 `blend`、时长缺省 `transition_duration`、曲线缺省恒等（`TransitionSpec.build` 按通道补齐）。
- 打断规则默认行为
  → 已定（2026-10-11，B18）：缺省 `blend`（用户拍板）；`snap` 需显式声明。
- 过渡与 Base / Modifier 的最终关系
  → 已定（2026-10-11，B18）：独立过渡层；合成位置 = 主相机状态写入点（Base 写入侧），次序 = 过渡层 → 覆盖链 → 渲染读取；与裁定 4 的偏差、原因与迁移门槛见 §6「实施接线」。
- JSON / 接口 / API
  → 已定（2026-10-11，B18）：字段 = `transition_params`（分参数）/ `transition_curve`（曲线）/ `transition_interrupt`（打断，缺省 `"blend"`）；接口 = `TransitionSpec` / `TransitionParams`（内部）；外部 API 不开放只留位（§9 步骤 8）。

---

## 9. 落地顺序（方向）

1. 定义过渡的六参数语义
2. 支持 cut / blend
3. 支持分参数过渡 —— ✅ 已落地（2026-10-11，B18）：`TransitionMode`（blend / hold / cut）+ `TransitionParams` + `TransitionSpec` 逐通道时长 / 曲线。
4. 接入曲线（数学函数模型）—— ✅ 已落地（2026-10-11，B18）：`TransitionSpec.instantiateCurves`（`MathModelInstance`，缺省恒等）；不重造曲线数学（math-models 定稿 8）。
5. 接入 Base / Modifier 链 —— ✅ 已落地（2026-10-11，B18）：`CameraTransitionLayer` 输出经 Base 写入点进链（`CameraKeyframeEvaluator` 每轨持有一份）。**偏差**：层在主相机状态写入点（Base 写入侧）而非裁定 4 的链后；原因与迁移门槛见 §6「实施接线」。
6. 支持打断 / 链式 —— ✅ 已落地（2026-10-11，B18）：`TransitionInterrupt`（SNAP / BLEND，缺省 BLEND）+ 链式 A→B→C（`CameraTrackPlayer.transitionPairAt`）。
7. 编辑器 UI —— ✗ 未做（2026-10-11，B18）：归编辑器阶段。
8. 内部稳定后考虑外部 API —— 留位不开放（2026-10-11，B18）：0.3.6 只做内部接口，不进 `api` 包。

---

## 10. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [数学函数模型](./math-models.md)
- [时间插值](./temporal-interpolation.md)
- [迟滞](./hysteresis.md)

---

## 事实核查（2026-10-07）

> 核查依据：本仓库源码。§1–§10 设计/方向内容未改动。

### ① 核实为真（§3 过渡来源 / §5 六参数语义的现状挂点）
- **morph 过渡已存在**：`transition` 枚举 `{cut, morph}`（`script/schema/TrackSchemas.java:30`、`script/ScriptValidator.java:166`）、`TransitionType.CUT/MORPH`（`script/TransitionType.java`）、`Clip.isMorph()` / `getTransitionDuration()`（默认 0.5，`script/Clip.java:71-77`）。实现：`CameraTrackPlayer.renderMorph(...)`（`script/CameraTrackPlayer.java:247`）——B 模型重叠区 `[prevEnd−t/2, prevEnd+t/2)`（:139-157），按 weight 对 **position + yaw/pitch/roll/fov/zoom 全 6 参数**交叉混合（角度用 `blendAngle`）。即 §4 的 "Cut + Blend + 双状态混合" 已有原型。
- **切线朝向已存在**：`orient` 枚举 `{manual, tangent}`（`ScriptValidator.java:167`）；`TangentOrientation.compute(...)`（`script/TangentOrientation.java`）由路径切线求 yaw/pitch，叠加 `yaw_offset`/`pitch_offset`；入口 `CameraTrackPlayer.isTangentOrientation`（:820）/ :791-800。
- **look_at / follow 目标切换**：`CameraTrackPlayer.selectorPolicy` + `smoothTargetPoint` + `TargetLock`（:1052-1054、:1197），角色 follow / look_at / look_at_target / facing_origin。
- **编辑器 seek = 硬定位（无过渡）**：`WebEditorApi.handleSeek`（`webui/WebEditorApi.java:211`）→ `CameraManager.pause()` + `setTime(t)`（`camera/CameraManager.java:229`）→ `scriptPlayer.alignTime(...)`，下一帧 `CameraTrackPlayer` 经 `setPositionDirect`/`setAllDirect` 直写（`CameraTrackPlayer.java:312-313`）。
- **六参数各自独立**：`CameraProperties` 五属性各持独立 `AnimValue`（`camera/CameraProperties.java:29-63`），`CameraPath` 单独管位置——与 §5 "每参数可独立时长/模式" 方向对应。

### ② 已修正
- 无（§3/§5 均为方向性表述，未发现与代码冲突的现状断言）。

### ③ 补全
- §3 所列 "Base Provider 切换（脚本 / 编辑器 / 飞行 / 外部）" 现状：脚本 = `CameraTrackPlayer`；编辑器预览 = `CameraManager` preview（`pushScript` / `setTime` / `previewSetCamera`，`CameraManager.java:207-…`）；飞行 = `control/FlightController`（直写 :294-297）；"外部" 暂无公开输入入口（脚本 JSON 为唯一外部输入）。
- §5 的 "分参数过渡" 已有原语：`CameraProperties.AnimValue.setTarget(v, dur)` 每参数独立时长；`CameraManager.stageTargetPosition/Yaw/...` + `commitStagedState()`，仅用于编辑器 staged 预览。
- 位置路径现状：`PathStrategies` 注册表静态仅注册 `"linear"`；`"bezier"`（`BezierPathStrategy`，含 ArcLengthLUT 匀速参数化）由 `CameraTrackPlayer` 直接持有实例（`CameraTrackPlayer.java:23`），未进注册表。样条（CatmullRom）仅 `script/PathStrategy.java` javadoc 列为未来项。

### ④ 未验证
- §3 的 "外部 Override 的 push / release"、"脚本打断 / 排队接播" 作为过渡来源列出，未在代码中找到对应的公开实现入口——**未验证**（可能为规划项）。

---

## 落地记录（2026-10-11，B18）

> B18（过渡 步骤 3-8）已落地，commit `2ddc08e`。本文是计划真源，裁定与落点此前只散落在 taskbook / progress / `docs/SCRIPT_FORMAT.md`，此处统一回写。§7/§8/§9 的就地销项与状态见上文。

**七类清单**（落点 `common/src/main/java/com/immersivecinematics/immersive_cinematics/camera/transition/`）：
- `CameraTransitionLayer`——过渡层本体（状态机 / 窗口 / 分参数 / 打断与链式；`blendInto` 逐帧零分配）
- `TransitionBlend`——混合公式单源（yaw / roll 最短路径、zoom 几何域）
- `TransitionSpec`——过渡声明归一（六通道模式 / 时长 / 曲线 + 打断；窗口口径单源）
- `TransitionParams`——分参数声明解析
- `TransitionMode`——通道模式（blend / hold / cut）
- `TransitionInterrupt`——打断规则（SNAP / BLEND）
- `TransitionFrame`——层输出载体（整帧六参数）

**逐项落点**：
- 分参数：`TransitionMode` + `TransitionParams` + `TransitionSpec.build`（未声明通道按片段级缺省补齐：blend + `transition_duration` + 恒等曲线）。
- 曲线：`TransitionSpec.instantiateCurves`（走 math 模型层 `MathModel` / `MathModelInstance`，不重造曲线数学；缺省恒等 = 线性斜坡）。
- 打断：`TransitionInterrupt`（缺省 BLEND = 从当前混合中间态续接；SNAP = 硬切到新目标）；schema 缺省 `transition_interrupt: "blend"`（`TrackSchemas`）、`Clip.getTransitionSpec` 同口径。
- 链式：`CameraTrackPlayer.transitionPairAt`（后开始的过渡优先，接管进行中的过渡）；A→B→C 为 blend 的自然延伸，时长重新起算。

**验收证据**：
- legacy morph 基线对拍：**2403 行逐字节**（`E:/tmp/b18/head.out` vs `new.out`，同 md5 `89ad5773…`；validator 输出 301 行同 md5 `96a1f432…`，`ab.diff` / `validator-ab.diff` 均 0 字节）。
- 探针 **51/51**（分参数 / 曲线 / 打断 blend·snap·链式 / 零分配）——**探针 stdout 未归档**：`E:/tmp/b18/out/probe-out` 仅存 `.class`，源码在 `E:/tmp/b18/probe`（`FeatureProbe.java` / `AllocDiag.java` / `ParseNegProbe.java` / `AbProbe.java`）。51/51 为任务书 / 提交记录口径，当前无法从归档复核。
- 实机抽验：**部分已验（2026-10-11，T17）**——① morph 叠化 / ② 分参数 / ③ 曲线实机跑通并逐帧定量；④ 打断 / ⑤ 链式**未验**（原因见下「实机抽验」小节「未验项」）。详见下节。

> 口径说明：本节按当前工作树复核（2026-10-11）。上述「未归档 / 未验」两项如实标注，不写成已验。

### 实机抽验（2026-10-11，T17）

**做法**：fixture `cinematics/tests/camera/test_camera_transition_b18_a.json`（A 趟：morph / 分参数 / 曲线）部署到 `fabric/run/immersive_cinematics/scripts/`，`ICINEMATICS_CAPTURE=1` + `ICINEMATICS_TRACE=1` 起 client（`sh gradlew :fabric:runClient --args='--quickPlaySingleplayer 新的世界'`；`options.txt` 临时 `maxFps:20` / `enableVsync:false`，跑完逐字节还原）。A 趟实机跑通：脚本自然播完（40.0s / 796 帧转储，≈19.9 fps），无异常、无模型告警。

**逐帧转储**（临时探针：`FrameDriver` 帧末一行 + 临时类 `debug/B18Trace`，开关 = `ICINEMATICS_TRACE`；**跑完已还原、不入库**）逐帧记录统一快照六参数 + 脚本时间。各段关键帧取常值 → 段间过渡的 A / B 两侧为常量，权重可由转储反解：

| 抽验点 | 结果（实测 vs 声明） |
|---|---|
| ① morph 叠化（缺省 0.5s，交界 6） | 权重 vs 线性进度**最大偏差 0.0010**（10 帧） |
| ① morph 叠化（6s 长窗，交界 10.5） | 权重 vs 线性进度**最大偏差 0.0001**（119 帧）；同一权重下 zoom 1.5→2 走几何域（w=0.5197 处实测 1.7419，解析值 1.7421） |
| ② 分参数 `roll`=hold | 整窗 [17.5,23.5) **恒为 30.000**（119 帧）；窗末 t=23.526 才随片段切换为 0 |
| ② 分参数 `fov`=cut | t=20.470 为 50.0 → t=20.519 为 70.0（交界 20.5，帧粒度内瞬时切换） |
| ② 分参数 `yaw` 2s / `pitch` 6s / `position` 1s 窗 | 各自窗口内权重 vs 线性**最大偏差 0.0002 / 0.0001 / 0.0005**（三窗互不相同、同帧并存） |
| ② `zoom` 缺省 1s 窗 | 几何域混合**最大偏差 0.00064**（A=2 → B=1） |
| ③ 曲线 `ease_in`（6s 窗，交界 30.5） | 权重 vs `进度²` **最大偏差 0.0001**（119 帧）；同窗 vs 线性偏差 0.2500（中点样本 t=30.411：实测 0.2354 / 线性 0.4852 / ease_in 0.2354） |
| ④ 打断 `snap` / `blend`、⑤ 链式 A→B→C | **未验**（见下「未验项」） |

**抓帧**（`ICINEMATICS_CAPTURE`；20 fps 下 200 帧/轮 ≈ 10s 一轮，4 轮落在 t=0.000 / 10.069 / 20.139 / 30.193）：归档 `fabric/run/lane-captures-b18/runA/`（raw / composited / frame 各 4 组 + `trace.txt` + `client.log`）。像素侧独立核对（地平线斜率 → roll、地平线高度 → pitch；有效 FOV = `fov/zoom`）：

| 抓帧（转储时刻） | 转储 roll / 像素 roll | 转储 pitch / 像素 pitch |
|---|---|---|
| r2（t=10.069，① 6s 窗内） | 24.281 / 24.457 | 14.281 / 14.23 |
| r3（t=20.139，② 窗内，roll=hold） | 30.000 / 30.069 | 6.807 / 6.73 |
| r4（t=30.193，③ 曲线窗内） | 0.000 / −0.000 | −7.986 / −8.27 |

- 像素 pitch 用可见地面边缘折算（有限距离的地面边缘比真地平线低 ≈ atan(3 格 / 192 区块) ≈ 0.9°）；与转储残差 ≤0.3°，roll ≤0.2°。
- r4 即 ③ 曲线窗的存档帧：转储权重 0.2014，线性应为 0.4489（yaw 192.086 vs 线性 206.9）——画面与转储同源一致。
- 首轮抓帧（`frame.png`，t=0.000）是加载期帧（区块未就绪、全天空），只作时序锚点，不作画面对照。

**未验项（如实记录）**：④ 打断 `snap` / `blend`、⑤ 链式 A→B→C **未做实机**。fixture `cinematics/tests/camera/test_camera_transition_b18_b.json` 已写好并通过 validator（`[OK]`），但第二趟客户端**未起**——本批实机尝试在 A 趟后被收口指令停止（环境负载：用户后台游戏期间不再起客户端、不重跑）。A 趟收尾脚本另有一个自写缺陷（PowerShell 杀进程脚本解析失败 → 客户端在脚本播完后空跑约 14 分钟），已手工杀进程并逐字节还原 `options.txt`、脚本目录与 `lane-captures/` 基线；该缺陷不影响 A 趟数据（转储 / 抓帧均取自脚本 40s 播放窗内）。④ / ⑤ 的实机补验**挂账 H6（实机验证批次）**。
