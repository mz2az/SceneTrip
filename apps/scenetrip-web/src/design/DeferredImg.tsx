/*
 * 보이지 않는 이미지를 받지 않는 <img> (MZ2AZ-558).
 *
 * 브라우저의 loading="lazy" 만으로는 두 경우를 못 막는다.
 *   1. 검색·홈 탭은 항상 마운트하고 `visibility:hidden` 으로 숨긴다(RootTabs Layer) — 숨긴 탭의 이미지도 받는다.
 *   2. 가로 줄(.scroll-x)의 화면 밖 포스터도 받는다.
 * 그래서 src 속성을 처음엔 달지 않고, 감싼 탭이 보이고(LayerVisibleContext) IntersectionObserver 가 화면 근처라고
 * 알릴 때 단다. 한 번 단 src 는 떼지 않는다. <img> 요소는 처음부터 그려 두므로 배치는 그대로다.
 */
import { type ImgHTMLAttributes, createContext, useContext, useEffect, useRef, useState } from 'react'

/** 이 아래 이미지를 담은 탭(Layer)이 지금 보이는가. Layer 밖(덮개 등)은 늘 보인다고 본다. */
export const LayerVisibleContext = createContext(true)

/**
 * 미리 받는 여유 — 세로로 200px(다음 화면 일부), 가로로 120px(가로 줄의 다음 카드 한 장 — 포스터 폭 108 + 간격).
 * `rootMargin` 은 화면(viewport) 경계에만 걸리고, 가로 줄(.scroll-x)·세로 목록(.scroll-y) 같은 스크롤 영역의 잘림에는
 * 걸리지 않는다. 그래서 같은 여유를 `scrollMargin`(스크롤 영역 쪽 여유)으로도 준다. 이 옵션을 모르는 브라우저는 무시하고,
 * 그때는 카드가 줄의 보이는 칸에 들어올 때 받는다.
 */
const MARGIN = '200px 120px'

export function shouldReveal({ layerVisible, intersecting, observerSupported, alreadyRevealed }: {
  layerVisible: boolean
  intersecting: boolean
  observerSupported: boolean
  alreadyRevealed: boolean
}): boolean {
  if (alreadyRevealed) return true
  if (!layerVisible) return false
  return observerSupported ? intersecting : true
}

const observerSupported = () => typeof globalThis.IntersectionObserver === 'function'

type Props = Omit<ImgHTMLAttributes<HTMLImageElement>, 'src'> & { src?: string | null }

export function DeferredImg({ src, ...rest }: Props) {
  const layerVisible = useContext(LayerVisibleContext)
  const ref = useRef<HTMLImageElement>(null)
  const [intersecting, setIntersecting] = useState(false)
  const [revealed, setRevealed] = useState(false)
  const supported = observerSupported()

  // 화면 근처인지 지켜본다. 드러난 뒤에는 더 볼 필요가 없어 끊는다.
  useEffect(() => {
    if (revealed || !supported || !src) return
    const el = ref.current
    if (!el) return
    const observer = new IntersectionObserver(
      (entries) => {
        for (const e of entries) if (e.target === el) setIntersecting(e.isIntersecting)
      },
      // scrollMargin 은 lib.dom 타입에 아직 없을 수 있어 넓혀서 넘긴다.
      { rootMargin: MARGIN, scrollMargin: MARGIN } as IntersectionObserverInit,
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [revealed, supported, src])

  const show = !!src && shouldReveal({ layerVisible, intersecting, observerSupported: supported, alreadyRevealed: revealed })
  useEffect(() => {
    if (show && !revealed) setRevealed(true)
  }, [show, revealed])

  return <img {...rest} ref={ref} loading="lazy" src={show ? src : undefined} />
}
