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
    /// 이 리뷰부터 보인다 — 사진첩의 방문자 사진에서 「리뷰 보기」 로 왔다(티켓 §4).
    var focusReviewId: Int64?
    /// 리뷰가 바뀌었다(쓰기·고치기·지우기) — 부른 화면이 별점 줄과 사진을 다시 읽는다.
    var onChanged: () -> Void = {}

    /// 이 대상의 사진첩 — 위쪽의 방문자 사진 모아 보기가 쓴다.
    @StateObject private var gallery: PhotoGallery
    /// 목록을 이 리뷰까지 내린다.
    @State private var jump: Int64?
    /// 방금 찾아간 리뷰 — 잠깐 바탕을 칠해 어느 줄인지 보인다.
    @State private var lit: Int64?

    init(subject: ReviewSubject, title: String, focusReviewId: Int64? = nil, onChanged: @escaping () -> Void = {}) {
        self.subject = subject
        self.title = title
        self.focusReviewId = focusReviewId
        self.onChanged = onChanged
        _gallery = StateObject(wrappedValue: PhotoGallery(subject: subject))
    }

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
            SheetHeader(title: tr("리뷰"), subtitle: title) { dismiss() }
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
                Task {
                    await reload()
                    // 리뷰의 사진이 바뀌었을 수 있다.
                    await reloadPhotos()
                }
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
        .task { await reloadPhotos() }
        .task {
            // 사진첩에서 왔으면 그 리뷰부터 — 첫 쪽이 온 뒤에 찾는다.
            if let focusReviewId {
                await show(review: focusReviewId)
            }
        }
        // 정렬을 바꾸면 새 첫 쪽이 올 때까지 다음 쪽을 받지 않는다 — 옛 목록 끝에 새 정렬의 둘째 쪽이 붙지 않게.
        .onChange(of: sort) { _, _ in total = reviews.count }
        .onAppear { AppAnalytics.log(.viewReviews(targetType: subject.kind)) }
    }

    private var list: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    // 리뷰가 없으면 요약(큰 「-」 와 빈 막대 다섯)을 그리지 않는다 — 아래 빈 상태가 같은 말을 한다.
                    if let summary, summary.count > 0 {
                        ReviewSummaryView(summary: summary)
                            .padding(.horizontal, 16).padding(.vertical, 16)
                        Divider()
                    }
                    // 방문자 사진 모아 보기 — 한 장도 없으면 이 절은 없다.
                    if !gallery.book.reviewPhotos.isEmpty {
                        VisitorPhotoStrip(gallery: gallery, title: title) { reviewId in
                            Task { await show(review: reviewId, after: .milliseconds(450)) }
                        }
                        Divider()
                    }
                    if reviews.isEmpty {
                        empty
                    } else {
                        sortPicker
                        ForEach(reviews, id: \.id) { review in
                            ReviewRow(review: review, onEdit: review.isMine ? { composing = true } : nil)
                                .padding(.horizontal, 16).padding(.vertical, 14)
                                .background(Color.accentColor.opacity(lit == review.id ? 0.1 : 0))
                                .id(review.id)
                            Divider().padding(.leading, 16)
                        }
                        if ReviewRules.hasMore(loaded: reviews.count, total: total) {
                            moreFooter
                        }
                    }
                }
            }
            .onChange(of: jump) { _, target in
                guard let target else { return }
                withAnimation { proxy.scrollTo(target, anchor: .top) }
                jump = nil
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
}

/// 받아 오기 — 첫 쪽, 다음 쪽, 방문자 사진, 한 리뷰를 찾아가기.
private extension ReviewsSheet {
    /// 첫 쪽부터 다시. 정렬이 바뀌어도 이것이 돈다.
    func reload() async {
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

    /// 방문자 사진. 목록과 따로 받는다 — 못 받아도 리뷰는 보이고, 정렬을 바꿀 때는 다시 받지 않는다.
    func reloadPhotos() async {
        await gallery.reload()
        await gallery.seekReviews()
    }

    /// 목록을 그 리뷰까지 내리고 잠깐 칠한다. 아직 안 받은 뒤쪽에 있으면 다섯 쪽까지 더 받아 찾는다 —
    /// 그래도 없으면(내려진 리뷰, 아주 뒤쪽) 목록은 그대로 둔다.
    /// `after` — 크게 보기가 내려가는 동안 기다린다(내려가는 중에는 스크롤이 먹지 않는다).
    func show(review id: Int64, after delay: Duration = .zero) async {
        try? await Task.sleep(for: delay)
        // 첫 쪽이 올 때까지 — 길어야 5초.
        for _ in 0 ..< 50 where phase == .loading {
            try? await Task.sleep(for: .milliseconds(100))
        }
        var tries = 0
        while tries < 5, !has(id), ReviewRules.hasMore(loaded: reviews.count, total: total) {
            await loadMore()
            tries += 1
        }
        guard has(id) else { return }
        jump = id
        withAnimation { lit = id }
        try? await Task.sleep(for: .seconds(2))
        withAnimation { lit = nil }
    }

    func has(_ id: Int64) -> Bool {
        reviews.contains { $0.id == id }
    }

    func loadMore() async {
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
