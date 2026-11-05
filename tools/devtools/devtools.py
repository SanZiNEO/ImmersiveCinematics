#!/usr/bin/env python
"""ImmersiveCinematics 本地测试工具包：icdev 的薄壳 CLI。

    python devtools.py <子命令> [参数]

所有能力都由 ``icdev`` 包实现；本文件只做参数解析与 print。同样的能力也可以
通过 MCP server 使用（``python -m icdev.server``，注册片段见 ``mcp.json``）。

控制游戏（客户端需在运行；用 Win32 定向消息，不抢焦点）：
    list                    列出 Minecraft 窗口
    cmd <文本>              聊天栏执行命令（自动补 /）
    say <文本>              聊天栏发消息（不加 /）
    key <键名>              按键（f1..f12 / esc / enter / tab / space / 单字符）
    hold <键名> <秒>        按住指定时长（投票长按用）
    play <脚本 id>          = cmd icinematics play <id>
    stop | reload           = cmd icinematics stop / reload
    shot [路径]             游戏内截图（F2，只含游戏画面）
    wait <秒>
    run <场景文件>          逐行执行场景脚本

装置与进程：
    server [世界名]         后台起服务端（目录 E:/tmp/icdev/server）
    rig-status              汇总服务端/客户端/端口/玩家/当前脚本
    rig-down                结束本机 icdev 登记的所有服务端与客户端
    ps                      列出 java 进程（role / pid / owned / 端口）
    kill --target client|server|all [--name X]
                            只终止 icdev 登记且三元组匹配的进程

服务端：
    rcon <命令>             向服务端发一条 RCON 命令

观察：
    tail [行数]             打印 latest.log 尾部（GBK 自动解码）
    trace [--out 路径] ...  把带诊断前缀的行过滤到独立文件
    where                   当前世界 / 类型 / 位置 / 维度
    perf [会话目录]         打印诊断会话的性能摘要
    sessions [数量]         列出最近的诊断会话
    analyze [会话目录]      校验诊断会话结构与数据完整性
    doctor                  环境自检（窗口 / 日志 / 配置 / 会话）

取证：
    pixels <图片> [--mode clusters|diff] [--compare 图] [--threshold N] ...
    snapshot-create <名字> [目标...]
    snapshot-restore <名字>
    snapshot-list
    config-get <键>
    config-set <键> <值>

启动：
    client [世界名] [--offline] [--username X]
    max                     最大化已在运行的游戏窗口
    shadow [光影包] [lane] [世界名] [--capture]
    scene [光影包] [lane]

自检：
    selftest                不依赖游戏/网络的模块自检（= python -m icdev.selftest）

全局：--pid <PID>           多个 Minecraft 窗口时指定目标
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

from icdev import config as ic_config
from icdev import diag as ic_diag
from icdev import info as ic_info
from icdev import launch, logs, process, rcon, snapshot, win32
from icdev import rig as ic_rig
from icdev.errors import IcdError
from icdev.paths import (
    DEFAULT_GAME_PORT,
    DEFAULT_RCON_PASSWORD,
    DEFAULT_RCON_PORT,
    DEFAULT_SERVER_DIR,
    FABRIC_RUN,
    FORGE_RUN,
    ICDEV_HOME,
    REPO_ROOT,
    SNAPSHOT_ROOT,
)

# ---------------------------------------------------------------- 路径与常量

ROOT = REPO_ROOT
RUN = Path(os.environ.get("IC_RUN_DIR") or FABRIC_RUN)
LOG = RUN / "logs" / "latest.log"
DIAG = RUN / "immersive_cinematics" / "diagnostics"
SESSIONS = DIAG / "sessions"
SCRIPTS = RUN / "immersive_cinematics" / "scripts"
DEVTOOLS_RUN = RUN / "dev-tools"
IRIS = RUN / "config" / "iris.properties"

GTEST_DIR = Path("E:/tmp/gtest")

CLIENT_LOG_NAME = "icdev-client.out"
SERVER_LOG_NAME = "icdev-server.out"

DEFAULT_WORLD = "新的世界"
DEFAULT_USERNAME = "Dev"
DEFAULT_SHADOW_PACK = "ComplementaryUnbound_r5.9.3.zip"
DEFAULT_SCENE_PACK = "MakeUp-UltraFast-9.5f.zip"

#: CLI 自己维护的进程登记（跨进程的「只杀自有」依据，三元组与 icdev.process 一致）
CLI_OWNED_FILE = ICDEV_HOME / "cli-owned.json"

PERF_KEYS = re.compile(
    r"valid|data sufficiency|lane rows|lane failure|game_render|lane_total"
    r"|composite_total|frame_interval"
)


# ---------------------------------------------------------------- 小工具

def die(msg: str) -> None:
    print(f"错误：{msg}", file=sys.stderr)
    raise SystemExit(1)


def rel(p) -> str:
    try:
        return str(Path(p).relative_to(ROOT))
    except ValueError:
        return str(p)


def _disp_width(s: str) -> int:
    """中文占两列，`doctor` 的对齐按显示宽度算。"""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in s)


def _json(value) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2))


def _decode(raw: bytes) -> str:
    """与 icdev.logs 一致的回落顺序（utf-8 → gbk → replace）。"""
    for encoding in ("utf-8", "gbk"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


# ---------------------------------------------------------------- 进程登记

def _record_pid(record: dict) -> int:
    try:
        return int(record.get("pid") or -1)
    except (TypeError, ValueError):
        return -1


def _load_owned() -> list[dict]:
    try:
        data = json.loads(CLI_OWNED_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    items = data.get("processes") if isinstance(data, dict) else None
    return [item for item in items if isinstance(item, dict)] if isinstance(items, list) else []


def _save_owned(records: list[dict]) -> None:
    try:
        ICDEV_HOME.mkdir(parents=True, exist_ok=True)
        tmp = CLI_OWNED_FILE.with_suffix(".json.tmp")
        tmp.write_text(
            json.dumps({"processes": records}, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.replace(tmp, CLI_OWNED_FILE)
    except OSError as exc:
        print(f"警告：进程登记写入失败：{exc}", file=sys.stderr)


def _remember(pid: int, role: str, run_dir: Path) -> None:
    """spawn 后把 (pid, 创建时间, cmdline 哈希) 落到 CLI 登记表，供 ps/kill 跨进程使用。"""
    if not process.IS_WINDOWS:
        return
    owned = None
    for _ in range(8):
        try:
            owned = process.register_owned(role, pid)
            break
        except IcdError:
            time.sleep(0.3)
    if owned is None:
        print(f"警告：pid={pid} 所有权登记失败（ic kill 不会命中它）", file=sys.stderr)
        return

    records = [item for item in _load_owned() if _record_pid(item) != owned.pid]
    records.append(
        {
            "role": owned.role,
            "pid": owned.pid,
            "created_at": owned.created_at,
            "cmdline_hash": owned.cmdline_hash,
            "run_dir": str(run_dir),
            "registered_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
    )
    _save_owned(records)


def _verified_owned() -> list[tuple[dict, process.RawProc]]:
    """登记表 ∧ 当前进程列表：pid、创建时间、命令行哈希三项全等才算自有。"""
    records = _load_owned()
    if not records:
        return []
    live = {item.pid: item for item in process.list_java_processes(use_cache=False)}
    alive: list[tuple[dict, process.RawProc]] = []
    for record in records:
        pid = _record_pid(record)
        raw = live.get(pid)
        if raw is None:
            continue
        if raw.created_at != record.get("created_at"):
            continue
        if process.cmdline_hash(raw.cmdline) != record.get("cmdline_hash"):
            continue
        alive.append((record, raw))
    if live and len(alive) != len(records):
        _save_owned([record for record, _ in alive])
    return alive


def _role_matches(role: str, target: str) -> bool:
    if target == "all":
        return True
    if target == "server":
        return role == "server"
    return role == "client" or role.startswith("client:")


# ---------------------------------------------------------------- 装置接线

def _rig_for_window(pid=None):
    """把一个已在运行的 Minecraft 窗口装配成单客户端 Rig。

    icdev.rig 没有「接管已有进程」的公开入口，而本任务约定不改 icdev/*；
    这里只注入一个 _Client，之后输入 / 截图 / 日志等待全部走 Rig 的公开方法。
    """
    window = win32.find_window(pid)
    rig = ic_rig.get_rig()
    rig._clients = [
        ic_rig._Client(name="cli", run_dir=RUN, pid=window.pid, ready=True)
    ]
    rig._state = "ready"
    return rig


def _cmd_text(rig, text: str) -> None:
    text = text.strip()
    if not text.startswith("/"):
        text = "/" + text
    rig.cmd(text)
    print(f"COMMAND {text.lstrip('/')}", flush=True)


def _say(rig, text: str) -> None:
    rig.cmd(text)
    print(f"CHAT {text}", flush=True)


def _shot(rig, path: str | None = None) -> None:
    target = None
    if path:
        candidate = Path(path)
        target = str(candidate if candidate.is_absolute() else RUN / candidate)
    data = rig.shot(path=target)
    print(rel(data["path"]), flush=True)


def _run_scenario(rig, path: str) -> None:
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        name, _, rest = line.partition(" ")
        rest = rest.strip()
        print(f"> {line}", flush=True)
        if name == "wait":
            time.sleep(float(rest))
        elif name == "cmd":
            _cmd_text(rig, rest)
        elif name == "say":
            _say(rig, rest)
        elif name == "key":
            rig.key(rest)
        elif name == "play":
            _cmd_text(rig, f"icinematics play {rest}")
        elif name == "stop":
            _cmd_text(rig, "icinematics stop")
        elif name == "reload":
            _cmd_text(rig, "icinematics reload")
        elif name == "shot":
            _shot(rig, rest or None)
        else:
            die(f"场景里未知子命令：{name}")


# ---------------------------------------------------------------- 控制

def cmd_list() -> int:
    windows = win32.list_minecraft_windows()
    if not windows:
        print("没有找到 Minecraft 窗口")
        return 0
    for win in windows:
        print(
            f"pid={win.pid:>6} hwnd={win.hwnd:>10} "
            f"{win.width}x{win.height} @({win.x},{win.y})  {win.title}"
        )
    return 0


# ---------------------------------------------------------------- 日志与诊断

def cmd_tail(lines: int) -> int:
    if not LOG.exists():
        die(f"日志不存在：{rel(LOG)}")
    text = logs.tail(LOG, lines)
    if text:
        print(text)
    return 0


def cmd_trace(out=None, lines=None, follow=False, pattern="[G-TEST]") -> int:
    """把 latest.log 里带诊断前缀的行过滤到独立文件（默认 E:/tmp/gtest/gtest.log）。"""
    if not LOG.exists():
        die(f"日志不存在：{rel(LOG)}")
    target = Path(out) if out else GTEST_DIR / "gtest.log"
    target.parent.mkdir(parents=True, exist_ok=True)

    text = logs.read_text(LOG)
    selected = [ln for ln in text.splitlines() if pattern in ln]
    if lines:
        selected = selected[-lines:]
    target.write_text("\n".join(selected) + ("\n" if selected else ""), encoding="utf-8")
    print(f"写入 {target}（{len(selected)} 行，pattern={pattern}）")
    if not follow:
        return 0

    offset = LOG.stat().st_size
    print("follow（Ctrl+C 退出）…", flush=True)
    try:
        while True:
            time.sleep(0.5)
            if not LOG.exists():
                continue
            size = LOG.stat().st_size
            if size < offset:
                offset = 0
            if size == offset:
                continue
            with LOG.open("rb") as fh:
                fh.seek(offset)
                chunk = fh.read(size - offset)
            offset = size
            fresh = [ln for ln in _decode(chunk).splitlines() if pattern in ln]
            for ln in fresh:
                print(ln, flush=True)
            if fresh:
                with target.open("a", encoding="utf-8") as fh:
                    for ln in fresh:
                        fh.write(ln + "\n")
    except KeyboardInterrupt:
        return 0


def cmd_where() -> int:
    data = ic_info.where()
    pos = data.get("pos")
    pos_text = ", ".join(f"{value:g}" for value in pos) if isinstance(pos, list) else None
    print(
        f"world={data.get('world')} type={data.get('worldType')} "
        f"pos={pos_text} dim={data.get('dimension')}"
    )
    for issue in data.get("issues") or []:
        print(f"WARN {issue}", file=sys.stderr)
    return 0


def _latest_session() -> Path:
    items = ic_diag.list_sessions(RUN, limit=1)
    if not items:
        die(f"没有诊断会话：{rel(SESSIONS)}")
    return Path(items[0]["path"])


def cmd_perf(session=None) -> int:
    directory = Path(session) if session else _latest_session()
    print(f"# {directory.name}")
    summaries = ic_diag.read_summaries(directory)
    if not summaries:
        print("（该会话没有性能 summary：manifest 里的 perf case 没跑，或会话被提前关闭）")
        return 0
    for key in sorted(summaries):
        print(f"\n## {key}")
        for line in summaries[key]["text"].splitlines():
            if PERF_KEYS.search(line):
                print(line)
    return 0


def cmd_sessions(count: int = 8) -> int:
    items = ic_diag.list_sessions(RUN, limit=count)
    if not items:
        print(f"（没有诊断会话：{rel(SESSIONS)}）")
        return 0
    for item in reversed(items):  # list_sessions 按 mtime 降序；CLI 按时间正序输出
        names = [Path(name).stem.replace("-summary", "") for name in item["summaries"]]
        suffix = f"  [{', '.join(names)}]" if names else ""
        print(f"{item['name']}  summaries={len(names)}{suffix}")
    return 0


def cmd_analyze(session=None) -> int:
    directory = Path(session) if session else _latest_session()
    data = ic_diag.analyze(directory)
    print(f"session: {directory}")
    print(f"summaries={data['summaries']}")
    for issue in data["issues"]:
        print(f"ERROR {issue}")
    if not data["ok"]:
        print(f"FAILED: {len(data['issues'])} structural error(s)")
        return 1
    print("OK: session structurally valid")
    return 0


def cmd_doctor() -> int:
    data = ic_info.doctor()
    rows: list[tuple[str, str]] = []

    windows = data.get("windows") or []
    if windows:
        for win in windows:
            rows.append(
                ("客户端窗口", f"pid={win.get('pid')} {win.get('w')}x{win.get('h')} {win.get('title')}")
            )
    else:
        rows.append(("客户端窗口", "未运行"))

    info = data.get("log") or {}
    if info.get("size") is not None:
        rows.append(
            ("latest.log", f"{rel(info.get('path'))}（{info.get('ageSec')}s 前更新，{info.get('size')} 字节）")
        )
    else:
        rows.append(("latest.log", "不存在"))

    rows.append(("最新诊断会话", data.get("latestSession") or "无"))
    debug = data.get("debugLogging")
    rows.append(("debugLogging", str(debug).lower() if debug is not None else "?"))
    rows.append(("光影包", data.get("shaderpack") or "?"))

    vsync = data.get("vsync")
    max_fps = data.get("maxFps")
    if vsync is None and max_fps is None:
        rows.append(("vsync / maxFps", "?"))
    else:
        rows.append(
            (
                "vsync / maxFps",
                f"{'off' if vsync else 'ON（性能采样要关）'} / {max_fps if max_fps is not None else '?'}",
            )
        )

    width = max(_disp_width(key) for key, _ in rows)
    for key, value in rows:
        print(f"{key}{' ' * (width - _disp_width(key) + 2)}{value}")
    for issue in data.get("issues") or []:
        print(f"WARN {issue}", file=sys.stderr)
    return 0


# ---------------------------------------------------------------- 装置与进程

def _prepare_server_dir(server_dir: Path, world: str | None) -> None:
    server_dir.mkdir(parents=True, exist_ok=True)
    (server_dir / "logs").mkdir(parents=True, exist_ok=True)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")

    entries = [
        (key, world if (key == "level-name" and world) else value)
        for key, value in ic_rig.SERVER_PROPERTIES_DEFAULTS
    ]
    force = set(ic_rig.RCON_FORCED_KEYS)
    if world:
        force.add("level-name")
    _patch_properties(server_dir / "server.properties", entries, force)


def _patch_properties(path: Path, entries: list[tuple[str, str]], force: set[str]) -> None:
    """按 Java properties 的 key=value 补齐缺失键；force 中的键强制覆盖。"""
    try:
        original = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        original = []

    wanted = dict(entries)
    seen: set[str] = set()
    out: list[str] = []
    for line in original:
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")):
            out.append(line)
            continue
        if "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
        elif ":" in stripped:
            key = stripped.split(":", 1)[0].strip()
        else:
            out.append(line)
            continue
        seen.add(key)
        out.append(f"{key}={wanted[key]}" if key in force and key in wanted else line)

    missing = [(key, value) for key, value in entries if key not in seen]
    if missing:
        if out and out[-1].strip():
            out.append("")
        out.extend(f"{key}={value}" for key, value in missing)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def cmd_ps() -> int:
    procs = process.list_java_processes()
    owned = {_record_pid(record): record.get("role") for record, _ in _verified_owned()}
    for item in process.owned_processes():
        if process.is_owned(item.pid):
            owned[item.pid] = item.role

    game_listen = process.port_listening(DEFAULT_GAME_PORT) if procs else False
    rcon_listen = process.port_listening(DEFAULT_RCON_PORT) if procs else False

    print(f"{'role':<10} {'pid':>7}  {'owned':<5} port")
    for raw in procs:
        role = process.role_of(raw.cmdline) or "-"
        ports = "-"
        if role == "server":
            ports = (
                f"{DEFAULT_GAME_PORT}={'listening' if game_listen else 'closed'} "
                f"rcon={DEFAULT_RCON_PORT}:{'listening' if rcon_listen else 'closed'}"
            )
        print(f"{role:<10} {raw.pid:>7}  {'yes' if raw.pid in owned else 'no':<5} {ports}")
    if not procs:
        print("（没有 java 进程）")
    return 0


def cmd_kill(target: str = "all", name: str | None = None) -> int:
    victims: list[dict] = []
    seen: set[int] = set()

    for record, raw in _verified_owned():
        role = str(record.get("role") or "")
        if not _role_matches(role, target):
            continue
        if name and name.lower() not in f"{role} {raw.cmdline}".lower():
            continue
        victims.append(record)
        seen.add(_record_pid(record))

    for item in process.owned_processes():
        if item.pid in seen or not _role_matches(item.role, target):
            continue
        if not process.is_owned(item.pid):
            continue
        if name and name.lower() not in item.role.lower():
            continue
        victims.append({"role": item.role, "pid": item.pid})
        seen.add(item.pid)

    if not victims:
        print("没有匹配的自有进程（只终止 icdev 登记过的进程）")
        return 0

    remaining = [
        record for record in _load_owned() if _record_pid(record) not in seen
    ]
    _save_owned(remaining)

    for record in victims:
        pid = _record_pid(record)
        if pid <= 0:
            continue
        process.terminate(pid, tree=True)
        process.forget_owned(pid)
        print(f"KILLED {record.get('role')} pid={pid}")
    return 0


def _current_script() -> str | None:
    directories = [RUN]
    directories += [
        Path(record["run_dir"])
        for record in _load_owned()
        if isinstance(record.get("run_dir"), str) and record["run_dir"]
    ]
    for directory in dict.fromkeys(directories):
        text = logs.tail(directory / "logs" / "latest.log", 400)
        found = ic_rig.SCRIPT_RE.findall(text)
        if found:
            return found[-1]
    return None


def cmd_rig_status() -> int:
    procs = process.list_java_processes()
    servers = [item for item in procs if process.role_of(item.cmdline) == "server"]
    clients = [item for item in procs if process.role_of(item.cmdline) == "client"]

    game_listen = process.port_listening(DEFAULT_GAME_PORT) if procs else False
    rcon_listen = process.port_listening(DEFAULT_RCON_PORT) if procs else False

    if servers and game_listen:
        state = "ready"
    elif procs:
        state = "partial"
    else:
        state = "absent"

    print(f"state={state}")
    for server in servers:
        print(
            f"server: pid={server.pid} port={DEFAULT_GAME_PORT}"
            f"({'listening' if game_listen else 'closed'})"
            f" rcon={DEFAULT_RCON_PORT}({'listening' if rcon_listen else 'closed'})"
        )
    if not servers:
        print("server: -")

    try:
        windows = {win.pid for win in win32.list_minecraft_windows()}
    except IcdError:
        windows = set()
    if clients:
        for client in clients:
            has_window = "yes" if client.pid in windows else "no"
            print(f"client: pid={client.pid} window={has_window}")
    else:
        print("client: -")

    players: list[str] = []
    if servers and rcon_listen:
        try:
            text = rcon.command("list", port=DEFAULT_RCON_PORT, password=DEFAULT_RCON_PASSWORD)
        except IcdError:
            text = ""
        match = ic_rig.LIST_RE.search(text or "")
        if match:
            players = [name.strip() for name in match.group(1).split(",") if name.strip()]
    print(f"players={players}")
    print(f"currentScript={_current_script()}")
    return 0


# ---------------------------------------------------------------- 服务端

def cmd_rcon(command: str, port: int, password: str, timeout_ms: int) -> int:
    client = rcon.get_client(port=port, password=password)
    client.timeout = max(0.5, float(timeout_ms) / 1000.0)
    print(client.command(command.lstrip("/")))
    return 0


# ---------------------------------------------------------------- 取证

def cmd_pixels(path: str, mode: str, compare: str | None, threshold: int | None, band) -> int:
    from icdev import pixels  # 按需导入：numpy/PIL 只在 pixels 命令里加载

    image = Path(path)
    if mode == "clusters":
        band_value = tuple(band) if band else None
        found = pixels.clusters(
            image,
            threshold=threshold if threshold is not None else 190,
            band=band_value,
        )
        _json(
            {
                "mode": "clusters",
                "path": str(image),
                "threshold": threshold if threshold is not None else 190,
                "count": len(found),
                "clusters": found,
            }
        )
        return 0

    if not compare:
        die("--mode diff 需要 --compare <对照图片>")
    data = pixels.diff(
        image,
        Path(compare),
        threshold=threshold if threshold is not None else 60,
    )
    _json({"mode": "diff", "path": str(image), "compare": compare, **data})
    return 0


def _default_snapshot_targets() -> list[Path]:
    candidates = [
        RUN / "immersive_cinematics" / "scripts",
        RUN / "config" / "immersive_cinematics.json",
        FORGE_RUN / "config" / "immersive_cinematics.json",
    ]
    targets = [path for path in candidates if path.exists()]
    if not targets:
        die("没有可快照的默认目标（fabric/forge 的配置或脚本不存在）")
    return targets


def cmd_snapshot_create(name: str, targets: list[str]) -> int:
    paths = [Path(item) for item in targets] if targets else _default_snapshot_targets()
    data = snapshot.create(name, paths)
    print(f"created {data['name']} files={data['files']} → {rel(data['path'])}")
    return 0


def cmd_snapshot_restore(name: str) -> int:
    data = snapshot.restore(name)
    print(f"restored={data['restored']} removed={len(data['removed'])} missing={len(data['missing'])}")
    for item in data["removed"]:
        print(f"REMOVED {item}")
    for item in data["missing"]:
        print(f"MISSING {item}")
    return 0


def cmd_snapshot_list() -> int:
    items = snapshot.list_snapshots()
    if not items:
        print(f"（没有快照：{rel(SNAPSHOT_ROOT)}）")
        return 0
    for item in items:
        print(f"{item['name']}  created={item['created_at']}  files={item['files']}")
    return 0


def cmd_config_get(key: str) -> int:
    print(json.dumps(ic_config.get(RUN, key), ensure_ascii=False))
    return 0


def cmd_config_set(key: str, raw: str) -> int:
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        value = raw
    old = ic_config.set(RUN, key, value)
    stored = ic_config.get(RUN, key)
    print(f"{key}: {old!r} -> {stored!r}")
    return 0


# ---------------------------------------------------------------- 启动

def _launch_client(
    world: str,
    username: str = DEFAULT_USERNAME,
    extra_jvm: tuple[str, ...] = (),
    run_dir: Path = RUN,
    log_name: str = CLIENT_LOG_NAME,
    maximize_hint: bool = True,
    wait_exit: bool = False,
):
    launch.ensure_common_jar()
    spec = launch.ensure_launch_spec()
    if extra_jvm:
        spec = launch.LaunchSpec(
            main=spec.main,
            classpath=list(spec.classpath),
            jvm_args=[*spec.jvm_args, *extra_jvm],
        )

    cmd = launch.build_client_cmd(spec, run_dir, username, world=world)
    log_path = Path(run_dir) / "logs" / log_name
    proc = launch.spawn(cmd, Path(run_dir), log_path)

    print(f"[client] world={world} username={username} pid={proc.pid}", flush=True)
    print(
        f"[client] 启动日志：{rel(log_path)}（游戏日志：{rel(Path(run_dir) / 'logs' / 'latest.log')}，"
        "用 `ic tail` 看）"
    )
    print("[client] 后台运行，命令已返回（进程不会随本命令退出）", flush=True)
    if maximize_hint:
        print("[client] 窗口出现后用 `ic max` 最大化", flush=True)
    _remember(proc.pid, "client", Path(run_dir))

    if wait_exit:
        print("[client] 等待游戏退出（Ctrl+C 只停止等待，不会杀进程）…", flush=True)
        try:
            code = launch.wait_exit(proc)
            print(f"[client] 已退出 exit={code}", flush=True)
        except KeyboardInterrupt:
            print("[client] 已停止等待；游戏仍在后台运行", flush=True)

    return proc


def cmd_client(world: str, username: str, maximize_hint: bool = True) -> int:
    _launch_client(world, username=username, maximize_hint=maximize_hint)
    return 0


def cmd_server(world: str | None) -> int:
    launch.ensure_common_jar()
    spec = launch.ensure_launch_spec()
    _prepare_server_dir(DEFAULT_SERVER_DIR, world)

    cmd = launch.build_server_cmd(spec, DEFAULT_SERVER_DIR)
    log_path = DEFAULT_SERVER_DIR / "logs" / SERVER_LOG_NAME
    proc = launch.spawn(cmd, DEFAULT_SERVER_DIR, log_path)

    print(f"[server] dir={DEFAULT_SERVER_DIR} pid={proc.pid}", flush=True)
    print(
        f"[server] 启动日志：{rel(log_path)}（游戏日志：{rel(DEFAULT_SERVER_DIR / 'logs' / 'latest.log')}；"
        "`ic tail` 只读 fabric/run 的日志）"
    )
    _remember(proc.pid, "server", DEFAULT_SERVER_DIR)
    return 0


def cmd_maximize(pid=None) -> int:
    win32.maximize(win32.find_window(pid))
    print("已最大化 Minecraft 窗口")
    return 0


def cmd_shadow(
    pack: str,
    lane: str,
    world: str,
    capture: bool = False,
    username: str = DEFAULT_USERNAME,
    wait_exit: bool = False,
) -> int:
    (SCRIPTS / "fqueue").mkdir(parents=True, exist_ok=True)
    IRIS.parent.mkdir(parents=True, exist_ok=True)

    ab = SCRIPTS / "fqueue/test_ab_split_scale.json"
    if not ab.exists():
        src = Path(
            "D:/mc/.minecraft/versions/ImmersiveCinematics/immersive_cinematics"
            "/scripts/fqueue/test_ab_split_scale.json"
        )
        if not src.exists():
            die(f"A/B 测试脚本缺失：{rel(ab)}，生产实例也没有：{src}")
        shutil.copy2(src, ab)

    auto_run = Path(os.environ.get("AUTO_RUN_JSON", DEVTOOLS_RUN / "auto_run_ab.json"))
    if not auto_run.exists():
        die(f"登录触发器缺失：{auto_run}")
    shutil.copy2(auto_run, SCRIPTS / "auto_run_ab.json")

    IRIS.write_text(
        "#This file stores configuration options for Iris, such as the currently active shaderpack\n"
        "colorSpace=SRGB\ndisableUpdateMessage=true\nenableDebugOptions=false\n"
        "maxShadowRenderDistance=32\n"
        f"shaderPack={pack}\nenableShaders=true\n",
        encoding="utf-8",
    )

    print(f"[shadow] pack={pack} lane={lane} world={world} capture={capture}")
    extra = ("-Dicinematics.capture=true",) if capture else ()
    _launch_client(world, username=username, extra_jvm=extra, wait_exit=wait_exit)
    return 0


def cmd_scene(pack: str, lane: str, username: str = DEFAULT_USERNAME) -> int:
    (SCRIPTS / "fqueue").mkdir(parents=True, exist_ok=True)
    src = DEVTOOLS_RUN / "test_shadow_scene.json"
    if not src.exists():
        die(f"受控场景脚本缺失：{src}")
    shutil.copy2(src, SCRIPTS / "fqueue/test_shadow_scene.json")

    setup = SCRIPTS / "setup_flat_rules.json"
    backup = SCRIPTS / "setup_flat_rules.json.bak"
    moved = False
    if setup.exists():
        shutil.move(str(setup), str(backup))
        moved = True
    try:
        return cmd_shadow(
            pack, lane, DEFAULT_WORLD, capture=True, username=username, wait_exit=True
        )
    finally:
        if moved:
            shutil.move(str(backup), str(setup))


# ---------------------------------------------------------------- 入口

def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="devtools.py",
        description="ImmersiveCinematics 本地测试工具包（icdev 薄壳 CLI）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "MCP（推荐）：python -m icdev.server，注册片段见 mcp.json\n"
            "自检：python devtools.py selftest（= python -m icdev.selftest）"
        ),
    )
    parser.add_argument("--pid", type=int, default=None, help="多个 Minecraft 窗口时指定目标")
    sub = parser.add_subparsers(dest="action", required=True)

    # 控制
    sub.add_parser("list", help="列出 Minecraft 窗口")
    for name, helptext in (("cmd", "聊天栏执行命令"), ("say", "聊天栏发消息")):
        child = sub.add_parser(name, help=helptext)
        child.add_argument("text")
    child = sub.add_parser("key", help="按键")
    child.add_argument("key")
    child = sub.add_parser("hold", help="按住按键指定秒数（投票长按）")
    child.add_argument("key")
    child.add_argument("seconds", type=float)
    child = sub.add_parser("play", help="播放脚本")
    child.add_argument("script_id")
    sub.add_parser("stop", help="停止播放")
    sub.add_parser("reload", help="重载脚本")
    child = sub.add_parser("shot", help="游戏内截图（F2，只含游戏画面）")
    child.add_argument("path", nargs="?")
    child = sub.add_parser("wait", help="等待")
    child.add_argument("seconds", type=float)
    child = sub.add_parser("run", help="执行场景文件")
    child.add_argument("scenario")

    # 装置与进程
    child = sub.add_parser("server", help="后台起服务端")
    child.add_argument("world", nargs="?", default=None, help="世界名（缺省沿用 server.properties）")
    sub.add_parser("rig-status", help="汇总服务端/客户端/端口/玩家")
    sub.add_parser("rig-down", help="结束本机 icdev 登记的所有装置进程")
    sub.add_parser("ps", help="列出 java 进程")
    child = sub.add_parser("kill", help="只终止 icdev 登记的自有进程")
    child.add_argument("--target", choices=("client", "server", "all"), default="all")
    child.add_argument("--name", default=None, help="按客户端名或命令行子串过滤")

    # 服务端
    child = sub.add_parser("rcon", help="向服务端发一条 RCON 命令")
    child.add_argument("command")
    child.add_argument("--port", type=int, default=DEFAULT_RCON_PORT)
    child.add_argument("--password", default=DEFAULT_RCON_PASSWORD)
    child.add_argument("--timeout-ms", type=int, default=10000)

    # 观察
    child = sub.add_parser("tail", help="日志尾部（GBK 解码）")
    child.add_argument("lines", nargs="?", type=int, default=40)
    child = sub.add_parser("trace", help="过滤诊断日志到独立文件")
    child.add_argument("--out", help="输出路径（默认 E:/tmp/gtest/gtest.log）")
    child.add_argument("--lines", type=int, help="只取末尾 N 行")
    child.add_argument("--follow", action="store_true", help="持续跟踪新增行")
    child.add_argument("--pattern", default="[G-TEST]", help="过滤子串（默认 [G-TEST]）")
    sub.add_parser("where", help="打印世界类型 / 玩家位置 / 维度")
    child = sub.add_parser("perf", help="性能摘要")
    child.add_argument("session", nargs="?")
    child = sub.add_parser("sessions", help="列出诊断会话")
    child.add_argument("count", nargs="?", type=int, default=8)
    child = sub.add_parser("analyze", help="校验诊断会话")
    child.add_argument("session", nargs="?")
    sub.add_parser("doctor", help="环境自检")

    # 取证
    child = sub.add_parser("pixels", help="像素级取证（亮簇 / 双图差异）")
    child.add_argument("path")
    child.add_argument("--mode", choices=("clusters", "diff"), default="clusters")
    child.add_argument("--compare", default=None, help="diff 的对照图片")
    child.add_argument("--threshold", type=int, default=None)
    child.add_argument("--band", nargs=2, type=float, default=None, help="相对高度带 y0 y1")
    child = sub.add_parser("snapshot-create", help="创建运行目录快照")
    child.add_argument("name")
    child.add_argument("targets", nargs="*", help="相对仓库根的文件/目录（缺省为配置与脚本）")
    child = sub.add_parser("snapshot-restore", help="还原快照（删除新增文件）")
    child.add_argument("name")
    sub.add_parser("snapshot-list", help="列出快照")
    child = sub.add_parser("config-get", help="读 mod 配置项")
    child.add_argument("key")
    child = sub.add_parser("config-set", help="写 mod 配置项（白名单校验）")
    child.add_argument("key")
    child.add_argument("value")

    # 启动
    child = sub.add_parser("client", help="后台启动客户端并直进世界")
    child.add_argument("world", nargs="?", default=DEFAULT_WORLD)
    child.add_argument(
        "--offline",
        action="store_true",
        help="兼容保留：直启路径固定以 --offline 构建 common jar",
    )
    child.add_argument("--username", default=DEFAULT_USERNAME, help="游戏内用户名")
    child.add_argument(
        "--no-maximize",
        action="store_true",
        help="不打印「用 ic max 最大化」提示（后台启动本身不会等窗口）",
    )
    sub.add_parser("max", help="最大化已在运行的游戏窗口")
    child = sub.add_parser("shadow", help="A/B 取证")
    child.add_argument("pack", nargs="?", default=DEFAULT_SHADOW_PACK)
    child.add_argument("lane", nargs="?", default="cam0")
    child.add_argument("world", nargs="?", default=DEFAULT_WORLD)
    child.add_argument("--capture", action="store_true", help="开 ICINEMATICS_CAPTURE 出图")
    child.add_argument("--username", default=DEFAULT_USERNAME)
    child = sub.add_parser("scene", help="受控场景取证")
    child.add_argument("pack", nargs="?", default=DEFAULT_SCENE_PACK)
    child.add_argument("lane", nargs="?", default="cam0")
    child.add_argument("--username", default=DEFAULT_USERNAME)

    sub.add_parser("selftest", help="不依赖游戏/网络的模块自检")
    return parser


def _dispatch(args: argparse.Namespace) -> int:
    action = args.action

    if action == "list":
        return cmd_list()
    if action == "tail":
        return cmd_tail(args.lines)
    if action == "trace":
        return cmd_trace(args.out, args.lines, args.follow, args.pattern)
    if action == "where":
        return cmd_where()
    if action == "perf":
        return cmd_perf(args.session)
    if action == "sessions":
        return cmd_sessions(args.count)
    if action == "doctor":
        return cmd_doctor()
    if action == "analyze":
        return cmd_analyze(args.session)
    if action == "client":
        return cmd_client(args.world, args.username, not args.no_maximize)
    if action == "server":
        return cmd_server(args.world)
    if action == "max":
        return cmd_maximize(args.pid)
    if action == "shadow":
        return cmd_shadow(args.pack, args.lane, args.world, args.capture, args.username)
    if action == "scene":
        return cmd_scene(args.pack, args.lane, args.username)
    if action == "ps":
        return cmd_ps()
    if action == "kill":
        return cmd_kill(args.target, args.name)
    if action == "rig-status":
        return cmd_rig_status()
    if action == "rig-down":
        result = cmd_kill("all", None)
        rcon.close_all()
        return result
    if action == "rcon":
        return cmd_rcon(args.command, args.port, args.password, args.timeout_ms)
    if action == "pixels":
        return cmd_pixels(args.path, args.mode, args.compare, args.threshold, args.band)
    if action == "snapshot-create":
        return cmd_snapshot_create(args.name, args.targets)
    if action == "snapshot-restore":
        return cmd_snapshot_restore(args.name)
    if action == "snapshot-list":
        return cmd_snapshot_list()
    if action == "config-get":
        return cmd_config_get(args.key)
    if action == "config-set":
        return cmd_config_set(args.key, args.value)
    if action == "selftest":
        from icdev import selftest

        return selftest.main([])

    if action == "wait":
        time.sleep(args.seconds)
        return 0

    # 其余都需要一个已在运行的客户端窗口
    rig = _rig_for_window(args.pid)
    if action == "cmd":
        _cmd_text(rig, args.text)
    elif action == "say":
        _say(rig, args.text)
    elif action == "key":
        rig.key(args.key)
    elif action == "hold":
        rig.hold(args.key, args.seconds)
    elif action == "play":
        _cmd_text(rig, f"icinematics play {args.script_id}")
    elif action == "stop":
        _cmd_text(rig, "icinematics stop")
    elif action == "reload":
        _cmd_text(rig, "icinematics reload")
    elif action == "shot":
        _shot(rig, args.path)
    elif action == "run":
        _run_scenario(rig, args.scenario)
    else:  # pragma: no cover - argparse 已限制
        die(f"未知子命令：{action}")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        return _dispatch(args)
    except IcdError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        detail = exc.detail
        if isinstance(detail, list) and detail and isinstance(detail[0], dict):
            for item in detail:
                print(
                    f"  pid={item.get('pid')} hwnd={item.get('hwnd')} {item.get('title')}",
                    file=sys.stderr,
                )
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
