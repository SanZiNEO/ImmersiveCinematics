# 0.3.6 计划文档「未定项」清查（pending discussions）

> **口径（2026-10-11 用户口径）**：本文档的「待定」= **0.3.6 边做边讨论**（随批次推进就地销项，销项进 §二），**不是长期搁置**；原「长期讨论」分类语义在 0.3.6 不成立。

## 本次语义评审：已确认项与真正未定项

本次只修订计划，不改代码；下列方向不再作为待二选一的“冲突”反复讨论：

| 已确认方向 | 真源 |
|---|---|
| 曲线是脚本级独立集合；绑定先选片段，再选目标位置对象/对象组的两个相邻关键帧；一片段可有多条曲线，不全片段复用一份几何 | [I 相机](I-script-core/track-camera.md)、[编辑器](Z-editor/object-model.md)、[迁移](I-script-core/migration.md) |
| 规则是脚本级独立集合；外层必选时间门（默认局部时间 `>=` 指定值，门后继续判定、迟到成立仍执行）AND 内层条件树；门前事件/事实可采集、门后确认提交；到门可实例级挂起，规则/片段事实接力，成功≠动作完成，动作可延迟 | [规则系统](I-script-core/rule-system.md) |
| 分支片段与主线片段同片段对象/schema；正常后继由片段默认、入口显式覆盖优先；失败 fallback 独立沿原主线继续 | [分支/循环](I-script-core/loop-execution.md)、[验收](I-script-core/acceptance.md) |
| 过渡采样 A/B 各自实际状态；窗口边缘没有关键帧也按正常求值取得，不拼原始末／首帧 | [I 相机](I-script-core/track-camera.md)、[运行时装配](I-script-core/runtime-evaluation.md) |
| 循环按执行次数与完成事实；无限无自然完成点，但框架不截断作者后续编排 | [次数执行](I-script-core/loop-execution.md)、[格式清理](I-script-core/format-cleanup.md) |

相机指模组内部相机模型，不是原版玩家相机。局部秒数可用于定位，不是完成真源；叠放顺序来自轨道层级和实例执行顺序，不来自 JSON 字段排列。

**结构评审：脚本级规则/路径集合与引用模型。** 推荐区分采样属性、规则定义/绑定、路径定义/绑定和运行激活；路径负责相邻位置节点间的区间求值，规则绑定负责执行时间门与条件组合，两者不合并为万能类型。待评审项为脚本级集合的引用形式、片段与对象/相邻关键帧寻址、插入和重排后的绑定关系、多入口绑定身份、事件事实记录边界与序列化。评审遵循组合优先、通道级覆盖、定义与运行态分离、创作策略与执行机制分离；现有字段布局不是目标能力上限。

进度见 [I 轮索引](I-script-core/README.md) 与 [实现进度](implementation-progress.md)：新目标未实现，C/B 已完成结果只保留其既有实现事实。以下日期化条目是各轮历史清查，不代表本次重新验证了全部遗留项。

## 〇、2026-10-08 用户裁决（本日新增；正文为当日快照，未逐条回改）

1. **同脚本重入 → 保持单实例**：同一份脚本对一个玩家只能同时播放一份；**脚本看作一个整体**（像 lane 那样）；「后来者居上」是**对不同脚本**说的。附带任务：**写一份《脚本状态机与状态追踪》计划**——把原本「布尔值点状事件判定」升级为「**事实追踪**」，后续优化基于该计划（见 progress 文档 P1）。
2. **Iris/Oculus → 先写调研计划**：目前尚不知问题在哪、未做调查——先产出调研计划文档（见 progress 文档 P2），结论用于定策略。
3. **每 lane 独立可见集合 → 做**（文档长期解；工程大，将拆多个子任务）。
4. **多实例 master 合并 → 按层级叠加**：层级关系 = lane 是一个**包**（其 RGBA 等通道在包内/包上调整——透明度就是 lane 相机合成好之后统一调整）→ 多 lane 合成放置到画面 → **master 对整体画面调整**；各管各的，最终效果 = **叠加**（多实例 master 按实例顺序叠加应用）。

## 〇-2、2026-10-10 用户预留（当日对话）

1. **脚本退出机制** → ✅ **方向已定（2026-10-10）**：人为干预、实际内容执行完、声明的退出条件三族；不以墙钟或推算时长冒充内容播完。规则新目标见 [I 规则系统](I-script-core/rule-system.md)；C 轮 condition-system 仅保留既有实现记录。统计字段清理见 [I 格式清理](I-script-core/format-cleanup.md)；不再引用重写前的 §8.1 或把旧总长当兜底退出条件。新目标为脚本级规则集合、必选时间门、实例级挂起、事实接力、延迟动作与分支出口分离；正常后继已确认由片段默认、入口显式覆盖优先。新规则激活与执行状态目标仍未实现，见实现进度。
2. **批次 C 扩容（用户当日指定）**：播放关系与队列（`playback-relations.md`）+ 判定规则系统（`condition-system.md`）两篇设计立项，实现归 **C 批**，不进 B 批；`wait-point-track.md` 被 condition-system.md 吸收（见各篇文首）。

---

> **如何使用（2026-10-08 入库）**：本文是「全量实现」的配套核对清单。第一节「仍未定」多数属计划文档约定的**执行时再定**型——会在对应任务的落地过程中定稿（定稿后按惯例回写该计划文档的落地标注，并从本节移除）；少数是架构/优先级级决策，需要用户拍板。第二节「已定稿」仅列供核对（避免重复讨论）。第三节是明确不做/作废/延后。第四节是文档滞后与口径冲突（与 `implementation-audit-2026-10-07.md` §三 的滞后清单重叠，统一在 H5 清理）。
>
> 生成：2026-10-08。只读清查，未改动 `plans/` 下任何文件。
> 范围：`plans/0.3.6/` 全部 34 篇计划文档 + `feedback-0.3.5/` 6 篇。元文档 `implementation-audit-2026-10-07.md`、`implementation-progress.md` 仅作交叉参考，不计入清查。
> 方法：逐篇通读「待定 / 待讨论 / 开放问题 / 可能的问题 / TBD / 执行时再定 / 未落地的倾向 / 后续待定」章节与行内标注，对每条判定：
> - **仍未定** = 文档自身与全仓其它文档均未见裁决，或明确标注「保留待定 / 待设计 / 需实测 / 需调研」。
> - **已定稿** = 原文就地划掉/加注「已定/已落地/已回答」，或**别处**（另一文档的小节、文首「落地标注」、代码）已给出裁决。
> - **明确不做 / 作废 / 延后** = 已决定不纳入或作废（**不算未定**，单列第三节）。
> 行号会漂移，出处一律「文档 + 小节（+ 行号）」；行号取自 2026-10-07 工作区。
> 文档级样板（每篇文首「字段名/接口/JSON 执行时再定」）是**约定**，不是具体未定项，未逐条列入。

---

## 一、仍未定（0.3.6 边做边讨论：随批次推进就地销项，销项进 §二；少数架构/优先级决策仍需用户拍板）

> D 轮（2026-10-17）的 D1–D8 / D10 已收口、D9 不做；F 轮（2026-10-17 实现 / 2026-10-18 光影画面）F2/F3/F5 实现完成、F4 不做；F8 设计基线已按调查重写，未实现；余项见 §二「F 轮」与本节各文档条目；各节保留一行状态便于核对，完整销项见 §二 / §三。

### I 轮规则 / 曲线 / 分支（2026-10-30）

- **事件条件自身的记录起点/有效期/消费（精确待评审项）**：状态谓词按实时语义判定，不永久锁存任意一度为真；事件事实按原条件有效期/匹配/消费语义保留到时间门。若原条件未定义记录起点、有效期或消费方式，按结构/条件评审补定，不假设所有历史事件永久有效；不做无界历史或跨实例事件总线。见 `I-script-core/rule-system.md`。

