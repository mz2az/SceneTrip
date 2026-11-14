package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code GET /pois} 의 {@code categories}(계약 1.31.0, docs/project/plans/keep-poi.md §1-5) — {@link
 * PoiStore.Criteria#categories()} 가 목록·{@code total} 에 같은 조건으로 걸리는지 실제 DB 에서 본다.
 *
 * <p>Criteria 의 마지막 칸이 {@code List<String> categories} 다(null 이면 거르지 않는다). 바다 위 한 점에 편의시설 다섯을 만들고
 * 끝나면 되돌린다: 쇼핑(sight) 둘, 관광지(sight) 하나, 한식(food) 하나, 그리고 갈래와 함께 거르는지 보려고 일부러 food 갈래에 둔 쇼핑 하나. 폐업한
 * 쇼핑도 하나 있다 — 걸러도 폐업은 여전히 빠진다.
 */
@DisplayName("PoiStore — 세부 종류(categories)로 거르기")
class PoiCategoriesIntegrationTest {

  private static final double LNG = 124.27;
  private static final double LAT = 33.43;
  private static final Bbox SPOT = new Bbox(LNG - 0.001, LAT - 0.001, LNG + 0.001, LAT + 0.001);

  private static JdbcClient jdbc;
  private static PoiStore store;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new PoiStore(jdbc);
  }

  private void seed() {
    poi("쇼핑가", "쇼핑", "sight", 0, false);
    poi("쇼핑나", "쇼핑", "sight", 1, false);
    poi("관광지가", "관광지", "sight", 2, false);
    poi("한식가", "한식", "food", 3, false);
    poi("쇼핑음식갈래", "쇼핑", "food", 4, false);
    poi("쇼핑폐업", "쇼핑", "sight", 5, true);
  }

  /** 같은 점에 겹치지 않게 서쪽→동쪽으로 조금씩 옮겨 놓는다(spread 칸이 같아도 이름순·거리와 무관). */
  private void poi(String name, String category, String group, int step, boolean closed) {
    jdbc.sql(
            "INSERT INTO poi (source_id, name, geom, category, category_group, closed_at)"
                + " VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,"
                + " :c, :g, "
                + (closed ? "now()" : "NULL")
                + ")")
        .param("s", "it-poi-categories-" + tag + "-" + name)
        .param("n", name)
        .param("lng", LNG - 0.0005 + step * 0.0001)
        .param("lat", LAT)
        .param("c", category)
        .param("g", group)
        .update();
  }

  private static PoiStore.Criteria criteria(
      PoiCategoryGroup group, List<String> categories, PoiStore.Sort sort, Integer spreadZoom) {
    return new PoiStore.Criteria(
        SPOT, null, null, null, group, sort, 50, 0, Lang.KO, null, spreadZoom, categories);
  }

  /** 상한 1 — 첫 페이지가 상한을 채워 개수 질의(COUNT)가 따로 돈다. 그래야 total 이 목록과 같은 조건을 세는지 보인다. */
  private static PoiStore.Criteria onePerPage(
      PoiCategoryGroup group, List<String> categories, PoiStore.Sort sort, Integer spreadZoom) {
    return new PoiStore.Criteria(
        SPOT, null, null, null, group, sort, 1, 0, Lang.KO, null, spreadZoom, categories);
  }

  private static PoiStore.Criteria criteria(PoiCategoryGroup group, List<String> categories) {
    return criteria(group, categories, PoiStore.Sort.ALPHABETICAL, null);
  }

  private <T> T seeded(Supplier<T> work) {
    return IntegrationDatabase.rolledBack(
        () -> {
          seed();
          return work.get();
        });
  }

  private static List<String> names(PoiStore.Page page) {
    return page.items().stream().map(PoiSummary::getName).toList();
  }

  @Test
  @DisplayName("거르지 않으면 열린 다섯이 다 나온다 — 픽스처가 제자리에 있는지")
  void withoutCategoriesAllOpenPois() {
    PoiStore.Page page = seeded(() -> store.list(criteria(null, null)));

    assertThat(page.total()).isEqualTo(5);
    assertThat(names(page)).containsExactlyInAnyOrder("쇼핑가", "쇼핑나", "관광지가", "한식가", "쇼핑음식갈래");
  }

  @Test
  @DisplayName("categories=[쇼핑] — 쇼핑만, 갈래와 무관하게, 폐업은 빠진다, total 은 거른 뒤의 수")
  void shoppingOnly() {
    PoiStore.Page page = seeded(() -> store.list(criteria(null, List.of("쇼핑"))));

    assertThat(names(page)).containsExactlyInAnyOrder("쇼핑가", "쇼핑나", "쇼핑음식갈래");
    assertThat(page.items()).extracting(PoiSummary::getCategory).containsOnly("쇼핑");
    assertThat(page.total()).isEqualTo(3);
  }

  @Test
  @DisplayName("categoryGroup=sight 와 함께 — 둘 다 맞는 것만")
  void shoppingWithinSightGroup() {
    PoiStore.Page page = seeded(() -> store.list(criteria(PoiCategoryGroup.SIGHT, List.of("쇼핑"))));

    assertThat(names(page)).containsExactlyInAnyOrder("쇼핑가", "쇼핑나");
    assertThat(page.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("여러 종류는 그 중 하나만 맞으면 된다 — [관광지, 쇼핑] 은 한식만 빠진다")
  void severalCategoriesAreOred() {
    PoiStore.Page page = seeded(() -> store.list(criteria(null, List.of("관광지", "쇼핑"))));

    assertThat(names(page)).containsExactlyInAnyOrder("쇼핑가", "쇼핑나", "관광지가", "쇼핑음식갈래");
    assertThat(page.total()).isEqualTo(4);
  }

  @Test
  @DisplayName("없는 종류 이름은 오류가 아니라 0 건, total 0")
  void unknownCategoryIsEmpty() {
    PoiStore.Page page = seeded(() -> store.list(criteria(null, List.of("없는종류시험"))));

    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isZero();
  }

  @Test
  @DisplayName("sort=spread 에서도 거른 것만 나온다 — 칸 순위는 거른 것 안에서")
  void spreadRespectsCategories() {
    PoiStore.Page page =
        seeded(
            () ->
                store.list(
                    criteria(PoiCategoryGroup.SIGHT, List.of("쇼핑"), PoiStore.Sort.SPREAD, 14)));

    assertThat(page.items()).isNotEmpty();
    assertThat(page.items()).extracting(PoiSummary::getCategory).containsOnly("쇼핑");
    assertThat(page.items())
        .extracting(PoiSummary::getCategoryGroup)
        .containsOnly(PoiCategoryGroup.SIGHT);
    assertThat(page.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("상한 1(이름순) — 개수 질의도 같은 조건을 센다: [쇼핑] 은 total 3")
  void countQueryFiltersToo() {
    PoiStore.Page page =
        seeded(() -> store.list(onePerPage(null, List.of("쇼핑"), PoiStore.Sort.ALPHABETICAL, null)));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).getCategory()).isEqualTo("쇼핑");
    assertThat(page.total()).isEqualTo(3);
  }

  @Test
  @DisplayName("상한 1(spread) — 개수 질의도 같은 조건: [쇼핑] + sight 는 total 2")
  void spreadCountQueryFiltersToo() {
    PoiStore.Page page =
        seeded(
            () ->
                store.list(
                    onePerPage(PoiCategoryGroup.SIGHT, List.of("쇼핑"), PoiStore.Sort.SPREAD, 14)));

    assertThat(page.items()).extracting(PoiSummary::getCategory).containsOnly("쇼핑");
    assertThat(page.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("화면 네모 + 기준점 + 거리순(평면 KNN 경로)에서도 거른다")
  void viewportDistanceRespectsCategories() {
    PoiStore.Page page =
        seeded(
            () ->
                store.list(
                    new PoiStore.Criteria(
                        SPOT,
                        LAT,
                        LNG,
                        null,
                        null,
                        PoiStore.Sort.DISTANCE,
                        1,
                        0,
                        Lang.KO,
                        null,
                        null,
                        List.of("쇼핑"))));

    assertThat(page.items()).extracting(PoiSummary::getCategory).containsOnly("쇼핑");
    assertThat(page.total()).isEqualTo(3);
  }

  @Test
  @DisplayName("반경 검색(네모 없음) 경로에서도 거른다 — 목록과 개수 모두")
  void radiusRespectsCategories() {
    PoiStore.Page page =
        seeded(
            () ->
                store.list(
                    new PoiStore.Criteria(
                        null,
                        LAT,
                        LNG,
                        150,
                        null,
                        PoiStore.Sort.DISTANCE,
                        1,
                        0,
                        Lang.KO,
                        null,
                        null,
                        List.of("쇼핑"))));

    assertThat(page.items()).extracting(PoiSummary::getCategory).containsOnly("쇼핑");
    assertThat(page.total()).isEqualTo(3);
  }
}
