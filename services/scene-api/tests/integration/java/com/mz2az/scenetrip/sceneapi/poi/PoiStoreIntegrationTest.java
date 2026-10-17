package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link PoiStore} 의 SQL 을 진짜 PostgreSQL 에 태운다.
 *
 * <p>같은 테스트가 두 DB 에서 돈다 — 노트북의 kind(전량, 약 91 만 곳)와 CI 의 서비스 컨테이너(저장소 표본 29 행 → POI 20 곳). 그래서 단언은
 * <b>양쪽에서 성립하는 것</b>만 쓴다: 특정 가게가 나온다가 아니라 "거리 오름차순이다", "전부 bbox 안이다", "요청한 갈래뿐이다", "반경 안이다". 상세·있는
 * id 시험도 가게 이름을 박지 않고, 기준 bbox 안의 숙박 하나를 실행 때 고른다. 기준 자리는 표본이 뭉쳐 있는 제주 모슬포다 — 숙박 5 곳·명소 3 곳·음식 2
 * 곳·카페 1 곳이 한 bbox 에 든다.
 *
 * <p>실행 계획 단언은 표가 작으면 건너뛴다. 수십 행에서는 플래너가 인덱스보다 순차 스캔을 고르는 것이 맞고, 그것을 실패로 보면 테스트가 거짓말을 한다.
 */
@DisplayName("PoiStore — 실제 DB 질의")
class PoiStoreIntegrationTest {

  /** 제주 모슬포. 표본의 숙박 5 곳·명소 3 곳·음식 2 곳·카페 1 곳이 이 안에 있다. */
  private static final Bbox MOSEULPO = new Bbox(126.24, 33.21, 126.30, 33.26);

  /** 기준점 — 표본 모슬포 묶음의 한가운데(모슬포2호텔 자리). */
  private static final double ORIGIN_LAT = 33.21773097;

  private static final double ORIGIN_LNG = 126.25071928;

