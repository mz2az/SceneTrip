/*
 * 「새 코스」 시트 시안 — 원래 「AI 로 짜기 / 직접 짜기」 두 카드(route/ForkSheet.tsx) 대신 세 갈래를 사진과 함께.
 *  ① 도시로 시작(어디로 가세요? — 시안의 질문 흐름) ② 작품으로 AI 가 짜기(원래 마법사) ③ 담아 둔 곳으로 직접 짜기(원래 직접 짜기).
 * 로컬 개발 서버 + 시안이 켜졌을 때만 원래 시트 대신 뜬다(docs/project/plans/web-trip-first-prototypes.md).
 */
import type { CSSProperties, ReactNode } from 'react'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { hasPhoto } from '@/features/home/homeRules'
import { Pressable } from '@/features/route/ui/Press'
import { useCart } from '@/state/cart'
import { MockBadge, useCities } from './protoParts'
import { protoActions, say } from './protoState'

const glow = '0 8px 22px rgba(92, 74, 214, 0.32)'

function Option({ title, caption, icon, onPress, primary, art }: { title: string; caption: string; icon: string; onPress: () => void; primary?: boolean; art?: ReactNode }) {
  const box: CSSProperties = primary
    ? { background: 'var(--pin-gradient)', boxShadow: glow, color: '#fff' }
    : { background: 'var(--system-background)', boxShadow: '0 2px 10px rgba(30, 20, 90, 0.08)', color: 'var(--label)' }
  return (
    <Pressable plain onClick={onPress} style={{ width: '100%', textAlign: 'left', padding: 16, borderRadius: 20, ...box }}>
      <VStack spacing={12} align="stretch">
        <HStack spacing={12}>
          <span style={{ width: 42, height: 42, flex: 'none', borderRadius: 14, display: 'grid', placeItems: 'center', background: primary ? 'rgba(255,255,255,0.22)' : 'color-mix(in srgb, var(--accent) 12%, transparent)' }}>
            <Icon name={icon} size={20} weight="semibold" color={primary ? 'white' : 'accent'} />
          </span>
          <VStack spacing={3} align="start" style={{ flex: 1, minWidth: 0 }}>
            <Txt size={17} weight="heavy" style={{ color: 'inherit' }}>{title}</Txt>
            <Txt size={13} style={{ color: primary ? 'rgba(255,255,255,0.88)' : 'var(--label-secondary)' }}>{caption}</Txt>
          </VStack>
          <Icon name="chevron.right" size={14} weight="semibold" color={primary ? 'white' : 'tertiary'} />
        </HStack>
        {art}
      </VStack>
    </Pressable>
  )
}

export function ProtoForkSheet({ open, onClose, onAI, onManual }: { open: boolean; onClose: () => void; onAI: () => void; onManual: () => void }) {
  const router = useTabRouter()
  const cart = useCart()
  const { cities } = useCities()
  const saved = cart.placeIds.size
  const cityPhotos = cities.slice(0, 4).map((c) => ({ name: c.name, url: c.places.find(hasPhoto)?.imageUrl ?? null }))
  const go = (fn: () => void) => () => {
    onClose()
    fn()
  }

  return (
    <Sheet open={open} onClose={onClose} dim={0.25} floating detent={560} radius={30} label={say('새 코스 만들기', 'New trip')}>
      <VStack spacing={14} align="stretch" style={{ padding: '8px 16px 16px' }}>
        <div style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)', alignSelf: 'center' }} />
        <HStack spacing={8}>
          <VStack spacing={2} align="start">
            <Txt size={22} weight="heavy">{say('새 여행 코스', 'New trip')}</Txt>
            <Txt size={13} color="secondary">{say('어떻게 시작할까요?', 'How do you want to start?')}</Txt>
          </VStack>
          <Spacer />
          <MockBadge />
        </HStack>

        <Option
          primary
          icon="mappin.and.ellipse"
          title={say('도시로 시작', 'Start with a city')}
          caption={say('어디로·며칠만 고르면 촬영지를 이어 코스를 만들어요', 'Pick where and how long — we link the spots')}
          onPress={go(() => {
            router.closeCover()
            protoActions.plan()
          })}
          art={
            <HStack spacing={6} style={{ width: '100%' }}>
              {cityPhotos.map((c) => (
                <div key={c.name} style={{ position: 'relative', flex: 1, height: 54, borderRadius: 12, overflow: 'hidden', background: 'rgba(255,255,255,0.2)' }}>
                  {c.url && <img src={c.url} alt="" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
                  <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to top, rgba(0,0,0,0.55), transparent 65%)' }} />
                  <span style={{ position: 'absolute', left: 7, bottom: 5, fontSize: 11, fontWeight: 700, color: '#fff' }}>{c.name}</span>
                </div>
              ))}
            </HStack>
          }
        />
        <Option
          icon="sparkles"
          title={say('좋아하는 작품으로 AI 가 짜기', 'Plan from shows you love')}
          caption={say('작품과 기간을 고르면 AI 가 동선까지 짜 드려요', 'Choose titles and days — AI builds the route')}
          onPress={go(onAI)}
        />
        <Option
          icon="pencil"
          title={say('담아 둔 곳으로 직접 짜기', 'Build from saved places')}
          caption={saved > 0 ? say(`장바구니의 ${saved}곳에서 골라 넣어요`, `Pick from your ${saved} saved places`) : say('장바구니에서 하나씩 담아요', 'Add places one by one from Saved')}
          onPress={go(onManual)}
        />
      </VStack>
    </Sheet>
  )
}
