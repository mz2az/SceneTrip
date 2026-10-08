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
///
/// 끊김·5xx·분당 한도의 재시도는 여기가 아니라 **이 빌더가 내주는 세션**이 한다(`RetryingSession`, MZ2AZ-366).
/// 그쪽이 안쪽이다 — 거기서 끝내 실패한 것만 여기로 올라오고, 401 은 그쪽 규칙에 없어 곧장 올라온다.
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
    /// 보내기 전 — 토큰을 싣고 쓰기 요청이면 센다. 돌려주는 값은 「세었는가」 와 **실은 토큰**.
    ///
    /// 실은 토큰을 들고 있어야 401 을 받았을 때 「이 요청이 낡은 토큰으로 나갔던 것인가」 를 안다 — 재시도
    /// 계층이 쉬었다 다시 보내는 사이에 다른 요청이 토큰을 갱신했을 수 있다.
    static func prepare(_ builder: RequestBuilder<some Any>) -> (counted: Bool, token: String?) {
        var sent: String?
        if builder.requiresAuthentication, let token = AuthTokens.accessToken {
            _ = builder.addHeader(name: "Authorization", value: "Bearer \(token)")
            sent = token
        }
        let counts = builder.method != "GET" && AuthRules.intercepts(url: builder.URLString)
        if counts {
            PendingWrites.begin()
        }
        return (counts, sent)
    }

    static func finish<T>(
        _ result: Swift.Result<Response<T>, ErrorResponse>,
        builder: RequestBuilder<T>,
        sent: String?,
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
            // **이미 누가 갱신했다** — 이 요청은 낡은 토큰으로 나갔을 뿐이다. 또 갱신하면 리프레시 토큰을 한 번 더
            // 쓰고 갱신이 두 번 나간다(재시도가 쉬는 사이에 다른 요청이 갱신한 경우, 2026-10-08 실기). 새 토큰으로
            // 바로 다시 보낸다. 그것도 401 이면 아래의 보통 흐름(갱신 → 한 번)을 탄다 — `sent: nil` 이라 여기로
            // 다시 들어오지 않는다.
            if AuthRules.alreadyRefreshed(sent: sent, current: AuthTokens.accessToken),
               let token = AuthTokens.accessToken
            {
                _ = builder.addHeader(name: "Authorization", value: "Bearer \(token)")
                resend { second in
                    finish(second, builder: builder, sent: nil, resend: resend, completion: completion)
                }
                return
            }
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

/// 응답을 디스크에 남기지 않는 세션 (MZ2AZ-363) — 서명된 사진 주소가 실려 오는 창구만 이것으로 나간다
/// (`AuthRules.carriesSignedUrls`). 나머지는 생성 클라이언트의 기본 세션 그대로다.
///
/// 캐시를 끈다고 느려지지 않는다: 서버가 `Cache-Control`·`ETag`·`Last-Modified` 를 주지 않아, 기본 세션도 이 응답들을
/// 저장만 하고 다시 쓰지는 못했다(2026-10-08 응답 머리말 확인).
private enum UncachedSession {
    static let shared: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()
}

/// 진짜 세션을 재시도 세션으로 감싼 것 둘 (MZ2AZ-366). **캐시 없는 세션도 같은 규칙을 탄다.**
///
/// 생성 코드의 기본 세션은 모듈 안에 숨어 있어 `super.createURLSession()` 으로만 얻는다 — 처음 받은 것을
/// 감싸 두고 다시 쓴다.
private enum RetrySessions {
    static let uncached = RetryingSession(inner: UncachedSession.shared)
    private static let lock = NSLock()
    private static var wrapped: RetryingSession?

    static func standard(_ inner: @autoclosure () -> URLSessionProtocol) -> RetryingSession {
        lock.lock()
        defer { lock.unlock() }
        if let wrapped {
            return wrapped
        }
        let made = RetryingSession(inner: inner())
        wrapped = made
        return made
    }
}

private final class AuthPlainBuilder<T>: URLSessionRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        AuthRules.carriesSignedUrls(url: URLString)
            ? RetrySessions.uncached : RetrySessions.standard(super.createURLSession())
    }

    @discardableResult
    override func execute(
        _ queue: DispatchQueue = SceneApiClientAPI.apiResponseQueue,
        _ completion: @escaping (Swift.Result<Response<T>, ErrorResponse>) -> Void
    ) -> RequestTask {
        let (counted, token) = AuthIntercept.prepare(self)
        return super.execute(queue) { result in
            if counted {
                PendingWrites.end()
            }
            AuthIntercept.finish(
                result, builder: self, sent: token,
                resend: { again in _ = super.execute(queue, again) },
                completion: completion
            )
        }
    }
}

private final class AuthDecodableBuilder<T: Decodable>: URLSessionDecodableRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        AuthRules.carriesSignedUrls(url: URLString)
            ? RetrySessions.uncached : RetrySessions.standard(super.createURLSession())
    }

    @discardableResult
    override func execute(
        _ queue: DispatchQueue = SceneApiClientAPI.apiResponseQueue,
        _ completion: @escaping (Swift.Result<Response<T>, ErrorResponse>) -> Void
    ) -> RequestTask {
        let (counted, token) = AuthIntercept.prepare(self)
        return super.execute(queue) { result in
            if counted {
                PendingWrites.end()
            }
            AuthIntercept.finish(
                result, builder: self, sent: token,
                resend: { again in _ = super.execute(queue, again) },
                completion: completion
            )
        }
    }
}
