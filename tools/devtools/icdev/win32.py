"""Win32 窗口枚举、定向输入（PostMessage）与 Job Object。

设计约束（MCP-DESIGN.md §五 D8 / §八）：
- 只用 ctypes（user32 / kernel32），不依赖 pyautogui；
- 输入走 ``PostMessage``，**不抢焦点、不移动鼠标**，窗口被遮挡也能工作；
- 非 Windows 平台抛 ``E_UNSUPPORTED``，但模块本身仍可导入；
- 模块内不打印，日志走 ``icdev.win32``。
"""

from __future__ import annotations

import ctypes
import logging
import sys
import time
from ctypes import wintypes
from dataclasses import dataclass
from typing import Optional

from .errors import E_INVALID_ARGUMENT, E_NO_WINDOW, E_PROCESS_FAILED, E_UNSUPPORTED, IcdError

log = logging.getLogger("icdev.win32")

IS_WINDOWS = sys.platform == "win32"

WM_KEYDOWN, WM_KEYUP, WM_CHAR = 0x100, 0x101, 0x102
SW_MAXIMIZE = 3

# 虚拟键码：命名键 + 字母 + 数字（与旧 devtools.py 的 VK 表保持一致）
VK: dict[str, int] = {
    "backspace": 0x08,
    "tab": 0x09,
    "enter": 0x0D,
    "return": 0x0D,
    "esc": 0x1B,
    "escape": 0x1B,
    "space": 0x20,
    "end": 0x23,
    "home": 0x24,
    "left": 0x25,
    "up": 0x26,
    "right": 0x27,
    "down": 0x28,
    "delete": 0x2E,
    "del": 0x2E,
    "slash": 0xBF,
    **{f"f{i}": 0x6F + i for i in range(1, 13)},
    **{c: ord(c.upper()) for c in "abcdefghijklmnopqrstuvwxyz"},
    **{c: ord(c) for c in "0123456789"},
}


# ---------------------------------------------------------------- Win32 句柄

if IS_WINDOWS:  # pragma: no cover - 平台分支，Windows 上恒真
    WNDENUMPROC = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    _user32 = ctypes.WinDLL("user32", use_last_error=True)
    _kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

    _user32.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]
    _user32.PostMessageW.restype = wintypes.BOOL
    _user32.MapVirtualKeyW.argtypes = [wintypes.UINT, wintypes.UINT]
    _user32.MapVirtualKeyW.restype = wintypes.UINT
    _user32.EnumWindows.argtypes = [WNDENUMPROC, wintypes.LPARAM]
    _user32.EnumWindows.restype = wintypes.BOOL
    _user32.GetWindowTextLengthW.argtypes = [wintypes.HWND]
    _user32.GetWindowTextLengthW.restype = ctypes.c_int
    _user32.GetWindowTextW.argtypes = [wintypes.HWND, wintypes.LPWSTR, ctypes.c_int]
    _user32.GetWindowTextW.restype = ctypes.c_int
    _user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    _user32.GetWindowThreadProcessId.restype = wintypes.DWORD
    _user32.GetWindowRect.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.RECT)]
    _user32.GetWindowRect.restype = wintypes.BOOL
    _user32.IsWindow.argtypes = [wintypes.HWND]
    _user32.IsWindow.restype = wintypes.BOOL
    _user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    _user32.ShowWindow.restype = wintypes.BOOL

    _kernel32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    _kernel32.CreateJobObjectW.restype = wintypes.HANDLE
    _kernel32.SetInformationJobObject.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    _kernel32.SetInformationJobObject.restype = wintypes.BOOL
    _kernel32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
    _kernel32.AssignProcessToJobObject.restype = wintypes.BOOL
    _kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    _kernel32.OpenProcess.restype = wintypes.HANDLE
    _kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    _kernel32.CloseHandle.restype = wintypes.BOOL
else:  # pragma: no cover - 非 Windows
    _user32 = None
    _kernel32 = None


