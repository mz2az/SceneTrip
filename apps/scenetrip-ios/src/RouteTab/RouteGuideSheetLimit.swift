import SwiftUI

/// 가이드 대화창의 **한도 안내와 남은 양** (MZ2AZ-366 C). 본체(`RouteGuideSheet.swift`)의 길이 한도
/// (`type_body_length`) 때문에 확장으로 뺐다 — 규칙은 `GuideLimit`, 상태는 `RouteGuideSession.limit`·`quota`.
extension RouteGuideSheet {
    var retryButton: some View {
        Button {
            Task { await session.retry() }
        } label: {
            Label("다시 시도", systemImage: "arrow.clockwise")
                .font(.caption.weight(.semibold))
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(Color.accentColor.opacity(0.14)))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("guide-retry")
    }

    // MARK: 챗봇 한도 (MZ2AZ-366 C)

    /// 한도(429 `GUIDE_LIMIT_REACHED`)에 걸린 동안의 안내와, 풀린 뒤의 한 줄.
    ///
    /// **자동으로 다시 보내지 않는다.** 풀리는 때를 알면 그때까지 전송을 잠그고 남은 분을 말한다. 풀리면
    /// 「이제 다시 물어볼 수 있어요」 와, 걸렸던 질문이 남아 있으면 「다시 시도」(같은 멱등 키).
    @ViewBuilder
    var limitNotice: some View {
        switch session.limit {
        case .clear:
            EmptyView()
        case .lifted:
            VStack(alignment: .leading, spacing: 8) {
                Label("이제 다시 물어볼 수 있어요", systemImage: "checkmark.circle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("guide-limit-lifted")
                if session.canRetry {
                    retryButton
                }
            }
        case let .reached(block):
            VStack(alignment: .leading, spacing: 8) {
                // 남은 분은 시간이 가면 준다 — 받은 순간의 숫자를 그대로 두지 않는다.
                // 타임라인의 벽시계는 박자로만 쓴다 — 남은 분은 연속 시계로 잰다(기기 시계를 돌려도 같다).
                TimelineView(.periodic(from: .now, by: 5)) { _ in
                    Label(block.message(at: GuideLimit.uptime()), systemImage: "hourglass")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .accessibilityIdentifier("guide-limit-notice")
                }
                // 풀리는 때를 모르면 잠그지 않는다 — 걸렸던 질문을 바로 다시 보낼 수 있다(같은 멱등 키).
                if session.canRetry {
                    retryButton
                }
                if GuideLimit.Tuning.plannerHint {
                    plannerHint
                }
            }
        }
    }

    /// 일정짜기 마법사 안내(티켓·계약 「요청 한도」). **무료라고 말하지 않는다** — 지금 설정에서는 마법사도 모델을
    /// 부른다(계획 §7-6). 끄는 자리는 `GuideLimit.Tuning.plannerHint`.
    var plannerHint: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("새 일정은 「AI 로 짜기」에서도 만들 수 있어요")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let onOpenPlanner {
                Button(action: onOpenPlanner) {
                    Label("AI 로 짜기 열기", systemImage: "sparkles")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(Capsule().fill(Color.accentColor.opacity(0.14)))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("guide-open-planner")
            }
        }
    }

    /// 입력창 위 한 줄 — 「3번 더 물어볼 수 있어요 · 12분 뒤 다시 채워져요」.
    ///
    /// 서버가 마지막 답에 실어 준 값이다(`RateLimit-*`). 헤더가 없으면(옛 서버, 이번 실행에서 아직 안 물음)
    /// 줄이 없고, 넉넉할 때도 없다. 한도 안내가 떠 있는 동안에는 그 안내가 같은 말을 하므로 숨긴다.
    var remainingLine: some View {
        TimelineView(.periodic(from: .now, by: 5)) { _ in
            if !session.isLimited, let text = GuideLimit.remaining(session.quota, now: GuideLimit.uptime()).text {
                Label(text, systemImage: "bubble.left.and.text.bubble.right")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20).padding(.top, 8)
                    .accessibilityIdentifier("guide-remaining")
            }
        }
    }
}
