package com.mz2az.scenetrip.sceneapi.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PinCategory;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.post.PostStore.Detail;
import com.mz2az.scenetrip.sceneapi.post.PostStore.Page;
import com.mz2az.scenetrip.sceneapi.post.PostStore.Stop;
import com.mz2az.scenetrip.sceneapi.post.PostStore.Summary;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link PostStore} 의 SQL 을 실제 PostgreSQL 에 태운다 — 계획 {@code community-post.md}, 계약 scene-api 1.10.0
 * tag {@code posts}, 마이그레이션 V27.
 *
 * <p>명세에서 나온 기대: 코스는 <b>글 쓴 순간의 사본</b>(촬영지·편의시설 항목만, 핀은 빠진다, 일차·순서·체류 그대로)이고 원본을 고치거나 지워도 그대로다. 목록은
 * 최신순, 본문 앞부분 120 자(공백은 하나로). 운영자가 내린 글은 목록·상세·담기 어디에도 없다. 탈퇴하면 글은 남고 글쓴이만 끊긴다. 지우기는 내 글만. 담기는
 * {@code origin = market} 인 새 코스, 지금 촬영지와 연결된 편의시설은 촬영지로 담긴다.
 *
 * <p>픽스처는 바다 위(위도 34.93 · 경도 129.61)에 두고 전부 트랜잭션 하나 안에서 돌린 뒤 되돌린다.
 */
@DisplayName("PostStore — 여행후기 실제 DB 질의 (V27)")
class PostStoreIntegrationTest {

  private static final double LAT = 34.93;
  private static final double LNG = 129.61;

  private static JdbcClient jdbc;
  private static PostStore store;
  private static CourseStore courses;
  private static UserStore users;
  private static AccountLinkStore links;
  private static UploadStore uploads;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private TransactionStatus tx;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    courses =
        new CourseStore(
            jdbc, DeployedTravel.estimator(), dwell, IntegrationDatabase.transactions());
    store = new PostStore(jdbc, IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
    uploads = new UploadStore(jdbc);
  }

  // ───────────── 쓰기 — 코스 사본 ─────────────

  @Test
  @DisplayName("쓰기 — 촬영지·편의시설 항목만 일차·순서·체류 그대로 떠 두고, 직접 찍은 핀은 빠진다")
  void createCopiesPlaceAndPoiItemsOnly() {
    rolledBack(
        () -> {
          UUID me = member();
          long a = place("a", "가 촬영지", "A Place", null);
          long b = place("b", "나 촬영지", "B Place", null);
          long q = poi("q", "다식당", null, "음식점");
          long course = course(me, 3);
          courses.replace(
              course,
              replace(
                  day(placeItem(a, 90), pin("숙소 1"), poiItem(q, 30)),
                  day(pin("숙소 2")),
                  day(placeItem(b, 45))));

          long postId = store.create(me, "제주 3일", "좋았다", List.of(), List.of(), course);

          // 원본 코스의 핀이 아닌 항목과 사본이 칸마다 같다.
          List<String> expected =
              jdbc.sql(
                      "SELECT concat_ws(':', day_no, sort_order, place_id, poi_id, dwell_min)"
                          + " FROM course_item WHERE course_id = :c"
                          + " AND (place_id IS NOT NULL OR poi_id IS NOT NULL)"
                          + " ORDER BY day_no, sort_order")
                  .param("c", course)
                  .query(String.class)
                  .list();
          assertThat(postItems(postId)).hasSize(3).containsExactlyElementsOf(expected);
          assertThat(postItems(postId))
              .extracting(s -> s.split(":")[0])
              .containsExactly("1", "1", "3");
          int pins =
              jdbc.sql(
                      "SELECT count(*) FROM course_item WHERE course_id = :c"
                          + " AND place_id IS NULL AND poi_id IS NULL")
                  .param("c", course)
                  .query(Integer.class)
                  .single();
          assertThat(pins).as("픽스처에 핀이 둘 있어야 시험에 뜻이 있다").isEqualTo(2);

          Summary head = store.find(postId, me).orElseThrow().head();
          assertThat(head.courseTitle()).isEqualTo(courseTitle(course));
          assertThat(head.courseDayCount()).isEqualTo(3);
          assertThat(head.placeCount()).isEqualTo(3);

          assertThat(store.stops(postId, "ko"))
              .extracting(
                  s -> s.dayNo() + ":" + s.placeId() + ":" + s.poiId() + ":" + s.dwellMinutes())
              .containsExactly("1:" + a + ":null:90", "1:null:" + q + ":30", "3:" + b + ":null:45");
          return null;
        });
  }

