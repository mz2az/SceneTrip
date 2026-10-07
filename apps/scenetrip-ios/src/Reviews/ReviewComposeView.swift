import SceneApiClient
import SwiftUI

/// 리뷰 쓰기·고치기 (MZ2AZ-363). 별점은 필수, 글은 선택이다 — 별점만 남겨도 된다.
///
/// 한 대상에 한 개라, 열 때 내 리뷰를 물어(`GET …/reviews/me`) 있으면 그것을 고치는 화면이 된다.
/// **가입한 사람만** 쓴다 — 이 화면은 로그인한 뒤에만 뜬다(`ReviewsSheet` 가 가른다).
///
/// 사진 올리기는 아직 없다 — 서버의 올리기 창구(`POST /uploads`)와 저장소가 붙은 뒤에 넣는다.
/// 이미 사진이 붙은 리뷰를 고칠 때는 그 사진을 그대로 남긴다(계약: 보낸 `photoKeys` 가 전부다).
struct ReviewComposeView: View {
    let subject: ReviewSubject
    let title: String
    /// 저장하거나 지웠다 — 목록과 별점 줄을 다시 읽게 한다.
    let onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var phase: Phase = .loading
    @State private var existing: Review?
    @State private var rating = 0
    @State private var text = ""
    @State private var working = false
    @State private var message: String?
    @State private var confirmingDelete = false
    /// 쓰던 것을 버릴지 묻는 창.
    @State private var confirmingDiscard = false
    @FocusState private var writing: Bool

    private enum Phase {
        case loading
        case ready
        case failed
    }

    /// 고친 것이 있는가 — 새 리뷰는 별이나 글을 건드렸을 때.
    private var dirty: Bool {
        guard phase == .ready else { return false }
        if existing == nil {
            return rating != 0 || ReviewRules.normalizedBody(text) != nil
        }
        return ReviewRules.changed(from: existing, rating: rating, body: text)
    }

