/*
 * 첫 화면을 받는 동안의 보라 바탕 + 해태 + SceneTrip(web-ad-landing.md §10-3-1).
 * index.html 의 `#root` 안에 같은 모양(같은 클래스·같은 그림)이 HTML 로 먼저 그려져 있다 — JS 를 받기 전에 보이고,
 * React 가 그리면 이것으로 바뀐다(모양이 같아 깜박이지 않는다). 모양은 index.html 의 <style> 이 정한다.
 */
import type { JSX } from 'react'
import haetae from './intro-haetae.webp?inline'

export const INTRO_HAETAE_SRC: string = haetae

export function IntroSplash(): JSX.Element {
  return (
    <div className="st-splash st-landing st-landing--loading" aria-busy="true">
      <img src={haetae} alt="" width={72} height={62} draggable={false} />
      <span>SceneTrip</span>
    </div>
  )
}
