# I 轮验收矩阵

> 状态：未实现
> 范围：对象模型、schema、循环、格式清理、迁移、编辑器、真实机器/性能的验收项
> 关联：`object-model.md`、`schema-and-types.md`、`runtime-evaluation.md`、`loop-execution.md`、`format-cleanup.md`、`migration.md`、`editor-object-model.md`

## 一、原则

- 用真实 parser/validator/sampler/渲染探针，不用 mock 断言；数据探针输出 `OK/FAIL` 计数。
- 每个行为至少一个正向 + 一个边界/负向；格式清理/迁移要有全仓扫描。
- 迁移以“旧运行时 vs 新运行时”的可观察结果为准；不是“结构看起来对”。
- 真实机器：Fabric 跑 fixture（`result=1` + 自然结束时间）；Forge 编译/加载；GL 探针不回归。
- 性能：F5 矩阵口径（600/600 per lane tier）不退化；对象采样不引入每帧分配。
- 所有验收脚本/探针进仓库；一次性脚本用完删除。

## 二、数据模型 / schema

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| M1 | 对象基本形态 | parser 读 `objects`，含 `value`/keyframes/空组/缺省 | 采样值符合 §3.4 |
| M2 | 路径唯一/未知路径 | 构造重复/未知/空段路径 | 加载期错误，信息含路径 |
| M3 | 同对象时间递增 | 重复/倒序时间 | 错误；不同对象同时间通过 |
| M4 | 非有限值 | NaN/Infinity | 解析期拒绝 |
| M5 | 超界关键帧 | `time<0`/`time>duration` | 忽略、不报错；最后一帧 in-range hold |
| M6 | 单帧/空组/缺省 | 单 keyframe、空 keyframes、无对象 | 恒值/对象值/schema 默认 |
| M7 | 插值 | linear/angle/geometric/step | 数值/角度/缩放/台阶符合定义 |
| M8 | 可空锁 | `yaw_lock`/`pitch_lock` null↔数值 | step 切换；两个非空角度最短路径 |
| M9 | 必填/默认 | required 无值/无默认；可选无默认 | required 错误；可选缺省可加载 |
| M10 | schema 一致性 | `TrackSchemas` vs 编辑器快照 vs 文档表 | 路径/类型/默认/插值集合一致 |
| M11 | `appliesTo` | OVERLAY fade/image/subtitle 错层对象 | validator 拒绝/警告按定义 |
| M12 | 旧字段 | `keyframes`、`meta.version`、`total_duration`、AUDIO `loop`、旧 clip 级 `curve` | 错误“已移除”；旧 3D `curve` 由迁移器转成 `objects.curve`（`axes:["x","y","z"]`），parser 不双读 |
| M13 | `curve` 成组 | 有 `curve` 时三轴关键帧时间一致/缺一个轴/时间不对齐；无 `curve` 时三轴独立 | 一致且三轴有值通过；缺值/不对齐错误；无 `curve` 独立通过 |
| M14 | 位置点源一致性 | `absolute`+非空 `position_origin`、`absolute+base`、`position_origin`/`position_origin_freeze`/`facing_source` 切换点、`base` 下 `facing_target`/`facing_entity` 切换点无位置关键帧、`auto` 下 `facing_target`+`facing_entity` 同时非空、`world` 下写 `facing_*` | 冲突组合错误；合法静态/动态点源组合通过；`auto` 按载荷/原点回落（实体/隐式 @p/player → entity，坐标/方块/结构 → world）；显式 `world`/`entity`/`line` 覆盖；未选中载荷警告；world 下方向源字段警告；切换点缺三轴关键帧错误 |
| M15 | 旧位置字段 | `follow`/`follow_selector`/`facing_origin`/`facing_origin_x/y/z`/`facing_origin_time`/`selector_*_follow`/`selector_*_facing_origin` | parser 报“已移除”；迁移器输出 `position_origin`/`position_origin_freeze`/`selector_*_position_origin`，旧 base 方向补 `facing_source=entity` + `facing_entity`（原点已是该实体/玩家时可省） |

