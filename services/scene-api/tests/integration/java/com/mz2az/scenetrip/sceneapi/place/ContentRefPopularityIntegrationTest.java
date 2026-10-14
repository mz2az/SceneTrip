package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.ContentRef;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.MarketSort;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.course.TravelEstimator;
import com.mz2az.scenetrip.sceneapi.market.MarketStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 장소×작품 인기도 {@code ContentRef.popularity} (MZ2AZ-339, 계약 1.16.0).
 *
 * <p>명세: {@code place_content.popularity_score} 그대로 — 같은 장소라도 작품마다 다르다. 점수가 없으면 0 이 아니라 null. 장소 목록
 * ({@code PlaceSummary.contents})에서만 차고, 마켓 코스 요약에서는 null.
 *
 * <p>픽스처: 장소 둘(A·B)과 작품 셋(X·Y·Z). 적재분은 산정 점수가 전부 있어 「없음」·「0」 쪽을 볼 수 없으므로 끼워 넣는다. 좌표는 바다 한가운데라 적재된
 * 촬영지와 영역이 겹치지 않는다. 트랜잭션째 되돌린다.
 *
 * <pre>
 *        X        Y        Z
 *   A   2.2      18.0     0
 *   B   12.345   (NULL)   —
 * </pre>
 */
@DisplayName("ContentRef.popularity — 장소 목록의 작품 배지에 장소×작품 산정 점수가 실린다")
class ContentRefPopularityIntegrationTest {

  private static final double A_LNG = 129.5000;
  private static final double A_LAT = 34.0000;
  private static final double B_LNG = 129.5005;
  private static final double B_LAT = 34.0003;

  private static JdbcClient jdbc;
  private static PlaceStore places;
  private static MarketStore market;
  private static CourseStore courses;
  private static UserStore users;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    TravelEstimator travel = DeployedTravel.estimator();
    DwellDefaults dwell = new DwellDefaults();
    dwell.putAll(Map.of());
    places = PlaceStores.create(jdbc);
    market = new MarketStore(jdbc, travel, IntegrationDatabase.transactions());
    courses = new CourseStore(jdbc, travel, dwell, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
  }

  @Test
  @DisplayName("q 검색 — 같은 장소의 작품마다 다른 값, 없는 짝은 null, 0 은 0, 소수점은 그대로")
  void searchList() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          Map<Long, PlaceSummary> byPlace =
              byId(list(criteria(f.marker, null, null, null, null, null, Lang.KO)));

