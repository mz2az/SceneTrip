import SwiftUI

/// 작품 포스터를 받아 기기에 30일 보관하고, 화면 크기로 줄여 푼다 (MZ2AZ-507, 계획 poster-disk-cache.md).
///
/// 포스터는 방송사·OTT 서버에 있어 우리가 줄일 수 없다 — 원본이 2MB 를 넘기도 한다(JTBC). `AsyncImage` 는 원본 크기로 풀고
/// 큰 응답은 시스템 캐시에 남기지 않아 홈을 열 때마다 다시 받았다. 순서는 메모리 → 기기 보관 → 네트워크.
/// 숫자와 지울 순서는 `PosterDiskRules`. **포스터만** 쓴다 — 우리 서버 사진은 서버 쪽에서 줄인다(사용자 결정).
enum PosterLoader {
    private static let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = 80 * 1024 * 1024
        return cache
    }()

    /// 기기 보관 폴더 — 앱 캐시 아래(시스템이 공간이 모자라면 비울 수 있다).
    private static let folder: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("posters", isDirectory: true)
    }()

    /// 시스템 캐시를 거치지 않는 세션 — 보관은 여기서 한다.
    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    /// 이미 풀어 둔 것 — 화면이 기다리지 않고 바로 그린다.
    static func cached(_ url: String) -> UIImage? {
        memory.object(forKey: url as NSString)
    }

    /// 받아서 푼다. 깨진 주소·오프라인·그림이 아니면 nil.
    static func load(_ url: String) async -> UIImage? {
        if let hit = cached(url) {
            return hit
        }
        let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            if let data = readDisk(url) {
                return PhotoLoader.decode(data, longest: PosterDiskRules.edge)
            }
            guard let data = await fetch(url) else { return nil }
            writeDisk(url, data)
            return PhotoLoader.decode(data, longest: PosterDiskRules.edge)
        }.value
        if let image {
            memory.setObject(image, forKey: url as NSString, cost: Int(image.size.width * image.size.height * 4))
        }
        return image
    }

    private static func fetch(_ url: String) async -> Data? {
        guard let parsed = URL(string: url),
              let (data, response) = try? await session.data(from: parsed),
              (response as? HTTPURLResponse).map({ (200 ..< 300).contains($0.statusCode) }) ?? false,
              data.count <= PosterDiskRules.maxDownloadBytes
        else { return nil }
        return data
    }

    /// 보관한 원본 — 30일이 지났으면 지우고 nil. 읽으면 「마지막으로 쓴 때」 를 지금으로.
    private static func readDisk(_ url: String) -> Data? {
        let file = folder.appendingPathComponent(PosterDiskRules.fileName(url: url))
        let manager = FileManager.default
        guard let attributes = try? manager.attributesOfItem(atPath: file.path),
              let savedAt = attributes[.creationDate] as? Date
        else { return nil }
        if PosterDiskRules.expired(savedAt: savedAt, now: Date()) {
            try? manager.removeItem(at: file)
            return nil
        }
        try? manager.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
        return try? Data(contentsOf: file)
    }

    /// 원본을 보관하고, 합계가 100MB 를 넘으면 오래 안 쓴 것부터 지운다.
    private static func writeDisk(_ url: String, _ data: Data) {
        let manager = FileManager.default
        try? manager.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(PosterDiskRules.fileName(url: url))
        guard (try? data.write(to: file, options: .atomic)) != nil else { return }
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let files = (try? manager.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys)) ?? []
        let entries = files.compactMap { item -> PosterDiskEntry? in
            guard let values = try? item.resourceValues(forKeys: Set(keys)) else { return nil }
            return PosterDiskEntry(
                name: item.lastPathComponent, bytes: values.fileSize ?? 0,
                lastUsed: values.contentModificationDate ?? .distantPast
            )
        }
        for name in PosterDiskRules.evictions(entries, maxBytes: PosterDiskRules.maxBytes) {
            try? manager.removeItem(at: folder.appendingPathComponent(name))
        }
    }
}

/// 작품 포스터 한 장 — 기기 보관·줄여 풀기(`PosterLoader`). 받기 전·실패는 `placeholder`.
struct PosterImage<Placeholder: View>: View {
    let url: String?
    var fits = false
    @ViewBuilder var placeholder: () -> Placeholder

    @State private var image: UIImage?
    @State private var shown: String?

    var body: some View {
        Color.clear
            .overlay {
                if let image, shown == url {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: fits ? .fit : .fill)
                } else {
                    placeholder()
                }
            }
            .clipped()
            .task(id: url) {
                guard let url, NewCoursePhoto.usable(url) else {
                    image = nil
                    return
                }
                if let hit = PosterLoader.cached(url) {
                    image = hit
                } else {
                    image = await PosterLoader.load(url)
                }
                shown = url
            }
    }
}
