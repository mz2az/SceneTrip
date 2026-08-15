package com.mz2az.scenetrip.sceneapi.content;

import com.mz2az.scenetrip.sceneapi.api.model.ContentCategory;
import com.mz2az.scenetrip.sceneapi.api.model.ContentDetail;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PersonRef;
import com.mz2az.scenetrip.sceneapi.api.model.RoleType;
import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 작품 목록·검색.
 *
 * <p>{@code q} 는 이 작품에 딸린 <b>모든 텍스트</b>에 걸린다 — 제목·별칭·작품 설명·인물 이름에 더해, 이 작품이 성지로 가진 <b>장소의
 * 이름·별칭·설명</b> 까지다. 인물로 걸린 것은 {@code content_cast} 를, 장소로 걸린 것은 {@code place_content} 를 타고 작품으로 바뀐
 * 뒤 나머지와 합쳐진다. 같은 작품이 제목과 배우 양쪽에 걸려도 한 번만 나온다.
 *
 * <p><b>{@link com.mz2az.scenetrip.sceneapi.place.PlaceStore} 와 대칭이다.</b> 검색어에 걸린 것과 이어진 작품을 이쪽이
 * 모으고, 이어진 장소를 그쪽이 모은다. 두 방향이 모두 있어야 검색어 하나가 두 탭을 함께 채운다.
 *
 * <p><b>장면 설명은 보지 않는다.</b> 장면은 장소와 작품 사이에 붙은 값이라 어느 한쪽의 검색 대상으로 삼으면 경계가 흐려진다.
 */
@Repository
public class ContentStore {

  /**
   * 이름·별칭·인물은 {@code search_term} 에서, 설명은 원본 테이블에서 찾는다.
   *
   * <p>둘을 나눈 이유는 정규화가 다르기 때문이다. {@code term_norm} 은 공백·구두점을 지운 형태라 짧은 이름에 맞고, 문장에 같은 규칙을 적용하면 앞글자
   * 매칭이 의미를 잃는다. 설명은 원문 그대로 부분 문자열로 훑는다 — 한국어는 조사가 붙어("한강<b>에서</b>") 전문검색이 잘 잡지 못하므로 {@code
   * pg_trgm} 의 부분 일치가 오히려 맞는다.
   *
   * <p>{@code count(*) OVER ()} 는 {@code LIMIT} 을 적용하기 <b>전</b>의 전체 개수를 준다. 개수를 따로 세는 질의를 한 번 더 보내지
   * 않아도 되고, 두 질의 사이에 데이터가 바뀌어 {@code total} 과 {@code items} 가 어긋나는 일도 없다.
   */
  private static final String LIST_SQL =
      """
      -- 파라미터에 ::TEXT 같은 캐스팅이 붙은 이유:
      --
      -- 값이 NULL 인 채로 `:q IS NULL` 처럼 쓰이면 PostgreSQL 이 그 자리의 타입을
      -- 추론할 근거가 없어 "could not determine data type of parameter" 로 질의가
      -- 통째로 실패한다. 필터를 걸지 않는 경우(q 없이 목록 조회)가 정확히 그 상황이다.
      -- 리터럴과 비교되는 자리는 추론이 되지만, NULL 비교만 있는 자리는 우리가 알려 줘야 한다.
      --
      -- PostgreSQL 의 `::TEXT` 대신 `CAST(... AS TEXT)` 를 쓴다. Spring 의 이름 파라미터
      -- 파서가 `:q::TEXT` 를 만나면 캐스팅의 콜론까지 파라미터 이름의 일부로 보아
      -- 캐스팅이 사라진다 — 문법 오류는 안 나고 타입만 조용히 빠진다(실측).
      WITH params AS (
          SELECT search_normalize(COALESCE(CAST(:q AS TEXT), '')) AS norm
      ),
      matched AS (
          -- 작품 제목·별칭
          SELECT st.entity_id AS content_id
          FROM search_term st CROSS JOIN params p
          WHERE p.norm <> '' AND st.entity_type = 'content'
            AND st.term_norm LIKE '%' || p.norm || '%'

          UNION

          -- 인물 이름 -> 그 인물의 작품
          SELECT cc.content_id
          FROM search_term st
          CROSS JOIN params p
          JOIN content_cast cc ON cc.person_id = st.entity_id
          WHERE p.norm <> '' AND st.entity_type = 'person'
            AND st.term_norm LIKE '%' || p.norm || '%'

          UNION

          -- 작품 설명
          SELECT ci.content_id
          FROM content_i18n ci CROSS JOIN params p
          WHERE p.norm <> '' AND ci.description ILIKE '%' || CAST(:q AS TEXT) || '%'

          UNION

          -- 이 작품이 성지로 가진 장소의 이름·별칭 -> 그 작품
          --
          -- '북촌한옥마을' 을 치면 거기서 촬영된 작품들이 작품 탭에 뜬다. PlaceStore 의
          -- 반대 방향이다 — 그쪽은 작품에 걸린 것을 그 작품의 촬영지로 옮기고, 여기는
          -- 장소에 걸린 것을 그 장소의 촬영작으로 옮긴다.
          SELECT pc.content_id
          FROM search_term st
          CROSS JOIN params p
          JOIN place_content pc ON pc.place_id = st.entity_id
          WHERE p.norm <> '' AND st.entity_type = 'place'
            AND st.term_norm LIKE '%' || p.norm || '%'

          UNION

          -- 이 작품이 성지로 가진 장소의 설명 -> 그 작품
          --
          -- 설명은 search_term 에 없다. 그 뷰는 이름류만 모으므로(V3) 원본 테이블을
          -- 직접 훑는다 — 위의 '작품 설명' 분기와 같은 이유, 같은 방식이다.
          SELECT pc.content_id
          FROM place_i18n pi
          CROSS JOIN params p
          JOIN place_content pc ON pc.place_id = pi.place_id
          WHERE p.norm <> '' AND pi.description ILIKE '%' || CAST(:q AS TEXT) || '%'

          UNION

          -- 이 작품의 장면 설명
          --
          -- '2화에서 지은탁이 등장하는 버스정류장' 처럼 어느 작품의 어느 장면이 어디서
          -- 찍혔는가를 적은 문장이다. 작품에도 장소에도 속하지 않고 둘을 잇는 자리에
          -- 붙어 있어 place_content 를 한 번 타고 온다. PlaceStore 의 같은 분기와
          -- 짝이다 — 그쪽은 place_id 를, 여기는 content_id 를 꺼낸다.
          --
          -- **실제로 값이 차 있는 유일한 설명이다.** 위 두 분기가 보는 description 은
          -- 수집이 안 돼 전부 NULL 이라, 이것이 붙기 전에는 설명으로 걸리는 검색이
          -- 하나도 없었다 (MZ2AZ-263).
          SELECT pc.content_id
          FROM place_content_i18n pci
          JOIN place_content pc ON pc.id = pci.place_content_id
          CROSS JOIN params p
          WHERE p.norm <> ''
            AND pci.relation_description ILIKE '%' || CAST(:q AS TEXT) || '%'
      ),
      display AS (
          SELECT DISTINCT ON (ci.content_id)
              ci.content_id, ci.title, ci.lang = :lang AS in_requested_lang
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      )
      SELECT
          c.id, c.category, c.poster_url, c.broadcaster, c.release_year, c.genres,
          d.title, d.in_requested_lang,
          (SELECT count(*) FROM place_content pc WHERE pc.content_id = c.id) AS place_count,
          count(*) OVER () AS total_count
      FROM content c
      JOIN display d ON d.content_id = c.id
      WHERE (CAST(:q AS TEXT) IS NULL OR c.id IN (SELECT content_id FROM matched))
        AND (CAST(:personId AS BIGINT) IS NULL
             OR EXISTS (SELECT 1 FROM content_cast cc
                         WHERE cc.content_id = c.id AND cc.person_id = CAST(:personId AS BIGINT)))
        AND (CAST(:category AS TEXT) IS NULL OR c.category = CAST(:category AS TEXT))
      -- id 를 함께 정렬 키로 두는 이유: 인기도가 같은 작품들의 순서가 고정되지 않으면
      -- offset 으로 다음 장을 받을 때 같은 항목이 두 번 나오거나 건너뛴다.
      ORDER BY c.popularity_score DESC, c.id DESC
      LIMIT :limit OFFSET :offset
      """;

