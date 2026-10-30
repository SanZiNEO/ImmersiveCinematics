# D 轮底座收口：执行计划（2026-10-17）

> **历史说明（2026-10-17）**：本文记录 D8 / D10 收口轮；文中 §1 / §5 的“D1–D3 未做 / 仍开放”是**当时状态**。D1–D7 均已在同日后续轮次完成：D1–D3 见 [scene-transition.md](scene-transition.md) §9，D4–D7 见 [color-mask-execution.md](color-mask-execution.md)。本文不删除历史，也不把后续轮次的验收混入本轮。
>
> **当前状态（2026-10-18 回写）**：D 轮已关单——D1–D8 / D10 于 2026-10-17 完成。本文只是 D8 / D10 的历史执行记录；现行仍开放的只有验收边界（Forge 未实机、Iris / Oculus 未实机）、§5 末条（斜线 / 星形 wipe 与跨实例层序不在本版）以及 E / F / G / H / P1 / P3 未关单，均不是 D 轮待办。

## 1. 版本现状与本轮选择

ABC 主体已完成，不重开既有实现。C 的事实追踪 / 连续语义（P1-impl / P3-impl）仍是独立底层升级；最近 C-pause 尚缺实机复验。**当时** D–H 与 Z 轮仍有工作，不能把 ABC 完成理解为 0.3.6 全版完成；D 轮的 D1–D3 已在同日后续收口（见文首历史说明）。

本轮选择 **D8 核实收口 + D10 LETTERBOX 透明度**，不是一次把整个 D 批次标成完成：

- D8：画面 lane 与现有覆盖层已经共享取材 / 放置参照，保留两族不同的素材来源与执行路径，不为统一而强行把世界渲染搬进 GUI 层。统一几何重写已于 2026-10-10 提前落地。
- 字幕：完整呈现、不裁切，不消费 `source`；`source` 仍用于图片与相机 lane，`fit` 不恢复。
- D10：LETTERBOX 轨接通 `opacity`；移除该播放器隐含的 smoothstep，使 `aspect_ratio` 与 `opacity` 均按脚本关键帧线性播放。
- 几何裁决一致性：去掉 `base_resolution` 的 16384 人为上限；保留当前正整数存储类型的可表示边界，解析和校验一致，不允许溢出截断。ScriptParser 已正确支持该范围，实际只修校验器。

选择依据：README 通用原则“先底层后应用”；`variable-frame.md` §3.1 / §4.2 / §9 / §11（字幕不取材）；`scene-transition.md` §2 LETTERBOX 缺口；`G-script-model/script-model.md` §4-1 运行时线性、缓动归编辑器；`consolidation/decisions.md` 第 3 项“不设上限”。

## 2. 调研前置（已完成）

编码前由两路只读子代理完成调查：

1. MC 1.20.1 GuiGraphics / Font / scissor / GUI 缩放与 framebuffer 坐标，以及渲染混合状态契约。
2. `example/` 中 Olive / ReplayMod / BBS / 相关模组的取材、关键帧、遮罩与混合机制，区分数据创作与运行时播放。

长期源码依据：Forge `1.20.1-47.4.16_mapped_official_1.20.1-sources.jar` 的 `GuiGraphics.java`、`Font.java`、`Window.java`、`RenderTarget.java`；Olive 的 `app/node/distort/crop/`、`app/node/distort/transform/`、`app/shaders/`；BBS 的 `particles/components/appearance/colors/Gradient.java`、`ui/framework/elements/utils/Batcher2D.java`。调查摘要已并入本节结论。

## 3. 小工作包与接口

按完整、独立可验收的功能切片派单，不机械拆成几行一单，也不把无关子系统塞进同一任务。任务书一次写全实现、接线、校验、样例及边界。主代理负责统筹、需求裁决、文档、实际验证与 Git；执行期间编码代理不构建、不提交。独立切片可批量派发，核心共享文件明确唯一写入者。

| 工作包 | 单一目标 | 边界 | 输出契约 |
|---|---|---|---|
| D8-a | 分辨率裁决一致性 | ScriptValidator 的 base_resolution 判断 | 大于 16384 的可表示正整数可用；非法 / 溢出拒绝；不改 ScriptMeta 类型 |
| D10-a | 黑边透明度与线性求值 | LetterboxTrackPlayer / TrackSchemas.letterbox / ScriptValidator、一份演示脚本 | opacity 缺省 1、范围 0~1；区间内线性、区间外保持端点；空档清黑边；无限片段时间正常推进 |
| D10 验收资产 | 实际播放器数值与生命周期探针 | 仅仓库外 Java / shell 文件 | 用真实解析器与播放器验证，不用 mock 回显，不入库 |

撤回包：字幕渲染与字幕接线 / 样例，均由原编码代理精准撤回自己的改动，不动 D10 代码或其他已有功能。

## 4. 验收

