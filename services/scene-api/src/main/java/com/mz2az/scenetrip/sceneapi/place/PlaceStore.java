package com.mz2az.scenetrip.sceneapi.place;

import com.mz2az.scenetrip.sceneapi.api.model.ContentRef;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Scene;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 촬영지 목록·검색·지도 조회.
 *
 * <p>엔드포인트 하나가 목업의 세 상황을 모두 담당한다 — 장소 탭 검색({@code q}), 지도 뷰포트 안의 핀({@code bbox}), 현 지도 내 성지
 * 검색({@code lat}·{@code lng}·{@code radiusMeters}). 조건이 붙는 자리만 다르고 나머지는 같은 질의다.
 *
 * <p>{@code q} 는 이 장소에 딸린 <b>모든 텍스트</b>에 걸린다 — 장소명·별칭·장소 설명에 더해, 이 장소에서 촬영된 작품의 제목·별칭·설명과 그 작품에 참여한
 * 인물의 이름까지다.
 *
 * <p><b>{@link com.mz2az.scenetrip.sceneapi.content.ContentStore} 와 대칭이다.</b> 검색어에 걸린 것과 이어진 장소를
 * 이쪽이 모으고, 이어진 작품을 그쪽이 모은다. 사용자는 검색 전에 탭을 고르지 않으므로 검색어 하나가 두 탭을 모두 채워야 한다 — 한쪽만 넓게 걸리면 반대쪽이 빈 화면이
 * 되어 고장으로 보인다.
 *
 * <p><b>다리는 하나만 건넌다.</b> 작품에 걸려 그 작품의 촬영지까지는 오지만, 그 촬영지에 걸린 또 다른 작품의 촬영지까지 가지는 않는다. 무한히 번지면 결과가 사실상
 * 전체 목록이 되어 검색이 뜻을 잃는다.
 */
@Repository
public class PlaceStore {

  /** 정렬 기준. 명세의 {@code sort} 파라미터에 대응한다. */
  public enum Sort {
    POPULARITY,
    DISTANCE
  }

  /** 조회 조건. 컨트롤러가 명세의 규칙을 검사한 뒤 만들어 넘긴다. */
  public record Criteria(
      String q,
      Long contentId,
      Bbox bbox,
      Double lat,
      Double lng,
      Integer radiusMeters,
      Sort sort,
      Lang lang,
      int limit,
      int offset) {

    boolean hasOrigin() {
      return lat != null && lng != null;
    }
  }

  /** 목록과 함께 전체 개수·언어 폴백 여부를 돌려준다. */
  public record Page(List<PlaceSummary> items, int total, boolean anyInRequestedLang) {}

  /**
   * 정렬 구문만 코드에서 갈아 끼운다.
   *
   * <p>사용자 입력을 SQL 에 이어 붙이는 것이 아니다 — {@link Sort} 는 이미 검증된 enum 이고, 여기서 넣는 것은 이 파일 안에 적힌 상수 문자열 둘 중
   * 하나뿐이다. 정렬 방향은 파라미터로 표현할 수 없어(플레이스홀더는 값 자리에만 온다) 이 방법이 필요하다.
   *
   * <p>거리순에도 인기도를 뒤에 두는 이유: 같은 건물의 두 장소처럼 거리가 같은 경우가 실제로 있고, 순서가 흔들리면 offset 페이지네이션에서 항목이 겹치거나 빠진다.
   */
  private static final String ORDER_BY_POPULARITY = "p.popularity_score DESC, p.id DESC";

  private static final String ORDER_BY_DISTANCE =
      "distance_meters ASC NULLS LAST, p.popularity_score DESC, p.id DESC";

