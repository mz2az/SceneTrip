package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Detail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Outcome;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardFetcher.Fetched;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 출처에 한 번 묻는 순서 — 검색 → 고르기 → 재검색 → 상세. 클라이언트는 가짜. */
@DisplayName("PoiCardFetcher — 출처에 묻는 순서")
class PoiCardFetcherTest {

  private static final long ID = 42L;
  private static final double LAT = 37.4375;
  private static final double LNG = 126.7819;

  private NaverPlaceClient client;
  private PoiCardFetcher fetcher;

  private static PoiDetail poi() {
    return new PoiDetail(ID, "정아각 본점[중식]", "중식", PoiCategoryGroup.FOOD, LAT, LNG)
        .region("경기")
        .city("시흥시");
  }

  private static Candidate near(String id, String name) {
    return new Candidate(id, name, LAT + 0.0001, LNG); // 약 11 m
  }

  private static Candidate farAway(String id, String name) {
    return new Candidate(id, name, LAT + 1.0, LNG); // 약 111 km
  }

  private static Detail detail() {
    return new Detail(
        "정아각본점",
        "중식당",
        "경기 시흥시 신천6길 1",
        "031-313-2727",
        "매일 11:00 - 21:00",
        4.4,
        1693,
        200,
        List.of("https://img.example/a.jpg"),
        "https://map.naver.com/p/entry/place/5784380");
  }

  @BeforeEach
  void setUp() {
    client = mock(NaverPlaceClient.class);
    fetcher = new PoiCardFetcher(client);
  }

  @Test
  @DisplayName("검색 → 고르기 → 상세 — 찾은 카드")
  void fetchesFound() {
    when(client.search("정아각 본점[중식]")).thenReturn(Outcome.ok(List.of(near("5784380", "정아각 본점"))));
    when(client.detail("5784380")).thenReturn(Outcome.ok(detail()));

    Fetched f = fetcher.fetch(poi());

    assertThat(f.received()).isTrue();
    assertThat(f.card().found()).isTrue();
    assertThat(f.card().naverId()).isEqualTo("5784380");
    assertThat(f.card().ruleVersion()).isEqualTo(NaverMatcher.RULE_VERSION);
    assertThat(f.card().score()).isEqualTo(4.4);
    assertThat(f.card().images()).containsExactly("https://img.example/a.jpg");
  }

  @Test
  @DisplayName("후보가 전부 안 맞으면 「없음」 — 상세는 안 부른다")
  void notFound() {
    when(client.search(anyString())).thenReturn(Outcome.ok(List.of(near("1", "완전히 다른 가게"))));

    Fetched f = fetcher.fetch(poi());

    assertThat(f.received()).isTrue();
    assertThat(f.card().found()).isFalse();
    assertThat(f.card().why()).isNotBlank();
    verify(client, never()).detail(anyString());
  }

  @Test
  @DisplayName("1 등이 far 밖이면 「이름 지역 시군구」로 한 번 더")
  void retriesWithDistrict() {
    when(client.search("정아각 본점[중식]")).thenReturn(Outcome.ok(List.of(farAway("9", "정아각 본점"))));
    when(client.search("정아각 본점[중식] 경기 시흥시"))
        .thenReturn(Outcome.ok(List.of(near("5784380", "정아각 본점"))));
    when(client.detail("5784380")).thenReturn(Outcome.ok(detail()));

    Fetched f = fetcher.fetch(poi());

    assertThat(f.card().found()).isTrue();
    verify(client).search("정아각 본점[중식] 경기 시흥시");
  }

  @Test
  @DisplayName("후보 0 건이어도 한 번 더 — 그래도 없으면 「없음」")
  void retriesOnEmpty() {
    when(client.search(anyString())).thenReturn(Outcome.ok(List.of()));

    Fetched f = fetcher.fetch(poi());

    verify(client).search("정아각 본점[중식] 경기 시흥시");
    assertThat(f.card().found()).isFalse();
  }

  @Test
  @DisplayName("가까운 후보가 있는데 이름이 다르면 재검색하지 않는다")
  void noRetryWhenNearButDifferent() {
    when(client.search(anyString())).thenReturn(Outcome.ok(List.of(near("1", "옆집 국밥"))));

    fetcher.fetch(poi());

    verify(client, never()).search("정아각 본점[중식] 경기 시흥시");
  }

  @Test
  @DisplayName("검색 실패는 「못 받음」 — 카드가 없고 blocked 가 전달된다")
  void transientFailure() {
    when(client.search(anyString())).thenReturn(Outcome.failed("HTTP 429", true));

    Fetched f = fetcher.fetch(poi());

    assertThat(f.received()).isFalse();
    assertThat(f.transientWhy()).isEqualTo("HTTP 429");
    assertThat(f.blocked()).isTrue();
  }

  @Test
  @DisplayName("상세 실패도 「못 받음」 — 다음에 다시 묻는다")
  void detailFailure() {
    when(client.search(anyString())).thenReturn(Outcome.ok(List.of(near("5784380", "정아각 본점"))));
    when(client.detail("5784380")).thenReturn(Outcome.failed("타임아웃", false));

    Fetched f = fetcher.fetch(poi());

    assertThat(f.received()).isFalse();
    assertThat(f.blocked()).isFalse();
  }
}