def _require_win32(what: str) -> None:
    if not IS_WINDOWS:
        raise IcdError(E_UNSUPPORTED, f"{what} 只支持 Windows", {"platform": sys.platform})


# ---------------------------------------------------------------- 窗口

@dataclass(frozen=True)
class Window:
    """一个顶层窗口的不可变快照。"""

    hwnd: int
    pid: int
    title: str
    x: int
    y: int
    width: int
    height: int


def _window_title(hwnd: int) -> str:
    length = _user32.GetWindowTextLengthW(wintypes.HWND(hwnd))
    if length <= 0:
        return ""
    buf = ctypes.create_unicode_buffer(length + 1)
    _user32.GetWindowTextW(wintypes.HWND(hwnd), buf, length + 1)
    return buf.value


def _window_pid(hwnd: int) -> int:
    pid = wintypes.DWORD(0)
    _user32.GetWindowThreadProcessId(wintypes.HWND(hwnd), ctypes.byref(pid))
    return int(pid.value)


def _window_rect(hwnd: int) -> tuple[int, int, int, int]:
    rect = wintypes.RECT()
    if not _user32.GetWindowRect(wintypes.HWND(hwnd), ctypes.byref(rect)):
        return (0, 0, 0, 0)
    return (
        int(rect.left),
        int(rect.top),
        int(rect.right - rect.left),
        int(rect.bottom - rect.top),
    )


def _snapshot(hwnd: int, title: Optional[str] = None) -> Window:
    if title is None:
        title = _window_title(hwnd)
    x, y, w, h = _window_rect(hwnd)
    return Window(hwnd=int(hwnd), pid=_window_pid(hwnd), title=title, x=x, y=y, width=w, height=h)


def list_minecraft_windows() -> list[Window]:
    """枚举标题含 ``Minecraft`` 的顶层窗口（按 EnumWindows 的 Z 序）。"""
    _require_win32("窗口枚举")

    found: list[Window] = []

    def _enum(hwnd, _lparam) -> bool:
        title = _window_title(int(hwnd))
        if "Minecraft" in title:
            found.append(_snapshot(int(hwnd), title))
        return True

    callback = WNDENUMPROC(_enum)
    if not _user32.EnumWindows(callback, 0):
        err = ctypes.get_last_error()
        # EnumWindows 只有回调返回 False 时才返回 0；回调恒返回 True，出错才走到这里
        raise IcdError(E_PROCESS_FAILED, "EnumWindows 失败", {"winerror": err})

    log.debug("枚举到 %d 个 Minecraft 窗口", len(found))
    return found


def find_window(pid: Optional[int] = None) -> Window:
    """按 pid 找窗口；pid 为 None 时要求全屏只有一个 Minecraft 窗口。

    - 一个都没有 → ``E_NO_WINDOW``
    - 多个且没给 pid → ``E_INVALID_ARGUMENT``（附候选列表，调用方必须指定 pid）
    """
    wins = list_minecraft_windows()
    if pid is not None:
        wins = [w for w in wins if w.pid == int(pid)]

    if not wins:
        raise IcdError(
            E_NO_WINDOW,
            "没找到 Minecraft 窗口" + (f"（pid={pid}）" if pid is not None else "") + "：客户端起了吗？",
            {"pid": pid},
        )
    if len(wins) > 1:
        detail = [{"pid": w.pid, "hwnd": w.hwnd, "title": w.title} for w in wins]
        log.error("找到多个 Minecraft 窗口：%s", detail)
        raise IcdError(E_INVALID_ARGUMENT, "找到多个 Minecraft 窗口，必须用 pid 指定目标", detail)
    return wins[0]


def maximize(win: Window) -> None:
    """最大化窗口（客户区撑满工作区；用于截图分区稳定）。"""
    _require_win32("窗口最大化")
    if not _user32.IsWindow(wintypes.HWND(win.hwnd)):
        raise IcdError(E_NO_WINDOW, f"窗口已失效（hwnd={win.hwnd}）", {"hwnd": win.hwnd})
    _user32.ShowWindow(wintypes.HWND(win.hwnd), SW_MAXIMIZE)
    log.debug("最大化窗口 hwnd=%s pid=%s", win.hwnd, win.pid)