### 索引层
- **Bezier 曲线编辑器（控制点可视化）**：数值编辑已有（`BezierCurveField`），控制点可视化无；随编辑器线排期。影响：编辑器线。（`README.md` §七；审计 §一）
- **相机底层七篇缺统摄 roadmap**：先做哪篇、谁依赖谁未定。影响：camera-state-plan / coordinate-frame / selector-model / math-models / temporal-interpolation / transition / hysteresis 的排期。（`README.md` §五）

### camera-composition.md（画面合成）
- **dest 矩形之外无 lane 覆盖时的兜底**：露原版视角还是黑场（作者可用重叠避免空隙，但框架兜底策略未定）。影响：合成层缺省行为。（§5 L87）
- **编辑器预览怎么表现多 lane 合成**：步骤 6 未落地。（§5 L88、§7 L117）
- **与 HUD / letterbox 的叠放顺序**：未定。（§5 L89）
- **主 lane（听者归属用）的判定规则**：与并行播放一起定。影响：AUDIO 听者归属。（§6 L97；`parallel-playback.md` §6 L141）
- **跨脚本 lane 寻址**（候选 B「合成导演」的前置）：未定。（§6 L99）

> 2026-10-18 光影修复在 **lane pass 内部**（跨视角历史 / shadowcolor），合成层契约未变；以上五项**仍开放**，归 F 轮收口。

### parallel-playback.md（并行播放）
- **同脚本重入是否放开**：放开需先改状态机键（现同脚本同玩家默认单实例，跨脚本无限制）。影响：触发状态机。（§3.5 L102、§5 L127、§6 L139）
- **多实例 AUDIO 的空间锚点**：听者唯一（`AudioListenerController` 已按顶层但 `hasActiveCameraClip` 为并集），口径待对齐。（§5 L128、§7 L204）
- **跳过投票 UI**：多实例同时可跳过时，玩家投的是哪一个——待定。（§5 L129）
- **实例与 Base Provider 的对应关系**（见相机状态与覆盖链）——待定。（§5 L130）
- **区块预加载 ticket** → **已裁决不做（2026-10-22）**：当前版本不做预加载 / 预热，区块加载交原版；见 [`camera-center-entities.md`](F-playback/camera-center-entities.md) §2.7。
- **性能**：实例数 × lane 数的开销曲线；档位建议放在渲染文档——待定。（§5 L132）
- **互斥组 / 队列语义的最终形态**：互斥组已确认不做，队列语义仍待定。（§6 L138）
- **实例 id 的网络协议形态**：网络包已带实例 id，协议形态未定。（§6 L140）
- **主 lane 判定规则**（与合成文档一起定）。（§6 L141）
- **尚未落地三项**：听者后来者居上（口径待对齐）/ 编辑器预览独立实例 / 队列按脚本匹配接播（`ScriptQueue` 无匹配 API）。（§7 L204）→ 编辑器预览独立实例 ✅（既有）；**队列按脚本匹配接播 ✅（C5-a `28d065e` 打通接播可达；F2 于 2026-10-17 补齐出队判定：priority/FIFO 逐项可播性、无 exclusive 一次排空、同脚本副本不误启动、预览隔离、去容量 8，无头探针 68/68；实机已由 C 轮队列接播覆盖，2026-10-18 结案）**；听者「后来者居上」口径**仍开放**（`hasActiveCameraClip` 为并集，见 `parallel-playback.md` §7）。
- **已知后果（已消解）**：~~Sodium/Embeddium 下 lane 渲染被禁用~~ → 整条禁用已移除；检测改为跳过 `setupRender` 专属 mixin、走优化器路径；lane 始终渲染。（§3.3 L91）

### multi-camera-rendering.md（多相机渲染）
- **§4「渲染顺序」关键难点**：第二遍在主渲染之后/GUI 之前，还是先渲染再合成——事实核查只补了事实边界（挂点 = `renderLevel` RETURN），设计未替文档决定。影响：渲染挂点。（§4 L93）
- **每 lane 独立可见集合 / 遮挡剔除状态**（长期解）→ ✅ **已做（B3，2026-10-11，见 multi-camera-rendering.md §12.9）**：per-lane 槽 + 归槽 + 逐 pass `smartCull`，倾斜矩形症状不再产生（B3-c 像素实证）；整帧 `smartCull=false` 缓解与 `CinematicOcclusion` 已删（B3-d）。
- **副画面是否需要支持 look_at / tangent 朝向**：保持待定。（§9 L175）
- **是否允许叠化期间相机 lane 继续移动**：保持待定。（§9 L176；`scene-transition.md` §6）
- **光影（Iris/Oculus）能否拿到「已处理后的画面」** → **F3 实现完成（2026-10-17）**：lane 始终渲染，每 lane 跑完整光影链，画面是光影处理后的结果；**2026-10-18 修复跨视角 TAA 历史 / `shadowcolor` 污染**，Fabric/Iris 1.7.6 三包 A/B 干净；**F3 验收通过（Fabric/Iris 1.7.6 三视角 + 热切换；Forge/Oculus 1.8.0 同 jar 已部署）**。（§7 L144、§12.5 L350、事实核查 L284；`render-routes.md` §4）
- **Rubidium 兼容**：检测按入口类名 `me.jellysquid.mods.sodium.client.SodiumClientMod` 覆盖，未取 Rubidium jar 逐一核对——**已知限制**（类名缺失会在 `defaultRequire:1` 下启动期失败）。（§11 注 L207、事实核查 L283）
- **Sodium 下两遍/帧 `frame++` 语义是否引发错误**：✅ Sodium 0.5.13 实机 A/B 与 F5 十档均无缺块 / 闪块（Embeddium 同构未单测）。（事实核查 L285；`render-second-pass-cost.md` §7）
- （文档同步）文首状态行 L3 → ✅ **已解决（B 轮）**：状态行已重写为区分「渲染底层已正式化（2026-10-07，见 §12）」与「待开工：上屏合成 / 脚本·lane 注册表接入」，与 §12/§12.1 一致（见 §四 备注 1）。

### render-routes.md（临时草稿）
- **光影：能否拿「已处理后的画面」** → **F3 已答（2026-10-17 / 10-18）**：能，lane 每遍都跑光影全链；跨视角历史 / shadowcolor 污染已修，Fabric/Iris 1.7.6 A/B 干净；Forge/Oculus 1.8.0 同 jar 已部署，验收通过。（§4）
- **性能模组（Sodium/Embeddium）二选一** → **已裁决（2026-10-17）**：不整条禁用；检测保留用于跳过 `setupRender` 专属 mixin（走其 Camera / Frustum 路径），lane 始终渲染。（§4）
- 本文为临时草稿，稳定后并入 `multi-camera-rendering.md`（未并入）。（文档头）

### cross-dimension-camera（跨维度相机，per-view 维度渲染）

