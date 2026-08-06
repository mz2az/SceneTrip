import NMapsMap
import SwiftUI

@main
struct SceneTripApp: App {
    init() {
        // 지도를 그리기 전에 인증 정보를 넣어야 한다.
        //
        // **`clientId` 가 아니라 `ncpKeyId` 다.** 키가 10자 레거시 형식이라 처음에
        // deprecated 인 `clientId` 에 넣었더니 "Authorize failed: 잘못된 클라이언트
        // ID를 지정" 으로 타일이 한 장도 안 왔다(실측). 프로토타입이 쓰는
        // flutter_naver_map 1.4.4 도 같은 키를 ncpKeyId 에 넣는다
        // (SdkInitializer.swift:41 `sdk.ncpKeyId = clientId`) — 이름만 clientId 일 뿐
        // 실제 속성은 이쪽이다. SDK 버전은 프로토타입(3.23.0)과 사실상 같다.
        NMFAuthManager.shared().ncpKeyId = Secrets.naverClientId
    }

    var body: some Scene {
        WindowGroup {
            SearchTabView()
        }
    }
}
