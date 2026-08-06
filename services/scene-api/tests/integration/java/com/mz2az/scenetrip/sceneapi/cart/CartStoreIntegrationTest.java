package com.mz2az.scenetrip.sceneapi.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link CartStore} 의 SQL 을 진짜 PostgreSQL 에 태운다.
 *
 * <p>다른 Store 와 달리 <b>쓰기</b>가 있다. 그래서 매번 새 기기 UUID 를 쓰고, 끝나면 그 기기의 행만 지운다 — 적재 데이터를 건드리지 않으므로 테스트를
 * 몇 번 돌려도 DB 상태가 그대로다.
 *
 * <p>중복 담기를 막는 것은 코드가 아니라 <b>DB 의 기본키</b>다. 그 제약이 실제로 걸리는지는 진짜 DB 에서만 확인된다 — 가짜 Store 로는 영영 알 수 없다.
 */
@DisplayName("CartStore — 실제 DB 질의")
class CartStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static CartStore store;

  private final UUID device = UUID.randomUUID();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new CartStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM cart_item WHERE device_id = :id").param("id", device).update();
  }

  @Test
  @DisplayName("담고 조회하면 그 장소가 들어 있다")
  void addsItem() {
    long placeId = IntegrationDatabase.anyPlaceId(jdbc);

    Optional<?> added = store.add(device, placeId, null, Lang.KO);

    assertThat(added).isPresent();
    assertThat(store.list(device, Lang.KO).items()).extracting("placeId").containsExactly(placeId);
  }

  @Test
  @DisplayName("같은 장소를 두 번 담으면 두 번째는 비어 있다 — DB 기본키가 막는다")
  void rejectsDuplicate() {
    long placeId = IntegrationDatabase.anyPlaceId(jdbc);
    store.add(device, placeId, null, Lang.KO);

    Optional<?> again = store.add(device, placeId, null, Lang.KO);

    assertThat(again).isEmpty();
    assertThat(store.list(device, Lang.KO).items()).hasSize(1);
  }

  @Test
  @DisplayName("빼면 목록에서 사라진다")
  void removesItem() {
    long placeId = IntegrationDatabase.anyPlaceId(jdbc);
    store.add(device, placeId, null, Lang.KO);

    assertThat(store.remove(device, placeId)).isTrue();
    assertThat(store.list(device, Lang.KO).items()).isEmpty();
  }

  @Test
  @DisplayName("담기지 않은 것을 빼면 false — 예외가 아니다")
  void removingAbsentItemIsFalse() {
    assertThat(store.remove(device, IntegrationDatabase.anyPlaceId(jdbc))).isFalse();
  }

  @Test
  @DisplayName("빈 장바구니는 빈 목록")
  void emptyCartIsEmptyList() {
    assertThat(store.list(device, Lang.KO).items()).isEmpty();
  }

  @Test
  @DisplayName("없는 장소는 placeExists 가 false")
  void placeExistsReflectsDatabase() {
    assertThat(store.placeExists(-1L)).isFalse();
    assertThat(store.placeExists(IntegrationDatabase.anyPlaceId(jdbc))).isTrue();
  }
}
