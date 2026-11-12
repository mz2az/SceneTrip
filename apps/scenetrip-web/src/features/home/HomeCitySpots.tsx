/*
 * 홈 「도시의 성지」 — 인사 바로 아래(MZ2AZ-531, 웹 DEV 실험). 계획: docs/project/plans/web-home-city-spots.md
 *
 * 광고로 들어온 사람은 첫 화면만 보고 나간다. 첫 화면에서 「이 도시에서 갈 수 있는 촬영지」를 보여 할 일을 알게 한다.
 * 제목 · 「지도에서 N곳 보기 ›」 / 도시 칩 줄(맨 앞 「내 위치」) / 그 도시 촬영지 카드 줄. 칩·카드 줄만 가로로 스크롤한다.
 * 웹에만 있는 문구라 iOS 문구표(en.generated.json)에 없다 — 이 파일 안에서 언어별로 고른다.
 */
import { DeferredImg } from '@/design/DeferredImg'
import { useEffect, useRef, useState } from 'react'
import { track, webEvent } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack, homeCard, rr } from '@/design/primitives'
import { LocationConsentSheet, useLocationConsent } from '@/features/profile/consent'
import { currentLang, tr, useLang } from '@/i18n'
import { locationConsentStore } from '@/state/consent'
import { useStore } from '@/state/storage'
import { Pressable } from '../route/ui/Press'
import { type LatLng, areaBbox, chooseInitialCity, cityCount, nearMeOutcome, regionBbox } from './homeCities'
import { type HomeCity, ensureCityPlaces, homeCityStore, loadHomeCities, selectHomeCity } from './homeCityModel'
import { hasPhoto } from './homeRules'

type PlaceSummary = components['schemas']['PlaceSummary']

/** 카드 줄에 올리는 수 — 나머지는 「지도에서 보기」. */
const CARD_LIMIT = 12
const CARD = { w: 148, photo: 96 }

/** 웹 전용 문구(한국어, 영어). */
const copy = (ko: string, en: string) => (currentLang() === 'en' ? en : ko)

// ---------------------------------------------------------------- 위치

const geo = (): Geolocation | null => (typeof navigator !== 'undefined' && navigator.geolocation ? navigator.geolocation : null)

/** 위치 한 번. 실패 사유를 나눠 돌려준다. */
function currentFix(options: PositionOptions): Promise<LatLng | 'denied' | 'failed'> {
  const g = geo()
  if (!g) return Promise.resolve('failed')
  return new Promise((resolve) =>
    g.getCurrentPosition(
      (p) => resolve({ lat: p.coords.latitude, lng: p.coords.longitude }),
      (e) => resolve(e.code === e.PERMISSION_DENIED ? 'denied' : 'failed'),
      options,
    ),
  )
}

/** Permissions API 의 위치 상태. **묻지 않는다** — 상태만 읽는다. 없으면 던진다. */
async function geolocationPermission(): Promise<string> {
  const status = await navigator.permissions.query({ name: 'geolocation' as PermissionName })
  return status.state
}

/** 처음 도시 — 권한이 이미 허용됐고 동의도 돼 있을 때만 캐시 위치를 짧게(3초) 묻는다. 세션에 한 번. */
let initialStarted = false
async function pickInitial(cities: readonly HomeCity[]): Promise<void> {
  if (initialStarted) return
  initialStarted = true
  const key = await chooseInitialCity(cities, {
    permission: geolocationPermission,
    consent: locationConsentStore.isAllowed(),
    fix: async () => {
      const fix = await currentFix({ enableHighAccuracy: false, maximumAge: 10 * 60_000, timeout: 3_000 })
      return typeof fix === 'string' ? null : fix
    },
  })
  // 그사이 사용자가 칩을 눌렀으면 덮지 않는다.
  if (!key || homeCityStore.get().selected) return
  selectHomeCity(key)
  track(webEvent.selectHomeCity(key, 'default'))
}

type Note = 'denied' | 'failed' | 'outside' | null

function noteText(note: Exclude<Note, null>): string {
  switch (note) {
    case 'denied': return tr('위치 권한이 필요합니다')
    case 'failed': return tr('현재 위치를 찾지 못했습니다')
    case 'outside': return copy('지금 한국 밖에 있어요 — 도시를 골라 보세요', "You're outside Korea — pick a city")
  }
}

