"""运行目录快照与还原。

快照布局::

    <root>/<name>/
        manifest.json
        files/<相对仓库根的路径>

manifest 结构::

    {name, created_at, root, files: [rel...], hashes: {rel: sha1}}

相对路径一律相对 manifest 里的 ``root``（默认仓库根），且必须落在其内部；
越界（绝对路径、盘符、``..``）一律视为非法参数。

还原时 ``apply=True`` 会删除「manifest 里没有、但目标目录存在」的文件，
范围仅限 manifest 记录过的父目录之内（递归扫描这些父目录）。
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from .errors import E_INVALID_ARGUMENT, E_NOT_FOUND, IcdError
from .paths import REPO_ROOT, SNAPSHOT_ROOT

logger = logging.getLogger("icdev.snapshot")

MANIFEST_NAME = "manifest.json"
FILES_DIR = "files"


# ---------------------------------------------------------------- 内部工具

def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _sha1(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _check_name(name: str) -> str:
    """快照名必须是单层、安全的目录名。"""
    if not isinstance(name, str) or not name.strip():
        raise IcdError(E_INVALID_ARGUMENT, "快照名不能为空")
    if name in (".", "..") or name.startswith("."):
        raise IcdError(E_INVALID_ARGUMENT, f"非法快照名：{name}")
    if any(ch in name for ch in ("/", "\\", ":", "\0")):
        raise IcdError(E_INVALID_ARGUMENT, f"快照名不能含路径分隔符：{name}")
    if name != name.strip():
        raise IcdError(E_INVALID_ARGUMENT, f"快照名首尾不能有空白：{name!r}")
    return name


def _to_posix(rel: Path) -> str:
    return rel.as_posix()


def _check_rel(rel: str, base: Path) -> Path:
    """把 manifest 里的相对路径解析成绝对路径，越界即抛 E_INVALID_ARGUMENT。"""
    if not isinstance(rel, str) or not rel:
        raise IcdError(E_INVALID_ARGUMENT, f"非法相对路径：{rel!r}")
    posix = PurePosixPath(rel.replace("\\", "/"))
    if posix.is_absolute() or not posix.parts:
        raise IcdError(E_INVALID_ARGUMENT, f"相对路径不能是绝对路径：{rel}")
    if any(part in ("", ".", "..") for part in posix.parts):
        raise IcdError(E_INVALID_ARGUMENT, f"相对路径不能含 . / ..：{rel}")
    if ":" in posix.parts[0]:
        raise IcdError(E_INVALID_ARGUMENT, f"相对路径不能含盘符：{rel}")
    base_r = base.resolve()
    target = (base_r / Path(*posix.parts)).resolve()
    if target != base_r and not target.is_relative_to(base_r):
        raise IcdError(E_INVALID_ARGUMENT, f"相对路径越出仓库根：{rel}")
    return target


def _snapshot_dir(name: str, root: Path) -> Path:
    _check_name(name)
    root_r = root.resolve()
    path = (root_r / name).resolve()
    if path.parent != root_r:
        raise IcdError(E_INVALID_ARGUMENT, f"快照路径越界：{name}")
    return path


def _read_manifest(snap_dir: Path) -> dict:
    p = snap_dir / MANIFEST_NAME
    if not p.is_file():
        raise IcdError(E_NOT_FOUND, f"快照缺少 {MANIFEST_NAME}：{snap_dir.name}")
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise IcdError(E_INVALID_ARGUMENT, f"manifest 无法解析：{snap_dir.name}", str(exc)) from exc
    if not isinstance(data, dict):
        raise IcdError(E_INVALID_ARGUMENT, f"manifest 结构非法：{snap_dir.name}")
    return data


def _write_atomic(path: Path, data: bytes) -> None:
    """写 tmp 再 replace，避免半写。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def _copy_atomic(src: Path, dest: Path) -> None:
    """流式复制到 tmp 再 replace，避免半写与整文件进内存。"""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".tmp")
    with src.open("rb") as fsrc, tmp.open("wb") as fdst:
        shutil.copyfileobj(fsrc, fdst, 1 << 20)
        fdst.flush()
        os.fsync(fdst.fileno())
    os.replace(tmp, dest)


# ---------------------------------------------------------------- 公开 API