- 调查已确认：客户端需要第二套 `ClientLevel` / `LevelRenderer` / `LightTexture`；服务端需要 `(player, dimension, cameraId)` 状态、带维度标签的数据流与目标维度 ticket。P0 是验证顺序，不是能力边界。
- `DimensionView` 生命周期 / 缓存上限 / 卸载策略：按维度复用；播放结束 / 断线 / 维度卸载释放；内存预算未定。
- 服务端相机维度流：自建带维度的区块 / 光照 / 方块 / 实体包；IP 的 `ChunkLoader` / `ChunkDataSyncManager` / `EntitySync` 只作参考，不替换 vanilla 玩家差集。具体包形态（自定义 viewer set / 复用原版增量发送）开放。
- 同一玩家同时多个相机 / 多个维度：接口按 `(player, dimension, cameraId)` 设计，不做“单相机维度”的能力限制；同维度多相机时原版单中心 + 额外 ticket 的分工、anchor 取最近还是每相机一条，开放。
- `CameraState` 公开 API 是否最终加维度字段：当前建议 `CameraState` 保持六参数，维度放 `CameraLane` / `LaneFrame` 旁路；若公开 API 要带维度，另加 wrapper。
- 维度元数据同步：非当前维度需要 `dimension key -> DimensionType / level stem`；原版三维度可推导，自定义 / 数据包 / 模组维度必须同步，不锁版本。
- 客户端加载方式：最低要求是第二套 `ClientLevel` / `LevelRenderer` / `LightTexture`；由谁构造、资源 reload / resize 谁负责开放，不锁固定方案。
- 粒子：每 view 独立 `ParticleEngine` 还是跨维度 lane 关闭粒子，开放。
- FABULOUS / shadow：静态 target 重定向、shadow map 按 view 复制 / 切换的成本，开放。
- `ClientChunkCache`：更新 view center / storage radius 还是自定义 chunk store，开放。
- CAMERA clip 的 `dimension` 缺省 / 空串 = 玩家当前维度；玩家换维度时缺省 view 跟随，不固化启动时维度。
- 旧传送路径（`ScriptEventManager.applyDimensionSchedule`）与旧 crossdim 测试要删除；不能留作兼容。

### render-second-pass-cost.md §7 未决问题
- **副画面是否需要独立光照 / 光照更新**（若需要，`light_updates` 段不可省）。（§7 L311、§1.2 L53）
- **视锥构造风险（实测）**：旋转视图矩阵后重建视锥会卡死在 `Frustum.offsetToFullyIncludeCameraCube` 的病态膨胀循环；正式实现必须自带剔除方案——**待设计**。（§7 L312、§4.2）
- **两遍/帧在 Sodium/Embeddium 下 `frame++` 语义**是否导致区块渲染错误——需实测。（§7 L313、§3.2 L188）
- **副画面在 Iris/Oculus 下的可用性**。（§7 L314）
- **「N lane」成本曲线（线性/次线性）与档位默认值**：重场景/加模组后待复测。（§7 L315）
- **遮挡剔除能否按 lane 独立维护**。（§7 L316）
- **外部佐证测量方案（可选）**：装 Iris + 阴影包估算第二遍占比。（§6.3 L304）
- **Sodium/Embeddium 存在时须先验证两遍/帧不触发错误**（先「能跑对」再「跑得快」）。（§6.3 L305）

### camera-state-plan.md（相机状态与覆盖链）
- **§8 可能的问题（9 条）** → ✅ **已定（B1-a，2026-10-10，见 camera-state-plan.md「覆盖链定稿（2026-10-10，B1-a ∪ B2 结案）」§0–§15 + §8 就地标注「已定」）**：优先级层级 / `suppressModifiers` / 每通道默认合成方式 / blend·transition 关系 / clamp·normalize / 无 Base 活跃时回退 / 每帧零分配 / 兼容与外部 API 语义逐条收口。
- **§9 待定（未结部分）** → ✅ **已定（B1-a，2026-10-10，同上覆盖链定稿）**：Base/Modifier 接口最终形态（§12 草案）/ 优先级数值 / 状态 buffer 设计（§12）/ 外部 API 开放时机 / `zoom` 去向，均已收口；§9 条目已就地标注「已定」。
- **每 lane 一份 `CameraPath`/`CameraProperties`** → ✅ **已结案（B2，2026-10-10）——不需要做**：已由 `CameraLane` / `CameraState` 快照模型承载（覆盖链定稿 §13）。
- **每 lane 独立可见集合/剔除状态** → ✅ **已做（B3，2026-10-11，见 multi-camera-rendering.md §12.9）**：per-lane 槽 + 逐 pass `smartCull`；`CinematicOcclusion` 已删（B3-d）。
- **`CameraPath`/`CameraProperties` 是否被状态 buffer 取代** → ✅ **已定（B1-a，2026-10-10，覆盖链定稿 §11）**：保留为包内「当前值写入缓冲」，不被取代；覆盖链每帧工作区是独立的 `CameraStateBuffer`（§12）。

### coordinate-frame.md（基准坐标系）
- **§5.8 待定（5 条）** → ✅ **全部已定（B8，2026-10-11，见 coordinate-frame.md §5.8 就地标注；结论以 `docs/SCRIPT_FORMAT.md` §方向锁 / §`up_axis` 移除 为准）**：锁值参照 = 世界角绝对值 / 未锁 = 跟现行 `yaw_base`·`pitch_base` 体系 / 锁替换该轴基准、关键帧偏移仍叠 / 挂角度通道（`yaw_lock`·`pitch_lock` 两把）/ `up_axis` 收编删除。
- **§6 可能的问题（13 条）** → ✅ **多数已定（B5–B9，2026-10-10 ~ 10-11，见 §6 就地标注）**：原点 / 三类线规则 / 端点清单共用 / 通道相对化形态 / `up_axis` 关系 / `look_at` 优先级 / 偏移收敛 / 注视点方块来源 / 关键帧级——均已定。**仍开放**：`look_at = none` 是否保留「假目标点」实现、偏移作为关键帧通道的插值、编辑器呈现、性能（多个点源/方向源每帧求值）。
- **§7 待定（8 条）** → ✅ **多数已定（B5–B8，2026-10-10 ~ 10-11，见 §7 就地标注）**：字段命名与形态 / 点源清单 / 偏移表达空间 / `look_at` 优先关系 / 垂直线处理——均已定。**仍开放**：方向源（身体朝向·视线）是否拆成两个独立来源（措辞已定，B9）、同一关键帧是否需要多个坐标系、外部 API 时机。

### selector-model.md（选择器）
- **§4 层内未定** → ✅ **已落地收口（B10–B15，2026-10-10，见 selector-model.md §4.6「落地形态（2026-10-10，B15）」）**：调用点命名/分组（`SelectorSchema.CALLPOINTS` 8 名）/ 请求去重与缓存归属（缓存键含调用点·锚点，B11）/ `sort=nearest` 锚点可配（B12）/ 多候选择一策略纳入本层（B13）。
- **§5 可能的问题（9 条）+ §6 待定（6 条）** → ✅ **缺口已落地收口（B10–B15，2026-10-10）**：缓存键与失效语义 / 锚点语义 / 调用点清单 / 锚点可配置 / 多候选择一策略等由 B10–B15 补齐。**注：B15「非相机调用点接入验证」（T16）已完成（2026-10-11）**——非相机调用点（触发器 / 未来轨道）复用同一选择器服务已由无头探针实证（27 项断言全绿），见 selector-model.md §4.6「非相机调用点接入验证（2026-10-11，T16）」；其余各项已收口。

### math-models.md（数学函数模型）
- **§5 可能的问题（12 条）+ §6 待定（7 条）** → ✅ **已定稿（B16-a，2026-10-10，见 math-models.md「定稿」1–12；§5/§6 已就地标注）**：统一接口形态 / 实例粒度 / dt 与采样 / 状态生命周期 / 定义落层 / 组合深度与性能 / 回退 / 持久化 / 表达式·函数图 / 编辑器 / 外部 API / 命名，以及 Registry 与 JSON schema / 内置模型清单 / 编辑器 UI / API 时机——全部收口；定稿 12 两项（seek 后模型状态 = `reset`、包名 `math`）已用户拍板。

