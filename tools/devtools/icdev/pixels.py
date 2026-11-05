"""像素级取证：亮像素簇、区域统计、双图差异、天空/地平线报告。

只依赖 PIL + numpy，不依赖游戏进程。所有公开函数失败时抛 :class:`IcdError`。

坐标约定：所有返回值里的 x/y/bbox 都是**原图像素**坐标（即使只统计了某个
band 或 region 子区域），方便直接和截图对齐。
"""

from __future__ import annotations

import logging
from pathlib import Path

from .errors import E_INVALID_ARGUMENT, E_NOT_FOUND, E_UNSUPPORTED, IcdError

log = logging.getLogger("icdev.pixels")

try:  # 允许在没装成像依赖的环境里 import 本模块
    import numpy as np
    from PIL import Image
except ImportError as _exc:  # pragma: no cover - 依赖缺失只在调用时报错
    np = None
    Image = None
    _IMPORT_ERROR: ImportError | None = _exc
else:
    _IMPORT_ERROR = None


# ------------------------------------------------------------------ 基础工具

def _require_imaging() -> None:
    if _IMPORT_ERROR is not None:
        raise IcdError(
            E_UNSUPPORTED,
            "pixels 需要 PIL + numpy（请在 tools/devtools 的 venv 里运行）",
            {"missing": str(_IMPORT_ERROR)},
        )


def _load_rgb(path: Path | str) -> "np.ndarray":
    """读成 int16 RGB 数组，避免后续算术在 uint8 上溢出。"""
    _require_imaging()
    p = Path(path)
    if not p.is_file():
        raise IcdError(E_NOT_FOUND, f"图片不存在：{p}", {"path": str(p)})
    try:
        with Image.open(p) as im:
            arr = np.asarray(im.convert("RGB"), dtype=np.int16)
    except Exception as exc:  # 损坏文件 / 非图片
        raise IcdError(
            E_INVALID_ARGUMENT, f"无法解码图片：{p}", {"path": str(p), "error": str(exc)}
        ) from exc
    return arr


def _band_rows(height: int, band: tuple[float, float] | None) -> tuple[int, int]:
    """band=(y0,y1) 是相对高度比例，返回 [y0,y1) 行区间。"""
    if band is None:
        return 0, height
    if not isinstance(band, (list, tuple)) or len(band) != 2:
        raise IcdError(E_INVALID_ARGUMENT, "band 必须是 (y0,y1) 两个相对高度比例", {"band": band})
    try:
        y0f, y1f = float(band[0]), float(band[1])
    except (TypeError, ValueError) as exc:
        raise IcdError(E_INVALID_ARGUMENT, "band 元素必须是数字", {"band": band}) from exc
    if not (0.0 <= y0f < y1f <= 1.0):
        raise IcdError(
            E_INVALID_ARGUMENT, "band 需满足 0 <= y0 < y1 <= 1", {"band": list(band)}
        )
    y0 = max(0, min(height, int(round(y0f * height))))
    y1 = max(0, min(height, int(round(y1f * height))))
    if y1 <= y0:
        raise IcdError(E_INVALID_ARGUMENT, "band 在该图高度上为空", {"band": list(band), "height": height})
    return y0, y1


