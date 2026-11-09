/*
 * 「새 코스」 시트 시안 — 원래 「AI 로 짜기 / 직접 짜기」 두 카드(route/ForkSheet.tsx) 대신 세 갈래를 한 묶음의 세 줄로.
 *  ① 도시로 시작(어디로 가세요? — 시안의 질문 흐름) ② 작품으로(원래 AI 마법사) ③ 담은 곳으로(원래 직접 짜기).
 * 줄마다 왼쪽에 무엇으로 시작하는지 보이는 사진 한 장(도시·포스터·담은 곳), 오른쪽 화살표, 줄 사이 얇은 선.
 * 그림자 띄운 큰 카드·타일을 쓰던 모양은 「틀 없이 적은 느낌」(2026-11-07 사용자 지적)이라 앱 설정 화면 같은 묶음 목록으로 바꿨다.
 * 로컬 개발 서버 + 시안이 켜졌을 때만 원래 시트 대신 뜬다(docs/project/plans/web-trip-first-prototypes.md).
 */
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { homeStore } from '@/features/home/homeModel'
import { Pressable } from '@/features/route/ui/Press'
import { useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import { MockBadge, cityPhotoOf, useCities } from './protoParts'
import { color, heading, radius } from './protoTokens'
import { protoActions, say } from './protoState'
import './proto.css'

/** 시트 높이 — 머리 + 세 줄 묶음에 맞춘다. 남는 바닥을 두지 않는다. */
const SHEET_HEIGHT = 368
const ART = 52

/** 묶음 안의 한 줄 — 사진 · 제목/설명 · 화살표. 첫 줄이 아니면 글자 칸 위에 얇은 선. */
function OptionRow({ photo, icon, title, caption, tag, first, onPress }: {
  photo: string | null
  icon: string
  title: string
  caption: string
  tag?: string
  first?: boolean
  onPress: () => void
}) {
  return (
    <Pressable plain onClick={onPress} style={{ display: 'block', width: '100%', textAlign: 'left', paddingLeft: 12 }}>
      <HStack spacing={12} style={{ width: '100%' }}>
        <div aria-hidden style={{ position: 'relative', width: ART, height: ART, margin: '12px 0', flex: 'none', borderRadius: radius.s, overflow: 'hidden', background: color.brandFill, display: 'grid', placeItems: 'center' }}>
          {photo ? <img src={photo} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} /> : <Icon name={icon} size={20} color="white" />}
        </div>
        <HStack spacing={8} style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', padding: '12px 12px 12px 0', borderTop: first ? undefined : '0.5px solid var(--separator)' }}>
          <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
            <HStack spacing={6}>
              <Txt as="h3" size={16} weight="semibold" lines={1} style={heading}>{title}</Txt>
              {tag && <span style={{ flex: 'none', fontSize: 11, fontWeight: 800, padding: '1px 6px', borderRadius: radius.s, background: 'color-mix(in srgb, var(--home-purple) 12%, transparent)', color: color.primaryText }}>{tag}</span>}
            </HStack>
            <Txt size={13} color="secondary" lines={2}>{caption}</Txt>
          </VStack>
          <Icon name="chevron.right" size={13} weight="semibold" color="tertiary" />
        </HStack>
      </HStack>
    </Pressable>
  )
}

export function ProtoForkSheet({ open, onClose, onAI, onManual }: { open: boolean; onClose: () => void; onAI: () => void; onManual: () => void }) {
  const router = useTabRouter()
  const cart = useCart()
  const works = useStore(homeStore, (s) => s.works)
  const { cities } = useCities()
  const saved = cart.items
  const cityPhoto = cities.map(cityPhotoOf).find(Boolean) ?? null
  const poster = works.find((w) => w.posterUrl)?.posterUrl ?? null
  const savedPhoto = saved.find((i) => i.imageUrl)?.imageUrl ?? null
  const go = (fn: () => void) => () => {
    onClose()
    fn()
  }

  return (
    <Sheet open={open} onClose={onClose} dim={0.25} floating detent={SHEET_HEIGHT} radius={30} label={say('새 코스 만들기', 'New trip')}>
      <VStack spacing={14} align="stretch" style={{ padding: '8px 16px 18px', background: 'var(--grouped-background)', height: '100%' }}>
        <div aria-hidden style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)', alignSelf: 'center' }} />
        <HStack spacing={8} style={{ padding: '0 4px' }}>
          <VStack spacing={2} align="start">
            <Txt as="h2" size={22} weight="heavy" style={heading}>{say('새 여행 코스', 'New trip')}</Txt>
            <Txt size={13} color="secondary">{say('어떻게 시작할까요?', 'How do you want to start?')}</Txt>
          </VStack>
          <Spacer />
          <MockBadge />
        </HStack>
        <div style={{ borderRadius: 12, background: 'var(--system-background)', overflow: 'hidden' }}>
          <OptionRow
            first
            photo={cityPhoto}
            icon="mappin.and.ellipse"
            title={say('도시로 시작', 'Start with a city')}
            caption={say('어디로·며칠만 고르면 코스 완성', 'Pick where and for how long.')}
            onPress={go(() => {
              router.closeCover()
              protoActions.plan()
            })}
          />
          <OptionRow
            photo={poster}
            icon="sparkles"
            title={say('작품으로', 'From shows')}
            tag="AI"
            caption={say('좋아하는 작품을 고르면 촬영지를 이어 줘요', 'Pick shows you love. AI links their spots.')}
            onPress={go(onAI)}
          />
          <OptionRow
            photo={savedPhoto}
            icon="bag"
            title={say('담은 곳으로', 'From saved')}
            caption={saved.length > 0 ? say(`담아 둔 ${saved.length}곳을 날마다 나눠 넣어요`, `Split your ${saved.length} saved places by day`) : say('빈 코스에서 하나씩 넣어요', 'Start empty and add places')}
            onPress={go(onManual)}
          />
        </div>
      </VStack>
    </Sheet>
  )
}
