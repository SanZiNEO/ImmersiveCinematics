# 0.3.3 编辑器深度优化计划

**目标版本**: 0.3.3
**创建**: 2026-06-16，更新 2026-06-18
**前置依赖**: 0.3.2 全部 10 项完成
**外部审查**: Claude ×2 + GPT ×1（2026-06-18）

---

## 背景

0.3.2 打好了编辑器骨架：⑤ 树形事件分发替代手动遍历、⑩ 参考分辨率缩放免疫 GUI Scale、② 统一了焦点接口、④⑧ 拆出了 letterbox/morph 独立逻辑。管线通了，但体验和代码清洁度不够。

0.3.3 聚焦于**编辑器代码质量**——消除硬编码、统一抽象、减少重复——为 0.4.0 多加载器架构做准备。

---

## 外部审查关键发现（Claude Code #1 — 架构）

审查确认了 2 个架构级问题：

### A1. Schema 单一真相来源 ★（最高优先）

同一套字段名在编辑器写侧和运行时读侧各自手写。`track_templates.json`（原 ③）只统一了编辑器写侧的默认值，Parser 读侧仍独立硬编码字段名——做完 ③ 依然是双源。

**升级**：模板升级为双向 schema——既驱动编辑器字段白名单/默认值，也驱动 Parser 字段校验。一份 JSON 两边读。

### 已确认的真实 Bug/代码债

| 编号 | 问题 | 位置 |
|------|------|------|
| B1 | 工具栏按钮缩放 Bug：`drawToolbar` 用 `(int)(3*sx)` 渲染，`clickToolbar` 用未缩放的 `x+3` 命中 | `TimelineArea.java` L151/L276 |
| R1 | 新建脚本引导块逐字复制 3 次（约 90 行），抽 `bootstrapNewScript()` | `EditorScreen.java` |
| R3 | `EditorOperations.recalc()` 算完 `maxEnd` 直接丢弃，纯空转 | `EditorOperations.java` |
| R4 | `LeftPanelArea.reflectFloatField()`、`addField()` 无调用方 | `LeftPanelArea.java` |
| R5 | `TimelineArea.LEFT_W` 占位常量未用、`verticalScroll` 只写不读 | `TimelineArea.java` |
| A5 | `EditorLogger` `autoFlush=true`，拖拽 clip 就磁盘同步 flush | `EditorLogger.java` |
| R6 | `LeftPanelArea` 局部用字面量 `cy += 16` 绕过缩放 | `LeftPanelArea.java` |

---

## 外部审查关键发现（Claude Code #2 — 代码整洁度）

### 面向用户的真缺陷

**E1 — 中文输入被拦截 ★**：`UITextInput.charTyped` 和 `UIAutoCompleteInput.charTyped` 用 `c < 127` 拦掉所有非 ASCII 字符。`name`/`author`/`description` 无法输入中文。

**指导**：放宽为 `!Character.isISOControl(c)`。

### 零风险速删

**C3 — 死掉的 FBO 回调机制**：`EditorBridge.setFboCallback(IntConsumer)`、`EditorBridgeImpl.notifyFbo(int)`、`PreviewArea.setFboTexture/setPlaying` 全无调用方。删除清理公共接口。

**C4 — 三个 Area 复制粘贴同一段 mouseClicked 样板**：下沉到 `UIButton.mouseClicked` 自身。

### 代码整洁度

| 编号 | 问题 |
|------|------|
| C1 | `EditorScreen.sx/sy` 全限定名刷屏 38 处 + 全局可变 `public static float`，封 `Scale.java` |
| C2/D3 | `UIDropdown` 和 `UIAutoCompleteInput` 各抄一份 Raw GL 辅助方法，改 `ctx.graphics.fill()` |
| D1 | `UITextInput` 每键提交 vs `UIFloatInput` 失焦提交，行为不一致 |
| D2 | MenuBarArea 状态栏裸数字 `205/140/80/128/72` 布局 |
| E2 | 文本框编辑能力极简（无方向键/光标/复制粘贴），非紧急 |

### 预览渲染健壮性（可用即可）

F1 `GL_CLAMP` 废弃常量 · F2 未缩放混用 · F3 双重画幅拟合 · F4 每帧无条件 blit · F5 墙钟漂移 · F6 alpha 掩码缺注释

---

## 外部审查关键发现（GPT #3 — 安全与稳定性）

GPT 在 Claude 未覆盖的安全漏洞和 GL 状态泄漏方面有补充发现。

### 安全漏洞

