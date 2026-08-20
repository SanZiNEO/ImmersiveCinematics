# 0.3.5 代码审查一轮文档

> 状态：收集完成，待逐条探索
> 范围：本次 0.3.5 所有待提交改动（假人、预加载、环境音、脚本 meta、跨平台 Mixin 等）
> 原则：先探索、后动手；不写 try/catch；用事实和源码说话

---

## 一、Mixin 相关

### 1.1 BiomeAmbientSoundsHandlerMixin 硬编码 lambda 方法名
- **严重度**：高
- **问题**：
  - Fabric 的 mood 音效 lambda 合成方法名是 `method_26271`
  - Forge 的是 `lambda$tick$3`
  - 当前用 `method="method_26271"` / `method="lambda$tick$3"` 分别注入，并 `require=0`
- **隐患**：
  - MC 小版本升级、Forge/Fabric 映射变化、lambda 编译顺序变化都可能断
  - 编译期持续出现 `Unable to determine descriptor` 警告
- **探索方向**：
  - 能否不 targeting lambda 名字，改成一个更稳定的注入点？
  - 能否用 `LocalPlayer` 坐标方法的通用拦截，而不是针对 `BiomeAmbientSoundsHandler` 内部 lambda？

### 1.2 PlayerListMixin 用字符串判断假人
- **严重度**：中
- **问题**：
  - 用 `message.contains("camera_anchor")` 判断是否跳过加入消息
  - 对 `Logger.info` 的重定向有 `Unable to locate method mapping` 警告
- **隐患**：
  - 假人改名会失效
  - 真实玩家叫 `camera_anchor` 会被误伤
  - 外部 Logger 映射警告存在不确定性
- **探索方向**：
  - 是否有更可靠的方式拿到当前加入的 `ServerPlayer` 并判断 `instanceof CameraFakePlayer`？
  - 能否避免对 `org.slf4j.Logger` 做 Mixin 重定向？

### 1.3 SoundEngineMixin 双平台双目标
- **严重度**：低（当前可用）
- **问题**：
  - Fabric 目标：`SoundBufferLibrary.getStream`
  - Forge 目标：`SoundInstance.getStream`
  - 两个注入都 `require=0`
- **隐患**：
  - `require=0` 会掩盖未来映射错误
- **探索方向**：
  - 是否可以拆成平台专属 Mixin，避免公共 Mixin 里依赖 `require=0`？

### 1.4 MinecraftMixin 三个 ModifyArg 重复
- **严重度**：低
- **问题**：三个 `@ModifyArg` 分别改 x/y/z，逻辑重复
- **探索方向**：是否可合并成更集中的注入，或保持现状

---

## 二、假人链路

### 2.1 CameraFakePlayer.tick() 不调用 super.tick()
- **严重度**：中
- **问题**：为阻止移动/坠落，`tick()` 完全跳过 `super.tick()`
- **隐患**：`tickCount` 不增长，部分玩家逻辑不跑；反常规
- **探索方向**：是否有更安全的“假人冻结”方式，或至少补上必要的 tick 逻辑

### 2.2 updateFakePlayerPosition 每 10 tick 调 connection.teleport()
- **严重度**：高
- **问题**：假连接不回 ACK，`ServerGamePacketListenerImpl.awaitingTeleport` 可能累积
- **探索方向**：改用 `moveTo()` + `chunkSource.move()`，避免反复 `teleport()`

### 2.3 PlayerList.placeNewPlayer/remove 产生玩家数据落盘
- **严重度**：高
- **问题**：
  - `placeNewPlayer` 会读取旧假人数据（日志里出现旧坐标）
  - `remove` 会保存假人玩家数据
- **探索方向**：能否用“不落盘”的假人注册方式；或每次随机 UUID + 清理数据文件

### 2.4 CameraFakeConnection 手动维护世界包白名单
- **严重度**：中
- **问题**：一大串 `instanceof` 决定转发哪些包
- **隐患**：版本升级容易漏包；某些实体视觉状态可能没转发
- **探索方向**：是否有更稳定的“黑名单/白名单”策略，或能否直接复用原版跟踪链路

### 2.5 syncedEntityIds 只增不减
- **严重度**：低
- **问题**：实体消失后 ID 仍留在集合里，长期运行内存增长
- **探索方向**：收到 `ClientboundRemoveEntitiesPacket` 时清理对应 ID

