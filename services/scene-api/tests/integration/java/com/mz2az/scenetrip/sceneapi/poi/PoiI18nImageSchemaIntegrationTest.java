package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V17 — 편의시설 번역·낱말 사전·사진 표가 계획(docs/project/plans/poi-i18n-image.md §4)대로 섰는지 진짜 DB 에서 본다.
 *
 * <p>아직 이 표들을 읽는 Store 가 없어서, 쓰려는 질의 모양(폴백 · 빠진 낱말 찾기)을 SQL 로 직접 태운다. 픽스처 POI 를 트랜잭션 안에서 만들고 끝나면
 * 되돌린다 — 적재된 95 만 행은 건드리지 않는다.
 */
@DisplayName("V17 — poi_i18n · poi_category_i18n · poi_image")
class PoiI18nImageSchemaIntegrationTest {
  private static JdbcClient jdbc;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  @Test
  @DisplayName("번역이 있으면 번역, 없으면 poi 의 한국어 — COALESCE 한 번으로 폴백한다")
  void fallsBackToKorean() {
    List<String> names =
        IntegrationDatabase.rolledBack(
            () -> {
              long translated = fixturePoi("v17-test-a", "동구짬뽕");
              long untranslated = fixturePoi("v17-test-b", "골목식당");
              jdbc.sql(
                      "INSERT INTO poi_i18n (poi_id, lang, name, trans_status)"
                          + " VALUES (:id, 'en', 'Donggu Jjamppong', 'machine')")
                  .param("id", translated)
                  .update();
              return jdbc.sql(
                      """
                      SELECT COALESCE(t.name, p.name)
                      FROM poi p
                      LEFT JOIN poi_i18n t ON t.poi_id = p.id AND t.lang = 'en'
                      WHERE p.id IN (:ids)
                      ORDER BY p.source_id
                      """)
                  .param("ids", List.of(translated, untranslated))
                  .query(String.class)
                  .list();
            });

    assertThat(names).containsExactly("Donggu Jjamppong", "골목식당");
  }

  @Test
  @DisplayName("이름 없이 주소만 있는 영어 행 — 칸마다 따로 폴백한다(이름은 한국어, 주소는 영어)")
  void fallsBackPerColumn() {
    List<String> row =
        IntegrationDatabase.rolledBack(
            () -> {
              long id = fixturePoi("v17-test-addr", "동구짬뽕");
              jdbc.sql(
                      "INSERT INTO poi_i18n (poi_id, lang, address) VALUES (:id, 'en', '24-34"
                          + " Donggureung-ro 148beon-gil, Guri-si, Gyeonggi-do')")
                  .param("id", id)
                  .update();
              return jdbc.sql(
                      """
                      SELECT COALESCE(t.name, p.name), COALESCE(t.address, p.address)
                      FROM poi p
                      LEFT JOIN poi_i18n t ON t.poi_id = p.id AND t.lang = 'en'
                      WHERE p.id = :id
                      """)
                  .param("id", id)
                  .query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
                  .single();
            });

    assertThat(row)
        .containsExactly("동구짬뽕", "24-34 Donggureung-ro 148beon-gil, Guri-si, Gyeonggi-do");
  }

  @Test
  @DisplayName("poi_i18n 에 ko 는 못 들어간다 — 한국어 원본은 poi 에만 있다")
  void rejectsKoreanTranslationRow() {
    assertThatThrownBy(
            () ->
                IntegrationDatabase.rolledBack(
                    () ->
                        jdbc.sql(
                                "INSERT INTO poi_i18n (poi_id, lang, name) VALUES (:id, 'ko',"
                                    + " '중복')")
                            .param("id", fixturePoi("v17-test-ko", "원본"))
                            .update()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("poi_i18n_not_ko_check");
  }

  @Test
  @DisplayName("사전에 없는 분류는 빠진 낱말 질의에 잡히고, 한 줄 넣으면 빠진다")
  void findsMissingCategoryTerm() {
    List<Boolean> missing =
        IntegrationDatabase.rolledBack(
            () -> {
              fixturePoi("v17-test-term", "새가게", "v17-새분류");
              boolean before = categoryMissing("v17-새분류");
              jdbc.sql(
                      "INSERT INTO poi_category_i18n (ko, lang, name)"
                          + " VALUES ('v17-새분류', 'en', 'New Category')")
                  .update();
              return List.of(before, categoryMissing("v17-새분류"));
            });

    assertThat(missing).containsExactly(true, false);
  }

  @Test
  @DisplayName("분류 사전에도 ko 는 못 들어간다 — 한국어 분류는 poi.category 그 자체다")
  void rejectsKoreanCategoryRow() {
    assertThatThrownBy(
            () ->
                IntegrationDatabase.rolledBack(
                    () ->
                        jdbc.sql(
                                "INSERT INTO poi_category_i18n (ko, lang, name)"
                                    + " VALUES ('카페', 'ko', '카페')")
                            .update()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("poi_category_i18n_not_ko_check");
  }

  @Test
  @DisplayName("같은 POI 에 같은 사진 주소는 한 번만 — 수집을 다시 돌려도 늘지 않는다")
  void rejectsDuplicateImage() {
    assertThatThrownBy(
            () ->
                IntegrationDatabase.rolledBack(
                    () -> {
                      long poi = fixturePoi("v17-test-img", "사진가게");
                      for (int i = 0; i < 2; i++) {
                        jdbc.sql(
                                "INSERT INTO poi_image (poi_id, url, source, credit) VALUES (:id,"
                                    + " 'https://img.example/1.jpg', 'own', 'SceneTrip')")
                            .param("id", poi)
                            .update();
                      }
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("poi_image_poi_url_uk");
  }

  @Test
  @DisplayName("POI 가 지워지면 번역·사진도 함께 지워진다")
  void cascadesOnPoiDelete() {
    List<Long> left =
        IntegrationDatabase.rolledBack(
            () -> {
              long poi = fixturePoi("v17-test-del", "없어질가게");
              jdbc.sql("INSERT INTO poi_i18n (poi_id, lang, name) VALUES (:id, 'en', 'Gone')")
                  .param("id", poi)
                  .update();
              jdbc.sql(
                      "INSERT INTO poi_image (poi_id, url) VALUES (:id,"
                          + " 'https://img.example/x.jpg')")
                  .param("id", poi)
                  .update();
              jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", poi).update();
              return List.of(
                  count("SELECT count(*) FROM poi_i18n WHERE poi_id = :id", poi),
                  count("SELECT count(*) FROM poi_image WHERE poi_id = :id", poi));
            });

    assertThat(left).containsExactly(0L, 0L);
  }

  private static long fixturePoi(String sourceId, String name) {
    return fixturePoi(sourceId, name, "한식");
  }

  private static long fixturePoi(String sourceId, String name, String category) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:sourceId, :name, ST_GeogFromText('POINT(127.0 37.5)'), :category, 'food')
            RETURNING id
            """)
        .param("sourceId", sourceId)
        .param("name", name)
        .param("category", category)
        .query(Long.class)
        .single();
  }

  private static boolean categoryMissing(String category) {
    return jdbc.sql(
            """
            SELECT EXISTS (
              SELECT 1 FROM poi p
              LEFT JOIN poi_category_i18n t
                ON t.ko = p.category AND t.lang = 'en'
              WHERE p.category = :category AND t.ko IS NULL
            )
            """)
        .param("category", category)
        .query(Boolean.class)
        .single();
  }

  private static long count(String sql, long poiId) {
    return jdbc.sql(sql).param("id", poiId).query(Long.class).single();
  }
}
