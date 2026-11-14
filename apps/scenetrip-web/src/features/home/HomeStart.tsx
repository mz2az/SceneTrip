/*
 * 다시 온 사람의 홈 — 정본: docs/project/plans/web-ad-landing.md §4 · §9-8.
 *
 *   담은 것이 없을 때  ShowHero(지금 뜨는 작품 1편의 장면 사진 + 「이 작품의 장소 N곳 보기」 → 랜딩)
 *                    → PickShows(「좋아하는 작품에서 시작하세요」 포스터 3열 6편 + 전체 보기, 칸 → 랜딩)
 *                    → (오늘의 성지) → CityChips(「도시로 찾기」 한 줄)
 *   담은 것이 있을 때  내 지도 카드 → AddMoreRow(「더 담아 볼까요?」 아직 안 담은 작품 포스터 → 작품 상세, 끝에 「주변 식당·쇼핑」 → /add)
 *
 * 「지금 뜨는 작품」 줄과 「어디로 여행 가세요?」 사진 카드는 이 칸들이 대신한다(같은 자료다).
 */
import { type JSX, useEffect, useId, useState } from 'react'
import { api, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { DeferredImg } from '@/design/DeferredImg'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { pickSceneThumb } from '@/features/proto/sceneFlip'
import { color, radius, shadow } from '@/features/proto/protoTokens'
import { Pressable } from '@/features/route/ui/Press'
import { say, useLang } from '@/i18n'
import { cityCount } from './homeCities'
import type { HomeCity } from './homeCityModel'
import '@/features/route/ui/icons'

type ContentSummary = components['schemas']['ContentSummary']

/** 작품 고르기 격자의 칸 수(3열 2줄). */
export const PICK_SHOWS = 6

// ---------------------------------------------------------------- 장면 카드(§9-8)

/** 작품 하나의 장면 사진 — 첫 촬영지의 상세에서 이 작품의 장면을 고른다. 한 번 받은 것은 기억한다. */
const heroScenes = new Map<number, string | null>()

function useShowScene(contentId: number): string | null {
  const [url, setUrl] = useState<string | null>(() => heroScenes.get(contentId) ?? null)
  useEffect(() => {
    if (heroScenes.has(contentId)) {
      setUrl(heroScenes.get(contentId) ?? null)
      return
    }
    let alive = true
    void (async () => {
      try {
        const list = await ok(api.GET('/contents/{contentId}/places', { params: { path: { contentId }, query: { limit: 3 } } }))
        let found: string | null = null
        for (const p of list.items.slice(0, 3)) {
          const detail = await ok(api.GET('/places/{placeId}', { params: { path: { placeId: p.id } } }))
          found = pickSceneThumb(detail, contentId)
          if (found) break
        }
        heroScenes.set(contentId, found)
        if (alive) setUrl(found)
      } catch {
        // 장면 사진이 없으면 포스터로 남는다.
      }
    })()
    return () => {
      alive = false
    }
  }, [contentId])
  return url
}

/** 처음 온 사람의 첫 칸 — 지금 뜨는 작품 1편의 장면 사진이 꽉 찬 카드 + 단추 하나(→ 랜딩). */
export function ShowHero({ work, onOpen }: { work: ContentSummary; onOpen: () => void }): JSX.Element {
  useLang()
  const scene = useShowScene(work.id)
  const photo = scene ?? work.posterUrl ?? null
  const n = work.placeCount
  return (
    <section aria-label={work.title} style={{ position: 'relative', margin: '0 20px', minHeight: 380, borderRadius: radius.l, overflow: 'hidden', background: color.brandFill, boxShadow: shadow.card }}>
      {photo && (
        <DeferredImg
          src={photo}
          alt=""
          referrerPolicy="no-referrer"
          draggable={false}
          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover', filter: scene ? undefined : 'blur(10px) saturate(1.1)', transform: scene ? undefined : 'scale(1.12)' }}
        />
      )}
      <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, rgba(0,0,0,0.35) 0%, rgba(0,0,0,0) 30%, rgba(0,0,0,0.1) 50%, rgba(0,0,0,0.78) 100%)' }} />
      <VStack spacing={10} align="stretch" style={{ position: 'relative', minHeight: 380, padding: '16px 18px 18px', justifyContent: 'space-between' }}>
        <span style={{ alignSelf: 'flex-start', display: 'inline-flex', alignItems: 'center', gap: 5, padding: '5px 10px', borderRadius: radius.pill, background: 'rgba(0,0,0,0.38)', backdropFilter: 'blur(8px)', WebkitBackdropFilter: 'blur(8px)' }}>
          <Icon name="film" size={12} color="#fff" />
          <Txt size={12} weight="bold" style={{ color: '#fff' }}>{say('가장 인기 있는 작품', 'Most popular show')}</Txt>
        </span>
        <VStack spacing={12} align="stretch">
          <VStack spacing={4} align="start">
            <Txt as="h2" size={28} weight="heavy" lines={2} style={{ margin: 0, color: '#fff', letterSpacing: -0.4, lineHeight: 1.1, textShadow: '0 2px 12px rgba(0,0,0,0.45)' }}>{work.title}</Txt>
            <Txt size={15} weight="semibold" style={{ color: 'rgba(255,255,255,0.92)', textShadow: '0 1px 6px rgba(0,0,0,0.5)' }}>{say('이 장면, 진짜 있는 곳이에요', 'This scene is a real place')}</Txt>
          </VStack>
          <Pressable
            plain
            className="proto-press"
            onClick={onOpen}
            style={{ width: '100%', minHeight: 52, borderRadius: 14, background: '#fff', display: 'grid', placeItems: 'center' }}
          >
            <Txt size={16} weight="bold" style={{ color: color.primaryText }}>
              {say(`이 작품의 장소 ${n}곳 보기`, `See ${n} ${n === 1 ? 'place' : 'places'} from this show`)}
            </Txt>
          </Pressable>
        </VStack>
      </VStack>
    </section>
  )
}

