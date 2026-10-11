package com.mz2az.scenetrip.sceneapi.popularity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 매일 밤 인기도 다시 계산(계획 {@code popularity.md} §4 재료와 무게, §5 계산, §2 기간·보관·한 번만)을 진짜 PostgreSQL 에 돌린다.
 *
 * <p>계산은 종류마다 전체를 보고(백분위) 모든 행을 덮어쓰므로, 시험마다 <b>한 트랜잭션 안에서</b> 기존 활동을 지우고 픽스처만 남긴 뒤 계산하고, 끝나면 통째로
 * 되돌린다({@link IntegrationDatabase#rolledBack}). 공유하는 로컬 DB 에는 아무것도 남지 않는다.
 *
 * <p>무게 시험은 백분위 방식에 기대지 않도록 짠다 — 대상을 그 종류에서 활동이 가장 많은 것으로 두면(미끼 하나는 조회 1) 순위가 100 이므로, 최종값에서 활동 점수가
 * 그대로 드러난다: 최종 = w × 출발점 + (1 − w) × 100, w = K / (K + 활동 점수). 백분위 자체는 §5 의 예시 표로 따로 본다.
 */
@DisplayName("recompute_popularity — 매일 밤 인기도 계산 (실제 DB)")
class PopularityRecomputeIntegrationTest {

  /** 계획 §5: K 는 처음 200. */
  private static final double K = 200;

  /** 함수가 소수 셋째 자리로 반올림한다고 보고 그만큼 넉넉히. */
  private static final Offset<Double> CLOSE = within(0.002);

  private static JdbcClient jdbc;
  private static PopularityJob job;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static List<Long> contents;
  private static List<Long> places;
  private static List<Long> pois;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    IntegrationDatabase.requirePoiSeeded(jdbc);
    // 계획의 처음 값(K 200 · 30 일 · 90 일 · 무게 1/3/3/5/5/8). application.yaml 이 같은 값인지는
    // PopularitySettingsTest 가 본다.
    job = new PopularityJob(jdbc, new PopularitySettings());
    contents = jdbc.sql("SELECT id FROM content ORDER BY id LIMIT 4").query(Long.class).list();
    places = jdbc.sql("SELECT id FROM place ORDER BY id LIMIT 4").query(Long.class).list();
    pois = jdbc.sql("SELECT id FROM poi ORDER BY id LIMIT 2").query(Long.class).list();
    assertThat(contents).hasSize(4);
    assertThat(places).hasSize(4);
    assertThat(pois).hasSize(2);
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  private static void inTx(Runnable work) {
    IntegrationDatabase.rolledBack(
        () -> {
          work.run();
          return null;
        });
  }

  /** 기존 활동을 모두 지운다(트랜잭션 안 — 되돌린다). 백분위가 픽스처끼리만 매겨지게. */
  private static void clearActivity() {
    for (String table :
        List.of("entity_view", "saved_content", "saved_place", "course_item", "review")) {
      jdbc.sql("DELETE FROM " + table).update();
    }
  }

  /**
   * 계산 함수를 기본 인자(계획의 처음 값)로 부른다 — {@code just popularity-recompute} 와 적재 끝이 부르는 것과 같다. 서버의 매일 밤 작업
   * ({@link PopularityJob#recompute()}) 경로는 {@link #nightlyJobRuns()}·{@link #honoursSettings()} 가
   * 따로 본다.
   */
  private static JsonNode recompute() {
    return JSON.readTree(
        jdbc.sql("SELECT recompute_popularity()::text").query(String.class).single());
  }

  private static JsonNode recompute(PopularityJob with) {
    return JSON.readTree(with.recompute());
  }

  private static void setContentSeed(long id, double seed) {
    jdbc.sql("UPDATE content SET popularity_seed = :s WHERE id = :id")
        .param("s", seed)
        .param("id", id)
        .update();
  }

  private static double score(String table, long id) {
    return jdbc.sql("SELECT popularity_score::float8 FROM " + table + " WHERE id = :id")
        .param("id", id)
        .query(Double.class)
        .single();
  }

  private static double seed(String table, long id) {
    return jdbc.sql("SELECT popularity_seed::float8 FROM " + table + " WHERE id = :id")
        .param("id", id)
        .query(Double.class)
        .single();
  }

  /** 서로 다른 기기 n 대가 daysAgo 일 전(한국 날짜)에 상세를 연 것. */
  private static void views(String type, long id, int n, int daysAgo) {
    views(type, id, n, daysAgo, "ko");
  }

  private static void views(String type, long id, int n, int daysAgo, String lang) {
    jdbc.sql(
            """
            INSERT INTO entity_view (entity_type, entity_id, install_id, lang, day)
            SELECT :t, :id, gen_random_uuid(), CAST(:lang AS lang_code),
                   (now() AT TIME ZONE 'Asia/Seoul')::date - :ago
            FROM generate_series(1, :n)
            """)
        .param("t", type)
        .param("id", id)
        .param("lang", lang)
        .param("ago", daysAgo)
        .param("n", n)
        .update();
  }

  private static UUID newUser() {
    return jdbc.sql("INSERT INTO app_user (id) VALUES (gen_random_uuid()) RETURNING id")
        .query(UUID.class)
        .single();
  }

  private static void heart(long content, int daysAgo) {
    jdbc.sql(
            "INSERT INTO saved_content (user_id, content_id, created_at)"
                + " VALUES (:u, :c, now() - make_interval(days => :ago))")
        .param("u", newUser())
        .param("c", content)
        .param("ago", daysAgo)
        .update();
  }

  private static void cart(long place, Long sourceContent, int daysAgo) {
    jdbc.sql(
            "INSERT INTO saved_place (user_id, place_id, source_content_id, created_at)"
                + " VALUES (:u, :p, :sc, now() - make_interval(days => :ago))")
        .param("u", newUser())
        .param("p", place)
        .param("sc", sourceContent)
        .param("ago", daysAgo)
        .update();
  }

  /** 코스 하나. updatedDaysAgo 일 전에 마지막으로 저장했다. */
  private static long course(int updatedDaysAgo) {
    return jdbc.sql(
            """
            INSERT INTO course (user_id, title, day_count, origin, created_at, updated_at)
            VALUES (:u, '인기도 시험', 1, 'self',
                    now() - make_interval(days => :ago), now() - make_interval(days => :ago))
            RETURNING id
            """)
        .param("u", newUser())
        .param("ago", updatedDaysAgo)
        .query(Long.class)
        .single();
  }

  private static void item(
      long course, Long place, Long poi, Long sourceContent, Integer visitedDaysAgo) {
    jdbc.sql(
            """
            INSERT INTO course_item (course_id, day_no, place_id, poi_id, sort_order, source_content_id, visited_at)
            VALUES (:c, 1, :p, :poi,
                    (SELECT COALESCE(max(sort_order), 0) + 1 FROM course_item WHERE course_id = :c),
                    :sc,
                    CASE WHEN CAST(:visited AS INT) IS NULL THEN NULL
                         ELSE now() - make_interval(days => CAST(:visited AS INT)) END)
            """)
        .param("c", course)
        .param("p", place)
        .param("poi", poi)
        .param("sc", sourceContent)
        .param("visited", visitedDaysAgo)
        .update();
  }

  private static void review(Long place, Long poi, int daysAgo, boolean removed) {
    jdbc.sql(
            """
            INSERT INTO review (place_id, poi_id, user_id, rating, created_at, updated_at, removed_at)
            VALUES (:p, :poi, :u, 5, now() - make_interval(days => :ago), now() - make_interval(days => :ago),
                    CASE WHEN :removed THEN now() ELSE NULL END)
            """)
        .param("p", place)
        .param("poi", poi)
        .param("u", newUser())
        .param("ago", daysAgo)
        .param("removed", removed)
        .update();
  }

  /** 그 종류에서 활동이 가장 많은 것(순위 100)의 최종값. 계획 §5 의 3·4 단계. */
  private static double topFinal(double seed, double points, double k) {
    double w = k / (k + points);
    return w * seed + (1 - w) * 100;
  }

  private static double topFinal(double seed, double points) {
    return topFinal(seed, points, K);
  }

  // ── 활동이 없을 때 ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("활동이 없으면 초기값 그대로다")
  class SeedOnly {

    @Test
    @DisplayName("활동이 하나도 없으면 작품·촬영지의 최종값은 초기값, 편의시설은 모두 0")
    void everythingIsSeed() {
      inTx(
          () -> {
            clearActivity();
            setContentSeed(contents.get(0), 73);

            JsonNode result = recompute();

            assertThat(result.get("skipped").asBoolean()).isFalse();
            assertThat(score("content", contents.get(0))).isCloseTo(73, CLOSE);
            long contentOff =
                jdbc.sql(
                        "SELECT count(*) FROM content WHERE abs(popularity_score - popularity_seed)"
                            + " > 0.001")
                    .query(Long.class)
                    .single();
            long placeOff =
                jdbc.sql(
                        "SELECT count(*) FROM place WHERE abs(popularity_score - popularity_seed) >"
                            + " 0.001")
                    .query(Long.class)
                    .single();
            long poiNonZero =
                jdbc.sql("SELECT count(*) FROM poi WHERE popularity_score <> 0")
                    .query(Long.class)
                    .single();
            assertThat(contentOff).as("초기값과 다른 작품 수").isZero();
            assertThat(placeOff).as("초기값과 다른 촬영지 수").isZero();
            assertThat(poiNonZero).as("0 이 아닌 편의시설 수").isZero();
          });
    }

    @Test
    @DisplayName("다른 편의시설에 활동이 있어도, 활동 없는 편의시설은 0 에 머문다")
    void poiWithoutActivityStaysZero() {
      inTx(
          () -> {
            clearActivity();
            views("poi", pois.get(0), 5, 0);

            recompute();

            assertThat(score("poi", pois.get(0))).isGreaterThan(0);
            assertThat(score("poi", pois.get(1))).isEqualTo(0.0);
          });
    }
  }

  // ── §5 예시 표 ────────────────────────────────────────────────────────────────

  /** §5 예시의 세 작품: 도깨비 90/40 · 신작 B 40/900 · 작품 C 60/10 (초기값/30 일 활동, 조회만으로 만든다). */
  private static void workedExampleFixture() {
    setContentSeed(contents.get(0), 90);
    setContentSeed(contents.get(1), 40);
    setContentSeed(contents.get(2), 60);
    views("content", contents.get(0), 40, 0);
    views("content", contents.get(1), 900, 0);
    views("content", contents.get(2), 10, 0);
  }

  /** 계획 §5 4 단계: w × 초기값 + (1 − w) × 순위, w = K / (K + 활동 점수). */
  private static double blend(double seed, double points, double rank) {
    double w = K / (K + points);
    return w * seed + (1 - w) * rank;
  }

  @Test
  @DisplayName("§5 예시 표(작품이 이 셋뿐일 때): 도깨비 → 86.1 · 신작 B → 89.1 · 작품 C → 58.7 (순위 2/3 · 3/3 · 1/3)")
  void planWorkedExampleThreeWorksOnly() {
    inTx(
        () -> {
          clearActivity();
          // 「작품이 이 셋뿐일 때」 — 나머지 작품을 트랜잭션 안에서 지운다(되돌린다).
          jdbc.sql("DELETE FROM content WHERE id NOT IN (:a, :b, :c)")
              .param("a", contents.get(0))
              .param("b", contents.get(1))
              .param("c", contents.get(2))
              .update();
          workedExampleFixture();

          recompute();

          assertThat(score("content", contents.get(1)))
              .as("신작 B")
              .isCloseTo(blend(40, 900, 100), CLOSE); // 89.091
          assertThat(score("content", contents.get(0)))
              .as("도깨비")
              .isCloseTo(blend(90, 40, 200.0 / 3), CLOSE); // 86.111
          assertThat(score("content", contents.get(2)))
              .as("작품 C")
              .isCloseTo(blend(60, 10, 100.0 / 3), CLOSE); // 58.730
        });
  }

  @Test
  @DisplayName("§5 전체 작품 중 셋만 활동: 순위는 (전체 − 2)/전체 · (전체 − 1)/전체 · 1 — 128 편이면 98.4 · 99.2 · 100")
  void planWorkedExampleAmongAllWorks() {
    inTx(
        () -> {
          clearActivity();
          workedExampleFixture();
          double n = jdbc.sql("SELECT count(*) FROM content").query(Long.class).single();

          recompute();

          double rankC = 100 * (n - 2) / n;
          double rankDokkaebi = 100 * (n - 1) / n;
          if (n == 128) {
            assertThat(rankC).isCloseTo(98.4, within(0.05));
            assertThat(rankDokkaebi).isCloseTo(99.2, within(0.05));
          }
          assertThat(score("content", contents.get(1)))
              .as("신작 B")
              .isCloseTo(blend(40, 900, 100), CLOSE);
          assertThat(score("content", contents.get(0)))
              .as("도깨비")
              .isCloseTo(blend(90, 40, rankDokkaebi), CLOSE);
          assertThat(score("content", contents.get(2)))
              .as("작품 C")
              .isCloseTo(blend(60, 10, rankC), CLOSE);
        });
  }

  @Test
  @DisplayName("§5 2 단계: 조회 한 번 받은 것도 활동 없는 것들보다 위 순위다 — 출발점보다 내려가지 않는다")
  void leastActiveIsRankedAboveInactive() {
    inTx(
        () -> {
          clearActivity();
          long target = contents.get(0);
          setContentSeed(target, 50);
          views("content", contents.get(1), 10, 0);
          views("content", target, 1, 0);
          double n = jdbc.sql("SELECT count(*) FROM content").query(Long.class).single();

          recompute();

          // 전체 n 편 중 이 점수(1) 이하: 활동 없는 n − 2 편 + 자기 자신
          assertThat(score("content", target)).isCloseTo(blend(50, 1, 100 * (n - 1) / n), CLOSE);
          assertThat(score("content", target)).as("출발점보다 내려가지 않는다").isGreaterThan(50);
        });
  }

  @Test
  @DisplayName("§5 예시 표(작품이 이 셋뿐일 때)의 순서: 신작 B > 도깨비 > 작품 C — 활동이 많은 신작이 초기값 높은 작품을 넘는다")
  void planWorkedExampleOrder() {
    inTx(
        () -> {
          clearActivity();
          jdbc.sql("DELETE FROM content WHERE id NOT IN (:a, :b, :c)")
              .param("a", contents.get(0))
              .param("b", contents.get(1))
              .param("c", contents.get(2))
              .update();
          workedExampleFixture();

          recompute();

          assertThat(score("content", contents.get(1)))
              .isGreaterThan(score("content", contents.get(0)));
          assertThat(score("content", contents.get(0)))
              .isGreaterThan(score("content", contents.get(2)));
        });
  }

  // ── §4 재료와 무게 ────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("작품의 재료 — 조회 1 · 하트 3 · 담기(source_content_id) 3 · 코스(source_content_id) 5")
  class ContentIngredients {

    private final long target = contents.get(0);
    private final long decoy = contents.get(1);

    private void assertTop(double points) {
      recompute();
      assertThat(score("content", target)).isCloseTo(topFinal(50, points), CLOSE);
      assertThat(score("content", target)).as("활동이 있으면 초기값보다 오른다").isGreaterThan(50);
    }

    private void prepare() {
      clearActivity();
      setContentSeed(target, 50);
      setContentSeed(decoy, 50);
      views("content", decoy, 1, 0);
    }

    @Test
    @DisplayName("조회 10 번 → 10 점")
    void views10() {
      inTx(
          () -> {
            prepare();
            views("content", target, 10, 0);
            assertTop(10);
          });
    }

    @Test
    @DisplayName("하트 4 개 → 12 점")
    void hearts() {
      inTx(
          () -> {
            prepare();
            for (int i = 0; i < 4; i++) {
              heart(target, 1);
            }
            assertTop(12);
          });
    }

    @Test
    @DisplayName("이 작품 때문에 담은 장소 2 개 → 6 점")
    void cartFromThisWork() {
      inTx(
          () -> {
            prepare();
            cart(places.get(0), target, 1);
            cart(places.get(1), target, 1);
            assertTop(6);
          });
    }

    @Test
    @DisplayName("이 작품에서 넣은 코스 항목 — 한 코스에 둘이어도 코스 하나 5 점, 코스 둘이면 10 점")
    void courseFromThisWork() {
      inTx(
          () -> {
            prepare();
            long first = course(1);
            item(first, places.get(0), null, target, null);
            item(first, places.get(1), null, target, null);
            long second = course(1);
            item(second, places.get(2), null, target, null);
            assertTop(10);
          });
    }

    @Test
    @DisplayName("섞으면 더한다 — 조회 10 + 하트 2 + 담기 1 + 코스 1 = 10 + 6 + 3 + 5 = 24 점")
    void combined() {
      inTx(
          () -> {
            prepare();
            views("content", target, 10, 0);
            heart(target, 2);
            heart(target, 3);
            cart(places.get(0), target, 1);
            long c = course(1);
            item(c, places.get(1), null, target, null);
            assertTop(24);
          });
    }
  }

  @Nested
  @DisplayName("촬영지의 재료 — 조회 1 · 담기 3 · 코스 5 · 방문 8 · 리뷰 5")
  class PlaceIngredients {

    private final long target = places.get(0);
    private final long decoy = places.get(1);

    private void prepare() {
      clearActivity();
      views("place", decoy, 1, 0);
    }

    private void assertTop(double points) {
      recompute();
      double seed = seed("place", target);
      assertThat(score("place", target)).isCloseTo(topFinal(seed, points), CLOSE);
    }

    @Test
    @DisplayName("조회 7 번 → 7 점")
    void views7() {
      inTx(
          () -> {
            prepare();
            views("place", target, 7, 0);
            assertTop(7);
          });
    }

    @Test
    @DisplayName("담기 2 번 → 6 점 (작품과 상관없이)")
    void carts() {
      inTx(
          () -> {
            prepare();
            cart(target, null, 1);
            cart(target, contents.get(0), 1);
            assertTop(6);
          });
    }

    @Test
    @DisplayName("코스 — 한 코스가 같은 곳을 두 번 담아도 한 번: 코스 둘 → 10 점")
    void courses() {
      inTx(
          () -> {
            prepare();
            long first = course(2);
            item(first, target, null, null, null);
            item(first, target, null, null, null);
            long second = course(2);
            item(second, target, null, null, null);
            assertTop(10);
          });
    }

    @Test
    @DisplayName("실제 방문(도장) 한 번 → 8 점 — 코스는 기간 밖이라 방문만 센다")
    void visit() {
      inTx(
          () -> {
            prepare();
            long old = course(45);
            item(old, target, null, null, 2);
            assertTop(8);
          });
    }

    @Test
    @DisplayName("리뷰 2 개 → 10 점, 내려진 리뷰는 빼고")
    void reviews() {
      inTx(
          () -> {
            prepare();
            review(target, null, 1, false);
            review(target, null, 1, false);
            review(target, null, 1, true);
            assertTop(10);
          });
    }
  }

  @Nested
  @DisplayName("편의시설의 재료 — 조회 1 · 코스 5 · 방문 8 · 리뷰 5, 초기값 0")
  class PoiIngredients {

    private final long target = pois.get(0);
    private final long decoy = pois.get(1);

    @Test
    @DisplayName("조회 1 + 기간 안 코스에서 방문(5 + 8) + 리뷰 1 = 19 점 → (1 − w) × 100")
    void combined() {
      inTx(
          () -> {
            clearActivity();
            views("poi", decoy, 1, 0);
            views("poi", target, 1, 0);
            long c = course(1);
            item(c, null, target, null, 1);
            review(null, target, 1, false);
            review(null, target, 1, true);

            recompute();

            assertThat(score("poi", target)).isCloseTo(topFinal(0, 19), CLOSE);
          });
    }

    @Test
    @DisplayName("코스에만 두 번(한 코스) → 5 점")
    void courseOnce() {
      inTx(
          () -> {
            clearActivity();
            views("poi", decoy, 1, 0);
            long c = course(1);
            item(c, null, target, null, null);
            item(c, null, target, null, null);

            recompute();

            assertThat(score("poi", target)).isCloseTo(topFinal(0, 5), CLOSE);
          });
    }
  }

  // ── §2 기간 ──────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("최근 30 일 활동만 센다")
  class Window {

    @Test
    @DisplayName("31 일 전의 조회·하트·담기·코스·방문·리뷰는 세지 않는다 — 초기값 그대로")
    void oldActivityIgnored() {
      inTx(
          () -> {
            clearActivity();
            long content = contents.get(0);
            long place = places.get(0);
            long poi = pois.get(0);
            setContentSeed(content, 50);
            views("content", content, 20, 31);
            heart(content, 31);
            cart(place, content, 31);
            long old = course(31);
            item(old, place, null, content, 31);
            item(old, null, poi, null, 31);
            review(place, null, 31, false);
            review(null, poi, 31, false);
            views("place", place, 20, 31);
            views("poi", poi, 20, 31);

            recompute();

            assertThat(score("content", content)).isCloseTo(50, CLOSE);
            assertThat(score("place", place)).isCloseTo(seed("place", place), CLOSE);
            assertThat(score("poi", poi)).isEqualTo(0.0);
          });
    }

    @Test
    @DisplayName("29 일 전의 조회와 하트는 센다")
    void recentActivityCounts() {
      inTx(
          () -> {
            clearActivity();
            long target = contents.get(0);
            setContentSeed(target, 50);
            views("content", contents.get(1), 1, 0);
            views("content", target, 6, 29);
            heart(target, 29);

            recompute();

            assertThat(score("content", target)).isCloseTo(topFinal(50, 9), CLOSE);
          });
    }
  }

  // ── 활동이 사라지면 ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("활동이 사라지면 작품·촬영지는 초기값으로, 편의시설은 0 으로 돌아간다")
  void fallsBackWhenActivityDisappears() {
    inTx(
        () -> {
          clearActivity();
          long content = contents.get(0);
          long place = places.get(0);
          long poi = pois.get(0);
          setContentSeed(content, 50);
          views("content", content, 10, 0);
          views("content", contents.get(1), 1, 0);
          cart(place, null, 1);
          views("place", places.get(1), 1, 0);
          views("poi", poi, 3, 0);
          views("poi", pois.get(1), 1, 0);

          recompute();
          assertThat(score("content", content)).isGreaterThan(50);
          assertThat(score("place", place)).isNotCloseTo(seed("place", place), CLOSE);
          assertThat(score("poi", poi)).isGreaterThan(0);

          clearActivity();
          recompute();

          assertThat(score("content", content)).isCloseTo(50, CLOSE);
          assertThat(score("place", place)).isCloseTo(seed("place", place), CLOSE);
          assertThat(score("poi", poi)).isEqualTo(0.0);
          assertThat(score("poi", pois.get(1))).isEqualTo(0.0);
        });
  }

  // ── 촬영지 초기값 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("촬영지 초기값 = max(작품 초기값 × 산정 점수 ÷ 그 작품의 최고 산정 점수)")
  void placeSeedFromWorkSeedAndRelativeSceneScore() {
    inTx(
        () -> {
          clearActivity();
          long workA = contents.get(0);
          long workB = contents.get(1);
          long p1 = places.get(0);
          long p2 = places.get(1);
          long p3 = places.get(2);
          setContentSeed(workA, 80);
          setContentSeed(workB, 30);
          // 두 작품의 다른 촬영지는 낮게 — 최고 산정 점수가 아래 픽스처에서 나오게
          jdbc.sql("UPDATE place_content SET popularity_score = 1 WHERE content_id IN (:a, :b)")
              .param("a", workA)
              .param("b", workB)
              .update();
          jdbc.sql("DELETE FROM place_content WHERE place_id IN (:p1, :p2, :p3)")
              .param("p1", p1)
              .param("p2", p2)
              .param("p3", p3)
              .update();
          link(p1, workA, 20); // A 의 최고 → 상대값 1
          link(p2, workA, 10); // A 안 0.5
          link(p2, workB, 6); // B 의 최고 → 상대값 1
          link(p3, workB, 3); // B 안 0.5

          recompute();

          assertThat(seed("place", p1)).as("80 × 1").isCloseTo(80, CLOSE);
          assertThat(seed("place", p2)).as("max(80 × 0.5, 30 × 1)").isCloseTo(40, CLOSE);
          assertThat(seed("place", p3)).as("30 × 0.5").isCloseTo(15, CLOSE);
          // 활동이 없으니 최종값도 그 초기값이다
          assertThat(score("place", p1)).isCloseTo(80, CLOSE);
          assertThat(score("place", p2)).isCloseTo(40, CLOSE);
          assertThat(score("place", p3)).isCloseTo(15, CLOSE);
        });
  }

  @Test
  @DisplayName("작품 초기값이 모두 같으면 촬영지 초기값 순서는 작품 안 산정 점수 순서와 같다")
  void equalWorkSeedsFollowInWorkRank() {
    inTx(
        () -> {
          clearActivity();
          long work = contents.get(0);
          long p1 = places.get(0);
          long p2 = places.get(1);
          long p3 = places.get(2);
          setContentSeed(work, 50);
          jdbc.sql("UPDATE place_content SET popularity_score = 1 WHERE content_id = :w")
              .param("w", work)
              .update();
          jdbc.sql("DELETE FROM place_content WHERE place_id IN (:p1, :p2, :p3)")
              .param("p1", p1)
              .param("p2", p2)
              .param("p3", p3)
              .update();
          link(p1, work, 66);
          link(p2, work, 40);
          link(p3, work, 2.2);

          recompute();

          assertThat(seed("place", p1)).isCloseTo(50, CLOSE);
          assertThat(seed("place", p1)).isGreaterThan(seed("place", p2));
          assertThat(seed("place", p2)).isGreaterThan(seed("place", p3));
        });
  }

  private static void link(long place, long content, double sceneScore) {
    jdbc.sql(
            "INSERT INTO place_content (place_id, content_id, popularity_score) VALUES (:p, :c,"
                + " :s)")
        .param("p", place)
        .param("c", content)
        .param("s", sceneScore)
        .update();
  }

  // ── 하루 집계와 보관 ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("하루 집계: 그날 그 언어로 본 기기 수")
  void dailyStatsCountDevicesPerLanguage() {
    inTx(
        () -> {
          clearActivity();
          long content = contents.get(0);
          views("content", content, 1, 0, "ko");
          views("content", content, 2, 0, "en");

          recompute();

          var rows =
              jdbc.sql(
                      """
                      SELECT lang::text AS lang, viewers FROM entity_daily_stats
                      WHERE entity_type = 'content' AND entity_id = :id
                        AND day = (now() AT TIME ZONE 'Asia/Seoul')::date
                      ORDER BY lang::text
                      """)
                  .param("id", content)
                  .query((rs, n) -> rs.getString("lang") + "=" + rs.getInt("viewers"))
                  .list();
          assertThat(rows).containsExactly("en=2", "ko=1");
        });
  }

  @Test
  @DisplayName("원본(조회·검색)은 90 일 뒤 지운다 — 그 안의 것과 하루 집계는 남는다")
  void purgesRawAfterRetention() {
    inTx(
        () -> {
          clearActivity();
          long content = contents.get(0);
          views("content", content, 1, 100);
          views("content", content, 1, 80);
          jdbc.sql(
                  """
                  INSERT INTO search_log (query, result_count, lang, created_at) VALUES
                      ('보관 시험 오래됨', 1, 'ko', now() - interval '100 days'),
                      ('보관 시험 최근', 1, 'ko', now() - interval '80 days')
                  """)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO entity_daily_stats (entity_type, entity_id, day, lang, viewers)
                  VALUES ('content', :id, (now() AT TIME ZONE 'Asia/Seoul')::date - 100, 'ko', 1)
                  ON CONFLICT DO NOTHING
                  """)
              .param("id", content)
              .update();

          recompute();

          long oldViews =
              jdbc.sql(
                      "SELECT count(*) FROM entity_view"
                          + " WHERE day = (now() AT TIME ZONE 'Asia/Seoul')::date - 100")
                  .query(Long.class)
                  .single();
          long keptViews =
              jdbc.sql(
                      "SELECT count(*) FROM entity_view"
                          + " WHERE day = (now() AT TIME ZONE 'Asia/Seoul')::date - 80")
                  .query(Long.class)
                  .single();
          long oldSearches =
              jdbc.sql("SELECT count(*) FROM search_log WHERE query = '보관 시험 오래됨'")
                  .query(Long.class)
                  .single();
          long keptSearches =
              jdbc.sql("SELECT count(*) FROM search_log WHERE query = '보관 시험 최근'")
                  .query(Long.class)
                  .single();
          long oldStats =
              jdbc.sql(
                      """
                      SELECT count(*) FROM entity_daily_stats
                      WHERE entity_type = 'content' AND entity_id = :id
                        AND day = (now() AT TIME ZONE 'Asia/Seoul')::date - 100
                      """)
                  .param("id", content)
                  .query(Long.class)
                  .single();
          assertThat(oldViews).as("100 일 전 조회").isZero();
          assertThat(keptViews).as("80 일 전 조회").isEqualTo(1);
          assertThat(oldSearches).as("100 일 전 검색").isZero();
          assertThat(keptSearches).as("80 일 전 검색").isEqualTo(1);
          assertThat(oldStats).as("100 일 전 하루 집계는 계속 남는다").isEqualTo(1);
        });
  }

  // ── 설정 ──────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("K·기간·무게는 설정을 따른다 — scenetrip.popularity.*")
  void honoursSettings() {
    inTx(
        () -> {
          clearActivity();
          long target = contents.get(0);
          setContentSeed(target, 50);
          views("content", contents.get(1), 1, 0);
          views("content", target, 10, 0);
          views("content", target, 50, 10); // 기간 7 일 밖

          PopularitySettings settings = new PopularitySettings();
          settings.setK(50);
          settings.setWindowDays(7);
          settings.setWeightView(2);
          recompute(new PopularityJob(jdbc, settings));

          assertThat(score("content", target)).isCloseTo(topFinal(50, 20, 50), CLOSE);
        });
  }

  // ── 서버의 매일 밤 작업 ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("서버의 매일 밤 작업(PopularityJob.recompute, 설정값을 넘긴다)이 계산을 끝낸다")
  void nightlyJobRuns() {
    inTx(
        () -> {
          clearActivity();
          long target = contents.get(0);
          setContentSeed(target, 50);
          views("content", contents.get(1), 1, 0);
          views("content", target, 10, 0);

          JsonNode result = recompute(job);

          assertThat(result.get("skipped").asBoolean()).isFalse();
          assertThat(score("content", target)).isCloseTo(topFinal(50, 10), CLOSE);
        });
  }

  // ── 한 번만 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("이미 누가 계산하는 중이면 건너뛴다 — 두 번 돌지 않는다")
  void concurrentRunIsSkipped() throws Exception {
    try (Connection other =
        DriverManager.getConnection(
            System.getenv("SCENETRIP_TEST_JDBC_URL"),
            System.getenv("SCENETRIP_TEST_DB_USER"),
            System.getenv("SCENETRIP_TEST_DB_PASSWORD"))) {
      other.setAutoCommit(false);
      // 다른 파드가 지금 계산 중인 것처럼 — 그 트랜잭션이 잠금을 쥔다.
      try (Statement s = other.createStatement();
          ResultSet rs =
              s.executeQuery(
                  "SELECT pg_try_advisory_xact_lock(hashtext('recompute_popularity'))")) {
        rs.next();
        assertThat(rs.getBoolean(1)).as("시험이 잠금을 먼저 잡았다").isTrue();
      }

      inTx(
          () -> {
            clearActivity();
            long target = contents.get(0);
            setContentSeed(target, 50);
            double before = score("content", target);
            views("content", target, 30, 0);
            views("content", contents.get(1), 1, 0);

            JsonNode skipped = recompute();

            assertThat(skipped.get("skipped").asBoolean()).isTrue();
            assertThat(score("content", target)).as("건너뛰었으니 그대로").isEqualTo(before);
          });

      other.rollback(); // 잠금을 놓는다

      inTx(
          () -> {
            clearActivity();
            assertThat(recompute().get("skipped").asBoolean()).as("잠금이 풀리면 돈다").isFalse();
          });
    }
  }
}
