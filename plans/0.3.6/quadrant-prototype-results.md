# 四象限原型 — 结论（多相机渲染底层验证）

**状态**: ✅ 验收通过（2026-10-05）
**分支**: `proto/four-quadrant`（提交 `89cd6cb`）
**原型代码**: 一次性，测完即删（清单见 §7）
**相关**: [交接文档](./quadrant-prototype-handover.md)（旧状态/背景）· [多相机渲染](./multi-camera-rendering.md) · [渲染子路线](./render-routes.md) · [画面合成](./camera-composition.md)

---

## 1. 目标与验收

目标：验证"**我们的相机**"对**方块层 + 实体层**是否都取景正确（原版渲染是分层的，实体有自己的渲染旋转矩阵）。

做法：2×2 四象限，每象限一个相机从 +x / −x / +z / −z **朝玩家推进、始终看向玩家**；半程（d=24）自动出图（每象限一张 + 主图一张）。

| 验收项 | 结果 |
|---|---|
| 每象限"整尺寸渲染 → 50% 缩放填充" | ✅ 四个象限各是一张完整画面 |
| 象限布局（右上 +x / 左上 −x / 左下 +z / 右下 −z） | ✅ |
| 玩家实体四象限可见、朝向正确（+z 正脸 / −z 后脑勺） | ✅ |
| 其他实体（牛/猪/羊/鸡/马/僵尸/骷髅/苦力怕/蜘蛛/末影人/女巫/盔甲架/掉落物） | ✅ |
| 发光（Glowing）描边逐象限绑定、跟着一起缩放 | ✅ |
| 云/天空跟随各自象限相机（不歪斜） | ✅ |
| 稳定：无死循环、半程出图成功 | ✅ |
| 相机穿进实心方块：稳定透视（不闪、光照正常，见 §3.5） | ✅ |

---

## 2. 原型实现（要点）

- **每象限一套独立的"我们模组的相机"实例**：原版 `Camera` + `CameraPath`（位置）+ `CameraProperties`（yaw/pitch/roll/fov/zoom）
- **接管走模组自己的路径**：`Camera.setup()` → `CameraMixin` 原型分支（写位置/朝向 + `initialized/level/entity/detached`，并覆写 `isDetached`/`getEntity`）；fov/zoom 走 `GameRendererMixin.getFov` 原型分支
- **渲染**：每象限**整尺寸**渲染进自己的 `RenderTarget`（`TextureTarget`，与主画面同尺寸），渲染期间把 `Minecraft.mainRenderTarget` 临时指向该 FBO（`MinecraftAccessor`），结束后按 **50% 缩放** `glBlitFramebuffer` 贴进象限
- **出图**：`Screenshot.grab` 主图 + `takeScreenshot` 裁四张 → `fabric/run/quadrant-captures/`
- **开关**：`-Dicinematics.quadrant=true` / `ICINEMATICS_QUADRANT=1`（默认关，零差异）

---

## 3. 五个坑与结论（0.3.6 必读）

### 3.1 viewport 会被原版内部重置 —— 不能"直接设象限 viewport"

`LevelRenderer.renderLevel` 实体段前有：`entityTarget.clear()` → `bindWrite(true)` → **把 GL viewport 重置成整窗尺寸**；随后 `getMainRenderTarget().bindWrite(false)` **不回滚 viewport**。

→ 直接给象限设 viewport 会被冲掉：地形正常，但**实体/粒子/云/天气按整屏尺寸绘制**，再被 scissor 切成四块（表现为"玩家出现在窗口正中、被四个象限各切一块"）。

**结论**：多画面不要依赖"设置 viewport"，走 **每 lane 一个 RenderTarget：整尺寸渲染进 FBO，再缩放上屏**（= `render-routes.md` 的路线）。

**落地细节**：渲染期间把 `Minecraft.mainRenderTarget` 指向该 lane 的 FBO（`@Accessor @Mutable` 改 `mainRenderTarget`），原版所有 `getMainRenderTarget()` 引用才会落在 lane 上——否则 `entityTarget.clear()` 之后的回绑会跳回真主画面。

### 3.2 发光描边 = 离屏 + 后处理 + 最后整屏 blit —— 必须"lane 自包含"

