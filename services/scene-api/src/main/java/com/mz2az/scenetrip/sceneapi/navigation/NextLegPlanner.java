package com.mz2az.scenetrip.sceneapi.navigation;

import static com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient.STATUS_END_NODES_NULL;
import static com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient.STATUS_EQUAL_POINTS;
import static com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient.STATUS_NO_RESULTS;
import static com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient.STATUS_OK;
import static com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient.STATUS_START_NODES_NULL;

import com.mz2az.scenetrip.sceneapi.api.model.LineString;
import com.mz2az.scenetrip.sceneapi.api.model.NextLeg;
import com.mz2az.scenetrip.sceneapi.api.model.RouteLeg;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoTransitResponse;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoWalkResponse;
import com.mz2az.scenetrip.sceneapi.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「지금 여기서 다음 목적지까지」 한 구간을 만든다 — 길찾기의 규칙이 전부 여기 있다.
 *
 * <p>카카오를 부르는 것은 {@link KakaoRoutingClient} 가 하고, 이 클래스는 <b>무엇을 물을지·받은 것을 어떻게 읽을지</b>만 정한다. 카카오 응답의
 * {@code status} 를 읽는 것도 여기다 — {@code NO_RESULTS}·{@code EQUAL_POINTS} 는 오류가 아니라 정상 답이라 예외가 아니라 분기로
 * 다룬다.
 *
 * <p>규칙 넷. 앱의 {@code KakaoTransit.swift} 에서 가져왔고, 실측(2026-09-02)으로 셋을 고쳤다.
 *
 * <ol>
 *   <li><b>가까우면 걷는다.</b> 직선거리가 {@code walk-only-under-meters}(기본 900) 미만이면 대중교통을 묻지 않는다. 카카오는 150 m
 *       에도 「버스 타라」고 답하는데 걷는 것보다 느렸다 — 컷은 실패를 피하려는 게 아니라 <i>걷는 게 빠른데 버스 타라는 답</i>을 피하려는 것이다.
 *   <li><b>대중교통이 없으면 걷는다.</b> {@code NO_RESULTS}·정류장 없음이면 도보를 묻는다. 상한은 두지 않는다 — 「도보 2시간」은 틀린 정보가
 *       아니고 사용자가 택시를 판단할 재료다. 걷는 길도 없어야 422 다. 앱은 여기서 포기했었다.
 *   <li><b>숫자는 카카오 것을 그대로 쓴다.</b> {@code totalTime}·{@code totalDistance} 는 문에서 문까지다 — 양 끝 도보가 step
 *       으로는 안 와도 합계에는 들어 있다(실측: {@code totalDistance − Σ차량} 이 도보 API 로 잰 양 끝 거리와 1 m 단위로 맞았다). 앱은
 *       기운 도보 시간을 여기에 또 더했고, 그것은 두 번 센 것이다.
 *   <li><b>양 끝 도보는 선을 그리려고 기운다.</b> 첫 step 이 차량이면 출발지→승차점, 끝 step 이 차량이면 하차점→목적지를 도보 API 로 받아 앞뒤에
 *       끼운다. 앱은 「{@code WALKING} 이 하나도 없을 때」만 기웠는데, 그러면 환승 도보만 있는 응답에서 양 끝을 놓친다. 기운 도보는 좌표와 안내문만
 *       보태고 숫자는 건드리지 않는다. 못 받으면 그쪽 선만 빠지고 숫자는 그대로다.
 * </ol>
 *
 * <p>모르는 값은 {@code null} 로 남긴다. 0 으로 두면 「안 걸어도 되는 경로」「공짜 경로」가 되어 가장 좋아 보인다.
 */
@Component
public class NextLegPlanner {

  private static final Logger log = LoggerFactory.getLogger(NextLegPlanner.class);

  static final String CODE_ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND";
  static final String CODE_NO_TRANSIT_NEARBY = "NO_TRANSIT_NEARBY";
  private static final String CODE_UNAVAILABLE = "ROUTING_UNAVAILABLE";

  private static final String WALKING = "WALKING";

  private final KakaoRoutingClient kakao;
  private final int walkOnlyUnderMeters;

  /**
   * 규칙에 필요한 것 둘 — 부를 곳과 컷.
   *
   * @param kakao 테스트에서는 가짜를 꽂는다.
   * @param walkOnlyUnderMeters 이 아래면 대중교통을 묻지 않는다. 실측 없이 정한 어림값이라 설정이다.
   */
  public NextLegPlanner(
      KakaoRoutingClient kakao,
      @Value("${scenetrip.navigation.walk-only-under-meters}") int walkOnlyUnderMeters) {
    this.kakao = kakao;
    this.walkOnlyUnderMeters = walkOnlyUnderMeters;
  }

