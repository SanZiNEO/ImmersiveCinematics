// ─────────────────────────────────────────────────────────────
// ImmersiveCinematics WebUI Editor — 完整类型系统
// 与 Java 侧 CinematicScript / ScriptMeta / Timeline / Clip / Keyframe / TriggerDefinition 一一对应
// 字段含义见 docs/SCRIPT_FORMAT.md
// ─────────────────────────────────────────────────────────────

/** 轨道类型枚举（与 Java TrackType 一致） */
export type TrackType = 'CAMERA' | 'LETTERBOX' | 'AUDIO' | 'EVENT' | 'MOD_EVENT' | 'OVERLAY'

/** 位置数据：相对模式用 dx/dy/dz，绝对模式用 x/y/z */
export interface PositionData {
  dx?: number
  dy?: number
  dz?: number
  x?: number
  y?: number
  z?: number
  relative_origin?: string
  relative_origin_x?: number
  relative_origin_y?: number
  relative_origin_z?: number
  [key: string]: unknown
}

/** 贝塞尔曲线控制点 */
export interface BezierControlPoint {
  x?: number
  y?: number
  z?: number
  dx?: number
  dy?: number
  dz?: number
  [key: string]: unknown
}

/** 贝塞尔曲线 */
export interface BezierCurve {
  type?: string
  control_points: BezierControlPoint[]
  [key: string]: unknown
}

/** 前置条件（trigger.requires 元素） */
export interface TriggerRequirement {
  type?: string
  script?: string
  [key: string]: unknown
}

/** 触发器定义（与 Java TriggerDefinition 一致） */
export interface TriggerDefinition {
  id: string
  type: string
  conditions: Record<string, unknown>
  repeatable: boolean
  delay: number
  on_enter?: boolean
  exit_buffer?: number
  requires?: (string | TriggerRequirement)[]
  [key: string]: unknown
}

/** 脚本 meta（与 Java ScriptMeta 一致，字段名用 snake_case 匹配 JSON） */
export interface ScriptMeta {
  id: string
  name: string
  author: string
  version: number
  description?: string
  dimension?: string
  preload?: boolean
  listener?: string
  // RuntimeBehavior
  block_keyboard: boolean
  block_mouse: boolean
  block_mob_ai?: boolean
  hide_hud: boolean
  hide_arm?: boolean | null
  suppress_bob?: boolean | null
  suppress_distortion?: boolean | null
  hide_chat?: boolean | null
  hide_scoreboard?: boolean | null
  hide_action_bar?: boolean | null
  hide_title?: boolean | null
  hide_subtitles?: boolean | null
  hide_hotbar?: boolean | null
  hide_crosshair?: boolean | null
  hide_bossbar?: boolean | null
  hide_skip_hud?: boolean | null
  hud_layers?: Record<string, boolean>
  render_player_model: boolean
  pause_when_game_paused: boolean
  interruptible: boolean
  skippable: boolean
  hold_at_end: boolean
  priority?: number
  skip_vote_ratio?: number | null
  // 相机区域刷怪
  camera_mob_spawn?: boolean
  camera_mob_radius?: number
  camera_mob_ai?: boolean
  // 触发器
  triggers?: TriggerDefinition[]
  [key: string]: unknown
}

/** 通用关键帧容器（与 Java Keyframe 一致） */
export interface Keyframe {
  time: number
  // CAMERA 关键帧字段
  position?: PositionData
  position_mode?: 'relative' | 'absolute'
  follow?: 'none' | 'entity'
  follow_selector?: string
  look_at?: 'none' | 'coordinate' | 'entity'
  look_at_selector?: string
  look_at_target_x?: number
  look_at_target_y?: number
  look_at_target_z?: number
  look_at_target_structure?: string
  look_at_target?: Record<string, unknown>
  yaw_base?: 'world' | 'entity' | 'line'
  pitch_base?: 'world' | 'entity' | 'line'
  yaw_base_selector?: string
  yaw_base_from?: string
  yaw_base_to?: string
  yaw?: number
  pitch?: number
  roll?: number
  fov?: number
  zoom?: number
  // LETTERBOX
  aspect_ratio?: number
  // AUDIO
  volume?: number
  x?: number
  // EVENT
  event_type?: string
  command?: string
  // OVERLAY
  opacity?: number
  font_scale?: number
  scale_x?: number
  scale_y?: number
  [key: string]: unknown
}

