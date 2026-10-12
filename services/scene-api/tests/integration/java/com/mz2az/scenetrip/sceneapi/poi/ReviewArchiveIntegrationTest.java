package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 겹친 리뷰 보관과 적재 기록 — V30 의 {@code review_archive}·{@code data_load}, 그리고 {@code
 * seed/place_poi_link.sql} 이 겹친 리뷰를 지우기 전에 보관 표로 복사하는 일(docs/project/plans/remote-data-load.md §3
 * 「겹친 리뷰」·「기록」).
 *
 * <p>연결 SQL 은 {@link PlacePoiLinkJobIntegrationTest} 와 같은 방식으로 JDBC 로 돌린다 — psql 메타 명령과 파일의
 * BEGIN/COMMIT 은 걷어 내고 {@code \copy} 가 채울 판정 표는 비워 둔다(주소가 같은 자동 연결만 쓴다). 전부 {@link
 * IntegrationDatabase#rolledBack} 안이라 공유 DB 에 아무것도 남기지 않는다.
 *
 * <p>픽스처는 적재 데이터와 겹치지 않는 바다 위(위도 34.88 · 경도 129.62 근처)에 둔다.
 */
@DisplayName("겹친 리뷰 보관(review_archive)·적재 기록(data_load) — 연결 SQL 이 지우기 전에 복사 (실제 DB)")
class ReviewArchiveIntegrationTest {
  private static final Path LINK_SQL = Path.of("services/scene-api/seed/place_poi_link.sql");

  private static final double LAT = 34.88;
  private static final double LNG = 129.62;
  private static final double METERS_PER_LNG_DEGREE = 91_300.0;

  private static JdbcClient jdbc;
  private static String beforeCopy;
  private static String afterCopy;

  @BeforeAll
  static void load() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    String sql = Files.readString(LINK_SQL);
    int copy = sql.indexOf("\n\\copy verdict");
    assertThat(copy).as("연결 SQL 에 판정 파일 \\copy 가 있어야 한다").isPositive();
    beforeCopy = plain(sql.substring(0, copy));
    afterCopy = plain(sql.substring(copy + 1));
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

  /**
   * 연결 일을 한 번 돌린다(열린 트랜잭션 안). 파일의 임시 표는 ON COMMIT DROP 이라 커밋 때 사라지는데 시험은 커밋하지 않으므로, 다시 돌리기 전에 이 접속의
   * 임시 표를 이름과 상관없이 모두 지운다 — 구현이 임시 표를 더해도 시험이 그 이름을 알 필요가 없다.
   */
  private static void runJob() {
    IntegrationDatabase.execute(
        """
        DO $drop$
        DECLARE r record;
        BEGIN
          FOR r IN SELECT relname FROM pg_class
                   WHERE relnamespace = pg_my_temp_schema() AND relkind = 'r'
          LOOP
            EXECUTE format('DROP TABLE IF EXISTS pg_temp.%I CASCADE', r.relname);
          END LOOP;
        END
        $drop$;
        DROP FUNCTION IF EXISTS pg_temp.road_key(TEXT);
        """);
    IntegrationDatabase.execute(beforeCopy);
    IntegrationDatabase.execute(afterCopy);
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  /** 촬영지 하나와, 주소가 같고 3 m 떨어진 편의시설 하나 — 연결 일이 address 로 자동 연결한다. 반환: {place, poi}. */
  private long[] linkedPair(String k) {
    long place =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :k) RETURNING id")
            .param("lng", LNG)
            .param("lat", LAT)
            .param("k", "it-archive-" + tag + "-" + k)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name, address) VALUES (:p, 'ko', :n, :a)")
        .param("p", place)
        .param("n", "수원 카페 몽테드")
        .param("a", "경기 수원시 팔달구 행궁로 12")
        .update();
    long poi =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group, address)
                VALUES (:s, '몽테드', ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                        '카페', 'food', '경기 수원시 팔달구 행궁로 12')
                RETURNING id
                """)
            .param("s", "it-archive-" + tag + "-" + k)
            .param("lng", LNG + 3 / METERS_PER_LNG_DEGREE)
            .param("lat", LAT)
            .query(Long.class)
            .single();
    return new long[] {place, poi};
  }

  private static UUID user() {
    return jdbc.sql("INSERT INTO app_user (id) VALUES (CAST(:id AS UUID)) RETURNING id")
        .param("id", UUID.randomUUID().toString())
        .query(UUID.class)
        .single();
  }

  /** 리뷰 하나. created_at 은 updated_at 보다 하루 더 이르다(둘이 구별되게). */
  private static long review(
      String column, long targetId, UUID user, int rating, String body, String updatedAgo) {
    return jdbc.sql(
            "INSERT INTO review ("
                + column
                + ", user_id, rating, body, created_at, updated_at) VALUES (:t, CAST(NULLIF(:u,"
                + " '') AS UUID), :r, :b, now() - CAST(:ago AS INTERVAL) - INTERVAL '1 day',"
                + " now() - CAST(:ago AS INTERVAL)) RETURNING id")
        .param("t", targetId)
        .param("u", user == null ? "" : user.toString())
        .param("r", rating)
        .param("b", body)
        .param("ago", updatedAgo)
        .query(Long.class)
        .single();
  }

  private static void photo(long reviewId, String key, int order) {
    jdbc.sql("INSERT INTO review_image (review_id, storage_key, sort_order) VALUES (:r, :k, :o)")
        .param("r", reviewId)
        .param("k", key)
        .param("o", order)
        .update();
  }

  private static boolean exists(long reviewId) {
    return jdbc.sql("SELECT count(*) FROM review WHERE id = :id")
            .param("id", reviewId)
            .query(Long.class)
            .single()
        > 0;
  }

  /** 지우기 전 원본 행을 보관 표 칸 이름으로 찍어 둔다. */
  private static Map<String, Object> original(long reviewId) {
    return jdbc.sql(
            "SELECT id AS review_id, place_id, poi_id, user_id, rating, body, created_at,"
                + " updated_at FROM review WHERE id = :id")
        .param("id", reviewId)
        .query()
        .singleRow();
  }

  private static List<Map<String, Object>> archived(long reviewId) {
    return jdbc.sql(
            "SELECT review_id, place_id, poi_id, user_id, rating, body, created_at, updated_at,"
                + " reason, kept_review_id, archived_at FROM review_archive WHERE review_id = :id")
        .param("id", reviewId)
        .query()
        .listOfRows();
  }

  private static List<String> archivedPhotoKeys(long reviewId) {
    return jdbc.sql("SELECT photo_keys FROM review_archive WHERE review_id = :id")
        .param("id", reviewId)
        .query(
            (rs, i) -> {
              Array a = rs.getArray(1);
              return a == null ? null : Arrays.asList((String[]) a.getArray());
            })
        .single();
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  /** 원본의 칸이 보관 행에 그대로 옮겨졌는지. */
  private static void assertCopied(Map<String, Object> before, Map<String, Object> row) {
    for (String col :
        List.of(
            "review_id",
            "place_id",
            "poi_id",
            "user_id",
            "rating",
            "body",
            "created_at",
            "updated_at")) {
      assertThat(normalize(row.get(col))).as(col).isEqualTo(normalize(before.get(col)));
    }
    assertThat((String) row.get("reason")).as("reason").isNotBlank();
    assertThat(row.get("archived_at")).as("archived_at").isNotNull();
  }

  private static Object normalize(Object v) {
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v != null && v.getClass().getName().equals("org.postgresql.util.PGobject")) {
      return v.toString();
    }
    return v == null ? null : v.toString();
  }

  // ── 겹친 리뷰 보관 ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("편의시설 리뷰가 오래됐으면 — 그 리뷰가 지워지고 원본 칸 그대로(poi_id 포함) 보관된다, kept_review_id 는 촬영지 리뷰")
  void olderPoiReviewIsArchived() {
    IntegrationDatabase.rolledBack(
        () -> {
          long[] pq = linkedPair("poi-old");
          UUID u = user();
          long older = review("poi_id", pq[1], u, 2, "편의시설에 쓴 옛 글", "5 days");
          long newer = review("place_id", pq[0], u, 5, "촬영지에 쓴 새 글", "1 day");
          Map<String, Object> before = original(older);

          runJob();

          assertThat(exists(older)).isFalse();
          assertThat(exists(newer)).isTrue();
          List<Map<String, Object>> rows = archived(older);
          assertThat(rows).hasSize(1);
          assertCopied(before, rows.get(0));
          assertThat(((Number) rows.get(0).get("kept_review_id")).longValue()).isEqualTo(newer);
          assertThat(rows.get(0).get("place_id")).isNull();
          assertThat(((Number) rows.get(0).get("poi_id")).longValue()).isEqualTo(pq[1]);
          assertThat(archived(newer)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("촬영지 리뷰가 오래됐으면 — 촬영지 리뷰가 지워지고 보관된다, kept_review_id 는 옮겨 온 편의시설 리뷰")
  void olderPlaceReviewIsArchived() {
    IntegrationDatabase.rolledBack(
        () -> {
          long[] pq = linkedPair("place-old");
          UUID u = user();
          long older = review("place_id", pq[0], u, 3, null, "7 days");
          long newer = review("poi_id", pq[1], u, 4, "새 글", "2 hours");
          Map<String, Object> before = original(older);

          runJob();

          assertThat(exists(older)).isFalse();
          assertThat(exists(newer)).isTrue();
          List<Map<String, Object>> rows = archived(older);
          assertThat(rows).hasSize(1);
          assertCopied(before, rows.get(0));
          assertThat(((Number) rows.get(0).get("kept_review_id")).longValue()).isEqualTo(newer);
          assertThat(((Number) rows.get(0).get("place_id")).longValue()).isEqualTo(pq[0]);
          assertThat(rows.get(0).get("poi_id")).isNull();
          assertThat(rows.get(0).get("body")).isNull();
          assertThat(archived(newer)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("사진 키 — 지운 리뷰의 저장소 키가 사진 순서(sort_order)대로 보관된다. 넣은 순서와 달라도")
  void photoKeysKeptInPhotoOrder() {
    IntegrationDatabase.rolledBack(
        () -> {
          long[] pq = linkedPair("photos");
          UUID u = user();
          long older = review("poi_id", pq[1], u, 4, "사진 셋", "4 days");
          String k0 = "reviews/it-archive-" + tag + "/zz-first.jpg";
          String k1 = "reviews/it-archive-" + tag + "/aa-second.jpg";
          String k2 = "reviews/it-archive-" + tag + "/mm-third.jpg";
          // 넣는 순서(id 순)·키 이름 순이 사진 순서와 다르게
          photo(older, k2, 2);
          photo(older, k0, 0);
          photo(older, k1, 1);
          long newer = review("place_id", pq[0], u, 5, null, "1 day");
          photo(newer, "reviews/it-archive-" + tag + "/kept.jpg", 0);

          runJob();

          assertThat(exists(older)).isFalse();
          assertThat(archivedPhotoKeys(older)).containsExactly(k0, k1, k2);
          assertThat(
                  count(
                      "SELECT count(*) FROM review_image WHERE review_id = "
                          + newer
                          + " AND storage_key LIKE 'reviews/it-archive-%'"))
              .as("남은 리뷰의 사진은 그대로")
              .isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("사진 없는 리뷰를 보관하면 photo_keys 는 빈 배열(NULL 아님)")
  void noPhotosIsEmptyArray() {
    IntegrationDatabase.rolledBack(
        () -> {
          long[] pq = linkedPair("nophoto");
          UUID u = user();
          long older = review("place_id", pq[0], u, 1, "사진 없음", "9 days");
          review("poi_id", pq[1], u, 5, null, "1 day");

          runJob();

          assertThat(exists(older)).isFalse();
          assertThat(archivedPhotoKeys(older)).isNotNull().isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("겹치지 않은 리뷰는 보관하지 않는다 — 한쪽에만 쓴 사람·탈퇴한(작성자 없는) 리뷰는 옮기거나 남기고 보관 표에 없다")
  void nonClashingReviewsAreNotArchived() {
    IntegrationDatabase.rolledBack(
        () -> {
          long[] pq = linkedPair("noclash");
          long onlyPoi = review("poi_id", pq[1], user(), 5, null, "3 days");
          long onlyPlace = review("place_id", pq[0], user(), 4, null, "2 days");
          long anonPoi = review("poi_id", pq[1], null, 3, null, "6 days");
          long anonPlace = review("place_id", pq[0], null, 2, null, "1 day");
          long archiveBefore = count("SELECT count(*) FROM review_archive");

          runJob();

          for (long r : List.of(onlyPoi, onlyPlace, anonPoi, anonPlace)) {
            assertThat(exists(r)).as("review %d", r).isTrue();
            assertThat(archived(r)).as("archive of %d", r).isEmpty();
          }
          assertThat(count("SELECT count(*) FROM review WHERE place_id = " + pq[0])).isEqualTo(4);
          assertThat(count("SELECT count(*) FROM review_archive")).isEqualTo(archiveBefore);
          return null;
        });
  }

  @Test
  @DisplayName("다시 돌리면 새로 보관하지 않는다 — 겹침이 없으면 보관 표가 그대로")
  void secondRunArchivesNothingMore() {
    IntegrationDatabase.rolledBack(
        () -> {
          long[] pq = linkedPair("rerun");
          UUID u = user();
          long older = review("poi_id", pq[1], u, 2, null, "5 days");
          long newer = review("place_id", pq[0], u, 5, null, "1 day");
          review("poi_id", pq[1], user(), 4, null, "2 days");

          runJob();
          long afterFirst = count("SELECT count(*) FROM review_archive");
          assertThat(archived(older)).hasSize(1);

          runJob();

          assertThat(count("SELECT count(*) FROM review_archive")).isEqualTo(afterFirst);
          assertThat(archived(older)).hasSize(1);
          assertThat(archived(newer)).isEmpty();
          assertThat(exists(newer)).isTrue();
          assertThat(count("SELECT count(*) FROM review WHERE place_id = " + pq[0])).isEqualTo(2);
          return null;
        });
  }

  @Test
  @DisplayName("외래 키가 없다 — 가리키던 촬영지·편의시설·사용자를 지워도 보관 행은 그대로 남는다")
  void archiveSurvivesDeletingReferents() {
    IntegrationDatabase.rolledBack(
        () -> {
          long[] pq = linkedPair("nofk");
          UUID u = user();
          long older = review("poi_id", pq[1], u, 2, "옛 글", "5 days");
          review("place_id", pq[0], u, 5, null, "1 day");
          runJob();
          assertThat(archived(older)).hasSize(1);

          jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", pq[1]).update();
          jdbc.sql("DELETE FROM place WHERE id = :id").param("id", pq[0]).update();
          jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
              .param("id", u.toString())
              .update();

          List<Map<String, Object>> rows = archived(older);
          assertThat(rows).hasSize(1);
          assertThat(((Number) rows.get(0).get("poi_id")).longValue()).isEqualTo(pq[1]);
          assertThat(rows.get(0).get("user_id").toString()).isEqualTo(u.toString());
          assertThat(rows.get(0).get("body")).isEqualTo("옛 글");
          assertThat(
                  count(
                      "SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                          + " AND conrelid = 'review_archive'::regclass"))
              .isZero();
          return null;
        });
  }

  // ── 적재 기록 ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("data_load — JSON 요약 한 줄을 받고 loaded_at 은 기본값(now)으로 찬다")
  void dataLoadAcceptsJsonSummary() {
    IntegrationDatabase.rolledBack(
        () -> {
          long id =
              jdbc.sql("INSERT INTO data_load (summary) VALUES (CAST(:s AS JSONB)) RETURNING id")
                  .param(
                      "s",
                      "{\"commit\":\"abc123\",\"csv_sha256\":\"deadbeef\",\"poi_release\":\"2026-10\","
                          + "\"counts\":{\"place\":479,\"poi\":910394},\"seconds\":78}")
                  .query(Long.class)
                  .single();
          Map<String, Object> row =
              jdbc.sql(
                      "SELECT loaded_at IS NOT NULL AS has_time, loaded_at = now() AS is_now,"
                          + " summary->>'commit' AS commit_id,"
                          + " (summary->'counts'->>'poi')::bigint AS pois"
                          + " FROM data_load WHERE id = :id")
                  .param("id", id)
                  .query()
                  .singleRow();
          assertThat(row.get("has_time")).isEqualTo(true);
          assertThat(row.get("is_now")).isEqualTo(true);
          assertThat(row.get("commit_id")).isEqualTo("abc123");
          assertThat(((Number) row.get("pois")).longValue()).isEqualTo(910_394L);
          return null;
        });
  }
}
