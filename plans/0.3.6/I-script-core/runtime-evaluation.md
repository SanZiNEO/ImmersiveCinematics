# 运行时求值（Runtime Evaluation）

> 状态：未实现
> 范围：对象采样、时间基准、轨道装配调用顺序、缓存/零分配、morph/lane 的求值边界
> 关联：`object-model.md`、`schema-and-types.md`、`loop-execution.md`、各 `track-*.md`；参考 `F-playback/camera-composition.md`、`multi-camera-rendering.md`

## 一、摘要

运行时只做两件事：**按路径采样字段**，以及**由轨道播放器把采样值组装成语义值**。同一片段内所有字段在同一 `executionLocalTime` 求值；采样是纯函数，不读其他字段的临时状态。轨道播放器只读自己需要的路径，按轨道文档定义的顺序组装；没有共享的 `from/to` 快照，没有隐藏的 `frame` 字段。采样器在加载时编译路径/关键帧数组，每帧只做数组访问和数值插值，不分配临时 map/字符串。

## 二、为什么做

### 2.1 问题

- 当前 `KeyframeInterpolator.computeInterpolation` 一次返回 `from/to/s`，所有字段共用同一对关键帧；复合值只能整体插值，字段缺失时逐字段回落默认值。
- 相机求值存在隐藏顺序：`frame` 是 per-frame 可变字段，`interpolateWorldPosition` 先算 `from` 再算 `to`，`frame` 最后是 `to` 的；`segmentYawPitch` 的 tangent 分支又会重算位置并覆盖 `frame`。base-space 偏移读到哪个 `frame` 取决于调用顺序。
- `isClipUsable` 扫描所有关键帧，包括超出 `duration` 的；一个超界关键帧的点源解析失败会让整个 clip 不可用。
- 片段循环在采样器里折叠时间；`LaneMask` 又复制一份；lane adjust 用原始时间，三条采样路径可能不同步。
- 每帧按字段名查 map、分配 `InterpolationResult`/临时向量，性能不可控。

### 2.2 目标

- 同一片段的所有对象在同一 `t` 采样；轨道装配在采样之后进行，顺序显式。
- 采样器是纯函数；状态（选择器锁、冻结、运动模型、breath 相位）由轨道/执行层持有，不藏在采样器里。
- 采样路径在加载时编译成整数槽位；每帧不按字符串查 map、不分配临时对象。
- 片段循环不改变采样器输入轴；播放控制给 `executionIndex` + `executionLocalTime`，采样器只认 `[0,duration]`。
- 静态片段（无 keyframes）也能求值；CAMERA 不再要求至少一个关键帧。
- 超界关键帧不参与求值，也不影响 `isClipUsable`。

### 2.3 非目标

- 不在运行时做 schema 类型检查/反射；数据已由 parser/validator 校验。
- 通用采样器不做曲线/缓动；`curve` 由 CAMERA 轨道按轴集合求值，缓动由编辑器在写入前烘焙成显式关键帧。
- 不在采样器里做轨道语义（相机六参数、覆盖层绘制、调色栈）。
- 不改变时间精度（float 秒）。

## 三、方案

### 3.1 一帧的求值顺序

对每个播放实例、每个活跃片段：

1. **播放控制给执行状态**：`ScriptPlayer`/`FrameDriver` 算出脚本时间；循环片段的执行状态给出 `executionIndex`、`executionLocalTime`、方向（pingpong）。`executionLocalTime` 始终在 `[0, duration]`。
2. **采样**：`ClipSampler` 对轨道需要的每个字段路径采样，写入预分配的 `SampledClip`（或按轨道类型预分配的原始数组）。所有字段用同一个 `executionLocalTime`。
3. **轨道装配**：轨道播放器读 `SampledClip`，按轨道文档定义的顺序组装语义值。装配只读采样结果，不回调采样器、不修改其他对象。
4. **轨道后处理**：相机 motion/breath/transition/modifier；overlay 图层参数；adjust 参数包；audio 实例参数。后处理的状态由轨道持有。
5. **消费/提交**：相机写 `CameraState`/lane；overlay 更新图层；adjust 发布参数；audio 更新实例；event 触发命令。

