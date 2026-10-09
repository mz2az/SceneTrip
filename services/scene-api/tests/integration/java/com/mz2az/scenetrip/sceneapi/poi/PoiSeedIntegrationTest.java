package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi.sql} 의 변환 규칙이 지켜졌는지 표에서 확인한다. 표본 31 행에 일부러 넣어 둔 것들 — 허용목록 밖 행, 이름·좌표가 같은 중복 쌍,
 * 갈래가 파일이 아니라 biz_middle 로 정해지는 행, 제외 분류 넷 — 이 전량에서도 같은 결과여야 한다.
 */
@DisplayName("POI 적재 — 변환 규칙")
class PoiSeedIntegrationTest {
  private static JdbcClient jdbc;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requirePoiSeeded(jdbc);
  }

  @Test
  @DisplayName("허용목록 밖(정육점, biz_middle=음식료)은 들어오지 않는다")
  void dropsRowsOutsideAllowList() {
    assertThat(count("SELECT count(*) FROM poi WHERE source_id = '4293053'")).isZero();
  }

  @Test
  @DisplayName("이름·좌표가 같은 두 줄(뚱땡이짬뽕)은 하나로 접힌다")
  void mergesDuplicateShops() {
    assertThat(count("SELECT count(*) FROM poi WHERE source_id IN ('12658692', '12647348')"))
        .isEqualTo(1);
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

  private static long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  @Test
  @DisplayName("관광공사 음식점이 100 m 안 같은 이름의 상가정보와 겹치면 상가정보만 남는다")
  void tourRowShadowedByStoreRowIsDropped() {
    assertThat(count("SELECT count(*) FROM poi WHERE source_id = 'MA0106202201A0999001'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM poi WHERE source_id = 'tour-9990001'")).isZero();
  }

  @Test
  @DisplayName("상가정보에 없는 관광공사 음식점은 들어온다")
  void tourRowWithoutStoreMatchIsKept() {
    assertThat(count("SELECT count(*) FROM poi WHERE source_id = 'tour-9990002'")).isEqualTo(1);
  }

  @Test
  @DisplayName("여행 앱에 보일 일이 없는 분류(유흥 주점 둘·구내식당·고시원)는 들어오지 않고, 이미 있던 것은 지우지 않고 숨긴다")
  void excludedCategoriesAreDropped() {
    assertThat(
            count(
                "SELECT count(*) FROM poi WHERE source_id IN ('MA0106202201A0999101',"
                    + " 'MA0106202201A0999102', 'MA0106202201A0999103', 'MA0106202201A0999104')"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM poi WHERE closed_at IS NULL AND category IN"
                    + " ('일반 유흥 주점', '무도 유흥 주점', '구내식당', '기숙사/고시원')"))
        .as("표본만이 아니라 표 전체에서 — 열린 POI 중에는 없다(닫힌 채 남는 건 된다)")
        .isZero();
  }

  @Test
  @DisplayName("공공데이터 출처 POI 의 분류는 전부 영어 사전에 있다 — 적재가 사전도 채운다")
  void publicDataCategoriesHaveEnglish() {
    // TMAP 판 표본(숫자 id)의 옛 분류(일본선술집·문화유적지)는 공공데이터 판에 없어 사전에도 없다 — 출처로 거른다.
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
  @DisplayName("입력의 addr_en 이 poi_i18n(en).address 로 들어간다 — 이름 칸은 비어 있다")
  void englishAddressIsLoaded() {
    long sample = count("SELECT count(*) FROM poi WHERE source_id = 'MA0106202201A0999001'");
    assumeTrue(sample == 1, "표본이 적재된 DB 에서만 — 전량 DB 에는 이 표본 행이 없다");
    var row =
        jdbc.sql(
                """
                SELECT t.name, t.address FROM poi_i18n t JOIN poi p ON p.id = t.poi_id
                WHERE p.source_id = 'MA0106202201A0999001' AND t.lang = 'en'
                """)
            .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)})
            .single();
    assertThat(row[0]).isNull();
    assertThat(row[1]).isEqualTo("1 Myeongdong-gil, Jung-gu, Seoul");
  }

  @Test
  @DisplayName("이름도 주소도 없는 영어 행은 남지 않는다")
  void noEmptyEnglishRows() {
    assertThat(
            count(
                "SELECT count(*) FROM poi_i18n WHERE lang = 'en'"
                    + " AND name IS NULL AND address IS NULL AND road IS NULL"))
        .isZero();
  }

  @Test
  @DisplayName("좌표가 한국 밖(위·경도가 바뀐 행)이면 들어오지 않는다")
  void outOfKoreaCoordinatesAreDropped() {
    assertThat(count("SELECT count(*) FROM poi WHERE source_id = 'tour-9990003'")).isZero();
  }
}
