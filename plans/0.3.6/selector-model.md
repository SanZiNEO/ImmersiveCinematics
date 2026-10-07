# 0.3.6 选择器：运行时目标来源与调用点独立（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [相机状态与覆盖链](./camera-state-plan.md)
> - [基准坐标系](./coordinate-frame.md)
> - [数学函数模型](./math-models.md)
> - [时间插值](./temporal-interpolation.md)
> - [过渡](./transition.md)
> - [迟滞](./hysteresis.md)

---

## 1. 定位（已确认）

**选择器（Selector）**：

> 让相机属性从“固定值”变成“运行时查询”的目标来源。

- 选择器不产生画面，只产出**引用目标**（实体 / 点）。
- 是**六参数模型的延伸应用**：六参数管“值是多少”，选择器管“值从哪来”。
- 服务于相机的位置基准、朝向目标、朝向基准；长期还要服务其它属性、其它轨道、外部模组。
- 属于**输入源层**，不是应用层。

不属于本文：

- 平滑 / 过渡 / 插值（应用层：[过渡](./transition.md) / [时间插值](./temporal-interpolation.md)）
- 相机状态的合成与覆盖（[相机状态与覆盖链](./camera-state-plan.md)）
- 目标运动的响应行为（[迟滞](./hysteresis.md)）——迟滞文档已明确把“目标选择迟滞、选择器切换策略”划归本层

### 现状挂点

| 调用点（角色） | 字段 | 服务的属性 |
|---|---|---|
| follow | `follow_selector` | 位置基准 |
| look_at | `look_at_selector` | 朝向目标（注视点） |
| look_at_target | `look_at_target.relative_to` | 坐标注视的相对基准 |
| yaw_base | `yaw_base_selector` | 朝向基准（yaw 与 pitch 共用） |
| yaw_base_from | `yaw_base_from` | 连线基准的 A 点 |
| yaw_base_to | `yaw_base_to` | 连线基准的 B 点 |

> **字段形态（2026-10-07 源码核查，`script/schema/TrackSchemas.java`）**：`follow_selector` / `look_at_selector` / `yaw_base_selector` 为 `string`，缺省 `"@p"`（:61 / :63 / :77）；`yaw_base_from` / `yaw_base_to` 为 `string`，缺省 `""`（:78-79）；`look_at_target` 为 `map`（缺省 `null`，:68），`relative_to` 是它内部的一个键。实际调用点共 **8 个** role（另有 `facing_origin` / `facing_target`，来自 `position.facing_origin` / `position.facing_target`，对应 `PositionData.originSelector` / `facingTarget`），本表只列了其中 6 个。

解析走两条路径：本地（`@p` / `@s` / `uuid:` / 简单 `@e[type,…]`）与服务端（NBT / tag 等原版扩展选项）。

- 分发点 `CameraTrackPlayer.resolveEntity`（:1044）：`@p` / `@s` 直接返回 `mc.player`（:1047-1049）；其余进 `resolveEntityInternal`（:1251）。
- 服务端路径判定 `requiresServerSelector`（:1324）：**只认 `@e[` 开头且 `]` 结尾**的 selector，且 `type=`（取反 / tag / 多个）与 `name=`（取反）之外的任何选项都转服务端。
- 服务端解析 `EntitySelectorResolver.resolve`（`trigger/server/EntitySelectorResolver.java:48`）：用原版 `EntitySelectorParser`（:62）+ `SuccessOnlySource.withPosition(x,y,z).withPermission(4)`（:66）+ `EntitySelector.findEntities`（:70）；上限 512 字符 / 32 条结果。
- 客户端缓存 `ClientEntitySelectorCache`（`trigger/client/ClientEntitySelectorCache.java`）：`CACHE` 键为 selector 字符串（:33），请求时把锚点 x/y/z 随包发给服务端（:48）。
- 本地求值 `resolveLocalSelector`（:1266）：`uuid:<uuid>`、`@e`、`@e[type=…,name=…]`（未知键静默忽略），`@e` 系列按 `distanceToSqr(origin)` 取最近的活实体。

