package com.mz2az.scenetrip.sceneapi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.auth.SignInService.SignedIn;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 애플 refresh token 암호문의 저장을 <b>진짜 PostgreSQL</b> 위에서 명세(V16 {@code
 * user_identity.apple_refresh_token_enc}, 계획 §4 「애플로 로그인할 때마다 새 토큰으로 덮어쓴다」, {@link SocialIdentity}
 * 설명 「구글은 null」, 계약 {@code AppleSignIn.givenName} 「두 번째부터는 비운다」)에 비춰 본다.
 *
 * <p>암호문은 이 자리에서 지은 바이트다 — 저장 계층은 그것을 풀지 않는다.
 */
@DisplayName("애플 refresh token 암호문 저장 — SignInService · AccountLinkStore (실제 DB)")
class AppleIdentityStorageIntegrationTest {

  private static JdbcClient jdbc;
  private static UserStore users;
  private static AccountLinkStore links;
  private static SignInService service;

  private final Set<UUID> createdUsers = new LinkedHashSet<>();
  private final Set<UUID> installs = new LinkedHashSet<>();
  private final Set<String> subjects = new LinkedHashSet<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    TransactionTemplate transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
    RefreshTokenStore refreshTokens =
        new RefreshTokenStore(jdbc, transactions, Duration.ofDays(60), Clock.systemUTC());
    service = new SignInService(users, links, refreshTokens, transactions);
  }

  @AfterEach
  void cleanUp() {
    Set<UUID> all = new LinkedHashSet<>(createdUsers);
    for (UUID install : installs) {
      jdbc.sql("SELECT user_id FROM user_device WHERE install_uuid = CAST(:i AS UUID)")
          .param("i", install.toString())
          .query(UUID.class)
          .optional()
          .ifPresent(all::add);
    }
    for (String subject : subjects) {
      jdbc.sql("SELECT user_id FROM user_identity WHERE subject = :s")
          .param("s", subject)
          .query(UUID.class)
          .list()
          .forEach(all::add);
    }
    for (UUID id : all) {
      jdbc.sql(
              "UPDATE app_user SET merged_into = NULL"
                  + " WHERE id = CAST(:id AS UUID) OR merged_into = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
    for (UUID id : all) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  @Test
  @DisplayName("V16 — user_identity.apple_refresh_token_enc 가 bytea 로 있다")
  void columnExists() {
    String type =
        jdbc.sql(
                "SELECT data_type FROM information_schema.columns WHERE table_name ="
                    + " 'user_identity' AND column_name = 'apple_refresh_token_enc'")
            .query(String.class)
            .single();

    assertThat(type).isEqualTo("bytea");
  }

  @Test
  @DisplayName("애플로 처음 가입하면 암호문이 저장되고 appleRefreshTokenEnc 가 그것을 돌려준다")
  void signUpStoresCipherText() {
    UUID install = newInstall();
    String subject = newSubject();
    byte[] first = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};

    SignedIn result = service.signIn(apple(subject, "김철수", first), install);

    assertThat(result.isNewUser()).isTrue();
    assertThat(storedCipherText(subject)).isEqualTo(first);
    assertThat(links.appleRefreshTokenEnc(result.userId()))
        .hasValueSatisfying(b -> assertThat(b).isEqualTo(first));
  }

  @Test
  @DisplayName("같은 설치본에서 다시 로그인하며 새 암호문을 주면 덮어쓴다")
  void laterSignInOverwrites() {
    UUID install = newInstall();
    String subject = newSubject();
    byte[] first = {1, 1, 1};
    byte[] second = {2, 2, 2, 2};
    SignedIn signedUp = service.signIn(apple(subject, "김철수", first), install);

    SignedIn again = service.signIn(apple(subject, null, second), install);

    assertThat(again.userId()).isEqualTo(signedUp.userId());
    assertThat(storedCipherText(subject)).isEqualTo(second);
    assertThat(links.appleRefreshTokenEnc(signedUp.userId()))
        .hasValueSatisfying(b -> assertThat(b).isEqualTo(second));
  }

  @Test
  @DisplayName("다른 설치본에서 로그인(합치기)하며 새 암호문을 주어도 덮어쓴다")
  void mergeSignInOverwrites() {
    String subject = newSubject();
    byte[] first = {3, 3, 3};
    byte[] second = {4, 4, 4};
    SignedIn signedUp = service.signIn(apple(subject, "김철수", first), newInstall());

    SignedIn merged = service.signIn(apple(subject, null, second), newInstall());

    assertThat(merged.merged()).isTrue();
    assertThat(merged.userId()).isEqualTo(signedUp.userId());
    assertThat(storedCipherText(subject)).isEqualTo(second);
  }

  @Test
  @DisplayName("다시 로그인하며 암호문이 null 이면 저장된 것을 지우지 않는다")
  void nullCipherTextKeepsStored() {
    UUID install = newInstall();
    String subject = newSubject();
    byte[] first = {5, 5, 5};
    SignedIn signedUp = service.signIn(apple(subject, "김철수", first), install);

    service.signIn(apple(subject, null, null), install);
    service.signIn(apple(subject, null, null), newInstall());

    assertThat(storedCipherText(subject)).isEqualTo(first);
    assertThat(links.appleRefreshTokenEnc(signedUp.userId()))
        .hasValueSatisfying(b -> assertThat(b).isEqualTo(first));
  }

  @Test
  @DisplayName("애플이 첫 로그인에만 준 이름은 이름 없이 다시 로그인해도 남는다")
  void firstLoginNamePersists() {
    UUID install = newInstall();
    String subject = newSubject();
    SignedIn signedUp = service.signIn(apple(subject, "김철수", new byte[] {6}), install);

    service.signIn(apple(subject, null, new byte[] {7}), install);
    service.signIn(apple(subject, null, new byte[] {8}), newInstall());

    assertThat(displayNameOf(subject)).isEqualTo("김철수");
    assertThat(users.profile(signedUp.userId()))
        .hasValueSatisfying(
            p ->
                assertThat(p.identities())
                    .anySatisfy(
                        i -> {
                          assertThat(i.provider()).isEqualTo("apple");
                          assertThat(i.displayName()).isEqualTo("김철수");
                        }));
  }

  @Test
  @DisplayName("구글로 가입한 계정은 칸이 비어 있고 appleRefreshTokenEnc 는 비어 있다")
  void googleNeverSetsIt() {
    UUID install = newInstall();
    String subject = newSubject();

    SignedIn result =
        service.signIn(new SocialIdentity("google", subject, "g@example.com", "구글"), install);

    assertThat(storedCipherText(subject)).isNull();
    assertThat(links.appleRefreshTokenEnc(result.userId())).isEmpty();
  }

  @Test
  @DisplayName("구글 신분이 (잘못) 암호문을 들고 와도 저장하지 않는다")
  void googleWithCipherTextStillNotStored() {
    UUID install = newInstall();
    String subject = newSubject();

    SignedIn result =
        service.signIn(
            new SocialIdentity("google", subject, "g@example.com", "구글", new byte[] {9, 9}),
            install);

    assertThat(storedCipherText(subject)).isNull();
    assertThat(links.appleRefreshTokenEnc(result.userId())).isEmpty();
  }

  @Test
  @DisplayName("신분이 없는 계정(비회원)은 appleRefreshTokenEnc 가 비어 있다")
  void guestHasNone() {
    UUID guest = users.resolve(newInstall());
    createdUsers.add(guest);

    assertThat(links.appleRefreshTokenEnc(guest)).isEmpty();
    assertThat(links.appleRefreshTokenEnc(UUID.randomUUID())).isEqualTo(Optional.empty());
  }

  // ───────────── 도우미 ─────────────

  private static SocialIdentity apple(String subject, String name, byte[] cipherText) {
    return new SocialIdentity("apple", subject, "x@privaterelay.appleid.com", name, cipherText);
  }

  private String newSubject() {
    String subject = "apple-it-" + UUID.randomUUID();
    subjects.add(subject);
    return subject;
  }

  private UUID newInstall() {
    UUID install = UUID.randomUUID();
    installs.add(install);
    return install;
  }

  private static byte[] storedCipherText(String subject) {
    return jdbc.sql("SELECT apple_refresh_token_enc FROM user_identity WHERE subject = :s")
        .param("s", subject)
        .query((rs, n) -> Optional.ofNullable(rs.getBytes(1)))
        .single()
        // single() 은 null 을 받지 않는다 — 칸이 NULL 인 행도 「행은 하나」 로 읽으려고 Optional 로 감쌌다.
        .orElse(null);
  }

  private static String displayNameOf(String subject) {
    return jdbc.sql("SELECT display_name FROM user_identity WHERE subject = :s")
        .param("s", subject)
        .query(String.class)
        .single();
  }
}
