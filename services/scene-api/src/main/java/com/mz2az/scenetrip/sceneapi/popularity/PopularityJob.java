package com.mz2az.scenetrip.sceneapi.popularity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 매일 밤 인기도를 다시 계산한다(계획 {@code popularity.md} §5).
 *
 * <p>계산은 DB 함수 {@code recompute_popularity}(V28) 한 곳에 있다 — 적재 끝과 {@code just popularity-recompute}
 * 도 같은 것을 부른다. 파드가 여럿이어도 함수가 advisory lock 으로 한 번만 돈다. 클러스터에 CronJob·배치 이미지를 따로 두지 않으려고 서버 안에 둔다.
 *
 * <p>{@code scenetrip.popularity.schedule-enabled=false} 면 돌지 않는다(시험).
 */
@Configuration
@ConditionalOnProperty(
    name = "scenetrip.popularity.schedule-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PopularityJob {

  private static final Logger log = LoggerFactory.getLogger(PopularityJob.class);

  private final JdbcClient jdbc;
  private final PopularitySettings settings;

  public PopularityJob(JdbcClient jdbc, PopularitySettings settings) {
    this.jdbc = jdbc;
    this.settings = settings;
  }

  /** 한국 시간 새벽. 사용자가 가장 적을 때. */
  @Scheduled(cron = "${scenetrip.popularity.cron:0 30 3 * * *}", zone = "Asia/Seoul")
  public void nightly() {
    try {
      log.info("인기도 다시 계산: {}", recompute());
    } catch (RuntimeException e) {
      // 다음 밤에 다시 돈다. 그동안 순서는 어제 값이다 — 서비스는 멈추지 않는다.
      log.error("인기도 다시 계산 실패", e);
    }
  }

  /**
   * 계산하고 함수가 돌려준 요약(JSON 문자열)을 준다.
   *
   * <p>인자마다 형을 못 박는다 — 자바 {@code double} 은 {@code double precision} 으로 가는데, 이름 붙인 인자로 부르면 Postgres
   * 가 {@code NUMERIC} 으로 바꿔 주지 않아 「그런 함수가 없다」 로 실패한다(시험이 잡았다). 실패는 {@link #nightly()} 가 로그로만 남기므로
   * 조용히 묻힌다.
   */
  public String recompute() {
    return jdbc.sql(
            """
            SELECT recompute_popularity(
                k => CAST(:k AS NUMERIC),
                window_days => CAST(:window AS INT),
                retention_days => CAST(:retention AS INT),
                w_view => CAST(:view AS NUMERIC),
                w_heart => CAST(:heart AS NUMERIC),
                w_cart => CAST(:cart AS NUMERIC),
                w_course => CAST(:course AS NUMERIC),
                w_review => CAST(:review AS NUMERIC),
                w_visit => CAST(:visit AS NUMERIC))::text
            """)
        .param("k", settings.getK())
        .param("window", settings.getWindowDays())
        .param("retention", settings.getRetentionDays())
        .param("view", settings.getWeightView())
        .param("heart", settings.getWeightHeart())
        .param("cart", settings.getWeightCart())
        .param("course", settings.getWeightCourse())
        .param("review", settings.getWeightReview())
        .param("visit", settings.getWeightVisit())
        .query(String.class)
        .single();
  }
}
