"""统一错误类型与错误码。

约定：
- 运行期失败（工具执行错误）→ MCP `isError: true` + `structuredContent = IcdError.to_dict()`。
- 未知工具 / 未知 action / 参数类型错 → 由 server.py 转成 JSON-RPC `-32602`，不经过这里。
"""

from __future__ import annotations

from typing import Any


# ---- 错误码（与 MCP-DESIGN.md §十 一一对应）----
E_NOT_READY = "E_NOT_READY"
E_ALREADY_UP = "E_ALREADY_UP"
E_TIMEOUT = "E_TIMEOUT"
E_NO_WINDOW = "E_NO_WINDOW"
E_OWNERSHIP = "E_OWNERSHIP"
E_PORT_IN_USE = "E_PORT_IN_USE"
E_CONFIG_INVALID = "E_CONFIG_INVALID"
E_NO_PLAYER = "E_NO_PLAYER"
E_RCON = "E_RCON"
E_UNSUPPORTED = "E_UNSUPPORTED"
E_NOT_FOUND = "E_NOT_FOUND"
E_INVALID_ACTION = "E_INVALID_ACTION"
E_INVALID_ARGUMENT = "E_INVALID_ARGUMENT"
E_PROCESS_FAILED = "E_PROCESS_FAILED"
E_INTERNAL = "E_INTERNAL"


class IcdError(Exception):
    """带错误码的可序列化异常。"""

    def __init__(self, code: str, message: str, detail: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.detail = detail

    def to_dict(self) -> dict:
        data = {"code": self.code, "message": self.message}
        if self.detail is not None:
            data["detail"] = self.detail
        return data

    def __str__(self) -> str:  # 日志用
        return f"{self.code}: {self.message}"


def ensure(condition: bool, code: str, message: str, detail: Any = None) -> None:
    """条件不成立时抛 IcdError。"""
    if not condition:
        raise IcdError(code, message, detail)
