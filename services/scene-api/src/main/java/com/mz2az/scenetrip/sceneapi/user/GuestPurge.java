package com.mz2az.scenetrip.sceneapi.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 방치된 비회원 계정 정리 — 마지막 이용 후 보관 기간(기본 365 일)이 지난 가입하지 않은 계정을 지운다(MZ2AZ-467, 처리방침의 「비회원 기록은 마지막 이용 후 1
 * 년」).
 *
 * <p>비회원 계정은 설치마다 하나씩 생긴다({@link UserStore}). 계정을 확인하는 요청마다(한 시간에 한 번까지) {@code last_seen_at} 이
 * 갱신되므로 그것이 마지막 이용이다 — 비어 있으면 만든 때. 기준이 1 년이라 한 시간 오차는 상관없다. 로그인하며 회원 계정에 합쳐진 빈 비회원 행({@code
 * merged_into})도 가입하지 않은 계정이라 같은 기준으로 지운다. 그 계정의 장바구니·코스·찜·기기 연결은 {@code ON DELETE CASCADE} 로 함께
 * 사라지고, 한 신고는 신고자 연결만 끊긴다. 비회원은 리뷰·후기를 쓰지 못해 지울 사진이 없다.
 *
 * <p>하루에 {@code batch} 개까지만 — 큰 DELETE 로 표를 오래 잡지 않는다. 남은 것은 다음 날 이어서 지운다. 여러 서버가 함께 돌아도 같은 줄을 지울
 * 뿐이다.
 */
@Component
public class GuestPurge {

  private static final Logger log = LoggerFactory.getLogger(GuestPurge.class);

  private final JdbcClient jdbc;
  private final int retentionDays;
  private final int batch;

  public GuestPurge(
      JdbcClient jdbc,
      @Value("${scenetrip.guest.retention-days:365}") int retentionDays,
      @Value("${scenetrip.guest.purge-batch:10000}") int batch) {
    this.jdbc = jdbc;
    this.retentionDays = retentionDays;
    this.batch = batch;
  }

  /** 한국 시간 새벽, 다른 정리 작업 뒤. */
  @Scheduled(cron = "${scenetrip.guest.purge-cron:0 5 4 * * *}", zone = "Asia/Seoul")
  public void purge() {
    try {
      int rows =
          jdbc.sql(
                  """
                  DELETE FROM app_user
                  WHERE id IN (
                      SELECT id FROM app_user
                      WHERE registered_at IS NULL
                        AND coalesce(last_seen_at, created_at) < now() - make_interval(days => :days)
                      ORDER BY coalesce(last_seen_at, created_at)
                      LIMIT :batch
                  )
                  """)
              .param("days", retentionDays)
              .param("batch", batch)
              .update();
      log.info("비회원 계정 정리: {} 개", rows);
    } catch (DataAccessException e) {
      log.error("비회원 계정 정리 실패", e);
    }
  }
}
