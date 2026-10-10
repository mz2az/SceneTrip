import Foundation

extension RouteStop {
    struct PoiText: Hashable {
        let displayName: String?
        let nameRoman: String?
        let categoryLabel: String?
        let displayAddress: String?
    }

    var label: PoiLabel {
        label(korean: AppLanguage.current == .ko)
    }

    /// 코스 제목과 번호 핀은 같은 제목을 쓴다. 읽는 법은 별도 줄에만 둔다(MZ2AZ-389).
    func label(korean: Bool) -> PoiLabel {
        guard poiId != nil else {
            return PoiLabel(title: place.name, reading: nil, roman: nil,
                            category: PlaceType.stopLabel(place.type, kind: kind), address: place.address)
        }
        return PoiLabel.make(
            name: place.name, displayName: poiText?.displayName, nameRoman: poiText?.nameRoman,
            category: place.type, categoryLabel: poiText?.categoryLabel,
            address: place.address, displayAddress: poiText?.displayAddress, korean: korean
        )
    }
}
