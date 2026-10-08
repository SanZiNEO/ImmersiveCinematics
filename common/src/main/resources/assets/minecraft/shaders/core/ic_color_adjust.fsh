#version 150

// 画面颜色调整 —— 一次全屏 pass 合并全部标量调整；master 与 lane 级共用同一份着色器与操作栈
// （master：合成输出 → 中转缓冲；lane 级：lane FBO → adjustTarget，见 plans/0.3.6/screen-color-adjust.md 步骤 5）。
//
// 操作栈顺序固定（与 plans/0.3.6/screen-color-adjust.md §3 一致，非破坏、不可调）：
//   1. 曝光      2. 对比度     3. 高光 / 阴影   4. 白场 / 黑场
//   5. 色温 / 色调  6. RGB 通道系数  7. RGB 复合曲线（CurveLut 查表）
//   8. 每通道曲线（RCurveLut / GCurveLut / BCurveLut 查表）
//   9. 色相旋转   10. 饱和度   11. 自然饱和度   12. 亮度   13. 灰度   14. 反相
//
// 参数口径（除四个曲线强度外全部为「0 = 无效果」的增量；缺省全 0 = 恒等，运行时也不会下发这个 pass）：
//   Exposure     -5 ~ 5   EV 档（×2^EV）
//   Contrast     -1 ~ 1   以中灰 0.5 为轴
//   Highlights   -1 ~ 1   亮部（亮度权重 l²；正 = 提亮、负 = 压暗）
//   Shadows      -1 ~ 1   暗部（亮度权重 (1-l)²）
//   Whites       -1 ~ 1   白场端点（黑点 / 白点的 levels 式线性映射，±1 = 端点位移 ±0.5）
//   Blacks       -1 ~ 1   黑场端点（同上：正 = 抬黑场、负 = 压黑）
//   Hue          -1 ~ 1   HSL 的 H 通道：色相旋转（±1 = ±180°；HSL 块内顺序 = hue → sat → lightness）
//   Saturation   -1 ~ 1   HSL 的 S 通道（-1 = 全灰、1 = 双倍）
//   Vibrance     -1 ~ 1   自然饱和度（正 = 低饱和像素优先，负 = 整体降饱和）
//   Lightness    -1 ~ 1   HSL 的 L 通道：正 = 向白推、负 = 向黑压
//   Temperature  -1 ~ 1   色温（正 = 暖 / 偏红，负 = 冷 / 偏蓝）
//   Tint         -1 ~ 1   色调（正 = 品红，负 = 绿）
//   Red          -1 ~ 1   R 通道乘性系数（增益 = 1 + 值：-1 = 归零、-0.5 = 减半、+1 = 双倍）
//   Green        -1 ~ 1   G 通道，同上
//   Blue         -1 ~ 1   B 通道，同上
//   CurveStrength 0 ~ 1   RGB 复合曲线的混合强度（c = mix(c, lut(c), 强度)）；曲线本身在 CurveLut
//                         （256×1，CPU 侧 Fritsch–Carlson 采样；无曲线时绑恒等 LUT，强度置 0 = 整步跳过）
//   RCurveStrength / GCurveStrength / BCurveStrength
//                 0 ~ 1   每通道曲线的混合强度（口径同上，各自只作用于对应通道，逐通道混合、互不干扰）；
//                         曲线在 RCurveLut / GCurveLut / BCurveLut（各自无曲线时绑恒等 LUT，强度置 0 = 该通道跳过）
//   Grayscale     0 ~ 1   灰度混合强度（1 = 完全黑白）
//   Invert        0 ~ 1   反相混合强度（1 = 完全反相）
//
// 亮度口径：Rec.709 权重（0.2126 / 0.7152 / 0.0722）。
//
// alpha 直通（画面完整性原则）：本 pass 只对 rgb 运算，输出 alpha 始终 = 输入 alpha
// （不乘、不混、不钳制）。画面先合成成完整、不透明的画面；透明度（alpha / opacity）只在
// 合成层（LaneCompositor 的 opacity / Overlay 层 opacity）于合成之后调控——
// 禁止把透明「烤」进画面，也禁止在画面处理阶段动 alpha。
// 两条路径同一契约，执行顺序：lane 渲染 → lane 级调整（只动 RGB）→ 合成（opacity / dest / source）
// → 全部 lane 完成后 → master 调整（本 pass 作用于合成输出）。
// 依据：plans/0.3.6/README.md「画面完整性原则」、plans/0.3.6/multi-camera-rendering.md §12.8-A。

