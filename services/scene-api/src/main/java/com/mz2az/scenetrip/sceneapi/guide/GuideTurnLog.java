package com.mz2az.scenetrip.sceneapi.guide;

import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.GuideToolUse;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

/**
 * 챗봇 한 턴의 처리 기록(V29 {@code guide_turn}, MZ2AZ-410).
 *
 * <p><b>대화 원문·사용자·기기는 남기지 않는다.</b> 쓴 도구, 돌려준 장소 수, 이어진 효과, 걸린 시간, 실패 코드만 — 장소를 0 개 돌려준 턴이 「못 찾은 답」
 * 후보다. 기록하다 실패해도 사용자의 답에는 영향이 없다(로그만).
 */
@Repository
public class GuideTurnLog {

  private static final Logger log = LoggerFactory.getLogger(GuideTurnLog.class);

  /** 성공한 턴의 {@code outcome}. */
  public static final String OK = "ok";

  private final JdbcClient jdbc;
  private final int retentionDays;

  public GuideTurnLog(
      JdbcClient jdbc, @Value("${scenetrip.guide.turn-log-retention-days:90}") int retentionDays) {
    this.jdbc = jdbc;
    this.retentionDays = retentionDays;
  }

  /**
   * 에이전트가 답한 턴. 응답이 어떤 모양이든 던지지 않는다 — 기록 때문에 사용자의 답이 실패하면 안 된다(목록에 null 이 섞인 응답에서 실제로 그랬다, 시험이 잡음).
   */
  public void answered(String lang, GuideChatReply reply, long tookMs) {
    try {
      List<String> tools =
          reply.getToolsUsed() == null
              ? List.of()
              : reply.getToolsUsed().stream()
                  .filter(Objects::nonNull)
                  .map(GuideToolUse::getTool)
                  .filter(Objects::nonNull)
                  .toList();
      List<String> effects =
          reply.getEffects() == null
              ? List.of()
              : reply.getEffects().stream()
                  .filter(Objects::nonNull)
                  .map(GuideEffect::getOp)
                  .filter(Objects::nonNull)
                  .toList();
      int places = reply.getPlaces() == null ? 0 : reply.getPlaces().size();
      write(lang, OK, tools, places, effects, tookMs);
    } catch (RuntimeException e) {
      log.warn("챗봇 처리 기록을 적지 못했다", e);
    }
  }

  /** 에이전트를 부르다 실패한 턴. {@code code} 는 응답의 오류 코드. */
  public void failed(String lang, String code, long tookMs) {
    try {
      write(lang, code, List.of(), null, List.of(), tookMs);
    } catch (RuntimeException e) {
      log.warn("챗봇 처리 기록을 적지 못했다", e);
    }
  }

  private void write(
      String lang,
      String outcome,
      List<String> tools,
      Integer places,
      List<String> effects,
      long tookMs) {
    try {
      jdbc.sql(
              """
              INSERT INTO guide_turn (lang, outcome, tools, place_count, effects, took_ms)
              VALUES (:lang, :outcome, :tools, :places, :effects, :tookMs)
              """)
          .param("lang", lang)
          .param("outcome", outcome)
          .param("tools", tools.toArray(String[]::new))
          .param("places", places)
          .param("effects", effects.toArray(String[]::new))
          .param("tookMs", (int) Math.min(Math.max(tookMs, 0), Integer.MAX_VALUE))
          .update();
    } catch (DataAccessException e) {
      log.warn("챗봇 처리 기록을 적지 못했다", e);
    }
  }

  /** 오래된 기록을 지운다 — 한국 시간 새벽, 인기도 계산 조금 뒤. */
  @Scheduled(cron = "${scenetrip.guide.turn-log-purge-cron:0 45 3 * * *}", zone = "Asia/Seoul")
  public void purge() {
    try {
      int rows =
          jdbc.sql("DELETE FROM guide_turn WHERE created_at < now() - make_interval(days => :days)")
              .param("days", retentionDays)
              .update();
      log.info("챗봇 처리 기록 정리: {} 줄", rows);
    } catch (DataAccessException e) {
      log.error("챗봇 처리 기록 정리 실패", e);
    }
  }
}
