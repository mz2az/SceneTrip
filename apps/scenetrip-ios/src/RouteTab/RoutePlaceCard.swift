import SwiftUI

/// 핀을 눌렀을 때 뜨는 **정보 카드** (2026-08-27).
///
/// 프로토타입 v6 가 지도 왼쪽 아래에 띄우던 카드를 옮긴 것이다. 분류·주소·전화를 보여 주고,
/// 더 보려면 네이버 지도로 넘긴다.
///
/// ## 우리 자료만 보여 준다 (2026-10-05, MZ2AZ-354)
///
/// 사진·영업시간·리뷰 수·별점은 서버가 네이버 지도의 비공식 주소를 불러 가져온 것이었다
/// (ADR 0011 — 데모 한정). 밖에 내보내는 빌드에는 쓸 수 없어 걷어냈다. 메뉴·예약·리뷰는
/// 「네이버 지도에서 보기」가 **그쪽 화면으로 넘긴다** — 옮겨 담지 않는다.
struct RoutePlaceCard: View {
    let place: RouteGuide.Place

    /// 「경로에 추가」. 주면 버튼이 뜬다 — 지도에서 고양이를 눌러 연 카드에서
    /// 바로 코스에 담는다(2026-08-27 사용자 지적: 시트의 ⊕ 까지 돌아가야 했다).
    var onAdd: (() -> Void)?

    /// 이미 코스에 들어 있는가. 그러면 추가 단추가 **체크 표시**로 바뀐다 —
    /// 두 번 담기지 않고, 그 상태에서 **한 번 더 누르면 경로에서 뺀다**
    /// (2026-08-27 사용자 요청).
    var added = false

    /// 「경로에서 빼기」. `added` 일 때 단추를 누르면 이것이 불린다.
    var onRemove: (() -> Void)?

    /// 「여기로 길찾기」. **여행 중 화면에서만 준다** — 계획 화면에서는 갈아탈 경로
    /// 자체가 없다. 주면 버튼이 뜬다.
    var onReroute: (() -> Void)?

    let onClose: () -> Void

    @State private var card: RouteGuide.Card?
    /// `card` 가 어느 장소의 것인가. 카드가 열린 채 다른 점을 누르면 `.task` 가 비우기 전 한 프레임은
    /// 앞 가게의 카드가 남아 있다 — 머리줄이 앞 가게 이름을 적지 않게 가른다.
    @State private var cardFor: String?
    /// 리뷰 시트 (MZ2AZ-363).
    @State private var reviewing: ReviewSubject?
    /// 카드의 사진 줄이 넘겨 보는 사진첩 (MZ2AZ-363).
    @StateObject private var gallery = PhotoGallery()

    /// 지금 장소의 카드. 앞 장소의 것이면 없는 것으로 친다.
    private var fresh: RouteGuide.Card? {
        cardFor == place.id ? card : nil
    }

    /// 리뷰가 붙는 대상 — 편의시설이면 편의시설, 촬영지면 촬영지. 코스에서 옮긴 것(둘 다 아님)은 없다.
    /// **촬영지와 같은 곳인 편의시설은 촬영지다**(MZ2AZ-378) — 리뷰가 촬영지 한 벌이다.
    private var subject: ReviewSubject? {
        switch place.target {
        case let .place(id): .place(id)
        case let .poi(id): .poi(id)
        case nil: nil
        }
    }

    /// 제목 — 상세가 왔으면 그 값이 먼저다. 가이드가 찾아 준 곳은 목록에 영어 이름이 없고 상세에만 있다.
    private var shownTitle: String {
        fresh?.title ?? place.label.title
    }

    /// 영어 이름이 없는 가게는 한글 이름 아래 읽는 법을 적는다(MZ2AZ-360). 제목과 같은 쪽의 값을 쓴다.
    private var shownReading: String? {
        fresh?.title == nil ? place.label.reading : fresh?.reading
    }

    /// 리뷰 시트·사진첩의 머리줄 — **카드에 적힌 그대로** 한 줄로(MZ2AZ-367). 제목만 넘겼더니 영어 화면에서
    /// 카드는 「트로 / Teuro」 인데 리뷰 화면은 「트로」 뿐이었다.
    private var reviewTitle: String {
        PoiLabel.heading(title: shownTitle, reading: shownReading)
    }

    @State private var loading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            // 별점 한 줄 — 서버가 별점을 실어 줄 때만 보인다(MZ2AZ-363).
            if let subject {
                RatingLine(rating: fresh?.rating) { reviewing = subject }
                    .padding(.horizontal, 14).padding(.bottom, 8)
            }

