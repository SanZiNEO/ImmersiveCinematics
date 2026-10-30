# 运行时求值（Runtime Evaluation）

> 状态：未实现
> 范围：对象采样、时间基准、脚本级曲线/规则集合的运行时索引、轨道装配调用顺序、缓存/零分配、morph/lane 的求值边界
> 关联：`object-model.md`、`schema-and-types.md`、`loop-execution.md`、`I-script-core/rule-system.md`（规则新方向专篇，未完成轮次；旧 `C-trigger/condition-system.md` 仅作既有实现参照）、各 `track-*.md`；参考 `F-playback/camera-composition.md`、`multi-camera-rendering.md`

## 一、摘要

运行时按两类输入求值：**SampledProperty（采样属性）** 的逐属性采样，以及**脚本级曲线集合与规则集合**的按片段解析。曲线条目先按目标片段索引，再按目标位置序列的端点对求几何/进度；规则集合由 RuleDefinition 与 RuleBinding 组成，绑定按目标片段索引，执行进入时建立 RuleActivation，时间门满足后推进判定；RuleActivation 是运行态，由播放实例管理。然后由轨道播放器把采样值与结构结果组装成语义值。同一片段内所有 SampledProperty 在同一 `executionLocalTime` 求值；采样是纯函数，不读其他属性的临时状态。曲线条目不是普通属性，也不合并成万能类型；规则定义/绑定/激活分离，有效判定 = 时间门 AND 内层条件树。轨道播放器只读自己需要的路径与结构结果，按轨道文档定义的顺序组装；没有共享的 `from/to` 快照，没有隐藏的 `frame` 字段。采样器在加载时编译路径/关键帧数组，每帧只做数组访问和数值插值，不分配临时 map/字符串。统一原则：组合优先、通道级覆盖、定义与运行态分离、创作策略与执行机制分离、脚本级结构按片段引用定位。

## 二、为什么做

### 2.1 问题

- 当前 `KeyframeInterpolator.computeInterpolation` 一次返回 `from/to/s`，所有字段共用同一对关键帧；复合值只能整体插值，字段缺失时逐字段回落默认值。
- 相机求值存在隐藏顺序：`frame` 是 per-frame 可变字段，`interpolateWorldPosition` 先算 `from` 再算 `to`，`frame` 最后是 `to` 的；`segmentYawPitch` 的 tangent 分支又会重算位置并覆盖 `frame`。base-space 偏移读到哪个 `frame` 取决于调用顺序。
- `isClipUsable` 扫描所有关键帧，包括超出 `duration` 的；一个超界关键帧的点源解析失败会让整个 clip 不可用。
- 片段循环在采样器里折叠时间；`LaneMask` 又复制一份；lane adjust 用原始时间，三条采样路径可能不同步。
- 每帧按字段名查 map、分配 `InterpolationResult`/临时向量，性能不可控。
- 曲线只有片段级一份，规则只有片段级数组：运行时无法按“目标片段 + 端点对”索引多条曲线，也无法按“目标片段 + 绑定 + 执行”索引规则激活；多入口/多条曲线只能靠特判。

### 2.2 目标

- 同一片段的所有 SampledProperty 在同一 `t` 采样；轨道装配在采样之后进行，顺序显式。
- 脚本级曲线集合与规则集合按目标片段索引：曲线条目只提供目标位置序列端点对的几何/进度；RuleBinding 只提供目标片段、执行局部时间门、阶段/调度声明与定义引用，RuleActivation 提供运行期行为；结构解析不伪装成属性采样，也不建立共享快照。
- 采样器是纯函数；状态（选择器锁、冻结、运动模型、breath 相位）由轨道/执行层持有，不藏在采样器里。
- 采样路径在加载时编译成整数槽位；每帧不按字符串查 map、不分配临时对象。
- 片段循环不改变采样器输入轴；播放控制给 `executionIndex` + `executionLocalTime`，采样器只认 `[0,duration]`。
- 静态片段（无 keyframes）也能求值；CAMERA 不再要求至少一个关键帧。
- 超界关键帧不参与求值，也不影响 `isClipUsable`。

### 2.3 非目标

