/*
 * 코스 편집의 장소 넣기 시안 — 떠 있는 ＋ 하나에서 두 갈래(2026-11-07 사용자 제안).
 *  - 「지도에서 추가」: 검색 탭과 같은 화면을 편집 위에 띄운다. 장소 상세의 「코스에 추가」 → 일차 목록에서 고르면 들어간다(protoCourseAdd).
 *  - 「보관함에서 추가」: 새 화면 없이 큰 서랍 — 담아 둔 곳을 체크 → 「선택 완료」 → 서랍 안에서 일차 목록을 골라 한 번에 넣는다.
 * 일차 목록은 편집 화면에서 보고 있던 일차에 「지금 보는 날」 을 붙여 둔다. 로컬 개발 서버 + 시안일 때만 쓰인다(docs/project/plans/web-trip-first-prototypes.md).
 */
import { useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import type { PlaceSummary } from '@/features/route/models'
import { cartPlaces } from '@/features/route/store'
import { DetentSheet, GlassButton, NavBar } from '@/features/route/ui/DetentSheet'
import { Pressable } from '@/features/route/ui/Press'
import { RemoteImage } from '@/features/route/ui/RemoteImage'
import { SearchTab } from '@/features/search/SearchTab'
import { useCart } from '@/state/cart'
import { CourseAddContext, DayList, DayPopup } from './protoCourseAdd'
import { FAB_SIZE } from './addFabLayout'
import { say } from './protoState'
import { color, radius, shadow } from './protoTokens'
import './proto.css'
import { smallImage } from '@/shared/smallImage'

/**
 * 떠 있는 ＋ — 해태 가이드 동그라미(route/guide/GuideChip, 얼굴 46)와 같은 크기·같은 오른쪽 여백(12)으로 세로 한 줄에 선다.
 * 누르면 그 위로 두 갈래 메뉴가 펼쳐진다. 메뉴와 어두운 바탕은 가이드 동그라미(겹침 순서 30·35)보다 위에 둔다 — 메뉴가 해태에 가려졌다(2026-11-07 사용자 지적).
 */
const FAB = FAB_SIZE
const FAB_EDGE = 12
/** 가이드 동그라미보다 위. */
const MENU_Z = 40

export function AddFab({ onMap, onSaved, bottom = 16 }: { onMap: () => void; onSaved: () => void; bottom?: number }) {
  const [open, setOpen] = useState(false)
  const pick = (fn: () => void) => () => {
    setOpen(false)
    fn()
  }
  return (
    <>
      {open && <div aria-hidden onClick={() => setOpen(false)} style={{ position: 'absolute', inset: 0, zIndex: MENU_Z - 1, background: 'rgba(0,0,0,0.12)' }} />}
      <div style={{ position: 'absolute', right: FAB_EDGE, bottom, zIndex: open ? MENU_Z : 30, display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 10 }}>
        {open && (
          <div role="menu" style={{ minWidth: 200, borderRadius: radius.m, background: 'var(--system-background)', boxShadow: shadow.float, overflow: 'hidden' }}>
            <MenuRow icon="map" title={say('지도에서 추가', 'Add from map')} caption={say('검색 탭처럼 찾아서 넣기', 'Find it like in Search')} onPress={pick(onMap)} />
            <div style={{ height: 0.5, marginLeft: 48, background: 'var(--separator)' }} />
            <MenuRow icon="bag" title={say('보관함에서 추가', 'Add from saved')} caption={say('담아 둔 곳 골라 넣기', 'Pick from saved places')} onPress={pick(onSaved)} />
          </div>
        )}
        <Pressable
          data-coach="editor-add"
          plain
          className="proto-press"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          aria-label={say('장소 추가', 'Add places')}
          style={{ width: FAB, height: FAB, borderRadius: 999, display: 'grid', placeItems: 'center', background: color.primaryFill, boxShadow: shadow.float }}
        >
          <span style={{ display: 'grid', transform: open ? 'rotate(45deg)' : 'none', transition: 'transform 0.2s ease' }}>
            <Icon name="plus" size={21} weight="bold" color="white" />
          </span>
        </Pressable>
      </div>
    </>
  )
}

function MenuRow({ icon, title, caption, onPress }: { icon: string; title: string; caption: string; onPress: () => void }) {
  return (
    <Pressable plain role="menuitem" onClick={onPress} style={{ width: '100%', padding: '12px 14px', textAlign: 'left' }}>
      <HStack spacing={12}>
        <Icon name={icon} size={20} color={color.primaryText} />
        <VStack spacing={1} align="start">
          <Txt size={15} weight="semibold">{title}</Txt>
          <Txt size={12} color="secondary">{caption}</Txt>
        </VStack>
      </HStack>
    </Pressable>
  )
}

/** 보관함에서 추가 — 큰 서랍. 1단계: 담아 둔 곳 체크(고른 순서 번호). 2단계(「선택 완료」): 서랍 안에서 넣을 일차를 목록으로 고른다. */
export function SavedAddSheet({ open, dayCounts, current, taken, onAdd, onClose }: {
  open: boolean
  dayCounts: readonly number[]
  current: number
  taken: ReadonlySet<number>
  onAdd: (places: PlaceSummary[], dayIndex: number) => void
  onClose: () => void
}) {
  return (
    <DetentSheet open={open} onClose={onClose} label={say('보관함에서 추가', 'Add from saved')} initialDetent="large">
      {open && <SavedBody dayCounts={dayCounts} current={current} taken={taken} onAdd={onAdd} onClose={onClose} />}
    </DetentSheet>
  )
}

/** 줄 사진 — 56, 줄 위아래로 12 씩 띄운다(사진이 줄 끝에 붙으면 답답하다 — 2026-11-07 사용자 지적). */
const THUMB = 56
const ROW_PAD = 12

function SavedBody({ dayCounts, current, taken, onAdd, onClose }: { dayCounts: readonly number[]; current: number; taken: ReadonlySet<number>; onAdd: (p: PlaceSummary[], d: number) => void; onClose: () => void }) {
  const cart = useCart()
  const places = cartPlaces(cart.items.filter((i) => !i.delisted))
  const free = places.filter((p) => !taken.has(p.id))
  const inCourse = places.filter((p) => taken.has(p.id))
  const [picked, setPicked] = useState<number[]>([])
  const [choosingDay, setChoosingDay] = useState(false)
  const toggle = (id: number) => setPicked((s) => (s.includes(id) ? s.filter((v) => v !== id) : [...s, id]))
  const allPicked = free.length > 0 && free.every((p) => picked.includes(p.id))
  const put = (day: number) => {
    // 고른 순서대로 넣는다 — 원래 장바구니 시트와 같다.
    onAdd(picked.map((id) => places.find((p) => p.id === id)).filter((p): p is PlaceSummary => !!p), day)
    onClose()
  }

  if (choosingDay) {
    return (
      <VStack style={{ flex: 1, minHeight: 0 }}>
        <NavBar title={say('몇 일차에 넣을까요?', 'Which day?')} leading={<GlassButton onClick={() => setChoosingDay(false)}>{say('이전', 'Back')}</GlassButton>} />
        <VStack spacing={10} align="stretch" className="scroll-y" style={{ flex: 1, minHeight: 0, padding: '4px 16px 24px' }}>
          <Txt size={13} color="secondary" style={{ padding: '0 4px' }}>{say(`고른 ${picked.length}곳을 넣을 날을 골라 주세요`, `Pick a day for the ${picked.length} places`)}</Txt>
          <DayList dayCounts={dayCounts} current={current} onPick={put} />
        </VStack>
      </VStack>
    )
  }

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <NavBar title={say('보관함에서 추가', 'Add from saved')} leading={<GlassButton onClick={onClose}>{say('닫기', 'Close')}</GlassButton>} />
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, padding: '4px 16px 24px' }}>
        {places.length === 0 ? (
          <VStack spacing={8} align="center" style={{ padding: '48px 12px' }}>
            <Icon name="bag" size={30} color="tertiary" />
            <Txt size={15} weight="semibold" align="center">{say('아직 담아 둔 곳이 없어요', 'Nothing saved yet')}</Txt>
            <Txt size={13} color="secondary" align="center">{say('「지도에서 추가」 로 찾아 넣어 보세요', 'Try “Add from map”.')}</Txt>
          </VStack>
        ) : (
          <VStack spacing={22} align="stretch">
            {free.length > 0 && (
              <section>
                {/* 묶음 머리 — 개수와 「모두 고르기」 */}
                <HStack style={{ padding: '0 4px 8px', minHeight: 32 }}>
                  <Txt size={13} weight="semibold" color="secondary">{say(`담아 둔 곳 ${free.length}`, `Saved ${free.length}`)}</Txt>
                  <span style={{ flex: 1 }} />
                  <Pressable plain onClick={() => setPicked(allPicked ? [] : free.map((p) => p.id))} style={{ minHeight: 32 }}>
                    <Txt size={13} weight="semibold" style={{ color: color.primaryText }}>{allPicked ? say('모두 풀기', 'Clear') : say('모두 고르기', 'Select all')}</Txt>
                  </Pressable>
                </HStack>
                <ul style={{ listStyle: 'none', margin: 0, padding: 0, borderRadius: 14, background: 'var(--system-background)', overflow: 'hidden' }}>
                  {free.map((p, i) => <SavedRow key={p.id} place={p} first={i === 0} order={picked.indexOf(p.id)} onPress={() => toggle(p.id)} />)}
                </ul>
              </section>
            )}
            {inCourse.length > 0 && (
              <section>
                {/* 위 「담아 둔 곳」 머리와 같은 틀(높이 32 + 아래 8) — 글자와 목록 사이가 같아진다 */}
                <HStack style={{ padding: '0 4px 8px', minHeight: 32 }}>
                  <Txt size={13} weight="semibold" color="secondary">{say(`이미 코스에 있는 곳 ${inCourse.length}`, `Already in the trip ${inCourse.length}`)}</Txt>
                </HStack>
                <ul style={{ listStyle: 'none', margin: 0, padding: 0, borderRadius: 14, background: 'var(--system-background)', overflow: 'hidden', opacity: 0.55 }}>
                  {inCourse.map((p, i) => <SavedRow key={p.id} place={p} first={i === 0} order={-1} done />)}
                </ul>
              </section>
            )}
          </VStack>
        )}
      </div>
      {/* 아래 고정 — 고른 수와 「선택 완료」. 고르기 전에는 흐리게. */}
      {free.length > 0 && (
        <div style={{ padding: '10px 16px calc(env(safe-area-inset-bottom, 0px) + 12px)', borderTop: '0.5px solid var(--separator)', background: 'var(--system-background)', flex: 'none' }}>
          <Pressable plain disabled={picked.length === 0} onClick={() => setChoosingDay(true)} style={{ width: '100%', minHeight: 50, borderRadius: 14, background: color.primaryFill, opacity: picked.length === 0 ? 0.4 : 1 }}>
            <Txt size={16} weight="bold" align="center" style={{ color: color.onPrimary }}>
              {picked.length === 0 ? say('넣을 곳을 골라 주세요', 'Pick places to add') : say(`${picked.length}곳 선택 완료`, `Done · ${picked.length} picked`)}
            </Txt>
          </Pressable>
        </div>
      )}
    </VStack>
  )
}

