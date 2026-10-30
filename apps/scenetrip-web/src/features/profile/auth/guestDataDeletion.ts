/*
 * 비회원 기록 삭제 — iOS `GuestDataDeletion` (Auth/GuestDataDeletion.swift, MZ2AZ-471).
 * 회원 탈퇴와 달리 토큰을 보내거나 세션을 갱신하지 않는다. 가입한 계정의 설치본이 부르면 서버가 `401 SESSION_REQUIRED` 로 막는다.
 */
import { ApiFailure } from '@/api/failure'
import { tr } from '@/i18n'

export type DeviceDeleteOutcome = 'deleted' | 'sessionRequired' | 'failed'

/** 「이 기기의 기록 지우기」 줄은 비회원에게만 보인다. */
export const isGuestDeleteVisible = (signedIn: boolean): boolean => !signedIn

export async function deleteGuestData(request: () => Promise<void>): Promise<DeviceDeleteOutcome> {
  try {
    await request()
    return 'deleted'
  } catch (error) {
    if (error instanceof ApiFailure && error.statusCode === 401 && error.apiCode === 'SESSION_REQUIRED') return 'sessionRequired'
    return 'failed'
  }
}

/** 결과 → 알림창 문구 (ProfileDeviceDataSection). */
export function deviceDeleteMessage(outcome: DeviceDeleteOutcome): string {
  switch (outcome) {
    case 'deleted': return tr('이 기기의 기록을 지웠어요.')
    case 'sessionRequired': return tr('가입한 계정의 기록이에요. 로그인한 뒤 마이페이지에서 회원 탈퇴를 이용해 주세요.')
    case 'failed': return tr('기록을 지우지 못했어요. 잠시 뒤 다시 시도해 주세요.')
  }
}
