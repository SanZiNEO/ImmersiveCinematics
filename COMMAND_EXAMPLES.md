# Immersive Cinematics 指令使用示例文档

本文档详细介绍了 Immersive Cinematics 模组的所有指令用法，包括新增的高级运镜效果和FOV控制功能。

## 基本指令格式

所有指令前缀：`/ic-run`

通用参数：
- `duration`: 运动持续时间（秒），最小值为 0.1 秒

## 1. 摄像机设置指令

### 1.1 设置摄像机位置
**指令格式：**
```
/ic-run set position <pos>
```

**参数说明：**
- `pos`: 目标位置（Vec3 格式，如 `0 64 0`）

**示例：**
```
/ic-run set position ~0 ~10 ~0
```
效果：将摄像机位置设置在当前位置上方10个方块处

### 1.2 设置摄像机旋转角度
**指令格式：**
```
/ic-run set rotation <yaw> <pitch>
```

**参数说明：**
- `yaw`: 偏航角（-180到180度，左右旋转）
- `pitch`: 俯仰角（-90到90度，上下旋转）

**示例：**
```
/ic-run set rotation 90 -30
```
效果：将摄像机朝向右侧（90度），并向下倾斜30度

### 1.3 设置摄像机FOV
**指令格式：**
```
/ic-run set fov <fov>
```

**参数说明：**
- `fov`: FOV值（5-120度）

**示例：**
```
/ic-run set fov 40
```
效果：将摄像机FOV设置为40度（长焦效果）

### 1.4 重置摄像机设置
**指令格式：**
```
/ic-run set reset
```

**示例：**
```
/ic-run set reset
```
效果：重置摄像机位置、旋转角度和FOV到默认设置

## 2. 直线运动指令

### 2.1 直接朝向目标的直线运动
**指令格式：**
```
/ic-run direct <start> <end> <duration> [fov <startFov> <endFov>] [heading <offset>]
```

**参数说明：**
- `start`: 起始位置（Vec3 格式，如 `0 64 0`）
- `end`: 结束位置（Vec3 格式）
- `duration`: 运动持续时间（秒）
- `fov` (可选): FOV动画参数
  - `startFov`: 起始FOV值（5-120度）
  - `endFov`: 结束FOV值（5-120度）
- `heading` (可选): 朝向偏移（-180到180度）

**示例：**
```
/ic-run direct 0 64 0 10 64 10 5 fov 70 40
```
效果：从 (0, 64, 0) 到 (10, 64, 10) 直线运动，耗时 5 秒，FOV从70度缩放到40度

### 2.2 平滑曲线运动（带视角过渡）
**指令格式：**
```
/ic-run smooth <start> <end> <duration> [fov <startFov> <endFov>] [heading <offset>]
```

**示例：**
```
/ic-run smooth 0 64 0 10 64 10 3 heading 45
```
效果：从 (0, 64, 0) 到 (10, 64, 10) 平滑曲线运动，镜头朝向偏移45度，耗时 3 秒

## 2. 环绕运动指令

**指令格式：**
```
/ic-run orbit <center> <radius> <speed> <height> <duration> [fov <startFov> <endFov>] [heading <offset>]
```

**参数说明：**
- `center`: 环绕中心点（Vec3 格式）
- `radius`: 环绕半径（最小值 0.1 方块）
- `speed`: 角速度（弧度/秒）
- `height`: 垂直位移（可正可负，单位方块）
- `duration`: 运动持续时间（秒）
- `fov` (可选): FOV动画参数
- `heading` (可选): 朝向偏移

**示例：**
```
/ic-run orbit 5 64 5 3 60 2 4 fov 60 80
```
效果：围绕 (5, 64, 5) 点进行环绕运动，半径 3 方块，角速度 60 度/秒，垂直上升 2 方块，持续 4 秒，FOV从60度扩展到80度

## 3. 贝塞尔曲线运动指令

**指令格式：**
```
/ic-run bezier <start> <control> <end> <duration> [fov <startFov> <endFov>] [heading <offset>]
```

**参数说明：**
- `start`: 起始位置（Vec3 格式）
- `control`: 控制点（决定曲线形状，Vec3 格式）
- `end`: 结束位置（Vec3 格式）
- `duration`: 运动持续时间（秒）
- `fov` (可选): FOV动画参数
- `heading` (可选): 朝向偏移

**示例：**
```
/ic-run bezier 0 64 0 5 70 5 10 64 10 3 heading -30
```
效果：从 (0, 64, 0) 出发，经过控制点 (5, 70, 5)，到达 (10, 64, 10)，形成一个平滑的拱形曲线，朝向偏移-30度，耗时 3 秒

## 4. 螺旋上升/下降运动指令

**指令格式：**
```
/ic-run spiral <center> <radius> <speed> <height> <duration> [fov <startFov> <endFov>] [heading <offset>]
```

**参数说明：**
- `center`: 螺旋中心点（Vec3 格式）
- `radius`: 螺旋半径（最小值 0.1 方块）
- `speed`: 角速度（弧度/秒）
- `height`: 垂直位移（可正可负，单位方块）
- `duration`: 运动持续时间（秒）
- `fov` (可选): FOV动画参数
- `heading` (可选): 朝向偏移

