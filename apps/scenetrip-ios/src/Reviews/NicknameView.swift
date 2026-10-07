import SwiftUI

/// 닉네임 정하기 (MZ2AZ-363). 로그인 직후 한 번 묻는 것과 마이페이지에서 바꾸는 것이 같은 화면이다.
///
/// 닉네임은 리뷰·후기에 작성자로 보이는 이름이다. 소셜 로그인 이름은 실명일 수 있어 남에게 보이지 않는다
/// — 그래서 서버가 `여행자12345` 꼴을 미리 붙여 두고, 여기서 바꾸게 한다. 건너뛰면 그 이름 그대로 쓴다.
struct NicknameView: View {
    enum Mode {
        /// 로그인 직후 — 「건너뛰기」 가 있다.
        case welcome
        /// 마이페이지 — 「취소」 가 있다.
        case edit
    }

    let mode: Mode
    let onDone: () -> Void

    @ObservedObject private var auth = AuthStore.shared
    @State private var text = ""
    @State private var saving = false
    /// 서버가 돌려준 판정. 글자를 고치면 지운다.
    @State private var serverMessage: String?
    @FocusState private var focused: Bool

    private var problem: NicknameRules.Problem? {
        NicknameRules.problem(in: text)
    }

    /// 지금 닉네임과 같으면 저장할 것이 없다(마이페이지). 처음 물을 때는 그대로 눌러도 「정했다」 가 된다.
    private var unchanged: Bool {
        mode == .edit && NicknameRules.normalized(text) == auth.me?.nickname
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(mode == .welcome ? tr("닉네임을 정해 주세요") : tr("닉네임 바꾸기"))
                .font(.title3.weight(.bold))
                .padding(.top, 28)
            Text("리뷰와 후기에 이 이름으로 보여요. 로그인 계정의 이름은 다른 사람에게 보이지 않습니다.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 6)

            TextField(tr("닉네임"), text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($focused)
                .onSubmit { save() }
                .padding(.horizontal, 14)
                .frame(height: 48)
                .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))
                .padding(.top, 20)
                .onChange(of: text) { _, _ in serverMessage = nil }

            // 서버 판정이 있으면 그것이 먼저다. 없으면 규칙 — 어긴 데가 있을 때만 빨갛게.
            Text(serverMessage ?? NicknameRules.hint(for: text.isEmpty ? nil : problem))
                .font(.footnote)
                .foregroundStyle(serverMessage != nil || (!text.isEmpty && problem != nil) ? Color.red : .secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)

            Spacer(minLength: 16)

            Button {
                save()
            } label: {
                HStack(spacing: 8) {
                    if saving {
                        ProgressView().tint(.white)
                    }
                    Text(mode == .welcome ? tr("이 닉네임으로 시작") : tr("저장"))
                        .font(.system(size: 16, weight: .semibold))
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(Capsule().fill(Color.accentColor.opacity(canSave ? 1 : 0.4)))
            }
            .buttonStyle(.plain)
            .disabled(!canSave)

            Button(mode == .welcome ? tr("건너뛰기") : tr("취소")) {
                if mode == .welcome {
                    auth.skipNickname()
                }
                onDone()
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.top, 14)
            .padding(.bottom, 18)
        }
        .padding(.horizontal, 24)
        .onAppear {
            text = auth.me?.nickname ?? ""
        }
    }

    private var canSave: Bool {
        !saving && problem == nil && !unchanged
    }

    private func save() {
        guard canSave else { return }
        saving = true
        focused = false
        Task {
            let outcome = await auth.setNickname(text)
            saving = false
            switch outcome {
            case .saved:
                onDone()
            case .invalid:
                serverMessage = tr("쓸 수 없는 닉네임이에요. 2~16자, 한글·영문·숫자·밑줄(_)만 쓸 수 있어요")
            case .taken:
                serverMessage = tr("이미 쓰는 닉네임입니다")
            case .failed:
                serverMessage = tr("저장하지 못했어요. 잠시 뒤 다시 해 주세요")
            }
        }
    }
}
