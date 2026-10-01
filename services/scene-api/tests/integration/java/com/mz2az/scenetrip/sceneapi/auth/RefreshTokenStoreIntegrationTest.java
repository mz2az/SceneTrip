package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Reason;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Rejected;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Rotated;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore.Rotation;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link RefreshTokenStore} 를 진짜 PostgreSQL 에 태운다 — 일회용·재사용 감지·폐기를 명세(계획 §4·§6, ADR 0018, 계약의
 * {@code /auth/refresh}) 에 비춰 본다.
 *
 * <p>이 Store 의 핵심 약속 둘은 진짜 DB 에서만 확인된다. 재사용을 잡았을 때 계정의 토큰을 끊는 쓰기가 <b>커밋되어 남는가</b>(트랜잭션이 되돌리면 탈취한 쪽이
 * 계속 쓴다), 그리고 같은 토큰으로 동시에 갱신이 둘 오면 <b>하나만</b> 통과하는가(잠금이 없으면 둘 다 새 토큰을 받는다).
 *
 * <p>매번 새 설치 UUID 로 계정을 만들고 끝나면 지운다. 리프레시 토큰은 {@code ON DELETE CASCADE} 로 함께 사라진다.
 */
@DisplayName("RefreshTokenStore — 실제 DB 질의")
class RefreshTokenStoreIntegrationTest {

  private static final Duration TTL = Duration.ofDays(60);

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;