- 不在运行时做 schema 类型检查/反射；数据已由 parser/validator 校验。
- 通用采样器不做曲线/规则；曲线由脚本级索引按目标片段与端点对求几何/进度，规则由规则引擎按绑定与执行管理激活/等待/观察/动作。缓动由编辑器在写入前烘焙成显式关键帧；不得用“在曲线段内插位置点并复用旧控制点”的方式实现。
- 不把 CurveDef/RuleBinding 伪装成 value-only SampledProperty；它们不是新轨道，也不与普通属性共用关键帧容器。
- 不把时间门实现成“到达后才开始监听”，也不实现成一次性到达事件后绕过条件。时间门是执行资格门：默认 = 本次执行片段局部时间 >= 指定值；门前有效事件/事实可采集，动作不得提前提交；到门后开始/继续组合判定，条件后续成立仍可执行。
- 不在采样器里做轨道语义（相机六参数、覆盖层绘制、调色栈）。
- 不改变时间精度（float 秒）。

## 三、方案

### 3.1 一帧的求值顺序

对每个播放实例、每个活跃片段：

1. **播放控制给执行状态**：`ScriptPlayer`/`FrameDriver` 算出脚本时间；循环片段的执行状态给出 `executionIndex`、`executionLocalTime`、方向（pingpong）。`executionLocalTime` 始终在 `[0, duration]`。
2. **脚本级结构解析与规则阶段**：曲线集合按目标片段索引，取本片段被引用的曲线条目，按本次执行局部位置求目标位置序列端点对的几何/进度。规则集合按目标片段索引：绑定所属片段执行进入时按（播放实例，绑定身份，执行身份）建立 RuleActivation；RuleBinding 在时间门前采集有效事件/事实、不提交动作；到达时间门后推进该激活，并按声明选择建立**本实例级内容持有**等待判定或保持非阻塞观察。判定阶段要求时间门与 all/any 条件树共同满足；判定成功记录成功事实，动作按声明的片段开始/完成、局部时间、延迟或事实再调度，不重新判定原规则；动作目标片段可与时间参照片段不同。判定成功、动作提交、动作完成分属不同事实，可被后续绑定引用。RuleDefinition 是不可变行为，不在定义上挂运行态；时间门控制执行资格，不是“到达后才开始监听”，也不是一次性到达事件。默认时间门 = 本次执行片段局部时间 >= 指定值，门后持续判定，条件后续成立仍可执行；门前事实可采集但不提交动作。已激活观察的生命周期不因片段活跃窗结束而统一失效，非阻塞观察不阻止片段自然完成。
3. **SampledProperty 采样**：`ClipSampler` 对轨道需要的每个属性路径采样，写入预分配的 `SampledClip`（或按轨道类型预分配的原始数组）。所有属性用同一个 `executionLocalTime`。
4. **轨道装配**：轨道播放器读 `SampledClip` 与结构解析结果，按轨道文档定义的顺序组装语义值。装配只读采样/结构结果，不回调采样器、不修改其他属性。
5. **轨道后处理**：相机每侧先求完整状态（属性采样 → 位置/朝向 → 本段 motion → 本段 breath），过渡层只混合两侧快照；overlay 图层参数；adjust 参数包；audio 实例参数。后处理的状态由轨道/执行侧持有。
6. **消费/提交**：相机写 `CameraState`/lane；overlay 更新图层；adjust 发布参数；audio 更新实例；event 触发命令；RuleActivation 关联的动作调度按各自身份提交，非阻塞观察不阻塞片段自然完成。

### 3.2 采样结果

```text
SampledClip
  clip
  executionIndex
  executionLocalTime
  values: float[] / int[] / bool[] / String[] / Object[]   // 按 schema 槽位
  presence: bitset                                           // 可选：区分“未提供”和“默认值”
```

