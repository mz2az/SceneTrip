import SwiftUI

/// 옆으로 한 쪽씩 넘기는 줄 (MZ2AZ-363) — 대표 사진 자리와 크게 보기가 같이 쓴다.
///
/// `TabView(.page)` 를 쓰지 않는다 — 쪽을 전부 만들어 두려 해서 사진이 수십 장이면 무겁다.
/// 게으른 가로 줄에 쪽 단위로 멈추게 한다(보이는 쪽 둘레만 만든다). 가로 스크롤이라 바깥의 세로 스크롤과
/// 방향으로 갈린다 — 위아래로 끌면 바깥이, 옆으로 끌면 이 줄이 움직인다.
struct PhotoPager<Page: View>: View {
    let count: Int
    @Binding var index: Int
    @ViewBuilder let page: (Int) -> Page

    /// 스크롤이 멈춘 쪽. `index` 와 서로 따라간다.
    @State private var position: Int?

    init(count: Int, index: Binding<Int>, @ViewBuilder page: @escaping (Int) -> Page) {
        self.count = count
        _index = index
        self.page = page
        _position = State(initialValue: index.wrappedValue)
    }

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(Array(0 ..< count), id: \.self) { number in
                    page(number)
                        .containerRelativeFrame(.horizontal)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollIndicators(.hidden)
        .scrollPosition(id: $position)
        .onChange(of: position) { _, now in
            if let now, now != index {
                index = now
            }
        }
        .onChange(of: index) { _, now in
            // 밖에서 바꿨다(크게 보기에서 넘기고 닫음, 사진이 줄어 당겨짐).
            if position != now {
                position = now
            }
        }
    }
}

/// 사진 크게 보기 — 검은 바탕에 한 장씩, 옆으로 넘긴다. 위에 「2 / 3」 과 닫기.
///
/// 리뷰 한 건의 사진(`ReviewPhotoThumbs`)과 사진첩(`GalleryViewer`)이 같이 쓴다. `index` 는 부른 화면의
/// 것이다 — 여기서 넘기면 부른 화면도 그 쪽에 가 있어, 닫으면 본 사진에 머문다.
struct PhotoViewer: View {
    let photos: [GalleryPhoto]
    /// 전체 수 — 아직 다 받지 않았으면 `photos.count` 보다 크다.
    let total: Int
    @Binding var index: Int
    /// 방문자 사진에 「방문자 사진」 을 적을 것인가. 리뷰 안에서 여는 사진에는 적지 않는다(전부 그 리뷰의 사진이다).
    var marksVisitors = false
    /// 방문자 사진에서 「리뷰 보기」 를 눌렀다 — 그 리뷰로 간다(티켓 §4). 없으면 단추가 없다.
    var onReview: ((Int64) -> Void)?

    @Environment(\.dismiss) private var dismiss

    private var current: GalleryPhoto? {
        photos.indices.contains(index) ? photos[index] : nil
    }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            PhotoPager(count: photos.count, index: $index) { number in
                GalleryImage(
                    photo: photos[number], fits: true,
                    active: PhotoGalleryRules.loads(page: number, current: index)
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityAddTraits(.isImage)
                .accessibilityLabel(
                    PhotoGalleryRules.spoken(
                        index: number, total: total, isReview: marksVisitors && photos[number].isReview
                    )
                )
            }
            topBar
        }
        .overlay(alignment: .bottom) { caption }
        // 보던 사진이 사라졌다(리뷰를 지웠다) — 빈 화면을 남기지 않는다.
        .onChange(of: photos.isEmpty, initial: true) { _, empty in
            if empty {
                dismiss()
            }
        }
    }

    private var topBar: some View {
        HStack {
            Text(verbatim: PhotoGalleryRules.position(index: index, total: total))
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(.white)
                .padding(.horizontal, 12).padding(.vertical, 6)
                .background(Capsule().fill(.black.opacity(0.45)))
                .accessibilityHidden(true)
            Spacer()
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(.black.opacity(0.45)))
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr("닫기"))
        }
        .padding(.horizontal, 12).padding(.top, 4)
    }

    /// 아래 한 줄 — 방문자 사진 표시와 「리뷰 보기」, 우리 사진의 출처 표기.
    @ViewBuilder private var caption: some View {
        if let current {
            let visitor = marksVisitors && current.isReview
            if visitor || current.credit != nil {
                HStack(spacing: 10) {
                    if visitor {
                        VisitorBadge()
                    }
                    if let credit = current.credit {
                        Text(verbatim: credit)
                            .font(.caption2).foregroundStyle(.white.opacity(0.75)).lineLimit(2)
                    }
                    Spacer(minLength: 8)
                    if visitor, let onReview, let reviewId = current.reviewId {
                        Button {
                            onReview(reviewId)
                        } label: {
                            HStack(spacing: 4) {
                                Text(tr("리뷰 보기", at: "사진"))
                                Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold))
                            }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .frame(height: 44)
                            .background(Capsule().fill(.white.opacity(0.2)))
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 14).padding(.bottom, 10)
            }
        }
    }
}

/// 「방문자 사진」 — 리뷰어가 올린 사진에 붙는 작은 표시(티켓 §4).
struct VisitorBadge: View {
    /// 작은 칸(격자)에서는 글 없이 그림만.
    var compact = false

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "person.crop.square")
            if !compact {
                Text("방문자 사진")
            }
        }
        .font(.caption2.weight(.semibold))
        .foregroundStyle(.white)
        .padding(.horizontal, compact ? 5 : 8).padding(.vertical, 4)
        .background(Capsule().fill(.black.opacity(0.55)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tr("방문자 사진"))
    }
}
