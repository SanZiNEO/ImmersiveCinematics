# camera_pool 测试说明（F7 相机租约池）

## 目标

验证「一个 CAMERA 片段 = 一个租约」：

- 同一轨相邻片段得到不同 `viewKey`，片段 B 不继承片段 A 的视角 / 颜色历史；
- 停止后重播，活跃租约回到 0，下一次播放从新代际开始；
- 租约键变化不影响合成顺序（顺序仍由本帧 lane 注册顺序决定）。

## Fixture

`test_lease_replay.json`：同一条 CAMERA 轨两个 5s 片段（0–5 位置 A，5–10 位置 B），无自动触发器。运行器会把 fixture 复制到 runtime 的 `fqueue/` 并剥掉触发器，由登录包装显式播放。

## 运行（单客户端，禁止专用服务端）

在仓库根目录：

```bash
# 相邻片段 + 停止后重播（0.5s 播放，8s 停止，10s 再播放，17.5s 再停止）
REPLAY=1 DRY_RUN=0 bash fabric/run/dev-tools/run-f-round-test.sh \
  cinematics/0.3.6/tests/camera_pool/test_lease_replay.json "新的世界"
```

- 默认关闭光影（退出时恢复 `iris.properties`），开启 `debugLogging`；
- 只启动一个 `:fabric:runClient`（单人 integrated server），不另开服务端、不跑第二个客户端；
- 客户端关闭后脚本自动提取日志到 `fabric/run/dev-tools/f-round-*.log`；
- 运行期间会把 runtime 的旧自动触发脚本移到 `scripts_disabled_f_round/`，退出时恢复；生成的登录包装与 fixture 默认删除（`KEEP=1` 可保留检查）。

## 预期日志

`fabric/run/logs/latest.log` / 报告文件里：

```text
[camera-lease] acquire script=test_lease_replay clipStart=0.0 slot=0 gen=1 active=1 view=lease0g1
[camera-lease] acquire script=test_lease_replay clipStart=5.0 slot=1 gen=1 active=2 view=lease1g1
[camera-lease] release script=test_lease_replay clipStart=0.0 slot=0 gen=1 active=1 view=lease0g1
...
[camera-lease] releaseOwner script=test_lease_replay active=0
...
[camera-lease] acquire script=test_lease_replay clipStart=0.0 slot=0 gen=2 active=1 view=lease0g2
```

验收点：

1. 片段 A / B 的 `view=` 不同（槽位或代际不同）；
2. 停止后 `releaseOwner ... active=0`，`[lane] 活跃 lane:` 为空或不再出现；
3. 重播首条 acquire 的 `gen` 大于第一次同槽位的 `gen`（旧视角键不会命中新租约）；
4. 不出现 `[camera-lease] releaseAll 后仍有 ... 条租约`；
5. 不出现 `ERROR` / `Exception` / `route-fail` / `oversize`。

> 实机结论以本次运行日志为准；本文件只定义 fixture 与观察口径。
