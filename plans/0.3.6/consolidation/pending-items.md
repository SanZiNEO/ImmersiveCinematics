# 0.3.6「待讨论」条目完整清单（截至 2026-10-18）

> 口径：只列「仍未定 / 仍需拍板 / 实现未做」的条目；已销项、已定稿、已落地的只保留一行说明。
> 状态标注：✅ 已定/已落地　🔵 方向已确认但实现未做　🟡 仍开放未定　👤 需用户拍板。
> 与 `pending-discussions.md` 的区别：本文已把 A/B 轮销项全部剔除，只留「当前真实未决」，并归并到对应批次。

---

## 〇、待拍板项（已于 2026-10-12 全部拍板，详见 `decisions.md`）

| # | 条目 | 决策 |
|---|---|---|
| ✅1 | P3-impl 触发器连续化默认档 | **默认连续**（升级模型：点状态→连续参照，服务脚本触发 + 条件判定系统） |
| ✅2 | variable-frame 画幅层 pillarbox | **不存在此问题**（lane 合成/采样均按窗口尺寸，无固定采样尺寸） |
| ✅3 | `meta.base_resolution` 校验上限 | **不设上限**（不限制作者） |
| ✅4 | condition-system 判定语义 / switch_variant | **「如果满足 xx 条件，就执行 xx」**（条件→动作）；等待点=「脚本内执行到 xx 时间」条件特例；**switch_variant 做、数据化进 JSON**（形态待讨论，可放 v2） |
| ✅5 | playback-relations 打断形态 / 队列 | **打断硬切**；**队列不设上限、按脚本控制的排队语义** |
| ✅6 | `/icinematics template` 命令 | **删除**（模板展开归编辑器） |

> 其余子项（命名、轻量变量、rules 与 meta.triggers 是否统一 schema、`interruptible` 与 `play_mode` 是否合并、interrupt 队列处置等）各文档已给「本篇」答案/提案，按文档执行，落地时随对应批次定稿。

---

## 一、播放与画面架构（未决项）

### parallel-playback.md
- ✅ **队列按脚本匹配接播**（`ScriptQueue` 无匹配 API）→ **F2 实现完成（2026-10-17）+ 实机覆盖（2026-10-18）**：出队按 priority/FIFO 逐项判定可播性、无 exclusive 一次排空、同脚本排队副本不被误启动、预览隔离、去掉容量 8；无头探针 68/68；队列接播实机已由 C 轮覆盖。
- 🔵 **听者「后来者居上」口径待对齐**（`AudioListenerController` 已按顶层，`hasActiveCameraClip` 为并集）→ 归 **F 批**，仍开放。
- 🟡 **多实例 AUDIO 空间锚点**、**跳过投票 UI**、**实例与 Base Provider 对应关系**、**区块预加载 ticket 并集**、**实例 id 网络协议形态** → 涉及并行播放放开（步骤 5/6），随 F 批收口，仍开放。
- ✅ 已销项：同脚本重入保持单实例（§〇 用户裁决）、互斥组不做、队列三态语义（被 `playback-relations.md` 吸收）。

### camera-composition.md
- 🟡 **dest 矩形之外无 lane 覆盖时的兜底**（露原版 vs 黑场）。
- 🟡 **编辑器预览多 lane 合成表现**（步骤 6 未做）。
- 🟡 **与 HUD / letterbox 的叠放顺序**。
- 🟡 **主 lane 判定规则**（听者归属；与 parallel-playback 一起定）。
- 🟡 **跨脚本 lane 寻址**（候选 B「合成导演」的前置）。
- ✅ 已销项：合成参数归属（A 自声明）、pip 层删除、order 冲突规则、叠化预热=重叠窗口。

