/*
 * 첫 화면 도시 칩으로 지도에 왔을 때 지도 위에 뜨는 카드 — 「서울 촬영지 124곳」 + 「이 도시로 코스 만들기」 + ✕
 * (계획 docs/project/plans/web-city-map-first.md §1).
 */
import { track, webEvent } from '@/analytics'
import { Icon } from '@/design/Icon'
import { Pressable, Txt } from '@/design/primitives'
import type { HomeCity } from '@/features/home/homeCityModel'
import { cityCount } from '@/features/home/homeCities'
import { clearCityFocus } from '@/features/home/cityFocus'
import { protoActions, say } from '@/features/proto/protoState'

export function CityFocusCard({ city }: { city: HomeCity }) {
  const count = cityCount(city)
  return (
    <div
      role="region"
      aria-label={say(`${city.name} 촬영지`, `${city.name} filming spots`)}
      style={{
        margin: '6px 16px 0', padding: '10px 4px 12px 14px', borderRadius: 16, background: 'var(--system-background)',
        boxShadow: '0 4px 16px rgba(0,0,0,0.14)', display: 'flex', flexWrap: 'wrap', alignItems: 'center', columnGap: 4, rowGap: 6, pointerEvents: 'auto',
      }}
    >
      {/* 좁은 폰에서도 곳 수가 잘리지 않게 — 제목과 ✕ 가 윗줄, 단추는 아랫줄 전체(검증 지적). */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} weight="bold" lines={1}>{say(`${city.name} 촬영지 ${count}곳`, `${count} spots in ${city.name}`)}</Txt>
        <Txt size={12} color="secondary" lines={1}>{say('칠한 곳 안의 핀을 눌러 보세요', 'Tap a pin in the shaded area')}</Txt>
      </div>
      <Pressable plain onClick={clearCityFocus} aria-label={say('닫기', 'Close')} style={{ flex: 'none', width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
        <Icon name="xmark" size={16} color="secondary" />
      </Pressable>
      <Pressable
        plain
        onClick={() => {
          track(webEvent.planStart('city_map'))
          clearCityFocus()
          protoActions.plan(city.key)
        }}
        style={{ flex: '1 0 100%', marginRight: 10, minHeight: 44, padding: '0 14px', borderRadius: 999, background: 'var(--home-purple)', color: '#fff', fontSize: 15, fontWeight: 700 }}
      >
        {say(`${city.name}(으)로 코스 만들기`, `Plan a trip in ${city.name}`)}
      </Pressable>
    </div>
  )
}
