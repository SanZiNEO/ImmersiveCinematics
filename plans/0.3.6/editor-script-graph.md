# 0.3.6 WebUI 无限画布：脚本架构图（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、协议细节、布局算法等执行时再定
>
> 相关文档：
> - [编辑器 WebUI 迁移](./editor-webui-migration.md)

---

## 1. 定位（已确认）

WebUI 编辑器新增一个**脚本架构图**视图：把包内所有脚本铺成一张直观的平面图，让作者看清自己的脚本是怎么串成故事线的。

已确认：

- **无限画布**形态（平移 / 缩放），对标组织架构图 / 节点图的交互习惯；
- **分组依据 = `scripts/` 文件夹**（第一维度）；
- **连线依据 = `requires` 前置依赖**，形成脚本树 / 脚本网（第二维度）；
- 落在 **WebUI 编辑器**；游戏内编辑器不做（整体退役方向见[迁移文档](./editor-webui-migration.md)）。

**不属于本文**：WebUI 编辑器整体架构与通信协议 → 迁移文档；触发器条件编辑 → [触发器条件体系](./trigger-conditions.md)。

---

## 2. 数据（方向）

图数据由 Java 侧扫描提供，走迁移文档的 `{ type, data, id }` 信封，新增一类图数据消息：

- **节点 = 脚本**：相对路径、所在文件夹、meta、时长 / 轨道构成（在 `Timeline` 上，非 `ScriptMeta`）、触发器清单（`requires` 挂在每个触发器上，见文末核查）；
- **边 = 触发器的 `requires` 前置依赖**：A 的某触发器 requires B → B → A 一条有向边（数组元素为字符串时即脚本 id；对象型取 `data.script`，自定义类型无脚本 id）；
- **分区 = 文件夹**：`scripts/` 的子文件夹递归映射。

---

## 3. 布局（方向）

- 文件夹作为**泳道 / 分区**（分组外框 + 标题）；
- 分区内按依赖**拓扑分层**：被依赖的在上（或左），依赖方在下（或右），形成故事线流向；
- 无依赖的孤立脚本：分区内网格收拢，不散落；
- 第一版**自研布局**（拓扑分层 + 简单防重叠），不引重型图库。

---

## 4. 交互（方向，分期）

| 期 | 交互 |
|---|---|
| 基础 | 平移、缩放；节点渲染（脚本名 + 触发器类型标记） |
| 阅读 | 悬停显示摘要（时长、轨道构成、触发条件、requires）；点击节点 / 边高亮上下游链 |
| 跳转 | 双击节点在编辑器里打开该脚本 |
| 定位 | 按名称 / 触发器类型 / 文件夹搜索过滤 |
| 整理（后置） | 节点手动拖拽，布局持久化（存储位置待定） |

---

## 5. 可能的问题

- 大包的渲染规模：节点上百时用 canvas 还是 svg，执行时按量级定。
- `requires` 成环的呈现（标红 / 虚线提示，不算错误——内容由作者负责）。
- 触发器 → 脚本、EVENT 调用链是否后续加成第二、三类边（本次确认**先做 requires**）。
- 布局文件存哪（`scripts/` 下隐藏文件还是编辑器本地存储）。
- 脚本增删改后的增量刷新。

---

## 6. 待定

- 边的种类扩展（触发器、EVENT 调用链）。
- 布局持久化形态。
- 画布是否支持反向编辑（拖拽连线改 `requires`——远期）。

---

## 7. 落地顺序（方向）

| # | 步骤 | 交付物（完成后我们要什么） |
|---|---|---|
| 1 | 数据协议：Java 侧脚本扫描 + 图数据消息 | WebUI 能拿到全量图数据（节点 / 边 / 分区） |
| 2 | 只读全景图：文件夹分区 + 拓扑分层自动布局 + 平移缩放 | 打开视图就能看到整个包的故事线全貌 |
| 3 | 阅读交互：悬停摘要、上下游高亮、双击打开脚本 | 从图跳进编辑器编辑任意脚本 |
| 4 | 搜索过滤 | 按名称 / 触发器类型 / 文件夹过滤定位 |
| 5 | 手动布局与持久化（后置） | 作者调整过的布局重开不丢 |

> 步骤 1–3 是核心价值（看懂故事线）；4–5 是体验增强。

---

## 事实核查（2026-10-07）

