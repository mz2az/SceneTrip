/*
 * 닉네임 정하기 — iOS `NicknameView` (Reviews/NicknameView.swift, MZ2AZ-363). 로그인 직후 한 번 묻는 것(welcome)과
 * 마이페이지에서 바꾸는 것(edit)이 같은 화면이다.
 *
 * 닉네임은 리뷰·후기에 작성자로 보이는 이름이다. 소셜 로그인 이름은 실명일 수 있어 남에게 보이지 않는다 — 그래서 서버가
 * `여행자12345` 꼴을 미리 붙여 두고, 여기서 바꾸게 한다. 건너뛰면 그 이름 그대로 쓴다.
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { PinoMascot } from '@/design/PinoMascot'
import { HStack, Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { textNotAllowedMessage } from '@/features/moderation/rules'
import { tr } from '@/i18n'
import { GlassSheet } from '../ui/GlassSheet'
import { accountActions, useAccount } from './account'
import { NICKNAME_MAX, hintFor, nicknameLength, normalized, problemIn } from './nicknameRules'

const PURPLE = 'var(--home-purple)'
/** 시트 높이 — `.presentationDetents([.height(480), .large])` */
const DETENT_HEIGHT = 480

export type NicknameMode = 'welcome' | 'edit'

/** 저장 결과 → 입력란 아래에 적을 문구. 성공이면 null. */
export function serverMessageFor(outcome: 'saved' | 'invalid' | 'taken' | 'textNotAllowed' | 'failed'): string | null {
  switch (outcome) {
    case 'saved': return null
    case 'invalid': return tr('쓸 수 없는 닉네임이에요. 2~16자, 한글·영문·숫자·밑줄(_)만 쓸 수 있어요')
    case 'taken': return tr('이미 쓰는 닉네임입니다')
    case 'textNotAllowed': return textNotAllowedMessage()
    case 'failed': return tr('저장하지 못했어요. 잠시 뒤 다시 해 주세요')
  }
}

/** `.lineLimit(1).minimumScaleFactor(0.65)` — 칸 폭에 맞춰 글자를 minScale 배까지 줄이고, 그래도 넘치면 말줄임. */
function FitText({ size, minScale, children, testId }: { size: number; minScale: number; children: string; testId?: string }) {
  const box = useRef<HTMLDivElement>(null)
  const [scale, setScale] = useState(1)
  useLayoutEffect(() => {
    const el = box.current
    const text = el?.firstElementChild as HTMLElement | null
    if (!el || !text) return
    const natural = text.scrollWidth / scale
    if (el.clientWidth <= 0 || natural <= 0) return
    setScale(Math.max(minScale, Math.min(1, el.clientWidth / natural)))
  }, [children, minScale, scale])
  return (
    <div ref={box} style={{ minWidth: 0, width: '100%' }}>
      <Txt size={size * scale} weight="semibold" lines={1} data-testid={testId}>{children}</Txt>
    </div>
  )
}

