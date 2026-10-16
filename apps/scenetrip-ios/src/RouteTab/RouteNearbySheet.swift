import SceneApiClient
import SwiftUI

/// 모델 없이 촬영지를 보고 현재 일차에 직접 담는다. 위치 권한 거절 시에도 지도 기준은 남는다.
struct RouteNearbySheet: View {
    let mapAnchor: RouteGuideAnchor
    let deviceLocation: TripSpot?
    let taken: Set<Int64>
    let onAdd: (PlaceSummary) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var search = RouteNearbySearch()
    @State private var anchor: RouteGuideAnchor?
    @State private var requestNumber = 0
    @State private var started = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 8) {
                controls
                results
            }
            .navigationTitle("주변 촬영지")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .locationConsentSheet(search.gate)
        .onAppear {
            guard !started else { return }
            started = true
            request(mapAnchor)
        }
        .task(id: requestNumber) {
            if let anchor {
                await search.load(anchor)
            }
        }
        .onDisappear { search.gate.cancel() }
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(anchor?.isMapCenter == false
                ? tr("내 위치 주변 촬영지입니다. AI를 사용하지 않아요.")
                : tr("지도 중심 주변 촬영지입니다. AI를 사용하지 않아요."))
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Button("지도 중심 주변") { request(mapAnchor) }
                    .accessibilityIdentifier("nearby-map-center")
                Button("내 위치로") {
                    guard let deviceLocation else { return }
                    request(RouteGuideAnchor(spot: deviceLocation, isMapCenter: false))
                }
                .disabled(deviceLocation == nil)
                .accessibilityIdentifier("nearby-device-location")
            }
            .buttonStyle(.bordered).font(.caption)
            if deviceLocation == nil {
                Text("내 위치를 사용할 수 없어 지도 중심으로 찾아요.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
    }

    @ViewBuilder private var results: some View {
        switch search.phase {
        case .idle:
            ContentUnavailableView("검색 기준을 선택해 주세요", systemImage: "map")
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .needsConsent:
            VStack(spacing: 12) {
                Text("위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.")
                    .font(.subheadline).multilineTextAlignment(.center)
                Button("다시 시도") { request(anchor ?? mapAnchor) }
                    .buttonStyle(.bordered)
            }
            .padding().frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .failed(failure):
            ErrorView(failure: failure) { request(anchor ?? mapAnchor) }
        case .loaded where search.places.isEmpty:
            ContentUnavailableView("주변에 촬영지가 없어요", systemImage: "mappin.slash",
                                   description: Text("지도를 다른 곳으로 옮겨 다시 찾아보세요."))
        case .loaded:
            List(search.places, id: \.id) { place in
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(place.name).font(.subheadline.weight(.medium))
                        if let work = place.contents?.first?.title {
                            Text(work).font(.caption).foregroundStyle(Color.accentColor)
                        }
                        if let address = place.address {
                            Text(address).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                    Button { onAdd(place) } label: {
                        Label(taken.contains(place.id) ? tr("담김") : tr("담기"),
                              systemImage: taken.contains(place.id) ? "checkmark.circle.fill" : "plus.circle")
                    }
                    .buttonStyle(.bordered)
                    .disabled(taken.contains(place.id))
                    .accessibilityLabel(String(format: tr(taken.contains(place.id)
                            ? "%@ · 코스에 담김" : "%@ 코스에 담기"), place.name))
                }
            }
            .listStyle(.plain)
        }
    }

    private func request(_ wanted: RouteGuideAnchor) {
        search.gate.requestLocation(for: wanted.source) {
            anchor = wanted
            requestNumber += 1
        }
    }
}
