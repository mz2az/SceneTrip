package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 마이그레이션 V46(사진 작은 판) — 계획 {@code photo-thumbnails.md} §3(MZ2AZ-561).
 *
 * <p>명세에서 나온 기대: {@code review_image}·{@code community_post_photo} 에 {@code thumbnail_key TEXT
 * NULL}, {@code thumbnail_attempts INT NOT NULL DEFAULT 0}, 그리고 부분 인덱스 {@code WHERE thumbnail_key
 * IS NULL AND thumbnail_attempts < 3}. 더하기만 — {@code place_image} 에는 칸을 더하지 않는다(V42 의 {@code
 * thumbnail_url}).
 */
@DisplayName("V46 — 사진 작은 판 칸과 부분 인덱스 (실제 DB)")
class PhotoThumbnailMigrationIntegrationTest {

  private static JdbcClient jdbc;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  @Test
  @DisplayName("review_image — thumbnail_key TEXT NULL, thumbnail_attempts INT NOT NULL DEFAULT 0")
  void reviewImageColumns() {
    assertColumns("review_image");
  }

  @Test
  @DisplayName(
      "community_post_photo — thumbnail_key TEXT NULL, thumbnail_attempts INT NOT NULL DEFAULT 0")
  void postPhotoColumns() {
    assertColumns("community_post_photo");
  }

  @Test
  @DisplayName("두 표 모두 「아직 없고 3 번 미만」 부분 인덱스가 있다")
  void partialIndexes() {
    for (String table : List.of("review_image", "community_post_photo")) {
      List<String> defs =
          jdbc.sql(
                  "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema()"
                      + " AND tablename = :t")
              .param("t", table)
              .query(String.class)
              .list();
      assertThat(defs)
          .as(table + " 의 인덱스")
          .anySatisfy(
              def -> {
                String d = def.replaceAll("\\s+", " ");
                assertThat(d).contains("thumbnail_key IS NULL");
                assertThat(d).contains("thumbnail_attempts < 3");
              });
    }
  }

  @Test
  @DisplayName("place_image 에는 새 칸이 없다 — 작은 사진은 V42 의 thumbnail_url")
  void placeImageUnchanged() {
    List<String> columns =
        jdbc.sql(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = current_schema() AND table_name = 'place_image'")
            .query(String.class)
            .list();
    assertThat(columns)
        .contains("thumbnail_url")
        .doesNotContain("thumbnail_key", "thumbnail_attempts");
  }

  private static void assertColumns(String table) {
    Map<String, Map<String, Object>> columns = new java.util.HashMap<>();
    jdbc.sql(
            "SELECT column_name, data_type, is_nullable, column_default"
                + " FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND table_name = :t")
        .param("t", table)
        .query()
        .listOfRows()
        .forEach(r -> columns.put((String) r.get("column_name"), r));

    assertThat(columns).as(table + " 의 칸").containsKeys("thumbnail_key", "thumbnail_attempts");

    Map<String, Object> key = columns.get("thumbnail_key");
    assertThat(key.get("data_type")).isEqualTo("text");
    assertThat(key.get("is_nullable")).isEqualTo("YES");

    Map<String, Object> attempts = columns.get("thumbnail_attempts");
    assertThat(attempts.get("data_type")).isEqualTo("integer");
    assertThat(attempts.get("is_nullable")).isEqualTo("NO");
    assertThat(String.valueOf(attempts.get("column_default"))).isEqualTo("0");
  }
}