原版选择器类型 / 选项清单与锚点机制见文末《事实核查（2026-10-07）》小节。

选择器只是**点源 / 方向源**的一种来源，坐标系本身的构建与通道相对化见[基准坐标系](./coordinate-frame.md)。

---

## 2. 发现的问题（本文档的起点）

**一个功能被 N 个地方共用，却只有一份控制。**

- 选择器的策略三件套（刷新频率 / 存活期是否换目标 / 切换平滑）目前是**关键帧级**的，注释与实现都是“作用于该关键帧所有 selector 字段”。
  > 2026-10-07 核查：**部分过时**。策略字段现有 **4 个**（`selector_refresh` / `selector_switch_while_alive` / `selector_switch_interval` / `selector_switch_smooth`），且 `CameraTrackPlayer.selectorPolicy(kf, role)`（:1145）已支持**调用点专属字段** `<字段>_<调用点>`（优先于通用字段，:1157/:1165）。隔离名单 `SELECTOR_CALLPOINTS`（:96-97）= follow / look_at / look_at_target / yaw_base / **facing_origin / facing_target**；`yaw_base_from` / `yaw_base_to` **不在**名单内，仍只吃通用字段。`TrackSchemas.java:69` 的注释与 schema 仍只声明通用字段。裁决与时间见文末核查小节。
- 后果：同一关键帧里的 **6 个调用点**（follow / look_at / look_at_target / yaw_base / yaw_base_from / yaw_base_to）被迫共用同一份策略。
  > 2026-10-07 核查：实际 role 共 **8 个**（上表 6 个 + `facing_origin` / `facing_target`）；代码的隔离名单是另一个 6 个（含 `facing_*`、不含 `yaw_base_from/to`）。
  - 例：想给 look_at 开“自动切换 + 切换平滑”，follow 会被一起改掉；1vN 时镜头会在同阵营单位之间乱跳。
  - 例：follow 想“目标活着就锁死不放”，look_at 想“跟着离得最近的敌人走”——现在无法同时成立。
  - 例：连线基准的两个端点（`yaw_base_from` / `yaw_base_to`）也被同一份策略管着，想给它们单独的刷新节奏做不到。
- 同类问题：解析结果缓存以 **selector 字符串**为键，不含调用点与锚点；而 `sort=nearest` 的结果依赖锚点，follow 与 look_at 传的锚点并不相同。
  > 2026-10-07 核查：**为真，但只适用于服务端结果缓存** `ClientEntitySelectorCache.CACHE`（:33，键 = selector；request 的 x/y/z 只随包发出，不参与键）。本地路径的目标锁 `targetLocks` 键为 `role + "\0" + selector`（`CameraTrackPlayer.targetKey` :1173），**已按调用点隔离**。
- 一般化的结论：**策略 / 状态类字段必须按“谁在用”隔离；共用字段只能作为默认回落。**

本轮的取舍：**先保持现状（混用）**，只把方向定下来。1v1 场景影响不明显，1vN 场景才会暴露。

---

## 3. 已确认方向

1. **调用点独立**：每个调用点拥有自己的策略，可单独配置，互不影响。
2. **共用字段只作默认回落**：通用字段仍然可用，缺省时向下继承，现有脚本行为不变。
3. **选择器只负责解析出目标**：平滑 / 过渡 / 插值不属本层。
4. 本轮只定方向，实现时再仔细看。

---

## 4. 长期方向（分块）

### 4.1 调用点模型

一次定义、多个调用点、互不影响。调用点如何命名、如何分组待定。

### 4.2 策略层

刷新频率 / 换目标时机 / 切换平滑按调用点隔离，通用值作为缺省。

### 4.3 解析层

本地与服务端两条路径、请求去重、结果缓存的归属（缓存键是否要含调用点与锚点）。

### 4.4 锚点与排序

`sort=nearest` 的参考点（锚点）归属：当前是相机位置，是否需要可配置。

### 4.5 候选与择一

多候选之后的择一策略（最近 / 存活优先 / …）是否纳入本层。

### 4.6 通用化

其它属性、其它轨道、外部模组共用同一套选择器定义；编辑器的呈现方式。

