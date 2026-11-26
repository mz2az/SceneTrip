package com.mz2az.scenetrip.sceneapi.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 장면 사진의 작은 판 — {@code just place-thumbnails} 도구를 장면 사진({@code scene/…})으로 넓힌 순수한 부분(계약 1.32.0, 계획
 * {@code web-ad-landing.md} §10-2, {@code photo-thumbnails.md} 「장면 사진」).
 *
 * <p>명세에서 나온 기대: 촬영지 사진과 같은 방식으로 {@code seed/candidates.csv} 의 {@code scene_image_url} 중 공용 버킷의
 * {@code scene/…} 주소만 대상이고, 작은 판은 {@code scene/thumb-720/<scene/ 뒤 경로, 확장자 .jpg>} 에 덮어쓰기 없이 올린다.
 * 결과는 {@code seed/scene_image_thumbnails.tsv}(머리줄 {@code url\tthumbnail_url}). 촬영지 사진 쪽({@link
 * PlaceThumbnails#thumbnailKey(String)})은 그대로 {@code place/} 만 본다.
 */
@DisplayName("PlaceThumbnails.Source.SCENE — 장면 사진 작은 판의 경로·대상·CSV 칸")
class SceneThumbnailsTest {

  private static final String ORIGIN =
      "https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/";
  private static final String SCENE = ORIGIN + "scene/mv496243/nv18661762_1.png";
  private static final String SCENE2 = ORIGIN + "scene/mv705996/nv11664039_2.jpg";
  private static final String PLACE =
      ORIGIN + "place/credited-2026-11-02/8aef77f4a653089c63dd65aab4cda4b1.jpg";
  private static final String OUTSIDE = "https://img.example/scene/a.png";

  @Test
  @DisplayName("규칙 — scene/<경로> → scene/thumb-720/<경로>.jpg, 그 아래 키는 이미 작은 판")
  void rulesAcceptScenePrefix() {
    assertThat(ThumbnailRules.thumbnailKey("scene/mv1/a.png"))
        .isEqualTo("scene/thumb-720/mv1/a.jpg");
    assertThat(ThumbnailRules.isThumbnailKey("scene/thumb-720/mv1/a.jpg")).isTrue();
    assertThat(ThumbnailRules.isThumbnailKey("scene/mv1/a.png")).isFalse();
  }

  @Test
  @DisplayName("thumbnailKey(SCENE) — 우리 장면 사진 주소만, scene/thumb-720/ 아래 .jpg")
  void sceneKey() {
    assertThat(PlaceThumbnails.thumbnailKey(PlaceThumbnails.Source.SCENE, SCENE))
        .contains("scene/thumb-720/mv496243/nv18661762_1.jpg");
    assertThat(PlaceThumbnails.thumbnailUrl(PlaceThumbnails.Source.SCENE, SCENE2))
        .contains(ORIGIN + "scene/thumb-720/mv705996/nv11664039_2.jpg");
  }

  @Test
  @DisplayName("thumbnailKey(SCENE) — 촬영지 사진·바깥 주소·이미 작은 판·빈 값은 대상 아님")
  void sceneNotTargets() {
    PlaceThumbnails.Source s = PlaceThumbnails.Source.SCENE;
    assertThat(PlaceThumbnails.thumbnailKey(s, PLACE)).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(s, OUTSIDE)).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(s, ORIGIN + "scene/thumb-720/mv1/a.jpg")).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(s, "")).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(s, null)).isEmpty();
  }

  @Test
  @DisplayName("촬영지 쪽은 그대로 — Source.PLACE 와 한 인자 판은 장면 사진을 대상으로 보지 않는다")
  void placeSourceUnchanged() {
    assertThat(PlaceThumbnails.thumbnailKey(PlaceThumbnails.Source.PLACE, SCENE)).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(SCENE)).isEmpty();
    assertThat(PlaceThumbnails.thumbnailKey(PlaceThumbnails.Source.PLACE, PLACE))
        .isEqualTo(PlaceThumbnails.thumbnailKey(PLACE));
  }

  @Test
  @DisplayName("pending(SCENE) — 장면 사진만, 중복 없이, 처음 나온 순서로, 이미 있는 것은 건너뛴다")
  void scenePending() {
    PlaceThumbnails.Source s = PlaceThumbnails.Source.SCENE;
    List<String> urls = List.of(SCENE2, PLACE, OUTSIDE, SCENE, SCENE2, "");

    assertThat(PlaceThumbnails.pending(s, urls, Set.of())).containsExactly(SCENE2, SCENE);
    assertThat(
            PlaceThumbnails.pending(s, urls, Set.of("scene/thumb-720/mv705996/nv11664039_2.jpg")))
        .containsExactly(SCENE);
  }

  @Test
  @DisplayName("Source — CSV 칸과 결과 TSV 자리")
  void sourceColumnsAndTsv() {
    assertThat(PlaceThumbnails.Source.PLACE.column()).isEqualTo("place_image_url");
    assertThat(PlaceThumbnails.Source.PLACE.tsv())
        .isEqualTo("services/scene-api/seed/place_image_thumbnails.tsv");
    assertThat(PlaceThumbnails.Source.SCENE.column()).isEqualTo("scene_image_url");
    assertThat(PlaceThumbnails.Source.SCENE.tsv())
        .isEqualTo("services/scene-api/seed/scene_image_thumbnails.tsv");
  }

  @Test
  @DisplayName("imageUrls — 고른 칸의 ; 로 나뉜 주소를 나온 순서대로(따옴표 안 쉼표·줄바꿈 포함)")
  void readsChosenColumn(@TempDir Path dir) throws IOException {
    Path csv = dir.resolve("c.csv");
    Files.writeString(
        csv,
        "id,place_image_url,scene_description,scene_image_url\n"
            + "1,"
            + PLACE
            + ",\"설명, 쉼표\n둘째 줄\","
            + SCENE
            + ";"
            + SCENE2
            + "\n"
            + "2,,,\n",
        StandardCharsets.UTF_8);

    assertThat(PlaceThumbnailsTool.imageUrls(csv, "scene_image_url"))
        .containsExactly(SCENE, SCENE2);
    assertThat(PlaceThumbnailsTool.imageUrls(csv, "place_image_url")).containsExactly(PLACE);
  }
}
