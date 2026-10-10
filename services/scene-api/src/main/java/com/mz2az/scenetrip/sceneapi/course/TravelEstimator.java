package com.mz2az.scenetrip.sceneapi.course;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 직선거리를 이동시간으로 바꾼다 — 구간마다, 거리에 따라 걷기·시내 대중교통·시외로 어림한다(MZ2AZ-370).
 *
 * <p><b>계획 단계에는 길찾기 API 를 부르지 않는다.</b> 여행 전에는 어림만 보여 주고 실제 경로는 여행 중에만 부른다(3주차 회의, 2026-10-10 권호 재확인
 * — 카카오 호출을 늘리지 않는다). 그래서 하루가 얼마나 걸리는지는 이 어림값으로 낸다.
 *
 * <p>전에는 모든 구간을 걸어서 갔다(직선 × 1.3 ÷ 시속 4 km). 경기 광주 → 수원(직선 25.6 km)이 8 시간 18 분, 제주 → 서울이 157 시간으로 나와
 * 화면에 낼 수 없었다. 이제 구간 하나마다
 *
 * <ul>
 *   <li><b>걷기</b> = 직선 × 우회 계수 ÷ 걷는 속도. {@code walk-max-meters} 이하에서만 고를 수 있다
 *   <li><b>시내 대중교통</b> = 고정분(정류장까지·기다림·갈아타기) + 직선 × 우회 계수 ÷ 시내 속도
 *   <li><b>시외</b> = 고정분(터미널·역까지·기다림) + 직선 ÷ 시외 속도 — 고속도로·철도는 거의 곧게 간다
 * </ul>
 *
 * <p>고르는 규칙: {@code walk-max-meters} 이하는 걷기와 시내 중 빠른 쪽, {@code city-max-meters} 이하는 시내, 그보다 멀면 시외 —
 * 단 시내 끝의 값보다 짧지 않게. <b>거리가 늘면 시간이 줄지 않는다.</b>
 *
 * <p>구간별로는 이 값을 내보내지 않는다. 하루 합계에만 실리고 {@code CourseDay.travelBasis} 가 직선거리 추정임을 밝힌다 — 어림값을 구간마다 보여
 * 주면 사용자가 실제 소요 시간으로 읽는다. 비행기 구간(제주 ↔ 육지)은 다루지 못한다 — 시외로 계산되어 길게 나온다.
 *
 * <p>모든 수치는 설정이다. 실측 없이 정한 값이라 데이터가 쌓이면 조정한다 — 코드를 고치지 않고 바꿀 수 있어야 한다.
 */
@Component
public class TravelEstimator {

  private final double walkSpeedKmh;
  private final double detourFactor;
  private final int walkMaxMeters;
  private final int cityMaxMeters;
  private final int cityOverheadMinutes;
  private final double citySpeedKmh;
  private final int intercityOverheadMinutes;
  private final double intercitySpeedKmh;

  /** 통합 테스트가 스프링 없이 직접 만들 수 있어야 해서 public 이다. */
  public TravelEstimator(
      @Value("${scenetrip.course.walking-speed-kmh}") double walkSpeedKmh,
      @Value("${scenetrip.course.detour-factor}") double detourFactor,
      @Value("${scenetrip.course.travel.walk-max-meters:1500}") int walkMaxMeters,
      @Value("${scenetrip.course.travel.city-max-meters:30000}") int cityMaxMeters,
      @Value("${scenetrip.course.travel.city-overhead-minutes:15}") int cityOverheadMinutes,
      @Value("${scenetrip.course.travel.city-speed-kmh:20}") double citySpeedKmh,
      @Value("${scenetrip.course.travel.intercity-overhead-minutes:30}")
          int intercityOverheadMinutes,
      @Value("${scenetrip.course.travel.intercity-speed-kmh:60}") double intercitySpeedKmh) {
    this.walkSpeedKmh = walkSpeedKmh;
    this.detourFactor = detourFactor;
    this.walkMaxMeters = walkMaxMeters;
    this.cityMaxMeters = cityMaxMeters;
    this.cityOverheadMinutes = cityOverheadMinutes;
    this.citySpeedKmh = citySpeedKmh;
    this.intercityOverheadMinutes = intercityOverheadMinutes;
    this.intercitySpeedKmh = intercitySpeedKmh;
  }

  /**
   * 구간 하나의 직선거리(m)를 분으로.
   *
   * @param straightLineMeters 앞 장소에서 여기까지의 직선거리. 0 이하면 0 분이다(첫 장소).
   */
  public int segmentMinutes(int straightLineMeters) {
    if (straightLineMeters <= 0) {
      return 0;
    }
    int city = cityMinutes(straightLineMeters);
    if (straightLineMeters <= walkMaxMeters) {
      // 걷기와 대중교통 중 빠른 쪽. 고정분이 있어 아주 가까우면 걷기가, 1 km 남짓부터는 대중교통이 이긴다 — 경계에서 값이 튀지 않는다.
      return Math.min(walkMinutes(straightLineMeters), city);
    }
    if (straightLineMeters <= cityMaxMeters) {
      return city;
    }
    // 시외는 시내 끝(city-max)보다 짧게 걸릴 수 없다 — 그렇지 않으면 30 km 는 2 시간, 30.001 km 는 1 시간으로 거리가 늘었는데 시간이
    // 준다(2026-10-10 시험이 찾았다). 시외 어림이 시내 끝의 값을 넘을 때까지는 시내 끝의 값에 머문다.
    return Math.max(intercityMinutes(straightLineMeters), cityMinutes(cityMaxMeters));
  }

  private int walkMinutes(int meters) {
    return (int) Math.round(meters * detourFactor / metresPerMinute(walkSpeedKmh));
  }

  private int cityMinutes(int meters) {
    return cityOverheadMinutes
        + (int) Math.round(meters * detourFactor / metresPerMinute(citySpeedKmh));
  }

  private int intercityMinutes(int meters) {
    return intercityOverheadMinutes + (int) Math.round(meters / metresPerMinute(intercitySpeedKmh));
  }

  private static double metresPerMinute(double kmh) {
    return kmh * 1000 / 60;
  }
}
