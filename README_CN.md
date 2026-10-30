# ImmersiveCinematics

**给整合包加过场动画。** 镜头运动、黑边、字幕、音乐、命令和调色都写在一个 JSON 脚本里，触发器命中时自动播放——不需要指令方块，也不需要红石。

[English Documentation](README.md)

> **代码实现由 AI 辅助完成。** 作者负责设计——架构、脚本格式、行为约定与验证；AI 编码助手在该方向下编写代码。

---

## 这是什么

ImmersiveCinematics 是一个 Minecraft 1.20.1 模组，按 JSON 脚本播放过场动画。触发器在服务端运行：监听玩法事件与条件，命中后在适用玩家的客户端上播放。播放全程由数据驱动——脚本声明轨道、片段和关键帧，运行时只负责执行。

典型用途：开场动画、Boss 战演出、固定机位解谜房间、开箱镜头特写、服务器公告、自动飞行巡览。

## 脚本

一个脚本是一个 JSON 文件：`meta`（id、name、author、version、触发器、运行时行为）与 `timeline`（轨道 → 片段 → 关键帧）。脚本格式为 v3，`meta.version` 必须写 `3`。

七种轨道：

| 轨道 | 作用 |
|---|---|
| `CAMERA` | 镜头位置、朝向与光学参数。每条轨道产出自己的画面 lane。 |
| `LETTERBOX` | 宽银幕黑边；宽高比与透明度都可打关键帧。 |
| `OVERLAY` | 颜色遮罩（渐变、遮罩形状、混合模式）、PNG/GIF 图片、字幕。 |
| `AUDIO` | 音乐与音效（OGG/WAV），走原版声音引擎。 |
| `EVENT` | 指定时刻执行服务端命令，支持 `&&` 命令链与权限 4。 |
| `MOD_EVENT` | 其它模组暴露的事件。 |
| `ADJUST` | 对整幅合成画面调色。 |

同一条轨道的片段可以重叠，后写的叠在上面。循环支持 `repeat` 与 `ping-pong`，脚本级宏循环只支持 `repeat`。脚本按**内容播完**结束——各轨片段播完、有限循环次数计满、没有挂起在跑的内容——而不是 `total_duration` 到点；`total_duration` 只作统计。

脚本从游戏目录下的 `immersive_cinematics/scripts/` 加载（子目录最多五层，路径用 `/` 分隔，`.json` 可省略）。图片、音频、`.cube` LUT 与译文从 `immersive_cinematics/resource/` 加载。

## 相机

- 每个关键帧有六个独立通道：位置、yaw、pitch、roll、FOV、zoom。
- 缺省匀速线性插值；贝塞尔路径按弧长参数化，过弯时速度均匀。
- 位置可写世界绝对坐标，也可写相对某个点源的偏移——玩家、固定坐标、就近方块、实体选择器或结构。点源可设为 `live`（逐帧实时）或 `snapshot`（首次取到即冻结）。
- 注视目标：玩家、实体、坐标、方块或结构；`look_at_part` 可瞄实体包围盒内的百分比部位。`yaw_lock` / `pitch_lock` 可把某一轴直接锁死。
- 实体选择器支持 `@a` / `@p` / `@e[…]`，可带 NBT 与 `type=` 过滤，并可按调用点单独配置重新选择与切换策略。
- 每个通道可挂运动模型：`smoothstep`、`ease_in` / `ease_out` / `ease_in_out`、`cubic_bezier`、`exponential_smooth`、`spring`、`inertia`，可用 `chain` 与 `blend` 组合。
- 修饰器按通道给运行中的相机打补丁（`add` / `multiply` / `replace`，按优先级应用，可限定通道、可整段跳过），不必改写关键帧。
- 过渡支持 `cut` 与 `morph`，可用 `transition_params` 逐通道指定混合 / 保持 / 硬切，用 `transition_interrupt` 指定被打断时的行为。
- 手持感：`perlin`、`perlin_axis`、`sine`，以及给短促冲击用的 `trauma`。

