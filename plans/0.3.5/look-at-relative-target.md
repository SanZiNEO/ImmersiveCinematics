# look_at 相对目标（Look-At Relative Target）：目标点支持"基准 + 偏移"

**状态**: ✅ 已完成（look_at_target 对象已实现）

## 一句话定义

`look_at` 的目标点从"绝对坐标 / 结构中心 / 实体中心"扩展为**任意基准 + 偏移**——可以看"羊正上方 3 格"、"触发点前方 5 格"、"某坐标点再偏移"，基准动态时目标点每帧跟随。

## 现状（为什么需要）

| 现有模式 | 目标点 | 局限 |
|---|---|---|
| `"entity"` + selector | 实体正中心 | 只能看中心，不能看"实体上方/前方 N 格" |
| `"coordinate"` + x/y/z | 世界绝对坐标 | 绝对——相对场景（玩家触发点运行时才知道）写不了 |
| `"coordinate"` + structure | 结构中心 | 只能看结构中心 |

缺口：**"相对实体的坐标"**（看实体附近的某个偏移点）和**"相对目标点的偏移"**。

## 设计草案

### 关键帧新字段 `look_at_target`（自描述对象，与 position/贝塞尔控制点同模式）

```json
"look_at": "coordinate",

// 1. 绝对坐标（与 look_at_target_x/y/z 等价）
"look_at_target": { "x": 100, "y": 64, "z": 200 },

// 2. 相对触发点偏移（与 position relative 同语义）
"look_at_target": { "dx": 3, "dy": 2, "dz": -5 },

// 3. 相对实体偏移（动态基准，每帧求值）
"look_at_target": { "relative_to": "@e[type=sheep]", "dx": 0, "dy": 1.6, "dz": 0 },

// 4. 相对坐标点偏移（基准 = 固定坐标 + 偏移）
"look_at_target": { "relative_to": "coordinate", "relative_x": 100, "relative_y": 64, "relative_z": 200, "dx": 3, "dy": 2, "dz": -5 }
```

### 字段语义

| 字段 | 说明 |
|---|---|
| `x/y/z` | 绝对世界坐标（模式 1） |
| `dx/dy/dz` | 偏移量（配合基准；基准缺省 = 触发点） |
| `relative_to` | 基准：`"coordinate"`（配 relative_x/y/z）或实体选择器（如 `@e[type=sheep]`，每帧求实体位置 + 偏移） |

### 兼容层（旧脚本零迁移）

- `look_at_target_x/y/z`（散字段绝对坐标）→ 保留，视为模式 1
- `look_at_target_structure` → 保留（结构中心，优先级最高：写结构 = 只看结构）
- 新对象 `look_at_target` 存在时优先于散字段

### 求值（evalLookTarget coordinate 分支重构）

```
look_at_target_structure 非空 → 结构中心（现有）
look_at_target 对象存在 →
    有 x/y/z         → 绝对点
    relative_to=coordinate → 基准坐标 + dx/dy/dz
    relative_to=实体      → 每帧实体位置 + dx/dy/dz
    只有 dx/dy/dz    → 触发点 + dx/dy/dz
否则 → look_at_target_x/y/z（现有）
```

两端关键帧目标点插值机制不变（t0/t1 → 插值目标 → 计算 yaw/pitch）。

### 空片段原则

- `relative_to` 实体找不到 → 该端无目标 → 空片段（isClipUsable 扩展检查相对实体基准）
- 与 look_at entity 现有处理一致

## 边界与待定

1. 四种子模式全做，还是先做"相对实体"（最常用场景：跟拍偏移视角）？
2. 编辑器：`look_at_target` 对象输入面板（相对类型下拉 + selector + 3 偏移输入 + 坐标输入）——主要成本在编辑器侧
3. 与 dynamic-yaw-reference（yaw 基准）的关系：look_at 目标相对化解决"看哪"，yaw 基准解决"朝向基准"——互补不冲突
4. 与 morph 转场：两端目标点插值机制已覆盖动态基准（每帧求值后插值）

## 依赖

- 求值框架与 isClipUsable 已有（相对实体检查是增量）
- 不依赖 yaw 基准 / 预设，可独立排期

## 执行前再看 / 具体方案

- **项目文件**：
  - `script/CameraTrackPlayer.java`：`evalLookTarget`（coordinate/entity/none 三分支）、`isClipUsable`、`resolveEntity`、`originPos`。
  - `script/ScriptParser.parseFieldBySchema`：未知对象会自动转 `Map`，`look_at_target` 可直接用 `kf.getObject("look_at_target")` 解析。
  - `common/src/main/resources/schema.json` CAMERA keyframes：新增 `look_at_target`（map 类型），保留旧散字段。
- **做法**：在 `evalLookTarget` 优先读 `look_at_target` 对象——有 x/y/z 为绝对；`relative_to=coordinate` 用 `relative_x/y/z + dx/dy/dz`；`relative_to` 为实体 selector 每帧 `resolveEntity` + 偏移；只有 dx/dy/dz 用 `originPos` + 偏移。`isClipUsable` 增加对 `relative_to` 实体不存在返回 false。
- **执行时再看**：`CameraTrackPlayer.evalLookTarget/isClipUsable/resolveEntity`、`ScriptParser`、schema.json、编辑器属性面板。