            if loading {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("불러오는 중입니다")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(14)
            } else if let card {
                found(card)
            } else {
                missing
            }
        }
        // **흰 시트 위에서도 카드가 구별돼야 한다**(2026-08-28 사용자 지적 — 흰색
        // 위 흰색이라 경계가 안 보였다). 가이드가 주는 정보라 말풍선과 같은
        // 피노 연보라를 옅게 깔고 보라 테두리를 두른다.
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(PinImage.light).opacity(0.16),
                            Color(PinImage.deep).opacity(0.07),
                        ],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    )
                )
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(.systemBackground)))
                .shadow(color: .black.opacity(0.22), radius: 12, y: 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(Color(PinImage.light).opacity(0.5), lineWidth: 1)
        )
        // **핀을 갈아탈 때마다 다시 받는다.** `.task {}` 로만 두면 SwiftUI 가 뷰를
        // 재사용할 때 한 번만 돌아서, 다른 고양이를 눌러도 앞 가게 정보가 그대로
        // 남는다(2026-08-27 사용자 지적 — 빨간 고양이는 바뀌는데 카드가 안 바뀜).
        .reviewsSheet($reviewing, title: reviewTitle) {
            Task { await load() }
        }
        .task(id: place.id) {
            loading = true
            card = nil
            await load()
        }
    }

    /// 이름 · 「네이버 지도에서 보기」 · 닫기.
    ///
    /// **한 줄에 다 들어가면 한 줄, 아니면 링크가 이름 아래로 내려간다.** 가이드 시트 안에서
    /// 펼친 카드는 좁다 — 링크를 줄이면 「View on…」으로 잘리고, 링크 폭을 지키면 이름이
    /// 두 글자씩 세 줄로 쪼개졌다(2026-10-05 실기). 넓은 카드에서는 전처럼 이름 옆에 선다
    /// (그 줄이 통째로 빠져야 정보가 한 화면에 다 보인다 — 2026-08-27 사용자 지적).
    private var header: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top) {
                title(wraps: false)
                Spacer(minLength: 8)
                naverLink
                closeButton
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top) {
                    title(wraps: true)
                    Spacer(minLength: 8)
                    closeButton
                }
                naverLink
            }
        }
        .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 8)
    }

    /// `wraps` 가 아니면 한 줄 폭을 그대로 요구한다 — `ViewThatFits` 가 그것으로 들어가는지 잰다.
    private func title(wraps: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(shownTitle).font(.headline)
                .lineLimit(wraps ? nil : 1)
                .fixedSize(horizontal: !wraps, vertical: true)
            if let reading = shownReading {
                Text(reading).font(.caption).foregroundStyle(.secondary)
                    .lineLimit(wraps ? nil : 1)
                    .fixedSize(horizontal: !wraps, vertical: true)
            }
            // 기준이 지도 중심일 뿐인 거리는 적지 않는다(`shownMeters`, MZ2AZ-367).
            if let meters = place.shownMeters {
                Text("\(meters) m").font(.caption).foregroundStyle(.secondary).fixedSize()
            }
        }
    }

    /// **더 보려면 네이버로** — 사진·메뉴·예약·리뷰는 그쪽에 있다. 그 가게의 네이버 장소 화면을 연다.
    /// 아이콘만 두면 「더 보기」인지 아무도 모른다(2026-08-27 사용자 지적) — 글자째 둔 미니 캡슐이다.
    ///
    /// **갈 곳이 없으면 버튼도 없다**(MZ2AZ-374) — 편의시설은 서버가 장소 번호를 찾은 곳에만 주소를 준다.
    /// 상세를 받는 동안에도 없다가 오면 선다. 넓은 카드에서는 이름 옆(닫기 단추 높이 안)이라 줄이 밀리지 않고,
    /// 좁은 카드에서는 이름 아래 한 줄이 생긴다 — 그때는 아래 표도 함께 들어오므로 따로 튀지 않는다.
    @ViewBuilder
    private var naverLink: some View {
        if let link = fresh?.naverUrl, let url = URL(string: link) {
            Link(destination: url) {
                HStack(spacing: 3) {
                    Text("네이버 지도에서 보기").font(.caption2.weight(.semibold))
                    Image(systemName: "arrow.up.right").font(.system(size: 9, weight: .semibold))
                }
                .padding(.horizontal, 8).padding(.vertical, 5)
                .background(
                    Capsule().fill(Color(red: 0.02, green: 0.78, blue: 0.35).opacity(0.12))
                )
                .foregroundStyle(Color(red: 0.02, green: 0.60, blue: 0.28))
            }
            .fixedSize()
        }
    }

    private var closeButton: some View {
        Button(action: onClose) {
            Image(systemName: "xmark")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 30, height: 30)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func found(_ card: RouteGuide.Card) -> some View {
        if !card.photos.photos.isEmpty {
            // 누르면 크게 넘겨 본다 — 방문자 사진에서는 그 리뷰로 갈 수 있다(MZ2AZ-363).
            CardPhotoStrip(gallery: gallery, title: reviewTitle) {
                Task { await load() }
            }
            .id(place.id)
            .padding(.bottom, 10)
        }

        VStack(spacing: 0) {
            row(tr("분류"), card.category)
            row(tr("주소"), card.address)
            row(tr("전화"), card.phone)
        }

        if let onAdd {
            Button(action: added ? (onRemove ?? {}) : onAdd) {
                HStack(spacing: 6) {
                    Image(systemName: added ? "checkmark.circle.fill" : "plus.circle.fill")
                        .font(.caption)
                    Text(added ? tr("경로에 있음 · 누르면 빼기") : tr("경로에 추가"))
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
            .disabled(added && onRemove == nil)
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

    /// 촬영지 상세를 못 받았을 때뿐이다 — 편의시설은 목록이 준 것만으로도 카드가 선다.
    private var missing: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("정보를 불러오지 못했습니다")
                .font(.subheadline.weight(.medium))
            if let category = place.label.category {
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
                // 영문 주소는 길다 — 좁은 카드(가이드 시트 안)에서 「42-8 Naksan-gil, Jo…」로 잘렸다. 줄을 바꾼다.
                Text(value).font(.caption)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14).padding(.vertical, 7)
            Divider().padding(.leading, 14)
        }
    }

    private func load() async {
        let asked = place.id
        let loaded = await RouteGuide.card(for: place)
        // 떠 있는 카드를 다시 읽다 실패했으면 가진 것을 둔다 — 네이버 버튼·전화·사진·별점이 사라지지 않게.
        // 핀을 갈아탔을 때는 `.task` 가 먼저 비우므로 `fresh` 가 없다.
        if RouteGuide.Card.keeps(fresh, over: loaded) {
            loading = false
            return
        }
        card = loaded
        gallery.show(loaded?.photos ?? PhotoGalleryRules.Book(), subject: subject)
        cardFor = asked
        loading = false
    }
}
