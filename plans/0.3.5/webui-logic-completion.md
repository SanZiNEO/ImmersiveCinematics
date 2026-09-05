# WebUI 逻辑补齐 + 注册表自动补全方案

> 目标：把旧 Java 编辑器已有、WebUI 缺失的业务逻辑补上；为 0.3.5 提前发布 WebUI 编辑器做功能闭环。
> 最后更新：2026-09-01

## 一、结论：自动补全采用“即时查询 + 轻量缓存”

两种方案对比：

### 方案 A：全部注册表随数据包一次性发送
- 优点：前端离线可用、无查询延迟。
- 缺点：1.20.1 物品/方块/实体/群系/维度/结构/进度总量很大（物品+方块+实体约 4k+，GameStages 还不确定），全量 JSON 可能数百 KB 到数 MB；WebSocket 首包大、浪费。
- 结论：不做第一版默认方案，可作为后续“首次连接预取常用小表”的增强。

### 方案 B：即时按需查询 registry.query / registry.get
- 旧 Java 编辑器本身就是“打开对应触发器时从 MC 注册表读候选”的即时模式。
- WebUI 采用同思路：前端在输入/聚焦时发 `registry.query`，Java 端从 `RegistryAccess` / `BuiltInRegistries` 查候选，返回匹配列表。
- 小表（`biome`、`dimension`、`structure`、`advancement`、`gamestage`）后续可加 `registry.get` 一次性全量缓存。
- 优点：首包小、实现简单、跨 Forge/Fabric 走 Mojang 官方注册表 API，无平台差异。

## 二、Java 侧数据源（MC 1.20.1 官方映射）

已从旧 Java 编辑器代码确认可用 API：

| 数据类型 | 数据源 |
|---|---|
| item | `BuiltInRegistries.ITEM.keySet()` |
| block | `BuiltInRegistries.BLOCK.keySet()` |
| entity | `BuiltInRegistries.ENTITY_TYPE.keySet()` |
| sound | `BuiltInRegistries.SOUND_EVENT.keySet()` |
| target | `BuiltInRegistries.BLOCK.keySet()` + `BuiltInRegistries.ENTITY_TYPE.keySet()` |
| biome | `level.registryAccess().registry(Registries.BIOME)` |
| dimension | `level.registryAccess().registry(Registries.DIMENSION)` |
| structure | `level.registryAccess().registry(Registries.STRUCTURE)` |
| advancement | `Minecraft.getInstance().getConnection().getAdvancements()` + 反射读取内部 map |

Forge/Fabric 均使用 Mojang official mapping，`RegistryAccess` / `BuiltInRegistries` / `Registries` 是公共 API，不需要平台特判。
`advancement` 的反射读取延续旧编辑器做法，失败时回退空列表，不阻塞 WebUI。

## 三、协议设计

```text
C→S: { "type": "registry.query", "data": { "kind": "item", "query": "iron", "limit": 20 } }
S→C: { "type": "registry.query.result", "data": { "kind": "item", "matches": ["minecraft:iron_ingot", ...] } }

C→S: { "type": "registry.get", "data": { "kind": "biome" } }
S→C: { "type": "registry.get.result", "data": { "kind": "biome", "values": ["minecraft:plains", ...] } }

C→S: { "type": "script.validate", "data": { "doc": {...} } }
S→C: { "type": "script.validate.result", "data": { "issues": ["..."], "ok": false } }
```

## 四、工作拆解

1. Java 后端
   - `WebRegistryService`：统一注册表查询。
   - `WebEditorApi`：注册 `registry.query` / `registry.get` / `script.validate`。
   - `ScriptFileService` / `WebEditorApi`：保存后通知服务端重载（沿用旧编辑器 C2S 通知思路或触发本地 reload）。
2. 前端
   - 脚本生命周期：新建 bootstrap、打开自动对齐/选 CAMERA、保存校验展示。
   - 位置模式切换、marker / A-B 循环写回 doc 并推送。
   - 轨道锁定/静音真正生效；预览推送剔除静音 AUDIO。
   - 字符串字段注册表自动补全。
   - 时间轴右键菜单/快捷键补齐。
   - 飞控记录逻辑补齐。
3. 构建验证
   - `common/forge/fabric compileJava`
   - `editor npm run build`
