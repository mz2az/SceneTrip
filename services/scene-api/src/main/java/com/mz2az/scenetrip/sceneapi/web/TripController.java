package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.TripApi;
import com.mz2az.scenetrip.sceneapi.api.model.TripPace;
import com.mz2az.scenetrip.sceneapi.api.model.TripPlan;
import com.mz2az.scenetrip.sceneapi.api.model.TripPlanInput;
import com.mz2az.scenetrip.sceneapi.trip.TripStore;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정한 여행 {@code /me/trip}(계약 1.25.0, MZ2AZ-542, 계획 {@code trip-memory.md}). 장바구니·코스처럼 비회원도 — 설치 계정,
 * 토큰이 있으면 그 계정({@link CurrentAccount#resolve}). 범위(일수·속도·도시 길이)는 계약에서 생성된 검증이 먼저 막고, 여기서는 앞뒤 공백만 뗀
 * 도시가 비었는지 본다.
 */
@RestController
class TripController implements TripApi {

  private final TripStore store;
  private final CurrentAccount accounts;

  TripController(TripStore store, CurrentAccount accounts) {
    this.store = store;
    this.accounts = accounts;
  }

  @Override
  public ResponseEntity<TripPlan> getTrip(UUID xInstallId) {
    UUID user = accounts.resolve(xInstallId);
    return ResponseEntity.ok(
        view(
            store
                .find(user)
                .orElseThrow(() -> ApiException.notFound("TRIP_NOT_FOUND", "정한 여행이 없습니다"))));
  }

  @Override
  public ResponseEntity<TripPlan> putTrip(UUID xInstallId, TripPlanInput in) {
    UUID user = accounts.resolve(xInstallId);
    String city = in.getCity() == null ? "" : in.getCity().strip();
    if (city.isEmpty()) {
      throw ApiException.badRequest("INVALID_PARAMETER", "도시 이름은 비울 수 없습니다");
    }
    try {
      return ResponseEntity.ok(
          view(store.put(user, city, in.getDays(), in.getPace().getValue(), in.getCourseId())));
    } catch (TripStore.CourseRejectedException e) {
      throw ApiException.badRequest("TRIP_COURSE_INVALID", e.getMessage());
    }
  }

  @Override
  public ResponseEntity<Void> deleteTrip(UUID xInstallId) {
    store.delete(accounts.resolve(xInstallId));
    return ResponseEntity.noContent().build();
  }

  private static TripPlan view(TripStore.Trip t) {
    return new TripPlan(t.city(), t.days(), TripPace.fromValue(t.pace()), t.updatedAt())
        .courseId(t.courseId());
  }
}
