# 0.3.5 第 5.5 轮：相机区域原版机制激活方案

> **⚠️ 本文已并入 `preload-camera-region-unified.md`（0.3.5 预加载与相机区域统一设计）。本文保留作为 5.5 轮细节追溯，执行以统一设计文档为准。**

**状态**: 🔄 已并入统一设计（`preload-camera-region-unified.md`），服务端代码已重构，待按统一设计清理/联调
**排期**: 插在第 5 轮之后、第 6 轮（文档/回归/发布）之前
**关联**: `chunk-preload.md`（相机区域已全加载）、`audio-listener-model.md`（环境音听者模型）、`1.md`（六轮拆分）

---

## 一句话定义

在服务端和客户端分别放一个**相机锚点（Camera Anchor）**，让原版机制把它当成“一个位于相机位置的虚拟玩家”。  
要的不是“服务端模拟中心偏移”，而是：**相机区域能刷怪、能听到环境音；其余机制按需激活，且激活的全部走原版逻辑，不引入自定义规则。**

---

## 一、相机锚点

- 服务端：复用已有相机位置上报（预加载通道），作为服务端相机锚点
- 客户端：`meta.listener = "camera"` 时，相机就是锚点
- 生命周期：
  - 脚本播放期间存在
  - 脚本结束 / 退出飞行 / 释放预加载时移除

---

## 二、机制激活矩阵

| 原版机制 | 默认状态 | 说明 |
|---|---|---|
| 区块加载 / 预加载 | ✅ 已激活 | 已有，相机区域全加载 |
| 环境音采样（群系/水下/气泡柱/方块粒子音） | ✅ 激活 | 采样中心 = 相机，跟随 `listener=camera`，不加开关 |
| 自然刷怪（NaturalSpawner） | ⚠️ 开关控制 | `camera_mob_spawn=true` 时激活 |
| 刷怪距离判定 | ✅ 激活 | 刷怪时把“最近玩家距离”按相机锚点计算 |
| 实体 despawn / 留存 | ✅ 激活 | 普通怪按相机距离 despawn；村民/命名/persistent 按原版永久保留 |
| 实体 AI（移动/攻击/寻路） | ⚠️ 开关控制 | `camera_mob_ai=true` 时激活实体 tick |
| 服务器模拟距离 / 完整区块 tick | ❌ 不激活 | 不做“服务端模拟中心偏移” |
| 玩家区补发 / 实体跟踪 | ❌ 不激活 | 维持原版，只服务真实玩家 |
| 自定义清理间隔 / 白名单 | ❌ 不激活 | 一律不引入 |

---

## 三、开关（只 3 个）

| 开关 | 默认 | 作用 |
|---|---|---|
| `camera_mob_spawn` | `false` | 是否在相机区域刷怪 |
| `camera_mob_radius` | `2` | 刷怪半径（区块） |
| `camera_mob_ai` | `false` | 是否让相机区实体正常 AI |

- 环境音不做开关，直接跟随 `meta.listener`
- 不新增清理间隔、不新增实体白名单、不新增留存时长

---

## 四、实现轮廓

### 客户端

- 3 个环境音 handler + `animateTick` 采样点重定向到相机：
  - `BiomeAmbientSoundsHandler`
  - `UnderwaterAmbientSoundHandler`
  - `BubbleColumnAmbientSoundHandler`
  - `ClientLevel.animateTick`（`Minecraft.render` 调用点）
- 只在 `meta.listener = "camera"` 时生效

### 服务端

- 新增 `CameraFakePlayer` / `CameraFakeConnection`：
  - 隐藏 `ServerPlayer`，加入服务端玩家列表
  - 不可见、不出现在玩家列表、发包全部丢弃
  - 驱动原版区块加载/生成/刷怪/despawn
- `CameraMobManager`：
  - 持有每个播放者的相机假人锚点
  - 脚本结束 / 相机释放时移除假人
  - `camera_mob_spawn=false` → 假人设旁观模式（只加载不刷怪）
  - `camera_mob_ai=false` → 相机区刷出的怪用原版 NoAI 冻结
- 不再手写 `NaturalSpawner` 距离替换 / `Mob` despawn mixin
- 配置：3 个开关改为脚本 meta 字段（`camera_mob_spawn` / `camera_mob_radius` / `camera_mob_ai`），客户端随预加载请求上报

---

## 五、验收标准

1. `listener=camera` 时，镜头附近能听到环境音（群系/水下/气泡柱/方块粒子音）
2. `camera_mob_spawn=true` 时，相机附近能按原版规则刷出群系生物
3. 普通怪离相机超过 despawn 距离会按原版消失
4. 村民/命名怪/persistent 怪按原版永久保留，我们不清
5. `camera_mob_ai=false`：只刷怪/留存，不做额外 AI
6. `camera_mob_ai=true`：相机区实体正常活动
7. 脚本结束/相机释放后，临时怪按原版机制自然处理
8. 不引入任何自定义清理规则/白名单/周期

---

## 六、明确不做

- 服务端模拟中心偏移
- 完整区块 tick / 红石 / 农作物生长等模拟
- 自定义实体清理
- 自定义实体白名单
- 玩家区补发 / 实体跟踪大改造

---

## 七、待实施时再看

- 预加载 ticket 当前只保证“加载”，是否足以支撑刷怪/实体 tick
- `NaturalSpawner.SpawnState` 的构造与调用时机
- 相机锚点与真实玩家同时存在时的“最近玩家”合并逻辑
- `Mob.removeWhenFarAway` 的 Mixin 注入点
