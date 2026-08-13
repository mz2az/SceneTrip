package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.CoursesApi;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseList;
import com.mz2az.scenetrip.sceneapi.api.model.CourseProgress;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.CourseStatus;
import com.mz2az.scenetrip.sceneapi.api.model.CourseSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 코스 — 만들기·읽기·편집 완료·삭제, 그리고 여행 중 진행.
 *
 * <p><b>편집은 완료를 누를 때 한 번이다.</b> 제목·기간·장소·순서·체류시간을 고치는 동안 서버로는 아무것도 나가지 않고, {@code PUT
 * /courses/&#123;courseId&#125;} 가 최종 모습을 통째로 받는다. 되돌리기가 공짜가 되고 반쪽만 반영되는 상태가 없다.
 *
 * <p><b>여행 중 동작만 즉시다.</b> 코스를 시작하거나 일차를 넘기는 것은 편집이 아니라서, 그때 코스 전체를 보내라고 할 수 없다.
 *
 * <p>주체는 헤더의 설치 UUID 가 아니라 그것이 가리키는 계정이다 — {@link UserStore#resolve} 가 잇는다.
 */
@RestController
class CourseController implements CoursesApi {

  private final CourseStore store;
  private final UserStore users;

  CourseController(CourseStore store, UserStore users) {
    this.store = store;
    this.users = users;
  }

  @Override
  public ResponseEntity<CourseList> listCourses(UUID xDeviceId, Lang acceptLanguage) {
    List<CourseSummary> items = store.list(users.resolve(xDeviceId));
    return ResponseEntity.ok(new CourseList(items, items.size()));
  }

  @Override
  public ResponseEntity<CourseDetail> createCourse(
      UUID xDeviceId, CourseCreate courseCreate, Lang acceptLanguage) {

    UUID user = users.resolve(xDeviceId);
    long courseId = store.create(user, courseCreate);
    return ResponseEntity.status(HttpStatus.CREATED).body(read(user, courseId, acceptLanguage));
  }

  @Override
  public ResponseEntity<CourseDetail> getCourse(
      UUID xDeviceId, Long courseId, Lang acceptLanguage) {

    return ResponseEntity.ok(read(users.resolve(xDeviceId), courseId, acceptLanguage));
  }

  @Override
  public ResponseEntity<CourseDetail> replaceCourse(
      UUID xDeviceId, Long courseId, CourseReplace courseReplace, Lang acceptLanguage) {

    UUID user = users.resolve(xDeviceId);
    requireCourse(user, courseId);
    validate(courseReplace);
    requireLongEnoughForTrip(user, courseId, courseReplace.getDays().size());

    try {
      store.replace(courseId, courseReplace);
    } catch (CourseStore.UnknownItemException e) {
      // 남의 코스 항목 id 를 넣었거나 이미 지워진 것을 가리켰다. 서버 결함이 아니라
      // 클라이언트가 잘못 보낸 것이므로 400 이다.
      throw ApiException.badRequest("UNKNOWN_COURSE_ITEM", e.getMessage());
    }

    return ResponseEntity.ok(read(user, courseId, acceptLanguage));
  }

  @Override
  public ResponseEntity<Void> deleteCourse(UUID xDeviceId, Long courseId) {
    if (!store.delete(users.resolve(xDeviceId), courseId)) {
      throw notFound(courseId);
    }
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<CourseDetail> updateCourseProgress(
      UUID xDeviceId, Long courseId, CourseProgress courseProgress, Lang acceptLanguage) {

    UUID user = users.resolve(xDeviceId);
    requireCourse(user, courseId);

    if (courseProgress.getStatus() == CourseStatus.ACTIVE
        && courseProgress.getCurrentDayNo() == null) {
      throw ApiException.badRequest("INVALID_PARAMETER", "여행 중으로 바꾸려면 currentDayNo 가 필요합니다");
    }

    store.updateProgress(courseId, courseProgress);
    return ResponseEntity.ok(read(user, courseId, acceptLanguage));
  }

  // ───────────── 안쪽 ─────────────

  private CourseDetail read(UUID user, long courseId, Lang lang) {
    return store.find(user, courseId, lang).orElseThrow(() -> notFound(courseId));
  }

  private void requireCourse(UUID user, long courseId) {
    if (!store.exists(user, courseId)) {
      throw notFound(courseId);
    }
  }

  /**
   * 여행 중인 코스를 지금 걷고 있는 일차보다 짧게 줄일 수 없다.
   *
   * <p>막지 않으면 {@code course_current_day_no_check} 가 트랜잭션을 되돌리고 500 이 나간다. 그것은 서버 결함이 아니라 사용자가 할 수
   * 없는 일을 한 것이므로 409 로 바꾼다.
   */
  private void requireLongEnoughForTrip(UUID user, long courseId, int newDayCount) {
    store
        .currentDayNo(user, courseId)
        .filter(currentDay -> currentDay > newDayCount)
        .ifPresent(
            currentDay -> {
              throw ApiException.conflict(
                  "COURSE_SHORTER_THAN_PROGRESS",
                  "여행 중에는 지금 일차(" + currentDay + ")보다 짧게 줄일 수 없습니다");
            });
  }

  /**
   * 장소를 가리키는 방법은 둘 중 하나여야 한다.
   *
   * <p>OpenAPI 로는 "정확히 하나" 를 표현할 방법이 마땅치 않아 여기서 본다. 그냥 넣으면 {@code course_item_target_check} 가 걸려
   * 500 이 나가는데, 그것은 클라이언트가 고칠 요청이다.
   */
  private static void validate(CourseReplace req) {
    for (int dayIndex = 0; dayIndex < req.getDays().size(); dayIndex++) {
      List<CourseItemInput> items = req.getDays().get(dayIndex).getItems();
      for (int i = 0; i < items.size(); i++) {
        CourseItemInput item = items.get(i);
        if ((item.getPlaceId() == null) == (item.getCustomPin() == null)) {
          throw ApiException.badRequest(
              "INVALID_PARAMETER",
              (dayIndex + 1) + "일차 " + (i + 1) + "번째 장소는 placeId 와 customPin 중" + " 정확히 하나여야 합니다");
        }
      }
    }
  }

  private static ApiException notFound(long courseId) {
    return ApiException.notFound("COURSE_NOT_FOUND", "코스 " + courseId + " 이(가) 없습니다");
  }
}
