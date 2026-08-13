package com.mz2az.scenetrip.sceneapi.market;

import com.mz2az.scenetrip.sceneapi.api.model.ContentRef;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.MarketDay;
import com.mz2az.scenetrip.sceneapi.api.model.MarketItem;
import com.mz2az.scenetrip.sceneapi.api.model.MarketSort;
import com.mz2az.scenetrip.sceneapi.api.model.TravelBasis;
import com.mz2az.scenetrip.sceneapi.course.TravelEstimator;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 코스마켓 — 올리기·담기·내리기.
 *
 * <p><b>올린 코스는 사본이다.</b> 올리는 순간의 일차·순서·머무는 시간을 통째로 떠서 저장하고, 그 뒤에 원본을 고쳐도 마켓의 것은 그대로 남는다. 남이 담아 간
 * 코스가 어느 날 갑자기 바뀌면 안 되기 때문이다. 그래서 {@code market_course_item} 이 {@code course_item} 을 참조하지 않고 값을 복사해
 * 둔다.
 *
 * <p><b>장소만은 사본으로 굳히지 않는다.</b> {@code place} 를 계속 참조한다 — 이름이나 주소가 나중에 고쳐지면 마켓에서도 고쳐진 값이 보이는 편이 맞다.
 * 사본이어야 하는 것은 장소가 아니라 순서와 머무는 시간이다.
 *
 * <p><b>직접 찍은 핀은 올릴 때 빠진다.</b> 개인 숙소 위치가 남에게 공개되면 안 되고, 핀은 코스에 매달려 있어 올린 사람이 코스를 지우면 함께 사라져 끊어진 참조가
 * 되기 때문이다.
 */
@Repository
public class MarketStore {

  /**
   * 목록.
   *
   * <p>내려간 것은 나오지 않는다. {@code q} 는 <b>작품 이름</b>으로 찾는데, {@code market_course_content} 태그를 타고 {@code
   * search_term} 을 보므로 한글 제목·영문 제목·줄임말이 모두 걸린다.
   *
   * <p>{@code liked}·{@code saved}·{@code mine} 은 <b>보는 사람에 따라 달라지는 값</b>이라 목록 질의가 함께 낸다. 따로 조회하면
   * 코스 수만큼 왕복이 생긴다.
   *
   * <p>파라미터에 {@code CAST(... AS TEXT)} 가 붙은 이유는 ContentStore 와 같다 — NULL 비교만 있는 자리는 PostgreSQL 이
   * 타입을 추론하지 못한다.
   */
  private static final String LIST_SQL =
      """
      WITH params AS (
          SELECT search_normalize(COALESCE(CAST(:q AS TEXT), '')) AS norm
      )
      SELECT
          m.id, m.title, m.description, m.day_count,
          m.like_count, m.save_count, m.published_at,
          (SELECT count(*) FROM market_course_item mi WHERE mi.market_course_id = m.id)
              AS place_count,
          (SELECT pim.url
             FROM market_course_item mi
             JOIN place_image pim ON pim.place_id = mi.place_id
            WHERE mi.market_course_id = m.id
            ORDER BY mi.day_no, mi.sort_order, pim.sort_order, pim.id
            LIMIT 1) AS thumbnail_url,
          EXISTS (SELECT 1 FROM market_like l
                   WHERE l.market_course_id = m.id
                     AND l.user_id = CAST(:userId AS UUID)) AS liked,
          EXISTS (SELECT 1 FROM course c
                   WHERE c.source_market_course_id = m.id
                     AND c.user_id = CAST(:userId AS UUID)) AS saved,
          m.author_id = CAST(:userId AS UUID) AS mine,
          count(*) OVER () AS total_count
      FROM market_course m
      CROSS JOIN params p
      WHERE m.unpublished_at IS NULL
        AND (p.norm = '' OR EXISTS (
              SELECT 1
              FROM market_course_content mcc
              JOIN search_term st
                ON st.entity_type = 'content' AND st.entity_id = mcc.content_id
              WHERE mcc.market_course_id = m.id
                AND st.term_norm LIKE '%' || p.norm || '%'))
      ORDER BY
          CASE WHEN :sort = 'likes' THEN m.like_count ELSE m.save_count END DESC,
          m.id DESC
      LIMIT :limit OFFSET :offset
      """;

