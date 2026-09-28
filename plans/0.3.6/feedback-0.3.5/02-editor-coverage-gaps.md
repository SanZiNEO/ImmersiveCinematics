# 02 游戏内编辑器 vs 脚本格式：能力覆盖缺口

- **来源**：代码排查（2026-09-28），非用户实测
- **状态**：待处理
- **结论**：游戏内编辑器**不能表达脚本格式的全部能力**。缺口集中在"手写 UI"的部分（触发器条件编辑器、时间轴菜单）；属性面板（脚本/Clip/关键帧）是 schema 驱动（`SchemaLoader` / `FieldControl` / `SchemaExporter`），覆盖度 = schema 覆盖度，个别 schema 字段没进编辑器分组。

## 一、触发器（`editor/panel/TriggerPanel.java` + `editor/trigger/*`）

| 能力（格式/运行时支持） | 编辑器现状 |
|---|---|
| `location` 区域模式（`corner1`/`corner2`，A→B 方体） | **不可用**（见 01：无标签 + 原始 key + 切换不重建） |
| `requires` 前置依赖（脚本解锁条件） | **完全没有 UI**（面板只编辑 id/repeatable/on_enter/exit_buffer/delay + conditions） |
| `dimension_change` 的 `from_dimension`（来源维度过滤） | 无法设置（`SingleIdEditor` 只有一个字段） |
| `entity_kill` 场景条件（dimension / biome / position+radius / corner1+corner2） | 无法设置（`EntityKillEditor` 只有 entity + or/and） |

- 触发类型清单本身是全的：`TriggerPanel.TYPE_LIST` 23 种 = `TriggerRegistry` 注册 23 种。
- 其余各类型条件字段（structure / observation / xp / inventory / item_on_interact …）编辑器均有对应控件。

## 二、脚本 meta（`ScriptPropertiesPanel.GROUPS`）

| 字段 | 现状 |
|---|---|
| `listener`（音频听者 player/camera） | schema（`MetaSchemas`）里有，但不在任何分组 → 游戏内编辑器改不了 |

## 三、时间轴（`EditorScreen.setOnTrackAdd`）

| 能力 | 现状 |
|---|---|
| 新增轨道 | 菜单只有 CAMERA / AUDIO / EVENT / MOD_EVENT / OVERLAY，**没有 LETTERBOX**（删掉默认 letterbox 轨道后无法再加） |

## 四、预设（`PresetRegistry`）

- 内置预设只有 1 个（环绕轨道 `OrbitCirclePreset`）——能力有、内容少。

## 五、交叉参考（已在 `docs/modules/editor.md` 记录的已知问题）

- 保存校验不阻断（`EditorScreen.saveScript()` 校验报错仍写盘）；
- 新建关键帧不按时间插值（`copyKeyframeProperties` 复制相邻帧）；
- `RawInputLogger` 输入采集未接线；
- 残留 `[KILO-DEBUG]` 控制台输出。

## 六、顺带：文档与代码不同步（非编辑器问题）

- 脚本层 `preload` / `camera_mob_spawn` / `camera_mob_radius` / `camera_mob_ai` 开关已从代码移除（近期提交「删除脚本层开关」；config 注释：预加载"服务端强制；脚本层没有开关"），但 `docs/SCRIPT_FORMAT.md`、`docs/AI_SCRIPTING_GUIDE.md`、`docs/modules/editor.md`、`docs/modules/script.md` 仍写着它们 → 需同步。