  private final JdbcClient jdbc;

  ContentStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 목록과 함께, 조건에 맞는 전체 개수와 언어 폴백 여부를 돌려준다. */
  public record Page(List<ContentSummary> items, int total, boolean anyInRequestedLang) {}

  public Page list(
      String q, Long personId, ContentCategory category, Lang lang, int limit, int offset) {
    List<Row> rows =
        jdbc.sql(LIST_SQL)
            .param("q", q)
            .param("personId", personId)
            .param("category", category == null ? null : category.getValue())
            .param("lang", lang.getValue())
            .param("limit", limit)
            .param("offset", offset)
            .query(ContentStore::mapRow)
            .list();

    return new Page(
        rows.stream().map(Row::summary).toList(),
        rows.isEmpty() ? 0 : rows.get(0).total(),
        rows.stream().anyMatch(Row::inRequestedLang));
  }

  /**
   * 작품 상세.
   *
   * <p>목록과 다른 것은 줄거리·별칭·출연진이 붙는 것뿐이다. 촬영지 목록은 여기 넣지 않는다 — 개수가 많고 페이지네이션이 필요해서 별도 조회다 ({@code GET
   * /contents/&#123;id&#125;/places}).
   */
  private static final String DETAIL_SQL =
      """
      WITH display AS (
          SELECT DISTINCT ON (ci.content_id)
              ci.content_id, ci.title, ci.description, ci.lang,
              ci.lang = :lang AS in_requested_lang
          FROM content_i18n ci
          WHERE ci.content_id = :id AND ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      )
      SELECT
          c.id, c.category, c.poster_url, c.broadcaster, c.release_year, c.genres,
          d.title, d.description, d.lang AS display_lang, d.in_requested_lang,
          (SELECT count(*) FROM place_content pc WHERE pc.content_id = c.id) AS place_count
      FROM content c
      JOIN display d ON d.content_id = c.id
      WHERE c.id = :id
      """;

