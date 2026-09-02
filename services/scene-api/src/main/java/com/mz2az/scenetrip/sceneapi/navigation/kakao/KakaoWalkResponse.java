package com.mz2az.scenetrip.sceneapi.navigation.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 카카오 도보 길찾기({@code GET /v2/routing/walk})의 응답 모양.
 *
 * <p>대중교통({@link KakaoTransitResponse})과 <b>다른 모양</b>이라 클래스가 따로다 — 후보 배열({@code routes[]})이 아니라 경로
 * 하나({@code route})고, 그 안에 {@code legs[]} 층이 하나 더 있어 {@code steps[]} 가 한 겹 깊다. 안쪽 이름({@code
 * Route}·{@code Step}·{@code Path})이 저쪽과 겹치지만 바깥 record 에 중첩돼 있어 전체 이름이 다르다.
 *
 * <p>숫자를 래퍼로 두고 모르는 필드를 무시하는 이유는 {@link KakaoTransitResponse} 와 같다.
 *
 * <p>실측(2026-09-02)으로 확인한 것 — 150 m 부터 3.5 km 까지 전부 {@code OK} 였고, {@code legs} 는 늘 하나였다. step 마다
 * 안내문과 좌표가 오고 3.5 km 에서 좌표 187개였다. 안내문에 「계단」은 한 번도 없었다 — 도보 응답은 계단을 말해 주지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoWalkResponse(String status, Route route) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Route(Properties properties, List<Leg> legs) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Properties(Integer totalDistance, Integer totalTime) {}

  /** 실측에서는 늘 하나였다. 경유지({@code via_x/y})를 주면 둘 이상이 될 것으로 보이나 우리는 경유지를 쓰지 않는다. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Leg(LegProperties properties, List<Step> steps) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LegProperties(Integer distance, Integer time) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Step(StepProperties properties, Path path) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StepProperties(
      Integer distance,
      /** 초. */
      Integer time,
      /** 턴바이턴 안내문. 「반석빌라 앞에서 차마시는 뜰 차뜰까지 오른쪽길로 95m 이동(북촌로11나길)」. */
      String guidance,
      /** 이 step 이 시작하는 자리의 경도. */
      Double x,
      /** 이 step 이 시작하는 자리의 위도. */
      Double y) {}

  /** <b>{@code [경도, 위도]} 순서다.</b> */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Path(List<List<Double>> points) {}
}
