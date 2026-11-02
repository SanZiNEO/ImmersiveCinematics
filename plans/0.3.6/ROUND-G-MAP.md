# G 轮项目地图（主代理）

> 建立时间：2026-11-02。依据：`plans/0.3.6/G-script-model/` 全部设计文档 + 源码核对。
> 本文件是本轮派单与验收的唯一索引；行号仅供定位，以类名 / 方法签名为准。

## 一、轮次范围与总体状态

| 交付单元 | 状态 | 证据 |
|---|---|---|
| G-math 表达式层 + 9 种曲线 + 精确圆锥 + 弧长/LUT + 连续性/复合 + 轴约束 | 代码已实现 | `common/src/main/java/.../math/path/` 30 文件；`script/schema/PathCurveRegistry` |
| `clip.curve` 多类型接入 + 旧链路删除 | 已实现 | `Clip.getPathSpec()`、`ScriptParser.parsePathCurve`、`ScriptValidator.checkPathCurve`；`BezierPathStrategy`/`ArcLengthLUT`/`BezierCurve` 已无残留 |
| G4 时钟抽象步骤 1–5 | 已实现 | `util/Clock`、`GameClock`、`PreviewClock`；`clock-abstraction.md` §六「已实现（五步全部落地）」 |
| 编辑器播放语义（`editor-playback.md`） | **未实现** | `PlaybackInstance.isPreview()`、`PlaybackRegistry.instanceBehaviors()` 跳过编辑器实例、`PlaybackLifecycle` 编辑器分支 |
| 文档回写 / 全量验证 / 提交 | 部分 | 见 §四 |

工作树基线：`main` 分支，HEAD `434ef5f`，工作树干净。

## 二、任务清单（编号 + 目标 + 验收）

### G-A 编辑器播放语义（代码，唯一未实现的功能块）

**目标**：编辑器播放 = 播放引擎的另一种时钟源；内容行为全部参与并集，游戏控制行为（键鼠屏蔽）不参与；命名与注释统一为「编辑器播放」。

**验收**：

1. `sh gradlew :common:compileJava` 成功（模块级）。
2. `grep -rn "isPreviewMode\|isPreviewPaused\|\.isPreview()" common/src/main/java` 无残留；保留项见 §3.4。
3. 无头探针 `E:/tmp/gunion` 全部 PASS：
   - 仅编辑器实例 + `hide_hud=false` → `hideHud=false`；
   - 编辑器实例 `block_keyboard=true` → `blockKeyboard=false`；
   - 游戏实例 `block_keyboard=true` → `blockKeyboard=true`；
   - `hud_layers` 双实例并集 + 未声明键回落各自 `hide_hud`；
   - 编辑器 `hide_hud=true` + 游戏 `hide_hud=false` → `hideHud=true`（内容行为参与并集）。
4. 代码审查：`PlaybackLifecycle.startScriptInternal` 的编辑器分支已删除，统一走 `recomputeUnion`；`releaseAllKeys()` 只对游戏实例执行。

### G-B1 G-math 文档回写（纯文档）

**目标**：把已实现的 15 篇状态与实测结果写回，不虚构未实现项。

**验收**：
- `G-math/README.md`：顶部状态行 + 15 行状态表逐行更新（`expression-contract`/`curve-bezier`/`arc-length`/`acceptance` 是旧状态）。
- `expression-contract.md` §六、`curve-bezier.md` §4.3 失效段落（`BezierCurve`/`MathUtil.cubicBezier` 已随旧链路删除）+ §六、`arc-length.md` §六、`acceptance.md` §六（470 项按四组归类）。
- 未实现项如实标注：`axis-constraints.md` 切空间/整条投影、`continuity-composition.md` 回旋线插入策略（均归 I 轮路径条目）。
- 不改 `G-math/` 以外任何文件。

### G-B2 G 轮索引与进度回写（依赖 G-A）

**目标**：G 轮索引、编辑器播放文档、进度表与实际状态一致。

