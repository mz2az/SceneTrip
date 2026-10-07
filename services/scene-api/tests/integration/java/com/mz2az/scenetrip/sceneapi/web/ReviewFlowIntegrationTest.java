package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.Me;
import com.mz2az.scenetrip.sceneapi.api.model.MyReview;
import com.mz2az.scenetrip.sceneapi.api.model.NicknameInput;
import com.mz2az.scenetrip.sceneapi.api.model.Photo;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoSource;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.Review;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewInput;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewList;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewSort;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewTargetType;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.auth.AppleClient;
import com.mz2az.scenetrip.sceneapi.auth.AppleLogin;
import com.mz2az.scenetrip.sceneapi.auth.RefreshTokenStore;
import com.mz2az.scenetrip.sceneapi.auth.SignInService;
import com.mz2az.scenetrip.sceneapi.auth.TokenCipher;
import com.mz2az.scenetrip.sceneapi.place.PlaceStores;
import com.mz2az.scenetrip.sceneapi.poi.PoiStores;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리뷰 창구를 <b>진짜 Store 와 진짜 PostgreSQL</b> 위에서 계약 1.4.0(tag {@code reviews}, {@code PUT
 * /me/nickname}, {@code DELETE /me} 설명, 상세의 {@code rating}·{@code photos}·{@code photoCount})에 비춰
 * 본다.
 *
 * <p>{@code AuthFlowIntegrationTest} 와 같이 스프링 없이 컨트롤러를 손으로 조립하고, 요청은 {@link MockHttpServletRequest}
 * 의 헤더만 바꿔 쓴다. 거절은 {@link ApiException} 의 상태·코드로 본다. 사진 저장소는 가짜다 — 「이 사용자가 올린 키」 를 시험이 정한다.
 *
 * <p>시험마다 제 촬영지·편의시설·계정을 만들고 끝나면 지운다.
 */
@DisplayName("리뷰 흐름 — 쓰기·목록·내 리뷰·사진첩·상세·닉네임·탈퇴 (실제 DB)")
class ReviewFlowIntegrationTest {

  private static final byte[] SECRET = filled(32, (byte) 9);

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static UserStore users;
  private static AccountLinkStore links;

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private final CurrentAccount accounts = new CurrentAccount(request, tokens, users);
  private final FakeStorage storage = new FakeStorage();
  private final ReviewStore store = new ReviewStore(jdbc, transactions);
  private final ReviewViews views = new ReviewViews(store, storage);
  private final ReviewsController reviews = new ReviewsController(store, views, storage, accounts);
  private final PlacesController places = new PlacesController(PlaceStores.create(jdbc), views);
  private final PoisController pois = new PoisController(PoiStores.create(jdbc), null, views);
  private final RefreshTokenStore refreshTokens =
      new RefreshTokenStore(jdbc, transactions, Duration.ofDays(60), Clock.systemUTC());
  private final AuthController auth =
      new AuthController(
          tokens,
          refreshTokens,
          users,
          accounts,
          null,
          new SignInService(users, links, refreshTokens, transactions),
          disabledApple());

  private final List<UUID> createdUsers = new ArrayList<>();
  private final List<Long> createdPlaces = new ArrayList<>();
  private final List<Long> createdPois = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
    users = new UserStore(jdbc);
    links = new AccountLinkStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    for (long id : createdPlaces) {
      jdbc.sql("DELETE FROM place WHERE id = :id").param("id", id).update();
    }
    for (long id : createdPois) {
      jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", id).update();
    }
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ───────────── 쓰기 권한 ─────────────

