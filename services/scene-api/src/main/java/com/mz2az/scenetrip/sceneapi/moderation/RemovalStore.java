package com.mz2az.scenetrip.sceneapi.moderation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자가 글·댓글·리뷰를 내린다(V48, MZ2AZ-569, 계획 {@code moderation-operator.md} §2).
 *
 * <p>지우지 않는다 — 각 표의 {@code removed_at}(「운영자가 내린 때」)을 채운다. 내린 것은 이미 모든 읽기가 {@code removed_at IS
 * NULL} 로 거른다. 새로 내렸을 때만 {@code moderation_action} 에 누가·무엇을·본문 앞부분을 남긴다. 이미 내려 있으면 시각도 기록도 그대로 둔다.
 */
@Repository
public class RemovalStore {

  public enum Outcome {
    /** 새로 내렸다. */
    REMOVED,
    /** 이미 내려 있었다. */
    ALREADY,
    /** 그런 행이 없다(지워졌다). */
    NOT_FOUND
  }

  private static final int EXCERPT_LENGTH = 200;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public RemovalStore(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  private record Row(boolean removed, String excerpt) {}

  public Outcome remove(UUID operator, ReportStore.Target target, long id) {
    String table =
        switch (target) {
          case POST -> "community_post";
          case COMMENT -> "community_comment";
          case REVIEW -> "review";
        };
    String excerpt =
        switch (target) {
          case POST -> "left(title || ' — ' || body, :len)";
          case COMMENT -> "left(body, :len)";
          case REVIEW -> "left(coalesce(body, ''), :len)";
        };
    return transactions.execute(
        status -> {
          // 같은 행을 두 운영자가 동시에 내려도 기록이 한 줄이 되게 행을 잡는다.
          Optional<Row> row =
              jdbc.sql(
                      "SELECT removed_at IS NOT NULL AS removed, %s AS excerpt FROM %s WHERE id = :id FOR UPDATE"
                          .formatted(excerpt, table))
                  .param("id", id)
                  .param("len", EXCERPT_LENGTH)
                  .query((rs, i) -> new Row(rs.getBoolean("removed"), rs.getString("excerpt")))
                  .optional();
          if (row.isEmpty()) {
            return Outcome.NOT_FOUND;
          }
          if (row.get().removed()) {
            return Outcome.ALREADY;
          }
          jdbc.sql("UPDATE %s SET removed_at = now() WHERE id = :id".formatted(table))
              .param("id", id)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO moderation_action (operator_id, target_type, target_id, action, excerpt)
                  VALUES (CAST(:operator AS UUID), :type, :id, 'remove', :excerpt)
                  """)
              .param("operator", operator.toString())
              .param("type", target.value)
              .param("id", id)
              .param("excerpt", row.get().excerpt())
              .update();
          return Outcome.REMOVED;
        });
  }
}
