# Immersive Cinematics 命令使用说明

## 1. 基础操作命令

### 1.1 设置摄像机位置
```
/ic-run set position <x> <y> <z>
```
- 功能：设置摄像机的固定位置
- 参数：
  - <x>, <y>, <z>：摄像机的目标坐标

### 1.2 设置摄像机旋转
```
/ic-run set rotation <yaw> <pitch>
```
- 功能：设置摄像机的固定旋转角度
- 参数：
  - <yaw>：偏航角（水平旋转角度），范围：-180 到 180 度
  - <pitch>：俯仰角（垂直旋转角度），范围：-90 到 90 度

### 1.3 设置摄像机视野
```
/ic-run set fov <fov>
```
- 功能：设置摄像机的固定视野角度
- 参数：
  - <fov>：视野角度，范围：5 到 120 度

### 1.4 重置摄像机设置
```
/ic-run set reset
```
- 功能：重置摄像机到默认设置（跟随玩家）

## 2. 运镜命令

### 2.1 直接线性运动
```
/ic-run direct <startX> <startY> <startZ> <endX> <endY> <endZ> <duration> [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：摄像机从起点到终点进行直接线性运动
- 参数：
  - <startX>, <startY>, <startZ>：起点坐标
  - <endX>, <endY>, <endZ>：终点坐标
  - <duration>：运动持续时间（秒）
  - [fov <startFov> <endFov>]：可选，视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

### 2.2 平滑曲线运动
```
/ic-run smooth <startX> <startY> <startZ> <endX> <endY> <endZ> <duration> [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：摄像机从起点到终点进行平滑曲线运动
- 参数：
  - <startX>, <startY>, <startZ>：起点坐标
  - <endX>, <endY>, <endZ>：终点坐标
  - <duration>：运动持续时间（秒）
  - [fov <startFov> <endFov>]：可选，视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

### 2.3 贝塞尔曲线运动
```
/ic-run bezier <startX> <startY> <startZ> <controlX> <controlY> <controlZ> <endX> <endY> <endZ> <duration> [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：摄像机沿贝塞尔曲线路径运动
- 参数：
  - <startX>, <startY>, <startZ>：起点坐标
  - <controlX>, <controlY>, <controlZ>：控制点坐标
  - <endX>, <endY>, <endZ>：终点坐标
  - <duration>：运动持续时间（秒）
  - [fov <startFov> <endFov>]：可选，视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

### 2.4 轨道运动
```
/ic-run orbit <centerX> <centerY> <centerZ> <radius> <speed> <height> <duration> [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：摄像机围绕中心点进行轨道运动
- 参数：
  - <centerX>, <centerY>, <centerZ>：中心点坐标
  - <radius>：轨道半径（格数）
  - <speed>：旋转速度（度/秒）
  - <height>：垂直偏移量（格数）
  - <duration>：运动持续时间（秒）
  - [fov <startFov> <endFov>]：可选，视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

### 2.5 螺旋运动
```
/ic-run spiral <centerX> <centerY> <centerZ> <radius> <speed> <height> <duration> [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：摄像机围绕中心点进行螺旋上升/下降运动
- 参数：
  - <centerX>, <centerY>, <centerZ>：中心点坐标
  - <radius>：轨道半径（格数）
  - <speed>：旋转速度（度/秒）
  - <height>：总垂直位移（格数）
  - <duration>：运动持续时间（秒）
  - [fov <startFov> <endFov>]：可选，视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

### 2.6 滑动变焦运动（Dolly Zoom）

#### 2.6.1 标准滑动变焦
```
/ic-run dolly <startX> <startY> <startZ> <endX> <endY> <endZ> <targetX> <targetY> <targetZ> <duration> [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：实现经典的滑动变焦效果，摄像机移动同时反向调整视野
- 参数：
  - <startX>, <startY>, <startZ>：起点坐标
  - <endX>, <endY>, <endZ>：终点坐标
  - <targetX>, <targetY>, <targetZ>：聚焦点坐标
  - <duration>：运动持续时间（秒）
  - [fov <startFov> <endFov>]：可选，自定义视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

#### 2.6.2 简化滑动变焦
```
/ic-run dolly simple <startX> <startY> <startZ> <targetX> <targetY> <targetZ> <duration> [forward|backward] [strength <strength>] [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：简化版滑动变焦，自动计算结束位置
- 参数：
  - <startX>, <startY>, <startZ>：起点坐标
  - <targetX>, <targetY>, <targetZ>：聚焦点坐标
  - <duration>：运动持续时间（秒）
  - [forward|backward]：可选，运动方向（默认为 forward）
  - [strength <strength>]：可选，变焦强度（0.1-2.0，默认1.0）
  - [fov <startFov> <endFov>]：可选，自定义视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

#### 2.6.3 高级滑动变焦
```
/ic-run dolly advanced <startX> <startY> <startZ> <targetX> <targetY> <targetZ> <backgroundX> <backgroundY> <backgroundZ> <duration> [forward|backward] [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：高级滑动变焦，支持后景强化效果
- 参数：
  - <startX>, <startY>, <startZ>：起点坐标
  - <targetX>, <targetY>, <targetZ>：聚焦点坐标
  - <backgroundX>, <backgroundY>, <backgroundZ>：后景位置坐标
  - <duration>：运动持续时间（秒）
  - [forward|backward]：可选，运动方向（默认为 forward）
  - [fov <startFov> <endFov>]：可选，自定义视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

#### 2.6.4 强度控制滑动变焦
```
/ic-run dolly strength <startX> <startY> <startZ> <targetX> <targetY> <targetZ> <duration> <strength> [forward|backward] [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：支持变焦强度控制的滑动变焦
- 参数：
  - <startX>, <startY>, <startZ>：起点坐标
  - <targetX>, <targetY>, <targetZ>：聚焦点坐标
  - <duration>：运动持续时间（秒）
  - <strength>：变焦强度（0.1-2.0，默认1.0）
  - [forward|backward]：可选，运动方向（默认为 forward）
  - [fov <startFov> <endFov>]：可选，自定义视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

### 2.7 静态平移运动
```
/ic-run pan <x> <y> <z> <startPitch> <startYaw> <endPitch> <endYaw> <duration> [fov <startFov> <endFov>] [heading <offset>]
```
- 功能：摄像机在固定位置进行旋转运动（平移视角）
- 参数：
  - <x>, <y>, <z>：摄像机固定位置
  - <startPitch>, <startYaw>：起始旋转角度
  - <endPitch>, <endYaw>：结束旋转角度
  - <duration>：运动持续时间（秒）
  - [fov <startFov> <endFov>]：可选，视野角度变化
  - [heading <offset>]：可选，朝向偏移角度（度）

## 3. 参数说明

### 3.1 通用参数
- **duration**：运动持续时间，单位：秒（必须大于0）
- **fov <startFov> <endFov>**：视野角度变化，范围：5-120度
- **heading <offset>**：朝向偏移角度，范围：-180到180度

### 3.2 轨道运动参数
- **radius**：轨道半径，单位：格数（必须大于0）
- **speed**：旋转速度，单位：度/秒（必须大于0）
- **height**：垂直偏移量，单位：格数（可为负值表示下降）

### 3.3 滑动变焦参数
- **targetX, targetY, targetZ**：聚焦点坐标（摄像机始终看向该点）
- **backgroundX, backgroundY, backgroundZ**：后景位置（增强空间压缩感）
- **strength**：变焦强度（0.1-2.0，默认1.0）

## 4. 使用示例

### 4.1 基础示例
```
/ic-run direct 0 64 0 10 64 10 5 fov 70 50
```
- 功能：摄像机从(0,64,0)到(10,64,10)直线运动，持续5秒，FOV从70度降到50度

### 4.2 贝塞尔曲线示例
```
/ic-run bezier 0 64 0 5 74 5 10 64 10 5 fov 70 50
```
- 功能：摄像机沿贝塞尔曲线路径运动，持续5秒，FOV从70度降到50度

### 4.3 轨道运动示例
```
/ic-run orbit 5 64 5 5 36 0 3 fov 70 50
```
- 功能：摄像机围绕(5,64,5)点进行轨道运动，半径5格，速度36度/秒，持续3秒，FOV从70度降到50度

### 4.4 滑动变焦示例
```
/ic-run dolly simple 0 64 0 5 64 5 3
```
- 功能：简化版滑动变焦，从(0,64,0)到聚焦点(5,64,5)，持续3秒

### 4.5 高级滑动变焦示例
```
/ic-run dolly advanced 0 64 0 5 64 5 5 64 25 3
```
- 功能：高级滑动变焦，从(0,64,0)到聚焦点(5,64,5)，后景位置(5,64,25)，持续3秒

## 5. 注意事项

1. 所有坐标可以使用相对坐标（前缀~），例如：~/~/~表示当前位置
2. 角度参数使用度作为单位
3. 持续时间必须大于0
4. 视野角度范围是5-120度
5. 使用滑动变焦时，建议聚焦点和后景位置在同一直线上以获得最佳效果
6. 强度参数建议值在0.5-1.5之间，避免过于极端的效果