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
   * <p>후보를 고르는 규칙은 네 단계다.
   *
   * <ol>
   *   <li>앞글자 일치로 찾는다 — {@code search_term_prefix_idx} 가 받는다
   *   <li>그것만으로 {@code limit} 이 차지 않고 검색어가 세 글자 이상이면 부분 일치로 보충한다
   *   <li>같은 엔티티가 여러 표기로 걸리면 하나만 남긴다. 앞글자 일치 &gt; 가중치 순
   *   <li>남은 것을 앞글자 일치 우선, 그다음 가중치 순으로 정렬한다
   * </ol>
   *
   * <p><b>두 조건을 한 {@code WHERE} 에 넣을 수 없어 갈래를 나눴다.</b> {@code LIKE '검색어%'} 는 {@code LIKE '%검색어%'} 에
   * 포함되므로, {@code AND} 로 이으면 부분 일치가 통째로 사라지고 {@code OR} 로 이으면 앞글자 조건이 삼켜져 인덱스를 놓친다. 별개의 {@code
   * SELECT} 로 떼어 {@code UNION ALL} 해야 각자 자기 조건만 갖는다.
   *
   * <p>부분 일치 갈래에 붙은 두 조건은 성능이 목적이고 결과를 바꾸지 않는다.
   *
   * <ul>
   *   <li>{@code length(...) >= 3} — {@code pg_trgm} 은 세 글자짜리 조각으로 색인한다. 검색어가 그보다 짧으면 양옆의 {@code %}
   *       때문에 조각을 만들 수 없어 인덱스가 전체 훑기가 된다. 계획 단계에서 거짓으로 접혀 스캔 노드 자체가 사라진다.
   *   <li>{@code (SELECT count(*) FROM prefix_hits) < :limit} — 앞글자만으로 화면이 찼으면 부분 일치를 실행하지 않는다. 마지막
   *       {@code ORDER BY} 가 {@code is_prefix} 를 첫 키로 쓰므로, 그 경우 부분 일치 행은 어차피 {@code LIMIT} 에 잘려 화면에
   *       오르지 못한다. 바깥 행을 참조하지 않는 조건이라 한 번만 계산되고, 거짓이면 아래 스캔을 통째로 건너뛴다.
   * </ul>
   *
   * <p><b>{@code search_normalize(:q)} 를 CTE 로 묶지 말 것.</b> 두 갈래가 함께 참조하는 순간 그 CTE 는 실체화되고, 정규화 결과가
   * 실행 시점 값이 되어 상수로 접히지 않는다. 그러면 {@code LIKE '검색어%'} 를 범위 조건으로 바꾸는 최적화가 불가능해져 앞글자 인덱스를 조용히 놓친다 —
   * 결과는 그대로고 느려지기만 한다. 함수가 {@code IMMUTABLE} 이라 몇 번을 쓰든 계획 단계에서 한 번 계산되고 끝이므로, 반복이 실행 비용을 만들지는 않는다.
   *
   * <p>언어 조건에 {@code lang IS NULL} 이 들어가는 이유: 라틴 문자 별칭은 언어를 판별할 수 없어 {@code NULL} 로 적재된다. 빠뜨리면
   * {@code Goblin} 이 검색되지 않는다. {@code 'ko'} 도 함께 보는 이유는 장소명이 한국어뿐이기 때문이다 — 영어 사용자에게 한국어 장소명이라도 보여
   * 주는 편이 빈 목록보다 낫다.
   */
  // 통합 테스트가 EXPLAIN 으로 이 질의의 계획을 확인한다. 인덱스를 놓쳐도 결과는 같고 느려지기만
  // 하므로, 결과 단언으로는 잡히지 않는 회귀다.
  static final String SUGGEST_SQL =
      """
      WITH prefix_hits AS MATERIALIZED (
          SELECT DISTINCT ON (st.entity_type, st.entity_id)
              st.entity_type,
              st.entity_id,
              st.term_display,
              TRUE AS is_prefix,
              st.weight
          FROM search_term st
          WHERE search_normalize(:q) <> ''
            AND st.term_norm LIKE search_normalize(:q) || '%'
            AND (st.lang = :lang OR st.lang IS NULL OR st.lang = 'ko')
          ORDER BY st.entity_type,
                   st.entity_id,
                   (st.lang = :lang) DESC NULLS LAST,
                   st.weight DESC
      ),
      fuzzy_hits AS (
          SELECT DISTINCT ON (st.entity_type, st.entity_id)
              st.entity_type,
              st.entity_id,
              st.term_display,
              FALSE AS is_prefix,
              st.weight
          FROM search_term st
          WHERE length(search_normalize(:q)) >= 3
            AND (SELECT count(*) FROM prefix_hits) < :limit
            AND st.term_norm LIKE '%' || search_normalize(:q) || '%'
            -- 앞글자로 걸리는 것은 위 갈래가 이미 가져갔다.
            AND st.term_norm NOT LIKE search_normalize(:q) || '%'
            AND (st.lang = :lang OR st.lang IS NULL OR st.lang = 'ko')
          ORDER BY st.entity_type,
                   st.entity_id,
                   (st.lang = :lang) DESC NULLS LAST,
                   st.weight DESC
      ),
      -- 두 갈래는 서로를 모르므로 같은 엔티티가 다른 표기로 양쪽에 걸릴 수 있다. 정식 명칭은
      -- 앞글자로, 별칭은 가운데로 걸리는 경우다. 여기서 한 번 더 걸러 엔티티당 한 줄을 지킨다.
      matched AS (
          SELECT DISTINCT ON (u.entity_type, u.entity_id)
              u.entity_type,
              u.entity_id,
              u.term_display,
              u.is_prefix,
              u.weight
          FROM (SELECT * FROM prefix_hits UNION ALL SELECT * FROM fuzzy_hits) u
          ORDER BY u.entity_type,
                   u.entity_id,
                   u.is_prefix DESC,
                   u.weight DESC
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
