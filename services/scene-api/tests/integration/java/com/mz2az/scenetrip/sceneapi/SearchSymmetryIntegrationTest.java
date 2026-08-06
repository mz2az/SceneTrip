package com.mz2az.scenetrip.sceneapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.content.ContentStore;
import com.mz2az.scenetrip.sceneapi.content.ContentStores;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStores;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 검색어 하나가 두 탭을 모두 채우는가.
 *
 * <p>이 저장소가 실제로 겪은 결함을 막는 테스트다. {@code ContentStore} 에 "장소 -> 그 장소의 촬영작" 분기가 없어서, 장소 이름으로 검색하면 장소
 * 탭만 차고 작품 탭이 비어 있었다. 단위 테스트는 Store 를 가짜로 바꿔 끼우므로 아무것도 눈치채지 못했고, 게이트는 내내 초록이었다.
 *
 * <p>두 Store 의 매칭 규칙이 각자의 SQL 에 나뉘어 적혀 있는 한 이 어긋남은 언제든 다시 생긴다. 검색 대상을 하나 늘릴 때 한쪽만 고치면 그만이기 때문이다.
 * <b>여기서 잡는다.</b>
 *
 * <p>입력을 코드에 박지 않고 DB 에서 뽑아 쓴다. '북촌한옥마을' 같은 이름을 적어 두면 적재 데이터가 바뀔 때마다 테스트가 깨지는데, 그것은 결함이 아니라 잡음이다.
 */
@DisplayName("검색 대칭 — 검색어 하나가 작품 탭과 장소 탭을 모두 채운다")
class SearchSymmetryIntegrationTest {

  private static JdbcClient jdbc;
  private static PlaceStore places;
  private static ContentStore contents;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    places = PlaceStores.create(jdbc);
    contents = ContentStores.create(jdbc);
  }

  @Test
  @DisplayName("장소 이름으로 검색하면 그 장소와 거기서 촬영된 작품이 모두 나온다")
  void placeNameFillsBothTabs() {
    String q = IntegrationDatabase.anyPlaceNameWithContent(jdbc);

    assertThat(placeTotal(q)).as("장소 탭 — '%s'", q).isPositive();
    assertThat(contentTotal(q)).as("작품 탭 — '%s' 에서 촬영된 작품", q).isPositive();
  }

  @Test
  @DisplayName("작품 이름으로 검색하면 그 작품과 촬영지가 모두 나온다")
  void contentTitleFillsBothTabs() {
    String q = IntegrationDatabase.anyContentTitleWithPlace(jdbc);

    assertThat(contentTotal(q)).as("작품 탭 — '%s'", q).isPositive();
    assertThat(placeTotal(q)).as("장소 탭 — '%s' 의 촬영지", q).isPositive();
  }

  @Test
  @DisplayName("인물 이름으로 검색하면 출연작과 그 촬영지가 모두 나온다")
  void personNameFillsBothTabs() {
    String q = IntegrationDatabase.anyPersonNameWithPlace(jdbc);

    assertThat(contentTotal(q)).as("작품 탭 — '%s' 의 출연작", q).isPositive();
    assertThat(placeTotal(q)).as("장소 탭 — '%s' 출연작의 촬영지", q).isPositive();
  }

  /**
   * 걸리지 않는 검색어는 양쪽 모두 0 이어야 한다.
   *
   * <p>위 세 개만 있으면 "무엇이든 다 돌려주는" 구현도 통과한다. 반대 방향을 함께 봐야 단언에 뜻이 생긴다.
   */
  @Test
  @DisplayName("걸리는 것이 없으면 양쪽 모두 빈다")
  void unmatchedQueryFillsNeither() {
    String q = "존재하지않는검색어" + Long.toHexString(0x5CE7E7217L);

    assertThat(contentTotal(q)).isZero();
    assertThat(placeTotal(q)).isZero();
  }

  private int contentTotal(String q) {
    return contents.list(q, null, null, Lang.KO, 50, 0).total();
  }

  private int placeTotal(String q) {
    return places
        .list(
            new PlaceStore.Criteria(
                q, null, null, null, null, null, PlaceStore.Sort.POPULARITY, Lang.KO, 50, 0))
        .total();
  }
}
