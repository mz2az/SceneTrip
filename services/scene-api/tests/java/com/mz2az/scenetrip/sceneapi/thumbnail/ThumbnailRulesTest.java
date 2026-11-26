package com.mz2az.scenetrip.sceneapi.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 작은 사진 규칙 — 계획 {@code docs/project/plans/photo-thumbnails.md} §2-1·§2-2(MZ2AZ-561), ADR 0030.
 *
 * <p>명세에서 나온 기대: 크기는 하나 — 긴 변 720px, JPEG q0.80, 키우지 않는다(비율은 그대로). 키는 원래 자리 아래 {@code thumb-720/}:
 * 사용자 사진 {@code posts/thumb-720/<원래 이름>}·{@code reviews/thumb-720/<원래 이름>}, 촬영지 사진 {@code
 * place/thumb-720/<place/ 뒤 경로>}. 실패는 3 번까지만 다시 한다.
 */
@DisplayName("ThumbnailRules — 긴 변 720·키 이름·시도 한도")
class ThumbnailRulesTest {

  @Test
  @DisplayName("상수 — 긴 변 720, JPEG 품질 0.80, 시도 한도 3")
  void constants() {
    assertThat(ThumbnailRules.LONG_SIDE).isEqualTo(720);
    assertThat(ThumbnailRules.JPEG_QUALITY).isEqualTo(0.80f);
    assertThat(ThumbnailRules.MAX_ATTEMPTS).isEqualTo(3);
  }

  @Nested
  @DisplayName("targetSize — 긴 변을 720 으로, 비율 그대로, 키우지 않는다")
  class TargetSize {

    @Test
    @DisplayName("가로 2048×1536 → 720×540")
    void landscapePhone() {
      assertThat(ThumbnailRules.targetSize(2048, 1536))
          .isEqualTo(new ThumbnailRules.Size(720, 540));
    }

    @Test
    @DisplayName("세로 1536×2048 → 540×720 — 긴 변이 높이면 높이가 720")
    void portraitPhone() {
      assertThat(ThumbnailRules.targetSize(1536, 2048))
          .isEqualTo(new ThumbnailRules.Size(540, 720));
    }

    @Test
    @DisplayName("촬영지 사진 1280×720 → 720×405")
    void placePhoto() {
      assertThat(ThumbnailRules.targetSize(1280, 720)).isEqualTo(new ThumbnailRules.Size(720, 405));
    }

    @Test
    @DisplayName("짧은 변은 반올림 — 2048×1365 → 720×480 (479.88)")
    void roundsShortSide() {
      assertThat(ThumbnailRules.targetSize(2048, 1365))
          .isEqualTo(new ThumbnailRules.Size(720, 480));
    }

    @Test
    @DisplayName("정사각 3000×3000 → 720×720")
    void square() {
      assertThat(ThumbnailRules.targetSize(3000, 3000))
          .isEqualTo(new ThumbnailRules.Size(720, 720));
    }

    @Test
    @DisplayName("긴 변이 이미 720 이하면 그대로 — 400×300, 720×500 은 키우지 않는다")
    void neverUpscales() {
      assertThat(ThumbnailRules.targetSize(400, 300)).isEqualTo(new ThumbnailRules.Size(400, 300));
      assertThat(ThumbnailRules.targetSize(720, 500)).isEqualTo(new ThumbnailRules.Size(720, 500));
      assertThat(ThumbnailRules.targetSize(1, 1)).isEqualTo(new ThumbnailRules.Size(1, 1));
    }

    @Test
    @DisplayName("아주 가는 그림도 짧은 변이 0 이 되지 않는다 — 5000×3 → 720×1")
    void shortSideAtLeastOne() {
      assertThat(ThumbnailRules.targetSize(5000, 3)).isEqualTo(new ThumbnailRules.Size(720, 1));
    }

    @Test
    @DisplayName("크기가 0 이하면 거절한다")
    void rejectsEmpty() {
      assertThatThrownBy(() -> ThumbnailRules.targetSize(0, 100))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ThumbnailRules.targetSize(100, -1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("thumbnailKey — 원래 자리 아래 thumb-720/")
  class ThumbnailKey {

    @Test
    @DisplayName("후기 사진 posts/<이름> → posts/thumb-720/<이름>")
    void post() {
      assertThat(ThumbnailRules.thumbnailKey("posts/9f2c1a.jpg"))
          .isEqualTo("posts/thumb-720/9f2c1a.jpg");
    }

    @Test
    @DisplayName("리뷰 사진 reviews/<이름> → reviews/thumb-720/<이름>")
    void review() {
      assertThat(ThumbnailRules.thumbnailKey("reviews/ab12cd.jpg"))
          .isEqualTo("reviews/thumb-720/ab12cd.jpg");
    }

    @Test
    @DisplayName("촬영지 사진 place/<경로> → place/thumb-720/<경로> — 하위 폴더를 그대로 둔다")
    void place() {
      assertThat(ThumbnailRules.thumbnailKey("place/credited-2026-11-02/8aef77.jpg"))
          .isEqualTo("place/thumb-720/credited-2026-11-02/8aef77.jpg");
    }

    @Test
    // 장면 사진(scene/)은 1.32.0 부터 작은 판을 만든다(web-ad-landing.md §10-2) — SceneThumbnailsTest 가 본다.
    @DisplayName("임시 자리·모르는 자리는 거절한다 — uploads/tmp/, content/, 접두사 없음, 비슷한 이름")
    void rejectsOtherPrefixes() {
      for (String key :
          new String[] {
            "uploads/tmp/a.jpg", "content/a.jpg", "a.jpg", "postsX/a.jpg", "sceneX/a.png", ""
          }) {
        assertThatThrownBy(() -> ThumbnailRules.thumbnailKey(key))
            .as(key)
            .isInstanceOf(IllegalArgumentException.class);
      }
    }

    @Test
    @DisplayName("작은 사진의 작은 사진은 없다 — thumb-720/ 아래 키는 거절한다")
    void rejectsThumbnailOfThumbnail() {
      assertThatThrownBy(() -> ThumbnailRules.thumbnailKey("posts/thumb-720/a.jpg"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ThumbnailRules.thumbnailKey("place/thumb-720/x/a.jpg"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("isThumbnailKey — 세 자리의 thumb-720/ 아래만 참")
    void isThumbnailKey() {
      assertThat(ThumbnailRules.isThumbnailKey("posts/thumb-720/a.jpg")).isTrue();
      assertThat(ThumbnailRules.isThumbnailKey("reviews/thumb-720/a.jpg")).isTrue();
      assertThat(ThumbnailRules.isThumbnailKey("place/thumb-720/x/a.jpg")).isTrue();
      assertThat(ThumbnailRules.isThumbnailKey("posts/a.jpg")).isFalse();
      assertThat(ThumbnailRules.isThumbnailKey("posts/thumb-720-a.jpg")).isFalse();
      assertThat(ThumbnailRules.isThumbnailKey("uploads/tmp/thumb-720/a.jpg")).isFalse();
    }
  }
}
