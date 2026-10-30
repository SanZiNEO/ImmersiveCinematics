> **过期文档（OUTDATED）——不得作为当前设计或实施依据**
>
> 本文未随最新设计更新，仅保留作历史参考。AI 与自动化工具不得据此推断当前格式、功能状态、接口或实施要求，也不得直接照搬其中示例用于新实现。
>
> 当前规划请先查阅 [0.3.6 计划状态索引](../../plans/0.3.6/README.md) 与 [I 轮实施基准](../../plans/0.3.6/I-script-core/README.md)，再按状态查阅对应未完成轮次。
>
> 计划目标不代表已经实现；核实当前代码行为时须结合源码，不得将旧文或未实现计划当作现状。

# command（命令）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/command/`

功能树：

- **命令注册**
  - ✅ `CinematicCommand.register()` 注册根命令 `/icinematics`，共 4 个子命令：play / stop / reload / validate（`CinematicCommand`）
- **play — 播放脚本**
  - ✅ `/icinematics play <file> [players]`：权限等级 2（op），从脚本文件读取 JSON 并解析，通过 `S2CPlayScriptPacket` 推送给目标玩家；省略 players 时发送给全体在线玩家，支持 `@a`/`@p` 等玩家选择器（`CinematicCommand`）
  - ✅ file 参数带 Tab 自动补全，**命令路径格式为 `scripts/` 下的相对路径**：`/` 分隔、不带 `.json`——层级子目录 `camera/center/foo.json` 补全为 `camera/center/foo`，字面目录 `camera_center/foo.json` 补全为 `camera_center/foo`，根目录脚本直接写 `showcase_01`（`CinematicCommand`）
  - ✅ 旧「目录:文件名」输入继续可用，只是**兼容别名**（不是规范写法）：`camera_center:foo` 先找字面 `camera_center/foo`，不存在再按旧规则把 namespace 里的 `_` 解码成 `/` 找 `camera/center/foo`；两个字面候选都存在时字面路径胜出并记歧义 WARN（`CinematicCommand`）
  - ✅ 文件不存在或解析失败时向执行者返回失败提示，列出实际尝试过的候选相对路径（含 `.json` 变体），并提示用 `/` 写明确路径（`CinematicCommand`）
- **stop — 停止播放**
  - ✅ `/icinematics stop`：通过 `S2CStopScriptPacket`（空 scriptId = 强制停止全部）向全体在线玩家发送停止指令（`CinematicCommand`）
  - ✅ `/icinematics stop <script> [players]`：只停指定脚本——script 为已加载脚本 id（Tab 补全 = 已加载 `meta.id` + 相对路径），也接受 `play` 同款相对路径（服务端按同一套候选解析文件取 `meta.id` 再下发）；客户端按 `(scriptId, instanceId)` 定位实例停止（**非顶层同样生效**，走渐出退场链），不在播则取消该脚本的排队项（`CinematicCommand`、`ClientScriptReceiver`、`CameraManager.stopScriptById` / `cancelQueued`）
  - ⚠ 参数顺序：第一个参数先按脚本 id 解析，因此纯玩家名会被当作脚本 id；只停玩家请用选择器（`/icinematics stop @a` = 停全部脚本），或在脚本之后写选择器（`CinematicCommand`）
- **reload — 重新加载脚本**
  - ✅ `/icinematics reload`：权限等级 2（op），直接从游戏根目录重新加载 `.json` 脚本并重建触发器索引；**不再同步到世界存档**（`CinematicCommand`、`ScriptManager`）
- **validate — 脚本静态校验**
  - ✅ `/icinematics validate <file>`：权限等级 2（op），用 `ScriptValidator` 静态校验脚本 JSON（结构/字段缺失/语义/缺省提示），一次输出完整问题清单；零问题输出"校验通过"（`CinematicCommand`、`ScriptValidator`）
  - ✅ file 参数与 `play` 用**同一套相对路径解析**（Tab 补全同源：`scripts/` 下、`/` 分隔、不带 `.json`；冒号写法仅作兼容别名，字面路径优先、旧 `_`→`/` 解码兜底）（`CinematicCommand`）
- **文件查找**
  - ✅ `findScriptFile()` 在**游戏根脚本目录**（`immersive_cinematics/scripts`）内查找：按候选相对路径顺序（字面路径优先，旧 `_`→`/` 解码仅作兜底）逐个先精确、再补 `.json`，第一个存在的文件胜出；每个候选都做 resolve + normalize + startsWith 校验拒绝路径遍历（`CinematicCommand`）

## 已知问题

- 命令路径只接受 `ResourceLocation` 允许的字符（小写字母、数字、`_`、`-`、`.`、`/`）：大写、空格、中文文件名无法用命令直接指定（来源：`ResourceLocationArgument`）
- 冒号输入在「字面目录」与「层级目录」同时存在时只能命中字面路径（旧解码候选不再尝试）；要访问层级目录请用 `/` 写全路径，例如 `camera_center:foo` 只命中 `camera_center/foo`，`camera/center/foo` 需写成 `camera/center/foo`（来源：`CinematicCommand`）