  @Test
  @DisplayName("토큰 없이 쓰기·내 리뷰·지우기는 401 SIGN_IN_REQUIRED 이고 아무것도 남지 않는다")
  void guestCannotWrite() {
    long place = place("시험 촬영지");
    withoutToken();

    expect(
        () -> reviews.putMyPlaceReview(place, input(5, null)),
        HttpStatus.UNAUTHORIZED,
        "SIGN_IN_REQUIRED");
    expect(() -> reviews.getMyPlaceReview(place), HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED");
    expect(() -> reviews.deleteMyPlaceReview(place), HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED");
    assertThat(reviewCount(place)).isZero();
  }

  // ───────────── 쓰기 → 읽기 → 고치기 → 지우기 ─────────────

  @Test
  @DisplayName(
      "한 사람의 흐름 — 쓰기 200 → 목록(토큰 없으면 isMine=false, 있으면 true) → 고치기(같은 id) → 지우기 204 → 내 리뷰 404 → 다시"
          + " 지워도 204")
  void writeReadUpdateDelete() throws InterruptedException {
    long place = place("시험 촬영지");
    UUID me = member();
    String myNickname = users.profile(me).orElseThrow().nickname();
    as(me);

    Review written = reviews.putMyPlaceReview(place, input(4, "  좋았어요 ")).getBody();
    assertThat(written.getRating()).isEqualTo(4);
    assertThat(written.getBody()).isEqualTo("좋았어요");
    assertThat(written.getIsMine()).isTrue();
    assertThat(written.getAuthor().getNickname()).isEqualTo(myNickname);
    assertThat(written.getUpdatedAt().isEqual(written.getCreatedAt())).isTrue();

    withoutToken();
    ReviewList anonymous = reviews.listPlaceReviews(place, null, null, null).getBody();
    assertThat(anonymous.getTotal()).isEqualTo(1);
    assertThat(anonymous.getItems().get(0).getIsMine()).isFalse();
    assertThat(anonymous.getSummary().getAverage()).isEqualTo(4.0);
    assertThat(anonymous.getSummary().getCount()).isEqualTo(1);
    assertThat(anonymous.getSummary().getDistribution()).containsExactly(0, 0, 0, 1, 0);

    as(me);
    assertThat(
            reviews
                .listPlaceReviews(place, null, null, null)
                .getBody()
                .getItems()
                .get(0)
                .getIsMine())
        .isTrue();
    assertThat(reviews.getMyPlaceReview(place).getBody().getId()).isEqualTo(written.getId());

    Thread.sleep(20);
    Review updated = reviews.putMyPlaceReview(place, input(2, "   ")).getBody();
    assertThat(updated.getId()).isEqualTo(written.getId());
    assertThat(updated.getRating()).isEqualTo(2);
    assertThat(updated.getBody()).isNull();
    assertThat(updated.getCreatedAt().isEqual(written.getCreatedAt())).isTrue();
    assertThat(updated.getUpdatedAt()).isAfter(written.getUpdatedAt());
    assertThat(reviewCount(place)).isEqualTo(1);

    assertThat(reviews.deleteMyPlaceReview(place).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    expect(() -> reviews.getMyPlaceReview(place), HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
    assertThat(reviews.deleteMyPlaceReview(place).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(reviewCount(place)).isZero();
  }

  @Test
  @DisplayName(
      "올리지 않은 사진 키면 400 REVIEW_PHOTO_INVALID 이고 기존 리뷰는 그대로다(사진 올리기 창구가 없어 빈 photoKeys 만 통한다)")
  void unknownPhotoKeyRejectedAndNothingChanges() {
    long poi = poi("시험 식당");
    UUID me = member();
    as(me);
    Review before = reviews.putMyPoiReview(poi, input(3, "처음")).getBody();

    expect(
        () ->
            reviews.putMyPoiReview(
                poi, input(5, "바꿈").photoKeys(List.of("uploads/tmp/" + UUID.randomUUID()))),
        HttpStatus.BAD_REQUEST,
        "REVIEW_PHOTO_INVALID");

    Review after = reviews.getMyPoiReview(poi).getBody();
    assertThat(after.getRating()).isEqualTo(3);
    assertThat(after.getBody()).isEqualTo("처음");
    assertThat(after.getUpdatedAt().isEqual(before.getUpdatedAt())).isTrue();
  }

  @Test
  @DisplayName("올리지 않은 사진 키로 처음 쓰면 400 REVIEW_PHOTO_INVALID 이고 리뷰가 생기지 않는다")
  void unknownPhotoKeyOnFirstWriteCreatesNothing() {
    long place = place("시험 촬영지");
    as(member());

    expect(
        () ->
            reviews.putMyPlaceReview(
                place, input(5, null).photoKeys(List.of("uploads/tmp/" + UUID.randomUUID()))),
        HttpStatus.BAD_REQUEST,
        "REVIEW_PHOTO_INVALID");
    assertThat(reviewCount(place)).isZero();
  }

  // ───────────── 대상 ─────────────

  @Test
  @DisplayName("숨긴 촬영지에는 쓸 수 있다, 폐업한 편의시설은 404 POI_NOT_FOUND, 없는 촬영지는 404 PLACE_NOT_FOUND")
  void targetRules() {
    long hidden = place("숨긴 촬영지");
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", hidden).update();
    long closed = poi("폐업 식당");
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", closed).update();
    as(member());

    assertThat(reviews.putMyPlaceReview(hidden, input(5, null)).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(reviews.listPlaceReviews(hidden, null, null, null).getBody().getTotal())
        .isEqualTo(1);

    expect(
        () -> reviews.putMyPoiReview(closed, input(5, null)),
        HttpStatus.NOT_FOUND,
        "POI_NOT_FOUND");
    expect(
        () -> reviews.listPoiReviews(closed, null, null, null),
        HttpStatus.NOT_FOUND,
        "POI_NOT_FOUND");
    expect(() -> reviews.listPoiPhotos(closed, null, null), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
    expect(
        () -> reviews.putMyPlaceReview(-1L, input(5, null)),
        HttpStatus.NOT_FOUND,
        "PLACE_NOT_FOUND");
    expect(
        () -> reviews.listPlaceReviews(-1L, null, null, null),
        HttpStatus.NOT_FOUND,
        "PLACE_NOT_FOUND");
  }

  // ───────────── 정렬 ─────────────

  @Test
  @DisplayName("목록 정렬 — rating_high · rating_low · recent")
  void listSorting() throws InterruptedException {
    long poi = poi("시험 식당");
    List<Long> ids = new ArrayList<>();
    for (int rating : List.of(2, 5, 3)) {
      as(member());
      ids.add(reviews.putMyPoiReview(poi, input(rating, null)).getBody().getId());
      Thread.sleep(20);
    }
    withoutToken();

    assertThat(ratings(reviews.listPoiReviews(poi, ReviewSort.RATING_HIGH, null, null).getBody()))
        .containsExactly(5, 3, 2);
    assertThat(ratings(reviews.listPoiReviews(poi, ReviewSort.RATING_LOW, null, null).getBody()))
        .containsExactly(2, 3, 5);
    assertThat(
            reviews.listPoiReviews(poi, ReviewSort.RECENT, null, null).getBody().getItems().stream()
                .map(Review::getId)
                .toList())
        .containsExactly(ids.get(2), ids.get(1), ids.get(0));
  }

  // ───────────── 내가 쓴 리뷰 ─────────────

  @Test
  @DisplayName("GET /me/reviews — 촬영지·편의시설을 섞어 최신순, target.name 은 촬영지면 요청 언어 → en → ko, 편의시설이면 한국어")
  void myReviewsAcrossTargets() throws InterruptedException {
    long place = place("북촌 시험", "Bukchon Test", "北村テスト");
    long poi = poi("시험 식당");
    UUID me = member();
    as(me);
    reviews.putMyPlaceReview(place, input(5, null));
    Thread.sleep(20);
    reviews.putMyPoiReview(poi, input(4, null));

    List<MyReview> ja = reviews.listMyReviews(Lang.JA, null, null).getBody().getItems();
    assertThat(ja).hasSize(2);
    assertThat(ja.get(0).getTarget().getType()).isEqualTo(ReviewTargetType.POI);
    assertThat(ja.get(0).getTarget().getId()).isEqualTo(poi);
    assertThat(ja.get(0).getTarget().getName()).isEqualTo("시험 식당");
    assertThat(ja.get(1).getTarget().getType()).isEqualTo(ReviewTargetType.PLACE);
    assertThat(ja.get(1).getTarget().getName()).isEqualTo("北村テスト");

    List<MyReview> zh = reviews.listMyReviews(Lang.ZH_HANT, null, null).getBody().getItems();
    assertThat(zh.get(1).getTarget().getName()).isEqualTo("Bukchon Test");
    assertThat(zh.get(0).getTarget().getName()).isEqualTo("시험 식당");
    assertThat(reviews.listMyReviews(Lang.JA, null, null).getBody().getTotal()).isEqualTo(2);
  }

  @Test
  @DisplayName("GET /me/reviews — 토큰이 없으면 401")
  void myReviewsNeedToken() {
    withoutToken();

    assertThatThrownBy(() -> reviews.listMyReviews(Lang.EN, null, null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  // ───────────── 사진첩·상세 ─────────────

  @Test
  @DisplayName("사진첩·상세 — 우리 사진 먼저, 그 뒤 리뷰 사진(서명 주소·reviewId). 상세에 rating·photos(앞 20)·photoCount")
  void galleryAndDetail() {
    long place = place("시험 촬영지");
    placeImage(place, "https://img.example/a.jpg", 0);
    placeImage(place, "https://img.example/b.jpg", 1);
    UUID me = member();
    String key = "reviews/" + UUID.randomUUID();
    storage.upload(me, key);
    as(me);
    Review review =
        reviews.putMyPlaceReview(place, input(5, "사진도").photoKeys(List.of(key))).getBody();
    assertThat(review.getPhotos()).hasSize(1);
    assertThat(review.getPhotos().get(0).getKey()).isEqualTo(key);
    assertThat(review.getPhotos().get(0).getUrl()).isEqualTo(FakeStorage.url(key));

    withoutToken();
    var gallery = reviews.listPlacePhotos(place, null, null).getBody();
    assertThat(gallery.getTotal()).isEqualTo(3);
    assertThat(gallery.getItems())
        .extracting(Photo::getSource)
        .containsExactly(PhotoSource.OFFICIAL, PhotoSource.OFFICIAL, PhotoSource.REVIEW);
    assertThat(gallery.getItems().get(0).getUrl())
        .isEqualTo(URI.create("https://img.example/a.jpg"));
    assertThat(gallery.getItems().get(2).getUrl()).isEqualTo(FakeStorage.url(key));
    assertThat(gallery.getItems().get(2).getReviewId()).isEqualTo(review.getId());

    var page = reviews.listPlacePhotos(place, 1, 2).getBody();
    assertThat(page.getTotal()).isEqualTo(3);
    assertThat(page.getItems()).extracting(Photo::getSource).containsExactly(PhotoSource.REVIEW);

    PlaceDetail detail = places.getPlace(place, Lang.KO, null, null).getBody();
    assertThat(detail.getRating().getAverage()).isEqualTo(5.0);
    assertThat(detail.getRating().getCount()).isEqualTo(1);
    assertThat(detail.getRating().getDistribution()).containsExactly(0, 0, 0, 0, 1);
    assertThat(detail.getPhotos()).hasSize(3);
    assertThat(detail.getPhotos().get(0).getSource()).isEqualTo(PhotoSource.OFFICIAL);
    assertThat(detail.getPhotoCount()).isEqualTo(3);
  }

  @Test
  @DisplayName("상세의 photos 는 앞 20 장까지, photoCount 는 전체 수")
  void detailPhotosCappedAtTwenty() {
    long place = place("시험 촬영지");
    for (int i = 0; i < 23; i++) {
      placeImage(place, "https://img.example/" + i + ".jpg", i);
    }

    PlaceDetail detail = places.getPlace(place, Lang.KO, null, null).getBody();

    assertThat(detail.getPhotos()).hasSize(20);
    assertThat(detail.getPhotoCount()).isEqualTo(23);
    assertThat(detail.getRating().getCount()).isZero();
    assertThat(detail.getRating().getAverage()).isNull();
  }

  @Test
  @DisplayName("편의시설 상세에 rating·photos·photoCount — 리뷰가 없으면 count 0, average null")
  void poiDetailCarriesRating() {
    long poi = poi("시험 식당");
    PoiDetail empty = pois.getPoi(poi, Lang.KO, null, null).getBody();
    assertThat(empty.getRating().getCount()).isZero();
    assertThat(empty.getRating().getAverage()).isNull();
    assertThat(empty.getPhotoCount()).isZero();

    as(member());
    reviews.putMyPoiReview(poi, input(3, null));
    as(member());
    reviews.putMyPoiReview(poi, input(4, null));

    PoiDetail detail = pois.getPoi(poi, Lang.KO, null, null).getBody();
    assertThat(detail.getRating().getAverage()).isEqualTo(3.5);
    assertThat(detail.getRating().getCount()).isEqualTo(2);
  }

  // ───────────── 닉네임 ─────────────

  @Test
  @DisplayName("PUT /me/nickname — 바뀐 Me(nicknameConfirmed=true), 리뷰 작성자도 새 닉네임으로 보인다")
  void nicknameChangeShowsOnReviews() {
    long place = place("시험 촬영지");
    UUID me = member();
    as(me);
    assertThat(auth.getMe().getBody().getNicknameConfirmed()).isFalse();
    assertThat(auth.getMe().getBody().getNickname()).matches("^여행자[0-9]+$");
    reviews.putMyPlaceReview(place, input(5, null));
    String chosen = "it" + suffix();

    Me updated = auth.setMyNickname(new NicknameInput("  " + chosen + "  ")).getBody();

    assertThat(updated.getNickname()).isEqualTo(chosen);
    assertThat(updated.getNicknameConfirmed()).isTrue();
    withoutToken();
    assertThat(
            reviews
                .listPlaceReviews(place, null, null, null)
                .getBody()
                .getItems()
                .get(0)
                .getAuthor()
                .getNickname())
        .isEqualTo(chosen);
  }

  @Test
  @DisplayName("PUT /me/nickname — 다른 사람 것과 대소문자만 달라도 409 NICKNAME_TAKEN, 내 것은 대소문자만 바꿔도 200")
  void nicknameUniquenessThroughController() {
    UUID owner = member();
    UUID other = member();
    String name = "Jeju" + suffix();
    as(owner);
    auth.setMyNickname(new NicknameInput(name));

    as(other);
    expect(
        () -> auth.setMyNickname(new NicknameInput(name.toLowerCase())),
        HttpStatus.CONFLICT,
        "NICKNAME_TAKEN");
    assertThat(auth.getMe().getBody().getNicknameConfirmed()).isFalse();

    as(owner);
    assertThat(auth.setMyNickname(new NicknameInput(name.toUpperCase())).getBody().getNickname())
        .isEqualTo(name.toUpperCase());
  }

  @Test
  @DisplayName("PUT /me/nickname — 「여행자 + 숫자」 와 규칙 밖의 닉네임은 400 NICKNAME_INVALID 이고 DB 는 그대로")
  void nicknameInvalidLeavesDbUntouched() {
    UUID me = member();
    as(me);
    String before = auth.getMe().getBody().getNickname();

    expect(
        () -> auth.setMyNickname(new NicknameInput("여행자99999")),
        HttpStatus.BAD_REQUEST,
        "NICKNAME_INVALID");
    expect(
        () -> auth.setMyNickname(new NicknameInput("a")),
        HttpStatus.BAD_REQUEST,
        "NICKNAME_INVALID");

    assertThat(auth.getMe().getBody().getNickname()).isEqualTo(before);
    assertThat(auth.getMe().getBody().getNicknameConfirmed()).isFalse();
  }

  // ───────────── 탈퇴 ─────────────

  @Test
  @DisplayName("DELETE /me — 리뷰는 남고 author 가 null, 별점 요약도 그대로. 계정은 지워진다")
  void deleteMeKeepsReviewsWithoutAuthor() {
    long place = place("시험 촬영지");
    long poi = poi("시험 식당");
    UUID leaver = member();
    as(leaver);
    Review placeReview = reviews.putMyPlaceReview(place, input(4, "남는 글")).getBody();
    reviews.putMyPoiReview(poi, input(2, null));

    assertThat(auth.deleteMe().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    withoutToken();
    assertThat(appUserCount(leaver)).isZero();
    ReviewList list = reviews.listPlaceReviews(place, null, null, null).getBody();
    assertThat(list.getTotal()).isEqualTo(1);
    Review kept = list.getItems().get(0);
    assertThat(kept.getId()).isEqualTo(placeReview.getId());
    assertThat(kept.getAuthor()).isNull();
    assertThat(kept.getBody()).isEqualTo("남는 글");
    assertThat(kept.getIsMine()).isFalse();
    assertThat(list.getSummary().getCount()).isEqualTo(1);
    assertThat(reviews.listPoiReviews(poi, null, null, null).getBody().getTotal()).isEqualTo(1);
  }

  // ───────────── 도우미 ─────────────

  /** 「이 사용자가 올린 키」 를 시험이 정하는 저장소. 서명 주소는 키로 만든 가짜다. */
  private static final class FakeStorage implements PhotoStorage {
    private final java.util.Map<UUID, Set<String>> uploads = new java.util.HashMap<>();

    void upload(UUID user, String key) {
      uploads.computeIfAbsent(user, u -> new HashSet<>()).add(key);
    }

    static URI url(String key) {
      return URI.create("https://signed.example/" + key);
    }

    @Override
    public URI viewUrl(String storageKey) {
      return url(storageKey);
    }

    @Override
    public Set<String> unattachedUploads(UUID user, Collection<String> keys) {
      Set<String> mine = uploads.getOrDefault(user, Set.of());
      return keys.stream().filter(mine::contains).collect(Collectors.toSet());
    }
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    createdUsers.add(id);
    links.register(id, "google", "it-flow-" + UUID.randomUUID(), null, null);
    return id;
  }

  private void as(UUID user) {
    request.removeHeader("Authorization");
    request.addHeader("Authorization", "Bearer " + tokens.issue(user).value());
  }

  private void withoutToken() {
    request.removeHeader("Authorization");
  }

  private static ReviewInput input(int rating, String body) {
    return new ReviewInput(rating, List.of()).body(body);
  }

  private static List<Integer> ratings(ReviewList list) {
    return list.getItems().stream().map(Review::getRating).toList();
  }

  private static void expect(Runnable call, HttpStatus status, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(status);
              assertThat(e.getCode()).isEqualTo(code);
            });
  }

  private long place(String ko) {
    return place(ko, null, null);
  }

  private long place(String ko, String en, String ja) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(126.98, 37.58), 4326)::geography) RETURNING id")
            .query(Long.class)
            .single();
    createdPlaces.add(id);
    placeName(id, "ko", ko);
    if (en != null) {
      placeName(id, "en", en);
    }
    if (ja != null) {
      placeName(id, "ja", ja);
    }
    return id;
  }

  private static void placeName(long placeId, String lang, String name) {
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, CAST(:l AS lang_code), :n)")
        .param("p", placeId)
        .param("l", lang)
        .param("n", name)
        .update();
  }

  private static void placeImage(long placeId, String url, int order) {
    jdbc.sql("INSERT INTO place_image (place_id, url, sort_order) VALUES (:p, :u, :o)")
        .param("p", placeId)
        .param("u", url)
        .param("o", order)
        .update();
  }

  private long poi(String name) {
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group)
                VALUES (:s, :n, ST_SetSRID(ST_MakePoint(126.25, 33.21), 4326)::geography, '한식', 'food')
                RETURNING id
                """)
            .param("s", "it-flow-" + UUID.randomUUID())
            .param("n", name)
            .query(Long.class)
            .single();
    createdPois.add(id);
    return id;
  }

  private static long reviewCount(long placeId) {
    return jdbc.sql("SELECT count(*) FROM review WHERE place_id = :p")
        .param("p", placeId)
        .query(Long.class)
        .single();
  }

  private static long appUserCount(UUID id) {
    return jdbc.sql("SELECT count(*) FROM app_user WHERE id = CAST(:id AS UUID)")
        .param("id", id.toString())
        .query(Long.class)
        .single();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  private static byte[] filled(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }

  /** 애플이 꺼진 AppleLogin — 탈퇴가 revokeFor 를 부르므로 null 대신 꺼진 것을 준다(AuthFlowIntegrationTest 와 같다). */
  private static AppleLogin disabledApple() {
    return new AppleLogin(
        null,
        new AppleClient(null, "com.example", "TEAM", "KEY", null, Clock.systemUTC()),
        new TokenCipher((byte[]) null),
        new AccountLinkStore(IntegrationDatabase.jdbcClient()));
  }
}
