package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCard;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCardBatch;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Detail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Outcome;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 표·매처·클라이언트를 잇는 순서. 전부 가짜 — 네이버도 DB 도 없다. */
@DisplayName("PoiCardService — 한 POI 를 처리하는 순서")
class PoiCardServiceTest {

  private static final long ID = 42L;
  private static final double LAT = 37.4375;
  private static final double LNG = 126.7819;

  private PoiStore pois;
  private PoiNaverStore store;
  private NaverPlaceClient client;
  private CardFiller filler;
  private PoiCardService service;

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

  private static NaverCard cachedFound() {
    return new NaverCard(
        ID,
        true,
        null,
        NaverMatcher.RULE_VERSION,
        OffsetDateTime.now(),
        "5784380",
        "정아각본점",
        "중식당",
        "주소",
        "전화",
        "시간",
        4.4,
        10,
        2,
        List.of("https://img.example/a.jpg"),
        "https://map.naver.com/p/entry/place/5784380");
  }

  @BeforeEach
  void setUp() {
    pois = mock(PoiStore.class);
    store = mock(PoiNaverStore.class);
    client = mock(NaverPlaceClient.class);
    filler = mock(CardFiller.class);
    service = new PoiCardService(pois, store, client, Optional.of(filler));
    when(pois.findDetail(eq(ID), any(), any())).thenReturn(Optional.of(poi()));
    when(filler.retryAfterSeconds()).thenReturn(7);
  }

  @Test
  @DisplayName("표에 있으면 출처를 부르지 않는다")
  void servesFromCache() {
    when(store.find(ID, NaverMatcher.RULE_VERSION)).thenReturn(Optional.of(cachedFound()));

    PoiCard card = service.card(ID).orElseThrow();

    assertThat(card.getFound()).isTrue();
    assertThat(card.getName()).isEqualTo("정아각본점");
    assertThat(card.getNaverUrl().toString()).endsWith("/5784380");
    assertThat(card.getImages()).hasSize(1);
    verify(client, never()).search(anyString());
  }

  @Test
  @DisplayName("없으면 검색 → 고르기 → 상세 → 저장 — 그리고 카드")
  void fetchesAndSaves() {
    when(store.find(ID, NaverMatcher.RULE_VERSION)).thenReturn(Optional.empty());
    when(client.search("정아각 본점[중식]")).thenReturn(Outcome.ok(List.of(near("5784380", "정아각 본점"))));
    when(client.detail("5784380")).thenReturn(Outcome.ok(detail()));

    PoiCard card = service.card(ID).orElseThrow();

    ArgumentCaptor<NaverCard> saved = ArgumentCaptor.forClass(NaverCard.class);
    verify(store).save(saved.capture());
    assertThat(saved.getValue().found()).isTrue();
    assertThat(saved.getValue().naverId()).isEqualTo("5784380");
    assertThat(saved.getValue().ruleVersion()).isEqualTo(NaverMatcher.RULE_VERSION);
    assertThat(saved.getValue().score()).isEqualTo(4.4);
    assertThat(card.getFound()).isTrue();
    assertThat(card.getReviewCount()).isEqualTo(1693);
  }

