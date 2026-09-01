export interface ScriptDoc {
  meta?: any
  timeline?: any
  [key: string]: any
}

export interface SchemaField {
  type: string
  default: any
  required: boolean
  enumValues: string[]
  section: string
}

export interface Schema {
  meta: Record<string, SchemaField>
  tracks: Record<string, {
    clips: Record<string, SchemaField>
    keyframes: Record<string, SchemaField>
  }>
}
