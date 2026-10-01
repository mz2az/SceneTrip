package com.mz2az.scenetrip.sceneapi.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일회용 리프레시 토큰 — 발급·교체·폐기 (ADR 0018, 표는 V15).
 *
 * <p><b>원문을 저장하지 않는다.</b> 앱에 준 난수의 SHA-256 만 둔다. 표가 새어도 그 값으로는 갱신할 수 없다.
 *
 * <p><b>쓸 때마다 바꾼다.</b> {@link #rotate} 는 받은 토큰에 사용 표시를 남기고 같은 사슬({@code family_id})로 새 토큰을 준다. 이미 쓴
 * 것이 다시 오면 재사용 — 탈취로 보아 그 계정의 토큰을 <b>전부</b> 끊는다. 도둑과 주인 중 누가 먼저 썼는지 서버는 모르기 때문이다. 앱이 동시에 두 번 갱신해도
 * 같은 판정이 나므로 계약이 「갱신은 동시에 하나만」 을 앱에 요구한다.
 *
 * <p>시각은 {@link Clock} 을 따른다. DB 의 {@code now()} 와 섞으면 만료 판정과 기록이 다른 시계를 보게 되고, 테스트가 만료를 재현할 수 없다.
 */
@Repository
public class RefreshTokenStore {

  /** 256 비트. 무차별 대입이 의미가 없는 크기라 해시에 솔트를 두지 않는다. */
  private static final int TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  private static final String INSERT_SQL =
      """
      INSERT INTO refresh_token (id, token_hash, user_id, family_id, install_uuid, expires_at, created_at)
      VALUES (CAST(:id AS UUID), :hash, CAST(:userId AS UUID), CAST(:familyId AS UUID),
              CAST(:installUuid AS UUID), :expiresAt, :now)
      """;

  /**
   * 받은 토큰을 잠그며 읽는다.
   *
   * <p>{@code FOR UPDATE} 가 핵심이다. 같은 토큰으로 갱신이 동시에 둘 오면 둘째는 첫째가 끝날 때까지 기다렸다가 사용 표시를 본다 — 재사용으로 판정된다.
   * 잠그지 않으면 둘 다 「아직 안 썼다」 를 읽고 각자 새 토큰을 받아, 일회용이 깨진다.
   */
  private static final String LOCK_SQL =
      """
      SELECT user_id, family_id, install_uuid, expires_at, used_at, revoked_at
      FROM refresh_token
      WHERE token_hash = :hash
      FOR UPDATE
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Duration ttl;
  private final Clock clock;

  @Autowired
  public RefreshTokenStore(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      @Value("${scenetrip.auth.refresh-token-ttl}") Duration ttl) {
    this(jdbc, transactions, ttl, Clock.systemUTC());
  }

  /** 테스트와 다른 패키지가 직접 만든다. 시계를 바꿔 만료를 재현한다. */
  public RefreshTokenStore(
      JdbcClient jdbc, TransactionTemplate transactions, Duration ttl, Clock clock) {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("리프레시 토큰 수명은 양수여야 합니다: " + ttl);
    }
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * 새 로그인 — 새 사슬의 첫 토큰을 만든다.
   *
   * @param installUuid 어느 설치본의 로그인인가. 기록용이며 판정에는 쓰지 않는다. 없을 수 있다.
   */
  public IssuedToken issue(UUID userId, UUID installUuid) {
    return insert(userId, UUID.randomUUID(), installUuid);
  }

  /**
   * 받은 토큰을 쓰고 새 토큰으로 바꾼다.
   *
   * <p>거절은 예외가 아니라 {@link Rejected} 로 돌려준다. 재사용을 잡으면 계정의 토큰을 끊는 쓰기가 <b>남아야</b> 하는데, 트랜잭션 안에서 예외를
   * 던지면 그 쓰기까지 되돌아간다.
   */
  public Rotation rotate(String rawToken) {
    byte[] hash = hash(rawToken);
    return transactions.execute(status -> rotateLocked(hash));
  }

  private Rotation rotateLocked(byte[] hash) {
    Optional<Row> found =
        jdbc.sql(LOCK_SQL)
            .param("hash", hash)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        rs.getObject("install_uuid", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("used_at", OffsetDateTime.class) != null,
                        rs.getObject("revoked_at", OffsetDateTime.class) != null))
            .optional();
    if (found.isEmpty()) {
      return new Rejected(Reason.UNKNOWN);
    }
    Row row = found.get();
    Instant now = clock.instant();

    // 순서가 뜻을 가진다. 폐기된 사슬(로그아웃·탈퇴·앞선 재사용 판정)의 토큰은 쓴 것이든 아니든
    // 「폐기됨」 이다 — 이미 끊긴 것을 다시 재사용으로 판정해 남은 다른 설치본까지 끊을 이유가 없다.
    if (row.revoked()) {
      return new Rejected(Reason.REVOKED);
    }
    if (row.used()) {
      revokeAllAt(row.userId(), now);
      return new Rejected(Reason.REUSED);
    }
    if (!now.isBefore(row.expiresAt())) {
      return new Rejected(Reason.EXPIRED);
    }

    jdbc.sql("UPDATE refresh_token SET used_at = :now WHERE token_hash = :hash")
        .param("now", at(now))
        .param("hash", hash)
        .update();
    IssuedToken next = insert(row.userId(), row.familyId(), row.installUuid());
    return new Rotated(row.userId(), next);
  }

  /**
   * 로그아웃 — 받은 토큰의 사슬을 끊는다. 다른 설치본의 로그인은 그대로다.
   *
   * @return 끊은 사슬의 계정. 모르는 토큰이면 비어 있다(계약상 로그아웃은 그래도 {@code 204} 다)
   */
  public Optional<UUID> revokeFamily(String rawToken) {
    byte[] hash = hash(rawToken);
    Instant now = clock.instant();
    return transactions.execute(
        status -> {
          Optional<FamilyOwner> owner =
              jdbc.sql("SELECT user_id, family_id FROM refresh_token WHERE token_hash = :hash")
                  .param("hash", hash)
                  .query(
                      (rs, n) ->
                          new FamilyOwner(
                              rs.getObject("user_id", UUID.class),
                              rs.getObject("family_id", UUID.class)))
                  .optional();
          owner.ifPresent(
              o ->
                  jdbc.sql(
                          "UPDATE refresh_token SET revoked_at = :now"
                              + " WHERE family_id = CAST(:familyId AS UUID) AND revoked_at IS NULL")
                      .param("now", at(now))
                      .param("familyId", o.familyId().toString())
                      .update());
          return owner.map(FamilyOwner::userId);
        });
  }

  /**
   * 이 계정의 리프레시 토큰을 전부 끊는다 — 재사용 판정과 같은 처리를 밖에서 부를 때.
   *
   * <p>탈퇴는 이것을 부르지 않아도 된다. 계정 행이 지워지면 {@code ON DELETE CASCADE} 로 함께 사라진다.
   *
   * @return 끊은 토큰 수
   */
  public int revokeAll(UUID userId) {
    return revokeAllAt(userId, clock.instant());
  }

  private int revokeAllAt(UUID userId, Instant now) {
    return jdbc.sql(
            "UPDATE refresh_token SET revoked_at = :now"
                + " WHERE user_id = CAST(:userId AS UUID) AND revoked_at IS NULL")
        .param("now", at(now))
        .param("userId", userId.toString())
        .update();
  }

  private IssuedToken insert(UUID userId, UUID familyId, UUID installUuid) {
    String raw = newRawToken();
    Instant now = clock.instant();
    jdbc.sql(INSERT_SQL)
        .param("id", UUID.randomUUID().toString())
        .param("hash", hash(raw))
        .param("userId", userId.toString())
        .param("familyId", familyId.toString())
        .param("installUuid", installUuid == null ? null : installUuid.toString())
        .param("expiresAt", at(now.plus(ttl)))
        .param("now", at(now))
        .update();
    return new IssuedToken(raw, ttl.toSeconds());
  }

  private static String newRawToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    // URL·헤더·JSON 어디에 실어도 이스케이프가 필요 없는 문자만 쓴다.
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** 원문의 SHA-256. 원문은 이 메서드 밖으로 나가지 않는다. */
  static byte[] hash(String rawToken) {
    if (rawToken == null) {
      rawToken = "";
    }
    try {
      return MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // 모든 JDK 가 SHA-256 을 갖고 있어야 한다(자바 명세). 없으면 실행 환경이 깨진 것이다.
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  private static OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** {@link #rotate} 의 결과 — 바뀌었거나 거절됐다. */
  public sealed interface Rotation permits Rotated, Rejected {}

  /**
   * 바뀌었다. 받은 토큰은 이제 쓴 것이다.
   *
   * @param userId 그 토큰의 계정 — 새 액세스 토큰을 이 계정으로 발급한다
   * @param next 앱에 내려줄 새 리프레시 토큰
   */
  public record Rotated(UUID userId, IssuedToken next) implements Rotation {}

  /** 거절됐다. 앱이 할 일은 이유와 상관없이 같다 — 토큰을 지우고 다시 로그인한다({@code 401 REFRESH_TOKEN_INVALID}). */
  public record Rejected(Reason reason) implements Rotation {}

  /** 거절 이유. 응답 코드는 하나로 같고, 이것은 로그와 지표를 위한 것이다. */
  public enum Reason {
    /** 모르는 토큰 */
    UNKNOWN,
    /** 만료 */
    EXPIRED,
    /** 사슬이 끊겼다 — 로그아웃·탈퇴·앞선 재사용 판정 */
    REVOKED,
    /** 이미 쓴 토큰이 다시 왔다. 이 판정으로 그 계정의 토큰이 전부 끊겼다 */
    REUSED
  }

  private record Row(
      UUID userId,
      UUID familyId,
      UUID installUuid,
      Instant expiresAt,
      boolean used,
      boolean revoked) {}

  private record FamilyOwner(UUID userId, UUID familyId) {}
}
