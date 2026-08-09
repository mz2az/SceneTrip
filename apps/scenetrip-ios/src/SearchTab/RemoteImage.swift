import SwiftUI

/// 서버가 준 URL 로 이미지를 그린다. 실패하면 자리표시자로 떨어진다.
///
/// 이미지 주소는 우리 것이 아니다 — TMDB 포스터, 구글/위키미디어 장소 사진처럼
/// 수집 시점의 외부 URL 이 그대로 온다(`ContentSummary.posterUrl`,
/// `PlaceSummary.imageUrl`). 그래서 **깨지는 것을 정상 경로로 다룬다.** 링크가 죽었거나
/// 기기가 오프라인이면 자리표시자가 남고, 목록 자체는 그대로 읽힌다.
///
/// **`Color.clear` 를 깔고 그 위에 이미지를 얹는다.** 이미지에 직접 `scaledToFill` 을
/// 걸고 안에서 자르면, 바깥에서 `.frame(...)` 을 주기 **전에** 잘리므로 원본 비율대로
/// 커진 그림이 프레임을 넘쳐 아래 글씨를 덮는다(실측 — 포스터가 세로로 길어서 장면
/// 카드의 제목·설명을 가렸다). 빈 색이 제안된 크기를 받고 그 위에서 채운 뒤 자르면
/// 프레임이 어디서 정해지든 결과가 같다.
///
/// 캐시는 `URLSession` 기본 것에 맡긴다. 목록이 길어져 스크롤이 버벅이면 그때
/// 다시 본다 — 지금 데이터로는 장소 155 개이고 화면에 한 번에 예닐곱 줄이다.
struct RemoteImage: View {
    let url: String?
    let symbol: String

    var body: some View {
        Color.clear
            .overlay { content }
            .clipped()
    }

    @ViewBuilder private var content: some View {
        if let url, let parsed = URL(string: url) {
            AsyncImage(url: parsed) { phase in
                switch phase {
                case let .success(image):
                    image.resizable().scaledToFill()
                case .empty:
                    placeholder.overlay(ProgressView().scaleEffect(0.6))
                default:
                    placeholder
                }
            }
        } else {
            placeholder
        }
    }

    private var placeholder: some View {
        Color(.systemGray5)
            .overlay(Image(systemName: symbol).foregroundStyle(.secondary))
    }
}
