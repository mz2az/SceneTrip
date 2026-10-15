package com.mz2az.scenetrip.sceneapi.moderation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 차단(V36, MZ2AZ-438, 계획 {@code community-moderation.md} §4).
 *
 * <p>「이 리뷰·후기의 작성자」 를 차단한다 — 앱에 사용자 번호가 없어 글로 받는다. 목록에서 빼는 것은 각 목록의 질의가 {@link #NOT_BLOCKED} 로 한다.
 */
@Repository
public class BlockStore {

  /**
   * 목록 질의에 붙이는 조건 — 「보는 사람(:viewer)이 이 작성자를 차단하지 않았다」. {@code %s} 에 작성자 칸(예: {@code r.user_id})을
   * 넣는다. 보는 사람이 없으면(:viewer 가 NULL) 아무것도 빼지 않는다. 짝 UNIQUE(blocker_id, blocked_id) 인덱스를 탄다.
   */
  public static final String NOT_BLOCKED =
      "NOT EXISTS (SELECT 1 FROM user_block ub"
          + " WHERE ub.blocker_id = CAST(:viewer AS UUID) AND ub.blocked_id = %s)";

  /** 차단 목록의 한 줄. */
  public record Blocked(long id, String nickname, OffsetDateTime blockedAt) {}

  /** 차단의 결과. */
  public sealed interface Outcome {
    /** 새로 차단했다. */
    record Created(Blocked block) implements Outcome {}

    /** 이미 차단한 사람이다 — 있던 줄. */
    record Existing(Blocked block) implements Outcome {}

    /** 그런 글이 없거나 이미 내려갔다. */
    record NotFound() implements Outcome {}

    /** 자기 글이다. */
    record Self() implements Outcome {}

    /** 작성자가 탈퇴해 글쓴이가 없다. */
    record NoAuthor() implements Outcome {}
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public BlockStore(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /** 글의 작성자를 차단한다. */
  public Outcome block(UUID blocker, ReportStore.Target target, long targetId) {
    return transactions.execute(
        status -> {
          String table = target == ReportStore.Target.REVIEW ? "review" : "community_post";
          Optional<Optional<UUID>> author =
              jdbc.sql(
                      "SELECT user_id FROM %s WHERE id = :id AND removed_at IS NULL"
                          .formatted(table))
                  .param("id", targetId)
                  .query(
                      (rs, i) -> {
                        String id = rs.getString("user_id");
                        return Optional.ofNullable(id == null ? null : UUID.fromString(id));
                      })
                  .optional();
          if (author.isEmpty()) {
            return new Outcome.NotFound();
          }
          if (author.get().isEmpty()) {
            return new Outcome.NoAuthor();
          }
          UUID blocked = author.get().get();
          if (Objects.equals(blocked, blocker)) {
            return new Outcome.Self();
          }
          int inserted =
              jdbc.sql(
                      "INSERT INTO user_block (blocker_id, blocked_id)"
                          + " VALUES (CAST(:blocker AS UUID), CAST(:blocked AS UUID))"
                          + " ON CONFLICT (blocker_id, blocked_id) DO NOTHING")
                  .param("blocker", blocker.toString())
                  .param("blocked", blocked.toString())
                  .update();
          Blocked row =
              jdbc.sql(
                      """
                      SELECT b.id, coalesce(u.nickname, '') AS nickname, b.created_at
                      FROM user_block b JOIN app_user u ON u.id = b.blocked_id
                      WHERE b.blocker_id = CAST(:blocker AS UUID)
                        AND b.blocked_id = CAST(:blocked AS UUID)
                      """)
                  .param("blocker", blocker.toString())
                  .param("blocked", blocked.toString())
                  .query(BlockStore::row)
                  .single();
          return inserted > 0 ? new Outcome.Created(row) : new Outcome.Existing(row);
        });
  }

  /** 내 차단 목록 — 최근에 차단한 사람부터. */
  public List<Blocked> list(UUID blocker) {
    return jdbc.sql(
            """
            SELECT b.id, coalesce(u.nickname, '') AS nickname, b.created_at
            FROM user_block b JOIN app_user u ON u.id = b.blocked_id
            WHERE b.blocker_id = CAST(:blocker AS UUID)
            ORDER BY b.created_at DESC, b.id DESC
            """)
        .param("blocker", blocker.toString())
        .query(BlockStore::row)
        .list();
  }

  /** 차단을 푼다. 내 목록에 그런 줄이 없으면 {@code false}. */
  public boolean unblock(UUID blocker, long blockId) {
    return jdbc.sql("DELETE FROM user_block WHERE id = :id AND blocker_id = CAST(:blocker AS UUID)")
            .param("id", blockId)
            .param("blocker", blocker.toString())
            .update()
        > 0;
  }

  private static Blocked row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new Blocked(
        rs.getLong("id"),
        rs.getString("nickname"),
        rs.getObject("created_at", OffsetDateTime.class));
  }
}
