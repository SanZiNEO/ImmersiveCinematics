# 脚本架构图

> 状态：实现中（步骤 1–4 已落地：图数据、只读全景、阅读交互、搜索过滤；步骤 5 手动布局与持久化、增量刷新未做）
> 范围：编辑器内的脚本架构图视图（数据扫描、布局、交互）；不含编辑器整体架构与协议（见 [`server-protocol.md`](server-protocol.md)），不含触发器条件语义
> 关联：[`../C-trigger/trigger-conditions.md`](../C-trigger/trigger-conditions.md)、[`../../../docs/SCRIPT_FORMAT.md`](../../../docs/SCRIPT_FORMAT.md)

## 一、摘要

脚本架构图是编辑器里的第二个视图：把包内所有脚本铺成一张可平移缩放的平面图，文件夹是分区、前置依赖是连线，让作者看清脚本怎样串成故事线。视图只读，不承担编辑。

## 二、为什么做

### 2.1 问题

- 脚本一多，前置依赖只存在于脚本文件里，作者只能逐个打开对照，串不成故事线。
- 看不出哪些脚本没人依赖、哪些脚本处在依赖链的哪一段。
- 打开某个脚本要先在列表里按路径找，跳转成本高。

### 2.2 目标

- 打开视图即看到整包脚本、文件夹分区与依赖方向。
- 孤立脚本聚在一起，不散落在图里。
- 悬停能看到时长、轨道构成、触发器与前置条件。
- 单击节点高亮上下游链，双击直接打开该脚本。
- 按名称、触发器类型、文件夹过滤定位。

### 2.3 非目标

- 本次只做前置依赖一种边；触发器到脚本、事件调用链留待以后。
- 不做反向编辑（拖拽连线改前置），属远期。
- 不在本次定 canvas 或 svg 的取舍，按量级再定。
- 不做多人协作与版本对比。

## 三、方案

从视图菜单进入。画布可无限平移缩放；文件夹是分区，带分组外框与标题。分区内按依赖拓扑分层：被依赖的在左或上，依赖方在右或下，形成故事线流向；孤立脚本在分区内网格收拢，不散落。前置条件成环时不当作错误，用醒目虚线标出，由作者判断是否有意为之。

阅读：悬停节点显示摘要（时长、轨道构成、触发条件、前置条件）；点击节点或边高亮上下游链；双击在编辑器里打开该脚本。定位：按名称、触发器类型、文件夹搜索过滤。整理：后一阶段支持节点手动拖拽与布局持久化，让作者调过的布局重开不丢。

边界：只读视图，不写脚本、不参与播放；图的规模与刷新方式按执行时的量级决定。

## 四、技术细节

### 4.1 核心模型

- 图数据服务：扫描脚本目录，产出节点、边、分区。
- 节点：一个脚本，带相对路径、所在文件夹、meta、时长与轨道构成（在时间线上，不在 meta）、触发器清单；每个触发器带前置条件。
- 边：一条前置依赖。A 的某个触发器以前置条件指向 B，则画一条 B 到 A 的有向边；数组元素为字符串时即脚本 id，对象型取其中的脚本 id，自定义类型没有脚本 id。
- 分区：一个文件夹，`scripts` 下的子文件夹递归映射。
- 布局：第一版自研，拓扑分层加简单防重叠，不引重型图库。
- 视图：无限画布、节点渲染、摘要、上下游高亮、跳转、搜索过滤。

### 4.2 契约

- 图数据由模组侧扫描提供，编辑器只渲染，不回写脚本。
- 请求与回执走编辑器统一的 `type`、`data`、`id` 信封，新增一类图数据消息。
- 数据一次全量给出；脚本增删改后的增量刷新留待以后。
- 节点以相对路径标识；边的两端按脚本 id 解析。
- 布局与视图状态归编辑器，不落到脚本文件。

### 4.3 数据形态

- 节点带相对路径、文件夹、meta 摘要、时长、轨道构成、触发器清单、去重后的前置脚本列表。
- 边带两端脚本、来源触发器类型、前置条件类型。
- 分区带文件夹路径与脚本数。
- 前置条件两种写法：字符串即脚本 id；对象写法按类型取脚本 id。
- 时长取有限内容末端估计；含无限内容不伪造时长，用 `infinite` 标记（`timeline.total_duration` 已确定删除，不再读取）。

### 4.4 边界

- 只读：不写脚本，不改前置依赖。
- 只画前置依赖边；自定义前置条件不成边。
- 成环不是错误，只做提示。
- 布局与视图状态不落到脚本文件；视图只在外部编辑器。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 继续用脚本列表 | 只列文件名 | 依赖关系不可见 | 看不出故事线 |
| 引重型图库（d3 / cytoscape / G6） | 现成布局与画布 | 依赖体积、样式难贴合分区语义 | 自研布局更可控 |
| 力导向布局 | 物理模拟 | 每次结果不同、文件夹语义丢失 | 拓扑分层可重复 |
| canvas 渲染 | 手动绘制 | 命中测试、文本、悬浮都要自己写 | 第一版按量级再定 |
| 只画脚本级依赖 | 同一对脚本的多条前置合成一条 | 丢失触发器粒度 | 边保留触发器与条件 |

