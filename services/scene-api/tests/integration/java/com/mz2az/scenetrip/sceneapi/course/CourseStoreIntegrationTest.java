package com.mz2az.scenetrip.sceneapi.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemSource;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PinCategory;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link CourseStore} 의 SQL 을 진짜 PostgreSQL 에 태운다.
 *
 * <p>이 테스트가 지키는 것은 <b>가짜 Store 로는 영영 알 수 없는 것들</b>이다. 순서를 통째로 다시 매기는 트랜잭션이 {@code DEFERRABLE} 유니크
 * 제약을 실제로 통과하는가, 항목을 지울 때 직접 찍은 핀이 함께 사라지는가, 창 함수가 낸 구간 거리가 일차별로 끊기는가. 전부 SQL 이 한 줄도 돌지 않으면 확인되지
 * 않는다.
 *
 * <p>특히 두 가지는 <b>회의에서 뒤집힌 결정</b>이라 회귀하기 쉽다 — 기간을 줄이면 뒤 일차의 장소가 지워진다는 것과, 항목 id 를 실어 보내면 방문 체크가
 * 살아남는다는 것.
 */
@DisplayName("CourseStore — 실제 DB 질의")
class CourseStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static CourseStore store;
  private static UserStore users;

  private final UUID installUuid = UUID.randomUUID();
  private UUID user;
  private long placeA;
  private long placeB;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store =
        new CourseStore(jdbc, new TravelEstimator(4.0, 1.3), IntegrationDatabase.transactions());
    users = new UserStore(jdbc);
  }

  @BeforeEach
  void createUser() {
    user = users.resolve(installUuid);
    List<Long> places =
        jdbc.sql("SELECT id FROM place ORDER BY id LIMIT 2").query(Long.class).list();
    placeA = places.get(0);
    placeB = places.get(1);
  }

  /** 계정을 지우면 코스·항목·핀이 ON DELETE CASCADE 로 함께 사라진다. 적재 데이터는 건드리지 않는다. */
  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
  }

  @Test
  @DisplayName("만들면 빈 일차가 기간만큼 생긴다 — 행이 아니라 번호로")
  void createsEmptyDays() {
    long id = store.create(user, new CourseCreate(3, CourseOrigin.SELF));

    CourseDetail course = store.find(user, id, Lang.KO).orElseThrow();

    assertThat(course.getDayCount()).isEqualTo(3);
    assertThat(course.getDays()).hasSize(3);
    assertThat(course.getDays()).allSatisfy(d -> assertThat(d.getItems()).isEmpty());
    assertThat(course.getPlaceCount()).isZero();
    // 일차를 담은 행은 하나도 없다. 기간은 day_count 컬럼 하나가 든다.
    assertThat(itemCount(id)).isZero();
  }

  @Test
  @DisplayName("이름을 비우면 서버가 채운다")
  void fillsBlankTitle() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF).title("  "));

    assertThat(store.find(user, id, Lang.KO).orElseThrow().getTitle()).isEqualTo("이름 없는 코스");
  }

  @Test
  @DisplayName("남의 코스는 보이지 않는다")
  void hidesOtherPeoplesCourses() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    UUID stranger = users.resolve(UUID.randomUUID());

    assertThat(store.find(stranger, id, Lang.KO)).isEmpty();
    assertThat(store.exists(stranger, id)).isFalse();

    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", stranger.toString())
        .update();
  }

  @Test
  @DisplayName("기간을 줄이면 뒤 일차의 장소가 지워진다 — MZ2AZ-229 의 결정을 뒤집은 자리")
  void shrinkingDropsTrailingItems() {
    long id = store.create(user, new CourseCreate(3, CourseOrigin.SELF));
    store.replace(id, replace(day(item(placeA, 60)), day(), day(item(placeB, 60))));
    assertThat(itemCount(id)).isEqualTo(2);

    // 2일치만 보낸다. 3일차의 placeB 는 "요청에 없는 항목" 이 되어 사라진다.
    store.replace(id, replace(day(item(placeA, 60)), day()));

    CourseDetail course = store.find(user, id, Lang.KO).orElseThrow();
    assertThat(course.getDayCount()).isEqualTo(2);
    assertThat(course.getDays()).hasSize(2);
    assertThat(itemCount(id)).isEqualTo(1);
  }

  @Test
  @DisplayName("항목 id 를 실어 보내면 방문 체크가 살아남는다")
  void keepsVisitedAtWhenIdIsSent() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    store.replace(id, replace(day(item(placeA, 60))));
    long itemId = onlyItem(id).getId();
    jdbc.sql("UPDATE course_item SET visited_at = now() WHERE id = :id")
        .param("id", itemId)
        .update();

    // id 를 그대로 돌려보낸다 — 체류시간만 고친다.
    store.replace(id, replace(day(item(placeA, 90).id(itemId))));

    CourseItem after = onlyItem(id);
    assertThat(after.getId()).isEqualTo(itemId);
    assertThat(after.getDwellMinutes()).isEqualTo(90);
    assertThat(after.getVisitedAt()).isNotNull();
  }

  @Test
  @DisplayName("항목 id 를 빠뜨리면 새 장소가 되어 방문 체크가 사라진다")
  void losesVisitedAtWhenIdIsOmitted() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    store.replace(id, replace(day(item(placeA, 60))));
    long itemId = onlyItem(id).getId();
    jdbc.sql("UPDATE course_item SET visited_at = now() WHERE id = :id")
        .param("id", itemId)
        .update();

    store.replace(id, replace(day(item(placeA, 60))));

    CourseItem after = onlyItem(id);
    assertThat(after.getId()).isNotEqualTo(itemId);
    assertThat(after.getVisitedAt()).isNull();
  }

  @Test
  @DisplayName("순서를 통째로 뒤집어도 DEFERRABLE 제약이 막지 않는다")
  void reordersWithinOneTransaction() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    store.replace(id, replace(day(item(placeA, 60), item(placeB, 60))));
    List<CourseItem> before =
        store.find(user, id, Lang.KO).orElseThrow().getDays().get(0).getItems();

    // 두 항목의 자리를 맞바꾼다. 중간 상태에서 sort_order 가 겹치는데, 제약이 지연
    // 검사라 트랜잭션 끝에 한 번만 본다.
    store.replace(
        id,
        replace(
            day(
                item(placeB, 60).id(before.get(1).getId()),
                item(placeA, 60).id(before.get(0).getId()))));

    List<CourseItem> after =
        store.find(user, id, Lang.KO).orElseThrow().getDays().get(0).getItems();
    assertThat(after)
        .extracting(CourseItem::getId)
        .containsExactly(before.get(1).getId(), before.get(0).getId());
  }

  @Test
  @DisplayName("직접 찍은 핀은 항목과 함께 만들어지고 함께 사라진다")
  void customPinLivesAndDiesWithItsItem() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF));

    store.replace(id, replace(day(pinItem("호텔 O", 37.55, 126.97))));

    CourseItem pin = onlyItem(id);
    assertThat(pin.getSource()).isEqualTo(CourseItemSource.CUSTOM_PIN);
    assertThat(pin.getPlaceId()).isNull();
    assertThat(pin.getCustomPinId()).isNotNull();
    assertThat(pin.getName()).isEqualTo("호텔 O");
    assertThat(pinCount(id)).isEqualTo(1);

    // 그 항목을 빼면 핀도 거둔다. 핀은 항목이 아니라 코스에 매달려 있어 저절로
    // 사라지지 않는다.
    store.replace(id, replace(day()));

    assertThat(itemCount(id)).isZero();
    assertThat(pinCount(id)).isZero();
  }

  @Test
  @DisplayName("같은 숙소를 두 자리에 넣으면 핀이 둘 생긴다 — 공유하지 않는다")
  void customPinsAreNotShared() {
    long id = store.create(user, new CourseCreate(2, CourseOrigin.SELF));

    store.replace(
        id, replace(day(pinItem("호텔 O", 37.55, 126.97)), day(pinItem("호텔 O", 37.55, 126.97))));

    assertThat(pinCount(id)).isEqualTo(2);
  }

  @Test
  @DisplayName("구간 거리는 일차 안에서만 이어진다 — 첫 장소에는 없다")
  void distanceRestartsEachDay() {
    long id = store.create(user, new CourseCreate(2, CourseOrigin.SELF));

    store.replace(id, replace(day(item(placeA, 60), item(placeB, 60)), day(item(placeA, 60))));

    CourseDetail course = store.find(user, id, Lang.KO).orElseThrow();
    List<CourseItem> firstDay = course.getDays().get(0).getItems();
    assertThat(firstDay.get(0).getDistanceMetersFromPrevious()).isNull();
    assertThat(firstDay.get(1).getDistanceMetersFromPrevious()).isNotNull();
    // 2일차의 첫 장소는 앞 일차의 마지막과 이어지지 않는다.
    assertThat(course.getDays().get(1).getItems().get(0).getDistanceMetersFromPrevious()).isNull();
  }

  @Test
  @DisplayName("하루 소요 시간 = 머무는 시간 + 이동시간. 도착·출발 시각은 없다")
  void dayTotalIsDwellPlusTravel() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    store.replace(id, replace(day(item(placeA, 90), item(placeB, 40))));

    var day = store.find(user, id, Lang.KO).orElseThrow().getDays().get(0);

    assertThat(day.getDwellMinutes()).isEqualTo(130);
    assertThat(day.getTravelMinutes()).isPositive();
    assertThat(day.getTotalMinutes()).isEqualTo(day.getDwellMinutes() + day.getTravelMinutes());
  }

  @Test
  @DisplayName("남의 항목 id 를 넣으면 거부한다")
  void rejectsForeignItemId() {
    long mine = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    long other = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    store.replace(other, replace(day(item(placeA, 60))));
    long otherItemId = onlyItem(other).getId();

    assertThatThrownBy(() -> store.replace(mine, replace(day(item(placeA, 60).id(otherItemId)))))
        .isInstanceOf(CourseStore.UnknownItemException.class);
  }

  @Test
  @DisplayName("코스를 지우면 항목과 핀이 함께 사라진다")
  void deleteCascades() {
    long id = store.create(user, new CourseCreate(1, CourseOrigin.SELF));
    store.replace(id, replace(day(item(placeA, 60), pinItem("호텔 O", 37.55, 126.97))));

    assertThat(store.delete(user, id)).isTrue();

    assertThat(itemCount(id)).isZero();
    assertThat(pinCount(id)).isZero();
    assertThat(store.find(user, id, Lang.KO)).isEmpty();
  }

  // ───────────── 거들기 ─────────────

  private static CourseReplace replace(CourseDayInput... days) {
    return new CourseReplace("테스트 코스", List.of(days));
  }

  private static CourseDayInput day(CourseItemInput... items) {
    return new CourseDayInput(List.of(items));
  }

  private static CourseItemInput item(long placeId, int dwellMinutes) {
    return new CourseItemInput(dwellMinutes).placeId(placeId);
  }

  private static CourseItemInput pinItem(String name, double lat, double lng) {
    return new CourseItemInput(60)
        .customPin(new CustomPinInput(name, PinCategory.LODGING, lat, lng));
  }

  private CourseItem onlyItem(long courseId) {
    List<CourseItem> items =
        store.find(user, courseId, Lang.KO).orElseThrow().getDays().stream()
            .flatMap(d -> d.getItems().stream())
            .toList();
    assertThat(items).hasSize(1);
    return items.get(0);
  }

  private int itemCount(long courseId) {
    return jdbc.sql("SELECT count(*) FROM course_item WHERE course_id = :id")
        .param("id", courseId)
        .query(Integer.class)
        .single();
  }

  private int pinCount(long courseId) {
    return jdbc.sql("SELECT count(*) FROM custom_pin WHERE course_id = :id")
        .param("id", courseId)
        .query(Integer.class)
        .single();
  }
}
