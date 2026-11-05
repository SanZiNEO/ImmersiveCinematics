"""icdev MCP server（stdio，手写 JSON-RPC，不依赖 MCP SDK）。

约束（MCP 2025-06-18 / MCP-DESIGN.md §三）：

- **stdout 只写 JSON-RPC**：一行一条、UTF-8、写完立即 flush。任何诊断信息走
  stderr 或 ``notifications/message``；本模块禁止 ``print()``。
- 主线程阻塞读 stdin；``tools/call`` 在工作线程执行，读循环不停，因此 ``rig.up``
  跑 180 s 时仍能收到 ``notifications/cancelled``。
- ``initialize`` → ``notifications/initialized`` → 操作。未收到 initialized 之前的
  ``tools/call`` 回 ``-32002 Server not initialized``。
- 协议错误（未知工具 / 未知 action / 参数缺失或类型错）→ JSON-RPC ``-32602``；
  执行期失败 → ``result.isError = true`` + ``structuredContent = IcdError.to_dict()``。

入口::

    python -m icdev.server            # stdio
    python -m icdev.server --selftest # 内存管道自检（不碰游戏）
"""

from __future__ import annotations

import argparse
import io
import json
import logging
import math
import re
import sys
import threading
import time
import traceback
from dataclasses import dataclass
from pathlib import Path
from typing import Any, IO, Optional

from . import config as config_mod
from . import diag as diag_mod
from . import info as info_mod
from . import pixels as pixels_mod
from . import process as process_mod
from . import snapshot as snapshot_mod
from .errors import E_INVALID_ARGUMENT, E_NOT_FOUND, E_OWNERSHIP, IcdError
from .paths import (
    DEFAULT_CLIENT_DIRS,
    DEFAULT_SERVER_DIR,
    FABRIC_RUN,
    FORGE_RUN,
    REPO_ROOT,
    SNAPSHOT_ROOT,
)

log = logging.getLogger("icdev.server")

# ------------------------------------------------------------------ 常量

PROTOCOL_VERSION = "2025-06-18"
SERVER_NAME = "icdev"
SERVER_VERSION = "0.1.0"
INSTRUCTIONS = "ImmersiveCinematics dev rig over MCP. One session = one rig."

INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
NOT_INITIALIZED = -32002

# errors.py 里没有「取消」，但 MCP 语义要求单独的错误码
E_CANCELLED = "E_CANCELLED"
E_INTERNAL = "E_INTERNAL"

TOOL_NAMES = [
    "rig",
    "process",
    "input",
    "capture",
    "log",
    "config",
    "snapshot",
    "rcon",
    "diag",
    "info",
]


# ------------------------------------------------------------------ 工具 schema


def _schema(actions: list[str], properties: dict[str, dict]) -> dict:
    """构造统一的 inputSchema：平铺 properties + action 枚举。"""
    props: dict[str, Any] = {
        "action": {
            "type": "string",
            "enum": list(actions),
            "description": "要执行的动作",
        }
    }
    props.update(properties)
    return {
        "type": "object",
        "properties": props,
        "required": ["action"],
        "additionalProperties": False,
    }


_P_TARGET = {"type": "string", "description": "server / client / clientB / clientC，或 run dir 路径"}
_P_TIMEOUT_MS = {"type": "integer", "description": "超时毫秒数", "minimum": 0}
_P_SHOT_TARGET = {"type": "string", "description": "截图目标客户端名（如 clientA），默认第一个客户端"}
_P_REGION = {
    "type": "object",
    "description": "截图区域：{x,y,w,h} 或 {x0,y0,x1,y1}",
}

