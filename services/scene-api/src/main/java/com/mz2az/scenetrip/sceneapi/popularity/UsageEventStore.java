package com.mz2az.scenetrip.sceneapi.popularity;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 사용 기록의 원본(계약 {@code POST /events}, 계획 {@code popularity.md} §6) — 상세 조회와 확정한 검색.
 *
 * <p>날짜는 한국 시간이다. 「같은 기기·같은 대상·같은 날은 한 번」 을 표의 유일 제약이 지킨다 — 두 번째는 조용히 버린다.
 */
@Repository
public class UsageEventStore {

  /** 조회 대상. 계약 {@code UsageEntityType} 의 값과 같다. */
  public enum Entity {
    CONTENT("content"),
    PLACE("place"),
    POI("poi");

    final String value;

    Entity(String value) {
      this.value = value;
    }
  }

  private final JdbcClient jdbc;

  public UsageEventStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 상세 조회 하나를 적는다. 대상이 없으면 적지 않는다 — 대상에 FK 가 없어(세 표를 가리킨다) 여기서 거른다.
   *
   * @param userId 가입 사용자. 비회원이면 {@code null}
   * @return 새로 적었으면 참. 오늘 이미 적혔거나 대상이 없으면 거짓
   */
  public boolean recordView(
      Entity entity, long entityId, UUID installId, UUID userId, String lang) {
    String table =
        switch (entity) {
          case CONTENT -> "content";
          case PLACE -> "place";
          case POI -> "poi";
        };
    // 표 이름은 위 enum 에서만 온다 — 사용자 입력이 아니다.
    int rows =
        jdbc.sql(
                """
                INSERT INTO entity_view (entity_type, entity_id, install_id, user_id, lang, day)
                SELECT :type, :id, :installId, :userId, :lang, (now() AT TIME ZONE 'Asia/Seoul')::date
                WHERE EXISTS (SELECT 1 FROM %s WHERE id = :id)
                ON CONFLICT ON CONSTRAINT entity_view_once_a_day DO NOTHING
                """
                    .formatted(table))
            .param("type", entity.value)
            .param("id", entityId)
            .param("installId", installId)
            .param("userId", userId)
            .param("lang", lang)
            .update();
    return rows == 1;
  }

  /** 확정한 검색 하나를 적는다. 기기·사용자와 잇지 않는다. */
  public void recordSearch(String query, int resultCount, String lang) {
    jdbc.sql("INSERT INTO search_log (query, result_count, lang) VALUES (:query, :count, :lang)")
        .param("query", query)
        .param("count", resultCount)
        .param("lang", lang)
        .update();
  }
}
