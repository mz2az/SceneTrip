package com.mz2az.scenetrip.sceneapi.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 운영 화면(Metabase)이 읽는 {@code analytics} 스키마의 뷰(계획 {@code metabase-prd.md} §3-4, Flyway V40)를 실제
 * PostgreSQL 에서 본다 — 칸이 명세대로인지, 개인을 가리키는 칸이 없는지, 숫자가 맞게 모이는지(한국 시간 날짜).
 *
 * <p>공유 로컬 DB 다. 픽스처는 2001 년 3 월 — 실제 데이터가 없는 날 — 에 넣고, 시험마다 한 트랜잭션 안에서 넣고 읽은 뒤 통째로 되돌린다({@link
 * IntegrationDatabase#rolledBack}).
 */
@DisplayName("analytics 뷰 — 운영 화면용 집계 (실제 DB)")
class AnalyticsViewsIntegrationTest {

  /** 픽스처의 한국 날짜. */
  private static final String DAY = "2001-03-10";

  private static final String DAY_BEFORE = "2001-03-09";

  /** 한국 시간 2001-03-10 01:30 — UTC 로는 전날이지만 {@link #DAY} 에 센다. */
  private static final String ON_DAY = "2001-03-09T16:30:00Z";

  /** 한국 시간 2001-03-09 23:59 — {@link #DAY_BEFORE} 에 센다. */
  private static final String ON_DAY_BEFORE = "2001-03-09T14:59:00Z";

  /** 명세 §3-4 의 뷰와 칸. */
  private static final Map<String, List<String>> SPEC = new LinkedHashMap<>();

  static {
    SPEC.put(
        "contents",
        List.of("content_id", "title_ko", "title_en", "category", "popularity_score", "hidden"));
    SPEC.put("places", List.of("place_id", "name_ko", "name_en", "popularity_score", "hidden"));
    SPEC.put("daily_new_installs", List.of("day", "installs"));
    SPEC.put("daily_new_members", List.of("day", "provider", "members"));
    SPEC.put("daily_active_viewers", List.of("day", "viewers"));
    SPEC.put(
        "daily_entity_views",
        List.of("day", "entity_type", "entity_id", "name_ko", "name_en", "viewers"));
    SPEC.put("daily_actions", List.of("day", "action", "actions"));
    SPEC.put("daily_chat", List.of("day", "lang", "outcome", "turns", "avg_took_ms"));
  }

  /** 개인을 가리키는 칸 이름(§3-4: 사용자 ID·설치 ID·이메일·이름·소셜 ID·토큰·본문·IP). */
  private static final Set<String> PERSONAL =
      Set.of(
          "user_id",
          "install_id",
          "install_uuid",
          "device_id",
          "email",
          "display_name",
          "nickname",
          "subject",
          "token_hash",
          "body",
          "ip",
          "author_id");

  private static JdbcClient jdbc;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  private static void inTx(Runnable work) {
    IntegrationDatabase.rolledBack(
        () -> {
          work.run();
          return null;
        });
  }

  private static List<String> columns(String view) {
    return jdbc.sql(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'analytics' AND table_name = :v
            ORDER BY ordinal_position
            """)
        .param("v", view)
        .query(String.class)
        .list();
  }

  /** 줄마다 칸을 '|' 로 이은 문자열. NULL 은 'NULL'. */
  private static List<String> rows(String sql) {
    return jdbc.sql(sql).query(String.class).list();
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  private static UUID newUser() {
    return jdbc.sql("INSERT INTO app_user (id) VALUES (gen_random_uuid()) RETURNING id")
        .query(UUID.class)
        .single();
  }

  private static long content(String titleKo, String titleEn, boolean hidden) {
    long id =
        jdbc.sql(
                """
                INSERT INTO content (category, popularity_score, hidden_at)
                VALUES ('drama', 12.5, CASE WHEN :h THEN now() END) RETURNING id
                """)
            .param("h", hidden)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO content_i18n (content_id, lang, title) VALUES (:id, 'ko', :t)")
        .param("id", id)
        .param("t", titleKo)
        .update();
    if (titleEn != null) {
      jdbc.sql("INSERT INTO content_i18n (content_id, lang, title) VALUES (:id, 'en', :t)")
          .param("id", id)
          .param("t", titleEn)
          .update();
    }
    return id;
  }

  private static long place(String nameKo, String nameEn, boolean hidden) {
    long id =
        jdbc.sql(
                """
                INSERT INTO place (type, geom, popularity_score, hidden_at)
                VALUES ('시험촬영지', ST_SetSRID(ST_MakePoint(127, 37), 4326)::geography, 7,
                        CASE WHEN :h THEN now() END)
                RETURNING id
                """)
            .param("h", hidden)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:id, 'ko', :n)")
        .param("id", id)
        .param("n", nameKo)
        .update();
    if (nameEn != null) {
      jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:id, 'en', :n)")
          .param("id", id)
          .param("n", nameEn)
          .update();
    }
    return id;
  }

  private static long poi(String nameKo, String nameEn) {
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group, address)
                VALUES ('analytics-it-' || gen_random_uuid(), :n,
                        ST_SetSRID(ST_MakePoint(127, 37), 4326)::geography, '카페', 'food', '서울')
                RETURNING id
                """)
            .param("n", nameKo)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO poi_i18n (poi_id, lang, name) VALUES (:id, 'en', :n)")
        .param("id", id)
        .param("n", nameEn)
        .update();
    return id;
  }

  private static void install(String at) {
    jdbc.sql(
            "INSERT INTO user_device (install_uuid, user_id, created_at)"
                + " VALUES (gen_random_uuid(), :u, CAST(:at AS timestamptz))")
        .param("u", newUser())
        .param("at", at)
        .update();
  }

  private static void identity(String provider, String at) {
    jdbc.sql(
            "INSERT INTO user_identity (provider, subject, user_id, email, created_at)"
                + " VALUES (:p, 'analytics-it-' || gen_random_uuid(), :u, 'it@example.com',"
                + " CAST(:at AS timestamptz))")
        .param("p", provider)
        .param("u", newUser())
        .param("at", at)
        .update();
  }

  private static void view(UUID installId, String type, long id, String day) {
    jdbc.sql(
            "INSERT INTO entity_view (entity_type, entity_id, install_id, lang, day)"
                + " VALUES (:t, :id, :i, 'ko', CAST(:d AS date))")
        .param("t", type)
        .param("id", id)
        .param("i", installId)
        .param("d", day)
        .update();
  }

  private static void dailyStat(String type, long id, String lang, int viewers) {
    jdbc.sql(
            "INSERT INTO entity_daily_stats (entity_type, entity_id, day, lang, viewers)"
                + " VALUES (:t, :id, CAST(:d AS date), CAST(:l AS lang_code), :v)")
        .param("t", type)
        .param("id", id)
        .param("d", DAY)
        .param("l", lang)
        .param("v", viewers)
        .update();
  }

  private static long course(String at) {
    return jdbc.sql(
            "INSERT INTO course (user_id, title, day_count, origin, created_at, updated_at)"
                + " VALUES (:u, '집계 시험', 1, 'self', CAST(:at AS timestamptz),"
                + " CAST(:at AS timestamptz)) RETURNING id")
        .param("u", newUser())
        .param("at", at)
        .query(Long.class)
        .single();
  }

  private static void turn(String lang, String outcome, int tookMs, String at) {
    jdbc.sql(
            "INSERT INTO guide_turn (lang, outcome, took_ms, created_at)"
                + " VALUES (CAST(:l AS lang_code), :o, :ms, CAST(:at AS timestamptz))")
        .param("l", lang)
        .param("o", outcome)
        .param("ms", tookMs)
        .param("at", at)
        .update();
  }

  // ── 칸 ──────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("뷰 여덟 개가 명세의 칸을 정확히 가진다")
  void viewsHaveExactlyTheSpecifiedColumns() {
    List<String> views =
        rows(
            "SELECT table_name FROM information_schema.views WHERE table_schema = 'analytics'"
                + " ORDER BY 1");
    assertThat(views).containsAll(SPEC.keySet());
    SPEC.forEach(
        (view, expected) ->
            assertThat(columns(view)).as(view).containsExactlyInAnyOrderElementsOf(expected));
  }

  @Test
  @DisplayName("analytics 의 어느 표·뷰에도 개인을 가리키는 칸이 없다")
  void noPersonalColumnAnywhereInAnalytics() {
    List<String> all =
        rows(
            "SELECT table_name || '.' || column_name FROM information_schema.columns"
                + " WHERE table_schema = 'analytics' ORDER BY 1");
    // 빈 스키마에서는 아래 단언이 공허하게 통과한다.
    assertThat(all).isNotEmpty();
    assertThat(all)
        .allSatisfy(
            qualified -> {
              String column = qualified.substring(qualified.indexOf('.') + 1);
              assertThat(PERSONAL).as(qualified).doesNotContain(column);
              assertThat(column).as(qualified).doesNotContain("email").doesNotContain("token");
            });
  }

  // ── 목록 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("contents·places — ko·en 이름(없으면 NULL), 인기도, hidden = hidden_at 이 찼는가")
  void catalogListsWithHiddenFlag() {
    inTx(
        () -> {
          long shown = content("시험작품", "Test Work", false);
          long hidden = content("숨긴작품", null, true);
          long placeShown = place("시험촬영지", "Test Spot", false);
          long placeHidden = place("숨긴촬영지", null, true);

          assertThat(
                  rows(
                      "SELECT content_id || '|' || title_ko || '|' || coalesce(title_en, 'NULL') ||"
                          + " '|' || category || '|' || popularity_score::float8 || '|' || hidden"
                          + " FROM analytics.contents WHERE content_id IN ("
                          + shown
                          + ", "
                          + hidden
                          + ") ORDER BY content_id"))
              .containsExactly(
                  shown + "|시험작품|Test Work|drama|12.5|false",
                  hidden + "|숨긴작품|NULL|drama|12.5|true");
          assertThat(
                  rows(
                      "SELECT place_id || '|' || name_ko || '|' || coalesce(name_en, 'NULL')"
                          + " || '|' || popularity_score::float8 || '|' || hidden"
                          + " FROM analytics.places WHERE place_id IN ("
                          + placeShown
                          + ", "
                          + placeHidden
                          + ") ORDER BY place_id"))
              .containsExactly(
                  placeShown + "|시험촬영지|Test Spot|7|false", placeHidden + "|숨긴촬영지|NULL|7|true");
        });
  }

  // ── 하루 집계 ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("daily_new_installs — 한국 시간 날짜로 센다(UTC 16:30 은 다음 날)")
  void newInstallsByKoreanDay() {
    inTx(
        () -> {
          install(ON_DAY);
          install(ON_DAY);
          install(ON_DAY_BEFORE);

          assertThat(
                  rows(
                      "SELECT day || '|' || installs FROM analytics.daily_new_installs"
                          + " WHERE day IN (DATE '"
                          + DAY_BEFORE
                          + "', DATE '"
                          + DAY
                          + "') ORDER BY day"))
              .containsExactly(DAY_BEFORE + "|1", DAY + "|2");
        });
  }

  @Test
  @DisplayName("daily_new_members — 날짜·소셜(google·apple)별 새 로그인 계정 수")
  void newMembersByProvider() {
    inTx(
        () -> {
          identity("google", ON_DAY);
          identity("google", ON_DAY);
          identity("apple", ON_DAY);
          identity("apple", ON_DAY_BEFORE);

          assertThat(
                  rows(
                      "SELECT day || '|' || provider || '|' || members"
                          + " FROM analytics.daily_new_members WHERE day IN (DATE '"
                          + DAY_BEFORE
                          + "', DATE '"
                          + DAY
                          + "')"))
              .containsExactlyInAnyOrder(
                  DAY_BEFORE + "|apple|1", DAY + "|google|2", DAY + "|apple|1");
        });
  }

  @Test
  @DisplayName("daily_active_viewers — 그날 상세를 하나라도 본 서로 다른 설치 수")
  void activeViewersCountDistinctInstalls() {
    inTx(
        () -> {
          long work = content("시험작품", null, false);
          long spot = place("시험촬영지", null, false);
          UUID a = UUID.randomUUID();
          UUID b = UUID.randomUUID();
          UUID c = UUID.randomUUID();
          view(a, "content", work, DAY);
          view(a, "place", spot, DAY); // 같은 설치가 둘을 봐도 한 명
          view(b, "content", work, DAY);
          view(c, "content", work, DAY_BEFORE);

          assertThat(
                  rows(
                      "SELECT day || '|' || viewers FROM analytics.daily_active_viewers"
                          + " WHERE day IN (DATE '"
                          + DAY_BEFORE
                          + "', DATE '"
                          + DAY
                          + "') ORDER BY day"))
              .containsExactly(DAY_BEFORE + "|1", DAY + "|2");
        });
  }

  @Test
  @DisplayName("daily_entity_views — 언어를 합치고 작품·촬영지·편의시설의 ko·en 이름을 붙인다")
  void entityViewsSumLanguagesAndCarryNames() {
    inTx(
        () -> {
          long work = content("시험작품", "Test Work", false);
          long spot = place("시험촬영지", null, false);
          long shop = poi("시험카페", "Test Cafe");
          dailyStat("content", work, "ko", 3);
          dailyStat("content", work, "en", 2);
          dailyStat("place", spot, "ko", 4);
          dailyStat("place", spot, "ja", 1);
          dailyStat("poi", shop, "ko", 1);

          assertThat(
                  rows(
                      "SELECT day || '|' || entity_type || '|' || entity_id || '|' ||"
                          + " coalesce(name_ko, 'NULL') || '|' || coalesce(name_en, 'NULL') || '|'"
                          + " || viewers FROM analytics.daily_entity_views WHERE day = DATE '"
                          + DAY
                          + "'"))
              .containsExactlyInAnyOrder(
                  DAY + "|content|" + work + "|시험작품|Test Work|5",
                  DAY + "|place|" + spot + "|시험촬영지|NULL|5",
                  DAY + "|poi|" + shop + "|시험카페|Test Cafe|1");
        });
  }

  @Test
  @DisplayName("daily_actions — 행동별 수, 한국 날짜, 지운 행은 빠진다")
  void actionsPerKindOnRemainingRows() {
    inTx(
        () -> {
          long work = content("시험작품", null, false);
          long spot = place("시험촬영지", null, false);
          long spot2 = place("시험촬영지2", null, false);

          jdbc.sql(
                  "INSERT INTO saved_content (user_id, content_id, created_at)"
                      + " VALUES (:u, :c, CAST(:at AS timestamptz))")
              .param("u", newUser())
              .param("c", work)
              .param("at", ON_DAY)
              .update();
          jdbc.sql(
                  "INSERT INTO saved_content (user_id, content_id, created_at)"
                      + " VALUES (:u, :c, CAST(:at AS timestamptz))")
              .param("u", newUser())
              .param("c", work)
              .param("at", ON_DAY_BEFORE)
              .update();
          for (long p : List.of(spot, spot2)) {
            jdbc.sql(
                    "INSERT INTO saved_place (user_id, place_id, created_at)"
                        + " VALUES (:u, :p, CAST(:at AS timestamptz))")
                .param("u", newUser())
                .param("p", p)
                .param("at", ON_DAY)
                .update();
          }
          course(ON_DAY);
          long deleted = course(ON_DAY);
          jdbc.sql("DELETE FROM course WHERE id = :id").param("id", deleted).update();
          jdbc.sql(
                  "INSERT INTO review (place_id, user_id, rating, created_at, updated_at)"
                      + " VALUES (:p, :u, 5, CAST(:at AS timestamptz), CAST(:at AS timestamptz))")
              .param("p", spot)
              .param("u", newUser())
              .param("at", ON_DAY)
              .update();
          jdbc.sql(
                  "INSERT INTO community_post (user_id, title, body, created_at)"
                      + " VALUES (:u, '집계 시험', '본문', CAST(:at AS timestamptz))")
              .param("u", newUser())
              .param("at", ON_DAY)
              .update();

          List<String> actual =
              rows(
                  "SELECT day || '|' || action || '|' || actions FROM analytics.daily_actions"
                      + " WHERE day IN (DATE '"
                      + DAY_BEFORE
                      + "', DATE '"
                      + DAY
                      + "') AND action <> 'cart_add'");
          assertThat(actual)
              .containsExactlyInAnyOrder(
                  DAY_BEFORE + "|save_content|1",
                  DAY + "|save_content|1",
                  DAY + "|save_place|2",
                  DAY + "|course_create|1",
                  DAY + "|review_create|1",
                  DAY + "|post_create|1");
        });
  }

  @Test
  @DisplayName("daily_chat — 날짜·언어·결과별 턴 수와 평균 걸린 시간")
  void chatTurnsAndAverageTime() {
    inTx(
        () -> {
          turn("ko", "ok", 100, ON_DAY);
          turn("ko", "ok", 300, ON_DAY);
          turn("en", "GUIDE_UNAVAILABLE", 50, ON_DAY);
          turn("ko", "ok", 999, ON_DAY_BEFORE);

          assertThat(
                  rows(
                      "SELECT day || '|' || lang || '|' || outcome || '|' || turns || '|'"
                          + " || round(avg_took_ms::numeric) FROM analytics.daily_chat"
                          + " WHERE day IN (DATE '"
                          + DAY_BEFORE
                          + "', DATE '"
                          + DAY
                          + "')"))
              .containsExactlyInAnyOrder(
                  DAY_BEFORE + "|ko|ok|1|999",
                  DAY + "|ko|ok|2|200",
                  DAY + "|en|GUIDE_UNAVAILABLE|1|50");
        });
  }
}
