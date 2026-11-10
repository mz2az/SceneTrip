import SceneApiClient
import SwiftUI

/// 홈 「지금 뜨는 작품」 의 「전체 보기」 — 인기순 작품 전체를 포스터 격자로 (MZ2AZ-547 H6, 웹 MZ2AZ-534 `TrendingWorks.tsx`).
///
/// 전에는 「전체 보기」 가 작품검색 탭을 열어 탭바의 「작품검색」 과 같았다. 이제 홈 위에 덮이는 화면이고 「뒤로」 가 홈으로 돌아간다.
/// 맨 앞은 고정 작품(케이팝 데몬 헌터스, `PinnedWorks`), 나머지는 서버 인기순 그대로다. 포스터를 누르면 홈 선반과 같은 길로
/// 작품검색 탭의 그 작품 상세가 열린다(웹처럼 상세에서 격자로 돌아오지는 않는다 — 덮개를 닫고 탭으로 간다).
struct TrendingWorksView: View {
    let onClose: () -> Void
    let onOpen: (ContentSummary) -> Void

    private enum Phase {
        case loading
        case failed
        case loaded([ContentSummary])
    }

    @State private var phase = Phase.loading

    private let columns = [
        GridItem(.flexible(), spacing: 12, alignment: .top),
        GridItem(.flexible(), spacing: 12, alignment: .top),
    ]

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("인기순").font(.system(size: 13)).foregroundStyle(.secondary)
                        .padding(.horizontal, 20).padding(.top, 6).padding(.bottom, 10)
                    grid
                }
            }
        }
        .background(Color(.systemGroupedBackground))
        .task { await load() }
    }

    private var header: some View {
        ZStack {
            Text("지금 뜨는 작품").font(.system(size: 17, weight: .semibold)).lineLimit(1)
                .padding(.horizontal, 60)
                .accessibilityAddTraits(.isHeader)
            HStack {
                Button(action: onClose) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 20, weight: .semibold))
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .accessibilityLabel(tr("이전"))
                Spacer()
            }
            .padding(.leading, 8)
        }
        .frame(height: 44)
    }

    @ViewBuilder private var grid: some View {
        switch phase {
        case .loading:
            LazyVGrid(columns: columns, spacing: 18) {
                ForEach(0 ..< 6, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 6) {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(Color(.systemGray5))
                            .aspectRatio(108.0 / 144.0, contentMode: .fit)
                        RoundedRectangle(cornerRadius: 4).fill(Color(.systemGray5)).frame(width: 60, height: 11)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.bottom, 28)
            .accessibilityLabel(tr("지금 뜨는 작품"))
        case .failed:
            VStack(spacing: 12) {
                Text("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요")
                    .font(.system(size: 14)).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button(tr("다시 시도")) { Task { await load() } }
                    .buttonStyle(.bordered)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24).padding(.vertical, 48)
        case let .loaded(works) where works.isEmpty:
            Text("검색된 작품이 없어요")
                .font(.system(size: 14)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity).padding(.vertical, 48)
        case let .loaded(works):
            LazyVGrid(columns: columns, spacing: 18) {
                ForEach(works) { work in
                    Button { onOpen(work) } label: { tile(work) }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(work.title), \(String(format: tr("촬영지 %lld곳"), work.placeCount))")
                }
            }
            .padding(.horizontal, 20).padding(.top, 4).padding(.bottom, 28)
        }
    }

    private func tile(_ work: ContentSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(108.0 / 144.0, contentMode: .fit)
                .overlay {
                    ZStack(alignment: .bottomLeading) {
                        LinearGradient(
                            colors: [Color(PinImage.deep).opacity(0.7), Color(PinImage.deep)],
                            startPoint: .topLeading, endPoint: .bottomTrailing
                        )
                        if let url = work.posterUrl {
                            RemoteImage(url: url, symbol: "film", poster: true)
                        }
                        LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .center, endPoint: .bottom)
                        Text(work.title)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(.white)
                            .lineLimit(2)
                            .padding(10)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            Text(String(format: tr("촬영지 %lld곳"), work.placeCount))
                .font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .contentShape(.rect)
    }

    private func load() async {
        if case .failed = phase {
            phase = .loading
        }
        do {
            let works = try await TrendingRules.load(
                fetchPage: { offset in
                    let page = try await ContentsAPI.listContents(limit: TrendingRules.pageLimit, offset: offset)
                    return (page.items, page.total)
                },
                fetchDetail: { try? await ContentsAPI.getContent(contentId: $0) }
            )
            phase = .loaded(works)
        } catch {
            // 들고 있는 목록이 있으면 그대로 둔다 — 새로 고침 실패로 보던 격자를 지우지 않는다.
            if case .loaded = phase {
                return
            }
            phase = .failed
        }
    }
}
