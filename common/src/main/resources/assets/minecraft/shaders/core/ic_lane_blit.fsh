#version 150

// lane 合成 blit 的片元着色器 —— 合成层专用（LaneCompositor）。
//
// 与原版 position_tex 的唯一差别：**不读 lane 纹理的 alpha**。
//   · RGB 直出：lane 画面的颜色逐像素照搬（× ColorModulator.rgb，合成层恒为 1）；
//   · alpha 恒 1：写出的 alpha 只由 ColorModulator.a（= 合成层的 opacity）决定。
//
// 于是混合结果（JSON 的 blend = add / srcalpha / 1-srcalpha）为
//   dst = lane.rgb × opacity + main.rgb × (1 − opacity)
// —— 与 lane 纹理里写下的 a 值完全无关。
//
// 为什么必须这样（见 LaneCompositor 类注释「alpha 契约」）：lane FBO 的 alpha 不干净且无意义——
// 原版从不显示它（RenderTarget._colorMask(true,true,true,false)），所以星星（a=127）、方块图集
// mipmap 边缘 1px 暗缝（a=146~254）、cutout 植被等都留着 a<255。用 position_tex（color × ColorModulator
// + srcalpha 混合）上屏时，这些像素的混合权重会被乘成 a × opacity，主画面（玩家视角）就从 lane 的
// a<255 处透出来 = 隐约透底。lane 内画面在设计上是 100% 不透明的完整画面，透明度只由合成层承担。
//
// 同理不保留 position_tex 的 `if (color.a == 0.0) discard;`：lane 的 alpha 不参与任何判定。

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(Sampler0, texCoord0).rgb, 1.0) * ColorModulator;
}
