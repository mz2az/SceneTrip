package com.mz2az.scenetrip.sceneapi.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 촬영지 작은 사진 도구({@code just place-thumbnails})의 순수한 부분 — 계획 {@code photo-thumbnails.md} §2-1·§2-2·§3
 * (MZ2AZ-561).
 *
 * <p>명세에서 나온 기대: {@code seed/candidates.csv} 의 {@code place_image_url} 중 공용 버킷 {@code
 * scenetrip-media-prod} 의 {@code place/…} 주소만 대상이다(장면 스틸 {@code scene/…}·관광공사 등 바깥 주소는 아니다). 작은 사진은
 * 같은 버킷 {@code place/thumb-720/<place/ 뒤 경로>} 에 덮어쓰기 없이 올린다 — 이미 있는 것은 건너뛴다. 결과 {@code
 * seed/place_image_thumbnails.tsv} 는 머리줄 {@code url\tthumbnail_url} 과 줄마다 원본 주소·작은 사진 주소.
 *
 * <p>내려받기·올리기(망)는 여기서 보지 않는다.
 */
@DisplayName("PlaceThumbnails — 촬영지 작은 사진 도구의 경로·TSV·건너뛰기")
class PlaceThumbnailsTest {

  private static final String ORIGIN =
      "https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/";
  private static final String CREDITED =
      ORIGIN + "place/credited-2026-11-02/8aef77f4a653089c63dd65aab4cda4b1.jpg";
  private static final String EDITED =
      ORIGIN + "place/edited-2026-10-22/1abaff1ac9ad1b354350f57be4caf8d8.jpg";
  private static final String SCENE = ORIGIN + "scene/mv496243/nv18661762_1.png";
  private static final String KTO =
      "https://tong.visitkorea.or.kr/cms/resource/46/1902246_image2_1.jpg";

  @Test
  @DisplayName("버킷 주소 — 공용 버킷 scenetrip-media-prod")
  void origin() {
    assertThat(PlaceThumbnails.ORIGIN).isEqualTo(ORIGIN);
  }

  @Test
  @DisplayName("thumbnailKey — 우리 촬영지 사진 주소 → place/thumb-720/<place/ 뒤 경로>")
  void thumbnailKeyOfOurPlacePhoto() {
    assertThat(PlaceThumbnails.thumbnailKey(CREDITED))
        .contains("place/thumb-720/credited-2026-11-02/8aef77f4a653089c63dd65aab4cda4b1.jpg");
    assertThat(PlaceThumbnails.thumbnailKey(EDITED))
        .contains("place/thumb-720/edited-2026-10-22/1abaff1ac9ad1b354350f57be4caf8d8.jpg");
  }

  @Test
  @DisplayName("thumbnailUrl — 같은 버킷의 작은 사진 공개 주소")
  void thumbnailUrlOfOurPlacePhoto() {
    assertThat(PlaceThumbnails.thumbnailUrl(CREDITED))
        .contains(
            ORIGIN + "place/thumb-720/credited-2026-11-02/8aef77f4a653089c63dd65aab4cda4b1.jpg");
  }

  @Test
  @DisplayName("대상 아님 — 장면 스틸(scene/), 관광공사 등 바깥 주소, 이미 작은 사진, 빈 값")
  void notOurPlacePhoto() {
    assertThat(PlaceThumbnails.thumbnailKey(SCENE)).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(KTO)).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(ORIGIN + "place/thumb-720/x/a.jpg")).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey("")).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(null)).isEmpty();
    // 버킷 이름만 닮은 다른 호스트
    assertThat(
            PlaceThumbnails.thumbnailKey(
                "https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com.evil.io/place/a.jpg"))
        .isEmpty();
    assertThat(PlaceThumbnails.thumbnailUrl(SCENE)).isEmpty();
  }

  @Test
  @DisplayName("TSV — 머리줄 url\\tthumbnail_url, 줄은 원본\\t작은 사진")
  void tsvFormat() {
    assertThat(PlaceThumbnails.TSV_HEADER).isEqualTo("url\tthumbnail_url");
    String thumb =
        ORIGIN + "place/thumb-720/credited-2026-11-02/8aef77f4a653089c63dd65aab4cda4b1.jpg";

    assertThat(PlaceThumbnails.tsvLine(CREDITED, thumb)).isEqualTo(CREDITED + "\t" + thumb);
  }

  @Test
  @DisplayName("TSV — 칸 안에 탭·줄바꿈이 있으면 거절한다(줄이 깨진다)")
  void tsvRejectsSeparators() {
    assertThatThrownBy(() -> PlaceThumbnails.tsvLine(CREDITED + "\t", "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PlaceThumbnails.tsvLine(CREDITED, "x\ny"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("pending — 우리 촬영지 사진만, 중복 없이, 처음 나온 순서로")
  void pendingFiltersAndDedupes() {
    List<String> urls = List.of(EDITED, SCENE, KTO, CREDITED, EDITED, "", CREDITED);

    assertThat(PlaceThumbnails.pending(urls, Set.of())).containsExactly(EDITED, CREDITED);
  }

  @Test
  @DisplayName("pending — 작은 사진이 이미 버킷에 있으면 건너뛴다(덮어쓰지 않는다)")
  void pendingSkipsExisting() {
    Set<String> existing =
        Set.of("place/thumb-720/edited-2026-10-22/1abaff1ac9ad1b354350f57be4caf8d8.jpg");

    assertThat(PlaceThumbnails.pending(List.of(EDITED, CREDITED), existing))
        .containsExactly(CREDITED);
  }
}
