package com.mz2az.scenetrip.sceneapi.search;

import com.mz2az.scenetrip.sceneapi.api.model.EntityType;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.Suggestion;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 자동완성 제안 조회.
 *
 * <p>모든 것이 {@code search_term} 하나에서 나온다. 장소명·장소별칭·작품명·작품별칭·인물명 다섯 곳을 합쳐 둔 MATERIALIZED VIEW 라, 조회 한
 * 번이면 네 종류가 모두 걸린다. 각 행이 {@code entity_type} 과 {@code entity_id} 를 들고 있으므로 프론트는 눌린 항목이 어디로 가는지 추가
 * 조회 없이 안다.
 *
 * <p><b>이름과 별칭만 본다. 설명·줄거리는 보지 않는다.</b> 자동완성은 한 줄짜리 제안이라 "줄거리 어딘가에 그 낱말이 있다" 를 보여 줄 방법이 없고, {@code
 * term_norm} 의 정규화(공백·구두점 제거)는 문장에 맞지 않는다.
 */
@Repository
public class SuggestionStore {

  /**
   * 걸린 항목과 그 대표 이름을 한 번에 가져온다.
   *
   * <p>질의를 나누지 않은 이유: 자동완성은 타자 한 번마다 불리는 가장 뜨거운 경로다. "걸린 id 를 찾고 → 종류별로 이름을 조회" 로 나누면 왕복이 네 번이 된다.
   *
   * <p>정규화는 {@code search_normalize()} 를 부른다. 색인을 만들 때와 검색할 때 규칙이 다르면 오류 없이 결과가 0 건이 되므로, 양쪽이 DB 의
   * 같은 함수를 쓴다.
   *
   * <p>후보를 고르는 규칙은 세 단계다.
   *
   * <ol>
   *   <li>부분 일치로 넓게 거른다 — {@code gin_trgm_ops} 인덱스가 받는다
   *   <li>같은 엔티티가 여러 표기로 걸리면 하나만 남긴다. 요청한 언어 &gt; 앞글자 일치 &gt; 가중치 순
   *   <li>남은 것을 앞글자 일치 우선, 그다음 가중치 순으로 정렬한다
   * </ol>
   *
   * <p>언어 조건에 {@code lang IS NULL} 이 들어가는 이유: 라틴 문자 별칭은 언어를 판별할 수 없어 {@code NULL} 로 적재된다. 빠뜨리면
   * {@code Goblin} 이 검색되지 않는다. {@code 'ko'} 도 함께 보는 이유는 장소명이 한국어뿐이기 때문이다 — 영어 사용자에게 한국어 장소명이라도 보여
   * 주는 편이 빈 목록보다 낫다.
   */
  private static final String SUGGEST_SQL =
      """
      WITH params AS (
          SELECT search_normalize(:q) AS norm
      ),
      matched AS (
          SELECT DISTINCT ON (st.entity_type, st.entity_id)
              st.entity_type,
              st.entity_id,
              st.term_display,
              st.term_norm LIKE p.norm || '%' AS is_prefix,
              st.weight
          FROM search_term st
          CROSS JOIN params p
          WHERE p.norm <> ''
            AND st.term_norm LIKE '%' || p.norm || '%'
            AND (st.lang = :lang OR st.lang IS NULL OR st.lang = 'ko')
          ORDER BY st.entity_type,
                   st.entity_id,
                   (st.lang = :lang) DESC NULLS LAST,
                   (st.term_norm LIKE p.norm || '%') DESC,
                   st.weight DESC
      ),
      -- 아래 셋은 "요청한 언어가 있으면 그것, 없으면 ko" 로 표시용 한 줄씩을 고른다.
      -- 걸린 id 로 미리 좁혀 두어야 전체 테이블을 훑지 않는다.
      place_display AS (
          SELECT DISTINCT ON (pi.place_id)
              pi.place_id,
              pi.name,
              pi.lang = :lang AS in_requested_lang,
              -- 장소의 보조 문구는 주소의 시·구다. '서울 마포구 독막로2길 9' -> '서울 마포구'
              NULLIF(btrim(split_part(pi.address, ' ', 1) || ' ' || split_part(pi.address, ' ', 2)), '')
                  AS subtitle
          FROM place_i18n pi
          WHERE pi.lang IN (:lang, 'ko')
            AND pi.place_id IN (SELECT entity_id FROM matched WHERE entity_type = 'place')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC
      ),
      content_display AS (
          SELECT DISTINCT ON (ci.content_id)
              ci.content_id,
              ci.title AS name,
              ci.lang = :lang AS in_requested_lang,
              -- 작품의 보조 문구는 '방송사 · 연도'. 둘 다 없으면 NULL 이다.
              NULLIF(concat_ws(' · ', c.broadcaster, c.release_year::TEXT), '') AS subtitle
          FROM content_i18n ci
          JOIN content c ON c.id = ci.content_id
          WHERE ci.lang IN (:lang, 'ko')
            AND ci.content_id IN (SELECT entity_id FROM matched WHERE entity_type = 'content')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      ),
      person_display AS (
          SELECT DISTINCT ON (pi.person_id)
              pi.person_id,
              pi.name,
              pi.lang = :lang AS in_requested_lang,
              -- 인물의 보조 문구는 대표 작품 — 가장 인기 있는 참여작이다.
              (SELECT ci.title
                 FROM content_cast cc
                 JOIN content c ON c.id = cc.content_id
                 JOIN content_i18n ci ON ci.content_id = c.id AND ci.lang IN (:lang, 'ko')
                WHERE cc.person_id = pi.person_id
                ORDER BY c.popularity_score DESC, (ci.lang = :lang) DESC
                LIMIT 1) AS subtitle
          FROM person_i18n pi
          WHERE pi.lang IN (:lang, 'ko')
            AND pi.person_id IN (SELECT entity_id FROM matched WHERE entity_type = 'person')
          ORDER BY pi.person_id, (pi.lang = :lang) DESC
      )
      SELECT
          m.entity_type,
          m.entity_id,
          m.term_display,
          COALESCE(pd.name, cd.name, sd.name) AS display_name,
          COALESCE(pd.subtitle, cd.subtitle, sd.subtitle) AS subtitle,
          COALESCE(pd.in_requested_lang, cd.in_requested_lang, sd.in_requested_lang, FALSE)
              AS in_requested_lang
      FROM matched m
      LEFT JOIN place_display   pd ON m.entity_type = 'place'   AND pd.place_id   = m.entity_id
      LEFT JOIN content_display cd ON m.entity_type = 'content' AND cd.content_id = m.entity_id
      LEFT JOIN person_display  sd ON m.entity_type = 'person'  AND sd.person_id  = m.entity_id
      -- 이름을 찾지 못한 항목은 버린다. 색인과 원본이 어긋난 상태(적재 도중 등)이고,
      -- 이름 없는 제안은 화면에 띄울 수 없다.
      WHERE COALESCE(pd.name, cd.name, sd.name) IS NOT NULL
      ORDER BY m.is_prefix DESC, m.weight DESC, display_name
      LIMIT :limit
      """;