**验收**：
- `G-script-model/README.md`：顶部状态、§二文档地图状态、§六实现结果。
- `editor-playback.md`：顶部状态 + §八实现结果（写清行为并集分组、画面源、尺寸现状 1280×720）。
- `implementation-progress.md`：批次 G 表 G4/编辑器播放状态 + 进度日志追加一行。
- `script-model.md` §六：`时间精度未开始` 已过时，改为指向 `clock-abstraction.md` 已完成。

### G-B3 范围外迁出标注核对（纯文档）

**目标**：G1/G2/G3/G5 的归属在文档中可查。

**现状核对**：
- G1/G2 → I 轮：`I-script-core/README.md` §三、`object-model.md`、`schema-and-types.md` 已有吸收说明；`G-script-model/README.md` §四已有。
- G3 延后：`script-model.md` §二、`implementation-progress.md` 已有。
- G5 → Z 轮：`Z-editor/templates.md` §7 已有。

**验收**：以上任一处缺失才补；已齐则不重复写。核对结果写进回报。

### G-B4 全量验证（依赖 G-A）

**目标**：代码与文档改动不引入回归。

**验收**：
- `sh gradlew compileJava`（含 fabric/forge）成功。
- `sh E:/tmp/icv/run.sh cinematics`：解析 217/217、校验 issue 与基线逐行一致。
- `sh E:/tmp/gcurve/run.sh`：470 项全 PASS。
- `sh E:/tmp/gclock/run.sh`、`sh E:/tmp/gclock/run-clock.sh`（需补 fabric 桩）通过。
- `E:/tmp/icv2`、`E:/tmp/icgl` 各 14 harness 通过。
- 实机项（F9 预览屏 HUD/键鼠、bezier 像素回归、新曲线脚本）**无法由主代理自动执行**，如实标注「未执行，需人工」。

### G-B5 提交（依赖 G-A/B1/B2/B3/B4）

- 代码一笔：`feat(0.3.6): 编辑器播放语义`。
- 文档一笔：`docs(0.3.6): G 轮回写`。
- 不 push。

## 三、真实文件 / 类 / 方法地图

### 3.1 编辑器播放语义（G-A）

| 文件 | 现状 | 目标 |
|---|---|---|
| `camera/PlaybackInstance.java` | 字段 `preview`（:47）、`isPreview()`（:72）、类 javadoc 写「预览实例…不参与行为并集」 | 字段 `editorDriven`、`isEditorDriven()`；javadoc 改为「参与内容行为、不参与游戏控制」 |
| `camera/PlaybackRegistry.java` | `instanceBehaviors()`（:157-163）跳过 `isPreview()`，返回 `List<ScriptMeta.RuntimeBehavior>` | 返回 `List<CinematicController.BehaviorEntry>`，**含全部实例**并标 `editorDriven` |
| `control/CinematicController.java` | `recomputeUnion(List<RuntimeBehavior>)`（:56-127）逐位 OR，无编辑器维度 | 新增 `record BehaviorEntry(RuntimeBehavior behavior, boolean editorDriven)`、`record Union(...)`、`static Union computeUnion(List<BehaviorEntry>)`、`void applyUnion(Union)`；`recomputeUnion` 保留为兼容入口或改为 `recomputeUnion(List<BehaviorEntry>)` |
| `camera/PlaybackLifecycle.java` | `startScriptInternal` 编辑器分支（:401-414）：只放行键鼠 / 按游戏实例重算；`releaseAllKeys`（:375-378）不区分实例 | 删除分支统一 `recomputeUnion`；`releaseAllKeys` 加 `!editorDriven` 条件 |
| `camera/FrameDriver.java` | `instance.isPreview()` 用于暂停态 / 时钟 / 账本 / 日志（:116,118,134,166,206） | 全部改 `isEditorDriven()`；语义不变 |
| `camera/PlaybackLedger.java` | `instance.isPreview()` 门控上报（:75） | 改 `isEditorDriven()` |
| `camera/PreviewChannel.java` | `isPreviewMode()`（:62）、`isPreviewPaused()`（:67）、字段 `previewMode`/`previewPaused` | 方法改 `isEditorPlayback()`/`isEditorPaused()`；字段改 `editorPlayback`/`editorPaused`；类 javadoc 重写 |
| `camera/CameraManager.java` | `isPreviewMode()`（:122）、`isPreviewPaused()`（:123）、`getPreviewTimeSeconds()`（:129） | 前两者改 `isEditorPlayback()`/`isEditorPaused()`；`getPreviewTimeSeconds` 保留（播放头读数） |
| `camera/PlayerMoveController.java` | `CameraManager.INSTANCE.isPreviewMode()`（:92） | 改 `isEditorPlayback()` |
| `trigger/client/CameraCenterReporter.java` | `CameraManager.INSTANCE.isPreviewMode()`（:83） | 改 `isEditorPlayback()` |
| `webui/WebEditorApi.java` | `isPreviewMode()`/`isPreviewPaused()`（:295-296） | 改 `isEditorPlayback()`/`isEditorPaused()` |
| `webui/WebFrameCapture.java` | 已取 `mc.getMainRenderTarget()`（:37）；类注释只写「主 RenderTarget 缩放」 | 注释明确「主 RenderTarget（含 HUD、lane 合成、调色），不是合成 FBO」；尺寸 1280×720 现状写清 |
| `webui/WebPreviewScreen.java` | 类 javadoc 写「画面传输 / 播放控制 / 飞控」 | 明确「传输主画面 + 编辑器时钟」 |
| `client/lane/LaneDiagnostics.java` | `inst.isPreview()`（:521,523），JSON 字段 `"preview"` | 方法改 `isEditorDriven()`；**JSON 字段名保留**（协议兼容） |
| `client/lane/ScriptLaneDriver.java` | `instance.isPreview()`（:123,157） | 改 `isEditorDriven()`；`captureId` 后缀 `_preview` 保留 |

