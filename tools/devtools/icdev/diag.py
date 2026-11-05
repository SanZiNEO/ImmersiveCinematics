"""诊断会话（F5 diagnostics）读取与 manifest 生成。

目录结构::

    <runDir>/immersive_cinematics/diagnostics/
        f-round.json                      # opt-in manifest（enabled 必须为 true）
        sessions/<ts>-<name>/
            manifest.json
            environment.json
            events.jsonl
            images/
            performance/<case>-run<N>-summary.md

所有功能只读文件，不需要游戏进程；失败抛 :class:`IcdError`。
"""

from __future__ import annotations

import json
import logging
import re
from datetime import datetime
from pathlib import Path

from .errors import E_INVALID_ARGUMENT, E_NOT_FOUND, IcdError

log = logging.getLogger("icdev.diag")

DIAG_SUBDIR = Path("immersive_cinematics") / "diagnostics"
SESSIONS_DIRNAME = "sessions"
MANIFEST_NAME = "f-round.json"

# 与 LaneDiagnostics 解析器一致的默认值
DEFAULT_SETTLE_SECONDS = 2.0
DEFAULT_FRAMES = 600
DEFAULT_MAX_IMAGES = 4
DEFAULT_MAX_IMAGES_PER_CASE = 1

REQUIRED_ENTRIES = (
    "manifest.json",
    "environment.json",
    "events.jsonl",
    "images",
    "performance",
)

_NUMBER_RE = re.compile(r"^[+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$")
_UNITS = ("ms", "µs", "μs", "us", "ns", "%", "s")
_RUN_SUFFIX_RE = re.compile(r"-run\d+$")
_SEPARATOR_RE = re.compile(r"^:?-{2,}:?$")


# ------------------------------------------------------------------ 路径与列表

def sessions_root(run_dir: Path) -> Path:
    """``<runDir>/immersive_cinematics/diagnostics/sessions``。"""
    return Path(run_dir) / DIAG_SUBDIR / SESSIONS_DIRNAME


def diagnostics_root(run_dir: Path) -> Path:
    return Path(run_dir) / DIAG_SUBDIR


def list_sessions(run_dir: Path, limit: int = 5) -> list[dict]:
    """列出诊断会话，按目录 mtime 降序。

    每项 ``{name, path, summaries: [文件名], mtime: ISO8601}``。``limit <= 0``
    表示不截断。``run_dir`` 不存在 → E_NOT_FOUND；只有 sessions 目录缺失 → ``[]``。
    """
    run = Path(run_dir)
    if not run.exists():
        raise IcdError(E_NOT_FOUND, f"run dir 不存在：{run}", {"path": str(run)})
    root = sessions_root(run)
    if not root.is_dir():
        return []

    entries = [d for d in root.iterdir() if d.is_dir()]
    entries.sort(key=lambda d: d.stat().st_mtime, reverse=True)

    out: list[dict] = []
    for d in entries:
        perf = d / "performance"
        summaries = (
            sorted(p.name for p in perf.glob("*-summary.md")) if perf.is_dir() else []
        )
        out.append(
            {
                "name": d.name,
                "path": str(d),
                "summaries": summaries,
                "mtime": datetime.fromtimestamp(d.stat().st_mtime).isoformat(timespec="seconds"),
            }
        )
        if limit and limit > 0 and len(out) >= limit:
            break
    return out


# ------------------------------------------------------------------ manifest

def write_manifest(run_dir: Path, cases: list[dict], session: str | None = None) -> Path:
    """写 ``<runDir>/immersive_cinematics/diagnostics/f-round.json``。

    ``cases`` 每项 ``{id, script, run?, lanes?, frames?, content?}``，转成
    ``perf: {settleSeconds: 2, frames: 600, expectedLanes: lanes, content?}``。
    ``lanes`` 缺省为 -1（mod 端默认值，表示不限）。``enabled`` 恒为 true——
    mod 只在 ``enabled == true`` 时启用诊断。

    返回写出的 manifest 路径。
    """
    if not isinstance(cases, list) or not cases:
        raise IcdError(E_INVALID_ARGUMENT, "cases 必须是非空数组", {"cases": cases})

    converted = [_convert_case(case, i) for i, case in enumerate(cases)]

    payload: dict = {"enabled": True}
    if session:
        payload["sessionName"] = str(session)
    payload["maxImages"] = DEFAULT_MAX_IMAGES
    payload["maxImagesPerCase"] = DEFAULT_MAX_IMAGES_PER_CASE
    payload["cases"] = converted

    target = diagnostics_root(run_dir) / MANIFEST_NAME
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    except OSError as exc:
        raise IcdError(
            E_INVALID_ARGUMENT, f"f-round.json 写入失败：{target}", {"path": str(target), "error": str(exc)}
        ) from exc
    log.debug("写 manifest %s（%d 个 case）", target, len(converted))
    return target