- 槽位在加载时由 `SchemaRegistry` 分配：每个 track type 的 SampledProperty 路径 -> 固定索引；同一 track type 的所有 clip 共用槽位布局。
- `values` 按类型分数组；避免 `Map<String,Object>` 和装箱。
- `presence` 用于 `required=false` 且无默认值的属性：轨道可以区分“作者没写”与“写了默认值”。大多数轨道不需要，可按需启用。
- `event_record` 属性不进数值数组；由 `EventClipRuntime` 单独持有事件列表。
- 曲线条目/RuleDefinition/RuleBinding/RuleActivation 不进 `SampledClip`：曲线结果（目标片段、端点对、几何、进度）由脚本级结构索引持有；RuleDefinition 只读；RuleBinding 是脚本级关联；RuleActivation 由播放实例按（播放实例，绑定身份，执行身份）持有。装配阶段显式读取结构结果。

### 3.3 采样器

```text
ClipSampler
  PropertyChannel[] channels    // 按 SampledProperty 槽位
  sample(t, out SampledClip)
```

`PropertyChannel`：

- `path` / `slot` / `type` / `interp` / `default`；
- `times: float[]` / `values: <typed>[]`（只含范围内 keyframes；加载时已排序/校验）；
- `cursor`：上次命中的段索引，用于顺序播放的 O(1) 前进；seek 时重置/二分。
- `sample(t)`：
  - 无 keyframes：写 `value`/`default`；
  - 单帧：写该值；
  - `t <= times[0]`：写 `values[0]`；
  - `t >= times[last]`：写 `values[last]`；
  - 否则用 cursor 前进/二分找段，按 `interp` 插值。

- 采样器不持有片段播放状态；`cursor` 是缓存，不影响结果。多线程/多实例播放时每个实例持有自己的 `ClipSampler`，或采样器无 cursor、用二分（按性能测试选）。
- 采样器不读 `loop`/`loop_mode`；执行层已经把 `t` 归一化。

### 3.4 轨道装配

- 轨道播放器拿到 `SampledClip` 与结构解析结果后，按 `track-*.md` 的顺序读槽位。典型：
  - CAMERA：位置源/朝向源/角度/合成参数/调色 -> 六参数 `CameraState`；位置与朝向各自持有参考系（位置 = 点源 + `position_space`；朝向 = `yaw_base`/`pitch_base` 或 `look_at`），不自动共享；本片段被脚本级曲线条目引用时，按该条目的端点对读其几何/进度；每侧 motion/breath 在自身求值内完成，morph 只混合两侧快照；modifier 在全局层。
  - OVERLAY：几何/透明度/颜色/遮罩/渐变 -> 图层参数。
  - ADJUST：59 通道 -> `ColorAdjustParams`。
  - AUDIO：volume/xyz -> 实例参数。
  - LETTERBOX：aspect/opacity -> 黑边参数。
  - EVENT：事件列表 -> 触发命令；不采样数值。
- 装配函数是纯函数（输入 `SampledClip` + 片段 value-only 字段 + 外部解析结果，输出语义值）；外部解析（实体位置、点源、选择器）由轨道持有的 resolver 提供。
- 同一个 `SampledClip` 可以被多个消费者读（相机状态、lane 合成参数、lane 调色），保证同一帧同一 `t`；不再各自调用 `computeInterpolation`。

### 3.5 相机求值的显式顺序

相机是最复杂的轨道，单独列出；细节见 `track-camera.md`。

1. 采样所有 SampledProperty（位置、mode/space、点源、选择器策略、朝向、角度、合成、调色）。
2. 解析点源/选择器（可能读实体/世界状态；同一个解析结果供位置/朝向/注视共用）。
3. 组装世界位置（position mode/space + position_origin/time + 方向源 `facing_source`：auto/world/entity/line，按载荷或原点回落；`fwd`/`up`/`right` 是位置偏移，base 轴没有独立角度偏移字段）。本片段有曲线条目覆盖当前局部时间所在的端点区间时，按该条目几何/进度求值；没有曲线条目覆盖时按 SampledProperty 插值。
4. 组装朝向（`look_at` 覆盖对应朝向结果；`orient=tangent` 用当前曲线条目的路径导数 + 未选轴 SampledProperty 导数 + offset；手动 base+lock+offset）。
5. 应用本片段自己的 value-only motion。
6. 应用本片段自己的 breath。
7. morph：A/B 各自按 1-6 求出一份完整状态快照，再按 transition 声明混合；窗口以交界为中心、半宽 = 该过渡声明的最大通道时长 / 2（缺省 = `transition_duration`；总宽 3 秒 = 交界前后各 1.5 秒）。每侧状态由该侧执行持有，每帧只推进一次。过渡层只读快照，不推进 motion/breath、不重复推进相机状态，不替两侧选效果。
8. 写全局相机状态；lane 快照；modifier 链；lane 合成参数。
9. 捕获 `lastWorldPos`（capture=false 时）。