  @Test
  @DisplayName("후보가 전부 안 맞으면 「없음」으로 저장한다 — 다시 묻지 않게")
  void savesNotFound() {
    when(store.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(client.search(anyString())).thenReturn(Outcome.ok(List.of(near("1", "완전히 다른 가게"))));

    PoiCard card = service.card(ID).orElseThrow();

    ArgumentCaptor<NaverCard> saved = ArgumentCaptor.forClass(NaverCard.class);
    verify(store).save(saved.capture());
    assertThat(saved.getValue().found()).isFalse();
    assertThat(saved.getValue().why()).isNotBlank();
    assertThat(card.getFound()).isFalse();
    assertThat(card.getWhy()).isEqualTo(saved.getValue().why());
    verify(client, never()).detail(anyString());
  }

  @Test
  @DisplayName("1 등이 far 밖이면 「이름 지역 시군구」로 한 번 더 검색한다")
  void retriesWithDistrictWhenWanderedOff() {
    when(store.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(client.search("정아각 본점[중식]")).thenReturn(Outcome.ok(List.of(farAway("9", "정아각 본점"))));
    when(client.search("정아각 본점[중식] 경기 시흥시"))
        .thenReturn(Outcome.ok(List.of(near("5784380", "정아각 본점"))));
    when(client.detail("5784380")).thenReturn(Outcome.ok(detail()));

    PoiCard card = service.card(ID).orElseThrow();

    assertThat(card.getFound()).isTrue();
    verify(client).search("정아각 본점[중식] 경기 시흥시");
  }

  @Test
  @DisplayName("후보가 0 건이어도 시군구로 한 번 더 — 그래도 없으면 「없음」")
  void retriesOnEmptyThenGivesUp() {
    when(store.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(client.search(anyString())).thenReturn(Outcome.ok(List.of()));

    PoiCard card = service.card(ID).orElseThrow();

    verify(client).search("정아각 본점[중식] 경기 시흥시");
    assertThat(card.getFound()).isFalse();
    verify(store).save(any());
  }

  @Test
  @DisplayName("가까운 후보가 있는데 이름이 다르면 재검색하지 않는다 — 검색어 문제가 아니다")
  void doesNotRetryWhenNearButDifferent() {
    when(store.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(client.search(anyString())).thenReturn(Outcome.ok(List.of(near("1", "옆집 국밥"))));

    service.card(ID);

    verify(client, never()).search("정아각 본점[중식] 경기 시흥시");
  }

  @Test
  @DisplayName("검색이 실패하면 저장하지 않고 found=false 로 답한다 — 다음에 다시 묻는다")
  void transientFailureIsNotSaved() {
    when(store.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(client.search(anyString())).thenReturn(Outcome.failed("타임아웃", false));

    PoiCard card = service.card(ID).orElseThrow();

    verify(store, never()).save(any());
    assertThat(card.getFound()).isFalse();
    assertThat(card.getWhy()).isEqualTo("타임아웃");
    assertThat(card.getCheckedAt()).isNotNull();
    verify(filler, never()).noteBlocked();
  }

  @Test
  @DisplayName("막혔으면 일꾼에게 알린다")
  void blockedIsPassedToFiller() {
    when(store.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(client.search(anyString())).thenReturn(Outcome.failed("HTTP 429", true));

    service.card(ID);

    verify(filler).noteBlocked();
    verify(store, never()).save(any());
  }

  @Test
  @DisplayName("POI 가 없으면 비어 있다")
  void missingPoi() {
    when(pois.findDetail(eq(7L), any(), any())).thenReturn(Optional.empty());

    assertThat(service.card(7L)).isEmpty();
    verify(client, never()).search(anyString());
  }

  @Test
  @DisplayName("여럿 — 있는 것은 카드, 없는 것은 pending 으로 줄에, 없는 POI 는 found=false. 순서 유지")
  void batchMixes() {
    when(pois.existingIds(List.of(1L, 2L, 3L, 999L))).thenReturn(Set.of(1L, 2L, 3L));
    NaverCard one =
        new NaverCard(
            1L,
            true,
            null,
            NaverMatcher.RULE_VERSION,
            OffsetDateTime.now(),
            "n1",
            "하나",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            "https://map.naver.com/p/entry/place/n1");
    when(store.findAll(any(), eq(NaverMatcher.RULE_VERSION))).thenReturn(Map.of(1L, one));

    PoiCardBatch batch = service.cards(List.of(1L, 2L, 3L, 999L));

    assertThat(batch.getItems()).extracting(PoiCard::getPoiId).containsExactly(1L, 2L, 3L, 999L);
    assertThat(batch.getItems().get(0).getFound()).isTrue();
    assertThat(batch.getItems().get(1).getPending()).isTrue();
    assertThat(batch.getItems().get(2).getPending()).isTrue();
    assertThat(batch.getItems().get(3).getFound()).isFalse();
    assertThat(batch.getItems().get(3).getWhy()).contains("없다");
    assertThat(batch.getRetryAfterSeconds()).isEqualTo(7);
    verify(filler).enqueue(List.of(2L, 3L));
    verify(client, never()).search(anyString());
  }

  @Test
  @DisplayName("여럿 — 전부 표에 있으면 줄에 넣지 않고 힌트도 없다")
  void batchAllCached() {
    when(pois.existingIds(List.of(1L))).thenReturn(Set.of(1L));
    when(store.findAll(any(), anyString())).thenReturn(Map.of(1L, cachedFound()));

    PoiCardBatch batch = service.cards(List.of(1L));

    assertThat(batch.getRetryAfterSeconds()).isNull();
    verify(filler, never()).enqueue(any());
  }

  @Test
  @DisplayName("일꾼이 없으면 pending 만 답하고 힌트는 상한 30")
  void batchWithoutFiller() {
    PoiCardService noFiller = new PoiCardService(pois, store, client, Optional.empty());
    when(pois.existingIds(List.of(2L))).thenReturn(Set.of(2L));
    when(store.findAll(any(), anyString())).thenReturn(Map.of());

    PoiCardBatch batch = noFiller.cards(List.of(2L));

    assertThat(batch.getItems().get(0).getPending()).isTrue();
    assertThat(batch.getRetryAfterSeconds()).isEqualTo(30);
  }
}
