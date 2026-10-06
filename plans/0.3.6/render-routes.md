# 渲染子路线（临时草稿）

> **临时文档**：内容来自 `mod-architecture-diagram.md` 的讨论（渲染节点往外连的路线）。
> 稳定后并入 `multi-camera-rendering.md`。

---

## 1. 根：主画面 vs 副画面

- **主画面**：原版渲染管线（主相机）→ 直接进主 framebuffer；
- **副画面**：第二个 `Camera` 再调一遍世界渲染 → 独立 FBO；**N 个相机 = N 遍**。

## 2. 副画面内部的分支

- **内容**：只走区块层（solid / cutout / cutoutMipped / translucent）；实体 / 粒子 / 天空 / 天气可选（默认关）；不带第一人称手 / HUD；
- **视锥**：独立（副相机自己的 Frustum → 窄视锥天然剔除）；只要视锥与该 lane 的相机姿态一致（`prepareCullFrustum(同一 pose, 相机位置, 投影)`），原版 `offsetToFullyIncludeCameraCube` 3~5 步收敛，**不需要自建剔除方案**（实机验证，见 `quadrant-prototype-results.md` §3.4）；
- **状态**：保存 / 恢复（framebuffer、Camera、投影 / 视图矩阵、视口、雾、着色器、深度缓冲、frustum）。原型实测另补三条：① 渲染期间把 `Minecraft.mainRenderTarget` 指向该 lane 的 FBO（原版内部会把 GL viewport 重置成整窗）；② **lane 自包含**——lane 级后处理（发光描边等）必须在 lane 的 FBO 内完成；③ `doEntityOutline() → blitToScreen()` 会改全局投影矩阵，调用后要还原（见 `quadrant-prototype-results.md` §3.1–3.3）。

## 3. 性能档位

- **分辨率：正式合成走原分辨率**（与主画面同分辨率，靠取材区域裁切再铺屏）；**低分辨率只用于编辑器预览传输**（省带宽）；
- 档位 = 内容开关 / 视距 / 状态复用等（**不降分辨率**）；
- 叠化只在过渡期双渲染；分屏长期双渲染——档位在这层。

## 4. 兼容分支

- **光影（Iris / Oculus）**：能否拿"已处理后的画面"；
- **性能模组（Sodium / Embeddium）**：检测到 → 副画面禁用 + warn；或走它们的 Camera / Frustum 管线（`CameraMixin` 路线）。

## 5. 待定

- ~~渲染顺序：第二遍在主渲染之后 / GUI 之前？~~ **已实测可定**：挂在 `GameRenderer.renderLevel` 的 RETURN（主渲染之后、GUI 之前），原型 4 / 16 / 25 画面均正常（见 `quadrant-prototype-results.md`）。
