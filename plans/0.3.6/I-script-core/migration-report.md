# I-5 迁移报告（摘要）

> 生成：2026-11-06。原始逐脚本报告（124 KB Markdown + 210 KB JSON）在仓库外 `E:/tmp/i5/report.md`，不入库；本页只保留结论与需人工复核的清单。
> 迁移器：`tools/script-migrate`（Java CLI，2190 行 / 7 文件）。**一次性工具**，已在 I-5 收尾删除，源码见 git 历史（`chore(0.3.6)` 提交）。

## 一、规模与结果

| 项 | 数值 |
|---|---|
| 脚本总数 | 252（旧格式 242 / 已是新格式 10） |
| 迁移成功 / 错误 | 242 / **0** |
| 片段 | 875 |
| 对象（`objects` 条目） | 7 441 |
| 脚本级路径条目（旧 `curve` 逐段展开） | 18 |
| 规则定义 / 绑定 | 21 / 21 |
| 总体积 | 1.73 MB（无单脚本接近 4 MiB 上限） |
| 幂等 | 对迁移后目录重跑：252 支全部「已是新格式」，迁移 0、错误 0，两次输出逐文件一致 |
| 校验器结果 | 迁移前 **19 / 252** → 迁移后 **8 / 258**（8 = 3 支既有缺陷 + 5 支新增旧字段负例） |

> 258 = 252 迁移脚本 + 5 支旧字段负例 + 1 支独立片段夹具（后两者为本轮新增，均为新格式）。

## 二、稀疏字段补齐（13 处 / 4 个片段）

旧运行时每个全局关键帧是稀疏快照，缺失字段在相邻全局帧之间回落到默认值；新格式每个对象只在自己的关键帧上插值。迁移对缺失帧按 schema 默认值补齐：

| 脚本 | 片段 | 通道 | 缺失帧 |
|---|---|---|---|
| `0.3.6/release/end_return_036.json` | `c1_8` | `opacity` | 4 / 6 |
| `0.3.6/release/showcase_036.json` | `c2_2` | `tint` | 6 / 13 |
| `0.3.6/tests/camera/test_camera_point_source.json` | `c1_1` | `yaw_base_from*` / `yaw_base_to*` / `facing_target` / `facing_origin` | 共 11 处 |
| `mod_showcase/scripts/showcase_03_village.json` | `c1_1` | `facing_origin` | 1 / 2 |

其余 849 个片段的所有通道在每个全局关键帧都有值，无需补齐。

## 三、`total_duration` 与派生末端不一致（6 支）

迁移删除 `timeline.total_duration`，由片段窗口派生有限末端。以下脚本旧声明值与派生末端不同，已逐条记录（新运行时不使用旧声明值）：

| 脚本 | 旧 `total_duration` | 派生末端 |
|---|---|---|
| `0.3.6/tests/camera/test_camera_relative_axis.json` | 8 | 12 |
| `0.3.6/tests/rules/test_exit_branch_extend.json` | 8 | 14 |
| `0.3.6/tests/rules/test_exit_content.json` | 12 | 10 |
| `0.3.6/tests/rules/test_exit_loop_count.json` | 10 | 6 |
| `mod_showcase/scripts/showcase_01_welcome.json` | 31.5 | 31 |
| `mod_showcase/scripts/showcase_06_nether.json` | 12 | 11.5 |

## 四、未知路径（已丢弃，5 条）

schema 里不存在、旧运行时也不消费的旧 clip 级字段；迁移不写入 `objects`（否则校验器报未知路径），逐条记录：

| 脚本 | 片段 | 字段 | 值 |
|---|---|---|---|
| `0.3.6/release/end_return_036.json` | `c1_1` | `transition_easing` | `"ease_in_out"` |
| `0.3.6/release/showcase_036.json` | `c1_1` | `transition_easing` | `"ease_in_out"` |
| `0.3.6/release/showcase_036.json` | `c1_3` | `transition_easing` | `"ease_in_out"` |
| `0.3.6/tests/overlay/test_overlay_fade.json` | `c2_1` | `fade_in` | `1` |
| `0.3.6/tests/overlay/test_overlay_fade.json` | `c2_1` | `fade_out` | `1` |

