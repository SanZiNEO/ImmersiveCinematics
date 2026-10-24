#!/usr/bin/env python3
"""MC 百科（mcmod.cn）词条编辑辅助工具。

    python mcmod.py <子命令> [参数]

只读（info/body/history 不需要登录，content/template 需要）：
    info <id>               词条概览
    body <id> [--out]       公开页渲染后的正文
    content <id> [--out]    编辑器原始正文（[h1=标题] 语法，改完可直接回写）
    history <id>            编辑记录

改正文：
    template <id>           取回整份表单 -> .mcmod-template.json
    submit <id> --body F    取模板 + 换正文 + 认验证码 + 提交（一条命令走完）
    payload <id> --body F   只生成 payload 文件
    save --payload P        提交现成的 payload
    draft --payload P       只存草稿，不发布
    captcha <id>            拉一张验证码存图，顺便打印 OCR 结果

接口要点（实测得来，改之前先看）：
- 页面「提交」= POST event.mcmod.cn/action/edit/doEdit/，表单只有 data 一项；
  doSave 是 Ctrl+S 存档，返回 state=0 也不发布——别拿它当提交用。
- data 是整份表单的 JSON（30+ 字段、链接、关系），逐字段重建容易冲掉无关字段，
  所以工具不重建：template 从 doLoad(key=auto) 取回服务端存档，只换 class-data.content。
- 会话和 User-Agent 绑定：同一份 cookie 换个 UA 就变游客，提交报 122（无编辑权限）。
  UA 默认取 cookie 文件第二行。
- 提交要验证码，错了返回 117；验证码是数字/字母，用 ddddocr 认，认错换图重试。
- 上一条编辑还在审核时提交返回 131，只能等审核通过；未登录返回 122。

登录态：环境变量 MCMOD_COOKIE，或同目录 .mcmod-cookie
（第一行 cookie，第二行可选 UA）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

BASE = "https://www.mcmod.cn"
EVENT = "https://event.mcmod.cn"
# 兜底 UA；站点按 UA 认会话，实际用哪个看 .mcmod-cookie 第二行（换浏览器就要换 UA）
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"
)
COOKIE_FILE = Path(__file__).with_name(".mcmod-cookie")


# ---------------------------------------------------------------- HTTP

def _cookie() -> str:
    """会话 cookie：环境变量优先，其次 .mcmod-cookie 第一行。"""
    return os.environ.get("MCMOD_COOKIE") or (
        COOKIE_FILE.read_text(encoding="utf-8").splitlines()[0].strip()
        if COOKIE_FILE.exists()
        else ""
    )


def _ua() -> str:
    """UA 跟会话绑定，必须和当初登录的浏览器一致，否则服务端当游客。"""
    if os.environ.get("MCMOD_UA"):
        return os.environ["MCMOD_UA"]
    if COOKIE_FILE.exists():
        lines = COOKIE_FILE.read_text(encoding="utf-8").splitlines()
        if len(lines) > 1 and lines[1].strip():
            return lines[1].strip()
    return UA


def http(url: str, data: dict | None = None, cookie: str | None = None) -> str:
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body)
    req.add_header("User-Agent", _ua())
    req.add_header("Accept-Language", "zh-CN,zh;q=0.9")
    # 编辑接口会看来源，少了这三样按外站请求处理
    req.add_header("Origin", BASE)
    req.add_header("Referer", f"{BASE}/class/edit/")
    req.add_header("X-Requested-With", "XMLHttpRequest")
    if body is not None:
        req.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
    ck = cookie if cookie is not None else _cookie()
    if ck:
        req.add_header("Cookie", ck)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:200]
        raise SystemExit(f"HTTP {exc.code} {url}\n{detail}") from exc
    return raw.decode("utf-8", "replace")


def http_bytes(url: str, cookie: str | None = None) -> bytes:
    """取图片用（验证码）；同样要带会话 cookie 和 UA。"""
    req = urllib.request.Request(url)
    req.add_header("User-Agent", _ua())
    req.add_header("Referer", f"{BASE}/class/edit/")
    ck = cookie if cookie is not None else _cookie()
    if ck:
        req.add_header("Cookie", ck)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read()


# ---------------------------------------------------------------- 验证码

# r= 是防缓存参数：不带的话拿到的还是上一张图，跟服务端存的答案对不上
CAPTCHA_URL = BASE + "/frame/edit/EditCaptcha/?id=class_edit_{id}&r={rnd}"

_OCR = None


def _fetch_captcha(class_id: str) -> bytes:
    return http_bytes(CAPTCHA_URL.format(id=class_id, rnd=int(time.time() * 1000)))


# 站点验证码固定两个字符（实测），认出来的长度对不上基本就是认歪了
CAPTCHA_LEN = 2


def _png(im) -> bytes:
    from io import BytesIO

    buf = BytesIO()
    im.save(buf, format="PNG")
    return buf.getvalue()


def _dilate(image: bytes) -> bytes:
    """深色线条加粗一档（MinFilter 取邻域最暗值，正好把细线撑开）。"""
    from io import BytesIO

    from PIL import Image, ImageFilter

    im = Image.open(BytesIO(image)).convert("L")
    return _png(im.filter(ImageFilter.MinFilter(3)))


def _ocr(image: bytes) -> str | None:
    """用 ddddocr 认一个字符；没装就返回 None（调用方转人工）。"""
    global _OCR
    try:
        import ddddocr
    except ImportError:
        return None
    if _OCR is None:  # 模型加载要几百毫秒，只建一次
        _OCR = ddddocr.DdddOcr(show_ad=False)
    code = _OCR.classification(image).strip()
    if not re.search(r"[A-Za-z0-9]", code):
        # 细线框字偶尔被认成汉字，加粗一档再试一次
        code = _OCR.classification(_dilate(image)).strip()
    return code


def _pad(image: bytes, margin: int = 6) -> bytes:
    """四周补白边。

    站点的字形本身贴着图边（有裁切），模型看到贴边的笔画容易认错；
    补一圈白边相当于给它留出「字的边界」。
    """
    from io import BytesIO

    from PIL import Image

    im = Image.open(BytesIO(image)).convert("RGB")
    out = Image.new("RGB", (im.width + margin * 2, im.height + margin * 2), (255, 255, 255))
    out.paste(im, (margin, margin))
    return _png(out)


def _split_glyphs(image: bytes) -> list[bytes]:
    """按列投影切成单字。

    整张图喂模型时它会把两个字符连起来认（或者认成一个汉字）；
    这里按「有墨水的列」分组，相邻笔画间隔很小就并回去，避免一个字被切碎。
    """
    from io import BytesIO

    from PIL import Image

    im = Image.open(BytesIO(_pad(image))).convert("L")
    w, h = im.size
    px = im.load()
    spans: list[list[int]] = []
    for x in range(w):
        if not any(px[x, y] < 200 for y in range(h)):
            continue
        if spans and x - spans[-1][1] <= 3:
            spans[-1][1] = x
        else:
            spans.append([x, x])
    boxes = [(x0, x1) for x0, x1 in spans if x1 - x0 >= 4]
    if len(boxes) < 2:  # 切不开就当整张处理，让模型自己看
        return [image]
    # 一个字被笔画间隙切成两段时，把间隔最小的相邻两段并回去
    while len(boxes) > CAPTCHA_LEN:
        gaps = [boxes[i + 1][0] - boxes[i][1] for i in range(len(boxes) - 1)]
        i = gaps.index(min(gaps))
        boxes[i : i + 2] = [(boxes[i][0], boxes[i + 1][1])]
    # 每个字再留一圈白边，跟整图一样避免贴边
    return [_pad(_png(im.crop((max(0, x0 - 2), 0, min(w, x1 + 3), h))), 4) for x0, x1 in boxes]


def _read_captcha(image: bytes) -> str | None:
    """逐字识别再拼起来。

    先按「每个字一个字符」严格拼，长度对得上就用；
    认多了（一个字被拆成两个字符）就每个字只取第一个字符——反正答案错了服务端也只是拒绝，
    比空手回去多一次机会。
    """
    strict = ""
    first = ""
    for glyph in _split_glyphs(image):
        code = _ocr(glyph)
        if code is None:
            return None  # 没装 OCR
        alnum = re.sub(r"[^A-Za-z0-9]", "", code)
        strict += alnum
        first += alnum[:1]
    if len(strict) == CAPTCHA_LEN:
        return strict
    return first if len(first) == CAPTCHA_LEN else None


def cmd_captcha(args) -> int:
    """拉一张新验证码存成图片，顺便打印逐字识别结果（人工核对用）。"""
    img = _fetch_captcha(args.id)
    out = Path(args.out)
    out.write_bytes(img)
    print(f"{len(img)} 字节 -> {out}")
    glyphs = _split_glyphs(img)
    for i, glyph in enumerate(glyphs, 1):
        code = _ocr(glyph)
        if code is None:
            print("OCR 不可用（未安装 ddddocr）")
            return 0
        print(f"  第 {i} 个字: {code!r}")
    print(f"合起来: {_read_captcha(img)!r}")
    return 0


# ---------------------------------------------------------------- HTML 解析

class _InnerHTML(HTMLParser):
    """抓取第一个 class 含指定 token 的元素的内部 HTML。

    用 HTMLParser 数深度而不是正则：词条正文里嵌着几十层 div/ul，
    正则配不齐结束标签，取出来的内容会缺尾或者多吞。
    """

    def __init__(self, token: str, tag: str | None = None) -> None:
        super().__init__(convert_charrefs=False)
        self.token = token
        self.tag = tag
        self.depth = 0
        self.captured: list[str] = []
        self.done = False

    def handle_starttag(self, tag, attrs):
        if self.done:
            return
        if self.depth:
            self.depth += 1
            self.captured.append(self.get_starttag_text() or "")
            return
        classes = (dict(attrs).get("class") or "").split()
        if self.token in classes and (self.tag is None or tag == self.tag):
            self.depth = 1

    def handle_startendtag(self, tag, attrs):
        if self.depth:
            self.captured.append(self.get_starttag_text() or "")

    def handle_endtag(self, tag):
        if not self.depth:
            return
        self.depth -= 1
        if self.depth == 0:
            self.done = True
        else:
            self.captured.append(f"</{tag}>")

    def handle_data(self, data):
        if self.depth:
            self.captured.append(data)

    def handle_entityref(self, name):
        if self.depth:
            self.captured.append(f"&{name};")

    def handle_charref(self, name):
        if self.depth:
            self.captured.append(f"&#{name};")

    def handle_comment(self, data):
        if self.depth:
            self.captured.append(f"<!--{data}-->")


def _strip_tags(html: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html)).strip()


# ---------------------------------------------------------------- 命令

def cmd_info(args) -> int:
    """词条概览：标题、正文长度、管理组 / 编辑组。"""
    html = http(f"{BASE}/class/{args.id}.html")
    title = re.search(r"<title>(.*?)</title>", html, re.S)
    body = _InnerHTML("text-area", "li")
    body.feed(html)

    def section(label: str) -> str:
        # 管理组 / 编辑组后面紧跟一小段文字，取到下一个标签为止就够判断有没有人了
        m = re.search(label + r"</[^>]+>(.{0,400}?)</", html, re.S)
        return _strip_tags(m.group(1))[:120] if m else ""

    info = {
        "id": args.id,
        "title": _strip_tags(title.group(1)) if title else "",
        "url": f"{BASE}/class/{args.id}.html",
        "body_chars": len(_strip_tags("".join(body.captured))),
        "body_html_chars": len("".join(body.captured)),
        "manage_group": section("管理组") or "暂无",
        "editor_group": section("编辑组") or "暂无",
    }
    print(json.dumps(info, ensure_ascii=False, indent=2))
    return 0


def cmd_body(args) -> int:
    """公开页渲染后的正文——给人看 / 做 diff 用，回写请用 content。"""
    html = http(f"{BASE}/class/{args.id}.html")
    parser = _InnerHTML("text-area", "li")
    parser.feed(html)
    content = "".join(parser.captured)
    if not content:
        print("未找到正文容器 li.text-area", file=sys.stderr)
        return 1
    if args.out:
        Path(args.out).write_text(content, encoding="utf-8")
        print(f"{len(content)} 字符 -> {args.out}")
    else:
        print(content)
    return 0


def cmd_content(args) -> int:
    """导出编辑器里的原始正文（含 [h1=标题] 标记），可直接改了再回写。"""
    html = http(f"{BASE}/class/edit/{args.id}/")
    m = re.search(
        r'<script id="editor-ueeditor" type="text/plain">(.*?)</script>', html, re.S
    )
    if not m:
        print("未取到编辑器内容——多半是没登录（会话和 UA 绑定）", file=sys.stderr)
        return 1
    content = m.group(1)
    if args.out:
        Path(args.out).write_text(content, encoding="utf-8")
        print(f"{len(content)} 字符 -> {args.out}")
    else:
        print(content)
    return 0


def cmd_history(args) -> int:
    html = http(f"{BASE}/class/history/{args.id}.html")
    rows = re.findall(
        r"由\s*<a[^>]*>(.*?)</a>\s*(编辑了模组|添加了模组).*?(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})",
        html,
        re.S,
    )
    for user, action, when in rows:
        print(f"{when}  {user}  {action}")
    if not rows:
        print("未解析到记录（页面结构可能变了）", file=sys.stderr)
        return 1
    return 0


def cmd_capture_js(args) -> int:
    print(
        f"""// 在 https://www.mcmod.cn/class/edit/{args.id}/ 的浏览器控制台执行（需已登录、页面加载完）：