  private final JdbcClient jdbc;

  SuggestionStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 제안과, 그중 하나라도 요청한 언어로 나왔는지를 함께 돌려준다. */
  public record Result(List<Suggestion> items, boolean anyInRequestedLang) {}

  public Result suggest(String q, Lang lang, int limit) {
    List<Row> rows =
        jdbc.sql(SUGGEST_SQL)
            .param("q", q)
            .param("lang", lang.getValue())
            .param("limit", limit)
            .query(
                (rs, rowNum) ->
                    new Row(
                        rs.getString("entity_type"),
                        rs.getLong("entity_id"),
                        rs.getString("display_name"),
                        rs.getString("term_display"),
                        rs.getString("subtitle"),
                        rs.getBoolean("in_requested_lang")))
            .list();

    List<Suggestion> items =
        rows.stream()
            .map(
                r ->
                    new Suggestion(EntityType.fromValue(r.entityType()), r.entityId(), r.name())
                        // 걸린 표기가 표시 이름과 같으면 굳이 내려보내지 않는다. 화면에
                        // 같은 글자를 두 번 보여 줄 이유가 없다.
                        .matchedTerm(r.name().equals(r.matchedTerm()) ? null : r.matchedTerm())
                        .subtitle(r.subtitle()))
            .toList();

    return new Result(items, rows.stream().anyMatch(Row::inRequestedLang));
  }

  private record Row(
      String entityType,
      long entityId,
      String name,
      String matchedTerm,
      String subtitle,
      boolean inRequestedLang) {}
}
