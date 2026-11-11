/*
 * 추천 코스 카드의 사진 칸 — 누르면 세로 축으로 돌아 뒷면을 보여 준다(MZ2AZ-547, 계획 motion-works-drawer-parity.md §6).
 * 정본: iOS `NewCourse/SceneFlipPhoto.swift`. 고르기·글은 `sceneFlip.ts`(flipFaces·flipPill·pickSceneImage·peekCardIndex), 여기는 장소 상세를 받아 기억하는 것과 그리기.
 *
 * - 모든 카드가 뒤집힌다. 앞면은 장소 사진(없으면 장면 그림), 뒷면은 남은 쪽이나 「…아직 없어요」 면(보라 바탕에 가운데 글).
 * - 왼쪽 아래 알약 — 앞면은 「장면 보기」(장면 그림이 앞면이면 「「작품」 속 장면」), 뒷면은 늘 앞면으로 돌아가는 단추.
 * - 0.45초 돌기, 동작 줄이기면 0.2초 서서히. 화면이 처음 뜰 때 첫 카드가 0.6초 뒤 한 번 살짝(25°) 돌았다 돌아온다(한 번 뒤집은 적이 있으면 안 한다).
 */
import { useEffect, useState } from 'react'
import type { components } from '@/api/generated/schema'
import { api, ok } from '@/api/client'
import { Icon } from '@/design/Icon'
import { Txt } from '@/design/primitives'
import { createStore, readJSON, useStore, writeJSON } from '@/state/storage'
import { Pressable } from '@/features/route/ui/Press'
import { say } from './protoState'
import { color, radius } from './protoTokens'
import { type FlipPill, flipFaces, flipPill, pickSceneImage } from './sceneFlip'

type PlaceSummary = components['schemas']['PlaceSummary']

/** 뒤집기 시간(초) — iOS `SceneFlipRules.flipDuration`. */
const FLIP_SECONDS = 0.45
/** 동작 줄이기면 도는 대신 서서히(초). */
const FADE_SECONDS = 0.2
/** 처음 한 번 살짝 돌기 — 화면이 뜬 뒤 기다림(ms)과 각도. */
const PEEK_DELAY_MS = 600
const PEEK_ANGLE = 25
/** 장소 상세를 동시에 받는 수. */
const MAX_FETCHES = 4
/** 사용자가 한 번이라도 뒤집었나 — 그 뒤로는 처음 한 번 살짝 돌기를 하지 않는다. */
const FLIPPED_KEY = 'proto.sceneFlip.seen'

// ---------------------------------------------------------------- 장면 그림 — 장소마다 `GET /places/{placeId}` 한 번, 앱이 기억한다

/** 장소 번호 → 뒷면 장면 그림. 받았는데 그림이 없으면 null, 아직 안 받았으면 키가 없다. */
const sceneStore = createStore<Record<number, string | null>>({})
const asked = new Set<number>()
const waiting: PlaceSummary[] = []
let running = 0

function pump(): void {
  while (running < MAX_FETCHES && waiting.length > 0) {
    const place = waiting.shift()!
    running += 1
    void ok(api.GET('/places/{placeId}', { params: { path: { placeId: place.id } } }))
      .then((detail) => pickSceneImage(detail, place.contents?.[0]?.contentId))
      .catch(() => null)
      .then((url) => {
        sceneStore.set((s) => ({ ...s, [place.id]: url }))
        running -= 1
        pump()
      })
  }
}

/** 카드가 보였다 — 처음이면 받을 차례에 넣는다. */
function loadScene(place: PlaceSummary): void {
  if (asked.has(place.id)) return
  asked.add(place.id)
  waiting.push(place)
  pump()
}

export const hasFlippedBefore = (): boolean => readJSON<boolean>(FLIPPED_KEY, false)

export const prefersReducedMotion = (): boolean => typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true

// ---------------------------------------------------------------- 사진 칸

