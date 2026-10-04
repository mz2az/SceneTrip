import Foundation
import SceneApiClient

/// 생성 클라이언트의 요청에 토큰을 싣고 401 을 처리한다 (MZ2AZ-336).
///
/// **생성기는 이것을 만들어 주지 않는다**(`social-login.md` §7). 화면 코드는 지금처럼
/// `CartAPI.getCart(...)` 를 부르기만 하면 된다 — 만료·갱신·재시도는 여기서 끝난다.
///
/// 1. 토큰을 받는 창구(계약의 `security`)에 `Authorization: Bearer` 를 싣는다.
/// 2. `ACCESS_TOKEN_EXPIRED` → 갱신(동시에 하나) → 원래 요청을 **한 번** 다시 보낸다.
/// 3. 세션이 깨졌으면 토큰을 지우고 로그인 화면, 비회원이 가입 전용 기능을 불렀으면 로그인 화면.
final class AuthRequestBuilderFactory: RequestBuilderFactory {
    func getNonDecodableBuilder<T>() -> RequestBuilder<T>.Type {
        AuthPlainBuilder<T>.self
    }

    func getBuilder<T: Decodable>() -> RequestBuilder<T>.Type {
        AuthDecodableBuilder<T>.self
    }
}

private typealias Completion<T> = (Swift.Result<Response<T>, ErrorResponse>) -> Void

private enum AuthIntercept {
    /// 보내기 전 — 토큰을 싣고 쓰기 요청이면 센다. 돌려주는 값은 「세었는가」.
    static func prepare(_ builder: RequestBuilder<some Any>) -> Bool {
        if builder.requiresAuthentication, let token = AuthTokens.accessToken {
            _ = builder.addHeader(name: "Authorization", value: "Bearer \(token)")
        }
        let counts = builder.method != "GET" && AuthRules.intercepts(url: builder.URLString)
        if counts {
            PendingWrites.begin()
        }
        return counts
    }

    static func finish<T>(
        _ result: Swift.Result<Response<T>, ErrorResponse>,
        builder: RequestBuilder<T>,
        resend: @escaping (@escaping Completion<T>) -> Void,
        completion: @escaping Completion<T>
    ) {
        guard case let .failure(.error(status, data, _, _)) = result,
              AuthRules.intercepts(url: builder.URLString)
        else {
            completion(result)
            return
        }
        switch AuthRules.action(status: status, code: AuthRules.apiCode(from: data)) {
        case .refreshAndRetry:
            Task {
                guard await AuthRefresher.refresh(), let token = AuthTokens.accessToken else {
                    completion(result)
                    return
                }
                _ = builder.addHeader(name: "Authorization", value: "Bearer \(token)")
                resend { second in
                    // 재시도는 한 번. 갱신 뒤에도 401 이면 로그인 화면이다.
                    if case let .failure(.error(again, _, _, _)) = second, again == 401 {
                        Task { await AuthStore.shared.sessionLost() }
                    }
                    completion(second)
                }
            }
        case .signOut:
            Task { await AuthStore.shared.sessionLost() }
            completion(result)
        case .promptSignIn:
            Task { await AuthStore.shared.promptSignIn() }
            completion(result)
        case .none:
            completion(result)
        }
    }
}

private final class AuthPlainBuilder<T>: URLSessionRequestBuilder<T> {
    @discardableResult
    override func execute(
        _ queue: DispatchQueue = SceneApiClientAPI.apiResponseQueue,
        _ completion: @escaping (Swift.Result<Response<T>, ErrorResponse>) -> Void
    ) -> RequestTask {
        let counted = AuthIntercept.prepare(self)
        return super.execute(queue) { result in
            if counted {
                PendingWrites.end()
            }
            AuthIntercept.finish(
                result, builder: self,
                resend: { again in _ = super.execute(queue, again) },
                completion: completion
            )
        }
    }
}

private final class AuthDecodableBuilder<T: Decodable>: URLSessionDecodableRequestBuilder<T> {
    @discardableResult
    override func execute(
        _ queue: DispatchQueue = SceneApiClientAPI.apiResponseQueue,
        _ completion: @escaping (Swift.Result<Response<T>, ErrorResponse>) -> Void
    ) -> RequestTask {
        let counted = AuthIntercept.prepare(self)
        return super.execute(queue) { result in
            if counted {
                PendingWrites.end()
            }
            AuthIntercept.finish(
                result, builder: self,
                resend: { again in _ = super.execute(queue, again) },
                completion: completion
            )
        }
    }
}
