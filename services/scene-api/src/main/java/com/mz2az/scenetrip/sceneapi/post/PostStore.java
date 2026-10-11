package com.mz2az.scenetrip.sceneapi.post;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 커뮤니티 여행후기(V27, 계약 1.10.0 {@code /posts}, 계획 {@code community-post.md}).
 *
 * <p>코스는 <b>글 쓴 순간의 사본</b>이다 — 쓰는 때 그 코스의 촬영지·편의시설 항목을 {@code community_post_item} 으로 떠 둔다. 직접 찍은
 * 핀은 떠 두지 않는다(개인 숙소 위치, 마켓과 같다). 이름·주소·사진은 떠 두지 않고 읽을 때 지금의 자료에서 가져온다.
 *
 * <p>운영자가 내린 글({@code removed_at})은 목록·상세·담기 어디에도 없다. 글쓴이가 탈퇴한 글({@code user_id} NULL)은 남는다.
 */
@Repository
public class PostStore {

  /** 목록의 본문 앞부분 길이(계약 {@code excerpt}). */
  static final int EXCERPT = 120;

  public record Page<T>(List<T> items, int total) {}

  /**
   * 목록의 한 줄.
   *
   * @param nickname 글쓴이. 탈퇴했으면 {@code null}
   * @param coverKey 첫 사진의 저장소 키. 사진이 없으면 {@code null}
   * @param courseTitle 붙인 코스 이름. 코스가 없으면 {@code null}
   */
  public record Summary(
      long id,
      String title,
      String excerpt,
      String nickname,
      OffsetDateTime createdAt,
      String coverKey,
      int photoCount,
      String courseTitle,
      Integer courseDayCount,
      int placeCount,
      boolean mine) {}

  /** 상세 — 목록 줄에 본문 전체와 사진 키들. */
  public record Detail(Summary head, String body, List<String> photoKeys) {}

  /**
   * 코스 사본의 한 곳. 촬영지면 이름·주소가 요청 언어, 편의시설이면 한국어 원본과 표시말({@code display*}·{@code nameRoman}·{@code
   * categoryLabel}) — 코스 항목({@code CourseStore})과 같은 규칙.
   */
  public record Stop(
      int dayNo,
      Long placeId,
      Long poiId,
      String name,
      String address,
      String category,
      double latitude,
      double longitude,
      String imageUrl,
      int dwellMinutes,
      String displayName,
      String nameRoman,
      String displayAddress,
      String categoryLabel) {}

