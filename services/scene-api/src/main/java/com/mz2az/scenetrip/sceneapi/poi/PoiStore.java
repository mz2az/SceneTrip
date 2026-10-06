package com.mz2az.scenetrip.sceneapi.poi;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiImage;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 편의시설(POI) 조회. 명세의 {@code GET /pois} · {@code GET /pois/{poiId}} 를 받친다.
 *
 * <p>{@link com.mz2az.scenetrip.sceneapi.place.PlaceStore} 와 같은 모양이되 언어 폴백이 다르다 — <b>칸마다 따로</b>다(명세
 * 「언어」, 계획 poi-i18n-image.md §13). 한국어 원본은 {@code poi} 에 있고 번역({@code poi_i18n})은 대부분 비어 있다 — 영어
 * 이름은 확실할 때만 있고 영어 주소는 약 99 % 다. 그래서 이름·주소·분류 이름이 각각 요청 언어 → en → 한국어 원본으로 떨어지고, 원래 한국어는 {@code
 * localName}·{@code localAddress} 로 늘 함께 나간다. {@code Content-Language} 로 알리는 것은 분류 이름의 언어다.
 *
 * <p>{@code place} 와 다른 점 둘은 규모에서 온다 — 성지는 155 개, POI 는 50 만 개다.
 *
 * <ul>
 *   <li><b>거리 정렬은 KNN 이다.</b> {@code ORDER BY geom <-> 기준점} 은 GiST 인덱스가 가까운 상자부터 열어 {@code LIMIT}
 *       만큼 나오면 멈춘다 — bbox 안 행을 전부 꺼내 거리를 재고 정렬하는 것({@code ORDER BY ST_Distance})과 달리 화면 크기에 시간이 늘지
 *       않는다. 실측(2026-09-04, 줌 13 서울 7 만 행): 전부 정렬 52 ms, KNN 3.9 ms.
 *   <li><b>{@code total} 은 따로 센다.</b> 한 질의에 {@code count(*) OVER ()} 를 붙이면 세기 위해 끝까지 가야 하므로 KNN 의
 *       「채우면 멈춤」이 사라지고 전부 정렬과 같아진다. 개수 질의는 거리를 재지 않고 인덱스만 세서 7 만 행에 14 ms 다.
 * </ul>
 *
 * <p>구면(geography)으로 정렬하는 이유: 평면(geometry) KNN 이 0.3 ms 로 더 빠르지만 위도 37° 에서 경도 1° 는 위도 1° 의 80% 라 동서
 * 방향이 20% 가깝게 계산된다. 프로토타입이 {@code ×0.8} 로 보정하던 그 오차다. 3.9 ms 는 사용자에게 0.3 ms 와 같은 「즉시」다.
 */
@Repository
public class PoiStore {

  /** 정렬 기준. 명세의 {@code sort} 파라미터에 대응한다. 인기도는 없다 — POI 에는 순위를 매길 근거가 없다. */
  public enum Sort {
    DISTANCE,
    ALPHABETICAL
  }

  /** 조회 조건. 컨트롤러가 명세의 규칙(영역 조건 필수, bbox 와 반경 배타 등)을 검사한 뒤 만들어 넘긴다. */
  public record Criteria(
      Bbox bbox,
      Double lat,
      Double lng,
      Integer radiusMeters,
      PoiCategoryGroup categoryGroup,
      Sort sort,
      int limit,
      int offset,
      Lang lang) {

    /** 언어를 말하지 않으면 한국어 — 카드·가이드처럼 이름을 화면에 바로 쓰지 않는 쪽. */
    public Criteria(
        Bbox bbox,
        Double lat,
        Double lng,
        Integer radiusMeters,
        PoiCategoryGroup categoryGroup,
        Sort sort,
        int limit,
        int offset) {
      this(bbox, lat, lng, radiusMeters, categoryGroup, sort, limit, offset, Lang.KO);
    }

    boolean hasOrigin() {
      return lat != null && lng != null;
    }
  }

  /**
   * 한 페이지치 항목과 조건에 맞는 전체 개수(명세의 {@code PoiList.total}), 그리고 항목들의 분류 이름이 실제로 나온 언어들 — {@code
   * Content-Language} 를 정하는 데 쓴다({@code Responses.used}).
   */
  public record Page(List<PoiSummary> items, int total, Set<Lang> shownLangs) {}

  /** 상세와 그 분류 이름이 실제로 나온 언어. */
  public record Detail(PoiDetail poi, Lang shownLang) {}

