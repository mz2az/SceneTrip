import SceneApiClient
import SwiftUI

/// 서버의 숨김·폐업 표시. 항목을 지우거나 저장 ID를 바꾸는 근거로 쓰지 않는다(MZ2AZ-458).
enum UnavailableItem: String, Hashable {
    case place
    case content
    case closedPoi

    var message: String {
        switch self {
        case .place: tr("더 이상 소개하지 않는 장소예요")
        case .content: tr("더 이상 소개하지 않는 작품이에요")
        case .closedPoi: tr("폐업했어요")
        }
    }

    static func notice(for item: CartItem) -> Self? {
        item.delisted == true ? .place : nil
    }

    static func notice(for item: ContentSummary) -> Self? {
        item.delisted == true ? .content : nil
    }

    static func notice(for item: CourseItem) -> Self? {
        switch item.source {
        case .place: item.delisted == true ? .place : nil
        case .poi: item.closed == true ? .closedPoi : nil
        case .custompin: nil
        }
    }

    /// 새로 담기만 막는다. 이미 담은 것을 빼는 동작은 항상 가능하다.
    static func canToggle(delisted: Bool?, saved: Bool) -> Bool {
        saved || delisted != true
    }

    static func contentDelisted(summary: ContentSummary, detail: ContentDetail?) -> Bool {
        // 상세를 다시 받으면 누락도 false다. 옛 요약의 true를 계속 붙들지 않는다.
        detail.map { $0.delisted == true } ?? (summary.delisted == true)
    }

    static func rejection(status: Int?, code: String?) -> String? {
        guard status == 409 else { return nil }
        switch code {
        case "PLACE_DELISTED": return tr("목록에서 빠진 장소라 담을 수 없어요.")
        case "CONTENT_DELISTED": return tr("목록에서 빠진 작품이라 찜할 수 없어요.")
        case "POI_CLOSED": return tr("폐업한 곳이라 코스에 넣을 수 없어요.")
        default: return nil
        }
    }
}

struct UnavailableItemNotice: View {
    let item: UnavailableItem

    var body: some View {
        Label(item.message, systemImage: "exclamationmark.circle")
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

extension ApiFailure {
    static let courseDetailNotLoaded = ApiFailure(
        statusCode: nil, traceId: nil, apiCode: "COURSE_DETAIL_NOT_LOADED"
    )

    var courseSaveMessage: String {
        if self == .courseDetailNotLoaded {
            return tr("코스를 불러오지 못했어요")
        }
        guard let rejection = UnavailableItem.rejection(status: statusCode, code: apiCode) else { return message }
        return rejection + " " + tr("편집 내용은 그대로예요. 해당 항목을 빼고 다시 저장해 주세요.")
    }
}

/// 코스의 표시 순서·번호는 그대로 두고 이동에 쓰는 곳만 고른다.
enum RouteAvailability {
    static func nextUnvisited(in stops: [RouteStop]) -> (number: Int, stop: RouteStop)? {
        stops.firstIndex { $0.canNavigate && !$0.visited }.map { ($0 + 1, stops[$0]) }
    }

    /// 미방문 행이 숨김·폐업뿐이면 방문 완료가 아니다. 전부 방문했다면 재시작할 곳을 살핀다.
    static func hasOnlyUnavailableRemaining(in stops: [RouteStop]) -> Bool {
        let remaining = stops.filter { !$0.visited }
        let candidates = remaining.isEmpty ? stops : remaining
        return !candidates.isEmpty && candidates.allSatisfy { !$0.canNavigate }
    }

    /// 시작할 목적지를 먼저 확인한다. nil이면 화면·서버의 활성 상태를 바꾸지 않는다.
    static func start(_ course: inout RouteCourse, dayIndex: Int) -> RouteStop? {
        guard course.days.indices.contains(dayIndex) else { return nil }
        let stops = course.days[dayIndex].stops
        guard !hasOnlyUnavailableRemaining(in: stops),
              let target = nextUnvisited(in: stops)?.stop ?? stops.first(where: \.canNavigate) else { return nil }
        course.isRunning = true
        return target
    }

    static func nextKilometers(after index: Int, in stops: [RouteStop]) -> Double? {
        guard stops.indices.contains(index), stops[index].canNavigate,
              let next = stops.dropFirst(index + 1).first(where: \.canNavigate) else { return nil }
        return RouteGeometry.kilometers(stops[index].place, next.place)
    }

    static func stayTotal(_ stops: [RouteStop]) -> Int {
        RouteStop.stayTotal(stops.filter(\.canNavigate))
    }

    /// 숨김·폐업 행은 원래 자리에 보존하고 방문 가능한 곳의 동선만 정렬한다.
    static func reordering(_ stops: [RouteStop], using transform: ([RouteStop]) -> [RouteStop]) -> [RouteStop] {
        var ordered = transform(stops.filter(\.canNavigate)).makeIterator()
        return stops.map { $0.canNavigate ? (ordered.next() ?? $0) : $0 }
    }
}

/// 찜은 검색 목록·상세, 코스 마법사, 찜 목록에서 같은 실패 안내를 쓴다.
private struct LikeFailureNotice: ViewModifier {
    @ObservedObject private var likes = LikeStore.shared

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom) {
            if let failure = likes.failure {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(tr("찜을 바꾸지 못했습니다")).font(.caption.weight(.semibold))
                        Text(failure.message).font(.caption)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    Button(tr("닫기")) { likes.clearFailure() }
                        .font(.caption).frame(minHeight: 44)
                }
                .padding(12)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 12)
            }
        }
    }
}

extension View {
    func likeFailureNotice() -> some View {
        modifier(LikeFailureNotice())
    }
}
