/*
 * 앱 안 브라우저 띠 — 정본: docs/project/plans/web-ad-landing.md §3.
 * 「더 담기」 와 내 지도의 머리줄 아래 얇은 띠: 「이 창을 닫으면 사라져요」 + 「브라우저에서 열기」(담은 뒤 시트 ② 의 넘기기와 같다).
 * 앱 안 브라우저(in_app_android·in_app_ios)가 아니면 그리지 않는다.
 */
import { type JSX, useState } from 'react'
import { track, webEvent } from '@/analytics'
import { Icon } from '@/design/Icon'
import { Txt } from '@/design/primitives'
import { color } from '@/features/proto/protoTokens'
import { Pressable } from '@/features/route/ui/Press'
import { say, useLang } from '@/i18n'
import { useCart } from '@/state/cart'
import { handoff } from './handoff'
import { currentKeepEnv } from './installPrompt'
import { KEEP_MAX, type KeepEnv, handoffHref, keepUrl } from './keepRules'
import { useKeep } from './keepStore'

/** 넘기는 주소에 실을 작품들 — 이번에 담은 작품(keepStore) 먼저, 그다음 담은 촬영지의 작품들. */
export function keepContentIds(fromSheet: readonly number[], items: readonly { sourceContentId?: number | null }[]): number[] {
  const ids: number[] = []
  for (const id of [...fromSheet, ...items.map((i) => i.sourceContentId ?? null)]) {
    if (typeof id === 'number' && !ids.includes(id)) ids.push(id)
  }
  return ids.slice(0, KEEP_MAX)
}

export function InAppBand(): JSX.Element | null {
  useLang()
  const [env] = useState<KeepEnv>(currentKeepEnv)
  const { contentIds } = useKeep()
  const cart = useCart()
  if (env !== 'in_app_android' && env !== 'in_app_ios') return null

  const open = () => {
    const href = handoffHref(env, keepUrl(window.location.origin, keepContentIds(contentIds, cart.items), [...cart.poiIds]))
    if (!href) return
    track(webEvent.keepPrompt(env, 'open_browser'))
    handoff.open(href)
  }

  return (
    <div
      role="note"
      style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '0 6px 0 14px', minHeight: 44, flex: 'none',
        background: 'color-mix(in srgb, var(--system-orange, #ff9500) 14%, var(--system-background))',
        borderBottom: '0.5px solid var(--separator)',
      }}
    >
      <Icon name="exclamationmark.triangle.fill" size={14} color="var(--system-orange, #ff9500)" />
      <Txt size={13} weight="semibold" lines={2} style={{ flex: 1, minWidth: 0 }}>{say('이 창을 닫으면 사라져요', 'Saved only in this window')}</Txt>
      <Pressable plain onClick={open} style={{ flex: 'none', minHeight: 44, padding: '0 10px', display: 'grid', placeItems: 'center' }}>
        <Txt size={13} weight="bold" style={{ color: color.primaryText }}>{say('브라우저에서 열기', 'Open in browser')}</Txt>
      </Pressable>
    </div>
  )
}