**示例：**
```
/ic-run spiral 5 64 5 4 45 3 5 fov 80 60
```
效果：围绕 (5, 64, 5) 点螺旋上升，半径 4 方块，角速度 45 度/秒，上升 3 方块，持续 5 秒，FOV从80度缩放到60度

## 5. 滑动变焦运动指令

### 5.1 标准滑动变焦运动
**指令格式：**
```
/ic-run dolly <start> <end> <target> <duration> [fov <startFov> <endFov>] [heading <offset>]
```

**参数说明：**
- `start`: 起始位置（Vec3 格式）
- `end`: 结束位置（Vec3 格式）
- `target`: 聚焦点（Vec3 格式，通常是路径中点）
- `duration`: 运动持续时间（秒）
- `fov` (可选): FOV动画参数
- `heading` (可选): 朝向偏移

**示例：**
```
/ic-run dolly 0 64 0 10 64 0 5 64 0 3 fov 40 80
```
效果：从 (0, 64, 0) 移动到 (10, 64, 0)，聚焦在 (5, 64, 0) 点，FOV从40度扩展到80度，产生强烈的空间压缩/拉伸错觉，耗时 3 秒

### 5.2 简化版滑动变焦运动（推荐）
**指令格式：**
```
/ic-run dolly simple <start> <target> <distance> <duration> [strength <strength>] [fov <startFov> <endFov>] [heading <offset>]
```

**参数说明：**
- `start`: 起始位置（Vec3 格式）
- `target`: 聚焦点（Vec3 格式，通常是路径中点）
- `distance`: 运动距离（从起始位置到结束位置的距离，单位方块）
- `duration`: 运动持续时间（秒）
- `strength` (可选): 变焦强度（0.1-2.0，默认1.0），值越大变焦效果越强
- `fov` (可选): FOV动画参数
- `heading` (可选): 朝向偏移

**示例：**
```
/ic-run dolly simple ~0 ~5 ~0 ~5 ~5 ~0 10 3 strength 1.5
```
效果：从当前位置移动到聚焦点左侧10个方块处，保持聚焦在 (x+5, y+5, z) 点，变焦强度为1.5倍，耗时 3 秒

### 5.3 滑动变焦运动技术说明
滑动变焦运动通过同时移动相机位置和调整FOV实现"希区柯克变焦"效果，给观众带来强烈的空间压缩/拉伸错觉。建议：
- 使用时确保相机运动方向与聚焦点在同一直线上
- 适当调整 duration 和 distance 参数以获得最佳视觉效果
- 使用 strength 参数控制变焦强度，避免过于夸张的效果

## 6. 静态旋转/摇镜头运动指令

**指令格式：**
```
/ic-run pan <position> <startPitch> <startYaw> <endPitch> <endYaw> <duration> [fov <startFov> <endFov>] [heading <offset>]
```

**参数说明：**
- `position`: 固定位置（Vec3 格式）
- `startPitch`: 起始俯仰角（-90 到 90 度）
- `startYaw`: 起始偏航角（-180 到 180 度）
- `endPitch`: 结束俯仰角（-90 到 90 度）
- `endYaw`: 结束偏航角（-180 到 180 度）
- `duration`: 运动持续时间（秒）
- `fov` (可选): FOV动画参数
- `heading` (可选): 朝向偏移

**示例：**
```
/ic-run pan 5 64 5 -15 0 -30 90 4 fov 65 50
```
效果：在 (5, 64, 5) 位置固定不动，镜头从俯仰角 -15 度、偏航角 0 度，平滑旋转到俯仰角 -30 度、偏航角 90 度，FOV从65度缩放到50度，耗时 4 秒

## Vec3 坐标格式说明

Minecraft 支持相对坐标和绝对坐标：
- 绝对坐标：`x y z`（如 `5 64 5`）
- 相对坐标：`~x ~y ~z`（如 `~5 ~ ~5` 表示当前位置 X+5，Z+5）

## 使用技巧

### 配合实际场景使用建议

#### 建筑展示
```
/ic-run dolly ~-10 ~5 ~-10 ~10 ~5 ~10 ~0 ~5 ~0 4 fov 75 40
```
效果：从建筑一侧移动到另一侧，聚焦在建筑中心，FOV从75度缩放到40度，产生强烈的空间感

#### Boss 登场
```
/ic-run spiral ~0 ~10 ~0 8 30 -5 6 fov 90 60
```
效果：从高位螺旋下降到地面，镜头始终朝向 boss 生成点，FOV从90度缩放到60度

#### 自然景观
```
/ic-run bezier ~0 ~8 ~0 ~0 ~15 ~-10 ~0 ~10 ~-20 8 fov 60 90
```
效果：从山脚下升起，经过山峰侧面，俯瞰山谷全景，FOV从60度扩展到90度

### 参数调整建议

#### 运动速度
- 快速运动：1-2 秒
- 中等运动：3-5 秒
- 缓慢运动：6-10 秒
- 超慢运动：10 秒以上（用于强调场景）

#### FOV范围
- 正常视角：60-70度
- 广角镜头：80-120度（适合场景展示）
- 长焦镜头：30-50度（适合特写镜头）
- 极端特写：5-25度（强烈压缩感）