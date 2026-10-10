# 0.3.6 数学函数模型（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 接口、字段、公式、JSON、注册细节等执行时再定
> - **定稿（2026-10-10）**：§5 十二问 / §6 七项已逐条定稿（§5/§6 就地标注），见文末〈定稿（2026-10-10）〉
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [时间插值](./temporal-interpolation.md)
> - [过渡](./transition.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位

建立**通用数学函数模型库**：

- 一个模型定义可以被模组内多个地方调用；
- 模型不绑定相机、轨道、UI 或具体功能；
- 数据驱动，内部优先，外部 API 顺带。

数学函数模型是底层能力，服务于：

- 相机参数运动
- 目标选择策略
- 音频参数
- UI / HUD 动画
- 编辑器预览
- 外部模组扩展

---

## 2. 已确认方向

1. **定义与实例分离**
   - 模型定义是数据；
   - 每个调用点有独立实例和独立状态。

2. **一处定义，多处调用**
   - 同一个模型定义可被多个通道 / 片段 / 模组引用；
   - 不共享可变状态。

3. **无状态与有状态分离**
   - 无状态：输出只由输入和时间决定；
   - 有状态：输出依赖历史，需要 dt 和状态。

4. **可组合**
   - 支持串联、并联、混合、映射。

5. **可序列化**
   - 模型定义可以用 JSON 描述；
   - 脚本 / 编辑器 / 外部 API 共用。

6. **确定性**
   - 相同输入 + 相同 dt 序列 + 相同 seed → 相同输出。

7. **无全局可变状态**
   - 状态属于实例，避免跨脚本污染。

8. **低开销、可回退、版本化**

---

## 3. 模型分类（方向）

### 3.1 曲线 / 缓动

- linear / smoothstep / ease / bezier / 分段曲线 / 自定义曲线

### 3.2 动态响应

- exponential_smooth / smooth_damp / spring / damper / viscous_lag / inertia / momentum

### 3.3 振荡 / 噪声

- sine / perlin / simplex / value_noise / trauma_shake / seeded_random / decay

### 3.4 非线性变换 / 响应

- deadband / hysteresis_threshold / directional_lag / stick_slip / backlash
- clamp / remap / scale / offset / curve_map

### 3.5 组合

- chain / blend / add / multiply / min / max / select / switch

### 3.6 输入源

- constant / time / keyframe / entity_attribute / external_input / model_input

> 具体模型清单、参数、接口、JSON 结构执行时再定。

---

## 4. 与调用方的关系

```text
数学函数模型（底层）
  ├─ 相机参数运动（迟滞 / 缓动 / 阻尼）
  ├─ 目标选择策略
  ├─ 音频参数
  ├─ UI / HUD 动画
  └─ 外部模组扩展
```

调用方只提供输入和上下文，不关心模型内部实现。

---

## 5. 可能的问题

> 定稿（2026-10-10）：十二问已逐条定稿或归置，见〈定稿（2026-10-10）〉。

- 模型接口最终长什么样（标量 / 向量 / 角度 / 颜色）　→ **已定**：定稿 1
- 模型实例的粒度（通道 / 角色 / 片段 / 全局）　→ **已定**：定稿 2
- dt 来源与采样策略　→ **已定**：定稿 3
- 状态重置时机　→ **已定**：定稿 4（seek 一项见「定稿 12-1」待拍板）
- 模型定义放在哪一层（关键帧 / 片段 / 脚本级）　→ **已定**：定稿 6.2（clip 级 + 脚本级引用）
- 组合模型的嵌套深度与性能　→ **已定**：定稿 5（深度/节点上限）、定稿 9（零分配与性能口径）
- 模型失败 / 非法参数的回退策略　→ **已定**：定稿 5
- 是否需要模型状态持久化　→ **已定：不做**：定稿 4、11
- 是否支持表达式 / 函数图　→ **已定：不做**：定稿 11
- 编辑器如何呈现（类型、参数、预览）　→ **已定归属**：定稿 10
- 外部 API 的注册与版本兼容　→ **已定时机与口径**：定稿 10
- 命名：数学函数模型 / 运动模型 / 响应模型　→ **已定**：定稿 10 末条

---

## 6. 待定

> 定稿（2026-10-10）：七项已全部定稿，见〈定稿（2026-10-10）〉。

- 统一接口形态　→ **已定**：定稿 1
- JSON schema　→ **已定**：定稿 6
- Registry 设计　→ **已定**：定稿 6
- 内置模型清单　→ **已定**：定稿 7（第一批 + 第二批）
- 模型状态生命周期　→ **已定**：定稿 2、4
- 编辑器 UI　→ **已定归属**：定稿 10
- 外部 API 开放时机　→ **已定**：定稿 10

---

## 7. 落地顺序（方向）

1. 定义模型分类和统一接口
2. 实现基础模型（曲线、动态响应）
3. 建立 Registry 和 JSON 描述
4. 接入第一个调用方（相机参数运动）
5. 建立“一处定义，多处调用”的复用机制
6. 接入编辑器
7. 内部稳定后开放外部 API

---

## 定稿（2026-10-10）

> 定稿范围：§6 七项 + §5 十二问（§5/§6 已就地标注）。产出 = B16-b / B16-c 任务书可直接引用的接口、Registry、JSON、清单与验收口径；每条结论标注依据段落，代码事实标注 `类:行`。
> 依据简称：本文 §1–§3；`camera-state-plan.md` §5/§6；`clock-abstraction.md` §3.1–§3.3；`script-model.md` §4-1（方案 E）/§5；`templates.md` §2；`next-phase-batches.md` B16–B19 行 / Q17 / P-2；`implementation-progress.md` 通用原则（2026-10-08/09 用户裁决）。
> 术语：**定义**（definition，不可变数据，可跨调用点共享）；**实例**（instance，每调用点一份，持有状态）；**调用点** = 一条 lane 的一个相机通道。

### 定稿 1｜统一接口形态（§5-1、§6-1）

**结论**：单接口 + 定长 double 通道 + 值域标记；定义 / 实例分离（§2.1、§2.3）；模型层零依赖（只 `java.*`）。

```java
package com.immersivecinematics.immersive_cinematics.math;   // 落点见「定稿 12-2」

/** 值域语义：决定通道数、环绕规则与组合时的混合方式。 */
public enum ValueDomain { SCALAR, ANGLE, VECTOR, COLOR }

/** 模型定义 —— 不可变，可被多个调用点共享（§2.1/§2.2）；由注册表按 type 名创建。 */
public interface MathModel {
    String type();                    // 注册名（snake_case），与 JSON 的 type 一致
    ValueDomain domain();             // v1：输入/输出同域；异构转换走组合 / 映射模型
    int inputArity();                 // 通道数：SCALAR/ANGLE=1、VECTOR=3、COLOR=4；0 = 无输入源（预留，v1 不实现）
    int outputArity();
    boolean stateful();               // true = 输出依赖历史（§2.3），需要 dt 与实例状态
    MathModelInstance instantiate();  // 定义 → 实例（每个调用点一份）
}

/** 模型实例 —— 每调用点一份；持有该调用点状态；step 零分配。 */
public interface MathModelInstance {
    /** 推进一步（每帧一次）。t = 单调秒；dt = 距上一步秒差（首步 / 暂停 = 0，负值按 0）；in/out 调用方复用。 */
    void step(double t, double dt, double[] in, double[] out);
    /** 状态归位；reset 后首次 step 以输入为初值（无起始瞬态）。无状态实例 no-op。 */
    void reset();
}
```

1.1 **统一方式 = 定长通道 + 值域**：标量 1 / 向量 3 / 颜色 4 通道；`Vec3` 等调用方类型在调用方边界拆装通道——模型层不依赖 MC 类型（无头可测；§1「模型不绑定相机、轨道、UI 或具体功能」）。
1.2 **角度语义（ANGLE）**：单位 = 度（与脚本 `yaw/pitch/roll`、`MathUtil.lerpAngle`、`Mth.rotLerp` 一致）；**差值 / 混合一律取最短路径**（wrap 到 (-180, 180]）；**绝对输出不强制归一**（保持现状：`MathUtil.lerpAngle` 输出可越界，MC 相机接受任意角度）；有状态角度模型跨 ±180° 按最短路径累计误差，不得绕远路。多圈语义（>360°）不在模型层（属关键帧 / 过渡层，`transition.md` §5 列为方向）。
1.3 **VECTOR / COLOR = 逐分量独立状态**（同参数），与相机位置各轴独立响应的现状一致。
1.4 **无状态 / 有状态分离（§2.3）**：`stateful()` 为定义级声明；无状态模型忽略 dt、`reset` 为 no-op；有状态模型的初值来源在实现内声明——v1 第一批 = 「首个输入即初值」；噪声 / 衰减族（后续批次）按定义参数给初值。
1.5 **组合的域** = 子模型域的公共域；不匹配 → 校验期拒绝（定稿 5）。

### 定稿 2｜实例粒度与归属（§5-2、§6-5）

**结论**：实例 = **（播放实例 → lane → 通道）** 一条；定义 = clip 级字段（定稿 6.2）。

| 候选粒度 | 定稿 |
|---|---|
| 全局 | ✗ §2.7 无全局可变状态 |
| 片段 | ✗ 片段只决定**定义**；切换片段不重建实例（状态延续） |
| 角色 | ✗ 角色 / 目标解析属调用方（§1） |
| **通道（采用）** | ✓ 实例归属「一条 lane 的一个通道」，与 `CameraLane` 每 lane 一份快照对齐（`CameraLane.java`：state + clip + clipLocalTime），与 B19 六通道一一对应（`hysteresis.md` §4 六通道表） |

- 六通道 = `position`（VECTOR ×1）+ `yaw` / `pitch` / `roll` / `fov` / `zoom`（各 ×1）→ 每 lane 至多 6 实例。
- 多实例并行（`parallel-playback.md`）：实例随播放实例 / lane 各自独立，跨脚本不共享（§2.2）。
- 「一处定义，多处调用」（§2.2）分两层：Java 层 = 同一定义对象被 N 个通道 `instantiate()`（B16-b 达成）；数据层 = 脚本级 `models` 块 + `{"ref": …}`（§7 步骤 5，定稿 6.3）。

### 定稿 3｜dt 来源、采样与帧率无关性（§5-3）

**结论**：dt 由调用方从 `Clock` 求差；模型层不读时钟；每渲染帧一次 step；模型内部自管子步进。

- **来源**：`clock-abstraction.md` §3.1 的一等 `Clock`（单调秒、运行 double）；调用方每帧取 `t = 实例 elapsed`（§3.3「一切对轨道 / 渲染 / 玩家的分发时间一律是 elapsed」），`dt = t − t_prev`。模型层不接触 MC / 时钟（可无头测）。
- **dt 语义**：double 秒；`dt = 0` 合法（首步 / 暂停帧）→ 有状态模型保持上一步输出；负值按 0 + warn（限频）；`dt > max_dt`（v1 常量 0.25s）→ 按 `max_dt` 推进（防大 dt 造成弹簧过冲；量级参照原版单帧补 tick 上限 `Math.min(10, i)`，`Minecraft.java:1003`）。
- **采样策略**：调用方每渲染帧一次（与现状相机帧驱动一致：`CameraTrackPlayer.writeAttributes` 每帧求值 + 直写，`CameraTrackPlayer.java:1032`、`:1057-1058`）；**不引入调用方侧固定步长累加器**；声明步长相关的模型在模型内部子步进——v1 第一批全部为连续时间闭式解（无需子步进）。
- **帧率无关性验收口径**：① 同 dt 序列 → 逐位一致（定稿 9）；② **同总时长、不同 dt 分割** → 采样点输出差 ≤ 1e-9（相对）——`exponential_smooth` 取 `exp(-dt/τ)` 逐段相乘即精确，`spring` 用二阶系统三种阻尼情形的解析步进；③ 游戏内 60 / 120 / 解锁帧率对拍由 B19 实机验收（`next-phase-batches.md` B19 行「帧率无关（dt 语义与帧率一致性实测）」）。

### 定稿 4｜状态生命周期（§5-4、§5-8、§6-5）

| 事件 | 定稿 |
|---|---|
| 片段切换，定义不变 | 状态**延续**（迟滞 / 弹簧跨片段连续） |
| 片段切换，定义变化 | **重建实例**（= 重置）；定义相等性按「type + 参数键值」比较（编辑器保存 / 增量替换不因无关字段重置） |
| 定义变为 null（片段未配模型） | **销毁实例**；再次启用 = 新实例（以当前输入为初值） |
| 脚本退出 / 实例停止 | 实例随 lane 销毁，不保留 |
| seek（`editor.seek` / `setTime` / `alignTime`） | **全实例 `reset()`**，从 seek 点重新推进（与「编辑器 seek = 硬定位」一致，`transition.md` 事实核查①）——见「定稿 12-1」 |
| 暂停（游戏暂停 / 失焦） | 时钟冻结 → `dt = 0` → 状态保持，不重置 |
| 多实例并行 | 各播放实例的实例互不影响（§2.2 / §2.7） |
| 持久化（§5-8） | **不做**：状态属运行时实例，不落盘、不跨实例 / 跨局（§2.7 + `script-model.md` §5） |

- 创建 / 销毁只发生在**片段边界**（惰性创建于该通道首次带定义求值），不在逐帧路径（零分配前提）。

### 定稿 5｜失败回退（§5-6、§5-7）

**总口径**：非法 → **warn（限频）+ 回落 `linear`（恒等 = 无效果）**——模型是值的「变换」，「最简」= 不改变值；与 `PathStrategies.get`「未注册 → warn + 回落默认」同模式（`PathStrategies.java:69-79`），默认类型同为 `linear`（`PathStrategies.DEFAULT_TYPE`）。

| 情形 | 处置 |
|---|---|
| 未注册 / 缺失 type | 解析期 warn 一次 + 该通道回落 `linear`；validator 记 issue（不阻断加载） |
| 未知参数 / 类型不符 | 忽略该参数 + warn 一次（其余参数照常） |
| 参数越界 | 钳制到模型声明的范围 + warn 一次（范围常量在模型实现内，单源） |
| 参数缺失 | 用 `FieldDef` 缺省（定稿 6.2） |
| 非有限值（数据入口 NaN / ±Inf） | 回落该参数缺省 + 限频告警——沿用 `MathUtil.sanitizeFloatLogged` / `Clip.getFloat` / `Keyframe.getFloat` 既有口径（`multi-instance-black-screen.md` 的 NaN 级联教训） |
| 运行时非有限值（输入 / 内部状态） | 该实例 `reset()` + 本帧输出 = 输入 + warn 一次 |
| 嵌套过深 / 节点过多 | 上限 **深度 ≤ 8、单定义节点 ≤ 32**；validator 拒绝；运行时（未过校验的脚本）整链回落 `linear` + warn |
| 组合中某子模型无效 | 仅该子模型回落 `linear`（= 该层无效果），链其余照常 |
| 回退的确定性 | 与正常路径同样确定性（同输入 → 同结果）；warn 限频不影响数值 |

- 上限取值理由：编辑器呈现深度、校验成本、脚本体积；8 层 / 32 节点覆盖「曲线 → 动态 → 混合」常见组合（B18 / B19 场景 ≤ 3 层）。

### 定稿 6｜Registry 与 JSON schema（§5-5、§6-2、§6-3）

**结论**：参数声明与 `script/schema` 同源（`FieldDef`，`templates.md` §2）；注册表登记「type →（参数 `FieldDef` 表，工厂）」；模型定义 = **clip 级字段**（缺省 null = 无模型）。

6.1 **两层落点**（依赖方向 `script → math`，`math` 零依赖）：

| 层 | 落点 | 内容 |
|---|---|---|
| 模型层（机制） | 新包 `...immersive_cinematics.math`（只 `java.*`） | `MathModel` / `MathModelInstance` / `ValueDomain` / `ModelParams` + 第一批实现 + 角度 helper |
| 绑定层（数据 → 定义） | `script/schema/MathModelRegistry`（与 `FieldDef` 同包，避免 math ↔ script 环） | 注册 / 创建（缺省填充 + 类型归一 + 未知忽略 + 未注册回落）/ `types()` / `params(type)` |

```java
public final class MathModelRegistry {                    // script/schema，FieldDef 同包
    public static final String DEFAULT_TYPE = "linear";   // 未注册 / 非法 → 回落（同 PathStrategies）
    public static void register(String type, Map<String, FieldDef> params,
                                Function<ModelParams, MathModel> factory);
    public static MathModel create(String type, Map<String, Object> raw);   // 已归一（缺省 / 钳制 / 未知忽略）
    public static Set<String> types();                    // 注册顺序只读视图（编辑器类型下拉）
    public static Map<String, FieldDef> params(String type);   // 编辑器表单 + 校验共用
}
```

6.2 **JSON 形态**（与既有 `curve` 同风格：`type` + 参数平铺）：

```json
"motion_yaw": { "type": "spring", "stiffness": 8.0, "damping": 0.75 }
```

组合族把子定义内联为对象（同样计入定稿 5 的深度 / 节点上限）：

```json
"motion_yaw": { "type": "chain", "models": [
    { "type": "smoothstep" },
    { "type": "spring", "stiffness": 8.0, "damping": 0.75 }
] }
```

- **落层 = clip 级**（§5-5 的答案）：先例 = `curve`（位置路径形状，clip 级，`TrackSchemas.java:46`）与 `rgb_curve`（曲线定义 clip 级 + 强度关键帧级，`TrackSchemas.java:64`）；理由：模型是「片段 / 区间的响应机制」，关键帧只承载随时间变化的采样值；关键帧级重复定义会放大脚本体积、破坏状态归属（定稿 2、4）。
- **关键帧级参数（例外）**：需逐帧变化的标量（如模型强度）后续按「定义 clip 级 + 强度关键帧级」加（形式同 `rgb_curve` 强度）；v1 不做。
- **脚本级复用块（§7 步骤 5）**：根级可选 `models: { "id": {…} }` + 引用点 `{"ref": "id"}`，解析期展开为同一定义对象（数据层「一处定义，多处调用」）；跨脚本 / 模组级复用 = 外部 API 阶段（定稿 10）。
- **脚本 schema 侧**：CAMERA clip 新增 6 个 clip 级字段 `motion_position` / `motion_yaw` / `motion_pitch` / `motion_roll` / `motion_fov` / `motion_zoom`，`FieldDef("model", null, false, List.of(), "motion")`；`"model"` 为新增字段类型（值 = 对象），解析走 `ScriptParser.parseFieldBySchema` 的 `switch (def.type())` 新分支（`ScriptParser.java:357-374`；既有先例：`position` → `PositionData`、`bezier_curve` → `BezierCurve`、`color_curve` → `ColorCurve` 均存解析后的对象），校验走 `MathModelRegistry`（type 已注册 / 参数名已知 / 深度节点上限），导出走 `SchemaExporter` 新增 `models` 段（类型 → 参数 schema；编辑器表单直接消费 `schema.get` 链路，`SchemaExporter.java`、`WebEditorApi.java:206-211`）。

### 定稿 7｜第一批模型清单与接入面（§6-4）

7.1 **B16-b 第一批**（§7 步骤 2「曲线、动态响应」+ 组合最小闭环）：

| 类 | type 名 | 参数（v1 建议值） | 有状态 |
|---|---|---|---|
| 曲线 | `linear` | —（默认类型 / 回退目标；恒等） | 否 |
| 曲线 | `smoothstep` | — | 否 |
| 曲线 | `ease_in` / `ease_out` / `ease_in_out` | — | 否 |
| 曲线 | `cubic_bezier` | `x1` / `y1` / `x2` / `y2`（CSS cubic-bezier；P0=(0,0)、P3=(1,1)） | 否 |
| 动态响应 | `exponential_smooth` | `tau`（0.15s） | 是 |
| 动态响应 | `spring` | `stiffness`（8.0）/ `damping`（0.75）/ `initial_velocity`（0） | 是 |
| 组合 | `chain` | `models`（子定义数组） | 任一子有状态 |
| 组合 | `blend` | `a` / `b`（子定义）+ `weight`（0.5） | 任一子有状态 |

- 域：曲线 / 动态响应族对任意域可用（VECTOR / COLOR 逐分量、ANGLE 按最短路径）；组合族要求子模型域一致（定稿 1.5）。
- 曲线命名与编辑器既有预设一致（`editor/src/operations.ts` 的 `EASING_PRESETS`：linear / ease_in / ease_out / ease_in_out），`smoothstep` 对齐 `MathUtil.smoothstep`。
- `damper` / `smooth_damp` **不独立成类型**：与 `spring` 是同一二阶系统，`damping` 参数覆盖临界 / 欠 / 过阻尼；预设归编辑器（口径：调配归编辑器）。
- **曲线族适用边界（重要）**：曲线族服务**运行时**求值（过渡权重、组合、动态响应），**不用于关键帧段内缓动**——后者已定「编辑器烘焙、运行时线性」（`script-model.md` §4-1 方案 E；`ScriptValidator.java:313-316` 已拦 `interpolation` 字段；`OverlayTrackPlayer.java:214-216` 同口径）。
- **第二批**（B16-b 之后、按调用方需要）：动态响应 `viscous_lag` / `directional_lag` / `inertia` / `momentum`（B19-b 前）；非线性 `clamp` / `remap` / `scale` / `offset` / `deadband` / `hysteresis_threshold` / `backlash`；组合 `add` / `multiply` / `min` / `max` / `select` / `switch`；振荡 / 噪声 `sine` / `value_noise` / `seeded_random` / `decay`（**复用 `BreathDisturbance` 的数学实现**，不迁移其调用点——`BreathDisturbance.java` 类注释的「同 seed + 同 globalTime → 同抖动」口径不变）；曲线 `smooth5` / 分段曲线（编辑器烘焙的数据化形状）。
- **输入源族（§3.6）v1 不实现**：输入由调用方提供（§1「调用方只提供输入和上下文」）；接口以 `inputArity() = 0` 预留无输入源模型。

7.2 **B16-c 接入面**（第一个调用方 = 相机参数运动，与 B19 共享）：

- 接入点 = 六参数最终目标值的**唯一写入口**：关键帧 / morph 混合（`renderMorph`，`CameraTrackPlayer.java:304`）→ **每通道模型 step** → 呼吸叠加（Modifier 语义：`camera-state-plan.md` §5.2 把「呼吸扰动」列在 Modifier 层）→ 直写（`CameraTrackPlayer.java:1032` / `:1057-1058`）。依据：`hysteresis.md` §5「Base Provider 产出目标值 → 迟滞 / 数学模型计算实际值 → CameraState 基础状态」。
- **每 lane 每帧单次 step**：`capture` 路径（非顶层 lane / morph 两端捕获）与顶层写入共享同一次求值结果，禁止同一实例一帧推进两次。
- 状态跨片段延续（定稿 4）；定义取自本帧该 lane 的生效片段，重叠窗内以进入片段的定义为当前定义。
- **默认关**：6 字段缺省 null → 逐位不改变现状（旧脚本零回归，B16 验收）。
- B19 复用同一接入面（只加模型类型与通道默认建议），不再造第二个接入点（`next-phase-batches.md` B16-c 行「与 B19 共享」）。

### 定稿 8｜调用方边界与依赖顺序（§1、§4）

**依赖顺序**（`next-phase-batches.md` 依赖图、Q17）：B16-b → B16-c →（B17 / B18 / B19 各自接）；B18 另依赖 B1，B19 另依赖 B1。

| 层 | 职责 | 不做什么 |
|---|---|---|
| 模型层 `math/` | 值 → 值的机制 | 不 import `camera/` `script/` `mixin/` `client/` `overlay/` `webui/` `control/` `trigger/`（只 `java.*`）；不读时钟；不解析 JSON；不知道相机 / 轨道 / UI（§1） |
| 绑定层 `script/schema/MathModelRegistry` | JSON → 定义；参数 schema；校验 / 导出 | 不做求值 |
| B16-c 相机参数运动 | 六通道接线、实例归属与生命周期、默认关 | 不实现模型数学 |
| B17 时间插值 | tick ↔ 渲染帧采样（prev / current、partialTick）、历史缓冲 / 外推 | 不实现阻尼 / 缓动（`temporal-interpolation.md` §2 对比表、§6「与数学函数模型（阻尼 / 缓动）的边界」） |
| B18 过渡 | A→B 状态变化、分参数、打断；**曲线来自模型层** | 不重造曲线数学（`transition.md` §8 步骤 4） |
| B19 迟滞 | 六通道行为清单 / 默认、接 Base / Modifier；**动态响应来自模型层** | 不重造阻尼 / 弹簧数学（`hysteresis.md` §8 步骤 2） |

- 边界硬规则（可 grep 验收，同 `camera-core-split.md` §〇 风格）：`math/` 只 import `java.*`；「JSON → 定义」只有 `MathModelRegistry` 一个入口。

### 定稿 9｜零分配与确定性验收口径（§2.6、§2.8）

- **零分配（§2.8 低开销）**：求值路径（`step` 及内部）零分配——不 `new`、不装箱、不用 Stream / Iterator、不拼字符串；组合模型中间缓冲在实例化时一次性分配；注册表查表只在解析 / 建实例时发生。**验收**：无头冒烟用 JDK 自带 `com.sun.management.ThreadMXBean#getThreadAllocatedBytes` 测「预热后同线程 1e6 次 step」分配增量 = 0 字节；并 grep 求值路径无 `new` / `String.format` / 装箱。
- **确定性（§2.6）**：① 同定义 + 同输入序列 + 同 dt 序列 + 同 seed → 两次运行逐位一致（`Double.doubleToLongBits` 相等）；② 同定义的两个实例同输入 → 同输出（不共享状态，§2.2）；③ `dt = 0`（暂停）不改变结果；④ dt 分割不变性：同总时长、不同 dt 分割 → 采样点差 ≤ 1e-9（相对，闭式解）；⑤ 声明步长相关的模型必须在注册处显式声明并给出容差（v1 第一批无此类）。
- **口径边界**：确定性承诺 = **同机同 JVM 重放一致**；不承诺跨平台逐位一致（与 MC 自身一致——`Mth` 基于 `Math`，避免 `StrictMath` 代价）。
- **性能口径（§5-6）**：组合上限（深度 ≤ 8 / 节点 ≤ 32）× 6 通道 → 每帧每 lane ≤ 6 × 32 次纯算术求值、0 分配；冒烟记录「六通道 × 8 节点 chain 单步耗时」入档（不作硬阈值）。
- **可回退 / 版本化（§2.8）**：回退见定稿 5；版本化 = type 名与参数名是**数据契约**——新增参数必须带 `FieldDef` 缺省（旧脚本继续解析）、语义变更必须换新 type 名；模型字段全为可选 → 脚本 `meta.version` 保持 3（`ScriptParser.java:95-97`）。

### 定稿 10｜编辑器呈现与外部 API（§5-10、§5-11、§6-6、§6-7）

- **编辑器（§6-6）**：类型下拉 = `MathModelRegistry.types()`；参数表单 / 默认值 = `schema.get` 的 `models` 段（`SchemaExporter`）驱动；**曲线可视化、参数预设、自定义形状烘焙**归编辑器侧运算（口径：运行时只读只执行、修改 / 生成 / 调配运算归编辑器——`implementation-progress.md` 通用原则 +「任务2改判」；`templates.md` §2 的 schema 驱动表单）。运行时不含编辑器逻辑、不含手柄 / 预设。
- **外部 API（§5-11、§6-7）**：**0.3.6 不开放外部注册**——`MathModelRegistry.register` 保持内部可见，不进 `api/` 包（`api/` 包是相机面，`camera-core-split.md` §2.4）。**开放时机 = 内部稳定后**（§7 步骤 7；`camera-state-plan.md` §1「内部优先，API 顺带」），门 = 模型库 + 至少两个调用方（相机参数运动、过渡 / 迟滞）落地且旧脚本零回归；届时随 `api/` 包阶段评估版本兼容（type / 参数名冻结 + 新增带缺省）。
- **命名（§5-12）**：层名用「数学模型（math models）」、代码 `math` 包 + `MathModel*`；「运动模型 / 响应模型」不采用——会把库绑到运动 / 相机语义，与 §1 冲突。

### 定稿 11｜不做项（§5-8、§5-9）

| 项 | 结论 | 依据 |
|---|---|---|
| 状态持久化（落盘 / 跨局） | 不做 | §2.7；`script-model.md` §5 |
| 表达式求值 / 函数图 | 不做（组合用注册模型类型表达；自定义形状由编辑器烘焙成数据） | 通用原则（创作运算归编辑器）；`script-model.md` §4-1 |
| 输入源族（§3.6） | v1 不实现（接口以 `inputArity() = 0` 预留） | §1；定稿 7.1 |
| 多圈角度语义 | 不在模型层（属关键帧 / 过渡层） | `transition.md` §5 |
| 编辑器曲线手柄 / 预设 | 不在运行时（编辑器阶段） | 「任务2改判」（progress:144） |

### 定稿 12｜待用户拍板

1. **seek 后的模型状态**：候选 A = `reset()`（倾向：与「编辑器 seek = 硬定位」一致、确定性最好、无隐藏成本；代价 = 拖动播放头后弹簧从初值追赶的一次瞬态）；候选 B = 从片段起点按固定步长预热重放（观感更接近连续播放；代价 = 每次 seek 的重放成本 + 需定义预热步长，且仍不等于真实历史）。
2. **模型库包名**：候选 A = `...immersive_cinematics.math`（倾向：不绑相机、与 `util/MathUtil` 的「通用工具」区分）；候选 B = `...immersive_cinematics.util.math`（与 `MathUtil` / `TimeInterpolation` 同处 util 树）。低影响，可与 B16-b 任务书一起定。

---

## 8. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [时间插值](./temporal-interpolation.md)
- [过渡](./transition.md)
- [迟滞](./hysteresis.md)

---

## 事实核查（2026-10-07）

> 核查依据：本仓库源码。§1–§8 设计内容未改动。
> **2026-10-10 回写**：§5/§6 已就地标注定稿（见〈定稿（2026-10-10）〉）；§1–§4、§7–§8 原文未改。

### ① 核实为真（§2 与现状相关的挂点）
- **2.3 无状态 / 有状态分离**：`KeyframeInterpolator` 为无状态工具类（全静态、不持状态，类注释明确）；`BezierPathStrategy` 为有状态（`lutCache: HashMap`）；`PathStrategies` 用 `Supplier` 注册、`get()` 每次返回新实例（`script/PathStrategies.java`）——"定义 / 实例分离 + 无状态 / 有状态并存" 已是现状。
- **2.5 可序列化 JSON**：`Keyframe` / `Clip` 以 `Map<String,Object> data` 承载、由 JSON 解析构造（`script/Keyframe.java`、`script/Clip.java`）；贝塞尔曲线 `BezierCurve` 由 JSON（`{"type":"bezier","control_points":[...]}`）反序列化（`script/BezierCurve.java`）；路径策略按字符串 `curve.type` 选择（`PathStrategies.get`）。数据驱动已有基础。
- **2.6 确定性**：`BreathDisturbance` 明确 "同 seed + 同 globalTime → 同抖动"（`script/BreathDisturbance.java` 类注释），确定性噪声已有实现。

### ② 已修正
- 无（§2 为已确认方向，未发现与代码冲突的现状断言）。

### ③ 补全
- **Registry 现状（2026-10-09 回写）**：`PathStrategies` 静态块现注册 `linear` + `bezier` 两条（`PathStrategies.java:36-41`，默认策略 `DEFAULT_TYPE = "linear"`）；`PathStrategies.get(type)`（:69-79）仅在该 type 未注册时 `LOGGER.warn` 并回落 `linear`，`bezier` 经动态查表不再回落。`CameraTrackPlayer` 仍直接 `new BezierPathStrategy()` 持有独立实例（`CameraTrackPlayer.java:27`，实例自带 LUT 缓存），在 :898、:942 按需选用（`follow` 时改用 `PathStrategies.get("linear")`）。原记录"仅注册 `linear`、`KeyframeInterpolator.interpolatePosition(from,to,s,clip)` 经 `get(curve.type)` 对 `bezier` 回退 linear"已随注册补齐而过时——该 4 参入口已不存在（现仅 5 参重载 `KeyframeInterpolator.java:170`，仓内无调用者）。
- **现有"组合"**：`CameraProperties` 五属性各持独立 `AnimValue`（每属性独立过渡，`camera/CameraProperties.java:29-63`），可视为 §3.5 "并联" 的雏形。

### ④ 未验证
- §2.4 "支持串联 / 并联 / 混合 / 映射"、§2.8 "低开销 / 可回退 / 版本化" 在代码中无对应统一实现——**未验证**（方向项）。**2026-10-10**：实现口径已定（组合 = `chain` / `blend`，见定稿 7.1；低开销 / 回退 / 版本化见定稿 5、9），代码仍待 B16-b。