  private static final String LIST_SQL =
      """
      WITH params AS (
          SELECT search_normalize(COALESCE(CAST(:q AS TEXT), '')) AS norm
      ),
      -- 검색어에 걸린 것과 이어진 장소를 전부 모은다.
      --
      -- 직접 걸린 장소(이름·설명)에 더해, 작품이나 인물에 걸렸으면 그 작품의 촬영지를
      -- 함께 담는다. ContentStore 가 반대 방향으로 같은 일을 하므로, 검색어 하나가
      -- 두 탭을 모두 채운다.
      --
      -- **한 다리만 건넌다.** 작품에 걸려 그 작품의 촬영지까지는 오지만, 그 촬영지에
      -- 걸린 또 다른 작품의 촬영지까지 가지는 않는다. 무한히 번지면 결과가 사실상
      -- 전체 목록이 되어 검색이 뜻을 잃는다.
      matched AS (
          -- 장소명·장소 별칭
          SELECT st.entity_id AS place_id
          FROM search_term st CROSS JOIN params p
          WHERE p.norm <> '' AND st.entity_type = 'place'
            AND st.term_norm LIKE '%' || p.norm || '%'

          UNION

          -- 장소 설명
          --
          -- 설명은 search_term 에 없다. 그 뷰는 이름류만 모으므로(V3) 원본을 직접
          -- 훑는다. term_norm 은 공백·구두점을 지운 형태라 짧은 이름에는 맞지만
          -- 문장에 쓰면 뜻을 잃는다.
          SELECT pi.place_id
          FROM place_i18n pi CROSS JOIN params p
          WHERE p.norm <> '' AND pi.description ILIKE '%' || CAST(:q AS TEXT) || '%'

          UNION

          -- 이 장소에서 촬영된 작품의 제목·별칭
          SELECT pc.place_id
          FROM search_term st
          CROSS JOIN params p
          JOIN place_content pc ON pc.content_id = st.entity_id
          WHERE p.norm <> '' AND st.entity_type = 'content'
            AND st.term_norm LIKE '%' || p.norm || '%'

          UNION

          -- 이 장소에서 촬영된 작품의 설명
          SELECT pc.place_id
          FROM content_i18n ci
          CROSS JOIN params p
          JOIN place_content pc ON pc.content_id = ci.content_id
          WHERE p.norm <> '' AND ci.description ILIKE '%' || CAST(:q AS TEXT) || '%'

          UNION

          -- 그 작품에 참여한 인물 -> 작품 -> 장소
          --
          -- 배우 이름으로 장소 탭이 채워지는 경로다. 없으면 '공유' 를 쳤을 때 작품
          -- 탭에는 도깨비가 뜨는데 장소 탭만 비어, 사용자에게는 고장으로 보인다.
          SELECT pc.place_id
          FROM search_term st
          CROSS JOIN params p
          JOIN content_cast cc ON cc.person_id = st.entity_id
          JOIN place_content pc ON pc.content_id = cc.content_id
          WHERE p.norm <> '' AND st.entity_type = 'person'
            AND st.term_norm LIKE '%' || p.norm || '%'

          UNION

          -- 여기서 찍힌 장면의 설명
          --
          -- '2화에서 지은탁이 등장하는 버스정류장' 처럼 어느 작품의 어느 장면이 여기서
          -- 찍혔는가를 적은 문장이다. 작품에도 장소에도 속하지 않고 그 둘을 잇는 자리에
          -- 붙어 있어, 위의 두 설명 분기와 달리 place_content 를 한 번 타고 온다.
          --
          -- **실제로 값이 차 있는 유일한 설명이다.** place_i18n·content_i18n 의
          -- description 은 수집이 안 돼 전부 NULL 이라, 이 분기가 붙기 전에는 설명으로
          -- 걸리는 검색이 하나도 없었다 (MZ2AZ-263).
          SELECT pc.place_id
          FROM place_content_i18n pci
          JOIN place_content pc ON pc.id = pci.place_content_id
          CROSS JOIN params p
          WHERE p.norm <> ''
            AND pci.relation_description ILIKE '%' || CAST(:q AS TEXT) || '%'
      ),
      display AS (
          SELECT DISTINCT ON (pi.place_id)
              pi.place_id, pi.name, pi.address, pi.lang = :lang AS in_requested_lang
          FROM place_i18n pi
          WHERE pi.lang IN (:lang, 'ko')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC
      ),
      origin AS (
          -- 기준점이 없으면 NULL 이고, 그러면 거리 계산과 반경 조건이 통째로 빠진다.
          SELECT CASE
                     WHEN CAST(:lat AS DOUBLE PRECISION) IS NULL THEN NULL
                     ELSE ST_SetSRID(
                              ST_MakePoint(CAST(:lng AS DOUBLE PRECISION),
                                           CAST(:lat AS DOUBLE PRECISION)),
                              4326)::geography
                 END AS point
      )
      SELECT
          p.id,
          d.name,
          p.type,
          d.address,
          d.in_requested_lang,
          -- geography 를 geometry 로 캐스팅해야 좌표를 꺼낼 수 있다. ST_X 가 경도, ST_Y 가 위도다.
          ST_Y(p.geom::geometry) AS latitude,
          ST_X(p.geom::geometry) AS longitude,
          (SELECT pim.url FROM place_image pim
            WHERE pim.place_id = p.id
            ORDER BY pim.sort_order, pim.id LIMIT 1) AS image_url,
          CASE WHEN o.point IS NULL THEN NULL
               ELSE round(ST_Distance(p.geom, o.point))::INT END AS distance_meters,
          count(*) OVER () AS total_count
      FROM place p
      JOIN display d ON d.place_id = p.id
      CROSS JOIN origin o
      WHERE (CAST(:q AS TEXT) IS NULL OR p.id IN (SELECT place_id FROM matched))
        AND (CAST(:contentId AS BIGINT) IS NULL
             OR EXISTS (SELECT 1 FROM place_content pc
                         WHERE pc.place_id = p.id
                           AND pc.content_id = CAST(:contentId AS BIGINT)))
        -- 뷰포트는 geometry 로 본다. geography 로 하면 경도 -180~180 사각형에서
        -- "Antipodal (180 degrees long) edge detected!" 로 질의가 통째로 실패한다 —
        -- 구면 위에서는 180 도짜리 간선이 어느 쪽으로 도는지 정할 수 없다(실측).
        --
        -- 뜻으로도 geometry 가 맞다. 지도의 뷰포트는 위경도 평면의 직사각형이고
        -- 클라이언트가 보내는 네 숫자가 바로 그것이다.
        --
        -- && 는 경계상자 겹침 연산자다. V6 의 표현식 인덱스가 이것을 받는다.
        AND (CAST(:minLng AS DOUBLE PRECISION) IS NULL
             OR p.geom::geometry && ST_MakeEnvelope(
                    CAST(:minLng AS DOUBLE PRECISION),
                    CAST(:minLat AS DOUBLE PRECISION),
                    CAST(:maxLng AS DOUBLE PRECISION),
                    CAST(:maxLat AS DOUBLE PRECISION),
                    4326))
        -- 반경. ST_DWithin 이 GiST 인덱스를 탄다 — ST_Distance 로 걸면 전수 계산이 된다.
        AND (CAST(:radiusMeters AS INTEGER) IS NULL
             OR o.point IS NULL
             OR ST_DWithin(p.geom, o.point, CAST(:radiusMeters AS INTEGER)))
      ORDER BY __ORDER_BY__
      LIMIT :limit OFFSET :offset
      """;

