// 资源字段的纯逻辑（与 SFC 分离，便于自检）：译文预览 / 缺 key、缺文件警告判定。
// 只做“值 → 提示”的判定，不碰 DOM 与连接状态；UI 由 ResourceStringField.vue 负责。
import type { ResourceLangEntry } from '../../types'
import { t } from '../../i18n'

/** 引用前缀（与 Java LangResources.PREFIX 一致） */
export const LANG_PREFIX = '@lang:'

/** 提示内容：text = 展示文本，warn = 是否警告态（缺 key / 缺文件） */
export interface ResourceHint {
  text: string
  warn: boolean
}

/** 取某条 key 在给定语言下的译文（回落 en_us → 任意已有译文） */
export function translationOf(entry: ResourceLangEntry, uiLang: string): string | undefined {
  const dict = entry.values || {}
  const text = dict[uiLang] ?? dict['en_us'] ?? Object.values(dict)[0]
  return text === undefined ? undefined : String(text)
}

/**
 * 文本字段提示：命中 → 显示译文（普通态）；缺 key → 警告；非引用 / 字典为空 → 不提示
 * （字典为空说明语言文件还没建，不该报缺 key——资源目录本来就可以后补）。
 */
export function langHint(value: string, entries: ResourceLangEntry[], uiLang: string): ResourceHint | null {
  if (!value.startsWith(LANG_PREFIX)) return null
  const key = value.slice(LANG_PREFIX.length).trim()
  if (!key || entries.length === 0) return null
  const hit = entries.find(e => e.key === key)
  const text = hit ? translationOf(hit, uiLang) : undefined
  if (text === undefined) return { text: t('resource.lang_missing'), warn: true }
  return { text: text.replace(/\n/g, '⏎'), warn: false }
}

/** 图片路径提示：清单里有 → 不提示；没有 → 警告（清单为空不提示） */
export function fileHint(value: string, files: string[]): ResourceHint | null {
  if (files.length === 0) return null
  return files.includes(value) ? null : { text: t('resource.file_missing'), warn: true }
}
