package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import java.util.List;
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