- 不再有 per-frame `frame` 字段；`frame` 作为局部变量在位置/朝向组装中传递。点源解析结果显式传入，不靠调用顺序。
- 每侧从自己的 `executionLocalTime` 求值：有关键帧就求所在段；没有关键帧用 `value`/默认值，不拿末帧/首帧兜底。
- 曲线条目按目标片段与端点对求值：一个 clip 可被多条曲线条目引用，每条只覆盖目标位置序列中的一个 A→B 相邻端点区间，端点必须明确；不同条目不共享控制点，控制点参考空间按条目声明。目标位置节点只是路径寻址域，不把 `fov`/`roll` 等普通属性拉回共享 `from/to`。曲线只负责 axes 中选中的坐标轴，段内选中轴由该条目几何/进度求值；未选轴继续按自身 SampledProperty 的局部时间/关键帧求值。2 轴只保证该平面内 2D 弧长，不承诺 3D 匀速。`world` 段用世界坐标，`base` 段用 fwd/up/right 坐标，base 动态时路径随参考系整体变换，不做线性降级。段外按 SampledProperty 插值；没有曲线条目覆盖的段正常按 SampledProperty 插值，不要求整片段三轴全局成组。
- RuleDefinition/RuleBinding/RuleActivation 不参与相机状态求值：RuleDefinition 是不可变行为，RuleBinding 只提供目标片段、执行局部时间门、阶段/调度声明与定义引用，RuleActivation 由播放实例按（播放实例，绑定身份，执行身份）管理等待/观察/事实记录/动作。有效判定 = 时间门 AND 内层条件树；内层 `any`/`or` 不得绕过外层时间门，多时间点用多绑定表达。状态谓词按实时语义判定，不永久锁存任意一度为真；事件事实按原条件有效期/匹配/消费语义保留到时间门；每个绑定/执行独立记录，不做无界历史或跨实例事件总线。规则不并入曲线集合，也不把相机属性拉进共享快照；非阻塞观察不阻止片段自然完成。
- `look_at` 的模型语义是覆盖对应朝向结果；`look_at=none` 直接按手动朝向求值。当前实现仅在至少一端 `look_at` 启用的混合段用 100 格远伪目标做端点求值；两端 `none` 已按角度插值。伪目标不是 `none` 的通用实现，不作为求值前提。
- `isClipUsable` 只检查 `[0,duration]` 内对象；静态片段直接可用。

### 3.6 循环与执行次数

- 循环是片段级的执行次数；播放控制负责 `executionIndex`/`executionLocalTime`/方向。采样器只接受已归一化的局部时间：有限片段在 `[0,duration]`；无限执行没有有限上界，只保证 `time >= 0`（无限是执行状态语义，格式层表示不是完成判据）。
- 运行位置语义 = clip + execution + local；当前网络 `(start, offset)` 只是该位置的投影参照，不要求改协议，也不把网络时间当成执行完成事实。
- 只有声明了 `loop` 的片段重复；非循环片段只执行一次，不被其他片段循环改变。
- `pingpong` 的反向由播放控制把本次执行的局部时间映射为 `duration - offset`；采样器仍按升序关键帧插值。
- 每次执行的采样结果独立。绑定所属片段执行进入时建立 RuleActivation；时间门可由作者给值或引用该片段已有对象关键帧，关键帧只提供局部时间值，不成为激活的额外前提；RuleBinding 在时间门前采集有效事件/事实，到达局部时间门后确认并推进激活，可按声明建立本实例级内容持有等待判定或保持非阻塞观察；RuleActivation 身份 =（播放实例，绑定身份，执行身份），激活后的判定成功、动作调度/提交/完成、计时、观察、事实记录由本播放实例管理。判定成功不等于动作完成；成功后按片段开始/完成、局部时间、延迟或事实再调度，不重新判定原规则。同一规则的多条绑定各自独立，不把多个时间门 OR 成最早入口；共用定义不共用运行态。已激活观察各自持有生命周期，可跨片段，不因片段活跃窗结束而统一失效；非阻塞观察不阻止片段自然完成。实例级持有期间内容局部时间不推进，实时计时/超时不因此冻结；多个持有来源最后一个解除才恢复。判定成功后已承诺未释放的动作作为明确执行依赖参与自然完成，但不冻结内容推进；等待片段开始与等待片段完成分别记账。音频/事件/服务端去重按各自既有边界，不因规则激活一律重启。相机每侧状态每帧只推进一次；transition 只消费 A/B 快照，不重复推进相机效果。详见 `loop-execution.md` 与 `rule-system.md`。
- 宏观循环（脚本级）由 `ScriptPlayer` 重复执行；SampledProperty 采样器不感知。