链路（1.20.1）：`OutlineBufferSource`（剪影 + 队伍色）→ **共享** `entityTarget` → `entity_outline` 后处理链（`entity_outline → blur H → blur V → blit`）→ `GameRenderer.render` 里 **`renderLevel` 返回之后**的 `doEntityOutline()` **整屏 1:1** 贴到主画面。

→ 多画面时：象限里永远看不到发光（描边进不了 lane 的画面），最后**只剩一份**（共享 target 被逐 lane 覆盖）以**整屏未缩放**的形式盖在合成图外面（看着像"某个机位的剪影"，且随该机位远近变化）。

**结论（lane 自包含原则）**：lane 的 `renderLevel` **加上所有 lane 级后处理**都必须在 lane 的 FBO 内完成，再缩放上屏。

**落地**：每 lane `renderLevel` 之后（FBO 还绑着）立刻调 `doEntityOutline()`；并**屏蔽原版**在 `renderLevel` 之后那次整屏调用（用"lane 渲染中"标志区分，避免误伤自己那次）。

### 3.3 `blitToScreen` 会改全局投影 —— 调用后要还原

`doEntityOutline()` → `entityTarget.blitToScreen(...)` → `RenderSystem.setProjectionMatrix(ortho, ORTHOGRAPHIC_Z)`（**全局**，且不还原）。

**结论**：lane 内调用后必须 `RenderSystem.setProjectionMatrix(laneProjection, VertexSorting.DISTANCE_TO_ORIGIN)` 还原，否则**下一个 lane** 里所有走全局矩阵的绘制（实体/粒子/方块实体，经 `BufferUploader.drawWithShader(RenderSystem.getModelViewMatrix(), getProjectionMatrix(), ...)`）会坏。

（地形/天空/云不受影响：它们走显式矩阵设置 uniform。）

### 3.4 视锥 `offsetToFullyIncludeCameraCube` 的终止条件（顺带澄清）

- 语义：循环把**参考点**沿 `viewVector` 每次移 4 格，直到"相机 8 格方块完全进入视锥"（JOML：`INSIDE=-2 / INTERSECT=-1 / OUTSIDE=-3`，循环条件 `!= -2`）；**方块坐标在循环外算一次**，循环内只有参考点变。
- 只要视锥与相机姿态**一致**（`prepareCullFrustum(同一 pose, 相机位置, 投影)`），四象限全部机位 **3~5 步收敛**（JOML 语义离线模拟 + 实机验证）。
- 之前的死循环来自"视锥与相机不一致"（方块永远进不去），**不是这条路径本身必然死锁**。
- **结论**：不需要绕过原版可见性刷新（不需要改 `prevCamRotX/Y` / `needsFrustumUpdate`），也就保住了原版的 8 格膨胀剔除行为。

### 3.5 相机穿进实心方块 → 可见区块塌缩/闪烁（遮挡剔除）

**现象**：相机自由穿墙、进到实心方块里之后，地形/空腔"一会儿有一会儿没有"（原型里表现为同一象限在"只剩天空"和"整条隧道都在"两张图之间来回切）。

**原版逻辑**（`LevelRenderer.setupRender`）：

```java
boolean bl3 = this.minecraft.smartCull;
if (player.isSpectator() && level.getBlockState(camera.getBlockPosition()).isSolidRender(level, pos)) {
    bl3 = false;   // 旁观者在实心方块里 → 关掉遮挡剔除
}
```

- 判定用的是 **`camera.getBlockPosition()`**（原版相机就是玩家眼睛），但**只有旁观者**才允许走这条；
- 旁观者另有 `noPhysics = true` → `ScreenEffectRenderer` 的"方块内遮罩"被跳过 → 得到"**稳定透视 + 正常光照**"的观感；
- 我们的相机自由飞行、可穿墙，语义上等价于旁观者，所以套用同一条件。
- **反面教材**：`ScreenEffectRenderer.renderScreenEffect` 里那条 `getViewBlockingState(player)` + `renderTex(..., 0.1 亮度)` 是"**玩家被方块掩埋**"的黑效果（整屏压暗），不要拿它做"相机在方块里"。

**为什么逐 pass 判定会闪（本原型踩的坑）**：可见区块集合（`renderChunkStorage`）是 `LevelRenderer` 上的**共享状态**，一帧里原型要渲染 5 个 pass（主 pass + 4 lane）。各 pass 用不同的 `smartCull` 值时会互相打架：

