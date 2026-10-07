package com.mz2az.scenetrip.sceneapi.review;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 촬영지·편의시설 리뷰(V21, 계획 {@code docs/project/plans/review.md}).
 *
 * <p>한 표에 둘이 함께 있고 줄마다 {@code place_id} 와 {@code poi_id} 중 하나만 찬다. 어느 쪽인지는 {@link Target} 이 칸 이름으로
 * 가른다 — 질의는 한 벌이고 칸 이름만 바뀐다. 칸 이름은 이 클래스 안의 상수에서만 오므로 SQL 에 끼워 넣어도 안전하다.
 *
 * <p>운영자가 내린 리뷰({@code removed_at})는 목록·평균·사진첩 어디에도 나오지 않는다. 작성자가 탈퇴한 리뷰({@code user_id} NULL)는
 * 남는다.
 */
@Repository
public class ReviewStore {

  /** 리뷰가 달리는 곳. */
  public enum Target {
    PLACE("place_id"),
    POI("poi_id");

    private final String column;

    Target(String column) {
      this.column = column;
    }
  }

  /** 정렬. 같은 별점이면 최신이 앞선다. */
  public enum Sort {
    RECENT("r.created_at DESC, r.id DESC"),
    RATING_HIGH("r.rating DESC, r.created_at DESC, r.id DESC"),
    RATING_LOW("r.rating ASC, r.created_at DESC, r.id DESC");

    private final String orderBy;

    Sort(String orderBy) {
      this.orderBy = orderBy;
    }
  }

  /** 단순 평균과 수, 1~5 점 분포. 리뷰가 없으면 평균은 {@code null}. */
  public record Summary(Double average, int count, List<Integer> distribution) {}

  /**
   * 리뷰 한 줄.
   *
   * @param nickname 작성자 닉네임. 탈퇴했으면 {@code null}
   * @param photoKeys 저장소 키, 올린 순서대로
   * @param mine 보는 사람이 쓴 것인가
   */
  public record Row(
      long id,
      int rating,
      String body,
      String nickname,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      boolean mine,
      List<String> photoKeys) {}

  /** 목록 한 장과 전체 수. */
  public record Page<T>(List<T> items, int total) {}

  /** 내 리뷰 목록의 한 줄 — 어느 곳의 리뷰인지가 붙는다. 촬영지 이름은 요청 언어 → en → ko, 편의시설은 한국어 원본. */
  public record MineRow(Row review, Target target, long targetId, String targetName) {}

  /**
   * 사진첩의 한 장.
   *
   * @param url 우리 사진의 주소. 리뷰 사진이면 {@code null} — 키로 서명해 만든다
   * @param storageKey 리뷰 사진의 저장소 키. 우리 사진이면 {@code null}
   * @param reviewId 리뷰 사진이면 그 리뷰
   * @param credit 우리 사진의 저작자 표기
   */
  public record Photo(String url, String storageKey, Long reviewId, String credit) {}