## 三、运行时采样

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| R1 | 同一 t 采样 | 多字段不同 keyframe 时间；探针记录采样 t | 所有字段用同一 `executionLocalTime` |
| R2 | 无共享 from/to | 对象 A 关键帧时间不影响对象 B | B 的采样只由 B 的 keyframes 决定 |
| R3 | 零分配 | 连续采样 10k 帧，统计分配/GC | 稳态无每帧临时 map/字符串/装箱（允许游标缓存） |
| R4 | 静态片段 | 无 keyframes 的 CAMERA/OVERLAY/AUDIO/ADJUST | 正常出画面/声音/调色 |
| R5 | 超界不影响可用性 | 超界 keyframe 的点源解析失败 | clip 仍可用 |
| R6 | 采样器纯函数 | 同输入重复采样/seek | 结果一致；无隐藏状态 |
| R7 | 多实例 | 同脚本两个播放实例 | 采样缓冲/状态隔离 |

## 四、CAMERA

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| C1 | 位置点源/偏移 | 无点源 world；静态点源 world/base；动态点源（`position_origin` 实体 + `position_origin_freeze=false`）world/base；base `facing_source=auto` | 世界位置符合点源模型；绝对坐标无点源；base 原点/方向源按 `auto` 回落正确 |
| C2 | 点源/偏移切换 | 静态/动态点源、world/base、`position_origin_freeze` false/true、`facing_target_freeze`/`look_at_freeze`/`yaw_base_from_freeze`/`yaw_base_to_freeze` false/true、`facing_source` 切换、非法组合、切换点无位置关键帧、`auto` 下 `facing_target`+`facing_entity` 冲突 | 合法组合按点源模型求值；`absolute`+非空原点、`absolute+base`、`auto` 双载荷错误；显式方向源未选中载荷警告；切换点缺三轴关键帧错误 |
| C3 | 动态位置原点 | 跟随移动实体 | 位置=实体脚底+偏移；位置原点不自动成为 `look_at` 目标；平滑/冻结按现行 |
| C4 | position_origin/facing 方向源 | 点源坐标/实体/方块/结构；`facing_source` auto/world/entity/line；`facing_entity`/`facing_target` 载荷 | 基准点/前轴正确；`facing_entity` 只取 body yaw 且无冻结开关；显式方向源覆盖 auto；未选中载荷不参与；方向源只有一个 |
| C5 | look_at none/manual | 无 `look_at` 目标、yaw_base world/entity/line、锁 | 手动朝向=base+lock+offset；`look_at=none` 仍保留 100 格伪目标插值语义 |
| C6 | look_at target | player/entity/block/coordinate | 朝向指向目标；动态目标逐帧解析 |
| C7 | selector 策略 | 7 调用点 × 6 字段，通用/专属覆盖 | 解析/回落符合 `SelectorSchema` |
| C8 | yaw/pitch/roll/fov/zoom | 角度/线性/几何插值 | 角度最短路径；zoom 几何 |
| C9 | 静态相机 | 无 keyframes | 有 lane、有画面 |
| C10 | morph | transition 各曲线/时长/打断 | A/B 混合符合 B-camera 语义 |
| C11 | motion/breath | 各模型/参数 | 与现行一致；执行边界不重置 |
| C12 | modifier 链 | 多 modifier/优先级/suppress | 全局状态按链；lane 参数保留 |
| C13 | lane 合成 | opacity/dest/source/mask/调色 | 与相机状态同一 t；循环不错位 |
| C14 | 维度 | 同维度/跨维度 | 与 `cross-dimension-camera.md` 一致 |
| C15 | curve/orient/offset | 轴集合 2/3 轴、顺序无关（`["y","x"]`）、3D 控制点、三轴成组、动态原点/base 方向、切线、offset keyframes | 2 轴做平面 2D 弧长、只取对应两分量；3 轴做 3D 弧长、取三分量；有 `curve` 时三轴关键帧成组；动态参考系下 curve 仍在段空间内求值、不退化；offset 只在 tangent 读取；不烘焙 |

## 五、OVERLAY / LETTERBOX

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| O1 | fade full/rect/circle | 形状/几何/透明度 | 与现行像素/区域一致 |
| O2 | fade gradient | linear/radial/角度/中心/半径 | 与现行一致 |
| O3 | fade color | 三通道独立关键帧；基色回落 | 颜色/插值正确 |
| O4 | image | source/anchor/scale/x/y | 取材/缩放/定位正确 |
| O5 | subtitle | font_scale/scale/alpha | 字号/位置/alpha 规则不变 |
| O6 | z_index | 多图层加入顺序 | 加入时排序；运行中改 z 不重排 |
| O7 | letterbox | aspect/opacity/淡出 | 与现行一致 |
| O8 | 静态图层 | 无 keyframes | 正常显示 |
| O9 | 循环 | 循环片段按次数执行 | 每次执行按 `executionLocalTime` 求值；执行边界不额外重置图层/淡出 |

