# H6 实机验证记录（审计 §四 7 条）

> 日期：2026-11-03（H-2）。环境：Windows 11，Fabric 1.20.1 dev 客户端 / Forge 47.4.16 dev 客户端。
> 方法：`tools/devtools` 驱动（Win32 定向消息，不抢焦点）；截图一律走**游戏内 F2**（`ic shot` 已改为取游戏帧缓冲，不再抓屏幕区域）。
> 数字均为像素统计（PIL 采样），不是肉眼判断。

---

## 一、逐条结果

| # | 审计条目 | 结果 | 证据 |
|---|---|---|---|
| 1 | hud-hard-hide：注册表 overlay 的 Pre 拦截 | **已跑（部分）** | 见 §二 |
| 2 | 叠化 / 黑场 / 白场 / 合成参数上屏 | **已跑** | 见 §三 |
| 3 | screen-color-adjust 上屏 + 光影先后顺序 + 性能 | **部分跑** | 见 §四 |
| 4 | 脚本 i18n 多语言切换 | **已跑** | 见 §五 |
| 5 | lane 缺陷症状 B 像素实证 | **未复现** | 间歇问题，本轮未采到现场；机制级证据与用户实机确认沿用前轮结论 |
| 6 | 编辑器 UI 交互层 | **无环境** | 编辑器源码不在本仓（`git ls-files editor` = 0，仓内只有 `dist/`、`node_modules/`、`release/`） |
| 7 | template 命令 + release 脚本游戏内实跑 | **已跑** | 见 §六 |

---

## 二、hud-hard-hide（#1）

夹具：`cinematics/0.3.6/tests/hud/test_h11_hard_hide.json`（`hide_hud=true`、`hard_hide_hud=true`、`hide_chat=true`、`hud_layers:{"minecraft:chat_panel": false}`）。

| 检查 | 观测 | 结论 |
|---|---|---|
| hard 接管生效 | 顶部本模组字幕亮像素 382；底部快捷栏 0；左下聊天 0 | 原版 HUD 被接管后不重画，本模组覆盖层照常补画 |
| 固定分类优先级 | `hide_chat=true` 且 `hud_layers` 写了 `minecraft:chat_panel=false` 时聊天仍为 0 | 固定分类只读 `hide_chat`，`hud_layers` 不覆盖（H11 文档口径一致） |

**注册链路验证**（用临时测试 overlay `icinematics_test:red_square`，画 160×160 纯红方块；验证后代码已删除）：

| 用例 | 红块像素 | 结论 |
|---|---|---|
| 对照：normal 模式、未注册 | 11454，中心 `(255,0,0)` | overlay 正常绘制 |
| hard + **未注册** | 5 | 被砍且不重画（验收 3） |
| hard + `HudLayerRegistry.register(id, "chat")` + `hide_chat=false` | 11542，中心 `(255,0,0)` | 注册后按分类重画（验收 1 / 4） |
| hard + 同上注册 + `hide_chat=true` | 5 | 注册不改变分类判定，隐藏分类仍被砍 |

**边界**：审计条目点名的外部模组（TACZ / SecurityCraft）未安装，用「未注册的原版/临时 overlay」覆盖了同一条判定路径（`categoryOf` → `isLayerHidden` → 取消或重画）；真实第三方 jar 的复测归后续实机批次。

---

## 三、转场与合成（#2）

夹具：`cinematics/0.3.6/tests/transition/*.json`（Fabric 客户端）。

| 夹具 | 采样 | 观测 | 结论 |
|---|---|---|---|
| `test_fade_black` | t≈3s（全遮段） | 中心 `(0,0,0)`；全图均值 `[0.2, 0.3, 0.3]` | 黑场完全遮挡 |
| `test_fade_white` | t≈3s（全遮段） | 中心 `(255,255,255)`；均值 `[254.6, 254.7, 254.7]` | 白场完全遮挡 |
| `test_dissolve` | t≈5s（重叠窗口） | 中心 `(203,196,188)`；均值 `[166.4, 165.3, 94.5]` | 两层混合，非纯 A/纯 B |
| `release/quadrant` | t≈4s | 四象限中心分别为 `(16,30,26)` `(63,32,53)` `(50,54,29)` `(112,102,92)`；日志显示 4 个 lane lease 同时活跃 | 四机位分屏合成生效 |

---

## 四、screen-color-adjust（#3）

