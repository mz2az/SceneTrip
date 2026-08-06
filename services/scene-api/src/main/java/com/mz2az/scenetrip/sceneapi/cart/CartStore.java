package com.mz2az.scenetrip.sceneapi.cart;

import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 기기 단위 장바구니.
 *
 * <p>주체는 {@code X-Device-Id} 헤더의 기기 UUID 다. 로그인이 없어 사용자를 식별할 방법이 그것뿐이다 — 로그인이 생기면 이 UUID 를 계정에 이어
 * 붙인다.
 *
 * <p>담기까지만이다. 루트(코스) 만들기는 이 범위 밖이다.
 */
@Repository
public class CartStore {

  /**
   * 담은 순서(오래된 것부터).
   *
   * <p>장바구니는 사용자가 걸어갈 순서를 스스로 만든 목록이라, 인기도가 아니라 담은 순서가 뜻을 가진다.
   */
  private static final String LIST_SQL =
      """
      WITH place_display AS (
          SELECT DISTINCT ON (pi.place_id) pi.place_id, pi.name, pi.address,
                 pi.lang = :lang AS in_requested_lang
          FROM place_i18n pi
          WHERE pi.lang IN (:lang, 'ko')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC
      ),
      content_display AS (
          SELECT DISTINCT ON (ci.content_id) ci.content_id, ci.title
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      )
      SELECT
          ci_.place_id,
          pd.name,
          pd.address,
          pd.in_requested_lang,
          ST_Y(p.geom::geometry) AS latitude,
          ST_X(p.geom::geometry) AS longitude,
          (SELECT pim.url FROM place_image pim
            WHERE pim.place_id = p.id
            ORDER BY pim.sort_order, pim.id LIMIT 1) AS image_url,
          ci_.source_content_id,
          cd.title AS source_content_title,
          ci_.created_at
      FROM cart_item ci_
      JOIN place p ON p.id = ci_.place_id
      JOIN place_display pd ON pd.place_id = p.id
      LEFT JOIN content_display cd ON cd.content_id = ci_.source_content_id
      WHERE ci_.device_id = CAST(:deviceId AS UUID)
      ORDER BY ci_.created_at, ci_.place_id
      """;

  private final JdbcClient jdbc;

  CartStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 장바구니 내용과 언어 폴백 여부. */
  public record Contents(List<CartItem> items, boolean anyInRequestedLang) {}

  public Contents list(UUID deviceId, Lang lang) {
    List<Row> rows =
        jdbc.sql(LIST_SQL)
            .param("deviceId", deviceId.toString())
            .param("lang", lang.getValue())
            .query(CartStore::mapRow)
            .list();

    return new Contents(
        rows.stream().map(Row::item).toList(), rows.stream().anyMatch(Row::inRequestedLang));
  }

  /**
   * 담는다.
   *
   * <p>중복은 DB 의 기본키가 막는다. 애플리케이션에서 "이미 있는지 조회하고 없으면 넣기" 로 하면 동시에 두 번 눌렀을 때 두 조회가 모두 "없음" 을 보고 둘 다
   * 들어간다. 제약 위반을 잡아 409 로 바꾸는 편이 경합에 안전하다.
   *
   * @return 담긴 항목. 이미 있으면 {@link Optional#empty()}
   */
  public Optional<CartItem> add(UUID deviceId, long placeId, Long sourceContentId, Lang lang) {
    try {
      jdbc.sql(
              """
              INSERT INTO cart_item (device_id, place_id, source_content_id)
              VALUES (CAST(:deviceId AS UUID), :placeId, CAST(:sourceContentId AS BIGINT))
              """)
          .param("deviceId", deviceId.toString())
          .param("placeId", placeId)
          .param("sourceContentId", sourceContentId)
          .update();
    } catch (DuplicateKeyException e) {
      return Optional.empty();
    }

    // 방금 넣은 것을 다시 읽어 돌려준다. 좌표·이미지·작품 제목은 다른 테이블에 있어
    // INSERT 만으로는 응답을 채울 수 없다.
    return list(deviceId, lang).items().stream().filter(i -> i.getPlaceId() == placeId).findFirst();
  }

  /**
   * 뺀다.
   *
   * @return 실제로 지워졌으면 {@code true}. 담겨 있지 않았으면 {@code false}
   */
  public boolean remove(UUID deviceId, long placeId) {
    return jdbc.sql(
                "DELETE FROM cart_item WHERE device_id = CAST(:deviceId AS UUID)"
                    + " AND place_id = :placeId")
            .param("deviceId", deviceId.toString())
            .param("placeId", placeId)
            .update()
        > 0;
  }

  /** 그 장소가 있는가. 없는 장소를 담으려 하면 404 다. */
  public boolean placeExists(long placeId) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM place WHERE id = :id)")
            .param("id", placeId)
            .query(Boolean.class)
            .single());
  }

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    CartItem item =
        new CartItem(
                rs.getLong("place_id"),
                rs.getString("name"),
                rs.getObject("created_at", OffsetDateTime.class))
            .address(rs.getString("address"))
            .latitude(rs.getDouble("latitude"))
            .longitude(rs.getDouble("longitude"))
            .imageUrl(uri(rs.getString("image_url")))
            .sourceContentId(longOrNull(rs))
            .sourceContentTitle(rs.getString("source_content_title"));
    return new Row(item, rs.getBoolean("in_requested_lang"));
  }

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

  /** {@code getLong} 은 NULL 을 0 으로 바꾼다. 작품 id 0 과 "담을 때 작품을 안 줬다" 는 다르다. */
  private static Long longOrNull(ResultSet rs) throws SQLException {
    long value = rs.getLong("source_content_id");
    return rs.wasNull() ? null : value;
  }

  private record Row(CartItem item, boolean inRequestedLang) {}
}
