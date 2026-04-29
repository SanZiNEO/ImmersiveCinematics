# 旧代码分析与重构决策 ✅ 分析完成，决策已验证

> **结论先行**：旧代码的核心问题已在重构中全部解决。新代码量约为旧代码的 15-20%，同时功能更强。
> 本文档作为历史参考保留，详细旧代码片段已删除（经验已融入新代码设计）。

## 旧代码架构概览

```
oldcode/
├── CinematicCameraEntity        extends LocalPlayer   ← 问题：错误的继承层级
├── CinematicManager             21K chars god class   ← 问题：职责过载
├── CinematicPath                固定路径类型枚举        ← 问题：Bezier/Orbit/Spiral等不可扩展
├── CinematicPathPoint           NBT序列化数据          ← 问题：序列化方式过重
├── CinematicCameraHandler       反射调用私有API        ← 问题：脆弱、版本敏感
├── 8个散布的Mixin               直接操作内部字段       ← 问题：耦合度高、维护困难
└── Config                       硬编码配置             ← 问题：无运行时修改能力
```

## 新旧架构对比

| 维度 | 旧代码 | 新代码 |
|------|--------|--------|
| **相机实体** | CinematicCameraEntity extends LocalPlayer | 纯POJO：CameraPath + CameraProperties |
| **管理器** | CinematicManager 21K chars 单体 | CameraManager 5.8K chars 精简桥接 |
| **路径系统** | 固定类型枚举 Bezier/Orbit/Spiral | 通用关键帧 + 可插值属性 |
| **序列化** | NBT CompoundTag | JSON（待实施，见 script_design.md） |
| **反射调用** | ObfuscationReflectionHelper 调用4个私有方法 | 0处反射，全部通过Mixin/事件 |
| **Mixin数量** | 8个散布Mixin | 2个聚焦Mixin + 2个Forge事件 |
| **Roll实现** | PoseStack Z轴旋转（渲染偏移问题） | ViewportEvent.ComputeCameraAngles |
| **HUD/手臂** | 无处理 | 白名单HUD隐藏 + 手臂/摇晃屏蔽 |
| **帧率** | 20tick/s 阶梯感 | partialTick 帧级插值 60fps |
| **镜头切换** | 无原子性保证 | 双缓冲 staged/commit 架构 |
| **代码量** | ~27文件 / ~190K chars | 8文件 / ~35K chars |

## 从旧代码提炼的设计原则

1. **绝不继承游戏内部类** — LocalPlayer/Mob 等类有大量隐式依赖，继承后需反射破解
2. **绝不反射调用私有API** — 用Mixin注入或Forge事件替代，版本升级不破裂
3. **纯数据类管理相机状态** — POJO无游戏逻辑依赖，测试/序列化/传输都更简单
4. **单一职责拆分** — CameraPath管位置、CameraProperties管朝向、CameraManager管桥接
5. **优先使用Forge事件** — ViewportEvent/RenderGuiOverlayEvent 比Mixin更安全
6. **帧级插值必须** — 20tick/s的相机位置直接使用会产生明显阶梯感

## 验证状态

- [x] 纯POJO相机方案完全可行，无Entity依赖
- [x] 2个聚焦Mixin + 2个Forge事件足以覆盖所有相机控制需求
- [x] partialTick插值消除阶梯感
- [x] 双缓冲架构支持无缝硬切换
- [x] 反射调用零处，版本升级无风险
- [x] CameraTestPlayer 18段叙事运镜验证了多段切换、roll、zoom、帧插值全部正常

---

**文档状态**：分析完成，压缩归档
**原文件**：7,312 chars / 361 lines → 压缩后约 2,200 chars
**最后更新**：2026/4/29
