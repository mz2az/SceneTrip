package com.mz2az.scenetrip.sceneapi.poi.naver;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code poi_naver} 읽고 쓰기.
 *
 * <p>조회는 <b>현재 판({@code ruleVersion})의 행만</b> 본다. 옛 판의 행은 없는 것처럼 보여 호출자가 다시 물어 덮어쓴다 — 규칙을 고칠 때 표를
 * 지우지 않아도 되는 이유다. 쓰기는 UPSERT 다: POI 하나에 행 하나, 다시 물으면 덮어쓴다.
 */
@Repository
public class PoiNaverStore {

  private static final String COLUMNS =
      """
      poi_id, found, why, rule_version, checked_at,
      naver_id, name, category, address, phone, hours, score, review_count, blog_reviews, images, url
      """;

  private static final String FIND_SQL =
      "SELECT " + COLUMNS + " FROM poi_naver WHERE poi_id = :poiId AND rule_version = :ruleVersion";

  private static final String FIND_ALL_SQL =
      "SELECT "
          + COLUMNS
          + " FROM poi_naver WHERE poi_id IN (:poiIds) AND rule_version = :ruleVersion";

  /**
   * 사진 목록은 줄바꿈으로 이어 보내고 SQL 이 배열로 자른다. JDBC 로 배열을 넘기려면 드라이버의 {@code createArrayOf} 를 직접 다뤄야 해서 —
   * URL 에는 줄바꿈이 없으므로 이쪽이 단순하다.
   */
  private static final String SAVE_SQL =
      """
      INSERT INTO poi_naver (poi_id, found, why, rule_version, checked_at,
                             naver_id, name, category, address, phone, hours,
                             score, review_count, blog_reviews, images, url)
      VALUES (:poiId, :found, :why, :ruleVersion, now(),
              :naverId, :name, :category, :address, :phone, :hours,
              :score, :reviewCount, :blogReviews,
              CASE WHEN :images = '' THEN '{}'::TEXT[] ELSE string_to_array(:images, E'\\n') END,
              :url)
      ON CONFLICT (poi_id) DO UPDATE SET
          found = EXCLUDED.found, why = EXCLUDED.why, rule_version = EXCLUDED.rule_version,
          checked_at = now(),
          naver_id = EXCLUDED.naver_id, name = EXCLUDED.name, category = EXCLUDED.category,
          address = EXCLUDED.address, phone = EXCLUDED.phone, hours = EXCLUDED.hours,
          score = EXCLUDED.score, review_count = EXCLUDED.review_count,
          blog_reviews = EXCLUDED.blog_reviews, images = EXCLUDED.images, url = EXCLUDED.url
      RETURNING
      """
          + COLUMNS;

  private final JdbcClient jdbc;

  PoiNaverStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** 현재 판의 결과. 없거나 옛 판이면 비어 있다 — 호출자는 「아직 안 물어봄」으로 다룬다. */
  public Optional<NaverCard> find(long poiId, String ruleVersion) {
    return jdbc.sql(FIND_SQL)
        .param("poiId", poiId)
        .param("ruleVersion", ruleVersion)
        .query(PoiNaverStore::mapRow)
        .optional();
  }

  /** 여럿. 있는 것만 담긴다 — 없는 id 는 키 자체가 없다. */
  public Map<Long, NaverCard> findAll(Collection<Long> poiIds, String ruleVersion) {
    Map<Long, NaverCard> out = new LinkedHashMap<>();
    if (poiIds.isEmpty()) {
      return out;
    }
    jdbc.sql(FIND_ALL_SQL)
        .param("poiIds", poiIds)
        .param("ruleVersion", ruleVersion)
        .query(PoiNaverStore::mapRow)
        .list()
        .forEach(card -> out.put(card.poiId(), card));
    return out;
  }

  /**
   * 있으면 덮어쓰고 없으면 넣는다. {@code checkedAt} 은 DB 시각으로 찍힌다 — 인자의 값은 무시한다. 저장된 행을 그대로 돌려준다(RETURNING) —
   * 시각을 알려고 한 번 더 읽지 않는다.
   */
  public NaverCard save(NaverCard card) {
    return jdbc.sql(SAVE_SQL)
        .param("poiId", card.poiId())
        .param("found", card.found())
        .param("why", card.why())
        .param("ruleVersion", card.ruleVersion())
        .param("naverId", card.naverId())
        .param("name", card.name())
        .param("category", card.category())
        .param("address", card.address())
        .param("phone", card.phone())
        .param("hours", card.hours())
        .param("score", card.score())
        .param("reviewCount", card.reviewCount())
        .param("blogReviews", card.blogReviews())
        .param("images", String.join("\n", card.images()))
        .param("url", card.url())
        .query(PoiNaverStore::mapRow)
        .single();
  }

  private static NaverCard mapRow(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal score = rs.getBigDecimal("score");
    Array images = rs.getArray("images");
    return new NaverCard(
        rs.getLong("poi_id"),
        rs.getBoolean("found"),
        rs.getString("why"),
        rs.getString("rule_version"),
        rs.getObject("checked_at", OffsetDateTime.class),
        rs.getString("naver_id"),
        rs.getString("name"),
        rs.getString("category"),
        rs.getString("address"),
        rs.getString("phone"),
        rs.getString("hours"),
        score == null ? null : score.doubleValue(),
        rs.getObject("review_count", Integer.class),
        rs.getObject("blog_reviews", Integer.class),
        images == null ? List.of() : Arrays.asList((String[]) images.getArray()),
        rs.getString("url"));
  }
}
