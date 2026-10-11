# 0.3.6 迟滞：相机参数运动响应（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 公式、参数、JSON、默认值等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [数学函数模型](./math-models.md)
> - [时间插值](./temporal-interpolation.md)
> - [过渡](./transition.md)

---

## 1. 定位（已确认）

本文的“迟滞”指：

> **相机参数的运动响应行为**：缓动、粘滞、阻尼、弹簧、惯性等，让运镜更自然丰富。

**不是**目标选择迟滞、选择器切换策略；那些属于目标策略层。

作用对象是 6 个相机参数：

- 位置：`x, y, z`
- 朝向：`yaw, pitch`
- 附加：`roll, fov, zoom`

作用位置：

- Base Provider：生成基础状态时
- Modifier Chain：修改状态时
- 未来的外部 API / 编辑器预览

---

## 2. 为什么需要（方向）

当前相机参数的变化方式：

- 关键帧插值：linear / smooth / bezier
- 目标切换：selector_switch_smooth
- 呼吸：perlin / sine / trauma
- 其他：直接写值

问题：

- 变化方式分散；
- 很难表达“有重量感的跟随”“粘滞转向”“弹簧停止”；
- 新增行为要改多处；
- 外部模组无法复用同一套运动行为。

方向：统一复用底层数学函数模型，做成可配置、可复用、可组合的相机参数响应。

---

## 3. 行为分类（方向）

### 3.1 缓动

- linear / smoothstep / ease_in / ease_out / ease_in_out / bezier / 自定义曲线

### 3.2 粘滞 / 滞后

- exponential_smooth / viscous_lag / directional_lag / deadband / hysteresis_threshold

### 3.3 阻尼 / 弹簧

- spring / damper / smooth_damp（临界阻尼 / 欠阻尼 / 过阻尼）

### 3.4 惯性 / 动量

- inertia / momentum

### 3.5 振荡 / 噪声（相关）

- sine / perlin / trauma_shake / decay

> 第一版支持哪些行为、参数怎么定执行时再定。

---

## 4. 各通道方向

| 通道 | 方向 |
|---|---|
| position | spring / viscous_lag / inertia |
| yaw | viscous_lag / spring / directional_lag |
| pitch | viscous_lag / spring |
| roll | easing / damped shake |
| fov | smoothstep / viscous_lag |
| zoom | smoothstep / spring |

注意角度环绕、数值域、安全范围。

---

## 5. 与覆盖链 / 数学模型的关系（方向）

```text
Base Provider 产出目标值
  → 迟滞 / 数学模型计算实际值
  → CameraState 基础状态

Modifier Chain 修改状态
  → 也可调用迟滞 / 数学模型
  → 最终状态
```

- 数学模型负责“数学函数怎么定义和复用”
- 迟滞负责“相机参数怎么自然变化”
- 覆盖链负责“谁提供 / 怎么合成”
- 渲染钩子只读最终状态

---

## 6. 可能的问题

- 第一版支持哪些行为
- 各通道默认行为
- 参数命名 / 单位 / 范围
- 与关键帧插值的边界
- 模型定义放在哪一层（片段 / 关键帧 / 脚本级）
- 状态生命周期与重置时机
- dt 语义与帧率一致性
- 与过渡、时间插值的边界
- 编辑器如何配置和预览
- 外部 API 开放时机
- 命名：迟滞 / 运动响应 / 镜头响应

---

## 7. 待定

- 行为清单
- 默认值
- 参数 schema
- 状态管理
- 编辑器 UI
- API

---

## 8. 落地顺序（方向）

1. 定义行为分类和参数方向
2. 复用底层数学模型实现基础行为
3. 接入 6 个相机通道
4. 接入 Base Provider / Modifier Chain
5. 编辑器 UI
6. 内部稳定后考虑外部 API

---

## 定稿与落地记录（2026-10-11，B19）

### 定稿：第一版行为清单 / 参数 / 命名（§6、§7 待定项收口）

