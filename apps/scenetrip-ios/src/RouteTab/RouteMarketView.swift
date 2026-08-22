import SwiftUI

/// 남이 올린 코스를 보고 내 것으로 담는 화면.
///
/// **미확정·데모용이다.** 8/11 회의에서 이 화면 자체는 살아 있지만 **이름이 정해지지
/// 않았다** — 「코스 마켓」은 물건을 사는 느낌이라는 반대가 나왔고 「인기 코스」·
/// 「다양한 코스」가 후보로만 남았다(회의록 5장 #1). 화면에는 일단 「인기 코스」로
/// 쓰고, 정해지면 이 파일에서 문구만 바꾼다.
///
/// 정렬 기준(담기순·좋아요순), 같은 코스를 여러 번 올릴 수 있는지, 검색을 무엇으로
/// 하는지도 전부 열려 있다. 그래서 여기서는 **목록과 담기까지만** 만든다 —
/// 정해지지 않은 것을 구현으로 굳히지 않으려는 것이다.
///
/// 좋아요·담긴 수는 지어낸 값이다. 순서를 보여 주려고 넣었다.
struct RouteMarketView: View {
    /// 탭의 세그먼트 안에 들어가 있는가.
    ///
    /// 목업이 마켓을 「내 코스」와 나란한 세그먼트로 두면서 이 화면은 두 자리에서
    /// 쓰이게 됐다. 시트로 열릴 때는 자기 내비게이션과 「닫기」가 필요하지만,
    /// 세그먼트 안에서는 **둘 다 있으면 안 된다** — 내비게이션이 겹쳐 제목이 두 줄이
    /// 되고, 닫을 것이 없는데 「닫기」가 뜬다.
    var embedded = false

    @EnvironmentObject private var store: RouteStore
    @Environment(\.dismiss) private var dismiss

    @State private var saved: Set<RouteCourse.ID> = []

    var body: some View {
        if embedded {
            list
        } else {
            NavigationStack {
                list
                    .navigationTitle("인기 코스")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("닫기") { dismiss() }
                        }
                    }
            }
        }
    }

    private var list: some View {
        List {
            Section {
                ForEach(store.popularCourses.indices, id: \.self) { index in
                    row(store.popularCourses[index], stats: store.popularStats[index])
                }
            } footer: {
                Text("이름과 정렬 기준이 아직 정해지지 않은 화면입니다 (데모)")
            }
        }
        .listStyle(.insetGrouped)
    }

    private func row(_ course: RouteCourse, stats: (likes: Int, saves: Int)) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(course.title).font(.headline)

            // **남의 코스에는 날짜가 없다.** 올린 사람의 날짜를 그대로 보여 주면
            // 담아 간 사람에게는 틀린 정보가 된다 — 「1일차·2일차」로만 보여 준다.
            Text("\(course.spanLabel) · \(course.stops.count)곳")
                .font(.caption).foregroundStyle(.secondary)

            ForEach(Array(course.days.enumerated()), id: \.element.id) { index, day in
                Text("\(index + 1)일차 · \(day.stops.map(\.place.name).joined(separator: " → "))")
                    .font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
            }

            HStack(spacing: 12) {
                Label("\(stats.likes)", systemImage: "heart")
                Label("\(stats.saves)", systemImage: "bag")
                Spacer()
                Button {
                    // 담는 순간 순서와 체류 시간을 통째로 떠 온다. 사본이라 원본이
                    // 나중에 바뀌어도 내 코스는 그대로다.
                    _ = store.copyToMine(course)
                    saved.insert(course.id)
                } label: {
                    Text(saved.contains(course.id) ? "담았습니다" : "내 코스로 담기")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(Capsule().fill(Color.accentColor.opacity(0.12)))
                }
                .buttonStyle(.plain)
                .disabled(saved.contains(course.id))
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
    }
}
