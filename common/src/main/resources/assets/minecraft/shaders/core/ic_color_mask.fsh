#version 150

// 颜色遮罩（fade 高级路径）片元着色器 —— 线性 / 径向多 stops 渐变、矩形 / 圆形遮罩、五种混合。
//
// 口径（plans/0.3.6/D-transition/color-mask-execution.md）：
//   · guiPos = GUI 像素坐标（左上原点、y 向下）；遮罩 / 渐变几何全部在片元内按像素计算。
//   · linear：t = 0.5 + dot(p - center, dir) / extent；radial：t = length(p - center) / extent。
//     端点外保持端点（t 钳到 0..1）。
//   · GradientLut = 1024×1 RGBA8（CPU 按 stops 烘好、端点对齐）；u 取 texel 中心，LINEAR 过滤在段内插值。
//   · coverage：full = 1；rect = |p-center| 逐分量 ≤ halfSize；circle = 距离 ≤ 半径（硬边，无 AA）。
//   · normal：fragColor = (src, opacity*coverage)，由 GL 的 srcalpha / 1-srcalpha 混合。
//   · 非 normal：base = 主 framebuffer 的实际内容（CPU 先 blit 到同尺寸 scratch，Sampler0 用
//     texelFetch 逐像素精确采样、不翻 y、不走过滤）；输出 = mix(base, B(base,src), opacity*coverage)，
//     alpha = 1（混合因子 1 等效替换），最终 RGB 全在 shader 内完成。
//   · 混合公式（a = base、b = src，本仓 A 轮 W3C 口径，与 ic_color_adjust.fsh 一致）：
//       normal = b
//       multiply = a*b
//       screen = 1-(1-a)(1-b)
//       soft_light = b<=0.5 ? a-(1-2b)a(1-a) : a+(2b-1)(d(a)-a)，
//                    d(a) = a<=0.25 ? ((16a-12)a+4)a : sqrt(a)   （a = base / Cb，b = src / Cs）
//       overlay = a<=0.5 ? 2ab : 1-2(1-a)(1-b)
//   · 所有路径都不写 framebuffer alpha（CPU colorMask 关 alpha），不污染主画面 alpha。

uniform sampler2D Sampler0;      // 主 framebuffer 的 backdrop 副本（仅非 normal 读取）
uniform sampler2D GradientLut;   // 渐变 LUT（1024×1；无渐变时绑 1×1 dummy，不读取）

uniform vec2 MaskCenter;         // 遮罩中心（GUI 像素）
uniform vec2 MaskHalfSize;       // rect 半宽 / 半高（GUI 像素）
uniform float MaskShape;         // 0 = full / 1 = rect / 2 = circle
uniform float MaskRadius;        // circle 半径（GUI 像素；真圆）

uniform float GradientType;      // 0 = 纯色 / 1 = linear / 2 = radial
uniform vec2 GradientDir;        // linear 单位方向 (cosθ, sinθ)，GUI y 向下
uniform vec2 GradientCenter;     // 渐变中心（GUI 像素）
uniform float GradientExtent;    // linear = |dx|*W+|dy|*H；radial = radius*min(W,H)（GUI 像素）

uniform vec3 Color;              // 纯色 RGB（0..1；渐变模式忽略）
uniform float Opacity;           // 关键帧 opacity（0..1，不含 coverage）
uniform float BlendMode;         // 0 = normal / 1 = multiply / 2 = screen / 3 = soft_light / 4 = overlay

in vec2 guiPos;

out vec4 fragColor;

float shapeCoverage(vec2 p) {
    if (MaskShape > 1.5) {
        return length(p - MaskCenter) <= MaskRadius ? 1.0 : 0.0;
    }
    if (MaskShape > 0.5) {
        vec2 d = abs(p - MaskCenter);
        return (d.x <= MaskHalfSize.x && d.y <= MaskHalfSize.y) ? 1.0 : 0.0;
    }
    return 1.0;
}

// d(Cb) 用于 soft_light 的高光分支（分支看 Cs）；Cb = 0.25 处两式连续（都等于 0.5）
vec3 softLightD(vec3 base) {
    vec3 poly = ((16.0 * base - 12.0) * base + 4.0) * base;
    return mix(poly, sqrt(base), step(vec3(0.25), base));
}

// W3C compositing-1：Cb = a（base）、Cs = b（src）；分支看 Cs，D 看 Cb
vec3 softLight(vec3 a, vec3 b) {
    vec3 low = a - (1.0 - 2.0 * b) * a * (1.0 - a);
    vec3 high = a + (2.0 * b - 1.0) * (softLightD(a) - a);
    return mix(low, high, step(vec3(0.5), b));
}

vec3 overlay(vec3 a, vec3 b) {
    vec3 low = 2.0 * a * b;
    vec3 high = 1.0 - 2.0 * (1.0 - a) * (1.0 - b);
    return mix(low, high, step(vec3(0.5), a));
}

void main() {
    float coverage = shapeCoverage(guiPos);

    vec3 src;
    if (GradientType > 0.5) {
        float t;
        if (GradientType < 1.5) {
            t = 0.5 + dot(guiPos - GradientCenter, GradientDir) / max(GradientExtent, 1e-6);
        } else {
            t = length(guiPos - GradientCenter) / max(GradientExtent, 1e-6);
        }
        t = clamp(t, 0.0, 1.0);
        // 1024 texel、端点对齐：t=0 → texel 0 中心，t=1 → texel 1023 中心
        float u = t * (1023.0 / 1024.0) + (0.5 / 1024.0);
        src = texture(GradientLut, vec2(u, 0.5)).rgb;
    } else {
        src = Color;
    }

    float amount = clamp(Opacity, 0.0, 1.0) * coverage;

    if (BlendMode < 0.5) {
        // normal：GL 混合（srcalpha / 1-srcalpha）；coverage=0 → alpha=0 → 目标逐位不变
        fragColor = vec4(src, amount);
        return;
    }

    // 非 normal：base = 当前主 framebuffer 的同一像素（scratch 副本，逐像素精确）
    vec3 base = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).rgb;
    vec3 mixed;
    if (BlendMode < 1.5) {
        mixed = base * src;
    } else if (BlendMode < 2.5) {
        mixed = 1.0 - (1.0 - base) * (1.0 - src);
    } else if (BlendMode < 3.5) {
        mixed = softLight(base, src);
    } else {
        mixed = overlay(base, src);
    }
    fragColor = vec4(mix(base, mixed, amount), 1.0);
}
