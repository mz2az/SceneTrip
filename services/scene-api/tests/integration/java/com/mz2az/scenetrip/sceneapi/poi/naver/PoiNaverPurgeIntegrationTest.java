package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V23 — {@code poi_naver} 를 비운다, 표는 남긴다 (ADR 0020, MZ2AZ-355).
 *
 * <p>Flyway 가 읽는 바로 그 파일을 클래스패스({@code db/migration}, :scene-api 의 resources)에서 읽어 JDBC 로 돌린다 —
 * PoiUpdateIntegrationTest 가 적재 SQL 을 다루는 방식과 같다. 픽스처(POI 하나 + 카드 둘)는 트랜잭션 안에서만 만들고 끝나면 되돌린다.
 */
@DisplayName("V23 — poi_naver 비우기")
class PoiNaverPurgeIntegrationTest {
  private static final String V23 = "db/migration/V23__poi_naver_purge.sql";

  private static JdbcClient jdbc;
  private static String purgeSql;

  @BeforeAll
  static void connect() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    try (InputStream in =
        PoiNaverPurgeIntegrationTest.class.getClassLoader().getResourceAsStream(V23)) {
      assertThat(in).as(V23 + " 가 클래스패스에 있다").isNotNull();
      purgeSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("V23 이 성공으로 적용돼 있고, 표는 남아 있으며 비어 있다")
  void appliedAndEmpty() {
    List<Boolean> applied =
        jdbc.sql(
                "SELECT success FROM flyway_schema_history"
                    + " WHERE version = '23' AND script = 'V23__poi_naver_purge.sql'")
            .query(Boolean.class)
            .list();
    assertThat(applied).as("V23 적용 이력").containsExactly(true);

    assertThat(
            jdbc.sql("SELECT to_regclass('public.poi_naver') IS NOT NULL")
                .query(Boolean.class)
                .single())
        .as("표는 지우지 않는다")
        .isTrue();
    assertThat(jdbc.sql("SELECT count(*) FROM poi_naver").query(Long.class).single())
        .as("poi_naver 행")
        .isZero();
  }

  @Test
  @DisplayName("행이 있어도 V23 의 SQL 을 돌리면 전부 지워진다 — 찾은 것도 못 찾은 것도")
  void purgeDeletesEveryRow() {
    long[] after =
        IntegrationDatabase.rolledBack(
            () -> {
              long poiA = insertPoi("v23-test-a");
              long poiB = insertPoi("v23-test-b");
              jdbc.sql(
                      """
                      INSERT INTO poi_naver (poi_id, found, rule_version, naver_id, name, images, url)
                      VALUES (:id, true, 'v3', '1234567', '명동교자본점', ARRAY['https://img.example/1.jpg'],
                              'https://map.naver.com/p/entry/place/1234567')
                      """)
                  .param("id", poiA)
                  .update();
              jdbc.sql(
                      """
                      INSERT INTO poi_naver (poi_id, found, why, rule_version)
                      VALUES (:id, false, '일치하는 장소가 없다', 'v2')
                      """)
                  .param("id", poiB)
                  .update();
              long before = count();

              IntegrationDatabase.execute(purgeSql);

              long poisLeft =
                  jdbc.sql("SELECT count(*) FROM poi WHERE id IN (:ids)")
                      .param("ids", List.of(poiA, poiB))
                      .query(Long.class)
                      .single();
              return new long[] {before, count(), poisLeft};
            });

    assertThat(after[0]).as("픽스처를 넣은 뒤").isGreaterThanOrEqualTo(2);
    assertThat(after[1]).as("V23 뒤").isZero();
    assertThat(after[2]).as("POI 자체는 건드리지 않는다").isEqualTo(2);
  }

  private static long count() {
    return jdbc.sql("SELECT count(*) FROM poi_naver").query(Long.class).single();
  }

  private static long insertPoi(String sourceId) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, 'V23 시험 가게', ST_SetSRID(ST_MakePoint(127.0, 37.6), 4326)::geography, '한식', 'food')
            RETURNING id
            """)
        .param("s", sourceId)
        .query(Long.class)
        .single();
  }
}
