// swift-tools-version: 5.10
//
// 서드파티 Swift 의존성 선언. **이 파일로 빌드하지 않는다** — 빌드는 Bazel 이 한다
// (CLAUDE.md §0 제1법칙). rules_swift_package_manager 가 이 매니페스트와
// Package.resolved 를 읽어 Bazel 저장소로 바꿔 주고, iOS 타깃은 거기서 나온
// 라벨을 deps 에 적는다.
//
// 의존성을 더하거나 버전을 올릴 때: 여기를 고치고 `just swift-deps-update` 를
// 돌린 뒤 Package.resolved diff 를 함께 커밋한다. MODULE.bazel 의 maven 절과
// 같은 규약이다.
import PackageDescription

let package = Package(
    name: "scenetrip",
    platforms: [.iOS(.v17)],
    dependencies: [
        // 네이버 지도 iOS SDK. 프로토타입(flutter_naver_map)이 쓰던 것과 같은
        // 엔진이며, 네이버가 SPM 으로 공식 배포한다(binaryTarget 래퍼).
        .package(url: "https://github.com/navermaps/SPM-NMapsMap.git", from: "3.23.3"),
    ]
)
