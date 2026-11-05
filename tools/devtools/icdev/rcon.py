"""RCON 客户端（连接复用）。

协议（Source/Minecraft 变体）：
- 包体 = <request_id:i32><type:i32><body><NUL><NUL>
- 前缀 = <length:i32>（包体字节数，不含前缀）
- type：3 = AUTH，2 = EXECCOMMAND
- 认证失败时服务端回 request_id = -1

所有失败（连接失败、认证失败、超时、对端关闭）统一抛 ``IcdError(E_RCON, ...)``。
"""

from __future__ import annotations

import logging
import socket
import struct
import threading

from .errors import E_RCON, IcdError
from .paths import DEFAULT_RCON_PASSWORD, DEFAULT_RCON_PORT

log = logging.getLogger("icdev.rcon")

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2

# 认证应答前可能夹带的无关包，最多容忍读取次数
_MAX_AUTH_PACKETS = 4


def _pack(req_id: int, req_type: int, body: bytes) -> bytes:
    payload = struct.pack("<ii", req_id, req_type) + body + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


class RconClient:
    """一次 RCON 会话。连接懒建立，``command`` 失败后连接作废并抛错。"""

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = DEFAULT_RCON_PORT,
        password: str = DEFAULT_RCON_PASSWORD,
        timeout: float = 10.0,
    ) -> None:
        self.host = host
        self.port = int(port)
        self.password = password
        self.timeout = float(timeout)
        self._sock: socket.socket | None = None
        self._req_id = 0
        self._lock = threading.Lock()

    # ---- 内部：连接 ----

    def _next_id(self) -> int:
        self._req_id += 1
        if self._req_id > 2_000_000_000:
            self._req_id = 1
        return self._req_id

    def _recv_exact(self, n: int) -> bytes:
        assert self._sock is not None
        buf = b""
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("连接被对端关闭")
            buf += chunk
        return buf

    def _read_packet(self) -> tuple[int, int, bytes]:
        raw = self._recv_exact(4)
        (length,) = struct.unpack("<i", raw)
        if length < 8 or length > 8 * 1024 * 1024:
            raise ConnectionError(f"非法包长度 {length}")
        data = self._recv_exact(length)
        req_id, req_type = struct.unpack("<ii", data[:8])
        body = data[8:]
        if body.endswith(b"\x00\x00"):
            body = body[:-2]
        elif body.endswith(b"\x00"):
            body = body[:-1]
        return req_id, req_type, body

    def _drop_socket(self) -> None:
        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass

    def _connect(self) -> None:
        """建立连接并完成认证；失败抛 IcdError。"""
        try:
            sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError as exc:
            raise IcdError(
                E_RCON,
                f"无法连接 RCON {self.host}:{self.port}",
                detail=str(exc),
            ) from exc
        sock.settimeout(self.timeout)
        self._sock = sock
        try:
            auth_id = self._next_id()
            sock.sendall(_pack(auth_id, SERVERDATA_AUTH, self.password.encode("utf-8")))
            for _ in range(_MAX_AUTH_PACKETS):
                rid, _rtype, _body = self._read_packet()
                if rid == -1:
                    raise IcdError(
                        E_RCON,
                        "RCON 认证失败（密码错误）",
                        detail={"host": self.host, "port": self.port},
                    )
                if rid == auth_id:
                    return
            raise IcdError(E_RCON, "RCON 认证未收到应答", detail={"host": self.host, "port": self.port})
        except IcdError:
            self._drop_socket()
            raise
        except (OSError, ConnectionError) as exc:
            self._drop_socket()
            raise IcdError(
                E_RCON,
                f"RCON 认证失败 {self.host}:{self.port}",
                detail=str(exc),
            ) from exc

    def _ensure_connected(self) -> None:
        if self._sock is None:
            self._connect()

    # ---- 公开 API ----

    def command(self, cmd: str) -> str:
        """执行一条服务端命令并返回响应文本。失败抛 ``IcdError(E_RCON)``。"""
        if not isinstance(cmd, str):
            raise IcdError(E_RCON, "命令必须是字符串", detail=type(cmd).__name__)
        with self._lock:
            self._ensure_connected()
            rid = self._next_id()
            try:
                assert self._sock is not None
                self._sock.sendall(_pack(rid, SERVERDATA_EXECCOMMAND, cmd.encode("utf-8")))
                while True:
                    resp_id, _rtype, body = self._read_packet()
                    if resp_id == rid:
                        return body.decode("utf-8", "replace").rstrip("\r\n")
            except IcdError:
                self._drop_socket()
                raise
            except (OSError, ConnectionError) as exc:
                self._drop_socket()
                raise IcdError(
                    E_RCON,
                    f"RCON 命令失败: {cmd.split(maxsplit=1)[0] if cmd.strip() else '<空命令>'}",
                    detail=str(exc),
                ) from exc

    def close(self) -> None:
        """关闭连接（幂等）。"""
        with self._lock:
            self._drop_socket()

    def __enter__(self) -> "RconClient":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()


# ---- 连接池 ----

_clients: dict[tuple[str, int, str], RconClient] = {}
_pool_lock = threading.Lock()


def get_client(
    host: str = "127.0.0.1",
    port: int = DEFAULT_RCON_PORT,
    password: str = DEFAULT_RCON_PASSWORD,
) -> RconClient:
    """按 (host, port, password) 复用客户端；复用前 ping 一次，失败则重建一次。"""
    key = (host, int(port), password)
    with _pool_lock:
        client = _clients.get(key)
        if client is None:
            client = RconClient(host=host, port=int(port), password=password)
            _clients[key] = client
            return client

    # 已缓存的客户端：ping 检查存活，失败则丢弃重建
    try:
        client.command("")
        return client
    except IcdError as exc:
        log.debug("RCON 客户端 ping 失败，重建连接: %s", exc)

    fresh = RconClient(host=host, port=int(port), password=password)
    with _pool_lock:
        old = _clients.get(key)
        if old is not None:
            old.close()
        _clients[key] = fresh
    return fresh


def close_all() -> None:
    """关闭并清空所有复用连接。"""
    with _pool_lock:
        clients = list(_clients.values())
        _clients.clear()
    for client in clients:
        client.close()


def command(
    cmd: str,
    host: str = "127.0.0.1",
    port: int = DEFAULT_RCON_PORT,
    password: str = DEFAULT_RCON_PASSWORD,
) -> str:
    """一步式执行：取复用客户端并执行命令。"""
    return get_client(host=host, port=port, password=password).command(cmd)
