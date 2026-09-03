package com.mz2az.scenetrip.sceneapi.navigation;

import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.FAR;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.HERE;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.NEAR;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.anyWalk;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.bus;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.fare;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.fareRange;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.points;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.route;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.transferWalk;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.transferWalkWithStairs;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.transit;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.walk;
import static com.mz2az.scenetrip.sceneapi.navigation.KakaoFixtures.walkStep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.NextLeg;
import com.mz2az.scenetrip.sceneapi.api.model.RouteLeg;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoRoutingClient;
import com.mz2az.scenetrip.sceneapi.navigation.kakao.KakaoTransitResponse;
import com.mz2az.scenetrip.sceneapi.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 길찾기 규칙. 카카오는 부르지 않는다 — 클라이언트를 가짜로 꽂고 응답 record 를 코드로 조립한다.
 *
 * <p>실측(2026-09-02)으로 앱의 규칙 셋을 고쳤고, 여기가 그것을 못 박는 자리다 — 합계에 도보 시간을 더하지 않는다, 걷는 거리는 뺄셈이다, 양 끝 기우기는
 * 첫/끝 step 의 종류로 판단한다. 이 셋이 돌아가면 앱이 했던 실수(두 번 세기, 환승 도보만 있을 때 양 끝 놓치기)로 되돌아간 것이다.
 *
 * <p>기대값의 근거는 2026-09-03 e2e — 북촌→홍익대부속중고: 31분·환승 1·도보 1,094 m·1,200원·legs 12 (BUS→WALK→BUS 에 양 끝
 * 기움).
 */
class NextLegPlannerTest {

  private static final int WALK_ONLY_UNDER = 900;

  private KakaoRoutingClient kakao;
  private NextLegPlanner planner;

  @BeforeEach
  void setUp() {
    kakao = mock(KakaoRoutingClient.class);
    planner = new NextLegPlanner(kakao, WALK_ONLY_UNDER);
  }

  // 승차점 (127.0, 37.005) · 하차점 (127.0, 37.018). 실측처럼 버스 step 의 첫/끝 좌표다.
  private static final List<List<Double>> BUS_PATH =
      points(
          new double[] {127.0, 37.005}, new double[] {127.0, 37.01}, new double[] {127.0, 37.018});

  private static KakaoTransitResponse.Route busOnly() {
    // 합계 2000 m 에 버스 1300 m → 양 끝 도보 700 m 가 합계에만 들어 있다 (실측 모양).
    return route(2000, 1500, 0, fare(1200), bus("종로02", 1300, 400, 5, BUS_PATH));
  }

  @Nested
  @DisplayName("가까우면 걷는다")
  class WalkOnly {