  /**
   * 조건 부분. 목록과 개수 질의가 <b>같은 WHERE</b> 를 써야 {@code total} 이 목록과 같은 것을 센다. 그래서 문자열 하나로 두고 둘이 나눠 쓴다.
   *
   * <p>{@code CAST(:x AS ...) IS NULL OR ...} 꼴은 "그 조건을 안 준 요청" 을 표현한다 — 바인딩 값이 NULL 이면 그 줄이 통째로 참이
   * 되어 조건이 빠진다. 캐스팅이 있는 이유는 NULL 만으로는 PostgreSQL 이 타입을 못 정해 "could not determine data type" 을 내기
   * 때문이다.
   */
  private static final String WHERE_SQL =
      """
      WHERE
        -- 뷰포트는 평면(geometry)으로 본다. 구면으로 만들면 경도 -180~180 사각형에서 180 도 간선의
        -- 방향을 정할 수 없어 질의가 실패한다(place 의 V6 주석). V13 의 표현식 인덱스가 이 && 를 받는다.
        (CAST(:minLng AS DOUBLE PRECISION) IS NULL
         OR p.geom::geometry && ST_MakeEnvelope(
                CAST(:minLng AS DOUBLE PRECISION),
                CAST(:minLat AS DOUBLE PRECISION),
                CAST(:maxLng AS DOUBLE PRECISION),
                CAST(:maxLat AS DOUBLE PRECISION),
                4326))
        -- 반경은 구면(geography)이다 — "몇 미터 안" 은 구면 거리가 맞다. ST_DWithin 이 V12 의 GiST 를 탄다.
        AND (CAST(:radiusMeters AS INTEGER) IS NULL
             OR o.point IS NULL
             OR ST_DWithin(p.geom, o.point, CAST(:radiusMeters AS INTEGER)))
        AND (CAST(:categoryGroup AS TEXT) IS NULL OR p.category_group = CAST(:categoryGroup AS TEXT))
        -- 폐업 표시된 POI 는 어디에도 내보내지 않는다(V18). 위의 위치·갈래 인덱스가 먼저 좁히고 여기서 거른다.
        AND p.closed_at IS NULL
      """;

  /** 기준점. 없으면 NULL 이고, 그러면 거리 계산과 반경 조건이 통째로 빠진다. */
  private static final String ORIGIN_SQL =
      """
      WITH origin AS (
          SELECT CASE
                     WHEN CAST(:lat AS DOUBLE PRECISION) IS NULL THEN NULL
                     ELSE ST_SetSRID(
                              ST_MakePoint(CAST(:lng AS DOUBLE PRECISION),
                                           CAST(:lat AS DOUBLE PRECISION)),
                              4326)::geography
                 END AS point
      )
      """;

  /**
   * 칸마다 따로 폴백한다 — 요청 언어 → en → 한국어 원본({@code poi}). 한국어를 요청하면 번역을 보지 않는다(번역 표에 {@code ko} 행이 없고,
   * 있어도 원본이 정본이다). {@code label_lang} 은 분류 이름이 실제로 나온 언어다.
   */
  private static final String LOCALIZED_SELECT =
      """
              CASE WHEN :lang = 'ko' THEN p.name
                   ELSE COALESCE(tr.name, te.name, p.name) END AS name,
              CASE WHEN :lang = 'ko' THEN p.address
                   ELSE COALESCE(tr.address, te.address, p.address) END AS address,
              CASE WHEN :lang = 'ko' THEN p.category
                   ELSE COALESCE(cr.name, ce.name, p.category) END AS category_label,
              CASE WHEN :lang = 'ko' THEN 'ko'
                   WHEN cr.name IS NOT NULL THEN :lang
                   WHEN ce.name IS NOT NULL THEN 'en'
                   ELSE 'ko' END AS label_lang,
              p.name AS local_name, p.address AS local_address, p.name_roman,
      """;

  /** 번역 두 갈래(요청 언어·en)를 붙인다. 둘 다 (poi_id, lang)·(ko, lang) 기본 키로 한 행씩이다. */
  private static final String LOCALIZED_JOIN =
      """
          LEFT JOIN poi_i18n tr ON tr.poi_id = p.id AND tr.lang = :lang
          LEFT JOIN poi_i18n te ON te.poi_id = p.id AND te.lang = 'en'
          LEFT JOIN poi_category_i18n cr ON cr.ko = p.category AND cr.lang = :lang
          LEFT JOIN poi_category_i18n ce ON ce.ko = p.category AND ce.lang = 'en'
      """;

