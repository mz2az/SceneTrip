/*
 * 탭바 시안 — 떠 있는 알약 하나에 세 탭을 같은 크기로(사용자 요청 2026-11-07: 탭이 셋뿐인데 홈만 위로 솟아 있어 어색하다).
 * 고른 탭은 단색 보라 알약(흰 글씨 대비 6:1)이 뒤에 깔리며 미끄러져 옮겨 간다. 반투명 유리 효과는 앱에서 이 탭바 한 곳에만 쓴다(S4). 홈 아이콘은 해태 얼굴을 작게. 바닥 줄은 자리만 잡고 비운다.
 * 원래 탭바: app/TabBar.tsx. 로컬 개발 서버 + 시안이 켜졌을 때만(docs/project/plans/web-trip-first-prototypes.md).
 */
import { Icon } from '@/design/Icon'
import { Pressable } from '@/design/primitives'
import { tr, useLang } from '@/i18n'
import type { TabId } from '@/app/routes'
import { color, radius, shadow, z } from './protoTokens'

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
    <nav style={{ position: 'relative', height: 'calc(var(--tabbar-height) + var(--safe-bottom))', paddingBottom: 'var(--safe-bottom)', flex: 'none', zIndex: z.tabbar, background: 'transparent' }}>
      <div
        style={{
          position: 'absolute', left: 16, right: 16, bottom: 'calc(var(--safe-bottom) + 8px)', height: 58, borderRadius: radius.pill,
          background: 'color-mix(in srgb, var(--system-background) 88%, transparent)', backdropFilter: 'blur(18px) saturate(1.6)', WebkitBackdropFilter: 'blur(18px) saturate(1.6)',
          boxShadow: shadow.float, display: 'flex', padding: 5,
        }}
      >
        {/* 고른 탭 뒤의 알약 — 탭을 바꾸면 미끄러져 옮겨 간다 */}
        <div
          aria-hidden
          style={{
            position: 'absolute', top: 5, bottom: 5, left: 5, width: 'calc((100% - 10px) / 3)', borderRadius: radius.pill, background: color.primaryFill,
            transform: `translateX(${at * 100}%)`, transition: 'transform 0.38s cubic-bezier(0.22, 1, 0.36, 1)',
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
