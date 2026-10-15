package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.TripPostCreate;
import com.mz2az.scenetrip.sceneapi.api.model.UploadCreate;
import com.mz2az.scenetrip.sceneapi.api.model.UploadPurpose;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.post.PostStore;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.StorageDeletionJob;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@code DELETE /posts/{postId}} 부터 사진 파일이 저장소에서 사라질 때까지 — 계약 {@code scene-api-v1.yaml} tag {@code
 * posts}, 계획 {@code post-photo-deletion.md}(MZ2AZ-406).
 *
 * <p>명세에서 나온 기대: 내 글이면 204, 남의 글·없는 글은 404 {@code POST_NOT_FOUND}. <b>요청은 저장소를 건드리지 않는다</b> — 파일은
 * 지우기 작업({@link StorageDeletionJob#run})이 지운다. 남의 글에 대한 404 뒤에는 파일도 목록도 그대로다.
 *
 * <p>컨트롤러를 스프링 없이 손으로 조립하고 진짜 Store·진짜 PostgreSQL 을 쓴다. 저장소(S3)만 가짜다. 전부 되돌리는 트랜잭션 하나 안에서 돌고 —
 * Store·작업의 트랜잭션은 그 안에 합류한다 — 다른 데서 쌓인 지울 목록이 셈을 흐리지 않게 표를 먼저 비운다(되돌려진다).
 */
@DisplayName("후기 지우기 흐름 — DELETE /posts/{id} → 지울 목록 → 지우기 작업 (실제 DB)")
class PostDeleteFlowIntegrationTest {

  private static final byte[] SECRET = filled(32, (byte) 7);

  private static JdbcClient jdbc;
  private static UserStore users;
  private static AccountLinkStore links;

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private final CurrentAccount accounts = new CurrentAccount(request, tokens, users);
  private final FakeStorage storage = new FakeStorage();
  private final UploadStore uploadStore = new UploadStore(jdbc);
  private final PostStore postStore = new PostStore(jdbc, IntegrationDatabase.transactions());
  private final PostsController posts =
      new PostsController(postStore, courses(), storage, uploadStore, users, accounts);
  private final UploadsController uploadsApi =
      new UploadsController(storage, uploadStore, accounts);
  private final StorageDeletionJob job =
      new StorageDeletionJob(jdbc, IntegrationDatabase.transactions(), storage);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  @Test
  @DisplayName("내 글 204 — 요청은 저장소를 부르지 않고 posts/ 키를 목록에 올린다. 작업이 돌면 파일이 사라지고 목록이 빈다")
  void ownDeleteThenJobRemovesFiles() {
    inTx(
        () -> {
          UUID me = member();
          as(me);
          long postId = createPostWithPhotos(2);
          List<String> keys = postKeys(postId);
          assertThat(keys).hasSize(2).allMatch(k -> k.startsWith("posts/"));
          assertThat(storage.files).containsAll(keys);

          var response = posts.deletePost(postId);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(storage.deletes).as("요청은 저장소를 건드리지 않는다").isEmpty();
          assertThat(storage.files).containsAll(keys);
          assertThat(listed()).containsExactlyInAnyOrderElementsOf(keys);

          assertThat(job.run()).isEqualTo(2);
          assertThat(storage.deletes).containsExactlyInAnyOrderElementsOf(keys);
          assertThat(storage.files).doesNotContainAnyElementsOf(keys);
          assertThat(listed()).isEmpty();

          // 지운 글은 다시 지우면 404, 작업은 할 일이 없다.
          expectNotFound(() -> posts.deletePost(postId));
          assertThat(job.run()).isZero();
        });
  }

  @Test
  @DisplayName("남의 글 404 POST_NOT_FOUND — 파일·글·목록 그대로, 저장소 호출 없음")
  void othersPostIs404AndTouchesNothing() {
    inTx(
        () -> {
          UUID owner = member();
          as(owner);
          long postId = createPostWithPhotos(1);
          List<String> keys = postKeys(postId);

          as(member());
          expectNotFound(() -> posts.deletePost(postId));

          assertThat(storage.deletes).isEmpty();
          assertThat(storage.files).containsAll(keys);
          assertThat(listed()).isEmpty();
          assertThat(postKeys(postId)).containsExactlyElementsOf(keys);
          assertThat(job.run()).isZero();
          assertThat(storage.files).containsAll(keys);
        });
  }

  @Test
  @DisplayName("없는 글 404 POST_NOT_FOUND — 목록 그대로")
  void missingPostIs404() {
    inTx(
        () -> {
          as(member());
          expectNotFound(() -> posts.deletePost(Long.MAX_VALUE));
          assertThat(listed()).isEmpty();
          assertThat(storage.deletes).isEmpty();
        });
  }

  @Test
  @DisplayName("저장소가 고장이어도 삭제 요청은 204 — 키는 목록에 남고, 살아난 뒤 작업이 지운다")
  void deleteSucceedsWhileStorageBroken() {
    inTx(
        () -> {
          UUID me = member();
          as(me);
          long postId = createPostWithPhotos(1);
          List<String> keys = postKeys(postId);
          storage.broken = true;

          assertThat(posts.deletePost(postId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(job.run()).isZero();
          assertThat(listed()).containsExactlyElementsOf(keys);
          assertThat(storage.files).containsAll(keys);

          storage.broken = false;
          assertThat(job.run()).isEqualTo(1);
          assertThat(storage.files).doesNotContainAnyElementsOf(keys);
          assertThat(listed()).isEmpty();
        });
  }

  // ───────────── 도우미 ─────────────

  private void inTx(Runnable work) {
    IntegrationDatabase.rolledBack(
        () -> {
          jdbc.sql("DELETE FROM storage_deletion").update();
          work.run();
          return null;
        });
  }

  private long createPostWithPhotos(int n) {
    List<String> tmp = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      var ticket =
          uploadsApi
              .createUpload(new UploadCreate(UploadPurpose.POST, "image/jpeg", 123_456L))
              .getBody();
      storage.files.add(ticket.getKey());
      tmp.add(ticket.getKey());
    }
    var created = posts.createPost(new TripPostCreate("지울 후기", "본문", tmp), null);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return created.getBody().getId();
  }

  private static List<String> postKeys(long postId) {
    return jdbc.sql(
            "SELECT storage_key FROM community_post_photo WHERE post_id = :p ORDER BY sort_order")
        .param("p", postId)
        .query(String.class)
        .list();
  }

  private static List<String> listed() {
    return jdbc.sql("SELECT storage_key FROM storage_deletion").query(String.class).list();
  }

  private static void expectNotFound(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(e.getCode()).isEqualTo("POST_NOT_FOUND");
            });
  }

  private static CourseStore courses() {
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    return new CourseStore(
        jdbc, DeployedTravel.estimator(), dwell, IntegrationDatabase.transactions());
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-postflow-" + UUID.randomUUID(), null, null);
    return id;
  }

  private void as(UUID user) {
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + tokens.issue(user).value());
  }

  private static byte[] filled(int n, byte b) {
    byte[] out = new byte[n];
    Arrays.fill(out, b);
    return out;
  }

  /** 가짜 S3 — 버킷의 파일 집합과 지우기 호출 기록. {@link #broken} 이면 지우기가 실패한다. */
  private static final class FakeStorage implements PhotoStorage {
    final Set<String> files = new HashSet<>();
    final List<String> deletes = new ArrayList<>();
    boolean broken;

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
      if (!files.remove(fromKey)) {
        throw new MissingPhotoException(fromKey);
      }
      files.add(toKey);
    }

    @Override
    public void listPages(
        String prefix, java.util.function.Consumer<java.util.List<StoredObject>> onPage) {
      // 이 시험은 고아 청소를 보지 않는다 — 파일이 없는 것처럼.
    }

    @Override
    public void delete(String storageKey) {
      deletes.add(storageKey);
      if (broken) {
        throw new IllegalStateException("저장소 고장(시험)");
      }
      files.remove(storageKey);
    }
  }
}
