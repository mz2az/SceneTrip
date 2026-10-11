import Foundation
import SceneApiClient

@MainActor
protocol CartClient {
    func fetch(installId: UUID) async throws -> Cart
    func remove(installId: UUID, placeId: Int64) async throws
}

private struct ServerCartClient: CartClient {
    func fetch(installId: UUID) async throws -> Cart {
        try await CartAPI.getCart(xInstallId: installId)
    }

    func remove(installId: UUID, placeId: Int64) async throws {
        try await CartAPI.removeCartItem(xInstallId: installId, placeId: placeId)
    }
}

/// 장바구니. 서버가 정본이고 이 타입은 화면이 읽을 사본을 들고 있다.
///
/// 계약이 담는 경로를 셋으로 적어 뒀다 — **장소 카드의 `+`, 상세의 저장 버튼, 장면
/// 팝업의 북마크. 셋 다 `POST /cart/items` 하나를 부른다.** 그래서 이 타입도 하나다.
///
/// 같은 장소를 두 번 담으면 서버가 `409` 를 준다. 계약이 그때 "이미 저장된 장소입니다"
/// 를 띄우라고 적어 뒀으므로 오류가 아니라 **정상 경로**로 다룬다 — 베타도 같았다
/// (`Cart.add` 가 false 를 돌려주고 화면이 스낵바를 띄웠다).
@MainActor
final class CartStore: ObservableObject {
    @Published private(set) var placeIds: Set<Int64> = []
    @Published private(set) var items: [CartItem] = []
    @Published private(set) var toast: String?

    private let installId: UUID
    private let client: any CartClient

    init() {
        installId = InstallIdentity.current
        client = ServerCartClient()
    }

    init(installId: UUID, client: any CartClient) {
        self.installId = installId
        self.client = client
    }

    func contains(_ placeId: Int64) -> Bool {
        placeIds.contains(placeId)
    }

    func refresh() async {
        guard let cart = try? await client.fetch(installId: installId) else { return }
        items = cart.items
        placeIds = Set(cart.items.map(\.placeId))
    }

    /// 담기. 이미 있으면 서버가 409 를 주고, 그것도 담긴 상태이므로 목록에 넣는다.
    func add(placeId: Int64, sourceContentId: Int64? = nil) async {
        do {
            _ = try await CartAPI.addCartItem(
                xInstallId: installId,
                cartItemCreate: CartItemCreate(
                    placeId: placeId, sourceContentId: sourceContentId
                )
            )
            placeIds.insert(placeId)
            toast = tr("장바구니에 담았습니다")
            AppAnalytics.log(.savePlace(placeId: placeId))
        } catch let ErrorResponse.error(code, _, _, _) where code == 409 {
            placeIds.insert(placeId)
            toast = tr("이미 저장된 장소입니다")
        } catch {
            toast = tr("담지 못했습니다. 잠시 후 다시 시도해 주세요")
        }
        await refresh()
    }

    func remove(placeId: Int64) async {
        do {
            try await client.remove(installId: installId, placeId: placeId)
        } catch {
            toast = tr("빼지 못했습니다. 잠시 후 다시 시도해 주세요")
            return
        }
        items.removeAll { $0.placeId == placeId }
        placeIds.remove(placeId)
        // 담을 때 알려 줬으니 뺄 때도 알려 준다. 목록 행에서 빼면 아이콘만 바뀌어
        // 눌렸는지 확신이 안 선다.
        toast = tr("장바구니에서 뺐습니다")
        await refresh()
    }

    func clearToast() {
        toast = nil
    }
}
