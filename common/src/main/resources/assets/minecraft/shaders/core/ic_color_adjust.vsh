#version 150

// 画面颜色调整（master）全屏 pass 的顶点着色器。
// 顶点格式 = DefaultVertexFormat.POSITION_TEX（属性名 Position / UV0，见 ic_color_adjust.json）。
// 与 program/sobel.vsh（原版后处理全屏 quad 的顶点着色器）同构：只做 ProjMat * ModelViewMat 变换 + UV 直通。

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
}
