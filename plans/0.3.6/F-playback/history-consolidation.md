# 光影调查分支历史压缩（2026-10-18）

分支：`investigate/036-shader-lane`
目的：把调查过程中的反复提交压成“最终状态”，不把探针与一次性验收脚本带进历史。

## 压缩前

共 9 个提交，45 个文件，+2346 / −159：

| 提交 | 内容 |
|---|---|
| `87c719b` | 光影阶段探针基线（C0/C1/C2 抓图、软 mixin） |
| `d26279c` | 阴影状态只读日志、一键自动测试入口 |
| `4359527` | 每视角状态审计探针 |
| `bb980d6` | 跨视角污染修复（shadowcolor + colortex 历史隔离） |
| `724accc` | 自动探测历史目标，移除 Iris 行为 mixin |
| `9e300ce` | 切换光影包时重置每视角历史 / previous |
| `5358818` | D 轮文档状态回写 |
| `33743ef` | F3 验收关单 |
| `7c77e43` | F5 大脚本传输上限收尾 |

主要反复修改的文件：`LaneRenderer.java`、`immersive_cinematics.mixins.json`（各 5 次），`IrisFinalizeProbeMixin` / `IrisCompositeProbeMixin`（各 4 次，最终删除），`LevelRendererMixin`、`ViewStateCapture`、`ColorHistoryCapture`、`LaneLeakProbe`、`CHANGELOG.md`（各 3 次）。净零文件：`LaneFullChainProbe`（+821/−821）、`ShadowStateLog`（+123/−123）、`ViewStateLog`（+289/−308）、7 个 `Iris*ProbeMixin`。

## 压缩后

按最终状态重写为 2 个提交：

1. `fix(0.3.6): 光影 lane 跨视角污染修复`
   - `ColorHistoryCapture` / `ShaderCompat` / `ShadowColorClear` / `ViewScope` / `ViewStateCapture`
   - `LaneRenderer` / `ScriptLaneDriver`
   - `IrisCameraTrackerCaptureMixin` / `IrisPreviousCaptureMixin` / `LevelRendererMixin`
   - 三个 `build.gradle` 的 `net/irisshaders` 排除、两个 MixinPlugin 的 Iris 门控
   - `CHANGELOG.md`
2. `docs(0.3.6): F3 验收与 F5 收尾文档`
   - D 轮状态回写、F3 验收、F5 大脚本传输上限、本文件

## 从最终树移除

- `LaneLeakProbe.java`（337 行调查探针）及 `LaneRenderer` / `ScriptLaneDriver` 中的调用
- `run-shadow-test.sh`（含硬编码 `D:/mc/...` 路径）
- `run-scene-test.sh`
- `tools/auto_run_ab.json`、`tools/auto_run_scene.json`、`tools/test_shadow_scene.json`

这些文件已复制到 `fabric/run/dev-tools/`（运行目录，git 忽略），需要复跑验收时仍可用。`tools/auto_run_f5*.json` 同样只留在本地，未提交。

## 历史丢弃

`87c719b` / `d26279c` / `4359527` 三个探针提交不再保留；其净零文件（`LaneFullChainProbe`、`ShadowStateLog`、`ViewStateLog`、`Iris*ProbeMixin`）直接消失。修复演进（首版 → 自动探测 → 切包重置）合并进单个修复提交。

## 备份与验证

- 压缩前备份标签：`backup/036-shader-lane-pre-squash`
- 最终树应与备份一致，除上述移除文件与本文件；可用
  `git diff backup/036-shader-lane-pre-squash HEAD --stat` 核对。
- `sh gradlew compileJava --offline` 三模块通过（仅既有警告）。
- F5 l25 已于 2026-10-18 完成 600/600（FULL / WORLD_ONLY，gzip 脚本传输修复后）；`failure-handling-audit.md` 仍未实施——本次只整理历史，不改这些状态。
