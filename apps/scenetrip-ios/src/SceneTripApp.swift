import NMapsMap
import SceneApiClient
import SwiftUI

@main
struct SceneTripApp: App {
    init() {
        // 지도를 그리기 전에 인증 정보를 넣어야 한다.
        //
        // **`clientId` 가 아니라 `ncpKeyId` 다.** 키가 10자 레거시 형식이라 처음에
        // deprecated 인 `clientId` 에 넣었더니 "Authorize failed" 로 타일이 한 장도
        // 오지 않았다(실측). flutter_naver_map 1.4.4 도 같은 키를 ncpKeyId 에 넣는다
        // (SdkInitializer.swift:41).
        NMFAuthManager.shared().ncpKeyId = Secrets.naverClientId

        // 서버 주소. 생성 클라이언트의 기본값은 8080 인데 로컬 클러스터에서 그 포트는
        // SigNoz UI 가 쓰고 **앱 API 는 8081** 이다 (`just cluster-up` 안내 참고).
        //
        // 지금은 로컬 클러스터를 전제한다. 배포 환경이 생기면 빌드 시점 주입으로
        // 바꾼다 — 네이버 키와 같은 방식이면 된다.
        SceneApiClientAPI.basePath = "http://localhost:8081/v1"
    }

    /// `Scene` 을 한정한다 — 명세에 같은 이름의 모델(장면)이 있어 생성 클라이언트의
    /// SceneApiClient.Scene 과 SwiftUI.Scene 이 부딪힌다.
    var body: some SwiftUI.Scene {
        WindowGroup {
            RootTabs()
        }
    }
}
