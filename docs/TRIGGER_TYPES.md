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
| `on_enter` | boolean | 否 | 仅位置类触发器有效：设为 `true` 后只在首次进入区域时触发，已在区域内不重复。默认 `false` |
| `exit_buffer` | number | 否 | 配合 `on_enter` 使用：玩家离开触发区域多少格后才标记为"已离开"，防止区域边界抖动导致反复触发。默认 `0` |
| `requires` | string[] | 否 | **前置依赖（解锁条件）**：前置脚本 id 列表，AND 语义——全部前置脚本**触发过**（跳过/打断/播完都算）本触发器才允许触发；缺省 = 无前置（立即待命）。可用于按剧情线逐级解锁。示例 `"requires": ["script_a"]` → 脚本 B 的触发器在脚本 A 触发后才生效 |

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
| `dimension` | string | 否 | 场景条件：击杀发生时的维度（如 `"minecraft:the_nether"`） |
| `biome` | string | 否 | 场景条件：击杀发生时的群系 |
| `position` | object | 否 | 场景条件：击杀位置点+半径 `{ "x": ..., "y": ..., "z": ... }` |
| `radius` | number | 否 | 配合 `position`，默认 `0` |
| `corner1`/`corner2` | object | 否 | 场景条件：击杀位置所在方体区域（两对角点） |

> 场景条件按**击杀时刻**的记录判定（被杀实体的位置/维度/群系），不是玩家当前位置。

**单实体：**
```json
{ "type": "entity_kill", "conditions": { "entity": "minecraft:zombie" } }
```

**场景组合 — 下界击杀僵尸（主世界击杀不触发）：**
```json
{
  "type": "entity_kill",
  "conditions": {
    "entity": "minecraft:zombie",
    "dimension": "minecraft:the_nether"
  }
}
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

## 6. `entity_interact`

玩家与实体交互时触发（右键点击实体）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `target` | string | 是 | 目标实体 ID，`"*"` 表示任意实体交互 |

```json
{
  "type": "entity_interact",
  "conditions": { "target": "minecraft:villager" }
}
```

---

## 7. `block_interact`

玩家与方块交互时触发（右键/左键点击方块）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `target` | string | 是 | 目标方块 ID，`"*"` 表示任意方块交互 |

```json
{
  "type": "block_interact",
  "conditions": { "target": "minecraft:jukebox" }
}
```

---

## 8. `item_on_interact`

玩家手持指定物品与指定目标（方块或实体）交互时触发。需要同时满足手持物品和目标两个条件。**[0.3.2 新增]**

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 手持物品 ID，支持子串/通配符匹配；`""`（空字符串）表示**空手** |
| `target` | string | 是 | 目标方块/实体 ID，`"*"` 表示任意目标 |
| `target_type` | string | 否 | `"block"` 或 `"entity"` — 限定目标类型；不写则方块/实体都算 |

```json
{
  "type": "item_on_interact",
  "conditions": { "item": "minecraft:carrot", "target": "minecraft:iron_block" }
}
```

**空手交互：**
```json
{
  "type": "item_on_interact",
  "conditions": { "item": "", "target": "minecraft:sheep" }
}
```

**限定目标类型 — 手持剪刀对羊：**
```json
{
  "type": "item_on_interact",
  "conditions": {
    "item": "minecraft:shears",
    "target": "minecraft:sheep",
    "target_type": "entity"
  }
}
```

---

## 9. `dimension_change`

玩家切换维度时触发。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `dimension` | string | 是 | 目标维度 ID，支持子串匹配 |
| `from_dimension` | string | 否 | 来源维度 ID，支持子串匹配；不写 = 不限制来源（旧脚本零迁移） |

```json
{
  "type": "dimension_change",
  "conditions": {
    "dimension": "minecraft:the_nether",
    "from_dimension": "minecraft:overworld"
  }
}
```

> 触发时机不变（原版切换维度事件）；求值 = 当前维度匹配 `dimension` &&（无 `from_dimension` || 来源维度匹配 `from_dimension`）。驻留型 `dimension` 共用求值器，不写 `from_dimension` 行为不变。

---

---

## 10. `item_craft`

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

## 11. `item_use`

玩家右键点击使用指定物品时触发（右键按下即触发，不等使用完成）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_use",
  "conditions": { "item": "minecraft:ender_pearl" }
}
```