### 3.2 G-math（已完成，供文档回写引用）

- 包：`common/src/main/java/com/immersivecinematics/immersive_cinematics/math/path/`（30 文件）。
- 注册表：`script/schema/PathCurveRegistry`（`DEFAULT_TYPE="bezier"`、`create`、`types`、`capabilities`、`params`）。
- 接入：`Clip.getPathSpec()`、`ScriptParser.parsePathCurve`、`ScriptValidator.checkPathCurve`、`SchemaExporter`（`curveTypes` 段）。
- 旧链路已删：`BezierPathStrategy`、`ArcLengthLUT`、`BezierCurve` 均无残留；`MathUtil.cubicBezier` 仍在（其他用途），但 `curve-bezier.md` §4.3 关于 `BezierCurve` javadoc 的段落已失效。

### 3.3 时钟（已完成，供文档回写引用）

- `util/Clock`、`util/GameClock`、`util/PreviewClock`。
- 运行侧 double：`ScriptPlayer`、`TrackPlayer` + 6 实现、`KeyframeInterpolator`、`CameraKeyframeEvaluator`、`TransitionSpec`、`CameraLane.clipLocalTime`、`PlayerMoveController`、`RuleEngine/RuleActions`、`PreviewChannel`、`PlaybackInstance/Lifecycle`、`WebEditorApi`。
- 存储侧 float：`Clip`/`Keyframe`/`Timeline`；唯一窄化点 `C2SScriptPausePacket`。

### 3.4 命名保留项（不要改）

- 类名 `PreviewChannel`、`PreviewClock`、`WebPreviewScreen`（设计文档明确保留）。
- `PreviewChannel.previewInstance()` / `setPreviewInstance` / `isPreviewInitialized` / `setPreviewInitialized` / `isPreviewDirectControl` / `setPreviewDirectControl`（通道内概念）。
- `CameraManager.getPreviewTimeSeconds()`（播放头读数）。
- `LaneDiagnostics` 的 JSON 字段 `"preview"`、`ScriptLaneDriver` 的 `captureId` 后缀 `_preview`（诊断/捕获协议）。
- `FrameDriver` 日志字段名可改（非协议），但 `LaneDiagnostics` JSON 不改。

### 3.5 文档回写目标文件