TOOLS: list[dict] = [
    {
        "name": "rig",
        "title": "Rig lifecycle",
        "description": (
            "装置生命周期。up: 后台启动服务端/客户端并立即返回（world 必填；用 status 轮询 progress/stage 至 ready，"
            "支持 progress 与 cancellation）；down: 结束整个装置；status: 查询当前状态。"
            "一次会话只允许一个装置。"
        ),
        "inputSchema": _schema(
            ["up", "down", "status"],
            {
                "world": {"type": "string", "description": "up: 世界名（必填）"},
                "clients": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "up: 要启动的客户端列表（如 clientA/clientB；缺省只起服务端）",
                },
                "server": {"type": "boolean", "description": "up: 是否启动服务端（默认 true）"},
                "shader": {"type": "string", "description": "up: shaderpack 名称"},
                "debug": {"type": "boolean", "description": "up: 是否开启调试"},
                "dirs": {
                    "type": "object",
                    "description": "up: {server?: path, clients?: [path, ...]}",
                },
                "force": {"type": "boolean", "description": "down: 强制结束（默认 false）"},
            },
        ),
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "process",
        "title": "Process control",
        "description": (
            "java 进程。list: 列出 KnotServer/KnotClient 与本装置认领的进程；"
            "kill: 只终止本会话登记且三元组匹配的进程（外部手工启动的进程永远不会被碰）。"
        ),
        "inputSchema": _schema(
            ["list", "kill"],
            {
                "target": {
                    "type": "string",
                    "enum": ["client", "server", "all"],
                    "description": "kill: 要终止的角色",
                },
                "name": {"type": "string", "description": "kill: 按客户端名或命令行子串过滤（可选）"},
            },
        ),
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "input",
        "title": "Game input",
        "description": (
            "向游戏窗口发输入。cmd: 在聊天栏执行命令（可等待日志匹配）；"
            "key: 单击按键；hold: 长按按键指定秒数。"
        ),
        "inputSchema": _schema(
            ["cmd", "key", "hold"],
            {
                "text": {"type": "string", "description": "cmd: 命令文本"},
                "target": _P_TARGET,
                "waitMatch": {"type": "string", "description": "cmd: 发送后等待的正则"},
                "timeoutMs": _P_TIMEOUT_MS,
                "key": {"type": "string", "description": "key/hold: 按键名"},
                "seconds": {"type": "number", "description": "hold: 按住秒数", "minimum": 0},
            },
        ),
        "annotations": {},
    },
    {
        "name": "capture",
        "title": "Screenshots & pixels",
        "description": (
            "取证。shot: 截取窗口（可指定路径/目标/区域）；pixels: 对图片做亮像素簇"
            "（clusters）或双图差异（diff）分析。"
        ),
        "inputSchema": _schema(
            ["shot", "pixels"],
            {
                "path": {"type": "string", "description": "shot: 输出路径；pixels: 图片路径"},
                "target": _P_SHOT_TARGET,
                "region": _P_REGION,
                "mode": {
                    "type": "string",
                    "enum": ["clusters", "diff"],
                    "description": "pixels: 分析模式",
                },
                "threshold": {"type": "integer", "description": "pixels: 阈值"},
                "band": {
                    "type": "array",
                    "items": {"type": "number"},
                    "minItems": 2,
                    "maxItems": 2,
                    "description": "pixels/clusters: 相对高度带 [y0, y1]",
                },
                "compare": {"type": "string", "description": "pixels/diff: 对照图片路径"},
            },
        ),
        "annotations": {"idempotentHint": True},
    },
    {
        "name": "log",
        "title": "Log access",
        "description": (
            "日志。tail: 末尾 N 行（GBK 容错）；trace: 按正则过滤（可写出到文件）；"
            "wait: 阻塞等待新日志命中正则。"
        ),
        "inputSchema": _schema(
            ["tail", "trace", "wait"],
            {
                "lines": {"type": "integer", "description": "tail/trace: 行数", "minimum": 0},
                "target": _P_TARGET,
                "pattern": {"type": "string", "description": "trace/wait: 正则"},
                "out": {"type": "string", "description": "trace: 过滤结果写出路径"},
                "timeoutMs": _P_TIMEOUT_MS,
            },
        ),
        "annotations": {"readOnlyHint": True},
    },
    {
        "name": "config",
        "title": "Mod config",
        "description": (
            "读写 run dir 下的 immersive_cinematics.json。get: 读单个键；"
            "set: 白名单键 + 类型/范围校验后写入，返回旧值。"
        ),
        "inputSchema": _schema(
            ["get", "set"],
            {
                "key": {"type": "string", "description": "配置键"},
                "value": {"description": "set: 新值（JSON 任意类型，由白名单校验）"},
                "target": _P_TARGET,
            },
        ),
        "annotations": {"idempotentHint": True},
    },
    {
        "name": "snapshot",
        "title": "Run snapshots",
        "description": (
            "运行目录快照。create: 备份配置/脚本并写 manifest；restore: 按 manifest 还原"
            "（含删除新增文件）；list: 列出快照。"
        ),
        "inputSchema": _schema(
            ["create", "restore", "list"],
            {
                "name": {"type": "string", "description": "create/restore: 快照名"},
                "targets": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "create: 相对仓库根的文件/目录；缺省为 fabric 的配置与脚本",
                },
            },
        ),
        "annotations": {"destructiveHint": True},
    },
    {
        "name": "rcon",
        "title": "RCON",
        "description": "向服务端发一条 RCON 命令（连接复用），返回响应文本。",
        "inputSchema": _schema(
            ["command"],
            {
                "command": {"type": "string", "description": "RCON 命令，如 list"},
                "timeoutMs": _P_TIMEOUT_MS,
            },
        ),
        "annotations": {},
    },
    {
        "name": "diag",
        "title": "Diagnostics",
        "description": (
            "诊断会话。manifest: 生成 f-round.json；perf: 读取摘要指标；"
            "sessions: 列出会话；analyze: 校验会话目录结构完整性。"
        ),
        "inputSchema": _schema(
            ["manifest", "perf", "sessions", "analyze"],
            {
                "cases": {
                    "type": "array",
                    "items": {"type": "object"},
                    "description": "manifest: [{id, script, lanes?, frames?, content?}]",
                },
                "session": {"type": "string", "description": "manifest/perf: 会话名（可选）"},
                "limit": {"type": "integer", "description": "sessions: 返回条数（默认 5，<=0 不限）"},
                "sessionDir": {"type": "string", "description": "analyze: 会话目录路径"},
            },
        ),
        "annotations": {"idempotentHint": True},
    },
    {
        "name": "info",
        "title": "Environment info",
        "description": "环境自检。doctor: 窗口/日志/配置/依赖体检；where: 当前世界、坐标与维度。",
        "inputSchema": _schema(["doctor", "where"], {}),
        "annotations": {"readOnlyHint": True},
    },
]

TOOL_BY_NAME = {tool["name"]: tool for tool in TOOLS}

# ------------------------------------------------------------------ 参数规格
# kind: str | bool | int | number | dict | str_list | obj_list | band | any
_SPECS: dict[tuple[str, str], dict[str, Any]] = {
    ("rig", "up"): {
        "params": {
            "world": "str",
            "clients": "str_list",
            "server": "bool",
            "shader": "str",
            "debug": "bool",
            "dirs": "dict",
        },
        "required": ["world"],
    },
    ("rig", "down"): {"params": {"force": "bool"}, "required": []},
    ("rig", "status"): {"params": {}, "required": []},
    ("process", "list"): {"params": {}, "required": []},
    ("process", "kill"): {
        "params": {"target": "str", "name": "str"},
        "required": ["target"],
        "enums": {"target": ["client", "server", "all"]},
    },
    ("input", "cmd"): {
        "params": {"text": "str", "target": "str", "waitMatch": "str", "timeoutMs": "int"},
        "required": ["text"],
    },
    ("input", "key"): {
        "params": {"key": "str", "target": "str"},
        "required": ["key"],
    },
    ("input", "hold"): {
        "params": {"key": "str", "seconds": "number", "target": "str"},
        "required": ["key", "seconds"],
    },
    ("capture", "shot"): {
        "params": {"path": "str", "target": "str", "region": "dict"},
        "required": [],
    },
    ("capture", "pixels"): {
        "params": {
            "path": "str",
            "mode": "str",
            "threshold": "int",
            "band": "band",
            "compare": "str",
        },
        "required": ["path", "mode"],
        "enums": {"mode": ["clusters", "diff"]},
    },
    ("log", "tail"): {"params": {"lines": "int", "target": "str"}, "required": []},
    ("log", "trace"): {"params": {"pattern": "str", "lines": "int", "out": "str"}, "required": []},
    ("log", "wait"): {
        "params": {"pattern": "str", "timeoutMs": "int", "target": "str"},
        "required": ["pattern"],
    },
    ("config", "get"): {"params": {"key": "str", "target": "str"}, "required": ["key"]},
    ("config", "set"): {
        "params": {"key": "str", "value": "any", "target": "str"},
        "required": ["key", "value"],
    },
    ("snapshot", "create"): {
        "params": {"name": "str", "targets": "str_list"},
        "required": ["name"],
    },
    ("snapshot", "restore"): {"params": {"name": "str"}, "required": ["name"]},
    ("snapshot", "list"): {"params": {}, "required": []},
    ("rcon", "command"): {
        "params": {"command": "str", "timeoutMs": "int"},
        "required": ["command"],
    },
    ("diag", "manifest"): {
        "params": {"cases": "obj_list", "session": "str"},
        "required": ["cases"],
    },
    ("diag", "perf"): {"params": {"session": "str"}, "required": []},
    ("diag", "sessions"): {"params": {"limit": "int"}, "required": []},
    ("diag", "analyze"): {"params": {"sessionDir": "str"}, "required": ["sessionDir"]},
    ("info", "doctor"): {"params": {}, "required": []},
    ("info", "where"): {"params": {}, "required": []},
}


