import SceneApiClient
import SwiftUI

/// 인사 — 해태 얼굴, 두 줄, 오른쪽 프로필 단추(마이페이지의 입구).
struct HomeHeader: View {
    let onProfile: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image("haetae-face")
                .resizable()
                .scaledToFit()
                .frame(width: 44, height: 38)
            VStack(alignment: .leading, spacing: 1) {
                Text("해태가 기다렸어요").font(.system(size: 13)).foregroundStyle(.secondary)
                Text("오늘은 어느 장면으로 갈까요?").font(.system(size: 19, weight: .bold))
            }
            Spacer(minLength: 0)
            Button(action: onProfile) {
                Image(systemName: "person")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.primary)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(Color(.systemBackground)))
                    .shadow(color: .black.opacity(0.08), radius: 1.5, y: 1)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("마이페이지")
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }
}

/// **내 여행 이어가기** 묶음 — 코스가 둘 이상이면 옆으로 넘기는 페이저, 아래에 1·2·3 점.
///
/// 여행 중인 코스가 첫 장이다. 한 장만 있거나(코스 하나) 아직 없으면 카드 하나.
struct HomeTripPager: View {
    let trips: [HomeTrip]
    let hasCourses: Bool
    let loading: Bool
    let onNavigate: (HomeTrip) -> Void
    let onOpenCourse: (HomeTrip) -> Void
    let onCreate: () -> Void

    @State private var page = 0

    var body: some View {
        if trips.count > 1 {
            VStack(spacing: 8) {
                TabView(selection: $page) {
                    ForEach(Array(trips.enumerated()), id: \.offset) { index, trip in
                        HomeTripCard(
                            trip: trip, rank: index + 1, hasCourses: hasCourses, loading: false,
                            onNavigate: onNavigate, onOpenCourse: onOpenCourse, onCreate: onCreate
                        )
                        .tag(index)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .frame(height: 196)
                HStack(spacing: 6) {
                    ForEach(trips.indices, id: \.self) { index in
                        Circle()
                            .fill(index == page ? TabBar.homePurple : Color(.systemGray4))
                            .frame(width: 6, height: 6)
                    }
                }
            }
        } else {
            HomeTripCard(
                trip: trips.first, rank: nil, hasCourses: hasCourses, loading: loading,
                onNavigate: onNavigate, onOpenCourse: onOpenCourse, onCreate: onCreate
            )
        }
    }
}

/// **내 여행 이어가기** — 홈 맨 위 고정 카드. 경로여정 탭이 없어진 자리를 이 카드가 맡는다.
///
/// 세 모습: 코스가 없으면 「코스 만들기」, 있으면 제목·기간·스탬프 진행과 단추 둘,
/// 아직 받는 중이면 자리만 지킨다.
struct HomeTripCard: View {
    let trip: HomeTrip?
    /// 페이저 안에서 몇 번째인가(1부터). 하나뿐이면 nil — 순위 배지를 안 단다.
    var rank: Int?
    let hasCourses: Bool
    let loading: Bool
    let onNavigate: (HomeTrip) -> Void
    let onOpenCourse: (HomeTrip) -> Void
    let onCreate: () -> Void

    private let gradient = LinearGradient(
        colors: [Color(PinImage.light), Color(PinImage.deep)],
        startPoint: .topLeading, endPoint: .bottomTrailing
    )

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let trip {
                filled(trip)
            } else if loading {
                Text("내 여행을 불러오는 중…").font(.system(size: 14)).opacity(0.9)
                    .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            } else {
                empty
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 18).padding(.vertical, 16)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(gradient))
        .shadow(color: Color(PinImage.deep).opacity(0.28), radius: 8, y: 6)
        .padding(.horizontal, 20)
    }

