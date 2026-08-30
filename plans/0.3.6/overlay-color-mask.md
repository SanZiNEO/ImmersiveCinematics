# 0.3.6 计划：完整颜色遮罩（Color Mask / Full Overlay）

## 1. 背景

0.3.5 已实现基础的全屏遮罩能力：

- `FadeLayer`：渲染全屏 ARGB 矩形
- 脚本写法：
  ```json
  {
    "layer_type": "fade",
    "color": "#000000",
    "opacity": 0.4
  }
  ```
- 黑色全屏遮罩在测试中可观察到，效果正常。

但在 `test_overlay_zindex` 中放置的红色半透明遮罩（`#FF0000` + `opacity 0.4`）没有明显看出来。

结论：

- 基础 fade 保留在 0.3.5
- **完整/更高级的颜色遮罩放到 0.3.6 做**

## 2. 0.3.6 目标

构建一套“完整颜色遮罩”能力，至少覆盖：

### 2.1 基础增强
- 全屏颜色遮罩（已有，继续保留）
- 透明度关键帧控制（已有）
- z_index 分层（已有，但需要测试更直观）

### 2.2 新增能力（讨论项）
- **渐变色遮罩**
  - 线性渐变 / 径向渐变
  - 多色 stops
- **混合模式**
  - 普通 / 正片叠底 / 屏幕 / 柔光 / 叠加等
- **局部遮罩**
  - 矩形 / 圆形区域
  - 可指定 x/y 与尺寸
- **颜色调色/滤镜**
  - LUT 或简单色彩偏移
  - 亮度和对比度控制

### 2.3 测试可视化
- 重做 `test_overlay_zindex`
- 要求一眼能看出层级：
  - 高对比色（如纯红 / 纯蓝 / 纯黄）
  - 足够不透明度
  - 不同位置 + 不同 z_index
  - 添加说明文字层

## 3. 涉及文件

- `common/.../overlay/FadeLayer.java`
- `common/.../overlay/OverlayManager.java`
- `common/.../overlay/OverlayLayer.java`
- `common/.../script/OverlayTrackPlayer.java`
- 脚本格式文档：
  - `docs/SCRIPT_FORMAT.md`
  - `docs/AI_SCRIPTING_GUIDE.md`
- 测试脚本：
  - `cinematics/tests/overlay/test_overlay_zindex.json`

## 4. 开放问题

1. 颜色遮罩是否要做渐变？
2. 是否要做混合模式？如果做，0.3.6 优先级如何？
3. 局部遮罩是否必须，还是先做全屏高级遮罩？
4. 是否保留现有脚本字段兼容？
   - 现有 `layer_type: "fade"` 是否扩展字段，还是新增 `layer_type: "color_mask"` / `"gradient"`？
5. 0.3.6 是否同时处理 WebUI 编辑器中的颜色/遮罩配置面板？

## 5. 验收标准

- [ ] 颜色遮罩能明显显示，不再依赖“仔细看”
- [ ] z_index 层级一眼可辨
- [ ] 至少支持纯色、渐变、局部区域三种形态（或按讨论范围）
- [ ] 测试脚本更新后可自动验证
- [ ] 文档同步更新
