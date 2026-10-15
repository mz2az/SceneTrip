import NMapsMap

/// 지도 범위가 GPS에서 유래했는지 보존한다. 인셋 변경은 출처를 바꾸지 않는다.
final class MapRegionSource {
    var source: LocationCoordinateSource = .userSelected

    func cameraWillChange(reason: Int) {
        if reason == NMFMapChangedByGesture || reason == NMFMapChangedByControl {
            source = .userSelected
        } else if reason == NMFMapChangedByLocation {
            source = .device
        }
    }
}