  /** 현재 위치에서 목적지까지. 카카오를 1~3번 부른다 — 도보만이면 1번, 대중교통이면 1번 + 양 끝 도보 최대 2번. */
  public NextLeg plan(Coordinate here, Coordinate target) {
    double straight = here.distanceMetersTo(target);
    if (straight < walkOnlyUnderMeters) {
      return walkOnly(here, target, CODE_ROUTE_NOT_FOUND);
    }

    KakaoTransitResponse res = kakao.transit(here, target);
    String status = res.status();
    if (STATUS_OK.equals(status)) {
      if (res.routes() == null || res.routes().isEmpty()) {
        // OK 인데 후보가 없다 — 문서에 없는 조합이지만 NO_RESULTS 와 같이 다룬다.
        log.info("대중교통 응답이 OK 인데 후보가 비었다 — 도보로 넘어간다 ({} m)", Math.round(straight));
        return walkOnly(here, target, CODE_ROUTE_NOT_FOUND);
      }
      return fromTransit(res.routes().get(0), here, target);
    }
    if (STATUS_EQUAL_POINTS.equals(status)) {
      return arrived();
    }
    if (STATUS_NO_RESULTS.equals(status)) {
      log.info("대중교통 경로 없음 — 도보로 넘어간다 ({} m)", Math.round(straight));
      return walkOnly(here, target, CODE_ROUTE_NOT_FOUND);
    }
    if (STATUS_START_NODES_NULL.equals(status) || STATUS_END_NODES_NULL.equals(status)) {
      log.info("근처에 정류장 없음({}) — 도보로 넘어간다 ({} m)", status, Math.round(straight));
      return walkOnly(here, target, CODE_NO_TRANSIT_NEARBY);
    }
    // INVALID_REQUEST 등 — 우리가 잘못 보낸 것이다. 500 그물로.
    throw new IllegalStateException("카카오 대중교통 응답 상태를 모른다: " + status);
  }

  // ───────────── 도보만 ─────────────

  /**
   * 도보만 묻는다. 가까울 때, 그리고 대중교통이 없을 때의 폴백.
   *
   * @param codeIfNoRoute 걷는 길도 없을 때 낼 422 코드. 대중교통이 없어서 왔으면 「경로 없음」, 정류장이 없어서 왔으면 「근처에 대중교통 없음」 —
   *     어느 쪽인지 앱이 다르게 말할 수 있게 한다.
   */
  private NextLeg walkOnly(Coordinate from, Coordinate to, String codeIfNoRoute) {
    KakaoWalkResponse res = kakao.walk(from, to);
    String status = res.status();
    if (STATUS_EQUAL_POINTS.equals(status)) {
      return arrived();
    }
    if (STATUS_NO_RESULTS.equals(status)
        || STATUS_START_NODES_NULL.equals(status)
        || STATUS_END_NODES_NULL.equals(status)) {
      throw ApiException.unprocessable(codeIfNoRoute, "걸어서 갈 수 있는 길을 찾지 못했습니다");
    }
    if (!STATUS_OK.equals(status) || res.route() == null) {
      throw new IllegalStateException("카카오 도보 응답 상태를 모른다: " + status);
    }

    KakaoWalkResponse.Properties p = res.route().properties();
    requireTotals(p == null ? null : p.totalDistance(), p == null ? null : p.totalTime());
    // 걷는 데 요금은 없다 — 이 0 은 「모름」이 아니라 진짜 0 이다.
    return new NextLeg(ceilMinutes(p.totalTime()), 0, walkLegs(res.route()))
        .walkMeters(p.totalDistance())
        .fareWon(0);
  }

  // ───────────── 대중교통 ─────────────

