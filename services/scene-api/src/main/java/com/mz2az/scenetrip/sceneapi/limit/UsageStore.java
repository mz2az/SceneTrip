package com.mz2az.scenetrip.sceneapi.limit;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 유료 API 사용량(V24 {@code usage_counter}) — 창 하나의 수를 원자적으로 늘리고 줄인다.
 *
 * <p>늘리기는 UPSERT 한 번이다. 「읽고 확인하고 쓰기」 로 하면 동시에 온 요청이 같은 수를 읽어 한도를 넘긴다.
 */
@Repository
public class UsageStore {

  private final JdbcClient jdbc;

  public UsageStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 하나 늘리고 늘린 뒤의 수를 돌려준다. */
  public int increment(String subject, String feature, OffsetDateTime windowStart) {
    return jdbc.sql(
            """
            INSERT INTO usage_counter (subject, feature, window_start, count)
            VALUES (:subject, :feature, :window, 1)
            ON CONFLICT (subject, feature, window_start)
            DO UPDATE SET count = usage_counter.count + 1
            RETURNING count
            """)
        .param("subject", subject)
        .param("feature", feature)
        .param("window", windowStart)
        .query(Integer.class)
        .single();
  }

  /** 하나 줄인다 — 한도를 넘어 되돌릴 때, 제공자가 실패해 사용자 몫으로 치지 않을 때. 0 아래로는 가지 않는다. */
  public void decrement(String subject, String feature, OffsetDateTime windowStart) {
    jdbc.sql(
            """
            UPDATE usage_counter SET count = count - 1
            WHERE subject = :subject AND feature = :feature AND window_start = :window AND count > 0
            """)
        .param("subject", subject)
        .param("feature", feature)
        .param("window", windowStart)
        .update();
  }

  /** 이틀 지난 창을 지운다. 하루 창이 끝난 뒤로 필요 없다. */
  public void purgeOld() {
    jdbc.sql("DELETE FROM usage_counter WHERE window_start < now() - INTERVAL '2 days'").update();
  }
}