def _region_rect(region: object, width: int, height: int) -> tuple[int, int, int, int]:
    """region → (x0,y0,x1,y1)，支持 {x,y,w,h} / {x0,y0,x1,y1} / 四元序列。"""
    if region is None:
        return 0, 0, width, height
    if isinstance(region, dict):
        keys = set(region)
        if {"x", "y", "w", "h"} <= keys:
            vals = (region["x"], region["y"], region["x"] + region["w"], region["y"] + region["h"])
        elif {"x0", "y0", "x1", "y1"} <= keys:
            vals = (region["x0"], region["y0"], region["x1"], region["y1"])
        else:
            raise IcdError(
                E_INVALID_ARGUMENT,
                "region 需含 x/y/w/h 或 x0/y0/x1/y1",
                {"region": region},
            )
    elif isinstance(region, (list, tuple)) and len(region) == 4:
        vals = tuple(region)
    else:
        raise IcdError(
            E_INVALID_ARGUMENT, "region 需为 dict 或 4 元素序列", {"region": region}
        )

    try:
        x0, y0, x1, y1 = (int(round(float(v))) for v in vals)
    except (TypeError, ValueError) as exc:
        raise IcdError(E_INVALID_ARGUMENT, "region 元素必须是数字", {"region": region}) from exc

    x0 = max(0, min(width, x0))
    x1 = max(0, min(width, x1))
    y0 = max(0, min(height, y0))
    y1 = max(0, min(height, y1))
    if x1 <= x0 or y1 <= y0:
        raise IcdError(
            E_INVALID_ARGUMENT, "region 在图像范围内为空", {"region": region, "size": [width, height]}
        )
    return x0, y0, x1, y1


# ------------------------------------------------------------------ 亮像素簇

def clusters(
    path: Path,
    threshold: int = 190,
    band: tuple[float, float] | None = None,
    gap: int = 40,
) -> list[dict]:
    """把「亮像素」按列聚成簇。

    亮像素 = RGB 三通道都 >= ``threshold``。``band`` 是相对高度比例
    ``(y0, y1)``，默认整图。列与列之间允许最多 ``gap`` 个空列，超过就断开成新簇。

    每个簇::

        {"x0", "x1", "y0", "y1", "px", "centerPct": [x%, y%]}
    """
    img = _load_rgb(path)
    height, width, _ = img.shape
    row0, row1 = _band_rows(height, band)
    gap = max(1, int(gap))

    mask = img[row0:row1].min(axis=2) >= int(threshold)
    column_hits = mask.sum(axis=0)
    columns = np.nonzero(column_hits > 0)[0]
    if columns.size == 0:
        return []

    spans: list[tuple[int, int]] = []
    start = prev = int(columns[0])
    for raw in columns[1:]:
        col = int(raw)
        if col - prev > gap:
            spans.append((start, prev))
            start = col
        prev = col
    spans.append((start, prev))

    out: list[dict] = []
    for x0, x1 in spans:
        sub = mask[:, x0 : x1 + 1]
        rows = np.nonzero(sub.any(axis=1))[0]
        if rows.size == 0:  # 理论不可达（列本身必须有亮像素）
            continue
        y0 = int(rows[0]) + row0
        y1 = int(rows[-1]) + row0
        out.append(
            {
                "x0": x0,
                "x1": x1,
                "y0": y0,
                "y1": y1,
                "px": int(sub.sum()),
                "centerPct": [
                    round(100.0 * (x0 + x1) / 2.0 / width, 2),
                    round(100.0 * (y0 + y1) / 2.0 / height, 2),
                ],
            }
        )
    return out


# ------------------------------------------------------------------ 区域统计

def region_stats(path: Path, region: dict | None = None) -> dict:
    """区域均值/标准差。

    返回 ``{width, height, meanRgb: [r,g,b], stdRgb: [r,g,b]}``，值为 0..255 的
    浮点数；``width``/``height`` 是**统计区域**的尺寸（无 region 时等于整图）。
    """
    img = _load_rgb(path)
    height, width, _ = img.shape
    x0, y0, x1, y1 = _region_rect(region, width, height)
    sub = img[y0:y1, x0:x1].astype(np.float64)
    flat = sub.reshape(-1, 3)
    mean = flat.mean(axis=0)
    std = flat.std(axis=0)
    return {
        "width": x1 - x0,
        "height": y1 - y0,
        "meanRgb": [round(float(v), 3) for v in mean],
        "stdRgb": [round(float(v), 3) for v in std],
    }


# ------------------------------------------------------------------ 双图差异

