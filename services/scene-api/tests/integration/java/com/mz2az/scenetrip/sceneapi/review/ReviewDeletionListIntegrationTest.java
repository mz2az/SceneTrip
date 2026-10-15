package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Row;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ReviewStore#delete}·{@link ReviewStore#put} 이 사진 키를 지울 목록({@code storage_deletion}, V34)에
 * 올리는지 실제 DB 로 본다 — 계획 {@code post-photo-deletion.md} §6-2(MZ2AZ-448).
 *
 * <p>명세에서 나온 기대: 내 리뷰를 지우면 그 리뷰의 사진 키가 전부 목록에 오른다(같은 트랜잭션, 지우기 전에). 남의 리뷰·없는 리뷰는 아무것도 오르지 않는다. 고치기는
 * 원래 붙어 있던 키 중 새 목록에 없는 것만 올린다 — 그대로 둔 사진·새로 붙인 사진은 오르지 않는다. 고치기가 실패하면 아무것도 오르지 않는다. 이미 목록에 있는 키는
 * 그대로 둔다(ON CONFLICT DO NOTHING).
 *
 * <p>전부 되돌리는 트랜잭션 하나 안에서 돈다 — 시험이 만든 촬영지·편의시설·계정·목록 줄은 남지 않고, 클러스터에서 도는 지우기 작업이 시험 줄을 보지 못한다.
 */
@DisplayName("ReviewStore — 리뷰 지우기·고치기에서 사진 키를 지울 목록에 올린다 (V34, 실제 DB)")
class ReviewDeletionListIntegrationTest {

