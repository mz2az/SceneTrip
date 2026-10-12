import SceneApiClient
import SwiftUI

/// 내 리뷰의 대상 상세. 기존 장소 상세·편의시설 카드를 사용한다 (MZ2AZ-363).
struct MyReviewDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store: MyReviewDetailStore
    @StateObject private var cart = CartStore()
    private let title: String

    init(target: ReviewTarget) {
        title = target.name
        _store = StateObject(wrappedValue: MyReviewDetailStore(subject: MyReviewRules.subject(of: target)))
    }

    var body: some View {
        Group {
            switch store.detail {
            case let .place(detail):
                PlaceDetailView(summary: summary(detail), load: { try await PlacesAPI.getPlace(placeId: $0) }) {
                    dismiss()
                }
                .environmentObject(cart)
                .task { await cart.refresh() }
            case let .poi(place):
                ScrollView {
                    RoutePlaceCard(place: place, onClose: { dismiss() }).padding(16)
                }
            case nil:
                VStack(spacing: 16) {
                    DetailHeader(title: title, subtitle: "", onBack: { dismiss() })
                    Spacer()
                    if store.failed {
                        Text("정보를 불러오지 못했습니다").foregroundStyle(.secondary)
                        Button(tr("다시 시도")) { Task { await store.reload() } }
                            .frame(minHeight: 44)
                    } else {
                        ProgressView()
                    }
                    Spacer()
                }
            }
        }
        .background(Color(.systemBackground))
        .task { await store.reload() }
    }

    private func summary(_ detail: PlaceDetail) -> PlaceSummary {
        PlaceSummary(
            id: detail.id, name: detail.name, type: detail.type, address: detail.address,
            latitude: detail.latitude, longitude: detail.longitude, imageUrl: detail.imageUrl,
            contents: PlaceWorks.refs(of: detail)
        )
    }
}

/// 같은 숫자의 촬영지·편의시설을 혼동하지 않고 실패 뒤 다시 받을 수 있다.
@MainActor
final class MyReviewDetailStore: ObservableObject {
    enum Detail {
        case place(PlaceDetail)
        case poi(RouteGuide.Place)
    }

    @Published private(set) var detail: Detail?
    @Published private(set) var failed = false
    private let subject: ReviewSubject
    private let loadPlace: (Int64) async throws -> PlaceDetail
    private let loadPoi: (Int64) async throws -> RouteGuide.Place
    private var generation = 0

    init(
        subject: ReviewSubject,
        loadPlace: @escaping (Int64) async throws -> PlaceDetail = { try await PlacesAPI.getPlace(placeId: $0) },
        loadPoi: @escaping (Int64) async throws -> RouteGuide.Place = { id in
            let detail = try await PoisAPI.getPoi(poiId: id)
            var place = RouteGuide.Place(
                id: "poi-\(detail.id)", name: detail.name, category: detail.category,
                address: detail.address,
                latitude: detail.latitude, longitude: detail.longitude
            )
            place.displayName = detail.displayName
            place.nameRoman = detail.nameRoman
            place.categoryLabel = detail.categoryLabel
            place.displayAddress = detail.displayAddress
            place.linkedPlaceId = detail.placeId
            return place
        }
    ) {
        self.subject = subject
        self.loadPlace = loadPlace
        self.loadPoi = loadPoi
    }

    func reload() async {
        generation += 1
        let asked = generation
        failed = false
        do {
            let fresh: Detail
            switch subject {
            case let .place(id):
                let place = try await loadPlace(id)
                guard place.id == id else { throw URLError(.badServerResponse) }
                fresh = .place(place)
            case let .poi(id):
                let poi = try await loadPoi(id)
                guard poi.poiId == id else { throw URLError(.badServerResponse) }
                fresh = .poi(poi)
            }
            guard !Task.isCancelled, asked == generation else { return }
            detail = fresh
        } catch {
            guard !Task.isCancelled, asked == generation else { return }
            failed = true
        }
    }
}