1. `sh gradlew compileJava`：common / Fabric / Forge 编译。
2. 无头真实校验：缺省、合法大分辨率、正整数边界、零负小数 / 溢出；既有语料的历史两条问题不作为新缺陷。
3. 黑边探针：t=0.25 的线性值、首尾保持、单关键帧、缺省 opacity、跨片段不继承旧透明度、空档清理、无限片段推进、停止只移除自己层。
4. 实际游戏画面：opacity 0 / 中间值 / 1、默认值、画幅比线性变化、左右黑边与退出清理；不只看 schema 字段存在。
5. 字幕撤回范围确认，不声称完成不存在需求的裁切能力。更新格式文档、模块说明、changelog 和版本看板后本地提交，不推送。

## 5. 后续仍开放（历史：本轮时未做；D1–D7 已在同日后续轮次完成）

> 本节前三条为历史待办，均已在同日后续轮次关闭；后两条是**现行开放项**（斜线 / 星形 wipe 与跨实例层序不在本版；E / F / G / H / P1 / P3 未关单），不属 D 轮待办。

- D4–D6：fade 工具的渐变 / 局部遮罩 / 混合增强。soft_light / overlay 需要 backdrop 采样与独立着色器流程，不能靠更改 GL 混合因子替代。**（历史；已完成，见 `color-mask-execution.md`）**
- D1–D3：遵循 `scene-transition.md` §3.2 的“纯数据调用、无独立转场轨/字段/代码”；§4 旧候选的冲突需按运行时只执行原则收口。**（历史；已完成，见 `scene-transition.md` §9）**
- D7：旧 z-index fixture 同轨同窗首片段使后片段不可选，不能用于证明 fade 颜色失效；应改成独立 OVERLAY 轨的高对比层级实验。**（历史；已完成，见 `color-mask-execution.md`）**
- 高级形状 wipe、跨实例整体视觉层序等不能用修改默认 z 值替代底层语义。**（斜线 / 星形 wipe 仍不在本版；跨实例层序不在本轮）**
- E（模板/生成）和编辑器仍按版本约定放最后；F/G/H/P1/P3 不混入本轮。**（仍适用：这些未关单）**

## 6. 实际验收记录（已完成）

- **编译**：在线 `compileJava` 在解析 Sponge snapshot 时 TLS 握手失败；改用 `sh gradlew --offline compileJava`，common / Fabric / Forge 均通过。未改项目网络或证书配置。启动客户端时 Forge 插件仍做联网证书预检，在 `--offline` 下使用单次 `-Dnet.minecraftforge.gradle.check.certs=false` 跳过预检，未下载新依赖。
- **真实播放器探针**：`ScriptParser → LetterboxTrackPlayer → LetterboxLayer / OverlayManager`，19 项断言。旧编译产物 10 项失败，修后全部通过；包含 1/4 与 3/4 时刻线性值、首尾保持、单帧 / 缺省 opacity、跨片段不继承、空档、无限片段推进与多实例停止隔离。探针在仓库外，不作为永久测试入库。
- **校验器**：20000（修前被拒）及 `{w:2147483647,h:65536}` 通过；2147483648、0、负数、缺分量、小数按预期拒绝；新 LETTERBOX opacity 非数字 / 超 0~1 各报一处错误。
- **语料**：tests + release 共 165 支脚本，仅原有 2 个脚本未通过：`test_camera_facing_origin_coordinate`（id 过长）、`test_camera_relative_axis`（yaw 缺失）。本轮新增样例通过，未顺手修改历史语料。
- **实机**：Fabric，用户已有世界“新的世界”，脚本手动命令播放。第一轮仅前三张 F2 成功（随后窗口失焦），第二轮改为给 Minecraft 窗口定向发送 F2，完整捕获九个时刻。用户补充四张截图，分别显示 P2 / P4 / P6 / P7，与脚本阶段一致。

2560×1392 framebuffer 的直接截图测量（背景 RGB = 128,160,192；采样避开说明字幕）：

| 阶段 | 截图（`fabric/run/screenshots/2026-10-17_*.png`） | 实测 |
|---|---|---|
| 缺省 opacity=1 | `09.30.19` | 上下黑边 RGB 0,0,0，高 152px；中间背景不变 |
| opacity=0 | `09.30.25` | 边缘与中心均为背景色，无黑边 |
| opacity=0.5 | `09.30.31` | 黑边 RGB 64,80,96，符合 alpha 混合 |
| opacity 线性动画约 t=26 | `09.30.42` | 黑边 RGB 95,119,142；实际截图略晚于目标时刻，不将它冒充严格 t=26 数值断言 |
| 画幅比线性变化约 t=35 | `09.30.51` | 上下黑边高 188px；精确线性数值由独立播放器探针覆盖 |
| aspect_ratio=1.0 | `09.31.03` | 左右黑边各 584px，等于 `(2560−1392)/2` |
| 片段空档 | `09.31.08` | 边缘与中心均恢复背景色 |
| 最后有限片段 | `09.31.14` | 左右黑边各 284px，画幅比继续变化 |
| 内容结束后 | `09.31.19` | 背景 / 黑边 / 说明层移除，恢复世界与 HUD |

第二轮日志：`elapsed=60.02` → `NATURAL_END -> requestExit` → `deactivateNow: reason=FINISHED` → 服务端 `test_letterbox_opacity fully complete`。本次启动的客户端已正常关闭（退出码 0）。Forge 仅编译通过，**未进行 Forge 实机验收**；没有扩大为整个 D 批次或 H6 全项验收。

