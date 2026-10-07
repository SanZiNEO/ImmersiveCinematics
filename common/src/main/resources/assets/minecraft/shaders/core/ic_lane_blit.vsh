#version 150

// lane 合成 blit 的顶点着色器。
// 顶点格式 = DefaultVertexFormat.POSITION_TEX（属性名 Position / UV0，见 ic_lane_blit.json）。
// 与原版 position_tex.vsh 逐行同构（合成层复刻 RenderTarget._blitToScreen 的屏幕空间画法：
// 正交投影 + 模型视图平移，UV 直通）。

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
}
