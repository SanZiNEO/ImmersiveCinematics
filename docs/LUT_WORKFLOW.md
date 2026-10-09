# Immersive Cinematics — LUT 工作流（创作者向）

如何得到**适配本模组**的 `.cube`：从捕获游戏帧、在专业调色工具里调色、导出 LUT，到在脚本里引用它。
字段的完整参考见 [SCRIPT_FORMAT.md](./SCRIPT_FORMAT.md)；`.cube` 解析的行业对照与样例见 `example/lut-reference/README.md`（本地参考目录，gitignored）。

---

## 一、总原则：运行时只读只执行

- **调配在专业工具，模组只做应用**：模组运行时对 `.cube` 只做两件事——**解析**（读文件、建表）与**查表**（逐像素查表 + 插值 + 强度混合）。它不生成 LUT、不编辑 LUT，也不做任何「智能补偿」。
- **对游戏画面直接调出来的 cube，输入域天然匹配**：捕获帧就是游戏自己的输出画面，在它上面调色导出的 cube，输入域与你游戏里的画面完全一致。
- **这是解决「现成 cube 错位」的根本方法**：别人的 cube（为电影素材 / 别的游戏调的）输入域与游戏画面不一致，套上后效果容易错位——例如**远景的绿色不变黄**（该 LUT 的映射在你的画面分布上不生效）。从游戏帧出发调色、导出的 cube 不存在这个问题。
- 运行时管线（了解即可，不用改）：`v = pow(c, lut_input_gamma)`（输入域修正，缺省不改）→ 查 3D 表 → `c = mix(c, lut(c), lut_strength)`（强度混合）。

## 二、制作步骤

### 1. 捕获游戏帧

```bash
ICINEMATICS_CAPTURE=1 sh gradlew :fabric:runClient --args='--quickPlaySingleplayer QuadrantTest'
```

- 进入世界后自动播放（开发环境已放好演示脚本；按本机环境为准）。
- 产物在 `fabric/run/lane-captures/`：**`frame[-rK].png` = 窗口终帧**，即调色要导入的「原画」。首轮无后缀，之后每轮带 `-r2`、`-r3`…；挑一张构图 / 光照合适的即可。

### 2. 导入调色工具

把捕获帧导入 **DaVinci Resolve**（或同类调色工具），在它上面调色——曝光 / 对比 / 色轮 / 曲线等随便用。

### 3. 导出 .cube

调色满意后，用工具里的 **Generate 3D LUT**（导出 3D LUT）功能导出 `.cube` 文件：

- **33³ 最常用**（本仓库参考样例即 33³ / 16³ 两种）；达芬奇等主流工具的导出均兼容本模组解析。

### 4. 放入资源目录

把 `.cube` 放进资源目录：

```
fabric/run/immersive_cinematics/resource/
```

（正式安装 = `<游戏目录>/immersive_cinematics/resource/`。）文件名就是脚本里的引用名，起个能认出来的名字。

### 5. 在脚本里引用（ADJUST 轨）

LUT 是 **master 级**处理，只写在 **ADJUST 轨道**的片段上（作用于整个世界画面）；相机片段上写 `lut` 无效（会被校验拦下）。

```json
{
  "type": "ADJUST",
  "clips": [
    {
      "start_time": 6,
      "duration": 6,
      "lut": "Teal and Orange.cube",
      "lut_input_gamma": 1.0,
      "keyframes": [
        { "time": 6, "lut_strength": 1 },
        { "time": 12, "lut_strength": 1 }
      ]
    }
  ]
}
```

三个字段（语义务必记准）：

| 字段 | 层级 | 类型 | 缺省 | 说明 |
|------|------|------|------|------|
| `lut` | clip 级 | string | 不写 = 无 LUT | `resource/` 目录下的 `.cube` **文件名**（不是路径） |
| `lut_strength` | 关键帧级 | float | `1` | 混合强度 `0~1`；可打关键帧，写回 `0` = 该 LUT 淡出 |
| `lut_input_gamma` | clip 级 | float | `1.0` | 输入域修正；**必须 > 0**；查表前 `v = pow(c, gamma)` |

- **`lut_strength`**：`0` = 完全不生效、`1` = 全量生效，中间值 = 按比例混合；缺省 `1`（写了 `lut` 就是全量生效）。做「渐入渐出」就在关键帧上拉它。
- **`lut_input_gamma`**：给「现成 cube」准备的纠偏口。现成 cube 套上后**整体偏亮 / 偏色**时，试 `0.45` 或 `2.2` 两个方向，取观感正确的一边——本质是给 LUT 的输入补一个指数补偿。自制的（从游戏帧调的）cube 一般保持缺省 `1.0` 不用动。

## 三、注意事项

1. **商业版权 LUT 不入库**：仓库只带解析代码与参考样例，不含商业 LUT。本地参考素材来源 `E:\BaiduNetdiskDownload\THE LUT BUNDLE`——换机器 / 重装后需从该处重新复制到 `fabric/run/immersive_cinematics/resource/`。
2. **文件形式**：只接受 `.cube`（扩展名大小写不敏感，`.cube` / `.CUBE` 均可）；文件名**不能含路径分隔符**（`/` `\` `:`）——只能放在 `resource/` 下，不存在路径穿越。
3. **只动 RGB**：调色只作用于颜色通道，alpha 直通——透明度由合成层的 `opacity` 管，和 LUT 无关。
4. **不影响 GUI**：LUT 作用于世界画面（master 级，合成之后、GUI 之前）；黑边 / 字幕 / 跳过提示等 GUI 层内容不会被套色。
5. **解析对照**：`.cube` 解析（含 Resolve 变体、DOMAIN、1D shaper + 3D 组合）的开源实现对照与样例在 `example/lut-reference/README.md`（本地 gitignored，不进构建）。

## 四、微调的两条路

调 LUT 效果有两条路：

1. **外部工具调 cube（本文档路线）**——回到调色工具改，重新导出 `.cube` 覆盖旧文件（或换个文件名）。这是行业主流流程：捕获参考帧 → 调色 → 导出 LUT → 引擎应用（Unity HDRP 等同类管线都是这个路子）。
2. **编辑器阶段的 LUT 微调工具（规划中）**——按「调配归编辑器」的分工，曲线手柄 / 模板 / 预设这类调色微调都放在编辑器阶段做，排在最后。在它落地之前，用外部工具即可。