### temporal-interpolation.md（时间插值）
- **§6 可能的问题（11 条）**：✅ **已裁（B17，2026-10-11，见该文档 §7）**——相机自身 prev/current 快照 = 不需要（§7.1）/ 历史缓冲深度 = 第一版不做（§7.2）/ 外推默认开关与最大外推时间 = 第一版不做（§7.2）/ 与过渡·覆盖链的先后关系 = 已文档化（§7.4）；其余（partialTick·alpha 来源、固定步长 vs 渲染帧 delta、角度·数值域规则、确定性·重放一致、每帧零分配、与数学函数模型边界）现状已定或引已定稿（§5 / §4.2 / `math-models.md` 定稿 8/9）。（§6 L112-121）
- **§7 待定（未结部分）**：✅ **已裁（B17，2026-10-11）**——快照 / 缓冲设计 = 不做（§7.1 / §7.2）；外推策略 = 第一版不做（§7.2）；相机状态快照是否进入统一状态体系 = 不需要（§7.1）；外部 API 开放时机 = 0.3.6 只留位不开放（§7.3）。（§7 L128-132、§8 L138）
- （口径冲突备注）「统一接口形态」「实体插值迁移范围」：✅ **已统一（B17，2026-10-11）**——以 §8 落地记录为准（步骤 1-2 已完成：`util/TimeInterpolation` + `CameraTrackPlayer` 收拢迁移）；事实核查的「接口形态仍待定」字样已按现行口径清理（只写现行）。（§7 L127/L130 vs §8 L138）

### transition.md（过渡）
- **§7 可能的问题（12 条）+ §8 待定（6 条）** → ✅ **已收口（B18，2026-10-11，见 transition.md §6「实施接线」+ §7/§8 就地标注 + §9 落地顺序）**：过渡层落点（`camera/transition/`）/ 打断规则（snap·blend，缺省 blend，用户拍板）/ 分参数默认 / 曲线走 math 模型层 / A·B 采样时机 / 与关键帧插值·`look_at`·外部 Override 的划界 / 链式与打断恢复 / 零分配与确定性 / 编辑器（归 Z 轮）/ API（留位）逐条收口。
- **yaw/pitch 的角度环绕、最短路径、多圈语义** → ✅ **已定（B18，2026-10-11）**：角度域混合公式单源 `TransitionBlend`（yaw / roll 最短路径、zoom 几何域）。

### hysteresis.md（迟滞）
- **§6 可能的问题（11 条）+ §7 待定（6 条）** → ✅ **已收口（B19，2026-10-11，见 hysteresis.md「定稿与落地记录（2026-10-11，B19）」）**：第一版行为清单 / 六通道默认建议（默认关）/ 参数命名·单位·范围 / 与关键帧插值·过渡·时间插值的边界 / 模型落层（`math` 层）/ 状态生命周期 / dt 与帧率无关性（headless 四档 dt 实测）/ 编辑器（不做）/ 外部 API（留位）/ 命名——逐条收口。

### trigger-conditions.md（触发器）
- **§5 待定（4 条）**：条件列表的字段形态与嵌套方式（any 纳入体系、最小版本后放开；嵌套执行时定）/ 前置条件↔脚本状态（`script_played`/`script_started`/`script_completed` 的「播完」是否含跳过·打断、状态归属谁记、并行播放下按玩家×脚本 id 还是按实例）/ 取反（not）是否纳入 / 编辑器支持的排期。（§5 L112-115、§1 L29）
- **组合器嵌套（多层）**：只做一层，嵌套未做。（§2 L43、§3.1 L60）→ ✅ **已做（C1，`863988a`）**：嵌套 ≤ 8 层（含顶层），求值/校验同源递归。
- **求值顺序**：按代价排序（先便宜后贵）未做（现按列表顺序+短路）。（§4.5 L91）→ 仍未做（非 C 轮范围）。
- **`death`（玩家死亡）触发器类型**：后续批次，未实现。（`mod-architecture-diagram.md` L185；审计 §二-3）→ ✅ **已做（C2，`64392ac`）**：27 类型；场景条件按死亡时刻记录判定。

### region-sync.md（区域同步；框架讨论，零落地）
- **§7 待定（5 条）**：接缝/触发精度（每 tick vs 沿用触发器轮询间隔）/ 镜像·旋转下的朝向策略细节 / 落点安全检查是否做 / 多规则重叠时的优先级 / 载具中的玩家是否处理。（§7 L125-129）
- **一对多 / 随机目标**：待定（先按一对一）。（§2 L39）
- **进入即传送 vs 持续映射两种模式的最终精度与手感**：取决于尚未实现的每 tick 映射路径，未验证。（§3 L43-50、事实核查 L185）

### wait-point-track.md（等待点轨道；框架讨论，零实现）
- **§10 TBD（8 条）**：`action` 的精确语义（continue/end/next_script/jump/branch）/ 超时行为的完整定义 / any·all_of·branch 事件树的最终形式 / 多玩家·广播脚本下等待点按玩家独立还是全局共享 / 按钮·屏幕坐标·文本输入等 UI 事件的具体渲染与交互 / 外部模组事件接入 API / 等待期间能否允许其他轨道（非音频）继续 / 编辑器中的可视化与编辑方式。（§10 L228-235）

### overlay-color-mask.md（Overlay 颜色遮罩）
- **D4–D7 已收口（2026-10-17，销项）**：§4 六项开放问题全部定型——渐变 / 混合 / 局部遮罩均做；扩展 `layer_type:"fade"`，不新增类型；默认全屏纯色保持；WebUI 面板归编辑器。实现、验收与 Forge / Iris 边界见 `D-transition/color-mask-execution.md`。

### screen-color-adjust.md（画面颜色调整）
- **曲线关键帧形态 a/b 的选择**：倾向先 b 后 a，仍留待执行时定。（§3.1 L283-286、§6 L337）
- **shader 曲线数学**：预烘焙 LUT（256×1）vs shader 内贝塞尔求值——执行时定。（§4 L297）
- **光影（Iris/Oculus）与我们的 pass 的先后顺序** → **挂点先后已定（2026-10-08，`iris-oculus-compat.md` §2.7）**：master 调色在原版 RPOST 之后、GUI 之前，读到的是光影处理后的画面；默认 `SRGB` 下色彩空间转换为 no-op。**仍开放**：pack 声明 color correction 且用户改成非 `SRGB` 时的顺序需显式钉死（`@Inject(order=…)`）；lane 画面侧的跨视角污染已于 2026-10-18 修复。（§5-1 L60、§5 L321、事实核查 L404）
- **多实例各写 master 的合并语义**：后写覆盖 / 按实例层级——仍开放（本版本至多 1 活跃实例）。（§5-4 L121、§5 L324；`state-brief-arch.md` 附-3）
- **编辑器曲线编辑器与色轮的实现形态**（自绘 vs 复用；与脚本模型 §6「参数寻址到分量」共用曲线编辑能力）。（§6 L338）
- **LUT 的时机**。（§6）

### scene-transition.md（画面转场）
- **D1–D3 已收口（2026-10-17，销项）**：数据落点 = **C（纯显式 clip / 关键帧）最终方案**，不新增 `transition_in/out`、`TRANSITION` 轨或运行时生成器；转场类型第一版 = cut / dissolve / black / white / wipe；z 序 = 显式 `z_index`（普通 fade 10 / 转场 fade 1000 / 示例字幕 100；同时显示的 OVERLAY 层各自独立轨，同轨只消费首活跃 clip）；叠化 = 底 A 不透明 + 上 B 0→1；旧“互补 alpha 不会露底”的错误结论已纠正。详见 `scene-transition.md` §3–§9。
- **仍开放（应用层，不阻塞 D 轮）**：叠化期间相机 lane 是否可动（沿用 multi-camera §9）；跳过 / 打断途中的转场行为；lane opacity 插值曲线；循环回卷衔接（repeat 硬切 or 可选叠化）。