`transition_easing` 全仓无 Java 消费点（死字段）；OVERLAY 的 `fade_in`/`fade_out` 属 AUDIO 专用路径。

## 五、人工复核项

| 类别 | 数量 | 处理 |
|---|---|---|
| 片段无 `id` → 分配 `c<轨道序>_<片段序>` | 853 | 机械分配，脚本内唯一；被规则/路径/出口引用者已写回 |
| `follow=entity` → 动态原点（`position_origin` + `freeze=false`） | 10 | 保语义；动态原点无法静态等值，运行期按实时实体位置求值 |
| 旧 `until` 规则 → `control=hold` | 5 | 语义近似（旧=挂起播放时钟；新=到门未成立即持有实例），单列报告 |
| `yaw_offset` / `pitch_offset` 在 `orient=manual` 下未写入 | 4 + 4 | 旧运行时同样不读（`requires=orient=tangent` 不满足），丢弃并记录 |
| 同一时刻多条事件记录 → 按 `1e-4` 秒错开 | 1（2 条） | `test_camera_center_crossdim_clip.json`；同一帧内触发，行为等价 |
| 关键帧 `color` 为 hex 字符串 → 解析为 `color.r/g/b` | 5 帧 | `test_color_mask.json`；`#RRGGBB / 255`，与旧 `parseKeyframeColor` 一致 |
| 关键帧时间非严格递增 → 排序 | 1 | 同上 crossdim 夹具的既有数据缺陷 |

## 六、明确语义变更（不宣称逐位等值）

1. `meta.version` / `timeline.total_duration` 删除（不再有格式版本号与声明总时长）。
2. AUDIO 文件循环 `loop` → `objects.file_loop`；新格式 `loop` 只作执行循环。
3. `look_at_offset` 等偏移对象：旧 `dx/dy/dz`（或 `fwd/up/right`）→ 新 `x/y/z` + `space`。
4. `follow` / `follow_selector` / `facing_origin*` / `selector_*_follow` / `selector_*_facing_origin` → `position_origin` / `position_origin_freeze` / `selector_*_position_origin`。
5. 旧 clip 级 `curve`（一份控制点隐式套到每个 from→to 段）→ 脚本级 `paths[]`（每个旧段一条，各自持有 geometry）。
6. 旧 `clip.rules`（随片段活跃窗生效）→ 脚本级 `rules[]` + `rule_bindings[]`（`gate.at = 0`，`until` → `hold`）。
7. 旧 `hud_layers` / `hard_hide_hud` → `screen_layers`（极性翻转）/ `screen_layer_mode`（`true`→`allowlist`、`false`→`normal`）。
8. `attenuation=inverse` → `linear`（该枚举值已删除，见 `track-audio-adjust.md` §4.4）。

## 七、复核结论

- 252 支全部可被新 parser/validator 加载；无结构错误、无非有限值、无重复时间（已排序/错开的两处见上表）。
- 迁移前后校验器差异全部可解释：消失的 11 支是「旧字段告警 / `meta.version=4` / clip 级废弃字段」随格式删除而消失；新增 5 支是本轮故意加的旧字段负例。
- 剩余 3 支既有缺陷与迁移无关，已登记到 `I-BACKLOG.md` 的 I-5 表。

---

## 八、I-5 裁决摘要

