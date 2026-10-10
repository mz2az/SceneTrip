package com.mz2az.scenetrip.sceneapi.course;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 계획 단계 이동시간 어림(MZ2AZ-370, scene-api 1.9.1 {@code TravelBasis}).
 *
 * <p>명세: 구간 하나마다 직선거리로 수단을 고른다.
 *
 * <ul>
 *   <li>걷기 = 직선 × 우회 ÷ 걷는 속도. 시내 = 고정분 + 직선 × 우회 ÷ 시내 속도. 시외 = 고정분 + 직선 ÷ 시외 속도
 *   <li>walk-max-meters 이하: 걷기와 시내 중 빠른 쪽
 *   <li>city-max-meters 이하: 시내
 *   <li>그보다 멀면: 시외 — 단 city-max-meters 의 시내 값보다 짧지 않게
 *   <li>0 이하(첫 장소): 0. 거리가 늘면 시간이 줄지 않는다
 * </ul>
 *
 * <p>기대값은 손으로 계산해 적었다(배포값: 걷기 분당 66.67 m, 시내 분당 333.33 m, 시외 분당 1000 m, 우회 1.3). 분 단위 정수로 반올림한다.
 */
@DisplayName("TravelEstimator — 구간별 이동시간 어림")
class TravelEstimatorTest {

  /** 배포되는 application.yaml 의 값. */
  private static TravelEstimator deployed() {
    return new TravelEstimator(4.0, 1.3, 1500, 30000, 15, 20, 30, 60);
  }

  @Test
  @DisplayName("0 과 음수는 0 분 — 첫 장소")
  void zeroAndNegative() {
    TravelEstimator t = deployed();

    assertThat(t.segmentMinutes(0)).isZero();
    assertThat(t.segmentMinutes(-1)).isZero();
    assertThat(t.segmentMinutes(-50_000)).isZero();
  }

  @Test
  @DisplayName("가까운 구간: 걷기가 빠르면 걷기 — 직선 × 1.3 ÷ 시속 4 km")
  void shortSegmentsWalkWhenFaster() {
    TravelEstimator t = deployed();

    assertThat(t.segmentMinutes(1)).isZero(); // 걷기 0.02 < 시내 15.0
    assertThat(t.segmentMinutes(500)).isEqualTo(10); // 걷기 9.75 < 시내 15 + 1.95
    assertThat(t.segmentMinutes(900)).isEqualTo(18); // 걷기 17.55 < 시내 15 + 3.51 → 19
  }

  @Test
  @DisplayName("가까운 구간: 시내가 빠르면 시내 — 고정 15 분 + 직선 × 1.3 ÷ 시속 20 km")
  void shortSegmentsTakeCityWhenFaster() {
    TravelEstimator t = deployed();

    assertThat(t.segmentMinutes(1100)).isEqualTo(19); // 걷기 21.45 > 시내 15 + 4.29
    assertThat(t.segmentMinutes(1200)).isEqualTo(20); // 걷기 23.4 > 시내 15 + 4.68
  }

  @Test
  @DisplayName("걷기 상한 경계: 1500 m 는 빠른 쪽(시내 21), 1501 m 는 시내 21 — 줄지 않는다")
  void walkBoundary() {
    TravelEstimator t = deployed();

    // 1500: 걷기 29.25 vs 시내 15 + 5.85 → 21. 이전 명세(걷기만)라면 29 였다.
    assertThat(t.segmentMinutes(1500)).isEqualTo(21);
    assertThat(t.segmentMinutes(1501)).isEqualTo(21); // 15 + 5.854
    assertThat(t.segmentMinutes(1501)).isGreaterThanOrEqualTo(t.segmentMinutes(1500));
  }

  @Test
  @DisplayName("시내: 고정 15 분 + 직선 × 1.3 ÷ 시속 20 km")
  void cityFormula() {
    TravelEstimator t = deployed();

    assertThat(t.segmentMinutes(5200)).isEqualTo(15 + 20); // 20.28
    assertThat(t.segmentMinutes(10_000)).isEqualTo(15 + 39); // 39.0
    assertThat(t.segmentMinutes(25_600)).isEqualTo(15 + 100); // 99.84 — 경기 광주 → 수원
  }

  @Test
  @DisplayName("시내 상한 경계: 30000 m 는 시내 132, 30001 m 는 시외지만 132 아래로 내려가지 않는다")
  void cityBoundary() {
    TravelEstimator t = deployed();

    assertThat(t.segmentMinutes(30_000)).isEqualTo(15 + 117); // 117.0
    // 시외 30 + 30.001 → 60 이지만 시내 끝 값 132 보다 짧을 수 없다.
    assertThat(t.segmentMinutes(30_001)).isEqualTo(132);
    assertThat(t.segmentMinutes(45_400)).isEqualTo(132); // 시외 75
    assertThat(t.segmentMinutes(100_000)).isEqualTo(132); // 시외 130
    assertThat(t.segmentMinutes(102_000)).isEqualTo(132); // 시외 132 — 같아지는 자리
  }

  @Test
  @DisplayName("시외: 고정 30 분 + 직선 ÷ 시속 60 km — 우회 계수 없음, 바닥(132)보다 길 때")
  void intercityFormula() {
    TravelEstimator t = deployed();

    assertThat(t.segmentMinutes(103_000)).isEqualTo(30 + 103);
    assertThat(t.segmentMinutes(150_000)).isEqualTo(30 + 150);
    // 우회 계수가 붙었다면 30 + 422.5 → 453 이다.
    assertThat(t.segmentMinutes(325_000)).isEqualTo(30 + 325);
  }

