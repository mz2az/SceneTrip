import ImageIO
import SwiftUI

/// 사진첩의 사진을 받아 작게 풀어 둔다 (MZ2AZ-363).
///
/// `RemoteImage`(`AsyncImage`)를 쓰지 않는 까닭이 둘이다.
///
/// 1. **열쇠가 주소가 아니다.** 리뷰 사진의 주소는 서명돼 있어 받을 때마다 다르다 — 주소로 기억하면 같은
///    사진을 다시 받고(깜빡임), 죽은 주소가 기억에 쌓인다. 여기서는 `GalleryPhoto.key` 로 기억한다.
///    서명된 주소는 디스크에도 남기지 않는다.
/// 2. **원본을 통째로 풀지 않는다.** 썸네일 주소가 없다 — 올 수 있는 것은 긴 변 2,048 의 원본이고
///    우리 사진은 3,000 을 넘기도 한다. 풀면 한 장이 수십 MB 라, 그릴 크기까지만 푼다(ImageIO).
enum PhotoLoader {
    /// 어느 크기로 풀 것인가(긴 변, 픽셀).
    enum Size: Int {
        /// 줄·격자의 작은 칸.
        case tile = 360
        /// 대표 사진 자리와 크게 보기 — 같은 것을 써서, 크게 볼 때 다시 받지 않는다.
        case page = 1600
    }

    private static let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        // 풀어 둔 픽셀의 합. 큰 사진 열 장쯤 — 넘으면 오래된 것부터 버린다.
        cache.totalCostLimit = 80 * 1024 * 1024
        return cache
    }()

    /// 서명된 주소를 디스크에 남기지 않는 세션.
    private static let unsaved: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    private static let flights = Flights()

    private static func name(_ photo: GalleryPhoto, _ size: Size) -> NSString {
        "\(size.rawValue)|\(photo.key)" as NSString
    }

    /// 이미 풀어 둔 것. 화면이 기다리지 않고 바로 그린다.
    static func cached(_ photo: GalleryPhoto, size: Size) -> UIImage? {
        memory.object(forKey: name(photo, size))
    }

    /// 받아서 푼다. 주소가 죽었거나(만료) 끊겼거나 그림이 아니면 nil.
    /// 같은 사진을 두 화면이 함께 찾으면 한 번만 받는다.
    static func load(_ photo: GalleryPhoto, size: Size) async -> UIImage? {
        if let hit = cached(photo, size: size) {
            return hit
        }
        let name = name(photo, size)
        let image = await flights.run(name as String) { await fetch(photo, size: size) }
        if let image {
            memory.setObject(image, forKey: name, cost: Int(image.size.width * image.size.height * 4))
        }
        return image
    }

    private static func fetch(_ photo: GalleryPhoto, size: Size) async -> UIImage? {
        guard let url = URL(string: photo.url) else { return nil }
        let session = PhotoGalleryRules.signed(photo) ? unsaved : URLSession.shared
        guard let (data, response) = try? await session.data(from: url),
              (response as? HTTPURLResponse).map({ (200 ..< 300).contains($0.statusCode) }) ?? true
        else { return nil }
        return decode(data, longest: size.rawValue)
    }

    /// 긴 변 `longest` 까지만 푼다. 세로로 찍은 사진은 바로 선다. 작은 사진은 키우지 않는다.
    static func decode(_ data: Data, longest: Int) -> UIImage? {
        let lazy = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, lazy) else { return nil }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, longest),
            kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary
        let index = CGImageSourceGetPrimaryImageIndex(source)
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, index, options) else { return nil }
        return UIImage(cgImage: image)
    }

    /// 같은 사진을 받는 일을 하나로 묶는다.
    private actor Flights {
        private var running: [String: Task<UIImage?, Never>] = [:]

        func run(_ name: String, _ work: @escaping @Sendable () async -> UIImage?) async -> UIImage? {
            if let task = running[name] {
                return await task.value
            }
            let task = Task { await work() }
            running[name] = task
            let image = await task.value
            running[name] = nil
            return image
        }
    }
}

/// 사진첩의 한 장을 그린다. `active` 가 아니면 받지 않는다(이미 풀어 둔 것은 그린다) —
/// 넘겨 보기에서 보이는 쪽과 양옆만 받게 한다.
struct GalleryImage: View {
    let photo: GalleryPhoto
    var size: PhotoLoader.Size = .page
    /// 잘리지 않게 안에 맞춘다(크게 보기). 아니면 칸을 채우고 넘치는 곳을 자른다.
    var fits = false
    /// 크게 보기의 검은 바탕. 대표·카드의 밝은 바탕에는 중립색 진행·실패 표시를 쓴다.
    var dark = false
    var active = true

