# 0.3.6 多相机渲染（PIP / 分屏 / 叠化）方案

**状态**: 📋 方案，未实现
**目标版本**: 0.3.6
**最后更新**: 2026-08-21

> 本文讨论“同一时刻渲染多个相机画面”以及“叠化（dissolve）”功能。
> 0.3.5 不做此功能；0.3.6 专门攻克。

---

## 1. 需求定义

### 1.1 PIP / 分屏

- 不是“带边框的小窗 UI”
- 而是：同一帧内渲染多个相机画面并合成到屏幕
- 例如：左侧显示相机 1，右侧显示相机 2
- 类似监视器墙 / 分屏监控

### 1.2 叠化（Dissolve / Crossfade）

- 不是 morph（相机状态平滑过渡）
- 而是：过渡期内两张画面互相淡化叠加
- 上一个画面 alpha 从 1 → 0，下一个画面 alpha 从 0 → 1
- 剪辑软件里的“叠化”效果

---

## 2. 底层能力

两者共用同一个底层能力：

> **多相机同时渲染到多个 framebuffer，再合成到屏幕。**

- PIP / 分屏 = 每帧渲染 N 个相机，按布局合成
- 叠化 = 过渡期渲染 2 个相机，按 alpha 合成

---

## 3. 技术路线

### 3.1 不是自己搭渲染器

- 复用原版世界渲染管线：`GameRenderer.renderLevel` / `LevelRenderer.renderLevel`
- 用第二个 `Camera` 再调用一次原版世界渲染
- 输出到独立的 `RenderTarget`（FBO）
- 最后把多张纹理合成到屏幕

### 3.2 每帧流程（双相机）

```text
1. 准备相机 A、相机 B（位置/朝向来自脚本路径）
2. 渲染 A → FBO_A
3. 渲染 B → FBO_B
4. 恢复主 framebuffer
5. 合成：
   - 分屏：左右各画一张
   - 叠化：A 画完，B 按 alpha 叠加
6. 继续渲染 GUI / 主画面
```

### 3.3 叠化提前量（预热）

问题：
- 叠化开始那一刻，A 和 B 的画面必须同时存在
- B 不能到叠化开始才第一次渲染，否则第一帧没准备好

解决：
- 在叠化开始前提前渲染 B（预热）
- 预热开始时间 = 叠化开始时间 - 预热时长
- 因为脚本时间线已知，可以精确计算

注意：
- 不是渲染“未来的世界”——世界是动态的，未来帧不存在
- 是提前让 B 的 framebuffer 进入有效状态（warm-up）

---

## 4. 关键难点

| 难点 | 说明 |
|---|---|
| 全局状态切换/恢复 | 当前 framebuffer、Camera、投影/视图矩阵、视口、雾、着色器、深度缓冲、frustum |
| 第二个 Camera | 需要独立 Camera 实例，位置/朝向来自我们的路径 |
| 第二个 Frustum | 副相机要有自己的视锥体，否则裁剪错误 |
| 副画面内容 | 通常只要世界，不要第一人称手/HUD |
| 渲染顺序 | 第二遍在主世界渲染之后、GUI 之前，还是先渲染第二遍再合成，需要定 |
| 光影/模组兼容 | 很多模组假设每帧只调一次 `renderLevel`，二次调用可能冲突 |
| 性能 | 两次世界渲染 ≈ 成本翻倍；副画面可降低分辨率 |

---

## 5. 脚本 / 数据模型草案（待定）

### 5.1 多相机

```json
{
  "type": "CAMERA",
  "clips": [
    { "camera_id": "cam_a", "start_time": 0, "duration": 10, "keyframes": [...] },
    { "camera_id": "cam_b", "start_time": 0, "duration": 10, "keyframes": [...] }
  ]
}
```

- 同一时间多条 CAMERA 轨道/多 camera_id 同时存在
- 由“合成布局”决定怎么显示

### 5.2 合成布局

```json
{
  "layout": {
    "mode": "split",
    "split": "left_right",
    "cameras": ["cam_a", "cam_b"]
  }
}
```

或叠化：

```json
{
  "layout": {
    "mode": "dissolve",
    "from": "cam_a",
    "to": "cam_b",
    "duration": 1.0,
    "prewarm": 0.2
  }
}
```

> 具体数据模型 0.3.6 再定，本文只记录方向。

---

## 6. 性能策略

- 副画面使用低分辨率 RenderTarget（如主画面 1/4）
- 叠化只在过渡期开双渲染，平时单渲染
- 分屏模式长期双渲染，必须做分辨率/性能档位
- 可选项：副画面跳过实体渲染 / 降低视距 / 关闭粒子

---

## 7. 兼容性风险

- 光影（Iris/Oculus）：
  - 它们自己会做多次渲染，说明多次渲染可行
  - 但第三方二次调用可能绕过光影的合成，或触发重复特效
  - 需要专门调研：能否在光影环境下拿到“已处理后的画面”
- 性能模组（Sodium）：
  - 渲染状态管理更严格，二次调用前必须确认状态保存/恢复方式

---

## 8. 实施步骤（0.3.6）

1. 调研：原版 `GameRenderer.renderLevel` 可重入性、状态保存/恢复
2. 原型：单机双 RenderTarget + 二次渲染，先不做光影兼容
3. 合成：分屏布局
4. 叠化：预热 + alpha 混合
5. 数据模型：多 camera_id + layout
6. 兼容：Iris/Oculus/Sodium 实测
7. 性能：低分辨率副画面 + 帧耗时统计
8. 文档：SCRIPT_FORMAT / AI_SCRIPTING_GUIDE

---

## 9. 开放问题

- 多相机是否走“多条 CAMERA 轨道”还是“单轨道多 camera_id”？
- 叠化是否只支持两相机，还是 N 相机？
- 副画面是否需要支持 look_at / tangent 朝向？
- 分屏是否要支持任意布局（左/右/上/下/画中画小窗）？
- 是否允许叠化期间主相机继续移动？
- 预热时长是固定值还是脚本可配？

---

## 10. 结论

- 0.3.5 不做
- 0.3.6 作为核心功能攻克
- 先做原型验证二次渲染，再谈数据模型和 UI

---

## 11. 参考：Sodium / Rubidium / Embeddium 源码

0.3.5 已验证一个重要事实：

- Sodium / Rubidium / Embeddium 的渲染中心来自 `Camera` / `Frustum`。
- 我们通过 `CameraMixin` 把虚拟相机写进 `Camera`，它们的 `Viewport` / `setupTerrain` 就会自动以虚拟相机为中心渲染。
- 因此 0.3.6 做 PIP / 分屏 / 叠化时，可以优先考虑“复用它们的 Camera/Frustum 管线”，而不是自己再硬改 `LevelRenderer.setupRender`。

参考源码位置：

- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/world/WorldRendererMixin.java`
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/client/render/viewport/Viewport.java`
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/frustum/FrustumMixin.java`
- `example/embeddium-20.1-forge/src/main/java/me/jellysquid/mods/sodium/client/render/SodiumWorldRenderer.java`

后续做副相机 / 多 RenderTarget / 光影兼容调研时，这些实现可以作为重要参考。
