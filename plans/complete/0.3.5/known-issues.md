# 0.3.5 已知问题记录（第 5.5 轮后）

**状态**: 已修复（2026-08-21）
**日期**: 2026-08-21

---

## 1. 触发器持久化：Forge 与 Fabric 行为不一致

### 状态

✅ 已修复。Fabric 已接入与 Forge 同一套 `TriggerStateStore` 存档读写链路：
- `ServerEventHandler.onServerStarted` → `TriggerStateStore.initialize(server)` 初始化存档目录
- `ServerPlayConnectionEvents.JOIN` → `loadForPlayer` 读取 `trigger_state/<uuid>.snbt`
- `ServerPlayConnectionEvents.DISCONNECT` → `unloadForPlayer` 保存并卸载
- `MinecraftServerMixin` → `saveEverything` 返回后 `saveAll()` 兜底
- `SERVER_STOPPING` → `saveAll()` 兜底

因此 Fabric 现在也会把 `repeatable: false` 的登录触发器写入存档，再次进入同一存档不会重复触发，与 Forge 行为一致。

### 原因（历史）

- 之前 Fabric 侧没有把 `TriggerStateStore` 的存档读写接到 Fabric 生命周期事件/存档保存上，状态只存在内存里。
- 现已补齐 Fabric 的存档保存 Mixin 与玩家加入/退出加载保存链路。

---

## 2. Forge 实体数量偏少：疑似 Forge 专属包时序问题

### 状态

✅ 已修复（2026-08-21 同村庄测试确认）。
- 修复前：Forge 客户端 `radius=64 count=23~25`
- 修复后：同一村庄位置稳定 `count=60~66`，与 Fabric 同区间

### 处理摘要

- 假人先 `moveTo` 相机坐标再 bootstrap，使 `addNewPlayer` 按原版顺序发送初始区块与实体
- `CameraFakeConnection` 解包 `ClientboundBundlePacket` 并原样转发，保持区块/实体同源时序
- 增加节流日志后确认初始 `ClientboundAddEntityPacket` 均到达客户端

---

## 附注：加载卡顿

- 之前的加载卡顿是由**泄露问题**导致的；
- 泄露修复后该问题已消失，不再作为当前未决问题记录。