    @State private var image: UIImage?
    /// `image` 가 어느 사진의 것인가 — 칸이 다른 사진으로 바뀌면 앞 사진을 남기지 않는다.
    @State private var shown: String?
    /// 받지 못한 주소. 같은 주소로는 다시 묻지 않는다 — 새 주소가 오면(다시 받음) 다시 해 본다.
    @State private var failedUrl: String?
    /// 「다시 시도」 를 누른 횟수 — 오르면 같은 주소로도 다시 받는다.
    @State private var attempt = 0
    /// 주소를 새로 받아 달라고 이미 청한 사진 — 한 사진에 한 번만 저절로 청한다.
    @State private var renewedFor: String?
    /// 이 사진이 든 사진첩의 주소를 새로 받는 길(`PhotoGallery.renew`). 사진첩 밖(리뷰 한 건의 사진)에는 없다.
    @Environment(\.photoRenew) private var renew

    private struct Work: Equatable {
        let key: String
        let active: Bool
        /// 실패했을 때만 주소를 본다. 성공한 사진은 주소가 바뀌어도 다시 받지 않는다.
        let retry: String?
        let attempt: Int
    }

    var body: some View {
        // `RemoteImage` 와 같은 까닭 — 빈 색이 제안된 크기를 받고, 그 위에서 채운 뒤 자른다.
        Color.clear
            .overlay { content }
            .clipped()
            .task(id: work) { await load() }
    }

    @ViewBuilder private var content: some View {
        if let image = current {
            if fits {
                Image(uiImage: image).resizable().scaledToFit()
            } else {
                Image(uiImage: image).resizable().scaledToFill()
            }
        } else if failed {
            failure
        } else if fits {
            ProgressView().tint(dark ? .white : .secondary)
        } else {
            Color(.systemGray5).overlay(ProgressView().scaleEffect(0.6))
        }
    }

    /// 이 칸의 사진 — 방금 받은 것, 없으면 풀어 둔 것.
    private var work: Work {
        Work(key: photo.key, active: active, retry: failedUrl == nil ? nil : photo.url, attempt: attempt)
    }

    private var failed: Bool {
        shown == photo.key && failedUrl != nil
    }

    private var current: UIImage? {
        shown == photo.key ? image : PhotoLoader.cached(photo, size: size)
    }

    /// 못 받았다 — 누르면 다시 받는다. 이 칸이 단추 안에 있어도(누르면 크게 보기) 여기를 누른 것은 다시 받기다.
    private var failure: some View {
        Group {
            if fits, size == .page {
                // 주소가 만료됐거나(한 시간) 끊겼다.
                VStack(spacing: 10) {
                    Image(systemName: "photo").font(.system(size: 30))
                    Text("사진을 불러오지 못했어요").font(.footnote)
                    Label(tr("다시 시도"), systemImage: "arrow.clockwise")
                        .font(.footnote.weight(.semibold))
                        .padding(.horizontal, 14)
                        .frame(height: 44)
                        .background(Capsule().fill(dark ? Color.white.opacity(0.2) : Color(.systemGray5)))
                }
                .foregroundStyle(dark ? Color.white.opacity(0.8) : Color.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Color(.systemGray5).overlay(
                    Image(systemName: "arrow.clockwise").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                )
            }
        }
        .contentShape(.rect)
        .onTapGesture { retry() }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel("\(tr("사진을 불러오지 못했어요")), \(tr("다시 시도"))")
    }

    /// 사람이 「다시 시도」 를 눌렀다. 서명된 주소면 새 주소부터 받는다 — 죽은 주소로는 몇 번을 해도 같다.
    private func retry() {
        failedUrl = nil
        attempt += 1
        if PhotoGalleryRules.signed(photo) {
            renew?(true)
        }
    }

    private func load() async {
        if shown != photo.key {
            image = PhotoLoader.cached(photo, size: size)
            shown = photo.key
            failedUrl = nil
        }
        guard image == nil, active, failedUrl != photo.url else { return }
        let loaded = await PhotoLoader.load(photo, size: size)
        guard !Task.isCancelled else { return }
        image = loaded
        failedUrl = loaded == nil ? photo.url : nil
        guard loaded == nil, let renew,
              PhotoGalleryRules.renewsAfterFailure(
                  signed: PhotoGalleryRules.signed(photo), alreadyRenewed: renewedFor == photo.key
              )
        else { return }
        // 주소가 죽었을 수 있다 — 사진첩을 다시 받으면 새 주소가 오고, 주소가 바뀌면 이 칸이 다시 받는다.
        renewedFor = photo.key
        renew(false)
    }
}

/// 사진첩의 주소를 새로 받는 길을 그 안의 사진들에게 내려 준다. `forced` 는 사람이 누른 것.
private struct PhotoRenewKey: EnvironmentKey {
    static let defaultValue: ((Bool) -> Void)? = nil
}

extension EnvironmentValues {
    var photoRenew: ((Bool) -> Void)? {
        get { self[PhotoRenewKey.self] }
        set { self[PhotoRenewKey.self] = newValue }
    }
}

extension View {
    /// 이 안의 사진들은 못 받으면 이 사진첩을 다시 받아 새 주소로 해 본다.
    func renewsPhotos(from gallery: PhotoGallery) -> some View {
        environment(\.photoRenew) { [weak gallery] forced in gallery?.renew(forced: forced) }
    }
}
