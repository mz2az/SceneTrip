import SwiftUI

/// 만료되는 후기 사진 주소는 디스크에 저장하지 않는다. 화면 크기만큼 풀어 그린다.
struct PostRemotePhoto: View {
    let url: String
    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0

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
                Button { attempt += 1 } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel(tr("사진 다시 받기"))
            } else {
                ProgressView()
            }
        }
        .clipped()
        .task(id: "\(url)-\(attempt)") { await load() }
    }

    private func load() async {
        image = nil
        failed = false
        guard let address = URL(string: url), let scheme = address.scheme,
              ["http", "https"].contains(scheme), address.host != nil
        else {
            failed = true
            return
        }
        do {
            let (data, response) = try await Self.session.data(from: address)
            guard let response = response as? HTTPURLResponse, (200 ..< 300).contains(response.statusCode),
                  data.count <= PhotoUploadRules.maxBytes else { failed = true; return }
            let picture = await Task.detached {
                PhotoShrink.image(from: data, longest: PhotoUploadRules.longestSide)
            }.value
            guard !Task.isCancelled else { return }
            image = picture
            failed = picture == nil
        } catch {
            if !Task.isCancelled {
                failed = true
            }
        }
    }
}

struct CommunityCoverPhoto: View {
    let post: CommunityPost

    var body: some View {
        if let url = post.remotePhotos?.first {
            PostRemotePhoto(url: url)
        } else if let name = post.photos?.first, let photo = CommunityStore.photo(name) {
            Color.clear.overlay { Image(uiImage: photo).resizable().scaledToFill() }.clipped()
        }
    }
}