  @Test
  @DisplayName("쓰기 — 사본은 원본 코스를 고치거나 지워도 그대로다")
  void snapshotSurvivesCourseEditAndDeletion() {
    rolledBack(
        () -> {
          UUID me = member();
          long a = place("a", "가 촬영지", "A Place", null);
          long b = place("b", "나 촬영지", "B Place", null);
          long course = course(me, 2);
          courses.replace(course, replace(day(placeItem(a, 90)), day(placeItem(b, 40))));
          long postId = store.create(me, "제목", "본문", List.of(), List.of(), course);
          List<String> before = postItems(postId);
          String title = courseTitle(course);

          courses.replace(course, new CourseReplace("바꾼 이름", List.of(day(placeItem(b, 15)))));
          assertThat(postItems(postId)).isEqualTo(before);
          assertThat(store.find(postId, me).orElseThrow().head().courseTitle()).isEqualTo(title);
          assertThat(store.find(postId, me).orElseThrow().head().courseDayCount()).isEqualTo(2);

          jdbc.sql("DELETE FROM course WHERE id = :c").param("c", course).update();
          assertThat(postItems(postId)).isEqualTo(before);
          assertThat(store.stops(postId, "en"))
              .extracting(Stop::placeId, Stop::dwellMinutes)
              .containsExactly(
                  org.assertj.core.groups.Tuple.tuple(a, 90),
                  org.assertj.core.groups.Tuple.tuple(b, 40));
          Summary head = store.find(postId, null).orElseThrow().head();
          assertThat(head.courseTitle()).isEqualTo(title);
          assertThat(head.placeCount()).isEqualTo(2);
          return null;
        });
  }

  @Test
  @DisplayName("쓰기 — 남의 코스·없는 코스는 CourseRejectedException 이고 글이 생기지 않는다")
  void rejectsOthersCourse() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID other = member();
          long theirs = course(other, 1);
          long before = count("SELECT count(*) FROM community_post");

          assertThatThrownBy(() -> store.create(me, "제목", "본문", List.of(), List.of(), theirs))
              .isInstanceOf(PostStore.CourseRejectedException.class);
          assertThatThrownBy(
                  () -> store.create(me, "제목", "본문", List.of(), List.of(), Long.MAX_VALUE))
              .isInstanceOf(PostStore.CourseRejectedException.class);
          assertThat(count("SELECT count(*) FROM community_post")).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("쓰기 — 사진은 보낸 순서대로 0.. 으로 붙고 첫 장이 대표, 막 옮긴 키가 아니거나 겹치면 거절")
  void photosInOrder() {
    rolledBack(
        () -> {
          UUID me = member();
          List<String> keys = List.of(key("c"), key("a"), key("b"));
          long postId = store.create(me, "제목", "본문", keys, Set.copyOf(keys), null);

          Detail d = store.find(postId, me).orElseThrow();
          assertThat(d.photoKeys()).containsExactlyElementsOf(keys);
          assertThat(d.head().coverKey()).isEqualTo(keys.get(0));
          assertThat(d.head().photoCount()).isEqualTo(3);
          assertThat(d.head().courseTitle()).isNull();
          assertThat(d.head().courseDayCount()).isNull();
          assertThat(d.head().placeCount()).isZero();
          assertThat(
                  jdbc.sql(
                          "SELECT sort_order FROM community_post_photo WHERE post_id = :p"
                              + " ORDER BY sort_order")
                      .param("p", postId)
                      .query(Integer.class)
                      .list())
              .containsExactly(0, 1, 2);

          String stray = key("x");
          assertThatThrownBy(
                  () -> store.create(me, "제목", "본문", List.of(stray), List.of(key("y")), null))
              .isInstanceOf(PostStore.PhotoKeyRejectedException.class);
          String dup = key("d");
          assertThatThrownBy(
                  () -> store.create(me, "제목", "본문", List.of(dup, dup), List.of(dup), null))
              .isInstanceOf(PostStore.PhotoKeyRejectedException.class);
          return null;
        });
  }