| 文件 | 要改的章节 |
|---|---|
| `G-script-model/G-math/README.md` | 顶部状态、§一 15 行表 |
| `G-math/expression-contract.md` | 顶部状态、§六 |
| `G-math/curve-bezier.md` | 顶部状态、§4.3 失效段、§六 |
| `G-math/arc-length.md` | 顶部状态、§六 |
| `G-math/acceptance.md` | 顶部状态、§六（470 项四组归类） |
| `G-script-model/README.md` | 顶部状态、§二表、§六 |
| `G-script-model/editor-playback.md` | 顶部状态、§八 |
| `G-script-model/script-model.md` | §六「时间精度未开始」 |
| `implementation-progress.md` | 批次 G 表、进度日志 |

## 四、依赖顺序

```
G-A（代码）──┬─→ G-B4（全量验证）─→ G-B5（提交）
             └─→ G-B2（G 轮索引/进度回写）─┘
G-B1（G-math 回写）──┘
G-B3（迁出标注核对）─┘
```

- G-A 与 G-B1 无依赖，可并行。
- G-B2 依赖 G-A（实现结果要写代码事实）。
- G-B4 依赖 G-A（编译/探针）。
- G-B5 最后。

## 五、已确认的现状与缺口

1. **核心 bug**：`PlaybackRegistry.instanceBehaviors()` 跳过编辑器实例 → 编辑器里脚本 `hide_hud` / `hud_layers` / `hide_*` 全部不生效。
2. **命名分裂**：`PlaybackInstance.isPreview()` 一个布尔同时表达时钟来源、内容语义、游戏控制。
3. **行为并集无维度**：`recomputeUnion` 只接受 `List<RuntimeBehavior>`，无法区分「内容行为」与「游戏控制行为」。
4. **画面源已正确**：`WebFrameCapture.capture` 取 `mc.getMainRenderTarget()`，含 HUD；尺寸硬编码 1280×720，无配置项（文档写明现状即可）。
5. **时钟已收尾**：G4 五步全落地，`clock-abstraction.md` §六已回写；`script-model.md` §六仍写「时间精度未开始」，需修正。
6. **G-math 文档滞后**：5 篇状态行/实现结果仍是旧状态；`curve-bezier.md` §4.3 引用了已删除的 `BezierCurve`。
7. **迁出标注已基本齐全**：G1/G2 在 I 轮三处、G3 在 script-model/进度表、G5 在 Z 轮 templates §7；只需核对，不重复写。

## 六、验证工具（仓库外）

| 工具 | 命令 | 覆盖 |
|---|---|---|
| `E:/tmp/gcurve` | `sh run.sh` | 470 项曲线/弧长/连续性/轴约束/fixture |
| `E:/tmp/gclock` | `sh run.sh`、`sh run-clock.sh` | 覆盖层帧间隔、elapsed 精度 |
| `E:/tmp/icv` | `sh run.sh <脚本目录>` | 无头解析/校验 |
| `E:/tmp/icv2`、`E:/tmp/icgl` | `sh run.sh` | 数据层 / GL |
| `E:/tmp/gunion`（本轮新建） | `sh run.sh` | `computeUnion` 无头断言 |

编译口径：日常 `sh gradlew :common:compileJava`；收口 `sh gradlew compileJava`。

## 七、验收结果（2026-11-02）

- 无头：`computeUnion` 20/20；`gcurve` 470/470；`gclock` 两档全过；`icv` 231 脚本 / 82 条 issue（与交接基线一致）；`icv2` 882 项、`icgl` 13 项。
- 实机 Fabric 0.3.6：游戏/编辑器实例 HUD 显隐、键鼠并集（编辑器 `block_keyboard=true` → 并集 `false`）、画面源（主 RenderTarget 2560×1392 → 1280×720）、退出恢复。
- 实机 Better MC BMC4 Forge 0.3.6（禁用 Radium）：同上通过。
- 兼容：Radium/Lithium 的 `ChunkMap.move @Overwrite` 已改为在 `ServerChunkCache.move` 期间临时替换玩家位置视图（`EntityAccessor` + `ServerChunkCacheCameraMixin`），仍走原版差集；详见 `F-playback/camera-center-entities.md` C1。
