# devtools：常用测试工具包

一个 Python 入口做全部常用操作：**控制游戏 / 启动客户端 / 取证 / 看日志 / 验诊断**。
**本目录入库**，是项目开发工具。

依赖由 uv 管理；首次使用先在本目录执行 `uv sync` 生成 `.venv`（含 `pyautogui` + `pillow`，`.venv/` 不入库）。

---

## 0. 怎么调用

```bash
# 最快（直接走本目录的 venv，无 uv 启动开销）
tools/devtools/.venv/Scripts/python.exe tools/devtools/devtools.py <子命令>

# 等价的 uv 写法（自动复用 .venv）
uv run --directory tools/devtools python devtools.py <子命令>

# Git Bash 里可以加个别名
alias ic='E:/Documents/GitHub/ImmersiveCinematics/tools/devtools/.venv/Scripts/python.exe E:/Documents/GitHub/ImmersiveCinematics/tools/devtools/devtools.py'
```

下文用 `ic` 代指上面第一种写法。

---

## 1. 子命令总表

### 控制游戏（客户端需在运行；Win32 定向消息，不抢焦点）

| 命令 | 作用 |
|---|---|
| `ic list` | 列出 Minecraft 窗口（pid / hwnd / 尺寸 / 标题） |
| `ic cmd "<文本>"` | 聊天栏执行命令（自动补前导 `/`） |
| `ic say "<文本>"` | 聊天栏发消息（按 `t` 打开，不加 `/`） |
| `ic key <键名>` | 按键：`f1..f12` / `esc` / `enter` / `tab` / `space` / 单字符 |
| `ic play <脚本 id>` | = `cmd icinematics play <id>` |
| `ic stop` / `ic reload` | = `cmd icinematics stop` / `reload` |
| `ic shot [路径]` | 截游戏窗口（相对路径按 `fabric/run/` 解析；默认 `lane-captures/mc-<时间>.png`） |
| `ic wait <秒>` | 等待 |
| `ic run <场景文件>` | 逐行执行场景脚本 |

### 观察

| 命令 | 作用 |
|---|---|
| `ic tail [行数]` | 打印 `latest.log` 尾部（**自动按 GBK 解码**） |
| `ic perf [会话目录]` | 打印诊断会话的性能摘要（valid / lane rows / p50 / p95） |
| `ic sessions [数量]` | 列出最近的诊断会话（带 summary 数量） |
| `ic doctor` | 环境自检：窗口 / 日志新鲜度 / 最新会话 / debugLogging / 光影包 / vsync+maxFps |

### 启动与取证

| 命令 | 作用 |
|---|---|
| `ic client [世界名] [--offline]` | 启动 Fabric 客户端并直接进世界 |
| `ic shadow [光影包] [lane] [世界名] [--capture]` | 一键 A/B 取证：写 `iris.properties` → 复制登录触发器 → 进世界自动播 |
| `ic scene [光影包] [lane]` | 受控场景取证（白地板 + 四色墙，A 背对墙 / B 正对墙） |

### 校验

| 命令 | 作用 |
|---|---|
| `ic analyze [会话目录]` | 校验诊断会话结构与数据完整性；结构错误退出 1，`INSUFFICIENT` 只告警 |

---

## 2. 为什么不用普通按键模拟

`devtools.py` 用 Win32 `PostMessageW` 把 `WM_KEYDOWN` / `WM_KEYUP` / `WM_CHAR`
**直接投递到目标窗口**：

- 不依赖窗口焦点——可以一边在编辑器里干活一边驱动客户端；
- 找到多个 `Minecraft` 窗口时要求显式 `--pid`，不会误操作别的客户端；
- 发命令前先 `End` + 320 次 `Backspace` 清空输入框，不会和上次残留拼在一起；
- 扫描码用 `MapVirtualKeyW` 现算，不硬编码键表。

---

## 3. 场景文件格式

`ic run <文件>` 逐行执行，`#` 注释、空行忽略：

```text
# 跑一次性能采样并取图
wait 2
play fround:ic_f_perf_l25
wait 4
shot lane-captures/l25-before.png
key f2
wait 60
stop
```

