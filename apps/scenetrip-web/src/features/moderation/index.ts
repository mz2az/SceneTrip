/*
 * 신고·차단 — iOS Models/Moderation.swift · Reviews/ModerationViews.swift (App Store 심사 지침 1.2).
 * 소유: 커뮤니티 담당. 글(커뮤니티)·후기(리뷰)·홈 「커뮤니티 지금」·마이페이지 「차단한 사용자」가 같이 쓴다.
 *
 * ── 사용법 ───────────────────────────────────────────────────────────────────────────────
 * 1) 목록 화면(후기 시트, 커뮤니티 탭 …)이 컨트롤러를 하나 들고 시트 자리를 한 번 단다:
 *
 *      const moderation = useModerationController()
 *      …
 *      <ModerationSheets controller={moderation} onBlocked={(t) => { if (t.kind === 'post') void community.blocked(t.id) }} />
 *
 * 2) 남의 글 줄에 「⋯」 를 둔다(자기 글·서버에 없는 글에는 두지 않는다 — `showsMenu`):
 *
 *      {showsMenu(review.isMine) && (
 *        <ModerationMenu target={reviewTarget(review.id)} canBlock={canBlock(review.isMine, review.author != null)} controller={moderation} />
 *      )}
 *
 *    줄 `onClick` 안에 두지 말고 형제로 둔다 — 안에 두면 「⋯」 를 눌러도 줄이 열린다(메뉴는 stopPropagation 하지만 시각적 겹침도 피한다).
 *
 * 3) 어디서든 차단·풀기가 일어나면 받아 둔 리뷰·방문자 사진을 비우고 다시 받는다:
 *
 *      useModerationChange(() => { forgetVisitorPhotos(); void reload() })
 *
 * 4) 리뷰·후기·닉네임·코스 이름 저장이 `400 TEXT_NOT_ALLOWED` 면 안내를 띄운다:
 *
 *      if (isTextNotAllowed(error)) setAlert(textNotAllowedMessage())
 *
 * 5) 마이페이지 「차단한 사용자」 — 화면(프로필 담당)은 스토어만 쓴다:
 *
 *      const blocked = useBlockedUsers()         // items·phase('loading'|'loaded'|'failed')·unblocking·message
 *      useEffect(() => { void blocked.reload() }, [])
 *      await blocked.unblock(user)               // 푼 뒤 moderationEvents 가 울린다(3)
 *
 * 6) 신고 시트가 떠 있는 동안 밑의 화면은 로그인 시트를 올리지 않는다 — 로그인 시트(프로필 담당)가 `reportSheetPresence.open` 을 본다.
 * ─────────────────────────────────────────────────────────────────────────────────────────
 */
export { type BlockedPhase, type BlockedUsersState, type BlockedUsersStore, type UseBlockedUsers, createBlockedUsersStore, useBlockedUsers } from './blockedUsers'
export { type ModerationClient, serverModerationClient } from './client'
export {
  type ModerationController,
  type ModerationDeps,
  type ModerationState,
  ReportSheetPresence,
  createModerationController,
  reportSheetPresence,
  useModerationController,
  useModerationState,
} from './controller'
export { type ModerationEvents, createModerationEvents, moderationEvents, useModerationChange, useModerationVersion } from './events'
export { ModerationMenu, ModerationSheets, ReportSheet } from './ModerationViews'
export * from './rules'