## 多机位

每条 `CAMERA` 轨道都是一个独立相机，产出自己的 lane。多条 lane 可合成成分屏与画中画，按各自的 opacity 混合，并可用矩形或圆形遮罩裁切。片段还可以写 `dimension`，在不传送玩家的前提下让某条 lane 渲染另一个维度的地形。

## 调色

`ADJUST` 调的是最终合成画面，`CAMERA` 片段上的调色只作用于它自己那条 lane。可调项：曝光、对比度、高光与阴影、白场与黑场、饱和度、自然饱和度、色温、色调、灰度、反相、RGB 通道系数、完整 HSL、RGB 复合曲线与每通道曲线、六条 hue 曲线、3×3 通道混合器、Lift/Gamma/Gain 色轮、`.cube` LUT、六色带色相 / 饱和度微调，以及混合模式（`multiply` / `screen` / `soft_light` / `overlay`）。所有参数都是关键帧通道。

## 触发器

服务端判定，共 27 种：`login`、`advancement`、`xp`、`observation`、`inventory`、`location`、`biome`、`dimension_change`、`dimension`、`structure`、`entity_kill`、`entity_interact`、`block_interact`、`item_on_interact`、`item_craft`、`item_use`、`item_consume`、`item_release`、`item_instant_use`、`item_use_interrupt`、`item_pickup`、`item_drop`、`gamestage`、`death`、`facing`，以及可嵌套的条件组合器 `all_of` / `any`（最多八层）。

每个触发器支持 `delay`、`repeatable`、`on_enter` / `exit_buffer`、`poll_interval`，以及 `requires` 前置条件——例如「另一个脚本已开始并结束」（`script_started` / `script_completed` / `script_played`），或其它模组注册的条件。轮询类触发器的检测间隔按「触发器级 → 脚本级 → 类型默认」逐级回落。每位玩家的触发状态会同步到客户端，重连不会丢进度。

## 播放控制

- `meta.play_mode`：`parallel`（缺省——不阻塞新来者，也不会被打断）、`exclusive`（我播时其它脚本排队）、`interruptible`（可被新来者顶掉）。
- 播放队列不设上限，且跨实例存活。只有 `exclusive` 脚本会挡住新来者；排队的请求按 `priority` 再按先来先服务接播。
- 长按 **C** 跳过当前演出，**Ctrl+P** 强制退出。多人跳过投票可按脚本或全局配置比例。
- 键盘与鼠标屏蔽、生物 AI 屏蔽、按层隐藏 HUD（聊天、记分板、动作栏、标题、字幕、快捷栏、准星、Boss 条、跳过提示）、隐藏手臂、抑制视角摇晃与画面扭曲、渲染玩家模型、游戏暂停时暂停。`hard_hide_hud`（Forge）接管 GUI 渲染，连不走 overlay 注册表、自行绘制的第三方 HUD 也会一并隐藏。
- 多个脚本可以同时播放：隐藏与屏蔽开关按实例取并集，暂停联动跟随任一要求暂停的实例。

## 世界加载

镜头离开玩家所在区域时，模组既不移动玩家，也不注册替身：客户端上报相机位置，服务端把它写成虚拟区块中心交给原版区块系统，区块加载、实体配对与刷怪都按原版规则跟随相机。镜头结束或脚本停止后中心归位，恢复按玩家位置加载。没有 `CAMERA` 轨道的脚本不会启动这套机制。

## 快速开始

1. Minecraft 1.20.1，Java 17+。
2. Forge 47.x+（无额外前置），或 Fabric 1.20.1（需要 Fabric API）。
3. 把 jar 放进客户端与服务端的 `mods/`——多人游戏两边都要装。
4. 把脚本放进游戏目录下的 `immersive_cinematics/scripts/`。

