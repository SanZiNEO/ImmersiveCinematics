# 0.3.6 计划索引

> 本目录归档 0.3.6 的计划与方向稿。
> 长期计划方向稿只确定方向、已确认项、可能的问题和待定项；
> 接口、字段、公式、JSON、迁移步骤等执行时再查再定。

## 一、功能计划

| 文档 | 说明 |
|---|---|
| `editor-webui-migration.md` | 编辑器 / WebUI 迁移计划 |
| `multi-camera-rendering.md` | 多相机渲染计划 |
| `overlay-color-mask.md` | Overlay 颜色遮罩计划 |
| `pause-point-track.md` | 暂停点轨道计划 |

## 二、相机底层架构

| 文档 | 说明 |
|---|---|
| `camera-state-plan.md` | 6 参数模型 + Base Chain / Modifier Chain 覆盖链；staged 删除；内部优先，API 顺带 |

## 三、底层通用能力

| 文档 | 说明 |
|---|---|
| `math-models.md` | 通用数学函数模型：一个定义多处调用、定义/实例分离、分类与扩展方向 |
| `temporal-interpolation.md` | 时间插值：逻辑 tick 与渲染帧之间的防卡顿 / 防抽帧平滑 |

## 四、相机应用层

| 文档 | 说明 |
|---|---|
| `transition.md` | 过渡：A→B 的六参数状态变化过程，用于非瞬时切镜头与丰富运镜 |
| `hysteresis.md` | 迟滞：相机参数运动响应（缓动 / 粘滞 / 阻尼 / 弹簧 / 惯性） |

## 五、相关长期讨论

- `../0.4.0/camera-motion-model.md`：相机运动模型与速度控制（长期讨论）
