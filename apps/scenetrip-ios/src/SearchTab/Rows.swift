import SwiftUI

/// 작품 탭 한 줄. 배우로 걸린 작품에는 "출연 김수현" 배지를 달아 제목으로 걸린 것과
/// 구분한다 (계획서 §3-3).
struct WorkRow: View {
    let work: Work
    let badge: String?

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color(.systemGray5))
                .frame(width: 46, height: 62)
                .overlay(Image(systemName: "film").foregroundStyle(.secondary))

            VStack(alignment: .leading, spacing: 4) {
                Text(work.title).font(.headline).foregroundStyle(.primary)
                Text(work.head.workMeta).font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    Text("촬영지 \(work.placeCount)")
                        .font(.caption2)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Capsule().fill(Color(.systemGray6)))
                    if let badge {
                        Text(badge)
                            .font(.caption2)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Capsule().fill(Color.accentColor.opacity(0.15)))
                            .foregroundStyle(Color.accentColor)
                    }
                }
                .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .contentShape(.rect)
    }
}

/// 장소 탭 한 줄.
struct PlaceRow: View {
    let row: SceneRow

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 6)
                .fill(Color(.systemGray5))
                .frame(width: 54, height: 54)
                .overlay(Image(systemName: "mappin.and.ellipse").foregroundStyle(.secondary))

            VStack(alignment: .leading, spacing: 3) {
                Text(row.placeName).font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(row.address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Text("\(row.title) · \(row.placeType)")
                    .font(.caption2).foregroundStyle(.tertiary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .contentShape(.rect)
    }
}

/// 드릴다운 2단 — 작품 상세. 그 작품의 촬영지 목록이 온다.
struct WorkDetail: View {
    let work: Work
    @Binding var chip: String
    let rows: [SceneRow]
    let onSelect: (SceneRow) -> Void
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            DetailHeader(title: work.title, subtitle: work.head.workMeta, onBack: onBack)
            ChipRow(selected: $chip)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(rows) { row in
                        Button { onSelect(row) } label: { PlaceRow(row: row) }
                            .buttonStyle(.plain)
                        Divider().padding(.leading, 14)
                    }
                }
            }
        }
    }
}

/// 드릴다운 3단 — 촬영지 상세.
struct PlaceDetail: View {
    let row: SceneRow
    let others: [SceneRow]
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            DetailHeader(title: row.placeName, subtitle: row.address, onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if !row.sceneDesc.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("장면").font(.caption).foregroundStyle(.secondary)
                            Text(row.sceneDesc).font(.subheadline)
                        }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text("작품").font(.caption).foregroundStyle(.secondary)
                        Text("\(row.title) · \(row.placeType)").font(.subheadline)
                    }
                    if !others.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("이곳에서 촬영한 다른 작품").font(.caption)
                                .foregroundStyle(.secondary)
                            ForEach(others) { other in
                                Text("· \(other.title)").font(.subheadline)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
            }
        }
    }
}

struct DetailHeader: View {
    let title: String
    let subtitle: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onBack) {
                Image(systemName: "chevron.left").font(.body.weight(.medium))
            }
            .foregroundStyle(.primary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
        }
        .padding(.horizontal, 14).padding(.bottom, 10)
    }
}
