/*
 * 닉네임 규칙 — iOS `NicknameRules` · `NicknameAsked` (Reviews/NicknameRules.swift, MZ2AZ-363).
 * 계약 `PUT /me/nickname`: 앞뒤 공백을 뗀 뒤 **2~16자, 한글·영문·숫자·`_`**.
 *
 * 서버가 정본이다 — 여기는 보내기 전에 걸러 헛걸음을 줄일 뿐이고, 서버가 `NICKNAME_INVALID` 를 주면 그 판정을 따른다.
 * 그래서 헷갈리는 글자(낱자 「ㅋㅋ」)는 여기서 막지 않고 서버에 맡긴다. 겹침(`NICKNAME_TAKEN`)은 서버만 안다.
 */
import { tr } from '@/i18n'
import { readStorage, writeStorage } from '@/state/storage'

export const NICKNAME_MIN = 2
export const NICKNAME_MAX = 16

export type NicknameProblem = 'tooShort' | 'tooLong' | 'badCharacters' | 'reserved'

/**
 * 서버에 보낼 모양 — 앞뒤 공백을 뗀다. 한글은 **합친 꼴(NFC)** 로 보낸다 — 맥에서 붙여 넣은 풀어쓴 한글(「ㅈ+ㅔ」)은
 * 같은 글자인데 낱자로 세어진다.
 */
export function normalized(raw: string): string {
  return raw.trim().normalize('NFC')
}

/** 한글(완성형·낱자)·영문·숫자·밑줄. */
function allowed(codePoint: number): boolean {
  if (codePoint >= 0x30 && codePoint <= 0x39) return true // 0-9
  if (codePoint >= 0x41 && codePoint <= 0x5a) return true // A-Z
  if (codePoint >= 0x61 && codePoint <= 0x7a) return true // a-z
  if (codePoint === 0x5f) return true // _
  if (codePoint >= 0xac00 && codePoint <= 0xd7a3) return true // 가-힣
  if (codePoint === 0x3164) return false // 한글 채움 문자 — 눈에 안 보인다. 빈칸 닉네임이 된다
  return codePoint >= 0x3131 && codePoint <= 0x318e // ㄱ-ㅎ ㅏ-ㅣ
}

/** 글자 수 — 바이트가 아니라 글자다. */
export const nicknameLength = (raw: string): number => [...normalized(raw)].length

/**
 * 서버가 가입 때 붙여 주는 꼴(`여행자12345`)인가. **이 꼴은 사람이 고를 수 없다** — 고르게 두면 나중에 가입하는 사람에게
 * 같은 번호가 붙는 순간 가입이 실패한다. 서버는 이것도 `NICKNAME_INVALID` 로 답해서, 여기서 먼저 갈라 알맞은 안내를 한다.
 */
export function isAutomatic(raw: string): boolean {
  return /^여행자[0-9]+$/.test(normalized(raw))
}

/** 규칙에 맞으면 null. 길이보다 글자를 먼저 본다 — 「!」 한 글자는 「짧다」 보다 「못 쓰는 글자」 가 맞는 안내다. */
export function problemIn(raw: string): NicknameProblem | null {
  const name = normalized(raw)
  if (![...name].every((c) => allowed(c.codePointAt(0)!))) return 'badCharacters'
  const length = [...name].length
  if (length < NICKNAME_MIN) return 'tooShort'
  if (length > NICKNAME_MAX) return 'tooLong'
  return isAutomatic(name) ? 'reserved' : null
}

/** 입력란 아래에 적을 안내. 문제없으면 규칙 한 줄. */
export function hintFor(problem: NicknameProblem | null): string {
  switch (problem) {
    case 'tooShort': return tr('2자 이상으로 정해 주세요')
    case 'tooLong': return tr('16자 이하로 정해 주세요')
    case 'badCharacters': return tr('한글·영문·숫자·밑줄(_)만 쓸 수 있어요')
    case 'reserved': return tr('「여행자 + 숫자」 는 자동으로 붙는 이름이라 고를 수 없어요')
    case null: return tr('2~16자, 한글·영문·숫자·밑줄(_)')
  }
}

/** 저장 결과 → 입력란 아래에 적을 문구 (서버 문장이 아니라 code 로 가른다, MZ2AZ-345). */
export type NicknameOutcome = 'saved' | 'invalid' | 'taken' | 'textNotAllowed' | 'failed'

// ---------------------------------------------------------------- 한 번만 묻는다

/**
 * 닉네임을 **한 번만 묻는다** — 건너뛴 사람에게 로그인할 때마다 다시 묻지 않는다(티켓). 계정마다 따로 적는다.
 * 브라우저에만 있고, 그 계정으로 물어봤다는 사실뿐이다.
 */
export interface AskedStorage {
  get(key: string): boolean
  set(key: string): void
}

export const browserAsked: AskedStorage = {
  get: (key) => readStorage(key) === 'true',
  set: (key) => writeStorage(key, 'true'),
}

const askedKey = (accountId: string) => `scenetrip.nickname.asked.${accountId}`

export const nicknameAsked = {
  has: (accountId: string, store: AskedStorage = browserAsked): boolean => store.get(askedKey(accountId)),
  mark: (accountId: string, store: AskedStorage = browserAsked): void => store.set(askedKey(accountId)),
  /** 로그인 응답을 보고 물어볼지 정한다. `confirmed` 가 **false 일 때만** — 옛 서버는 칸이 없어 undefined 다. */
  shouldAsk: (confirmed: boolean | null | undefined, accountId: string, store: AskedStorage = browserAsked): boolean =>
    confirmed === false && !nicknameAsked.has(accountId, store),
}