### 3.7 缓存与零分配

- 加载期：
  - 解析 `objects`，按 schema 槽位编译 SampledProperty `PropertyChannel`；
  - 解析脚本级曲线/规则集合，按目标片段编译曲线条目索引（目标片段 -> 端点对/几何/进度）与规则绑定索引（目标片段 -> 绑定/时间门/定义）；RuleActivation 只在运行期创建；结构条目单独编译，不进入属性槽位；
  - 排序/校验 keyframes；
  - 预分配 `SampledClip` 的 typed arrays；
  - 预计算 clip 的 `windowEnd`/`isInfinite` 与有限末端估计贡献：有限末端估计只在所有内容静态有限时成立（不是 `max(windowEnd)` 的别名，也不驱动消费）；含无限内容时记录“无有限自然末端”，有限部分显示范围单独派生，不把数值哨兵当完成判据。
- 每帧：
  - 复用 `SampledClip`（每个播放实例/每个 lane 一个），不 new；
  - 采样只写 primitive 数组；
  - 装配用 primitive/record，不创建 `Map`/`String`；
  - 路径字符串只在加载/日志/错误中使用。
- morph A/B：两个预分配 `SampledClip` + 两份完整状态快照（含各自 motion/breath），不交换/复制；transition 直接读两个结果。
- lane 合成：每个 lane 一个 `LaneFrame`/`SampledClip`，复用；不按帧分配列表。
- 事件：事件列表在加载时编译；每帧只比较时间游标，不遍历所有 keyframes。

### 3.8 错误处理

- 加载期：parser/validator 报错，脚本不进入运行时。
- 运行期：数据已校验；不因对象值/关键帧缺失抛异常。外部解析失败（实体不存在、点源越界）按轨道现有失败处理：限频日志 + 回退/跳过，不中断播放。
- 采样器不做“猜测/修复”；超界关键帧已在加载时过滤（有限片段按 `duration`，无限执行只看 `time < 0`）。
- 断言只用于编程错误（槽位越界、类型数组不匹配），不在生产路径做昂贵检查。

## 四、技术细节

### 4.1 核心模型

```text
ClipRuntime
  Clip clip
  ClipSampler sampler
  SampledClip sampleBuffer
  CurveEntryRuntime[] curves?   // 本片段被引用的脚本级曲线条目；与 SampledClip 分离
  EventClipRuntime events?      // 仅 EVENT/MOD_EVENT

ScriptStructureRuntime
  curvesByClip: Map<clipId, CurveEntryRuntime[]>
  rulesByClip:  Map<clipId, RuleBindingRuntime[]>
  rules:        RuleDefRuntime[]          // 只读定义
  activations:  RuleActivation[]          // 播放实例持有；含判定/成功/动作阶段
  facts:        RuleFactIndex             // 本实例内规则成功/片段开始/完成等事实
  holds:        HoldLedger                // 本实例级持有来源记账

ClipSampler
  PropertyChannel[] bySlot
  int[] pathToSlot              // 加载期编译；运行期不用
```