    @Test
    @DisplayName("900 m 미만이면 대중교통을 묻지 않는다")
    void underCutAsksWalkOnly() {
      when(kakao.walk(eq(HERE), eq(NEAR), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, NEAR, Lang.KO);

      verify(kakao, never()).transit(any(), any(), anyString());
      verify(kakao, times(1)).walk(any(), any(), anyString());
      assertThat(leg.getTransfers()).isZero();
      assertThat(leg.getWalkMeters()).isEqualTo(300);
      assertThat(leg.getFareWon()).as("걷는 데 요금은 없다 — 모름이 아니라 진짜 0").isZero();
      assertThat(leg.getLegs()).hasSize(2).allMatch(l -> l.getMode() == RouteLeg.ModeEnum.WALK);
      assertThat(leg.getLegs().get(0).getGuidance()).isEqualTo("가상빌라까지 200m 이동");
      assertThat(leg.getLegs().get(0).getMeters()).isEqualTo(200);
    }

    @Test
    @DisplayName("도보도 없으면 422 ROUTE_NOT_FOUND — 직선 추정으로 지어내지 않는다")
    void noWalkIs422() {
      when(kakao.walk(any(), any(), anyString())).thenReturn(walk("NO_RESULTS"));

      assertThatThrownBy(() -> planner.plan(HERE, NEAR, Lang.KO))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getCode()).isEqualTo("ROUTE_NOT_FOUND");
              });
    }
  }

  @Nested
  @DisplayName("대중교통 — 숫자는 카카오 것을 그대로")
  class TransitNumbers {

    @Test
    @DisplayName("totalMinutes 는 합계 올림 — 기운 도보 시간을 더하지 않는다")
    void totalMinutesIsCeilOfKakaoTotal() {
      when(kakao.transit(eq(HERE), eq(FAR), anyString())).thenReturn(transit("OK", busOnly()));
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk()); // 기운 도보 280초 × 2

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      // 1500초 → 25분. 앱은 여기에 280+280초를 더해 35분을 냈다 — 두 번 센 것.
      assertThat(leg.getTotalMinutes()).isEqualTo(25);
    }

    @Test
    @DisplayName("walkMeters 는 합계 − 차량 — 호출 없이, 환승 도보까지 포함")
    void walkMetersIsTotalMinusVehicles() {
      // 실측 검산 그대로: 2872 − (1298 + 944) = 630 = 양 끝 473 + 환승 157
      KakaoTransitResponse.Route r =
          route(
              2872,
              1452,
              1,
              fare(1500),
              bus("종로02", 1298, 557, 5, BUS_PATH),
              transferWalk(157, 171, points(new double[] {127.0, 37.018})),
              bus("143", 944, 282, 3, BUS_PATH));
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", r));
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      assertThat(leg.getWalkMeters()).isEqualTo(630);
      assertThat(leg.getTransfers()).isEqualTo(1);
    }

    @Test
    @DisplayName("요금 — value 는 그대로, 범위는 중간값, 없으면 null")
    void fareShapes() {
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk());

      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", busOnly()));
      assertThat(planner.plan(HERE, FAR, Lang.KO).getFareWon()).isEqualTo(1200);

      when(kakao.transit(any(), any(), anyString()))
          .thenReturn(
              transit(
                  "OK",
                  route(
                      2000, 1500, 0, fareRange(1500, 3200), bus("1101", 1300, 400, 5, BUS_PATH))));
      assertThat(planner.plan(HERE, FAR, Lang.KO).getFareWon()).isEqualTo(2350);

      when(kakao.transit(any(), any(), anyString()))
          .thenReturn(transit("OK", route(2000, 1500, 0, null, bus("x", 1300, 400, 5, BUS_PATH))));
      assertThat(planner.plan(HERE, FAR, Lang.KO).getFareWon()).as("모르면 0 이 아니라 null").isNull();
    }

    @Test
    @DisplayName("합계가 없으면 503 — 우리 결함이 아니라 제공자가 모양을 바꾼 것")
    void missingTotalsIs503() {
      KakaoTransitResponse.Route broken =
          new KakaoTransitResponse.Route(
              new KakaoTransitResponse.Properties("BUS", null, 1500, 0, fare(1200)),
              List.of(bus("x", 1300, 400, 5, BUS_PATH)));
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", broken));

      assertThatThrownBy(() -> planner.plan(HERE, FAR, Lang.KO))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
  }

  @Nested
  @DisplayName("양 끝 도보 기우기 — 첫/끝 step 의 종류로 판단")
  class Stitching {

    @Test
    @DisplayName("버스만 오면 승차점까지·하차점부터 도보를 받아 앞뒤에 끼운다")
    void busOnlyStitchesBothEnds() {
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", busOnly()));
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      // 출발지→승차점(첫 좌표), 하차점(끝 좌표)→목적지
      verify(kakao).walk(eq(HERE), eq(new Coordinate(37.005, 127.0)), anyString());
      verify(kakao).walk(eq(new Coordinate(37.018, 127.0)), eq(FAR), anyString());
      assertThat(leg.getLegs())
          .extracting(RouteLeg::getMode)
          .containsExactly(
              RouteLeg.ModeEnum.WALK,
              RouteLeg.ModeEnum.WALK,
              RouteLeg.ModeEnum.TRANSIT,
              RouteLeg.ModeEnum.WALK,
              RouteLeg.ModeEnum.WALK);
      RouteLeg transit = leg.getLegs().get(2);
      assertThat(transit.getVehicleType()).isEqualTo("마을");
      assertThat(transit.getVehicleName()).isEqualTo("종로02");
      assertThat(transit.getStopCount()).isEqualTo(4);
      assertThat(transit.getPath().getCoordinates()).hasSize(3);
    }

    @Test
    @DisplayName("BUS→WALK→BUS 도 양 끝을 기운다 — 앱은 WALKING 이 있다고 안 기웠다")
    void transferWalkOnlyStillStitchesEnds() {
      KakaoTransitResponse.Route r =
          route(
              3440,
              1860,
              1,
              fare(1200),
              bus("종로02", 1298, 343, 5, BUS_PATH),
              transferWalk(577, 633, points(new double[] {127.0, 37.018})),
              bus("성북03", 1048, 357, 5, BUS_PATH));
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", r));
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      verify(kakao, times(2)).walk(any(), any(), anyString());
      assertThat(leg.getLegs()).hasSize(2 + 1 + 1 + 1 + 2); // 기움 2 · BUS · 환승 · BUS · 기움 2
      assertThat(leg.getLegs().get(3).getMode()).isEqualTo(RouteLeg.ModeEnum.WALK);
      assertThat(leg.getLegs().get(3).getGuidance()).isEqualTo("가상정류장B정류장까지 도보로 이동");
      assertThat(leg.getWalkMeters()).isEqualTo(3440 - 1298 - 1048);
    }

    @Test
    @DisplayName("카카오가 앞 도보를 줬으면 앞은 안 기운다")
    void leadingWalkingSkipsHeadStitch() {
      KakaoTransitResponse.Route r =
          route(
              2000,
              1500,
              0,
              fare(1200),
              transferWalk(300, 300, points(new double[] {127.0, 37.0})),
              bus("종로02", 1300, 400, 5, BUS_PATH));
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", r));
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      verify(kakao, times(1)).walk(any(), any(), anyString()); // 뒤만
      verify(kakao).walk(eq(new Coordinate(37.018, 127.0)), eq(FAR), anyString());
      assertThat(leg.getLegs().get(0).getMode()).isEqualTo(RouteLeg.ModeEnum.WALK);
      assertThat(leg.getLegs().get(0).getMeters()).isEqualTo(300);
    }

    @Test
    @DisplayName("기울 도보가 503 이면 그 끝만 빠지고 숫자는 그대로 — 예외를 내지 않는다")
    void stitchFailureDropsThatEndOnly() {
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", busOnly()));
      when(kakao.walk(eq(HERE), any(), anyString()))
          .thenThrow(ApiException.unavailable("ROUTING_UNAVAILABLE", "한도"));
      when(kakao.walk(eq(new Coordinate(37.018, 127.0)), any(), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      assertThat(leg.getLegs())
          .extracting(RouteLeg::getMode)
          .containsExactly(
              RouteLeg.ModeEnum.TRANSIT, RouteLeg.ModeEnum.WALK, RouteLeg.ModeEnum.WALK);
      assertThat(leg.getTotalMinutes()).isEqualTo(25);
      assertThat(leg.getWalkMeters()).isEqualTo(700);
    }

    @Test
    @DisplayName("계단은 안내문에서 긁는다 — 대중교통 응답의 WALKING 에서만")
    void stairsFromGuidance() {
      KakaoTransitResponse.Route r =
          route(
              2000,
              1500,
              1,
              fare(1200),
              bus("a", 800, 300, 3, BUS_PATH),
              transferWalkWithStairs(100, 120),
              bus("b", 800, 300, 3, BUS_PATH));
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", r));
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      assertThat(leg.getLegs()).filteredOn(RouteLeg::getHasStairs).hasSize(1);
      assertThat(leg.getLegs())
          .filteredOn(RouteLeg::getHasStairs)
          .first()
          .extracting(RouteLeg::getGuidance)
          .asString()
          .contains("계단");
    }
  }

  @Nested
  @DisplayName("대중교통이 없으면 걷는다 — 상한 없이")
  class Fallback {

    @Test
    @DisplayName("NO_RESULTS 면 도보로, 그것도 없으면 422 ROUTE_NOT_FOUND")
    void noResultsFallsBackToWalk() {
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("NO_RESULTS"));
      when(kakao.walk(eq(HERE), eq(FAR), anyString())).thenReturn(anyWalk());

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);
      assertThat(leg.getTransfers()).isZero();
      assertThat(leg.getLegs()).allMatch(l -> l.getMode() == RouteLeg.ModeEnum.WALK);

      when(kakao.walk(any(), any(), anyString())).thenReturn(walk("NO_RESULTS"));
      assertThatThrownBy(() -> planner.plan(HERE, FAR, Lang.KO))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ROUTE_NOT_FOUND"));
    }

    @Test
    @DisplayName("정류장이 없어도 걷는다 — 걷는 길도 없으면 422 NO_TRANSIT_NEARBY 로 갈린다")
    void nodesNullFallsBackWithDistinctCode() {
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("STARTNODES_NULL"));
      when(kakao.walk(any(), any(), anyString())).thenReturn(walk("NO_RESULTS"));

      assertThatThrownBy(() -> planner.plan(HERE, FAR, Lang.KO))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getCode()).isEqualTo("NO_TRANSIT_NEARBY");
              });
    }

    @Test
    @DisplayName("같은 점이면 오류가 아니라 빈 legs — 이미 도착")
    void equalPointsIsArrived() {
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("EQUAL_POINTS"));

      NextLeg leg = planner.plan(HERE, FAR, Lang.KO);

      assertThat(leg.getLegs()).isEmpty();
      assertThat(leg.getTotalMinutes()).isZero();
      assertThat(leg.getWalkMeters()).isZero();
      verify(kakao, never()).walk(any(), any(), anyString());
    }

    @Test
    @DisplayName("모르는 상태값은 우리 결함 — 500 그물로")
    void unknownStatusIsIllegalState() {
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("INVALID_REQUEST"));

      assertThatThrownBy(() -> planner.plan(HERE, FAR, Lang.KO))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  @DisplayName("언어")
  class Language {

    @Test
    @DisplayName("ko 는 ko 로, 그 외는 en 으로 카카오에 보내고 guidanceLang 에 찍는다")
    void langMapsToKakaoAndGuidanceLang() {
      when(kakao.transit(any(), any(), anyString())).thenReturn(transit("OK", busOnly()));
      when(kakao.walk(any(), any(), anyString())).thenReturn(anyWalk());

      assertThat(planner.plan(HERE, FAR, Lang.KO).getGuidanceLang())
          .isEqualTo(NextLeg.GuidanceLangEnum.KO);
      verify(kakao).transit(any(), any(), eq("ko"));

      assertThat(planner.plan(HERE, FAR, Lang.JA).getGuidanceLang())
          .isEqualTo(NextLeg.GuidanceLangEnum.EN);
      verify(kakao).transit(any(), any(), eq("en"));
      verify(kakao, times(2)).walk(any(), any(), eq("en")); // 기운 도보도 같은 언어로
    }
  }

  @Test
  @DisplayName("도보 step 은 계단을 모른다 — false 이지 없음이 아니다")
  void walkStepsNeverClaimStairs() {
    when(kakao.walk(any(), any(), anyString()))
        .thenReturn(walk(100, 100, walkStep("계단이라는 이름의 카페까지 100m", 100, 100, points())));

    NextLeg leg = planner.plan(HERE, NEAR, Lang.KO);

    assertThat(leg.getLegs().get(0).getHasStairs())
        .as("도보 응답은 계단 정보가 없어 문자열이 우연히 맞아도 안 긁는다")
        .isFalse();
  }
}
