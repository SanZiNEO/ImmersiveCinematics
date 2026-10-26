# F 轮脚本命令路径解析

> 状态：已实现（2026-10-26）；实机命令验证待统一测试
> 范围：`/icinematics play|stop|validate` 的脚本路径解析、Tab 补全、模板输出与脚本示例。不改脚本 JSON 格式、`meta.id`、网络层脚本 id、触发器 `script_id`。
> 关联：[`CinematicCommand`](../../../common/src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java)、`docs/SCRIPT_FORMAT.md` §0、`docs/modules/command.md`、`cinematics/0.3.6/tests/camera_center/README.md`、`cinematics/0.3.6/tests/camera_pool/README.md`。

## 一、摘要

当前命令把脚本目录层级压成 `_`，再用 `:` 分隔目录与文件名：`camera/center/foo.json` → `camera_center:foo`，`scriptPathOf` 再把 namespace 里的 `_` 还原成 `/`。这个编码是有损的：字面文件夹 `camera_center` 和层级文件夹 `camera/center` 会得到同一个命令，解析时只能命中后者。F 轮测试目录 `camera_center/`、`camera_pool/` 正好踩中。

本计划把**相对路径**定为命令的唯一规范标识：`camera_center/foo`、`camera/center/foo` 都用 `/`，不带 `.json`；`:` 只作为旧输入别名。解析时先找字面路径，找不到再按旧规则解码，第一个存在的文件胜出；两者都存在时字面路径优先并记歧义日志。`meta.id` 与网络层完全不动，测试脚本不用改名，`_` 继续作为单词分隔符。

## 二、为什么做

### 2.1 问题

| 实际文件 | 实现前 `toCommandId` | 实现前 `scriptPathOf` 解析 | 结果 |
|---|---|---|---|
| `camera_center/test_x.json` | `camera_center:test_x` | `camera/center/test_x` | 找不到 |
| `camera/center/test_x.json` | `camera_center:test_x` | `camera/center/test_x` | 找到 |
| `camera_center/test_x.json` | `camera_center/test_x` | `camera_center/test_x` | 找到（但补全不这么给） |

- **同一字符串有两种含义。** `camera_center:test_x` 既可能是字面文件夹，也可能是层级文件夹；当前实现永远选后者。
- **`_` 是合法单词分隔符，不是层级分隔符。** `ResourceLocation` 的 namespace 和 path 都允许 `_`，path 还允许 `/`；把 `/` 编码成 `_` 必然丢失信息。
- **`.replace(".json", "")` 也是全量替换。** 文件名里出现 `.json` 子串时会被误删；应只去掉末尾后缀。
- **补全、模板、文档都在推 colon。** 用户照着补全输入，就会继续踩同一个坑；测试 README 里的 `camera:center/...` 也会让读者以为 colon 是规范写法。
- **`stop` / `validate` 共用同一套解析。** 问题不只在 `play`；三处必须一起改，否则行为不一致。

### 2.2 目标

- 字面文件夹与层级文件夹都能被明确寻址：`camera_center/foo` 与 `camera/center/foo` 各自命中。
- 旧的 colon 命令继续可用：`camera_center:foo`、`camera:center/foo` 不报错。
- colon 解析确定：字面路径优先，旧解码兜底；两者都存在时字面赢，并留下歧义日志。
- 补全、模板输出、文档示例统一用 `/`，不再引导用户写 colon。
- 测试 fixture 不改名、不移动；`camera_center/`、`camera_pool/` 保持原样。
- `meta.id`、网络层脚本 id、触发器 `script_id`、脚本 JSON 格式不变。

### 2.3 非目标

- 不新增参数类型，继续用 `ResourceLocationArgument.id()`。
- 不放宽字符集：命令路径仍只接受小写字母、数字、`_`、`-`、`.`、`/`；不处理大写、空格、中文文件名。
- 不改 `resource/` 音频/图片路径、编辑器脚本列表、递归深度、脚本加载规则。
- 不改 `stop` 找不到文件时的字面量回退口径（现有行为保留）。
- 不重命名测试目录或脚本文件。

### 2.4 外部惯例

