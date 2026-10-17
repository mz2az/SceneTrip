package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi_detail.sql} 의 「어느 POI 인가」 가 번호 기록(poi_source, V38)을 먼저 본다 — poi-load-cleanup.md
 * §3·§5-5 (MZ2AZ-491).
 *
 * <p>국문은 번호 기록 → 없으면 {@code poi.source_id}. 영문은 {@code tour-en-<번호>} 기록 → 없으면 이름 + 300 m, 짝이 되면 기록에
 * 남긴다. 적재가 psql 로 돌리는 바로 그 파일(메타 명령이 없다)을 JDBC 로 돌린다. 픽스처는 적재 데이터와 겹치지 않는 바다 위(위도 35.2 · 경도 130.2)에
 * 두고 번호는 {@code it-pds-} 꼴로만 만든다. 트랜잭션은 끝나면 되돌린다. 명세만 보고 쓴 시험.
 */
@DisplayName("POI 상세 맞추기 — 번호 기록으로 POI 찾기 (실제 DB)")
class PoiDetailSourceIntegrationTest {
  private static final Path DETAIL_SQL = Path.of("services/scene-api/seed/poi_detail.sql");
  private static final double LAT = 35.2;
  private static final double LNG = 130.2;
  private static final String MOD = "2024-01-15T09:30:00+09:00";

  private static JdbcClient jdbc;
  private static String detailSql;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    detailSql = Files.readString(DETAIL_SQL);
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────

  private String id(String s) {
    return "it-pds-" + tag + "-" + s;
  }

  /** 기준점에서 북쪽으로 metersNorth 미터의 위도. */
  private static double latAt(double metersNorth) {
    return LAT + metersNorth / 111_320.0;
  }

