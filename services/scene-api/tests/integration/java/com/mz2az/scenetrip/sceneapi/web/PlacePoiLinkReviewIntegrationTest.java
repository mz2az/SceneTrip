package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.MyReview;
import com.mz2az.scenetrip.sceneapi.api.model.Photo;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoList;
import com.mz2az.scenetrip.sceneapi.api.model.PhotoSource;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Review;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewInput;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewList;
import com.mz2az.scenetrip.sceneapi.api.model.ReviewTargetType;
import com.mz2az.scenetrip.sceneapi.api.model.UploadCreate;
import com.mz2az.scenetrip.sceneapi.api.model.UploadPurpose;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.place.PlaceStores;
import com.mz2az.scenetrip.sceneapi.poi.PoiStores;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinksForTests;
import com.mz2az.scenetrip.sceneapi.review.PhotoStorage;
import com.mz2az.scenetrip.sceneapi.review.ReviewStore;
import com.mz2az.scenetrip.sceneapi.review.UploadStore;
import com.mz2az.scenetrip.sceneapi.user.AccountLinkStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 촬영지와 같은 곳인 편의시설(docs/project/plans/place-poi-link.md §5·§6, 계약 scene-api 1.7.0) — 진짜 Store 와 진짜
 * PostgreSQL 위에서 명세만 보고 짠 시험.
 *
 * <ul>
 *   <li>편의시설 목록·상세에 {@code placeId} — 보이는 촬영지와 연결됐을 때만. 숨긴 촬영지면 없다(V25 주석).
 *   <li>편의시설 리뷰 창구(목록·요약·사진첩·내 리뷰·쓰기·지우기)는 연결된 편의시설이면 그 촬영지의 리뷰로 처리한다.
 *   <li>404 의 코드는 물은 대상의 것이다 — 없는 편의시설이면 {@code POI_NOT_FOUND}.
 * </ul>
 *
 * <p>연결은 연결 일(seed/place_poi_link.sql)을 거치지 않고 {@code place_poi_link} 에 직접 넣는다 — 그 일은 {@code
 * PlacePoiLinkJobIntegrationTest} 가 본다. 시험마다 제 촬영지·편의시설·계정을 만들고 끝나면 지운다(연결은 CASCADE).
 */
@DisplayName("같은 곳 연결 — 편의시설 응답의 placeId 와 리뷰 창구 바꿔 끼우기 (실제 DB)")
class PlacePoiLinkReviewIntegrationTest {

  private static final byte[] SECRET = filled(32, (byte) 7);

  /** 적재 데이터와 겹치지 않는 바다 위 한 점(대한해협). 목록 bbox 가 시험 픽스처만 보게. */
  private static final double LAT = 34.95;

  private static final double LNG = 129.55;
  private static final String BBOX = "129.54,34.94,129.56,34.96";

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
  private final UploadStore uploadStore = new UploadStore(jdbc);
  private final ReviewsController reviews =
      new ReviewsController(store, views, storage, uploadStore, accounts);
  private final UploadsController uploads = new UploadsController(storage, uploadStore, accounts);
  private final PoisController pois =
      new PoisController(PoiStores.create(jdbc), null, views, NaverLinksForTests.disabled(jdbc));
  private final PlacesController places = new PlacesController(PlaceStores.create(jdbc), views);

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
    for (long id : createdPois) {
      jdbc.sql("DELETE FROM poi WHERE id = :id").param("id", id).update();
    }
    for (long id : createdPlaces) {
      jdbc.sql("DELETE FROM place WHERE id = :id").param("id", id).update();
    }
    for (UUID id : createdUsers) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  // ───────────── placeId ─────────────