def create(name: str, targets: list[Path], root: Path = SNAPSHOT_ROOT,
           repo_root: Path = REPO_ROOT) -> dict:
    """把 targets（文件或目录，递归）复制进快照并写 manifest。"""
    _check_name(name)
    repo_root = Path(repo_root).resolve()
    if not targets:
        raise IcdError(E_INVALID_ARGUMENT, "targets 不能为空")

    snap_dir = _snapshot_dir(name, Path(root))
    if snap_dir.exists():
        if any(snap_dir.iterdir()):
            raise IcdError(E_INVALID_ARGUMENT, f"快照已存在：{name}")
    files_dir = snap_dir / FILES_DIR
    files_dir.mkdir(parents=True, exist_ok=True)

    rels: list[str] = []
    seen: set[str] = set()
    for raw in targets:
        target = Path(raw)
        if not target.is_absolute():
            target = repo_root / target
        target = target.resolve()
        if target != repo_root and not target.is_relative_to(repo_root):
            raise IcdError(E_INVALID_ARGUMENT, f"target 不在仓库根内：{target}")
        if not target.exists():
            raise IcdError(E_NOT_FOUND, f"target 不存在：{target}")

        if target.is_file():
            candidates = [target]
        elif target.is_dir():
            candidates = [p for p in sorted(target.rglob("*"))
                          if p.is_file() and not p.is_symlink()]
        else:
            raise IcdError(E_INVALID_ARGUMENT, f"target 既不是文件也不是目录：{target}")

        for src in candidates:
            rel = _to_posix(src.relative_to(repo_root))
            if rel in seen:
                continue
            seen.add(rel)
            dest = files_dir / Path(*PurePosixPath(rel).parts)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)
            rels.append(rel)

    rels.sort()
    manifest = {
        "name": name,
        "created_at": _now(),
        "root": str(repo_root),
        "files": rels,
        "hashes": {rel: _sha1(files_dir / Path(*PurePosixPath(rel).parts)) for rel in rels},
    }
    _write_atomic(snap_dir / MANIFEST_NAME,
                  json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8"))
    logger.info("snapshot created name=%s files=%d", name, len(rels))
    return {"name": name, "path": str(snap_dir), "files": len(rels)}


def restore(name: str, root: Path = SNAPSHOT_ROOT, apply: bool = True,
            repo_root: Path = REPO_ROOT) -> dict:
    """按 manifest 还原；apply=True 时删除 manifest 之外的新增文件。"""
    snap_dir = _snapshot_dir(name, Path(root))
    if not snap_dir.is_dir():
        raise IcdError(E_NOT_FOUND, f"快照不存在：{name}")
    manifest = _read_manifest(snap_dir)
    base = Path(manifest.get("root") or repo_root)
    files_dir = snap_dir / FILES_DIR
    blobs = manifest.get("files") or []
    if not isinstance(blobs, list):
        raise IcdError(E_INVALID_ARGUMENT, f"manifest.files 非法：{name}")

    restored = 0
    missing: list[str] = []
    targets: dict[str, Path] = {}
    for rel in blobs:
        dest = _check_rel(rel, base)
        targets[rel] = dest
        src = files_dir / Path(*PurePosixPath(rel.replace("\\", "/")).parts)
        if not src.is_file():
            missing.append(rel)
            continue
        if apply:
            _copy_atomic(src, dest)
        restored += 1

    removed: list[str] = []
    if apply:
        base_r = base.resolve()
        parents = sorted({dest.parent for dest in targets.values()})
        for parent in parents:
            if not parent.is_dir():
                continue
            for found in sorted(parent.rglob("*")):
                if found.is_symlink() or not found.is_file():
                    continue
                fp = found.resolve()
                if fp == base_r or not fp.is_relative_to(base_r):
                    continue
                rel = _to_posix(fp.relative_to(base_r))
                if rel in targets:
                    continue
                found.unlink()
                removed.append(rel)

    logger.info("snapshot restored name=%s restored=%d removed=%d missing=%d",
                name, restored, len(removed), len(missing))
    return {"restored": restored, "removed": sorted(removed), "missing": sorted(missing)}


def list_snapshots(root: Path = SNAPSHOT_ROOT) -> list[dict]:
    """列出快照，按 created_at 降序。读不了 manifest 的目录按目录 mtime 兜底。"""
    root = Path(root)
    if not root.is_dir():
        return []
    out: list[dict] = []
    for entry in root.iterdir():
        if not entry.is_dir():
            continue
        created_at: str | None = None
        count = 0
        try:
            manifest = _read_manifest(entry)
            created_at = manifest.get("created_at") if isinstance(manifest.get("created_at"), str) else None
            files = manifest.get("files")
            count = len(files) if isinstance(files, list) else 0
        except IcdError as exc:
            logger.debug("skip snapshot %s: %s", entry.name, exc)
        if not created_at:
            created_at = datetime.fromtimestamp(
                entry.stat().st_mtime, timezone.utc).isoformat(timespec="seconds")
        out.append({"name": entry.name, "created_at": created_at, "files": count})
    out.sort(key=lambda item: item["created_at"], reverse=True)
    return out


def delete(name: str, root: Path = SNAPSHOT_ROOT) -> None:
    """删除整个快照目录；不存在抛 E_NOT_FOUND。"""
    snap_dir = _snapshot_dir(name, Path(root))
    if not snap_dir.is_dir():
        raise IcdError(E_NOT_FOUND, f"快照不存在：{name}")
    shutil.rmtree(snap_dir)
    logger.info("snapshot deleted name=%s", name)
