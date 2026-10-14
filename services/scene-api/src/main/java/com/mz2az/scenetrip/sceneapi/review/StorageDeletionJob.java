package com.mz2az.scenetrip.sceneapi.review;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 지울 목록({@code storage_deletion}, V34)의 사진 파일을 저장소에서 지운다(MZ2AZ-406, 계획 {@code
 * post-photo-deletion.md}).
 *
 * <p>글을 지우는 요청은 목록에 올리기만 하고, 저장소에서 지우는 일은 이 작업 하나가 전부 맡는다 — 지우는 길이 하나다. 1 분마다 돈다. cron 이어야 한다: 가상
 * 스레드를 켠 이 서버의 스케줄러는 cron 실행을 매번 새 가상 스레드에 넘기지만, 고정 지연은 스케줄러 스레드 하나에서 직접 돌아 길어지면 다른 작업의 시작까지 밀린다.
 *
 * <p>파드가 여럿이어도 한 대만 돈다 — 트랜잭션 advisory lock 을 못 잡으면 그 차례를 건너뛴다({@code recompute_popularity} 와 같은
 * 방식). 잠금은 트랜잭션이 끝나면 풀리므로 도중에 죽어도 남지 않는다.
 *
 * <p>{@code scenetrip.media.deletion.schedule-enabled=false} 면 돌지 않는다(시험은 {@link #run()} 을 직접 부른다).
 */
@Component
@ConditionalOnProperty(
    name = "scenetrip.media.deletion.schedule-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class StorageDeletionJob {

  private static final Logger log = LoggerFactory.getLogger(StorageDeletionJob.class);

  /** 한 번에 지우는 수 — 한 차례가 길어지지 않게. */
  static final int BATCH = 20;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final PhotoStorage storage;

  public StorageDeletionJob(
      JdbcClient jdbc, TransactionTemplate transactions, PhotoStorage storage) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.storage = storage;
  }

  @Scheduled(cron = "${scenetrip.media.deletion.cron:0 * * * * *}", zone = "Asia/Seoul")
  public void everyMinute() {
    try {
      int deleted = run();
      if (deleted > 0) {
        log.info("사진 파일 {} 개를 지웠습니다", deleted);
      }
    } catch (RuntimeException e) {
      // 다음 차례에 다시 돈다. 목록은 그대로다.
      log.error("사진 파일 지우기 작업 실패", e);
    }
  }

  /**
   * 한 차례 돈다 — 오래된 것부터 {@link #BATCH} 개를 지우고, 지운 줄은 뺀다.
   *
   * <p>저장소가 실패하면 그 키의 {@code attempts}·{@code last_error} 를 올리고 <b>이번 차례는 멈춘다</b> — 저장소가 고장인데 남은 키를
   * 다 두드리며 타임아웃을 기다리지 않는다. 실패 기록은 앞서 지운 줄과 함께 커밋된다(예외를 잡았으니 롤백되지 않는다).
   *
   * @return 지운 개수. 저장소가 없거나 다른 파드가 도는 중이면 0
   */
  public int run() {
    if (!storage.available()) {
      return 0;
    }
    Integer deleted =
        transactions.execute(
            status -> {
              boolean locked =
                  jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('storage_deletion'))")
                      .query(Boolean.class)
                      .single();
              if (!locked) {
                return 0;
              }
              List<String> keys =
                  jdbc.sql(
                          "SELECT storage_key FROM storage_deletion"
                              + " ORDER BY created_at, storage_key LIMIT :batch")
                      .param("batch", BATCH)
                      .query(String.class)
                      .list();
              int done = 0;
              for (String key : keys) {
                try {
                  storage.delete(key);
                } catch (RuntimeException e) {
                  jdbc.sql(
                          "UPDATE storage_deletion SET attempts = attempts + 1, last_error = :error"
                              + " WHERE storage_key = :key")
                      .param("error", String.valueOf(e.getMessage()))
                      .param("key", key)
                      .update();
                  log.warn("사진 파일을 지우지 못했습니다 — 다음 차례에 다시: {}", key, e);
                  break;
                }
                jdbc.sql("DELETE FROM storage_deletion WHERE storage_key = :key")
                    .param("key", key)
                    .update();
                done++;
              }
              return done;
            });
    return deleted == null ? 0 : deleted;
  }
}
