import Foundation

/// 이 설치본을 가리키는 값 (MZ2AZ-261).
///
/// 서버가 「누구의 장바구니·코스인가」를 이 값으로 가른다. 계약의 `X-Install-Id`
/// 헤더에 실려 나간다.
///
/// ## 이름이 기기가 아니라 설치본인 이유
///
/// 이 값은 기기를 가리키지 않는다 — **앱을 지웠다 깔면 새 값이 되고, 같은 폰에 두 번
/// 깔면 서로 다른 사람으로 보인다.** `deviceId` 라고 부르면 「폰을 바꿨는데 왜 데이터가
/// 없죠」를 버그로 오해하게 된다. 헤더도 같은 이유로 `X-Device-Id` 에서 `X-Install-Id` 로
/// 바꿨다(MZ2AZ-328).
///
/// ## 왜 장바구니에서 꺼냈나
///
/// 이 값이 `CartStore` 안에 있었다. 코스·마켓·찜 API 가 전부 같은 값을 필요로 하는데
/// 그 자리에 두면 **코스 기능이 장바구니 코드에 의존하게 된다.** 둘 사이에는 아무
/// 관계가 없는데 코드에는 관계가 생긴다.
///
/// ## 저장 열쇠는 바꾸지 않는다
///
/// `scenetrip.deviceId` 라는 열쇠 이름이 실제를 오도하지만 **지금 바꾸지 않는다.**
/// 바꾸면 이미 깔린 앱의 값이 고아가 되어 그 사람의 장바구니가 끊긴다. 열쇠 정리는
/// 키체인으로 옮길 때 폴백 읽기와 함께 한 번에 한다.
enum InstallIdentity {
    private static let key = "scenetrip.deviceId"

    /// 최초 실행에 만들어 보관한 값. 이후로는 계속 같은 것을 돌려준다.
    ///
    /// **매번 새로 만들면 앱을 껐다 켤 때마다 장바구니와 코스가 빈다.**
    static let current: UUID = {
        if let saved = UserDefaults.standard.string(forKey: key),
           let parsed = UUID(uuidString: saved)
        {
            return parsed
        }
        let fresh = UUID()
        UserDefaults.standard.set(fresh.uuidString, forKey: key)
        return fresh
    }()
}
