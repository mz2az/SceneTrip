package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link PoiNaverStore} — UPSERT · 판 무시 · 배열 왕복 · CHECK 제약을 진짜 DB 에서 본다. 넣은 행은 테스트가 끝날 때 지운다. */
@DisplayName("PoiNaverStore — 실제 DB 질의")
class PoiNaverStoreIntegrationTest {
  private static JdbcClient jdbc;
  private static PoiNaverStore store;
  private static long poiA;
  private static long poiB;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requirePoiSeeded(jdbc);
    store = new PoiNaverStore(jdbc);
    List<Long> ids = jdbc.sql("SELECT id FROM poi ORDER BY id LIMIT 2").query(Long.class).list();
    poiA = ids.get(0);
    poiB = ids.get(1);
  }

  @AfterEach
  void cleanup() {
    jdbc.sql("DELETE FROM poi_naver WHERE poi_id IN (:ids)")
        .param("ids", List.of(poiA, poiB))
        .update();
  }

  @Test
  @DisplayName("못 찾은 결과도 저장되고, 같은 판으로 조회된다")
  void savesNotFound() {
    store.save(NaverCard.notFound(poiA, "일치하는 장소가 없다", "v3"));

    Optional<NaverCard> got = store.find(poiA, "v3");
    assertThat(got).isPresent();
    assertThat(got.get().found()).isFalse();
    assertThat(got.get().why()).isEqualTo("일치하는 장소가 없다");
    assertThat(got.get().checkedAt()).isNotNull();
    assertThat(got.get().images()).isEmpty();
  }

  @Test
  @DisplayName("찾은 결과 — 사진 배열·별점이 왕복하고, 다시 저장하면 덮어쓴다")
  void savesFoundAndOverwrites() {
    store.save(NaverCard.notFound(poiA, "처음엔 못 찾음", "v3"));
    store.save(
        found(poiA, 4.39, List.of("https://img.example/1.jpg", "https://img.example/2.jpg")));

    NaverCard got = store.find(poiA, "v3").orElseThrow();
    assertThat(got.found()).isTrue();
    assertThat(got.why()).isNull();
    assertThat(got.naverId()).isEqualTo("1234567");
    assertThat(got.score()).isEqualTo(4.39);
    assertThat(got.images())
        .containsExactly("https://img.example/1.jpg", "https://img.example/2.jpg");
    assertThat(
            jdbc.sql("SELECT count(*) FROM poi_naver WHERE poi_id = :id")
                .param("id", poiA)
                .query(Long.class)
                .single())
        .as("UPSERT — 행은 하나뿐")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("별점이 없으면 NULL 로 남는다 — 0 으로 채우지 않는다")
  void keepsMissingScoreNull() {
    store.save(found(poiA, null, List.of()));

    assertThat(store.find(poiA, "v3").orElseThrow().score()).isNull();
  }

  @Test
  @DisplayName("옛 판의 행은 없는 것처럼 보인다")
  void ignoresOtherRuleVersion() {
    store.save(NaverCard.notFound(poiA, "옛 규칙의 판정", "v2"));

    assertThat(store.find(poiA, "v3")).isEmpty();
    assertThat(store.find(poiA, "v2")).isPresent();
  }

  @Test
  @DisplayName("여럿 조회 — 있는 것만 담기고 없는 id 는 키가 없다")
  void findsAllPresentOnly() {
    store.save(found(poiA, 4.0, List.of()));

    Map<Long, NaverCard> got = store.findAll(List.of(poiA, poiB, -1L), "v3");
    assertThat(got).containsOnlyKeys(poiA);
  }

  @Test
  @DisplayName("찾았다면서 id 가 없는 행은 CHECK 가 막는다")
  void checkConstraintRejectsInconsistentRow() {
    NaverCard broken =
        new NaverCard(
            poiA, true, null, "v3", null, null, "이름만", null, null, null, null, null, null, null,
            List.of(), null);

    assertThatThrownBy(() -> store.save(broken))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static NaverCard found(long poiId, Double score, List<String> images) {
    return new NaverCard(
        poiId,
        true,
        null,
        "v3",
        null,
        "1234567",
        "명동교자본점",
        "칼국수,만두",
        "서울 중구 명동10길 29",
        "02-776-5348",
        "매일 10:30 - 21:00",
        score,
        40408,
        12000,
        images,
        "https://map.naver.com/p/entry/place/1234567");
  }
}
