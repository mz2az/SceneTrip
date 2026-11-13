package com.mz2az.scenetrip.sceneapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 운영자 판정(계획 {@code moderation-operator.md} §1, MZ2AZ-569)을 실제 {@code user_identity} 위에서 본다 — 그 계정의
 * 로그인 신분 메일 중 하나라도 {@code sha256(lower(trim(email)))} 가 설정 목록에 있으면 운영자다.
 *
 * <p>진짜 메일은 이 파일에 없다. 가짜 주소의 해시만 목록에 넣는다. 시험마다 한 트랜잭션 안에서 넣고 읽고 되돌린다({@link
 * IntegrationDatabase#rolledBack}).
 */
@DisplayName("OperatorDirectory — 운영자 판정 (실제 DB)")
class OperatorDirectoryIntegrationTest {

  private static final String OPERATOR_EMAIL = "op.erator@example.test";

  private static JdbcClient jdbc;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  private static OperatorDirectory directory(String... emails) {
    return new OperatorDirectory(
        jdbc, java.util.Arrays.stream(emails).map(OperatorDirectory::emailSha256).toList());
  }

  @Test
  @DisplayName("신분 메일의 해시가 목록에 있으면 true — 저장된 메일의 대소문자·앞뒤 공백은 무시한다")
  void matchingIdentityIsOperator() {
    boolean result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID user = newUser();
              identity("google", user, " Op.Erator@Example.TEST ");
              return directory(OPERATOR_EMAIL).isOperator(user);
            });
    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("신분이 둘이면 하나만 맞아도 true — 애플 대리 주소 + 구글 운영자 메일")
  void anyIdentityMatches() {
    boolean result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID user = newUser();
              identity("apple", user, "abc@privaterelay.appleid.com");
              identity("google", user, OPERATOR_EMAIL);
              return directory(OPERATOR_EMAIL).isOperator(user);
            });
    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("목록에 여러 해시가 있어도 그중 하나와 맞으면 true")
  void listOfSeveralHashes() {
    boolean result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID user = newUser();
              identity("google", user, OPERATOR_EMAIL);
              return directory("someone.else@example.test", OPERATOR_EMAIL).isOperator(user);
            });
    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("다른 메일의 계정은 false")
  void otherEmailIsNotOperator() {
    boolean result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID user = newUser();
              identity("google", user, "member@example.test");
              return directory(OPERATOR_EMAIL).isOperator(user);
            });
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("메일이 비어 있는 신분뿐이면 false")
  void identityWithoutEmailIsNotOperator() {
    boolean result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID user = newUser();
              identity("apple", user, null);
              return directory(OPERATOR_EMAIL).isOperator(user);
            });
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("신분이 없는 계정(비회원)·없는 계정은 false")
  void noIdentityOrNoAccountIsNotOperator() {
    List<Boolean> result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID guest = newUser();
              OperatorDirectory d = directory(OPERATOR_EMAIL);
              return List.of(d.isOperator(guest), d.isOperator(UUID.randomUUID()));
            });
    assertThat(result).containsExactly(false, false);
  }

  @Test
  @DisplayName("목록이 비어 있으면 운영자는 없다 — 메일이 맞아도 false")
  void emptyListMeansNoOperator() {
    boolean result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID user = newUser();
              identity("google", user, OPERATOR_EMAIL);
              return new OperatorDirectory(jdbc, List.of()).isOperator(user);
            });
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("다른 계정의 신분 메일이 맞아도 이 계정은 false — 계정별로 본다")
  void otherAccountsIdentityDoesNotCount() {
    boolean result =
        IntegrationDatabase.rolledBack(
            () -> {
              UUID operator = newUser();
              identity("google", operator, OPERATOR_EMAIL);
              UUID member = newUser();
              identity("google", member, "member@example.test");
              return directory(OPERATOR_EMAIL).isOperator(member);
            });
    assertThat(result).isFalse();
  }

  private static UUID newUser() {
    return jdbc.sql("INSERT INTO app_user (id) VALUES (gen_random_uuid()) RETURNING id")
        .query(UUID.class)
        .single();
  }

  private static void identity(String provider, UUID userId, String email) {
    jdbc.sql(
            "INSERT INTO user_identity (provider, subject, user_id, email)"
                + " VALUES (:p, 'operator-it-' || gen_random_uuid(), :u, :e)")
        .param("p", provider)
        .param("u", userId)
        .param("e", email)
        .update();
  }
}