  @Test
  @DisplayName("GET /pois — 보이는 촬영지와 연결된 편의시설에만 placeId. 연결되지 않았거나 숨긴 촬영지와 연결된 것은 없다. 셋 다 목록에 남는다")
  void listCarriesPlaceId() {
    long place = place("링크시험 수원 카페 몽테드");
    long hiddenPlace = place("링크시험 숨긴 촬영지");
    hide(hiddenPlace);
    long linked = poi("링크시험 몽테드");
    long unlinked = poi("링크시험 동네김밥");
    long toHidden = poi("링크시험 숨긴 곳 가게");
    link(linked, place);
    link(toHidden, hiddenPlace);

    List<PoiSummary> items =
        pois.listPois(Lang.KO, BBOX, null, null, null, null, null, 200, 0).getBody().getItems();

    assertThat(items).extracting(PoiSummary::getId).contains(linked, unlinked, toHidden);
    assertThat(byId(items, linked).getPlaceId()).isEqualTo(place);
    assertThat(byId(items, unlinked).getPlaceId()).isNull();
    assertThat(byId(items, toHidden).getPlaceId()).isNull();
  }

  @Test
  @DisplayName("GET /pois/{id} — 연결된 편의시설이면 placeId, 아니면·숨긴 촬영지면 없다")
  void detailCarriesPlaceId() {
    long place = place("링크시험 촬영지");
    long hiddenPlace = place("링크시험 숨긴 촬영지");
    hide(hiddenPlace);
    long linked = poi("링크시험 가게");
    long unlinked = poi("링크시험 다른 가게");
    long toHidden = poi("링크시험 숨긴 곳 가게");
    link(linked, place);
    link(toHidden, hiddenPlace);

    assertThat(pois.getPoi(linked, Lang.KO, null, null).getBody().getPlaceId()).isEqualTo(place);
    assertThat(pois.getPoi(unlinked, Lang.KO, null, null).getBody().getPlaceId()).isNull();
    assertThat(pois.getPoi(toHidden, Lang.KO, null, null).getBody().getPlaceId()).isNull();
  }

  // ───────────── 리뷰 창구 — 촬영지로 처리 ─────────────

  @Test
  @DisplayName("편의시설 창구로 쓴 리뷰는 촬영지에 저장되고 촬영지 창구에 보인다 — 내 리뷰·목록·요약이 양쪽에서 같다")
  void writeThroughPoiLandsOnPlace() {
    long place = place("링크시험 촬영지");
    long poi = poi("링크시험 가게");
    link(poi, place);
    UUID me = member();
    as(me);

    Review written = reviews.putMyPoiReview(poi, input(4, "같은 곳")).getBody();

    var row =
        jdbc.sql("SELECT place_id, poi_id FROM review WHERE id = :id")
            .param("id", written.getId())
            .query()
            .singleRow();
    assertThat(((Number) row.get("place_id")).longValue()).isEqualTo(place);
    assertThat(row.get("poi_id")).isNull();

    assertThat(reviews.getMyPlaceReview(place).getBody().getId()).isEqualTo(written.getId());
    assertThat(reviews.getMyPoiReview(poi).getBody().getId()).isEqualTo(written.getId());

    withoutToken();
    ReviewList viaPlace = reviews.listPlaceReviews(place, null, null, null).getBody();
    ReviewList viaPoi = reviews.listPoiReviews(poi, null, null, null).getBody();
    assertThat(viaPlace.getTotal()).isEqualTo(1);
    assertThat(viaPlace.getItems()).extracting(Review::getId).containsExactly(written.getId());
    assertSameList(viaPoi, viaPlace);
  }

  @Test
  @DisplayName("촬영지 창구로 쓴 리뷰가 편의시설 창구 목록·내 리뷰에 보인다 (isMine 포함)")
  void placeReviewShowsOnPoi() {
    long place = place("링크시험 촬영지");
    long poi = poi("링크시험 가게");
    link(poi, place);
    UUID other = member();
    as(other);
    Review theirs = reviews.putMyPlaceReview(place, input(2, null)).getBody();
    UUID me = member();
    as(me);
    Review mine = reviews.putMyPlaceReview(place, input(5, "좋다")).getBody();

    ReviewList list = reviews.listPoiReviews(poi, null, null, null).getBody();
    assertThat(list.getTotal()).isEqualTo(2);
    assertThat(list.getItems())
        .extracting(Review::getId)
        .containsExactlyInAnyOrder(theirs.getId(), mine.getId());
    assertThat(list.getItems().stream().filter(Review::getIsMine).map(Review::getId))
        .containsExactly(mine.getId());
    assertThat(list.getSummary().getCount()).isEqualTo(2);
    assertThat(list.getSummary().getAverage()).isEqualTo(3.5);
    assertThat(list.getSummary().getDistribution()).containsExactly(0, 1, 0, 0, 1);
    assertThat(reviews.getMyPoiReview(poi).getBody().getId()).isEqualTo(mine.getId());
  }

