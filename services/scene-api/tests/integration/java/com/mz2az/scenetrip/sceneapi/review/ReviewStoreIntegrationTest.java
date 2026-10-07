package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.MineRow;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Page;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Photo;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Row;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Sort;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Summary;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore.Target;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ReviewStore} 를 진짜 PostgreSQL(V21)에 태운다 — 계약 1.4.0 tag {@code reviews}, 계획 {@code
 * review.md} §2·§3·§12.
 *
 * <p>적재 데이터를 건드리지 않도록 시험마다 제 촬영지·편의시설을 만들어 쓰고, 끝나면 그것과 만든 계정을 지운다. 리뷰는 촬영지·편의시설을 지우면 CASCADE 로 함께
 * 사라진다(탈퇴로 작성자가 끊긴 리뷰도).
 */
@DisplayName("ReviewStore — 리뷰·별점·사진첩 (실제 DB)")
class ReviewStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;
  private static AccountLinkStore links;
  private static ReviewStore reviews;

  private final List<UUID> createdUsers = new ArrayList<>();
  private final List<Long> createdPlaces = new ArrayList<>();
  private final List<Long> createdPois = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
    reviews = new ReviewStore(jdbc, transactions);
  }

  @AfterEach
  void cleanUp() {
    for (long id : createdPlaces) {
      jdbc.sql("DELETE FROM place WHERE id = :id").param("id", id).update();
    }
    for (long id : createdPois) {
      jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", id).update();
    }
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ───────────── 대상 ─────────────

  @Test
  @DisplayName("exists — 촬영지는 숨긴 것도 있다, 편의시설은 폐업하지 않은 것만, 없는 id 는 없다")
  void existsFollowsDetailRules() {
    long place = place("시험 촬영지");
    long hidden = place("숨긴 촬영지");
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", hidden).update();
    long poi = poi("시험 식당");
    long closed = poi("폐업 식당");
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", closed).update();

    assertThat(reviews.exists(Target.PLACE, place)).isTrue();
    assertThat(reviews.exists(Target.PLACE, hidden)).isTrue();
    assertThat(reviews.exists(Target.PLACE, -1L)).isFalse();
    assertThat(reviews.exists(Target.POI, poi)).isTrue();
    assertThat(reviews.exists(Target.POI, closed)).isFalse();
    assertThat(reviews.exists(Target.POI, -1L)).isFalse();
  }

  // ───────────── 쓰기·고치기 ─────────────

  @Test
  @DisplayName("put — 처음이면 만들고 그 리뷰를 돌려준다(작성자 닉네임, isMine, 고친 적 없으면 updatedAt = createdAt)")
  void putCreates() {
    long place = place("시험 촬영지");
    UUID me = member();

    Row saved = reviews.put(Target.PLACE, place, me, 4, "좋았어요", List.of(), Set.of());

    assertThat(saved.rating()).isEqualTo(4);
    assertThat(saved.body()).isEqualTo("좋았어요");
    assertThat(saved.nickname()).isEqualTo(nickname(me));
    assertThat(saved.mine()).isTrue();
    assertThat(saved.photoKeys()).isEmpty();
    assertThat(saved.updatedAt()).isEqualTo(saved.createdAt());
    assertThat(reviews.mine(Target.PLACE, place, me)).contains(saved);
  }

  @Test
  @DisplayName("put — 한 사람이 한 곳에 하나: 두 번째는 같은 리뷰를 고친다(id·createdAt 그대로, updatedAt 이 바뀐다)")
  void putTwiceUpdates() throws InterruptedException {
    long poi = poi("시험 식당");
    UUID me = member();
    Row first = reviews.put(Target.POI, poi, me, 2, "별로", List.of(), Set.of());
    Thread.sleep(20);

    Row second = reviews.put(Target.POI, poi, me, 5, null, List.of(), Set.of());

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.rating()).isEqualTo(5);
    assertThat(second.body()).isNull();
    assertThat(second.createdAt()).isEqualTo(first.createdAt());
    assertThat(second.updatedAt()).isAfter(first.updatedAt());
    assertThat(countReviews("poi_id", poi)).isEqualTo(1);
  }

  @Test
  @DisplayName("put — 같은 사람이 촬영지와 편의시설에 각각 하나씩은 쓸 수 있다, 다른 사람은 같은 곳에 따로 쓴다")
  void onePerTargetPerUser() {
    long place = place("시험 촬영지");
    long poi = poi("시험 식당");
    UUID me = member();
    UUID other = member();

    reviews.put(Target.PLACE, place, me, 5, null, List.of(), Set.of());
    reviews.put(Target.POI, poi, me, 4, null, List.of(), Set.of());
    reviews.put(Target.PLACE, place, other, 3, null, List.of(), Set.of());

    assertThat(countReviews("place_id", place)).isEqualTo(2);
    assertThat(countReviews("poi_id", poi)).isEqualTo(1);
  }

  @Test
  @DisplayName("put — 올리지 않은 사진 키면 거절하고 아무것도 남기지 않는다(새 리뷰도 생기지 않는다)")
  void putWithUnknownKeyCreatesNothing() {
    long place = place("시험 촬영지");
    UUID me = member();

    assertThatThrownBy(
            () ->
                reviews.put(
                    Target.PLACE, place, me, 5, "글", List.of("uploads/tmp/not-mine"), Set.of()))
        .isInstanceOf(ReviewStore.PhotoKeyRejectedException.class);

    assertThat(countReviews("place_id", place)).isZero();
    assertThat(reviews.mine(Target.PLACE, place, me)).isEmpty();
  }

  @Test
  @DisplayName("put — 고칠 때 사진 키가 거절되면 별점·글·사진 모두 고치기 전 그대로다(전체 되돌림)")
  void putWithUnknownKeyRollsBackUpdate() {
    long place = place("시험 촬영지");
    UUID me = member();
    String key = "reviews/" + UUID.randomUUID();
    Row before = reviews.put(Target.PLACE, place, me, 2, "처음 글", List.of(key), Set.of(key));

    assertThatThrownBy(
            () ->
                reviews.put(
                    Target.PLACE,
                    place,
                    me,
                    5,
                    "바꾼 글",
                    List.of(key, "uploads/tmp/" + UUID.randomUUID()),
                    Set.of()))
        .isInstanceOf(ReviewStore.PhotoKeyRejectedException.class);

    Row after = reviews.mine(Target.PLACE, place, me).orElseThrow();
    assertThat(after.rating()).isEqualTo(2);
    assertThat(after.body()).isEqualTo("처음 글");
    assertThat(after.photoKeys()).containsExactly(key);
    assertThat(after.updatedAt()).isEqualTo(before.updatedAt());
  }

  @Test
  @DisplayName("put — 같은 키를 두 번 보내면 거절한다")
  void putWithDuplicateKeyIsRejected() {
    long place = place("시험 촬영지");
    UUID me = member();
    String key = "uploads/tmp/" + UUID.randomUUID();

    assertThatThrownBy(
            () -> reviews.put(Target.PLACE, place, me, 5, null, List.of(key, key), Set.of(key)))
        .isInstanceOf(ReviewStore.PhotoKeyRejectedException.class);
    assertThat(countReviews("place_id", place)).isZero();
  }

  @Test
  @DisplayName("put — 사진은 보낸 순서 그대로 통째로 바뀐다: 이미 붙은 키는 다시 받고, 빠진 키는 떨어진다")
  void putReplacesPhotosInOrder() {
    long poi = poi("시험 식당");
    UUID me = member();
    String a = "reviews/a-" + UUID.randomUUID();
    String b = "reviews/b-" + UUID.randomUUID();
    String c = "reviews/c-" + UUID.randomUUID();

    Row first = reviews.put(Target.POI, poi, me, 5, null, List.of(a, b), Set.of(a, b));
    assertThat(first.photoKeys()).containsExactly(a, b);

    // b 는 이미 붙어 있어 업로드 목록에 없어도 받는다. a 는 빠졌으니 떨어진다. c 는 새로 올린 것.
    Row second = reviews.put(Target.POI, poi, me, 5, null, List.of(c, b), Set.of(c));

    assertThat(second.photoKeys()).containsExactly(c, b);
    assertThat(imageKeys(second.id())).containsExactly(c, b);
  }

  @Test
  @DisplayName("put — 다른 사람 리뷰에 붙은 키는 업로드 목록에 없으면 받지 않는다")
  void putCannotStealOthersAttachedKey() {
    long place = place("시험 촬영지");
    UUID owner = member();
    UUID thief = member();
    String key = "reviews/" + UUID.randomUUID();
    reviews.put(Target.PLACE, place, owner, 5, null, List.of(key), Set.of(key));

    assertThatThrownBy(
            () -> reviews.put(Target.PLACE, place, thief, 1, null, List.of(key), Set.of()))
        .isInstanceOf(ReviewStore.PhotoKeyRejectedException.class);
    assertThat(reviews.mine(Target.PLACE, place, thief)).isEmpty();
  }

  @Test
  @DisplayName("put — 사진 10 장까지 받는다")
  void putAcceptsTenPhotos() {
    long place = place("시험 촬영지");
    UUID me = member();
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      keys.add("reviews/" + i + "-" + UUID.randomUUID());
    }

    Row saved = reviews.put(Target.PLACE, place, me, 5, null, keys, Set.copyOf(keys));

    assertThat(saved.photoKeys()).containsExactlyElementsOf(keys);
  }

  // ───────────── 지우기 ─────────────

  @Test
  @DisplayName("delete — 지우면 true 이고 사진 행도 사라진다, 다시 지우면 false")
  void deleteRemovesReviewAndImages() {
    long place = place("시험 촬영지");
    UUID me = member();
    String key = "reviews/" + UUID.randomUUID();
    Row saved = reviews.put(Target.PLACE, place, me, 5, null, List.of(key), Set.of(key));

    assertThat(reviews.delete(Target.PLACE, place, me)).isTrue();
    assertThat(reviews.mine(Target.PLACE, place, me)).isEmpty();
    assertThat(imageKeys(saved.id())).isEmpty();
    assertThat(reviews.delete(Target.PLACE, place, me)).isFalse();
  }

  @Test
  @DisplayName("delete — 남의 리뷰는 지우지 않는다")
  void deleteOnlyMine() {
    long place = place("시험 촬영지");
    UUID owner = member();
    UUID other = member();
    reviews.put(Target.PLACE, place, owner, 5, null, List.of(), Set.of());

    assertThat(reviews.delete(Target.PLACE, place, other)).isFalse();
    assertThat(reviews.mine(Target.PLACE, place, owner)).isPresent();
  }

  // ───────────── 요약 ─────────────

  @Test
  @DisplayName("summary — 리뷰가 없으면 평균 null, 수 0, 분포 0 다섯 칸")
  void summaryEmpty() {
    long place = place("시험 촬영지");

    Summary s = reviews.summary(Target.PLACE, place);

    assertThat(s.average()).isNull();
    assertThat(s.count()).isZero();
    assertThat(s.distribution()).containsExactly(0, 0, 0, 0, 0);
  }

  @Test
  @DisplayName("summary — 단순 평균 소수 한 자리, 수, 1~5 점 분포. 운영자가 내린 리뷰는 빠진다")
  void summaryAveragesAndExcludesRemoved() {
    long poi = poi("시험 식당");
    reviews.put(Target.POI, poi, member(), 5, null, List.of(), Set.of());
    reviews.put(Target.POI, poi, member(), 4, null, List.of(), Set.of());
    reviews.put(Target.POI, poi, member(), 4, null, List.of(), Set.of());
    UUID troll = member();
    Row removed = reviews.put(Target.POI, poi, troll, 1, "도배", List.of(), Set.of());
    remove(removed.id());

    Summary s = reviews.summary(Target.POI, poi);

    // (5+4+4)/3 = 4.333… → 4.3
    assertThat(s.average()).isEqualTo(4.3);
    assertThat(s.count()).isEqualTo(3);
    assertThat(s.distribution()).containsExactly(0, 0, 0, 2, 1);
  }

  @Test
  @DisplayName("summary — 소수 한 자리로 반올림(4.75 → 4.8), 촬영지·편의시설은 따로 센다")
  void summaryRoundsToOneDecimal() {
    long place = place("시험 촬영지");
    long poi = poi("시험 식당");
    for (int r : List.of(5, 5, 5, 4)) {
      reviews.put(Target.PLACE, place, member(), r, null, List.of(), Set.of());
    }
    reviews.put(Target.POI, poi, member(), 1, null, List.of(), Set.of());

    // (5+5+5+4)/4 = 4.75 → 4.8
    assertThat(reviews.summary(Target.PLACE, place).average()).isEqualTo(4.8);
    assertThat(reviews.summary(Target.PLACE, place).count()).isEqualTo(4);
    assertThat(reviews.summary(Target.POI, poi).average()).isEqualTo(1.0);
    assertThat(reviews.summary(Target.POI, poi).count()).isEqualTo(1);
  }

  // ───────────── 목록 ─────────────

  @Test
  @DisplayName("list — 최신순 기본, 별점 높은·낮은 순은 같은 별점이면 최신이 앞선다")
  void listSorts() throws InterruptedException {
    long place = place("시험 촬영지");
    long r1 = put(Target.PLACE, place, 3); // 가장 오래됨
    long r2 = put(Target.PLACE, place, 5);
    long r3 = put(Target.PLACE, place, 3);
    long r4 = put(Target.PLACE, place, 1); // 가장 최신

    assertThat(ids(reviews.list(Target.PLACE, place, Sort.RECENT, 20, 0, null)))
        .containsExactly(r4, r3, r2, r1);
    assertThat(ids(reviews.list(Target.PLACE, place, Sort.RATING_HIGH, 20, 0, null)))
        .containsExactly(r2, r3, r1, r4);
    assertThat(ids(reviews.list(Target.PLACE, place, Sort.RATING_LOW, 20, 0, null)))
        .containsExactly(r4, r3, r1, r2);
  }

  @Test
  @DisplayName("list — limit·offset 으로 자르고 total 은 전체 수")
  void listPages() throws InterruptedException {
    long poi = poi("시험 식당");
    long r1 = put(Target.POI, poi, 3);
    long r2 = put(Target.POI, poi, 4);
    long r3 = put(Target.POI, poi, 5);

    Page<Row> page = reviews.list(Target.POI, poi, Sort.RECENT, 2, 1, null);

    assertThat(ids(page)).containsExactly(r2, r1);
    assertThat(page.total()).isEqualTo(3);
    assertThat(ids(reviews.list(Target.POI, poi, Sort.RECENT, 2, 0, null))).containsExactly(r3, r2);
  }

  @Test
  @DisplayName("list — 운영자가 내린 리뷰는 목록·total 에서 빠진다, 다른 곳의 리뷰는 섞이지 않는다")
  void listExcludesRemovedAndOtherTargets() throws InterruptedException {
    long place = place("시험 촬영지");
    long otherPlace = place("다른 촬영지");
    long kept = put(Target.PLACE, place, 4);
    long removed = put(Target.PLACE, place, 1);
    remove(removed);
    put(Target.PLACE, otherPlace, 5);

    Page<Row> page = reviews.list(Target.PLACE, place, Sort.RECENT, 20, 0, null);

    assertThat(ids(page)).containsExactly(kept);
    assertThat(page.total()).isEqualTo(1);
  }

  @Test
  @DisplayName("list — mine 은 보는 사람 자신의 리뷰만 true, 보는 사람이 없으면 모두 false. 작성자 닉네임이 실린다")
  void listMarksMine() {
    long place = place("시험 촬영지");
    UUID me = member();
    UUID other = member();
    Row mine = reviews.put(Target.PLACE, place, me, 5, null, List.of(), Set.of());
    Row theirs = reviews.put(Target.PLACE, place, other, 4, null, List.of(), Set.of());

    Page<Row> asMe = reviews.list(Target.PLACE, place, Sort.RECENT, 20, 0, me);
    Page<Row> anonymous = reviews.list(Target.PLACE, place, Sort.RECENT, 20, 0, null);

    assertThat(asMe.items())
        .anySatisfy(
            r -> {
              assertThat(r.id()).isEqualTo(mine.id());
              assertThat(r.mine()).isTrue();
              assertThat(r.nickname()).isEqualTo(nickname(me));
            })
        .anySatisfy(
            r -> {
              assertThat(r.id()).isEqualTo(theirs.id());
              assertThat(r.mine()).isFalse();
              assertThat(r.nickname()).isEqualTo(nickname(other));
            });
    assertThat(anonymous.items()).allSatisfy(r -> assertThat(r.mine()).isFalse());
  }

  @Test
  @DisplayName("list — 각 리뷰의 사진 키가 올린 순서대로 실린다")
  void listCarriesPhotoKeys() {
    long poi = poi("시험 식당");
    String a = "reviews/a-" + UUID.randomUUID();
    String b = "reviews/b-" + UUID.randomUUID();
    reviews.put(Target.POI, poi, member(), 5, null, List.of(b, a), Set.of(a, b));

    Row row = reviews.list(Target.POI, poi, Sort.RECENT, 20, 0, null).items().get(0);

    assertThat(row.photoKeys()).containsExactly(b, a);
  }

  @Test
  @DisplayName("mine — 운영자가 내린 내 리뷰는 없는 것으로 본다")
  void mineHidesRemoved() {
    long place = place("시험 촬영지");
    UUID me = member();
    Row saved = reviews.put(Target.PLACE, place, me, 3, null, List.of(), Set.of());
    remove(saved.id());

    assertThat(reviews.mine(Target.PLACE, place, me)).isEmpty();
  }

  // ───────────── 탈퇴 ─────────────

  @Test
  @DisplayName("탈퇴(UserStore.delete) — 리뷰는 남고 작성자만 끊긴다: 목록의 nickname 이 null, 별점 요약도 그대로")
  void accountDeletionKeepsReviewsAnonymously() {
    long place = place("시험 촬영지");
    UUID leaver = member();
    String key = "reviews/" + UUID.randomUUID();
    Row saved = reviews.put(Target.PLACE, place, leaver, 4, "남는 글", List.of(key), Set.of(key));

    assertThat(users.delete(leaver)).isTrue();

    assertThat(userIdOf(saved.id())).isNull();
    Page<Row> page = reviews.list(Target.PLACE, place, Sort.RECENT, 20, 0, null);
    assertThat(page.total()).isEqualTo(1);
    Row row = page.items().get(0);
    assertThat(row.id()).isEqualTo(saved.id());
    assertThat(row.nickname()).isNull();
    assertThat(row.body()).isEqualTo("남는 글");
    assertThat(row.photoKeys()).containsExactly(key);
    assertThat(reviews.summary(Target.PLACE, place).count()).isEqualTo(1);
    assertThat(reviews.photos(Target.PLACE, place, 20, 0).items())
        .extracting(Photo::storageKey)
        .contains(key);
  }

  @Test
  @DisplayName("탈퇴한 두 사람의 리뷰가 같은 곳에 함께 남을 수 있다(user_id NULL 끼리는 유일 제약에 걸리지 않는다)")
  void twoDeletedAuthorsOnSameTarget() {
    long poi = poi("시험 식당");
    UUID a = member();
    UUID b = member();
    reviews.put(Target.POI, poi, a, 5, null, List.of(), Set.of());
    reviews.put(Target.POI, poi, b, 3, null, List.of(), Set.of());

    users.delete(a);
    users.delete(b);

    assertThat(reviews.list(Target.POI, poi, Sort.RECENT, 20, 0, null).total()).isEqualTo(2);
  }

  // ───────────── 내가 쓴 리뷰 ─────────────

  @Test
  @DisplayName("listMine — 촬영지·편의시설을 섞어 최신순, 어느 곳인지(type·id·name)가 붙는다. 내린 것은 빠진다")
  void listMineMixesTargets() throws InterruptedException {
    long place = place("북촌 시험", "Bukchon Test", null);
    long poi = poi("모슬포 시험 식당");
    long removedPlace = place("내린 촬영지");
    UUID me = member();
    reviews.put(Target.PLACE, place, me, 5, null, List.of(), Set.of());
    Thread.sleep(20);
    reviews.put(Target.POI, poi, me, 3, null, List.of(), Set.of());
    Thread.sleep(20);
    Row removed = reviews.put(Target.PLACE, removedPlace, me, 1, null, List.of(), Set.of());
    remove(removed.id());
    // 남의 리뷰는 나오지 않는다
    reviews.put(Target.PLACE, place, member(), 2, null, List.of(), Set.of());

    Page<MineRow> page = reviews.listMine(me, "ko", 20, 0);

    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(2);
    MineRow newest = page.items().get(0);
    MineRow older = page.items().get(1);
    assertThat(newest.target()).isEqualTo(Target.POI);
    assertThat(newest.targetId()).isEqualTo(poi);
    assertThat(newest.targetName()).isEqualTo("모슬포 시험 식당");
    assertThat(newest.review().mine()).isTrue();
    assertThat(older.target()).isEqualTo(Target.PLACE);
    assertThat(older.targetId()).isEqualTo(place);
    assertThat(older.targetName()).isEqualTo("북촌 시험");
  }

  @Test
  @DisplayName("listMine — 촬영지 이름은 요청 언어 → en → ko, 편의시설은 언제나 한국어 원본")
  void listMinePlaceNameFallback() {
    long withJa = place("일본어도 있음", "Has Japanese", "日本語あり");
    long enOnly = place("영어까지", "English Only", null);
    long koOnly = place("한국어만", null, null);
    long poi = poi("한국어 식당");
    UUID me = member();
    for (long p : List.of(withJa, enOnly, koOnly)) {
      reviews.put(Target.PLACE, p, me, 4, null, List.of(), Set.of());
    }
    reviews.put(Target.POI, poi, me, 4, null, List.of(), Set.of());

    assertThat(namesById(reviews.listMine(me, "ja", 20, 0)))
        .containsEntry(withJa, "日本語あり")
        .containsEntry(enOnly, "English Only")
        .containsEntry(koOnly, "한국어만")
        .containsEntry(poi, "한국어 식당");
    assertThat(namesById(reviews.listMine(me, "en", 20, 0)))
        .containsEntry(withJa, "Has Japanese")
        .containsEntry(koOnly, "한국어만");
    assertThat(namesById(reviews.listMine(me, "ko", 20, 0)))
        .containsEntry(withJa, "일본어도 있음")
        .containsEntry(enOnly, "영어까지");
    assertThat(namesById(reviews.listMine(me, "zh-Hant", 20, 0)))
        .containsEntry(withJa, "Has Japanese")
        .containsEntry(poi, "한국어 식당");
  }

  @Test
  @DisplayName("listMine — limit·offset 으로 자르고 total 은 전체 수")
  void listMinePages() throws InterruptedException {
    UUID me = member();
    List<Long> places = List.of(place("가"), place("나"), place("다"));
    for (long p : places) {
      reviews.put(Target.PLACE, p, me, 4, null, List.of(), Set.of());
      Thread.sleep(20);
    }

    Page<MineRow> page = reviews.listMine(me, "ko", 1, 1);

    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).extracting(MineRow::targetId).containsExactly(places.get(1));
  }

  // ───────────── 사진첩 ─────────────

  @Test
  @DisplayName("photos — 우리 사진 먼저(sort_order 순), 그 뒤 리뷰 사진(최신 리뷰부터, 리뷰 안은 올린 순서). total 은 둘을 합친 수")
  void photosOrderOfficialThenReviews() throws InterruptedException {
    long place = place("시험 촬영지");
    placeImage(place, "https://img.example/second.jpg", 1);
    placeImage(place, "https://img.example/first.jpg", 0);
    String old1 = "reviews/old1-" + UUID.randomUUID();
    String old2 = "reviews/old2-" + UUID.randomUUID();
    String new1 = "reviews/new1-" + UUID.randomUUID();
    Row older =
        reviews.put(
            Target.PLACE, place, member(), 4, null, List.of(old1, old2), Set.of(old1, old2));
    Thread.sleep(20);
    Row newer = reviews.put(Target.PLACE, place, member(), 5, null, List.of(new1), Set.of(new1));

    Page<Photo> page = reviews.photos(Target.PLACE, place, 20, 0);

    assertThat(page.total()).isEqualTo(5);
    assertThat(page.items())
        .extracting(Photo::url)
        .containsExactly(
            "https://img.example/first.jpg", "https://img.example/second.jpg", null, null, null);
    assertThat(page.items())
        .extracting(Photo::storageKey)
        .containsExactly(null, null, new1, old1, old2);
    assertThat(page.items())
        .extracting(Photo::reviewId)
        .containsExactly(null, null, newer.id(), older.id(), older.id());
  }

  @Test
  @DisplayName("photos — limit·offset 이 우리 사진과 리뷰 사진의 경계를 넘어 이어진다")
  void photosPageAcrossBoundary() {
    long place = place("시험 촬영지");
    placeImage(place, "https://img.example/0.jpg", 0);
    placeImage(place, "https://img.example/1.jpg", 1);
    String k = "reviews/" + UUID.randomUUID();
    reviews.put(Target.PLACE, place, member(), 5, null, List.of(k), Set.of(k));

    Page<Photo> page = reviews.photos(Target.PLACE, place, 2, 1);

    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items())
        .extracting(Photo::url)
        .containsExactly("https://img.example/1.jpg", null);
    assertThat(page.items()).extracting(Photo::storageKey).containsExactly(null, k);
  }

  @Test
  @DisplayName("photos — 편의시설은 poi_image 를 저작자 표기와 함께, 운영자가 내린 리뷰의 사진은 빠진다")
  void photosForPoiWithCreditAndRemoved() {
    long poi = poi("시험 식당");
    poiImage(poi, "https://img.example/poi.jpg", 0, "한국관광공사");
    String kept = "reviews/kept-" + UUID.randomUUID();
    String gone = "reviews/gone-" + UUID.randomUUID();
    reviews.put(Target.POI, poi, member(), 5, null, List.of(kept), Set.of(kept));
    Row removed = reviews.put(Target.POI, poi, member(), 1, null, List.of(gone), Set.of(gone));
    remove(removed.id());

    Page<Photo> page = reviews.photos(Target.POI, poi, 20, 0);

    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items().get(0).url()).isEqualTo("https://img.example/poi.jpg");
    assertThat(page.items().get(0).credit()).isEqualTo("한국관광공사");
    assertThat(page.items().get(1).storageKey()).isEqualTo(kept);
  }

  @Test
  @DisplayName("photos — 사진이 없으면 빈 목록과 0")
  void photosEmpty() {
    long place = place("시험 촬영지");

    Page<Photo> page = reviews.photos(Target.PLACE, place, 20, 0);

    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isZero();
  }

  // ───────────── V21 제약 ─────────────

  @Test
  @DisplayName("V21 — 리뷰는 촬영지·편의시설 중 정확히 하나를 가리킨다(둘 다·둘 다 없음은 거부)")
  void reviewTargetsExactlyOne() {
    long place = place("시험 촬영지");
    long poi = poi("시험 식당");
    UUID me = member();

    assertThatThrownBy(() -> insertReview(place, poi, me, 3))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertReview(null, null, me, 3))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V21 — 별점은 1~5")
  void ratingRange() {
    long place = place("시험 촬영지");

    assertThatThrownBy(() -> insertReview(place, null, member(), 0))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertReview(place, null, member(), 6))
        .isInstanceOf(DataIntegrityViolationException.class);
    insertReview(place, null, member(), 1);
    insertReview(place, null, member(), 5);
  }

  @Test
  @DisplayName("V21 — 한 사람이 한 곳에 둘은 DB 도 거부한다")
  void uniquePerUserAndTarget() {
    long place = place("시험 촬영지");
    UUID me = member();
    insertReview(place, null, me, 3);

    assertThatThrownBy(() -> insertReview(place, null, me, 4))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("V21 — 리뷰 사진은 sort_order 0~9, 리뷰당 최대 10 장")
  void atMostTenImages() {
    long place = place("시험 촬영지");
    long review = insertReview(place, null, member(), 5);
    for (int i = 0; i < 10; i++) {
      insertImage(review, i);
    }

    assertThatThrownBy(() -> insertImage(review, 10))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertImage(review, -1))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertImage(review, 3))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(imageKeys(review)).hasSize(10);
  }

  // ───────────── 도우미 ─────────────

  /** 가입한 계정 — 가입 경로(AccountLinkStore.register)를 그대로 타서 자동 닉네임이 붙는다. */
  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    createdUsers.add(id);
    links.register(id, "google", "it-review-" + UUID.randomUUID(), null, null);
    return id;
  }

  private long place(String ko) {
    return place(ko, null, null);
  }

  private long place(String ko, String en, String ja) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(126.98, 37.58), 4326)::geography) RETURNING id")
            .query(Long.class)
            .single();
    createdPlaces.add(id);
    placeName(id, "ko", ko);
    if (en != null) {
      placeName(id, "en", en);
    }
    if (ja != null) {
      placeName(id, "ja", ja);
    }
    return id;
  }

  private static void placeName(long placeId, String lang, String name) {
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, CAST(:l AS lang_code), :n)")
        .param("p", placeId)
        .param("l", lang)
        .param("n", name)
        .update();
  }

  private long poi(String name) {
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group)
                VALUES (:s, :n, ST_SetSRID(ST_MakePoint(126.25, 33.21), 4326)::geography, '한식', 'food')
                RETURNING id
                """)
            .param("s", "it-review-" + UUID.randomUUID())
            .param("n", name)
            .query(Long.class)
            .single();
    createdPois.add(id);
    return id;
  }

  private static void placeImage(long placeId, String url, int order) {
    jdbc.sql("INSERT INTO place_image (place_id, url, sort_order) VALUES (:p, :u, :o)")
        .param("p", placeId)
        .param("u", url)
        .param("o", order)
        .update();
  }

  private static void poiImage(long poiId, String url, int order, String credit) {
    jdbc.sql("INSERT INTO poi_image (poi_id, url, sort_order, credit) VALUES (:p, :u, :o, :c)")
        .param("p", poiId)
        .param("u", url)
        .param("o", order)
        .param("c", credit)
        .update();
  }

  /** 새 가입자 하나가 별점만 남긴다. 만든 시각이 갈리도록 잠깐 쉰다. */
  private long put(Target target, long id, int rating) throws InterruptedException {
    long reviewId = reviews.put(target, id, member(), rating, null, List.of(), Set.of()).id();
    Thread.sleep(20);
    return reviewId;
  }

  private static void remove(long reviewId) {
    jdbc.sql("UPDATE review SET removed_at = now() WHERE id = :id").param("id", reviewId).update();
  }

  private static long insertReview(Long placeId, Long poiId, UUID user, int rating) {
    return jdbc.sql(
            """
            INSERT INTO review (place_id, poi_id, user_id, rating)
            VALUES (CAST(:place AS BIGINT), CAST(:poi AS BIGINT), CAST(:u AS UUID), :r)
            RETURNING id
            """)
        .param("place", placeId)
        .param("poi", poiId)
        .param("u", user.toString())
        .param("r", rating)
        .query(Long.class)
        .single();
  }

  private static void insertImage(long reviewId, int order) {
    jdbc.sql("INSERT INTO review_image (review_id, storage_key, sort_order) VALUES (:r, :k, :o)")
        .param("r", reviewId)
        .param("k", "reviews/" + UUID.randomUUID())
        .param("o", order)
        .update();
  }

  private static List<String> imageKeys(long reviewId) {
    return jdbc.sql("SELECT storage_key FROM review_image WHERE review_id = :r ORDER BY sort_order")
        .param("r", reviewId)
        .query(String.class)
        .list();
  }

  private static int countReviews(String column, long id) {
    // 열 이름은 이 파일 안의 상수로만 들어온다.
    return jdbc.sql("SELECT count(*) FROM review WHERE " + column + " = :id")
        .param("id", id)
        .query(Integer.class)
        .single();
  }

  private static String nickname(UUID user) {
    return jdbc.sql("SELECT nickname FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .query(String.class)
        .single();
  }

  private static UUID userIdOf(long reviewId) {
    return jdbc.sql("SELECT user_id FROM review WHERE id = :id")
        .param("id", reviewId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  private static List<Long> ids(Page<Row> page) {
    return page.items().stream().map(Row::id).toList();
  }

  private static java.util.Map<Long, String> namesById(Page<MineRow> page) {
    java.util.Map<Long, String> m = new java.util.HashMap<>();
    page.items().forEach(r -> m.put(r.targetId(), r.targetName()));
    return m;
  }
}
