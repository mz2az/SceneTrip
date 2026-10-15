package com.mz2az.scenetrip.sceneapi.moderation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커뮤니티 신고(V35, MZ2AZ-438, 계획 {@code community-moderation.md} §3).
 *
 * <p>리뷰·여행후기를 신고한다. 신고 당시의 작성자와 본문 앞부분을 함께 적는다 — 글이 나중에 지워지거나 고쳐져도 무엇이 신고됐는지 알 수 있게. 처리(글 내리기)는 아직
 * 없다. 접수 후 1 년이 지난 기록은 지운다(처리방침에 적는 보관 기간, {@link #purge()}).
 */
@Repository
public class ReportStore {

  /** 신고할 수 있는 글. 값은 계약 {@code ReportTargetType} 과 표의 {@code target_type} 이 같다. */
  public enum Target {
    REVIEW("review"),
    POST("post");

    public final String value;

    Target(String value) {
      this.value = value;
    }
  }

  /** 신고의 결과. */
  public enum Outcome {
    /** 새로 적었다. */
    RECORDED,
    /** 이 사람이 이 글을 이미 신고했다 — 한 번으로 센다. */
    ALREADY,
    /** 그런 글이 없거나 이미 내려갔다. */
    NOT_FOUND,
    /** 자기 글이다. */
    OWN
  }

  /** 신고하는 글의 지금 모습 — 작성자(탈퇴했으면 {@code null})와 본문 앞부분. */
  private record Snapshot(UUID authorId, String excerpt) {}

  private static final Logger log = LoggerFactory.getLogger(ReportStore.class);

  private static final int EXCERPT_LENGTH = 200;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final int retentionDays;

  public ReportStore(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      @Value("${scenetrip.moderation.report-retention-days:365}") int retentionDays) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.retentionDays = retentionDays;
  }

  /**
   * 신고한다.
   *
   * @param reason 계약 {@code ReportReason} 의 값
   * @param detail 덧붙이는 설명. 없으면 {@code null}
   */
  public Outcome report(UUID reporter, Target target, long targetId, String reason, String detail) {
    return transactions.execute(
        status -> {
          Optional<Snapshot> found = snapshot(target, targetId);
          if (found.isEmpty()) {
            return Outcome.NOT_FOUND;
          }
          Snapshot snap = found.get();
          if (Objects.equals(snap.authorId(), reporter)) {
            return Outcome.OWN;
          }
          int inserted =
              jdbc.sql(
                      """
                      INSERT INTO content_report
                          (reporter_id, target_type, target_id, reason, detail, author_id, excerpt)
                      VALUES (CAST(:reporter AS UUID), :type, :id, :reason, :detail,
                              CAST(:author AS UUID), :excerpt)
                      ON CONFLICT (reporter_id, target_type, target_id) DO NOTHING
                      """)
                  .param("reporter", reporter.toString())
                  .param("type", target.value)
                  .param("id", targetId)
                  .param("reason", reason)
                  .param("detail", detail)
                  .param("author", snap.authorId() == null ? null : snap.authorId().toString())
                  .param("excerpt", snap.excerpt())
                  .update();
          return inserted > 0 ? Outcome.RECORDED : Outcome.ALREADY;
        });
  }

  /** 내려가지 않은 글의 작성자와 본문 앞부분. 후기는 「제목 — 본문」. */
  private Optional<Snapshot> snapshot(Target target, long targetId) {
    String sql =
        switch (target) {
          case REVIEW ->
              "SELECT user_id, left(coalesce(body, ''), :len) AS excerpt"
                  + " FROM review WHERE id = :id AND removed_at IS NULL";
          case POST ->
              "SELECT user_id, left(title || ' — ' || body, :len) AS excerpt"
                  + " FROM community_post WHERE id = :id AND removed_at IS NULL";
        };
    return jdbc.sql(sql)
        .param("id", targetId)
        .param("len", EXCERPT_LENGTH)
        .query(
            (rs, i) -> {
              String author = rs.getString("user_id");
              return new Snapshot(
                  author == null ? null : UUID.fromString(author), rs.getString("excerpt"));
            })
        .optional();
  }

  /** 보관 기간이 지난 신고를 지운다 — 한국 시간 새벽, 챗봇 처리 기록 정리 조금 뒤. 여러 서버가 함께 돌아도 같은 줄을 지울 뿐이다. */
  @Scheduled(cron = "${scenetrip.moderation.report-purge-cron:0 50 3 * * *}", zone = "Asia/Seoul")
  public void purge() {
    try {
      int rows =
          jdbc.sql(
                  "DELETE FROM content_report"
                      + " WHERE created_at < now() - make_interval(days => :days)")
              .param("days", retentionDays)
              .update();
      log.info("신고 기록 정리: {} 줄", rows);
    } catch (DataAccessException e) {
      log.error("신고 기록 정리 실패", e);
    }
  }
}
