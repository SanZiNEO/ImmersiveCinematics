# 方块搜索基准（Block-Based Reference）：相对坐标/注视目标的基准可以是"玩家附近搜索到的方块"

## 一句话定义

`relative_origin`（position 相对基准）与 `look_at_target`（注视目标）的基准体系再增加一种：**在玩家附近搜索匹配的方块**（如传送门框架 obsidian、钻石矿），以找到的方块位置为基准——脚本可以直接写"相对传送门方块的坐标"、"看向最近的水井"之类。

## 场景（脚本 9 例子）

回到主世界后要播一段镜头，位置想基于**传送门框架方块**：

```json
{
  "time": 0,
  "position": { "dx": 0, "dy": 3, "dz": -5 },     // 相对基准的偏移
  "position_mode": "relative",
  "relative_origin": "block:minecraft:obsidian",   // 基准 = 玩家附近最近的 obsidian
  "yaw": 180, "pitch": -10
}
```

搜索在玩家附近（小半径，如 32 格内）遍历方块，取**最近**的匹配方块作为基准点。玩家在传送门旁触发 → 基准 = 传送门框架 → 镜头在框架上方 3 格、前方 5 格的位置。

## 基准体系全景（规划中）

| 基准 | 现有/计划 | 语义 |
|---|---|---|
| 玩家激活位置 | ✅ 现有 | relative_origin 缺省 |
| `"coordinate"` + x/y/z | ✅ 现有 | 固定坐标 |
| 结构 id（中心） | ✅ 现有 | 结构中心（就近搜寻） |
| 实体（look_at） | ✅ 现有 | 实体正中心 |
| **方块 id + 半径** | 🆕 本方案 | **玩家附近最近的匹配方块** |

## 设计草案

### 写法（沿用字符串扩展，兼容现有）

```json
"relative_origin": "block:minecraft:obsidian",          // 默认搜索半径
"relative_origin": "block:minecraft:obsidian:64",       // 显式半径（格）
```

或结构化对象（与 look_at_target 方案统一）：

```json
"relative_origin": { "type": "block", "block": "minecraft:obsidian", "radius": 32 }
```

### 搜索实现

- 范围：玩家附近小半径（默认 16~32 格，脚本场景玩家必然在目标方块旁），**不做多区块搜索**
- 遍历：xz 平面按方块遍历 + y 范围限制（玩家 y ± 8~16），匹配方块 id 后取最近
- 开销评估：32 格半径 ≈ 65×65×17 ≈ 7 万次 getBlockState——一次性可接受；16 格半径 ≈ 1.8 万次
- **缓存**：方块是静态的（传送门不会动）——解析成功缓存（与结构基准同语义：成功永久、失败短重试）
- 服务端推送前替换（`/icinematics play` 时按执行者位置解析）+ 客户端编辑器预览兜底（复用 resolveStructurePos 模式）

### 应用面

- `relative_origin`：position 相对基准（用户例子）
- `look_at_target`：注视目标（"看向最近的水井/高塔/传送门"）
- 与 look-at-relative-target 方案共用基准解析器（structure/entity/coordinate/block 统一）

## 边界与待定

1. 搜索半径默认值（16 vs 32）与 y 范围——待定，跟"玩家在目标旁"场景平衡开销
2. 多匹配取最近——遍历时记录最近距离即可
3. 方块 tag 支持（如 `#minecraft:logs`）还是仅精确 id——先精确 id
4. 与预设系统的组合：预设"回主世界开场"直接内置方块基准参数

## 依赖

- 基准解析器统一（与 look-at-relative-target 一起做）
- 服务端/客户端双路径（resolveStructurePos 模式复用）
- 不依赖 yaw 基准/预设，可独立排期
