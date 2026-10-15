package com.mz2az.scenetrip.sceneapi.cart;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 장바구니의 「목록에서 빠짐」 표시(scene-api 1.18.0, docs/project/plans/delisted-items.md) — 실제 DB.
 *
 * <p>명세: {@code place.hidden_at} 이 찬 촬영지는 {@code CartItem.delisted = true}, 아니면 {@code false}(없으면
 * {@code false} 로 읽는다). 숨긴 촬영지도 장바구니에서 <b>빠지지 않는다</b>. 숨김이 풀리면 다시 {@code false}.
 *
 * <p>적재된 촬영지의 {@code hidden_at} 을 잠깐 바꾸므로 전부 {@link IntegrationDatabase#rolledBack} 안에서 돌린다 — 공유 DB
 * 는 그대로다.
 */
@DisplayName("장바구니 — 숨긴 촬영지의 delisted 표시 (실제 DB)")
class CartDelistedIntegrationTest {

  private static JdbcClient jdbc;
  private static CartStore store;
  private static UserStore users;

  private UUID user;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new CartStore(jdbc);
    users = new UserStore(jdbc);
  }

  @Test
  @DisplayName("보이는 촬영지 — 담기 응답과 조회 모두 delisted 가 false(또는 없음)")
  void visiblePlaceIsNotDelisted() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);

          Optional<CartItem> added = store.add(user, p, null, Lang.KO);

          assertThat(added).isPresent();
          assertNotDelisted(added.get());
          assertNotDelisted(onlyItem());
          return null;
        });
  }

  @Test
  @DisplayName("담은 뒤 숨기면 조회에 그대로 남고 delisted 가 true")
  void hiddenAfterAddingIsKeptAndDelisted() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          store.add(user, p, null, Lang.KO);

          hide(p);

          CartItem item = onlyItem();
          assertThat(item.getPlaceId()).isEqualTo(p);
          assertThat(item.getDelisted()).isTrue();
          assertThat(store.list(user, Lang.KO).items()).hasSize(1);
          return null;
        });
  }

  @Test
  @DisplayName("숨김이 풀리면 delisted 가 다시 false(또는 없음)")
  void unhidingClearsFlag() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          store.add(user, p, null, Lang.KO);
          hide(p);
          assertThat(onlyItem().getDelisted()).isTrue();

          unhide(p);

          assertNotDelisted(onlyItem());
          return null;
        });
  }

  @Test
  @DisplayName("이미 숨긴 촬영지를 담으면 담기 응답의 delisted 가 true")
  void addingHiddenPlaceReturnsDelisted() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          hide(p);

          Optional<CartItem> added = store.add(user, p, null, Lang.KO);

          assertThat(added).isPresent();
          assertThat(added.get().getDelisted()).isTrue();
          assertThat(onlyItem().getDelisted()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("여러 개 중 숨긴 것 하나만 delisted — 줄마다 따로 정해진다")
  void flagIsPerRow() {
    rolledBack(
        () -> {
          List<Long> ps = visiblePlaces(3);
          ps.forEach(p -> store.add(user, p, null, Lang.KO));

          hide(ps.get(1));

          List<CartItem> items = store.list(user, Lang.KO).items();
          assertThat(items)
              .extracting(CartItem::getPlaceId)
              .containsExactlyInAnyOrderElementsOf(ps);
          for (CartItem item : items) {
            if (item.getPlaceId().equals(ps.get(1))) {
              assertThat(item.getDelisted()).isTrue();
            } else {
              assertNotDelisted(item);
            }
          }
          return null;
        });
  }

  @Test
  @DisplayName("남의 장바구니 — 같은 숨긴 촬영지면 그쪽도 delisted, 서로의 항목은 섞이지 않는다")
  void otherUsersCart() {
    rolledBack(
        () -> {
          List<Long> ps = visiblePlaces(2);
          UUID stranger = users.resolve(UUID.randomUUID());
          store.add(user, ps.get(0), null, Lang.KO);
          store.add(stranger, ps.get(0), null, Lang.KO);
          store.add(stranger, ps.get(1), null, Lang.KO);

          hide(ps.get(0));

          assertThat(onlyItem().getDelisted()).isTrue();
          List<CartItem> theirs = store.list(stranger, Lang.KO).items();
          assertThat(theirs).hasSize(2);
          assertThat(theirs)
              .filteredOn(i -> i.getPlaceId().equals(ps.get(0)))
              .singleElement()
              .satisfies(i -> assertThat(i.getDelisted()).isTrue());
          assertThat(theirs)
              .filteredOn(i -> i.getPlaceId().equals(ps.get(1)))
              .singleElement()
              .satisfies(CartDelistedIntegrationTest::assertNotDelisted);
          return null;
        });
  }

  @Test
  @DisplayName("숨긴 촬영지를 빼면 목록에서 사라진다 — 표시만 할 뿐 빼기는 막지 않는다")
  void hiddenPlaceCanBeRemoved() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          store.add(user, p, null, Lang.KO);
          hide(p);

          assertThat(store.remove(user, p)).isTrue();
          assertThat(store.list(user, Lang.KO).items()).isEmpty();
          return null;
        });
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────

  private void rolledBack(Supplier<Void> work) {
    IntegrationDatabase.rolledBack(
        () -> {
          user = users.resolve(UUID.randomUUID());
          return work.get();
        });
  }

  private CartItem onlyItem() {
    List<CartItem> items = store.list(user, Lang.KO).items();
    assertThat(items).hasSize(1);
    return items.get(0);
  }

  /** 명세: 값이 없으면 false 로 읽는다. 그러니 false 이거나 비어 있어야 한다 — true 만 아니면 된다. */
  private static void assertNotDelisted(CartItem item) {
    assertThat(item.getDelisted()).isNotEqualTo(Boolean.TRUE);
  }

  private static List<Long> visiblePlaces(int n) {
    List<Long> ids =
        jdbc.sql("SELECT id FROM place WHERE hidden_at IS NULL ORDER BY id LIMIT :n")
            .param("n", n)
            .query(Long.class)
            .list();
    assertThat(ids).as("숨기지 않은 촬영지 %d 개", n).hasSize(n);
    return ids;
  }

  private static void hide(long placeId) {
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", placeId).update();
  }

  private static void unhide(long placeId) {
    jdbc.sql("UPDATE place SET hidden_at = NULL WHERE id = :id").param("id", placeId).update();
  }
}
