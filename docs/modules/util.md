# util（工具类）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/util/`

功能树：

- **数学工具**
  - ✅ `MathUtil` 提供相机系统共用插值函数：`lerp` 线性插值、`lerpAngle` 角度环绕插值（处理 ±180° 环绕跳变）（`MathUtil`）
  - ✅ 插值曲线：`smooth`（Hermite 3t²-2t³）、`smooth5`（五次 Hermite）、`easeIn`/`easeOut`/`easeInOut`（二次缓动）（`MathUtil`）
  - ✅ `cubicBezier` 三次贝塞尔曲线求值（P1==P2 重合时做正圆弧运动）（`MathUtil`）
  - ✅ `smoothstep` 平滑混合（morph 过渡权重），含 edge0==edge1 的 NaN 防护（`MathUtil`）
  - ✅ Hermite 基函数 h00/h10/h01/h11（速度曲线引擎用）（`MathUtil`）
  - ✅ NaN/Infinity 防护：`sanitizeFloat`/`sanitizeVec3` 防止异常脚本数据污染相机状态（`MathUtil`）
- **资源路径**
  - ✅ `ResourcePath` 统一管理外部资源（音频/图片）路径：根目录 `<游戏目录>/immersive_cinematics/resource/`，提供解析/存在性检查/目录创建（`ResourcePath`）
- **纹理加载**
  - ✅ `TextureLoader` 从资源目录加载 PNG 纹理为 `DynamicTexture`，以 `immersive_cinematics:<文件名>` 注册，带缓存与清空缓存接口（`TextureLoader`）

## 已知问题

- `TextureLoader.clearCache()` 无调用方：纹理缓存只在进程生命周期内累积，资源重载后不会自动清空（来源：`TextureLoader`）
