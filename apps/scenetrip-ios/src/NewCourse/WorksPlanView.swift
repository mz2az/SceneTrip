import SceneApiClient
import SwiftUI

/// 작품으로(AI) — 「어떤 여행이에요?」 → 「누구의 장면을 따라갈까요?」 → 짜는 중 → 저장 전 초안 편집
/// (MZ2AZ-547 A1–A3, 웹 `proto/ProtoPlanFlows.tsx` `ProtoAIPlan`·`ProtoWizardSteps.tsx` `ProtoWorkStep`).
///
/// 일정은 서버 창구가 짠다(`POST /guide/plan`, `RouteStore.guideDraft`). 응답은 초안이고 **저장하지 않는다** — 편집 화면(isNew)에서
/// 「만들기」 를 눌러야 서버에 들어간다.
struct WorksPlanView: View {
    let onClose: () -> Void

    @EnvironmentObject private var store: RouteStore
    @ObservedObject private var likes = LikeStore.shared

    private enum Step { case trip, works }

    /// 확인용 뒷문 — `-worksStep works` 면 작품 고르기(A2)부터.
    @State private var step = UserDefaults.standard.string(forKey: "worksStep") == "works" ? Step.works : Step.trip
    @State private var span = RouteSpan.oneNight
    @State private var date: Date?
    @State private var pace = RoutePace.loose
    @State private var workIds: Set<Int64> = []
    @State private var preset = false
    @State private var tooMany = false
    @State private var planning = false
    /// 「그만두기」 를 누르면 올라가는 번호 — 늦게 온 응답이 이미 닫은 짜는 중 화면을 열지 않게.
    @State private var run = 0
    @State private var draft: RouteCourse?
    @State private var failure: RouteGuideFailure?

    @StateObject private var consentGate = AIConsentGate()
    @StateObject private var locationGate = AIConsentGate(store: LocationConsentStore.shared)
    /// 지금 어디에 있나 — 시작할 때 한 번 물어 둔다. 못 받아도 코스는 만들어진다.
    @StateObject private var locator = RouteLocator()

    private var here: (lat: Double, lng: Double)? {
        if case let .found(latitude, longitude) = locator.state {
            return (latitude, longitude)
        }
        return nil
    }

    var body: some View {
        if let draft {
            RouteEditorView(course: draft, isNew: true)
        } else {
            questions
                .task {
                    locator.start()
                    await store.refreshMaterials()
                    presetLiked()
                }
                .onChange(of: store.sortedWorks.count) { _, _ in presetLiked() }
        }
    }

    /// 찜한 작품을 미리 골라 둔다 — 작품 목록이 온 뒤 한 번만(사람이 고친 것을 덮지 않게).
    private func presetLiked() {
        guard !preset, !store.sortedWorks.isEmpty else { return }
        preset = true
        workIds = WorkPickRules.preselect(works: store.sortedWorks, liked: likes.contentIds)
    }

    private var questions: some View {
        Group {
            if planning {
                PlanningScreen(posters: posters, summary: summary) {
                    run += 1
                    planning = false
                }
            } else if step == .trip {
                tripStep
            } else {
                worksStep
            }
        }
        .likeFailureNotice()
        .aiConsentSheet(consentGate)
        .locationConsentSheet(locationGate, onCancel: { plan() })
        .alert(String(format: tr("작품은 %lld개까지 고를 수 있어요"), WorkPickRules.maxWorks), isPresented: $tooMany) {
            Button(tr("확인"), role: .cancel) {}
        } message: {
            Text("하나를 빼고 다시 골라 주세요")
        }
        .alert(tr("일정을 짜지 못했어요"), isPresented: Binding(get: { failure != nil }, set: {
            if !$0 {
                failure = nil
            }
        })) {
            Button(tr("확인"), role: .cancel) { failure = nil }
        } message: {
            Text(verbatim: failure?.message ?? "")
        }
    }

    // MARK: A1

    private var tripStep: some View {
        NewCourseScreen(step: (at: 1, of: 2), onBack: onClose) {
            NewCourseTitle(text: tr("어떤 여행이에요?"))
            TripScheduleGroup(span: $span, date: $date)
            NewCourseGroup(label: tr("다니는 속도"), footnote: tr("방문 수는 고른 작품과 이동 거리에 따라 달라져요")) {
                PaceRows(pace: $pace)
            }
        } footer: {
            NewCoursePrimaryButton(tr("다음")) { step = .works }
        }
    }

