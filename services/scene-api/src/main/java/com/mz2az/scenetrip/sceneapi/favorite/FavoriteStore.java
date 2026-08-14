package com.mz2az.scenetrip.sceneapi.favorite;

import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 작품 찜 — 하트.
 *
 * <p><b>장소 장바구니와 별개 표다.</b> 8/11 회의가 작품엔 찜, 장소엔 장바구니로 갈랐다. 합치면 대상 종류를 나타내는 칸이 생겨 FK 를 못 걸고, 장바구니에만
 * 있는 {@code source_content_id} 때문에 컬럼 구성도 다르다.
 *
 * <p>주체는 {@code app_user.id} 다. {@code X-Device-Id} 를 계정으로 바꾸는 일은 {@link
 * com.mz2az.scenetrip.sceneapi.user.UserStore} 가 한다.
 *
 * <p><b>담기와 빼기가 멱등이다.</b> 하트는 토글이라 같은 상태를 두 번 요청하는 일이 흔하고, 그때마다 오류를 내면 프론트가 사용자에게 보여 줄 것이 없다.
 * 장바구니({@code POST /cart/items})가 409 를 내는 것과 갈리는 지점이다.
 */
@Repository
public class FavoriteStore {

  /**
   * 찜한 작품 목록 — 최근에 찜한 것부터.
   *
   * <p>모양을 {@code /contents} 와 같은 {@code ContentSummary} 로 맞춘다. 찜 목록도 화면에서는 작품 카드라, 다른 모양을 주면 프론트가
   * 같은 카드를 두 벌 만들어야 한다.
   *
   * <p>{@code place_count} 는 그 작품의 촬영지 수다. 카드에 "성지 12곳" 으로 뜬다.
   */
  private static final String LIST_SQL =
      """
      WITH display AS (
          SELECT DISTINCT ON (ci.content_id)
              ci.content_id, ci.title, ci.lang = :lang AS in_requested_lang
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      )
      SELECT
          c.id, c.category, c.broadcaster, c.poster_url, c.release_year, c.genres,
          d.title, d.in_requested_lang,
          (SELECT count(*) FROM place_content pc WHERE pc.content_id = c.id) AS place_count,
          count(*) OVER () AS total_count
      FROM saved_content s
      JOIN content c ON c.id = s.content_id
      JOIN display d ON d.content_id = c.id
      WHERE s.user_id = CAST(:userId AS UUID)
      ORDER BY s.created_at DESC, c.id DESC
      LIMIT :limit OFFSET :offset
      """;

  private final JdbcClient jdbc;

  /** 다른 패키지의 통합 테스트가 직접 만들 수 있어야 해서 public 이다. */
  public FavoriteStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 목록과 전체 개수, 그리고 요청한 언어로 채워졌는지. */
  public record Page(List<ContentSummary> items, int total, boolean anyInRequestedLang) {}

  public Page list(UUID userId, Lang lang, int limit, int offset) {
    List<Row> rows =
        jdbc.sql(LIST_SQL)
            .param("userId", userId.toString())
            .param("lang", lang.getValue())
            .param("limit", limit)
            .param("offset", offset)
            .query(FavoriteStore::mapRow)
            .list();

    return new Page(
        rows.stream().map(Row::summary).toList(),
        rows.isEmpty() ? 0 : rows.get(0).total(),
        rows.stream().anyMatch(Row::inRequestedLang));
  }

  /**
   * 찜한다.
   *
   * <p>{@code ON CONFLICT DO NOTHING} 이 멱등을 만든다. "있는지 보고 없으면 넣기" 로 하면 하트를 빠르게 두 번 눌렀을 때 두 조회가 모두
   * "없음" 을 보고 둘 다 들어가려다 기본키에서 터진다.
   */
  public void add(UUID userId, long contentId) {
    jdbc.sql(
            """
            INSERT INTO saved_content (user_id, content_id)
            VALUES (CAST(:userId AS UUID), :contentId)
            ON CONFLICT DO NOTHING
            """)
        .param("userId", userId.toString())
        .param("contentId", contentId)
        .update();
  }

  /** 찜을 뺀다. 찜하지 않은 것을 빼도 오류가 아니다 — 결과가 같기 때문이다. */
  public void remove(UUID userId, long contentId) {
    jdbc.sql(
            "DELETE FROM saved_content"
                + " WHERE user_id = CAST(:userId AS UUID) AND content_id = :contentId")
        .param("userId", userId.toString())
        .param("contentId", contentId)
        .update();
  }

  /** 그 작품이 있는가. 없는 작품을 찜하려 하면 404 다 — 외래키 위반을 500 으로 내보내지 않는다. */
  public boolean contentExists(long contentId) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM content WHERE id = :id)")
            .param("id", contentId)
            .query(Boolean.class)
            .single());
  }

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    ContentSummary summary =
        new ContentSummary(
                rs.getLong("id"),
                ContentCategory.fromValue(rs.getString("category")),
                rs.getString("title"),
                rs.getInt("place_count"))
            .posterUrl(uri(rs.getString("poster_url")))
            .broadcaster(rs.getString("broadcaster"))
            .releaseYear(integerOrNull(rs, "release_year"))
            .genres(stringArray(rs.getArray("genres")));
    return new Row(summary, rs.getInt("total_count"), rs.getBoolean("in_requested_lang"));
  }

  /** 수집한 URL 이 URI 로 파싱되지 않으면 그 필드만 비운다. 목록 전체가 500 이 되면 안 된다. */
  private static URI uri(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** {@code getInt} 는 NULL 을 0 으로 바꾼다. 연도 0 과 "연도 없음" 은 다르다. */
  private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static List<String> stringArray(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    return List.of((String[]) array.getArray());
  }

  private record Row(ContentSummary summary, int total, boolean inRequestedLang) {}
}
