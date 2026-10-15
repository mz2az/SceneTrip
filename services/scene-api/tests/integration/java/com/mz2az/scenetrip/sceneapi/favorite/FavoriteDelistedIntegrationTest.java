package com.mz2az.scenetrip.sceneapi.favorite;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.content.ContentStore;
import com.mz2az.scenetrip.sceneapi.content.ContentStores;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 찜한 작품의 「목록에서 빠짐」 표시(scene-api 1.20.0, docs/project/plans/delisted-items.md) — 실제 DB.
 *
 * <p>명세: {@code content.hidden_at} 이 찬 작품은 찜 목록({@code GET /favorites/contents})의 {@code
 * ContentSummary.delisted} 가 {@code true}. 숨긴 작품도 찜에서 빠지지 않는다. 숨김이 풀리면 {@code false}. 작품 목록·검색에서는
 * 숨긴 작품이 애초에 빠지므로 늘 {@code false} 다.
 *
 * <p>적재된 작품의 {@code hidden_at} 을 잠깐 바꾸므로 전부 {@link IntegrationDatabase#rolledBack} 안에서 돌린다.
 */
@DisplayName("찜한 작품 — 숨긴 작품의 delisted 표시 (실제 DB)")
class FavoriteDelistedIntegrationTest {

  private static JdbcClient jdbc;
  private static FavoriteStore store;
  private static ContentStore contents;
  private static UserStore users;

  private UUID user;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new FavoriteStore(jdbc);
    contents = ContentStores.create(jdbc);
    users = new UserStore(jdbc);
  }

  @Test
  @DisplayName("보이는 작품 — delisted 는 false(또는 없음)")
  void visibleContentIsNotDelisted() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          store.add(user, c);

          assertNotDelisted(onlyItem());
          return null;
        });
  }

  @Test
  @DisplayName("찜한 뒤 숨기면 목록·전체 개수에 그대로 남고 delisted 가 true")
  void hiddenAfterFavoritingIsKeptAndDelisted() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          store.add(user, c);

          hide(c);

          FavoriteStore.Page page = store.list(user, Lang.KO, 20, 0);
          assertThat(page.total()).isEqualTo(1);
          assertThat(page.items()).hasSize(1);
          assertThat(page.items().get(0).getId()).isEqualTo(c);
          assertThat(page.items().get(0).getDelisted()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("숨김이 풀리면 delisted 가 다시 false(또는 없음)")
  void unhidingClearsFlag() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          store.add(user, c);
          hide(c);
          assertThat(onlyItem().getDelisted()).isTrue();

          jdbc.sql("UPDATE content SET hidden_at = NULL WHERE id = :id").param("id", c).update();

          assertNotDelisted(onlyItem());
          return null;
        });
  }

  @Test
  @DisplayName("이미 숨긴 작품을 찜해도 목록에 나오고 delisted 가 true")
  void favoritingHiddenContent() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          hide(c);

          store.add(user, c);

          assertThat(onlyItem().getDelisted()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("여러 개 중 숨긴 것 하나만 delisted — 페이지를 잘라도 줄마다 맞다")
  void flagIsPerRowAcrossPages() {
    rolledBack(
        () -> {
          List<Long> cs = visibleContents(3);
          cs.forEach(c -> store.add(user, c));
          hide(cs.get(1));

          List<ContentSummary> all = store.list(user, Lang.KO, 20, 0).items();
          assertThat(all).extracting(ContentSummary::getId).containsExactlyInAnyOrderElementsOf(cs);
          for (ContentSummary s : all) {
            if (s.getId().equals(cs.get(1))) {
              assertThat(s.getDelisted()).isTrue();
            } else {
              assertNotDelisted(s);
            }
          }

          // 한 장씩 잘라 봐도 같은 값이다.
          for (int offset = 0; offset < 3; offset++) {
            ContentSummary s = store.list(user, Lang.KO, 1, offset).items().get(0);
            assertThat(Boolean.TRUE.equals(s.getDelisted())).isEqualTo(s.getId().equals(cs.get(1)));
          }
          return null;
        });
  }

  @Test
  @DisplayName("남의 찜 — 같은 숨긴 작품이면 그쪽도 delisted, 서로의 찜은 섞이지 않는다")
  void otherUsersFavorites() {
    rolledBack(
        () -> {
          List<Long> cs = visibleContents(2);
          UUID stranger = users.resolve(UUID.randomUUID());
          store.add(user, cs.get(0));
          store.add(stranger, cs.get(0));
          store.add(stranger, cs.get(1));

          hide(cs.get(0));

          assertThat(onlyItem().getDelisted()).isTrue();
          List<ContentSummary> theirs = store.list(stranger, Lang.KO, 20, 0).items();
          assertThat(theirs).hasSize(2);
          assertThat(theirs)
              .filteredOn(s -> s.getId().equals(cs.get(0)))
              .singleElement()
              .satisfies(s -> assertThat(s.getDelisted()).isTrue());
          assertThat(theirs)
              .filteredOn(s -> s.getId().equals(cs.get(1)))
              .singleElement()
              .satisfies(FavoriteDelistedIntegrationTest::assertNotDelisted);
          return null;
        });
  }

  @Test
  @DisplayName("작품 목록(ContentSummary 를 같이 쓰는 곳)에는 delisted 가 true 인 줄이 없다")
  void contentListNeverDelisted() {
    ContentStore.Page page = contents.list(null, null, null, Lang.KO, 50, 0);

    assertThat(page.items()).isNotEmpty();
    assertThat(page.items()).allSatisfy(FavoriteDelistedIntegrationTest::assertNotDelisted);
  }

  // ── 도우미 ─────────────────────────────────────────────────────────────────

  private void rolledBack(Supplier<Void> work) {
    IntegrationDatabase.rolledBack(
        () -> {
          user = users.resolve(UUID.randomUUID());
          return work.get();
        });
  }

  private ContentSummary onlyItem() {
    List<ContentSummary> items = store.list(user, Lang.KO, 20, 0).items();
    assertThat(items).hasSize(1);
    return items.get(0);
  }

  /** 명세: 값이 없으면 false 로 읽는다 — true 만 아니면 된다. */
  private static void assertNotDelisted(ContentSummary s) {
    assertThat(s.getDelisted()).isNotEqualTo(Boolean.TRUE);
  }

  private static List<Long> visibleContents(int n) {
    List<Long> ids =
        jdbc.sql("SELECT id FROM content WHERE hidden_at IS NULL ORDER BY id LIMIT :n")
            .param("n", n)
            .query(Long.class)
            .list();
    assertThat(ids).as("숨기지 않은 작품 %d 개", n).hasSize(n);
    return ids;
  }

  private static void hide(long contentId) {
    jdbc.sql("UPDATE content SET hidden_at = now() WHERE id = :id").param("id", contentId).update();
  }
}
