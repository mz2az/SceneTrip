package com.mz2az.scenetrip.sceneapi.course;

/**
 * 통합 테스트가 스프링 없이 쓰는 {@link TravelEstimator} — <b>배포되는 application.yaml 의 값</b>을 그대로 옮겼다.
 *
 * <p>값이 네 파일에 흩어지지 않게 여기 한 곳에 둔다. 파일과 같은 값인지는 단위 레인의 {@code TravelEstimatorTest} 가 실제 yaml 을 태워
 * 확인한다(scenetrip.course: walking-speed-kmh 4.0, detour-factor 1.3, travel.* — MZ2AZ-370).
 */
public final class DeployedTravel {

  public static final double WALK_SPEED_KMH = 4.0;
  public static final double DETOUR_FACTOR = 1.3;
  public static final int WALK_MAX_METERS = 1500;
  public static final int CITY_MAX_METERS = 30000;
  public static final int CITY_OVERHEAD_MINUTES = 15;
  public static final double CITY_SPEED_KMH = 20;
  public static final int INTERCITY_OVERHEAD_MINUTES = 30;
  public static final double INTERCITY_SPEED_KMH = 60;

  private DeployedTravel() {}

  public static TravelEstimator estimator() {
    return new TravelEstimator(
        WALK_SPEED_KMH,
        DETOUR_FACTOR,
        WALK_MAX_METERS,
        CITY_MAX_METERS,
        CITY_OVERHEAD_MINUTES,
        CITY_SPEED_KMH,
        INTERCITY_OVERHEAD_MINUTES,
        INTERCITY_SPEED_KMH);
  }

  /**
   * 명세의 규칙을 테스트 쪽에서 따로 적은 것 — 구현을 부르지 않는다. 구간 하나의 직선거리(m)를 분으로.
   *
   * <p>걷기 = 직선 × 우회 ÷ 걷는 속도, 시내 = 고정분 + 직선 × 우회 ÷ 시내 속도, 시외 = 고정분 + 직선 ÷ 시외 속도. 걷기 상한 이하는 걷기와 시내 중
   * 빠른 쪽, 시내 상한 이하는 시내, 그보다 멀면 시외 — 단 시내 상한의 시내 값보다 짧지 않게. 0 이하(첫 장소)는 0.
   */
  public static int expectedSegmentMinutes(int straightMeters) {
    if (straightMeters <= 0) {
      return 0;
    }
    if (straightMeters <= WALK_MAX_METERS) {
      return Math.min(walk(straightMeters), city(straightMeters));
    }
    if (straightMeters <= CITY_MAX_METERS) {
      return city(straightMeters);
    }
    return Math.max(city(CITY_MAX_METERS), intercity(straightMeters));
  }

  private static int walk(int m) {
    return (int) Math.round(m * DETOUR_FACTOR / (WALK_SPEED_KMH * 1000 / 60));
  }

  private static int city(int m) {
    return CITY_OVERHEAD_MINUTES
        + (int) Math.round(m * DETOUR_FACTOR / (CITY_SPEED_KMH * 1000 / 60));
  }

  private static int intercity(int m) {
    return INTERCITY_OVERHEAD_MINUTES + (int) Math.round(m / (INTERCITY_SPEED_KMH * 1000 / 60));
  }
}
