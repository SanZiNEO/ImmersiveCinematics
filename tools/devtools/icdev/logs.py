"""日志读取（GBK 容错）、过滤、正则等待。

客户端/服务端日志是 UTF-8 与 GBK 混杂的（Windows 上 MC 与部分 mod 会写平台编码），
所以解码走「utf-8 → gbk → replace」。日志文件在进程起来之前不存在，
等待类接口把「文件不存在」当成「还没命中」，不抛异常。
"""

from __future__ import annotations

import logging
import os
import re
import time
from pathlib import Path

from .errors import E_INVALID_ARGUMENT, IcdError

log = logging.getLogger("icdev.logs")

#: tail 从文件尾反向读取的块大小
_CHUNK = 65536

#: 等待时的轮询间隔
POLL_INTERVAL_S = 0.1

#: 等待期间保留的已读字节上限（尾部截断，命中检测每轮都做，所以丢头部安全）
_MAX_WAIT_BUFFER = 1_000_000

#: wait 命中/超时时返回的上下文行数
CONTEXT_LINES = 20


def _decode(data: bytes) -> str:
    for encoding in ("utf-8", "gbk"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def read_text(path: Path) -> str:
    """整读日志；utf-8 失败回落 gbk，再失败用 ``errors="replace"``。"""
    return _decode(Path(path).read_bytes())


def _size(path: Path) -> int | None:
    try:
        return path.stat().st_size
    except OSError:
        return None


def _tail_raw(path: Path, lines: int) -> tuple[bytes, bool]:
    """反向分块读末尾；返回 (原始字节, 是否已读到文件头)。"""
    with Path(path).open("rb") as fh:
        fh.seek(0, os.SEEK_END)
        pos = fh.tell()
        chunks: list[bytes] = []
        newlines = 0
        while pos > 0 and newlines <= lines:
            step = min(_CHUNK, pos)
            pos -= step
            fh.seek(pos)
            chunk = fh.read(step)
            chunks.append(chunk)
            newlines += chunk.count(b"\n")
    return b"".join(reversed(chunks)), pos == 0


def tail(path: Path, lines: int = 200) -> str:
    """末尾 ``lines`` 行。文件不存在/不可读 → 空串。"""
    path = Path(path)
    if lines <= 0:
        return ""
    try:
        raw, at_start = _tail_raw(path, lines)
    except OSError as exc:
        log.debug("读取日志失败（视为空）：%s（%s）", path, exc)
        return ""

    rows = _decode(raw).splitlines()
    if not at_start and rows:
        rows = rows[1:]  # 首行可能是被块边界切开的半行
    return "\n".join(rows[-lines:])


def trace(path: Path, pattern: str | None = None, lines: int = 2000) -> str:
    """按正则过滤日志（``pattern=None`` 等价于 tail），返回最后 ``lines`` 条。"""
    if lines <= 0:
        return ""
    if pattern is None:
        return tail(path, lines)

    regex = _compile(pattern)
    text = ""
    try:
        text = read_text(path)
    except OSError as exc:
        log.debug("读取日志失败（视为空）：%s（%s）", path, exc)
        return ""

    matched = [row for row in text.splitlines() if regex.search(row)]
    return "\n".join(matched[-lines:])


def _compile(pattern: str) -> re.Pattern[str]:
    if not isinstance(pattern, str) or not pattern:
        raise IcdError(E_INVALID_ARGUMENT, "pattern 必须是非空字符串")
    try:
        return re.compile(pattern)
    except re.error as exc:
        raise IcdError(E_INVALID_ARGUMENT, f"正则不合法：{pattern}", detail=str(exc)) from exc


def _context_rows(rows: list[str], index: int) -> str:
    start = max(0, index - CONTEXT_LINES // 2)
    end = min(len(rows), index + CONTEXT_LINES // 2 + 1)
    return "\n".join(rows[start:end])


def _context(text: str, match_pos: int) -> str:
    """缓冲区内的命中位置附近的约 20 行。"""
    rows = text.splitlines()
    if not rows:
        return ""
    index = min(text.count("\n", 0, match_pos), len(rows) - 1)
    return _context_rows(rows, index)


def _match_context(path: Path, regex: re.Pattern[str], text: str, match_pos: int) -> str:
    """命中时的上下文：优先从文件尾部取（``from_end=True`` 时缓冲区里没有历史行）。"""
    rows = tail(path, CONTEXT_LINES * 20).splitlines()
    for index in range(len(rows) - 1, -1, -1):
        if regex.search(rows[index]):
            return _context_rows(rows, index)
    return _context(text, match_pos)


def _wait(
    path: Path,
    patterns: list[re.Pattern[str]],
    timeout_s: float,
    from_end: bool,
) -> tuple[int, str]:
    """轮询等待任一正则命中。返回 (命中下标, 上下文)；超时返回 (-1, 最后 20 行)。"""
    path = Path(path)
    offset = 0
    if from_end:
        size = _size(path)
        if size:
            offset = size

    buffer = b""
    deadline = time.monotonic() + max(0.0, float(timeout_s))

    while True:
        size = _size(path)
        if size is not None and size < offset:
            # 日志被轮转/清空：从新文件头重新开始
            log.debug("日志被截断，重置读取位置：%s", path)
            offset = 0
            buffer = b""
        if size is not None and size > offset:
            try:
                with path.open("rb") as fh:
                    fh.seek(offset)
                    chunk = fh.read(size - offset)
            except OSError as exc:  # 写入中/被轮转
                log.debug("读取日志失败（本轮跳过）：%s（%s）", path, exc)
                chunk = b""
            if chunk:
                offset += len(chunk)
                buffer += chunk
                text = _decode(buffer)
                for index, regex in enumerate(patterns):
                    found = regex.search(text)
                    if found:
                        return index, _match_context(path, regex, text, found.start())
                if len(buffer) > _MAX_WAIT_BUFFER:
                    # 已扫描过的部分可以丢，只保留尾部
                    cut = buffer.find(b"\n", len(buffer) - _MAX_WAIT_BUFFER // 2)
                    buffer = buffer[cut + 1:] if cut >= 0 else buffer[-_MAX_WAIT_BUFFER:]

        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return -1, tail(path, CONTEXT_LINES)
        time.sleep(min(POLL_INTERVAL_S, remaining))


def wait_match(
    path: Path,
    pattern: str,
    timeout_s: float,
    from_end: bool = True,
) -> tuple[bool, str]:
    """等待正则出现。

    ``from_end=True`` 时只匹配调用之后新增的内容（记录调用时的文件长度）。
    返回 (是否命中, 命中时约 20 行上下文 / 超时则最后 20 行)。
    """
    regex = _compile(pattern)
    index, context = _wait(Path(path), [regex], timeout_s, from_end)
    return index >= 0, context


def wait_any(
    path: Path,
    patterns: list[str],
    timeout_s: float,
    from_end: bool = True,
) -> tuple[str | None, str]:
    """等待多个正则中的任意一个。返回 (命中的 pattern / None, 上下文或最后 20 行)。"""
    if not patterns:
        raise IcdError(E_INVALID_ARGUMENT, "patterns 不能为空")
    regexes = [_compile(p) for p in patterns]
    index, context = _wait(Path(path), regexes, timeout_s, from_end)
    if index < 0:
        return None, context
    return patterns[index], context