依据：本仓库 `common/src/main/java/com/immersivecinematics/immersive_cinematics/` 源码。

### ① 核实为真

- **`{ type, data, id }` 信封**：`webui/WebEditorApi.java` 类注释 `协议：{ "type": "...", "data": {...}, "id": "..." }`（:19）；入站解析 :28–32；出站组装 :271–275。传输层 `webui/WebSocketSession.java` 只做 WebSocket 帧收发（`sendText`/`sendFrame`），不涉及信封。
- **现有消息类型中无图数据消息**：`WebEditorApi.java` 的 `switch (type)`（:35–71）仅含 `hello`、`script.list/load/save/delete/new/validate`、`registry.query/get`、`schema.get`、`editor.seek/play/pause/stop/setCamera/pushScript/enter_flight_mode/exit_flight_mode`；全仓库 grep `graph` 在 `webui/` 与 `editor/src` 均无命中。§2“新增一类图数据消息”属实。
- **`scripts/` 递归扫描 + 子文件夹**：`webui/ScriptFileService.listScripts()`（:25）用 `Files.walk(SCRIPTS_DIR, 5)`（:28），过滤 `.json`，返回正斜杠相对路径（:34）。`script/ScriptManager.loadFromDir`（:51）用 `Files.walk(dir, MAX_SCRIPT_DEPTH)`，`MAX_SCRIPT_DEPTH = 5`（:27）。两处递归深度均为 5，§2“子文件夹递归映射”属实。
- **触发器清单可读**：`ScriptMeta.getTriggers()` 返回 `List<TriggerDefinition>`。
- **轨道构成可读**：`Timeline.getTracks()`（`Timeline.java`，`List<TimelineTrack> tracks`）。
- **步骤 1 接入点可复用**：`ScriptFileService.listScripts()` / `loadScript(String relativePath)`（:40）已提供扫描与读取；`ScriptManager.getScript(id)` / `getAllScripts()` 提供解析后的脚本对象。

### ② 已修正

- **`requires` 不是脚本级字段**：旧表述（§2 节点含“`requires` 列表”、边为脚本级“A requires B”）→ 实际 `requires` 是**触发器级**数组，位于 `meta.triggers[].requires`，解析于 `ScriptParser.parseTriggerRequires`（`ScriptParser.java`:573–609），存于 `TriggerDefinition.requires`（`TriggerDefinition.java`:16，`getRequires()` :50），注册进 `TriggerRegistration`。已改 §2 表述。
- **时长 / 轨道构成不在 `meta`**：`ScriptMeta` 字段为 id/name/author/version/description/behavior/priority/dimension/triggers/skipVoteRatio，**无时长、无轨道**；时长来自 `Timeline.getTotalDuration()`（`CinematicScript.getTotalDuration()` 转发），轨道来自 `Timeline.getTracks()`。已改 §2。
- **边的元素形态不止字符串**：`requires` 元素可为字符串（= 脚本 id，等价 `{"type":"script_played","script":"id"}`）或对象 `{"type":"...", ...}`；内置 `script_played`/`script_started`/`script_completed` 的脚本 id 在 `data.script`（`TriggerRequirement.scriptPlayed/scriptStarted/scriptCompleted`），自定义类型无脚本 id。已改 §2。
- **方法名**：任务描述中的 `readScript` 实为 `ScriptFileService.loadScript`（:40）；`listScripts` 属实。

### ③ 补全的信息

- `ScriptFileService.SCRIPTS_DIR = Paths.get("immersive_cinematics", "scripts")`（相对进程 CWD）；`ScriptManager.GLOBAL_SCRIPT_DIR = "immersive_cinematics/scripts"`，但基于 `server.getServerDirectory()`（`ScriptManager.loadAll` :44）。二者目录字符串一致、基准不同——WebUI 走 `ScriptFileService`（客户端进程侧），图数据扫描应复用后者。
- `ScriptManager.loadFromDir` 在加载期已做 `requires` 引用校验（指向不存在脚本 / 自引用 → 写 `ErrorLog`，`ScriptManager.java`:115–127），可复用于图中悬空边的提示。
- `resolveSafe`（`ScriptFileService`:52）做路径越界防护，图数据读取路径应沿用。

### ④ 无法核实（未验证）

- 无。§3–§7 的布局算法、交互分期、落地顺序属方向稿，无代码断言需核实。
