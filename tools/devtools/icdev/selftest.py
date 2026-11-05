"""icdev 自检：不依赖游戏、不依赖网络。

    python -m icdev.selftest

每一项输出 ``PASS 名称`` / ``FAIL 名称: 原因``，最后输出 ``SUMMARY pass=N fail=M``；
有失败时进程退出码为 1。自检里的「MCP 子进程」用当前解释器 ``-m icdev.server``
跑真正的 stdio 协议，launch 的形状检查用 ``.venv`` 里的解释器 ``-c`` 跑。

本模块只读/只写临时目录，不碰游戏进程，也不改仓库文件。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Callable

from .paths import DEVTOOLS_DIR

#: 与 MCP-DESIGN.md §十 一一对应的错误码
EXPECTED_CODES = {
    "E_NOT_READY",
    "E_ALREADY_UP",
    "E_TIMEOUT",
    "E_NO_WINDOW",
    "E_OWNERSHIP",
    "E_PORT_IN_USE",
    "E_CONFIG_INVALID",
    "E_NO_PLAYER",
    "E_RCON",
    "E_UNSUPPORTED",
    "E_NOT_FOUND",
    "E_INVALID_ACTION",
    "E_INVALID_ARGUMENT",
    "E_PROCESS_FAILED",
    "E_INTERNAL",
}

MODULES = (
    "errors",
    "paths",
    "win32",
    "process",
    "launch",
    "logs",
    "rcon",
    "config",
    "snapshot",
    "pixels",
    "diag",
    "rig",
    "server",
    "info",
)


# ------------------------------------------------------------------ 1. 导入

def test_imports() -> None:
    import importlib

    for name in MODULES:
        importlib.import_module(f"icdev.{name}")

    from . import errors

    found = {name for name in vars(errors) if name.startswith("E_")}
    missing = EXPECTED_CODES - found
    if missing:
        raise AssertionError(f"errors 缺少错误码：{sorted(missing)}")

    for name in EXPECTED_CODES:
        value = getattr(errors, name)
        if value != name:
            raise AssertionError(f"{name} 的值不是自身：{value!r}")

    err = errors.IcdError(errors.E_TIMEOUT, "超时", {"ms": 1})
    if err.to_dict() != {"code": "E_TIMEOUT", "message": "超时", "detail": {"ms": 1}}:
        raise AssertionError(f"to_dict 结构不符：{err.to_dict()!r}")
    if str(err) != "E_TIMEOUT: 超时":
        raise AssertionError(f"__str__ 结构不符：{str(err)!r}")


# ------------------------------------------------------------------ 2. config

def test_config() -> None:
    from . import config
    from .errors import E_CONFIG_INVALID, IcdError

    with tempfile.TemporaryDirectory(prefix="icdev-selftest-config-") as tmp:
        run = Path(tmp)

        old = config.set(run, "skipHoldThresholdMs", 500)
        if old is not None:
            raise AssertionError(f"首次 set 的旧值应为 None：{old!r}")
        if config.get(run, "skipHoldThresholdMs") != 500:
            raise AssertionError("get 没有读回 500")

        old = config.set(run, "skipHoldThresholdMs", 800)
        if old != 500:
            raise AssertionError(f"第二次 set 的旧值应为 500：{old!r}")

        config.set(run, "showSkipHud", False)
        if config.get(run, "showSkipHud") is not False:
            raise AssertionError("bool 写入/读回失败")

        path = config.config_path(run)
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("skipHoldThresholdMs") != 800 or data.get("showSkipHud") is not False:
            raise AssertionError(f"落盘内容不符：{data!r}")

        for key, value in (
            ("noSuchKey", 1),
            ("skipHoldThresholdMs", 0),
            ("skipHoldThresholdMs", 60001),
            ("skipHoldThresholdMs", "500"),
            ("skipHoldThresholdMs", True),
            ("showSkipHud", 1),
        ):
            try:
                config.set(run, key, value)
            except IcdError as exc:
                if exc.code != E_CONFIG_INVALID:
                    raise AssertionError(
                        f"非法输入 {key}={value!r} 返回了 {exc.code}，应为 {E_CONFIG_INVALID}"
                    )
            else:
                raise AssertionError(f"非法输入 {key}={value!r} 没有被拒绝")


# ------------------------------------------------------------------ 3. snapshot

def test_snapshot() -> None:
    from . import snapshot

    with tempfile.TemporaryDirectory(prefix="icdev-selftest-snap-") as tmp:
        base = Path(tmp)
        repo = base / "repo"
        cfg = repo / "config" / "a.json"
        cfg.parent.mkdir(parents=True)
        cfg.write_text('{"v":1}', encoding="utf-8")

        snaps = base / "snapshots"
        created = snapshot.create("s1", [cfg], root=snaps, repo_root=repo)
        if created["files"] != 1:
            raise AssertionError(f"快照文件数应为 1：{created!r}")

        manifest = snaps / "s1" / "manifest.json"
        if not manifest.is_file():
            raise AssertionError(f"manifest 不存在：{manifest}")
        data = json.loads(manifest.read_text(encoding="utf-8"))
        rel = "config/a.json"
        if data.get("files") != [rel] or rel not in data.get("hashes", {}):
            raise AssertionError(f"manifest 结构不符：{data!r}")

        cfg.write_text('{"v":2}', encoding="utf-8")
        extra = cfg.parent / "extra.txt"
        extra.write_text("new", encoding="utf-8")

        result = snapshot.restore("s1", root=snaps)
        if cfg.read_text(encoding="utf-8") != '{"v":1}':
            raise AssertionError("restore 没有还原文件内容")
        if extra.exists():
            raise AssertionError("restore 没有删除新增文件 extra.txt")
        if "config/extra.txt" not in result.get("removed", []):
            raise AssertionError(f"removed 列表不符：{result!r}")
        if result.get("restored") != 1:
            raise AssertionError(f"restored 应为 1：{result!r}")


# ------------------------------------------------------------------ 4. pixels

def test_pixels() -> None:
    from PIL import Image
    import numpy as np

    from . import pixels

    with tempfile.TemporaryDirectory(prefix="icdev-selftest-px-") as tmp:
        path = Path(tmp) / "fake.png"
        arr = np.zeros((100, 200, 3), dtype=np.uint8)
        arr[40:61, 90:111] = 255
        Image.fromarray(arr, mode="RGB").save(path)

        found = pixels.clusters(path, threshold=190)
        if len(found) != 1:
            raise AssertionError(f"应只检出 1 个亮簇：{found!r}")
        center = found[0]["centerPct"]
        if abs(center[0] - 50.0) > 0.01 or abs(center[1] - 50.0) > 0.01:
            raise AssertionError(f"簇中心应≈(50,50)：{center!r}")
        if found[0]["px"] != 21 * 21:
            raise AssertionError(f"亮像素数应为 441：{found[0]!r}")

        same = pixels.diff(path, path)
        if same.get("changedPx") != 0 or same.get("pct") != 0.0:
            raise AssertionError(f"同图 diff 应全 0：{same!r}")
        if same.get("meanAbsDiff") != 0.0 or same.get("bbox") is not None:
            raise AssertionError(f"同图 diff 的均值/bbox 不符：{same!r}")


# ------------------------------------------------------------------ 5. diag

_SUMMARY = """\
# caseA

