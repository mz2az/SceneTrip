package com.mz2az.scenetrip.sceneapi.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.CartPoi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.PoiStores;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
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
 * 장바구니에 편의시설 담기 — {@link CartStore} 의 새 SQL 을 진짜 PostgreSQL 에 태운다(계약 1.31.0,
 * docs/project/plans/keep-poi.md §2). 표는 {@code saved_poi}({@code user_id} → {@code app_user},
 * {@code poi_id} → {@code poi} ON DELETE CASCADE, {@code created_at}, 기본키 {@code (user_id,
 * poi_id)}).
 *
 * <p>쓰는 Store 메서드:
 *
 * <ul>
 *   <li>{@code boolean poiAvailable(long poiId)} — 편의시설이 있고 폐업({@code closed_at})이 아니다
 *   <li>{@code Optional<CartPoi> addPoi(UUID userId, long poiId, Lang lang)} — 이미 담겼으면(기본키 위반) 비어
 *       있다. 촬영지와 이어진 편의시설(숨기지 않은 촬영지의 {@code place_poi_link})이면 {@code saved_place} 에 넣고 {@code
 *       placeId} 를 채워 돌려준다
 *   <li>{@code boolean removePoi(UUID userId, long poiId)}
 *   <li>{@code list(userId, lang).pois()} — 담은 순서({@code created_at} 오래된 것부터), 폐업은 {@code
 *       closed=true}
 * </ul>
 *
 * <p>모든 시험이 한 트랜잭션 안에서 픽스처(바다 위 한 점의 편의시설·촬영지, 새 계정)를 만들고 끝나면 되돌린다 — 적재 데이터를 건드리지 않는다.
 */
@DisplayName("CartStore — 편의시설 담기 (실제 DB)")
class CartPoiIntegrationTest {

  /** 바다 위 — 적재 데이터와 다른 통합 시험의 픽스처와 겹치지 않는 자리. */
  private static final double LNG = 124.83;

  private static final double LAT = 32.77;

  private static final Bbox SPOT = new Bbox(LNG - 0.001, LAT - 0.001, LNG + 0.001, LAT + 0.001);

  /** 사전과 겹치지 않게 지어낸 분류 — en 이름을 픽스처로 넣는다. */
  private static final String CATEGORY = "담기시험분류";

  private static JdbcClient jdbc;
  private static CartStore store;
  private static PoiStore pois;
  private static UserStore users;
  private static AccountLinkStore links;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new CartStore(jdbc);
    pois = PoiStores.create(jdbc);
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  // ───────────── 픽스처 ─────────────

  private UUID newUser() {
    return users.resolve(UUID.randomUUID());
  }