  private static final String FIND_SQL =
      """
      SELECT
          m.id, m.title, m.description, m.day_count,
          m.like_count, m.save_count, m.published_at,
          (SELECT count(*) FROM market_course_item mi WHERE mi.market_course_id = m.id)
              AS place_count,
          (SELECT pim.url
             FROM market_course_item mi
             JOIN place_image pim ON pim.place_id = mi.place_id
            WHERE mi.market_course_id = m.id
            ORDER BY mi.day_no, mi.sort_order, pim.sort_order, pim.id
            LIMIT 1) AS thumbnail_url,
          EXISTS (SELECT 1 FROM market_like l
                   WHERE l.market_course_id = m.id
                     AND l.user_id = CAST(:userId AS UUID)) AS liked,
          EXISTS (SELECT 1 FROM course c
                   WHERE c.source_market_course_id = m.id
                     AND c.user_id = CAST(:userId AS UUID)) AS saved,
          m.author_id = CAST(:userId AS UUID) AS mine,
          0 AS total_count
      FROM market_course m
      WHERE m.id = :postId AND m.unpublished_at IS NULL
      """;

  /** 사본의 항목. 구간 거리는 코스 상세와 같은 창 함수로 낸다. */
  private static final String ITEMS_SQL =
      """
      WITH place_display AS (
          SELECT DISTINCT ON (pi.place_id) pi.place_id, pi.name, pi.address
          FROM place_i18n pi
          WHERE pi.lang IN (:lang, 'ko')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC
      ),
      content_display AS (
          SELECT DISTINCT ON (ci.content_id) ci.content_id, ci.title
          FROM content_i18n ci
          WHERE ci.lang IN (:lang, 'ko')
          ORDER BY ci.content_id, (ci.lang = :lang) DESC
      ),
      item AS (
          SELECT
              mi.day_no, mi.sort_order, mi.dwell_min, mi.place_id, mi.source_content_id,
              pd.name, pd.address, p.type AS category, p.geom,
              cd.title AS source_content_title,
              (SELECT pim.url FROM place_image pim
                WHERE pim.place_id = p.id
                ORDER BY pim.sort_order, pim.id LIMIT 1) AS image_url
          FROM market_course_item mi
          JOIN place p ON p.id = mi.place_id
          LEFT JOIN place_display pd ON pd.place_id = p.id
          LEFT JOIN content_display cd ON cd.content_id = mi.source_content_id
          WHERE mi.market_course_id = :postId
      )
      SELECT
          day_no, sort_order, dwell_min, place_id, name, address, category, image_url,
          source_content_id, source_content_title,
          ST_Y(geom::geometry) AS latitude,
          ST_X(geom::geometry) AS longitude,
          ST_Distance(geom, lag(geom) OVER (PARTITION BY day_no ORDER BY sort_order))
              AS distance_from_previous
      FROM item
      ORDER BY day_no, sort_order
      """;

  private final JdbcClient jdbc;
  private final TravelEstimator travel;
  private final TransactionTemplate transactions;

  /** 다른 패키지의 통합 테스트가 직접 만들 수 있어야 해서 public 이다. */
  public MarketStore(JdbcClient jdbc, TravelEstimator travel, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.travel = travel;
    this.transactions = transactions;
  }

  /** 목록과 전체 개수. */
  public record Page(List<MarketCourseSummary> items, int total) {}

  public Page list(UUID userId, String q, MarketSort sort, int limit, int offset) {
    List<Row> rows =
        jdbc.sql(LIST_SQL)
            .param("userId", userId.toString())
            .param("q", q)
            .param("sort", (sort == null ? MarketSort.SAVES : sort).getValue())
            .param("limit", limit)
            .param("offset", offset)
            .query(MarketStore::mapRow)
            .list();

    return new Page(
        rows.stream().map(Row::summary).toList(), rows.isEmpty() ? 0 : rows.get(0).total());
  }

