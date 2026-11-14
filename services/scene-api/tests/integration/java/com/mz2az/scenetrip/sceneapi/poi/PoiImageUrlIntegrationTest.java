package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiImage;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code PoiSummary.imageUrl} 과 {@code GET /pois} 의 {@code hasImage}(계약 1.31.0,
 * docs/project/plans/keep-poi.md §1-5b)를 실제 DB 에서 본다.
 *
 * <p>{@code imageUrl} 은 상세({@code GET /pois/{poiId}})가 주는 사진의 첫 장이다. 상세는 {@code
 * poi_image_visible}(V38 — 표출 중단된 관광공사 번호의 사진은 뺀 뷰)을 {@code sort_order, id} 순으로 읽는다. 그래서 기대값은 「보이는
 * 사진 중 sort_order 가 가장 작고, 같으면 id 가 작은 것」 이고, 시험마다 상세의 {@code images[0].url} 과도 맞춰 본다.
 *
 * <p>{@link PoiStore.Criteria} 의 마지막 칸 {@code Boolean hasImage} — {@code TRUE} 면 위 규칙으로 사진이 있는 것만,
 * 목록 모든 갈래와 개수에. 바다 위 한 점에 픽스처를 만들고 끝나면 되돌린다.
 */
@DisplayName("PoiStore — 대표 사진 imageUrl · hasImage")
class PoiImageUrlIntegrationTest {

  private static final double LNG = 124.61;
  private static final double LAT = 33.27;
  private static final Bbox SPOT = new Bbox(LNG - 0.001, LAT - 0.001, LNG + 0.001, LAT + 0.001);