---

## 5. 可能的问题

- 调用点的命名与分组
- 回落规则的层级（关键帧 / 片段 / 脚本 / 全局）
- 缓存键与失效语义（锚点变化、目标死亡 / 卸载、维度切换）
- 锚点语义（相机 / 被跟随者 / 玩家）
- 与片段可用性检查的关系（目标解析失败时片段按空处理）
- 请求频率与性能（同一字符串被多个调用点使用）
- 与“目标选择策略”的边界：哪些属于选择器，哪些属于策略层
- 编辑器的呈现与预览
- 外部 API 的时机与形态

---

## 6. 待定

- 字段命名与缺省规则
- 调用点清单
- 是否引入可复用的选择器预设
- 锚点是否可配置
- 多候选择一策略
- 外部 API 开放时机

---

## 7. 落地顺序（方向）

1. 定义调用点与策略隔离
2. 解析层的缓存与请求归属
3. 锚点 / 多候选
4. 通用化（其它属性、其它轨道、外部模组）
5. 编辑器呈现

---

## 8. 相关文档

- [相机状态与覆盖链](./camera-state-plan.md)
- [基准坐标系](./coordinate-frame.md)
- [数学函数模型](./math-models.md)
- [时间插值](./temporal-interpolation.md)
- [过渡](./transition.md)
- [迟滞](./hysteresis.md)

---

## 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **部分选择器既不支持也不转服务端**：`@a` / `@r` / `@n` / `@p[team=…]` 等既不走本地解析、也不发服务端请求（warn + null）——`requiresServerSelector` 只认 `@e[...]`。
  - 2026-10-07 复核：**为真**。`resolveEntity`（:1044）只特判 `@p` / `@s`（:1047-1049）；`requiresServerSelector`（:1324）要求以 `@e[` 开头且 `]` 结尾，故这些 selector 返回 false → `resolveLocalSelector`（:1266）落入 else → `LOGGER.warn("不支持的实体 selector: …")`（:1311）+ 返回 null。
  - 补全：`team=` 写在 `@e[...]` 上**可用**（会被判为需服务端解析，原版 `EntitySelectorOptions` 支持）；`@n` 在原版 1.20.1 **不存在**（`EntitySelectorParser.parseSelector` 只有 p/a/r/s/e 五个分支，其余抛 `ERROR_UNKNOWN_SELECTOR_TYPE`）。

---

## 事实核查（2026-10-07）

> 依据：MC 1.20.1 官方 sources.jar（`net/minecraft/commands/arguments/selector/`）、本仓库 `common/` 源码。
> 未在游戏内运行验证的条目标注「未验证」。

### ① 核实为真