  private static JdbcClient jdbc;
  private static ReviewStore reviews;
  private static UserStore users;
  private static AccountLinkStore links;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    reviews = new ReviewStore(jdbc, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  // ───────────── 지우기 ─────────────

  @Test
  @DisplayName("촬영지 리뷰를 지우면 사진 키가 전부 목록에 오른다 — attempts 0·last_error 없음, 사진 행은 사라지고 다른 키는 오르지 않는다")
  void deletePlaceReviewListsAllKeys() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          List<String> keys = List.of(key("a"), key("b"), key("c"));
          Row saved = reviews.put(Target.PLACE, place, me, 5, "좋다", keys, Set.copyOf(keys));
          long othersBefore = listedOutside(keys);

          assertThat(reviews.delete(Target.PLACE, place, me)).isTrue();

          assertThat(listed(keys)).containsExactlyInAnyOrderElementsOf(keys);
          for (String k : keys) {
            assertThat(row(k)).endsWith("|0|");
          }
          assertThat(listedOutside(keys)).as("다른 키는 오르지 않는다").isEqualTo(othersBefore);
          assertThat(imageCount(saved.id())).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("편의시설 리뷰를 지워도 같다 — 사진 키가 목록에 오른다")
  void deletePoiReviewListsKeys() {
    IntegrationDatabase.rolledBack(
        () -> {
          long poi = poi();
          UUID me = member();
          List<String> keys = List.of(key("p1"), key("p2"));
          reviews.put(Target.POI, poi, me, 4, null, keys, Set.copyOf(keys));

          assertThat(reviews.delete(Target.POI, poi, me)).isTrue();

          assertThat(listed(keys)).containsExactlyInAnyOrderElementsOf(keys);
          return null;
        });
  }

  @Test
  @DisplayName("남이 지우려 하면 false — 주인의 리뷰·사진 그대로, 목록에 아무것도 오르지 않는다")
  void strangerDeleteListsNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID owner = member();
          UUID stranger = member();
          List<String> keys = List.of(key("x"), key("y"));
          reviews.put(Target.PLACE, place, owner, 5, null, keys, Set.copyOf(keys));
          long before = count("SELECT count(*) FROM storage_deletion");

          assertThat(reviews.delete(Target.PLACE, place, stranger)).isFalse();

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          assertThat(reviews.mine(Target.PLACE, place, owner).orElseThrow().photoKeys())
              .containsExactlyElementsOf(keys);
          return null;
        });
  }

  @Test
  @DisplayName("같은 곳에 두 사람이 리뷰를 썼을 때 내 리뷰를 지우면 내 사진만 오른다 — 남의 사진은 오르지 않는다")
  void deleteListsOnlyMyKeysAtSharedPlace() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          UUID other = member();
          List<String> mine = List.of(key("mine1"), key("mine2"));
          List<String> theirs = List.of(key("theirs1"));
          reviews.put(Target.PLACE, place, me, 5, null, mine, Set.copyOf(mine));
          reviews.put(Target.PLACE, place, other, 3, null, theirs, Set.copyOf(theirs));

          assertThat(reviews.delete(Target.PLACE, place, me)).isTrue();

          assertThat(listed(mine)).containsExactlyInAnyOrderElementsOf(mine);
          assertThat(listed(theirs)).isEmpty();
          assertThat(reviews.mine(Target.PLACE, place, other).orElseThrow().photoKeys())
              .containsExactlyElementsOf(theirs);
          return null;
        });
  }

  @Test
  @DisplayName("지운 곳의 키만 오른다 — 같은 사람의 다른 곳(촬영지·편의시설) 리뷰 사진은 오르지 않는다")
  void deleteListsOnlyThatTargetsKeys() {
    IntegrationDatabase.rolledBack(
        () -> {
          long goneplace = place();
          long keptPlace = place();
          long keptPoi = poi();
          UUID me = member();
          List<String> gone = List.of(key("gone"));
          List<String> keptA = List.of(key("keptA"));
          List<String> keptB = List.of(key("keptB"));
          reviews.put(Target.PLACE, goneplace, me, 5, null, gone, Set.copyOf(gone));
          reviews.put(Target.PLACE, keptPlace, me, 5, null, keptA, Set.copyOf(keptA));
          reviews.put(Target.POI, keptPoi, me, 5, null, keptB, Set.copyOf(keptB));

          assertThat(reviews.delete(Target.PLACE, goneplace, me)).isTrue();

          assertThat(listed(gone)).containsExactlyElementsOf(gone);
          assertThat(listed(keptA)).isEmpty();
          assertThat(listed(keptB)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("사진 없는 리뷰를 지우면 true 이고 목록은 그대로다")
  void deleteWithoutPhotos() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          reviews.put(Target.PLACE, place, me, 3, "사진 없음", List.of(), Set.of());
          long before = count("SELECT count(*) FROM storage_deletion");

          assertThat(reviews.delete(Target.PLACE, place, me)).isTrue();

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("없는 리뷰를 지우면 false — 목록은 그대로")
  void deleteMissing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          long before = count("SELECT count(*) FROM storage_deletion");

          assertThat(reviews.delete(Target.PLACE, place, me)).isFalse();
          assertThat(reviews.delete(Target.POI, -1L, me)).isFalse();

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("두 번 지우면 두 번째는 false — 키는 한 번만, 오류 없이")
  void deleteTwice() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          List<String> keys = List.of(key("once"));
          reviews.put(Target.PLACE, place, me, 5, null, keys, Set.copyOf(keys));

          assertThat(reviews.delete(Target.PLACE, place, me)).isTrue();
          assertThat(reviews.delete(Target.PLACE, place, me)).isFalse();

          assertThat(listed(keys)).containsExactlyElementsOf(keys);
          return null;
        });
  }

  @Test
  @DisplayName(
      "키가 이미 목록에 있어도 지우기는 성공한다 — 있던 줄은 created_at·attempts·last_error 그대로, 새 키는 attempts 0")
  void deleteWithAlreadyListedKey() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          String already = key("already");
          String fresh = key("fresh");
          List<String> keys = List.of(already, fresh);
          reviews.put(Target.PLACE, place, me, 5, null, keys, Set.copyOf(keys));
          preList(already);

          Object result = nested(() -> reviews.delete(Target.PLACE, place, me));

          assertThat(result).as("이미 목록에 있는 키 때문에 리뷰 지우기가 실패하지 않는다").isEqualTo(Boolean.TRUE);
          assertThat(reviews.mine(Target.PLACE, place, me)).isEmpty();
          assertThat(listed(keys)).containsExactlyInAnyOrderElementsOf(keys);
          assertThat(row(already)).isEqualTo(PRE_LISTED);
          assertThat(row(fresh)).endsWith("|0|");
          return null;
        });
  }

  // ───────────── 고치기 ─────────────

  @Test
  @DisplayName("처음 쓰기(put 새 리뷰)는 아무것도 올리지 않는다")
  void createListsNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          List<String> keys = List.of(key("n1"), key("n2"));
          long before = count("SELECT count(*) FROM storage_deletion");

          reviews.put(Target.PLACE, place, me, 5, null, keys, Set.copyOf(keys));

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("고치며 a 를 빼고 b 를 두고 c 를 더하면 a 만 오른다")
  void editListsOnlyDroppedKey() {
    IntegrationDatabase.rolledBack(
        () -> {
          long poi = poi();
          UUID me = member();
          String a = key("a");
          String b = key("b");
          String c = key("c");
          reviews.put(Target.POI, poi, me, 5, null, List.of(a, b), Set.of(a, b));
          long othersBefore = listedOutside(List.of(a, b, c));

          Row edited = reviews.put(Target.POI, poi, me, 4, "고침", List.of(c, b), Set.of(c));

          assertThat(edited.photoKeys()).containsExactly(c, b);
          assertThat(listed(List.of(a, b, c))).containsExactly(a);
          assertThat(row(a)).endsWith("|0|");
          assertThat(listedOutside(List.of(a, b, c))).isEqualTo(othersBefore);
          return null;
        });
  }

  @Test
  @DisplayName("사진을 그대로 두고 별점·글만 고치면 아무것도 오르지 않는다")
  void editSamePhotosListsNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          List<String> keys = List.of(key("s1"), key("s2"));
          reviews.put(Target.PLACE, place, me, 2, "처음", keys, Set.copyOf(keys));
          long before = count("SELECT count(*) FROM storage_deletion");

          reviews.put(Target.PLACE, place, me, 5, "고침", keys, Set.of());

          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("사진 순서만 바꾸면 아무것도 오르지 않는다")
  void editReorderListsNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          String a = key("r1");
          String b = key("r2");
          String c = key("r3");
          reviews.put(Target.PLACE, place, me, 5, null, List.of(a, b, c), Set.of(a, b, c));
          long before = count("SELECT count(*) FROM storage_deletion");

          Row edited = reviews.put(Target.PLACE, place, me, 5, null, List.of(c, a, b), Set.of());

          assertThat(edited.photoKeys()).containsExactly(c, a, b);
          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("사진을 모두 빼면 원래 키가 전부 오른다")
  void editRemovingAllListsAll() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          List<String> keys = List.of(key("all1"), key("all2"));
          reviews.put(Target.PLACE, place, me, 5, null, keys, Set.copyOf(keys));

          Row edited = reviews.put(Target.PLACE, place, me, 5, null, List.of(), Set.of());

          assertThat(edited.photoKeys()).isEmpty();
          assertThat(listed(keys)).containsExactlyInAnyOrderElementsOf(keys);
          return null;
        });
  }

  @Test
  @DisplayName("사진을 모두 새것으로 바꾸면 원래 키만 오르고 새 키는 오르지 않는다")
  void editReplacingAllListsOldOnly() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          List<String> old = List.of(key("old1"), key("old2"));
          List<String> fresh = List.of(key("new1"));
          reviews.put(Target.PLACE, place, me, 5, null, old, Set.copyOf(old));

          reviews.put(Target.PLACE, place, me, 5, null, fresh, Set.copyOf(fresh));

          assertThat(listed(old)).containsExactlyInAnyOrderElementsOf(old);
          assertThat(listed(fresh)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("내 리뷰를 고쳐도 같은 곳의 남의 리뷰 사진은 오르지 않는다")
  void editDoesNotTouchOthersPhotos() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          UUID other = member();
          List<String> mine = List.of(key("m"));
          List<String> theirs = List.of(key("t"));
          reviews.put(Target.PLACE, place, me, 5, null, mine, Set.copyOf(mine));
          reviews.put(Target.PLACE, place, other, 5, null, theirs, Set.copyOf(theirs));

          reviews.put(Target.PLACE, place, me, 5, null, List.of(), Set.of());

          assertThat(listed(mine)).containsExactlyElementsOf(mine);
          assertThat(listed(theirs)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("고치기가 거절되면(받지 않는 사진 키) 빠질 뻔한 사진도 목록에 오르지 않고 사진은 그대로다")
  void rejectedEditListsNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          String a = key("keep");
          String b = key("would-drop");
          reviews.put(Target.PLACE, place, me, 2, "처음", List.of(a, b), Set.of(a, b));
          long before = count("SELECT count(*) FROM storage_deletion");

          Object result =
              nested(
                  () -> {
                    reviews.put(
                        Target.PLACE,
                        place,
                        me,
                        5,
                        "바꾼 글",
                        List.of(a, "uploads/tmp/it-revdel-" + tag + "-bogus.jpg"),
                        Set.of());
                    return Boolean.TRUE;
                  });

          assertThat(result).isInstanceOf(ReviewStore.PhotoKeyRejectedException.class);
          assertThat(count("SELECT count(*) FROM storage_deletion")).isEqualTo(before);
          assertThat(listed(List.of(a, b))).isEmpty();
          assertThat(reviews.mine(Target.PLACE, place, me).orElseThrow().photoKeys())
              .containsExactly(a, b);
          return null;
        });
  }

  @Test
  @DisplayName("고치며 뺀 키가 이미 목록에 있어도 고치기는 성공한다 — 있던 줄은 그대로")
  void editWithAlreadyListedDroppedKey() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          String already = key("already");
          String fresh = key("fresh-drop");
          String kept = key("kept");
          reviews.put(
              Target.PLACE,
              place,
              me,
              5,
              null,
              List.of(already, fresh, kept),
              Set.of(already, fresh, kept));
          preList(already);

          Object result =
              nested(
                  () -> {
                    reviews.put(Target.PLACE, place, me, 4, null, List.of(kept), Set.of());
                    return Boolean.TRUE;
                  });

          assertThat(result).as("이미 목록에 있는 키 때문에 고치기가 실패하지 않는다").isEqualTo(Boolean.TRUE);
          assertThat(reviews.mine(Target.PLACE, place, me).orElseThrow().photoKeys())
              .containsExactly(kept);
          assertThat(row(already)).isEqualTo(PRE_LISTED);
          assertThat(row(fresh)).endsWith("|0|");
          assertThat(listed(List.of(kept))).isEmpty();
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  private static final String PRE_LISTED = "2001-02-03T04:05:06Z|2|앞선 실패(시험)";

  private static void preList(String key) {
    jdbc.sql(
            "INSERT INTO storage_deletion (storage_key, created_at, attempts, last_error)"
                + " VALUES (:k, TIMESTAMPTZ '2001-02-03 04:05:06+00', 2, :e)")
        .param("k", key)
        .param("e", "앞선 실패(시험)")
        .update();
  }

  /** 저장점(NESTED) 안에서 돌린다. 던지면 저장점까지만 되돌리고 그 예외를 돌려준다. */
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
    links.register(id, "google", "it-revdel-" + UUID.randomUUID(), null, null);
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

  private static long poi() {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, '시험 식당', ST_SetSRID(ST_MakePoint(126.25, 33.21), 4326)::geography, '한식', 'food')
            RETURNING id
            """)
        .param("s", "it-revdel-" + UUID.randomUUID())
        .query(Long.class)
        .single();
  }

  private String key(String name) {
    return "reviews/it-revdel-" + tag + "-" + name + ".jpg";
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

  private static long imageCount(long reviewId) {
    return count("SELECT count(*) FROM review_image WHERE review_id = " + reviewId);
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
