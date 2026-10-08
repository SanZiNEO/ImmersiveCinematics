# 编辑器退役残留资源清点（0.3.6）

**状态**: ✅ 已清理（2026-10-08）—— 本文原为只清点清单；2026-10-08 已按 §3.1 执行删除，删除记录见 §3.1 末。
**范围**: 0.3.6 游戏内编辑器（Java `editor/` 包）退役后，`resources/` 下遗留的、已无 live 代码引用的纹理等资源。
**判定口径**: 「残留」= 该资源文件在当前 live 代码（common/fabric/forge 的 Java + mixin + webui 后端 + `editor/` 前端 + 资源 json/lang）中**零引用**；文档提及（`docs/`、`plans/`）**不算引用**。

---

## 1. 背景

- 0.3.6 决定**不留游戏内编辑器**，只维护独立 WebUI 编辑器（Vue 3 + Electron，源码在 `editor/`）。退役于 **2026-10-07** 落地，见 [editor-webui-migration.md](./editor-webui-migration.md) §4。
- 退役提交：`6d0544c` `refactor(0.3.6): 游戏内编辑器退役——删除 editor/ 包（10,176 行）+ F6/配置/lang 清理`。该提交删除了 `common/.../editor/` 整包 62 文件（含 `widget/`、`panel/`、`area/`、`fields/` 等），但**没有清理它消费的纹理资源**——`common/src/main/resources/assets/immersive_cinematics/textures/gui/editor/` 与 `.../gui/flight/` 原样留下。
- 残留的成因很直接：纹理是**按名字**被加载的，不在删除的 Java 源码里，删包时不会连带报错。加载方两处，均在已删除的 `editor/` 包里：
  - `editor/widget/EditorIcons.java`：`ICON_PATH = "textures/gui/editor/"`，`new ResourceLocation("immersive_cinematics", ICON_PATH + name + ".png")` —— 编辑器全部 64×64 UI 图标的总入口。
  - `editor/widget/FlightKeyHints.java`：`textures/gui/flight/keycap.png` 与 `textures/gui/flight/mouse.png` —— 编辑器内飞控按键提示图标。
- 当前 live 代码里，**不存在任何指向 `textures/gui/editor/` 或 `textures/gui/flight/` 的引用**（全仓 grep 零命中，见 §5）。

---

## 2. 清单总表

扫描范围：`common/src/main/resources/`（86 文件）、`fabric/src/main/resources/`（2 文件）、`forge/src/main/resources/`（3 文件），合计 **91 文件**。
「现存引用」列：`零` = 全仓零命中；`仍用` = 列出引用点；`仅文档` = 只有 `docs/`/`plans/` 提及（不计为引用）。

### 2.1 编辑器 UI 图标 `textures/gui/editor/*.png`（72 文件，合计 57,170 B）

