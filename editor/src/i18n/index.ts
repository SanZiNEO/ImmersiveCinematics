// 国际化字典系统（仿 MC I18n）
// 语言包为扁平 key-value，支持 %s / %d 占位符替换
import zh_cn from './zh_cn'
import en_us from './en_us'

export type Lang = 'zh_cn' | 'en_us'

const LANGS: Record<Lang, Record<string, string>> = { zh_cn, en_us }

let current: Lang = 'zh_cn'

export function setLang(lang: Lang) {
  current = lang
}

export function getLang(): Lang {
  return current
}

export function t(key: string, ...args: (string | number)[]): string {
  const dict = LANGS[current] || LANGS.zh_cn
  let str = dict[key]
  if (str === undefined) {
    // 回退到英文，再回退到 key 本身
    str = LANGS.en_us[key] ?? key
  }
  // 替换 %s / %d 占位符
  if (args.length > 0) {
    let i = 0
    str = str.replace(/%[sd]/g, () => String(args[i++] ?? ''))
  }
  return str
}

// 响应式语言状态（供 Vue 组件监听切换）
import { ref } from 'vue'
export const reactiveLang = ref<Lang>('zh_cn')

export function switchLang(lang: Lang) {
  current = lang
  reactiveLang.value = lang
}