    private var canSave: Bool {
        !working && dirty && ReviewRules.canSave(rating: rating, body: text)
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            // 오류는 머리줄 바로 아래에 고정한다 — 글이 길면 본문 아래 줄은 화면 밖으로 밀린다.
            if let message {
                Text(message)
                    .font(.footnote).foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 9)
                    .background(Color.red)
            }
            switch phase {
            case .loading:
                Spacer()
                ProgressView()
                Spacer()
            case .failed:
                Spacer()
                Text("리뷰를 불러오지 못했어요").font(.subheadline.weight(.medium))
                Button(tr("다시 시도")) {
                    phase = .loading
                    Task { await load() }
                }
                .font(.subheadline)
                .padding(.top, 8)
                Spacer()
            case .ready:
                form
            }
        }
        .task { await load() }
        // 쓰던 것이 있으면 쓸어내려 닫히지 않는다 — 「취소」 가 버릴지 묻는다.
        .interactiveDismissDisabled(working || dirty)
        // 세션이 풀리면 로그인 화면이 이 화면 위로 올라와야 한다 — 밑의 시트는 이미 이 화면을 올리고 있다.
        .signInSheet()
        // 경고창으로 묻는다 — iOS 26 의 선택 팝업은 취소 단추를 숨겨 「버리기」 하나만 보였다.
        .alert(tr("쓰던 리뷰를 버릴까요?"), isPresented: $confirmingDiscard) {
            Button(tr("버리기"), role: .destructive) { dismiss() }
            Button(tr("계속 쓰기"), role: .cancel) {}
        }
        .alert(tr("리뷰를 지울까요?"), isPresented: $confirmingDelete) {
            Button(tr("지우기"), role: .destructive) { remove() }
            Button(tr("취소"), role: .cancel) {}
        } message: {
            Text("지우면 되돌릴 수 없어요.")
        }
    }

    private var header: some View {
        ZStack {
            VStack(spacing: 1) {
                Text(existing == nil ? tr("리뷰 쓰기") : tr("리뷰 고치기")).font(.headline)
                Text(title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .padding(.horizontal, 76)
            HStack {
                Button(tr("취소")) {
                    if dirty {
                        confirmingDiscard = true
                    } else {
                        dismiss()
                    }
                }
                .disabled(working)
                Spacer()
                Button {
                    save()
                } label: {
                    if working {
                        ProgressView().accessibilityLabel(tr("저장 중"))
                    } else {
                        Text("저장").fontWeight(.semibold)
                    }
                }
                .disabled(!canSave)
            }
            .padding(.horizontal, 16)
        }
        .frame(height: 52)
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(spacing: 8) {
                    StarPicker(rating: $rating)
                    Text(rating == 0 ? tr("별을 눌러 점수를 주세요") : String(format: tr("%d점"), rating))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 22)

                VStack(alignment: .leading, spacing: 6) {
                    ZStack(alignment: .topLeading) {
                        if text.isEmpty {
                            Text("어땠는지 들려주세요 (선택)")
                                .font(.body).foregroundStyle(.tertiary)
                                .padding(.horizontal, 5).padding(.vertical, 8)
                                .allowsHitTesting(false)
                        }
                        // 높이에 상한을 둔다 — 글만큼 계속 커지면 글자 수와 지우기 단추가 화면 밖으로 밀린다.
                        TextEditor(text: $text)
                            .font(.body)
                            .focused($writing)
                            .frame(minHeight: 150, maxHeight: 260)
                            .scrollContentBackground(.hidden)
                            .accessibilityLabel(tr("리뷰 글"))
                    }
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(.secondarySystemBackground)))

                    HStack {
                        let count = ReviewRules.bodyLength(text)
                        if count > ReviewRules.bodyLimit {
                            Text("글이 너무 길어요").font(.footnote).foregroundStyle(.red)
                        }
                        Spacer()
                        Text(verbatim: "\(count)/\(ReviewRules.bodyLimit)")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(count > ReviewRules.bodyLimit ? Color.red : Color(.tertiaryLabel))
                    }
                }

                if existing != nil {
                    Button(role: .destructive) {
                        confirmingDelete = true
                    } label: {
                        Label(tr("리뷰 지우기"), systemImage: "trash")
                            .font(.subheadline)
                    }
                    .disabled(working)
                    .padding(.top, 4)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func load() async {
        do {
            existing = try await subject.myReview()
            rating = existing?.rating ?? 0
            text = existing?.body ?? ""
            phase = .ready
        } catch {
            phase = .failed
        }
    }

    private func save() {
        guard canSave else { return }
        working = true
        writing = false
        message = nil
        Task {
            defer { working = false }
            do {
                let wasNew = existing == nil
                // 이미 붙어 있던 사진은 그대로 남긴다 — 빼고 보내면 지워진다.
                let saved = try await subject.save(
                    rating: rating, body: ReviewRules.normalizedBody(text),
                    photoKeys: existing?.photos.map(\.key) ?? []
                )
                AppAnalytics.log(.writeReview(
                    targetType: subject.kind, rating: saved.rating,
                    photoCount: saved.photos.count, hasBody: saved.body != nil, edited: !wasNew
                ))
                onChanged()
                dismiss()
            } catch {
                message = failureText(error)
            }
        }
    }

    private func remove() {
        working = true
        message = nil
        Task {
            defer { working = false }
            do {
                try await subject.deleteMine()
                AppAnalytics.log(.deleteReview(targetType: subject.kind))
                onChanged()
                dismiss()
            } catch {
                message = failureText(error)
            }
        }
    }

    /// 서버 문장이 아니라 code 로 가른다(MZ2AZ-345).
    private func failureText(_ error: Error) -> String {
        if case let ErrorResponse.error(status, data, _, _) = error {
            let code = AuthRules.apiCode(from: data)
            // 세션이 풀렸다 — 로그인 화면이 위로 올라온다(`AuthStore.sessionLost`). 쓰던 글은 그대로 둔다.
            // 로그인 상태 값이 아니라 code 로 가른다 — 상태는 다른 작업이 바꿔서 순서를 믿을 수 없다.
            if AuthRules.action(status: status, code: code) == .signOut {
                return tr("로그인이 풀렸어요. 다시 로그인해 주세요")
            }
            switch code {
            case "SIGN_IN_REQUIRED": return tr("로그인한 뒤에 쓸 수 있어요")
            case "REVIEW_PHOTO_INVALID": return tr("사진을 다시 올려 주세요")
            default: break
            }
        }
        return tr("저장하지 못했어요. 잠시 뒤 다시 해 주세요")
    }
}

/// 별 다섯 — 눌러서 1~5점을 고른다.
struct StarPicker: View {
    @Binding var rating: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(1 ... 5, id: \.self) { index in
                Button {
                    rating = index
                } label: {
                    Image(systemName: index <= rating ? "star.fill" : "star")
                        .font(.system(size: 34))
                        .foregroundStyle(
                            index <= rating ? Color(red: 1.0, green: 0.72, blue: 0.0) : Color(.systemGray3)
                        )
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(format: tr("%d점"), index))
                .accessibilityAddTraits(index == rating ? .isSelected : [])
            }
        }
    }
}