/** 通用片段容器（与 Java Clip 一致） */
export interface Clip {
  start_time: number
  duration: number
  keyframes: Keyframe[]
  // CAMERA clip 字段
  transition?: 'cut' | 'morph'
  transition_duration?: number
  interpolation?: 'linear' | 'smooth'
  curve?: BezierCurve
  orient?: 'manual' | 'tangent'
  yaw_offset?: number
  pitch_offset?: number
  loop?: boolean
  loop_count?: number
  loop_mode?: 'repeat' | 'pingpong'
  cam_breath_enabled?: boolean
  cam_breath_type?: 'perlin' | 'perlin_axis' | 'sine' | 'trauma'
  cam_breath_intensity?: number
  cam_breath_seed?: number
  cam_breath_speed?: number
  cam_breath_trauma?: number
  cam_breath_decay?: number
  // AUDIO clip 字段
  sound?: string
  source?: 'file' | 'minecraft'
  category?: 'music' | 'ambient'
  pitch?: number
  fade_in?: number
  fade_out?: number
  attenuation?: 'none' | 'linear' | 'inverse'
  // EVENT / MOD_EVENT
  event_type?: string
  data?: Record<string, unknown>
  // OVERLAY
  layer_type?: 'fade' | 'image' | 'subtitle' | 'pip'
  color?: string
  path?: string
  text?: string
  z_index?: number
  [key: string]: unknown
}

/** 轨道（与 Java TimelineTrack 一致） */
export interface Track {
  type: TrackType
  id: string
  name?: string
  clips: Clip[]
  [key: string]: unknown
}

/** 时间轴（与 Java Timeline 一致） */
export interface Timeline {
  total_duration: number
  tracks: Track[]
  [key: string]: unknown
}

/** 脚本根文档（与 Java CinematicScript 一致） */
export interface ScriptDoc {
  meta: ScriptMeta
  timeline: Timeline
  [key: string]: unknown
}

// ─────────────────────────────────────────────────────────────
// Schema 类型（与 Java FieldDef / SchemaExporter 输出一致）
// ─────────────────────────────────────────────────────────────

/** 单个字段的 schema 定义 */
export interface SchemaField {
  type: string // string / int / float / bool / tristate / enum / position / bezier_curve / map / object
  default: unknown
  required: boolean
  enumValues: string[]
  section: string
}

/** 轨道类型的 schema：clip 字段 + keyframe 字段 */
export interface TrackSchema {
  clips: Record<string, SchemaField>
  keyframes: Record<string, SchemaField>
}

/** 触发器 conditions schema */
export interface TriggerConditionSchema {
  fields: Record<string, SchemaField>
}

/** 完整 schema（schema.get 返回值） */
export interface Schema {
  meta: Record<string, SchemaField>
  tracks: Record<string, TrackSchema>
  triggers?: {
    types: string[]
    conditions: Record<string, Record<string, SchemaField>>
  }
  [key: string]: unknown
}

// ─────────────────────────────────────────────────────────────
// 编辑器状态类型
// ─────────────────────────────────────────────────────────────

/** 选中状态 */
export interface Selection {
  track: number
  clip: number
  keyframe: number
}

/** 轨道显示状态（纯编辑器状态，不写入脚本） */
export interface TrackViewState {
  visible: boolean
  locked: boolean
  muted: boolean
}

/** 时间轴工具 */
export type TimelineTool = 'select' | 'razor'
