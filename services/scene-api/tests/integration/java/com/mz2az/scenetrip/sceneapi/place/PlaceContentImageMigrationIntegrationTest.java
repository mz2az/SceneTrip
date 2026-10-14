package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code V33__place_content_image.sql} — 옛 칸 {@code place_content.scene_image_url} 의 {@code ;} 로 이은
 * 값이 순서대로 {@code place_content_image} 줄이 되는가(MZ2AZ-429).
 *
 * <p>Flyway 를 빈 DB 에 처음부터 돌리는 장치는 이 레인에 없다. 그래서 앱에 실리는 바로 그 마이그레이션 파일을 클래스패스에서 읽어, 트랜잭션 안에서 표를
 * 지우고(있으면) 옛 칸에 값을 심은 뒤 파일을 통째로 돌린다. PostgreSQL 의 DDL 은 트랜잭션에 들어가므로 끝나면 표도 값도 그대로 되돌아간다.
 */
@DisplayName("V33 — 장면 스틸을 칸에서 표로 옮긴다")
class PlaceContentImageMigrationIntegrationTest {

  private static final String MIGRATION = "/db/migration/V33__place_content_image.sql";

  private static JdbcClient jdbc;
  private static String migrationSql;

  @BeforeAll
  static void connect() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    try (InputStream in =
        Objects.requireNonNull(
            PlaceContentImageMigrationIntegrationTest.class.getResourceAsStream(MIGRATION),
            MIGRATION + " 가 클래스패스에 없다")) {
      migrationSql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @DisplayName("; 로 이은 값이 다듬어져 순서대로 줄이 되고, 빈 조각은 버리며 sort_order 는 자리 × 10 이다")
  void splitsSemicolonJoinedValueIntoOrderedRows() {
    IntegrationDatabase.rolledBack(
        () -> {
          List<Long> ids =
              jdbc.sql("SELECT id FROM place_content ORDER BY id LIMIT 3").query(Long.class).list();
          assertThat(ids).as("장소×작품이 셋은 있어야 세 경우를 본다").hasSize(3);
          long joined = ids.get(0);
          long single = ids.get(1);
          long empty = ids.get(2);

          IntegrationDatabase.execute("DROP TABLE IF EXISTS place_content_image");
          setLegacy(joined, " https://example.invalid/1.jpg ; ;https://example.invalid/3.jpg;");
          setLegacy(single, "https://example.invalid/only.jpg");
          setLegacy(empty, "  ");

          IntegrationDatabase.execute(migrationSql);

          assertThat(rows(joined))
              .as("앞뒤 공백은 다듬고, 빈 조각은 버리되 자리 번호는 원래 목록의 것이다")
              .extracting(r -> r.get("url"), r -> ((Number) r.get("sort_order")).intValue())
              .containsExactly(
                  tuple("https://example.invalid/1.jpg", 10),
                  tuple("https://example.invalid/3.jpg", 30));
          assertThat(rows(single))
              .extracting(r -> r.get("url"), r -> ((Number) r.get("sort_order")).intValue())
              .containsExactly(tuple("https://example.invalid/only.jpg", 10));
          assertThat(rows(empty)).as("공백뿐인 칸은 줄을 만들지 않는다").isEmpty();

          assertThat(legacy(joined))
              .as("옛 칸은 이번에 지우지 않는다 — 배포 중 옛 판 서버가 읽는다")
              .isEqualTo(" https://example.invalid/1.jpg ; ;https://example.invalid/3.jpg;");
          return null;
        });
  }

  private static void setLegacy(long placeContentId, String value) {
    jdbc.sql("UPDATE place_content SET scene_image_url = :v WHERE id = :id")
        .param("v", value)
        .param("id", placeContentId)
        .update();
  }

  private static String legacy(long placeContentId) {
    return jdbc.sql("SELECT scene_image_url FROM place_content WHERE id = :id")
        .param("id", placeContentId)
        .query(String.class)
        .single();
  }

  private static List<Map<String, Object>> rows(long placeContentId) {
    return jdbc.sql(
            "SELECT url, sort_order FROM place_content_image WHERE place_content_id = :id"
                + " ORDER BY sort_order, id")
        .param("id", placeContentId)
        .query()
        .listOfRows();
  }
}
