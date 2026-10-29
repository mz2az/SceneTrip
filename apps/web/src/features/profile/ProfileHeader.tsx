/*
 * 피노와 계정 카드 — 비회원이면 로그인 단추, 로그인했으면 닉네임(누르면 바꾸기)과 메일 (iOS ProfileTabView `header`, MZ2AZ-336·363).
 * 닉네임이 남에게 보이는 이름이다. 옛 서버는 닉네임이 없어 전처럼 계정 이름을 보인다.
 */
import { Icon } from '@/design/Icon'
import { PinoMascot } from '@/design/PinoMascot'
import { Pressable, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { accountActions, useAccount } from './auth/account'

const PINO_WIDTH = 96

export function ProfileHeader({ onEditNickname }: { onEditNickname: () => void }) {
  const { signedIn, me, busy } = useAccount()
  return (
    <VStack align="center" spacing={8} style={{ padding: '12px 0' }}>
      <PinoMascot width={PINO_WIDTH} />
      {signedIn ? (
        <>
          {me?.nickname ? (
            <Pressable plain onClick={onEditNickname} aria-label={tr('닉네임 바꾸기')} title={me.nickname}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
                <Txt size={17} weight="semibold" style={{ lineHeight: '22px' }}>{me.nickname}</Txt>
                <Icon name="pencil" size={12} weight="semibold" color="secondary" />
              </span>
            </Pressable>
          ) : (
            <Txt size={17} weight="semibold" style={{ lineHeight: '22px' }}>{me?.displayName ?? tr('여행자')}</Txt>
          )}
          {me?.email && <Txt size={12} color="secondary">{me.email}</Txt>}
        </>
      ) : (
        <>
          <Txt size={17} weight="semibold" style={{ lineHeight: '22px' }}>{tr('비회원으로 여행 중')}</Txt>
          <Txt size={12} color="secondary">{tr('로그인하면 코스와 찜이 계정에 저장돼요')}</Txt>
          <Pressable
            plain
            disabled={busy}
            onClick={accountActions.openSignIn}
            style={{ marginTop: 4, padding: '6.5px 22px', borderRadius: 20, background: 'var(--accent)', opacity: busy ? 0.5 : 1 }}
          >
            <Txt size={15} weight="semibold" color="white" style={{ lineHeight: '19px' }}>{tr('로그인')}</Txt>
          </Pressable>
        </>
      )}
    </VStack>
  )
}
