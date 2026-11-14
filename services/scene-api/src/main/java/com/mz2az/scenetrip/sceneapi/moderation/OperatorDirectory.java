package com.mz2az.scenetrip.sceneapi.moderation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 운영자인가(MZ2AZ-569, 계획 {@code moderation-operator.md} §1).
 *
 * <p>설정 {@code scenetrip.moderation.operator-email-sha256} 에 둔 해시 목록과 계정에 붙은 로그인 신분의 메일을 견준다. 해시는
 * {@code sha256(lower(trim(email)))} 의 소문자 16진수다 — 메일 원문을 저장소에 적지 않는다(V47 의 팀원 메일과 같은 방식). 목록이 비면
 * 운영자는 없다.
 */
@Component
public class OperatorDirectory {

  private final JdbcClient jdbc;
  private final Set<String> hashes;

  public OperatorDirectory(
      JdbcClient jdbc,
      @Value("${scenetrip.moderation.operator-email-sha256:}") List<String> hashes) {
    this.jdbc = jdbc;
    this.hashes =
        hashes.stream()
            .map(h -> h.strip().toLowerCase(Locale.ROOT))
            .filter(h -> !h.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  /** 계정에 붙은 신분 중 메일이 있는 것 하나라도 목록에 있으면 운영자다. 계정이 없거나 비회원이면 {@code false}. */
  public boolean isOperator(UUID user) {
    if (user == null || hashes.isEmpty()) {
      return false;
    }
    return jdbc
        .sql(
            "SELECT email FROM user_identity WHERE user_id = CAST(:user AS UUID) AND email IS NOT"
                + " NULL")
        .param("user", user.toString())
        .query(String.class)
        .list()
        .stream()
        .anyMatch(email -> hashes.contains(emailSha256(email)));
  }

  /** {@code sha256(lower(trim(email)))} 의 소문자 16진수 64자. */
  public static String emailSha256(String email) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(email.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 이 없습니다", e);
    }
  }
}
