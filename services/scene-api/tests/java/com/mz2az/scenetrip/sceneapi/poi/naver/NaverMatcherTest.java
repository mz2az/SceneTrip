package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Match;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 판정 규칙을 표로 고정한다. 케이스 대부분은 프로토타입이 사고로 배운 것(짧은 이름, 대괄호 노선)과 2026-09-03 실측에서 고른 것이다. 규칙을 고칠 때 이 표가
 * 무엇이 바뀌는지 보여 준다.
 */
@DisplayName("NaverMatcher — 같은 곳인가")
class NaverMatcherTest {

  /** (우리 이름, 네이버 이름, 거리 m, 갈래, 기대). */
  private record Case(
      String tmap, String naver, Integer meters, PoiCategoryGroup group, boolean ok, String note) {}

  private static final List<Case> TABLE =
      List.of(
          new Case("명동교자 본점", "명동교자본점", 40, PoiCategoryGroup.FOOD, true, "띄어쓰기만 다름 → exact"),
          new Case("온정 국밥 집", "온정국밥집", 10, PoiCategoryGroup.FOOD, true, "정규화"),
          new Case("커피", "노크 커피바 선릉", 15, PoiCategoryGroup.FOOD, false, "짧은 이름은 part 를 안 믿는다"),
          new Case("커피", "커피", 15, PoiCategoryGroup.FOOD, true, "짧아도 exact 는 통과"),
          new Case(
              "안국역[3호선]", "안국역 3호선", 20, PoiCategoryGroup.TRANSIT, true, "nameKeyFull — 대괄호 노선"),
          new Case("스타벅스 강남역점", "스타벅스 강남역", 40, PoiCategoryGroup.FOOD, true, "mid 안이면 part OK"),
          new Case("스타벅스 강남역점", "스타벅스 강남역", 120, PoiCategoryGroup.FOOD, false, "mid 넘으면 exact 만"),
          new Case("스타벅스 강남역점", "스타벅스 강남역점", 120, PoiCategoryGroup.FOOD, true, "far 안 exact"),
          new Case("스타벅스 강남역점", "스타벅스 강남역점", 200, PoiCategoryGroup.FOOD, false, "가게 far 초과"),
          new Case("스타벅스 강남역점", "스타벅스 강남대로점", 40, PoiCategoryGroup.FOOD, false, "가까워도 이름 다름"),
          new Case("경복궁", "경복궁", 500, PoiCategoryGroup.SIGHT, true, "명소는 far 800"),
          new Case("경복궁", "경복궁", 900, PoiCategoryGroup.SIGHT, false, "명소 far 초과"),
          new Case(
              "경복궁",
              "경복궁 돌담길",
              300,
              PoiCategoryGroup.SIGHT,
              false,
              "3글자는 명소여도 part 를 안 믿는다 — 돌담길은 다른 곳"),
          new Case(
              "국립중앙박물관", "국립중앙박물관 전시관", 300, PoiCategoryGroup.SIGHT, true, "명소 mid 400 안 part"),
          new Case("죽변역", "죽변역", 500, PoiCategoryGroup.TRANSIT, true, "역도 넓은 계단"),
          new Case("보성식당", "보성식당", 140_000, PoiCategoryGroup.FOOD, false, "다른 동네 지점"),
          new Case("모슬포호텔", "모슬포호텔", 500, PoiCategoryGroup.STAY, false, "숙박은 가게 계단"),
          new Case("<b>명동교자</b> 본점", "명동교자본점", 5, PoiCategoryGroup.FOOD, true, "HTML 태그 제거"),
          new Case("명동교자 본점", "명동교자본점", null, PoiCategoryGroup.FOOD, false, "좌표 없는 후보는 탈락"));

