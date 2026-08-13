package com.mz2az.scenetrip.sceneapi.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MarketCourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.MarketSort;
import com.mz2az.scenetrip.sceneapi.api.model.PinCategory;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.course.TravelEstimator;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link MarketStore} 의 SQL 을 진짜 PostgreSQL 에 태운다.
 *
 * <p><b>이 티켓의 핵심은 "사본" 이 정말 사본인가</b>이고, 그것은 실제 DB 에서만 확인된다. 올린 뒤 원본을 고쳐도 마켓이 안 따라 변하는가, 직접 찍은 핀이
 * 빠지는가, 내렸다가 다시 올릴 수 있는가, 담아 간 코스가 원본과 끊겨 있는가.
 *
 * <p>HTTP 계층의 {@code 401}(가입 사용자만) 은 여기서 보지 않는다 — 그것은 컨트롤러 몫이고, 지금은 가입시키는 경로가 없어 그 벽 뒤의 로직을 확인할 방법이
 * 이 레인뿐이다.
 */
@DisplayName("MarketStore — 실제 DB 질의")
class MarketStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static MarketStore store;
  private static CourseStore courses;
  private static UserStore users;

  private final UUID authorInstall = UUID.randomUUID();
  private final UUID readerInstall = UUID.randomUUID();
  private UUID author;
  private UUID reader;
  private long placeA;
  private long placeB;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    TravelEstimator travel = new TravelEstimator(4.0, 1.3);
    DwellDefaults dwell = new DwellDefaults();
    dwell.putAll(Map.of());
    courses = new CourseStore(jdbc, travel, dwell, IntegrationDatabase.transactions());
    store = new MarketStore(jdbc, travel, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
  }

  @BeforeEach
  void createUsers() {
    author = users.resolve(authorInstall);
    reader = users.resolve(readerInstall);
    List<Long> places =
        jdbc.sql("SELECT id FROM place ORDER BY id LIMIT 2").query(Long.class).list();
    placeA = places.get(0);
    placeB = places.get(1);
  }

  @AfterEach
  void cleanUp() {
    // 올린 사본은 author 를 지우면 CASCADE 로 따라간다. reader 가 담아 간 코스도 마찬가지다.
    jdbc.sql("DELETE FROM app_user WHERE id IN (CAST(:a AS UUID), CAST(:r AS UUID))")
        .param("a", author.toString())
        .param("r", reader.toString())
        .update();
  }

  @Test
  @DisplayName("올리면 그때의 순서와 머무는 시간이 사본으로 굳는다")
  void publishSnapshotsTheCourse() {
    long courseId = courseWith(item(placeA, 90), item(placeB, 40));

    long postId = store.publish(author, courseId, "제주 성지순례").orElseThrow();

    MarketCourseDetail post = store.find(reader, postId, Lang.KO).orElseThrow();
    assertThat(post.getDescription()).isEqualTo("제주 성지순례");
    assertThat(post.getPlaceCount()).isEqualTo(2);
    assertThat(post.getDays().get(0).getItems())
        .extracting(i -> i.getPlaceId() + ":" + i.getDwellMinutes())
        .containsExactly(placeA + ":90", placeB + ":40");
  }

  @Test
  @DisplayName("올린 뒤 원본을 고쳐도 마켓의 것은 그대로다 — 이 티켓의 전제")
  void originalEditsDoNotLeakIntoTheCopy() {
    long courseId = courseWith(item(placeA, 90), item(placeB, 40));
    long postId = store.publish(author, courseId, "설명").orElseThrow();

    // 원본을 통째로 갈아엎는다 — 장소 하나만 남기고 체류시간도 바꾼다.
    courses.replace(courseId, replace(day(item(placeA, 15))));

    MarketCourseDetail post = store.find(reader, postId, Lang.KO).orElseThrow();
    assertThat(post.getPlaceCount()).isEqualTo(2);
    assertThat(post.getDays().get(0).getItems().get(0).getDwellMinutes()).isEqualTo(90);
  }

  @Test
  @DisplayName("직접 찍은 핀은 올릴 때 빠진다 — 남의 숙소 위치가 공개되면 안 된다")
  void customPinsAreNotPublished() {
    long courseId =
        courseWith(
            item(placeA, 60),
            new CourseItemInput()
                .dwellMinutes(60)
                .customPin(new CustomPinInput("호텔 O", PinCategory.LODGING, 37.55, 126.97)));

    long postId = store.publish(author, courseId, "설명").orElseThrow();

    MarketCourseDetail post = store.find(reader, postId, Lang.KO).orElseThrow();
    assertThat(post.getPlaceCount()).isEqualTo(1);
    assertThat(post.getDays().get(0).getItems())
        .extracting(i -> i.getName())
        .doesNotContain("호텔 O");
  }

  @Test
  @DisplayName("한 코스를 두 번 올릴 수 없다 — 내리면 다시 올릴 수 있다")
  void onlyOneLivePostPerCourse() {
    long courseId = courseWith(item(placeA, 60));
    long first = store.publish(author, courseId, "첫 번째").orElseThrow();

    assertThat(store.publish(author, courseId, "두 번째")).isEmpty();

    assertThat(store.unpublish(author, first)).isTrue();
    long second = store.publish(author, courseId, "다시 올림").orElseThrow();

    assertThat(second).isNotEqualTo(first);
  }

  @Test
  @DisplayName("내리면 목록과 상세에서 사라지지만 행은 남는다")
  void unpublishHidesButKeeps() {
    long postId = store.publish(author, courseWith(item(placeA, 60)), "설명").orElseThrow();

    store.unpublish(author, postId);

    assertThat(store.find(reader, postId, Lang.KO)).isEmpty();
    assertThat(store.isLive(postId)).isFalse();
    // 담아 간 사람의 course.source_market_course_id 가 이 행을 가리키므로 지우지 않는다.
    assertThat(rowCount("SELECT count(*) FROM market_course WHERE id = " + postId)).isEqualTo(1);
  }

  @Test
  @DisplayName("남이 올린 것은 내릴 수 없다")
  void onlyAuthorCanUnpublish() {
    long postId = store.publish(author, courseWith(item(placeA, 60)), "설명").orElseThrow();

    assertThat(store.unpublish(reader, postId)).isFalse();
    assertThat(store.isLive(postId)).isTrue();
  }

  @Test
  @DisplayName("담으면 내 코스가 새로 생기고 원본과 끊겨 있다")
  void saveCopiesIntoOwnCourse() {
    long postId =
        store.publish(author, courseWith(item(placeA, 90), item(placeB, 40)), "설명").orElseThrow();

    long myCourseId = store.save(reader, postId);

    CourseDetail mine = courses.find(reader, myCourseId, Lang.KO).orElseThrow();
    assertThat(mine.getOrigin()).isEqualTo(CourseOrigin.MARKET);
    assertThat(mine.getSourceMarketCourseId()).isEqualTo(postId);
    // 날짜는 묻지 않는다 — 언제 갈지는 담아 가는 사람이 정한다.
    assertThat(mine.getStartDate()).isNull();
    assertThat(mine.getDays().get(0).getItems())
        .extracting(i -> i.getPlaceId() + ":" + i.getDwellMinutes())
        .containsExactly(placeA + ":90", placeB + ":40");

    // 담은 뒤 내 코스를 고쳐도 마켓은 그대로다.
    courses.replace(myCourseId, replace(day(item(placeA, 15))));
    assertThat(store.find(reader, postId, Lang.KO).orElseThrow().getPlaceCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("담기면 담기 수가 오르고 「담김」 표시가 켜진다")
  void saveCountAndFlag() {
    long postId = store.publish(author, courseWith(item(placeA, 60)), "설명").orElseThrow();
    assertThat(store.find(reader, postId, Lang.KO).orElseThrow().getSaved()).isFalse();

    store.save(reader, postId);

    MarketCourseDetail post = store.find(reader, postId, Lang.KO).orElseThrow();
    assertThat(post.getSaveCount()).isEqualTo(1);
    assertThat(post.getSaved()).isTrue();
    assertThat(post.getMine()).isFalse();
    assertThat(store.find(author, postId, Lang.KO).orElseThrow().getMine()).isTrue();
  }

  @Test
  @DisplayName("좋아요는 토글이고 개수가 행과 함께 움직인다")
  void likeIsIdempotentAndCounted() {
    long postId = store.publish(author, courseWith(item(placeA, 60)), "설명").orElseThrow();

    store.like(reader, postId, true);
    store.like(reader, postId, true);

    MarketCourseDetail post = store.find(reader, postId, Lang.KO).orElseThrow();
    assertThat(post.getLikeCount()).isEqualTo(1);
    assertThat(post.getLiked()).isTrue();

    store.like(reader, postId, false);
    store.like(reader, postId, false);

    post = store.find(reader, postId, Lang.KO).orElseThrow();
    assertThat(post.getLikeCount()).isZero();
    assertThat(post.getLiked()).isFalse();
  }

  @Test
  @DisplayName("상세에도 작품 태그가 실린다 — 목록에만 있으면 코스를 여는 순간 사라진다")
  void detailCarriesContentTags() {
    long postId = store.publish(author, courseWith(item(placeA, 60)), "설명").orElseThrow();
    org.junit.jupiter.api.Assumptions.assumeTrue(
        contentTitleOf(placeA) != null, "그 장소에 연결된 작품이 없다");

    assertThat(store.find(reader, postId, Lang.KO).orElseThrow().getContents()).isNotEmpty();
  }

  @Test
  @DisplayName("작품 이름으로 찾는다 — 태그가 올릴 때 굳는다")
  void searchesByContentName() {
    long postId = store.publish(author, courseWith(item(placeA, 60)), "설명").orElseThrow();
    String title = contentTitleOf(placeA);
    org.junit.jupiter.api.Assumptions.assumeTrue(title != null, "그 장소에 연결된 작품이 없다");

    MarketStore.Page hit = store.list(reader, title, MarketSort.SAVES, 20, 0);
    MarketStore.Page miss = store.list(reader, "없을만한작품이름zzz", MarketSort.SAVES, 20, 0);

    assertThat(hit.items()).extracting(i -> i.getId()).contains(postId);
    assertThat(miss.items()).extracting(i -> i.getId()).doesNotContain(postId);
  }

  @Test
  @DisplayName("내려간 것은 목록에 없다")
  void listHidesUnpublished() {
    long postId = store.publish(author, courseWith(item(placeA, 60)), "설명").orElseThrow();
    assertThat(store.list(reader, null, MarketSort.SAVES, 50, 0).items())
        .extracting(i -> i.getId())
        .contains(postId);

    store.unpublish(author, postId);

    assertThat(store.list(reader, null, MarketSort.SAVES, 50, 0).items())
        .extracting(i -> i.getId())
        .doesNotContain(postId);
  }

  @Test
  @DisplayName("원본 코스를 지워도 사본은 남는다")
  void copySurvivesOriginalDeletion() {
    long courseId = courseWith(item(placeA, 60));
    long postId = store.publish(author, courseId, "설명").orElseThrow();

    courses.delete(author, courseId);

    MarketCourseDetail post = store.find(reader, postId, Lang.KO).orElseThrow();
    assertThat(post.getPlaceCount()).isEqualTo(1);
  }

  // ───────────── 거들기 ─────────────

  private long courseWith(CourseItemInput... items) {
    long id = courses.create(author, new CourseCreate(1, CourseOrigin.SELF).title("원본 코스"));
    courses.replace(id, replace(day(items)));
    return id;
  }

  private static CourseReplace replace(CourseDayInput... days) {
    return new CourseReplace("원본 코스", List.of(days));
  }

  private static CourseDayInput day(CourseItemInput... items) {
    return new CourseDayInput(List.of(items));
  }

  private static CourseItemInput item(long placeId, int dwellMinutes) {
    return new CourseItemInput().placeId(placeId).dwellMinutes(dwellMinutes);
  }

  private String contentTitleOf(long placeId) {
    return jdbc.sql(
            """
            SELECT ci.title
            FROM place_content pc
            JOIN content_i18n ci ON ci.content_id = pc.content_id AND ci.lang = 'ko'
            WHERE pc.place_id = :placeId
            LIMIT 1
            """)
        .param("placeId", placeId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private int rowCount(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }
}