| 编号 | 事项 | 裁决 |
|---|---|---|
| D1 | 旧格式（clip 级 `keyframes`） | 整体删除：parser/validator 不再识别，出现即「已移除」；`Clip.keyframes` / `Keyframe` / `KeyframeInterpolator` 与 10 处旧消费分支一并删除 |
| D2 | 派生范围 | `CinematicScript` 持有只读 `infinite` / `contentEnd` / `finiteDisplayEnd`，加载期派生；不用 ∞/NaN/MAX 哨兵；`Clip.getWindowEnd()` 无限抛异常，调用方先判 `hasFiniteWindowEnd()` |
| D3 | `goto_clip` | 只接受 `{clip, entry_time}`；范围按目标片段有效范围 + 可达/覆盖，不用总时长 |
| D4 | AUDIO `file_loop` | 旧 `loop`（文件循环）→ `objects.file_loop`；新格式 `loop` 只作执行循环 |
| D5 | `hud_layers` / `hard_hide_hud` | 删除解析与校验入口，出现即「已移除」；`ScreenLayer` 表是唯一回落真源 |
| D6 | `attenuation=inverse` | 选 ② 删除枚举值（`SoundInstance.Attenuation` 只有 NONE/LINEAR；自建逐帧增益无可验证验收）；迁移写 `linear` |
| D7 | `gradient.stops` | 选 ① 保持 value-only 并定性关闭 |
| D8 | OVERLAY 同轨多层 / 运行中 `z_index` | 选 ② 明确不支持同轨重叠、`z_index` 仅加入时排序 |
| D9 | 宏观循环 L6 | 选 ② 语义不变、`loop_start`/`loop_end` 仅区间定位 |
| D10 | `ExitReason.USER_SKIP` | 删除枚举值与 `PlaybackLifecycle` 分支；`CompletionReason.SKIPPED` 保留（外部 API / 历史回执识别） |
| D11 | 脚本级独立片段渲染 | `ScriptPlayer` 隐式轨道表：按 `track` 类型分组，索引接在 timeline 之后 |
| D12 | `spi` 机械迁移 | 11 类收进 `spi`，共 19 类；旧 FQN 零残留 |
| D13 | 文档 | 按新格式重写；`docs/SCRIPT_FORMAT.md` 2125 → 741 行 |
| D14 | 迁移器去留 | 一次性工具：先提交（`e55caef`），收尾删除（`e0f8afd`）；摘要留本文件 |

## 九、实机验收记录（2026-11-06）

**装置**：`icdev` rig — Fabric dev 专用服 + 1 客户端，世界「新的世界」。
服务端脚本目录 = `E:/tmp/icdev/server/immersive_cinematics/scripts`（**不是** `fabric/run/...`；rig 的服端 run dir 在仓库外）。

| 项 | 结果 | 证据 |
|---|---|---|
| 独立片段分支（I4-R6） | **通过** | `tests/rules/test_standalone_camera_branch.json`：规则 `time@2s` + `goto_clip` 启动 `clips[]` 里的独立 CAMERA 片段。日志 `pass=test_standalone_camera_branch_cam0_clip0#0 cam=(0.000,200.000,0.000)` 证明**隐式轨道 cam0** 产出了该片段的相机 pass；判别帧（`position.y=200` / `pitch=-80`）画面变为夜空俯视 + 右下「长按 C 跳过动画」→ 相机**实际上屏** |
| 新格式脚本推送/自然结束 | **通过** | `已向 1 名玩家推送脚本`、`result=1`、`脚本播放停止 … (原因: FINISHED)` |
| 旧格式拒绝（服端残留脚本） | **通过** | `Failed to load script … meta.version: 已移除…` |
| Forge 编译 | **通过** | `:forge:compileJava` BUILD SUCCESSFUL |
| `test_exit_loop_count`（有限 3 圈，跨度 6s） | **通过** | 圈数耗尽自然结束；相机租约 `lease0g1` 取/放各一次 |
| `test_exit_infinite`（无限） | **通过** | 12s 内无 `FINISHED`；手动 `stop` 收尾 |
| `test_camera_loop_repeat`（无限，跨度 12s） | **通过** | 15s 内 80 个 lane pass；无 `FINISHED`；租约只取一次 |
| `test_camera_loop_pingpong`（无限） | **通过** | 15s 内 101 个 lane pass；无 `FINISHED`；0 异常 |
| `test_objects_multitrack`（6 轨） | **通过** | `FINISHED`；租约到 `lease0g5`（片段切换 5 代）；相机 `(12.00, 64.0, -8.00)` |

全局异常扫描：仅 4 行 ERROR，全部是 dev 客户端 `Failed to verify authentication`（401），**无轨道/规则/相机相关异常**。

**验收口径**：功能矩阵 + 实机人工观感。沙盒环境存在生物移动、光影变化与浮点精度差异，不适用逐像素对比。
