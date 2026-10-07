# 0.3.6 全计划实现审计（2026-10-07）

> 审计范围：`plans/0.3.6/` 全部计划文档（含 `feedback-0.3.5/`）逐篇对照仓库代码核实实现状态。
> 方法：三个审计代理并行，读文档的「已确认」项 + 落地顺序/交付物 + 状态标注，用 grep/read 查代码证据；「没找到实现证据 = 未实现」，不替计划找补。
> 状态图例：✅ 已落地　🟠 部分落地　❌ 未实现　🚫 计划自身标注框架讨论/长期方向（无本版承诺，如实记录供决策）。
> 结论先行：**计划内标"已确认/要落地"的内容并未全部实现**——主链路（并行播放、lane 渲染、退役、循环、i18n、缓动烘焙等）已落地，但调色系统第二/三批与 lane 级、相机底层七篇的覆盖链/模型层、触发器剩余批次、转场/遮罩增强、编辑器多个步骤等仍在计划未开工状态。下方为完整缺口清单。

---

## 一、总览

| 文档 | 状态 | 一句话结论 |
|---|---|---|
| parallel-playback.md | ✅ | 6 步全落地；剩 3 项计划标注待定/不做 |
| camera-composition.md | 🟠 | 数据层+渲染+接线全落地；编辑器步骤 6 未做 |
| multi-camera-rendering.md | 🟠 | §12 全部落地；每 lane 独立可见集合（长期解）、Iris 适配、视距档位、帧耗时统计未做 |
| script-loop.md | ✅ | 全落地（宏观 pingpong 为计划延后项） |
| variable-frame.md | 🟠 | 步骤 1/2/4 落地；步骤 3/5/6 未做；LETTERBOX opacity 未接线 |
| script-model.md | 🟠 | 缓动烘焙/重叠/合成参数/实例身份落地；分量寻址、keyframable、meta 关键帧化、时间精度未做 |
| scene-transition.md | 🟠 | 步骤 1/2 落地；步骤 3/4/5 未做；z 序遗留 |
| hud-hard-hide.md | ✅ | 代码完整；实机验证未做 |
| overlay-color-mask.md | 🟠 | 纯色覆盖层保留；渐变/混合/局部全未做；测试未重做 |
| screen-color-adjust.md | 🟠 | 第一批标量组（12 通道 master）落地；曲线组/第二三批/lane 级/调整层/编辑器 UI 全未做 |
| wait-point-track.md | 🚫 | 框架讨论，零实现 |
| editor-webui-migration.md | 🟠 | 退役+安全+飞控绑定落地；选中片段全屏 lane 渲染未做 |
| editor-script-graph.md | 🟠 | 步骤 1-4 落地；拖拽布局持久化、增量刷新未做 |
| templates.md | 🟠 | 片段级模板+命令落地；轨道级/脚本级/编辑器面板/用户模板未做；旧 PresetsPanel 残留 |
| trigger-conditions.md | 🟠 | 最小版本+any/组合+校验落地；嵌套组合、death、频率可配、组合器 UI、动作面收敛未做 |
| region-sync.md | 🚫 | 框架讨论，零实现 |
| camera-state-plan.md | 🟠 | 六参数+staged 删除+退役+读侧写侧收口落地；Base/Modifier 覆盖链、每 lane 独立状态未做 |
| coordinate-frame.md | 🚫 | 方向零落地（既有原语外）；§8 清单 9 条未做 |
| selector-model.md | 🟠 | 6 role 调用点隔离已做（文档晚于代码）；其余策略/锚点/多候选/通用化未做 |
| math-models.md | 🚫 | 方向零落地（仅散落原语） |
| temporal-interpolation.md | 🟠 | 步骤 1-2 落地（60 万逐位对照）；步骤 3-6 未做 |
| transition.md | 🟠 | cut/blend（morph 原型）已做；分参数过渡/曲线/链/打断/UI 未做 |
| hysteresis.md | 🚫 | 方向零落地（6 步全未做） |
| feedback 01/02 | 🚫✅ | 作废判定成立（缺陷载体已随编辑器删除） |
| feedback 03 | ✅ | exit_buffer 归一化已修（3a191d3）；README 状态滞后 |
| feedback 04 | 🟠 | 轮询下限一致；#1 触发器直接执行命令、#2 间隔可配未做（与 trigger-conditions 重叠） |
| feedback 05 | ✅ | on_enter 复位已修（f9ae675） |
| README 待定小项：Bezier 曲线编辑器 | ❌ | 数值编辑有（BezierCurveField）、控制点可视化无 |

---

## 二、功能性缺口清单（计划内写了、代码里没有）

### 高优先（直接影响交付物/常用能力）