  // ───────────── 목록 ─────────────

  @Test
  @DisplayName("목록 — 최신순, 전체 수, 본문 앞부분은 공백을 하나로 접은 120 자까지, 사진·장소 수, 글쓴이 거르기, isMine")
  void listNewestFirstWithExcerptAndCounts() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID other = member();
          long a = place("a", "가 촬영지", "A Place", null);
          long course = course(me, 1);
          courses.replace(course, replace(day(placeItem(a, 60), pin("숙소"))));

          String longBody = "첫 줄\n\n  둘째   줄\t셋째\r\n" + "가나다라마바사아자차카타파하 ".repeat(15) + "끝";
          long baseline = store.list(null, null, 1, 0).total();

          long p1 = store.create(me, "첫 글", "짧은 본문", List.of(), List.of(), null);
          List<String> keys = List.of(key("1"), key("2"));
          long p2 = store.create(me, "둘째 글", longBody, keys, keys, course);
          long p3 = store.create(other, "남의 글", "남", List.of(), List.of(), null);
          // 한 트랜잭션 안은 now() 가 같다 — 시각을 벌려 둔다(먼 미래라 적재 데이터보다 앞선다).
          setCreated(p1, "2999-01-01T00:00:00Z");
          setCreated(p2, "2999-01-03T00:00:00Z");
          setCreated(p3, "2999-01-02T00:00:00Z");

          Page<Summary> all = store.list(me, null, 3, 0);
          assertThat(all.total()).isEqualTo(baseline + 3);
          assertThat(all.items()).extracting(Summary::id).containsExactly(p2, p3, p1);
          assertThat(all.items()).extracting(Summary::mine).containsExactly(true, false, true);

          Summary s2 = all.items().get(0);
          String collapsed = longBody.replaceAll("\\s+", " ");
          assertThat(s2.excerpt()).hasSize(120).isEqualTo(collapsed.substring(0, 120));
          assertThat(s2.excerpt()).doesNotContain("\n", "\t", "\r", "  ");
          assertThat(s2.title()).isEqualTo("둘째 글");
          assertThat(s2.nickname()).isEqualTo(nickname(me));
          assertThat(s2.createdAt()).isNotNull();
          assertThat(s2.photoCount()).isEqualTo(2);
          assertThat(s2.coverKey()).isEqualTo(keys.get(0));
          assertThat(s2.courseTitle()).isEqualTo(courseTitle(course));
          assertThat(s2.courseDayCount()).isEqualTo(1);
          assertThat(s2.placeCount()).as("핀은 세지 않는다").isEqualTo(1);
          Summary s1 = all.items().get(2);
          assertThat(s1.excerpt()).isEqualTo("짧은 본문");
          assertThat(s1.coverKey()).isNull();
          assertThat(s1.photoCount()).isZero();
          assertThat(s1.courseTitle()).isNull();

          // 페이지
          assertThat(store.list(me, null, 1, 1).items())
              .extracting(Summary::id)
              .containsExactly(p3);

          // 보는 사람이 없으면 isMine 은 모두 false
          assertThat(store.list(null, null, 3, 0).items())
              .extracting(Summary::mine)
              .containsOnly(false);

