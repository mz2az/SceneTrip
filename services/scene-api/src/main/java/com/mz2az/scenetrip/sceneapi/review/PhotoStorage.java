package com.mz2az.scenetrip.sceneapi.review;

import java.net.URI;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 사용자 사진 저장소(계획 {@code review.md} §6·§12) — 사진은 앱이 저장소에 바로 올리고, 서버는 키만 다룬다.
 *
 * <p>버킷({@code scenetrip-user-media-*})과 올리기 창구({@code POST /uploads})가 붙기 전에는 {@link
 * NoPhotoStorage} 가 이 자리를 채운다 — 새 사진은 하나도 받지 않으므로 리뷰는 사진 없이만 쓰인다.
 */
public interface PhotoStorage {

  /** 보여 줄 주소 — 한 시간짜리 서명된 주소(계약: 앱은 저장하지 않는다). */
  URI viewUrl(String storageKey);

  /** {@code keys} 중 이 사용자가 올려 아직 어디에도 붙지 않은 것. 리뷰가 새로 붙여도 되는 키다. */
  Set<String> unattachedUploads(UUID user, Collection<String> keys);
}
