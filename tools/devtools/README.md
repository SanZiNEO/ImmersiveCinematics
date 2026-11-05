# devtools：常用测试工具包

**只支持 Windows 11**（Win32 窗口/输入、PowerShell 进程枚举、`netstat` 端口探测都需要它）。

一个 Python 包 `icdev` 提供全部能力，两个入口：

- **MCP server（推荐）**：`python -m icdev.server`，stdio JSON-RPC，10 个工具；
- **薄壳 CLI**：`python devtools.py <子命令>`，同样能力也可用 CLI，但一次进程只做一条命令。

依赖由 uv 管理；首次使用先在本目录执行 `uv sync` 生成 `.venv`
（含 `numpy` + `pillow`，`.venv/` 不入库）。

---

## 0. MCP（推荐）

MCP 入口是**有状态**的：装置起来后一直留在同一个会话里，`rig.up` / `rig.down`
配对的进程、所有权、Job Object 兜底都由 server 维护；CLI 每次调用都是新进程，
只能对已经在运行的游戏做单步操作。

### 注册

仓库已带注册片段 [`mcp.json`](mcp.json)，把它合并进 MCP 客户端的配置即可：

```json
{
  "mcpServers": {
    "icdev": {
      "command": "E:/Documents/GitHub/ImmersiveCinematics/tools/devtools/.venv/Scripts/python.exe",
      "args": ["-m", "icdev.server"],
      "cwd": "E:/Documents/GitHub/ImmersiveCinematics/tools/devtools"
    }
  }
}
```

手动验证：

```bash
cd tools/devtools
uv run python -m icdev.server --selftest   # 内存管道自检，不碰游戏
uv run python -m icdev.server              # 正常启动（stdio，等客户端连）
```

### 10 个工具与 action 一览

| 工具 | action | 作用 |
|---|---|---|
| `rig` | `up` / `down` / `status` | 起停整个装置（服务端 + N 个客户端）并等就绪；`up` 支持 `world` / `clients` / `shader` / `debug` |
| `process` | `list` / `kill` | 列出 java 进程；只终止本会话登记且三元组匹配的进程 |
| `input` | `cmd` / `key` / `hold` | 聊天栏命令（可等日志正则）、单击按键、长按按键 |
| `capture` | `shot` / `pixels` | 游戏内截图；亮像素簇或双图差异（需要 `numpy` + `pillow`） |
| `log` | `tail` / `trace` / `wait` | 日志尾部（GBK 容错）、正则过滤/写出、阻塞等待命中 |
| `config` | `get` / `set` | 读写 `immersive_cinematics.json`（白名单 + 类型/范围校验） |
| `snapshot` | `create` / `restore` / `list` | 运行目录/脚本快照与还原（还原会删掉 manifest 之外的新增文件） |
| `rcon` | `command` | 向服务端发一条 RCON 命令 |
| `diag` | `manifest` / `perf` / `sessions` / `analyze` | 写 `f-round.json`、读性能摘要、列会话、校验会话结构 |
| `info` | `doctor` / `where` | 环境体检；当前世界/坐标/维度 |

工具执行失败返回 `isError: true` + `structuredContent = {code, message, detail?}`；
错误码见 `icdev/errors.py`（`E_NOT_READY` / `E_TIMEOUT` / `E_NO_WINDOW` / `E_CONFIG_INVALID` …）。

### 一个典型会话

```text
rig.up      { world: "新的世界", clients: ["clientA"] }      # 起服务端 + clientA，等就绪
input.cmd   { target: "clientA", text: "icinematics play fround:ic_f_perf_l25" }
input.hold  { target: "clientA", key: "space", seconds: 3 }  # 长按投票
capture.shot   { target: "clientA", path: "E:/tmp/icdev/shots/l25.png" }
capture.pixels { path: "E:/tmp/icdev/shots/l25.png", mode: "clusters" }
rig.down    {}                                               # 停掉整棵进程树并清所有权
```

装置默认目录：服务端 `E:/tmp/icdev/server`，客户端 `fabric/run` /
`E:/tmp/icdev/clientB` / `E:/tmp/icdev/clientC`（可用 `ICDEV_HOME` 改根目录）。

---

## 1. 怎么调用 CLI

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

## 2. 子命令总表

下表与 MCP 工具一一对应；同样能力也可用 CLI。CLI 直接 `print` 结果，
出错时 stderr 打印 `错误：E_XXX: ...` 并退出 1（不抛栈）。

### 控制游戏（客户端需在运行；Win32 定向消息，不抢焦点）

