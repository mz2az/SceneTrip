package com.mz2az.scenetrip.sceneapi.review;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일주일에 한 번, 아무 글도 가리키지 않는 사용자 사진 파일을 지울 목록에 올린다(MZ2AZ-448, 계획 {@code post-photo-deletion.md} §6-3).
 *
 * <p>글을 지우거나 리뷰를 고치는 길은 키를 바로 목록에 올린다. 이 작업은 그 길이 생기기 전에 남은 파일과, 앞으로 어떤 이유로 새는 파일을 잡는다. 지우는 것은
 * {@link StorageDeletionJob} 이다 — 지우는 길은 여전히 하나다.
 *
 * <p>{@code reviews/}·{@code posts/} 만 본다({@code uploads/tmp/} 는 버킷 수명 규칙이 하루 뒤 지운다). 마지막으로 바뀐 지
 * {@link #MIN_AGE} 가 넘은 파일만 — 사진은 옮겨진 <b>뒤에</b> DB 에 붙으므로 그 사이, 또는 저장에 실패해 되돌리는 도중의 파일을 지우면 안 된다.
 * 쪽(S3 는 1,000 개)마다 DB 와 비교해 목록을 한꺼번에 메모리에 올리지 않는다.
 *
 * <p>파드가 여럿이어도 한 대만 돈다 — 트랜잭션 advisory lock {@code
 * pg_try_advisory_xact_lock(hashtext('orphan_photo_sweep'))} ({@link StorageDeletionJob} 의 {@code
 * hashtext('storage_deletion')} 과 같은 방식, 다른 열쇠 — 둘이 서로 막지 않는다). {@code
 * scenetrip.media.orphan-sweep.schedule-enabled=false} 면 돌지 않는다(시험은 {@link #run()} 을 직접 부른다).
 */
@Component
@ConditionalOnProperty(
    name = "scenetrip.media.orphan-sweep.schedule-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OrphanPhotoSweep {

  private static final Logger log = LoggerFactory.getLogger(OrphanPhotoSweep.class);

  /** 이보다 최근에 바뀐 파일은 건드리지 않는다. */
  static final Duration MIN_AGE = Duration.ofDays(7);

  /** 사진이 붙는 자리. 임시 자리({@code uploads/tmp/})는 넣지 않는다. */
  static final List<String> PREFIXES = List.of("reviews/", "posts/");

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final PhotoStorage storage;
  private final Clock clock;

  @Autowired
  public OrphanPhotoSweep(JdbcClient jdbc, TransactionTemplate transactions, PhotoStorage storage) {
    this(jdbc, transactions, storage, Clock.systemUTC());
  }

  OrphanPhotoSweep(
      JdbcClient jdbc, TransactionTemplate transactions, PhotoStorage storage, Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.storage = storage;
    this.clock = clock;
  }

  /** 한국 시간 일요일 새벽. 사용자가 가장 적을 때. */
  @Scheduled(cron = "${scenetrip.media.orphan-sweep.cron:0 0 4 * * SUN}", zone = "Asia/Seoul")
  public void weekly() {
    try {
      int queued = run();
      log.info("고아 사진 {} 개를 지울 목록에 올렸습니다", queued);
    } catch (RuntimeException e) {
      // 다음 주에 다시 돈다. 그동안 고아 파일은 남아 있을 뿐 아무것도 깨지지 않는다.
      log.error("고아 사진 청소 실패", e);
    }
  }

  /**
   * 한 차례 돈다.
   *
   * @return 지울 목록에 새로 올린 개수. 저장소가 없거나 다른 파드가 도는 중이면 0
   */
  public int run() {
    if (!storage.available()) {
      return 0;
    }
    Instant cutoff = clock.instant().minus(MIN_AGE);
    Integer queued =
        transactions.execute(
            status -> {
              boolean locked =
                  jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('orphan_photo_sweep'))")
                      .query(Boolean.class)
                      .single();
              if (!locked) {
                return 0;
              }
              int[] count = {0};
              for (String prefix : PREFIXES) {
                storage.listPages(
                    prefix,
                    page -> {
                      List<String> old =
                          page.stream()
                              .filter(o -> o.lastModified().isBefore(cutoff))
                              .map(PhotoStorage.StoredObject::key)
                              .toList();
                      if (!old.isEmpty()) {
                        count[0] += queueUnreferenced(old);
                      }
                    });
              }
              return count[0];
            });
    return queued == null ? 0 : queued;
  }

  /** 리뷰 사진·후기 사진 어디에서도 가리키지 않는 키를 목록에 올리고, 새로 오른 개수를 돌려준다. */
  private int queueUnreferenced(List<String> keys) {
    Set<String> referenced =
        new HashSet<>(
            jdbc.sql(
                    """
                    SELECT storage_key FROM review_image WHERE storage_key = ANY(:keys)
                    UNION
                    SELECT storage_key FROM community_post_photo WHERE storage_key = ANY(:keys)
                    """)
                .param("keys", keys.toArray(String[]::new))
                .query(String.class)
                .list());
    int queued = 0;
    for (String key : keys) {
      if (!referenced.contains(key)) {
        queued +=
            jdbc.sql(
                    "INSERT INTO storage_deletion (storage_key) VALUES (:key)"
                        + " ON CONFLICT (storage_key) DO NOTHING")
                .param("key", key)
                .update();
      }
    }
    return queued;
  }
}
