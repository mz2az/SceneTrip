import { describe, expect, it } from 'vitest'
import { pinMarks, pinsKey } from './mapRules'
import { pinSvg } from './pinImage'

const place = (id: number) => ({ id })

describe('pinMarks', () => {
  it('열어 둔 장소가 이미 핀이면 그 핀만 강조하고 번호를 지킨다', () => {
    const marks = pinMarks([place(1), place(2), place(3)], place(2), true)
    expect(marks.map((m) => [m.place.id, m.number, m.selected])).toEqual([[1, 1, false], [2, 2, true], [3, 3, false]])
  })

  it('열어 둔 장소가 핀에 없으면 번호 없는 강조 핀을 맨 뒤에 하나 더 둔다', () => {
    const marks = pinMarks([place(1), place(2)], place(424), true)
    expect(marks.map((m) => [m.place.id, m.number, m.selected])).toEqual([[1, 1, false], [2, 2, false], [424, null, true]])
  })

  it('고른 장소가 없으면 핀을 그대로 둔다', () => {
    const marks = pinMarks([place(1), place(2)], null, false)
    expect(marks.map((m) => [m.number, m.selected])).toEqual([[null, false], [null, false]])
  })

  it('핀이 하나도 없어도 열어 둔 장소는 꽂는다', () => {
    expect(pinMarks([], place(7), true)).toEqual([{ place: place(7), number: null, selected: true }])
  })
})

describe('pinsKey', () => {
  it('선택한 장소가 바뀌면 열쇠가 달라져 핀을 다시 그린다', () => {
    expect(pinsKey([1, 2], true, 2)).not.toBe(pinsKey([1, 2], true, null))
    expect(pinsKey([1, 2], true, 2)).not.toBe(pinsKey([1, 2], true, 1))
    expect(pinsKey([1, 2], true)).toBe(pinsKey([1, 2], true, null))
  })
})

describe('pinSvg', () => {
  it('선택 핀은 가운데 점으로 구분되고 색은 같으며 그라데이션 id 가 겹치지 않는다', () => {
    const plain = pinSvg(null)
    const chosen = pinSvg(null, true)
    expect(chosen).not.toBe(plain)
    expect(chosen).toContain('#7a69ed')
    expect(chosen).not.toContain('#f2483c')
    expect(chosen).toContain('id="pgns"')
    expect(plain).toContain('id="pgn"')
    expect(pinSvg(3, true)).toContain('id="pg3s"')
  })
})