          assertThat(byPlace).containsOnlyKeys(f.a, f.b);
          assertA(byPlace.get(f.a), f);
          assertB(byPlace.get(f.b), f);
          return null;
        });
  }

  @Test
  @DisplayName("작품으로 거른 목록(GET /contents/{id}/places) — 거른 작품이 아닌 배지도 제 작품 값을 갖는다")
  void filteredByContent() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          Map<Long, PlaceSummary> byX =
              byId(list(criteria(null, f.x, null, null, null, null, Lang.KO)));
          assertThat(byX).containsOnlyKeys(f.a, f.b);
          assertA(byX.get(f.a), f);
          assertB(byX.get(f.b), f);

          // 점수가 없는 짝(B×Y)도 목록에서 빠지지 않고, 그 배지는 null 이다.
          Map<Long, PlaceSummary> byY =
              byId(list(criteria(null, f.y, null, null, null, null, Lang.KO)));
          assertThat(byY).containsOnlyKeys(f.a, f.b);
          assertA(byY.get(f.a), f);
          assertB(byY.get(f.b), f);

          // 점수가 0 인 짝(A×Z)도 목록에 나오고 배지는 0.
          Map<Long, PlaceSummary> byZ =
              byId(list(criteria(null, f.z, null, null, null, null, Lang.KO)));
          assertThat(byZ).containsOnlyKeys(f.a);
          assertA(byZ.get(f.a), f);
          return null;
        });
  }

  @Test
  @DisplayName("지도 영역(bbox) 조회 — 같은 값")
  void bboxList() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();
          Bbox area = new Bbox(A_LNG - 0.01, A_LAT - 0.01, A_LNG + 0.01, A_LAT + 0.01);

          Map<Long, PlaceSummary> byPlace =
              byId(list(criteria(null, null, area, null, null, null, Lang.KO)));

          assertThat(byPlace).containsOnlyKeys(f.a, f.b);
          assertA(byPlace.get(f.a), f);
          assertB(byPlace.get(f.b), f);
          return null;
        });
  }

  @Test
  @DisplayName("반경 + 거리순 조회 — 같은 값, 정렬이 바뀌어도 배지가 장소를 따라간다")
  void radiusByDistance() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          List<PlaceSummary> items =
              places
                  .list(
                      new PlaceStore.Criteria(
                          null,
                          null,
                          null,
                          A_LAT,
                          A_LNG,
                          1000,
                          PlaceStore.Sort.DISTANCE,
                          Lang.KO,
                          50,
                          0))
                  .items();

          assertThat(items).extracting(PlaceSummary::getId).containsExactly(f.a, f.b);
          assertA(items.get(0), f);
          assertB(items.get(1), f);
          return null;
        });
  }

  @Test
  @DisplayName("페이지를 나눠도(limit 1, offset 1) 그 장소의 값")
  void paged() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          List<PlaceSummary> second =
              places
                  .list(
                      new PlaceStore.Criteria(
                          null,
                          null,
                          null,
                          A_LAT,
                          A_LNG,
                          1000,
                          PlaceStore.Sort.DISTANCE,
                          Lang.KO,
                          1,
                          1))
                  .items();

          assertThat(second).extracting(PlaceSummary::getId).containsExactly(f.b);
          assertB(second.get(0), f);
          return null;
        });
  }

  @Test
  @DisplayName("언어를 바꿔도(en) 값은 같다 — 언어를 타지 않는 값")
  void otherLanguage() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();

          Map<Long, PlaceSummary> byPlace =
              byId(list(criteria(null, f.x, null, null, null, null, Lang.EN)));

          assertThat(byPlace).containsOnlyKeys(f.a, f.b);
          assertA(byPlace.get(f.a), f);
          assertB(byPlace.get(f.b), f);
          return null;
        });
  }

  @Test
  @DisplayName("마켓 코스 목록(MarketCourseSummary.contents) — 점수가 있어도 null")
  void marketSummaryIsNull() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();
          UUID author = users.resolve(UUID.randomUUID());
          UUID reader = users.resolve(UUID.randomUUID());
          long postId = market.publish(author, courseAt(author, f.a, f.b), "설명").orElseThrow();

          MarketStore.Page page =
              market.withContents(market.list(reader, null, MarketSort.SAVES, 10_000, 0), Lang.KO);

          MarketCourseSummary mine =
              page.items().stream()
                  .filter(p -> p.getId() == postId)
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("올린 코스가 목록에 없다"));
          assertThat(mine.getContents())
              .extracting(ContentRef::getContentId)
              .containsExactlyInAnyOrder(f.x, f.y, f.z);
          assertThat(mine.getContents()).extracting(ContentRef::getPopularity).containsOnlyNulls();
          return null;
        });
  }

  @Test
  @DisplayName("마켓 코스 상세(MarketCourseDetail.contents) — 장소 목록이 아니므로 null")
  void marketDetailIsNull() {
    IntegrationDatabase.rolledBack(
        () -> {
          Fixture f = Fixture.insert();
          UUID author = users.resolve(UUID.randomUUID());
          UUID reader = users.resolve(UUID.randomUUID());
          long postId = market.publish(author, courseAt(author, f.a, f.b), "설명").orElseThrow();

          MarketCourseDetail post = market.find(reader, postId, Lang.KO).orElseThrow();

          assertThat(post.getContents())
              .extracting(ContentRef::getContentId)
              .containsExactlyInAnyOrder(f.x, f.y, f.z);
          assertThat(post.getContents()).extracting(ContentRef::getPopularity).containsOnlyNulls();
          return null;
        });
  }

  @Test
  @DisplayName("적재분 — 계약 설명의 예(N서울타워: 별에서 온 그대 2.2 · 케이팝 데몬 헌터스 18.0)가 DB 값 그대로 실린다")
  void seededExample() {
    Long place =
        jdbc.sql(
                "SELECT place_id FROM place_i18n WHERE lang = 'ko' AND name = 'N서울타워'"
                    + " ORDER BY place_id LIMIT 1")
            .query(Long.class)
            .optional()
            .orElse(null);
    Assumptions.assumeTrue(place != null, "적재분에 N서울타워가 없다");

    Map<Long, BigDecimal> expected = new HashMap<>();
    jdbc.sql("SELECT content_id, popularity_score FROM place_content WHERE place_id = :p")
        .param("p", place)
        .query(
            (rs, i) -> {
              expected.put(rs.getLong(1), rs.getBigDecimal(2));
              return null;
            })
        .list();
    assertThat(expected).hasSizeGreaterThanOrEqualTo(2);

    // 작품마다 거른 목록에서 같은 장소의 배지를 본다 — 어느 작품으로 들어가든 배지 값은 같아야 한다.
    for (long contentId : expected.keySet()) {
      PlaceSummary mine =
          places
              .list(criteria(null, contentId, null, null, null, null, Lang.KO, 200))
              .items()
              .stream()
              .filter(p -> p.getId() == (long) place)
              .findFirst()
              .orElseThrow(() -> new AssertionError("작품 " + contentId + " 의 목록에 N서울타워가 없다"));
      Map<Long, ContentRef> refs = refs(mine);
      assertThat(refs).containsOnlyKeys(expected.keySet());
      expected.forEach(
          (c, score) -> {
            Double actual = refs.get(c).getPopularity();
            if (score == null) {
              assertThat(actual).as("작품 %d", c).isNull();
            } else {
              assertThat(actual).as("작품 %d", c).isEqualTo(score.doubleValue());
            }
          });
    }
    // 계약이 예로 든 값 자체.
    assertThat(expected.values())
        .extracting(v -> v == null ? null : v.doubleValue())
        .contains(2.2, 18.0);
  }

  // ───────────── 거들기 ─────────────

  private static void assertA(PlaceSummary a, Fixture f) {
    Map<Long, ContentRef> refs = refs(a);
    assertThat(refs).containsOnlyKeys(f.x, f.y, f.z);
    assertThat(refs.get(f.x).getPopularity()).isEqualTo(2.2);
    assertThat(refs.get(f.y).getPopularity()).isEqualTo(18.0);
    assertThat(refs.get(f.z).getPopularity()).as("0 은 null 이 아니라 0").isNotNull().isZero();
  }

  private static void assertB(PlaceSummary b, Fixture f) {
    Map<Long, ContentRef> refs = refs(b);
    assertThat(refs).containsOnlyKeys(f.x, f.y);
    assertThat(refs.get(f.x).getPopularity()).isEqualTo(12.345);
    assertThat(refs.get(f.y).getPopularity()).as("점수 없음은 0 이 아니라 null").isNull();
  }

  private static Map<Long, ContentRef> refs(PlaceSummary p) {
    assertThat(p.getContents()).isNotNull();
    return p.getContents().stream()
        .collect(Collectors.toMap(ContentRef::getContentId, Function.identity()));
  }

  private static Map<Long, PlaceSummary> byId(List<PlaceSummary> items) {
    return items.stream().collect(Collectors.toMap(PlaceSummary::getId, Function.identity()));
  }

  private static List<PlaceSummary> list(PlaceStore.Criteria c) {
    return places.list(c).items();
  }

  private static PlaceStore.Criteria criteria(
      String q, Long contentId, Bbox area, Double lat, Double lng, Integer radius, Lang lang) {
    return criteria(q, contentId, area, lat, lng, radius, lang, 50);
  }

  private static PlaceStore.Criteria criteria(
      String q,
      Long contentId,
      Bbox area,
      Double lat,
      Double lng,
      Integer radius,
      Lang lang,
      int limit) {
    return new PlaceStore.Criteria(
        q, contentId, area, lat, lng, radius, PlaceStore.Sort.POPULARITY, lang, limit, 0);
  }

  private static long courseAt(UUID owner, long... placeIds) {
    long id = courses.create(owner, new CourseCreate(1, CourseOrigin.SELF).title("인기도 코스"));
    List<CourseItemInput> items =
        java.util.Arrays.stream(placeIds)
            .mapToObj(p -> new CourseItemInput().placeId(p).dwellMinutes(60))
            .toList();
    courses.replace(id, new CourseReplace("인기도 코스", List.of(new CourseDayInput(items))));
    return id;
  }

  /** 표식은 매번 새로 만든다. 장소 설명에 달아 {@code q} 로 이 픽스처만 고른다. */
  private record Fixture(String marker, long x, long y, long z, long a, long b) {

    static Fixture insert() {
      String token = UUID.randomUUID().toString().replace("-", "");
      String marker = "zzpopularity" + token;

      long x = content("인기도픽스처X" + token);
      long y = content("인기도픽스처Y" + token);
      long z = content("인기도픽스처Z" + token);
      long a = place(A_LNG, A_LAT, "인기도 픽스처 A", marker);
      long b = place(B_LNG, B_LAT, "인기도 픽스처 B", marker);

      link(a, x, new BigDecimal("2.2"));
      link(a, y, new BigDecimal("18.0"));
      link(a, z, BigDecimal.ZERO);
      link(b, x, new BigDecimal("12.345"));
      link(b, y, null);

      return new Fixture(marker, x, y, z, a, b);
    }

    private static long content(String title) {
      long id =
          jdbc.sql("INSERT INTO content (category) VALUES ('drama') RETURNING id")
              .query(Long.class)
              .single();
      for (String lang : new String[] {"ko", "en"}) {
        jdbc.sql(
                "INSERT INTO content_i18n (content_id, lang, title)"
                    + " VALUES (:id, CAST(:lang AS lang_code), :title)")
            .param("id", id)
            .param("lang", lang)
            .param("title", title + " " + lang)
            .update();
      }
      return id;
    }

    private static long place(double lng, double lat, String name, String marker) {
      long id =
          jdbc.sql(
                  "INSERT INTO place (geom)"
                      + " VALUES (ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)"
                      + " RETURNING id")
              .param("lng", lng)
              .param("lat", lat)
              .query(Long.class)
              .single();
      for (String lang : new String[] {"ko", "en"}) {
        jdbc.sql(
                "INSERT INTO place_i18n (place_id, lang, name, description)"
                    + " VALUES (:id, CAST(:lang AS lang_code), :name, :description)")
            .param("id", id)
            .param("lang", lang)
            .param("name", name + " " + lang)
            .param("description", marker)
            .update();
      }
      return id;
    }

    private static void link(long place, long content, BigDecimal score) {
      jdbc.sql(
              "INSERT INTO place_content (place_id, content_id, popularity_score)"
                  + " VALUES (:p, :c, :s)")
          .param("p", place)
          .param("c", content)
          .param("s", score, java.sql.Types.NUMERIC)
          .update();
    }
  }
}
