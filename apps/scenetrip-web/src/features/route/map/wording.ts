/*
 * 지도 핀 아래 이름표 — iOS Models/Wording.swift `PinCaption`.
 * 영어 이름은 길다(「Korea National University of Arts Seokgwan-dong Campus …」 80자가 넘는 것이 옆 핀과 지도를 덮었다).
 * 이름표는 어느 핀인지 알아보는 데 쓰는 것이라 앞머리면 된다 — 전체 이름은 눌러 연 카드에 있다.
 */
export const PinCaption = {
  /** 이보다 길면 자른다. */
  limit: 28,
  /** 이름표의 폭(px). 넘으면 줄을 바꾼다. */
  width: 132,
  text(name: string): string {
    const chars = [...name]
    return chars.length <= PinCaption.limit ? name : `${chars.slice(0, PinCaption.limit - 1).join('').trimEnd()}…`
  },
}
