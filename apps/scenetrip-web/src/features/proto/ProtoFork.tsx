/*
 * 「새 코스」 시트 시안 — 원래 「AI 로 짜기 / 직접 짜기」 두 카드(route/ForkSheet.tsx) 대신 세 갈래를 사진과 함께.
 *  ① 도시로 시작(어디로 가세요? — 시안의 질문 흐름): 맨 위 큰 카드, 도시 사진 줄
 *  ② 작품으로 짜기(원래 AI 마법사): 지금 뜨는 작품 포스터 세 장 — 「작품을 고르는 길」 이라는 것이 그림으로 보이게
 *  ③ 담아 둔 곳으로(원래 직접 짜기): 장바구니 장소 사진, 비었으면 비었다고
 *  ②③ 은 나란히 같은 크기 타일(사진 세 장을 일자로) — 시트 높이를 줄이고, 둘이 대등한 갈래라는 것을 모양으로 말한다.
 * 로컬 개발 서버 + 시안이 켜졌을 때만 원래 시트 대신 뜬다(docs/project/plans/web-trip-first-prototypes.md).
 */
import type { CSSProperties, ReactNode } from 'react'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { homeStore } from '@/features/home/homeModel'
import { Pressable } from '@/features/route/ui/Press'
import { useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import { MockBadge, coverOf, useCities } from './protoParts'
import { color, heading, radius, shadow } from './protoTokens'
import { protoActions, say } from './protoState'
import './proto.css'

/** 시트 높이 — 내용(머리 + 큰 카드 + 타일 줄)에 맞춘다. 남는 바닥을 두지 않는다. */
const SHEET_HEIGHT = 424

/** 사진 세 장을 같은 크기로 나란히 — 포스터·장소 사진 묶음(S5: 비스듬히 겹친 장식 대신). */
function Strip({ urls, tall }: { urls: readonly (string | null)[]; tall?: boolean }) {
  return (
    <div aria-hidden style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 4 }}>
      {[0, 1, 2].map((i) => (
        <div key={i} style={{ aspectRatio: tall ? '2 / 3' : '1 / 1', borderRadius: radius.s, overflow: 'hidden', background: 'var(--system-gray5)' }}>
          {urls[i] && <img src={urls[i]!} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
        </div>
      ))}
    </div>
  )
}

function Tile({ art, title, caption, tag, onPress }: { art: ReactNode; title: string; caption: string; tag?: string; onPress: () => void }) {
  return (
    <Pressable plain className="proto-press" onClick={onPress} style={{ flex: 1, minWidth: 0, padding: 12, borderRadius: radius.l, background: 'var(--system-background)', boxShadow: shadow.card, textAlign: 'left' }}>
      <VStack spacing={10} align="stretch">
        {art}
        <VStack spacing={3} align="start">
          <HStack spacing={6}>
            <Txt as="h3" size={15} weight="heavy" lines={1} style={heading}>{title}</Txt>
            {tag && <span style={{ flex: 'none', fontSize: 12, fontWeight: 800, padding: '1px 6px', borderRadius: radius.s, background: 'color-mix(in srgb, var(--home-purple) 12%, transparent)', color: color.primaryText }}>{tag}</span>}
          </HStack>
          <Txt size={13} color="secondary" lines={2}>{caption}</Txt>
        </VStack>
      </VStack>
    </Pressable>
  )
}