| 命令 | 作用 |
|---|---|
| `ic list` | 列出 Minecraft 窗口（pid / hwnd / 尺寸 / 标题）；没有时打印「没有找到 Minecraft 窗口」 |
| `ic cmd "<文本>"` | 聊天栏执行命令（自动补前导 `/`） |
| `ic say "<文本>"` | 聊天栏发消息（按 `t` 打开，不加 `/`） |
| `ic key <键名>` | 按键：`f1..f12` / `esc` / `enter` / `tab` / `space` / 单字符 |
| `ic hold <键名> <秒>` | 按住指定秒数（投票长按） |
| `ic play <脚本 id>` | = `cmd icinematics play <id>` |
| `ic stop` / `ic reload` | = `cmd icinematics stop` / `reload` |
| `ic shot [路径]` | 游戏内截图（发 F2，取游戏自己帧缓冲，只含游戏画面）。相对路径按 `fabric/run/` 解析；不给路径时留在 `fabric/run/screenshots/` 并打印路径 |
| `ic wait <秒>` | 等待 |
| `ic run <场景文件>` | 逐行执行场景脚本 |

### 装置与进程

| 命令 | 作用 |
|---|---|
| `ic server [世界名]` | **后台**起服务端：初始化 `E:/tmp/icdev/server`（eula / RCON 配置）→ `java -cp` 直启；打印 pid 后立刻返回 |
| `ic rig-status` | 汇总服务端/客户端进程、`25565`/`25575` 端口、在线玩家、当前播放脚本 |
| `ic rig-down` | 结束本机 icdev 登记的所有服务端与客户端 |
| `ic ps` | 列出 java 进程：`role / pid / owned / 端口` |
| `ic kill --target client\|server\|all [--name X]` | **只终止 icdev 登记过、且（pid, 创建时间, cmdline 哈希）三项全等的进程**；外部手工起的 java 永远不会被碰 |

`ic kill` / `ic ps` 的跨进程依据是 `E:/tmp/icdev/cli-owned.json`（`ic client` /
`ic server` 启动成功后写入）。文件丢了或三元组对不上，就当作「不是我的」。

### 服务端

| 命令 | 作用 |
|---|---|
| `ic rcon <命令> [--port] [--password] [--timeout-ms]` | 向服务端发一条 RCON 命令（默认 `127.0.0.1:25575` / 密码 `icdev`） |

### 观察

| 命令 | 作用 |
|---|---|
| `ic tail [行数]` | 打印 `latest.log` 尾部（**自动按 GBK 解码**） |
| `ic trace [--out 路径] [--lines N] [--follow] [--pattern 子串]` | 把日志里带诊断前缀的行过滤到独立文件（默认 `E:/tmp/gtest/gtest.log`；`--follow` 持续追加） |
| `ic perf [会话目录]` | 打印诊断会话的性能摘要（valid / lane rows / p50 / p95） |
| `ic sessions [数量]` | 列出最近的诊断会话（带 summary 数量） |
| `ic where` | 打印当前世界名 / 世界类型（flat/noise/debug）/ 玩家登录位置 / 维度 |
| `ic doctor` | 环境自检：窗口 / 日志新鲜度 / 最新会话 / debugLogging / 光影包 / vsync+maxFps |
| `ic analyze [会话目录]` | 校验诊断会话结构与数据完整性（含 summary 的 COMPLETE/指标表）；有问题退出 1 |

### 取证

| 命令 | 作用 |
|---|---|
| `ic pixels <图片> [--mode clusters\|diff] [--compare 图] [--threshold N] [--band y0 y1]` | 亮像素簇（默认）或双图差异，输出 JSON（坐标按原图像素） |
| `ic snapshot-create <名字> [目标...]` | 创建快照（缺省目标：`fabric/run` 的脚本 + 配置，以及 forge 配置） |
| `ic snapshot-restore <名字>` | 按 manifest 还原，并删除 manifest 之外的新增文件 |
| `ic snapshot-list` | 列出快照（名称 / 创建时间 / 文件数） |
| `ic config-get <键>` | 读 `fabric/run/config/immersive_cinematics.json` 单个键 |
| `ic config-set <键> <值>` | 白名单键 + 类型/范围校验后写入，打印旧值→新值 |

### 启动与取证

| 命令 | 作用 |
|---|---|
| `ic client [世界名] [--offline] [--username X]` | **后台**启动客户端（`ensure_common_jar` → `ensure_launch_spec` → `java -cp` 直启），工作目录 `fabric/run`，启动日志 `fabric/run/logs/icdev-client.out`；打印 pid 后立刻返回，**不阻塞** |
| `ic max` | 最大化已在运行的游戏窗口（后台启动不再自动最大化） |
| `ic shadow [光影包] [lane] [世界名] [--capture] [--username X]` | 一键 A/B 取证：写 `iris.properties` → 复制登录触发器 → 后台进世界自动播 |
| `ic scene [光影包] [lane] [--username X]` | 受控场景取证（白地板 + 四色墙，A 背对墙 / B 正对墙）；会等游戏退出后恢复 `setup_flat_rules.json` |

`client` / `server` 都不再走 `gradlew :fabric:runClient`：第一次会导出 loom 的
classpath 并缓存到 `E:/tmp/icdev/launch-spec.json`，之后直接 `java -cp` 起进程，
多开时不会抢 gradle 锁。`--offline` 是兼容参数——直启路径的构建固定带 `--offline`。

### 自检

| 命令 | 作用 |
|---|---|
| `ic selftest` | = `cd tools/devtools && python -m icdev.selftest`：9 组不依赖游戏/网络的模块自检 |

