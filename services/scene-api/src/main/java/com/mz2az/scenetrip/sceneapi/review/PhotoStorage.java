package com.mz2az.scenetrip.sceneapi.review;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 사용자 사진 저장소(계획 {@code review.md} §6·§12·§13) — 사진은 앱이 저장소에 바로 올리고, 서버는 키만 다룬다.
 *
 * <p>키는 둘로 나뉜다. {@code uploads/tmp/…} 는 막 올린 것으로 버킷 수명 규칙이 하루 뒤 지운다. 리뷰에 붙는 순간 {@link #move} 로
 * {@code reviews/…} 에 옮긴다 — 그래야 붙인 사진이 수명 규칙에 지워지지 않는다.
 *
 * <p>버킷이 설정되지 않은 환경(설정 {@code scenetrip.media.bucket} 이 비어 있음)에서는 {@link NoPhotoStorage} 가 이 자리를
 * 채운다 — 올리기 창구는 503 이고 리뷰는 사진 없이만 쓰인다.
 */
public interface PhotoStorage {

  /** 이 환경에 저장소가 있는가. 없으면 올리기 창구가 503 이다. */
  boolean available();

  /** 보여 줄 주소 — 한 시간짜리 서명된 주소(계약: 앱은 저장하지 않는다). */
  URI viewUrl(String storageKey);

  /**
   * 올릴 주소 — {@code expiresIn} 동안 쓸 수 있는 서명된 PUT 주소. 형식과 크기를 서명에 넣어, 다른 형식이나 다른 크기의 파일은 저장소가 거절한다.
   */
  PresignedUpload presignUpload(
      String storageKey, String contentType, long bytes, Duration expiresIn);

  /** 옮긴다(복사 뒤 원본 삭제). 원본이 없으면 {@link MissingPhotoException}. */
  void move(String fromKey, String toKey);

  /**
   * 지운다(MZ2AZ-406). 없는 키여도 성공이다 — 같은 키를 두 번 지워도 된다.
   *
   * <p>저장소가 실패하면 <b>던진다</b>. {@link #move} 안의 원본 삭제와 달리 삼키지 않는다 — {@code uploads/tmp/} 는 수명 규칙이 하루
   * 뒤 지워 주지만 {@code posts/} 에는 그런 규칙이 없어, 삼키면 파일이 영영 남는다. 부른 쪽이 그 키를 지울 목록에 남긴다.
   */
  void delete(String storageKey);

  /**
   * {@code prefix} 아래 파일을 쪽마다 넘긴다(MZ2AZ-448, 주간 고아 청소). S3 는 한 쪽에 1,000 개까지다 — 목록을 한꺼번에 메모리에 올리지
   * 않는다. 저장소가 실패하면 던진다.
   */
  void listPages(String prefix, Consumer<List<StoredObject>> onPage);

  /** 저장소에 있는 파일 하나 — 키와 마지막으로 바뀐 때. */
  record StoredObject(String key, Instant lastModified) {}

  /** 서명된 올릴 주소와, PUT 에 그대로 붙여야 하는 헤더. */
  record PresignedUpload(URI url, Map<String, String> requiredHeaders, Instant expiresAt) {}

  /** 저장소에 그 키의 파일이 없다 — 받은 주소로 올리지 않았거나 이미 지워졌다. */
  final class MissingPhotoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MissingPhotoException(String key) {
      super("저장소에 사진이 없습니다: " + key);
    }
  }
}