  /**
   * 각 장소에 등장한 작품들.
   *
   * <p>장소 목록과 나눈 이유: 한 장소에 작품이 여럿이라 조인하면 장소가 그 수만큼 중복되고, {@code LIMIT} 이 장소가 아니라 행에 걸려 페이지네이션이
   * 어긋난다. 앞 질의로 장소를 확정한 뒤 그 id 들로 한 번 더 부른다.
   */
  private static final String CONTENTS_SQL =
      """
      WITH display AS (
          SELECT DISTINCT ON (ci.content_id) ci.content_id, ci.title
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      ),
      scene AS (
          SELECT DISTINCT ON (pci.place_content_id)
              pci.place_content_id, pci.relation_description
          FROM place_content_i18n pci
          WHERE pci.lang IN (:lang, 'ko')
          ORDER BY pci.place_content_id, (pci.lang = :lang) DESC
      )
      SELECT pc.place_id, c.id AS content_id, d.title, c.poster_url, s.relation_description
      FROM place_content pc
      JOIN content c ON c.id = pc.content_id
      JOIN display d ON d.content_id = c.id
      LEFT JOIN scene s ON s.place_content_id = pc.id
      WHERE pc.place_id IN (:placeIds)
      ORDER BY pc.place_id, c.popularity_score DESC, c.id DESC
      """;

