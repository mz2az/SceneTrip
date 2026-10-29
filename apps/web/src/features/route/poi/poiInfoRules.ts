/*
 * 관광공사 안내의 표시 규칙 — iOS RouteTab/PoiInfoRules.swift (MZ2AZ-470). 내용·언어 폴백은 서버가 결정한다.
 */
import type { components } from '@/api/generated/schema'
import { currentLang, tr, type Lang } from '@/i18n'
import { webAddress } from './webAddress'

export type PoiInfo = components['schemas']['PoiInfo']
type PoiInfoText = components['schemas']['PoiInfoText']

export interface PoiInfoRow {
  id: string
  label: string
  text: string
  languageTag: string | null
}

const FIELDS: ReadonlyArray<readonly [string, keyof PoiInfo]> = [
  ['개요', 'overview'], ['이용 시간', 'useTime'], ['쉬는 날', 'restDate'], ['주차', 'parking'], ['대표 메뉴', 'firstMenu'], ['입장료', 'useFee'],
]

/** 글이 쓰인 언어가 앱 언어와 다를 때만 언어 이름을 붙인다. */
function languageTag(lang: components['schemas']['Lang'], app: Lang): string | null {
  if (lang === app) return null
  switch (lang) {
    case 'ko':
      return tr('한국어')
    case 'en':
      return tr('영어')
    case 'ja':
      return tr('일본어')
    case 'zh-Hant':
      return tr('중국어 번체')
    default:
      return null
  }
}

export function poiInfoRows(info: PoiInfo | null | undefined, app: Lang = currentLang()): PoiInfoRow[] {
  if (!info) return []
  const rows: PoiInfoRow[] = []
  for (const [label, key] of FIELDS) {
    const value = info[key] as PoiInfoText | null | undefined
    if (!value || !value.text.trim()) continue
    rows.push({ id: label, label: tr(label), text: value.text, languageTag: languageTag(value.lang, app) })
  }
  return rows
}

export const poiInfoHomepage = (info: PoiInfo | null | undefined): string | null => webAddress(info?.homepage)

/** 값 없는 안내에는 공간을 잡지 않는다. */
export const poiInfoVisible = (info: PoiInfo | null | undefined): boolean => poiInfoRows(info).length > 0 || poiInfoHomepage(info) !== null