- Minecraft `ResourceLocation`：`namespace:path`，namespace 不允许 `/`，**path 允许 `/`**，`_` 合法（[NeoForged](https://docs.neoforged.net/docs/1.20.6/misc/resourcelocation)、[Minecraft Wiki](https://minecraft.wiki/w/Resource_location)）。
- Godot：只支持 `/` 作路径分隔符，`res://` 表示项目根；稳定引用用 `uid://`，路径作为回退（[文件系统](https://docs.godotengine.org/en/4.4/tutorials/scripting/filesystem.html)、[TSCN / UID](https://docs.godotengine.org/en/4.4/development/file_formats/tscn.html)）。
- Unreal：资源用 `/Game/...` 路径引用，路径直接对应磁盘目录（[Assets and Packages](https://dev.epicgames.com/documentation/en-us/unreal-engine/assets-and-packages?application_version=4.27)）。

结论：层级用 `/`，稳定 id / 命名空间单独一层；没有把 `/` 压成 `_` 再反解的成熟做法。

## 三、方案

### 3.1 规范写法

命令路径 = `scripts/` 下的相对路径，`/` 分隔，不带 `.json`：

```text
/icinematics play camera_center/test_x
/icinematics play camera/center/test_x
/icinematics validate camera_center/test_x
/icinematics stop camera_center/test_x
```

根目录脚本直接写文件名：`showcase_01`。`minecraft:showcase_01` 与 `showcase_01` 等价。

### 3.2 解析顺序

对 `ResourceLocation`（namespace, path）：

1. `namespace == minecraft`：候选只有 `path`。
2. 其他 namespace：
   - 候选 1：字面 `namespace + "/" + path`；
   - 候选 2：旧解码 `namespace.replace('_', '/') + "/" + path`（与候选 1 相同则去重）。
3. 按候选顺序找文件：先精确路径，再补 `.json`；第一个存在的胜出。
4. 两个字面/旧解码候选都存在时，候选 1（字面）胜出，记一条歧义日志，提示改用 `/` 明确路径。

| 输入 | 候选 | 命中 |
|---|---|---|
| `camera_center/foo` | `camera_center/foo` | 字面文件夹 |
| `camera/center/foo` | `camera/center/foo` | 层级文件夹 |
| `camera_center:foo` | `camera_center/foo` → `camera/center/foo` | 先字面，缺失再旧解码 |
| `camera:center/foo` | `camera/center/foo` | 层级文件夹 |
| `foo` / `minecraft:foo` | `foo` | 根目录 |
| `generated:foo` | `generated/foo` | 生成目录 |

### 3.3 补全与输出

- `play` / `validate` 的 Tab 补全输出相对路径，例如 `camera_center/test_x`、`camera/center/test_x`；不再输出 `camera_center:test_x`。
- `stop` 的补全输出“已加载 `meta.id` + 相对路径”的并集；两者都可能被用户使用。
- 模板生成后的提示改为 `/icinematics play generated/<id>` 与 `/icinematics validate generated/<id>`。
- 文件不存在时，错误信息列出实际尝试过的候选路径（含 `.json` 变体），并提示可用 `/` 写明确路径。

### 3.4 安全与兼容

- 每个候选都先 `resolve` + `normalize`，再用 `startsWith(scriptDir.normalize())` 拒绝越界；绝对路径、`../` 逃逸仍然拒绝。
- colon 旧命令保留，不删除、不警告弃用；只有“两个字面候选都存在”时才记歧义日志。
- `meta.id` 仍是脚本唯一身份；命令路径只用于定位文件。
- 测试 fixture 的部署目录不变；只是 README / 命令示例改成 `/`。

## 四、技术细节

### 4.1 核心模型

在 `CinematicCommand` 内收敛为三个入口：

- `toCommandPath(Path globalDir, Path file)`：文件 → 相对路径，去掉末尾 `.json`；用 `substring`，不用 `replace`。
- `scriptPathCandidates(ResourceLocation location)`：按 §3.2 生成候选相对路径列表，去重、保持顺序。
- `findScriptFile(List<String> candidates, Path scriptDir)`：按候选顺序做安全解析与 `.json` 回退；返回第一个存在的文件。需要歧义日志时，在返回前检查是否还有后续候选也存在。

调用方：

- `playScript`：`findScriptFile(scriptPathCandidates(location), globalDir)`。
- `validateScriptFile`：同上。
- `stopScript` / `resolveStopScriptId`：同上；文件找到则解析 `meta.id`，找不到则沿用现有字面量回退。
- `SCRIPT_SUGGESTIONS`：`toCommandPath`。
- `LOADED_SCRIPT_SUGGESTIONS`：已加载 id 与 `toCommandPath` 结果的并集。
- `generateFromTemplate`：输出 `generated/<id>`，不再拼 `generated:<id>`。

### 4.2 契约

- **字面优先**：候选 1 存在时绝不回退到候选 2；即使候选 2 也存在。
- **旧解码只在字面缺失时发生**：`camera_center:foo` 在 `camera_center/foo.json` 存在时不会读 `camera/center/foo.json`。
- **`.json` 可选**：`foo` 与 `foo.json` 都接受；只去掉末尾后缀，不碰文件名中间的 `.json`。
- **路径分隔符**：命令统一 `/`；Windows 文件系统路径在 `toForwardRel` 转 `/`。
- **三命令一致**：`play` / `stop` / `validate` 使用同一个候选生成器与查找器。
- **身份不变**：`meta.id`、网络包字段、触发器 `script_id` 不变；`stop` 仍可只写已加载 id。
- **歧义日志**：仅 colon 输入且两个字面候选都存在时触发；内容包含两个候选与实际选择，级别为 WARN。

### 4.3 数据形态

不新增数据结构、不新增协议字段。命令参数仍是 `ResourceLocation`；候选列表是短生命周期 `List<String>`。

### 4.4 边界

- 不改 `ResourceLocationArgument` 的解析规则，也不新增 `StringArgumentType` 路径参数。
- 不改 `ScriptManager` 的加载、递归深度、`meta.id` 冲突提示。
- 不改 `resource/` 的音频/图片路径解析。
- 不改编辑器预览与 `editor/`。
- 不改 `stop` 的“文件不存在 → 字面量”行为。

### 4.5 迁移

- 现有 colon 命令无需迁移，继续可用。
- 文档与测试 README 的示例从 `camera:center/...` 改为 `camera/center/...`；如果目录本身是 `camera_center/`，写 `camera_center/...`。
- 模板生成提示从 `generated:<id>` 改为 `generated/<id>`；旧写法仍能解析到同一文件。
- 不移动、不改名任何 fixture。

## 五、验收

1. `camera_center/test_x` 命中 `scripts/camera_center/test_x.json`。
2. `camera/center/test_x` 命中 `scripts/camera/center/test_x.json`。
3. 只有 `camera_center/test_x.json` 时，`camera_center:test_x` 命中字面文件。
4. 只有 `camera/center/test_x.json` 时，`camera_center:test_x` 走旧解码命中。
5. 两个文件同时存在时，`camera_center:test_x` 命中字面文件，并出现歧义 WARN。
6. `camera:center/test_x` 命中层级文件。
7. 根目录 `foo`、`minecraft:foo`、`foo.json` 都能命中同一个文件。
8. `../` 越界与绝对路径被拒绝，不读到 `scripts/` 外。
9. `stop` / `validate` 与 `play` 的命中结果一致；`stop` 仍可直接写已加载 `meta.id`。
10. Tab 补全输出 `/` 路径；模板输出提示 `generated/<id>`。
11. `sh gradlew :common:compileJava --offline`、`sh gradlew :fabric:compileJava :forge:compileJava --offline` 通过。

## 六、不做什么

| 方案 | 结论 | 原因 |
|---|---|---|
| 禁止测试脚本用 `_` | 不做 | `_` 是合法单词分隔符，问题在编码，不在命名 |
| 把 `_` 换成 `-` 绕过 | 不做 | 只是让旧解码恰好能反解，语义仍不是目录层级 |
| 新增 `StringArgumentType` 路径参数 | 不做 | 会混入玩家选择器/大小写/空格问题，收益不足 |
| 固定 namespace + 完整路径（如 `ic:camera_center/foo`） | 不做 | 更长，且现有 slash 形式已足够 |
| 删除 colon 兼容 | 不做 | 旧命令、旧文档、模板提示仍在用，保留成本低 |
| 重命名 `camera_center/`、`camera_pool/` | 不做 | 测试目录命名合理，不应被命令编码绑架 |

## 七、实现结果（2026-10-26）

- `CinematicCommand`：`toCommandId` → `toCommandPath`（只去末尾 `.json`）；新增 `scriptPathCandidates`；`findScriptFile(List<String>, Path)` 按候选顺序查找，字面优先、旧解码兜底，双命中 WARN；`play` / `stop` / `validate` 统一走这套解析；`play` / `validate` 补全输出相对路径，`stop` 补全为已加载 `meta.id` + 相对路径；模板提示改为 `generated/<id>`。
- 文档：`docs/SCRIPT_FORMAT.md` §0 / §11、`docs/modules/command.md`、`cinematics/0.3.6/tests/camera_center/README.md` 的示例改为 `/` 路径；colon 只保留为兼容说明。
- 编译：`sh gradlew :common:compileJava --offline`、`sh gradlew :fabric:compileJava :forge:compileJava --offline` 均 `BUILD SUCCESSFUL`。
- 无头烟测：用临时 Gradle init 脚本反射调用实际 `CinematicCommand` 方法，覆盖 `camera_center/test_x`、`camera/center/test_x`、`camera_center:test_x`、`camera:center/test_x`、仅层级文件时的旧解码、根目录、`minecraft:`、`.json`、`generated:` 与 `../` 拒绝；全部通过，临时脚本已删除。
- 未跑实机命令；§五 的 in-game 验收留到统一测试阶段。
