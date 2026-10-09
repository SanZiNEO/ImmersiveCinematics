#version 150

// 画面颜色调整 —— 一次全屏 pass 合并全部标量调整；master 与 lane 级共用同一份着色器与操作栈
// （master：合成输出 → 中转缓冲；lane 级：lane FBO → adjustTarget，见 plans/0.3.6/screen-color-adjust.md 步骤 5）。
//
// 操作栈顺序固定（与 plans/0.3.6/screen-color-adjust.md §3 一致，非破坏、不可调）：
//   1. 曝光      2. 对比度     3. 高光 / 阴影   4. 白场 / 黑场
//   5. 色温 / 色调  6. RGB 通道系数  7. RGB 通道混合器（3×3 矩阵，单位阵 + 参数矩阵）
//   8. RGB 复合曲线（CurveLut 查表）
//   9. 每通道曲线（RCurveLut / GCurveLut / BCurveLut 查表）
//  10. Lift / Gamma / Gain 色轮（三组逐通道；每通道曲线之后、钳制之前）
//  11. LUT（.cube：单张合成 3D 表，CPU 预合成 1D shaper + 两段 DOMAIN；LGG 之后、HSL 块之前）
//  12. 色相旋转   13. 饱和度   14. 自然饱和度   15. 亮度
//  16. 六条 hue 曲线（HvH → HvS → HvL → LvS → SvS → SvL；HSL 块内，见下）
//  17. PS 式六色带微调（Hue / Sat 分色带；六条固定色带 + 升余弦权重；HSL 块之后、灰度之前）
//  18. 灰度      19. 反相
//  20. 层级混合（混合模式作用于调整层：调整层输出 vs 基画面；不是操作栈步骤，在全部步骤之后整体施加）
//
// 六条 hue 曲线（DaVinci 曲线页口径；步骤 12 ~ 16 共用一个 HSL 块、一次 rgb2hsl 换算）：
//   键取「进入该块时」的 h0 / s0 / l0（不受标量 HSL 改动影响），每条各自按强度 mix 混合：
//     HvH  h = mix(h, HvH(h0), st)   HvS  s = mix(s, HvS(h0), st)   HvL  l = mix(l, HvL(h0), st)
//     LvS  s = mix(s, LvS(l0), st)   SvS  s = mix(s, SvS(s0), st)   SvL  l = mix(l, SvL(s0), st)
//   顺序即上表顺序；同一目标分量（s / l）的多条按此序依次叠加。
//
// PS 式六色带微调（第 17 步；PS「色相 / 饱和度」的六色带形态）：
//   六条固定色带，中心角 = 红 0° / 黄 60° / 绿 120° / 青 180° / 蓝 240° / 品红 300°；
//   带权重 = 升余弦锥形 w = 0.5·(1 + cos(π·|Δ| / 30°))（|Δ| = 像素色相与中心的最短角距、单位度；
//   |Δ| ≥ 30° → 0）—— 六带各撑 ±30°、恰铺满色相环、相邻带在边界平滑交叠。
//   像素级：Δhue = Σ wᵢ·Hueᵢ（±1 = ±180°）、Δsat = Σ wᵢ·Satᵢ；先旋色相、再改饱和：
//     h' = fract(h + 0.5·Δhue)      s' = clamp(s·(1 + Δsat), 0, 1)
//   十二通道全 0 → 整步跳过（逐位恒等）；透明像素（RGB = 黑）经本步后仍是 (0,0,0)
//   （s = 0 / l = 0 走 hsl2rgb 的灰度分支，色相旋转不产生新值、不复活透明像素）。
//
// 参数口径（除十条曲线强度外全部为「0 = 无效果」的增量；缺省全 0 = 恒等，运行时也不会下发这个 pass）：
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
//   MixRR ~ MixBB  -1 ~ 1  RGB 通道混合器（PS 式 3×3 矩阵；行 = 输出通道、列 = 输入通道）：
//                         实际矩阵 = 单位阵 + 参数矩阵，out.r = (1+MixRR)·r + MixRG·g + MixRB·b
//                         （g / b 行同式）；九个全 0 = 单位阵 = 跳过乘法（逐位恒等）
//   CurveStrength 0 ~ 1   RGB 复合曲线的混合强度（c = mix(c, lut(c), 强度)）；曲线本身在 CurveLut
//                         （256×1，CPU 侧 Fritsch–Carlson 采样；无曲线时绑恒等 LUT，强度置 0 = 整步跳过）
//   RCurveStrength / GCurveStrength / BCurveStrength
//                 0 ~ 1   每通道曲线的混合强度（口径同上，各自只作用于对应通道，逐通道混合、互不干扰）；
//                         曲线在 RCurveLut / GCurveLut / BCurveLut（各自无曲线时绑恒等 LUT，强度置 0 = 该通道跳过）
//   HvHStrength / HvSStrength / HvLStrength / LvSStrength / SvSStrength / SvLStrength
//                 0 ~ 1   六条 hue 曲线的混合强度（键 / 目标见上表；曲线在 HvHLut / HvSLut / HvLLut /
//                         LvSLut / SvSLut / SvLLut；各自无曲线时绑恒等 LUT，强度置 0 = 该条跳过）
//   LiftR / LiftG / LiftB        -1 ~ 1  Lift 色轮（阴影）：c' = c + lift·(1-c)
//                                        （正 = 抬阴影、负 = 压黑；c = 1 的通道不动）
//   GammaR / GammaG / GammaB     -1 ~ 1  Gamma 色轮（中间调）：c' = pow(max(c,0), exp2(-gamma))
//                                        （0 = 指数 1 恒等；正 = 中间调提亮、负 = 压暗）
//   GainR / GainG / GainB        -1 ~ 1  Gain 色轮（高光）：c' = c·(1+gain)
//                                        （正 = 乘性提亮、负 = 压暗）
//                                        三组都是逐通道；九个全 0 = 整步跳过（逐位恒等）
//   LutStrength   0 ~ 1   LUT 混合强度（c = mix(c, lut(c), 强度)）；LUT 本身在 Lut3D
//                         （无 LUT / 强度 0 = 整步跳过）
//   LutInputGamma > 0     LUT 输入域适配（缺省 1 = 不变换）：查表前逐通道
//                         v = pow(clamp(c, 0, 1), LutInputGamma)，以 v 为四面体查表坐标
//                         （现成 .cube 按特定素材 / 色彩空间调成时，把画面映射回表假设的输入域）；
//                         LutInputGamma = 1 时不执行 pow（逐位恒等）；无 LUT 时忽略
//   Lut3DSize     合成表的网格边长 S（0 = 无 LUT）。表由 CPU 在加载期预合成：
//                 .cube 的 1D shaper 与 1D / 3D 两段 DOMAIN 输入域归一化全部烘焙进这一张表
//                 （CubeLut#composed3D），采样坐标 x ∈ [0,1]³ 直接对应网格 —— 着色器不做任何归一化。
//   插值：四面体（tetrahedral；texelFetch 取 8 个角点后自己加权，不用纹理过滤）
//   HueRed / SatRed ~ HueMagenta / SatMagenta   -1 ~ 1
//                 PS 式六色带微调（六条固定色带各一对，顺序 = 红 / 黄 / 绿 / 青 / 蓝 / 品红）：
//                 Hue* = 该带内的色相旋转（±1 = ±180°）、Sat* = 该带内的饱和度增量
//                 （sat *= 1 + Δsat，钳制 0~1）；带权重 = 升余弦锥形
//                 w = 0.5·(1 + cos(π·|Δ| / 30°))（|Δ| = 与中心的最短角距；|Δ| ≥ 30° → 0，
//                 六带各撑 ±30°、恰铺满色相环）；十二个全 0 = 整步跳过（逐位恒等）
//   Grayscale     0 ~ 1   灰度混合强度（1 = 完全黑白）
//   Invert        0 ~ 1   反相混合强度（1 = 完全反相）
//   BlendMode     0 ~ 4   层级混合的混合模式编码（0 = normal 直替换 = 缺省 / 1 = multiply / 2 = screen /
//                         3 = soft_light / 4 = overlay）：作用于调整层输出（上面 19 步算出的颜色）
//                         与基画面（Sampler0 原图）的整体混合；只由 ADJUST 轨（master）取值
//   BlendAmount   0 ~ 1   层级混合强度（缺省 1 = 全量生效；写回 0 = 调整层整体透明 = 输出基画面）；
//                         可随时间淡入淡出
//
// 层级混合（第 20 步；混合模式作用于调整层 = ADJUST 轨）：
//   全部 19 步算出调整后颜色 c_adj 后，把它与基画面（Sampler0 原图）c_base 按混合模式整体混合：
//     c = mix(c_base, blend(c_base, c_adj, BlendMode), clamp(BlendAmount, 0, 1))   （逐通道 RGB、均 0~1）
//   混合公式（a = c_base、b = c_adj；W3C compositing 口径）：
//     normal(0)     = b（直替换 = 现状）
//     multiply(1)   = a·b
//     screen(2)     = 1 - (1-a)·(1-b)
//     soft_light(3) = a ≤ 0.5 ? b - (1-2a)·b·(1-b) : b + (2a-1)·(d(b)-b)，
//                     其中 d(b) = b ≤ 0.25 ? ((16b-12)·b+4)·b : sqrt(b)
//     overlay(4)    = a ≤ 0.5 ? 2ab : 1 - 2(1-a)(1-b)（a = 0.5 处两式相等，边界无跳变）
//   缺省 normal + 1 = 直替换（与混合功能落地前逐位一致，故该步整段跳过）；BlendAmount = 0 =
//   调整层整体透明（输出 = 基画面 = 恒等）。<b>不是操作栈步骤</b>，而是层级语义
//   （调整层输出 vs 底下的画面）；只动 rgb，alpha 逐位直通。
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
uniform sampler2D HvHLut;      // HvH：hue → hue
uniform sampler2D HvSLut;      // HvS：hue → 饱和度
uniform sampler2D HvLLut;      // HvL：hue → 亮度
uniform sampler2D LvSLut;      // LvS：亮度 → 饱和度
uniform sampler2D SvSLut;      // SvS：饱和度 → 饱和度
uniform sampler2D SvLLut;      // SvL：饱和度 → 亮度

