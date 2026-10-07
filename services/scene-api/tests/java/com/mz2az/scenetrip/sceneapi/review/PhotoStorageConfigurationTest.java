package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 저장소 고르기 — 설정 {@code scenetrip.media.bucket} 이 비면 「없음」(올리기 503), 있으면 S3. 망을 쓰지 않는다: 클라이언트를 만들기만 하고
 * 부르지 않는다.
 */
@DisplayName("PhotoStorageConfiguration — 버킷 설정으로 저장소를 고른다")
class PhotoStorageConfigurationTest {

  private final PhotoStorageConfiguration config = new PhotoStorageConfiguration();

  @Test
  @DisplayName("버킷이 비었거나 공백뿐이면 available() 이 false 인 저장소")
  void blankBucketIsUnavailable() {
    for (String bucket : new String[] {"", "   "}) {
      PhotoStorage storage = config.photoStorage(bucket, "ap-northeast-2");

      assertThat(storage.available()).isFalse();
      assertThat(storage).isNotInstanceOf(S3PhotoStorage.class);
    }
  }

  @Test
  @DisplayName("저장소가 없으면 올릴 주소·옮기기는 크게 실패한다(불릴 일이 없어야 한다)")
  void unavailableStorageRefusesWork() {
    PhotoStorage storage = config.photoStorage("", "ap-northeast-2");

    assertThatThrownBy(
            () ->
                storage.presignUpload("uploads/tmp/a.jpg", "image/jpeg", 1, Duration.ofMinutes(10)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> storage.move("uploads/tmp/a.jpg", "reviews/a.jpg"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("버킷 이름이 있으면 S3 저장소, available() 은 true")
  void bucketSelectsS3() {
    PhotoStorage storage =
        config.photoStorage(
            "scenetrip-user-media-000000000000-ap-northeast-2-dev", "ap-northeast-2");

    assertThat(storage).isInstanceOf(S3PhotoStorage.class);
    assertThat(storage.available()).isTrue();
  }
}
