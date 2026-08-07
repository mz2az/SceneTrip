import SceneApiClient
import SwiftUI

/// 장바구니 시트 — 담은 장소를 보여 주고 뺄 수 있게 한다.
///
/// 베타의 `showCartSheet` 와 같은 범위다. 그 주석이 경계를 정확히 적어 뒀다 —
/// **"담기까지만 (루트 만들기는 MVP1 범위 밖)"**. 즉 담긴 것을 보고 빼는 것까지가
/// 여기이고, 이 목록을 경로(코스)로 엮는 것은 별도 에픽이다 (계획서 §2).
///
/// 순번을 매기는 이유도 거기에 있다. 담은 순서가 나중에 코스의 기본 순서가 된다 —
/// 계약도 "담은 순서(오래된 것부터)로 돌려준다" 고 적어 뒀다.
struct CartSheet: View {
    @EnvironmentObject private var cart: CartStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if cart.items.isEmpty {
                    ContentUnavailableView(
                        "담은 장소가 없습니다",
                        systemImage: "bag",
                        description: Text("장소를 저장하면 여기에 모입니다")
                    )
                } else {
                    List {
                        ForEach(Array(cart.items.enumerated()), id: \.element.placeId) {
                            index, item in
                            row(index: index, item: item)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(cart.items.isEmpty ? "장바구니" : "장바구니 \(cart.items.count)곳")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("닫기") { dismiss() }
                }
            }
        }
        .task { await cart.refresh() }
    }

    private func row(index: Int, item: CartItem) -> some View {
        HStack(spacing: 12) {
            Text("\(index + 1)")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Color.accentColor))

            RemoteImage(url: item.imageUrl, symbol: "mappin.and.ellipse")
                .frame(width: 44, height: 44)
                .clipShape(.rect(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(.subheadline.weight(.semibold))
                // 어느 작품 때문에 담았는지를 서버가 기억한다 (sourceContentId).
                // 같은 장소라도 담은 맥락이 다르면 사용자에게는 다른 의미다.
                if let title = item.sourceContentTitle {
                    Text(title).font(.caption).foregroundStyle(.secondary)
                } else if let address = item.address {
                    Text(address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            Button {
                Task { await cart.remove(placeId: item.placeId) }
            } label: {
                Image(systemName: "trash").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 4)
    }
}
