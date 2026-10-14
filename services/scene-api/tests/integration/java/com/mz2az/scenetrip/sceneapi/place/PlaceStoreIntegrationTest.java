package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Scene;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link PlaceStore} 의 SQL 을 진짜 PostgreSQL 에 태운다.
 *
 * <p>이 파일이 지키는 것은 "결과가 정확한가" 보다 <b>"질의가 성립하는가"</b> 다. 컬럼 이름 오타, PostGIS 함수의 인자 순서, 파라미터 타입 추론 실패 —
 * 전부 실행해 봐야만 드러나고, 지금까지는 운영에서 처음 터졌다.
 *
 * <p>PostGIS 를 쓰는 분기가 특히 그렇다. {@code ST_MakeEnvelope} 는 geography 로 부르면 날짜변경선에서 질의가 통째로 실패하고,
 * {@code ST_DWithin} 은 인자 순서가 바뀌어도 문법 오류가 나지 않는다.
 */
@DisplayName("PlaceStore — 실제 DB 질의")
class PlaceStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static PlaceStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new PlaceStore(jdbc);
  }

  @Test
  @DisplayName("조건 없는 목록 — 인기도 정렬")
  void listsByPopularity() {
    PlaceStore.Page page = store.list(criteria(null, null, null, null, null, 10, 0));

    assertThat(page.items()).isNotEmpty();
    assertThat(page.total()).isGreaterThanOrEqualTo(page.items().size());
  }

  @Test
  @DisplayName("지도 뷰포트(bbox) — geometry 로 봐야 하는 분기")
  void filtersByViewport() {
    Bbox seoul = new Bbox(126.7, 37.4, 127.2, 37.7);

    PlaceStore.Page page = store.list(criteria(null, seoul, null, null, null, 50, 0));

    assertThat(page.total()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("경도 -180~180 뷰포트 — geography 로 하면 질의가 실패하던 자리")
  void handlesWorldWideViewport() {
    Bbox world = new Bbox(-180, -90, 180, 90);

    PlaceStore.Page page = store.list(criteria(null, world, null, null, null, 5, 0));

    assertThat(page.items()).isNotEmpty();
  }

  @Test
  @DisplayName("반경 검색과 거리순 정렬 — ST_DWithin · ST_Distance")
  void filtersByRadiusAndSortsByDistance() {
    PlaceStore.Page page =
        store.list(
            new PlaceStore.Criteria(
                null,
                null,
                null,
                37.5665,
                126.9780,
                50_000,
                PlaceStore.Sort.DISTANCE,
                Lang.KO,
                20,
                0));

    assertThat(page.items()).isNotEmpty();
    assertThat(page.items())
        .as("거리순이면 distanceMeters 가 채워지고 오름차순이어야 한다")
        .isSortedAccordingTo((a, b) -> Integer.compare(distance(a), distance(b)));
  }

  @Test
  @DisplayName("상세 조회 — 이미지·장면·작품이 함께 붙는다")
  void findsDetail() {
    long id = IntegrationDatabase.anyPlaceId(jdbc);

    PlaceStore.Detail detail = store.findDetail(id, Lang.KO, 37.5665, 126.9780).orElseThrow();

    assertThat(detail.place().getId()).isEqualTo(id);
    assertThat(detail.place().getName()).isNotBlank();
  }

  @Test
  @DisplayName("없는 id 는 빈 값 — 예외가 아니다")
  void missingDetailIsEmpty() {
    assertThat(store.findDetail(-1L, Lang.KO, null, null)).isEmpty();
  }

  /**
   * 장면 목록의 순서는 계약이다.
   *
   * <p>명세가 {@code scenes[]} 를 인기도 내림차순으로 약속한다. 한 장소가 여러 작품에 걸릴 때 어느 것을 먼저 보여 줄지가 화면에서 갈리지 않도록 서버가
   * 정하기로 한 것이라, 정렬이 빠지면 iOS 와 Android 가 각자 다른 순서를 그린다. 작품이 하나뿐인 장소로는 이것을 확인할 수 없으므로 둘 이상 걸린 장소를 골라서
   * 본다.
   */
  @Test
  @DisplayName("장면 목록이 인기도 내림차순이다")
  void scenesAreSortedByPopularity() {
    long id = placeWithMultipleContents();

    List<Scene> scenes =
        store.findDetail(id, Lang.KO, null, null).orElseThrow().place().getScenes();

    assertThat(scenes).hasSizeGreaterThan(1);
    assertThat(scenes)
        .as("인기도 내림차순 — 순서를 서버가 정한다는 계약")
        .isSortedAccordingTo((a, b) -> Integer.compare(popularity(b), popularity(a)));
  }

  /**
   * 장면 스틸이 실제로 실려 오는가.
   *
   * <p>스틸은 {@code place_content_image} 에 한 장이 한 줄로 있다(V33, MZ2AZ-429). 묶는 조건을 한 단계 잘못 걸어도 문법 오류가 나지
   * 않고 전부 빈 배열이 되므로 — 화면에서는 사진이 없는 것과 구분되지 않는다 — 값이 실제로 오는지를 여기서 본다.
   *
   * <p><b>스틸을 테스트가 직접 심는다.</b> 시드에 스틸이 있기를 기대하면 시드가 바뀔 때 깨진다 — 성지후보 v3 에는 그 컬럼 자체가 없어 실제로 깨졌다(ADR
   * 0007 도 스틸이 오래 비어 있을 것이라 한다). 검사하는 것은 질의지 데이터가 아니므로, 아무 장면 하나에 줄을 넣고 보고 트랜잭션째 되돌린다.
   */
  @Test
  @DisplayName("장면 스틸이 응답에 실린다")
  void scenesCarryStillImages() {
    String planted = "https://example.invalid/scene-still.jpg";

    IntegrationDatabase.rolledBack(
        () -> {
          SceneTarget t = sceneTarget();
          clearStills(t);
          still(t, planted, 10);

          List<Scene> scenes =
              store.findDetail(t.placeId(), Lang.KO, null, null).orElseThrow().place().getScenes();

          assertThat(scenes)
              .as("심어 둔 장면 스틸이 실려 오지 않으면 질의가 끊긴 것이다")
              // 생성된 모델은 이 필드를 URI 로 든다 — 문자열로 견준다.
              .anyMatch(s -> planted.equals(String.valueOf(s.getSceneImageUrl())));
          return null;
        });
  }

  /**
   * 한 성지에 장면 스틸이 여러 장이면 전부, 순서대로 실린다(MZ2AZ-429, 계약 1.14.0).
   *
   * <p>송광사 성지의 실제 모양 — 두 장. {@code sceneImageUrl} 은 그 첫 장이다.
   */
  @Test
  @DisplayName("장면 스틸 여러 장이 sceneImageUrls 로 순서대로 실리고 sceneImageUrl 은 첫 장이다")
  void scenesCarryMultipleStills() {
    String one =
        "https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/scene/mv705996/nv11664039_1.png";
    String two =
        "https://scenetrip-media-prod.s3.ap-northeast-2.amazonaws.com/scene/mv705996/nv11664039_2.png";

    IntegrationDatabase.rolledBack(
        () -> {
          SceneTarget t = sceneTarget();
          clearStills(t);
          still(t, one, 10);
          still(t, two, 20);

          Scene scene = scene(t);

          assertThat(scene.getSceneImageUrls())
              .extracting(String::valueOf)
              .as("두 장 모두, sort_order 순서대로")
              .containsExactly(one, two);
          assertThat(String.valueOf(scene.getSceneImageUrl()))
              .as("sceneImageUrl 은 sceneImageUrls 의 첫 장")
              .isEqualTo(one);
          return null;
        });
  }

  /**
   * 보여 줄 순서는 넣은 순서가 아니라 {@code sort_order} 다. 같으면 먼저 넣은 줄(id)이 앞이다.
   *
   * <p>일부러 거꾸로 넣는다 — 넣은 순서대로 넣으면 정렬이 빠져도 대개 그 순서로 나와 테스트가 통과해 버린다.
   */
  @Test
  @DisplayName("장면 스틸은 sort_order 순, 같으면 id 순이다 — 넣은 순서가 아니다")
  void scenesStillsFollowSortOrder() {
    IntegrationDatabase.rolledBack(
        () -> {
          SceneTarget t = sceneTarget();
          clearStills(t);
          still(t, "https://example.invalid/c.jpg", 30);
          still(t, "https://example.invalid/a.jpg", 10);
          still(t, "https://example.invalid/b1.jpg", 20);
          still(t, "https://example.invalid/b2.jpg", 20);

          Scene scene = scene(t);

          assertThat(scene.getSceneImageUrls())
              .extracting(String::valueOf)
              .containsExactly(
                  "https://example.invalid/a.jpg",
                  "https://example.invalid/b1.jpg",
                  "https://example.invalid/b2.jpg",
                  "https://example.invalid/c.jpg");
          assertThat(String.valueOf(scene.getSceneImageUrl()))
              .as("첫 장은 sort_order 가 가장 작은 것")
              .isEqualTo("https://example.invalid/a.jpg");
          return null;
        });
  }

  @Test
  @DisplayName("장면 스틸이 없으면 sceneImageUrls 는 빈 목록이고 sceneImageUrl 은 null")
  void scenesWithoutStillsHaveEmptyList() {
    IntegrationDatabase.rolledBack(
        () -> {
          SceneTarget t = sceneTarget();
          clearStills(t);

          Scene scene = scene(t);

          assertThat(scene.getSceneImageUrls()).as("없으면 빈 배열 — null 이 아니다").isNotNull().isEmpty();
          assertThat(scene.getSceneImageUrl()).isNull();
          return null;
        });
  }

  /** 주소로 읽히지 않는 줄은 그 장만 빠진다 — 상세 전체가 500 이 되지 않는다. 첫 줄이 깨졌으면 {@code sceneImageUrl} 은 살아남은 첫 장이다. */
  @Test
  @DisplayName("주소가 아닌 장면 스틸은 빠지고 sceneImageUrl 은 남은 첫 장이다")
  void scenesDropInvalidStillUrls() {
    String valid = "https://example.invalid/valid-still.jpg";

    IntegrationDatabase.rolledBack(
        () -> {
          SceneTarget t = sceneTarget();
          clearStills(t);
          still(t, "https://example.invalid/has space.jpg", 10);
          still(t, valid, 20);
          still(t, "   ", 30);

          Scene scene = scene(t);

          assertThat(scene.getSceneImageUrls())
              .extracting(String::valueOf)
              .as("공백이 든 값·빈 값은 URI 가 아니다 — 버린다")
              .containsExactly(valid);
          assertThat(String.valueOf(scene.getSceneImageUrl())).isEqualTo(valid);
          return null;
        });
  }

  /**
   * 옛 칸 {@code place_content.scene_image_url} 은 남아 있지만 더는 읽지 않는다(V33). 그 칸에 값이 있어도 표에 줄이 없으면 스틸은 없다
   * — 옛 칸을 다시 읽기 시작하면 이어 붙인 문자열이 주소 하나로 나간다.
   */
  @Test
  @DisplayName("옛 scene_image_url 칸은 읽지 않는다 — 표에 줄이 없으면 스틸도 없다")
  void scenesIgnoreLegacyColumn() {
    IntegrationDatabase.rolledBack(
        () -> {
          SceneTarget t = sceneTarget();
          clearStills(t);
          jdbc.sql("UPDATE place_content SET scene_image_url = :url WHERE id = :id")
              .param(
                  "url",
                  "https://example.invalid/legacy-1.jpg;https://example.invalid/legacy-2.jpg")
              .param("id", t.placeContentId())
              .update();

          Scene scene = scene(t);

          assertThat(scene.getSceneImageUrls()).isEmpty();
          assertThat(scene.getSceneImageUrl()).isNull();
          return null;
        });
  }

  /** 스틸을 심을 장면 하나 — 장소×작품 한 쌍. */
  private record SceneTarget(long placeContentId, long placeId, long contentId) {}

  private static SceneTarget sceneTarget() {
    Map<String, Object> row =
        jdbc.sql("SELECT id, place_id, content_id FROM place_content ORDER BY id LIMIT 1")
            .query()
            .singleRow();
    return new SceneTarget(
        ((Number) row.get("id")).longValue(),
        ((Number) row.get("place_id")).longValue(),
        ((Number) row.get("content_id")).longValue());
  }

  private static void clearStills(SceneTarget t) {
    jdbc.sql("DELETE FROM place_content_image WHERE place_content_id = :id")
        .param("id", t.placeContentId())
        .update();
  }

  private static void still(SceneTarget t, String url, int sortOrder) {
    jdbc.sql(
            "INSERT INTO place_content_image (place_content_id, url, sort_order)"
                + " VALUES (:id, :url, :sort)")
        .param("id", t.placeContentId())
        .param("url", url)
        .param("sort", sortOrder)
        .update();
  }

  private static Scene scene(SceneTarget t) {
    return store
        .findDetail(t.placeId(), Lang.KO, null, null)
        .orElseThrow()
        .place()
        .getScenes()
        .stream()
        .filter(s -> s.getContentId() == t.contentId())
        .findFirst()
        .orElseThrow();
  }

  private int popularity(Scene scene) {
    return jdbc.sql("SELECT popularity_score FROM content WHERE id = :id")
        .param("id", scene.getContentId())
        .query(Integer.class)
        .single();
  }

  /** 촬영작이 둘 이상 걸린 장소. 없으면 정렬을 확인할 수 없으므로 테스트가 실패해야 한다. */
  private long placeWithMultipleContents() {
    return jdbc.sql(
            """
            SELECT place_id FROM place_content
            GROUP BY place_id HAVING count(*) > 1
            ORDER BY place_id LIMIT 1
            """)
        .query(Long.class)
        .single();
  }

  /**
   * 페이지를 넘길 때 항목이 겹치거나 빠지지 않는가.
   *
   * <p>정렬 키가 인기도 하나뿐이면 동점인 장소들의 순서가 매 질의마다 흔들려, offset 으로 다음 장을 받을 때 같은 항목이 두 번 나오거나 사라진다. 그래서 SQL
   * 이 {@code id} 를 보조 키로 두고 있고, 이 테스트가 그것을 지킨다.
   */
  @Test
  @DisplayName("offset 페이지네이션이 겹치지 않는다")
  void pagesDoNotOverlap() {
    List<Long> first = ids(store.list(criteria(null, null, null, null, null, 3, 0)));
    List<Long> second = ids(store.list(criteria(null, null, null, null, null, 3, 3)));

    assertThat(first).doesNotContainAnyElementsOf(second);
  }

  /**
   * 응답 언어의 폴백 사슬 — 요청한 언어, 그다음 en, 그다음 ko.
   *
   * <p>적재된 장소에는 지금 ko 행뿐이라(수집 데이터에 영어 장소명이 없다) en 칸을 실제 데이터로는 확인할 수 없다. 그래서 픽스처 장소 둘을 끼워 넣고 트랜잭션째
   * 되돌린다. 두 장소는 설명에 같은 표식을 달아 {@code q} 로 그 둘만 고른다.
   */
  @Test
  @DisplayName("목록 — ja 요청에 en 이 있으면 en, ko 만 있으면 ko")
  void listFallsBackThroughEnglishThenKorean() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          PlaceStore.Page ja = store.list(byMarker(f.marker, Lang.JA));
          assertThat(names(ja))
              .containsEntry(f.withEnglish, "Fallback Fixture Place")
              .containsEntry(f.koreanOnly, "폴백 픽스처 장소 한국어만")
              .hasSize(2);
          assertThat(ja.shownLangs()).containsExactlyInAnyOrder(Lang.EN, Lang.KO);

          PlaceStore.Page ko = store.list(byMarker(f.marker, Lang.KO));
          assertThat(names(ko))
              .containsEntry(f.withEnglish, "폴백 픽스처 장소 영어도")
              .containsEntry(f.koreanOnly, "폴백 픽스처 장소 한국어만");
          assertThat(ko.shownLangs()).containsExactly(Lang.KO);

          PlaceStore.Page en = store.list(byMarker(f.marker, Lang.EN));
          assertThat(names(en))
              .containsEntry(f.withEnglish, "Fallback Fixture Place")
              .containsEntry(f.koreanOnly, "폴백 픽스처 장소 한국어만");
          assertThat(en.shownLangs()).containsExactlyInAnyOrder(Lang.EN, Lang.KO);
          return null;
        });
  }

  @Test
  @DisplayName("상세 — shownLang 이 실제로 고른 행의 언어다")
  void detailReportsShownLang() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          PlaceStore.Detail english =
              store.findDetail(f.withEnglish, Lang.JA, null, null).orElseThrow();
          assertThat(english.place().getName()).isEqualTo("Fallback Fixture Place");
          assertThat(english.shownLang()).isEqualTo(Lang.EN);

          PlaceStore.Detail korean =
              store.findDetail(f.koreanOnly, Lang.JA, null, null).orElseThrow();
          assertThat(korean.place().getName()).isEqualTo("폴백 픽스처 장소 한국어만");
          assertThat(korean.shownLang()).isEqualTo(Lang.KO);

          PlaceStore.Detail asKorean =
              store.findDetail(f.withEnglish, Lang.KO, null, null).orElseThrow();
          assertThat(asKorean.place().getName()).isEqualTo("폴백 픽스처 장소 영어도");
          assertThat(asKorean.shownLang()).isEqualTo(Lang.KO);
          return null;
        });
  }

  /** 폴백 시험용 장소 둘 — ko+en, ko 만. 표식은 매번 새로 만들어 적재 데이터와 겹치지 않게 한다. */
  private record Fixture(String marker, long withEnglish, long koreanOnly) {

    static Fixture insert() {
      String marker = "zzfallback" + UUID.randomUUID().toString().replace("-", "");
      long withEnglish = place();
      i18n(withEnglish, "ko", "폴백 픽스처 장소 영어도", marker);
      i18n(withEnglish, "en", "Fallback Fixture Place", marker);

      long koreanOnly = place();
      i18n(koreanOnly, "ko", "폴백 픽스처 장소 한국어만", marker);

      return new Fixture(marker, withEnglish, koreanOnly);
    }

    private static long place() {
      return jdbc.sql(
              "INSERT INTO place (geom)"
                  + " VALUES (ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography)"
                  + " RETURNING id")
          .query(Long.class)
          .single();
    }

    private static void i18n(long placeId, String lang, String name, String description) {
      jdbc.sql(
              "INSERT INTO place_i18n (place_id, lang, name, description)"
                  + " VALUES (:id, :lang, :name, :description)")
          .param("id", placeId)
          .param("lang", lang)
          .param("name", name)
          .param("description", description)
          .update();
    }
  }

  private static PlaceStore.Criteria byMarker(String marker, Lang lang) {
    return new PlaceStore.Criteria(
        marker, null, null, null, null, null, PlaceStore.Sort.POPULARITY, lang, 10, 0);
  }

  private static Map<Long, String> names(PlaceStore.Page page) {
    return page.items().stream()
        .collect(Collectors.toMap(PlaceSummary::getId, PlaceSummary::getName));
  }

  private static int distance(PlaceSummary p) {
    return p.getDistanceMeters() == null ? Integer.MAX_VALUE : p.getDistanceMeters();
  }

  private static List<Long> ids(PlaceStore.Page page) {
    return page.items().stream().map(PlaceSummary::getId).toList();
  }

  private static PlaceStore.Criteria criteria(
      String q, Bbox bbox, Double lat, Double lng, Integer radius, int limit, int offset) {
    return new PlaceStore.Criteria(
        q, null, bbox, lat, lng, radius, PlaceStore.Sort.POPULARITY, Lang.KO, limit, offset);
  }
}