1. **调色系统（screen-color-adjust）**——四象限交付物的 RGB 逐格调色卡在这里：
   - ❌ 曲线组：RGB 复合曲线（形态 b）
   - ❌ 步骤 2：RGBA 通道拆分 + 完整 HSL（色相旋转）+ 每通道 R/G/B 曲线
   - ❌ 步骤 4：第二批（RGB 通道混合器、六条 hue 曲线 HvH/HvS/HvL/LvS/SvS/SvL、Lift/Gamma/Gain 色轮）
   - ❌ 步骤 5：**lane 级调整**（每 lane FBO 内、合成前——多相机每机位独立调色的前提）
   - ❌ 步骤 6：调整层 + 第三批（LUT/六色带/混合模式）
   - ❌ 步骤 3 编辑器 UI：ADJUST 前端全缺（TrackType 联合、轨道列表、时间轴配色、demo schema）
   - ❌ 多实例各写 master 的合并语义未定稿（当前实际 = 各实例后发布者覆盖）
2. **每 lane 独立可见集合**（multi-camera-rendering §12.8-B 长期解 / camera-state-plan §7）：现为整帧共享单份 + smartCull=false 缓解（播放期绘制量上升）；穿墙/远距运镜的倾斜矩形已缓解但未根治。
3. **触发器（trigger-conditions）**：组合器嵌套（多层）、`death` 触发器类型、检测频率按触发器/脚本可配（现仅类型级、facing/all_of/any 硬编码 5 tick）、组合器编辑器 UI（schema 空字段表→表单为空）、动作面收敛（StopPlaybackAction/PlaySoundAction/ExecuteCommandAction 均为死代码，运行时只注册 StartPlaybackAction）。
4. **variable-frame 步骤 3/5**：画面层（lane）未并入 OverlayLayer 统一体系（走独立 LaneCompositor 路径，位置/锚点/缩放对画面层不适用）；source/fit 未补到文本层。
5. **scene-transition 步骤 3-5**：转场数据落点（§4 A/B/C）未定稿、wipe 未做、编辑器转场面板未做；z 序遗留（转场遮罩 z=10 压不住字幕 z=30）。

### 中优先（计划内的功能批次）

6. **overlay-color-mask 增强**：渐变遮罩（线性/径向多色 stops）、混合模式（正片叠底/屏幕/柔光/叠加）、局部遮罩（矩形/圆形）——全未做；test_overlay_zindex 高对比重做未做。
7. **templates 步骤 3-6**：轨道级模板、脚本级模板（meta/触发器骨架）、编辑器模板面板、纯数据用户模板；嵌套引用（片段←轨道 id）未做；**旧 PresetsPanel.vue 残留**（硬编码 orbit_circle 贝塞尔，未迁移到新模板体系也未删）。
8. **editor-script-graph 步骤 5**：节点手动拖拽布局与持久化、脚本增删改后的增量刷新。
9. **editor-webui-migration**：飞控"选中片段画面全屏 lane 渲染"（依赖已齐，仅表现层未做）。
10. **camera-composition 步骤 6**：编辑器合成参数专用面板 + 多 lane 预览（现仅 schema 通用表单顺带渲染 opacity/dest/source，无编排/预览）。
11. **script-model 未做条目**：参数寻址到分量（"只让 X 动"）、FieldDef keyframable 标记、meta 关键帧化（listener/hide_hud/可跳过等仍整段单值）、时间精度改高精度、叠化 hold 的"自动补充等值关键帧"生成工具/模板。
12. **parallel-playback**：队列按脚本匹配接播（ScriptQueue 无匹配 API，计划标注待定但未定稿）。
13. **multi-camera-rendering**：Iris/Oculus 适配、视距档位（内容档位已做）、帧耗时统计（压测代码随原型删除后未重测）。
14. **feedback-04**：#1 触发器直接执行命令、#2 轮询间隔可配（与 #3 重叠）。
15. **README 待定小项**：Bezier 曲线编辑器控制点可视化。

### 长期方向稿（🔒/🟡，零或部分落地——按用户口径全部如实列出）

16. **camera-state-plan**：Base Chain / Modifier Chain 覆盖链（优先级、通道掩码、REPLACE/ADD/MULTIPLY、震屏/后坐力/Dolly Zoom）零实现；每 lane 一份 CameraPath/CameraProperties 未做；外部 API 未做。
17. **coordinate-frame**：统一点源清单（yaw_base_from/to、facing_target 仍只认实体）、look_at 方块来源与部位百分比微调（现写死 AABB 中心）、偏移下放到点源、通道相对化、方向锁、垂直线边界未修（lineDir 条件仍只拦零长度）。
18. **selector-model**：yaw_base_from/to 不在调用点隔离清单；schema/编辑器无策略字段；缓存键不含调用点；锚点不可配；多候选/通用化未做；@a/@r/@n/@p[team=…] 不解析的已知缺陷仍在。
19. **math-models**：模型层零实现（spring/damper/smooth_damp/viscous_lag/inertia/momentum 全仓零命中；非线性变换与组合零实现）。
20. **temporal-interpolation 步骤 3-6**：相机状态 prev/current 快照插值、历史缓冲/外推、模型接入、外部 API。
21. **transition 步骤 3-8**：分参数过渡（morph 现为六参数同一 weight）、曲线接入（weight 是线性斜坡）、Base/Modifier 接入、过渡级打断/链式、编辑器过渡 UI、外部 API。
22. **hysteresis**：全部 6 步零落地。
23. **region-sync**：区域同步/镜像传送零实现（阻塞 fb-05 已解除，可开工）。
24. **wait-point-track**：等待点轨道零实现（TrackType 无 WAIT_POINT、无事件源、ModEventTrackPlayer 空占位、无 player_death 触发器）。

