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
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var detent: PresentationDetent = .height(480)

    private var problem: NicknameRules.Problem? {
        NicknameRules.problem(in: text)
    }

    /// 입력란이 지금 닉네임 그대로인가.
    private var unchanged: Bool {
        NicknameRules.normalized(text) == auth.me?.nickname
    }

    /// 지금 닉네임 그대로면 규칙을 따지지 않는다 — 자동 닉네임(`여행자12345`)이 미리 채워져 있는데
    /// 그것을 「고를 수 없는 이름」 이라고 빨갛게 적으면 안 된다.
    private var shownProblem: NicknameRules.Problem? {
        unchanged ? nil : problem
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(mode == .welcome ? tr("닉네임을 정해 주세요") : tr("닉네임 바꾸기"))
                            .font(.title2.weight(.bold))
                        Text("리뷰와 후기에 이 이름으로 보여요. 로그인 계정의 이름은 다른 사람에게 보이지 않습니다.")
                            .font(.subheadline).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    Button { cancel() } label: {
                        Image(systemName: "xmark").font(.system(size: 17, weight: .semibold))
                            .frame(width: 44, height: 44)
                            .background(Color(.secondarySystemGroupedBackground), in: Circle())
                    }.buttonStyle(.plain).disabled(saving).accessibilityLabel(tr("닫기"))
                }
                namePreview
                input
            }
            .padding(.horizontal, 24).padding(.vertical, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color(.systemGroupedBackground))
        .safeAreaInset(edge: .bottom) { actions }
        .tint(TabBar.homePurple)
        .interactiveDismissDisabled(saving)
        .presentationDetents([.height(480), .large], selection: $detent)
        .onAppear {
            text = auth.me?.nickname ?? ""
            if typeSize.isAccessibilitySize {
                detent = .large
            }
        }
        .onChange(of: typeSize) { _, size in
            if size.isAccessibilitySize {
                detent = .large
            }
        }
    }

    private var namePreview: some View {
        HStack(spacing: 16) {
            PinoMascot(width: 66).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text("작성자 이름 미리보기").font(.caption).foregroundStyle(.secondary)
                Text(verbatim: NicknameRules.normalized(text).isEmpty ? tr("여행자") : NicknameRules.normalized(text))
                    .font(.title3.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.65)
                    .accessibilityIdentifier("nickname-preview")
            }
            Spacer(minLength: 0)
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
    }

    private var input: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("닉네임").font(.subheadline.weight(.semibold))
                Spacer()
                Text(verbatim: "\(NicknameRules.normalized(text).count)/\(NicknameRules.maxLength)")
                    .font(.caption.monospacedDigit()).foregroundStyle(shownProblem == .tooLong ? .red : .secondary)
            }
            HStack(spacing: 8) {
                TextField(tr("닉네임"), text: $text)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .submitLabel(.done).focused($focused).onSubmit { save() }
                    .accessibilityIdentifier("nickname-input")
                if !text.isEmpty {
                    Button { text = ""; focused = true } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                            .frame(width: 32, height: 44)
                    }.buttonStyle(.plain).accessibilityLabel(tr("입력 지우기"))
                }
            }
            .padding(.horizontal, 14).frame(minHeight: 56)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
            .overlay { RoundedRectangle(cornerRadius: 14).strokeBorder(focused ? TabBar.homePurple : .clear, lineWidth: 1.5) }
            .disabled(saving).onChange(of: text) { _, _ in serverMessage = nil }
            Text(serverMessage ?? NicknameRules.hint(for: text.isEmpty ? nil : shownProblem))
                .font(.footnote)
                .foregroundStyle(serverMessage != nil || (!text.isEmpty && shownProblem != nil) ? Color.red : .secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var actions: some View {
        VStack(spacing: 6) {
            Button { save() } label: {
                HStack(spacing: 8) {
                    if saving {
                        ProgressView().tint(.white)
                    }
                    Text(mode == .welcome ? tr("이 닉네임으로 시작") : tr("저장"))
                        .font(.headline)
                }
                .foregroundStyle(.white).padding(.vertical, 15)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(Capsule().fill(TabBar.homePurple.opacity(canSave ? 1 : 0.4)))
            }.buttonStyle(.plain).disabled(!canSave)
            Button(mode == .welcome ? tr("건너뛰기") : tr("취소")) { cancel() }
                .font(.subheadline).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 44).disabled(saving)
        }
        .padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 6)
        .background(Color(.systemBackground)).overlay(alignment: .top) { Divider() }
    }

    private func cancel() {
        guard !saving else { return }
        if mode == .welcome {
            auth.skipNickname()
        }
        onDone()
    }

    /// 마이페이지에서는 바뀐 것이 있어야 저장한다. 처음 물을 때는 미리 채워진 자동 닉네임 그대로도 누를 수 있다
    /// — 그것은 「이 이름으로 간다」 는 뜻이라 건너뛰기와 같다(서버는 자동 닉네임을 다시 정하는 것을 받지 않는다).
    private var canSave: Bool {
        guard !saving else { return false }
        if unchanged {
            return mode == .welcome && !text.isEmpty
        }
        return problem == nil
    }

    private func save() {
        guard canSave else { return }
        if unchanged {
            // 자동 닉네임 그대로 시작한다 — 서버에 보낼 것이 없다.
            auth.skipNickname()
            onDone()
            return
        }
        saving = true
        focused = false
        let requestEpoch = auth.epoch
        Task {
            guard auth.epoch == requestEpoch else {
                saving = false
                return
            }
            let outcome = await auth.setNickname(text)
            saving = false
            guard auth.epoch == requestEpoch else { return }
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
