import SceneApiClient
import SwiftUI

/// 리뷰·여행후기의 「⋯」 — 「신고」 와 「이 사용자 차단」 (MZ2AZ-461, App Store 심사 지침 1.2).
///
/// 자기 글에는 그리지 않는다(`ModerationRules.showsMenu`) — 부르는 쪽이 거른다. 누르면 화면에 단
/// `moderationSheets` 가 시트·확인 창을 올린다.
struct ModerationMenu: View {
    let target: ModerationTarget
    /// 작성자가 있는 남의 글일 때만 차단을 둔다(`ModerationRules.canBlock`).
    let canBlock: Bool
    @ObservedObject var controller: ModerationController

    var body: some View {
        Menu {
            Button {
                controller.askReport(target)
            } label: {
                Label(tr("신고"), systemImage: "exclamationmark.bubble")
            }
            if canBlock {
                Button(role: .destructive) {
                    controller.askBlock(target)
                } label: {
                    Label(tr("이 사용자 차단"), systemImage: "nosign")
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 32, height: 28)
                // 그림은 작아도 누르는 자리는 넉넉히(44pt 가까이).
                .contentShape(Rectangle().inset(by: -8))
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(tr("신고·차단 메뉴"))
        .accessibilityIdentifier("moderation-menu")
    }
}

extension View {
    /// 신고 시트·차단 확인 창·안내를 올릴 자리. 목록 화면에 한 번 단다.
    /// `onBlocked` — 차단했다. 그 글을 지우고 목록을 다시 받는다(서버가 이후 목록에서 그 사람의 글을 뺀다).
    func moderationSheets(
        _ controller: ModerationController,
        onBlocked: @escaping (ModerationTarget) -> Void
    ) -> some View {
        modifier(ModerationSheets(controller: controller, onBlocked: onBlocked))
    }

    /// 어디서든 차단·풀기가 일어났다(`ModerationEvents`) — 받아 둔 리뷰·방문자 사진을 비우고 다시 받는다.
    func onModerationChange(_ action: @escaping () -> Void) -> some View {
        modifier(ModerationChange(action: action))
    }
}

private struct ModerationChange: ViewModifier {
    let action: () -> Void
    @ObservedObject private var events = ModerationEvents.shared

    func body(content: Content) -> some View {
        content.onChange(of: events.version) { _, _ in action() }
    }
}

private struct ModerationSheets: ViewModifier {
    @ObservedObject var controller: ModerationController
    let onBlocked: (ModerationTarget) -> Void

    func body(content: Content) -> some View {
        content
            .sheet(item: $controller.reporting, onDismiss: ReportSheetPresence.shared.disappeared) { target in
                ReportSheet(target: target, controller: controller)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
            }
            .alert(
                tr("이 사용자를 차단할까요?"),
                isPresented: Binding(
                    get: { controller.confirmingBlock != nil },
                    set: {
                        if !$0 {
                            controller.confirmingBlock = nil
                        }
                    }
                ),
                presenting: controller.confirmingBlock
            ) { target in
                Button(tr("차단"), role: .destructive) {
                    Task {
                        if await controller.block(target) {
                            onBlocked(target)
                        }
                    }
                }
                Button(tr("취소"), role: .cancel) {}
            } message: { _ in
                Text("이 사람의 리뷰·후기가 더 이상 보이지 않아요. 마이페이지의 「차단한 사용자」에서 풀 수 있어요.")
            }
            .alert(
                controller.notice ?? "",
                isPresented: Binding(
                    get: { controller.notice != nil },
                    set: {
                        if !$0 {
                            controller.notice = nil
                        }
                    }
                )
            ) {
                Button(tr("확인"), role: .cancel) {}
            }
    }
}

/// 신고 — 이유 여섯 중 하나와 설명(선택). 보내면 「접수됐어요」 를 보이고 닫는다.
struct ReportSheet: View {
    let target: ModerationTarget
    @ObservedObject var controller: ModerationController

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var auth = AuthStore.shared
    @State private var reason: ReportReason?
    @State private var detail = ""
    /// 보낸 결과 — 알림으로 보인다.
    @State private var outcome: ModerationRules.ReportOutcome?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(ModerationRules.reasons, id: \.self) { item in
                        Button {
                            reason = item
                        } label: {
                            HStack {
                                Text(ModerationRules.label(item)).foregroundStyle(.primary)
                                Spacer()
                                if reason == item {
                                    Image(systemName: "checkmark")
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(Color.accentColor)
                                }
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(reason == item ? .isSelected : [])
                    }
                } header: {
                    Text("신고 이유")
                } footer: {
                    Text("운영자가 확인하고 규칙을 어긴 글은 내려요.")
                }
                Section {
                    TextField(tr("자세한 내용 (선택)"), text: $detail, axis: .vertical)
                        .lineLimit(3 ... 6)
                        .onChange(of: detail) { _, text in
                            if text.count > ModerationRules.detailLimit {
                                detail = String(text.prefix(ModerationRules.detailLimit))
                            }
                        }
                } header: {
                    Text("설명")
                } footer: {
                    Text(verbatim: "\(detail.count)/\(ModerationRules.detailLimit)")
                }
            }
            .navigationTitle(tr("신고"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr("취소")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if controller.sending {
                        ProgressView()
                    } else {
                        Button(tr("보내기")) { submit() }
                            .disabled(reason == nil)
                    }
                }
            }
            .interactiveDismissDisabled(controller.sending)
            // 떠 있는 동안 밑의 화면은 로그인 화면을 올리지 않는다(`ReportSheetPresence`). 신고 중에 세션이 풀려
            // 로그인을 청하면 이 시트가 먼저 닫히고, 닫힌 뒤 밑의 화면이 로그인 화면을 올린다 — 덮개 밑에서는 시트가
            // 뜨지 않는다(`signInSheet` 규칙).
            .onAppear {
                ReportSheetPresence.shared.appeared(signInRequested: auth.showingSignIn) { dismiss() }
            }
            .onDisappear { ReportSheetPresence.shared.disappeared() }
            .onChange(of: auth.showingSignIn) { _, showing in
                ReportSheetPresence.shared.signInChanged(showing) { dismiss() }
            }
            .alert(
                outcome.map { ModerationRules.message($0) } ?? "",
                isPresented: Binding(
                    get: { outcome != nil },
                    set: {
                        if !$0 {
                            outcome = nil
                        }
                    }
                ),
                presenting: outcome
            ) { shown in
                Button(tr("확인"), role: .cancel) {
                    if shown.finishes {
                        dismiss()
                    }
                }
            }
        }
    }

    private func submit() {
        guard let reason else { return }
        Task {
            outcome = await controller.report(target, reason: reason, detail: detail)
        }
    }
}