uniform sampler2D Sampler0;

// 曲线 LUT（都是 256×1；无曲线时绑恒等 LUT，配合各自强度 = 0 跳过）
uniform sampler2D CurveLut;    // RGB 复合曲线（四个通道共用一条）
uniform sampler2D RCurveLut;   // R 每通道曲线
uniform sampler2D GCurveLut;   // G 每通道曲线
uniform sampler2D BCurveLut;   // B 每通道曲线

uniform float Exposure;
uniform float Contrast;
uniform float Highlights;
uniform float Shadows;
uniform float Whites;
uniform float Blacks;
uniform float Hue;
uniform float Saturation;
uniform float Vibrance;
uniform float Lightness;
uniform float Temperature;
uniform float Tint;
uniform float Red;
uniform float Green;
uniform float Blue;
uniform float CurveStrength;
uniform float RCurveStrength;
uniform float GCurveStrength;
uniform float BCurveStrength;
uniform float Grayscale;
uniform float Invert;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float luma(vec3 c) {
    return dot(c, LUMA);
}

// 曲线 LUT 查表（复合曲线与每通道曲线共用）：256×1 纹理，lut[i] 对应 x = i / 255。
// 半 texel 偏移（+ 0.5/256）把 x 对到 texel 中心，再由纹理的 LINEAR 过滤在相邻 LUT 项之间插值
// （等价于在曲线采样点之间线性插值；x 在 0~1 外先钳制，与 CPU 侧的端点外钳制一致）。
float lutLookup(sampler2D lut, float x) {
    return texture(lut, vec2(clamp(x, 0.0, 1.0) * (255.0 / 256.0) + (0.5 / 256.0), 0.5)).r;
}

// RGB → HSL（标准换算；d ≈ 0 即灰点：s = 0、h 无意义）
vec3 rgb2hsl(vec3 c) {
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    float l = (mx + mn) * 0.5;
    float d = mx - mn;
    float h = 0.0;
    float s = 0.0;
    if (d > 1e-5) {
        s = (l > 0.5) ? d / (2.0 - mx - mn) : d / (mx + mn);
        if (mx == c.r) {
            h = (c.g - c.b) / d + ((c.g < c.b) ? 6.0 : 0.0);
        } else if (mx == c.g) {
            h = (c.b - c.r) / d + 2.0;
        } else {
            h = (c.r - c.g) / d + 4.0;
        }
        h /= 6.0;
    }
    return vec3(h, s, l);
}

float hue2rgb(float p, float q, float t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 1.0 / 2.0) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

// HSL → RGB（s = 0 直接返回灰度，与 rgb2hsl 的灰点分支对应）
vec3 hsl2rgb(vec3 hsl) {
    float h = hsl.x;
    float s = hsl.y;
    float l = hsl.z;
    if (s <= 0.0) {
        return vec3(l);
    }
    float q = (l < 0.5) ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(hue2rgb(p, q, h + 1.0 / 3.0),
                hue2rgb(p, q, h),
                hue2rgb(p, q, h - 1.0 / 3.0));
}

