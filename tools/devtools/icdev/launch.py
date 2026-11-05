"""loom 启动参数导出与缓存、`java` 直启。

思路（MCP-DESIGN.md §六）：先用一个临时 gradle init script 让 loom 自己把
`runClient` 的 main / classpath / jvmArgs 写成 5 行文本，缓存到
``${ICDEV_HOME}/launch-spec.json``；之后多开客户端/服务端直接 `java -cp`，
绕开「同一项目目录并发 gradle 构建抢锁」。

缓存失效键：``common/build/libs/*.jar`` 的最大 mtime。jar 一旦重新产出，
classpath 里的 common jar 内容就变了，必须重新 dump。

本模块不打印、不依赖游戏进程，导入即安全。
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .errors import (
    E_INVALID_ARGUMENT,
    E_NOT_FOUND,
    E_PROCESS_FAILED,
    E_UNSUPPORTED,
    IcdError,
)
from .paths import ICDEV_HOME, REPO_ROOT

log = logging.getLogger("icdev.launch")

#: 缓存的启动参数
SPEC_PATH = ICDEV_HOME / "launch-spec.json"

#: common jar 与源码目录（增量判断用）
COMMON_JAR_GLOB = "common/build/libs/*.jar"
COMMON_SRC_DIR = REPO_ROOT / "common" / "src"

#: dump 哪个 loom run 配置
DUMP_TASK = "runClient"
FABRIC_PROJECT = ":fabric"

#: gradle 单次调用上限（首次配置可能很慢）
GRADLE_TIMEOUT_S = 900.0

#: 服务端主类：DevLaunchInjector 会按 `-Dfabric.dli.main` 注入真正的入口
DEFAULT_SERVER_MAIN = "net.fabricmc.loader.impl.launch.knot.KnotServer"

#: 临时 init script。与 E:/tmp/h6-rig/dump.gradle 逐字一致（已验证可用）。
INIT_SCRIPT = """\
// 临时 gradle init script：把 loom 的 runClient / runServer 启动参数导出成可直接执行的命令行。
// 目的：绕过「同一项目目录下并发 gradle 构建会抢锁」，让两个客户端 + 一个服务端同时跑。
// 由 icdev.launch 在缓存失效时生成，dump 完即删。
gradle.projectsEvaluated {
    def p = gradle.rootProject.findProject(':fabric')
    if (p == null) return
    def runTaskName = (gradle.startParameter.projectProperties['runTask'] ?: 'runClient').toString()
    def outFile = (gradle.startParameter.projectProperties['outFile'] ?: 'icdev-run.txt').toString()
    def workDir = (gradle.startParameter.projectProperties['workDir'] ?: '').toString()
    def extraArgs = (gradle.startParameter.projectProperties['extraArgs'] ?: '').toString()

    p.tasks.register('dumpRun') {
        doLast {
            def t = p.tasks.named(runTaskName).get()
            def sb = new StringBuilder()
            sb.append('MAIN=').append(t.mainClass.getOrNull()).append('\\n')
            sb.append('WORKDIR=').append(workDir ?: t.workingDir.absolutePath).append('\\n')
            sb.append('CP=').append(t.classpath.asPath).append('\\n')
            def sysProps = []
            t.systemProperties.each { k, v -> sysProps << ("-D" + k + "=" + v) }
            sb.append('JVMARGS=').append((sysProps + t.jvmArgs).join(' ')).append('\\n')
            def args = new ArrayList(t.args)
            if (extraArgs) args.addAll(extraArgs.split(' ').findAll { it })
            sb.append('ARGS=').append(args.join(' ')).append('\\n')
            new File(outFile).parentFile.mkdirs()
            new File(outFile).text = sb.toString()
            println "dumped ${runTaskName} -> ${outFile}"
        }
    }
}
"""

#: 参数切分：双引号/单引号包起来的一段算一个 token（loom 的 dump 用空格拼接）
_TOKEN_RE = re.compile(r'"(?:[^"\\]|\\.)*"|\'[^\']*\'|[^\s]+')


# ---------------------------------------------------------------- 数据模型


@dataclass
class LaunchSpec:
    """一次 loom run 配置展开后的启动参数。client / server 共用同一份 classpath。"""

    main: str
    classpath: list[str] = field(default_factory=list)
    jvm_args: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "main": self.main,
            "classpath": list(self.classpath),
            "jvm_args": list(self.jvm_args),
        }

    @classmethod
    def from_dict(cls, data: dict) -> "LaunchSpec":
        if not isinstance(data, dict):
            raise IcdError(E_INVALID_ARGUMENT, "launch spec 必须是对象")
        main = data.get("main")
        cp = data.get("classpath") or []
        jvm = data.get("jvm_args") or []
        if not isinstance(main, str) or not main:
            raise IcdError(E_INVALID_ARGUMENT, "launch spec 缺少 main")
        if not isinstance(cp, list) or not all(isinstance(x, str) for x in cp):
            raise IcdError(E_INVALID_ARGUMENT, "launch spec 的 classpath 必须是字符串数组")
        if not isinstance(jvm, list) or not all(isinstance(x, str) for x in jvm):
            raise IcdError(E_INVALID_ARGUMENT, "launch spec 的 jvm_args 必须是字符串数组")
        return cls(main=main, classpath=list(cp), jvm_args=list(jvm))


# ---------------------------------------------------------------- gradle


def _gradle_prefix() -> list[str]:
    """与 devtools.gradle_prefix 同口径：优先 POSIX shell（Git Bash），否则 gradlew.bat。"""
    for candidate in ("sh", "bash"):
        if shutil.which(candidate):
            return [candidate, "gradlew"]
    bat = REPO_ROOT / "gradlew.bat"
    if bat.exists():
        return [str(bat)]
    raise IcdError(E_NOT_FOUND, "找不到 gradlew / sh / bash", detail=str(REPO_ROOT))


def _run_gradle(args: list[str], timeout: float = GRADLE_TIMEOUT_S) -> subprocess.CompletedProcess:
    cmd = _gradle_prefix() + list(args)
    log.debug("gradle: %s", " ".join(cmd))
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            stdin=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired as exc:
        raise IcdError(
            E_PROCESS_FAILED,
            f"gradle 超时（{timeout:.0f}s）",
            detail=(exc.stdout or "")[-4000:] if isinstance(exc.stdout, str) else None,
        ) from exc
    except OSError as exc:
        raise IcdError(E_PROCESS_FAILED, "无法执行 gradle", detail=str(exc)) from exc
    return proc


def _gradle_output(proc: subprocess.CompletedProcess) -> str:
    return (proc.stdout or "") + ("\n" + proc.stderr if proc.stderr else "")


def _output_tail(text: str, lines: int = 40) -> str:
    rows = text.splitlines()
    return "\n".join(rows[-lines:])


# ---------------------------------------------------------------- common jar


def _common_jars() -> list[Path]:
    libs = REPO_ROOT / "common" / "build" / "libs"
    if not libs.is_dir():
        return []
    jars = [
        p
        for p in libs.glob("*.jar")
        if p.is_file() and not p.stem.endswith(("-sources", "-javadoc"))
    ]
    return sorted(jars, key=lambda p: p.stat().st_mtime)


def _find_common_jar() -> Path | None:
    jars = _common_jars()
    return jars[-1] if jars else None


def _newest_jar_mtime() -> float:
    jars = _common_jars()
    return max((p.stat().st_mtime for p in jars), default=0.0)


def _newest_common_source_mtime() -> float:
    if not COMMON_SRC_DIR.is_dir():
        return 0.0
    newest = 0.0
    for src in COMMON_SRC_DIR.rglob("*.java"):
        try:
            newest = max(newest, src.stat().st_mtime)
        except OSError:  # 文件正在被移动/删除
            continue
    return newest


def ensure_common_jar(force: bool = False) -> Path:
    """增量刷新 dev 运行时依赖的三份产物，并返回 common jar 路径。

    dev 客户端的 classpath 顺序是
    ``fabric/build/classes/java/main`` → ``fabric/build/resources/main`` → ``common/build/libs/*.jar``，
    前两项会**遮住** common jar。只重建 jar 会导致改了代码/语言文件却仍跑旧实现，
    所以这里必须同时刷新 ``:fabric:compileJava`` 与 ``:fabric:processResources``。

    跳过条件：三份产物都存在，且最旧的产物 mtime 不早于 `common/src` / `fabric/src` 下最新源文件。
    """
    jar = _find_common_jar()
    outputs = [p for p in (
        jar,
        REPO_ROOT / "fabric" / "build" / "resources" / "main",
        REPO_ROOT / "fabric" / "build" / "classes" / "java" / "main",
    ) if p is not None]

    if not force and len(outputs) == 3 and all(p.exists() for p in outputs):
        newest = _newest_source_mtime()
        oldest_out = min(p.stat().st_mtime for p in outputs)
        if oldest_out >= newest:
            log.debug("dev 产物已是最新：%s", jar)
            return jar  # type: ignore[return-value]

    log.info("刷新 dev 产物（:common:jar :fabric:processResources :fabric:compileJava）…")
    proc = _run_gradle([
        ":common:jar",
        ":fabric:processResources",
        ":fabric:compileJava",
        "--offline",
        "--no-daemon",
    ])
    out = _gradle_output(proc)
    if proc.returncode != 0:
        raise IcdError(
            E_PROCESS_FAILED,
            f"dev 产物构建失败（exit={proc.returncode}）",
            detail=_output_tail(out),
        )

    jar = _find_common_jar()
    if jar is None:
        raise IcdError(
            E_PROCESS_FAILED,
            "构建成功但没找到 common jar",
            detail=_output_tail(out),
        )
    return jar


def _newest_source_mtime() -> float:
    """`common/src` 与 `fabric/src` 下最新源文件的 mtime。"""
    newest = 0.0
    for base in (REPO_ROOT / "common" / "src", REPO_ROOT / "fabric" / "src"):
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if path.is_file() and path.suffix in (".java", ".json", ".toml", ".txt"):
                newest = max(newest, path.stat().st_mtime)
    return newest


# ---------------------------------------------------------------- dump / parse


def _split_args(text: str) -> list[str]:
    """按空白切分，保留引号内空格（dump 用空格拼接 argv）。"""
    tokens = []
    for raw in _TOKEN_RE.findall(text):
        if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "\"'":
            raw = raw[1:-1]
        tokens.append(raw)
    return tokens


def _read_argfile(path: Path) -> list[str]:
    try:
        text = path.read_bytes().decode("utf-8", errors="replace")
    except OSError:
        log.info("jvm_args 引用的 @argfile 已不存在，已忽略（classpath 由本模块提供）：%s", path)
        return []
    return _split_args(text)


def _expand_jvm_args(args: list[str], depth: int = 0) -> list[str]:
    """展开 `@argfile`，并剔掉 ``-cp``/``-classpath`` 对（classpath 由本模块统一提供）。"""
    out: list[str] = []
    i = 0
    while i < len(args):
        arg = args[i]
        if arg in ("-cp", "-classpath", "--class-path"):
            i += 2  # 一并丢掉后面的 classpath 值
            continue
        if arg.startswith(("-cp=", "--class-path=")):
            i += 1
            continue
        if arg.startswith("@"):
            file_arg = arg[1:]
            nested: list[str] = []
            if depth < 3 and file_arg:
                nested = _expand_jvm_args(_read_argfile(Path(file_arg)), depth + 1)
            elif depth >= 3:
                log.warning("@argfile 嵌套过深，已忽略：%s", file_arg)
            out.extend(nested)
            i += 1
            continue
        out.append(arg)
        i += 1
    return out


def parse_dump(text: str) -> LaunchSpec:
    """解析 init script 导出的 ``MAIN=`` / ``CP=`` / ``JVMARGS=``（``WORKDIR`` / ``ARGS`` 忽略）。"""
    fields: dict[str, str] = {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if key in ("MAIN", "WORKDIR", "CP", "JVMARGS", "ARGS"):
            fields[key] = value

    main = (fields.get("MAIN") or "").strip()
    if not main or main == "null":
        raise IcdError(E_INVALID_ARGUMENT, "dump 结果缺少 MAIN（loom 的 mainClass 为空）", detail=text[:2000])

    cp_text = fields.get("CP", "")
    classpath = [p for p in cp_text.split(os.pathsep) if p]
    jvm_args = _expand_jvm_args(_split_args(fields.get("JVMARGS", "")))

    return LaunchSpec(main=main, classpath=classpath, jvm_args=jvm_args)


def _dump_spec() -> LaunchSpec:
    """跑一次 gradle，把 :fabric 的 runClient 启动参数 dump 出来。"""
    ICDEV_HOME.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="icdev-dump-", dir=str(ICDEV_HOME)))
    init_script = tmp / "dump.gradle"
    out_file = tmp / "run.txt"
    try:
        init_script.write_text(INIT_SCRIPT, encoding="utf-8", newline="\n")
        proc = _run_gradle(
            [
                "-I",
                str(init_script),
                f"{FABRIC_PROJECT}:dumpRun",
                f"-PrunTask={DUMP_TASK}",
                f"-PoutFile={out_file}",
                "--offline",
                "--no-daemon",
            ]
        )
        out = _gradle_output(proc)
        if proc.returncode != 0:
            raise IcdError(
                E_PROCESS_FAILED,
                f"导出 loom 启动参数失败（exit={proc.returncode}）",
                detail=_output_tail(out),
            )
        if not out_file.is_file():
            raise IcdError(
                E_PROCESS_FAILED,
                "gradle 结束但没有产出 dump 文件",
                detail=_output_tail(out) or str(out_file),
            )
        text = out_file.read_text(encoding="utf-8", errors="replace")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    spec = parse_dump(text)
    if not spec.classpath:
        raise IcdError(E_PROCESS_FAILED, "dump 结果的 classpath 为空", detail=_output_tail(text))
    log.info(
        "已导出 %s：main=%s，classpath %d 项，jvm_args %d 项",
        DUMP_TASK,
        spec.main,
        len(spec.classpath),
        len(spec.jvm_args),
    )
    return spec


def _load_cache() -> dict | None:
    if not SPEC_PATH.is_file():
        return None
    try:
        data = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        log.warning("launch-spec 缓存损坏，将重新导出：%s", exc)
        return None
    return data if isinstance(data, dict) else None


def _cache_fresh(data: dict) -> bool:
    try:
        cached_mtime = float(data.get("jar_mtime", -1.0))
    except (TypeError, ValueError):
        return False
    return abs(cached_mtime - _newest_jar_mtime()) < 1e-6


def _save_cache(spec: LaunchSpec) -> None:
    payload = spec.to_dict()
    payload["jar_mtime"] = _newest_jar_mtime()
    payload["created"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    SPEC_PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = SPEC_PATH.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, SPEC_PATH)
    log.debug("已写入 %s", SPEC_PATH)


def ensure_launch_spec(force: bool = False) -> LaunchSpec:
    """返回（必要时生成）启动参数缓存。缓存失效键是 common jar 的 mtime。

    先跑一次增量 ``:common:jar``（jar 比源码新时纯 mtime 判断，不调 gradle），
    这样 common 源码改动会推高 jar mtime，进而触发重新 dump。
    """
    ensure_common_jar(force=force)

    if not force:
        cached = _load_cache()
        if cached is not None and _cache_fresh(cached):
            try:
                return LaunchSpec.from_dict(cached)
            except IcdError as exc:
                log.warning("launch-spec 缓存结构不合法，将重新导出：%s", exc)

    spec = _dump_spec()
    _save_cache(spec)
    return spec


# ---------------------------------------------------------------- 命令行拼装


def _classpath(spec: LaunchSpec) -> str:
    return os.pathsep.join(spec.classpath)


def _server_main() -> str:
    return os.environ.get("ICDEV_SERVER_MAIN") or DEFAULT_SERVER_MAIN


_JAVA_CANDIDATES = [
    os.environ.get("ICDEV_JAVA"),
    (Path(os.environ["JAVA_HOME"]) / "bin" / "java.exe") if os.environ.get("JAVA_HOME") else None,
    Path(r"C:/Program Files/Java/jdk-17/bin/java.exe"),
    Path(r"C:/Program Files/Eclipse Adoptium/jdk-17/bin/java.exe"),
]


def resolve_java() -> str:
    """解析真正的 JVM 可执行文件。

    PATH 上的 ``java`` 在 Windows 上常是 Oracle 的启动器 shim，它会再起一个子进程，
    导致「我们 spawn 的 pid」与「窗口所属的 PID」不一致（窗口枚举会失败）。
    优先用显式 JDK 路径，最后才回落 ``which("java")``。
    """
    for cand in _JAVA_CANDIDATES:
        if cand and Path(cand).exists():
            return str(cand)
    found = shutil.which("java")
    if found:
        return found
    raise IcdError(E_UNSUPPORTED, "找不到 java 可执行文件（可用 ICDEV_JAVA 指定）")


def build_client_cmd(
    spec: LaunchSpec,
    run_dir: Path,
    username: str,
    multiplayer: str | None = None,
    world: str | None = None,
) -> list[str]:
    """``java <jvm_args> -cp <cp> <main> [--quickPlay*] --username <name>``。

    ``run_dir`` 是游戏目录：Minecraft 以进程 cwd 作为 game dir，调用方应把它
    原样传给 :func:`spawn` 的 ``cwd``；这里顺手确保目录存在。
    """
    if not username:
        raise IcdError(E_INVALID_ARGUMENT, "username 不能为空")
    if multiplayer and world:
        raise IcdError(E_INVALID_ARGUMENT, "multiplayer 与 world 只能二选一")

    run_dir = Path(run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)

    game_args: list[str] = []
    if multiplayer:
        game_args += ["--quickPlayMultiplayer", multiplayer]
    elif world:
        game_args += ["--quickPlaySingleplayer", world]
    game_args += ["--username", username]

    return [
        resolve_java(),
        *_expand_jvm_args(spec.jvm_args),
        "-cp",
        _classpath(spec),
        spec.main,
        *game_args,
    ]


def build_server_cmd(spec: LaunchSpec, run_dir: Path) -> list[str]:
    """服务端：覆盖 ``fabric.dli.env`` / ``fabric.dli.main``，并用 ``nogui`` 起来。"""
    run_dir = Path(run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)

    jvm_args = [
        arg
        for arg in _expand_jvm_args(spec.jvm_args)
        if not arg.startswith(("-Dfabric.dli.env=", "-Dfabric.dli.main="))
    ]

    return [
        resolve_java(),
        "-Dfabric.dli.env=server",
        f"-Dfabric.dli.main={_server_main()}",
        *jvm_args,
        "-cp",
        _classpath(spec),
        spec.main,
        "nogui",
    ]


def spawn(cmd: list[str], cwd: Path, log_path: Path) -> subprocess.Popen:
    """启动子进程：stdout/stderr 追加到 ``log_path``，不继承 stdin，独立进程组。"""
    if not cmd:
        raise IcdError(E_INVALID_ARGUMENT, "启动命令为空")

    cwd = Path(cwd)
    cwd.mkdir(parents=True, exist_ok=True)
    log_path = Path(log_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) if os.name == "nt" else 0

    handle = open(log_path, "ab")
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            stdin=subprocess.DEVNULL,
            stdout=handle,
            stderr=subprocess.STDOUT,
            creationflags=creationflags,
            close_fds=True,
        )
    except OSError as exc:
        raise IcdError(
            E_PROCESS_FAILED,
            f"无法启动进程：{cmd[0]}",
            detail=str(exc),
        ) from exc
    finally:
        handle.close()

    log.info("已启动 pid=%s → %s", proc.pid, log_path)
    return proc


def wait_exit(proc: subprocess.Popen, timeout_s: float | None = None) -> int | None:
    """等待进程退出；超时返回 ``None``（不杀进程，由调用方决定）。"""
    try:
        return proc.wait(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        return None
