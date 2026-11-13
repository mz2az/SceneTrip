package com.mz2az.scenetrip.sceneapi.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 팀원 계정 빼기(계획 {@code metabase-prd.md} §3-7, MZ2AZ-563)를 실제 PostgreSQL 에서 본다 — 팀원 이메일 해시 표에 오른 계정과 그
 * 계정으로 합쳐진 비회원 계정, 그 계정들의 설치가 바뀐 일곱 뷰에서 빠지고, 같은 날의 외부 사용자는 그대로 남는지.
 *
 * <p>진짜 이메일은 이 파일 어디에도 없다. 시험마다 트랜잭션 안에서 가짜 주소 {@link #TEAM_EMAIL} 의 해시를 {@code
 * public.analytics_internal_email} 에 직접 넣고, 그 주소를 대소문자만 바꾼 소셜 신분을 만든다 — 해시는 소문자로 바꾼 주소에서 낸다(명세).
 *
 * <p>공유 로컬 DB 다. 픽스처는 2001 년 — 실제 데이터가 없는 날 — 에 넣고, 시험마다 한 트랜잭션 안에서 넣고 읽은 뒤 통째로 되돌린다({@link
 * IntegrationDatabase#rolledBack}).
 */
@DisplayName("analytics 뷰 — 팀원 계정 빼기 (실제 DB)")
class AnalyticsInternalAccountsIntegrationTest {

  /** 픽스처의 한국 날짜. */
  private static final String DAY = "2001-03-10";

  /** 한국 시간 2001-03-10 01:30 — UTC 로는 전날이지만 {@link #DAY} 에 센다. */
  private static final String ON_DAY = "2001-03-09T16:30:00Z";

  /** 가짜 팀원 주소. 표에는 이것을 소문자로 바꾼 것의 해시만 들어간다. */
  private static final String TEAM_EMAIL = "Team.Member@example.test";

  /** 바뀌는 일곱 뷰의 칸 — 이름·순서·형이 V40·V41 그대로여야 한다. */
  private static final Map<String, List<String>> CHANGED = new LinkedHashMap<>();

  static {
    CHANGED.put("daily_new_installs", List.of("day:date", "installs:bigint"));
    CHANGED.put("daily_new_members", List.of("day:date", "provider:text", "members:bigint"));
    CHANGED.put("daily_active_viewers", List.of("day:date", "viewers:bigint"));
    CHANGED.put("daily_actions", List.of("day:date", "action:text", "actions:bigint"));
    CHANGED.put(
        "daily_install_quality",
        List.of(
            "day:date", "installs:bigint", "active_installs:bigint", "returning_installs:bigint"));
    CHANGED.put(
        "funnel_by_install_day",
        List.of(
            "day:date",
            "installs:bigint",
            "viewed:bigint",
            "signed_in:bigint",
            "saved:bigint",
            "course_created:bigint",
            "posted:bigint"));
    CHANGED.put(
        "retention_by_install_day",
        List.of(
            "day:date",
            "installs:bigint",
            "returned_d1:bigint",
            "returned_d7:bigint",
            "returned_d30:bigint"));
  }

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

  /** 줄마다 칸을 '|' 로 이은 문자열. */
  private static List<String> rows(String sql) {
    return jdbc.sql(sql).query(String.class).list();
  }

  /** 명세의 해시: 소문자로 바꾼 주소의 UTF-8 바이트 SHA-256, 소문자 16 진수 64 글자. */
  private static String sha256OfLowercase(String email) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String plusDays(String day, int days) {
    return LocalDate.parse(day).plusDays(days).toString();
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────────────

  /**
   * 내부 계정 셋.
   *
   * @param member 원래 대소문자 주소로 구글 로그인한 팀원
   * @param memberUpper 같은 주소를 모두 대문자로 애플 로그인한 다른 계정(소문자 규칙)
   * @param mergedAnon 로그인 전 비회원 계정 — {@code merged_into} 가 {@code member}
   */
  private record Team(UUID member, UUID memberUpper, UUID mergedAnon) {}

  private static UUID newUser() {
    return jdbc.sql("INSERT INTO app_user (id) VALUES (gen_random_uuid()) RETURNING id")
        .query(UUID.class)
        .single();
  }

  private static void registerInternalEmail(String email) {
    jdbc.sql("INSERT INTO public.analytics_internal_email (email_sha256, note) VALUES (:h, :n)")
        .param("h", sha256OfLowercase(email))
        .param("n", "integration test")
        .update();
  }

  private static void identity(String provider, UUID userId, String email, String at) {
    jdbc.sql(
            "INSERT INTO user_identity (provider, subject, user_id, email, created_at)"
                + " VALUES (:p, 'analytics-it-' || gen_random_uuid(), :u, :e,"
                + " CAST(:at AS timestamptz))")
        .param("p", provider)
        .param("u", userId)
        .param("e", email)
        .param("at", at)
        .update();
  }

  private static void register(UUID userId, String at) {
    jdbc.sql("UPDATE app_user SET registered_at = CAST(:at AS timestamptz) WHERE id = :u")
        .param("u", userId)
        .param("at", at)
        .update();
  }

  /** 팀원 해시를 표에 넣고 내부 계정 셋을 만든다. 신분은 {@code identityAt} 에 생긴다. */
  private static Team team(String identityAt) {
    registerInternalEmail(TEAM_EMAIL);

    UUID member = newUser();
    register(member, identityAt);
    identity("google", member, TEAM_EMAIL, identityAt);

    UUID memberUpper = newUser();
    register(memberUpper, identityAt);
    identity("apple", memberUpper, TEAM_EMAIL.toUpperCase(Locale.ROOT), identityAt);

    UUID mergedAnon = newUser();
    jdbc.sql("UPDATE app_user SET merged_into = :m WHERE id = :u")
        .param("m", member)
        .param("u", mergedAnon)
        .update();
    return new Team(member, memberUpper, mergedAnon);
  }

  /** 외부 사용자 — 이메일이 표에 없는 가입 계정. */
  private static UUID externalMember(String provider, String at) {
    UUID user = newUser();
    register(user, at);
    identity(provider, user, "someone-" + UUID.randomUUID() + "@example.test", at);
    return user;
  }

  private static UUID installFor(UUID userId, String at) {
    return jdbc.sql(
            "INSERT INTO user_device (install_uuid, user_id, created_at)"
                + " VALUES (gen_random_uuid(), :u, CAST(:at AS timestamptz))"
                + " RETURNING install_uuid")
        .param("u", userId)
        .param("at", at)
        .query(UUID.class)
        .single();
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

  /** 찜·장바구니·코스·후기·여행후기를 하나씩. */
  private static void everyAction(UUID userId, String at) {
    savedContent(userId, content(), at);
    savedPlace(userId, place(), at);
    course(userId, at);
    review(userId, place(), at);
    post(userId, at);
  }

  // ── 저장과 내부 계정 ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "public.analytics_internal_email(email_sha256, note) 표와"
          + " public.analytics_internal_user(user_id) 뷰가 있다")
  void storageTableAndInternalUserViewExist() {
    assertThat(
            rows(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND table_name = 'analytics_internal_email' ORDER BY ordinal_position"))
        .containsExactly("email_sha256", "note");
    assertThat(
            rows(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND table_name = 'analytics_internal_user' ORDER BY ordinal_position"))
        .containsExactly("user_id");
    assertThat(
            rows(
                "SELECT table_name FROM information_schema.views WHERE table_schema = 'public'"
                    + " AND table_name = 'analytics_internal_user'"))
        .containsExactly("analytics_internal_user");
  }

  @Test
  @DisplayName("analytics_internal_user — 소문자 해시가 표에 있는 신분의 계정 + 그 계정으로 합쳐진 비회원, 외부는 없다")
  void internalUserIsHashedIdentityPlusMergedAnonymous() {
    inTx(
        () -> {
          Team team = team(ON_DAY);
          UUID external = externalMember("google", ON_DAY);
          UUID unrelatedAnon = newUser();
          UUID mergedIntoExternal = newUser();
          jdbc.sql("UPDATE app_user SET merged_into = :m WHERE id = :u")
              .param("m", external)
              .param("u", mergedIntoExternal)
              .update();

          List<String> internal =
              rows(
                  "SELECT user_id::text FROM public.analytics_internal_user WHERE user_id IN ('"
                      + String.join(
                          "', '",
                          List.of(
                              team.member().toString(),
                              team.memberUpper().toString(),
                              team.mergedAnon().toString(),
                              external.toString(),
                              unrelatedAnon.toString(),
                              mergedIntoExternal.toString()))
                      + "')");
          assertThat(internal)
              .containsExactlyInAnyOrder(
                  team.member().toString(),
                  team.memberUpper().toString(),
                  team.mergedAnon().toString());
        });
  }

  // ── 칸은 그대로 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("바뀌는 일곱 뷰의 칸 이름·순서·형이 그대로다")
  void changedViewsKeepTheirColumns() {
    CHANGED.forEach(
        (view, expected) ->
            assertThat(
                    jdbc.sql(
                            """
                            SELECT column_name || ':' || data_type FROM information_schema.columns
                            WHERE table_schema = 'analytics' AND table_name = :v
                            ORDER BY ordinal_position
                            """)
                        .param("v", view)
                        .query(String.class)
                        .list())
                .as(view)
                .containsExactlyElementsOf(expected));
  }

  // ── V40 의 뷰 ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("daily_new_installs — 팀원·합쳐진 비회원 계정의 설치는 빼고 외부 설치만 센다")
  void newInstallsExcludeInternalInstalls() {
    inTx(
        () -> {
          Team team = team(ON_DAY);
          installFor(team.member(), ON_DAY);
          installFor(team.member(), ON_DAY); // 팀원의 두 번째 기기
          installFor(team.memberUpper(), ON_DAY);
          installFor(team.mergedAnon(), ON_DAY);

          installFor(externalMember("google", ON_DAY), ON_DAY);
          installFor(newUser(), ON_DAY); // 로그인 안 한 외부 설치

          assertThat(
                  rows(
                      "SELECT day || '|' || installs FROM analytics.daily_new_installs"
                          + " WHERE day = DATE '"
                          + DAY
                          + "'"))
              .containsExactly(DAY + "|2");
        });
  }

  @Test
  @DisplayName("daily_new_members — user_id 가 내부 계정인 신분은 빼고(대소문자 무관, 다른 주소의 신분도), 외부만 센다")
  void newMembersExcludeInternalIdentities() {
    inTx(
        () -> {
          Team team = team(ON_DAY); // google 1(원래 대소문자) + apple 1(대문자)
          // 같은 팀원 계정에 붙은 애플 대리 주소 신분 — 이메일은 표에 없지만 user_id 가 내부라 빠진다.
          identity(
              "apple", team.member(), "relay-" + UUID.randomUUID() + "@relay.example.test", ON_DAY);

          externalMember("google", ON_DAY);
          externalMember("google", ON_DAY);
          externalMember("apple", ON_DAY);

          assertThat(
                  rows(
                      "SELECT day || '|' || provider || '|' || members"
                          + " FROM analytics.daily_new_members WHERE day = DATE '"
                          + DAY
                          + "'"))
              .containsExactlyInAnyOrder(DAY + "|google|2", DAY + "|apple|1");
        });
  }

  @Test
  @DisplayName("daily_active_viewers — 내부 설치의 조회, user_id 가 내부 계정(합쳐진 비회원 포함)인 조회를 뺀다")
  void activeViewersExcludeInternalInstallsAndUsers() {
    inTx(
        () -> {
          Team team = team(ON_DAY);
          long work = content();

          UUID teamInstall = installFor(team.member(), ON_DAY);
          viewAs(teamInstall, null, work, DAY); // 내부 설치, user_id 없음
          viewAs(UUID.randomUUID(), team.memberUpper(), work, DAY); // 설치 값은 모르지만 사람이 내부
          viewAs(UUID.randomUUID(), team.mergedAnon(), work, DAY); // 합쳐진 비회원
          UUID anonInstall = installFor(team.mergedAnon(), ON_DAY);
          viewAs(anonInstall, null, work, DAY); // 합쳐진 비회원의 설치

          UUID outsider = newUser();
          viewAs(installFor(outsider, ON_DAY), outsider, work, DAY);
          viewAs(UUID.randomUUID(), null, work, DAY); // 설치 표에 없는 외부 조회

          assertThat(
                  rows(
                      "SELECT day || '|' || viewers FROM analytics.daily_active_viewers"
                          + " WHERE day = DATE '"
                          + DAY
                          + "'"))
              .containsExactly(DAY + "|2");
        });
  }

  @Test
  @DisplayName("daily_actions — 내부 계정(합쳐진 비회원 포함)의 찜·장바구니·코스·후기·여행후기를 뺀다")
  void actionsExcludeInternalUsers() {
    inTx(
        () -> {
          Team team = team(ON_DAY);
          everyAction(team.member(), ON_DAY);
          everyAction(team.memberUpper(), ON_DAY);
          everyAction(team.mergedAnon(), ON_DAY);

          everyAction(externalMember("google", ON_DAY), ON_DAY);

          assertThat(
                  rows(
                      "SELECT day || '|' || action || '|' || actions FROM analytics.daily_actions"
                          + " WHERE day = DATE '"
                          + DAY
                          + "' AND action IN ('save_content', 'save_place', 'course_create',"
                          + " 'review_create', 'post_create')"))
              .containsExactlyInAnyOrder(
                  DAY + "|save_content|1",
                  DAY + "|save_place|1",
                  DAY + "|course_create|1",
                  DAY + "|review_create|1",
                  DAY + "|post_create|1");
        });
  }

  // ── V41 의 뷰 ───────────────────────────────────────────────────────────────

  /**
   * 같은 날 내부 설치 셋(팀원·대문자 팀원·합쳐진 비회원)과 외부 설치 둘. 내부 설치는 모두 가입·찜·장바구니·코스·후기·여행후기를 하고 설치일·다음 날·+7·+30 에
   * 상세를 본다. 외부는 하나만 설치 날과 다음 날 보고 찜한다, 하나는 아무것도 안 한다.
   */
  private static void installDayFixture() {
    Team team = team(ON_DAY);
    long work = content();
    for (UUID internal : List.of(team.member(), team.memberUpper(), team.mergedAnon())) {
      register(internal, ON_DAY);
      everyAction(internal, ON_DAY);
      UUID install = installFor(internal, ON_DAY);
      for (int offset : List.of(0, 1, 7, 30)) {
        viewAs(install, internal, work, plusDays(DAY, offset));
      }
    }

    UUID outsider = newUser();
    UUID outsiderInstall = installFor(outsider, ON_DAY);
    viewAs(outsiderInstall, outsider, work, DAY);
    viewAs(outsiderInstall, outsider, work, plusDays(DAY, 1));
    savedContent(outsider, work, ON_DAY);

    installFor(newUser(), ON_DAY); // 아무것도 안 한 외부 설치
  }

  @Test
  @DisplayName("daily_install_quality — 내부 설치와 그 활동·재방문을 빼고 외부 설치만 센다")
  void installQualityExcludesInternalInstalls() {
    inTx(
        () -> {
          installDayFixture();
          assertThat(
                  rows(
                      "SELECT day || '|' || installs || '|' || active_installs || '|' ||"
                          + " returning_installs FROM analytics.daily_install_quality"
                          + " WHERE day = DATE '"
                          + DAY
                          + "'"))
              .containsExactly(DAY + "|2|1|1");
        });
  }

  @Test
  @DisplayName("funnel_by_install_day — 내부 설치는 어느 단계에도 안 센다")
  void funnelExcludesInternalInstalls() {
    inTx(
        () -> {
          installDayFixture();
          assertThat(
                  rows(
                      "SELECT day || '|' || installs || '|' || viewed || '|' || signed_in || '|'"
                          + " || saved || '|' || course_created || '|' || posted"
                          + " FROM analytics.funnel_by_install_day WHERE day = DATE '"
                          + DAY
                          + "'"))
              .containsExactly(DAY + "|2|1|0|1|0|0");
        });
  }

  @Test
  @DisplayName("retention_by_install_day — 내부 설치의 재방문을 빼고 외부 설치만 센다")
  void retentionExcludesInternalInstalls() {
    inTx(
        () -> {
          installDayFixture();
          assertThat(
                  rows(
                      "SELECT day || '|' || installs || '|' || returned_d1 || '|' || returned_d7"
                          + " || '|' || returned_d30 FROM analytics.retention_by_install_day"
                          + " WHERE day = DATE '"
                          + DAY
                          + "'"))
              .containsExactly(DAY + "|2|1|0|0");
        });
  }
}
