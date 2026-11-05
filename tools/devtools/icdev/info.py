"""环境自检：客户端窗口 / 日志 / 关键配置 / 当前所在世界。

两个入口都不允许抛异常：任何子项失败只置 ``None`` 并追加一条 ``issues``。
"""

from __future__ import annotations

import gzip
import json
import logging
import re
import time
from pathlib import Path, PurePosixPath

from .paths import REPO_ROOT

logger = logging.getLogger("icdev.info")

# ---------------------------------------------------------------- 路径

def _run_dir(project_root: Path) -> Path:
    return Path(project_root) / "fabric" / "run"


def _rel(project_root: Path, path: Path) -> str:
    try:
        return PurePosixPath(Path(path).resolve().relative_to(Path(project_root).resolve())).as_posix()
    except (ValueError, OSError):
        return str(path)


def _read_text(path: Path) -> str | None:
    """按 UTF-8 读，失败退 GBK；都失败返回 None（不抛）。"""
    try:
        raw = path.read_bytes()
    except OSError:
        return None
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1", errors="replace")


# ---------------------------------------------------------------- 窗口

def _attr(obj, *names):
    for name in names:
        if isinstance(obj, dict):
            if name in obj:
                return obj[name]
        else:
            value = getattr(obj, name, None)
            if value is not None:
                return value
    return None


def _window_entry(win) -> dict | None:
    entry = {
        "pid": _attr(win, "pid", "process_id"),
        "w": _attr(win, "w", "width"),
        "h": _attr(win, "h", "height"),
        "title": _attr(win, "title", "window_title", "caption"),
    }
    if all(v is None for v in entry.values()):
        return None
    return entry


# ---------------------------------------------------------------- doctor

def doctor(project_root: Path = REPO_ROOT) -> dict:
    project_root = Path(project_root)
    run = _run_dir(project_root)
    issues: list[str] = []

    windows: list[dict] = []
    try:
        from .win32 import list_minecraft_windows  # 延迟导入：缺 pywin32 也不影响本模块导入
        for win in list_minecraft_windows() or []:
            entry = _window_entry(win)
            if entry is None:
                issues.append("窗口枚举返回了无法识别的条目")
                continue
            windows.append(entry)
    except Exception as exc:  # noqa: BLE001 - 自检必须容错
        issues.append(f"窗口枚举失败：{exc}")

    log_path = run / "logs" / "latest.log"
    log: dict = {"path": str(log_path), "ageSec": None, "size": None}
    try:
        if log_path.is_file():
            st = log_path.stat()
            log["ageSec"] = max(0, int(time.time() - st.st_mtime))
            log["size"] = st.st_size
        else:
            issues.append(f"latest.log 不存在：{_rel(project_root, log_path)}")
    except OSError as exc:
        issues.append(f"latest.log 读取失败：{exc}")

    latest_session: str | None = None
    sessions_dir = run / "immersive_cinematics" / "diagnostics" / "sessions"
    try:
        if sessions_dir.is_dir():
            dirs = sorted((d for d in sessions_dir.iterdir() if d.is_dir()), key=lambda d: d.name)
            if dirs:
                latest_session = dirs[-1].name
    except OSError as exc:
        issues.append(f"诊断会话读取失败：{exc}")

    debug_logging: bool | None = None
    mod_cfg = run / "config" / "immersive_cinematics.json"
    try:
        if mod_cfg.is_file():
            cfg = json.loads(mod_cfg.read_text(encoding="utf-8"))
            value = cfg.get("debugLogging") if isinstance(cfg, dict) else None
            debug_logging = bool(value) if isinstance(value, bool) else None
            if not isinstance(value, bool):
                issues.append("debugLogging 不是布尔值")
        else:
            issues.append(f"mod 配置不存在：{_rel(project_root, mod_cfg)}")
    except (OSError, json.JSONDecodeError) as exc:
        issues.append(f"mod 配置解析失败：{exc}")

    shaderpack: str | None = None
    iris = run / "config" / "iris.properties"
    try:
        text = _read_text(iris) if iris.is_file() else None
        if text is None:
            issues.append(f"iris.properties 不存在：{_rel(project_root, iris)}")
        else:
            for line in text.splitlines():
                if line.startswith("shaderPack="):
                    shaderpack = line.split("=", 1)[1].strip()
    except OSError as exc:
        issues.append(f"iris.properties 读取失败：{exc}")

    vsync: bool | None = None
    max_fps: int | None = None
    options = run / "options.txt"
    try:
        text = _read_text(options) if options.is_file() else None
        if text is None:
            issues.append(f"options.txt 不存在：{_rel(project_root, options)}")
        else:
            for line in text.splitlines():
                if line.startswith("enableVsync:"):
                    raw = line.split(":", 1)[1].strip().lower()
                    vsync = raw == "true"
                elif line.startswith("maxFps:"):
                    raw = line.split(":", 1)[1].strip()
                    try:
                        max_fps = int(raw)
                    except ValueError:
                        issues.append(f"maxFps 不是整数：{raw!r}")
    except OSError as exc:
        issues.append(f"options.txt 读取失败：{exc}")

    return {
        "windows": windows,
        "log": log,
        "latestSession": latest_session,
        "debugLogging": debug_logging,
        "shaderpack": shaderpack,
        "vsync": vsync,
        "maxFps": max_fps,
        "issues": issues,
    }


# ---------------------------------------------------------------- where