export function ProtoForkSheet({ open, onClose, onAI, onManual }: { open: boolean; onClose: () => void; onAI: () => void; onManual: () => void }) {
  const router = useTabRouter()
  const cart = useCart()
  const works = useStore(homeStore, (s) => s.works)
  const { cities } = useCities()
  const saved = cart.items
  // 도시 목록이 오기 전에도 같은 자리를 차지하게 빈 칸 넷 — 받으면 사진으로 바뀌어 시트 높이가 튀지 않는다.
  const cityPhotos = cities.length > 0 ? cities.slice(0, 4).map((c) => ({ name: c.name, url: coverOf(c)?.imageUrl ?? null })) : ['1', '2', '3', '4'].map((k) => ({ name: k, url: null }))
  const posters = works.filter((w) => w.posterUrl).slice(0, 3).map((w) => w.posterUrl ?? null)
  const savedPhotos = saved.filter((i) => i.imageUrl).slice(0, 3).map((i) => i.imageUrl ?? null)
  const go = (fn: () => void) => () => {
    onClose()
    fn()
  }
  const cityCard: CSSProperties = { width: '100%', textAlign: 'left', padding: 16, borderRadius: radius.l, background: color.primaryFill, boxShadow: shadow.card, color: color.onPrimary }

  return (
    <Sheet open={open} onClose={onClose} dim={0.25} floating detent={SHEET_HEIGHT} radius={30} label={say('새 코스 만들기', 'New trip')}>
      <VStack spacing={14} align="stretch" style={{ padding: '8px 16px 18px' }}>
        <div aria-hidden style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)', alignSelf: 'center' }} />
        <HStack spacing={8}>
          <VStack spacing={2} align="start">
            <Txt as="h2" size={22} weight="heavy" style={heading}>{say('새 여행 코스', 'New trip')}</Txt>
            <Txt size={13} color="secondary">{say('어떻게 시작할까요?', 'How do you want to start?')}</Txt>
          </VStack>
          <Spacer />
          <MockBadge />
        </HStack>

        <Pressable
          plain
            className="proto-press"
          onClick={go(() => {
            router.closeCover()
            protoActions.plan()
          })}
          style={cityCard}
        >
          <VStack spacing={12} align="stretch">
            <HStack spacing={12}>
              <span aria-hidden style={{ width: 40, height: 40, flex: 'none', borderRadius: radius.m, display: 'grid', placeItems: 'center', background: 'rgba(255,255,255,0.18)' }}>
                <Icon name="mappin.and.ellipse" size={19} weight="semibold" color="white" />
              </span>
              <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
                <Txt as="h3" size={17} weight="heavy" style={{ ...heading, color: color.onPrimary }}>{say('도시로 시작', 'Start with a city')}</Txt>
                <Txt size={13} style={{ color: 'rgba(255,255,255,0.92)' }}>{say('어디로·며칠만 고르면 코스 완성', 'Pick where and for how long.')}</Txt>
              </VStack>
              <Icon name="chevron.right" size={14} weight="semibold" color="white" />
            </HStack>
            <HStack spacing={6} style={{ width: '100%' }}>
              {cityPhotos.map((c) => (
                <div key={c.name} aria-hidden style={{ position: 'relative', flex: 1, height: 54, borderRadius: radius.s, overflow: 'hidden', background: 'rgba(255,255,255,0.18)' }}>
                  {c.url && <img src={c.url} alt="" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
                  <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to top, rgba(0,0,0,0.55), transparent 65%)' }} />
                  {c.url && <span style={{ position: 'absolute', left: 7, bottom: 5, fontSize: 12, fontWeight: 700, color: color.onPhoto }}>{c.name}</span>}
                </div>
              ))}
            </HStack>
          </VStack>
        </Pressable>

        <HStack spacing={10} align="stretch" style={{ width: '100%' }}>
          <Tile
            art={<Strip urls={posters} tall />}
            title={say('작품으로', 'From shows')}
            tag="AI"
            caption={say('좋아하는 작품을 고르면 촬영지를 이어 줘요', 'Pick shows you love. AI links their spots.')}
            onPress={go(onAI)}
          />
          <Tile
            art={<Strip urls={savedPhotos} />}
            title={say('담은 곳으로', 'From saved')}
            caption={saved.length > 0 ? say(`담아 둔 ${saved.length}곳에서 골라 직접 짜요`, `Build it from your ${saved.length} saved places`) : say('담아 둔 곳에서 하나씩 넣어요', 'Add places you saved, one by one')}
            onPress={go(onManual)}
          />
        </HStack>
      </VStack>
    </Sheet>
  )
}
