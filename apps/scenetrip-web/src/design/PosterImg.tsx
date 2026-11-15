/*
 * 작품 포스터를 카드 상자에 채우는 겹 — 정본: docs/project/web-app.md §15 (MZ2AZ-582).
 *
 * 포스터 원본이 가로 배너인 작품이 많다(DEV 128편 중 37편). 세로 카드에 cover 로 넣으면 가운데만 남아 제목·인물이 잘린다.
 * 그래서 그림을 받은 뒤 비율을 보고, 상자보다 1.3배 넘게 넓으면 그림 전체를 보이고(contain) 뒤를 같은 그림을 흐리게 깔아 채운다.
 * 그 밖에는 cover. 앞 그림은 DeferredImg(보이지 않으면 받지 않는다), 뒤 그림은 앞 그림을 받은 뒤에만 단다(같은 주소).
 */
import { type CSSProperties, type JSX, useState } from 'react'
import { DeferredImg } from './DeferredImg'

/** 그림이 상자보다 이만큼 넘게 넓으면 자르지 않는다. */
const WIDE_RATIO = 1.3

const fill: CSSProperties = { position: 'absolute', inset: 0, width: '100%', height: '100%' }

export function PosterImg({ src, position = 'center' }: { src: string | null | undefined; position?: string }): JSX.Element | null {
  const [wide, setWide] = useState(false)
  if (!src) return null
  return (
    <span data-poster-fit={wide ? 'wide' : 'cover'} style={{ ...fill, display: 'block', overflow: 'hidden' }}>
      {wide && (
        <img
          src={src}
          alt=""
          aria-hidden="true"
          referrerPolicy="no-referrer"
          draggable={false}
          style={{ ...fill, objectFit: 'cover', filter: 'blur(14px) brightness(0.6)', transform: 'scale(1.2)' }}
        />
      )}
      <DeferredImg
        src={src}
        alt=""
        referrerPolicy="no-referrer"
        draggable={false}
        onLoad={(e) => {
          const img = e.currentTarget
          if (!img.naturalWidth || !img.naturalHeight || !img.clientWidth || !img.clientHeight) return
          setWide(img.naturalWidth / img.naturalHeight > (img.clientWidth / img.clientHeight) * WIDE_RATIO)
        }}
        style={{ ...fill, objectFit: wide ? 'contain' : 'cover', objectPosition: wide ? 'center 38%' : position }}
      />
    </span>
  )
}
