import Foundation
import SceneApiClient
import UIKit

/// 사진 한 장을 올리지 못한 이유 (MZ2AZ-363). 서버 문장이 아니라 `code` 로 가른다(MZ2AZ-345).
enum PhotoUploadFailure: Error, Equatable {
    /// 고른 파일을 그림으로 읽지 못했다(깨진 파일, 아이클라우드에서 못 받음).
    case unreadable
    /// 줄여도 10 MB 를 넘는다 — `UPLOAD_TOO_LARGE`.
    case tooLarge
    /// `UPLOAD_TYPE_UNSUPPORTED`. 앱은 늘 JPEG 로 올리므로 보통은 볼 일이 없다.
    case unsupportedType
    /// `429 RATE_LIMITED` — 분당 요청 상한.
    case rateLimited
    /// `503 UPLOAD_UNAVAILABLE` — 이 서버에 사진 저장소가 없다. 사진 없이는 쓸 수 있다.
    case unavailable
    /// 세션이 풀렸다 — 로그인 화면이 위로 올라온다.
    case signedOut
    /// 리뷰 저장 때 서버가 이 키를 거절했다(`REVIEW_PHOTO_INVALID`) — 올린 지 하루가 지났거나 파일이 없다.
    case expired
    /// 저장소가 PUT 을 거절했다(서명 만료·크기 불일치 등).
    case storage
    /// 네트워크, 그 밖의 서버 오류.
    case network
}

/// 올리기의 순수 규칙 — 한도, 오류 가르기, 저장소로 보낼 요청.
enum PhotoUploadRules {
    /// 긴 변(티켓 「올리기 전에 긴 변 2,048px 로 줄이기」).
    static let longestSide: CGFloat = 2048
    /// 한 장의 상한(티켓 「10MB」, 서버 `UploadsController.MAX_BYTES`).
    static let maxBytes = 10 * 1024 * 1024
    /// **언제나 JPEG 로 올린다.** 서버는 PNG·HEIC·WebP 도 받지만, 다시 그려야 EXIF 가 빠지고
    /// HEIC 를 그대로 올리면 Android·웹이 못 읽을 수 있다.
    static let contentType = "image/jpeg"

    /// `POST /uploads` 가 실패한 이유.
    static func failure(status: Int, code: String?) -> PhotoUploadFailure {
        switch code {
        case "UPLOAD_TOO_LARGE": return .tooLarge
        case "UPLOAD_TYPE_UNSUPPORTED": return .unsupportedType
        case "UPLOAD_UNAVAILABLE": return .unavailable
        case "RATE_LIMITED": return .rateLimited
        default: break
        }
        switch status {
        case 401: return .signedOut
        case 429: return .rateLimited
        default: return .network
        }
    }

    /// 같은 사진으로 다시 해 볼 만한가. 너무 크거나 못 읽는 사진은 다시 해도 같다 — 빼는 길만 준다.
    static func retryable(_ failure: PhotoUploadFailure) -> Bool {
        switch failure {
        case .unreadable, .tooLarge, .unsupportedType: false
        case .rateLimited, .unavailable, .signedOut, .expired, .storage, .network: true
        }
    }

    /// 이 실패 뒤에 **줄 선 사진을 멈추는가.** 한도·저장소 없음·네트워크·세션은 다음 사진도 똑같이 실패한다 —
    /// 연달아 실패시키지 않고 멈춰 두었다가 「다시 시도」 에서 잇는다. 사진 자체의 문제는 그 칸만의 일이다.
    static func stopsQueue(_ failure: PhotoUploadFailure) -> Bool {
        switch failure {
        case .rateLimited, .unavailable, .signedOut, .storage, .network: true
        case .unreadable, .tooLarge, .unsupportedType, .expired: false
        }
    }

    /// 사용자에게 보일 한 줄.
    static func text(_ failure: PhotoUploadFailure) -> String {
        switch failure {
        case .unreadable: tr("사진을 읽지 못했어요. 다른 사진을 골라 주세요")
        case .tooLarge: tr("사진이 너무 커요(10MB 까지). 다른 사진을 골라 주세요")
        case .unsupportedType: tr("올릴 수 없는 사진 형식이에요")
        case .rateLimited: tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")
        case .unavailable: tr("지금은 사진을 올릴 수 없어요. 사진을 빼면 저장할 수 있어요")
        case .signedOut: tr("로그인이 풀렸어요. 다시 로그인해 주세요")
        case .expired: tr("사진을 다시 올려 주세요")
        case .storage, .network: tr("사진을 올리지 못했어요. 다시 시도해 주세요")
        }
    }

    /// 저장소(S3·MinIO)로 보낼 PUT. **우리 서버가 아니다** — `Authorization` 을 붙이지 않고,
    /// 헤더는 서버가 준 `requiredHeaders` 를 그대로 싣는다(서명에 들어간 것이라 빠지거나 다르면 거절된다).
    static func putRequest(uploadUrl: String, requiredHeaders: [String: String]) -> URLRequest? {
        guard let url = URL(string: uploadUrl), url.scheme != nil, url.host != nil else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        for (name, value) in requiredHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }
        return request
    }
}

/// 사진 한 장 올리기 (MZ2AZ-363): `POST /uploads` 로 서명된 주소를 받아 → 저장소로 바로 PUT → `key`.
///
/// 주소를 받는 것은 생성된 클라이언트(`UploadsAPI`)가 한다 — 토큰·만료 갱신이 거기 걸려 있다.
/// PUT 은 우리 API 가 아니라 저장소로 가므로 `URLSession` 으로 직접 보낸다.
/// `purpose` 를 받는 것은 여행후기 서버(MZ2AZ-352)가 생기면 같은 창구를 쓰기 때문이다.
enum PhotoUploader {
    /// 올릴 JPEG 를 고른 파일에서 만든다 — 긴 변까지만 풀어 다시 그린다(EXIF·위치가 빠진다).
    /// 그림이 아니면 `unreadable`, 품질을 낮춰도 10 MB 를 넘으면 `tooLarge`.
    static func prepare(_ file: Data) -> Result<Data, PhotoUploadFailure> {
        guard let drawn = PhotoShrink.image(from: file, longest: PhotoUploadRules.longestSide) else {
            return .failure(.unreadable)
        }
        guard let data = PhotoShrink.encode(drawn, maxBytes: PhotoUploadRules.maxBytes) else {
            return .failure(.tooLarge)
        }
        return .success(data)
    }

    /// 올리고 `key` 를 돌려준다.
    static func upload(
        _ data: Data, purpose: UploadPurpose = .review, session: URLSession = .shared
    ) async -> Result<String, PhotoUploadFailure> {
        let ticket: UploadTicket
        do {
            ticket = try await UploadsAPI.createUpload(uploadCreate: UploadCreate(
                purpose: purpose, contentType: PhotoUploadRules.contentType, bytes: Int64(data.count)
            ))
        } catch let ErrorResponse.error(status, body, _, _) {
            return .failure(PhotoUploadRules.failure(status: status, code: AuthRules.apiCode(from: body)))
        } catch {
            return .failure(.network)
        }
        guard let request = PhotoUploadRules.putRequest(
            uploadUrl: ticket.uploadUrl, requiredHeaders: ticket.requiredHeaders
        ) else {
            return .failure(.storage)
        }
        do {
            let (_, response) = try await session.upload(for: request, from: data)
            guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                return .failure(.storage)
            }
            return .success(ticket.key)
        } catch {
            return .failure(.network)
        }
    }
}