  private static final String LIST_SQL =
      ORIGIN_SQL
          + """
          SELECT
              p.id, p.category, p.category_group,
          """
          + LOCALIZED_SELECT
          + """
              -- geography 에서 좌표를 꺼내려면 geometry 로 캐스팅한다. ST_X 가 경도, ST_Y 가 위도다.
              ST_Y(p.geom::geometry) AS latitude,
              ST_X(p.geom::geometry) AS longitude,
              CASE WHEN o.point IS NULL THEN NULL
                   ELSE round(ST_Distance(p.geom, o.point))::INT END AS distance_meters
          FROM poi p
          CROSS JOIN origin o
          """
          + LOCALIZED_JOIN
          + WHERE_SQL
          + """
          ORDER BY __ORDER_BY__
          LIMIT :limit OFFSET :offset
          """;

  private static final String COUNT_SQL =
      ORIGIN_SQL + "SELECT count(*) FROM poi p CROSS JOIN origin o\n" + WHERE_SQL;

  /** 구면 KNN. {@code <->} 를 ORDER BY 에 두면 GiST 가 가까운 순으로 꺼내 준다. 기준점이 없으면 컨트롤러가 이 정렬을 거부한다(400). */
  private static final String ORDER_BY_DISTANCE = "p.geom <-> o.point, p.id";

  /**
   * 이름순. {@code COLLATE "C"} 는 코드포인트 순서 — 한글은 그것이 곧 가나다순이다. DB 로케일에 맡기면 환경마다 다르다: kind 는 {@code C}
   * 라 가나다순인데 CI 의 서비스 컨테이너는 {@code en_US} 라 glibc 나름의 순서로 「모슬포」를 「드림」 앞에 놓았다(2026-09-04 CI 실패). 이름이
   * 같은 가게가 수백씩이라(스타벅스) {@code id} 를 뒤에 붙여야 페이지가 흔들리지 않는다.
   */
  private static final String ORDER_BY_ALPHABETICAL = "p.name COLLATE \"C\", p.id";

  private static final String DETAIL_SQL =
      ORIGIN_SQL
          + """
          SELECT
              p.id, p.category, p.category_group, p.road, p.tel, p.region, p.city,
          """
          + LOCALIZED_SELECT
          + """
              ST_Y(p.geom::geometry) AS latitude,
              ST_X(p.geom::geometry) AS longitude,
              CASE WHEN o.point IS NULL THEN NULL
                   ELSE round(ST_Distance(p.geom, o.point))::INT END AS distance_meters
          FROM poi p
          CROSS JOIN origin o
          """
          + LOCALIZED_JOIN
          + """
          WHERE p.id = :id AND p.closed_at IS NULL
          """;

  /** 우리가 모은 사진 — 정렬 순서대로, 첫 장이 대표(V17 의 poi_image_poi_idx 가 받는다). */
  private static final String IMAGES_SQL =
      """
      SELECT url, credit FROM poi_image WHERE poi_id = :id ORDER BY sort_order, id
      """;

  private final JdbcClient jdbc;

  PoiStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 목록 한 페이지와 전체 개수. 질의 둘 — 이유는 클래스 주석. */
  public Page list(Criteria criteria) {
    // formatted() 가 아니라 replace() 다. 사용자 입력이 아니라 이 파일 안의 상수 둘 중 하나를 끼운다.
    String sql =
        LIST_SQL.replace(
            "__ORDER_BY__",
            criteria.sort() == Sort.DISTANCE ? ORDER_BY_DISTANCE : ORDER_BY_ALPHABETICAL);

    List<Row> rows =
        bind(jdbc.sql(sql), criteria)
            .param("limit", criteria.limit())
            .param("offset", criteria.offset())
            .query(PoiStore::mapRow)
            .list();
    List<PoiSummary> items = rows.stream().map(Row::summary).toList();
    Set<Lang> shown = new LinkedHashSet<>();
    rows.forEach(r -> shown.add(r.labelLang()));

    // 첫 페이지가 상한보다 적으면 그것이 곧 전체다 — 개수 질의를 아낀다.
    int total =
        criteria.offset() == 0 && items.size() < criteria.limit()
            ? items.size()
            : bind(jdbc.sql(COUNT_SQL), criteria).query(Integer.class).single();

    return new Page(items, total, shown);
  }

