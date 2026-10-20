package com.mz2az.scenetrip.sceneapi.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Account;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 마지막 사용 시각({@code last_seen_at}) 기록을 한 시간에 한 번으로 줄인 것을 진짜 PostgreSQL 에 태운다(MZ2AZ-502).
 *
 * <p>{@link UserStore#lookup} 과 {@link UserStore#touchSignedIn} 은 개인 기능 요청마다 불린다. 그때마다 시각을 덮어쓰던
 * UPDATE 가 부하 시험에서 DB 시간의 약 11% 였다. 이 값은 365 일 안 쓴 비회원 정리({@link GuestPurge})에만 쓰이므로 한 시간 단위면 충분하다.
 *
 * <p>명세에서 나온 기대:
 *
 * <ul>
 *   <li>저장된 시각이 비었거나 한 시간보다 오래됐을 때만 지금으로 바꾼다. 한 시간 안이면 한 글자도 바뀌지 않는다.
 *   <li>돌려주는 판정(계정 id·가입 여부, 살아 있는 가입 계정인가)은 기록을 건너뛰어도 전과 같다. 특히 방금 기록된 가입 계정의 토큰이 「믿을 수 없는 토큰」 으로
 *       바뀌면 안 된다 — 이것이 가장 큰 회귀 위험이다.
 *   <li>false 를 돌려주는 계정(없음·비회원·합쳐짐)의 행은 건드리지 않는다.
 * </ul>
 *
 * <p>매번 새 설치 UUID 로 계정을 만들고 끝나면 지운다.
 */
@DisplayName("UserStore — 마지막 사용 시각은 한 시간에 한 번만 기록 (실제 DB)")
class UserStoreLastSeenThrottleIntegrationTest {

  private static final String RECENT = "10 minutes";
  private static final String STALE = "2 hours";

  private static JdbcClient jdbc;
  private static UserStore users;

  private final List<UUID> createdUsers = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    users = new UserStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    // merged_into 가 다른 행을 가리키면 그 행을 먼저 지울 수 없으므로 끊고 지운다.
    for (UUID id : createdUsers) {
      jdbc.sql("UPDATE app_user SET merged_into = NULL WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ───────────── lookup ─────────────

  @Test
  @DisplayName("비회원 — 기기·계정 시각이 10 분 전이면 lookup 은 같은 계정을 돌려주고 두 시각을 그대로 둔다")
  void lookupGuestWithinHourKeepsTimestamps() {
    UUID install = UUID.randomUUID();
    UUID id = track(users.lookup(install)).id();
    setDeviceLastSeenAgo(install, RECENT);
    setUserLastSeenAgo(id, RECENT);
    Instant deviceBefore = deviceLastSeen(install);
    Instant userBefore = userLastSeen(id);

    Account account = users.lookup(install);

    assertThat(account).isEqualTo(new Account(id, false));
    assertThat(deviceLastSeen(install)).as("user_device.last_seen_at").isEqualTo(deviceBefore);
    assertThat(userLastSeen(id)).as("app_user.last_seen_at").isEqualTo(userBefore);
  }

  @Test
  @DisplayName("가입 계정 — 시각이 10 분 전이면 lookup 은 registered=true 를 그대로 돌려주고 두 시각을 그대로 둔다")
  void lookupRegisteredWithinHourKeepsTimestamps() {
    UUID install = UUID.randomUUID();
    UUID id = track(users.lookup(install)).id();
    register(id);
    setDeviceLastSeenAgo(install, RECENT);
    setUserLastSeenAgo(id, RECENT);
    Instant deviceBefore = deviceLastSeen(install);
    Instant userBefore = userLastSeen(id);

    Account account = users.lookup(install);

    assertThat(account).isEqualTo(new Account(id, true));
    assertThat(deviceLastSeen(install)).as("user_device.last_seen_at").isEqualTo(deviceBefore);
    assertThat(userLastSeen(id)).as("app_user.last_seen_at").isEqualTo(userBefore);
  }

  @Test
  @DisplayName("기기·계정 시각이 비어 있으면 lookup 이 둘 다 지금으로 채우고 같은 계정을 돌려준다")
  void lookupFillsNullTimestamps() {
    UUID install = UUID.randomUUID();
    UUID id = track(users.lookup(install)).id();
    clearDeviceLastSeen(install);
    clearUserLastSeen(id);
    Instant before = dbNow();

    Account account = users.lookup(install);

    assertThat(account).isEqualTo(new Account(id, false));
    assertThat(deviceLastSeen(install)).as("user_device.last_seen_at").isAfterOrEqualTo(before);
    assertThat(userLastSeen(id)).as("app_user.last_seen_at").isAfterOrEqualTo(before);
  }

  @Test
  @DisplayName("기기·계정 시각이 2 시간 전이면 lookup 이 둘 다 지금으로 옮기고 같은 계정을 돌려준다")
  void lookupAdvancesStaleTimestamps() {
    UUID install = UUID.randomUUID();
    UUID id = track(users.lookup(install)).id();
    register(id);
    setDeviceLastSeenAgo(install, STALE);
    setUserLastSeenAgo(id, STALE);
    Instant before = dbNow();

    Account account = users.lookup(install);

    assertThat(account).isEqualTo(new Account(id, true));
    assertThat(deviceLastSeen(install)).as("user_device.last_seen_at").isAfterOrEqualTo(before);
    assertThat(userLastSeen(id)).as("app_user.last_seen_at").isAfterOrEqualTo(before);
  }

  @Test
  @DisplayName("처음 보는 설치 UUID 면 지금처럼 계정을 만들고 기기·계정 시각을 함께 남긴다")
  void lookupNewInstallStillCreatesWithTimestamps() {
    UUID install = UUID.randomUUID();
    Instant before = dbNow();

    Account account = track(users.lookup(install));

    assertThat(account.registered()).isFalse();
    assertThat(deviceLastSeen(install)).as("user_device.last_seen_at").isAfterOrEqualTo(before);
    assertThat(userLastSeen(account.id())).as("app_user.last_seen_at").isAfterOrEqualTo(before);
  }

  // ───────────── touchSignedIn ─────────────

  @Test
  @DisplayName("가입한 채 살아 있는 계정 — 시각이 10 분 전이라 기록을 건너뛰어도 true 이고 시각은 그대로다")
  void touchSignedInWithinHourStillTrue() {
    UUID id = track(users.lookup(UUID.randomUUID())).id();
    register(id);
    setUserLastSeenAgo(id, RECENT);
    Instant before = userLastSeen(id);

    assertThat(users.touchSignedIn(id)).isTrue();
    assertThat(userLastSeen(id)).as("app_user.last_seen_at").isEqualTo(before);
  }

  @Test
  @DisplayName("가입한 채 살아 있는 계정 — 시각이 비어 있으면 true 이고 지금으로 채운다")
  void touchSignedInFillsNull() {
    UUID id = track(users.lookup(UUID.randomUUID())).id();
    register(id);
    clearUserLastSeen(id);
    Instant before = dbNow();

    assertThat(users.touchSignedIn(id)).isTrue();
    assertThat(userLastSeen(id)).as("app_user.last_seen_at").isAfterOrEqualTo(before);
  }

  @Test
  @DisplayName("가입한 채 살아 있는 계정 — 시각이 2 시간 전이면 true 이고 지금으로 옮긴다")
  void touchSignedInAdvancesStale() {
    UUID id = track(users.lookup(UUID.randomUUID())).id();
    register(id);
    setUserLastSeenAgo(id, STALE);
    Instant before = dbNow();

    assertThat(users.touchSignedIn(id)).isTrue();
    assertThat(userLastSeen(id)).as("app_user.last_seen_at").isAfterOrEqualTo(before);
  }

  @Test
  @DisplayName("비회원 계정이면 시각이 10 분 전이든 2 시간 전이든 false 이고 행을 건드리지 않는다")
  void touchSignedInGuestFalseAndUntouched() {
    UUID recent = track(users.lookup(UUID.randomUUID())).id();
    UUID stale = track(users.lookup(UUID.randomUUID())).id();
    setUserLastSeenAgo(recent, RECENT);
    setUserLastSeenAgo(stale, STALE);
    Instant recentBefore = userLastSeen(recent);
    Instant staleBefore = userLastSeen(stale);

    assertThat(users.touchSignedIn(recent)).isFalse();
    assertThat(users.touchSignedIn(stale)).isFalse();
    assertThat(userLastSeen(recent)).isEqualTo(recentBefore);
    assertThat(userLastSeen(stale)).isEqualTo(staleBefore);
  }

  @Test
  @DisplayName("합쳐진 계정이면 시각이 10 분 전이든 2 시간 전이든 false 이고 행을 건드리지 않는다")
  void touchSignedInMergedFalseAndUntouched() {
    UUID survivor = track(users.lookup(UUID.randomUUID())).id();
    UUID recent = track(users.lookup(UUID.randomUUID())).id();
    UUID stale = track(users.lookup(UUID.randomUUID())).id();
    register(survivor);
    for (UUID merged : List.of(recent, stale)) {
      register(merged);
      jdbc.sql("UPDATE app_user SET merged_into = CAST(:to AS UUID) WHERE id = CAST(:id AS UUID)")
          .param("to", survivor.toString())
          .param("id", merged.toString())
          .update();
    }
    setUserLastSeenAgo(recent, RECENT);
    setUserLastSeenAgo(stale, STALE);
    Instant recentBefore = userLastSeen(recent);
    Instant staleBefore = userLastSeen(stale);

    assertThat(users.touchSignedIn(recent)).isFalse();
    assertThat(users.touchSignedIn(stale)).isFalse();
    assertThat(userLastSeen(recent)).isEqualTo(recentBefore);
    assertThat(userLastSeen(stale)).isEqualTo(staleBefore);
  }

  @Test
  @DisplayName("없는 계정 id 면 false")
  void touchSignedInUnknownFalse() {
    assertThat(users.touchSignedIn(UUID.randomUUID())).isFalse();
  }

  // ───────────── 도움 ─────────────

  private Account track(Account account) {
    createdUsers.add(account.id());
    return account;
  }

  private static void register(UUID id) {
    jdbc.sql("UPDATE app_user SET registered_at = now() WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .update();
  }

  /** {@code ago} 는 이 클래스의 상수(RECENT·STALE)만 받는다 — PostgreSQL interval 문자열. */
  private static void setUserLastSeenAgo(UUID id, String ago) {
    jdbc.sql(
            "UPDATE app_user SET last_seen_at = now() - CAST(:ago AS INTERVAL)"
                + " WHERE id = CAST(:id AS UUID)")
        .param("ago", ago)
        .param("id", id.toString())
        .update();
  }

  private static void setDeviceLastSeenAgo(UUID install, String ago) {
    jdbc.sql(
            "UPDATE user_device SET last_seen_at = now() - CAST(:ago AS INTERVAL)"
                + " WHERE install_uuid = CAST(:i AS UUID)")
        .param("ago", ago)
        .param("i", install.toString())
        .update();
  }

  private static void clearUserLastSeen(UUID id) {
    jdbc.sql("UPDATE app_user SET last_seen_at = NULL WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .update();
  }

  private static void clearDeviceLastSeen(UUID install) {
    jdbc.sql("UPDATE user_device SET last_seen_at = NULL WHERE install_uuid = CAST(:i AS UUID)")
        .param("i", install.toString())
        .update();
  }

  /** DB 시계의 지금 — 앱과 DB 시계가 어긋나도 비교가 흔들리지 않게 DB 쪽 값으로 잰다. */
  private static Instant dbNow() {
    return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
  }

  private static Instant userLastSeen(UUID id) {
    OffsetDateTime t =
        jdbc.sql("SELECT last_seen_at FROM app_user WHERE id = CAST(:id AS UUID)")
            .param("id", id.toString())
            .query(OffsetDateTime.class)
            .optional()
            .orElse(null);
    return t == null ? null : t.toInstant();
  }

  private static Instant deviceLastSeen(UUID install) {
    OffsetDateTime t =
        jdbc.sql("SELECT last_seen_at FROM user_device WHERE install_uuid = CAST(:i AS UUID)")
            .param("i", install.toString())
            .query(OffsetDateTime.class)
            .optional()
            .orElse(null);
    return t == null ? null : t.toInstant();
  }
}
