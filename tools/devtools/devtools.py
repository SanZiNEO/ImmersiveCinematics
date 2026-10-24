#!/usr/bin/env python
"""ImmersiveCinematics 本地测试工具包：一个入口做全部常用操作。

    python devtools.py <子命令> [参数]

控制游戏（客户端需在运行；用 Win32 定向消息，不抢焦点）：
    list                    列出 Minecraft 窗口
    cmd <文本>              聊天栏执行命令（自动补 /）
    say <文本>              聊天栏发消息（不加 /）
    key <键名>              按键（f1..f12 / esc / enter / tab / space / 单字符）
    play <脚本 id>          = cmd icinematics play <id>
    stop | reload           = cmd icinematics stop / reload
    shot [路径]             截游戏窗口
    wait <秒>
    run <场景文件>          逐行执行场景脚本

观察：
    tail [行数]             打印 latest.log 尾部（GBK 自动解码）
    perf [会话目录]         打印诊断会话的性能摘要
    sessions [数量]         列出最近的诊断会话
    doctor                  环境自检（窗口 / 日志 / 配置 / 会话）

启动与取证：
    client [世界名] [--offline]          启动 Fabric 客户端并进世界
    shadow [光影包] [lane] [世界名]      一键 A/B 取证（固定 pack + 自动播放）
    scene [光影包] [lane]                受控场景取证（白地板 + 四色墙）

校验：
    analyze <会话目录>      校验诊断会话结构与数据完整性（结构错误退出 1）

全局：--pid <PID>           多个 Minecraft 窗口时指定目标
"""

import argparse
import ctypes
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
import unicodedata
from ctypes import wintypes
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / "fabric/run"
LOG = RUN / "logs/latest.log"
DIAG = RUN / "immersive_cinematics/diagnostics"
SESSIONS = DIAG / "sessions"
SHOTS = RUN / "lane-captures"
SCRIPTS = RUN / "immersive_cinematics/scripts"
DEVTOOLS_RUN = RUN / "dev-tools"
IRIS = RUN / "config/iris.properties"
MOD_CONFIG = RUN / "config/immersive_cinematics.json"

WM_KEYDOWN, WM_KEYUP, WM_CHAR = 0x100, 0x101, 0x102

VK = {
    "backspace": 0x08, "tab": 0x09, "enter": 0x0D, "esc": 0x1B, "space": 0x20,
    "end": 0x23, "home": 0x24, "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "delete": 0x2E, "slash": 0xBF,
    **{f"f{i}": 0x6F + i for i in range(1, 13)},
    **{c: ord(c.upper()) for c in "abcdefghijklmnopqrstuvwxyz"},
    **{c: ord(c) for c in "0123456789"},
}

WIN32 = sys.platform == "win32"
if WIN32:
    u = ctypes.windll.user32
    u.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    u.MapVirtualKeyW.argtypes = [wintypes.UINT, wintypes.UINT]


def die(msg):
    print(f"错误：{msg}", file=sys.stderr)
    sys.exit(1)


def rel(p):
    try:
        return str(Path(p).relative_to(ROOT))
    except ValueError:
        return str(p)


def _disp_width(s):
    """中文占两列，`doctor` 的对齐按显示宽度算。"""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in s)


# ---------------------------------------------------------------- 窗口与输入

def window_pid(win):
    pid = wintypes.DWORD()
    u.GetWindowThreadProcessId(wintypes.HWND(win._hWnd), ctypes.byref(pid))
    return pid.value


def all_windows():
    if not WIN32:
        die("控制游戏只支持 Windows（依赖 Win32 PostMessage）")
    import pyautogui
    return [w for w in pyautogui.getAllWindows() if "Minecraft" in (w.title or "")]


def find_window(pid=None):
    wins = all_windows()
    if pid is not None:
        wins = [w for w in wins if window_pid(w) == pid]
    if not wins:
        die("没找到 Minecraft 窗口" + (f"（pid={pid}）" if pid else "") + "：客户端起了吗？")
    if len(wins) > 1:
        listing = "\n".join(f"  pid={window_pid(w)} hwnd={w._hWnd} {w.title}" for w in wins)
        die(f"找到多个 Minecraft 窗口，用 --pid 指定：\n{listing}")
    return wins[0]


def send_key(win, vk, delay=0.01):
    scan = u.MapVirtualKeyW(vk, 0)
    u.PostMessageW(wintypes.HWND(win._hWnd), WM_KEYDOWN, vk, 1 | (scan << 16))
    if delay:
        time.sleep(delay)
    u.PostMessageW(wintypes.HWND(win._hWnd), WM_KEYUP, vk,
                   1 | (scan << 16) | (1 << 30) | (1 << 31))


