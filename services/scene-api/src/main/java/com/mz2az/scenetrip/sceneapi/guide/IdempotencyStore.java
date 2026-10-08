package com.mz2az.scenetrip.sceneapi.guide;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 가이드 챗봇 턴의 멱등 키(V24 {@code idempotency_key}, 계획 {@code rate-limit.md} §5).
 *
 * <p>판정은 (계정, 키) 를 넣어 보는 것 자체다 — 넣어지면 처음 보는 키, 충돌하면 본 적 있는 키. 먼저 조회하면 동시에 온 두 요청이 모두 「없음」 을 보고 둘 다
 * 처리한다. 처리 중인 채 1 분이 지난 키는 서버가 죽은 것으로 보고 새로 처리를 허락한다(챗봇 응답 상한 40 초). 24 시간 지난 키는 지운다.
 */
@Repository
public class IdempotencyStore {

  /** {@link #begin} 의 결과. */
  public sealed interface Begin {
    /** 처음 보는 키 — 처리하고 {@link #complete} 또는 {@link #abandon} 한다. */
    record Started() implements Begin {}

    /** 이미 끝난 키 — 저장한 응답(JSON)을 그대로 준다. */
    record Replay(String responseJson) implements Begin {}

    /** 같은 키가 아직 처리 중이다 — 409. */
    record InProgress() implements Begin {}

    /** 같은 키에 다른 내용이 왔다 — 422. */
    record Mismatch() implements Begin {}
  }

  private final JdbcClient jdbc;

  public IdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Begin begin(UUID user, String key, String requestHash) {
    jdbc.sql("DELETE FROM idempotency_key WHERE created_at < now() - INTERVAL '24 hours'").update();
    // 처리 중인 채 1 분 넘은 것은 죽은 처리다 — 지우고 새로 시작하게 한다.
    jdbc.sql(
            """
            DELETE FROM idempotency_key
            WHERE user_id = CAST(:user AS UUID) AND key = :key
              AND state = 'processing' AND created_at < now() - INTERVAL '1 minute'
            """)
        .param("user", user.toString())
        .param("key", key)
        .update();
    int inserted =
        jdbc.sql(
                """
                INSERT INTO idempotency_key (user_id, key, request_hash, state)
                VALUES (CAST(:user AS UUID), :key, :hash, 'processing')
                ON CONFLICT (user_id, key) DO NOTHING
                """)
            .param("user", user.toString())
            .param("key", key)
            .param("hash", requestHash)
            .update();
    if (inserted == 1) {
      return new Begin.Started();
    }
    record Row(String hash, String state, String response) {}
    Optional<Row> row =
        jdbc.sql(
                "SELECT request_hash, state, response::TEXT AS response FROM idempotency_key"
                    + " WHERE user_id = CAST(:user AS UUID) AND key = :key")
            .param("user", user.toString())
            .param("key", key)
            .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3)))
            .optional();
    if (row.isEmpty()) {
      // 그 사이 지워졌다(실패한 처리의 abandon) — 다시 시작해 본다.
      return begin(user, key, requestHash);
    }
    if (!row.get().hash().equals(requestHash)) {
      return new Begin.Mismatch();
    }
    if ("completed".equals(row.get().state())) {
      return new Begin.Replay(row.get().response());
    }
    return new Begin.InProgress();
  }

  /** 처리가 끝났다 — 응답을 저장한다. */
  public void complete(UUID user, String key, String responseJson) {
    jdbc.sql(
            """
            UPDATE idempotency_key SET state = 'completed', response = CAST(:response AS JSONB)
            WHERE user_id = CAST(:user AS UUID) AND key = :key
            """)
        .param("user", user.toString())
        .param("key", key)
        .param("response", responseJson)
        .update();
  }

  /** 처리가 실패했다 — 키를 지워 같은 키의 재시도가 다시 처리되게 한다. */
  public void abandon(UUID user, String key) {
    jdbc.sql(
            "DELETE FROM idempotency_key WHERE user_id = CAST(:user AS UUID) AND key = :key"
                + " AND state = 'processing'")
        .param("user", user.toString())
        .param("key", key)
        .update();
  }
}