| 文件（`assets/immersive_cinematics/textures/gui/editor/`） | 大小 | 原用途（推断 + 依据） | 现存引用 | 建议 |
|---|---|---|---|---|
| add-clip.png | 762 B | 编辑器时间轴「添加片段」按钮图标；退役前 `editor/area/TimelineArea.java:489` `drawIconBtn(..., "add-clip", ...)` | 零 | 删 |
| add-track.png | 615 B | 时间轴「添加轨道」按钮；`TimelineArea.java:493` | 零 | 删 |
| remove-track.png | 616 B | 时间轴「删除轨道」按钮；`TimelineArea.java:494` | 零 | 删 |
| delete.png | 914 B | 时间轴「删除片段」按钮；`TimelineArea.java:490` | 零 | 删 |
| keyframe.png | 776 B | 时间轴「添加关键帧」按钮 / 关键帧拖拽光标；`TimelineArea.java:491,1268` | 零 | 删 |
| circle-x.png | 1386 B | 时间轴「删除关键帧」按钮；`TimelineArea.java:492` | 零 | 删 |
| eye.png | 1525 B | 轨道可见性按钮（显示态）；`TimelineArea.java:575`、`panel/TrackListPanel.java:42` | 零 | 删 |
| eye-off.png | 1423 B | 轨道可见性按钮（隐藏态）；`TimelineArea.java:575` | 零 | 删 |
| lock.png | 868 B | 轨道锁定按钮（锁定态）；`TimelineArea.java:579` | 零 | 删 |
| lock-open.png | 860 B | 轨道锁定按钮（解锁态）；`TimelineArea.java:579` | 零 | 删 |
| mute.png | 710 B | 轨道静音按钮（静音态）；`TimelineArea.java:584` | 零 | 删 |
| volume.png | 759 B | 轨道静音按钮（有声态）；`TimelineArea.java:584` | 零 | 删 |
| play.png | 431 B | 预览区播放按钮；`area/PreviewArea.java:52,89` | 零 | 删 |
| pause.png | 389 B | 预览区暂停按钮；`PreviewArea.java:86` | 零 | 删 |
| stop.png | 356 B | 预览区停止按钮；`PreviewArea.java:54` | 零 | 删 |
| arrow-up.png | 524 B | 编辑器通用箭头图标（上）；未在退役前代码中命中 | 零 | 删 |
| arrow-down.png | 524 B | 编辑器通用箭头图标（下）；未命中 | 零 | 删 |
| arrow-left.png | 370 B | 编辑器通用箭头图标（左）；未命中 | 零 | 删 |
| arrow-right.png | 363 B | 编辑器通用箭头图标（右）；未命中 | 零 | 删 |
| chevron-up.png | 340 B | 下拉/折叠指示（单箭头，上）；未命中 | 零 | 删 |
| chevron-down.png | 331 B | 下拉/折叠指示（单箭头，下）；未命中 | 零 | 删 |
| chevron-left.png | 302 B | 折叠指示（单箭头，左）；未命中 | 零 | 删 |
| chevron-right.png | 303 B | 折叠指示（单箭头，右）；未命中 | 零 | 删 |
| chevrons-left.png | 587 B | 双箭头（左，如跳到开头）；未命中 | 零 | 删 |
| chevrons-right.png | 589 B | 双箭头（右，如跳到结尾）；未命中 | 零 | 删 |
| circle-plus.png | 1317 B | 圆形加号（新建/添加）；未命中 | 零 | 删 |
| plus.png | 332 B | 加号；未命中 | 零 | 删 |
| minus.png | 193 B | 减号；未命中 | 零 | 删 |
| close.png | 414 B | 关闭；未命中 | 零 | 删 |
| check.png | 377 B | 勾选/确认；未命中 | 零 | 删 |
| dot.png | 413 B | 圆点（标记/marker）；未命中 | 零 | 删 |
| more.png | 659 B | 更多（横向省略号）；未命中 | 零 | 删 |
| more-vertical.png | 649 B | 更多（纵向省略号）；未命中 | 零 | 删 |
| copy.png | 854 B | 复制；未命中 | 零 | 删 |
| paste.png | 838 B | 粘贴；未命中 | 零 | 删 |
| save.png | 492 B | 保存；未命中 | 零 | 删 |
| open.png | 750 B | 打开；未命中 | 零 | 删 |
| import.png | 615 B | 导入；未命中 | 零 | 删 |
| export.png | 612 B | 导出；未命中 | 零 | 删 |
| folder.png | 662 B | 文件夹（脚本列表）；未命中 | 零 | 删 |
| undo.png | 710 B | 撤销；未命中 | 零 | 删 |
| redo.png | 732 B | 重做；未命中 | 零 | 删 |
| refresh.png | 1425 B | 刷新；未命中 | 零 | 删 |
| reset.png | 1416 B | 重置；未命中 | 零 | 删 |
| search.png | 1199 B | 搜索；未命中 | 零 | 删 |
| gear.png | 2113 B | 设置（齿轮）；未命中 | 零 | 删 |
| help.png | 1622 B | 帮助；未命中 | 零 | 删 |
| info.png | 1347 B | 信息；未命中 | 零 | 删 |
| warning.png | 985 B | 警告；未命中 | 零 | 删 |
| home.png | 681 B | 主页/复位；未命中 | 零 | 删 |
| link.png | 1326 B | 链接/关联；未命中 | 零 | 删 |
| code.png | 442 B | 代码/脚本；未命中 | 零 | 删 |
| script.png | 603 B | 脚本；未命中 | 零 | 删 |
| terminal.png | 677 B | 终端/控制台；未命中 | 零 | 删 |
| event.png | 919 B | 事件；未命中 | 零 | 删 |
| overlay.png | 808 B | 叠加层；未命中 | 零 | 删 |
| camera.png | 1160 B | 相机轨道；未命中 | 零 | 删 |
| audio.png | 1017 B | 音频轨道；未命中 | 零 | 删 |
| clock.png | 1378 B | 时间；未命中 | 零 | 删 |
| split.png | 792 B | 分割（razor/切分）；未命中 | 零 | 删 |
| razor.png | 740 B | 剃刀/切割工具；未命中 | 零 | 删 |
| pointer.png | 645 B | 指针/选择工具；未命中 | 零 | 删 |
| record.png | 1281 B | 录制；未命中 | 零 | 删 |
| star.png | 1179 B | 收藏/标记；未命中 | 零 | 删 |
| frame-all.png | 574 B | 全览/适配全部；未命中 | 零 | 删 |
| zoom-in.png | 1335 B | 放大；未命中 | 零 | 删 |
| zoom-out.png | 1266 B | 缩小；未命中 | 零 | 删 |
| next.png | 607 B | 下一个；未命中 | 零 | 删 |
| previous.png | 624 B | 上一个；未命中 | 零 | 删 |
| skip-back.png | 558 B | 回退；未命中 | 零 | 删 |
| skip-forward.png | 550 B | 快进；未命中 | 零 | 删 |
| sync.png | 659 B | 同步；未命中 | 零 | 删 |