class InvalidParams(Exception):
    """参数问题：由调用方转成 JSON-RPC -32602（不是工具执行错误）。"""


def _fail(message: str) -> None:
    raise InvalidParams(message)


def _check_kind(where: str, name: str, value: Any, kind: str) -> None:
    if kind == "any":
        return
    if kind == "str":
        if not isinstance(value, str):
            _fail(f"{where}.{name} 必须是字符串")
    elif kind == "bool":
        if not isinstance(value, bool):
            _fail(f"{where}.{name} 必须是布尔值")
    elif kind == "int":
        if isinstance(value, bool) or not isinstance(value, int):
            _fail(f"{where}.{name} 必须是整数")
    elif kind == "number":
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(float(value))
        ):
            _fail(f"{where}.{name} 必须是有限数字")
    elif kind == "dict":
        if not isinstance(value, dict):
            _fail(f"{where}.{name} 必须是对象")
    elif kind == "str_list":
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            _fail(f"{where}.{name} 必须是字符串数组")
    elif kind == "obj_list":
        if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
            _fail(f"{where}.{name} 必须是对象数组")
    elif kind == "band":
        if (
            not isinstance(value, list)
            or len(value) != 2
            or not all(
                isinstance(item, (int, float)) and not isinstance(item, bool) for item in value
            )
        ):
            _fail(f"{where}.{name} 必须是 [y0, y1] 两个数字")
    else:  # pragma: no cover - 规格表写错属于内部错误
        raise AssertionError(f"未知参数类型: {kind}")


def validate_arguments(tool: str, arguments: Any) -> tuple[str, dict]:
    """校验 tools/call 参数；返回 (action, arguments)。问题抛 InvalidParams。"""
    if not isinstance(arguments, dict):
        _fail("arguments 必须是对象")

    action = arguments.get("action")
    if not isinstance(action, str):
        _fail(f"{tool}.action 缺失或不是字符串")

    spec = _SPECS.get((tool, action))
    if spec is None:
        allowed = sorted(a for (name, a) in _SPECS if name == tool)
        _fail(f"{tool} 未知 action: {action!r}（可选: {', '.join(allowed)}）")

    params: dict[str, str] = spec["params"]
    allowed_keys = {"action"} | set(params)
    extra = sorted(set(arguments) - allowed_keys)
    if extra:
        _fail(f"{tool}.{action} 不接受参数: {', '.join(extra)}")

    for name in spec["required"]:
        if name not in arguments:
            _fail(f"{tool}.{action} 缺少必填参数 {name}")

    for name, kind in params.items():
        if name in arguments:
            _check_kind(f"{tool}.{action}", name, arguments[name], kind)

    for name, choices in (spec.get("enums") or {}).items():
        if name in arguments and arguments[name] not in choices:
            _fail(
                f"{tool}.{action}.{name} 取值非法: {arguments[name]!r}"
                f"（可选: {', '.join(choices)}）"
            )

    # action 级的条件必填：diff 必须有对照图
    if tool == "capture" and action == "pixels" and arguments.get("mode") == "diff":
        if "compare" not in arguments:
            _fail("capture.pixels: mode=diff 需要 compare")

    return action, arguments


# ------------------------------------------------------------------ 运行期辅助


def _get_rig():
    """延迟导入 rig：rig.py 可能在并行开发中，缺失时只影响装置类工具。"""
    try:
        from .rig import get_rig
    except ImportError as exc:  # pragma: no cover - 正常仓库内不会发生
        raise IcdError(E_INTERNAL, f"icdev.rig 不可用：{exc}") from exc
    return get_rig()


def _jsonable(value: Any) -> Any:
    """把结果压成可 JSON 序列化的结构（Path/dataclass/NaN 等兜底）。"""
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_jsonable(item) for item in value]
    return str(value)


def _resolve_path(raw: str) -> Path:
    """相对路径按仓库根解析，保证 server 的 cwd 不影响结果。"""
    path = Path(raw).expanduser()
    if not path.is_absolute():
        path = REPO_ROOT / path
    return path


_CLIENT_INDEX = {
    "client": 0,
    "clienta": 0,
    "client1": 0,
    "clientb": 1,
    "client2": 1,
    "clientc": 2,
    "client3": 2,
}


def _target_run_dir(target: Optional[str]) -> Path:
    """config 的 target → run dir。支持 server/clientA..C 或显式路径。"""
    if target is None:
        return DEFAULT_SERVER_DIR
    lowered = target.strip().lower()
    if lowered in ("server", "server1"):
        return DEFAULT_SERVER_DIR
    if lowered in _CLIENT_INDEX:
        return DEFAULT_CLIENT_DIRS[_CLIENT_INDEX[lowered]]
    if "/" in target or "\\" in target or Path(target).is_absolute():
        path = Path(target).expanduser()
        if not path.is_absolute():
            path = REPO_ROOT / path
        return path
    raise IcdError(
        E_INVALID_ARGUMENT,
        f"未知 target: {target!r}（可用 server/client/clientB/clientC 或 run dir 路径）",
        {"target": target},
    )


def _diag_run_dir() -> Path:
    """诊断默认 run dir：优先「真的存在 sessions 目录」的那个。"""
    for run_dir in (DEFAULT_SERVER_DIR, FABRIC_RUN):
        if diag_mod.sessions_root(run_dir).is_dir():
            return run_dir
    return DEFAULT_SERVER_DIR


def _session_dir(run_dir: Path, session: Optional[str]) -> Path:
    if session:
        looks_like_path = (
            Path(session).is_absolute() or "/" in session or "\\" in session
        )
        if looks_like_path:
            direct = _resolve_path(session)
            if direct.is_dir():
                return direct
        candidate = diag_mod.sessions_root(run_dir) / session
        if candidate.is_dir():
            return candidate
        if looks_like_path:
            return _resolve_path(session)  # 交给 read_summaries 报 E_NOT_FOUND
        raise IcdError(
            E_NOT_FOUND,
            f"诊断会话不存在: {session}",
            {"runDir": str(run_dir)},
        )

    items = diag_mod.list_sessions(run_dir, limit=1)
    if not items:
        raise IcdError(E_NOT_FOUND, "没有可用的诊断会话", {"runDir": str(run_dir)})
    return Path(items[0]["path"])