| Metric | n | p50 | p95 | mean | min | max |
|---|---:|---:|---:|---:|---:|---:|
| frame_ms | 600 | 16.7 ms | 20.5 ms | 17.1 ms | 12 ms | 33 ms |
| lane_rows | 0 | 0 | 0 | 0 | 0 | 0 |
"""


def test_diag() -> None:
    from . import diag

    with tempfile.TemporaryDirectory(prefix="icdev-selftest-diag-") as tmp:
        run = Path(tmp) / "run"
        cases = [
            {"id": "caseA", "script": "fround:ic_f_perf_l25", "lanes": 25, "frames": 120},
            {"id": "caseB", "script": "fround:ic_f_perf_l16"},
        ]
        path = diag.write_manifest(run, cases, session="sess-x")
        if not path.is_file():
            raise AssertionError(f"manifest 没有写出：{path}")
        payload = json.loads(path.read_text(encoding="utf-8"))
        if payload.get("enabled") is not True or payload.get("sessionName") != "sess-x":
            raise AssertionError(f"manifest 顶层不符：{payload!r}")
        got = payload.get("cases") or []
        if len(got) != 2 or got[0].get("id") != "caseA" or got[0].get("run") != 1:
            raise AssertionError(f"cases 结构不符：{got!r}")
        if got[0]["perf"].get("frames") != 120 or got[0]["perf"].get("expectedLanes") != 25:
            raise AssertionError(f"perf 未按 case 覆盖：{got[0]!r}")
        if got[1]["perf"].get("expectedLanes") != -1:
            raise AssertionError(f"lanes 缺省应为 -1：{got[1]!r}")

        session = run / diag.DIAG_SUBDIR / diag.SESSIONS_DIRNAME / "20260101-caseA"
        (session / "performance").mkdir(parents=True)
        (session / "performance" / "caseA-run1-summary.md").write_text(
            _SUMMARY, encoding="utf-8"
        )
        summaries = diag.read_summaries(session)
        if "caseA" not in summaries:
            raise AssertionError(f"summary 未按 case 聚合：{sorted(summaries)}")
        metrics = summaries["caseA"]["metrics"]
        if metrics.get("frame_ms", {}).get("n") != 600:
            raise AssertionError(f"frame_ms.n 解析错误：{metrics.get('frame_ms')!r}")
        if metrics.get("frame_ms", {}).get("p50") != 16.7:
            raise AssertionError(f"frame_ms.p50 解析错误：{metrics.get('frame_ms')!r}")
        if metrics.get("lane_rows", {}).get("n") != 0:
            raise AssertionError(f"lane_rows.n 解析错误：{metrics.get('lane_rows')!r}")


# ------------------------------------------------------------------ 6. logs

def test_logs() -> None:
    from . import logs

    with tempfile.TemporaryDirectory(prefix="icdev-selftest-log-") as tmp:
        path = Path(tmp) / "latest.log"
        path.write_bytes("第一行\n第二行 [G-TEST] ok\n".encode("gbk"))

        text = logs.tail(path, 2)
        if "第二行 [G-TEST] ok" not in text:
            raise AssertionError(f"tail 没有正确按 GBK 解码：{text!r}")

        matched, context = logs.wait_match(path, r"\[G-TEST\]", 1.0, from_end=False)
        if not matched or "[G-TEST]" not in context:
            raise AssertionError(f"wait_match 没有命中已有内容：{matched!r} {context!r}")

        matched, _ = logs.wait_match(path, "不会出现的行", 0.2)
        if matched:
            raise AssertionError("wait_match 对不存在的 pattern 命中了")

        if logs.tail(Path(tmp) / "missing.log", 5) != "":
            raise AssertionError("不存在的日志应返回空串")


# ------------------------------------------------------------------ 7. process

def test_process() -> None:
    from . import process

    server = "java -cp x net.fabricmc.devlaunchinjector.Main -Dfabric.dli.main=KnotServer"
    client = "java -cp x net.fabricmc.devlaunchinjector.Main KnotClient --username Dev"
    injector = "java -cp x net.fabricmc.devlaunchinjector.Main -Dfabric.dli.env=server"

    if process.role_of(server) != "server":
        raise AssertionError(f"KnotServer 未识别为 server：{process.role_of(server)!r}")
    if process.role_of(client) != "client":
        raise AssertionError(f"KnotClient 未识别为 client：{process.role_of(client)!r}")
    if process.role_of(injector) is not None:
        raise AssertionError("devlaunchinjector 不应被识别成任何角色")
    if process.role_of("") is not None:
        raise AssertionError("空命令行应返回 None")


# ------------------------------------------------------------------ 8. server

def test_server() -> None:
    requests = [
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "selftest", "version": "0"},
            },
        },
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "no-such-tool", "arguments": {"action": "x"}},
        },
    ]
    payload = "\n".join(json.dumps(item, ensure_ascii=False) for item in requests) + "\n"

    proc = subprocess.run(
        [sys.executable, "-m", "icdev.server"],
        input=payload,
        cwd=str(DEVTOOLS_DIR),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=90,
    )
    if proc.returncode != 0:
        raise AssertionError(
            f"icdev.server 退出码 {proc.returncode}\nstderr:\n{proc.stderr[-2000:]}"
        )

    messages: list[dict] = []
    for index, line in enumerate(proc.stdout.splitlines(), 1):
        if not line.strip():
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            raise AssertionError(
                f"stdout 第 {index} 行不是合法 JSON（{exc}）：{line[:200]!r}"
            ) from exc
        if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
            raise AssertionError(f"stdout 第 {index} 行不是 JSON-RPC 2.0：{line[:200]!r}")
        messages.append(message)

    def response(request_id: int) -> dict:
        hits = [
            item
            for item in messages
            if item.get("id") == request_id and ("result" in item or "error" in item)
        ]
        if len(hits) != 1:
            raise AssertionError(f"id={request_id} 应恰好 1 条响应，实际 {len(hits)}")
        return hits[0]

    init = response(1).get("result") or {}
    if not init.get("protocolVersion"):
        raise AssertionError(f"initialize 结果缺少 protocolVersion：{init!r}")

    tools = (response(2).get("result") or {}).get("tools")
    if not isinstance(tools, list) or len(tools) != 10:
        raise AssertionError(f"tools/list 应返回 10 个工具：{tools!r}")
    names = [tool.get("name") for tool in tools]
    if len(set(names)) != 10 or "rig" not in names or "capture" not in names:
        raise AssertionError(f"工具名称异常：{names!r}")

    unknown = response(3).get("error") or {}
    if unknown.get("code") != -32602:
        raise AssertionError(f"未知工具应返回 -32602：{unknown!r}")
    if not str(unknown.get("message", "")).startswith("Invalid params: "):
        raise AssertionError(f"未知工具错误消息格式不符：{unknown.get('message')!r}")


# ------------------------------------------------------------------ 9. launch

_LAUNCH_PROBE = r"""
import json
import os
import sys
from pathlib import Path

