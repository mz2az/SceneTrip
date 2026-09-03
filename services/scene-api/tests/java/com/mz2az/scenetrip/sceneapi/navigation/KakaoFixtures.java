package com.mz2az.scenetrip.sceneapi.navigation;

import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoTransitResponse;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoWalkResponse;
import java.util.List;

/**
 * 카카오 응답을 코드로 조립한다. 모양은 실측(2026-09-02, 북촌 출발 13건)과 같고 <b>내용은 지어낸 것</b>이다.
 *
 * <p>실측 JSON 을 그대로 저장소에 넣지 않는다 — 정류장 이름·안내문이 카카오 데이터라 복제·저장에 해당할 수 있다(운영정책 제5조 30호). 구조·필드
 * 이름·{@code WALKING} 의 자리만 실측 그대로 베꼈다.
 *
 * <p>좌표는 {@code [경도, 위도]} 다. 원점은 (37.0, 127.0). 대중교통이 나오게 하려면 목적지를 900 m 밖으로 둔다 — 위도 0.02 도가 약 2.2
 * km 다.
 */
final class KakaoFixtures {

  private KakaoFixtures() {}

  static final Coordinate HERE = new Coordinate(37.0, 127.0);

  /** 약 111 m. 900 m 컷 아래. */
  static final Coordinate NEAR = new Coordinate(37.001, 127.0);

  /** 약 2.2 km. 대중교통을 묻는다. */
  static final Coordinate FAR = new Coordinate(37.02, 127.0);

  static List<List<Double>> points(double[]... lngLat) {
    return java.util.Arrays.stream(lngLat).map(p -> List.of(p[0], p[1])).toList();
  }

  // ───────────── 대중교통 ─────────────

  static KakaoTransitResponse transit(String status, KakaoTransitResponse.Route... routes) {
    return new KakaoTransitResponse(status, List.of(routes));
  }

  static KakaoTransitResponse.Route route(
      int totalDistance,
      int totalTime,
      int transfers,
      KakaoTransitResponse.Fare fare,
      KakaoTransitResponse.Step... steps) {
    return new KakaoTransitResponse.Route(
        new KakaoTransitResponse.Properties("BUS", totalDistance, totalTime, transfers, fare),
        List.of(steps));
  }

  static KakaoTransitResponse.Fare fare(int value) {
    return new KakaoTransitResponse.Fare(value, null, null);
  }

  static KakaoTransitResponse.Fare fareRange(int min, int max) {
    return new KakaoTransitResponse.Fare(null, min, max);
  }

  /** 버스 한 구간. 정거장 {@code stops} 개(길이 − 1 이 「N 정거장」), 첫 좌표가 승차점·끝 좌표가 하차점. */
  static KakaoTransitResponse.Step bus(
      String name, int distance, int time, int stops, List<List<Double>> path) {
    List<KakaoTransitResponse.Stop> stopList =
        java.util.stream.IntStream.range(0, stops)
            .mapToObj(i -> new KakaoTransitResponse.Stop("가상정류장" + (char) ('A' + i)))
            .toList();
    return new KakaoTransitResponse.Step(
        new KakaoTransitResponse.StepProperties(
            "BUS",
            "마을 " + name + " (가상정류장A > 가상정류장" + (char) ('A' + stops - 1) + ")",
            distance,
            time,
            stopList,
            List.of(new KakaoTransitResponse.Vehicle(name, "마을"))),
        new KakaoTransitResponse.Path(path));
  }

  /** 대중교통 응답 안의 환승 도보. 실측처럼 통째로 한 step 이고 {@code stops} 는 하차·승차 둘. */
  static KakaoTransitResponse.Step transferWalk(int distance, int time, List<List<Double>> path) {
    return new KakaoTransitResponse.Step(
        new KakaoTransitResponse.StepProperties(
            "WALKING",
            "가상정류장B정류장까지 도보로 이동",
            distance,
            time,
            List.of(
                new KakaoTransitResponse.Stop("가상정류장A"), new KakaoTransitResponse.Stop("가상정류장B")),
            null),
        new KakaoTransitResponse.Path(path));
  }

  /** 안내문에 「계단」이 든 도보 step. 대중교통 응답에서만 나올 수 있다. */
  static KakaoTransitResponse.Step transferWalkWithStairs(int distance, int time) {
    return new KakaoTransitResponse.Step(
        new KakaoTransitResponse.StepProperties(
            "WALKING", "계단을 이용해 가상정류장B까지 이동", distance, time, null, null),
        new KakaoTransitResponse.Path(points(new double[] {127.0, 37.01})));
  }

  // ───────────── 도보 ─────────────

  static KakaoWalkResponse walk(String status) {
    return new KakaoWalkResponse(status, null);
  }

  /** 도보 응답. step 마다 안내문 하나 — 턴바이턴. */
  static KakaoWalkResponse walk(int totalDistance, int totalTime, KakaoWalkResponse.Step... steps) {
    return new KakaoWalkResponse(
        "OK",
        new KakaoWalkResponse.Route(
            new KakaoWalkResponse.Properties(totalDistance, totalTime),
            List.of(
                new KakaoWalkResponse.Leg(
                    new KakaoWalkResponse.LegProperties(totalDistance, totalTime),
                    List.of(steps)))));
  }

  static KakaoWalkResponse.Step walkStep(
      String guidance, int distance, int time, List<List<Double>> path) {
    double x = path.isEmpty() ? 127.0 : path.get(0).get(0);
    double y = path.isEmpty() ? 37.0 : path.get(0).get(1);
    return new KakaoWalkResponse.Step(
        new KakaoWalkResponse.StepProperties(distance, time, guidance, x, y),
        new KakaoWalkResponse.Path(path));
  }

  /** 아무 도보 응답. 기우기 검사에서 「호출됐고 좌표가 붙었다」만 보면 될 때. */
  static KakaoWalkResponse anyWalk() {
    return walk(
        300,
        280,
        walkStep("가상빌라까지 200m 이동", 200, 180, points(new double[] {127.0, 37.0})),
        walkStep("가상빌라 앞에서 오른쪽길로 100m 이동", 100, 100, points(new double[] {127.0, 37.001})));
  }
}
