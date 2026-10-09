import Foundation
import SceneApiClient
import SwiftUI

/// 로그인 상태 (MZ2AZ-336). 앱에 하나뿐이다.
///
/// 계정은 **있어도 되고 없어도 된다** — 검색·장바구니·찜·코스는 비회원으로 다 된다
/// (`docs/api/auth.md`). 로그인은 그 데이터를 계정에 붙여 다른 기기에서도 보게 하고,
/// 길찾기·챗봇·마켓 쓰기를 연다.
@MainActor
final class AuthStore: ObservableObject {
    static let shared = AuthStore()

    /// 로그인한 계정. 토큰은 있는데 아직 `/me` 를 못 읽었으면 `nil` 일 수 있다.
    @Published private(set) var me: Me?
    @Published private(set) var signedIn = false
    /// 로그인 화면을 띄울 것인가. 화면 어디서든 이것을 켜면 맨 위 화면이 시트를 올린다.
    @Published var showingSignIn = false
    /// 계정이 바뀔 때마다 오른다(로그인·로그아웃·탈퇴·세션 소실). 서버 데이터를 든 화면이
    /// 이것을 보고 다시 읽는다 — 설치본이 가리키는 계정이 달라졌기 때문이다.
    @Published private(set) var epoch = 0
    @Published private(set) var busy = false
    /// 로그인 화면에 보일 한 줄.
    @Published var message: String?
    /// 닉네임 정하기 화면을 띄울 것인가 (MZ2AZ-363). 로그인 직후, 서버가 붙인 자동 닉네임을 아직
    /// 확정하지 않았을 때 한 번 켠다. 맨 위 화면이 `nicknameSheet` 로 올린다.
    @Published var askingNickname = false

    private let installId = InstallIdentity.current

    /// 앱이 뜰 때 한 번. 생성 클라이언트에 토큰·401 처리를 끼우고 저장된 세션을 되살린다.
    func start() {
        SceneApiClientAPI.requestBuilderFactory = AuthRequestBuilderFactory()
        // 키체인은 앱을 지워도 남는다 — 지웠다 깐 앱이 옛 로그인을 이어받으면 안 된다.
        if InstallMarker.freshInstall {
            AuthTokens.clear()
        }
        signedIn = AuthTokens.hasSession
        if signedIn {
            Task { await loadMe() }
        }
        // 닉네임 화면을 서버 없이 띄워 보는 뒷문 — `-previewNickname`(`-initialTab` 과 같은 종류).
        // 로그인 응답이 있어야 뜨는 화면이라, 서버(MZ2AZ-362)가 생기기 전에는 이 길로만 볼 수 있다.
        if ProcessInfo.processInfo.arguments.contains("-previewNickname") {
            Task {
                try? await Task.sleep(for: .seconds(2))
                askingNickname = true
            }
        }
    }

    func promptSignIn() {
        guard !signedIn else { return }
        message = nil
        showingSignIn = true
    }

