package com.mz2az.scenetrip.sceneapi.navigation.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 카카오 대중교통 길찾기({@code GET /v2/routing/publictraffic})의 응답 모양.
 *
 * <p>카카오가 정한 모양이라 우리가 바꿀 수 없다. 여기서는 받기만 하고, 우리 모양({@code NextLeg})으로 바꾸는 것은 {@code NextLegPlanner}
 * 가 한다. 그래서 이 패키지 밖으로 이 타입이 나가지 않는다.
 *
 * <p><b>숫자는 전부 래퍼 타입이다.</b> 카카오가 안 준 필드가 {@code int} 면 0 이 되고, 그 0 이 「도보 0 m」「요금 0 원」으로 읽혀 그 후보가 가장
 * 좋아 보인다 — 프로토타입이 같은 실수를 네 번 반복하고 얻은 규칙이다. 없으면 {@code null} 로 남겨 받는 쪽이 「모름」으로 다루게 한다.
 *
 * <p><b>모르는 필드는 무시한다.</b> 카카오가 필드를 더해도 깨지지 않아야 한다. 최상위 {@code properties}(후보 집계)와 {@code
 * landingURL} 은 쓸 데가 없어 애초에 받지 않는다.
 *
 * <p>실측(2026-09-02, 북촌 출발 13건)으로 확인한 것 —
 *
 * <ul>
 *   <li>{@code totalDistance}·{@code totalTime} 은 <b>문에서 문까지</b>다. 양 끝 도보(출발지→승차 정류장, 하차 정류장→목적지)가
 *       step 으로는 안 오지만 합계에는 들어 있다. {@code totalDistance − Σ step.distance} 가 도보 API 로 실측한 양 끝 거리와 1
 *       m 단위로 맞았다.
 *   <li>양 끝 도보 step 은 87% 에서 없고, 환승 사이 도보는 걸을 것이 있을 때만 {@code WALKING} step 으로 온다. 같은 정류장 환승(0 m)은
 *       생략된다.
 *   <li>{@code fare} 는 {@code value} 하나거나 {@code min}/{@code max} 둘이다. 둘이 같이 오지 않는다. 범위는 거리 비례
 *       노선(직행)이 섞였을 때다.
 *   <li>step 의 시간 필드는 {@code time} 이다. {@code duration} 은 없다 — 프로토타입 스위프트가 그 이름을 읽어 늘 nil 이었다.
 * </ul>
 *
 * @param status {@code OK} 외에 {@code NO_RESULTS}·{@code EQUAL_POINTS}·{@code
 *     STARTNODES_NULL}·{@code ENDNODES_NULL}·{@code INVALID_REQUEST}. {@code OK} 가 아니어도 HTTP 200
 *     이다.
 * @param routes 경로 후보. 카카오가 정렬해서 주므로 첫 것이 카카오 기준 1위다. 거리에 따라 1~15개.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoTransitResponse(String status, List<Route> routes) {

  /** 경로 후보 하나. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Route(Properties properties, List<Step> steps) {}

  /**
   * 후보 하나의 합계.
   *
   * @param type {@code BUS} · {@code SUBWAY} · {@code BUS_AND_SUBWAY}
   * @param totalDistance 문에서 문까지(m). 양 끝 도보 포함.
   * @param totalTime 문에서 문까지(초). 양 끝 도보와 대기 포함 — 여기에 도보 시간을 또 더하면 두 번 센다.
   * @param transfers 환승 횟수
   * @param fare 요금
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Properties(
      String type, Integer totalDistance, Integer totalTime, Integer transfers, Fare fare) {}

  /** 요금(원). {@code value} 하나거나 {@code min}/{@code max} 범위 — 둘이 같이 오지 않는다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Fare(Integer value, Integer min, Integer max) {}

  /** 한 구간 — 탈 것 하나, 또는 걷기. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Step(StepProperties properties, Path path) {}

  /**
   * 구간의 속성.
   *
   * @param type {@code BUS} · {@code SUBWAY} · {@code WALKING}
   * @param guidance 안내문. 「마을 종로02 (북촌한옥마을입구 > 가회동주민센터)」「종로2가정류장까지 도보로 이동」
   * @param distance 미터
   * @param time 초
   * @param stops 지나는 정거장. {@code WALKING} 에는 없다. 길이 − 1 이 「N 정거장」이다.
   * @param vehicles 탈 수 있는 노선들. 같은 정류장 쌍을 잇는 노선이 여럿이면 여럿이다. {@code WALKING} 에는 없다.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StepProperties(
      String type,
      String guidance,
      Integer distance,
      Integer time,
      List<Stop> stops,
      List<Vehicle> vehicles) {}

  /** 정거장. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Stop(String name) {}

  /** 노선. {@code name} 은 「종로02」「143」, {@code type} 은 「마을」「간선」「직행」. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Vehicle(String name, String type) {}

  /** 이 step 이 지나는 실제 길 좌표. <b>{@code [경도, 위도]} 순서다.</b> 첫 좌표가 승차점, 끝 좌표가 하차점이다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Path(List<List<Double>> points) {}
}
