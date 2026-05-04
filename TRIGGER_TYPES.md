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

所有匹配 ID 的字段均支持三种匹配模式：

- `"minecraft:village_plains"` — 精确匹配
- `"minecraft:*"` — 命名空间通配（匹配所有 `minecraft:` 开头的 ID）
- `"village"` — 子串匹配（无冒号时，匹配任何包含 `village` 的 ID）
- `"*"` — 任意匹配

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

支持三种检测方式，满足任一即触发：

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `dimension` | string | 否 | 维度 ID，如 `"minecraft:overworld"` |
| `position` | object | 否 | 点+半径检测 `{ "x": ..., "y": ..., "z": ... }` |
| `radius` | number | 否 | 配合 `position`，默认 `0`（精确点） |
| `corner1` | object | 否 | 方体区域对角点1 `{ "x": ..., "y": ..., "z": ... }` |
| `corner2` | object | 否 | 方体区域对角点2 `{ "x": ..., "y": ..., "z": ... }` |

**点+半径：**
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

**方体区域（需同时定义两个对角点）：**
```json
{
  "type": "location",
  "conditions": {
    "dimension": "minecraft:overworld",
    "corner1": { "x": 0, "y": 60, "z": 0 },
    "corner2": { "x": 50, "y": 80, "z": 50 }
  }
}
```

不写 `position` 或 `corner` 时只检测维度。

---

## 3. `advancement`

玩家获得指定进度时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `advancement` | string | 是 | 进度 ID，支持子串匹配 |

```json
{
  "type": "advancement",
  "conditions": {
    "advancement": "minecraft:story/enter_the_nether"
  }
}
```

```json
{
  "type": "advancement",
  "conditions": {
    "advancement": "kill_a_mob"
  }
}
```

---

## 4. `biome`

玩家进入指定生物群系时触发（轮询，每 40 ticks ≈ 2 秒检测一次）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `biome` | string | 是 | 群系 ID，支持子串匹配 |

```json
{
  "type": "biome",
  "conditions": { "biome": "minecraft:desert" }
}
```

```json
{
  "type": "biome",
  "conditions": { "biome": "plains" }
}
```

---

## 5. `entity_kill`

玩家击杀指定实体时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `entity` | string 或 string[] | 是 | 实体 ID，支持子串/通配符匹配 |
| `mode` | string | 仅数组时可用 | `"or"`（默认）— 击杀任一触发；`"and"` — 全部击杀过才触发 |

**单实体：**
```json
{ "type": "entity_kill", "conditions": { "entity": "minecraft:zombie" } }
```

**通配：**
```json
{ "type": "entity_kill", "conditions": { "entity": "*" } }
```

**OR 模式（默认）：**
```json
{
  "type": "entity_kill",
  "conditions": {
    "entity": ["minecraft:zombie", "minecraft:skeleton"],
    "mode": "or"
  }
}
```

**AND 模式：**
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
  "conditions": { "target": "minecraft:jukebox" }
}
```

---

## 7. `dimension_change`

玩家切换维度时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `dimension` | string | 是 | 目标维度 ID，支持子串匹配 |

```json
{
  "type": "dimension_change",
  "conditions": { "dimension": "minecraft:the_nether" }
}
```

---

## 8. `dimension`

同 `dimension_change`。

```json
{
  "type": "dimension",
  "conditions": { "dimension": "minecraft:the_end" }
}
```

---

## 9. `item_craft`

玩家合成指定物品时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_craft",
  "conditions": { "item": "minecraft:leather_chestplate" }
}
```

---

## 10. `item_use`

玩家使用完成指定物品时触发（吃完食物、喝完药水、射完箭等）。  
仅监听 `LivingEntityUseItemEvent.Finish`，右键按下时**不**触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_use",
  "conditions": { "item": "minecraft:golden_apple" }
}
```

---

## 11. `inventory`

玩家背包物品检测（轮询，每 20 ticks ≈ 1 秒检测一次）。

支持三种模式：

| 模式 | 写法 | 说明 |
|------|------|------|
| 存在检测（AND） | `"mode": "and"`（默认） | **全部**拥有时才触发 |
| 存在检测（OR） | `"mode": "or"` | 有**任一**即触发 |
| 数量增加 | `"change": "increase"` | 物品数量增加时触发 |
| 数量减少 | `"change": "decrease"` | 物品数量减少时触发 |

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `items` | string[] | 是 | 物品 ID 列表 |
| `mode` | string | 否 | `"and"`（默认）或 `"or"` |
| `change` | string | 否 | `"increase"` 或 `"decrease"` |

**AND 模式 — 全部拥有：**
```json
{
  "type": "inventory",
  "conditions": { "items": ["minecraft:diamond", "minecraft:emerald"] }
}
```

**OR 模式 — 有任一即可：**
```json
{
  "type": "inventory",
  "conditions": { "items": ["minecraft:diamond", "minecraft:emerald"], "mode": "or" }
}
```

**数量增加 — 物品变多时触发：**
```json
{
  "type": "inventory",
  "conditions": { "items": ["minecraft:sponge"], "change": "increase" }
}
```

**数量减少 — 物品变少时触发（装备、放置、消耗）：**
```json
{
  "type": "inventory",
  "conditions": { "items": ["minecraft:diamond_helmet"], "change": "decrease" }
}
```

---

## 12. `custom`

通过外部模组/命令调用 `CustomEventTracker.fire(player, eventId)` 时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `event_id` | string | 是 | 自定义事件 ID |

```json
{
  "type": "custom",
  "conditions": { "event_id": "story_beat_1" }
}
```

---

## 13. `command`

通过服务端命令触发（预留，当前始终返回 `false`）。

```json
{
  "type": "command",
  "conditions": {}
}
```

---

## 14. `structure`

玩家进入指定结构时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。  
按配置级结构注册名匹配（如 `minecraft:village_plains`），支持子串匹配。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `structure` | string | 是 | 结构 ID，支持子串匹配，如 `"village"` 匹配所有村庄变体 |
| `radius` | int | 否 | 检测半径（方块），默认 `0`（仅检测玩家所在方块） |

```json
{
  "type": "structure",
  "conditions": { "structure": "village", "radius": 32 }
}
```

```json
{
  "type": "structure",
  "conditions": { "structure": "minecraft:fortress" }
}
```

常见结构：`minecraft:village_plains`、`minecraft:village_desert`、`minecraft:village_savanna`、`minecraft:village_taiga`、`minecraft:village_snowy`、`minecraft:fortress`、`minecraft:stronghold`、`minecraft:mineshaft`、`minecraft:ancient_city`。

---

## 15. `gamestage`

玩家拥有指定游戏阶段时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。  
需要安装 [GameStages](https://www.curseforge.com/minecraft/mc-mods/gamestages) 模组，未安装时始终返回 `false`。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `stage` | string | 是 | 阶段名称 |

```json
{
  "type": "gamestage",
  "conditions": { "stage": "entered_dungeon" }
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
          "structure": "village",
          "radius": 32
        }
      }
    ]
  }
}
```
