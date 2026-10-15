import SwiftUI

/// 비회원의 설치 기록 삭제. 가입자의 회원 탈퇴와 구분해 확인한다.
struct ProfileDeviceDataSection: View {
    @ObservedObject private var auth = AuthStore.shared
    @State private var confirming = false
    @State private var message: String?

    var body: some View {
        if GuestDataDeletion.isVisible(signedIn: auth.signedIn) {
            Section {
                Button(role: .destructive) {
                    confirming = true
                } label: {
                    HStack {
                        Text("이 기기의 기록 지우기").font(.subheadline)
                        Spacer()
                        if auth.busy {
                            ProgressView()
                        }
                    }
                }
                .disabled(auth.busy)
                .alert("이 기기의 기록을 지울까요?", isPresented: $confirming) {
                    Button("지우기", role: .destructive) { Task { await delete() } }
                    Button("취소", role: .cancel) {}
                } message: {
                    Text("이 기기의 장바구니·코스·찜이 모두 지워지고 되돌릴 수 없어요.")
                }
                .alert(message ?? "", isPresented: Binding(
                    get: { message != nil }, set: {
                        if !$0 {
                            message = nil
                        }
                    }
                )) {
                    Button("확인", role: .cancel) { message = nil }
                }
            } footer: {
                Text("이 기기의 발자취도 함께 지워져요.")
            }
        }
    }

    private func delete() async {
        switch await auth.deleteDeviceData() {
        case .deleted:
            message = tr("이 기기의 기록을 지웠어요.")
        case .sessionRequired:
            message = tr("가입한 계정의 기록이에요. 로그인한 뒤 마이페이지에서 회원 탈퇴를 이용해 주세요.")
        case .failed:
            message = tr("기록을 지우지 못했어요. 잠시 뒤 다시 시도해 주세요.")
        }
    }
}
