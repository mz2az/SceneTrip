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
            showingSignIn = false
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
            forget()
            return true
        } catch {
            return false
        }
    }

    /// 세션이 깨졌다(토큰 위조·폐기·리프레시 만료). 토큰을 지우고 로그인 화면을 띄운다.
    func sessionLost() {
        guard signedIn else { return }
        forget()
        message = tr("로그인이 풀렸어요. 다시 로그인해 주세요")
        showingSignIn = true
    }

    private func forget() {
        // 발자취는 기기에만 있지만 그 사람의 것이다 — 계정이 떠나면 함께 지운다 (MZ2AZ-348).
        FootprintStore.shared.forgetOwner()
        AuthTokens.clear()
        GoogleOAuth.forget()
        me = nil
        signedIn = false
        accountChanged()
    }

    private func accountChanged() {
        epoch += 1
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

private struct AccountChange: ViewModifier {
    let reload: () async -> Void
    @ObservedObject private var auth = AuthStore.shared

    func body(content: Content) -> some View {
        content.onChange(of: auth.epoch) { _, _ in
            Task { await reload() }
        }
    }
}