  /** 리뷰를 저장할 때 사진 키가 받아들여지지 않았다. */
  public static final class PhotoKeyRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PhotoKeyRejectedException(String message) {
      super(message);
    }
  }

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public ReviewStore(JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /** 리뷰를 달 수 있는 곳인가 — 상세가 열리는 곳과 같다. 촬영지는 숨긴 것도(V20, 상세는 열린다), 편의시설은 폐업하지 않은 것만(폐업하면 상세가 404 다). */
  public boolean exists(Target target, long id) {
    String sql =
        target == Target.PLACE
            ? "SELECT EXISTS (SELECT 1 FROM place WHERE id = :id)"
            : "SELECT EXISTS (SELECT 1 FROM poi WHERE id = :id AND closed_at IS NULL)";
    return Boolean.TRUE.equals(jdbc.sql(sql).param("id", id).query(Boolean.class).single());
  }

  /** 단순 평균(소수 한 자리)과 수, 분포. */
  public Summary summary(Target target, long id) {
    return jdbc.sql(
            """
            SELECT round(avg(rating)::NUMERIC, 1)::DOUBLE PRECISION AS average,
                   count(*) AS n,
                   count(*) FILTER (WHERE rating = 1) AS r1,
                   count(*) FILTER (WHERE rating = 2) AS r2,
                   count(*) FILTER (WHERE rating = 3) AS r3,
                   count(*) FILTER (WHERE rating = 4) AS r4,
                   count(*) FILTER (WHERE rating = 5) AS r5
            FROM review
            WHERE %s = :id AND removed_at IS NULL
            """
                .formatted(target.column))
        .param("id", id)
        .query(
            (rs, n) -> {
              double avg = rs.getDouble("average");
              return new Summary(
                  rs.wasNull() ? null : avg,
                  rs.getInt("n"),
                  List.of(
                      rs.getInt("r1"),
                      rs.getInt("r2"),
                      rs.getInt("r3"),
                      rs.getInt("r4"),
                      rs.getInt("r5")));
            })
        .single();
  }

  /** 그곳의 리뷰 한 장. {@code viewer} 는 토큰을 보낸 가입 사용자, 없으면 {@code null}. */
  public Page<Row> list(Target target, long id, Sort sort, int limit, int offset, UUID viewer) {
    List<Row> rows =
        jdbc.sql(
                """
                SELECT r.id, r.rating, r.body, u.nickname, r.created_at, r.updated_at,
                       (r.user_id IS NOT NULL AND r.user_id = CAST(:viewer AS UUID)) AS mine
                FROM review r
                LEFT JOIN app_user u ON u.id = r.user_id
                WHERE r.%s = :id AND r.removed_at IS NULL
                ORDER BY %s
                LIMIT :limit OFFSET :offset
                """
                    .formatted(target.column, sort.orderBy))
            .param("id", id)
            .param("viewer", viewer == null ? null : viewer.toString())
            .param("limit", limit)
            .param("offset", offset)
            .query((rs, n) -> row(rs, List.of()))
            .list();
    int total =
        jdbc.sql(
                "SELECT count(*) FROM review WHERE %s = :id AND removed_at IS NULL"
                    .formatted(target.column))
            .param("id", id)
            .query(Integer.class)
            .single();
    return new Page<>(withPhotos(rows), total);
  }

  /** 그곳에 내가 쓴 리뷰. 운영자가 내린 것은 없는 것으로 본다. */
  public Optional<Row> mine(Target target, long id, UUID user) {
    return mine(target, id, user, false);
  }

  private Optional<Row> mine(Target target, long id, UUID user, boolean includeRemoved) {
    return jdbc.sql(
            """
            SELECT r.id, r.rating, r.body, u.nickname, r.created_at, r.updated_at, true AS mine
            FROM review r
            JOIN app_user u ON u.id = r.user_id
            WHERE r.%s = :id AND r.user_id = CAST(:user AS UUID)%s
            """
                .formatted(target.column, includeRemoved ? "" : " AND r.removed_at IS NULL"))
        .param("id", id)
        .param("user", user.toString())
        .query((rs, n) -> row(rs, List.of()))
        .optional()
        .map(r -> withPhotos(List.of(r)).get(0));
  }

  /**
   * 쓰기·고치기 — 보낸 것이 전부다(계약 PUT). 사진도 보낸 순서로 통째로 바뀐다.
   *
   * <p>받아들이는 사진 키는 둘이다: 이 리뷰에 이미 붙어 있던 것, 그리고 {@code uploadedKeys} — 이 사용자가 올려 아직 어디에도 붙지 않은 것 (사진
   * 올리기 창구가 판정해 넘긴다). 그 밖의 키는 {@link PhotoKeyRejectedException}.
   *
   * <p>한 트랜잭션이다. {@code @Transactional} 이 아니라 {@link TransactionTemplate} 인 이유는 {@code
   * CourseStore#replace} 의 주석과 같다 — 통합 테스트가 Store 를 스프링 없이 만든다.
   */
  public Row put(
      Target target,
      long id,
      UUID user,
      int rating,
      String body,
      List<String> photoKeys,
      Collection<String> uploadedKeys) {
    return transactions.execute(
        status -> {
          Long reviewId =
              jdbc.sql(
                      """
                      INSERT INTO review (%1$s, user_id, rating, body)
                      VALUES (:id, CAST(:user AS UUID), :rating, :body)
                      ON CONFLICT (user_id, %1$s) DO UPDATE
                          SET rating = EXCLUDED.rating, body = EXCLUDED.body, updated_at = now()
                      RETURNING id
                      """
                          .formatted(target.column))
                  .param("id", id)
                  .param("user", user.toString())
                  .param("rating", rating)
                  .param("body", body)
                  .query(Long.class)
                  .single();

          Set<String> attached =
              new HashSet<>(
                  jdbc.sql("SELECT storage_key FROM review_image WHERE review_id = :r")
                      .param("r", reviewId)
                      .query(String.class)
                      .list());
          Set<String> seen = new HashSet<>();
          for (String key : photoKeys) {
            if (!seen.add(key)) {
              throw new PhotoKeyRejectedException("같은 사진이 두 번 있습니다");
            }
            if (!attached.contains(key) && !uploadedKeys.contains(key)) {
              throw new PhotoKeyRejectedException("올리지 않았거나 만료된 사진입니다");
            }
          }

          jdbc.sql("DELETE FROM review_image WHERE review_id = :r").param("r", reviewId).update();
          for (int i = 0; i < photoKeys.size(); i++) {
            jdbc.sql(
                    "INSERT INTO review_image (review_id, storage_key, sort_order)"
                        + " VALUES (:r, :key, :order)")
                .param("r", reviewId)
                .param("key", photoKeys.get(i))
                .param("order", i)
                .update();
          }
          // 운영자가 내린 리뷰를 고쳐도 내림은 풀리지 않는다 — 고친 것을 돌려주되 남에게는 여전히 안 보인다.
          return mine(target, id, user, true).orElseThrow();
        });
  }

  /** 지운다. 사진 행은 CASCADE. 없었으면 {@code false}. */
  public boolean delete(Target target, long id, UUID user) {
    return jdbc.sql(
                "DELETE FROM review WHERE %s = :id AND user_id = CAST(:user AS UUID)"
                    .formatted(target.column))
            .param("id", id)
            .param("user", user.toString())
            .update()
        > 0;
  }

  /** 내가 쓴 리뷰, 촬영지·편의시설을 섞어 최신순. */
  public Page<MineRow> listMine(UUID user, String lang, int limit, int offset) {
    record Head(Row row, Target target, long targetId, String name) {}
    List<Head> heads =
        jdbc.sql(
                """
                SELECT r.id, r.rating, r.body, u.nickname, r.created_at, r.updated_at, true AS mine,
                       r.place_id, r.poi_id,
                       COALESCE(
                           (SELECT pi.name FROM place_i18n pi
                             WHERE pi.place_id = r.place_id AND pi.lang IN (:lang, 'en', 'ko')
                             ORDER BY (pi.lang = :lang) DESC, (pi.lang = 'en') DESC
                             LIMIT 1),
                           (SELECT p.name FROM poi p WHERE p.id = r.poi_id)) AS target_name
                FROM review r
                JOIN app_user u ON u.id = r.user_id
                WHERE r.user_id = CAST(:user AS UUID) AND r.removed_at IS NULL
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT :limit OFFSET :offset
                """)
            .param("user", user.toString())
            .param("lang", lang)
            .param("limit", limit)
            .param("offset", offset)
            .query(
                (rs, n) -> {
                  long placeId = rs.getLong("place_id");
                  boolean isPlace = !rs.wasNull();
                  return new Head(
                      row(rs, List.of()),
                      isPlace ? Target.PLACE : Target.POI,
                      isPlace ? placeId : rs.getLong("poi_id"),
                      rs.getString("target_name"));
                })
            .list();
    int total =
        jdbc.sql(
                "SELECT count(*) FROM review"
                    + " WHERE user_id = CAST(:user AS UUID) AND removed_at IS NULL")
            .param("user", user.toString())
            .query(Integer.class)
            .single();
    List<Row> rows = withPhotos(heads.stream().map(Head::row).toList());
    List<MineRow> items = new ArrayList<>();
    for (int i = 0; i < heads.size(); i++) {
      Head h = heads.get(i);
      items.add(new MineRow(rows.get(i), h.target(), h.targetId(), h.name()));
    }
    return new Page<>(items, total);
  }

  /**
   * 사진첩 — 우리 사진 먼저(정렬 순서), 그 뒤 리뷰 사진(최신 리뷰부터, 리뷰 안에서는 올린 순서). 운영자가 내린 리뷰의 사진은 빠진다.
   *
   * <p>우리 사진은 촬영지면 {@code place_image}(저작자 표기 칸이 없다), 편의시설이면 {@code poi_image}.
   */
  public Page<Photo> photos(Target target, long id, int limit, int offset) {
    String official =
        target == Target.PLACE
            ? "SELECT url, NULL::TEXT AS credit, sort_order, id FROM place_image WHERE place_id ="
                + " :id"
            : "SELECT url, credit, sort_order, id FROM poi_image WHERE poi_id = :id";
    String union =
        """
        SELECT o.url, NULL::TEXT AS storage_key, NULL::BIGINT AS review_id, o.credit,
               0 AS part, NULL::TIMESTAMPTZ AS created_at, o.sort_order, o.id
        FROM (%s) o
        UNION ALL
        SELECT NULL, ri.storage_key, r.id, NULL,
               1, r.created_at, ri.sort_order, r.id
        FROM review r
        JOIN review_image ri ON ri.review_id = r.id
        WHERE r.%s = :id AND r.removed_at IS NULL
        """
            .formatted(official, target.column);
    List<Photo> items =
        jdbc.sql(
                """
                SELECT * FROM (%s) u
                ORDER BY part,
                         -- 우리 사진은 정렬 순서대로, 리뷰 사진은 최신 리뷰부터 그 안에서 올린 순서로
                         CASE WHEN part = 0 THEN sort_order END,
                         CASE WHEN part = 0 THEN id END,
                         created_at DESC, id DESC, sort_order
                LIMIT :limit OFFSET :offset
                """
                    .formatted(union))
            .param("id", id)
            .param("limit", limit)
            .param("offset", offset)
            .query(
                (rs, n) -> {
                  long reviewId = rs.getLong("review_id");
                  return new Photo(
                      rs.getString("url"),
                      rs.getString("storage_key"),
                      rs.wasNull() ? null : reviewId,
                      rs.getString("credit"));
                })
            .list();
    int total =
        jdbc.sql("SELECT count(*) FROM (%s) u".formatted(union))
            .param("id", id)
            .query(Integer.class)
            .single();
    return new Page<>(items, total);
  }

  private static Row row(java.sql.ResultSet rs, List<String> photoKeys)
      throws java.sql.SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getInt("rating"),
        rs.getString("body"),
        rs.getString("nickname"),
        rs.getObject("created_at", OffsetDateTime.class),
        rs.getObject("updated_at", OffsetDateTime.class),
        rs.getBoolean("mine"),
        photoKeys);
  }

  /** 리뷰들의 사진 키를 한 번에 읽어 붙인다 — 리뷰마다 묻지 않는다. */
  private List<Row> withPhotos(List<Row> rows) {
    if (rows.isEmpty()) {
      return rows;
    }
    Map<Long, List<String>> keys = new LinkedHashMap<>();
    rows.forEach(r -> keys.put(r.id(), new ArrayList<>()));
    jdbc.sql(
            "SELECT review_id, storage_key FROM review_image"
                + " WHERE review_id IN (:ids) ORDER BY review_id, sort_order")
        .param("ids", keys.keySet())
        .query(
            rs -> {
              keys.get(rs.getLong("review_id")).add(rs.getString("storage_key"));
            });
    return rows.stream()
        .map(
            r ->
                new Row(
                    r.id(),
                    r.rating(),
                    r.body(),
                    r.nickname(),
                    r.createdAt(),
                    r.updatedAt(),
                    r.mine(),
                    List.copyOf(keys.get(r.id()))))
        .toList();
  }
}
