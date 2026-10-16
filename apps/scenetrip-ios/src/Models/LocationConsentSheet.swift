import SwiftUI

struct LocationConsentSheet: View {
    let allow: () -> Void
    let cancel: () -> Void

    var body: some View {
        ConsentSheet(
            title: "위치기반서비스 이용 동의",
            disclosure: tr("""
            현재 위치를 주변 촬영지·편의시설 찾기, AI 가이드, 길 안내에 씁니다. \
            위치는 요청을 처리하는 데만 쓰고 바로 지우며, 길 안내 때는 경로 계산을 위해 카카오에 보냅니다.
            """),
            linkTitle: "위치기반서비스 이용약관", link: LegalLinks.locationServices,
            allowTitle: "동의하고 계속", identifier: "location-consent",
            allow: allow, cancel: cancel
        )
    }
}

private struct LocationConsentPresentation: ViewModifier {
    @ObservedObject var gate: AIConsentGate
    var onCancel: () -> Void
    @State private var approved = false

    func body(content: Content) -> some View {
        content.sheet(isPresented: $gate.isPresented, onDismiss: didDismiss) {
            LocationConsentSheet(allow: allow, cancel: gate.cancel)
        }
    }

    private func allow() {
        approved = true
        gate.allow()
    }

    private func didDismiss() {
        let cancelled = !approved
        approved = false
        gate.didDismiss()
        if cancelled {
            onCancel()
        }
    }
}

private struct KakaoDirectionsBanner: ViewModifier {
    @ObservedObject var notice: KakaoDirectionsNotice

    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if let id = notice.visibleRequest {
                Text("길 안내를 위해 카카오에 현재 위치를 보냈어요.")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 9)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Color(PinImage.deep)))
                    .padding(.horizontal, 16).padding(.top, 54)
                    .accessibilityIdentifier("kakao-location-notice")
                    .allowsHitTesting(false)
                    .task(id: id) {
                        try? await Task.sleep(for: .seconds(4))
                        guard !Task.isCancelled else { return }
                        notice.hide(id)
                    }
            }
        }
    }
}

extension View {
    func locationConsentSheet(_ gate: AIConsentGate, onCancel: @escaping () -> Void = {}) -> some View {
        modifier(LocationConsentPresentation(gate: gate, onCancel: onCancel))
    }

    func kakaoDirectionsNotice(_ notice: KakaoDirectionsNotice) -> some View {
        modifier(KakaoDirectionsBanner(notice: notice))
    }
}