_RE_LOGIN = re.compile(r"logged in with entity id \d+ at \(([^)]+)\)")
_RE_DIMCHANGE = re.compile(r"dimension change: [^=]*=> (\S+)")
_RE_SAVE_LEVEL = re.compile(r"Saving chunks for level 'ServerLevel\[([^\]]+)\]'/(\S+)")
_RE_LOAD_LEVEL = re.compile(r"(?:Loading level|Loading world) '([^']+)'")
_RE_START_REGION = re.compile(r"Preparing start region for dimension (\S+)")


def _parse_pos(raw: str) -> list[float] | None:
    parts = [p.strip() for p in raw.split(",")]
    if len(parts) != 3:
        return None
    try:
        return [float(p) for p in parts]
    except ValueError:
        return None


def _scan_level_dat(level: Path) -> tuple[str | None, str | None]:
    """从 level.dat 抠 (LevelName, 世界类型)。只做浅扫描，失败返回 (None, None)。"""
    try:
        raw = gzip.decompress(level.read_bytes())
    except (OSError, EOFError, gzip.BadGzipFile) as exc:
        logger.debug("level.dat 解压失败 %s: %s", level, exc)
        return (None, None)

    name = None
    try:
        name = _nbt_string(raw, "LevelName")
    except Exception:  # noqa: BLE001 - 浅扫描，失败就算了
        name = None

    world_type = None
    # 生成器名在 NBT 里是字符串 minecraft:<type>
    for candidate in ("flat", "debug", "noise"):
        if f"minecraft:{candidate}".encode() in raw:
            world_type = "noise(default)" if candidate == "noise" else candidate
            break
    return (name, world_type)


def _nbt_string(raw: bytes, key: str) -> str | None:
    """在未解析的 NBT 里找 TAG_String 的同名载荷（够用即可）。"""
    needle = key.encode("utf-8")
    start = 0
    while True:
        idx = raw.find(needle, start)
        if idx < 0:
            return None
        pos = idx + len(needle)
        if pos + 2 <= len(raw):
            length = int.from_bytes(raw[pos:pos + 2], "big")
            if 0 < length < 4096 and pos + 2 + length <= len(raw):
                try:
                    return raw[pos + 2:pos + 2 + length].decode("utf-8")
                except UnicodeDecodeError:
                    pass
        start = idx + 1


def _latest_save(saves: Path) -> Path | None:
    try:
        dirs = [d for d in saves.iterdir() if d.is_dir()]
    except OSError:
        return None
    if not dirs:
        return None

    def mtime(d: Path) -> float:
        level = d / "level.dat"
        try:
            return level.stat().st_mtime
        except OSError:
            return 0.0

    return max(dirs, key=mtime)


def where(project_root: Path = REPO_ROOT) -> dict:
    project_root = Path(project_root)
    run = _run_dir(project_root)
    issues: list[str] = []

    world: str | None = None
    world_type: str | None = None
    pos: list[float] | None = None
    dimension: str | None = None
    source = "log"

    log_path = run / "logs" / "latest.log"
    text = _read_text(log_path) if log_path.is_file() else None
    if text is None:
        issues.append(f"latest.log 不存在或不可读：{_rel(project_root, log_path)}")
    else:
        login: tuple[int, str] | None = None
        for m in _RE_LOGIN.finditer(text):
            if login is None or m.start() >= login[0]:
                login = (m.start(), m.group(1))
        if login is not None:
            pos = _parse_pos(login[1])
            if pos is None:
                issues.append(f"登录坐标无法解析：{login[1]!r}")

        # 世界名与维度取「日志中出现位置最靠后」的那条，避免旧会话覆盖新会话
        dim_at: int = -1
        world_at: int = -1
        for m in _RE_DIMCHANGE.finditer(text):
            if m.start() >= dim_at:
                dim_at, dimension = m.start(), m.group(1)
        for m in _RE_START_REGION.finditer(text):
            if m.start() >= dim_at:
                dim_at, dimension = m.start(), m.group(1)
        for m in _RE_SAVE_LEVEL.finditer(text):
            if m.start() >= dim_at:
                dim_at, dimension = m.start(), m.group(2)
            if m.start() >= world_at:
                world_at, world = m.start(), m.group(1)
        for m in _RE_LOAD_LEVEL.finditer(text):
            if m.start() >= world_at:
                world_at, world = m.start(), m.group(1)

        if world is None:
            issues.append("latest.log 里没有世界加载/传送行")

    saves = run / "saves"
    save_dir: Path | None = None
    if world:
        candidate = saves / world
        if candidate.is_dir():
            save_dir = candidate
        else:
            issues.append(f"日志里的世界没有对应存档：{_rel(project_root, candidate)}")
    else:
        save_dir = _latest_save(saves)
        if save_dir is not None:
            world = save_dir.name
            source = "level.dat"
        else:
            issues.append(f"没有可用存档：{_rel(project_root, saves)}")

    if save_dir is not None:
        level = save_dir / "level.dat"
        if level.is_file():
            level_name, scan_type = _scan_level_dat(level)
            if world is None and level_name:
                world = level_name
                source = "level.dat"
            if scan_type:
                world_type = scan_type
            else:
                issues.append(f"level.dat 无法判定世界类型：{_rel(project_root, level)}")
        else:
            issues.append(f"level.dat 不存在：{_rel(project_root, level)}")

    if world is None:
        source = "level.dat"

    return {
        "world": world,
        "worldType": world_type,
        "pos": pos,
        "dimension": dimension,
        "source": source,
        "issues": issues,
    }