| 项 | 结论 |
|---|---|
| 第一版行为 | **三型**：`exponential_smooth`（缓动·粘滞，`tau` 即粘滞常数）、`spring`（阻尼弹簧，`stiffness`/`damping`/`initial_velocity`）、**`inertia`（惯性，本批新增）**；振荡 / 噪声不新增（`BreathDisturbance` 已在，不重复造） |
| 不入第一版 | `momentum` / `viscous_lag` / `directional_lag`（第二批，按调用方需要）；关键帧级模型强度（`math-models.md` 定稿 6.2「v1 不做」）；编辑器 UI（步骤 5）；外部 API（步骤 6，只留位） |
| 各通道默认 | **默认关**：六个 `motion_*` 字段缺省 `null` = 不接模型 = 逐位不改变现状，作者显式启用。启用时的**建议起点** = `exponential_smooth`（`tau` 缺省 0.15）统一六通道（可预测、无过冲）；通道差异化（position / yaw 用 `inertia`、roll 用 `spring` 小阻尼等）只写文档为**建议**，不作缺省——见 `docs/SCRIPT_FORMAT.md`「相机参数运动模型」 |
| 参数命名 / 单位 / 范围 | 随模型层 `FieldDef`（`math-models.md` 定稿 6.1），本层不另立命名；单位 = 度（角度）/ 秒（时间）/ 归一化（曲线输入）；范围单源在各模型实现（定稿 5） |
| 层命名 | **迟滞**（代码侧 = `motion_*` 字段 + `math` 模型层 + `camera/source/LaneMotionStepper` 接入面；不引入「运动响应 / 镜头响应」新词） |
| 模型定义落层 | clip 级（`math-models.md` 定稿 6.2，引用不重开） |

### `inertia` 语义与边界（本批新增模型）

- 定义：**阻尼基准随输入运动**的二阶追踪 —— `e'' = −k·e − 2ζω·e'`，`e = x − target`、`e' = x' − target'`（`ω = √k`）；`target'` = 输入变化率估计（ANGLE 取最短角差），且不超过上一步估计的幅度 = 跳变不注入冲量；参数名 / 缺省 / 范围与 `spring` 一致。
- 与 `spring` 的边界（唯一差别 = 阻尼项基准）：`spring` 取绝对速度 → 跟随匀速目标有稳态滞后 `2ζv/ω`；`inertia` 取相对速度 → **稳态零滞后**，动量集中在起步（慢）与停止（过冲后回稳）；输入静止（`target' = 0`）时两者同式。
- 与「粘滞」的边界：一阶形态「输入变化率驱动的动量延续 + 静止衰减」（`x' = v`、`v' = (target' − v)/τ`，播种 `x = target`）数学上**等价于 `exponential_smooth`（同 τ）**——故不另立该形态，粘滞行为由 `exponential_smooth` 承担（注记见 `math-models.md` 定稿 7.1）。
- 位置不连续（cut 过渡 / 循环回卷 / 目标切换）直通：输出随输入跳变、速度（动量）保留并随后衰减。
- 实测（一次性探针 `E:/tmp/b19`，2026-10-11；参数 = 缺省 `stiffness 8 / damping 0.75`，输入 = 匀速 20/s 后停止）：稳态滞后 `inertia` ≈ −0.09° vs `spring` ≈ 10.57°；停止过冲 3.18° vs 0.46°；起步最大滞后 3.12° vs 10.90°。

### 落地记录（§8 六步）

| 步骤 | 状态 | 落点 |
|---|---|---|
| 1 定义行为分类和参数方向 | 完成（上一节定稿） | 三型 + 默认关 + 参数随 `FieldDef` |
| 2 复用底层数学模型实现基础行为 | 完成 | 新增 `math/InertiaModel` + `script/schema/MathModelRegistry` 注册（`inertia` 排在 `spring` 后）；不重造数学 |
| 3 接入 6 个相机通道 | 复用 B16-c 接入面（不新增接入点） | `camera/source/LaneMotionStepper`（每 lane 每帧单次 step；实例粒度 = 播放实例→lane→通道） |
| 4 接入 Base Provider / Modifier Chain | 随 B1 / B16-c 已定：Base 产目标值 → 迟滞算实际值 → `CameraState`；Modifier 层可调用同一模型层 | 本批无新增接线（只加模型类型与文档） |
| 5 编辑器 UI | 不做（归编辑器阶段） | — |
| 6 外部 API | 只留位不开放（`MathModelRegistry.register` 不进 `api/`） | — |
| 收口 | 默认关 + 未改路径基线对拍 + 帧率无关实测 | 见下 |

### 验收记录（2026-10-11）

