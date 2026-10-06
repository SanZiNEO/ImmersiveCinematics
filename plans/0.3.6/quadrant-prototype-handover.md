# 四象限原型 — 交接文档

> 状态：**已完成，验收通过**（结论与多相机落地要点见 [`quadrant-prototype-results.md`](./quadrant-prototype-results.md)）。
> 本文保留为当时的交接记录：下面的现象与疑点均已在后续排查中解释（viewport 被原版重置 / 发光描边是离屏+后处理+整屏 blit / 视锥一致即收敛）。
> 分支：`proto/four-quadrant`（从 main `25d806a` 切出；**已提交**：原型 → 结论文档 → 方块内遮挡修复 → 压力测试+数据归档 → 文档按实测更新）。

## 1. 目标（用户口径）

- 验证"**我们的相机**"对**方块层 + 实体层**是否都取景正确（原版渲染是分层的；实体有自己的渲染旋转矩阵，这是用户关心的核心）。
- 验收：让四个相机**朝玩家走、始终看向玩家**；**玩家实体在四个象限里都正常出现**即通过。
- 出图：走到一半时——**每象限一张 + 游戏主图一张**。

## 2. 代码（当时状态；后续演进见本节末）

| 文件 | 说明 |
|---|---|
| `common/.../proto/QuadrantProto.java`（新） | 开关 `-Dicinematics.quadrant=true` / 环境变量 `ICINEMATICS_QUADRANT=1`（默认关，零差异）；四象限方向与 yaw（**+x→90 / −x→−90 / +z→180 / −z→0**，脚本口径见 `docs/AI_SCRIPTING_GUIDE.md` §1.4）；距离 d(t)=12↔36 往返；**朝玩家推进、越过 d=24 时触发一次出图**（预热 10s）。 |
| `common/.../mixin/QuadrantProtoMixin.java`（新） | `@Inject(method="renderLevel", at=@At("RETURN"))` on `GameRenderer`：原版单画面渲染完之后，把屏幕切 2×2；每象限：viewport+scissor → 清色深 → 设相机 → 视图 PoseStack（XP=pitch、YP=yaw+180）→ `setInverseViewRotationMatrix` → `prepareCullFrustum` → **绕过视锥 offset**（见 §4）→ `LevelRenderer.renderLevel`。四遍后出图（`Screenshot.grab` 主图 + `takeScreenshot` 裁四张）。原型期间 `renderHand=false`、`options.hideGui=true`。 |
| `common/.../mixin/CameraInvoker.java`（新） | `@Invoker` 暴露 `Camera.setPosition/setRotation`。 |
| `common/.../mixin/LevelRendererAccessor.java`（新） | `@Accessor` `prevCamRotX/prevCamRotY/needsFrustumUpdate` + `@Invoker applyFrustum(Frustum)`。 |
| `common/src/main/resources/immersive_cinematics.mixins.json` | 登记以上三个（client 列表）。 |
| `fabric/.../fabric/FabricNetwork.java`（改） | S2C 的 `ClientPlayNetworking` 注册包进 `EnvType.CLIENT` 判断——否则**专用服务端启动即崩**（Cannot load class ClientPlayNetworking in environment type SERVER）。 |

> **后续变更（本文写完之后）**：`CameraInvoker` / `LevelRendererAccessor` **已删除**；`QuadrantProto` / `QuadrantProtoMixin` **已重写**——每画面一个独立"我们模组的相机"实例、整尺寸渲染进离屏缓冲再缩放合成、描边逐画面在 lane 内合成、模式改为 `ICINEMATICS_QUADRANT=1|4|9|16|25|…`（1=只采样原版基线；≥4 取最近平方数，**不设上限**）、每次跑 30 秒出逐秒性能数据。详见 `quadrant-prototype-results.md` 与 `quadrant-perf/`。

## 3. 已确认的 MC 源码事实（1.20.1，fabric 官方名反编译源）