| 断言 | 证据 |
|---|---|
| §1 表中 6 个字段全部存在且属性归属正确 | `script/schema/TrackSchemas.java`：`follow_selector`(:61) / `look_at_selector`(:63) / `yaw_base_selector`(:77) 均为 `string` 缺省 `"@p"`；`yaw_base_from`(:78) / `yaw_base_to`(:79) 为 `string` 缺省 `""`；`look_at_target`(:68) 为 `map`。读取点 `CameraTrackPlayer`：follow :331/:493/:501、look_at :642、look_at_target :199/:727、yaw_base :840/:854（yaw 与 pitch 共用，与文档一致）、yaw_base_from/to :869-870（`lineDir`） |
| `look_at_target.relative_to` 是 `look_at_target` 对象内部键 | `CameraTrackPlayer.evalLookTargetObject` :712（`relative_to` 三态：缺省=触发点偏移 / `"coordinate"` / 实体 selector）；`isClipUsable` :197 做前置拦截 |
| 两条解析路径存在，且本地/服务端分工如文档所述 | `resolveEntity` :1044 →（`@p`/`@s` 直接 `mc.player` :1047-1049）→ `resolveEntityInternal` :1251 → `requiresServerSelector` :1324 → `resolveServerSelector` :1371 / `resolveLocalSelector` :1266 |
| 服务端路径确实用原版选择器解析 | `trigger/server/EntitySelectorResolver.java`：`new EntitySelectorParser(new StringReader(selector), true)`(:62)、`SuccessOnlySource(...).withPosition(new Vec3(x,y,z)).withPermission(4)`(:66)、`entitySelector.findEntities(source)`(:70)、`EntitySelectorOptions.bootStrap()`(:84) |
| §2「缓存以 selector 字符串为键，不含调用点与锚点」 | `ClientEntitySelectorCache.CACHE` = `Map<String, Entry>`(:33)，`get`/`request`/`onResult` 全部按 selector(:40/:48/:79)；锚点 x/y/z 只进 `C2SResolveEntitySelectorPacket`（网络包字段），不参与键 |
| §4.4「`sort=nearest` 锚点当前是相机位置」 | 调用点传入的 `origin` = `lastWorldPos`（= 插值出的相机世界坐标，`CameraTrackPlayer` :314/:910）；look_at 传 `segPos`（相机段位置，`segmentYawPitch` :770-771）。客户端把该坐标发给服务端并作为 `withPosition` 的 origin |
| 已知缺陷：`@a`/`@r`/`@n`/`@p[team=…]` warn + null | 见上「已知缺陷」节复核 |
| 原版 `@p` 语义 = 最近的玩家（limit 1） | `EntitySelectorParser.parseSelector`：`@p` → `maxResults=1`、`order=ORDER_NEAREST`、`limitToType(EntityType.PLAYER)` |
| `sort=nearest` 相对的是 selector 原点（命令源位置，可被 `x/y/z` 逐分量覆盖） | `EntitySelector.findEntitiesRaw` 里 `Vec3 vec3 = this.position.apply(commandSourceStack.getPosition())`；`getPredicate(vec3)` 用 `range.matchesSqr(distanceToSqr(vec3))`；`sortAndLimit(vec3, list)` 用 `order.accept(vec3, list)`；`ORDER_NEAREST` 按 `distanceToSqr(vec3)` 升序 |
| `team=` 的匹配对象是队伍名，且支持取反 | `EntitySelectorOptions.register("team", …)`：非 `LivingEntity` 直接 false，否则比较 `entity.getTeam().getName()`，`!= bl`（`!team=x` 取反） |

### ② 已修正的断言

1. **§2「策略三件套…目前是关键帧级，注释与实现都是『作用于该关键帧所有 selector 字段』」→ 部分过时。**
   - 新事实：`CameraTrackPlayer` 已有 `SelectorPolicy` 记录(:90) 与 `SELECTOR_CALLPOINTS`(:96-97)，`selectorPolicy(kf, role)`(:1145) 先读 `<字段>_<调用点>`（:1157 `floatForCallpoint` / :1165 `boolForCallpoint`），缺失才回落通用字段 → **调用点隔离已实现**（对名单内 6 个 role）。
   - 残留：`yaw_base_from` / `yaw_base_to` 不在名单内（仍吃通用字段）；`TrackSchemas.java:69` 的注释与 schema 仍只声明 4 个通用字段，未声明调用点专属字段。
   - 时间裁决：`git log -1 --format=%cI` → 本文档 `2026-10-06T21:16:28+08:00`（bf44b41，仅追加「已知缺陷」节）**晚于** `CameraTrackPlayer.java` 的 `2026-09-17T13:38:01+08:00`（09a4813）。按「晚修改者为准」字面取文档；但 `git blame` 显示该句引入于 `ab4d611`（2026-09-15 14:14），**早于**实现调用点隔离的 `a3b29b2`（2026-09-16 22:03）——即该句写的是改动前状态，故以代码为现状，按已过时处理。两侧时间均记录在此备查。
2. **§2「策略三件套（刷新频率 / 存活期是否换目标 / 切换平滑）」→ 现为 4 项。** 新增 `selector_switch_interval`（`TrackSchemas.java:73`，a3b29b2 引入；`TrackSchemas.java:72` 注释：「两次真实切换之间的最小间隔（与扫描频率无关）；缺省 = selector_refresh」；`CHANGELOG.md` 0.3.6 节亦有条目）。
3. **§2「同一关键帧里的 6 个调用点」→ 实际 8 个 role。** 除表中 6 个外还有 `facing_origin`（`position.facing_origin` → `PositionData.originSelector`）与 `facing_target`（`PositionData.facingTarget`），读取点 :220/:490 与 :225/:391。

