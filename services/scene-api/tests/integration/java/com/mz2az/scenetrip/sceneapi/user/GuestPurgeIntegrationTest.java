package com.mz2az.scenetrip.sceneapi.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 방치된 비회원 계정 정리(MZ2AZ-467) — {@link GuestPurge#purge()} 를 실제 DB 로 본다.
 *
 * <p>명세에서 나온 기대: 비회원({@code registered_at IS NULL}) 중 마지막 사용({@code last_seen_at}, 없으면 {@code
 * created_at})이 보관 기간보다 오래된 계정을 지운다. 회원에 합쳐진 비회원 줄도 같은 규칙으로 지운다. 회원은 아무리 오래돼도 남고, 기간 안의 계정도 남는다. 지운
 * 계정에 매달린 기기 연결·장바구니·코스·찜은 FK CASCADE 로 사라지고, 그 계정이 한 신고는 줄이 남고 신고자만 비워진다. 한 번에 최대 {@code batch} 개,
 * 마지막 사용이 오래된 순서로 지우고 나머지는 다음 호출에서 지운다. DB 오류에도 던지지 않는다.
 *
 * <p>전부 되돌리는 트랜잭션 하나 안에서 돈다 — purge 는 같은 DataSource 의 접속을 써서 이 트랜잭션에 들어온다. 그래서 개발 DB 의 다른 오래된 비회원이
 * 같이 지워져도 끝나면 되돌아간다. 한도·순서 시험은 개발 데이터보다 확실히 오래된 1980~1990 년대 시각을 써서, 이 시험이 만든 계정이 가장 오래된 후보가 되게 한다.
 */
@DisplayName("방치된 비회원 계정 정리 — GuestPurge.purge (실제 DB)")
class GuestPurgeIntegrationTest {

  /** 한도가 결과를 가리지 않게 하는 큰 값. 경계 시험에서 개발 DB 의 오래된 비회원이 몇 개든 이 시험의 계정까지 닿게 한다. */
  private static final int UNLIMITED = Integer.MAX_VALUE;

  private static JdbcClient jdbc;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  @Test
  @DisplayName("보관 기간 경계 — 365 일이면 365 일+1 시간 전·400 일 전은 지우고, 364 일 23 시간 전·방금은 남긴다")
  void retentionBoundary() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID older = guest("400 days", "400 days");
          UUID justOver = guest("500 days", "365 days 1 hour");
          UUID justUnder = guest("500 days", "364 days 23 hours");
          UUID fresh = guest("500 days", "0 seconds");

          new GuestPurge(jdbc, 365, UNLIMITED).purge();

          assertThat(exists(older)).isFalse();
          assertThat(exists(justOver)).isFalse();
          assertThat(exists(justUnder)).isTrue();
          assertThat(exists(fresh)).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("보관 기간은 생성자 값을 따른다 — 30 일이면 31 일 전은 지우고 29 일 전은 남긴다. 다시 불러도 같다")
  void retentionFollowsSetting() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID older = guest("31 days", "31 days");
          UUID within = guest("31 days", "29 days");

          GuestPurge purge = new GuestPurge(jdbc, 30, UNLIMITED);
          purge.purge();
          purge.purge();

          assertThat(exists(older)).isFalse();
          assertThat(exists(within)).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("last_seen_at 이 없으면 created_at 이 마지막 사용 — 400 일 전 생성은 지우고 300 일 전 생성은 남긴다")
  void nullLastSeenFallsBackToCreatedAt() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID oldNeverSeen = guest("400 days", null);
          UUID recentNeverSeen = guest("300 days", null);

          new GuestPurge(jdbc, 365, UNLIMITED).purge();

          assertThat(exists(oldNeverSeen)).isFalse();
          assertThat(exists(recentNeverSeen)).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName(
      "last_seen_at 이 있으면 그것이 기준 — 400 일 전 생성이라도 10 일 전에 썼으면 남고, 10 일 전 생성이라도 last_seen_at 이 400 일"
          + " 전이면 지운다")
  void lastSeenTakesPrecedenceOverCreatedAt() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID oldButActive = guest("400 days", "10 days");
          UUID newButStaleSeen = guest("10 days", "400 days");

          new GuestPurge(jdbc, 365, UNLIMITED).purge();

          assertThat(exists(oldButActive)).isTrue();
          assertThat(exists(newButStaleSeen)).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("회원은 아무리 오래돼도 남는다 — last_seen_at 이 없든 있든. 기간 안의 회원도 그대로")
  void membersAreNeverDeleted() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID ancientNeverSeen = member("3000 days", null);
          UUID ancientSeen = member("3000 days", "2000 days");
          UUID recent = member("1 day", "1 day");

          new GuestPurge(jdbc, 365, UNLIMITED).purge();

          assertThat(exists(ancientNeverSeen)).isTrue();
          assertThat(exists(ancientSeen)).isTrue();
          assertThat(exists(recent)).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("회원에 합쳐진 비회원 줄도 같은 규칙 — 오래된 것은 지우고 기간 안의 것은 남긴다. 합쳐 받은 회원은 남는다")
  void mergedGuestRowsFollowTheSameRule() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID target = member("1000 days", "1000 days");
          UUID oldMerged = guest("400 days", "400 days");
          UUID recentMerged = guest("100 days", "100 days");
          mergeInto(oldMerged, target);
          mergeInto(recentMerged, target);

          new GuestPurge(jdbc, 365, UNLIMITED).purge();

          assertThat(exists(oldMerged)).isFalse();
          assertThat(exists(recentMerged)).isTrue();
          assertThat(exists(target)).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("지운 계정의 기기 연결·장바구니·코스·작품 찜은 함께 사라지고, 그 계정이 한 신고는 줄이 남고 신고자만 비워진다. 남는 계정의 것은 그대로")
  void cascadesAndReporterBecomesNull() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          long content = content();
          UUID stale = guest("400 days", "400 days");
          UUID kept = guest("400 days", "1 day");
          UUID device = device(stale);
          UUID keptDevice = device(kept);
          cart(stale, place);
          cart(kept, place);
          long course = course(stale);
          long keptCourse = course(kept);
          favorite(stale, content);
          favorite(kept, content);
          long report = report(stale, place);
          long keptReport = report(kept, place);

          new GuestPurge(jdbc, 365, UNLIMITED).purge();

          assertThat(exists(stale)).isFalse();
          assertThat(count("SELECT count(*) FROM user_device WHERE install_uuid = :v", device))
              .isZero();
          assertThat(count("SELECT count(*) FROM user_device WHERE user_id = :v", stale)).isZero();
          assertThat(count("SELECT count(*) FROM saved_place WHERE user_id = :v", stale)).isZero();
          assertThat(count("SELECT count(*) FROM course WHERE id = :v", course)).isZero();
          assertThat(count("SELECT count(*) FROM saved_content WHERE user_id = :v", stale))
              .isZero();
          assertThat(count("SELECT count(*) FROM content_report WHERE id = :v", report))
              .isEqualTo(1);
          assertThat(
                  jdbc.sql("SELECT reporter_id IS NULL FROM content_report WHERE id = :id")
                      .param("id", report)
                      .query(Boolean.class)
                      .single())
              .isTrue();

          assertThat(exists(kept)).isTrue();
          assertThat(count("SELECT count(*) FROM user_device WHERE install_uuid = :v", keptDevice))
              .isEqualTo(1);
          assertThat(count("SELECT count(*) FROM saved_place WHERE user_id = :v", kept))
              .isEqualTo(1);
          assertThat(count("SELECT count(*) FROM course WHERE id = :v", keptCourse)).isEqualTo(1);
          assertThat(count("SELECT count(*) FROM saved_content WHERE user_id = :v", kept))
              .isEqualTo(1);
          assertThat(
                  jdbc.sql("SELECT reporter_id::text FROM content_report WHERE id = :id")
                      .param("id", keptReport)
                      .query(String.class)
                      .single())
              .isEqualTo(kept.toString());
          return null;
        });
  }

  @Test
  @DisplayName(
      "한 번에 batch 개까지, 마지막 사용이 오래된 순서로 — 2 개면 1991·1993 을 지우고 1995 는 남긴다. 다음 호출에서 나머지를 지운다. 더 오래된"
          + " 회원은 한도를 차지하지 않는다")
  void batchLimitOldestFirst() {
    IntegrationDatabase.rolledBack(
        () -> {
          // 마지막 사용 = coalesce(last_seen_at, created_at).
          // created_at 순으로 고르면 c(1980)·a(1990)가 지워져 이 시험이 갈린다.
          UUID a = guestAt("1990-01-01", "1995-01-01"); // 마지막 사용 1995
          UUID b = guestAt("1993-01-01", null); // 마지막 사용 1993
          UUID c = guestAt("1980-01-01", "1991-01-01"); // 마지막 사용 1991
          UUID oldMember = memberAt("1970-01-01", null); // 가장 오래됐지만 회원

          GuestPurge purge = new GuestPurge(jdbc, 365, 2);
          purge.purge();

          assertThat(exists(c)).isFalse();
          assertThat(exists(b)).isFalse();
          assertThat(exists(a)).isTrue();
          assertThat(exists(oldMember)).isTrue();

          purge.purge();

          assertThat(exists(a)).isFalse();
          assertThat(exists(oldMember)).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("batch 가 1 이면 한 번에 하나 — 가장 오래된 것부터")
  void batchOfOne() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID first = guestAt("1981-01-01", null);
          UUID second = guestAt("1982-01-01", null);

          GuestPurge purge = new GuestPurge(jdbc, 365, 1);
          purge.purge();

          assertThat(exists(first)).isFalse();
          assertThat(exists(second)).isTrue();

          purge.purge();

          assertThat(exists(second)).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("DB 에 닿지 못해도 purge 는 던지지 않는다(로그만)")
  void purgeSwallowsDatabaseFailure() {
    DriverManagerDataSource broken =
        new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=1");
    broken.setDriverClassName("org.postgresql.Driver");
    GuestPurge purge = new GuestPurge(JdbcClient.create(broken), 365, 10000);

    assertThatCode(purge::purge).doesNotThrowAnyException();
  }

  // ───────────── 도우미 ─────────────

  /**
   * 비회원. {@code createdAgo}·{@code lastSeenAgo} 는 지금으로부터의 간격, {@code lastSeenAgo} 가 null 이면 비운다.
   */
  private static UUID guest(String createdAgo, String lastSeenAgo) {
    return user(false, createdAgo, lastSeenAgo);
  }

  /** 회원. 가입 시각은 만든 시각과 같게 둔다. */
  private static UUID member(String createdAgo, String lastSeenAgo) {
    return user(true, createdAgo, lastSeenAgo);
  }

  private static UUID user(boolean registered, String createdAgo, String lastSeenAgo) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO app_user (id, registered_at, last_seen_at, created_at)
            VALUES (CAST(:id AS UUID),
                    CASE WHEN :reg THEN now() - CAST(:created AS interval) END,
                    now() - CAST(:seen AS interval),
                    now() - CAST(:created AS interval))
            """)
        .param("id", id.toString())
        .param("reg", registered)
        .param("created", createdAgo)
        .param("seen", lastSeenAgo)
        .update();
    return id;
  }

  /** 절대 시각(UTC 날짜)으로 만든 비회원. 한도·순서 시험용. */
  private static UUID guestAt(String created, String lastSeen) {
    return userAt(false, created, lastSeen);
  }

  private static UUID memberAt(String created, String lastSeen) {
    return userAt(true, created, lastSeen);
  }

  private static UUID userAt(boolean registered, String created, String lastSeen) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO app_user (id, registered_at, last_seen_at, created_at)
            VALUES (CAST(:id AS UUID),
                    CASE WHEN :reg THEN CAST(:created AS timestamptz) END,
                    CAST(:seen AS timestamptz),
                    CAST(:created AS timestamptz))
            """)
        .param("id", id.toString())
        .param("reg", registered)
        .param("created", created + " 00:00:00+00")
        .param("seen", lastSeen == null ? null : lastSeen + " 00:00:00+00")
        .update();
    return id;
  }

  private static void mergeInto(UUID from, UUID to) {
    jdbc.sql("UPDATE app_user SET merged_into = CAST(:to AS UUID) WHERE id = CAST(:id AS UUID)")
        .param("to", to.toString())
        .param("id", from.toString())
        .update();
  }

  private static UUID device(UUID user) {
    UUID install = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO user_device (install_uuid, user_id, last_seen_at)"
                + " VALUES (CAST(:i AS UUID), CAST(:u AS UUID), now())")
        .param("i", install.toString())
        .param("u", user.toString())
        .update();
    return install;
  }

  private static void cart(UUID user, long place) {
    jdbc.sql("INSERT INTO saved_place (user_id, place_id) VALUES (CAST(:u AS UUID), :p)")
        .param("u", user.toString())
        .param("p", place)
        .update();
  }

  private static long course(UUID user) {
    return jdbc.sql(
            "INSERT INTO course (user_id, title, day_count, origin) VALUES (CAST(:u AS UUID),"
                + " 'it-guest-purge', 1, 'self') RETURNING id")
        .param("u", user.toString())
        .query(Long.class)
        .single();
  }

  private static void favorite(UUID user, long content) {
    jdbc.sql("INSERT INTO saved_content (user_id, content_id) VALUES (CAST(:u AS UUID), :c)")
        .param("u", user.toString())
        .param("c", content)
        .update();
  }

  /** 신고 한 줄. 대상 글과는 외래 키가 없어 대상 번호는 아무 값이나 된다. */
  private static long report(UUID reporter, long target) {
    return jdbc.sql(
            "INSERT INTO content_report (reporter_id, target_type, target_id, reason)"
                + " VALUES (CAST(:u AS UUID), 'review', :t, 'spam') RETURNING id")
        .param("u", reporter.toString())
        .param("t", target)
        .query(Long.class)
        .single();
  }

  private static long place() {
    return jdbc.sql(
            "INSERT INTO place (type, geom) VALUES ('시험',"
                + " ST_SetSRID(ST_MakePoint(126.98, 37.58), 4326)::geography) RETURNING id")
        .query(Long.class)
        .single();
  }

  private static long content() {
    return jdbc.sql("INSERT INTO content (category) VALUES ('drama') RETURNING id")
        .query(Long.class)
        .single();
  }

  private static boolean exists(UUID id) {
    return count("SELECT count(*) FROM app_user WHERE id = :v", id) == 1;
  }

  private static long count(String sql, Object value) {
    boolean uuid = value instanceof UUID;
    return jdbc.sql(uuid ? sql.replace(":v", "CAST(:v AS UUID)") : sql)
        .param("v", uuid ? value.toString() : value)
        .query(Long.class)
        .single();
  }
}
