import { describe, expect, it } from 'vitest'
import { type AskedStorage, isAutomatic, nicknameAsked, nicknameLength, normalized, problemIn } from './nicknameRules'

const memory = (): AskedStorage => {
  const set = new Set<string>()
  return { get: (k) => set.has(k), set: (k) => void set.add(k) }
}

// iOS tests/NicknameRulesTests.swift — 계약: 앞뒤 공백을 뗀 뒤 2~16자, 한글·영문·숫자·`_`.
describe('NicknameRules', () => {
  it('길이 경계', () => {
    expect(problemIn('가')).toBe('tooShort')
    expect(problemIn('가나')).toBeNull()
    expect(problemIn('a'.repeat(16))).toBeNull()
    expect(problemIn('a'.repeat(17))).toBe('tooLong')
    expect(problemIn('')).toBe('tooShort')
  })

  it('길이는 바이트가 아니라 글자 수다', () => {
    expect(problemIn('가'.repeat(16))).toBeNull()
    expect(problemIn('가'.repeat(17))).toBe('tooLong')
  })

  it('풀어쓴(NFD) 한글은 합친 꼴로 본다', () => {
    const decomposed = '제주'.normalize('NFD')
    expect([...decomposed].length).not.toBe(2)
    expect(problemIn(decomposed)).toBeNull()
    expect(normalized(decomposed)).toBe('제주')
    expect(nicknameLength(decomposed)).toBe(2)
  })

  it('눈에 안 보이는 한글 채움 문자로 빈칸 닉네임을 만들 수 없다', () => {
    expect(problemIn('ㅤㅤ')).toBe('badCharacters')
  })

  it('길이는 공백을 뗀 뒤 센다', () => {
    expect(problemIn('  제주러버  ')).toBeNull()
    expect(problemIn(' a ')).toBe('tooShort')
    expect(normalized('  제주러버\n')).toBe('제주러버')
  })

  it('쓸 수 있는 글자', () => {
    for (const name of ['제주러버12345', 'Seoul_Fan_07', 'ㅋㅋ', '__', '2026']) expect(problemIn(name), name).toBeNull()
  })

  it('가운데 공백·기호·이모지·한자·가나는 못 쓴다 — 한 글자여도 「못 쓰는 글자」', () => {
    for (const name of ['제주 러버', 'hello!', 'a-b', '여행자😀', '旅行者', 'たびびと', 'name@x', '!']) {
      expect(problemIn(name), name).toBe('badCharacters')
    }
  })

  it('「여행자 + 숫자」 는 서버가 붙이는 꼴이라 고를 수 없다', () => {
    expect(problemIn('여행자10001')).toBe('reserved')
    expect(problemIn(' 여행자7 ')).toBe('reserved')
    expect(isAutomatic('여행자48213')).toBe(true)
    for (const name of ['여행자', '여행자_01', '여행자10001번', '제주여행자1']) expect(problemIn(name), name).toBeNull()
  })

  it('로그인 응답의 nicknameConfirmed 가 false 일 때만, 그 계정에 한 번만 묻는다', () => {
    const store = memory()
    expect(nicknameAsked.shouldAsk(false, 'a', store)).toBe(true)
    expect(nicknameAsked.shouldAsk(true, 'a', store)).toBe(false)
    expect(nicknameAsked.shouldAsk(undefined, 'a', store)).toBe(false) // 옛 서버는 칸이 없다
    nicknameAsked.mark('a', store)
    expect(nicknameAsked.shouldAsk(false, 'a', store)).toBe(false)
    expect(nicknameAsked.shouldAsk(false, 'b', store)).toBe(true) // 다른 계정은 따로다
  })
})
