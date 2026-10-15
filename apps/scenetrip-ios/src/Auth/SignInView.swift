import SwiftUI

/// 로그인 화면 (MZ2AZ-336). 반쯤 올라오는 시트다.
///
/// 로그인은 **가입을 겸한다** — 처음 보는 구글·Apple 계정이면 그 자리에서 계정이 만들어지고,
/// 이 기기에서 비회원으로 담아 둔 장바구니·코스·찜이 그 계정으로 옮겨 간다.
///
struct SignInView: View {
    @ObservedObject private var auth = AuthStore.shared
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Image("haetae-joy")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 72)
                    .padding(.top, 18)

                Text("SceneTrip 로그인")
                    .font(.title3.weight(.bold))
                    .padding(.top, 14)
                Text("담아 둔 장바구니·코스·찜이 계정에 저장돼요.\n다른 기기에서도 그대로 이어집니다.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)

                Spacer(minLength: 16)

                if let message = auth.message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .padding(.bottom, 10)
                }

                if auth.busy {
                    ProgressView()
                        .padding(.bottom, 10)
                }

                if auth.appleSignInEnabled {
                    AppleSignInButton(enabled: !auth.busy) {
                        Task { await auth.signInWithApple() }
                    }
                    .id(colorScheme)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .padding(.bottom, 10)
                }

                Button {
                    Task { await auth.signInWithGoogle() }
                } label: {
                    // 구글 브랜드 지침의 밝은 단추 — 흰 바탕, #747775 테두리, #1F1F1F 글자, **공식 「G」 로고**.
                    // 글자 「G」로 그려 두었더니 지침에 안 맞았다(동의 화면을 정식으로 올릴 때 심사 대상).
                    // 로고는 색·비율을 바꾸지 않는다. 문구도 구글이 허용한 것(「Google 계정으로 계속하기」) 중 하나다.
                    HStack(spacing: 10) {
                        Image("google-g")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 20, height: 20)
                            .accessibilityHidden(true)
                        Text("Google 계정으로 계속하기")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(Color(red: 0.12, green: 0.12, blue: 0.12))
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Capsule().fill(Color.white))
                    .overlay(Capsule().strokeBorder(Color(red: 0.455, green: 0.467, blue: 0.459), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .disabled(auth.busy)

                legalNotice
                    .padding(.top, 12)

                Button("나중에 할게요") { auth.showingSignIn = false }
                    .disabled(auth.busy)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .padding(.top, 14)
                    .padding(.bottom, 18)
            }
            .padding(.horizontal, 24)
        }
    }

    /// 가입 동의 안내 (MZ2AZ-455). 로그인이 가입을 겸하므로 단추 바로 아래에 둔다 — 체크박스는 없다.
    /// 두 링크는 따로 누를 수 있는 `Link` 라 VoiceOver 가 「링크」로 읽는다. 큰 글자에서 한 줄에
    /// 안 들어가면 위아래로 쌓는다. 주소는 `LegalLinks` 한 곳에 있다.
    private var legalNotice: some View {
        VStack(spacing: 0) {
            Text("계속하면 이용약관과 개인정보 처리방침에 동의하는 것으로 봅니다.")
                .fixedSize(horizontal: false, vertical: true)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { legalLinks(separated: true) }
                VStack(spacing: 0) { legalLinks(separated: false) }
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
    }

    @ViewBuilder private func legalLinks(separated: Bool) -> some View {
        legalLink("이용약관", to: LegalLinks.terms)
        if separated {
            Text(verbatim: "·").accessibilityHidden(true)
        }
        legalLink("개인정보 처리방침", to: LegalLinks.privacy)
    }

    /// 글자는 작아도 누르는 자리는 44pt 이상으로 넓힌다 — 캡션 높이(14pt)만으로는 손가락이 빗나간다.
    private func legalLink(_ title: LocalizedStringKey, to url: URL) -> some View {
        Link(destination: url) {
            Text(title)
                .underline()
                .foregroundStyle(Color.accentColor)
                .frame(minHeight: 44)
                .contentShape(.rect)
        }
    }
}