可用行：`wait <秒>` / `cmd <命令>` / `say <消息>` / `key <键>` / `play <id>` /
`stop` / `reload` / `shot [路径]`。示例见 `scenarios/smoke.txt`。

---

## 4. 典型工作流

### 跑一次 F5 性能采样

```bash
# 1. 确认诊断清单里有目标 case
cat fabric/run/immersive_cinematics/diagnostics/f-round.json

# 2. 起客户端（登录触发会按 auto_run_ab.json 自动播放）
ic client "新的世界"

# 3. 另开一个终端等两次 PERF COMPLETE
ic tail 200 | grep "PERF COMPLETE"

# 4. 看结果
ic perf
ic analyze   # 默认校验最新会话
```

### 客户端已在运行时手动驱动

```bash
ic cmd "icinematics play fround:ic_f_perf_l16"
ic shot
ic tail 30
```

### 光影 A/B 取证

```bash
ic shadow ComplementaryUnbound_r5.9.3.zip cam0 --capture
# 出图在 fabric/run/lane-captures/
```

---

## 5. 注意事项

1. **日志是 GBK**：`latest.log` 里中文按 GBK 写盘，直接 `grep 中文` 永远不命中。
   用 `ic tail`，或转码后再搜：
   ```bash
   powershell -NoProfile -Command "$fs=[IO.File]::Open('fabric/run/logs/latest.log','Open','Read','ReadWrite'); \
     $sr=New-Object IO.StreamReader($fs,[Text.Encoding]::GetEncoding(936)); $t=$sr.ReadToEnd(); $sr.Close(); $fs.Close(); \
     [IO.File]::WriteAllText('E:\tmp\log-utf8.txt',$t,(New-Object Text.UTF8Encoding($false)))"
   ```
   文件被客户端占用时必须用 `[IO.File]::Open(...,'Read','ReadWrite')` 共享读。
2. **运行时开关只剩 `ICINEMATICS_CAPTURE`**（或 `-Dicinematics.capture`）。
   `ICINEMATICS_CAPTURE_LANE` / `ICINEMATICS_PROBE_SCRIPT` / `ICINEMATICS_SHADOW_LOG`
   在 F 轮探针清理时已删除，导出也不会有人读。
3. **`debugLogging`**（`fabric/run/config/immersive_cinematics.json`）控制
   `[view-capture]` / `[preload *]` 等详细日志；性能采样时应保持 `false`。
4. **不要直接执行 `devtools.py`**（没有 shebang 依赖）：始终用 venv 里的 python 调用，
   或用 `uv run --directory`。
5. **`fabric/run/dev-tools/` 是历史目录**：里面留着旧版 shell 脚本与触发器 JSON，
   相对路径有误且导出了已失效的环境变量。以本目录为准；触发器 JSON 仍会被
   `ic shadow` 读取（`AUTO_RUN_JSON` 可覆盖）。

---

## 6. 与仓库内其它工具的关系

| 工具 | 位置 | 说明 |
|---|---|---|
| 诊断会话（F5） | `fabric/run/immersive_cinematics/diagnostics/` | `f-round.json` 是 manifest，产物在 `sessions/<时间戳>-<name>/` |
| lane 调试捕获 | `fabric/run/lane-captures/` | `ICINEMATICS_CAPTURE=1` 时按帧写 raw / composited / 窗口终帧 |
| 错误日志 | `fabric/run/logs/immersive_cinematics/script-errors.log` | 限频错误落盘（含堆栈） |
| 无头校验器 | `E:/tmp/icv`（仓库外） | `sh run.sh [脚本目录...]`，脚本格式 / 语义校验 |
| 数据层 / GL 冒烟 | `E:/tmp/icv2`、`E:/tmp/icgl` | 各 14 个 harness，改调色 / shader / lane 后跑 |
| 像素分析探针 | `E:/tmp/{AlphaStats,GreenProbe,RgbAnalyze,SeamProbe}.java` | alpha 分布 / 绿边 / RGB 分离 / 接缝 |