def _convert_case(case: object, index: int) -> dict:
    if not isinstance(case, dict):
        raise IcdError(E_INVALID_ARGUMENT, f"cases[{index}] 必须是对象", {"case": case})

    case_id = case.get("id")
    script = case.get("script")
    if not isinstance(case_id, str) or not case_id.strip():
        raise IcdError(E_INVALID_ARGUMENT, f"cases[{index}] 缺少 id", {"case": case})
    if not isinstance(script, str) or not script.strip():
        raise IcdError(E_INVALID_ARGUMENT, f"cases[{index}] 缺少 script", {"case": case})

    run = _as_int(case.get("run", 1), f"cases[{index}].run", minimum=0)
    frames = _as_int(case.get("frames", DEFAULT_FRAMES), f"cases[{index}].frames", minimum=1)
    lanes_raw = case.get("lanes", -1)
    lanes = _as_int(lanes_raw, f"cases[{index}].lanes", minimum=-1)

    perf: dict = {
        "settleSeconds": DEFAULT_SETTLE_SECONDS,
        "frames": frames,
        "expectedLanes": lanes,
    }
    content = case.get("content")
    if isinstance(content, str) and content.strip():
        perf["content"] = content.strip()

    return {"id": case_id.strip(), "script": script.strip(), "run": run, "perf": perf}


