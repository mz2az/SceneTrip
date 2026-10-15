package com.mz2az.scenetrip.sceneapi.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 탈퇴({@link UserStore#delete}) 때 그 사람이 쓴 글을 함께 지우는지 실제 DB 로 본다(MZ2AZ-466).
 *
 * <p>명세에서 나온 기대: 탈퇴하면 그 계정의 리뷰·리뷰 사진, 여행후기·사진·본문 배치·코스 사본, 리뷰 보관 사본이 지워지고, 그 계정으로 합쳐진 비회원 계정과 그 계정의
 * 글도 함께 지워진다. 사진 키(리뷰 사진·후기 사진·보관 사본의 photo_keys)는 전부 지울 목록({@code storage_deletion})에 오르고, 이미 오른
 * 키는 오류가 아니다. 남의 글·사진·보관 사본은 그대로, 내가 한 신고는 줄이 남고 신고자만 비워진다. 없는 계정은 false. 전부 함께 되거나 함께 안 된다.
 *
 * <p>원자성 시험을 빼면 전부 되돌리는 트랜잭션 하나 안에서 돈다. 원자성 시험은 바깥 트랜잭션이 대신 묶어 주지 않도록 커밋 모드로 돌고, 만든 것을 직접 지운다.
 */
@DisplayName("UserStore.delete — 탈퇴하면 쓴 글·사진·보관 사본을 함께 지운다 (실제 DB)")
class AccountDeletionContentIntegrationTest {

  private static JdbcClient jdbc;
  private static UserStore users;
  private static AccountLinkStore links;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  /** 한 계정이 가진 글 묶음 — 리뷰 하나(사진 둘), 후기 하나(사진 둘·배치·코스 사본), 보관 사본 둘(사진 둘·없음). */
  private record Content(
      UUID user, long review, long post, List<Long> archives, List<String> keys) {}

  @Test
  @DisplayName("탈퇴하면 리뷰·리뷰 사진·후기·후기 사진·코스 사본·보관 사본이 사라지고, 그 사진 키가 전부 지울 목록에 오른다")
  void deletesOwnContentAndListsKeys() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          Content mine = content(me, place, "me");

          assertThat(users.delete(me)).isTrue();

          assertGone(mine);
          assertThat(listed(mine.keys())).containsExactlyInAnyOrderElementsOf(mine.keys());
          assertThat(
                  count(
                      "SELECT count(*) FROM storage_deletion WHERE storage_key IN (:k)"
                          + " AND (attempts <> 0 OR last_error IS NOT NULL)",
                      mine.keys()))
              .as("새로 오른 키는 attempts 0, last_error 없음")
              .isZero();
          assertThat(countUser("SELECT count(*) FROM app_user WHERE id = CAST(:u AS UUID)", me))
              .isZero();
          return null;
        });
  }

  @Test
  @DisplayName("합쳐진 비회원 계정도 지워지고, 그 계정의 리뷰·후기·보관 사본과 사진 키도 함께 처리된다")
  void deletesMergedGuestsAndTheirContent() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          UUID guest1 = guest();
          UUID guest2 = guest();
          mergeInto(guest1, me);
          mergeInto(guest2, me);
          Content g1 = content(guest1, place, "g1");

          assertThat(users.delete(me)).isTrue();

          assertGone(g1);
          assertThat(listed(g1.keys())).containsExactlyInAnyOrderElementsOf(g1.keys());
          for (UUID g : List.of(guest1, guest2)) {
            assertThat(countUser("SELECT count(*) FROM app_user WHERE id = CAST(:u AS UUID)", g))
                .as("합쳐진 비회원 계정 %s", g)
                .isZero();
          }
          return null;
        });
  }

  @Test
  @DisplayName("남의 리뷰·후기·보관 사본·사진(그 사람에게 합쳐진 비회원 것까지)은 그대로이고, 그 키는 목록에 오르지 않는다")
  void othersContentUntouched() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          UUID other = member();
          UUID othersGuest = guest();
          mergeInto(othersGuest, other);
          content(me, place, "me");
          Content theirs = content(other, place, "other");
          Content theirGuest = content(othersGuest, place, "otherguest");

          assertThat(users.delete(me)).isTrue();

          for (Content c : List.of(theirs, theirGuest)) {
            assertKept(c);
            assertThat(listed(c.keys())).isEmpty();
          }
          assertThat(countUser("SELECT count(*) FROM app_user WHERE id = CAST(:u AS UUID)", other))
              .isEqualTo(1);
          assertThat(
                  countUser(
                      "SELECT count(*) FROM app_user WHERE id = CAST(:u AS UUID)", othersGuest))
              .isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("내가 한 신고는 줄이 남고 신고자만 비워진다 — 남이 한 신고는 신고자 그대로")
  void reportsKeptReporterNulled() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          UUID other = member();
          Content theirs = content(other, place, "other");
          long myReport = report(me, "post", theirs.post());
          long theirReport = report(other, "review", theirs.review());

          assertThat(users.delete(me)).isTrue();

          assertThat(reporterOf(myReport)).as("내 신고 줄은 남는다").isPresent();
          assertThat(reporterOf(myReport).get()).as("신고자는 비워진다").isEqualTo("");
          assertThat(reporterOf(theirReport)).contains(other.toString());
          return null;
        });
  }

  @Test
  @DisplayName("지울 목록에 이미 있는 키가 섞여 있어도 탈퇴는 성공한다 — 있던 줄은 그대로, 나머지 키도 오른다")
  void alreadyListedKeyIsNotAnError() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          Content mine = content(me, place, "me");
          String already = mine.keys().get(0);
          jdbc.sql(
                  "INSERT INTO storage_deletion (storage_key, created_at, attempts, last_error)"
                      + " VALUES (:k, TIMESTAMPTZ '2001-02-03 04:05:06+00', 2, :e)")
              .param("k", already)
              .param("e", "앞선 실패(시험)")
              .update();

          assertThat(users.delete(me)).isTrue();

          assertGone(mine);
          assertThat(listed(mine.keys())).containsExactlyInAnyOrderElementsOf(mine.keys());
          assertThat(
                  jdbc.sql(
                          "SELECT attempts || '|' || coalesce(last_error, '') FROM storage_deletion"
                              + " WHERE storage_key = :k")
                      .param("k", already)
                      .query(String.class)
                      .single())
              .isEqualTo("2|앞선 실패(시험)");
          return null;
        });
  }

  @Test
  @DisplayName("예전처럼 장바구니·차단(양쪽)·로그인 기록·기기도 함께 지워진다")
  void existingCascadesStillHappen() {
    IntegrationDatabase.rolledBack(
        () -> {
          long place = place();
          UUID me = member();
          UUID other = member();
          jdbc.sql("INSERT INTO saved_place (user_id, place_id) VALUES (CAST(:u AS UUID), :p)")
              .param("u", me.toString())
              .param("p", place)
              .update();
          block(me, other);
          block(other, me);

          assertThat(users.delete(me)).isTrue();

          assertThat(
                  countUser(
                      "SELECT count(*) FROM saved_place WHERE user_id = CAST(:u AS UUID)", me))
              .isZero();
          assertThat(
                  countUser(
                      "SELECT count(*) FROM user_block WHERE blocker_id = CAST(:u AS UUID)"
                          + " OR blocked_id = CAST(:u AS UUID)",
                      me))
              .isZero();
          assertThat(
                  countUser(
                      "SELECT count(*) FROM user_identity WHERE user_id = CAST(:u AS UUID)", me))
              .isZero();
          assertThat(
                  countUser(
                      "SELECT count(*) FROM user_device WHERE user_id = CAST(:u AS UUID)", me))
              .isZero();
          return null;
        });
  }

  @Test
  @DisplayName("없는 계정은 false — 지울 목록도 그대로")
  void missingAccountIsFalse() {
    IntegrationDatabase.rolledBack(
        () -> {
          long before =
              jdbc.sql("SELECT count(*) FROM storage_deletion").query(Long.class).single();

          assertThat(users.delete(UUID.randomUUID())).isFalse();

          assertThat(jdbc.sql("SELECT count(*) FROM storage_deletion").query(Long.class).single())
              .isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("원자성 — 계정 행을 지우는 단계에서 실패하면 글·사진·보관 사본·지울 목록 어느 것도 바뀌지 않는다 (커밋 모드)")
  void atomicOnFailure() {
    String fn = "it_acctdel_fail_" + tag;
    long place = -1;
    UUID me = null;
    Content mine = null;
    try {
      place = place();
      me = member();
      mine = content(me, place, "atomic");
      // 계정 행을 지우려는 순간에만 실패시킨다 — 다른 계정에는 영향이 없다. id 는 이 시험이 만든 UUID 다.
      IntegrationDatabase.execute(
          "CREATE FUNCTION "
              + fn
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.id = '"
              + me
              + "'::uuid THEN RAISE EXCEPTION 'it: 탈퇴 실패 주입'; END IF; RETURN OLD; END $$;"
              + " CREATE TRIGGER "
              + fn
              + " BEFORE DELETE ON app_user FOR EACH ROW EXECUTE FUNCTION "
              + fn
              + "();");

      UUID target = me;
      Object result;
      try {
        result = users.delete(target);
      } catch (RuntimeException e) {
        result = e;
      }

      assertThat(result).as("실패는 true 로 보고되지 않는다").isNotEqualTo(Boolean.TRUE);
      assertKept(mine);
      assertThat(listed(mine.keys())).as("지울 목록에 오른 키가 없다").isEmpty();
      assertThat(countUser("SELECT count(*) FROM app_user WHERE id = CAST(:u AS UUID)", me))
          .isEqualTo(1);
    } finally {
      IntegrationDatabase.execute(
          "DROP TRIGGER IF EXISTS " + fn + " ON app_user; DROP FUNCTION IF EXISTS " + fn + "();");
      if (mine != null) {
        jdbc.sql("DELETE FROM storage_deletion WHERE storage_key IN (:k)")
            .param("k", mine.keys())
            .update();
        jdbc.sql("DELETE FROM review_archive WHERE id IN (:a)")
            .param("a", mine.archives())
            .update();
        jdbc.sql("DELETE FROM community_post WHERE id = :p").param("p", mine.post()).update();
        jdbc.sql("DELETE FROM review WHERE id = :r").param("r", mine.review()).update();
      }
      if (me != null) {
        jdbc.sql("DELETE FROM app_user WHERE id = CAST(:u AS UUID)")
            .param("u", me.toString())
            .update();
      }
      if (place != -1) {
        jdbc.sql("DELETE FROM place WHERE id = :p").param("p", place).update();
      }
    }
  }

  // ───────────── 단언 ─────────────

  private static void assertGone(Content c) {
    assertThat(count("SELECT count(*) FROM review WHERE id = " + c.review())).as("리뷰").isZero();
    assertThat(count("SELECT count(*) FROM review_image WHERE review_id = " + c.review()))
        .as("리뷰 사진")
        .isZero();
    assertThat(count("SELECT count(*) FROM community_post WHERE id = " + c.post()))
        .as("후기")
        .isZero();
    assertThat(count("SELECT count(*) FROM community_post_photo WHERE post_id = " + c.post()))
        .as("후기 사진")
        .isZero();
    assertThat(count("SELECT count(*) FROM community_post_item WHERE post_id = " + c.post()))
        .as("코스 사본")
        .isZero();
    assertThat(archivesLeft(c.archives())).as("보관 사본").isZero();
  }

  private static void assertKept(Content c) {
    assertThat(count("SELECT count(*) FROM review WHERE id = " + c.review())).as("리뷰").isEqualTo(1);
    assertThat(count("SELECT count(*) FROM review_image WHERE review_id = " + c.review()))
        .as("리뷰 사진")
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM community_post WHERE id = " + c.post()))
        .as("후기")
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM community_post WHERE body_blocks IS NOT NULL AND id = "
                    + c.post()))
        .as("후기 배치")
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM community_post_photo WHERE post_id = " + c.post()))
        .as("후기 사진")
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM community_post_item WHERE post_id = " + c.post()))
        .as("코스 사본")
        .isEqualTo(1);
    assertThat(archivesLeft(c.archives())).as("보관 사본").isEqualTo(c.archives().size());
  }

  // ───────────── 만들기 ─────────────

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-acctdel-" + UUID.randomUUID(), null, null);
    return id;
  }

  private static UUID guest() {
    return users.resolve(UUID.randomUUID());
  }

  private static void mergeInto(UUID from, UUID to) {
    jdbc.sql("UPDATE app_user SET merged_into = CAST(:to AS UUID) WHERE id = CAST(:id AS UUID)")
        .param("to", to.toString())
        .param("id", from.toString())
        .update();
  }

  private static long place() {
    return jdbc.sql(
            "INSERT INTO place (type, geom) VALUES ('시험',"
                + " ST_SetSRID(ST_MakePoint(126.98, 37.58), 4326)::geography) RETURNING id")
        .query(Long.class)
        .single();
  }

  private Content content(UUID user, long place, String who) {
    List<String> keys = new ArrayList<>();
    String r1 = key(who, "review-1");
    String r2 = key(who, "review-2");
    String p1 = key(who, "post-1");
    String p2 = key(who, "post-2");
    String a1 = key(who, "archive-1");
    String a2 = key(who, "archive-2");
    keys.addAll(List.of(r1, r2, p1, p2, a1, a2));

    long review =
        jdbc.sql(
                "INSERT INTO review (place_id, user_id, rating, body) VALUES (:p, CAST(:u AS UUID),"
                    + " 4, '시험 리뷰') RETURNING id")
            .param("p", place)
            .param("u", user.toString())
            .query(Long.class)
            .single();
    reviewImage(review, r1, 0);
    reviewImage(review, r2, 1);

    long post =
        jdbc.sql(
                """
                INSERT INTO community_post (user_id, title, body, course_title, course_day_count, body_blocks)
                VALUES (CAST(:u AS UUID), '시험 후기', '본문', '시험 코스', 1,
                        CAST('[{"type":"text","text":"본문"}]' AS jsonb))
                RETURNING id
                """)
            .param("u", user.toString())
            .query(Long.class)
            .single();
    postPhoto(post, p1, 0);
    postPhoto(post, p2, 1);
    jdbc.sql(
            "INSERT INTO community_post_item (post_id, day_no, sort_order, place_id, dwell_min)"
                + " VALUES (:post, 1, 0, :p, 30)")
        .param("post", post)
        .param("p", place)
        .update();

    long withPhotos = archive(user, place, "{" + a1 + "," + a2 + "}", "0 seconds");
    long withoutPhotos = archive(user, place, "{}", "0 seconds");
    return new Content(user, review, post, List.of(withPhotos, withoutPhotos), keys);
  }

  private static void reviewImage(long review, String key, int order) {
    jdbc.sql("INSERT INTO review_image (review_id, storage_key, sort_order) VALUES (:r, :k, :o)")
        .param("r", review)
        .param("k", key)
        .param("o", order)
        .update();
  }

  private static void postPhoto(long post, String key, int order) {
    jdbc.sql(
            "INSERT INTO community_post_photo (post_id, storage_key, sort_order) VALUES (:p, :k,"
                + " :o)")
        .param("p", post)
        .param("k", key)
        .param("o", order)
        .update();
  }

  private static long archive(UUID user, long place, String keysLiteral, String age) {
    return jdbc.sql(
            """
            INSERT INTO review_archive (archived_at, reason, review_id, place_id, user_id, rating,
                                        body, created_at, updated_at, photo_keys)
            VALUES (now() - CAST(:age AS interval), 'it-acctdel', :rid, :p, CAST(:u AS UUID), 3,
                    '보관', now(), now(), CAST(:keys AS text[]))
            RETURNING id
            """)
        .param("age", age)
        .param("rid", -1L)
        .param("p", place)
        .param("u", user.toString())
        .param("keys", keysLiteral)
        .query(Long.class)
        .single();
  }

  private static long report(UUID reporter, String type, long target) {
    return jdbc.sql(
            "INSERT INTO content_report (reporter_id, target_type, target_id, reason)"
                + " VALUES (CAST(:u AS UUID), :t, :id, 'spam') RETURNING id")
        .param("u", reporter.toString())
        .param("t", type)
        .param("id", target)
        .query(Long.class)
        .single();
  }

  private static void block(UUID blocker, UUID blocked) {
    jdbc.sql(
            "INSERT INTO user_block (blocker_id, blocked_id) VALUES (CAST(:a AS UUID), CAST(:b AS"
                + " UUID))")
        .param("a", blocker.toString())
        .param("b", blocked.toString())
        .update();
  }

  private String key(String who, String name) {
    return "it-acctdel/" + tag + "-" + who + "-" + name + ".jpg";
  }

  // ───────────── 읽기 ─────────────

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  private static long count(String sql, List<String> keys) {
    return jdbc.sql(sql).param("k", keys).query(Long.class).single();
  }

  private static long countUser(String sql, UUID user) {
    return jdbc.sql(sql).param("u", user.toString()).query(Long.class).single();
  }

  private static long archivesLeft(List<Long> ids) {
    return jdbc.sql("SELECT count(*) FROM review_archive WHERE id IN (:a)")
        .param("a", ids)
        .query(Long.class)
        .single();
  }

  private static List<String> listed(List<String> keys) {
    return jdbc.sql("SELECT storage_key FROM storage_deletion WHERE storage_key IN (:k)")
        .param("k", keys)
        .query(String.class)
        .list();
  }

  /** 신고 줄의 신고자. 줄이 없으면 비어 있고, 신고자가 NULL 이면 빈 문자열이다. */
  private static java.util.Optional<String> reporterOf(long reportId) {
    return jdbc.sql("SELECT coalesce(reporter_id::text, '') FROM content_report WHERE id = :id")
        .param("id", reportId)
        .query(String.class)
        .optional();
  }
}
