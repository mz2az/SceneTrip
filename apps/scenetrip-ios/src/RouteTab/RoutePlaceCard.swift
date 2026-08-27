import SwiftUI

/// 핀을 눌렀을 때 뜨는 **정보 카드** (2026-08-27).
///
/// 프로토타입 v6 가 지도 왼쪽 아래에 띄우던 카드를 옮긴 것이다. 사진·영업시간·
/// 리뷰·별점을 보여 주고, 더 보려면 네이버 앱으로 넘긴다.
///
/// ## 왜 앱 안에서 다 보여 주지 않나
///
/// 네이버 지도가 가진 것(메뉴판·예약·최신 리뷰 전문)을 우리가 옮겨 담을 수 없고,
/// 옮겨 담아도 낡는다. **판단에 필요한 만큼만** 보여 주고 나머지는 원본으로 보낸다 —
/// 별점 4.95 에 리뷰 115건이면 들어갈지 말지는 정해진다.
///
/// ## 못 찾는 것도 답이다
///
/// 우리 POI 자료는 TMAP 것이라 네이버에 없는 가게가 있다. 그때 빈 카드를 띄우지
/// 않고 **왜 없는지**를 적는다 — 네이버에 없다고 나쁜 가게가 아니다.
struct RoutePlaceCard: View {
    let place: RouteGuide.Place

    /// 「경로에 추가」. 주면 버튼이 뜬다 — 지도에서 고양이를 눌러 연 카드에서
    /// 바로 코스에 담는다(2026-08-27 사용자 지적: 시트의 ⊕ 까지 돌아가야 했다).
    var onAdd: (() -> Void)?

    /// 이미 코스에 들어 있는가. 그러면 추가 단추 대신 **체크 표시**가 뜬다 —
    /// 또 누르게 두면 같은 곳이 두 번 담긴다.
    var added = false

    /// 「여기로 길찾기」. **여행 중 화면에서만 준다** — 계획 화면에서는 갈아탈 경로
    /// 자체가 없다. 주면 버튼이 뜬다.
    var onReroute: (() -> Void)?

    let onClose: () -> Void

    @State private var card: RouteGuide.Card?
    @State private var loading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if loading {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("네이버에서 찾는 중입니다")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(14)
            } else if let card, card.found {
                found(card)
            } else {
                missing
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
        )
        // **핀을 갈아탈 때마다 다시 받는다.** `.task {}` 로만 두면 SwiftUI 가 뷰를
        // 재사용할 때 한 번만 돌아서, 다른 고양이를 눌러도 앞 가게 정보가 그대로
        // 남는다(2026-08-27 사용자 지적 — 빨간 고양이는 바뀌는데 카드가 안 바뀜).
        .task(id: place.id) {
            loading = true
            card = nil
            await load()
        }
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(place.name).font(.headline)
                if let meters = place.distanceMeters {
                    Text("\(meters) m").font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            // **더 보려면 네이버로** — 메뉴·예약·리뷰 전문은 그쪽에 있다.
            // 맨 아래 큰 단추였는데 이름 옆 아이콘으로 줄였다 — 그 줄이 통째로
            // 빠져야 정보가 한 화면에 다 보인다(2026-08-27 사용자 지적).
            if let link = card?.naverUrl, let url = URL(string: link) {
                // 아이콘만 두면 「더 보기」인지 아무도 모른다(2026-08-27 사용자
                // 지적) — 원래 아래에 있던 단추를 글자째 줄인 미니 캡슐이다.
                Link(destination: url) {
                    HStack(spacing: 3) {
                        Text("네이버 더보기").font(.caption2.weight(.semibold))
                        Image(systemName: "arrow.up.right").font(.system(size: 9, weight: .semibold))
                    }
                    .padding(.horizontal, 8).padding(.vertical, 5)
                    .background(
                        Capsule().fill(Color(red: 0.02, green: 0.78, blue: 0.35).opacity(0.12))
                    )
                    .foregroundStyle(Color(red: 0.02, green: 0.60, blue: 0.28))
                }
            }
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 8)
    }

    @ViewBuilder
    private func found(_ card: RouteGuide.Card) -> some View {
        if !card.images.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(card.images.prefix(5), id: \.self) { url in
                        RemoteImage(url: url, symbol: "photo")
                            .frame(width: 92, height: 70)
                            .clipShape(.rect(cornerRadius: 8))
                    }
                }
                .padding(.horizontal, 14)
            }
            .padding(.bottom, 10)
        }

        VStack(spacing: 0) {
            row("분류", card.category)
            row("영업", card.hours)
            row("주소", card.address ?? place.address)
            row("전화", card.phone)
            row("방문자 리뷰", card.reviewCount.map { "\($0)건" })
            row("블로그 리뷰", card.blogReviews.map { "\($0)건" })
            // 별점은 있을 때만. 없는 것을 0.0 으로 적으면 「최악」으로 읽힌다.
            row("별점", card.score.map { String(format: "%.2f", $0) })
        }

        if let onAdd {
            Button(action: onAdd) {
                HStack(spacing: 6) {
                    Image(systemName: added ? "checkmark.circle.fill" : "plus.circle.fill")
                        .font(.caption)
                    Text(added ? "경로에 있음" : "경로에 추가")
                        .font(.subheadline.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(added ? Color(.systemGray5) : Color.accentColor)
                )
                .foregroundStyle(added ? Color.secondary : Color.white)
            }
            .buttonStyle(.plain)
            .disabled(added)
            .padding(.horizontal, 14).padding(.top, 12)
        }

        if let onReroute {
            // 즉석에서 목적지를 이 가게로 바꾼다 — 걷다가 배가 고프면 목적지가
            // 바뀌는 것이 내비게이션이다.
            Button(action: onReroute) {
                HStack(spacing: 6) {
                    Image(systemName: "location.north.fill").font(.caption)
                    Text("여기로 길찾기").font(.subheadline.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
                .frame(height: 44)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.accentColor.opacity(onAdd == nil ? 1 : 0.14))
                )
                .foregroundStyle(onAdd == nil ? Color.white : Color.accentColor)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 14).padding(.top, onAdd == nil ? 12 : 8)
        }

        // 네이버 링크는 머리줄의 아이콘이 맡는다(`header`). 여기 큰 단추로 두면
        // 표 한 줄이 밀려 화면 밖으로 나간다.
        Color.clear.frame(height: 14)
    }

    private var missing: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("네이버에서 찾지 못했습니다")
                .font(.subheadline.weight(.medium))
            Text(card?.why ?? "우리 자료(TMAP)에는 있지만 네이버에 없는 가게일 수 있습니다.")
                .font(.caption).foregroundStyle(.secondary)
            if let category = place.category {
                Text(category).font(.caption2).foregroundStyle(.tertiary)
            }
        }
        .padding(14)
    }

    @ViewBuilder
    private func row(_ label: String, _ value: String?) -> some View {
        if let value, !value.isEmpty {
            HStack(alignment: .top) {
                Text(label)
                    .font(.caption).foregroundStyle(.secondary)
                    .frame(width: 76, alignment: .leading)
                Text(value).font(.caption)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14).padding(.vertical, 7)
            Divider().padding(.leading, 14)
        }
    }

    private func load() async {
        card = await RouteGuide.card(for: place)
        loading = false
    }
}
