package com.mz2az.scenetrip.sceneapi.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 리뷰 보관본({@code review_archive}, V30) 정리 — 보관 기간(기본 90 일)이 지난 사본과 그 사진 파일을 지운다(MZ2AZ-466).
 *
 * <p>보관본은 같은 곳 연결이 한 사람의 겹친 리뷰를 지울 때 떠 두는 사본이라, 연결이 잘못됐을 때 되돌리는 동안만 있으면 된다. 연결은 분기 갱신 때 돌아 문제가 있으면
 * 그 안에 드러난다. 사진 파일은 지울 목록({@code storage_deletion})에 올리고 {@link StorageDeletionJob} 이 지운다 — 목록 올리기와
 * 행 지우기는 한 문장이다. 여러 서버가 함께 돌아도 같은 줄을 지울 뿐이다. 탈퇴할 때는 기간과 상관없이 바로 지운다({@code UserStore.delete}).
 */
@Component
public class ReviewArchivePurge {

  private static final Logger log = LoggerFactory.getLogger(ReviewArchivePurge.class);

  private final JdbcClient jdbc;
  private final int retentionDays;

  public ReviewArchivePurge(
      JdbcClient jdbc, @Value("${scenetrip.review.archive-retention-days:90}") int retentionDays) {
    this.jdbc = jdbc;
    this.retentionDays = retentionDays;
  }

  /** 한국 시간 새벽, 신고 기록 정리 조금 뒤. */
  @Scheduled(cron = "${scenetrip.review.archive-purge-cron:0 55 3 * * *}", zone = "Asia/Seoul")
  public void purge() {
    try {
      int rows =
          jdbc.sql(
                  """
                  WITH old AS (
                      SELECT id, photo_keys FROM review_archive
                      WHERE archived_at < now() - make_interval(days => :days)
                  ),
                  queued AS (
                      INSERT INTO storage_deletion (storage_key)
                      SELECT DISTINCT unnest(photo_keys) FROM old
                      ON CONFLICT (storage_key) DO NOTHING
                  )
                  DELETE FROM review_archive WHERE id IN (SELECT id FROM old)
                  """)
              .param("days", retentionDays)
              .update();
      log.info("리뷰 보관본 정리: {} 줄", rows);
    } catch (DataAccessException e) {
      log.error("리뷰 보관본 정리 실패", e);
    }
  }
}