> 说明：其中 **15 个**（add-clip / add-track / circle-x / delete / eye / eye-off / keyframe / lock / lock-open / mute / volume / play / pause / stop / remove-track）在退役前**确有引用**（`EditorIcons.render` / `UIButton.icon`），随编辑器一起成为孤儿；其余 **57 个**在退役前的 `editor/` 包里也**搜不到引用**——它们是 0.3.5 第 5 轮（`9555052` `0.3.5 round 5: flight HUD, editor icons, zoom rework...`）**整批引入的图标集**，属于「引进来但从未接线」的资源。两类都已零引用，删除结论相同。
> SVG 源文件已不在 resources 下：`0590fa1` `Move SVG source files out of resources to root svg directory` 把它们移到了仓库根的 `svg/editor/`（72 个 `.svg`，与 PNG 一一对应）。

### 2.2 飞控按键提示图标 `textures/gui/flight/*.png`（2 文件，合计 2,862 B）

| 文件（`assets/immersive_cinematics/textures/gui/flight/`） | 大小 | 原用途（推断 + 依据） | 现存引用 | 建议 |
|---|---|---|---|---|
| keycap.png | 777 B | 编辑器内飞控按键提示的「键帽」底图；退役前 `editor/widget/FlightKeyHints.java:28` `KEYCAP = .../flight/keycap.png` | 零 | 删 |
| mouse.png | 2085 B | 编辑器内飞控提示的「鼠标」图示；`FlightKeyHints.java:29` `MOUSE = .../flight/mouse.png` | 零 | 删 |

> 现行飞控 HUD 在 `webui/WebPreviewScreen.drawFlightHud()`，**只用文字**（`I18n` + `keyLabel`）绘制按键提示，**不加载任何纹理**（已通读该函数确认）。因此 `flight/` 两张图在 WebUI 时代没有承接方。
> SVG 源：`svg/flight/keycap.svg`、`svg/flight/mouse.svg`。

### 2.3 非编辑器资源（仍被使用 / 结构文件，全部保留）

