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
   * 장면 설명은 작품과 장소 <b>사이</b>에 붙는 유일한 텍스트다.
   *
   * <p>"2화에서 지은탁이 등장하는 버스정류장" 처럼 어느 작품의 어느 장면이 여기서 찍혔는지를 적은 문장이라 작품에도 장소에도 속하지 않는다. 그래서 걸리면 양쪽이 함께
   * 차야 한다.
   *
   * <p>이 분기가 늦게 붙은 이유가 있다 — 검색이 훑던 설명 컬럼 둘({@code place_i18n}·{@code content_i18n})은 값이 하나도 없고, 값이
   * 꽉 찬 것은 이 표뿐인데 그쪽을 보지 않았다. 그래서 <b>설명 검색이 통째로 죽어 있어도 게이트는 내내 초록이었다.</b>
   */
  @Test
  @DisplayName("장면 설명에만 있는 말로 검색해도 양쪽 탭이 찬다")
  void sceneDescriptionFillsBothTabs() {
    String q = IntegrationDatabase.anySceneOnlyWord(jdbc);

    assertThat(contentTotal(q)).as("작품 탭 — '%s' 장면이 나온 작품", q).isPositive();
    assertThat(placeTotal(q)).as("장소 탭 — '%s' 장면을 찍은 장소", q).isPositive();
  }

  /**
   * 자동완성은 여기까지 오지 않는다.
   *
   * <p>장면 설명은 값이 차 있어 {@code search_term} 에 넣고 싶어지는 자리다. 넣으면 제안 목록에 "2화에서 … 버스정류장입니다" 가 문장째로 뜬다 — 한
   * 줄짜리 제안으로 쓸 수 없다. 계약이 약속한 경계이므로 여기서 못 박는다.
   */
  @Test
  @DisplayName("그 말로 자동완성을 부르면 아무것도 제안하지 않는다")
  void sceneDescriptionNeverSuggests() {
    String q = IntegrationDatabase.anySceneOnlyWord(jdbc);

    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM search_term
                    WHERE term_norm LIKE '%' || search_normalize(:q) || '%'
                    """)
                .param("q", q)
                .query(Long.class)
                .single())
        .as("search_term 에 '%s' 가 들어가면 제안 목록이 문장으로 오염된다", q)
        .isZero();
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
