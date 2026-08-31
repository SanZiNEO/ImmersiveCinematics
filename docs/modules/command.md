# command（命令）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/command/`

功能树：

- **命令注册**
  - ✅ `CinematicCommand.register()` 注册根命令 `/icinematics`，共 4 个子命令：play / stop / reload / validate（`CinematicCommand`）
- **play — 播放脚本**
  - ✅ `/icinematics play <file> [players]`：权限等级 2（op），从脚本文件读取 JSON 并解析，通过 `S2CPlayScriptPacket` 推送给目标玩家；省略 players 时发送给全体在线玩家，支持 `@a`/`@p` 等玩家选择器（`CinematicCommand`）
  - ✅ file 参数带 Tab 自动补全，**命令标识格式为“目录:文件名”**：子目录 `chapter1/boss_fight.json` 补全为 `chapter1:boss_fight`，根目录脚本直接写 `showcase_01`；目录名中的 `/` 在命令标识中以命名空间下划线表示（`CinematicCommand`）
  - ✅ 文件不存在或解析失败时向执行者返回带搜索路径的失败提示（`CinematicCommand`）
- **stop — 停止播放**
  - ✅ `/icinematics stop [players]`：通过 `S2CStopScriptPacket`（空 scriptId = 强制停止全部）向目标玩家发送停止指令，默认全体玩家（`CinematicCommand`）
- **reload — 重新加载脚本**
  - ✅ `/icinematics reload`：权限等级 2（op），直接从游戏根目录重新加载 `.json` 脚本并重建触发器索引；**不再同步到世界存档**（`CinematicCommand`、`ScriptManager`）
- **validate — 脚本静态校验**
  - ✅ `/icinematics validate <file>`：权限等级 2（op），用 `ScriptValidator` 静态校验脚本 JSON（结构/字段缺失/语义/缺省提示），一次输出完整问题清单；零问题输出"校验通过"（`CinematicCommand`、`ScriptValidator`）
- **文件查找**
  - ✅ `findScriptFile()` 在**游戏根脚本目录**（`immersive_cinematics/scripts`）内查找（支持子目录命令标识映射相对路径，自动补全 .json 后缀），并使用 normalize + startsWith 校验拒绝路径遍历（`CinematicCommand`）

## 已知问题

- `/icinematics play` 的失败文案列出 2 条搜索路径，但 `findScriptFile()` 实际只做游戏根脚本目录内的规范化查找（来源：`CinematicCommand`）