**S1 — `/icinematics play` 路径遍历 ★★★（高优先级）**：`Path.of(filePath)` 直接接受用户输入，无目录边界检查。`play` 无权限要求。`../../etc` 可能越权读取服务器文件，解析成功后会通过 S2C 网络包发送给所有客户端的客户端。

**修复**：加 `.requires(s -> s.hasPermission(2))` + 规范化路径边界校验 + 符号链接防护 + 文件大小上限。

### 崩溃与稳定性

**C2 — CAMERA 关键帧缺 `position` 会逐帧 NPE 日志风暴 ★★**：`ScriptParser` 允许 null，`KeyframeInterpolator` 无条件解引用。每帧异常→被 catch→下帧再抛→日志无限膨胀。

**修复**：CAMERA 解析时强制 position 必填或插值器加 null 防护。

### OpenGL 状态泄漏

**G1 — Overlay 异常后 `GL_ALWAYS` 永久泄漏 ★★**：`depthFunc` 恢复不在 finally，dropdown 异常后不恢复。

**修复**：`try { renderOverlay(); } finally { RenderSystem.depthFunc(GL_LEQUAL); }`

**G2 — PreviewCapture 修改 GL 状态不恢复 ★★**：`glBindFramebuffer`/`glColorMask`/`glClearColor` 按固定值修改不保存原状态。

**修复**：捕获前保存，finally 恢复。

### 资源泄漏

**P1 — Bezier LUT 全局无界缓存 ★★**：`BezierPathStrategy` 全局静态 `HashMap` 无上限、无清理。编辑器持续推送不同控制点时内存无限增长。

**修复**：LUT 改为脚本实例持有，脚本停止释放。

**R1 — RawInputLogger 文件句柄不关 ★**：`disable()` 不 close `PrintWriter`。

**修复**：`onClose()` 中加 `writer.close()`。

### 性能

**P3 — EditorOutput.tick() 被双源调用**：`CinematicKeyBindings` 和 `EditorScreen.render` 各调一次。

**P4 — hasActiveCameraClip 每帧被多个 Mixin 重复扫描**：缓存一次，各 Mixin 读缓存。

**T1 — CameraManager 线程约束未显式化**：加 `isSameThread()` 断言（Claude 已覆盖）。

---

## 概览

共 22 项，分 4 个阶段。

```
阶段 0 — 安全+Bug+死代码        阶段 1 — 核心重构           阶段 2 — 整洁度        阶段 3 — 收尾
──────────────────────          ───────────────              ───────────────        ───────────────
S1 路径遍历修复                  ② Timeline Widget 化 ★      C1 Scale 封装          ④ 子 Widget 相对坐标
E1 中文输入修复                     └→ UIClipWidget           D1 输入模型统一
B1 工具栏缩放 Bug                    └→ UIKeyframeDiamond     D2 菜单布局去魔法数
R1 脚本重复代码提取                  └→ UITransitionZone      C4 mouseClicked 去重
R3-R5 死代码删除                 ③ 双向 Schema 模板          E2 文本框增强（非紧急）
A5 Logger 治理                   ① 多轨道时间轴渲染
R6 缩放字面量统一                 C2 position null 防护
C3 死 FBO 回调删除               G1 overlay try/finally
F1 GL_CLAMP 修复                 G2 PreviewCapture GL 恢复
R1 RawInputLogger close          P1 Bezier LUT 生命周期
```

---

## 依赖关系

```
阶段 0（先做，独立，不改行为）
   ↓
② Timeline Widget 化
   ├── ③ 双向 Schema ──→ Widget 可借模板自省 + Parser 校验
   ├── ① 多轨道渲染 ──→ 拆出 Widget 天然支持逐行渲染
   └── ④ 相对坐标 ──→ Widget 化后坐标自然联动

多加载器架构（用户自行处理）← 以上做完后 common/ 可直接迁
```

---

## 推荐执行顺序

```
阶段 0 ── S1 · E1 · B1 · R1 · R3-R5 · A5 · R6 · C3 · F1 · R1 close（半天-1天，安全+Bug+死代码）
阶段 1 ── ② Timeline Widget 化（4-6h）→ ③ 双向 Schema（2-3h）→ ① 多轨道（1-2h）
            → C2 · G1 · G2 · P1· P3· P4（跟在重构后面，顺手）
阶段 2 ── C1 · D1 · D2 · C4 · E2（整洁度，低优先）
阶段 3 ── ④ 相对坐标（1h）
(多加载器架构)                          (独立，用户处理)
```

② 是基石。做完后 TimelineArea 从手动管线巨石变成 Widget 组装层，后续改动只涉及独立 Widget 类。
