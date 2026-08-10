import SceneApiClient
import SwiftUI

/// 검색 탭의 **지도 위에 얹히는 것들** — 검색바와 지도 조작 버튼.
///
/// `SearchTabView.swift` 에서 떼어 냈다. 한 타입의 본문이 350 줄을 넘으면 린트가
/// 막는데(swiftlint `type_body_length`), 그 한도는 "한 화면에 담기는 만큼만" 이라는
/// 뜻이다. 상태 선언과 화면 뼈대는 그쪽에 남기고, **지도 위 겹쳐 그리는 부품**을
/// 이 파일로 옮겨 둘을 나눠 읽을 수 있게 했다.
extension SearchTabView {
    // MARK: 검색바

    /// 검색어와 장바구니가 **한 캡슐**이다 — 프로토타입의 검색바와 같다. 따로 떠
    /// 있던 원형 버튼은 폐기했다.
    var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("작품·배우·장소로 검색", text: $draft)
                .focused($searchFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .onSubmit { commit(draft) }
                .onChange(of: draft) { _, term in Task { await suggest(term) } }
            if !draft.isEmpty {
                Button {
                    draft = ""
                    commit("")
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                }
            }

            Divider().frame(height: 22)

            Button { showCart = true } label: {
                Image(systemName: "cart")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(.primary)
                    .overlay(alignment: .topTrailing) {
                        if !cart.items.isEmpty {
                            Text("\(cart.items.count)")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5).padding(.vertical, 2)
                                .background(Capsule().fill(Color.red))
                                .offset(x: 10, y: -8)
                        }
                    }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(Capsule().fill(Color(.systemBackground)).shadow(radius: 3, y: 1))
        .padding(.horizontal, 14)
        .padding(.top, 8)
    }

    /// 지도 위 원형 조작 버튼.
    func mapControl(
        _ label: String,
        symbol: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.primary)
                .frame(width: 38, height: 38)
                .background(Circle().fill(Color(.systemBackground)).shadow(radius: 3, y: 1))
                // 보이는 원은 38pt 지만 누르는 자리는 44pt — 애플 최소 권장이다.
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .accessibilityLabel(label)
    }

    // MARK: 현 지도 내 성지 검색

    /// 목업의 토글 버튼. 켜면 지도 중심 반경 5km 안의 촬영지만 남고, 다시 누르면
    /// 검색 전 상태로 돌아간다.
    ///
    /// **검색어를 지우고 장소 탭으로 옮긴다.** 반경은 장소의 성질이므로 작품 탭에
    /// 남아 있으면 무엇이 걸러졌는지 보이지 않는다 — 목업도 같은 처리를 한다.
    ///
    /// 검색 중에는 숨긴다. 자동완성 패널이 내려오는 자리와 겹친다.
    var nearbyButton: some View {
        Button {
            if nearby {
                // 해제도 **카메라를 건드리지 않는다.** commit 을 부르면 그 안에서
                // fit 이 걸려 지도가 결과 범위로 튄다 — 켤 때 안 움직인 화면이
                // 끌 때 움직이면 사용자는 무엇 때문에 움직였는지 알 수 없다.
                nearby = false
                draft = ""
                committed = ""
                chip = CategoryChip.all
                data.search("")
            } else {
                guard let box = camera.boundingBox else { return }
                nearby = true
                draft = ""
                committed = ""
                selectedPlace = nil
                selectedContent = nil
                contentPlaces = []
                tab = .place
                chip = CategoryChip.all
                detent = .medium
                // **카메라를 건드리지 않는다.** 이 기능은 "지금 보고 있는 이 화면
                // 안" 을 묻는 것이라, 결과가 왔다고 지도를 옮기거나 확대하면
                // 사용자가 물어본 그 화면이 사라진다. 그래서 fit 을 걸지 않는다.
                data.searchInViewport(bbox: box)
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: nearby ? "xmark" : "arrow.clockwise")
                    .font(.footnote.weight(.semibold))
                Text(nearby ? "이 지도에서 \(data.places.count)곳 · 해제" : "현 지도 내 성지 검색")
                    .font(.subheadline.weight(.medium))
            }
            .foregroundStyle(nearby ? Color(.systemBackground) : Color.primary)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(
                Capsule()
                    .fill(nearby ? Color.primary : Color(.systemBackground))
                    .shadow(radius: 3, y: 1)
            )
        }
    }
}