def type_text(win, text):
    for ch in text:
        u.PostMessageW(wintypes.HWND(win._hWnd), WM_CHAR, ord(ch), 1)
        time.sleep(0.005)


def open_input(win, opener):
    send_key(win, opener)
    time.sleep(0.3)
    send_key(win, VK["end"])
    for _ in range(320):          # 清空上次残留
        send_key(win, VK["backspace"], delay=0)
    time.sleep(0.15)


def send_command(win, text):
    open_input(win, VK["slash"])
    type_text(win, "/" + text)
    send_key(win, VK["enter"])
    print(f"COMMAND {text}", flush=True)


def send_chat(win, text):
    open_input(win, ord("T"))
    type_text(win, text)
    send_key(win, VK["enter"])
    print(f"CHAT {text}", flush=True)


def take_shot(win, path=None):
    import pyautogui
    if win.width <= 0 or win.height <= 0:
        die("窗口尺寸为 0（最小化了？）")
    # 相对路径按游戏目录（fabric/run/）解析，符合直觉：shot lane-captures/x.png
    path = Path(path) if path else SHOTS / time.strftime("mc-%Y%m%d-%H%M%S.png")
    if not path.is_absolute():
        path = RUN / path
    # 窗口可能部分在屏幕外（左上负坐标），裁到屏幕内再截
    sw, sh = pyautogui.size()
    left, top = max(0, win.left), max(0, win.top)
    right, bottom = min(sw, win.left + win.width), min(sh, win.top + win.height)
    if right <= left or bottom <= top:
        die(f"窗口不在屏幕可见区域内（@{win.left},{win.top} {win.width}x{win.height}）")
    path.parent.mkdir(parents=True, exist_ok=True)
    pyautogui.screenshot(region=(left, top, right - left, bottom - top)).save(path)
    print(rel(path), flush=True)


def dispatch(win, name, rest):
    if name == "wait":
        time.sleep(float(rest))
    elif name == "cmd":
        send_command(win, rest)
    elif name == "say":
        send_chat(win, rest)
    elif name == "key":
        vk = VK.get(rest.lower())
        if vk is None:
            die(f"未知键名：{rest}（可用：{', '.join(sorted(VK))}）")
        send_key(win, vk)
    elif name == "play":
        send_command(win, f"icinematics play {rest}")
    elif name == "stop":
        send_command(win, "icinematics stop")
    elif name == "reload":
        send_command(win, "icinematics reload")
    elif name == "shot":
        take_shot(win, rest or None)
    else:
        die(f"场景里未知子命令：{name}")


def run_scenario(win, path):
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        name, _, rest = line.partition(" ")
        print(f"> {line}", flush=True)
        dispatch(win, name, rest.strip())


# ---------------------------------------------------------------- 日志与诊断

def tail_log(lines=40):
    if not LOG.exists():
        die(f"日志不存在：{rel(LOG)}")
    text = LOG.read_bytes().decode("gbk", errors="replace")   # 客户端日志是 GBK
    print("\n".join(text.splitlines()[-lines:]))


def session_dirs():
    if not SESSIONS.exists():
        return []
    return sorted((d for d in SESSIONS.iterdir() if d.is_dir()), key=lambda d: d.name)


def latest_session():
    dirs = session_dirs()
    if not dirs:
        die(f"没有诊断会话：{rel(SESSIONS)}")
    return dirs[-1]


PERF_KEYS = re.compile(
    r"valid|data sufficiency|lane rows|lane failure|game_render|lane_total"
    r"|composite_total|frame_interval")


def print_perf(session=None):
    d = Path(session) if session else latest_session()
    perf = d / "performance"
    if not perf.exists():
        die(f"{d.name} 没有 performance/（这次会话没跑 perf case）")
    print(f"# {d.name}")
    files = sorted(perf.glob("*summary.md"))
    if not files:
        print("（该会话没有性能 summary：manifest 里的 perf case 没跑，或会话被提前关闭）")
        return
    for f in files:
        print(f"\n## {f.name}")
        for line in f.read_text(encoding="utf-8").splitlines():
            if PERF_KEYS.search(line):
                print(line)


def list_sessions(n=8):
    dirs = session_dirs()[-n:]
    if not dirs:
        print(f"（没有诊断会话：{rel(SESSIONS)}）")
        return
    for d in dirs:
        perf = d / "performance"
        summaries = sorted(perf.glob("*summary.md")) if perf.exists() else []
        print(f"{d.name}  summaries={len(summaries)}"
              + (f"  [{', '.join(s.stem.replace('-summary', '') for s in summaries)}]" if summaries else ""))


