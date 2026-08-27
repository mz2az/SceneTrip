import SceneApiClient
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

    @State private var saved: Set<Int64> = []

    /// 담는 중인 코스. 서버를 오가므로 그동안 표시가 있어야 한다.
    @State private var busy: Int64?

    var body: some View {
        if embedded {
            list.modifier(SaveFailureAlert())
        } else {
            NavigationStack {
                list
                    .modifier(SaveFailureAlert())
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
            if !store.marketLoaded {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            } else if store.marketCourses.isEmpty {
                // **「아직 없다」와 「못 받았다」는 다르다.** 둘을 같은 빈 화면으로
                // 보여 주면 사용자가 앱이 고장 났는지 알 수 없다.
                ContentUnavailableView(
                    "아직 올라온 코스가 없습니다",
                    systemImage: "tray",
                    description: Text("코스를 만들고 「마켓에 올리기」를 누르면 여기 보입니다")
                )
                .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(store.marketCourses, id: \.id) { course in
                        row(course)
                    }
                } footer: {
                    Text("이름과 정렬 기준이 아직 정해지지 않은 화면입니다")
                }
            }
        }
        .listStyle(.insetGrouped)
        .task { await store.refreshMarket() }
        .refreshable { await store.refreshMarket() }
    }

    private func row(_ course: MarketCourseSummary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(course.title).font(.headline)

            // **남의 코스에는 날짜가 없다.** 올린 사람의 날짜를 그대로 보여 주면
            // 담아 간 사람에게는 틀린 정보가 된다 — 「N일」로만 보여 준다.
            Text("\(course.dayCount)일 · \(course.placeCount)곳")
                .font(.caption).foregroundStyle(.secondary)

            if !course.description.isEmpty {
                Text(course.description)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }

            // 어느 작품 코스인지. 사용자가 마켓에 오는 동기가 이것이다.
            if let works = course.contents?.map(\.title), !works.isEmpty {
                HStack(spacing: 4) {
                    Image(systemName: "film").font(.system(size: 9))
                    Text(works.prefix(2).joined(separator: " · "))
                        .font(.caption2.weight(.medium)).lineLimit(1)
                }
                .foregroundStyle(Color(PinImage.deep))
            }

            HStack(spacing: 12) {
                Button {
                    Task { await store.toggleMarketLike(course) }
                } label: {
                    Label("\(course.likeCount)", systemImage: course.liked ? "heart.fill" : "heart")
                        .foregroundStyle(course.liked ? Color.pink : Color.secondary)
                }
                .buttonStyle(.plain)

                Label("\(course.saveCount)", systemImage: "bag")
                Spacer()

                Button {
                    busy = course.id
                    Task {
                        if await store.saveFromMarket(course) {
                            saved.insert(course.id)
                        }
                        busy = nil
                    }
                } label: {
                    Group {
                        if busy == course.id {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(isSaved(course) ? "담았습니다" : "내 코스로 담기")
                                .font(.caption.weight(.semibold))
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .background(Capsule().fill(Color.accentColor.opacity(0.12)))
                }
                .buttonStyle(.plain)
                .disabled(isSaved(course) || busy != nil)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
    }

    /// 서버가 알려 주는 `saved` 를 먼저 믿고, 방금 담은 것은 새로고침 전까지
    /// 로컬로 기억한다.
    private func isSaved(_ course: MarketCourseSummary) -> Bool {
        course.saved || saved.contains(course.id)
    }
}

/// 담기·좋아요가 실패하면 알린다.
///
/// **말없이 실패하면 안 된다.** 특히 마켓은 **가입해야 되는 동작**이 셋이라
/// (올리기·담기·좋아요) 비회원이 누르면 `401` 이 온다 — 그때 아무 말이 없으면
/// 사용자는 버튼이 고장 난 줄 안다.
struct SaveFailureAlert: ViewModifier {
    @EnvironmentObject private var store: RouteStore

    func body(content: Content) -> some View {
        content.alert(title, isPresented: Binding(
            get: { store.failure != nil },
            set: {
                if !$0 {
                    store.clearFailure()
                }
            }
        )) {
            Button("확인", role: .cancel) {}
        } message: {
            Text(message)
        }
    }

    /// 가입이 필요한 것과 그냥 실패한 것을 갈라 말한다.
    private var title: String {
        store.failure?.statusCode == 401 ? "가입이 필요합니다" : "하지 못했습니다"
    }

    private var message: String {
        store.failure?.statusCode == 401
            ? "코스를 담고 좋아요를 누르려면 가입해야 합니다."
            : (store.failure?.message ?? "잠시 후 다시 시도해 주세요.")
    }
}
