package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 폐업 표시(V18 {@code closed_at})된 POI 는 목록·개수·상세·있는 id 어디에도 나오지
 * 않는다(docs/project/plans/poi-i18n-image.md §8-1). 픽스처 둘을 바다 위 한 점(영업 하나, 폐업 하나)에 만들고 끝나면 되돌린다.
 */
@DisplayName("PoiStore — 폐업 POI 는 숨긴다")
class PoiClosedIntegrationTest {
  private static final Bbox SPOT = new Bbox(126.0999, 33.0999, 126.1001, 33.1001);

  private static JdbcClient jdbc;
  private static PoiStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new PoiStore(jdbc);
  }

  private record Seen(
      List<String> listed, int total, boolean detailOpen, boolean detailClosed, List<Long> ids) {}

  private static Seen look() {
    return IntegrationDatabase.rolledBack(
        () -> {
          long open = insert("closed-test-open", null);
          long closed = insert("closed-test-closed", "now()");
          PoiStore.Page page =
              store.list(
                  new PoiStore.Criteria(
                      SPOT, null, null, null, null, PoiStore.Sort.ALPHABETICAL, 50, 0));
          return new Seen(
              page.items().stream().map(PoiSummary::getName).toList(),
              page.total(),
              store.findDetail(open, null, null).isPresent(),
              store.findDetail(closed, null, null).isPresent(),
              store.existingIds(List.of(open, closed)).stream().sorted().toList());
        });
  }

  private static long insert(String sourceId, String closedAt) {
    return jdbc.sql(
            "INSERT INTO poi (source_id, name, geom, category, category_group, closed_at)"
                + " VALUES (:s, :s, ST_SetSRID(ST_MakePoint(126.1, 33.1), 4326)::geography,"
                + " '한식', 'food', "
                + (closedAt == null ? "NULL" : closedAt)
                + ") RETURNING id")
        .param("s", sourceId)
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("목록과 개수에 폐업 POI 가 없다")
  void listSkipsClosed() {
    Seen seen = look();
    assertThat(seen.listed()).containsExactly("closed-test-open");
    assertThat(seen.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("폐업 POI 의 상세는 없다(404 의 근거), 영업 중인 것은 있다")
  void detailHidesClosed() {
    Seen seen = look();
    assertThat(seen.detailOpen()).isTrue();
    assertThat(seen.detailClosed()).isFalse();
  }

  @Test
  @DisplayName("있는 id 에 폐업 POI 는 들지 않는다 — 여럿 카드 조회에서 「없는 id」 가 된다")
  void existingIdsSkipsClosed() {
    Seen seen = look();
    assertThat(seen.ids()).hasSize(1);
  }
}
