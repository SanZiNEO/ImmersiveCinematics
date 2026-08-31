# 预设系统（Presets）：参数化脚本一键生成

**状态**: ✅ 系统已完成（内置预设库目前仅“环绕轨道”）

## 一句话定义

预设 = **参数化脚本模板**：玩家输入几个参数（圆心、半径、朝向……），预设内部完成所有计算（如贝塞尔控制点），自动生成可直接播放的脚本 JSON——"一键启用"，玩家不需要懂曲线/关键帧的具体写法。

## 场景（用户例子：环绕轨道预设）

玩家想做一个"环绕某目标一圈"的镜头，但不该让他自己算三个贝塞尔曲线怎么拼圆。预设暴露的参数：

| 参数 | 语义 | 支持模式 |
|---|---|---|
| 圆心 | 环绕中心 | 相对（触发点/实体）或绝对 |
| 半径 | 环绕半径（格） | 数值 |
| 高度 | 弧线高度（格，相对圆心/绝对） | 数值 |
| 朝向 | 看向谁（实体/坐标/固定方向） | 选择器 |
| 圈数 / 时长 | 转几圈、多快 | 数值 |

预设内部自动完成：三段三阶贝塞尔拼圆（每段 120°，控制点距离 = `R × 4/3 × tan(θ/4)` ≈ `0.77R`）、关键帧、look_at、fov 等——输出完整 CAMERA clip。

## 设计草案

### 预设定义格式

```json
{
  "id": "orbit_circle",
  "name": "环绕轨道",
  "description": "围绕目标做圆弧环绕（三段贝塞尔拼圆）",
  "params": [
    { "key": "center", "type": "position", "label": "圆心", "mode": "relative|absolute" },
    { "key": "radius", "type": "number", "label": "半径", "min": 2, "max": 200, "default": 8 },
    { "key": "height", "type": "number", "label": "高度", "default": 2 },
    { "key": "look_at", "type": "selector", "label": "朝向目标" },
    { "key": "duration", "type": "number", "label": "时长", "default": 8 }
  ],
  "generate": "orbit_circle"   // 生成逻辑（代码注册，非纯数据）
}
```

### 生成逻辑分层

- **数据层**：参数 schema（编辑器渲染表单）
- **逻辑层**：生成函数（Java 注册，输入参数对象 → 输出脚本 JSON）——几何计算（贝塞尔控制点、关键帧插值）在这里
- **输出层**：生成的脚本走现有 ScriptParser/Validator 校验，与手写脚本完全等价（可继续在编辑器里改）

### 接入点（待定）

1. 编辑器：预设面板/按钮（选预设 → 填参数 → 插入脚本）
2. 命令：`/icinematics preset <id>`（可选，服务端生成到 scripts 目录）
3. 生成位置：新建脚本文件 or 追加到当前编辑文档

### 预设库规划（初版候选）

- 环绕轨道（三段贝塞尔拼圆，用户例子）
- 直线推进/后退
- 升降镜头（直线/弧线）
- 环绕 + 变焦（希区柯克）
- 固定机位 + 呼吸
- 跟随跟拍（玩家背后第三人称）

每个预设 = 参数 schema + 生成函数，独立注册。

## 边界与待定

1. **生成结果的可编辑性**：一键生成后玩家是否继续在编辑器里微调？（倾向：是——生成的是普通脚本，不是黑盒）
2. **预设的可扩展性**：内置预设注册表 vs 支持玩家自写预设（自定义预设涉及生成逻辑，不能纯 JSON——初版只做内置）
3. **参数与"动态基准"（dynamic-yaw-reference）的关系**：环绕预设在 yaw 基准/实体相对体系落地后可以直接消费（如"相对目标视线环绕"）
4. **与触发器前置依赖的关系**：生成的脚本同样支持 requires 等字段（预设可带默认 meta）
5. UI 工作量：参数表单是主要成本（编辑器侧），生成逻辑本身简单

## 依赖与顺序建议

- 前置：脚本生成走现有 schema/validator（已具备）
- 可与 dynamic-yaw-reference（yaw_base）并行/在其后落地——环绕预设若用相对控制点（贝塞尔相对模式已实现）初版即可工作，无需等 yaw 基准

## 执行前再看 / 具体方案

- **数学参考**：
  - 用三段三次贝塞尔拟合整圆：每段 120°，控制点到端点距离 = `R * 4/3 * tan(θ/4)`；θ=120° → `4/3 * tan(30°) ≈ 0.7698R`。
  - 参考：StackOverflow “How to create circle with Bézier curves?”、Charles Petzold “Bézier Circles and Bézier Ellipses”。
- **项目文件**：
  - `script/MathUtil.java`（`cubicBezier`）
  - `script/BezierCurve.java`（每段两个控制点，支持相对/绝对）
  - `script/BezierPathStrategy.java` / `ArcLengthLUT.java`（匀速贝塞尔）
  - `script/ScriptParser.java` / `ScriptValidator.java`（生成结果校验）
  - `editor/EditorOperations.addClip`（插入生成脚本）
- **做法**：预设 = 参数 schema + Java 生成函数；输出标准脚本 JSON 后走 `ScriptParser`/`ScriptValidator`，与手写脚本等价。先做“环绕轨道”时确认现有 `BezierCurve` 是 clip 级单段（两个控制点），整圆需要用 3 个 clip 或扩展为多段。
- **执行时再看**：`BezierCurve`、`MathUtil`、`BezierPathStrategy`、`ArcLengthLUT`、`ScriptParser`、`EditorOperations.addClip`。
