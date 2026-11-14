/*
 * 「더 담기」 — 덮개 `/add`. 정본: docs/project/plans/web-ad-landing.md §2 · §3 · §7 · §9-3 · §9-4, keep-poi.md §9-2.
 *
 * 담은 뒤 시트 ① 을 대신한다. 작품 상세의 「모두 담기」 뒤, 랜딩의 담기 뒤, 내 지도의 「더 담기」 가 모두 여기로 온다.
 *
 *   머리줄  뒤로 · 「내 지도 · N곳」(N = 촬영지 + 편의시설) · 오른쪽 「내 지도」 단추(배지), 그 아래 「더 담아 볼까요?」
 *   (앱 안 브라우저면 띠 — InAppBand)
 *   다른 작품의 촬영지  모자이크(Visit Japan 「Trending」): 큰 가로 카드 1장 + 정사각 2열, 최대 9편(moreWorks).
 *                       칸을 누르면(원형 ＋) 그 작품 촬영지를 받아 addMany(…, 'more') + keep_more, 칸에 「✓ +N」.
 *   주변 쇼핑 · 명소 · 식당·카페  담은 곳 둘레(nearbyBbox)에서 GET /pois(sort=spread), 줄마다 옆으로 넘기는 정사각 칸 최대 12곳 +
 *                       끝의 「지도에서 더 보기」(그 칩이 켜진 내 지도). 쇼핑·명소는 사진 있는 곳만, 식당·카페는 분류 아이콘 칸.
 *                       칸을 누르면 담기(addPoi) — 칸에 ✓. 다시 눌러도 빼지 않는다 — 빼기는 내 지도에서(§10-3-12).
 *   아래 고정  「이 N곳으로 여행 일정 짜기」 → 간직하기(②, 설치된 환경이면 ③) · 「내 지도 보기」 → /my-map
 *
 * 칸은 사진 위 왼쪽 아래 굵은 흰 글씨 + 아래쪽 어두운 그라데이션, 오른쪽 위 원형 ＋(담기면 ✓). 칸 하나가 단추이고 그 이름이
 * 「{이름} 담기」 / 「Add {이름}」 이다(담겨 있으면 aria-pressed). 담는 순간 칸이 「내 지도」 단추로 날아간다(flyToMyMap).
 */