---

## 三、文档滞后（代码已改、文档没跟上——建议一次文档同步批次清掉）

1. parallel-playback.md §步骤 4/5/6 落地记录末行（"听者后来者居上、编辑器预览独立实例尚未落地"——已分别由 0773bdc / 32f1282 落地）。
2. camera-state-plan.md：CameraManager.onRenderFrame javadoc 挂点仍写 LaneRendererMixin（实际 GameRendererMixin）。
3. plans/0.3.6/README.md 索引：editor-script-graph 仍标 🔵（自身文档步骤 1-4 已 ✅）；feedback 03 仍"🟢 可开工"（已修）。
4. feedback-0.3.5/README.md 状态列：03 实际已修仍写待处理；04 #2 未实现未反映。
5. docs/SCRIPT_FORMAT.md:59-63 与 docs/AI_SCRIPTING_GUIDE.md:125：仍写 meta.preload / camera_mob_spawn/radius/ai（代码已删）。
6. docs/modules/editor.md 已知问题节：仍记"enterFlightMode 忽略 x/y/z"（WebPreviewScreen 已修）；另一条旧口径待归档。
7. scene-transition.md alpha 总账两行：letterbox"无 alpha 字段"（LetterboxLayer 已有 opacity，只是轨未接线）、pip"opacity 不生效"（已修）。
8. editor-webui-migration.md：§1.2 "webui 8 文件"（现 10）；§2 消息清单缺 script.graph/resource.list；§3 预览隔离条目已被 previewInstance 落地。
9. temporal-interpolation.md 已知缺陷节：两条（PathStrategies 只注册 linear、4 参死代码）已被 3a191d3 修复。
10. hysteresis.md / camera-state-plan 等：clip 级 `interpolation` 枚举 {linear,smooth} 相关表述——字段 0.3.6 整体移除（ScriptValidator 报错），运行时统一线性 + 编辑器烘焙。
11. multi-camera-rendering.md §12.1 LaneDebugDriver 行："含临时上屏（合成层落地后删）"——已改走 LaneCompositor，行文未更新。
12. editor/src/demo.ts 离线 schema 陈旧：meta 无 macro_loop 三字段、无 ADJUST 轨、selector 策略字段缺失（仅影响离线编辑器的控件可见性）。

---

## 四、验证缺口（实机未测项，代码已写）

1. hud-hard-hide：TACZ/SecurityCraft 类注册表 overlay 的 Pre 拦截实机验证。
2. 叠化/黑场/白场/合成参数（dest/source/opacity）的游戏内上屏视觉验证（数据链路均过校验器+离线仿真）。
3. screen-color-adjust：游戏内实际画面、光影（Iris finalizeGameRendering）先后顺序、性能定量。
4. 脚本 i18n：真实客户端多语言切换下字幕/架构图 description 显示（无头只验证了解析函数与接线）。
5. lane 缺陷修复的症状 B 像素实证：5 轮捕获未采到现场（间歇性），机制级证据 + 用户实机确认（时间 13000 黑白盒交界已不再产生倾斜矩形）。
6. 编辑器 UI 交互层（datalist 下拉、Gizmo、图形交互）——SSR 渲染断言覆盖，真实浏览器未跑。
7. template 命令、release 脚本集在游戏内 `/icinematics` 实跑（validator 全过、离线仿真全过）。

---

## 五、交付物脚本已知偏差（cinematics/release/，d3e1049）

- 四象限"每格一种 RGB 调色"未按字面实现：ADJUST 是 master 级单例（后发布者覆盖）且无 R/G/B 通道——现为入口实例整屏 10 关键帧调色动画（温度/色调/灰度/反相，含反相脉冲），右下格 opacity=0.5（字面实现）。
- 要按字面交付需先落地 §二-1 的 lane 级调整 + R/G/B 通道。
- 其余（四机位 dest 2×2、叠化、分屏+PIP、letterbox、音频、触发链、循环闭合）均已实现并过 validator + 离线仿真。

---

## 六、审计方法说明

- 三个审计代理（组 A 播放与画面架构+运行时表现 11 篇；组 B 编辑器+触发器+feedback 12 篇；组 C 相机底层七篇）。
- 全部结论带代码证据（类名:行号 / commit 号），完整证据见各代理报告（本回合存档于 agent://PlanAuditA/B/C 与 .planning/ 进度）。
- 未实现项的判定口径：计划文档"已确认/落地顺序/交付物"写了 + 代码找不到 = 缺口；文档自标"不做/延后/待定"的按计划口径记录但单列。