    func signInWithGoogle() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        message = nil
        do {
            let google = try await GoogleOAuth.signIn()
            // 나가 있는 쓰기가 끝난 뒤에 부른다 — 겹치면 합치기 전 계정에 떨어진다(계약).
            await PendingWrites.settle()
            let session = try await AuthAPI.signInWithGoogle(
                xInstallId: installId,
                googleSignIn: GoogleSignIn(idToken: google.idToken, nonce: google.nonce)
            )
            AuthTokens.store(session)
            me = session.user
            signedIn = true
            AppAnalytics.setMember(true)
            AppAnalytics.log(session.isNewUser ? .signUp(method: "google") : .login(method: "google"))
            showingSignIn = false
            askNicknameIfNeeded(session.user)
            // `merged` 가 아니어도 다시 읽는다 — 값이 싸고, 화면이 든 것이 서버와 같다는 보장이 된다.
            accountChanged()
        } catch GoogleOAuth.Failure.cancelled {
            // 사람이 창을 닫았다. 알릴 것이 없다.
        } catch let ErrorResponse.error(status, data, _, _) where status > 0 {
            message = AuthRules.apiCode(from: data) == "AUTH_PROVIDER_UNAVAILABLE"
                ? tr("구글에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요")
                : tr("로그인하지 못했어요. 다시 해 주세요")
        } catch {
            message = tr("연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요")
        }
    }

    /// 로그아웃. 서버 응답과 상관없이 토큰을 지운다 — 이 설치본은 서버에서 **새 비회원**이 된다.
    func signOut() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        AppAnalytics.log(.logout)
        if let token = AuthTokens.refreshToken {
            try? await AuthAPI.signOut(
                xInstallId: installId, refreshTokenBody: RefreshTokenBody(refreshToken: token)
            )
        }
        forget()
    }

    /// 탈퇴. **되돌릴 수 없다** — 부르는 쪽이 확인 창을 띄운 뒤에 부른다. 지웠으면 `true`.
    func deleteAccount() async -> Bool {
        guard !busy else { return false }
        busy = true
        defer { busy = false }
        do {
            try await AuthAPI.deleteMe()
            AppAnalytics.log(.deleteAccount)
            forget()
            return true
        } catch {
            return false
        }
    }

    /// 세션이 깨졌다(토큰 위조·폐기·리프레시 만료). 토큰을 지우고 로그인 화면을 띄운다.
    func sessionLost() {
        guard signedIn else { return }
        // 닉네임 화면이 떠 있었으면 그것이 내려간 뒤에 로그인 화면을 올린다 — 내려가는 중에는 안 뜬다.
        let wasAskingNickname = askingNickname
        forget()
        message = tr("로그인이 풀렸어요. 다시 로그인해 주세요")
        guard wasAskingNickname else {
            showingSignIn = true
            return
        }
        Task {
            try? await Task.sleep(for: .milliseconds(600))
            if !signedIn {
                showingSignIn = true
            }
        }
    }

    // MARK: 닉네임 (MZ2AZ-363)

    enum NicknameOutcome: Equatable {
        case saved
        /// `400 NICKNAME_INVALID` — 길이·글자 규칙.
        case invalid
        /// `409 NICKNAME_TAKEN` — 다른 사람이 쓴다.
        case taken
        case failed
    }

    /// 닉네임을 정한다·바꾼다. 서버가 받으면 `me` 가 바뀐다.
    func setNickname(_ raw: String) async -> NicknameOutcome {
        do {
            let updated = try await AuthAPI.setMyNickname(
                nicknameInput: NicknameInput(nickname: NicknameRules.normalized(raw))
            )
            // 기다리는 사이 로그아웃됐으면 떠난 계정을 되살리지 않는다.
            guard signedIn else { return .failed }
            me = updated
            NicknameAsked.mark(updated.id.uuidString)
            AppAnalytics.log(.setNickname(skipped: false))
            return .saved
        } catch let ErrorResponse.error(_, data, _, _) {
            // 서버 문장이 아니라 code 로 가른다(MZ2AZ-345).
            switch AuthRules.apiCode(from: data) {
            case "NICKNAME_INVALID": return .invalid
            case "NICKNAME_TAKEN": return .taken
            default: return .failed
            }
        } catch {
            return .failed
        }
    }

    /// 「건너뛰기」 — 자동 닉네임 그대로 쓴다. 다음 로그인 때 다시 묻지 않는다.
    ///
    /// **아직 확정하지 않은 계정일 때만 적는다.** 확인용 뒷문(`-previewNickname`)으로 띄운 화면이나 이미
    /// 정한 계정에서 건너뛴 것을 「물어봤음」 으로 적으면, 정작 물어야 할 때 안 묻는다.
    func skipNickname() {
        guard let me, me.nicknameConfirmed == false else { return }
        NicknameAsked.mark(me.id.uuidString)
        AppAnalytics.log(.setNickname(skipped: true))
    }

    /// 로그인 화면이 내려간 뒤에 올린다 — 시트가 내려가는 중에 다른 시트를 올리면 안 뜬다.
    private func askNicknameIfNeeded(_ user: Me) {
        guard NicknameAsked.shouldAsk(confirmed: user.nicknameConfirmed, accountId: user.id.uuidString) else {
            return
        }
        Task {
            try? await Task.sleep(for: .milliseconds(600))
            guard signedIn, me?.id == user.id else { return }
            askingNickname = true
        }
    }

    private func forget() {
        // 발자취는 기기에만 있지만 그 사람의 것이다 — 계정이 떠나면 함께 지운다 (MZ2AZ-348).
        FootprintStore.shared.forgetOwner()
        AuthTokens.clear()
        GoogleOAuth.forget()
        me = nil
        signedIn = false
        askingNickname = false
        AppAnalytics.setMember(false)
        accountChanged()
    }

    private func accountChanged() {
        epoch += 1
        // 챗봇 한도와 남은 양은 계정마다 따로다 — 앞 계정의 잠금을 다음 계정에 남기지 않는다(MZ2AZ-366).
        RateLimitLedger.shared.forget(.guide)
        RouteGuideSession.shared.forgetLimit()
        Task { await LikeStore.shared.refresh() }
    }

    private func loadMe() async {
        me = try? await AuthAPI.getMe()
    }
}

extension View {
    /// 로그인 화면을 올릴 자리. **지금 맨 위에 보이는 화면에만** `active` 를 켠다 — 덮개 밑의
    /// 화면이 시트를 올리려 들면 아무 일도 일어나지 않는다.
    func signInSheet(active: Bool = true) -> some View {
        modifier(SignInSheet(active: active))
    }

    /// 닉네임 정하기 화면을 올릴 자리 — `signInSheet` 와 같은 화면에, 같은 `active` 로 단다.
    func nicknameSheet(active: Bool = true) -> some View {
        modifier(NicknameSheet(active: active))
    }

    /// 계정이 바뀌면(로그인·로그아웃·탈퇴) 서버에서 다시 읽는다.
    func onAccountChange(_ reload: @escaping () async -> Void) -> some View {
        modifier(AccountChange(reload: reload))
    }
}

private struct SignInSheet: ViewModifier {
    let active: Bool
    @ObservedObject private var auth = AuthStore.shared

    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding(
            get: { active && auth.showingSignIn },
            set: {
                if !$0 {
                    auth.showingSignIn = false
                }
            }
        )) {
            SignInView()
                .presentationDetents([.medium])
        }
    }
}

private struct NicknameSheet: ViewModifier {
    let active: Bool
    @ObservedObject private var auth = AuthStore.shared

    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding(
            get: { active && auth.askingNickname },
            set: {
                // 쓸어내려 닫은 것도 건너뛴 것이다 — 다시 묻지 않는다.
                if !$0, auth.askingNickname {
                    auth.askingNickname = false
                    auth.skipNickname()
                }
            }
        )) {
            NicknameView(mode: .welcome) { auth.askingNickname = false }
                .presentationDetents([.medium])
        }
    }
}

private struct AccountChange: ViewModifier {
    let reload: () async -> Void
    @ObservedObject private var auth = AuthStore.shared

    func body(content: Content) -> some View {
        content.onChange(of: auth.epoch) { _, _ in
            Task { await reload() }
        }
    }
}
