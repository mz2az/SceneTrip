import SceneApiClient
import SwiftUI

/// 자동완성 패널 (§3-3).
///
/// 랭킹과 갈래(작품·인물·장소)는 **서버가 정한다** — `GET /v1/search/suggestions`
/// 가 type 으로 구분해 돌려주고, 명세에 "앞글자 일치 우선, 동점이면 인기도순"
/// 이라고 적혀 있다. 프론트가 같은 규칙을 두 번 구현하면 iOS·Android 가 갈린다.
/// 여기서는 그 순서를 **섹션으로 배치만** 한다 — 첫 작품은 포스터 카드로, 장소는
/// 장소끼리, 전체는 연관 검색어 칩으로.
struct SuggestionPanel: View {
    let draft: String
    let suggestions: [Suggestion]

    /// 가장 연관된 작품 — 포스터·메타와 함께 맨 위에 뜨고, 누르면 작품 상세로 바로
    /// 들어간다 (검색어 치환이 아니다). suggest 응답의 첫 작품을 부모가
    /// `GET /contents/{id}` 로 채운 것이다.
    let topWork: ContentDetail?
    /// 두 번째 값은 **고른 것의 갈래** 다. 어느 탭을 열지 화면이 그것으로 정한다.
    /// 갈래를 모르는 경우(직접 입력 후 엔터)는 nil 이다.
    let onCommit: (String, EntityType?) -> Void
    let onOpenWork: (ContentDetail) -> Void

    /// 장소 제안을 골랐다 — 검색어 커밋이 아니라 **그 장소로 이동**하는 동작이다.
    let onSelectPlace: (Suggestion) -> Void

    /// 검색창이 비었을 때 보여 주는 추천. 갈래별로 하나씩 둔다 — 셋이 각각 작품·
    /// 인물·장소라서 어느 탭이 열리는지도 함께 익힌다.
    private static let recommended: [(term: String, type: EntityType)] = [
        ("도깨비", .content),
        ("공유", .person),
        ("북촌한옥마을", .place),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if draft.isEmpty {
                sectionLabel("추천 검색어")
                // 글자만 두면 **고른 것이 장소인지 작품인지 알 수 없어** 늘 작품 탭이
                // 열렸다(실측: 북촌한옥마을을 눌러도 작품 탭). 갈래를 함께 적는다.
                ForEach(Self.recommended, id: \.term) { item in
                    Button { onCommit(item.term, item.type) } label: {
                        row(icon: symbol(item.type), text: item.term, detail: "")
                    }
                    .buttonStyle(.plain)
                }
            } else {
                if let topWork {
                    workCard(topWork)
                    Divider().padding(.leading, 14)
                }

                // 장소 행은 3개까지만 — 패널은 위(검색창)에 붙은 채 내용만큼만
                // 아래로 자라므로, 내용이 키보드까지 닿지 않게 총높이를 여기서
                // 붙든다. 나머지 장소는 연관 검색어 칩으로도 닿을 수 있다.
                let placeItems = suggestions.filter { $0.type == .place }.prefix(3)
                if !placeItems.isEmpty {
                    sectionLabel("장소")
                    ForEach(Array(placeItems), id: \.self) { item in
                        Button { onSelectPlace(item) } label: {
                            row(
                                icon: "mappin.and.ellipse",
                                text: item.name,
                                detail: item.subtitle ?? "",
                                alias: alias(of: item)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }

                if !suggestions.isEmpty {
                    sectionLabel("연관 검색어")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(suggestions, id: \.self) { item in
                                Button {
                                    // 칩도 갈래대로 움직인다 — 장소 칩은 그 장소로.
                                    if item.type == .place {
                                        onSelectPlace(item)
                                    } else {
                                        onCommit(item.name, item.type)
                                    }
                                } label: {
                                    Label(item.name, systemImage: symbol(item.type))
                                        .font(.caption)
                                        .padding(.horizontal, 10).padding(.vertical, 6)
                                        .background(Capsule().fill(Color(.systemGray6)))
                                        .foregroundStyle(.primary)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 14)
                    }
                    .padding(.vertical, 10)
                }
            }
        }
        .background(Color(.systemBackground))
        .clipShape(.rect(cornerRadius: 14))
        .shadow(color: .black.opacity(0.12), radius: 10, y: 3)
        .padding(.horizontal, 14)
        // 높이 프레임을 걸지 않는다 — `maxHeight` 프레임은 늘 그 높이를 차지하고
        // 내용을 세로 가운데에 놓아서, 내용이 적으면 검색창에서 떨어져 보이고
        // 많으면 위로 넘쳐 검색창을 덮는다(실측). 패널은 내용만큼만 아래로 자란다.
    }

    private func workCard(_ work: ContentDetail) -> some View {
        Button { onOpenWork(work) } label: {
            HStack(spacing: 12) {
                RemoteImage(url: work.posterUrl, symbol: "film")
                    .frame(width: 46, height: 62)
                    .clipShape(.rect(cornerRadius: 6))
                VStack(alignment: .leading, spacing: 4) {
                    Text(work.title).font(.headline).foregroundStyle(.primary)
                    Text(meta(of: work)).font(.caption).foregroundStyle(.secondary)
                    if let alias = alias(of: work) {
                        aliasBadge(alias)
                    }
                }
                Spacer()
                Image(systemName: "arrow.up.left").font(.caption).foregroundStyle(.tertiary)
            }
            .padding(14)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private func meta(of work: ContentDetail) -> String {
        [
            work.broadcaster,
            work.releaseYear.map(String.init),
            work.genres?.joined(separator: " "),
        ]
        .compactMap { $0 }
        .filter { !$0.isEmpty }
        .joined(separator: " · ")
    }

    /// 별칭으로 걸렸으면 그 표기를, 아니면 첫 별칭(영어 제목)을 배지로 보여 준다.
    private func alias(of work: ContentDetail) -> String? {
        let matched = suggestions.first { $0.type == .content && $0.id == work.id }
        if let term = matched?.matchedTerm, term != work.title {
            return term
        }
        return work.aliases?.first
    }

    /// `matchedTerm` 은 실제로 걸린 표기다 — 별칭으로 걸렸을 때만 이름과 다르므로
    /// 그때만 보여 준다.
    private func alias(of item: Suggestion) -> String? {
        guard let term = item.matchedTerm, term != item.name else { return nil }
        return term
    }

    private func aliasBadge(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Capsule().fill(Color.accentColor.opacity(0.12)))
            .foregroundStyle(Color.accentColor)
    }

    private func symbol(_ type: EntityType) -> String {
        switch type {
        case .content: "film"
        case .person: "person"
        case .place: "mappin.and.ellipse"
        }
    }

    private func sectionLabel(_ title: String) -> some View {
        HStack {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 4)
    }

    private func row(icon: String, text: String, detail: String, alias: String? = nil) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).frame(width: 18).foregroundStyle(.secondary)
            Text(text).foregroundStyle(.primary)
            if let alias {
                aliasBadge(alias)
            }
            Spacer()
            if !detail.isEmpty {
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .contentShape(.rect)
    }
}