  @Test
  @DisplayName("한 사람 한 곳 하나 — 촬영지에 쓴 뒤 편의시설 창구로 쓰면 같은 리뷰를 고친다")
  void poiPutEditsExistingPlaceReview() {
    long place = place("링크시험 촬영지");
    long poi = poi("링크시험 가게");
    link(poi, place);
    UUID me = member();
    as(me);
    Review first = reviews.putMyPlaceReview(place, input(2, "처음")).getBody();

    Review edited = reviews.putMyPoiReview(poi, input(5, "고침")).getBody();

    assertThat(edited.getId()).isEqualTo(first.getId());
    assertThat(edited.getRating()).isEqualTo(5);
    assertThat(reviewsOnPlace(place)).isEqualTo(1);
    assertThat(reviewsOnPoi(poi)).isZero();
    assertThat(reviews.getMyPlaceReview(place).getBody().getBody()).isEqualTo("고침");
  }

  @Test
  @DisplayName("편의시설 창구로 지우면 촬영지 리뷰가 지워진다 — 양쪽 내 리뷰가 404 REVIEW_NOT_FOUND")
  void poiDeleteRemovesPlaceReview() {
    long place = place("링크시험 촬영지");
    long poi = poi("링크시험 가게");
    link(poi, place);
    UUID me = member();
    as(me);
    reviews.putMyPlaceReview(place, input(3, null));

    assertThat(reviews.deleteMyPoiReview(poi).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(reviewsOnPlace(place)).isZero();
    expect(() -> reviews.getMyPlaceReview(place), HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
    expect(() -> reviews.getMyPoiReview(poi), HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND");
  }

  @Test
  @DisplayName("GET /me/reviews — 편의시설 창구로 쓴 리뷰는 촬영지 리뷰다(target place, 촬영지 id)")
  void myReviewsShowPlaceTarget() {
    long place = place("링크시험 촬영지");
    long poi = poi("링크시험 가게");
    link(poi, place);
    UUID me = member();
    as(me);
    reviews.putMyPoiReview(poi, input(4, null));

    List<MyReview> mine = reviews.listMyReviews(Lang.KO, null, null).getBody().getItems();
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).getTarget().getType()).isEqualTo(ReviewTargetType.PLACE);
    assertThat(mine.get(0).getTarget().getId()).isEqualTo(place);
    assertThat(mine.get(0).getTarget().getName()).isEqualTo("링크시험 촬영지");
  }

  @Test
  @DisplayName("사진첩·상세 — 연결된 편의시설의 사진첩·별점·사진 수는 촬영지의 것(우리 사진 먼저, 그 뒤 리뷰 사진)")
  void galleryAndDetailArePlaces() {
    long place = place("링크시험 촬영지");
    placeImage(place, "https://img.example/link-a.jpg", 0);
    long poi = poi("링크시험 가게");
    link(poi, place);
    UUID me = member();
    as(me);
    String tmp = uploadPhoto("image/jpeg");
    Review r = reviews.putMyPoiReview(poi, input(5, "사진").photoKeys(List.of(tmp))).getBody();
    as(member());
    reviews.putMyPlaceReview(place, input(3, null));

    withoutToken();
    PhotoList viaPoi = reviews.listPoiPhotos(poi, null, null).getBody();
    PhotoList viaPlace = reviews.listPlacePhotos(place, null, null).getBody();
    assertThat(viaPoi.getTotal()).isEqualTo(2).isEqualTo(viaPlace.getTotal());
    assertThat(viaPoi.getItems())
        .extracting(Photo::getSource)
        .containsExactly(PhotoSource.OFFICIAL, PhotoSource.REVIEW);
    assertThat(viaPoi.getItems())
        .extracting(Photo::getUrl)
        .containsExactlyElementsOf(urls(viaPlace));
    assertThat(viaPoi.getItems().get(1).getReviewId()).isEqualTo(r.getId());

    PoiDetail detail = pois.getPoi(poi, Lang.KO, null, null).getBody();
    var placeDetail = places.getPlace(place, Lang.KO, null, null).getBody();
    assertThat(detail.getRating().getCount()).isEqualTo(2);
    assertThat(detail.getRating().getAverage()).isEqualTo(4.0);
    assertThat(detail.getRating().getDistribution())
        .containsExactlyElementsOf(placeDetail.getRating().getDistribution());
    assertThat(detail.getPhotoCount()).isEqualTo(2).isEqualTo(placeDetail.getPhotoCount());
    assertThat(detail.getPhotos())
        .extracting(Photo::getUrl)
        .containsExactlyElementsOf(urls(viaPlace));
  }

  @Test
  @DisplayName("숨긴 촬영지와 연결된 편의시설은 제 리뷰를 쓴다 — 편의시설에 저장되고 촬영지에는 없다")
  void hiddenPlaceLinkIsNotUsed() {
    long hiddenPlace = place("링크시험 숨긴 촬영지");
    hide(hiddenPlace);
    long poi = poi("링크시험 가게");
    link(poi, hiddenPlace);
    UUID me = member();
    as(me);

    reviews.putMyPoiReview(poi, input(4, null));

    assertThat(reviewsOnPoi(poi)).isEqualTo(1);
    assertThat(reviewsOnPlace(hiddenPlace)).isZero();
    assertThat(reviews.listPoiReviews(poi, null, null, null).getBody().getTotal()).isEqualTo(1);
    assertThat(reviews.listPlaceReviews(hiddenPlace, null, null, null).getBody().getTotal())
        .isZero();
  }

  @Test
  @DisplayName("연결되지 않은 편의시설은 그대로 편의시설 리뷰다")
  void unlinkedPoiKeepsOwnReviews() {
    long place = place("링크시험 촬영지");
    long poi = poi("링크시험 가게");
    UUID me = member();
    as(me);

    reviews.putMyPoiReview(poi, input(4, null));

    assertThat(reviewsOnPoi(poi)).isEqualTo(1);
    assertThat(reviewsOnPlace(place)).isZero();
  }

  // ───────────── 404 는 물은 대상의 코드 ─────────────

  @Test
  @DisplayName("없는 편의시설 — 목록·내 리뷰·쓰기·지우기·사진첩·상세 모두 404 POI_NOT_FOUND")
  void unknownPoiIsPoiNotFound() {
    as(member());
    long missing = -1L;

    expect(
        () -> reviews.listPoiReviews(missing, null, null, null),
        HttpStatus.NOT_FOUND,
        "POI_NOT_FOUND");
    expect(() -> reviews.getMyPoiReview(missing), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
    expect(
        () -> reviews.putMyPoiReview(missing, input(5, null)),
        HttpStatus.NOT_FOUND,
        "POI_NOT_FOUND");
    expect(() -> reviews.deleteMyPoiReview(missing), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
    expect(() -> reviews.listPoiPhotos(missing, null, null), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
    expect(() -> pois.getPoi(missing, Lang.KO, null, null), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
  }

  @Test
  @DisplayName("폐업한 연결 편의시설 — 리뷰 창구도 404 POI_NOT_FOUND (촬영지 리뷰로 새지 않는다)")
  void closedLinkedPoiIsPoiNotFound() {
    long place = place("링크시험 촬영지");
    long poi = poi("링크시험 가게");
    link(poi, place);
    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :id").param("id", poi).update();
    as(member());

    expect(
        () -> reviews.listPoiReviews(poi, null, null, null), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
    expect(
        () -> reviews.putMyPoiReview(poi, input(5, null)), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
    expect(() -> reviews.listPoiPhotos(poi, null, null), HttpStatus.NOT_FOUND, "POI_NOT_FOUND");
    assertThat(reviewsOnPlace(place)).isZero();
  }

  @Test
  @DisplayName("없는 촬영지는 여전히 404 PLACE_NOT_FOUND")
  void unknownPlaceIsPlaceNotFound() {
    as(member());
    expect(
        () -> reviews.listPlaceReviews(-1L, null, null, null),
        HttpStatus.NOT_FOUND,
        "PLACE_NOT_FOUND");
    expect(
        () -> reviews.putMyPlaceReview(-1L, input(5, null)),
        HttpStatus.NOT_FOUND,
        "PLACE_NOT_FOUND");
  }

  // ───────────── 도우미 ─────────────

  private static void assertSameList(ReviewList a, ReviewList b) {
    assertThat(a.getTotal()).isEqualTo(b.getTotal());
    assertThat(a.getItems()).extracting(Review::getId).containsExactlyElementsOf(ids(b));
    assertThat(a.getSummary().getCount()).isEqualTo(b.getSummary().getCount());
    assertThat(a.getSummary().getAverage()).isEqualTo(b.getSummary().getAverage());
    assertThat(a.getSummary().getDistribution())
        .containsExactlyElementsOf(b.getSummary().getDistribution());
  }

  private static List<Long> ids(ReviewList l) {
    return l.getItems().stream().map(Review::getId).toList();
  }

  private static List<URI> urls(PhotoList l) {
    return l.getItems().stream().map(Photo::getUrl).toList();
  }

  private static PoiSummary byId(List<PoiSummary> items, long id) {
    return items.stream().filter(p -> p.getId() == id).findFirst().orElseThrow();
  }

  private long place(String ko) {
    long id =
        jdbc.sql(
                "INSERT INTO place (type, geom) VALUES ('시험',"
                    + " ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) RETURNING id")
            .param("lng", LNG)
            .param("lat", LAT)
            .query(Long.class)
            .single();
    createdPlaces.add(id);
    jdbc.sql("INSERT INTO place_i18n (place_id, lang, name) VALUES (:p, 'ko', :n)")
        .param("p", id)
        .param("n", ko)
        .update();
    return id;
  }

  private static void hide(long placeId) {
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :id").param("id", placeId).update();
  }

  private long poi(String name) {
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group)
                VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, '카페', 'food')
                RETURNING id
                """)
            .param("s", "it-link-" + UUID.randomUUID())
            .param("n", name)
            .param("lng", LNG)
            .param("lat", LAT)
            .query(Long.class)
            .single();
    createdPois.add(id);
    return id;
  }

  private static void link(long poiId, long placeId) {
    jdbc.sql("INSERT INTO place_poi_link (poi_id, place_id, method) VALUES (:q, :p, 'manual')")
        .param("q", poiId)
        .param("p", placeId)
        .update();
  }

  private static void placeImage(long placeId, String url, int order) {
    jdbc.sql("INSERT INTO place_image (place_id, url, sort_order) VALUES (:p, :u, :o)")
        .param("p", placeId)
        .param("u", url)
        .param("o", order)
        .update();
  }

  private static long reviewsOnPlace(long placeId) {
    return jdbc.sql("SELECT count(*) FROM review WHERE place_id = :p")
        .param("p", placeId)
        .query(Long.class)
        .single();
  }

  private static long reviewsOnPoi(long poiId) {
    return jdbc.sql("SELECT count(*) FROM review WHERE poi_id = :p")
        .param("p", poiId)
        .query(Long.class)
        .single();
  }

  private String uploadPhoto(String contentType) {
    var response =
        uploads.createUpload(new UploadCreate(UploadPurpose.REVIEW, contentType, 1_000L));
    String key = response.getBody().getKey();
    storage.files.add(key);
    return key;
  }

  private UUID member() {
    UUID id = users.resolve(UUID.randomUUID());
    createdUsers.add(id);
    links.register(id, "google", "it-link-" + UUID.randomUUID(), null, null);
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

  private static void expect(Runnable call, HttpStatus status, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.getStatus()).isEqualTo(status);
              assertThat(e.getCode()).isEqualTo(code);
            });
  }

  private static byte[] filled(int length, byte value) {
    byte[] b = new byte[length];
    Arrays.fill(b, value);
    return b;
  }

  /** 가짜 S3 — ReviewFlowIntegrationTest 의 것과 같은 규칙(있으면 옮기고, 없으면 MissingPhotoException). */
  private static final class FakeStorage implements PhotoStorage {
    final Set<String> files = new HashSet<>();

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
    public void move(String fromKey, String toKey) {
      if (!files.remove(fromKey)) {
        throw new MissingPhotoException(fromKey);
      }
      files.add(toKey);
    }
  }
}
