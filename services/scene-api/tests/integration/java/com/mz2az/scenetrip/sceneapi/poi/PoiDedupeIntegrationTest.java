package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi_dedupe.sql} — 이미 남아 있는 같은 가게 중복 정리(poi-load-cleanup.md §8 2단계, MZ2AZ-491).
 *
 * <p>같은 가게: 열린 편의시설 둘이 1.5 m 안 · 같은 갈래 · 이름이 같다(NFKC 정규화 뒤 대괄호 꼬리를 빼고 소문자 영숫자·한글만). 전화가 둘 다 있고 다르면서
 * 세부 종류도 다르면 합치지 않는다. 남는 쪽: 「~기타」 가 아닌 쪽 → 전화 있는 쪽 → 번호가 짧은 쪽 → 작은 쪽, 셋 이상 이어지면 가장 나은 하나로. 숨긴 쪽은
 * closed_at 만 — 리뷰(겹치면 updated_at 이 늦은 것만, 나머지는 review_archive)·코스 항목·글 항목·번호 기록과, 남는 쪽에 없을 때 상세·영어
 * 칸·사진·촬영지 연결을 옮긴다.
 *
 * <p>{@code just poi-dedupe} 이 psql 로 돌리는 바로 그 파일을 메타 명령({@code \set}·{@code \echo}·{@code \if})과
 * 트랜잭션 문장만 걷어 JDBC 로 돌린다 — 그래서 {@code dry_run} 갈래는 여기서 볼 수 없다. 파일은 DB 전체를 보지만 전부 {@link
 * IntegrationDatabase#rolledBack} 안이라 공유 DB 는 그대로다. 픽스처는 적재 데이터와 겹치지 않는 바다 위(위도 35.7 · 경도 131.3
 * 근처)에 무리마다 500 m 씩 떨어뜨려 두고, 단언은 픽스처 행만 본다. 명세만 보고 쓴 시험.
 */
@DisplayName("같은 가게 정리 — 판정·남는 쪽·옮기기·사슬·멱등 (실제 DB)")
class PoiDedupeIntegrationTest {
  private static final Path DEDUPE_SQL = Path.of("services/scene-api/seed/poi_dedupe.sql");

  private static final double LAT = 35.7;
  private static final double LNG = 131.3;
  private static final double M_PER_LAT = 111_320.0;

  /** 위도 35.7 에서 경도 1 도 ≈ 90.4 km. */
  private static final double M_PER_LNG = 90_400.0;

  private static JdbcClient jdbc;
  private static String dedupeSql;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void load() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    dedupeSql = plain(Files.readString(DEDUPE_SQL));
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