### variable-frame.md（画面系统核心）
- **D8 / D10 已完成（2026-10-17，销项）**：统一几何（§4.2）沿用；`base_resolution` 不设 16384 人为上限（正整数可表示范围）；LETTERBOX opacity 线性接线。
- **仍开放（与几何口径无关）**：画面层 z 序基线（应在 letterbox 之下）；旋转是否纳入；小尺寸画面是否按显示尺寸渲染；与 HUD / letterbox 的叠放顺序细节；编辑器预览画布具体形态。
- 已结清：取材边界（钳制到素材内、裁到 0 不绘制）；`dest` 与画布换算（无换算，统一窗口参照）；`pip` 删除；`fit` 废除。

### script-model.md（关键帧模型）
- **§6 可能的问题（6 条）+ §7 待定（6 条）**：参数寻址的粒度在编辑器怎么呈现（每分量一条曲线还是合并）/ 关键帧级插值后片段级插值字段（clip `interpolation`）的去留与迁移 / meta 关键帧化的边界（哪些 meta 不适合）/ 时间精度的迁移代价 / 参数声明与现有 `FieldDef` 的合并方式 / 关键帧数据规模（脚本体积·解析开销）；关键帧的字段形态（含贝塞尔手柄表示）/ 参数寻址的具体写法 / `keyframable` 的声明位置（schema 还是运行时）/ meta 关键帧化的清单与例外 / 时间精度方案 / 实例身份与跨实例引用的规则。（§6 L88-93、§7 L99-104）
- **时间精度**：是否改为有理数/高精度（长脚本+密集关键帧）。（§4-5 L71）
- **预览（编辑器）与正式播放的关系**：预览是「特殊实例」还是「独立通道」；实例身份/状态归属/跨实例引用（能否引用别的实例的 lane）。（§5 L81-82）

### I-script-core/loop-execution.md（循环）
- **折返瞬间的动画/事件帧对齐、长跑时虚拟时钟精度、触发式启动的脚本与宏观循环的关系、脚本时间起点与相对基准的取值时机**：未定。
- **宏观 pingpong 的落地时机**：延后。
- **编辑器属性面板 + 预览表现**：字段已进 schema，前端表现待确认。

### script-i18n.md（脚本 i18n）
- **编辑器预览的翻译切换**：预览器是否跟随编辑器 UI 语言 / 能否手动切语言预览——保留待定（现按客户端语言解析）。（§6-4 L147-148）
- **模板生成文本**：模板参数产出的字幕文案要不要直接产 key——保留待定（依赖 `Z-editor/templates.md` 模板体系）。（§6-5 L149-150）
- **跨 MC 版本的 `LanguageManager`/`ClientLanguage` API 形态**：未核对（证据全取自 1.20.1）。（§8 L244-248）
- **是否需要更快的译文重载路径**（编辑器预览通道内单独重读）。（§3.4 L107）

### Z-editor/templates.md（模板参数化）
- **§4 可能的问题（5 条）+ §5 待定（4 条）**：模板参数与运行时基准（相对坐标/选择器/触发点）的关系 / 三层之间传参的形态（继承/覆盖/命名空间）/ 自定义纯数据模板（远期，依赖 math-models）/ 生成结果的可编辑性（脱离 or 保留来源标记再生成）/ 模板版本化（倾向「生成即脱离」）/ 编辑器呈现（模板面板=表单+实时预览+插入位置）/ 三层模板的注册表与 `id` 命名空间 / 参数声明与 `FieldDef` 的合并方式（是否需 `keyframable` 之外新属性）/ 脚本级模板与触发器模板的关系（倾向随脚本级走）/ 模板库第一批清单。（§4 L89-94、§5 L100-103）
- **嵌套引用（片段←轨道 id）**：本版未实现，留给步骤 3。（§1 L37）

### Z-editor/（编辑器）
- **飞控「选中片段画面的全屏 lane 渲染」**：未实现（依赖多相机渲染 + 并行播放的预览隔离）；当前只解决「进入飞控时取哪个片段的哪个姿态」。（`flight-mode.md` §二、`README.md` §六）
- **预览与游戏内播放的通道隔离**：已落地（预览为独立实例）；原属并行播放步骤 5。（`server-protocol.md` §六）
- **多 lane 合成本身在编辑器预览中的表现**：跨文档引用 `camera-composition.md` §5 待定项（本文无裁决）。（`README.md` §六）
- **远期 / 可选**：H.264 / WebRTC、飞控远程面板、动效。（`README.md` §六）
- **游戏内实际连接未验证**（本机无 MC 运行环境）。（`security.md` §四）
- **架构图大包渲染规模**：节点上百时用 canvas 还是 svg——量级压测与 canvas 迁移留到有真实大包时再定。（`script-graph.md` §九）
- **架构图布局持久化形态 / 存储位置**（步骤 5 未做）。（`script-graph.md` §九）

### screen-layers.md（画面层）
- **§7 遗留（4 条，hard 模式实现后仍未就地把结论落定）**：其他模组在 `RenderGuiEvent.Pre` 的非绘制副作用会被一并跳过（hard 固有代价，需写明）/ 重画原版 overlay 的记账（每个原版 overlay 在「非 ForgeGui 循环内」调用时行为是否一致）/ 编辑器预览·暂停·退场动画无副作用需确认 / Sodium·Embeddium 兼容「执行时确认」（只做点状注入，风险低结论未实测）。（§7 L175-180）

### feedback-0.3.5/
- **03 `exit_buffer` 外扩对角落点顺序敏感**：✅ 已实现（2026-10-09 回写）——`Evaluators.expandAxis`（:64-77）先做 min/max 归一化再外扩（`Math.min(a, b) − buffer` / `Math.max(a, b) + buffer`，:70-71），与 `inBox`（:189-202）的顺序无关语义一致；脚本侧「最小角在前」规避已非必需。影响：触发器 `exit_buffer`。（`03-exit-buffer-corner-order.md` L4、L25；`README.md` §六-03）
- **04 轮询间隔按触发器/脚本可配**：已并入 `trigger-conditions.md` 需求 3「检测频率可配」，**实现未做**（现为类型级硬编码 5 tick）。（`04-trigger-latency.md` L19/L22）
- **02 文档与代码不同步（非编辑器问题）**：`docs/SCRIPT_FORMAT.md`、`docs/AI_SCRIPTING_GUIDE.md`、`docs/modules/editor.md`、`docs/modules/script.md` 仍写已从代码移除的 `preload` / `camera_mob_spawn` / `camera_mob_radius` / `camera_mob_ai`，需同步。（`02-editor-coverage-gaps.md` §六 L42）

### 原型文档遗留
- **quadrant-prototype-results.md §6 与 0.3.6 衔接**：Sodium/Embeddium/Iris 兼容（原型纯原版管线）未覆盖 / 其他「renderLevel 之后」的整屏步骤同类风险 / 0.3.6 排查清单（`doEntityOutline` / `postEffect` / `tryTakeScreenshotIfNeeded` / `Minecraft` 最后 `blitToScreen` 属哪个 lane）。（§6）
- **每 lane 独立可见集合**（长期方向）→ ✅ **已做（B3，2026-10-11）**：见 multi-camera-rendering.md §12.9。

---

## 二、已定稿（仅列供核对）

> 这些条目曾以「待定/开放问题/可能的问题」出现，现已有裁决。给出「在哪儿定的」。

