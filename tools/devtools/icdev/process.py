"""java 进程枚举、所有权三元组、端口探测与终止。

设计约束（MCP-DESIGN.md §五 D5 / §八 8.3）：
- 所有权 = ``(pid, 创建时间, cmdline 哈希)`` 三元组，逐项比对，任一不符即「不是我的」；
- ``role_of`` **只**认 ``KnotServer`` / ``KnotClient``。历史上的误杀根因是按
  ``devlaunchinjector`` 前缀过滤——服务端和客户端共用那个前缀，绝不能再看它；
- 枚举走 PowerShell ``Get-CimInstance``（避免 psutil 依赖），结果缓存 2 s；
- 模块内不打印，日志走 ``icdev.process``；失败抛 ``IcdError``，不依赖游戏进程即可导入。
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from .errors import (
    E_INVALID_ARGUMENT,
    E_NOT_FOUND,
    E_PROCESS_FAILED,
    E_TIMEOUT,
    E_UNSUPPORTED,
    IcdError,
)

log = logging.getLogger("icdev.process")

IS_WINDOWS = sys.platform == "win32"

CACHE_TTL = 2.0
PS_TIMEOUT = 20.0

# 只按这两个类名判角色；不要再加 devlaunchinjector（见模块 docstring）
ROLE_MARKERS: tuple[tuple[str, str], ...] = (
    ("KnotServer", "server"),
    ("KnotClient", "client"),
)

_PS_SCRIPT = (
    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" "
    "| Select-Object ProcessId,CreationDate,CommandLine | ConvertTo-Json -Compress"
)
_PS_ARGS = ["powershell", "-NoProfile", "-Command", _PS_SCRIPT]

_DOTNET_EPOCH = re.compile(r"^/Date\((-?\d+)(?:[+-]\d{4})?\)/$")


@dataclass(frozen=True)
class RawProc:
    """一条 java 进程记录。"""

    pid: int
    created_at: str
    cmdline: str


@dataclass(frozen=True)
class Owned:
    """本装置认领的进程：登记时的三元组快照。"""

    role: str
    pid: int
    created_at: str
    cmdline_hash: str


# ---------------------------------------------------------------- 通用执行

def _decode(raw: bytes) -> str:
    """PowerShell 重定向输出的编码随控制台代码页变（通常是 ANSI），逐级回退。"""
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    for enc in ("utf-8", "mbcs", "cp1252"):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", "replace")


def _run(args: list[str], *, timeout: float, what: str) -> str:
    try:
        proc = subprocess.run(args, capture_output=True, timeout=timeout, check=False)
    except FileNotFoundError as exc:
        raise IcdError(E_UNSUPPORTED, f"{what} 不可用：找不到可执行文件 {args[0]}", {"cmd": args}) from exc
    except subprocess.TimeoutExpired as exc:
        raise IcdError(E_TIMEOUT, f"{what} 超时（{timeout:.0f}s）", {"cmd": args}) from exc
    except OSError as exc:
        raise IcdError(E_PROCESS_FAILED, f"{what} 启动失败：{exc}", {"cmd": args}) from exc

    out = _decode(proc.stdout or b"")
    if proc.returncode != 0:
        err = _decode(proc.stderr or b"").strip()
        raise IcdError(
            E_PROCESS_FAILED,
            f"{what} 失败（exit={proc.returncode}）：{err[:400] or '无 stderr'}",
            {"cmd": args, "exit": proc.returncode, "stderr": err[:2000]},
        )
    return out


# ---------------------------------------------------------------- 进程枚举

_cache_lock = threading.Lock()
_cache: dict[str, object] = {"at": 0.0, "items": None}


def _normalize_created(value: object) -> str:
    """``/Date(ms)/``（PS 5.1）→ ISO；ISO 字符串（PS 7）原样保留。"""
    if value is None:
        return ""
    text = str(value).strip()
    match = _DOTNET_EPOCH.match(text)
    if match:
        try:
            ms = int(match.group(1))
            return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc).isoformat()
        except (ValueError, OverflowError, OSError):
            return text
    return text


def _query_java_processes() -> list[RawProc]:
    text = _run(_PS_ARGS, timeout=PS_TIMEOUT, what="进程枚举（Get-CimInstance）").strip()
    if not text or text == "null":
        return []
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        raise IcdError(
            E_PROCESS_FAILED,
            f"进程枚举输出不是合法 JSON：{exc}",
            {"head": text[:500]},
        ) from exc

    if isinstance(data, dict):  # 只有一个 java 进程时 ConvertTo-Json 不输出数组
        data = [data]
    if not isinstance(data, list):
        raise IcdError(E_PROCESS_FAILED, "进程枚举输出结构异常", {"type": type(data).__name__})

    items: list[RawProc] = []
    for row in data:
        if not isinstance(row, dict):
            continue
        try:
            pid = int(row.get("ProcessId"))
        except (TypeError, ValueError):
            continue
        items.append(
            RawProc(
                pid=pid,
                created_at=_normalize_created(row.get("CreationDate")),
                cmdline=str(row.get("CommandLine") or ""),
            )
        )
    items.sort(key=lambda p: p.pid)
    log.debug("枚举到 %d 个 java 进程", len(items))
    return items


def list_java_processes(use_cache: bool = True) -> list[RawProc]:
    """当前 ``java.exe`` 进程列表（缓存 2 s；没有 java 进程时返回空列表）。"""
    if not IS_WINDOWS:
        raise IcdError(E_UNSUPPORTED, "进程枚举只支持 Windows", {"platform": sys.platform})

    now = time.monotonic()
    with _cache_lock:
        cached = _cache["items"]
        if use_cache and cached is not None and now - float(_cache["at"]) < CACHE_TTL:
            return list(cached)  # type: ignore[arg-type]

    items = _query_java_processes()
    with _cache_lock:
        _cache["at"] = time.monotonic()
        _cache["items"] = items
    return list(items)


# ---------------------------------------------------------------- 角色与哈希

def role_of(cmdline: str) -> Optional[str]:
    """从命令行判角色：``KnotServer`` → server，``KnotClient`` → client，其余 → None。

    只认这两个类名；``devlaunchinjector`` 同时出现在服务端和客户端里，**不可用于判角色**。
    """
    if not cmdline:
        return None
    for marker, role in ROLE_MARKERS:
        if marker in cmdline:
            return role
    return None


def cmdline_hash(cmdline: str) -> str:
    """命令行指纹：sha1 前 16 位（够区分，又不至于把完整路径写进日志）。"""
    digest = hashlib.sha1((cmdline or "").encode("utf-8", "replace")).hexdigest()
    return digest[:16]


# ---------------------------------------------------------------- 所有权

_owned: dict[int, Owned] = {}
_owned_lock = threading.Lock()


def _raw_for(pid: int, use_cache: bool) -> Optional[RawProc]:
    for item in list_java_processes(use_cache=use_cache):
        if item.pid == int(pid):
            return item
    return None


def register_owned(role: str, pid: int) -> Owned:
    """登记一个由本会话 spawn 的进程，记录三元组快照。"""
    if not isinstance(role, str) or not role:
        raise IcdError(E_INVALID_ARGUMENT, "role 必须是非空字符串", {"role": repr(role)})

    raw = _raw_for(pid, use_cache=False)
    if raw is None:
        raise IcdError(
            E_NOT_FOUND,
            f"进程 {pid} 不在 java 进程列表里，无法登记所有权",
            {"pid": pid, "role": role},
        )

    derived = role_of(raw.cmdline)
    if derived is None:
        log.warning("pid=%s 命令行里没有 KnotServer/KnotClient，仍按 role=%s 登记", pid, role)
    elif role in ("server", "client") and role != derived:
        # 角色名允许是 clientA/clientB 之类的细分标签；只有简单的 server/client 才做交叉校验
        log.warning("pid=%s 的 role=%s 与命令行推导的 %s 不一致（以调用方为准）", pid, role, derived)

    owned = Owned(
        role=role,
        pid=int(pid),
        created_at=raw.created_at,
        cmdline_hash=cmdline_hash(raw.cmdline),
    )
    with _owned_lock:
        _owned[int(pid)] = owned
    log.info("登记所有权 role=%s pid=%s created_at=%s hash=%s", role, pid, raw.created_at, owned.cmdline_hash)
    return owned


def owned_processes() -> list[Owned]:
    """本会话登记过的进程（按 pid 排序）。"""
    with _owned_lock:
        items = list(_owned.values())
    return sorted(items, key=lambda o: o.pid)


def is_owned(pid: int) -> bool:
    """三元组逐项比对：pid 在登记表 + 创建时间一致 + cmdline 哈希一致。

    任何一项不符（或进程已消失）都返回 False——宁可少杀，不可错杀。
    """
    with _owned_lock:
        owned = _owned.get(int(pid))
    if owned is None:
        return False

    try:
        raw = _raw_for(pid, use_cache=False)
    except IcdError:
        log.warning("校验 pid=%s 所有权时进程枚举失败，按「不是我的」处理", pid)
        return False
    if raw is None:
        return False

    ok = raw.created_at == owned.created_at and cmdline_hash(raw.cmdline) == owned.cmdline_hash
    if not ok:
        log.warning(
            "pid=%s 三元组不匹配（created_at %s/%s, hash %s/%s），视为非自有进程",
            pid,
            raw.created_at,
            owned.created_at,
            cmdline_hash(raw.cmdline),
            owned.cmdline_hash,
        )
    return ok


def forget_owned(pid: int) -> None:
    """丢掉登记（进程已退出或不属于我们时调用；不存在也不报错）。"""
    with _owned_lock:
        _owned.pop(int(pid), None)


# ---------------------------------------------------------------- 终止

def _pid_alive(pid: int) -> bool:
    if not IS_WINDOWS:
        return False
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL

    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    STILL_ACTIVE = 259

    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
    if not handle:
        return False
    try:
        code = wintypes.DWORD(0)
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
            return False
        return code.value == STILL_ACTIVE
    finally:
        kernel32.CloseHandle(handle)


def terminate(pid: int, tree: bool = True) -> None:
    """``taskkill /T /F`` 结束整棵进程树；进程已退出视为成功。"""
    if not IS_WINDOWS:
        raise IcdError(E_UNSUPPORTED, "进程终止只支持 Windows", {"platform": sys.platform})

    args = ["taskkill", "/PID", str(int(pid))]
    if tree:
        args.append("/T")
    args.append("/F")

    try:
        proc = subprocess.run(args, capture_output=True, timeout=20.0, check=False)
    except FileNotFoundError as exc:
        raise IcdError(E_UNSUPPORTED, "taskkill 不可用", {"cmd": args}) from exc
    except subprocess.TimeoutExpired as exc:
        raise IcdError(E_TIMEOUT, f"taskkill 超时：pid={pid}", {"pid": pid}) from exc
    except OSError as exc:
        raise IcdError(E_PROCESS_FAILED, f"taskkill 启动失败：{exc}", {"pid": pid}) from exc

    if proc.returncode != 0:
        err = _decode(proc.stderr or b"").strip()
        if not _pid_alive(pid):
            log.debug("pid=%s 已退出，taskkill 返回 %s（视为成功）", pid, proc.returncode)
            return
        raise IcdError(
            E_PROCESS_FAILED,
            f"终止进程失败：pid={pid}（exit={proc.returncode}）：{err[:300]}",
            {"pid": pid, "exit": proc.returncode, "stderr": err[:2000]},
        )
    log.info("已终止 pid=%s（tree=%s）", pid, tree)


# ---------------------------------------------------------------- 端口

_TCP_PORT = re.compile(r":(\d+)$")


def port_listening(port: int) -> bool:
    """本地是否有 TCP 端口处于 LISTENING（netstat -ano 解析）。"""
    if not IS_WINDOWS:
        raise IcdError(E_UNSUPPORTED, "端口探测只支持 Windows", {"platform": sys.platform})
    port = int(port)
    if not 1 <= port <= 65535:
        raise IcdError(E_INVALID_ARGUMENT, f"端口越界：{port}", {"port": port})

    text = _run(["netstat", "-ano"], timeout=15.0, what="netstat")
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 4 or parts[0].upper() != "TCP":
            continue
        if parts[3].upper() != "LISTENING":
            continue
        match = _TCP_PORT.search(parts[1])
        if match and int(match.group(1)) == port:
            return True
    return False


# ---------------------------------------------------------------- 按角色清理

def kill_by_role(role: str) -> list[int]:
    """终止本会话登记且角色匹配的进程，返回实际终止的 pid 列表。

    只动登记表里的进程，且终止前重新校验三元组：外部手工起的 KnotServer/KnotClient
    永远不会被这里碰到（误杀防护）。
    """
    victims = [o for o in owned_processes() if o.role == role]
    stopped: list[int] = []
    for owned in victims:
        if not is_owned(owned.pid):
            log.warning("跳过 pid=%s（role=%s）：三元组不匹配或进程已退出", owned.pid, owned.role)
            forget_owned(owned.pid)
            continue
        terminate(owned.pid, tree=True)
        forget_owned(owned.pid)
        stopped.append(owned.pid)
    return stopped
