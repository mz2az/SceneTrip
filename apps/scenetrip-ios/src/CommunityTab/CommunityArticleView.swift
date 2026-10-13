import SceneApiClient
import SwiftUI

enum CommunityPhotoSource {
    case remote(String)
    case local(String)
    case preview(UIImage?)
    case pending
    case failed

    @MainActor init(slot: ReviewPhotoDraft.Slot) {
        if let url = slot.remoteUrl {
            self = .remote(url)
        } else if let image = slot.preview {
            self = .preview(image)
        } else if slot.state == .waiting || slot.state == .uploading {
            self = .pending
        } else {
            self = .failed
        }
    }

    var retryable: Bool {
        switch self {
        case .remote, .local: true
        default: false
        }
    }

    var identity: String {
        switch self {
        case let .remote(url): "remote:\(url)"
        case let .local(name): "local:\(name)"
        case let .preview(image): "preview:\(image.map(ObjectIdentifier.init).map(String.init(describing:)) ?? "waiting")"
        case .pending: "pending"
        case .failed: "failed"
        }
    }
}

struct CommunityArticleView: View {
    let blocks: [TripPostBlock]
    let photos: [CommunityPhotoSource]

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 22) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { offset, block in
                if block.kind == .text, let text = block.text {
                    Text(verbatim: text).font(.body).lineSpacing(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("community-paragraph-\(offset)")
                } else if block.kind == .photo, let index = block.photoIndex, photos.indices.contains(index) {
                    CommunityArticlePhoto(source: photos[index], block: block)
                        .accessibilityIdentifier("community-body-photo-\(index)")
                }
            }
        }
    }
}

/// 원래 비율로 보여 주고, 같은 메모리 사진을 확대 화면으로 넘긴다. 서명 URL은 디스크에 저장하지 않는다.
struct CommunityArticlePhoto: View {
    let source: CommunityPhotoSource
    let block: TripPostBlock
    var representative = false

    @State private var image: UIImage?
    @State private var failed = false
    @State private var attempt = 0
    @State private var expanded = false

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 30
        return URLSession(configuration: configuration)
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            canvas
            if let caption = block.caption, !caption.isEmpty {
                Text(verbatim: caption).font(.caption).foregroundStyle(.secondary)
            }
            if block.aiGenerated == true {
                Label(tr("AI 변환 이미지"), systemImage: "sparkles")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            if let credit = block.credit, !credit.isEmpty {
                Text(verbatim: credit).font(.caption2).foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
        .task(id: "\(source.identity)-\(attempt)") { await load() }
        .fullScreenCover(isPresented: $expanded) {
            if let image {
                CommunityPhotoViewer(image: image, block: block)
            }
        }
    }

    @ViewBuilder private var canvas: some View {
        if let image {
            Image(uiImage: image).resizable().scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: representative ? 220 : nil)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(alignment: .bottomTrailing) {
                    Button { expanded = true } label: {
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .foregroundStyle(.white).frame(width: 44, height: 44)
                            .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
                    }.buttonStyle(.plain).padding(6).accessibilityLabel(tr("사진 크게 보기"))
                }
                .onTapGesture { expanded = true }
                .accessibilityLabel(block.caption ?? tr("후기 사진"))
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { expanded = true }
        } else {
            Color(.secondarySystemBackground).aspectRatio(1.5, contentMode: .fit)
                .frame(maxHeight: representative ? 220 : nil)
                .overlay {
                    if failed {
                        VStack(spacing: 8) {
                            Text(source.retryable ? tr("사진을 불러오지 못했어요") : tr("사진을 다시 골라 주세요"))
                                .font(.caption).foregroundStyle(.secondary)
                            if source.retryable {
                                Button("사진 다시 받기") { attempt += 1 }.frame(minHeight: 44)
                            }
                        }
                    } else {
                        ProgressView().accessibilityLabel(tr("사진을 불러오는 중"))
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }

    private func load() async {
        image = nil; failed = false
        switch source {
        case let .local(name):
            image = CommunityStore.photo(name)
        case let .preview(preview):
            image = preview
            failed = preview == nil
            return
        case .pending: return
        case .failed:
            failed = true
            return
        case let .remote(url):
            guard let address = URL(string: url), ["http", "https"].contains(address.scheme), address.host != nil else {
                failed = true; return
            }
            do {
                let (data, response) = try await Self.session.data(from: address)
                guard let response = response as? HTTPURLResponse, (200 ..< 300).contains(response.statusCode),
                      data.count <= PhotoUploadRules.maxBytes else { failed = true; return }
                let decoded = await Task.detached { PhotoShrink.image(from: data, longest: PhotoUploadRules.longestSide) }.value
                guard !Task.isCancelled else { return }
                image = decoded
            } catch {
                if !Task.isCancelled {
                    failed = true
                }
                return
            }
        }
        failed = image == nil
    }
}