  public Optional<MarketCourseDetail> find(UUID userId, long postId, Lang lang) {
    return jdbc.sql(FIND_SQL)
        .param("postId", postId)
        .param("userId", userId.toString())
        .query(MarketStore::mapRow)
        .optional()
        .map(
            row -> {
              // 작품 태그는 상세에도 실어야 한다. 카드의 배지이자 "이 코스가 무슨 작품
              // 코스인가" 를 말하는 값이라, 목록에만 있고 상세에 없으면 코스를 열자마자
              // 그 정보가 사라진다.
              row.summary().setContents(contents(postId, lang));
              return detail(row.summary(), days(postId, row.summary().getDayCount(), lang));
            });
  }

  /**
   * 올린다 — 지금 모습을 통째로 뜬다.
   *
   * <p>세 단계가 한 트랜잭션이다. 사본 머리를 만들고, 항목을 복사하고, 작품 태그를 굳힌다. 중간에 끊기면 항목 없는 사본이나 태그 없는 사본이 남는데 둘 다 화면에서
   * 빈 코스로 보인다.
   *
   * @return 만들어진 사본의 id. 이미 올라가 있으면 {@link Optional#empty()}
   */
  public Optional<Long> publish(UUID userId, long courseId, String description) {
    return transactions.execute(
        status -> {
          Long postId;
          try {
            postId =
                jdbc.sql(
                        """
                        INSERT INTO market_course
                            (author_id, source_course_id, title, description, day_count)
                        SELECT CAST(:userId AS UUID), c.id, c.title, :description, c.day_count
                        FROM course c
                        WHERE c.id = :courseId AND c.user_id = CAST(:userId AS UUID)
                        RETURNING id
                        """)
                    .param("userId", userId.toString())
                    .param("courseId", courseId)
                    .param("description", description)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
          } catch (DuplicateKeyException alreadyLive) {
            // V11 의 부분 유니크 인덱스. 한 코스에서 살아 있는 사본은 하나뿐이다.
            return Optional.empty();
          }
          if (postId == null) {
            return Optional.empty();
          }

          // place_id IS NOT NULL 이 직접 찍은 핀을 걸러 낸다. 그래서 사본의 장소 수가
          // 원본보다 적을 수 있고, 핀만 있던 일차는 빈 일차로 올라간다.
          jdbc.sql(
                  """
                  INSERT INTO market_course_item
                      (market_course_id, day_no, place_id, sort_order, dwell_min,
                       source_content_id)
                  SELECT :postId, i.day_no, i.place_id, i.sort_order, i.dwell_min,
                         i.source_content_id
                  FROM course_item i
                  WHERE i.course_id = :courseId AND i.place_id IS NOT NULL
                  """)
              .param("postId", postId)
              .param("courseId", courseId)
              .update();

          // 작품 태그를 값으로 굳힌다. 담긴 장소의 place_content 로 매번 계산할 수도
          // 있지만, 사본이라 나중에 장소-작품 연결이 늘어도 올릴 때의 태그가 유지돼야
          // 하고 작품 이름 검색이 이 표만 읽으면 끝난다.
          jdbc.sql(
                  """
                  INSERT INTO market_course_content (market_course_id, content_id)
                  SELECT DISTINCT :postId, pc.content_id
                  FROM market_course_item mi
                  JOIN place_content pc ON pc.place_id = mi.place_id
                  WHERE mi.market_course_id = :postId
                  ON CONFLICT DO NOTHING
                  """)
              .param("postId", postId)
              .update();

          return Optional.of(postId);
        });
  }

  /**
   * 내린다.
   *
   * <p>행을 지우지 않고 시각만 찍는다 — 이미 담아 간 사람의 {@code course.source_market_course_id} 가 이 행을 가리키고 있어서, 지우면
   * 그 사람 화면에서 출처가 사라진다.
   *
   * @return 올린 사람이 맞고 실제로 내려갔으면 {@code true}
   */
  public boolean unpublish(UUID userId, long postId) {
    return jdbc.sql(
                """
                UPDATE market_course SET unpublished_at = now()
                 WHERE id = :postId AND author_id = CAST(:userId AS UUID)
                   AND unpublished_at IS NULL
                """)
            .param("postId", postId)
            .param("userId", userId.toString())
            .update()
        > 0;
  }

