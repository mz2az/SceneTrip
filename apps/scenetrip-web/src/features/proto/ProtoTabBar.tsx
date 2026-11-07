/*
 * 탭바 시안 — 세 탭을 같은 크기로(사용자 요청 2026-11-07: 탭이 셋뿐인데 홈만 위로 솟아 있어 어색하다).
 * 고른 탭은 단색 보라 알약(흰 글씨 대비 6:1)이 뒤에 깔리며 미끄러져 옮겨 간다. 홈 아이콘은 해태 얼굴을 작게. 바닥 줄은 자리만 잡고 비운다.
 * 원래 탭바: app/TabBar.tsx. 로컬 개발 서버 + 시안이 켜졌을 때만(docs/project/plans/web-trip-first-prototypes.md).
 */
import { Icon } from '@/design/Icon'
import { Pressable } from '@/design/primitives'
import { tr, useLang } from '@/i18n'
import type { TabId } from '@/app/routes'
import { color, radius, z } from './protoTokens'
import './proto.css'

const TABS: ReadonlyArray<{ id: TabId; label: () => string }> = [
  { id: 'search', label: () => tr('작품검색') },
  { id: 'home', label: () => tr('홈') },
  { id: 'community', label: () => tr('커뮤니티') },
]

function TabIcon({ id, on }: { id: TabId; on: boolean }) {
  const color = on ? 'white' : 'var(--label-secondary)'
  if (id === 'home') return <img src="/generated/haetae-face.png" alt="" width={26} height={22} style={{ objectFit: 'contain', filter: on ? 'none' : 'grayscale(0.6) opacity(0.8)' }} />
  return <Icon name={id === 'search' ? 'map' : 'bubble.left.and.bubble.right'} size={20} color={color} />
}

export function ProtoTabBar({ selected, onSelect }: { selected: TabId; onSelect: (tab: TabId) => void }) {
  useLang()
  const at = Math.max(0, TABS.findIndex((t) => t.id === selected))
  return (
    // 화면 아래에 붙은 흰 막대(위 가는 선) — 떠 있는 유리 알약은 아이폰 최신 탭바와 너무 닮아 뺐다(사용자 2026-11-07, ui-ux-pro-max 점검).
    // 고른 탭은 막대 안의 그라데이션 알약이 미끄러져 옮겨 가며 알린다(nav-state-active). 아이콘과 글자를 함께 둔다(nav-label-icon).
    <nav
      className="hair-t"
      aria-label={tr('탭')}
      style={{ position: 'relative', height: 'calc(var(--tabbar-height) + var(--safe-bottom))', paddingBottom: 'var(--safe-bottom)', flex: 'none', zIndex: z.tabbar, background: 'var(--system-background)' }}
    >
      <div style={{ position: 'relative', display: 'flex', height: '100%', padding: '6px 12px' }}>
        {/* 고른 탭 뒤의 알약 — 탭을 바꾸면 미끄러져 옮겨 간다 */}
        <div
          aria-hidden
          style={{
            position: 'absolute', top: 6, bottom: 6, left: 12, width: 'calc((100% - 24px) / 3)', borderRadius: radius.pill, background: color.primaryFill,
            transform: `translateX(${at * 100}%)`, transition: 'transform 0.38s cubic-bezier(0.22, 1, 0.36, 1)',
          }}
        />
        {TABS.map((t) => {
          const on = t.id === selected
          return (
            <Pressable
              key={t.id}
              className="proto-press"
              onClick={() => onSelect(t.id)}
              aria-label={t.label()}
              aria-current={on}
              style={{ flex: 1, position: 'relative', zIndex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, borderRadius: radius.pill }}
            >
              <TabIcon id={t.id} on={on} />
              <span style={{ fontSize: 13, fontWeight: on ? 800 : 600, color: on ? color.onPrimary : 'var(--label-secondary)', whiteSpace: 'nowrap', transition: 'color 0.2s' }}>{t.label()}</span>
            </Pressable>
          )
        })}
      </div>
    </nav>
  )
}