源码 jar（`genSources` 生成）：
`E:/Documents/GitHub/ImmersiveCinematics/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-d95c7b3016/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/minecraft-merged-d95c7b3016-1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2-sources.jar`
（已解出到 `C:\Users\yjsng\AppData\Local\Temp\mcsrc\`：GameRenderer / LevelRenderer / Camera / renderer/culling/Frustum / Screenshot）

- `GameRenderer.renderLevel(float f, long l, PoseStack poseStack)`（:1036）：`lightTexture.updateLightTexture` → 投影（`getProjectionMatrix(getFov(...))` + bobHurt/bobView/反胃）→ `camera.setup(level, cameraEntity, !firstPerson, mirrored, f)` → `poseStack.mulPose(XP, camera.getXRot())` + `mulPose(YP, camera.getYRot()+180)` → `setInverseViewRotationMatrix(...)` → `levelRenderer.prepareCullFrustum(poseStack, camera.getPosition(), getProjectionMatrix(max(d, fov)))` → `levelRenderer.renderLevel(...)`；之后才是手/HUD。
- `LevelRenderer.prepareCullFrustum(PoseStack, Vec3, Matrix4f)`（:984）：`cullingFrustum = new Frustum(poseStack.last().pose(), matrix4f); prepare(camX,camY,camZ)` —— **不调用** offset。
- `LevelRenderer.renderLevel(...)`（:996）：`entityRenderDispatcher.prepare(level, camera, ...)`；实体循环跳过条件含 **`entity == camera.getEntity() && !camera.isDetached()`**；`renderEntity(entity, x−d, y−e, z−f, yaw, partialTick, poseStack, ...)` ⇒ 实体跟随相机（视图矩阵 + 相机位置）。
- `Camera`：私有 `setPosition(double,double,double)` / `setRotation(float,float)`；`isDetached()`。
- `LevelRenderer.setupRender`（:755）内：`if (needsFrustumUpdate.compareAndSet(true,false) || m != prevCamRotX || n != prevCamRotY) applyFrustum(new Frustum(frustum).offsetToFullyIncludeCameraCube(8));`（m/n = `floor(camera.getXRot()/2)`、`floor(camera.getYRot()/2)`，字段是 **double**；`needsFrustumUpdate` 是 **AtomicBoolean**）。
- `Frustum.offsetToFullyIncludeCameraCube(int)`（culling/Frustum.java:31）：沿 `viewVector` 每次把 camX/Y/Z **后移 4 格**直到相机 8 格方块完全进视锥；**视锥平面固定** → 方向不匹配时**死循环**（实测卡死；探针 jstack 也实证过）。
- `Screenshot.grab(File gameDirectory, String name, RenderTarget, Consumer<Component>)` → 写 `<gameDir>/screenshots/<name>.png`；`Screenshot.takeScreenshot(RenderTarget)` → NativeImage（已 flipY，第 0 行 = 屏幕顶部）。

## 4. 死循环的处理方式（当前实现）

`setupRender` 里那个 `offsetToFullyIncludeCameraCube` 每象限都会走（相机旋转变了）→ 卡死。当前做法：每象限在 `renderLevel` 之前——
把 `prevCamRotX/Y` 设成该象限的值、`needsFrustumUpdate` 置 false（让 `setupRender` **跳过**该分支），再用象限自己的视锥直接 `applyFrustum(...)` 刷新可见性。
**注意**：这等于绕开了原版可见性刷新路径，可见集是否正确**未验证**（疑点之一）。

## 5. 运行方式

```sh
# 正常地形测试世界（已生成：服务端 runServer 生成后拷进 saves）
#   fabric/run/saves/QuadrantTest（来自 fabric/run/world/，正常地形；旧的"新的世界"是超平坦，用户明确不要用）
# 客户端：
ICINEMATICS_QUADRANT=1 sh gradlew :fabric:runClient --args='--quickPlaySingleplayer QuadrantTest'
```
产物：`fabric/run/quadrant-captures/quadrant-{plusX,minusX,plusZ,minusZ}.png` + `fabric/run/screenshots/quadrant-main.png`。

前置条件（都已做，注意还原）：
- `fabric/run/immersive_cinematics/scripts/` → 已改名为 `scripts.quadrant-bak`（旧脚本会干扰）。
- `fabric/run/options.txt`：`pauseOnLostFocus:false`（失焦不渲染）。
- `fabric/run/eula.txt`：为 runServer 写的。

## 6. 观察到的现象（2026-10-05 21:11 那次运行）

- 出图成功：日志 `[quadrant] captured at halfway: main + 4 quadrants (d=21.1 blocks)`；**无卡死**（死循环绕过有效）。
- 主图 `quadrant-main.png`：**上排两象限明显是不同视角**（左：草坡；右：山坡+太阳）✓；**下排两象限看起来像一张连续画面**（待判定）。
- **四个象限里都看不到"居中"的玩家实体**——验收口径（玩家在四象限里都正常出现）**未达成**。
- **天上的云是斜的**（主图可见）——"直接改原版相机"的另一条直接证据：天空/云那条矩阵链路没被正确驱动。
- 玩家进世界位置 (6.5, 131.0, −2.5)（正常地形）。

## 6.1 用户判定（原话要点）

- 测试**必须用模组的相机逻辑**（测的就是我们模组；要看的是"我们模组的相机"出图的效果）。
- 直接调原版的问题已被两条现象证明：**实体不存在** + **云是斜的**（原版有自己的旋转矩阵/状态链路，逐象限只改 `Camera` 字段驱动不了分层渲染的每一层）。

## 7. 疑点（供新对话排查，非结论）

1. **首要怀疑**：`CameraMixin` 的 `isDetached()` / `getEntity()` 覆写很可能只在 `CameraManager.INSTANCE.isActive()`（有脚本在放）时才生效。本次运行**没有脚本** → 原版逻辑生效 → `renderLevel` 的跳过条件 `entity == camera.getEntity() && !camera.isDetached()` 命中 → **本地玩家实体被跳过**（正是"看不到玩家"的直接解释）。原型要么走 mod 自己的相机激活路径，要么直接覆写 `isDetached` 为 true。
2. 四象限共用一个 `mainCamera` 实例：`camera.setup()` 一帧只在原版流程里跑一次；原型只用 `@Invoker` 改 position/rotation，`Camera` 的其它状态（`initialized`、流体/雾类型、`level`/`entity` 字段）仍是原版那份 → 可能影响雾/水下/声音等表现（截图里未明显体现）。
3. 可见性：`prevCamRotX/Y` 被强行改写 + 手动 `applyFrustum`（§4）——下排两象限"像一张连续画面"是否与此有关**未验证**。
4. 状态保存/恢复：原型只恢复 viewport/scissor；投影、雾、RenderSystem 状态未显式恢复（原版 `renderLevel` 末尾自身会收敛一部分）。
5. `Camera.setup` 未被原型调用 → `CameraMixin.onSetup`（HEAD+cancel 写 mod 相机状态）在本原型路径下**根本没走到**；即原型用的是"直接改 Camera 字段"，不是"mod 相机状态"。

## 8. 残留物清单

- 分支 `proto/four-quadrant`（6 个提交）——**已整体并入 main（`e41ccb1`），可安全删除**。
- `fabric/run/`：`saves/QuadrantTest`（新世界）、`saves/新的世界*`（旧，超平坦）、`immersive_cinematics/scripts.quadrant-bak`、`options.txt`（pauseOnLostFocus=false）、`eula.txt`、`world/`（服务端生成的源世界）、`quadrant-captures/`、`screenshots/quadrant-main.png`。
- 客户端如仍在运行：按窗口标题 `Minecraft*` 结束。
- 探针分支 `perf/second-pass-probe`（2 提交，另一个任务）——**已按"测完即删"删除**，数字留档在 `render-second-pass-cost.md` §4.2。