// ---------------------------------------------------------------- 화면

export function HomeCitySpots({ onOpenPlace, onOpenMap }: { onOpenPlace: (place: PlaceSummary) => void; onOpenMap: (bbox: string) => void }) {
  const lang = useLang()
  const state = useStore(homeCityStore, (s) => s)
  const location = useLocationConsent()
  const [note, setNote] = useState<Note>(null)
  const [locating, setLocating] = useState(false)
  const chipRow = useRef<HTMLDivElement>(null)
  const cardRow = useRef<HTMLDivElement>(null)

  useEffect(() => {
    void loadHomeCities()
  }, [lang])

  const cities = state.cities
  useEffect(() => {
    if (state.status === 'loaded' && cities.length > 0) void pickInitial(cities)
  }, [state.status, cities])

  const city = cities.find((c) => c.key === state.selected) ?? null
  // 도시 요약으로 만든 줄은 고른 도시의 촬영지를 그때 받는다(MZ2AZ-545).
  useEffect(() => {
    if (city && !city.placesLoaded) void ensureCityPlaces(city.key).catch(() => {})
  }, [city?.key, city?.placesLoaded]) // eslint-disable-line react-hooks/exhaustive-deps

  // 고른 칩이 보이게 줄을 민다(가까운 도시가 줄 뒤쪽일 수 있다). 카드 줄은 맨 앞으로. 세로 스크롤은 건드리지 않는다.
  useEffect(() => {
    const row = chipRow.current
    const chip = row?.querySelector<HTMLElement>(`[data-city="${CSS.escape(state.selected ?? '')}"]`)
    if (row && chip) {
      const left = chip.offsetLeft - 20
      const right = chip.offsetLeft + chip.offsetWidth + 20 - row.clientWidth
      if (row.scrollLeft > left) row.scrollTo?.({ left, behavior: 'smooth' })
      else if (row.scrollLeft < right) row.scrollTo?.({ left: right, behavior: 'smooth' })
    }
    cardRow.current?.scrollTo?.({ left: 0 })
  }, [state.selected, cities])

  // 안내는 4초 뒤 사라진다.
  useEffect(() => {
    if (!note) return
    const t = setTimeout(() => setNote(null), 4000)
    return () => clearTimeout(t)
  }, [note])

  if (state.status === 'failed' || (state.status === 'loaded' && cities.length === 0)) return null

  const choose = (key: string) => {
    setNote(null)
    if (key === state.selected) return
    selectHomeCity(key)
    track(webEvent.selectHomeCity(key, 'chip'))
  }

  // 「내 위치」 — 누를 때만 묻는다(앱 위치 동의 → 브라우저 권한). 실패·한국 밖이면 지금 도시 그대로 두고 한 줄 안내.
  const nearMe = () => {
    if (locating || state.status !== 'loaded') return
    setNote(null)
    location.request(() => {
      setLocating(true)
      void currentFix({ enableHighAccuracy: false, maximumAge: 60_000, timeout: 8_000 }).then((fix) => {
        setLocating(false)
        if (typeof fix === 'string') return setNote(fix)
        const now = homeCityStore.get()
        const outcome = nearMeOutcome(now.cities, now.status === 'loaded', fix)
        if (outcome.kind === 'outside') return setNote('outside')
        // 목록을 아직 받는 중이면 안내 없이 지금 도시 그대로 — 버튼은 목록이 올 때까지 잠겨 있어 보통 여기 오지 않는다.
        if (outcome.kind === 'pending') return
        selectHomeCity(outcome.key)
        track(webEvent.selectHomeCity(outcome.key, 'near_me'))
      })
    })
  }

  const loading = city === null || !city.placesLoaded
  const count = city ? cityCount(city) : 0
  const bbox = city ? (city.places.length > 0 ? regionBbox(city.places) : city.area ? areaBbox(city.area) : null) : null

  return (
    <VStack spacing={10} align="stretch" data-testid="home-city-spots">
      <HStack spacing={8} align="baseline" style={{ padding: '0 20px' }}>
        <Txt size={17} weight="heavy" lines={1} style={{ minWidth: 0 }}>
          {city ? copy(`${city.name}의 성지`, `Spots in ${city.name}`) : copy('도시의 성지', 'Spots by city')}
        </Txt>
        <Spacer />
        {bbox && (
          <Pressable plain onClick={() => onOpenMap(bbox)} style={{ flex: 'none' }}>
            <Txt size={13} weight="semibold" color="accent">{copy(`지도에서 ${count}곳 보기 ›`, `See all ${count} on map ›`)}</Txt>
          </Pressable>
        )}
      </HStack>

      <div ref={chipRow} className="scroll-x">
        <HStack spacing={8} style={{ padding: '2px 20px 4px', width: 'max-content' }}>
          <Chip on={false} onClick={nearMe} disabled={state.status !== 'loaded'} label={copy('내 위치', 'Near me')} icon={locating ? 'location.fill' : 'location'} />
          {cities.map((c) => (
            <Chip key={c.key} city={c.key} on={c.key === state.selected} onClick={() => choose(c.key)} label={c.name} count={cityCount(c)} />
          ))}
        </HStack>
      </div>

      {note && (
        <div style={{ padding: '0 20px' }} role="status">
          <Txt size={12} color="secondary">{noteText(note)}</Txt>
        </div>
      )}

      <div ref={cardRow} className="scroll-x">
        <HStack spacing={10} align="start" style={{ padding: '2px 20px 6px', width: 'max-content' }}>
          {loading
            ? [0, 1, 2].map((i) => <div key={i} style={{ width: CARD.w, height: CARD.photo + 52, ...homeCard(16), opacity: 0.6 }} />)
            : city!.places.slice(0, CARD_LIMIT).map((place) => <SpotCard key={place.id} place={place} onOpen={() => onOpenPlace(place)} />)}
        </HStack>
      </div>
      <LocationConsentSheet gate={location.gate} />
    </VStack>
  )
}