# ---------------------------------------------------------------- 诊断会话校验

def _pct(arr, p):
    if not arr:
        return 0
    return arr[min(len(arr) - 1, max(0, math.ceil(p * len(arr)) - 1))]


def analyze(session):
    """移植自 analyze_session.mjs：结构错误退出 1，INSUFFICIENT 只告警。"""
    d = Path(session)
    if not d.is_dir():
        die(f"不是目录：{session}")
    errors, warns = [], []
    manifest_p, env_p, events_p = d / "manifest.json", d / "environment.json", d / "events.jsonl"
    images_d, perf_d = d / "images", d / "performance"
    for p in (manifest_p, env_p, events_p, images_d, perf_d):
        if not p.exists():
            errors.append(f"missing {p.name}")
    if errors:
        for e in errors:
            print("ERROR " + e)
        sys.exit(1)

    manifest = json.loads(manifest_p.read_text(encoding="utf-8"))
    env = json.loads(env_p.read_text(encoding="utf-8"))
    events = []
    for i, line in enumerate(events_p.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            errors.append(f"events.jsonl line {i}: bad JSON")

    images = sorted(p for p in images_d.iterdir() if p.suffix == ".png")
    perf_files = sorted(p.name for p in perf_d.iterdir())

    print(f"session: {d}")
    print(f"mc={env.get('minecraft_version', '?')} mod={env.get('mod_version', '?')} "
          f"fb={env.get('framebuffer_width')}x{env.get('framebuffer_height')} "
          f"renderDistance={env.get('effective_render_distance', '?')} "
          f"vsync={env.get('vsync', '?')} maxfps={env.get('max_fps', '?')} "
          f"shaderInUse={env.get('shader_pack_in_use')} shaderPack={env.get('shader_pack_name')}")
    print(f"events={len(events)} images={len(images)} perfFiles={len(perf_files)}")

    image_bytes = 0
    for p in images:
        size = p.stat().st_size
        image_bytes += size
        if size == 0:
            errors.append(f"zero-byte image {p.name}")
    print(f"image bytes: {image_bytes}")

    captured = {}
    for e in events:
        if e.get("event") == "checkpoint_capture":
            captured[e.get("case")] = captured.get(e.get("case"), 0) + 1
    for c in manifest.get("cases", []):
        if c.get("perf"):
            continue
        expected = len(c.get("checkpoints") or [])
        got = captured.get(c.get("id"), 0)
        if got < expected:
            warns.append(f"feature case {c['id']}: {got}/{expected} checkpoints requested "
                         f"(script may not have been played)")

    summary = []
    for name in perf_files:
        if not name.endswith("-frames.csv"):
            continue
        base = name[: -len("-frames.csv")]
        rows = [line.split(",") for line in
                (perf_d / name).read_text(encoding="utf-8").strip().splitlines()]
        header = rows[0]
        idx = {h: i for i, h in enumerate(header)}
        body = rows[1:]
        valid = [r for r in body if r[idx["valid"]] == "1"]
        reasons = {}
        for r in body:
            if r[idx["valid"]] != "1":
                reasons[r[idx["reason"]]] = reasons.get(r[idx["reason"]], 0) + 1

        def vals(col):
            out = []
            for r in valid:
                try:
                    v = float(r[idx[col]])
                except (ValueError, IndexError):
                    continue
                if math.isfinite(v) and v > 0:
                    out.append(v)
            return sorted(out)

        summary_md = perf_d / (base + "-summary.md")
        text = summary_md.read_text(encoding="utf-8") if summary_md.exists() else ""
        insufficient = "INSUFFICIENT" in text
        if not text:
            errors.append(f"missing {base}-summary.md")
        if len(valid) < 600:
            warns.append(f"{base}: {len(valid)}/600 valid frames"
                         + (" (INSUFFICIENT recorded)" if insufficient else ""))
        if not insufficient and len(valid) < 600:
            errors.append(f"{base}: summary claims complete but <600 valid frames")
        summary.append({
            "case": base, "rows": len(body), "valid": len(valid), "reasons": reasons,
            "game_p50": _pct(vals("game_render_ns"), 0.5),
            "game_p95": _pct(vals("game_render_ns"), 0.95),
            "lane_p50": _pct(vals("lane_total_ns"), 0.5),
            "comp_p50": _pct(vals("composite_total_ns"), 0.5),
            "interval_p50": _pct(vals("frame_interval_ns"), 0.5),
            "insufficient": insufficient,
        })

    print("\nperf runs:")
    for s in summary:
        print(f"  {s['case']}: rows={s['rows']} valid={s['valid']}"
              f" game_p50={s['game_p50']:.0f}ns game_p95={s['game_p95']:.0f}ns"
              f" lane_p50={s['lane_p50']:.0f}ns composite_p50={s['comp_p50']:.0f}ns"
              f" interval_p50={s['interval_p50']:.0f}ns"
              f" invalid={json.dumps(s['reasons'], ensure_ascii=False)}"
              + (" INSUFFICIENT" if s["insufficient"] else ""))

    for c in manifest.get("cases", []):
        if not c.get("perf"):
            continue
        prefix = f"{c['id']}-run{c.get('run', 1)}"
        if not any(f.startswith(prefix) for f in perf_files):
            warns.append(f"perf case {c['id']} run {c.get('run', 1)}: no output yet")

    for w in warns:
        print("WARN " + w)
    if errors:
        for e in errors:
            print("ERROR " + e)
        print(f"FAILED: {len(errors)} structural error(s)")
        sys.exit(1)
    print("OK: session structurally valid (insufficient user runs are warnings, not failures)")


# ---------------------------------------------------------------- 启动与取证

def gradle_prefix():
    for candidate in ("sh", "bash"):
        if shutil.which(candidate):
            return [candidate, "gradlew"]
    bat = ROOT / "gradlew.bat"
    if bat.exists():
        return [str(bat)]
    die("找不到 gradlew / sh / bash")


def launch_client(world, extra_args, env_extra=None):
    cmd = gradle_prefix() + list(extra_args) + [
        ":fabric:runClient", f"--args=--quickPlaySingleplayer {world}"]
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    print(f"[client] world={world} capture={env.get('ICINEMATICS_CAPTURE', '0')}")
    print(f"[client] 日志：{rel(LOG)}（用 `devtools.py tail` 看，自动 GBK 解码）")
    return subprocess.run(cmd, cwd=ROOT, env=env)


def cmd_client(world, extra_args):
    return launch_client(world, extra_args)


def cmd_shadow(pack, lane, world, capture=False):
    (SCRIPTS / "fqueue").mkdir(parents=True, exist_ok=True)
    IRIS.parent.mkdir(parents=True, exist_ok=True)

    ab = SCRIPTS / "fqueue/test_ab_split_scale.json"
    if not ab.exists():
        src = Path("D:/mc/.minecraft/versions/ImmersiveCinematics/immersive_cinematics"
                   "/scripts/fqueue/test_ab_split_scale.json")
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
        f"shaderPack={pack}\nenableShaders=true\n", encoding="utf-8")

    print(f"[shadow] pack={pack} lane={lane} world={world} capture={capture}")
    return launch_client(world, [], {"ICINEMATICS_CAPTURE": "1" if capture else "0"})


