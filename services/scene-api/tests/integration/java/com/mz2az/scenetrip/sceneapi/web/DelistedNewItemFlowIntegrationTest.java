package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.CartItem;
import com.mz2az.scenetrip.sceneapi.api.model.CartItemCreate;
import com.mz2az.scenetrip.sceneapi.api.model.ContentDetail;
import com.mz2az.scenetrip.sceneapi.api.model.ContentSummary;
import com.mz2az.scenetrip.sceneapi.api.model.CourseCreate;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDayInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseDetail;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItem;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemInput;
import com.mz2az.scenetrip.sceneapi.api.model.CourseItemSource;
import com.mz2az.scenetrip.sceneapi.api.model.CourseOrigin;
import com.mz2az.scenetrip.sceneapi.api.model.CourseReplace;
import com.mz2az.scenetrip.sceneapi.api.model.FavoriteContentCreate;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceDetail;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.cart.CartStores;
import com.mz2az.scenetrip.sceneapi.content.ContentStores;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.course.DeployedTravel;
import com.mz2az.scenetrip.sceneapi.course.DwellDefaults;
import com.mz2az.scenetrip.sceneapi.favorite.FavoriteStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStores;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 숨긴 촬영지·작품, 폐업한 편의시설을 <b>새로</b> 담는 것은 막고, 이미 담은 것은 남긴다(scene-api 1.18.0,
 * docs/project/plans/delisted-items.md §3-1, docs/api/errors.md 의 409 표) — 컨트롤러와 진짜 Store, 실제 DB.
 *
 * <ul>
 *   <li>상세 {@code GET /places/{id}}·{@code GET /contents/{id}} 는 숨겨도 열리고 {@code delisted: true}.
 *   <li>{@code POST /cart/items} — 숨긴 촬영지를 새로 담으면 409 {@code PLACE_DELISTED}. 이미 담겨 있으면 지금처럼 409
 *       {@code DUPLICATE_CART_ITEM}.
 *   <li>{@code POST /favorites/contents} — 숨긴 작품을 새로 찜하면 409 {@code CONTENT_DELISTED}. 이미 찜했으면 204.
 *   <li>{@code PUT /courses/{id}} — 새 줄(id 없음)이 숨긴 촬영지면 409 {@code PLACE_DELISTED}, 폐업 편의시설이면 409
 *       {@code POI_CLOSED}, 없는 편의시설이면 400 {@code POI_NOT_FOUND}. 이미 있던 줄(id 있음)은 그대로 저장된다.
 * </ul>
 *
 * <p>적재된 촬영지·작품의 숨김을 잠깐 바꾸므로 전부 {@link IntegrationDatabase#rolledBack} 안에서 돌린다 — 공유 DB 는 그대로다.
 */
@DisplayName("새로 담기 막기 — 숨긴 촬영지·작품, 폐업 편의시설 (컨트롤러 + 실제 DB)")
class DelistedNewItemFlowIntegrationTest {

  private static final byte[] SECRET = filled(32, (byte) 9);
  private static final double LAT = 34.92;
  private static final double LNG = 129.62;

  private static JdbcClient jdbc;
  private static UserStore users;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private final CurrentAccount accounts = new CurrentAccount(request, tokens, users);
  private final CartController cart = new CartController(CartStores.create(jdbc), accounts);
  private final FavoriteController favorites =
      new FavoriteController(new FavoriteStore(jdbc), accounts);
  private final CourseController courses = new CourseController(courseStore(), accounts);
  private final PlacesController places =
      new PlacesController(
          PlaceStores.create(jdbc),
          new ReviewViews(
              new ReviewStore(jdbc, IntegrationDatabase.transactions()), new NoPhotos()));
  private final ContentsController contents =
      new ContentsController(ContentStores.create(jdbc), PlaceStores.create(jdbc));

  /** 이 시험의 비회원 설치 UUID. X-Install-Id 로 보낸다. */
  private UUID install;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    users = new UserStore(jdbc);
  }

  // ───────────── 상세 ─────────────

  @Test
  @DisplayName(
      "GET /places/{id} — 보이면 delisted 가 true 가 아니고, 숨기면 200 으로 열리며 delisted true, 풀면 다시 아님")
  void placeDetailCarriesDelisted() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          assertThat(placeDetail(p).getDelisted()).isNotEqualTo(Boolean.TRUE);

          hidePlace(p);
          ResponseEntity<PlaceDetail> hidden = places.getPlace(p, Lang.KO, null, null);
          assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(hidden.getBody().getId()).isEqualTo(p);
          assertThat(hidden.getBody().getDelisted()).isTrue();

          unhidePlace(p);
          assertThat(placeDetail(p).getDelisted()).isNotEqualTo(Boolean.TRUE);
          return null;
        });
  }

  @Test
  @DisplayName(
      "GET /contents/{id} — 보이면 delisted 가 true 가 아니고, 숨기면 200 으로 열리며 delisted true, 풀면 다시 아님")
  void contentDetailCarriesDelisted() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          assertThat(contentDetail(c).getDelisted()).isNotEqualTo(Boolean.TRUE);

          hideContent(c);
          ResponseEntity<ContentDetail> hidden = contents.getContent(c, Lang.KO);
          assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(hidden.getBody().getId()).isEqualTo(c);
          assertThat(hidden.getBody().getDelisted()).isTrue();

          jdbc.sql("UPDATE content SET hidden_at = NULL WHERE id = :id").param("id", c).update();
          assertThat(contentDetail(c).getDelisted()).isNotEqualTo(Boolean.TRUE);
          return null;
        });
  }

  // ───────────── 장바구니 ─────────────

  @Test
  @DisplayName("POST /cart/items — 보이는 촬영지는 201 이고 delisted 가 true 가 아니다")
  void cartAddsVisiblePlace() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);

          ResponseEntity<CartItem> added = addToCart(p);

          assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
          assertThat(added.getBody().getPlaceId()).isEqualTo(p);
          assertThat(added.getBody().getDelisted()).isNotEqualTo(Boolean.TRUE);
          return null;
        });
  }

  @Test
  @DisplayName("POST /cart/items — 장바구니에 없는 숨긴 촬영지는 409 PLACE_DELISTED, 아무것도 담기지 않는다")
  void cartRejectsNewHiddenPlace() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          hidePlace(p);

          ApiException e = apiError(() -> addToCart(p));

          assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e.getCode()).isEqualTo("PLACE_DELISTED");
          assertThat(cartItems()).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName(
      "POST /cart/items — 이미 담긴 숨긴 촬영지는 지금처럼 409 DUPLICATE_CART_ITEM(이미 있음을 먼저 본다), 담긴 것은 남는다")
  void cartDuplicateCheckComesFirst() {
    // 중복은 DB 기본키가 막는다 — 바깥 트랜잭션 하나로 묶으면 그 위반이 트랜잭션을 깨 뒤 조회가 실패한다. 요청마다
    // 트랜잭션이 따로인 운영과 같게 커밋하며 돌리고 끝에 되돌린다.
    committed(
        () -> {
          long p = visiblePlaces(1).get(0);
          addToCart(p);
          hidePlace(p);

          ApiException e = apiError(() -> addToCart(p));

          assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e.getCode()).isEqualTo("DUPLICATE_CART_ITEM");
          assertThat(cartItems())
              .singleElement()
              .satisfies(i -> assertThat(i.getDelisted()).isTrue());
          return null;
        });
  }

  @Test
  @DisplayName("POST /cart/items — 남의 장바구니에 있어도 내 장바구니에 없으면 409 PLACE_DELISTED (이미 있음은 사용자마다)")
  void cartDuplicateIsPerUser() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          UUID mine = install;
          install = UUID.randomUUID();
          addToCart(p);
          install = mine;
          hidePlace(p);

          ApiException e = apiError(() -> addToCart(p));

          assertThat(e.getCode()).isEqualTo("PLACE_DELISTED");
          return null;
        });
  }

  @Test
  @DisplayName("POST /cart/items — 숨김이 풀리면 다시 201")
  void cartAcceptsAfterUnhide() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          hidePlace(p);
          assertThat(apiError(() -> addToCart(p)).getCode()).isEqualTo("PLACE_DELISTED");

          unhidePlace(p);

          assertThat(addToCart(p).getStatusCode()).isEqualTo(HttpStatus.CREATED);
          return null;
        });
  }

  @Test
  @DisplayName("POST /cart/items — 없는 촬영지는 지금처럼 404 PLACE_NOT_FOUND")
  void cartMissingPlaceIsStillNotFound() {
    rolledBack(
        () -> {
          ApiException e = apiError(() -> addToCart(-1L));

          assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
          assertThat(e.getCode()).isEqualTo("PLACE_NOT_FOUND");
          return null;
        });
  }

  // ───────────── 찜 ─────────────

  @Test
  @DisplayName("POST /favorites/contents — 보이는 작품은 204")
  void favoriteAddsVisibleContent() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);

          assertThat(favorite(c).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(favoriteItems()).extracting(ContentSummary::getId).containsExactly(c);
          return null;
        });
  }

  @Test
  @DisplayName("POST /favorites/contents — 찜하지 않은 숨긴 작품은 409 CONTENT_DELISTED, 찜에 들어가지 않는다")
  void favoriteRejectsNewHiddenContent() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          hideContent(c);

          ApiException e = apiError(() -> favorite(c));

          assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e.getCode()).isEqualTo("CONTENT_DELISTED");
          assertThat(favoriteItems()).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("POST /favorites/contents — 이미 찜한 숨긴 작품은 지금처럼 204, 찜은 하나로 남고 delisted true")
  void favoriteAlreadyThereIsNoContent() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          favorite(c);
          hideContent(c);

          assertThat(favorite(c).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(favoriteItems())
              .singleElement()
              .satisfies(s -> assertThat(s.getDelisted()).isTrue());
          return null;
        });
  }

  @Test
  @DisplayName("POST /favorites/contents — 숨긴 작품을 찜에서 뺀 뒤 다시 찜하면 409 CONTENT_DELISTED")
  void favoriteRemovedThenReAddedIsBlocked() {
    rolledBack(
        () -> {
          long c = visibleContents(1).get(0);
          favorite(c);
          hideContent(c);
          favorites.removeFavoriteContent(install, c);

          assertThat(apiError(() -> favorite(c)).getCode()).isEqualTo("CONTENT_DELISTED");
          return null;
        });
  }

  // ───────────── 코스 편집 ─────────────

  @Test
  @DisplayName("PUT /courses/{id} — 새 줄이 숨긴 촬영지면 409 PLACE_DELISTED, 코스는 그대로")
  void courseRejectsNewHiddenPlace() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          hidePlace(p);
          long c = newCourse(1);

          ApiException e = apiError(() -> put(c, day(placeItem(p))));

          assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e.getCode()).isEqualTo("PLACE_DELISTED");
          assertThat(items(c)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("PUT /courses/{id} — 새 줄이 폐업한 편의시설이면 409 POI_CLOSED, 코스는 그대로")
  void courseRejectsNewClosedPoi() {
    rolledBack(
        () -> {
          long q = poi("closed", 0);
          closePoi(q);
          long c = newCourse(1);

          ApiException e = apiError(() -> put(c, day(poiItem(q))));

          assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(e.getCode()).isEqualTo("POI_CLOSED");
          assertThat(items(c)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("PUT /courses/{id} — 새 줄이 없는 편의시설이면 지금처럼 400 POI_NOT_FOUND")
  void courseMissingPoiIsStillBadRequest() {
    rolledBack(
        () -> {
          long missing =
              jdbc.sql("SELECT COALESCE(max(id), 0) + 1000 FROM poi").query(Long.class).single();
          long c = newCourse(1);

          ApiException e = apiError(() -> put(c, day(poiItem(missing))));

          assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(e.getCode()).isEqualTo("POI_NOT_FOUND");
          return null;
        });
  }

  @Test
  @DisplayName("PUT /courses/{id} — 이미 있던 숨긴 촬영지·폐업 편의시설 줄(id 있음)은 200 으로 그대로 저장되고, 다른 편집도 함께 반영된다")
  void courseKeepsExistingDelistedAndClosedRows() {
    rolledBack(
        () -> {
          List<Long> ps = visiblePlaces(2);
          long hiddenP = ps.get(0);
          long otherP = ps.get(1);
          long q = poi("kept", 0);
          long c = newCourse(1);
          List<CourseItem> before =
              put(c, day(placeItem(hiddenP), poiItem(q))).getBody().getDays().get(0).getItems();
          long placeRowId = before.get(0).getId();
          long poiRowId = before.get(1).getId();
          hidePlace(hiddenP);
          closePoi(q);

          // 순서를 바꾸고 체류시간을 고치고, 보이는 촬영지를 새로 더한다.
          ResponseEntity<CourseDetail> saved =
              put(
                  c,
                  day(
                      poiItem(q).id(poiRowId).dwellMinutes(90),
                      placeItem(hiddenP).id(placeRowId),
                      placeItem(otherP)));

          assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
          List<CourseItem> after = saved.getBody().getDays().get(0).getItems();
          assertThat(after).hasSize(3);
          assertThat(after.get(0).getId()).isEqualTo(poiRowId);
          assertThat(after.get(0).getClosed()).isTrue();
          assertThat(after.get(0).getDwellMinutes()).isEqualTo(90);
          assertThat(after.get(1).getId()).isEqualTo(placeRowId);
          assertThat(after.get(1).getDelisted()).isTrue();
          assertThat(after.get(2).getPlaceId()).isEqualTo(otherP);
          assertThat(after.get(2).getDelisted()).isNotEqualTo(Boolean.TRUE);
          // GET 도 같은 것을 준다.
          assertThat(items(c)).extracting(CourseItem::getId).startsWith(poiRowId, placeRowId);
          return null;
        });
  }

  @Test
  @DisplayName("PUT /courses/{id} — 숨긴 촬영지 줄을 빼고 저장한 뒤 id 없이 다시 넣으면 409 PLACE_DELISTED")
  void courseHiddenPlaceRemovedThenReAddedIsBlocked() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          long c = newCourse(1);
          put(c, day(placeItem(p)));
          hidePlace(p);

          assertThat(put(c, day()).getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(items(c)).isEmpty();

          assertThat(apiError(() -> put(c, day(placeItem(p)))).getCode())
              .isEqualTo("PLACE_DELISTED");
          return null;
        });
  }

  @Test
  @DisplayName("PUT /courses/{id} — 폐업 편의시설 줄을 빼고 저장한 뒤 id 없이 다시 넣으면 409 POI_CLOSED")
  void courseClosedPoiRemovedThenReAddedIsBlocked() {
    rolledBack(
        () -> {
          long q = poi("readd", 0);
          long c = newCourse(1);
          put(c, day(poiItem(q)));
          closePoi(q);

          put(c, day());

          assertThat(apiError(() -> put(c, day(poiItem(q)))).getCode()).isEqualTo("POI_CLOSED");
          return null;
        });
  }

  @Test
  @DisplayName(
      "PUT /courses/{id} — 이미 있는 숨긴 촬영지를 id 없이 보내면(빼고 새로 넣기) 409 PLACE_DELISTED, 원래 줄은 남는다")
  void courseExistingHiddenPlaceSentWithoutIdIsBlocked() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          long c = newCourse(1);
          long rowId =
              put(c, day(placeItem(p))).getBody().getDays().get(0).getItems().get(0).getId();
          hidePlace(p);

          ApiException e = apiError(() -> put(c, day(placeItem(p))));

          assertThat(e.getCode()).isEqualTo("PLACE_DELISTED");
          assertThat(items(c))
              .singleElement()
              .satisfies(
                  i -> {
                    assertThat(i.getId()).isEqualTo(rowId);
                    assertThat(i.getDelisted()).isTrue();
                  });
          return null;
        });
  }

  @Test
  @DisplayName("PUT /courses/{id} — 409 이면 같은 요청의 다른 편집도 반영되지 않는다(전부 아니면 전무)")
  void courseRejectionIsAtomic() {
    // 편집 완료의 트랜잭션이 가장 바깥이어야 그 롤백을 볼 수 있다 — 바깥 트랜잭션에 합류하면 거절 전에 쓴 것이 남아 보인다.
    committed(
        () -> {
          List<Long> ps = visiblePlaces(2);
          long shown = ps.get(0);
          long hiddenP = ps.get(1);
          long c = newCourse(1);
          long rowId =
              put(c, day(placeItem(shown).dwellMinutes(60)))
                  .getBody()
                  .getDays()
                  .get(0)
                  .getItems()
                  .get(0)
                  .getId();
          hidePlace(hiddenP);

          ApiException e =
              apiError(
                  () ->
                      put(
                          c,
                          day(placeItem(shown).id(rowId).dwellMinutes(120), placeItem(hiddenP))));

          assertThat(e.getCode()).isEqualTo("PLACE_DELISTED");
          assertThat(items(c))
              .singleElement()
              .satisfies(
                  i -> {
                    assertThat(i.getId()).isEqualTo(rowId);
                    assertThat(i.getDwellMinutes()).isEqualTo(60);
                  });
          return null;
        });
  }

  @Test
  @DisplayName("PUT /courses/{id} — 핀 줄과 보이는 촬영지·영업 편의시설 새 줄은 지금처럼 200")
  void courseOrdinaryNewRowsStillSave() {
    rolledBack(
        () -> {
          long p = visiblePlaces(1).get(0);
          long q = poi("open", 0);
          long c = newCourse(1);

          ResponseEntity<CourseDetail> saved =
              put(
                  c,
                  day(
                      placeItem(p),
                      poiItem(q),
                      new CourseItemInput()
                          .dwellMinutes(60)
                          .customPin(
                              new com.mz2az.scenetrip.sceneapi.api.model.CustomPinInput(
                                  "시험 숙소",
                                  com.mz2az.scenetrip.sceneapi.api.model.PinCategory.LODGING,
                                  LAT + 0.01,
                                  LNG))));

          assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(saved.getBody().getDays().get(0).getItems())
              .extracting(CourseItem::getSource)
              .containsExactly(
                  CourseItemSource.PLACE, CourseItemSource.POI, CourseItemSource.CUSTOM_PIN);
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  private void rolledBack(Supplier<Void> work) {
    IntegrationDatabase.rolledBack(
        () -> {
          install = UUID.randomUUID();
          return work.get();
        });
  }

  /** 숨긴 촬영지 — {@link #committed} 가 끝에 숨김을 푼다(고른 것은 모두 원래 보이던 것이다). */
  private final List<Long> hiddenPlaces = new java.util.ArrayList<>();

  /**
   * 커밋하며 돌리고 끝에 되돌린다 — 이 시험이 만든 비회원 계정(장바구니·코스는 CASCADE)을 지우고, 숨긴 적재 촬영지의 숨김을 푼다. 단언이 실패해도 finally
   * 에서 정리한다.
   */
  private void committed(Supplier<Void> work) {
    install = UUID.randomUUID();
    try {
      work.get();
    } finally {
      for (long id : hiddenPlaces) {
        unhidePlace(id);
      }
      jdbc.sql(
              "DELETE FROM app_user WHERE id IN (SELECT user_id FROM user_device"
                  + " WHERE install_uuid = CAST(:i AS UUID))")
          .param("i", install.toString())
          .update();
    }
  }

  private static ApiException apiError(Runnable call) {
    ApiException e = catchThrowableOfType(ApiException.class, call::run);
    assertThat(e).as("ApiException 이 나야 한다").isNotNull();
    return e;
  }

  private PlaceDetail placeDetail(long id) {
    return places.getPlace(id, Lang.KO, null, null).getBody();
  }

  private ContentDetail contentDetail(long id) {
    return contents.getContent(id, Lang.KO).getBody();
  }

  private ResponseEntity<CartItem> addToCart(long placeId) {
    return cart.addCartItem(install, new CartItemCreate(placeId), Lang.KO);
  }

  private List<CartItem> cartItems() {
    return cart.getCart(install, Lang.KO).getBody().getItems();
  }

  private ResponseEntity<Void> favorite(long contentId) {
    return favorites.addFavoriteContent(install, new FavoriteContentCreate(contentId));
  }

  private List<ContentSummary> favoriteItems() {
    return favorites.listFavoriteContents(install, Lang.KO, 20, 0).getBody().getItems();
  }

  private long newCourse(int days) {
    return courses
        .createCourse(install, new CourseCreate(days, CourseOrigin.SELF), Lang.KO)
        .getBody()
        .getId();
  }

  private ResponseEntity<CourseDetail> put(long courseId, CourseDayInput... days) {
    return courses.replaceCourse(
        install, courseId, new CourseReplace("새로 담기 막기 시험", List.of(days)), Lang.KO);
  }

  private List<CourseItem> items(long courseId) {
    return courses.getCourse(install, courseId, Lang.KO).getBody().getDays().stream()
        .flatMap(d -> d.getItems().stream())
        .toList();
  }

  private static CourseDayInput day(CourseItemInput... items) {
    return new CourseDayInput(List.of(items));
  }

  private static CourseItemInput placeItem(long placeId) {
    return new CourseItemInput().placeId(placeId).dwellMinutes(60);
  }

  private static CourseItemInput poiItem(long poiId) {
    return new CourseItemInput().poiId(poiId).dwellMinutes(60);
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

  private static List<Long> visibleContents(int n) {
    List<Long> ids =
        jdbc.sql("SELECT id FROM content WHERE hidden_at IS NULL ORDER BY id LIMIT :n")
            .param("n", n)
            .query(Long.class)
            .list();
    assertThat(ids).as("숨기지 않은 작품 %d 개", n).hasSize(n);
    return ids;
  }

  /** 바다 위의 편의시설. 촬영지와 연결하지 않으므로 편의시설 줄로 저장된다. */
  private long poi(String key, double dxMeters) {
    return jdbc.sql(
            """
            INSERT INTO poi (source_id, name, geom, category, category_group, address)
            VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '카페', 'food',
                    '부산 기장군 시험동')
            RETURNING id
            """)
        .param("s", "it-delisted-flow-" + tag + "-" + key)
        .param("n", "시험 편의시설 " + key)
        .param("lng", LNG + dxMeters / 91_300.0)
        .param("lat", LAT)
        .query(Long.class)
        .single();
  }

  private void hidePlace(long id) {
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", id).update();
    hiddenPlaces.add(id);
  }

  private static void unhidePlace(long id) {
    jdbc.sql("UPDATE place SET hidden_at = NULL WHERE id = :id").param("id", id).update();
  }

  private static void hideContent(long id) {
    jdbc.sql("UPDATE content SET hidden_at = now() WHERE id = :id").param("id", id).update();
  }

  private static void closePoi(long id) {
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", id).update();
  }

  private static CourseStore courseStore() {
    DwellDefaults dwell = new DwellDefaults();
    dwell.setFallback(60);
    dwell.setByPoiGroup("food=60, sight=60, stay=30, transit=15");
    return new CourseStore(
        jdbc, DeployedTravel.estimator(), dwell, IntegrationDatabase.transactions());
  }

  private static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }

  /** 상세의 사진첩이 부르는 저장소. 이 시험은 사진을 보지 않는다 — 비어 있는 것처럼. */
  private static final class NoPhotos implements PhotoStorage {
    @Override
    public boolean available() {
      return true;
    }

    @Override
    public URI viewUrl(String storageKey) {
      return URI.create("https://signed.example/" + storageKey);
    }

    @Override
    public PresignedUpload presignUpload(
        String storageKey, String contentType, long bytes, Duration expiresIn) {
      return new PresignedUpload(
          URI.create("https://bucket.example/" + storageKey),
          Map.of("Content-Type", contentType),
          Instant.now().plus(expiresIn));
    }

    @Override
    public void move(String fromKey, String toKey) {}

    @Override
    public void listPages(
        String prefix, java.util.function.Consumer<java.util.List<StoredObject>> onPage) {}

    @Override
    public void delete(String storageKey) {}
  }
}
