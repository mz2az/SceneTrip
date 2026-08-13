package com.mz2az.scenetrip.sceneapi.course;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 직선거리를 이동시간으로 바꾼다.
 *
 * <p><b>계획 단계에는 길찾기 API 를 부르지 않는다.</b> 여행 전에는 직선거리만 보여 주고 실제 경로는 여행 중에만 부른다(3주차 회의). 그래서 하루가 얼마나
 * 걸리는지는 이 어림값으로 낸다.
 *
 * <p>구간별로는 이 값을 내보내지 않는다. 하루 합계에만 실리고 {@code CourseDay.travelBasis} 가 직선거리 추정임을 밝힌다 — 어림값을 구간마다 보여
 * 주면 사용자가 실제 소요 시간으로 읽는다.
 *
 * <p>두 값 모두 설정이다. 실측 없이 정한 수치라 데이터가 쌓이면 조정한다 — 코드를 고치지 않고 바꿀 수 있어야 한다.
 */
@Component
public class TravelEstimator {

  private final double speedKmh;
  private final double detourFactor;

  /** 통합 테스트가 스프링 없이 직접 만들 수 있어야 해서 public 이다. */
  public TravelEstimator(
      @Value("${scenetrip.course.walking-speed-kmh}") double speedKmh,
      @Value("${scenetrip.course.detour-factor}") double detourFactor) {
    this.speedKmh = speedKmh;
    this.detourFactor = detourFactor;
  }

  /**
   * 직선거리 합계(m)를 분으로.
   *
   * @param straightLineMeters 한 일차의 구간 거리 합. 0 이면 0 분이다.
   */
  public int minutes(int straightLineMeters) {
    if (straightLineMeters <= 0) {
      return 0;
    }
    double metresPerMinute = speedKmh * 1000 / 60;
    return (int) Math.round(straightLineMeters * detourFactor / metresPerMinute);
  }
}