def _role_matches(role: str, target: str) -> bool:
    """rig 把客户端登记成 ``client:<name>``；kill 的 target=client 要按前缀匹配。"""
    if target == "all":
        return role == "server" or role == "client" or role.startswith("client:")
    if target == "client":
        return role == "client" or role.startswith("client:")
    return role == target


def _default_snapshot_targets() -> list[Path]:
    candidates = [
        FABRIC_RUN / "immersive_cinematics" / "scripts",
        FABRIC_RUN / "config" / "immersive_cinematics.json",
        FORGE_RUN / "config" / "immersive_cinematics.json",
    ]
    targets = [path for path in candidates if path.exists()]
    if not targets:
        raise IcdError(
            E_NOT_FOUND,
            "没有可快照的默认目标（fabric/forge 的配置或脚本不存在）",
            {"candidates": [str(path) for path in candidates]},
        )
    return targets


def _ensure_rig_dict(value: Any) -> dict:
    return value if isinstance(value, dict) else {"result": value}


@dataclass
class ToolResult:
    structured: dict
    text: Optional[str] = None


@dataclass
class CallContext:
    request_id: Any
    cancel: threading.Event
    progress_token: Any = None


# ------------------------------------------------------------------ server


class Server:
    """一行一条 JSON-RPC 的 stdio server。一个实例 = 一个会话 = 一个装置。"""

    def __init__(
        self,
        stdin: Optional[IO[str]] = None,
        stdout: Optional[IO[str]] = None,
        stderr: Optional[IO[str]] = None,
    ) -> None:
        self._stdin = stdin if stdin is not None else sys.stdin
        self._out = stdout if stdout is not None else sys.stdout
        self._err = stderr if stderr is not None else sys.stderr

        self._write_lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._threads_lock = threading.Lock()

        self._initialized = False
        self._closed = threading.Event()
        self._shutting_down = False

        self._cancel_events: dict[tuple[str, str], threading.Event] = {}
        self._threads: set[threading.Thread] = set()

        self._rig_active = False

    # ---- 输出 ----

    def _send(self, payload: dict) -> None:
        """唯一写 stdout 的入口：一行一条，ensure_ascii=False，立即 flush。"""
        if self._closed.is_set():
            return
        try:
            line = json.dumps(payload, ensure_ascii=False)
            with self._write_lock:
                self._out.write(line + "\n")
                self._out.flush()
        except (BrokenPipeError, OSError, ValueError):
            self._closed.set()
            log.debug("stdout 已关闭，停止写协议消息")

    def _notify(self, method: str, params: dict) -> None:
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def _notify_message(self, level: str, text: str) -> None:
        if not self._initialized:
            log.debug("初始化前不发 logging 通知：%s", text)
            return
        self._notify("notifications/message", {"level": level, "logger": "icdev", "data": text})

    def _write_stderr(self, text: str) -> None:
        try:
            self._err.write(text if text.endswith("\n") else text + "\n")
            self._err.flush()
        except (OSError, ValueError):
            pass

    # ---- 请求登记 ----

    @staticmethod
    def _id_key(request_id: Any) -> tuple[str, str]:
        return (type(request_id).__name__, json.dumps(request_id, sort_keys=True))

    def _register_request(self, request_id: Any, event: threading.Event) -> None:
        with self._state_lock:
            self._cancel_events[self._id_key(request_id)] = event

    def _forget_request(self, request_id: Any) -> None:
        with self._state_lock:
            self._cancel_events.pop(self._id_key(request_id), None)

    def _cancel_request(self, request_id: Any) -> None:
        with self._state_lock:
            event = self._cancel_events.get(self._id_key(request_id))
        if event is None:
            log.debug("取消了一个未知/已完成的请求：%r", request_id)
            return
        event.set()
        log.debug("已请求取消 requestId=%r", request_id)

    # ---- 主循环 ----

    def run(self) -> int:
        code = 0
        try:
            for line in self._stdin:
                if self._closed.is_set():
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    message = json.loads(line)
                except json.JSONDecodeError as exc:
                    self._write_stderr(
                        f"[icdev] 忽略非法 JSON 行（{exc.msg}）：{line[:200]!r}"
                    )
                    continue
                if not isinstance(message, dict):
                    self._write_stderr("[icdev] 忽略非对象 JSON 行")
                    continue
                try:
                    self._handle_message(message)
                except Exception as exc:  # noqa: BLE001 - 协议循环必须存活
                    self._write_stderr("[icdev] 处理消息时发生未捕获异常：")
                    traceback.print_exc(file=self._err)
                    if isinstance(message, dict) and "id" in message:
                        self._send_error(
                            message.get("id"),
                            -32603,
                            f"Internal error: {exc}",
                        )
        except KeyboardInterrupt:  # pragma: no cover - 交互式退出
            code = 130
        finally:
            self._shutdown()
        return code

    def _handle_message(self, message: dict) -> None:
        method = message.get("method")

        # 客户端对 server 请求的响应：本 server 不发请求，直接忽略。
        if method is None:
            return

        if not isinstance(method, str):
            if "id" in message:
                self._send_error(message.get("id"), INVALID_REQUEST, "Invalid Request: method 必须是字符串")
            return

        if method.startswith("notifications/"):
            self._handle_notification(method, message.get("params"))
            return

        if "id" not in message:
            # JSON-RPC notification 不需要响应；未知 notification 忽略。
            log.debug("忽略无 id 的请求：%s", method)
            return

        request_id = message["id"]

        if method == "initialize":
            self._send_result(request_id, self._initialize_result())
            return

        if method == "ping":
            self._send_result(request_id, {})
            return

        if method == "tools/list":
            self._send_result(request_id, {"tools": TOOLS})
            return

        if method == "tools/call":
            self._handle_tools_call(request_id, message.get("params"))
            return

        self._send_error(request_id, METHOD_NOT_FOUND, f"Method not found: {method}")

    def _handle_notification(self, method: str, params: Any) -> None:
        if method == "notifications/initialized":
            self._initialized = True
            log.debug("收到 notifications/initialized")
            self._notify_message("info", "icdev session initialized")
            return

        if method == "notifications/cancelled":
            if isinstance(params, dict) and "requestId" in params:
                self._cancel_request(params.get("requestId"))
            return

        log.debug("忽略通知：%s", method)

    # ---- 响应 ----

    @staticmethod
    def _initialize_result() -> dict:
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}, "logging": {}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": INSTRUCTIONS,
        }

    def _send_result(self, request_id: Any, result: Any) -> None:
        self._send({"jsonrpc": "2.0", "id": request_id, "result": _jsonable(result)})

    def _send_error(self, request_id: Any, code: int, message: str, data: Any = None) -> None:
        error: dict[str, Any] = {"code": code, "message": message}
        if data is not None:
            error["data"] = _jsonable(data)
        self._send({"jsonrpc": "2.0", "id": request_id, "error": error})

    @staticmethod
    def _ok_payload(result: ToolResult) -> dict:
        structured = _jsonable(result.structured)
        if not isinstance(structured, dict):
            structured = {"result": structured}
        text = result.text
        if text is None:
            text = json.dumps(structured, ensure_ascii=False)
        return {
            "content": [{"type": "text", "text": text}],
            "structuredContent": structured,
            "isError": False,
        }

    @staticmethod
    def _error_payload(error: IcdError) -> dict:
        data = _jsonable(error.to_dict())
        if not isinstance(data, dict):  # to_dict 保证是 dict；防御性兜底
            data = {"code": E_INTERNAL, "message": str(error)}
        return {
            "content": [{"type": "text", "text": f"{error.code}: {error.message}"}],
            "structuredContent": data,
            "isError": True,
        }

    # ---- tools/call ----

    def _handle_tools_call(self, request_id: Any, params: Any) -> None:
        if not self._initialized:
            self._send_error(request_id, NOT_INITIALIZED, "Server not initialized")
            return

        if not isinstance(params, dict):
            self._send_error(request_id, INVALID_PARAMS, "Invalid params: params 必须是对象")
            return

        name = params.get("name")
        if not isinstance(name, str):
            self._send_error(request_id, INVALID_PARAMS, "Invalid params: name 必须是字符串")
            return
        if name not in TOOL_BY_NAME:
            self._send_error(request_id, INVALID_PARAMS, f"Invalid params: 未知工具 {name!r}")
            return

        arguments = params.get("arguments")
        if not isinstance(arguments, dict):
            self._send_error(
                request_id, INVALID_PARAMS, "Invalid params: arguments 必须是对象"
            )
            return

        try:
            action, arguments = validate_arguments(name, arguments)
        except InvalidParams as exc:
            self._send_error(request_id, INVALID_PARAMS, f"Invalid params: {exc}")
            return

        progress_token = None
        meta = params.get("_meta")
        if meta is not None:
            if not isinstance(meta, dict):
                self._send_error(
                    request_id, INVALID_PARAMS, "Invalid params: _meta 必须是对象"
                )
                return
            progress_token = meta.get("progressToken")

        event = threading.Event()
        self._register_request(request_id, event)

        if event.is_set():  # 极端竞态：刚登记就被取消
            self._forget_request(request_id)
            self._send_result(
                request_id,
                self._error_payload(IcdError(E_CANCELLED, "请求已取消")),
            )
            return

        ctx = CallContext(request_id=request_id, cancel=event, progress_token=progress_token)
        thread = threading.Thread(
            target=self._run_tool_thread,
            args=(request_id, name, action, arguments, ctx),
            name=f"icdev-call-{action}",
            daemon=True,
        )
        with self._threads_lock:
            self._threads.add(thread)
        try:
            thread.start()
        except RuntimeError as exc:  # 线程创建失败
            with self._threads_lock:
                self._threads.discard(thread)
            self._forget_request(request_id)
            self._send_result(
                request_id,
                self._error_payload(IcdError(E_INTERNAL, f"工作线程创建失败：{exc}")),
            )

    def _run_tool_thread(
        self,
        request_id: Any,
        tool: str,
        action: str,
        arguments: dict,
        ctx: CallContext,
    ) -> None:
        try:
            try:
                result = self._dispatch(tool, action, arguments, ctx)
                payload = self._ok_payload(result)
            except IcdError as exc:
                payload = self._error_payload(exc)
            except Exception as exc:  # noqa: BLE001 - 未预期异常 → E_INTERNAL
                self._write_stderr(f"[icdev] 工具 {tool}.{action} 未预期异常：")
                traceback.print_exc(file=self._err)
                payload = self._error_payload(IcdError(E_INTERNAL, str(exc)))
            self._send({"jsonrpc": "2.0", "id": request_id, "result": payload})
        except Exception:  # noqa: BLE001 - 写响应失败也不能带崩进程
            self._write_stderr(f"[icdev] 回写 {tool}.{action} 结果失败：")
            traceback.print_exc(file=self._err)
        finally:
            self._forget_request(request_id)
            with self._threads_lock:
                self._threads.discard(threading.current_thread())

    # ---- 分派 ----

    def _dispatch(
        self, tool: str, action: str, arguments: dict, ctx: CallContext
    ) -> ToolResult:
        if ctx.cancel.is_set():
            raise IcdError(E_CANCELLED, "请求已取消", {"requestId": ctx.request_id})

        handler = getattr(self, f"_do_{tool}", None)
        if handler is None:  # pragma: no cover - 工具表与 handler 不同步属于内部错误
            raise IcdError(E_INTERNAL, f"工具 {tool} 没有实现")

        result = handler(action, arguments, ctx)
        if isinstance(result, ToolResult):
            return result
        if isinstance(result, dict):
            return ToolResult(result)
        return ToolResult({"result": result})

    # ---- rig ----

    def _do_rig(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        rig = _get_rig()

        if action == "status":
            return ToolResult(_ensure_rig_dict(rig.status()))

        if action == "down":
            force = bool(args.get("force", False))
            self._notify_message("info", f"rig.down: 开始（force={force}）")
            try:
                state = rig.down(force=force)
            except IcdError as exc:
                self._notify_message("error", f"rig.down: 失败（{exc.code}）{exc.message}")
                raise
            with self._state_lock:
                self._rig_active = False
            self._notify_message("info", "rig.down: 完成")
            return ToolResult(_ensure_rig_dict(state))

        # action == "up"
        clients = args.get("clients") or []
        world = args.get("world")
        self._notify_message(
            "info",
            f"rig.up: 开始（world={world!r}, clients={clients!r}, "
            f"server={args.get('server', True)!r}）",
        )

        def progress(value: Any, message: Any) -> None:
            token = ctx.progress_token
            if token is None:
                return
            try:
                current = float(value)
            except (TypeError, ValueError):
                return
            if not math.isfinite(current):
                return
            self._notify(
                "notifications/progress",
                {
                    "progressToken": token,
                    "progress": current,
                    "total": 1.0,
                    "message": str(message),
                },
            )

        try:
            state = rig.up(
                world=world,
                clients=clients,
                server=args.get("server", True),
                shader=args.get("shader"),
                debug=bool(args.get("debug", False)),
                dirs=args.get("dirs"),
                progress=progress,
                cancelled=ctx.cancel.is_set,
                wait=False,  # 启动要 ~90s，超过客户端请求超时；立即返回，用 rig.status 轮询
            )
        except IcdError as exc:
            if ctx.cancel.is_set():
                # rig 内部把取消表达成 E_TIMEOUT；对外统一成 E_CANCELLED
                self._notify_message("warning", "rig.up: 已取消")
                raise IcdError(
                    E_CANCELLED,
                    "rig.up 已取消",
                    {"requestId": ctx.request_id},
                ) from exc
            self._notify_message("error", f"rig.up: 失败（{exc.code}）{exc.message}")
            raise
        except Exception as exc:  # noqa: BLE001 - 通知后再交给统一错误处理
            self._notify_message("error", f"rig.up: 未预期失败 {exc}")
            raise

        with self._state_lock:
            self._rig_active = True

        if ctx.cancel.is_set():
            # 取消发生在 up 返回前的竞态窗口：主动回收，绝不留半个装置
            try:
                rig.down(force=True)
            except Exception as exc:  # noqa: BLE001 - 回收尽力而为
                self._write_stderr(f"[icdev] 取消 rig.up 后回收失败：{exc}")
            with self._state_lock:
                self._rig_active = False
            self._notify_message("warning", "rig.up: 已取消并回收")
            raise IcdError(E_CANCELLED, "rig.up 已取消", {"requestId": ctx.request_id})

        self._notify_message("info", "rig.up: 已开始（后台启动，用 status 轮询就绪）")
        return ToolResult(_ensure_rig_dict(state))

    # ---- process ----

    def _do_process(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        if action == "list":
            owned = {item.pid: item for item in process_mod.owned_processes()}
            items: list[dict] = []
            for raw in process_mod.list_java_processes(use_cache=False):
                record = owned.get(raw.pid)
                role = process_mod.role_of(raw.cmdline) or (record.role if record else None)
                if role is None and record is None:
                    continue
                cmdline = raw.cmdline
                if len(cmdline) > 240:
                    cmdline = cmdline[:240] + "…"
                items.append(
                    {
                        "role": record.role if record is not None else role,
                        "kind": role,
                        "pid": raw.pid,
                        "owned": raw.pid in owned,
                        "createdAt": raw.created_at,
                        "cmdline": cmdline,
                    }
                )
            return ToolResult({"processes": items, "count": len(items)})

        # action == "kill"
        target = args["target"]
        name = args.get("name")
        owned = process_mod.owned_processes()

        if name is None:
            victims = [
                item for item in owned if _role_matches(item.role, target)
            ]
        else:
            needle = name.lower()
            owned_by_pid = {item.pid: item for item in owned}
            raws = {
                raw.pid: raw for raw in process_mod.list_java_processes(use_cache=False)
            }
            victims = []
            external = []
            for raw in raws.values():
                record = owned_by_pid.get(raw.pid)
                derived = process_mod.role_of(raw.cmdline)
                role = derived or (record.role if record else None)
                if role is None or not _role_matches(role, target):
                    continue
                matched = needle in raw.cmdline.lower()
                if record is not None:
                    suffix = (
                        record.role.split(":", 1)[1]
                        if ":" in record.role
                        else record.role
                    )
                    matched = matched or record.role.lower() == needle or suffix.lower() == needle
                if not matched:
                    continue
                if record is not None and process_mod.is_owned(raw.pid):
                    victims.append(record)
                else:
                    external.append(raw)

            if external:
                raise IcdError(
                    E_OWNERSHIP,
                    f"按 name={name!r} 命中了非本会话拥有的进程，拒绝 kill",
                    {
                        "pids": [raw.pid for raw in external],
                        "cmdlines": [raw.cmdline for raw in external],
                    },
                )

        stopped: list[dict] = []
        for item in victims:
            # 与 kill_by_role 相同的三元组校验：只杀本会话真正拥有的进程
            if not process_mod.is_owned(item.pid):
                process_mod.forget_owned(item.pid)
                continue
            process_mod.terminate(item.pid, tree=True)
            process_mod.forget_owned(item.pid)
            stopped.append({"role": item.role, "pid": item.pid})

        return ToolResult({"stopped": stopped, "count": len(stopped)})

    # ---- input ----

    def _do_input(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        rig = _get_rig()

        if action == "cmd":
            result = rig.cmd(
                args["text"],
                target=args.get("target"),
                wait_match=args.get("waitMatch"),
                timeout_ms=args.get("timeoutMs", 5000),
            )
            return ToolResult(_ensure_rig_dict(result))

        if action == "key":
            result = rig.key(args["key"], target=args.get("target"))
            return ToolResult(_ensure_rig_dict(result))

        # action == "hold"
        result = rig.hold(
            args["key"], float(args["seconds"]), target=args.get("target")
        )
        return ToolResult(_ensure_rig_dict(result))

    # ---- capture ----

    def _do_capture(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        if action == "shot":
            rig = _get_rig()
            shot_path = args.get("path")
            result = rig.shot(
                path=str(_resolve_path(shot_path)) if shot_path else None,
                target=args.get("target"),
                region=args.get("region"),
            )
            return ToolResult(_ensure_rig_dict(result))

        # action == "pixels"
        path = _resolve_path(args["path"])
        mode = args["mode"]
        threshold = args.get("threshold")

        if mode == "clusters":
            kwargs: dict[str, Any] = {}
            if threshold is not None:
                kwargs["threshold"] = threshold
            clusters = pixels_mod.clusters(
                path, band=args.get("band"), **kwargs
            )
            return ToolResult(
                {
                    "mode": "clusters",
                    "path": str(path),
                    "band": args.get("band"),
                    "threshold": threshold if threshold is not None else 190,
                    "count": len(clusters),
                    "clusters": clusters,
                }
            )

        compare = _resolve_path(args["compare"])
        kwargs = {}
        if threshold is not None:
            kwargs["threshold"] = threshold
        data = pixels_mod.diff(path, compare, **kwargs)
        return ToolResult(
            {
                "mode": "diff",
                "path": str(path),
                "compare": str(compare),
                "threshold": threshold if threshold is not None else 60,
                **data,
            }
        )

    # ---- log ----

    def _do_log(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        rig = _get_rig()

        if action == "tail":
            lines = args.get("lines", 200)
            target = args.get("target")
            text = rig.log_tail(lines=lines, target=target) or ""
            return ToolResult(
                {
                    "target": target,
                    "requestedLines": lines,
                    "lineCount": len(text.splitlines()),
                    "text": text,
                },
                text=text,
            )

        if action == "wait":
            result = rig.log_wait(
                args["pattern"],
                timeout_ms=args.get("timeoutMs", 5000),
                target=args.get("target"),
            )
            return ToolResult(_ensure_rig_dict(result))

        # action == "trace"
        lines = args.get("lines", 2000)
        pattern = args.get("pattern")
        text = rig.log_tail(lines=lines) or ""
        if pattern is not None:
            try:
                regex = re.compile(pattern)
            except re.error as exc:
                raise IcdError(
                    E_INVALID_ARGUMENT,
                    f"正则不合法：{pattern}",
                    {"pattern": pattern, "error": str(exc)},
                ) from exc
            filtered = "\n".join(
                row for row in text.splitlines() if regex.search(row)
            )
        else:
            filtered = text

        out_path = args.get("out")
        structured: dict[str, Any] = {
            "pattern": pattern,
            "requestedLines": lines,
            "matchCount": len(filtered.splitlines()) if filtered else 0,
            "out": None,
        }
        if out_path is not None:
            target_path = _resolve_path(out_path)
            try:
                target_path.parent.mkdir(parents=True, exist_ok=True)
                payload = filtered if (not filtered or filtered.endswith("\n")) else filtered + "\n"
                target_path.write_text(payload, encoding="utf-8", newline="\n")
            except OSError as exc:
                raise IcdError(
                    E_INVALID_ARGUMENT,
                    f"写出过滤结果失败：{target_path}",
                    {"path": str(target_path), "error": str(exc)},
                ) from exc
            structured["out"] = str(target_path)

        return ToolResult(structured, text=filtered)

    # ---- config ----

    def _do_config(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        run_dir = _target_run_dir(args.get("target"))
        key = args["key"]

        if action == "get":
            return ToolResult(
                {
                    "key": key,
                    "value": config_mod.get(run_dir, key),
                    "target": args.get("target"),
                    "runDir": str(run_dir),
                }
            )

        # action == "set"
        old = config_mod.set(run_dir, key, args.get("value"))
        return ToolResult(
            {
                "key": key,
                "oldValue": old,
                "value": args.get("value"),
                "target": args.get("target"),
                "runDir": str(run_dir),
            }
        )

    # ---- snapshot ----

    def _do_snapshot(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        if action == "list":
            snapshots = snapshot_mod.list_snapshots()
            return ToolResult(
                {
                    "root": str(SNAPSHOT_ROOT),
                    "count": len(snapshots),
                    "snapshots": snapshots,
                }
            )

        if action == "restore":
            data = snapshot_mod.restore(args["name"])
            return ToolResult({"name": args["name"], **data})

        # action == "create"
        raw_targets = args.get("targets")
        targets = (
            [Path(item) for item in raw_targets]
            if raw_targets is not None
            else _default_snapshot_targets()
        )
        data = snapshot_mod.create(args["name"], targets)
        return ToolResult(data)

    # ---- rcon ----

    def _do_rcon(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        rig = _get_rig()
        response = rig.rcon(
            args["command"], timeout_ms=args.get("timeoutMs", 10000)
        )
        return ToolResult(
            {"command": args["command"], "response": response},
            text=response,
        )

    # ---- diag ----

    def _do_diag(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        run_dir = _diag_run_dir()

        if action == "manifest":
            path = diag_mod.write_manifest(
                run_dir, args["cases"], session=args.get("session")
            )
            return ToolResult(
                {
                    "runDir": str(run_dir),
                    "path": str(path),
                    "session": args.get("session"),
                    "cases": len(args["cases"]),
                }
            )

        if action == "sessions":
            limit = args.get("limit", 5)
            items = diag_mod.list_sessions(run_dir, limit=limit)
            return ToolResult(
                {"runDir": str(run_dir), "count": len(items), "sessions": items}
            )

        if action == "perf":
            session_dir = _session_dir(run_dir, args.get("session"))
            summaries = diag_mod.read_summaries(session_dir)
            return ToolResult(
                {
                    "runDir": str(run_dir),
                    "session": session_dir.name,
                    "path": str(session_dir),
                    "count": len(summaries),
                    "summaries": summaries,
                }
            )

        # action == "analyze"
        session_dir = _resolve_path(args["sessionDir"])
        return ToolResult(diag_mod.analyze(session_dir))

    # ---- info ----

    def _do_info(self, action: str, args: dict, ctx: CallContext) -> ToolResult:
        if action == "doctor":
            return ToolResult(info_mod.doctor())
        return ToolResult(info_mod.where())

    # ---- 关闭 ----

    def _shutdown(self) -> None:
        with self._state_lock:
            if self._shutting_down:
                return
            self._shutting_down = True
            events = list(self._cancel_events.values())
            threads = list(self._threads)

        self._notify_message("info", "icdev server 关闭：取消在途请求并回收装置")

        for event in events:
            event.set()

        deadline = time.monotonic() + 30.0
        for thread in threads:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            thread.join(remaining)

        still_alive = [thread.name for thread in threads if thread.is_alive()]
        if still_alive:
            self._write_stderr(f"[icdev] 关闭时仍有工作线程未退出：{still_alive}")

        if self._rig_active:
            try:
                _get_rig().down(force=False)
            except Exception as exc:  # noqa: BLE001 - 退出路径尽力清理
                self._write_stderr(f"[icdev] 退出时 rig.down 失败：{exc}")
            self._rig_active = False

        try:
            from . import rcon as rcon_mod

            rcon_mod.close_all()
        except Exception:  # noqa: BLE001 - 关闭连接池失败不影响退出
            pass

        self._closed.set()


# ------------------------------------------------------------------ selftest


def _parse_stdout_lines(raw: str) -> list[dict]:
    messages: list[dict] = []
    for index, line in enumerate(raw.splitlines(), 1):
        if not line.strip():
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            raise AssertionError(
                f"stdout 第 {index} 行不是合法 JSON（{exc}）：{line[:200]!r}"
            ) from exc
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            raise AssertionError(f"stdout 第 {index} 行不是 JSON-RPC 2.0 对象：{line[:200]!r}")
        messages.append(message)
    return messages


def _selftest() -> int:
    """内存管道跑一遍关键协议路径；结果写 stderr，返回进程退出码。"""
    requests = [
        # 0: 初始化前调用 → -32002
        {"jsonrpc": "2.0", "id": 0, "method": "tools/call",
         "params": {"name": "rig", "arguments": {"action": "status"}}},
        # 1: initialize
        {"jsonrpc": "2.0", "id": 1, "method": "initialize",
         "params": {
             "protocolVersion": PROTOCOL_VERSION,
             "capabilities": {},
             "clientInfo": {"name": "selftest", "version": "0"},
         }},
        # 2: initialized（通知）
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        # 3: tools/list
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        # 4: ping
        {"jsonrpc": "2.0", "id": 3, "method": "ping"},
        # 5: 未知工具
        {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
         "params": {"name": "nope", "arguments": {"action": "x"}}},
    ]
    stdin = io.StringIO(
        "\n".join(json.dumps(item, ensure_ascii=False) for item in requests)
        + "\n{ 这不是 JSON\n"
    )
    stdout = io.StringIO()
    stderr = io.StringIO()

    try:
        server = Server(stdin=stdin, stdout=stdout, stderr=stderr)
        server.run()

        messages = _parse_stdout_lines(stdout.getvalue())

        def by_id(request_id: Any) -> dict:
            matches = [
                item
                for item in messages
                if item.get("id") == request_id and ("result" in item or "error" in item)
            ]
            if len(matches) != 1:
                raise AssertionError(
                    f"id={request_id!r} 应恰好有 1 条响应，实际 {len(matches)}"
                )
            return matches[0]

        pre_init = by_id(0)
        if pre_init.get("error", {}).get("code") != NOT_INITIALIZED:
            raise AssertionError(f"初始化前 tools/call 应返回 {NOT_INITIALIZED}: {pre_init}")

        init = by_id(1).get("result") or {}
        if init.get("protocolVersion") != PROTOCOL_VERSION:
            raise AssertionError(f"protocolVersion 错误: {init.get('protocolVersion')!r}")
        if init.get("capabilities", {}).get("tools") is None:
            raise AssertionError("capabilities.tools 缺失")
        if init.get("capabilities", {}).get("logging") is None:
            raise AssertionError("capabilities.logging 缺失")
        if init.get("serverInfo", {}).get("name") != SERVER_NAME:
            raise AssertionError(f"serverInfo 错误: {init.get('serverInfo')!r}")

        tools = by_id(2).get("result", {}).get("tools")
        if not isinstance(tools, list) or len(tools) != 10:
            raise AssertionError(f"tools/list 应返回 10 个工具，实际: {type(tools)} {tools!r}")
        names = [tool.get("name") for tool in tools]
        if names != TOOL_NAMES:
            raise AssertionError(f"工具顺序/名称不符: {names}")

        for tool in tools:
            for field_name in ("name", "title", "description", "inputSchema", "annotations"):
                if field_name not in tool:
                    raise AssertionError(f"工具 {tool.get('name')} 缺少 {field_name}")
            schema = tool["inputSchema"]
            if schema.get("type") != "object":
                raise AssertionError(f"工具 {tool['name']} inputSchema.type != object")
            if schema.get("required") != ["action"]:
                raise AssertionError(f"工具 {tool['name']} required 不是 ['action']")
            if schema.get("additionalProperties") is not False:
                raise AssertionError(f"工具 {tool['name']} 未禁止额外字段")
            enum = schema.get("properties", {}).get("action", {}).get("enum") or []
            expected = sorted(a for (tool_name, a) in _SPECS if tool_name == tool["name"])
            if sorted(enum) != expected:
                raise AssertionError(
                    f"工具 {tool['name']} action 枚举与实现不一致: {enum} vs {expected}"
                )
            annotations = tool.get("annotations")
            if not isinstance(annotations, dict):
                raise AssertionError(f"工具 {tool['name']} annotations 不是对象")

        ping = by_id(3)
        if ping.get("result") != {} or "error" in ping:
            raise AssertionError(f"ping 应返回 {{}}: {ping}")

        unknown = by_id(4)
        error = unknown.get("error") or {}
        if error.get("code") != INVALID_PARAMS:
            raise AssertionError(f"未知工具应返回 {INVALID_PARAMS}: {unknown}")
        if not str(error.get("message", "")).startswith("Invalid params: "):
            raise AssertionError(f"未知工具错误消息格式不符: {error.get('message')!r}")

        if "非法 JSON" not in stderr.getvalue():
            raise AssertionError("非法 JSON 行未按约定记录到 stderr")

        summary = [
            f"[selftest] initialize: protocolVersion={PROTOCOL_VERSION}, "
            "capabilities.tools={}, capabilities.logging={}",
            f"[selftest] tools/list: {len(tools)} tools, inputSchema.type=object",
            f"[selftest] 初始化前 tools/call: {NOT_INITIALIZED}",
            "[selftest] ping: {}",
            f"[selftest] 未知工具: {INVALID_PARAMS} Invalid params",
            "[selftest] 非法 JSON 行: 已忽略并写 stderr",
            f"[selftest] stdout: {len(messages)} 条消息，每行均可 json.loads",
        ]
        for line in summary:
            sys.stderr.write(line + "\n")
        sys.stderr.write("[selftest] PASS\n")
        sys.stderr.flush()
        return 0
    except Exception as exc:  # noqa: BLE001 - 自检失败要给出可读原因
        sys.stderr.write(f"[selftest] FAIL: {exc}\n")
        detail = stderr.getvalue()
        if detail:
            sys.stderr.write("[selftest] server stderr:\n" + detail)
        sys.stderr.flush()
        return 1


# ------------------------------------------------------------------ 入口


def _configure_logging() -> None:
    root = logging.getLogger()
    if not root.handlers:
        handler = logging.StreamHandler(sys.stderr)
        handler.setFormatter(
            logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
        )
        root.addHandler(handler)
    root.setLevel(logging.INFO)


def _force_utf8_stdio() -> None:
    streams = (
        (sys.stdin, None),
        (sys.stdout, "\n"),
        (sys.stderr, None),
    )
    for stream, newline in streams:
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            if newline is None:
                reconfigure(encoding="utf-8", errors="replace")
            else:
                reconfigure(encoding="utf-8", errors="replace", newline=newline)
        except (ValueError, OSError):
            pass


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m icdev.server",
        description="icdev MCP server（stdio，JSON-RPC 2025-06-18）",
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="用内存管道跑 initialize/tools/list/tools-call 自检后退出",
    )
    args = parser.parse_args(argv)

    _force_utf8_stdio()
    _configure_logging()

    if args.selftest:
        return _selftest()

    return Server().run()


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
