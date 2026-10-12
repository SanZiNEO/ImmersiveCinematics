import os, re

BASE = os.path.abspath("E:/Documents/GitHub/ImmersiveCinematics/plans/0.3.6")
COMPLETE = os.path.abspath("E:/Documents/GitHub/ImmersiveCinematics/plans/complete/0.3.6")

# 旧路径（相对 BASE）-> 新路径（相对 BASE；../complete/... 表示移到 complete/0.3.6）
MOVES = {
    "parallel-playback.md": "F-playback/parallel-playback.md",
    "camera-composition.md": "F-playback/camera-composition.md",
    "multi-camera-rendering.md": "F-playback/multi-camera-rendering.md",
    "script-loop.md": "done/script-loop.md",
    "variable-frame.md": "D-transition/variable-frame.md",
    "script-model.md": "G-script-model/script-model.md",
    "scene-transition.md": "D-transition/scene-transition.md",
    "editor-webui-migration.md": "done/editor-webui-migration.md",
    "editor-script-graph.md": "done/editor-script-graph.md",
    "templates.md": "E-template/templates.md",
    "trigger-conditions.md": "C-trigger/trigger-conditions.md",
    "region-sync.md": "H-other/region-sync.md",
    "condition-system.md": "C-trigger/condition-system.md",
    "playback-relations.md": "C-trigger/playback-relations.md",
    "point-source-time-mode.md": "C-trigger/point-source-time-mode.md",
    "state-tracking.md": "C-trigger/state-tracking.md",
    "trigger-continuity.md": "C-trigger/trigger-continuity.md",
    "hud-hard-hide.md": "H-other/hud-hard-hide.md",
    "overlay-color-mask.md": "D-transition/overlay-color-mask.md",
    "script-i18n.md": "done/script-i18n.md",
    "clock-abstraction.md": "G-script-model/clock-abstraction.md",
    "iris-oculus-compat.md": "F-playback/iris-oculus-compat.md",
    "code-comment-style.md": "misc/code-comment-style.md",
    "render-routes.md": "_archive/render-routes.md",
    "render-second-pass-cost.md": "_archive/render-second-pass-cost.md",
    "quadrant-prototype-handover.md": "_archive/quadrant-prototype-handover.md",
    "quadrant-prototype-results.md": "_archive/quadrant-prototype-results.md",
    "multi-instance-black-screen.md": "_archive/multi-instance-black-screen.md",
    "editor-leftover-resources.md": "_archive/editor-leftover-resources.md",
    "wait-point-track.md": "_archive/wait-point-track.md",
    "implementation-audit-2026-10-07.md": "_archive/implementation-audit-2026-10-07.md",
    "next-phase-batches.md": "_archive/next-phase-batches.md",
    "screen-color-adjust.md": "../complete/0.3.6/A-color/screen-color-adjust.md",
    "camera-state-plan.md": "../complete/0.3.6/B-camera/camera-state-plan.md",
    "coordinate-frame.md": "../complete/0.3.6/B-camera/coordinate-frame.md",
    "selector-model.md": "../complete/0.3.6/B-camera/selector-model.md",
    "math-models.md": "../complete/0.3.6/B-camera/math-models.md",
    "temporal-interpolation.md": "../complete/0.3.6/B-camera/temporal-interpolation.md",
    "transition.md": "../complete/0.3.6/B-camera/transition.md",
    "hysteresis.md": "../complete/0.3.6/B-camera/hysteresis.md",
    "camera-core-split.md": "../complete/0.3.6/B-camera/camera-core-split.md",
    "feedback-0.3.5/README.md": "../complete/0.3.6/feedback-0.3.5/README.md",
    "feedback-0.3.5/01-trigger-tab-region-mode.md": "../complete/0.3.6/feedback-0.3.5/01-trigger-tab-region-mode.md",
    "feedback-0.3.5/02-editor-coverage-gaps.md": "../complete/0.3.6/feedback-0.3.5/02-editor-coverage-gaps.md",
    "feedback-0.3.5/03-exit-buffer-corner-order.md": "../complete/0.3.6/feedback-0.3.5/03-exit-buffer-corner-order.md",
    "feedback-0.3.5/04-trigger-latency.md": "../complete/0.3.6/feedback-0.3.5/04-trigger-latency.md",
    "feedback-0.3.5/05-on-enter-not-repeatable.md": "../complete/0.3.6/feedback-0.3.5/05-on-enter-not-repeatable.md",
}

NEW2OLD = {v: k for k, v in MOVES.items()}
LINK_RE = re.compile(r'\]\(([^)]*)\)')

def rel_from_base(abs_path):
    return os.path.relpath(abs_path, BASE).replace('\\', '/')

def fix_file(new_abs):
    if not os.path.isfile(new_abs):
        return 0
    new_rel = rel_from_base(new_abs)
    old_rel = NEW2OLD.get(new_rel, new_rel)
    old_dir = os.path.dirname(os.path.join(BASE, old_rel))
    new_dir = os.path.dirname(new_abs)
    content = open(new_abs, encoding='utf-8').read()
    n = [0]
    def repl(m):
        link = m.group(1)
        if link.startswith(('http://', 'https://', '#')) or link.startswith('/'):
            return m.group(0)
        if '#' in link:
            pp, anchor = link.split('#', 1)
            anchor = '#' + anchor
        else:
            pp, anchor = link, ''
        if not pp.endswith('.md'):
            return m.group(0)
        abs_t = os.path.normpath(os.path.join(old_dir, pp))
        t_rel = rel_from_base(abs_t)
        new_t = MOVES.get(t_rel, t_rel)
        new_abs_t = os.path.normpath(os.path.join(BASE, new_t))
        new_link = os.path.relpath(new_abs_t, new_dir).replace('\\', '/')
        if new_link != link:
            n[0] += 1
        return '](' + new_link + anchor + ')'
    new_content = LINK_RE.sub(repl, content)
    if new_content != content:
        open(new_abs, 'w', encoding='utf-8').write(new_content)
    return n[0]

total = 0
files = 0
for root in [BASE, COMPLETE]:
    for dirpath, dirs, fs in os.walk(root):
        for f in fs:
            if f.endswith('.md'):
                c = fix_file(os.path.join(dirpath, f))
                if c:
                    total += c
                    files += 1
print("fixed links:", total, "in", files, "files")
