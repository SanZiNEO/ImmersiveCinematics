# Schema Java 元数据化（Schema → Java Metadata）

**版本**: 0.3.5
**类型**: 重构 / 架构
**状态**: 📋 方案待细化
**关联**: `editor-modularization.md`（编辑器拆分）、`script-folder-organization.md`、所有新增字段计划

---

## 一句话定义

`schema.json` 原本是“编辑器 ↔ 播放器不互通时代”的中间契约；现在两者已直接通信，它的通信意义消失，但仍作为字段元数据单源被编辑器/解析器/默认值共用。本次把这份元数据从 JSON 字符串迁移为 **Java 注册表/定义**，让字段类型、默认值、枚举、必填等只有一个编译期可检查的来源。

## 背景

- 早期编辑器和播放器不互通，改代码容易出错，所以用 `schema.json` 当“中间样板”。
- 现在编辑器通过 `CameraManager.pushScript(JSON)` 与播放模块直接通信，schema 不再是跨模块契约。
- 但 schema 仍被三处依赖：
  1. `LeftPanelArea` 属性面板反射（显示哪些字段、类型、枚举、默认值）
  2. `ScriptParser` 解析 JSON 字段类型
  3. `EditorDefaults` 填默认值
- 问题：JSON 字符串类型系统没有编译期检查；加一个字段要同步改多处；复杂字段（position、bezier_curve、map、未来的 `look_at_target`）描述能力弱，最后还是要写 Java 特例。

## 方案

### 目标形态

在 common 中建立 Java 侧字段元数据注册表，例如：

```
script/
├── schema/
│   ├── FieldDef.java            # record：type/default/required/enum/section
│   ├── TrackSchemas.java        # 各轨道 clip/keyframe 字段定义
│   ├── MetaSchemas.java         # meta 字段定义
│   └── SchemaRegistry.java      # 统一入口（替代 SchemaLoader 读 JSON）
```

- `SchemaLoader` 对外接口尽量保持 `FieldDef`/`getClipFields` 等不变，内部改为读 Java 注册表。
- 编辑器反射、`ScriptParser`、`EditorDefaults` 继续使用同一套 `FieldDef`，但来源变成 Java。
- 脚本 JSON 格式不变；文件保存、S2C 网络包仍用 JSON。

### 复杂字段如何表达

- `position`、`bezier_curve`、`map` 等目前由 `ScriptParser` 特判的字段，在 Java 定义中仍以 `type="position"` 等标记，解析逻辑保留在 `ScriptParser`。
- 未来 `look_at_target` 这类对象字段可在 Java 定义中声明为 `map`/`object`，编辑器再按 Java 定义渲染条件表单。

## 迁移范围

| 来源 | 迁移到 |
|---|---|
| `schema.json` 的 `track_types` | `TrackSchemas.java` |
| `schema.json` 的 `meta` | `MetaSchemas.java` |
| `SchemaLoader`（读 JSON） | `SchemaRegistry`（读 Java） |
| `ScriptParser` 的 schema 驱动解析 | 保持，读 `SchemaRegistry` |
| `EditorDefaults` | 保持，读 `SchemaRegistry` |
| `LeftPanelArea` 字段反射 | 保持，读 `SchemaRegistry` |
| `ScriptValidator` 的硬编码枚举/字段检查 | 逐步改用 Java 定义（可选） |

## 实施顺序

1. 在 `script/schema` 包建立 `FieldDef` / `TrackSchemas` / `MetaSchemas` / `SchemaRegistry`。
2. 把当前 `schema.json` 中所有字段定义迁移到 Java（meta + CAMERA/LETTERBOX/AUDIO/EVENT/MOD_EVENT/OVERLAY）。
3. 改造 `SchemaLoader`：内部从 Java 注册表构建，外部接口不变。
4. 跑通编辑器属性面板、`ScriptParser`、`EditorDefaults` 回归。
5. 删除 `schema.json` 或保留为文档/测试参照。
6. 后续新增字段（`cam_breath_type`、`yaw_base`、`look_at_target`、`category`、EVENT `position` 等）直接在 Java 定义中添加。

## 边界与待定

1. `FieldDef` 是否需要支持“对象/嵌套字段”描述，还是复杂字段仍由 `ScriptParser`/编辑器特判。
2. 是否同时让 `ScriptValidator` 完全改为读 Java 元数据。
3. 是否保留 `schema.json` 作为“文档化示例”放在 resources 中。

## 验收

- 删除/停用 `schema.json` 后编辑器、解析器、默认值功能不变。
- 新增一个字段只需改一个 Java 文件（外加必要的解析/编辑器特判）。
- 编译期能发现字段名/枚举拼写错误。

## 参考文件

- `common/src/main/resources/schema.json`
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/script/SchemaLoader.java`
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptParser.java`
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorDefaults.java`
- `common/src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/LeftPanelArea.java`
