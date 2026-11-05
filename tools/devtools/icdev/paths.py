"""路径与环境常量（唯一的「仓库在哪」来源）。"""

from __future__ import annotations

import os
from pathlib import Path

# tools/devtools/icdev/paths.py -> tools/devtools -> tools -> 仓库根
DEVTOOLS_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = DEVTOOLS_DIR.parents[1]

FABRIC_RUN = REPO_ROOT / "fabric" / "run"
FORGE_RUN = REPO_ROOT / "forge" / "run"

# 装置默认 run dir（第二/第三个客户端放这里，避免与 fabric/run 抢文件）
ICDEV_HOME = Path(os.environ.get("ICDEV_HOME", "E:/tmp/icdev"))
DEFAULT_SERVER_DIR = ICDEV_HOME / "server"
DEFAULT_CLIENT_DIRS = [FABRIC_RUN, ICDEV_HOME / "clientB", ICDEV_HOME / "clientC"]

SNAPSHOT_ROOT = ICDEV_HOME / "snapshots"
SHOT_ROOT = ICDEV_HOME / "shots"
LOG_ROOT = ICDEV_HOME / "logs"

DEFAULT_RCON_PORT = 25575
DEFAULT_RCON_PASSWORD = "icdev"
DEFAULT_GAME_PORT = 25565


def ensure_dirs() -> None:
    """按需创建 icdev 自己的目录（不碰仓库内的 run dir）。"""
    for d in (ICDEV_HOME, SNAPSHOT_ROOT, SHOT_ROOT, LOG_ROOT):
        d.mkdir(parents=True, exist_ok=True)
