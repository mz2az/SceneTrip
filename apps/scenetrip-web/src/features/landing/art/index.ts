/*
 * 첫 화면 해태 그림(작은 WebP) — 덮개 덩어리에 글자로 실려 온다(?inline, web-ad-landing.md §12).
 * 한 곳에서 한 번만 불러 객체로 내보낸다 — 여러 파일이 각자 `?inline` 을 부르면 묶을 때 같은 그림이 쓰는 자리마다 복사된다.
 */
import haetaeJoy from './haetae-joy.webp?inline'
import haetaePeek from './haetae-peek.webp?inline'
import haetaePin from './haetae-pinhold.webp?inline'
import haetaeSit from './haetae-sit.webp?inline'
import pawLeft from './paw-left.webp?inline'
import pawRight from './paw-right.webp?inline'

export const landingArt: Readonly<{ joy: string; peek: string; pin: string; sit: string; pawLeft: string; pawRight: string }> = {
  joy: haetaeJoy,
  peek: haetaePeek,
  pin: haetaePin,
  sit: haetaeSit,
  pawLeft,
  pawRight,
}