自检覆盖：模块导入与错误码、config 白名单、snapshot 存取、pixels、diag manifest/summary、
logs、`process.role_of`、真实 MCP 子进程协议（initialize/tools/list/未知工具）、
`launch` 命令形状。输出 `PASS/FAIL 名称` + `SUMMARY pass=N fail=M`，失败退出 1。

---

## 3. 为什么不用普通按键模拟

`icdev.win32` 用 Win32 `PostMessageW` 把 `WM_KEYDOWN` / `WM_KEYUP` / `WM_CHAR`
**直接投递到目标窗口**：

- 不依赖窗口焦点——可以一边在编辑器里干活一边驱动客户端；
- 找到多个 `Minecraft` 窗口时要求显式 `--pid`，不会误操作别的客户端；
- 发命令前先 `End` + 320 次 `Backspace` 清空输入框，不会和上次残留拼在一起；
- 扫描码用 `MapVirtualKeyW` 现算，不硬编码键表；
- 只用 `ctypes`，不再依赖 `pyautogui`。

---

## 4. 场景文件格式

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

## 5. 典型工作流

### 跑一次 F5 性能采样

```bash
# 1. 确认诊断清单里有目标 case
cat fabric/run/immersive_cinematics/diagnostics/f-round.json

# 2. 后台起客户端（登录触发会按 auto_run_ab.json 自动播放）
ic client "新的世界"
ic max                      # 窗口出现后最大化

# 3. 另开一个终端看日志
ic tail 200 | grep "PERF COMPLETE"

# 4. 看结果
ic perf
ic analyze                  # 默认校验最新会话
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

### 起服务端并直连

```bash
ic server "新的世界"          # 后台；默认 RCON 25575 / 密码 icdev
ic rcon list
ic client "新的世界" --username DevA
```

---

## 6. 注意事项

1. **日志是 GBK**：`latest.log` 里中文按 GBK 写盘，直接 `grep 中文` 永远不命中。
   `ic tail` 会按 `utf-8 → gbk → replace` 依次尝试。文件被客户端占用时用
   PowerShell 共享读转码：
   ```bash
   powershell -NoProfile -Command "$fs=[IO.File]::Open('fabric/run/logs/latest.log','Open','Read','ReadWrite'); \
     $sr=New-Object IO.StreamReader($fs,[Text.Encoding]::GetEncoding(936)); $t=$sr.ReadToEnd(); $sr.Close(); $fs.Close(); \
     [IO.File]::WriteAllText('E:\tmp\log-utf8.txt',$t,(New-Object Text.UTF8Encoding($false)))"
   ```
2. **运行时开关只剩 `ICINEMATICS_CAPTURE`**（或 `-Dicinematics.capture`）。
   `ic shadow --capture` 通过 `-Dicinematics.capture=true` 传给 JVM。
   `ICINEMATICS_CAPTURE_LANE` / `ICINEMATICS_PROBE_SCRIPT` / `ICINEMATICS_SHADOW_LOG`
   在 F 轮探针清理时已删除，导出也不会有人读。
3. **`debugLogging`**（`fabric/run/config/immersive_cinematics.json`）控制
   `[view-capture]` / `[preload *]` 等详细日志；性能采样时应保持 `false`。
4. **`ic client` / `ic server` 是后台进程**：命令返回后进程仍在跑，用 `ic ps` 看、
   `ic kill` 停。进程所有权只认 icdev 的登记表/登记文件，不按进程名乱杀。
5. **不要直接执行 `devtools.py`**（没有 shebang 依赖）：始终用 venv 里的 python 调用，
   或用 `uv run --directory`。
6. **`fabric/run/dev-tools/` 是历史目录**：里面留着旧版 shell 脚本与触发器 JSON，
   相对路径有误且导出了已失效的环境变量。以本目录为准；触发器 JSON 仍会被
   `ic shadow` 读取（`AUTO_RUN_JSON` 可覆盖）。

---

## 7. 与仓库内其它工具的关系

| 工具 | 位置 | 说明 |
|---|---|---|
| 诊断会话（F5） | `fabric/run/immersive_cinematics/diagnostics/` | `f-round.json` 是 manifest，产物在 `sessions/<时间戳>-<name>/` |
| lane 调试捕获 | `fabric/run/lane-captures/` | `ICINEMATICS_CAPTURE` 打开时按帧写 raw / composited / 窗口终帧 |
| 错误日志 | `fabric/run/logs/immersive_cinematics/script-errors.log` | 限频错误落盘（含堆栈） |
| 无头校验器 | `E:/tmp/icv`（仓库外） | `sh run.sh [脚本目录...]`，脚本格式 / 语义校验 |
| 数据层 / GL 冒烟 | `E:/tmp/icv2`、`E:/tmp/icgl` | 各 14 个 harness，改调色 / shader / lane 后跑 |
| 像素分析探针 | `E:/tmp/{AlphaStats,GreenProbe,RgbAnalyze,SeamProbe}.java` | alpha 分布 / 绿边 / RGB 分离 / 接缝 |