void main() {
    vec4 src = texture(Sampler0, texCoord);
    vec3 c = src.rgb;

    // 1. 曝光：EV 档
    c *= exp2(Exposure);

    // 2. 对比度：以中灰 0.5 为轴（+1 → ×2，-1 → 全压到中灰）
    c = (c - 0.5) * (1.0 + Contrast) + 0.5;

    // 3. 高光 / 阴影：按像素亮度分区加权（正 = 向白抬、负 = 向黑压；逐通道同式，不改色相比例）
    float l = luma(clamp(c, 0.0, 1.0));
    float hiMask = l * l;
    float loMask = (1.0 - l) * (1.0 - l);
    c += Highlights * hiMask * ((Highlights >= 0.0) ? (1.0 - c) : c);
    c += Shadows * loMask * ((Shadows >= 0.0) ? (1.0 - c) : c);

    // 4. 白场 / 黑场：端点线性映射（levels 式）
    //    黑点 = 0.5*Blacks（正 = 抬起黑场、负 = 压黑）、白点 = 1 + 0.5*Whites（正 = 抬白、负 = 压白）
    //    out = blackPoint + c * (whitePoint - blackPoint)
    float blackPoint = 0.5 * Blacks;
    float whitePoint = 1.0 + 0.5 * Whites;
    c = blackPoint + c * (whitePoint - blackPoint);

    // 5. 色温 / 色调：通道增益，按亮度归一化（调色温不改变整体明暗）
    vec3 gain = vec3(1.0 + 0.5 * Temperature, 1.0 - 0.5 * Tint, 1.0 - 0.5 * Temperature);
    gain /= max(dot(gain, LUMA), 1e-4);
    c *= gain;

    // 6. RGB 通道系数：逐通道乘性调整（增益 = 1 + 值；-1 = 该通道归零、-0.5 = 减半、+1 = 双倍）
    //    三个值全 0 时跳过乘法（保持逐位恒等）
    if (Red != 0.0 || Green != 0.0 || Blue != 0.0) {
        c *= vec3(1.0 + Red, 1.0 + Green, 1.0 + Blue);
    }

    // 7. RGB 复合曲线（曲线组形态 b：曲线定义一次 = CurveLut，关键帧只控 CurveStrength 混合强度）
    //    逐通道查表 → 按强度混合；无曲线 / 曲线淡出（CurveStrength = 0）时整步跳过（逐位恒等）
    if (CurveStrength > 0.0) {
        c = mix(c, vec3(lutLookup(CurveLut, c.r), lutLookup(CurveLut, c.g), lutLookup(CurveLut, c.b)),
                clamp(CurveStrength, 0.0, 1.0));
    }

    // 8. 每通道曲线（曲线组形态 b：R / G / B 各一条 = RCurveLut / GCurveLut / BCurveLut，关键帧只控各自强度）
    //    逐通道查表 → 按强度混合；该通道没有曲线 / 淡出（强度 = 0）时跳过（逐位恒等）；
    //    三条各自只写自己的分量，互不干扰（r 曲线不改 g / b）
    if (RCurveStrength > 0.0) {
        c.r = mix(c.r, lutLookup(RCurveLut, c.r), clamp(RCurveStrength, 0.0, 1.0));
    }
    if (GCurveStrength > 0.0) {
        c.g = mix(c.g, lutLookup(GCurveLut, c.g), clamp(GCurveStrength, 0.0, 1.0));
    }
    if (BCurveStrength > 0.0) {
        c.b = mix(c.b, lutLookup(BCurveLut, c.b), clamp(BCurveStrength, 0.0, 1.0));
    }

    // 后续按 HSL / 亮度混合，先收敛到 [0,1]（曲线输出由控制点限定在 [0,1] 内，此处一并兜底）
    c = clamp(c, 0.0, 1.0);

    // 9 ~ 12. 完整 HSL：色相旋转 → 饱和度 / 自然饱和度 → 亮度
    //    四个 HSL 通道全 0 时跳过换算（保持逐位恒等）
    if (Hue != 0.0 || Saturation != 0.0 || Vibrance != 0.0 || Lightness != 0.0) {
        vec3 hsl = rgb2hsl(c);
        // 色相旋转：Hue = ±1 → ±180°（±0.5 圈）；fract 对负值同样回绕到 [0,1)
        float h = fract(hsl.x + Hue * 0.5);
        float s = hsl.y;
        s = clamp(s * (1.0 + Saturation), 0.0, 1.0);
        s = clamp((Vibrance >= 0.0) ? (s + Vibrance * s * (1.0 - s)) : (s * (1.0 + Vibrance)), 0.0, 1.0);
        // 亮度：正 = 向白推（L → 1）、负 = 向黑压（L → 0）；双向混合，两端为满量程
        float l = clamp(hsl.z + Lightness * ((Lightness >= 0.0) ? (1.0 - hsl.z) : hsl.z), 0.0, 1.0);
        c = hsl2rgb(vec3(h, s, l));
    }

    // 13. 灰度：按亮度混合
    if (Grayscale != 0.0) {
        c = mix(c, vec3(luma(c)), clamp(Grayscale, 0.0, 1.0));
    }

    // 14. 反相：按强度混合
    if (Invert != 0.0) {
        c = mix(c, 1.0 - c, clamp(Invert, 0.0, 1.0));
    }

    // alpha 直通：透明度归合成层（LaneCompositor / Overlay 层 opacity），此处只输出 rgb 的运算结果
    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
