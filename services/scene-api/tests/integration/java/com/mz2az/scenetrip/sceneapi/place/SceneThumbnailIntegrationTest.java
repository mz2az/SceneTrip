package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Scene;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 장면 사진 작은 판·촬영지 한국어 이름(계약 1.32.0) — 계획 {@code web-ad-landing.md} §10-2, {@code
 * photo-thumbnails.md} 「장면 사진」.
 *
 * <p>명세에서 나온 기대:
 *
 * <ul>
 *   <li>{@code Scene.sceneImageThumbnailUrls} 는 {@code sceneImageUrls} 와 같은 순서·같은 길이, 작은 판({@code
 *       place_content_image.thumbnail_url}, V50)이 없는 장은 그 자리에 원본. {@code sceneImageThumbnailUrl} 은
 *       그 첫 장. 장면 사진이 없으면 빈 배열과 null.
 *   <li>{@code PlaceSummary.sceneImageThumbnailUrl} 은 작품을 특정한 목록({@code GET
 *       /contents/{id}/places})에서만, 그 작품 장면의 첫 장 작은 판(없으면 원본). 장면 사진이 없거나 작품을 특정하지 않은 목록은 null.
 *   <li>{@code PlaceSummary.nameKo} 는 요청 언어와 무관하게 {@code place_i18n} 의 {@code ko} 이름, 없으면 null.
 * </ul>
 *
 * <p>아무도 없는 좌표(남극해)에 촬영지를 만들고 전부 되돌리는 트랜잭션 안에서 돈다.
 */
@DisplayName("PlaceStore — 장면 사진 작은 판·한국어 이름 (실제 DB, 1.32.0)")
class SceneThumbnailIntegrationTest {

  private static final double LNG = 11.7322;
  private static final double LAT = -61.7322;
  private static final String M = "https://media.example/";