- `ClipRuntime` 每个播放实例每个 clip 一份；同一脚本被多个玩家播放时，实例各自持有。
- `SampledClip` 的数组长度 = 该 track type 的 SampledProperty 槽位数；不同 track 的 `SampledClip` 类型不同（或统一大数组 + 槽位段）。
- `PropertyChannel` 的值数组按类型分开；`event_record` 用 `Object[]`，只读。
- 脚本级曲线条目由 `ScriptStructureRuntime.curvesByClip` 按片段索引；每条几何只读，段进度按执行位置计算。RuleDef 只读；RuleBindingRuntime 只关联定义、目标片段、时间门与阶段/调度声明；RuleActivation 由播放实例持有，身份为（播放实例，绑定身份，执行身份）。

### 4.2 契约

- **创建**：脚本加载/实例创建时，`ClipRuntime` 由 `Clip` + `SchemaRegistry` 构建；采样器编译一次；脚本级曲线/规则集合由脚本级结构解析器编译一次，按目标片段建立 `curvesByClip`/`rulesByClip` 索引，不与属性槽位混建；RuleActivation 在绑定所属片段执行进入时按（播放实例，绑定身份，执行身份）创建，承载门前观察/事实；时间门只决定有效判定与动作，不决定激活是否存在。
- **销毁**：随实例/clip 销毁；不跨实例共享可变采样缓冲或结构运行态。
- **状态归谁**：采样器只有 cursor 缓存；选择器锁/冻结/运动模型/breath 相位归轨道播放器（morph A/B 各一份）；曲线条目几何只读、进度按执行位置计算；RuleDefinition 只读，RuleBinding 只关联定义/目标片段/时间门/阶段与调度声明，RuleActivation 以（播放实例，绑定身份，执行身份）归播放实例，时间门前的事实、判定成功、动作待执行/完成/取消、持有来源归同一绑定/执行上下文；本实例级持有由 `HoldLedger` 按来源记账；事件游标归 `EventClipRuntime`。
- **边界**：采样器不读外部世界；点源/实体解析在装配阶段，由轨道 resolver 做；结构解析器不读 SampledProperty 的临时状态，RuleBinding 不把属性拉进共享快照；时间门前的事实采集与动作提交分离，不因时间门未满足而丢弃有效提前事件。实例级持有只冻结本实例内容推进，不影响其它实例；等未来内容时不得同时冻结该进度。
- **线程**：采样/装配在客户端渲染线程或服务端 tick 线程；每个实例的缓冲不跨线程共享。

### 4.3 数据形态

- `SampledClip.executionIndex` 是 int；`executionLocalTime` 是 float。
- `presence` bitset 只对可选 SampledProperty 分配；默认不分配。
- 曲线条目/RuleDefinition/RuleBinding/RuleActivation 的运行态与 SampledProperty 数组分开：曲线条目持有目标片段/端点对/几何（只读）与当前段/进度；RuleDefinition 只读；RuleBinding 只持有定义引用/目标片段/时间门/阶段声明；RuleActivation 持有（播放实例，绑定身份，执行身份）及等待/观察/事实记录/判定成功/动作调度状态；它们不写 `SampledClip`。状态谓词与事件事实分开记录：状态不永久锁存，事件事实按原条件有效期/消费语义保留到时间门；每绑定/执行独立，不共享历史。判定成功、动作待执行/完成/取消分别记录；规则事实索引只服务本实例内的后续绑定，不做脚本级共享或跨实例总线。
- `event_record` 的值是 schema 定义的记录（`event_type`/`command`/`position` 等），加载期校验；运行期只读。
- 采样结果不包含路径字符串；日志需要路径时从 `PropertyChannel` 取。

### 4.4 边界