// ---------------------------------------------------------------- 작품 고르기(§4)

/** 포스터 칸 — 사진 위 왼쪽 아래 굵은 흰 글씨. 칸 전체가 단추. */
function PosterTile({ work, onPress, width }: { work: ContentSummary; onPress: () => void; width?: number }) {
  return (
    <Pressable
      plain
      className="proto-press"
      onClick={onPress}
      style={{ position: 'relative', width: width ?? '100%', aspectRatio: '3 / 4', flex: width ? 'none' : undefined, borderRadius: 14, overflow: 'hidden', background: color.brandFill, padding: 0, display: 'block', textAlign: 'left' }}
    >
      {work.posterUrl && <DeferredImg src={work.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />}
      <span style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, rgba(0,0,0,0) 45%, rgba(0,0,0,0.75))' }} />
      <span style={{ position: 'absolute', left: 8, right: 8, bottom: 7, display: 'flex', flexDirection: 'column', gap: 1 }}>
        <span style={{ fontSize: 13, fontWeight: 800, lineHeight: 1.15, color: '#fff', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{work.title}</span>
        <span style={{ fontSize: 11, fontWeight: 600, color: 'rgba(255,255,255,0.85)' }}>{say(`촬영지 ${work.placeCount}곳`, `${work.placeCount} ${work.placeCount === 1 ? 'spot' : 'spots'}`)}</span>
      </span>
    </Pressable>
  )
}

/** 「좋아하는 작품에서 시작하세요」 — 포스터 3열 6편 + 전체 보기. 칸을 누르면 그 작품의 랜딩. */
export function PickShows({ works, failed, onPick, onAll }: { works: readonly ContentSummary[]; failed: boolean; onPick: (w: ContentSummary) => void; onAll: () => void }): JSX.Element {
  useLang()
  const id = useId()
  return (
    <section aria-labelledby={id}>
      <VStack spacing={10} align="stretch">
        <HStack spacing={8} align="center" style={{ padding: '0 20px' }}>
          <Txt as="h2" id={id} size={19} weight="heavy" style={{ margin: 0 }}>{say('좋아하는 작품에서 시작하세요', 'Start with a show you love')}</Txt>
          <Spacer />
          <Pressable plain onClick={onAll} style={{ minHeight: 44, display: 'grid', alignItems: 'center', flex: 'none' }}>
            <Txt size={13} weight="semibold" color="accent">{say('전체 보기', 'See all')}</Txt>
          </Pressable>
        </HStack>
        {failed && works.length === 0 ? (
          <Txt size={13} color="secondary" style={{ padding: '0 20px' }}>{say('작품을 불러오지 못했어요', "Couldn't load shows")}</Txt>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, padding: '0 20px' }}>
            {works.slice(0, PICK_SHOWS).map((w) => (
              <PosterTile key={w.id} work={w} onPress={() => onPick(w)} />
            ))}
          </div>
        )}
      </VStack>
    </section>
  )
}