  /** 편의시설 하나(기록 없이). */
  private static long poi(String sourceId, String name, double metersNorth) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '시험분류', 'sight')
            RETURNING id
            """)
        .param("s", sourceId)
        .param("n", name)
        .param("lng", LNG)
        .param("lat", latAt(metersNorth))
        .query(Long.class)
        .single();
  }

  private static void record(String sourceId, long poiId, String source) {
    jdbc.sql("INSERT INTO poi_source (source_id, poi_id, source) VALUES (:s, :p, :src)")
        .param("s", sourceId)
        .param("p", poiId)
        .param("src", source)
        .update();
  }

  /** 기록 한 줄 — 「poi_id|source|active」, 없으면 null. */
  private static String recordOf(String sourceId) {
    return jdbc.sql(
            "SELECT poi_id || '|' || source || '|' || active FROM poi_source WHERE source_id = :s")
        .param("s", sourceId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static void createInputTables() {
    IntegrationDatabase.execute(
        """
        DROP TABLE IF EXISTS pg_temp.t_poi_detail_in, pg_temp.t_poi_detail_image_in;
        CREATE TEMP TABLE t_poi_detail_in (
            source_id TEXT, match_name TEXT, lat FLOAT8, lng FLOAT8,
            lang TEXT, tour_content_id TEXT, tour_modified TIMESTAMPTZ,
            overview TEXT, use_time TEXT, rest_date TEXT, parking TEXT, first_menu TEXT, use_fee TEXT,
            homepage TEXT
        ) ON COMMIT DROP;
        CREATE TEMP TABLE t_poi_detail_image_in (
            lang TEXT, tour_content_id TEXT, url TEXT, thumbnail_url TEXT, license TEXT,
            credit TEXT, sort_order INT
        ) ON COMMIT DROP;
        """);
  }

  /** 국문 상세 한 줄 — source_id 로 찾는다. */
  private static void inKo(String sourceId, String contentId, String overview) {
    inRow(sourceId, null, null, "ko", contentId, overview);
  }

  /** 영문 상세 한 줄 — source_id 없이 기록 또는 이름·좌표로. 자리는 기준점 북쪽 metersNorth 미터. */
  private static void inEn(
      String matchName, double metersNorth, String contentId, String overview) {
    inRow(null, matchName, latAt(metersNorth), "en", contentId, overview);
  }

  private static void inRow(
      String sourceId,
      String matchName,
      Double lat,
      String lang,
      String contentId,
      String overview) {
    jdbc.sql(
            """
            INSERT INTO t_poi_detail_in (source_id, match_name, lat, lng, lang, tour_content_id,
                                         tour_modified, overview, use_time)
            VALUES (:s, :n, :lat, :lng, :l, :c, CAST(:m AS timestamptz), :o, '09:00')
            """)
        .param("s", sourceId)
        .param("n", matchName)
        .param("lat", lat)
        .param("lng", lat == null ? null : LNG)
        .param("l", lang)
        .param("c", contentId)
        .param("m", MOD)
        .param("o", overview)
        .update();
  }

  private static void inImage(String lang, String contentId, String url) {
    jdbc.sql(
            """
            INSERT INTO t_poi_detail_image_in (lang, tour_content_id, url, credit, sort_order)
            VALUES (:lang, :c, :u, '한국관광공사', 10)
            """)
        .param("lang", lang)
        .param("c", contentId)
        .param("u", url)
        .update();
  }

  /** 파일을 돌리고 (이어진 줄, 못 찾은 줄) 을 읽는다. */
  private static long[] runSql() {
    IntegrationDatabase.execute(detailSql);
    return jdbc.sql("SELECT matched_rows, unmatched_rows FROM t_poi_detail_summary")
        .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
        .single();
  }

  /** 같은 트랜잭션에서 다시 돌리려고 입력 두 표 말고 이 세션의 임시 표를 지운다. */
  private static void forgetScratchTables() {
    List<String> temps =
        jdbc.sql(
                """
                SELECT c.relname FROM pg_class c
                WHERE c.relpersistence = 't' AND c.relkind = 'r'
                  AND c.relnamespace = pg_my_temp_schema()
                  AND c.relname NOT IN ('t_poi_detail_in', 't_poi_detail_image_in')
                """)
            .query(String.class)
            .list();
    for (String t : temps) {
      IntegrationDatabase.execute("DROP TABLE IF EXISTS pg_temp.\"" + t + "\"");
    }
  }

  /** 그 POI·언어 상세의 개요, 없으면 null. */
  private static String overview(long poiId, String lang) {
    return jdbc.sql(
            "SELECT overview FROM poi_detail WHERE poi_id = :p AND lang = CAST(:l AS lang_code)")
        .param("p", poiId)
        .param("l", lang)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static List<String> imageUrls(long poiId) {
    return jdbc.sql("SELECT url FROM poi_image WHERE poi_id = :p ORDER BY url")
        .param("p", poiId)
        .query(String.class)
        .list();
  }

  // ── 국문 ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("국문 — 상가정보에 합쳐진 관광공사 번호의 상세·사진이 기록을 따라 그 상가정보 편의시설에 붙는다")
  void koreanDetailFollowsMergedRecord() {
    IntegrationDatabase.rolledBack(
        () -> {
          String storeNo = "MA" + id("s");
          long store = poi(storeNo, "시험상가", 0);
          record(storeNo, store, "store");
          String tourNo = "tour-" + id("1");
          record(tourNo, store, "tour");
          createInputTables();
          inKo(tourNo, id("1"), "합쳐진 곳의 개요");
          inImage("ko", id("1"), "https://img.test/" + tag + "/merged.jpg");

          long[] s = runSql();

          assertThat(s).containsExactly(1, 0);
          assertThat(overview(store, "ko")).isEqualTo("합쳐진 곳의 개요");
          assertThat(imageUrls(store)).containsExactly("https://img.test/" + tag + "/merged.jpg");
          return null;
        });
  }

  @Test
  @DisplayName("국문 — 기록이 없으면 poi.source_id 로 찾는다")
  void koreanFallsBackToRepresentativeNumber() {
    IntegrationDatabase.rolledBack(
        () -> {
          String tourNo = "tour-" + id("2");
          long p = poi(tourNo, "시험관광지", 0);
          createInputTables();
          inKo(tourNo, id("2"), "대표 번호로 찾은 개요");

          long[] s = runSql();

          assertThat(s).containsExactly(1, 0);
          assertThat(overview(p, "ko")).isEqualTo("대표 번호로 찾은 개요");
          return null;
        });
  }

  @Test
  @DisplayName("국문 — 기록이 대표 번호보다 먼저다 — 같은 번호가 다른 편의시설에 기록돼 있으면 그쪽으로")
  void koreanRecordBeatsRepresentativeNumber() {
    IntegrationDatabase.rolledBack(
        () -> {
          String tourNo = "tour-" + id("3");
          long byColumn = poi(tourNo, "대표번호쪽", 0);
          long byRecord = poi("MA" + id("q"), "기록쪽", 50);
          record(tourNo, byRecord, "tour");
          createInputTables();
          inKo(tourNo, id("3"), "기록으로 찾은 개요");

          runSql();

          assertThat(overview(byRecord, "ko")).isEqualTo("기록으로 찾은 개요");
          assertThat(overview(byColumn, "ko")).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("국문 — 기록에도 대표 번호에도 없는 번호는 세기만 한다")
  void koreanUnknownNumberIsUnmatched() {
    IntegrationDatabase.rolledBack(
        () -> {
          createInputTables();
          inKo("tour-" + id("none"), id("none"), "갈 곳 없음");

          long[] s = runSql();

          assertThat(s).containsExactly(0, 1);
          return null;
        });
  }

  // ── 영문 ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("영문 — tour-en-<번호> 기록이 있으면 이름·거리가 맞지 않아도 그 편의시설로, 새 기록은 없다")
  void englishFollowsRecord() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poi("tour-" + id("4"), "시험기록쪽", 0);
          String enNo = "tour-en-" + id("e4");
          record(enNo, p, "tour_en");
          createInputTables();
          inEn("전혀다른이름", 5_000, id("e4"), "English by record");

          long[] s = runSql();

          assertThat(s).containsExactly(1, 0);
          assertThat(overview(p, "en")).isEqualTo("English by record");
          assertThat(recordOf(enNo)).isEqualTo(p + "|tour_en|true");
          return null;
        });
  }

  @Test
  @DisplayName("영문 — 기록이 이름 짝보다 먼저다 — 같은 이름의 더 가까운 편의시설이 있어도 기록 쪽으로")
  void englishRecordBeatsNameMatch() {
    IntegrationDatabase.rolledBack(
        () -> {
          String name = "시험영문" + tag;
          long recorded = poi("tour-" + id("5"), "기록된곳" + tag, 200);
          long sameName = poi("tour-" + id("6"), name, 0);
          record("tour-en-" + id("e5"), recorded, "tour_en");
          createInputTables();
          inEn(name, 0, id("e5"), "English record first");

          runSql();

          assertThat(overview(recorded, "en")).isEqualTo("English record first");
          assertThat(overview(sameName, "en")).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("영문 — 기록이 없으면 이름 + 300 m 로 짝짓고 tour-en-<번호> 로 기록한다 — 다음 실행은 이름이 바뀌어도 기록으로 찾는다")
  void englishNameMatchIsRecordedAndReused() {
    IntegrationDatabase.rolledBack(
        () -> {
          String name = "시험짝" + tag;
          long p = poi("tour-" + id("7"), name, 0);
          String enNo = "tour-en-" + id("e7");
          createInputTables();
          inEn(name, 120, id("e7"), "English by name");

          long[] first = runSql();

          assertThat(first).containsExactly(1, 0);
          assertThat(overview(p, "en")).isEqualTo("English by name");
          assertThat(recordOf(enNo)).isEqualTo(p + "|tour_en|true");

          // 이름이 달라지면 이름 짝은 안 된다 — 그래도 기록으로 찾는다
          jdbc.sql("UPDATE poi SET name = :n WHERE id = :id")
              .param("n", "바뀐이름" + tag)
              .param("id", p)
              .update();
          IntegrationDatabase.execute("UPDATE t_poi_detail_in SET overview = 'English again'");
          forgetScratchTables();
          long[] second = runSql();

          assertThat(second).containsExactly(1, 0);
          assertThat(overview(p, "en")).isEqualTo("English again");
          assertThat(
                  jdbc.sql("SELECT count(*) FROM poi_source WHERE source_id = :s")
                      .param("s", enNo)
                      .query(Long.class)
                      .single())
              .isEqualTo(1L);
          return null;
        });
  }

  @Test
  @DisplayName("영문 — 짝이 없으면(300 m 밖) 세기만 하고 기록도 편의시설도 만들지 않는다")
  void englishWithoutMatchLeavesNoRecord() {
    IntegrationDatabase.rolledBack(
        () -> {
          String name = "시험먼곳" + tag;
          long p = poi("tour-" + id("8"), name, 0);
          createInputTables();
          inEn(name, 400, id("e8"), "too far");

          long[] s = runSql();

          assertThat(s).containsExactly(0, 1);
          assertThat(overview(p, "en")).isNull();
          assertThat(recordOf("tour-en-" + id("e8"))).isNull();
          assertThat(
                  jdbc.sql("SELECT count(*) FROM poi WHERE source_id LIKE '%' || :t || '%'")
                      .param("t", tag)
                      .query(Long.class)
                      .single())
              .isEqualTo(1L);
          return null;
        });
  }
}
