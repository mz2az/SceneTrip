package com.mz2az.scenetrip.sceneapi.review;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 사진 올리기 기록(V22) — 「이 사용자가 하루 안에 받은, 아직 어디에도 붙지 않은 키」 를 가린다.
 *
 * <p>하루는 버킷 수명 규칙({@code uploads/tmp/} 하루 뒤 삭제)과 같다. 그보다 오래된 기록은 파일이 이미 없을 수 있어 받아들이지 않는다.
 */
@Repository
public class UploadStore {

  private final JdbcClient jdbc;

  public UploadStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 받은 키를 적는다. 그 김에 하루 넘은 기록을 지운다 — 따로 정리 작업을 두지 않는다. */
  public void record(UUID user, String key, String purpose, String contentType, long bytes) {
    jdbc.sql("DELETE FROM photo_upload WHERE created_at < now() - INTERVAL '1 day'").update();
    jdbc.sql(
            """
            INSERT INTO photo_upload (storage_key, user_id, purpose, content_type, bytes)
            VALUES (:key, CAST(:user AS UUID), :purpose, :contentType, :bytes)
            """)
        .param("key", key)
        .param("user", user.toString())
        .param("purpose", purpose)
        .param("contentType", contentType)
        .param("bytes", bytes)
        .update();
  }

  /** {@code keys} 중 이 사용자가 하루 안에 받았고 아직 붙지 않은 것. */
  public Set<String> unattached(UUID user, Collection<String> keys) {
    if (keys.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.sql(
                """
                SELECT storage_key FROM photo_upload
                WHERE user_id = CAST(:user AS UUID) AND storage_key IN (:keys)
                  AND created_at >= now() - INTERVAL '1 day'
                """)
            .param("user", user.toString())
            .param("keys", keys)
            .query(String.class)
            .list());
  }

  /** 붙였으니 지운다. */
  public void consume(Collection<String> keys) {
    if (keys.isEmpty()) {
      return;
    }
    jdbc.sql("DELETE FROM photo_upload WHERE storage_key IN (:keys)").param("keys", keys).update();
  }
}