  /** 살아 있는 사본인가. 404 와 403 을 가르는 데 쓴다. */
  public boolean isLive(long postId) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM market_course"
                    + " WHERE id = :postId AND unpublished_at IS NULL)")
            .param("postId", postId)
            .query(Boolean.class)
            .single());
  }

  /** 이 사본을 올린 사람인가. */
  public boolean isAuthor(UUID userId, long postId) {
    return Boolean.TRUE.equals(
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM market_course"
                    + " WHERE id = :postId AND author_id = CAST(:userId AS UUID))")
            .param("postId", postId)
            .param("userId", userId.toString())
            .query(Boolean.class)
            .single());
  }

  /**
   * 담는다 — 내 코스로 복사한다.
   *
   * <p>순서와 머무는 시간을 그대로 옮기고 <b>날짜는 묻지 않는다.</b> 언제 갈지는 담아 가는 사람이 정한다. 담은 뒤로는 원본과 아무 관계가 없어 마음대로 고칠 수
   * 있고, 같은 코스를 여러 번 담으면 그때마다 새 코스가 생긴다.
   *
   * @return 새로 만들어진 내 코스의 id
   */
  public long save(UUID userId, long postId) {
    return transactions.execute(
        status -> {
          long courseId =
              jdbc.sql(
                      """
                      INSERT INTO course
                          (user_id, title, day_count, origin, source_market_course_id)
                      SELECT CAST(:userId AS UUID), m.title, m.day_count, 'market', m.id
                      FROM market_course m
                      WHERE m.id = :postId AND m.unpublished_at IS NULL
                      RETURNING id
                      """)
                  .param("userId", userId.toString())
                  .param("postId", postId)
                  .query(Long.class)
                  .single();

          // source_content_id 를 함께 옮긴다. 없으면 담아 간 코스가 장소마다 "어느 작품
          // 때문에 담겼는지" 를 잃는다 — 작품 태그는 코스 단위라 거기까지 말하지 못한다.
          jdbc.sql(
                  """
                  INSERT INTO course_item
                      (course_id, day_no, place_id, sort_order, dwell_min, source_content_id)
                  SELECT :courseId, mi.day_no, mi.place_id, mi.sort_order, mi.dwell_min,
                         mi.source_content_id
                  FROM market_course_item mi
                  WHERE mi.market_course_id = :postId
                  """)
              .param("courseId", courseId)
              .param("postId", postId)
              .update();

          jdbc.sql("UPDATE market_course SET save_count = save_count + 1 WHERE id = :postId")
              .param("postId", postId)
              .update();

          return courseId;
        });
  }

  /**
   * 좋아요를 켜고 끈다.
   *
   * <p>토글이라 같은 상태를 두 번 요청해도 오류가 아니다. 개수 컬럼은 <b>같은 트랜잭션에서</b> 움직인다 — 행과 개수가 따로 놀면 목록 정렬이 실제 좋아요 수와
   * 어긋난다. 실제로 바뀐 행이 있을 때만 개수를 건드리는 것이 멱등을 만든다.
   */
  public void like(UUID userId, long postId, boolean liked) {
    transactions.executeWithoutResult(
        status -> {
          int changed =
              liked
                  ? jdbc.sql(
                          """
                          INSERT INTO market_like (user_id, market_course_id)
                          VALUES (CAST(:userId AS UUID), :postId)
                          ON CONFLICT DO NOTHING
                          """)
                      .param("userId", userId.toString())
                      .param("postId", postId)
                      .update()
                  : jdbc.sql(
                          "DELETE FROM market_like"
                              + " WHERE user_id = CAST(:userId AS UUID)"
                              + " AND market_course_id = :postId")
                      .param("userId", userId.toString())
                      .param("postId", postId)
                      .update();

          if (changed > 0) {
            jdbc.sql(
                    "UPDATE market_course SET like_count = like_count + :delta"
                        + " WHERE id = :postId")
                .param("delta", liked ? 1 : -1)
                .param("postId", postId)
                .update();
          }
        });
  }

  // ───────────── 안쪽 ─────────────

  private List<MarketDay> days(long postId, int dayCount, Lang lang) {
    Map<Integer, List<MarketItem>> byDay = new LinkedHashMap<>();
    Map<Integer, Integer> travelMetres = new LinkedHashMap<>();
    for (int i = 1; i <= dayCount; i++) {
      byDay.put(i, new ArrayList<>());
      travelMetres.put(i, 0);
    }

    jdbc.sql(ITEMS_SQL)
        .param("postId", postId)
        .param("lang", lang.getValue())
        .query(
            (ResultSet rs, int rowNum) -> {
              int slot = Math.min(rs.getInt("day_no"), dayCount);
              byDay.get(slot).add(mapItem(rs));
              travelMetres.merge(slot, intOrZero(rs, "distance_from_previous"), Integer::sum);
              return null;
            })
        .list();

    List<MarketDay> days = new ArrayList<>();
    for (Map.Entry<Integer, List<MarketItem>> e : byDay.entrySet()) {
      List<MarketItem> items = e.getValue();
      int dwell = items.stream().mapToInt(MarketItem::getDwellMinutes).sum();
      int travelMinutes = travel.minutes(travelMetres.get(e.getKey()));
      days.add(
          new MarketDay(
              e.getKey(),
              items,
              dwell,
              travelMinutes,
              dwell + travelMinutes,
              TravelBasis.STRAIGHT_LINE));
    }
    return days;
  }

  /** 작품 태그. 카드의 배지이자 목록 검색이 보는 대상이다. */
  private List<ContentRef> contents(long postId, Lang lang) {
    return jdbc.sql(
            """
            SELECT DISTINCT ON (c.id) c.id, ci.title, c.poster_url
            FROM market_course_content mcc
            JOIN content c ON c.id = mcc.content_id
            JOIN content_i18n ci ON ci.content_id = c.id AND ci.lang IN (:lang, 'ko')
            WHERE mcc.market_course_id = :postId
            ORDER BY c.id, (ci.lang = :lang) DESC
            """)
        .param("postId", postId)
        .param("lang", lang.getValue())
        .query(
            (ResultSet rs, int rowNum) ->
                new ContentRef(rs.getLong("id"), rs.getString("title"))
                    .posterUrl(uri(rs.getString("poster_url"))))
        .list();
  }

  /** 목록의 각 항목에도 작품 태그를 채운다. 카드가 배지를 그린다. */
  public Page withContents(Page page, Lang lang) {
    page.items().forEach(item -> item.setContents(contents(item.getId(), lang)));
    return page;
  }

  private MarketCourseDetail detail(MarketCourseSummary s, List<MarketDay> days) {
    return new MarketCourseDetail(
            s.getId(),
            s.getTitle(),
            s.getDescription(),
            s.getDayCount(),
            s.getPlaceCount(),
            s.getLikeCount(),
            s.getSaveCount(),
            s.getLiked(),
            s.getSaved(),
            s.getMine(),
            s.getPublishedAt(),
            days)
        .thumbnailUrl(s.getThumbnailUrl())
        .contents(s.getContents());
  }

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    MarketCourseSummary summary =
        new MarketCourseSummary(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getInt("day_count"),
                rs.getInt("place_count"),
                rs.getInt("like_count"),
                rs.getInt("save_count"),
                rs.getBoolean("liked"),
                rs.getBoolean("saved"),
                rs.getBoolean("mine"),
                rs.getObject("published_at", OffsetDateTime.class))
            .thumbnailUrl(uri(rs.getString("thumbnail_url")));
    return new Row(summary, rs.getInt("total_count"));
  }

  private static MarketItem mapItem(ResultSet rs) throws SQLException {
    return new MarketItem(
            rs.getLong("place_id"),
            rs.getString("name"),
            rs.getDouble("latitude"),
            rs.getDouble("longitude"),
            rs.getInt("dwell_min"))
        .address(rs.getString("address"))
        .category(rs.getString("category"))
        .imageUrl(uri(rs.getString("image_url")))
        .distanceMetersFromPrevious(integerOrNull(rs, "distance_from_previous"))
        .sourceContentId(longOrNull(rs, "source_content_id"))
        .sourceContentTitle(rs.getString("source_content_title"));
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

  private static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
    int value = (int) Math.round(rs.getDouble(column));
    return rs.wasNull() ? null : value;
  }

  private static int intOrZero(ResultSet rs, String column) throws SQLException {
    int value = (int) Math.round(rs.getDouble(column));
    return rs.wasNull() ? 0 : value;
  }

  private record Row(MarketCourseSummary summary, int total) {}
}