  /** 한 번 돌린다(열린 트랜잭션 안). 앞선 실행의 임시 표는 커밋 때 사라질 것이라 다시 돌리기 전에 대신 지운다. */
  private static void runDedupe() {
    List<String> temps =
        jdbc.sql(
                """
                SELECT c.relname FROM pg_class c
                WHERE c.relpersistence = 't' AND c.relkind = 'r'
                  AND c.relnamespace = pg_my_temp_schema()
                """)
            .query(String.class)
            .list();
    for (String t : temps) {
      IntegrationDatabase.execute("DROP TABLE IF EXISTS pg_temp.\"" + t + "\"");
    }
    IntegrationDatabase.execute(dedupeSql);
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  /** 편의시설 하나 만들기. 자리는 무리(cluster) 기준점에서 동쪽 eastMeters. 대표 번호 기록도 함께. */
  private final class Poi {
    private final String key;
    private String name = "시험가게";
    private String category = "시험한식";
    private String group = "food";
    private String tel;
    private int cluster;
    private double east;

    Poi(String key) {
      this.key = key;
    }

    Poi name(String v) {
      name = v;
      return this;
    }

    Poi category(String v) {
      category = v;
      return this;
    }

    Poi group(String v) {
      group = v;
      return this;
    }

    Poi tel(String v) {
      tel = v;
      return this;
    }

    Poi at(int cluster, double eastMeters) {
      this.cluster = cluster;
      this.east = eastMeters;
      return this;
    }

    long insert() {
      String sourceId = src(key);
      long id =
          jdbc.sql(
                  """
                  INSERT INTO poi (source_id, name, geom, category, category_group, tel)
                  VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :c, :g, :t)
                  RETURNING id
                  """)
              .param("s", sourceId)
              .param("n", name)
              .param("lng", LNG + east / M_PER_LNG)
              .param("lat", LAT + cluster * 500.0 / M_PER_LAT)
              .param("c", category)
              .param("g", group)
              .param("t", tel)
              .query(Long.class)
              .single();
      record(sourceId, id, "store");
      return id;
    }
  }

  private Poi poi(String key) {
    return new Poi(key);
  }

  /** 시험 번호 — 길이는 key 의 길이로만 갈린다(앞부분은 같다). */
  private String src(String key) {
    return "it-dd-" + tag + "-" + key;
  }

  private static void record(String sourceId, long poiId, String source) {
    jdbc.sql("INSERT INTO poi_source (source_id, poi_id, source) VALUES (:s, :p, :src)")
        .param("s", sourceId)
        .param("p", poiId)
        .param("src", source)
        .update();
  }

  private static boolean isOpen(long poiId) {
    return jdbc.sql("SELECT closed_at IS NULL FROM poi WHERE id = :id")
        .param("id", poiId)
        .query(Boolean.class)
        .single();
  }

  private static Long recordedPoi(String sourceId) {
    return jdbc.sql("SELECT poi_id FROM poi_source WHERE source_id = :s")
        .param("s", sourceId)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private static UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO app_user (id) VALUES (CAST(:id AS UUID))")
        .param("id", id.toString())
        .update();
    return id;
  }

  private static long review(long poiId, UUID user, String updatedAgo) {
    return jdbc.sql(
            """
            INSERT INTO review (poi_id, user_id, rating, created_at, updated_at)
            VALUES (:p, CAST(:u AS UUID), 4, now() - CAST(:ago AS INTERVAL), now() - CAST(:ago AS INTERVAL))
            RETURNING id
            """)
        .param("p", poiId)
        .param("u", user.toString())
        .param("ago", updatedAgo)
        .query(Long.class)
        .single();
  }

  /** 리뷰가 지금 붙은 편의시설, 지워졌으면 null. */
  private static Long reviewPoi(long reviewId) {
    return jdbc.sql("SELECT poi_id FROM review WHERE id = :id")
        .param("id", reviewId)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private static long archived(long reviewId) {
    return jdbc.sql("SELECT count(*) FROM review_archive WHERE review_id = :id")
        .param("id", reviewId)
        .query(Long.class)
        .single();
  }

  private static long scalar(String sql, long id) {
    return jdbc.sql(sql).param("id", id).query(Long.class).single();
  }

  private static String text(String sql, long id) {
    return jdbc.sql(sql).param("id", id).query(String.class).optional().orElse(null);
  }

  private List<String> fixtureImageUrls(long poiId) {
    return jdbc.sql("SELECT url FROM poi_image WHERE poi_id = :id ORDER BY url")
        .param("id", poiId)
        .query(String.class)
        .list();
  }

  private String img(String name) {
    return "https://img.test/dd/" + tag + "/" + name + ".jpg";
  }

  private void image(long poiId, String name) {
    jdbc.sql("INSERT INTO poi_image (poi_id, url, sort_order, source) VALUES (:p, :u, 10, 'own')")
        .param("p", poiId)
        .param("u", img(name))
        .update();
  }

  private static void detail(long poiId, String lang, String overview) {
    jdbc.sql(
            """
            INSERT INTO poi_detail (poi_id, lang, overview, tour_content_id)
            VALUES (:p, CAST(:l AS lang_code), :o, :o)
            """)
        .param("p", poiId)
        .param("l", lang)
        .param("o", overview)
        .update();
  }

  private long fixturePoiCount() {
    return jdbc.sql("SELECT count(*) FROM poi WHERE source_id LIKE :p")
        .param("p", src("") + "%")
        .query(Long.class)
        .single();
  }

  // ── 옮기기 ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("한쪽을 숨기고 리뷰·코스·글 항목·번호·상세·영어 칸·사진·촬영지 연결을 남는 쪽으로 — 겹친 리뷰는 늦은 것만, 나머지는 보관")
  void hidesLoserAndMovesEverything() {
    IntegrationDatabase.rolledBack(
        () -> {
          long keep = poi("w").tel("010-0000-0001").at(0, 0).insert();
          long lose = poi("l").at(0, 0.8).insert();
          String extra = "tour-" + src("extra");
          record(extra, lose, "tour");

          // 리뷰: u1 은 숨는 쪽이 늦다 → 숨는 쪽 것을 남긴다. u3 은 남는 쪽이 늦다. u2 는 숨는 쪽에만.
          UUID u1 = user();
          UUID u2 = user();
          UUID u3 = user();
          long r1Lose = review(lose, u1, "1 day");
          long r1Keep = review(keep, u1, "5 days");
          long r2Lose = review(lose, u2, "3 days");
          long r3Keep = review(keep, u3, "1 day");
          long r3Lose = review(lose, u3, "4 days");

          // 코스 항목·글 항목
          UUID owner = user();
          long course =
              jdbc.sql(
                      "INSERT INTO course (user_id, title, day_count, origin) VALUES (CAST(:u AS"
                          + " UUID), '시험', 1, 'self') RETURNING id")
                  .param("u", owner.toString())
                  .query(Long.class)
                  .single();
          long item =
              jdbc.sql(
                      "INSERT INTO course_item (course_id, day_no, sort_order, poi_id) VALUES (:c,"
                          + " 1, 1, :p) RETURNING id")
                  .param("c", course)
                  .param("p", lose)
                  .query(Long.class)
                  .single();
          long post =
              jdbc.sql(
                      "INSERT INTO community_post (user_id, title, body) VALUES (CAST(:u AS UUID),"
                          + " '시험 글', '본문') RETURNING id")
                  .param("u", owner.toString())
                  .query(Long.class)
                  .single();
          jdbc.sql(
                  "INSERT INTO community_post_item (post_id, day_no, sort_order, poi_id, dwell_min)"
                      + " VALUES (:post, 1, 1, :p, 45)")
              .param("post", post)
              .param("p", lose)
              .update();

          // 상세: ko 는 숨는 쪽에만(옮긴다), en 은 둘 다(남는 쪽 것을 둔다). 영어 칸: 숨는 쪽에만.
          detail(lose, "ko", "숨는 쪽 개요");
          detail(lose, "en", "loser english");
          detail(keep, "en", "keeper english");
          jdbc.sql("INSERT INTO poi_i18n (poi_id, lang, name) VALUES (:p, 'en', 'Loser Name')")
              .param("p", lose)
              .update();
          // 사진: only 는 숨는 쪽에만, both 는 양쪽에
          image(lose, "only");
          image(lose, "both");
          image(keep, "both");
          // 촬영지 연결: 숨는 쪽에만
          long place =
              jdbc.sql(
                      "INSERT INTO place (type, geom, place_key) VALUES ('시험',"
                          + " ST_SetSRID(ST_MakePoint(131.3, 35.7), 4326)::geography, :k) RETURNING"
                          + " id")
                  .param("k", src("place"))
                  .query(Long.class)
                  .single();
          jdbc.sql(
                  "INSERT INTO place_poi_link (poi_id, place_id, method) VALUES (:p, :pl,"
                      + " 'manual')")
              .param("p", lose)
              .param("pl", place)
              .update();

          runDedupe();

          assertThat(isOpen(keep)).isTrue();
          assertThat(isOpen(lose)).isFalse();
          assertThat(fixturePoiCount()).as("편의시설 행은 지우지 않는다").isEqualTo(2L);

          // 리뷰
          assertThat(reviewPoi(r1Lose)).isEqualTo(keep);
          assertThat(reviewPoi(r1Keep)).isNull();
          assertThat(archived(r1Keep)).isEqualTo(1L);
          assertThat(reviewPoi(r2Lose)).isEqualTo(keep);
          assertThat(reviewPoi(r3Keep)).isEqualTo(keep);
          assertThat(reviewPoi(r3Lose)).isNull();
          assertThat(archived(r3Lose)).isEqualTo(1L);
          assertThat(scalar("SELECT count(*) FROM review WHERE poi_id = :id", lose)).isZero();
          assertThat(archived(r2Lose)).isZero();

          // 코스·글 항목
          assertThat(scalar("SELECT poi_id FROM course_item WHERE id = :id", item)).isEqualTo(keep);
          assertThat(scalar("SELECT poi_id FROM community_post_item WHERE post_id = :id", post))
              .isEqualTo(keep);

          // 번호 기록
          assertThat(recordedPoi(src("l"))).isEqualTo(keep);
          assertThat(recordedPoi(extra)).isEqualTo(keep);
          assertThat(recordedPoi(src("w"))).isEqualTo(keep);

          // 상세·영어 칸
          assertThat(
                  text("SELECT overview FROM poi_detail WHERE poi_id = :id AND lang = 'ko'", keep))
              .isEqualTo("숨는 쪽 개요");
          assertThat(
                  text("SELECT overview FROM poi_detail WHERE poi_id = :id AND lang = 'en'", keep))
              .isEqualTo("keeper english");
          assertThat(text("SELECT name FROM poi_i18n WHERE poi_id = :id AND lang = 'en'", keep))
              .isEqualTo("Loser Name");

          // 사진
          assertThat(fixtureImageUrls(keep)).containsExactly(img("both"), img("only"));

          // 촬영지 연결
          assertThat(scalar("SELECT place_id FROM place_poi_link WHERE poi_id = :id", keep))
              .isEqualTo(place);
          return null;
        });
  }

  // ── 남는 쪽 ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("남는 쪽 순서 — 「~기타」 아닌 쪽 → 전화 있는 쪽 → 번호가 짧은 쪽 → 작은 쪽")
  void keepOrder() {
    IntegrationDatabase.rolledBack(
        () -> {
          // 1. 기타 쪽이 전화도 있고 번호도 짧지만 기타가 아닌 쪽이 남는다
          long etc = poi("a").category("시험분류기타").tel("02-000-0001").at(1, 0).insert();
          long specific = poi("bbbb").category("시험분류").at(1, 0.7).insert();
          // 2. 전화 있는 쪽 — 번호가 길어도
          long noTel = poi("c").at(2, 0).insert();
          long withTel = poi("dddd").tel("02-000-0002").at(2, 0.7).insert();
          // 3. 번호가 짧은 쪽
          long longNo = poi("eeee").at(3, 0).insert();
          long shortNo = poi("f").at(3, 0.7).insert();
          // 4. 길이가 같으면 작은 쪽(먼저 넣어 id 도 번호도 작다)
          long first = poi("g").at(4, 0).insert();
          long second = poi("h").at(4, 0.7).insert();

          runDedupe();

          assertThat(isOpen(specific)).isTrue();
          assertThat(isOpen(etc)).isFalse();
          assertThat(isOpen(withTel)).isTrue();
          assertThat(isOpen(noTel)).isFalse();
          assertThat(isOpen(shortNo)).isTrue();
          assertThat(isOpen(longNo)).isFalse();
          assertThat(isOpen(first)).isTrue();
          assertThat(isOpen(second)).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("셋이 이어지면(A–B, B–C 만 1.5 m 안) 모두 가장 나은 하나로 — 리뷰·번호도 그쪽으로")
  void chainOfThreeCollapsesToBest() {
    IntegrationDatabase.rolledBack(
        () -> {
          long a = poi("aaa").at(5, 0).insert();
          long b = poi("bbb").at(5, 1.0).insert();
          long c = poi("ccc").tel("02-000-0003").at(5, 2.0).insert();
          long ra = review(a, user(), "2 days");

          runDedupe();

          assertThat(isOpen(c)).isTrue();
          assertThat(isOpen(a)).isFalse();
          assertThat(isOpen(b)).isFalse();
          assertThat(reviewPoi(ra)).isEqualTo(c);
          assertThat(recordedPoi(src("aaa"))).isEqualTo(c);
          assertThat(recordedPoi(src("bbb"))).isEqualTo(c);
          assertThat(fixturePoiCount()).isEqualTo(3L);
          return null;
        });
  }

  // ── 같은 가게 판정 ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("판정 — 전화·세부 종류가 둘 다 다르면 두고, 숫자만 다른 이름·2 m·다른 갈래·이미 닫힌 쪽은 합치지 않는다; 정규화로 같아지는 이름은 합친다")
  void sameStoreRules() {
    IntegrationDatabase.rolledBack(
        () -> {
          // 합치지 않는다
          long amb1 = poi("amb1").category("시험한식").tel("02-111-1111").at(10, 0).insert();
          long amb2 = poi("amb2").category("시험중식").tel("02-222-2222").at(10, 0.5).insert();
          long dg1 = poi("dg1").name("관악산５호점").at(11, 0).insert();
          long dg2 = poi("dg2").name("관악산２호점").at(11, 0.5).insert();
          long oc1 = poi("oc1").name("오션힐４１０호").at(12, 0).insert();
          long oc2 = poi("oc2").name("오션힐３０１호").at(12, 0.5).insert();
          long far1 = poi("far1").at(13, 0).insert();
          long far2 = poi("far2").at(13, 2.0).insert();
          long grp1 = poi("grp1").group("food").at(14, 0).insert();
          long grp2 = poi("grp2").group("sight").at(14, 0.5).insert();
          long open = poi("cl1").at(15, 0).insert();
          long closed = poi("cl2").at(15, 0.5).insert();
          jdbc.sql("UPDATE poi SET closed_at = TIMESTAMPTZ '2025-01-01 00:00+09' WHERE id = :id")
              .param("id", closed)
              .update();

          // 합친다
          long tel1 = poi("tel1").tel("02-333-3333").at(20, 0).insert();
          long tel2 = poi("tel2").tel("02-444-4444").at(20, 0.5).insert();
          long fw1 = poi("fw1").name("시험카페５").at(21, 0).insert();
          long fw2 = poi("fw2").name("시험카페5").at(21, 0.5).insert();
          long ro1 = poi("ro1").name("ＳＣＥＮＥ Ⅱ").at(22, 0).insert();
          long ro2 = poi("ro2").name("scene ii").at(22, 0.5).insert();
          long br1 = poi("br1").name("시험빵집 [면세점(TAX REFUND SHOP)]").at(23, 0).insert();
          long br2 = poi("br2").name("시험빵집").at(23, 0.5).insert();
          long pu1 = poi("pu1").name("시험-국밥·집").at(24, 0).insert();
          long pu2 = poi("pu2").name("시험 국밥집").at(24, 0.5).insert();

          runDedupe();

          assertThat(List.of(amb1, amb2, dg1, dg2, oc1, oc2, far1, far2, grp1, grp2, open))
              .as("합치지 않을 쌍은 모두 열려 있다")
              .allMatch(PoiDedupeIntegrationTest::isOpen);
          assertThat(
                  text(
                      "SELECT to_char(closed_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD') FROM poi"
                          + " WHERE id = :id",
                      closed))
              .as("이미 닫힌 쪽은 그대로")
              .isEqualTo("2025-01-01");
          assertThat(recordedPoi(src("cl2"))).isEqualTo(closed);

          for (long[] pair :
              new long[][] {{tel1, tel2}, {fw1, fw2}, {ro1, ro2}, {br1, br2}, {pu1, pu2}}) {
            assertThat(isOpen(pair[0]) ^ isOpen(pair[1]))
                .as("하나만 열려 있어야 한다: %d·%d", pair[0], pair[1])
                .isTrue();
          }
          return null;
        });
  }

  // ── 멱등 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("두 번째 실행은 아무것도 바꾸지 않는다 — 숨김·번호 기록·리뷰 자리가 그대로")
  void secondRunChangesNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long a = poi("x1").at(30, 0).insert();
          long b = poi("x2").tel("02-000-0004").at(30, 0.6).insert();
          poi("y1").at(31, 0).insert();
          poi("y2").at(31, 1.0).insert();
          poi("y3").at(31, 2.0).insert();
          poi("z1").category("시험한식").tel("02-555-5555").at(32, 0).insert();
          poi("z2").category("시험중식").tel("02-666-6666").at(32, 0.5).insert();
          UUID u = user();
          review(a, u, "1 day");
          review(b, u, "2 days");

          runDedupe();
          List<Map<String, Object>> first = snapshot();
          runDedupe();

          assertThat(snapshot()).isEqualTo(first);
          assertThat(fixturePoiCount()).isEqualTo(7L);
          return null;
        });
  }

  /** 픽스처의 지금 모습 — 편의시설 숨김, 번호 기록, 리뷰 자리. */
  private List<Map<String, Object>> snapshot() {
    String like = src("") + "%";
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.addAll(
        jdbc.sql("SELECT id, closed_at FROM poi WHERE source_id LIKE :p ORDER BY id")
            .param("p", like)
            .query()
            .listOfRows());
    rows.addAll(
        jdbc.sql(
                "SELECT source_id, poi_id, active FROM poi_source WHERE source_id LIKE :p ORDER BY"
                    + " source_id")
            .param("p", like)
            .query()
            .listOfRows());
    rows.addAll(
        jdbc.sql(
                """
                SELECT r.id, r.poi_id, r.updated_at FROM review r
                JOIN poi p ON p.id = r.poi_id
                WHERE p.source_id LIKE :p ORDER BY r.id
                """)
            .param("p", like)
            .query()
            .listOfRows());
    return rows;
  }
}
