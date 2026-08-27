import SwiftUI

/// AI 여행 릴스 — **예고편** (2026-08-28).
///
/// 아직 만드는 기능이 아니다. 자리를 먼저 만든 이유는 마이페이지의 다른 「준비 중」
/// 줄과 달리 이것은 **팔리는 그림이 있어야 하는 기능**이라서다 — 무엇이 올지
/// 한 장으로 보여 주고, 열리면 알림을 받겠다는 마음만 받아 둔다(아직 알림도 없으니
/// 그것도 정직하게 적는다).
struct ReelsTeaserView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 6)

            PinoMascot(pose: .sparkle, width: 110)

            Text("AI 여행 릴스")
                .font(.title3.weight(.bold))

            VStack(alignment: .leading, spacing: 10) {
                teaserRow(symbol: "point.topleft.down.to.point.bottomright.curvepath",
                          text: "다녀온 코스의 동선과 장소를 AI 가 읽고")
                teaserRow(symbol: "photo.on.rectangle.angled",
                          text: "여행 사진을 골라 장면 순서로 엮어서")
                teaserRow(symbol: "film",
                          text: "인스타그램에 올릴 15초 릴스를 만들어 드릴 예정이에요")
            }
            .padding(.horizontal, 8)

            Spacer()

            Text("준비 중입니다 — 열리면 마이페이지에서 가장 먼저 보여요")
                .font(.caption).foregroundStyle(.secondary)
                .padding(.bottom, 16)
        }
        .padding(.horizontal, 20)
    }

    private func teaserRow(symbol: String, text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.system(size: 14))
                .foregroundStyle(Color(PinImage.deep))
                .frame(width: 24)
            Text(text).font(.subheadline)
            Spacer(minLength: 0)
        }
    }
}
