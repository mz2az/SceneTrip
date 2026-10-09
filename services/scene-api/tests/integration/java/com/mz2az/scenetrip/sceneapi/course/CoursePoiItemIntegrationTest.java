package com.mz2az.scenetrip.sceneapi.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemSource;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 코스 항목의 편의시설 갈래(scene-api 1.8.0, docs/project/plans/course-poi-item.md §1·§4) — 실제 DB.
 *
 * <p>명세: {@code CourseItemInput.poiId} 로 담는다. 없거나 폐업한 편의시설은 {@code POI_NOT_FOUND}(여기서는 {@link
 * CourseStore.UnknownPoiException}). 촬영지와 같은 곳으로 연결된 편의시설은 촬영지로 저장된다. 조회는 사본이 아니라 지금의 편의시설 자료이고, 체류
 * 기본값은 갈래별, 길찾기 목적지는 편의시설 좌표다.
 *
 * <p>픽스처는 적재 데이터와 겹치지 않는 바다 위(위도 34.9 · 경도 129.6 근처)에 두고, 전부 {@link IntegrationDatabase#rolledBack}
 * 안에서 돌린다 — 공유 DB 는 그대로다.
 */
@DisplayName("코스 항목의 편의시설 갈래 — 저장·조회·체류 기본값·길찾기 좌표 (실제 DB)")
class CoursePoiItemIntegrationTest {

  private static final double LAT = 34.9;
  private static final double LNG = 129.6;

  /** 위도 34.9 에서 경도 1 도 ≈ 91.3 km. */
  private static final double METERS_PER_LNG_DEGREE = 91_300.0;

  private static JdbcClient jdbc;
  private static CourseStore store;
  private static UserStore users;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    dwell.setByPlaceType("시험촬영지=45");
    // application.yaml 의 scenetrip.course.dwell.by-poi-group 과 같은 값(계획 §4).
    dwell.setByPoiGroup("food=60, sight=60, stay=30, transit=15");
    store =
        new CourseStore(
            jdbc, new TravelEstimator(4.0, 1.3), dwell, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
  }

  // ── 저장 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("poiId 로 담으면 편의시설 항목이 된다 — source poi · poiId · 이름·주소·분류·좌표가 편의시설의 것")
  void storesPoiItem() {
    rolledBack(
        () -> {
          long q = poi("a", "시험 카페 하나", "카페", "food", "부산 기장군 시험동", 0);
          long course = course(1);

          store.replace(course, replace(day(poiItem(q).dwellMinutes(50))));

          CourseItem item = onlyItem(course);
          assertThat(item.getSource()).isEqualTo(CourseItemSource.POI);
          assertThat(item.getPoiId()).isEqualTo(q);
          assertThat(item.getPlaceId()).isNull();
          assertThat(item.getCustomPinId()).isNull();
          assertThat(item.getName()).isEqualTo("시험 카페 하나");
          assertThat(item.getAddress()).isEqualTo("부산 기장군 시험동");
          assertThat(item.getCategory()).isEqualTo("카페");
          assertThat(item.getLatitude()).isCloseTo(LAT, within(1e-6));
          assertThat(item.getLongitude()).isCloseTo(LNG, within(1e-6));
          assertThat(item.getDwellMinutes()).isEqualTo(50);
          // 사본이 아니라 참조다 — 핀이 만들어지지 않는다.
          assertThat(count("SELECT count(*) FROM custom_pin WHERE course_id = " + course)).isZero();
          assertThat(
                  count(
                      "SELECT count(*) FROM course_item WHERE course_id = "
                          + course
                          + " AND poi_id = "
                          + q))
              .isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("없는 편의시설이면 UnknownPoiException (컨트롤러가 400 POI_NOT_FOUND 로 바꾼다)")
  void rejectsUnknownPoi() {
    rolledBack(
        () -> {
          long course = course(1);
          long missing = count("SELECT COALESCE(max(id), 0) + 1000 FROM poi");

          assertThatThrownBy(() -> store.replace(course, replace(day(poiItem(missing)))))
              .isInstanceOf(CourseStore.UnknownPoiException.class);
          return null;
        });
  }

  @Test
  @DisplayName("폐업한 편의시설을 새로 담으면 UnknownPoiException")
  void rejectsClosedPoi() {
    rolledBack(
        () -> {
          long q = poi("closed", "시험 폐업 식당", "한식", "food", "부산 기장군 시험동", 0);
          close(q);
          long course = course(1);

          assertThatThrownBy(() -> store.replace(course, replace(day(poiItem(q)))))
              .isInstanceOf(CourseStore.UnknownPoiException.class);
          return null;
        });
  }

  @Test
  @DisplayName("보이는 촬영지와 같은 곳으로 연결된 편의시설은 촬영지 항목으로 저장·반환된다")
  void linkedPoiIsStoredAsPlace() {
    rolledBack(
        () -> {
          long p = place("linked", "시험 촬영지 카페", 0);
          long q = poi("linked", "시험 촬영지 카페", "카페", "food", "부산 기장군 시험동", 2);
          link(q, p);
          long course = course(1);

          store.replace(course, replace(day(poiItem(q).dwellMinutes(60))));

          CourseItem item = onlyItem(course);
          assertThat(item.getSource()).isEqualTo(CourseItemSource.PLACE);
          assertThat(item.getPlaceId()).isEqualTo(p);
          assertThat(item.getPoiId()).isNull();
          assertThat(
                  count(
                      "SELECT count(*) FROM course_item WHERE course_id = "
                          + course
                          + " AND poi_id IS NOT NULL"))
              .isZero();
          return null;
        });
  }

  @Test
  @DisplayName("연결된 촬영지가 숨겨져 있으면 그 연결은 쓰지 않는다 — 편의시설 항목으로 남는다")
  void linkToHiddenPlaceIsIgnored() {
    rolledBack(
        () -> {
          long p = place("hidden", "시험 숨긴 촬영지", 0);
          jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", p).update();
          long q = poi("hidden", "시험 숨긴 촬영지", "카페", "food", "부산 기장군 시험동", 2);
          link(q, p);
          long course = course(1);

          store.replace(course, replace(day(poiItem(q).dwellMinutes(60))));

          CourseItem item = onlyItem(course);
          assertThat(item.getSource()).isEqualTo(CourseItemSource.POI);
          assertThat(item.getPoiId()).isEqualTo(q);
          return null;
        });
  }

  @Test
  @DisplayName("편의시설 항목도 id 를 실어 보내면 옮겨도 id·방문 체크가 남는다")
  void movedPoiItemKeepsIdAndVisitedAt() {
    rolledBack(
        () -> {
          long q1 = poi("m1", "시험 이동 하나", "카페", "food", "부산", 0);
          long q2 = poi("m2", "시험 이동 둘", "카페", "food", "부산", 100);
          long course = course(2);
          store.replace(
              course,
              replace(day(poiItem(q1).dwellMinutes(60), poiItem(q2).dwellMinutes(60)), day()));
          List<CourseItem> before = items(course);
          long id1 = before.get(0).getId();
          long id2 = before.get(1).getId();
          jdbc.sql("UPDATE course_item SET visited_at = now() WHERE id = :id")
              .param("id", id1)
              .update();

          // q1 을 2일차로 옮기고 체류시간을 바꾼다.
          store.replace(
              course,
              replace(
                  day(poiItem(q2).id(id2).dwellMinutes(60)),
                  day(poiItem(q1).id(id1).dwellMinutes(90))));

          CourseDetail after = store.find(user(), course, Lang.KO).orElseThrow();
          CourseItem moved = after.getDays().get(1).getItems().get(0);
          assertThat(moved.getId()).isEqualTo(id1);
          assertThat(moved.getSource()).isEqualTo(CourseItemSource.POI);
          assertThat(moved.getPoiId()).isEqualTo(q1);
          assertThat(moved.getVisitedAt()).isNotNull();
          assertThat(moved.getDwellMinutes()).isEqualTo(90);
          assertThat(after.getDays().get(0).getItems())
              .extracting(CourseItem::getId)
              .containsExactly(id2);
          return null;
        });
  }

  @Test
  @DisplayName("이미 담긴 편의시설이 폐업해도 id 를 보내면 그대로 둘 수 있고, 이름·좌표가 계속 보인다")
  void closedPoiItemSurvivesWithId() {
    rolledBack(
        () -> {
          long q = poi("kept", "시험 곧 폐업", "한식", "food", "부산", 0);
          long course = course(1);
          store.replace(course, replace(day(poiItem(q).dwellMinutes(60))));
          long id = onlyItem(course).getId();
          close(q);

          // 폐업한 뒤에 읽어도 항목은 남아 있다.
          CourseItem shown = onlyItem(course);
          assertThat(shown.getSource()).isEqualTo(CourseItemSource.POI);
          assertThat(shown.getName()).isEqualTo("시험 곧 폐업");
          assertThat(shown.getLatitude()).isCloseTo(LAT, within(1e-6));

          store.replace(course, replace(day(poiItem(q).id(id).dwellMinutes(60))));

          CourseItem after = onlyItem(course);
          assertThat(after.getId()).isEqualTo(id);
          assertThat(after.getPoiId()).isEqualTo(q);
          return null;
        });
  }

  // ── 조회 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("조회는 사본이 아니라 지금의 편의시설 자료다 — 편의시설을 고치면 코스에도 반영된다")
  void readsCurrentPoiData() {
    rolledBack(
        () -> {
          long q = poi("live", "시험 옛 이름", "카페", "food", "부산 옛 주소", 0);
          long course = course(1);
          store.replace(course, replace(day(poiItem(q).dwellMinutes(60))));

          jdbc.sql(
                  "UPDATE poi SET name = '시험 새 이름', address = '부산 새 주소', category = '베이커리', geom ="
                      + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography WHERE id = :id")
              .param("lng", LNG + 0.001)
              .param("lat", LAT + 0.001)
              .param("id", q)
              .update();

          CourseItem item = onlyItem(course);
          assertThat(item.getName()).isEqualTo("시험 새 이름");
          assertThat(item.getAddress()).isEqualTo("부산 새 주소");
          assertThat(item.getCategory()).isEqualTo("베이커리");
          assertThat(item.getLatitude()).isCloseTo(LAT + 0.001, within(1e-6));
          assertThat(item.getLongitude()).isCloseTo(LNG + 0.001, within(1e-6));
          return null;
        });
  }

  @Test
  @DisplayName("사진은 편의시설 사진의 첫 장(sort_order 순), 사진이 없으면 비어 있다")
  void imageIsFirstPoiImage() {
    rolledBack(
        () -> {
          long withImages = poi("img", "시험 사진 있음", "카페", "food", "부산", 0);
          long without = poi("noimg", "시험 사진 없음", "카페", "food", "부산", 50);
          image(withImages, "https://img.example.com/" + tag + "/second.jpg", 20);
          image(withImages, "https://img.example.com/" + tag + "/first.jpg", 10);
          long course = course(1);
          store.replace(
              course,
              replace(
                  day(poiItem(withImages).dwellMinutes(60), poiItem(without).dwellMinutes(60))));

          List<CourseItem> items = items(course);
          assertThat(items.get(0).getImageUrl()).isNotNull();
          assertThat(items.get(0).getImageUrl().toString())
              .isEqualTo("https://img.example.com/" + tag + "/first.jpg");
          assertThat(items.get(1).getImageUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("앞 항목에서의 거리가 편의시설 좌표로 계산된다 — 첫 항목은 없다")
  void distanceFromPreviousUsesPoiCoordinates() {
    rolledBack(
        () -> {
          long q1 = poi("d1", "시험 거리 하나", "카페", "food", "부산", 0);
          long q2 = poi("d2", "시험 거리 둘", "카페", "food", "부산", 200);
          long course = course(1);
          store.replace(
              course, replace(day(poiItem(q1).dwellMinutes(60), poiItem(q2).dwellMinutes(60))));

          List<CourseItem> items = items(course);
          assertThat(items.get(0).getDistanceMetersFromPrevious()).isNull();
          assertThat(items.get(1).getDistanceMetersFromPrevious()).isNotNull();
          assertThat(items.get(1).getDistanceMetersFromPrevious()).isBetween(180, 220);
          return null;
        });
  }

  @Test
  @DisplayName("촬영지·편의시설·핀이 한 일차에 섞여도 각자의 source 로 순서대로 나온다")
  void mixedSourcesInOneDay() {
    rolledBack(
        () -> {
          long p = place("mix", "시험 섞기 촬영지", 0);
          long q = poi("mix", "시험 섞기 편의시설", "카페", "food", "부산", 100);
          long course = course(1);
          store.replace(
              course,
              replace(
                  day(
                      new CourseItemInput().placeId(p).dwellMinutes(60),
                      poiItem(q).dwellMinutes(60),
                      new CourseItemInput()
                          .dwellMinutes(60)
                          .customPin(
                              new com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput(
                                  "시험 숙소",
                                  com.mz2az.scenetrip.sceneapi.api.model.PinCategory.LODGING,
                                  LAT,
                                  LNG + 300 / METERS_PER_LNG_DEGREE)))));

          assertThat(items(course))
              .extracting(CourseItem::getSource)
              .containsExactly(
                  CourseItemSource.PLACE, CourseItemSource.POI, CourseItemSource.CUSTOM_PIN);
          assertThat(items(course).get(1).getDistanceMetersFromPrevious()).isBetween(90, 110);
          return null;
        });
  }

  // ── 체류 기본값 (§4, scenetrip.course.dwell.by-poi-group) ─────────────────────

  @Test
  @DisplayName("체류시간을 비우면 편의시설 갈래별 기본값 — food 60 · sight 60 · stay 30 · transit 15")
  void dwellDefaultsByPoiGroup() {
    rolledBack(
        () -> {
          long food = poi("gf", "시험 음식", "한식", "food", "부산", 0);
          long sight = poi("gs", "시험 명소", "박물관", "sight", "부산", 30);
          long stay = poi("gt", "시험 숙박", "호텔", "stay", "부산", 60);
          long transit = poi("gr", "시험 교통", "지하철역", "transit", "부산", 90);
          long course = course(1);

          store.replace(
              course, replace(day(poiItem(food), poiItem(sight), poiItem(stay), poiItem(transit))));

          assertThat(items(course))
              .extracting(CourseItem::getDwellMinutes)
              .containsExactly(60, 60, 30, 15);
          return null;
        });
  }

  @Test
  @DisplayName("보낸 체류시간이 있으면 갈래 기본값을 덮어쓰지 않는다")
  void explicitDwellWinsForPoi() {
    rolledBack(
        () -> {
          long transit = poi("ex", "시험 교통 명시", "지하철역", "transit", "부산", 0);
          long course = course(1);
          store.replace(course, replace(day(poiItem(transit).dwellMinutes(120))));
          assertThat(onlyItem(course).getDwellMinutes()).isEqualTo(120);
          return null;
        });
  }

  @Test
  @DisplayName("옮기면서 체류시간을 비우면 편의시설 갈래 기본값으로 돌아간다")
  void movedPoiItemWithoutDwellGetsGroupDefault() {
    rolledBack(
        () -> {
          long stay = poi("mv", "시험 숙박 옮김", "호텔", "stay", "부산", 0);
          long course = course(1);
          store.replace(course, replace(day(poiItem(stay).dwellMinutes(120))));
          long id = onlyItem(course).getId();

          store.replace(course, replace(day(poiItem(stay).id(id))));

          assertThat(onlyItem(course).getDwellMinutes()).isEqualTo(30);
          return null;
        });
  }

  @Test
  @DisplayName("연결된 편의시설을 체류시간 없이 담으면 촬영지 유형의 기본값이 붙는다(촬영지로 저장되므로)")
  void linkedPoiGetsPlaceTypeDwell() {
    rolledBack(
        () -> {
          long p = place("ldw", "시험 연결 체류", 0);
          long q = poi("ldw", "시험 연결 체류", "지하철역", "transit", "부산", 1);
          link(q, p);
          long course = course(1);

          store.replace(course, replace(day(poiItem(q))));

          // 촬영지 유형 「시험촬영지」 = 45. transit 15 가 아니다.
          assertThat(onlyItem(course).getDwellMinutes()).isEqualTo(45);
          return null;
        });
  }

  // ── 길찾기 목적지 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("항목 좌표 — 편의시설이면 편의시설의 좌표를 준다")
  void itemLocationOfPoi() {
    rolledBack(
        () -> {
          long q = poi("loc", "시험 좌표", "카페", "food", "부산", 500);
          long course = course(1);
          store.replace(course, replace(day(poiItem(q).dwellMinutes(60))));
          long itemId = onlyItem(course).getId();

          var loc = store.findItemLocation(course, itemId);

          assertThat(loc).isPresent();
          assertThat(loc.get().latitude()).isCloseTo(LAT, within(1e-6));
          assertThat(loc.get().longitude())
              .isCloseTo(LNG + 500 / METERS_PER_LNG_DEGREE, within(1e-6));
          return null;
        });
  }

  // ── 거들기 ──────────────────────────────────────────────────────────────────

  private UUID user;

  private UUID user() {
    return user;
  }

  /** 트랜잭션 안에서 사용자를 만들고 일을 돌린 뒤 통째로 되돌린다. */
  private void rolledBack(java.util.function.Supplier<Void> work) {
    IntegrationDatabase.rolledBack(
        () -> {
          user = users.resolve(UUID.randomUUID());
          return work.get();
        });
  }

  private long course(int days) {
    return store.create(user, new CourseCreate(days, CourseOrigin.SELF));
  }

  private long poi(
      String source, String name, String category, String group, String address, double dxMeters) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group, address)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :c, :g, :a)
            RETURNING id
            """)
        .param("s", "it-course-poi-" + tag + "-" + source)
        .param("n", name)
        .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
        .param("lat", LAT)
        .param("c", category)
        .param("g", group)
        .param("a", address)
        .query(Long.class)
        .single();
  }

  private long place(String key, String name, double dxMeters) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key) VALUES ('시험촬영지',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :k) RETURNING id")
            .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
            .param("lat", LAT)
            .param("k", "it-course-poi-" + tag + "-" + key)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name, address) VALUES (:p, 'ko', :n, '부산')")
        .param("p", id)
        .param("n", name)
        .update();
    return id;
  }

  private static void link(long poiId, long placeId) {
    jdbc.sql("INSERT INTO place_poi_link (poi_id, place_id, method) VALUES (:q, :p, 'manual')")
        .param("q", poiId)
        .param("p", placeId)
        .update();
  }

  private static void close(long poiId) {
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", poiId).update();
  }

  private static void image(long poiId, String url, int sortOrder) {
    jdbc.sql("INSERT INTO poi_image (poi_id, url, sort_order) VALUES (:q, :u, :s)")
        .param("q", poiId)
        .param("u", url)
        .param("s", sortOrder)
        .update();
  }

  private static CourseItemInput poiItem(long poiId) {
    return new CourseItemInput().poiId(poiId);
  }

  private static CourseReplace replace(CourseDayInput... days) {
    return new CourseReplace("편의시설 시험 코스", List.of(days));
  }

  private static CourseDayInput day(CourseItemInput... items) {
    return new CourseDayInput(List.of(items));
  }

  private List<CourseItem> items(long courseId) {
    return store.find(user, courseId, Lang.KO).orElseThrow().getDays().stream()
        .flatMap(d -> d.getItems().stream())
        .toList();
  }

  private CourseItem onlyItem(long courseId) {
    List<CourseItem> items = items(courseId);
    assertThat(items).hasSize(1);
    return items.get(0);
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