- 求值器不定义轨道语义；轨道文档定义“读哪些槽位、按什么顺序组装”，以及曲线条目/RuleDefinition/RuleBinding/RuleActivation 如何被该轨道消费。
- 求值器不定义结构条目的序列化/引用字段；只定义脚本级结构与 SampledProperty 的求值边界。CurveDef/RuleDef/RuleBinding 的序列化/引用字段待评审，RuleActivation 属于运行态。
- 求值器固定“时间门是执行资格门、提前事实可采集、动作不提前提交、到门后组合判定”；默认时间门 = 本次执行局部时间 >= 指定值，门后持续判定，条件后续成立仍可执行。不另造精确时刻或统一窗口变体。
- 普通/分支片段共用同一 `ClipSampler`、对象 schema 与执行局部时间坐标；求值器不为分支建立第二套内容/采样路径，分支只改变执行来源/编排去向。求值器不定义正常/分支片段的编排去向；片段默认 + 入口显式覆盖由执行层按 `rule-system.md` 处理，失败 fallback 独立。
- 求值器不定义规则控制阶段的完整算法（判定持有、成功事实匹配、动作延迟调度、事实接力）；这些归 `rule-system.md`。求值器只提供本实例级持有记账、事实索引与执行局部时间/内容位置接口。
- 结构评审项（非用户待确认）：事实记录起点/有效期/消费/跨执行匹配、稳定片段引用、持有/调度 schema 表达、集合序列化/迁移/编辑器呈现。求值器不预设这些形态。
- 求值器不定义执行边界状态；`loop-execution.md` 定义。
- 求值器不定义迁移；`migration.md` 定义。
- 求值器不定义编辑器；编辑器只消费 schema 和采样预览接口。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 保留 `from/to/s` 共享快照 | 每个 clip 仍选一对关键帧，所有字段共用 | 复合值不能单轴调；稀疏字段回落分裂；隐藏顺序依旧 | 与对象模型冲突 |
| 每帧按路径查 map | `Map<String, AnimObject>` + 字符串键 | 每帧哈希/装箱/临时对象；性能不可控 | 加载期编译槽位更稳 |
| 每个轨道各自采样 | 相机、lane 合成、lane 调色各调一次采样器 | 同一帧可能用不同执行状态/局部时间；重复计算 | 一个 `SampledClip` 多消费者 |
| 采样器持有选择器/运动状态 | 把状态塞进对象通道 | 纯函数被破坏；多实例/seek/循环重置困难 | 状态归轨道/执行层 |
| 拆成三条独立 1D 曲线 | 每轴一条标量曲线 + 各自的弧长 | 1D 没有平面/空间曲线，无法定义 2D/3D 弧长；三轴时间不对齐时不是一条明确几何曲线 | 每条曲线条目引用目标片段的 A→B 相邻端点并持有自己的 geometry |
| clip 级一份控制点套所有段 | 一个字段只有一组 P1/P2，对每个位置段隐式复用 | 不同段不能有不同曲线；同片段不能有多条候选路径；插入关键帧会改变其余段 | 脚本级曲线集合，每条引用目标片段与端点对 |
| 曲线只按全片段关键帧总编号 | 把 FOV/调色等所有对象的帧拼成总序列，曲线按总序号取相邻帧 | 其它对象增删帧会改变位置曲线端点 | 端点相邻性只在目标位置序列内判断 |
| 把曲线与规则合并成万能类型 | 一个结构条目同时管路径几何、进度、激活、观察、动作 | 职责混杂；定义/绑定/运行态无法分离；复用定义会串运行态 | 曲线集合与规则定义/绑定/激活按职责拆分，共享脚本级集合原则但不合并 |
| 时间门到达后才开始监听条件 | 到点前不采集事件，到点后才开始观察 | 有效的提前事件丢失；与“条件在门前可能已触发”冲突 | 门前采集事实、动作不提前提交；到点确认后再执行 |
| 判定成功即动作完成 | 成功事实与动作提交/完成合并 | 延迟动作、规则接力、完成诊断无法区分 | 成功、动作待执行/完成/取消分开记账 |
| 动作延迟时重新判定原规则 | 到目标片段/时间再判一次 | 原状态条件可能已变假，成功被隐式抹掉 | 成功后按声明调度，不重判 |
| 用脚本级共享布尔记录规则成功 | 定义上放“已成功”标记供后续规则读 | 循环/多实例串状态；无法定位绑定/执行 | 事实按实例与绑定/执行定位 |
| 多个时间入口 OR 成最早时间 | 同一规则写多个 `time >= t`，取最早满足的一个 | 后续入口被最早入口吞掉；once/边沿/计时无法按入口独立 | 每条绑定独立时间门与运行身份 |
| 分支片段使用独立内容 schema / 全局片段库 / 新 sequence 库 | 分支内容另存一份格式，或把全部片段迁到全局库 | 两套采样/装配路径；普通片段也被迫改承载 | 普通/分支共用同一 `ClipSampler` 与对象 schema，分支只改执行来源/编排去向 |
| 正常完成默认层不提供、只能入口硬编码 | 每个启动点各自决定去向，片段无默认 | 复用片段要重复编排；正常/失败出口混在一起 | 片段默认 + 入口显式覆盖，失败 fallback 独立 |
| 过渡先混合再套 next motion / prev breath | 混合六参数后，用下一段 motion、上一段 breath 补齐 | 两侧效果归属错位；每侧状态不完整；同一效果可能被推进多次或漏掉 | 每侧先求完整状态（含本段 motion/breath），过渡只混合快照 |

