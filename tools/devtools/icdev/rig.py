"""装置（rig）状态机：一个 MCP 会话一个装置。

职责：
- ``up`` / ``down``：起停服务端与多个客户端；每个 spawn 的子进程进 Job Object，
  并按 ``server`` / ``client:<name>`` 登记所有权。
- 就绪断言：服务端 ``Done (...)!``、客户端 ``Loaded N advancements`` 或服务端日志
  ``<name> joined the game``，再加窗口出现。
- 输入：聊天栏命令、按键、长按；服务端命令走 RCON。
- 截图：游戏内 F2，从日志解析文件名，可选复制/裁剪。
- 日志：tail / 正则等待。
- 状态：进程、玩家列表、当前脚本。

等待循环每 0.2 s 检查一次 ``cancelled``，取消时停掉已起的一切。
本模块不依赖 server.py；导入即安全（不碰游戏进程）。
"""

from __future__ import annotations

import codecs
import ctypes
import logging
import os
import re
import shutil
import struct
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from . import config as mod_config
from . import launch, logs, process, rcon, win32
from .errors import (
    E_ALREADY_UP,
    E_INTERNAL,
    E_INVALID_ARGUMENT,
    E_NOT_FOUND,
    E_NOT_READY,
    E_NO_WINDOW,
    E_TIMEOUT,
    E_UNSUPPORTED,
    IcdError,
)
from .paths import (
    DEFAULT_CLIENT_DIRS,
    DEFAULT_GAME_PORT,
    DEFAULT_RCON_PASSWORD,
    DEFAULT_RCON_PORT,
    DEFAULT_SERVER_DIR,
    ICDEV_HOME,
)

log = logging.getLogger("icdev.rig")

# ---------------------------------------------------------------- 常量

#: 服务端「Done (x.xxxs)! For help」就绪行
SERVER_READY_RE = r"Done \([0-9.]+s\)! For help"

#: 客户端「Loaded 123 advancements」就绪行
CLIENT_READY_RE = r"Loaded \d+ advancements"

#: 服务端「<name> joined the game」
JOINED_TEMPLATE = r"{name}\s+joined the game"

#: 截图日志（英文/中文各版本）
SHOT_RE = re.compile(r"(?:Saved screenshot as|截图保存为)\s+(\S+)")

#: 当前播放脚本
SCRIPT_RE = re.compile(r"Playing script from server:\s+(\S+)")

#: RCON list 输出
LIST_RE = re.compile(r"(?:players online|在线玩家)\s*[:：]\s*(.*)$")

SERVER_TIMEOUT_S = 120.0
CLIENT_READY_TIMEOUT_S = 180.0
WINDOW_TIMEOUT_S = 60.0
SHOT_TIMEOUT_S = 5.0
POLL_INTERVAL_S = 0.2

WAIT_BUFFER_MAX = 1_000_000

#: RCON 四项：工具依赖固定值，启动时强制覆盖
RCON_FORCED_KEYS = {
    "server-port",
    "enable-rcon",
    "rcon.port",
    "rcon.password",
}

SERVER_PROPERTIES_DEFAULTS: tuple[tuple[str, str], ...] = (
    ("online-mode", "false"),
    ("server-port", str(DEFAULT_GAME_PORT)),
    ("enable-rcon", "true"),
    ("rcon.port", str(DEFAULT_RCON_PORT)),
    ("rcon.password", DEFAULT_RCON_PASSWORD),
    ("level-name", "world"),
    ("gamemode", "creative"),
)


class _Cancelled(Exception):
    """内部信号：等待期间收到取消。"""


# ---------------------------------------------------------------- 小工具


def _now() -> float:
    return time.monotonic()


def _last_lines(text: str, count: int) -> str:
    rows = text.splitlines()
    return "\n".join(rows[-count:]) if rows else ""


def _compile(pattern: str) -> re.Pattern[str]:
    if not isinstance(pattern, str) or not pattern:
        raise IcdError(E_INVALID_ARGUMENT, "pattern 必须是非空字符串")
    try:
        return re.compile(pattern)
    except re.error as exc:
        raise IcdError(E_INVALID_ARGUMENT, f"正则不合法：{pattern}", detail=str(exc)) from exc


def _pid_alive(pid: int | None) -> bool:
    """进程是否仍存活（只查句柄，不发信号）。"""
    try:
        value = int(pid) if pid is not None else 0
    except (TypeError, ValueError):
        return False
    if value <= 0:
        return False
    if sys.platform == "win32":
        kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
        handle = kernel32.OpenProcess(0x00100000, False, value)  # SYNCHRONIZE
        if not handle:
            return False
        try:
            return kernel32.WaitForSingleObject(handle, 0) == 0x00000102  # WAIT_TIMEOUT
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(value, 0)
        return True
    except OSError:
        return False