// LUT（.cube）：单张合成 3D 表（S×S×S，CPU 加载期预合成，见 Lut3DSize 注释）；
// 无 LUT 时尺寸 = 0（不生效），强度 = 0 时整步跳过
uniform sampler3D Lut3D;       // 合成表（S×S×S；texelFetch 取角点 + 四面体插值）

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
uniform float MixRR;
uniform float MixRG;
uniform float MixRB;
uniform float MixGR;
uniform float MixGG;
uniform float MixGB;
uniform float MixBR;
uniform float MixBG;
uniform float MixBB;
uniform float CurveStrength;
uniform float RCurveStrength;
uniform float GCurveStrength;
uniform float BCurveStrength;
uniform float LiftR;
uniform float LiftG;
uniform float LiftB;
uniform float GammaR;
uniform float GammaG;
uniform float GammaB;
uniform float GainR;
uniform float GainG;
uniform float GainB;
uniform float LutStrength;
uniform float LutInputGamma;
uniform float Lut3DSize;
uniform float HvHStrength;
uniform float HvSStrength;
uniform float HvLStrength;
uniform float LvSStrength;
uniform float SvSStrength;
uniform float SvLStrength;
uniform float HueRed;
uniform float SatRed;
uniform float HueYellow;
uniform float SatYellow;
uniform float HueGreen;
uniform float SatGreen;
uniform float HueCyan;
uniform float SatCyan;
uniform float HueBlue;
uniform float SatBlue;
uniform float HueMagenta;
uniform float SatMagenta;
uniform float Grayscale;
uniform float Invert;
uniform float BlendMode;     // 层级混合模式编码（0 = normal … 4 = overlay；见文件头第 20 步）
uniform float BlendAmount;   // 层级混合强度（0 ~ 1，缺省 1 = 全量生效）

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

