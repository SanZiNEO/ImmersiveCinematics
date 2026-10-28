# EVENT / MOD_EVENT 轨道

> 状态：未实现
> 范围：EVENT 离散事件对象、事件记录、边沿触发、事件身份、服务端/客户端分工、MOD_EVENT 占位
> 关联：`object-model.md`、`runtime-evaluation.md`、`loop-execution.md`；参考 `docs/SCRIPT_FORMAT.md` §7–§8、`trigger/server/ScriptEventManager.java`、`script/PlayerMoveController.java`

## 一、摘要

EVENT / MOD_EVENT 不是采样轨道：事件是**离散边沿**，不是随时间插值的数值。它们仍放在 `objects` 字段容器里，但字段是 `event`（MOD_EVENT 是 `mod_event`），关键帧值是事件记录，`interp` 为无；运行时按时间游标触发一次，不做值插值。EVENT 记录至少包含 `command`，可选 `position`（玩家移动目标）；MOD_EVENT 记录包含 `event_type`/`data`。事件身份 = 播放实例 + clip + 对象路径 + 事件索引；不再用 `clipIndex<<16|kfIndex` 这种位打包键。

## 二、为什么做

### 2.1 问题

- EVENT 的 `command` 挂在共享关键帧上，`event_type`/`position` 也在同一帧；对象模型没有“离散事件”的类型，直接拆成数值对象会丢失“命令 + 位置”的原子性。
- 服务端用 `clipIndex<<16|kfIndex` 作为已触发键；关键帧索引在对象模型里不存在，且 16 位上限是隐式约束。
- `PlayerMoveController` 从同一关键帧读 `position.x/z` 或 `dx/dz`；如果拆成独立叶子，x/z 时间不同就无法配对。
- `MOD_EVENT` 的 `ModEventTrackPlayer` 是占位（永远不活跃），但 schema/格式仍存在；对象模型需要给它一个明确的容器，避免“以后再加一套”。

### 2.2 目标

- 事件保持原子记录：一条事件 = 一个 `{time, value}`，`value` 内含该事件需要的字段；不拆成可独立插值的叶子。
- 边沿触发：进入事件时间点触发一次；不按帧重复触发；seek/循环的行为明确。
- 事件身份稳定：实例 + clip + 对象路径 + 事件索引；服务端/客户端用同一身份去重。
- 片段执行循环不重复触发事件：同一实例内每条事件只触发一次；重新播放/重入才重置。
- EVENT 与 MOD_EVENT 共用容器/触发骨架；MOD_EVENT 的运行时可以暂时空实现，但格式/校验完整。
- 不把事件塞进 `SampledClip` 的数值槽位；事件列表单独编译/游标。

### 2.3 非目标

- 不实现新的命令类型/权限模型；`command` 仍走现有服务端执行链。
- 不实现 `MOD_EVENT` 的第三方消费；本轮只定义格式/校验/占位。
- 不把 EVENT 变成可循环反向的轨道；`pingpong` 在 EVENT 上拒绝。
- 不改变 `PlayerMoveController` 的移动数学；只改变事件记录来源/配对方式。

## 三、方案

### 3.1 EVENT 对象

```json
{
  "start_time": 0,
  "duration": 5,
  "objects": {
    "event": {
      "keyframes": [
        {"time": 0, "value": {"command": ""}},
        {"time": 2, "value": {"command": "/time set 6000"}},
        {"time": 5, "value": {"command": "/say 结束"}}
      ]
    }
  }
}
```

- `event` 是离散字段；`value` 可为 `null`/省略；`keyframes` 是事件序列。
- 每个 keyframe 的 `value` 是事件记录：
  - `event_type`：可选，缺省取 value-only `event_type` 字段（默认 `command`）；本轮 EVENT 只接受 `command`。
  - `command`：字符串；空字符串 = 占位，不执行。
  - `position`：可选，`{x,z}`（绝对）或 `{dx,dz}`（相对）；供 `PlayerMoveController` 配对。一个记录内的 command + position 原子绑定，不拆叶子。
- `event` 对象没有 `interp`；keyframes 时间严格递增；超界事件忽略；重复时间报错。
- value-only `event_type` 字段保留为默认值；如果记录里也写，记录优先。
- 旧格式的 `keyframes: [{time, command, event_type, position}]` 迁移为 `event` 对象的记录；一个旧关键帧 = 一个事件记录。

### 3.2 触发

- 服务端 `ScriptEventManager` 对每个播放实例持事件游标：
  - 按 `event.time` 升序；
  - 当 `elapsed >= clipStart + event.time` 且该事件未触发 → 触发一次，游标前进；
  - 同一帧跨过多个事件 → 按时间顺序全部触发；
  - 事件时间相同（同一对象内不允许）不会出现；不同对象/不同 clip 可以同时间，按 clip/轨道顺序。
- `command` 为空 = 占位，标记已触发但不执行。
- 暂停/挂起：事件时间线随实例暂停，恢复从内容位置继续；不按墙钟补触发。
- seek：跳到目标时间后，游标重置到“目标时间之后的下一个事件”；目标时间之前的事件不补触发。若作者需要 seek 后重放，使用规则动作/重新播放，不在事件层做。
- 循环：EVENT 不参与客户端循环；服务端 EVENT 按自己的时间线推进，事件只触发一次，不按片段执行次数重复。片段级 `loop` 不改变服务端事件的重复性；宏观循环也不重放服务端 EVENT（现有口径）。`pingpong` 在 EVENT 上拒绝。
- 如果作者需要“每次执行都执行命令”，不要依赖 EVENT 循环；用规则动作/显式分段/多条事件轨道表达。该边界在 `loop-execution.md` §3.5 统一说明。
- 客户端 `PlayerMoveController` 读同一事件记录：只处理带 `position` 的记录；`command` 记录忽略。服务端只处理 `command`，忽略 `position`。两边用同一事件身份去重/对齐。