class _LogFollower:
    """增量日志读取：只返回构造之后新增的字节（文件被截断时自动重头读）。"""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self.offset = self._size()
        self._decoder = codecs.getincrementaldecoder("utf-8")("replace")

    def _size(self) -> int:
        try:
            return self.path.stat().st_size
        except OSError:
            return 0

    def read_new(self) -> str:
        size = self._size()
        if size < self.offset:  # 日志被截断/轮转
            self.offset = 0
            self._decoder.reset()
        if size <= self.offset:
            return ""
        try:
            with self.path.open("rb") as fh:
                fh.seek(self.offset)
                chunk = fh.read(size - self.offset)
        except OSError:
            return ""
        self.offset = size
        return self._decoder.decode(chunk)

    def tail(self, lines: int = 40) -> str:
        return logs.tail(self.path, lines)


# ---------------------------------------------------------------- 文件准备


def _patch_properties(path: Path, entries: list[tuple[str, str]], force: set[str]) -> None:
    """按 Java properties 的 ``key=value`` 形式补齐缺失键；``force`` 中的键强制覆盖。"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
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
        if key in force and key in wanted:
            out.append(f"{key}={wanted[key]}")
        else:
            out.append(line)

    missing = [(key, value) for key, value in entries if key not in seen]
    if missing:
        if out and out[-1].strip():
            out.append("")
        out.extend(f"{key}={value}" for key, value in missing)

    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def _write_eula(server_dir: Path) -> None:
    server_dir.mkdir(parents=True, exist_ok=True)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")


def _prepare_server(server_dir: Path, world: str) -> None:
    server_dir.mkdir(parents=True, exist_ok=True)
    (server_dir / "logs").mkdir(parents=True, exist_ok=True)
    _write_eula(server_dir)

    entries = [(key, world if key == "level-name" else value) for key, value in SERVER_PROPERTIES_DEFAULTS]
    _patch_properties(server_dir / "server.properties", entries, RCON_FORCED_KEYS)


def _ensure_options(run_dir: Path) -> None:
    path = run_dir / "options.txt"
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        text = ""
    if re.search(r"(?m)^\s*pauseOnLostFocus\s*:", text):
        return
    if text and not text.endswith("\n"):
        text += "\n"
    path.write_text(text + "pauseOnLostFocus:false\n", encoding="utf-8")


def _write_iris(run_dir: Path, shader: str | None) -> None:
    path = run_dir / "config" / "iris.properties"
    if shader:
        entries = [("shaderPack", str(shader)), ("enableShaders", "true")]
    else:
        entries = [("enableShaders", "false")]
    _patch_properties(path, entries, {key for key, _ in entries})


def _prepare_client(run_dir: Path, shader: str | None, debug: bool) -> None:
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "logs").mkdir(parents=True, exist_ok=True)
    _ensure_options(run_dir)
    _write_iris(run_dir, shader)
    if debug:
        mod_config.set(run_dir, "debugLogging", True)


def _resolve_client_dirs(clients: list[str], dirs: dict | None) -> list[Path]:
    raw = (dirs or {}).get("clients") or []
    if isinstance(raw, (str, os.PathLike)):
        raw = [raw]
    custom = [Path(p) for p in raw]
    resolved: list[Path] = []
    for index in range(len(clients)):
        if index < len(custom):
            resolved.append(custom[index])
        elif index < len(DEFAULT_CLIENT_DIRS):
            resolved.append(Path(DEFAULT_CLIENT_DIRS[index]))
        else:
            resolved.append(ICDEV_HOME / f"client{index + 1}")
    return resolved


# ---------------------------------------------------------------- 截图辅助


def _shot_files(directory: Path) -> list[Path]:
    if not directory.is_dir():
        return []
    return sorted((p for p in directory.glob("*.png") if p.is_file()), key=lambda p: p.name)


def _await_file(path: Path, timeout_s: float) -> Path | None:
    deadline = _now() + max(0.0, timeout_s)
    while True:
        try:
            if path.is_file() and path.stat().st_size > 0:
                return path
        except OSError:
            pass
        if _now() >= deadline:
            return None
        time.sleep(0.1)


def _png_size(path: Path) -> tuple[int, int]:
    try:
        with Path(path).open("rb") as fh:
            head = fh.read(24)
    except OSError as exc:
        raise IcdError(E_INVALID_ARGUMENT, f"读取截图失败：{path}", detail=str(exc)) from exc
    if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n":
        raise IcdError(E_INVALID_ARGUMENT, f"不是 PNG 文件：{path}")
    width, height = struct.unpack(">II", head[16:24])
    return int(width), int(height)


def _image_size(path: Path) -> tuple[int, int]:
    try:
        from PIL import Image  # type: ignore[import-not-found]
    except ImportError:
        return _png_size(path)
    try:
        with Image.open(path) as image:
            return int(image.size[0]), int(image.size[1])
    except Exception:  # noqa: BLE001 - 损坏/非图片时退回 PNG 头
        return _png_size(path)


def _region_box(region: dict) -> tuple[int, int, int, int]:
    if not isinstance(region, dict):
        raise IcdError(E_INVALID_ARGUMENT, "region 必须是 {{x,y,width,height}} 对象")
    try:
        x = int(region.get("x", 0))
        y = int(region.get("y", 0))
        width = int(region.get("width", region.get("w", 0)))
        height = int(region.get("height", region.get("h", 0)))
    except (TypeError, ValueError) as exc:
        raise IcdError(E_INVALID_ARGUMENT, f"region 数值不合法：{region}", detail=str(exc)) from exc
    if width <= 0 or height <= 0:
        raise IcdError(E_INVALID_ARGUMENT, f"region 宽高必须为正：{region}")
    return x, y, x + width, y + height


def _crop_image(path: Path, region: dict) -> Path:
    try:
        from PIL import Image  # type: ignore[import-not-found]
    except ImportError as exc:
        raise IcdError(E_UNSUPPORTED, "裁剪截图需要 Pillow") from exc
    box = _region_box(region)
    try:
        with Image.open(path) as image:
            cropped = image.crop(box)
            cropped.save(path)
    except IcdError:
        raise
    except Exception as exc:  # noqa: BLE001
        raise IcdError(E_INVALID_ARGUMENT, f"裁剪截图失败：{path}", detail=str(exc)) from exc
    return path


# ---------------------------------------------------------------- 数据模型


@dataclass
class _Client:
    name: str
    run_dir: Path
    pid: Optional[int] = None
    ready: bool = False


class Rig:
    """一个装置：一个服务端 + N 个客户端。"""

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._state = "absent"
        self._server: dict | None = None
        self._clients: list[_Client] = []
        self._job = None  # win32.JobObject | None
        self._last_error: dict | None = None
        # 异步启动（MCP 请求有客户端超时，rig.up 不能同步阻塞）
        self._cancel_event = threading.Event()
        self._start_thread: threading.Thread | None = None
        self._progress_value = 0.0
        self._progress_stage = ""

    # ------------------------------------------------------------ 公共 API

    def up(
        self,
        *,
        world: str,
        clients: list[str],
        server: bool = True,
        shader: str | None = None,
        debug: bool = False,
        dirs: dict | None = None,
        progress: Callable[[float, str], None] | None = None,
        cancelled: Callable[[], bool] | None = None,
        wait: bool = True,
    ) -> dict:
        """起整个装置；失败时清场并抛 ``IcdError``。

        ``wait=False`` 时立刻返回 ``{"state": "starting"}``，实际启动在后台线程里跑，
        调用方用 :meth:`status` 轮询（MCP 客户端对单次请求有 30 s 上限，而启动要 ~90 s）。
        """
        if not isinstance(world, str) or not world.strip():
            raise IcdError(E_INVALID_ARGUMENT, "world 必须是非空字符串")
        world = world.strip()
        if not isinstance(clients, (list, tuple)):
            raise IcdError(E_INVALID_ARGUMENT, "clients 必须是字符串列表")
        if dirs is not None and not isinstance(dirs, dict):
            raise IcdError(E_INVALID_ARGUMENT, "dirs 必须是对象（或 None）")
        names = [str(item) for item in clients]
        if any(not name.strip() for name in names):
            raise IcdError(E_INVALID_ARGUMENT, "clients 里有空名字", detail=names)
        if len(set(names)) != len(names):
            raise IcdError(E_INVALID_ARGUMENT, "clients 里有重名", detail=names)
        if shader is not None:
            shader = str(shader).strip() or None

        with self._lock:
            if self._state != "absent":
                raise IcdError(E_ALREADY_UP, "装置已经在运行", detail=self.status())
            self._state = "starting"
            self._last_error = None
            self._server = None
            self._clients = []
            self._cancel_event.clear()
            self._progress_value = 0.0
            self._progress_stage = "准备启动"

        def _report(value: float, stage: str) -> None:
            self._progress_value = float(value)
            self._progress_stage = stage
            if progress is not None:
                progress(value, stage)

        def _is_cancelled() -> bool:
            if self._cancel_event.is_set():
                return True
            return bool(cancelled()) if cancelled is not None else False

        started = _now()
        kwargs = dict(
            world=world,
            names=names,
            server=server,
            shader=shader,
            debug=debug,
            dirs=dirs,
            progress=_report,
            cancelled=_is_cancelled,
            started=started,
        )

        if not wait:
            thread = threading.Thread(target=self._up_worker, kwargs=kwargs,
                                      name="icdev-rig-up", daemon=True)
            with self._lock:
                self._start_thread = thread
            thread.start()
            return {"state": "starting", "world": world, "clients": names}

        return self._up_worker(**kwargs)

    def _up_worker(self, **kwargs) -> dict:
        """同步启动体（异步模式下在后台线程里执行）。"""
        try:
            return self._start(
                **kwargs,
            )
        except _Cancelled:
            error = IcdError(E_TIMEOUT, "已取消")
            self._remember(error)
            self.down(force=True)
            raise error from None
        except IcdError as exc:
            self._remember(exc)
            self.down(force=True)
            raise
        except Exception as exc:  # noqa: BLE001 - 非预期异常也要清场
            error = IcdError(E_INTERNAL, f"装置启动失败：{exc}")
            self._remember(error)
            self.down(force=True)
            raise error from exc

    def down(self, force: bool = False) -> dict:
        """停掉本会话登记的所有 server/client 进程；失败的 pid 只记 warning。"""
        stopped: list[dict] = []
        warnings: list[str] = []

        # 取消并等待后台启动线程（否则它可能在我们杀完进程后又拉起一个）
        self._cancel_event.set()
        thread = self._start_thread
        if thread is not None and thread.is_alive() and thread is not threading.current_thread():
            thread.join(timeout=15.0)
        with self._lock:
            self._start_thread = None

        with self._lock:
            if self._state != "absent":
                self._state = "stopping"

        owned = [
            item
            for item in process.owned_processes()
            if item.role == "server" or item.role.startswith("client:")
        ]
        for item in owned:
            try:
                process.terminate(item.pid)
                stopped.append({"role": item.role, "pid": item.pid})
            except IcdError as exc:
                warnings.append(f"{item.role}#{item.pid}: {exc}")
                log.warning("终止 %s（pid=%s）失败：%s", item.role, item.pid, exc)
            finally:
                process.forget_owned(item.pid)

        with self._lock:
            job = self._job
            self._job = None
        if job is not None:
            try:
                job.close()
            except Exception as exc:  # noqa: BLE001 - 清场不允许抛
                warnings.append(f"job.close: {exc}")
                log.warning("关闭 Job Object 失败：%s", exc)

        try:
            rcon.close_all()
        except Exception as exc:  # noqa: BLE001
            warnings.append(f"rcon.close_all: {exc}")
            log.warning("关闭 RCON 连接失败：%s", exc)

        with self._lock:
            self._state = "absent"
            self._server = None
            self._clients = []

        log.info("装置已停止（force=%s，终止 %d 个进程）", force, len(stopped))
        return {"state": "absent", "stopped": stopped, "warnings": warnings, "forced": bool(force)}

    def status(self) -> dict:
        with self._lock:
            state = self._state
            server = dict(self._server) if self._server else None
            clients = [
                {
                    "name": client.name,
                    "pid": client.pid,
                    "runDir": str(client.run_dir),
                    "ready": bool(client.ready),
                }
                for client in self._clients
            ]
            last_error = self._last_error

        if server is not None:
            server["ready"] = bool(server.get("ready")) and _pid_alive(server.get("pid"))
        for client in clients:
            client["ready"] = bool(client["ready"]) and _pid_alive(client["pid"])

        server_out = None
        if server is not None:
            server_out = {
                "pid": server.get("pid"),
                "port": DEFAULT_GAME_PORT,
                "rconPort": DEFAULT_RCON_PORT,
                "ready": bool(server.get("ready")),
            }

        players: list[str] = []
        if server_out is not None and server_out["ready"]:
            players = self._players()

        out = {
            "state": state,
            "server": server_out,
            "clients": clients,
            "players": players,
            "currentScript": self._current_script(),
            "lastError": last_error,
        }
        if state in ("starting", "stopping"):
            out["progress"] = round(self._progress_value, 3)
            out["stage"] = self._progress_stage
        return out

    def cmd(
        self,
        text: str,
        target: str | None = None,
        wait_match: str | None = None,
        timeout_ms: int = 5000,
    ) -> dict:
        """向客户端聊天栏输入一行；``target="server"`` 时走 RCON。"""
        if not isinstance(text, str) or not text.strip():
            raise IcdError(E_INVALID_ARGUMENT, "text 必须是非空字符串")
        if wait_match is not None:
            regex = _compile(wait_match)
        else:
            regex = None
        timeout_s = max(0.05, float(timeout_ms) / 1000.0)

        if target == "server":
            log_path = self._server_log_path()
            follower = _LogFollower(log_path) if regex is not None else None
            response = self.rcon(text, timeout_ms=timeout_ms)
            result: dict = {"sent": True, "target": "server", "response": response}
            if regex is not None and follower is not None:
                matched, context, _ = self._wait_any([("server", follower, regex)], timeout_s, None, log_path)
                result["matched"] = matched is not None
                result["lastLines"] = context
            return result

        client = self._client_or_raise(target)
        window = self._require_window(client)
        log_path = client.run_dir / "logs" / "latest.log"
        follower = _LogFollower(log_path) if regex is not None else None
        win32.open_chat_and_type(window, text)
        result = {"sent": True, "target": client.name}
        if regex is not None and follower is not None:
            matched, context, _ = self._wait_any([("client", follower, regex)], timeout_s, None, log_path)
            result["matched"] = matched is not None
            result["lastLines"] = context
        else:
            result["matched"] = None
            result["lastLines"] = ""
        return result

    def key(self, key: str, target: str | None = None) -> dict:
        if not isinstance(key, str) or not key.strip():
            raise IcdError(E_INVALID_ARGUMENT, "key 必须是非空字符串")
        if target == "server":
            raise IcdError(E_INVALID_ARGUMENT, "按键只能发给客户端")
        client = self._client_or_raise(target)
        window = self._require_window(client)
        win32.send_key(window, key)
        return {"sent": True, "key": key, "target": client.name}

    def hold(self, key: str, seconds: float, target: str | None = None) -> dict:
        if not isinstance(key, str) or not key.strip():
            raise IcdError(E_INVALID_ARGUMENT, "key 必须是非空字符串")
        try:
            duration = float(seconds)
        except (TypeError, ValueError) as exc:
            raise IcdError(E_INVALID_ARGUMENT, f"seconds 不合法：{seconds!r}") from exc
        if duration < 0:
            raise IcdError(E_INVALID_ARGUMENT, "seconds 不能为负")
        if target == "server":
            raise IcdError(E_INVALID_ARGUMENT, "按键只能发给客户端")

        client = self._client_or_raise(target)
        window = self._require_window(client)
        vk = win32.vk_for(key)
        win32.send_key_down(window, vk)
        try:
            time.sleep(duration)
        finally:
            win32.send_key_up(window, vk)
        return {"sent": True, "key": key, "seconds": duration, "target": client.name}

    def shot(
        self,
        path: str | None = None,
        target: str | None = None,
        region: dict | None = None,
    ) -> dict:
        """游戏内 F2 截图；返回最终路径与尺寸。"""
        client = self._client_or_raise(target)
        window = self._require_window(client)
        shot_dir = client.run_dir / "screenshots"
        before = {item.name for item in _shot_files(shot_dir)}
        log_path = client.run_dir / "logs" / "latest.log"
        follower = _LogFollower(log_path)

        win32.send_key(window, "f2")
        _, context, found = self._wait_any(
            [("shot", follower, SHOT_RE)], SHOT_TIMEOUT_S, None, log_path
        )

        source: Path | None = None
        if found is not None:
            name = found.group(1).strip().strip("\"'")
            candidate = Path(name)
            if not candidate.is_absolute():
                candidate = shot_dir / candidate
            source = _await_file(candidate, 2.0)
        if source is None:
            fresh = [item for item in _shot_files(shot_dir) if item.name not in before]
            if fresh:
                source = max(fresh, key=lambda item: item.stat().st_mtime)
        if source is None:
            raise IcdError(E_TIMEOUT, "F2 截图未产生文件", detail=context)

        dest = source
        if path:
            wanted = Path(path)
            if wanted.resolve() != source.resolve():
                try:
                    wanted.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, wanted)
                except OSError as exc:
                    raise IcdError(
                        E_INVALID_ARGUMENT,
                        f"截图复制失败：{wanted}",
                        detail=str(exc),
                    ) from exc
            dest = wanted

        if region:
            dest = _crop_image(dest, region)

        width, height = _image_size(dest)
        return {"path": str(dest), "width": width, "height": height}

    def rcon(self, command: str, timeout_ms: int = 10000) -> str:
        if not isinstance(command, str) or not command.strip():
            raise IcdError(E_INVALID_ARGUMENT, "command 必须是非空字符串")
        with self._lock:
            server = self._server
        if server is None:
            raise IcdError(E_NOT_READY, "服务端未启动")

        client = rcon.get_client(
            host="127.0.0.1",
            port=DEFAULT_RCON_PORT,
            password=DEFAULT_RCON_PASSWORD,
        )
        client.timeout = max(0.5, float(timeout_ms) / 1000.0)
        return client.command(command.lstrip("/"))

    def log_tail(self, lines: int = 200, target: str | None = None) -> str:
        try:
            count = max(1, int(lines))
        except (TypeError, ValueError) as exc:
            raise IcdError(E_INVALID_ARGUMENT, f"lines 不合法：{lines!r}") from exc
        path = self._log_path(target)
        return logs.tail(path, count)

    def log_wait(
        self,
        pattern: str,
        timeout_ms: int = 5000,
        target: str | None = None,
    ) -> dict:
        regex = _compile(pattern)
        timeout_s = max(0.05, float(timeout_ms) / 1000.0)
        path = self._log_path(target)
        follower = _LogFollower(path)

        # 先看现有尾部，避免「先触发、后等待」时漏掉已经写出的行
        existing = follower.tail(200)
        found = regex.search(existing)
        if found is not None:
            return {
                "matched": True,
                "path": str(path),
                "lastLines": _last_lines(existing, 20),
            }

        matched, context, _ = self._wait_any([("log", follower, regex)], timeout_s, None, path)
        return {"matched": matched is not None, "path": str(path), "lastLines": context}

    # ------------------------------------------------------------ up 内部

    def _start(
        self,
        *,
        world: str,
        names: list[str],
        server: bool,
        shader: str | None,
        debug: bool,
        dirs: dict | None,
        progress: Callable[[float, str], None] | None,
        cancelled: Callable[[], bool] | None,
        started: float,
    ) -> dict:
        self._report(progress, 0.05, "准备启动环境（构建 common jar）")
        launch.ensure_common_jar(force=True)
        self._check_cancel(cancelled)
        spec = launch.ensure_launch_spec()
        self._check_cancel(cancelled)

        server_dir = Path((dirs or {}).get("server") or DEFAULT_SERVER_DIR)
        client_dirs = _resolve_client_dirs(names, dirs)

        with self._lock:
            stale = self._job
            self._job = None
        if stale is not None:
            try:
                stale.close()
            except Exception as exc:  # noqa: BLE001 - 上一次的兜底句柄
                log.warning("关闭遗留 Job Object 失败：%s", exc)
        with self._lock:
            self._job = win32.create_job()

        server_follower: _LogFollower | None = None
        if server:
            self._report(progress, 0.15, f"启动服务端：{server_dir}")
            _prepare_server(server_dir, world)
            if debug:
                mod_config.set(server_dir, "debugLogging", True)
            self._check_cancel(cancelled)

            server_log = server_dir / "logs" / "latest.log"
            server_follower = _LogFollower(server_log)
            cmd = launch.build_server_cmd(spec, server_dir)
            proc = self._spawn("server", cmd, server_dir, "server-launch.log")
            with self._lock:
                self._server = {"pid": proc.pid, "run_dir": str(server_dir), "ready": False}

            matched, context, _ = self._wait_any(
                [("server", server_follower, _compile(SERVER_READY_RE))],
                SERVER_TIMEOUT_S,
                cancelled,
                server_log,
            )
            if matched is None:
                raise IcdError(
                    E_TIMEOUT,
                    f"服务端启动超时（{SERVER_TIMEOUT_S:.0f}s）",
                    detail=context,
                )
            with self._lock:
                if self._server is not None:
                    self._server["ready"] = True
            self._report(progress, 0.4, "服务端就绪")
        else:
            self._report(progress, 0.4, "跳过服务端（只起客户端）")

        base = 0.4 if server else 0.15
        span = 0.95 - base
        total = max(1, len(names))
        for index, name in enumerate(names):
            self._check_cancel(cancelled)
            run_dir = client_dirs[index]
            self._report(
                progress,
                base + span * index / total,
                f"启动客户端 {name}：{run_dir}",
            )
            _prepare_client(run_dir, shader, debug)
            self._check_cancel(cancelled)

            client_log = run_dir / "logs" / "latest.log"
            follower = _LogFollower(client_log)
            cmd = launch.build_client_cmd(
                spec,
                run_dir,
                name,
                multiplayer=None if not server else f"localhost:{DEFAULT_GAME_PORT}",
                world=None if server else world,
            )
            proc = self._spawn(f"client:{name}", cmd, run_dir, f"client-{name}-launch.log")
            client = _Client(name=name, run_dir=run_dir, pid=proc.pid)
            with self._lock:
                self._clients.append(client)

            matched, context, _ = self._wait_client_ready(
                client, follower, server_follower, cancelled
            )
            if matched is None:
                raise IcdError(
                    E_TIMEOUT,
                    f"客户端 {name} 未在 {CLIENT_READY_TIMEOUT_S:.0f}s 内就绪",
                    detail=context,
                )
            client.ready = True

            window = self._wait_window(proc.pid, WINDOW_TIMEOUT_S, cancelled)
            if window is None:
                raise IcdError(
                    E_TIMEOUT,
                    f"客户端 {name} 的窗口未在 {WINDOW_TIMEOUT_S:.0f}s 内出现",
                    detail=logs.tail(client_log, 40),
                )
            log.info("客户端 %s 就绪 pid=%s hwnd=%s", name, proc.pid, window.hwnd)
            self._report(progress, base + span * (index + 1) / total, f"客户端 {name} 就绪")

        self._report(progress, 1.0, "装置就绪")
        with self._lock:
            self._state = "ready"
            server_info = (
                {
                    "pid": self._server.get("pid"),
                    "port": DEFAULT_GAME_PORT,
                    "rconPort": DEFAULT_RCON_PORT,
                    "ready": bool(self._server.get("ready")),
                }
                if self._server
                else None
            )
            client_info = [
                {"name": item.name, "pid": item.pid, "runDir": str(item.run_dir), "ready": item.ready}
                for item in self._clients
            ]

        return {
            "state": "ready",
            "server": server_info,
            "clients": client_info,
            "world": world,
            "shader": shader,
            "elapsed": round(_now() - started, 2),
        }

    def _spawn(self, role: str, cmd: list[str], run_dir: Path, launch_log_name: str):
        launch_log = run_dir / "logs" / launch_log_name
        proc = launch.spawn(cmd, run_dir, launch_log)
        with self._lock:
            job = self._job
        if job is not None:
            try:
                job.add(proc.pid)
            except IcdError as exc:
                log.warning("pid=%s 加入 Job 失败（terminate 兜底）：%s", proc.pid, exc)
        self._register_owned(role, proc.pid)
        return proc

    def _register_owned(self, role: str, pid: int) -> None:
        # 刚 spawn 的 java 可能还没出现在 CIM 里，短暂重试
        last: IcdError | None = None
        for _ in range(6):
            try:
                process.register_owned(role, pid)
                return
            except IcdError as exc:
                last = exc
                time.sleep(0.3)
        log.warning("所有权登记失败 role=%s pid=%s：%s（Job Object 兜底）", role, pid, last)

    def _wait_client_ready(
        self,
        client: _Client,
        follower: _LogFollower,
        server_follower: _LogFollower | None,
        cancelled: Callable[[], bool] | None,
    ) -> tuple[str | None, str, re.Match[str] | None]:
        client_log = client.run_dir / "logs" / "latest.log"
        entries: list[tuple[str, _LogFollower, re.Pattern[str]]] = [
            ("client", follower, _compile(CLIENT_READY_RE))
        ]
        if server_follower is not None:
            entries.append(
                (
                    "server",
                    server_follower,
                    _compile(JOINED_TEMPLATE.format(name=re.escape(client.name))),
                )
            )
        return self._wait_any(entries, CLIENT_READY_TIMEOUT_S, cancelled, client_log)

    def _wait_window(self, pid: int, timeout_s: float, cancelled: Callable[[], bool] | None):
        deadline = _now() + max(0.0, timeout_s)
        last: IcdError | None = None
        while True:
            try:
                return win32.find_window(pid)
            except IcdError as exc:
                if exc.code == E_UNSUPPORTED:
                    raise
                last = exc
            if self._is_cancelled(cancelled):
                raise _Cancelled()
            if _now() >= deadline:
                log.debug("等待窗口超时 pid=%s：%s", pid, last)
                return None
            time.sleep(0.4)

    def _wait_any(
        self,
        entries: list[tuple[str, _LogFollower, re.Pattern[str]]],
        timeout_s: float,
        cancelled: Callable[[], bool] | None,
        fallback: Path | None = None,
    ) -> tuple[str | None, str, re.Match[str] | None]:
        """轮询多个日志；返回 (命中键, 上下文, Match)；超时返回 (None, 最后 40 行, None)。"""
        if not entries:
            raise IcdError(E_INVALID_ARGUMENT, "没有可等待的日志")

        buffers: dict[str, str] = {}
        deadline = _now() + max(0.0, timeout_s)
        while True:
            for key, follower, _regex in entries:
                chunk = follower.read_new()
                if chunk:
                    merged = buffers.get(key, "") + chunk
                    buffers[key] = merged[-WAIT_BUFFER_MAX:]

            for key, _follower, regex in entries:
                found = regex.search(buffers.get(key, ""))
                if found is not None:
                    return key, _last_lines(buffers[key], 20), found

            if self._is_cancelled(cancelled):
                raise _Cancelled()
            if _now() >= deadline:
                path = fallback or entries[0][1].path
                return None, logs.tail(path, 40), None
            time.sleep(POLL_INTERVAL_S)

    # ------------------------------------------------------------ 状态辅助

    def _remember(self, exc: IcdError) -> None:
        with self._lock:
            self._last_error = exc.to_dict()

    def _players(self) -> list[str]:
        try:
            text = self.rcon("list", timeout_ms=2000)
        except Exception as exc:  # noqa: BLE001 - 状态查询不应抛
            log.debug("RCON list 失败：%s", exc)
            return []
        matched = LIST_RE.search(text or "")
        if matched is None:
            return []
        body = matched.group(1).strip()
        if not body:
            return []
        return [name.strip() for name in body.split(",") if name.strip()]

    def _current_script(self) -> str | None:
        with self._lock:
            clients = list(self._clients)
        for client in clients:
            text = logs.tail(client.run_dir / "logs" / "latest.log", 400)
            found = SCRIPT_RE.findall(text)
            if found:
                return found[-1]
        return None

    def _client_or_raise(self, target: str | None) -> _Client:
        with self._lock:
            clients = list(self._clients)
        if not clients:
            raise IcdError(E_NOT_READY, "装置没有客户端（先 up）")
        wanted = target if target is not None else clients[0].name
        for client in clients:
            if client.name == wanted:
                return client
        raise IcdError(
            E_NOT_FOUND,
            f"未知客户端：{wanted}",
            detail={"clients": [item.name for item in clients]},
        )

    def _require_window(self, client: _Client):
        if client.pid is None:
            raise IcdError(E_NO_WINDOW, f"客户端 {client.name} 没有进程")
        try:
            return win32.find_window(client.pid)
        except IcdError as exc:
            if exc.code in (E_NO_WINDOW, E_INVALID_ARGUMENT, E_NOT_FOUND):
                raise IcdError(
                    E_NO_WINDOW,
                    f"客户端 {client.name} 没有可见窗口（pid={client.pid}）",
                    detail=exc.detail,
                ) from exc
            raise

    def _server_log_path(self) -> Path:
        with self._lock:
            server = dict(self._server) if self._server else None
        if server is None:
            raise IcdError(E_NOT_READY, "服务端未启动")
        return Path(server["run_dir"]) / "logs" / "latest.log"

    def _log_path(self, target: str | None) -> Path:
        if target == "server":
            return self._server_log_path()
        client = self._client_or_raise(target)
        return client.run_dir / "logs" / "latest.log"

    # ------------------------------------------------------------ 进度/取消

    @staticmethod
    def _report(
        callback: Callable[[float, str], None] | None,
        value: float,
        message: str,
    ) -> None:
        if callback is None:
            return
        try:
            callback(float(value), message)
        except Exception:  # noqa: BLE001 - 进度回调不应影响启动
            log.warning("进度回调失败（已忽略）", exc_info=True)

    @staticmethod
    def _is_cancelled(cancelled: Callable[[], bool] | None) -> bool:
        if cancelled is None:
            return False
        try:
            return bool(cancelled())
        except Exception:  # noqa: BLE001
            log.warning("cancelled 回调失败（按未取消处理）", exc_info=True)
            return False

    def _check_cancel(self, cancelled: Callable[[], bool] | None) -> None:
        if self._is_cancelled(cancelled):
            raise _Cancelled()


# ---------------------------------------------------------------- 单例

_rig: Rig | None = None
_rig_lock = threading.Lock()


def get_rig() -> Rig:
    """模块级单例。"""
    global _rig
    with _rig_lock:
        if _rig is None:
            _rig = Rig()
        return _rig


def reset_rig() -> None:
    """丢弃单例（selftest 用）；先尽量停掉已有装置。"""
    global _rig
    with _rig_lock:
        rig = _rig
        _rig = None
    if rig is not None:
        try:
            rig.down(force=True)
        except Exception:  # noqa: BLE001 - 重置不抛
            log.warning("reset_rig 停装置失败（已忽略）", exc_info=True)


__all__ = ["Rig", "get_rig", "reset_rig"]
