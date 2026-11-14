package com.mz2az.scenetrip.sceneapi.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 담은 촬영지의 한국어 이름(계약 1.32.0 {@code CartItem.nameKo}) — 계획 {@code web-ad-landing.md} §10-2.
 *
 * <p>명세에서 나온 기대: {@code nameKo} 는 요청 언어와 무관하게 {@code place_i18n} 의 {@code ko} 이름, 없으면 null. {@code
 * name} 은 지금처럼 요청 언어 폴백. 담기 응답({@code add})과 목록({@code list}) 둘 다.
 */
@DisplayName("CartStore — nameKo 는 언제나 한국어 이름 (실제 DB, 1.32.0)")
class CartNameKoIntegrationTest {

  private static final double LNG = 12.4411;
  private static final double LAT = -62.4411;

  private static JdbcClient jdbc;
  private static CartStore store;
  private static UserStore users;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new CartStore(jdbc);
    users = new UserStore(jdbc);
  }

  private record Seen(CartItem added, List<CartItem> listed, long both, long englishOnly) {}

  private static Seen look() {
    return IntegrationDatabase.rolledBack(
        () -> {
          UUID user = users.resolve(UUID.randomUUID());
          long both = place(0.0001);
          name(both, "ko", "남산 시험 타워");
          name(both, "en", "Namsan Test Tower");
          long englishOnly = place(0.0002);
          name(englishOnly, "en", "English Only Spot");

          CartItem added = store.add(user, both, null, Lang.EN).orElseThrow();
          assertThat(store.add(user, englishOnly, null, Lang.EN)).isPresent();
          return new Seen(added, store.list(user, Lang.EN).items(), both, englishOnly);
        });
  }

  @Test
  @DisplayName("add 응답 — name 은 요청 언어, nameKo 는 한국어")
  void addCarriesNameKo() {
    Seen seen = look();

    assertThat(seen.added().getName()).isEqualTo("Namsan Test Tower");
    assertThat(seen.added().getNameKo()).isEqualTo("남산 시험 타워");
  }

  @Test
  @DisplayName("list — 한국어 이름이 있으면 nameKo, 없으면 null")
  void listCarriesNameKo() {
    Seen seen = look();
    Map<Long, CartItem> byId =
        seen.listed().stream().collect(Collectors.toMap(CartItem::getPlaceId, Function.identity()));

    assertThat(byId.get(seen.both()).getNameKo()).isEqualTo("남산 시험 타워");
    assertThat(byId.get(seen.englishOnly()).getName()).isEqualTo("English Only Spot");
    assertThat(byId.get(seen.englishOnly()).getNameKo()).isNull();
  }

  private static long place(double offset) {
    return jdbc.sql(
            "INSERT INTO place (type, geom) VALUES ('시험',"
                + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) RETURNING id")
        .param("lng", LNG + offset)
        .param("lat", LAT)
        .query(Long.class)
        .single();
  }

  private static void name(long placeId, String lang, String name) {
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, CAST(:l AS lang_code), :n)")
        .param("p", placeId)
        .param("l", lang)
        .param("n", name)
        .update();
  }
}