### 3.2 采样结果

```text
SampledClip
  clip
  executionIndex
  executionLocalTime
  values: float[] / int[] / bool[] / String[] / Object[]   // 按 schema 槽位
  presence: bitset                                           // 可选：区分“未提供”和“默认值”
```

- 槽位在加载时由 `SchemaRegistry` 分配：每个 track type 的对象路径 -> 固定索引；同一 track type 的所有 clip 共用槽位布局。
- `values` 按类型分数组；避免 `Map<String,Object>` 和装箱。
- `presence` 用于 `required=false` 且无默认值的对象：轨道可以区分“作者没写”与“写了默认值”。大多数轨道不需要，可按需启用。
- `event_record` 对象不进数值数组；由 `EventClipRuntime` 单独持有事件列表。

### 3.3 采样器

```text
ClipSampler
  ObjectChannel[] channels       // 按槽位
  sample(t, out SampledClip)
```

`ObjectChannel`：

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

- 轨道播放器拿到 `SampledClip` 后，按 `track-*.md` 的顺序读槽位。典型：
  - CAMERA：位置源/朝向源/角度/合成参数/调色 -> 六参数 `CameraState`；再 motion/breath/transition/modifier。
  - OVERLAY：几何/透明度/颜色/遮罩/渐变 -> 图层参数。
  - ADJUST：59 通道 -> `ColorAdjustParams`。
  - AUDIO：volume/xyz -> 实例参数。
  - LETTERBOX：aspect/opacity -> 黑边参数。
  - EVENT：事件列表 -> 触发命令；不采样数值。
- 装配函数是纯函数（输入 `SampledClip` + 片段 value-only 字段 + 外部解析结果，输出语义值）；外部解析（实体位置、点源、选择器）由轨道持有的 resolver 提供。
- 同一个 `SampledClip` 可以被多个消费者读（相机状态、lane 合成参数、lane 调色），保证同一帧同一 `t`；不再各自调用 `computeInterpolation`。

### 3.5 相机求值的显式顺序

相机是最复杂的轨道，单独列出；细节见 `track-camera.md`。

1. 采样所有对象（位置、mode/space、点源、选择器策略、朝向、角度、合成、调色）。
2. 解析点源/选择器（可能读实体/世界状态；同一个解析结果供位置/朝向/注视共用）。
3. 组装世界位置（position mode/space + position_origin/time + 方向源 `facing_source`：auto/world/entity/line，按载荷或原点回落；`fwd`/`up`/`right` 是位置偏移，base 轴没有独立角度偏移字段）。
4. 组装朝向（look_at 优先；`orient=tangent` 用 `curve` 三轴导数 + offset；手动 base+lock+offset）。
5. 应用 value-only motion。
6. 应用 breath。
7. morph：A/B 各自按 1-6 求值，再按 transition 混合。
8. 写全局相机状态；lane 快照；modifier 链；lane 合成参数。
9. 捕获 `lastWorldPos`（capture=false 时）。

- 不再有 per-frame `frame` 字段；`frame` 作为局部变量在位置/朝向组装中传递。点源解析结果显式传入，不靠调用顺序。
- `curve` 在段的空间内求值（`world` 用世界坐标，`base` 用 fwd/up/right）；base 动态时曲线随参考系整体变换，不做线性降级。
- `isClipUsable` 只检查 `[0,duration]` 内对象；静态片段直接可用。

### 3.6 循环与执行次数

- 循环是片段级的执行次数；播放控制负责 `executionIndex`/`executionLocalTime`/方向，采样器只接受 `[0,duration]`。
- 只有声明了 `loop` 的片段重复；非循环片段只执行一次，不被其他片段循环改变。
- `pingpong` 的反向由播放控制把本次执行的局部时间映射为 `duration - offset`；采样器仍按升序关键帧插值。
- 每次执行的采样结果独立；状态策略见 `loop-execution.md` §3.5（只重置玩家移动，音频/事件/相机状态按现有设计）。
- 宏观循环（脚本级）由 `ScriptPlayer` 重复执行；对象采样器不感知。

### 3.7 缓存与零分配

