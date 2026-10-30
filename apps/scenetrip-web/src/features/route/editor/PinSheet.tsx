/*
 * 지도를 눌러 찍은 자리에 이름과 갈래만 붙인다 — iOS RouteEditorParts.swift `RoutePinSheet`.
 * 회의에서 이 기능이 나온 맥락은 숙소다(「핀 찍는 건 있어야 될 것 같은데. 저기 (숙소) 넣고 싶은 것도 넣을 수 있게」). 그래서 갈래 목록 맨 앞이 숙소다.
 * 이 자리는 우리 데이터에 없는 곳이라 서버 id 를 줄 수 없어 음수로 매긴다 — 진짜 촬영지 id 와 절대 겹치지 않는다(RouteMock.pinnedPlace).
 */
import { useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { tr } from '@/i18n'
import type { PlaceSummary } from '../models'
import { PIN_KINDS, type PinKind } from '../placeType'
import { DetentSheet, GlassButton, NavBar } from '../ui/DetentSheet'
import type { LatLng } from '../map/RouteMap'

/** 직접 찍은 핀의 임시 id — 시각으로 매긴 음수(`-(now_ms mod 1e9)`). */
export function pinnedPlace(name: string, category: string, at: LatLng, now = Date.now()): PlaceSummary {
  return { id: -(now % 1_000_000_000), name, type: category, address: null, latitude: at.latitude, longitude: at.longitude }
}

export function PinSheet({ pin, onClose, onDone }: { pin: LatLng | null; onClose: () => void; onDone: (name: string, category: PinKind) => void }) {
  return (
    <DetentSheet open={pin !== null} onClose={onClose} label={tr('이 자리에 추가')}>
      {pin && <PinBody pin={pin} onClose={onClose} onDone={onDone} />}
    </DetentSheet>
  )
}

function Section({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <div>
      {title && <div style={{ padding: '14px 32px 7px' }}><Txt size={13} color="secondary">{title}</Txt></div>}
      <div style={{ margin: '0 16px', background: 'var(--system-background)', borderRadius: 26, overflow: 'hidden' }}>{children}</div>
    </div>
  )
}

function PinBody({ pin, onClose, onDone }: { pin: LatLng; onClose: () => void; onDone: (name: string, category: PinKind) => void }) {
  const [name, setName] = useState('')
  const [category, setCategory] = useState<PinKind>(PIN_KINDS[0])
  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <NavBar
        title={tr('이 자리에 추가')}
        leading={<GlassButton onClick={onClose}>{tr('취소')}</GlassButton>}
        trailing={<GlassButton strong onClick={() => { onDone(name.trim() === '' ? tr('이름 없는 장소') : name, category); onClose() }}>{tr('추가')}</GlassButton>}
      />
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, paddingBottom: 24 }}>
        <Section title={tr('이름')}>
          <input
            className="bare-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={tr('예: 오늘 묵을 숙소')}
            aria-label={tr('이름')}
            style={{ width: '100%', padding: '14px 16px', fontSize: 17 }}
          />
        </Section>
        <Section title={tr('갈래')}>
          {PIN_KINDS.map((kind, i) => (
            <div key={kind}>
              <Pressable plain onClick={() => setCategory(kind)} style={{ width: '100%', textAlign: 'left' }}>
                <HStack justify="between" style={{ padding: '13px 16px' }}>
                  <Txt size={17}>{tr(kind)}</Txt>
                  {category === kind && <Icon name="checkmark" size={16} weight="semibold" color="accent" />}
                </HStack>
              </Pressable>
              {i < PIN_KINDS.length - 1 && <div style={{ height: 1, marginLeft: 16, background: 'var(--separator)', transform: 'scaleY(0.3333)' }} />}
            </div>
          ))}
        </Section>
        <div style={{ margin: '14px 16px 0', background: 'var(--system-background)', borderRadius: 26, padding: '12px 16px' }}>
          <Txt size={12} color="secondary" mono style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
            {tr('위도 %.5f · 경도 %.5f', pin.latitude, pin.longitude)}
          </Txt>
        </div>
      </div>
    </VStack>
  )
}