下载：[GitHub Releases](https://github.com/SanZiNEO/ImmersiveCinematics/releases)。

### 一个最小脚本

```json
{
  "meta": {
    "id": "welcome",
    "name": "Welcome",
    "author": "Your Name",
    "version": 3,
    "triggers": [
      { "id": "on_login", "type": "login", "delay": 1.0, "conditions": {} }
    ]
  },
  "timeline": {
    "total_duration": 6.0,
    "tracks": [
      {
        "type": "camera",
        "clips": [
          {
            "start_time": 0.0,
            "duration": 6.0,
            "keyframes": [
              { "time": 0.0, "position": { "dx": 5, "dy": 2, "dz": 3 }, "yaw": 90, "pitch": 5 },
              { "time": 6.0, "position": { "dx": 0, "dy": 2, "dz": -5 }, "yaw": 0, "pitch": 10 }
            ]
          }
        ]
      }
    ]
  }
}
```

`position` 里的 `dx` / `dy` / `dz` 是相对玩家激活位置的世界轴偏移，`yaw` 与 `pitch` 单位为度。存成 `immersive_cinematics/scripts/welcome.json`，执行 `/icinematics reload`，玩家登录 1 秒后就会播放。

### 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/icinematics play <脚本> [玩家]` | op 2 | 播放脚本；支持 Tab 补全与 `@a` / `@p` 选择器 |
| `/icinematics stop [脚本] [玩家]` | — | 停止播放；不写参数 = 全停 |
| `/icinematics reload` | op 2 | 从磁盘重新读取脚本并重建触发器 |
| `/icinematics validate <脚本>` | op 2 | 静态校验脚本，一次列出全部问题 |
| `/icinematics template list` | op 2 | 列出内置片段模板及其参数 |
| `/icinematics template <id> [键=值…]` | op 2 | 按模板展开生成脚本 |

脚本路径相对 `immersive_cinematics/scripts/`，`.json` 可省略。

## 编辑器

过场动画在**独立编辑器**里制作——一个桌面程序（Electron + Vue 3），通过本地连接与游戏通信，需单独下载——目前是 Windows 安装包（版本 0.1.0，测试中），源码在 `editor/`。

游戏内按 **F9** 启动本地服务并打开预览屏。编辑器提供多轨时间轴、按 schema 生成的表单、实时预览、飞行取景（**F7**）、A-B 循环、marker 与快捷键；脚本格式随模组走，所以表单始终对应正在运行的版本。缓动与速度曲线由编辑器烘焙成显式关键帧，运行时只做线性插值。

模组 jar 内含播放运行时，以及编辑器对接用的 WebSocket 服务、帧推流与预览屏；编辑器本体不在 jar 里。旧的游戏内 Java 编辑器（F6）已在 0.3.6 退役。

## 兼容性

- Forge 47.x+ 与 Fabric 1.20.1 共用同一份 common 实现；平台差异只在渲染与 HUD 适配（`hard_hide_hud` 仅 Forge）以及配置界面入口。
- 兼容 Sodium、Rubidium、Embeddium。
- Iris / Oculus 光影：每条 lane 的光影状态相互隔离，跨维度 lane 同样适用；切换光影包不再需要重启。
- 暂停感知：游戏暂停时脚本冻结。

## 版本

| | |
|---|---|
| Minecraft | 1.20.1 |
| Java | 17+ |
| 脚本格式 | v3 |
| 已发布 | 0.3.5（2026-09-09） |
| 开发中 | 0.3.6（未发布）——本 README 描述的是当前开发状态 |
| 独立编辑器 | 0.1.0（测试中） |

## 后续计划

0.3.6 仍在进行中：WebUI 编辑器工作流、编辑器侧模板引擎，以及剩余的光影管线隔离。之后是 Minecraft 多版本适配；脚本格式尚未定型。

---

## 许可证

MIT 许可证