  private static JdbcClient jdbc;
  private static PoiStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requirePoiSeeded(jdbc);
    store = new PoiStore(jdbc);
  }

  @Test
  @DisplayName("뷰포트 + 중심 — 전부 bbox 안이고 거리 오름차순이다")
  void viewportSortedByDistance() {
    PoiStore.Page page =
        store.list(
            criteria(MOSEULPO, ORIGIN_LAT, ORIGIN_LNG, null, null, PoiStore.Sort.DISTANCE, 30, 0));

    assertThat(page.items()).isNotEmpty();
    assertThat(page.items()).allSatisfy(p -> assertInside(p, MOSEULPO));
    assertThat(page.items()).extracting(PoiSummary::getDistanceMeters).isSorted();
    assertThat(page.total()).isGreaterThanOrEqualTo(page.items().size());
  }

  @Test
  @DisplayName("상한에 걸리면 total 이 items 보다 크다 — 앱의 「더 확대하세요」 근거")
  void totalExceedsItemsWhenCapped() {
    PoiStore.Page page =
        store.list(
            criteria(MOSEULPO, ORIGIN_LAT, ORIGIN_LNG, null, null, PoiStore.Sort.DISTANCE, 2, 0));

    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isGreaterThan(2);
  }

  @Test
  @DisplayName("갈래로 거르면 그 갈래만 나온다")
  void filtersByCategoryGroup() {
    PoiStore.Page page =
        store.list(
            criteria(
                MOSEULPO,
                ORIGIN_LAT,
                ORIGIN_LNG,
                null,
                PoiCategoryGroup.STAY,
                PoiStore.Sort.DISTANCE,
                30,
                0));

    assertThat(page.items()).isNotEmpty();
    assertThat(page.items())
        .extracting(PoiSummary::getCategoryGroup)
        .containsOnly(PoiCategoryGroup.STAY);
  }

  @Test
  @DisplayName("반경 — ST_DWithin. 전부 그 거리 안이다")
  void filtersByRadius() {
    PoiStore.Page page =
        store.list(
            criteria(null, ORIGIN_LAT, ORIGIN_LNG, 800, null, PoiStore.Sort.DISTANCE, 30, 0));

    assertThat(page.items()).isNotEmpty();
    assertThat(page.items())
        .extracting(PoiSummary::getDistanceMeters)
        .allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(800));
  }

  @Test
  @DisplayName("이름순 — 기준점 없이. 거리는 비어 있다")
  void sortsAlphabeticallyWithoutOrigin() {
    PoiStore.Page page =
        store.list(criteria(MOSEULPO, null, null, null, null, PoiStore.Sort.ALPHABETICAL, 30, 0));

    assertThat(page.items()).isNotEmpty();
    assertThat(page.items()).extracting(PoiSummary::getName).isSorted();
    assertThat(page.items()).extracting(PoiSummary::getDistanceMeters).containsOnlyNulls();
  }

  @Test
  @DisplayName("경도 -180~180 뷰포트 — geography 로 하면 질의가 실패하던 자리")
  void handlesWorldWideViewport() {
    PoiStore.Page page =
        store.list(
            criteria(
                new Bbox(-180, -90, 180, 90),
                null,
                null,
                null,
                null,
                PoiStore.Sort.ALPHABETICAL,
                5,
                0));

    assertThat(page.items()).hasSize(5);
  }

  @Test
  @DisplayName("상세 — 목록의 같은 POI 와 이름·갈래·거리가 맞는다")
  void findsDetail() {
    PoiSummary stay = nearestStay();
    Optional<PoiDetail> detail = store.findDetail(stay.getId(), ORIGIN_LAT, ORIGIN_LNG);

    assertThat(detail).isPresent();
    assertThat(detail.get().getId()).isEqualTo(stay.getId());
    assertThat(detail.get().getName()).isNotBlank().isEqualTo(stay.getName());
    assertThat(detail.get().getCategoryGroup()).isEqualTo(PoiCategoryGroup.STAY);
    assertThat(detail.get().getDistanceMeters()).isEqualTo(stay.getDistanceMeters());
  }

  @Test
  @DisplayName("있는 id 만 골라낸다 — 여럿 카드 조회가 「없는 id」를 표시하는 근거")
  void existingIds() {
    long id = nearestStay().getId();

    assertThat(store.existingIds(java.util.List.of(id, -1L, -2L))).containsExactly(id);
    assertThat(store.existingIds(java.util.List.of())).isEmpty();
  }

  @Test
  @DisplayName("없는 id 는 빈 값 — 예외가 아니다")
  void missingDetailIsEmpty() {
    assertThat(store.findDetail(-1, null, null)).isEmpty();
  }

  @Test
  @DisplayName("거리순 목록이 인덱스를 탄다 — 순차 스캔이 없다 (표가 클 때만)")
  void distanceListUsesIndex() {
    long rows = jdbc.sql("SELECT count(*) FROM poi").query(Long.class).single();
    assumeTrue(rows >= 10_000, "표본 " + rows + " 행에서는 순차 스캔이 정답이라 계획을 단언하지 않는다");

    // 성긴 곳(모슬포, bbox 안 수백 행) — 플래너가 평면 인덱스로 거른 뒤 정렬할 수도, KNN 을 탈 수도 있다. 어느 인덱스든 된다.
    String sparse =
        store.explainList(
            criteria(MOSEULPO, ORIGIN_LAT, ORIGIN_LNG, null, null, PoiStore.Sort.DISTANCE, 30, 0));
    assertThat(sparse).as("성긴 뷰포트\n" + sparse).doesNotContain("Seq Scan on poi");

    // 빽빽한 곳(강남역 2 km, bbox 안 수천 행). 어느 인덱스를 고르는지는 플래너 몫 — 순차 스캔만 아니면 된다.
    Bbox gangnam = new Bbox(127.017, 37.489, 127.037, 37.507);
    String dense =
        store.explainList(
            criteria(gangnam, 37.498, 127.027, null, null, PoiStore.Sort.DISTANCE, 30, 0));
    assertThat(dense).as("빽빽한 뷰포트\n" + dense).doesNotContain("Seq Scan on poi");
  }

  private static PoiStore.Criteria criteria(
      Bbox bbox,
      Double lat,
      Double lng,
      Integer radius,
      PoiCategoryGroup group,
      PoiStore.Sort sort,
      int limit,
      int offset) {
    return new PoiStore.Criteria(bbox, lat, lng, radius, group, sort, limit, offset);
  }

  private static void assertInside(PoiSummary p, Bbox b) {
    assertThat(p.getLongitude()).isBetween(b.minLng(), b.maxLng());
    assertThat(p.getLatitude()).isBetween(b.minLat(), b.maxLat());
  }

  /** 기준 bbox 안에서 기준점에 가장 가까운 숙박 — 어느 DB 든 모슬포에는 숙박이 있다. */
  private static PoiSummary nearestStay() {
    PoiStore.Page page =
        store.list(
            criteria(
                MOSEULPO,
                ORIGIN_LAT,
                ORIGIN_LNG,
                null,
                PoiCategoryGroup.STAY,
                PoiStore.Sort.DISTANCE,
                1,
                0));
    assertThat(page.items()).as("모슬포 bbox 안 숙박").isNotEmpty();
    return page.items().get(0);
  }
}
