import SceneApiClient
import SwiftUI

/// 리뷰 시트 (MZ2AZ-363) — 요약(평균·수·점수 막대), 정렬, 목록. 촬영지와 편의시설이 같은 화면을 쓴다.
///
/// 읽기는 비회원도 한다. 쓰기·고치기·지우기는 가입한 사람만 — 아래 단추가 `ReviewComposeView` 를 연다.
/// 리뷰 글은 번역하지 않는다 — 쓴 언어 그대로 섞여 온다(서버 결정).
struct ReviewsSheet: View {
    let subject: ReviewSubject
    /// 대상의 이름 — 머리줄에 적는다.
    let title: String
    /// 리뷰가 바뀌었다(쓰기·고치기·지우기) — 부른 화면이 별점 줄을 다시 읽는다.
    var onChanged: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var auth = AuthStore.shared
    /// 쓰기 화면.
    @State private var composing = false
    /// 비회원이 쓰기를 눌러 로그인 화면으로 갔다 — 로그인하면 쓰기로 잇는다.
    @State private var composeAfterSignIn = false
    /// 로그인 뒤 닉네임 화면이 먼저 떴다 — 그것이 닫히면 쓰기로 잇는다.
    @State private var composeAfterNickname = false
    @State private var sort: ReviewSort = .recent
    @State private var reviews: [Review] = []
    @State private var summary: RatingSummary?
    @State private var total = 0
    @State private var phase: Phase = .loading
    /// 첫 쪽을 새로 받을 때마다 오른다 — 다음 쪽 받기의 열쇠에 섞어, 정렬이 바뀌어 같은 개수가 와도 다시 돈다.
    @State private var generation = 0
    /// 다음 쪽을 못 받았다. 돌아가는 표시 대신 「다시 시도」 를 보인다.
    @State private var moreFailed = false