  /**
   * 다른 표기 전부.
   *
   * <p>별칭 테이블만 보면 안 된다. 적재할 때 영문 표기 하나를 {@code content_i18n} 의 {@code en} 제목으로 승격했으므로, 별칭 테이블에는 그것이
   * 없다. 명세의 예시가 {@code ["Guardian: The Lonely and Great God", "Goblin"]} 인데 앞의 것이 바로 승격된 영문 제목이다.
   *
   * <p>지금 화면에 보이는 제목은 뺀다. 같은 글자를 제목과 별칭 두 곳에 보여 줄 이유가 없다.
   */
  private static final String ALIASES_SQL =
      """
      SELECT DISTINCT term FROM (
          SELECT ci.title AS term FROM content_i18n ci
           WHERE ci.content_id = :id AND ci.lang <> CAST(:displayLang AS TEXT)
          UNION
          SELECT ca.alias FROM content_alias ca WHERE ca.content_id = :id
      ) t
      WHERE term <> CAST(:displayTitle AS TEXT)
      ORDER BY term
      """;

  /** 출연진. 배우를 감독보다 앞에 둔다 — 목업의 작품 카드가 먼저 보여 주는 것이 배우다. */
  private static final String CAST_SQL =
      """
      WITH display AS (
          SELECT DISTINCT ON (pi.person_id) pi.person_id, pi.name
          FROM person_i18n pi
          WHERE pi.lang IN (:lang, 'ko')
            AND pi.person_id IN (SELECT person_id FROM content_cast WHERE content_id = :id)
          ORDER BY pi.person_id, (pi.lang = :lang) DESC
      )
      SELECT cc.person_id, d.name, cc.role_type
      FROM content_cast cc
      JOIN display d ON d.person_id = cc.person_id
      WHERE cc.content_id = :id
      ORDER BY (cc.role_type = 'actor') DESC, cc.sort_order NULLS LAST, cc.person_id
      """;

  /** 상세와 언어 폴백 여부. 없으면 {@link Optional#empty()}. */
  public record Detail(ContentDetail content, boolean inRequestedLang) {}

  public Optional<Detail> findDetail(long contentId, Lang lang) {
    List<DetailRow> rows =
        jdbc.sql(DETAIL_SQL)
            .param("id", contentId)
            .param("lang", lang.getValue())
            .query(ContentStore::mapDetail)
            .list();

    if (rows.isEmpty()) {
      return Optional.empty();
    }
    DetailRow row = rows.get(0);
    ContentDetail detail = row.content();

    detail.setAliases(
        jdbc.sql(ALIASES_SQL)
            .param("id", contentId)
            .param("displayLang", row.displayLang())
            .param("displayTitle", detail.getTitle())
            .query(String.class)
            .list());

    detail.setCast(
        jdbc.sql(CAST_SQL)
            .param("id", contentId)
            .param("lang", lang.getValue())
            .query(
                (rs, rowNum) ->
                    new PersonRef(
                        rs.getLong("person_id"),
                        rs.getString("name"),
                        RoleType.fromValue(rs.getString("role_type"))))
            .list());

    return Optional.of(new Detail(detail, row.inRequestedLang()));
  }

  private static DetailRow mapDetail(ResultSet rs, int rowNum) throws SQLException {
    ContentDetail detail =
        new ContentDetail(
                rs.getLong("id"),
                ContentCategory.fromValue(rs.getString("category")),
                rs.getString("title"),
                rs.getInt("place_count"))
            .posterUrl(uri(rs.getString("poster_url")))
            .broadcaster(rs.getString("broadcaster"))
            .releaseYear(integerOrNull(rs, "release_year"))
            .genres(stringArray(rs.getArray("genres")))
            .description(rs.getString("description"));
    return new DetailRow(detail, rs.getString("display_lang"), rs.getBoolean("in_requested_lang"));
  }

  private record DetailRow(ContentDetail content, String displayLang, boolean inRequestedLang) {}

  /**
   * 그 작품이 있는가.
   *
   * <p>{@code GET /contents/&#123;id&#125;/places} 가 이것을 먼저 본다. 없는 작품의 촬영지를 조회하면 빈 목록이 나오는데, 그것은
   * "촬영지가 아직 없는 작품" 과 구별되지 않는다. 앞의 것은 404 여야 하고 뒤의 것은 빈 배열이 맞다.
   */
  public boolean exists(long contentId) {
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

  /**
   * 수집한 URL 이 URI 로 파싱되지 않으면 그 필드만 비운다.
   *
   * <p>정제 전 데이터라 공백이나 한글이 든 URL 이 섞여 있을 수 있다. 그것 하나 때문에 목록 전체가 500 이 되면, 다른 멀쩡한 작품까지 화면에 뜨지 않는다.
   */
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