### 3.3 事件身份

- `EventKey` = `(instanceId, clipIndex, objectPath, eventIndex)`；`eventIndex` 是对象内 keyframes 的稳定索引（加载时确定）。
- 服务端/客户端各自持有已触发集合；`instanceId` 区分同脚本多实例。
- 不再使用 `clipIndex<<16|kfIndex`；事件数量不受 16 位限制。
- 事件记录可以带作者 `id`（可选字符串）用于跨脚本/跨实例识别；没有 `id` 时用 `EventKey`。本轮不要求 `id`，但 schema 预留。

### 3.4 MOD_EVENT

```json
{
  "objects": {
    "mod_event": {
      "keyframes": [
        {"time": 1, "value": {"event_type": "mymod:animation", "data": {"foo": 1}}}
      ]
    }
  }
}
```

- `mod_event` 是离散字段；记录字段 `event_type`（必填）、`data`（可选 map）。
- value-only `event_type`/`data` 字段保留为默认/结构；记录优先。
- `ModEventTrackPlayer` 仍是占位：`isActiveAt` 永远 false，不消费；格式/validator 完整，第三方以后可以接。
- MOD_EVENT 不参与循环反向；`pingpong` 拒绝。

### 3.5 作者看到的形态

- 编辑器把 `event` 显示为事件列表（时间 + 命令/位置），不是关键帧曲线；时间轴上显示为竖线/标记。
- 新增事件 = 在 `event` 对象加一个 keyframe；删除/移动事件只动该对象。
- 事件记录字段由 schema 的 `event_record` 子 schema 定义（`event_type`/`command`/`position`/`data`），编辑器按事件类型显示表单。
- 同一 clip 内可以有多个事件对象？本轮只允许一个 `event`（EVENT）或 `mod_event`（MOD_EVENT），避免顺序歧义；需要多组事件用多条轨道。

## 四、技术细节

### 4.1 核心模型

```text
EventClipRuntime
  EventKey[] keys
  EventRecord[] records       // 按时间升序
  int cursor
  BitSet fired

EventRecord
  time
  eventType
  command?
  position? {x,z} | {dx,dz}
  data?
```

- 加载时编译：对象 keyframes -> `records`（过滤超界、校验时间/类型）；`keys` 与 records 一一对应。
- `EventClipRuntime` 每个播放实例每个 clip 一份；`fired` 用于去重/seek。
- 服务端/客户端可以各自持有 runtime；记录只读。

### 4.2 契约

- **创建**：clip 加载时编译事件列表；实例创建时分配 `EventClipRuntime`。
- **销毁**：随实例/clip 销毁；清理 `fired`/游标。
- **状态归谁**：游标/已触发集合归 `EventClipRuntime`；服务端执行结果归 `ScriptEventManager`；客户端移动目标归 `PlayerMoveController`。
- **边界**：事件 runtime 不采样数值；不进入 `SampledClip`；不参与相机/覆盖层/调色。

### 4.3 数据形态

- `time` 有限、严格递增；超界忽略。
- `command` 字符串；空 = 占位。
- `position` 是记录内的 map，不是独立对象；`x/z` 或 `dx/dz` 二选一，不能混用；数值有限。
- `data` 是任意 JSON map；MOD_EVENT 原样传给消费者；本轮不校验内部结构。
- `event_type` 字符串；EVENT 只接受 `command`；MOD_EVENT 必填。

### 4.4 边界

- 不改变命令执行/权限/回执；`command` 仍走服务端现有链路。
- 不改变玩家移动数学；只改变配对来源。
- 不把事件做成可插值对象；`interp` 无。
- 不实现 MOD_EVENT 消费；占位。
- 不参与相机/覆盖层/调色采样。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 拆成 `command`/`position.x`/`position.z` 叶子 | 每个字段独立关键帧 | 命令与位置无法原子配对；同一事件的时间可能被拆散；事件身份变复杂 | 事件是原子记录 |
| 保留旧 `keyframes` 数组 | EVENT 单独一套数组，不进对象容器 | 与对象模型不一致；事件轨道仍要特殊 parser/editor | 用 `event` 离散对象统一容器 |
| 一条事件一个对象 | `event.1`、`event.2`…… | 对象数量爆炸；顺序/身份/编辑器都复杂 | 一个 `event` 对象 + keyframes |
| 服务端/客户端各自解析旧 keyframes | 保留 `clipIndex<<16\|kfIndex` | 位打包上限；对象模型无 kfIndex；两套解析 | 统一 `EventKey` |
| EVENT 参与 pingpong 反向 | 反向触发命令 | 命令反向执行没有语义；服务端状态不可逆 | 拒绝 pingpong |

**本方案的缺点**：

- 事件记录是“对象容器里的非插值值”，类型系统要多一个 `event_record`；与“叶子对象”原则有例外，需要在文档/编辑器里解释。
- 一个 clip 只允许一个事件对象，事件分组要靠多条轨道；轨道数量可能增加。
- 服务端/客户端双端各自持有 runtime，去重/seek 行为要严格对齐，否则命令/移动会重复或漏。
- 宏观循环不重放 EVENT、片段级循环重放 EVENT 的差异需要作者理解。
- `position` 记录仍是 map，不是叶子；玩家移动无法只动 x 或 z 的关键帧，但事件本来就是离散点，不需要插值。

## 六、实现结果

状态：未实现

| 原设计 | 实际实现 | 偏离原因 |
|---|---|---|
| 未实现 | — | — |