/** 카드 사진 칸(16:9 칸을 꽉 채운다). `peek` 이면 처음 한 번 살짝 돈다. 카드가 다른 곳으로 바뀌면 부르는 쪽이 `key` 로 앞면부터 다시 그린다. */
export function SceneFlipPhoto({ place, peek }: { place: PlaceSummary; peek: boolean }) {
  const scene = useStore(sceneStore, (s) => s[place.id] ?? null)
  const faces = flipFaces(place.imageUrl, scene)
  const [flipped, setFlipped] = useState(false)
  const [peekAngle, setPeekAngle] = useState(0)
  const reduced = prefersReducedMotion()

  useEffect(() => loadScene(place), [place])

  // 처음 한 번 살짝 돌았다 돌아온다 — 뒤집힌다는 것을 알려 준다.
  useEffect(() => {
    if (!peek || reduced) return
    const out = window.setTimeout(() => setPeekAngle(PEEK_ANGLE), PEEK_DELAY_MS)
    const back = window.setTimeout(() => setPeekAngle(0), PEEK_DELAY_MS + 320)
    return () => {
      window.clearTimeout(out)
      window.clearTimeout(back)
    }
  }, [peek, reduced])

  const toggle = () => {
    writeJSON(FLIPPED_KEY, true)
    setPeekAngle(0)
    setFlipped((f) => !f)
  }

  const work = place.contents?.[0]?.title?.trim() || null
  const angle = (flipped ? 180 : 0) + peekAngle
  const face = (back: boolean): React.CSSProperties => ({
    position: 'absolute', inset: 0, backfaceVisibility: 'hidden', WebkitBackfaceVisibility: 'hidden',
    ...(reduced ? { opacity: back === flipped ? 1 : 0, transition: `opacity ${FADE_SECONDS}s ease-in-out` } : back ? { transform: 'rotateY(180deg)' } : {}),
  })

  return (
    <Pressable
      plain
      onClick={toggle}
      aria-label={flipped ? say('장소 사진 보기', 'View place photo') : say('장면 그림 보기', 'View scene art')}
      style={{ position: 'absolute', inset: 0, display: 'block', perspective: 900 }}
    >
      <div
        style={{
          position: 'absolute', inset: 0, transformStyle: 'preserve-3d',
          transform: reduced ? undefined : `rotateY(${angle}deg)`,
          transition: reduced ? undefined : `transform ${FLIP_SECONDS}s ease-in-out`,
        }}
      >
        <div style={face(false)} aria-hidden>
          {faces.front ? <Photo url={faces.front} /> : <Missing text={say('장소 사진이 아직 없어요', 'No place photo yet')} />}
          <Pill kind={flipPill(faces, false)} work={work} />
        </div>
        <div style={face(true)} aria-hidden>
          {faces.back.kind === 'scene' ? (
            <Photo url={faces.back.url} />
          ) : faces.back.kind === 'noPhoto' ? (
            <Missing text={say('장소 사진이 아직 없어요', 'No place photo yet')} />
          ) : (
            <Missing text={say('장면 그림이 아직 없어요', 'No scene art yet')} />
          )}
          <Pill kind={flipPill(faces, true)} work={work} />
        </div>
      </div>
    </Pressable>
  )
}

function Photo({ url }: { url: string }) {
  return <img src={url} alt="" loading="lazy" draggable={false} referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />
}

/** 「없음」 면 — 보라 바탕에 가운데 글. */
function Missing({ text }: { text: string }) {
  return (
    <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', background: color.brandFill }}>
      <Txt size={15} weight="semibold" style={{ color: color.onPhoto }}>{text}</Txt>
    </div>
  )
}

/** 사진 칸 왼쪽 아래 알약. 각 면에 붙어 함께 돌므로 돌고 난 뒤에도 왼쪽 아래에 바로 읽힌다. */
function Pill({ kind, work }: { kind: FlipPill; work: string | null }) {
  const text =
    kind === 'scene' ? say('장면 보기', 'View scene')
      : kind === 'photo' ? say('장소 사진 보기', 'View place photo')
        : work ? say(`「${work}」 속 장면`, `Scene from “${work}”`) : say('장면 그림', 'Scene art')
  return (
    <span
      style={{
        position: 'absolute', left: 10, bottom: 10, maxWidth: 'calc(100% - 20px)', display: 'inline-flex', alignItems: 'center', gap: 5,
        padding: '5px 9px', borderRadius: radius.pill, background: 'rgba(0,0,0,0.55)', color: '#fff',
      }}
    >
      <Icon name={kind === 'work' ? 'photo' : 'arrow.clockwise'} size={11} weight="bold" color="#fff" />
      <Txt size={12} weight="semibold" lines={1} style={{ color: '#fff' }}>{text}</Txt>
    </span>
  )
}