// ---------------------------------------------------------------- 도시로 찾기(§4)

/** 「도시로 찾기」 — 도시 칩 한 줄(지금 사진 카드의 칩만 남긴 것). */
export function CityChips({ cities, onPick }: { cities: readonly HomeCity[]; onPick: (c: HomeCity) => void }): JSX.Element | null {
  useLang()
  if (cities.length === 0) return null
  return (
    <section aria-label={say('도시로 찾기', 'Explore by city')}>
      <VStack spacing={8} align="stretch">
        <Txt as="h2" size={17} weight="heavy" style={{ margin: 0, padding: '0 20px' }}>{say('도시로 찾기', 'Explore by city')}</Txt>
        <div className="scroll-x" style={{ overflowX: 'auto', scrollbarWidth: 'none' }}>
          <HStack spacing={8} style={{ padding: '0 20px', width: 'max-content' }}>
            {cities.slice(0, 10).map((c) => (
              <Pressable key={c.key} plain className="proto-press" onClick={() => onPick(c)} style={{ flex: 'none', minHeight: 44, display: 'grid', alignItems: 'center' }}>
                <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 5, height: 36, padding: '0 14px', borderRadius: radius.pill, lineHeight: '36px', background: 'var(--system-background)', boxShadow: shadow.card }}>
                  <Txt size={14} weight="semibold">{c.name}</Txt>
                  <Txt size={12} color="secondary">{cityCount(c)}</Txt>
                </span>
              </Pressable>
            ))}
          </HStack>
        </div>
      </VStack>
    </section>
  )
}

// ---------------------------------------------------------------- 더 담기 줄(§4)

/** 「더 담아 볼까요?」 — 아직 안 담은 작품 포스터를 옆으로 넘기고, 끝에 「주변 식당·쇼핑」 칸(→ /add). */
export function AddMoreRow({ works, onOpen, onNearby }: { works: readonly ContentSummary[]; onOpen: (w: ContentSummary) => void; onNearby: () => void }): JSX.Element {
  useLang()
  const id = useId()
  const W = 112
  return (
    <section aria-labelledby={id}>
      <VStack spacing={10} align="stretch">
        <Txt as="h2" id={id} size={19} weight="heavy" style={{ margin: 0, padding: '0 20px' }}>{say('더 담아 볼까요?', 'Add more')}</Txt>
        <div className="scroll-x" style={{ overflowX: 'auto', scrollbarWidth: 'none' }}>
          <HStack spacing={8} align="stretch" style={{ padding: '0 20px', width: 'max-content' }}>
            {works.map((w) => (
              <PosterTile key={w.id} work={w} width={W} onPress={() => onOpen(w)} />
            ))}
            <Pressable
              plain
              className="proto-press"
              onClick={onNearby}
              style={{ width: W, aspectRatio: '3 / 4', flex: 'none', borderRadius: 14, background: 'color-mix(in srgb, var(--home-purple) 10%, var(--system-background))', border: `1.5px dashed ${color.primary}`, display: 'grid', placeItems: 'center', padding: 8 }}
            >
              <VStack spacing={8} align="center">
                <HStack spacing={4}>
                  <Icon name="fork.knife" size={18} color={color.primaryText} />
                  <Icon name="bag" size={18} color={color.primaryText} />
                </HStack>
                <Txt size={13} weight="bold" align="center" style={{ color: color.primaryText }}>{say('주변 식당·쇼핑', 'Food & shopping nearby')}</Txt>
              </VStack>
            </Pressable>
          </HStack>
        </div>
      </VStack>
    </section>
  )
}
