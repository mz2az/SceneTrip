package com.mz2az.scenetrip.sceneapi.review;

import com.mz2az.scenetrip.sceneapi.thumbnail.PhotoShrinker;
import com.mz2az.scenetrip.sceneapi.thumbnail.ThumbnailRules;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 후기·리뷰 사진의 작은 사진(긴 변 720 JPEG)을 만든다 — 계획 {@code docs/project/plans/photo-thumbnails.md} §2-1·§9,
 * ADR 0030, MZ2AZ-561.
 *
 * <p>{@code community_post_photo}·{@code review_image} 에서 {@code thumbnail_key IS NULL AND
 * thumbnail_attempts < 3} 인 줄을 새것부터 골라(글 사진을 먼저, 남은 자리를 리뷰 사진으로) 한 차례에 {@link #BATCH} 장까지, 한 장씩:
 * 원본을 읽고 → 줄이고({@link PhotoShrinker}) → {@code <자리>/thumb-720/<이름>.jpg} 에 올리고 → 같은 줄에 키를 적는다. 새 사진도
 * 옛 사진(백필)도 같은 길이다.
 *
 * <p>실패:
 *
 * <ul>
 *   <li>그 사진만의 실패 — 원본이 없다·그림으로 읽을 수 없다: 그 줄의 {@code thumbnail_attempts + 1}, 차례의 나머지는 계속한다.
 *   <li>저장소 자체의 실패 — 읽기·올리기 오류: 그 줄의 시도 +1 하고 <b>이번 차례는 멈춘다</b>(다음 분에 다시). 저장소가 고장인데 남은 장을 다 두드리지
 *       않는다({@link StorageDeletionJob} 과 같다).
 * </ul>
 *
 * 3 번 실패한 줄은 더 고르지 않는다 — 응답은 원본으로 간다. 올리는 사이 글·리뷰가 지워졌으면(키를 적을 줄이 없다) 올린 작은 사진을 지울 목록에 넣는다.
 *
 * <p>파드가 여럿이어도 한 대만 돈다 — 트랜잭션 advisory lock {@code hashtext('photo_thumbnail')}. 1 분마다, 지우기 작업과 겹치지
 * 않게 30 초에. {@code scenetrip.media.thumbnail.schedule-enabled=false} 면 돌지 않는다. 저장소가 없는 환경은 켜져 있어도 0
 * 이다.
 */
@Component
@ConditionalOnProperty(
    name = "scenetrip.media.thumbnail.schedule-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PhotoThumbnailJob {

  private static final Logger log = LoggerFactory.getLogger(PhotoThumbnailJob.class);

  /** 한 차례에 만드는 최대 수 — 한 차례가 길어지지 않게. */
  public static final int BATCH = 20;

  private static final String CONTENT_TYPE = "image/jpeg";

  /** 사진 줄이 있는 표. 표 이름은 이 클래스 안의 상수에서만 오므로 SQL 에 끼워 넣어도 안전하다. */
  private enum Table {
    POST("community_post_photo", "post_id DESC, sort_order"),
    REVIEW("review_image", "id DESC");

    private final String name;
    private final String newestFirst;

    Table(String name, String newestFirst) {
      this.name = name;
      this.newestFirst = newestFirst;
    }
  }

  private record Pending(Table table, String key) {}

  /** 저장소 자체가 실패했다 — 이번 차례를 멈춘다. */
  private static final class StorageDown extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StorageDown(RuntimeException cause) {
      super(cause);
    }
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final PhotoStorage storage;

  public PhotoThumbnailJob(
      JdbcClient jdbc, TransactionTemplate transactions, PhotoStorage storage) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.storage = storage;
  }

  @Scheduled(cron = "${scenetrip.media.thumbnail.cron:30 * * * * *}", zone = "Asia/Seoul")
  public void everyMinute() {
    try {
      int made = run();
      if (made > 0) {
        log.info("작은 사진 {} 장을 만들었습니다", made);
      }
    } catch (RuntimeException e) {
      // 다음 차례에 다시 돈다. 그동안 응답은 원본으로 간다.
      log.error("작은 사진 작업 실패", e);
    }
  }

  /**
   * 한 차례 돈다.
   *
   * @return 만든 작은 사진 수. 저장소가 없거나 다른 파드가 도는 중이면 0
   */
  public int run() {
    if (!storage.available()) {
      return 0;
    }
    Integer made =
        transactions.execute(
            status -> {
              boolean locked =
                  jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('photo_thumbnail'))")
                      .query(Boolean.class)
                      .single();
              if (!locked) {
                return 0;
              }
              int done = 0;
              for (Pending p : pending()) {
                try {
                  if (make(p)) {
                    done++;
                  }
                } catch (StorageDown e) {
                  log.warn("사진 저장소 오류 — 이번 차례를 멈춥니다: {}", p.key(), e.getCause());
                  break;
                }
              }
              return done;
            });
    return made == null ? 0 : made;
  }

  /** 고를 줄 — 글 사진을 새것부터, 남은 자리를 리뷰 사진으로(계획 §9-1). */
  private List<Pending> pending() {
    List<Pending> out = new ArrayList<>(select(Table.POST, BATCH));
    if (out.size() < BATCH) {
      out.addAll(select(Table.REVIEW, BATCH - out.size()));
    }
    return out;
  }

  private List<Pending> select(Table table, int limit) {
    return jdbc
        .sql(
            ("SELECT storage_key FROM %s"
                    + " WHERE thumbnail_key IS NULL AND thumbnail_attempts < :max"
                    + " ORDER BY %s LIMIT :limit")
                .formatted(table.name, table.newestFirst))
        .param("max", ThumbnailRules.MAX_ATTEMPTS)
        .param("limit", limit)
        .query(String.class)
        .list()
        .stream()
        .map(k -> new Pending(table, k))
        .toList();
  }

  /**
   * 한 장. 만들었으면 {@code true}. 그 사진만의 실패는 시도를 올리고 {@code false}, 저장소 실패는 시도를 올리고 {@link StorageDown}.
   */
  private boolean make(Pending p) {
    String thumbnailKey;
    byte[] small;
    try {
      thumbnailKey = ThumbnailRules.thumbnailKey(p.key());
      small = PhotoShrinker.shrink(storage.read(p.key()));
    } catch (IllegalArgumentException
        | PhotoStorage.MissingPhotoException
        | PhotoShrinker.UnreadableImageException e) {
      failed(p, e);
      return false;
    } catch (RuntimeException e) {
      failed(p, e);
      throw new StorageDown(e);
    }
    try {
      storage.put(thumbnailKey, small, CONTENT_TYPE);
    } catch (RuntimeException e) {
      failed(p, e);
      throw new StorageDown(e);
    }
    int updated =
        jdbc.sql(
                "UPDATE %s SET thumbnail_key = :thumb WHERE storage_key = :key"
                    .formatted(p.table().name))
            .param("thumb", thumbnailKey)
            .param("key", p.key())
            .update();
    if (updated == 0) {
      // 올리는 사이 글·리뷰가 지워졌다 — 가리킬 줄이 없는 작은 사진은 지울 목록으로(그 길은 하나다, StorageDeletionJob).
      jdbc.sql(
              "INSERT INTO storage_deletion (storage_key) VALUES (:key)"
                  + " ON CONFLICT (storage_key) DO NOTHING")
          .param("key", thumbnailKey)
          .update();
      return false;
    }
    return true;
  }

  private void failed(Pending p, RuntimeException e) {
    jdbc.sql(
            "UPDATE %s SET thumbnail_attempts = thumbnail_attempts + 1 WHERE storage_key = :key"
                .formatted(p.table().name))
        .param("key", p.key())
        .update();
    log.warn("작은 사진을 만들지 못했습니다(시도 +1): {} — {}", p.key(), e.toString());
  }
}