## 六、AUDIO / ADJUST

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| A1 | audio volume/xyz | 关键帧音量/位置 | 与现行一致；无 keyframes 时用字段 `value` 基量 |
| A2 | file_loop vs 执行 loop | 两层循环组合 | 文件循环在实例内；执行边界仍活跃的实例不重启，不再活跃的下次执行新建 |
| A3 | position_mode | relative/absolute | 相对跟随/世界坐标正确 |
| A4 | category/attenuation | music/ambient/none/linear | 与现行一致；inverse 按文档边界 |
| A5 | listener | meta.listener camera/player | 与现行一致 |
| A6 | ADJUST 59 通道 | 每通道单测/组合 | 数值/顺序与 A 轮一致 |
| A7 | 曲线/LUT | 4+6 曲线、LUT/输入 gamma | 强度/栈位不变 |
| A8 | blend_mode/amount | 5 模式 × 0/1 量 | 与 A 轮一致 |
| A9 | lane vs master | CAMERA 57 通道 + ADJUST 59 | 各作用各的，不互相覆盖 |
| A10 | 无限片段 | duration<0 / loop_count=-1 | local time 前进，不冻结 |

## 七、EVENT / MOD_EVENT

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| E1 | command 触发 | 单事件/多事件/空 command | 到点触发一次；空占位不执行 |
| E2 | 事件身份 | 多实例/多 clip/seek | 不重复/不漏；`EventKey` 稳定 |
| E3 | position 配对 | `{x,z}`/`{dx,dz}` | 同一记录原子配对；玩家移动正确 |
| E4 | 暂停/挂起 | 暂停在事件前后 | 时间线冻结；恢复不补触发 |
| E5 | 片段循环 | repeat N 次 | 服务端 EVENT 只触发一次，不按执行次数重复 |
| E6 | pingpong | EVENT/MOD_EVENT | validator 拒绝 |
| E7 | MOD_EVENT | 记录格式/占位 | 可加载/校验；运行时不消费 |

## 八、循环 / 格式清理

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| L1 | windowEnd | 有限/无限/不循环 | `start + duration × N`；无限 ∞ |
| L2 | executionLocalTime | 边界/跨执行 | `[0,duration]`；每次执行重新从 0 起；无全局折叠 |
| L3 | pingpong | 正向/反向/边界 | 奇偶执行方向；值连续；非视觉轨道拒绝 |
| L4 | 消费/内容事实 | 有限 N/无限 | N 次执行后消费；无限不消费 |
| L5 | 状态策略 | motion/breath/selector/audio/event/overlay | 按 `loop-execution.md` §3.5：只重置玩家移动；音频/事件/相机状态按现有设计 |
| L6 | 宏观循环 | repeat/无限/`loop_end` | 与现行一致；EVENT 不重放 |
| L7 | hold_at_end | 有限/无限 | 有限驻留 contentEnd；无限告警/不驻留 |
| L8 | contentEnd | 多片段/无片段/无限 | max windowEnd/0/∞ |
| L9 | 旧字段 | version/total_duration/keyframes/audio loop | 全部“已移除”；curve 原样迁移为 `objects.curve` |
| L10 | 触点 | FrameDriver/RuleActions/Command/Graph/模板/编辑器 | 无 total_duration 读取/写入 |

## 九、迁移

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| G1 | 全量结构 | 223 脚本/813 clip/2411 keyframe | 全部可被新 parser/validator 加载 |
| G2 | 稀疏字段 | 构造“字段只在部分全局帧出现”的 fixture | 旧/新在全局帧 + 密集采样点一致 |
| G3 | 复合值 | position/dest/source/color/offset | 叶子值/空间/颜色正确 |
| G4 | 曲线/切线 | 旧 curve/orient fixture；新增 2 轴/3 轴组合 fixture；三轴成组/不对齐 | 旧 3D curve 原样映射 `axes:["x","y","z"]`，控制点/3D 弧长不变；2 轴做 2D 弧长；迁移后三轴成组；orient/offset 保留 |
| G5 | 动态相机 | position_origin/selector/look_at | 保语义或标记人工复核；报告完整 |
| G6 | EVENT | 旧 keyframes → 记录 | command/position/空占位一致 |
| G7 | AUDIO | `loop`→`file_loop` | 文件循环/执行循环分开 |
| G8 | 旧 total_duration | 与新 contentEnd 比较 | 差异报告；不写新字段 |
| G9 | 幂等/回滚 | 重复运行/失败/备份 | 新格式跳过；失败不覆盖；可回滚 |
| G10 | 体积 | 压缩前/后 | 单脚本 ≤ 4 MiB 解压 / 1 MiB 压缩；超限报告 |
| G11 | 报告 | 每脚本 | 修正帧/curve 拆分/最大误差/复核项齐全 |