def _as_int(value: object, label: str, minimum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise IcdError(E_INVALID_ARGUMENT, f"{label} 必须是整数", {label: value})
    if isinstance(value, float) and not value.is_integer():
        raise IcdError(E_INVALID_ARGUMENT, f"{label} 必须是整数", {label: value})
    number = int(value)
    if number < minimum:
        raise IcdError(E_INVALID_ARGUMENT, f"{label} 必须 >= {minimum}", {label: value})
    return number


# ------------------------------------------------------------------ summary 解析

def _split_row(line: str) -> list[str] | None:
    """``| a | b |`` → ['a','b']；不是表格行返回 None。"""
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|") or len(stripped) < 2:
        return None
    return [cell.strip() for cell in stripped[1:-1].split("|")]


def _is_separator(cells: list[str]) -> bool:
    return bool(cells) and all(_SEPARATOR_RE.match(c.strip()) for c in cells if c.strip())


def _to_number(text: str) -> int | float | None:
    """纯数字 → int/float；带 ms/µs/ns/% 等单位 → 剥掉单位后的数字；否则 None。"""
    value = text.strip()
    if not value:
        return None
    if _NUMBER_RE.match(value):
        return _finalize_number(float(value))
    for unit in _UNITS:
        if value.endswith(unit):
            head = value[: -len(unit)].strip()
            if _NUMBER_RE.match(head):
                return _finalize_number(float(head))
    return None


def _finalize_number(value: float) -> int | float:
    return int(value) if value.is_integer() else value


def _parse_metric_table(text: str) -> dict:
    """解析 markdown 性能表，返回 ``{metric: {n,p50,p95,mean,min,max}}``。"""
    lines = text.splitlines()
    metrics: dict = {}
    i = 0
    while i < len(lines):
        header = _split_row(lines[i])
        if not header or not header[0].strip().lower().startswith("metric"):
            i += 1
            continue

        columns = [cell.lower() for cell in header]
        i += 1
        if i < len(lines):
            sep = _split_row(lines[i])
            if sep and _is_separator(sep):
                i += 1

        while i < len(lines):
            row = _split_row(lines[i])
            if row is None or _is_separator(row):
                break
            name = row[0].strip()
            if not name or name.lower().startswith("metric"):
                break
            record = {}
            for idx, column in enumerate(columns[1:], start=1):
                if not column:
                    continue
                cell = row[idx] if idx < len(row) else ""
                record[column] = _to_number(cell)
            metrics[name] = record
            i += 1
        # 只解析第一张指标表：同一个 summary 里没有第二张
        break
    return metrics


def read_summaries(session_dir: Path) -> dict:
    """解析 ``performance/*-summary.md``。

    返回 ``{caseId: {metrics: {...}, text: 原文}}``。caseId 取文件名去掉
    ``-summary`` 与 ``-run<N>`` 后缀；同一 caseId 多次出现（多 run）时，后者
    以完整文件 stem（含 run）为 key，避免互相覆盖。
    """
    session = Path(session_dir)
    if not session.is_dir():
        raise IcdError(E_NOT_FOUND, f"会话目录不存在：{session}", {"path": str(session)})

    perf = session / "performance"
    if not perf.is_dir():
        return {}

    out: dict = {}
    for path in sorted(perf.glob("*-summary.md")):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            raise IcdError(
                E_INVALID_ARGUMENT,
                f"summary 读取失败：{path}",
                {"path": str(path), "error": str(exc)},
            ) from exc

        stem = path.stem[: -len("-summary")] if path.stem.endswith("-summary") else path.stem
        case_id = _RUN_SUFFIX_RE.sub("", stem) or stem
        key = case_id if case_id not in out else stem
        out[key] = {"metrics": _parse_metric_table(text), "text": text}
    return out


# ------------------------------------------------------------------ 结构校验

def analyze(session_dir: Path) -> dict:
    """校验一个诊断会话目录的结构完整性。

    检查：必需文件/目录是否齐全、JSON/JSONL 是否可解析、每个 summary 是否
    ``COMPLETE``（不含 ``INSUFFICIENT``）、指标表是否有数据行且至少一行有正样本。

    返回 ``{ok: bool, issues: [str], summaries: int}``；``issues`` 为空即 ok。
    """
    session = Path(session_dir)
    if not session.is_dir():
        raise IcdError(E_NOT_FOUND, f"会话目录不存在：{session}", {"path": str(session)})

    issues: list[str] = []

    for name in REQUIRED_ENTRIES:
        if not (session / name).exists():
            issues.append(f"missing {name}")

    _check_json(session / "manifest.json", issues)
    _check_json(session / "environment.json", issues)
    _check_events(session / "events.jsonl", issues)

    perf = session / "performance"
    summaries = sorted(perf.glob("*-summary.md")) if perf.is_dir() else []
    if perf.is_dir() and not summaries:
        issues.append("performance/ 里没有任何 *-summary.md")

    for path in summaries:
        text = path.read_text(encoding="utf-8", errors="replace")
        upper = text.upper()
        if "COMPLETE" not in upper or "INSUFFICIENT" in upper:
            issues.append(f"{path.name}: data sufficiency 不是 COMPLETE")
        metrics = _parse_metric_table(text)
        if not metrics:
            issues.append(f"{path.name}: 指标表没有数据行")
            continue
        # 单行 n=0 是合法的（例如 0 lane 会话的 lane_render）；整表无有效样本才算错
        if not any(
            isinstance(record.get("n"), (int, float)) and record["n"] > 0
            for record in metrics.values()
        ):
            issues.append(f"{path.name}: 指标表所有指标 n 均非正")

    return {"ok": not issues, "issues": issues, "summaries": len(summaries)}


def _check_json(path: Path, issues: list[str]) -> None:
    if not path.is_file():
        return  # 缺失已由 REQUIRED_ENTRIES 报过
    try:
        json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except (OSError, json.JSONDecodeError) as exc:
        issues.append(f"{path.name}: 无法解析（{exc}）")


def _check_events(path: Path, issues: list[str]) -> None:
    if not path.is_file():
        return
    for lineno, line in enumerate(
        path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
    ):
        if not line.strip():
            continue
        try:
            json.loads(line)
        except json.JSONDecodeError:
            issues.append(f"events.jsonl line {lineno}: bad JSON")