### I 轮规则 / 曲线 / 分支（2026-10-30）
- **时间门** → 已确认：默认门 = 片段本次执行局部时间 `>=` 指定时间；门后开始/继续判定其它条件，迟到成立仍可执行，不要求 cross 事件或恰好同秒。门前有效事件/事实按源语义采集保留，门后才组合判定/动作；不是状态一度真就永久缓存。
- **规则控制** → 已确认：到门可暂停整个本次播放实例等待判定，不影响其它脚本实例；普通非阻塞观察仍可用；判定等待与动作延后是不同阶段/持有来源，多个持有来源最后一个解除才恢复。
- **事实接力与延迟动作** → 已确认：规则判定成功、片段开始/执行完成等事实可作为后续规则条件；接力范围限本实例与相关绑定/执行，不扩大到通用变量语言、跨脚本任意控制或外部注册总线。成功≠动作完成；动作可延迟到指定片段开始/完成、指定局部时间或明确时间条件/延迟；已承诺但未释放的动作作为执行依赖参与自然完成，不静默丢弃；时间参照与动作目标可不同。
- **正常后继** → 已确认：片段定义默认，启动/编排入口显式覆盖优先（入口显式 > 片段默认）；失败 fallback 独立沿原主线继续。
- **规则/曲线归属** → 已确认：脚本级独立集合，通过片段引用定位；同一规则多入口各有独立绑定与运行态；曲线绑定目标位置对象/对象组的两个相邻关键帧。

### camera-composition.md
- **`pip` 层的去留** → 已定（2026-10-08，CLEANUP-2）：**`pip` 层已删除**，被 lane 取代——画中画 = 一条 CAMERA lane 的合成参数（`dest` / `source` / `opacity`），即 §3 方向里的「pip 层被合成层取代」。`PipLayer` 为占位实现（仅白框 + 半透明黑底，从未接入真实相机画面），运行时 `OverlayTrackPlayer` 的 `pip` 分支、`layer_type` 枚举与校验一并移除（现为 `fade` / `image` / `subtitle` 三值）。**WebUI 里对脚本片段的便捷操作留 Z 轮**。（§3、§6；`variable-frame.md` §6/§8）
- **合成参数归属（自声明 vs 导演）**：取候选 A（自声明），B 结构空间不堵死。定于本文 §7 步骤 1 定稿（L130-148）。（§2 L52/L59、§5 L85、§6 L96）
- **关键帧通道落点**：合成参数（opacity/dest/source）全部挂在 CAMERA 轨 clip 的关键帧（`Keyframe.data` 通用容器），order 不进字段表。定于 §7 步骤 1（L130）。（§2 L61、§6 L96）
- **order 冲突判定规则**：轨道层级 → 轨道内 clip 顺序 → z_index。定于 §1。（§5 L86）
- **叠化预热时长**：预热 = 重叠窗口本身，「预热时长」退化为作者编排，不再是框架参数。定于 §4（L79）。（§4 L79；`multi-camera-rendering.md` §9 L177）

### multi-camera-rendering.md §9 开放问题（划掉/已回答）
- **多相机是否走「多条 CAMERA 轨道」还是「单轨道多 camera_id」** → 已回答：多脚本实例 × 每实例多 lane，不设上限。（§9 L171-172）
- **叠化是否只支持两相机 / 分屏是否支持任意布局** → 已回答：统一为合成参数特例，lane 数不设限。（§9 L173-174）
- **预热时长是固定值还是脚本可配** → 已解答（2026-10-07）：都不是，预热 = 重叠窗口。（§9 L177）
- **性能模组兼容策略** → 检测保留用于跳过 `setupRender` 专属 mixin（走优化器路径）；~~整条禁用~~ 已移除（2026-10-17）；lane 始终渲染。（§7.1 L151）
- **lane 纹理 alpha 漏进合成（透底）** → 已用专用着色器 `ic_lane_blit` 忽略 alpha 修复（2026-10-07）。（§12.8-A L391）
- **§12.9 per-lane 可见集合设计（B3-a）与全量 BFS 归槽（§12.9.7②）** → 已定稿并收敛（B3-a 设计定稿 + B3-b 归槽收敛，2026-10-11）：见 §12.9（设计）/ §12.9.2（载体选型）/ §12.9.7②（归槽收敛结论：lane pass 内同步 BFS + 换入 lane 槽前 await 主 pass 异步落地）。

### quadrant-prototype-handover.md
- **全部现象与疑点（viewport 被原版重置 / 发光描边 / 视锥一致即收敛 / 下排连续画面 / 玩家实体缺失 / Camera 其它状态 / 可见性 / 状态保存恢复 / `Camera.setup` 未走）** → 均已定稿：文档头 L4 声明「均已在后续排查中解释」，原型重写后 `quadrant-prototype-results.md` 验收通过。（文档头 L4、§4/§6/§7）
- **分支 `proto/four-quadrant` 与 `fabric/run` 残留物** → 已落地：分支已并入 main（e41ccb1），可安全删除。（§8）

### render-routes.md
- **渲染顺序：第二遍在主渲染之后 / GUI 之前？** → 已实测可定：挂 `GameRenderer.renderLevel` 的 RETURN（主渲染之后、GUI 之前），原型 4/16/25 画面均正常。（§5 L30-32）

### camera-state-plan.md
- **统一状态接口的最终形态** → 已定（2026-10-07）：`camera/CameraState.java` 不可变 record（六参数平铺），不活跃用 `null` 快照。定于 §6「接口定稿」。（§9 L192、§6 L119-129）
- **staged 删除的具体范围** → 已定（2026-10-07，已落地）：见 §4（L67-74 删除清单）。（§4 L56-66、§9 L196）
- **Modifier 合成模式** → 已确认：REPLACE / ADD / MULTIPLY。（§5.2 L92）
- **整屏画面「单相机替换」链** → 已落地（2026-10-07）：旧单相机替换分支 / getFov / roll 钩子已删除。（§7 L166-167）
- **覆盖链定稿（§8 九条 + §9 未结项）** → 已定（B1-a，2026-10-10）：见本文「覆盖链定稿（2026-10-10，B1-a ∪ B2 结案）」§0–§15。（§8 / §9）
- **B1 四项拍板（2026-10-10 用户拍板）** → 已定：① Modifier 缺省合成 = 偏移类 ADD（position/yaw/pitch/roll）、缩放类 MULTIPLY（fov/zoom）、REPLACE 显式 ② Base 优先级 飞行 300 > 直控 200 ③ Modifier 只作用覆盖链输出（全局相机状态） ④ 通道掩码 6 位。（见覆盖链定稿 §2 补注；`implementation-progress.md`「B1 拍板」行）
- **B2 结案（每 lane 一份 `CameraPath`/`CameraProperties`）** → 已结案（B2，2026-10-10）：不需要做，已由 lane 快照模型（`CameraLane` / `CameraState`）取代。（覆盖链定稿 §13）

### coordinate-frame.md（B5–B8 点源链定稿）
- **统一点源清单 / 偏移下放 / 注视点 / 方向锁** → 已定并落地（B5–B8，2026-10-10 ~ 10-11）：见 coordinate-frame.md §5.1（点源五形态）/ §5.4（偏移下放，旧 `relative_to`·`look_at_target` 对象形态删除）/ §5.5（`look_at` 方块来源 + `look_at_part`）/ §5.6（与 `look_at` 的叠加次序）/ §5.8（方向锁 `yaw_lock`·`pitch_lock`，`up_axis` 收编删除）/ §6（三类线规则）/ §7（待定收口）/ §8（落地顺序步骤 1-7 已完成）。
- **术语口径（身体朝向 / 视线）** → 已定（B9，2026-10-10）：见 coordinate-frame.md §2.2。

### math-models.md（B16-a 定稿 1–12）
- **数学函数模型库全部待定项** → 已定稿（B16-a，2026-10-10）：见 math-models.md「定稿」1–12（§5 十二问 + §6 七项）；定稿 12 两项拍板 = seek 后模型状态 `reset`、包名 `math`。