/** 보관함 한 줄 — 사진(위아래 띄움) · 이름/작품/주소 · 고름 표시. 고른 줄은 바탕을 옅은 보라로. */
function SavedRow({ place, first, order, done = false, onPress }: { place: PlaceSummary; first: boolean; order: number; done?: boolean; onPress?: () => void }) {
  const on = order >= 0
  const work = place.contents?.[0]?.title
  return (
    <li>
      <Pressable
        plain
        disabled={done}
        role="checkbox"
        aria-checked={on || done}
        onClick={onPress}
        style={{ width: '100%', textAlign: 'left', paddingLeft: 14, background: on ? 'color-mix(in srgb, var(--home-purple) 7%, var(--system-background))' : undefined, transition: 'background 0.15s' }}
      >
        <HStack spacing={14}>
          <span style={{ flex: 'none', margin: `${ROW_PAD}px 0`, display: 'grid' }}>
            <RemoteImage url={smallImage(place.imageThumbnailUrl, place.imageUrl)} symbol="mappin.and.ellipse" size={{ w: THUMB, h: THUMB }} radius={12} />
          </span>
          {/* 글자 칸 — 위 선은 여기서부터(사진 오른쪽만 나눈다) */}
          <HStack spacing={12} style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', padding: `0 16px 0 0`, borderTop: first ? undefined : '0.5px solid var(--separator)' }}>
            <VStack spacing={3} align="start" style={{ flex: 1, minWidth: 0 }}>
              <Txt size={16} weight="semibold" lines={1}>{place.name}</Txt>
              {work && <Txt size={13} color="purple" lines={1}>{work}</Txt>}
              {place.address && <Txt size={12} color="secondary" lines={1}>{place.address}</Txt>}
            </VStack>
            {done ? <Icon name="checkmark.circle.fill" size={24} color="var(--system-gray3)" />
              : on ? <span style={{ width: 26, height: 26, borderRadius: 999, flex: 'none', display: 'grid', placeItems: 'center', background: color.primaryFill }}><Txt size={13} weight="heavy" color="white">{order + 1}</Txt></span>
                : <span style={{ width: 26, height: 26, borderRadius: 999, flex: 'none', border: '1.5px solid var(--system-gray3)' }} />}
          </HStack>
        </HStack>
      </Pressable>
    </li>
  )
}