    // MARK: A2

    private var worksStep: some View {
        NewCourseScreen(step: (at: 2, of: 2), onBack: { step = .trip }) {
            NewCourseTitle(text: tr("누구의 장면을 따라갈까요?"))
            Text("여러 개 골라도 돼요. 안 고르면 인기 작품으로 짜요")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
            PosterGrid(
                works: store.sortedWorks,
                selected: workIds,
                liked: likes.contentIds,
                onToggle: { id in
                    let next = WorkPickRules.toggle(workIds, id)
                    workIds = next.selected
                    tooMany = next.tooMany
                },
                onLike: { store.toggleFavorite($0) }
            )
        } footer: {
            VStack(spacing: 8) {
                Text(verbatim: chosen.isEmpty
                    ? tr("안 고르면 인기 작품으로 짜요")
                    : String(format: tr("%lld개 골랐어요 · 최대 %lld개"), chosen.count, WorkPickRules.maxWorks))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                NewCoursePrimaryButton(action: start) {
                    HStack(spacing: 6) {
                        Image(systemName: "sparkles").font(.system(size: 15))
                        Text("AI로 코스 짜기")
                    }
                }
            }
        }
    }

    private var chosen: [ContentSummary] {
        store.sortedWorks.filter { workIds.contains($0.id) }
    }

    private var posters: [String] {
        let source = chosen.isEmpty ? Array(store.sortedWorks.prefix(3)) : chosen
        return source.compactMap(\.posterUrl).filter(NewCoursePhoto.usable)
    }

    private var summary: [String] {
        [
            span.label,
            date.map { String(format: tr("%@ 출발"), RouteFormat.day($0)) },
            tr(pace.rawValue),
            chosen.isEmpty ? tr("인기 작품") : String(format: tr("작품 %lld"), chosen.count),
        ].compactMap { $0 }
    }

    private func start() {
        consentGate.request {
            if here != nil {
                locationGate.request { plan() }
            } else {
                plan()
            }
        }
    }

    private func plan() {
        guard !planning else { return }
        run += 1
        let mine = run
        planning = true
        Task {
            let result = await store.guideDraft(
                span: span, startDate: date, workIds: workIds, pace: pace,
                near: LocationConsentStore.shared.isAllowed ? here : nil
            )
            guard mine == run else { return }
            planning = false
            switch result {
            case let .success(course): draft = course
            case let .failure(reason): failure = reason
            }
        }
    }
}

