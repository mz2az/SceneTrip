package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi.sql} 의 변환 규칙이 지켜졌는지 표에서 확인한다. 표본 23 행에 일부러 넣어 둔 세 가지 — 허용목록 밖 행, 이름·좌표가 같은 중복 쌍,
 * 갈래가 파일이 아니라 biz_middle 로 정해지는 행 — 이 전량에서도 같은 결과여야 한다.
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
}