import { type JSX, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { track, webEvent } from '@/analytics'
import { api, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { paths } from '@/app/routes'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { homeStore, reloadHome } from '@/features/home/homeModel'
import { color, contained } from '@/features/proto/protoTokens'
import { poiGlyph, poiGroupOf, poiTone } from '@/features/route/poi/poiGroup'
import { Pressable } from '@/features/route/ui/Press'
import { SceneData } from '@/features/search/sceneData'
import { say, useLang } from '@/i18n'
import { spots } from '@/shared/spots'
import { cartActions, cartPoiBusyStore, useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import '@/features/route/ui/icons'
import { MyMapButton, flyToMyMap, liveMyMapCount } from './flyToMyMap'
import { InAppBand, keepContentIds } from './InAppBand'
import { currentKeepEnv } from './installPrompt'
import { type NearbyKind, nearbyBbox, nearbyQuery, savedPoiName } from './keepPoiRules'
import { moreWorks } from './keepRules'
import { keepActions, useKeep } from './keepStore'
import './keep.css'

type ContentSummary = components['schemas']['ContentSummary']
type PoiSummary = components['schemas']['PoiSummary']

/** 다른 작품 칸의 수. */
const WORKS_MAX = 9
/** 주변 줄마다 칸 수. */
const ROW_MAX = 12
/** `sort=spread` 의 칸 크기(지도 확대 단계) — 담은 곳 둘레 정도의 넓이. */
const SPREAD_ZOOM = 14
/** 줄의 정사각 칸 한 변. */
const SQUARE = 148

/** 줄 순서 — 쇼핑 → 명소 → 식당·카페(§2). */
const ROWS: readonly { kind: Exclude<NearbyKind, 'stay'>; photos: boolean }[] = [
  { kind: 'shopping', photos: true },
  { kind: 'sight', photos: true },
  { kind: 'food', photos: false },
]

const rowTitle = (kind: NearbyKind): string =>
  kind === 'shopping' ? say('주변 쇼핑', 'Shopping nearby') : kind === 'sight' ? say('주변 명소', 'Sights nearby') : say('주변 식당·카페', 'Food & cafés nearby')

type WorkTile = { kind: 'loading' } | { kind: 'done'; count: number } | { kind: 'failed' }
type Row = { phase: 'loading' | 'loaded' | 'failed'; items: PoiSummary[] }

export function AddMoreCover({ onClose }: { onClose: () => void }): JSX.Element {
  useLang()
  const navigate = useNavigate()
  const cart = useCart()
  const { contentIds } = useKeep()
  const count = Math.max(0, liveMyMapCount(cart))

  // 처음 뜰 때 한 번 — add_more(view). 장바구니를 아직 받은 적이 없으면 받는다(주소로 바로 들어온 사람).
  const viewed = useRef(false)
  useEffect(() => {
    if (!viewed.current) {
      viewed.current = true
      track(webEvent.addMore('view'))
    }
    if (cart.loadState === 'notLoaded') void cartActions.refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 열릴 때 한 번.
  }, [])

  const openMap = () => navigate(paths.myMap)
  const plan = () => {
    track(webEvent.addMore('plan'))
    keepActions.startPlan(currentKeepEnv(), keepContentIds(contentIds, cart.items))
  }

  return (
    <VStack spacing={0} align="stretch" style={{ height: '100%', minHeight: 0, background: 'var(--grouped-background)', paddingTop: 'var(--safe-top)' }}>
      <HStack spacing={4} style={{ padding: '4px 12px 0 8px', minHeight: 52, flex: 'none' }}>
        <Pressable plain onClick={onClose} aria-label={say('뒤로', 'Back')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', flex: 'none' }}>
          <Icon name="chevron.left" size={20} weight="semibold" />
        </Pressable>
        <Txt as="h1" size={19} weight="bold" lines={1} style={{ margin: 0, flex: 1, minWidth: 0 }}>
          {say(`마이맵 · ${count}곳`, `My map · ${spots(count)}`)}
        </Txt>
        <MyMapButton onPress={openMap} />
      </HStack>
      <InAppBand />

      <div className="scroll-y" style={{ flex: 1, minHeight: 0, ...contained }}>
        <VStack spacing={26} align="stretch" style={{ padding: '6px 0 28px' }}>
          <Txt as="h2" size={26} weight="heavy" style={{ margin: 0, padding: '0 20px', letterSpacing: -0.4 }}>
            {say('더 담아 볼까요?', 'Add more to My map')}
          </Txt>
          <OtherWorks />
          <NearbyRows />
        </VStack>
      </div>

      <div style={{ flex: 'none', padding: '12px 20px calc(10px + var(--safe-bottom))', background: 'var(--system-background)', borderTop: '0.5px solid var(--separator)' }}>
        <VStack spacing={4} align="stretch">
          <Pressable plain className="proto-press" onClick={plan} style={{ width: '100%', minHeight: 54, borderRadius: 16, background: color.primaryFill, display: 'grid', placeItems: 'center' }}>
            <HStack spacing={8}>
              <Icon name="paperplane.fill" size={16} color="#fff" />
              <Txt size={17} weight="bold" style={{ color: color.onPrimary }}>{say(`이 ${count}곳으로 여행 일정 짜기`, `Plan my trip with ${spots(count)}`)}</Txt>
            </HStack>
          </Pressable>
          <Pressable plain onClick={openMap} style={{ width: '100%', minHeight: 44, display: 'grid', placeItems: 'center' }}>
            <Txt size={15} weight="semibold" style={{ color: color.primaryText }}>{say('마이맵 보기', 'See My map')}</Txt>
          </Pressable>
        </VStack>
      </div>
    </VStack>
  )
}

// ---------------------------------------------------------------- 다른 작품

function OtherWorks() {
  const cart = useCart()
  const works = useStore(homeStore, (s) => s.works)
  const failed = useStore(homeStore, (s) => s.failed)
  const [tiles, setTiles] = useState<Record<number, WorkTile>>({})
  const scene = useRef<SceneData | null>(null)
  const [reloading, setReloading] = useState(false)

  // 홈을 거치지 않고 들어온 사람은 목록이 비어 있다 — 한 번 받아 온다.
  const asked = useRef(false)
  const reload = useCallback(() => {
    setReloading(true)
    void reloadHome()
      .catch(() => {})
      .finally(() => setReloading(false))
  }, [])
  useEffect(() => {
    if (asked.current || homeStore.get().works.length > 0) return
    asked.current = true
    reload()
  }, [reload])

  // 이미 담은 작품은 뺀다 — 여기서 고른 작품은 담긴 뒤에도 칸에 남아 「✓ +N」 을 보인다.
  const saved = new Set(cart.items.map((i) => i.sourceContentId).filter((id): id is number => typeof id === 'number'))
  const exclude = new Set([...saved].filter((id) => tiles[id] === undefined))
  const list = moreWorks(works, exclude, WORKS_MAX)

  const pick = async (w: ContentSummary, el: HTMLElement) => {
    const now = tiles[w.id]
    if (now?.kind === 'loading' || now?.kind === 'done') return
    setTiles((t) => ({ ...t, [w.id]: { kind: 'loading' } }))
    try {
      scene.current ??= new SceneData()
      const placeIds = (await scene.current.placesOfContent(w.id)).map((p) => p.id)
      if (placeIds.length === 0) throw new Error('no places')
      const result = await cartActions.addMany(placeIds, w.id, undefined, 'more')
      const n = result.saved + result.already
      if (n === 0) throw new Error('nothing saved')
      // 날아가기는 저장 결과를 받은 뒤(§10-3-9).
      flyToMyMap(el, { imageUrl: w.posterUrl ?? null })
      keepActions.addContent(w.id)
      track(webEvent.keepMore(w.id))
      setTiles((t) => ({ ...t, [w.id]: { kind: 'done', count: n } }))
    } catch {
      setTiles((t) => ({ ...t, [w.id]: { kind: 'failed' } }))
    }
  }

  const loading = works.length === 0 && (reloading || !failed)
  if (works.length === 0 && failed && !reloading) {
    return (
      <Section title={say('다른 작품의 촬영지', 'Spots from other shows')}>
        <LoadFailed onRetry={reload} />
      </Section>
    )
  }
  if (loading) {
    return (
      <Section title={say('다른 작품의 촬영지', 'Spots from other shows')}>
        <div style={{ padding: '0 20px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }} aria-busy="true">
          <div style={{ gridColumn: '1 / -1', aspectRatio: '16 / 9', borderRadius: 16, background: 'var(--system-gray5)' }} />
          <div style={{ aspectRatio: '1', borderRadius: 14, background: 'var(--system-gray5)' }} />
          <div style={{ aspectRatio: '1', borderRadius: 14, background: 'var(--system-gray5)' }} />
        </div>
      </Section>
    )
  }
  if (list.length === 0) return null

  return (
    <Section title={say('다른 작품의 촬영지', 'Spots from other shows')}>
      <div style={{ padding: '0 20px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
        {list.map((w, i) => {
          const state = tiles[w.id]
          const big = i === 0
          return (
            <Tile
              key={w.id}
              big={big}
              name={w.title}
              sub={say(`촬영지 ${w.placeCount}곳`, spots(w.placeCount))}
              imageUrl={w.posterUrl ?? null}
              pressed={state?.kind === 'done'}
              busy={state?.kind === 'loading'}
              badge={state?.kind === 'done' ? `✓ +${state.count}` : null}
              note={state?.kind === 'failed' ? say('담지 못했어요', "Couldn't save") : null}
              onPress={(el) => void pick(w, el)}
            />
          )
        })}
      </div>
    </Section>
  )
}

// ---------------------------------------------------------------- 주변 세 줄

function NearbyRows() {
  const cart = useCart()
  const navigate = useNavigate()
  const points = useMemo(
    () => [
      ...cart.items
        .filter((i) => typeof i.latitude === 'number' && typeof i.longitude === 'number' && i.delisted !== true)
        .map((i) => ({ latitude: i.latitude!, longitude: i.longitude! })),
      ...cart.pois.filter((p) => Number.isFinite(p.latitude) && Number.isFinite(p.longitude)).map((p) => ({ latitude: p.latitude, longitude: p.longitude })),
    ],
    [cart.items, cart.pois],
  )
  // 둘레는 처음 정한 것으로 고정한다 — 여기서 담을 때마다 줄이 다시 받히면 칸이 바뀌어 버린다.
  const [bbox, setBbox] = useState<string | null>(null)
  useEffect(() => {
    if (bbox === null) setBbox(nearbyBbox(points))
  }, [bbox, points])

  const [rows, setRows] = useState<Partial<Record<NearbyKind, Row>>>({})
  const load = useCallback(
    (kind: NearbyKind, photos: boolean, box: string) => {
      setRows((r) => ({ ...r, [kind]: { phase: 'loading', items: r[kind]?.items ?? [] } }))
      const query = { bbox: box, sort: 'spread' as const, spreadZoom: SPREAD_ZOOM, limit: ROW_MAX, ...nearbyQuery(kind), ...(photos ? { hasImage: true } : {}) }
      ok(api.GET('/pois', { params: { query } }))
        .then((list) => setRows((r) => ({ ...r, [kind]: { phase: 'loaded', items: list.items.slice(0, ROW_MAX) } })))
        .catch(() => setRows((r) => ({ ...r, [kind]: { phase: 'failed', items: [] } })))
    },
    [],
  )
  useEffect(() => {
    if (!bbox) return
    for (const row of ROWS) load(row.kind, row.photos, bbox)
  }, [bbox, load])

  const more = (kind: NearbyKind) => {
    keepActions.setNearby(kind)
    navigate(paths.myMap)
  }

  if (!bbox) return null
  return (
    <>
      {ROWS.map(({ kind, photos }) => {
        const row = rows[kind]
        if (!row) return null
        if (row.phase === 'loaded' && row.items.length === 0) return null
        // 받는 중 — 제목 자리와 칸 자리를 회색으로(§7). 줄이 비면 제목째 빠지므로 제목은 받은 뒤에 쓴다.
        if (row.phase === 'loading' && row.items.length === 0) {
          return (
            <div key={kind} aria-busy="true" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ margin: '0 20px', width: 130, height: 20, borderRadius: 6, background: 'var(--system-gray5)' }} />
              <div style={{ display: 'flex', gap: 10, padding: '0 20px', overflow: 'hidden' }}>
                {[0, 1, 2].map((i) => <div key={i} style={{ width: SQUARE, height: SQUARE, flex: 'none', borderRadius: 14, background: 'var(--system-gray5)' }} />)}
              </div>
            </div>
          )
        }
        return (
          <Section key={kind} title={rowTitle(kind)}>
            {row.phase === 'failed' ? (
              <LoadFailed onRetry={() => load(kind, photos, bbox)} />
            ) : (
              <div className="scroll-x" style={{ overflowX: 'auto', scrollbarWidth: 'none', ...contained }} data-sheet-nodrag>
                <div style={{ display: 'flex', gap: 10, padding: '0 20px', width: 'max-content' }}>
                  {row.items.map((p) => <PoiTile key={p.id} poi={p} shopping={kind === 'shopping'} />)}
                  {row.phase === 'loaded' && (
                    <Pressable plain className="proto-press" onClick={() => more(kind)} style={{ width: 112, height: SQUARE, borderRadius: 14, border: `1.5px dashed ${color.primary}`, background: 'var(--system-background)', display: 'grid', placeItems: 'center', flex: 'none' }}>
                      <VStack spacing={8} align="center">
                        <Icon name="map" size={22} color={color.primaryText} />
                        <Txt size={13} weight="bold" align="center" style={{ color: color.primaryText, padding: '0 8px' }}>{say('지도에서 더 보기', 'More on the map')}</Txt>
                      </VStack>
                    </Pressable>
                  )}
                </div>
              </div>
            )}
          </Section>
        )
      })}
    </>
  )
}

function PoiTile({ poi, shopping = false }: { poi: PoiSummary; shopping?: boolean }) {
  const lang = useLang()
  const cart = useCart()
  const busy = useStore(cartPoiBusyStore, (s) => s.has(poi.id))
  const linked = typeof poi.placeId === 'number' ? poi.placeId : null
  const added = cart.poiIds.has(poi.id) || (linked !== null && cart.placeIds.has(linked))
  const { title, roman } = savedPoiName(poi, lang === 'ko')
  const group = poiGroupOf(poi.categoryGroup, poi.category)

  // 편의시설 칸은 담기만 한다(§10-3-12) — 담긴 칸(촬영지와 같은 곳이면 그 촬영지)을 다시 눌러도 빼지 않는다. 빼기는 내 지도에서.
  // 날아가기는 저장 결과를 받은 뒤, 담겼을 때만(§10-3-9).
  const toggle = (el: HTMLElement) => {
    if (busy || added) return
    void cartActions.addPoi({ id: poi.id, placeId: linked }).then((result) => {
      if (result === 'failed') return
      flyToMyMap(el, { imageUrl: poi.imageUrl ?? null })
      track(webEvent.addMore('poi'))
    })
  }

  return (
    <Tile
      name={title}
      roman={roman}
      sub={poi.categoryLabel ?? poi.category}
      imageUrl={poi.imageUrl ?? null}
      glyph={poi.imageUrl ? null : shopping ? 'bag' : poiGlyph(group, poi.category)}
      tone={poiTone(group)}
      pressed={added}
      busy={busy}
      size={SQUARE}
      onPress={toggle}
    />
  )
}

// ---------------------------------------------------------------- 부품

function Section({ title, children }: { title: string; children: JSX.Element | JSX.Element[] }) {
  return (
    <section aria-label={title}>
      <VStack spacing={10} align="stretch">
        <Txt as="h3" size={18} weight="heavy" style={{ margin: 0, padding: '0 20px' }}>{title}</Txt>
        {children}
      </VStack>
    </section>
  )
}

function LoadFailed({ onRetry }: { onRetry: () => void }) {
  return (
    <HStack spacing={10} style={{ margin: '0 20px', padding: '10px 10px 10px 14px', borderRadius: 14, background: 'var(--system-background)' }}>
      <Txt size={14} weight="semibold" color="secondary" style={{ flex: 1 }}>{say('불러오지 못했어요', "Couldn't load")}</Txt>
      <Pressable plain onClick={onRetry} style={{ minHeight: 44, padding: '0 12px', display: 'grid', placeItems: 'center' }}>
        <Txt size={14} weight="bold" style={{ color: color.primaryText }}>{say('다시 시도', 'Try again')}</Txt>
      </Pressable>
    </HStack>
  )
}

/**
 * 모자이크 칸 — 사진(없으면 분류 아이콘) 위 왼쪽 아래 굵은 흰 글씨, 아래쪽 어둡게, 오른쪽 위 원형 ＋(담기면 ✓).
 * 칸 전체가 단추이고 이름은 「{name} 담기」 / 「Add {name}」.
 */
function Tile({ name, roman, sub, imageUrl, glyph, tone, pressed, busy, badge, note, big, size, onPress }: {
  name: string
  roman?: string | null
  sub?: string | null
  imageUrl: string | null
  glyph?: string | null
  tone?: string
  pressed: boolean
  busy?: boolean
  badge?: string | null
  note?: string | null
  big?: boolean
  /** 줄의 정사각 칸이면 한 변(px). 없으면 격자 칸(1:1, 큰 칸은 16:9). */
  size?: number
  onPress: (el: HTMLElement) => void
}) {
  const box = size !== undefined ? { width: size, height: size, flex: 'none' as const } : { aspectRatio: big ? '16 / 9' : '1', gridColumn: big ? '1 / -1' : undefined }
  const background = imageUrl || !tone ? undefined : `linear-gradient(140deg, color-mix(in srgb, ${tone} 70%, #fff), ${tone})`
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, ...(size !== undefined ? { flex: 'none' } : { gridColumn: big ? '1 / -1' : undefined }) }}>
      <Pressable
        plain
        className="st-tile proto-press"
        onClick={(e) => onPress(e.currentTarget)}
        aria-label={say(`${name} 담기`, `Add ${name}`)}
        aria-pressed={pressed}
        aria-busy={busy || undefined}
        style={{ ...box, position: 'relative', display: 'block', width: size ?? '100%', padding: 0, textAlign: 'left', background }}
      >
        {imageUrl ? (
          <img src={imageUrl} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} />
        ) : glyph ? (
          <span style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', paddingBottom: 30 }}>
            <Icon name={glyph} size={40} weight="semibold" color="rgba(255,255,255,0.92)" />
          </span>
        ) : null}
        <span className="st-tile__shade" />
        {pressed && <span className="st-tile__done" />}
        <span style={{ position: 'absolute', left: 12, right: 12, bottom: 10, display: 'flex', flexDirection: 'column', gap: 1 }}>
          {badge && <span style={{ fontSize: 15, fontWeight: 900, color: '#fff' }}>{badge}</span>}
          <span style={{ fontSize: big ? 22 : 16, fontWeight: 800, lineHeight: 1.15, color: '#fff', textShadow: '0 1px 6px rgba(0,0,0,0.45)', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{name}</span>
          {roman && <span style={{ fontSize: 12, fontWeight: 600, color: 'rgba(255,255,255,0.9)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{roman}</span>}
          {sub && <span style={{ fontSize: 12, fontWeight: 600, color: 'rgba(255,255,255,0.82)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{sub}</span>}
        </span>
        <span className="st-plus" aria-hidden style={{ position: 'absolute' }} data-pressed={pressed}>
          <span style={{ background: pressed ? color.primaryFill : undefined }}>
            {busy ? <Spinner size={16} /> : <Icon name={pressed ? 'checkmark' : 'plus'} size={18} weight="bold" color={pressed ? '#fff' : color.primaryText} />}
          </span>
        </span>
      </Pressable>
      {note && <Txt size={12} weight="semibold" color="red" lines={2}>{note}</Txt>}
    </div>
  )
}
