/*
 * 탭바 — 양옆은 얇은 아이콘, 가운데는 위로 솟은 동그란 홈(해태 얼굴).
 * 출처: apps/scenetrip-ios/src/RootTabs.swift (TabBar, MapSearchIcon)
 * 눌렀을 때: iOS `.buttonStyle(.plain)` 은 누르는 동안 내용(홈은 원·그림자·글자 전부)이 흐려진다(iOS 시뮬레이터 실측 불투명도 ≈ 0.7, 세 탭 모두).
 *   바 56, 홈 원 62 + 바탕색 4 테, 그림자 pinDeep .42 radius 7 y 6, 글자 11, 선택은 homePurple.
 */
import { Icon } from '@/design/Icon'
import { Pressable, Txt, VStack, shadow } from '@/design/primitives'
import { tr, useLang } from '@/i18n'
import type { TabId } from './routes'

function MapSearchIcon({ color }: { color: string }) {
  // map 위에 돋보기를 겹친다. 돋보기 둘레는 탭바 바탕색으로 한 겹 파낸다.
  return (
    <span style={{ position: 'relative', display: 'inline-block', width: 24, height: 24 }} aria-hidden>
      <Icon name="map" size={19} color={color} style={{ position: 'absolute', left: 0, top: 1 }} />
      <span
        style={{
          position: 'absolute', right: 0, bottom: 0, width: 15, height: 15, borderRadius: '50%',
          background: 'var(--system-background)', display: 'grid', placeItems: 'center',
        }}
      >
        <Icon name="magnifyingglass" size={11} weight="bold" color={color} />
      </span>
    </span>
  )
}

export function TabBar({ selected, onSelect }: { selected: TabId; onSelect: (tab: TabId) => void }) {
  useLang()
  const sideColor = (tab: TabId) => (selected === tab ? 'var(--home-purple)' : 'var(--label-secondary)')
  const side = (tab: 'search' | 'community', label: string) => (
    <Pressable
      className="tab-press"
      onClick={() => onSelect(tab)}
      style={{ flex: 1, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}
      aria-label={label}
      aria-current={selected === tab}
    >
      <VStack spacing={3} align="center">
        {tab === 'search' ? <MapSearchIcon color={sideColor(tab)} /> : <span style={{ display: 'grid', placeItems: 'center', height: 24 }}><Icon name="bubble.left.and.bubble.right" size={22} color={sideColor(tab)} /></span>}
        <Txt size={11} style={{ color: sideColor(tab) }}>{label}</Txt>
      </VStack>
    </Pressable>
  )
  return (
    <nav
      className="hair-t"
      style={{
        position: 'relative', display: 'flex', height: 'calc(var(--tabbar-height) + var(--safe-bottom))', paddingBottom: 'var(--safe-bottom)',
        background: 'var(--system-background)', flex: 'none', zIndex: 20,
      }}
    >
      {side('search', tr('작품검색'))}
      <Pressable
        className="tab-press"
        onClick={() => onSelect('home')}
        aria-label={tr('홈')}
        aria-current={selected === 'home'}
        style={{ flex: 1, height: 56, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'visible' }}
      >
        <VStack spacing={3} align="center" style={{ transform: 'translateY(-18px)' }}>
          <div
            style={{
              width: 58, height: 58, margin: 2, borderRadius: '50%', background: 'var(--pin-gradient)', display: 'grid', placeItems: 'center',
              boxShadow: `0 0 0 4px var(--grouped-background), ${shadow('rgba(122,105,237,0.8)', 9, 6)}`,
            }}
          >
            <img src="/generated/haetae-face.png" alt="" width={40} height={34} style={{ objectFit: 'contain' }} />
          </div>
          <Txt size={11} weight={selected === 'home' ? 'bold' : 'regular'} style={{ color: selected === 'home' ? 'var(--home-purple)' : 'var(--label-secondary)' }}>
            {tr('홈')}
          </Txt>
        </VStack>
      </Pressable>
      {side('community', tr('커뮤니티'))}
    </nav>
  )
}
