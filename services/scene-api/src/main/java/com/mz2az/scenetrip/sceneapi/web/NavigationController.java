package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.NavigationApi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.NextLeg;
import com.mz2az.scenetrip.sceneapi.api.model.NextLegRequest;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.navigation.Coordinate;
import com.mz2az.scenetrip.sceneapi.navigation.NextLegPlanner;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 여행 중 길찾기 — 현재 위치에서 다음 목적지까지 한 구간.
 *
 * <p><b>여행 중에만 부를 수 있다.</b> 코스가 {@code active} 가 아니면 409 다. 유료 API 라 호출 여부를 서버가 정한다 — 클라이언트가 정하게 두면
 * 과금이 클라이언트 손에 넘어간다. 같은 이유로 가입 사용자만 부를 수 있다. 전체 구간을 미리 계산해 두지 않고 <b>한 구간씩</b> 부른다 — 사용자가 계획대로만 움직이지
 * 않기 때문이다.
 *
 * <p>확인 다섯은 전부 우리 DB 만 본다. 여기서 튕기면 제공자 쿼터를 쓰지 않는다 — 가입 안 한 사람, 예정 코스, 남의 코스, 지워진 항목이 비용 없이 걸러진다.
 * 404 가 409 보다 앞인 이유는 {@code CourseController} 와 같다 — 남의 코스에 「여행 중이 아닙니다」라고 하면 그 코스가 있다는 것을 알려 주는
 * 셈이다.
 *
 * <p>이 클래스는 통제와 변환만 한다. 규칙은 {@link NextLegPlanner}, 제공자 호출은 그 안의 클라이언트에 있고, 그쪽이 던진 {@code
 * ApiException}(422·503)은 {@code ApiExceptionHandler} 가 받는다 — 여기에 {@code try/catch} 가 없는 이유다.
 *
 * <p>목적지는 코스 항목({@code itemId})으로만 가리킨다. 챗봇이 찾아 준 가게로 갈아타는 것(코스 밖 좌표)은 이 계약에 없다.
 */
@RestController
class NavigationController implements NavigationApi {

  private final NextLegPlanner planner;
  private final CourseStore courses;
  private final UserStore users;

  NavigationController(NextLegPlanner planner, CourseStore courses, UserStore users) {
    this.planner = planner;
    this.courses = courses;
    this.users = users;
  }

  @Override
  public ResponseEntity<NextLeg> getNextLeg(
      UUID xDeviceId, NextLegRequest request, Lang acceptLanguage) {

    UUID user = users.resolve(xDeviceId);
    long courseId = request.getCourseId();

    if (!users.isRegistered(user)) {
      throw ApiException.signInRequired("SIGN_IN_REQUIRED", "이 동작은 가입한 사용자만 할 수 있습니다");
    }
    if (!courses.exists(user, courseId)) {
      throw ApiException.notFound("COURSE_NOT_FOUND", "코스 " + courseId + " 을(를) 찾을 수 없습니다");
    }
    if (!courses.isActive(user, courseId)) {
      throw ApiException.conflict("COURSE_NOT_ACTIVE", "코스 " + courseId + " 은(는) 여행 중이 아닙니다");
    }
    long itemId = request.getItemId();
    CourseStore.ItemLocation target =
        courses
            .findItemLocation(courseId, itemId)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "COURSE_ITEM_NOT_FOUND",
                        "코스 " + courseId + " 에 항목 " + itemId + " 이(가) 없습니다"));

    // 코스가 내준 좌표를 길찾기 타입으로. 두 패키지가 서로 모르고 여기서만 만난다.
    NextLeg leg =
        planner.plan(
            new Coordinate(request.getLatitude(), request.getLongitude()),
            new Coordinate(target.latitude(), target.longitude()),
            acceptLanguage);
    return ResponseEntity.ok(leg);
  }
}
