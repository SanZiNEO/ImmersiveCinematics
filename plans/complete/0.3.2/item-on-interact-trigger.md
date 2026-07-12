# ⑨ 物品交互触发器 — item_on_interact

**版本**: 0.3.2  
**类型**: 新功能  
**文件数**: 2 (Evaluators.java + ImmersiveCinematics.java)  
**执行方式**: 按文件顺序照做

---

## 设计

现有 `interact` 只查目标（方块/实体），`item_use` 只查物品。新增 `item_on_interact` 同时检查两者。

脚本格式：
```json
{
  "trigger_id": "boss_summon",
  "type": "item_on_interact",
  "conditions": {
    "item": "minecraft:carrot",
    "target": "minecraft:iron_block"
  }
}
```

`item` 和 `target` 都支持通配符 `*` 和 `mod_id:*`。

---

## 文件 1 — Evaluators.java

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/trigger/server/evaluator/Evaluators.java`

### 步骤 1a — InteractTracker 新增 item 追踪

在第 338 行 `clear()` 方法后、`}` 闭包前，加:

```java
private static final Map<UUID, String> lastInteractionItems = new java.util.HashMap<>();

public static void recordInteractionItem(UUID uuid, ItemStack stack) {
    if (stack.isEmpty()) return;
    lastInteractionItems.put(uuid, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
}

public static String getLastInteractionItem(ServerPlayer player) {
    return lastInteractionItems.get(player.getUUID());
}
```

### 步骤 1b — InteractTracker.clear 同步清理 item

在第 338 行 `lastInteractions.remove(uuid);` 之后加:

```java
lastInteractionItems.remove(uuid);
```

### 步骤 1c — 新增 evaluateItemOnInteract

在第 164 行 `evaluateItemUse` 方法之后、第 166 行 `evaluateInventory` 之前，加:

```java
public static boolean evaluateItemOnInteract(ServerPlayer player, JsonObject c) {
    if (!c.has("item") || !c.has("target")) return false;
    String lastItem = InteractTracker.getLastInteractionItem(player);
    if (lastItem == null) return false;
    String itemPattern = c.get("item").getAsString();
    if (!matchesId(lastItem, itemPattern)) return false;
    String lastTarget = InteractTracker.getLastInteraction(player);
    if (lastTarget == null) return false;
    String targetPattern = c.get("target").getAsString();
    return matchesId(lastTarget, targetPattern);
}
```

---

## 文件 2 — ImmersiveCinematics.java

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/ImmersiveCinematics.java`

### 步骤 2a — 注册新 TriggerType

在第 113 行 `item_use` 注册之后（第 114 行 `}` 之前），加:

```java
TriggerRegistry.register(new TriggerType("item_on_interact", ListenStrategy.EVENT_DRIVEN, 0,
        Evaluators::evaluateItemOnInteract, Set.of(
                PlayerInteractEvent.RightClickBlock.class,
                PlayerInteractEvent.LeftClickBlock.class,
                PlayerInteractEvent.EntityInteract.class)));
```

### 步骤 2b — 事件处理器记录手持物品

在 3 个事件处理器中加 `recordInteractionItem`:

**onPlayerInteractBlock (Line 179-183)**:
```diff
  Evaluators.InteractTracker.recordBlock(player.getUUID(), event.getLevel().getBlockState(event.getPos()));
+ Evaluators.InteractTracker.recordInteractionItem(player.getUUID(), player.getItemInHand(event.getHand()));
  TriggerEngine.INSTANCE.onGameEvent(event, player);
```

**onPlayerLeftClickBlock (Line 186-190)**:
```diff
  Evaluators.InteractTracker.recordBlock(player.getUUID(), event.getLevel().getBlockState(event.getPos()));
+ Evaluators.InteractTracker.recordInteractionItem(player.getUUID(), player.getItemInHand(event.getHand()));
  TriggerEngine.INSTANCE.onGameEvent(event, player);
```

**onPlayerInteractEntity (Line 193-197)**:
```diff
  Evaluators.InteractTracker.recordEntity(player.getUUID(), event.getTarget().getType());
+ Evaluators.InteractTracker.recordInteractionItem(player.getUUID(), player.getItemInHand(event.getHand()));
  TriggerEngine.INSTANCE.onGameEvent(event, player);
```

---

## 完成检查清单

- [ ] InteractTracker 新增 lastInteractionItems map + recordInteractionItem + getLastInteractionItem
- [ ] InteractTracker.clear 同步清理 lastInteractionItems
- [ ] Evaluators 新增 evaluateItemOnInteract 方法
- [ ] ImmersiveCinematics.registerTriggerTypes 注册 item_on_interact
- [ ] 3 个事件处理器加 recordInteractionItem 调用
- [ ] 编译通过
- [ ] 用 carrot 右键 iron_block 能触发 item_on_interact
- [ ] 用 carrot 右键其他方块不触发（target 不匹配）
- [ ] 用其他物品右键 iron_block 不触发（item 不匹配）
