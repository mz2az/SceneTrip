import Foundation
import SceneApiClient

/// 가입 계정에 묶인 채 남은 설치 식별자를 버리고 새 비회원이 된다 (MZ2AZ-521). 웹(MZ2AZ-520
/// `client.ts` 의 `renewInstall`)과 같은 규칙이다.
///
/// ## 왜
///
/// 로그인하면 서버가 이 설치본의 `X-Install-Id` 를 가입 계정에 묶는다. 정식 로그아웃은 서버가 묶음을 풀어
/// 새 비회원에 짝지어 준다(계약 `/auth/sign-out`). 그런데 로그인이 **저절로** 풀리면(리프레시 거절 등) 앱은
/// 토큰만 지우고, 그 뒤 토큰 없이 나가는 요청은 모두 `401 SESSION_REQUIRED` 를 받는다 — 홈의 「내 여행」,
/// 장바구니, 찜이 앱을 지웠다 깔기 전까지 영영 실패한다. 설치 식별자를 새로 만들면 서버가 처음 보는 값이라
/// 새 비회원 계정을 짝지어 준다 — 정식 로그아웃과 같은 「새 비회원」이다.
///
/// ## 규칙
///
/// - **토큰 없이 보낸** 요청이 `401 SESSION_REQUIRED` 를 받았고 **지금도 세션이 없을 때만** 바꾼다.
///   토큰을 실어 받은 거절은 여기 오지 않는다 — 기존 로그아웃 처리(`AuthRules` 의 `signOut`)가 맡는다.
/// - 그 요청이 보낸 번호가 **지금 번호일 때만** 바꾼다(`InstallIdHolder.renew(from:)`). 같은 낡은 번호로
///   거절받은 요청 여럿이 와도 한 번만 바뀌고, 이미 바뀐 뒤에 도착한 옛 번호의 거절은 다시 바꾸지 않는다.
/// - **한 실행에 한 번만** 바꾼다. 새 번호마저 거절되면 바꾸지도 다시 보내지도 않고 기존 표에 넘긴다 —
///   바꾸기 → 화면 다시 읽기 → 거절 → 바꾸기 고리를 막는다.
/// - 세션이 있으면(그 사이 로그인, 또는 리프레시 토큰만 남은 상태) 바꾸지 않고 기존 표에 넘긴다.
/// - 조회(GET·HEAD)는 지금 번호로 **한 번만** 다시 보내 그 응답을 돌려준다. 그것도 거절이면 그대로 돌려준다.
/// - 쓰기는 다시 보내지 않는다 — 한 번 거절된 쓰기를 다른 계정에 떨구지 않는다. 사람이 다시 하면 새 번호로 나간다.
/// - 실제로 바꿨으면 `AuthStore.installRenewed()` 로 계정이 바뀐 것처럼 화면이 다시 읽게 한다.
enum InstallRenewal {
    static let header = "X-Install-Id"

    /// 설치 식별자 자리와 「바꿨다」 알림. 시험이 바꿔 끼운다 — 생성 클라이언트 경로(`AuthIntercept`)를 그대로 타는
    /// 시험이 앱의 진짜 설치 식별자를 바꾸거나 화면 다시 읽기(서버 호출)를 일으키지 않게.
    struct Environment {
        var holder: InstallIdHolder = InstallIdentity.shared
        var renewed: @Sendable () -> Void = { Task { @MainActor in AuthStore.shared.installRenewed() } }
    }

    static var environment = Environment()

    /// 이 결과를 이 규칙이 맡았으면 `true` — 그때는 `completion` 을 여기서(또는 다시 보낸 뒤) 부른다.
    /// `false` 면 아무것도 하지 않았다. 부르는 쪽이 원래 흐름을 이어 간다.
    ///
    /// 부르는 쪽(`AuthIntercept.finish`)은 그 전에 「요청을 보낸 뒤 세션이 바뀌지 않았다」 를 이미 확인했다 —
    /// 그 사이 로그인했으면 여기 오지 않는다. `hasSession` 은 그 확인 뒤의 지금 상태를 한 번 더 본다.
    static func handle<T>(
        _ result: Swift.Result<Response<T>, ErrorResponse>,
        builder: URLSessionRequestBuilder<T>,
        holder: InstallIdHolder = InstallRenewal.environment.holder,
        hasSession: () -> Bool = {
            let tokens = AuthTokens.snapshot
            return tokens.accessToken != nil || tokens.refreshToken != nil
        },
        renewed: @escaping @Sendable () -> Void = InstallRenewal.environment.renewed,
        resend: @escaping (@escaping (Swift.Result<Response<T>, ErrorResponse>) -> Void) -> Void,
        completion: @escaping (Swift.Result<Response<T>, ErrorResponse>) -> Void
    ) -> Bool {
        guard case let .failure(.error(status, data, _, _)) = result, status == 401,
              AuthRules.apiCode(from: data) == "SESSION_REQUIRED"
        else { return false }
        let headers = builder.buildHeaders()
        guard !headers.keys.contains(where: { $0.caseInsensitiveCompare("Authorization") == .orderedSame })
        else { return false }
        // 세션이 있으면 번호를 버리지 않고 기존 표(signOut)에 넘긴다 — 방금 계정에 묶인 번호를 버리면 안 되고, 액세스
        // 토큰 없이 리프레시 토큰만 남은 드문 상태라면 기존 처리가 세션을 정리해 로그인 화면으로 이끈다. 그 사이 새로
        // 로그인했다면 기존 처리의 `clear(ifCurrent:)` 가 실패해 아무것도 지우지 않는다. 번호를 싣지 않은 요청도 같다.
        guard let sent = sentInstallId(headers), !hasSession() else { return false }
        let renewal = holder.renew(from: sent)
        switch renewal.outcome {
        case .renewed:
            renewed()
        case .superseded:
            break
        case .exhausted:
            // 이번 실행에서 이미 바꾼 번호마저 거절됐다 — 또 바꾸면 고리가 된다. 바꾸지도 다시 보내지도 않는다.
            return false
        }
        guard ["GET", "HEAD"].contains(builder.method.uppercased()) else {
            completion(result)
            return true
        }
        _ = builder.addHeader(name: header, value: renewal.current.uuidString)
        // 한 번만 — 다시 보낸 결과는 이 규칙을 다시 타지 않고 그대로 돌려준다.
        resend { completion($0) }
        return true
    }

    private static func sentInstallId(_ headers: [String: String]) -> UUID? {
        headers.first { $0.key.caseInsensitiveCompare(header) == .orderedSame }
            .flatMap { UUID(uuidString: $0.value) }
    }
}
