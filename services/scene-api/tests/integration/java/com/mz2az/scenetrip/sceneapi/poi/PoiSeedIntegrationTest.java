package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi.sql} 의 변환 규칙이 지켜졌는지 표에서 확인한다.
 *
 * <p>표본({@code seed/poi-sample.jsonl}, 29 행)은 공개 판 {@code poi-data-2026-06} 의 실제 줄이다. 전량
 * DB(로컬·PRD)도 같은 판을 같은 규칙으로 넣었으므로 표본 줄은 거기에도 그대로 있다 — 그래서 이 시험은 표본만 든 DB 와 전량 DB 양쪽에서 같은 결과여야 한다.
 * 단언은 표본 줄의 {@code source_id} 로만 하고, 표 전체 행 수에는 기대지 않는다.
 *
 * <p>표본 29 행 → POI 20 곳: 제외 분류 4 · 한국 밖 좌표 1 · 같은 이름·좌표로 접힘 3(상가정보끼리 · 상가정보→관광공사 · 교통끼리) · 관광공사 가리기
 * 1 이 빠진다. 규칙은 {@code docs/project/plans/poi-load-cleanup.md} §2 · {@code poi-source.md} §2-1.
 */
@DisplayName("POI 적재 — 변환 규칙")
class PoiSeedIntegrationTest {
  private static JdbcClient jdbc;