- 编译：`sh gradlew -Dnet.minecraftforge.gradle.check.certs=false :common:compileJava` → BUILD SUCCESSFUL（含新增 `math/InertiaModel` 与注册表改动；同批次并发改动一并编译通过）。
- 未改路径基线对拍：既有类型（10 型 + 参数变体 + 回落路径）HEAD 版类 vs 新版类 **raw-bits 逐字节一致**（46 行）；集成路径（`LaneMotionStepper` + 既有类型）raw-bits 转储逐字节一致；validator stdout（tests + release）逐字节一致，唯一差异 = 新增 fixture `test_camera_motion_inertia.json` 自身（HEAD 版类不识别 `inertia` → 报 4 条「type 未注册」；新版类 `[OK]`，全仓 issue 数 3 → 2，**0 新增**）。
- 零分配：`inertia` / `spring` / `exponential_smooth` 各 1e6 次 step 分配增量 = **0 字节**；求值路径无 `new` / 装箱。
- 确定性：同定义两实例同输入逐位一致；`reset()` 后重放逐位一致；ANGLE 域跨 ±180° 走最短路径。
- dt 分割（1/60 vs 1/120 / 1/240 / 1/300，分段线性输入）：`inertia` 最大差 0.26°（同量级 `spring` 0.14°、`exponential_smooth` 0.13°）。
- 数据层 / GL / validator：`icv`（tests + release）、`icv2`、`icgl` 全绿。
- 帧率无关实测（实机）：见下节。

### 帧率无关实测（2026-10-11）

**方法（headless，真接入面）**：驱动 `LaneMotionStepper`（B16-c 冻结的六通道唯一接入点）——同一分段线性目标函数（0~1s 静止 → 1~2s 匀速 60/s → 2~3s 静止 → 3~4s 反向），四档 dt 序列（60 / 120 / 240 / 抖动 = 1/240 整数倍的不规则切分，模拟解锁帧率的帧时分布），在公共采样点（1/60 网格 × 241）比对六通道输出（探针 `E:/tmp/b19/probe/RateProbe.java`）。

| 档位组合 | 最大差（通道 = yaw，度） |
|---|---|
| **默认关（无 `motion_*`）** | **0.000e+00**（60 / 120 / 240 / 抖动四档逐位一致） |
| 六通道 `inertia`（缺省参数） | 120 vs 60：4.91e-1；240 vs 60：7.30e-1；抖动 vs 60：7.30e-1 |
| 六通道 `spring` | 2.30e-1 / 3.45e-1 / 2.53e-1 |
| 六通道 `exponential_smooth` | 2.43e-1 / 3.66e-1 / 2.78e-1 |
| 六通道 `inertia`（`stiffness` 3） | 4.95e-1 / 7.38e-1 / 7.38e-1 |

- **默认关路径逐位零差**：不接模型的通道在任何 dt 序列下逐位一致（未改路径不引入任何帧率相关行为）。
- **三型模型同为 kink 采样量级**（0.2° ~ 0.7°）：来源 = 分段线性输入在关键帧折点处的采样（`dt` 越大，折点落在帧内越晚）+ `inertia` 跳变抑制的首帧前馈延迟；三者同量级 = 与既有模型同族，非新模型特有缺陷。
- 结论：按验收口径「采样点输出差 ≤ 1e-9（相对）**或观感一致**」——`inertia` 取后者（≤ 0.74° 于 60° 量程，肉眼不可辨）；`dt = 0`（暂停）/ 首步 / `reset()` 语义与既有模型逐位一致。

**实机（options.txt `maxFps`）**：`maxFps` 三档冒烟由主代理在收口时执行（见 `000.md` B19 行验收「实机抽验」）；本任务的实机部分只做「脚本可跑 + 无异常 + 抓帧」冒烟（见下「实机冒烟」）。

### 实机冒烟（2026-10-11）

**三档帧率各一次**（`options.txt`：`maxFps` 60 / 120 / 260（解锁）+ `enableVsync:false`；跑完还原原始值 `maxFps:60` / `enableVsync:true`）：部署 fixture `test_camera_motion_inertia.json`（改名 `b19_inertia_smoke.json`）到 `fabric/run/immersive_cinematics/scripts/`，`ICINEMATICS_CAPTURE=1` 起 client，login 触发自动播放。

| 档 | 结果 |
|---|---|
| 60 | `脚本播放开始: 相机测试-参数运动模型（惯性）(总时长 10.0s)` → `NATURAL_END elapsed=10.00` → `fully complete`；5 轮抓帧（首帧 + 每 200 帧）中 3 轮落脚本活跃期 |
| 120 | 同上，5 轮全部落脚本活跃期（`cam0` raw / composited / 窗口终帧各 5 张） |
| 260（解锁） | 同上，5 轮全部落脚本活跃期；无异常 / 无模型告警 |