**本方案的缺点**：

- 每个 track type 需要一份槽位布局和 `SampledClip` 类型；新增轨道/对象要同步代码。
- 采样器/装配的边界要求轨道作者遵守；如果有人在装配里回调采样器，隐藏顺序会回来。
- `presence`/可选对象增加分支；大多数轨道可以不用，但 schema 要支持。
- 预分配缓冲增加内存占用（每实例每 clip）；需要按活跃 clip 池控制。
- morph/lane 多缓冲的复用容易出别名 bug；需要明确的“只读/可写”约定。
- morph 两侧各持完整状态（含 motion/breath），状态与缓冲比“混合后套一份效果”多；需要明确每侧只推进一次，避免同一实例一帧 step 两次。
- RuleDefinition 可复用，但 RuleActivation 必须按（播放实例，绑定身份，执行身份）隔离；实现若把运行态挂在定义或绑定上，会串状态。非阻塞观察不阻止片段自然完成，完成判定不能等待观察。
- 时间门前的事实采集需要按条件自身语义确定记录起点/有效期/消费方式；原条件未定义时只能列为待评审，不能假设所有历史事件永久有效，也不能引入无界历史缓冲。
- 时间门默认是 `>=` 持续判定：门后条件可能长时间不成立，规则保持未成立；作者需要用条件/超时/退出收束，不能把“已经过点”当成必然执行。

## 六、实现结果

状态：未实现

本节只列当前代码与设计的差距；相机语义的完整差距见 `track-camera.md` §六。

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 每侧完整状态（含本段 motion/breath）→ 过渡混合 | `CameraKeyframeEvaluator.renderMorph` 先混合六参数，再用 `nextClip` 的 motion、`prevClip` 的 breath 补齐 | 现行实现按“混合后套效果”接线；新设计每侧先求完整状态 |
| 某侧无关键帧时按 `value`/所在段求值 | 某侧 `computeInterpolation` 返回 null 时用 `lastWorldPos`/0 兜底 | 旧端点求值路径没有静态片段语义 |
| 每侧 motion/breath 每帧只推进一次 | 混合后只对 `nextClip`/`prevClip` 各跑一次 | 现行实现缺少两侧各自完整效果状态，不能据此推断每侧已独立推进 |
| 曲线为脚本级集合，按目标片段/端点对索引；RuleDefinition 不可变、RuleBinding 引用目标片段与时间门、RuleActivation 以（实例，绑定，执行）隔离 | 旧 `Clip.curve` 是 clip 级一份 3D 控制点，对每个 from→to 段隐式复用；`objects.curve` 从未实现；规则是片段级 `clip.rules`，没有定义/绑定/时间门分层 | 旧格式只有一份 `curve` 与片段级规则数组；脚本级结构是新增设计，序列化/引用待评审 |
| 规则判定成功/动作调度/动作完成分阶段；实例级持有按来源记账；规则事实可接力 | 旧规则随片段活跃窗建槽，没有成功事实、动作延迟或实例级判定持有 | 规则阶段模型未实现；新方向见 `rule-system.md` |
| 有限末端估计只在静态有限时成立；含无限无有限末端；显示范围单独派生 | 当前 `contentEnd` 由片段窗口推导，无限依赖哨兵/截断 | 旧实现把派生值当完成判据 |