  @Test
  @DisplayName("반올림: 분 단위 가장 가까운 정수")
  void roundsToNearestMinute() {
    TravelEstimator t = deployed();

    // 걷기: 실제 직선 51.28 m 가 1 분.
    assertThat(t.segmentMinutes(25)).isZero(); // 0.4875
    assertThat(t.segmentMinutes(26)).isEqualTo(1); // 0.507
    // 시내: 1 분 = 직선 256.4 m.
    assertThat(t.segmentMinutes(10_128)).isEqualTo(15 + 39); // 39.4992
    assertThat(t.segmentMinutes(10_129)).isEqualTo(15 + 40); // 39.5031
    // 시외: 1000 m 가 1 분.
    assertThat(t.segmentMinutes(150_499)).isEqualTo(30 + 150);
    assertThat(t.segmentMinutes(150_500)).isEqualTo(30 + 151);
  }

  @Test
  @DisplayName("거리가 늘면 시간이 줄지 않는다 — 0 ~ 300 km 를 10 m 간격으로 훑는다")
  void neverDecreasesWithDistance() {
    TravelEstimator t = deployed();

    int previous = t.segmentMinutes(0);
    for (int m = 10; m <= 300_000; m += 10) {
      int now = t.segmentMinutes(m);
      if (now < previous) {
        throw new AssertionError(m + " m 에서 " + previous + " → " + now + " 분으로 줄었다");
      }
      previous = now;
    }
    // 두 경계 바로 앞뒤는 1 m 간격으로.
    for (int edge : new int[] {1500, 30_000}) {
      for (int m = edge - 50; m < edge + 50; m++) {
        assertThat(t.segmentMinutes(m + 1))
            .as("%d → %d m", m, m + 1)
            .isGreaterThanOrEqualTo(t.segmentMinutes(m));
      }
    }
  }

  @Test
  @DisplayName("다른 설정에서도 줄지 않는다 — 시내 끝보다 시외가 훨씬 빠른 값")
  void neverDecreasesWithOtherConfig() {
    TravelEstimator t = new TravelEstimator(3.0, 1.5, 3000, 50_000, 5, 15, 10, 200);

    int previous = 0;
    for (int m = 0; m <= 300_000; m += 7) {
      int now = t.segmentMinutes(m);
      if (now < previous) {
        throw new AssertionError(m + " m 에서 " + previous + " → " + now + " 분으로 줄었다");
      }
      previous = now;
    }
  }

  @Test
  @DisplayName("모든 수치가 설정에서 온다 — 다른 값을 주면 다른 결과")
  void usesEveryConfiguredValue() {
    // 걷기 시속 6(분당 100 m), 우회 2.0, 걷기 상한 1000, 시내 상한 10000, 시내 고정 5·시속 30(분당 500 m),
    // 시외 고정 50·시속 120(분당 2000 m).
    TravelEstimator t = new TravelEstimator(6.0, 2.0, 1000, 10_000, 5, 30, 50, 120);

    assertThat(t.segmentMinutes(200)).isEqualTo(4); // 걷기 4 < 시내 5 + 0.8
    assertThat(t.segmentMinutes(1000)).isEqualTo(9); // 걷기 20 > 시내 5 + 4
    assertThat(t.segmentMinutes(1001)).isEqualTo(9); // 시내 5 + 4.004
    assertThat(t.segmentMinutes(10_000)).isEqualTo(45); // 시내 5 + 40
    assertThat(t.segmentMinutes(10_001)).isEqualTo(55); // 시외 50 + 5.0005 (바닥 45 보다 길다)
    assertThat(t.segmentMinutes(100_000)).isEqualTo(100); // 시외 50 + 50
  }

  @Test
  @DisplayName("배포되는 application.yaml 의 scenetrip.course 값이 실제로 붙는다")
  void bindsTheRealApplicationYaml() throws Exception {
    var sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yaml"));
    var environment = new StandardEnvironment();
    sources.forEach(source -> environment.getPropertySources().addFirst(source));

    // @Value 의 기본값이 yaml 과 같아, 키 이름이 어긋나도 조용히 기본값으로 돈다. 그래서 키가 파일에 있는지 먼저 본다.
    assertThat(environment.getProperty("scenetrip.course.walking-speed-kmh")).isEqualTo("4.0");
    assertThat(environment.getProperty("scenetrip.course.detour-factor")).isEqualTo("1.3");
    assertThat(environment.getProperty("scenetrip.course.travel.walk-max-meters"))
        .isEqualTo("1500");
    assertThat(environment.getProperty("scenetrip.course.travel.city-max-meters"))
        .isEqualTo("30000");
    assertThat(environment.getProperty("scenetrip.course.travel.city-overhead-minutes"))
        .isEqualTo("15");
    assertThat(environment.getProperty("scenetrip.course.travel.city-speed-kmh")).isEqualTo("20");
    assertThat(environment.getProperty("scenetrip.course.travel.intercity-overhead-minutes"))
        .isEqualTo("30");
    assertThat(environment.getProperty("scenetrip.course.travel.intercity-speed-kmh"))
        .isEqualTo("60");

    // 스프링이 그 파일로 실제 빈을 만들었을 때의 결과.
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(environment);
      context.register(TravelEstimator.class);
      context.refresh();
      TravelEstimator bound = context.getBean(TravelEstimator.class);

      assertThat(bound.segmentMinutes(0)).isZero();
      assertThat(bound.segmentMinutes(500)).isEqualTo(10);
      assertThat(bound.segmentMinutes(1500)).isEqualTo(21);
      assertThat(bound.segmentMinutes(1501)).isEqualTo(21);
      assertThat(bound.segmentMinutes(30_000)).isEqualTo(132);
      assertThat(bound.segmentMinutes(30_001)).isEqualTo(132);
      assertThat(bound.segmentMinutes(150_000)).isEqualTo(180);
    }
  }
}