  private long poi(String name, String category, String group) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, name_roman, geom, category, category_group, address)
            VALUES (:s, :n, :r, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :c, :g, :a)
            RETURNING id
            """)
        .param("s", "it-cart-poi-" + tag + "-" + name)
        .param("n", name)
        .param("r", "Damgi " + name.hashCode())
        .param("lng", LNG)
        .param("lat", LAT)
        .param("c", category)
        .param("g", group)
        .param("a", "제주 담기동 " + name)
        .query(Long.class)
        .single();
  }

  private long poi(String name) {
    return poi(name, "한식", "food");
  }

  private static void translate(long poiId, String lang, String name, String address) {
    jdbc.sql(
            "INSERT INTO poi_i18n (poi_id, lang, name, address) VALUES (:id, CAST(:lang AS"
                + " lang_code), :n, :a)")
        .param("id", poiId)
        .param("lang", lang)
        .param("n", name)
        .param("a", address)
        .update();
  }

  private static void categoryName(String ko, String lang, String name) {
    jdbc.sql(
            "INSERT INTO poi_category_i18n (ko, lang, name) VALUES (:ko, CAST(:lang AS lang_code),"
                + " :n)")
        .param("ko", ko)
        .param("lang", lang)
        .param("n", name)
        .update();
  }

  private long place(String name) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom, place_key) VALUES ('시험촬영지',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :k) RETURNING id")
            .param("lng", LNG)
            .param("lat", LAT)
            .param("k", "it-cart-poi-" + tag + "-" + name)
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name, address) VALUES (:p, 'ko', :n, '제주')")
        .param("p", id)
        .param("n", name)
        .update();
    return id;
  }

  private static void link(long poiId, long placeId) {
    jdbc.sql("INSERT INTO place_poi_link (poi_id, place_id, method) VALUES (:q, :p, 'manual')")
        .param("q", poiId)
        .param("p", placeId)
        .update();
  }

  private static void hide(long placeId) {
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :p").param("p", placeId).update();
  }

  private static void close(long poiId) {
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", poiId).update();
  }

  private static long count(String table, UUID user) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = CAST(:u AS UUID)")
        .param("u", user.toString())
        .query(Long.class)
        .single();
  }

  private static List<Long> poiIds(UUID user) {
    return store.list(user, Lang.KO).pois().stream().map(CartPoi::getId).toList();
  }

  private static <T> T rolledBack(Supplier<T> work) {
    return IntegrationDatabase.rolledBack(work);
  }

  // ───────────── 담기·목록 ─────────────

  @Test
  @DisplayName("담으면 CartPoi 가 돌아오고 목록의 pois 에 있다 — items 에는 섞이지 않는다")
  void addsPoi() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("가담기식당");

          Optional<CartPoi> added = store.addPoi(user, id, Lang.KO);

          assertThat(added).isPresent();
          CartPoi p = added.orElseThrow();
          assertThat(p.getId()).isEqualTo(id);
          assertThat(p.getName()).isEqualTo("가담기식당");
          assertThat(p.getCategory()).isEqualTo("한식");
          assertThat(p.getCategoryGroup()).isEqualTo(PoiCategoryGroup.FOOD);
          assertThat(p.getLatitude()).isCloseTo(LAT, offset(1e-6));
          assertThat(p.getLongitude()).isCloseTo(LNG, offset(1e-6));
          assertThat(p.getAddedAt()).isNotNull();
          assertThat(p.getClosed()).isFalse();
          assertThat(p.getPlaceId()).isNull();

          CartStore.Contents contents = store.list(user, Lang.KO);
          assertThat(contents.pois()).extracting(CartPoi::getId).containsExactly(id);
          assertThat(contents.items()).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("목록은 담은 순서(created_at 오래된 것부터)다 — id 순이 아니다")
  void listsInAddedOrder() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long first = poi("나담기A");
          long second = poi("나담기B");
          store.addPoi(user, first, Lang.KO);
          store.addPoi(user, second, Lang.KO);
          // 한 트랜잭션 안이라 now() 가 같다 — 나중에 만든(id 가 큰) 것을 더 오래전에 담은 것으로 돌려 놓는다.
          jdbc.sql(
                  "UPDATE saved_poi SET created_at = now() - interval '1 hour'"
                      + " WHERE user_id = CAST(:u AS UUID) AND poi_id = :p")
              .param("u", user.toString())
              .param("p", second)
              .update();

          assertThat(poiIds(user)).containsExactly(second, first);
          return null;
        });
  }

  @Test
  @DisplayName("이름·주소·분류의 언어는 GET /pois(PoiStore.list)와 같은 값이다 — ko·en·ja")
  void languageMatchesPoiList() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("다담기식당", CATEGORY, "food");
          translate(id, "en", "Dadamgi Restaurant", "1 Damgi-ro, Jeju");
          categoryName(CATEGORY, "en", "Keep Test Category");
          store.addPoi(user, id, Lang.KO);

          for (Lang lang : List.of(Lang.KO, Lang.EN, Lang.JA)) {
            CartPoi kept = store.list(user, lang).pois().get(0);
            PoiSummary shown =
                pois
                    .list(
                        new PoiStore.Criteria(
                            SPOT, null, null, null, null, PoiStore.Sort.ALPHABETICAL, 50, 0, lang))
                    .items()
                    .stream()
                    .filter(s -> s.getId() == id)
                    .findFirst()
                    .orElseThrow();
            assertThat(kept.getName()).as(lang + " name").isEqualTo(shown.getName());
            assertThat(kept.getDisplayName())
                .as(lang + " displayName")
                .isEqualTo(shown.getDisplayName());
            assertThat(kept.getNameRoman()).as(lang + " nameRoman").isEqualTo(shown.getNameRoman());
            assertThat(kept.getAddress()).as(lang + " address").isEqualTo(shown.getAddress());
            assertThat(kept.getDisplayAddress())
                .as(lang + " displayAddress")
                .isEqualTo(shown.getDisplayAddress());
            assertThat(kept.getCategory()).as(lang + " category").isEqualTo(shown.getCategory());
            assertThat(kept.getCategoryLabel())
                .as(lang + " categoryLabel")
                .isEqualTo(shown.getCategoryLabel());
            assertThat(kept.getCategoryGroup())
                .as(lang + " categoryGroup")
                .isEqualTo(shown.getCategoryGroup());
          }

          // 영어 요청이면 새 칸이 채워진다 — 위 비교가 둘 다 null 이라 공허하게 통과하지 않았는지.
          CartPoi en = store.list(user, Lang.EN).pois().get(0);
          assertThat(en.getDisplayName()).isEqualTo("Dadamgi Restaurant");
          assertThat(en.getDisplayAddress()).isEqualTo("1 Damgi-ro, Jeju");
          assertThat(en.getCategoryLabel()).isEqualTo("Keep Test Category");
          assertThat(en.getName()).isEqualTo("다담기식당");
          return null;
        });
  }

  @Test
  @DisplayName("담는 언어가 응답 언어다 — addPoi(…, EN) 의 displayName 이 영어")
  void addUsesRequestedLanguage() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("라담기식당");
          translate(id, "en", "Radamgi Restaurant", null);

          CartPoi added = store.addPoi(user, id, Lang.EN).orElseThrow();

          assertThat(added.getName()).isEqualTo("라담기식당");
          assertThat(added.getDisplayName()).isEqualTo("Radamgi Restaurant");
          return null;
        });
  }

  @Test
  @DisplayName("같은 편의시설을 두 번 담으면 두 번째는 비어 있다(→ 409) — DB 기본키가 막는다")
  void rejectsDuplicate() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("마담기식당");
          assertThat(store.addPoi(user, id, Lang.KO)).isPresent();

          // 기본키 위반 뒤 PostgreSQL 은 같은 트랜잭션의 다음 문장을 받지 않는다 — 위반 뒤의 조회는 duplicateLeavesOneRow 가 본다.
          assertThat(store.addPoi(user, id, Lang.KO)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("중복 담기 뒤에도 한 행만 있다 — 자동 커밋으로 돌려 위반 뒤 조회까지 본다")
  void duplicateLeavesOneRow() {
    // 한 트랜잭션 안에서는 기본키 위반 뒤 다음 문장이 막힌다. 이 시험만 CartStoreIntegrationTest 처럼 자동 커밋으로 돌리고 직접 지운다.
    UUID user = newUser();
    long id = poi("바담기식당");
    try {
      assertThat(store.addPoi(user, id, Lang.KO)).isPresent();
      assertThat(store.addPoi(user, id, Lang.KO)).isEmpty();

      assertThat(count("saved_poi", user)).isEqualTo(1);
      assertThat(poiIds(user)).containsExactly(id);
    } finally {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:u AS UUID)")
          .param("u", user.toString())
          .update();
      jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", id).update();
    }
  }

  // ───────────── 폐업 ─────────────

  @Test
  @DisplayName("있고 영업 중이면 poiAvailable, 없거나 폐업이면 아니다(→ 404 POI_NOT_FOUND)")
  void availabilityFollowsClosedAt() {
    rolledBack(
        () -> {
          long open = poi("사담기영업");
          long closed = poi("사담기폐업");
          close(closed);

          assertThat(store.poiAvailable(open)).isTrue();
          assertThat(store.poiAvailable(closed)).isFalse();
          assertThat(store.poiAvailable(-1L)).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("이미 담은 편의시설이 폐업하면 목록에 남고 closed=true")
  void closedAfterAddStaysWithFlag() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("아담기식당");
          store.addPoi(user, id, Lang.KO);
          close(id);

          List<CartPoi> kept = store.list(user, Lang.KO).pois();
          assertThat(kept).extracting(CartPoi::getId).containsExactly(id);
          assertThat(kept.get(0).getClosed()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("폐업한 편의시설도 뺄 수 있다")
  void closedPoiCanBeRemoved() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("자담기식당");
          store.addPoi(user, id, Lang.KO);
          close(id);

          assertThat(store.removePoi(user, id)).isTrue();
          assertThat(store.list(user, Lang.KO).pois()).isEmpty();
          return null;
        });
  }

  // ───────────── 촬영지와 이어진 편의시설 ─────────────

  @Test
  @DisplayName("촬영지와 이어진 편의시설은 촬영지로 담긴다 — saved_place 에 있고 saved_poi 에는 없다, placeId 가 채워진다")
  void linkedPoiIsSavedAsPlace() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("차담기카페");
          long place = place("차담기촬영지");
          link(id, place);

          CartPoi added = store.addPoi(user, id, Lang.KO).orElseThrow();

          assertThat(added.getId()).isEqualTo(id);
          assertThat(added.getPlaceId()).isEqualTo(place);
          assertThat(count("saved_poi", user)).isZero();
          assertThat(count("saved_place", user)).isEqualTo(1);
          CartStore.Contents contents = store.list(user, Lang.KO);
          assertThat(contents.items()).extracting(CartItem::getPlaceId).containsExactly(place);
          assertThat(contents.pois()).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("이어진 촬영지가 이미 담겨 있으면 비어 있다(→ 409)")
  void linkedPoiWhosePlaceIsAlreadySavedIsDuplicate() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("카담기카페");
          long place = place("카담기촬영지");
          link(id, place);
          assertThat(store.add(user, place, null, Lang.KO)).isPresent();

          assertThat(store.addPoi(user, id, Lang.KO)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("숨긴 촬영지와의 연결은 쓰지 않는다 — 편의시설로 담기고 placeId 는 없다")
  void linkToHiddenPlaceIsIgnored() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("타담기카페");
          long place = place("타담기촬영지");
          link(id, place);
          hide(place);

          CartPoi added = store.addPoi(user, id, Lang.KO).orElseThrow();

          assertThat(added.getPlaceId()).isNull();
          assertThat(count("saved_place", user)).isZero();
          assertThat(count("saved_poi", user)).isEqualTo(1);
          assertThat(poiIds(user)).containsExactly(id);
          return null;
        });
  }

  // ───────────── 빼기·주체 ─────────────

  @Test
  @DisplayName("빼면 목록에서 사라지고 true, 담겨 있지 않으면 false — 예외가 아니다")
  void removesPoi() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("파담기식당");
          store.addPoi(user, id, Lang.KO);

          assertThat(store.removePoi(user, id)).isTrue();
          assertThat(store.list(user, Lang.KO).pois()).isEmpty();
          assertThat(store.removePoi(user, id)).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("다른 사람의 담은 편의시설은 보이지 않고 뺄 수도 없다")
  void othersCannotSeeOrRemove() {
    rolledBack(
        () -> {
          UUID owner = newUser();
          UUID other = newUser();
          long id = poi("하담기식당");
          store.addPoi(owner, id, Lang.KO);

          assertThat(store.list(other, Lang.KO).pois()).isEmpty();
          assertThat(store.removePoi(other, id)).isFalse();
          assertThat(poiIds(owner)).containsExactly(id);
          return null;
        });
  }

  @Test
  @DisplayName("빈 장바구니의 pois 는 빈 목록이다(null 이 아니다)")
  void emptyCartHasEmptyPois() {
    rolledBack(
        () -> {
          UUID user = newUser();
          assertThat(store.list(user, Lang.KO).pois()).isNotNull().isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("편의시설 행이 지워지면 담은 것도 같이 사라진다(ON DELETE CASCADE)")
  void deletingPoiCascades() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("거담기식당");
          store.addPoi(user, id, Lang.KO);

          jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", id).update();

          assertThat(count("saved_poi", user)).isZero();
          assertThat(store.list(user, Lang.KO).pois()).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("계정이 지워지면 담은 편의시설도 같이 사라진다 — saved_place 와 같은 규칙")
  void deletingUserCascades() {
    rolledBack(
        () -> {
          UUID user = newUser();
          long id = poi("너담기식당");
          store.addPoi(user, id, Lang.KO);

          jdbc.sql("DELETE FROM app_user WHERE id = CAST(:u AS UUID)")
              .param("u", user.toString())
              .update();

          assertThat(count("saved_poi", user)).isZero();
          return null;
        });
  }

  // ───────────── 계정 잇기 ─────────────

  @Test
  @DisplayName("비회원 → 가입 계정 합치기에서 saved_poi 가 옮겨지고, 양쪽에 있던 것은 하나만 남는다")
  void mergeMovesSavedPois() {
    rolledBack(
        () -> {
          UUID guest = newUser();
          UUID member = newUser();
          long onlyGuest = poi("더담기식당");
          long both = poi("러담기식당");
          long onlyMember = poi("머담기식당");
          store.addPoi(guest, onlyGuest, Lang.KO);
          store.addPoi(guest, both, Lang.KO);
          store.addPoi(member, both, Lang.KO);
          store.addPoi(member, onlyMember, Lang.KO);

          links.merge(guest, member);

          assertThat(count("saved_poi", guest)).isZero();
          assertThat(count("saved_poi", member)).isEqualTo(3);
          assertThat(poiIds(member)).containsExactlyInAnyOrder(onlyGuest, both, onlyMember);
          return null;
        });
  }

  @Test
  @DisplayName("합친 뒤 비회원 쪽에 늦게 들어온 담기도 쓸어 온다(sweepInto)")
  void sweepMovesLateSavedPois() {
    rolledBack(
        () -> {
          UUID guest = newUser();
          UUID member = newUser();
          links.merge(guest, member);
          long late = poi("버담기식당");
          store.addPoi(guest, late, Lang.KO);

          links.sweepInto(member);

          assertThat(count("saved_poi", guest)).isZero();
          assertThat(poiIds(member)).containsExactly(late);
          return null;
        });
  }
}
