# 0.4.0 路线图

**依赖**: 先完成 `plans/0.3.2/` 的全部 7 项

---

## 1. 音频轨道

→ 详见 `audio-system.md`

---

## 2. 事件轨道（Event Track）

数据模型变更：EVENT 从 clip 段模型改为 **keyframe 驱动的时间点模型**。

### 数据模型

```json
{
  "type": "EVENT",
  "clips": [
    {
      "start_time": 0,
      "duration": 0,
      "keyframes": [
        { "time": 3.5, "command": "/time set day", "event_type": "command" },
        { "time": 5.0, "command": "/weather clear" }
      ]
    }
  ]
}
```

- `duration` 固定为 0，clip 仅作为 keyframe 的容器
- 每条关键帧 = 一个触发点，到达 `time` 时触发
- 运行时不检查 clip 段范围，直接遍历所有 keyframe 触发
- 允许空 keyframe 数组（clip 存在但没有触发点）

### 编辑器行为

- 时间轴上 EVENT 轨道不渲染 clip 段矩形
- 在 clip 覆盖的时间段内，每条 keyframe 显示为**独立标记点**（菱形/竖线）
- 点击标记点展开属性面板编辑命令
- 编辑器预览模式下模拟触发（显示命令但不执行）

### 运行时

- `EventTrackPlayer` 继承现有 TrackPlayer 接口
- `onRenderFrame()` 检查缓存的时间→触发状态，到达时通过服务端执行
- 一次性触发保护（每个 keyframe 只触发一次）

### 依赖

- 验证服务端 ScriptEventManager 命令执行流程完整性

---

## 3. 判定点轨道（Pause Point Track）

新增轨道类型 `PAUSE_POINT`，同属 **keyframe 驱动的时间点模型**。

### 用途

脚本播放到指定时间点时暂停（时间冻结），等待条件满足后继续。

**场景：**
- 对话选项：文字显示时暂停，玩家选择后继续
- 演出节奏控制：脚本播放到此等待玩家操作
- 与其他模组联动：等待动画播放完成

### 数据模型

```json
{
  "type": "PAUSE_POINT",
  "clips": [
    {
      "start_time": 0,
      "duration": 0,
      "keyframes": [
        {
          "time": 3.5,
          "resume_condition": "player_interact",
          "timeout": 30,
          "allow_mouse": true,
          "allow_keyboard": true
        }
      ]
    }
  ]
}
```

- `resume_condition`：恢复条件（`player_interact` / `custom` / `timeout`）
- `timeout`：超时秒数，超过自动恢复（可选）
- `allow_mouse/allow_keyboard`：暂停期间是否允许输入（为对话选项等交互场景打开控制权）

### 编辑器行为

同 EVENT 轨道：渲染 keyframe 标记点，不渲染 clip 段。

### 运行时

- `PausePointTrackPlayer` 实现 TrackPlayer
- 到达关键帧 → ScriptPlayer 切换为**暂停状态**（时间冻结，轨道保持当前帧）
- 条件满足 → ScriptPlayer 恢复时间推进
- 暂停期间 `CameraManager` 保持输出最后一帧
- 现有轨道（Camera/Letterbox/Audio）无需改动

### 服务端交互（可选）

如果恢复条件需要服务端判断（如等待其他玩家确认），通过现有 S2C/C2S 网络包扩展。

---

## 4. 时间轴多轨道支持

- 轨道列显示全部轨道类型（CAMERA / LETTERBOX / AUDIO / EVENT / PAUSE_POINT）
- 每种轨道独立颜色标识、独立行渲染
- EVENT 和 PAUSE_POINT 轨道只渲染关键帧标记点，不渲染 clip 段

---

## 5. IMAGE 轨道 (覆盖层: fade / image / subtitle)

新增 `IMAGE` 轨道，走 `OverlayManager` 注册 `ImageLayer`。一个轨道承载三类效果：

| 类型 | clip 字段 | 说明 |
|------|----------|------|
| screen_fade | `type: "fade"` | 全屏颜色遮罩。`color` + 关键帧 `opacity` → 场景淡入淡出 |
| image | `type: "image"` | PNG 纹理叠加。`path` + 关键帧 `opacity/scale/anchor` |
| subtitle | `type: "subtitle"` | 文字字幕。`text` + 关键帧 `opacity/font_size/anchor` |

关键帧统一：`time` + `opacity`(0-1)。image 额外 `scale`/`anchor`，subtitle 额外 `font_size`/`anchor`。

BBS 参考结论：fade 不放 camera clip 里，不放独立轨道。归类到 IMAGE 轨道作为 `type: "fade"` 的 clip。

---

## 6. 编辑器音频联动

- 编辑器预览时音频随播放头同步（精确定位 `ogg` 的 `readAll()` 缓存，WAV 用 `InputStream.skip()` seek）
- 音频 clip 属性面板（sound 路径自动补全、source/attenuation 下拉）
- 拖拽 clip/关键帧时音频实时响应

---

## 7. 相机区块预加载

→ 详见 `camera-chunk-preload.md`

---

## 8. 镜头跟踪系统 (look_at + follow)

→ 详见 `camera-tracking-system.md`

---

## 9. 镜头呼吸扰动

→ 详见 `camera-breath-shake.md`

---

## 10. 维度字段

- camera clip 新增 `dimension` 字段，默认 `"same"`（和玩家同维度）
- 支持 `minecraft:overworld` / `minecraft:the_nether` / `minecraft:the_end` 等显式维度
- 解析时校验维度存在性，不存在报错
- 区块预加载按维度独立管理 ticket
- 跨维度邻接 clip 强制 `transition: cut`（静默覆盖 morph，作者写了 morph 也不会生效）

---

## 11. Bezier 曲线编辑器支持

- `interpolation` 下拉增加 `"bezier"` 选项（当前只有 `linear` ↔ `smooth`）
- 选中 bezier 时显示控制点 P1、P2 的 x/y/z 编辑字段
- Timeline 上的 clip 矩形内渲染控制点连线可视化（P0→P1 和 P3→P2 虚线）

---

## 12. 预设片段库（远期）

- LeftPanel 新增"预设"页面（和脚本列表/脚本属性/片段属性/关键帧属性并列）
- 每种轨道提供预设 clip 模板（CAMERA=绕圈/推拉/平移，LETTERBOX=淡入淡出/硬切，AUDIO=淡入淡出）
- 双击预设 → 弹出参数面板 → 填起始时间/目标/速度 → 按钮插入到轨道时间轴
- 预设可保存到文件、从社区分享导入