def cmd_scene(pack, lane):
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
        return cmd_shadow(pack, lane, "新的世界", capture=True)
    finally:
        if moved:
            shutil.move(str(backup), str(setup))


# ---------------------------------------------------------------- 自检

def doctor():
    rows = []

    wins = all_windows() if WIN32 else []
    if wins:
        for w in wins:
            rows.append(("客户端窗口", f"pid={window_pid(w)} {w.width}x{w.height} {w.title}"))
    else:
        rows.append(("客户端窗口", "未运行"))

    if LOG.exists():
        age = int(time.time() - LOG.stat().st_mtime)
        rows.append(("latest.log", f"{rel(LOG)}（{age}s 前更新，{LOG.stat().st_size} 字节）"))
    else:
        rows.append(("latest.log", "不存在"))

    dirs = session_dirs()
    if dirs:
        d = dirs[-1]
        perf = d / "performance"
        n = len(list(perf.glob("*summary.md"))) if perf.exists() else 0
        rows.append(("最新诊断会话", f"{d.name}（summaries={n}）"))
    else:
        rows.append(("最新诊断会话", "无"))

    if MOD_CONFIG.exists():
        try:
            cfg = json.loads(MOD_CONFIG.read_text(encoding="utf-8"))
            rows.append(("debugLogging", str(cfg.get("debugLogging")).lower()))
        except json.JSONDecodeError:
            rows.append(("debugLogging", "配置文件解析失败"))
    else:
        rows.append(("debugLogging", "配置文件不存在"))

    if IRIS.exists():
        pack = next((ln.split("=", 1)[1].strip() for ln in
                     IRIS.read_text(encoding="utf-8").splitlines()
                     if ln.startswith("shaderPack=")), "?")
        rows.append(("光影包", pack))
    else:
        rows.append(("光影包", "iris.properties 不存在"))

    opts = RUN / "options.txt"
    if opts.exists():
        text = opts.read_text(encoding="utf-8", errors="replace")
        vsync = "enableVsync:false" in text
        fps = next((ln.split(":", 1)[1] for ln in text.splitlines()
                    if ln.startswith("maxFps:")), "?")
        rows.append(("vsync / maxFps", f"{'off' if vsync else 'ON（性能采样要关）'} / {fps}"))

    width = max(_disp_width(k) for k, _ in rows)
    for k, v in rows:
        print(f"{k}{' ' * (width - _disp_width(k) + 2)}{v}")