**本方案的缺点**：

- 大包规模下的渲染方式未定。
- 前置成环只能提示，不能自动理清顺序。
- 自动布局算出的位置不能由作者保留，要等后置的整理阶段。
- 图不随脚本增删改自动更新，需要重开或刷新。

## 六、实现结果

状态：实现中。步骤 1 到 4 已落地（图数据、只读全景、阅读交互、搜索过滤）；步骤 5 手动布局与持久化、增量刷新未做。

| 设计目标 | 实现 | 说明 |
|---|---|---|
| 分区内拓扑分层加简单防重叠 | 另加跨分区列对齐、同层按父节点平均行号排序、成环先缩点再分层（2026-10-07） | 跨文件夹依赖要保持左到右，减少交叉 |
| 环用醒目虚线提示 | 环内节点同层，橙色虚线 | 与整体配色一致 |
| 渲染方式按量级再定（canvas 或 svg） | HTML 节点加 SVG 连线，同一世界层变换 | 实测规模仅十余节点 |
| 交互分基础、阅读、跳转、定位、整理五期 | 前四期一次落地，整理期未做 | 前四期成本低 |
| 布局持久化存储位置待定 | 未实现，位置未定 | — |
| 未设计数据异常提示 | 新增悬空引用、自引用、重复脚本 id、不可解析四类提示，上限 200 条 | 图上要能看出坏数据 |
| 节点描述按原文显示 | 扫描时解析文本资源引用（2026-10-07） | 脚本 i18n 同批 |

## 七、落地顺序

| # | 步骤 | 交付物（完成后我们要什么） | 状态 |
|---|---|---|---|
| 1 | 数据协议：Java 侧脚本扫描 + 图数据消息 | WebUI 能拿到全量图数据（节点 / 边 / 分区） | ✅ 已落地 |
| 2 | 只读全景图：文件夹分区 + 拓扑分层自动布局 + 平移缩放 | 打开视图就能看到整个包的故事线全貌 | ✅ 已落地 |
| 3 | 阅读交互：悬停摘要、上下游高亮、双击打开脚本 | 从图跳进编辑器编辑任意脚本 | ✅ 已落地 |
| 4 | 搜索过滤 | 按名称 / 触发器类型 / 文件夹过滤定位 | ✅ 已落地（成本低，随 2–3 一起做） |
| 5 | 手动布局与持久化（后置） | 作者调整过的布局重开不丢 | ⬜ 未做 |

> 步骤 1–3 是核心价值（看懂故事线）；4–5 是体验增强。

## 八、事实核查（2026-10-07）

依据：本仓库 `common/src/main/java/com/immersivecinematics/immersive_cinematics/` 源码。

### ① 核实为真

- **`{ type, data, id }` 信封**：`webui/WebEditorApi.java` 类注释（:19）；入站解析 :28–32；出站组装 :271–275。传输层 `webui/WebSocketSession.java` 只做 WebSocket 帧收发，不涉及信封。
- **现有消息类型中无图数据消息**：`WebEditorApi.java` 的 `switch (type)`（:35–71）仅含 `hello`、`script.list/load/save/delete/new/validate`、`registry.query/get`、`schema.get`、`editor.seek/play/pause/stop/setCamera/pushScript/enter_flight_mode/exit_flight_mode`；全仓库 grep `graph` 在 `webui/` 与 `editor/src` 均无命中。
- **`scripts/` 递归扫描 + 子文件夹**：`webui/ScriptFileService.listScripts()`（:25）用 `Files.walk(SCRIPTS_DIR, 5)`（:28），过滤 `.json`，返回正斜杠相对路径（:34）。`script/ScriptManager.loadFromDir`（:51）用 `Files.walk(dir, MAX_SCRIPT_DEPTH)`，`MAX_SCRIPT_DEPTH = 5`（:27）。
- **触发器清单可读**：`ScriptMeta.getTriggers()` 返回 `List<TriggerDefinition>`。
- **轨道构成可读**：`Timeline.getTracks()`（`Timeline.java`，`List<TimelineTrack> tracks`）。
- **步骤 1 接入点可复用**：`ScriptFileService.listScripts()` / `loadScript(String relativePath)`（:40）已提供扫描与读取；`ScriptManager.getScript(id)` / `getAllScripts()` 提供解析后的脚本对象。

### ② 已修正

- **`requires` 不是脚本级字段**：实际是**触发器级**数组，位于 `meta.triggers[].requires`，解析于 `ScriptParser.parseTriggerRequires`（`ScriptParser.java`:573–609），存于 `TriggerDefinition.requires`（`TriggerDefinition.java`:16，`getRequires()` :50），注册进 `TriggerRegistration`。
- **时长 / 轨道构成不在 `meta`**：`ScriptMeta` 字段为 id/name/author/version/description/behavior/priority/dimension/triggers/skipVoteRatio，**无时长、无轨道**；时长来自 `Timeline.getTotalDuration()`（`CinematicScript.getTotalDuration()` 转发），轨道来自 `Timeline.getTracks()`。
- **边的元素形态不止字符串**：`requires` 元素可为字符串（= 脚本 id，等价 `{"type":"script_played","script":"id"}`）或对象 `{"type":"...", ...}`；内置 `script_played`/`script_started`/`script_completed` 的脚本 id 在 `data.script`（`TriggerRequirement.scriptPlayed/scriptStarted/scriptCompleted`），自定义类型无脚本 id。
- **方法名**：任务描述中的 `readScript` 实为 `ScriptFileService.loadScript`（:40）。

