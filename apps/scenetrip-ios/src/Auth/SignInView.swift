import SwiftUI

/// 로그인 화면 (MZ2AZ-336). 반쯤 올라오는 시트다.
///
/// 로그인은 **가입을 겸한다** — 처음 보는 구글 계정이면 그 자리에서 계정이 만들어지고,
/// 이 기기에서 비회원으로 담아 둔 장바구니·코스·찜이 그 계정으로 옮겨 간다.
///
/// 애플 로그인 단추는 서버 준비가 끝나면 이 아래에 놓는다 — 그때까지 자리를 비워 둔다.
struct SignInView: View {
    @ObservedObject private var auth = AuthStore.shared

    var body: some View {
        VStack(spacing: 0) {
            Image("haetae-joy")
                .resizable()
                .scaledToFit()
                .frame(height: 96)
                .padding(.top, 34)

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

            Button {
                Task { await auth.signInWithGoogle() }
            } label: {
                HStack(spacing: 10) {
                    if auth.busy {
                        ProgressView()
                    } else {
                        Text("G")
                            .font(.system(size: 18, weight: .bold, design: .rounded))
                            .foregroundStyle(Color(red: 0.26, green: 0.52, blue: 0.96))
                    }
                    Text("Google 로 계속하기")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(Capsule().fill(Color(.systemBackground)))
                .overlay(Capsule().strokeBorder(Color(.systemGray3), lineWidth: 1))
            }
            .buttonStyle(.plain)
            .disabled(auth.busy)

            Button("나중에 할게요") { auth.showingSignIn = false }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.top, 14)
                .padding(.bottom, 18)
        }
        .padding(.horizontal, 24)
    }
}