  private static JdbcClient jdbc;
  private static PoiStore store;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new PoiStore(jdbc);
  }

  /** 열린 편의시설 일곱 — 사진이 보이는 것 다섯, 안 보이는 것 둘. 폐업 하나는 어디에도 안 나온다. */
  private record Ids(
      long twoImages,
      long tie,
      long shop,
      long food,
      long noImage,
      long hiddenOnly,
      long mixed,
      long closed) {}

  private static final int OPEN = 7;
  private static final int WITH_IMAGE = 5;

  private Ids seed() {
    long twoImages = poi("사진둘", "관광지", "sight", 0, false);
    // 넣는 순서와 정렬 순서를 반대로 — id 순이 아니라 sort_order 순인지.
    image(twoImages, "two-second", 20, null);
    image(twoImages, "two-first", 10, null);

    long tie = poi("사진동순", "관광지", "sight", 1, false);
    // sort_order 가 같으면 id 가 작은(먼저 넣은) 것.
    image(tie, "tie-a", 5, null);
    image(tie, "tie-b", 5, null);

    long shop = poi("사진쇼핑", "쇼핑", "sight", 2, false);
    image(shop, "shop", 0, null);

    long food = poi("사진식당", "한식", "food", 3, false);
    image(food, "food", 0, null);

    long noImage = poi("사진없음", "관광지", "sight", 4, false);

    // 표출 중단된 관광공사 번호의 사진뿐 — 상세에 사진이 없으므로 imageUrl 도 없다.
    long hiddenOnly = poi("사진숨김뿐", "관광지", "sight", 5, false);
    String hiddenNo = "img" + tag + "h";
    source("tour-" + hiddenNo, hiddenOnly, false);
    image(hiddenOnly, "hidden-only", 0, hiddenNo);

    // 앞자리 사진은 숨김, 뒷자리 사진은 보인다 — 보이는 것 중 첫 장.
    long mixed = poi("사진섞임", "관광지", "sight", 6, false);
    String mixedNo = "img" + tag + "m";
    source("tour-" + mixedNo, mixed, false);
    image(mixed, "mixed-hidden", 1, mixedNo);
    image(mixed, "mixed-visible", 9, null);

    long closed = poi("사진폐업", "관광지", "sight", 7, true);
    image(closed, "closed", 0, null);

    return new Ids(twoImages, tie, shop, food, noImage, hiddenOnly, mixed, closed);
  }

  private long poi(String name, String category, String group, int step, boolean closed) {
    return jdbc.sql(
            "INSERT INTO poi (source_id, name, geom, category, category_group, closed_at)"
                + " VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,"
                + " :c, :g, "
                + (closed ? "now()" : "NULL")
                + ") RETURNING id")
        .param("s", "it-poi-image-" + tag + "-" + name)
        .param("n", name)
        .param("lng", LNG - 0.0005 + step * 0.0001)
        .param("lat", LAT)
        .param("c", category)
        .param("g", group)
        .query(Long.class)
        .single();
  }

  private String url(String name) {
    return "https://img.example/it-" + tag + "/" + name + ".jpg";
  }

  private void image(long poiId, String name, int sort, String tourContentId) {
    jdbc.sql(
            "INSERT INTO poi_image (poi_id, url, sort_order, tour_content_id)"
                + " VALUES (:p, :u, :o, :c)")
        .param("p", poiId)
        .param("u", url(name))
        .param("o", sort)
        .param("c", tourContentId)
        .update();
  }

  private static void source(String sourceId, long poiId, boolean active) {
    jdbc.sql(
            "INSERT INTO poi_source (source_id, poi_id, source, active) VALUES (:s, :p, 'tour',"
                + " :a)")
        .param("s", sourceId)
        .param("p", poiId)
        .param("a", active)
        .update();
  }

  private static PoiStore.Criteria criteria(
      PoiCategoryGroup group,
      List<String> categories,
      Boolean hasImage,
      PoiStore.Sort sort,
      Integer spreadZoom,
      int limit) {
    return new PoiStore.Criteria(
        SPOT,
        null,
        null,
        null,
        group,
        sort,
        limit,
        0,
        Lang.KO,
        null,
        spreadZoom,
        categories,
        hasImage);
  }

  private static PoiStore.Criteria alphabetical(Boolean hasImage, int limit) {
    return criteria(null, null, hasImage, PoiStore.Sort.ALPHABETICAL, null, limit);
  }

  private record Seen<T>(Ids ids, T value) {}

  private <T> Seen<T> seeded(Function<Ids, T> work) {
    return IntegrationDatabase.rolledBack(
        () -> {
          Ids ids = seed();
          return new Seen<>(ids, work.apply(ids));
        });
  }

  private static Map<Long, URI> imageUrls(PoiStore.Page page) {
    Map<Long, URI> byId = new HashMap<>();
    page.items().forEach(s -> byId.put(s.getId(), s.getImageUrl()));
    return byId;
  }

  private static List<String> names(PoiStore.Page page) {
    return page.items().stream().map(PoiSummary::getName).toList();
  }

  // ───────────── imageUrl ─────────────

  @Test
  @DisplayName("목록의 imageUrl — 보이는 사진 중 sort_order·id 순 첫 장, 없거나 다 숨김이면 null")
  void listImageUrlIsFirstVisibleImage() {
    Seen<PoiStore.Page> seen = seeded(ids -> store.list(alphabetical(null, 50)));
    Ids ids = seen.ids();
    Map<Long, URI> urls = imageUrls(seen.value());

    assertThat(seen.value().total()).isEqualTo(OPEN);
    assertThat(urls.get(ids.twoImages())).isEqualTo(URI.create(url("two-first")));
    assertThat(urls.get(ids.tie())).isEqualTo(URI.create(url("tie-a")));
    assertThat(urls.get(ids.shop())).isEqualTo(URI.create(url("shop")));
    assertThat(urls.get(ids.food())).isEqualTo(URI.create(url("food")));
    assertThat(urls.get(ids.mixed())).isEqualTo(URI.create(url("mixed-visible")));
    assertThat(urls).containsEntry(ids.noImage(), null);
    assertThat(urls).containsEntry(ids.hiddenOnly(), null);
    assertThat(urls).doesNotContainKey(ids.closed());
  }

  @Test
  @DisplayName("목록의 imageUrl 은 상세(GET /pois/{id})가 주는 사진의 첫 장과 같은 주소다 — 상세의 imageUrl 도 같다")
  void listImageUrlMatchesDetailFirstImage() {
    record Pair(Long id, URI listed, URI detailFirst, URI detailImageUrl) {}
    Seen<List<Pair>> seen =
        seeded(
            ids ->
                store.list(alphabetical(null, 50)).items().stream()
                    .map(
                        s -> {
                          PoiDetail d =
                              store.findDetail(s.getId(), Lang.KO, null, null).orElseThrow().poi();
                          List<PoiImage> images = d.getImages();
                          return new Pair(
                              s.getId(),
                              s.getImageUrl(),
                              images.isEmpty() ? null : images.get(0).getUrl(),
                              d.getImageUrl());
                        })
                    .toList());

    assertThat(seen.value()).hasSize(OPEN);
    for (Pair p : seen.value()) {
      assertThat(p.listed()).as("poi %d 목록 vs 상세 첫 장", p.id()).isEqualTo(p.detailFirst());
      assertThat(p.detailImageUrl()).as("poi %d 상세 imageUrl", p.id()).isEqualTo(p.detailFirst());
    }
  }

  // ───────────── hasImage ─────────────

  @Test
  @DisplayName("hasImage=TRUE — 보이는 사진이 있는 것만, total 도 거른 뒤의 수")
  void hasImageKeepsOnlyWithImage() {
    Seen<PoiStore.Page> seen = seeded(ids -> store.list(alphabetical(true, 50)));

    assertThat(names(seen.value()))
        .containsExactlyInAnyOrder("사진둘", "사진동순", "사진쇼핑", "사진식당", "사진섞임");
    assertThat(seen.value().items()).allSatisfy(s -> assertThat(s.getImageUrl()).isNotNull());
    assertThat(seen.value().total()).isEqualTo(WITH_IMAGE);
  }

  @Test
  @DisplayName("hasImage=FALSE·null 은 거르지 않는다")
  void falseOrNullDoesNotFilter() {
    Seen<List<Integer>> seen =
        seeded(
            ids ->
                List.of(
                    store.list(alphabetical(false, 50)).total(),
                    store.list(alphabetical(null, 50)).total()));

    assertThat(seen.value()).containsExactly(OPEN, OPEN);
  }

  @Test
  @DisplayName("상한 1(이름순) — 개수 질의도 hasImage 로 센다")
  void countQueryRespectsHasImage() {
    Seen<PoiStore.Page> seen = seeded(ids -> store.list(alphabetical(true, 1)));

    assertThat(seen.value().items()).hasSize(1);
    assertThat(seen.value().items().get(0).getImageUrl()).isNotNull();
    assertThat(seen.value().total()).isEqualTo(WITH_IMAGE);
  }

  @Test
  @DisplayName("sort=spread — 거른 것만, 상한 1 일 때 개수도")
  void spreadRespectsHasImage() {
    Seen<List<PoiStore.Page>> seen =
        seeded(
            ids ->
                List.of(
                    store.list(criteria(null, null, true, PoiStore.Sort.SPREAD, 14, 50)),
                    store.list(criteria(null, null, true, PoiStore.Sort.SPREAD, 14, 1))));

    PoiStore.Page all = seen.value().get(0);
    PoiStore.Page one = seen.value().get(1);
    assertThat(all.items()).hasSize(WITH_IMAGE);
    assertThat(all.items()).allSatisfy(s -> assertThat(s.getImageUrl()).isNotNull());
    assertThat(one.items()).hasSize(1);
    assertThat(one.total()).isEqualTo(WITH_IMAGE);
  }

  @Test
  @DisplayName("화면 네모 + 기준점 + 거리순(평면 KNN 경로) — 거른 것만, 개수도")
  void viewportDistanceRespectsHasImage() {
    Seen<PoiStore.Page> seen =
        seeded(
            ids ->
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
                        null,
                        true)));

    assertThat(seen.value().items()).hasSize(1);
    assertThat(seen.value().items().get(0).getImageUrl()).isNotNull();
    assertThat(seen.value().total()).isEqualTo(WITH_IMAGE);
  }

  @Test
  @DisplayName("반경 검색(네모 없음) 경로 — 거른 것만, 개수도")
  void radiusRespectsHasImage() {
    Seen<PoiStore.Page> seen =
        seeded(
            ids ->
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
                        null,
                        true)));

    assertThat(seen.value().items()).hasSize(1);
    assertThat(seen.value().items().get(0).getImageUrl()).isNotNull();
    assertThat(seen.value().total()).isEqualTo(WITH_IMAGE);
  }

  @Test
  @DisplayName("categories·categoryGroup 과 함께 — 모두 맞는 것만")
  void combinesWithCategoriesAndGroup() {
    Seen<List<PoiStore.Page>> seen =
        seeded(
            ids ->
                List.of(
                    store.list(
                        criteria(null, List.of("쇼핑"), true, PoiStore.Sort.ALPHABETICAL, null, 50)),
                    store.list(
                        criteria(
                            PoiCategoryGroup.FOOD,
                            null,
                            true,
                            PoiStore.Sort.ALPHABETICAL,
                            null,
                            50)),
                    store.list(
                        criteria(
                            PoiCategoryGroup.SIGHT,
                            List.of("관광지"),
                            true,
                            PoiStore.Sort.ALPHABETICAL,
                            null,
                            50))));

    assertThat(names(seen.value().get(0))).containsExactly("사진쇼핑");
    assertThat(seen.value().get(0).total()).isEqualTo(1);
    assertThat(names(seen.value().get(1))).containsExactly("사진식당");
    assertThat(seen.value().get(1).total()).isEqualTo(1);
    assertThat(names(seen.value().get(2))).containsExactlyInAnyOrder("사진둘", "사진동순", "사진섞임");
    assertThat(seen.value().get(2).total()).isEqualTo(3);
  }
}