# ---------------------------------------------------------------- 键盘输入

def vk_for(key: str) -> int:
    """键名 → 虚拟键码；未知键名抛 ``E_INVALID_ARGUMENT``。"""
    if not isinstance(key, str):
        raise IcdError(E_INVALID_ARGUMENT, "键名必须是字符串", {"key": repr(key)})
    code = VK.get(key.lower())
    if code is None:
        raise IcdError(
            E_INVALID_ARGUMENT,
            f"未知键名：{key!r}（支持字母/数字/f1..f12/esc/enter/space/tab 等）",
            {"key": key, "supported": sorted(VK)},
        )
    return code


def _post(win: Window, msg: int, wparam: int, lparam: int) -> None:
    if not _user32.PostMessageW(wintypes.HWND(win.hwnd), msg, wparam, lparam):
        err = ctypes.get_last_error()
        raise IcdError(
            E_PROCESS_FAILED,
            f"PostMessage 失败（hwnd={win.hwnd}, msg=0x{msg:04X}）",
            {"hwnd": win.hwnd, "winerror": err},
        )


def send_key_down(win: Window, vk: int) -> None:
    """按下（不发抬起）：长按用 down → sleep → up。"""
    _require_win32("键盘输入")
    scan = _user32.MapVirtualKeyW(int(vk), 0)
    _post(win, WM_KEYDOWN, int(vk), 1 | (scan << 16))


def send_key_up(win: Window, vk: int) -> None:
    _require_win32("键盘输入")
    scan = _user32.MapVirtualKeyW(int(vk), 0)
    _post(win, WM_KEYUP, int(vk), 1 | (scan << 16) | (1 << 30) | (1 << 31))


def send_key(win: Window, key: str, delay: float = 0.01) -> None:
    """单击一个键（按下 → delay → 抬起）。"""
    vk = vk_for(key)
    send_key_down(win, vk)
    if delay:
        time.sleep(delay)
    send_key_up(win, vk)


def send_text(win: Window, text: str) -> None:
    """逐字符 WM_CHAR；不经过剪贴板、不受输入法影响。"""
    _require_win32("键盘输入")
    for ch in text:
        _post(win, WM_CHAR, ord(ch), 1)
        time.sleep(0.005)


def open_chat_and_type(win: Window, text: str) -> None:
    """打开聊天栏 → 清空残留 → 输入 → 回车。

    清空逻辑照搬旧实现：t（打开聊天）→ End（跳到末尾）→ 320 次退格（抹掉上次残留）
    → 等 0.15s 让客户端处理完输入事件，否则首批字符会被吞。
    """
    send_key(win, "t")
    time.sleep(0.3)
    send_key(win, "end")
    for _ in range(320):
        send_key(win, "backspace", delay=0)
    time.sleep(0.15)
    send_text(win, text)
    send_key(win, "enter")
    log.debug("已向 hwnd=%s 输入 %d 字符", win.hwnd, len(text))


# ---------------------------------------------------------------- Job Object

JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000
JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9

PROCESS_SET_QUOTA = 0x0100
PROCESS_TERMINATE = 0x0001


class _IO_COUNTERS(ctypes.Structure):
    _fields_ = [
        ("ReadOperationCount", ctypes.c_ulonglong),
        ("WriteOperationCount", ctypes.c_ulonglong),
        ("OtherOperationCount", ctypes.c_ulonglong),
        ("ReadTransferCount", ctypes.c_ulonglong),
        ("WriteTransferCount", ctypes.c_ulonglong),
        ("OtherTransferCount", ctypes.c_ulonglong),
    ]


