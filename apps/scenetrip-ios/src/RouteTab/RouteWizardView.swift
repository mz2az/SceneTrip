import SceneApiClient
import SwiftUI

/// 코스를 만들기 전에 묻는 질문 흐름 (8/11 회의 3-3 · 3-4 확정).
///
/// 묻는 것과 묻지 않는 것이 회의에서 하나씩 정해졌다.
///
/// | 묻는다 | 묻지 않는다 |
/// | --- | --- |
/// | 기간 (당일치기 ~ 5박 6일) | **시작 시각** — 회의 중 삭제 확정 |
/// | 떠나는 날 (**선택**, 종료일은 자동) | 돌아오는 날 |
/// | 어떤 작품을 좋아하나요 | 테마 |
/// | 빡빡하게 / 널널하게 | 지역 (지금은 서울뿐) |
///
/// 「직접 짜기」는 기간까지만 묻는다. 직접 짜는 사람에게 작품과 스타일을 묻는 것은
/// 쓸모가 없다 — 어차피 자기가 하나씩 담는다.
///
/// 답을 다 받으면 **바로 저장하지 않고 편집 화면으로 넘긴다.** 회의 확정:
/// *"AI로 초안을 만들고 바로 저장시키는 게 아니고, 거기서 수정할 수 있게 해 줘야"*.
struct RouteWizardView: View {
    let kind: RouteWizardKind

    @EnvironmentObject private var store: RouteStore
    @Environment(\.dismiss) private var dismiss

    @State private var index = 0
    @State private var span: RouteSpan = .oneNight
    @State private var hasDate = false
    @State private var pickedDate = Date()
    @State private var workIds: Set<Int64> = []
    @State private var pace: RoutePace = .tight

    /// 만들어진 초안. 있는 동안에는 이 시트가 편집 화면으로 바뀐다 — 시트를 새로 띄우면
    /// 닫히고 열리는 사이에 화면이 한 번 비어 사용자가 흐름을 잃는다.
    @State private var draft: RouteCourse?

    enum Step { case span, dates, works, pace, review }

    private var steps: [Step] {
        kind == .aiPlan ? [.span, .dates, .works, .pace, .review] : [.span, .dates]
    }

    private var step: Step {
        steps[min(index, steps.count - 1)]
    }

    private var startDate: Date? {
        hasDate ? pickedDate : nil
    }

    var body: some View {
        if let draft {
            RouteEditorView(course: draft, isNew: true)
        } else {
            questions
        }
    }

