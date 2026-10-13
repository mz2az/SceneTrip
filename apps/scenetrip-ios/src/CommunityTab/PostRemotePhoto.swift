import SwiftUI

/// 만료되는 후기 사진 주소는 디스크에 저장하지 않는다. 화면 크기만큼 풀어 그린다.
struct PostRemotePhoto: View {
    let url: String
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0
    @State private var shown: String?
    @State private var failedUrl: String?
    @State private var lastAttempt = -1
    @State private var renewedFor: String?
    @Environment(\.photoRenew) private var renew

    private var objectKey: String {
        PhotoGalleryRules.unsigned(url)
    }

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        return URLSession(configuration: configuration)
    }()

    var body: some View {
        Color(.systemGray5).overlay {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else if failed {
                Button { retry() } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel(tr("사진 다시 받기"))
            } else {
                ProgressView()
            }
        }
        .clipped()
        .task(id: "\(objectKey)-\(failed ? url : "")-\(attempt)") { await load() }
    }

    private func load() async {
        if shown != objectKey {
            shown = objectKey
            image = nil
            failed = false
            failedUrl = nil
        }
        guard image == nil, failedUrl != url || lastAttempt != attempt else { return }
        lastAttempt = attempt
        guard let address = URL(string: url), let scheme = address.scheme,
              ["http", "https"].contains(scheme), address.host != nil
        else {
            reportFailure()
            return
        }
        do {
            let (data, response) = try await Self.session.data(from: address)
            guard let response = response as? HTTPURLResponse, (200 ..< 300).contains(response.statusCode),
                  data.count <= PhotoUploadRules.maxBytes else { reportFailure(); return }
            let picture = await Task.detached {
                PhotoShrink.image(from: data, longest: PhotoUploadRules.longestSide)
            }.value
            guard !Task.isCancelled else { return }
            image = picture
            if picture == nil {
                reportFailure()
            } else {
                failed = false; failedUrl = nil
            }
        } catch {
            if !Task.isCancelled {
                reportFailure()
            }
        }
    }

    private func retry() {
        failed = false
        failedUrl = nil
        attempt += 1
        renew?(true)
    }

    private func reportFailure() {
        guard !Task.isCancelled else { return }
        failed = true
        failedUrl = url
        guard let renew, renewedFor != objectKey else { return }
        renewedFor = objectKey
        renew(false)
    }
}

struct CommunityCoverPhoto: View {
    let post: CommunityPost
    @StateObject private var refresh: CommunityPhotoRefresh

    init(post: CommunityPost) {
        self.post = post
        _refresh = StateObject(wrappedValue: CommunityPhotoRefresh(post: post))
    }

    var body: some View {
        if let url = (refresh.post ?? post).remotePhotos?.first {
            PostRemotePhoto(url: url)
                .renewsPhotos { forced in Task { await refresh.renew(forced: forced) } }
                .onChange(of: post.remotePhotos) { _, _ in refresh.show(post) }
                .onDisappear { refresh.cancelPending() }
        } else if let name = post.photos?.first, let photo = CommunityStore.photo(name) {
            Color.clear.overlay { Image(uiImage: photo).resizable().scaledToFill() }.clipped()
        }
    }
}
