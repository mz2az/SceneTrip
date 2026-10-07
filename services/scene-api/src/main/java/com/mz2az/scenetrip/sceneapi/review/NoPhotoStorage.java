package com.mz2az.scenetrip.sceneapi.review;

import java.net.URI;
import java.time.Duration;

/**
 * 저장소가 설정되지 않은 환경의 자리 — 올리기 창구는 {@link #available()} 을 보고 503 을 낸다.
 *
 * <p>올린 사진이 없으므로 사진이 있는 리뷰가 생길 길이 없어 나머지는 불릴 일이 없다. 불렸다면 저장소 없이 사진이 들어간 것이라 크게 실패한다.
 */
final class NoPhotoStorage implements PhotoStorage {

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public URI viewUrl(String storageKey) {
    throw new IllegalStateException("사진 저장소가 설정되지 않았는데 리뷰 사진이 있습니다: " + storageKey);
  }

  @Override
  public PresignedUpload presignUpload(
      String storageKey, String contentType, long bytes, Duration expiresIn) {
    throw new IllegalStateException("사진 저장소가 설정되지 않았습니다");
  }

  @Override
  public void move(String fromKey, String toKey) {
    throw new IllegalStateException("사진 저장소가 설정되지 않았습니다");
  }
}
