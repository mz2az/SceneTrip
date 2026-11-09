package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.poi.PoiStores;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinksForTests;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 편의시설 목록 {@code GET /v1/pois} 의 골고루 정렬({@code sort=spread}, MZ2AZ-540)과 이름 검색({@code q},
 * MZ2AZ-283) — 계약 scene-api 1.23.0, docs/project/plans/poi-spread-search.md §2·§4. 진짜 PoiStore 와 진짜
 * PostgreSQL 위에서 명세만 보고 짠 시험.
 *
 * <p>칸: 웹 메르카토르 타일 {@code spreadZoom + 2} 단계. 이 시험은 {@code spreadZoom = 10}(칸 = 12 단계 타일)으로 묻는다.
 * 픽스처는 적재 데이터가 없는 동해 한가운데(울릉도 남쪽 바다) 네 칸에 둔다 — 칸 번호는 아래 {@link #cell} 로 계산해 시험 앞머리에서 픽스처 설계를 먼저
 * 확인한다(칸 경계에서 멀리 둔 점들이다).
 *
 * <pre>
 *   A1·A2 (36.50, 131.170 / 131.180) → 칸 (3540, 1601)
 *   B     (36.50, 131.260)           → 칸 (3541, 1601)
 *   C     (36.44, 131.260)           → 칸 (3541, 1602)
 *   D     (36.44, 131.170)           → 칸 (3540, 1602)
 * </pre>
 *
 * <p>시험마다 {@link IntegrationDatabase#rolledBack} 안에서 행을 넣고 묻는다 — 끝나면 되돌린다.
 */
@DisplayName("편의시설 목록 — 골고루 정렬(spread)·이름 검색(q) (실제 DB)")
class PoiSpreadSearchIntegrationTest {

  private static final String SEA_BBOX = "131.10,36.40,131.35,36.60";
  private static final int SPREAD_ZOOM = 10;
  private static final int CELL_Z = SPREAD_ZOOM + 2;

  private static final double[] A1 = {36.50, 131.170};
  private static final double[] A2 = {36.50, 131.180};
  private static final double[] B = {36.50, 131.260};
  private static final double[] C = {36.44, 131.260};
  private static final double[] D = {36.44, 131.170};

  /** SEA_BBOX 밖의 바다 한 점(동해 남쪽). q 의 「영역이 있으면 그 안에서만」 을 보려고 둔다. */
  private static final double[] OUTSIDE = {35.50, 131.20};

  private static JdbcClient jdbc;

  private final PoisController pois =
      new PoisController(
          PoiStores.create(jdbc),
          null,
          new ReviewViews(
              new ReviewStore(jdbc, IntegrationDatabase.transactions()), new NoPhotos()),
          NaverLinksForTests.disabled(jdbc));

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    long already =
        jdbc.sql(
                "SELECT count(*) FROM poi WHERE ST_Intersects(geom::geometry,"
                    + " ST_MakeEnvelope(131.10, 36.40, 131.35, 36.60, 4326))")
            .query(Long.class)
            .single();
    assertThat(already).as("시험 바다 영역에 이미 POI 가 있으면 픽스처만 보는 단언이 깨진다").isZero();
  }

  // ───────────── 칸 계산(명세의 식 그대로) ─────────────

  private static String cell(double[] latLng) {
    double lat = latLng[0];
    double lng = latLng[1];
    double n = Math.pow(2, CELL_Z);
    long cx = (long) Math.floor((lng + 180) / 360 * n);
    double r = Math.toRadians(lat);
    long cy = (long) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n);
    return cx + "," + cy;
  }

  @Test
  @DisplayName("픽스처 설계 확인 — A1·A2 는 같은 칸, A·B·C·D 는 서로 다른 칸")
  void fixtureCellsAreAsDesigned() {
    assertThat(cell(A1)).isEqualTo(cell(A2)).isEqualTo("3540,1601");
    assertThat(List.of(cell(A1), cell(B), cell(C), cell(D))).doesNotHaveDuplicates();
  }

  // ───────────── 골고루 정렬 ─────────────

  @Test
  @DisplayName("같은 칸 두 곳 — 인기도 높은 쪽이 spreadRank 1, 다른 쪽이 2. 다른 칸 혼자인 곳은 1 이고 모든 2등보다 먼저 온다")
  void spreadRanksWithinCellAndOrdersRanksFirst() {
    record Ids(long a1, long a2, long b, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long a1 = poi("spread-a1", A1, "food", 50);
              long a2 = poi("spread-a2", A2, "food", 10);
              // B 의 인기도는 A2 보다 낮다 — 그래도 칸 1등이라 A2(칸 2등)보다 먼저다.
              long b = poi("spread-b", B, "food", 1);
              return new Ids(a1, a2, b, spread(SEA_BBOX, 200));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.a1(), r.b(), r.a2());
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsExactly(1, 1, 2);
  }

  @Test
  @DisplayName("칸 1등들끼리도 칸 안 순서(인기도 큰 순)로 늘어선다 — 1등 묶음 → 2등 묶음")
  void winnersOrderedByInCellOrderingThenRunnersUp() {
    record Ids(List<Long> expected, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long a1 = poi("spread-o-a1", A1, "food", 40);
              long a2 = poi("spread-o-a2", A2, "food", 35);
              long c1 = poi("spread-o-c1", C, "food", 30);
              long c2 = poi("spread-o-c2", C, "food", 25);
              long d1 = poi("spread-o-d1", D, "food", 20);
              long d2 = poi("spread-o-d2", D, "food", 15);
              return new Ids(List.of(a1, c1, d1, a2, c2, d2), spread(SEA_BBOX, 200));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactlyElementsOf(r.expected());
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsExactly(1, 1, 1, 2, 2, 2);
  }

  @Test
  @DisplayName("인기도가 같은 한 칸 — 교통이 식당보다 먼저(교통 0 · 명소 1 · 숙소 2 · 식당·카페 3)")
  void transitBeatsFoodOnEqualPopularity() {
    record Ids(long transit, long sight, long stay, long food, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              // 먼저 넣은(작은 id) 쪽이 식당이다 — id 로 갈렸다면 식당이 이긴다.
              long food = poi("spread-g-food", A1, "food", 7);
              long stay = poi("spread-g-stay", A1, "stay", 7);
              long sight = poi("spread-g-sight", A2, "sight", 7);
              long transit = poi("spread-g-transit", A2, "transit", 7);
              return new Ids(transit, sight, stay, food, spread(SEA_BBOX, 200));
            });

    assertThat(r.items())
        .extracting(PoiSummary::getId)
        .containsExactly(r.transit(), r.sight(), r.stay(), r.food());
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsExactly(1, 2, 3, 4);
  }

  @Test
  @DisplayName("같은 요청 두 번 — 같은 순서(인기도·갈래가 다 같아도 섞은 순서가 늘 같다)")
  void spreadIsDeterministic() {
    record Two(List<Long> first, List<Long> second, List<PoiSummary> items) {}
    Two r =
        rolledBack(
            () -> {
              for (int i = 0; i < 6; i++) {
                poi("spread-det-a-" + i, i % 2 == 0 ? A1 : A2, "food", 3);
                poi("spread-det-c-" + i, C, "food", 3);
              }
              List<PoiSummary> first = spread(SEA_BBOX, 200);
              List<PoiSummary> second = spread(SEA_BBOX, 200);
              return new Two(ids(first), ids(second), first);
            });

    assertThat(r.first()).hasSize(12);
    assertThat(r.second()).containsExactlyElementsOf(r.first());
    // 두 칸이라 1등 둘 → 2등 둘 → … → 6등 둘.
    assertThat(r.items())
        .extracting(PoiSummary::getSpreadRank)
        .containsExactly(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6);
  }

  @Test
  @DisplayName("limit 이 칸 수보다 작으면 칸 1등만 — 그중 순위 높은 칸부터")
  void limitBelowCellCountReturnsOnlyWinners() {
    record Ids(long a1, long c1, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long a1 = poi("spread-l-a1", A1, "food", 40);
              poi("spread-l-a2", A2, "food", 35);
              long c1 = poi("spread-l-c1", C, "food", 30);
              poi("spread-l-c2", C, "food", 25);
              poi("spread-l-d1", D, "food", 20);
              poi("spread-l-d2", D, "food", 15);
              return new Ids(a1, c1, spread(SEA_BBOX, 2));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.a1(), r.c1());
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsOnly(1);
  }

  @Test
  @DisplayName("폐업 POI 는 골고루 정렬에 나오지 않고 칸 순위도 차지하지 않는다")
  void spreadSkipsClosed() {
    record Ids(long open, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              poiClosed("spread-closed", A1, 100);
              long open = poi("spread-open", A2, "food", 1);
              return new Ids(open, spread(SEA_BBOX, 200));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.open());
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsExactly(1);
  }

  // ───────────── 이름 검색 q ─────────────

  @Test
  @DisplayName("q — 한국어 이름에 띄어쓰기·대소문자 무시로 부분 일치. 「스타 벅스」 가 「스타벅스 강남점」 에 걸린다")
  void queryMatchesKoreanNameIgnoringSpaces() {
    record Ids(long hit, long other, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long hit = poiNamed("스타벅스 강남점", A1, null);
              long other = poiNamed("동네김밥", A2, null);
              return new Ids(hit, other, search(Lang.KO, SEA_BBOX, "스타 벅스"));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.hit());
  }

  @Test
  @DisplayName("q — 대소문자를 가리지 않는다(라틴 글자 이름)")
  void queryIgnoresCase() {
    record Ids(long hit, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long hit = poiNamed("CAFE Sealion 시험점", A1, null);
              poiNamed("동네김밥", A2, null);
              return new Ids(hit, search(Lang.KO, SEA_BBOX, "cafe sea"));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.hit());
  }

  @Test
  @DisplayName("q — 영역 없이도 된다(전국에서 찾기)")
  void queryWorksWithoutArea() {
    record Ids(long hit, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long hit = poiNamed("씬트립물개시험다방 본점", A1, null);
              return new Ids(hit, search(Lang.KO, null, "씬트립 물개시험"));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.hit());
  }

  @Test
  @DisplayName("q — 영문 번역 이름(poi_i18n)에도 걸린다")
  void queryMatchesEnglishTranslatedName() {
    record Ids(long hit, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long hit = poiNamed("씬트립해달시험분식", A1, null);
              jdbc.sql(
                      "INSERT INTO poi_i18n (poi_id, lang, name) VALUES (:id, CAST('en' AS"
                          + " lang_code), 'SceneTrip SeaOtter Test Diner')")
                  .param("id", hit)
                  .update();
              return new Ids(hit, search(Lang.EN, null, "seaotter TEST"));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.hit());
  }

  @Test
  @DisplayName("q — 읽는 법(nameRoman)에도 걸린다")
  void queryMatchesRomanizedName() {
    record Ids(long hit, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long hit = poiNamed("씬트립수달시험식당", A1, "Ssinteurip Sudalsiheom Sikdang");
              return new Ids(hit, search(Lang.KO, null, "ssinteurip sudal"));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.hit());
  }

  @Test
  @DisplayName("q + bbox — 영역 안의 것만. 영역이 없으면 밖의 것도 나온다")
  void queryWithBboxStaysInside() {
    record Ids(long inside, long outside, List<PoiSummary> boxed, List<PoiSummary> anywhere) {}
    Ids r =
        rolledBack(
            () -> {
              long inside = poiNamed("씬트립갈매기시험 횟집", A1, null);
              long outside = poiNamed("씬트립갈매기시험 횟집", OUTSIDE, null);
              return new Ids(
                  inside,
                  outside,
                  search(Lang.KO, SEA_BBOX, "씬트립갈매기시험"),
                  search(Lang.KO, null, "씬트립갈매기시험"));
            });

    assertThat(r.boxed()).extracting(PoiSummary::getId).containsExactly(r.inside());
    assertThat(r.anywhere())
        .extracting(PoiSummary::getId)
        .containsExactlyInAnyOrder(r.inside(), r.outside());
  }

  @Test
  @DisplayName("q — 폐업 POI 는 이름이 맞아도 나오지 않는다")
  void querySkipsClosed() {
    record Ids(long open, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long open = poiNamed("씬트립고래시험 국밥", A1, null);
              long closed = poiNamed("씬트립고래시험 국밥 폐업점", A2, null);
              jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id")
                  .param("id", closed)
                  .update();
              return new Ids(open, search(Lang.KO, null, "씬트립고래시험"));
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.open());
  }

  @Test
  @DisplayName("q 와 spread 를 함께 — 맞는 것만 칸 순위로")
  void queryWithSpread() {
    record Ids(long a1, long b, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long a1 = poiNamed("씬트립상어시험 카페 1호", A1, null, 9);
              poiNamed("씬트립상어시험 카페 2호", A2, null, 1);
              long b = poiNamed("씬트립상어시험 카페 3호", B, null, 5);
              poiNamed("동네김밥", B, null, 99);
              return new Ids(
                  a1,
                  b,
                  pois.listPois(
                          Lang.KO,
                          SEA_BBOX,
                          null,
                          null,
                          null,
                          null,
                          "씬트립상어시험",
                          "spread",
                          SPREAD_ZOOM,
                          200,
                          0)
                      .getBody()
                      .getItems());
            });

    assertThat(r.items()).hasSize(3);
    assertThat(r.items().subList(0, 2))
        .extracting(PoiSummary::getId)
        .containsExactly(r.a1(), r.b());
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsExactly(1, 1, 2);
  }

  // ───────────── 바뀌지 않는 것 ─────────────

  @Test
  @DisplayName("기존 이름순 — 순서는 이름순 그대로, spreadRank 는 없다")
  void alphabeticalUnchanged() {
    record Ids(List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              poiNamed("다시험", C, null);
              poiNamed("가시험", B, null);
              poiNamed("나시험", A1, null);
              return new Ids(
                  pois.listPois(Lang.KO, SEA_BBOX, null, null, null, null, null, null, null, 30, 0)
                      .getBody()
                      .getItems());
            });

    assertThat(r.items()).extracting(PoiSummary::getName).containsExactly("가시험", "나시험", "다시험");
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsOnlyNulls();
  }

  @Test
  @DisplayName("기존 거리순 — 기준점에서 가까운 순 그대로, spreadRank 는 없다(인기도와 무관)")
  void distanceUnchanged() {
    record Ids(long near, long mid, long far, List<PoiSummary> items) {}
    Ids r =
        rolledBack(
            () -> {
              long far = poi("dist-far", C, "food", 99);
              long mid = poi("dist-mid", B, "food", 50);
              long near = poi("dist-near", A2, "food", 0);
              return new Ids(
                  near,
                  mid,
                  far,
                  pois.listPois(
                          Lang.KO,
                          SEA_BBOX,
                          A1[0],
                          A1[1],
                          null,
                          null,
                          null,
                          "distance",
                          null,
                          30,
                          0)
                      .getBody()
                      .getItems());
            });

    assertThat(r.items()).extracting(PoiSummary::getId).containsExactly(r.near(), r.mid(), r.far());
    assertThat(r.items()).extracting(PoiSummary::getDistanceMeters).isSorted();
    assertThat(r.items()).extracting(PoiSummary::getSpreadRank).containsOnlyNulls();
  }

  // ───────────── 도우미 ─────────────

  private List<PoiSummary> spread(String bbox, int limit) {
    return pois.listPois(
            Lang.KO, bbox, null, null, null, null, null, "spread", SPREAD_ZOOM, limit, 0)
        .getBody()
        .getItems();
  }

  private List<PoiSummary> search(Lang lang, String bbox, String q) {
    return pois.listPois(lang, bbox, null, null, null, null, q, null, null, 200, 0)
        .getBody()
        .getItems();
  }

  private static List<Long> ids(List<PoiSummary> items) {
    return items.stream().map(PoiSummary::getId).toList();
  }

  private static <T> T rolledBack(Supplier<T> work) {
    return IntegrationDatabase.rolledBack(work);
  }

  private static long poi(String name, double[] at, String group, int popularity) {
    return insert(name, null, at, group, popularity, false);
  }

  private static long poiClosed(String name, double[] at, int popularity) {
    return insert(name, null, at, "food", popularity, true);
  }

  private static long poiNamed(String name, double[] at, String roman) {
    return insert(name, roman, at, "food", 0, false);
  }

  private static long poiNamed(String name, double[] at, String roman, int popularity) {
    return insert(name, roman, at, "food", popularity, false);
  }

  private static long insert(
      String name, String roman, double[] at, String group, int popularity, boolean closed) {
    String category =
        switch (group) {
          case "transit" -> "지하철역";
          case "sight" -> "관광지";
          case "stay" -> "호텔";
          default -> "한식";
        };
    return jdbc.sql(
            "INSERT INTO poi (source_id, name, name_roman, geom, category, category_group,"
                + " popularity_score, closed_at) VALUES (:s, :name, :roman,"
                + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :cat, :grp, :pop, "
                + (closed ? "now()" : "NULL")
                + ") RETURNING id")
        .param("s", "it-spread-search-" + UUID.randomUUID())
        .param("name", name)
        .param("roman", roman)
        .param("lng", at[1])
        .param("lat", at[0])
        .param("cat", category)
        .param("grp", group)
        .param("pop", popularity)
        .query(Long.class)
        .single();
  }

  /** 목록은 사진을 서명하지 않는다 — 컨트롤러를 만들려고만 둔 저장소. */
  private static final class NoPhotos implements PhotoStorage {
    @Override
    public boolean available() {
      return false;
    }

    @Override
    public URI viewUrl(String storageKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PresignedUpload presignUpload(
        String storageKey, String contentType, long bytes, Duration expiresIn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void move(String fromKey, String toKey) {}

    @Override
    public void listPages(String prefix, Consumer<List<StoredObject>> onPage) {}

    @Override
    public void delete(String storageKey) {}
  }
}
