# 事件轨道 — 关键帧驱动改造
**创建日期**: 2026-07-27

## 是什么

EVENT 从当前 clip 级字段模型改为 keyframe 驱动的多点触发模型。

## 当前模型（clip 驱动）

`ScriptEventManager.onServerTick()` 用 `nextClipIndex` 顺序遍历 `eventClips`：
- clip 到达 start_time → 执行 clip.getCommand()
- 所有 clip 消费完 → 结束

## 目标模型（keyframe 驱动）

- clip 作为 keyframe 的容器，start_time + duration 定义有效区间
- keyframes 中的每个 keyframe 是一个触发点，time 为 clip 内偏移
- 遍历 keyframes，到达 globalTime 时执行 command
- 已触发的 keyframe 去重保护（只触发一次）
- 首尾 keyframe 可以为空值（只有 time，没有 command）

## 数据模型

```
EVENT {
  clips: {}         // 不再需要 clip 级字段
  keyframes: {
    event_type: string (default "command")
    command: string
  }
}
```

## 运行时流程

```
ScriptEventManager.onServerTick():
  对每个播放中的脚本:
    遍历所有 EVENT clip → 遍历 clip.keyframes
      找到 globalTime >= keyframe.time 且尚未触发的 keyframe
      如果有 command → player.server.getCommands().performPrefixedCommand()
      标记已触发
```

命令执行方式不变（复用现有的 `executeCommand` 方法）。

## 编辑器

- EVENT 轨道不渲染 clip 段矩形
- 在 clip 覆盖的时间段内，每条 keyframe 显示为标记点（菱形/竖线）
- 点击标记点展开属性面板编辑 command
- 空的 keyframe（无 command）显示为灰色标记点

## 改动文件

| 文件 | 操作 |
|------|------|
| `schema.json` | 修改 — EVENT keyframe 加 command/event_type |
| `script/ScriptEventManager.java` | 修改 — clip 遍历改为 keyframe 遍历 |
| `editor/area/TimelineArea.java` | 修改 — EVENT keyframe 标记点渲染 |
| `editor/area/LeftPanelArea.java` | 修改 — EVENT 属性面板适配 |
