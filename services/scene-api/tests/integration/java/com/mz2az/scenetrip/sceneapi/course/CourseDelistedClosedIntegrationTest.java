package com.mz2az.scenetrip.sceneapi.course;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemSource;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PinCategory;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 코스 항목의 「목록에서 빠짐」·「폐업」 표시(scene-api 1.20.0, docs/project/plans/delisted-items.md) — 실제 DB.
 *
 * <p>명세: 촬영지 줄은 {@code place.hidden_at} 으로 {@code delisted}, 편의시설 줄은 {@code poi.closed_at} 으로
 * {@code closed} 를 싣는다. 해당 없는 칸(촬영지 줄의 {@code closed}, 편의시설 줄의 {@code delisted}, 핀 줄의 둘 다)은 없다.
 * 숨기거나 폐업해도 항목은 코스에 남는다. 값이 없으면 {@code false} 로 읽는다.
 *
 * <p>편의시설 픽스처는 적재 데이터와 겹치지 않는 바다 위에 두고, 전부 {@link IntegrationDatabase#rolledBack} 안에서 돌린다.
 */
@DisplayName("내 코스 — 촬영지 delisted · 편의시설 closed 표시 (실제 DB)")
class CourseDelistedClosedIntegrationTest {

  private static final double LAT = 34.91;
  private static final double LNG = 129.61;

  private static JdbcClient jdbc;
  private static CourseStore store;
  private static UserStore users;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private UUID user;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    dwell.setByPoiGroup("food=60, sight=60, stay=30, transit=15");
    store =
        new CourseStore(
            jdbc, DeployedTravel.estimator(), dwell, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
  }

  // ── 촬영지 줄 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("보이는 촬영지 줄 — delisted 는 false(또는 없음), closed 는 없음")
  void visiblePlaceRow() {
    rolledBack(
        () -> {
          long p = visiblePlace();
          long c = course(1);
          store.replace(c, replace(day(placeItem(p))));

          CourseItem item = onlyItem(c);
          assertThat(item.getSource()).isEqualTo(CourseItemSource.PLACE);
          assertThat(item.getDelisted()).isNotEqualTo(Boolean.TRUE);
          assertThat(item.getClosed()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("담은 뒤 촬영지를 숨기면 줄이 남고 delisted 가 true, closed 는 없음")
  void hiddenPlaceRowIsKeptAndDelisted() {
    rolledBack(
        () -> {
          long p = visiblePlace();
          long c = course(1);
          store.replace(c, replace(day(placeItem(p))));

          hidePlace(p);

          CourseItem item = onlyItem(c);
          assertThat(item.getPlaceId()).isEqualTo(p);
          assertThat(item.getDelisted()).isTrue();
          assertThat(item.getClosed()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("숨김이 풀리면 촬영지 줄의 delisted 가 다시 false(또는 없음)")
  void unhidingPlaceClearsFlag() {
    rolledBack(
        () -> {
          long p = visiblePlace();
          long c = course(1);
          store.replace(c, replace(day(placeItem(p))));
          hidePlace(p);
          assertThat(onlyItem(c).getDelisted()).isTrue();

          jdbc.sql("UPDATE place SET hidden_at = NULL WHERE id = :id").param("id", p).update();

          assertThat(onlyItem(c).getDelisted()).isNotEqualTo(Boolean.TRUE);
          return null;
        });
  }

  @Test
  @DisplayName("숨긴 촬영지 줄을 id 와 함께 다시 저장(PUT)해도 남고 delisted 가 true")
  void hiddenPlaceRowSurvivesReplaceWithId() {
    rolledBack(
        () -> {
          long p = visiblePlace();
          long c = course(1);
          store.replace(c, replace(day(placeItem(p))));
          long itemId = onlyItem(c).getId();
          hidePlace(p);

          store.replace(c, replace(day(placeItem(p).id(itemId))));

          CourseItem item = onlyItem(c);
          assertThat(item.getId()).isEqualTo(itemId);
          assertThat(item.getDelisted()).isTrue();
          return null;
        });
  }

  // ── 편의시설 줄 ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("영업 중인 편의시설 줄 — closed 는 false(또는 없음), delisted 는 없음")
  void openPoiRow() {
    rolledBack(
        () -> {
          long q = poi("open", 0);
          long c = course(1);
          store.replace(c, replace(day(poiItem(q))));

          CourseItem item = onlyItem(c);
          assertThat(item.getSource()).isEqualTo(CourseItemSource.POI);
          assertThat(item.getClosed()).isNotEqualTo(Boolean.TRUE);
          assertThat(item.getDelisted()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("담은 뒤 폐업하면 편의시설 줄이 남고 closed 가 true, delisted 는 없음")
  void closedPoiRowIsKeptAndClosed() {
    rolledBack(
        () -> {
          long q = poi("closed", 0);
          long c = course(1);
          store.replace(c, replace(day(poiItem(q))));

          closePoi(q);

          CourseItem item = onlyItem(c);
          assertThat(item.getPoiId()).isEqualTo(q);
          assertThat(item.getClosed()).isTrue();
          assertThat(item.getDelisted()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("폐업한 편의시설 줄을 id 와 함께 다시 저장(PUT)해도 남고 closed 가 true")
  void closedPoiRowSurvivesReplaceWithId() {
    rolledBack(
        () -> {
          long q = poi("kept", 0);
          long c = course(1);
          store.replace(c, replace(day(poiItem(q))));
          long itemId = onlyItem(c).getId();
          closePoi(q);

          store.replace(c, replace(day(poiItem(q).id(itemId))));

          CourseItem item = onlyItem(c);
          assertThat(item.getId()).isEqualTo(itemId);
          assertThat(item.getClosed()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("폐업 표시가 풀리면 closed 가 다시 false(또는 없음)")
  void reopeningPoiClearsFlag() {
    rolledBack(
        () -> {
          long q = poi("reopen", 0);
          long c = course(1);
          store.replace(c, replace(day(poiItem(q))));
          closePoi(q);
          assertThat(onlyItem(c).getClosed()).isTrue();

          jdbc.sql("UPDATE poi SET closed_at = NULL WHERE id = :id").param("id", q).update();

          assertThat(onlyItem(c).getClosed()).isNotEqualTo(Boolean.TRUE);
          return null;
        });
  }

  // ── 핀 줄·섞인 코스·남의 데이터 ─────────────────────────────────────────────────

  @Test
  @DisplayName("직접 찍은 핀 줄에는 delisted·closed 둘 다 없다")
  void customPinRowHasNeither() {
    rolledBack(
        () -> {
          long c = course(1);
          store.replace(c, replace(day(pinItem("시험 숙소"))));

          CourseItem item = onlyItem(c);
          assertThat(item.getSource()).isEqualTo(CourseItemSource.CUSTOM_PIN);
          assertThat(item.getDelisted()).isNull();
          assertThat(item.getClosed()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("한 코스에 섞여 있으면 줄마다 자기 칸만 — 숨긴 촬영지·보이는 촬영지·폐업·영업·핀, 일차를 넘어서도")
  void mixedCourse() {
    rolledBack(
        () -> {
          List<Long> ps = visiblePlaces(2);
          long hiddenP = ps.get(0);
          long shownP = ps.get(1);
          long closedQ = poi("mix-closed", 0);
          long openQ = poi("mix-open", 200);
          long c = course(2);
          store.replace(
              c,
              replace(
                  day(placeItem(hiddenP), poiItem(closedQ), pinItem("시험 핀")),
                  day(placeItem(shownP), poiItem(openQ))));

          hidePlace(hiddenP);
          closePoi(closedQ);

          List<CourseItem> items = items(c);
          assertThat(items).hasSize(5);
          for (CourseItem item : items) {
            switch (item.getSource()) {
              case PLACE -> {
                assertThat(item.getClosed()).isNull();
                if (item.getPlaceId().equals(hiddenP)) {
                  assertThat(item.getDelisted()).isTrue();
                } else {
                  assertThat(item.getPlaceId()).isEqualTo(shownP);
                  assertThat(item.getDelisted()).isNotEqualTo(Boolean.TRUE);
                }
              }
              case POI -> {
                assertThat(item.getDelisted()).isNull();
                if (item.getPoiId().equals(closedQ)) {
                  assertThat(item.getClosed()).isTrue();
                } else {
                  assertThat(item.getPoiId()).isEqualTo(openQ);
                  assertThat(item.getClosed()).isNotEqualTo(Boolean.TRUE);
                }
              }
              default -> {
                assertThat(item.getDelisted()).isNull();
                assertThat(item.getClosed()).isNull();
              }
            }
          }
          return null;
        });
  }

  @Test
  @DisplayName("남의 코스 — 같은 숨긴 촬영지·폐업 편의시설이면 그쪽도 표시되고, 내 코스는 남에게 보이지 않는다")
  void otherUsersCourse() {
    rolledBack(
        () -> {
          long p = visiblePlace();
          long q = poi("other", 0);
          long mine = course(1);
          store.replace(mine, replace(day(placeItem(p), poiItem(q))));
          UUID stranger = users.resolve(UUID.randomUUID());
          long theirs = store.create(stranger, new CourseCreate(1, CourseOrigin.SELF));
          store.replace(theirs, replace(day(placeItem(p), poiItem(q))));

          hidePlace(p);
          closePoi(q);

          assertThat(store.find(stranger, mine, Lang.KO)).isEmpty();
          List<CourseItem> theirItems =
              store.find(stranger, theirs, Lang.KO).orElseThrow().getDays().stream()
                  .flatMap(d -> d.getItems().stream())
                  .toList();
          assertThat(theirItems).hasSize(2);
          assertThat(theirItems)
              .filteredOn(i -> i.getSource() == CourseItemSource.PLACE)
              .singleElement()
              .satisfies(i -> assertThat(i.getDelisted()).isTrue());
          assertThat(theirItems)
              .filteredOn(i -> i.getSource() == CourseItemSource.POI)
              .singleElement()
              .satisfies(i -> assertThat(i.getClosed()).isTrue());
          return null;
        });
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────

  private void rolledBack(Supplier<Void> work) {
    IntegrationDatabase.rolledBack(
        () -> {
          user = users.resolve(UUID.randomUUID());
          return work.get();
        });
  }

  private long course(int days) {
    return store.create(user, new CourseCreate(days, CourseOrigin.SELF));
  }

  private static long visiblePlace() {
    return visiblePlaces(1).get(0);
  }

  private static List<Long> visiblePlaces(int n) {
    List<Long> ids =
        jdbc.sql("SELECT id FROM place WHERE hidden_at IS NULL ORDER BY id LIMIT :n")
            .param("n", n)
            .query(Long.class)
            .list();
    assertThat(ids).as("숨기지 않은 촬영지 %d 개", n).hasSize(n);
    return ids;
  }

  /** 바다 위의 편의시설. 촬영지와 연결하지 않으므로 편의시설 항목으로 저장된다. */
  private long poi(String key, double dxMeters) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group, address)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '카페', 'food',
                    '부산 기장군 시험동')
            RETURNING id
            """)
        .param("s", "it-course-delisted-" + tag + "-" + key)
        .param("n", "시험 편의시설 " + key)
        .param("lng", LNG + dxMeters / 91_300.0)
        .param("lat", LAT)
        .query(Long.class)
        .single();
  }

  private static void hidePlace(long placeId) {
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", placeId).update();
  }

  private static void closePoi(long poiId) {
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", poiId).update();
  }

  private static CourseItemInput placeItem(long placeId) {
    return new CourseItemInput().placeId(placeId).dwellMinutes(60);
  }

  private static CourseItemInput poiItem(long poiId) {
    return new CourseItemInput().poiId(poiId).dwellMinutes(60);
  }

  private static CourseItemInput pinItem(String name) {
    return new CourseItemInput()
        .dwellMinutes(60)
        .customPin(new CustomPinInput(name, PinCategory.LODGING, LAT + 0.01, LNG));
  }

  private static CourseReplace replace(CourseDayInput... days) {
    return new CourseReplace("숨김 표시 시험 코스", List.of(days));
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
}
