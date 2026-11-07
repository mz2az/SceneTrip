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

/**
 * 해태 얼굴을 납작한 아이콘으로 — 구름 갈기(연하게) · 뿔 · 귀 · 얼굴(진하게) · 눈과 웃는 입(뚫음). 한 색(currentColor)이라 탭 색을 그대로 따른다.
 * 원본 그림: public/generated/haetae-face.png.
 */
function HaetaeGlyph({ size = 24, color }: { size?: number; color: string }) {
  const mane = [0, 45, 90, 135, 180, 225, 270, 315].map((deg) => {
    const r = (deg * Math.PI) / 180
    return { cx: 12 + Math.cos(r) * 7.2, cy: 14 + Math.sin(r) * 6.6 }
  })
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden style={{ color, display: 'block' }}>
      <defs>
        <mask id="haetae-face-cut">
          <rect width="24" height="24" fill="#fff" />
          <circle cx="9.3" cy="13.6" r="1.25" fill="#000" />
          <circle cx="14.7" cy="13.6" r="1.25" fill="#000" />
          <path d="M9.6 16.2 Q12 18.9 14.4 16.2 Z" fill="#000" />
        </mask>
      </defs>
      {/* 구름 갈기 */}
      <g fill="currentColor" opacity="0.38">
        <ellipse cx="12" cy="14" rx="8.4" ry="7.6" />
        {mane.map((m, i) => <circle key={i} cx={m.cx} cy={m.cy} r="3" />)}
      </g>
      {/* 뿔·귀 */}
      <path d="M12 1.6 L13.6 6 L10.4 6 Z" fill="currentColor" />
      <path d="M5.6 6.4 L9 8.6 L6.2 10.6 Z" fill="currentColor" />
      <path d="M18.4 6.4 L15 8.6 L17.8 10.6 Z" fill="currentColor" />
      {/* 얼굴 */}
      <ellipse cx="12" cy="14.4" rx="6.1" ry="5.6" fill="currentColor" mask="url(#haetae-face-cut)" />
    </svg>
  )
}

/**
 * 고르지 않은 홈 — 옆 탭의 선 아이콘(지도·말풍선)과 같은 굵기의 선 해태: 둥근 갈기 테두리 · 뿔 · 귀 · 눈 · 웃는 입.
 * 고른 홈은 채운 해태(HaetaeGlyph). 선 ↔ 채움으로 「지금 여기」 를 말하는 흔한 탭 규칙을 따른다.
 */
function HaetaeLine({ size = 24, color }: { size?: number; color: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden style={{ color, display: 'block' }}>
      {/* 구름 갈기 — 물결 테두리 */}
      <path d="M12 5.4c1.3-1 3.1-.6 3.8.8 1.5-.3 2.9.9 2.8 2.4 1.3.6 1.8 2.2 1.1 3.4.8 1.2.4 2.9-.9 3.5.2 1.5-1 2.8-2.5 2.7-.6 1.3-2.2 1.9-3.5 1.2-.9 1-2.7 1-3.6 0-1.3.7-2.9.1-3.5-1.2-1.5.1-2.7-1.2-2.5-2.7-1.3-.6-1.7-2.3-.9-3.5-.7-1.2-.2-2.8 1.1-3.4-.1-1.5 1.3-2.7 2.8-2.4.7-1.4 2.5-1.8 3.8-.8Z" />
      {/* 뿔 */}
      <path d="M12 1.8 12.9 4.6 11.1 4.6Z" fill="currentColor" />
      {/* 눈·입 */}
      <circle cx="9.6" cy="12.6" r="0.9" fill="currentColor" stroke="none" />
      <circle cx="14.4" cy="12.6" r="0.9" fill="currentColor" stroke="none" />
      <path d="M10 15.3c1.1 1.2 2.9 1.2 4 0" />
    </svg>
  )
}

function TabIcon({ id, on }: { id: TabId; on: boolean }) {
  const color = on ? 'white' : 'var(--label-secondary)'
  if (id === 'home') return on ? <HaetaeGlyph size={24} color="#fff" /> : <HaetaeLine size={23} color="var(--label-secondary)" />
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