    private var questions: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(title).font(.title3.weight(.semibold))
                        .padding(.horizontal, 20)
                    content.padding(.horizontal, 20)
                }
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            footer
        }
        .background(Color(.systemGroupedBackground))
    }

    // MARK: 머리와 발

    private var header: some View {
        VStack(spacing: 10) {
            HStack {
                Button("취소") { dismiss() }
                Spacer()
                Text("\(index + 1) / \(steps.count)")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            ProgressView(value: Double(index + 1), total: Double(steps.count))
                .tint(.accentColor)
            // AI 가 일한다는 사실을 질문 화면 내내 띄워 둔다 (회의에서 가장 강하게
            // 요구된 항목). 「직접 짜기」에는 띄우지 않는다 — 짜는 주체가 사용자다.
            if kind == .aiPlan {
                RouteAIBanner(text: "AI 가 일정을 짜 드립니다")
            }
        }
        .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 10)
        .background(Color(.systemBackground))
    }

    private var footer: some View {
        HStack(spacing: 10) {
            if index > 0 {
                Button("이전") { index -= 1 }
                    .buttonStyle(.bordered)
            }
            // 라벨에 너비를 준다. 버튼 바깥에 `.frame` 을 걸면 **누르는 자리만
            // 넓어지고 파란 알약은 글자 크기 그대로** 남는다(실측).
            Button {
                advance()
            } label: {
                Text(isLast ? finishLabel : "다음")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding(16)
        .background(Color(.systemBackground))
    }

    private var isLast: Bool {
        index == steps.count - 1
    }

    private var finishLabel: String {
        kind == .aiPlan ? "AI 로 일정 짜기" : "코스 만들기"
    }

    private var title: String {
        switch step {
        case .span: "얼마나 다녀오나요?"
        case .dates: "언제 떠나나요?"
        // 회의에서 문구까지 정했다 (1부 54:01).
        case .works: "어떤 작품을 좋아하나요?"
        case .pace: "어떻게 다닐까요?"
        case .review: "이렇게 짜 드립니다"
        }
    }

    private func advance() {
        guard isLast else {
            index += 1
            return
        }
        draft = kind == .aiPlan
            ? store.aiDraft(span: span, startDate: startDate, workIds: workIds, pace: pace)
            : store.emptyCourse(span: span, startDate: startDate)
    }

    // MARK: 질문

    @ViewBuilder private var content: some View {
        switch step {
        case .span: spanStep
        case .dates: dateStep
        case .works: workStep
        case .pace: paceStep
        case .review: reviewStep
        }
    }

    /// 기간. **몇 밤을 자는지**로 묻는다 — "여행 일수 2일" 이라고 쓰면 사용자가 밤 수를
    /// 다시 세어야 한다. 6개를 넘는 여정은 만든 뒤 일차 ＋ 로 늘린다.
    private var spanStep: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 10) {
            ForEach(RouteSpan.allCases) { each in
                let isOn = span == each
                Text(each.label)
                    .font(.subheadline.weight(isOn ? .semibold : .regular))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .fill(isOn ? Color.accentColor : Color(.systemBackground))
                    )
                    .foregroundStyle(isOn ? .white : .primary)
                    .onTapGesture { span = each }
            }
        }
    }

    /// 떠나는 날. **정하지 않아도 다음으로 넘어갈 수 있다** (회의 확정).
    /// 돌아오는 날은 묻지 않고 기간에서 계산해 보여만 준다.
    private var dateStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            DatePicker(
                "떠나는 날",
                selection: $pickedDate,
                in: Date()...,
                displayedComponents: .date
            )
            .datePickerStyle(.graphical)
            .padding(.horizontal, 6)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
            .onChange(of: pickedDate) { _, _ in hasDate = true }

            if hasDate, let back = returnDate {
                Label("돌아오는 날 \(RouteFormat.day(back)) · 자동", systemImage: "arrow.uturn.left")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("날짜 지우기") { hasDate = false }
                    .font(.footnote)
            } else {
                Text("날짜는 나중에 정해도 됩니다")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var returnDate: Date? {
        Calendar.current.date(byAdding: .day, value: span.nights, to: pickedDate)
    }

    /// 작품. **찜한 것이 먼저, 나머지는 인기도순** (회의 확정). 하트는 찜, 체크는 선택 —
    /// 8/11 회의에서 "작품에는 하트, 장소에는 플러스" 로 갈라 놓은 그 표시다.
    private var workStep: some View {
        VStack(spacing: 0) {
            ForEach(store.works, id: \.id) { work in
                workRow(work)
                Divider()
            }
            Text("고르지 않으면 인기 작품의 촬영지에서 뽑습니다")
                .font(.caption).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 10)
        }
    }

    private func workRow(_ work: ContentSummary) -> some View {
        HStack(spacing: 12) {
            Button {
                store.toggleFavorite(work.id)
            } label: {
                Image(systemName: store.isFavorite(work.id) ? "heart.fill" : "heart")
                    .foregroundStyle(store.isFavorite(work.id) ? .pink : .secondary)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                Text(work.title).font(.subheadline.weight(.semibold))
                Text([work.broadcaster, work.releaseYear.map(String.init)]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: workIds.contains(work.id) ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(workIds.contains(work.id) ? Color.accentColor : Color(.systemGray3))
        }
        .padding(.vertical, 10)
        .contentShape(.rect)
        .onTapGesture {
            if workIds.contains(work.id) {
                workIds.remove(work.id)
            } else {
                workIds.insert(work.id)
            }
        }
    }

    /// 빡빡하게 / 널널하게. **아직 일정을 바꾸지 않는다** — 로직이 회의에서 정해지지
    /// 않았다. 그 사실을 화면에도 적어 둔다: 팀이 목업을 보고 "되는 줄 알았다" 고
    /// 착각하는 것이 정하지 않았다는 사실보다 나쁘다.
    private var paceStep: some View {
        VStack(spacing: 10) {
            ForEach(RoutePace.allCases) { each in
                let isOn = pace == each
                HStack(spacing: 12) {
                    Image(systemName: each.symbol)
                        .foregroundStyle(isOn ? .white : Color.accentColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(each.rawValue).font(.subheadline.weight(.semibold))
                        Text(each.caption).font(.caption)
                            .foregroundStyle(isOn ? .white.opacity(0.9) : .secondary)
                    }
                    Spacer()
                }
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(isOn ? Color.accentColor : Color(.systemBackground))
                )
                .foregroundStyle(isOn ? .white : .primary)
                .onTapGesture { pace = each }
            }
            Text("이 답이 일정을 어떻게 바꿀지는 아직 정하지 않았습니다")
                .font(.caption2).foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var reviewStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            summary("기간", span.label)
            Divider()
            summary("떠나는 날", hasDate ? RouteFormat.day(pickedDate) : "정하지 않음")
            Divider()
            summary("작품", pickedWorkTitles)
            Divider()
            summary("스타일", pace.rawValue)
        }
        .padding(.horizontal, 14)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemBackground)))
    }

    private var pickedWorkTitles: String {
        let titles = store.works.filter { workIds.contains($0.id) }.map(\.title)
        return titles.isEmpty ? "인기 작품" : titles.joined(separator: ", ")
    }

    private func summary(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.subheadline.weight(.medium))
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, 12)
    }
}