  /**
   * 촬영지 상세.
   *
   * <p>목록과 다른 것은 장소 설명·네이버 링크·사진 전부·장면 목록이 붙는 것뿐이다.
   *
   * <p>"이어서 걷기 좋은 곳" 은 별도 필드가 아니다. 이 장소의 좌표로 {@code GET /places?lat=&lng=&radiusMeters=1000} 을 부르면
   * 된다 — 같은 것을 두 곳에서 계산하지 않는다.
   */
  private static final String DETAIL_SQL =
      """
      WITH display AS (
          SELECT DISTINCT ON (pi.place_id)
              pi.place_id, pi.name, pi.address, pi.description,
              pi.lang = :lang AS in_requested_lang
          FROM place_i18n pi
          WHERE pi.place_id = :id AND pi.lang IN (:lang, 'ko')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC
      ),
      origin AS (
          SELECT CASE
                     WHEN CAST(:lat AS DOUBLE PRECISION) IS NULL THEN NULL
                     ELSE ST_SetSRID(
                              ST_MakePoint(CAST(:lng AS DOUBLE PRECISION),
                                           CAST(:lat AS DOUBLE PRECISION)),
                              4326)::geography
                 END AS point
      )
      SELECT
          p.id, d.name, p.type, d.address, d.description, p.naver_place_url,
          d.in_requested_lang,
          ST_Y(p.geom::geometry) AS latitude,
          ST_X(p.geom::geometry) AS longitude,
          CASE WHEN o.point IS NULL THEN NULL
               ELSE round(ST_Distance(p.geom, o.point))::INT END AS distance_meters
      FROM place p
      JOIN display d ON d.place_id = p.id
      CROSS JOIN origin o
      WHERE p.id = :id
      """;

  /** 사진 전부. 정렬 순서대로이고 첫 번째가 대표 이미지다. */
  private static final String IMAGES_SQL =
      "SELECT url FROM place_image WHERE place_id = :id ORDER BY sort_order, id";

  /**
   * 이 장소에서 촬영된 작품과 장면.
   *
   * <p>목록의 {@code sceneDescription} 과 달리 여기서는 작품을 특정하지 않아도 전부 나온다. 상세 화면은 "이 장소의 장면" 을 가로 스크롤 카드로
   * 보여 주므로 여러 작품이 나란히 있는 것이 정상이다.
   */
  private static final String SCENES_SQL =
      """
      WITH display AS (
          SELECT DISTINCT ON (ci.content_id) ci.content_id, ci.title
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      ),
      scene AS (
          SELECT DISTINCT ON (pci.place_content_id)
              pci.place_content_id, pci.relation_description
          FROM place_content_i18n pci
          WHERE pci.lang IN (:lang, 'ko')
          ORDER BY pci.place_content_id, (pci.lang = :lang) DESC
      )
      SELECT c.id AS content_id, d.title, c.poster_url, pc.scene_image_url,
             s.relation_description
      FROM place_content pc
      JOIN content c ON c.id = pc.content_id
      JOIN display d ON d.content_id = c.id
      LEFT JOIN scene s ON s.place_content_id = pc.id
      WHERE pc.place_id = :id
      ORDER BY c.popularity_score DESC, c.id DESC
      """;

  /** 상세와 언어 폴백 여부. 없으면 {@link Optional#empty()}. */
  public record Detail(PlaceDetail place, boolean inRequestedLang) {}

  private final JdbcClient jdbc;

  PlaceStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Detail> findDetail(long placeId, Lang lang, Double lat, Double lng) {
    boolean hasOrigin = lat != null && lng != null;
    List<DetailRow> rows =
        jdbc.sql(DETAIL_SQL)
            .param("id", placeId)
            .param("lang", lang.getValue())
            .param("lat", hasOrigin ? lat : null)
            .param("lng", hasOrigin ? lng : null)
            .query(PlaceStore::mapDetail)
            .list();

    if (rows.isEmpty()) {
      return Optional.empty();
    }
    DetailRow row = rows.get(0);
    PlaceDetail detail = row.place();

    detail.setImageUrls(
        jdbc.sql(IMAGES_SQL).param("id", placeId).query(String.class).list().stream()
            .map(PlaceStore::uri)
            .filter(Objects::nonNull)
            .toList());

    detail.setScenes(
        jdbc.sql(SCENES_SQL)
            .param("id", placeId)
            .param("lang", lang.getValue())
            .query(
                (rs, rowNum) ->
                    new Scene(rs.getLong("content_id"), rs.getString("title"))
                        .posterUrl(uri(rs.getString("poster_url")))
                        .sceneDescription(rs.getString("relation_description"))
                        .sceneImageUrl(uri(rs.getString("scene_image_url"))))
            .list());

    return Optional.of(new Detail(detail, row.inRequestedLang()));
  }

