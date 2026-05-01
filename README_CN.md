# 🎬 ImmersiveCinematics

**专业级 Minecraft 电影摄影相机引擎。** 支持 JSON 驱动的脚本时间轴、多轨道播放、6自由度相机控制、数学驱动插值和解耦朝向控制——为高质量游戏内过场动画而生。

[English Documentation](README.md)

> 本项目由 AI Agent 生成。作者仅提供创意和概念，AI Agent 负责代码实现和功能开发。

---

## ✨ 功能特性

### 🎥 相机系统
- **6自由度相机控制** — 位置、偏航、俯仰、滚转、视场角、缩放、景深
- **帧回调驱动** — 采用 ReplayMod 式 `System.nanoTime()` 每渲染帧精确计算，实现亚 tick 精度
- **贝塞尔曲线路径** — 三次贝塞尔曲线，支持 `PathStrategies` 注册表扩展新曲线类型
- **坐标模式** — 相对模式（基于玩家的 `dx/dy/dz`）或绝对模式（世界坐标 `x/y/z`）
- **虚拟时钟** — 暂停感知计时；游戏暂停时脚本自动冻结

### 📜 JSON 脚本系统 (v3)
- **多轨道时间轴** — 5 种轨道类型：`CAMERA`、`LETTERBOX`、`AUDIO`、`EVENT`、`MOD_EVENT`
- **关键帧动画** — 每片段关键帧数组，时间单调递增验证
- **片段过渡** — `cut`（硬切换）和 `crossfade`（片段间平滑混合）
- **循环系统** — 可配置循环次数，支持无限时长（`duration: -1`）
- **严格验证** — 带字段路径信息的错误报告，`ScriptParseException` 精确定位问题

### 🎨 插值引擎
- **5 种曲线类型** — `linear`、`smooth`（Hermite）、`ease_in`、`ease_out`、`ease_in_out`
- **2 种插值作用域** — `clip`（整体进度映射）和 `segment`（逐段映射）
- **2 种曲线组合模式** — `override`（片段覆盖脚本默认值）和 `composed`（双重平滑）
- **3 级覆盖体系** — 脚本级 → 片段级 → 关键帧级插值
- **NaN 防护** — 所有插值结果经 sanitize 处理，异常值回退到最后有效帧

### 🖥️ 电影覆盖层
- **Letterbox 黑边** — 电影宽银幕画幅比黑边（默认 2.35:1），带渐入/渐出动画
- **光影兼容** — 仅使用 `GuiGraphics.fill()` 绘制，不涉及 OpenGL/FBO/Shader，与光影包完美兼容
- **Hermite 缓动** — smooth(t) = 3t² − 2t³，实现自然的黑边过渡效果

### 🎛️ 运行时行为（15 项标志位）
| 标志位 | 默认值 | 说明 |
|--------|--------|------|
| `block_keyboard` | `true` | 播放期间屏蔽键盘输入 |
| `block_mouse` | `true` | 播放期间屏蔽鼠标输入 |
| `hide_hud` | `true` | 隐藏所有 HUD 元素 |
| `hide_arm` | `true` | 隐藏玩家手臂和手持物品 |
| `suppress_bob` | `true` | 抑制受伤/死亡/行走摇晃及反胃效果 |
| `block_chat` | `false` | 屏蔽聊天消息 |
| `block_scoreboard` | `false` | 屏蔽计分板显示 |
| `block_action_bar` | `false` | 屏蔽动作栏消息 |
| `block_particles` | `false` | 屏蔽粒子效果 |
| `render_player_model` | `true` | 以第三人称渲染玩家模型 |
| `pause_when_game_paused` | `true` | 游戏暂停时冻结脚本 |
| `interruptible` | `true` | 允许其他脚本抢占当前脚本 |
| `skippable` | `true` | 允许用户长按退出键跳过播放 |
| `hold_at_end` | `false` | 播放结束后保持最后一帧 |
| `block_mob_ai` | `false` | 屏蔽附近生物 AI |

---

## 🚀 快速开始

### 安装

1. 安装 [Minecraft Forge 1.20.1](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)（47.x+）
2. 下载最新版本并放入 `.minecraft/mods/` 目录
3. 启动游戏

### 命令

| 命令 | 说明 |
|------|------|
| `/cinematic play <文件>` | 加载并播放脚本 |
| `/cinematic stop` | 停止当前播放 |
| `/cinematic status` | 显示播放状态 |

**文件搜索顺序：**
1. 绝对路径
2. 游戏目录相对路径
3. `cinematics/` 子目录
4. 自动添加 `.json` 后缀

首次使用时自动创建 `cinematics/` 目录。

### 你的第一个脚本