// 会直接下载一个 mcmod-{args.id}-payload.json，作为后续 --template 用。
(() => {{
  const p = getEditorData(false);
  const blob = new Blob([JSON.stringify(p)], {{type: 'application/json'}});
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'mcmod-{args.id}-payload.json';
  a.click();
  console.log('payload chars:', JSON.stringify(p).length);
}})();
"""
    )
    return 0


def _check_template(data: dict, class_id: str) -> None:
    """模板必须属于同一个词条，否则提交会改错对象。"""
    if "class-data" not in data:
        raise SystemExit("模板里没有 class-data —— 重新跑 template，或用 capture-js 导出")
    if data.get("action") != "class_edit":
        raise SystemExit(f"模板 action={data.get('action')!r}，不是 class_edit")
    if str(data.get("edit-id")) != str(class_id) or str(data.get("class-id")) != str(class_id):
        raise SystemExit(
            f"模板属于词条 edit-id={data.get('edit-id')} class-id={data.get('class-id')}，"
            f"不是 {class_id} —— 重新跑 template {class_id}"
        )


def _apply_body(payload: dict, body: str, reason: str, remark: str) -> int:
    """换正文 + 改动说明/附言，返回原正文长度。模板其余字段一律不动。"""
    cd = payload["class-data"]
    old = len(cd.get("content", ""))
    cd["content"] = body
    if reason:
        cd["reason"] = reason[:255]  # 公开，显示在改动对比
    if remark:
        cd["remark"] = remark[:255]  # 只有审核员看得到
    payload.pop("captcha", None)  # 验证码每次提交都不一样，交给 _submit 填
    return old


def cmd_payload(args) -> int:
    """把新正文塞进模板，生成可以直接提交的 payload 文件。"""
    data = _load_template(args, args.id)
    new_body = Path(args.body).read_text(encoding="utf-8")
    old = _apply_body(data, new_body, args.reason, args.remark)

    Path(args.out).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    print(f"正文 {old} -> {len(new_body)} 字符，payload 已写入 {args.out}")
    print(f"action={data.get('action')} edit-id={data.get('edit-id')} 字段={len(cd)}")
    return 0


_LANG: dict | None = None


def _state_message(state) -> str:
    """把接口返回的 state 数字翻成站点自己的提示文案。"""
    global _LANG
    if _LANG is None:
        try:
            raw = http("https://static.mcmod.cn/public/lang/zh_cn.json?v=120")
            _LANG = json.loads(raw).get("zh_cn", {}).get("warning", {}).get("inform", {})
        except Exception:
            _LANG = {}
    return _LANG.get(str(state), "")


# 模板缓存：doLoad 拿回来的是整份表单，改正文之外还能反复复用，不必每次重取
TEMPLATE_CACHE = Path(__file__).with_name(".mcmod-template.json")


def cmd_template(args) -> int:
    """取回整份表单当提交模板——不需要开浏览器。

    doLoad 的 key=auto 就是编辑页自己自动存的那份，内容等于上次打开表单时的状态；
    没有这份存档时，去编辑页打开一次让它存一次即可（或改用 capture-js）。
    """
    raw = http(
        f"{BASE}/action/edit/doLoad/",
        data={"actionType": "class_edit", "actionID": str(args.id), "key": args.key},
    )
    try:
        result = json.loads(raw)
    except json.JSONDecodeError:
        print(f"响应不是 JSON：{raw[:200]}", file=sys.stderr)
        return 1
    if result.get("state") != 0:
        msg = _state_message(result.get("state"))
        print(f"取模板失败，state={result.get('state')} {msg}".rstrip(), file=sys.stderr)
        print("（先在编辑页让它自动存一次档，或改用 capture-js）", file=sys.stderr)
        return 1
    data = json.loads(result["html"]).get("savedata") or {}
    _check_template(data, args.id)
    out = Path(args.out or TEMPLATE_CACHE)
    out.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    cd = data.get("class-data", {})
    print(f"模板已写入 {out}（{len(cd)} 字段，正文 {len(cd.get('content', ''))} 字符）")
    return 0


def _load_template(args, class_id: str) -> dict:
    path = Path(args.template) if getattr(args, "template", None) else TEMPLATE_CACHE
    if not path.exists():
        raise SystemExit(f"没有模板文件 {path}：先跑 `template {class_id}`")
    data = json.loads(path.read_text(encoding="utf-8"))
    _check_template(data, class_id)
    return data


def _ocr_available() -> bool:
    try:
        import ddddocr  # noqa: F401
    except ImportError:
        return False
    return True


def _submit(payload: dict, class_id: str, captcha: str, retries: int, dry_run: bool) -> int:
    """POST doEdit。验证码自动识别，被拒或认不出就换一张重试。"""
    if dry_run:
        data = json.dumps(payload, ensure_ascii=False)
        print(f"[dry-run] POST {EVENT}/action/edit/doEdit/")
        print(f"  data    = {len(data)} 字符")
        print(f"  edit-id = {payload.get('edit-id')}  action={payload.get('action')}")
        print(f"  captcha = {captcha or '<自动识别>'}")
        return 0
    if not captcha and not _ocr_available():
        print("没装 ddddocr：先 pip install ddddocr，或用 --captcha 手工传", file=sys.stderr)
        return 2

    attempts = 1 if captcha else max(1, retries)
    last_img: bytes | None = None
    for attempt in range(1, attempts + 1):
        if captcha:
            code = captcha
        else:
            # 验证码用一次就作废，重试必须重新拉图
            last_img = _fetch_captcha(class_id)
            code = _read_captcha(last_img)
            if not code:
                print(f"第 {attempt} 张没认出来，换一张", file=sys.stderr)
                continue
        payload["captcha"] = code
        raw = http(f"{EVENT}/action/edit/doEdit/", data={"data": json.dumps(payload, ensure_ascii=False)})
        try:
            result = json.loads(raw)
        except json.JSONDecodeError:
            print(f"响应不是 JSON：{raw[:300]}", file=sys.stderr)
            return 1
        state = result.get("state")
        if state == 0:
            print(f"提交成功（state=0）{result.get('url', '')}".rstrip())
            return 0
        if state == 117 and not captcha and attempt < attempts:
            print(f"验证码 {code!r} 被拒，换一张重试（{attempt}/{attempts}）", file=sys.stderr)
            continue
        print(f"提交失败，state={state} {_state_message(state)}".rstrip(), file=sys.stderr)
        if state == 131:  # 上一条编辑还在审核，服务器不接受新的
            print("→ 等上一条审核通过再提交", file=sys.stderr)
        return 1

    if last_img:
        path = TEMPLATE_CACHE.parent / ".mcmod-captcha.png"
        path.write_bytes(last_img)
        print(f"试了 {attempts} 张都没过。图在 {path}", file=sys.stderr)
        print("→ 人工认一下，用 --captcha <两个字符> 重跑同一条命令", file=sys.stderr)
    return 1


def cmd_save(args) -> int:
    """提交现成的 payload 文件。"""
    payload = json.loads(Path(args.payload).read_text(encoding="utf-8"))
    class_id = str(payload.get("edit-id") or "")
    if not class_id:
        print("payload 里没有 edit-id", file=sys.stderr)
        return 1
    return _submit(payload, class_id, args.captcha, args.retries, args.dry_run)


def cmd_submit(args) -> int:
    """一条命令走完：取模板 → 换正文 → 认验证码 → 提交。"""
    class_id = str(args.id)
    payload = _load_template(args, class_id)
    body = Path(args.body).read_text(encoding="utf-8")
    old = _apply_body(payload, body, args.reason, args.remark)
    print(f"正文 {old} -> {len(body)} 字符")
    return _submit(payload, class_id, args.captcha, args.retries, args.dry_run)


def cmd_draft(args) -> int:
    """存草稿（Ctrl+S / 自动存档）= POST /action/edit/doSave/，不会公开发布。"""
    payload = json.loads(Path(args.payload).read_text(encoding="utf-8"))
    payload["captcha"] = args.captcha or ""
    form = {
        "actionType": payload.get("action", "class_edit"),
        "actionID": str(payload.get("edit-id", "")),
        "data": json.dumps(payload, ensure_ascii=False),
        "name": args.name or "",
    }
    if args.dry_run:
        print(f"[dry-run] POST {EVENT}/action/edit/doSave/ (草稿)")
        print(f"  actionType = {form['actionType']}")
        print(f"  actionID   = {form['actionID']}")
        print(f"  data       = {len(form['data'])} 字符")
        return 0

    raw = http(f"{EVENT}/action/edit/doSave/", data=form)
    try:
        result = json.loads(raw)
    except json.JSONDecodeError:
        print(f"响应不是 JSON：{raw[:300]}", file=sys.stderr)
        return 1
    state = result.get("state")
    if state == 0:
        print("草稿已保存（state=0）")
        return 0
    msg = _state_message(state)
    print(f"保存失败，state={state} {msg}".rstrip(), file=sys.stderr)
    return 1


# ---------------------------------------------------------------- CLI

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="MC 百科词条编辑辅助工具")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("info", help="词条概览")
    p.add_argument("id")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("body", help="导出词条正文 HTML（公开页面渲染后的版本）")
    p.add_argument("id")
    p.add_argument("--out")
    p.set_defaults(func=cmd_body)

    p = sub.add_parser("content", help="导出编辑器原始正文（[h1=标题] 标记，可回写）")
    p.add_argument("id")
    p.add_argument("--out")
    p.set_defaults(func=cmd_content)

    p = sub.add_parser("history", help="编辑记录")
    p.add_argument("id")
    p.set_defaults(func=cmd_history)

    p = sub.add_parser("capture-js", help="打印捕获 payload 模板的 JS（没有快速存档时的兜底）")
    p.add_argument("id")
    p.set_defaults(func=cmd_capture_js)

    p = sub.add_parser("template", help="从服务端快速存档取回表单模板（不需要浏览器）")
    p.add_argument("id")
    p.add_argument("--out", help=f"默认 {TEMPLATE_CACHE.name}")
    p.add_argument("--key", default="auto", help="存档 key，默认 auto")
    p.set_defaults(func=cmd_template)

    p = sub.add_parser("captcha", help="拉一张验证码存成图片（人工识别用）")
    p.add_argument("id")
    p.add_argument("--out", default="captcha.png")
    p.set_defaults(func=cmd_captcha)

    p = sub.add_parser("payload", help="用模板 + 新正文生成待提交 payload")
    p.add_argument("id")
    p.add_argument("--template", help=f"默认读 {TEMPLATE_CACHE.name}")
    p.add_argument("--body", required=True)
    p.add_argument("--reason", default="", help="改动说明（公开，显示在改动对比）")
    p.add_argument("--remark", default="", help="改动附言（仅审核员可见）")
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_payload)

    p = sub.add_parser("submit", help="取模板 + 换正文 + 认验证码 + 提交，一条命令走完")
    p.add_argument("id")
    p.add_argument("--body", required=True)
    p.add_argument("--template", help=f"默认读 {TEMPLATE_CACHE.name}")
    p.add_argument("--reason", default="")
    p.add_argument("--remark", default="")
    p.add_argument("--captcha", default="", help="手工验证码；不填就自动识别")
    p.add_argument("--retries", type=int, default=8, help="验证码换图重试次数")
    p.add_argument("--dry-run", action="store_true")
    p.set_defaults(func=cmd_submit)

    p = sub.add_parser("save", help="提交现成的 payload 文件")
    p.add_argument("--payload", required=True)
    p.add_argument("--captcha", default="", help="手工验证码；不填就自动识别")
    p.add_argument("--retries", type=int, default=8)
    p.add_argument("--dry-run", action="store_true")
    p.set_defaults(func=cmd_save)

    p = sub.add_parser("draft", help="只存草稿，不发布")
    p.add_argument("--payload", required=True)
    p.add_argument("--captcha", default="")
    p.add_argument("--name", default="")
    p.add_argument("--dry-run", action="store_true")
    p.set_defaults(func=cmd_draft)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
