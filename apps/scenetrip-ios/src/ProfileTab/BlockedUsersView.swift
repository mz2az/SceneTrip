import SceneApiClient
import SwiftUI

/// 마이페이지 「차단한 사용자」 (MZ2AZ-461) — 내가 차단한 사람과 차단한 때, 줄마다 「차단 풀기」.
///
/// 가입한 사람만 연다(차단은 로그인해야 한다). 풀면 그 사람의 리뷰·후기가 다음에 받을 때부터 다시 보인다.
struct BlockedUsersView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store = BlockedUsersStore()
    @ObservedObject private var auth = AuthStore.shared

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(tr("차단한 사용자"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(tr("닫기")) { dismiss() }
                    }
                }
        }
        .task { await store.reload() }
        // 로그아웃·탈퇴하면 남의 목록을 남겨 두지 않는다.
        .onChange(of: auth.signedIn) { _, signedIn in
            if !signedIn {
                dismiss()
            }
        }
        .alert(
            store.message ?? "",
            isPresented: Binding(
                get: { store.message != nil },
                set: {
                    if !$0 {
                        store.message = nil
                    }
                }
            )
        ) {
            Button(tr("확인"), role: .cancel) {}
        }
    }

    @ViewBuilder private var content: some View {
        switch store.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 10) {
                Text("차단 목록을 불러오지 못했어요").font(.subheadline.weight(.medium))
                Button(tr("다시 시도")) { Task { await store.reload() } }
                    .frame(minHeight: 44)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded where store.items.isEmpty:
            ContentUnavailableView {
                Label(tr("차단한 사용자가 없어요"), systemImage: "person.crop.circle.badge.checkmark")
            } description: {
                Text("리뷰나 여행후기의 「⋯」 에서 작성자를 차단할 수 있어요")
            }
        case .loaded:
            List {
                Section {
                    ForEach(store.items, id: \.id) { user in
                        row(user)
                    }
                } footer: {
                    Text("차단한 사람의 리뷰와 여행후기는 보이지 않아요. 차단한 사람에게는 알리지 않아요.")
                }
            }
            .refreshable { await store.reload() }
        }
    }

    private func row(_ user: BlockedUser) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: user.nickname).font(.subheadline.weight(.medium))
                Text(String(
                    format: tr("%@ 차단"),
                    user.blockedAt.formatted(.dateTime.year().month().day().locale(AppLanguage.currentLocale))
                ))
                .font(.caption).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            Spacer()
            if store.unblocking.contains(user.id) {
                ProgressView()
            } else {
                Button(tr("차단 풀기")) {
                    Task {
                        if await store.unblock(user) {
                            // 여행후기 목록이 그 사람의 글을 다시 보이게 받는다.
                            await CommunityStore.shared.invalidateAndRefresh()
                        }
                    }
                }
                .buttonStyle(.borderless)
                .font(.subheadline.weight(.semibold))
                .accessibilityLabel(String(format: tr("%@ 차단 풀기"), user.nickname))
            }
        }
        .frame(minHeight: 44)
    }
}

/// 마이페이지 「문의하기」 (MZ2AZ-461, App Store 1.2 — 연락처 공개). 주소를 **글자로** 보인다 — 메일 앱이 없는
/// 기기에서도 주소를 길게 눌러 복사할 수 있다(`textSelection`). 옆의 단추는 메일 앱을 연다. 주소는 `LegalLinks.supportEmail` 한 곳에 있다.
struct SupportContactRow: View {
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "envelope")
                .font(.system(size: 15))
                .foregroundStyle(.blue)
                .frame(width: 26)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("문의하기").font(.subheadline)
                Text(verbatim: LegalLinks.supportEmail)
                    .font(.caption).foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            Spacer()
            Link(destination: LegalLinks.supportMail) {
                Text("메일 쓰기").font(.subheadline.weight(.semibold))
            }
            .buttonStyle(.borderless)
            .accessibilityHint(Text(verbatim: LegalLinks.supportEmail))
        }
    }
}
