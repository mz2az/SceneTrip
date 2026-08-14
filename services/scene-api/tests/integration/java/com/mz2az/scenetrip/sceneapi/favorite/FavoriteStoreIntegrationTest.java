package com.mz2az.scenetrip.sceneapi.favorite;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link FavoriteStore} 의 SQL 을 진짜 PostgreSQL 에 태운다.
 *
 * <p>여기서만 확인되는 것 셋 — {@code ON CONFLICT DO NOTHING} 이 실제로 멱등을 만드는가, 창 함수로 낸 {@code total_count} 가
 * 페이지를 잘라도 전체 개수를 주는가, 그리고 <b>찜과 장바구니가 정말 다른 표인가</b>. 마지막 것은 8/11 회의가 가른 지점이라 한쪽을 건드렸을 때 다른 쪽이 따라
 * 움직이면 안 된다.
 */
@DisplayName("FavoriteStore — 실제 DB 질의")
class FavoriteStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static FavoriteStore store;
  private static UserStore users;

  private final UUID installUuid = UUID.randomUUID();
  private UUID user;
  private long contentA;
  private long contentB;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    store = new FavoriteStore(jdbc);
    users = new UserStore(jdbc);
  }

  @BeforeEach
  void createUser() {
    user = users.resolve(installUuid);
    List<Long> ids =
        jdbc.sql("SELECT id FROM content ORDER BY id LIMIT 2").query(Long.class).list();
    contentA = ids.get(0);
    contentB = ids.get(1);
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();
  }

  @Test
  @DisplayName("찜하면 목록에 뜨고 작품 카드 모양 그대로다")
  void addsAndLists() {
    store.add(user, contentA);

    FavoriteStore.Page page = store.list(user, Lang.KO, 20, 0);

    assertThat(page.total()).isEqualTo(1);
    ContentSummary only = page.items().get(0);
    assertThat(only.getId()).isEqualTo(contentA);
    assertThat(only.getTitle()).isNotBlank();
    // 촬영지 수는 카드에 "성지 N곳" 으로 뜬다. 적재분은 작품마다 촬영지가 있다.
    assertThat(only.getPlaceCount()).isNotNegative();
  }

  @Test
  @DisplayName("같은 작품을 두 번 찜해도 한 번 담긴다 — 하트는 토글이라 오류가 아니다")
  void addIsIdempotent() {
    store.add(user, contentA);
    store.add(user, contentA);

    assertThat(store.list(user, Lang.KO, 20, 0).total()).isEqualTo(1);
  }

  @Test
  @DisplayName("찜하지 않은 것을 빼도 조용히 넘어간다")
  void removeIsIdempotent() {
    store.remove(user, contentA);
    store.add(user, contentA);
    store.remove(user, contentA);
    store.remove(user, contentA);

    assertThat(store.list(user, Lang.KO, 20, 0).items()).isEmpty();
  }

  @Test
  @DisplayName("최근에 찜한 것이 앞에 온다")
  void newestFirst() {
    store.add(user, contentA);
    store.add(user, contentB);

    assertThat(store.list(user, Lang.KO, 20, 0).items())
        .extracting(ContentSummary::getId)
        .containsExactly(contentB, contentA);
  }

  @Test
  @DisplayName("잘라도 전체 개수는 그대로 준다")
  void totalSurvivesPaging() {
    store.add(user, contentA);
    store.add(user, contentB);

    FavoriteStore.Page firstPage = store.list(user, Lang.KO, 1, 0);

    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.total()).isEqualTo(2);
  }

  @Test
  @DisplayName("남의 찜은 보이지 않는다")
  void hidesOtherPeoplesFavorites() {
    store.add(user, contentA);
    UUID stranger = users.resolve(UUID.randomUUID());

    assertThat(store.list(stranger, Lang.KO, 20, 0).items()).isEmpty();

    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", stranger.toString())
        .update();
  }

  @Test
  @DisplayName("작품 찜과 장소 장바구니는 서로를 건드리지 않는다 — 8/11 회의가 가른 지점")
  void favoritesAndCartAreSeparate() {
    long placeId = IntegrationDatabase.anyPlaceId(jdbc);
    jdbc.sql("INSERT INTO saved_place (user_id, place_id) VALUES (CAST(:u AS UUID), :p)")
        .param("u", user.toString())
        .param("p", placeId)
        .update();

    store.add(user, contentA);
    store.remove(user, contentA);

    // 찜을 넣었다 뺐는데 장바구니가 따라 비면 두 개념이 한 표에 얽혀 있는 것이다.
    assertThat(savedPlaceCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("없는 작품은 contentExists 가 false — 외래키 위반을 500 으로 내보내지 않는다")
  void contentExistsReflectsDatabase() {
    assertThat(store.contentExists(-1L)).isFalse();
    assertThat(store.contentExists(contentA)).isTrue();
  }

  @Test
  @DisplayName("계정을 지우면 찜도 함께 사라진다")
  void cascadesWithAccount() {
    store.add(user, contentA);

    jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", user.toString())
        .update();

    assertThat(
            jdbc.sql("SELECT count(*) FROM saved_content WHERE user_id = CAST(:u AS UUID)")
                .param("u", user.toString())
                .query(Integer.class)
                .single())
        .isZero();
  }

  private int savedPlaceCount() {
    return jdbc.sql("SELECT count(*) FROM saved_place WHERE user_id = CAST(:u AS UUID)")
        .param("u", user.toString())
        .query(Integer.class)
        .single();
  }
}
