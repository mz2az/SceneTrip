/*
 * 웹에만 있는 문구의 영어 — 영어 표의 정본은 iOS `Localizable.strings` 이고 `npm run gen` 이 en.generated.json 으로 옮긴다.
 * iOS 에 아직 없는 웹 전용 문구만 여기 둔다. 같은 열쇠가 생성표에 생기면 생성표가 이긴다(iOS 문구를 따른다).
 */
export const webEnglish: Readonly<Record<string, string>> = {
  // MZ2AZ-532 촬영지 상세 「길찾기」
  '%@ 가는 길': 'Trip to %@',
  '로그인하면 바로 길안내를 시작해요': 'Sign in to start directions right away',
}