  /** 붙이려는 코스가 그 사람 것이 아니거나 없다. */
  public static final class CourseRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CourseRejectedException(long courseId) {
      super("코스 " + courseId + " 은(는) 내 코스가 아닙니다");
    }
  }

  /** 붙이려는 사진 키를 받을 수 없다. */
  public static final class PhotoKeyRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PhotoKeyRejectedException(String message) {
      super(message);
    }
  }

  private static final String SUMMARY_COLUMNS =
      """
      p.id, p.title, left(regexp_replace(p.body, '\\s+', ' ', 'g'), %d) AS excerpt, u.nickname, p.created_at,
      (SELECT ph.storage_key FROM community_post_photo ph
        WHERE ph.post_id = p.id ORDER BY ph.sort_order LIMIT 1) AS cover_key,
      (SELECT count(*) FROM community_post_photo ph WHERE ph.post_id = p.id)::INT AS photo_count,
      p.course_title, p.course_day_count,
      (SELECT count(*) FROM community_post_item i WHERE i.post_id = p.id)::INT AS place_count,
      (p.user_id IS NOT NULL AND p.user_id = CAST(:viewer AS UUID)) AS mine
      """
          .formatted(EXCERPT);

  /**
   * 사본의 장소들 — {@code CourseStore} 의 항목 질의와 같은 규칙(촬영지는 요청 언어 → en → ko, 편의시설은 원본 + 표시말). 숨긴 촬영지·폐업한
   * 편의시설도 보인다 — 사본이다.
   */
  private static final String STOPS_SQL =
      """
      WITH place_display AS (
          SELECT DISTINCT ON (pi.place_id) pi.place_id, pi.name, pi.address
          FROM place_i18n pi
          WHERE pi.lang IN (:lang, 'en', 'ko')
          ORDER BY pi.place_id, (pi.lang = :lang) DESC, (pi.lang = 'en') DESC
      )
      SELECT
          i.day_no, i.place_id, i.poi_id, i.dwell_min,
          COALESCE(pd.name, q.name)              AS name,
          COALESCE(pd.address, q.address)        AS address,
          COALESCE(pl.type, q.category)          AS category,
          ST_Y(COALESCE(pl.geom, q.geom)::geometry) AS latitude,
          ST_X(COALESCE(pl.geom, q.geom)::geometry) AS longitude,
          COALESCE(
              (SELECT pim.url FROM place_image pim WHERE pim.place_id = pl.id
                ORDER BY pim.sort_order, pim.id LIMIT 1),
              (SELECT qim.url FROM poi_image qim WHERE qim.poi_id = q.id
                ORDER BY qim.sort_order, qim.id LIMIT 1)) AS image_url,
          q.name_roman AS poi_name_roman,
          CASE WHEN q.id IS NULL OR :lang = 'ko' THEN NULL
               ELSE COALESCE(qtr.name, qte.name) END AS poi_display_name,
          CASE WHEN q.id IS NULL OR :lang = 'ko' THEN NULL
               ELSE COALESCE(qtr.address, qte.address) END AS poi_display_address,
          CASE WHEN q.id IS NULL THEN NULL
               WHEN :lang = 'ko' THEN q.category
               ELSE COALESCE(qcr.name, qce.name, q.category) END AS poi_category_label
      FROM community_post_item i
      LEFT JOIN place pl ON pl.id = i.place_id
      LEFT JOIN place_display pd ON pd.place_id = pl.id
      LEFT JOIN poi q ON q.id = i.poi_id
      LEFT JOIN poi_i18n qtr ON qtr.poi_id = q.id AND qtr.lang = :lang
      LEFT JOIN poi_i18n qte ON qte.poi_id = q.id AND qte.lang = 'en'
      LEFT JOIN poi_category_i18n qcr ON qcr.ko = q.category AND qcr.lang = :lang
      LEFT JOIN poi_category_i18n qce ON qce.ko = q.category AND qce.lang = 'en'
      WHERE i.post_id = :postId
      ORDER BY i.day_no, i.sort_order
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public PostStore(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /**
   * 쓴다. {@code photoKeys} 는 이 순서대로 붙고 첫 장이 대표다 — 모두 {@code fresh}(이번 요청에서 막 {@code posts/} 로 옮긴
   * 키)여야 한다. {@code courseId} 가 있으면 그 사람의 코스여야 하고, 촬영지·편의시설 항목만 떠 둔다.
   *
   * @return 새 글의 id
   * @throws CourseRejectedException 남의 코스·없는 코스
   * @throws PhotoKeyRejectedException 받을 수 없는 사진 키
   */
  public long create(
      UUID user,
      String title,
      String body,
      List<String> photoKeys,
      Collection<String> fresh,
      Long courseId) {
    if (new HashSet<>(photoKeys).size() != photoKeys.size()) {
      throw new PhotoKeyRejectedException("같은 사진이 두 번 있습니다");
    }
    Set<String> allowed = new HashSet<>(fresh);
    for (String key : photoKeys) {
      if (!allowed.contains(key)) {
        throw new PhotoKeyRejectedException("올리지 않았거나 만료된 사진입니다");
      }
    }
    return transactions.execute(
        status -> {
          String courseTitle = null;
          Integer dayCount = null;
          if (courseId != null) {
            record Course(String title, int dayCount) {}
            Course c =
                jdbc.sql(
                        "SELECT title, day_count FROM course"
                            + " WHERE id = :courseId AND user_id = CAST(:user AS UUID)")
                    .param("courseId", courseId)
                    .param("user", user.toString())
                    .query((rs, n) -> new Course(rs.getString("title"), rs.getInt("day_count")))
                    .optional()
                    .orElseThrow(() -> new CourseRejectedException(courseId));
            courseTitle = c.title();
            dayCount = c.dayCount();
          }

          long postId =
              jdbc.sql(
                      """
                      INSERT INTO community_post (user_id, title, body, course_title, course_day_count)
                      VALUES (CAST(:user AS UUID), :title, :body, :courseTitle, CAST(:dayCount AS INT))
                      RETURNING id
                      """)
                  .param("user", user.toString())
                  .param("title", title)
                  .param("body", body)
                  .param("courseTitle", courseTitle)
                  .param("dayCount", dayCount)
                  .query(Long.class)
                  .single();

          for (int i = 0; i < photoKeys.size(); i++) {
            jdbc.sql(
                    "INSERT INTO community_post_photo (post_id, storage_key, sort_order)"
                        + " VALUES (:postId, :key, :order)")
                .param("postId", postId)
                .param("key", photoKeys.get(i))
                .param("order", i)
                .update();
          }

          if (courseId != null) {
            // 촬영지·편의시설 항목만 — 직접 찍은 핀(custom_pin_id)은 떠 두지 않는다.
            jdbc.sql(
                    """
                    INSERT INTO community_post_item
                        (post_id, day_no, sort_order, place_id, poi_id, dwell_min, source_content_id)
                    SELECT :postId, ci.day_no, ci.sort_order, ci.place_id, ci.poi_id, ci.dwell_min,
                           ci.source_content_id
                    FROM course_item ci
                    WHERE ci.course_id = :courseId
                      AND (ci.place_id IS NOT NULL OR ci.poi_id IS NOT NULL)
                    """)
                .param("postId", postId)
                .param("courseId", courseId)
                .update();
          }
          return postId;
        });
  }

  /** 목록, 최신순. {@code author} 가 있으면 그 사람의 글만. */
  public Page<Summary> list(UUID viewer, UUID author, int limit, int offset) {
    String where =
        "p.removed_at IS NULL" + (author == null ? "" : " AND p.user_id = CAST(:author AS UUID)");
    var listQuery =
        jdbc.sql(
                "SELECT "
                    + SUMMARY_COLUMNS
                    + " FROM community_post p LEFT JOIN app_user u ON u.id = p.user_id WHERE "
                    + where
                    + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset")
            .param("viewer", viewer == null ? null : viewer.toString())
            .param("limit", limit)
            .param("offset", offset);
    var countQuery = jdbc.sql("SELECT count(*) FROM community_post p WHERE " + where);
    if (author != null) {
      listQuery = listQuery.param("author", author.toString());
      countQuery = countQuery.param("author", author.toString());
    }
    List<Summary> items = listQuery.query(PostStore::mapSummary).list();
    int total = countQuery.query(Integer.class).single();
    return new Page<>(items, total);
  }

  /** 상세. 없거나 내린 글이면 비어 있다. */
  public Optional<Detail> find(long postId, UUID viewer) {
    Optional<Detail> head =
        jdbc.sql(
                "SELECT "
                    + SUMMARY_COLUMNS
                    + ", p.body FROM community_post p LEFT JOIN app_user u ON u.id = p.user_id"
                    + " WHERE p.id = :postId AND p.removed_at IS NULL")
            .param("postId", postId)
            .param("viewer", viewer == null ? null : viewer.toString())
            .query((rs, n) -> new Detail(mapSummary(rs, n), rs.getString("body"), List.of()))
            .optional();
    return head.map(
        d ->
            new Detail(
                d.head(),
                d.body(),
                jdbc.sql(
                        "SELECT storage_key FROM community_post_photo"
                            + " WHERE post_id = :postId ORDER BY sort_order")
                    .param("postId", postId)
                    .query(String.class)
                    .list()));
  }

  /** 코스 사본의 장소들, 일차·순서대로. */
  public List<Stop> stops(long postId, String lang) {
    return jdbc.sql(STOPS_SQL)
        .param("postId", postId)
        .param("lang", lang)
        .query(
            (rs, n) ->
                new Stop(
                    rs.getInt("day_no"),
                    longOrNull(rs, "place_id"),
                    longOrNull(rs, "poi_id"),
                    rs.getString("name"),
                    rs.getString("address"),
                    rs.getString("category"),
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude"),
                    rs.getString("image_url"),
                    rs.getInt("dwell_min"),
                    rs.getString("poi_display_name"),
                    rs.getString("poi_name_roman"),
                    rs.getString("poi_display_address"),
                    rs.getString("poi_category_label")))
        .list();
  }

  /** 내 글을 지운다. 사진·코스 사본 행은 CASCADE. 내 글이 아니거나 없으면 {@code false}. */
  public boolean delete(long postId, UUID user) {
    return jdbc.sql(
                "DELETE FROM community_post WHERE id = :postId AND user_id = CAST(:user AS UUID)")
            .param("postId", postId)
            .param("user", user.toString())
            .update()
        > 0;
  }

  /**
   * 후기의 코스를 내 코스로 담는다 — 순서·체류 그대로, 날짜는 비운다. 만든 방식은 {@code market}(계약 — 후기가 마켓 자리를 대신하고, {@code
   * CourseOrigin} 에 값을 더하면 낡은 앱이 코스를 못 읽는다). 편의시설 항목이 지금 촬영지와 같은 곳으로 연결돼 있으면 촬영지로 담는다
   * (MZ2AZ-371·377 — 코스 저장과 같은 규칙).
   *
   * @return 새 코스의 id. 글이 없거나 내렸거나 코스가 붙지 않았으면 비어 있다
   */
  public Optional<Long> save(UUID user, long postId) {
    return transactions.execute(
        status -> {
          Optional<Long> courseId =
              jdbc.sql(
                      """
                      INSERT INTO course (user_id, title, day_count, origin)
                      SELECT CAST(:user AS UUID), p.course_title, p.course_day_count, 'market'
                      FROM community_post p
                      WHERE p.id = :postId AND p.removed_at IS NULL AND p.course_title IS NOT NULL
                      RETURNING id
                      """)
                  .param("user", user.toString())
                  .param("postId", postId)
                  .query(Long.class)
                  .optional();
          courseId.ifPresent(
              id ->
                  jdbc.sql(
                          """
                          INSERT INTO course_item
                              (course_id, day_no, place_id, poi_id, sort_order, dwell_min, source_content_id)
                          SELECT :courseId, i.day_no,
                                 COALESCE(i.place_id, lp.id),
                                 CASE WHEN i.place_id IS NULL AND lp.id IS NULL THEN i.poi_id END,
                                 i.sort_order, i.dwell_min, i.source_content_id
                          FROM community_post_item i
                          LEFT JOIN place_poi_link l ON l.poi_id = i.poi_id
                          LEFT JOIN place lp ON lp.id = l.place_id AND lp.hidden_at IS NULL
                          WHERE i.post_id = :postId
                          """)
                      .param("courseId", id)
                      .param("postId", postId)
                      .update());
          return courseId;
        });
  }

  private static Summary mapSummary(ResultSet rs, int rowNum) throws SQLException {
    Integer day = rs.getObject("course_day_count", Integer.class);
    return new Summary(
        rs.getLong("id"),
        rs.getString("title"),
        rs.getString("excerpt"),
        rs.getString("nickname"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getString("cover_key"),
        rs.getInt("photo_count"),
        rs.getString("course_title"),
        day,
        rs.getInt("place_count"),
        rs.getBoolean("mine"));
  }

  private static Long longOrNull(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