- 整帧重建（`needsFullRenderChunkUpdate`，通常由本帧第一个 pass 消费）用 `true` → 集合**塌缩**；
- lane pass 用 `false` → 局部更新（区块编译完成触发）不做遮挡过滤 → 集合**一圈圈涨回来**；
- 两者交替 → 画面在"塌缩 / 完整"之间来回闪。

**修复（本次，按帧统一）**：

| 文件 | 做法 |
|---|---|
| `camera/CinematicOcclusion.java`（新） | `beginFrame()`：**每帧只决定一次**"我们的相机（原型＝任一 lane 相机）是否在实心方块里"；状态切换时 `levelRenderer.needsUpdate()` 强制一次重建 |
| `GameRendererMixin` | 挂 `render(float,long,boolean)` 的 HEAD → 每帧调一次 `beginFrame()` |
| `LevelRendererMixin` | `setupRender` HEAD/RETURN 包夹：本帧决定"关"时，这次调用里 `smartCull = false`（**整帧所有 pass 同一个值**） |

**长期方向（0.3.6）**：真正的多相机应当**每个 lane/相机独立管理自己的可见集合与剔除状态**（各自的 `renderChunkStorage` + BFS + frustum），互不干扰——那时各 lane 才允许有自己的遮挡行为；本原型是"整帧统一"的过渡方案（因为共享状态必须帧内一致）。

**简单验证方法**：让相机穿进地形里来回飞，看画面是否**稳定停在"完整透视"**（不再在"只剩天空 / 整条隧道都在"之间切）；F3 调试屏里 `C: x/y (s)` 的 `(s)` 表示 `smartCull` 处于开启状态，可用来确认本帧的开关。

---

## 4. 运行方式

```sh
ICINEMATICS_QUADRANT=1 sh gradlew :fabric:runClient --args='--quickPlaySingleplayer QuadrantTest'
```

产物：`fabric/run/quadrant-captures/quadrant-{plusX,minusX,plusZ,minusZ}.png` + `fabric/run/screenshots/quadrant-main.png`

前置：`fabric/run/immersive_cinematics/scripts` 已改名 `scripts.quadrant-bak`（旧脚本会干扰）；`options.txt` 里 `pauseOnLostFocus:false`。

---

## 5. 世界副作用与清理

原型启动时（临时逻辑）会：把玩家切创造模式 + 在玩家周围 summon 13 种实体 + 一个 1024 血发光骷髅；并对所有骷髅 `effect give glowing`。

→ 多次运行会在存档里累积实体/发光。清理：`/kill @e[type=!player]`（或删 `fabric/run/saves/QuadrantTest`）。

---

## 6. 与 0.3.6 的衔接

- **渲染底层路线已验证**：每 lane 一个 RenderTarget + 缩放合成可行（`render-routes.md` §1–2）。
- **正式实现必须带上的三条**：lane 自包含（§3.2）、lane 内全局状态还原（§3.3）、lane 期间主画面指向（§3.1）。
- **多相机需要每 lane 独立的状态（长期方向）**：可见集合 / 遮挡剔除（`renderChunkStorage` + BFS + frustum）目前是 `LevelRenderer` 上的共享状态；原型用"整帧统一决定"过渡（§3.5），真正的多相机要按 lane 各自维护，才允许各 lane 有自己的遮挡行为。
- **仍未覆盖**：Sodium/Embeddium/Iris 兼容（本原型纯原版管线）；`postEffect` 等其他"renderLevel 之后"的整屏步骤（同类风险）；性能（4 遍整尺寸渲染 + 4 次 blit 未测）。
- **0.3.6 排查清单（"这一步属于哪个 lane？"）**：`doEntityOutline` / `postEffect` / `tryTakeScreenshotIfNeeded` / `Minecraft` 最后的 `blitToScreen`。

---

## 7. 原型文件清单（删除原型时按此清理）

- `common/.../proto/QuadrantProto.java`（含临时 summon 逻辑）
- `common/.../mixin/QuadrantProtoMixin.java`
- `common/.../mixin/MinecraftAccessor.java`
- `CameraMixin` / `GameRendererMixin` / `LevelRendererMixin` 里的**原型分支**（带 🧪 注释标记）
- `immersive_cinematics.mixins.json` 里的对应条目
- **保留**：`FabricNetwork` 的 `EnvType.CLIENT` 修复（与原型无关——专用服务端启动崩溃的修复）