创建 `cinematics/hello.json`：

```json
{
  "meta": {
    "id": "hello_world",
    "name": "你好世界",
    "author": "你",
    "version": 3,
    "description": "一个简单的相机平移",
    "interpolation": "smooth",
    "block_keyboard": true,
    "block_mouse": true,
    "hide_hud": true,
    "hide_arm": true,
    "suppress_bob": true,
    "interruptible": true,
    "skippable": true,
    "hold_at_end": false
  },
  "timeline": {
    "total_duration": 10.0,
    "tracks": [
      {
        "type": "CAMERA",
        "clips": [
          {
            "start_time": 0.0,
            "duration": 10.0,
            "transition": "cut",
            "interpolation": "smooth",
            "interpolation_scope": "clip",
            "position_mode": "relative",
            "loop": false,
            "keyframes": [
              {
                "time": 0.0,
                "position": { "dx": 5, "dy": 2, "dz": 0 },
                "yaw": 90, "pitch": 5, "roll": 0,
                "fov": 70, "zoom": 1.0, "dof": 0
              },
              {
                "time": 10.0,
                "position": { "dx": -5, "dy": 3, "dz": 0 },
                "yaw": -90, "pitch": 15, "roll": 0,
                "fov": 70, "zoom": 1.0, "dof": 0
              }
            ]
          }
        ]
      },
      {
        "type": "LETTERBOX",
        "clips": [
          {
            "start_time": 0.0,
            "duration": 10.0,
            "enabled": true,
            "aspect_ratio": 2.35,
            "fade_in": 0.5,
            "fade_out": 0.5
          }
        ]
      }
    ]
  }
}
```

然后执行：`/cinematic play hello`

---

## 📋 脚本格式参考

### Meta 部分

```json
{
  "meta": {
    "id": "my_script",          // 必填：^[a-zA-Z0-9_]{1,32}$
    "name": "我的脚本",          // 必填：最长50字符
    "author": "作者",            // 必填：最长30字符
    "version": 3,               // 必填：必须为3
    "description": "...",       // 可选
    "interpolation": "smooth",  // 可选：脚本级默认曲线
    "curve_composition_mode": "override",  // 可选：override|composed
    // ... 15 项运行时行为标志位（见上表）
  }
}
```

### 时间轴与轨道

```json
{
  "timeline": {
    "total_duration": 30.0,     // 正数=有限时长，负数=无限时长，0=非法
    "tracks": [
      { "type": "CAMERA", "clips": [...] },
      { "type": "LETTERBOX", "clips": [...] },
      { "type": "AUDIO", "clips": [...] },
      { "type": "EVENT", "clips": [...] },
      { "type": "MOD_EVENT", "clips": [...] }
    ]
  }
}
```

### 相机片段

```json
{
  "start_time": 0.0,
  "duration": 10.0,
  "transition": "cut",                    // cut | crossfade
  "crossfade_duration": 0.5,              // 仅 crossfade 时有效
  "interpolation": "smooth",              // linear|smooth|ease_in|ease_out|ease_in_out
  "interpolation_scope": "clip",          // clip | segment
  "position_mode": "relative",            // relative (dx/dy/dz) | absolute (x/y/z)
  "loop": false,
  "loop_count": -1,                       // -1 = 无限循环
  "curve": {                              // 可选：贝塞尔路径
    "type": "bezier",
    "control_points": [
      { "x": 1.0, "y": 0.0, "z": 0.5 },
      { "x": 2.0, "y": 0.0, "z": -0.5 }
    ]
  },
  "keyframes": [...]
}
```

### 相机关键帧

```json
{
  "time": 0.0,
  "position": { "dx": 5, "dy": 2, "dz": 0 },  // 或 { "x": 100, "y": 64, "z": -200 }
  "yaw": 90,
  "pitch": 5,
  "roll": 0,
  "fov": 70,
  "zoom": 1.0,
  "dof": 0,
  "interpolation": "ease_in"   // 可选：逐关键帧覆盖（仅 SEGMENT 作用域）
}
```

### 插值系统

插值引擎支持 3 级覆盖层次结构：

```
脚本级（meta.interpolation）
  └─ 片段级（clip.interpolation）— 覆盖脚本默认值
       └─ 关键帧级（keyframe.interpolation）— 覆盖片段级（仅 SEGMENT 作用域）
```

**插值作用域：**
- **`clip`** — 曲线映射应用到整体片段进度，关键帧段内线性插值。仅在片段开头/结尾有缓入缓出效果。
- **`segment`** — 曲线映射独立应用到每个关键帧段。每段都有自己的缓入缓出。