from icdev import launch

run_dir = Path(sys.argv[1])
spec = launch.LaunchSpec(
    main="com.example.Main",
    classpath=["a.jar", "b c.jar"],
    jvm_args=["-Xmx1G", "-Dfabric.dli.env=client", "-Dfabric.dli.main=old.Main"],
)
cp = os.pathsep.join(spec.classpath)

client = launch.build_client_cmd(spec, run_dir, "Dev", world="世界 A")
assert Path(client[0]).name.lower().startswith("java"), client
assert sum(1 for arg in client if arg == "-cp") == 1, client
at = client.index("-cp")
assert client[at + 1] == cp, client
assert client[at + 2] == "com.example.Main", client
assert client[-4:] == ["--quickPlaySingleplayer", "世界 A", "--username", "Dev"], client

client_mp = launch.build_client_cmd(spec, run_dir, "Dev", multiplayer="localhost:25565")
assert "--quickPlayMultiplayer" in client_mp and "localhost:25565" in client_mp, client_mp
assert "--quickPlaySingleplayer" not in client_mp, client_mp

server = launch.build_server_cmd(spec, run_dir)
assert Path(server[0]).name.lower().startswith("java"), server
assert "-Dfabric.dli.env=server" in server, server
assert server[-1] == "nogui", server
assert "--username" not in server, server
assert [a for a in server if a.startswith("-Dfabric.dli.env=")] == ["-Dfabric.dli.env=server"], server
mains = [a for a in server if a.startswith("-Dfabric.dli.main=")]
assert len(mains) == 1 and mains[0].endswith("KnotServer"), server
at = server.index("-cp")
assert server[at + 1] == cp and server[at + 2] == "com.example.Main", server