### ③ 补全的信息

**原版选择器类型（1.20.1，`EntitySelectorParser.parseSelector`）**

| 类型 | 语义 | 关键设置 |
|---|---|---|
| `@p` | 距原点**最近**的玩家 | `maxResults=1`、`ORDER_NEAREST`、限 `EntityType.PLAYER` |
| `@a` | 所有玩家 | `maxResults=MAX`、`ORDER_ARBITRARY` |
| `@r` | 随机一名玩家 | `maxResults=1`、`ORDER_RANDOM` |
| `@s` | 执行实体自身 | `currentEntity=true` |
| `@e` | 所有实体 | `maxResults=MAX`、`ORDER_ARBITRARY`、谓词 `Entity::isAlive` |
| `@n` | **1.20.1 不存在** | 落到 else 分支抛 `ERROR_UNKNOWN_SELECTOR_TYPE` |

**原版选项清单（`EntitySelectorOptions.bootStrap()`，共 21 项）**
`name`、`distance`、`level`、`x`、`y`、`z`、`dx`、`dy`、`dz`、`x_rotation`、`y_rotation`、`limit`、`sort`、`gamemode`、`team`、`type`、`tag`、`nbt`、`scores`、`advancements`、`predicate`。
- `sort=` 取值：`nearest` / `furthest` / `random` / `arbitrary`（:148-157）；缺省 `order = EntitySelector.ORDER_ARBITRARY`。
- `team=`：比较 `LivingEntity` 的队伍名，支持 `team=!x`；非 `LivingEntity` 直接不匹配（:210-226）。
- `nbt=`：`TagParser.readStruct` + `NbtUtils.compareNbt(要求, entity.saveWithoutId(new CompoundTag()), true)`（部分匹配），玩家额外注入 `SelectedItem`（:274-285）；与 `NbtPredicate.getEntityTagToCompare` 逻辑一致。
- `tag=` / `type=` / `name=` 均支持 `!` 取反；`type=` 另支持 `#tag` 实体类型标签。

**锚点机制**：`EntitySelector` 的 `position` 函数把命令源位置逐分量替换为 `x`/`y`/`z` 选项值，`distance` / `dx,dy,dz`(AABB) / `sort=nearest` 全部相对这个点求值。项目侧不暴露 `x/y/z` 给脚本，而是由调用点把相机坐标经 `withPosition` 注入 —— 即「锚点 = 相机位置」是项目约定，不是原版限制。

**编辑器现状（删包前快照）**：`editor/panel/KeyframePropertiesPanel.CAMERA_GROUPS`（该类已随 0.3.6 游戏内编辑器退役删除）只列了 `yaw_base_selector` / `yaw_base_from` / `yaw_base_to` / `look_at_selector` / `look_at_target*` / `follow_selector`，未列 `selector_refresh` 等策略字段；Web 编辑器（`editor/src/demo.ts`、`types.ts`、`DynamicForm.vue`）同样只有这 6 个字段。调用点专属字段（如 `selector_refresh_look_at`）运行时可用（`kf.getData().containsKey`），但 schema 与编辑器都未声明/呈现。

### ④ 无法核实的断言

- 上述全部结论均来自**静态读码 + 官方 sources.jar**，**未在游戏内运行验证**（含：调用点专属字段 `selector_refresh_look_at` 等实际生效、`@a`/`@r`/`@n` 的实际 warn 表现）。
- 「§2 缓存问题在 1vN 场景造成 follow 与 look_at 互相污染」属行为推断，未构造场景验证。
- 本仓库 `EntitySelectorResolver` javadoc 称 `nbt=` 走 `NbtPredicate.getEntityTagToCompare`：1.20.1 中 `nbt=` 选项实际内联了等价逻辑（`NbtUtils.compareNbt(..., true)` + `SelectedItem`），语义一致；该 javadoc 在代码内，本文档不改，仅记录。
