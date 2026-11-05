"""`immersive_cinematics.json` 读写与校验。

配置文件位于 ``<runDir>/config/immersive_cinematics.json``，服务端与客户端使用同名文件。
写入路径：白名单键 → 类型/范围校验 → 读现状 → 原子替换（tmp + os.replace）。
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
from pathlib import Path

from .errors import E_CONFIG_INVALID, IcdError

log = logging.getLogger("icdev.config")

CONFIG_DIRNAME = "config"
CONFIG_FILENAME = "immersive_cinematics.json"

# 白名单：key -> {"type": int|bool|float, "min": ..., "max": ...}
# 类型语义：bool 必须严格是 bool；int 不接受 bool；float 接受 int（含整值 float）。
WHITELIST: dict[str, dict] = {
    # 跳过/投票 HUD
    "skipHoldThresholdMs": {"type": int, "min": 1, "max": 60000},
    "showSkipHud": {"type": bool},
    "skipVoteRatio": {"type": int, "min": 10, "max": 100},
    "skipHudX": {"type": int, "min": 0, "max": 100},
    "skipHudY": {"type": int, "min": 0, "max": 100},
    "voteHudX": {"type": int, "min": 0, "max": 100},
    "voteHudY": {"type": int, "min": 0, "max": 100},
    # 调试
    "debugLogging": {"type": bool},
    # 触发器轮询间隔（tick）
    "triggerPollIntervalLocation": {"type": int, "min": 1, "max": 600},
    "triggerPollIntervalBiome": {"type": int, "min": 1, "max": 600},
    "triggerPollIntervalInventory": {"type": int, "min": 1, "max": 600},
    "triggerPollIntervalStructure": {"type": int, "min": 1, "max": 600},
    "triggerPollIntervalGamestage": {"type": int, "min": 1, "max": 600},
}


# ---- 路径 ----


def config_path(run_dir: Path) -> Path:
    """客户端 run dir 里的模组配置文件。"""
    return Path(run_dir) / CONFIG_DIRNAME / CONFIG_FILENAME


def server_config_path(run_dir: Path) -> Path:
    """服务端 run dir 里的同名配置文件（布局一致）。"""
    return Path(run_dir) / CONFIG_DIRNAME / CONFIG_FILENAME


# ---- 读 ----


def _load(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise IcdError(E_CONFIG_INVALID, f"配置文件读取失败: {path}", detail=str(exc)) from exc
    if not raw.strip():
        return {}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise IcdError(E_CONFIG_INVALID, f"配置文件解析失败: {path}", detail=str(exc)) from exc
    if not isinstance(data, dict):
        raise IcdError(E_CONFIG_INVALID, f"配置文件顶层不是对象: {path}", detail=type(data).__name__)
    return data


def read_all(run_dir: Path) -> dict:
    """返回整份配置；文件不存在返回空 dict。"""
    return _load(config_path(run_dir))


def get(run_dir: Path, key: str) -> object:
    """读单个键；文件不存在或键缺失返回 ``None``。"""
    data = _load(config_path(run_dir))
    return data.get(key)


# ---- 校验 ----


def _coerce(key: str, value: object) -> object:
    """按白名单校验并归一化（float 整值转 int）；不合法抛 IcdError。"""
    rule = WHITELIST[key]
    expected = rule["type"]

    if expected is bool:
        if not isinstance(value, bool):
            raise IcdError(
                E_CONFIG_INVALID,
                f"配置项 {key} 需要 bool",
                detail={"key": key, "value": value, "expected": "bool"},
            )
        return value

    if expected is int:
        if isinstance(value, bool) or not isinstance(value, int):
            # 容忍 JSON 里的整值浮点
            if isinstance(value, float) and value.is_integer():
                value = int(value)
            else:
                raise IcdError(
                    E_CONFIG_INVALID,
                    f"配置项 {key} 需要 int",
                    detail={"key": key, "value": value, "expected": "int"},
                )
        if "min" in rule and value < rule["min"]:
            raise IcdError(
                E_CONFIG_INVALID,
                f"配置项 {key} 低于下限 {rule['min']}",
                detail={"key": key, "value": value, "min": rule["min"], "max": rule.get("max")},
            )
        if "max" in rule and value > rule["max"]:
            raise IcdError(
                E_CONFIG_INVALID,
                f"配置项 {key} 超过上限 {rule['max']}",
                detail={"key": key, "value": value, "min": rule.get("min"), "max": rule["max"]},
            )
        return int(value)

    if expected is float:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise IcdError(
                E_CONFIG_INVALID,
                f"配置项 {key} 需要 float",
                detail={"key": key, "value": value, "expected": "float"},
            )
        value = float(value)
        if "min" in rule and value < rule["min"]:
            raise IcdError(
                E_CONFIG_INVALID,
                f"配置项 {key} 低于下限 {rule['min']}",
                detail={"key": key, "value": value, "min": rule["min"], "max": rule.get("max")},
            )
        if "max" in rule and value > rule["max"]:
            raise IcdError(
                E_CONFIG_INVALID,
                f"配置项 {key} 超过上限 {rule['max']}",
                detail={"key": key, "value": value, "min": rule.get("min"), "max": rule["max"]},
            )
        return value

    raise IcdError(E_CONFIG_INVALID, f"配置项 {key} 的白名单类型非法", detail={"key": key})


# ---- 写 ----


def _dump(path: Path, data: dict) -> None:
    """缩进 2、保留中文、原子替换。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    fd, tmp_name = tempfile.mkstemp(prefix=".icdev-config-", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp_name, path)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def set(run_dir: Path, key: str, value: object) -> object:
    """设置白名单键，返回旧值；非法键/类型/范围抛 ``IcdError(E_CONFIG_INVALID)``。"""
    if key not in WHITELIST:
        raise IcdError(
            E_CONFIG_INVALID,
            f"配置项 {key} 不在白名单内",
            detail={"key": key, "allowed": sorted(WHITELIST)},
        )
    normalized = _coerce(key, value)
    path = config_path(run_dir)
    data = _load(path)
    old = data.get(key)
    data[key] = normalized
    try:
        _dump(path, data)
    except OSError as exc:
        raise IcdError(E_CONFIG_INVALID, f"配置文件写入失败: {path}", detail=str(exc)) from exc
    log.debug("config set %s: %r -> %r (%s)", key, old, normalized, path)
    return old