  @Test
  @DisplayName("규칙 표")
  void table() {
    for (Case c : TABLE) {
      NaverMatcher.Verdict v = NaverMatcher.matches(c.tmap(), c.naver(), c.meters(), c.group());
      assertThat(v.ok())
          .as(
              "%s / %s / %s m / %s — %s (why=%s)",
              c.tmap(), c.naver(), c.meters(), c.group(), c.note(), v.why())
          .isEqualTo(c.ok());
      if (!c.ok()) {
        assertThat(v.why()).as("탈락에는 이유가 있다: " + c.note()).isNotBlank();
      }
    }
  }

  @Test
  @DisplayName("정규화 — 한글이 지워지지 않는다 (UNICODE_CHARACTER_CLASS)")
  void normalizationKeepsKorean() {
    assertThat(NaverMatcher.nameKey("온정 국밥 집")).isEqualTo("온정국밥집");
    assertThat(NaverMatcher.nameKey("안국역[3호선]")).isEqualTo("안국역");
    assertThat(NaverMatcher.nameKeyFull("안국역[3호선]")).isEqualTo("안국역3호선");
    assertThat(NaverMatcher.nameKeyFull("안국역 3호선")).isEqualTo("안국역3호선");
    assertThat(NaverMatcher.nameKey("Cafe Étoile")).isEqualTo("cafeétoile");
    assertThat(NaverMatcher.nameKey(null)).isEmpty();
  }

  @Test
  @DisplayName("후보 고르기 — 1등이 다른 가게면 2등을 본다 (프로토타입과 다른 점)")
  void picksFirstPassingByDistance() {
    double lat = 37.5636;
    double lng = 126.9853;
    Candidate other = new Candidate("1", "명동할머니국수", lat + 0.00005, lng); // 약 6 m
    Candidate right = new Candidate("2", "명동교자본점", lat + 0.00015, lng); // 약 17 m
    Candidate far = new Candidate("3", "명동교자 강남점", lat + 0.06, lng); // 약 6.7 km

    Match m =
        NaverMatcher.pick("명동교자 본점", lat, lng, PoiCategoryGroup.FOOD, List.of(far, right, other));

    assertThat(m.found()).isTrue();
    assertThat(m.candidate().id()).isEqualTo("2");
    assertThat(m.distanceMeters()).isBetween(15, 20);
    assertThat(m.nearestMeters()).as("가장 가까운 건 1등(다른 가게)").isBetween(4, 8);
  }

  @Test
  @DisplayName("아무것도 안 맞으면 — 가장 가까운 후보의 이유와 그 거리를 돌려준다")
  void reportsNearestWhenNothingMatches() {
    double lat = 33.2177;
    double lng = 126.2506;
    Candidate branch = new Candidate("9", "보성식당", lat + 1.26, lng); // 약 140 km

    Match m = NaverMatcher.pick("보성식당", lat, lng, PoiCategoryGroup.FOOD, List.of(branch));

    assertThat(m.found()).isFalse();
    assertThat(m.nearestMeters()).isGreaterThan(100_000);
    assertThat(m.why()).contains("떨어졌다");
  }

  @Test
  @DisplayName("후보가 없으면 — 「일치하는 장소가 없다」, 거리도 없다")
  void noCandidates() {
    Match m = NaverMatcher.pick("아무데나", 37.5, 127.0, PoiCategoryGroup.FOOD, List.of());

    assertThat(m.found()).isFalse();
    assertThat(m.nearestMeters()).isNull();
    assertThat(m.why()).isEqualTo("일치하는 장소가 없다");
  }

  @Test
  @DisplayName("좌표 없는 후보는 뒤로 가고 통과하지 못한다")
  void candidatesWithoutCoordinatesLose() {
    Candidate noCoords = new Candidate("1", "명동교자본점", null, null);
    Match m =
        NaverMatcher.pick("명동교자 본점", 37.5636, 126.9853, PoiCategoryGroup.FOOD, List.of(noCoords));

    assertThat(m.found()).isFalse();
    assertThat(m.nearestMeters()).isNull();
    assertThat(m.why()).contains("좌표가 없어");
  }
}