### 2.6 sendEntitiesForChunk 每次 new ServerEntity
- **严重度**：中
- **问题**：为补发实体临时创建 `ServerEntity`，不是原版正常跟踪对象
- **探索方向**：能否拿到 `ChunkMap` 里已有的 `ServerEntity`，而不是新建

---

## 三、预加载 / 区块

### 3.1 ChunkPreloadManager 死代码
- **严重度**：中
- **问题**：`classify()`、`scanCache`、`scanInFlight`、`worldgenIssued` 已无人使用
- **探索方向**：清理死代码，更新注释

### 3.2 ChunkTicketPool 注释过时
- **严重度**：低
- **问题**：文档还说 far 相机区 / prewarm 共享，实际现在只有 prewarm 在用
- **探索方向**：更新注释

### 3.3 countEntities() 每秒全量扫描 625 个区块
- **严重度**：中
- **问题**：为了日志，每秒 `getEntities` 扫 625 块，性能不低
- **探索方向**：改为 debug 级别，或降低频率（如 5 秒一次），或只统计相机区

---

## 四、脚本 meta / 网络

### 4.1 C2SPreloadRequestPacket 协议扩展没有版本保护
- **严重度**：中
- **问题**：加了 3 个字段，旧客户端/旧服务端会直接错位
- **探索方向**：协议版本化或兼容读取

### 4.2 PreloadRequester.releaseIfNeeded() 写死 false, 2, false
- **严重度**：低
- **问题**：RELEASE 模式虽然服务端忽略，但魔法值不优雅
- **探索方向**：用常量或默认值对象

---

## 五、其它

### 5.1 测试脚本
- 三个测试脚本都加了 `camera_mob_*`，没问题
- `test_camera_fixed_village.json` 坐标已改为 `(-288,-60,0)`

### 5.2 规划文件
- `task_plan.md / findings.md / progress.md` 目前在仓库未跟踪
- 如果不想提交这些开发过程文件，需要加入 `.gitignore` 或删除

---

## 优先处理建议

1. 假人 `connection.teleport()` ACK 累积（运行时风险）
2. 假人玩家数据落盘 / 旧数据读取（不干净）
3. `BiomeAmbientSoundsHandlerMixin` lambda 方法名硬编码（版本脆弱）
4. `ChunkPreloadManager` 死代码 + 每秒全量扫描（性能/整洁）

---

## 新发现：脚本结束后状态不干净

### 6.1 脚本自动播放结束后玩家无法移动
- **现象**：脚本播放完自动停止后，只能转视角，不能移动
- **疑似**：输入封锁（`block_keyboard` / `block_mouse`）或玩家移动控制未在停止时恢复
- **探索方向**：检查 `ScriptPlayer` / `CameraManager` / `CinematicController` 的停止清理链

### 6.2 不干净状态下再次播放，敌对生物可能不出现
- **现象**：脚本停止后（卡住状态下）再次播放，偶尔不刷/不显示敌对生物；重进世界后正常
- **疑似**：
  - 假人/实体同步状态（`syncedEntityIds`、`CameraFakeConnection`、`CameraMobManager.anchors`）没有完全清理
  - 或玩家输入/相机状态未复位导致预加载/假人链路异常
- **探索方向**：
  - 检查 `ChunkPreloadManager.release()` / `CameraMobManager.removeAnchor()` 是否完整清理
  - 检查脚本结束事件是否一定触发释放
  - 检查 `syncedEntityIds` 是否在重建假人时被重置

---

## 本轮已处理

- 修复脚本结束后输入未恢复：`CinematicController.revert()` 的 `blockKeyboard/blockMouse` 改为 `false`
- 清理硬编码：
  - 删除 Biome lambda 方法名硬编码（保留 `tick()` 主采样重定向）
  - `PlayerListMixin` 改用 `instanceof CameraFakePlayer`
  - `releaseIfNeeded()` 魔法值改为常量
- 假人实体同步：
  - 假连接转发世界包 + 区块发送后补发实体 + 周期补发未同步实体
  - `syncedEntityIds` 去重，避免重复生成
- 跨平台 Mixin：
  - `SoundEngineMixin` 支持 Fabric/Forge 双目标
  - `PlayerListMixin` 修正外层参数签名

## 探索记录

（待逐条补充）
