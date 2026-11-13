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
  // MZ2AZ-566 길찾기 단계 목록 — 버스 종류는 제공자가 한국어로만 준다
  '지하철': 'Subway',
  '간선버스': 'Blue bus (trunk)',
  '지선버스': 'Green bus (branch)',
  '마을버스': 'Village bus',
  '광역버스': 'Red bus (express)',
  '급행버스': 'Express bus',
  '순환버스': 'Circular bus',
  '공항버스': 'Airport bus',
  '버스': 'Bus',
  '%d분 걷기': 'Walk %d min',
  '%@까지': 'to %@',
  '탈 곳': 'Board at',
  '내릴 곳': 'Get off at',
  '도착 · %@': 'Arrive · %@',
  '도보': 'Walk',
  '경로': 'Route',
  // MZ2AZ-567 단계를 누르면 그 구간으로
  '지도에서 보기': 'Show on map',
}
