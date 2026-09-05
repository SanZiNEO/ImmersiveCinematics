// ─────────────────────────────────────────────────────────────
// ImmersiveCinematics WebUI Editor — Schema 消费工具
// 对应 Java 侧 EditorDefaults.java + 保存时精简默认字段的逻辑
// Java 侧 SchemaRegistry 是唯一权威，前端只消费 schema.get 返回的数据
// ─────────────────────────────────────────────────────────────

import type { Schema, SchemaField, TrackType } from './types'

// ── 字段分组 ──────────────────────────────────────────────────

/** 按 section 分组字段，返回 { section: [key, field][] }，保持原顺序 */
export function groupBySection(
  fields: Record<string, SchemaField>,
): Record<string, [string, SchemaField][]> {
  const result: Record<string, [string, SchemaField][]> = {}
  for (const [key, field] of Object.entries(fields)) {
    const section = field.section || 'info'
    if (!result[section]) result[section] = []
    result[section].push([key, field])
  }
  return result
}

/** 获取指定 section 的字段列表 */
export function getFieldsForSection(
  fields: Record<string, SchemaField>,
  section: string,
): [string, SchemaField][] {
  return Object.entries(fields).filter(([, f]) => (f.section || 'info') === section)
}

// ── 默认值填充（对应 EditorDefaults.java）──────────────────────

/**
 * 按 schema 填充对象缺失字段。
 * - tristate / 无默认值字段不写（保持 undefined = 未设置）
 * - required 且无默认的 string 字段补 ''
 * - position 类型且为 CAMERA 关键帧时补 {dx:0, dy:2, dz:0}
 */
export function fillDefaults(
  obj: Record<string, unknown>,
  fields: Record<string, SchemaField>,
  opts?: { trackType?: TrackType; isKeyframe?: boolean },
): void {
  for (const [key, def] of Object.entries(fields)) {
    if (obj[key] !== undefined) continue
    const defVal = def.default
    if (defVal != null) {
      obj[key] = cloneDefault(defVal)
    } else if (def.required && def.type === 'string') {
      obj[key] = ''
    } else if (def.type === 'position' && opts?.isKeyframe && opts?.trackType === 'CAMERA') {
      obj[key] = { dx: 0, dy: 2, dz: 0 }
    }
  }
}

/** 填充 clip 的默认字段（按轨道类型 schema） */
export function fillClipDefaults(
  clip: Record<string, unknown>,
  schema: Schema,
  trackType: TrackType,
): void {
  const trackSchema = schema.tracks?.[trackType]
  if (!trackSchema) return
  fillDefaults(clip, trackSchema.clips, { trackType })
}

/** 填充 keyframe 的默认字段（按轨道类型 schema） */
export function fillKeyframeDefaults(
  kf: Record<string, unknown>,
  schema: Schema,
  trackType: TrackType,
): void {
  const trackSchema = schema.tracks?.[trackType]
  if (!trackSchema) return
  fillDefaults(kf, trackSchema.keyframes, { trackType, isKeyframe: true })
}

/** 填充 meta 的默认字段 */
export function fillMetaDefaults(
  meta: Record<string, unknown>,
  schema: Schema,
): void {
  fillDefaults(meta, schema.meta)
}

// ── 保存精简（删除等于默认值的字段）────────────────────────────

/**
 * 保存前精简：删除值等于默认值的字段。
 * tristate 的 null/undefined 本来就不写。
 * 保留 start_time / duration / time / keyframes / type / id 等结构字段。
 */
export function stripDefaults(
  obj: Record<string, unknown>,
  fields: Record<string, SchemaField>,
): void {
  for (const [key, def] of Object.entries(fields)) {
    const val = obj[key]
    if (val === undefined) continue
    // 必填字段绝不能删：例如 meta.version / meta.id / meta.name
    // 删掉后 ScriptParser 会报“缺少必填字段”，导致脚本根本无法播放。
    if (def.required) continue
    // tristate 为 null 时删除
    if (def.type === 'tristate' && val === null) {
      delete obj[key]
      continue
    }
    // 值等于默认值时删除（用深度比较）
    if (def.default != null && deepEqual(val, def.default)) {
      delete obj[key]
    }
  }
}

/** 精简整个脚本 doc（meta + 所有 clip/keyframe） */
export function stripScriptDefaults(doc: Record<string, unknown>, schema: Schema): void {
  // meta 不精简：id/name/author/version 都是 ScriptParser 必填字段，
  // 精简会把看似“默认”的 author/version 删掉，导致播放器无法解析。
  // timeline.tracks
  const timeline = doc.timeline as Record<string, unknown> | undefined
  const tracks = timeline?.tracks as unknown[] | undefined
  if (!tracks) return
  for (const track of tracks) {
    const t = track as Record<string, unknown>
    const trackType = t.type as TrackType
    const trackSchema = schema.tracks?.[trackType]
    if (!trackSchema) continue
    const clips = t.clips as Record<string, unknown>[] | undefined
    if (!clips) continue
    for (const clip of clips) {
      stripDefaults(clip, trackSchema.clips)
      const kfs = clip.keyframes as Record<string, unknown>[] | undefined
      if (kfs) {
        for (const kf of kfs) {
          stripDefaults(kf, trackSchema.keyframes)
        }
      }
    }
  }
}

// ── 字段类型工具 ──────────────────────────────────────────────

export function isComplexType(type: string): boolean {
  return ['position', 'bezier_curve', 'map', 'object'].includes(type)
}

export function isNumericType(type: string): boolean {
  return type === 'int' || type === 'float'
}

export function getSectionLabel(section: string): string {
  const labels: Record<string, string> = {
    info: '基本信息',
    runtime: '运行时行为',
    camera: '相机区域',
  }
  return labels[section] ?? section
}

// ── 内部工具 ──────────────────────────────────────────────────

function cloneDefault(val: unknown): unknown {
  if (val === null || typeof val !== 'object') return val
  return JSON.parse(JSON.stringify(val))
}

function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== typeof b) return false
  if (a === null || b === null) return false
  if (typeof a !== 'object') return false
  const aObj = a as Record<string, unknown>
  const bObj = b as Record<string, unknown>
  const aKeys = Object.keys(aObj)
  const bKeys = Object.keys(bObj)
  if (aKeys.length !== bKeys.length) return false
  for (const k of aKeys) {
    if (!deepEqual(aObj[k], bObj[k])) return false
  }
  return true
}