  /** 표본 29 행의 source_id 전부. */
  private static final List<String> SAMPLE_IDS =
      List.of(
          "MA010120220814275700",
          "MA010120220806284436",
          "MA010120220812600637",
          "MA010120220803339631",
          "MA010120220808415189",
          "tour-3031541",
          "tour-132161",
          "tour-2755023",
          "MA0101202504A0057604",
          "MA010120220812380222",
          "MA010120220804825580",
          "MA010120220803534826",
          "MA010120220803825714",
          "MA010120220810638010",
          "tour-2805408",
          "tour-2801736",
          "MA010120220813687905",
          "metro-1706_I4101",
          "train-안양역",
          "tour-2611568",
          "MA010120220808407445",
          "MA010120220805220038",
          "MA0106202201A0285079",
          "MA010120220806674974",
          "tour-2901530",
          "air-김포",
          "train-서울역",
          "hub-41273-안산역",
          "busstop-SJB270054312");

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requirePoiSeeded(jdbc);
  }

  @Test
  @DisplayName("표본 29 행은 POI 20 곳이 된다 — 제외 4 · 한국 밖 1 · 접힘 3 · 관광공사 가리기 1")
  void sampleBecomesTwentyPois() {
    assertThat(SAMPLE_IDS).hasSize(29).doesNotHaveDuplicates();
    assertThat(
            jdbc.sql("SELECT count(*) FROM poi WHERE source_id IN (:ids)")
                .param("ids", SAMPLE_IDS)
                .query(Long.class)
                .single())
        .isEqualTo(20);
  }

  @Test
  @DisplayName("갈래는 biz_middle 로 정해진다 — 음식점·카페·술집 food, 숙박 stay, 관광명소 sight, 교통시설 transit")
  void categoryGroupComesFromBizMiddle() {
    assertThat(groupOf("MA010120220812380222")).as("통통 — 음식점").isEqualTo("food");
    assertThat(groupOf("MA010120220804825580")).as("모슬포3호텔커피숍 — 카페").isEqualTo("food");
    assertThat(groupOf("MA010120220803534826")).as("욜로욜로 — 술집").isEqualTo("food");
    assertThat(groupOf("MA010120220814275700")).as("모슬포3호텔 — 숙박").isEqualTo("stay");
    assertThat(groupOf("tour-132161")).as("대정오일시장 — 관광명소(종류는 쇼핑)").isEqualTo("sight");
    assertThat(groupOf("air-김포")).as("김포공항 — 교통시설").isEqualTo("transit");
  }

  @Test
  @DisplayName("갈래는 네 값뿐이고 세부 종류(category)는 비어 있지 않다")
  void categoryGroupsAreTheFourAndCategoryIsFilled() {
    assertThat(
            count(
                "SELECT count(*) FROM poi WHERE category_group NOT IN"
                    + " ('food','stay','sight','transit')"))
        .isZero();
    assertThat(count("SELECT count(*) FROM poi WHERE category IS NULL OR category = ''")).isZero();
  }

  @Test
  @DisplayName("여행 앱에 보일 일이 없는 분류(유흥 주점 둘·구내식당·고시원)는 들어오지 않고, 이미 있던 것은 지우지 않고 숨긴다")
  void excludedCategoriesAreDropped() {
    assertThat(
            present(
                "MA010120220808407445", // 태양룸가요방 — 일반 유흥 주점
                "MA010120220805220038", // 퍼브 — 무도 유흥 주점
                "MA0106202201A0285079", // …원주구내식당 — 구내식당
                "MA010120220806674974")) // 말리니 — 기숙사/고시원
        .isEmpty();
    assertThat(
            count(
                "SELECT count(*) FROM poi WHERE closed_at IS NULL AND category IN"
                    + " ('일반 유흥 주점', '무도 유흥 주점', '구내식당', '기숙사/고시원')"))
        .as("표본만이 아니라 표 전체에서 — 열린 POI 중에는 없다(닫힌 채 남는 건 된다)")
        .isZero();
  }

  @Test
  @DisplayName("좌표가 한국 밖(북위 33~39·동경 124~132 밖)이면 들어오지 않는다")
  void outOfKoreaCoordinatesAreDropped() {
    // 계남근린공원 — 서울 주소인데 좌표가 북위 19.69·동경 117.99.
    assertThat(present("tour-2611568")).isEmpty();
  }

  @Test
  @DisplayName("상가정보끼리 이름·좌표가 같으면(욜로욜로 두 줄) 하나로 접힌다 — 다른 조건이 같으면 작은 번호가 남는다")
  void sameNameSameSpotStoreRowsCollapse() {
    assertThat(present("MA010120220803534826", "MA010120220803825714"))
        .containsExactly("MA010120220803534826");
  }

  @Test
  @DisplayName("좌표가 같아도 이름이 다르면 접히지 않는다(드림제주·아크로뷰호텔)")
  void sameSpotDifferentNameIsKept() {
    assertThat(present("MA010120220803339631", "MA010120220808415189")).hasSize(2);
  }

  @Test
  @DisplayName("출처가 달라도 이름·좌표(1 m)가 같으면 접힌다 — 전화·자루 분류가 같으면 짧은 번호(관광공사)가 남는다(횡계가정식집밥)")
  void sameNameSameSpotAcrossSourcesCollapse() {
    assertThat(present("tour-2801736", "MA010120220813687905")).containsExactly("tour-2801736");
  }

  @Test
  @DisplayName("교통끼리 이름·좌표가 같으면 접힌다 — 전화가 있는 쪽이 짧은 번호보다 앞선다(안양역)")
  void phoneBeatsShorterIdWhenCollapsing() {
    // train-안양역 이 더 짧지만 전화가 없다. metro-1706_I4101 은 1588-7788.
    assertThat(present("metro-1706_I4101", "train-안양역")).containsExactly("metro-1706_I4101");
  }

  @Test
  @DisplayName("관광공사 음식점이 100 m 안 같은 갈래·이름 포함의 상가정보와 겹치면 상가정보만 남는다(가가와)")
  void tourRowShadowedByStoreRowIsDropped() {
    // 두 줄은 약 1 m 어긋나 소수 5자리에서 갈린다 — 1 m 접기가 아니라 100 m 가리기에 걸리는 쌍.
    assertThat(present("MA010120220810638010", "tour-2805408"))
        .containsExactly("MA010120220810638010");
  }

  @Test
  @DisplayName("상가정보에 없는 관광공사 음식점은 들어온다(가경목장)")
  void tourRowWithoutStoreMatchIsKept() {
    assertThat(present("tour-2901530")).containsExactly("tour-2901530");
  }

  @Test
  @DisplayName("공공데이터 출처 POI 의 분류는 전부 영어 사전에 있다 — 적재가 사전도 채운다")
  void publicDataCategoriesHaveEnglish() {
    assertThat(
            count(
                """
                SELECT count(DISTINCT p.category) FROM poi p
                LEFT JOIN poi_category_i18n t ON t.ko = p.category AND t.lang = 'en'
                WHERE p.source_id ~ '^(MA|tour-|busstop-|metro-|train-|air-|hub-)'
                  AND p.closed_at IS NULL AND t.ko IS NULL
                """))
        .isZero();
    assertThat(
            jdbc.sql("SELECT name FROM poi_category_i18n WHERE ko = '카페' AND lang = 'en'")
                .query(String.class)
                .single())
        .isEqualTo("Cafe");
  }

  @Test
  @DisplayName(
      "입력의 addr_en 은 poi_i18n(en).address, name_en 은 name 으로 들어간다 — name_en 이 없으면 이름 칸은 비어 있다")
  void englishAddressAndNameAreLoaded() {
    String[] hotel = english("MA010120220814275700"); // 모슬포3호텔 — name_en 없음
    assertThat(hotel[0]).isNull();
    assertThat(hotel[1])
        .isEqualTo("15 Choenamdanhaean-ro 30beon-gil, Daejeong-eup, Seogwipo-si, Jeju-do");

    String[] restaurant = english("MA0101202504A0057604"); // 모슬포2호텔식당 — name_en 있음
    assertThat(restaurant[0]).isEqualTo("Moseulpo 2 Hotel Restaurant");
    assertThat(restaurant[1])
        .isEqualTo("13 Choenamdanhaean-ro 30beon-gil, Daejeong-eup, Seogwipo-si, Jeju-do");
  }

  @Test
  @DisplayName("영어 이름도 주소도 없는 줄은 영어 행을 남기지 않는다(하모체육공원)")
  void noEnglishRowWithoutNameOrAddress() {
    assertThat(present("tour-3031541")).hasSize(1);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM poi_i18n t JOIN poi p ON p.id = t.poi_id
                    WHERE p.source_id = 'tour-3031541' AND t.lang = 'en'
                    """)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM poi_i18n WHERE lang = 'en'"
                    + " AND name IS NULL AND address IS NULL AND road IS NULL"))
        .as("표 전체에서도 빈 영어 행은 없다")
        .isZero();
  }

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  /** 주어진 source_id 중 poi 에 있는 것, 작은 순. */
  private static List<String> present(String... sourceIds) {
    return jdbc.sql("SELECT source_id FROM poi WHERE source_id IN (:ids) ORDER BY source_id")
        .param("ids", List.of(sourceIds))
        .query(String.class)
        .list();
  }

  private static String groupOf(String sourceId) {
    return jdbc.sql("SELECT category_group FROM poi WHERE source_id = :s")
        .param("s", sourceId)
        .query(String.class)
        .single();
  }

  /** {name, address} — 영어 행. */
  private static String[] english(String sourceId) {
    return jdbc.sql(
            """
            SELECT t.name, t.address FROM poi_i18n t JOIN poi p ON p.id = t.poi_id
            WHERE p.source_id = :s AND t.lang = 'en'
            """)
        .param("s", sourceId)
        .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
        .single();
  }
}