export function NicknameForm({ mode, onDone }: { mode: NicknameMode; onDone: () => void }) {
  const { me, epoch } = useAccount()
  const [text, setText] = useState(me?.nickname ?? '')
  const [saving, setSaving] = useState(false)
  const [serverMessage, setServerMessage] = useState<string | null>(null)
  const [focused, setFocused] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const problem = problemIn(text)
  const unchanged = normalized(text) === me?.nickname
  /** 지금 닉네임 그대로면 규칙을 따지지 않는다 — 자동 닉네임이 미리 채워져 있는데 「고를 수 없는 이름」 이라고 빨갛게 적으면 안 된다. */
  const shownProblem = unchanged ? null : problem
  const preview = normalized(text) === '' ? tr('여행자') : normalized(text)
  const countNow = nicknameLength(text)

  /** 마이페이지에서는 바뀐 것이 있어야 저장한다. 처음 물을 때는 미리 채워진 자동 닉네임 그대로도 누를 수 있다 — 건너뛰기와 같다. */
  const canSave = !saving && (unchanged ? mode === 'welcome' && text !== '' : problem === null)

  const cancel = () => {
    if (saving) return
    if (mode === 'welcome') accountActions.skipNickname()
    onDone()
  }

  const save = async () => {
    if (!canSave) return
    if (unchanged) {
      // 자동 닉네임 그대로 시작한다 — 서버에 보낼 것이 없다.
      accountActions.skipNickname()
      onDone()
      return
    }
    setSaving(true)
    input.current?.blur()
    const outcome = await accountActions.setNickname(text)
    setSaving(false)
    if (outcome === 'saved') onDone()
    else setServerMessage(serverMessageFor(outcome))
  }

  // 저장하는 사이 계정이 바뀌었으면(로그아웃·세션 소실) 이 시트의 일은 끝났다.
  const startEpoch = useRef(epoch)
  useEffect(() => {
    if (epoch !== startEpoch.current) onDone()
  }, [epoch, onDone])

  const hintRed = serverMessage !== null || (text !== '' && shownProblem !== null)
  return (
    <>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack spacing={22} style={{ padding: '24px 24px' }}>
          <HStack align="start" spacing={8}>
            <VStack spacing={8} style={{ flex: 1 }}>
              <Txt as="h2" size={22} weight="bold" kerning={-0.75} style={{ lineHeight: '27px' }}>
                {mode === 'welcome' ? tr('닉네임을 정해 주세요') : tr('닉네임 바꾸기')}
              </Txt>
              <Txt as="p" size={15} color="secondary" kerning={-0.3} style={{ lineHeight: '20px' }}>
                {tr('리뷰와 후기에 이 이름으로 보여요. 로그인 계정의 이름은 다른 사람에게 보이지 않습니다.')}
              </Txt>
            </VStack>
            <Pressable
              plain
              disabled={saving}
              aria-label={tr('닫기')}
              onClick={cancel}
              style={{ width: 44, height: 44, borderRadius: 22, background: 'var(--secondary-grouped-background)', display: 'grid', placeItems: 'center', flex: 'none' }}
            >
              <Icon name="xmark" size={28} weight="semibold" />
            </Pressable>
          </HStack>

          <HStack spacing={16} style={{ padding: 16, background: 'var(--secondary-grouped-background)', ...rr(20) }}>
            <PinoMascot width={66} alive={false} />
            <VStack spacing={6} style={{ flex: 1, minWidth: 0 }}>
              <Txt size={12} color="secondary">{tr('작성자 이름 미리보기')}</Txt>
              <FitText size={20} minScale={0.65} testId="nickname-preview">{preview}</FitText>
            </VStack>
          </HStack>

          <VStack spacing={10}>
            <HStack justify="between">
              <Txt size={15} weight="semibold">{tr('닉네임')}</Txt>
              <Txt size={12} mono color={shownProblem === 'tooLong' ? 'red' : 'secondary'}>{`${countNow}/${NICKNAME_MAX}`}</Txt>
            </HStack>
            <HStack
              spacing={8}
              style={{
                padding: '0 14px', minHeight: 56, background: 'var(--secondary-grouped-background)', ...rr(14),
                boxShadow: focused ? `inset 0 0 0 1.5px ${PURPLE}` : undefined,
              }}
            >
              <input
                ref={input}
                className="st-input"
                data-testid="nickname-input"
                value={text}
                disabled={saving}
                placeholder={tr('닉네임')}
                autoCapitalize="off"
                autoCorrect="off"
                spellCheck={false}
                enterKeyHint="done"
                onFocus={() => setFocused(true)}
                onBlur={() => setFocused(false)}
                onChange={(e) => {
                  setText(e.target.value)
                  setServerMessage(null)
                }}
                onKeyDown={(e) => e.key === 'Enter' && void save()}
              />
              {text !== '' && (
                <Pressable
                  plain
                  aria-label={tr('입력 지우기')}
                  onClick={() => {
                    setText('')
                    setServerMessage(null)
                    input.current?.focus()
                  }}
                  style={{ width: 32, height: 44, display: 'grid', placeItems: 'center' }}
                >
                  <Icon name="xmark.circle.fill" size={17} color="secondary" />
                </Pressable>
              )}
            </HStack>
            <Txt as="div" size={13} color={hintRed ? 'red' : 'secondary'} style={{ lineHeight: '16px' }}>
              {serverMessage ?? hintFor(text === '' ? null : shownProblem)}
            </Txt>
          </VStack>
        </VStack>
      </div>

      {/* safeAreaInset(.bottom) — 눌러서 저장 */}
      <VStack spacing={6} style={{ padding: '14px 24px calc(6px + var(--safe-bottom))', background: 'var(--system-background)', borderTop: '0.5px solid var(--separator)', flex: 'none' }}>
        <Pressable
          plain
          disabled={!canSave}
          onClick={() => void save()}
          style={{ width: '100%', minHeight: 52, borderRadius: 26, background: PURPLE, opacity: canSave ? 1 : 0.4, padding: '15px 16px' }}
        >
          <HStack spacing={8} justify="center">
            {saving && <Spinner size={18} />}
            <Txt size={17} weight="semibold" color="white">{mode === 'welcome' ? tr('이 닉네임으로 시작') : tr('저장')}</Txt>
          </HStack>
        </Pressable>
        <Pressable plain disabled={saving} onClick={cancel} style={{ width: '100%', minHeight: 44, textAlign: 'center' }}>
          <Txt size={15} color="secondary">{mode === 'welcome' ? tr('건너뛰기') : tr('취소')}</Txt>
        </Pressable>
      </VStack>
    </>
  )
}

/** 로그인 직후 한 번 — `nicknameSheet()` (AuthStore.swift) */
export function NicknameWelcomeSheet() {
  const { askingNickname, busy } = useAccount()
  return (
    <GlassSheet
      open={askingNickname}
      onClose={accountActions.dismissNicknameBySwipe}
      detent={DETENT_HEIGHT}
      dismissDisabled={busy}
      zIndex={60}
      background="var(--grouped-background)"
      label={tr('닉네임을 정해 주세요')}
    >
      <NicknameForm mode="welcome" onDone={accountActions.closeNickname} />
    </GlassSheet>
  )
}

/** 마이페이지에서 바꾸기. */
export function NicknameEditSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <GlassSheet open={open} onClose={onClose} detent={DETENT_HEIGHT} zIndex={60} background="var(--grouped-background)" label={tr('닉네임 바꾸기')}>
      <NicknameForm mode="edit" onDone={onClose} />
    </GlassSheet>
  )
}