  /** 주어진 id 중 실제로 있는 것 — 폐업 표시된 것은 없는 것으로 친다. 여럿 카드 조회가 「없는 id」를 그 자리에 표시하려고 쓴다. */
  public Set<Long> existingIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.sql("SELECT id FROM poi WHERE id IN (:ids) AND closed_at IS NULL")
            .param("ids", ids)
            .query(Long.class)
            .list());
  }

  /** 상세 하나(한국어). 카드처럼 이름을 화면에 바로 쓰지 않는 쪽이 쓴다. */
  public Optional<PoiDetail> findDetail(long id, Double lat, Double lng) {
    return findDetail(id, Lang.KO, lat, lng).map(Detail::poi);
  }

  /** 상세 하나와 사진. 없거나 폐업 표시됐으면 비어 있다 — 404 는 컨트롤러의 몫이다. */
  public Optional<Detail> findDetail(long id, Lang lang, Double lat, Double lng) {
    boolean hasOrigin = lat != null && lng != null;
    Optional<Detail> found =
        jdbc.sql(DETAIL_SQL)
            .param("id", id)
            .param("lang", lang.getValue())
            .param("lat", hasOrigin ? lat : null)
            .param("lng", hasOrigin ? lng : null)
            .query(PoiStore::mapDetail)
            .optional();
    found.ifPresent(
        d ->
            d.poi()
                .images(
                    jdbc.sql(IMAGES_SQL)
                        .param("id", id)
                        .query(
                            (rs, n) ->
                                new PoiImage(URI.create(rs.getString("url")))
                                    .credit(rs.getString("credit")))
                        .list()));
    return found;
  }

  /**
   * 목록 질의의 실행 계획. <b>통합 테스트가 인덱스를 타는지 확인하는 용도</b>다 — 인덱스 이름이 계획에 찍히는 것이 아니라 순차 스캔이 없는지를 본다. 운영 코드는
   * 부르지 않는다.
   */
  String explainList(Criteria criteria) {
    String sql =
        "EXPLAIN "
            + LIST_SQL.replace(
                "__ORDER_BY__",
                criteria.sort() == Sort.DISTANCE ? ORDER_BY_DISTANCE : ORDER_BY_ALPHABETICAL);
    return String.join(
        "\n",
        bind(jdbc.sql(sql), criteria)
            .param("limit", criteria.limit())
            .param("offset", criteria.offset())
            .query(String.class)
            .list());
  }

  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Criteria c) {
    return spec.param("lang", c.lang().getValue())
        .param("lat", c.hasOrigin() ? c.lat() : null)
        .param("lng", c.hasOrigin() ? c.lng() : null)
        .param("radiusMeters", c.radiusMeters())
        .param("categoryGroup", c.categoryGroup() == null ? null : c.categoryGroup().getValue())
        .param("minLng", c.bbox() == null ? null : c.bbox().minLng())
        .param("minLat", c.bbox() == null ? null : c.bbox().minLat())
        .param("maxLng", c.bbox() == null ? null : c.bbox().maxLng())
        .param("maxLat", c.bbox() == null ? null : c.bbox().maxLat());
  }

  private record Row(PoiSummary summary, Lang labelLang) {}

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    PoiSummary summary =
        new PoiSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category"),
                PoiCategoryGroup.fromValue(rs.getString("category_group")),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"))
            .localName(rs.getString("local_name"))
            .categoryLabel(rs.getString("category_label"))
            .nameRoman(rs.getString("name_roman"))
            .address(rs.getString("address"))
            .localAddress(rs.getString("local_address"))
            .distanceMeters(integerOrNull(rs, "distance_meters"));
    return new Row(summary, Lang.fromValue(rs.getString("label_lang")));
  }

  private static Detail mapDetail(ResultSet rs, int rowNum) throws SQLException {
    PoiDetail detail =
        new PoiDetail(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("category"),
                PoiCategoryGroup.fromValue(rs.getString("category_group")),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                List.of())
            .localName(rs.getString("local_name"))
            .categoryLabel(rs.getString("category_label"))
            .nameRoman(rs.getString("name_roman"))
            .address(rs.getString("address"))
            .localAddress(rs.getString("local_address"))
            .distanceMeters(integerOrNull(rs, "distance_meters"))
            .road(rs.getString("road"))
            .tel(rs.getString("tel"))
            .region(rs.getString("region"))
            .city(rs.getString("city"));
    return new Detail(detail, Lang.fromValue(rs.getString("label_lang")));
  }

  /** {@code getInt} 는 NULL 을 0 으로 돌려준다. 0 m 와 "기준점 없음" 은 다르다. */
  private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }
}