  // DB 의 timestamptz 는 마이크로초까지라 나노초를 잘라 둔다. 시작점은 실제 시각 근처로 —
  // created_at 은 DB 의 now() 로 채워지므로 시계가 너무 동떨어지면 행이 이상해 보인다.
  private final MutableClock clock =
      new MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
  private final RefreshTokenStore store = new RefreshTokenStore(jdbc, transactions, TTL, clock);
  private final List<UUID> createdUsers = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    for (UUID id : createdUsers) {
      deleteUser(id);
    }
  }

  // ───────────── 교체 ─────────────

  @Test
  @DisplayName("발급한 토큰을 교체하면 같은 계정의 새 토큰이 나온다")
  void rotateGivesNewTokenForSameUser() {
    UUID user = newUser();
    IssuedToken first = store.issue(user, UUID.randomUUID());

    Rotated rotated = rotated(store.rotate(first.value()));

    assertThat(rotated.userId()).isEqualTo(user);
    assertThat(rotated.next().value()).isNotEqualTo(first.value());
  }

  @Test
  @DisplayName("발급과 교체 모두 수명은 설정한 초다 — 교체하면 수명이 새로 시작된다")
  void expiresInIsTtlSeconds() {
    UUID user = newUser();
    IssuedToken first = store.issue(user, null);
    clock.advance(Duration.ofDays(10));

    Rotated rotated = rotated(store.rotate(first.value()));

    assertThat(first.expiresInSeconds()).isEqualTo(TTL.toSeconds());
    assertThat(rotated.next().expiresInSeconds()).isEqualTo(TTL.toSeconds());
  }

  @Test
  @DisplayName("교체한 토큰을 다시 교체할 수 있다 — 사슬이 이어진다")
  void chainContinues() {
    UUID user = newUser();
    IssuedToken first = store.issue(user, null);

    Rotated second = rotated(store.rotate(first.value()));
    Rotated third = rotated(store.rotate(second.next().value()));

    assertThat(third.userId()).isEqualTo(user);
  }

  @Test
  @DisplayName("교체한 토큰은 같은 사슬(family_id)에, 새 로그인은 새 사슬에 든다")
  void familyIds() {
    UUID user = newUser();
    IssuedToken first = store.issue(user, null);
    Rotated next = rotated(store.rotate(first.value()));
    IssuedToken otherLogin = store.issue(user, null);

    assertThat(familyOf(next.next().value())).isEqualTo(familyOf(first.value()));
    assertThat(familyOf(otherLogin.value())).isNotEqualTo(familyOf(first.value()));
  }

  @Test
  @DisplayName("토큰은 32 바이트 난수의 base64url 이다")
  void tokenShape() {
    String raw = store.issue(newUser(), null).value();

    assertThat(raw).matches("[A-Za-z0-9_-]+=*");
    assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
  }

  // ───────────── 재사용 감지 ─────────────

  @Test
  @DisplayName("쓴 토큰이 다시 오면 REUSED, 그 계정의 토큰이 전부 끊긴다 — 다른 로그인까지")
  void reuseRevokesEveryTokenOfUser() {
    UUID user = newUser();
    IssuedToken stolen = store.issue(user, UUID.randomUUID());
    IssuedToken otherLogin = store.issue(user, UUID.randomUUID());
    Rotated legit = rotated(store.rotate(stolen.value()));

    assertThat(store.rotate(stolen.value())).isEqualTo(new Rejected(Reason.REUSED));

    // 같은 사슬의 새 토큰도, 다른 설치본의 로그인도 끊겼다.
    assertThat(store.rotate(legit.next().value())).isEqualTo(new Rejected(Reason.REVOKED));
    assertThat(store.rotate(otherLogin.value())).isEqualTo(new Rejected(Reason.REVOKED));
  }

  @Test
  @DisplayName("재사용 판정으로 끊은 것은 커밋되어 남는다 — 트랜잭션 밖에서 다시 읽어도 revoked_at 이 있다")
  void reuseRevocationPersists() {
    UUID user = newUser();
    IssuedToken first = store.issue(user, null);
    store.issue(user, null);
    store.rotate(first.value());

    store.rotate(first.value());

    // jdbc 는 자동 커밋 접속이다. 끊는 쓰기가 되돌려졌다면 여기서 null 이 보인다.
    long live =
        jdbc.sql(
                "SELECT count(*) FROM refresh_token"
                    + " WHERE user_id = CAST(:u AS UUID) AND revoked_at IS NULL")
            .param("u", user.toString())
            .query(Long.class)
            .single();
    long total =
        jdbc.sql("SELECT count(*) FROM refresh_token WHERE user_id = CAST(:u AS UUID)")
            .param("u", user.toString())
            .query(Long.class)
            .single();
    assertThat(total).isEqualTo(3);
    assertThat(live).isZero();
  }

  @Test
  @DisplayName("재사용 판정은 다른 계정의 토큰을 건드리지 않는다")
  void reuseDoesNotTouchOtherUsers() {
    UUID victim = newUser();
    UUID bystander = newUser();
    IssuedToken first = store.issue(victim, null);
    IssuedToken unrelated = store.issue(bystander, null);
    store.rotate(first.value());

    store.rotate(first.value());

    assertThat(rotated(store.rotate(unrelated.value())).userId()).isEqualTo(bystander);
  }

  // ───────────── 모르는 토큰·만료 ─────────────

  @Test
  @DisplayName("모르는 토큰은 UNKNOWN")
  void unknownToken() {
    String neverIssued = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    assertThat(store.rotate(neverIssued)).isEqualTo(new Rejected(Reason.UNKNOWN));
    assertThat(store.rotate("garbage")).isEqualTo(new Rejected(Reason.UNKNOWN));
  }

  @Test
  @DisplayName("수명이 지난 토큰은 EXPIRED")
  void expiredToken() {
    IssuedToken token = store.issue(newUser(), null);
    clock.advance(TTL.plusSeconds(1));

    assertThat(store.rotate(token.value())).isEqualTo(new Rejected(Reason.EXPIRED));
  }

  @Test
  @DisplayName("수명이 다하기 직전에는 교체된다")
  void validJustBeforeExpiry() {
    UUID user = newUser();
    IssuedToken token = store.issue(user, null);
    clock.advance(TTL.minusSeconds(1));

    assertThat(rotated(store.rotate(token.value())).userId()).isEqualTo(user);
  }

  // ───────────── 로그아웃 — 사슬 하나만 ─────────────

  @Test
  @DisplayName("revokeFamily 는 그 로그인의 사슬만 끊고 계정 id 를 돌려준다 — 다른 로그인은 그대로")
  void revokeFamilyRevokesOnlyThatChain() {
    UUID user = newUser();
    IssuedToken thisInstall = store.issue(user, UUID.randomUUID());
    IssuedToken otherInstall = store.issue(user, UUID.randomUUID());

    assertThat(store.revokeFamily(thisInstall.value())).contains(user);

    assertThat(store.rotate(thisInstall.value())).isEqualTo(new Rejected(Reason.REVOKED));
    assertThat(rotated(store.rotate(otherInstall.value())).userId()).isEqualTo(user);
  }

  @Test
  @DisplayName("사슬의 최신 토큰으로 로그아웃하면 그 사슬이 끊긴다")
  void revokeFamilyWithLatestTokenOfChain() {
    UUID user = newUser();
    IssuedToken first = store.issue(user, null);
    Rotated latest = rotated(store.rotate(first.value()));

    assertThat(store.revokeFamily(latest.next().value())).contains(user);

    assertThat(store.rotate(latest.next().value())).isEqualTo(new Rejected(Reason.REVOKED));
  }

  @Test
  @DisplayName("모르는 토큰으로 revokeFamily 하면 비어 있다")
  void revokeFamilyUnknownToken() {
    String neverIssued = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    assertThat(store.revokeFamily(neverIssued)).isEmpty();
  }

  // ───────────── 전부 끊기 ─────────────

  @Test
  @DisplayName("revokeAll 은 끊은 토큰 수를 돌려주고, 끊긴 토큰은 REVOKED")
  void revokeAllRevokesEverything() {
    UUID user = newUser();
    IssuedToken a = store.issue(user, UUID.randomUUID());
    IssuedToken b = store.issue(user, UUID.randomUUID());

    assertThat(store.revokeAll(user)).isEqualTo(2);

    assertThat(store.rotate(a.value())).isEqualTo(new Rejected(Reason.REVOKED));
    assertThat(store.rotate(b.value())).isEqualTo(new Rejected(Reason.REVOKED));
  }

  @Test
  @DisplayName("이미 끊긴 계정에 revokeAll 을 다시 부르면 0 — 새로 끊은 것이 없다")
  void revokeAllTwiceCountsZero() {
    UUID user = newUser();
    store.issue(user, null);
    store.revokeAll(user);

    assertThat(store.revokeAll(user)).isZero();
  }

  @Test
  @DisplayName("revokeAll 은 다른 계정을 건드리지 않는다")
  void revokeAllIsScopedToUser() {
    UUID user = newUser();
    UUID other = newUser();
    store.issue(user, null);
    IssuedToken othersToken = store.issue(other, null);

    store.revokeAll(user);

    assertThat(rotated(store.rotate(othersToken.value())).userId()).isEqualTo(other);
  }

  // ───────────── 원문을 저장하지 않는다 ─────────────

  @Test
  @DisplayName("원문은 저장하지 않고 SHA-256 만 둔다")
  void storesOnlyHash() {
    UUID user = newUser();
    IssuedToken token = store.issue(user, null);
    byte[] rawBytes = token.value().getBytes(StandardCharsets.UTF_8);

    List<byte[]> hashes =
        jdbc.sql("SELECT token_hash FROM refresh_token WHERE user_id = CAST(:u AS UUID)")
            .param("u", user.toString())
            .query(byte[].class)
            .list();

    assertThat(hashes).hasSize(1);
    assertThat(hashes.get(0)).isNotEqualTo(rawBytes).isEqualTo(sha256(rawBytes));
    // 표 어디에도 원문이 없다 — 해시 열만이 아니라 다른 열에 실려 있지 않은지도 본다.
    long rawAnywhere =
        jdbc.sql(
                "SELECT count(*) FROM refresh_token r"
                    + " WHERE r.user_id = CAST(:u AS UUID)"
                    + " AND (r.token_hash = :raw OR position(:rawText IN r::text) > 0)")
            .param("u", user.toString())
            .param("raw", rawBytes)
            .param("rawText", token.value())
            .query(Long.class)
            .single();
    assertThat(rawAnywhere).isZero();
  }

  // ───────────── 설치 UUID ─────────────

  @Test
  @DisplayName("설치 UUID 없이도 발급·교체된다")
  void nullInstallUuid() {
    UUID user = newUser();
    IssuedToken token = store.issue(user, null);

    assertThat(rotated(store.rotate(token.value())).userId()).isEqualTo(user);
  }

  @Test
  @DisplayName("설치 UUID 는 기록으로 남는다")
  void installUuidIsRecorded() {
    UUID user = newUser();
    UUID install = UUID.randomUUID();
    IssuedToken token = store.issue(user, install);

    UUID stored =
        jdbc.sql("SELECT install_uuid FROM refresh_token WHERE token_hash = :h")
            .param("h", sha256(token.value().getBytes(StandardCharsets.UTF_8)))
            .query(UUID.class)
            .single();
    assertThat(stored).isEqualTo(install);
  }

  // ───────────── 동시성 ─────────────

  @Test
  @DisplayName("같은 토큰으로 동시에 두 번 교체하면 하나는 Rotated, 하나는 REUSED")
  void concurrentRotateIsOneTimeUse() throws Exception {
    // 한 번은 운으로 통과할 수 있어 여러 번 돌린다.
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 10; round++) {
        UUID user = newUser();
        String raw = store.issue(user, null).value();
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Rotation> attempt =
            () -> {
              start.await(10, TimeUnit.SECONDS);
              return store.rotate(raw);
            };

        Future<Rotation> f1 = pool.submit(attempt);
        Future<Rotation> f2 = pool.submit(attempt);
        List<Rotation> results =
            List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));

        assertThat(results).as("round %d", round).filteredOn(Rotated.class::isInstance).hasSize(1);
        assertThat(results)
            .as("round %d", round)
            .filteredOn(r -> r.equals(new Rejected(Reason.REUSED)))
            .hasSize(1);

        // 재사용 판정이 계정의 토큰을 전부 끊었으므로 이긴 쪽이 받은 새 토큰도 끊겼다.
        Rotated winner =
            (Rotated) results.stream().filter(Rotated.class::isInstance).findFirst().orElseThrow();
        assertThat(store.rotate(winner.next().value()))
            .as("round %d", round)
            .isEqualTo(new Rejected(Reason.REVOKED));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  // ───────────── 탈퇴 ─────────────

  @Test
  @DisplayName("계정 행을 지우면 그 리프레시 토큰도 사라진다 — CASCADE")
  void deletingUserCascades() {
    UUID user = newUser();
    IssuedToken token = store.issue(user, null);
    store.issue(user, null);

    deleteUser(user);

    long rows =
        jdbc.sql("SELECT count(*) FROM refresh_token WHERE user_id = CAST(:u AS UUID)")
            .param("u", user.toString())
            .query(Long.class)
            .single();
    assertThat(rows).isZero();
    assertThat(store.rotate(token.value())).isEqualTo(new Rejected(Reason.UNKNOWN));
  }

  // ───────────── 도우미 ─────────────

  private UUID newUser() {
    UUID id = users.resolve(UUID.randomUUID());
    createdUsers.add(id);
    return id;
  }

  private static void deleteUser(UUID id) {
    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .update();
  }

  private static Rotated rotated(Rotation rotation) {
    assertThat(rotation).isInstanceOf(Rotated.class);
    return (Rotated) rotation;
  }

  private static UUID familyOf(String raw) {
    return jdbc.sql("SELECT family_id FROM refresh_token WHERE token_hash = :h")
        .param("h", sha256(raw.getBytes(StandardCharsets.UTF_8)))
        .query(UUID.class)
        .single();
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 테스트가 시간을 앞으로 돌리는 시계. 동시성 테스트가 두 스레드에서 읽으므로 volatile. */
  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
