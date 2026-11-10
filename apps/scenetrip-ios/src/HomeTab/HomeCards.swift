import SceneApiClient
import SwiftUI

/// 인사 — 해태 얼굴, 두 줄, 오른쪽 톱니바퀴 단추(마이페이지의 입구 — 언어 바꾸기가 거기 있다).
///
/// 첫 실행 언어 고르기를 빼며(MZ2AZ-526) 사람 모양을 톱니바퀴로 바꿨다 — 설정 입구로 보이게. 웹과 같다.
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
                Image(systemName: "gearshape")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.primary)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(Color(.systemBackground)))
                    .shadow(color: .black.opacity(0.08), radius: 1.5, y: 1)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("설정")
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }
}

/// **지금 뜨는 작품** — 서버 인기순 첫 20편의 포스터 가로 스크롤.
struct HomeWorkShelf: View {
    let works: [ContentSummary]
    let failed: Bool
    let onOpen: (ContentSummary) -> Void
    let onAll: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HomeSectionHeader(
                title: tr("지금 뜨는 작품"), subtitle: tr("인기순"), action: tr("전체 보기"), onAction: onAll
            )
            if failed {
                Text("작품을 불러오지 못했습니다 — 백엔드(:8081)가 켜져 있나요?")
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 20)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 10) {
                        ForEach(works) { work in
                            Button { onOpen(work) } label: { poster(work).contentShape(.rect) }
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
                    RemoteImage(url: url.absoluteString, symbol: "film")
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
    /// 카드 본체(사진·이름·주소)를 눌렀다 — 그 촬영지의 상세로 간다.
    var onOpen: () -> Void = {}
    /// 「담기」를 눌렀다 — main 은 길찾기 대신 장바구니로 잇는다(MZ2AZ-313).
    let onSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HomeSectionHeader(title: tr("오늘의 성지"), subtitle: tr("매일 한 장면"))
            VStack(alignment: .leading, spacing: 0) {
                // 사진 띠는 통째로 단추다 — 「담기」와 겹치지 않는 자리라 여기서 연다.
                Button(action: onOpen) { hero }.buttonStyle(.plain)
                VStack(alignment: .leading, spacing: 6) {
                    Button(action: onOpen) {
                        Text(title).font(.system(size: 15, weight: .bold)).lineLimit(2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    HStack(spacing: 6) {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.system(size: 12)).foregroundStyle(Color(PinImage.deep))
                        Text(place.address ?? tr("주소 없음"))
                            .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                        Spacer(minLength: 0)
                        Button(action: onSave) {
                            Label(
                                saved ? tr("담김") : tr("담기"),
                                systemImage: saved ? "checkmark.circle.fill" : "plus.circle"
                            )
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

    private var hero: some View {
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
        // 잘린 원본 사진의 보이지 않는 영역이 위의 작품 목록 터치를 가로채지 않게 한다.
        .contentShape(.rect)
    }

    /// 「장소명 — 장면 설명」. 장면 설명이 없으면 장소명만.
    private var title: String {
        if let scene = place.sceneDescription?.trimmingCharacters(in: .whitespacesAndNewlines), !scene.isEmpty {
            return "\(place.name) — \(scene)"
        }
        return place.name
    }
}
