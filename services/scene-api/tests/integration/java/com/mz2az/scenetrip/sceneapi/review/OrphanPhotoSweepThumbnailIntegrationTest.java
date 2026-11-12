package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 고아 청소가 작은 사진을 「가리키는 파일」로 보는지 — 계획 {@code photo-thumbnails.md} §2-3(MZ2AZ-561).
 *
 * <p>명세에서 나온 기대: {@link OrphanPhotoSweep} 의 「가리키는 키」 질의에 {@code review_image.thumbnail_key}·{@code
 * community_post_photo.thumbnail_key} 를 더한다 — 그래야 7 일 뒤 작은 사진이 고아로 지워지지 않는다. 아무 줄도 가리키지 않는 {@code
 * thumb-720/} 파일은 여전히 고아다.
 *
 * <p>저장소만 가짜이고 시계는 고정이다. 전부 되돌리는 트랜잭션 하나 안에서 돈다.
 */
@DisplayName("OrphanPhotoSweep — 작은 사진(thumb-720/)도 가리키는 파일이다 (V46, 실제 DB)")
class OrphanPhotoSweepThumbnailIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-15T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static JdbcClient jdbc;
  private static ReviewStore reviews;
  private static PostStore posts;
  private static UserStore users;
  private static AccountLinkStore links;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final ListingStorage storage = new ListingStorage();
  private final OrphanPhotoSweep sweep =
      new OrphanPhotoSweep(
          IntegrationDatabase.jdbcClient(), IntegrationDatabase.transactions(), storage, CLOCK);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    reviews = new ReviewStore(jdbc, IntegrationDatabase.transactions());
    posts = new PostStore(jdbc, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  @Test
  @DisplayName("후기 사진 줄이 가리키는 posts/thumb-720/ 파일은 오래돼도 올리지 않는다")
  void postThumbnailIsKept() {
    IntegrationDatabase.rolledBack(
        () -> {
          String original = key("posts/", "p");
          String thumb = key("posts/thumb-720/", "p");
          long postId =
              posts.create(member(), "제목", "본문", List.of(original), List.of(original), null);
          jdbc.sql("UPDATE community_post_photo SET thumbnail_key = :t WHERE post_id = :p")
              .param("t", thumb)
              .param("p", postId)
              .update();
          storage.put(original, NOW.minus(Duration.ofDays(30)));
          storage.put(thumb, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isZero();
          assertThat(listed(original, thumb)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("리뷰 사진 줄이 가리키는 reviews/thumb-720/ 파일은 오래돼도 올리지 않는다")
  void reviewThumbnailIsKept() {
    IntegrationDatabase.rolledBack(
        () -> {
          String original = key("reviews/", "r");
          String thumb = key("reviews/thumb-720/", "r");
          long reviewId =
              reviews
                  .put(
                      Target.PLACE, place(), member(), 5, null, List.of(original), Set.of(original))
                  .id();
          jdbc.sql("UPDATE review_image SET thumbnail_key = :t WHERE review_id = :r")
              .param("t", thumb)
              .param("r", reviewId)
              .update();
          storage.put(original, NOW.minus(Duration.ofDays(30)));
          storage.put(thumb, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isZero();
          assertThat(listed(original, thumb)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("아무 줄도 가리키지 않는 오래된 thumb-720/ 파일은 고아다 — 올린다")
  void unreferencedThumbnailIsOrphan() {
    IntegrationDatabase.rolledBack(
        () -> {
          String kept = key("posts/", "kept");
          String keptThumb = key("posts/thumb-720/", "kept");
          String orphanThumb = key("posts/thumb-720/", "orphan");
          long postId = posts.create(member(), "제목", "본문", List.of(kept), List.of(kept), null);
          jdbc.sql("UPDATE community_post_photo SET thumbnail_key = :t WHERE post_id = :p")
              .param("t", keptThumb)
              .param("p", postId)
              .update();
          storage.put(kept, NOW.minus(Duration.ofDays(30)));
          storage.put(keptThumb, NOW.minus(Duration.ofDays(30)));
          storage.put(orphanThumb, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isEqualTo(1);
          assertThat(listed(kept, keptThumb, orphanThumb)).containsExactly(orphanThumb);
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  private String key(String prefix, String name) {
    return prefix + "it-sweepthumb-" + tag + "-" + name + ".jpg";
  }

  private static List<String> listed(String... keys) {
    return jdbc.sql("SELECT storage_key FROM storage_deletion WHERE storage_key IN (:k)")
        .param("k", List.of(keys))
        .query(String.class)
        .list();
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-sweepthumb-" + UUID.randomUUID(), null, null);
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

  /** 넣은 파일만 접두사로 넘기는 가짜 저장소. 청소는 지우거나 옮기지 않는다. */
  private static final class ListingStorage implements PhotoStorage {
    final List<StoredObject> objects = new ArrayList<>();

    void put(String key, Instant lastModified) {
      objects.add(new StoredObject(key, lastModified));
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public URI viewUrl(String storageKey) {
      return URI.create("https://signed.example/" + storageKey);
    }

    @Override
    public PresignedUpload presignUpload(
        String storageKey, String contentType, long bytes, Duration expiresIn) {
      return new PresignedUpload(
          URI.create("https://bucket.example/" + storageKey),
          Map.of("Content-Type", contentType),
          NOW.plus(expiresIn));
    }

    @Override
    public void move(String fromKey, String toKey) {
      throw new AssertionError("고아 청소가 move 를 부르면 안 된다");
    }

    @Override
    public void delete(String storageKey) {
      throw new AssertionError("고아 청소는 지우지 않는다 — 목록에만 올린다: " + storageKey);
    }

    @Override
    public byte[] read(String storageKey) {
      throw new AssertionError("고아 청소는 파일을 읽지 않는다: " + storageKey);
    }

    @Override
    public void put(String storageKey, byte[] bytes, String contentType) {
      throw new AssertionError("고아 청소는 올리지 않는다: " + storageKey);
    }

    @Override
    public void listPages(String prefix, Consumer<List<StoredObject>> onPage) {
      List<StoredObject> matching =
          objects.stream().filter(o -> o.key().startsWith(prefix)).toList();
      if (!matching.isEmpty()) {
        onPage.accept(matching);
      }
    }
  }
}