- 加载期：
  - 解析 `objects`，按 schema 槽位编译 `ObjectChannel`；
  - 排序/校验 keyframes；
  - 预分配 `SampledClip` 的 typed arrays；
  - 预计算 clip 的 `windowEnd`/`isInfinite`/`contentEnd` 贡献。
- 每帧：
  - 复用 `SampledClip`（每个播放实例/每个 lane 一个），不 new；
  - 采样只写 primitive 数组；
  - 装配用 primitive/record，不创建 `Map`/`String`；
  - 路径字符串只在加载/日志/错误中使用。
- morph A/B：两个预分配 `SampledClip`，不交换/复制；transition 直接读两个结果。
- lane 合成：每个 lane 一个 `LaneFrame`/`SampledClip`，复用；不按帧分配列表。
- 事件：事件列表在加载时编译；每帧只比较时间游标，不遍历所有 keyframes。

### 3.8 错误处理

- 加载期：parser/validator 报错，脚本不进入运行时。
- 运行期：数据已校验；不因对象值/关键帧缺失抛异常。外部解析失败（实体不存在、点源越界）按轨道现有失败处理：限频日志 + 回退/跳过，不中断播放。
- 采样器不做“猜测/修复”；超界关键帧已在加载时过滤。
- 断言只用于编程错误（槽位越界、类型数组不匹配），不在生产路径做昂贵检查。

## 四、技术细节

### 4.1 核心模型

```text
ClipRuntime
  Clip clip
  ClipSampler sampler
  SampledClip sampleBuffer
  EventClipRuntime events?      // 仅 EVENT/MOD_EVENT

ClipSampler
  ObjectChannel[] bySlot
  int[] pathToSlot              // 加载期编译；运行期不用
```

- `ClipRuntime` 每个播放实例每个 clip 一份；同一脚本被多个玩家播放时，实例各自持有。
- `SampledClip` 的数组长度 = 该 track type 的槽位数；不同 track 的 `SampledClip` 类型不同（或统一大数组 + 槽位段）。
- `ObjectChannel` 的值数组按类型分开；`event_record` 用 `Object[]`，只读。

### 4.2 契约

- **创建**：脚本加载/实例创建时，`ClipRuntime` 由 `Clip` + `SchemaRegistry` 构建；采样器编译一次。
- **销毁**：随实例/clip 销毁；不跨实例共享可变采样缓冲。
- **状态归谁**：采样器只有 cursor 缓存；选择器锁/冻结/运动模型/breath 相位归轨道播放器；事件游标归 `EventClipRuntime`。
- **边界**：采样器不读外部世界；点源/实体解析在装配阶段，由轨道 resolver 做。
- **线程**：采样/装配在客户端渲染线程或服务端 tick 线程；每个实例的缓冲不跨线程共享。

### 4.3 数据形态

- `SampledClip.executionIndex` 是 int；`executionLocalTime` 是 float。
- `presence` bitset 只对可选对象分配；默认不分配。
- `event_record` 的值是 schema 定义的记录（`event_type`/`command`/`position` 等），加载期校验；运行期只读。
- 采样结果不包含路径字符串；日志需要路径时从 `ObjectChannel` 取。

### 4.4 边界

- 求值器不定义轨道语义；轨道文档定义“读哪些槽位、按什么顺序组装”。
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
| 拆成三条独立 1D 曲线 | 每轴一条标量曲线 + 各自的弧长 | 1D 没有平面/空间曲线，无法定义 2D/3D 弧长；三轴时间不对齐时不是一条明确几何曲线 | 一个 `curve` 字段 + 轴集合，按所选子空间做 2D/3D 弧长 |

**本方案的缺点**：

- 每个 track type 需要一份槽位布局和 `SampledClip` 类型；新增轨道/对象要同步代码。
- 采样器/装配的边界要求轨道作者遵守；如果有人在装配里回调采样器，隐藏顺序会回来。
- `presence`/可选对象增加分支；大多数轨道可以不用，但 schema 要支持。
- 预分配缓冲增加内存占用（每实例每 clip）；需要按活跃 clip 池控制。
- morph/lane 多缓冲的复用容易出别名 bug；需要明确的“只读/可写”约定。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