| 文件 | 大小 | 用途 | 现存引用 | 建议 |
|---|---|---|---|---|
| `assets/immersive_cinematics/icon.png` | 83,903 B | 模组图标 | `fabric.mod.json:14` `"icon": "assets/immersive_cinematics/icon.png"`；`forge/.../mods.toml:14` `logoFile = "assets/immersive_cinematics/icon.png"` | 留 |
| `assets/immersive_cinematics/lang/en_us.json` | 2,509 B | 英文语言文件（含飞控/WebUI/HUD 键） | 走 MC 资源包 `assets/<ns>/lang/` 约定自动加载（无代码字符串）；键如 `key.immersive_cinematics.editor.flight.*`、`hud.immersive_cinematics.skip_hold` 由 `CinematicKeyBindings` / `SkipHudRenderer` 消费 | 留 |
| `assets/immersive_cinematics/lang/zh_cn.json` | 2,562 B | 中文语言文件 | 同上 | 留 |
| `assets/immersive_cinematics/textures/gui/skip_key.png` | 2,621 B | HUD 跳过提示键位图标 | `control/SkipHudRenderer.java:15` `SKIP_KEY_TEXTURE = new ResourceLocation(MOD_ID, "textures/gui/skip_key.png")` | 留 |
| `assets/minecraft/shaders/core/ic_color_adjust.json` | 4,512 B | 调色着色器程序 | `post/ColorAdjustPass.java:89` `SHADER_NAME = "ic_color_adjust"` | 留 |
| `assets/minecraft/shaders/core/ic_color_adjust.vsh` | 531 B | 调色着色器顶点 | 同上（`shaders/core/<name>.json` 引用） | 留 |
| `assets/minecraft/shaders/core/ic_color_adjust.fsh` | 17,493 B | 调色着色器片元 | 同上 | 留 |
| `assets/minecraft/shaders/core/ic_lane_blit.json` | 736 B | lane 合成 blit 着色器程序 | `client/lane/LaneCompositor.java:83` `SHADER_NAME = "ic_lane_blit"` | 留 |
| `assets/minecraft/shaders/core/ic_lane_blit.vsh` | 537 B | lane blit 顶点 | 同上 | 留 |
| `assets/minecraft/shaders/core/ic_lane_blit.fsh` | 1,528 B | lane blit 片元 | 同上 | 留 |
| `immersive_cinematics.mixins.json` | 1,390 B | common mixin 配置 | 构建 / mixin 加载 | 留 |
| `pack.mcmeta` | 101 B | common 资源包元数据 | 构建 | 留 |
| `fabric/src/main/resources/fabric.mod.json` | — | Fabric 模组元数据 | 加载 | 留 |
| `fabric/src/main/resources/immersive_cinematics.fabric.mixins.json` | — | Fabric mixin 配置 | 加载 | 留 |
| `forge/src/main/resources/immersive_cinematics.forge.mixins.json` | — | Forge mixin 配置 | 加载 | 留 |
| `forge/src/main/resources/META-INF/mods.toml` | — | Forge 模组元数据 | 加载 | 留 |
| `forge/src/main/resources/pack.mcmeta` | — | Forge 资源包元数据 | 构建 | 留 |

---

## 3. 分组小节

### 3.1 确定残留（零引用，已删除）

共 **74 文件**，合计 **60,032 B**（约 58.6 KB）：

- `assets/immersive_cinematics/textures/gui/editor/*.png` —— **72 文件 / 57,170 B**
- `assets/immersive_cinematics/textures/gui/flight/keycap.png`、`mouse.png` —— **2 文件 / 2,862 B**

依据：全仓（Java / mixin / 资源 json / `editor/` 前端 / webui 后端）grep 路径 `gui/editor`、`gui/flight` **零命中**；唯一加载方 `editor/widget/EditorIcons.java`、`editor/widget/FlightKeyHints.java` 已随 `6d0544c` 删除。

**附带的空目录**：`assets/immersive_cinematics/textures/gui/editor/svg/` 是**空目录**（0 文件，git 未跟踪），是 `0590fa1` 把 SVG 移出 resources 后遗留的壳。删除 `editor/` 图标时一并清掉即可（空目录不进 jar，无功能影响）。

---

### 3.1.1 清理记录（2026-10-08 执行）