| 夹具 | 观测 | 结论 |
|---|---|---|
| `test_adjust_lut_demo` | 全图均值 `[129.4, 148.9, 134.9]`，中心 `(105,79,120)` | LUT 调色上屏 |
| `test_adjust_hsl` | 全图均值 `[130.9, 149.0, 136.0]`，中心 `(105,79,121)` | HSL 调色上屏 |

**未做**：与 Iris 的先后顺序（需要启光影重跑 A/B 并比对帧）、性能定量（需要诊断会话统计）。`fabric/run/shaderpacks/` 有 4 个光影包，环境具备，归后续实机批次。

---

## 五、脚本 i18n（#4）

临时夹具（不入库）：`test_h6_i18n`，两条字幕分别在独立 OVERLAY 轨：
- `@lang:test_h6.greeting`（有译文）
- `@lang:test_h6.missing_key`（无译文，验证回退）

字典：`fabric/run/immersive_cinematics/resource/lang/{zh_cn,en_us}.json`（临时写入，测后已删）。

| 语言 | 译文行亮像素 / 横向范围 | 缺 key 行亮像素 / 横向范围 |
|---|---|---|
| `zh_cn` | 4305 px，x ∈ [630, 1908] | 221 px，x ∈ [1200, 1596] |
| `en_us` | 4348 px，x ∈ [716, 1906] | 221 px，x ∈ [1200, 1596] |

结论：切换语言后同一 key 渲染出不同文本（宽度与像素分布均变化）→ 真实客户端多语言生效；缺 key 两种语言完全一致 → 回退到原样引用串，与 `LangResources` 约定一致。

---

## 六、template 与 release 脚本（#7）

| 命令 / 夹具 | 观测 |
|---|---|
| `/icinematics template list` | 聊天栏列出 4 个模板：`fade`、`static_breath`、`dolly`、`orbit_arc` |
| `/icinematics template fade` | 生成 `scripts/generated/fade.json`（OVERLAY 轨），回执「校验通过」 |
| `/icinematics template orbit_arc` | 生成 `scripts/generated/orbit_arc.json`（CAMERA 轨），回执「校验通过」 |
| `/icinematics template camera_orbit` | 报「未知模板」，并列出可用模板（参数校验生效） |
| `/icinematics validate test_fade_black` | 回执「校验通过: test_fade_black.json」 |
| `/icinematics play release/quadrant` | 四机位同时活跃（4 个 lease），四象限颜色互不相同，正常自然结束 |

**说明**：`release/` 下的脚本带登录触发器，会连续自动播放；实机时临时移出运行目录以免干扰单条验证，事后已还原。

---

## 七、H9 跳过 HUD 的布局实测（非审计条目，随本轮一并验证）

| 配置 | 观测 | 结论 |
|---|---|---|
| 缺省 `skipHud(95,90)` | 环形元素中心实测 `y = 90.10%` | 纵坐标按元素中心换算正确 |
| `skipHud(50,50)` | 整块中心实测 `(51.52%, 50.07%)` | 中心语义成立（x 偏差来自环形图标位于块右端，扫描窗把环计入） |
| `skipHud(0,0)` | 可见部分从 `(0,0)` 起、到 `(87,15)` 止；客户端存活 | 不做越界保护，半出屏不崩 |

**未验证**：票数行在单机未发布下按 MC 口径不显示，因此其像素位置未在实机单独测量（需要第二名玩家产生票数包才会出现）。它与长按块共用同一套「元素中心 → 像素」换算，已由上面三行覆盖；门控分支见 `skip-vote-and-hud.md` §六。

---

## 八、环境备注（给后续实机的人）

- `ic shot` 已改为游戏内 F2 截图：产物只含游戏画面，不受窗口遮挡影响，也不会把前台其他应用的内容带进截图。
- `forge/run/options.txt` 的 `pauseOnLostFocus` 原来是 `true`，失焦即暂停并弹菜单，会抢走焦点；已改为 `false`（运行目录，不入库）。
- Forge 的 dev 客户端任务名是 `:forge:Client`（`forge/build.gradle` 里 `taskName 'Client'`），不是 `runClient`；`devtools.py client` 目前只走 `:fabric:runClient`。
- devtools 用 `IC_RUN_DIR` 切换运行目录：Forge 实机要设 `IC_RUN_DIR=forge/run`，否则截图/日志/脚本都落在 `fabric/run`。
