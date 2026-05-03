# Immersive Cinematics — 触发类型参考

每个脚本的 `meta.triggers` 数组可定义多个触发条件。  
每个 trigger 包含以下通用字段：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 触发器的唯一标识（脚本内不重复即可） |
| `type` | string | 是 | 触发类型，见下方各类型详情 |
| `repeatable` | boolean | 否 | 是否可重复触发，默认 `false` |
| `delay` | number | 否 | 触发后延迟执行（秒），默认 `0` |
| `conditions` | object | 否 | 各类型特有的条件，见下方 |

---

## 1. `login`

玩家登录时触发一次。

```json
{
  "type": "login",
  "conditions": {}
}
```

---

## 2. `location`

玩家进入指定位置/维度时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `dimension` | string | 否 | 维度 ID，如 `"minecraft:overworld"`、`"minecraft:the_nether"` |
| `position` | object | 否 | 坐标 `{ "x": ..., "y": ..., "z": ... }` |
| `radius` | number | 否 | 检测半径（方块），配合 `position` 使用，默认 `0`（精确坐标） |

```json
{
  "type": "location",
  "conditions": {
    "dimension": "minecraft:overworld",
    "position": { "x": 100, "y": 64, "z": 200 },
    "radius": 10
  }
}
```

不写 `position` 时只检测维度。

---

## 3. `advancement`

玩家获得指定进度时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `advancement` | string | 是 | 进度 ID，如 `"minecraft:story/mine_diamond"` |

```json
{
  "type": "advancement",
  "conditions": {
    "advancement": "minecraft:story/enter_the_nether"
  }
}
```

---

## 4. `biome`

玩家进入指定生物群系时触发（轮询，每 40 ticks ≈ 2 秒检测一次）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `biome` | string | 是 | 群系 ID，如 `"minecraft:plains"`、`"minecraft:jungle"` |

```json
{
  "type": "biome",
  "conditions": {
    "biome": "minecraft:swamp"
  }
}
```

---

## 5. `entity_kill`

玩家击杀指定实体时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `entity` | string 或 string[] | 是 | 实体 ID，支持通配符 `*`（任意实体）和命名空间通配 `minecraft:*` |
| `mode` | string | 仅数组时可用 | `"or"`（默认）— 击杀任一即触发；`"and"` — 全部击杀过至少一次才触发 |

**单实体：**
```json
{
  "type": "entity_kill",
  "conditions": { "entity": "minecraft:zombie" }
}
```

**通配：**
```json
{
  "type": "entity_kill",
  "conditions": { "entity": "*" }
}
```

**OR 模式（默认）— 击杀任一触发：**
```json
{
  "type": "entity_kill",
  "conditions": {
    "entity": ["minecraft:zombie", "minecraft:skeleton"],
    "mode": "or"
  }
}
```

**AND 模式 — 全部击杀过才触发：**
```json
{
  "type": "entity_kill",
  "conditions": {
    "entity": ["minecraft:zombie", "minecraft:skeleton", "minecraft:spider"],
    "mode": "and"
  }
}
```

---

## 6. `interact`

玩家与方块或实体交互时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `target` | string | 是 | 目标方块/实体 ID，`"*"` 表示任意交互 |

```json
{
  "type": "interact",
  "conditions": {
    "target": "minecraft:crafting_table"
  }
}
```

---

## 7. `dimension_change`

玩家切换维度时触发。与 `dimension` 功能相同。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `dimension` | string | 是 | 目标维度 ID |

```json
{
  "type": "dimension_change",
  "conditions": {
    "dimension": "minecraft:the_nether"
  }
}
```

---

## 8. `dimension`

同 `dimension_change`。

```json
{
  "type": "dimension",
  "conditions": {
    "dimension": "minecraft:the_end"
  }
}
```

---

## 9. `item_craft`

玩家合成指定物品时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持 `minecraft:*` 命名空间通配 |

```json
{
  "type": "item_craft",
  "conditions": {
    "item": "minecraft:diamond_sword"
  }
}
```

---

## 10. `inventory`

玩家背包中包含所有指定物品时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `items` | string[] | 是 | 物品 ID 列表，**全部**拥有时才触发 |

```json
{
  "type": "inventory",
  "conditions": {
    "items": ["minecraft:diamond", "minecraft:emerald"]
  }
}
```

---

## 11. `custom`

通过外部模组/命令调用 `CustomEventTracker.fire(player, eventId)` 时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `event_id` | string | 是 | 自定义事件 ID |

```json
{
  "type": "custom",
  "conditions": {
    "event_id": "story_beat_1"
  }
}
```

---

## 12. `command`

通过服务端命令触发（预留，当前始终返回 `false`）。

```json
{
  "type": "command",
  "conditions": {}
}
```

---

## 13. `structure`

玩家进入指定结构时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。  
按 `StructureType` 匹配，支持 `radius` 扩大检测范围。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `structure` | string | 是 | 结构类型 ID，如 `"minecraft:village"`、`"minecraft:fortress"` |
| `radius` | int | 否 | 检测半径（方块），默认 `0`（仅检测玩家所在方块） |

```json
{
  "type": "structure",
  "conditions": {
    "structure": "minecraft:village",
    "radius": 32
  }
}
```

常见结构 ID：`minecraft:village`、`minecraft:fortress`、`minecraft:temple`、`minecraft:stronghold`、`minecraft:mineshaft`、`minecraft:shipwreck`、`minecraft:ancient_city`、`minecraft:trail_ruins`。

---

## 14. `gamestage`

玩家拥有指定游戏阶段时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。  
需要安装 [GameStages](https://www.curseforge.com/minecraft/mc-mods/gamestages) 模组，未安装时始终返回 `false`。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `stage` | string | 是 | 阶段名称 |

```json
{
  "type": "gamestage",
  "conditions": {
    "stage": "entered_dungeon"
  }
}
```

---

## 完整示例

```json
{
  "meta": {
    "id": "my_cinematic",
    "name": "示例脚本",
    "author": "ImmersiveCinematics",
    "version": 3,
    "triggers": [
      {
        "id": "on_login",
        "type": "login",
        "repeatable": false,
        "delay": 1.0
      },
      {
        "id": "enter_village",
        "type": "structure",
        "repeatable": true,
        "conditions": {
          "structure": "minecraft:village",
          "radius": 32
        }
      }
    ]
  }
}
```
