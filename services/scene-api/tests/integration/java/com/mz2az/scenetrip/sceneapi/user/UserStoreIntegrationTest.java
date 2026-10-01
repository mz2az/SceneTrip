package com.mz2az.scenetrip.sceneapi.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.user.UserStore.Account;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link UserStore#lookup} 와 {@link UserStore#touchSignedIn} 을 진짜 PostgreSQL 에 태운다 — 요청의 계정을 정하는 두
 * 질의다(계획 §3, 계약 「인증」 절).
 *
 * <p>둘 다 판정이 SQL 안에 있다(가입 여부, 합쳐짐·탈퇴). 단위 테스트는 이 Store 를 가짜로 바꿔 끼우므로 그 판정이 틀려도 모른다 — 가입 계정이 설치 UUID
 * 만으로 열리거나, 탈퇴한 계정의 토큰이 계속 통하는 결함이 이 레인에서만 보인다.
 *
 * <p>매번 새 설치 UUID 로 계정을 만들고 끝나면 지운다.
 */
@DisplayName("UserStore — 계정 결정 질의 (실제 DB)")
class UserStoreIntegrationTest {

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
      deleteUser(id);
    }
  }

  // ───────────── lookup ─────────────

  @Test
  @DisplayName("처음 보는 설치 UUID 면 비회원 계정을 만들어 registered=false 로 돌려준다")
  void lookupCreatesGuest() {
    UUID install = UUID.randomUUID();

    Account account = track(users.lookup(install));

    assertThat(account.id()).isNotNull().isNotEqualTo(install);
    assertThat(account.registered()).isFalse();
    assertThat(deviceOwner(install)).contains(account.id());
    assertThat(registeredAt(account.id())).isNull();
  }

  @Test
  @DisplayName("같은 설치 UUID 는 같은 계정이다 — 두 번째에는 새로 만들지 않는다")
  void lookupIsStable() {
    UUID install = UUID.randomUUID();

    Account first = track(users.lookup(install));
    Account second = users.lookup(install);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(users.resolve(install)).isEqualTo(first.id());
  }

  @Test
  @DisplayName("resolve 로 만든 계정을 lookup 이 같은 id 로 찾는다")
  void lookupFindsAccountMadeByResolve() {
    UUID install = UUID.randomUUID();
    UUID id = users.resolve(install);
    createdUsers.add(id);

    assertThat(users.lookup(install)).isEqualTo(new Account(id, false));
  }

  @Test
  @DisplayName("registered_at 이 채워지면 lookup 이 registered=true 라고 말한다")
  void lookupReportsRegistered() {
    UUID install = UUID.randomUUID();
    UUID id = track(users.lookup(install)).id();
    register(id);

    Account account = users.lookup(install);

    assertThat(account).isEqualTo(new Account(id, true));
  }

  @Test
  @DisplayName("가입 판정을 끈 Store(로컬 우회)에서도 lookup 의 registered 는 DB 그대로다")
  void lookupIgnoresRegistrationBypass() {
    // 우회는 마켓·길찾기 벽(SIGN_IN_REQUIRED)만 치운다. SESSION_REQUIRED 까지 꺼지면
    // 로컬에서 가입 계정이 설치 UUID 하나로 열린다.
    UserStore bypassed = new UserStore(jdbc, false);
    UUID guestInstall = UUID.randomUUID();
    UUID memberInstall = UUID.randomUUID();
    UUID guest = track(bypassed.lookup(guestInstall)).id();
    UUID member = track(bypassed.lookup(memberInstall)).id();
    register(member);

    assertThat(bypassed.lookup(guestInstall)).isEqualTo(new Account(guest, false));
    assertThat(bypassed.lookup(memberInstall)).isEqualTo(new Account(member, true));
    // 대조군 — 같은 Store 의 isRegistered 는 우회를 따른다. 그래서 둘을 가르는 것이 뜻이 있다.
    assertThat(bypassed.isRegistered(guest)).isTrue();
  }

  // ───────────── touchSignedIn ─────────────

  @Test
  @DisplayName("가입한 채 살아 있는 계정이면 true 이고 last_seen_at 을 남긴다")
  void touchSignedInLiveAccount() {
    UUID id = track(users.lookup(UUID.randomUUID())).id();
    register(id);
    jdbc.sql("UPDATE app_user SET last_seen_at = NULL WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .update();

    assertThat(users.touchSignedIn(id)).isTrue();
    assertThat(lastSeenAt(id)).isNotNull();
  }

  @Test
  @DisplayName("touchSignedIn 은 last_seen_at 을 앞으로 옮긴다")
  void touchSignedInAdvancesLastSeen() {
    UUID id = track(users.lookup(UUID.randomUUID())).id();
    register(id);
    OffsetDateTime old = OffsetDateTime.parse("2020-01-01T00:00:00Z");
    jdbc.sql("UPDATE app_user SET last_seen_at = :t WHERE id = CAST(:id AS UUID)")
        .param("t", old)
        .param("id", id.toString())
        .update();

    users.touchSignedIn(id);

    assertThat(lastSeenAt(id)).isAfter(old);
  }

  @Test
  @DisplayName("없는 계정 id 면 false")
  void touchSignedInUnknown() {
    assertThat(users.touchSignedIn(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("가입하지 않은 계정이면 false")
  void touchSignedInGuest() {
    UUID id = track(users.lookup(UUID.randomUUID())).id();

    assertThat(users.touchSignedIn(id)).isFalse();
  }

  @Test
  @DisplayName("다른 계정으로 합쳐진 계정이면 가입 표시가 남아 있어도 false")
  void touchSignedInMerged() {
    UUID survivor = track(users.lookup(UUID.randomUUID())).id();
    UUID merged = track(users.lookup(UUID.randomUUID())).id();
    register(survivor);
    register(merged);
    jdbc.sql("UPDATE app_user SET merged_into = CAST(:to AS UUID) WHERE id = CAST(:id AS UUID)")
        .param("to", survivor.toString())
        .param("id", merged.toString())
        .update();

    assertThat(users.touchSignedIn(merged)).isFalse();
    assertThat(users.touchSignedIn(survivor)).isTrue();
  }

  @Test
  @DisplayName("행이 지워진(탈퇴) 계정이면 false")
  void touchSignedInDeleted() {
    UUID id = track(users.lookup(UUID.randomUUID())).id();
    register(id);
    assertThat(users.touchSignedIn(id)).isTrue();

    deleteUser(id);

    assertThat(users.touchSignedIn(id)).isFalse();
  }

  @Test
  @DisplayName("가입 판정을 끈 Store 에서도 touchSignedIn 은 비회원에게 false")
  void touchSignedInIgnoresRegistrationBypass() {
    // 우회가 이 판정까지 끄면 아무 계정 id 로 서명된 토큰이 비회원 계정을 연다.
    UserStore bypassed = new UserStore(jdbc, false);
    UUID id = track(bypassed.lookup(UUID.randomUUID())).id();

    assertThat(bypassed.touchSignedIn(id)).isFalse();
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

  private static void deleteUser(UUID id) {
    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .update();
  }

  private static Optional<UUID> deviceOwner(UUID install) {
    return jdbc.sql("SELECT user_id FROM user_device WHERE install_uuid = CAST(:i AS UUID)")
        .param("i", install.toString())
        .query(UUID.class)
        .optional();
  }

  private static OffsetDateTime registeredAt(UUID id) {
    return jdbc.sql("SELECT registered_at FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(OffsetDateTime.class)
        .optional()
        .orElse(null);
  }

  private static OffsetDateTime lastSeenAt(UUID id) {
    return jdbc.sql("SELECT last_seen_at FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(OffsetDateTime.class)
        .optional()
        .orElse(null);
  }
}
