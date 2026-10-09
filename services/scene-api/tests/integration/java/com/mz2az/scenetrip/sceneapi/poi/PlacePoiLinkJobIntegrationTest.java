package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/place_poi_link.sql} — 촬영지 ↔ 편의시설 같은 곳 연결과 리뷰 옮기기(docs/project/plans/place-poi-link.md
 * §2~§5).
 *
 * <p>{@code just place-poi-link} 이 psql 로 돌리는 바로 그 파일을 JDBC 로 돌린다. psql 메타 명령({@code \set}·{@code
 * \copy}·{@code \echo}·{@code \if}…)과 파일 자체의 {@code BEGIN}/{@code COMMIT}/{@code ROLLBACK} 은 걷어 내고,
 * {@code \copy} 가 채울 판정 표({@code verdict})는 시험이 직접 채운다. 전부 {@link IntegrationDatabase#rolledBack}
 * 안이라 적재된 촬영지·편의시설에 대해 일이 계산한 연결·옮긴 리뷰도 함께 되돌려진다 — 공유 DB 는 그대로다.
 *
 * <p>픽스처는 적재 데이터와 겹치지 않는 바다 위(위도 34.9 · 경도 129.6 근처)에 둔다. 단언은 픽스처 행만 본다.
 */
@DisplayName("같은 곳 연결 일 — 후보·A/B/C·판정 파일·하나에 하나·멱등·리뷰 옮기기 (실제 DB)")
class PlacePoiLinkJobIntegrationTest {
  private static final Path LINK_SQL = Path.of("services/scene-api/seed/place_poi_link.sql");

  private static final double LAT = 34.9;
  private static final double LNG = 129.6;

  /** 위도 34.9 에서 경도 1 도 ≈ 91.3 km. */
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

  /** 판정 파일 한 줄. */
  private record Verdict(String placeKey, String poiSourceId, String verdict) {}

  /**
   * 일을 한 번 돌린다(이미 열린 트랜잭션 안). 파일의 임시 표는 ON COMMIT DROP 이라 커밋 때 사라진다 — 시험은 커밋하지 않으므로 다시 돌리기 전에 그것을
   * 대신 지운다.
   */
  private static void runJob(Verdict... verdicts) {
    IntegrationDatabase.execute(
        "DROP TABLE IF EXISTS pg_temp.verdict, pg_temp.candidate, pg_temp.wanted, pg_temp.was,"
            + " pg_temp.clash, pg_temp.moved; DROP FUNCTION IF EXISTS pg_temp.road_key(TEXT);");
    IntegrationDatabase.execute(beforeCopy);
    for (Verdict v : verdicts) {
      jdbc.sql("INSERT INTO verdict VALUES (:k, :s, :v, '시험')")
          .param("k", v.placeKey())
          .param("s", v.poiSourceId())
          .param("v", v.verdict())
          .update();
    }
    IntegrationDatabase.execute(afterCopy);
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  /** 촬영지 하나 — place_key 는 시험마다 고유({@link #key}). */
  private long place(String key, String name, String address, double dxMeters) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :k) RETURNING id")
            .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
            .param("lat", LAT)
            .param("k", key(key))
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name, address) VALUES (:p, 'ko', :n, :a)")
        .param("p", id)
        .param("n", name)
        .param("a", address)
        .update();
    return id;
  }

  private long poi(String source, String name, String address, double dxMeters) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group, address)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '카페', 'food', :a)
            RETURNING id
            """)
        .param("s", src(source))
        .param("n", name)
        .param("lng", LNG + dxMeters / METERS_PER_LNG_DEGREE)
        .param("lat", LAT)
        .param("a", address)
        .query(Long.class)
        .single();
  }

  private String key(String k) {
    return "it-link-" + tag + "-" + k;
  }

  private String src(String s) {
    return "it-link-" + tag + "-" + s;
  }

  private Verdict same(String placeKey, String poiSource) {
    return new Verdict(key(placeKey), src(poiSource), "same");
  }

  private Verdict not(String placeKey, String poiSource) {
    return new Verdict(key(placeKey), src(poiSource), "not");
  }

  /** 편의시설 → (촬영지 id, method). 연결이 없으면 없음. */
  private static Map<Long, String> links(long... poiIds) {
    Map<Long, String> out = new LinkedHashMap<>();
    for (long id : poiIds) {
      jdbc.sql("SELECT place_id || ' ' || method FROM place_poi_link WHERE poi_id = :id")
          .param("id", id)
          .query(String.class)
          .optional()
          .ifPresent(v -> out.put(id, v));
    }
    return out;
  }

  private static String linkOf(long poiId) {
    return links(poiId).get(poiId);
  }

  private static UUID user() {
    UUID id = UUID.randomUUID();
    return jdbc.sql("INSERT INTO app_user (id) VALUES (CAST(:id AS UUID)) RETURNING id")
        .param("id", id.toString())
        .query(UUID.class)
        .single();
  }

  private static long review(
      String column, long targetId, UUID user, int rating, String updatedAgo) {
    return jdbc.sql(
            "INSERT INTO review ("
                + column
                + ", user_id, rating, created_at, updated_at) VALUES (:t, CAST(NULLIF(:u, '') AS"
                + " UUID), :r, now() - CAST(:ago AS INTERVAL), now() - CAST(:ago AS INTERVAL))"
                + " RETURNING id")
        .param("t", targetId)
        .param("u", user == null ? "" : user.toString())
        .param("r", rating)
        .param("ago", updatedAgo)
        .query(Long.class)
        .single();
  }

  /** 리뷰 하나의 지금 자리: "place <id>" · "poi <id>" · 지워졌으면 null. */
  private static String whereIs(long reviewId) {
    return jdbc.sql(
            "SELECT CASE WHEN place_id IS NOT NULL THEN 'place ' || place_id ELSE 'poi ' || poi_id"
                + " END FROM review WHERE id = :id")
        .param("id", reviewId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  // ── 후보 (§2) ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A — 50 m 안·편의시설 이름(띄어쓰기 뺌)이 촬영지 이름 안·도로명+건물번호 같음(「경기도」/「경기」·괄호 법정동 무시) → address 로 자동 연결")
  void tierAAddressLinks() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("a", "수원 카페 몽테드", "경기도 수원시 팔달구 행궁로 12 (남창동)", 0);
          long q = poi("a", "카페 몽 테드", "경기 수원시 팔달구 행궁로 12", 30);
          runJob();
          assertThat(linkOf(q)).isEqualTo(p + " address");
          return null;
        });
  }

  @Test
  @DisplayName("반대 방향(촬영지 이름 ⊂ 편의시설 이름)은 후보가 아니다 — 주소가 같고 0 m 여도 연결하지 않는다")
  void reverseContainmentIsNotACandidate() {
    IntegrationDatabase.rolledBack(
        () -> {
          place("rev", "대구 이월드", "대구 달서구 두류공원로 200", 0);
          long q = poi("rev", "스타벅스 대구이월드", "대구 달서구 두류공원로 200", 0);
          runJob();
          assertThat(linkOf(q)).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("50 m 밖이면 이름·주소가 같아도 후보가 아니다")
  void beyondFiftyMetersIsNotACandidate() {
    IntegrationDatabase.rolledBack(
        () -> {
          place("far", "링크시험 몽테드", "경기 수원시 팔달구 행궁로 12", 0);
          long q = poi("far", "링크시험 몽테드", "경기 수원시 팔달구 행궁로 12", 70);
          runJob();
          assertThat(linkOf(q)).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("B — 편의시설 주소가 동까지라 견줄 수 없고 5 m 안이면 near 로 자동 연결, 5 m 밖이면(C) 연결하지 않는다")
  void tierBNearLinksOnlyWithinFiveMeters() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("b", "부산 소소주점", "부산 중구 광복로 55", 0);
          long near = poi("b1", "소소주점", "부산 중구 광복동", 2);
          long p2 = place("b2", "통영 얄개분식", "경남 통영시 중앙로 9", 400);
          long far = poi("b2", "얄개분식", "경남 통영시 중앙동", 412);
          runJob();
          assertThat(linkOf(near)).isEqualTo(p + " near");
          assertThat(linkOf(far)).isNull();
          assertThat(p2).isPositive();
          return null;
        });
  }

  @Test
  @DisplayName("C — 두 주소가 다 있는데 다르면 거리가 가까워도 판정 전까지 연결하지 않는다")
  void tierCNotLinkedWithoutVerdict() {
    IntegrationDatabase.rolledBack(
        () -> {
          place("c", "링크시험 명동", "서울 중구 명동길 14", 0);
          long q = poi("c", "명동", "서울 중구 명동8길 3", 1);
          runJob();
          assertThat(linkOf(q)).isNull();
          return null;
        });
  }

  // ── 판정 파일 (§3) ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("판정 same 이면 C 도 연결한다 — method manual")
  void sameVerdictLinksTierC() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("c", "링크시험 명동", "서울 중구 명동길 14", 0);
          long q = poi("c", "명동", "서울 중구 명동8길 3", 1);
          runJob(same("c", "c"));
          assertThat(linkOf(q)).isEqualTo(p + " manual");
          return null;
        });
  }

  @Test
  @DisplayName("판정 not 이면 A 여도 연결하지 않는다")
  void notVerdictOverridesTierA() {
    IntegrationDatabase.rolledBack(
        () -> {
          place("a", "코엑스 K팝 스퀘어", "서울 강남구 영동대로 513", 0);
          long q = poi("a", "코엑스", "서울 강남구 영동대로 513", 10);
          runJob(not("a", "a"));
          assertThat(linkOf(q)).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("판정 same 은 후보가 아니어도(멀고 이름이 달라도) 연결한다")
  void sameVerdictLinksNonCandidate() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("x", "링크시험 배다리 헌책방골목", "인천 동구 금곡로 10", 0);
          long q = poi("x", "전혀 다른 이름", "인천 동구 송림동", 300);
          runJob(same("x", "x"));
          assertThat(linkOf(q)).isEqualTo(p + " manual");
          return null;
        });
  }

  // ── 하나에 하나 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("한 편의시설이 두 촬영지의 자동 후보면 가까운 쪽 하나에만 붙는다. 한 촬영지에는 여럿이 붙을 수 있다")
  void onePoiOnePlaceNearestWins() {
    IntegrationDatabase.rolledBack(
        () -> {
          long nearPlace = place("n1", "링크시험 대학로 마로니에공원", "서울 종로구 대학로 104", 0);
          long farPlace = place("n2", "링크시험 대학로 마로니에공원 야외무대", "서울 종로구 대학로 104", 40);
          long q = poi("n", "마로니에공원", "서울 종로구 대학로 104", 10);
          long q2 = poi("n2", "대학로", "서울 종로구 대학로 104", 2);
          runJob();
          assertThat(linkOf(q)).isEqualTo(nearPlace + " address");
          // 「대학로」 도 두 촬영지 모두의 후보 — 가까운 쪽(2 m 인 nearPlace)
          assertThat(linkOf(q2)).isEqualTo(nearPlace + " address");
          assertThat(farPlace).isPositive();
          return null;
        });
  }

  @Test
  @DisplayName("사람 판정이 자동보다 먼저 — same 이 붙인 먼 촬영지가 가까운 자동 후보를 이긴다")
  void manualBeatsNearerAuto() {
    IntegrationDatabase.rolledBack(
        () -> {
          place("m1", "링크시험 대학로 마로니에공원", "서울 종로구 대학로 104", 0);
          long farPlace = place("m2", "링크시험 대학로 마로니에공원 야외무대", "서울 종로구 대학로 104", 40);
          long q = poi("m", "마로니에공원", "서울 종로구 대학로 104", 5);
          runJob(same("m2", "m"));
          assertThat(linkOf(q)).isEqualTo(farPlace + " manual");
          return null;
        });
  }

  // ── 멱등·맞추기 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("다시 돌려도 같다 — 연결 집합·method 가 그대로이고, 원하지 않는 기존 연결은 지운다")
  void idempotentAndReconciles() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("i", "수원 카페 몽테드", "경기 수원시 팔달구 행궁로 12", 0);
          long q = poi("i", "몽테드", "경기 수원시 팔달구 행궁로 12", 3);
          long stray = poi("stray", "아무 상관 없는 곳", "경기 수원시 팔달구 정조로 1", 20);
          // 지난번에 붙어 있던(이제는 원하지 않는) 연결
          jdbc.sql(
                  "INSERT INTO place_poi_link (poi_id, place_id, method) VALUES (:q, :p, 'manual')")
              .param("q", stray)
              .param("p", p)
              .update();

          runJob();
          Map<Long, String> first = links(q, stray);
          runJob();
          Map<Long, String> second = links(q, stray);

          assertThat(first).containsExactly(Map.entry(q, p + " address"));
          assertThat(second).isEqualTo(first);
          return null;
        });
  }

  @Test
  @DisplayName("판정을 not 으로 바꿔 다시 돌리면 연결이 풀린다 — 옮긴 리뷰는 촬영지에 남는다")
  void verdictChangeUnlinksButMovedReviewsStay() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("u", "수원 카페 몽테드", "경기 수원시 팔달구 행궁로 12", 0);
          long q = poi("u", "몽테드", "경기 수원시 팔달구 행궁로 12", 3);
          long r = review("poi_id", q, user(), 4, "1 day");

          runJob();
          assertThat(linkOf(q)).isEqualTo(p + " address");
          assertThat(whereIs(r)).isEqualTo("place " + p);

          runJob(not("u", "u"));
          assertThat(linkOf(q)).isNull();
          assertThat(whereIs(r)).isEqualTo("place " + p);
          return null;
        });
  }

  @Test
  @DisplayName("숨긴 촬영지에는 자동으로 붙지 않는다")
  void hiddenPlaceIsNotLinked() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("h", "수원 카페 몽테드", "경기 수원시 팔달구 행궁로 12", 0);
          jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", p).update();
          long q = poi("h", "몽테드", "경기 수원시 팔달구 행궁로 12", 3);
          runJob();
          assertThat(linkOf(q)).isNull();
          return null;
        });
  }

  // ── 리뷰 옮기기 (§5) ────────────────────────────────────────────────────────

  @Test
  @DisplayName("새로 연결된 편의시설의 리뷰는 촬영지로 옮긴다 — 한 사람이 양쪽에 썼으면 updated_at 이 늦은 것 하나만 남는다")
  void reviewsMoveAndClashKeepsLatest() {
    IntegrationDatabase.rolledBack(
        () -> {
          long p = place("r", "수원 카페 몽테드", "경기 수원시 팔달구 행궁로 12", 0);
          long q = poi("r", "몽테드", "경기 수원시 팔달구 행궁로 12", 3);
          UUID onlyPoi = user();
          UUID poiNewer = user();
          UUID placeNewer = user();
          UUID onlyPlace = user();
          long r1 = review("poi_id", q, onlyPoi, 5, "3 days");
          long r2Poi = review("poi_id", q, poiNewer, 4, "1 day");
          long r2Place = review("place_id", p, poiNewer, 2, "5 days");
          long r3Poi = review("poi_id", q, placeNewer, 1, "5 days");
          long r3Place = review("place_id", p, placeNewer, 3, "1 day");
          long r4 = review("place_id", p, onlyPlace, 5, "2 days");
          long anonymous = review("poi_id", q, null, 3, "4 days");

          runJob();

          assertThat(whereIs(r1)).isEqualTo("place " + p);
          assertThat(whereIs(r2Poi)).isEqualTo("place " + p);
          assertThat(whereIs(r2Place)).isNull();
          assertThat(whereIs(r3Poi)).isNull();
          assertThat(whereIs(r3Place)).isEqualTo("place " + p);
          assertThat(whereIs(r4)).isEqualTo("place " + p);
          assertThat(whereIs(anonymous)).isEqualTo("place " + p);
          assertThat(count("SELECT count(*) FROM review WHERE poi_id = " + q)).isZero();
          assertThat(count("SELECT count(*) FROM review WHERE place_id = " + p)).isEqualTo(5);

          runJob();
          assertThat(count("SELECT count(*) FROM review WHERE place_id = " + p)).isEqualTo(5);
          assertThat(whereIs(r2Poi)).isEqualTo("place " + p);
          assertThat(whereIs(r3Place)).isEqualTo("place " + p);
          return null;
        });
  }

  @Test
  @DisplayName("연결되지 않은 편의시설(C)의 리뷰는 옮기지 않는다")
  void unlinkedPoiReviewsStay() {
    IntegrationDatabase.rolledBack(
        () -> {
          place("rc", "링크시험 명동", "서울 중구 명동길 14", 0);
          long q = poi("rc", "명동", "서울 중구 명동8길 3", 1);
          long r = review("poi_id", q, user(), 4, "1 day");
          runJob();
          assertThat(whereIs(r)).isEqualTo("poi " + q);
          return null;
        });
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
