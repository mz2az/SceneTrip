package com.mz2az.scenetrip.sceneapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 운영자 판정의 DB 없는 부분 — 계획 {@code moderation-operator.md} §1(MZ2AZ-569). 메일 해시의 모양과 「계정이 없으면 운영자가
 * 아니다」. 실제 {@code user_identity} 를 읽는 판정은 통합 레인({@code OperatorDirectoryIntegrationTest})이 본다.
 */
@DisplayName("OperatorDirectory — 메일 해시와 null 계정")
class OperatorDirectoryTest {

  @Test
  @DisplayName("emailSha256 은 sha256(lower(trim(email))) 의 소문자 16진수 64자다")
  void emailSha256IsLowercaseHexOfNormalizedEmail() throws Exception {
    String expected =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("someone@example.test".getBytes(StandardCharsets.UTF_8)));

    assertThat(OperatorDirectory.emailSha256("someone@example.test")).isEqualTo(expected);
    assertThat(OperatorDirectory.emailSha256("  SomeOne@Example.TEST \t")).isEqualTo(expected);
    assertThat(expected).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("다른 메일은 다른 해시다")
  void differentEmailsDiffer() {
    assertThat(OperatorDirectory.emailSha256("a@example.test"))
        .isNotEqualTo(OperatorDirectory.emailSha256("b@example.test"));
  }

  @Test
  @DisplayName("계정이 null(비회원·토큰 없음)이면 false — 목록에 무엇이 있든")
  void nullUserIsNotOperator() {
    OperatorDirectory directory =
        new OperatorDirectory(
            mock(JdbcClient.class), List.of(OperatorDirectory.emailSha256("op@example.test")));
    assertThat(directory.isOperator(null)).isFalse();
  }
}
