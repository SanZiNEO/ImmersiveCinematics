# 镜头跟踪系统 (look_at + follow)
**创建日期**: 2026-07-27

## 是什么

Camera clip 新增 `tracking` 配置，让运镜跟踪目标。两部分组合形成动态摄像机效果：

- **关键帧** — 定义镜头的空间路径（position/yaw/pitch/roll 等）
- **tracking（clip 级）** — 决定是否覆盖关键帧输出的 yaw/pitch/position

## 两种跟踪模式

### look_at — 注视目标

覆盖 yaw/pitch。位置走关键帧，朝向始终对准目标。

支持的目标类型：
- `coordinate` — 固定坐标点
- `entity` — 实体（当前用 `@p` 选择器，后续扩展 UUID/其他选择器）

### follow — 跟随目标

覆盖 position。朝向走关键帧，位置为玩家位置 + 相对偏移。

支持的目标类型：
- `entity` — 实体（当前为玩家）

## 数据模型

clip 级字段，与 keyframes 并列：

```json
{
  "start_time": 0,
  "duration": 10,
  "tracking": {
    "look_at": "none",                    // none | coordinate | entity
    "look_target": [100.0, 64.0, 200.0],  // coordinate 时: 固定坐标
    "target_selector": "@p",              // entity 时: 选择器
    "follow": "none",                     // none | entity
    "follow_offset": [0.0, 2.0, 0.0]        // follow 时: 相对玩家偏移
  },
  "keyframes": [
    {"time": 0, "position": {...}, "yaw": 0, "pitch": 0},
    {"time": 10, "position": {...}, "yaw": 90, "pitch": 10}
  ]
}
```

## 二合一效果

关键帧定义镜头路径，tracking 决定最终输出：

| 组合 | 行为 |
|------|------|
| 关键帧路径 + look_at(coordinate) | 镜头沿路径飞行，始终注视固定点 |
| 关键帧路径 + look_at(player) | 镜头沿路径飞行，始终注视玩家 |
| 关键帧朝向 + follow(player) | 镜头跟随玩家移动，保持相对偏移 |
| look_at + follow(player) | 第三人称跟拍 |

## 运行时

`CameraTrackPlayer.writeAttributes()` 中执行：

```
1. 关键帧插值 → yaw, pitch, position, roll, fov, zoom, dof
2. 如果 tracking.look_at != "none":
     计算目标方向 → atan2 算出 yaw/pitch → 覆盖
3. 如果 tracking.follow != "none":
     跟随目标位置 + offset → 覆盖 position
4. 写入 CameraManager
```

## 扩展性

当前只处理玩家实体（`@p`），但接口预留了：
- `look_at: "entity"` 后续可扩展 UUID、`@e[type=villager]` 等选择器
- `follow: "entity"` 同理
- `target_selector` 字段兼容所有选择器格式
- 非玩家实体需要目标所在区块已加载 → 0.3.5 预加载完成后自然可用

## 改动文件

| 文件 | 操作 |
|------|------|
| `schema.json` | 修改 — CAMERA clip 加 `tracking` 字段 |
| `script/ScriptParser.java` | 修改 — 解析 tracking 块 |
| `script/CameraTrackPlayer.java` | 修改 — writeAttributes 中应用 look_at/follow |