# ---------------------------------------------------------------- 入口

def main():
    ap = argparse.ArgumentParser(
        description="ImmersiveCinematics 本地测试工具包",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="子命令列表见模块 docstring：python devtools.py --help 之后看 `--help` 正文。")
    ap.add_argument("--pid", type=int, default=None, help="多个 Minecraft 窗口时指定目标")
    sub = ap.add_subparsers(dest="action", required=True)

    sub.add_parser("list", help="列出 Minecraft 窗口")
    for name, helptext in (("cmd", "聊天栏执行命令"), ("say", "聊天栏发消息")):
        p = sub.add_parser(name, help=helptext)
        p.add_argument("text")
    p = sub.add_parser("key", help="按键")
    p.add_argument("key")
    p = sub.add_parser("play", help="播放脚本")
    p.add_argument("script_id")
    sub.add_parser("stop", help="停止播放")
    sub.add_parser("reload", help="重载脚本")
    p = sub.add_parser("shot", help="截游戏窗口")
    p.add_argument("path", nargs="?")
    p = sub.add_parser("wait", help="等待")
    p.add_argument("seconds", type=float)
    p = sub.add_parser("run", help="执行场景文件")
    p.add_argument("scenario")

    p = sub.add_parser("tail", help="日志尾部（GBK 解码）")
    p.add_argument("lines", nargs="?", type=int, default=40)
    p = sub.add_parser("perf", help="性能摘要")
    p.add_argument("session", nargs="?")
    p = sub.add_parser("sessions", help="列出诊断会话")
    p.add_argument("count", nargs="?", type=int, default=8)
    sub.add_parser("doctor", help="环境自检")

    p = sub.add_parser("client", help="启动客户端")
    p.add_argument("world", nargs="?", default="新的世界")
    p.add_argument("--offline", action="store_true", help="gradle --offline")
    p = sub.add_parser("shadow", help="A/B 取证")
    p.add_argument("pack", nargs="?", default="ComplementaryUnbound_r5.9.3.zip")
    p.add_argument("lane", nargs="?", default="cam0")
    p.add_argument("world", nargs="?", default="新的世界")
    p.add_argument("--capture", action="store_true", help="开 ICINEMATICS_CAPTURE 出图")
    p = sub.add_parser("scene", help="受控场景取证")
    p.add_argument("pack", nargs="?", default="MakeUp-UltraFast-9.5f.zip")
    p.add_argument("lane", nargs="?", default="cam0")

    p = sub.add_parser("analyze", help="校验诊断会话")
    p.add_argument("session", nargs="?")

    args = ap.parse_args()
    a = args.action

    if a == "list":
        wins = all_windows()
        if not wins:
            print("（没有 Minecraft 窗口）")
        for w in wins:
            print(f"pid={window_pid(w):>6} hwnd={w._hWnd:>10} "
                  f"{w.width}x{w.height} @({w.left},{w.top})  {w.title}")
        return 0
    if a == "tail":
        tail_log(args.lines)
        return 0
    if a == "perf":
        print_perf(args.session)
        return 0
    if a == "sessions":
        list_sessions(args.count)
        return 0
    if a == "doctor":
        doctor()
        return 0
    if a == "analyze":
        analyze(args.session or latest_session())
        return 0
    if a == "client":
        return cmd_client(args.world, ["--offline"] if args.offline else []).returncode
    if a == "shadow":
        return cmd_shadow(args.pack, args.lane, args.world, capture=args.capture).returncode
    if a == "scene":
        return cmd_scene(args.pack, args.lane).returncode

    win = find_window(args.pid)
    if a == "wait":
        time.sleep(args.seconds)
    elif a == "run":
        run_scenario(win, args.scenario)
    else:
        payload = (getattr(args, "text", None) or getattr(args, "key", None)
                   or getattr(args, "script_id", None) or getattr(args, "path", None) or "")
        dispatch(win, a, payload)
    return 0


if __name__ == "__main__":
    sys.exit(main())
