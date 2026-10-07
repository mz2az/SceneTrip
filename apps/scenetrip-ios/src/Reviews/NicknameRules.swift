import Foundation

/// 닉네임 규칙 (MZ2AZ-363). 계약 `PUT /me/nickname`: 앞뒤 공백을 뗀 뒤 **2~16자, 한글·영문·숫자·`_`**.
///
/// 서버가 정본이다 — 여기는 보내기 전에 걸러 헛걸음을 줄일 뿐이고, 서버가 `NICKNAME_INVALID` 를
/// 주면 그 판정을 따른다. 그래서 헷갈리는 글자(낱자 「ㅋㅋ」)는 여기서 막지 않고 서버에 맡긴다.
/// 겹침(`NICKNAME_TAKEN`)은 서버만 안다.
enum NicknameRules {
    static let minLength = 2
    static let maxLength = 16

    enum Problem: Equatable {
        case tooShort
        case tooLong
        case badCharacters
    }

    /// 서버에 보낼 모양 — 앞뒤 공백을 뗀다.
    ///
    /// 한글은 **합친 꼴(NFC)** 로 보낸다 — 맥에서 붙여 넣은 풀어쓴 한글(「ㅈ+ㅔ」)은 같은 글자인데 낱자로 세어진다.
    static func normalized(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
    }

    /// 규칙에 맞으면 nil. 길이보다 글자를 먼저 본다 — 「!」 한 글자는 「짧다」 보다 「못 쓰는 글자」 가 맞는 안내다.
    static func problem(in raw: String) -> Problem? {
        let name = normalized(raw)
        if !name.unicodeScalars.allSatisfy(allowed) {
            return .badCharacters
        }
        if name.count < minLength {
            return .tooShort
        }
        if name.count > maxLength {
            return .tooLong
        }
        return nil
    }

    /// 한글(완성형·낱자)·영문·숫자·밑줄.
    private static func allowed(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x30 ... 0x39, 0x41 ... 0x5A, 0x61 ... 0x7A, 0x5F: true // 0-9 A-Z a-z _
        case 0xAC00 ... 0xD7A3: true // 가-힣
        case 0x3164: false // 한글 채움 문자 — 눈에 안 보인다. 빈칸 닉네임이 된다
        case 0x3131 ... 0x318E: true // ㄱ-ㅎ ㅏ-ㅣ
        default: false
        }
    }

    /// 입력란 아래에 적을 안내. 문제없으면 규칙 한 줄.
    static func hint(for problem: Problem?) -> String {
        switch problem {
        case .tooShort: tr("2자 이상으로 정해 주세요")
        case .tooLong: tr("16자 이하로 정해 주세요")
        case .badCharacters: tr("한글·영문·숫자·밑줄(_)만 쓸 수 있어요")
        case nil: tr("2~16자, 한글·영문·숫자·밑줄(_)")
        }
    }
}

/// 닉네임을 **한 번만 묻는다** — 건너뛴 사람에게 로그인할 때마다 다시 묻지 않는다(티켓).
/// 계정마다 따로 적는다. 기기에만 있고, 그 계정으로 물어봤다는 사실뿐이다.
enum NicknameAsked {
    private static func key(_ accountId: String) -> String {
        "scenetrip.nickname.asked.\(accountId)"
    }

    static func has(_ accountId: String, in defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: key(accountId))
    }

    static func mark(_ accountId: String, in defaults: UserDefaults = .standard) {
        defaults.set(true, forKey: key(accountId))
    }

    /// 로그인 응답을 보고 물어볼지 정한다. `confirmed` 가 **false 일 때만** — 옛 서버는 칸이 없어 nil 이다.
    static func shouldAsk(confirmed: Bool?, accountId: String, in defaults: UserDefaults = .standard) -> Bool {
        confirmed == false && !has(accountId, in: defaults)
    }
}
