/*
 * SwiftUI `Menu { Button… } label: { … }` — 단추를 누르면 그 아래에 선택지가 뜨는 작은 메뉴. 고른 것에는 체크가 붙는다.
 * 시트 안(스크롤·overflow 가 잘린 곳)에서도 보이게 앱 프레임의 오버레이 루트로 포탈해 단추 위치에 맞춘다.
 */
import { type ReactNode, useContext, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from '@/design/Icon'
import { OverlayRootContext } from '@/design/overlay'
import { Txt } from '@/design/primitives'
import { Pressable } from './Press'

export function ChoiceMenu<T extends string | number>({ choices, current, onPick, label, children }: {
  choices: ReadonlyArray<{ value: T; label: string }>
  current: T
  onPick: (value: T) => void
  label: string
  children: ReactNode
}) {
  const root = useContext(OverlayRootContext)
  const button = useRef<HTMLButtonElement>(null)
  const [at, setAt] = useState<{ right: number; top: number; flip: boolean } | null>(null)

  useEffect(() => {
    if (!at) return
    const close = () => setAt(null)
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [at])

  const open = () => {
    const b = button.current
    const frame = root?.getBoundingClientRect()
    if (!b || !frame) return
    const r = b.getBoundingClientRect()
    const below = frame.bottom - r.bottom
    setAt({ right: frame.right - r.right, top: r.bottom - frame.top + 6, flip: below < choices.length * 46 + 16 })
  }

  return (
    <>
      <Pressable ref={button} plain onClick={open} aria-label={label} aria-haspopup="menu">{children}</Pressable>
      {at && root && createPortal(
        <div style={{ position: 'absolute', inset: 0, zIndex: 90 }} onClick={() => setAt(null)}>
          <div
            role="menu"
            style={{
              position: 'absolute', right: at.right, ...(at.flip ? { bottom: `calc(100% - ${at.top - 12}px)` } : { top: at.top }),
              minWidth: 200, maxHeight: '60%', overflowY: 'auto', borderRadius: 14, padding: '6px 0',
              background: 'rgba(250,250,250,0.94)', backdropFilter: 'blur(30px)', WebkitBackdropFilter: 'blur(30px)', boxShadow: '0 8px 30px rgba(0,0,0,0.2)',
            }}
          >
            {choices.map((c) => (
              <Pressable
                key={String(c.value)}
                plain
                role="menuitem"
                onClick={() => {
                  setAt(null)
                  onPick(c.value)
                }}
                style={{ width: '100%', padding: '12px 16px', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 24 }}
              >
                <Txt size={17}>{c.label}</Txt>
                {c.value === current && <Icon name="checkmark" size={15} weight="semibold" />}
              </Pressable>
            ))}
          </div>
        </div>,
        root,
      )}
    </>
  )
}
