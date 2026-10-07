#version 150

// 画面颜色调整（master，第一批标量组）——一次全屏 pass 合并全部标量调整。
//
// 操作栈顺序固定（与 plans/0.3.6/screen-color-adjust.md §3 一致，非破坏、不可调）：
//   1. 曝光      2. 对比度     3. 高光 / 阴影   4. 白场 / 黑场
//   5. 色温 / 色调  6. RGB 通道系数  7. 饱和度   8. 自然饱和度   9. 灰度   10. 反相
//
// 参数口径（全部为「0 = 无效果」的增量；缺省全 0 = 恒等，运行时也不会下发这个 pass）：
//   Exposure     -5 ~ 5   EV 档（×2^EV）
//   Contrast     -1 ~ 1   以中灰 0.5 为轴
//   Highlights   -1 ~ 1   亮部（亮度权重 l²；正 = 提亮、负 = 压暗）
//   Shadows      -1 ~ 1   暗部（亮度权重 (1-l)²）
//   Whites       -1 ~ 1   白场端点（黑点 / 白点的 levels 式线性映射，±1 = 端点位移 ±0.5）
//   Blacks       -1 ~ 1   黑场端点（同上：正 = 抬黑场、负 = 压黑）
//   Saturation   -1 ~ 1   HSL 的 S 通道（-1 = 全灰、1 = 双倍）
//   Vibrance     -1 ~ 1   自然饱和度（正 = 低饱和像素优先，负 = 整体降饱和）
//   Temperature  -1 ~ 1   色温（正 = 暖 / 偏红，负 = 冷 / 偏蓝）
//   Tint         -1 ~ 1   色调（正 = 品红，负 = 绿）
//   Red          -1 ~ 1   R 通道乘性系数（增益 = 1 + 值：-1 = 归零、-0.5 = 减半、+1 = 双倍）
//   Green        -1 ~ 1   G 通道，同上
//   Blue         -1 ~ 1   B 通道，同上
//   Grayscale     0 ~ 1   灰度混合强度（1 = 完全黑白）
//   Invert        0 ~ 1   反相混合强度（1 = 完全反相）
//
// 亮度口径：Rec.709 权重（0.2126 / 0.7152 / 0.0722）。
//
// alpha 直通（画面完整性原则）：本 pass 只对 rgb 运算，输出 alpha 始终 = 输入 alpha
// （不乘、不混、不钳制）。画面先合成成完整、不透明的画面；透明度（alpha / opacity）只在
// 合成层（LaneCompositor 的 opacity / Overlay 层 opacity）于合成之后调控——
// 禁止把透明「烤」进画面，也禁止在画面处理阶段动 alpha。
// 依据：plans/0.3.6/README.md「画面完整性原则」、plans/0.3.6/multi-camera-rendering.md §12.8-A。

uniform sampler2D Sampler0;

uniform float Exposure;
uniform float Contrast;
uniform float Highlights;
uniform float Shadows;
uniform float Whites;
uniform float Blacks;
uniform float Saturation;
uniform float Vibrance;
uniform float Temperature;
uniform float Tint;
uniform float Red;
uniform float Green;
uniform float Blue;
uniform float Grayscale;
uniform float Invert;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float luma(vec3 c) {
    return dot(c, LUMA);
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

    // 后续按 HSL / 亮度混合，先收敛到 [0,1]
    c = clamp(c, 0.0, 1.0);

    // 7 / 8. 饱和度、自然饱和度：HSL 的 S 通道（无 HSL 调整时跳过换算，保持逐位恒等）
    if (Saturation != 0.0 || Vibrance != 0.0) {
        vec3 hsl = rgb2hsl(c);
        float s = hsl.y;
        s = clamp(s * (1.0 + Saturation), 0.0, 1.0);
        s = clamp((Vibrance >= 0.0) ? (s + Vibrance * s * (1.0 - s)) : (s * (1.0 + Vibrance)), 0.0, 1.0);
        c = hsl2rgb(vec3(hsl.x, s, hsl.z));
    }

    // 9. 灰度：按亮度混合
    if (Grayscale != 0.0) {
        c = mix(c, vec3(luma(c)), clamp(Grayscale, 0.0, 1.0));
    }

    // 10. 反相：按强度混合
    if (Invert != 0.0) {
        c = mix(c, 1.0 - c, clamp(Invert, 0.0, 1.0));
    }

    // alpha 直通：透明度归合成层（LaneCompositor / Overlay 层 opacity），此处只输出 rgb 的运算结果
    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
