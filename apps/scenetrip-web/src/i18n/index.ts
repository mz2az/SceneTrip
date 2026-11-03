/*
 * 문구 번역 — iOS 의 `tr()` 와 같은 규칙 (apps/scenetrip-ios/src/Models/AppLanguage.swift).
 * 웹은 2026-11-03 저장된 언어가 없으면 기기 언어와 무관하게 영어로 시작한다(MZ2AZ-523).
 *
 *  - 열쇠는 **한국어 원문**이다. 코드에 한국어를 그대로 적고 영어일 때만 표에서 찾는다. 표에 없으면 원문이 나온다.
 *  - 숫자·이름이 들어가는 문구는 iOS 와 같이 `%d` `%lld` `%@` `%.1f` `%1$@` 자리표시자를 쓴다.
 *      SwiftUI 의 `Text("스탬프 \(a)/\(b)")` 는 열쇠가 "스탬프 %lld/%lld" 이므로 tr("스탬프 %lld/%lld", a, b) 로 쓴다.
 *  - 같은 한국어가 자리에 따라 다른 영어이면 열쇠는 `원문|자리` (trAt).
 *  - 영어 표는 iOS 의 en.lproj/Localizable.strings 에서 `npm run gen` 이 만든다(src/i18n/en.generated.json).
 */
import { useSyncExternalStore } from 'react'
import enTable from './en.generated.json'

export type Lang = 'ko' | 'en'
export const LANG_CHOICES: Lang[] = ['ko', 'en']
const KEY = 'scenetrip.language'

const en = enTable as Record<string, string>

/** 언어 이름은 그 언어로 적는다 (AppLanguage.name(of:)). */
export function langName(lang: Lang): string {
  return lang === 'ko' ? '한국어' : 'English'
}

function readSaved(): Lang | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'ko' || v === 'en' ? v : null
  } catch {
    return null
  }
}

let current: Lang = readSaved() ?? 'en'
const listeners = new Set<() => void>()
/** 언어가 **실제로 바뀔 때** 한 번 부른다. 화면을 다시 그리는 리스너(`listeners`)보다 먼저 돌아서, 다시 읽는 쪽이 비워진 사본을 본다. */
const changeListeners = new Set<(lang: Lang) => void>()

/** 서버에서 받아 들고 있는 사본(작품·장소 이름 등)은 받을 때의 언어로 굳는다 — 언어가 바뀌면 비우거나 다시 받는 곳에서 부른다. */
export function onLanguageChange(listener: (lang: Lang) => void): () => void {
  changeListeners.add(listener)
  return () => changeListeners.delete(listener)
}

export function currentLang(): Lang {
  return current
}
export function chooseLanguage(lang: Lang) {
  try {
    localStorage.setItem(KEY, lang)
  } catch {
    /* 저장 못 해도 이번 실행은 그 언어로 */
  }
  const changed = current !== lang
  current = lang
  if (typeof document !== 'undefined') document.documentElement.lang = lang
  if (changed) changeListeners.forEach((l) => l(lang))
  listeners.forEach((l) => l())
}
if (typeof document !== 'undefined') document.documentElement.lang = current

/** 언어가 바뀌면 다시 그려야 하는 컴포넌트에서 부른다. */
export function useLang(): Lang {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => current,
  )
}

/** printf 계열 자리표시자를 채운다. `%1$@` 같은 위치 지정, `%.1f` 같은 소수 자릿수를 지원한다. */
export function format(template: string, args: ReadonlyArray<string | number>): string {
  let next = 0
  return template.replace(/%(?:(\d+)\$)?(\.\d+)?(@|lld|ld|d|f|%)/g, (match, pos: string | undefined, prec: string | undefined, kind: string) => {
    if (kind === '%') return '%'
    const index = pos ? Number(pos) - 1 : next++
    const value = args[index]
    if (value === undefined) return match
    if (kind === 'f') return Number(value).toFixed(prec ? Number(prec.slice(1)) : 6)
    if (kind === 'd' || kind === 'lld' || kind === 'ld') return String(Math.trunc(Number(value)))
    return String(value)
  })
}

function lookup(key: string): string | undefined {
  if (current === 'ko') return undefined
  // SwiftUI 의 `\(count)` 는 열쇠에 `%lld` 로 남는다 — 코드에 `%d` 로 적어도 같은 문구를 찾는다.
  return en[key] ?? (key.includes('%d') ? en[key.replace(/%d/g, '%lld')] : undefined)
}

/** 한국어 원문을 지금 언어로. 숫자·이름은 뒤에 인자로 넘긴다. */
export function tr(korean: string, ...args: Array<string | number>): string {
  const found = lookup(korean) ?? korean
  return args.length ? format(found, args) : found
}

/** 같은 한국어가 자리에 따라 다른 영어일 때 — 열쇠는 `원문|자리`. 없으면 자리 없는 번역으로 물러선다. */
export function trAt(korean: string, place: string, ...args: Array<string | number>): string {
  const found = lookup(`${korean}|${place}`) ?? lookup(korean) ?? korean
  return args.length ? format(found, args) : found
}

/** 테스트용 — 영어 표에 열쇠가 있는가. */
export function hasEnglish(korean: string): boolean {
  return korean in en
}