### transition.md（B18）
- **过渡步骤 3–8 / §7 十二问 / §8 六项** → 已定并落地（B18，2026-10-11）：见 transition.md §6「实施接线」+ §7/§8 就地标注 + §9 落地顺序；缺省打断 = blend（用户拍板），曲线走 `math` 模型层。

### hysteresis.md（B19）
- **迟滞六步 / §6 十一问 / §7 六项** → 已定并落地（B19，2026-10-11）：见 hysteresis.md「定稿与落地记录（2026-10-11，B19）」；第一版三型（含 `inertia`）、默认关、帧率无关 headless 四档 dt 实测。

### selector-model.md（B10–B15）
- **调用点隔离 / 缓存键 / 锚点 / 择一 / 通用化** → 已落地收口（B10–B15，2026-10-10）：见 selector-model.md §4.6「落地形态（2026-10-10，B15）」+ `implementation-progress.md` B10–B15 行。**已完（T16，2026-10-11）**：B15「非相机调用点接入验证」见 selector-model.md §4.6「非相机调用点接入验证（2026-10-11，T16）」。

### screen-color-adjust.md
- **数据落点（层类型/轨道）** → 已定稿：独立 ADJUST 轨道（`TrackType.ADJUST`，JSON `"type":"adjust"`）。（§4 L296、§6 L333、文首落地标注）
- **第一版参数清单** → 已定：第一批标量组（12 通道 master；后增量 R/G/B 至 15 通道）。（§6 L332、文首落地标注）
- **调整是否影响 GUI 层（字幕/letterbox/跳过提示）** → 已结清：不影响（挂点在 GUI 之前）；副作用 F2 截图带调色。（§5-3 L323、§6）
- **RGB↔HSL 转换的边界（灰点色相、饱和度钳制）** → 已结清：灰点走 `S ≤ 0` 分支返回灰度；S 钳制 0~1；亮度 Rec.709。（§5 L322、文首落地标注）

### script-i18n.md（§6 逐条落地）
- **回退链（语言族回退）** → 已落地：只做精确匹配 + `en_us` 兜底，无语言族回退。（§6-1 L141-142）
- **语言代码命名 / 文件名大小写** → 已落地：`^[a-z0-9_]{2,16}$`（严格小写），不合格按 `en_us`。（§6-2 L143-144）
- **缺 key 提示** → 已落地：运行时 debug 日志 + 原样显示引用串；编辑器黄色提示，不进校验问题列表、不阻塞。（§6-3 L145-146）
- **`@lang:` 转义** → 已落地：`@@lang:<key>` → 字面量 `@lang:<key>`。（§6-6 L151-152）
- **key 命名规范** → 已落地（部分）：`LangResources.KEY_PATTERN`；**撞键检查不做**（JSON 语义天然合并，跨脚本复用是设计意图）。（§6-7 L153-154）
- **占位符参数化** → 已落地：不做参数化，`%` 按字面渲染。（§6-8 L155-156）
- **按脚本分语言文件** → 已定：不采用，仍是全局一份。（§2.1 L48-49）
- **`@lang:` 引用语法** → 采用 `@lang:<key>`。（§2.2 L51-67）

### screen-layers.md（§8 六项已结清，见文首「落地标注」）
- **模式字段名与归属** → 已定稿：脚本级三态 `meta.hard_hide_hud`（true=hard，null/false=normal），不新增全局配置键。（§8 L187、落地标注 L21-26）
- **是否补发 `RenderGuiOverlayEvent.Pre/Post`** → 已定稿：**不补发**（理由：`@ApiStatus.Internal`、唯一消费者已由重画过滤复现、避免逐帧额外派发）。（§7 L177、§8 L188、落地标注 L43-51）
- **渲染顺序（重画层与自有 overlay 叠放）** → 已定稿：`renderHardHide` 末尾补画 `ClientEventHandler.onRenderHud`，normal 仍走 `RenderGuiEvent.Post`。（§7 L178、落地标注 L36-41）
- **模式开关候选形态（脚本 meta 三态 + 全局配置）** → 已落地：只落脚本 meta 三态，无全局键。（§5 L155）

### I-script-core/loop-execution.md（循环）
- **宏观末端实现口径** → 已落地：有限循环 = `start + 局部周期 × loop_count`，非循环 = `start + duration`。
- **`loop_start`/`loop_end` 子区间循环** → 已落地：作为宏观循环区间参数 `[a,b]` 纳入。
- **编辑器预览如何表现宏观循环 / A-B 循环关系** → 已定：预览不做循环展开/折叠，按真实时间线播放；`loop_start`/`loop_end` 即宏观循环区间参数。
- **折返那一帧的首尾衔接** → 已定（不做提示）：repeat 硬切，作者自负。
- **服务端是否跟随折叠** → 已定：不跟随。

### script-model.md
- **合成参数（opacity 等）挂在 clip 上** → 已定（随 camera-composition 步骤 1，候选 A/挂 clip 关键帧）。（§4-6 L72、§8-4 L113）

### temporal-interpolation.md
- **统一接口形态** → 已落地（步骤 1-2，2026-10-07）：`util/TimeInterpolation` 统一工具。（§7、§8）—— 事实核查的「接口形态仍待定」字样已按现行口径清理（B17，2026-10-11），口径统一。
- **实体插值迁移范围** → 已落地：`CameraTrackPlayer` 三个散落插值方法收拢迁移（60 万次逐位对照零差异）。（§7、§8）
- **相机快照 / 历史缓冲·外推 / 内部 API / 与覆盖链·B18 先后关系** → 已裁（B17，2026-10-11）：相机快照不需要、缓冲 / 外推第一版不做、内部 API 0.3.6 只留位、先后关系已文档化。（§7.1-§7.4）

### variable-frame.md
- **宽高比不一致的裁切/留黑策略、每类默认参数** → 已定稿：见 §4.1 / §3.1。（§7 L248）

### Z-editor/script-graph.md（架构图）
- **`requires` 成环的呈现** → 已落地：Tarjan SCC 缩点后分层，环内同层、标橙虚线（不算错误）。（`script-graph.md` §六）

### trigger-conditions.md
- **三项需求（条件组合 / 朝向范围 / 检测频率）** → 已确认（§5 L102-108）：条件模型（单一+组合器 any/all_of+前置锁存）、动作面两类、朝向范围按原版视线参数、最小版本只做 all_of/玩家/JSON 手写、旧格式零迁移、**轮询间隔单列按触发器/脚本可配（已定，实现未做）**。
- **检测频率按类型全局** → 已定稿（单列可配），实现未做。（§2 L43、§4.4 L90）

### feedback-0.3.5/
- **05 `on_enter` 每局只触发一次** → 已修复（0.3.6）：状态机每次轮询都执行，复位分支可达；`exit_buffer` 一并生效。（`05-on-enter-not-repeatable.md` L41-43、`README.md` §六-05）
- **04 触发器→命令延迟（可配项）** → 部分已调（config）：`triggerPollInterval_location` 20 → 2。（`04-trigger-latency.md` L14、`README.md` §六-04）
- **parallel-playback 已知缺陷「`emergencyStop()` 不清队列」** → 已修正（`deactivateNow` 先 `reset()` 清空 `scriptQueue`）。（`parallel-playback.md` L233）
- **parallel-playback「`C2SPlaybackStarted` 无条件回报」** → 已修复（2026-10-07，ACK 与播放账本拆分）。（`parallel-playback.md` L234）

