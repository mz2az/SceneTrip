/*
 * 웹에만 있는 문구의 영어 — 영어 표의 정본은 iOS `Localizable.strings` 이고 `npm run gen` 이 en.generated.json 으로 옮긴다.
 * iOS 에 아직 없는 웹 전용 문구만 여기 둔다. 같은 열쇠가 생성표에 생기면 생성표가 이긴다(iOS 문구를 따른다).
 */
export const webEnglish: Readonly<Record<string, string>> = {
  // MZ2AZ-532 촬영지 상세 「길찾기」
  '%@ 가는 길': 'Trip to %@',
  // MZ2AZ-551 글쓰기 본문 칸의 이름
  '본문': 'Post body',
  // 새 판 배포 알림(2026-11-09)
  '새 버전이 나왔어요': 'A new version is available',
  '새로고침하면 최신 버전으로 바뀌어요.': 'Reload to get the latest version.',
  '나중에': 'Later',
  '새로고침': 'Reload',
  // 방금 만든 코스를 저장 없이 나갈 때(2026-11-09)
  '저장하지 않고 나갈까요?': 'Leave without saving?',
  '방금 만든 코스가 지워져요.': 'The course you just made will be deleted.',
  '나가기': 'Leave',
}