**动作**：删除上述 74 个 PNG，并移除两个随之变空的目录。

| 项 | 数量 | 字节 |
|---|---|---|
| `textures/gui/editor/*.png` | 72 | 57,170 |
| `textures/gui/flight/{keycap,mouse}.png` | 2 | 2,862 |
| **文件合计** | **74** | **60,032**（约 58.6 KB） |
| 空目录 `textures/gui/editor/svg/` | 0 | 0 |
| 清空后删除的目录 | `textures/gui/editor/`、`textures/gui/flight/` | — |

**复核证据（删除前）**：

- `grep -r "gui/editor"`、`grep -r "gui/flight"` 于 `common/src`、`fabric/src`、`forge/src`、`editor/src`、`editor/electron`、`editor/index.html`（含 webui Java 后端）—— **零命中**。
- 全仓 grep `textures/gui/(editor|flight)` —— **仅命中本清单文件自身**（`plans/`，按口径不算引用），无任何 live 代码 / 资源 json / 前端引用。
- 唯一加载方 `editor/widget/EditorIcons.java`、`editor/widget/FlightKeyHints.java` 已随 `6d0544c` 删除。
- 删除后 `textures/gui/` 仅余 `skip_key.png`（`SkipHudRenderer` 在用）。

**保留**：仓库根 `svg/editor/`（72 `.svg`）+ `svg/flight/`（2 `.svg`）源文件完整未动，可随时重导出 PNG；「仍被使用」组（§3.2）未触碰。

**验证**：

- `sh gradlew compileJava` —— **绿**（exit 0；仅 11 条既有 deprecation 警告，与本次删除无关）。
- 脚本 validator（`E:/tmp/icv`，`cinematics/tests` + `cinematics/release`，121 脚本）—— 运行正常；3 个既有 test 脚本告警（`test_camera_facing_origin*.json` 缺 `look_at_target`、`test_camera_relative_axis.json` 缺 `yaw`）为**删除前既存**的数据问题，与资源删除无关。

### 3.2 仍被使用（保留）

见 §2.3。要点：

- `skip_key.png` —— `SkipHudRenderer` 的 HUD 图标，**唯一仍在用的 textures/gui 下文件**。
- `icon.png` —— Fabric / Forge 两端的模组图标，两端元数据文件都指向它。
- 6 个 shader 文件 —— `ColorAdjustPass`（调色）与 `LaneCompositor`（lane 合成）的运行时着色器。
- lang 两个 json —— 标准语言文件，`editor.flight.key.*` 等键仍被飞控 HUD 消费（键被 `editor-webui-migration.md` §4 明确保留）。

### 3.3 待确认（用途不明）

无。全部 74 个候选的用途都能追到已删除的加载方（`EditorIcons` / `FlightKeyHints`），判定明确；不存在「来源不明的孤儿资源」。

---

## 4. 清理建议与风险

**建议**：删除 §3.1 的 74 个 PNG + 空目录 `editor/svg/`。它们是纯客户端资源，删除**不影响任何运行逻辑**。

**影响面**：

- 无代码引用 → 删后编译、加载、运行均不受影响；`sh gradlew compileJava` 不受影响（资源不参与编译）。
- 资源包体积：省约 58.6 KB（未压缩）。收益很小，价值主要在「不留误导性资源」。
- **唯一风险点**：若将来 WebUI 编辑器想复用这套图标（例如把 `play/pause/stop/undo/redo` 等搬到 Electron 端），需要从根 `svg/`（源在，74 个 `.svg` 齐全）重新导出 PNG，而不是从 resources 拿。因此**删除 PNG 不丢源**——SVG 源完整保留在 `svg/editor/` 与 `svg/flight/`。
- 若担心「图标集可能用于未来功能」，**可只删 `flight/`（2 个，明确无承接方）**，`editor/` 图标暂留；但这只是心理缓冲，`editor/` 图标同样零引用。

**不做的事**：本文只清点、不 commit（提交由主代理统一进行）。删除动作已于 2026-10-08 按 §3.1.1 执行。

---

## 5. 验证 / 扫描覆盖报告

