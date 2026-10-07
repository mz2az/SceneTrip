package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.UploadsApi;
import com.mz2az.scenetrip.sceneapi.api.model.UploadCreate;
import com.mz2az.scenetrip.sceneapi.api.model.UploadTicket;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사진 올릴 주소(계약 {@code POST /uploads}, 계획 {@code review.md} §6·§13).
 *
 * <p>서버는 파일을 받지 않는다 — 10 분짜리 서명된 PUT 주소를 주고, 누가 어떤 키를 받았는지만 적는다. 형식과 크기는 서명에 들어가 다른 파일은 저장소가 거절한다.
 * 키는 {@code uploads/tmp/<uuid>.<확장자>} — 리뷰에 붙을 때 {@code reviews/} 로 옮겨진다.
 */
@RestController
class UploadsController implements UploadsApi {

  static final long MAX_BYTES = 10L * 1024 * 1024;
  static final Duration UPLOAD_TTL = Duration.ofMinutes(10);

  /** 받는 형식과 키의 확장자. */
  static final Map<String, String> TYPES =
      Map.of(
          "image/jpeg", "jpg",
          "image/png", "png",
          "image/heic", "heic",
          "image/webp", "webp");

  private final PhotoStorage storage;
  private final UploadStore uploads;
  private final CurrentAccount accounts;

  UploadsController(PhotoStorage storage, UploadStore uploads, CurrentAccount accounts) {
    this.storage = storage;
    this.uploads = uploads;
    this.accounts = accounts;
  }

  @Override
  public ResponseEntity<UploadTicket> createUpload(UploadCreate body) {
    UUID user = accounts.requireMember();
    String ext = body.getContentType() == null ? null : TYPES.get(body.getContentType());
    if (ext == null) {
      throw ApiException.badRequest("UPLOAD_TYPE_UNSUPPORTED", "JPEG·PNG·HEIC·WebP 만 올릴 수 있습니다");
    }
    long bytes = body.getBytes() == null ? 0 : body.getBytes();
    if (bytes < 1 || bytes > MAX_BYTES) {
      throw ApiException.badRequest("UPLOAD_TOO_LARGE", "사진은 1 바이트 이상 10 MB 이하여야 합니다");
    }
    if (!storage.available()) {
      throw ApiException.unavailable("UPLOAD_UNAVAILABLE", "이 서버에는 사진 저장소가 없습니다");
    }

    String key = "uploads/tmp/" + UUID.randomUUID() + "." + ext;
    PhotoStorage.PresignedUpload p =
        storage.presignUpload(key, body.getContentType(), bytes, UPLOAD_TTL);
    uploads.record(user, key, body.getPurpose().getValue(), body.getContentType(), bytes);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new UploadTicket(
                key, p.url(), p.requiredHeaders(), p.expiresAt().atOffset(ZoneOffset.UTC)));
  }
}
