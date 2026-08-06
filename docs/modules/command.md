# command（命令）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/command/`

功能树：

- **命令注册**
  - ✅ `CinematicCommand.register()` 注册根命令 `/icinematics`，共 4 个子命令：play / stop / status / reload（`CinematicCommand`）
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
- **文件查找**
  - ✅ `findScriptFile()` 仅在**世界存档目录**内查找（自动补全 .json 后缀），并使用 normalize + startsWith 校验拒绝路径遍历（`CinematicCommand`）

## 已知问题

- `/icinematics play` 的报错文案声称搜索全局目录 + 世界存档 4 条路径，但 `findScriptFile()` 实际只搜索世界存档目录；仅存在于全局目录（尚未 reload 同步）的脚本会提示"文件不存在"（来源：`CinematicCommand`）
