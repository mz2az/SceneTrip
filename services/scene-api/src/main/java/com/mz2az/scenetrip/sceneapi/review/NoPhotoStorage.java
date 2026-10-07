package com.mz2az.scenetrip.sceneapi.review;

import java.net.URI;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 저장소가 아직 없을 때의 자리 — 올린 사진이 없으므로 새 키는 하나도 받지 않는다.
 *
 * <p>S3 저장소가 붙으면 이 클래스를 지우고 그 구현으로 바꾼다. 사진이 있는 리뷰가 생길 길이 없어 {@link #viewUrl} 은 불릴 일이 없다. 불렸다면 저장소
 * 없이 사진이 들어간 것이라 크게 실패한다.
 */
@Component
class NoPhotoStorage implements PhotoStorage {

  @Override
  public URI viewUrl(String storageKey) {
    throw new IllegalStateException("사진 저장소가 설정되지 않았는데 리뷰 사진이 있습니다: " + storageKey);
  }

  @Override
  public Set<String> unattachedUploads(UUID user, Collection<String> keys) {
    return Set.of();
  }
}
