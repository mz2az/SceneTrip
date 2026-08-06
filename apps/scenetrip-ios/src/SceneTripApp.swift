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

/// 작품검색 탭. 지도는 항상 보이고 그 위에 바텀시트가 얹힌다 (계획서 §3-1).
/// 지금은 지도만 — 시트·검색·칩은 이 위에 쌓는다.
struct SearchTabView: View {
    var body: some View {
        NaverMapView()
            .ignoresSafeArea()
    }
}

/// 네이버 지도를 SwiftUI 에 얹는 최소 래퍼. 카메라·핀은 다음 단계에서 붙인다.
struct NaverMapView: UIViewRepresentable {
    func makeUIView(context _: Context) -> NMFNaverMapView {
        let view = NMFNaverMapView()
        view.showZoomControls = false
        // 서울 시청 근처. 첫 진입 카메라 규칙은 MZ2AZ-162 에서 다시 잡는다.
        view.mapView.moveCamera(
            NMFCameraUpdate(scrollTo: NMGLatLng(lat: 37.5666, lng: 126.9784))
        )
        return view
    }

    func updateUIView(_: NMFNaverMapView, context _: Context) {}
}
