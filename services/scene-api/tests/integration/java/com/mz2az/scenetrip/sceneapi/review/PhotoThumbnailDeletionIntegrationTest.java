package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 지우는 길이 작은 사진도 지울 목록에 올리는지 — 계획 {@code photo-thumbnails.md} §2-3(MZ2AZ-561).
 *
 * <p>명세에서 나온 기대: 글·리뷰를 지우거나 리뷰 사진을 뺄 때({@code PostStore}·{@code ReviewStore}·{@code UserStore} 의
 * {@code INSERT INTO storage_deletion}) 원본 키와 함께 {@code thumbnail_key} 도 올린다. 작은 사진이 아직 없는 사진은 원본만
 * 오른다(NULL 을 올리지 않는다).
 *
 * <p>작은 사진 키는 V46 칸에 직접 적는다 — 작업({@code PhotoThumbnailJob})이 적었을 자리. 전부 되돌리는 트랜잭션 하나 안에서 돈다.
 */
@DisplayName("지우기 — 원본과 작은 사진을 함께 지울 목록에 (V46, 실제 DB)")
class PhotoThumbnailDeletionIntegrationTest {

  private static JdbcClient jdbc;
  private static PostStore posts;
  private static ReviewStore reviews;
  private static UserStore users;
  private static AccountLinkStore links;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    posts = new PostStore(jdbc, IntegrationDatabase.transactions());
    reviews = new ReviewStore(jdbc, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  @Test
  @DisplayName("후기를 지우면 원본과 작은 사진이 모두 오른다 — 작은 사진이 없는 사진은 원본만")
  void postDeleteListsThumbnails() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          String a = key("posts/", "a");
          String b = key("posts/", "b");
          long postId = posts.create(me, "제목", "본문", List.of(a, b), List.of(a, b), null);
          String ta = thumb("posts/", "a");
          setPostThumb(postId, a, ta);

          assertThat(posts.delete(postId, me)).isTrue();

          assertThat(listed(a, b, ta)).containsExactlyInAnyOrder(a, b, ta);
          assertThat(nullListed()).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("리뷰를 지우면 원본과 작은 사진이 모두 오른다")
  void reviewDeleteListsThumbnails() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          long place = place();
          String a = key("reviews/", "a");
          String b = key("reviews/", "b");
          long reviewId =
              reviews.put(Target.PLACE, place, me, 5, null, List.of(a, b), Set.of(a, b)).id();
          String ta = thumb("reviews/", "a");
          String tb = thumb("reviews/", "b");
          setReviewThumb(reviewId, a, ta);
          setReviewThumb(reviewId, b, tb);

          assertThat(reviews.delete(Target.PLACE, place, me)).isTrue();

          assertThat(listed(a, b, ta, tb)).containsExactlyInAnyOrder(a, b, ta, tb);
          return null;
        });
  }

  @Test
  @DisplayName("리뷰를 고치며 사진을 빼면 뺀 사진의 원본과 작은 사진이 오른다 — 남긴 사진의 작은 사진은 오르지 않고 줄에 그대로")
  void reviewEditDropListsThumbnail() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          long place = place();
          String a = key("reviews/", "drop");
          String b = key("reviews/", "keep");
          long reviewId =
              reviews.put(Target.PLACE, place, me, 5, null, List.of(a, b), Set.of(a, b)).id();
          String ta = thumb("reviews/", "drop");
          String tb = thumb("reviews/", "keep");
          setReviewThumb(reviewId, a, ta);
          setReviewThumb(reviewId, b, tb);

          reviews.put(Target.PLACE, place, me, 4, "고침", List.of(b), Set.of());

          assertThat(listed(a, b, ta, tb)).containsExactlyInAnyOrder(a, ta);
          assertThat(
                  jdbc.sql(
                          "SELECT thumbnail_key FROM review_image"
                              + " WHERE review_id = :r AND storage_key = :k")
                      .param("r", reviewId)
                      .param("k", b)
                      .query(String.class)
                      .single())
              .as("남긴 사진은 작은 사진을 그대로 가진다")
              .isEqualTo(tb);
          return null;
        });
  }

  @Test
  @DisplayName("탈퇴하면 리뷰·후기 사진의 원본과 작은 사진이 모두 오른다")
  void accountDeleteListsThumbnails() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          long place = place();
          String r = key("reviews/", "acct");
          String p = key("posts/", "acct");
          long reviewId = reviews.put(Target.PLACE, place, me, 5, null, List.of(r), Set.of(r)).id();
          long postId = posts.create(me, "제목", "본문", List.of(p), List.of(p), null);
          String tr = thumb("reviews/", "acct");
          String tp = thumb("posts/", "acct");
          setReviewThumb(reviewId, r, tr);
          setPostThumb(postId, p, tp);

          assertThat(users.delete(me)).isTrue();

          assertThat(listed(r, p, tr, tp)).containsExactlyInAnyOrder(r, p, tr, tp);
          assertThat(nullListed()).isZero();
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  private String key(String prefix, String name) {
    return prefix + "it-thumbdel-" + tag + "-" + name + ".jpg";
  }

  private String thumb(String prefix, String name) {
    return prefix + "thumb-720/it-thumbdel-" + tag + "-" + name + ".jpg";
  }

  private static void setPostThumb(long postId, String key, String thumbnailKey) {
    assertThat(
            jdbc.sql(
                    "UPDATE community_post_photo SET thumbnail_key = :t"
                        + " WHERE post_id = :p AND storage_key = :k")
                .param("t", thumbnailKey)
                .param("p", postId)
                .param("k", key)
                .update())
        .isEqualTo(1);
  }

  private static void setReviewThumb(long reviewId, String key, String thumbnailKey) {
    assertThat(
            jdbc.sql(
                    "UPDATE review_image SET thumbnail_key = :t"
                        + " WHERE review_id = :r AND storage_key = :k")
                .param("t", thumbnailKey)
                .param("r", reviewId)
                .param("k", key)
                .update())
        .isEqualTo(1);
  }

  private static List<String> listed(String... keys) {
    return jdbc.sql("SELECT storage_key FROM storage_deletion WHERE storage_key IN (:k)")
        .param("k", List.of(keys))
        .query(String.class)
        .list();
  }

  private static long nullListed() {
    return jdbc.sql("SELECT count(*) FROM storage_deletion WHERE storage_key IS NULL")
        .query(Long.class)
        .single();
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-thumbdel-" + UUID.randomUUID(), null, null);
    return id;
  }

  private static long place() {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(126.98, 37.58), 4326)::geography) RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, CAST('ko' AS lang_code),"
                + " :n)")
        .param("p", id)
        .param("n", "시험 촬영지")
        .update();
    return id;
  }
}
