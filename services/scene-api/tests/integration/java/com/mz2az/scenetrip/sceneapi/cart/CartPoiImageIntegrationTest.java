package com.mz2az.scenetrip.sceneapi.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CartPoi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.PoiStores;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 담은 편의시설의 {@code imageUrl}(계약 1.31.0 §1-5b) — {@code POST /cart/pois} 응답({@link
 * CartStore#addPoi})과 {@code GET /cart} 의 {@code pois}({@link CartStore#list}) 모두 상세({@code GET
 * /pois/{id}})의 사진 첫 장과 같은 주소다. 사진이 없으면 null. 한 트랜잭션 안에서 만들고 되돌린다.
 */
@DisplayName("CartStore — 담은 편의시설의 imageUrl (실제 DB)")
class CartPoiImageIntegrationTest {

  private static final double LNG = 124.71;
  private static final double LAT = 32.91;

  private static JdbcClient jdbc;
  private static CartStore store;
  private static PoiStore pois;
  private static UserStore users;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new CartStore(jdbc);
    pois = PoiStores.create(jdbc);
    users = new UserStore(jdbc);
  }

  private long poi(String name) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '관광지', 'sight')
            RETURNING id
            """)
        .param("s", "it-cart-poi-image-" + tag + "-" + name)
        .param("n", name)
        .param("lng", LNG)
        .param("lat", LAT)
        .query(Long.class)
        .single();
  }

  private String url(String name) {
    return "https://img.example/it-cart-" + tag + "/" + name + ".jpg";
  }

  private void image(long poiId, String name, int sort) {
    jdbc.sql("INSERT INTO poi_image (poi_id, url, sort_order) VALUES (:p, :u, :o)")
        .param("p", poiId)
        .param("u", url(name))
        .param("o", sort)
        .update();
  }

  private static URI detailFirstImage(long poiId) {
    PoiDetail d = pois.findDetail(poiId, Lang.KO, null, null).orElseThrow().poi();
    return d.getImages().isEmpty() ? null : d.getImages().get(0).getUrl();
  }

  private record Seen(
      CartPoi addedWith,
      CartPoi addedWithout,
      List<CartPoi> listed,
      URI expected,
      URI detailFirst,
      long with,
      long without) {}

  private Seen look() {
    return IntegrationDatabase.rolledBack(
        () -> {
          UUID user = users.resolve(UUID.randomUUID());
          long with = poi("사진있음");
          image(with, "second", 20);
          image(with, "first", 10);
          long without = poi("사진없음");

          CartPoi addedWith = store.addPoi(user, with, Lang.KO).orElseThrow();
          CartPoi addedWithout = store.addPoi(user, without, Lang.KO).orElseThrow();
          return new Seen(
              addedWith,
              addedWithout,
              store.list(user, Lang.KO).pois(),
              URI.create(url("first")),
              detailFirstImage(with),
              with,
              without);
        });
  }

  @Test
  @DisplayName("addPoi 응답의 imageUrl — 사진 첫 장(상세와 같은 주소), 없으면 null")
  void addResponseCarriesImageUrl() {
    Seen seen = look();

    assertThat(seen.detailFirst()).isEqualTo(seen.expected());
    assertThat(seen.addedWith().getImageUrl()).isEqualTo(seen.expected());
    assertThat(seen.addedWithout().getImageUrl()).isNull();
  }

  @Test
  @DisplayName("list().pois() 의 imageUrl — 사진 첫 장(상세와 같은 주소), 없으면 null")
  void listCarriesImageUrl() {
    Seen seen = look();

    assertThat(seen.listed()).hasSize(2);
    for (CartPoi p : seen.listed()) {
      if (p.getId() == seen.with()) {
        assertThat(p.getImageUrl()).isEqualTo(seen.expected());
      } else {
        assertThat(p.getId()).isEqualTo(seen.without());
        assertThat(p.getImageUrl()).isNull();
      }
    }
  }
}