**曲线组合模式：**
- **`override`**（默认）— 片段/关键帧级插值覆盖脚本级默认值。当片段为 `linear` 且脚本为 `smooth` 时，使用 `smooth`。
- **`composed`** — 数学组合：`adjustedT = clipCurve(scriptCurve(t))`。产生双重平滑效果。

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────────┐
│                    JSON 脚本 (v3)                        │
└──────────────────────┬──────────────────────────────────┘
                       │ ScriptParser.parse()
                       ▼
┌─────────────────────────────────────────────────────────┐
│                CinematicScript (POJO)                    │
│  ┌──────────┐  ┌──────────────────────────────────────┐ │
│  │ ScriptMeta│  │  Timeline → TimelineTrack[]          │ │
│  │  (15标志位│  │    ├─ CAMERA  → CameraClip[]        │ │
│  │   + 插值  │  │    ├─ LETTERBOX → LetterboxClip[]   │ │
│  │   + 组合) │  │    ├─ AUDIO   → AudioClip[]         │ │
│  └──────────┘  │    ├─ EVENT   → EventClip[]          │ │
│                │    └─ MOD_EVENT → ModEventClip[]      │ │
│                └──────────────────────────────────────┘ │
└──────────────────────┬──────────────────────────────────┘
                       │ ScriptPlayer.start()
                       ▼
┌─────────────────────────────────────────────────────────┐
│              ScriptPlayer（运行时驱动器）                  │
│  ┌─────────────────────────────────────────────────────┐│
│  │ TrackPlayer[]（逐轨道播放）                          ││
│  │  ├─ CameraTrackPlayer → KeyframeInterpolator        ││
│  │  ├─ LetterboxTrackPlayer → OverlayManager           ││
│  │  ├─ AudioTrackPlayer                                ││
│  │  └─ ModEventTrackPlayer                             ││
│  └─────────────────────────────────────────────────────┘│
└──────────────────────┬──────────────────────────────────┘
                       │ onRenderFrame() 每帧调用
                       ▼
┌─────────────────────────────────────────────────────────┐
│            CameraManager（单例桥梁）                      │
│  ┌──────────────────┐  ┌──────────────────┐            │
│  │ CameraProperties  │  │   CameraPath     │            │
│  │ (yaw/pitch/roll/  │  │ (position)       │            │
│  │  fov/zoom/dof)    │  │                  │            │
│  └────────┬─────────┘  └────────┬─────────┘            │
└───────────┼──────────────────────┼──────────────────────┘
            │                      │
            ▼                      ▼
┌─────────────────────────────────────────────────────────┐
│                  Mixin 层（只读）                         │
│  CameraMixin.onSetup() → 位置、偏航、俯仰                 │
│  GameRendererMixin.onGetFov() → 视场角/缩放              │
│  ClientCameraEvents.onComputeCameraAngles() → 滚转       │
│  GameRendererMixin.onRenderItemInHand() → 手臂隐藏       │
│  GameRendererMixin.onBobHurt/onBobView() → 摇晃抑制      │
└─────────────────────────────────────────────────────────┘
```

**设计原则：**
- `CameraProperties` + `CameraPath` 是纯 POJO — 互不知晓
- `CameraManager` 是 POJO 与 Mixin 层之间的**唯一桥梁**
- Mixin 层**只读** `CameraManager`，绝不写入
- 帧回调驱动：`onRenderFrame()` 由 `CameraMixin.onSetup()` 每渲染帧调用

---

## 📌 版本信息

**当前版本：0.3.0（开发中）**

| 组件 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Forge | 47.4.16+ |
| 脚本格式 | v3 |

---

## 🔮 未来计划

- **路径可视化** — 使用原版粒子实现相机路径预览，不同路径类型使用不同粒子效果，高亮起点/终点
- **时间轴开发界面** — 可视化时间轴界面，支持实时编辑、参数联动和路径时长/序列管理
- **音频轨道播放** — 完整实现音频轨道播放器，支持音量/音调控制和淡入淡出效果
- **游戏内脚本编辑器** — 集成编辑器，无需离开游戏即可创建和修改脚本
- **同步系统** — 基于时间轴的同步机制，与其他模组联动（角色动画、音乐播放、自定义事件）

---

## 🙏 致谢

- **freecam** — 相机实现思路，为本项目的相机控制功能奠定了基础
- **ExplorersCompass** — 监听器设计的灵感和参考，优化了目标定位和事件触发逻辑
- **Travelers-Titles** — 监听器实现的宝贵思路，助力事件监控系统完善
- **ReplayMod** — 帧回调驱动架构，启发了每渲染帧精确计时系统

---

## 📄 许可证

本项目基于 MIT 许可证发布。