    @ViewBuilder
    private func filled(_ trip: HomeTrip) -> some View {
        HStack(spacing: 8) {
            if let rank {
                Text("\(rank)")
                    .font(.system(size: 12, weight: .heavy))
                    .frame(width: 22, height: 22)
                    .background(Circle().fill(.white.opacity(0.28)))
            }
            Text(trip.course.isRunning ? "여행 중" : "예정")
                .font(.system(size: 12, weight: .bold))
                .padding(.horizontal, 9).padding(.vertical, 3)
                .background(Capsule().fill(.white.opacity(0.22)))
            Text("\(trip.course.spanLabel) · \(trip.course.placeCount)곳")
                .font(.system(size: 12)).opacity(0.85)
        }
        Text(trip.course.title).font(.system(size: 18, weight: .heavy)).lineLimit(1)
        HStack(spacing: 10) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.3))
                    Capsule().fill(.white)
                        .frame(width: geo.size.width * progress(trip))
                }
            }
            .frame(height: 6)
            Text("스탬프 \(trip.visited)/\(trip.total)").font(.system(size: 12, weight: .semibold))
        }
        HStack(spacing: 8) {
            Button { onNavigate(trip) } label: {
                Text("이어서 길찾기")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(TabBar.homePurple)
                    .frame(maxWidth: .infinity).padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.white))
            }
            .buttonStyle(.plain)
            .disabled(trip.nextStop == nil)
            .opacity(trip.nextStop == nil ? 0.6 : 1)
            Button { onOpenCourse(trip) } label: {
                Text("코스 보기")
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.white.opacity(0.2)))
            }
            .buttonStyle(.plain)
        }
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(hasCourses ? "코스" : "첫 여행")
                .font(.system(size: 12, weight: .bold))
                .padding(.horizontal, 9).padding(.vertical, 3)
                .background(Capsule().fill(.white.opacity(0.22)))
            Text("여행을 시작해 볼까요?").font(.system(size: 18, weight: .heavy))
            Text("보고 싶은 작품과 기간만 고르면 촬영지를 이어서 일정으로 짜 드립니다")
                .font(.system(size: 13)).opacity(0.9)
            Button(action: onCreate) {
                Text("코스 만들기")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(TabBar.homePurple)
                    .frame(maxWidth: .infinity).padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.white))
            }
            .buttonStyle(.plain)
        }
    }

    private func progress(_ trip: HomeTrip) -> CGFloat {
        guard trip.total > 0 else { return 0 }
        return CGFloat(trip.visited) / CGFloat(trip.total)
    }
}

/// **지금 뜨는 작품** — 포스터 가로 스크롤, 촬영지 많은 순.
struct HomeWorkShelf: View {
    let works: [ContentSummary]
    let failed: Bool
    let onOpen: (ContentSummary) -> Void
    let onAll: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HomeSectionHeader(title: "지금 뜨는 작품", subtitle: "촬영지가 많은 순", action: "전체 보기", onAction: onAll)
            if failed {
                Text("작품을 불러오지 못했습니다 — 백엔드(:8081)가 켜져 있나요?")
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 20)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 10) {
                        ForEach(works) { work in
                            Button { onOpen(work) } label: { poster(work) }
                                .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 20)
                }
            }
        }
    }

    private func poster(_ work: ContentSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(LinearGradient(colors: [Color(PinImage.deep).opacity(0.7), Color(PinImage.deep)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
                if let url = work.posterUrl.flatMap(URL.init(string:)) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Color.clear
                    }
                    .frame(width: 108, height: 144)
                    .clipped()
                }
                LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .center, endPoint: .bottom)
                Text(work.title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .padding(10)
            }
            .frame(width: 108, height: 144)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            Text("촬영지 \(work.placeCount)곳").font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .frame(width: 108)
    }
}

/// **오늘의 성지** — 하루 한 곳. 작품 배지 · 장소명과 장면 · 주소 · 담기.
struct HomeTodayCard: View {
    let place: PlaceSummary
    /// 장바구니에 이미 담긴 곳인가. 단추가 「담김」으로 바뀐다.
    var saved = false
    /// 「담기」를 눌렀다 — main 은 길찾기 대신 장바구니로 잇는다(MZ2AZ-313).
    let onSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HomeSectionHeader(title: "오늘의 성지", subtitle: "매일 한 장면")
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .bottomLeading) {
                    LinearGradient(
                        colors: [Color(PinImage.deep).opacity(0.13), Color(PinImage.light).opacity(0.33)],
                        startPoint: .leading, endPoint: .trailing
                    )
                    if let url = place.imageUrl.flatMap(URL.init(string:)) {
                        AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
                            .frame(height: 96).frame(maxWidth: .infinity).clipped()
                    }
                    if let work = place.contents?.first?.title {
                        Text(work)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(TabBar.homePurple)
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Capsule().fill(Color(.systemBackground)))
                            .padding(.horizontal, 16).padding(.bottom, 12)
                    }
                }
                .frame(height: 96)
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(.system(size: 15, weight: .bold)).lineLimit(2)
                    HStack(spacing: 6) {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.system(size: 12)).foregroundStyle(Color(PinImage.deep))
                        Text(place.address ?? "주소 없음")
                            .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                        Spacer(minLength: 0)
                        Button(action: onSave) {
                            Label(saved ? "담김" : "담기", systemImage: saved ? "checkmark.circle.fill" : "plus.circle")
                                .font(.system(size: 13, weight: .bold))
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(saved ? Color(PinImage.deep) : Color.accentColor)
                    }
                }
                .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 14)
            }
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .homeCard(radius: 20)
            .padding(.horizontal, 20)
        }
    }

    /// 「장소명 — 장면 설명」. 장면 설명이 없으면 장소명만.
    private var title: String {
        if let scene = place.sceneDescription?.trimmingCharacters(in: .whitespacesAndNewlines), !scene.isEmpty {
            return "\(place.name) — \(scene)"
        }
        return place.name
    }
}
