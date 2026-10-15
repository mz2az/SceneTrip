package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link OrphanPhotoSweep} 를 실제 PostgreSQL 위에서 돌린다 — 계획 {@code post-photo-deletion.md}
 * §6-3(MZ2AZ-448).
 *
 * <p>명세에서 나온 기대: 저장소의 {@code reviews/}·{@code posts/} 만 본다. 마지막 수정이 {@link
 * OrphanPhotoSweep#MIN_AGE}(7 일)보다 오래되었고 {@code review_image}·{@code community_post_photo} 어디에서도
 * 가리키지 않는 키만 {@code storage_deletion} 에 올린다. {@code uploads/tmp/}·다른 접두사는 올리지 않는다. 이미 목록에 있는 키는 그대로
 * 두고 세지 않는다. 돌려주는 값은 새로 올린 개수, 저장소가 없거나 다른 파드가 잠금을 쥐고 있으면 0. 한 대만 돈다 — 트랜잭션 advisory lock, {@link
 * StorageDeletionJob} 과 다른 열쇠.
 *
 * <p>잠금 열쇠는 클래스 문서에 「다른 열쇠」로만 적혀 있어 값을 모른다 — 작업이 돈 뒤 같은 트랜잭션 안에서 {@code pg_locks} 로 쥔 advisory lock
 * 을 관찰해 열쇠를 알아내고, 그 열쇠를 다른 접속이 쥐게 해 건너뛰는지 본다.
 *
 * <p>저장소만 가짜이고 시계는 고정이다. 전부 되돌리는 트랜잭션 하나 안에서 돈다.
 */
@DisplayName("OrphanPhotoSweep — 가리키지 않는 오래된 사진 파일을 지울 목록에 올린다 (실제 DB)")
class OrphanPhotoSweepIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-06-07T19:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static JdbcClient jdbc;
  private static ReviewStore reviews;
  private static PostStore posts;
  private static UserStore users;
  private static AccountLinkStore links;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final FakeStorage storage = new FakeStorage();
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

  // ───────────── 올리는 것 ─────────────

  @Test
  @DisplayName("reviews/·posts/ 의 8 일 된 고아 파일을 올린다 — 새로 올린 개수를 돌려주고 줄은 attempts 0")
  void listsOldOrphansInBothPrefixes() {
    rolledBack(
        () -> {
          String review = key("reviews/", "orphan");
          String post = key("posts/", "orphan");
          storage.put(review, NOW.minus(Duration.ofDays(8)));
          storage.put(post, NOW.minus(Duration.ofDays(8)));

          assertThat(sweep.run()).isEqualTo(2);

          assertThat(listed(review, post)).containsExactlyInAnyOrder(review, post);
          assertThat(attempts(review)).isZero();
          assertThat(attempts(post)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("7 일 1 초 된 고아는 올리고, 6 일 23 시간 59 분 59 초·방금 바뀐 고아는 올리지 않는다")
  void ageBoundary() {
    rolledBack(
        () -> {
          String old = key("reviews/", "old");
          String young = key("reviews/", "young");
          String fresh = key("posts/", "fresh");
          String future = key("posts/", "future");
          storage.put(old, NOW.minus(OrphanPhotoSweep.MIN_AGE).minusSeconds(1));
          storage.put(young, NOW.minus(OrphanPhotoSweep.MIN_AGE).plusSeconds(1));
          storage.put(fresh, NOW);
          storage.put(future, NOW.plusSeconds(60));

          assertThat(sweep.run()).isEqualTo(1);

          assertThat(listed(old, young, fresh, future)).containsExactly(old);
          return null;
        });
  }

  @Test
  @DisplayName("마지막 수정이 정확히 7 일 전이면 올리지 않는다 — 명세는 「7 일이 넘은 것」")
  void exactlySevenDaysIsNotListed() {
    rolledBack(
        () -> {
          String edge = key("reviews/", "edge");
          storage.put(edge, NOW.minus(OrphanPhotoSweep.MIN_AGE));

          assertThat(sweep.run()).isZero();
          assertThat(listed(edge)).isEmpty();
          return null;
        });
  }

  // ───────────── 가리키는 파일 ─────────────

  @Test
  @DisplayName("리뷰 사진(review_image)이 가리키는 오래된 파일은 올리지 않는다 — 옆의 고아만 오른다")
  void reviewReferencedIsKept() {
    rolledBack(
        () -> {
          String used = key("reviews/", "used");
          String orphan = key("reviews/", "orphan");
          reviews.put(Target.PLACE, place(), member(), 5, null, List.of(used), Set.of(used));
          storage.put(used, NOW.minus(Duration.ofDays(30)));
          storage.put(orphan, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isEqualTo(1);
          assertThat(listed(used, orphan)).containsExactly(orphan);
          return null;
        });
  }

  @Test
  @DisplayName("후기 사진(community_post_photo)이 가리키는 오래된 파일은 올리지 않는다")
  void postReferencedIsKept() {
    rolledBack(
        () -> {
          String used = key("posts/", "used");
          String orphan = key("posts/", "orphan");
          posts.create(member(), "제목", "본문", List.of(used), List.of(used), null);
          storage.put(used, NOW.minus(Duration.ofDays(30)));
          storage.put(orphan, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isEqualTo(1);
          assertThat(listed(used, orphan)).containsExactly(orphan);
          return null;
        });
  }

  @Test
  @DisplayName("가리키는 표는 접두사와 상관없다 — posts/ 아래 키를 리뷰가, reviews/ 아래 키를 후기가 가리켜도 올리지 않는다")
  void referenceCheckedAcrossTables() {
    rolledBack(
        () -> {
          String postKeyUsedByReview = key("posts/", "by-review");
          String reviewKeyUsedByPost = key("reviews/", "by-post");
          // 저장 쪽 검사에 걸리지 않게 제 접두사로 붙인 뒤 키만 바꾼다 — 보는 것은 표다.
          String r = key("reviews/", "tmp-r");
          String p = key("posts/", "tmp-p");
          long reviewId =
              reviews.put(Target.PLACE, place(), member(), 5, null, List.of(r), Set.of(r)).id();
          long postId = posts.create(member(), "제목", "본문", List.of(p), List.of(p), null);
          jdbc.sql("UPDATE review_image SET storage_key = :k WHERE review_id = :id")
              .param("k", postKeyUsedByReview)
              .param("id", reviewId)
              .update();
          jdbc.sql("UPDATE community_post_photo SET storage_key = :k WHERE post_id = :id")
              .param("k", reviewKeyUsedByPost)
              .param("id", postId)
              .update();
          storage.put(postKeyUsedByReview, NOW.minus(Duration.ofDays(30)));
          storage.put(reviewKeyUsedByPost, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isZero();
          assertThat(listed(postKeyUsedByReview, reviewKeyUsedByPost)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("운영자가 내린 리뷰(removed_at)의 사진도 가리키는 것이다 — 올리지 않는다")
  void removedReviewPhotoIsKept() {
    rolledBack(
        () -> {
          String used = key("reviews/", "removed");
          long reviewId =
              reviews
                  .put(Target.PLACE, place(), member(), 1, null, List.of(used), Set.of(used))
                  .id();
          jdbc.sql("UPDATE review SET removed_at = now() WHERE id = :id")
              .param("id", reviewId)
              .update();
          storage.put(used, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isZero();
          assertThat(listed(used)).isEmpty();
          return null;
        });
  }

  // ───────────── 보지 않는 자리 ─────────────

  @Test
  @DisplayName("uploads/tmp/·다른 접두사의 오래된 파일은 올리지 않는다 — 저장소에 묻는 접두사는 reviews/·posts/ 뿐")
  void onlyReviewsAndPostsPrefixes() {
    rolledBack(
        () -> {
          String tmp = key("uploads/tmp/", "tmp");
          String content = key("content/", "x");
          String bare = "it-sweep-" + tag + "-root.jpg";
          String lookalike = key("reviewsX/", "lookalike");
          String orphan = key("reviews/", "orphan");
          for (String k : List.of(tmp, content, bare, lookalike, orphan)) {
            storage.put(k, NOW.minus(Duration.ofDays(60)));
          }

          assertThat(sweep.run()).isEqualTo(1);

          assertThat(listed(tmp, content, bare, lookalike, orphan)).containsExactly(orphan);
          assertThat(storage.prefixes).isNotEmpty();
          assertThat(Set.copyOf(storage.prefixes))
              .as("명세: reviews/·posts/ 만 본다")
              .isEqualTo(Set.of("reviews/", "posts/"));
          return null;
        });
  }

  // ───────────── 이미 목록에 있는 키 ─────────────

  @Test
  @DisplayName("이미 목록에 있는 고아는 그대로 두고 세지 않는다 — created_at·attempts·last_error 그대로")
  void alreadyListedIsLeftUntouched() {
    rolledBack(
        () -> {
          String already = key("reviews/", "already");
          String fresh = key("posts/", "fresh");
          jdbc.sql(
                  "INSERT INTO storage_deletion (storage_key, created_at, attempts, last_error)"
                      + " VALUES (:k, TIMESTAMPTZ '2001-02-03 04:05:06+00', 3, :e)")
              .param("k", already)
              .param("e", "앞선 실패(시험)")
              .update();
          storage.put(already, NOW.minus(Duration.ofDays(30)));
          storage.put(fresh, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).as("새로 올린 것만 센다").isEqualTo(1);

          assertThat(row(already)).isEqualTo("2001-02-03T04:05:06Z|3|앞선 실패(시험)");
          assertThat(row(fresh)).endsWith("|0|");
          return null;
        });
  }

  @Test
  @DisplayName("두 번 돌면 두 번째는 0 — 같은 키가 두 줄이 되지 않는다")
  void secondRunAddsNothing() {
    rolledBack(
        () -> {
          String orphan = key("reviews/", "twice");
          storage.put(orphan, NOW.minus(Duration.ofDays(30)));

          assertThat(sweep.run()).isEqualTo(1);
          assertThat(sweep.run()).isZero();
          assertThat(listed(orphan)).containsExactly(orphan);
          return null;
        });
  }

  // ───────────── 쪽 ─────────────

  @Test
  @DisplayName("여러 쪽으로 나뉜 목록 — 모든 쪽의 고아를 올리고, 어느 쪽이든 가리키는 파일·최근 파일은 올리지 않는다")
  void walksAllPages() {
    rolledBack(
        () -> {
          storage.pageSize = 3;
          List<String> orphans = new ArrayList<>();
          List<String> kept = new ArrayList<>();
          UUID me = member();
          List<String> referencedPostKeys = new ArrayList<>();
          for (int i = 0; i < 10; i++) {
            String r = key("reviews/", "page-orphan-" + i);
            String p = key("posts/", "page-orphan-" + i);
            storage.put(r, NOW.minus(Duration.ofDays(10)));
            storage.put(p, NOW.minus(Duration.ofDays(10)));
            orphans.add(r);
            orphans.add(p);
          }
          for (int i = 0; i < 4; i++) {
            String young = key("reviews/", "page-young-" + i);
            storage.put(young, NOW.minus(Duration.ofDays(1)));
            kept.add(young);
            String ref = key("posts/", "page-ref-" + i);
            storage.put(ref, NOW.minus(Duration.ofDays(10)));
            referencedPostKeys.add(ref);
            kept.add(ref);
          }
          posts.create(me, "제목", "본문", referencedPostKeys, referencedPostKeys, null);

          assertThat(sweep.run()).isEqualTo(orphans.size());

          List<String> all = new ArrayList<>(orphans);
          all.addAll(kept);
          assertThat(listed(all.toArray(String[]::new)))
              .containsExactlyInAnyOrderElementsOf(orphans);
          assertThat(storage.pagesServed).as("한 쪽이 아니라 여러 쪽으로 넘겼다").isGreaterThan(2);
          return null;
        });
  }

  // ───────────── 저장소 없음 · 잠금 ─────────────

  @Test
  @DisplayName("저장소가 없는 환경(available=false)이면 0 — 저장소에 묻지 않고 아무것도 올리지 않는다")
  void unavailableStorage() {
    rolledBack(
        () -> {
          String orphan = key("reviews/", "unavailable");
          storage.put(orphan, NOW.minus(Duration.ofDays(30)));
          storage.available = false;

          assertThat(sweep.run()).isZero();
          assertThat(storage.prefixes).isEmpty();
          assertThat(listed(orphan)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("돌 때 트랜잭션 advisory lock 하나를 쥔다 — 지우기 작업(hashtext('storage_deletion'))과 다른 열쇠")
  void holdsItsOwnAdvisoryLock() {
    rolledBack(
        () -> {
          storage.put(key("reviews/", "lock"), NOW.minus(Duration.ofDays(30)));

          sweep.run();

          List<long[]> held = heldAdvisoryLocks();
          assertThat(held).as("작업의 트랜잭션(시험 트랜잭션에 합류)이 advisory lock 을 쥐고 있어야 한다").hasSize(1);
          assertThat(held.get(0)).isNotEqualTo(lockOf(storageDeletionKey()));
          return null;
        });
  }

  @Test
  @DisplayName("다른 쪽(다른 파드)이 같은 advisory lock 을 쥐고 있으면 그 차례를 건너뛴다 — 0, 아무것도 오르지 않는다")
  void skipsWhenLockHeldElsewhere() throws SQLException {
    // 먼저 작업이 쥐는 열쇠를 관찰한다.
    long[] lock =
        rolledBack(
            () -> {
              storage.put(key("reviews/", "probe"), NOW.minus(Duration.ofDays(30)));
              sweep.run();
              List<long[]> held = heldAdvisoryLocks();
              assertThat(held).hasSize(1);
              return held.get(0);
            });
    storage.reset();

    try (Connection other = rawConnection()) {
      other.setAutoCommit(false);
      try (Statement st = other.createStatement()) {
        st.execute(lockSql("pg_advisory_xact_lock", lock));
      }
      rolledBack(
          () -> {
            String orphan = key("reviews/", "skipped");
            storage.put(orphan, NOW.minus(Duration.ofDays(30)));

            assertThat(sweep.run()).isZero();
            assertThat(listed(orphan)).isEmpty();
            return null;
          });
      other.rollback();
    }
    storage.reset();

    // 잠금이 풀리면 다시 돈다 — 건너뛴 까닭이 잠금이었음을 확인한다.
    rolledBack(
        () -> {
          String orphan = key("reviews/", "after");
          storage.put(orphan, NOW.minus(Duration.ofDays(30)));
          assertThat(sweep.run()).isEqualTo(1);
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  private static <T> T rolledBack(Supplier<T> work) {
    return IntegrationDatabase.rolledBack(work);
  }

  /**
   * 이 접속(시험 트랜잭션)이 쥔 advisory lock 들 — {classid, objid, objsubid}. bigint 열쇠 하나면 objsubid 1, int 두
   * 개면 2.
   */
  private static List<long[]> heldAdvisoryLocks() {
    return jdbc.sql(
            "SELECT classid::bigint, objid::bigint, objsubid::bigint FROM pg_locks"
                + " WHERE locktype = 'advisory' AND granted AND pid = pg_backend_pid()")
        .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)})
        .list();
  }

  private static long storageDeletionKey() {
    return jdbc.sql("SELECT hashtext('storage_deletion')::bigint").query(Long.class).single();
  }

  /** bigint 열쇠 하나가 pg_locks 에 보이는 모양. */
  private static long[] lockOf(long key) {
    return new long[] {key >>> 32, key & 0xFFFFFFFFL, 1};
  }

  private static String lockSql(String fn, long[] lock) {
    if (lock[2] == 1) {
      long key = (lock[0] << 32) | lock[1];
      return "SELECT " + fn + "(" + key + "::bigint)";
    }
    return "SELECT " + fn + "(" + (int) lock[0] + ", " + (int) lock[1] + ")";
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-sweep-" + UUID.randomUUID(), null, null);
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

  private String key(String prefix, String name) {
    return prefix + "it-sweep-" + tag + "-" + name + ".jpg";
  }

  private static List<String> listed(String... keys) {
    return jdbc.sql("SELECT storage_key FROM storage_deletion WHERE storage_key IN (:k)")
        .param("k", List.of(keys))
        .query(String.class)
        .list();
  }

  private static int attempts(String key) {
    return jdbc.sql("SELECT attempts FROM storage_deletion WHERE storage_key = :k")
        .param("k", key)
        .query(Integer.class)
        .single();
  }

  private static String row(String key) {
    return jdbc.sql(
            "SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')"
                + " || '|' || attempts || '|' || coalesce(last_error, '')"
                + " FROM storage_deletion WHERE storage_key = :k")
        .param("k", key)
        .query(String.class)
        .single();
  }

  private static Connection rawConnection() throws SQLException {
    return DriverManager.getConnection(
        System.getenv("SCENETRIP_TEST_JDBC_URL"),
        System.getenv("SCENETRIP_TEST_DB_USER"),
        System.getenv("SCENETRIP_TEST_DB_PASSWORD"));
  }

  /**
   * 가짜 저장소 — 넣은 파일을 넣은 순서로 들고 있다가, 묻는 접두사로 시작하는 것만 {@link #pageSize} 개씩 쪽으로 넘긴다. 접두사를 무엇으로 물었는지
   * 적는다. 지우기·옮기기는 청소가 부르면 안 된다.
   */
  private static final class FakeStorage implements PhotoStorage {
    final List<StoredObject> objects = new ArrayList<>();
    final List<String> prefixes = new ArrayList<>();
    int pageSize = 1000;
    int pagesServed;
    boolean available = true;

    void put(String key, Instant lastModified) {
      objects.add(new StoredObject(key, lastModified));
    }

    void reset() {
      objects.clear();
      prefixes.clear();
      pagesServed = 0;
      available = true;
      pageSize = 1000;
    }

    @Override
    public boolean available() {
      return available;
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
    public void listPages(String prefix, Consumer<List<StoredObject>> onPage) {
      prefixes.add(prefix);
      List<StoredObject> matching =
          objects.stream().filter(o -> o.key().startsWith(prefix)).toList();
      for (int from = 0; from < matching.size(); from += pageSize) {
        pagesServed++;
        onPage.accept(
            List.copyOf(matching.subList(from, Math.min(from + pageSize, matching.size()))));
      }
    }
  }
}