/// 작품 포스터 3열 — 포스터를 누르면 고름(보라 테두리·✓), 왼쪽 위 하트는 찜 (A2).
/// 하트는 포스터 단추 **밖의 형제 단추**다 — 단추 안에 단추를 두지 않는다.
private struct PosterGrid: View {
    let works: [ContentSummary]
    let selected: Set<Int64>
    let liked: Set<Int64>
    let onToggle: (Int64) -> Void
    let onLike: (Int64) -> Void

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), alignment: .leading, spacing: 14) {
            ForEach(works, id: \.id) { work in
                cell(work)
            }
        }
    }

    private func cell(_ work: ContentSummary) -> some View {
        let on = selected.contains(work.id)
        let heart = liked.contains(work.id)
        return ZStack(alignment: .topLeading) {
            Button { onToggle(work.id) } label: {
                VStack(alignment: .leading, spacing: 6) {
                    Color.clear
                        .aspectRatio(2 / 3, contentMode: .fit)
                        .overlay { NewCoursePhoto(url: work.posterUrl) }
                        .overlay {
                            if on {
                                Color(red: 60 / 255, green: 40 / 255, blue: 160 / 255).opacity(0.28)
                            }
                        }
                        .overlay(alignment: .topTrailing) {
                            ZStack {
                                Circle().fill(on ? NewCourseStyle.purple : .black.opacity(0.35))
                                Circle().strokeBorder(.white, lineWidth: 2)
                                if on {
                                    Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                                }
                            }
                            .frame(width: 24, height: 24)
                            .padding(6)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS))
                        .overlay {
                            if on {
                                RoundedRectangle(cornerRadius: NewCourseStyle.radiusS).strokeBorder(NewCourseStyle.purple, lineWidth: 3)
                            }
                        }
                    Text(verbatim: work.title)
                        .font(.system(size: 13, weight: on ? .bold : .semibold))
                        .foregroundStyle(on ? NewCourseStyle.purple : .primary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(on ? .isSelected : [])

            Button { onLike(work.id) } label: {
                Image(systemName: heart ? "heart.fill" : "heart")
                    .font(.system(size: 14))
                    .foregroundStyle(heart ? Color.red : Color.white)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(.black.opacity(0.35)))
                    .frame(width: 44, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .offset(x: -6, y: -6)
            .disabled(!UnavailableItem.canToggle(delisted: work.delisted, saved: heart))
            .accessibilityLabel(String(format: tr("%@ 찜"), work.title))
            .accessibilityAddTraits(heart ? .isSelected : [])
        }
    }
}

/// 짜는 중 화면 — 고른 포스터 부채꼴, 「코스를 짜고 있어요」 + 요약 칩, 지금 하는 일 세 줄(0 · 3.5 · 8초), [그만두기] (A3).
/// 서버가 단계를 알려 주지 않으므로 시각으로 넘기는 눈속임이다 — 마지막 줄은 끝날 때까지 돈다.
struct PlanningScreen: View {
    let posters: [String]
    let summary: [String]
    let onCancel: () -> Void

    @State private var started = Date()

    private static let stages = ["고른 작품의 촬영지를 모으는 중", "가까운 곳끼리 묶는 중", "하루씩 동선을 잇는 중"]

    var body: some View {
        NewCourseScreen(onBack: nil) {
            VStack(spacing: 22) {
                fan.padding(.top, 24)
                VStack(spacing: 8) {
                    Text("코스를 짜고 있어요")
                        .font(.system(size: 24, weight: .heavy))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    chips
                }
                TimelineView(.periodic(from: started, by: 0.5)) { context in
                    stageList(current: PlanningStages.current(elapsed: context.date.timeIntervalSince(started)))
                }
                Text("조금 걸릴 수 있어요. 다 되면 바로 고칠 수 있게 열어 드려요")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
        } footer: {
            Button(action: onCancel) {
                Text("그만두기")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).fill(Color(.systemBackground)))
                    .overlay(RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).strokeBorder(Color(.separator), lineWidth: 1))
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
        .onAppear { started = Date() }
    }

    private var fan: some View {
        ZStack {
            let shown = Array(posters.prefix(3))
            if shown.isEmpty {
                RoundedRectangle(cornerRadius: NewCourseStyle.radiusM).fill(NewCourseStyle.brandFill)
                    .frame(width: 80, height: 110)
            }
            ForEach(Array(shown.enumerated()), id: \.offset) { index, url in
                let mid = Double(shown.count - 1) / 2
                let shift = Double(index) - mid
                NewCoursePhoto(url: url)
                    .frame(width: 72, height: 108)
                    .clipShape(RoundedRectangle(cornerRadius: NewCourseStyle.radiusS))
                    .shadow(color: .black.opacity(0.14), radius: 10, y: 6)
                    .rotationEffect(.degrees(shift * 9))
                    .offset(x: shift * 46, y: abs(shift) * 8)
            }
        }
        .frame(width: 200, height: 150)
        .accessibilityHidden(true)
    }

    private var chips: some View {
        CenteredFlow(spacing: 6) {
            ForEach(summary, id: \.self) { text in
                Text(verbatim: text)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NewCourseStyle.purple)
                    .lineLimit(1)
                    .padding(.horizontal, 11).padding(.vertical, 5)
                    .background(Capsule().fill(NewCourseStyle.purple.opacity(0.10)))
            }
        }
    }

    private func stageList(current: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(Self.stages.enumerated()), id: \.offset) { index, stage in
                HStack(spacing: 10) {
                    Group {
                        if index < current {
                            Image(systemName: "checkmark.circle.fill").font(.system(size: 20)).foregroundStyle(NewCourseStyle.purple)
                        } else if index == current {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "circle").font(.system(size: 18)).foregroundStyle(Color(.systemGray3))
                        }
                    }
                    .frame(width: 22, height: 22)
                    Text(verbatim: tr(stage)).font(.system(size: 15, weight: index == current ? .bold : .regular))
                }
                .opacity(index > current ? 0.4 : 1)
            }
        }
        .frame(maxWidth: 320, alignment: .leading)
    }
}