---

## 12. `item_consume`

玩家**用尽**指定物品时触发（食物吃完、药水喝完——`LivingEntity.completeUsingItem()` 完成路径，Mixin 注入）。  
吃一半/喝一半松手**不**触发（走 `item_use_interrupt`）；右键按下也不触发（那是 `item_use`）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_consume",
  "conditions": { "item": "minecraft:golden_apple" }
}
```

---

## 13. `item_release`

玩家**松手释放**指定物品时触发（弓/弩满蓄松手射箭、三叉戟掷出、望远镜收起）。  
按 `UseAnim` 判定：BOW / CROSSBOW / SPEAR / SPYGLASS 属于"释放"；不贴合原版状态机的模组自定义 `UseAnim.CUSTOM` 一律归入 `item_use_interrupt`。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_release",
  "conditions": { "item": "minecraft:bow" }
}
```

---

## 14. `item_instant_use`

玩家**瞬间使用**指定物品时触发（扔雪球/鸡蛋/末影珍珠、投掷喷溅与滞留药水、经验瓶）。  
通过投掷物实体（`ThrowableItemProjectile`）加入世界判定，覆盖雪球/鸡蛋/珍珠/药水/经验瓶；**不覆盖**烟花火箭与末影之眼。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_instant_use",
  "conditions": { "item": "minecraft:snowball" }
}
```

---

## 15. `item_use_interrupt`

玩家**中断使用**指定物品时触发（食物/药水吃一半松手、普通物品右键后松开、模组自定义 UseAnim 物品）。  
弓/弩/三叉戟/望远镜的松手归 `item_release`，不在此列。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_use_interrupt",
  "conditions": { "item": "minecraft:bread" }
}
```

---

## 16. `item_pickup`

玩家拾取指定物品时触发（捡起掉落物）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_pickup",
  "conditions": { "item": "minecraft:diamond" }
}
```

---

## 17. `item_drop`

玩家丢弃指定物品时触发（Q 键丢出）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `item` | string | 是 | 物品 ID，支持子串/通配符匹配 |

```json
{
  "type": "item_drop",
  "conditions": { "item": "minecraft:*" }
}
```

---

## 18. `xp`

玩家的经验条件达成时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `level` | int | 否 | 经验等级达到该值（`experienceLevel`） |
| `total` | int | 否 | 累计总经验点达到该值（`totalExperience`，**含已花费**，非当前持有量） |

`level` 与 `total` 至少写一个，同时写则两者都满足（AND）。

```json
{
  "type": "xp",
  "conditions": { "level": 30 }
}
```

```json
{
  "type": "xp",
  "conditions": { "total": 1000 }
}
```

---

## 19. `dimension`

玩家**驻留**在指定维度时触发（轮询，每 20 ticks ≈ 1 秒检测一次）。  
与 `dimension_change`（切换瞬间触发一次）的区别：`dimension` 是状态型，配合 `on_enter` 可实现"进入某维度时"语义。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `dimension` | string | 是 | 维度 ID，支持子串/通配匹配（如 `"mod:*"`） |

```json
{
  "type": "dimension",
  "conditions": { "dimension": "minecraft:the_nether" }
}
```

---

## 20. `observation`

玩家准星注视指定目标时触发（轮询，每 5 ticks ≈ 0.25 秒检测一次，服务端射线）。  
配合 `on_enter` 即"注视进入"语义：首次注视到目标时触发一次，移开后再次注视可再触发（配合 `repeatable`）。

| 条件字段 | 类型 | 必需 | 说明 |
|---------|------|------|------|
| `target` | string | 是 | 目标方块/实体 ID |
| `target_type` | string | 否 | `"block"` 只查方块、`"entity"` 只查实体；不写两者都查、命中距离近者优先 |
| `reach` | number | 否 | 射线距离（格），默认 `4.5` |

```json
{
  "type": "observation",
  "conditions": { "target": "minecraft:sheep", "target_type": "entity" }
}
```

```json
{
  "type": "observation",
  "conditions": { "target": "minecraft:lectern", "target_type": "block", "reach": 6 }
}
```

---

## 21. `inventory`

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

## 22. `structure`

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

## 23. `gamestage`

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
