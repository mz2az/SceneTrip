import { type CSSProperties, useState } from 'react'
import { Icon } from '@/design/Icon'

/**
 * 서버가 준 URL 로 이미지를 그린다. 실패하면 자리표시자로 떨어진다 — iOS SearchTab/RemoteImage.swift.
 * 이미지 주소는 우리 것이 아니다(작품 제공 플랫폼 포스터, 구글/위키미디어 장소 사진 — 수집 시점의 외부 URL). 그래서 깨지는 것을 정상 경로로 다룬다.
 * 링크가 죽었거나 오프라인이면 자리표시자(회색 + 기호)가 남고 목록 자체는 그대로 읽힌다. 프레임 안에서 채우고 자른다(scaledToFill + clipped).
 */
export function RemoteImage({ url, symbol, size, radius = 0, style, fit = 'cover' }: {
  url: string | null | undefined
  symbol: string
  size?: { w: number; h: number }
  radius?: number
  style?: CSSProperties
  fit?: 'cover' | 'contain'
}) {
  const [failed, setFailed] = useState(false)
  const box: CSSProperties = { width: size?.w, height: size?.h, borderRadius: radius, overflow: 'hidden', flex: 'none', position: 'relative', background: 'var(--system-gray5)', ...style }
  if (!url || failed) {
    return (
      <div style={{ ...box, display: 'grid', placeItems: 'center' }} aria-hidden>
        <Icon name={symbol} size={18} color="secondary" />
      </div>
    )
  }
  return (
    <div style={box} aria-hidden>
      <img src={url} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setFailed(true)} style={{ width: '100%', height: '100%', objectFit: fit }} draggable={false} />
    </div>
  )
}
