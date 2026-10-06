```mermaid
flowchart LR
    subgraph PB["播放（实例）"]
        INST["播放实例<br/>（一个正在播放的脚本；数量不设上限）"]
        subgraph LANE["实例内容"]
            CAMS["相机画面 lane × N<br/>（每条 CAMERA 轨 / 每个相机一条）"]
            NONCAM["非画面轨<br/>（音频 / 事件 / 覆盖层 / 黑边 / 模组事件）"]
        end
        LIFE["实例生命周期<br/>（可跳过 / 可打断 / 末尾保持 / 随游戏暂停）"]
        UNION["运行时控制并集<br/>（隐藏 HUD / 屏蔽输入 / 抑制摆动）"]
        subgraph LOOP["脚本循环（两级折叠）"]
            LMAC["宏观循环<br/>（整条时间轴折回；末端 = 最后片段最后一次播完）"]
            LMIC["微观循环<br/>（片段局部时间：repeat / pingpong）"]
        end
        WAITPT["等待点轨道（WAIT_POINT）<br/>（停表 → 等回报 → 继续 / 结束 / 接播 / 分支）"]
        REENTRY["重入规则<br/>（同玩家 + 同脚本 = 单实例）"]
        QUEUE["接播 / 排队<br/>（现状：不可打断才排队）"]
    end
    subgraph SRV["服务端账本"]
        SRVKEY["触发状态机 + 前置<br/>（键：玩家 + 脚本）"]
        SRVVOTE["跳过投票"]
        SRVTIME["事件时间线<br/>（EVENT 命令按实例推进）"]
    end
    subgraph INPUT["输入与直控"]
        RAW["玩家输入<br/>（键盘 / 鼠标 / 滚轮 / 视角）"]
        ROUTE["输入路由<br/>（放行 / 自用 / 屏蔽 / 飞控）"]
        KEYS["键位绑定<br/>（跳过 / 编辑器 / 飞控）"]
        BEHAV["行为开关<br/>（键鼠屏蔽 / 可跳过 / 可打断 / 暂停联动）"]
        FLY["飞行直控<br/>（编辑器取景）"]
        DIRECT["编辑器直控<br/>（拖拽 / 摆位）"]
        PAUSE["游戏暂停 / 窗口失焦"]
    end
    subgraph CAM["相机（lane）"]
        SRC["输入源"]
        subgraph CORE["相机核心"]
            subgraph MAIN["主要核心"]
                POS["位置"]
                subgraph ORI["朝向"]
                    Y["yaw"]
                    PI["pitch"]
                end
            end
            subgraph SECOND["次要核心"]
                FOV["fov"]
                R["roll"]
                Z["zoom（缩放）"]
            end
        end
        DEST["输出"]
        SRC --> CORE
        CORE --> DEST
    end
    subgraph FRAMES["参考系"]
        subgraph SELFF["自建参考系"]
            SP["位置<br/>基准点 + 三轴偏移"]
            SO["朝向<br/>相对朝向"]
        end
        subgraph WORLDF["世界参考系"]
            WP["位置<br/>游戏 x/y/z"]
            WO["朝向<br/>yaw / pitch"]
        end
    end
    subgraph TRACK["追踪"]
        LOOK["看向（look_at）"]
        FOLLOW["跟随（follow）"]
    end
    subgraph WPOS["位置（世界系）"]
        ABS["绝对位置"]
        REL["相对位置"]
    end
    subgraph COORDSRC["坐标源"]
        ENT["实体（含玩家）<br/>（可通过 AABB 百分比偏移：脚 / 头 / 其他部位）"]
        COORD["坐标"]
        STRUCT["结构"]
        BLOCK["方块"]
    end
    subgraph DIRSRC["方向源"]
        AXIS["世界轴"]
        VIEW["实体视线"]
        LINE["连线（A→B）"]
    end
    subgraph TRIGGER["触发器核心"]
        subgraph COND["条件（含状态）"]
            C_SINGLE["单一"]
            subgraph C_COMB["组合器"]
                C_ANY["任意（any）"]
                C_ALL["全部（all_of）"]
            end
            C_REQ["前置<br/>（物品 / 成就 / 脚本 / …）"]
        end
        subgraph ACTS["动作"]
            ACT["动作"]
            A_CTRL["控制脚本<br/>（播放 / 停止 / 暂停）"]
            A_WAIT["回报等待点"]
            ACT --> A_CTRL
            ACT --> A_WAIT
        end
    end
    subgraph TYPES["触发器类型（25）"]
        subgraph G1["位置 / 世界（5）"]
            T1["location"]
            T2["biome"]
            T3["structure"]
            T4["dimension"]
            T5["dimension_change"]
            T1 ~~~ T2
            T2 ~~~ T3
            T3 ~~~ T4
            T4 ~~~ T5
        end
        subgraph G2["视线（2）"]
            T6["facing"]
            T7["observation"]
            T6 ~~~ T7
        end
        subgraph G3["物品 · 使用（5）"]
            T8["item_use"]
            T9["item_consume"]
            T10["item_release"]
            T11["item_instant_use"]
            T12["item_use_interrupt"]
            T8 ~~~ T9
            T9 ~~~ T10
            T10 ~~~ T11
            T11 ~~~ T12
        end
        subgraph G4["物品 · 流转 / 持有（4）"]
            T13["item_craft"]
            T14["item_pickup"]
            T15["item_drop"]
            T16["inventory"]
            T13 ~~~ T14
            T14 ~~~ T15
            T15 ~~~ T16
        end
        subgraph G5["实体与交互（4）"]
            T17["entity_kill"]
            T18["entity_interact"]
            T19["block_interact"]
            T20["item_on_interact"]
            T17 ~~~ T18
            T18 ~~~ T19
            T19 ~~~ T20
        end
        subgraph G6["进度 / 阶段（3）"]
            T21["advancement"]
            T22["xp"]
            T23["gamestage"]
            T21 ~~~ T22
            T22 ~~~ T23
        end
        subgraph G7["会话（2）"]
            T24["login"]
            T25["death<br/>（未实现）"]
            T24 ~~~ T25
        end
    end
    subgraph WORLD["世界交互"]
        subgraph CAMZONE["区块与实体（相机中心）"]
            PRELOAD["区块预加载<br/>（加载 / 下发 / 客户端缓存中心换成相机）"]
            PAIR["实体配对<br/>（相机附近实体按原版规则配对 / 下发）"]
            ANCHOR["相机锚点<br/>（纯坐标虚拟玩家：刷怪 / 消失 / 配对的距离基准）"]
        end
        subgraph VIEWC["渲染视图"]
            VIEWCENTER["渲染视图中心<br/>（区块构建 / 可见集中心改用相机）"]
        end
        subgraph MOB["生成与消失"]
            SPAWN["自然生成<br/>（中心取真实玩家与锚点更近者）"]
            DESPAWN["生物消失<br/>（最近玩家改用锚点）"]
        end
        REGION["区域同步 / 镜像传送<br/>（对应区域坐标映射，计划）"]
    end
    subgraph MULTI["多相机"]
        MMAIN["主画面<br/>（原版一遍，直接进主缓冲）"]
        MLANE["副画面 × N（lane）<br/>（第二及以后的相机各渲一遍；不设上限）"]
        MFBO["离屏缓冲<br/>（每 lane 一张，与主画面同分辨率；顺序复用）"]
        MCOMP["合成<br/>（取材区域 / 目标区域 / 不透明度 / 叠放顺序）"]
        MWARM["叠化预热<br/>（过渡前提前渲染下一 lane）"]
    end
    subgraph RENDER["渲染"]
        RPOSE["相机姿态<br/>（位置 + yaw / pitch）"]
        RVIEW["视图矩阵<br/>（含 roll：绕视线轴）"]
        RPROJ["投影<br/>（生效 FOV = fov / zoom）"]
        RFRUSTUM["视锥<br/>（每相机独立）"]
        RCULL["遮挡剔除<br/>（可见集合，帧内共享）"]
        RLEVEL["世界渲染<br/>（区块层 / 实体层）"]
        ROUTLINE["描边上屏<br/>（lane 自包含）"]
        RPOST["后处理 / 最终上屏"]
    end
    TEX["纹理"]
    FRAME["画面"]
    subgraph OVERLAY["覆盖层"]
        OVM["覆盖层管理器<br/>（按 z 排序 · 统一渲染 / 重置）"]
        subgraph LAYERS["图层（z_index 升序）"]
            LB["画幅黑边<br/>（letterbox，z=0）"]
            FADE["颜色遮罩 / 淡入淡出<br/>（fade，z=10）"]
            IMG["图片 / GIF<br/>（image，z=20）"]
            SUB["字幕<br/>（subtitle，z=30）"]
            PIP["画中画<br/>（pip，z=40 · 占位）"]
        end
        RES["资源加载<br/>（resource/ 目录 · PNG / GIF）"]
        TRKOV["覆盖层轨道（OVERLAY）"]
        TRKLB["黑边轨道（LETTERBOX）"]
    end
    subgraph HUD["HUD 显隐"]
        WL["显隐白名单<br/>（hide_hud + 各单项开关 + hud_layers）"]
        META["脚本 meta<br/>（hide_* / hud_layers）"]
        MIXGUI["原版 HUD 逐元素拦截<br/>（Gui / 聊天 / Boss 条 / 玩家列表 / 字幕）"]
        REGOV["注册表 overlay 拦截<br/>（Forge · RenderGuiOverlayEvent.Pre）"]
        FABQ["Fabric 主动查询 API<br/>（isHidden(layerId)）"]
        HARD["强硬隐藏模式<br/>（Pre 接管 + 白名单重画，计划）"]
    end
    subgraph AUDIO["音频"]
        subgraph LISTEN["听者（meta.listener）"]
            LP["玩家听者（player，默认）"]
            LC["相机听者（camera）<br/>（需活跃 CAMERA clip）"]
        end
        subgraph ASRC["音源（AUDIO clip）"]
            SREL["相对音源<br/>（每帧锚定听者 + 关键帧偏移）"]
            SABS["绝对音源<br/>（世界坐标）"]
        end
        subgraph ACAT["类别"]
            CM["背景音（music）<br/>（非空间 / 随身）"]
            CA["环境音（ambient）<br/>（空间衰减）"]
        end
        subgraph ARES["音频资源"]
            RF["外部文件（file）<br/>（resource/ 下 ogg / wav）"]
            RM["原版声音（minecraft）<br/>（资源包 OGG）"]
        end
        APLAY["播放实例<br/>（关键帧驱动 volume / x / y / z；淡入淡出 / 循环 / 音高）"]
        ENG["原版声音引擎（SoundEngine）"]
        BGM["原版背景音乐"]
        AMB["环境音采样<br/>（群系 / 水下 / 气泡柱 / 环境粒子）"]
        BC["服务端声音广播<br/>（第二判定点）"]
    end
    subgraph EDITOR["编辑器（WebUI）"]
        EXT["外部编辑器<br/>（Vue3 + Electron 独立应用）"]
        WSS["本地 WebSocket 服务<br/>（127.0.0.1:8765）"]
        MSGROUTE["消息路由<br/>（文本协议 / 回执）"]
        SCRIPTFS["脚本文件服务<br/>（immersive_cinematics/scripts）"]
        REGISTRY["注册表与补全<br/>（物品 / 方块 / 实体 / 群系 / 维度 / 结构 / 进度…）"]
        SCHEMA["字段元数据<br/>（schema.get，Java 唯一权威）"]
        PREVIEW["预览屏（F9）<br/>（取帧 + 播放控制 + 飞控 HUD）"]
        FCAP["帧捕获<br/>（720p 小 FBO）"]
        FSTREAM["帧推流<br/>（RGBA ~60fps，丢旧帧）"]
        GRAPH["脚本架构图<br/>（无限画布，计划）"]
        OLDED["旧游戏内编辑器<br/>（F6，退役中）"]
    end
    ACT -->|控制脚本| INST
    ACT -->|回报等待点| WAITPT
    INST -->|注册| CAMS
    INST -->|持有| NONCAM
    INST -->|按实例计算| LIFE
    INST -->|逐位取并集| UNION
    INST -->|分发脚本时间| LMAC
    LMAC -->|f(g(t))| LMIC
    INST -->|到达等待点：冻结实例时钟| WAITPT
    WAITPT -->|继续 / 结束 / 分支| INST
    WAITPT -->|接播下一个| QUEUE
    INST -->|结束（原因）| QUEUE
    QUEUE -->|接播| INST
    REENTRY -->|约束新建| INST
    SRVKEY -->|命中 → 新建实例| INST
    INST -->|开始 / 结束回报（原因）| SRVTIME
    INST -->|跳过记账| SRVVOTE
    SRVVOTE -->|投票达标 → 停止实例| INST
    SRVTIME -->|EVENT 命令| INST
    CAMS -->|画面| RLEVEL
    UNION -->|隐藏 HUD| WL
    UNION -->|屏蔽输入| BEHAV
    RAW --> ROUTE
    KEYS -->|跳过长按 / 编辑器快捷键| ROUTE
    BEHAV -->|屏蔽开关| ROUTE
    LIFE -->|runtime behavior| BEHAV
    ROUTE -->|放行| PAUSE
    ROUTE -->|自用：跳过 / 强制退出| INST
    ROUTE -->|飞控| FLY
    PAUSE -->|放行输入 + 冻结时钟| ROUTE
    FLY -->|整帧 6 参数| SRC
    DIRECT -->|整帧 6 参数| SRC
    PREVIEW -->|F7 进入 / 前端指令退出| FLY
    CAM -->|× N| MULTI
    POS --> WP
    POS --> SP
    ORI --> WO
    ORI --> SO
    ORI --> LOOK
    POS --> FOLLOW
    COORDSRC --> LOOK
    ENT --> FOLLOW
    WP --> WPOS
    SP --> SELFPOS["自建位置"]
    COORDSRC --> SP
    DIRSRC --> SO
    COORDSRC --> REL
    TYPES --> COND
    POS -->|听者位置| LP
    LC ~~~ POS
    LP --> APLAY
    LC --> APLAY
    ASRC --> APLAY
    ARES --> APLAY
    APLAY --> ENG
    APLAY -->|压制| BGM
    LISTEN -->|采样点重定向| AMB
    APLAY -->|上报听者位置| BC
    BC -->|放宽半径下发| ENG
    POS -->|相机位置（周期上报）| PRELOAD
    PRELOAD -->|虚拟中心| PAIR
    PRELOAD -->|锚点（纯坐标）| ANCHOR
    PRELOAD -->|区块已加载 / 已下发| VIEWCENTER
    VIEWCENTER --> RLEVEL
    ANCHOR -->|更近者| SPAWN
    ANCHOR -->|更近者| DESPAWN
    SPAWN -->|相机区实体| PAIR
    MULTI -->|预加载取并集（计划）| PRELOAD
    ACT -->|区域规则（计划）| REGION
    REGION -->|坐标映射（站哪传哪）| ENT
    DEST -->|相机姿态| RPOSE
    RPOSE --> RVIEW
    RPOSE --> RPROJ
    RVIEW --> RFRUSTUM
    RPROJ --> RFRUSTUM
    RPOSE -->|在实心方块内| RCULL
    RFRUSTUM --> RLEVEL
    RCULL --> RLEVEL
    RLEVEL --> MMAIN
    RLEVEL -->|× N 遍| MLANE
    MLANE --> MFBO
    MFBO --> ROUTLINE
    ROUTLINE --> MCOMP
    MCOMP --> RPOST
    RPOST --> TEX
    TEX --> FRAME
    MWARM -->|提前渲染| MLANE
    MCOMP -->|无 lane 覆盖时| MMAIN
    INST -->|OVERLAY / LETTERBOX 轨| TRKOV
    INST -->|LETTERBOX 轨| TRKLB
    TRKOV -->|创建 / 移除层| OVM
    TRKLB --> LB
    LAYERS --> OVM
    RES --> IMG
    OVM -->|HUD 渲染回调| FRAME
    META -->|apply / revert| WL
    WL -->|分类判定| MIXGUI
    WL -->|分类判定| REGOV
    WL -->|分类判定| FABQ
    MIXGUI -->|cancel| FRAME
    REGOV -->|cancel| FRAME
    HARD -.->|计划| REGOV
    HARD -->|补画| OVM
    EXT -->|连接 ws://127.0.0.1:8765| WSS
    EXT -->|脚本 CRUD / 校验 / 推送| MSGROUTE
    EXT -->|registry.query / registry.get| REGISTRY
    EXT -->|schema.get| SCHEMA
    MSGROUTE --> SCRIPTFS
    MSGROUTE -->|pushScript / seek / play / pause / stop| PREVIEW
    PREVIEW -->|playback.state / flight.state| MSGROUTE
    PREVIEW --> FCAP
    FCAP --> FSTREAM
    FSTREAM -->|二进制 RGBA 帧| EXT
    SCRIPTFS -->|保存成功通知| SRVTIME
    OLDED -->|退役迁移| EXT
    GRAPH -->|requires 依赖网| SCRIPTFS
```