### ③ 补全的信息

- `ScriptFileService.SCRIPTS_DIR = Paths.get("immersive_cinematics", "scripts")`（相对进程 CWD）；`ScriptManager.GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts"`，但基于 `server.getServerDirectory()`（`ScriptManager.loadAll` :44）。二者目录字符串一致、基准不同——WebUI 走 `ScriptFileService`（客户端进程侧），图数据扫描复用后者。
- `ScriptManager.loadFromDir` 在加载期已做 `requires` 引用校验（指向不存在脚本 / 自引用 → 写 `ErrorLog`，`ScriptManager.java`:115–127），可复用于图中悬空边的提示。
- `resolveSafe`（`ScriptFileService`:52）做路径越界防护，图数据读取路径沿用。

### ④ 无法核实（未验证）

- 无。布局算法、交互分期、落地顺序属方向稿，无代码断言需核实。

## 九、落地记录（2026-10-07，步骤 1–4）

### 数据侧（Java）

- 新增 `webui/ScriptGraphService.java`：复用 `ScriptFileService.listScripts()/loadScript()`（含 `resolveSafe` 越界防护）扫描 `immersive_cinematics/scripts`，容错读取（不依赖 `ScriptParser`——解析失败即停是运行时口径，写坏的脚本也要出现在图上）。
- 新增消息：请求 `script.graph`（无 data）→ 回执 `script.graph.result`（`id` 原样回传），走既有 `{type,data,id}` 信封（`WebEditorApi.handle` 的 switch 分支）。
- 数据形态：
  - `nodes[]`：`id`（= 相对路径，唯一）/ `path` / `folder`（"" = 根）/ `scriptId`（meta.id）/ `name` / `author` / `description` / `dimension` / `priority` / `duration`（有限内容 = `contentEnd`；含无限不伪造，另给 `infinite`）/ `infinite` / `tracks[{type,clips}]` / `triggers[{type,repeatable,requires[{type,script,resolved}]}]` / `requires[]`（去重脚本 id）/ `valid` / `error?`；
  - `edges[]`：`from`（被依赖脚本的节点 id）→ `to`（依赖方节点 id）+ `fromScript`/`toScript`/`trigger`/`requirement`；同一 (from,to,trigger,requirement) 只留一条；
  - `folders[{path,count}]`：分区（= 文件夹，根在前）；
  - `warnings[{kind,path,message}]`：`dangling`（指向不存在脚本，与 `ScriptManager.loadFromDir` 的加载期校验同口径）/ `self_reference` / `duplicate_id` / `invalid`（读失败 / JSON 坏 / meta.id 或 timeline 缺失），上限 200 条。
- 边方向：被依赖 → 依赖方（B 被 A requires 则 B → A）。
- 版本口径：只做 `requires` 边；触发器 → 脚本、EVENT 调用链仍为后续。

### 视图侧（前端）

- 新增 `editor/src/graphLayout.ts`（纯函数布局，无第三方图库）+ `editor/src/components/ScriptGraph.vue`（无限画布）+ `store.viewMode`（`'editor' | 'graph'`）；入口：标题栏「视图 → 脚本架构图」与菜单栏一键切换按钮，双击节点跳回编辑器并 `loadScript(path)`。
- 布局：泳道 = 文件夹（根在前），**列跨泳道对齐**（跨文件夹依赖仍是左→右流向）；分区内按拓扑分层（被依赖在左）；孤立脚本在分层区右侧按 3 行网格收拢；`requires` 成环用 Tarjan SCC 缩点后再分层——环内节点同层、标橙虚线。
- 交互：拖拽平移（左键空白 / 中键任意处）+ 滚轮指针锚定缩放（15%–250%）+ 适配视图；悬停摘要（时长 / 轨道构成 / 触发器与 requires / 说明 / 维度 / 作者 / 脚本问题）；单击高亮上下游链（上游绿、下游蓝、其余压暗，Esc 取消）；按名称 / 触发器类型 / 文件夹过滤（AND 组合，可「只看匹配」隐藏其余）；顶部横幅列出图数据提示。
- 渲染方式：HTML 节点 + SVG 连线（同一条 transform 世界层）。实测规模仅 11 节点，量级压测与 canvas 迁移留到有真实大包时再定。

### 未做

- 手动拖拽布局与持久化（步骤 5）；
- 脚本增删改后的增量刷新（每次打开视图 / 手动刷新全量重扫）；
- 反向编辑（拖拽连线改 `requires`）、触发器 / EVENT 边；
- 布局持久化形态。
