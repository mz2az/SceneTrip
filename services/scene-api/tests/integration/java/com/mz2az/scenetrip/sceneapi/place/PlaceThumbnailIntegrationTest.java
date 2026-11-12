package com.mz2az.scenetrip.sceneapi.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import java.net.URI;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 촬영지 목록의 작은 사진 칸(계약 1.29.0 {@code PlaceSummary.imageThumbnailUrl}, MZ2AZ-561) — 계획 {@code
 * photo-thumbnails.md} §2-4·§5.
 *
 * <p>명세에서 나온 기대: {@code imageThumbnailUrl} 은 <b>대표 사진 줄</b>({@code imageUrl} 로 나가는 첫 사진)의 {@code
 * place_image.thumbnail_url} 이다. 그 줄에 작은 사진이 없으면 null — 뒤 사진의 작은 사진을 빌려 오지 않는다. {@code imageUrl} 의
 * 뜻은 그대로 원본이다.
 *
 * <p>아무도 없는 좌표(남극해)에 촬영지를 만들고 그 둘레 뷰포트로 묻는다. 전부 되돌리는 트랜잭션 안에서 돈다.
 */
@DisplayName("PlaceStore.list — imageThumbnailUrl 은 대표 사진 줄의 thumbnail_url (실제 DB)")
class PlaceThumbnailIntegrationTest {

  private static final double LNG = 10.5611;
  private static final double LAT = -60.5611;

  private static JdbcClient jdbc;
  private static PlaceStore store;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = PlaceStores.create(jdbc);
  }

  @Test
  @DisplayName("대표 사진에 작은 사진이 있으면 그것, 없으면 null — imageUrl 은 언제나 원본")
  void thumbnailOfRepresentativePhoto() {
    IntegrationDatabase.rolledBack(
        () -> {
          long withThumb = place(0.0001);
          image(
              withThumb,
              "https://media.example/place/a.jpg",
              "https://media.example/place/thumb-720/a.jpg",
              0);
          image(withThumb, "https://media.example/place/a2.jpg", null, 1);

          long withoutThumb = place(0.0002);
          image(withoutThumb, "https://media.example/place/b.jpg", null, 0);

          // 대표(첫) 사진엔 작은 사진이 없고 두 번째에만 있다 — 빌려 오지 않는다
          long laterOnly = place(0.0003);
          image(laterOnly, "https://media.example/place/c.jpg", null, 0);
          image(
              laterOnly,
              "https://media.example/place/c2.jpg",
              "https://media.example/place/thumb-720/c2.jpg",
              1);

          Map<Long, PlaceSummary> byId = listAround();

          assertThat(byId.get(withThumb).getImageUrl())
              .isEqualTo(URI.create("https://media.example/place/a.jpg"));
          assertThat(byId.get(withThumb).getImageThumbnailUrl())
              .isEqualTo(URI.create("https://media.example/place/thumb-720/a.jpg"));

          assertThat(byId.get(withoutThumb).getImageUrl())
              .isEqualTo(URI.create("https://media.example/place/b.jpg"));
          assertThat(byId.get(withoutThumb).getImageThumbnailUrl()).isNull();

          assertThat(byId.get(laterOnly).getImageUrl())
              .isEqualTo(URI.create("https://media.example/place/c.jpg"));
          assertThat(byId.get(laterOnly).getImageThumbnailUrl()).isNull();
          return null;
        });
  }

  @Test
  @DisplayName("사진이 없는 촬영지는 imageUrl·imageThumbnailUrl 둘 다 null")
  void noPhoto() {
    IntegrationDatabase.rolledBack(
        () -> {
          long bare = place(0.0004);

          PlaceSummary s = listAround().get(bare);

          assertThat(s.getImageUrl()).isNull();
          assertThat(s.getImageThumbnailUrl()).isNull();
          return null;
        });
  }

  private static Map<Long, PlaceSummary> listAround() {
    Bbox around = new Bbox(LNG - 0.01, LAT - 0.01, LNG + 0.01, LAT + 0.01);
    PlaceStore.Page page =
        store.list(
            new PlaceStore.Criteria(
                null, null, around, null, null, null, PlaceStore.Sort.POPULARITY, Lang.KO, 50, 0));
    return page.items().stream()
        .collect(Collectors.toMap(PlaceSummary::getId, Function.identity()));
  }

  private static long place(double offset) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) RETURNING id")
            .param("lng", LNG + offset)
            .param("lat", LAT)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, CAST('ko' AS lang_code),"
                + " :n)")
        .param("p", id)
        .param("n", "시험 촬영지 " + id)
        .update();
    return id;
  }

  private static void image(long placeId, String url, String thumbnailUrl, int order) {
    jdbc.sql(
            "INSERT INTO place_image (place_id, url, sort_order, thumbnail_url)"
                + " VALUES (:p, :u, :o, :t)")
        .param("p", placeId)
        .param("u", url)
        .param("o", order)
        .param("t", thumbnailUrl)
        .update();
  }
}
