import SceneApiClient
import SwiftUI

/// 내 리뷰 (MZ2AZ-363) — 내가 쓴 리뷰 전부. 촬영지와 편의시설이 섞여 최신순으로 온다(`GET /me/reviews`).
///
/// 줄을 누르면 그 대상의 리뷰 시트가 열린다 — 고치기·지우기는 거기서 한다.
struct MyReviewsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var auth = AuthStore.shared
    @State private var reviews: [MyReview] = []
    @State private var total = 0
    @State private var phase: Phase = .loading
    @State private var moreFailed = false
    @State private var generation = 0
    @State private var opening: MyReview?
    /// 편의시설의 앱 언어 이름 (MZ2AZ-367). 서버(`target.name`)는 편의시설을 한국어 원본으로만 준다 —
    /// 영어 화면에서는 줄이 보일 때 상세를 한 번 물어 카드와 같은 이름으로 바꾼다. 못 받으면 원본 그대로.
    @State private var poiNames: [Int64: PoiLabel] = [:]

    private enum Phase {
        case loading
        case loaded
        case failed
    }

    private static let pageSize = 20

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
                Spacer()
                Text("리뷰를 불러오지 못했어요").font(.subheadline.weight(.medium))
                Button(tr("다시 시도")) {
                    phase = .loading
                    Task { await reload() }
                }
                .font(.subheadline)
                .padding(.top, 8)
                Spacer()
            case .loaded:
                list
            }
        }
        .task { await reload() }
        // 세션이 풀리면 닫는다 — 내 리뷰는 로그인한 사람의 것이고, 남겨 두면 「불러오지 못했어요」 만 반복된다.
        .onChange(of: auth.signedIn) { _, signedIn in
            if !signedIn {
                dismiss()
            }
        }
        .sheet(item: $opening) { review in
            ReviewsSheet(subject: MyReviewRules.subject(of: review.target), title: heading(of: review.target)) {
                // 거기서 고치거나 지웠다 — 내 목록도 다시 읽는다.
                Task { await reload() }
            }
            .presentationDetents([.large])
        }
    }

    private var header: some View {
        ZStack {
            // 「내 리뷰」 는 리뷰 줄의 딱지(My review)이기도 하다 — 목록의 제목은 자리를 달리 적는다.
            Text(tr("내 리뷰", at: "목록")).font(.headline)
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
                if reviews.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "star.bubble")
                            .font(.system(size: 34)).foregroundStyle(.tertiary)
                        Text("아직 쓴 리뷰가 없어요").font(.subheadline.weight(.medium))
                        Text("다녀온 촬영지와 가게에 별점을 남겨 보세요")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 48)
                }
                ForEach(reviews, id: \.id) { review in
                    Button {
                        opening = review
                    } label: {
                        row(review)
                            .padding(.horizontal, 16).padding(.top, 14)
                            .padding(.bottom, review.photos.isEmpty ? 14 : 8)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .task(id: review.target.id) { await lookUpName(of: review.target) }
                    // 사진은 줄의 단추 밖에 둔다 — 줄을 누르면 그 대상의 리뷰로, 사진을 누르면 크게 보기로 간다.
                    if !review.photos.isEmpty {
                        ReviewPhotoThumbs(photos: review.photos, size: 64)
                            .padding(.horizontal, 16).padding(.bottom, 14)
                    }
                    Divider().padding(.leading, 16)
                }
                if ReviewRules.hasMore(loaded: reviews.count, total: total) {
                    moreFooter
                }
            }
        }
    }

    private func row(_ review: MyReview) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            let name = name(of: review.target)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Image(systemName: MyReviewRules.symbol(of: review.target))
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityLabel(MyReviewRules.kindName(of: review.target))
                // 카드와 같은 모양 — 제목, 그 아래 작은 줄. 한 줄로 이으면 뒤쪽(한국어 이름)이 잘렸다.
                VStack(alignment: .leading, spacing: 1) {
                    Text(name.title)
                        .font(.subheadline.weight(.semibold)).lineLimit(1)
                    if let reading = name.reading {
                        Text(reading).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold)).foregroundStyle(.tertiary)
            }
            HStack(spacing: 8) {
                StarRow(rating: review.rating, size: 11)
                Text(dateText(review)).font(.caption2).foregroundStyle(.tertiary)
            }
            if let body = review.body, !body.isEmpty {
                Text(body).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func name(of target: ReviewTarget) -> (title: String, reading: String?) {
        MyReviewRules.name(of: target, lookedUp: poiNames[target.id], korean: AppLanguage.current == .ko)
    }

    /// 리뷰 시트 머리줄 — 카드에서 열 때와 같은 한 줄.
    private func heading(of target: ReviewTarget) -> String {
        let name = name(of: target)
        return PoiLabel.heading(title: name.title, reading: name.reading)
    }

    /// 한 곳에 리뷰는 한 개라 줄마다 한 번이고, 화면에 나온 줄만 묻는다.
    private func lookUpName(of target: ReviewTarget) async {
        guard MyReviewRules.needsLookup(target, korean: AppLanguage.current == .ko), poiNames[target.id] == nil,
              let detail = try? await PoisAPI.getPoi(poiId: target.id)
        else { return }
        poiNames[target.id] = PoiLabel.make(
            name: detail.name, displayName: detail.displayName, nameRoman: detail.nameRoman,
            category: detail.category, categoryLabel: detail.categoryLabel,
            address: detail.address, displayAddress: detail.displayAddress, korean: false
        )
    }

    private func dateText(_ review: MyReview) -> String {
        let when = review.createdAt.formatted(
            .relative(presentation: .named).locale(AppLanguage.currentLocale)
        )
        return ReviewRules.edited(createdAt: review.createdAt, updatedAt: review.updatedAt)
            ? "\(when) · \(tr("수정됨"))"
            : when
    }

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
            .task(id: "\(generation)-\(reviews.count)") { await loadMore() }
        }
    }

    private func reload() async {
        do {
            let page = try await ReviewsAPI.listMyReviews(limit: Self.pageSize, offset: 0)
            guard !Task.isCancelled else { return }
            reviews = page.items
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
        let asked = (generation: generation, offset: reviews.count)
        do {
            let page = try await ReviewsAPI.listMyReviews(limit: Self.pageSize, offset: asked.offset)
            guard !Task.isCancelled, asked.generation == generation, asked.offset == reviews.count else { return }
            let known = Set(reviews.map(\.id))
            let fresh = page.items.filter { !known.contains($0.id) }
            reviews += fresh
            total = fresh.isEmpty ? reviews.count : page.total
        } catch {
            guard !Task.isCancelled, asked.generation == generation else { return }
            moreFailed = true
        }
    }
}