function Chip({ on, onClick, label, count, icon, city, disabled }: { on: boolean; onClick: () => void; label: string; count?: number; icon?: string; city?: string; disabled?: boolean }) {
  return (
    <Pressable
      plain
      onClick={onClick}
      disabled={disabled}
      data-city={city}
      aria-pressed={city ? on : undefined}
      style={{
        flex: 'none', padding: '6px 12px', borderRadius: 999,
        background: on ? 'var(--accent)' : 'var(--system-background)', color: on ? '#fff' : 'var(--label)',
        boxShadow: on ? 'none' : 'var(--card-shadow)', opacity: disabled ? 0.5 : 1,
      }}
    >
      <HStack spacing={5}>
        {icon && <Icon name={icon} size={13} weight="semibold" color={on ? 'white' : 'accent'} />}
        <Txt size={14} weight={on ? 'semibold' : 'regular'} style={{ color: 'inherit' }}>{label}</Txt>
        {count !== undefined && <Txt size={12} style={{ color: on ? 'rgba(255,255,255,0.8)' : 'var(--secondary-label)' }}>{count}</Txt>}
      </HStack>
    </Pressable>
  )
}

function SpotCard({ place, onOpen }: { place: PlaceSummary; onOpen: () => void }) {
  const work = place.contents?.[0]?.title
  return (
    <Pressable plain onClick={onOpen} aria-label={place.name} style={{ width: CARD.w, textAlign: 'left', overflow: 'hidden', ...homeCard(16) }}>
      <div
        style={{
          position: 'relative', height: CARD.photo, overflow: 'hidden', ...rr(0),
          background: 'linear-gradient(to bottom right, color-mix(in srgb, var(--pin-deep) 13%, transparent), color-mix(in srgb, var(--pin-light) 33%, transparent))',
        }}
      >
        {hasPhoto(place) && (
          <DeferredImg src={place.imageUrl!} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} draggable={false} />
        )}
      </div>
      <VStack spacing={3} align="stretch" style={{ padding: '8px 10px 10px' }}>
        <Txt size={13} weight="bold" lines={1}>{place.name}</Txt>
        <Txt size={11} color={work ? 'purple' : 'secondary'} weight={work ? 'semibold' : 'regular'} lines={1}>{work ?? (place.address ?? tr('주소 없음'))}</Txt>
      </VStack>
    </Pressable>
  )
}
