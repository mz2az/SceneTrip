package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi_image.sql} — 관광공사 대표 이미지를 POI 의 {@code poi_image} 에 맞춘다(출처 {@code tour_api} 행만).
 *
 * <p>적재가 psql 로 돌리는 바로 그 파일을 JDBC 로 돌린다. 다시 돌려도 같은지(멱등)를 보려면 두 번의 적재가 각각 커밋되어야 하므로, 이 시험은 픽스처 POI 를
 * 실제로 커밋하고 끝날 때마다 지운다. 픽스처는 {@code test-img-} 로 시작하는 source_id 로만 만든다 — 적재된 POI 와 겹칠 수 없다.
 */
@DisplayName("POI 관광공사 사진 맞추기 — seed/poi_image.sql")
class PoiImageSeedIntegrationTest {
  private static final Path IMAGE_SQL = Path.of("services/scene-api/seed/poi_image.sql");
  private static final String PREFIX = "test-img-";

  private static JdbcClient jdbc;
  private static String imageSql;

  @BeforeAll
  static void connect() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    imageSql = Files.readString(IMAGE_SQL);
  }

  /** 입력 한 줄 — t_poi_image_in 의 한 행. */
  private record In(String sourceId, String url, String credit, int sortOrder) {}

  /** t_poi_image_summary 의 한 행. */
  private record Summary(
      long inputPois,
      long matchedPois,
      long unmatchedPois,
      long inserted,
      long updated,
      long removed) {}

  @BeforeEach
  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM poi_image WHERE poi_id IN (SELECT id FROM poi WHERE source_id LIKE :p)")
        .param("p", PREFIX + "%")
        .update();
    jdbc.sql("DELETE FROM poi WHERE source_id LIKE :p").param("p", PREFIX + "%").update();
  }

  private static long poi(String sourceId) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, :s, ST_SetSRID(ST_MakePoint(127.0, 37.6), 4326)::geography, '관광지', 'sight')
            RETURNING id
            """)
        .param("s", PREFIX + sourceId)
        .query(Long.class)
        .single();
  }

  private static void image(long poiId, String url, String source, String credit, int sortOrder) {
    jdbc.sql(
            """
            INSERT INTO poi_image (poi_id, url, source, credit, sort_order)
            VALUES (:p, :u, :s, :c, :o)
            """)
        .param("p", poiId)
        .param("u", url)
        .param("s", source)
        .param("c", credit)
        .param("o", sortOrder)
        .update();
  }

  /** 입력을 넣고 적재 파일을 한 트랜잭션에서 돌린 뒤 커밋한다. 요약을 돌려준다. */
  private static Summary run(In... input) {
    return IntegrationDatabase.transactions()
        .execute(
            status -> {
              IntegrationDatabase.execute(
                  """
                  CREATE TEMP TABLE t_poi_image_in (
                      source_id TEXT, url TEXT, credit TEXT, sort_order INT
                  ) ON COMMIT DROP
                  """);
              for (In in : input) {
                jdbc.sql("INSERT INTO t_poi_image_in VALUES (:s, :u, :c, :o)")
                    .param("s", PREFIX + in.sourceId())
                    .param("u", in.url())
                    .param("c", in.credit())
                    .param("o", in.sortOrder())
                    .update();
              }
              IntegrationDatabase.execute(imageSql);
              return jdbc.sql(
                      """
                      SELECT input_pois, matched_pois, unmatched_pois, inserted, updated, removed
                      FROM t_poi_image_summary
                      """)
                  .query(
                      (rs, n) ->
                          new Summary(
                              rs.getLong("input_pois"),
                              rs.getLong("matched_pois"),
                              rs.getLong("unmatched_pois"),
                              rs.getLong("inserted"),
                              rs.getLong("updated"),
                              rs.getLong("removed")))
                  .single();
            });
  }

  /** POI 의 사진을 「url|source|credit|sort_order」 로, url 순서로. */
  private static List<String> images(long poiId) {
    return jdbc.sql(
            """
            SELECT url || '|' || coalesce(source, '') || '|' || coalesce(credit, '') || '|' || sort_order
            FROM poi_image WHERE poi_id = :p ORDER BY url COLLATE "C"
            """)
        .param("p", poiId)
        .query(String.class)
        .list();
  }

  @Test
  @DisplayName("빈 POI 에 넣는다 — 출처 tour_api, 표기·순서는 입력 그대로")
  void insertsIntoEmpty() {
    long a = poi("a");
    Summary s =
        run(new In("a", "https://x/a1.jpg", "C1", 0), new In("a", "https://x/a2.jpg", "C1", 1));
    assertThat(images(a))
        .containsExactly("https://x/a1.jpg|tour_api|C1|0", "https://x/a2.jpg|tour_api|C1|1");
    assertThat(s).isEqualTo(new Summary(1, 1, 0, 2, 0, 0));
  }

  @Test
  @DisplayName("같은 입력으로 두 번 돌리면 두 번째는 바꿀 것이 없다")
  void rerunIsIdempotent() {
    long a = poi("a");
    long b = poi("b");
    image(a, "https://x/old.jpg", "tour_api", "C1", 0);
    In[] input = {
      new In("a", "https://x/a1.jpg", "C1", 0),
      new In("b", "https://x/b1.jpg", "C3", 0),
      new In("nope", "https://x/n.jpg", "C1", 0)
    };
    run(input);
    List<String> afterFirstA = images(a);
    List<String> afterFirstB = images(b);
    Summary second = run(input);
    assertThat(second.inserted()).isZero();
    assertThat(second.updated()).isZero();
    assertThat(second.removed()).isZero();
    assertThat(second).isEqualTo(new Summary(3, 2, 1, 0, 0, 0));
    assertThat(images(a)).isEqualTo(afterFirstA).containsExactly("https://x/a1.jpg|tour_api|C1|0");
    assertThat(images(b)).isEqualTo(afterFirstB).containsExactly("https://x/b1.jpg|tour_api|C3|0");
  }

  @Test
  @DisplayName("표기나 순서가 바뀐 tour_api 행은 고친다")
  void changedCreditOrSortIsUpdated() {
    long a = poi("a");
    image(a, "https://x/a1.jpg", "tour_api", "C1", 0);
    image(a, "https://x/a2.jpg", "tour_api", "C1", 1);
    image(a, "https://x/a3.jpg", "tour_api", "C1", 2);
    Summary s =
        run(
            new In("a", "https://x/a1.jpg", "C3", 0),
            new In("a", "https://x/a2.jpg", "C1", 5),
            new In("a", "https://x/a3.jpg", "C1", 2));
    assertThat(images(a))
        .containsExactly(
            "https://x/a1.jpg|tour_api|C3|0",
            "https://x/a2.jpg|tour_api|C1|5",
            "https://x/a3.jpg|tour_api|C1|2");
    assertThat(s).isEqualTo(new Summary(1, 1, 0, 0, 2, 0));
  }

  @Test
  @DisplayName("입력에서 빠진 주소의 tour_api 행은 지운다")
  void urlNoLongerInInputIsRemoved() {
    long a = poi("a");
    image(a, "https://x/a1.jpg", "tour_api", "C1", 0);
    image(a, "https://x/gone.jpg", "tour_api", "C1", 1);
    Summary s = run(new In("a", "https://x/a1.jpg", "C1", 0));
    assertThat(images(a)).containsExactly("https://x/a1.jpg|tour_api|C1|0");
    assertThat(s).isEqualTo(new Summary(1, 1, 0, 0, 0, 1));
  }

  @Test
  @DisplayName("tour_api 가 아닌 행은 손대지 않는다 — 같은 주소가 입력에 있어도 출처·표기 그대로")
  void otherSourceRowsUntouched() {
    long a = poi("a");
    image(a, "https://x/own.jpg", "own", "우리 사진", 7);
    image(a, "https://x/mine.jpg", "own", "우리 사진", 8);
    image(a, "https://x/nosrc.jpg", null, null, 9);
    Summary s =
        run(new In("a", "https://x/own.jpg", "C1", 0), new In("a", "https://x/a1.jpg", "C1", 1));
    assertThat(images(a))
        .containsExactly(
            "https://x/a1.jpg|tour_api|C1|1",
            "https://x/mine.jpg|own|우리 사진|8",
            "https://x/nosrc.jpg|||9",
            "https://x/own.jpg|own|우리 사진|7");
    assertThat(s.inserted()).isEqualTo(1);
    assertThat(s.updated()).isZero();
    assertThat(s.removed()).isZero();
  }

  @Test
  @DisplayName("입력에 없는 POI 는 tour_api 행이라도 그대로 둔다")
  void poiNotInInputUntouched() {
    long a = poi("a");
    long c = poi("c");
    image(c, "https://x/c1.jpg", "tour_api", "C1", 0);
    Summary s = run(new In("a", "https://x/a1.jpg", "C1", 0));
    assertThat(images(c)).containsExactly("https://x/c1.jpg|tour_api|C1|0");
    assertThat(images(a)).containsExactly("https://x/a1.jpg|tour_api|C1|0");
    assertThat(s.removed()).isZero();
  }

  @Test
  @DisplayName("맞는 POI 가 없는 번호는 세기만 하고 넘어간다")
  void unmatchedSourceIdCounted() {
    Summary s =
        run(
            new In("nope-1", "https://x/n1.jpg", "C1", 0),
            new In("nope-1", "https://x/n2.jpg", "C1", 1),
            new In("nope-2", "https://x/n3.jpg", "C1", 0));
    assertThat(s).isEqualTo(new Summary(2, 0, 2, 0, 0, 0));
    long leaked =
        jdbc.sql("SELECT count(*) FROM poi_image WHERE url LIKE 'https://x/n%'")
            .query(Long.class)
            .single();
    assertThat(leaked).isZero();
  }

  @Test
  @DisplayName("요약 숫자 — 바꾸기 전에 센 넣기·고치기·지우기와 맞은·안 맞은 POI")
  void summaryNumbers() {
    long a = poi("a");
    long b = poi("b");
    long c = poi("c");
    image(a, "https://x/a1.jpg", "tour_api", "C1", 0);
    image(a, "https://x/a2.jpg", "tour_api", "C1", 1);
    image(a, "https://x/a3.jpg", "tour_api", "C1", 2);
    image(a, "https://x/a-own.jpg", "own", "우리 사진", 9);
    image(c, "https://x/c1.jpg", "tour_api", "C1", 0);

    Summary s =
        run(
            new In("a", "https://x/a1.jpg", "C1", 0), // 그대로
            new In("a", "https://x/a2.jpg", "C3", 1), // 표기 바뀜 → 고치기
            new In("a", "https://x/a4.jpg", "C1", 3), // 새 주소 → 넣기
            new In("a", "https://x/a-own.jpg", "C1", 4), // own 행이 이미 있음 → 손대지 않음
            new In("b", "https://x/b1.jpg", "C1", 0), // 새 POI → 넣기
            new In("x", "https://x/x1.jpg", "C1", 0), // 맞는 POI 없음
            new In("x", "https://x/x2.jpg", "C1", 1));
    // a3 는 입력에서 빠짐 → 지우기. c 는 입력에 없음 → 그대로.

    assertThat(s).isEqualTo(new Summary(3, 2, 1, 2, 1, 1));
    assertThat(images(a))
        .containsExactly(
            "https://x/a-own.jpg|own|우리 사진|9",
            "https://x/a1.jpg|tour_api|C1|0",
            "https://x/a2.jpg|tour_api|C3|1",
            "https://x/a4.jpg|tour_api|C1|3");
    assertThat(images(b)).containsExactly("https://x/b1.jpg|tour_api|C1|0");
    assertThat(images(c)).containsExactly("https://x/c1.jpg|tour_api|C1|0");
  }
}