## 十、编辑器

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| U1 | 加载/保存/推送 | 新格式脚本 | 无 version/total_duration；对象保持 |
| U2 | 单对象编辑 | 加/删/移 keyframe | 只影响该对象；跨对象同时间合法 |
| U3 | 超界 keyframe | 拖出/缩片段 | 保留、显示轴外、不钳制 |
| U4 | 片段重叠 | lane 模型脚本 | 不再报“片段重叠” |
| U5 | schema 表单 | 对象列表/分组/枚举/可空 | 来自 schema；无硬编码通道表 |
| U6 | 缓动/曲线 | 作者缓动、轴集合（含 `["y","x"]`）、`curve` 开启/关闭、offset | 缓动保存后显式 keyframes；curve/orient/offset 原样保留，不烘焙；轴集合顺序无关；开启 `curve` 后三轴关键帧成组，关闭后恢复独立 |
| U7 | 校验 | 服务端错误 | 定位到对象/keyframe；本地不误报 |
| U8 | 总长/seek/zoom | 有限/无限 | 用 contentEnd；无限显示 ∞ |
| U9 | 离线 demo | schema 快照 | 路径集合与运行时一致 |

## 十一、真实机器 / 性能 / 回归

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| P1 | Fabric fixture | 跑 `cinematics/0.3.6/tests` 关键集 | `result=1`；自然结束时间符合 |
| P2 | Forge | 编译/加载 | 编译通过；无运行时缺失类 |
| P3 | GL 探针 | `ic_lane_blit`/`ic_color_mask` 等 | 与 D/F 轮基线一致 |
| P4 | 性能 | F5 矩阵 600/600 per lane tier | 不退化；对象采样无每帧分配 |
| P5 | 光影 | Iris/Oculus 同维度/跨维度 | 与 F 轮结论一致 |
| P6 | 文档 | SCRIPT_FORMAT/AI_GUIDE/modules | 无 version/total_duration/旧循环/旧 keyframes |
| P7 | 代码清理 | grep `getAnimPeriod`/`effectiveTime`/`total_duration`/`meta.version` | 运行时无残留（迁移器/测试除外） |

## 十二、完成定义

- 所有 M/R/C/O/A/E/L/G/U/V/P 项通过，或有明确记录的例外/人工复核项。
- 迁移报告 0 个未解释超差；所有人工复核项有结论。
- 真实机器跑通；GL/性能基线不回归。
- 文档/模板/示例/编辑器全部新格式；旧字段只在迁移器/测试负向 fixture 中出现。
- 没有临时探针/一次性脚本留在仓库。

## 十三、视频媒体

| # | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| V1 | 本地视频播放 | `layer_type=image` + `.mp4`/`.webm`；短/长文件 | 画面出现；时间跟片段局部时间；不跟全局墙钟 |
| V2 | 暂停/seek/循环 | 暂停、跳转、loop 折返 | 暂停停帧；seek 从目标时间重开；循环按执行语义回卷 |
| V3 | 失败回退 | 缺 ffmpeg、坏文件、解码中途退出 | 错误纹理/最后一帧；不崩；日志限频；可恢复 |
| V4 | 资源回收 | 图层结束、停播、世界退出 | 解码进程退出；纹理/缓冲释放；无僵尸进程 |
| V5 | 预览编码流 | 720p/1080p、H.264、JPEG 回退 | 有 PTS；前端按 PTS 播放；无 PTS 不硬播 |
| V6 | 预览背压 | 慢客户端/多会话 | 每会话有界队列；丢旧帧计数；不阻塞其他会话 |
| V7 | 媒体分片 | 分片/乱序/丢片/校验失败 | 按序重组；CRC/SHA-256 校验；只重拉坏片；缓存命中 |
| V8 | 路径/权限 | `..`、绝对路径、超限文件、非白名单扩展名 | 拒绝；不越出资源根；错误可定位 |
| V9 | 许可证 | 参考实现代码/片段 | 不复制 GPLv3；MIT 片段保留声明；ffmpeg 不打包 |

## 十四、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