          // 글쓴이 거르기 — 내 글만, 전체 수도 내 글만
          Page<Summary> mine = store.list(me, me, 20, 0);
          assertThat(mine.total()).isEqualTo(2);
          assertThat(mine.items()).extracting(Summary::id).containsExactly(p2, p1);
          Page<Summary> theirs = store.list(me, other, 20, 0);
          assertThat(theirs.items()).extracting(Summary::id).containsExactly(p3);
          assertThat(theirs.items()).extracting(Summary::mine).containsExactly(false);
          return null;
        });
  }

  @Test
  @DisplayName("상세 — 본문 전체(공백 그대로), 사진 키 순서, isMine 은 보는 사람 기준")
  void findDetail() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID other = member();
          String body = "줄 하나\n\n줄  둘";
          long postId = store.create(me, "제목", body, List.of(), List.of(), null);

          Detail d = store.find(postId, me).orElseThrow();
          assertThat(d.body()).isEqualTo(body);
          assertThat(d.head().title()).isEqualTo("제목");
          assertThat(d.head().mine()).isTrue();
          assertThat(d.head().nickname()).isEqualTo(nickname(me));
          assertThat(store.find(postId, other).orElseThrow().head().mine()).isFalse();
          assertThat(store.find(postId, null).orElseThrow().head().mine()).isFalse();
          assertThat(store.find(Long.MAX_VALUE, me)).isEmpty();
          return null;
        });
  }

  // ───────────── 운영자 내리기 · 탈퇴 · 지우기 ─────────────

  @Test
  @DisplayName("운영자가 내린 글은 목록·전체 수·상세·담기 어디에도 없다")
  void removedPostIsHiddenEverywhere() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID reader = member();
          long a = place("a", "가 촬영지", "A Place", null);
          long course = course(me, 1);
          courses.replace(course, replace(day(placeItem(a, 60))));
          long postId = store.create(me, "제목", "본문", List.of(), List.of(), course);
          long totalBefore = store.list(null, null, 1, 0).total();
          int myTotalBefore = store.list(me, me, 20, 0).total();

          jdbc.sql("UPDATE community_post SET removed_at = now() WHERE id = :p")
              .param("p", postId)
              .update();

          assertThat(store.find(postId, me)).isEmpty();
          assertThat(store.list(me, me, 20, 0).items())
              .extracting(Summary::id)
              .doesNotContain(postId);
          assertThat(store.list(me, me, 20, 0).total()).isEqualTo(myTotalBefore - 1);
          assertThat(store.list(null, null, 1, 0).total()).isEqualTo(totalBefore - 1);
          long coursesBefore = coursesOf(reader);
          assertThat(store.save(reader, postId)).isEmpty();
          assertThat(coursesOf(reader)).isEqualTo(coursesBefore);
          return null;
        });
  }

  @Test
  @DisplayName("탈퇴 — 글은 남고 글쓴이만 끊긴다: nickname null, 아무에게도 isMine 이 아니다")
  void withdrawnAuthorKeepsPost() {
    rolledBack(
        () -> {
          UUID leaver = member();
          List<String> keys = List.of(key("w"));
          long postId = store.create(leaver, "남는 글", "본문", keys, keys, null);
          setCreated(postId, "2999-02-01T00:00:00Z");

          assertThat(users.delete(leaver)).isTrue();

          Detail d = store.find(postId, null).orElseThrow();
          assertThat(d.head().nickname()).isNull();
          assertThat(d.head().mine()).isFalse();
          assertThat(d.body()).isEqualTo("본문");
          assertThat(d.photoKeys()).containsExactlyElementsOf(keys);
          Summary top = store.list(null, null, 1, 0).items().get(0);
          assertThat(top.id()).isEqualTo(postId);
          assertThat(top.nickname()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("지우기 — 내 글만. 남의 글·없는 글은 false 이고 그대로, 내 글은 사진·사본 행까지 사라진다")
  void deleteOnlyOwn() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID other = member();
          long a = place("a", "가 촬영지", "A Place", null);
          long course = course(me, 1);
          courses.replace(course, replace(day(placeItem(a, 60))));
          List<String> keys = List.of(key("del"));
          long postId = store.create(me, "제목", "본문", keys, keys, course);

          assertThat(store.delete(postId, other)).isFalse();
          assertThat(store.find(postId, null)).isPresent();
          assertThat(store.delete(Long.MAX_VALUE, me)).isFalse();

          assertThat(store.delete(postId, me)).isTrue();
          assertThat(store.find(postId, me)).isEmpty();
          assertThat(count("SELECT count(*) FROM community_post_photo WHERE post_id = " + postId))
              .isZero();
          assertThat(count("SELECT count(*) FROM community_post_item WHERE post_id = " + postId))
              .isZero();
          assertThat(store.delete(postId, me)).isFalse();
          return null;
        });
  }

  // ───────────── 담기 ─────────────

  @Test
  @DisplayName("담기 — origin market 인 새 내 코스, 이름·일수·항목(일차·순서·체류) 그대로, 날짜 없음. 핀만 있던 일차는 빈 일차")
  void saveCreatesMarketCourse() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID reader = member();
          long a = place("a", "가 촬영지", "A Place", null);
          long b = place("b", "나 촬영지", "B Place", null);
          long q = poi("q", "다식당", null, "음식점");
          long course = course(me, 3);
          courses.replace(
              course,
              replace(
                  day(placeItem(a, 90), poiItem(q, 30)), day(pin("숙소")), day(placeItem(b, 45))));
          long postId = store.create(me, "제목", "본문", List.of(), List.of(), course);

          long first = store.save(reader, postId).orElseThrow();
          long second = store.save(reader, postId).orElseThrow();
          assertThat(second).as("담을 때마다 새 코스").isNotEqualTo(first);

          CourseDetail mine = courses.find(reader, first, Lang.KO).orElseThrow();
          assertThat(mine.getOrigin()).isEqualTo(CourseOrigin.MARKET);
          assertThat(mine.getTitle()).isEqualTo(courseTitle(course));
          assertThat(mine.getDayCount()).isEqualTo(3);
          assertThat(mine.getStartDate()).isNull();
          assertThat(mine.getDays()).hasSize(3);
          assertThat(mine.getDays().get(0).getItems())
              .extracting(i -> i.getPlaceId() + ":" + i.getPoiId() + ":" + i.getDwellMinutes())
              .containsExactly(a + ":null:90", "null:" + q + ":30");
          assertThat(mine.getDays().get(1).getItems()).isEmpty();
          assertThat(mine.getDays().get(2).getItems())
              .extracting(CourseItem::getPlaceId, CourseItem::getDwellMinutes)
              .containsExactly(org.assertj.core.groups.Tuple.tuple(b, 45));
          // 글쓴이의 코스와 끊겨 있다 — 원본은 그대로.
          assertThat(courses.find(me, first, Lang.KO)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("담기 — 코스가 붙지 않은 글·없는 글은 비어 있고 코스가 생기지 않는다")
  void saveWithoutCourse() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID reader = member();
          long postId = store.create(me, "제목", "본문", List.of(), List.of(), null);
          long before = coursesOf(reader);

          assertThat(store.save(reader, postId)).isEmpty();
          assertThat(store.save(reader, Long.MAX_VALUE)).isEmpty();
          assertThat(coursesOf(reader)).isEqualTo(before);
          return null;
        });
  }

  @Test
  @DisplayName("담기 — 사본의 편의시설이 지금 보이는 촬영지와 연결돼 있으면 촬영지 항목으로, 숨긴 촬영지와 연결이면 편의시설 그대로")
  void saveConvertsLinkedPoiToPlace() {
    rolledBack(
        () -> {
          UUID me = member();
          UUID reader = member();
          long visible = place("vis", "보이는 촬영지", "Visible", null);
          long hidden = place("hid", "숨긴 촬영지", "Hidden", null);
          long linked = poi("lnk", "연결식당", null, "음식점");
          long toHidden = poi("toh", "숨김연결식당", null, "음식점");
          long plain = poi("pln", "그냥식당", null, "음식점");
          long course = course(me, 1);
          courses.replace(
              course, replace(day(poiItem(linked, 50), poiItem(toHidden, 20), poiItem(plain, 15))));
          long postId = store.create(me, "제목", "본문", List.of(), List.of(), course);

          // 글을 쓴 뒤에 연결이 생겼다.
          link(linked, visible);
          link(toHidden, hidden);
          jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :p").param("p", hidden).update();

          long saved = store.save(reader, postId).orElseThrow();
          List<String> rows =
              jdbc.sql(
                      "SELECT concat_ws(':', sort_order, coalesce(place_id::text, '-'),"
                          + " coalesce(poi_id::text, '-'), dwell_min)"
                          + " FROM course_item WHERE course_id = :c ORDER BY day_no, sort_order")
                  .param("c", saved)
                  .query(String.class)
                  .list();
          List<String> orders = postItems(postId).stream().map(s -> s.split(":")[1]).toList();
          assertThat(rows)
              .containsExactly(
                  orders.get(0) + ":" + visible + ":-:50",
                  orders.get(1) + ":-:" + toHidden + ":20",
                  orders.get(2) + ":-:" + plain + ":15");
          return null;
        });
  }

  // ───────────── 사본의 장소 표시 ─────────────

  @Test
  @DisplayName("장소 — 촬영지 이름은 요청 언어(없으면 en), 편의시설은 한국어 이름 + 표시말(언어별), 좌표")
  void stopsLanguageRules() {
    rolledBack(
        () -> {
          UUID me = member();
          long p = place("lang", "한국 촬영지", "English Place", "日本ロケ地");
          long q = poi("lang", "가나식당", "Gana Sikdang", "음식점-" + tag);
          poiName(q, "en", "Gana Restaurant", "1 Gana-ro");
          poiName(q, "ja", "ガナ食堂", null);
          category("음식점-" + tag, "en", "Restaurant");
          category("음식점-" + tag, "ja", "飲食店");
          long course = course(me, 1);
          courses.replace(course, replace(day(placeItem(p, 60), poiItem(q, 40))));
          long postId = store.create(me, "제목", "본문", List.of(), List.of(), course);

          List<Stop> ja = store.stops(postId, "ja");
          assertThat(ja).hasSize(2);
          Stop place = ja.get(0);
          assertThat(place.placeId()).isEqualTo(p);
          assertThat(place.poiId()).isNull();
          assertThat(place.name()).isEqualTo("日本ロケ地");
          assertThat(place.latitude()).isCloseTo(LAT, org.assertj.core.data.Offset.offset(1e-6));
          assertThat(place.longitude()).isGreaterThan(LNG - 0.01).isLessThan(LNG + 0.01);
          assertThat(place.displayName()).isNull();
          assertThat(place.nameRoman()).isNull();
          assertThat(place.categoryLabel()).isNull();
          Stop poi = ja.get(1);
          assertThat(poi.placeId()).isNull();
          assertThat(poi.poiId()).isEqualTo(q);
          assertThat(poi.name()).isEqualTo("가나식당");
          assertThat(poi.displayName()).isEqualTo("ガナ食堂");
          assertThat(poi.displayAddress()).as("ja 주소 없음 → en").isEqualTo("1 Gana-ro");
          assertThat(poi.nameRoman()).isEqualTo("Gana Sikdang");
          assertThat(poi.category()).isEqualTo("음식점-" + tag);
          assertThat(poi.categoryLabel()).isEqualTo("飲食店");
          assertThat(poi.dwellMinutes()).isEqualTo(40);

          assertThat(store.stops(postId, "en").get(0).name()).isEqualTo("English Place");
          assertThat(store.stops(postId, "en").get(1).displayName()).isEqualTo("Gana Restaurant");
          assertThat(store.stops(postId, "en").get(1).categoryLabel()).isEqualTo("Restaurant");
          assertThat(store.stops(postId, "zh-Hant").get(0).name())
              .as("번역 없는 언어 → en")
              .isEqualTo("English Place");

          List<Stop> ko = store.stops(postId, "ko");
          assertThat(ko.get(0).name()).isEqualTo("한국 촬영지");
          assertThat(ko.get(1).name()).isEqualTo("가나식당");
          assertThat(ko.get(1).displayName()).isNull();
          assertThat(ko.get(1).displayAddress()).isNull();
          assertThat(ko.get(1).categoryLabel()).isEqualTo("음식점-" + tag);
          assertThat(ko.get(1).nameRoman()).isEqualTo("Gana Sikdang");
          return null;
        });
  }

  // ───────────── V27 제약 ─────────────

  @Test
  @DisplayName("V27 — 제목 1~100 자, 본문 1~5,000 자")
  void titleAndBodyLength() {
    rolledBack(
        () -> {
          UUID me = member();
          insertPost(me, "가".repeat(100), "나".repeat(5000)); // 경계는 된다
          violates(() -> insertPost(me, "", "본문"));
          violates(() -> insertPost(me, "가".repeat(101), "본문"));
          violates(() -> insertPost(me, "제목", ""));
          violates(() -> insertPost(me, "제목", "나".repeat(5001)));
          return null;
        });
  }

  @Test
  @DisplayName("V27 — 사진은 순서 0~7 의 8 장까지, 같은 순서·같은 키는 두 번 없다")
  void photoConstraints() {
    rolledBack(
        () -> {
          UUID me = member();
          long postId = insertPost(me, "제목", "본문");
          for (int i = 0; i < 8; i++) {
            insertPhoto(postId, key("p" + i), i);
          }
          violates(() -> insertPhoto(postId, key("p8"), 8));
          violates(() -> insertPhoto(postId, key("neg"), -1));
          long other = insertPost(me, "제목", "본문");
          violates(() -> insertPhoto(other, key("p0"), 0)); // 키 유일
          violates(
              () -> {
                insertPhoto(other, key("o1"), 1);
                insertPhoto(other, key("o2"), 1); // 같은 순서
              });
          return null;
        });
  }

  @Test
  @DisplayName("V27 — 사본 항목은 촬영지·편의시설 중 정확히 하나")
  void itemTargetExactlyOne() {
    rolledBack(
        () -> {
          UUID me = member();
          long p = place("ck", "제약 촬영지", "Check", null);
          long q = poi("ck", "제약식당", null, "음식점");
          long postId = insertPost(me, "제목", "본문");
          insertItem(postId, 1, p, null);
          insertItem(postId, 2, null, q);
          violates(() -> insertItem(postId, 3, p, q));
          violates(() -> insertItem(postId, 4, null, null));
          return null;
        });
  }

  @Test
  @DisplayName("V27 — 사진 올리기 목적에 post 가 더해졌다(review 도 그대로), 그 밖은 거절")
  void uploadPurposeAcceptsPost() {
    rolledBack(
        () -> {
          UUID me = member();
          String postKey = "uploads/tmp/it-post-" + tag + ".jpg";
          uploads.record(me, postKey, "post", "image/jpeg", 100);
          uploads.record(me, "uploads/tmp/it-review-" + tag + ".jpg", "review", "image/jpeg", 100);
          assertThat(uploads.unattached(me, List.of(postKey))).containsExactly(postKey);
          violates(
              () ->
                  uploads.record(
                      me, "uploads/tmp/it-other-" + tag + ".jpg", "other", "image/jpeg", 100));
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  private void rolledBack(java.util.function.Supplier<Void> work) {
    IntegrationDatabase.transactions()
        .execute(
            status -> {
              status.setRollbackOnly();
              tx = status;
              try {
                return work.get();
              } finally {
                tx = null;
              }
            });
  }

  /** 제약 위반을 저장점 안에서 확인한다 — 위반 뒤에도 같은 트랜잭션으로 이어 가도록 저장점으로 되돌린다. */
  private void violates(Runnable statement) {
    Object savepoint = tx.createSavepoint();
    try {
      assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
    } finally {
      tx.rollbackToSavepoint(savepoint);
    }
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    links.register(id, "google", "it-post-" + UUID.randomUUID(), null, null);
    return id;
  }

  private static String nickname(UUID user) {
    return jdbc.sql("SELECT nickname FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .query(String.class)
        .single();
  }

  private String key(String name) {
    return "posts/it-" + tag + "-" + name + ".jpg";
  }

  private long course(UUID user, int days) {
    return courses.create(user, new CourseCreate(days, CourseOrigin.SELF));
  }

  private static String courseTitle(long course) {
    return jdbc.sql("SELECT title FROM course WHERE id = :c")
        .param("c", course)
        .query(String.class)
        .single();
  }

  private static long coursesOf(UUID user) {
    return jdbc.sql("SELECT count(*) FROM course WHERE user_id = CAST(:u AS UUID)")
        .param("u", user.toString())
        .query(Long.class)
        .single();
  }

  private static CourseReplace replace(CourseDayInput... days) {
    return new CourseReplace("후기 시험 코스", Arrays.asList(days));
  }

  private static CourseDayInput day(CourseItemInput... items) {
    return new CourseDayInput(List.of(items));
  }

  private static CourseItemInput placeItem(long placeId, int dwell) {
    return new CourseItemInput().placeId(placeId).dwellMinutes(dwell);
  }

  private static CourseItemInput poiItem(long poiId, int dwell) {
    return new CourseItemInput().poiId(poiId).dwellMinutes(dwell);
  }

  private static CourseItemInput pin(String name) {
    return new CourseItemInput()
        .dwellMinutes(60)
        .customPin(new CustomPinInput(name, PinCategory.LODGING, LAT + 0.01, LNG + 0.01));
  }

  private static List<String> postItems(long postId) {
    return jdbc.sql(
            "SELECT concat_ws(':', day_no, sort_order, place_id, poi_id, dwell_min)"
                + " FROM community_post_item WHERE post_id = :p ORDER BY day_no, sort_order")
        .param("p", postId)
        .query(String.class)
        .list();
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  private static void setCreated(long postId, String at) {
    jdbc.sql("UPDATE community_post SET created_at = CAST(:at AS TIMESTAMPTZ) WHERE id = :p")
        .param("at", at)
        .param("p", postId)
        .update();
  }

  private long place(String key, String ko, String en, String ja) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key) VALUES ('시험촬영지',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :k) RETURNING id")
            .param("lng", LNG + Math.abs(key.hashCode() % 1000) / 1_000_000.0)
            .param("lat", LAT)
            .param("k", "it-post-" + tag + "-" + key)
            .query(Long.class)
            .single();
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

  private long poi(String key, String name, String roman, String category) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, name_roman, geom, category, category_group, address)
            VALUES (:s, :n, :r, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :c, 'food', '부산 앞바다')
            RETURNING id
            """)
        .param("s", "it-post-" + tag + "-" + key)
        .param("n", name)
        .param("r", roman)
        .param("lng", LNG + 0.002)
        .param("lat", LAT)
        .param("c", category)
        .query(Long.class)
        .single();
  }

  private static void poiName(long poiId, String lang, String name, String address) {
    jdbc.sql(
            "INSERT INTO poi_i18n (poi_id, lang, name, address) VALUES (:id, CAST(:lang AS"
                + " lang_code), :name, :addr)")
        .param("id", poiId)
        .param("lang", lang)
        .param("name", name)
        .param("addr", address)
        .update();
  }

  private static void category(String ko, String lang, String name) {
    jdbc.sql(
            "INSERT INTO poi_category_i18n (ko, lang, name) VALUES (:ko, CAST(:lang AS lang_code),"
                + " :name)")
        .param("ko", ko)
        .param("lang", lang)
        .param("name", name)
        .update();
  }

  private static void link(long poiId, long placeId) {
    jdbc.sql("INSERT INTO place_poi_link (poi_id, place_id, method) VALUES (:q, :p, 'manual')")
        .param("q", poiId)
        .param("p", placeId)
        .update();
  }

  private static long insertPost(UUID user, String title, String body) {
    return jdbc.sql(
            "INSERT INTO community_post (user_id, title, body) VALUES (CAST(:u AS UUID), :t, :b)"
                + " RETURNING id")
        .param("u", user.toString())
        .param("t", title)
        .param("b", body)
        .query(Long.class)
        .single();
  }

  private static void insertPhoto(long postId, String key, int order) {
    jdbc.sql(
            "INSERT INTO community_post_photo (post_id, storage_key, sort_order)"
                + " VALUES (:p, :k, :o)")
        .param("p", postId)
        .param("k", key)
        .param("o", order)
        .update();
  }

  private static void insertItem(long postId, int order, Long placeId, Long poiId) {
    jdbc.sql(
            "INSERT INTO community_post_item (post_id, day_no, sort_order, place_id, poi_id,"
                + " dwell_min) VALUES (:p, 1, :o, CAST(:pl AS BIGINT), CAST(:q AS BIGINT), 30)")
        .param("p", postId)
        .param("o", order)
        .param("pl", placeId)
        .param("q", poiId)
        .update();
  }
}