  private static JdbcClient jdbc;
  private static PlaceStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = PlaceStores.create(jdbc);
  }

  @Test
  @DisplayName("상세의 장면 — 작은 판 배열은 원본과 같은 순서·길이, 없는 장은 원본, 첫 장이 sceneImageThumbnailUrl")
  void detailScenesCarryThumbnailsInOrder() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place(0.0001, "장면 시험 촬영지", "Scene Test Spot");
          long both = content("둘 다");
          long none = content("사진 없음");
          long pcBoth = placeContent(place, both);
          sceneImage(pcBoth, M + "scene/a/1.png", M + "scene/thumb-720/a/1.jpg", 10);
          sceneImage(pcBoth, M + "scene/a/2.png", null, 20);
          sceneImage(pcBoth, M + "scene/a/3.png", M + "scene/thumb-720/a/3.jpg", 30);
          placeContent(place, none);

          List<Scene> scenes =
              store.findDetail(place, Lang.EN, null, null).orElseThrow().place().getScenes();
          Map<Long, Scene> byContent =
              scenes.stream().collect(Collectors.toMap(Scene::getContentId, Function.identity()));

          Scene s = byContent.get(both);
          assertThat(s.getSceneImageUrls())
              .containsExactly(
                  URI.create(M + "scene/a/1.png"),
                  URI.create(M + "scene/a/2.png"),
                  URI.create(M + "scene/a/3.png"));
          assertThat(s.getSceneImageThumbnailUrls())
              .containsExactly(
                  URI.create(M + "scene/thumb-720/a/1.jpg"),
                  URI.create(M + "scene/a/2.png"),
                  URI.create(M + "scene/thumb-720/a/3.jpg"));
          assertThat(s.getSceneImageThumbnailUrl())
              .isEqualTo(URI.create(M + "scene/thumb-720/a/1.jpg"));

          Scene empty = byContent.get(none);
          assertThat(empty.getSceneImageUrls()).isEmpty();
          assertThat(empty.getSceneImageThumbnailUrls()).isEmpty();
          assertThat(empty.getSceneImageThumbnailUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("첫 장에 작은 판이 없으면 sceneImageThumbnailUrl 은 원본 첫 장")
  void firstThumbnailFallsBackToOriginal() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place(0.0002, "원본 대체 촬영지", null);
          long content = content("원본만");
          long pc = placeContent(place, content);
          sceneImage(pc, M + "scene/b/1.png", null, 10);

          Scene s =
              store.findDetail(place, Lang.KO, null, null).orElseThrow().place().getScenes().get(0);

          assertThat(s.getSceneImageThumbnailUrls())
              .containsExactly(URI.create(M + "scene/b/1.png"));
          assertThat(s.getSceneImageThumbnailUrl()).isEqualTo(URI.create(M + "scene/b/1.png"));
          return null;
        });
  }

  @Test
  @DisplayName("작품의 촬영지 목록 — 그 작품 장면 첫 장의 작은 판(없으면 원본), 장면 사진이 없으면 null")
  void contentPlacesCarryFirstSceneThumbnail() {
    IntegrationDatabase.rolledBack(
        () -> {
          long content = content("목록 작품");
          long other = content("다른 작품");

          long thumbed = place(0.0003, "작은 판 있는 곳", null);
          long pc1 = placeContent(thumbed, content);
          sceneImage(pc1, M + "scene/c/1.png", M + "scene/thumb-720/c/1.jpg", 10);
          sceneImage(pc1, M + "scene/c/2.png", M + "scene/thumb-720/c/2.jpg", 20);
          // 다른 작품의 장면은 섞지 않는다
          long pcOther = placeContent(thumbed, other);
          sceneImage(pcOther, M + "scene/z/1.png", M + "scene/thumb-720/z/1.jpg", 5);

          long originalOnly = place(0.0004, "원본만 있는 곳", null);
          sceneImage(placeContent(originalOnly, content), M + "scene/d/1.png", null, 10);

          long noScene = place(0.0005, "장면 사진 없는 곳", null);
          placeContent(noScene, content);

          Map<Long, PlaceSummary> byId = listByContent(content);

          assertThat(byId.get(thumbed).getSceneImageThumbnailUrl())
              .isEqualTo(URI.create(M + "scene/thumb-720/c/1.jpg"));
          assertThat(byId.get(originalOnly).getSceneImageThumbnailUrl())
              .isEqualTo(URI.create(M + "scene/d/1.png"));
          assertThat(byId.get(noScene).getSceneImageThumbnailUrl()).isNull();

          // 다른 작품으로 좁히면 그 작품의 장면
          assertThat(listByContent(other).get(thumbed).getSceneImageThumbnailUrl())
              .isEqualTo(URI.create(M + "scene/thumb-720/z/1.jpg"));
          return null;
        });
  }

  @Test
  @DisplayName("작품을 특정하지 않은 목록은 sceneImageThumbnailUrl 이 null")
  void otherListsLeaveSceneThumbnailNull() {
    IntegrationDatabase.rolledBack(
        () -> {
          long content = content("뷰포트 작품");
          long place = place(0.0006, "뷰포트 촬영지", null);
          sceneImage(
              placeContent(place, content), M + "scene/e/1.png", M + "scene/thumb-720/e/1.jpg", 10);

          assertThat(listAround().get(place).getSceneImageThumbnailUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("nameKo — 영어로 물어도 한국어 이름, 한국어 이름이 없으면 null")
  void nameKoIsAlwaysKorean() {
    IntegrationDatabase.rolledBack(
        () -> {
          long both = place(0.0007, "여의도 시험 공원", "Yeouido Test Park");
          long englishOnly = placeWithoutKorean(0.0008, "English Only Spot");

          Map<Long, PlaceSummary> byId = listAround();

          assertThat(byId.get(both).getName()).isEqualTo("Yeouido Test Park");
          assertThat(byId.get(both).getNameKo()).isEqualTo("여의도 시험 공원");
          assertThat(byId.get(englishOnly).getName()).isEqualTo("English Only Spot");
          assertThat(byId.get(englishOnly).getNameKo()).isNull();

          // 작품의 촬영지 목록에도 온다
          long content = content("이름 작품");
          placeContent(both, content);
          assertThat(listByContent(content).get(both).getNameKo()).isEqualTo("여의도 시험 공원");
          return null;
        });
  }

  private static Map<Long, PlaceSummary> listAround() {
    Bbox around = new Bbox(LNG - 0.01, LAT - 0.01, LNG + 0.01, LAT + 0.01);
    return index(
        store.list(
            new PlaceStore.Criteria(
                null, null, around, null, null, null, PlaceStore.Sort.POPULARITY, Lang.EN, 50, 0)));
  }

  private static Map<Long, PlaceSummary> listByContent(long contentId) {
    return index(
        store.list(
            new PlaceStore.Criteria(
                null,
                contentId,
                null,
                null,
                null,
                null,
                PlaceStore.Sort.POPULARITY,
                Lang.EN,
                50,
                0)));
  }

  private static Map<Long, PlaceSummary> index(PlaceStore.Page page) {
    return page.items().stream()
        .collect(Collectors.toMap(PlaceSummary::getId, Function.identity()));
  }

  private static long insertPlace(double offset) {
    return jdbc.sql(
            "INSERT INTO place (type, geom) VALUES ('시험',"
                + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) RETURNING id")
        .param("lng", LNG + offset)
        .param("lat", LAT)
        .query(Long.class)
        .single();
  }

  private static long place(double offset, String ko, String en) {
    long id = insertPlace(offset);
    placeName(id, "ko", ko);
    if (en != null) {
      placeName(id, "en", en);
    }
    return id;
  }

  private static long placeWithoutKorean(double offset, String en) {
    long id = insertPlace(offset);
    placeName(id, "en", en);
    return id;
  }

  private static void placeName(long placeId, String lang, String name) {
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, CAST(:l AS lang_code), :n)")
        .param("p", placeId)
        .param("l", lang)
        .param("n", name)
        .update();
  }

  private static long content(String title) {
    long id =
        jdbc.sql("INSERT INTO content (category) VALUES ('drama') RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO content_i18n (content_id, lang, title) VALUES (:c, CAST('ko' AS"
                + " lang_code), :t)")
        .param("c", id)
        .param("t", title)
        .update();
    return id;
  }

  private static long placeContent(long placeId, long contentId) {
    return jdbc.sql("INSERT INTO place_content (place_id, content_id) VALUES (:p, :c) RETURNING id")
        .param("p", placeId)
        .param("c", contentId)
        .query(Long.class)
        .single();
  }

  private static void sceneImage(long placeContentId, String url, String thumbnailUrl, int order) {
    jdbc.sql(
            "INSERT INTO place_content_image (place_content_id, url, sort_order, thumbnail_url)"
                + " VALUES (:pc, :u, :o, :t)")
        .param("pc", placeContentId)
        .param("u", url)
        .param("o", order)
        .param("t", thumbnailUrl)
        .update();
  }
}