### 5.1 覆盖

| 范围 | 文件数 | 说明 |
|---|---|---|
| `common/src/main/resources/` | 86 | 递归全量枚举（含 `assets/`、`pack.mcmeta`、mixin 配置） |
| `fabric/src/main/resources/` | 2 | `fabric.mod.json`、`immersive_cinematics.fabric.mixins.json` |
| `forge/src/main/resources/` | 3 | `immersive_cinematics.forge.mixins.json`、`META-INF/mods.toml`、`pack.mcmeta` |
| **合计** | **91** | — |

分组计数：

| 组 | 文件数 | 字节 |
|---|---|---|
| 仍被使用（§2.3，含结构/元数据文件） | 17 | — |
| 确定残留 `textures/gui/editor/*.png` | 72 | 57,170 |
| 确定残留 `textures/gui/flight/*.png` | 2 | 2,862 |
| 空目录（非文件）`textures/gui/editor/svg/` | 0 | 0 |
| **残留文件小计** | **74** | **60,032** |

> 注：`fabric/`、`forge/` 下**未发现同源残留**——两者只有元数据 / mixin / 资源包声明，且 `mods.toml` 的 `logoFile`、`fabric.mod.json` 的 `icon` 指向的是仍被使用的 `common` 侧 `icon.png`。

### 5.2 判定证据（grep）

对每个资源名做了全仓引用扫描（`common/src`、`fabric/src`、`forge/src`、`editor/src`、`editor/electron`、`editor/index.html`），关键结果：

| 判定 | 证据 |
|---|---|
| `gui/editor` 零引用 | 全仓 `grep -r "gui/editor"`（排除 node_modules）**零命中**（仅本文件与 `docs/`/`plans/` 提及，按口径不计） |
| `gui/flight` 零引用 | 全仓 `grep -r "gui/flight"` **零命中** |
| `skip_key.png` 仍用 | `common/.../control/SkipHudRenderer.java:15` |
| `icon.png` 仍用 | `fabric/src/main/resources/fabric.mod.json:14`、`forge/src/main/resources/META-INF/mods.toml:14` |
| `ic_color_adjust` 仍用 | `common/.../post/ColorAdjustPass.java:89`（+ 文档注释 `:71`） |
| `ic_lane_blit` 仍用 | `common/.../client/lane/LaneCompositor.java:83` |
| 加载方已删除 | `git show 6d0544c^:.../editor/widget/EditorIcons.java`（`ICON_PATH = "textures/gui/editor/"`）、`.../editor/widget/FlightKeyHints.java`（`KEYCAP`/`MOUSE` 两个 ResourceLocation）；两文件均在 `6d0544c` 被删 |
| 15 个图标退役前有引用 | `git grep` 于 `6d0544c^` 命中 `TimelineArea.java`（add-clip/add-track/remove-track/delete/keyframe/circle-x/eye/eye-off/lock/lock-open/mute/volume）、`PreviewArea.java`（play/pause/stop）、`TrackListPanel.java`（eye） |
| 57 个图标退役前亦无引用 | 上述 72 个名字逐一 `git grep -F "\"<name>\"" 6d0544c^ -- '.../editor/**'`，除 15 个外全部 0 命中 |
| SVG 源完整 | 根 `svg/editor/`（72 `.svg`）+ `svg/flight/`（2 `.svg`），共 74 个，`git ls-files svg` = 74 |
| 飞控 HUD 不再用纹理 | 通读 `webui/WebPreviewScreen.drawFlightHud()`：仅 `I18n` 文本 + `keyLabel`，无 `ResourceLocation` / `blit` |
| 前端不用 mod 纹理 | `editor/src` 只用自带 `assets/icons/*.svg` 与 `assets/icon.png`；无 `textures/`、无 `immersive_cinematics` 资源路径引用 |

### 5.3 文档落盘

本文件即产物：`plans/0.3.6/editor-leftover-resources.md`。原清点阶段未删除、移动任何资源文件；2026-10-08 已按 §3.1.1 执行删除（未 commit，提交由主代理统一进行）。
