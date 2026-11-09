package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.place.CityRules.CityRef;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * 주소 → 도시 규칙(계약 1.26.0 {@code GET /places/cities}, MZ2AZ-545).
 *
 * <p>사례는 웹 규칙 시험 {@code apps/scenetrip-web/src/features/home/homeCities.test.ts}(MZ2AZ-531)의 {@code
 * cityOfAddress}· {@code englishCityName} 사례를 그대로 옮겼다 — 웹과 서버의 규칙이 갈라지지 않게 한다. 웹의 {@code
 * orderCities} 순서 규칙은 도시 수를 세는 SQL 과 함께 통합 레인({@code PlaceCitiesIntegrationTest})이 본다.
 */
@DisplayName("CityRules — 한국어 주소의 도시")
class CityRulesTest {

  private static CityRef city(String address) {
    return CityRules.cityOf(address).orElseThrow(() -> new AssertionError("도시가 없다: " + address));
  }

  @Nested
  @DisplayName("cityOf — 한국어 주소의 도시")
  class CityOf {

    @TestFactory
    @DisplayName("주소 → 한국어 이름")
    Stream<DynamicTest> koreanName() {
      return Stream.of(
              new String[] {"서울특별시 종로구 낙산성곽서1길 12", "서울"},
              new String[] {"부산광역시 해운대구 우동", "부산"},
              new String[] {"인천광역시 강화군 강화읍", "인천"},
              new String[] {"대구광역시 달서구", "대구"},
              new String[] {"세종특별자치시 다솜로 216", "세종"},
              new String[] {"제주특별자치도 서귀포시 안덕면", "제주"},
              new String[] {"제주특별자치도 제주시 애월읍", "제주"},
              new String[] {"경상북도 경주시 첨성로", "경주"},
              new String[] {"경기도 수원시 팔달구 정조로", "수원"},
              new String[] {"강원특별자치도 강릉시 주문진읍", "강릉"},
              new String[] {"충청남도 부여군 규암면", "부여"},
              new String[] {"전북특별자치도 전주시 완산구", "전주"})
          .map(
              c ->
                  DynamicTest.dynamicTest(
                      c[0] + " → " + c[1], () -> assertThat(city(c[0]).ko()).isEqualTo(c[1])));
    }

    @Test
    @DisplayName("특별시·광역시·제주는 통째로 한 열쇠다 — 구·시가 달라도 같다")
    void wholeCitiesShareOneKey() {
      assertThat(city("서울특별시 종로구").key()).isEqualTo(city("서울특별시 강남구 역삼동").key());
      assertThat(city("제주특별자치도 서귀포시").key()).isEqualTo(city("제주특별자치도 제주시").key());
    }

    @Test
    @DisplayName("열쇠는 한국어 이름이다 — /places?city= 에 그대로 보내는 값")
    void keyIsKoreanName() {
      assertThat(city("서울특별시 종로구").key()).isEqualTo("서울");
      assertThat(city("경상북도 경주시 첨성로").key()).isEqualTo("경주");
    }

    @Test
    @DisplayName("도는 시·군으로 나뉜다")
    void provincesSplitIntoCities() {
      assertThat(city("경기도 수원시").key()).isNotEqualTo(city("경기도 파주시").key());
    }

    @Test
    @DisplayName("경기도 광주시는 광주광역시와 다른 열쇠다 — 도를 붙인다")
    void gyeonggiGwangjuIsNotMetroGwangju() {
      assertThat(city("경기도 광주시 퇴촌면").key()).isNotEqualTo(city("광주광역시 동구").key());
      assertThat(city("경기도 광주시 퇴촌면").key()).isEqualTo("경기 광주");
      assertThat(city("광주광역시 동구").key()).isEqualTo("광주");
    }

    @Test
    @DisplayName("강원 고성·경남 고성은 서로 다른 열쇠다")
    void twoGoseongsDiffer() {
      assertThat(city("강원특별자치도 고성군 토성면").key()).isNotEqualTo(city("경상남도 고성군 고성읍").key());
    }

    @Test
    @DisplayName("전남광주통합특별시는 시·군으로 나누고, 구로 시작하면 광주다")
    void unifiedSpecialCity() {
      assertThat(city("전남광주통합특별시 순천시 송광면").ko()).isEqualTo("순천");
      assertThat(city("전남광주통합특별시 동구 문화전당로").ko()).isEqualTo("광주");
      assertThat(city("전남광주통합특별시 동구").key()).isEqualTo(city("광주광역시 동구").key());
    }

    @Test
    @DisplayName("주소가 없거나 모르는 모양이면 빈 값")
    void unknownIsEmpty() {
      assertThat(CityRules.cityOf(null)).isEmpty();
      assertThat(CityRules.cityOf("")).isEmpty();
      assertThat(CityRules.cityOf("Tokyo, Japan")).isEmpty();
    }
  }

  @Nested
  @DisplayName("englishName — 영어 이름")
  class EnglishName {

    @Test
    @DisplayName("광역 도시는 표에서")
    void metroFromTable() {
      assertThat(CityRules.englishName(city("서울특별시 종로구"), "12 Naksan, Jongno-gu, Seoul"))
          .isEqualTo("Seoul");
      assertThat(CityRules.englishName(city("제주특별자치도 서귀포시"), "1 Jungmun, Seogwipo-si, Jeju-do"))
          .isEqualTo("Jeju");
    }

    @Test
    @DisplayName("시·군은 영어 주소의 -si·-gun 을 뗀다")
    void cityFromEnglishAddress() {
      assertThat(
              CityRules.englishName(
                  city("경상북도 경주시"), "1 Cheomseong-ro, Gyeongju-si, Gyeongsangbuk-do"))
          .isEqualTo("Gyeongju");
      assertThat(CityRules.englishName(city("충청남도 부여군"), "5 Gyuam, Buyeo-gun, Chungcheongnam-do"))
          .isEqualTo("Buyeo");
    }

    @Test
    @DisplayName("도를 붙인 열쇠는 영어에서도 도로 구분한다 — 광주·경기 광주, 강원·경남 고성이 겹치지 않는다")
    void ambiguousNamesCarryProvince() {
      assertThat(CityRules.englishName(city("광주광역시 동구"), "38 Munhwajeondang-ro, Dong-gu, Gwangju"))
          .isEqualTo("Gwangju");
      assertThat(
              CityRules.englishName(
                  city("경기도 광주시 퇴촌면"), "1 Toechon-ro, Toechon-myeon, Gwangju-si, Gyeonggi-do"))
          .isEqualTo("Gwangju (Gyeonggi)");
      assertThat(
              CityRules.englishName(
                  city("강원특별자치도 고성군 토성면"),
                  "31-6 Ayajinbuk-gil, Toseong-myeon, Goseong-gun, Gangwon-do"))
          .isEqualTo("Goseong (Gangwon)");
      assertThat(
              CityRules.englishName(
                  city("경상남도 고성군 고성읍"), "1 Songhak-ro, Goseong-eup, Goseong-gun, Gyeongsangnam-do"))
          .isEqualTo("Goseong (Gyeongnam)");
    }

    @Test
    @DisplayName("영어 주소가 없으면 한국어 이름")
    void fallsBackToKorean() {
      assertThat(CityRules.englishName(city("경기도 수원시"), null)).isEqualTo("수원");
    }
  }

  @Test
  @DisplayName("고정 순서는 서울·부산·경주·제주·인천 — 제주가 인천 앞(사용자 결정 2026-11-09)")
  void fixedCitiesOrder() {
    assertThat(CityRules.FIXED_CITIES).containsExactly("서울", "부산", "경주", "제주", "인천");
  }
}
