import Foundation
import SceneApiClient

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

    private let deviceId: UUID

    init() {
        deviceId = Self.loadOrCreateDeviceId()
    }

    func contains(_ placeId: Int64) -> Bool {
        placeIds.contains(placeId)
    }

    func refresh() async {
        guard let cart = try? await CartAPI.getCart(xDeviceId: deviceId) else { return }
        items = cart.items
        placeIds = Set(cart.items.map(\.placeId))
    }

    /// 담기. 이미 있으면 서버가 409 를 주고, 그것도 담긴 상태이므로 목록에 넣는다.
    func add(placeId: Int64, sourceContentId: Int64? = nil) async {
        do {
            _ = try await CartAPI.addCartItem(
                xDeviceId: deviceId,
                cartItemCreate: CartItemCreate(
                    placeId: placeId, sourceContentId: sourceContentId
                )
            )
            placeIds.insert(placeId)
            toast = "장바구니에 담았습니다"
        } catch let ErrorResponse.error(code, _, _, _) where code == 409 {
            placeIds.insert(placeId)
            toast = "이미 저장된 장소입니다"
        } catch {
            toast = "담지 못했습니다. 잠시 후 다시 시도해 주세요"
        }
        await refresh()
    }

    func remove(placeId: Int64) async {
        try? await CartAPI.removeCartItem(xDeviceId: deviceId, placeId: placeId)
        placeIds.remove(placeId)
        await refresh()
    }

    func clearToast() {
        toast = nil
    }

    /// 기기 UUID. 계약: "앱이 최초 실행 시 생성해 보관하는 기기 UUID. 장바구니의
    /// 주체다. 로그인이 도입되면 이 값을 사용자 계정에 이어 붙인다."
    ///
    /// 매번 새로 만들면 앱을 껐다 켤 때마다 장바구니가 빈다.
    private static func loadOrCreateDeviceId() -> UUID {
        let key = "scenetrip.deviceId"
        if let saved = UserDefaults.standard.string(forKey: key),
           let parsed = UUID(uuidString: saved)
        {
            return parsed
        }
        let fresh = UUID()
        UserDefaults.standard.set(fresh.uuidString, forKey: key)
        return fresh
    }
}