### multi-camera-rendering.md
- 🟡 **副画面是否支持 look_at / tangent 朝向**。
- 🟡 **叠化期间相机 lane 是否可移动**。
- 🟡 **光影（Iris/Oculus）能否拿「已处理后的画面」** → **F3 实现完成（2026-10-17 / 画面修复 2026-10-18）**：lane 始终渲染，每 lane 跑完整光影链；跨视角 TAA 历史与 `shadowcolor` 污染已修，Fabric/Iris 1.7.6 三包 A/B 干净。**F3 验收通过（Fabric/Iris 三视角 + 热切换实机；Forge/Oculus 同构移植、同 jar 部署）**（§7 L144、§12.5 L350、事实核查 L284；`render-routes.md` §4）。
- ✅ **Sodium 下两遍 / 帧 `frame++` 语义** → Sodium 0.5.13 实机 A/B 与 F5 十档均无缺块 / 闪块；🟡 **Rubidium 类名核对**为已知限制（检测按入口类名覆盖，类名缺失会 `defaultRequire:1` 启动期失败）。（§11 注 L207、事实核查 L283/285）
- ✅ 已销项：渲染顺序（renderLevel RETURN 已实测）、每 lane 独立可见集合（B3）、预热时长、性能模组检测策略、alpha 透底（ic_lane_blit）、per-lane 槽归槽。

### scene-transition.md
- 🟡 **转场数据落点 A/B/C**（倾向 C 先行 / A 默认 / B 远期）→ 归 **D1**。
- 🟡 **第一版是否纳入 wipe** → 归 **D2**。
- 🟡 **转场时长/曲线默认值**、**转场期间时间线语义**、**lane opacity 插值曲线**、**跳过/打断交互** → 归 D 批。
- 🟡 **z 序**（fade z=10 压不住字幕 z=30）→ 归 **D3**。
- 🟡 **叠化期间相机 lane 可动**（沿用 multi-camera §9）。
- ✅ 已销项：GUI 层不受影响、cut/叠化/黑场白场数据链路已验证。

### variable-frame.md
- 🟡 **画面层 z 序基线**（应在 letterbox 之下，现 letterbox z=0 已最小）。
- 🟡 **旋转是否纳入**、**小尺寸画面是否按显示尺寸渲染**。
- 🔵 **画面层并入统一覆盖层模型**（步骤 3；或结案回写）→ 归 **D8**。
- ✅ 已拍板：画幅层 pillarbox 不存在、`base_resolution` 不设上限（见 §〇）。
- ✅ 已销项：统一几何模型（§4.2 取代参考画布）、素材无内在尺寸取材语义、取材超边界钳制、dest 与画布无换算。

### script-model.md
- 🟡 **参数寻址到分量的编辑器呈现**（每分量一条曲线 vs 合并）→ 归 **G1**。
- 🟡 **关键帧级插值后 clip `interpolation` 字段去留**（部分已定：字段已删）。
- 🟡 **meta 关键帧化边界**（哪些 meta 不适合）→ 归 **G3**。
- 🟡 **时间精度迁移代价**（是否高精度）→ 归 **G4 / clock-abstraction**。
- 🟡 **参数声明与 FieldDef 合并方式**、**关键帧数据规模**、**keyframable 声明位置** → 归 **G2/G3**。
- ✅ 已销项：关键帧字段形态（方案 E：缓动=编辑器烘焙、手柄归编辑器）、合成参数挂 clip、时间重叠模型。

### script-loop.md
- 🟡 **折返瞬间动画/事件帧对齐**、**长跑虚拟时钟精度**（float 强转单精度）、**触发式脚本第 0 秒起点** → 部分归 **G4 / clock-abstraction**。
- 🔵 **宏观 pingpong**（计划延后项）。
- ✅ 已销项：宏观末端口径、子区间循环、预览不展开、服务端不跟随折叠、折返硬切。