/** 지도에서 추가 — 검색 탭을 그대로 편집 위에 띄운다. 맨 위 줄은 ‹ 돌아가기만. 장소 상세의 「코스에 추가」 → 아래에서 일차 목록. */
export function MapAddScreen({ dayCounts, current, taken, onAdd, onClose }: {
  dayCounts: readonly number[]
  current: number
  taken: ReadonlySet<number>
  onAdd: (place: PlaceSummary, dayIndex: number) => void
  onClose: () => void
}) {
  const [asking, setAsking] = useState<PlaceSummary | null>(null)
  const [addedTo, setAddedTo] = useState<ReadonlyMap<number, number>>(new Map())
  return (
    <div role="dialog" aria-modal="true" aria-label={say('지도에서 추가', 'Add from map')} style={{ position: 'absolute', inset: 0, zIndex: 60, display: 'flex', flexDirection: 'column', background: 'var(--system-background)' }}>
      {/* 높이는 코스 편집의 머리줄과 같게(위아래 4 + 단추 44) */}
      <HStack justify="between" style={{ padding: '4px 8px', borderBottom: '0.5px solid var(--separator)', flex: 'none' }}>
        <Pressable plain onClick={onClose} style={{ minHeight: 44, padding: '0 8px' }}>
          <HStack spacing={4}>
            <Icon name="chevron.left" size={18} weight="semibold" color="accent" />
            <Txt size={16} color="accent">{say('코스로', 'Back to trip')}</Txt>
          </HStack>
        </Pressable>
        <Txt size={16} weight="semibold">{say('지도에서 추가', 'Add from map')}</Txt>
        <div style={{ width: 88 }} />
      </HStack>
      <div style={{ position: 'relative', flex: 1, minHeight: 0 }}>
        <CourseAddContext.Provider value={{ taken, addedTo, ask: setAsking }}>
          <SearchTab active embedded />
        </CourseAddContext.Provider>
      </div>
      {asking && (
        <DayPopup
          title={say(`「${asking.name}」 을 몇 일차에 넣을까요?`, `Add “${asking.name}” to which day?`)}
          dayCounts={dayCounts}
          current={current}
          onPick={(day) => {
            onAdd(asking, day)
            setAddedTo((m) => new Map(m).set(asking.id, day))
            setAsking(null)
          }}
          onClose={() => setAsking(null)}
        />
      )}
    </div>
  )
}
