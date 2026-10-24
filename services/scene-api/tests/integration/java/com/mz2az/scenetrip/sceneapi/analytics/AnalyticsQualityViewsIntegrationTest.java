package com.mz2az.scenetrip.sceneapi.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 봇을 거른 설치·퍼널·재방문·검색어 뷰(계획 {@code metabase-prd.md} §3-6, Flyway V41)를 실제 PostgreSQL 에서 본다 — 칸이
 * 명세대로인지, 개인을 가리키는 칸이 없는지, 숫자가 명세의 뜻대로 모이는지(한국 시간 날짜).
 *
 * <p>설치의 「사람」 은 {@code user_device.user_id}, 설치 자신의 조회는 {@code entity_view.install_id} 로 잇는다(명세).
 *
 * <p>공유 로컬 DB 다. 픽스처는 2001 년 — 실제 데이터가 없는 날 — 에 넣고, 시험마다 한 트랜잭션 안에서 넣고 읽은 뒤 통째로 되돌린다({@link
 * IntegrationDatabase#rolledBack}).
 */
@DisplayName("analytics 뷰 — 봇 거른 설치·퍼널·재방문·검색어 (실제 DB)")
class AnalyticsQualityViewsIntegrationTest {

  /** 픽스처의 한국 날짜. */
  private static final String DAY = "2001-03-10";

  private static final String DAY_BEFORE = "2001-03-09";

  /** 한국 시간 2001-03-10 01:30 — UTC 로는 전날이지만 {@link #DAY} 에 센다. */
  private static final String ON_DAY = "2001-03-09T16:30:00Z";

  /** 한국 시간 2001-03-09 23:59 — {@link #DAY_BEFORE} 에 센다. */
  private static final String ON_DAY_BEFORE = "2001-03-09T14:59:00Z";

  /** 명세 §3-6 의 뷰와 칸. */
  private static final Map<String, List<String>> SPEC = new LinkedHashMap<>();

  static {
    SPEC.put(
        "daily_install_quality",
        List.of("day", "installs", "active_installs", "returning_installs"));
    SPEC.put(
        "funnel_by_install_day",
        List.of("day", "installs", "viewed", "signed_in", "saved", "course_created", "posted"));
    SPEC.put(
        "retention_by_install_day",
        List.of("day", "installs", "returned_d1", "returned_d7", "returned_d30"));
    SPEC.put("daily_search", List.of("day", "searches", "zero_result_searches"));
    SPEC.put("search_terms", List.of("day", "query", "searches", "zero_result_searches"));
  }

  /** 개인을 가리키는 칸 이름(§3-4 의 원칙: 사용자 ID·설치 ID·이메일·이름·소셜 ID·토큰·본문·제목(글)·IP). */
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
          "title",
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

  /** 줄마다 칸을 '|' 로 이은 문자열. */
  private static List<String> rows(String sql) {
    return jdbc.sql(sql).query(String.class).list();
  }

  /** {@code day} 가 주어진 날짜들 중 하나인 조건. */
  private static String dayIn(String... days) {
    StringBuilder in = new StringBuilder(" WHERE day IN (");
    for (int i = 0; i < days.length; i++) {
      if (i > 0) {
        in.append(", ");
      }
      in.append("DATE '").append(days[i]).append('\'');
    }
    return in.append(')').toString();
  }

  /** 한국 날짜 {@code day} 의 01:30(UTC 로는 전날 16:30). UTC 날짜로 세면 전날로 잘못 간다. */
  private static String earlyMorningKst(String day) {
    return LocalDate.parse(day).minusDays(1) + "T16:30:00Z";
  }

  private static String plusDays(String day, int days) {
    return LocalDate.parse(day).plusDays(days).toString();
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  /** 설치 한 개 — 설치 값과 그 설치의 사람. */
  private record Install(UUID installId, UUID userId) {}

  private static UUID newUser() {
    return jdbc.sql("INSERT INTO app_user (id) VALUES (gen_random_uuid()) RETURNING id")
        .query(UUID.class)
        .single();
  }

  /** 새 사람의 새 설치. */
  private static Install install(String at) {
    return installFor(newUser(), at);
  }

  /** 이미 있는 사람의 설치 하나 더(폰과 태블릿처럼). */
  private static Install installFor(UUID userId, String at) {
    UUID installId =
        jdbc.sql(
                "INSERT INTO user_device (install_uuid, user_id, created_at)"
                    + " VALUES (gen_random_uuid(), :u, CAST(:at AS timestamptz))"
                    + " RETURNING install_uuid")
            .param("u", userId)
            .param("at", at)
            .query(UUID.class)
            .single();
    return new Install(installId, userId);
  }

  private static long content() {
    return jdbc.sql(
            "INSERT INTO content (category, popularity_score) VALUES ('drama', 1) RETURNING id")
        .query(Long.class)
        .single();
  }

  private static long place() {
    return jdbc.sql(
            """
            INSERT INTO place (type, geom, popularity_score)
            VALUES ('시험촬영지', ST_SetSRID(ST_MakePoint(127, 37), 4326)::geography, 1)
            RETURNING id
            """)
        .query(Long.class)
        .single();
  }

  /** 설치 {@code installId} 로 작품 상세를 {@code day}(한국 날짜)에 봄. */
  private static void view(UUID installId, long contentId, String day) {
    viewAs(installId, null, contentId, day);
  }

  private static void viewAs(UUID installId, UUID userId, long contentId, String day) {
    jdbc.sql(
            "INSERT INTO entity_view (entity_type, entity_id, install_id, user_id, lang, day)"
                + " VALUES ('content', :id, :i, :u, 'ko', CAST(:d AS date))")
        .param("id", contentId)
        .param("i", installId)
        .param("u", userId)
        .param("d", day)
        .update();
  }

  private static void register(UUID userId, String at) {
    jdbc.sql("UPDATE app_user SET registered_at = CAST(:at AS timestamptz) WHERE id = :u")
        .param("u", userId)
        .param("at", at)
        .update();
  }

  private static void savedContent(UUID userId, long contentId, String at) {
    jdbc.sql(
            "INSERT INTO saved_content (user_id, content_id, created_at)"
                + " VALUES (:u, :c, CAST(:at AS timestamptz))")
        .param("u", userId)
        .param("c", contentId)
        .param("at", at)
        .update();
  }

  private static void savedPlace(UUID userId, long placeId, String at) {
    jdbc.sql(
            "INSERT INTO saved_place (user_id, place_id, created_at)"
                + " VALUES (:u, :p, CAST(:at AS timestamptz))")
        .param("u", userId)
        .param("p", placeId)
        .param("at", at)
        .update();
  }

  private static void course(UUID userId, String at) {
    jdbc.sql(
            "INSERT INTO course (user_id, title, day_count, origin, created_at, updated_at)"
                + " VALUES (:u, '집계 시험', 1, 'self', CAST(:at AS timestamptz),"
                + " CAST(:at AS timestamptz))")
        .param("u", userId)
        .param("at", at)
        .update();
  }

  private static void review(UUID userId, long placeId, String at) {
    jdbc.sql(
            "INSERT INTO review (place_id, user_id, rating, created_at, updated_at)"
                + " VALUES (:p, :u, 5, CAST(:at AS timestamptz), CAST(:at AS timestamptz))")
        .param("p", placeId)
        .param("u", userId)
        .param("at", at)
        .update();
  }

  private static void post(UUID userId, String at) {
    jdbc.sql(
            "INSERT INTO community_post (user_id, title, body, created_at)"
                + " VALUES (:u, '집계 시험', '본문', CAST(:at AS timestamptz))")
        .param("u", userId)
        .param("at", at)
        .update();
  }

  private static void search(String query, int resultCount, String lang, String at) {
    jdbc.sql(
            "INSERT INTO search_log (query, result_count, lang, created_at)"
                + " VALUES (:q, :n, CAST(:l AS lang_code), CAST(:at AS timestamptz))")
        .param("q", query)
        .param("n", resultCount)
        .param("l", lang)
        .param("at", at)
        .update();
  }

  // ── 칸 ──────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("V41 의 뷰 다섯 개가 analytics 에 있고 명세의 칸을 정확히 가진다")
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
  @DisplayName("다섯 뷰 어디에도 개인을 가리키는 칸이 없다")
  void noPersonalColumnInTheNewViews() {
    SPEC.keySet()
        .forEach(
            view -> {
              List<String> cols = columns(view);
              // 뷰가 없으면 칸 목록이 비어 아래 단언이 공허하게 통과한다.
              assertThat(cols).as(view).isNotEmpty();
              assertThat(cols)
                  .as(view)
                  .allSatisfy(
                      column -> {
                        assertThat(PERSONAL).as(view + "." + column).doesNotContain(column);
                        assertThat(column)
                            .as(view + "." + column)
                            .doesNotContain("email")
                            .doesNotContain("token")
                            .doesNotContain("install_id")
                            .doesNotContain("user_id");
                      });
            });
  }

  // ── 설치 품질 ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("daily_install_quality — 설치 / 이 설치로 상세를 본 설치 / 서로 다른 2 일 이상 본 설치(한국 날짜)")
  void installQualityCountsActiveAndReturningByViews() {
    inTx(
        () -> {
          long work = content();
          long work2 = content();

          Install once = install(ON_DAY); // 설치 날 한 번 → 활동, 재방문 아님
          view(once.installId(), work, DAY);

          Install twoThingsSameDay = install(ON_DAY); // 같은 날 둘 → 활동, 재방문 아님
          view(twoThingsSameDay.installId(), work, DAY);
          view(twoThingsSameDay.installId(), work2, DAY);

          Install nextDay = install(ON_DAY); // 설치 날 + 다음 날 → 재방문
          view(nextDay.installId(), work, DAY);
          view(nextDay.installId(), work, plusDays(DAY, 1));

          Install laterTwoDays = install(ON_DAY); // 설치 날은 안 보고 다른 두 날 → 재방문
          view(laterTwoDays.installId(), work, plusDays(DAY, 2));
          view(laterTwoDays.installId(), work, plusDays(DAY, 5));

          // 사람은 같지만 다른 설치 값으로 기록된 조회는 이 설치의 조회가 아니다(조회는 install_id 로 잇는다).
          Install idle = install(ON_DAY);
          viewAs(UUID.randomUUID(), idle.userId(), work, DAY);
          viewAs(UUID.randomUUID(), idle.userId(), work, plusDays(DAY, 1));

          Install dayBefore = install(ON_DAY_BEFORE); // 한국 03-09 설치, 두 날 봄
          view(dayBefore.installId(), work, DAY_BEFORE);
          view(dayBefore.installId(), work, DAY);

          assertThat(
                  rows(
                      "SELECT day || '|' || installs || '|' || active_installs || '|' ||"
                          + " returning_installs FROM analytics.daily_install_quality"
                          + dayIn(DAY_BEFORE, DAY)
                          + " ORDER BY day"))
              .containsExactly(DAY_BEFORE + "|1|1|1", DAY + "|5|4|2");
        });
  }

  @Test
  @DisplayName("daily_install_quality — 사람의 찜·장바구니·코스·후기·여행후기·가입 하나만 해도 활동한 설치다")
  void installQualityCountsEveryPersonActivityKind() {
    inTx(
        () -> {
          long work = content();
          long spot = place();

          // 활동 종류마다 다른 날(한국 날짜) — 그날 설치 둘 중 하나만 그 활동을 한다.
          Map<String, Consumer<UUID>> kinds = new LinkedHashMap<>();
          kinds.put("2001-05-01", u -> savedContent(u, work, ON_DAY));
          kinds.put("2001-05-02", u -> savedPlace(u, spot, ON_DAY));
          kinds.put("2001-05-03", u -> course(u, ON_DAY));
          kinds.put("2001-05-04", u -> review(u, spot, ON_DAY));
          kinds.put("2001-05-05", u -> post(u, ON_DAY));
          kinds.put("2001-05-06", u -> register(u, ON_DAY));

          List<String> expected = new ArrayList<>();
          kinds.forEach(
              (day, act) -> {
                Install active = install(earlyMorningKst(day));
                install(earlyMorningKst(day)); // 아무것도 안 한 설치
                act.accept(active.userId());
                expected.add(day + "|2|1|0");
              });

          assertThat(
                  rows(
                      "SELECT day || '|' || installs || '|' || active_installs || '|' ||"
                          + " returning_installs FROM analytics.daily_install_quality"
                          + dayIn(kinds.keySet().toArray(String[]::new))
                          + " ORDER BY day"))
              .as("날짜별 활동 종류: 05-01 찜, 05-02 장바구니, 05-03 코스, 05-04 후기, 05-05 여행후기, 05-06 가입")
              .containsExactlyElementsOf(expected);
        });
  }

  // ── 퍼널 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("funnel_by_install_day — 단계마다 설치 한 번만, 사람 단계는 user_device.user_id 로(한국 날짜)")
  void funnelCountsEachStepOncePerInstall() {
    inTx(
        () -> {
          long work = content();
          long work2 = content();
          long spot = place();
          long spot2 = place();

          Install viewer = install(ON_DAY); // 세 번 봐도 viewed 1
          view(viewer.installId(), work, DAY);
          view(viewer.installId(), work2, DAY);
          view(viewer.installId(), work, plusDays(DAY, 1));

          Install member = install(ON_DAY); // 가입 + 찜 둘 + 장바구니 → signed_in 1, saved 1
          register(member.userId(), ON_DAY);
          savedContent(member.userId(), work, ON_DAY);
          savedContent(member.userId(), work2, ON_DAY);
          savedPlace(member.userId(), spot, ON_DAY);

          // 같은 사람의 두 번째 설치 — 사람 단계(가입·찜)는 이 설치에도 닿는다.
          installFor(member.userId(), ON_DAY);

          Install planner = install(ON_DAY); // 코스 둘 → course_created 1
          course(planner.userId(), ON_DAY);
          course(planner.userId(), ON_DAY);
          // 사람은 같지만 다른 설치 값의 조회 — viewed 에 넣지 않는다.
          viewAs(UUID.randomUUID(), planner.userId(), work, DAY);

          Install writer = install(ON_DAY); // 후기 + 여행후기 → posted 1
          review(writer.userId(), spot, ON_DAY);
          review(writer.userId(), spot2, ON_DAY);
          post(writer.userId(), ON_DAY);

          install(ON_DAY); // 아무것도 안 함

          Install dayBefore = install(ON_DAY_BEFORE); // 한국 03-09 설치, 가입만
          register(dayBefore.userId(), ON_DAY_BEFORE);

          assertThat(
                  rows(
                      "SELECT day || '|' || installs || '|' || viewed || '|' || signed_in || '|'"
                          + " || saved || '|' || course_created || '|' || posted"
                          + " FROM analytics.funnel_by_install_day"
                          + dayIn(DAY_BEFORE, DAY)
                          + " ORDER BY day"))
              .containsExactly(DAY_BEFORE + "|1|0|1|0|0|0", DAY + "|6|1|2|2|1|1");
        });
  }

  // ── 재방문 ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("retention_by_install_day — 설치일 + N 일 이후(그날 포함) 다시 본 설치(롤링), 설치 날만 본 것은 안 센다")
  void retentionIsRollingFromInstallDay() {
    inTx(
        () -> {
          long work = content();

          // 설치 날만 → 어디에도 안 센다. UTC 날짜로 설치일을 잡으면 d1 에 잘못 들어간다.
          Install sameDayOnly = install(ON_DAY);
          view(sameDayOnly.installId(), work, DAY);

          Install plus1 = install(ON_DAY); // d1
          view(plus1.installId(), work, DAY);
          view(plus1.installId(), work, plusDays(DAY, 1));

          Install plus6 = install(ON_DAY); // d1 (d7 경계 하루 전)
          view(plus6.installId(), work, plusDays(DAY, 6));

          Install plus7 = install(ON_DAY); // d1·d7, d30 아님
          view(plus7.installId(), work, plusDays(DAY, 7));

          Install plus29 = install(ON_DAY); // d1·d7 (d30 경계 하루 전)
          view(plus29.installId(), work, plusDays(DAY, 29));

          Install plus30 = install(ON_DAY); // d1·d7·d30
          view(plus30.installId(), work, plusDays(DAY, 30));

          install(ON_DAY); // 안 봄

          // 사람은 같지만 다른 설치 값의 다음 날 조회 — 이 설치의 재방문이 아니다.
          Install other = install(ON_DAY);
          viewAs(UUID.randomUUID(), other.userId(), work, plusDays(DAY, 8));

          Install dayBefore = install(ON_DAY_BEFORE); // 한국 03-09 설치, 03-10 에 봄 → d1
          view(dayBefore.installId(), work, DAY);

          assertThat(
                  rows(
                      "SELECT day || '|' || installs || '|' || returned_d1 || '|' || returned_d7"
                          + " || '|' || returned_d30 FROM analytics.retention_by_install_day"
                          + dayIn(DAY_BEFORE, DAY)
                          + " ORDER BY day"))
              .containsExactly(DAY_BEFORE + "|1|1|0|0", DAY + "|8|5|3|1");
        });
  }

  // ── 검색 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("daily_search — search_log 일별 검색 수와 결과 0 건 수(한국 날짜)")
  void dailySearchCountsAndZeroResults() {
    inTx(
        () -> {
          search("집계시험어", 3, "ko", ON_DAY);
          search("집계시험어", 0, "en", ON_DAY);
          search("없는집계어", 0, "ko", ON_DAY);
          search("집계시험어", 5, "ko", ON_DAY_BEFORE);

          assertThat(
                  rows(
                      "SELECT day || '|' || searches || '|' || zero_result_searches"
                          + " FROM analytics.daily_search"
                          + dayIn(DAY_BEFORE, DAY)
                          + " ORDER BY day"))
              .containsExactly(DAY_BEFORE + "|1|0", DAY + "|3|2");
        });
  }

  @Test
  @DisplayName("search_terms — 일별·검색어별 검색 수와 결과 0 건 수(언어는 합친다, 한국 날짜)")
  void searchTermsPerDayAndQuery() {
    inTx(
        () -> {
          search("집계시험어", 3, "ko", ON_DAY);
          search("집계시험어", 0, "en", ON_DAY);
          search("없는집계어", 0, "ko", ON_DAY);
          search("집계시험어", 5, "ko", ON_DAY_BEFORE);

          assertThat(
                  rows(
                      "SELECT day || '|' || query || '|' || searches || '|' ||"
                          + " zero_result_searches FROM analytics.search_terms"
                          + dayIn(DAY_BEFORE, DAY)))
              .containsExactlyInAnyOrder(
                  DAY_BEFORE + "|집계시험어|1|0", DAY + "|집계시험어|2|1", DAY + "|없는집계어|1|1");
        });
  }
}
