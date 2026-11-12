package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link PhotoThumbnailJob} 을 실제 PostgreSQL 위에서 돌린다 — 계획 {@code photo-thumbnails.md}
 * §2-1·§3·§5(MZ2AZ-561), ADR 0030.
 *
 * <p>명세에서 나온 기대: {@code review_image}·{@code community_post_photo} 에서 {@code thumbnail_key IS NULL
 * AND thumbnail_attempts < 3} 인 줄만 고른다. 한 차례 최대 {@link PhotoThumbnailJob#BATCH}(20) 장. 원본을 읽어 줄이고
 * {@code posts/thumb-720/<이름>}·{@code reviews/thumb-720/<이름>} 에 {@code image/jpeg} 로 올린 뒤 같은 줄에
 * {@code thumbnail_key} 를 적는다. 실패(못 읽는 형식·저장소 오류)는 {@code thumbnail_attempts + 1} 이고 키는 비어 있다 — 3 번
 * 실패하면 더 고르지 않는다. 그 사이 글이 지워졌으면 올린 파일을 지울 목록({@code storage_deletion})에 넣는다. 한 대만 돈다 — 트랜잭션
 * advisory lock {@code hashtext('photo_thumbnail')}. 돌려주는 값은 만든 작은 사진 수.
 *
 * <p>저장소만 가짜다. 시험은 전부 되돌리는 트랜잭션 하나 안에서 돌고 작업의 트랜잭션은 그 안에 합류한다. 로컬 DB 에 이미 있는 사진 줄이 셈을 흐리지 않게, 트랜잭션
 * 안에서 남의 줄은 시도 한도(3)로 올려 둔다(되돌려지므로 실제로는 바뀌지 않는다).
 */
@DisplayName("PhotoThumbnailJob — 작은 사진이 없는 사진 줄을 골라 만든다 (V46, 실제 DB)")
class PhotoThumbnailJobIntegrationTest {

  private static JdbcClient jdbc;
  private static PostStore posts;
  private static ReviewStore reviews;
  private static UserStore users;
  private static AccountLinkStore links;

  private static final byte[] PHOTO = jpeg(1000, 750);

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final FakeStorage storage = new FakeStorage();
  private final PhotoThumbnailJob job =
      new PhotoThumbnailJob(
          IntegrationDatabase.jdbcClient(), IntegrationDatabase.transactions(), storage);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    posts = new PostStore(jdbc, IntegrationDatabase.transactions());
    reviews = new ReviewStore(jdbc, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  // ───────────── 만들기 ─────────────

  @Test
  @DisplayName("후기·리뷰 사진의 작은 사진을 만든다 — thumb-720/ 키에 image/jpeg 로 올리고 줄에 키를 적는다, 만든 수를 돌려준다")
  void makesThumbnailsForBothTables() {
    rolledBack(
        () -> {
          String p1 = key("posts/", "p1");
          String p2 = key("posts/", "p2");
          String r1 = key("reviews/", "r1");
          long postId = posts.create(member(), "제목", "본문", List.of(p1, p2), List.of(p1, p2), null);
          long reviewId =
              reviews.put(Target.PLACE, place(), member(), 5, null, List.of(r1), Set.of(r1)).id();
          storage.files.put(p1, PHOTO);
          storage.files.put(p2, PHOTO);
          storage.files.put(r1, PHOTO);

          assertThat(job.run()).isEqualTo(3);

          String t1 = thumbOf("posts/", "p1");
          String t2 = thumbOf("posts/", "p2");
          String tr = thumbOf("reviews/", "r1");
          assertThat(postThumb(postId, p1)).isEqualTo(t1);
          assertThat(postThumb(postId, p2)).isEqualTo(t2);
          assertThat(reviewThumb(reviewId, r1)).isEqualTo(tr);
          assertThat(storage.puts.keySet()).containsExactlyInAnyOrder(t1, t2, tr);
          assertThat(storage.contentTypes.values()).containsOnly("image/jpeg");
          BufferedImage made = image(storage.puts.get(t1));
          assertThat(Math.max(made.getWidth(), made.getHeight())).isEqualTo(720);
          assertThat(made.getWidth()).isEqualTo(720);
          assertThat(made.getHeight()).isEqualTo(540);
          return null;
        });
  }

  @Test
  @DisplayName("한 번 만든 줄은 다시 고르지 않는다 — 두 번째 차례는 0, 원본을 다시 읽지 않는다")
  void doneRowsAreNotPickedAgain() {
    rolledBack(
        () -> {
          String p1 = key("posts/", "once");
          posts.create(member(), "제목", "본문", List.of(p1), List.of(p1), null);
          storage.files.put(p1, PHOTO);

          assertThat(job.run()).isEqualTo(1);
          storage.reads.clear();

          assertThat(job.run()).isZero();
          assertThat(storage.reads).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("고르는 줄 — thumbnail_key 가 이미 있거나 thumbnail_attempts 가 3 이면 고르지 않고, 2 면 고른다")
  void selectsOnlyMissingAndUnderLimit() {
    rolledBack(
        () -> {
          String done = key("posts/", "done");
          String exhausted = key("posts/", "exhausted");
          String twice = key("posts/", "twice");
          String fresh = key("reviews/", "fresh");
          long postId =
              posts.create(
                  member(),
                  "제목",
                  "본문",
                  List.of(done, exhausted, twice),
                  List.of(done, exhausted, twice),
                  null);
          reviews.put(Target.PLACE, place(), member(), 4, null, List.of(fresh), Set.of(fresh));
          setPost(postId, done, "posts/thumb-720/already.jpg", 0);
          setPost(postId, exhausted, null, 3);
          setPost(postId, twice, null, 2);
          for (String k : List.of(done, exhausted, twice, fresh)) {
            storage.files.put(k, PHOTO);
          }

          assertThat(job.run()).isEqualTo(2);

          assertThat(storage.reads).containsExactlyInAnyOrder(twice, fresh);
          assertThat(postThumb(postId, done)).isEqualTo("posts/thumb-720/already.jpg");
          assertThat(postThumb(postId, exhausted)).isNull();
          assertThat(postThumb(postId, twice)).isEqualTo(thumbOf("posts/", "twice"));
          return null;
        });
  }

  @Test
  @DisplayName("한 차례에 BATCH(20) 장까지 — 25 장이면 20, 다음 차례에 5, 그다음 0")
  void batchOfTwenty() {
    rolledBack(
        () -> {
          int total = PhotoThumbnailJob.BATCH + 5;
          List<String> keys = new ArrayList<>();
          for (int i = 0; i < total; i++) {
            keys.add(key("posts/", "b" + i));
          }
          // 글 하나에 사진은 8 장까지다.
          for (int from = 0; from < total; from += 8) {
            List<String> chunk = keys.subList(from, Math.min(from + 8, total));
            posts.create(member(), "제목", "본문", chunk, chunk, null);
          }
          for (String k : keys) {
            storage.files.put(k, PHOTO);
          }

          assertThat(job.run()).isEqualTo(PhotoThumbnailJob.BATCH);
          assertThat(thumbnailed(keys)).isEqualTo(PhotoThumbnailJob.BATCH);

          assertThat(job.run()).isEqualTo(5);
          assertThat(thumbnailed(keys)).isEqualTo(total);

          assertThat(job.run()).isZero();
          return null;
        });
  }

  // ───────────── 실패 ─────────────

  @Test
  @DisplayName("못 읽는 형식이면 thumbnail_attempts 가 1 오르고 키는 비어 있다 — 아무것도 올리지 않는다")
  void unreadableCountsAttempt() {
    rolledBack(
        () -> {
          String bad = key("posts/", "bad");
          long postId = posts.create(member(), "제목", "본문", List.of(bad), List.of(bad), null);
          storage.files.put(bad, new byte[] {1, 2, 3, 4});

          assertThat(job.run()).isZero();

          assertThat(postThumb(postId, bad)).isNull();
          assertThat(postAttempts(postId, bad)).isEqualTo(1);
          assertThat(storage.puts).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("세 번 실패하면 더 고르지 않는다 — 넷째 차례는 원본을 읽지 않고 attempts 는 3 에 멈춘다")
  void stopsAfterThreeFailures() {
    rolledBack(
        () -> {
          String bad = key("reviews/", "cmyk");
          long reviewId =
              reviews.put(Target.PLACE, place(), member(), 3, null, List.of(bad), Set.of(bad)).id();
          storage.files.put(bad, new byte[] {9, 9, 9});

          for (int i = 1; i <= 3; i++) {
            job.run();
            assertThat(reviewAttempts(reviewId, bad)).as("%d 번째 차례 뒤", i).isEqualTo(i);
          }
          storage.reads.clear();

          assertThat(job.run()).isZero();
          assertThat(storage.reads).doesNotContain(bad);
          assertThat(reviewAttempts(reviewId, bad)).isEqualTo(3);
          assertThat(reviewThumb(reviewId, bad)).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("원본이 저장소에 없으면(읽기 실패) attempts 가 오르고 키는 비어 있다")
  void missingOriginalCountsAttempt() {
    rolledBack(
        () -> {
          String gone = key("posts/", "gone");
          long postId = posts.create(member(), "제목", "본문", List.of(gone), List.of(gone), null);
          // storage.files 에 넣지 않는다

          assertThat(job.run()).isZero();

          assertThat(postAttempts(postId, gone)).isEqualTo(1);
          assertThat(postThumb(postId, gone)).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("올리기가 실패하면 attempts 가 오르고 키는 비어 있다")
  void putFailureCountsAttempt() {
    rolledBack(
        () -> {
          String p = key("posts/", "putfail");
          long postId = posts.create(member(), "제목", "본문", List.of(p), List.of(p), null);
          storage.files.put(p, PHOTO);
          storage.failPut = true;

          assertThat(job.run()).isZero();

          assertThat(postAttempts(postId, p)).isEqualTo(1);
          assertThat(postThumb(postId, p)).isNull();
          return null;
        });
  }

  // ───────────── 그 사이 지워진 글 ─────────────

  @Test
  @DisplayName("올리는 사이 글이 지워졌으면 올린 작은 사진을 지울 목록에 넣는다")
  void deletedMeanwhileQueuesThumbnail() {
    rolledBack(
        () -> {
          String p = key("posts/", "raced");
          long postId = posts.create(member(), "제목", "본문", List.of(p), List.of(p), null);
          storage.files.put(p, PHOTO);
          String thumb = thumbOf("posts/", "raced");
          storage.onPut =
              k ->
                  jdbc.sql("DELETE FROM community_post WHERE id = :id")
                      .param("id", postId)
                      .update();

          job.run();

          assertThat(storage.puts).containsKey(thumb);
          assertThat(
                  jdbc.sql("SELECT count(*) FROM storage_deletion WHERE storage_key = :k")
                      .param("k", thumb)
                      .query(Long.class)
                      .single())
              .isEqualTo(1L);
          return null;
        });
  }

  @Test
  @DisplayName("같은 키가 앞서 지울 목록에 올라 있었으면(리뷰 고치기와 겹친 차례) 다시 만들어 쓰일 때 목록에서 뺀다")
  void reusedThumbnailKeyLeavesDeletionQueue() {
    rolledBack(
        () -> {
          String p = key("posts/", "again");
          posts.create(member(), "제목", "본문", List.of(p), List.of(p), null);
          storage.files.put(p, PHOTO);
          String thumb = thumbOf("posts/", "again");
          jdbc.sql("INSERT INTO storage_deletion (storage_key) VALUES (:k)")
              .param("k", thumb)
              .update();

          assertThat(job.run()).isEqualTo(1);

          assertThat(
                  jdbc.sql("SELECT count(*) FROM storage_deletion WHERE storage_key = :k")
                      .param("k", thumb)
                      .query(Long.class)
                      .single())
              .isZero();
          return null;
        });
  }

  // ───────────── 잠금 ─────────────

  @Test
  @DisplayName("다른 쪽(다른 파드)이 advisory lock(hashtext('photo_thumbnail'))을 쥐고 있으면 건너뛴다 — 0, 읽기 없음")
  void skipsWhenLockHeldElsewhere() throws SQLException {
    try (Connection other = rawConnection()) {
      other.setAutoCommit(false);
      try (Statement st = other.createStatement()) {
        st.execute("SELECT pg_advisory_xact_lock(hashtext('photo_thumbnail'))");
      }
      rolledBack(
          () -> {
            String p = key("posts/", "locked");
            long postId = posts.create(member(), "제목", "본문", List.of(p), List.of(p), null);
            storage.files.put(p, PHOTO);

            assertThat(job.run()).isZero();
            assertThat(storage.reads).isEmpty();
            assertThat(postThumb(postId, p)).isNull();
            assertThat(postAttempts(postId, p)).isZero();
            return null;
          });
      other.rollback();
    }
  }

  // ───────────── 도우미 ─────────────

  /** 되돌리는 트랜잭션 — 로컬 DB 의 남의 사진 줄은 그 안에서 시도 한도로 올려 고르지 않게 한다. */
  private static <T> T rolledBack(Supplier<T> work) {
    return IntegrationDatabase.rolledBack(
        () -> {
          jdbc.sql(
                  "UPDATE review_image SET thumbnail_attempts = 3"
                      + " WHERE thumbnail_key IS NULL AND thumbnail_attempts < 3")
              .update();
          jdbc.sql(
                  "UPDATE community_post_photo SET thumbnail_attempts = 3"
                      + " WHERE thumbnail_key IS NULL AND thumbnail_attempts < 3")
              .update();
          return work.get();
        });
  }

  private String key(String prefix, String name) {
    return prefix + "it-thumb-" + tag + "-" + name + ".jpg";
  }

  private String thumbOf(String prefix, String name) {
    return prefix + "thumb-720/it-thumb-" + tag + "-" + name + ".jpg";
  }

  private static void setPost(long postId, String key, String thumbnailKey, int attempts) {
    jdbc.sql(
            "UPDATE community_post_photo SET thumbnail_key = :t, thumbnail_attempts = :a"
                + " WHERE post_id = :p AND storage_key = :k")
        .param("t", thumbnailKey)
        .param("a", attempts)
        .param("p", postId)
        .param("k", key)
        .update();
  }

  private static String postThumb(long postId, String key) {
    return jdbc.sql(
            "SELECT thumbnail_key FROM community_post_photo WHERE post_id = :p AND storage_key ="
                + " :k")
        .param("p", postId)
        .param("k", key)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static int postAttempts(long postId, String key) {
    return jdbc.sql(
            "SELECT thumbnail_attempts FROM community_post_photo"
                + " WHERE post_id = :p AND storage_key = :k")
        .param("p", postId)
        .param("k", key)
        .query(Integer.class)
        .single();
  }

  private static String reviewThumb(long reviewId, String key) {
    return jdbc.sql(
            "SELECT thumbnail_key FROM review_image WHERE review_id = :r AND storage_key = :k")
        .param("r", reviewId)
        .param("k", key)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static int reviewAttempts(long reviewId, String key) {
    return jdbc.sql(
            "SELECT thumbnail_attempts FROM review_image WHERE review_id = :r AND storage_key = :k")
        .param("r", reviewId)
        .param("k", key)
        .query(Integer.class)
        .single();
  }

  private static long thumbnailed(List<String> keys) {
    return jdbc.sql(
            "SELECT count(*) FROM community_post_photo"
                + " WHERE storage_key IN (:k) AND thumbnail_key IS NOT NULL")
        .param("k", keys)
        .query(Long.class)
        .single();
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-thumb-" + UUID.randomUUID(), null, null);
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

  private static byte[] jpeg(int w, int h) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setPaint(new java.awt.GradientPaint(0, 0, Color.RED, w, h, Color.CYAN));
    g.fillRect(0, 0, w, h);
    g.dispose();
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(img, "jpg", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BufferedImage image(byte[] bytes) {
    try {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
      assertThat(img).as("올린 것은 그림이어야 한다").isNotNull();
      return img;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Connection rawConnection() throws SQLException {
    return DriverManager.getConnection(
        System.getenv("SCENETRIP_TEST_JDBC_URL"),
        System.getenv("SCENETRIP_TEST_DB_USER"),
        System.getenv("SCENETRIP_TEST_DB_PASSWORD"));
  }

  /**
   * 메모리 가짜 저장소 — {@link #files} 에 든 키만 읽힌다(없으면 {@link PhotoStorage.MissingPhotoException}). 읽은 키·올린
   * 키와 바이트·형식을 적는다.
   */
  private static final class FakeStorage implements PhotoStorage {
    final Map<String, byte[]> files = new HashMap<>();
    final List<String> reads = new ArrayList<>();
    final Map<String, byte[]> puts = new HashMap<>();
    final Map<String, String> contentTypes = new HashMap<>();
    final Set<String> deleted = new HashSet<>();
    boolean failPut;
    Consumer<String> onPut = k -> {};

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
          Instant.now().plus(expiresIn));
    }

    @Override
    public void move(String fromKey, String toKey) {
      throw new AssertionError("작은 사진 작업이 move 를 부르면 안 된다");
    }

    @Override
    public void delete(String storageKey) {
      deleted.add(storageKey);
    }

    @Override
    public void listPages(String prefix, Consumer<List<StoredObject>> onPage) {
      throw new AssertionError("작은 사진 작업은 목록을 훑지 않는다 — DB 에서 고른다");
    }

    @Override
    public byte[] read(String storageKey) {
      reads.add(storageKey);
      byte[] bytes = files.get(storageKey);
      if (bytes == null) {
        throw new MissingPhotoException(storageKey);
      }
      return bytes;
    }

    @Override
    public void put(String storageKey, byte[] bytes, String contentType) {
      if (failPut) {
        throw new IllegalStateException("저장소 고장(시험)");
      }
      puts.put(storageKey, bytes);
      contentTypes.put(storageKey, contentType);
      onPut.accept(storageKey);
    }
  }
}
