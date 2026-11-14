/**
 * 첫 화면 덮개가 걷히는 까닭(web-ad-landing.md §11).
 *   skip    ①·② 의 「건너뛰기」          browse  ③ 의 「먼저 둘러볼게요」
 *   kept    ③ 에서 담기를 끝냄(→ /add)    myMap   ③ 머리줄의 「내 지도」(→ /my-map)
 *   notNew  처음 온 사람이 아니다(담은 것·코스가 있다)
 *   failed  작품·촬영지를 받지 못했다, 촬영지가 0곳, 목록에서 빠진 작품
 */
export type IntroClose = 'skip' | 'browse' | 'kept' | 'myMap' | 'notNew' | 'failed'