### F-playback 轮（2026-10-17 实现 / 2026-10-18 光影画面修复）
- ✅ **F2 队列接播** → 实现完成（2026-10-17）+ 实机覆盖（2026-10-18），无头探针 68/68；队列接播实机已由 C 轮覆盖。
- ✅ **F3 Iris/Oculus** → 实现完成 + 验收通过；Fabric/Iris 1.7.6 光影 lane A/B 于 2026-10-18 通过（MakeUp / Complementary / BSL 三包，绿边 / 缝隙 / 扇形消除）；**F3 验收通过（Forge/Oculus 同构移植、同 jar 部署）**；④ 由 Sodium 实机覆盖、⑥ 由 F5 矩阵覆盖，⑤ 与非 `SRGB` 色彩空间为已知限制。
- ⊘ **F4 视距档位** → **不做**：lane 视距自动跟随玩家，不新增脚本字段。
- ✅ **F5 帧耗时统计** → 实现完成 + 矩阵完整（2026-10-18）：诊断会话 + 21 支脚本；l0 / l1 / l4 / l16 / l25 的 FULL 与 WORLD_ONLY 均 600/600；per-view 历史拷贝成本已包含在复测数据中。
- 🟡 **失败处理审计** → `failure-handling-audit.md`（只读）**60 findings / 23 P0**，16 处空 / 仅注释 catch；`PacketGuard` 与断线清理未实现，按 P0 / P1 / P2 分批整改。
- 🟡 **Iris 已知限制**：⑤ Rubidium 类名核对、③ 非 `SRGB` 色彩空间；①② 已由实机 A/B 收口，④ 由 Sodium 实机覆盖，⑥ 由 F5 矩阵覆盖。
- 🟡 **合成 / 并行 / 多相机开放项**：见上文 `camera-composition.md`（dest 外兜底、编辑器预览多 lane、HUD/letterbox 顺序、主 lane、跨脚本 lane 寻址）、`parallel-playback.md`（听者后来者居上、多实例 AUDIO 锚点、跳过投票 UI、实例/Base Provider、ticket 并集、实例 id 网络）、`multi-camera-rendering.md`（lane look_at/tangent、叠化期间相机 lane 移动）。

---

## 二、编辑器

### editor-webui-migration.md
- 🔵 **飞控「选中片段全屏 lane 渲染」**（依赖多相机渲染 + 并行播放步骤 5）。
- 🔵 **预览与游戏内播放通道隔离**（属并行播放步骤 5）。
- 🔵 **多 lane 合成在编辑器预览中的表现**（跨文档引用 camera-composition §5）。
- 🟡 **远期/可选**：H.264/WebRTC、飞控远程面板、动效。

### editor-script-graph.md
- 🟡 **大包渲染规模**（canvas vs svg，留到真实大包再定）。
- 🔵 **布局持久化形态/存储位置**（步骤 5 未做）。

### templates.md
- 🟡 **模板参数与运行时基准关系**、**三层传参形态**、**自定义纯数据模板**、**生成结果可编辑性**、**版本化**、**编辑器呈现**、**注册表 id 命名空间**、**脚本级/触发器模板关系**、**第一批清单** → 已整体归 Z 轮（E0 重审后）。
- ✅ 已销项：参数声明复用 FieldDef、嵌套引用归编辑器。

---

## 三、触发器与运行时表现

### trigger-conditions.md
- 🔵 **组合器嵌套（多层 all_of/any）** → 归 **C1**。
- 🟡 **前置条件↔脚本状态口径**（「播完」是否含跳过/打断、状态归属谁、并行播放下按玩家×脚本 id 还是实例）。
- 🟡 **取反（not）是否纳入**。
- 🔵 **检测频率按触发器/脚本可配** → 归 **C3**（与 P3-impl 步骤 4 合并）。
- 🔵 **`death` 触发器类型** → 归 **C2**。
- 🔵 **组合器编辑器 UI** → Z 轮。

### region-sync.md（框架讨论，零实现，归 H1）
- 🟡 **接缝/触发精度**（每 tick vs 沿用轮询间隔）、**镜像·旋转朝向策略**、**落点安全检查**、**多规则重叠优先级**、**载具中玩家**、**一对多/随机目标**（先一对一）。

