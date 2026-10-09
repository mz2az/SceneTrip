package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/course_pin_to_poi.sql} — 개인 핀으로 저장된 편의시설을 편의시설 항목으로 바꾸는 일(course-poi-item.md §5).
 *
 * <p>명세: 개인 핀 중 <b>이름이 정확히 같고 1 m 안</b>에 영업 중인 편의시설이 있으면 그 편의시설 항목으로 바꾼다(같은 곳으로 연결된 편의시설이면 촬영지로). 못
 * 찾으면 핀으로 둔다. 바꾼 뒤 아무 항목도 가리키지 않는 핀은 지운다. 몇 번을 돌려도 같다.
 *
 * <p>{@code just course-pin-to-poi} 가 psql 로 돌리는 바로 그 파일을 JDBC 로 돌린다. psql 메타 명령과 파일의 {@code
 * BEGIN}/{@code COMMIT}/{@code ROLLBACK} 은 걷어 내고 시험이 트랜잭션을 쥔다({@link
 * IntegrationDatabase#rolledBack}) — 공유 DB 는 그대로다. 픽스처는 적재 데이터와 겹치지 않는 바다 위(위도 34.9 · 경도 129.6 근처)에
 * 둔다.
 */
@DisplayName("개인 핀 → 편의시설 일 — 이름 같고 1 m 안·연결이면 촬영지·못 찾으면 그대로·고아 핀 지움·멱등 (실제 DB)")
class CoursePinToPoiJobIntegrationTest {
  private static final Path JOB_SQL = Path.of("services/scene-api/seed/course_pin_to_poi.sql");

  private static final double LAT = 34.9;
  private static final double LNG = 129.6;

  /** 위도 34.9 에서 경도 1 도 ≈ 91.3 km. */
  private static final double METERS_PER_LNG_DEGREE = 91_300.0;

  private static JdbcClient jdbc;
  private static String job;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void load() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    job = plain(Files.readString(JOB_SQL));
  }

  /** psql 메타 명령과 파일의 트랜잭션 문장을 걷어 낸다 — 시험이 트랜잭션을 쥔다. */
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

  /** 일을 한 번 돌린다(이미 열린 트랜잭션 안). 임시 표는 ON COMMIT DROP 이라 커밋하지 않는 시험이 대신 지운다. */
  private static void runJob() {
    IntegrationDatabase.execute("DROP TABLE IF EXISTS pg_temp.pin_match;");
    IntegrationDatabase.execute(job);
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  private long course() {
    UUID user = UUID.randomUUID();
    jdbc.sql("INSERT INTO app_user (id) VALUES (CAST(:id AS UUID))")
        .param("id", user.toString())
        .update();
    return jdbc.sql(
            "INSERT INTO course (user_id, title, day_count, origin) VALUES (CAST(:u AS UUID),"
                + " '시험', 1, 'self') RETURNING id")
        .param("u", user.toString())
        .query(Long.class)
        .single();
  }

  private long poi(String source, String name, double dxMeters) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group, address)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '카페', 'food', '부산')
            RETURNING id
            """)
        .param("s", "it-pin-" + tag + "-" + source)
        .param("n", name)
        .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
        .param("lat", LAT)
        .query(Long.class)
        .single();
  }

  private long place(String key, String name, double dxMeters) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :k) RETURNING id")
            .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
            .param("lat", LAT)
            .param("k", "it-pin-" + tag + "-" + key)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name, address) VALUES (:p, 'ko', :n, '부산')")
        .param("p", id)
        .param("n", name)
        .update();
    return id;
  }

  private static void link(long poiId, long placeId) {
    jdbc.sql("INSERT INTO place_poi_link (poi_id, place_id, method) VALUES (:q, :p, 'manual')")
        .param("q", poiId)
        .param("p", placeId)
        .update();
  }

  /** 핀과 그 핀을 가리키는 항목을 만들고 항목 id 를 돌려준다. */
  private static long pinItem(long courseId, String name, double dxMeters, int sortOrder) {
    long pin =
        jdbc.sql(
                "INSERT INTO custom_pin (course_id, name, category, geom) VALUES (:c, :n, 'food',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) RETURNING id")
            .param("c", courseId)
            .param("n", name)
            .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
            .param("lat", LAT)
            .query(Long.class)
            .single();
    return jdbc.sql(
            "INSERT INTO course_item (course_id, day_no, custom_pin_id, sort_order, dwell_min,"
                + " visited_at) VALUES (:c, 1, :p, :s, 75, now()) RETURNING id")
        .param("c", courseId)
        .param("p", pin)
        .param("s", sortOrder)
        .query(Long.class)
        .single();
  }

  /** "place <id>" · "poi <id>" · "pin" — 그리고 순서·체류·방문 기록. 지워졌으면 null. */
  private static String itemState(long itemId) {
    return jdbc.sql(
            "SELECT CASE WHEN place_id IS NOT NULL THEN 'place ' || place_id"
                + " WHEN poi_id IS NOT NULL THEN 'poi ' || poi_id ELSE 'pin' END"
                + " || ' ' || sort_order || ' ' || dwell_min || ' ' || (visited_at IS NOT NULL)"
                + " FROM course_item WHERE id = :id")
        .param("id", itemId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static long pinsOf(long courseId) {
    return jdbc.sql("SELECT count(*) FROM custom_pin WHERE course_id = :c")
        .param("c", courseId)
        .query(Long.class)
        .single();
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  // ── 바꾸기 ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("이름이 정확히 같고 1 m 안의 영업 중인 편의시설이면 편의시설 항목이 되고 핀은 지워진다 — 순서·체류·방문 기록은 그대로")
  void exactNameWithinOneMeterBecomesPoi() {
    IntegrationDatabase.rolledBack(
        () -> {
          long q = poi("exact", "핀시험 카페 " + tag, 0);
          long c = course();
          long item = pinItem(c, "핀시험 카페 " + tag, 0.5, 1);

          runJob();

          assertThat(itemState(item)).isEqualTo("poi " + q + " 1 75 true");
          assertThat(pinsOf(c)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("보이는 촬영지와 같은 곳으로 연결된 편의시설이면 촬영지 항목이 된다")
  void linkedPoiBecomesPlace() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("linked", "핀시험 촬영지 " + tag, 2);
          long q = poi("linked", "핀시험 연결 " + tag, 0);
          link(q, p);
          long c = course();
          long item = pinItem(c, "핀시험 연결 " + tag, 0, 1);

          runJob();

          assertThat(itemState(item)).isEqualTo("place " + p + " 1 75 true");
          assertThat(pinsOf(c)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("연결된 촬영지가 숨겨져 있으면 편의시설 항목이 된다")
  void linkToHiddenPlaceGivesPoi() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("hidden", "핀시험 숨긴 촬영지 " + tag, 2);
          jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", p).update();
          long q = poi("hidden", "핀시험 숨김 " + tag, 0);
          link(q, p);
          long c = course();
          long item = pinItem(c, "핀시험 숨김 " + tag, 0, 1);

          runJob();

          assertThat(itemState(item)).isEqualTo("poi " + q + " 1 75 true");
          return null;
        });
  }

  // ── 그대로 두기 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("1 m 밖이면 이름이 같아도 핀으로 남는다")
  void beyondOneMeterStaysPin() {
    IntegrationDatabase.rolledBack(
        () -> {
          poi("far", "핀시험 먼 곳 " + tag, 0);
          long c = course();
          long item = pinItem(c, "핀시험 먼 곳 " + tag, 3, 1);

          runJob();

          assertThat(itemState(item)).isEqualTo("pin 1 75 true");
          assertThat(pinsOf(c)).isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("이름이 정확히 같지 않으면(띄어쓰기 하나라도) 핀으로 남는다")
  void differentNameStaysPin() {
    IntegrationDatabase.rolledBack(
        () -> {
          poi("name", "핀시험 이름 " + tag, 0);
          long c = course();
          long item = pinItem(c, "핀시험  이름 " + tag, 0, 1);

          runJob();

          assertThat(itemState(item)).isEqualTo("pin 1 75 true");
          assertThat(pinsOf(c)).isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("폐업한 편의시설만 있으면 핀으로 남는다")
  void closedPoiStaysPin() {
    IntegrationDatabase.rolledBack(
        () -> {
          long q = poi("closed", "핀시험 폐업 " + tag, 0);
          jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", q).update();
          long c = course();
          long item = pinItem(c, "핀시험 폐업 " + tag, 0, 1);

          runJob();

          assertThat(itemState(item)).isEqualTo("pin 1 75 true");
          return null;
        });
  }

  @Test
  @DisplayName("한 코스에서 바뀐 핀만 지우고 남은 핀은 둔다")
  void deletesOnlyOrphanedPins() {
    IntegrationDatabase.rolledBack(
        () -> {
          long q = poi("mixed", "핀시험 섞기 " + tag, 0);
          long c = course();
          long converted = pinItem(c, "핀시험 섞기 " + tag, 0, 1);
          long kept = pinItem(c, "핀시험 숙소 " + tag, 100, 2);

          runJob();

          assertThat(itemState(converted)).isEqualTo("poi " + q + " 1 75 true");
          assertThat(itemState(kept)).isEqualTo("pin 2 75 true");
          assertThat(pinsOf(c)).isEqualTo(1);
          assertThat(
                  count(
                      "SELECT count(*) FROM custom_pin cp JOIN course_item ci ON ci.custom_pin_id"
                          + " = cp.id WHERE ci.id = "
                          + kept))
              .isEqualTo(1);
          return null;
        });
  }

  // ── 멱등 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("다시 돌려도 같다")
  void idempotent() {
    IntegrationDatabase.rolledBack(
        () -> {
          long q = poi("idem", "핀시험 멱등 " + tag, 0);
          long p = place("idem", "핀시험 멱등 촬영지 " + tag, 2);
          long linked = poi("idem2", "핀시험 멱등 연결 " + tag, 50);
          link(linked, p);
          long c = course();
          long a = pinItem(c, "핀시험 멱등 " + tag, 0, 1);
          long b = pinItem(c, "핀시험 멱등 연결 " + tag, 50, 2);
          long kept = pinItem(c, "핀시험 멱등 숙소 " + tag, 200, 3);

          runJob();
          List<String> first = List.of(itemState(a), itemState(b), itemState(kept));
          long pinsFirst = pinsOf(c);
          runJob();
          List<String> second = List.of(itemState(a), itemState(b), itemState(kept));

          assertThat(first)
              .containsExactly(
                  "poi " + q + " 1 75 true", "place " + p + " 2 75 true", "pin 3 75 true");
          assertThat(second).isEqualTo(first);
          assertThat(pinsOf(c)).isEqualTo(pinsFirst).isEqualTo(1);
          return null;
        });
  }

  // ── 미리 보기 ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("--dry-run 은 되돌린다 — 파일이 dry_run 변수일 때 ROLLBACK, 아닐 때만 COMMIT 한다")
  void dryRunRollsBack() throws IOException {
    String raw = Files.readString(JOB_SQL);
    int begin = raw.indexOf("\nBEGIN;");
    int ifDry = raw.indexOf("\\if :{?dry_run}");
    int rollback = raw.indexOf("ROLLBACK;", ifDry);
    int elseAt = raw.indexOf("\\else", ifDry);
    int commit = raw.indexOf("COMMIT;", elseAt);
    int endif = raw.indexOf("\\endif", commit);

    assertThat(begin).as("명시적 BEGIN 이 있어야 되돌릴 수 있다").isPositive();
    assertThat(raw).contains("\\set ON_ERROR_STOP on");
    assertThat(ifDry).as("dry_run 분기").isGreaterThan(begin);
    assertThat(rollback).isGreaterThan(ifDry).isLessThan(elseAt);
    assertThat(commit).isGreaterThan(elseAt).isLessThan(endif);
    // 분기 앞에서 COMMIT 하면 미리 보기가 이미 반영된다.
    assertThat(raw.substring(begin, ifDry)).doesNotContain("COMMIT;");
  }
}