- 三档均**无异常、无模型相关告警**，脚本自然播完（六通道 `inertia` 在真机路径上工作）。
- 抓帧归档：`fabric/run/lane-captures-b19/{run60,run120,run260}/`；`lane-captures/` 已还原用户基线（逐文件一致）。
- **跨帧率数值比对不在实机做**：捕获节奏 = 帧计数（每 200 帧一轮），60 / 120 / 260 三档的轮次落在**不同脚本时刻**，且无逐帧相机状态日志 → 实机只能做「能跑 + 观感」；**帧率无关的数值证据 = 上节 headless 集成路径对拍**（真接入面 + 四档 dt 序列 + 公共采样点）。实机 `maxFps` 三档的采样点比对归主代理收口（`000.md` B19 行「实机抽验」）。

---

## 9. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [数学函数模型](./math-models.md)
- [时间插值](./temporal-interpolation.md)
- [过渡](./transition.md)

---

## 事实核查（2026-10-07）

> 核查依据：本仓库源码（唯一事实依据）。§1–§9 设计内容未改动。

### ① 核实为真
- **目标切换 `selector_switch_smooth`**：确为关键帧字段 `selector_switch_smooth`（float，默认 0.0）——`script/schema/TrackSchemas.java:74`；被 `CameraTrackPlayer.selectorPolicy(Keyframe,String)` 读入 `SelectorPolicy.switchSmooth`（`script/CameraTrackPlayer.java:1152`，record 定义 :90），在 `smoothTargetPoint(...)`（:1197）按秒做目标点平滑过渡。调用角色含 follow / look_at / look_at_target / facing_origin（:335/:432/:646/:732）。
- **"其他：直接写值"路径**：`CameraPath.setPositionDirect(Vec3)`（`camera/CameraPath.java:37`）、`CameraProperties.setAllDirect(yaw,pitch,roll,fov,zoom)` / `setYawDirect`…（`camera/CameraProperties.java:77-99`，内部 `AnimValue.setDirect` 瞬移无过渡）。调用点：`CameraTrackPlayer.java:312-313`、:908-909；`CameraManager.java:72-74`、:319-321、:602；`FlightController.java:294-297`。

### ② 已修正 / 需澄清
- **"关键帧插值：linear / smooth / bezier"** → 实为两类不同字段，原表述把 `bezier` 混入 interpolation：
  - **0.3.6 起：clip 级 `interpolation` 字段已整体移除**——`ScriptValidator` 对 `clip.has("interpolation")` 报错拦截（提示删除该字段），运行时关键帧之间统一**匀速线性**，缓动由编辑器烘焙成显式关键帧写入脚本（见 `script-model.md` §4-1）。因此「`interpolation` 枚举 `{linear, smooth}`」这一表述已不适用（0.3.6 前该枚举仅被 `OverlayTrackPlayer` 读取，`smooth` = Catmull-Rom 样条，作用于 overlay 浮点通道；CAMERA 片段声明但不读）。
  - `bezier` 从来不是 interpolation 取值，而是相机**位置路径**字段 `curve`（`bezier_curve` 类型，`TrackSchemas.java`），经 `PathStrategy`（`linear`/`bezier`）求值；朝向按匀速线性插值（`KeyframeInterpolator`）。
  - 冲突裁决：`hysteresis.md` 最后修改 **2026-09-15T00:34:21+08:00**；`TrackSchemas.java` 最后修改 **2026-09-17T13:38:01+08:00** → 代码更晚，以代码为准（当时枚举 `{linear, smooth}`）；**该字段 0.3.6 已整体移除，本条仅存历史意义**。
- **"呼吸：perlin / sine / trauma"** → 实为 4 种：`perlin` / `perlin_axis` / `sine` / `trauma`（`script/BreathDisturbance.java` 常量 `TYPE_PERLIN`/`TYPE_PERLIN_AXIS`/`TYPE_SINE`/`TYPE_TRAUMA`），由 clip 字段 `cam_breath_type` 选择；参数 `cam_breath_intensity`/`seed`/`speed`/`trauma`/`decay`。（`BreathDisturbance.java` 最后修改 2026-08-18T22:41:19+08:00，早于本文；属枚举补全，非行为冲突。）

### ③ 补全
- 现有"分参数过渡"原语：`CameraProperties.AnimValue.setTarget(v, dur)` 每参数独立时长/进度（`CameraProperties.java:29-63`）；`CameraManager.stageTargetPosition/Yaw/Pitch/Roll/Fov/Zoom(...)` + `commitStagedState()`（`CameraManager.java:353-…`），当前仅编辑器预览（staged）使用。
- `BezierPathStrategy` 为有状态（`lutCache`），与无状态 `KeyframeInterpolator` 并存——对应 §3 无状态/有状态分离方向的现状挂点。

### ④ 未验证
- 无。