### wait-point-track.md（已被 condition-system.md 吸收，TBD 移接）
- 原文 8 条 TBD（action 语义 / 超时 / 事件树 / 多玩家 / UI 事件 / 外部事件 API / 等待期其它轨道 / 编辑器可视化）→ 已并入 `condition-system.md` §2/§3/§9，实现归 **H2**（前置 C2 + P1-impl + P3-impl）。

### overlay-color-mask.md
- 🟡 **渐变是否做**（D4）、**混合模式是否做+优先级**（D5，⚠ 与 screen-color-adjust 的 `blend_mode` 边界待明确：遮罩层 vs 调整层）、**局部遮罩是否必须**（D6）、**字段兼容形态**（扩展 `fade` vs 新增类型）、**WebUI 面板**。

### screen-color-adjust.md
- 🟡 **曲线关键帧形态 a/b**（倾向先 b 后 a，b 已落地，a 归编辑器）。
- 🟡 **shader 曲线数学**（LUT 256×1 vs shader 贝塞尔求值）。
- 🔵 **光影（Iris/Oculus）与我们 pass 的先后** → 归 F3/P2。
- ✅ 已销项：多实例 master 合并（A13' 按层级叠加）、RGB↔HSL 边界、GUI 层影响、数据落点、第一版参数清单。
- 🔵 **编辑器曲线编辑器与色轮实现形态** → Z 轮。

### hud-hard-hide.md
- 🟡 **§7 遗留 4 条**（其它模组 Pre 副作用被跳过 / 原版 overlay 重画记账一致性 / 编辑器预览·暂停·退场动画副作用 / Sodium·Embeddium 兼容实机）——hard 模式固有代价 + 实机未验证。
- ✅ 已销项：模式字段三态、不补发 RenderGuiOverlayEvent、渲染顺序、开关形态。

---

## 四、相机底层（B 轮已落地，仅剩「遗留 + 长期开放」）

> 本组 8 篇的方向/待定项已由 B1–B19 全部销项。以下只列「落地后仍开放」的残留，多数不阻塞后续批次。

- 🟡 **camera-state-plan §2 补注**：直控层「压制脚本导演」是名义层（`DirectControlBase.isBaseActive()` 仅直写瞬为真），需「各层各持自有 record」后才生效——未安排。
- 🟡 **camera-state-plan T23 遗留 ①②**：① 无活跃实例时快照恒 `null`（直控无画面，候选门改「链输出非 null 即快照」）；② 飞控 tick 挂 `WebPreviewScreen.render` 晚一帧（候选前移帧首）。
- 🟡 **transition.md §6 迁移门槛 ①②**：①「主 lane 无条件取链输出」未满足；②「层应用点移到 refreshCameraState」未做——过渡层当前留在 Base 写入侧，与裁定 4「链后」有已知偏差。
- 🔵 **transition 实机抽验 ④打断 / ⑤链式**未做 → 挂账 **H6**。
- ✅ **camera-core-split §三 P2**：接播（pendingScript/ScriptQueue）路径实机已由 C 轮队列接播覆盖（2026-10-18）。
- 🟡 **coordinate-frame 仍开放 7 条**：`look_at=none` 假目标点、偏移关键帧插值、编辑器呈现、多点源/方向源每帧性能、方向源是否拆分两个独立来源、同一关键帧多坐标系、外部 API 时机。
- 🟡 **temporal-interpolation §6 未标注 6 条**：partialTick/alpha 来源、固定步长 vs 渲染帧 delta、角度数值域插值规则、与数学函数模型边界、确定性/重放、零分配——已被 math-models 定稿 8/9 / 覆盖链定稿 §0 旁证约束，仅未就地回写。
- ✅ 已销项（B 轮全部）：覆盖链、统一点源、方向锁、选择器通用化、数学库、时间插值裁定、过渡、迟滞、核心拆分。

---

## 五、其余文档（未决项）