def diff(
    path_a: Path,
    path_b: Path,
    threshold: int = 60,
    region: dict | None = None,
) -> dict:
    """两图差异（逐通道绝对差之和）。

    变化像素判据与既有分析脚本一致：``|a-b|`` 三通道求和 > ``threshold``。
    ``meanAbsDiff`` 是该和数组在统计区域上的均值。region 内的统计仍返回
    原图坐标。

    返回::

        {changedPx, pct, meanAbsDiff,
         bbox: [x0,y0,x1,y1] | None,
         cols: {changed, min, max}, rows: {changed, min, max}}
    """
    a = _load_rgb(path_a)
    b = _load_rgb(path_b)
    if a.shape != b.shape:
        raise IcdError(
            E_INVALID_ARGUMENT,
            "两张图片尺寸不一致",
            {"a": list(a.shape[:2]), "b": list(b.shape[:2])},
        )
    height, width, _ = a.shape
    x0, y0, x1, y1 = _region_rect(region, width, height)

    da = a[y0:y1, x0:x1].astype(np.int32)
    db = b[y0:y1, x0:x1].astype(np.int32)
    delta = np.abs(da - db).sum(axis=2)
    mask = delta > float(threshold)

    changed = int(mask.sum())
    total = int(mask.size)
    result: dict = {
        "changedPx": changed,
        "pct": round(100.0 * changed / total, 4) if total else 0.0,
        "meanAbsDiff": round(float(delta.mean()), 4) if total else 0.0,
        "bbox": None,
        "cols": {"changed": 0, "min": None, "max": None},
        "rows": {"changed": 0, "min": None, "max": None},
    }
    if changed == 0:
        return result

    ys, xs = np.nonzero(mask)
    result["bbox"] = [
        int(xs.min()) + x0,
        int(ys.min()) + y0,
        int(xs.max()) + x0,
        int(ys.max()) + y0,
    ]
    per_col = np.nonzero(mask.sum(axis=0))[0]
    per_row = np.nonzero(mask.sum(axis=1))[0]
    result["cols"] = {
        "changed": int(per_col.size),
        "min": int(per_col[0]) + x0,
        "max": int(per_col[-1]) + x0,
    }
    result["rows"] = {
        "changed": int(per_row.size),
        "min": int(per_row[0]) + y0,
        "max": int(per_row[-1]) + y0,
    }
    return result


# ------------------------------------------------------------------ 天空报告

def sky_report(path: Path, band: tuple[float, float] | None = None) -> dict:
    """天空带与天际线。

    天空像素判据：``b - r > 30`` 且 ``b > 120``（与 tower_count.py 一致）。
    对每个有天空的列取最深天空行（图像 y 向下），得到地平线样本。

    - ``median`` / ``p95``：地平线列样本的中位数与 95 分位（原图行号）
    - ``holeDepth``：最深天空行比地平线中位数低多少像素（地形块缺失会让天空
      下探到地面位置；对不足半数宽度的缺口都稳健；0 = 无缺口）
    - ``skyPct``：天空像素占统计 band 的百分比

    没有任何天空像素时 ``median``/``p95``/``holeDepth`` 均为 ``None``，
    ``skyPct`` 为 0。
    """
    img = _load_rgb(path)
    height, width, _ = img.shape
    row0, row1 = _band_rows(height, band)

    sub = img[row0:row1].astype(np.int32)
    r, _, b = sub[..., 0], sub[..., 1], sub[..., 2]
    sky = ((b - r) > 30) & (b > 120)

    sky_px = int(sky.sum())
    total = int(sky.size)
    report: dict = {
        "median": None,
        "p95": None,
        "holeDepth": None,
        "skyPct": round(100.0 * sky_px / total, 2) if total else 0.0,
    }
    if sky_px == 0:
        return report

    rows = np.arange(row1 - row0).reshape(-1, 1)
    deepest = np.where(sky, rows, -1).max(axis=0)  # 每列最深天空行（band 内）
    has_sky = sky.any(axis=0)
    horizon = deepest[has_sky]

    median_local = float(np.median(horizon))
    p95_local = float(np.percentile(horizon, 95))
    report["median"] = round(median_local + row0, 2)
    report["p95"] = round(p95_local + row0, 2)
    report["holeDepth"] = round(max(0.0, float(deepest.max()) - median_local), 2)
    return report