  private static DetailRow mapDetail(ResultSet rs, int rowNum) throws SQLException {
    PlaceDetail detail =
        new PlaceDetail(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"))
            .type(rs.getString("type"))
            .address(rs.getString("address"))
            .description(rs.getString("description"))
            .naverPlaceUrl(uri(rs.getString("naver_place_url")))
            .distanceMeters(integerOrNull(rs, "distance_meters"));
    return new DetailRow(detail, rs.getBoolean("in_requested_lang"));
  }

  private record DetailRow(PlaceDetail place, boolean inRequestedLang) {}

  public Page list(Criteria criteria) {
    // formatted() 를 쓰지 않는다. 이 SQL 에는 LIKE '%' 의 퍼센트 기호가 있어
    // 서식 문자열로 해석되면 깨진다 — ErrorProne 이 빌드에서 잡아 준 지점이다.
    String sql =
        LIST_SQL.replace(
            "__ORDER_BY__",
            criteria.sort() == Sort.DISTANCE ? ORDER_BY_DISTANCE : ORDER_BY_POPULARITY);

    List<Row> rows =
        jdbc.sql(sql)
            .param("q", criteria.q())
            .param("contentId", criteria.contentId())
            .param("lang", criteria.lang().getValue())
            .param("lat", criteria.hasOrigin() ? criteria.lat() : null)
            .param("lng", criteria.hasOrigin() ? criteria.lng() : null)
            .param("radiusMeters", criteria.radiusMeters())
            .param("minLng", criteria.bbox() == null ? null : criteria.bbox().minLng())
            .param("minLat", criteria.bbox() == null ? null : criteria.bbox().minLat())
            .param("maxLng", criteria.bbox() == null ? null : criteria.bbox().maxLng())
            .param("maxLat", criteria.bbox() == null ? null : criteria.bbox().maxLat())
            .param("limit", criteria.limit())
            .param("offset", criteria.offset())
            .query(PlaceStore::mapRow)
            .list();

    if (rows.isEmpty()) {
      return new Page(List.of(), 0, false);
    }

    attachContents(rows, criteria.lang(), criteria.contentId());

    return new Page(
        rows.stream().map(Row::summary).toList(),
        rows.get(0).total(),
        rows.stream().anyMatch(Row::inRequestedLang));
  }

  /**
   * 작품 배지와 장면 설명을 채운다.
   *
   * <p>{@code sceneDescription} 은 <b>작품을 특정했을 때만</b> 채운다. 한 장소가 여러 작품에 나오면 "어느 작품의 장면인가" 가 정해지지 않기
   * 때문이다 — 명세가 그렇게 규정한다.
   */
  private void attachContents(List<Row> rows, Lang lang, Long contentId) {
    List<Long> placeIds = rows.stream().map(r -> r.summary().getId()).toList();

    Map<Long, List<ContentRef>> byPlace = new LinkedHashMap<>();
    Map<Long, String> sceneByPlace = new LinkedHashMap<>();

    jdbc.sql(CONTENTS_SQL)
        .param("lang", lang.getValue())
        .param("placeIds", placeIds)
        .query(
            (rs, rowNum) -> {
              long placeId = rs.getLong("place_id");
              long thisContentId = rs.getLong("content_id");
              byPlace
                  .computeIfAbsent(placeId, k -> new ArrayList<>())
                  .add(
                      new ContentRef(thisContentId, rs.getString("title"))
                          .posterUrl(uri(rs.getString("poster_url"))));
              if (contentId != null && contentId == thisContentId) {
                String description = rs.getString("relation_description");
                if (description != null) {
                  sceneByPlace.put(placeId, description);
                }
              }
              return null;
            })
        .list();

    for (Row row : rows) {
      long id = row.summary().getId();
      row.summary().setContents(byPlace.getOrDefault(id, List.of()));
      row.summary().setSceneDescription(sceneByPlace.get(id));
    }
  }

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    PlaceSummary summary =
        new PlaceSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"))
            .type(rs.getString("type"))
            .address(rs.getString("address"))
            .imageUrl(uri(rs.getString("image_url")))
            .distanceMeters(integerOrNull(rs, "distance_meters"));
    return new Row(summary, rs.getInt("total_count"), rs.getBoolean("in_requested_lang"));
  }

  /** 수집한 URL 이 URI 로 파싱되지 않으면 그 필드만 비운다. 목록 전체를 500 으로 만들지 않는다. */
  static URI uri(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** {@code getInt} 는 NULL 을 0 으로 바꾼다. "0 미터" 와 "기준점이 없어 거리를 모른다" 는 다르다. */
  private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private record Row(PlaceSummary summary, int total, boolean inRequestedLang) {}
}
