# 0.3.6 基准坐标系：点源、方向源与通道相对化（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [选择器](./selector-model.md)
> - [数学函数模型](./math-models.md)
> - [过渡](./transition.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位（已确认）

**基准坐标系（Frame）**：

> 由 **一个原点 + 一个基础朝向** 搭建出来的局部坐标系；相机的参数可以在这个坐标系里以相对量表达。

```text
点源（原点）      ┐
                  ├─► 基准坐标系（原点 + 三轴）─► 位置 / yaw / pitch
方向源（基础朝向）┘
```

- 它不产生画面，只给出“这一帧的基准”。
- 与六参数模型的关系：六参数管“值是多少”，基准坐标系管“值相对谁”。
- 短期服务脚本关键帧的位置与朝向；长期服务其它来源（编辑器直控、其它轨道、外部 API）。

不属于本文：

- 选择器本身（[选择器](./selector-model.md)）——它只是点源 / 方向源的一种来源
- 平滑 / 过渡 / 迟滞（应用层）
- 覆盖链的合成顺序（[相机状态与覆盖链](./camera-state-plan.md)）

---

## 2. 概念定义（已确认）

### 2.1 点源（Point Source）

能产出一个**世界坐标**的东西：玩家、实体（选择器）、固定坐标、结构、方块……

> **点源 = 来源 + 偏移**；偏移的表达空间可选（世界轴 / 基准坐标系）。

凡是能产出坐标的来源，理论上都能吃同一层偏移。

### 2.2 方向源（Direction Source）

能产出一个**方向**的东西：世界轴、实体朝向、实体视线、两点连线……

### 2.3 坐标系构建

```text
原点  = 点源
fwd   = 方向源（归一化）
right = fwd 的水平投影 与 世界竖直 的叉乘
up    = right × fwd
```

垂直面是否跟随方向源的俯仰，由“垂直面基准”决定（见 2.4）。

### 2.4 通道相对化（每个通道单独开关）

| 通道 | 相对坐标系 | 绝对 |
|---|---|---|
| 位置 | `fwd / up / right` 偏移 | 世界坐标 |
| yaw | 基础方向 + 偏移 | 世界角 |
| pitch | 基础俯仰 + 偏移 | 世界角 |
| 注视点 | 偏移按基准坐标系表达 | 偏移按世界轴表达 |

- 各项**各自独立**决定是否相对化。
- 这就是“水平面 / 垂直面分开控制”的落点：例如 yaw 跟坐标系、pitch 不跟；或者位置跟、朝向不跟。
- **注视点本身也是点源**（它最终产出的就是坐标），所以它同样吃偏移，表达空间与世界轴 / 基准坐标系一致。
- 例外：`look_at = none` 是**角度模式**（朝向由关键帧 `yaw` / `pitch` + 基准决定），不是坐标来源，不吃偏移。

---

## 3. 连线（已确认方向）

- 连线 = **点源 A 作为原点** + **A → B 作为基础朝向**。
- 连线的两个端点应当是**点源**，而不是只能填实体选择器：凡是能产出坐标的来源都应可用。
- 连线只是方向源的一种；“以连线替代生物视线”是本层的典型用法。
- 注视点与坐标系原点共用同一套点源定义（来源 + 偏移）。

---

## 4. 现状（方向）

| 概念 | 现状 |
|---|---|
| 点源 | 位置侧已有：玩家 / 固定坐标 / 结构 / 方块；连线端点**只有实体选择器** ← 不统一 |
| 方向源 | 朝向侧已有 `world` / `entity` / `line` 三种（`yaw_base` / `pitch_base` 各自取值）；位置侧另有一套“基准朝向”（`facing_origin` 自身朝向 / `facing_target` 连线），两处来源未合并 |
| 坐标系 | 位置用“follow 实体 / 玩家”一套（`follow` / `follow_selector`）、朝向用 `yaw_base` / `pitch_base` 一套，**两套 base 未合并**；位置另有基准空间偏移（`fwd` / `up` / `right` + `facing_origin` / `facing_target`），是“原点 + 基础朝向”的早期落点 |
| 每通道开关 | 朝向已按轴独立（`yaw_base` / `pitch_base` 分开）；位置只有 `position_mode` = `absolute` / `relative`，相对又分世界轴 `dx/dy/dz` 与基准空间 `fwd` / `up` / `right` 两种，且相对基准**不能选 line** |
| 连线当原点 | `yaw_base = line` 的端点 A 只参与算方向；但 `facing_origin`(A) + `facing_target`(B) + `fwd` / `up` / `right` 已实现“A 为原点 + A→B 为基础朝向”摆位（仅位置基准空间偏移，端点仍限实体选择器） |
| 注视点 | `look_at: entity` 的注视点写死为“实体位置 + 包围盒高度 / 2”，**只能看中心，没有偏移** |
| 注视点偏移 | `look_at_target` 对象形式可选 `space: facing` 按基准坐标系表达：此时偏移取自 `fwd` / `up` / `right`（本帧没有基准系才回落 `dx/dy/dz`）；缺省世界轴；不是点源的通用属性 |
| `look_at` 模式 | 枚举只有 `none` / `coordinate` / `entity`；结构 / 坐标 / 相对点都塞在 `coordinate` 里；**没有方块来源**（位置侧有 `block:id[:radius]`） |
| `look_at = none` | 是角度模式，实现上表现为“沿关键帧角度方向 100 格远的假目标点”，用于复用两端目标点插值 |
| 垂直线边界 | 纯垂直线（两端水平位置相同、只有高度差）未拦截，会算出无意义的水平角 |
| “实体视线”方向源 | 实现取的是身体 yaw + `xRot`，与“视线”字面不一致 |

### 4.1 由此产生的问题

- “沿连线摆机位”只能走 `facing_origin` + `facing_target` + `fwd` / `up` / `right` 一条路（端点限实体选择器）；`yaw_base = line` 那条连线不参与位置——两条连线来源不统一。
- 位置与朝向可以来自两套不同的基准，组合不自洽。
- `yaw_base = line` 只在 `look_at = none` 时生效（朝向被 `look_at` 覆盖）。
- 注视点无法微调：想看向头 / 脸做不到，只能对着包围盒中心。
- 偏移不通用：注视点偏移只存在于 `look_at = coordinate` 的 `look_at_target` 对象里；表达空间只有世界轴 / `facing` 两种，且 `facing` 依赖本帧位置侧已建立基准系。

---

## 5. 长期方向（分块）

### 5.1 统一点源与方向源

点源清单统一（玩家 / 实体选择器 / 坐标 / 结构 / 方块 / …），**连线端点与位置基准共用同一套点源**。

### 5.2 坐标系构建

原点 + 基础朝向 → 三轴；明确世界竖直与方向源俯仰的关系。

参考系的静 / 动由**来源**决定：点源 / 方向源任一动态（实体 / 玩家 / 视线 / 实体端连线）→ 参考系动态；世界参考系恒静态。

### 5.3 通道相对化

位置 / yaw / pitch / 注视点各项独立开关；水平面与垂直面可分开取来源。

### 5.4 偏移下放到点源

偏移成为点源的通用属性（**来源 + 偏移**），任何能产出坐标的来源都能带一层偏移，表达空间可选世界轴 / 基准坐标系。

现有 `relative_to` + `dx/dy/dz` 收敛为“实体点源 + 世界轴偏移”的一个特例，不再是一套独立的偏移。

### 5.5 注视点

注视点即点源：来源可以是实体 / 坐标 / 结构 / 方块 / …，再叠一层偏移。这样“看包围盒中心”“看头”“看某个部位”都只是同一机制的不同参数。

来源（方向）——不管哪种来源，最终产物都是一个坐标点：

- 直接坐标（绝对 / 相对点）
- 结构（结构中心）
- 方块（就近搜索，复用 `block:id[:radius]` 的语义）
- 实体（实时取，会动）

实体目标的偏移（方向）：

- 目标点 = 实体 AABB 碰撞箱内的一个点，按**百分比**定位：每轴 0%–100%，50% = 中心（= 现在写死的值）。
- 偏移始终落在 AABB 内（生物体内），不是往体外偏。
- 其他来源（坐标 / 结构 / 方块）不带这个偏移——要对点微调，直接在坐标上加减即可。

（偏移的表达空间除世界轴 / 基准系外，多一种“AABB 内百分比”——只对实体来源有效。）

### 5.6 朝向与 look_at 的关系

`look_at` 是朝向的**动态开关**（对 yaw / pitch 的动态输入）：

- **开** = 动态覆盖静态（关键帧角度 + 基准）——两者**互斥、不叠**：动态有动态的偏移，静态有静态的偏移。
- **关** = 静态（关键帧 `yaw` / `pitch` + 基准，旧写法）。
- 两种写法可以在同一关键帧里并存（都写），但同一时刻只有一个生效。

位置与朝向**各有自己的开关**（位置 = 跟随，朝向 = 看向），互不影响。

### 5.7 与其它层的边界

坐标系的平滑 / 迟滞属应用层；坐标系只负责给出“这一帧的基准”。

### 5.8 方向锁（yaw / pitch 分轴）

基础朝向的每个轴各自一把锁：

- **没锁** = 跟来源（方向源给的 yaw / pitch）；
- **锁了** = 用作者给的固定值；
- yaw 与 pitch **分开算**（可以一个跟来源、一个锁死）。

典型用法（第三人称）：pitch 锁在固定角（如 15°），yaw 跟玩家视角。

与现状的关系：

- `up_axis`（`view` / `world`）是这套的**粗版**：`view` ≈ 两轴都不锁；`world` ≈ pitch 锁在 0。
- 角度通道的 `yaw_base` / `pitch_base` 是同类东西的另一半：分轴、来源选择，但“锁值”目前只有 0。

待定：

- 锁值的参照（世界角 / 相对来源）
- 没锁时的“来源”清单（方向源：世界轴 / 实体视线 / 连线）
- 锁了之后关键帧自己的 `yaw` / `pitch` 偏移是否还叠
- 锁挂在基准系、角度通道，还是同一机制共用
- 命名（`up_axis` / `view` / `world` 要改）

---

## 6. 可能的问题

- 原点取 A，还是 A 与 B 的中点
- 纯水平线 / 纯垂直线 / 零长度线的定义与拒绝规则
- 端点来源与位置基准是否完全共用同一套清单
- 通道相对化的字段形态（每通道一个开关？还是一个来源枚举？）
- 与 `up_axis`（world / view）的关系：是否被“垂直面基准”吸收
- 与 `look_at` 的优先级
- 偏移与现有 `look_at_target` 的 `dx/dy/dz` 如何收敛（统一为一层偏移？）
- `look_at = none`（角度模式）是否保留“假目标点”的实现方式
- 是否补齐注视点的方块 / 结构等来源
- 偏移作为关键帧通道的插值（两端各自算好带偏移的点，再插值）
- 关键帧级还是片段级
- 作者可用性与编辑器呈现（怎么让作者理解“坐标系”这件事）
- 性能：多个点源 / 方向源每帧求值

---

## 7. 待定

- 字段命名与形态
- 点源清单
- 方向源清单（身体朝向 / 视线是否拆成两个来源）
- 偏移的表达空间与字段命名（注视点与位置是否共用同一组）
- `look_at` 与坐标系朝向的优先关系
- 垂直线的处理
- 同一关键帧是否需要多个坐标系
- 外部 API 时机

---

## 8. 落地顺序（方向）

1. 定义点源与方向源的统一清单
2. 定义坐标系构建（原点 + 朝向 → 三轴）
3. 通道相对化（位置 / yaw / pitch / 注视点 独立开关）
4. 偏移下放到点源（注视点 / 连线端点 / 位置基准共用；收敛现有 `relative_to` 偏移）
5. 注视点来源补齐与部位微调（头 / 中心 / 自定义偏移）
6. 连线端点改用统一点源
7. 修掉垂直线的边界情况与“视线 / 身体朝向”的措辞
8. 编辑器呈现

---

## 9. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [选择器](./selector-model.md)
- [数学函数模型](./math-models.md)
- [过渡](./transition.md)
- [迟滞](./hysteresis.md)

---

## 事实核查（2026-10-07）

核查依据：本仓库工作树源码（HEAD，`git status` 除 `plans/` 外无改动）+ MC 1.20.1 sources.jar。以下行号均为核查当日工作树行号。

路径缩写：
- `CTP` = `common/src/main/java/com/immersivecinematics/immersive_cinematics/script/CameraTrackPlayer.java`
- `PD` = `.../script/PositionData.java`；`SP` = `.../script/ScriptParser.java`；`SV` = `.../script/ScriptValidator.java`；`TS` = `.../script/schema/TrackSchemas.java`
- `Entity.java` = `build/mc-sources/net/minecraft/world/entity/Entity.java`（已解出）
- `SRCJAR!<成员>` = `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-d95c7b3016/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/minecraft-merged-d95c7b3016-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2-sources.jar!<成员>`

### ① 核实为真

| 断言 | 证据 |
|---|---|
| 位置侧点源已有 玩家 / 固定坐标 / 结构 / 方块 | `PD:22-34`（`ORIGIN_PLAYER=0` / `ORIGIN_COORDINATE=1` / `ORIGIN_STRUCTURE=2` / `ORIGIN_BLOCK=3` / `ORIGIN_SELECTOR=4`）；解析在 `SP.parseRelativeWithOrigin`（`SP:512-547`）：缺省玩家激活位置 / `relative_origin:"coordinate"`+`relative_origin_x/y/z` / `block:id[:radius]`（或 `{type:"block",block,radius}`）/ 其它字符串=结构 id |
| 连线端点**只有实体选择器** | `CTP.lineDir`（`CTP:868-871`）对 `yaw_base_from` / `yaw_base_to` 只调 `resolveEntity`；`facing_target` 同（`CTP:390-391`）。`resolveEntity` 只认选择器（`CTP:1044-1139`） |
| 朝向侧方向源 `world` / `entity` / `line` | `TS:75-79`（枚举 + `yaw_base_selector` / `yaw_base_from` / `yaw_base_to`）；求值 `yawBaseOf`（`CTP:837-848`）/ `pitchBaseOf`（`CTP:851-862`） |
| 两套 base 未合并 | 位置：`follow` / `follow_selector`（`CTP.evalKeyframeWorldPos:330-336`）；朝向：`yaw_base` / `pitch_base`（`CTP:837` / `:851`）。两组互不引用 |
| 朝向已按轴独立 | `yaw_base` 与 `pitch_base` 是独立字段、独立求值（`TS:75-76`，`CTP:837` / `:851`） |
| 位置 `position_mode` = relative / absolute | `TS:52`；`SP.parsePositionData`（`SP:371-397`） |
| 相对基准不能选 line | `CTP.resolveRelativeBase`（`CTP:510-524`）只处理 coordinate / 结构 / block / 玩家；`OriginSpec`（`SP:469-474`）只有 `PLAYER` / `COORDINATE` / `SELECTOR` |
| `look_at: entity` 注视点写死“实体位置 + 包围盒高度 / 2” | `CTP.evalLookTarget:645`：`entityPosInterp(target).add(0, target.getBbHeight() / 2.0, 0)` |
| `look_at` 枚举只有 `none` / `coordinate` / `entity` | `TS:62`；`SV:279`（`checkEnum`）；求值 `CTP.evalLookTarget:639-683` |
| 结构 / 坐标 / 相对点都塞在 `coordinate` 里 | `CTP.evalLookTarget` coordinate 分支：`look_at_target_structure`（`:649-661`）→ `look_at_target` 对象（`:663-668`）→ 散字段 `look_at_target_x/y/z`（`:670-673`） |
| **没有方块来源**（位置侧有 `block:id[:radius]`） | `evalLookTarget` 全分支无 block 解析；`PD.parseBlockOriginString`（`PD:337`）只被位置侧调用（`SP:439`、`SP:544`） |
| `look_at = none` = 100 格假目标点 | `CTP.evalLookTarget:675-682`：`yaw`/`pitch` → MC 视线公式 `(-sin y·cos p, -sin p, cos y·cos p)` × 100 |
| 纯垂直线未拦截 | `CTP.lineDir:872-880` 只在 `horizontal < 1e-4 && \|dy\| < 1e-4`（零长度）返回 null；纯垂直线 `horizontal≈0`、`\|dy\|>0` 通过，`yaw = atan2(0,0) - 90 = -90` 恒定。`CTP.buildFrame:452-455` 同样只拦零长度（warn 后 null） |
| “实体视线”方向源实现取的是身体 yaw + `xRot` | `CTP.yawBaseOf:840-841` → `entityBodyYawInterp`（`CTP:1428-1434`，LivingEntity 分支 `Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot)` = **身体 yaw**）；`CTP.pitchBaseOf:854-855` → `entityPitchInterp`（`CTP:1437-1440`，`Mth.lerp(pt, e.xRotO, e.getXRot())`）。位置基准系朝向（`CTP:400-401`）用同一对 |
| `up_axis` = `view` / `world` | `PD.getUpAxis`（`PD:328-330`，缺省 `"view"`）；`SP:416-419` 仅接受 view/world，否则抛 `ScriptParseException`；生效点 `CTP.buildFrame:462-473`（`"view".equals(upAxis) && \|dy\|>1e-4` 才跟随俯仰） |
| §5.5“50% = 中心（= 现在写死的值）” | 现在写死的点 = `entityPosInterp + getBbHeight()/2`（`CTP:645`）= AABB 中心：`EntityDimensions.makeBoundingBox`（`SRCJAR!net/minecraft/world/entity/EntityDimensions.java:24-28`）生成 `[x-w/2, y, z-w/2] → [x+w/2, y+h, z+w/2]`，`AABB.getCenter()` = min/max 中点（`SRCJAR!net/minecraft/world/phys/AABB.java:351-353`） |

### ② MC 源码核实（§4 表格两条断言的依据）

**“实体视线方向源取身体 yaw + xRot，与视线字面不一致”** —— 成立。

- 原版视线：`Entity.getViewVector(pt)` = `calculateViewVector(getViewXRot(pt), getViewYRot(pt))`（`Entity.java:1483-1485`）；`getViewXRot` = `Mth.lerp(pt, xRotO, getXRot())`（`:1487-1489`）、`getViewYRot` = `Mth.lerp(pt, yRotO, getYRot())`（`:1491-1493`）。即原版视线 = **同一个实体的 `yRot` + `xRot` 对**，`LivingEntity` 不覆写该方法。
- 身体朝向是另一对字段：`LivingEntity.yBodyRot` / `yBodyRotO` / `yHeadRot` / `yHeadRotO`（`SRCJAR!net/minecraft/world/entity/LivingEntity.java:202-205`）；`getVisualRotationYInDegrees()` 被覆写为返回 `yBodyRot`（`:3098-3100`）；`tickHeadTurn` 让 `yBodyRot` 以 0.3 系数追赶 `yRot`、偏差超 ±50° 才钳（`:2257-2264`）。
- 渲染分工：`LivingEntityRenderer` 身体用 `Mth.rotLerp(pt, yBodyRotO, yBodyRot)`、头用 `Mth.rotLerp(pt, yHeadRotO, yHeadRot)`（`SRCJAR!net/minecraft/client/renderer/entity/LivingEntityRenderer.java:70-71`）。
- 结论：本仓取 **`yBodyRot`（身体水平）+ `xRot`（头部俯仰）**，与原版 `getViewVector`（`yRot`+`xRot`，同源一对）不同，且自身混用了两套来源 —— 与字面“视线”不一致的断言成立。

**注视点写死“实体位置 + 包围盒高度 / 2”对应哪段代码** —— 对应本仓 `CTP.evalLookTarget:645`（`look_at=entity`）与 `CTP.evalFacingFrame:393`（`facing_target` 目标点，同一表达式）。该式在几何上等于原版 `entity.getBoundingBox().getCenter()`（`AABB.java:351-353` + `EntityDimensions.java:24-28`），即 AABB 几何中心，**不是**原版眼睛位置（`getEyeHeight` = `height * 0.85F`，`Entity.java:2785-2787`；`getEyeY()` = `position.y + eyeHeight`，`:3244-3246`）。

### ③ 已修正（旧说法 → 新事实 + 证据）

| # | 旧说法（原文） | 新事实 | 证据 |
|---|---|---|---|
| 1 | 方向源“已有 `world` / `entity` / `line` 三种，但**只作用于朝向**” | 该枚举确实只作用于朝向（`yaw_base`/`pitch_base`）；但**位置侧另有一套基准朝向来源**：`facing_origin` 自身朝向 / `facing_target` 连线 | `CTP.evalFacingFrame:387-394`、`CTP:400-401`；`PD.facingTarget`（`PD:71-72`） |
| 2 | 位置只有“绝对 or 相对” | `position_mode` 只有 absolute/relative，但 relative 内部再分两种：世界轴 `dx/dy/dz` 与基准空间 `fwd/up/right`（二者互斥，混用报错） | `SP.parsePositionData:371-397`、`SP.parseFacingRelative:408-412` |
| 3 | 连线当原点“未实现（A 只参与算方向）” | `yaw_base = line` 的 A 确实只算方向；但 `facing_origin`(A) + `facing_target`(B) + `fwd/up/right` **已实现**“A 为原点 + A→B 为基础朝向”（仅位置基准空间偏移；端点仍限实体选择器） | `SP.parseFacingRelative:423-454`；`PD.facingToEntity`/`withFacingTarget`（`PD:168-172`、`:211-214`）；`CTP.evalFacingFrame:379-394`；`CTP.buildFrame:449-476` |
| 4 | §4.1“想做‘沿连线摆机位’做不到——最有价值的一半（位置）没接上” | 位置侧已能沿连线摆：基准点 = A（`resolvePointSource:417-436`，实体来源 = `entityPosInterp` 脚底），前轴 = A→B；限制是端点限实体选择器、且只在 `fwd/up/right` 模式 | 同 #3；提交 `a3b29b2`（2026-09-16T22:03） |
| 5 | 注视点偏移“（`dx/dy/dz`）可选 `space: facing`” | `space: facing` 时偏移取自 **`fwd` / `up` / `right`**（`frameOffsetToWorld:481-484`）；`dx/dy/dz` 仅在本帧无基准系时兜底 | `CTP.evalLookTargetObject:704-710` |
| 6 | §4.1“只有 `coordinate` + `relative_to` 一条路能吃偏移，且不能选表达空间” | 偏移现在有 `space` 字段（缺省世界轴 / `facing`）；但只存在于 `look_at = coordinate` 的 `look_at_target` 对象里，且 `facing` 依赖本帧位置侧已建立基准系 | `CTP:704-711`；`CTP:357-362`（`frameOrigin`/`frameFwd`/`frameRight`/`frameUp` 仅由 `evalFacingOffset` 写入） |

**时间裁决**（仓库根 `git log -1 --format=%cI -- <路径>`）：

- `plans/0.3.6/coordinate-frame.md` = **2026-10-05T16:40:17+08:00**；`CTP` = **2026-09-17T13:38:01+08:00**；`PD` = **2026-09-16T22:03:11+08:00**；`SP` = **2026-09-26T16:27:29+08:00**；`TS` = **2026-09-17T13:38:01+08:00**。
- 但 §4 表格**文字**自 `ccdb37c`（2026-09-16T20:35）起未再改动（`git show ccdb37c:plans/0.3.6/coordinate-frame.md` 的 §4 与当前逐字相同），后续提交只改了 §5.5 / §5.8 等小节；`facing_origin` / `facing_target` 于 `a3b29b2`（2026-09-16T22:03，`feat(camera): 基准坐标系支持指定基准点与 A→B 连线朝向`）落地，**晚于 §4 文字**。故对 #1–#4 这几条，按内容时间裁决：**代码更晚，以代码为准**。
- #5 属 §4 表格文字，同样早于 `space: facing` 的落地代码（`CTP` 该分支在 `evalLookTargetObject` 内），以代码为准。

### ④ 补全（源码定位）

- **统一点源函数**：`CTP.resolvePointSource`（`CTP:417-436`）——坐标 / 结构中心 / 方块中心 / 实体（实体来源 = `entityPosInterp` 脚底，注释 `CTP:430-431`）。其 javadoc 声称“位置基准 / 注视点 / 连线端点共用同一种求值”（`CTP:412-413`），但**实际只有位置基准调用它**：注视点走 `evalLookTarget`（`CTP:639-683`）、连线端点走 `lineDir`→`resolveEntity`（`CTP:868-871`）——文档“不统一”的判断在这一点上成立。
- **位置基准点来源清单**：`facing_origin` 支持 实体选择器 / `"coordinate"`+`facing_origin_x/y/z` / `block:id[:radius]` / 结构 id（`SP:423-454`）；不写则回落 `follow` 实体 / 玩家（`SP:456-465`）。
- **方块语法**：`block:id[:radius]`，`PD.parseBlockOriginString`（`PD:337-349`）；默认半径 `DEFAULT_BLOCK_RADIUS = 16`（`PD:37`）；另有结构化写法 `{type:"block", block, radius}`（`SP:517-529`）。
- **结构基准**：`relative_origin` / `facing_origin` 填 `#...` 或含 `:` 的字符串（`SP:443-447`、`SP:541-546`）；解析 `CTP.resolveStructurePos:555-582`（原版 `StructureLocator.locateCenter`，3 区块内，成功永久缓存）。
- **基准朝向**：`facing_target` 非空 → 基准点 → 目标（目标点 = `entityPosInterp + getBbHeight()/2`，`CTP:393`）；为空 → 基准点自身朝向（实体 = `entityBodyYawInterp` + `entityPitchInterp`，`CTP:400-401`）。
- **`up_axis` 作用域**：只对 `fwd/up/right`（基准空间）生效；`dx/dy/dz` 世界轴偏移无此字段（`PD:74-75`、`SP:416`）。
- **片段前置拦截**：`CTP.isClipUsable:186-230`——`look_at` 实体 / 结构 / `look_at_target.relative_to` 实体 / `follow` 实体 / `yaw_base`|`pitch_base` 的实体与 line 端点 / `facing_origin` / `facing_target` / `relative_origin` 结构·方块任一不可解析 → 片段按空处理（不写相机）。
- **选择器调用点**：`SELECTOR_CALLPOINTS` = `follow` / `look_at` / `look_at_target` / `yaw_base` / `facing_origin` / `facing_target`（`CTP:96-97`）；策略字段名 `<字段>_<调用点>`，回落通用字段（`CTP.selectorPolicy:1145-1147`）。
- **`look_at_target` 对象四种模式**：`{x,y,z}` / `{dx,dy,dz}`（基准 = 触发点 `originPos`）/ `{relative_to:<selector>}` / `{relative_to:"coordinate", relative_x/y/z}`，均可叠 `space: facing`（`CTP.evalLookTargetObject:694-733`，`relative_to` 缺省分支 `:712-716`）。
- **`pitch_base = entity` 复用 `yaw_base_selector`**：没有独立的 `pitch_base_selector` 字段（`CTP:840` 与 `CTP:854` 读同一字段；`TS:77`）。
- **`lineDir` 同时供 yaw 与 pitch**：`yaw_base = line` 取 `dir[0]`、`pitch_base = line` 取 `dir[1]`（`CTP:843-846`、`:857-860`）；两端点缺一即 null。
- **`look_at` 目标点带切换平滑**：`look_at=entity`（`CTP:646`）与 `look_at_target.relative_to`（`CTP:732`）都走 `smoothTargetPoint`。
- **服务端推送前替换**：`look_at_target_structure` 与 block `relative_origin` 在 `/icinematics play` 推送前被替换为坐标，客户端解析路径主要为编辑器预览兜底（`CTP:552-553`、`:592-593`）。

### ⑤ 未验证

- 编辑器前端（`editor/src`）是否已暴露 `facing_origin` / `facing_target` / `up_axis` 字段：本次未核查（超出本文 §4 现状表的范围）。
- 跨文档冲突：未处理（按要求不改他人文档）。
