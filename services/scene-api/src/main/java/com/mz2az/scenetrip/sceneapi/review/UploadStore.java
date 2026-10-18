package com.mz2az.scenetrip.sceneapi.review;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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

  /** {@code keys} 중 이 사용자가 하루 안에 받았고 아직 붙지 않은 것 — 보기만 한다. 붙일 때는 {@link #claim} 을 쓴다. */
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

  /**
   * 붙일 사진을 <b>옮기기 전에</b> 차지한다 — 「아직 안 붙은 것」 을 고르고 그 기록을 지우는 것을 문장 하나로 한다(MZ2AZ-498).
   *
   * <p>예전에는 고르기({@link #unattached})와 지우기를 따로 했다 — 그 사이에 같은 요청이 한 번 더 오면(두 번 누름) 둘 다 「안 붙음」 을 보고 같은
   * 정식 자리로 옮겼고, 늦게 실패한 쪽이 되돌리면서 이긴 쪽이 쓰는 파일을 지웠다(로컬 재현: 여행후기 10/10, 리뷰 9/10). 이제는 둘 중 하나만 기록을 지울 수
   * 있고, 진 쪽은 차지한 것이 없어 파일을 건드리지 않는다.
   *
   * <p>행은 키 순서로 잠근다 — 사진 여러 장을 다른 순서로 보낸 두 요청이 서로를 기다리며 멈추지(교착) 않게. 이 문장은 바로 확정된다(트랜잭션 밖) — 저장소
   * 옮기기를 트랜잭션 안에서 기다리지 않는다는 기존 원칙 그대로다. 저장하지 못하면 {@link #release} 로 돌려놓는다. 차지한 직후 서버가 멈추면 그 사진은 다시
   * 쓸 수 없다 — 드물고, 앱은 사진을 다시 고르면 된다.
   *
   * @return 차지한 것. {@code keys} 중 이 사용자가 하루 안에 받았고 아직 붙지 않은 것만 들어 있다
   */
  public Claim claim(UUID user, Collection<String> keys) {
    if (keys.isEmpty()) {
      return Claim.NONE;
    }
    List<Row> rows =
        jdbc.sql(
                """
                DELETE FROM photo_upload
                 WHERE storage_key IN (
                       SELECT storage_key FROM photo_upload
                        WHERE user_id = CAST(:user AS UUID) AND storage_key IN (:keys)
                          AND created_at >= now() - INTERVAL '1 day'
                        ORDER BY storage_key
                          FOR UPDATE)
                RETURNING storage_key, user_id, purpose, content_type, bytes, created_at
                """)
            .param("user", user.toString())
            .param("keys", keys)
            .query(
                (rs, n) ->
                    new Row(
                        rs.getString("storage_key"),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("purpose"),
                        rs.getString("content_type"),
                        rs.getLong("bytes"),
                        rs.getTimestamp("created_at")))
            .list();
    return new Claim(rows);
  }

  /** 저장하지 못했으니 차지한 기록을 돌려놓는다 — 앱이 같은 키로 다시 보낼 수 있게. 받은 시각도 그대로(하루 기한이 늘지 않게). */
  public void release(Claim claim) {
    for (Row r : claim.rows()) {
      jdbc.sql(
              """
              INSERT INTO photo_upload (storage_key, user_id, purpose, content_type, bytes, created_at)
              VALUES (:key, :user, :purpose, :contentType, :bytes, :createdAt)
              ON CONFLICT (storage_key) DO NOTHING
              """)
          .param("key", r.key())
          .param("user", r.user())
          .param("purpose", r.purpose())
          .param("contentType", r.contentType())
          .param("bytes", r.bytes())
          .param("createdAt", r.createdAt())
          .update();
    }
  }

  /** 차지한 업로드 기록. 돌려놓을 때 그대로 다시 넣으려고 행 전체를 든다. */
  public record Claim(List<Row> rows) {
    public static final Claim NONE = new Claim(List.of());

    /** 차지한 키. */
    public Set<String> keys() {
      Set<String> out = new LinkedHashSet<>();
      rows.forEach(r -> out.add(r.key()));
      return out;
    }
  }

  /** {@code photo_upload} 한 줄. */
  public record Row(
      String key, UUID user, String purpose, String contentType, long bytes, Timestamp createdAt) {}
}
