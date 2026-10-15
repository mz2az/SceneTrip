import SceneApiClient
import SwiftUI

/// 긴 개요·큰 글자도 카드 밖으로 밀어내지 않고 읽는다. 값 없는 안내에는 공간을 잡지 않는다.
struct PoiInfoSection: View {
    let info: PoiInfo?

    private let maximumHeight: CGFloat = 220

    var body: some View {
        if PoiInfoRules.isVisible(info) {
            ViewThatFits(in: .vertical) {
                content.fixedSize(horizontal: false, vertical: true)
                ScrollView { content }
            }
            .frame(maxHeight: maximumHeight)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14).padding(.top, 10)
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(PoiInfoRules.rows(info)) { row in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(verbatim: row.label).font(.caption.weight(.semibold))
                        if let tag = row.languageTag {
                            Text(verbatim: tag).font(.caption2)
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(Capsule().fill(Color(.systemGray5)))
                        }
                    }
                    .foregroundStyle(.secondary)
                    // 서버가 준 자유 문장이다. 줄바꿈·문단을 보존하며 시각 계산이나 링크 감지를 하지 않는다.
                    Text(verbatim: row.text).font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let url = PoiInfoRules.homepage(info) {
                Link(destination: url) {
                    Label(tr("홈페이지"), systemImage: "arrow.up.right.square")
                        .font(.caption.weight(.semibold))
                        .frame(minHeight: 44)
                }
            }
        }
    }
}
