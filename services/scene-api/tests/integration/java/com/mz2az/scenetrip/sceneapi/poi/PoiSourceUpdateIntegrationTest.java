package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi_filter.sql}(줄 거르기) · {@code seed/poi_source_update.sql}(번호 기록 기준 출처별 업데이트) —
 * poi-load-cleanup.md §3·§5 (5-1 · 5-2 · 5-3 · 5-7), MZ2AZ-492. 명세(계획 문서와 두 파일의 머리 설명)만 보고 쓴 시험.
 *
 * <p>{@code just poi-update} 가 psql 로 돌리는 두 순수 SQL 파일을 메타 명령만 걷어 JDBC 로 돌린다. 입력 임시 표({@code t_raw}
 * · {@code t_hidden} · {@code t_upd_param})는 시험이 만든다. 전부 {@link IntegrationDatabase#rolledBack} 안이라
 * 공유 DB 는 그대로다.
 *
 * <p><b>판 전체(full) 상가정보 실행의 격리</b>: 판 전체는 「입력에 없는 살아 있는 상가정보 번호 = 사라짐」 이라, 적재된 DB(로컬 91 만 곳)에서 돌리면
 * 그 트랜잭션 안에서는 픽스처 밖 상가정보 편의시설도 모두 숨겨진다. 이것은 명세대로의 동작이라 피할 수 없다 — 그래서 판 전체 실행은 한 시험 안에서 (두 번: 처음 +
 * 같은 입력 다시) 모든 상가정보 경우를 함께 돌리고, 단언은 픽스처 행만 본다. 되돌리기로 끝나므로 공유 DB 에는 남지 않는다. 관광공사 경우는 바뀐
 * 것만(full=false)으로 돌려 적재 데이터에 닿지 않는다.
 *
 * <p>픽스처는 적재 데이터와 겹치지 않는 바다 위(위도 34.3 · 경도 131.6 근처)에 경우마다 1 km 씩 떨어뜨린다. 번호에는 시험마다 다른 꼬리표를 넣는다.
 */
@DisplayName("편의시설 출처별 업데이트 — 줄 거르기 · 번호 기록 · 숨김/다시 열기 · 멱등 (실제 DB)")
class PoiSourceUpdateIntegrationTest {
  private static final Path FILTER_SQL = Path.of("services/scene-api/seed/poi_filter.sql");
  private static final Path UPDATE_SQL = Path.of("services/scene-api/seed/poi_source_update.sql");

  private static final double LAT = 34.3;
  private static final double LNG = 131.6;
  private static final double M_PER_LAT = 111_320.0;

  /** 위도 34.3 에서 경도 1 도 ≈ 91.96 km. */
  private static final double M_PER_LNG = 91_960.0;

  private static JdbcClient jdbc;
  private static String filterSql;
  private static String updateSql;

  private final String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

  @BeforeAll
  static void load() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    filterSql = plain(Files.readString(FILTER_SQL));
    updateSql = plain(Files.readString(UPDATE_SQL));
  }

  private static String plain(String sql) {
    List<String> kept = new ArrayList<>();
    for (String line : sql.split("\n", -1)) {
      String t = line.strip();
      if (t.startsWith("\\")
          || t.equals("BEGIN;")
          || t.equals("COMMIT;")
          || t.equals("ROLLBACK;")) {
        continue;
      }
      kept.add(line);
    }
    return String.join("\n", kept);
  }

  // ── 실행 ────────────────────────────────────────────────────────────────────

  /** 앞선 실행이 남긴 임시 표·뷰·함수를 지운다(커밋 때 사라질 것들이지만 같은 트랜잭션에서 다시 돌리려면). */
  private static void resetTemp() {
    List<String> rels =
        jdbc.sql(
                """
                SELECT CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'S' THEN 'SEQUENCE' ELSE 'TABLE' END
                       || ' IF EXISTS pg_temp."' || c.relname || '"'
                FROM pg_class c
                WHERE c.relnamespace = pg_my_temp_schema() AND c.relkind IN ('r', 'v', 'S')
                """)
            .query(String.class)
            .list();
    for (String r : rels) {
      IntegrationDatabase.execute("DROP " + r + " CASCADE");
    }
    List<String> fns =
        jdbc.sql(
                "SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.pronamespace ="
                    + " pg_my_temp_schema()")
            .query(String.class)
            .list();
    for (String f : fns) {
      IntegrationDatabase.execute("DROP FUNCTION IF EXISTS " + f + " CASCADE");
    }
  }

  private static void inputTables(String source, boolean full, List<String> hidden) {
    resetTemp();
    // full 은 예약어라 칸 정의(full BOOLEAN)가 안 된다 — 적재(poi_update_load.sql)처럼 SELECT … AS full 로 만든다.
    IntegrationDatabase.execute(
        "CREATE TEMP TABLE t_raw (doc TEXT) ON COMMIT DROP;"
            + " CREATE TEMP TABLE t_hidden (source_id TEXT) ON COMMIT DROP;"
            + " CREATE TEMP TABLE t_upd_param ON COMMIT DROP AS SELECT '"
            + source.replace("'", "")
            + "'::text AS source, "
            + full
            + " AS full;");
    for (String h : hidden) {
      jdbc.sql("INSERT INTO t_hidden VALUES (:h)").param("h", h).update();
    }
  }

  private static void raw(List<String> docs) {
    for (String d : docs) {
      jdbc.sql("INSERT INTO t_raw VALUES (:d)").param("d", d).update();
    }
  }

  /** 거르기 → 업데이트. t_upd_summary 를 돌려준다. */
  private static Map<String, Object> run(
      String source, boolean full, List<String> docs, List<String> hidden) {
    inputTables(source, full, hidden);
    raw(docs);
    IntegrationDatabase.execute(filterSql);
    IntegrationDatabase.execute(updateSql);
    return jdbc.sql("SELECT * FROM t_upd_summary").query().singleRow();
  }

  // ── 입력 줄 ─────────────────────────────────────────────────────────────────

  private static double lat(int cluster) {
    return LAT + cluster * 1000.0 / M_PER_LAT;
  }

  private static double lng(double eastMeters) {
    return LNG + eastMeters / M_PER_LNG;
  }

  private static String doc(
      String id, String name, int cluster, double east, String kind, String bizMiddle) {
    return String.format(
        java.util.Locale.ROOT,
        "{\"id\":\"%s\",\"name\":\"%s\",\"lat\":\"%.8f\",\"lng\":\"%.8f\",\"addr\":\"시험도 시험시\","
            + "\"road\":\"\",\"tel\":\"\",\"kind\":\"%s\",\"biz_middle\":\"%s\",\"biz_lower\":\"\","
            + "\"region\":\"시험\",\"city\":\"시험시\",\"road_addr\":\"\",\"bld_no\":\"\"}",
        id,
        name,
        lat(cluster),
        lng(east),
        kind,
        bizMiddle);
  }

  private static String food(String id, String name, int cluster, double east) {
    return doc(id, name, cluster, east, "한식", "음식점");
  }

  private String store(String key) {
    return "MAIT" + tag + key;
  }

  private String tour(String key) {
    return "tour-it" + tag + key;
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  private static long poi(
      String sourceId, String name, int cluster, double east, String category, String group) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :c, :g)
            RETURNING id
            """)
        .param("s", sourceId)
        .param("n", name)
        .param("lng", lng(east))
        .param("lat", lat(cluster))
        .param("c", category)
        .param("g", group)
        .query(Long.class)
        .single();
  }

  private static void record(String sourceId, long poiId, String source, boolean active) {
    jdbc.sql("INSERT INTO poi_source (source_id, poi_id, source, active) VALUES (:s, :p, :src, :a)")
        .param("s", sourceId)
        .param("p", poiId)
        .param("src", source)
        .param("a", active)
        .update();
  }

  /** 편의시설 + 대표 번호 기록. */
  private static long poiWithRecord(
      String sourceId,
      String source,
      String name,
      int cluster,
      double east,
      String category,
      String group) {
    long id = poi(sourceId, name, cluster, east, category, group);
    record(sourceId, id, source, true);
    return id;
  }

  private static void close(long poiId) {
    jdbc.sql("UPDATE poi SET closed_at = now() - interval '1 day' WHERE id = :id")
        .param("id", poiId)
        .update();
  }

  private static boolean isOpen(long poiId) {
    return jdbc.sql("SELECT closed_at IS NULL FROM poi WHERE id = :id")
        .param("id", poiId)
        .query(Boolean.class)
        .single();
  }

  private static String representative(long poiId) {
    return jdbc.sql("SELECT source_id FROM poi WHERE id = :id")
        .param("id", poiId)
        .query(String.class)
        .single();
  }

  private static String name(long poiId) {
    return jdbc.sql("SELECT name FROM poi WHERE id = :id")
        .param("id", poiId)
        .query(String.class)
        .single();
  }

  /** 편의시설 자리와 (cluster, east) 사이 거리(m). */
  private static double distance(long poiId, int cluster, double east) {
    return jdbc.sql(
            "SELECT ST_Distance(geom, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) FROM"
                + " poi WHERE id = :id")
        .param("lng", lng(east))
        .param("lat", lat(cluster))
        .param("id", poiId)
        .query(Double.class)
        .single();
  }

  private static Long recordedPoi(String sourceId) {
    return jdbc.sql("SELECT poi_id FROM poi_source WHERE source_id = :s")
        .param("s", sourceId)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private static Boolean active(String sourceId) {
    return jdbc.sql("SELECT active FROM poi_source WHERE source_id = :s")
        .param("s", sourceId)
        .query(Boolean.class)
        .optional()
        .orElse(null);
  }

  private static long poiCount() {
    return jdbc.sql("SELECT count(*) FROM poi").query(Long.class).single();
  }

  private static long poisWithRepresentative(String sourceId) {
    return jdbc.sql("SELECT count(*) FROM poi WHERE source_id = :s")
        .param("s", sourceId)
        .query(Long.class)
        .single();
  }

  /** 픽스처(꼬리표가 든 번호)와 그 편의시설의 상태 — 멱등 비교용. */
  private List<String> snapshot() {
    String like = "%" + tag + "%";
    List<String> out = new ArrayList<>();
    out.addAll(
        jdbc.sql(
                """
                SELECT p.id || '|' || p.source_id || '|' || p.name || '|' || p.category || '|'
                       || round(ST_Y(p.geom::geometry)::numeric, 7) || ',' || round(ST_X(p.geom::geometry)::numeric, 7)
                       || '|' || (p.closed_at IS NULL)
                FROM poi p
                WHERE p.source_id LIKE :l
                   OR p.id IN (SELECT s.poi_id FROM poi_source s WHERE s.source_id LIKE :l)
                ORDER BY p.id
                """)
            .param("l", like)
            .query(String.class)
            .list());
    out.addAll(
        jdbc.sql(
                "SELECT source_id || '|' || poi_id || '|' || source || '|' || active FROM"
                    + " poi_source WHERE source_id LIKE :l ORDER BY source_id")
            .param("l", like)
            .query(String.class)
            .list());
    return out;
  }

  private static long number(Map<String, Object> summary, String key) {
    Object v = summary.get(key);
    return v == null ? -1 : ((Number) v).longValue();
  }

  // ── 상가정보 · 판 전체 ─────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "상가정보 판 전체 — 재발급 이어짐(리뷰 그대로)·폐업 숨김(관광공사 번호가 있어도)·이름 고침·새 가게·관광공사 편의시설에 번호·살아 있는"
          + " 지점과 안 이음·새 줄끼리 하나로·숫자만 다른 이름은 따로·다른 출처 그대로 · 같은 판 다시 = 변화 0 · 지우지 않음")
  void storeFullRun() {
    IntegrationDatabase.rolledBack(
        () -> {
          // 0 재발급: 옛 번호는 판에서 빠지고, 같은 가게가 10 m 안에 새 번호로.
          long reissue = poiWithRecord(store("re0"), "store", "시험국밥집", 0, 0, "한식", "food");
          UUID user = UUID.randomUUID();
          jdbc.sql("INSERT INTO app_user (id) VALUES (CAST(:id AS UUID))")
              .param("id", user.toString())
              .update();
          long review =
              jdbc.sql(
                      "INSERT INTO review (poi_id, user_id, rating) VALUES (:p, CAST(:u AS UUID),"
                          + " 5) RETURNING id")
                  .param("p", reissue)
                  .param("u", user.toString())
                  .query(Long.class)
                  .single();
          // 1 폐업
          long closed = poiWithRecord(store("cl"), "store", "시험닫힌집", 1, 0, "한식", "food");
          // 2 폐업 — 관광공사 번호도 있다
          long closedTour = poiWithRecord(store("ct"), "store", "시험둘다집", 2, 0, "한식", "food");
          record(tour("ct"), closedTour, "tour", true);
          // 3 이름 고침(번호 그대로)
          long renamed = poiWithRecord(store("rn"), "store", "시험옛이름", 3, 0, "한식", "food");
          // 5 관광공사에만 있던 음식점 — 상가정보 새 번호가 40 m 옆에
          long tourOnly = poiWithRecord(tour("to"), "tour", "시험관광식당", 5, 0, "음식점", "food");
          // 6 살아 있는 지점
          long alive = poiWithRecord(store("al"), "store", "시험치킨 강남점", 6, 0, "치킨", "food");
          // 9 다른 출처
          long otherTour = poiWithRecord(tour("ts"), "tour", "시험명소", 9, 0, "관광지", "sight");
          long otherTransit =
              poiWithRecord("train-it" + tag, "transit", "시험역", 9, 300, "기차역", "transit");

          List<String> input =
              List.of(
                  food(store("re1"), "시험국밥집", 0, 10),
                  food(store("rn"), "시험새이름", 3, 0),
                  food(store("nw"), "시험새가게", 4, 0),
                  food(store("to"), "시험관광식당", 5, 40),
                  doc(store("al"), "시험치킨 강남점", 6, 0, "치킨", "음식점"),
                  doc(store("br"), "시험치킨 역삼점", 6, 5, "치킨", "음식점"),
                  food(store("tw1"), "시험분식왕", 7, 0),
                  food(store("tw2"), "시험분식왕", 7, 10),
                  food(store("d5"), "시험관악산５호점", 8, 0),
                  food(store("d2"), "시험관악산２호점", 8, 10));

          long before = poiCount();
          Map<String, Object> first = run("store", true, input, List.of());
          List<String> afterFirst = snapshot();

          SoftAssertions s = new SoftAssertions();
          // 0 재발급
          s.assertThat(recordedPoi(store("re1"))).as("재발급: 새 번호가 같은 편의시설에").isEqualTo(reissue);
          s.assertThat(representative(reissue)).as("재발급: 대표 번호가 새 번호로").isEqualTo(store("re1"));
          s.assertThat(active(store("re0"))).as("재발급: 옛 번호 active=false").isFalse();
          s.assertThat(active(store("re1"))).as("재발급: 새 번호 active").isTrue();
          s.assertThat(isOpen(reissue)).as("재발급: 열려 있다").isTrue();
          s.assertThat(
                  jdbc.sql("SELECT poi_id FROM review WHERE id = :id")
                      .param("id", review)
                      .query(Long.class)
                      .single())
              .as("재발급: 리뷰 그대로")
              .isEqualTo(reissue);
          // 1·2 폐업
          s.assertThat(isOpen(closed)).as("폐업: 숨김").isFalse();
          s.assertThat(active(store("cl"))).as("폐업: 번호 active=false").isFalse();
          s.assertThat(isOpen(closedTour)).as("폐업: 관광공사 번호가 있어도 숨김").isFalse();
          s.assertThat(active(tour("ct"))).as("폐업: 넣지 않은 출처(관광공사) 번호는 그대로").isTrue();
          // 3 이름
          s.assertThat(name(renamed)).as("이름 고침").isEqualTo("시험새이름");
          s.assertThat(isOpen(renamed)).isTrue();
          // 4 새 가게
          Long fresh = recordedPoi(store("nw"));
          s.assertThat(fresh).as("새 가게: 번호 기록").isNotNull();
          if (fresh != null) {
            s.assertThat(representative(fresh)).as("새 가게: 대표 번호").isEqualTo(store("nw"));
            s.assertThat(isOpen(fresh)).isTrue();
          }
          // 5 관광공사 편의시설에 번호
          s.assertThat(recordedPoi(store("to"))).as("관광공사 편의시설에 상가정보 번호").isEqualTo(tourOnly);
          s.assertThat(representative(tourOnly)).as("대표 번호가 상가정보로").isEqualTo(store("to"));
          s.assertThat(distance(tourOnly, 5, 40)).as("좌표는 상가정보 것").isLessThan(1.0);
          s.assertThat(active(tour("to"))).as("관광공사 번호 그대로 살아 있음").isTrue();
          s.assertThat(isOpen(tourOnly)).isTrue();
          // 6 살아 있는 지점과 안 이음
          Long branch = recordedPoi(store("br"));
          s.assertThat(branch).as("다른 지점: 새 편의시설").isNotNull().isNotEqualTo(alive);
          s.assertThat(isOpen(alive)).isTrue();
          s.assertThat(representative(alive)).isEqualTo(store("al"));
          // 7 새 줄끼리 하나로
          Long tw1 = recordedPoi(store("tw1"));
          s.assertThat(tw1).as("새 줄 둘: 하나로").isNotNull().isEqualTo(recordedPoi(store("tw2")));
          s.assertThat(
                  jdbc.sql("SELECT count(*) FROM poi WHERE source_id IN (:a, :b)")
                      .param("a", store("tw1"))
                      .param("b", store("tw2"))
                      .query(Long.class)
                      .single())
              .as("새 줄 둘: 편의시설 하나")
              .isEqualTo(1L);
          // 8 숫자만 다른 이름
          s.assertThat(recordedPoi(store("d5")))
              .as("숫자만 다른 이름은 따로")
              .isNotNull()
              .isNotEqualTo(recordedPoi(store("d2")));
          // 9 다른 출처
          s.assertThat(isOpen(otherTour)).as("다른 출처(관광공사) 그대로").isTrue();
          s.assertThat(active(tour("ts"))).isTrue();
          s.assertThat(isOpen(otherTransit)).as("다른 출처(교통) 그대로").isTrue();
          s.assertThat(active("train-it" + tag)).isTrue();

          // 같은 판 다시
          Map<String, Object> second = run("store", true, input, List.of());
          s.assertThat(snapshot()).as("같은 판 다시: 픽스처 상태 그대로").isEqualTo(afterFirst);
          for (String k :
              List.of(
                  "linked_same_store",
                  "inserted",
                  "collapsed_new",
                  "deactivated_numbers",
                  "hidden",
                  "reopened")) {
            s.assertThat(number(second, k))
                .as("같은 판 다시: " + k + " = 0 (첫 실행 " + first.get(k) + ")")
                .isZero();
          }
          s.assertThat(poiCount()).as("편의시설 행은 줄지 않는다").isGreaterThanOrEqualTo(before);
          System.out.println("store 첫 실행: " + first + "\nstore 다시: " + second);
          s.assertAll();
          return null;
        });
  }

  // ── 관광공사 · 바뀐 것만 ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("관광공사 표출 중단 — 관광공사에만 있는 편의시설은 숨김, 상가정보 번호도 있으면 번호만 멈춤")
  void tourHidden() {
    IntegrationDatabase.rolledBack(
        () -> {
          long only = poiWithRecord(tour("a"), "tour", "시험관광지", 0, 0, "관광지", "sight");
          long both = poiWithRecord(store("b"), "store", "시험겸업식당", 1, 0, "한식", "food");
          record(tour("b"), both, "tour", true);
          long before = poiCount();

          run("tour", false, List.of(), List.of(tour("a"), tour("b")));

          assertThat(active(tour("a"))).isFalse();
          assertThat(isOpen(only)).as("관광공사 단독 → 숨김").isFalse();
          assertThat(active(tour("b"))).isFalse();
          assertThat(isOpen(both)).as("상가정보 번호가 있으면 숨기지 않음").isTrue();
          assertThat(active(store("b"))).isTrue();
          assertThat(poiCount()).isGreaterThanOrEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("관광공사 영문 표출 중단 — 편의시설은 그대로, 그 영문 상세만 안 나감(행은 남음)")
  void tourEnglishHidden() {
    IntegrationDatabase.rolledBack(
        () -> {
          String cid = "it" + tag + "en";
          long p = poiWithRecord(tour("c"), "tour", "시험영문명소", 0, 0, "관광지", "sight");
          record("tour-en-" + cid, p, "tour_en", true);
          jdbc.sql(
                  "INSERT INTO poi_detail (poi_id, lang, overview, tour_content_id) VALUES (:p,"
                      + " CAST('en' AS lang_code), 'english overview', :c)")
              .param("p", p)
              .param("c", cid)
              .update();
          String visible =
              "SELECT count(*) FROM poi_detail_visible WHERE poi_id = :p AND lang = 'en'";
          assertThat(jdbc.sql(visible).param("p", p).query(Long.class).single()).isEqualTo(1L);

          run("tour", false, List.of(), List.of("tour-en-" + cid));

          assertThat(active("tour-en-" + cid)).isFalse();
          assertThat(isOpen(p)).isTrue();
          assertThat(name(p)).isEqualTo("시험영문명소");
          assertThat(active(tour("c"))).isTrue();
          assertThat(jdbc.sql(visible).param("p", p).query(Long.class).single())
              .as("영문 상세 안 나감")
              .isZero();
          assertThat(
                  jdbc.sql("SELECT count(*) FROM poi_detail WHERE poi_id = :p AND lang = 'en'")
                      .param("p", p)
                      .query(Long.class)
                      .single())
              .as("행은 남음")
              .isEqualTo(1L);
          return null;
        });
  }

  @Test
  @DisplayName("숨긴 편의시설은 자기 판단 출처 번호가 돌아오면 다시 열린다")
  void reopensWhenOwnNumberReturns() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poi(tour("d"), "시험돌아온명소", 0, 0, "관광지", "sight");
          record(tour("d"), p, "tour", false);
          close(p);

          Map<String, Object> sum =
              run(
                  "tour",
                  false,
                  List.of(doc(tour("d"), "시험돌아온명소", 0, 0, "관광지", "관광명소")),
                  List.of());

          assertThat(active(tour("d"))).isTrue();
          assertThat(isOpen(p)).as("다시 열림").isTrue();
          assertThat(number(sum, "reopened")).isGreaterThanOrEqualTo(1L);
          return null;
        });
  }

  @Test
  @DisplayName("제외 분류(구내식당) 편의시설은 번호가 돌아와도 다시 열지 않는다 — t_ok 에 줄을 직접 넣어 업데이트 규칙만 본다")
  void excludedCategoryIsNotReopened() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poi(tour("e"), "시험사내식당", 0, 0, "구내식당", "food");
          record(tour("e"), p, "tour", false);
          close(p);

          // 업데이트는 거르기가 남긴 임시 표(t_ok 와 제외 분류 표)를 쓴다 — 빈 입력으로 거르기를 돌린 뒤 t_ok 에 제외 분류 줄을 직접 넣는다.
          inputTables("tour", false, List.of());
          IntegrationDatabase.execute(filterSql);
          jdbc.sql(
                  "INSERT INTO t_ok (source_id, name, lat, lng, category, category_group,"
                      + " has_addr_en, has_name_en, has_name_roman) VALUES (:s, '시험사내식당', :lat,"
                      + " :lng, '구내식당', 'food', false, false, false)")
              .param("s", tour("e"))
              .param("lat", lat(0))
              .param("lng", lng(0))
              .update();
          IntegrationDatabase.execute(updateSql);

          assertThat(isOpen(p)).as("제외 분류는 열지 않음").isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("같은 가게 정리로 숨긴 편의시설(번호 기록 없음)은 그 번호가 와도 다시 열리지 않는다 — 번호는 남는 쪽 것")
  void dedupeHiddenIsNeverReopened() {
    IntegrationDatabase.rolledBack(
        () -> {
          long survivor = poiWithRecord(tour("s1"), "tour", "시험합친명소", 0, 0, "관광지", "sight");
          record(tour("h"), survivor, "tour", true);
          long hidden = poi(tour("h"), "시험합친명소", 0, 1, "관광지", "sight");
          close(hidden);
          long before = poiCount();

          run("tour", false, List.of(doc(tour("h"), "시험합친명소", 0, 1, "관광지", "관광명소")), List.of());

          assertThat(isOpen(hidden)).as("정리로 숨긴 쪽은 그대로 숨김").isFalse();
          assertThat(isOpen(survivor)).isTrue();
          assertThat(recordedPoi(tour("h"))).isEqualTo(survivor);
          assertThat(poiCount()).as("새 편의시설로 되살아나지 않음").isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("관광공사 새 번호가 상가정보 편의시설과 같은 가게(100 m) — 번호만 기록, 이름·좌표·대표는 상가정보 것 · 같은 입력 다시 = 변화 0")
  void tourNewNumberJoinsStorePoi() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = poiWithRecord(store("f"), "store", "시험한정식당", 0, 0, "한식", "food");
          long before = poiCount();
          List<String> input = List.of(doc(tour("f"), "시험한정식당", 0, 60, "음식점", "음식점"));

          run("tour", false, input, List.of());
          List<String> afterFirst = snapshot();

          assertThat(recordedPoi(tour("f"))).isEqualTo(p);
          assertThat(representative(p)).isEqualTo(store("f"));
          assertThat(name(p)).isEqualTo("시험한정식당");
          assertThat(distance(p, 0, 0)).as("좌표는 상가정보 것").isLessThan(1.0);
          assertThat(poiCount()).as("중복 핀 없음").isEqualTo(before);
          assertThat(poisWithRepresentative(tour("f"))).isZero();

          Map<String, Object> second = run("tour", false, input, List.of());
          assertThat(snapshot()).isEqualTo(afterFirst);
          assertThat(number(second, "inserted")).isZero();
          assertThat(number(second, "linked_same_store")).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("입력에 이번 출처가 아닌 줄이 있으면 멈춘다")
  void otherSourceRowIsError() {
    IntegrationDatabase.rolledBack(
        () -> {
          assertThatThrownBy(
                  () -> run("tour", false, List.of(food(store("x"), "시험섞인줄", 0, 0)), List.of()))
              .hasStackTraceContaining("이번 출처");
          return null;
        });
  }

  // ── 줄 거르기 ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("줄 거르기 — 제외 분류 · 한국 밖 · 필수 칸 없음 · 갈래 밖은 버리고 이유별로 센다")
  void filterDropsAndCounts() {
    IntegrationDatabase.rolledBack(
        () -> {
          inputTables("store", false, List.of());
          raw(
              List.of(
                  food(store("ok"), "시험좋은집", 0, 0),
                  doc(store("yu"), "시험유흥", 0, 10, "일반 유흥 주점", "술집"),
                  doc(store("go"), "시험고시원", 0, 20, "기숙사/고시원", "숙박"),
                  doc(store("gu"), "시험사내식당", 0, 30, "구내식당", "음식점"),
                  food(store("far"), "시험바다밖", 0, 0)
                      .replace(String.format(java.util.Locale.ROOT, "%.8f", lat(0)), "40.10000000"),
                  food(store("nn"), "", 0, 40),
                  "{\"id\":\""
                      + store("nc")
                      + "\",\"name\":\"시험좌표없음\",\"lat\":\"\",\"lng\":\"\",\"kind\":\"한식\",\"biz_middle\":\"음식점\"}",
                  doc(store("bm"), "시험갈래밖", 0, 50, "기타", "기타업종")));
          IntegrationDatabase.execute(filterSql);

          List<String> kept =
              jdbc.sql("SELECT source_id FROM t_ok ORDER BY source_id").query(String.class).list();
          Map<String, Object> sum = jdbc.sql("SELECT * FROM t_filter_summary").query().singleRow();
          System.out.println("거르기 요약: " + sum);

          assertThat(kept).containsExactly(store("ok"));
          assertThat(number(sum, "input_rows")).isEqualTo(8L);
          assertThat(number(sum, "outside_groups")).isEqualTo(1L);
          assertThat(number(sum, "excluded_category")).isEqualTo(3L);
          assertThat(number(sum, "outside_korea")).isEqualTo(1L);
          assertThat(number(sum, "kept")).isEqualTo(1L);
          // 「버린 줄을 이유별로」 — 이유별 수 + 남긴 줄 = 입력 줄. 필수 칸(번호·이름·좌표)이 없어 버린 2 줄도 세어야 한다.
          long accounted =
              sum.entrySet().stream()
                  .filter(e -> !e.getKey().equals("input_rows") && e.getValue() instanceof Number)
                  .mapToLong(e -> ((Number) e.getValue()).longValue())
                  .sum();
          assertThat(accounted).as("요약이 모든 입력 줄을 설명한다 " + sum).isEqualTo(8L);
          return null;
        });
  }
}
