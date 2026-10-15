import Foundation
import SceneApiClient

/// 관광공사 안내의 표시 규칙 (MZ2AZ-470). 내용·언어 폴백은 서버가 결정한다.
enum PoiInfoRules {
    struct Row: Identifiable {
        let id: String
        let label: String
        let text: String
        let languageTag: String?
    }

    static func rows(_ info: PoiInfo?) -> [Row] {
        guard let info else { return [] }
        let fields: [(String, PoiInfoText?)] = [
            ("개요", info.overview), ("이용 시간", info.useTime), ("쉬는 날", info.restDate),
            ("주차", info.parking), ("대표 메뉴", info.firstMenu), ("입장료", info.useFee),
        ]
        return fields.compactMap { label, value in
            guard let value, !value.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return Row(id: label, label: tr(label), text: value.text, languageTag: languageTag(value.lang))
        }
    }

    static func homepage(_ info: PoiInfo?) -> URL? {
        WebAddress.url(info?.homepage)
    }

    static func isVisible(_ info: PoiInfo?) -> Bool {
        !rows(info).isEmpty || homepage(info) != nil
    }

    private static func languageTag(_ lang: Lang) -> String? {
        guard lang != AppLanguage.current else { return nil }
        switch lang {
        case .ko: return tr("한국어")
        case .en: return tr("영어")
        case .ja: return tr("일본어")
        case .zhHant: return tr("중국어 번체")
        }
    }
}
