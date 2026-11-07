/*
 * 탭바 시안 — 떠 있는 알약 하나에 세 탭을 같은 크기로(사용자 요청 2026-11-07: 탭이 셋뿐인데 홈만 위로 솟아 있어 어색하다).
 * 고른 탭은 앱 그라데이션 알약이 뒤에 깔리며 미끄러져 옮겨 간다. 홈 아이콘은 해태 얼굴을 작게. 바닥 줄은 자리만 잡고 비운다.
 * 원래 탭바: app/TabBar.tsx. 로컬 개발 서버 + 시안이 켜졌을 때만(docs/project/plans/web-trip-first-prototypes.md).
 */
import { Icon } from '@/design/Icon'
import { Pressable } from '@/design/primitives'
import { tr, useLang } from '@/i18n'
import type { TabId } from '@/app/routes'

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
    // 바깥 줄은 원래 탭바와 같은 높이 — 위 화면들이 그 높이를 기준으로 자리를 잡는다. 알약은 그 안에 떠 있다.
    <nav style={{ position: 'relative', height: 'calc(var(--tabbar-height) + var(--safe-bottom))', paddingBottom: 'var(--safe-bottom)', flex: 'none', zIndex: 20, background: 'transparent' }}>
      <div
        style={{
          position: 'absolute', left: 16, right: 16, bottom: 'calc(var(--safe-bottom) + 8px)', height: 58, borderRadius: 999,
          background: 'color-mix(in srgb, var(--system-background) 88%, transparent)', backdropFilter: 'blur(18px) saturate(1.6)', WebkitBackdropFilter: 'blur(18px) saturate(1.6)',
          boxShadow: '0 8px 28px rgba(40, 30, 120, 0.16), 0 1px 0 rgba(255,255,255,0.6) inset', display: 'flex', padding: 5,
        }}
      >
        {/* 고른 탭 뒤의 알약 — 탭을 바꾸면 미끄러져 옮겨 간다 */}
        <div
          aria-hidden
          style={{
            position: 'absolute', top: 5, bottom: 5, left: 5, width: 'calc((100% - 10px) / 3)', borderRadius: 999, background: 'var(--pin-gradient)',
            boxShadow: '0 4px 12px rgba(92, 74, 214, 0.35)', transform: `translateX(${at * 100}%)`, transition: 'transform 0.38s cubic-bezier(0.22, 1, 0.36, 1)',
          }}
        />
        {TABS.map((t) => {
          const on = t.id === selected
          return (
            <Pressable
              key={t.id}
              className="tab-press"
              onClick={() => onSelect(t.id)}
              aria-label={t.label()}
              aria-current={on}
              style={{ flex: 1, position: 'relative', zIndex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, borderRadius: 999 }}
            >
              <TabIcon id={t.id} on={on} />
              <span style={{ fontSize: 13, fontWeight: on ? 800 : 600, color: on ? '#fff' : 'var(--label-secondary)', whiteSpace: 'nowrap', transition: 'color 0.2s' }}>{t.label()}</span>
            </Pressable>
          )
        })}
      </div>
    </nav>
  )
}
