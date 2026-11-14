package com.mz2az.scenetrip.sceneapi.cart;

import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.CartPoi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.poi.PoiColumns;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 장소 장바구니.
 *
 * <p>표 이름은 {@code saved_place} 이고 주체는 {@code app_user.id} 다. {@code X-Install-Id} 헤더로 오는 설치 UUID 를
 * 계정으로 바꾸는 일은 {@link com.mz2az.scenetrip.sceneapi.user.UserStore} 가 하고, 여기는 이미 바뀐 값을 받는다.
 *
 * <p>이 표는 원래 {@code cart_item} 이었다. 8/11 회의가 「작품엔 찜, 장소엔 장바구니」로 가르면서 「장소 찜」이 없어졌고, 남은 하나의 이름이
 * {@code saved_place} 로 정해졌다 (MZ2AZ-111 · MZ2AZ-199 스키마 문서). 작품 찜은 별도 표 {@code saved_content} 다.
 *
 * <p>편의시설(1.31.0)은 별도 표 {@code saved_poi} 에 담고 {@code Cart.pois} 로 따로 내보낸다 — 옛 iOS 앱(1.0.0)이 {@code
 * CartItem.placeId} 가 반드시 있다고 읽기 때문이다(docs/project/plans/keep-poi.md §1). 촬영지와 같은 곳인 편의시설은 촬영지로
 * 담긴다({@link #addPoi}).
 *
 * <p>담기까지만이다. 코스에 넣는 것은 코스 쪽이다.
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
                 pi.lang AS shown_lang
          FROM place_i18n pi
          WHERE pi.lang IN (:lang, 'en', 'ko')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC, (pi.lang = 'en') DESC
      ),
      content_display AS (
          SELECT DISTINCT ON (ci.content_id) ci.content_id, ci.title
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'en', 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC, (ci.lang = 'en') DESC
      )
      SELECT
          ci_.place_id,
          pd.name,
          pd.address,
          pd.shown_lang,
          ST_Y(p.geom::geometry) AS latitude,
          ST_X(p.geom::geometry) AS longitude,
          (SELECT pim.url FROM place_image pim
            WHERE pim.place_id = p.id
            ORDER BY pim.sort_order, pim.id LIMIT 1) AS image_url,
          -- 대표 사진 줄의 작은 사진(1.29.0, MZ2AZ-561) — 같은 순서의 첫 줄
          (SELECT pim.thumbnail_url FROM place_image pim
            WHERE pim.place_id = p.id
            ORDER BY pim.sort_order, pim.id LIMIT 1) AS image_thumbnail_url,
          ci_.source_content_id,
          cd.title AS source_content_title,
          ci_.created_at,
          -- 목록에서 빠진 촬영지(MZ2AZ-451). 거르지 않는다 — 담은 것은 남기고 알리기만 한다.
          p.hidden_at IS NOT NULL AS delisted
      FROM saved_place ci_
      JOIN place p ON p.id = ci_.place_id
      JOIN place_display pd ON pd.place_id = p.id
      LEFT JOIN content_display cd ON cd.content_id = ci_.source_content_id
      WHERE ci_.user_id = CAST(:userId AS UUID)
      ORDER BY ci_.created_at, ci_.place_id
      """;

  /**
   * 편의시설 한 행 — 이름·주소·분류의 언어와 {@code placeId} 는 {@code GET /pois} 와 같은 코드({@link PoiColumns})다. 폐업은
   * 거르지 않고 {@code closed} 로 알린다.
   */
  private static final String POI_SELECT =
      "SELECT p.id, p.category, p.category_group,\n"
          + PoiColumns.LOCALIZED_SELECT
          + """
              ST_Y(p.geom::geometry) AS latitude,
              ST_X(p.geom::geometry) AS longitude,
              p.closed_at IS NOT NULL AS closed
          """;

  /** 담은 편의시설 — 담은 순서(오래된 것부터), 같은 때면 id 순. */
  private static final String LIST_POIS_SQL =
      POI_SELECT
          + """
              , sp.created_at AS added_at
          FROM saved_poi sp
          JOIN poi p ON p.id = sp.poi_id
          """
          + PoiColumns.LOCALIZED_JOIN
          + """
          WHERE sp.user_id = CAST(:userId AS UUID)
          ORDER BY sp.created_at, sp.poi_id
          """;

  /** 방금 담은 편의시설 하나. */
  private static final String ONE_POI_SQL =
      POI_SELECT + "FROM poi p\n" + PoiColumns.LOCALIZED_JOIN + "WHERE p.id = :id\n";

  private final JdbcClient jdbc;

  CartStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 장바구니 내용 — 촬영지({@code items}), 그 항목들이 실제로 나온 언어들, 담은 편의시설({@code pois}, 1.31.0).
   *
   * <p>{@code shownLangs} 는 촬영지 기준이다 — {@code Content-Language} 규칙은 1.31.0 에서도 그대로다(keep-poi.md
   * §2).
   */
  public record Contents(List<CartItem> items, Set<Lang> shownLangs, List<CartPoi> pois) {

    /** 편의시설 없이 — 촬영지만 있는 장바구니. */
    public Contents(List<CartItem> items, Set<Lang> shownLangs) {
      this(items, shownLangs, List.of());
    }
  }

  public Contents list(UUID userId, Lang lang) {
    List<Row> rows = rows(userId, lang);
    return new Contents(
        rows.stream().map(Row::item).toList(),
        rows.stream().map(Row::shownLang).collect(Collectors.toUnmodifiableSet()),
        pois(userId, lang));
  }

  private List<Row> rows(UUID userId, Lang lang) {
    return jdbc.sql(LIST_SQL)
        .param("userId", userId.toString())
        .param("lang", lang.getValue())
        .query(CartStore::mapRow)
        .list();
  }

  /** 담은 편의시설 — 담은 순서(오래된 것부터). 폐업한 것도 거르지 않고 {@code closed=true} 로 남긴다. */
  private List<CartPoi> pois(UUID userId, Lang lang) {
    return jdbc.sql(LIST_POIS_SQL)
        .param("userId", userId.toString())
        .param("lang", lang.getValue())
        .query((rs, n) -> cartPoi(rs, rs.getObject("added_at", OffsetDateTime.class)))
        .list();
  }

  /**
   * 담는다.
   *
   * <p>중복은 DB 의 기본키가 막는다. 애플리케이션에서 "이미 있는지 조회하고 없으면 넣기" 로 하면 동시에 두 번 눌렀을 때 두 조회가 모두 "없음" 을 보고 둘 다
   * 들어간다. 제약 위반을 잡아 409 로 바꾸는 편이 경합에 안전하다.
   *
   * @return 담긴 항목. 이미 있으면 {@link Optional#empty()}
   */
  public Optional<CartItem> add(UUID userId, long placeId, Long sourceContentId, Lang lang) {
    try {
      jdbc.sql(
              """
              INSERT INTO saved_place (user_id, place_id, source_content_id)
              VALUES (CAST(:userId AS UUID), :placeId, CAST(:sourceContentId AS BIGINT))
              """)
          .param("userId", userId.toString())
          .param("placeId", placeId)
          .param("sourceContentId", sourceContentId)
          .update();
    } catch (DuplicateKeyException e) {
      return Optional.empty();
    }

    // 방금 넣은 것을 다시 읽어 돌려준다. 좌표·이미지·작품 제목은 다른 테이블에 있어
    // INSERT 만으로는 응답을 채울 수 없다.
    return rows(userId, lang).stream()
        .map(Row::item)
        .filter(i -> i.getPlaceId() == placeId)
        .findFirst();
  }

  /**
   * 뺀다.
   *
   * @return 실제로 지워졌으면 {@code true}. 담겨 있지 않았으면 {@code false}
   */
  public boolean remove(UUID userId, long placeId) {
    return jdbc.sql(
                "DELETE FROM saved_place WHERE user_id = CAST(:userId AS UUID)"
                    + " AND place_id = :placeId")
            .param("userId", userId.toString())
            .param("placeId", placeId)
            .update()
        > 0;
  }

  /**
   * 새로 담을 수 없는가 — 목록에서 빠진(숨긴) 촬영지인데 아직 이 사람의 장바구니에 없다(MZ2AZ-451). 이미 담아 둔 것은 다시 눌러도 지금처럼 「이미 담김」 이
   * 답이다 — 그래서 「이미 있나」 를 먼저 본다.
   */
  public boolean delistedAndNew(UUID userId, long placeId) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                """
                SELECT EXISTS (SELECT 1 FROM place WHERE id = :placeId AND hidden_at IS NOT NULL)
                   AND NOT EXISTS (SELECT 1 FROM saved_place
                                   WHERE user_id = CAST(:userId AS UUID) AND place_id = :placeId)
                """)
            .param("userId", userId.toString())
            .param("placeId", placeId)
            .query(Boolean.class)
            .single());
  }

  /** 그 편의시설이 있고 폐업 표시가 없는가. 아니면 새로 담을 수 없다 — 404 {@code POI_NOT_FOUND}(keep-poi.md §1-3). */
  public boolean poiAvailable(long poiId) {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM poi WHERE id = :id AND closed_at IS NULL)")
            .param("id", poiId)
            .query(Boolean.class)
            .single());
  }

  /**
   * 편의시설을 담는다(1.31.0).
   *
   * <p><b>촬영지와 같은 곳인 편의시설</b>(숨기지 않은 촬영지와의 {@code place_poi_link} — {@code GET /pois} 가 {@code
   * placeId} 를 정하는 규칙 그대로)이면 촬영지로 담는다({@link #add}). 코스의 {@code poiId} 규칙과 같다. 응답의 {@code placeId}
   * 가 채워지고, 다음 {@code GET /cart} 에서는 {@code pois} 가 아니라 {@code items} 에 나온다.
   *
   * <p>중복은 {@link #add} 와 같이 기본키 위반을 잡는다 — 「조회 뒤 넣기」 는 동시에 두 번 눌렀을 때 둘 다 통과한다. 위반 뒤에는 다른 문장을 보내지
   * 않는다(PostgreSQL 은 실패한 트랜잭션의 다음 문장을 받지 않는다).
   *
   * @return 담긴 편의시설. 이미 담겨 있으면(이어진 촬영지가 이미 담겨 있어도) {@link Optional#empty()}
   */
  public Optional<CartPoi> addPoi(UUID userId, long poiId, Lang lang) {
    Optional<Long> linkedPlace =
        jdbc.sql("SELECT lk.place_id FROM " + PoiColumns.VISIBLE_LINK + " lk WHERE lk.poi_id = :id")
            .param("id", poiId)
            .query(Long.class)
            .optional();

    OffsetDateTime addedAt;
    if (linkedPlace.isPresent()) {
      Optional<CartItem> item = add(userId, linkedPlace.get(), null, lang);
      if (item.isEmpty()) {
        return Optional.empty();
      }
      addedAt = item.get().getAddedAt();
    } else {
      try {
        addedAt =
            jdbc.sql(
                    """
                    INSERT INTO saved_poi (user_id, poi_id)
                    VALUES (CAST(:userId AS UUID), :poiId)
                    RETURNING created_at
                    """)
                .param("userId", userId.toString())
                .param("poiId", poiId)
                .query((rs, n) -> rs.getObject("created_at", OffsetDateTime.class))
                .single();
      } catch (DuplicateKeyException e) {
        return Optional.empty();
      }
    }

    // 이름·분류·좌표는 편의시설 표와 번역 표에 있어 INSERT 만으로는 응답을 채울 수 없다 — 목록과 같은 열로 다시 읽는다.
    return jdbc.sql(ONE_POI_SQL)
        .param("id", poiId)
        .param("lang", lang.getValue())
        .query((rs, n) -> cartPoi(rs, addedAt))
        .optional();
  }

  /**
   * 담은 편의시설을 뺀다. 폐업한 것도 뺄 수 있다.
   *
   * @return 실제로 지워졌으면 {@code true}. 담겨 있지 않았으면 {@code false}
   */
  public boolean removePoi(UUID userId, long poiId) {
    return jdbc.sql(
                "DELETE FROM saved_poi WHERE user_id = CAST(:userId AS UUID) AND poi_id = :poiId")
            .param("userId", userId.toString())
            .param("poiId", poiId)
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
            .imageThumbnailUrl(uri(rs.getString("image_thumbnail_url")))
            .sourceContentId(longOrNull(rs))
            .sourceContentTitle(rs.getString("source_content_title"))
            .delisted(rs.getBoolean("delisted"));
    return new Row(item, Lang.fromValue(rs.getString("shown_lang")));
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

  /** 편의시설 행을 장바구니 모양으로 — 공통 칸은 {@link PoiColumns#summary} 그대로, 담은 때와 폐업 표시만 더한다. */
  private static CartPoi cartPoi(ResultSet rs, OffsetDateTime addedAt) throws SQLException {
    PoiSummary s = PoiColumns.summary(rs);
    return new CartPoi(
            s.getId(),
            s.getName(),
            s.getCategory(),
            s.getCategoryGroup(),
            s.getLatitude(),
            s.getLongitude(),
            addedAt)
        .placeId(s.getPlaceId())
        .displayName(s.getDisplayName())
        .nameRoman(s.getNameRoman())
        .categoryLabel(s.getCategoryLabel())
        .address(s.getAddress())
        .displayAddress(s.getDisplayAddress())
        .imageUrl(s.getImageUrl())
        .closed(rs.getBoolean("closed"));
  }

  private record Row(CartItem item, Lang shownLang) {}
}