### D 轮（2026-10-17）
- **scene-transition D1–D3** → 已收口：C 为最终方案（纯显式 clip / 关键帧，无新字段 / 新轨 / 运行时生成器）；wipe = CAMERA `mask_shape` full/rect/circle + source/dest 关键帧；z 序 = 显式 `z_index`；叠化 = 底 A 不透明 + 上 B 淡入。见 `scene-transition.md` §3–§9。
- **overlay-color-mask D4–D7** → 已收口：渐变 / 五混合 / 局部矩形圆形 / 高对比 z 序演示。见 `color-mask-execution.md`。
- **variable-frame D8 / D10** → 已完成。见 `execution-round.md`（历史轮）与 `variable-frame.md` 文首。

### F 轮（2026-10-17 实现 / 2026-10-18 光影画面；余项见 §一）
- **F2 队列接播** → ✅ 实现完成 + 实机覆盖（2026-10-18）：出队按 priority/FIFO 逐项判定可播性、无 exclusive 一次排空、同脚本排队副本不被误启动、预览隔离、去掉容量 8；无头探针 68/68；队列接播实机已由 C 轮覆盖（exclusive 阻塞 → 两请求入队 result=2 → FIFO 接播三连命中）。
- **F3 Iris/Oculus** → 实现完成（2026-10-17）：删除 `isUnavailable()` 整条禁用，lane 始终渲染；检测保留用于跳过 `setupRender` 专属 mixin、走优化器 Camera/Frustum 路径。**2026-10-18 修复光影 lane 跨视角污染**：per-view TAA 历史（自动探测目标）、`shadowcolor` 按视角清除、深度重绑、切换光影包重置缓存；Fabric/Iris 1.7.6 下 MakeUp / Complementary / BSL 三包 A/B 干净。**F3 验收通过（Fabric/Iris 1.7.6 三视角 + 热切换；Forge/Oculus 1.8.0 同 jar 已部署）**；④ 由 Sodium 实机覆盖、⑥ 由 F5 矩阵覆盖，⑤ 与非 `SRGB` 色彩空间为已知限制。
- **F5 帧耗时统计** → ✅ 实现完成 + 矩阵完整（2026-10-18）：诊断会话 + 21 支脚本；`perf_l0` / `perf_l1` / `perf_l4` / `perf_l16` / `perf_l25` 的 FULL 与 WORLD_ONLY 均 600/600（l25 在 gzip 传输修复后补齐）；per-view 历史拷贝成本已包含在复测数据中。
- **失败处理审计** → 只读审计完成（`failure-handling-audit.md`）：60 findings / 23 P0、16 处空 / 仅注释 catch；`PacketGuard` 与断线清理未实现，按 P0/P1/P2 分批整改——归 F 轮收口。

### 跨文档已结清
- **scene-transition「转场是否影响 GUI 层」** → 已结清：不影响（共用 `screen-color-adjust.md` §5-3 的结论）。（`scene-transition.md` §6 L118）
- **quadrant-perf/summary.md「≈1.2–1.4 × 主画面」比值** → 已删除（summary.md L17 已改；`multi-camera-rendering.md` 事实核查 L268「未改动」的说法已过时）。（`quadrant-perf/summary.md` L17）

---

## 三、明确不做 / 作废 / 延后（不算未定，供核对）

- **feedback 01 / 02（游戏内编辑器区域模式、编辑器 vs 脚本格式能力缺口）** → ⛔ 随游戏内编辑器退役作废（0.3.6 删除 `editor/` 包）；02 留档为对账检查单。（`feedback-0.3.5/README.md` L8-9、`01-*` L4、`02-*` L4）
- **feedback 04 #1（触发器直接执行命令）、#3（事件时间线乐观启动）** → 暂不做（#3 自带「不建议」，破坏暂停同步）。（`04-trigger-latency.md` L19/L23）
- **画面层针对性 mixin 兜底、`hud_layers` 键规范、Fabric 等价能力** → 本版本明确不做（留作将来最后手段 / 维持现状 / Fabric 不做）。（`screen-layers.md` §5/§7）
- **架构图：触发器·EVENT 边、反向编辑、增量刷新、布局持久化** → 本版未做（后置/远期）。（`Z-editor/script-graph.md` §七）
- **循环宏观 pingpong** → 延后（见第一节，属延后项而非否决）。
- **script-i18n 步骤 3（lang 编辑面板）** → 本期可选、后置、未做。（`script-i18n.md` §7 L160-169）
- **screen-color-adjust 第二批 / 第三批 / 调整层 / 编辑器 UI** → 本版本明确不做（属后续批次）。（`screen-color-adjust.md` 文首状态行 L13、L117-120）
- **screen-color-adjust 限定器（qualifier）/窗口/跟踪器** → 不做，远期另议。（§3 L278）
- **parallel-playback 单 lane 全屏优化（保留点②）** → 未保留：一律走全 lane 渲染（每 lane ≈3.4–3.6 ms）。（§3.3 L90）
- **parallel-playback 触发状态机按实例维护** → 不做：状态机键保持（玩家 + 脚本 + 触发器），与同脚本单实例一致。（§3.7 L111）
- **overlay-color-mask 颜色调色/滤镜** → 已移交 `screen-color-adjust.md`。（`overlay-color-mask.md` §2.2）
- **variable-frame D9（文本层取材 / 字幕裁切）** → **不做**：字幕完整呈现、无裁切需求；`source` 只用于图片与相机画面 lane，`fit` 不恢复。（`variable-frame.md` 文首、`execution-round.md` 历史轮）
- **quadrant-prototype 原型文件（QuadrantProto 等）** → 已删除/正式化为 `client/lane/LaneRenderer`（2026-10-07）。（`quadrant-prototype-results.md` §7 L146-150）
- **F4 视距档位（`render_distance` 等脚本字段）** → **不做**：lane 视距自动跟随玩家设置，不新增脚本开关。（`F-playback/multi-camera-rendering.md`；`implementation-progress.md` F4 行）
- **`plans/0.3.6/README.md` §五 七篇的 🔒「长期讨论」标记** → 口径修正：本目录全部文档 = 0.3.6 工作范围；「🔒 长期讨论」已废止为**历史标记**，0.3.6 工作方式 = **边做边讨论**、随批次就地销项（2026-10-11 用户口径），标记应视为「未实现」而非范围外。（`README.md` §五；审计 §六）

---

## 四、备注：文档滞后与口径冲突（非「未定」，但影响清查准确性）

1. ✅ **已解决（B 轮）**：`multi-camera-rendering.md` 文首状态行已重写为区分「渲染底层已正式化（2026-10-07，见 §12）」与「待开工：上屏合成 / 脚本·lane 注册表接入」，与 §12 一致，不再自相矛盾。
2. `camera-state-plan.md`：`CameraManager.onRenderFrame` javadoc 挂点仍写 `LaneRendererMixin`（实际 `GameRendererMixin`）。
3. ✅ **已解决**：`plans/0.3.6/README.md` 索引已按当前状态标注（编辑器主体已实现，剩余工作单列）；`feedback 03` 已于 2026-10-09 回写为 ✅ 已实现（证据见第一节 03 条）。
4. `feedback-0.3.5/README.md` 状态列：03 已于 2026-10-09 回写为 ✅ 已实现（证据见第一节 03 条）；04 #2 未实现未反映（已列入第一节）。
5. ✅ **已解决（B17，2026-10-11）**：`temporal-interpolation.md` 事实核查的「接口形态仍待定」字样已按现行口径清理（只写现行，指向 §7 B17 结案），与 §8 落地记录不再冲突。
6. `multi-camera-rendering.md` 事实核查 L268 称 `quadrant-perf/summary.md` 比值句「未改动」，实际该句已在 summary.md L17 删除。
7. `docs/SCRIPT_FORMAT.md:59-63`、`docs/AI_SCRIPTING_GUIDE.md:125` 等仍写已删字段（见第一节 feedback 02）。
8. `mod-architecture-diagram.md` 未纳入 2026-10-07 事实核查（`README.md` 事实核查说明）。
