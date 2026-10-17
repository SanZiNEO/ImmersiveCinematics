#version 150

// 颜色遮罩（fade 高级路径）顶点着色器。
// 顶点格式 = DefaultVertexFormat.POSITION_TEX（Position / UV0，见 ic_color_mask.json）。
// 顶点坐标 = GUI 像素（左上原点、y 向下）；正交投影由 CPU 下发（0..W, H..0），这里只做标准变换。
// UV0 仅为顶点格式占位，片元不读取（遮罩 / 渐变几何全部由 guiPos 在片元内计算）。

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 guiPos;

void main() {
    guiPos = Position.xy;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
