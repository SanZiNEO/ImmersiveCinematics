# command（命令）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/command/`

功能树：

- **命令注册**
  - ✅ `CinematicCommand.register()` 注册根命令 `/icinematics`，共 5 个子命令：play / stop / status / reload / validate（`CinematicCommand`）
- **play — 播放脚本**
  - ✅ `/icinematics play <file> [players]`：权限等级 2（op），从脚本文件读取 JSON 并解析，通过 `S2CPlayScriptPacket` 推送给目标玩家；省略 players 时发送给全体在线玩家，支持 `@a`/`@p` 等玩家选择器（`CinematicCommand`）
  - ✅ file 参数带 Tab 自动补全（来自全局与世界存档目录的 .json 文件名）（`CinematicCommand`）
  - ✅ 文件不存在或解析失败时向执行者返回带搜索路径的失败提示（`CinematicCommand`）
- **stop — 停止播放**
  - ✅ `/icinematics stop [players]`：通过 `S2CStopScriptPacket`（空 scriptId = 强制停止全部）向目标玩家发送停止指令，默认全体玩家（`CinematicCommand`）
- **status — 播放状态**
  - ✅ `/icinematics status`：输出相机激活状态、脚本模式下的脚本名与剩余时间（`CinematicCommand`）
- **reload — 同步并重载**
  - ✅ `/icinematics reload`：权限等级 2（op），将全局脚本目录（`immersive_cinematics/scripts`）的 .json 覆盖同步到世界存档目录，然后调用 `ScriptManager.reload()` 重新加载并重建触发器索引（`CinematicCommand`、`ScriptManager`）
- **validate — 脚本静态校验**
  - ✅ `/icinematics validate <file>`：权限等级 2（op），用 `ScriptValidator` 静态校验脚本 JSON（结构/字段缺失/语义/缺省提示），一次输出完整问题清单；零问题输出"校验通过"（`CinematicCommand`、`ScriptValidator`）
- **文件查找**
  - ✅ `findScriptFile()` 在**游戏根脚本目录**（`immersive_cinematics/scripts`）内查找（自动补全 .json 后缀），并使用 normalize + startsWith 校验拒绝路径遍历（`CinematicCommand`）

## 已知问题

- `/icinematics play` 的报错文案声称搜索 4 条路径，但 `findScriptFile()` 实际只搜索游戏根脚本目录（来源：`CinematicCommand`）
