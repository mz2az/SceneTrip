import SwiftUI

/// 「내 위치」 를 눌렀는데 **뜻대로 되지 않은** 경우 (MZ2AZ-252).
///
/// 성공에 해당하는 값이 없는 것은 일부러다 — 성공하면 지도가 그 자리로 날아가므로
/// 사용자가 눈으로 안다. **실패만** 말이 필요하다. 눌렀는데 아무 일도 일어나지
/// 않는 것이 이 버튼의 원래 문제였다.
enum LocateOutcome: Identifiable {
    /// 권한이 거부·제한됐다. 앱 안에서는 풀 수 없고 설정으로 보내야 한다.
    case denied
    /// 권한은 있는데 좌표를 못 얻었다. 실내·비행기 모드에서 실제로 난다.
    case failed

    var id: Int {
        switch self {
        case .denied: 0
        case .failed: 1
        }
    }
}

/// 실패했을 때만 뜨는 안내.
///
/// 화면(`SearchTabView`)이 아니라 여기에 두는 이유는 두 가지다. 하나는 검색 탭
/// 본체가 이미 길어서 린트의 타입 길이 한도에 걸린다는 것이고, 다른 하나는
/// **경로 탭에서도 같은 버튼을 쓰게 되기 때문**이다. 그때 문구가 갈리면 안 된다.
private struct LocateFailureAlert: ViewModifier {
    @Binding var outcome: LocateOutcome?

    func body(content: Content) -> some View {
        content.alert(item: $outcome) { failure in
            switch failure {
            case .denied:
                // 왜 안 되는지에서 그치지 않고 **어디서 고치는지**까지 데려다준다.
                // 위치 권한은 앱 안에서 다시 물어볼 수 없어서, 설정으로 보내지
                // 않으면 사용자가 스스로 풀 방법이 없다.
                Alert(
                    title: Text("위치 권한이 필요합니다"),
                    message: Text("설정에서 위치 접근을 허용하면 현재 위치를 보여 드립니다."),
                    primaryButton: .default(Text("설정 열기")) {
                        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                        UIApplication.shared.open(url)
                    },
                    secondaryButton: .cancel(Text("닫기"))
                )
            case .failed:
                // 이쪽은 사용자가 할 수 있는 것이 기다리는 것뿐이라 버튼이 하나다.
                Alert(
                    title: Text("현재 위치를 찾지 못했습니다"),
                    message: Text("실내이거나 신호가 약할 수 있습니다. 잠시 후 다시 눌러 주세요."),
                    dismissButton: .default(Text("확인"))
                )
            }
        }
    }
}

extension View {
    /// 「내 위치」 실패 안내를 붙인다. 값이 `nil` 이면 아무것도 뜨지 않는다.
    func locateFailureAlert(_ outcome: Binding<LocateOutcome?>) -> some View {
        modifier(LocateFailureAlert(outcome: outcome))
    }
}