  private NextLeg fromTransit(
      KakaoTransitResponse.Route route, Coordinate here, Coordinate target) {
    KakaoTransitResponse.Properties p = route.properties();
    requireTotals(p == null ? null : p.totalDistance(), p == null ? null : p.totalTime());
    List<KakaoTransitResponse.Step> steps = route.steps() == null ? List.of() : route.steps();

    // 걷는 거리 = 합계 − 차량 구간. 환승 도보(WALKING step)는 빼지 않는다 — 그것도 걷는 것이다.
    // 실측 검산: 2000 m 케이스 2872 − (1298 + 944) = 630 = 양 끝 473 + 환승 157.
    int vehicleMeters = 0;
    for (KakaoTransitResponse.Step step : steps) {
      if (!isWalking(step)) {
        vehicleMeters += nz(step.properties().distance());
      }
    }
    List<RouteLeg> legs = new ArrayList<>();
    for (KakaoTransitResponse.Step step : steps) {
      legs.add(isWalking(step) ? walkLeg(step) : transitLeg(step));
    }

    // 양 끝 도보 — 첫/끝 step 이 차량일 때만. 위치로 판단하므로 카카오가 양 끝 도보를 줬으면 안 기우고,
    // 환승 도보만 줬으면 양 끝은 기운다.
    if (!steps.isEmpty() && !isWalking(steps.get(0))) {
      Coordinate boarding = firstPoint(steps.get(0));
      if (boarding != null) {
        legs.addAll(0, stitch(here, boarding, "출발지→승차점"));
      }
    }
    if (!steps.isEmpty() && !isWalking(steps.get(steps.size() - 1))) {
      Coordinate alighting = lastPoint(steps.get(steps.size() - 1));
      if (alighting != null) {
        legs.addAll(stitch(alighting, target, "하차점→목적지"));
      }
    }

    int walkMetersRaw = p.totalDistance() - vehicleMeters;
    Integer walkMeters = walkMetersRaw >= 0 ? walkMetersRaw : null;
    return new NextLeg(ceilMinutes(p.totalTime()), nz(p.transfers()), legs)
        .walkMeters(walkMeters)
        .fareWon(fare(p.fare()));
  }

  /**
   * 양 끝 도보를 도보 API 로 받는다. <b>선을 그리려는 것이지 숫자를 맞추려는 것이 아니다</b> — 합계는 이미 문에서 문까지라 여기서 더하지 않는다.
   *
   * <p>못 받으면 빈 목록이다. 본 경로는 이미 있으니 선이 조금 끊기는 쪽이 통째로 503 을 내는 것보다 낫다. 도보 쿼터가 대중교통보다 먼저 바닥나는 날이 이 자리에서
   * 조용히 시작되므로 WARN 으로 남긴다.
   */
  private List<RouteLeg> stitch(Coordinate from, Coordinate to, String label) {
    try {
      KakaoWalkResponse res = kakao.walk(from, to);
      if (STATUS_OK.equals(res.status()) && res.route() != null) {
        return walkLegs(res.route());
      }
      log.info("양 끝 도보를 못 받았다 ({}): status={}", label, res.status());
    } catch (ApiException e) {
      log.warn("양 끝 도보 호출 실패 ({}): {} {}", label, e.getCode(), e.getMessage());
    }
    return List.of();
  }

  // ───────────── step → RouteLeg ─────────────

  /** 도보 API 응답 전체를 구간 목록으로. step 마다 하나 — 골목을 꺾을 때마다 안내문이 하나씩이라 턴바이턴이 된다. */
  private static List<RouteLeg> walkLegs(KakaoWalkResponse.Route route) {
    List<RouteLeg> legs = new ArrayList<>();
    if (route.legs() == null) {
      return legs;
    }
    for (KakaoWalkResponse.Leg leg : route.legs()) {
      if (leg.steps() == null) {
        continue;
      }
      for (KakaoWalkResponse.Step step : leg.steps()) {
        KakaoWalkResponse.StepProperties sp = step.properties();
        String guidance = sp == null || sp.guidance() == null ? "걸어서 이동" : sp.guidance();
        legs.add(
            new RouteLeg(
                RouteLeg.ModeEnum.WALK,
                guidance,
                walkDetail(sp == null ? null : sp.distance(), sp == null ? null : sp.time()),
                lineString(step.path() == null ? null : step.path().points()),
                // 도보 응답은 계단을 말해 주지 않는다 — 실측에서 「계단」 문구가 한 번도 없었다. 「없음」이 아니라 「모름」이다.
                false));
      }
    }
    return legs;
  }

  /** 대중교통 응답 안의 WALKING step — 환승 도보. 통째로 한 step 이라 안내문도 하나다. */
  private static RouteLeg walkLeg(KakaoTransitResponse.Step step) {
    KakaoTransitResponse.StepProperties sp = step.properties();
    String guidance = sp.guidance() == null ? "걸어서 이동" : sp.guidance();
    return new RouteLeg(
        RouteLeg.ModeEnum.WALK,
        guidance,
        walkDetail(sp.distance(), sp.time()),
        lineString(step.path() == null ? null : step.path().points()),
        hasStairs(guidance));
  }

  private static RouteLeg transitLeg(KakaoTransitResponse.Step step) {
    KakaoTransitResponse.StepProperties sp = step.properties();
    String title = vehicleName(sp);
    if (title == null) {
      title = sp.guidance() == null ? modeLabel(sp.type()) : sp.guidance();
    }
    String detail;
    int minutes = ceilMinutes(nz(sp.time()));
    if (sp.stops() != null && sp.stops().size() > 1) {
      detail = (sp.stops().size() - 1) + " 정거장 · " + Math.max(1, minutes) + "분";
    } else {
      detail = Math.max(1, minutes) + "분";
    }
    return new RouteLeg(
        RouteLeg.ModeEnum.TRANSIT,
        title,
        detail,
        lineString(step.path() == null ? null : step.path().points()),
        // 카카오는 계단을 구조화해서 주지 않는다 — 안내문에 섞여 나올 때만 안다. 실측 13건엔 없었지만 지하철이면 나올 수 있다.
        hasStairs(sp.guidance()));
  }