// LUT（.cube）查表：四面体插值（tetrahedral，业界默认；三线性在中性灰附近会偏色，
// 见 example/lut-reference/README.md）—— 8 个角点用 texelFetch 精确取（不走纹理过滤），
// 按 d.r / d.g / d.b 的大小关系选四面体、四点加权。
// 合成表的输入域已经是 [0,1]³（CPU 加载期把 1D shaper 与两段 DOMAIN 归一化全部烘焙进表，
// 见 CubeLut#composed3D），所以这里只做 [0,1] 钳制 + 乘 (size-1) 取网格坐标，不做任何归一化。
vec3 lut3dLookup(vec3 c) {
    vec3 t = clamp(c, 0.0, 1.0) * (Lut3DSize - 1.0);
    ivec3 p = ivec3(floor(t));
    ivec3 n = min(p + ivec3(1), ivec3(int(Lut3DSize) - 1));
    vec3 d = t - vec3(p);
    vec3 c000 = texelFetch(Lut3D, ivec3(p.x, p.y, p.z), 0).rgb;
    vec3 c111 = texelFetch(Lut3D, ivec3(n.x, n.y, n.z), 0).rgb;
    if (d.r > d.g) {
        if (d.g > d.b) {
            // r > g > b
            vec3 c100 = texelFetch(Lut3D, ivec3(n.x, p.y, p.z), 0).rgb;
            vec3 c110 = texelFetch(Lut3D, ivec3(n.x, n.y, p.z), 0).rgb;
            return (1.0 - d.r) * c000 + (d.r - d.g) * c100 + (d.g - d.b) * c110 + d.b * c111;
        } else if (d.r > d.b) {
            // r > b >= g
            vec3 c100 = texelFetch(Lut3D, ivec3(n.x, p.y, p.z), 0).rgb;
            vec3 c101 = texelFetch(Lut3D, ivec3(n.x, p.y, n.z), 0).rgb;
            return (1.0 - d.r) * c000 + (d.r - d.b) * c100 + (d.b - d.g) * c101 + d.g * c111;
        } else {
            // b >= r > g
            vec3 c001 = texelFetch(Lut3D, ivec3(p.x, p.y, n.z), 0).rgb;
            vec3 c101 = texelFetch(Lut3D, ivec3(n.x, p.y, n.z), 0).rgb;
            return (1.0 - d.b) * c000 + (d.b - d.r) * c001 + (d.r - d.g) * c101 + d.g * c111;
        }
    } else {
        if (d.b > d.g) {
            // b > g >= r
            vec3 c001 = texelFetch(Lut3D, ivec3(p.x, p.y, n.z), 0).rgb;
            vec3 c011 = texelFetch(Lut3D, ivec3(p.x, n.y, n.z), 0).rgb;
            return (1.0 - d.b) * c000 + (d.b - d.g) * c001 + (d.g - d.r) * c011 + d.r * c111;
        } else if (d.b > d.r) {
            // g >= b > r
            vec3 c010 = texelFetch(Lut3D, ivec3(p.x, n.y, p.z), 0).rgb;
            vec3 c011 = texelFetch(Lut3D, ivec3(p.x, n.y, n.z), 0).rgb;
            return (1.0 - d.g) * c000 + (d.g - d.b) * c010 + (d.b - d.r) * c011 + d.r * c111;
        } else {
            // g >= r >= b
            vec3 c010 = texelFetch(Lut3D, ivec3(p.x, n.y, p.z), 0).rgb;
            vec3 c110 = texelFetch(Lut3D, ivec3(n.x, n.y, p.z), 0).rgb;
            return (1.0 - d.g) * c000 + (d.g - d.r) * c010 + (d.r - d.b) * c110 + d.b * c111;
        }
    }
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

// PS 式六色带的单条带权重（升余弦锥形）：|Δ| = 像素色相与带中心的最短角距（度）
//   w = 0.5·(1 + cos(π·|Δ| / 30°))（|Δ| = 0 → 1、|Δ| = 30° → 0）；|Δ| ≥ 30° → 0（带外无贡献）
// 六条带（中心 0° / 60° / … / 300°）各撑 ±30°，恰铺满色相环、相邻带在边界平滑交叠。
float sixBandWeight(float hueDegrees, float centerDegrees) {
    float d = hueDegrees - centerDegrees;
    d = d - 360.0 * floor(d / 360.0 + 0.5);   // 最短角距，归一到 (-180, 180]
    float ad = abs(d);
    if (ad >= 30.0) {
        return 0.0;
    }
    return 0.5 * (1.0 + cos(radians(ad * 6.0)));   // π/30 rad/° = radians(|Δ| · 6)
}

// 六色带在像素色相 theta（度）处的合成增量：x = Δhue（±1 = ±180°）、y = Δsat（饱和度乘性增量）
//   中心角 = 红 0° / 黄 60° / 绿 120° / 青 180° / 蓝 240° / 品红 300°（顺序即通道顺序）
vec2 sixBandShift(float theta) {
    return vec2(
            sixBandWeight(theta, 0.0) * HueRed
                    + sixBandWeight(theta, 60.0) * HueYellow
                    + sixBandWeight(theta, 120.0) * HueGreen
                    + sixBandWeight(theta, 180.0) * HueCyan
                    + sixBandWeight(theta, 240.0) * HueBlue
                    + sixBandWeight(theta, 300.0) * HueMagenta,
            sixBandWeight(theta, 0.0) * SatRed
                    + sixBandWeight(theta, 60.0) * SatYellow
                    + sixBandWeight(theta, 120.0) * SatGreen
                    + sixBandWeight(theta, 180.0) * SatCyan
                    + sixBandWeight(theta, 240.0) * SatBlue
                    + sixBandWeight(theta, 300.0) * SatMagenta);
}

// 层级混合（第 20 步）：调整层输出（b = 全部 19 步算出的颜色）与基画面（a = Sampler0 原图）的混合。
// 公式 = W3C compositing 口径（逐通道、输入均 0~1）；mode = 0 ~ 4 编码（见文件头）。
float softLightChannel(float a, float b) {
    float d = (b <= 0.25) ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return (a <= 0.5) ? (b - (1.0 - 2.0 * a) * b * (1.0 - b))
                      : (b + (2.0 * a - 1.0) * (d - b));
}

// 混合模式（a = 基画面、b = 调整层输出；逐通道 RGB、均 0~1）
vec3 blendChannel(vec3 a, vec3 b, float mode) {
    if (mode < 0.5) {
        return b;                                        // normal：直替换（缺省 = 现状）
    }
    if (mode < 1.5) {
        return a * b;                                    // multiply
    }
    if (mode < 2.5) {
        return 1.0 - (1.0 - a) * (1.0 - b);              // screen
    }
    if (mode < 3.5) {
        return vec3(softLightChannel(a.r, b.r),          // soft_light
                    softLightChannel(a.g, b.g),
                    softLightChannel(a.b, b.b));
    }
    // overlay：a ≤ 0.5 → 2ab，否则 1 - 2(1-a)(1-b)（a = 0.5 处两式相等，边界无跳变）
    return mix(2.0 * a * b, 1.0 - 2.0 * (1.0 - a) * (1.0 - b), step(vec3(0.5), a));
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

    // 7. RGB 通道混合器：PS 式 3×3 矩阵（实际矩阵 = 单位阵 + 参数矩阵；行 = 输出通道、列 = 输入通道）
    //    out.r = (1+MixRR)·r + MixRG·g + MixRB·b，g / b 行同式；九个参数全 0 = 单位阵，跳过乘法（逐位恒等）
    //    结果仍受下方 [0,1] 钳制管辖（与通道系数 / 曲线同段，HSL 输入有界）
    if (MixRR != 0.0 || MixRG != 0.0 || MixRB != 0.0
            || MixGR != 0.0 || MixGG != 0.0 || MixGB != 0.0
            || MixBR != 0.0 || MixBG != 0.0 || MixBB != 0.0) {
        c = vec3((1.0 + MixRR) * c.r + MixRG * c.g + MixRB * c.b,
                 MixGR * c.r + (1.0 + MixGG) * c.g + MixGB * c.b,
                 MixBR * c.r + MixBG * c.g + (1.0 + MixBB) * c.b);
    }

    // 8. RGB 复合曲线（曲线组形态 b：曲线定义一次 = CurveLut，关键帧只控 CurveStrength 混合强度）
    //    逐通道查表 → 按强度混合；无曲线 / 曲线淡出（CurveStrength = 0）时整步跳过（逐位恒等）
    if (CurveStrength > 0.0) {
        c = mix(c, vec3(lutLookup(CurveLut, c.r), lutLookup(CurveLut, c.g), lutLookup(CurveLut, c.b)),
                clamp(CurveStrength, 0.0, 1.0));
    }

    // 9. 每通道曲线（曲线组形态 b：R / G / B 各一条 = RCurveLut / GCurveLut / BCurveLut，关键帧只控各自强度）
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

    // 10. Lift / Gamma / Gain 色轮（三组逐通道色轮；每组公式见下）
    //     Lift（阴影）    c' = c + lift·(1-c)       正 = 抬阴影、负 = 压黑（c = 1 的通道不动）
    //     Gamma（中间调） c' = pow(max(c,0), exp2(-gamma))  0 = 指数 1 恒等；正 = 中间调提亮、负 = 压暗
    //     Gain（高光）    c' = c·(1+gain)           正 = 乘性提亮、负 = 压暗
    //     九个通道全 0 时整步跳过（逐位恒等）；结果仍受下方 [0,1] 钳制管辖
    if (LiftR != 0.0 || LiftG != 0.0 || LiftB != 0.0
            || GammaR != 0.0 || GammaG != 0.0 || GammaB != 0.0
            || GainR != 0.0 || GainG != 0.0 || GainB != 0.0) {
        vec3 lift = vec3(LiftR, LiftG, LiftB);
        vec3 gamma = vec3(GammaR, GammaG, GammaB);
        vec3 gain = vec3(GainR, GainG, GainB);
        // Lift：向白端（1）混合，lift = 0 的通道逐位不变
        c = c + lift * (1.0 - c);
        // Gamma：指数 = exp2(-gamma)；指数恰为 1（该通道 gamma = 0）时直接用原值（逐位恒等），
        // 其余通道走幂次（负输入先夹到 0，避免 pow 的 NaN）
        vec3 gammaExp = exp2(-gamma);
        c = mix(pow(max(c, 0.0), gammaExp), c, equal(gammaExp, vec3(1.0)));
        // Gain：乘性增益（1 + 值），gain = 0 的通道逐位不变
        c = c * (1.0 + gain);
    }

    // 后续按 HSL / 亮度混合，先收敛到 [0,1]（覆盖步骤 1 ~ 10：曝光 / 对比度 / 高光阴影 / 白黑场 /
    // 色温色调 / 通道系数 / 通道混合器 / 四条曲线 / Lift-Gamma-Gain；曲线输出由控制点限定在 [0,1] 内，
    // 此处一并兜底）
    c = clamp(c, 0.0, 1.0);

    // 11. LUT（.cube；LGG 之后、HSL 块之前）：单张合成表（CPU 加载期把 1D shaper 与两段 DOMAIN
    //     归一化烘焙进表，见 Lut3DSize 注释），查表 = 四面体（tetrahedral，见 lut3dLookup）；
    //     查表输入 = pow(clamp(c,0,1), LutInputGamma)（输入域适配：现成 .cube 按特定素材 / 色彩空间
    //     调成时把画面映射回表假设的输入域；LutInputGamma = 1 时不执行 pow，逐位恒等）；
    //     无 LUT / 强度 0 → 整步跳过（逐位恒等）；LUT 数据可超出 [0,1]，由下一步前的钳制兜底
    if (LutStrength > 0.0 && Lut3DSize > 0.0) {
        vec3 lutIn = c;
        if (LutInputGamma != 1.0) {
            lutIn = pow(clamp(c, 0.0, 1.0), vec3(LutInputGamma));
        }
        c = mix(c, lut3dLookup(lutIn), clamp(LutStrength, 0.0, 1.0));
    }

    // 12 ~ 16. 完整 HSL：色相旋转 → 饱和度 / 自然饱和度 → 亮度 → 六条 hue 曲线
    //    四个 HSL 通道全 0 且六条曲线都不生效时跳过换算（保持逐位恒等）
    if (Hue != 0.0 || Saturation != 0.0 || Vibrance != 0.0 || Lightness != 0.0
            || HvHStrength > 0.0 || HvSStrength > 0.0 || HvLStrength > 0.0
            || LvSStrength > 0.0 || SvSStrength > 0.0 || SvLStrength > 0.0) {
        vec3 hsl = rgb2hsl(c);
        // 六条 hue 曲线的键 = 进入本块时的 h0 / s0 / l0（rgb2hsl 的原始输出，不受标量 HSL 改动影响）
        float h0 = hsl.x;
        float s0 = hsl.y;
        float l0 = hsl.z;
        // 色相旋转：Hue = ±1 → ±180°（±0.5 圈）；fract 对负值同样回绕到 [0,1)
        float h = fract(h0 + Hue * 0.5);
        float s = s0;
        s = clamp(s * (1.0 + Saturation), 0.0, 1.0);
        s = clamp((Vibrance >= 0.0) ? (s + Vibrance * s * (1.0 - s)) : (s * (1.0 + Vibrance)), 0.0, 1.0);
        // 亮度：正 = 向白推（L → 1）、负 = 向黑压（L → 0）；双向混合，两端为满量程
        float l = clamp(l0 + Lightness * ((Lightness >= 0.0) ? (1.0 - l0) : l0), 0.0, 1.0);
        // 六条 hue 曲线（DaVinci 曲线页口径）：各自查表 → 按强度混合，顺序固定
        //   键（输入）= h0 / s0 / l0；目标（输出）= hue / 饱和度 / 亮度，同一目标的多条按此序叠加
        //   无对应曲线 / 强度 0 → 跳过该条（逐位恒等）
        if (HvHStrength > 0.0) {
            h = mix(h, lutLookup(HvHLut, h0), clamp(HvHStrength, 0.0, 1.0));   // hue → hue
        }
        if (HvSStrength > 0.0) {
            s = mix(s, lutLookup(HvSLut, h0), clamp(HvSStrength, 0.0, 1.0));   // hue → 饱和度
        }
        if (HvLStrength > 0.0) {
            l = mix(l, lutLookup(HvLLut, h0), clamp(HvLStrength, 0.0, 1.0));   // hue → 亮度
        }
        if (LvSStrength > 0.0) {
            s = mix(s, lutLookup(LvSLut, l0), clamp(LvSStrength, 0.0, 1.0));   // 亮度 → 饱和度
        }
        if (SvSStrength > 0.0) {
            s = mix(s, lutLookup(SvSLut, s0), clamp(SvSStrength, 0.0, 1.0));   // 饱和度 → 饱和度
        }
        if (SvLStrength > 0.0) {
            l = mix(l, lutLookup(SvLLut, s0), clamp(SvLStrength, 0.0, 1.0));   // 饱和度 → 亮度
        }
        c = hsl2rgb(vec3(h, s, l));
    }

    // 17. PS 式六色带微调（Hue / Sat 分色带）：HSL 块之后、灰度之前
    //     六条固定色带（红 0° / 黄 60° / 绿 120° / 青 180° / 蓝 240° / 品红 300°）各一对 hue / sat 通道；
    //     带权重 = 升余弦锥形（|Δ| ≥ 30° → 0，六带各撑 ±30°、恰铺满色相环、边界平滑交叠）；
    //     Δhue = Σ wᵢ·Hueᵢ（±1 = ±180°）、Δsat = Σ wᵢ·Satᵢ；先旋色相、再改饱和（sat *= 1 + Δsat，钳制 0~1）；
    //     十二通道全 0 → 整步跳过（逐位恒等）；s = 0 / l = 0（透明像素的黑）走 hsl2rgb 灰度分支
    //     → RGB 仍是 (0,0,0)，色相旋转不产生新值、不复活透明像素
    if (HueRed != 0.0 || SatRed != 0.0 || HueYellow != 0.0 || SatYellow != 0.0
            || HueGreen != 0.0 || SatGreen != 0.0 || HueCyan != 0.0 || SatCyan != 0.0
            || HueBlue != 0.0 || SatBlue != 0.0 || HueMagenta != 0.0 || SatMagenta != 0.0) {
        vec3 hsl = rgb2hsl(c);
        vec2 shift = sixBandShift(hsl.x * 360.0);
        float h = fract(hsl.x + shift.x * 0.5);              // Δhue：±1 = ±180°（归一化色相 × 0.5）
        float s = clamp(hsl.y * (1.0 + shift.y), 0.0, 1.0);  // 饱和度乘性增量，钳制 0~1
        c = hsl2rgb(vec3(h, s, hsl.z));
    }

    // 18. 灰度：按亮度混合
    if (Grayscale != 0.0) {
        c = mix(c, vec3(luma(c)), clamp(Grayscale, 0.0, 1.0));
    }

    // 19. 反相：按强度混合
    if (Invert != 0.0) {
        c = mix(c, 1.0 - c, clamp(Invert, 0.0, 1.0));
    }

    // 20. 层级混合（混合模式作用于调整层 = ADJUST 轨）：把调整层输出（上面 19 步的 c = c_adj）
    //     与基画面（Sampler0 原图 src.rgb = c_base）按混合模式整体混合——
    //     c = mix(c_base, blend(c_base, c_adj, BlendMode), clamp(BlendAmount, 0, 1))（逐通道 RGB）；
    //     不是操作栈步骤，而是层级语义（调整层输出 vs 底下的画面）；alpha 逐位直通（不受本步影响）。
    //     缺省 normal + 1 = 直替换（与混合功能落地前逐位一致，故该步整段跳过）；
    //     BlendAmount = 0 → 调整层整体透明（输出 = 基画面 = 恒等）
    if (BlendMode != 0.0 || BlendAmount != 1.0) {
        vec3 base = src.rgb;
        c = mix(base, blendChannel(base, c, BlendMode), clamp(BlendAmount, 0.0, 1.0));
    }

    // alpha 直通：透明度归合成层（LaneCompositor / Overlay 层 opacity），此处只输出 rgb 的运算结果
    fragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
