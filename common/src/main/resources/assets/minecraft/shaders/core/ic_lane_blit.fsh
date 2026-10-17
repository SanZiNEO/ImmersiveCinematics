#version 150

// lane 合成 blit 的片元着色器 —— 合成层专用（LaneCompositor）。
//
// 与原版 position_tex 的差别：
//   · 不读 lane 纹理的 alpha：RGB 直出（× ColorModulator.rgb，合成层恒为 1）；
//   · 输出 alpha = ColorModulator.a（= 合成层的 opacity）× 遮罩覆盖率（full = 1）。
//
// 于是混合结果（JSON 的 blend = add / srcalpha / 1-srcalpha）为
//   dst = lane.rgb × opacity × coverage + main.rgb × (1 − opacity × coverage)
// —— 与 lane 纹理里写下的 a 值完全无关；遮罩区域外 coverage = 0 → 主画面保持原样。
//
// 为什么必须忽略 lane 纹理 alpha（见 LaneCompositor 类注释「alpha 契约」）：lane FBO 的 alpha 不干净
// 且无意义——原版从不显示它（RenderTarget._colorMask(true,true,true,false)），所以星星（a=127）、
// 方块图集 mipmap 边缘 1px 暗缝（a=146~254）、cutout 植被等都留着 a<255。用 position_tex
// （color × ColorModulator + srcalpha 混合）上屏时，这些像素的混合权重会被乘成 a × opacity，
// 主画面就从 lane 的 a<255 处透出来 = 隐约透底。lane 内画面在设计上是 100% 不透明的完整画面，
// 透明度只由合成层承担。
//
// 遮罩（CAMERA clip 的 mask_* 关键帧）：
//   · 所有几何 uniform 都是窗口物理像素（Java 侧按主 framebuffer 的 screenW / screenH 换算后上传）。
//     gl_FragCoord 原点在窗口左下，这里翻成左上原点（与脚本字段口径一致）。
//   · rect：MaskCenter ± MaskHalfSize（物理像素），硬边；
//   · circle：MaskCenter 为中心、MaskRadius 为半径（物理像素）——非正方形窗口里仍是真圆，硬边；
//   · MaskShape = 0 full（不读其余遮罩 uniform，Java 侧仍会显式复位它们）。
//
// 同理不保留 position_tex 的 `if (color.a == 0.0) discard;`：lane 的 alpha 不参与任何判定。

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

uniform float MaskShape;      // 0 = full / 1 = rect / 2 = circle
uniform vec2 MaskCenter;      // 窗口中心（物理像素，左上原点）
uniform vec2 MaskHalfSize;    // rect 半宽 / 半高（物理像素）
uniform float MaskRadius;     // circle 半径（物理像素）
uniform vec2 ScreenSize;      // 主 framebuffer 物理像素

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec3 rgb = texture(Sampler0, texCoord0).rgb;

    float coverage = 1.0;
    if (MaskShape > 0.5) {
        // gl_FragCoord 原点在左下 → 翻成窗口左上原点（物理像素）
        vec2 screenPos = vec2(gl_FragCoord.x, ScreenSize.y - gl_FragCoord.y);
        if (MaskShape < 1.5) {
            vec2 d = abs(screenPos - MaskCenter);
            coverage = (d.x <= MaskHalfSize.x && d.y <= MaskHalfSize.y) ? 1.0 : 0.0;
        } else {
            coverage = (length(screenPos - MaskCenter) <= MaskRadius) ? 1.0 : 0.0;
        }
    }

    fragColor = vec4(rgb * ColorModulator.rgb, ColorModulator.a * coverage);
}
