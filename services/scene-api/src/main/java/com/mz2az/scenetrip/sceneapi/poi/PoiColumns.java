package com.mz2az.scenetrip.sceneapi.poi;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 편의시설 한 행을 응답 모양으로 읽는 SQL 조각과 매퍼 — {@code GET /pois}({@link PoiStore})와 장바구니의 편의시설({@code
 * cart.CartStore}, 계약 1.31.0)이 <b>같은 코드</b>를 쓴다. 언어 규칙(이름·주소·분류)과 촬영지 연결({@code placeId}) 규칙이 두 벌로
 * 갈라지지 않게 여기 한 곳에 둔다(docs/project/plans/keep-poi.md §2).
 *
 * <p>쓰는 쪽은 편의시설 표를 {@code p} 로 부르고 {@code :lang} 을 바인딩한다. {@link #LOCALIZED_SELECT} 는 끝에 쉼표가 있어 뒤에
 * 열을 더 잇는다. 좌표 열({@code latitude}·{@code longitude})과 {@code id}·{@code category}·{@code
 * category_group} 은 쓰는 쪽이 고른다 — 골고루 질의처럼 고르는 자리가 다르다.
 */
public final class PoiColumns {

  private PoiColumns() {}

  /**
   * 대표 사진(1.31.0, keep-poi.md §1-5b) — 상세가 주는 사진({@code poi_image_visible}, V38 — 표출 중단된 관광공사 번호의
   * 사진은 뺀 뷰)을 {@code sort_order, id} 순으로 읽은 첫 장. 상세의 {@code images[0]} 과 같은 규칙이다. 상관 서브쿼리라 결과로 돌려주는
   * 행에만 붙는다 — PostgreSQL 은 LIMIT 이 있으면 비싼 열을 정렬·자르기 뒤로 미룬다. {@code poi_image_poi_idx (poi_id,
   * sort_order)} 를 탄다.
   */
  public static final String FIRST_IMAGE_URL =
      "(SELECT vi.url FROM poi_image_visible vi WHERE vi.poi_id = p.id"
          + " ORDER BY vi.sort_order, vi.id LIMIT 1)";

  /**
   * 사진이 있는 것만({@code hasImage=true}, 1.31.0). {@link #FIRST_IMAGE_URL} 과 같은 뷰라 「거른 것엔 언제나 {@code
   * imageUrl} 이 있다」. {@code :hasImage} 가 TRUE 가 아니면(FALSE·NULL) 통째로 참이다. 목록의 모든 갈래와 개수 질의가 같은 조건을
   * 쓴다.
   */
  public static final String HAS_IMAGE_SQL =
      """
        AND (CAST(:hasImage AS BOOLEAN) IS NOT TRUE
             OR EXISTS (SELECT 1 FROM poi_image_visible hi WHERE hi.poi_id = p.id))
      """;

  /**
   * 요청 언어의 값 — 요청 언어 → en, 없으면 NULL(분류 이름은 한국어 원본). 한국어를 요청하면 번역을 보지 않는다(번역 표에 {@code ko} 행이 없고,
   * 있어도 원본이 정본이다). {@code label_lang} 은 분류 이름이 실제로 나온 언어다. {@code name}·{@code address} 는 언제나 원본.
   */
  public static final String LOCALIZED_SELECT =
      """
              p.name, p.address,
              CASE WHEN :lang = 'ko' THEN NULL
                   ELSE COALESCE(tr.name, te.name) END AS display_name,
              CASE WHEN :lang = 'ko' THEN NULL
                   ELSE COALESCE(tr.address, te.address) END AS display_address,
              CASE WHEN :lang = 'ko' THEN p.category
                   ELSE COALESCE(cr.name, ce.name, p.category) END AS category_label,
              CASE WHEN :lang = 'ko' THEN 'ko'
                   WHEN cr.name IS NOT NULL THEN :lang
                   WHEN ce.name IS NOT NULL THEN 'en'
                   ELSE 'ko' END AS label_lang,
              p.name_roman,
              lk.place_id AS linked_place_id,
      """
          + "        "
          + FIRST_IMAGE_URL
          + " AS image_url,\n";

  /**
   * 같은 곳인 촬영지(V25, MZ2AZ-371) — 편의시설 하나에 많아야 한 줄({@code place_poi_link} 의 기본키가 {@code poi_id}). 숨긴
   * 촬영지의 연결은 쓰지 않는다 — 지도에 없으니 편의시설이 스스로 보여야 한다. 장바구니가 「촬영지로 담을지」 를 정할 때도 이 규칙을 쓴다.
   */
  public static final String VISIBLE_LINK =
      """
      (SELECT l.poi_id, l.place_id
       FROM place_poi_link l
       JOIN place lp ON lp.id = l.place_id AND lp.hidden_at IS NULL)
      """;

  /** 번역 두 갈래(요청 언어·en)와 촬영지 연결을 붙인다. 번역은 (poi_id, lang)·(ko, lang) 기본 키로 한 행씩이다. */
  public static final String LOCALIZED_JOIN =
      """
          LEFT JOIN poi_i18n tr ON tr.poi_id = p.id AND tr.lang = :lang
          LEFT JOIN poi_i18n te ON te.poi_id = p.id AND te.lang = 'en'
          LEFT JOIN poi_category_i18n cr ON cr.ko = p.category AND cr.lang = :lang
          LEFT JOIN poi_category_i18n ce ON ce.ko = p.category AND ce.lang = 'en'
          LEFT JOIN
      """
          + VISIBLE_LINK
          + " lk ON lk.poi_id = p.id\n";

  /**
   * 한 행의 공통 칸 — {@code id}·이름·분류·좌표·주소·{@code placeId}. 질의마다 다른 칸({@code distanceMeters}·{@code
   * spreadRank}, 장바구니의 {@code addedAt}·{@code closed})은 쓰는 쪽이 더한다.
   */
  public static PoiSummary summary(ResultSet rs) throws SQLException {
    return new PoiSummary(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("category"),
            PoiCategoryGroup.fromValue(rs.getString("category_group")),
            rs.getDouble("latitude"),
            rs.getDouble("longitude"))
        .displayName(rs.getString("display_name"))
        .categoryLabel(rs.getString("category_label"))
        .nameRoman(rs.getString("name_roman"))
        .address(rs.getString("address"))
        .displayAddress(rs.getString("display_address"))
        .placeId(longOrNull(rs, "linked_place_id"))
        .imageUrl(imageUrl(rs));
  }

  /** 대표 사진 주소({@link #FIRST_IMAGE_URL} 의 {@code image_url} 열). 없거나 URI 로 못 읽으면 null. */
  public static URI imageUrl(ResultSet rs) throws SQLException {
    String value = rs.getString("image_url");
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return URI.create(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** 분류 이름이 실제로 나온 언어 — {@code Content-Language} 를 정하는 데 쓴다. */
  public static Lang labelLang(ResultSet rs) throws SQLException {
    return Lang.fromValue(rs.getString("label_lang"));
  }

  /** {@code getLong} 은 NULL 을 0 으로 돌려준다. */
  static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