/// 내 리뷰 목록의 순수 규칙.
enum MyReviewRules {
    /// 계약의 대상 → 리뷰 시트가 받는 대상.
    static func subject(of target: ReviewTarget) -> ReviewSubject {
        switch target.type {
        case .place: .place(target.id)
        case .poi: .poi(target.id)
        }
    }

    /// 편의시설의 앱 언어 이름을 앱이 따로 물어야 하는가 — 한국어 화면과 촬영지는 서버가 준 이름이 곧 답이다.
    static func needsLookup(_ target: ReviewTarget, korean: Bool) -> Bool {
        target.type == .poi && !korean
    }

    /// 줄과 리뷰 시트 머리줄에 적을 이름. 물어 온 이름은 편의시설의 영어 화면에서만 쓴다 —
    /// 영어로 받아 둔 채 한국어로 바꾸면 원본으로 돌아간다.
    static func name(
        of target: ReviewTarget, lookedUp: PoiLabel?, korean: Bool
    ) -> (title: String, reading: String?) {
        guard needsLookup(target, korean: korean), let lookedUp else { return (target.name, nil) }
        return (lookedUp.title, lookedUp.reading)
    }

    /// 아이콘만으로는 낭독에서 종류가 안 드러난다 — 이름으로 읽힌다.
    static func kindName(of target: ReviewTarget) -> String {
        switch target.type {
        case .place: tr("촬영지")
        case .poi: tr("편의시설")
        }
    }

    /// 촬영지는 영화 슬레이트, 편의시설은 가게.
    static func symbol(of target: ReviewTarget) -> String {
        switch target.type {
        case .place: "movieclapper"
        case .poi: "storefront"
        }
    }
}
