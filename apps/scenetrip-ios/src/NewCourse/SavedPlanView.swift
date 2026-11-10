import SceneApiClient
import SwiftUI

/// 담은 곳으로 — 「며칠 다녀오나요?」 만 묻고, 보관함의 담은 곳 전부를 날마다 나눠 넣은 편집 화면을 연다
/// (MZ2AZ-547 A4, 웹 `proto/ProtoPlanFlows.tsx` `ProtoSavedPlan`).
///
/// 시·도 묶음 → 가까운 순으로 잇고(`SavedPlanRules.arrange`) 날마다 고르게 나눈다(`splitEven`). 고르기·빼기·순서는 편집 화면에서.
/// 담은 곳이 없으면 빈 코스로 시작한다. 저장 전 초안이다(isNew).
struct SavedPlanView: View {
    let onClose: () -> Void

    @StateObject private var cart = CartStore()
    @State private var span = RouteSpan.oneNight
    @State private var date: Date?
    @State private var draft: RouteCourse?

    private var items: [CartItem] {
        cart.items.filter { $0.delisted != true }
    }

    var body: some View {
        if let draft {
            RouteEditorView(course: draft, isNew: true)
        } else {
            NewCourseScreen(onBack: onClose) {
                NewCourseTitle(text: tr("며칠 다녀오나요?"))
                TripScheduleGroup(span: $span, date: $date)
                NewCourseGroup(label: tr("넣을 곳"), footnote: items.isEmpty
                    ? tr("담아 둔 곳이 없어 빈 코스로 시작해요. 다음 화면에서 하나씩 넣으면 돼요")
                    : tr("가까운 곳끼리 날마다 나눠 넣어요. 빼거나 순서를 바꾸는 건 다음 화면에서 할 수 있어요"))
                {
                    savedRow
                }
            } footer: {
                NewCoursePrimaryButton(tr("코스 만들기")) { create() }
            }
            .task { await cart.refresh() }
        }
    }

    private var savedRow: some View {
        HStack(spacing: 12) {
            let photos = items.compactMap(\.imageUrl).filter(NewCoursePhoto.usable).prefix(4)
            HStack(spacing: -8) {
                if photos.isEmpty {
                    Image(systemName: "bag").font(.system(size: 20)).foregroundStyle(.secondary)
                }
                ForEach(Array(photos), id: \.self) { url in
                    NewCoursePhoto(url: url)
                        .frame(width: 32, height: 32)
                        .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS))
                        .overlay(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS).strokeBorder(Color(.systemBackground), lineWidth: 2))
                }
            }
            .accessibilityHidden(true)
            Text("담아 둔 곳").font(.system(size: 15))
            Spacer()
            Text(String(format: tr("%lld곳"), items.count)).font(.system(size: 15)).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }

    private func create() {
        let days = SavedPlanRules.splitEven(SavedPlanRules.arrange(SavedPlanRules.places(from: items)), days: span.days)
        draft = RouteCourse(
            title: tr("내 코스", at: "코스 제목"),
            startDate: date,
            days: days.map { places in RouteDay(stops: places.map { RouteStop(place: $0) }) }
        )
    }
}