### state-tracking.md（P1 方向稿，实现未做）
- 🟡 §6.4 待定 12 条（事实类型清单/字段形态/查询原语/时间基准/触发器身份/enterStates 去留/淘汰策略/旧 SNBT 存档处理/「为什么触发」粒度/S2C 包形态/与 trigger-conditions §5 归属/事实层是否暴露）。
- 🟡 §2.6 已知空指针缺陷（`ScriptPlayback.server` 从未赋值）→ 归 P1-impl 步骤 4 修。

### trigger-continuity.md（P3 方向稿，实现未做）
- 🟡 §9 待定 12 条，其中 **👤 默认档** 需拍板（见 §〇）；其余（原语清单/dwell 单位/子步数/传送载具判定/observation 是否连续化/冻结语义/组合器窗口/与 P1 接口/状态型净变化/编辑器呈现/性能预算）执行时定。

### iris-oculus-compat.md（P2 调研计划，部分落地）
- 🟡 §5.2 剩余待定（lane 目标形态、Rubidium 结论来源、性能档位是否另立）；禁用策略已否决、`isUnavailable()` 已删除。
- 🔵 核心可行性实测未做（缺 Iris/光影包环境，已记录缺件清单）。

### clock-abstraction.md（G4 方案，步骤 1 已落地）
- 🟡 §6 待定 3 条，其中 §6-1（Clock 放 util/ 还是 camera/）**实际已按 util/ 落地，应回改标注「已定」**；§6-2（getElapsedSeconds 改 double）已按 double 落地；§6-3（1.20.2+ tick 可调）归多版本待办。

### condition-system.md / playback-relations.md / point-source-time-mode.md（批次 C 新设计）
- 设计已定稿（§7/§9 的「待拍板」项均已有「本篇」答案，见 decisions.md），实现归 C6/C5/C7；point-source-time-mode §5 字段枚举命名实现时定。

### code-comment-style.md（CLEANUP-3，未执行）
- 🟡 §6.1「收敛批次是否登记进 implementation-progress」由主代理定。

### multi-instance-black-screen.md（已结案）
- 🟡 唯一残留：F1「失败时返回前有效结果」语义去留（已指向进度日志，先不动）。

### mod-architecture-diagram.md
- 🟡 **WAIT_POINT 轨道节点已过时**（condition-system 已定「不新开轨道、等待点由阻塞规则 `until` 表达」），图中节点需改为判定规则阻塞语义。

### feedback-0.3.5/
- 🟡 **02 §六「文档与代码不同步」仍存活**：`docs/SCRIPT_FORMAT.md` / `AI_SCRIPTING_GUIDE.md` / `docs/modules/editor.md` / `docs/modules/script.md` 仍写已删的 `preload` / `camera_mob_*` 字段——**唯一不随编辑器退役作废的活待办**。
- 🔵 **04 #2 轮询间隔按触发器/脚本可配** → 已归口 `trigger-conditions.md` 需求 3 / C3。
- ✅ 已销项：01/02 作废、03 exit_buffer 归一化、05 on_enter 复位、04 #1 命令归 EVENT 轨。

---

## 六、汇总统计

| 类别 | 数量 |
|---|---|
| 需用户拍板 | 已全部拍板（6 项，见 §〇 / decisions.md） |
| 仍开放未定（🟡） | ~60 条，归并为约 20 类；F 轮余项见 §一「F-playback 轮」（失败审计、Iris 已知限制） |
| 方向已确认、实现未做（🔵） | 覆盖批次 C/D/F/G/H 全部待办；F2 队列接播已实现并实机覆盖 |
| 已销项（A/B 轮 + 各批已落地） | 其余全部（含 F4 不做、F3 光影画面 A/B） |

> 说明：🟡 未定项绝大多数属「执行时再定」或「Z 轮呈现」，随对应批次（C/D/F/G/H/P1-impl/P3-impl）落地时定稿即可；需提前决策的项已全部拍板（§〇）。