try:
    launch.build_client_cmd(spec, run_dir, "Dev", multiplayer="localhost:25565", world="x")
except Exception as exc:
    assert getattr(exc, "code", None) == "E_INVALID_ARGUMENT", repr(exc)
else:
    raise AssertionError("multiplayer 与 world 同时给时应报错")

print(json.dumps({"client": client, "server": server}))
"""


def test_launch() -> None:
    venv_python = DEVTOOLS_DIR / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    python = venv_python if venv_python.is_file() else Path(sys.executable)

    with tempfile.TemporaryDirectory(prefix="icdev-selftest-launch-") as tmp:
        proc = subprocess.run(
            [str(python), "-c", _LAUNCH_PROBE, tmp],
            cwd=str(DEVTOOLS_DIR),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )
        if proc.returncode != 0:
            raise AssertionError(
                f"launch 命令形状检查失败（{python}）\n{proc.stdout[-1000:]}\n{proc.stderr[-3000:]}"
            )
        try:
            data = json.loads(proc.stdout.strip().splitlines()[-1])
        except (IndexError, json.JSONDecodeError) as exc:
            raise AssertionError(f"launch 探针没有输出 JSON：{proc.stdout!r}") from exc

        client = data["client"]
        server = data["server"]
        if client[-1] != "Dev" or server[-1] != "nogui":
            raise AssertionError(f"命令首尾不符：{client!r} / {server!r}")


# ------------------------------------------------------------------ 入口

TESTS: tuple[tuple[str, Callable[[], None]], ...] = (
    ("imports", test_imports),
    ("config", test_config),
    ("snapshot", test_snapshot),
    ("pixels", test_pixels),
    ("diag", test_diag),
    ("logs", test_logs),
    ("process", test_process),
    ("server", test_server),
    ("launch", test_launch),
)


def main(argv: list[str] | None = None) -> int:
    """跑全部自检；返回进程退出码（0 全过，1 有失败）。"""
    passed = 0
    failed = 0
    for name, func in TESTS:
        try:
            func()
        except BaseException as exc:  # noqa: BLE001 - 自检要报出任何失败
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            failed += 1
            print(f"FAIL {name}: {exc}", flush=True)
        else:
            passed += 1
            print(f"PASS {name}", flush=True)

    print(f"SUMMARY pass={passed} fail={failed}", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main(sys.argv[1:]))
