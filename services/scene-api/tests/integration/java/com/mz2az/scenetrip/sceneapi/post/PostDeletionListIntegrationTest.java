package com.mz2az.scenetrip.sceneapi.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link PostStore#delete} 가 사진 키를 지울 목록({@code storage_deletion}, V34)에 올리는지 실제 DB 로 본다 — 계획
 * {@code post-photo-deletion.md} §2·§4(MZ2AZ-406).
 *
 * <p>명세에서 나온 기대: 내 글을 지우면 true, 글·사진 행은 사라지고 그 글의 사진 키가 전부 목록에 오른다(attempts 0). 남의 글·없는 글은 false 이고
 * 목록에 아무것도 오르지 않는다. 사진 없는 글은 목록을 건드리지 않는다. 글 삭제와 목록 기록은 함께 되거나 함께 안 된다.
 *
 * <p>전부 되돌리는 트랜잭션 하나 안에서 돈다.
 */
@DisplayName("PostStore.delete — 사진 키를 지울 목록에 올린다 (V34, 실제 DB)")
class PostDeletionListIntegrationTest {

  private static JdbcClient jdbc;
  private static PostStore store;
  private static UserStore users;
  private static AccountLinkStore links;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new PostStore(jdbc, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  @Test
  @DisplayName("내 글을 지우면 그 글의 사진 키가 전부 목록에 오른다 — attempts 0, last_error 없음, 사진 행은 사라진다")
  void ownDeleteListsAllPhotoKeys() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          List<String> keys = List.of(key("a"), key("b"), key("c"));
          long postId = store.create(me, "제목", "본문", keys, keys, null);
          long others = listedOutside(keys);

          assertThat(store.delete(postId, me)).isTrue();

          assertThat(listed(keys)).containsExactlyInAnyOrderElementsOf(keys);
          assertThat(
                  jdbc.sql(
                          "SELECT count(*) FROM storage_deletion WHERE storage_key IN (:k) AND"
                              + " (attempts <> 0 OR last_error IS NOT NULL)")
                      .param("k", keys)
                      .query(Long.class)
                      .single())
              .isZero();
          assertThat(listedOutside(keys)).as("다른 키는 오르지 않는다").isEqualTo(others);
          assertThat(count("SELECT count(*) FROM community_post_photo WHERE post_id = " + postId))
              .isZero();
          assertThat(count("SELECT count(*) FROM community_post WHERE id = " + postId)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("지운 글의 키만 오른다 — 같은 사람의 다른 글 사진은 오르지 않는다")
  void onlyThatPostsKeys() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          List<String> gone = List.of(key("gone"));
          List<String> kept = List.of(key("kept1"), key("kept2"));
          long goneId = store.create(me, "지울 글", "본문", gone, gone, null);
          long keptId = store.create(me, "남길 글", "본문", kept, kept, null);

          assertThat(store.delete(goneId, me)).isTrue();

          assertThat(listed(gone)).containsExactlyElementsOf(gone);
          assertThat(listed(kept)).isEmpty();
          assertThat(store.find(keptId, me).orElseThrow().photoKeys())
              .containsExactlyElementsOf(kept);
          return null;
        });
  }

  @Test
  @DisplayName("남의 글은 false — 글·사진 행 그대로, 목록에 아무것도 오르지 않는다")
  void othersPostListsNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID owner = member();
          UUID stranger = member();
          List<String> keys = List.of(key("x"), key("y"));
          long postId = store.create(owner, "제목", "본문", keys, keys, null);
          long before = count("SELECT count(*) FROM storage_deletion");

          assertThat(store.delete(postId, stranger)).isFalse();

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          assertThat(listed(keys)).isEmpty();
          assertThat(store.find(postId, owner).orElseThrow().photoKeys())
              .containsExactlyElementsOf(keys);
          return null;
        });
  }

  @Test
  @DisplayName("없는 글은 false — 목록은 그대로")
  void missingPostListsNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          long before = count("SELECT count(*) FROM storage_deletion");

          assertThat(store.delete(Long.MAX_VALUE, me)).isFalse();
          assertThat(store.delete(-1L, me)).isFalse();

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("사진 없는 글을 지우면 true 이고 목록은 그대로다")
  void postWithoutPhotos() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          long postId = store.create(me, "제목", "본문", List.of(), List.of(), null);
          long before = count("SELECT count(*) FROM storage_deletion");

          assertThat(store.delete(postId, me)).isTrue();

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("같은 글을 두 번 지우면 두 번째는 false — 목록의 키는 한 번만, 오류 없이")
  void secondDeleteIsFalse() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          List<String> keys = List.of(key("once"));
          long postId = store.create(me, "제목", "본문", keys, keys, null);

          assertThat(store.delete(postId, me)).isTrue();
          assertThat(store.delete(postId, me)).isFalse();

          assertThat(listed(keys)).containsExactlyElementsOf(keys);
          return null;
        });
  }

  @Test
  @DisplayName(
      "키가 이미 목록에 있어도 글 삭제는 성공한다 — 있던 줄은 created_at·attempts·last_error 그대로, 두 키 모두 한 번씩 (ON"
          + " CONFLICT DO NOTHING)")
  void alreadyListedKeyIsLeftUntouched() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID me = member();
          String already = key("already");
          String fresh = key("fresh");
          List<String> keys = List.of(already, fresh);
          long postId = store.create(me, "제목", "본문", keys, keys, null);
          jdbc.sql(
                  "INSERT INTO storage_deletion (storage_key, created_at, attempts, last_error)"
                      + " VALUES (:k, TIMESTAMPTZ '2001-02-03 04:05:06+00', 2, :e)")
              .param("k", already)
              .param("e", "앞선 실패(시험)")
              .update();
          String before = row(already);

          // 저장점 안에서 부른다 — 명세와 달리 던지더라도 바깥 시험 트랜잭션에서 상태를 이어서 확인할 수 있게.
          Object result = nested(() -> store.delete(postId, me));

          assertThat(result).as("명세: 이미 목록에 있는 키 때문에 글 삭제가 실패하지 않는다").isEqualTo(Boolean.TRUE);
          assertThat(count("SELECT count(*) FROM community_post WHERE id = " + postId)).isZero();
          assertThat(count("SELECT count(*) FROM community_post_photo WHERE post_id = " + postId))
              .isZero();
          assertThat(listed(keys)).containsExactlyInAnyOrderElementsOf(keys);
          assertThat(row(already)).isEqualTo(before).isEqualTo("2001-02-03T04:05:06Z|2|앞선 실패(시험)");
          assertThat(row(fresh)).endsWith("|0|");
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  /** 저장점(NESTED) 안에서 돌린다. 던지면 저장점까지만 되돌리고 그 예외를 돌려준다 — 단언 메시지에 실린다. */
  private static Object nested(Supplier<Boolean> work) {
    TransactionTemplate t = IntegrationDatabase.transactions();
    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    try {
      return t.execute(status -> work.get());
    } catch (RuntimeException e) {
      return e;
    }
  }

  /** 목록 줄 하나를 created_at(UTC)|attempts|last_error 로. */
  private static String row(String key) {
    return jdbc.sql(
            "SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')"
                + " || '|' || attempts || '|' || coalesce(last_error, '')"
                + " FROM storage_deletion WHERE storage_key = :k")
        .param("k", key)
        .query(String.class)
        .single();
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-postdel-" + UUID.randomUUID(), null, null);
    return id;
  }

  private String key(String name) {
    return "posts/it-postdel-" + tag + "-" + name + ".jpg";
  }

  private static List<String> listed(List<String> keys) {
    return jdbc.sql("SELECT storage_key FROM storage_deletion WHERE storage_key IN (:k)")
        .param("k", keys)
        .query(String.class)
        .list();
  }

  private static long listedOutside(List<String> keys) {
    return jdbc.sql("SELECT count(*) FROM storage_deletion WHERE storage_key NOT IN (:k)")
        .param("k", keys)
        .query(Long.class)
        .single();
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
