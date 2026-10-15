package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi_detail.sql} — 관광공사 상세(poi_detail)와 관광공사 사진(poi_image, source = 'tour_api')을 입력과
 * 같게 맞추기(docs/project/plans/poi-source.md §6).
 *
 * <p>적재가 psql 로 돌리는 바로 그 파일을 JDBC 로 돌린다. 픽스처 POI 는 출처 번호 {@code tdtest-} 로만 만든다 — 맞추기는 입력에 있는 POI 만
 * 보므로 적재된 행은 건드리지 않는다. 트랜잭션은 끝나면 되돌린다. 명세만 보고 쓴 시험.
 */
@DisplayName("POI 상세 맞추기 — 관광공사 상세·사진")
class PoiDetailIntegrationTest {
  private static final Path DETAIL_SQL = Path.of("services/scene-api/seed/poi_detail.sql");
  private static final OffsetDateTime PAST = OffsetDateTime.parse("2020-01-01T00:00:00+09:00");
  private static final String MOD = "2024-01-15T09:30:00+09:00";

  private static JdbcClient jdbc;
  private static String detailSql;

  @BeforeAll
  static void connect() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    detailSql = Files.readString(DETAIL_SQL);
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────

  private static long poi(String sourceId) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, :s, ST_SetSRID(ST_MakePoint(127.0, 37.6), 4326)::geography, '관광지', 'sight')
            RETURNING id
            """)
        .param("s", sourceId)
        .query(Long.class)
        .single();
  }

  private static void existingDetail(long poiId, String lang, String overview, String contentId) {
    jdbc.sql(
            """
            INSERT INTO poi_detail (poi_id, lang, overview, use_time, tour_content_id, tour_modified, updated_at)
            VALUES (:p, CAST(:l AS lang_code), :o, '09:00', :c, CAST(:m AS timestamptz), :u)
            """)
        .param("p", poiId)
        .param("l", lang)
        .param("o", overview)
        .param("c", contentId)
        .param("m", MOD)
        .param("u", PAST)
        .update();
  }

  private static void existingImage(
      long poiId, String url, int sort, String source, String credit, String contentId) {
    jdbc.sql(
            """
            INSERT INTO poi_image (poi_id, url, sort_order, source, credit, thumbnail_url, license, tour_content_id)
            VALUES (:p, :u, :o, :s, :c, NULL, NULL, :t)
            """)
        .param("p", poiId)
        .param("u", url)
        .param("o", sort)
        .param("s", source)
        .param("c", credit)
        .param("t", contentId)
        .update();
  }

  private static void createInputTables() {
    IntegrationDatabase.execute(
        """
        CREATE TEMP TABLE t_poi_detail_in (
            source_id TEXT, lang TEXT, tour_content_id TEXT, tour_modified TIMESTAMPTZ,
            overview TEXT, use_time TEXT, rest_date TEXT, parking TEXT, first_menu TEXT, use_fee TEXT,
            homepage TEXT
        ) ON COMMIT DROP;
        CREATE TEMP TABLE t_poi_detail_image_in (
            source_id TEXT, tour_content_id TEXT, url TEXT, thumbnail_url TEXT, license TEXT,
            credit TEXT, sort_order INT
        ) ON COMMIT DROP;
        """);
  }

  /** 입력 상세 한 줄. 이용시간은 '09:00', 나머지 칸(쉬는 날·주차…)은 null. */
  private static void inDetail(String sourceId, String lang, String contentId, String overview) {
    jdbc.sql(
            """
            INSERT INTO t_poi_detail_in
            VALUES (:s, :l, :c, CAST(:m AS timestamptz), :o, '09:00', NULL, NULL, NULL, NULL, NULL)
            """)
        .param("s", sourceId)
        .param("l", lang)
        .param("c", contentId)
        .param("m", MOD)
        .param("o", overview)
        .update();
  }

  private static void inImage(
      String sourceId, String contentId, String url, String thumb, String license, int sort) {
    jdbc.sql("INSERT INTO t_poi_detail_image_in VALUES (:s, :c, :u, :t, :l, '한국관광공사', :o)")
        .param("s", sourceId)
        .param("c", contentId)
        .param("u", url)
        .param("t", thumb)
        .param("l", license)
        .param("o", sort)
        .update();
  }

  /** 파일을 돌리고 요약 한 줄을 읽는다. */
  private static Map<String, Long> runSql() {
    IntegrationDatabase.execute(detailSql);
    List<Map<String, Object>> rows =
        jdbc.sql(
                """
                SELECT input_rows, matched_rows, unmatched_rows, details_inserted, details_updated,
                       images_inserted, images_removed
                FROM t_poi_detail_summary
                """)
            .query()
            .listOfRows();
    assertThat(rows).hasSize(1);
    Map<String, Long> out = new LinkedHashMap<>();
    rows.get(0).forEach((k, v) -> out.put(k, ((Number) v).longValue()));
    return out;
  }

  /**
   * 새 실행처럼 — 실제로는 실행마다 트랜잭션이 따로라 SQL 이 만든 임시 표가 남지 않는다. 같은 트랜잭션 안에서 두 번 돌리려고 입력 두 표 말고 이 세션의 임시
   * 표(요약 등)를 지운다.
   */
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

  private static Map<String, Object> detail(long poiId, String lang) {
    List<Map<String, Object>> rows =
        jdbc.sql(
                """
                SELECT overview, use_time, rest_date, parking, first_menu, use_fee, homepage,
                       tour_content_id, tour_modified, updated_at
                FROM poi_detail WHERE poi_id = :p AND lang = CAST(:l AS lang_code)
                """)
            .param("p", poiId)
            .param("l", lang)
            .query()
            .listOfRows();
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** POI 의 사진 — 「url|source|sort|credit|thumb|license|content」 를 주소 순으로. */
  private static List<String> images(long poiId) {
    return jdbc.sql(
            """
            SELECT url || '|' || coalesce(source, '-') || '|' || sort_order || '|' || coalesce(credit, '-')
                   || '|' || coalesce(thumbnail_url, '-') || '|' || coalesce(license, '-')
                   || '|' || coalesce(tour_content_id, '-')
            FROM poi_image WHERE poi_id = :p ORDER BY url
            """)
        .param("p", poiId)
        .query(String.class)
        .list();
  }

  private static long count(String sql, long poiId) {
    return jdbc.sql(sql).param("p", poiId).query(Long.class).single();
  }

  private static Map<String, Long> summary(
      long input, long matched, long unmatched, long dIns, long dUpd, long iIns, long iRem) {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("input_rows", input);
    m.put("matched_rows", matched);
    m.put("unmatched_rows", unmatched);
    m.put("details_inserted", dIns);
    m.put("details_updated", dUpd);
    m.put("images_inserted", iIns);
    m.put("images_removed", iRem);
    return m;
  }

  // ── 시험 ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("없으면 넣는다 — 상세 한 줄·사진, 이름이 없는 POI 는 세기만")
  void insertsAndCountsUnmatched() {
    IntegrationDatabase.rolledBack(
        () -> {
          long a = poi("tdtest-a");
          createInputTables();
          inDetail("tdtest-a", "ko", "c-a", "새 개요");
          inDetail("tdtest-missing", "ko", "c-m", "없는 곳");
          inImage(
              "tdtest-a",
              "c-a",
              "https://img.test/a1.jpg",
              "https://img.test/a1_s.jpg",
              "kogl_type1",
              10);
          inImage("tdtest-a", "c-a", "https://img.test/a2.jpg", null, null, 20);
          inImage("tdtest-missing", "c-m", "https://img.test/m1.jpg", null, null, 10);

          Map<String, Long> s = runSql();

          assertThat(s).isEqualTo(summary(2, 1, 1, 1, 0, 2, 0));
          Map<String, Object> d = detail(a, "ko");
          assertThat(d).isNotNull();
          assertThat(d.get("overview")).isEqualTo("새 개요");
          assertThat(d.get("use_time")).isEqualTo("09:00");
          assertThat(d.get("rest_date")).isNull();
          assertThat(d.get("tour_content_id")).isEqualTo("c-a");
          assertThat(d.get("tour_modified")).isNotNull();
          assertThat(images(a))
              .containsExactly(
                  "https://img.test/a1.jpg|tour_api|10|한국관광공사|https://img.test/a1_s.jpg|kogl_type1|c-a",
                  "https://img.test/a2.jpg|tour_api|20|한국관광공사|-|-|c-a");
          assertThat(
                  jdbc.sql("SELECT count(*) FROM poi_image WHERE url = 'https://img.test/m1.jpg'")
                      .query(Long.class)
                      .single())
              .isZero();
          return null;
        });
  }

  @Test
  @DisplayName("다르면 고치고 같으면 그대로 — 입력에 없는 언어는 건드리지 않는다")
  void updatesOnlyDifferentRows() {
    IntegrationDatabase.rolledBack(
        () -> {
          long a = poi("tdtest-a");
          long b = poi("tdtest-b");
          long other = poi("tdtest-other");
          existingDetail(a, "ko", "옛 개요", "c-a");
          existingDetail(a, "en", "old overview", "c-a-en");
          existingDetail(b, "ko", "같은 개요", "c-b");
          existingDetail(other, "ko", "다른 곳", "c-o");
          createInputTables();
          inDetail("tdtest-a", "ko", "c-a", "새 개요");
          inDetail("tdtest-b", "ko", "c-b", "같은 개요");

          Map<String, Long> s = runSql();

          assertThat(s).isEqualTo(summary(2, 2, 0, 0, 1, 0, 0));
          assertThat(detail(a, "ko").get("overview")).isEqualTo("새 개요");
          // 같은 줄은 손대지 않는다 — updated_at 이 픽스처의 과거 시각 그대로
          Map<String, Object> db = detail(b, "ko");
          assertThat(db.get("overview")).isEqualTo("같은 개요");
          assertThat(((java.sql.Timestamp) db.get("updated_at")).toInstant())
              .isEqualTo(PAST.toInstant());
          // 입력에 없는 언어·POI
          Map<String, Object> en = detail(a, "en");
          assertThat(en.get("overview")).isEqualTo("old overview");
          assertThat(((java.sql.Timestamp) en.get("updated_at")).toInstant())
              .isEqualTo(PAST.toInstant());
          Map<String, Object> o = detail(other, "ko");
          assertThat(o.get("overview")).isEqualTo("다른 곳");
          assertThat(((java.sql.Timestamp) o.get("updated_at")).toInstant())
              .isEqualTo(PAST.toInstant());
          return null;
        });
  }

  @Test
  @DisplayName("같은 입력으로 다시 돌리면 아무것도 바뀌지 않는다")
  void rerunChangesNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long a = poi("tdtest-a");
          existingImage(a, "https://img.test/old.jpg", 10, "tour_api", "한국관광공사", "c-a");
          createInputTables();
          inDetail("tdtest-a", "ko", "c-a", "개요");
          inDetail("tdtest-missing", "ko", "c-m", "없는 곳");
          inImage(
              "tdtest-a",
              "c-a",
              "https://img.test/a1.jpg",
              "https://img.test/a1_s.jpg",
              "kogl_type3",
              10);
          inImage("tdtest-a", "c-a", "https://img.test/a2.jpg", null, null, 20);

          Map<String, Long> first = runSql();
          assertThat(first).isEqualTo(summary(2, 1, 1, 1, 0, 2, 1));
          Map<String, Object> d1 = detail(a, "ko");
          List<String> i1 = images(a);

          forgetScratchTables();
          Map<String, Long> second = runSql();

          assertThat(second).isEqualTo(summary(2, 1, 1, 0, 0, 0, 0));
          assertThat(detail(a, "ko")).isEqualTo(d1);
          assertThat(images(a)).isEqualTo(i1);
          return null;
        });
  }

  @Test
  @DisplayName("사진 범위 — 이 콘텐츠 번호와 번호 없는 관광공사 사진만 맞추고, 다른 번호·다른 출처·다른 POI 는 그대로")
  void imageScope() {
    IntegrationDatabase.rolledBack(
        () -> {
          long a = poi("tdtest-a");
          long c = poi("tdtest-c");
          // a — 범위 안
          existingImage(a, "https://img.test/u1.jpg", 99, "tour_api", "옛 표기", "c-a");
          existingImage(a, "https://img.test/old.jpg", 30, "tour_api", "한국관광공사", "c-a");
          existingImage(a, "https://img.test/null-old.jpg", 40, "tour_api", "한국관광공사", null);
          existingImage(a, "https://img.test/u2.jpg", 50, "tour_api", "한국관광공사", null);
          // a — 범위 밖
          existingImage(a, "https://img.test/en.jpg", 60, "tour_api", "한국관광공사", "c-a-en");
          existingImage(a, "https://img.test/own.jpg", 0, "own", "우리", null);
          existingImage(a, "https://img.test/u3.jpg", 5, "own", "우리", null);
          // 입력에 없는 POI
          existingImage(c, "https://img.test/c-null.jpg", 10, "tour_api", "한국관광공사", null);
          existingImage(c, "https://img.test/c-a.jpg", 20, "tour_api", "한국관광공사", "c-a");
          List<String> cBefore = images(c);

          createInputTables();
          inDetail("tdtest-a", "ko", "c-a", "개요");
          inImage(
              "tdtest-a",
              "c-a",
              "https://img.test/u1.jpg",
              "https://img.test/u1_s.jpg",
              "kogl_type1",
              10);
          inImage("tdtest-a", "c-a", "https://img.test/u2.jpg", null, "kogl_type3", 20);
          inImage(
              "tdtest-a",
              "c-a",
              "https://img.test/u3.jpg",
              "https://img.test/u3_s.jpg",
              "kogl_type1",
              30);
          inImage("tdtest-a", "c-a", "https://img.test/u4.jpg", null, null, 40);

          Map<String, Long> s = runSql();

          assertThat(images(a))
              .containsExactly(
                  "https://img.test/en.jpg|tour_api|60|한국관광공사|-|-|c-a-en",
                  "https://img.test/own.jpg|own|0|우리|-|-|-",
                  "https://img.test/u1.jpg|tour_api|10|한국관광공사|https://img.test/u1_s.jpg|kogl_type1|c-a",
                  "https://img.test/u2.jpg|tour_api|20|한국관광공사|-|kogl_type3|c-a",
                  "https://img.test/u3.jpg|own|5|우리|-|-|-",
                  "https://img.test/u4.jpg|tour_api|40|한국관광공사|-|-|c-a");
          assertThat(images(c)).isEqualTo(cBefore);
          assertThat(s.get("images_inserted")).isEqualTo(1L);
          assertThat(s.get("images_removed")).isEqualTo(2L);
          return null;
        });
  }

  @Test
  @DisplayName("입력에 있는데 사진이 없으면 범위 안 관광공사 사진을 지운다")
  void poiWithoutInputImagesLosesInScopeImages() {
    IntegrationDatabase.rolledBack(
        () -> {
          long d = poi("tdtest-d");
          existingImage(d, "https://img.test/d1.jpg", 10, "tour_api", "한국관광공사", "c-d");
          existingImage(d, "https://img.test/d-own.jpg", 0, "own", "우리", null);
          createInputTables();
          inDetail("tdtest-d", "ko", "c-d", "개요");

          Map<String, Long> s = runSql();

          assertThat(images(d)).containsExactly("https://img.test/d-own.jpg|own|0|우리|-|-|-");
          assertThat(s).isEqualTo(summary(1, 1, 0, 1, 0, 0, 1));
          return null;
        });
  }

  @Test
  @DisplayName("요약은 한 줄이고 입력이 없는 POI 의 상세 줄 수는 그대로")
  void untouchedCounts() {
    IntegrationDatabase.rolledBack(
        () -> {
          long other = poi("tdtest-other");
          existingDetail(other, "ko", "그대로", "c-o");
          existingImage(other, "https://img.test/o.jpg", 10, "tour_api", "한국관광공사", "c-o");
          createInputTables();
          inDetail("tdtest-missing", "ko", "c-m", "없는 곳");

          Map<String, Long> s = runSql();

          assertThat(s).isEqualTo(summary(1, 0, 1, 0, 0, 0, 0));
          assertThat(count("SELECT count(*) FROM poi_detail WHERE poi_id = :p", other))
              .isEqualTo(1);
          assertThat(count("SELECT count(*) FROM poi_image WHERE poi_id = :p", other)).isEqualTo(1);
          return null;
        });
  }
}