    private enum Phase {
        case loading
        case loaded
        case failed
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            switch phase {
            case .loading:
                Spacer()
                ProgressView()
                Spacer()
            case .failed:
                failure
            case .loaded:
                list
                Divider()
                writeBar
            }
        }
        // 이 시트가 맨 위 화면이다 — 로그인·닉네임 화면은 여기서 올린다.
        .signInSheet()
        .nicknameSheet()
        .sheet(isPresented: $composing) {
            ReviewComposeView(subject: subject, title: title) {
                onChanged()
                Task { await reload() }
            }
        }
        .onChange(of: auth.signedIn) { _, signedIn in
            // 로그인 화면이 내려간 뒤에 쓰기 화면을 올린다 — 내려가는 중에는 안 뜬다.
            guard signedIn, composeAfterSignIn else { return }
            composeAfterSignIn = false
            Task {
                // 처음 가입한 사람에게는 닉네임 화면이 먼저 뜬다(로그인 600ms 뒤) — 그것을 기다린 다음 본다.
                try? await Task.sleep(for: .milliseconds(1000))
                guard auth.signedIn else { return }
                if auth.askingNickname {
                    composeAfterNickname = true
                } else {
                    composing = true
                }
            }
        }
        .onChange(of: auth.askingNickname) { _, asking in
            // 닉네임을 정했거나 건너뛰었다 — 리뷰를 쓰러 가입한 사람이 다시 누르지 않게 쓰기로 잇는다.
            guard !asking, composeAfterNickname else { return }
            composeAfterNickname = false
            Task {
                try? await Task.sleep(for: .milliseconds(600))
                if auth.signedIn {
                    composing = true
                }
            }
        }
        // 로그인하면 「내 리뷰」 표시가 달라진다 — 목록을 다시 읽는다.
        .onAccountChange { await reload() }
        .onChange(of: auth.showingSignIn) { _, showing in
            // 로그인하지 않고 닫았으면 잇지 않는다.
            if !showing, !auth.signedIn {
                composeAfterSignIn = false
            }
        }
        .task(id: sort) { await reload() }
        // 정렬을 바꾸면 새 첫 쪽이 올 때까지 다음 쪽을 받지 않는다 — 옛 목록 끝에 새 정렬의 둘째 쪽이 붙지 않게.
        .onChange(of: sort) { _, _ in total = reviews.count }
        .onAppear { AppAnalytics.log(.viewReviews(targetType: subject.kind)) }
    }

    private var header: some View {
        ZStack {
            VStack(spacing: 1) {
                Text("리뷰").font(.headline)
                Text(title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.horizontal, 56)
            HStack {
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tr("닫기"))
            }
        }
        .padding(.horizontal, 6).padding(.top, 6).padding(.bottom, 4)
    }

    private var list: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                // 리뷰가 없으면 요약(큰 「-」 와 빈 막대 다섯)을 그리지 않는다 — 아래 빈 상태가 같은 말을 한다.
                if let summary, summary.count > 0 {
                    ReviewSummaryView(summary: summary)
                        .padding(.horizontal, 16).padding(.vertical, 16)
                    Divider()
                }
                if reviews.isEmpty {
                    empty
                } else {
                    sortPicker
                    ForEach(reviews, id: \.id) { review in
                        ReviewRow(review: review, onEdit: review.isMine ? { composing = true } : nil)
                            .padding(.horizontal, 16).padding(.vertical, 14)
                        Divider().padding(.leading, 16)
                    }
                    if ReviewRules.hasMore(loaded: reviews.count, total: total) {
                        moreFooter
                    }
                }
            }
        }
    }

    /// 목록 끝 — 닿으면 다음 쪽을 받는다. 못 받으면 돌아가는 표시를 남겨 두지 않고 다시 시도를 준다.
    @ViewBuilder
    private var moreFooter: some View {
        if moreFailed {
            Button {
                moreFailed = false
            } label: {
                Text("더 불러오지 못했어요 · 다시 시도")
                    .font(.footnote)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 18)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.accentColor)
        } else {
            HStack {
                Spacer()
                ProgressView()
                Spacer()
            }
            .padding(.vertical, 18)
            // 열쇠가 바뀌면 앞 작업은 취소되고 새로 돈다 — 플래그로 막지 않는다.
            .task(id: "\(generation)-\(reviews.count)") { await loadMore() }
        }
    }

    /// 아래에 붙은 쓰기 단추. 내 리뷰가 목록에 보이면 「내 리뷰 고치기」.
    private var writeBar: some View {
        Button {
            write()
        } label: {
            Label(hasMine ? tr("내 리뷰 고치기") : tr("리뷰 쓰기"), systemImage: "square.and.pencil")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(Capsule().fill(Color.accentColor))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    /// 받은 쪽에 내 리뷰가 있는가. 아직 안 받은 뒤쪽에 있을 수도 있다 — 그때도 쓰기 화면이 내 리뷰를 불러와 고치기로 연다.
    private var hasMine: Bool {
        reviews.contains(where: \.isMine)
    }

    /// 가입한 사람만 쓴다 — 비회원이면 로그인 화면을 먼저 올리고, 로그인하면 쓰기로 잇는다.
    private func write() {
        if auth.signedIn {
            composing = true
        } else {
            composeAfterSignIn = true
            auth.promptSignIn()
        }
    }

    private var sortPicker: some View {
        Picker(tr("정렬"), selection: $sort) {
            Text("최신순").tag(ReviewSort.recent)
            Text("별점 높은 순").tag(ReviewSort.ratingHigh)
            Text("별점 낮은 순").tag(ReviewSort.ratingLow)
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 4)
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Image(systemName: "star.bubble")
                .font(.system(size: 34)).foregroundStyle(.tertiary)
            Text("아직 리뷰가 없어요").font(.subheadline.weight(.medium))
            Text("다녀온 분의 이야기가 여기에 모여요")
                .font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }

    private var failure: some View {
        VStack(spacing: 10) {
            Spacer()
            Text("리뷰를 불러오지 못했어요").font(.subheadline.weight(.medium))
            Button(tr("다시 시도")) {
                phase = .loading
                Task { await reload() }
            }
            .font(.subheadline)
            Spacer()
        }
    }

    /// 첫 쪽부터 다시. 정렬이 바뀌어도 이것이 돈다.
    private func reload() async {
        do {
            let page = try await subject.reviews(sort: sort, offset: 0)
            guard !Task.isCancelled else { return }
            reviews = page.items
            summary = page.summary
            total = page.total
            moreFailed = false
            generation += 1
            phase = .loaded
        } catch {
            guard !Task.isCancelled else { return }
            phase = .failed
        }
    }

    private func loadMore() async {
        let asked = (sort: sort, generation: generation, offset: reviews.count)
        do {
            let page = try await subject.reviews(sort: asked.sort, offset: asked.offset)
            // 받는 사이 정렬이 바뀌었거나 첫 쪽을 새로 받았으면 버린다.
            guard !Task.isCancelled, asked.generation == generation, asked.offset == reviews.count else { return }
            // 받는 사이 새 리뷰가 끼어들면 같은 리뷰가 두 번 올 수 있다 — id 로 거른다.
            let known = Set(reviews.map(\.id))
            let fresh = page.items.filter { !known.contains($0.id) }
            reviews += fresh
            // 더 올 것이 없으면(빈 쪽) 끝낸다 — 안 그러면 끝에서 계속 묻는다.
            total = fresh.isEmpty ? reviews.count : page.total
        } catch {
            guard !Task.isCancelled, asked.generation == generation else { return }
            moreFailed = true
        }
    }
}

/// 요약 — 왼쪽에 평균과 리뷰 수, 오른쪽에 5~1점 막대.
struct ReviewSummaryView: View {
    let summary: RatingSummary

    var body: some View {
        HStack(alignment: .center, spacing: 20) {
            VStack(spacing: 4) {
                Text(ReviewRules.average(summary.average))
                    .font(.system(size: 38, weight: .bold, design: .rounded))
                StarRow(value: summary.average ?? 0, size: 12)
                Text(ReviewRules.countText(summary.count))
                    .font(.caption).foregroundStyle(.secondary)
            }
            .frame(minWidth: 84)
            VStack(spacing: 5) {
                ForEach(ReviewRules.bars(summary.distribution), id: \.score) { bar in
                    HStack(spacing: 8) {
                        Text(verbatim: "\(bar.score)")
                            .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                            .frame(width: 10)
                        GeometryReader { proxy in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color(.systemGray5))
                                Capsule()
                                    .fill(Color(red: 1.0, green: 0.72, blue: 0.0))
                                    .frame(width: proxy.size.width * bar.share)
                            }
                        }
                        .frame(height: 6)
                        Text(verbatim: "\(bar.count)")
                            .font(.caption2.monospacedDigit()).foregroundStyle(.tertiary)
                            .frame(width: 26, alignment: .trailing)
                    }
                }
            }
        }
        // 막대의 숫자 열 개를 그대로 읽히면 뜻이 없다 — 한 문장으로 읽힌다.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ReviewRules.spoken(summary))
    }
}

/// 별 다섯. 리뷰 한 건은 정수(1~5), 평균은 반 개까지 그린다 — 4.6 을 다섯 개로 채우면 5.0 으로 읽힌다.
struct StarRow: View {
    let value: Double
    var size: CGFloat = 12

    init(rating: Int, size: CGFloat = 12) {
        value = Double(rating)
        self.size = size
    }

    init(value: Double, size: CGFloat = 12) {
        self.value = value
        self.size = size
    }

    var body: some View {
        HStack(spacing: 1.5) {
            ForEach(Array(ReviewRules.stars(for: value).enumerated()), id: \.offset) { _, star in
                Image(systemName: star.symbol)
                    .font(.system(size: size))
                    .foregroundStyle(
                        star == .empty ? Color(.systemGray3) : Color(red: 1.0, green: 0.72, blue: 0.0)
                    )
            }
        }
        .accessibilityElement()
        .accessibilityLabel(String(format: tr("별점 %@점"), ReviewRules.starValue(value)))
    }
}