  // ───────────── 잔손질 ─────────────

  /** 「마을 종로02」「간선 143」. 같은 정류장 쌍을 잇는 노선이 여럿이면 첫 것만 — 안내문이 「외 1대」로 나머지를 말해 준다. */
  private static String vehicleName(KakaoTransitResponse.StepProperties sp) {
    if (sp.vehicles() == null || sp.vehicles().isEmpty()) {
      return null;
    }
    KakaoTransitResponse.Vehicle v = sp.vehicles().get(0);
    if (v.name() == null) {
      return null;
    }
    return v.type() == null ? v.name() : v.type() + " " + v.name();
  }

  private static String modeLabel(String type) {
    if ("SUBWAY".equals(type)) {
      return "지하철";
    }
    if ("BUS".equals(type)) {
      return "버스";
    }
    return "이동";
  }

  private static String walkDetail(Integer meters, Integer seconds) {
    int minutes = Math.max(1, ceilMinutes(nz(seconds)));
    return meters == null ? "도보 " + minutes + "분" : "도보 " + minutes + "분 · " + meters + " m";
  }

  private static boolean hasStairs(String guidance) {
    return guidance != null && guidance.contains("계단");
  }

  /**
   * 버스 요금은 거리 비례 노선이 섞이면 값이 아니라 <b>범위</b>로 온다. 0 으로 두면 「공짜 경로」가 되므로 중간값을 쓴다. 실측: 41개 후보 중 2개가 범위였고
   * 둘 다 직행 1101 이 섞인 것이었다.
   */
  private static Integer fare(KakaoTransitResponse.Fare fare) {
    if (fare == null) {
      return null;
    }
    if (fare.value() != null) {
      return fare.value();
    }
    if (fare.min() != null && fare.max() != null) {
      return (fare.min() + fare.max()) / 2;
    }
    return null;
  }

  /** 출발지와 목적지가 같다. 오류가 아니라 「이미 도착」이다 — 계약이 {@code legs: []} 로 정했다. */
  private static NextLeg arrived() {
    return new NextLeg(0, 0, List.of()).walkMeters(0).fareWon(0);
  }

  /**
   * 합계는 카카오 문서상 필수다. 없으면 우리 계약({@code totalMinutes} 필수)을 채울 수 없으니 「응답을 읽지 못함」으로 다룬다 — 우리 결함이 아니라
   * 제공자 쪽이 모양을 바꾼 것이다.
   */
  private static void requireTotals(Integer totalDistance, Integer totalTime) {
    if (totalDistance == null || totalTime == null) {
      log.warn("카카오 응답에 합계가 없다: distance={} time={}", totalDistance, totalTime);
      throw ApiException.unavailable(CODE_UNAVAILABLE, "길찾기 제공자의 응답에 합계가 없습니다");
    }
  }

  private static boolean isWalking(KakaoTransitResponse.Step step) {
    return step.properties() != null && WALKING.equals(step.properties().type());
  }

  /** 카카오 좌표는 {@code [경도, 위도]} 다. */
  private static Coordinate firstPoint(KakaoTransitResponse.Step step) {
    List<List<Double>> pts = step.path() == null ? null : step.path().points();
    if (pts == null || pts.isEmpty() || pts.get(0).size() < 2) {
      return null;
    }
    return new Coordinate(pts.get(0).get(1), pts.get(0).get(0));
  }

  private static Coordinate lastPoint(KakaoTransitResponse.Step step) {
    List<List<Double>> pts = step.path() == null ? null : step.path().points();
    if (pts == null || pts.isEmpty()) {
      return null;
    }
    List<Double> last = pts.get(pts.size() - 1);
    return last.size() < 2 ? null : new Coordinate(last.get(1), last.get(0));
  }

  /** 좌표는 이미 {@code [경도, 위도]} 라 GeoJSON 순서와 같다 — 뒤집지 않는다. */
  private static LineString lineString(List<List<Double>> points) {
    return new LineString(LineString.TypeEnum.LINE_STRING, points == null ? List.of() : points);
  }

  /** 초 → 분, 올림. 도착 예정은 넉넉히 말하는 쪽이 낫다. 0 초는 0 분. */
  private static int ceilMinutes(int seconds) {
    return seconds <= 0 ? 0 : (int) Math.ceil(seconds / 60.0);
  }

  private static int nz(Integer v) {
    return v == null ? 0 : v;
  }
}