class _JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_longlong),
        ("PerJobUserTimeLimit", ctypes.c_longlong),
        ("LimitFlags", wintypes.DWORD),
        ("MinimumWorkingSetSize", ctypes.c_size_t),
        ("MaximumWorkingSetSize", ctypes.c_size_t),
        ("ActiveProcessLimit", wintypes.DWORD),
        ("Affinity", ctypes.c_size_t),
        ("PriorityClass", wintypes.DWORD),
        ("SchedulingClass", wintypes.DWORD),
    ]


class _JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("BasicLimitInformation", _JOBOBJECT_BASIC_LIMIT_INFORMATION),
        ("IoInfo", _IO_COUNTERS),
        ("ProcessMemoryLimit", ctypes.c_size_t),
        ("JobMemoryLimit", ctypes.c_size_t),
        ("PeakProcessMemoryUsed", ctypes.c_size_t),
        ("PeakJobMemoryUsed", ctypes.c_size_t),
    ]


class JobObject:
    """Windows Job Object，``KILL_ON_JOB_CLOSE``：句柄一关，整棵子进程树被内核杀掉。

    这是 server 被 SIGKILL 时的兜底（钩子不执行，但进程句柄随进程销毁而释放）。
    """

    def __init__(self) -> None:
        _require_win32("Job Object")
        handle = _kernel32.CreateJobObjectW(None, None)
        if not handle:
            err = ctypes.get_last_error()
            raise IcdError(E_PROCESS_FAILED, "CreateJobObject 失败", {"winerror": err})

        info = _JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        ok = _kernel32.SetInformationJobObject(
            handle,
            JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
            ctypes.byref(info),
            ctypes.sizeof(info),
        )
        if not ok:
            err = ctypes.get_last_error()
            _kernel32.CloseHandle(handle)
            raise IcdError(
                E_PROCESS_FAILED,
                "SetInformationJobObject(KILL_ON_JOB_CLOSE) 失败",
                {"winerror": err},
            )

        self._handle: Optional[int] = handle
        self._pids: set[int] = set()
        log.debug("创建 Job Object handle=%s", handle)

    @property
    def handle(self) -> Optional[int]:
        return self._handle

    @property
    def pids(self) -> set[int]:
        """已加入本 Job 的进程 pid（仅记录，不代表仍存活）。"""
        return set(self._pids)

    def add(self, pid: int) -> None:
        """把进程加入 Job；进程须在加入前尚未退出。"""
        _require_win32("Job Object")
        if self._handle is None:
            raise IcdError(E_PROCESS_FAILED, "Job Object 已关闭")
        hproc = _kernel32.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, False, int(pid))
        if not hproc:
            err = ctypes.get_last_error()
            raise IcdError(
                E_PROCESS_FAILED,
                f"OpenProcess 失败：pid={pid}（进程已退出？）",
                {"pid": pid, "winerror": err},
            )
        try:
            if not _kernel32.AssignProcessToJobObject(self._handle, hproc):
                err = ctypes.get_last_error()
                raise IcdError(
                    E_PROCESS_FAILED,
                    f"AssignProcessToJobObject 失败：pid={pid}",
                    {"pid": pid, "winerror": err},
                )
        finally:
            _kernel32.CloseHandle(hproc)
        self._pids.add(int(pid))
        log.debug("pid=%s 已加入 Job handle=%s", pid, self._handle)

    def close(self) -> None:
        """关闭 Job 句柄（幂等）；关闭即终止 Job 内所有进程。"""
        if self._handle is None:
            return
        handle, self._handle = self._handle, None
        _kernel32.CloseHandle(handle)
        self._pids.clear()
        log.debug("关闭 Job handle=%s", handle)

    def __enter__(self) -> "JobObject":
        return self

    def __exit__(self, *_exc) -> None:
        self.close()

    def __del__(self) -> None:  # pragma: no cover - 解释器退出路径
        try:
            self.close()
        except Exception:  # noqa: BLE001 - 析构期不能再抛
            pass


def create_job() -> JobObject:
    """创建一个 ``KILL_ON_JOB_CLOSE`` 的 Job Object。"""
    return JobObject()
