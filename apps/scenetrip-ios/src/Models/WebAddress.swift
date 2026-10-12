import Foundation

/// 서버가 준 외부 페이지·사진 주소의 공통 HTTP(S) 경계.
enum WebAddress {
    static func url(_ raw: String?) -> URL? {
        guard let raw else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              value.range(of: "%(?![0-9A-Fa-f]{2})", options: .regularExpression) == nil,
              let parts = URLComponents(string: value),
              let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil else { return nil }
        return parts.url
    }
}
