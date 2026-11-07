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

    var objectKey: String {
        if case let .remote(url) = self {
            return "remote:\(PhotoGalleryRules.unsigned(url))"
        }
        return identity
    }
}

struct CommunityArticleView: View {
    let blocks: [TripPostBlock]
    let photos: [CommunityPhotoSource]
    /// 글 끝의 「References」·「Photo credits」 를 한 줄 뒤로 접는다 — 읽는 화면만 켠다(작성 미리보기는 다 보인다).
    /// 길고 주소가 섞여 본문 끝이 어지러웠다(MZ2AZ-528, Android·웹 `splitReferences` 와 같은 규칙).
    var foldReferences = false

    var body: some View {
        let parts = foldReferences ? CommunityArticle.splitReferences(blocks) : (body: blocks, references: [])
        LazyVStack(alignment: .leading, spacing: 22) {
            ForEach(Array(parts.body.enumerated()), id: \.offset) { offset, block in
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
            if !parts.references.isEmpty {
                CommunityReferencesFold(blocks: parts.references)
            }
        }
    }
}

/// 접힌 덧붙임 구간 — 줄 이름은 글의 제목 줄을 그대로 쓴다(글 언어를 따른다). 누르면 펼치고 다시 누르면 접는다.
/// 모양은 코스 편집기의 접힌 알림줄(`RouteEditorView.draftNotes`)과 같은 결 — 보조 글자색 + 위·아래 꺾쇠.
struct CommunityReferencesFold: View {
    let blocks: [TripPostBlock]
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { open.toggle() }
            } label: {
                HStack(spacing: 4) {
                    Text(verbatim: CommunityArticle.referencesLabel(blocks)).font(.caption)
                    Image(systemName: open ? "chevron.up" : "chevron.down").font(.caption2.weight(.semibold))
                }
                .foregroundStyle(.secondary)
                .padding(.vertical, 6)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityValue(tr(CommunityArticle.foldStateKey(open: open)))
            .accessibilityIdentifier("community-references-toggle")
            if open {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { offset, block in
                        if let text = block.text {
                            Text(verbatim: text).font(.caption).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                                .accessibilityIdentifier("community-reference-\(offset)")
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 사진 아래 AI 변환 표시와 출처. **AI 변환 표시는 고지라 늘 보이고**, 출처 문장은 「더 보기」 뒤로 접는다(MZ2AZ-528).
/// 확대 화면(`CommunityPhotoViewer`)의 출처는 그대로 다 보인다.
struct CommunityPhotoNoticeView: View {
    let notice: CommunityPhotoNotice
    @State private var open = false

    var body: some View {
        if !notice.isEmpty {
            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 0) {
                    if notice.aiGenerated {
                        Label(tr("AI 변환 이미지"), systemImage: "sparkles")
                            .font(.caption2).foregroundStyle(.secondary)
                        Spacer(minLength: 8)
                    }
                    if notice.foldsCredit {
                        Button {
                            withAnimation(.easeInOut(duration: 0.2)) { open.toggle() }
                        } label: {
                            HStack(spacing: 2) {
                                Text(tr("더 보기")).font(.caption2)
                                Image(systemName: open ? "chevron.up" : "chevron.down")
                                    .font(.system(size: 9, weight: .semibold))
                            }
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 6)
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .accessibilityValue(tr(CommunityArticle.foldStateKey(open: open)))
                        .accessibilityIdentifier("community-photo-credit-toggle")
                    }
                }
                if let credit = notice.shownCredit(open: open) {
                    Text(verbatim: credit).font(.caption2).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("community-photo-credit")
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
    @State private var shown: String?
    @State private var failedIdentity: String?
    @State private var lastAttempt = -1
    @State private var renewedFor: String?
    @Environment(\.photoRenew) private var renew

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
            CommunityPhotoNoticeView(notice: CommunityPhotoNotice(block: block))
        }
        .task(id: "\(source.objectKey)-\(failed ? source.identity : "")-\(attempt)") { await load() }
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
                                Button("사진 다시 받기") { retry() }.frame(minHeight: 44)
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
        if shown != source.objectKey {
            shown = source.objectKey
            image = nil
            failed = false
            failedIdentity = nil
        }
        guard image == nil, failedIdentity != source.identity || lastAttempt != attempt else { return }
        lastAttempt = attempt
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
            await loadRemote(url)
            return
        }
        if image == nil {
            reportFailure()
        } else {
            failed = false; failedIdentity = nil
        }
    }

    private func loadRemote(_ url: String) async {
        guard let address = URL(string: url), ["http", "https"].contains(address.scheme), address.host != nil else {
            reportFailure(); return
        }
        do {
            let (data, response) = try await Self.session.data(from: address)
            guard let response = response as? HTTPURLResponse, (200 ..< 300).contains(response.statusCode),
                  data.count <= PhotoUploadRules.maxBytes else { reportFailure(); return }
            let decoded = await Task.detached { PhotoShrink.image(from: data, longest: PhotoUploadRules.longestSide) }.value
            guard !Task.isCancelled else { return }
            image = decoded
            if decoded == nil {
                reportFailure()
            } else {
                failed = false; failedIdentity = nil
            }
        } catch {
            if !Task.isCancelled {
                reportFailure()
            }
        }
    }

    private func retry() {
        failed = false
        failedIdentity = nil
        attempt += 1
        if case .remote = source {
            renew?(true)
        }
    }

    private func reportFailure() {
        guard !Task.isCancelled else { return }
        failed = true
        failedIdentity = source.identity
        guard case .remote = source, let renew, renewedFor != source.objectKey else { return }
        renewedFor = source.objectKey
        renew(false)
    }
}
