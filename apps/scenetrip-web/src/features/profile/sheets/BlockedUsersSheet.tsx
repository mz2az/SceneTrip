/*
 * 차단한 사용자 — iOS `BlockedUsersView` (ProfileTab/BlockedUsersView.swift, MZ2AZ-461). 내가 차단한 사람과 차단한 때, 줄마다 「차단 풀기」.
 * 가입한 사람만 연다(차단은 로그인해야 한다). 풀면 그 사람의 리뷰·후기가 다음에 받을 때부터 다시 보인다.
 * 목록·풀기는 커뮤니티 담당의 `features/moderation` 스토어(`useBlockedUsers`)를 쓴다.
 */
import { useEffect } from 'react'
import { Alert } from '@/design/overlay'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { useBlockedUsers } from '@/features/moderation/blockedUsers'
import { tr } from '@/i18n'
import { abbreviatedDate } from '../dates'
import { useAccount } from '../auth/account'
import { ContentUnavailable } from '../ui/ContentUnavailable'
import { GlassSheet, SheetBody } from '../ui/GlassSheet'
import { ListSection } from '../ui/ListUI'

export function BlockedUsersSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { signedIn } = useAccount()
  const blocked = useBlockedUsers()
  useEffect(() => {
    if (open) void blocked.reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])
  // 로그아웃·탈퇴하면 남의 목록을 남겨 두지 않는다.
  useEffect(() => {
    if (open && !signedIn) onClose()
  }, [open, signedIn, onClose])

  return (
    <GlassSheet open={open} onClose={onClose} detent="medium" label={tr('차단한 사용자')}>
      {/* NavigationStack 의 inline 제목 + 왼쪽 「닫기」 */}
      <HStack justify="center" style={{ position: 'relative', padding: '14px 12px 6px', minHeight: 56, flex: 'none' }}>
        <Pressable
          plain
          onClick={onClose}
          style={{ position: 'absolute', left: 12, top: 8, height: 40, padding: '0 16px', borderRadius: 20, background: 'rgba(255,255,255,0.6)', display: 'grid', placeItems: 'center' }}
        >
          <Txt size={17} color="accent">{tr('닫기')}</Txt>
        </Pressable>
        <Txt size={17} weight="semibold">{tr('차단한 사용자')}</Txt>
      </HStack>

      {blocked.phase === 'loading' ? (
        <VStack align="center" justify="center" style={{ flex: 1 }}>
          <Spinner />
        </VStack>
      ) : blocked.phase === 'failed' ? (
        <VStack align="center" justify="center" spacing={10} style={{ flex: 1 }}>
          <Txt size={15} weight="medium">{tr('차단 목록을 불러오지 못했어요')}</Txt>
          <Pressable plain onClick={() => void blocked.reload()} style={{ minHeight: 44 }}>
            <Txt size={17} color="accent">{tr('다시 시도')}</Txt>
          </Pressable>
        </VStack>
      ) : blocked.items.length === 0 ? (
        <ContentUnavailable title={tr('차단한 사용자가 없어요')} symbol="person.crop.circle.badge.checkmark" description={tr('리뷰나 여행후기의 「⋯」 에서 작성자를 차단할 수 있어요')} />
      ) : (
        <SheetBody>
          <div style={{ padding: '0 16px 24px' }}>
            <ListSection footer={tr('차단한 사람의 리뷰와 여행후기는 보이지 않아요. 차단한 사람에게는 알리지 않아요.')} style={{ marginTop: 8 }}>
              {blocked.items.map((user) => (
                <div key={user.id} className="ls-row" style={{ minHeight: 52, '--sep-inset': '16px' } as React.CSSProperties}>
                  <HStack spacing={12} style={{ width: '100%', minHeight: 44 }}>
                    <VStack spacing={2} style={{ flex: 1, minWidth: 0 }}>
                      <Txt size={15} weight="medium" lines={1}>{user.nickname}</Txt>
                      <Txt size={12} color="secondary">{tr('%@ 차단', abbreviatedDate(user.blockedAt))}</Txt>
                    </VStack>
                    {blocked.unblocking.has(user.id) ? (
                      <Spinner />
                    ) : (
                      <Pressable plain aria-label={tr('%@ 차단 풀기', user.nickname)} onClick={() => void blocked.unblock(user)} style={{ minHeight: 44 }}>
                        <Txt size={15} weight="semibold" color="accent">{tr('차단 풀기')}</Txt>
                      </Pressable>
                    )}
                  </HStack>
                </div>
              ))}
            </ListSection>
          </div>
        </SheetBody>
      )}
      <Alert open={blocked.message !== null} title={blocked.message ?? ''} buttons={[{ label: tr('확인'), role: 'cancel' }]} onClose={blocked.dismissMessage} />
    </GlassSheet>
  )
}
