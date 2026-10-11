package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceList;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.content.ContentStores;
import com.mz2az.scenetrip.sceneapi.place.PlaceStores;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 작품 상세의 촬영지 순서(계약 {@code GET /contents/{contentId}/places} 기본 정렬, 계획 {@code popularity.md} §5 마지막
 * 문단 — 산정 점수 {@code place_content.popularity_score} 를 「작품 상세의 촬영지 순서」 에 쓴다).
 *
 * <p>산정 점수가 높은 촬영지가 앞, 산정 점수가 없는 촬영지는 있는 것들 뒤. 장소 자체의 인기도({@code place.popularity_score})를 일부러 반대로
 * 깔아, 그것이 아니라 작품 안 점수가 순서를 정하는지 본다.
 */
@DisplayName("GET /contents/{id}/places — 작품 안 산정 점수 순 (실제 DB)")
class ContentPlacesSceneScoreOrderIntegrationTest {

  private static JdbcClient jdbc;
  private static ContentsController controller;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    controller = new ContentsController(ContentStores.create(jdbc), PlaceStores.create(jdbc));
  }

  private static long workWithPlaces(int atLeast) {
    return jdbc.sql(
            "SELECT content_id FROM place_content GROUP BY content_id HAVING count(*) >= :n"
                + " ORDER BY count(*) DESC, content_id LIMIT 1")
        .param("n", atLeast)
        .query(Long.class)
        .single();
  }

  /** 목록에 실제로 나오는 촬영지(숨긴·합친 장소는 빠진다)를 id 순으로. */
  private static List<Long> placesOf(long work) {
    return listed(work).stream().sorted().toList();
  }

  private static void sceneScore(long work, long place, Double score) {
    jdbc.sql(
            "UPDATE place_content SET popularity_score = :s WHERE content_id = :c AND place_id ="
                + " :p")
        .param("s", score)
        .param("c", work)
        .param("p", place)
        .update();
  }

  private static void placePopularity(long place, double score) {
    jdbc.sql("UPDATE place SET popularity_score = :s WHERE id = :p")
        .param("s", score)
        .param("p", place)
        .update();
  }

  private static List<Long> listed(long work) {
    PlaceList body = controller.listContentPlaces(work, Lang.KO, null, null, 100, 0).getBody();
    assertThat(body).isNotNull();
    return body.getItems().stream().map(PlaceSummary::getId).toList();
  }

  @Test
  @DisplayName("작품 안 산정 점수가 높은 촬영지가 앞이다 — 장소 인기도가 반대여도")
  void ordersBySceneScoreWithinWork() {
    IntegrationDatabase.rolledBack(
        () -> {
          long work = workWithPlaces(4);
          List<Long> ps = placesOf(work);
          assertThat(ps).hasSizeGreaterThanOrEqualTo(4);
          // 산정 점수는 id 순으로 오르고(마지막이 최고), 장소 인기도는 id 순으로 내린다(첫째가 최고).
          for (int i = 0; i < ps.size(); i++) {
            sceneScore(work, ps.get(i), (double) (i + 1));
            placePopularity(ps.get(i), 1000.0 - i);
          }

          List<Long> order = listed(work);

          assertThat(order).containsExactlyElementsOf(ps.reversed());
          return null;
        });
  }

  @Test
  @DisplayName("산정 점수가 없는 촬영지는 있는 것들 뒤에 온다")
  void placesWithoutSceneScoreComeLast() {
    IntegrationDatabase.rolledBack(
        () -> {
          long work = workWithPlaces(4);
          List<Long> ps = placesOf(work);
          assertThat(ps).hasSizeGreaterThanOrEqualTo(4);
          long noScoreA = ps.get(0);
          long noScoreB = ps.get(1);
          // 점수 없는 둘에 가장 높은 장소 인기도를 준다 — 그래도 뒤여야 한다.
          for (int i = 0; i < ps.size(); i++) {
            sceneScore(work, ps.get(i), i < 2 ? null : (double) (i + 1));
            placePopularity(ps.get(i), i < 2 ? 5000.0 : 10.0);
          }

          List<Long> order = listed(work);

          assertThat(order).hasSize(ps.size());
          assertThat(order.subList(order.size() - 2, order.size()))
              .containsExactlyInAnyOrder(noScoreA, noScoreB);
          List<Long> scored = order.subList(0, order.size() - 2);
          List<Long> expectedScored = ps.subList(2, ps.size()).reversed();
          assertThat(scored).containsExactlyElementsOf(expectedScored);
          return null;
        });
  }

  @Test
  @DisplayName("다른 작품에서의 산정 점수는 이 작품의 순서에 쓰지 않는다 — 작품 안에서만")
  void otherWorksSceneScoreDoesNotLeak() {
    IntegrationDatabase.rolledBack(
        () -> {
          long work = workWithPlaces(3);
          List<Long> ps = placesOf(work);
          assertThat(ps).hasSizeGreaterThanOrEqualTo(3);
          long other =
              jdbc.sql("SELECT id FROM content WHERE id <> :w ORDER BY id LIMIT 1")
                  .param("w", work)
                  .query(Long.class)
                  .single();
          for (int i = 0; i < ps.size(); i++) {
            sceneScore(work, ps.get(i), (double) (i + 1));
          }
          // 이 작품에서 가장 낮은 촬영지가 다른 작품에서는 압도적으로 높다
          long lowest = ps.get(0);
          jdbc.sql(
                  """
                  INSERT INTO place_content (place_id, content_id, popularity_score) VALUES (:p, :o, 99999)
                  ON CONFLICT (place_id, content_id) DO UPDATE SET popularity_score = 99999
                  """)
              .param("p", lowest)
              .param("o", other)
              .update();

          List<Long> order = listed(work);

          assertThat(order.get(order.size() - 1)).isEqualTo(lowest);
          assertThat(order.get(0)).isEqualTo(ps.get(ps.size() - 1));
          return null;
        });
  }
}
